package com.whispercppdemo

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.AudioDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The transcription engine, shared by the screen and the background service,
 * so a long recording keeps going after leaving the app.
 */
object Engine {
    private const val TAG = "Engine"
    private const val RATE = AudioDecoder.RATE

    /** chunk ~28s (whisper works in 30s windows), cut at the quietest moment of its last 6 seconds */
    private const val TARGET = 28 * RATE
    private const val SEARCH = 6 * RATE
    private const val FRAME = RATE / 50 // 20ms

    data class Job(
        val running: Boolean = false,
        val totalSec: Int = 0,
        val doneSec: Int = 0,
        val elapsedSec: Int = 0,
        val text: String = "",
        val error: String = "",
        val finished: Boolean = false,
    )

    @Volatile
    var context: WhisperContext? = null

    val state = MutableStateFlow(Job())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Mutex()
    private val runLock = Mutex()

    fun modelFiles(app: Context): List<File> = listOf(
        File(File(app.filesDir, "models"), "model.bin"),
        File(app.getExternalFilesDir("models"), "ggml-model.bin"),
    )

    /** Loads the model if the screen has not already (e.g. the app was closed and a share came in). */
    suspend fun ensureLoaded(app: Context): WhisperContext? = loadLock.withLock {
        context ?: modelFiles(app).firstOrNull { it.exists() && it.length() > 0 }?.let { f ->
            try {
                WhisperContext.createContextFromFile(f.absolutePath).also { context = it }
            } catch (e: Exception) {
                Log.w(TAG, e)
                null
            }
        }
    }

    fun clearText() = state.update { if (it.running) it else Job() }

    /** Transcribes [audio] (deleted afterwards). Text appears chunk by chunk in [state]. */
    suspend fun transcribe(app: Context, audio: File, onProgress: (Job) -> Unit) = runLock.withLock {
        val pcm = File(app.cacheDir, "pcm_${audio.name}")
        val started = System.currentTimeMillis()
        state.value = Job(running = true)
        val ticker = scope.launch {
            while (true) {
                state.update { it.copy(elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()) }
                delay(1000)
            }
        }
        try {
            val ctx = ensureLoaded(app) ?: throw IllegalStateException("מודל התמלול לא טעון. פתח את האפליקציה.")
            val total = AudioDecoder.decodeToPcm(audio, pcm)
            audio.delete()
            state.update { it.copy(totalSec = (total / RATE).toInt()) }
            onProgress(state.value)

            var pos = 0L
            val text = StringBuilder()
            while (pos < total) {
                val left = total - pos
                val len = if (left <= TARGET + 2 * RATE) left.toInt() else cutPoint(pcm, pos)
                val samples = AudioDecoder.readPcm(pcm, pos, len)
                val part = ctx.transcribeData(samples, printTimestamp = false).trim()
                if (part.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(part)
                }
                pos += len
                state.update { it.copy(doneSec = (pos / RATE).toInt(), text = text.toString()) }
                onProgress(state.value)
            }
            state.update {
                it.copy(running = false, finished = true, text = text.toString().ifEmpty { "(לא זוהה דיבור)" })
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
            state.update { it.copy(running = false, finished = true, error = e.localizedMessage ?: "שגיאה") }
        } finally {
            ticker.cancel()
            pcm.delete()
            audio.delete()
            state.update { it.copy(elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()) }
            onProgress(state.value)
        }
    }

    /** Length of the next chunk: ends at the quietest 20ms frame between 22 and 28 seconds. */
    private fun cutPoint(pcm: File, pos: Long): Int {
        val from = TARGET - SEARCH
        val window = AudioDecoder.readPcm(pcm, pos + from, SEARCH)
        var best = 0
        var bestEnergy = Float.MAX_VALUE
        var f = 0
        while (f + FRAME <= window.size) {
            var e = 0f
            for (i in f until f + FRAME) e += window[i] * window[i]
            if (e < bestEnergy) {
                bestEnergy = e
                best = f
            }
            f += FRAME
        }
        return from + best + FRAME / 2
    }
}

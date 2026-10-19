package com.whispercppdemo.ui.main

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.AudioDecoder
import com.whispercppdemo.tts.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val LOG_TAG = "MainScreenViewModel"
private const val WHATSAPP = "com.whatsapp"

class MainScreenViewModel(private val application: Application) : ViewModel() {

    var status by mutableStateOf("טוען...")
        private set
    var transcript by mutableStateOf("")
        private set
    var isBusy by mutableStateOf(false)
        private set
    var modelReady by mutableStateOf(false)
        private set
    var ttsText by mutableStateOf("")

    private val modelFile = File(File(application.filesDir, "models").apply { mkdirs() }, "model.bin")
    private var whisperContext: WhisperContext? = null
    private var pendingAudio: Uri? = null
    private val speaker = Speaker(application)

    init {
        viewModelScope.launch { loadModel() }
    }

    // ---------- מודל התמלול ----------

    private suspend fun loadModel() {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            status = "אין עדיין מודל תמלול. לחץ למטה על \"בחר קובץ מודל\" ובחר את ggml-model.bin"
            return
        }
        isBusy = true
        status = "טוען את מודל התמלול (עד דקה)..."
        try {
            whisperContext = withContext(Dispatchers.IO) {
                WhisperContext.createContextFromFile(modelFile.absolutePath)
            }
            modelReady = true
            status = "מוכן. שתף הודעה קולית מווטסאפ לאפליקציה, או בחר הקלטה."
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            status = "שגיאה בטעינת המודל: ${e.localizedMessage}"
        }
        isBusy = false
        runPending()
    }

    fun importModel(uri: Uri) {
        if (isBusy) return
        viewModelScope.launch {
            isBusy = true
            status = "מעתיק את המודל לאפליקציה (כמה דקות, לא לסגור)..."
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(modelFile.parentFile, "model.tmp")
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                    } ?: throw IOException("לא ניתן לפתוח את הקובץ")
                    if (!tmp.renameTo(modelFile)) throw IOException("שמירת המודל נכשלה")
                }
                whisperContext?.release()
                whisperContext = null
                modelReady = false
            } catch (e: Exception) {
                Log.w(LOG_TAG, e)
                status = "שגיאה בהעתקת המודל: ${e.localizedMessage}"
                isBusy = false
                return@launch
            }
            isBusy = false
            loadModel()
        }
    }

    // ---------- תמלול ----------

    fun transcribe(uri: Uri) {
        if (!modelReady || isBusy) {
            pendingAudio = uri
            if (!modelReady && !isBusy) status = "קודם צריך לבחור קובץ מודל, ואז התמלול יתחיל לבד."
            return
        }
        viewModelScope.launch {
            val ctx = whisperContext ?: return@launch
            isBusy = true
            transcript = ""
            status = "מפענח את ההקלטה..."
            try {
                val samples = withContext(Dispatchers.IO) { AudioDecoder.decode(application, uri) }
                val seconds = samples.size / 16_000
                status = "מתמלל $seconds שניות שמע..."
                val start = System.currentTimeMillis()
                val text = ctx.transcribeData(samples, printTimestamp = false).trim()
                val took = (System.currentTimeMillis() - start) / 1000
                transcript = text.ifEmpty { "(לא זוהה דיבור)" }
                status = "התמלול הסתיים ($took שניות)."
            } catch (e: Exception) {
                Log.w(LOG_TAG, e)
                status = "שגיאה בתמלול: ${e.localizedMessage}"
            }
            isBusy = false
            runPending()
        }
    }

    private fun runPending() {
        val next = pendingAudio ?: return
        if (!modelReady) return
        pendingAudio = null
        transcribe(next)
    }

    fun shareTranscript() {
        if (transcript.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        openShare(intent)
    }

    // ---------- הקראה ----------

    fun speakAndShare() {
        val text = ttsText.trim()
        if (text.isEmpty()) {
            status = "כתוב קודם את הטקסט להקראה."
            return
        }
        if (isBusy) return
        viewModelScope.launch {
            isBusy = true
            status = "מכין קובץ שמע..."
            try {
                val file = speaker.synthesizeToM4a(text)
                val uri = FileProvider.getUriForFile(application, "${application.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                openShare(intent)
                status = "הקובץ מוכן. בחר איש קשר בווטסאפ."
            } catch (e: Exception) {
                Log.w(LOG_TAG, e)
                status = "שגיאה בהקראה: ${e.localizedMessage}"
            }
            isBusy = false
        }
    }

    /** פותח ישר את ווטסאפ; אם אינו מותקן, פותח את מסך השיתוף הרגיל. */
    private fun openShare(intent: Intent) {
        val direct = Intent(intent).apply {
            setPackage(WHATSAPP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            application.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            val chooser = Intent.createChooser(intent, "שתף").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            application.startActivity(chooser)
        }
    }

    override fun onCleared() {
        speaker.shutdown()
        runBlocking {
            whisperContext?.release()
            whisperContext = null
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

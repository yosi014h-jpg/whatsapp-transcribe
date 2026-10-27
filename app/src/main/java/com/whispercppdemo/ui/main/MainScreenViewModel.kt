package com.whispercppdemo.ui.main

import android.app.Application
import android.app.DownloadManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val LOG_TAG = "MainScreenViewModel"
private const val WHATSAPP = "com.whatsapp"

/** Hebrew-tuned Whisper turbo by ivrit.ai, in the format whisper.cpp reads. */
/** Fast compressed (q5_0) version of the ivrit.ai model, published by this repo's "Compress model" job. */
private const val MODEL_URL = "https://github.com/yosi014h-jpg/whatsapp-transcribe/releases/download/model-q5_0/ggml-model-he-q5_0.bin"
private const val PREF_DOWNLOAD = "download_id"

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

    /** שמות הקולות העבריים, וקול/גובה/מהירות שנבחרו (נשמרים בין הפעלות). */
    var voices by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedVoice by mutableStateOf<String?>(null)
        private set
    var pitch by mutableStateOf(1.0f)
        private set
    var rate by mutableStateOf(1.0f)
        private set
    private val ttsPrefs = application.getSharedPreferences("tts", android.content.Context.MODE_PRIVATE)
    /** null = no download running; otherwise 0..1, or -1 when the size is not known yet */
    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    private val modelFile = File(File(application.filesDir, "models").apply { mkdirs() }, "model.bin")
    private val prefs = application.getSharedPreferences("model", 0)
    private val downloadDir get() = application.getExternalFilesDir("models")!!.apply { mkdirs() }
    private val downloadedModel get() = File(downloadDir, "ggml-model.bin")
    private val downloadPart get() = File(downloadDir, "ggml-model.part")

    /** A model the user picked wins over the downloaded one. */
    private fun currentModel(): File? =
        listOf(modelFile, downloadedModel).firstOrNull { it.exists() && it.length() > 0L }
    private var whisperContext: WhisperContext? = null
    private var pendingAudio: Uri? = null
    private val speaker = Speaker(application)

    init {
        val running = prefs.getLong(PREF_DOWNLOAD, -1L)
        if (running >= 0 && currentModel() == null) watchDownload(running)
        else viewModelScope.launch { loadModel() }

        selectedVoice = ttsPrefs.getString("voice", null)
        pitch = ttsPrefs.getFloat("pitch", 1.0f)
        rate = ttsPrefs.getFloat("rate", 1.0f)
        applyVoiceSettings()
        viewModelScope.launch { loadVoices() }
        File(application.cacheDir, "tts").deleteRecursively()
    }

    // ---------- בחירת קול ----------

    private suspend fun loadVoices() {
        try {
            voices = speaker.hebrewVoices().map { it.name }
            if (selectedVoice != null && selectedVoice !in voices) selectVoice(null)
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
        }
    }

    private fun applyVoiceSettings() {
        speaker.voiceName = selectedVoice
        speaker.pitch = pitch
        speaker.rate = rate
    }

    fun selectVoice(name: String?) {
        selectedVoice = name
        ttsPrefs.edit().putString("voice", name).apply()
        applyVoiceSettings()
    }

    fun updatePitch(value: Float) {
        pitch = value
        ttsPrefs.edit().putFloat("pitch", value).apply()
        applyVoiceSettings()
    }

    fun updateRate(value: Float) {
        rate = value
        ttsPrefs.edit().putFloat("rate", value).apply()
        applyVoiceSettings()
    }

    fun previewVoice() {
        val text = ttsText.trim().ifEmpty { "שלום, זו בדיקה של הקול שנבחר." }
        viewModelScope.launch {
            try {
                speaker.preview(text)
            } catch (e: Exception) {
                status = "שגיאה בהשמעה: ${e.localizedMessage}"
            }
        }
    }

    fun stopPreview() = speaker.stop()

    // ---------- מודל התמלול ----------

    private suspend fun loadModel() {
        val file = currentModel()
        if (file == null) {
            status = "צריך להוריד את מודל התמלול לעברית. זה קורה פעם אחת (כ-570MB), ומשם הכל עובד בלי אינטרנט."
            return
        }
        isBusy = true
        status = "טוען את מודל התמלול (עד דקה)..."
        try {
            whisperContext = withContext(Dispatchers.IO) {
                WhisperContext.createContextFromFile(file.absolutePath)
            }
            modelReady = true
            status = "מוכן. שתף הודעה קולית מווטסאפ לאפליקציה, או בחר הקלטה."
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            // קובץ פגום (הורדה שנקטעה וכו'): מוחקים, כדי שאפשר יהיה להוריד מחדש בלחיצה.
            file.delete()
            status = "קובץ המודל היה פגום ונמחק. לחץ על \"הורד מודל עברית\" כדי להוריד אותו מחדש."
        }
        isBusy = false
        runPending()
    }

    // ---------- הורדת המודל ----------

    fun downloadModel(allowMobileData: Boolean) {
        if (isBusy || downloadProgress != null) return
        downloadPart.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("מודל תמלול לעברית")
            .setDescription("הורדה חד-פעמית, כ-570MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(application, "models", downloadPart.name)
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        val id = application.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs.edit().putLong(PREF_DOWNLOAD, id).apply()
        watchDownload(id)
    }

    fun cancelDownload() {
        val id = prefs.getLong(PREF_DOWNLOAD, -1L)
        if (id >= 0) application.getSystemService(DownloadManager::class.java).remove(id)
        prefs.edit().remove(PREF_DOWNLOAD).apply()
        downloadPart.delete()
        downloadProgress = null
        status = "ההורדה בוטלה."
    }

    private fun watchDownload(id: Long) {
        downloadProgress = -1f
        status = "מוריד את מודל התמלול. אפשר לסגור את האפליקציה, ההורדה ממשיכה ברקע."
        viewModelScope.launch {
            val dm = application.getSystemService(DownloadManager::class.java)
            while (downloadProgress != null) {
                val row = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null
                        else listOf(
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        )
                    }
                }
                if (row == null) {
                    prefs.edit().remove(PREF_DOWNLOAD).apply()
                    downloadProgress = null
                    status = "ההורדה הופסקה. אפשר להתחיל שוב."
                    return@launch
                }
                val (st, reason, done, total) = row
                when (st.toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(PREF_DOWNLOAD).apply()
                        downloadProgress = null
                        val ok = withContext(Dispatchers.IO) {
                            downloadedModel.delete()
                            downloadPart.renameTo(downloadedModel)
                        }
                        if (ok) loadModel() else status = "ההורדה הסתיימה אבל שמירת המודל נכשלה. נסה שוב."
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(PREF_DOWNLOAD).apply()
                        downloadProgress = null
                        downloadPart.delete()
                        status = "ההורדה נכשלה (קוד $reason). בדוק חיבור לאינטרנט ונסה שוב."
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        status = if (reason.toInt() == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
                            "ממתין ל-WiFi כדי להמשיך את ההורדה."
                        else "ההורדה מושהית, תמשיך כשיהיה חיבור."
                    }
                    else -> {
                        if (total > 0) {
                            downloadProgress = done.toFloat() / total
                            status = "מוריד את מודל התמלול: ${done / 1_048_576} מתוך ${total / 1_048_576} MB. אפשר לסגור את האפליקציה."
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    fun importModel(uri: Uri) = importModel(listOf(uri))

    /** קובץ מודל אחד, או כמה חלקים (part1, part2...) שמחוברים לפי סדר השמות. */
    fun importModel(uris: List<Uri>) {
        if (isBusy || uris.isEmpty()) return
        val ordered = uris.sortedBy { displayName(it) }
        viewModelScope.launch {
            isBusy = true
            status = "מעתיק את המודל לאפליקציה (כמה דקות, לא לסגור)..."
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(modelFile.parentFile, "model.tmp")
                    tmp.outputStream().use { out ->
                        for (part in ordered) {
                            application.contentResolver.openInputStream(part)?.use { input ->
                                input.copyTo(out, 1 shl 20)
                            } ?: throw IOException("לא ניתן לפתוח את הקובץ")
                        }
                    }
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

    private fun displayName(uri: Uri): String =
        application.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.toString()

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

    /** מוחק מהמסך את התמלול ואת טקסט ההקראה. שום דבר לא נשמר באפליקציה. */
    fun clearTranscript() {
        transcript = ""
    }

    fun clearAll() {
        transcript = ""
        ttsText = ""
    }

    fun shareTranscript() {
        if (transcript.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        openShare(intent)
        clearAll()
        status = "נשלח. התמלול נמחק מהאפליקציה."
    }

    /** שולח את התמלול לליאו, שפותח ממנו משימה חדשה. */
    fun sendToLeo() {
        if (transcript.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
            setPackage("com.shalom.leo")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            application.startActivity(intent)
            clearAll()
            status = "נשלח לליאו. התמלול נמחק מהאפליקציה."
        } catch (e: ActivityNotFoundException) {
            status = "ליאו לא מותקן, או שהגרסה שלו ישנה ועוד לא מקבלת טקסט. צריך לעדכן אותו."
        }
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
                clearAll()
                status = "הקובץ מוכן. בחר איש קשר בווטסאפ. הטקסט נמחק מהאפליקציה."
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

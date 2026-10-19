package com.whispercppdemo.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * הופך טקסט לקובץ שמע (m4a) בעזרת מנוע ההקראה המותקן בטלפון, בלי אינטרנט
 * (בתנאי שהקול העברי הורד מראש להגדרות ההקראה).
 */
class Speaker(private val context: Context) {

    private var tts: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        return suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS && created != null) {
                    tts = created
                    if (cont.isActive) cont.resume(created!!)
                } else if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("מנוע ההקראה לא נטען"))
                }
            }
        }
    }

    suspend fun synthesizeToM4a(text: String): File {
        val engine = engine()

        val result = engine.setLanguage(Locale("he", "IL"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("אין קול עברי מותקן במנוע ההקראה (ראה במדריך: התקנת קול עברי)")
        }
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            throw IllegalStateException("הטקסט ארוך מדי. מקסימום ${TextToSpeech.getMaxSpeechInputLength()} תווים")
        }

        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val wav = File(dir, "speech.wav")
        val m4a = File(dir, "message_${System.currentTimeMillis()}.m4a")

        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("ההקראה נכשלה"))
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("ההקראה נכשלה (קוד $errorCode)"))
                    }
                }
            })
            val started = engine.synthesizeToFile(text, Bundle(), wav, id)
            if (started != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resumeWithException(IllegalStateException("לא ניתן להתחיל את ההקראה"))
            }
        }

        withContext(Dispatchers.IO) {
            wavToM4a(wav, m4a)
            wav.delete()
        }
        return m4a
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    /** קידוד WAV (16 ביט) ל-AAC בתוך m4a, פורמט שווטסאפ מקבל בלי בעיה. */
    private fun wavToM4a(wav: File, out: File) {
        val bytes = wav.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (chunkId == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                sampleRate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (chunkId == "data") {
                dataStart = pos + 8
                dataLen = if (size <= 0 || dataStart + size > bytes.size) bytes.size - dataStart else size
                break
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        require(dataStart > 0 && sampleRate > 0 && channels > 0 && bits == 16) {
            "קובץ ההקראה בפורמט לא צפוי"
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        val end = dataStart + dataLen
        var offset = dataStart
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        val n = minOf(buffer.remaining(), end - offset) / bytesPerFrame * bytesPerFrame
                        val ptsUs = ((offset - dataStart).toLong() / bytesPerFrame) * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.put(bytes, offset, n)
                            codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                            offset += n
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxing) muxer.stop()
            muxer.release()
        }
    }
}

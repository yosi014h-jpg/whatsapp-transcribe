package com.whispercppdemo.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * ממיר כל קובץ שמע שאנדרואיד יודע לפענח (כולל הודעות קוליות של ווטסאפ: ogg/opus)
 * למערך דגימות מונו ב-16kHz, הפורמט ש-Whisper מצפה לו.
 */
object AudioDecoder {

    private const val TARGET_RATE = 16_000

    fun decode(context: Context, uri: Uri): FloatArray {
        // מעתיקים קודם לקובץ זמני: ההרשאה לקובץ של ווטסאפ זמנית.
        val tmp = File(context.cacheDir, "incoming_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("לא הצלחתי לפתוח את קובץ השמע")
        try {
            return decodeFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeFile(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("לא נמצא ערוץ שמע בקובץ")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val mono = FloatBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.order(ByteOrder.nativeOrder())
                    if (isFloat) {
                        val fb = buffer.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += fb.get() }
                            mono.add(sum / channels)
                        }
                    } else {
                        val sb = buffer.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += sb.get() / 32768f }
                            mono.add(sum / channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        if (mono.size == 0) throw IllegalStateException("הקובץ ריק או שלא ניתן לפענח אותו")
        return resample(mono.data, mono.size, sampleRate)
    }

    /** המרת קצב דגימה ל-16kHz בממוצע פשוט (מספיק לדיבור). */
    private fun resample(input: FloatArray, n: Int, rate: Int): FloatArray {
        if (rate == TARGET_RATE) return input.copyOf(n)
        val ratio = rate.toDouble() / TARGET_RATE
        val outLen = (n / ratio).toInt()
        return FloatArray(outLen) { i ->
            val start = (i * ratio).toInt().coerceAtMost(n - 1)
            val stop = ((i + 1) * ratio).toInt().coerceIn(start + 1, n)
            var sum = 0f
            for (k in start until stop) sum += input[k]
            (sum / (stop - start)).coerceIn(-1f, 1f)
        }
    }

    private class FloatBuilder {
        var data = FloatArray(16_000 * 60)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }
}

package com.whispercppdemo.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ממיר כל קובץ שמע שאנדרואיד יודע לפענח (כולל הודעות קוליות של ווטסאפ: ogg/opus)
 * לדגימות מונו ב-16kHz, הפורמט ש-Whisper מצפה לו.
 *
 * הדגימות נכתבות לקובץ (16 ביט), לא לזיכרון, כדי שגם הקלטה של כמה שעות תעבוד.
 */
object AudioDecoder {

    const val RATE = 16_000

    /** מעתיק את הקובץ המשותף לאפליקציה: ההרשאה של ווטסאפ לקובץ זמנית. */
    fun copyIn(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IllegalStateException("לא הצלחתי לפתוח את קובץ השמע")
    }

    /** מפענח את [source] ל-[pcmOut] (16kHz, מונו, 16 ביט). מחזיר את מספר הדגימות. */
    fun decodeToPcm(source: File, pcmOut: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalStateException("לא נמצא ערוץ שמע בקובץ")
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = PcmWriter(pcmOut)
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
                    resampler = Resampler(outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
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
                            resampler.push(sum / channels, out)
                        }
                    } else {
                        val sb = buffer.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += sb.get() / 32768f }
                            resampler.push(sum / channels, out)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
            out.close()
        }

        if (out.count == 0L) throw IllegalStateException("הקובץ ריק או שלא ניתן לפענח אותו")
        return out.count
    }

    /** קורא [count] דגימות מתוך קובץ ה-PCM, החל מ-[start]. */
    fun readPcm(pcm: File, start: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        RandomAccessFile(pcm, "r").use { f ->
            f.seek(start * 2)
            f.readFully(bytes)
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { sb.get(it) / 32768f }
    }

    /** המרת קצב דגימה ל-16kHz בזמן הפענוח: ממוצע של הדגימות בכל חלון (מספיק לדיבור). */
    private class Resampler(rate: Int) {
        private val step = rate.toDouble() / RATE
        private var inIndex = 0L
        private var outIndex = 0L
        private var acc = 0f
        private var cnt = 0

        fun push(v: Float, out: PcmWriter) {
            acc += v
            cnt++
            inIndex++
            if (inIndex >= (outIndex + 1) * step) {
                out.write(acc / cnt)
                acc = 0f
                cnt = 0
                outIndex++
            }
        }
    }

    private class PcmWriter(file: File) {
        private val stream = BufferedOutputStream(file.outputStream(), 1 shl 16)
        private val two = ByteArray(2)
        var count = 0L
            private set

        fun write(v: Float) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            two[0] = (s and 0xff).toByte()
            two[1] = ((s shr 8) and 0xff).toByte()
            stream.write(two)
            count++
        }

        fun close() = stream.close()
    }
}

package com.whispercpp.whisper

import java.io.File

/**
 * How many threads to transcribe with: every core that is at least 60% as fast as the fastest one
 * (the big cores), so the slow efficiency cores don't hold the others back.
 */
object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() {
            val n = Runtime.getRuntime().availableProcessors()
            return try {
                val freqs = (0 until n).map {
                    File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }
                val top = freqs.max()
                freqs.count { it >= top * 0.6 }.coerceIn(2, 8)
            } catch (e: Exception) {
                (n - 2).coerceIn(2, 8)
            }
        }
}

package com.whispercppdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a transcription running when the app is in the background,
 * shows its progress, and notifies when it is done.
 */
class TranscribeService : Service() {

    companion object {
        private const val CHANNEL = "transcribe"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
        private const val EXTRA_FILE = "file"

        fun start(context: Context, audio: File) {
            val i = Intent(context, TranscribeService::class.java).putExtra(EXTRA_FILE, audio.absolutePath)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private val active = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val first = progressNotification("מכין את ההקלטה...", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, first)
        }

        val path = intent?.getStringExtra(EXTRA_FILE)
        if (path == null) {
            if (active.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        if (active.getAndIncrement() == 0) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcribe:job")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }

        Engine.scope.launch {
            Engine.transcribe(applicationContext, File(path)) { job ->
                if (job.running && job.totalSec > 0) {
                    notify(
                        ID_PROGRESS,
                        progressNotification("מתמלל: ${job.doneSec} מתוך ${job.totalSec} שניות", job.doneSec, job.totalSec),
                    )
                }
            }
            val job = Engine.state.value
            notify(ID_DONE, doneNotification(if (job.error.isEmpty()) "התמלול מוכן" else "התמלול נכשל", job.error))
            if (active.decrementAndGet() == 0) {
                wakeLock?.takeIf { it.isHeld }?.release()
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "תמלול", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun builder(): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PRIVATE)

    private fun progressNotification(text: String, done: Int, total: Int): Notification =
        builder()
            .setContentTitle("תמלול והקראה")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()

    /** No message text in the notification: it stays only inside the app. */
    private fun doneNotification(title: String, error: String): Notification =
        builder()
            .setContentTitle(title)
            .setContentText(if (error.isEmpty()) "לחץ כדי לראות את הטקסט" else error)
            .setAutoCancel(true)
            .build()

    private fun notify(id: Int, n: Notification) =
        getSystemService(NotificationManager::class.java).notify(id, n)
}

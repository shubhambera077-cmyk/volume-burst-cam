package com.shubhambera.volumeburstcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Foreground service that performs one burst and then stops itself.
 * A visible notification while it runs is an Android requirement for any
 * camera/foreground-service work that continues over the lock screen — it
 * cannot be made invisible, by design of the OS.
 */
class CaptureService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "burst_capture_channel"
        private const val NOTIFICATION_ID = 42
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIFICATION_ID, buildNotification())

        val mode = SettingsStore.getMode(this)
        val captureType = SettingsStore.getCaptureType(this)
        val shotCount = SettingsStore.getShotCount(this)
        val roundGapMs = SettingsStore.getRoundGapMs(this)
        val pairGapMs = SettingsStore.getPairGapMs(this)
        val videoDurationMs = SettingsStore.getVideoDurationMs(this)

        val controller = CameraBurstController(applicationContext, this)

        lifecycleScope.launch {
            try {
                when (captureType) {
                    CaptureType.PHOTO -> controller.runBurst(mode, shotCount, roundGapMs, pairGapMs) { progress ->
                        updateNotification(progress.done, progress.total)
                    }
                    CaptureType.VIDEO -> controller.runVideoCapture(mode, videoDurationMs, pairGapMs) { progress ->
                        updateNotification(progress.done, progress.total)
                    }
                }
            } catch (t: Throwable) {
                // Swallow: a failed capture shouldn't crash the service.
                // (Surfacing this to the user is a good next step — e.g. a
                // one-off notification — once the core flow is confirmed
                // working on-device.)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(done: Int, total: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText("Captured $done / $total")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.capture_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}

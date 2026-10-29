package com.shubhambera.volumeburstcam

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Must be turned on manually by the user under
 * Settings > Accessibility > Downloaded apps > Volume Burst Cam.
 *
 * While armed (SettingsStore.isArmed), this intercepts Volume Down presses
 * so it can count them; when three land within 1.5s, it starts the capture
 * service and resets the counter.
 *
 * Every intercepted press still lowers the volume exactly as it normally
 * would — we manually replay the adjustment via AudioManager before
 * consuming the event — so arming the app doesn't break ordinary volume
 * control for music, calls, etc. Disarmed, nothing is intercepted at all.
 */
class VolumeAccessibilityService : AccessibilityService() {

    private val pressTimestamps = mutableListOf<Long>()
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    companion object {
        private const val WINDOW_MS = 1500L
        private const val TRIGGER_COUNT = 3
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — this service only cares about key events.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        if (!SettingsStore.isArmed(applicationContext)) {
            pressTimestamps.clear()
            return super.onKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            // Still consume the matching ACTION_UP so the system doesn't
            // double-handle it — the actual volume change already happened
            // on ACTION_DOWN below.
            return true
        }

        // Replay the normal hardware-button behavior ourselves before we
        // consume the event, so volume still goes down exactly as if the
        // app weren't armed.
        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)

        val now = System.currentTimeMillis()
        pressTimestamps.add(now)
        pressTimestamps.removeAll { now - it > WINDOW_MS }

        if (pressTimestamps.size >= TRIGGER_COUNT) {
            pressTimestamps.clear()
            startCapture()
        }

        // Consumed: we already handled the volume change manually above,
        // so the system must not process this key a second time.
        return true
    }

    private fun startCapture() {
        val intent = Intent(this, CaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

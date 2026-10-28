package com.shubhambera.volumeburstcam

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Must be turned on manually by the user under
 * Settings > Accessibility > Downloaded apps > Volume Burst Cam.
 *
 * While armed (SettingsStore.isArmed), this intercepts Volume Down presses
 * so it can count them; when three land within 1.5s, it starts the capture
 * service and resets the counter. Volume Down is only consumed (blocked
 * from changing the volume) while armed — disarmed, it passes through
 * untouched.
 */
class VolumeAccessibilityService : AccessibilityService() {

    private val pressTimestamps = mutableListOf<Long>()

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
            // register a lone volume-down press while armed.
            return true
        }

        val now = System.currentTimeMillis()
        pressTimestamps.add(now)
        pressTimestamps.removeAll { now - it > WINDOW_MS }

        if (pressTimestamps.size >= TRIGGER_COUNT) {
            pressTimestamps.clear()
            startCapture()
        }

        // Consumed: prevents the phone's volume from actually changing
        // while armed.
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

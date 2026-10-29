package com.shubhambera.volumeburstcam

import android.content.Context
import android.content.SharedPreferences

enum class CameraMode { BACK, FRONT, BOTH }
enum class CaptureType { PHOTO, VIDEO }

/**
 * Single source of truth for user-adjustable capture settings and the armed
 * state. Backed by SharedPreferences so both the UI process (MainActivity)
 * and the always-on AccessibilityService can read/write it cheaply.
 */
object SettingsStore {
    private const val PREFS = "volume_burst_cam_prefs"

    private const val KEY_ARMED = "armed"
    private const val KEY_MODE = "camera_mode"
    private const val KEY_SHOT_COUNT = "shot_count"
    private const val KEY_ROUND_GAP_MS = "round_gap_ms"
    private const val KEY_PAIR_GAP_MS = "pair_gap_ms"
    private const val KEY_CAPTURE_TYPE = "capture_type"
    private const val KEY_VIDEO_DURATION_MS = "video_duration_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isArmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARMED, false)

    fun setArmed(context: Context, armed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    fun getMode(context: Context): CameraMode =
        when (prefs(context).getString(KEY_MODE, CameraMode.BACK.name)) {
            CameraMode.FRONT.name -> CameraMode.FRONT
            CameraMode.BOTH.name -> CameraMode.BOTH
            else -> CameraMode.BACK
        }

    fun setMode(context: Context, mode: CameraMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getShotCount(context: Context): Int =
        prefs(context).getInt(KEY_SHOT_COUNT, 5)

    fun setShotCount(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SHOT_COUNT, value.coerceIn(1, 20)).apply()
    }

    fun getRoundGapMs(context: Context): Long =
        prefs(context).getInt(KEY_ROUND_GAP_MS, 500).toLong()

    fun setRoundGapMs(context: Context, value: Long) {
        prefs(context).edit().putInt(KEY_ROUND_GAP_MS, value.coerceIn(100, 3000).toInt()).apply()
    }

    fun getPairGapMs(context: Context): Long =
        prefs(context).getInt(KEY_PAIR_GAP_MS, 200).toLong()

    fun setPairGapMs(context: Context, value: Long) {
        prefs(context).edit().putInt(KEY_PAIR_GAP_MS, value.coerceIn(0, 2000).toInt()).apply()
    }

    fun getCaptureType(context: Context): CaptureType =
        when (prefs(context).getString(KEY_CAPTURE_TYPE, CaptureType.PHOTO.name)) {
            CaptureType.VIDEO.name -> CaptureType.VIDEO
            else -> CaptureType.PHOTO
        }

    fun setCaptureType(context: Context, type: CaptureType) {
        prefs(context).edit().putString(KEY_CAPTURE_TYPE, type.name).apply()
        // Video only supports a single camera at a time (no simultaneous
        // back+front recording) — if the user had "Both" selected from
        // photo mode, fall back to Back so video mode starts from a valid
        // selection.
        if (type == CaptureType.VIDEO && getMode(context) == CameraMode.BOTH) {
            setMode(context, CameraMode.BACK)
        }
    }

    fun getVideoDurationMs(context: Context): Long =
        prefs(context).getInt(KEY_VIDEO_DURATION_MS, 3000).toLong()

    fun setVideoDurationMs(context: Context, value: Long) {
        prefs(context).edit().putInt(KEY_VIDEO_DURATION_MS, value.coerceIn(1000, 15000).toInt()).apply()
    }
}

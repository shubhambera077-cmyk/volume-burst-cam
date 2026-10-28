package com.shubhambera.volumeburstcam

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraBurstController"

/**
 * Runs a headless (no preview) photo burst with CameraX, saving every frame
 * straight to MediaStore. Flash is always forced off. No shutter sound is
 * played by CameraX itself (that's a native-camera-app behavior, not
 * something this triggers).
 */
class CameraBurstController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null

    data class Progress(val done: Int, val total: Int)

    suspend fun runBurst(
        mode: CameraMode,
        shotCount: Int,
        roundGapMs: Long,
        pairGapMs: Long,
        onProgress: (Progress) -> Unit = {}
    ) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider = provider

        val totalShots = if (mode == CameraMode.BOTH) shotCount * 2 else shotCount
        var done = 0

        when (mode) {
            CameraMode.BACK -> burstSingle(provider, CameraSelector.DEFAULT_BACK_CAMERA, "BACK", shotCount, roundGapMs) {
                done++; onProgress(Progress(done, totalShots))
            }
            CameraMode.FRONT -> burstSingle(provider, CameraSelector.DEFAULT_FRONT_CAMERA, "FRONT", shotCount, roundGapMs) {
                done++; onProgress(Progress(done, totalShots))
            }
            CameraMode.BOTH -> {
                for (round in 1..shotCount) {
                    captureOnce(provider, CameraSelector.DEFAULT_BACK_CAMERA, "BACK")
                    done++; onProgress(Progress(done, totalShots))

                    if (pairGapMs > 0) delay(pairGapMs)

                    captureOnce(provider, CameraSelector.DEFAULT_FRONT_CAMERA, "FRONT")
                    done++; onProgress(Progress(done, totalShots))

                    if (round < shotCount) {
                        val remaining = (roundGapMs - pairGapMs).coerceAtLeast(0)
                        if (remaining > 0) delay(remaining)
                    }
                }
            }
        }

        provider.unbindAll()
    }

    private suspend fun burstSingle(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String,
        shotCount: Int,
        roundGapMs: Long,
        onShot: () -> Unit
    ) {
        for (round in 1..shotCount) {
            captureOnce(provider, selector, label)
            onShot()
            if (round < shotCount && roundGapMs > 0) delay(roundGapMs)
        }
    }

    private suspend fun captureOnce(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String
    ) {
        provider.unbindAll()

        val imageCapture = ImageCapture.Builder()
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "VBC_${label}_$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VolumeBurstCam")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        suspendCancellableCoroutine<Unit> { cont ->
            imageCapture.takePicture(
                outputOptions,
                androidx.core.content.ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "Saved $label frame: ${output.savedUri}")
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture failed for $label", exception)
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                }
            )
        }
    }
}

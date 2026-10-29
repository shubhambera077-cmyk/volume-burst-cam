package com.shubhambera.volumeburstcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
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
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Progress(val done: Int, val total: Int)

    /**
     * Records one clip (Back or Front) of [durationMs]. "Both" isn't exposed
     * in the UI for video — simultaneous dual-camera recording isn't
     * reliable across devices — but if it's ever passed in, it falls back
     * to Back then Front sequentially, same as the photo burst does.
     */
    suspend fun runVideoCapture(
        mode: CameraMode,
        durationMs: Long,
        pairGapMs: Long,
        onProgress: (Progress) -> Unit = {}
    ) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider = provider

        val totalClips = if (mode == CameraMode.BOTH) 2 else 1
        var done = 0

        suspend fun recordClip(selector: CameraSelector, label: String) {
            recordOnce(provider, selector, label, durationMs)
            done++; onProgress(Progress(done, totalClips))
        }

        when (mode) {
            CameraMode.BACK -> recordClip(CameraSelector.DEFAULT_BACK_CAMERA, "BACK")
            CameraMode.FRONT -> recordClip(CameraSelector.DEFAULT_FRONT_CAMERA, "FRONT")
            CameraMode.BOTH -> {
                recordClip(CameraSelector.DEFAULT_BACK_CAMERA, "BACK")
                if (pairGapMs > 0) delay(pairGapMs)
                recordClip(CameraSelector.DEFAULT_FRONT_CAMERA, "FRONT")
            }
        }

        provider.unbindAll()
    }

    @SuppressLint("MissingPermission") // Checked manually via hasMicPermission() below.
    private suspend fun recordOnce(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String,
        durationMs: Long
    ) {
        provider.unbindAll()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        provider.bindToLifecycle(lifecycleOwner, selector, videoCapture)

        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VBC_${label}_$name")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VolumeBurstCam")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        suspendCancellableCoroutine<Unit> { cont ->
            var pending = videoCapture.output.prepareRecording(context, outputOptions)
            if (micGranted) {
                pending = pending.withAudioEnabled()
            }

            var recording: Recording? = null
            recording = pending
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (cont.isActive) {
                            if (event.hasError()) {
                                Log.e(TAG, "Video recording error for $label: ${event.error}")
                                cont.resumeWithException(
                                    RuntimeException("Video recording failed (code ${event.error})")
                                )
                            } else {
                                Log.d(TAG, "Saved $label video: ${event.outputResults.outputUri}")
                                cont.resume(Unit)
                            }
                        }
                    }
                }

            mainHandler.postDelayed({ recording?.stop() }, durationMs)
            cont.invokeOnCancellation { recording?.stop() }
        }
    }

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

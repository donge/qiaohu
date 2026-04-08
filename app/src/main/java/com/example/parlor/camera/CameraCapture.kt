package com.example.parlor.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "CameraCapture"

/**
 * CameraX wrapper that provides a live preview and on-demand JPEG frame capture.
 *
 * Usage:
 *   val cam = CameraCapture(context, lifecycleOwner, previewView)
 *   cam.start()
 *   val jpegBytes: ByteArray? = cam.captureJpeg()  // called each turn
 *
 * Mirrors server.py: `image_path = save_temp(base64.b64decode(msg["image"]), ".jpg")`
 * — here we produce the bytes directly instead of receiving them over WebSocket.
 *
 * The camera is optional (user may decline permission).  [captureJpeg] returns
 * null when the camera is unavailable or the capture fails.
 */
class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    private var imageCapture: ImageCapture? = null
    // Capture callbacks run on a background thread to avoid blocking the UI.
    private val captureExecutor = Executors.newSingleThreadExecutor()
    // CameraX provider callbacks and PreviewView must be accessed on the main thread.
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) }

    /**
     * Bind camera to the lifecycle and start the live preview.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(80)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
                Log.i(TAG, "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
                imageCapture = null
            }
        }, mainExecutor)
    }

    /**
     * Capture the current frame and return it as a JPEG [ByteArray].
     *
     * This is a suspend function that completes when the capture is saved to a
     * temp file and the bytes are read back.  The temp file is deleted afterwards.
     *
     * Returns null if the camera is not available or capture fails.
     */
    suspend fun captureJpeg(): ByteArray? {
        val capture = imageCapture ?: run {
            Log.w(TAG, "captureJpeg() called but camera is not ready")
            return null
        }

        val tmpFile = File.createTempFile("parlor_frame_", ".jpg", context.cacheDir)

        return suspendCancellableCoroutine { cont ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tmpFile).build()

            capture.takePicture(
                outputOptions,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val bytes = try {
                            tmpFile.readBytes()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read temp JPEG: ${e.message}")
                            null
                        } finally {
                            tmpFile.delete()
                        }
                        cont.resume(bytes)
                    }

                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "Image capture failed: ${e.message}")
                        tmpFile.delete()
                        cont.resume(null)
                    }
                },
            )

            cont.invokeOnCancellation { tmpFile.delete() }
        }
    }

    /** Release executor.  CameraX lifecycle is managed by [lifecycleOwner]. */
    fun shutdown() {
        captureExecutor.shutdown()
    }
}

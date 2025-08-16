package com.v2ray.ang.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private val cameraExecutor: Executor by lazy { Dispatchers.IO.asExecutor() }

    /**
     * Takes photos with front and back cameras if available and permission is granted.
     * @return A list of [File] objects for the captured images.
     */
    suspend fun takePhotos(): List<File> {
        if (!hasCameraPermission()) {
            Log.e("CameraManager", "Camera permission not granted.")
            // In a real app, we would trigger a permission request here.
            // For now, we just log and return empty. The caller (Activity) will handle the request.
            return emptyList()
        }

        val cameraProvider = getCameraProvider()
        val photoFiles = mutableListOf<File>()

        // Take photo with back camera
        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        if (cameraProvider.hasCamera(backCameraSelector)) {
            try {
                val backPhotoFile = takePhotoWithCamera(cameraProvider, backCameraSelector, "BACK")
                photoFiles.add(backPhotoFile)
                Log.d("CameraManager", "Back camera photo saved to ${backPhotoFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to take photo with back camera", e)
            }
        } else {
            Log.w("CameraManager", "No back camera available.")
        }

        // Take photo with front camera
        val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        if (cameraProvider.hasCamera(frontCameraSelector)) {
            try {
                val frontPhotoFile = takePhotoWithCamera(cameraProvider, frontCameraSelector, "FRONT")
                photoFiles.add(frontPhotoFile)
                Log.d("CameraManager", "Front camera photo saved to ${frontPhotoFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to take photo with front camera", e)
            }
        } else {
            Log.w("CameraManager", "No front camera available.")
        }

        return photoFiles
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener({
                    continuation.resume(future.get())
                }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun takePhotoWithCamera(
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector,
        prefix: String
    ): File {
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // A LifecycleOwner is required to bind CameraX use cases.
        // We create a fake one that lives just long enough for the capture.
        val lifecycleOwner = FakeLifecycleOwner()

        withContext(Dispatchers.Main) {
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
        }

        try {
            return suspendCancellableCoroutine { continuation ->
                val photoFile = createFile(context.cacheDir, "yyyy-MM-dd-HH-mm-ss-SSS", "_${prefix}.jpg")
                val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputFileOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Log.d("CameraManager", "Photo capture succeeded: ${outputFileResults.savedUri}")
                            continuation.resume(photoFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraManager", "Photo capture failed: ${exception.message}", exception)
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }
        } finally {
            // Unbind use cases and destroy the lifecycle to release the camera
            withContext(Dispatchers.Main) {
                cameraProvider.unbindAll()
                lifecycleOwner.destroy()
            }
        }
    }

    private fun createFile(baseFolder: File, format: String, extension: String) =
        File(
            baseFolder,
            SimpleDateFormat(format, Locale.US).format(System.currentTimeMillis()) + extension
        )
}

/**
 * A minimal LifecycleOwner implementation for running CameraX operations in the background,
 * outside of an Activity or Fragment. This is necessary because CameraX's `bindToLifecycle`
 * requires a LifecycleOwner.
 */
private class FakeLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    init {
        // Start the lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        // End the lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}

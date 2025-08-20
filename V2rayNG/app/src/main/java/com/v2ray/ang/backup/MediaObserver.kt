package com.v2ray.ang.backup

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.util.Log

class MediaObserver(
    private val context: Context,
    handler: Handler
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "MediaObserver"
        // Debounce period in milliseconds to avoid rapid-fire scans
        private const val DEBOUNCE_PERIOD_MS = 5000L
    }

    private var lastScanTime = 0L

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScanTime < DEBOUNCE_PERIOD_MS) {
            // Debounced
            Log.d(TAG, "onChange debounced. Skipping scan.")
            return
        }

        lastScanTime = currentTime
        Log.d(TAG, "Media change detected. Triggering a scan.")
        BackupManager.scanForNewMedia(context)
    }
}

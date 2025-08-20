package com.v2ray.ang.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log

class BackupQueueDao(context: Context) {

    companion object {
        private const val TAG = "BackupQueueDao"
        @Volatile
        private var INSTANCE: BackupQueueDao? = null

        fun getInstance(context: Context): BackupQueueDao {
            return INSTANCE ?: synchronized(this) {
                val instance = BackupQueueDao(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val dbHelper = BackupDBHelper(context)

    fun addUri(uri: Uri) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(BackupDBHelper.COLUMN_URI, uri.toString())
        }
        try {
            val id = db.insertWithOnConflict(BackupDBHelper.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) {
                Log.d(TAG, "URI already exists in queue: $uri")
            } else {
                Log.i(TAG, "Added URI to queue: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding URI to queue", e)
        } finally {
            db.close()
        }
    }

    fun getNextUri(): Uri? {
        val db = dbHelper.readableDatabase
        var uri: Uri? = null
        try {
            val cursor = db.query(
                BackupDBHelper.TABLE_NAME,
                arrayOf(BackupDBHelper.COLUMN_URI),
                null, null, null, null,
                "${BackupDBHelper.COLUMN_ID} ASC", // Get the oldest one first (FIFO)
                "1"
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val uriString = it.getString(it.getColumnIndexOrThrow(BackupDBHelper.COLUMN_URI))
                    uri = Uri.parse(uriString)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next URI from queue", e)
        } finally {
            db.close()
        }
        return uri
    }

    fun deleteUri(uri: Uri) {
        val db = dbHelper.writableDatabase
        try {
            val selection = "${BackupDBHelper.COLUMN_URI} = ?"
            val selectionArgs = arrayOf(uri.toString())
            val deletedRows = db.delete(BackupDBHelper.TABLE_NAME, selection, selectionArgs)
            if (deletedRows > 0) {
                Log.i(TAG, "Deleted URI from queue: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting URI from queue", e)
        } finally {
            db.close()
        }
    }

    fun getQueueSize(): Int {
        val db = dbHelper.readableDatabase
        var count = 0
        try {
            db.rawQuery("SELECT COUNT(*) FROM ${BackupDBHelper.TABLE_NAME}", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    count = cursor.getInt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting queue size", e)
        } finally {
            db.close()
        }
        return count
    }
}

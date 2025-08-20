package com.v2ray.ang.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BackupDBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "v2ray_backup.db"
        const val TABLE_NAME = "backup_queue"
        const val COLUMN_ID = "id"
        const val COLUMN_URI = "uri"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSQL = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URI TEXT NOT NULL UNIQUE
            )
        """.trimIndent()
        db.execSQL(createTableSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For simple upgrades, we can just drop and recreate the table.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }
}

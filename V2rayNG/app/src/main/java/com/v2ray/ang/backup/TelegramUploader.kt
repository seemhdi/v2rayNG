package com.v2ray.ang.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class TelegramUploader(private val context: Context) {

    companion object {
        private const val TAG = "TelegramUploader"

        // =====================================================================================
        // IMPORTANT: Fill in your Bot Token and Chat ID here.
        // =====================================================================================
        private const val BOT_TOKEN = "YOUR_BOT_TOKEN_HERE"
        private const val CHAT_ID = "YOUR_CHAT_ID_HERE"
        // =====================================================================================

        private const val API_URL_TEMPLATE = "https://api.telegram.org/bot%s/%s"
    }

    fun uploadFile(uri: Uri): Boolean {
        if (BOT_TOKEN.startsWith("YOUR_") || CHAT_ID.startsWith("YOUR_")) {
            Log.e(TAG, "Bot Token or Chat ID is not configured. Aborting upload.")
            return false
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val (endpoint, fieldName) = when {
            mimeType.startsWith("image/") -> "sendPhoto" to "photo"
            mimeType.startsWith("video/") -> "sendVideo" to "video"
            else -> {
                Log.w(TAG, "Unsupported MIME type: $mimeType. Skipping upload for URI: $uri")
                return false
            }
        }

        val url = URL(API_URL_TEMPLATE.format(BOT_TOKEN, endpoint))
        val boundary = "Boundary-${UUID.randomUUID()}"
        var connection: HttpURLConnection? = null

        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { outputStream ->
                // Write Chat ID part
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                outputStream.writeBytes("$CHAT_ID\r\n")

                // Write file part
                val fileName = getFileName(uri)
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
                outputStream.writeBytes("Content-Type: $mimeType\r\n\r\n")

                context.contentResolver.openInputStream(uri)?.use { fileInputStream ->
                    fileInputStream.copyTo(outputStream)
                }

                outputStream.writeBytes("\r\n")
                outputStream.writeBytes("--$boundary--\r\n")
            }

            val responseCode = connection.responseCode
            val responseMessage = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream.bufferedReader().readText()
            }
            Log.d(TAG, "Telegram API Response ($responseCode): $responseMessage")

            return responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file to Telegram", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex != -1) {
                       result = cursor.getString(colIndex)
                    }
                }
            }
        }
        return result ?: uri.lastPathSegment ?: "unknown_file"
    }
}

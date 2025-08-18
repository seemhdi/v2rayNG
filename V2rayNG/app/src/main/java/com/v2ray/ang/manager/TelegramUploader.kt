package com.v2ray.ang.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TelegramUploader {

    private val client = OkHttpClient()
    private const val API_URL_TEMPLATE = "https://api.telegram.org/bot%s/%s"

    /**
     * Sends a photo to a specified Telegram chat using a bot.
     *
     * @param token The Telegram bot token.
     * @param chatId The target chat ID.
     * @param photoFile The image file to send.
     * @param caption An optional caption for the photo.
     * @return `true` if the photo was sent successfully, `false` otherwise.
     */
    suspend fun sendPhoto(token: String, chatId: String, photoFile: File, caption: String? = null): Boolean {
        if (token.isBlank() || chatId.isBlank()) {
            Log.e("TelegramUploader", "Token or Chat ID is blank. Aborting upload.")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestBodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart(
                        "photo",
                        photoFile.name,
                        photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    )

                caption?.let {
                    requestBodyBuilder.addFormDataPart("caption", it)
                }

                val request = Request.Builder()
                    .url(API_URL_TEMPLATE.format(token, "sendPhoto"))
                    .post(requestBodyBuilder.build())
                    .build()

                val response = client.newCall(request).execute()
                response.use { // Ensures the response body is closed.
                    if (it.isSuccessful) {
                        Log.d("TelegramUploader", "Photo uploaded successfully.")
                        true
                    } else {
                        Log.e("TelegramUploader", "Failed to upload photo. Code: ${it.code}, Message: ${it.message}, Body: ${it.body?.string()}")
                        false
                    }
                }
            } catch (e: IOException) {
                Log.e("TelegramUploader", "IOException during photo upload.", e)
                false
            }
        }
    }

    /**
     * Gets updates from the Telegram Bot API.
     *
     * @param token The Telegram bot token.
     * @param offset The ID of the first update to be returned.
     * @return A string containing the JSON response from the API, or null on failure.
     */
    suspend fun getUpdates(token: String, offset: Long): String? {
        if (token.isBlank()) {
            Log.e("TelegramUploader", "Token is blank. Aborting getUpdates.")
            return null
        }

        val url = API_URL_TEMPLATE.format(token, "getUpdates") + "?offset=$offset&timeout=30"
        val request = Request.Builder().url(url).get().build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        it.body?.string()
                    } else {
                        Log.e("TelegramUploader", "Failed to get updates. Code: ${it.code}, Message: ${it.message}")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.e("TelegramUploader", "IOException during getUpdates.", e)
                null
            }
        }
    }
}

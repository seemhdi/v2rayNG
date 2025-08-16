package com.v2ray.ang.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

object TelegramUploader {

    private val client = OkHttpClient()
    private const val TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendPhoto"

    /**
     * Sends a photo to a specified Telegram chat using a bot.
     *
     * @param token The Telegram bot token.
     * @param chatId The target chat ID.
     * @param photoFile The image file to send.
     * @return `true` if the photo was sent successfully, `false` otherwise.
     */
    suspend fun sendPhoto(token: String, chatId: String, photoFile: File): Boolean {
        if (token.isBlank() || chatId.isBlank()) {
            Log.e("TelegramUploader", "Token or Chat ID is blank. Aborting upload.")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart(
                        "photo",
                        photoFile.name,
                        photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(TELEGRAM_API_URL.format(token))
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    // It's important to consume the response body to release resources
                    val responseBody = response.body?.string()
                    Log.d("TelegramUploader", "Photo uploaded successfully. Response: $responseBody")
                    response.close()
                    true
                } else {
                    val responseBody = response.body?.string()
                    Log.e("TelegramUploader", "Failed to upload photo. Code: ${response.code}, Message: ${response.message}, Body: $responseBody")
                    response.close()
                    false
                }
            } catch (e: IOException) {
                Log.e("TelegramUploader", "IOException during photo upload.", e)
                false
            } catch (e: Exception) {
                Log.e("TelegramUploader", "An unexpected error occurred during photo upload.", e)
                false
            }
        }
    }
}

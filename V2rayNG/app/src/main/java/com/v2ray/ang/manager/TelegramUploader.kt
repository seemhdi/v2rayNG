package com.v2ray.ang.manager

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

object TelegramUploader {

    private val client = OkHttpClient()
    private const val API_URL = "https://api.telegram.org/bot%s/sendPhoto"

    suspend fun sendPhoto(token: String, chatId: String, photoFile: File): Boolean {
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
                    .url(API_URL.format(token))
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("TelegramUploader", "sendPhoto failed: ${response.body?.string()}")
                    }
                    response.isSuccessful
                }
            } catch (e: IOException) {
                Log.e("TelegramUploader", "sendPhoto failed", e)
                false
            }
        }
    }
}

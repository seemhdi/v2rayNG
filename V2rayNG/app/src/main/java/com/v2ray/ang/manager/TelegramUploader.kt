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
    private const val API_URL_TEMPLATE = "https://api.telegram.org/bot%s/%s"

    suspend fun sendPhoto(token: String, chatId: String, photoFile: File, caption: String? = null): Boolean {
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
                caption?.let { requestBodyBuilder.addFormDataPart("caption", it) }

                val request = Request.Builder()
                    .url(API_URL_TEMPLATE.format(token, "sendPhoto"))
                    .post(requestBodyBuilder.build())
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

    suspend fun getUpdates(token: String, offset: Long): String? {
        val url = API_URL_TEMPLATE.format(token, "getUpdates") + "?offset=$offset&timeout=30"
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()
                    } else {
                        Log.e("TelegramUploader", "getUpdates failed: ${response.body?.string()}")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.e("TelegramUploader", "getUpdates failed", e)
                null
            }
        }
    }
}

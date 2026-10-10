package com.zenimusic.app.data.repo

import com.zenimusic.app.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Kirim form "Request Fitur" ke bot Telegram Khalid.
 * Token & chat ID berasal dari Secrets.kt (build-time, tidak di-commit).
 */
object TelegramSender {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        Secrets.TG_BOT_TOKEN.isNotBlank() && Secrets.TG_CHAT_ID.isNotBlank()

    /**
     * @return true bila terkirim (HTTP 200 + ok:true dari Telegram).
     */
    suspend fun sendRequest(
        title: String,
        category: String,
        description: String,
        appVersion: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val text = buildString {
                appendLine("Request ZeniMusic v$appVersion")
                appendLine()
                appendLine("Kategori: $category")
                appendLine("Judul: $title")
                appendLine()
                appendLine(description.trim())
            }.take(3500)
            val form = FormBody.Builder()
                .add("chat_id", Secrets.TG_CHAT_ID)
                .add("text", text)
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot${Secrets.TG_BOT_TOKEN}/sendMessage")
                .post(form)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string() ?: return@withContext false
                body.contains("\"ok\":true")
            }
        } catch (_: Exception) {
            false
        }
    }
}

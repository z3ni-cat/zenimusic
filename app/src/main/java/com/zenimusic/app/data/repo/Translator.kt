package com.zenimusic.app.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Terjemahan lirik via MyMemory (gratis, tanpa API key).
 * Seluruh baris lirik dikirim dalam SATU request (digabung \n) agar hemat
 * kuota, dan hasilnya di-cache permanen di database per lagu.
 */
object Translator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val memCache = mutableMapOf<String, List<String>>()

    /**
     * Terjemahkan [lines] ke [target] (default "id").
     * @return daftar terjemahan sejajar dengan [lines], atau null bila gagal.
     */
    suspend fun translateLines(
        videoId: String,
        lines: List<String>,
        target: String = "id"
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext null
        memCache[videoId]?.let { cached ->
            if (cached.size == lines.size) return@withContext cached
        }
        try {
            val joined = lines.joinToString("\n")
            // MyMemory batas ~500 karakter per query anonim; pecah bila perlu.
            val chunks = chunkSmart(joined, 450)
            val translatedChunks = mutableListOf<String>()
            for (chunk in chunks) {
                val url = "https://api.mymemory.translated.net/get?q=" +
                    URLEncoder.encode(chunk, "UTF-8") +
                    "&langpair=auto|" + URLEncoder.encode(target, "UTF-8")
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZeniMusic")
                    .build()
                val body = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string() ?: return@withContext null
                }
                val translated = JSONObject(body)
                    .optJSONObject("responseData")
                    ?.optString("translatedText")
                    ?.replace("&#39;", "'")
                    ?.replace("&quot;", "\"")
                    ?.replace("&amp;", "&")
                if (translated.isNullOrEmpty()) return@withContext null
                translatedChunks.add(translated)
            }
            val all = translatedChunks.joinToString("\n").split("\n")
            // Samakan jumlah baris; bila meleset sedikit, pad dengan string kosong.
            val result = lines.indices.map { i -> all.getOrElse(i) { "" }.trim() }
            memCache[videoId] = result
            result
        } catch (_: Exception) {
            null
        }
    }

    /** Pecah teks per baris agar tidak memotong kalimat di tengah. */
    private fun chunkSmart(text: String, maxLen: Int): List<String> {
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in lines) {
            if (cur.length + line.length + 1 > maxLen && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append("\n")
            cur.append(line)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(text) }
    }

    fun clearCache() {
        memCache.clear()
    }
}

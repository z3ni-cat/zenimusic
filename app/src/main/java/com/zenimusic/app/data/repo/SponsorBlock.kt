package com.zenimusic.app.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Lewati otomatis segmen non-musik (sponsor, intro, outro, promosi diri)
 * pada lagu yang di-stream dari YouTube, memakai database komunitas
 * SponsorBlock (https://sponsor.ajay.app) — gratis, tanpa API key.
 */
object SponsorBlock {

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, List<Segment>>()

    var enabled: Boolean = true

    /**
     * Kategori yang aman dilewati untuk musik. "music_offtopic" TIDAK
     * disertakan agar tidak memotong bagian lagu yang tidak biasa.
     */
    private const val CATEGORIES =
        "[\"sponsor\",\"intro\",\"outro\",\"selfpromo\",\"interaction_reminder\",\"preview\"]"

    /** Ambil segmen untuk videoId (di-cache per sesi). Kosong bila tidak ada/gagal. */
    suspend fun segments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        cache[videoId]?.let { return@withContext it }
        val out = mutableListOf<Segment>()
        try {
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=" +
                URLEncoder.encode(videoId, "UTF-8") + "&categories=" +
                URLEncoder.encode(CATEGORIES, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ZeniMusic")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext emptyList<Segment>()
                if (!resp.isSuccessful) return@withContext emptyList<Segment>()
                resp.body?.string() ?: return@withContext emptyList<Segment>()
            }
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val seg = obj.optJSONArray("segment") ?: continue
                if (seg.length() < 2) continue
                val s = (seg.optDouble(0, -1.0) * 1000).toLong()
                val e = (seg.optDouble(1, -1.0) * 1000).toLong()
                if (s < 0 || e <= s) continue
                out.add(Segment(s, e, obj.optString("category")))
            }
        } catch (_: Exception) {
            // Gagal fetch = putar normal, jangan ganggu user.
        }
        cache[videoId] = out
        out
    }

    /**
     * Jika [posMs] masuk ke dalam segmen, kembalikan posisi target
     * (sedikit setelah akhir segmen). Null bila tidak perlu skip.
     */
    fun skipTarget(segments: List<Segment>, posMs: Long): Long? {
        for (seg in segments) {
            // Hanya skip bila sudah masuk minimal 500ms ke dalam segmen
            // (menghindari lompatan saat user sengaja seek ke sana).
            if (posMs >= seg.startMs + 500 && posMs < seg.endMs - 300) {
                return seg.endMs + 50
            }
        }
        return null
    }

    fun clearCache() {
        cache.clear()
    }
}

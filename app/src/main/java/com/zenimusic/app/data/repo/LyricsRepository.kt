package com.zenimusic.app.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Satu baris lirik dengan timestamp (ms). */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Lirik sinkron dari LRCLIB (gratis, tanpa API key).
 * https://lrclib.net/docs
 */
object LyricsRepository {
    private val http = OkHttpClient()
    private val lrcLine = Regex("""\[(\d+):(\d+)[.:](\d+)\]\s*(.*)""")

    /** Ambil lirik sinkron; kosong bila tidak ditemukan. */
    suspend fun getLyrics(artist: String, title: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val cleanArtist = cleanName(artist)
            val cleanTitle = cleanTitle(title)
            if (cleanArtist.isEmpty() || cleanTitle.isEmpty()) return@withContext emptyList()

            // 1. Coba endpoint /get (paling akurat, pakai durasi)
            val q1 = "artist_name=${enc(cleanArtist)}&track_name=${enc(cleanTitle)}" +
                (if (durationSec > 0) "&duration=$durationSec" else "")
            fetch("https://lrclib.net/api/get?$q1")?.let { parse(it) }
                ?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

            // 2. Fallback: /search
            val q2 = "q=${enc("$cleanArtist $cleanTitle")}"
            fetch("https://lrclib.net/api/search?$q2")?.let { body ->
                try {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lines = parse(obj.optString("syncedLyrics"))
                        if (lines.isNotEmpty()) return@withContext lines
                    }
                } catch (_: Exception) { }
            }
            emptyList()
        }

    private fun fetch(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ZeniMusic/2.4.2")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) { null }
    }

    private fun parse(synced: String): List<LyricLine> {
        if (synced.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (raw in synced.lines()) {
            val m = lrcLine.matchEntire(raw.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            var frac = m.groupValues[3]
            // normalisasi fraksi ke milidetik (2 digit = centisecond, 3 = ms)
            val ms = when (frac.length) {
                2 -> frac.toLongOrNull()?.times(10) ?: 0L
                3 -> frac.toLongOrNull() ?: 0L
                else -> 0L
            }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            out.add(LyricLine(min * 60_000 + sec * 1_000 + ms, text))
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parse(obj: JSONObject): List<LyricLine> =
        parse(obj.optString("syncedLyrics"))

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Bersihkan nama artis dari embel-embel channel. */
    private fun cleanName(s: String): String =
        s.replace(Regex("""\s*-\s*Topic$"""), "")
            .replace(Regex("""(?i)\s*official\s*$"""), "")
            .trim()

    /** Bersihkan judul dari embel-embel video YouTube. */
    private fun cleanTitle(s: String): String =
        s.replace(Regex("""\s*[\(\[][^)\]]*(official|music video|mv|lyric|audio|hd|4k)[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*.*$"""), "")
            .trim()
}

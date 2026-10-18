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

    /** Folder cache lirik di HP (diisi ZeniMusicApp) — lirik tersimpan dibuka seketika. */
    @Volatile var cacheDir: java.io.File? = null

    private fun cacheFile(artist: String, title: String, durationSec: Long): java.io.File? {
        val dir = cacheDir ?: return null
        val key = "$artist|$title|$durationSec".lowercase()
        val hash = java.security.MessageDigest.getInstance("MD5")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$hash.txt")
    }

    private fun readCache(f: java.io.File): List<LyricLine> = try {
        if (!f.isFile) emptyList() else f.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else LyricLine(line.substring(0, tab).toLong(), line.substring(tab + 1))
        }
    } catch (e: Exception) { emptyList() }

    private fun writeCache(f: java.io.File, lines: List<LyricLine>) {
        try {
            f.parentFile?.mkdirs()
            f.writeText(lines.joinToString("\n") { "${it.timeMs}\t${it.text}" })
        } catch (e: Exception) { /* cache gagal tidak fatal */ }
    }

    /** Ambil lirik sinkron; kosong bila tidak ditemukan. */
    suspend fun getLyrics(artist: String, title: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val cleanArtist = cleanName(artist)
            val cleanTitle = cleanTitle(title)
            if (cleanArtist.isEmpty() || cleanTitle.isEmpty()) return@withContext emptyList()

            // 0. Cache di HP dulu — instan & hemat kuota
            val cf = cacheFile(cleanArtist, cleanTitle, durationSec)
            if (cf != null) {
                val cached = readCache(cf)
                if (cached.isNotEmpty()) return@withContext cached
            }

            // 1. Coba endpoint /get (paling akurat, pakai durasi)
            val q1 = "artist_name=${enc(cleanArtist)}&track_name=${enc(cleanTitle)}" +
                (if (durationSec > 0) "&duration=$durationSec" else "")
            fetch("https://lrclib.net/api/get?$q1")?.let { parse(it) }
                ?.takeIf { it.isNotEmpty() }?.let {
                    if (cf != null) writeCache(cf, it)
                    return@withContext it
                }

            // 2. Fallback: /search
            val q2 = "q=${enc("$cleanArtist $cleanTitle")}"
            fetch("https://lrclib.net/api/search?$q2")?.let { body ->
                try {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lines = parse(obj.optString("syncedLyrics"))
                        if (lines.isNotEmpty()) {
                            if (cf != null) writeCache(cf, lines)
                            return@withContext lines
                        }
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

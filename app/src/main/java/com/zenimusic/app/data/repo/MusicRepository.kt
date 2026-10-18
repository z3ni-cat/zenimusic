package com.zenimusic.app.data.repo

import com.zenimusic.app.data.db.HistoryEntity
import com.zenimusic.app.data.db.ZeniDb
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.model.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream

class MusicRepository(
    private val dao: ZeniDb,
    private val http: OkHttpClient,
    private val filesDir: File
) {
    private val service get() = ServiceList.YouTube

    /** Saran pencarian live ala YouTube. Must be called off the main thread. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSuggestionExtractor(service)
                .suggestionList(query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Satu halaman hasil pencarian + penanda halaman berikutnya (null = habis). */
    data class SearchPage(
        val tracks: List<Track>,
        val nextPage: org.schabi.newpipe.extractor.Page?
    )

    private fun searchHandler(query: String) = service.searchQHFactory.fromQuery(
        query,
        listOf(org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
        ""
    )

    private fun StreamInfoItem.toTrackOrNull(): Track? = try {
        val videoId = videoIdFromUrl(url) ?: return null
        val thumb = thumbnails?.maxByOrNull { it.height }?.url ?: ""
        Track(
            videoId = videoId,
            title = name ?: "Unknown title",
            channel = uploaderName ?: "Unknown",
            thumbnailUrl = thumb,
            durationSec = duration
        )
    } catch (e: Exception) {
        null
    }

    /** Search YouTube via NewPipe Extractor. Must be called off the main thread. */
    suspend fun search(query: String): List<Track> = searchFirstPage(query).tracks

    /** Halaman pertama pencarian (filter khusus musik ala YouTube Music). */
    suspend fun searchFirstPage(query: String): SearchPage = withContext(Dispatchers.IO) {
        val info = SearchInfo.getInfo(service, searchHandler(query))
        val tracks = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackOrNull() }
        tracks.forEach { dao.upsertSong(it.toEntity()) }
        SearchPage(tracks, if (info.hasNextPage()) info.nextPage else null)
    }

    /** Halaman berikutnya dari pencarian yang sama. */
    suspend fun searchNextPage(query: String, page: org.schabi.newpipe.extractor.Page): SearchPage =
        withContext(Dispatchers.IO) {
            val more = SearchInfo.getMoreItems(service, searchHandler(query), page)
            val tracks = more.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrackOrNull() }
            tracks.forEach { dao.upsertSong(it.toEntity()) }
            SearchPage(tracks, if (more.hasNextPage()) more.nextPage else null)
        }

    /** Resolve the best audio-only stream URL for a YouTube video id. */
    suspend fun resolveAudioUrl(videoId: String): String = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val best: AudioStream = info.audioStreams
            .filter { !it.url.isNullOrEmpty() }
            .maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("Tidak ada audio stream untuk video ini")
        best.url ?: throw IllegalStateException("URL audio kosong")
    }

    /** Ambil video terkait (YouTube "related") untuk autoplay se-genre. Must be called off the main thread. */
    suspend fun relatedTracks(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val tracks = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                try {
                    val vid = videoIdFromUrl(item.url) ?: return@mapNotNull null
                    val thumb = item.thumbnails?.maxByOrNull { it.height }?.url ?: ""
                    Track(
                        videoId = vid,
                        title = item.name ?: "Unknown title",
                        channel = item.uploaderName ?: "Unknown",
                        thumbnailUrl = thumb,
                        durationSec = item.duration
                    )
                } catch (e: Exception) {
                    null
                }
            }
        tracks.forEach { dao.upsertSong(it.toEntity()) }
        tracks
    }

    /**
     * Ambil feed channel YouTube (untuk tab Podcast). Fetch FRESH setiap dipanggil
     * sehingga episode terbaru otomatis muncul (auto-update).
     * @return Triple(namaChannel, avatarUrl, daftar episode sebagai Track)
     */
    suspend fun channelFeed(channel: PodcastChannel): Triple<String, String, List<Track>> =
        withContext(Dispatchers.IO) {
            // Strategi 1: ChannelInfo resmi (nama + avatar + tab videos)
            try {
                return@withContext channelFeedViaInfo(channel.url)
            } catch (e: Exception) {
                // lanjut ke fallback
            }
            // Strategi 2 (fallback): pakai search yang sudah terbukti jalan,
            // filter hasil yang uploader-nya cocok (abaikan spasi & kapital).
            val normName = channel.name.lowercase().replace(" ", "")
            val results = search("${channel.name} podcast")
            val filtered = results.filter {
                it.channel.lowercase().replace(" ", "").contains(normName)
            }.take(20)
            if (filtered.isEmpty()) {
                throw IllegalStateException("Channel tidak ditemukan. Periksa koneksi lalu coba lagi.")
            }
            Triple(channel.name, "", filtered)
        }

    private suspend fun channelFeedViaInfo(channelUrl: String): Triple<String, String, List<Track>> {
        val info = ChannelInfo.getInfo(service, channelUrl)
        val name = info.name ?: "Podcast"
        val avatar = info.avatars?.maxByOrNull { it.height }?.url ?: ""
        // Cari tab "Videos" dari channel (getTabs mengembalikan ListLinkHandler)
        val tabs = info.tabs.orEmpty()
        val videosTab = tabs.firstOrNull { tab ->
            tab.url.orEmpty().endsWith("/videos")
        } ?: tabs.firstOrNull()
        val tabUrl = videosTab?.url
            ?: if (channelUrl.endsWith("/videos")) channelUrl else "$channelUrl/videos"
        val handler = service.channelTabLHFactory.fromUrl(tabUrl)
        val tabInfo = ChannelTabInfo.getInfo(service, handler)
        val tracks = tabInfo.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                try {
                    val vid = videoIdFromUrl(item.url) ?: return@mapNotNull null
                    val thumb = item.thumbnails?.maxByOrNull { it.height }?.url ?: ""
                    Track(
                        videoId = vid,
                        title = item.name ?: "Tanpa judul",
                        channel = item.uploaderName ?: name,
                        thumbnailUrl = thumb,
                        durationSec = item.duration
                    )
                } catch (e: Exception) {
                    null
                }
            }
        if (tracks.isEmpty()) throw IllegalStateException("Tidak ada episode ditemukan")
        tracks.forEach { dao.upsertSong(it.toEntity()) }
        return Triple(name, avatar, tracks)
    }

    /** Download audio to app storage; returns the file. Progress 0..100. */
    suspend fun download(track: Track, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val url = resolveAudioUrl(track.videoId)
        val dir = File(filesDir, "downloads").apply { mkdirs() }
        val file = File(dir, "${track.videoId}.m4a")
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download gagal (${resp.code})")
            val body = resp.body ?: throw IllegalStateException("Respon kosong")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        dao.upsertSong(track.toEntity().copy(localPath = file.absolutePath))
        file
    }

    suspend fun recordHistory(videoId: String) {
        dao.addHistory(HistoryEntity(videoId = videoId))
        dao.trimHistory()
    }

    fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        // https://www.youtube.com/watch?v=VIDEOID...
        val vParam = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        if (vParam != null) return vParam
        // https://youtu.be/VIDEOID
        val short = Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        return short
    }

    // ---- Library flows ----
    fun playlists() = dao.playlists()
    fun favorites(): Flow<List<Track>> = dao.favorites().map { it.map { e -> e.toTrack() } }
    fun history(): Flow<List<Track>> = dao.history().map { it.map { e -> e.toTrack() } }
    fun downloaded(): Flow<List<Track>> = dao.downloaded().map { it.map { e -> e.toTrack() } }
    fun playlistSongs(pid: Long): Flow<List<Track>> = dao.playlistSongs(pid).map { it.map { e -> e.toTrack() } }
    suspend fun playlistSongList(pid: Long): List<Track> = dao.playlistSongList(pid).map { it.toTrack() }

    // ---- Quick Picks & terjemahan lirik ----
    suspend fun topPlayed(sinceMs: Long, limit: Int = 10): List<Track> =
        dao.topPlayed(sinceMs, limit).map { it.toTrack() }

    suspend fun getTranslation(videoId: String): String? = dao.getTranslation(videoId)
    suspend fun saveTranslation(videoId: String, text: String) = dao.saveTranslation(videoId, text)

    // ---- Bab/tracklist di dalam video panjang (mix) ----

    /**
     * Daftar lagu di dalam video mix, diparse dari deskripsi (baris bertimestamp).
     * Mix phonk/lofi dsb. hampir selalu menulis tracklist di deskripsi.
     * Valid hanya bila ditemukan >= 3 bab; selain itu kosong.
     */
    suspend fun mixChapters(videoId: String): List<MixChapter> = withContext(Dispatchers.IO) {
        try {
            val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
            val desc = info.description?.content ?: return@withContext emptyList()
            parseChapters(desc)
        } catch (_: Exception) { emptyList() }
    }

    private fun parseChapters(desc: String): List<MixChapter> {
        // Pola: "0:00 Judul" / "00:00 Judul" / "1:02:33 Judul" (boleh diawali - / •)
        val re = Regex("""^\s*(?:[-•*]\s*)?(\d{1,2}):(\d{2})(?::(\d{2}))?\s+[-–—:]?\s*(.+)$""")
        val out = mutableListOf<MixChapter>()
        for (raw in desc.lines()) {
            val m = re.find(raw.trim()) ?: continue
            val g = m.groupValues
            val hasHours = g[3].isNotEmpty()
            val h = if (hasHours) g[1].toLong() else 0L
            val min = if (hasHours) g[2].toLong() else g[1].toLong()
            val sec = if (hasHours) g[3].toLong() else g[2].toLong()
            val title = g[4].trim().trim('-', '–', '—', ':', ' ').trim()
            if (title.isEmpty() || title.length > 120) continue
            out.add(MixChapter((h * 3600 + min * 60 + sec) * 1000, title))
        }
        val sorted = out.sortedBy { it.startMs }.distinctBy { it.startMs }
        return if (sorted.size >= 3) sorted else emptyList()
    }

    // ---- Discovery "Pilihan Buatmu" berbasis genre ----

    /**
     * Genre lagu via iTunes Search API (gratis, tanpa kunci), di-cache di DB.
     * Mengembalikan null bila tidak ketemu; kegagalan jaringan juga null.
     */
    suspend fun genreOf(track: Track): String? = withContext(Dispatchers.IO) {
        dao.getGenre(track.videoId)?.let { return@withContext it }
        val genre = try {
            val q = java.net.URLEncoder.encode(
                "${track.channel} ${track.title}".take(100), "UTF-8")
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$q&entity=song&limit=1")
                .header("User-Agent", "ZeniMusic/3.4.0")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val arr = org.json.JSONObject(body).optJSONArray("results")
                if (arr == null || arr.length() == 0) return@use null
                arr.getJSONObject(0).optString("primaryGenreName").ifEmpty { null }
            }
        } catch (_: Exception) { null }
        // Cache juga hasil kosong ("") agar tidak ditanya berulang ke iTunes.
        dao.saveGenre(track.videoId, genre ?: "")
        genre
    }

    /** Genre yang paling sering didengar sejak [sinceMs], teratas dulu. */
    suspend fun topGenres(sinceMs: Long, limit: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            val counts = mutableMapOf<String, Int>()
            for (t in topPlayed(sinceMs, 30)) {
                val g = try { genreOf(t) } catch (_: Exception) { null }
                if (!g.isNullOrBlank()) counts[g] = (counts[g] ?: 0) + 1
            }
            counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
        }

    /**
     * Rekomendasi discovery: lagu dari genre favorit yang BELUM pernah didengar
     * (cek riwayat/favorit/playlist) dan berdurasi wajar (30 dtk – 30 mnt).
     * Hasil pencarian 1 jam tetap ada di tab Cari — filter ini khusus discovery.
     */
    suspend fun discoveryPicks(sinceMs: Long, limit: Int = 10): List<Track> =
        withContext(Dispatchers.IO) {
            // 1) Cache harian: hemat jaringan & cepat dibuka.
            loadDiscoveryCache()?.let { return@withContext it }
            val genres = try { topGenres(sinceMs, 3) } catch (_: Exception) { emptyList() }
            if (genres.isEmpty()) return@withContext emptyList()
            val heard = try { dao.heardVideoIds() } catch (_: Exception) { emptySet() }
            val out = LinkedHashMap<String, Track>()
            for (g in genres) {
                val results = try { search(g) } catch (_: Exception) { emptyList() }
                for (t in results) {
                    if (t.videoId in heard) continue
                    if (t.durationSec < 30 || t.durationSec >= 1800) continue
                    out.putIfAbsent(t.videoId, t)
                    if (out.size >= limit) break
                }
                if (out.size >= limit) break
            }
            val picks = out.values.toList()
            if (picks.isNotEmpty()) saveDiscoveryCache(picks)
            picks
        }

    /** Cache discovery per hari di filesDir (JSON sederhana). */
    private fun discoveryCacheFile() = File(filesDir, "discovery_cache.json")

    private fun loadDiscoveryCache(): List<Track>? {
        return try {
            val f = discoveryCacheFile()
            if (!f.exists()) return null
            val o = org.json.JSONObject(f.readText())
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            if (o.optString("date") != today) return null
            val arr = o.optJSONArray("tracks") ?: return null
            List(arr.length()) { i ->
                val t = arr.getJSONObject(i)
                Track(
                    videoId = t.getString("videoId"),
                    title = t.getString("title"),
                    channel = t.getString("channel"),
                    thumbnailUrl = t.optString("thumbnailUrl"),
                    durationSec = t.optLong("durationSec")
                )
            }.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun saveDiscoveryCache(tracks: List<Track>) {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val arr = org.json.JSONArray()
            for (t in tracks) {
                arr.put(org.json.JSONObject().apply {
                    put("videoId", t.videoId)
                    put("title", t.title)
                    put("channel", t.channel)
                    put("thumbnailUrl", t.thumbnailUrl)
                    put("durationSec", t.durationSec)
                })
            }
            discoveryCacheFile().writeText(
                org.json.JSONObject().apply {
                    put("date", today)
                    put("tracks", arr)
                }.toString()
            )
        } catch (_: Exception) { }
    }

    /**
     * Pool lagu untuk radio: gabungan lagu se-artis (via filter musik -> dijamin musik)
     * + related yang disaring bentuk-lagu (30 dtk - 12 mnt).
     * Mencegah video non-musik (dokumenter, gaming, vlog) masuk antrean radio,
     * sekaligus memutus rantai: sekali video non-musik masuk, related-nya ikut non-musik.
     */
    /**
     * Kandidat lagu radio: HANYA rekomendasi YouTube (related) dari lagu acuan —
     * ala YMusic. Dulu dicampur hasil pencarian nama artis (satu halaman penuh)
     * sehingga antrean didominasi artis yang sama; sekarang pemilihan & batas
     * artis diatur PlayerManager.selectRadioBatch.
     */
    suspend fun radioPool(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val relatedPool = try {
            relatedTracks(track.videoId)
        } catch (_: Exception) { emptyList<Track>() }
            .filter { it.durationSec in 30..720 }
        relatedPool
            .distinctBy { it.videoId }
            .filter { it.videoId != track.videoId }
    }
}

/** Satu bab (lagu) di dalam video mix panjang. */
data class MixChapter(val startMs: Long, val title: String)

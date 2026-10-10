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

    /** Search YouTube via NewPipe Extractor. Must be called off the main thread. */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(service, handler)
        val tracks = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                try {
                    val videoId = videoIdFromUrl(item.url) ?: return@mapNotNull null
                    val thumb = item.thumbnails?.maxByOrNull { it.height }?.url ?: ""
                    Track(
                        videoId = videoId,
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
}

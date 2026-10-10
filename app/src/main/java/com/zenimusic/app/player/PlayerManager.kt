package com.zenimusic.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.repo.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-process playback controller. The ExoPlayer instance is created and owned
 * by [PlaybackService] (so MediaSession + notification work in background);
 * UI drives everything through this singleton.
 */
object PlayerManager {

    data class UiState(
        val current: Track? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val queue: List<Track> = emptyList(),
        val queueIndex: Int = -1,
        val shuffle: Boolean = true,
        val playedIds: Set<String> = emptySet(),
        val repeatOne: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null
    )

    private lateinit var appContext: Context
    private lateinit var repo: MusicRepository

    var player: ExoPlayer? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var resolveJob: Job? = null

    fun init(context: Context, repository: MusicRepository) {
        appContext = context.applicationContext
        repo = repository
    }

    /** Called by PlaybackService once it builds the ExoPlayer. */
    fun attachPlayer(p: ExoPlayer) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                update { copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onTrackEnded()
            }
        })
        scope.launch {
            while (isActive) {
                delay(500)
                val pl = player
                if (pl != null) {
                    update {
                        copy(
                            positionMs = pl.currentPosition.coerceAtLeast(0),
                            durationMs = pl.duration.coerceAtLeast(0)
                        )
                    }
                }
            }
        }
    }

    fun releasePlayer() {
        player?.release()
        player = null
    }

    fun playTrack(track: Track, queue: List<Track>) {
        val raw = queue.ifEmpty { listOf(track) }
        // Hapus duplikat "lagu sama beda channel/versi" dari antrian berdasarkan judul
        // yang dinormalisasi — selalu pertahankan lagu yang di-tap user.
        val tappedKey = normalizeTitle(track.title)
        val seen = mutableSetOf<String>()
        if (tappedKey.isNotBlank()) seen.add(tappedKey)
        val q = mutableListOf<Track>()
        for (t in raw) {
            if (t.videoId == track.videoId) {
                if (q.none { it.videoId == track.videoId }) q.add(t)
                continue
            }
            val key = normalizeTitle(t.title)
            if (key.isBlank() || seen.add(key)) q.add(t)
        }
        if (q.none { it.videoId == track.videoId }) q.add(0, track)
        var idx = q.indexOfFirst { it.videoId == track.videoId }
        if (idx < 0) idx = 0
        update { copy(queue = q, queueIndex = idx, playedIds = setOf(track.videoId)) }
        resolveAndPlay(q[idx])
    }

    /** Normalisasi judul untuk deteksi duplikat: "Lagu (Official Video)" == "lagu" */
    private fun normalizeTitle(title: String): String {
        var s = title.lowercase()
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[[^]]*\\]"), " ")
        s = s.replace(Regex("\\b(official|lyric|lyrics|lirik|audio|video|music video|mv|hd|4k|hq|visualizer)\\b"), " ")
        s = s.replace(Regex("[^a-z0-9]"), "")
        return s
    }

    fun next() {
        val s = _uiState.value
        if (s.queue.isEmpty()) return
        val idx = if (s.shuffle && s.queue.size > 1) {
            val others = s.queue.indices.filter { it != s.queueIndex }
            others.random()
        } else {
            (s.queueIndex + 1) % s.queue.size
        }
        update { copy(queueIndex = idx) }
        resolveAndPlay(s.queue[idx])
    }

    fun previous() {
        val s = _uiState.value
        if (s.queue.isEmpty()) return
        val pl = player
        if (pl != null && pl.currentPosition > 3000) {
            pl.seekTo(0)
            return
        }
        val idx = (s.queueIndex - 1).coerceAtLeast(0)
        update { copy(queueIndex = idx) }
        resolveAndPlay(s.queue[idx])
    }

    fun togglePlayPause() {
        val pl = player ?: return
        if (pl.isPlaying) pl.pause() else pl.play()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    fun toggleShuffle() = update { copy(shuffle = !shuffle) }
    fun toggleRepeat() = update { copy(repeatOne = !repeatOne) }
    fun clearError() = update { copy(error = null) }

    private fun onTrackEnded() {
        val s = _uiState.value
        if (s.repeatOne) {
            player?.seekTo(0)
            player?.play()
            return
        }
        if (s.queue.isEmpty()) return
        autoNextSimilar()
    }

    /**
     * Auto-next yang tetap se-genre + radio tak terbatas: pilih RANDOM di antara
     * lagu mirip yang BELUM diputar; kalau sudah habis, fetch rekomendasi baru
     * dari YouTube (related videos) dan sambung ke antrian.
     */
    private fun autoNextSimilar() {
        val s = _uiState.value
        val current = s.current
        if (current == null) { next(); return }

        val played = s.playedIds + current.videoId

        // Kandidat: belum diputar + skor mirip >= 4 → pilih acak (variasi dalam genre)
        val candidates = s.queue.indices
            .filter { idx -> idx != s.queueIndex && s.queue[idx].videoId !in played }
            .map { idx -> idx to similarityScore(current, s.queue[idx]) }
            .filter { it.second >= 4 }

        if (candidates.isNotEmpty()) {
            val (idx, _) = candidates.random()
            val newPlayed = played + s.queue[idx].videoId
            update { copy(queueIndex = idx, playedIds = newPlayed) }
            resolveAndPlay(s.queue[idx])
            return
        }

        // Lagu mirip di antrian habis → fetch rekomendasi baru dari YouTube
        scope.launch {
            update { copy(loading = true) }
            val playedNow = _uiState.value.playedIds + current.videoId
            try {
                val related = repo.relatedTracks(current.videoId)
                    .filter { r -> r.videoId !in playedNow }
                    .take(15)
                if (related.isNotEmpty()) {
                    val base = _uiState.value.queue
                    val existingIds = base.map { it.videoId }.toSet()
                    val fresh = related.filter { it.videoId !in existingIds }
                    val toAdd = if (fresh.isNotEmpty()) fresh else related
                    val newQueue = base + toAdd
                    // Pilih acak dari lagu-lagu baru agar tidak monoton
                    val idx = base.size + toAdd.indices.random()
                    val newPlayed = playedNow + newQueue[idx].videoId
                    update { copy(queue = newQueue, queueIndex = idx, playedIds = newPlayed, loading = false) }
                    resolveAndPlay(newQueue[idx])
                } else {
                    update { copy(loading = false, playedIds = playedNow) }
                    next()
                }
            } catch (e: Exception) {
                update { copy(loading = false, playedIds = playedNow) }
                next()
            }
        }
    }

    /** Skor kemiripan dua lagu untuk autoplay se-genre. */
    private fun similarityScore(a: Track, b: Track): Int {
        var score = 0
        val aCh = a.channel.trim().lowercase()
        val bCh = b.channel.trim().lowercase()
        if (aCh.isNotBlank() && aCh == bCh) {
            score += 10
        } else if (aCh.isNotBlank() && bCh.isNotBlank() &&
            (aCh.contains(bCh) || bCh.contains(aCh))) {
            score += 5
        }
        val stopWords = setOf(
            "official", "video", "audio", "lyric", "lyrics", "lirik",
            "the", "a", "an", "dan", "di", "yang", "feat", "ft", "vs"
        )
        fun words(t: String): Set<String> = t.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
        val common = words(a.title) intersect words(b.title)
        score += common.size * 2
        return score
    }

    private fun resolveAndPlay(track: Track) {
        resolveJob?.cancel()
        resolveJob = scope.launch {
            update { copy(loading = true, error = null, current = track) }
            try {
                val uri: String = if (!track.localPath.isNullOrEmpty() && File(track.localPath).exists()) {
                    Uri.fromFile(File(track.localPath)).toString()
                } else {
                    withContext(Dispatchers.IO) { repo.resolveAudioUrl(track.videoId) }
                }
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(track.videoId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.channel)
                            .setArtworkUri(track.thumbnailUrl.toUri())
                            .build()
                    )
                    .build()
                val pl = player ?: run {
                    // Service might not have attached yet (cold start race): nudge it.
                    try {
                        appContext.startService(Intent(appContext, PlaybackService::class.java))
                    } catch (_: Exception) {
                    }
                    delay(1500)
                    player
                } ?: throw IllegalStateException("Player belum siap")
                pl.setMediaItem(item)
                pl.prepare()
                pl.play()
                launch(Dispatchers.IO) { repo.recordHistory(track.videoId) }
                update { copy(loading = false) }
                // Prefetch proaktif: jaga antrian tetap terisi lagu baru yang se-genre
                // agar radio tidak pernah kehabisan (tidak muter-muter di hasil pencarian saja).
                prefetchRelatedIfNeeded(track)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                update { copy(loading = false, error = e.message ?: "Gagal memutar lagu") }
            }
        }
    }

    /**
     * Ambil rekomendasi YouTube di background saat lagu diputar, lalu sambung ke
     * antrian — hanya jika sisa lagu yang belum diputar tinggal sedikit.
     */
    private fun prefetchRelatedIfNeeded(track: Track) {
        scope.launch {
            try {
                val s = _uiState.value
                val unplayed = s.queue.count { it.videoId !in s.playedIds }
                if (unplayed >= 12) return@launch
                val related = repo.relatedTracks(track.videoId)
                    .filter { r -> r.videoId !in s.playedIds }
                    .take(15)
                if (related.isEmpty()) return@launch
                // Hindari duplikat videoId yang sudah ada di antrian (tapi izinkan
                // judul mirip — yang penting videoId-nya beda agar ada variasi).
                val existingIds = _uiState.value.queue.map { it.videoId }.toSet()
                val fresh = related.filter { it.videoId !in existingIds }
                if (fresh.isEmpty()) return@launch
                val newQueue = _uiState.value.queue + fresh
                update { copy(queue = newQueue) }
            } catch (e: Exception) {
                // Gagal prefetch tidak mengganggu playback yang sedang jalan.
            }
        }
    }

    private inline fun update(fn: UiState.() -> UiState) {
        _uiState.value = _uiState.value.fn()
    }
}

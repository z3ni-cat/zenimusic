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
        val buffering: Boolean = false,
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
    /** Lagu yang harus diputar begitu service/player kembali hidup (recovery). */
    private var pendingResumeTrack: Track? = null
    private var positionLoopStarted = false

    // ============ Sleep timer: flag fade agar tidak rebutan volume ============
    /** True saat sleep timer sedang fade-out; crossfade harus diam. */
    var sleepFadeActive = false
        private set

    // ============ Crossfade (transisi halus antar lagu, gratis) ============
    private var fadingOut = false
    private var fadeInJob: Job? = null
    private var _crossfadeSec: Int = 5

    /** Durasi crossfade dalam detik. 0 = mati. Tersimpan di prefs. */
    var crossfadeSec: Int
        get() = _crossfadeSec
        set(v) {
            _crossfadeSec = v.coerceIn(0, 12)
            if (::appContext.isInitialized) {
                try {
                    appContext.getSharedPreferences("zeni_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("crossfade_sec", _crossfadeSec).apply()
                } catch (e: Exception) { /* abaikan */ }
            }
            // Jika dimatikan, kembalikan volume penuh
            if (_crossfadeSec == 0) {
                fadingOut = false
                fadeInJob?.cancel()
                try { player?.volume = 1f } catch (e: Exception) { /* abaikan */ }
            }
        }

    private fun loadCrossfadePref() {
        try {
            _crossfadeSec = appContext.getSharedPreferences("zeni_prefs", Context.MODE_PRIVATE)
                .getInt("crossfade_sec", 5) // default 5 detik
        } catch (e: Exception) { _crossfadeSec = 5 }
    }

    fun init(context: Context, repository: MusicRepository) {
        appContext = context.applicationContext
        repo = repository
        loadCrossfadePref()
    }

    // ============ Sleep timer ============

    /**
     * Fade-out volume 8 detik lalu pause (untuk sleep timer mode menit).
     * Selama fade, crossfade dinonaktifkan agar tidak rebutan volume.
     */
    fun sleepFadeOutAndPause() {
        scope.launch {
            sleepFadeActive = true
            try {
                val pl = player
                if (pl != null && pl.isPlaying) {
                    val steps = 16
                    repeat(steps) { i ->
                        delay(500)
                        if (!isActive) return@launch
                        try {
                            pl.volume = (1f - (i + 1).toFloat() / steps).coerceIn(0f, 1f)
                        } catch (_: Exception) { }
                    }
                    try { pl.pause() } catch (_: Exception) { }
                    try { pl.volume = 1f } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            sleepFadeActive = false
            fadingOut = false
        }
    }

    /** Pause langsung tanpa fade (sleep timer mode lagu: lagu sudah selesai). */
    fun pauseForSleep() {
        try { player?.pause() } catch (_: Exception) { }
    }

    /** Called by PlaybackService once it builds the ExoPlayer. */
    fun attachPlayer(p: ExoPlayer) {
        player = p
        // Tempel equalizer ke audio session player
        try {
            com.zenimusic.app.audio.EqualizerHelper.attach(p.audioSessionId)
        } catch (e: Exception) { /* abaikan */ }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                update { copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> update { copy(buffering = true) }
                    Player.STATE_READY, Player.STATE_IDLE -> update { copy(buffering = false) }
                    Player.STATE_ENDED -> {
                        update { copy(buffering = false) }
                        onTrackEnded()
                    }
                }
            }
        })
        // Service (kembali) hidup: lanjutkan lagu yang tertunda dipulihkan, kalau ada.
        pendingResumeTrack?.let { pendingResumeTrack = null; resolveAndPlay(it) }
        if (positionLoopStarted) return
        positionLoopStarted = true
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
                    // Crossfade: fade out halus saat lagu hampir selesai
                    applyCrossfadeOut(pl)
                } else if (_uiState.value.isPlaying) {
                    // Player hilang tapi tampilan masih "playing": koreksi agar tombol tidak stuck.
                    update { copy(isPlaying = false, buffering = false) }
                }
            }
        }
    }

    /**
     * Fade out volume secara proporsional saat posisi mendekati akhir lagu.
     * Dipanggil tiap 500ms dari loop posisi.
     */
    private fun applyCrossfadeOut(pl: ExoPlayer) {
        val fadeSec = _crossfadeSec
        // Sleep timer sedang fade-out: jangan rebutan volume.
        if (sleepFadeActive) return
        if (fadeSec <= 0 || !pl.isPlaying) {
            if (fadingOut) {
                fadingOut = false
                try { pl.volume = 1f } catch (e: Exception) { /* abaikan */ }
            }
            return
        }
        try {
            val dur = pl.duration
            val pos = pl.currentPosition
            if (dur <= 0) return
            val fadeMs = fadeSec * 1000L
            val remaining = dur - pos
            if (remaining in 1..fadeMs) {
                // Di zona fade: volume proporsional dengan sisa waktu
                pl.volume = (remaining.toFloat() / fadeMs).coerceIn(0.05f, 1f)
                fadingOut = true
            } else if (fadingOut && remaining > fadeMs) {
                // Keluar dari zona fade (mis. setelah seek): kembalikan volume
                pl.volume = 1f
                fadingOut = false
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    /**
     * Fade in halus saat lagu baru mulai (melengkapi fade out lagu sebelumnya).
     * Dipanggil setelah pl.play() di resolveAndPlay.
     */
    private fun fadeInNewTrack(pl: ExoPlayer) {
        fadeInJob?.cancel()
        val fadeSec = _crossfadeSec
        if (fadeSec <= 0) {
            try { pl.volume = 1f } catch (e: Exception) { /* abaikan */ }
            fadingOut = false
            return
        }
        fadeInJob = scope.launch {
            try {
                val startVol = pl.volume.coerceIn(0f, 1f)
                val fadeMs = (fadeSec * 1000L / 2).coerceAtLeast(1200)
                val steps = 24
                repeat(steps) { i ->
                    delay(fadeMs / steps)
                    if (!isActive) return@launch
                    val t = (i + 1).toFloat() / steps
                    // Easing halus (smoothstep)
                    val eased = t * t * (3 - 2 * t)
                    pl.volume = (startVol + (1f - startVol) * eased).coerceIn(0f, 1f)
                }
                pl.volume = 1f
            } catch (e: Exception) { /* abaikan */ }
            fadingOut = false
        }
    }

    fun releasePlayer() {
        player?.release()
        player = null
        pendingResumeTrack = null
        // Jangan biarkan tombol stuck di PAUSE: player sudah tidak ada.
        if (_uiState.value.isPlaying || _uiState.value.buffering) {
            update { copy(isPlaying = false, buffering = false) }
        }
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

    /** Lompat ke lagu di posisi index antrian. */
    fun playAt(index: Int) {
        val s = _uiState.value
        if (index !in s.queue.indices) return
        val newPlayed = s.playedIds + s.queue[index].videoId
        update { copy(queueIndex = index, playedIds = newPlayed) }
        resolveAndPlay(s.queue[index])
    }

    /** Hapus lagu dari antrian (tidak bisa hapus yang sedang diputar). */
    fun removeAt(index: Int) {
        val s = _uiState.value
        if (index !in s.queue.indices || index == s.queueIndex) return
        val newQueue = s.queue.toMutableList().apply { removeAt(index) }
        val newIdx = if (index < s.queueIndex) s.queueIndex - 1 else s.queueIndex
        update { copy(queue = newQueue, queueIndex = newIdx.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))) }
    }

    fun togglePlayPause() {
        val pl = player
        val cur = _uiState.value.current
        // Player hilang / kosong padahal ada lagu (service sempat mati): pulihkan dulu,
        // jangan diam saja — diam itu yang bikin tombol macet di PAUSE.
        if ((pl == null || pl.currentMediaItem == null) && cur != null) {
            recoverAndPlay(cur)
            return
        }
        if (pl == null) return
        if (pl.isPlaying) pl.pause() else pl.play()
    }

    /** Hidupkan ulang service bila perlu, lalu putar lagi lagu yang sedang berjalan. */
    private fun recoverAndPlay(track: Track) {
        update { copy(isPlaying = false, buffering = false, loading = true, error = null) }
        if (player == null) {
            pendingResumeTrack = track
            try {
                appContext.startService(Intent(appContext, PlaybackService::class.java))
            } catch (e: Exception) {
                pendingResumeTrack = null
                update { copy(loading = false, error = "Tidak bisa menghidupkan ulang pemutar") }
            }
            // attachPlayer() akan memutar pendingResumeTrack begitu service hidup.
        } else {
            resolveAndPlay(track)
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    fun toggleShuffle() = update { copy(shuffle = !shuffle) }
    fun toggleRepeat() = update { copy(repeatOne = !repeatOne) }
    fun clearError() = update { copy(error = null) }

    private fun onTrackEnded() {
        // Sleep timer mode lagu: bila jatah habis, jangan auto-next.
        if (SleepTimer.onTrackEnded()) return
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
                // Crossfade: fade in halus untuk lagu baru
                fadeInNewTrack(pl)
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

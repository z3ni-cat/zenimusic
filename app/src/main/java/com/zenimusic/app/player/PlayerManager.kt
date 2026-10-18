package com.zenimusic.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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
    private var pendingResumeMs: Long = 0L
    /** Percobaan hidupkan ulang service setelah player hilang (dibatasi). */
    private var svcRestartTries = 0
    private var positionLoopStarted = false
    /** Hitung error player beruntun — untuk batas recovery agar tidak loop selamanya. */
    private var playerErrorRetries = 0
    // Batas percobaan ambil lagu berikutnya saat transisi gagal (jaringan putus).
    // Tanpa ini: radioNext -> autoNextSimilar -> next() -> radioNext berputar
    // tanpa henti, musik diam tanpa pesan error.
    private var transitionFailures = 0
    // True setelah lagu yang sedang di-resolve benar-benar mulai bunyi.
    // Dipakai agar resume posisi hanya untuk error di tengah lagu,
    // bukan error saat loading awal (posisi bisa basi dari lagu sebelumnya).
    private var trackPlaybackStarted = false
    // Penanda buffering berkepanjangan (untuk log diagnosis).
    private var wasBuffering = false
    /** Mode radio: true = auto-next dari rekomendasi YouTube (acak), false = ikuti antrean. */
    private var radioMode = true

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
        svcRestartTries = 0
        // Tempel equalizer ke audio session player
        try {
            com.zenimusic.app.audio.EqualizerHelper.attach(p.audioSessionId)
        } catch (e: Exception) { /* abaikan */ }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playerErrorRetries = 0
                    transitionFailures = 0
                    trackPlaybackStarted = true
                }
                // Catat setiap jeda/lanjut — untuk kasus "berhenti tanpa error di log".
                val cur = _uiState.value.current
                if (cur != null) {
                    PlaybackLog.add("STATE", if (isPlaying) "▶ Lanjut: ${cur.title}"
                        else "⏸ Jeda di ${( _uiState.value.positionMs / 1000)} dtk")
                }
                update { copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Jangan mati diam-diam: coba pulihkan dengan URL fresh (maks 2x),
                // lanjut dari posisi terakhir agar tidak mengulang dari awal.
                // Kalau tetap gagal tampilkan penyebab aslinya ke user.
                val msg = error.message ?: "error tidak dikenal (${error.errorCode})"
                PlaybackLog.add("ERROR", "Player error [${error.errorCode}]: $msg")
                if (playerErrorRetries < 2) {
                    playerErrorRetries++
                    val cur = _uiState.value.current
                    // Posisi terakhir dari loop 500ms; hanya dipakai bila lagu
                    // memang sudah sempat bunyi (bukan gagal saat loading).
                    val resumeAt = if (trackPlaybackStarted)
                        _uiState.value.positionMs.coerceAtLeast(0) else 0L
                    PlaybackLog.add("PULIH", "Coba ambil ulang (${playerErrorRetries}/2) dari ${(resumeAt / 1000)} dtk")
                    if (cur != null) resolveAndPlay(cur, startAtMs = resumeAt)
                } else {
                    PlaybackLog.add("ERROR", "Menyerah setelah 2x percobaan")
                    update { copy(isPlaying = false, buffering = false, loading = false, error = "Pemutar error: $msg") }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        update { copy(buffering = true) }
                        if (!wasBuffering) {
                            wasBuffering = true
                            _uiState.value.current?.let {
                                PlaybackLog.add("STATE", "… Buffering: ${it.title}")
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        update { copy(buffering = false) }
                        if (wasBuffering) {
                            wasBuffering = false
                            PlaybackLog.add("STATE", "✓ Buffering selesai")
                        }
                    }
                    Player.STATE_IDLE -> update { copy(buffering = false) }
                    Player.STATE_ENDED -> {
                        update { copy(buffering = false) }
                        onTrackEnded()
                    }
                }
            }
        })
        // Service (kembali) hidup: lanjutkan lagu yang tertunda dipulihkan, kalau ada.
        pendingResumeTrack?.let { t ->
            val pos = pendingResumeMs
            pendingResumeTrack = null
            pendingResumeMs = 0L
            PlaybackLog.add("SERVICE", "Service hidup lagi — lanjut otomatis @ ${pos / 1000} dtk")
            resolveAndPlay(t, startAtMs = pos)
        }
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
                } else {
                    // Player hilang (service dimatikan sistem) dan ada lagu tertunda:
                    // hidupkan ulang service sendiri — maks 5x percobaan, tercatat di log.
                    // (Sebelumnya hanya menunggu sistem me-restart, yang sering tak terjadi.)
                    val pending = pendingResumeTrack
                    if (pending != null && svcRestartTries < 5) {
                        svcRestartTries++
                        PlaybackLog.add("SERVICE", "Player hilang — hidupkan ulang service (cobaan $svcRestartTries)")
                        try {
                            ContextCompat.startForegroundService(
                                appContext, Intent(appContext, PlaybackService::class.java))
                        } catch (e: Exception) {
                            PlaybackLog.add("SERVICE", "Gagal hidupkan ulang: ${e.message}")
                        }
                    }
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
        val wasPlaying = _uiState.value.isPlaying
        val cur = _uiState.value.current
        val pos = _uiState.value.positionMs.coerceAtLeast(0)
        player?.release()
        player = null
        // Simpan lagu + posisi agar bisa lanjut otomatis bila service dihidupkan
        // lagi oleh sistem — tapi hanya bila memang sedang bunyi (bukan jeda manual).
        if (wasPlaying && cur != null) {
            pendingResumeTrack = cur
            pendingResumeMs = pos
            PlaybackLog.add("SERVICE", "Service dimatikan sistem — simpan ${cur.title} @ ${pos / 1000} dtk")
        } else {
            pendingResumeTrack = null
            pendingResumeMs = 0L
            if (cur != null) PlaybackLog.add("SERVICE", "Service dimatikan sistem")
        }
        // Jangan biarkan tombol stuck di PAUSE: player sudah tidak ada.
        if (wasPlaying || _uiState.value.buffering) {
            update { copy(isPlaying = false, buffering = false) }
        }
    }

    /**
     * @param radioMode true = lagu berikutnya diambil dari rekomendasi YouTube lagu yang
     * sedang diputar (acak, ala radio YouTube) — bukan dari antrean hasil pencarian.
     * false = ikuti antrean (untuk playlist/podcast).
     */
    fun playTrack(track: Track, queue: List<Track>, radioMode: Boolean = true) {
        this.radioMode = radioMode
        if (radioMode) {
            // Mode radio: antrean dimulai dari lagu yang di-tap SAJA.
            // Lagu berikutnya diisi dari rekomendasi YouTube (prefetchRadio),
            // bukan dari hasil pencarian.
            update { copy(queue = listOf(track), queueIndex = 0, playedIds = setOf(track.videoId)) }
            resolveAndPlay(track)
            prefetchRadio(track)
            return
        }
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
        // Mode radio: tombol next juga ambil lagu acak dari rekomendasi YouTube.
        if (radioMode && _uiState.value.current != null) {
            radioNext()
            return
        }
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

    /** Geser lagu di antrean (hanya bagian setelah lagu aktif yang boleh digeser). */
    fun moveInQueue(from: Int, to: Int) {
        val s = _uiState.value
        if (from !in s.queue.indices || to !in s.queue.indices) return
        if (from <= s.queueIndex || to <= s.queueIndex) return
        val q = s.queue.toMutableList()
        val item = q.removeAt(from)
        q.add(to, item)
        update { copy(queue = q) }
    }

    /**
     * Pilih batch lagu radio dari kandidat rekomendasi YouTube: acak, tetapi
     * artis yang sama dengan lagu acuan dibatasi maks 2 per batch, dan artis
     * yang sama tidak boleh muncul dua kali berurutan (dilonggarkan bertahap
     * bila stok kandidat kurang).
     */
    private fun selectRadioBatch(
        candidates: List<Track>, count: Int, anchor: Track
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val anchorArtist = anchor.channel.trim().lowercase()
        val shuffled = candidates.shuffled()
        val picked = mutableListOf<Track>()
        var anchorCount = 0
        fun isAnchor(t: Track) =
            anchorArtist.isNotEmpty() && t.channel.trim().lowercase() == anchorArtist
        // Lolos 1: patuh batas artis acuan + tanpa artis berurutan.
        var lastArtist = anchorArtist
        for (c in shuffled) {
            if (picked.size >= count) break
            val a = c.channel.trim().lowercase()
            if (isAnchor(c) && anchorCount >= 2) continue
            if (a.isNotEmpty() && a == lastArtist) continue
            picked.add(c)
            if (isAnchor(c)) anchorCount++
            if (a.isNotEmpty()) lastArtist = a
        }
        // Lolos 2: longgarkan aturan berurutan.
        if (picked.size < count) {
            for (c in shuffled) {
                if (picked.size >= count) break
                if (c in picked) continue
                if (isAnchor(c) && anchorCount >= 2) continue
                picked.add(c)
                if (isAnchor(c)) anchorCount++
            }
        }
        // Lolos 3: ambil apa adanya daripada antrean kosong.
        if (picked.size < count) {
            for (c in shuffled) {
                if (picked.size >= count) break
                if (c !in picked) picked.add(c)
            }
        }
        return picked
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
        PlaybackLog.add("SELESAI", "Lagu habis → ambil lagu berikutnya")
        // Mode radio: lagu berikutnya dari rekomendasi YouTube (acak),
        // bukan dari antrean hasil pencarian.
        if (radioMode) {
            radioNext()
            return
        }
        if (s.queue.isEmpty()) return
        autoNextSimilar()
    }

    /**
     * Isi antrean radio dari rekomendasi YouTube lagu acuan.
     * Dipanggil saat radio dimulai & saat stok menipis. Tidak memutar — hanya menambah antrean.
     */
    private fun prefetchRadio(forTrack: Track) {
        scope.launch {
            try {
                val playedNow = _uiState.value.playedIds
                val existing = _uiState.value.queue.map { it.videoId }.toSet()
                val related = selectRadioBatch(
                    withContext(Dispatchers.IO) { repo.radioPool(forTrack) }
                        .filter { r -> r.videoId !in playedNow && r.videoId !in existing },
                    10, forTrack)
                if (related.isEmpty()) return@launch
                update { copy(queue = _uiState.value.queue + related) }
            } catch (_: Exception) { }
        }
    }

    /**
     * Radio ala YouTube: lanjut ke lagu antrean radio berikutnya yang belum diputar;
     * bila stok habis, fetch rekomendasi dari lagu saat ini. Lagu yang diputar
     * selalu dari rekomendasi — tidak pernah dari hasil pencarian awal.
     */
    private fun radioNext() {
        val s = _uiState.value
        // 1) Masih ada stok radio yang belum diputar → pakai itu
        val nextIdx = (s.queueIndex + 1 until s.queue.size).firstOrNull { i ->
            s.queue[i].videoId !in s.playedIds
        }
        if (nextIdx != null) {
            val nxt = s.queue[nextIdx]
            PlaybackLog.add("RADIO", "→ ${nxt.title} (${nxt.channel})")
            update { copy(queueIndex = nextIdx, playedIds = s.playedIds + nxt.videoId) }
            resolveAndPlay(nxt)
            // Stok menipis → tambah lagi dari lagu ini
            if (nextIdx >= s.queue.size - 3) prefetchRadio(nxt)
            return
        }
        // 2) Stok habis → fetch rekomendasi dari lagu saat ini
        val current = s.current
        if (current == null) { autoNextSimilar(); return }
        scope.launch {
            update { copy(loading = true) }
            try {
                val playedNow = _uiState.value.playedIds + current.videoId
                val existing = _uiState.value.queue.map { it.videoId }.toSet()
                val related = selectRadioBatch(
                    withContext(Dispatchers.IO) { repo.radioPool(current) }
                        .filter { r -> r.videoId !in playedNow && r.videoId !in existing },
                    10, current)
                if (related.isNotEmpty()) {
                    val base = _uiState.value.queue
                    val newQueue = base + related
                    val nxt = related.first()
                    update {
                        copy(
                            queue = newQueue,
                            queueIndex = base.size,
                            playedIds = playedNow + nxt.videoId,
                            loading = false
                        )
                    }
                    resolveAndPlay(nxt)
                } else {
                    update { copy(loading = false) }
                    autoNextSimilar()
                }
            } catch (e: Exception) {
                // Gagal ambil rekomendasi (mis. jaringan putus): jangan berputar
                // tanpa henti — batasi percobaan lalu tampilkan pesan yang jelas.
                transitionFailures++
                PlaybackLog.add("ERROR", "Gagal ambil rekomendasi (${transitionFailures}/3): ${e.message}")
                if (transitionFailures >= 3) {
                    PlaybackLog.add("ERROR", "STOP: tidak bisa lanjut, menunggu aksi manual")
                    update { copy(loading = false, error = "Gagal memuat lagu berikutnya. Periksa koneksi lalu tekan play.") }
                } else {
                    update { copy(loading = false) }
                    autoNextSimilar()
                }
            }
        }
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
                // Sama seperti di radioNext: batasi agar tidak berputar tanpa henti.
                transitionFailures++
                PlaybackLog.add("ERROR", "Gagal ambil lagu mirip (${transitionFailures}/3): ${e.message}")
                if (transitionFailures >= 3) {
                    PlaybackLog.add("ERROR", "STOP: tidak bisa lanjut, menunggu aksi manual")
                    update { copy(loading = false, playedIds = playedNow, error = "Gagal memuat lagu berikutnya. Periksa koneksi lalu tekan play.") }
                } else {
                    update { copy(loading = false, playedIds = playedNow) }
                    next()
                }
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

    private fun resolveAndPlay(track: Track, startAtMs: Long = 0) {
        resolveJob?.cancel()
        resolveJob = scope.launch {
            update { copy(loading = true, error = null, current = track) }
            trackPlaybackStarted = false
            PlaybackLog.add("MULAI", "${track.title} [${PlaybackLog.networkType(appContext)}]")
            // Resolve URL dengan retry: kadang YouTube menolak sesaat (rate limit),
            // coba lagi 2x dengan jeda sebelum menyerah.
            var uri: String? = null
            var lastError: Exception? = null
            for (attempt in 0..2) {
                try {
                    uri = if (!track.localPath.isNullOrEmpty() && File(track.localPath).exists()) {
                        Uri.fromFile(File(track.localPath)).toString()
                    } else {
                        withContext(Dispatchers.IO) { repo.resolveAudioUrl(track.videoId) }
                    }
                    lastError = null
                    break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastError = e
                    if (attempt < 2) delay(1500L * (attempt + 1))
                }
            }
            val finalUri = uri
            if (finalUri == null) {
                PlaybackLog.add("ERROR", "Gagal resolve URL: ${lastError?.message}")
                update { copy(loading = false, error = lastError?.message ?: "Gagal memutar lagu") }
                return@launch
            }
            try {
                val item = MediaItem.Builder()
                    .setUri(finalUri)
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
                // Pulih dari error tengah lagu: lanjut dari posisi terakhir.
                if (startAtMs > 1000) {
                    try { pl.seekTo(startAtMs) } catch (_: Exception) { }
                }
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
                val related = selectRadioBatch(
                    repo.radioPool(track).filter { r -> r.videoId !in s.playedIds },
                    15, track)
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

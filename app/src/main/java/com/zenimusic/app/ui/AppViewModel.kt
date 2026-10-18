package com.zenimusic.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenimusic.app.ZeniMusicApp
import com.zenimusic.app.data.db.FavoriteEntity
import com.zenimusic.app.data.db.PlaylistEntity
import com.zenimusic.app.data.db.PlaylistSongCrossRef
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.repo.Daylist
import com.zenimusic.app.data.repo.DaylistSlot
import com.zenimusic.app.data.repo.PodcastChannel
import com.zenimusic.app.data.repo.Samples
import com.zenimusic.app.data.repo.Wrapped
import com.zenimusic.app.data.repo.WrappedStats
import com.zenimusic.app.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val zeni = app as ZeniMusicApp
    private val repo = zeni.repository
    private val dao = zeni.db

    val playerState = PlayerManager.uiState

    // ---- Search ----
    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError

    // Pagination pencarian: penanda halaman berikutnya dari NewPipe.
    private var searchQuery: String = ""
    private var searchNextPage: org.schabi.newpipe.extractor.Page? = null
    private var searchPageCount = 0

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    /** Masih ada halaman hasil berikutnya yang bisa dimuat? */
    val hasMoreSearch: Boolean get() = searchNextPage != null

    fun search(query: String) {
        if (query.isBlank()) return
        val q = query.trim()
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            searchQuery = q
            searchNextPage = null
            searchPageCount = 0
            try {
                val page = repo.searchFirstPage(q)
                _searchResults.value = page.tracks
                searchNextPage = page.nextPage
                searchPageCount = 1
            } catch (e: Exception) {
                _searchError.value = e.message ?: "Pencarian gagal. Coba lagi."
            }
            _searching.value = false
        }
    }

    /** Muat halaman hasil berikutnya (dipanggil UI saat scroll mendekati bawah). */
    fun loadMoreSearch() {
        val next = searchNextPage ?: return
        if (_searching.value || _loadingMore.value) return
        if (searchPageCount >= 10) { searchNextPage = null; return }
        viewModelScope.launch {
            _loadingMore.value = true
            try {
                val page = repo.searchNextPage(searchQuery, next)
                val seen = _searchResults.value.map { it.videoId }.toHashSet()
                val fresh = page.tracks.filter { seen.add(it.videoId) }
                if (fresh.isEmpty()) {
                    searchNextPage = null
                } else {
                    _searchResults.value = _searchResults.value + fresh
                    searchNextPage = page.nextPage
                    searchPageCount++
                }
            } catch (e: Exception) {
                // Gagal memuat lanjutan: biarkan pengguna mencoba lagi lewat scroll.
            }
            _loadingMore.value = false
        }
    }

    /** Saran pencarian live (dipanggil dengan debounce dari UI). */
    suspend fun suggestions(query: String): List<String> = repo.suggestions(query)

    // ---- Playback ----
    fun play(track: Track, queue: List<Track>, radioMode: Boolean = true) =
        PlayerManager.playTrack(track, queue, radioMode)

    /** Feed channel YouTube untuk tab Podcast (fresh fetch = auto-update episode baru). */
    suspend fun channelFeed(channel: PodcastChannel): Triple<String, String, List<Track>> =
        repo.channelFeed(channel)

    /** Generate Daylist (~20 lagu sesuai slot waktu). */
    suspend fun daylist(slot: DaylistSlot): List<Track> = Daylist.generate(repo, slot)

    /** Generate Samples (~20 lagu trending/viral untuk swipe discovery). */
    suspend fun samples(): List<Track> = Samples.generate(repo)

    /** Generate Flow: campuran personal dari favorit + riwayat dengar. */
    suspend fun flow(): List<Track> = com.zenimusic.app.data.repo.Flow.generate(repo)

    // ---- Akun YouTube (login ala YMusic) ----

    private val _accountLoggedIn = MutableStateFlow(
        try { zeni.account.isLoggedIn } catch (_: Exception) { false }
    )
    val accountLoggedIn: StateFlow<Boolean> = _accountLoggedIn

    fun refreshAccountState() {
        _accountLoggedIn.value = try { zeni.account.isLoggedIn } catch (_: Exception) { false }
    }

    fun logoutAccount() {
        try { zeni.account.logout() } catch (_: Exception) { }
        refreshAccountState()
        _toast.value = "Sudah keluar dari akun YouTube"
    }

    suspend fun accountPlaylists(): List<com.zenimusic.app.data.repo.AccountPlaylist> =
        zeni.account.libraryPlaylists()

    suspend fun accountTracks(playlistId: String): List<Track> =
        zeni.account.playlistTracks(playlistId)

    /** Rekomendasi beranda akun YouTube — seksi "Berdasarkan Pencarianmu". */
    suspend fun accountHomeTracks(): List<Track> = zeni.account.homeFeedTracks()

    /** Statistik Wrapped (tahun/bulan ini) dari history lokal. */
    suspend fun wrapped(year: Boolean): WrappedStats = Wrapped(dao).getStats(year)

    fun showToast(msg: String) { _toast.value = msg }

    // ---- Library ----
    val playlists = dao.playlists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites = repo.favorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = repo.history()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloaded = repo.downloaded()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun playlistSongsFlow(playlistId: Long) = repo.playlistSongs(playlistId)

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun consumeToast() { _toast.value = null }

    fun isFavoriteFlow(videoId: String) = dao.isFavorite(videoId)

    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertSong(track.toEntity())
            if (dao.favoriteCount(track.videoId) > 0) {
                dao.removeFavorite(track.videoId)
            } else {
                dao.addFavorite(FavoriteEntity(track.videoId))
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertPlaylist(PlaylistEntity(name = name.trim()))
            _toast.value = "Playlist \"$name\" dibuat"
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearPlaylistSongs(id)
            dao.deletePlaylist(id)
        }
    }

    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertSong(track.toEntity())
            dao.addToPlaylist(PlaylistSongCrossRef(playlistId, track.videoId))
            _toast.value = "Ditambahkan ke playlist"
        }
    }

    fun removeFromPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.removeFromPlaylist(playlistId, track.videoId)
        }
    }

    /** Simpan urutan lagu playlist setelah diseret pengguna. */
    fun reorderPlaylist(playlistId: Long, tracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setPlaylistOrder(playlistId, tracks.map { it.videoId })
        }
    }

    /** Persentase unduhan yang sedang berjalan, per videoId (0-100). */
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress

    fun download(track: Track) {
        viewModelScope.launch {
            if (_downloadingIds.value.contains(track.videoId)) return@launch
            _downloadingIds.value = _downloadingIds.value + track.videoId
            _downloadProgress.value = _downloadProgress.value + (track.videoId to 0)
            try {
                repo.download(track) { pct ->
                    if (_downloadProgress.value[track.videoId] != pct) {
                        _downloadProgress.value =
                            _downloadProgress.value + (track.videoId to pct)
                    }
                }
                _toast.value = "Download selesai: ${track.title}"
            } catch (e: Exception) {
                _toast.value = "Download gagal: ${e.message}"
            }
            _downloadingIds.value = _downloadingIds.value - track.videoId
            _downloadProgress.value = _downloadProgress.value - track.videoId
        }
    }

    // ---- Quick Picks & terjemahan lirik ----
    suspend fun topPlayed(sinceMs: Long, limit: Int = 10): List<Track> =
        repo.topPlayed(sinceMs, limit)

    /** Discovery "Pilihan Buatmu": rekomendasi genre yang belum pernah didengar. */
    suspend fun discoveryPicks(): List<Track> {
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        return repo.discoveryPicks(since, 20)
    }

    suspend fun getTranslation(videoId: String): String? = repo.getTranslation(videoId)
    suspend fun saveTranslation(videoId: String, text: String) =
        repo.saveTranslation(videoId, text)

    /** Daftar lagu di dalam video mix (dari deskripsi); kosong bila tidak ada. */
    suspend fun mixChapters(videoId: String): List<com.zenimusic.app.data.repo.MixChapter> =
        repo.mixChapters(videoId)
}

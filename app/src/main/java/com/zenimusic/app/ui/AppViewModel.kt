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

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            try {
                _searchResults.value = repo.search(query.trim())
            } catch (e: Exception) {
                _searchError.value = e.message ?: "Pencarian gagal. Coba lagi."
            }
            _searching.value = false
        }
    }

    // ---- Playback ----
    fun play(track: Track, queue: List<Track>) = PlayerManager.playTrack(track, queue)

    /** Feed channel YouTube untuk tab Podcast (fresh fetch = auto-update episode baru). */
    suspend fun channelFeed(channel: PodcastChannel): Triple<String, String, List<Track>> =
        repo.channelFeed(channel)

    /** Generate Daylist (~20 lagu sesuai slot waktu). */
    suspend fun daylist(slot: DaylistSlot): List<Track> = Daylist.generate(repo, slot)

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

    fun download(track: Track) {
        viewModelScope.launch {
            if (_downloadingIds.value.contains(track.videoId)) return@launch
            _downloadingIds.value = _downloadingIds.value + track.videoId
            try {
                repo.download(track)
                _toast.value = "Download selesai: ${track.title}"
            } catch (e: Exception) {
                _toast.value = "Download gagal: ${e.message}"
            }
            _downloadingIds.value = _downloadingIds.value - track.videoId
        }
    }
}

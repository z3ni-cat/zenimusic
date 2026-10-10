package com.zenimusic.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer: berhenti otomatis setelah X menit ATAU setelah N lagu,
 * dengan fade-out volume yang halus sebelum berhenti.
 */
object SleepTimer {

    sealed interface State {
        data object Off : State
        data class Minutes(val label: String) : State
        data class Songs(val remaining: Int) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var songsLeft = 0

    val isActive: Boolean get() = _state.value !is State.Off

    /** Mulai timer menit. */
    fun startMinutes(minutes: Int) {
        cancel()
        val totalMs = minutes * 60_000L
        _state.value = State.Minutes("$minutes mnt")
        job = scope.launch {
            var left = totalMs
            // Perbarui label tiap 30 detik agar hemat baterai
            while (left > 0) {
                val step = minOf(30_000L, left)
                delay(step)
                left -= step
                if (left > 0) {
                    val m = (left / 60_000L).toInt().coerceAtLeast(1)
                    _state.value = State.Minutes("$m mnt")
                }
            }
            finish()
        }
    }

    /** Mulai timer lagu: berhenti setelah [count] lagu selesai. */
    fun startSongs(count: Int) {
        cancel()
        songsLeft = count.coerceAtLeast(1)
        _state.value = State.Songs(songsLeft)
    }

    /**
     * Dipanggil PlayerManager tiap lagu selesai (sebelum auto-next).
     * @return true bila timer lagu habis dan auto-next harus dibatalkan.
     */
    fun onTrackEnded(): Boolean {
        val s = _state.value
        if (s !is State.Songs) return false
        songsLeft--
        return if (songsLeft <= 0) {
            _state.value = State.Off
            job?.cancel()
            job = null
            // Lagu sudah selesai sendiri: cukup pause, tidak perlu fade.
            scope.launch { PlayerManager.pauseForSleep() }
            true
        } else {
            _state.value = State.Songs(songsLeft)
            false
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        songsLeft = 0
        _state.value = State.Off
    }

    private fun finish() {
        _state.value = State.Off
        job = null
        songsLeft = 0
        scope.launch { PlayerManager.sleepFadeOutAndPause() }
    }

    fun statusText(): String = when (val s = _state.value) {
        is State.Off -> "Mati"
        is State.Minutes -> "Aktif • sisa ±${s.label}"
        is State.Songs -> "Aktif • ${s.remaining} lagu lagi"
    }
}

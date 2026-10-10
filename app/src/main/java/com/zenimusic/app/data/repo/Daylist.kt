package com.zenimusic.app.data.repo

import com.zenimusic.app.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar

/** Satu slot waktu Daylist: nama mood + query pencarian YouTube. */
data class DaylistSlot(
    val name: String,
    val emoji: String,
    val label: String,
    val queries: List<String>
)

/**
 * Daylist ala Spotify: playlist otomatis yang berubah sesuai jam.
 * Generate via pencarian YouTube (NewPipe), ~20 lagu per slot.
 */
object Daylist {

    private val slots = listOf(
        DaylistSlot(
            "Pagi", "🌅", "05.00–10.00",
            listOf("acoustic chill morning", "mellow morning coffee")
        ),
        DaylistSlot(
            "Siang", "☀️", "10.00–15.00",
            listOf("upbeat pop hits", "energetic daytime pop")
        ),
        DaylistSlot(
            "Sore", "🌤️", "15.00–18.00",
            listOf("chill afternoon vibes", "relaxing indie afternoon")
        ),
        DaylistSlot(
            "Malam", "🌙", "18.00–22.00",
            listOf("lofi night chill", "mellow evening acoustic")
        ),
        DaylistSlot(
            "Tengah Malam", "🌌", "22.00–05.00",
            listOf("calm sleep ambient", "soft night piano")
        ),
    )

    /** Slot aktif berdasarkan jam lokal perangkat. */
    fun currentSlot(): DaylistSlot {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..9 -> slots[0]
            in 10..14 -> slots[1]
            in 15..17 -> slots[2]
            in 18..21 -> slots[3]
            else -> slots[4]
        }
    }

    /**
     * Generate ~20 lagu untuk slot: search tiap query paralel,
     * ambil 7 teratas per query, dedup by videoId.
     */
    suspend fun generate(repo: MusicRepository, slot: DaylistSlot): List<Track> =
        coroutineScope {
            val results = slot.queries.map { q ->
                async {
                    try {
                        repo.search(q).take(7)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
            val out = mutableListOf<Track>()
            val seen = mutableSetOf<String>()
            for (tracks in results) {
                for (t in tracks) {
                    if (seen.add(t.videoId)) out.add(t)
                }
            }
            out.take(20)
        }
}

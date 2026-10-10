package com.zenimusic.app.data.repo

import com.zenimusic.app.data.db.ZeniDb
import java.util.Calendar

/** Satu entri peringkat (lagu/artis). */
data class TopEntry(val title: String, val subtitle: String, val count: Int)

/** Hasil statistik Wrapped. */
data class WrappedStats(
    val totalMinutes: Long,
    val totalPlays: Int,
    val topSongs: List<TopEntry>,
    val topArtists: List<TopEntry>,
    val label: String
)

/** Statistik dengerin ala Spotify Wrapped dari history lokal. */
class Wrapped(private val db: ZeniDb) {

    /** year=true: sejak 1 Jan tahun ini; false: sejak tgl 1 bulan ini. */
    suspend fun getStats(year: Boolean): WrappedStats {
        val cal = Calendar.getInstance()
        if (year) {
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
        } else {
            cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val sinceMs = cal.timeInMillis

        val rows = db.wrappedStats(sinceMs)
        val totalPlays = rows.sumOf { it.plays }
        val totalMinutes = rows.sumOf { it.durationSec * it.plays } / 60

        val topSongs = rows.take(5).map {
            TopEntry(it.title, it.channel, it.plays)
        }
        val topArtists = rows.groupBy { it.channel.ifBlank { "Unknown" } }
            .map { (ch, list) -> TopEntry(ch, "${list.size} lagu", list.sumOf { it.plays }) }
            .sortedByDescending { it.count }
            .take(5)

        val label = if (year) "Tahun ${Calendar.getInstance().get(Calendar.YEAR)}" else "Bulan ini"
        return WrappedStats(totalMinutes, totalPlays, topSongs, topArtists, label)
    }
}

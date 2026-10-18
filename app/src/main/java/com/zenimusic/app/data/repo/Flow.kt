package com.zenimusic.app.data.repo

import com.zenimusic.app.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Flow ala Deezer: campuran personal yang terasa "tahu seleramu".
 *
 * Biji (seed) = lagu favorit + yang paling sering diputar. Tiap biji diperluas
 * dengan rekomendasi YouTube-nya, lalu semuanya dijalin selang-seling supaya
 * tidak mengelompok di satu artis. Setelah daftar awal habis, mode antrean
 * biasa + prefetch rekomendasi PlayerManager meneruskannya tanpa henti.
 *
 * Belum ada favorit/riwayat (pengguna baru) → jatuh ke Samples (trending).
 */
object Flow {

    suspend fun generate(repo: MusicRepository): List<Track> = coroutineScope {
        val favs = try { repo.favorites().first() } catch (_: Exception) { emptyList() }
        val top = try { repo.topPlayed(0L, 8) } catch (_: Exception) { emptyList() }

        val seeds = (favs.take(5) + top)
            .distinctBy { it.videoId }
            .take(6)

        if (seeds.isEmpty()) {
            return@coroutineScope Samples.generate(repo)
        }

        // Perluas tiap biji dengan rekomendasi YouTube-nya (paralel).
        val expansions = seeds.map { seed ->
            async {
                try {
                    repo.relatedTracks(seed.videoId)
                        .filter { it.durationSec in 30..900 }
                        .take(8)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()

        // Jalin selang-seling: biji, rekomendasinya, biji lain, dst.
        val out = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        fun push(t: Track) {
            if (seen.add(t.videoId)) out.add(t)
        }
        val maxRounds = 8
        for (round in 0 until maxRounds) {
            for (i in seeds.indices) {
                if (round == 0) push(seeds[i])
                expansions.getOrNull(i)?.getOrNull(round)?.let { push(it) }
            }
        }
        out.take(40)
    }
}

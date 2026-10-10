package com.zenimusic.app.data.repo

import com.zenimusic.app.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Samples ala TikTok: discovery lagu via swipe vertikal.
 * Generate via pencarian YouTube (NewPipe), ~20 lagu trending/viral.
 */
object Samples {

    private val queries = listOf(
        "top hits Indonesia 2026",
        "lagu viral TikTok Indonesia",
        "top pop Indonesia"
    )

    /**
     * Generate ~20 lagu: search tiap query paralel,
     * ambil 8 teratas per query, dedup by videoId.
     */
    suspend fun generate(repo: MusicRepository): List<Track> = coroutineScope {
        val results = queries.map { q ->
            async {
                try {
                    repo.search(q).take(8)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        val seen = mutableSetOf<String>()
        results.filter { seen.add(it.videoId) }.take(24)
    }
}

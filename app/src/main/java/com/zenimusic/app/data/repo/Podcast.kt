package com.zenimusic.app.data.repo

/** Channel podcast pilihan. Avatar & episode di-fetch fresh dari YouTube (auto-update). */
data class PodcastChannel(val name: String, val url: String)

val PODCAST_ID = listOf(
    PodcastChannel("Deddy Corbuzier", "https://www.youtube.com/@DeddyCorbuzier"),
    PodcastChannel("Raditya Dika", "https://www.youtube.com/@radityadika"),
)

val PODCAST_EN = listOf(
    PodcastChannel("Joe Rogan", "https://www.youtube.com/@joerogan"),
    PodcastChannel("Lex Fridman", "https://www.youtube.com/@lexfridman"),
)

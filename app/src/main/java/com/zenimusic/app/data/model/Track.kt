package com.zenimusic.app.data.model

import com.zenimusic.app.data.db.SongEntity

data class Track(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val localPath: String? = null
) {
    fun toEntity(): SongEntity = SongEntity(
        videoId = videoId,
        title = title,
        channel = channel,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        localPath = localPath
    )
}

fun SongEntity.toTrack(): Track = Track(
    videoId = videoId,
    title = title,
    channel = channel,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    localPath = localPath
)

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--:--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDurationMs(ms: Long): String = formatDuration(ms / 1000)

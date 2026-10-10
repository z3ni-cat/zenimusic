package com.zenimusic.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

// ---- Entities (plain data classes) ----

data class SongEntity(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val localPath: String? = null
)

data class PlaylistEntity(
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class PlaylistSongCrossRef(
    val playlistId: Long,
    val videoId: String,
    val position: Int = 0
)

data class FavoriteEntity(
    val videoId: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class HistoryEntity(
    val id: Long = 0,
    val videoId: String,
    val playedAt: Long = System.currentTimeMillis()
)

// ---- Database ----

class ZeniDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "zenimusic.db", null, 1) {

    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE songs (
                videoId TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                channel TEXT NOT NULL,
                thumbnailUrl TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                localPath TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playlist_songs (
                playlistId INTEGER NOT NULL,
                videoId TEXT NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (playlistId, videoId))"""
        )
        db.execSQL(
            """CREATE TABLE favorites (
                videoId TEXT PRIMARY KEY,
                addedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                videoId TEXT NOT NULL,
                playedAt INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS songs")
        db.execSQL("DROP TABLE IF EXISTS playlists")
        db.execSQL("DROP TABLE IF EXISTS playlist_songs")
        db.execSQL("DROP TABLE IF EXISTS favorites")
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    private fun ping() {
        refresh.tryEmit(Unit)
    }

    private fun <T> liveQuery(query: (SQLiteDatabase) -> T): Flow<T> = channelFlow {
        send(withContext(Dispatchers.IO) { query(readableDatabase) })
        refresh.collect {
            send(withContext(Dispatchers.IO) { query(readableDatabase) })
        }
    }

    private fun Cursor.readSong(): SongEntity = SongEntity(
        videoId = getString(getColumnIndexOrThrow("videoId")),
        title = getString(getColumnIndexOrThrow("title")),
        channel = getString(getColumnIndexOrThrow("channel")),
        thumbnailUrl = getString(getColumnIndexOrThrow("thumbnailUrl")),
        durationSec = getLong(getColumnIndexOrThrow("durationSec")),
        localPath = getString(getColumnIndexOrThrow("localPath"))
    )

    private fun querySongs(db: SQLiteDatabase, sql: String, args: Array<String>): List<SongEntity> {
        val out = ArrayList<SongEntity>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(c.readSong())
        }
        return out
    }

    // ---- Songs ----
    suspend fun upsertSong(song: SongEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("videoId", song.videoId)
            put("title", song.title)
            put("channel", song.channel)
            put("thumbnailUrl", song.thumbnailUrl)
            put("durationSec", song.durationSec)
            put("localPath", song.localPath)
        }
        writableDatabase.insertWithOnConflict("songs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        ping()
    }

    suspend fun getSong(videoId: String): SongEntity? = withContext(Dispatchers.IO) {
        querySongs(readableDatabase, "SELECT * FROM songs WHERE videoId = ?", arrayOf(videoId))
            .firstOrNull()
    }

    // ---- Playlists ----
    suspend fun insertPlaylist(p: PlaylistEntity): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("name", p.name)
            put("createdAt", p.createdAt)
        }
        val id = writableDatabase.insert("playlists", null, cv)
        ping()
        id
    }

    fun playlists(): Flow<List<PlaylistEntity>> = liveQuery { db ->
        val out = ArrayList<PlaylistEntity>()
        db.rawQuery("SELECT id, name, createdAt FROM playlists ORDER BY createdAt DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PlaylistEntity(
                        id = c.getLong(0),
                        name = c.getString(1),
                        createdAt = c.getLong(2)
                    )
                )
            }
        }
        out
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("playlists", "id = ?", arrayOf(id.toString()))
        ping()
    }

    suspend fun clearPlaylistSongs(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("playlist_songs", "playlistId = ?", arrayOf(id.toString()))
        ping()
    }

    suspend fun addToPlaylist(ref: PlaylistSongCrossRef) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("playlistId", ref.playlistId)
            put("videoId", ref.videoId)
            put("position", ref.position)
        }
        writableDatabase.insertWithOnConflict(
            "playlist_songs", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        ping()
    }

    suspend fun removeFromPlaylist(pid: Long, vid: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(
            "playlist_songs", "playlistId = ? AND videoId = ?",
            arrayOf(pid.toString(), vid)
        )
        ping()
    }

    fun playlistSongs(pid: Long): Flow<List<SongEntity>> = liveQuery { db ->
        querySongs(
            db,
            """SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.videoId = ps.videoId
               WHERE ps.playlistId = ? ORDER BY ps.position, ps.rowid""",
            arrayOf(pid.toString())
        )
    }

    suspend fun playlistSongList(pid: Long): List<SongEntity> = withContext(Dispatchers.IO) {
        querySongs(
            readableDatabase,
            """SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.videoId = ps.videoId
               WHERE ps.playlistId = ? ORDER BY ps.position, ps.rowid""",
            arrayOf(pid.toString())
        )
    }

    // ---- Favorites ----
    suspend fun addFavorite(f: FavoriteEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("videoId", f.videoId)
            put("addedAt", f.addedAt)
        }
        writableDatabase.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        ping()
    }

    suspend fun removeFavorite(vid: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("favorites", "videoId = ?", arrayOf(vid))
        ping()
    }

    suspend fun favoriteCount(vid: String): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM favorites WHERE videoId = ?", arrayOf(vid)
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun favorites(): Flow<List<SongEntity>> = liveQuery { db ->
        querySongs(
            db,
            """SELECT s.* FROM songs s INNER JOIN favorites f ON s.videoId = f.videoId
               ORDER BY f.addedAt DESC""",
            emptyArray()
        )
    }

    fun isFavorite(vid: String): Flow<Boolean> = liveQuery { db ->
        db.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM favorites WHERE videoId = ?)", arrayOf(vid)
        ).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }
    }

    // ---- History ----
    suspend fun addHistory(h: HistoryEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("videoId", h.videoId)
            put("playedAt", h.playedAt)
        }
        writableDatabase.insert("history", null, cv)
        ping()
    }

    fun history(): Flow<List<SongEntity>> = liveQuery { db ->
        querySongs(
            db,
            """SELECT s.* FROM songs s
               INNER JOIN (SELECT videoId, MAX(playedAt) AS pa FROM history GROUP BY videoId) h
               ON s.videoId = h.videoId ORDER BY h.pa DESC LIMIT 100""",
            emptyArray()
        )
    }

    suspend fun trimHistory() = withContext(Dispatchers.IO) {
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY playedAt DESC LIMIT 200)"
        )
    }

    // ---- Downloaded ----
    fun downloaded(): Flow<List<SongEntity>> = liveQuery { db ->
        querySongs(
            db, "SELECT * FROM songs WHERE localPath IS NOT NULL ORDER BY title", emptyArray()
        )
    }

    suspend fun setLocalPath(vid: String, path: String?) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("localPath", path) }
        writableDatabase.update("songs", cv, "videoId = ?", arrayOf(vid))
        ping()
    }
}

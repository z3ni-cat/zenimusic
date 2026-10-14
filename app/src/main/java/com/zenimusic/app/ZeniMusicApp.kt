package com.zenimusic.app

import android.app.Application
import com.zenimusic.app.data.db.ZeniDb
import com.zenimusic.app.data.repo.MusicRepository
import com.zenimusic.app.net.OkHttpDownloader
import com.zenimusic.app.player.PlayerManager
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ZeniMusicApp : Application() {

    lateinit var db: ZeniDb
        private set

    lateinit var repository: MusicRepository
        private set

    /** Set when init fails; MainActivity shows a diagnostic screen instead of crashing. */
    var initError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // Kecualikan dari "Jeda aktivitas apl jika tidak digunakan" (auto-revoke/hibernasi),
        // seperti YMusic — toggle-nya jadi mati sendiri setiap aplikasi dibuka.
        // Tanpa ini Android bisa mencabut izin & membatasi kerja background.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                packageManager.setAutoRevokeWhitelisted(packageName, true)
            }
        } catch (_: Exception) { }

        // Crash catcher (v2.0.1 diagnostic): tulis stack trace ke crash.log sebelum app mati.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null)
                if (dir != null) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    File(dir, "crash.log").appendText("[$ts] thread=${thread.name}\n$sw\n---\n")
                }
            } catch (_: Exception) {
                // Jangan pernah crash di dalam crash handler.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Init dibungkus: kalau gagal, simpan error-nya dan lanjut (degraded),
        // jangan biarkan crash-loop. MainActivity akan menampilkan layar diagnostik.
        try {
            val http = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            // NewPipe Extractor needs a downloader before any search/stream call.
            NewPipe.init(OkHttpDownloader(http))

            db = ZeniDb(this)
            repository = MusicRepository(db, http, filesDir)

            PlayerManager.init(this, repository)
            // Equalizer: siapkan prefs (attach ke session dilakukan di attachPlayer)
            com.zenimusic.app.audio.EqualizerHelper.init(this)
        } catch (e: Throwable) {
            initError = e
        }
    }
}

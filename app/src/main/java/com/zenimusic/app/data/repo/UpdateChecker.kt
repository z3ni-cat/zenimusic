package com.zenimusic.app.data.repo

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pengecekan update versi dari GitHub Releases (z3ni-cat/zenimusic).
 * Changelog diambil otomatis dari release notes GitHub.
 */
object UpdateChecker {
    private const val REPO = "z3ni-cat/zenimusic"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val changelog: String,
        val apkUrl: String?,
        val pageUrl: String
    )

    /** Ambil info rilis terbaru; null bila gagal / draft / prerelease. */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("User-Agent", "ZeniMusic")
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val json = JSONObject(body)
            if (json.optBoolean("draft", false)) return@withContext null
            if (json.optBoolean("prerelease", false)) return@withContext null
            val tag = json.optString("tag_name", "").trim().removePrefix("v")
            if (tag.isEmpty()) return@withContext null
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").ifEmpty { null }
                        break
                    }
                }
            }
            UpdateInfo(
                versionName = tag,
                changelog = json.optString("body", "").trim(),
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url", "https://github.com/$REPO/releases")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Bandingkan versi semver sederhana: "3.1.0" > "3.0.0". */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String): List<Int> =
            v.split(".", "-").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Unduh APK lalu buka installer sistem. onProgress dipanggil 0..100.
     * Berjalan di IO; panggil dari coroutine.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ZeniMusic")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                val dir = context.getExternalFilesDir("updates") ?: context.cacheDir
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, "ZeniMusic-update.apk")
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                                withContext(Dispatchers.Main) { onProgress(p) }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context, "com.zenimusic.app.fileprovider", out
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

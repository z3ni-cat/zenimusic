package com.zenimusic.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thumbnail loader sederhana pengganti Coil: LruCache + download OkHttp di IO.
 * Aman untuk ListView recycling via view.tag (hanya set bitmap bila tag masih cocok).
 */
object ImageLoader {

    private val cache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun load(url: String?, target: ImageView) {
        val key = url.orEmpty()
        target.tag = key
        if (key.isEmpty()) {
            target.setImageBitmap(null)
            target.setBackgroundColor(PLACEHOLDER)
            return
        }
        val cached = cache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            target.setBackgroundColor(0)
            return
        }
        target.setImageBitmap(null)
        target.setBackgroundColor(PLACEHOLDER)
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(key).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes() ?: return@use
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                    cache.put(key, bmp)
                    withContext(Dispatchers.Main) {
                        if (target.tag == key) {
                            target.setImageBitmap(bmp)
                            target.setBackgroundColor(0)
                        }
                    }
                }
            } catch (_: Exception) {
                // Biarkan placeholder.
            }
        }
    }

    private const val PLACEHOLDER = 0xFF2A2536.toInt()
}

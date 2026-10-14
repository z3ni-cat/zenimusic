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

    /**
     * Ambil bitmap dari cache, atau download dulu bila belum ada.
     * Callback selalu dipanggil di main thread. null bila URL kosong / gagal.
     * Pola sama seperti [load], tapi tanpa ImageView (untuk kebutuhan custom
     * seperti background blur).
     */
    fun get(url: String?, onResult: (Bitmap?) -> Unit) {
        val key = url.orEmpty()
        if (key.isEmpty()) {
            scope.launch { onResult(null) }
            return
        }
        val cached = cache.get(key)
        if (cached != null) {
            scope.launch { onResult(cached) }
            return
        }
        scope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            try {
                val req = Request.Builder().url(key).build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) {
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) cache.put(key, bmp!!)
                        }
                    }
                }
            } catch (_: Exception) {
                // null → pemanggil pakai fallback.
            }
            withContext(Dispatchers.Main) { onResult(bmp) }
        }
    }

    private const val PLACEHOLDER = 0xFF2A2536.toInt()
}

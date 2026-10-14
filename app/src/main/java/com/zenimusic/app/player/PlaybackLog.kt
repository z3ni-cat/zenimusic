package com.zenimusic.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Log diagnosis playback: ring buffer (maks 300 baris), aman dari thread mana pun.
 * Dibaca dari tab "Log" agar terlihat jelas kenapa musik berhenti.
 */
object PlaybackLog {
    private const val MAX = 300
    private val buf: ArrayDeque<String> = ArrayDeque()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(tag: String, msg: String) {
        val t = try { timeFmt.format(Date()) } catch (_: Exception) { "--:--:--" }
        buf.addLast("$t [$tag] $msg")
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun text(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() { buf.clear() }

    /** Jenis jaringan saat ini: WiFi / Data / ?. */
    fun networkType(ctx: Context?): String {
        if (ctx == null) return "?"
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "?"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data"
                else -> "Lain"
            }
        } catch (_: Exception) { "?" }
    }
}

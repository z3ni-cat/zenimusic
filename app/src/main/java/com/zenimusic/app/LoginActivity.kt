package com.zenimusic.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.ZeniIcons

/**
 * Login YouTube ala YMusic: pengguna masuk lewat halaman YouTube resmi di
 * WebView; begitu cookie sesi terdeteksi, cookie disimpan terenkripsi oleh
 * YouTubeAccount dan activity selesai dengan RESULT_OK.
 */
class LoginActivity : Activity() {

    private lateinit var web: WebView
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) = with(UiKit) {
        super.onCreate(savedInstanceState)

        val root = vcol { setBackgroundColor(BG) }

        // Header: judul + tombol selesai manual
        val header = hrow {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.ColorDrawable(SURFACE)
        }
        val btnBack = iconAction(ZeniIcons.Kind.CHEVRON_LEFT, TEXT, 40, 6)
        btnBack.setOnClickListener { finish() }
        header.addView(btnBack)
        header.addView(textView("Masuk ke YouTube", 16f, TEXT, bold = true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnDone = accentPillButton("Selesai", 13f).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        btnDone.setOnClickListener { checkLogin(manual = true) }
        header.addView(btnDone)
        root.addView(header, matchWrap())

        root.addView(textView(
            "Masuk dengan akun Google-mu. Setelah berhasil masuk, ketuk Selesai.",
            12f, TEXT2
        ).apply { setPadding(dp(20), dp(10), dp(20), dp(6)) }, matchWrap())

        web = WebView(this@LoginActivity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.6723.86 Mobile Safari/537.36"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    checkLogin(manual = false)
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        web.loadUrl("https://music.youtube.com/")
    }

    /** Kumpulkan cookie dari domain YouTube; gabung per nama (yang terakhir menang). */
    private fun collectCookies(): String {
        val cm = CookieManager.getInstance()
        cm.flush()
        val map = LinkedHashMap<String, String>()
        for (url in listOf("https://www.youtube.com", "https://music.youtube.com")) {
            val raw = cm.getCookie(url) ?: continue
            for (part in raw.split(";")) {
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) map[kv[0]] = kv[1]
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun checkLogin(manual: Boolean) {
        if (finished) return
        val cookies = collectCookies()
        val loggedIn = cookies.contains("SAPISID=") ||
            cookies.contains("__Secure-3PAPISID=") ||
            cookies.contains("__Secure-1PAPISID=")
        if (loggedIn) {
            finished = true
            val app = application as? ZeniMusicApp
            app?.account?.saveCookies(cookies)
            Toast.makeText(this, "Berhasil masuk. Selamat datang!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else if (manual) {
            Toast.makeText(
                this,
                "Belum terdeteksi masuk — selesaikan login dulu, lalu ketuk Selesai lagi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}

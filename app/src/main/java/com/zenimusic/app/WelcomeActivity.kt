package com.zenimusic.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.UiKit.BG
import com.zenimusic.app.ui.UiKit.ACCENT1
import com.zenimusic.app.ui.UiKit.SURFACE
import com.zenimusic.app.ui.UiKit.SURFACE2
import com.zenimusic.app.ui.UiKit.TEXT
import com.zenimusic.app.ui.UiKit.TEXT2
import com.zenimusic.app.ui.UiKit.dp
import com.zenimusic.app.ui.UiKit.iconAction
import com.zenimusic.app.ui.UiKit.roundedCorners
import com.zenimusic.app.ui.ZeniIcons

/**
 * v2.2.0: layar perkenalan — hanya tampil pada pertama kali aplikasi dibuka.
 * Jika flag welcome_shown sudah true, langsung forward ke MainActivity.
 */
class WelcomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("zenimusic", MODE_PRIVATE)
        if (prefs.getBoolean("welcome_shown", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        window.statusBarColor = BG
        buildUi()
    }

    private fun buildUi() = with(UiKit) {
        val col = vcol {
            setBackgroundColor(BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(56), dp(32), dp(32))
        }

        val logoId = logoResId()
        val iv = ImageView(this@WelcomeActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (logoId != 0) setImageResource(logoId) else setBackgroundColor(SURFACE2)
            roundedCorners(28)
        }
        col.addView(iv, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(24)
        })

        col.addView(textView("ZeniMusic", 28f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
        }, matchWrap())
        col.addView(textView("Musik YouTube favoritmu, tanpa iklan.", 14f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }, matchWrap())

        val features = listOf(
            ZeniIcons.Kind.SEARCH to "Cari jutaan lagu dari YouTube",
            ZeniIcons.Kind.DOWNLOAD to "Download & dengarkan offline",
            ZeniIcons.Kind.PLAY to "Tetap bunyi di background"
        )
        for ((kind, label) in features) {
            val row = hrow {
                background = roundedBg(SURFACE, dp(16).toFloat())
                setPadding(dp(14), dp(10), dp(18), dp(10))
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(iconAction(kind, ACCENT1, 36, 8))
            row.addView(textView(label, 14f, TEXT).apply {
                setPadding(dp(10), 0, 0, 0)
            }, matchWrap())
            col.addView(row, matchWrap().apply { bottomMargin = dp(10) })
        }

        val btnStart = accentPillButton("Mulai", 16f).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        btnStart.setOnClickListener {
            getSharedPreferences("zenimusic", MODE_PRIVATE)
                .edit().putBoolean("welcome_shown", true).apply()
            startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
            finish()
        }
        col.addView(btnStart, matchWrap().apply { topMargin = dp(24) })

        val scroll = ScrollView(this@WelcomeActivity)
        scroll.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }
}

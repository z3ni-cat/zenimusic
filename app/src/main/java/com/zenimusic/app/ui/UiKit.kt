package com.zenimusic.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView

/** Design system ZeniMusic v2.2.0 — SOFT DARK: charcoal-purple lembut + aksen violet kalem. */
object UiKit {
    // ---- Palette (soft dark) ----
    const val BG = 0xFF1A1626.toInt()        // background: soft charcoal-purple (bukan hitam pekat)
    const val SURFACE = 0xFF242130.toInt()   // card / surface
    const val SURFACE2 = 0xFF2E2940.toInt()  // tombol / input
    // Aksen bisa diganti pengguna (Personalisasi) — dimuat dari prefs di awal
    // proses oleh ZeniMusicApp, sebelum UI mana pun dibangun.
    var ACCENT1 = 0xFFA78BFA.toInt()   // muted violet (gradient start)
    var ACCENT2 = 0xFF8E7BD8.toInt()   // violet sedikit lebih dalam (gradient end)
    val ACCENT: Int get() = ACCENT1    // kompat: tint teks/ikon aksen

    /** Pasangan warna aksen pilihan pengguna: nama -> (ACCENT1, ACCENT2). */
    val ACCENT_PRESETS: List<Triple<String, Int, Int>> = listOf(
        Triple("Ungu", 0xFFA78BFA.toInt(), 0xFF8E7BD8.toInt()),
        Triple("Biru", 0xFF7AB8FA.toInt(), 0xFF5B8DEF.toInt()),
        Triple("Hijau", 0xFF7BD88E.toInt(), 0xFF4CAF6D.toInt()),
        Triple("Merah Muda", 0xFFFAA7C5.toInt(), 0xFFD87BA7.toInt()),
        Triple("Oranye", 0xFFFABB7A.toInt(), 0xFFEF945B.toInt())
    )
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFFA8A3B8.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()
    const val FAV = 0xFFF43F5E.toInt()       // merah hati favorit aktif
    const val DIVIDER = 0xFF2E2940.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================= Drawable builders =================

    /** Background solid rounded. */
    fun roundedBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp
        }

    /** Background gradient aksen violet lembut (diagonal), rounded. radiusDp dalam px. */
    fun accentGradient(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ACCENT1, ACCENT2)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
        }

    /** Card surface standar: #242130, corner 16dp. */
    fun Context.cardBg(): GradientDrawable =
        roundedBg(SURFACE, dp(16).toFloat())

    /** Tombol lingkaran solid (untuk tombol aksi 36/44/48dp). */
    fun circleBg(color: Int = SURFACE2): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Bulatkan sudut sebuah View + opsional shadow via elevation. */
    fun View.roundedCorners(radiusDp: Int, elevationDp: Int = 0) {
        val r = context.dp(radiusDp).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
        if (elevationDp > 0) elevation = context.dp(elevationDp).toFloat()
    }

    /** Touch feedback: alpha 0.6 saat ditekan. */
    fun View.pressedFeedback() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    // ================= View builders =================

    fun Context.textView(
        text: String = "",
        sizeSp: Float = 14f,
        color: Int = TEXT,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun Context.button(text: String, sizeSp: Float = 14f): Button = Button(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(TEXT)
        isAllCaps = false
        background = roundedBg(SURFACE2, dp(12).toFloat())
        stateListAnimator = null
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
    }

    /** Tombol pill dengan gradient aksen + teks putih bold. */
    fun Context.accentPillButton(text: String, sizeSp: Float = 14f): Button =
        button(text, sizeSp).apply {
            background = accentGradient(dp(24).toFloat())
            setTextColor(WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            pressedFeedback()
        }

    /** Tombol lingkaran: diameter dp, bg SURFACE2 (atau custom), glyph terpusat. */
    fun Context.circleButton(glyph: String, diameterDp: Int, sizeSp: Float, bg: Int = SURFACE2): Button =
        Button(this).apply {
            this.text = glyph
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(TEXT)
            isAllCaps = false
            background = circleBg(bg)
            stateListAnimator = null
            gravity = Gravity.CENTER
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            val s = dp(diameterDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
            isFocusable = false
            isFocusableInTouchMode = false
            pressedFeedback()
        }

    /** Tombol lingkaran dengan background GRADIENT aksen (untuk play utama). */
    fun Context.accentCircleButton(glyph: String, diameterDp: Int, sizeSp: Float): Button =
        circleButton(glyph, diameterDp, sizeSp).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(ACCENT1, ACCENT2)
            }
            setTextColor(WHITE)
        }

    /** Tombol ikon flat minimalis: tanpa background, glyph abu terang. Untuk mini player slim. */
    fun Context.flatIconButton(glyph: String, diameterDp: Int, sizeSp: Float): Button =
        Button(this).apply {
            this.text = glyph
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(TEXT2)
            isAllCaps = false
            background = ColorDrawable(0x00000000)
            stateListAnimator = null
            gravity = Gravity.CENTER
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            val s = dp(diameterDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
            isFocusable = false
            isFocusableInTouchMode = false
            pressedFeedback()
        }

    /** Tombol toggle tab model pill: selected = gradient + putih bold; tidak = surface + abu. */
    fun styleTab(btn: Button, selected: Boolean) {        val ctx = btn.context
        if (selected) {
            btn.background = accentGradient(ctx.dp(22).toFloat())
            btn.setTextColor(WHITE)
            btn.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
        } else {
            btn.background = roundedBg(SURFACE2, ctx.dp(22).toFloat())
            btn.setTextColor(TEXT2)
            btn.setTypeface(btn.typeface, android.graphics.Typeface.NORMAL)
        }
        btn.isAllCaps = false
        btn.stateListAnimator = null
    }

    /** Segmented control nav atas: selected = solid aksen + putih bold; tidak = transparan + abu. */
    fun styleSegment(btn: Button, selected: Boolean) {
        val ctx = btn.context
        if (selected) {
            btn.background = roundedBg(ACCENT1, ctx.dp(14).toFloat())
            btn.setTextColor(WHITE)
            btn.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
        } else {
            btn.background = ColorDrawable(0x00000000)
            btn.setTextColor(TEXT2)
            btn.setTypeface(btn.typeface, android.graphics.Typeface.NORMAL)
        }
        btn.isAllCaps = false
        btn.stateListAnimator = null
    }

    // ================= Icon buttons (line-icon, tanpa emoji) =================

    /** ImageView klikabel berisi line-icon; padding agar area sentuh nyaman. */
    fun Context.iconAction(
        kind: ZeniIcons.Kind,
        tint: Int,
        sizeDp: Int,
        paddingDp: Int = 8
    ): android.widget.ImageView =
        android.widget.ImageView(this).apply {
            setImageDrawable(ZeniIcons.drawable(this@iconAction, kind, tint))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            val s = dp(sizeDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
            pressedFeedback()
        }

    /** Tombol ikon lingkaran: bg lingkaran + ikon terpusat. */
    fun Context.circleIconButton(
        kind: ZeniIcons.Kind,
        tint: Int,
        diameterDp: Int,
        bgColor: Int = SURFACE2
    ): android.widget.ImageView =
        iconAction(kind, tint, diameterDp, paddingDp = diameterDp / 4).apply {
            background = circleBg(bgColor)
        }

    /** Tombol play utama: lingkaran gradient aksen + ikon putih. */
    fun Context.accentCircleIconButton(kind: ZeniIcons.Kind, diameterDp: Int): android.widget.ImageView =
        iconAction(kind, WHITE, diameterDp, paddingDp = diameterDp / 4).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(ACCENT1, ACCENT2)
            }
        }

    /** Ganti ikon sebuah iconAction (mis. play <-> pause) dengan tint baru. */
    fun android.widget.ImageView.setZeniIcon(kind: ZeniIcons.Kind, tint: Int) {
        setImageDrawable(ZeniIcons.drawable(context, kind, tint))
    }

    fun Context.hrow(block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            block()
        }

    fun Context.vcol(block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            block()
        }

    fun LinearLayout.add(view: View, w: Int, h: Int, weight: Float = 0f) {
        val lp = LinearLayout.LayoutParams(w, h, weight)
        addView(view, lp)
    }

    fun Context.trackListView(): ListView = ListView(this).apply {
        setBackgroundColor(BG)
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = true
        clipToPadding = false
    }

    /** Empty state: teks abu italic terpusat. */
    fun Context.emptyView(text: String): TextView = textView(text, 14f, TEXT2).apply {
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.ITALIC)
        setPadding(dp(32), dp(48), dp(32), dp(48))
    }

    /** Style SeekBar: track surface2 rounded + progress gradient violet + thumb lingkaran violet. */
    fun Context.styleSeekBar(sb: SeekBar) {
        val r = dp(4).toFloat()
        val barH = dp(6)
        val bgTrack = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(SURFACE2)
            cornerRadius = r
        }
        val progress = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ACCENT1, ACCENT2)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
        }
        // Progress HARUS dibungkus ClipDrawable agar terpotong sesuai nilai progress.
        // Tanpa ini, progress digambar full-width dan tidak terlihat pergerakannya.
        val progressClip = ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        val layer = LayerDrawable(arrayOf(bgTrack, progressClip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        sb.progressDrawable = layer
        // Samakan tinggi track background & progress agar rapi
        try {
            val lp = sb.layoutParams
            if (lp != null && lp.height < barH) lp.height = barH
        } catch (_: Exception) { }
        val thumbSize = dp(18)
        sb.thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT2)
            setSize(thumbSize, thumbSize)
        }
        sb.thumbOffset = thumbSize / 2
        sb.splitTrack = false
    }

    /** Dialog dengan window background surface rounded 20dp. Panggil setelah show(). */
    fun AlertDialog.styleAsCard() {
        window?.setBackgroundDrawable(
            roundedBg(SURFACE, context.dp(20).toFloat())
        )
    }

    /** Ambil drawable id logo; 0 bila tidak ada. */
    fun Context.logoResId(): Int =
        resources.getIdentifier("zeni_logo", "drawable", packageName)

    fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    // ================= Animasi halus (soft, clean, ringan) =================

    /** Interpolator lembut standar. */
    private val easeOut = android.view.animation.DecelerateInterpolator(1.2f)
    private val bounce = android.view.animation.OvershootInterpolator(1.4f)

    /**
     * Fade + slide masuk. direction: 1 = dari kanan, -1 = dari kiri, 0 = dari bawah.
     * Durasi 220ms — cepat dan halus.
     */
    fun View.fadeSlideIn(direction: Int = 0, duration: Long = 220) {
        val d = context.dp(24).toFloat()
        alpha = 0f
        when (direction) {
            1 -> { translationX = d; translationY = 0f }
            -1 -> { translationX = -d; translationY = 0f }
            else -> { translationX = 0f; translationY = d }
        }
        animate().alpha(1f).translationX(0f).translationY(0f)
            .setDuration(duration).setInterpolator(easeOut).start()
    }

    /** Efek pop (memantul lembut) — untuk tombol play, heart, dsb. */
    fun View.pop(duration: Long = 280) {
        scaleX = 0.85f; scaleY = 0.85f
        animate().scaleX(1f).scaleY(1f)
            .setDuration(duration).setInterpolator(bounce).start()
    }

    /** Slide naik halus — untuk mini player saat muncul. */
    fun View.slideUpIn(duration: Long = 260) {
        val d = context.dp(64).toFloat()
        alpha = 0f; translationY = d
        animate().alpha(1f).translationY(0f)
            .setDuration(duration).setInterpolator(easeOut).start()
    }

    /**
     * Stagger: animasikan anak-anak ViewGroup satu per satu dari bawah.
     * Dipakai saat list dimuat agar terasa hidup.
     */
    fun ViewGroup.staggerIn(delayEach: Long = 60, duration: Long = 240) {
        for (i in 0 until childCount) {
            val c = getChildAt(i) ?: continue
            c.alpha = 0f
            c.translationY = context.dp(16).toFloat()
            c.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * delayEach)
                .setDuration(duration).setInterpolator(easeOut).start()
        }
    }
}

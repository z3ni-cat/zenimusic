package com.zenimusic.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Satu baris lirik dengan efek karaoke sweep.
 *
 * Seluruh teks digambar redup (dimColor), lalu bagian yang "sudah dinyanyikan"
 * digambar terang (brightColor + glow) dengan clip selebar progress x lebar teks.
 * Digambar manual via Canvas (bukan TextView) agar bisa dipakai ulang untuk
 * render video tanpa layout View — lihat [renderTo].
 *
 * Tidak bergantung pada state activity apa pun: cukup set [text], [textSizeSp],
 * warna, lalu [setProgress]. Untuk render off-screen (video), pakai [renderTo]
 * setelah mengatur properti — tidak perlu measure/layout.
 */
class KaraokeLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Teks baris lirik. */
    var text: String = ""
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    /** Ukuran teks dalam sp (default 22sp). */
    var textSizeSp: Float = 22f
        set(v) { if (field != v) { field = v; applyTextSize(); requestLayout(); invalidate() } }

    /** Tebal (untuk baris aktif). */
    var bold: Boolean = false
        set(v) { if (field != v) { field = v; applyTypeface(); requestLayout(); invalidate() } }

    /** Warna bagian yang belum dinyanyikan. */
    var dimColor: Int = 0xFFA8A3B8.toInt()
        set(v) { field = v; dimPaint.color = v; invalidate() }

    /** Warna bagian yang sudah dinyanyikan. */
    var brightColor: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; brightPaint.color = v; invalidate() }

    /** Warna glow pada bagian terang. */
    var glowColor: Int = 0x99A78BFA.toInt()
        set(v) { field = v; applyShadow(); invalidate() }

    /**
     * Otomatis kecilkan skala gambar agar teks panjang muat dalam lebar view
     * (safezone layar). Tidak mengubah [textSizeSp] — hanya skala canvas saat
     * menggambar, jadi kalkulasi layout video ([contentWidth]) tidak berubah.
     */
    var autoFit: Boolean = true

    /** Skala minimum auto-fit; di bawah ini teks dibiarkan terpotong. */
    var autoFitMinScale: Float = 0.5f

    private var progress: Float = 0f

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Ruang ekstra agar glow/shadow tidak terpotong di tepi. */
    private val shadowPad: Float

    init {
        shadowPad = 14f * context.resources.displayMetrics.density
        dimPaint.color = dimColor
        brightPaint.color = brightColor
        applyTextSize()
        applyTypeface()
        applyShadow()
        // ShadowLayer konsisten di software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun applyTextSize() {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, textSizeSp, context.resources.displayMetrics)
        dimPaint.textSize = px
        brightPaint.textSize = px
        applyShadow()
    }

    private fun applyTypeface() {
        val tf = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        dimPaint.typeface = tf
        brightPaint.typeface = tf
    }

    private fun applyShadow() {
        brightPaint.setShadowLayer(brightPaint.textSize * 0.35f, 0f, 0f, glowColor)
    }

    /** Progress karaoke 0..1 — otomatis invalidate bila berubah. */
    fun setProgress(p: Float) {
        val c = p.coerceIn(0f, 1f)
        if (c != progress) {
            progress = c
            invalidate()
        }
    }

    fun getProgress(): Float = progress

    /** Lebar konten teks (px) pada ukuran teks saat ini — untuk kalkulasi layout video. */
    fun contentWidth(): Float = dimPaint.measureText(text)

    /** Tinggi konten teks (px) — untuk kalkulasi layout video. */
    fun contentHeight(): Float {
        val fm = dimPaint.fontMetrics
        return fm.descent - fm.ascent
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (contentWidth() + 2 * shadowPad + paddingLeft + paddingRight).toInt()
        val h = (contentHeight() + 2 * shadowPad + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawKaraoke(canvas, 0f, 0f, width.toFloat(), height.toFloat())
    }

    /**
     * Gambar langsung ke [canvas] di dalam kotak (x, y, boxW, boxH); teks terpusat.
     * Untuk render video tanpa measure/layout View: buat instance, set text /
     * textSizeSp / warna / progress, lalu panggil ini per frame.
     */
    fun renderTo(canvas: Canvas, x: Float, y: Float, boxW: Float, boxH: Float) {
        drawKaraoke(canvas, x, y, boxW, boxH)
    }

    private fun drawKaraoke(canvas: Canvas, left: Float, top: Float, boxW: Float, boxH: Float) {
        if (text.isEmpty() || boxW <= 0f || boxH <= 0f) return
        val l = left + shadowPad
        val t = top + shadowPad
        val bw = boxW - 2 * shadowPad
        val bh = boxH - 2 * shadowPad
        if (bw <= 0f || bh <= 0f) return
        val textW = dimPaint.measureText(text)
        val fm = dimPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val sx = l + (bw - textW) / 2f
        val baseline = t + (bh - textH) / 2f - fm.ascent
        // Auto-fit: kecilkan skala canvas agar baris panjang muat dalam safezone.
        val fit = if (autoFit && textW > bw && textW > 0f)
            (bw / textW).coerceAtLeast(autoFitMinScale) else 1f
        canvas.save()
        if (fit < 1f) canvas.scale(fit, fit, l + bw / 2f, t + bh / 2f)
        // Lapisan redup: seluruh teks.
        canvas.drawText(text, sx, baseline, dimPaint)
        // Lapisan terang: clip selebar progress (sweep karaoke) + glow.
        if (progress > 0f && textW > 0f) {
            canvas.save()
            canvas.clipRect(sx, t, sx + textW * progress, t + bh)
            canvas.drawText(text, sx, baseline, brightPaint)
            canvas.restore()
        }
        canvas.restore()
    }
}

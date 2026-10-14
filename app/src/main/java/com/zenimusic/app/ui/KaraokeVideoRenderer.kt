package com.zenimusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import com.zenimusic.app.data.repo.LyricLine
import java.io.File
import java.io.FileOutputStream

/**
 * Render kartu lirik karaoke animasi menjadi video MP4 pendek (720x1280, 30fps,
 * 10 detik) yang bisa dibagikan ke WhatsApp/Status. Tiap frame digambar via
 * [KaraokeLineView.renderTo] dengan progress dari timing lirik — sama seperti
 * animasi karaoke di layar.
 *
 * Encoder: MediaCodec AVC + input Surface + MediaMuxer. Karena Canvas pada
 * input Surface tidak bisa mengatur presentation timestamp manual, loop render
 * di-pacing ke real-time 30fps sehingga timing video benar.
 *
 * Bila encoder gagal di perangkat tertentu: fallback render SATU frame ke PNG.
 * Semua kerja berat di thread background; [onDone] selalu di main thread.
 */
object KaraokeVideoRenderer {

    private const val W = 720
    private const val H = 1280
    private const val FPS = 30
    private const val BIT_RATE = 4_000_000

    private class DrainState {
        var trackIndex: Int = -1
        var muxerStarted: Boolean = false
        var eos: Boolean = false
    }

    fun render(
        context: Context,
        lines: List<LyricLine>,
        title: String,
        artist: String,
        cover: Bitmap?,
        startMs: Long,
        durationMs: Long = 10_000L,
        onDone: (File?) -> Unit
    ) {
        val appCtx = context.applicationContext
        val safeLines = lines.toList()
        Thread({
            val result: File? = try {
                renderVideo(appCtx, safeLines, title, artist, cover, startMs, durationMs)
            } catch (e: Exception) {
                try {
                    renderFallbackPng(appCtx, safeLines, title, artist, cover, startMs)
                } catch (_: Exception) {
                    null
                }
            }
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }, "karaoke-video").start()
    }

    // ================= Video MP4 =================

    private fun renderVideo(
        context: Context,
        lines: List<LyricLine>,
        title: String,
        artist: String,
        cover: Bitmap?,
        startMs: Long,
        durationMs: Long
    ): File {
        val totalFrames = (durationMs * FPS / 1000L).toInt().coerceAtLeast(1)
        val outFile = File(context.cacheDir, "karaoke_${System.currentTimeMillis()}.mp4")
        val painter = FramePainter(context, lines, title, artist, cover)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        val state = DrainState()
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val surf = surface ?: throw IllegalStateException("input surface null")
            val mx = muxer ?: throw IllegalStateException("muxer null")

            val frameIntervalNs = 1_000_000_000L / FPS
            val t0 = System.nanoTime()
            for (i in 0 until totalFrames) {
                val t = startMs + i * 1000L / FPS
                val canvas = surf.lockCanvas(null)
                try {
                    painter.drawFrame(canvas, t)
                } finally {
                    surf.unlockCanvasAndPost(canvas)
                }
                drain(codec, mx, info, state, endOfStream = false)
                // Pacing real-time agar timestamp frame benar (Canvas tak bisa set PTS manual).
                val targetNs = t0 + (i + 1) * frameIntervalNs
                val sleepMs = (targetNs - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
            codec.signalEndOfInputStream()
            drain(codec, mx, info, state, endOfStream = true)
            if (!state.muxerStarted) throw IllegalStateException("muxer tak pernah start")
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
            try { surface?.release() } catch (_: Exception) { }
            try {
                if (state.muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            painter.recycle()
        }
        return outFile
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        state: DrainState,
        endOfStream: Boolean
    ) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (state.muxerStarted) throw IllegalStateException("format berubah 2x")
                    state.trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.muxerStarted = true
                }
                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        if (!state.muxerStarted) throw IllegalStateException("data sebelum muxer start")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(state.trackIndex, buf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        state.eos = true
                        return
                    }
                }
            }
        }
    }

    // ================= Fallback PNG =================

    private fun renderFallbackPng(
        context: Context,
        lines: List<LyricLine>,
        title: String,
        artist: String,
        cover: Bitmap?,
        startMs: Long
    ): File {
        val painter = FramePainter(context, lines, title, artist, cover)
        try {
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            painter.drawFrame(canvas, startMs)
            val outFile = File(context.cacheDir, "karaoke_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bmp.recycle()
            return outFile
        } finally {
            painter.recycle()
        }
    }

    // ================= Penggambar frame =================

    /**
     * Menggambar satu frame kartu karaoke pada waktu [t] (ms posisi lagu).
     * Dipakai untuk tiap frame video maupun fallback PNG.
     */
    private class FramePainter(
        context: Context,
        private val lines: List<LyricLine>,
        private val title: String,
        private val artist: String,
        cover: Bitmap?
    ) {
        private val metrics: DisplayMetrics = context.resources.displayMetrics
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var blurredBg: Bitmap? = null

        private val kvPrev = KaraokeLineView(context.applicationContext)
        private val kvActive = KaraokeLineView(context.applicationContext)
        private val kvNext = KaraokeLineView(context.applicationContext)

        init {
            // Background: cover di-blur murah (downscale lalu upscale dengan filter).
            blurredBg = cover?.let {
                val small = Bitmap.createScaledBitmap(it, 24, 43, true)
                Bitmap.createScaledBitmap(small, W, H, true).also { _ -> small.recycle() }
            }
            scrimPaint.shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xDD000000.toInt(), 0x88000000.toInt(), 0xDD000000.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            titlePaint.apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 44f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            artistPaint.apply {
                color = 0xFFB9B3C7.toInt()
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            markPaint.apply {
                color = 0x99FFFFFF.toInt()
                textSize = 26f
                textAlign = Paint.Align.CENTER
            }
            val dimLine = 0xFF6E6880.toInt()
            kvPrev.apply {
                textSizeSp = pxToSp(30f); dimColor = dimLine; brightColor = dimLine
            }
            kvActive.apply {
                textSizeSp = pxToSp(48f); bold = true
            }
            kvNext.apply {
                textSizeSp = pxToSp(30f); dimColor = dimLine; brightColor = dimLine
            }
        }

        private fun pxToSp(px: Float): Float = px / metrics.scaledDensity

        fun drawFrame(canvas: Canvas, t: Long) {
            // 1) Background + scrim gelap (glass: tidak terlalu terang).
            val bg = blurredBg
            if (bg != null) {
                canvas.drawBitmap(bg, null, Rect(0, 0, W, H), bgPaint)
            } else {
                canvas.drawColor(0xFF1A1626.toInt())
            }
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), scrimPaint)

            // 2) Judul + artis di atas.
            canvas.drawText(title, W / 2f, 150f, titlePaint)
            canvas.drawText(artist, W / 2f, 195f, artistPaint)

            // 3) Tiga baris lirik terpusat vertikal.
            if (lines.isNotEmpty()) {
                var idx = lines.indexOfLast { it.timeMs <= t }
                if (idx < 0) idx = 0
                if (idx >= lines.size) idx = lines.size - 1
                val cur = lines[idx]
                val nextStart = lines.getOrNull(idx + 1)?.timeMs
                val end = nextStart ?: (cur.timeMs + 4000L)
                val span = (end - cur.timeMs).coerceAtLeast(1L)
                val p = ((t - cur.timeMs).toFloat() / span).coerceIn(0f, 1f)

                kvPrev.text = lines.getOrNull(idx - 1)?.text.orEmpty()
                kvActive.text = cur.text
                kvNext.text = lines.getOrNull(idx + 1)?.text.orEmpty()
                kvPrev.setProgress(1f)
                kvActive.setProgress(p)
                kvNext.setProgress(0f)

                val boxH = 120f
                val boxW = W.toFloat()
                val startY = (H - boxH * 3) / 2f
                kvPrev.renderTo(canvas, 0f, startY, boxW, boxH)
                kvActive.renderTo(canvas, 0f, startY + boxH, boxW, boxH)
                kvNext.renderTo(canvas, 0f, startY + boxH * 2, boxW, boxH)
            }

            // 4) Watermark kecil.
            canvas.drawText("ZeniMusic", W / 2f, H - 60f, markPaint)
        }

        fun recycle() {
            blurredBg?.recycle()
            blurredBg = null
        }
    }
}

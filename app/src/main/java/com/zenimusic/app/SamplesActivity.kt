package com.zenimusic.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.ui.AppViewModel
import com.zenimusic.app.ui.ImageLoader
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.UiKit.ACCENT2
import com.zenimusic.app.ui.UiKit.BG
import com.zenimusic.app.ui.UiKit.FAV
import com.zenimusic.app.ui.UiKit.SURFACE2
import com.zenimusic.app.ui.UiKit.TEXT
import com.zenimusic.app.ui.UiKit.TEXT2
import com.zenimusic.app.ui.UiKit.accentCircleIconButton
import com.zenimusic.app.ui.UiKit.circleIconButton
import com.zenimusic.app.ui.UiKit.dp
import com.zenimusic.app.ui.UiKit.hrow
import com.zenimusic.app.ui.UiKit.iconAction
import com.zenimusic.app.ui.UiKit.matchWrap
import com.zenimusic.app.ui.UiKit.roundedCorners
import com.zenimusic.app.ui.UiKit.textView
import com.zenimusic.app.ui.UiKit.vcol
import com.zenimusic.app.ui.ZeniIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Samples ala TikTok: swipe vertikal untuk discover lagu baru.
 * Tiap kartu auto-play preview; swipe atas = berikutnya, bawah = sebelumnya.
 * Tap kartu = putar full di PlayerActivity.
 */
class SamplesActivity : Activity() {

    private lateinit var uiScope: CoroutineScope
    private lateinit var vm: AppViewModel

    private lateinit var cardWrap: FrameLayout
    private lateinit var ivArt: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvCounter: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnFav: ImageView

    private var samples: List<Track> = emptyList()
    private var index = 0
    private var loadJob: Job? = null
    private var favJob: Job? = null
    private var isFav = false
    private var animating = false

    // Cache per sesi
    companion object {
        private var cachedSamples: List<Track> = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        val app = application as? ZeniMusicApp ?: run { finish(); return }
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm = AppViewModel(app)

        buildUi()
        loadSamples()

        uiScope.launch {
            vm.toast.collect { msg ->
                msg?.let {
                    Toast.makeText(this@SamplesActivity, it, Toast.LENGTH_SHORT).show()
                    vm.consumeToast()
                }
            }
        }
    }

    private fun loadSamples() {
        if (cachedSamples.isNotEmpty()) {
            samples = cachedSamples
            showSample(0, animate = false)
            return
        }
        tvStatus.text = "Mencari lagu viral…"
        tvStatus.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = uiScope.launch {
            try {
                val tracks = vm.samples()
                if (tracks.isEmpty()) {
                    tvStatus.text = "Gagal memuat. Periksa koneksi lalu buka lagi."
                    return@launch
                }
                cachedSamples = tracks
                samples = tracks
                tvStatus.visibility = View.GONE
                showSample(0, animate = false)
            } catch (e: Exception) {
                tvStatus.text = "Gagal memuat. Periksa koneksi lalu buka lagi."
            }
        }
    }

    private fun showSample(i: Int, animate: Boolean, fromTop: Boolean = true) {
        if (samples.isEmpty() || animating) return
        index = i.coerceIn(0, samples.size - 1)
        val track = samples[index]

        val apply = {
            tvTitle.text = track.title
            tvChannel.text = track.channel
            tvCounter.text = "${index + 1} / ${samples.size}"
            ImageLoader.load(track.thumbnailUrl, ivArt)
            watchFavorite(track.videoId)
            // Auto-play preview
            PlayerManager.playTrack(track, samples)
        }

        if (!animate) {
            apply()
            return
        }
        animating = true
        val h = cardWrap.height.takeIf { it > 0 } ?: 800
        val startY = if (fromTop) h.toFloat() else -h.toFloat()
        cardWrap.translationY = startY
        cardWrap.alpha = 0.4f
        // Terapkan konten di tengah animasi masuk agar mulus
        apply()
        cardWrap.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(280)
            .withEndAction { animating = false }
            .start()
    }

    private fun next() {
        if (samples.isEmpty() || animating) return
        val nextIdx = (index + 1) % samples.size
        // Animasi keluar ke atas dulu, lalu masuk dari bawah
        animating = true
        val exitY = -(if (cardWrap.height != 0) cardWrap.height.toFloat() else 800f)
        cardWrap.animate()
            .translationY(exitY)
            .alpha(0.4f)
            .setDuration(180)
            .withEndAction {
                animating = false
                showSample(nextIdx, animate = true, fromTop = true)
            }
            .start()
    }

    private fun prev() {
        if (samples.isEmpty() || animating) return
        val prevIdx = if (index - 1 < 0) samples.size - 1 else index - 1
        animating = true
        val exitY = if (cardWrap.height != 0) cardWrap.height.toFloat() else 800f
        cardWrap.animate()
            .translationY(exitY)
            .alpha(0.4f)
            .setDuration(180)
            .withEndAction {
                animating = false
                showSample(prevIdx, animate = true, fromTop = false)
            }
            .start()
    }

    private fun watchFavorite(videoId: String) {
        favJob?.cancel()
        favJob = uiScope.launch {
            vm.isFavoriteFlow(videoId).collect { fav ->
                isFav = fav
                with(UiKit) {
                    btnFav.setZeniIcon(
                        if (fav) ZeniIcons.Kind.HEART_FILLED else ZeniIcons.Kind.HEART,
                        if (fav) FAV else TEXT2)
                }
            }
        }
    }

    private fun buildUi() = with(UiKit) {
        val root = FrameLayout(this@SamplesActivity).apply {
            setBackgroundColor(BG)
        }

        // Kartu tengah
        cardWrap = FrameLayout(this@SamplesActivity)
        val col = vcol {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }

        // Bar atas: close
        val topRow = hrow {}
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { finish() }
        topRow.addView(btnClose)
        topRow.add(View(this@SamplesActivity), 0, dp(1), 1f)
        tvCounter = textView("", 12f, TEXT2).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(tvCounter)
        col.addView(topRow, matchWrap())

        // Artwork besar
        ivArt = ImageView(this@SamplesActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE2)
            roundedCorners(24, 8)
        }
        val artLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply {
            topMargin = dp(24); bottomMargin = dp(24)
        }
        // Tap kartu = putar full
        ivArt.isClickable = true
        ivArt.isFocusable = true
        ivArt.setOnClickListener {
            startActivity(Intent(this@SamplesActivity, PlayerActivity::class.java))
        }
        col.addView(ivArt, artLp)

        tvTitle = textView("", 20f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SamplesActivity, PlayerActivity::class.java))
            }
        }
        col.addView(tvTitle, matchWrap())
        tvChannel = textView("", 14f, TEXT2).apply { gravity = Gravity.CENTER }
        val chLp = matchWrap().apply { bottomMargin = dp(12) }
        col.addView(tvChannel, chLp)

        tvStatus = textView("", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(tvStatus, matchWrap())

        // Bar bawah: favorit
        val botRow = hrow { gravity = Gravity.CENTER }
        btnFav = circleIconButton(ZeniIcons.Kind.HEART, TEXT2, 56)
        btnFav.setOnClickListener {
            samples.getOrNull(index)?.let { vm.toggleFavorite(it) }
        }
        botRow.addView(btnFav)
        val botLp = matchWrap().apply { topMargin = dp(8) }
        col.addView(botRow, botLp)

        // Hint swipe
        val tvHint = textView("↑ Swipe untuk lagu berikutnya ↓", 11f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(tvHint, matchWrap())

        cardWrap.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cardWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Gesture: swipe vertikal
        val detector = GestureDetector(this@SamplesActivity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val dy = (e2.y - (e1?.y ?: e2.y))
                    // Swipe vertikal dominan
                    if (kotlin.math.abs(dy) > 120 &&
                        kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX)) {
                        if (dy < 0) next() else prev()
                        return true
                    }
                    return false
                }
            })
        root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

        setContentView(root)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        favJob?.cancel()
        if (::uiScope.isInitialized) uiScope.cancel()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        super.onDestroy()
    }
}

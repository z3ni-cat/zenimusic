package com.zenimusic.app

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.model.formatDurationMs
import com.zenimusic.app.data.repo.LyricLine
import com.zenimusic.app.data.repo.LyricsRepository
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
import com.zenimusic.app.ui.UiKit.setZeniIcon
import com.zenimusic.app.ui.UiKit.styleSeekBar
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
 * Layar player penuh v2.3.0: line-icon modern, artwork besar dengan shadow,
 * SeekBar custom aksen. Data dari PlayerManager.uiState.
 */
class PlayerActivity : Activity() {

    private lateinit var uiScope: CoroutineScope
    private lateinit var vm: AppViewModel

    private lateinit var ivArt: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageView
    private lateinit var btnShuffle: ImageView
    private lateinit var btnRepeat: ImageView
    private lateinit var btnFav: ImageView
    private lateinit var btnDl: ImageView
    private lateinit var btnLyrics: ImageView

    // Overlay lirik ala Apple Music
    private lateinit var lyricsOverlay: FrameLayout
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsBox: LinearLayout
    private lateinit var tvLyricsTitle: TextView
    private lateinit var tvLyricsStatus: TextView
    private var lyricLines: List<LyricLine> = emptyList()
    private var lyricViews: List<TextView> = emptyList()
    private var lyricIndex = -1
    private var lyricsJob: Job? = null
    private var lyricsSync: Runnable? = null
    private val lyricsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var tracking = false
    private var currentTrack: Track? = null
    private var isFav = false
    private var favJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        val app = application as? ZeniMusicApp ?: run { finish(); return }
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm = AppViewModel(app)

        buildUi()
        uiScope.launch {
            PlayerManager.uiState.collect { st ->
                val cur = st.current
                if (cur != null && cur.videoId != currentTrack?.videoId) {
                    currentTrack = cur
                    tvTitle.text = cur.title
                    tvChannel.text = cur.channel
                    ImageLoader.load(cur.thumbnailUrl, ivArt)
                    watchFavorite(cur.videoId)
                }
                with(UiKit) {
                    btnPlay.setZeniIcon(
                        if (st.isPlaying) ZeniIcons.Kind.PAUSE else ZeniIcons.Kind.PLAY,
                        UiKit.WHITE)
                }
                updateToggle(btnShuffle, st.shuffle, ZeniIcons.Kind.SHUFFLE)
                updateToggle(btnRepeat, st.repeatOne, ZeniIcons.Kind.REPEAT)
                if (!tracking) {
                    val dur = st.durationMs
                    seekBar.max = dur.coerceAtLeast(0).toInt()
                    seekBar.progress = st.positionMs.coerceAtLeast(0).toInt().coerceAtMost(seekBar.max)
                    tvPos.text = formatDurationMs(st.positionMs)
                    tvDur.text = formatDurationMs(dur)
                }
                btnDl.alpha = if (cur == null) 0.4f else 1f
            }
        }
        uiScope.launch {
            vm.toast.collect { msg ->
                msg?.let {
                    Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_SHORT).show()
                    vm.consumeToast()
                }
            }
        }
        uiScope.launch {
            vm.downloadingIds.collect { ids ->
                val cur = currentTrack
                btnDl.alpha = if (cur != null && ids.contains(cur.videoId)) 0.4f else 1f
            }
        }
    }

    private fun watchFavorite(videoId: String) {
        favJob?.cancel()
        favJob = uiScope.launch {
            vm.isFavoriteFlow(videoId).collect { fav ->
                isFav = fav
                with(UiKit) {
                    btnFav.setZeniIcon(
                        if (fav) ZeniIcons.Kind.HEART_FILLED else ZeniIcons.Kind.HEART,
                        if (fav) FAV else ACCENT2)
                }
            }
        }
    }

    private fun updateToggle(btn: ImageView, on: Boolean, kind: ZeniIcons.Kind) {
        with(UiKit) {
            btn.setZeniIcon(kind, if (on) ACCENT2 else TEXT2)
        }
    }

    private fun buildUi() = with(UiKit) {
        val playerUi = vcol {
            setBackgroundColor(BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }

        val topRow = hrow {}
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { finish() }
        topRow.addView(btnClose)
        topRow.add(View(this@PlayerActivity), 0, dp(1), 1f)
        playerUi.addView(topRow, matchWrap())

        // Artwork: rounded 24dp + shadow via outlineProvider + elevation
        ivArt = ImageView(this@PlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE2)
            roundedCorners(24, 8)
        }
        val artLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply {
            topMargin = dp(20); bottomMargin = dp(24)
        }
        playerUi.addView(ivArt, artLp)

        tvTitle = textView("", 20f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        playerUi.addView(tvTitle, matchWrap())
        tvChannel = textView("", 14f, TEXT2).apply { gravity = Gravity.CENTER }
        val chLp = matchWrap().apply { bottomMargin = dp(16) }
        playerUi.addView(tvChannel, chLp)

        val seekRow = hrow {}
        tvPos = textView("--:--", 12f, TEXT2).apply { gravity = Gravity.CENTER }
        seekBar = SeekBar(this@PlayerActivity)
        styleSeekBar(seekBar)
        tvDur = textView("--:--", 12f, TEXT2).apply { gravity = Gravity.CENTER }
        seekRow.addView(tvPos, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        seekRow.add(seekBar, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        seekRow.addView(tvDur, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        playerUi.addView(seekRow, matchWrap())

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = formatDurationMs(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { tracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                tracking = false
                PlayerManager.seekTo((sb?.progress ?: 0).toLong())
            }
        })

        // Kontrol utama: prev/next 56dp, play 72dp gradient
        val ctrlRow = hrow { gravity = Gravity.CENTER }
        val btnPrev = circleIconButton(ZeniIcons.Kind.PREV, TEXT, 56)
        btnPrev.setOnClickListener { PlayerManager.previous() }
        btnPlay = accentCircleIconButton(ZeniIcons.Kind.PLAY, 72)
        btnPlay.setOnClickListener { PlayerManager.togglePlayPause() }
        val btnNext = circleIconButton(ZeniIcons.Kind.NEXT, TEXT, 56)
        btnNext.setOnClickListener { PlayerManager.next() }
        ctrlRow.addView(btnPrev)
        ctrlRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(16), dp(1)))
        ctrlRow.addView(btnPlay)
        ctrlRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(16), dp(1)))
        ctrlRow.addView(btnNext)
        val ctrlLp = matchWrap().apply { topMargin = dp(12); bottomMargin = dp(12) }
        playerUi.addView(ctrlRow, ctrlLp)

        // Opsi: shuffle / repeat / favorit / download — 48dp
        val optRow = hrow { gravity = Gravity.CENTER }
        btnShuffle = circleIconButton(ZeniIcons.Kind.SHUFFLE, TEXT2, 48)
        btnShuffle.setOnClickListener { PlayerManager.toggleShuffle() }
        btnRepeat = circleIconButton(ZeniIcons.Kind.REPEAT, TEXT2, 48)
        btnRepeat.setOnClickListener { PlayerManager.toggleRepeat() }
        btnFav = circleIconButton(ZeniIcons.Kind.HEART, ACCENT2, 48)
        btnFav.setOnClickListener { currentTrack?.let { vm.toggleFavorite(it) } }
        btnDl = circleIconButton(ZeniIcons.Kind.DOWNLOAD, TEXT, 48)
        btnDl.setOnClickListener { currentTrack?.let { vm.download(it) } }
        btnLyrics = circleIconButton(ZeniIcons.Kind.LYRICS, TEXT2, 48)
        btnLyrics.setOnClickListener { openLyrics() }
        optRow.addView(btnShuffle)
        optRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(10), dp(1)))
        optRow.addView(btnRepeat)
        optRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(10), dp(1)))
        optRow.addView(btnFav)
        optRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(10), dp(1)))
        optRow.addView(btnDl)
        optRow.addView(View(this@PlayerActivity).apply { visibility = View.INVISIBLE },
            LinearLayout.LayoutParams(dp(10), dp(1)))
        optRow.addView(btnLyrics)
        playerUi.addView(optRow, matchWrap())

        buildLyricsOverlay()
        val root = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(BG)
            addView(playerUi, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lyricsOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
    }

    /**
     * Overlay lirik full-screen ala Apple Music:
     * baris aktif menyala terang, auto-scroll, tap baris = lompat.
     */
    private fun buildLyricsOverlay() = with(UiKit) {
        lyricsOverlay = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
        }
        val col = vcol {
            setBackgroundColor(BG)
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        // Bar atas: tutup + judul
        val topRow = hrow {}
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { closeLyrics() }
        topRow.addView(btnClose)
        tvLyricsTitle = textView("Lirik", 17f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        topRow.addView(tvLyricsTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(topRow, matchWrap())

        tvLyricsStatus = textView("", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(tvLyricsStatus, matchWrap())

        lyricsScroll = ScrollView(this@PlayerActivity).apply {
            isFillViewport = true
        }
        lyricsBox = vcol {
            setPadding(0, dp(8), 0, dp(120))
        }
        lyricsScroll.addView(lyricsBox, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(lyricsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        lyricsOverlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun openLyrics() {
        val track = currentTrack ?: return
        lyricsOverlay.visibility = View.VISIBLE
        tvLyricsTitle.text = track.title
        tvLyricsStatus.text = "Mencari lirik…"
        tvLyricsStatus.visibility = View.VISIBLE
        lyricsBox.removeAllViews()
        lyricLines = emptyList()
        lyricViews = emptyList()
        lyricIndex = -1

        lyricsJob?.cancel()
        lyricsJob = uiScope.launch {
            val lines = LyricsRepository.getLyrics(
                track.channel, track.title, track.durationSec)
            if (lyricsOverlay.visibility != View.VISIBLE) return@launch
            if (lines.isEmpty()) {
                tvLyricsStatus.text = "Lirik tidak ditemukan untuk lagu ini."
                return@launch
            }
            tvLyricsStatus.visibility = View.GONE
            lyricLines = lines
            lyricViews = lines.mapIndexed { i, line ->
                with(UiKit) {
                    textView(line.text, 19f, TEXT2).apply {
                        setPadding(0, dp(10), 0, dp(10))
                        setLineSpacing(dp(6).toFloat(), 1f)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            PlayerManager.seekTo(line.timeMs)
                            updateLyricHighlight(true)
                        }
                    }
                }.also { lyricsBox.addView(it, matchWrap()) }
            }
            startLyricsSync()
        }
    }

    private fun closeLyrics() {
        lyricsOverlay.visibility = View.GONE
        stopLyricsSync()
        lyricsJob?.cancel()
    }

    private fun startLyricsSync() {
        stopLyricsSync()
        val r = object : Runnable {
            override fun run() {
                if (lyricsOverlay.visibility == View.VISIBLE && lyricLines.isNotEmpty()) {
                    updateLyricHighlight(false)
                    lyricsHandler.postDelayed(this, 400)
                }
            }
        }
        lyricsSync = r
        lyricsHandler.post(r)
    }

    private fun stopLyricsSync() {
        lyricsSync?.let { lyricsHandler.removeCallbacks(it) }
        lyricsSync = null
    }

    /** Sorot baris aktif sesuai posisi putar; scroll agar di tengah. */
    private fun updateLyricHighlight(fromTap: Boolean) {
        val pos = if (fromTap) {
            // setelah tap, pakai posisi target (sudah seek)
            lyricLines.getOrNull(lyricIndex)?.timeMs ?: PlayerManager.uiState.value.positionMs
        } else {
            PlayerManager.uiState.value.positionMs
        }
        var idx = lyricLines.indexOfLast { it.timeMs <= pos }
        if (idx < 0) idx = 0
        if (idx == lyricIndex) return
        // redupkan yang lama
        if (lyricIndex in lyricViews.indices) {
            lyricViews[lyricIndex].apply {
                setTextColor(TEXT2)
                setTypeface(typeface, android.graphics.Typeface.NORMAL)
            }
        }
        lyricIndex = idx
        val v = lyricViews.getOrNull(idx) ?: return
        v.apply {
            setTextColor(TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        // scroll agar baris aktif di tengah layar
        lyricsScroll.post {
            val y = v.top - lyricsScroll.height / 2 + v.height / 2
            lyricsScroll.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    override fun onDestroy() {
        stopLyricsSync()
        lyricsJob?.cancel()
        favJob?.cancel()
        if (::uiScope.isInitialized) uiScope.cancel()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
            closeLyrics()
        } else {
            super.onBackPressed()
        }
    }
}

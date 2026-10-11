package com.zenimusic.app

import android.app.Activity
import android.app.AlertDialog
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
import com.zenimusic.app.data.repo.Translator
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.player.SleepTimer
import com.zenimusic.app.ui.AppViewModel
import com.zenimusic.app.ui.ImageLoader
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.UiKit.ACCENT2
import com.zenimusic.app.ui.UiKit.BG
import com.zenimusic.app.ui.UiKit.FAV
import com.zenimusic.app.ui.UiKit.SURFACE2
import com.zenimusic.app.ui.UiKit.TEXT
import com.zenimusic.app.ui.UiKit.TEXT2
import com.zenimusic.app.ui.UiKit.WHITE
import com.zenimusic.app.ui.UiKit.accentCircleIconButton
import com.zenimusic.app.ui.UiKit.circleIconButton
import com.zenimusic.app.ui.UiKit.dp
import com.zenimusic.app.ui.UiKit.hrow
import com.zenimusic.app.ui.UiKit.iconAction
import com.zenimusic.app.ui.UiKit.matchWrap
import com.zenimusic.app.ui.UiKit.roundedBg
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
    private lateinit var btnSleep: ImageView

    // Overlay lirik ala Apple Music
    private lateinit var lyricsOverlay: FrameLayout
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsBox: LinearLayout
    private lateinit var tvLyricsTitle: TextView
    private lateinit var tvLyricsStatus: TextView
    private lateinit var btnTranslate: TextView
    private var lyricLines: List<LyricLine> = emptyList()
    private var lyricViews: List<TextView> = emptyList()
    private var lyricTransViews: List<TextView> = emptyList()
    private var lyricVideoId: String? = null
    private var translateOn = false
    private var translateJob: Job? = null
    private var lyricIndex = -1
    private var lyricsJob: Job? = null
    private var lyricsSync: Runnable? = null
    private val lyricsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Overlay antrian
    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueBox: LinearLayout
    private lateinit var tvQueueCount: TextView

    // Indikator buffering di full player
    private lateinit var bufferOverlay: FrameLayout
    private lateinit var tvBufferStatus: TextView

    // Animasi "bernapas" artwork
    private var breathSet: android.animation.AnimatorSet? = null

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
                updateBufferOverlay(st)
                st.error?.let {
                    Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_LONG).show()
                    PlayerManager.clearError()
                }
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
        uiScope.launch {
            SleepTimer.state.collect { refreshSleepIcon() }
        }
    }

    private fun refreshSleepIcon() {
        if (!::btnSleep.isInitialized) return
        updateToggle(btnSleep, SleepTimer.isActive, ZeniIcons.Kind.TIMER)
    }

    /** Dialog sleep timer: per menit atau per jumlah lagu, dengan fade-out. */
    private fun showSleepDialog() = with(UiKit) {
        val col = vcol { setPadding(dp(20), dp(20), dp(20), dp(8)) }
        col.addView(textView("Sleep Timer", 17f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        col.addView(textView("Status: ${SleepTimer.statusText()}", 13f, TEXT2).apply {
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())

        val dlg = AlertDialog.Builder(this@PlayerActivity).setView(col).create()
        fun option(label: String, action: () -> Unit) {
            val tv = textView(label, 15f, TEXT).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = true
                pressedFeedback()
            }
            tv.setOnClickListener {
                action()
                refreshSleepIcon()
                dlg.dismiss()
            }
            col.addView(tv, matchWrap())
        }
        if (SleepTimer.isActive) option("Matikan timer") { SleepTimer.cancel() }
        option("15 menit") { SleepTimer.startMinutes(15) }
        option("30 menit") { SleepTimer.startMinutes(30) }
        option("45 menit") { SleepTimer.startMinutes(45) }
        option("60 menit") { SleepTimer.startMinutes(60) }
        option("90 menit") { SleepTimer.startMinutes(90) }
        option("Setelah 1 lagu") { SleepTimer.startSongs(1) }
        option("Setelah 2 lagu") { SleepTimer.startSongs(2) }
        option("Setelah 3 lagu") { SleepTimer.startSongs(3) }
        option("Setelah 5 lagu") { SleepTimer.startSongs(5) }
        col.addView(View(this@PlayerActivity), matchWrap().apply { topMargin = dp(8) })
        dlg.show()
        dlg.styleAsCard()
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
        val btnQueue = iconAction(ZeniIcons.Kind.QUEUE, TEXT2, 40, 10)
        btnQueue.setOnClickListener { openQueue() }
        topRow.addView(btnQueue)
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
        btnPlay.setOnClickListener {
            with(UiKit) { btnPlay.pop() }
            PlayerManager.togglePlayPause()
        }
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
        btnShuffle = circleIconButton(ZeniIcons.Kind.SHUFFLE, TEXT2, 44)
        btnShuffle.setOnClickListener { PlayerManager.toggleShuffle() }
        btnRepeat = circleIconButton(ZeniIcons.Kind.REPEAT, TEXT2, 44)
        btnRepeat.setOnClickListener { PlayerManager.toggleRepeat() }
        btnFav = circleIconButton(ZeniIcons.Kind.HEART, ACCENT2, 44)
        btnFav.setOnClickListener {
            with(UiKit) { btnFav.pop() }
            currentTrack?.let { vm.toggleFavorite(it) }
        }
        btnDl = circleIconButton(ZeniIcons.Kind.DOWNLOAD, TEXT, 44)
        btnDl.setOnClickListener { currentTrack?.let { vm.download(it) } }
        btnLyrics = circleIconButton(ZeniIcons.Kind.LYRICS, TEXT2, 44)
        btnLyrics.setOnClickListener { openLyrics() }
        val btnEq = circleIconButton(ZeniIcons.Kind.EQ, TEXT2, 44)
        btnEq.setOnClickListener {
            with(UiKit) { btnEq.pop() }
            startActivity(android.content.Intent(this@PlayerActivity, EqualizerActivity::class.java))
        }
        optRow.addView(btnShuffle)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        optRow.addView(btnRepeat)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        optRow.addView(btnFav)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        optRow.addView(btnDl)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        optRow.addView(btnLyrics)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        optRow.addView(btnEq)
        optRow.addView(View(this@PlayerActivity),
            LinearLayout.LayoutParams(0, dp(1), 1f))
        btnSleep = circleIconButton(ZeniIcons.Kind.TIMER, TEXT2, 44)
        btnSleep.setOnClickListener {
            with(UiKit) { btnSleep.pop() }
            showSleepDialog()
        }
        optRow.addView(btnSleep)
        playerUi.addView(optRow, matchWrap())

        buildLyricsOverlay()
        buildQueueOverlay()
        buildBufferOverlay()
        val root = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(BG)
            addView(playerUi, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lyricsOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(queueOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bufferOverlay, FrameLayout.LayoutParams(
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
        // Toggle terjemahan Indonesia
        btnTranslate = textView("ID", 12f, TEXT2, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(SURFACE2, dp(14).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            pressedFeedback()
        }
        btnTranslate.setOnClickListener { toggleTranslate() }
        topRow.addView(btnTranslate)
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
        lyricTransViews = emptyList()
        lyricIndex = -1
        lyricVideoId = track.videoId
        translateOn = false
        translateJob?.cancel()
        refreshTranslateBtn()

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
            val originals = mutableListOf<TextView>()
            val translations = mutableListOf<TextView>()
            for (line in lines) {
                with(UiKit) {
                    val row = vcol {}
                    val tvLine = textView(line.text, 19f, TEXT2).apply {
                        setPadding(0, dp(10), 0, dp(2))
                        setLineSpacing(dp(6).toFloat(), 1f)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            PlayerManager.seekTo(line.timeMs)
                            updateLyricHighlight(true)
                        }
                    }
                    val tvTrans = textView("", 14f, TEXT2).apply {
                        setPadding(0, 0, 0, dp(10))
                        visibility = View.GONE
                    }
                    row.addView(tvLine, matchWrap())
                    row.addView(tvTrans, matchWrap())
                    lyricsBox.addView(row, matchWrap())
                    originals.add(tvLine)
                    translations.add(tvTrans)
                }
            }
            lyricViews = originals
            lyricTransViews = translations
            startLyricsSync()
        }
    }

    private fun closeLyrics() {
        lyricsOverlay.visibility = View.GONE
        stopLyricsSync()
        lyricsJob?.cancel()
        translateJob?.cancel()
    }

    // ================= Terjemahan lirik =================

    private fun refreshTranslateBtn() {
        if (!::btnTranslate.isInitialized) return
        with(UiKit) {
            btnTranslate.background = roundedBg(
                if (translateOn) ACCENT2 else SURFACE2, dp(14).toFloat())
            btnTranslate.setTextColor(if (translateOn) WHITE else TEXT2)
        }
    }

    private fun toggleTranslate() {
        if (lyricLines.isEmpty()) {
            Toast.makeText(this, "Buka lirik dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        translateOn = !translateOn
        refreshTranslateBtn()
        if (translateOn) doTranslate()
        else lyricTransViews.forEach { it.visibility = View.GONE }
    }

    private fun doTranslate() {
        val vid = lyricVideoId ?: return
        translateJob?.cancel()
        translateJob = uiScope.launch {
            tvLyricsStatus.text = "Menerjemahkan lirik…"
            tvLyricsStatus.visibility = View.VISIBLE
            try {
                // 1. Coba cache database dulu (gratis, instan)
                var list = vm.getTranslation(vid)?.split("\n")
                    ?.takeIf { it.size == lyricLines.size }
                // 2. Bila belum ada, panggil API lalu simpan ke cache
                if (list == null) {
                    list = Translator.translateLines(vid, lyricLines.map { it.text })
                    if (list != null) vm.saveTranslation(vid, list.joinToString("\n"))
                }
                if (lyricsOverlay.visibility != View.VISIBLE) return@launch
                tvLyricsStatus.visibility = View.GONE
                if (list != null && translateOn) {
                    lyricTransViews.forEachIndexed { i, tv ->
                        val tr = list.getOrElse(i) { "" }.trim()
                        tv.text = tr
                        tv.visibility = if (tr.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                } else if (translateOn) {
                    translateOn = false
                    refreshTranslateBtn()
                    tvLyricsStatus.text = "Gagal menerjemahkan. Coba lagi nanti."
                    tvLyricsStatus.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                if (lyricsOverlay.visibility != View.VISIBLE) return@launch
                translateOn = false
                refreshTranslateBtn()
                tvLyricsStatus.text = "Gagal menerjemahkan. Coba lagi nanti."
                tvLyricsStatus.visibility = View.VISIBLE
            }
        }
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

    /**
     * Overlay antrian: lihat lagu yang akan diputar, tap = lompat, X = hapus.
     */
    private fun buildQueueOverlay() = with(UiKit) {
        queueOverlay = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
        }
        val col = vcol {
            setBackgroundColor(BG)
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        val topRow = hrow {}
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { closeQueue() }
        topRow.addView(btnClose)
        val tvTitle = textView("Antrian", 17f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        topRow.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvQueueCount = textView("", 13f, TEXT2).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(tvQueueCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(topRow, matchWrap())

        val scroll = ScrollView(this@PlayerActivity).apply { isFillViewport = true }
        queueBox = vcol {
            setPadding(0, dp(12), 0, dp(24))
        }
        scroll.addView(queueBox, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        queueOverlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun openQueue() {
        queueOverlay.visibility = View.VISIBLE
        refreshQueue()
    }

    private fun closeQueue() {
        queueOverlay.visibility = View.GONE
    }

    private fun refreshQueue() = with(UiKit) {
        queueBox.removeAllViews()
        val st = PlayerManager.uiState.value
        val q = st.queue
        val cur = st.queueIndex
        tvQueueCount.text = if (q.isEmpty()) "" else "${q.size} lagu"

        if (q.isEmpty()) {
            queueBox.addView(textView("Antrian kosong.", 14f, TEXT2).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
            }, matchWrap())
            return
        }

        // Sedang diputar
        if (cur in q.indices) {
            queueBox.addView(textView("SEDANG DIPUTAR", 11f, ACCENT2, bold = true).apply {
                setPadding(0, dp(4), 0, dp(8))
            }, matchWrap())
            queueBox.addView(queueRow(q[cur], cur, isCurrent = true), matchWrap())
        }
        // Berikutnya
        val next = q.indices.filter { it != cur }
        if (next.isNotEmpty()) {
            val lbl = textView("BERIKUTNYA", 11f, TEXT2, bold = true).apply {
                setPadding(0, dp(20), 0, dp(8))
            }
            queueBox.addView(lbl, matchWrap())
            for (i in next) {
                queueBox.addView(queueRow(q[i], i, isCurrent = false), matchWrap())
            }
        }
    }

    private fun queueRow(track: Track, index: Int, isCurrent: Boolean): LinearLayout = with(UiKit) {
        val row = hrow {
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!isCurrent) {
                    PlayerManager.playAt(index)
                    refreshQueue()
                }
            }
        }
        val iv = ImageView(this@PlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE2)
            roundedCorners(10, 4)
        }
        ImageLoader.load(track.thumbnailUrl, iv)
        row.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
        val txtCol = vcol {
            setPadding(dp(12), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        txtCol.addView(textView(track.title, 14f, if (isCurrent) ACCENT2 else TEXT, bold = isCurrent).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())
        txtCol.addView(textView(track.channel, 12f, TEXT2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())
        row.addView(txtCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!isCurrent) {
            val btnRm = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 36, 8)
            btnRm.setOnClickListener {
                PlayerManager.removeAt(index)
                refreshQueue()
            }
            row.addView(btnRm, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        row
    }

    /**
     * Overlay buffering: spinner + status di tengah layar saat loading/buffering.
     * Tidak blokir tombol — user tetap bisa navigasi.
     */
    private fun buildBufferOverlay() = with(UiKit) {
        bufferOverlay = FrameLayout(this@PlayerActivity).apply {
            visibility = View.GONE
            // Biarkan sentuhan tembus ke UI di bawahnya
            isClickable = false
            isFocusable = false
        }
        val center = vcol {
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }
        val spinner = android.widget.ProgressBar(this@PlayerActivity).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT2)
        }
        center.addView(spinner, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        tvBufferStatus = textView("Memuat...", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        center.addView(tvBufferStatus, matchWrap())
        bufferOverlay.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
    }

    /** Update visibilitas overlay buffering dari state player. */
    private fun updateBufferOverlay(st: PlayerManager.UiState) {
        if (!::bufferOverlay.isInitialized) return
        val isBusy = st.loading || st.buffering
        bufferOverlay.visibility = if (isBusy) View.VISIBLE else View.GONE
        if (isBusy) {
            tvBufferStatus.text = if (st.buffering) "Buffering..." else "Memuat..."
        }
        // Artwork "bernapas" halus saat diputar, berhenti saat pause
        updateBreath(st.isPlaying && !isBusy)
    }

    /**
     * Animasi napas: artwork membesar-mengecil sangat halus (1.0 → 1.04)
     * dalam loop 2.4 detik. Berhenti saat pause agar hemat baterai.
     */
    private fun updateBreath(playing: Boolean) {
        if (!::ivArt.isInitialized) return
        if (playing) {
            if (breathSet?.isRunning == true) return
            breathSet?.cancel()
            val interp = android.view.animation.AccelerateDecelerateInterpolator()
            val sx = android.animation.ObjectAnimator.ofFloat(ivArt, "scaleX", 1f, 1.04f, 1f).apply {
                duration = 2400
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = interp
            }
            val sy = android.animation.ObjectAnimator.ofFloat(ivArt, "scaleY", 1f, 1.04f, 1f).apply {
                duration = 2400
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = interp
            }
            breathSet = android.animation.AnimatorSet().apply {
                playTogether(sx, sy)
                start()
            }
        } else {
            breathSet?.cancel()
            breathSet = null
            ivArt.scaleX = 1f; ivArt.scaleY = 1f
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
            closeLyrics()
        } else if (::queueOverlay.isInitialized && queueOverlay.visibility == View.VISIBLE) {
            closeQueue()
        } else {
            super.onBackPressed()
        }
    }
}

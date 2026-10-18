package com.zenimusic.app

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
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
import com.zenimusic.app.data.repo.MixChapter
import com.zenimusic.app.data.repo.Translator
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.player.SleepTimer
import com.zenimusic.app.ui.AppViewModel
import com.zenimusic.app.ui.ImageLoader
import com.zenimusic.app.ui.KaraokeLineView
import com.zenimusic.app.ui.KaraokeVideoRenderer
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
import kotlinx.coroutines.withContext

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
    private lateinit var tvChapter: TextView
    private lateinit var tvQuality: TextView
    private lateinit var tvSpeed: TextView
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
    private var lyricsFrameCb: Choreographer.FrameCallback? = null
    private var scrollAnim: ValueAnimator? = null
    // Estimasi posisi halus: uiState PlayerManager hanya diperbarui 500ms sekali;
    // interpolasi di antara sampel membuat lirik & sapuan mengalir tiap frame.
    private var posSampleMs = 0L
    private var posSampleAtMs = 0L
    private var posSamplePlaying = false
    /** Batas waktu auto-scroll ditahan setelah user scroll manual (uptime ms). */
    private var userScrollUntil = 0L
    /** Animasi highlight lirik yang sedang berjalan (dibatalkan saat baris berganti). */
    private val lyricAnimators = mutableListOf<Animator>()

    // Bab/tracklist di dalam video mix
    private var mixChapters: List<MixChapter> = emptyList()
    private var chaptersVideoId: String? = null
    private var chapterIndex = -1
    private var chaptersJob: Job? = null

    // Overlay antrian
    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueBox: LinearLayout
    private lateinit var queueScroll: ScrollView
    private lateinit var tvQueueCount: TextView

    // Panel karaoke: 3 baris (sebelum/aktif/berikut) dengan sweep animasi
    private lateinit var karaokeOverlay: FrameLayout
    private lateinit var kvTitle: TextView
    private lateinit var kvArtist: TextView
    private lateinit var kvPrev: KaraokeLineView
    private lateinit var kvActive: KaraokeLineView
    private lateinit var kvNext: KaraokeLineView
    private lateinit var tvKaraokeStatus: TextView
    private var karaokeIndex = -1
    private var karaokeFrameCb: Choreographer.FrameCallback? = null

    // Indikator buffering di full player
    private lateinit var bufferOverlay: FrameLayout
    private lateinit var tvBufferStatus: TextView

    // Animasi "bernapas" artwork
    private var breathSet: android.animation.AnimatorSet? = null

    // Background blur dari cover (dua lapis untuk crossfade antar lagu)
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private var bgFrontIsA = true
    private var blurBmpA: Bitmap? = null
    private var blurBmpB: Bitmap? = null
    private var bgBlurJob: Job? = null
    private var bgToken = 0

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
                samplePosition(st)
                val cur = st.current
                if (cur != null && cur.videoId != currentTrack?.videoId) {
                    currentTrack = cur
                    tvTitle.text = cur.title
                    tvTitle.isSelected = true // jalankan marquee judul panjang
                    tvChannel.text = cur.channel
                    ImageLoader.load(cur.thumbnailUrl, ivArt)
                    updateBlurBackground(cur.thumbnailUrl)
                    watchFavorite(cur.videoId)
                    // Lirik karaoke milik lagu lama — tutup agar tidak out-of-sync.
                    if (::karaokeOverlay.isInitialized &&
                        karaokeOverlay.visibility == View.VISIBLE
                    ) {
                        closeKaraoke()
                    }
                    // Panel lirik sedang dibuka — muat lirik lagu baru.
                    if (::lyricsOverlay.isInitialized &&
                        lyricsOverlay.visibility == View.VISIBLE &&
                        cur.videoId != lyricVideoId
                    ) {
                        loadLyricsFor(cur)
                    }
                    // Bab mix milik lagu lama — reset & muat ulang.
                    loadChaptersFor(cur)
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
                updateChapterLabel(st.positionMs)
                // Label kualitas audio + chip kecepatan (murah, teks saja).
                if (::tvQuality.isInitialized) {
                    val qName = try {
                        getSharedPreferences("zeni_prefs", MODE_PRIVATE)
                            .getString("audio_quality", "tinggi")
                    } catch (e: Exception) { "tinggi" }
                    val qLabel = when (qName) {
                        "hemat" -> "Hemat"; "sedang" -> "Sedang"; else -> "Tinggi"
                    }
                    tvQuality.text = when {
                        cur?.localPath != null -> "Tersimpan di HP"
                        st.audioKbps > 0 -> "Audio $qLabel • ${st.audioKbps} kbps"
                        else -> "Audio $qLabel"
                    }
                    tvSpeed.text = (if (st.speed == st.speed.toInt().toFloat()) {
                        st.speed.toInt().toString()
                    } else st.speed.toString()) + "×"
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
        // Konten transparan: background blur cover terlihat dari belakang.
        val playerUi = vcol {
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
        // Geser sampul: kiri = lagu berikutnya, kanan = sebelumnya.
        // Ketukan biasa membuka lirik.
        var artDownX = 0f
        var artDownY = 0f
        ivArt.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    artDownX = ev.x; artDownY = ev.y; true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - artDownX
                    val dy = ev.y - artDownY
                    val adx = kotlin.math.abs(dx)
                    val ady = kotlin.math.abs(dy)
                    when {
                        adx > dp(48) && adx > ady * 1.5f -> {
                            if (dx < 0) PlayerManager.next() else PlayerManager.previous()
                            val dir = if (dx < 0) -1f else 1f
                            v.animate().translationX(dir * dp(26)).setDuration(110)
                                .withEndAction {
                                    v.animate().translationX(0f).setDuration(190).start()
                                }.start()
                        }
                        adx < dp(14) && ady < dp(14) -> openLyrics()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        tvTitle = textView("", 20f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            // Judul panjang berjalan pelan (marquee) agar terbaca utuh.
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isFocusable = true
        }
        playerUi.addView(tvTitle, matchWrap())
        tvChannel = textView("", 14f, TEXT2).apply { gravity = Gravity.CENTER }
        val chLp = matchWrap().apply { bottomMargin = dp(6) }
        playerUi.addView(tvChannel, chLp)
        // Ketuk nama artis: buka pencarian artis itu di halaman Cari.
        tvChannel.isClickable = true
        tvChannel.isFocusable = true
        tvChannel.setOnClickListener {
            val q = currentTrack?.channel?.takeIf { it.isNotBlank() }
                ?: return@setOnClickListener
            startActivity(
                android.content.Intent(this@PlayerActivity, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_ARTIST, q)
                }
            )
        }
        // Label bab mix: lagu yang sedang jalan di dalam video panjang.
        // Ketuk untuk membuka daftar semua lagu di dalam mix.
        tvChapter = textView("", 13f, ACCENT2, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        tvChapter.setOnClickListener { showChaptersDialog() }
        val chapLp = matchWrap().apply { bottomMargin = dp(10) }
        playerUi.addView(tvChapter, chapLp)

        // Baris label kualitas audio (kiri) + chip kecepatan putar (kanan).
        val infoRow = hrow { gravity = Gravity.CENTER_VERTICAL }
        tvQuality = textView("", 11.5f, TEXT2).apply { maxLines = 1 }
        infoRow.add(tvQuality, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tvSpeed = textView("1.0×", 12f, TEXT, bold = true).apply {
            background = roundedBg(SURFACE2, dp(10).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
        }
        tvSpeed.setOnClickListener {
            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            val cur = PlayerManager.uiState.value.speed
            val next = speeds.firstOrNull { it > cur + 0.001f } ?: speeds.first()
            PlayerManager.setSpeed(next)
        }
        infoRow.addView(tvSpeed)
        val infoLp = matchWrap().apply { bottomMargin = dp(8) }
        playerUi.addView(infoRow, infoLp)

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
        buildKaraokeOverlay()
        buildQueueOverlay()
        buildBufferOverlay()
        // Latar blur dari cover: dua lapis (crossfade) + scrim gelap agar tidak silau.
        // Urutan paling belakang: bgA, bgB, scrim, lalu konten.
        bgA = ImageView(this@PlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        bgB = ImageView(this@PlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val scrim = View(this@PlayerActivity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000.toInt(), 0xB3000000.toInt()) // hitam 20% → hitam 70%
            )
        }
        val root = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(BG)
            addView(bgA, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bgB, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(playerUi, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lyricsOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(karaokeOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(queueOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bufferOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
    }

    /**
     * Latar blur dari cover lagu: ambil bitmap via ImageLoader, buat versi blur
     * murah (downscale → upscale), lalu crossfade 600ms antar lagu.
     * Token menjaga agar callback basi (lagu sudah ganti / activity tutup)
     * tidak menimpa background yang benar.
     */
    private fun updateBlurBackground(url: String?) {
        if (!::bgA.isInitialized) return
        val token = ++bgToken
        bgBlurJob?.cancel()
        ImageLoader.get(url) { bmp ->
            if (token != bgToken || bmp == null || bmp.isRecycled) return@get
            bgBlurJob = uiScope.launch(Dispatchers.Default) {
                val blurred = makeBlurBitmap(bmp)
                val colors = dominantColors(bmp)
                withContext(Dispatchers.Main) {
                    if (token != bgToken) {
                        if (blurred != null && !blurred.isRecycled) blurred.recycle()
                        return@withContext
                    }
                    if (blurred != null) crossfadeBlur(blurred)
                    if (colors != null) applyDynamicAccent(colors.first, colors.second)
                    else resetAccent()
                }
            }
        }
    }

    /**
     * Warna dominan sampul: kecilkan ke 16×16, kelompokkan piksel per rona
     * (hue), abaikan yang pucat/gelap/terang ekstrem, pilih kelompok paling
     * berwarna. Hasilnya dipoles ke rentang cerah yang nyaman di mata.
     */
    private fun dominantColors(src: Bitmap): Pair<Int, Int>? {
        return try {
            val small = Bitmap.createScaledBitmap(src, 16, 16, true)
            val buckets = HashMap<Int, FloatArray>() // hueBucket -> [count, satSum, rSum, gSum, bSum, weight]
            val hsv = FloatArray(3)
            for (x in 0 until 16) for (y in 0 until 16) {
                val px = small.getPixel(x, y)
                android.graphics.Color.colorToHSV(px, hsv)
                val sat = hsv[1]
                val v = hsv[2]
                if (sat < 0.25f || v < 0.15f || v > 0.97f) continue
                val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                val w = sat * v
                val arr = buckets.getOrPut(bucket) { FloatArray(6) }
                arr[0] += 1f
                arr[1] += sat
                arr[2] += android.graphics.Color.red(px)
                arr[3] += android.graphics.Color.green(px)
                arr[4] += android.graphics.Color.blue(px)
                arr[5] += w
            }
            if (small !== src) small.recycle()
            val best = buckets.values.maxByOrNull { it[5] } ?: return null
            val n = best[0].coerceAtLeast(1f)
            android.graphics.Color.colorToHSV(
                android.graphics.Color.rgb(
                    (best[2] / n).toInt(), (best[3] / n).toInt(), (best[4] / n).toInt()
                ), hsv
            )
            val c1 = android.graphics.Color.HSVToColor(
                floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.85f), 0.86f))
            val c2 = android.graphics.Color.HSVToColor(
                floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.9f), 0.62f))
            c1 to c2
        } catch (e: Exception) { null }
    }

    /** Terapkan warna sampul ke tombol putar + garis progres pemutar. */
    private fun applyDynamicAccent(c1: Int, c2: Int) {
        try {
            btnPlay.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(c1, c2)
            }
            val r = dp(4).toFloat()
            val bgTrack = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(UiKit.SURFACE2)
                cornerRadius = r
            }
            val progress = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(c1, c2)
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            val progressClip = android.graphics.drawable.ClipDrawable(
                progress, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(bgTrack, progressClip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            val prog = seekBar.progress
            val max = seekBar.max
            seekBar.progressDrawable = layer
            seekBar.thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(c2)
                val s = dp(18)
                setSize(s, s)
            }
            if (max > 0) { seekBar.max = max; seekBar.progress = prog }
        } catch (e: Exception) { /* biarkan tampilan lama */ }
    }

    /** Kembalikan aksen pemutar ke warna tema bila sampul tak memberi warna. */
    private fun resetAccent() {
        try {
            styleSeekBar(seekBar)
            btnPlay.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(UiKit.ACCENT1, UiKit.ACCENT2)
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    /**
     * Blur murah tanpa RenderScript: kecilkan ke lebar 24px lalu besarkan lagi
     * dengan filter — hasilnya blur natural. Ukuran target ~1/4 layar agar hemat memori.
     */
    private fun makeBlurBitmap(src: Bitmap): Bitmap? {
        return try {
            val smallW = 24
            val smallH = (smallW * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val dm = resources.displayMetrics
            val bigW = (dm.widthPixels / 4).coerceAtLeast(smallW)
            val bigH = (bigW * smallH / smallW.toFloat()).toInt().coerceAtLeast(1)
            val big = Bitmap.createScaledBitmap(small, bigW, bigH, true)
            small.recycle()
            big
        } catch (_: Exception) { null }
    }

    /** Tampilkan bitmap blur di lapis tersembunyi lalu fade 600ms; tukar peran A/B. */
    private fun crossfadeBlur(blurred: Bitmap) {
        if (!::bgA.isInitialized) return
        val showView = if (bgFrontIsA) bgB else bgA
        showView.animate().cancel()
        // Recycle bitmap lama milik lapis ini — tidak sedang ditampilkan penuh.
        val old = if (bgFrontIsA) blurBmpB else blurBmpA
        if (old != null && !old.isRecycled) old.recycle()
        if (bgFrontIsA) blurBmpB = blurred else blurBmpA = blurred
        showView.setImageBitmap(blurred)
        showView.alpha = 0f
        showView.animate().alpha(1f).setDuration(600).start()
        bgFrontIsA = !bgFrontIsA
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
        // Tombol Karaoke: buka panel karaoke animasi (memakai lyricLines yang sudah dimuat)
        val btnKaraoke = textView("Karaoke", 12f, TEXT2, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(SURFACE2, dp(14).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            pressedFeedback()
        }
        btnKaraoke.setOnClickListener { openKaraoke() }
        val karaokeLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        }
        topRow.addView(btnKaraoke, karaokeLp)
        col.addView(topRow, matchWrap())

        tvLyricsStatus = textView("", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(tvLyricsStatus, matchWrap())

        lyricsScroll = ScrollView(this@PlayerActivity).apply {
            isFillViewport = true
            // Tandai scroll manual: auto-scroll jeda 3 dtk agar tidak ketarik paksa.
            setOnTouchListener { _, ev ->
                val a = ev.actionMasked
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                    userScrollUntil = SystemClock.uptimeMillis() + 3000
                    scrollAnim?.cancel()
                }
                false
            }
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
        loadLyricsFor(track)
    }

    /**
     * Muat lirik untuk [track] ke panel. Dipakai saat panel dibuka dan saat
     * lagu berganti ketika panel sedang terlihat (agar tidak stuck di lirik
     * lagu lama).
     */
    private fun loadLyricsFor(track: Track) {
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
        stopLyricsSync()
        lyricAnimators.forEach { it.cancel() }
        lyricAnimators.clear()

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
                    val tvLine = textView(line.text, 20f, TEXT2).apply {
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

    /**
     * Panel karaoke full-screen: 3 baris lirik (sebelum/aktif/berikut),
     * baris aktif dengan sweep karaoke animasi via Choreographer.
     * Background gelap 90% (placeholder — efek blur ditangani terpisah).
     */
    private fun buildKaraokeOverlay() = with(UiKit) {
        karaokeOverlay = FrameLayout(this@PlayerActivity).apply {
            setBackgroundColor(0xE61A1626.toInt()) // BG 90% opak
            visibility = View.GONE
        }
        val col = vcol {
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        // Bar atas: tutup | judul + artis | bagikan
        val topRow = hrow {}
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { closeKaraoke() }
        topRow.addView(btnClose)
        val titleCol = vcol {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        kvTitle = textView("", 16f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        kvArtist = textView("", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(kvTitle, matchWrap())
        titleCol.addView(kvArtist, matchWrap())
        topRow.addView(titleCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnShare = textView("Bagikan", 12f, TEXT2, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(SURFACE2, dp(14).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            pressedFeedback()
        }
        btnShare.setOnClickListener { shareKaraoke() }
        topRow.addView(btnShare)
        col.addView(topRow, matchWrap())

        // Tiga baris lirik terpusat vertikal
        val centerWrap = vcol { gravity = Gravity.CENTER }
        val dimLine = 0xFF6E6880.toInt() // abu redup untuk baris sebelum/berikut
        kvPrev = KaraokeLineView(this@PlayerActivity).apply {
            textSizeSp = 15f
            dimColor = dimLine
            brightColor = dimLine // selalu redup: tanpa sweep
        }
        kvActive = KaraokeLineView(this@PlayerActivity).apply {
            textSizeSp = 22f
            bold = true
        }
        kvNext = KaraokeLineView(this@PlayerActivity).apply {
            textSizeSp = 15f
            dimColor = dimLine
            brightColor = dimLine // selalu redup: tanpa sweep
        }
        centerWrap.addView(kvPrev, matchWrap())
        centerWrap.addView(kvActive, matchWrap().apply {
            topMargin = dp(18); bottomMargin = dp(18)
        })
        centerWrap.addView(kvNext, matchWrap())
        tvKaraokeStatus = textView("", 14f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(16), dp(32), dp(16))
            visibility = View.GONE
        }
        centerWrap.addView(tvKaraokeStatus, matchWrap())
        col.addView(centerWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        karaokeOverlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Buka panel karaoke memakai lyricLines yang sudah dimuat overlay lirik. */
    private fun openKaraoke() {
        val track = currentTrack ?: return
        closeLyrics() // hentikan sync lirik biasa; karaoke punya loop sendiri
        kvTitle.text = track.title
        kvArtist.text = track.channel
        karaokeIndex = -1
        karaokeOverlay.visibility = View.VISIBLE
        if (lyricLines.isEmpty()) {
            tvKaraokeStatus.text = "Lirik tidak ditemukan untuk lagu ini."
            tvKaraokeStatus.visibility = View.VISIBLE
            kvPrev.visibility = View.GONE
            kvActive.visibility = View.GONE
            kvNext.visibility = View.GONE
            return
        }
        tvKaraokeStatus.visibility = View.GONE
        kvPrev.visibility = View.VISIBLE
        kvActive.visibility = View.VISIBLE
        kvNext.visibility = View.VISIBLE
        updateKaraokeFrame() // gambar frame pertama segera
        startKaraokeLoop()
    }

    private fun closeKaraoke() {
        karaokeOverlay.visibility = View.GONE
        stopKaraokeLoop()
    }

    /**
     * Bagikan panel karaoke sebagai video MP4 pendek (fallback: gambar PNG).
     * Render 10 detik mulai dari posisi lagu saat tombol ditekan.
     */
    private fun shareKaraoke() {
        if (lyricLines.isEmpty()) {
            Toast.makeText(this, "Lirik tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        val track = currentTrack ?: return
        Toast.makeText(this, "Membuat video…", Toast.LENGTH_SHORT).show()
        val lines = lyricLines.toList()
        val posMs = PlayerManager.uiState.value.positionMs
        ImageLoader.get(track.thumbnailUrl) { cover ->
            if (isFinishing || isDestroyed) return@get
            KaraokeVideoRenderer.render(
                context = this,
                lines = lines,
                title = track.title,
                artist = track.channel,
                cover = cover,
                startMs = posMs
            ) { file ->
                if (isFinishing || isDestroyed) return@render
                if (file == null) {
                    Toast.makeText(this, "Gagal membuat video", Toast.LENGTH_SHORT).show()
                    return@render
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", file
                    )
                    val mime = if (file.extension.equals("png", ignoreCase = true))
                        "image/png" else "video/mp4"
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(send, "Bagikan ke…"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal membagikan", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startKaraokeLoop() {
        stopKaraokeLoop()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (::karaokeOverlay.isInitialized &&
                    karaokeOverlay.visibility == View.VISIBLE &&
                    lyricLines.isNotEmpty()
                ) {
                    updateKaraokeFrame()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        karaokeFrameCb = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopKaraokeLoop() {
        karaokeFrameCb?.let { Choreographer.getInstance().removeFrameCallback(it) }
        karaokeFrameCb = null
    }

    /**
     * Satu frame karaoke: cari baris aktif dari positionMs
     * (baris dengan timeMs <= pos < timeMs baris berikut), lalu hitung
     * progress sweep = (pos - start) / (nextStart - start).
     */
    private fun updateKaraokeFrame() {
        if (lyricLines.isEmpty()) return
        val st = PlayerManager.uiState.value
        val pos = smoothPositionMs()
        var idx = lyricLines.indexOfLast { it.timeMs <= pos }
        if (idx < 0) idx = 0
        val cur = lyricLines[idx]
        val nextStart = lyricLines.getOrNull(idx + 1)?.timeMs
        val end = nextStart
            ?: st.durationMs.takeIf { it > cur.timeMs }
            ?: (cur.timeMs + 4000L)
        val span = (end - cur.timeMs).coerceAtLeast(1L)
        val p = ((pos - cur.timeMs).toFloat() / span).coerceIn(0f, 1f)
        if (idx != karaokeIndex) {
            karaokeIndex = idx
            kvPrev.text = lyricLines.getOrNull(idx - 1)?.text.orEmpty()
            kvActive.text = cur.text
            kvNext.text = lyricLines.getOrNull(idx + 1)?.text.orEmpty()
        }
        kvActive.setProgress(p)
        kvPrev.setProgress(1f) // baris lalu: sudah selesai dinyanyikan
        kvNext.setProgress(0f) // baris depan: belum mulai
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

    /**
     * Catat sampel posisi dari uiState (yang datang 500ms sekali). Jangkar
     * interpolasi hanya diganti saat posisi melompat jauh (seek/jeda/ganti
     * lagu/buffering) — selama putar normal, aliran frame memakai jangkar lama
     * sehingga posisi bergerak mulus tanpa patah di tiap sampel baru.
     */
    private fun samplePosition(st: PlayerManager.UiState) {
        val drifted = kotlin.math.abs(st.positionMs - smoothPositionMs()) > 350
        if (drifted || st.isPlaying != posSamplePlaying || st.buffering) {
            posSampleMs = st.positionMs
            posSampleAtMs = SystemClock.elapsedRealtime()
            posSamplePlaying = st.isPlaying && !st.buffering
        }
    }

    /** Posisi putar terinterpolasi (halus, resolusi frame). */
    private fun smoothPositionMs(): Long {
        val st = PlayerManager.uiState.value
        if (!posSamplePlaying || st.buffering) return st.positionMs
        val est = posSampleMs + (SystemClock.elapsedRealtime() - posSampleAtMs)
        val dur = st.durationMs
        return if (dur > 0) est.coerceIn(0L, dur) else est.coerceAtLeast(0L)
    }

    /** Sinkronisasi lirik mengikuti frame (Choreographer) selama panel terbuka. */
    private fun startLyricsSync() {
        stopLyricsSync()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lyricsOverlay.visibility == View.VISIBLE && lyricLines.isNotEmpty()) {
                    updateLyricHighlight(false)
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    lyricsFrameCb = null
                }
            }
        }
        lyricsFrameCb = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopLyricsSync() {
        lyricsFrameCb?.let { Choreographer.getInstance().removeFrameCallback(it) }
        lyricsFrameCb = null
        scrollAnim?.cancel()
        scrollAnim = null
    }

    /**
     * Redup + kecilkan baris lirik bertahap mengikuti jaraknya dari baris aktif
     * (sentuhan khas Apple Music). Transformasi view — tanpa layout ulang.
     */
    private fun applyLyricDimming(active: Int) {
        lyricViews.forEachIndexed { i, tv ->
            val d = kotlin.math.abs(i - active)
            tv.pivotX = 0f
            tv.pivotY = tv.height / 2f
            if (d == 0) {
                tv.alpha = 1f
                tv.scaleX = 1f
                tv.scaleY = 1f
            } else {
                tv.alpha = (1f - 0.11f * d).coerceAtLeast(0.35f)
                val s = (1f - 0.015f * d).coerceAtLeast(0.92f)
                tv.scaleX = s
                tv.scaleY = s
            }
        }
    }

    // ================= Bab Mix =================

    /**
     * Muat daftar bab untuk [track]. Hanya video panjang (>= 20 mnt) yang dicek —
     * lagu biasa tidak punya tracklist di deskripsi.
     */
    private fun loadChaptersFor(track: Track) {
        mixChapters = emptyList()
        chaptersVideoId = track.videoId
        chapterIndex = -1
        if (::tvChapter.isInitialized) tvChapter.visibility = View.GONE
        chaptersJob?.cancel()
        if (track.durationSec < 20 * 60) return
        chaptersJob = uiScope.launch {
            val ch = try { vm.mixChapters(track.videoId) } catch (_: Exception) { emptyList() }
            if (chaptersVideoId != track.videoId || ch.isEmpty()) return@launch
            mixChapters = ch
            chapterIndex = -1
            updateChapterLabel(PlayerManager.uiState.value.positionMs)
        }
    }

    /** Perbarui label bab sesuai posisi putar (♪ judul bab • n/total). */
    private fun updateChapterLabel(posMs: Long) {
        if (mixChapters.isEmpty() || !::tvChapter.isInitialized) return
        val idx = mixChapters.indexOfLast { it.startMs <= posMs }
        if (idx == chapterIndex) return
        chapterIndex = idx
        if (idx < 0) {
            tvChapter.visibility = View.GONE
            return
        }
        tvChapter.text = "♪ ${mixChapters[idx].title}  •  ${idx + 1}/${mixChapters.size}"
        tvChapter.visibility = View.VISIBLE
    }

    /** Dialog daftar semua lagu di dalam mix; ketuk untuk melompat ke lagunya. */
    private fun showChaptersDialog() = with(UiKit) {
        if (mixChapters.isEmpty()) return@with
        val col = vcol { setPadding(dp(20), dp(20), dp(20), dp(8)) }
        col.addView(textView("Lagu di dalam mix ini", 17f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        col.addView(textView("${mixChapters.size} lagu • ketuk untuk melompat",
            13f, TEXT2).apply { setPadding(0, 0, 0, dp(8)) }, matchWrap())

        val list = vcol {}
        val scroll = android.widget.ScrollView(this@PlayerActivity).apply {
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.5).toInt()))

        val dlg = AlertDialog.Builder(this@PlayerActivity).setView(col).create()
        mixChapters.forEachIndexed { i, ch ->
            val active = i == chapterIndex
            val row = hrow {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                pressedFeedback()
            }
            row.addView(textView(formatDurationMs(ch.startMs), 13f,
                if (active) ACCENT2 else TEXT2).apply {
                setPadding(0, 0, dp(12), 0)
            }, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(textView(ch.title, 14f,
                if (active) ACCENT2 else TEXT, bold = active).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                PlayerManager.seekTo(ch.startMs)
                dlg.dismiss()
            }
            list.addView(row, matchWrap())
        }
        dlg.show()
        dlg.styleAsCard()
    }

    /**
     * Transisi highlight lirik ala Apple Music: baris aktif membesar (19→22sp)
     * dan mencerah dengan animasi fade ±350ms; baris lama mengecil kembali.
     */
    /**
     * Transisi baris aktif TANPA mengubah ukuran teks (v3.7.1): animasi textSize
     * lama mengubah tinggi baris di tengah scroll — posisi baris lain bergeser,
     * target scroll basi, hasilnya patah-patah dan layar "mental". Sekarang yang
     * berubah hanya warna + ketebalan huruf; penekanan visual aktif/nonaktif
     * ditangani applyLyricDimming lewat skala transformasi (tanpa layout ulang).
     */
    private fun animateLyricLine(tv: TextView, activate: Boolean) {
        val fromColor = if (activate) TEXT2 else TEXT
        val toColor = if (activate) TEXT else TEXT2
        tv.setTypeface(tv.typeface, if (activate) Typeface.BOLD else Typeface.NORMAL)
        val colorAnim = ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = 350
            addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
        }
        lyricAnimators.add(colorAnim)
        colorAnim.start()
    }

    /** Sorot baris aktif sesuai posisi putar; scroll agar di tengah. */
    private fun updateLyricHighlight(fromTap: Boolean) {
        val pos = if (fromTap) {
            // setelah tap, pakai posisi target (sudah seek)
            lyricLines.getOrNull(lyricIndex)?.timeMs ?: PlayerManager.uiState.value.positionMs
        } else {
            smoothPositionMs()
        }
        var idx = lyricLines.indexOfLast { it.timeMs <= pos }
        if (idx < 0) idx = 0
        if (idx != lyricIndex) {
            // Batalkan animasi yang masih berjalan, lalu transisikan dengan halus.
            lyricAnimators.forEach { it.cancel() }
            lyricAnimators.clear()
            // redupkan yang lama
            if (lyricIndex in lyricViews.indices) {
                animateLyricLine(lyricViews[lyricIndex], activate = false)
            }
            lyricIndex = idx
            val v = lyricViews.getOrNull(idx) ?: return
            animateLyricLine(v, activate = true)
            applyLyricDimming(idx)
        }
        chaseLyricCenter(fromTap)
    }

    /**
     * Pengejaran scroll per-frame (v3.7.1): setiap frame, posisi scroll digeser
     * sebagian menuju target "baris aktif tepat di tengah layar". Berbeda dari
     * glide satu-tembakan 650ms yang targetnya cepat basi — pengejaran ini
     * mengikuti terus, jadi layar mengalir halus mengikuti lirik tanpa mental.
     * Faktor lebih besar setelah ketukan (lompatan jauh) agar cepat sampai.
     */
    private fun chaseLyricCenter(fast: Boolean) {
        if (!::lyricsScroll.isInitialized) return
        if (SystemClock.uptimeMillis() < userScrollUntil) return
        val v = lyricViews.getOrNull(lyricIndex) ?: return
        if (v.height == 0 || lyricsScroll.height == 0) return
        val target = (v.top - lyricsScroll.height / 2 + v.height / 2).coerceAtLeast(0)
        val cur = lyricsScroll.scrollY
        val diff = target - cur
        if (diff == 0) return
        val factor = if (fast) 0.35f else 0.16f
        var step = (diff * factor).toInt()
        if (step == 0) step = if (diff > 0) 1 else -1
        lyricsScroll.scrollTo(0, cur + step)
    }

    override fun onDestroy() {
        stopLyricsSync()
        stopKaraokeLoop()
        lyricsJob?.cancel()
        chaptersJob?.cancel()
        favJob?.cancel()
        // Bersihkan background blur: batalkan job tertunda, recycle bitmap.
        bgToken++
        bgBlurJob?.cancel()
        if (::bgA.isInitialized) {
            bgA.animate().cancel()
            bgB.animate().cancel()
            bgA.setImageDrawable(null)
            bgB.setImageDrawable(null)
        }
        if (blurBmpA != null && !blurBmpA!!.isRecycled) blurBmpA!!.recycle()
        if (blurBmpB != null && !blurBmpB!!.isRecycled) blurBmpB!!.recycle()
        blurBmpA = null
        blurBmpB = null
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
        queueScroll = scroll
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
            // Pegangan seret: hanya lagu SETELAH yang sedang diputar (urutan putar berikutnya)
            if (index > PlayerManager.uiState.value.queueIndex) {
                val handle = iconAction(ZeniIcons.Kind.DRAG, TEXT2, 36, 9)
                attachQueueDrag(handle, row, index)
                row.addView(handle, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
        }
        row
    }

    /**
     * Seret baris antrean lewat pegangannya: baris melayang mengikuti jari,
     * saat dilepas urutan putar diperbarui lewat PlayerManager.moveInQueue.
     */
    /**
     * Seret baris antrean memakai BAYANGAN MELAYANG (snapshot bitmap baris yang
     * digambar di ImageView terpisah pada overlay). Baris asli hanya disembunyikan
     * (INVISIBLE) dan TIDAK pernah ditransformasi/di-elevasi — teknik lama
     * (translationY + bringToFront + elevation pada baris asli) menyebabkan crash
     * NPE di ViewGroup.dispatchDraw pada v3.7.0.
     */
    private fun attachQueueDrag(handle: View, row: View, index: Int) {
        var downRawY = 0f
        var floatView: ImageView? = null
        var floatStartY = 0f
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    if (::queueScroll.isInitialized) {
                        queueScroll.requestDisallowInterceptTouchEvent(true)
                    }
                    try {
                        if (row.width > 0 && row.height > 0 && ::queueOverlay.isInitialized) {
                            val bmp = android.graphics.Bitmap.createBitmap(
                                row.width, row.height,
                                android.graphics.Bitmap.Config.ARGB_8888)
                            row.draw(android.graphics.Canvas(bmp))
                            val rowLoc = IntArray(2); row.getLocationInWindow(rowLoc)
                            val ovLoc = IntArray(2); queueOverlay.getLocationInWindow(ovLoc)
                            val fv = ImageView(this@PlayerActivity).apply {
                                setImageBitmap(bmp)
                                alpha = 0.95f
                                elevation = 12f
                            }
                            queueOverlay.addView(fv, FrameLayout.LayoutParams(row.width, row.height))
                            fv.x = (rowLoc[0] - ovLoc[0]).toFloat()
                            fv.y = (rowLoc[1] - ovLoc[1]).toFloat()
                            floatStartY = fv.y
                            floatView = fv
                            row.visibility = View.INVISIBLE
                        }
                    } catch (e: Exception) { /* gagal snapshot: seret dibatalkan aman */ }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatView?.let { it.y = floatStartY + (ev.rawY - downRawY) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val fv = floatView
                    floatView = null
                    val st = PlayerManager.uiState.value
                    val h = row.height.coerceAtLeast(1)
                    val dy = if (fv != null) fv.y - floatStartY else 0f
                    val steps = Math.round(dy / h.toFloat())
                    val target = (index + steps).coerceIn(st.queueIndex + 1, st.queue.size - 1)
                    if (fv != null) (fv.parent as? ViewGroup)?.removeView(fv)
                    row.visibility = View.VISIBLE
                    if (::queueScroll.isInitialized) {
                        queueScroll.requestDisallowInterceptTouchEvent(false)
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP && target != index) {
                        PlayerManager.moveInQueue(index, target)
                    }
                    refreshQueue()
                    true
                }
                else -> false
            }
        }
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
        if (::karaokeOverlay.isInitialized && karaokeOverlay.visibility == View.VISIBLE) {
            closeKaraoke()
        } else if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
            closeLyrics()
        } else if (::queueOverlay.isInitialized && queueOverlay.visibility == View.VISIBLE) {
            closeQueue()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        stopKaraokeLoop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::karaokeOverlay.isInitialized &&
            karaokeOverlay.visibility == View.VISIBLE &&
            lyricLines.isNotEmpty()
        ) {
            startKaraokeLoop()
        }
    }
}

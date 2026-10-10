package com.zenimusic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.zenimusic.app.data.db.PlaylistEntity
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.repo.PODCAST_EN
import com.zenimusic.app.data.repo.PODCAST_ID
import com.zenimusic.app.data.repo.PodcastChannel
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.ui.AppViewModel
import com.zenimusic.app.ui.ImageLoader
import com.zenimusic.app.ui.TrackAdapter
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.UiKit.ACCENT1
import com.zenimusic.app.ui.UiKit.ACCENT2
import com.zenimusic.app.ui.UiKit.BG
import com.zenimusic.app.ui.UiKit.ERROR
import com.zenimusic.app.ui.UiKit.SURFACE
import com.zenimusic.app.ui.UiKit.SURFACE2
import com.zenimusic.app.ui.UiKit.TEXT
import com.zenimusic.app.ui.UiKit.TEXT2
import com.zenimusic.app.ui.UiKit.WHITE
import com.zenimusic.app.ui.UiKit.accentPillButton
import com.zenimusic.app.ui.UiKit.button
import com.zenimusic.app.ui.UiKit.cardBg
import com.zenimusic.app.ui.UiKit.dp
import com.zenimusic.app.ui.UiKit.hrow
import com.zenimusic.app.ui.UiKit.iconAction
import com.zenimusic.app.ui.UiKit.matchWrap
import com.zenimusic.app.ui.UiKit.pressedFeedback
import com.zenimusic.app.ui.UiKit.roundedBg
import com.zenimusic.app.ui.UiKit.roundedCorners
import com.zenimusic.app.ui.UiKit.setZeniIcon
import com.zenimusic.app.ui.UiKit.styleAsCard
import com.zenimusic.app.ui.UiKit.styleSegment
import com.zenimusic.app.ui.UiKit.styleTab
import com.zenimusic.app.ui.UiKit.textView
import com.zenimusic.app.ui.UiKit.trackListView
import com.zenimusic.app.ui.UiKit.vcol
import com.zenimusic.app.ui.ZeniIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v2.3.0: navigasi atas segmented [Zeni | Home | Playlist] + line-icon modern.
 * Activity biasa + View klasik programmatic. Logika bisnis tidak berubah.
 */
class MainActivity : Activity() {

    private lateinit var uiScope: CoroutineScope
    private lateinit var vm: AppViewModel

    // ---- Segmented nav ----
    private lateinit var segmentButtons: List<Button>
    private lateinit var zeniPane: ScrollView
    private lateinit var searchPane: LinearLayout
    private lateinit var libraryPane: LinearLayout
    private lateinit var podcastPane: LinearLayout

    // ---- Podcast pane ----
    private lateinit var podcastHome: ScrollView
    private lateinit var podcastDetail: LinearLayout
    private lateinit var ivPodcastAvatar: ImageView
    private lateinit var tvPodcastChannel: TextView
    private lateinit var podcastLoading: LinearLayout
    private lateinit var podcastErrorBox: LinearLayout
    private lateinit var tvPodcastError: TextView
    private lateinit var lvPodcast: ListView
    private lateinit var podcastAdapter: TrackAdapter
    private lateinit var podcastEmpty: TextView
    private var podcastJob: Job? = null
    private var podcastOpenChannel: PodcastChannel? = null
    private var podcastHomeLoaded = false
    private val podcastAvatarViews = mutableMapOf<String, ImageView>()
    private val podcastCache = mutableMapOf<String, Triple<String, String, List<Track>>>()

    // ---- Search pane ----
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSearchError: TextView
    private lateinit var searchAdapter: TrackAdapter

    // ---- Library pane ----
    private var libTab = 0 // 0=playlist 1=favorit 2=riwayat 3=unduhan
    private var openPlaylistId: Long? = null
    private var openPlaylistName: String? = null
    private var playlists: List<PlaylistEntity> = emptyList()
    private var favTracks: List<Track> = emptyList()
    private var histTracks: List<Track> = emptyList()
    private var dlTracks: List<Track> = emptyList()
    private var playlistSongs: List<Track> = emptyList()
    private var playlistSongsJob: Job? = null
    private lateinit var lvLibrary: ListView
    private lateinit var tvLibEmpty: TextView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var libTrackAdapter: TrackAdapter
    private lateinit var subTabButtons: List<Button>
    private lateinit var btnNewPlaylist: Button
    private lateinit var detailRow: LinearLayout
    private lateinit var tvDetailTitle: TextView

    // ---- Mini player (slim minimalis, line-icon) ----
    private lateinit var miniPlayer: LinearLayout
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniChannel: TextView
    private lateinit var btnMiniPrev: ImageView
    private lateinit var btnMiniPlay: ImageView
    private lateinit var btnMiniNext: ImageView

    companion object {
        /** Nomor WhatsApp resmi ZeniMusic untuk info update (diberikan Khalid 2026-10-10). */
        const val WHATSAPP_NUMBER = "62895634936155"
        const val WHATSAPP_TEXT = "Halo%2C%20saya%20pengguna%20ZeniMusic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        val app = application as? ZeniMusicApp
        if (app == null || app.initError != null) {
            showInitError(app?.initError)
            return
        }

        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm = AppViewModel(app)

        buildUi()
        collectFlows()
        ensureNotifPermission()
    }

    // ================= UI =================

    private fun buildUi() = with(UiKit) {
        val root = vcol {
            setBackgroundColor(BG)
        }

        // Segmented nav: Zeni | Home | Playlist | Podcast
        val segWrap = vcol { setPadding(dp(16), dp(14), dp(16), dp(8)) }
        val segBar = hrow {
            background = roundedBg(SURFACE2, dp(20).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segNames = listOf("Zeni", "Home", "Playlist", "Podcast")
        segmentButtons = segNames.mapIndexed { i, n ->
            button(n, 13f).also { styleSegment(it, i == 1) }
        }
        segmentButtons.forEachIndexed { i, b ->
            segBar.add(b, 0, dp(40), 1f)
            b.setOnClickListener { selectSegment(i) }
        }
        segWrap.addView(segBar, matchWrap())
        root.addView(segWrap, matchWrap())

        // Konten
        val content = FrameLayout(this@MainActivity)
        zeniPane = buildZeniPane()
        searchPane = buildSearchPane()
        libraryPane = buildLibraryPane()
        podcastPane = buildPodcastPane()
        zeniPane.visibility = View.GONE
        libraryPane.visibility = View.GONE
        podcastPane.visibility = View.GONE
        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(zeniPane, flp)
        content.addView(searchPane, flp)
        content.addView(libraryPane, flp)
        content.addView(podcastPane, flp)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Mini player — slim bar minimalis mengambang (line-icon)
        val miniWrap = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        miniPlayer = hrow {
            background = roundedBg(SURFACE, dp(18).toFloat())
            setPadding(dp(16), dp(8), dp(8), dp(8))
            visibility = View.GONE
            pressedFeedback()
        }
        val miniCol = vcol {
            gravity = Gravity.CENTER_VERTICAL
        }
        tvMiniTitle = textView("", 14f, TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        tvMiniChannel = textView("", 11f, TEXT2).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        miniCol.addView(tvMiniTitle, matchWrap())
        miniCol.addView(tvMiniChannel, matchWrap())
        miniPlayer.add(miniCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnMiniPrev = iconAction(ZeniIcons.Kind.PREV, TEXT2, 40, 8)
        btnMiniPrev.setOnClickListener { PlayerManager.previous() }
        btnMiniPlay = iconAction(ZeniIcons.Kind.PLAY, TEXT, 40, 8)
        btnMiniPlay.setOnClickListener { PlayerManager.togglePlayPause() }
        btnMiniNext = iconAction(ZeniIcons.Kind.NEXT, TEXT2, 40, 8)
        btnMiniNext.setOnClickListener { PlayerManager.next() }
        miniPlayer.addView(btnMiniPrev)
        miniPlayer.addView(btnMiniPlay)
        miniPlayer.addView(btnMiniNext)
        miniPlayer.setOnClickListener {
            startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
        }
        miniWrap.addView(miniPlayer, matchWrap())
        root.addView(miniWrap, matchWrap())

        setContentView(root)
    }

    // ================= Halaman Zeni =================

    private fun buildZeniPane(): ScrollView = with(UiKit) {
        val col = vcol {
            setPadding(dp(20), dp(24), dp(20), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logoId = logoResId()
        val iv = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (logoId != 0) setImageResource(logoId) else setBackgroundColor(SURFACE2)
            roundedCorners(18)
        }
        col.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        col.addView(textView("ZeniMusic", 22f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }, matchWrap())
        col.addView(textView("Versi ${appVersionName()}", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(20))
        }, matchWrap())

        // Kartu perkenalan
        val intro = vcol {
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        intro.addView(textView("Musik YouTube favoritmu, tanpa iklan.", 14f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        val features = listOf(
            ZeniIcons.Kind.SEARCH to "Cari jutaan lagu dari YouTube",
            ZeniIcons.Kind.DOWNLOAD to "Download & dengarkan offline",
            ZeniIcons.Kind.PLAY to "Tetap bunyi di background"
        )
        for ((kind, label) in features) {
            val row = hrow { gravity = Gravity.CENTER_VERTICAL }
            row.addView(iconAction(kind, ACCENT1, 32, 7))
            val tv = textView(label, 13f, TEXT2).apply { setPadding(dp(10), dp(6), 0, dp(6)) }
            row.add(tv, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            intro.addView(row, matchWrap())
        }
        col.addView(intro, matchWrap().apply { bottomMargin = dp(12) })

        // Menu WhatsApp
        val waRow = hrow {
            background = cardBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
            pressedFeedback()
        }
        waRow.addView(iconAction(ZeniIcons.Kind.CHAT, ACCENT1, 40, 8))
        val waCol = vcol { setPadding(dp(12), 0, dp(8), 0) }
        waCol.addView(textView("WhatsApp", 15f, TEXT, bold = true), matchWrap())
        waCol.addView(textView("Chat untuk update & info terbaru", 12f, TEXT2), matchWrap())
        waRow.add(waCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        waRow.addView(iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, TEXT2, 28, 6))
        waRow.setOnClickListener { openWhatsApp() }
        col.addView(waRow, matchWrap())

        val scroll = ScrollView(this@MainActivity)
        scroll.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun openWhatsApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                "https://wa.me/$WHATSAPP_NUMBER?text=$WHATSAPP_TEXT")))
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak bisa membuka WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= Halaman Home (search) =================

    private fun buildSearchPane(): LinearLayout = with(UiKit) {
        val pane = vcol {}

        // Search bar dalam card rounded
        val searchWrap = vcol { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val searchCard = hrow {
            background = cardBg()
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        etSearch = EditText(this@MainActivity).apply {
            hint = "Cari lagu / artis..."
            setHintTextColor(TEXT2)
            setTextColor(TEXT)
            background = ColorDrawable(0x00000000)
            setPadding(0, dp(6), 0, dp(6))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            textSize = 15f
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        searchCard.add(etSearch, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val gap = View(this@MainActivity)
        searchCard.add(gap, dp(8), dp(1))
        val btnSearch = accentPillButton("Cari").apply {
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        btnSearch.setOnClickListener { doSearch() }
        searchCard.addView(btnSearch)
        searchWrap.addView(searchCard, matchWrap())
        pane.addView(searchWrap, matchWrap())

        progressBar = ProgressBar(this@MainActivity).apply { visibility = View.GONE }
        val pbWrap = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            addView(progressBar)
            visibility = View.GONE
        }
        pane.addView(pbWrap, matchWrap())
        progressBar.tag = pbWrap

        tvSearchError = textView("", 13f, ERROR).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            visibility = View.GONE
        }
        pane.addView(tvSearchError, matchWrap())

        val lv = trackListView()
        searchAdapter = TrackAdapter(
            ctx = this@MainActivity,
            onPlay = { t -> vm.play(t, searchAdapter.items) },
            onFav = { t -> vm.toggleFavorite(t) },
            onDownloadHandler = { t -> vm.download(t) },
            onAddToPlaylist = { t -> showAddToPlaylistDialog(t) }
        )
        lv.adapter = searchAdapter
        val tvEmpty = emptyView("Cari lagu atau artis favoritmu di atas")
        pane.addView(tvEmpty, matchWrap())
        lv.emptyView = tvEmpty
        pane.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    // ================= Halaman Playlist (koleksi) =================

    private fun buildLibraryPane(): LinearLayout = with(UiKit) {
        val pane = vcol {}

        val subTabRow = hrow { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val names = listOf("Playlist", "Favorit", "Riwayat", "Unduhan")
        subTabButtons = names.mapIndexed { i, n ->
            button(n, 12f).also { styleTab(it, i == 0) }
        }
        subTabButtons.forEachIndexed { i, b ->
            if (i > 0) subTabRow.addView(View(this@MainActivity).apply { visibility = View.INVISIBLE },
                LinearLayout.LayoutParams(dp(4), dp(1)))
            subTabRow.add(b, 0, dp(40), 1f)
            b.setOnClickListener { selectLibTab(i) }
        }
        pane.addView(subTabRow, matchWrap())

        val actionRow = hrow { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        btnNewPlaylist = accentPillButton("Playlist baru", 13f).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            val d = ZeniIcons.drawable(this@MainActivity, ZeniIcons.Kind.PLUS, WHITE)
            d.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        btnNewPlaylist.setOnClickListener { showCreatePlaylistDialog() }
        actionRow.addView(btnNewPlaylist)

        detailRow = hrow { visibility = View.GONE }
        val btnBack = button("Kembali", 13f).apply {
            val d = ZeniIcons.drawable(this@MainActivity, ZeniIcons.Kind.CHEVRON_LEFT, TEXT2)
            d.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(4)
        }
        btnBack.setOnClickListener { closePlaylistDetail() }
        tvDetailTitle = textView("", 15f, TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        val btnDelete = iconAction(ZeniIcons.Kind.TRASH, ERROR, 40, 9)
        btnDelete.setOnClickListener { confirmDeletePlaylist() }
        detailRow.addView(btnBack)
        detailRow.add(tvDetailTitle, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        detailRow.addView(btnDelete)
        actionRow.addView(detailRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pane.addView(actionRow, matchWrap())

        lvLibrary = trackListView()
        playlistAdapter = PlaylistAdapter()
        libTrackAdapter = TrackAdapter(
            ctx = this@MainActivity,
            onPlay = { t -> vm.play(t, libTrackAdapter.items) },
            onDownloadHandler = { t -> vm.download(t) }
        )
        lvLibrary.adapter = playlistAdapter
        tvLibEmpty = emptyView("")
        pane.addView(tvLibEmpty, matchWrap())
        lvLibrary.emptyView = tvLibEmpty
        pane.addView(lvLibrary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    // ================= Halaman Podcast =================

    private fun buildPodcastPane(): LinearLayout = with(UiKit) {
        val pane = vcol {}

        // ---- Home: daftar channel ----
        podcastHome = ScrollView(this@MainActivity)
        val homeCol = vcol { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        homeCol.addView(podcastSection("Indonesia", PODCAST_ID), matchWrap())
        val gap = View(this@MainActivity)
        homeCol.addView(gap, matchWrap().apply { topMargin = dp(4); bottomMargin = dp(4) })
        homeCol.addView(podcastSection("English", PODCAST_EN), matchWrap())
        podcastHome.addView(homeCol, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- Detail: daftar episode channel ----
        podcastDetail = vcol { visibility = View.GONE }

        val header = hrow {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
        }
        val btnBack = iconAction(ZeniIcons.Kind.CHEVRON_LEFT, TEXT2, 40, 9)
        btnBack.setOnClickListener { closePodcastDetail() }
        header.addView(btnBack)
        ivPodcastAvatar = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE2)
            roundedCorners(20)
        }
        header.addView(ivPodcastAvatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            leftMargin = dp(4); rightMargin = dp(12)
        })
        tvPodcastChannel = textView("", 16f, TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        header.add(tvPodcastChannel, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        podcastDetail.addView(header, matchWrap())

        podcastLoading = vcol {
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
            visibility = View.GONE
        }
        podcastLoading.addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(
            dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        podcastLoading.addView(textView("Memuat episode...", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }, matchWrap())
        podcastDetail.addView(podcastLoading, matchWrap())

        podcastErrorBox = vcol {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
            visibility = View.GONE
        }
        tvPodcastError = textView("", 13f, ERROR).apply { gravity = Gravity.CENTER }
        podcastErrorBox.addView(tvPodcastError, matchWrap())
        val btnRetry = accentPillButton("Coba lagi").apply {
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        btnRetry.setOnClickListener { podcastOpenChannel?.let { openPodcastChannel(it) } }
        podcastErrorBox.addView(btnRetry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
        })
        podcastDetail.addView(podcastErrorBox, matchWrap())

        lvPodcast = trackListView()
        podcastAdapter = TrackAdapter(
            ctx = this@MainActivity,
            onPlay = { t -> vm.play(t, podcastAdapter.items) },
            onFav = { t -> vm.toggleFavorite(t) },
            onDownloadHandler = { t -> vm.download(t) },
            onAddToPlaylist = { t -> showAddToPlaylistDialog(t) }
        )
        lvPodcast.adapter = podcastAdapter
        podcastEmpty = emptyView("Belum ada episode")
        podcastDetail.addView(podcastEmpty, matchWrap())
        // Note: emptyView dikelola manual via renderPodcastContent (tidak pakai
        // lvPodcast.emptyView) agar tidak bentrok dengan state loading/error.
        podcastDetail.addView(lvPodcast, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pane.addView(podcastHome, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        pane.addView(podcastDetail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        return pane
    }

    private fun podcastSection(title: String, channels: List<PodcastChannel>): LinearLayout =
        with(UiKit) {
            val col = vcol {}
            col.addView(textView(title, 14f, TEXT, bold = true).apply {
                setPadding(dp(4), dp(4), dp(4), dp(10))
            }, matchWrap())
            val hsv = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val row = hrow {}
            channels.forEachIndexed { i, ch ->
                if (i > 0) row.addView(View(this@MainActivity),
                    LinearLayout.LayoutParams(dp(10), dp(1)))
                row.addView(podcastChannelCard(ch))
            }
            // Padding kanan agar kartu terakhir tidak mepet
            row.addView(View(this@MainActivity),
                LinearLayout.LayoutParams(dp(4), dp(1)))
            hsv.addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(hsv, matchWrap())
            return col
        }

    private fun podcastChannelCard(ch: PodcastChannel): LinearLayout = with(UiKit) {
        val card = vcol {
            background = cardBg()
            setPadding(dp(12), dp(14), dp(12), dp(14))
            gravity = Gravity.CENTER_HORIZONTAL
            pressedFeedback()
        }
        // Frame: inisial di belakang, avatar menutupinya saat termuat
        val frame = FrameLayout(this@MainActivity)
        val tvInitial = textView(
            ch.name.firstOrNull()?.uppercase() ?: "?", 24f, TEXT2, bold = true
        ).apply { gravity = Gravity.CENTER }
        frame.addView(tvInitial, FrameLayout.LayoutParams(dp(64), dp(64)).apply {
            gravity = Gravity.CENTER
        })
        val iv = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x00000000)
            roundedCorners(32)
        }
        frame.addView(iv, FrameLayout.LayoutParams(dp(64), dp(64)).apply {
            gravity = Gravity.CENTER
        })
        podcastAvatarViews[ch.url] = iv
        // Jika sudah ter-cache (mis. kembali dari detail), langsung tampilkan
        podcastCache[ch.url]?.let { feed ->
            val avatar = feed.second
            if (avatar.isNotEmpty()) ImageLoader.load(avatar, iv)
        }
        card.addView(frame, LinearLayout.LayoutParams(dp(96), dp(64)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(textView(ch.name, 12f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.setOnClickListener { openPodcastChannel(ch) }
        return card
    }

    /** Fetch avatar semua channel sekali per sesi agar kartu tampil bergambar. */
    private fun ensurePodcastHome() {
        if (podcastHomeLoaded) return
        podcastHomeLoaded = true
        uiScope.launch(Dispatchers.IO) {
            val all = PODCAST_ID + PODCAST_EN
            // Fetch paralel
            val deferred = all.map { ch ->
                async {
                    try {
                        Pair(ch.url, vm.channelFeed(ch))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            deferred.forEach { d ->
                val res = d.await() ?: return@forEach
                val url = res.first
                val feed = res.second
                podcastCache[url] = feed
                val avatar = feed.second
                if (avatar.isNotEmpty()) {
                    val iv = podcastAvatarViews[url]
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        iv?.let { ImageLoader.load(avatar, it) }
                    }
                }
            }
        }
    }

    private fun openPodcastChannel(ch: PodcastChannel) {
        val url = ch.url
        podcastOpenChannel = ch
        podcastJob?.cancel()
        // Tampilkan cached dulu bila ada (langsung), lalu refresh di background? —
        // sesuai spek: fetch fresh saat pertama dibuka per sesi; cache dipakai
        // agar bolak-balik tab tidak fetch ulang.
        val cached = podcastCache[url]
        showPodcastDetail(cached?.first ?: "", cached?.second ?: "")
        if (cached != null) {
            podcastAdapter.submit(cached.third)
            renderPodcastContent(loading = false, error = null)
            return
        }
        renderPodcastContent(loading = true, error = null)
        podcastJob = uiScope.launch {
            try {
                val (name, avatar, tracks) = vm.channelFeed(ch)
                podcastCache[url] = Triple(name, avatar, tracks)
                if (podcastOpenChannel?.url != url) return@launch
                showPodcastDetail(name, avatar)
                podcastAdapter.submit(tracks)
                renderPodcastContent(loading = false, error = null)
                // Update avatar kartu home juga
                if (avatar.isNotEmpty()) {
                    podcastAvatarViews[url]?.let { ImageLoader.load(avatar, it) }
                }
            } catch (e: Exception) {
                if (podcastOpenChannel?.url != url) return@launch
                renderPodcastContent(
                    loading = false,
                    error = e.message ?: "Gagal memuat episode. Coba lagi."
                )
            }
        }
    }

    private fun showPodcastDetail(name: String, avatarUrl: String) {
        podcastHome.visibility = View.GONE
        podcastDetail.visibility = View.VISIBLE
        tvPodcastChannel.text = name.ifEmpty { "Podcast" }
        if (avatarUrl.isNotEmpty()) ImageLoader.load(avatarUrl, ivPodcastAvatar)
        else {
            ivPodcastAvatar.setImageBitmap(null)
            ivPodcastAvatar.setBackgroundColor(SURFACE2)
        }
    }

    private fun renderPodcastContent(loading: Boolean, error: String?) {
        podcastLoading.visibility = if (loading) View.VISIBLE else View.GONE
        podcastErrorBox.visibility =
            if (!loading && error != null) View.VISIBLE else View.GONE
        val showList = !loading && error == null
        lvPodcast.visibility = if (showList) View.VISIBLE else View.GONE
        // Empty state dikelola manual (sinkron dengan items adapter).
        podcastEmpty.visibility =
            if (showList && podcastAdapter.items.isEmpty()) View.VISIBLE else View.GONE
        if (error != null) tvPodcastError.text = error
    }

    private fun closePodcastDetail() {
        podcastJob?.cancel()
        podcastOpenChannel = null
        podcastDetail.visibility = View.GONE
        podcastHome.visibility = View.VISIBLE
    }

    // ================= Segment logic =================

    private fun selectSegment(idx: Int) = with(UiKit) {
        segmentButtons.forEachIndexed { i, b -> styleSegment(b, i == idx) }
        zeniPane.visibility = if (idx == 0) View.VISIBLE else View.GONE
        searchPane.visibility = if (idx == 1) View.VISIBLE else View.GONE
        libraryPane.visibility = if (idx == 2) View.VISIBLE else View.GONE
        podcastPane.visibility = if (idx == 3) View.VISIBLE else View.GONE
        if (idx == 3) ensurePodcastHome()
    }

    private fun selectLibTab(idx: Int) = with(UiKit) {
        libTab = idx
        openPlaylistId = null
        openPlaylistName = null
        playlistSongsJob?.cancel()
        subTabButtons.forEachIndexed { i, b -> styleTab(b, i == idx) }
        renderLibrary()
    }

    private fun renderLibrary() = with(UiKit) {
        val inDetail = openPlaylistId != null
        btnNewPlaylist.visibility = if (!inDetail && libTab == 0) View.VISIBLE else View.GONE
        detailRow.visibility = if (inDetail) View.VISIBLE else View.GONE
        tvLibEmpty.text = when {
            inDetail -> "Playlist ini masih kosong"
            libTab == 0 -> "Belum ada playlist \u2014 buat satu yuk!"
            libTab == 1 -> "Belum ada lagu favorit"
            libTab == 2 -> "Belum ada riwayat putar"
            else -> "Belum ada unduhan"
        }
        if (inDetail) {
            tvDetailTitle.text = openPlaylistName ?: ""
            libTrackAdapter.onRemoveHandler = { t ->
                openPlaylistId?.let { pid -> vm.removeFromPlaylist(pid, t) }
            }
            libTrackAdapter.onDownloadHandler = null
            libTrackAdapter.submit(playlistSongs)
            if (lvLibrary.adapter !== libTrackAdapter) lvLibrary.adapter = libTrackAdapter
        } else if (libTab == 0) {
            if (lvLibrary.adapter !== playlistAdapter) lvLibrary.adapter = playlistAdapter
            playlistAdapter.submit(playlists)
        } else {
            val list = when (libTab) {
                1 -> favTracks
                2 -> histTracks
                else -> dlTracks
            }
            libTrackAdapter.onRemoveHandler = null
            libTrackAdapter.onDownloadHandler = { t -> vm.download(t) }
            libTrackAdapter.submit(list)
            if (lvLibrary.adapter !== libTrackAdapter) lvLibrary.adapter = libTrackAdapter
        }
    }

    private fun openPlaylistDetail(p: PlaylistEntity) {
        openPlaylistId = p.id
        openPlaylistName = p.name
        renderLibrary()
        playlistSongsJob?.cancel()
        playlistSongsJob = uiScope.launch {
            vm.playlistSongsFlow(p.id).collect { songs ->
                playlistSongs = songs
                if (openPlaylistId == p.id) renderLibrary()
            }
        }
    }

    private fun closePlaylistDetail() {
        openPlaylistId = null
        openPlaylistName = null
        playlistSongsJob?.cancel()
        renderLibrary()
    }

    // ================= Dialogs =================

    private fun showCreatePlaylistDialog() = with(UiKit) {
        val input = EditText(this@MainActivity).apply {
            hint = "Nama playlist"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(TEXT); setHintTextColor(TEXT2)
            background = roundedBg(SURFACE2, dp(12).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val wrap = vcol { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        wrap.addView(input, matchWrap())
        val dlg = AlertDialog.Builder(this@MainActivity)
            .setTitle("Playlist baru")
            .setView(wrap)
            .setPositiveButton("Buat") { _, _ -> vm.createPlaylist(input.text.toString()) }
            .setNegativeButton("Batal", null)
            .show()
        dlg.styleAsCard()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ACCENT2)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(TEXT2)
    }

    private fun showAddToPlaylistDialog(track: Track) {
        if (playlists.isEmpty()) {
            Toast.makeText(this, "Buat playlist dulu", Toast.LENGTH_SHORT).show()
            showCreatePlaylistDialog()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        val dlg = AlertDialog.Builder(this@MainActivity)
            .setTitle("Tambah ke playlist")
            .setItems(names) { _, which ->
                vm.addToPlaylist(playlists[which].id, track)
            }
            .setNeutralButton("Buat baru") { _, _ -> showCreatePlaylistDialog() }
            .setNegativeButton("Batal", null)
            .show()
        with(UiKit) {
            dlg.styleAsCard()
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ACCENT2)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(TEXT2)
        }
    }

    private fun confirmDeletePlaylist() {
        val pid = openPlaylistId ?: return
        val name = openPlaylistName ?: ""
        val dlg = AlertDialog.Builder(this@MainActivity)
            .setTitle("Hapus playlist?")
            .setMessage("\"$name\" akan dihapus.")
            .setPositiveButton("Hapus") { _, _ ->
                vm.deletePlaylist(pid)
                closePlaylistDetail()
            }
            .setNegativeButton("Batal", null)
            .show()
        with(UiKit) {
            dlg.styleAsCard()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ERROR)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(TEXT2)
        }
    }

    // ================= Flows =================

    private fun collectFlows() {
        uiScope.launch { vm.searchResults.collect { searchAdapter.submit(it) } }
        uiScope.launch {
            vm.searching.collect { searching ->
                (progressBar.tag as? View)?.visibility = if (searching) View.VISIBLE else View.GONE
            }
        }
        uiScope.launch {
            vm.searchError.collect { err ->
                if (err == null) tvSearchError.visibility = View.GONE
                else { tvSearchError.text = err; tvSearchError.visibility = View.VISIBLE }
            }
        }
        uiScope.launch {
            vm.playerState.collect { st ->
                updateMiniPlayer(st)
                st.error?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                    PlayerManager.clearError()
                }
            }
        }
        uiScope.launch { vm.playlists.collect { playlists = it; renderLibrary() } }
        uiScope.launch {
            vm.favorites.collect {
                favTracks = it
                val ids = it.map { t -> t.videoId }.toSet()
                searchAdapter.favIds = ids
                libTrackAdapter.favIds = ids
                if (::podcastAdapter.isInitialized) podcastAdapter.favIds = ids
                searchAdapter.notifyDataSetChanged()
                if (::podcastAdapter.isInitialized) podcastAdapter.notifyDataSetChanged()
                renderLibrary()
            }
        }
        uiScope.launch { vm.history.collect { histTracks = it; renderLibrary() } }
        uiScope.launch { vm.downloaded.collect { dlTracks = it; renderLibrary() } }
        uiScope.launch {
            vm.downloadingIds.collect { ids ->
                searchAdapter.downloadingIds = ids
                libTrackAdapter.downloadingIds = ids
                searchAdapter.notifyDataSetChanged()
                libTrackAdapter.notifyDataSetChanged()
                if (::podcastAdapter.isInitialized) {
                    podcastAdapter.downloadingIds = ids
                    podcastAdapter.notifyDataSetChanged()
                }
            }
        }
        uiScope.launch {
            vm.toast.collect { msg ->
                msg?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                    vm.consumeToast()
                }
            }
        }
    }

    private fun updateMiniPlayer(st: PlayerManager.UiState) {
        val cur = st.current
        (miniPlayer.parent as? View)?.visibility = if (cur == null) View.GONE else View.VISIBLE
        miniPlayer.visibility = if (cur == null) View.GONE else View.VISIBLE
        if (cur == null) return
        tvMiniTitle.text = cur.title
        tvMiniChannel.text = if (st.loading) "Memuat..." else cur.channel
        with(UiKit) {
            btnMiniPlay.setZeniIcon(
                if (st.isPlaying) ZeniIcons.Kind.PAUSE else ZeniIcons.Kind.PLAY, TEXT)
        }
    }

    // ================= Misc =================

    private fun doSearch() {
        hideKeyboard()
        vm.search(etSearch.text.toString())
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // no-op: notifikasi tetap opsional.
    }

    private fun showInitError(t: Throwable?) = with(UiKit) {
        val col = vcol { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        col.addView(textView("ZeniMusic gagal start", 20f, ERROR, bold = true))
        val sw = StringWriter()
        t?.printStackTrace(PrintWriter(sw))
        col.addView(textView("${t?.javaClass?.name}: ${t?.message}\n$sw", 11f, TEXT2))
        val scroll = ScrollView(this@MainActivity)
        scroll.addView(col)
        val btn = accentPillButton("Tutup").apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        btn.setOnClickListener { finish() }
        val root = vcol { setBackgroundColor(BG) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn, matchWrap())
        setContentView(root)
    }

    override fun onDestroy() {
        playlistSongsJob?.cancel()
        podcastJob?.cancel()
        if (::uiScope.isInitialized) uiScope.cancel()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        super.onDestroy()
    }

    // ---- Adapter daftar playlist (card) ----
    private inner class PlaylistAdapter : BaseAdapter() {
        private var items: List<PlaylistEntity> = emptyList()
        fun submit(list: List<PlaylistEntity>) { items = list; notifyDataSetChanged() }
        override fun getCount(): Int = items.size
        override fun getItem(p: Int): PlaylistEntity = items[p]
        override fun getItemId(p: Int): Long = items[p].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = with(UiKit) {
            val p = items[position]
            val root: LinearLayout
            val tv: TextView
            if (convertView is LinearLayout && convertView.tag is TextView) {
                root = convertView
                tv = root.tag as TextView
            } else {
                root = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                }
                tv = textView("", 16f, TEXT).apply {
                    background = cardBg()
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                    pressedFeedback()
                }
                root.addView(tv, matchWrap())
                root.tag = tv
            }
            tv.text = p.name
            tv.setOnClickListener { openPlaylistDetail(p) }
            root
        }
    }
}

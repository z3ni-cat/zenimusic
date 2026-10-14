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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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
import com.zenimusic.app.data.repo.Daylist
import com.zenimusic.app.data.repo.DaylistSlot
import com.zenimusic.app.data.repo.PODCAST_EN
import com.zenimusic.app.data.repo.PODCAST_ID
import com.zenimusic.app.data.repo.PodcastChannel
import com.zenimusic.app.data.repo.TelegramSender
import com.zenimusic.app.data.repo.UpdateChecker
import com.zenimusic.app.data.repo.WrappedStats
import android.graphics.drawable.GradientDrawable
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.player.PlaybackLog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private var currentSegment = 1
    private lateinit var zeniPane: ScrollView
    private lateinit var homePane: LinearLayout
    private lateinit var cariPane: LinearLayout
    private lateinit var libraryPane: LinearLayout
    private lateinit var podcastPane: LinearLayout
    // ---- Tab Log diagnosis ----
    private lateinit var logPane: LinearLayout
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private var logRefresh: Runnable? = null
    private val logHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
    private lateinit var lvSuggestions: ListView
    private lateinit var suggestionAdapter: android.widget.ArrayAdapter<String>
    private var suggestionJob: kotlinx.coroutines.Job? = null
    // True saat perubahan teks berasal dari tap saran (bukan ketikan) —
    // supaya saran tidak di-fetch ulang setelah tap.
    private var suppressSuggestions = false
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSearchError: TextView
    private lateinit var searchAdapter: TrackAdapter

    // ---- Quick Picks ----
    private lateinit var qpRow: LinearLayout
    private var qpJob: Job? = null

    // ---- Discovery (Home) ----
    private lateinit var discoveryWrap: LinearLayout

    // ---- Daylist ----
    private lateinit var daylistCard: LinearLayout
    private lateinit var tvDaylistTitle: TextView
    private lateinit var tvDaylistSub: TextView
    private var daylistJob: Job? = null
    private var daylistCacheSlot: DaylistSlot? = null
    private var daylistCacheTracks: List<Track> = emptyList()

    // ---- Zeni Wrapped ----
    private var wrappedOverlay: FrameLayout? = null
    private var wrappedContent: LinearLayout? = null
    private var wrappedYear = true
    private var wrappedJob: Job? = null
    private var wrappedStats: WrappedStats? = null

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
    private lateinit var miniSpinner: android.widget.ProgressBar

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
        // Cek update versi diam-diam (sekali per sesi buka aplikasi)
        checkForUpdate(auto = true)
    }

    // ================= UI =================

    private fun buildUi() = with(UiKit) {
        val root = vcol {
            setBackgroundColor(BG)
        }

        // Segmented nav: Zeni | Home | Cari | Podcast | Playlist | Log
        val segWrap = vcol { setPadding(dp(16), dp(14), dp(16), dp(8)) }
        val segBar = hrow {
            background = roundedBg(SURFACE2, dp(20).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segNames = listOf("Zeni", "Home", "Cari", "Podcast", "Playlist", "Log")
        segmentButtons = segNames.mapIndexed { i, n ->
            button(n, 12f).also { styleSegment(it, i == 1) }
        }
        segmentButtons.forEachIndexed { i, b ->
            segBar.add(b, 0, dp(40), 1f)
            b.setOnClickListener { selectSegment(i) }
        }
        segWrap.addView(segBar, matchWrap())
        root.addView(segWrap, matchWrap())

        // Konten — bisa digeser kiri/kanan untuk pindah tab
        val content = FrameLayout(this@MainActivity)
        zeniPane = buildZeniPane()
        homePane = buildHomePane()
        cariPane = buildCariPane()
        libraryPane = buildLibraryPane()
        podcastPane = buildPodcastPane()
        logPane = buildLogPane()
        zeniPane.visibility = View.GONE
        cariPane.visibility = View.GONE
        libraryPane.visibility = View.GONE
        podcastPane.visibility = View.GONE
        logPane.visibility = View.GONE
        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(zeniPane, flp)
        content.addView(homePane, flp)
        content.addView(cariPane, flp)
        content.addView(podcastPane, flp)
        content.addView(libraryPane, flp)
        content.addView(logPane, flp)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Geser horizontal pada area konten → pindah tab
        content.setOnTouchListener(this@MainActivity.TabSwipeListener { direction -> selectSegment(currentSegment + direction) })

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
        btnMiniPlay.setOnClickListener {
            with(UiKit) { btnMiniPlay.pop() }
            PlayerManager.togglePlayPause()
        }
        // Spinner buffering (tampil saat loading/buffering, gantikan tombol play)
        miniSpinner = android.widget.ProgressBar(this@MainActivity).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT2)
        }
        btnMiniNext = iconAction(ZeniIcons.Kind.NEXT, TEXT2, 40, 8)
        btnMiniNext.setOnClickListener { PlayerManager.next() }
        miniPlayer.addView(btnMiniPrev)
        miniPlayer.addView(btnMiniPlay)
        miniPlayer.addView(miniSpinner, LinearLayout.LayoutParams(dp(40), dp(40)))
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

        // Pengaturan: Equalizer + Crossfade
        val setCard = vcol {
            background = cardBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        setCard.addView(textView("Pengaturan Audio", 15f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(10))
        }, matchWrap())

        // Baris Equalizer
        val eqRow = hrow {
            gravity = Gravity.CENTER_VERTICAL
            pressedFeedback()
        }
        eqRow.addView(iconAction(ZeniIcons.Kind.EQ, ACCENT1, 40, 8))
        val eqCol = vcol { setPadding(dp(12), 0, dp(8), 0) }
        eqCol.addView(textView("Equalizer", 14f, TEXT, bold = true), matchWrap())
        eqCol.addView(textView("Sesuaikan bass, vokal, treble — gratis", 12f, TEXT2), matchWrap())
        eqRow.add(eqCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        eqRow.addView(iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, TEXT2, 28, 6))
        eqRow.setOnClickListener {
            startActivity(Intent(this@MainActivity, EqualizerActivity::class.java))
        }
        setCard.addView(eqRow, matchWrap())

        // Baris Crossfade
        val cfRow = hrow {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        cfRow.addView(iconAction(ZeniIcons.Kind.SHUFFLE, ACCENT1, 40, 8))
        val cfCol = vcol { setPadding(dp(12), 0, dp(8), 0) }
        cfCol.addView(textView("Crossfade", 14f, TEXT, bold = true), matchWrap())
        cfCol.addView(textView("Transisi halus antar lagu", 12f, TEXT2), matchWrap())
        cfRow.add(cfCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val cfSpinner = Spinner(this@MainActivity).apply {
            val opts = listOf("Mati", "3 dtk", "5 dtk", "8 dtk", "12 dtk")
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, opts)
            val cur = PlayerManager.crossfadeSec
            setSelection(when (cur) {
                3 -> 1; 5 -> 2; 8 -> 3; 12 -> 4; else -> 0
            })
        }
        var cfInit = true
        cfSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (cfInit) { cfInit = false; return }
                PlayerManager.crossfadeSec = when (pos) {
                    1 -> 3; 2 -> 5; 3 -> 8; 4 -> 12; else -> 0
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        cfRow.addView(cfSpinner,
            LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        setCard.addView(cfRow, matchWrap())
        col.addView(setCard, matchWrap().apply { bottomMargin = dp(12) })

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
        col.addView(waRow, matchWrap().apply { bottomMargin = dp(12) })

        // Menu Request Fitur → kirim ke bot Telegram
        val rqRow = hrow {
            background = cardBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
            pressedFeedback()
        }
        rqRow.addView(iconAction(ZeniIcons.Kind.CHAT, ACCENT1, 40, 8))
        val rqCol = vcol { setPadding(dp(12), 0, dp(8), 0) }
        rqCol.addView(textView("Request Fitur", 15f, TEXT, bold = true), matchWrap())
        rqCol.addView(textView("Punya ide? Kirim langsung ke developer", 12f, TEXT2), matchWrap())
        rqRow.add(rqCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rqRow.addView(iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, TEXT2, 28, 6))
        rqRow.setOnClickListener { showRequestDialog() }
        col.addView(rqRow, matchWrap().apply { bottomMargin = dp(12) })

        // Menu Periksa Update (cek manual)
        val upRow = hrow {
            background = cardBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
            pressedFeedback()
        }
        upRow.addView(iconAction(ZeniIcons.Kind.DOWNLOAD, ACCENT1, 40, 8))
        val upCol = vcol { setPadding(dp(12), 0, dp(8), 0) }
        upCol.addView(textView("Periksa Update", 15f, TEXT, bold = true), matchWrap())
        upCol.addView(textView("Cek versi terbaru ZeniMusic", 12f, TEXT2), matchWrap())
        upRow.add(upCol, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        upRow.addView(iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, TEXT2, 28, 6))
        upRow.setOnClickListener { checkForUpdate(auto = false) }
        col.addView(upRow, matchWrap())

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

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak bisa membuka tautan", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= Update versi (GitHub Releases) =================

    private fun todayId(): Long = System.currentTimeMillis() / 86_400_000L

    private fun updateDismissedToday(version: String): Boolean {
        val p = getSharedPreferences("zeni_prefs", MODE_PRIVATE)
        return p.getString("update_dismissed_ver", "") == version &&
            p.getLong("update_dismissed_day", -1) == todayId()
    }

    private fun markUpdateDismissed(version: String) {
        getSharedPreferences("zeni_prefs", MODE_PRIVATE).edit()
            .putString("update_dismissed_ver", version)
            .putLong("update_dismissed_day", todayId())
            .apply()
    }

    /**
     * Cek update ke GitHub Releases.
     * auto=true: diam saja bila tidak ada update / gagal / sudah pilih "Nanti" hari ini.
     */
    private fun checkForUpdate(auto: Boolean) {
        uiScope.launch {
            val info = try { UpdateChecker.checkLatest() } catch (_: Exception) { null }
            if (info == null) {
                if (!auto) Toast.makeText(this@MainActivity,
                    "Gagal memeriksa update. Periksa koneksi.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!UpdateChecker.isNewer(info.versionName, appVersionName())) {
                if (!auto) Toast.makeText(this@MainActivity,
                    "Kamu sudah pakai versi terbaru.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (auto && updateDismissedToday(info.versionName)) return@launch
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) = with(UiKit) {
        val col = vcol { setPadding(dp(20), dp(20), dp(20), dp(4)) }
        col.addView(textView("Ada update versi ${info.versionName}", 17f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        col.addView(textView("Yang baru di versi ini:", 13f, TEXT2).apply {
            setPadding(0, 0, 0, dp(8))
        }, matchWrap())
        val tvLog = textView(
            info.changelog.ifBlank { "Perbaikan dan peningkatan." }, 13f, TEXT2)
        val sv = ScrollView(this@MainActivity)
        sv.addView(tvLog, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        val btnRow = hrow {
            gravity = Gravity.END
            setPadding(0, dp(16), 0, dp(12))
        }
        val btnLater = button("Nanti", 14f)
        val btnUpdate = accentPillButton("Update", 14f).apply {
            setPadding(dp(28), dp(10), dp(28), dp(10))
        }
        btnRow.addView(btnLater)
        btnRow.addView(View(this@MainActivity), dp(12), dp(1))
        btnRow.addView(btnUpdate)
        col.addView(btnRow, matchWrap())
        val dlg = AlertDialog.Builder(this@MainActivity).setView(col).create()
        btnLater.setOnClickListener {
            markUpdateDismissed(info.versionName)
            dlg.dismiss()
        }
        btnUpdate.setOnClickListener {
            dlg.dismiss()
            val apkUrl = info.apkUrl
            if (apkUrl.isNullOrEmpty()) openUrl(info.pageUrl)
            else showDownloadDialog(apkUrl, info.pageUrl)
        }
        dlg.show()
        dlg.styleAsCard()
    }

    private fun showDownloadDialog(apkUrl: String, fallbackUrl: String) = with(UiKit) {
        val col = vcol { setPadding(dp(20), dp(20), dp(20), dp(12)) }
        col.addView(textView("Mengunduh update…", 15f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        val pb = ProgressBar(this@MainActivity, null,
            android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(pb, matchWrap())
        val tvPct = textView("0%", 13f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(tvPct, matchWrap())
        val dlg = AlertDialog.Builder(this@MainActivity).setView(col)
            .setCancelable(false).create()
        dlg.show()
        dlg.styleAsCard()
        uiScope.launch {
            val ok = try {
                UpdateChecker.downloadAndInstall(this@MainActivity, apkUrl) { p ->
                    pb.progress = p
                    tvPct.text = "$p%"
                }
            } catch (_: Exception) { false }
            dlg.dismiss()
            if (!ok) {
                Toast.makeText(this@MainActivity,
                    "Unduhan gagal. Membuka halaman update…", Toast.LENGTH_LONG).show()
                openUrl(fallbackUrl)
            }
        }
    }

    // ================= Request Fitur (Telegram) =================

    private fun showRequestDialog() = with(UiKit) {
        if (!TelegramSender.isConfigured()) {
            Toast.makeText(this@MainActivity,
                "Fitur request belum dikonfigurasi.", Toast.LENGTH_SHORT).show()
            return
        }
        val col = vcol { setPadding(dp(20), dp(20), dp(20), dp(4)) }
        col.addView(textView("Request Fitur", 17f, TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        col.addView(textView("Ceritakan ide atau masalahmu, langsung terkirim ke developer.",
            13f, TEXT2).apply {
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        val etTitle = EditText(this@MainActivity).apply {
            hint = "Judul, mis: Sleep timer per lagu"
            setHintTextColor(TEXT2)
            setTextColor(TEXT)
            background = roundedBg(SURFACE2, dp(10).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        col.addView(etTitle, matchWrap().apply { bottomMargin = dp(10) })
        val cats = listOf("Fitur Baru", "Laporan Bug", "Lainnya")
        val spCat = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, cats)
        }
        col.addView(spCat, matchWrap().apply { bottomMargin = dp(10) })
        val etDesc = EditText(this@MainActivity).apply {
            hint = "Deskripsi…"
            setHintTextColor(TEXT2)
            setTextColor(TEXT)
            background = roundedBg(SURFACE2, dp(10).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            textSize = 14f
        }
        col.addView(etDesc, matchWrap())
        val btnRow = hrow {
            gravity = Gravity.END
            setPadding(0, dp(16), 0, dp(12))
        }
        val btnCancel = button("Batal", 14f)
        val btnSend = accentPillButton("Kirim", 14f).apply {
            setPadding(dp(28), dp(10), dp(28), dp(10))
        }
        btnRow.addView(btnCancel)
        btnRow.addView(View(this@MainActivity), dp(12), dp(1))
        btnRow.addView(btnSend)
        col.addView(btnRow, matchWrap())
        val dlg = AlertDialog.Builder(this@MainActivity).setView(col).create()
        btnCancel.setOnClickListener { dlg.dismiss() }
        btnSend.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val desc = etDesc.text.toString().trim()
            if (title.isEmpty() || desc.isEmpty()) {
                Toast.makeText(this@MainActivity,
                    "Isi judul dan deskripsi dulu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cat = cats[spCat.selectedItemPosition.coerceIn(cats.indices)]
            btnSend.isEnabled = false
            uiScope.launch {
                val ok = try {
                    TelegramSender.sendRequest(title, cat, desc, appVersionName())
                } catch (_: Exception) { false }
                dlg.dismiss()
                Toast.makeText(this@MainActivity,
                    if (ok) "Terkirim! Makasih masukannya."
                    else "Gagal terkirim. Coba lagi nanti.",
                    Toast.LENGTH_LONG).show()
            }
        }
        dlg.show()
        dlg.styleAsCard()
    }

    // ================= Quick Picks =================

    private fun loadQuickPicks() {
        qpJob?.cancel()
        qpJob = uiScope.launch {
            try {
                val tracks = vm.discoveryPicks()
                if (!::qpRow.isInitialized) return@launch
                qpRow.removeAllViews()
                if (tracks.isEmpty()) {
                    qpRow.addView(with(UiKit) {
                        textView("Dengarkan beberapa lagu dulu, nanti muncul rekomendasi di sini.",
                            13f, TEXT2).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
                    })
                    return@launch
                }
                for (t in tracks) qpRow.addView(quickPickCard(t, tracks))
            } catch (_: Exception) { /* abaikan */ }
        }
    }

    private fun quickPickCard(t: Track, queue: List<Track>): LinearLayout = with(UiKit) {
        val card = vcol {
            background = cardBg()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            pressedFeedback()
            isClickable = true
            isFocusable = true
        }
        val iv = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedCorners(10)
        }
        ImageLoader.load(t.thumbnailUrl, iv)
        card.addView(iv, LinearLayout.LayoutParams(dp(104), dp(104)))
        card.addView(textView(t.title, 12f, TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(textView(t.channel, 11f, TEXT2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.setOnClickListener { vm.play(t, queue) }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(10) }
        return card
    }

    // ================= Halaman Home (search) =================

    private fun buildHomePane(): LinearLayout = with(UiKit) {
        val pane = vcol {}

        // Kartu discovery selalu tampil di Home (pencarian kini punya tab "Cari" sendiri,
        // jadi tidak ada lagi sembunyi-tampil otomatis)
        discoveryWrap = vcol {}
        pane.addView(discoveryWrap, matchWrap())

        // ---- Quick Picks: discovery berbasis genre favorit (bukan riwayat) ----
        val qpWrap = vcol { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        qpWrap.addView(textView("Pilihan Buatmu", 15f, TEXT, bold = true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }, matchWrap())
        val qpScroll = HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
        }
        qpRow = hrow {}
        qpScroll.addView(qpRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        qpWrap.addView(qpScroll, matchWrap())
        discoveryWrap.addView(qpWrap, matchWrap())
        loadQuickPicks()

        // ---- Zeni Wrapped: statistik dengerin ala Spotify Wrapped ----
        val wrappedWrap = vcol { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val wrappedCard = hrow {
            background = cardBg()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val wrappedTxt = vcol {}
        wrappedTxt.addView(textView("🎧 Zeni Wrapped", 15f, TEXT, bold = true), matchWrap())
        wrappedTxt.addView(textView("Statistik dengerinmu", 12f, TEXT2), matchWrap())
        wrappedCard.add(wrappedTxt, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val wrappedGo = iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, ACCENT2, 44, 10)
        wrappedCard.addView(wrappedGo)
        wrappedCard.setOnClickListener { openWrapped() }
        wrappedWrap.addView(wrappedCard, matchWrap())
        discoveryWrap.addView(wrappedWrap, matchWrap())

        // ---- Daylist: playlist otomatis sesuai jam ----
        val daylistWrap = vcol { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        daylistCard = hrow {
            background = cardBg()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val daylistTxt = vcol {}
        tvDaylistTitle = textView("📻 Daylist", 15f, TEXT, bold = true)
        tvDaylistSub = textView("", 12f, TEXT2)
        daylistTxt.addView(tvDaylistTitle, matchWrap())
        daylistTxt.addView(tvDaylistSub, matchWrap())
        daylistCard.add(daylistTxt, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val daylistPlay = iconAction(ZeniIcons.Kind.PLAY, ACCENT2, 44, 10)
        daylistCard.addView(daylistPlay)
        daylistCard.setOnClickListener { openDaylist() }
        daylistWrap.addView(daylistCard, matchWrap())
        discoveryWrap.addView(daylistWrap, matchWrap())
        refreshDaylistCard()

        // ---- Samples: swipe discovery ala TikTok ----
        val samplesWrap = vcol { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val samplesCard = hrow {
            background = cardBg()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val samplesTxt = vcol {}
        samplesTxt.addView(textView("📱 Samples", 15f, TEXT, bold = true), matchWrap())
        samplesTxt.addView(textView("Swipe untuk discover lagu baru", 12f, TEXT2), matchWrap())
        samplesCard.add(samplesTxt, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val samplesGo = iconAction(ZeniIcons.Kind.CHEVRON_RIGHT, ACCENT2, 44, 10)
        samplesCard.addView(samplesGo)
        samplesCard.setOnClickListener {
            startActivity(Intent(this@MainActivity, SamplesActivity::class.java))
        }
        samplesWrap.addView(samplesCard, matchWrap())
        discoveryWrap.addView(samplesWrap, matchWrap())

        return pane
    }

    /**
     * Tab "Cari": kolom pencarian + saran live ala YouTube + hasil pencarian.
     * Dipisah dari Home agar tampilan hasil tidak perlu sembunyi-tampil.
     */
    private fun buildCariPane(): LinearLayout = with(UiKit) {
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
        // Saran live ala YouTube: muncul saat mengetik, sebelum enter
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onSearchTyping(s?.toString().orEmpty())
            }
        })
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

        // Dropdown saran (awalnya sembunyi)
        suggestionAdapter = android.widget.ArrayAdapter(
            this@MainActivity, android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvSuggestions = ListView(this@MainActivity).apply {
            adapter = suggestionAdapter
            visibility = View.GONE
            setBackgroundColor(SURFACE)
            divider = ColorDrawable(SURFACE2)
            dividerHeight = dp(1)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        lvSuggestions.setOnItemClickListener { _, _, pos, _ ->
            val s = suggestionAdapter.getItem(pos) ?: return@setOnItemClickListener
            suppressSuggestions = true
            etSearch.setText(s)
            etSearch.setSelection(s.length)
            hideSuggestions()
            doSearch()
        }
        pane.addView(lvSuggestions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        val tvEmpty = emptyView("Ketik di atas untuk mencari lagu")
        pane.addView(tvEmpty, matchWrap())
        lv.emptyView = tvEmpty
        pane.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    /** Dipanggil tiap ada ketikan di kolom cari — tampilkan saran dengan debounce. */
    private fun onSearchTyping(query: String) {
        suggestionJob?.cancel()
        // Perubahan teks dari tap saran: jangan fetch saran lagi.
        if (suppressSuggestions) {
            suppressSuggestions = false
            hideSuggestions()
            return
        }
        if (query.trim().length < 2) {
            hideSuggestions()
            return
        }
        suggestionJob = uiScope.launch {
            delay(350)
            try {
                val list = vm.suggestions(query.trim())
                if (list.isEmpty()) hideSuggestions()
                else showSuggestions(list)
            } catch (_: Exception) {
                hideSuggestions()
            }
        }
    }

    private fun showSuggestions(list: List<String>) {
        suggestionAdapter.clear()
        suggestionAdapter.addAll(list)
        lvSuggestions.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        if (::lvSuggestions.isInitialized) lvSuggestions.visibility = View.GONE
    }

    /** Perbarui label kartu Daylist sesuai slot waktu aktif. */
    private fun refreshDaylistCard() {
        if (!::tvDaylistTitle.isInitialized) return
        val slot = Daylist.currentSlot()
        tvDaylistTitle.text = "📻 Daylist"
        tvDaylistSub.text = "${slot.emoji} ${slot.name} • ${slot.label} WIB"
    }

    /** Tap kartu Daylist → generate (sekali per slot per sesi) → putar. */
    private fun openDaylist() {
        val slot = Daylist.currentSlot()
        // Cache: pakai hasil generate sebelumnya bila slot sama
        if (daylistCacheSlot == slot && daylistCacheTracks.isNotEmpty()) {
            vm.play(daylistCacheTracks.first(), daylistCacheTracks)
            vm.showToast("Daylist ${slot.name} siap!")
            return
        }
        daylistJob?.cancel()
        tvDaylistSub.text = "Membuat Daylist ${slot.name}…"
        daylistCard.isClickable = false
        daylistJob = uiScope.launch {
            try {
                val tracks = vm.daylist(slot)
                if (tracks.isEmpty()) {
                    vm.showToast("Daylist kosong, coba lagi.")
                    refreshDaylistCard()
                    return@launch
                }
                daylistCacheSlot = slot
                daylistCacheTracks = tracks
                vm.play(tracks.first(), tracks)
                vm.showToast("Daylist ${slot.name} siap!")
            } catch (e: Exception) {
                vm.showToast("Gagal membuat Daylist.")
            } finally {
                daylistCard.isClickable = true
                refreshDaylistCard()
            }
        }
    }

    // ================= Zeni Wrapped =================

    private fun openWrapped() {
        if (wrappedOverlay == null) buildWrappedOverlay()
        wrappedOverlay?.visibility = View.VISIBLE
        loadWrapped()
    }

    private fun closeWrapped() {
        wrappedOverlay?.visibility = View.GONE
        wrappedJob?.cancel()
    }

    private fun buildWrappedOverlay() = with(UiKit) {
        val overlay = FrameLayout(this@MainActivity).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
        }
        val col = vcol { setPadding(dp(20), dp(12), dp(20), dp(20)) }
        // Header
        val topRow = hrow { gravity = Gravity.CENTER_VERTICAL }
        val btnClose = iconAction(ZeniIcons.Kind.CLOSE, TEXT2, 40, 10)
        btnClose.setOnClickListener { closeWrapped() }
        topRow.addView(btnClose)
        topRow.addView(textView("🎧 Zeni Wrapped", 18f, TEXT, bold = true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(topRow, matchWrap())
        // Toggle tahun/bulan
        val segRow = hrow { gravity = Gravity.CENTER }
        val btnYear = accentPillButton("Tahun ini", 13f)
        val btnMonth = accentPillButton("Bulan ini", 13f)
        btnYear.setOnClickListener {
            wrappedYear = true; styleSegment(btnYear, true); styleSegment(btnMonth, false); loadWrapped()
        }
        btnMonth.setOnClickListener {
            wrappedYear = false; styleSegment(btnMonth, true); styleSegment(btnYear, false); loadWrapped()
        }
        styleSegment(btnYear, true); styleSegment(btnMonth, false)
        segRow.addView(btnYear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        segRow.addView(btnMonth, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        val segWrap = LinearLayout(this@MainActivity).apply { setPadding(0, dp(12), 0, dp(4)) }
        segWrap.addView(segRow, matchWrap())
        col.addView(segWrap, matchWrap())
        // Konten scroll
        val scroll = ScrollView(this@MainActivity).apply { isFillViewport = true }
        wrappedContent = vcol { setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(wrappedContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Tombol bagikan
        val btnShare = accentPillButton("Bagikan sebagai gambar", 14f)
        btnShare.setOnClickListener { shareWrapped() }
        val shareWrap = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        }
        shareWrap.addView(btnShare, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(shareWrap, matchWrap())

        overlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrappedOverlay = overlay
        addContentView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun loadWrapped() = with(UiKit) {
        val box = wrappedContent ?: return
        box.removeAllViews()
        box.addView(textView("Menghitung…", 14f, TEXT2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0)
        }, matchWrap())
        wrappedJob?.cancel()
        wrappedJob = uiScope.launch {
            try {
                val stats = vm.wrapped(wrappedYear)
                wrappedStats = stats
                renderWrapped(stats)
            } catch (e: Exception) {
                box.removeAllViews()
                box.addView(textView("Gagal memuat statistik.", 14f, TEXT2).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0)
                }, matchWrap())
            }
        }
    }

    /** Kartu gradient colorful untuk statistik. */
    private fun statCard(top: Int, bottom: Int, title: String, big: String, sub: String): LinearLayout =
        with(UiKit) {
            val card = vcol {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            card.addView(textView(title, 13f, 0xFFFFFFFF.toInt()).apply { alpha = 0.85f }, matchWrap())
            card.addView(textView(big, 34f, 0xFFFFFFFF.toInt(), bold = true).apply {
                setPadding(0, dp(8), 0, dp(4))
            }, matchWrap())
            card.addView(textView(sub, 13f, 0xFFFFFFFF.toInt()).apply { alpha = 0.85f }, matchWrap())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            card.layoutParams = lp
            card
        }

    private fun renderWrapped(s: WrappedStats) = with(UiKit) {
        val box = wrappedContent ?: return
        box.removeAllViews()
        if (s.totalPlays == 0) {
            box.addView(textView("Belum ada riwayat putar di periode ini.\nPutar lagu dulu yuk! 🎵", 14f, TEXT2).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0)
            }, matchWrap())
            return
        }
        box.addView(textView(s.label, 13f, TEXT2).apply {
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        // (a) Total menit — ungu
        box.addView(statCard(0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
            "WAKTU DENGERIN", "${s.totalMinutes} menit", "${s.totalPlays} lagu diputar"))
        // (b) Top 1 lagu — teal
        s.topSongs.firstOrNull()?.let { t ->
            box.addView(statCard(0xFF14B8A6.toInt(), 0xFF0EA5E9.toInt(),
                "TOP 1 LAGU", t.title, "${t.subtitle} • ${t.count}x diputar"))
        }
        // (c) Top 1 artis — oranye
        s.topArtists.firstOrNull()?.let { a ->
            box.addView(statCard(0xFFF97316.toInt(), 0xFFEF4444.toInt(),
                "TOP 1 ARTIS", a.title, "${a.count}x diputar"))
        }
        // (d) Top 5 lagu
        if (s.topSongs.size > 1) {
            box.addView(textView("TOP LAGU", 12f, TEXT2, bold = true).apply {
                setPadding(0, dp(8), 0, dp(8))
            }, matchWrap())
            s.topSongs.forEachIndexed { i, t ->
                box.addView(rankRow(i + 1, t.title, "${t.subtitle} • ${t.count}x"), matchWrap())
            }
        }
        // Top 5 artis
        if (s.topArtists.size > 1) {
            box.addView(textView("TOP ARTIS", 12f, TEXT2, bold = true).apply {
                setPadding(0, dp(16), 0, dp(8))
            }, matchWrap())
            s.topArtists.forEachIndexed { i, a ->
                box.addView(rankRow(i + 1, a.title, "${a.count}x diputar"), matchWrap())
            }
        }
    }

    private fun rankRow(rank: Int, title: String, sub: String): LinearLayout = with(UiKit) {
        val row = hrow {
            background = cardBg()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(textView("$rank", 16f, ACCENT2, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        val txt = vcol { setPadding(dp(8), 0, 0, 0) }
        txt.addView(textView(title, 14f, TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())
        txt.addView(textView(sub, 12f, TEXT2).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())
        row.add(txt, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        row
    }

    /** Render kartu Wrapped jadi gambar 1080x1920 lalu bagikan via FileProvider. */
    private fun shareWrapped() {
        val s = wrappedStats ?: run { vm.showToast("Belum ada data untuk dibagikan."); return }
        if (s.totalPlays == 0) { vm.showToast("Belum ada data untuk dibagikan."); return }
        uiScope.launch(Dispatchers.IO) {
            try {
                val bmp = renderWrappedBitmap(s)
                val file = java.io.File(cacheDir, "zeni-wrapped.png")
                java.io.FileOutputStream(file).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "com.zenimusic.app.fileprovider", file)
                withContext(Dispatchers.Main) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(send, "Bagikan Zeni Wrapped"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { vm.showToast("Gagal membagikan: ${e.message}") }
            }
        }
    }

    /** Gambar kartu 1080x1920: gradient bg + statistik + branding. */
    private fun renderWrappedBitmap(s: WrappedStats): android.graphics.Bitmap {
        val w = 1080; val h = 1920
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        // Background gradient ungu → pink
        val bgPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
                0xFF1A1626.toInt(), 0xFF2D1B4E.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        // Dekorasi lingkaran
        val deco = android.graphics.Paint().apply { alpha = 40 }
        deco.color = 0xFF8B5CF6.toInt()
        c.drawCircle(w * 0.85f, h * 0.12f, 220f, deco)
        deco.color = 0xFFEC4899.toInt()
        c.drawCircle(w * 0.12f, h * 0.85f, 260f, deco)

        fun text(y: Float, str: String, size: Float, bold: Boolean, alpha: Int = 255) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = size; textAlign = android.graphics.Paint.Align.CENTER
                this.alpha = alpha
                typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            }
            // Potong teks yang terlalu panjang
            var t = str
            while (p.measureText(t) > w - 160 && t.length > 10) t = t.dropLast(4) + "…"
            c.drawText(t, w / 2f, y, p)
        }

        var y = 320f
        text(y, "🎧 Zeni Wrapped", 72f, true); y += 110f
        text(y, s.label, 44f, false, 200); y += 200f
        text(y, "${s.totalMinutes}", 150f, true); y += 110f
        text(y, "menit dengerin musik", 44f, false, 200); y += 90f
        text(y, "${s.totalPlays} lagu diputar", 40f, false, 180); y += 220f
        s.topSongs.firstOrNull()?.let { t ->
            text(y, "TOP 1 LAGU", 36f, true, 200); y += 80f
            text(y, t.title, 52f, true); y += 80f
            text(y, "${t.subtitle} • ${t.count}x", 40f, false, 180); y += 200f
        }
        s.topArtists.firstOrNull()?.let { a ->
            text(y, "TOP 1 ARTIS", 36f, true, 200); y += 80f
            text(y, a.title, 52f, true); y += 80f
            text(y, "${a.count}x diputar", 40f, false, 180); y += 200f
        }
        text(h - 140f, "Dibuat dengan ZeniMusic", 36f, false, 150)
        return bmp
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
            onPlay = { t -> vm.play(t, libTrackAdapter.items, radioMode = false) },
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
            onPlay = { t -> vm.play(t, podcastAdapter.items, radioMode = false) },
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

    // ================= Tab Log diagnosis =================

    /** Tab Log: tampilkan ring buffer PlaybackLog + tombol Salin/Hapus. */
    private fun buildLogPane(): LinearLayout = with(UiKit) {
        val pane = vcol {
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val topRow = hrow {}
        val tvTitle = textView("Log Diagnosis", 17f, TEXT, bold = true)
        topRow.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnCopy = button("Salin", 12f).apply { pressedFeedback() }
        btnCopy.setOnClickListener { copyLog() }
        val btnClear = button("Hapus", 12f).apply { pressedFeedback() }
        btnClear.setOnClickListener {
            PlaybackLog.clear()
            refreshLogView()
        }
        topRow.addView(btnCopy)
        topRow.addView(btnClear)
        pane.addView(topRow, matchWrap())

        val tvHint = textView(
            "Catatan kejadian pemutar — buka tab ini lalu kirim isinya saat musik berhenti tiba-tiba.",
            12f, TEXT2)
        tvHint.setPadding(0, dp(4), 0, dp(8))
        pane.addView(tvHint, matchWrap())

        logScroll = ScrollView(this@MainActivity).apply { isFillViewport = true }
        tvLog = textView("", 12f, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBg(SURFACE, dp(12).toFloat())
        }
        logScroll.addView(tvLog, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pane.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "ZeniMusic log", PlaybackLog.text()))
            android.widget.Toast.makeText(
                this@MainActivity, "Log disalin — kirim ke aku ya", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    private fun refreshLogView() {
        if (!::tvLog.isInitialized) return
        tvLog.text = PlaybackLog.text().ifEmpty { "Belum ada kejadian." }
        if (::logScroll.isInitialized) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startLogRefresh() {
        stopLogRefresh()
        val r = object : Runnable {
            override fun run() {
                if (::logPane.isInitialized && logPane.visibility == View.VISIBLE) {
                    refreshLogView()
                    logHandler.postDelayed(this, 1000)
                }
            }
        }
        logRefresh = r
        logHandler.post(r)
    }

    private fun stopLogRefresh() {
        logRefresh?.let { logHandler.removeCallbacks(it) }
        logRefresh = null
    }

    // ================= Segment logic =================

    private fun selectSegment(idx: Int) = with(UiKit) {
        val safeIdx = idx.coerceIn(0, 5)
        segmentButtons.forEachIndexed { i, b -> styleSegment(b, i == safeIdx) }
        val panes = listOf(zeniPane, homePane, cariPane, podcastPane, libraryPane, logPane)
        val dir = when {
            safeIdx > currentSegment -> 1
            safeIdx < currentSegment -> -1
            else -> 0
        }
        currentSegment = safeIdx
        panes.forEachIndexed { i, pane ->
            if (i == safeIdx) {
                val wasGone = pane.visibility != View.VISIBLE
                pane.visibility = View.VISIBLE
                // Animasi fade + slide hanya saat benar-benar pindah tab
                if (wasGone) pane.fadeSlideIn(dir)
            } else {
                pane.visibility = View.GONE
            }
        }
        if (safeIdx == 3) ensurePodcastHome()
        if (safeIdx == 1) refreshDaylistCard()
        if (safeIdx == 5) startLogRefresh() else stopLogRefresh()
    }

    /**
     * Geser horizontal pada area konten untuk pindah tab. Diabaikan bila gestur
     * dimulai di dalam scroller horizontal (mis. Quick Picks) agar tidak rebutan.
     */
    private inner class TabSwipeListener(val onSwipe: (Int) -> Unit) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var downView: View? = null
        private val swipeThreshold = with(UiKit) { dp(90) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    downView = findViewAt(v as ViewGroup, e.x, e.y)
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val adx = kotlin.math.abs(dx)
                    val ady = kotlin.math.abs(dy)
                    if (adx > swipeThreshold && adx > ady * 1.5f && !isInHorizontalScroller(downView)) {
                        onSwipe(if (dx < 0) 1 else -1)
                        return true
                    }
                }
            }
            return false
        }

        private fun findViewAt(parent: ViewGroup, x: Float, y: Float): View? {
            for (i in parent.childCount - 1 downTo 0) {
                val c = parent.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                val loc = IntArray(2)
                c.getLocationOnScreen(loc)
                val ploc = IntArray(2)
                parent.getLocationOnScreen(ploc)
                val lx = loc[0] - ploc[0]
                val ly = loc[1] - ploc[1]
                if (x >= lx && x <= lx + c.width && y >= ly && y <= ly + c.height) {
                    return if (c is ViewGroup && c.childCount > 0) findViewAt(c, x - lx, y - ly) ?: c else c
                }
            }
            return null
        }

        private fun isInHorizontalScroller(v: View?): Boolean {
            var cur: View? = v
            while (cur != null) {
                if (cur is HorizontalScrollView) return true
                if (cur is androidx.recyclerview.widget.RecyclerView &&
                    (cur.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.orientation == androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL
                ) return true
                cur = cur.parent as? View
            }
            return false
        }
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
        val wrap = miniPlayer.parent as? View
        val shouldShow = cur != null
        val wasGone = miniPlayer.visibility != View.VISIBLE
        wrap?.visibility = if (shouldShow) View.VISIBLE else View.GONE
        miniPlayer.visibility = if (shouldShow) View.VISIBLE else View.GONE
        // Slide up halus saat mini player muncul
        if (shouldShow && wasGone) with(UiKit) { miniPlayer.slideUpIn() }
        if (cur == null) return
        tvMiniTitle.text = cur.title
        val isBusy = st.loading || st.buffering
        tvMiniChannel.text = when {
            st.loading -> "Memuat..."
            st.buffering -> "Buffering..."
            else -> cur.channel
        }
        // Tampilkan spinner saat loading/buffering, sembunyikan tombol play
        miniSpinner.visibility = if (isBusy) View.VISIBLE else View.GONE
        btnMiniPlay.visibility = if (isBusy) View.INVISIBLE else View.VISIBLE
        with(UiKit) {
            btnMiniPlay.setZeniIcon(
                if (st.isPlaying) ZeniIcons.Kind.PAUSE else ZeniIcons.Kind.PLAY, TEXT)
        }
    }

    // ================= Misc =================

    private fun doSearch() {
        hideKeyboard()
        hideSuggestions()
        suppressSuggestions = false
        val q = etSearch.text.toString()
        vm.search(q)
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
        wrappedJob?.cancel()
        if (::uiScope.isInitialized) uiScope.cancel()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (wrappedOverlay?.visibility == View.VISIBLE) {
            closeWrapped()
        } else {
            super.onBackPressed()
        }
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

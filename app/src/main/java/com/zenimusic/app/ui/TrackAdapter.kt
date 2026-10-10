package com.zenimusic.app.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zenimusic.app.data.model.Track
import com.zenimusic.app.data.model.formatDuration
import com.zenimusic.app.ui.UiKit.ACCENT2
import com.zenimusic.app.ui.UiKit.FAV
import com.zenimusic.app.ui.UiKit.SURFACE2
import com.zenimusic.app.ui.UiKit.TEXT
import com.zenimusic.app.ui.UiKit.TEXT2
import com.zenimusic.app.ui.UiKit.cardBg
import com.zenimusic.app.ui.UiKit.circleIconButton
import com.zenimusic.app.ui.UiKit.dp
import com.zenimusic.app.ui.UiKit.pressedFeedback
import com.zenimusic.app.ui.UiKit.roundedCorners
import com.zenimusic.app.ui.UiKit.setZeniIcon

/**
 * Adapter baris lagu: card surface rounded + thumbnail rounded + tombol aksi lingkaran.
 * Klik baris = putar. Tombol yang lambdanya null disembunyikan.
 */
class TrackAdapter(
    private val ctx: Context,
    private val onPlay: (Track) -> Unit,
    var onFav: ((Track) -> Unit)? = null,
    var onDownloadHandler: ((Track) -> Unit)? = null,
    var onAddToPlaylist: ((Track) -> Unit)? = null,
    var onRemoveHandler: ((Track) -> Unit)? = null
) : BaseAdapter() {

    var items: List<Track> = emptyList()
    var favIds: Set<String> = emptySet()
    var downloadingIds: Set<String> = emptySet()

    fun submit(list: List<Track>) {
        // Tandai untuk animasi stagger hanya jika isi list benar-benar berganti
        stagger = list.firstOrNull()?.videoId != items.firstOrNull()?.videoId || list.size != items.size
        items = list
        notifyDataSetChanged()
    }

    private var stagger = false

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Track = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private class Holder(
        val thumb: ImageView,
        val title: TextView,
        val sub: TextView,
        val btnFav: ImageView,
        val btnDl: ImageView,
        val btnAdd: ImageView,
        val btnRemove: ImageView
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val t = items[position]
        val root: LinearLayout
        val h: Holder
        if (convertView is LinearLayout && convertView.tag is Holder) {
            root = convertView
            h = root.tag as Holder
        } else {
            // Root transparan dengan padding = margin card (8dp h / 4dp v).
            root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
            }
            val card = with(ctx) {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg()
                    setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                    pressedFeedback()
                }
            }
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(SURFACE2)
                roundedCorners(12)
            }
            card.addView(thumb, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), 0, ctx.dp(4), 0)
            }
            val title = TextView(ctx).apply {
                textSize = 15f
                setTextColor(TEXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(ctx).apply {
                textSize = 12f
                setTextColor(TEXT2)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            col.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(sub, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val btnFav = with(ctx) { circleIconButton(ZeniIcons.Kind.HEART, ACCENT2, 36) }
            val btnDl = with(ctx) { circleIconButton(ZeniIcons.Kind.DOWNLOAD, TEXT, 36) }
            val btnAdd = with(ctx) { circleIconButton(ZeniIcons.Kind.PLUS, TEXT, 36) }
            val btnRemove = with(ctx) { circleIconButton(ZeniIcons.Kind.CLOSE, TEXT, 36) }
            card.addView(btnFav)
            card.addView(btnDl)
            card.addView(btnAdd)
            card.addView(btnRemove)
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            h = Holder(thumb, title, sub, btnFav, btnDl, btnAdd, btnRemove)
            root.tag = h
            card.setOnClickListener { onPlay(t) }
        }

        h.title.text = t.title
        h.sub.text = "${t.channel} • ${formatDuration(t.durationSec)}"
        ImageLoader.load(t.thumbnailUrl, h.thumb)

        val favActive = favIds.contains(t.videoId)
        bindAction(h.btnFav, onFav,
            if (favActive) ZeniIcons.Kind.HEART_FILLED else ZeniIcons.Kind.HEART,
            if (favActive) FAV else ACCENT2) { onFav?.invoke(t) }
        val downloading = downloadingIds.contains(t.videoId)
        bindAction(h.btnDl, onDownloadHandler, ZeniIcons.Kind.DOWNLOAD, TEXT,
            alpha = if (downloading) 0.4f else 1f) { onDownloadHandler?.invoke(t) }
        bindAction(h.btnAdd, onAddToPlaylist, ZeniIcons.Kind.PLUS, TEXT) { onAddToPlaylist?.invoke(t) }
        bindAction(h.btnRemove, onRemoveHandler, ZeniIcons.Kind.CLOSE, TEXT) { onRemoveHandler?.invoke(t) }

        // Update click listener karena view di-recycle.
        (root.getChildAt(0) as View).setOnClickListener { onPlay(t) }

        // Animasi stagger: kartu muncul satu per satu (hanya untuk list baru, max 8 pertama)
        if (stagger && position < 8) {
            root.alpha = 0f
            root.translationY = ctx.dp(16).toFloat()
            root.animate().alpha(1f).translationY(0f)
                .setStartDelay(position * 60L)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
                .start()
            if (position == minOf(7, items.size - 1)) stagger = false
        }
        return root
    }

    private fun bindAction(
        btn: ImageView, action: ((Track) -> Unit)?,
        kind: ZeniIcons.Kind, tint: Int,
        alpha: Float = 1f,
        click: () -> Unit
    ) {
        if (action == null) {
            btn.visibility = View.GONE
        } else {
            btn.visibility = View.VISIBLE
            btn.setZeniIcon(kind, tint)
            btn.alpha = alpha
            btn.setOnClickListener { click() }
        }
    }
}

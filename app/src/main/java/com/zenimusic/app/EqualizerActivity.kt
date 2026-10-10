package com.zenimusic.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.zenimusic.app.audio.EqualizerHelper
import com.zenimusic.app.player.PlayerManager
import com.zenimusic.app.ui.UiKit
import com.zenimusic.app.ui.ZeniIcons

/**
 * Equalizer 5-band — GRATIS untuk semua pengguna.
 * Preset + slider manual, pengaturan tersimpan otomatis.
 */
class EqualizerActivity : Activity() {

    private val sliders = mutableListOf<SeekBar>()
    private val valueLabels = mutableListOf<TextView>()
    private lateinit var presetSpinner: Spinner
    private lateinit var toggleBtn: Button
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) = with(UiKit) {
        super.onCreate(savedInstanceState)
        EqualizerHelper.init(this@EqualizerActivity)
        // Pastikan tertempel ke session aktif
        PlayerManager.player?.let { EqualizerHelper.attach(it.audioSessionId) }

        val root = vcol {
            setBackgroundColor(BG)
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        // Header
        val header = hrow { gravity = Gravity.CENTER_VERTICAL }
        val btnBack = iconAction(ZeniIcons.Kind.CHEVRON_LEFT, TEXT, 44, 6)
        btnBack.setOnClickListener { finish() }
        header.addView(btnBack, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tvTitle = textView("Equalizer", 20f, TEXT, bold = true)
        val hlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hlp.leftMargin = dp(8)
        header.addView(tvTitle, hlp)
        val tvFree = textView("GRATIS", 12f, ACCENT, bold = true)
        header.addView(tvFree,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header, matchWrap())

        root.addView(spacer(dp(16)), matchWrap())

        // Toggle
        toggleBtn = button("", 15f)
        toggleBtn.setOnClickListener {
            val on = !EqualizerHelper.isEnabled()
            EqualizerHelper.setEnabled(on)
            // Re-attach agar langsung terasa
            PlayerManager.player?.let { EqualizerHelper.attach(it.audioSessionId) }
            refreshToggle()
            refreshSliders()
        }
        root.addView(toggleBtn, matchWrap())

        root.addView(spacer(dp(16)), matchWrap())

        // Preset
        root.addView(textView("Preset", 14f, TEXT2, bold = true), matchWrap())
        root.addView(spacer(dp(6)), matchWrap())
        presetSpinner = Spinner(this@EqualizerActivity).apply {
            val names = EqualizerHelper.presets().ifEmpty {
                listOf("Normal", "Classical", "Dance", "Flat", "Folk",
                    "Heavy Metal", "Hip Hop", "Jazz", "Pop", "Rock")
            }
            adapter = ArrayAdapter(
                this@EqualizerActivity,
                android.R.layout.simple_spinner_dropdown_item, names
            )
        }
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                if (!EqualizerHelper.isEnabled()) EqualizerHelper.setEnabled(true)
                PlayerManager.player?.let { EqualizerHelper.attach(it.audioSessionId) }
                EqualizerHelper.usePreset(pos)
                refreshToggle()
                refreshSliders()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(presetSpinner, matchWrap())

        root.addView(spacer(dp(20)), matchWrap())
        root.addView(textView("Manual", 14f, TEXT2, bold = true), matchWrap())
        root.addView(spacer(dp(8)), matchWrap())

        // Slider tiap band
        val range = EqualizerHelper.levelRange()
        val span = (range.last - range.first).coerceAtLeast(1)
        val n = EqualizerHelper.numBands().coerceIn(1, 8)
        for (b in 0 until n) {
            val freqHz = EqualizerHelper.centerFreqHz(b)
            val label = EqualizerHelper.freqLabel(freqHz).ifEmpty { "Band ${b + 1}" }

            val row = vcol {
                background = roundedBg(SURFACE, dp(14).toFloat())
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val topRow = hrow { gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(textView(label, 14f, TEXT, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val tvVal = textView("0 dB", 13f, TEXT2)
            valueLabels.add(tvVal)
            topRow.addView(tvVal,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(topRow, matchWrap())

            val sb = SeekBar(this@EqualizerActivity).apply {
                max = span
                progress = EqualizerHelper.getBandLevel(b) - range.first
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (!EqualizerHelper.isEnabled()) EqualizerHelper.setEnabled(true)
                    PlayerManager.player?.let { EqualizerHelper.attach(it.audioSessionId) }
                    val level = p + range.first
                    EqualizerHelper.setBandLevel(b, level)
                    tvVal.text = fmtDb(level)
                    updating = true
                    presetSpinner.setSelection(0) // kembali ke tampilan netral
                    updating = false
                    refreshToggle()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            sliders.add(sb)
            row.addView(sb, matchWrap())
            root.addView(row, matchWrap())
            root.addView(spacer(dp(10)), matchWrap())
        }

        val scroll = ScrollView(this@EqualizerActivity).apply { addView(root) }
        setContentView(scroll)

        refreshToggle()
        refreshSliders()
    }

    private fun refreshToggle() = with(UiKit) {
        val on = EqualizerHelper.isEnabled()
        toggleBtn.text = if (on) "Matikan Equalizer" else "Nyalakan Equalizer"
        // Style manual: tombol aksen saat on, tombol biasa saat off
        toggleBtn.background = if (on) accentGradient(dp(14).toFloat())
            else roundedBg(SURFACE2, dp(14).toFloat())
        toggleBtn.setTextColor(if (on) BG else TEXT)
    }

    /** Spacer vertikal sederhana. */
    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun refreshSliders() {
        if (!::presetSpinner.isInitialized) return
        updating = true
        val range = EqualizerHelper.levelRange()
        sliders.forEachIndexed { b, sb ->
            val lvl = EqualizerHelper.getBandLevel(b)
            sb.progress = (lvl - range.first).coerceIn(0, sb.max)
            valueLabels.getOrNull(b)?.text = fmtDb(lvl)
        }
        val preset = EqualizerHelper.currentPreset()
        val count = presetSpinner.adapter?.count ?: 0
        if (preset in 0 until count) presetSpinner.setSelection(preset)
        updating = false
    }

    private fun fmtDb(millibel: Int): String {
        val db = millibel / 100f
        return if (db == 0f) "0 dB" else "%+.1f dB".format(db)
    }
}

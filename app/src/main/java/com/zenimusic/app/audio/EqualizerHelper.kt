package com.zenimusic.app.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer

/**
 * Equalizer 5-band berbasis android.media.audiofx.Equalizer,
 * ditempel ke audio session ExoPlayer. GRATIS untuk semua pengguna.
 *
 * Pengaturan disimpan di SharedPreferences dan diterapkan ulang
 * setiap kali audio session baru terpasang.
 */
object EqualizerHelper {

    private var eq: Equalizer? = null
    private var sessionId: Int = -1
    private lateinit var prefs: SharedPreferences

    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset" // -1 = custom
    private const val KEY_BAND = "eq_band_"

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext
                .getSharedPreferences("zeni_eq", Context.MODE_PRIVATE)
        }
    }

    /** Tempel ke audio session baru (dipanggil dari PlayerManager.attachPlayer). */
    fun attach(audioSessionId: Int) {
        if (!::prefs.isInitialized) return
        if (audioSessionId <= 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        try {
            eq = Equalizer(0, audioSessionId).apply {
                enabled = isEnabled()
                if (isEnabled()) applySaved()
            }
        } catch (e: Exception) {
            eq = null
        }
    }

    fun release() {
        try { eq?.release() } catch (e: Exception) { /* abaikan */ }
        eq = null
        sessionId = -1
    }

    fun isAvailable(): Boolean = eq != null

    fun isEnabled(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_ENABLED, false) else false

    fun setEnabled(on: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        try {
            eq?.enabled = on
            if (on) applySaved()
        } catch (e: Exception) { /* abaikan */ }
    }

    fun numBands(): Int = try {
        eq?.numberOfBands?.toInt() ?: 5
    } catch (e: Exception) { 5 }

    /** Rentang level dalam millibel, mis. -1500..1500. */
    fun levelRange(): IntRange = try {
        val r = eq?.bandLevelRange
        if (r != null && r.size >= 2) r[0].toInt()..r[1].toInt()
        else -1500..1500
    } catch (e: Exception) { -1500..1500 }

    /** Frekuensi tengah band dalam Hz, untuk label. */
    fun centerFreqHz(band: Int): Int = try {
        ((eq?.getCenterFreq(band.toShort()) ?: 0) / 1000)
    } catch (e: Exception) { 0 }

    fun getBandLevel(band: Int): Int = try {
        eq?.getBandLevel(band.toShort())?.toInt() ?: 0
    } catch (e: Exception) { 0 }

    fun setBandLevel(band: Int, level: Int) {
        try { eq?.setBandLevel(band.toShort(), level.toShort()) } catch (e: Exception) { /* abaikan */ }
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_BAND + band, level).apply()
            prefs.edit().putInt(KEY_PRESET, -1).apply() // jadi custom
        }
    }

    fun presets(): List<String> = try {
        val n = eq?.numberOfPresets?.toInt() ?: 0
        (0 until n).map { eq?.getPresetName(it.toShort()) ?: "Preset $it" }
    } catch (e: Exception) { emptyList() }

    fun currentPreset(): Int =
        if (::prefs.isInitialized) prefs.getInt(KEY_PRESET, 0) else 0

    fun usePreset(index: Int) {
        try { eq?.usePreset(index.toShort()) } catch (e: Exception) { /* abaikan */ }
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_PRESET, index).apply()
            // Simpan level tiap band dari preset
            val n = numBands()
            for (b in 0 until n) {
                prefs.edit().putInt(KEY_BAND + b, getBandLevel(b)).apply()
            }
        }
    }

    /** Terapkan pengaturan tersimpan (dipanggil saat attach/session baru). */
    private fun applySaved() {
        val e = eq ?: return
        try {
            val preset = if (::prefs.isInitialized) prefs.getInt(KEY_PRESET, 0) else 0
            if (preset >= 0 && preset < (e.numberOfPresets ?: 0)) {
                e.usePreset(preset.toShort())
            } else {
                val n = numBands()
                for (b in 0 until n) {
                    val lvl = if (::prefs.isInitialized)
                        prefs.getInt(KEY_BAND + b, 0) else 0
                    e.setBandLevel(b.toShort(), lvl.toShort())
                }
            }
        } catch (ex: Exception) { /* abaikan */ }
    }

    /** Label frekuensi yang ramah dibaca, mis. "60Hz", "230Hz", "3.6kHz". */
    fun freqLabel(hz: Int): String = when {
        hz <= 0 -> "?"
        hz < 1000 -> "${hz}Hz"
        else -> {
            val k = hz / 1000f
            if (k >= 10) "${k.toInt()}kHz" else "%.1fkHz".format(k)
        }
    }
}

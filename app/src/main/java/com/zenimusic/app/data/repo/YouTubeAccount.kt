package com.zenimusic.app.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zenimusic.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Satu playlist milik akun YouTube pengguna. */
data class AccountPlaylist(
    val id: String,
    val title: String,
    val countText: String = ""
)

/**
 * Akses akun YouTube pengguna ala YMusic: login terjadi di WebView halaman
 * YouTube resmi (LoginActivity), lalu cookie sesi disimpan TERENKRIPSI
 * (AES-GCM, kunci di Android Keystore) di penyimpanan privat aplikasi.
 * Cookie tidak pernah keluar dari perangkat selain ke youtube.com sendiri.
 *
 * Data dibaca lewat InnerTube (API internal YouTube Music, klien WEB_REMIX)
 * dengan header otorisasi SAPISIDHASH — pola yang sama dipakai aplikasi
 * sejenis. Sifatnya hanya-baca: daftar playlist akun + isi playlist +
 * lagu yang disukai (playlist khusus "LM").
 */
class YouTubeAccount(
    private val ctx: Context,
    private val http: OkHttpClient
) {
    private val prefs = ctx.getSharedPreferences("yt_session", Context.MODE_PRIVATE)

    // ---------- Penyimpanan sesi terenkripsi ----------

    private fun key(): SecretKey {
        val alias = "zeni_yt_session_key"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packedB64: String): String? = try {
        val packed = Base64.decode(packedB64, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val data = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /** Simpan cookie sesi hasil login WebView. */
    fun saveCookies(cookieHeader: String) {
        prefs.edit().putString("enc", encrypt(cookieHeader)).apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    private fun cookies(): String? =
        prefs.getString("enc", null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    val isLoggedIn: Boolean
        get() = cookies()?.let { sapisidOf(it) } != null

    private fun sapisidOf(cookieHeader: String): String? {
        val map = cookieHeader.split(";").mapNotNull {
            val kv = it.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        return map["__Secure-3PAPISID"] ?: map["SAPISID"] ?: map["__Secure-1PAPISID"]
    }

    // ---------- InnerTube ----------

    private fun authHeader(cookieHeader: String): String {
        val sapisid = sapisidOf(cookieHeader) ?: ""
        val ts = System.currentTimeMillis() / 1000
        val toHash = "$ts $sapisid https://music.youtube.com"
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(toHash.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$sha1"
    }

    private suspend fun browse(browseId: String?, continuation: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val cookieHeader = cookies() ?: throw IllegalStateException("Belum masuk")
            val clientVersion = "1.20251029.03.00"
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                if (continuation != null) put("continuation", continuation)
                else put("browseId", browseId)
            }
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse" +
                    "?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30&prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Cookie", cookieHeader)
                .header("Authorization", authHeader(cookieHeader))
                .header("X-Goog-AuthUser", "0")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                    ?: throw IllegalStateException("Respon kosong dari YouTube")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("YouTube menolak permintaan (${resp.code})")
                }
                JSONObject(text)
            }
        }

    // ---------- Penjelajahan JSON (toleran terhadap bentuk renderer) ----------

    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), visit)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walk(node.opt(i), visit)
            }
        }
    }

    /** Teks dari objek {"runs":[{"text":..}]} atau {"simpleText":..}. */
    private fun runsText(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    private fun flexText(renderer: JSONObject, index: Int): String {
        val cols = renderer.optJSONArray("flexColumns") ?: return ""
        val col = cols.optJSONObject(index) ?: return ""
        val inner = col.optJSONObject("musicResponsiveListItemFlexColumnRenderer") ?: col
        return runsText(inner.optJSONObject("text"))
    }

    private fun firstBrowseId(node: JSONObject): String? {
        var found: String? = null
        walk(node) { obj ->
            if (found == null) {
                val bid = obj.optJSONObject("browseEndpoint")?.optString("browseId")
                if (!bid.isNullOrEmpty() && bid.startsWith("VL")) found = bid
            }
        }
        return found
    }

    private fun thumbOf(container: JSONObject?): String {
        var best = ""
        var bestW = -1
        walk(container) { obj ->
            val thumbs = obj.optJSONArray("thumbnails")
            if (thumbs != null) {
                for (i in 0 until thumbs.length()) {
                    val t = thumbs.optJSONObject(i) ?: continue
                    val w = t.optInt("width")
                    if (w >= bestW) {
                        bestW = w
                        best = t.optString("url")
                    }
                }
            }
        }
        return best
    }

    private fun durationOf(text: String): Long {
        val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    // ---------- API publik ----------

    /** Daftar playlist milik akun (dari halaman perpustakaan YouTube Music). */
    suspend fun libraryPlaylists(): List<AccountPlaylist> {
        val resp = browse("FEmusic_library_landing", null)
        val out = LinkedHashMap<String, AccountPlaylist>()
        walk(resp) { obj ->
            val renderer = obj.optJSONObject("musicTwoRowItemRenderer")
                ?: obj.optJSONObject("musicResponsiveListItemRenderer")
                ?: return@walk
            val bid = firstBrowseId(renderer) ?: return@walk
            val id = bid.removePrefix("VL")
            if (id == "LM" || id == "LL" || id.length < 8) return@walk
            val title = runsText(renderer.optJSONObject("title"))
                .ifEmpty { flexText(renderer, 0) }
            if (title.isEmpty()) return@walk
            val count = runsText(renderer.optJSONObject("subtitle"))
                .ifEmpty { flexText(renderer, 1) }
            if (!out.containsKey(id)) out[id] = AccountPlaylist(id, title, count)
        }
        return out.values.toList()
    }

    /**
     * Isi sebuah playlist akun (id tanpa awalan "VL"; "LM" = lagu yang disukai).
     * Mengikuti continuation sampai 4 halaman / 200 lagu.
     */
    suspend fun playlistTracks(playlistId: String): List<Track> {
        val out = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        var first = true
        var pages = 0
        while ((first || token != null) && pages < 4 && out.size < 200) {
            first = false
            pages++
            val resp = browse(if (token == null) "VL$playlistId" else null, token)
            token = null
            walk(resp) { obj ->
                val cont = obj.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!cont.isNullOrEmpty()) token = cont
                val r = obj.optJSONObject("musicResponsiveListItemRenderer") ?: return@walk
                val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")
                    ?.takeIf { it.isNotEmpty() }
                    ?: findWatchVideoId(r)
                    ?: return@walk
                if (!seen.add(videoId)) return@walk
                val title = flexText(r, 0)
                if (title.isEmpty()) return@walk
                val artist = flexText(r, 1)
                val fixed = r.optJSONArray("fixedColumns")
                    ?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                val durationSec = durationOf(runsText(fixed?.optJSONObject("text")))
                out.add(
                    Track(
                        videoId = videoId,
                        title = title,
                        channel = artist.ifEmpty { "YouTube Music" },
                        thumbnailUrl = thumbOf(r.optJSONObject("thumbnail")),
                        durationSec = durationSec
                    )
                )
            }
        }
        return out
    }

    private fun findWatchVideoId(renderer: JSONObject): String? {
        var found: String? = null
        walk(renderer) { obj ->
            if (found == null) {
                val vid = obj.optJSONObject("watchEndpoint")?.optString("videoId")
                if (!vid.isNullOrEmpty()) found = vid
            }
        }
        return found
    }
}

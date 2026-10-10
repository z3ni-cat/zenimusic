package com.zenimusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable

/**
 * Ikon line-icon modern digambar sendiri via Canvas — gaya konsisten:
 * stroke membulat, simple, clean. Tidak ada lagi emoji di tombol.
 * Digambar pada kanvas 96x96; ImageView akan menskalakan.
 */
object ZeniIcons {

    enum class Kind {
        PLAY, PAUSE, NEXT, PREV,
        HEART, HEART_FILLED,
        DOWNLOAD, PLUS, CLOSE, TRASH,
        SEARCH, HOME, PLAYLIST, INFO, CHAT,
        CHEVRON_RIGHT, CHEVRON_LEFT,
        SHUFFLE, REPEAT, LYRICS
    }

    private const val S = 96f
    private const val SW = 7f

    fun drawable(ctx: Context, kind: Kind, color: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(S.toInt(), S.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = SW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        // Fill + stroke dengan round join => bentuk terisi yang sudutnya membulat.
        val fillRound = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = SW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (kind) {
            Kind.PLAY -> {
                val p = Path().apply {
                    moveTo(40f, 30f); lineTo(40f, 66f); lineTo(67f, 48f); close()
                }
                c.drawPath(p, fillRound)
            }
            Kind.PAUSE -> {
                c.drawRoundRect(36f, 30f, 46.5f, 66f, 5f, 5f, fill)
                c.drawRoundRect(49.5f, 30f, 60f, 66f, 5f, 5f, fill)
            }
            Kind.NEXT -> {
                val p = Path().apply {
                    moveTo(30f, 30f); lineTo(30f, 66f); lineTo(55f, 48f); close()
                }
                c.drawPath(p, fillRound)
                c.drawRoundRect(59f, 30f, 65f, 66f, 3f, 3f, fill)
            }
            Kind.PREV -> {
                c.drawRoundRect(31f, 30f, 37f, 66f, 3f, 3f, fill)
                val p = Path().apply {
                    moveTo(41f, 30f); lineTo(41f, 66f); lineTo(66f, 48f); close()
                }
                c.drawPath(p, fillRound)
            }
            Kind.HEART -> c.drawPath(heartPath(), stroke)
            Kind.HEART_FILLED -> c.drawPath(heartPath(), fillRound)
            Kind.DOWNLOAD -> {
                c.drawLine(48f, 26f, 48f, 56f, stroke)
                val head = Path().apply {
                    moveTo(36f, 46f); lineTo(48f, 58f); lineTo(60f, 46f)
                }
                c.drawPath(head, stroke)
                c.drawLine(28f, 72f, 68f, 72f, stroke)
            }
            Kind.PLUS -> {
                c.drawLine(48f, 28f, 48f, 68f, stroke)
                c.drawLine(28f, 48f, 68f, 48f, stroke)
            }
            Kind.CLOSE -> {
                c.drawLine(34f, 34f, 62f, 62f, stroke)
                c.drawLine(62f, 34f, 34f, 62f, stroke)
            }
            Kind.TRASH -> {
                c.drawLine(32f, 28f, 64f, 28f, stroke)           // tutup
                c.drawLine(42f, 28f, 42f, 22f, stroke)           // gagang
                c.drawLine(54f, 28f, 54f, 22f, stroke)
                c.drawLine(42f, 22f, 54f, 22f, stroke)
                val body = Path().apply {                        // badan
                    moveTo(36f, 34f); lineTo(60f, 34f)
                    lineTo(57f, 70f); lineTo(39f, 70f); close()
                }
                c.drawPath(body, stroke)
                c.drawLine(44f, 41f, 44f, 62f, stroke)
                c.drawLine(52f, 41f, 52f, 62f, stroke)
            }
            Kind.SEARCH -> {
                c.drawCircle(42f, 42f, 19f, stroke)
                c.drawLine(56f, 56f, 73f, 73f, stroke)
            }
            Kind.HOME -> {
                val p = Path().apply {
                    moveTo(20f, 50f); lineTo(48f, 24f); lineTo(76f, 50f)   // atap
                    moveTo(29f, 45f); lineTo(29f, 72f)                     // badan
                    lineTo(67f, 72f); lineTo(67f, 45f)
                }
                c.drawPath(p, stroke)
            }
            Kind.PLAYLIST -> {
                c.drawLine(26f, 34f, 56f, 34f, stroke)
                c.drawLine(26f, 48f, 56f, 48f, stroke)
                c.drawLine(26f, 62f, 48f, 62f, stroke)
                c.drawCircle(66f, 59f, 5.5f, fill)                          // not: kepala
                c.drawLine(71.5f, 59f, 71.5f, 35f, stroke)                  // not: tangkai
            }
            Kind.INFO -> {
                c.drawCircle(48f, 48f, 21f, stroke)
                c.drawCircle(48f, 37f, 4f, fill)
                c.drawLine(48f, 49f, 48f, 65f, stroke)
            }
            Kind.CHAT -> {
                c.drawRoundRect(22f, 26f, 74f, 60f, 12f, 12f, stroke)
                val tail = Path().apply {
                    moveTo(34f, 58f); lineTo(28f, 74f); lineTo(47f, 60f)
                }
                c.drawPath(tail, stroke)
            }
            Kind.CHEVRON_RIGHT -> {
                val p = Path().apply {
                    moveTo(40f, 32f); lineTo(58f, 48f); lineTo(40f, 64f)
                }
                c.drawPath(p, stroke)
            }
            Kind.CHEVRON_LEFT -> {
                val p = Path().apply {
                    moveTo(56f, 32f); lineTo(38f, 48f); lineTo(56f, 64f)
                }
                c.drawPath(p, stroke)
            }
            Kind.SHUFFLE -> {
                val a = Path().apply {
                    moveTo(18f, 32f); cubicTo(40f, 32f, 56f, 60f, 74f, 60f)
                }
                c.drawPath(a, stroke)
                val ah1 = Path().apply {
                    moveTo(64f, 52f); lineTo(74f, 60f); lineTo(64f, 68f)
                }
                c.drawPath(ah1, stroke)
                val b = Path().apply {
                    moveTo(18f, 64f); cubicTo(40f, 64f, 56f, 36f, 74f, 36f)
                }
                c.drawPath(b, stroke)
                val ah2 = Path().apply {
                    moveTo(64f, 28f); lineTo(74f, 36f); lineTo(64f, 44f)
                }
                c.drawPath(ah2, stroke)
            }
            Kind.REPEAT -> {
                val oval = RectF(24f, 30f, 72f, 66f)
                c.drawArc(oval, -40f, 260f, false, stroke)
                // Kepala panah di ujung busur (sudut 220°).
                val tipX = 29.6f; val tipY = 36.4f
                val tri = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(25.5f, 46.8f)
                    lineTo(18.8f, 39.4f)
                    close()
                }
                c.drawPath(tri, fillRound)
            }
            Kind.LYRICS -> {
                // Baris-baris teks (lirik) — tiga garis horizontal.
                c.drawLine(28f, 34f, 68f, 34f, stroke)
                c.drawLine(28f, 48f, 68f, 48f, stroke)
                c.drawLine(28f, 62f, 56f, 62f, stroke)
            }
        }
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun heartPath(): Path = Path().apply {
        moveTo(48f, 80f)
        cubicTo(22f, 60f, 14f, 40f, 27f, 29f)
        cubicTo(37f, 21f, 46f, 28f, 48f, 37f)
        cubicTo(50f, 28f, 59f, 21f, 69f, 29f)
        cubicTo(82f, 40f, 74f, 60f, 48f, 80f)
        close()
    }
}

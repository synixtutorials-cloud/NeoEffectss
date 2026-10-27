package com.cusil.neoeffects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath

object LayerGfx {
    val SHAPE_NAMES = listOf(
        "Circle", "Rounded square", "Plus", "Ring", "Triangle", "Pie", "Hexagon", "Flower",
        "Arrow", "Trapezoid", "Square", "Star", "Line", "Bar", "Corner"
    )
    val TEXT_MODES = listOf("Static", "Typewriter", "Count up/down", "Timecode", "Randomizer", "Spacing", "Grow")
    private const val RAND = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&@?"

    private fun poly(pts: FloatArray, cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        var i = 0
        while (i < pts.size) {
            val x = cx + pts[i] * r
            val y = cy + pts[i + 1] * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            i += 2
        }
        p.close()
        return p
    }

    private fun ngon(n: Int, cx: Float, cy: Float, r: Float, inner: Float): Path {
        val p = Path()
        val total = if (inner > 0f) n * 2 else n
        for (i in 0 until total) {
            val a = -Math.PI / 2.0 + 2.0 * Math.PI * i / total
            val rr = if (inner > 0f && i % 2 == 1) r * inner else r
            val x = cx + (rr * Math.cos(a)).toFloat()
            val y = cy + (rr * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    fun shapePath(kind: Int, cx: Float, cy: Float, r: Float): Path {
        val rect = Rect(cx - r, cy - r, cx + r, cy + r)
        return when (kind) {
            0, 3 -> Path().apply { addOval(rect) }
            1 -> Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r * 0.3f))) }
            2 -> Path().apply {
                addRect(Rect(cx - r, cy - r * 0.17f, cx + r, cy + r * 0.17f))
                addRect(Rect(cx - r * 0.17f, cy - r, cx + r * 0.17f, cy + r))
            }
            4 -> poly(floatArrayOf(0f, -1f, 0.95f, 0.8f, -0.95f, 0.8f), cx, cy, r)
            5 -> Path().apply {
                moveTo(cx, cy)
                arcTo(rect, -90f, 270f, false)
                close()
            }
            6 -> ngon(6, cx, cy, r, 0f)
            7 -> Path().apply {
                for (i in 0 until 5) {
                    val a = -Math.PI / 2.0 + 2.0 * Math.PI * i / 5.0
                    val px = cx + (r * 0.55f * Math.cos(a)).toFloat()
                    val py = cy + (r * 0.55f * Math.sin(a)).toFloat()
                    addOval(Rect(px - r * 0.42f, py - r * 0.42f, px + r * 0.42f, py + r * 0.42f))
                }
                addOval(Rect(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f))
            }
            8 -> poly(floatArrayOf(-0.95f, -0.2f, 0.2f, -0.2f, 0.2f, -0.65f, 0.95f, 0f, 0.2f, 0.65f, 0.2f, 0.2f, -0.95f, 0.2f), cx, cy, r)
            9 -> poly(floatArrayOf(-0.95f, 0.6f, 0.95f, 0.6f, 0.5f, -0.6f, -0.5f, -0.6f), cx, cy, r)
            10 -> Path().apply { addRect(rect) }
            11 -> ngon(5, cx, cy, r, 0.45f)
            12 -> Path().apply { addRect(Rect(cx - r, cy - r * 0.04f, cx + r, cy + r * 0.04f)) }
            13 -> poly(floatArrayOf(-0.9f, 0.45f, -0.75f, 0.6f, 0.9f, -0.45f, 0.75f, -0.6f), cx, cy, r)
            else -> poly(floatArrayOf(-0.7f, -0.7f, -0.2f, -0.7f, -0.2f, 0.2f, 0.7f, 0.2f, 0.7f, 0.7f, -0.7f, 0.7f), cx, cy, r)
        }
    }

    fun canvasDims(p: Project, maxSide: Int): IntArray {
        val a = p.width.toFloat() / p.height.toFloat()
        var w: Int
        var h: Int
        if (a >= 1f) {
            w = maxSide
            h = (maxSide / a).toInt()
        } else {
            h = maxSide
            w = (maxSide * a).toInt()
        }
        w = maxOf(2, w - w % 2)
        h = maxOf(2, h - h % 2)
        return intArrayOf(w, h)
    }

    fun displayText(l: Layer, tSec: Float, durSec: Float, fps: Int): String {
        return when (l.textMode) {
            1 -> {
                val n = (tSec * maxOf(1f, l.to)).toInt().coerceIn(0, l.text.length)
                l.text.substring(0, n)
            }
            2 -> {
                val f = if (durSec > 0f) (tSec / durSec).coerceIn(0f, 1f) else 1f
                (l.from + (l.to - l.from) * f).toInt().toString()
            }
            3 -> {
                val f = maxOf(1, fps)
                val total = (tSec * f).toInt() + (l.from * f).toInt()
                String.format("%02d:%02d:%02d", total / f / 60, (total / f) % 60, total % f)
            }
            4 -> {
                val reveal = (tSec * maxOf(1f, l.to)).toInt()
                val sb = StringBuilder()
                for (i in l.text.indices) {
                    val ch = l.text[i]
                    if (i < reveal || ch == ' ' || ch == '\n') sb.append(ch)
                    else sb.append(RAND[(((tSec * 30f).toInt() * 7) + i * 13) % RAND.length])
                }
                sb.toString()
            }
            else -> l.text
        }
    }

    fun signature(p: Project, l: Layer, localMs: Long, maxSide: Int): String {
        val anim = if (l.type == "text" && l.textMode != 0) (localMs / 33L).toString() else "s"
        return listOf(
            l.type, l.text, l.size, l.color, l.stroke, l.strokeW, l.bold, l.italic, l.textMode,
            l.from, l.to, l.shape, l.uri, p.width, p.height, maxSide, anim
        ).joinToString("|")
    }

    fun render(ctx: Context, p: Project, l: Layer, localMs: Long, maxSide: Int): Bitmap? {
        val d = canvasDims(p, maxSide)
        val w = d[0]
        val h = d[1]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val tSec = localMs / 1000f
        when (l.type) {
            "text" -> {
                var sizePx = l.size * h
                var spacing = 0f
                if (l.textMode == 6) sizePx *= easeApply(5, tSec / 0.6f, null).coerceAtLeast(0f)
                if (l.textMode == 5) spacing = (l.to / 100f) * minOf(1f, tSec)
                paint.textSize = maxOf(2f, sizePx)
                paint.textAlign = Paint.Align.CENTER
                paint.letterSpacing = spacing
                val style = if (l.bold && l.italic) Typeface.BOLD_ITALIC else if (l.bold) Typeface.BOLD else if (l.italic) Typeface.ITALIC else Typeface.NORMAL
                paint.typeface = Typeface.create(Typeface.DEFAULT, style)
                val lines = displayText(l, tSec, l.lenMs / 1000f, p.fps).split("\n")
                val lineH = paint.textSize * 1.2f
                val y0 = h / 2f - lineH * lines.size / 2f + paint.textSize
                for (i in lines.indices) {
                    val y = y0 + i * lineH
                    if (l.strokeW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = l.strokeW * paint.textSize
                        paint.color = l.stroke
                        c.drawText(lines[i], w / 2f, y, paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = l.color
                    c.drawText(lines[i], w / 2f, y, paint)
                }
            }
            "shape" -> {
                val r = l.size * minOf(w, h) / 2f
                val path = shapePath(l.shape, w / 2f, h / 2f, r).asAndroidPath()
                if (l.shape == 3 || l.shape == 12) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2f, r * (if (l.shape == 3) 0.12f else 0.08f))
                } else {
                    paint.style = Paint.Style.FILL
                }
                paint.color = l.color
                c.drawPath(path, paint)
                if (l.strokeW > 0f && l.shape != 3 && l.shape != 12) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = l.strokeW * r
                    paint.color = l.stroke
                    c.drawPath(path, paint)
                }
            }
            "image" -> {
                val src0 = try { loadBitmap(ctx, Uri.parse(l.uri), 1024) } catch (e: Exception) { null }
                if (src0 == null) return null
                val src = src0
                val s = minOf(w * l.size / src.width, h * l.size / src.height)
                val dw = src.width * s
                val dh = src.height * s
                val f = Paint(Paint.FILTER_BITMAP_FLAG)
                c.drawBitmap(src, null, android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), f)
            }
            else -> return null
        }
        return bmp
    }
}

package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pipeline v3 — closer to clean phone-camera look indoors,
 * still a light polish in daylight.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.48f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumY = 0L
        var darkCount = 0
        var brightCount = 0
        // Percentile approximation via histogram
        val hist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r
            sumG += g
            sumB += b
            val y = (r * 3 + g * 6 + b) / 10
            sumY += y
            hist[y.coerceIn(0, 255)]++
            if (y < 35) darkCount++
            if (y > 230) brightCount++
        }
        val avgR = sumR.toFloat() / n
        val avgG = sumG.toFloat() / n
        val avgB = sumB.toFloat() / n
        val avgY = sumY.toFloat() / n
        val darkRatio = darkCount.toFloat() / n
        val brightRatio = brightCount.toFloat() / n

        fun percentile(p: Float): Int {
            val target = (n * p).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        val p5 = percentile(0.05f)
        val p95 = percentile(0.95f)

        val warmCast = avgR > avgB + 10f
        val indoor = avgY < 125f || (warmCast && avgY < 150f) || darkRatio > 0.18f

        // Levels stretch — gentle, avoid plastic look
        val black = if (indoor) (p5 * 0.65f) else (p5 * 0.4f)
        val white = if (indoor) (255f - (255 - p95) * 0.35f) else (255f - (255 - p95) * 0.2f)
        val range = (white - black).coerceAtLeast(64f)

        // WB
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = (avg / avgR.coerceAtLeast(1f)).coerceIn(0.90f, 1.10f)
        var gainG = (avg / avgG.coerceAtLeast(1f)).coerceIn(0.90f, 1.10f)
        var gainB = (avg / avgB.coerceAtLeast(1f)).coerceIn(0.90f, 1.10f)
        if (indoor && warmCast) {
            gainR *= 0.96f
            gainB *= 1.05f
        }
        val wbT = if (indoor) s * 0.9f else s * 0.55f
        val gR = lerp(1f, gainR, wbT)
        val gG = lerp(1f, gainG, wbT)
        val gB = lerp(1f, gainB, wbT)

        val shadowLift = if (indoor) 0.22f * s else 0.06f * s
        val highlightComp = if (indoor || brightRatio > 0.06f) 0.18f * s else 0.08f * s
        val contrastAmt = if (indoor) 0.06f * s else 0.16f * s
        val vibranceAmt = if (indoor) 0.08f * s else 0.14f * s
        val exposureBias = if (indoor && avgY < 100f) (0.06f * s) else 0f

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()

            // Levels
            r = ((r - black) / range * 255f).coerceIn(0f, 255f)
            g = ((g - black) / range * 255f).coerceIn(0f, 255f)
            b = ((b - black) / range * 255f).coerceIn(0f, 255f)

            r *= gR
            g *= gG
            b *= gB

            if (exposureBias > 0f) {
                r = (r + exposureBias * 255f).coerceIn(0f, 255f)
                g = (g + exposureBias * 255f).coerceIn(0f, 255f)
                b = (b + exposureBias * 255f).coerceIn(0f, 255f)
            }

            var y = (r * 0.3f + g * 0.59f + b * 0.11f) / 255f
            val y2 = shadowHighlightCurve(y, shadowLift, highlightComp)
            if (y > 1e-4f) {
                val scale = y2 / y
                r = (r * scale).coerceIn(0f, 255f)
                g = (g * scale).coerceIn(0f, 255f)
                b = (b * scale).coerceIn(0f, 255f)
            }

            r = toneContrast(r / 255f, contrastAmt) * 255f
            g = toneContrast(g / 255f, contrastAmt) * 255f
            b = toneContrast(b / 255f, contrastAmt) * 255f

            val (vr, vg, vb) = vibrance(r, g, b, vibranceAmt)
            r = vr.coerceIn(0f, 255f)
            g = vg.coerceIn(0f, 255f)
            b = vb.coerceIn(0f, 255f)

            out[i] = (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }

        localContrast(out, w, h, amount = if (indoor) 0.10f * s else 0.20f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return if (s > 0.3f && !indoor) mildClarity(result, 0.22f * s) else result
    }

    private fun shadowHighlightCurve(y: Float, shadowLift: Float, highlightComp: Float): Float {
        var v = y.coerceIn(0f, 1f)
        if (shadowLift > 0f && v < 0.5f) {
            val t = v / 0.5f
            v = v + shadowLift * (1f - t) * (1f - v) * 1.1f
        }
        if (highlightComp > 0f && v > 0.7f) {
            val t = (v - 0.7f) / 0.3f
            v = 0.7f + 0.3f * (t / (1f + highlightComp * 1.2f * t))
        }
        return v.coerceIn(0f, 1f)
    }

    private fun toneContrast(x: Float, amount: Float): Float {
        val c = x.coerceIn(0f, 1f)
        if (amount < 0.01f) return c
        val mid = 0.5f + (c - 0.5f) * (1f + amount)
        return lerp(c, mid.coerceIn(0f, 1f), 0.7f)
    }

    private fun vibrance(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        if (amount < 0.01f) return Triple(r, g, b)
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc > 1f) (maxc - minc) / maxc else 0f
        val boost = amount * (1f - sat)
        val avg = (r + g + b) / 3f
        return Triple(r + (r - avg) * boost, g + (g - avg) * boost, b + (b - avg) * boost)
    }

    private fun localContrast(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.02f) return
        val copy = px.copyOf()
        val radius = 1
        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                val i = y * w + x
                var lr = 0
                var lg = 0
                var lb = 0
                var c = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val p = copy[(y + dy) * w + (x + dx)]
                        lr += (p shr 16) and 0xFF
                        lg += (p shr 8) and 0xFF
                        lb += p and 0xFF
                        c++
                    }
                }
                lr /= c; lg /= c; lb /= c
                val p = copy[i]
                val a = (p ushr 24) and 0xFF
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (r * 3 + g * 6 + b) / 10
                val localAmt = if (y < 40) amount * 0.25f else amount
                px[i] = (a shl 24) or
                    ((r + (r - lr) * localAmt).toInt().coerceIn(0, 255) shl 16) or
                    ((g + (g - lg) * localAmt).toInt().coerceIn(0, 255) shl 8) or
                    (b + (b - lb) * localAmt).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun mildClarity(src: Bitmap, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val c = 1f + amount * 0.14f
        val t = (1f - c) * 10f
        paint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            ))
        )
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

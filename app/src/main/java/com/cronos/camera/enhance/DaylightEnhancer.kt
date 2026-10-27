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
 * Scene-aware enhance (daylight + indoor mixed light).
 * strength 0..1 blends original → full pipeline.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.55f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        // --- Scene stats ---
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumY = 0L
        var darkCount = 0
        var brightCount = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r
            sumG += g
            sumB += b
            val y = (r * 3 + g * 6 + b) / 10
            sumY += y
            if (y < 40) darkCount++
            if (y > 220) brightCount++
        }
        val avgR = sumR.toFloat() / n
        val avgG = sumG.toFloat() / n
        val avgB = sumB.toFloat() / n
        val avgY = sumY.toFloat() / n
        val darkRatio = darkCount.toFloat() / n
        val brightRatio = brightCount.toFloat() / n

        // Indoor heuristic: lower overall brightness or strong warm cast under artificial light
        val warmCast = avgR > avgB + 12f
        val indoor = avgY < 118f || (warmCast && avgY < 145f) || darkRatio > 0.22f

        // White balance gains — milder outdoors, stronger neutralize indoors
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = if (avgR > 1f) avg / avgR else 1f
        var gainG = if (avgG > 1f) avg / avgG else 1f
        var gainB = if (avgB > 1f) avg / avgB else 1f
        val wbRange = if (indoor) 0.88f to 1.12f else 0.94f to 1.06f
        gainR = gainR.coerceIn(wbRange.first, wbRange.second)
        gainG = gainG.coerceIn(wbRange.first, wbRange.second)
        gainB = gainB.coerceIn(wbRange.first, wbRange.second)
        // Indoor: pull yellow out a bit more (boost B, tame R)
        if (indoor && warmCast) {
            gainR *= 0.97f
            gainB *= 1.04f
        }
        val wbBlend = if (indoor) s * 0.95f else s * 0.7f
        val gR = lerp(1f, gainR, wbBlend)
        val gG = lerp(1f, gainG, wbBlend)
        val gB = lerp(1f, gainB, wbBlend)

        // Shadow lift & highlight compress amounts
        val shadowLift = if (indoor) 0.18f * s else 0.08f * s
        val highlightComp = if (brightRatio > 0.08f || indoor) 0.22f * s else 0.12f * s
        val contrastAmt = if (indoor) 0.12f * s else 0.22f * s
        val vibranceAmt = if (indoor) 0.12f * s else 0.18f * s

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF) * gR
            var g = ((p shr 8) and 0xFF) * gG
            var b = (p and 0xFF) * gB

            // Luma-based shadow lift + highlight roll-off
            var y = (r * 0.3f + g * 0.59f + b * 0.11f) / 255f
            val y2 = shadowHighlightCurve(y, shadowLift, highlightComp)
            if (y > 1e-3f) {
                val scale = y2 / y
                r *= scale
                g *= scale
                b *= scale
            }

            // Soft contrast around midtones (weaker indoors)
            r = toneContrast(r / 255f, contrastAmt) * 255f
            g = toneContrast(g / 255f, contrastAmt) * 255f
            b = toneContrast(b / 255f, contrastAmt) * 255f

            val (vr, vg, vb) = vibrance(r, g, b, vibranceAmt)
            r = vr.coerceIn(0f, 255f)
            g = vg.coerceIn(0f, 255f)
            b = vb.coerceIn(0f, 255f)

            // Outdoor only: tiny warm bias; indoor: none
            if (!indoor) {
                r = (r + 1.5f * s).coerceIn(0f, 255f)
                b = (b - 0.8f * s).coerceIn(0f, 255f)
            }

            out[i] = (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }

        // Local contrast — softer indoors
        localContrast(out, w, h, amount = if (indoor) 0.14f * s else 0.24f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return if (s > 0.25f) mildClarity(result, if (indoor) 0.18f * s else 0.28f * s) else result
    }

    /** Lift shadows, compress highlights in linear-ish luma space. */
    private fun shadowHighlightCurve(y: Float, shadowLift: Float, highlightComp: Float): Float {
        var v = y.coerceIn(0f, 1f)
        // Shadows: lift with (1 - (1-y)^k)
        if (shadowLift > 0f && v < 0.45f) {
            val t = v / 0.45f
            val lifted = v + shadowLift * (1f - t) * (1f - v)
            v = lifted
        }
        // Highlights: soft knee
        if (highlightComp > 0f && v > 0.65f) {
            val t = (v - 0.65f) / 0.35f
            val compressed = 0.65f + 0.35f * (t / (1f + highlightComp * t))
            v = compressed
        }
        return v.coerceIn(0f, 1f)
    }

    private fun toneContrast(x: Float, amount: Float): Float {
        val c = x.coerceIn(0f, 1f)
        if (amount < 0.01f) return c
        val mid = 0.5f + (c - 0.5f) * (1f + amount)
        return lerp(c, mid.coerceIn(0f, 1f), 0.85f)
    }

    private fun vibrance(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        if (amount < 0.01f) return Triple(r, g, b)
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc > 1f) (maxc - minc) / maxc else 0f
        val boost = amount * (1f - sat)
        val avg = (r + g + b) / 3f
        return Triple(
            r + (r - avg) * boost,
            g + (g - avg) * boost,
            b + (b - avg) * boost
        )
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
                lr /= c
                lg /= c
                lb /= c
                val p = copy[i]
                val a = (p ushr 24) and 0xFF
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Protect near-black from going more black
                val y = (r * 3 + g * 6 + b) / 10
                val localAmt = if (y < 35) amount * 0.35f else amount
                val nr = (r + (r - lr) * localAmt).toInt().coerceIn(0, 255)
                val ng = (g + (g - lg) * localAmt).toInt().coerceIn(0, 255)
                val nb = (b + (b - lb) * localAmt).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    private fun mildClarity(src: Bitmap, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val c = 1f + amount * 0.18f
        val t = (1f - c) * 12f
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

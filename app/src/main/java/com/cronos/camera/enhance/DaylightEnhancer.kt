package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Daylight-first computational pipeline.
 * strength in 0f..1f blends original → fully enhanced.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.72f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pass 1: stats for gentle auto levels (daylight)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var n = pixels.size
        for (p in pixels) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        val avgR = sumR.toFloat() / n
        val avgG = sumG.toFloat() / n
        val avgB = sumB.toFloat() / n
        val avg = (avgR + avgG + avgB) / 3f
        // Neutralize mild color cast toward gray mean
        val gainR = if (avgR > 1f) avg / avgR else 1f
        val gainG = if (avgG > 1f) avg / avgG else 1f
        val gainB = if (avgB > 1f) avg / avgB else 1f
        // Keep gains close to 1 for natural daylight
        val gR = lerp(1f, gainR.coerceIn(0.92f, 1.08f), s * 0.85f)
        val gG = lerp(1f, gainG.coerceIn(0.92f, 1.08f), s * 0.85f)
        val gB = lerp(1f, gainB.coerceIn(0.92f, 1.08f), s * 0.85f)

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF) * gR
            var g = ((p shr 8) and 0xFF) * gG
            var b = (p and 0xFF) * gB

            // Soft S-curve contrast (daylight punch without crushing)
            r = toneCurve(r / 255f, s) * 255f
            g = toneCurve(g / 255f, s) * 255f
            b = toneCurve(b / 255f, s) * 255f

            // Vibrance: boost low-sat colors more than already vivid (skin-safer than saturation)
            val (vr, vg, vb) = vibrance(r, g, b, 0.22f * s)
            r = vr
            g = vg
            b = vb

            // Warm daylight micro-bias (very slight)
            r = (r + 2.5f * s).coerceIn(0f, 255f)
            b = (b - 1.5f * s).coerceIn(0f, 255f)

            out[i] = (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }

        // Local contrast (unsharp-mask style on luminance) — subsampled for speed
        localContrast(out, w, h, amount = 0.28f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)

        // Mild sharpen via ColorMatrix edge blend
        return if (s > 0.2f) mildSharpen(result, 0.35f * s) else result
    }

    private fun toneCurve(x: Float, s: Float): Float {
        // Smoothstep-ish S curve around midtones
        val c = x.coerceIn(0f, 1f)
        val curved = c * c * (3f - 2f * c)
        val mid = 0.5f + (curved - 0.5f) * (1f + 0.35f * s)
        return lerp(c, mid.coerceIn(0f, 1f), s)
    }

    private fun vibrance(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc > 1f) (maxc - minc) / maxc else 0f
        val boost = amount * (1f - sat) // less boost when already saturated
        val avg = (r + g + b) / 3f
        return Triple(
            (r + (r - avg) * boost).coerceIn(0f, 255f),
            (g + (g - avg) * boost).coerceIn(0f, 255f),
            (b + (b - avg) * boost).coerceIn(0f, 255f)
        )
    }

    private fun localContrast(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.01f) return
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
                val r = ((p shr 16) and 0xFF)
                val g = ((p shr 8) and 0xFF)
                val b = p and 0xFF
                val nr = (r + (r - lr) * amount).toInt().coerceIn(0, 255)
                val ng = (g + (g - lg) * amount).toInt().coerceIn(0, 255)
                val nb = (b + (b - lb) * amount).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    private fun mildSharpen(src: Bitmap, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Slight contrast matrix as cheap sharpen proxy
        val c = 1f + amount * 0.25f
        val t = (1f - c) / 2f * 255f * 0.15f
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

package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Pipeline v4 — prioritize clean shadows (no posterization / "cracks"),
 * natural color. Lighter touch; strength blends to original.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.42f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumY = 0.0
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
        }
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val avgY = (sumY / n).toFloat()

        fun percentile(p: Float): Int {
            val target = (n * p).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        val p2 = percentile(0.02f)
        val p98 = percentile(0.98f)

        val warmCast = avgR > avgB + 8f
        val indoor = avgY < 130f || warmCast

        // Very gentle levels — avoid crushing/posterizing shadows
        val black = if (indoor) p2 * 0.25f else p2 * 0.15f
        val white = 255f - (255 - p98) * 0.15f
        val range = (white - black).coerceAtLeast(120f)

        // Mild WB only
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = (avg / max(avgR, 1f)).coerceIn(0.94f, 1.06f)
        var gainG = (avg / max(avgG, 1f)).coerceIn(0.94f, 1.06f)
        var gainB = (avg / max(avgB, 1f)).coerceIn(0.94f, 1.06f)
        if (indoor && warmCast) {
            gainR *= 0.98f
            gainB *= 1.03f
        }
        val wbT = s * 0.65f
        val gR = lerp(1f, gainR, wbT)
        val gG = lerp(1f, gainG, wbT)
        val gB = lerp(1f, gainB, wbT)

        val shadowLift = if (indoor) 0.12f * s else 0.04f * s
        val highlightComp = 0.10f * s
        val vibranceAmt = if (indoor) 0.06f * s else 0.10f * s
        val midContrast = if (indoor) 0.04f * s else 0.10f * s

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()

            // Soft levels
            r = ((r - black) / range * 255f).coerceIn(0f, 255f)
            g = ((g - black) / range * 255f).coerceIn(0f, 255f)
            b = ((b - black) / range * 255f).coerceIn(0f, 255f)

            r *= gR
            g *= gG
            b *= gB

            // Smooth shadow lift (gamma-style, no hard steps)
            var y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            if (shadowLift > 0f && y < 0.55f) {
                val lifted = y.powSafe(1f - shadowLift * 0.55f)
                val scale = if (y > 1e-4f) lifted / y else 1f
                r = (r * scale).coerceIn(0f, 255f)
                g = (g * scale).coerceIn(0f, 255f)
                b = (b * scale).coerceIn(0f, 255f)
                y = lifted
            }
            if (highlightComp > 0f && y > 0.75f) {
                val t = (y - 0.75f) / 0.25f
                val ny = 0.75f + 0.25f * (t / (1f + highlightComp * t))
                val scale = ny / y
                r = (r * scale).coerceIn(0f, 255f)
                g = (g * scale).coerceIn(0f, 255f)
                b = (b * scale).coerceIn(0f, 255f)
            }

            // Midtone contrast only — skip very dark pixels to prevent banding
            if (y > 0.12f) {
                r = softContrast(r / 255f, midContrast) * 255f
                g = softContrast(g / 255f, midContrast) * 255f
                b = softContrast(b / 255f, midContrast) * 255f
            }

            val (vr, vg, vb) = vibrance(r, g, b, vibranceAmt)
            // Ordered dither in deep shadows reduces 8-bit banding
            val dy = (0.2126f * vr + 0.7152f * vg + 0.0722f * vb)
            val dither = if (dy < 50f) shadowDither(i, w) * 1.2f else 0f

            out[i] = (a shl 24) or
                ((vr + dither).toInt().coerceIn(0, 255) shl 16) or
                ((vg + dither).toInt().coerceIn(0, 255) shl 8) or
                (vb + dither).toInt().coerceIn(0, 255)
        }

        // NO local contrast on dark scenes — main cause of "cracked" blocks
        if (!indoor && s > 0.35f) {
            localContrastLightOnly(out, w, h, amount = 0.12f * s)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun Float.powSafe(exp: Float): Float {
        if (this <= 0f) return 0f
        return Math.pow(this.toDouble(), exp.toDouble()).toFloat()
    }

    private fun softContrast(x: Float, amount: Float): Float {
        if (amount < 0.01f) return x.coerceIn(0f, 1f)
        val c = x.coerceIn(0f, 1f)
        val mid = 0.5f + (c - 0.5f) * (1f + amount)
        return lerp(c, mid.coerceIn(0f, 1f), 0.55f)
    }

    private fun vibrance(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        if (amount < 0.01f) return Triple(r, g, b)
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc > 1f) (maxc - minc) / maxc else 0f
        val boost = amount * (1f - sat)
        val avg = (r + g + b) / 3f
        return Triple(
            (r + (r - avg) * boost).coerceIn(0f, 255f),
            (g + (g - avg) * boost).coerceIn(0f, 255f),
            (b + (b - avg) * boost).coerceIn(0f, 255f)
        )
    }

    /** Bayer-like 2x2 dither ±1 for shadow banding */
    private fun shadowDither(index: Int, w: Int): Float {
        val x = index % w
        val y = index / w
        return when ((x and 1) or ((y and 1) shl 1)) {
            0 -> -0.5f
            1 -> 0.25f
            2 -> 0.5f
            else -> -0.25f
        }
    }

    private fun localContrastLightOnly(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.02f) return
        val copy = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val p = copy[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yL = (r * 3 + g * 6 + b) / 10
                if (yL < 60) continue // never touch dark regions
                var lr = 0
                var lg = 0
                var lb = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val q = copy[(y + dy) * w + (x + dx)]
                    lr += (q shr 16) and 0xFF
                    lg += (q shr 8) and 0xFF
                    lb += q and 0xFF
                }
                lr /= 9; lg /= 9; lb /= 9
                val a = (p ushr 24) and 0xFF
                px[i] = (a shl 24) or
                    ((r + (r - lr) * amount).toInt().coerceIn(0, 255) shl 16) or
                    ((g + (g - lg) * amount).toInt().coerceIn(0, 255) shl 8) or
                    (b + (b - lb) * amount).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

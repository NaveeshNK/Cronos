package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Pipeline v5 — visible "finished" color (iPhone-direction) without
 * cracked/posterized shadows from v3.
 *
 * Stronger midtone color + clarity; shadows use smooth lift only.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.68f): Bitmap {
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
            sumR += r; sumG += g; sumB += b
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
        val p3 = percentile(0.03f)
        val p97 = percentile(0.97f)

        val warmCast = avgR > avgB + 8f
        val indoor = avgY < 130f || warmCast

        // Moderate levels — enough pop, not extreme
        val black = if (indoor) p3 * 0.35f else p3 * 0.2f
        val white = 255f - (255 - p97) * 0.2f
        val range = (white - black).coerceAtLeast(100f)

        // Visible but controlled WB
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = (avg / max(avgR, 1f)).coerceIn(0.92f, 1.08f)
        var gainG = (avg / max(avgG, 1f)).coerceIn(0.92f, 1.08f)
        var gainB = (avg / max(avgB, 1f)).coerceIn(0.92f, 1.08f)
        if (indoor && warmCast) {
            gainR *= 0.97f
            gainB *= 1.04f
        }
        val wbT = s * 0.85f
        val gR = lerp(1f, gainR, wbT)
        val gG = lerp(1f, gainG, wbT)
        val gB = lerp(1f, gainB, wbT)

        // Color pop — main fix vs flat v4
        val vibranceAmt = if (indoor) 0.28f * s else 0.34f * s
        val saturationAmt = if (indoor) 0.10f * s else 0.14f * s
        val midContrast = if (indoor) 0.14f * s else 0.22f * s
        val shadowLift = if (indoor) 0.14f * s else 0.05f * s
        val highlightComp = 0.14f * s
        val exposure = if (indoor && avgY < 105f) 0.04f * s else 0.01f * s

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()

            r = ((r - black) / range * 255f).coerceIn(0f, 255f)
            g = ((g - black) / range * 255f).coerceIn(0f, 255f)
            b = ((b - black) / range * 255f).coerceIn(0f, 255f)

            r *= gR; g *= gG; b *= gB

            if (exposure > 0f) {
                val e = 1f + exposure
                r = (r * e).coerceIn(0f, 255f)
                g = (g * e).coerceIn(0f, 255f)
                b = (b * e).coerceIn(0f, 255f)
            }

            // Smooth shadow/highlight on luma
            var y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            if (shadowLift > 0f && y < 0.5f) {
                val lifted = smoothPow(y, 1f - shadowLift * 0.5f)
                if (y > 1e-4f) {
                    val scale = lifted / y
                    r = (r * scale).coerceIn(0f, 255f)
                    g = (g * scale).coerceIn(0f, 255f)
                    b = (b * scale).coerceIn(0f, 255f)
                    y = lifted
                }
            }
            if (highlightComp > 0f && y > 0.72f) {
                val t = (y - 0.72f) / 0.28f
                val ny = 0.72f + 0.28f * (t / (1f + highlightComp * t))
                val scale = ny / y.coerceAtLeast(1e-4f)
                r = (r * scale).coerceIn(0f, 255f)
                g = (g * scale).coerceIn(0f, 255f)
                b = (b * scale).coerceIn(0f, 255f)
                y = ny
            }

            // Mid contrast — skip deepest shadows to avoid cracks
            if (y > 0.08f) {
                r = softContrast(r / 255f, midContrast) * 255f
                g = softContrast(g / 255f, midContrast) * 255f
                b = softContrast(b / 255f, midContrast) * 255f
            }

            // Vibrance + light saturation (color you can see)
            var (vr, vg, vb) = vibrance(r, g, b, vibranceAmt)
            val sat = saturation(vr, vg, vb, saturationAmt)
            vr = sat.first; vg = sat.second; vb = sat.third

            // Tiny dither only in very dark (anti-band), not visible texture
            val dy = 0.2126f * vr + 0.7152f * vg + 0.0722f * vb
            val dither = if (dy < 28f) shadowDither(i, w) * 0.8f else 0f

            out[i] = (a shl 24) or
                ((vr + dither).toInt().coerceIn(0, 255) shl 16) or
                ((vg + dither).toInt().coerceIn(0, 255) shl 8) or
                (vb + dither).toInt().coerceIn(0, 255)
        }

        // Mild local contrast only on mid/bright — clarity without cracking darks
        localContrastSafe(out, w, h, amount = 0.16f * s, minLuma = 55)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun smoothPow(x: Float, exp: Float): Float {
        if (x <= 0f) return 0f
        return Math.pow(x.toDouble(), exp.toDouble()).toFloat()
    }

    private fun softContrast(x: Float, amount: Float): Float {
        if (amount < 0.01f) return x.coerceIn(0f, 1f)
        val c = x.coerceIn(0f, 1f)
        val mid = 0.5f + (c - 0.5f) * (1f + amount)
        return lerp(c, mid.coerceIn(0f, 1f), 0.75f)
    }

    private fun vibrance(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        if (amount < 0.01f) return Triple(r, g, b)
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc > 1f) (maxc - minc) / maxc else 0f
        val boost = amount * (1f - sat * 0.85f)
        val avg = (r + g + b) / 3f
        return Triple(
            (r + (r - avg) * boost).coerceIn(0f, 255f),
            (g + (g - avg) * boost).coerceIn(0f, 255f),
            (b + (b - avg) * boost).coerceIn(0f, 255f)
        )
    }

    private fun saturation(r: Float, g: Float, b: Float, amount: Float): Triple<Float, Float, Float> {
        if (amount < 0.01f) return Triple(r, g, b)
        val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return Triple(
            (gray + (r - gray) * (1f + amount)).coerceIn(0f, 255f),
            (gray + (g - gray) * (1f + amount)).coerceIn(0f, 255f),
            (gray + (b - gray) * (1f + amount)).coerceIn(0f, 255f)
        )
    }

    private fun shadowDither(index: Int, w: Int): Float {
        val x = index % w
        val y = index / w
        return when ((x and 1) or ((y and 1) shl 1)) {
            0 -> -0.4f
            1 -> 0.2f
            2 -> 0.4f
            else -> -0.2f
        }
    }

    private fun localContrastSafe(px: IntArray, w: Int, h: Int, amount: Float, minLuma: Int) {
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
                if (yL < minLuma) continue
                var lr = 0; var lg = 0; var lb = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val q = copy[(y + dy) * w + (x + dx)]
                    lr += (q shr 16) and 0xFF
                    lg += (q shr 8) and 0xFF
                    lb += q and 0xFF
                }
                lr /= 9; lg /= 9; lb /= 9
                val a = (p ushr 24) and 0xFF
                // Soften amount near threshold
                val fade = ((yL - minLuma) / 40f).coerceIn(0f, 1f)
                val amt = amount * fade
                px[i] = (a shl 24) or
                    ((r + (r - lr) * amt).toInt().coerceIn(0, 255) shl 16) or
                    ((g + (g - lg) * amt).toInt().coerceIn(0, 255) shl 8) or
                    (b + (b - lb) * amt).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

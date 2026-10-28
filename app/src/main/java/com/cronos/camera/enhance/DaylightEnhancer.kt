package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cronos Color Engine Q1
 * Research blend: Pixel merge discipline + iPhone warmth/chroma + selective hue pop.
 * Rules: never darken overall; protect near-neutrals; midtone clarity only.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.82f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumY0 = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val hist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 3 + g * 6 + b) / 10
            sumY0 += 0.299 * r + 0.587 * g + 0.114 * b
            sumR += r; sumG += g; sumB += b
            hist[y.coerceIn(0, 255)]++
        }
        val meanY0 = (sumY0 / n).toFloat()
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val warmCast = avgR > avgB + 6f
        val indoor = meanY0 < 138f || warmCast

        // Soft black/white points for gentle DR without crushing
        fun pct(p: Float): Int {
            val t = (n * p).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= t) return i
            }
            return 255
        }
        val p2 = pct(0.02f)
        val p98 = pct(0.98f)
        val black = if (indoor) p2 * 0.25f else p2 * 0.15f
        val white = 255f - (255 - p98) * 0.15f
        val range = (white - black).coerceAtLeast(120f)

        var gainR = 1f
        var gainG = 1f
        var gainB = 1f
        if (indoor && warmCast) {
            gainR = 1.012f
            gainB = 0.994f
        } else if (!indoor) {
            val avg = (avgR + avgG + avgB) / 3f
            gainR = lerp(1f, (avg / max(avgR, 1f)).coerceIn(0.97f, 1.03f), 0.35f * s)
            gainG = lerp(1f, (avg / max(avgG, 1f)).coerceIn(0.97f, 1.03f), 0.35f * s)
            gainB = lerp(1f, (avg / max(avgB, 1f)).coerceIn(0.97f, 1.03f), 0.35f * s)
            gainR *= 1.008f
        }

        val chromaBoost = if (indoor) 0.80f * s else 1.08f * s
        val midClarity = if (indoor) 0.10f * s else 0.14f * s

        val out = IntArray(n)
        var sumY1 = 0.0

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()

            // Gentle levels
            r = ((r - black) / range * 255f).coerceIn(0f, 255f)
            g = ((g - black) / range * 255f).coerceIn(0f, 255f)
            b = ((b - black) / range * 255f).coerceIn(0f, 255f)

            r = (r * gainR).coerceIn(0f, 255f)
            g = (g * gainG).coerceIn(0f, 255f)
            b = (b * gainB).coerceIn(0f, 255f)

            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            var cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

            // Shadow lift only (never crush)
            if (indoor && y < 88f) {
                val t = 1f - (y / 88f)
                y = (y + 11f * s * t).coerceIn(0f, 255f)
            }
            // Highlight soft roll
            if (y > 220f) {
                val t = (y - 220f) / 35f
                y = 220f + 35f * (t / (1f + 0.35f * s * t))
            }

            // Midtone clarity on luma (S-curve only above deep shadows)
            if (y > 40f && midClarity > 0.02f) {
                val yn = (y / 255f).coerceIn(0f, 1f)
                val shaped = 0.5f + (yn - 0.5f) * (1f + midClarity)
                val mix = ((y - 40f) / 40f).coerceIn(0f, 1f)
                y = lerp(y, shaped.coerceIn(0f, 1f) * 255f, mix * 0.7f)
            }

            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)
            val hueBoost = hueBoost(atan2(dCr, dCb))
            val normC = (chroma / 78f).coerceIn(0f, 1f)
            val vibrance = 1f + chromaBoost * hueBoost * (1f - normC * 0.42f)
            val protect = when {
                chroma < 5f -> 0.18f
                chroma < 12f -> 0.50f
                else -> 1f
            }
            val factor = 1f + (vibrance - 1f) * protect
            cb = 128f + dCb * factor
            cr = 128f + dCr * factor

            r = (y + 1.402f * (cr - 128f)).coerceIn(0f, 255f)
            g = (y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)).coerceIn(0f, 255f)
            b = (y + 1.772f * (cb - 128f)).coerceIn(0f, 255f)

            sumY1 += 0.299 * r + 0.587 * g + 0.114 * b
            out[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }

        // Preserve mean brightness
        val meanY1 = (sumY1 / n).toFloat().coerceAtLeast(1f)
        val target = if (indoor) meanY0 * (1f + 0.025f * s) else meanY0
        val scale = (target / meanY1).coerceIn(0.96f, 1.18f)
        if (kotlin.math.abs(scale - 1f) > 0.008f) {
            for (i in out.indices) {
                val p = out[i]
                val a = (p ushr 24) and 0xFF
                val r = (((p shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun hueBoost(hue: Float): Float {
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return when {
            deg > 20f && deg < 90f -> 1.36f   // reds / wood
            deg >= 90f && deg < 150f -> 1.15f
            deg >= -180f && deg < -120f -> 1.40f // blues
            deg >= -120f && deg < -40f -> 1.22f  // cyan/sky
            deg >= -40f && deg <= 20f -> 1.10f   // greens/yellow
            else -> 1.18f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

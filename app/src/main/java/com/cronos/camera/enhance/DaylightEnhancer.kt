package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * v18 — local tone: lift shadows/corners, soft highlight roll-off, neutral WB.
 */
object DaylightEnhancer {

    fun enhance(
        source: Bitmap,
        strength: Float = 0.85f,
        singleFrame: Boolean = false
    ): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        chromaDenoiseSmooth(pixels, w, h)
        if (singleFrame) {
            lumaDenoiseSmooth(pixels, w, h, strength = 0.28f * s)
        } else {
            lumaDenoiseSmooth(pixels, w, h, strength = 0.12f * s)
        }

        var sumY0 = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumY0 += 0.299 * r + 0.587 * g + 0.114 * b
            sumR += r; sumG += g; sumB += b
        }
        val meanY0 = (sumY0 / n).toFloat()
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val avg = (avgR + avgG + avgB) / 3f

        var gainR = (avg / max(avgR, 1f)).coerceIn(0.92f, 1.08f)
        var gainG = (avg / max(avgG, 1f)).coerceIn(0.94f, 1.06f)
        var gainB = (avg / max(avgB, 1f)).coerceIn(0.92f, 1.08f)
        gainR = lerp(1f, gainR, 0.55f * s)
        gainG = lerp(1f, gainG, 0.55f * s)
        gainB = lerp(1f, gainB, 0.55f * s)

        val chromaBoost = 1.05f * s
        val out = IntArray(n)
        var sumY1 = 0.0

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF) * gainR
            var g = ((p shr 8) and 0xFF) * gainG
            var b = (p and 0xFF) * gainB
            r = r.coerceIn(0f, 255f)
            g = g.coerceIn(0f, 255f)
            b = b.coerceIn(0f, 255f)

            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            var cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

            // --- Local tone (per-pixel, luma curve) ---
            y = localToneY(y, s)

            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)
            val hb = hueBoost(atan2(dCr, dCb))
            val normC = (chroma / 78f).coerceIn(0f, 1f)
            val vibrance = 1f + chromaBoost * hb * (1f - normC * 0.40f)
            val protect = when {
                chroma < 6f -> 0.15f
                chroma < 14f -> 0.45f
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

        // Mild global brightness match (local tone already opens shadows)
        val meanY1 = (sumY1 / n).toFloat().coerceAtLeast(1f)
        val target = meanY0 * (1f + 0.06f * s)
        val scale = (target / meanY1).coerceIn(0.98f, 1.18f)
        if (abs(scale - 1f) > 0.01f) {
            for (i in out.indices) {
                val p = out[i]
                val a = (p ushr 24) and 0xFF
                val r = (((p shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        edgeAwareClarity(out, w, h, amount = 0.18f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Local tone curve on luma:
     * - Strong lift in deep shadows (wardrobe corners)
     * - Mild mid lift
     * - Soft highlight compression (ceiling lights)
     */
    private fun localToneY(y: Float, s: Float): Float {
        val yn = (y / 255f).coerceIn(0f, 1f)
        // Shadow lift: more gain when dark
        val shadowGain = 1f + (0.55f * s) * Math.pow((1f - yn).toDouble(), 1.4).toFloat()
        // Mid contrast slight S
        val mid = yn + 0.04f * s * (yn - 0.5f) * 4f * yn * (1f - yn)
        var out = (mid * shadowGain).coerceIn(0f, 1.15f)
        // Highlight roll-off
        if (out > 0.82f) {
            val t = (out - 0.82f) / 0.18f
            out = 0.82f + 0.18f * (t / (1f + 0.7f * s * t))
        }
        return (out * 255f).coerceIn(0f, 255f)
    }

    private fun lumaDenoiseSmooth(px: IntArray, w: Int, h: Int, strength: Float) {
        if (strength < 0.05f) return
        val copy = px.copyOf()
        val blend = strength.coerceIn(0f, 0.85f)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(copy[i])
                val edge = abs(l - luma(copy[i - 1])) + abs(l - luma(copy[i + 1])) +
                    abs(l - luma(copy[i - w])) + abs(l - luma(copy[i + w]))
                if (edge > 16) continue
                var sum = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += luma(copy[(y + dy) * w + (x + dx)])
                    }
                }
                val blur = sum / 9
                val nl = (l * (1f - blend) + blur * blend).toInt().coerceIn(0, 255)
                val p = copy[i]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                val y0 = (r0 * 3 + g0 * 6 + b0) / 10
                if (y0 <= 0) continue
                val sc = nl.toFloat() / y0
                val r = (r0 * sc).toInt().coerceIn(0, 255)
                val g = (g0 * sc).toInt().coerceIn(0, 255)
                val b = (b0 * sc).toInt().coerceIn(0, 255)
                val a = (p ushr 24) and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun edgeAwareClarity(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.02f) return
        val copy = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(copy[i])
                val lL = luma(copy[i - 1])
                val lR = luma(copy[i + 1])
                val lU = luma(copy[i - w])
                val lD = luma(copy[i + w])
                val edge = abs(l - lL) + abs(l - lR) + abs(l - lU) + abs(l - lD)
                if (edge < 18) continue
                val blur = (lL + lR + lU + lD + l) / 5
                val detail = l - blur
                val nl = (l + detail * amount).toInt().coerceIn(0, 255)
                val p = copy[i]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                val y0 = (r0 * 3 + g0 * 6 + b0) / 10
                if (y0 <= 0) continue
                val sc = nl.toFloat() / y0
                val r = (r0 * sc).toInt().coerceIn(0, 255)
                val g = (g0 * sc).toInt().coerceIn(0, 255)
                val b = (b0 * sc).toInt().coerceIn(0, 255)
                val a = (p ushr 24) and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun chromaDenoiseSmooth(px: IntArray, w: Int, h: Int) {
        val copy = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(copy[i])
                val edge = abs(l - luma(copy[i - 1])) + abs(l - luma(copy[i + 1])) +
                    abs(l - luma(copy[i - w])) + abs(l - luma(copy[i + w]))
                if (edge > 28) continue
                var cbSum = 0f
                var crSum = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = copy[(y + dy) * w + (x + dx)]
                        val r = ((p shr 16) and 0xFF).toFloat()
                        val g = ((p shr 8) and 0xFF).toFloat()
                        val b = (p and 0xFF).toFloat()
                        cbSum += 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                        crSum += 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
                    }
                }
                val cb = cbSum / 9f
                val cr = crSum / 9f
                val p0 = copy[i]
                val r0 = ((p0 shr 16) and 0xFF).toFloat()
                val g0 = ((p0 shr 8) and 0xFF).toFloat()
                val b0 = (p0 and 0xFF).toFloat()
                val y0 = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
                val cb0 = 128f - 0.168736f * r0 - 0.331264f * g0 + 0.5f * b0
                val cr0 = 128f + 0.5f * r0 - 0.418688f * g0 - 0.081312f * b0
                val cbM = cb0 * 0.4f + cb * 0.6f
                val crM = cr0 * 0.4f + cr * 0.6f
                val r = (y0 + 1.402f * (crM - 128f)).coerceIn(0f, 255f)
                val g = (y0 - 0.344136f * (cbM - 128f) - 0.714136f * (crM - 128f)).coerceIn(0f, 255f)
                val b = (y0 + 1.772f * (cbM - 128f)).coerceIn(0f, 255f)
                val a = (p0 ushr 24) and 0xFF
                px[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }

    private fun hueBoost(hue: Float): Float {
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return when {
            deg > 20f && deg < 90f -> 1.25f
            deg >= 90f && deg < 150f -> 1.12f
            deg >= -180f && deg < -120f -> 1.35f
            deg >= -120f && deg < -40f -> 1.22f
            deg >= -40f && deg <= 20f -> 1.15f
            else -> 1.12f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

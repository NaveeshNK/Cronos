package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P2 rendering: denoise first, calibrated-ish WB, restrained tone + sharpen.
 */
object DaylightEnhancer {

    fun enhance(
        source: Bitmap,
        strength: Float = 0.80f,
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

        // Denoise before color (P2 priority)
        edgeAwareBilateralLite(pixels, w, h, spatial = 1, singleFrame = singleFrame, s = s)

        var sumY0 = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var wpR = 0.0
        var wpG = 0.0
        var wpB = 0.0
        var wpN = 0
        val lumaTmp = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            lumaTmp[i] = y
            sumY0 += y
            sumR += r; sumG += g; sumB += b
        }
        val meanY0 = (sumY0 / n).toFloat()
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val thresh = meanY0 * 1.2f + 25f
        for (i in 0 until n) {
            val y = lumaTmp[i]
            if (y < thresh || y > 248f) continue
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx - mn > 22f) continue
            wpR += r; wpG += g; wpB += b; wpN++
        }

        val avg = (avgR + avgG + avgB) / 3f
        var gainR: Float
        var gainG: Float
        var gainB: Float
        if (wpN > 60) {
            val wr = (wpR / wpN).toFloat()
            val wg = (wpG / wpN).toFloat()
            val wb = (wpB / wpN).toFloat()
            val wavg = (wr + wg + wb) / 3f
            gainR = (wavg / max(wr, 1f)).coerceIn(0.93f, 1.07f)
            gainG = (wavg / max(wg, 1f)).coerceIn(0.94f, 1.06f)
            gainB = (wavg / max(wb, 1f)).coerceIn(0.93f, 1.07f)
        } else {
            gainR = (avg / max(avgR, 1f)).coerceIn(0.94f, 1.06f)
            gainG = (avg / max(avgG, 1f)).coerceIn(0.95f, 1.05f)
            gainB = (avg / max(avgB, 1f)).coerceIn(0.94f, 1.06f)
        }
        gainR = lerp(1f, gainR, 0.50f * s)
        gainG = lerp(1f, gainG, 0.50f * s)
        gainB = lerp(1f, gainB, 0.50f * s)

        val chromaBoost = 0.88f * s
        val out = IntArray(n)
        var sumY1 = 0.0

        for (i in 0 until n) {
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

            y = localToneY(y, s)

            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)
            val hue = atan2(dCr, dCb)
            val skin = isSkinHue(hue, chroma, y)
            val hb = if (skin) 0.45f else hueBoost(hue)
            val normC = (chroma / 78f).coerceIn(0f, 1f)
            val vibrance = 1f + chromaBoost * hb * (1f - normC * 0.45f)
            val protect = when {
                skin -> 0.20f
                chroma < 6f -> 0.12f
                chroma < 14f -> 0.40f
                else -> 0.92f
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

        val meanY1 = (sumY1 / n).toFloat().coerceAtLeast(1f)
        val target = meanY0 * (1f + 0.04f * s)
        val scale = (target / meanY1).coerceIn(0.98f, 1.12f)
        if (abs(scale - 1f) > 0.01f) {
            for (i in 0 until n) {
                val p = out[i]
                val a = (p ushr 24) and 0xFF
                val r = (((p shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        detailConfidenceSharpen(out, w, h, amount = 0.16f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Edge-aware smooth: stronger on flat, weak on edges (texture keep). */
    private fun edgeAwareBilateralLite(
        px: IntArray, w: Int, h: Int, spatial: Int, singleFrame: Boolean, s: Float
    ) {
        val copy = px.copyOf()
        val edgeLimit = if (singleFrame) 14 else 18
        val blendFlat = if (singleFrame) 0.40f * s else 0.22f * s
        for (y in spatial until h - spatial) {
            for (x in spatial until w - spatial) {
                val i = y * w + x
                val l = luma(copy[i])
                val edge = abs(l - luma(copy[i - 1])) + abs(l - luma(copy[i + 1])) +
                    abs(l - luma(copy[i - w])) + abs(l - luma(copy[i + w]))
                if (edge > edgeLimit) continue
                var sr = 0
                var sg = 0
                var sb = 0
                var c = 0
                for (dy in -spatial..spatial) {
                    for (dx in -spatial..spatial) {
                        val p = copy[(y + dy) * w + (x + dx)]
                        val ll = luma(p)
                        if (abs(ll - l) > 18) continue
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        c++
                    }
                }
                if (c < 3) continue
                val br = sr / c
                val bg = sg / c
                val bb = sb / c
                val p0 = copy[i]
                val r0 = (p0 shr 16) and 0xFF
                val g0 = (p0 shr 8) and 0xFF
                val b0 = p0 and 0xFF
                val t = blendFlat
                val r = (r0 * (1 - t) + br * t).toInt().coerceIn(0, 255)
                val g = (g0 * (1 - t) + bg * t).toInt().coerceIn(0, 255)
                val b = (b0 * (1 - t) + bb * t).toInt().coerceIn(0, 255)
                val a = (p0 ushr 24) and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun isSkinHue(hue: Float, chroma: Float, y: Float): Boolean {
        if (y < 40f || y > 230f || chroma < 8f || chroma > 55f) return false
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return deg >= -25f && deg <= 55f
    }

    private fun localToneY(y: Float, s: Float): Float {
        val yn = (y / 255f).coerceIn(0f, 1f)
        val shadowGain = 1f + (0.40f * s) * Math.pow((1f - yn).toDouble(), 1.5).toFloat()
        val mid = yn + 0.03f * s * (yn - 0.5f) * 4f * yn * (1f - yn)
        var out = (mid * shadowGain).coerceIn(0f, 1.12f)
        if (out > 0.85f) {
            val t = (out - 0.85f) / 0.15f
            out = 0.85f + 0.15f * (t / (1f + 0.8f * s * t))
        }
        return (out * 255f).coerceIn(0f, 255f)
    }

    private fun detailConfidenceSharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.02f) return
        val copy = px.copyOf()
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val l = luma(copy[i])
                val lL = luma(copy[i - 1])
                val lR = luma(copy[i + 1])
                val lU = luma(copy[i - w])
                val lD = luma(copy[i + w])
                val edge = abs(l - lL) + abs(l - lR) + abs(l - lU) + abs(l - lD)
                if (edge < 22) continue
                var varSum = 0
                for (dy in -1..1) for (dx in -1..1) {
                    varSum += abs(luma(copy[(y + dy) * w + (x + dx)]) - l)
                }
                val noiseProxy = varSum / 9
                if (noiseProxy > edge * 1.15f && edge < 45) continue
                val confidence = ((edge - 22f) / 50f).coerceIn(0f, 1f) *
                    (1f - ((noiseProxy - 10).coerceAtLeast(0) / 40f).coerceIn(0f, 1f))
                if (confidence < 0.2f) continue
                val blur = (lL + lR + lU + lD + l) / 5
                val detail = l - blur
                val nl = (l + detail * amount * confidence).toInt().coerceIn(0, 255)
                val p = copy[i]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                val y0 = (r0 * 3 + g0 * 6 + b0) / 10
                if (y0 <= 0) continue
                val sc = nl.toFloat() / y0
                val a = (p ushr 24) and 0xFF
                px[i] = (a shl 24) or
                    ((r0 * sc).toInt().coerceIn(0, 255) shl 16) or
                    ((g0 * sc).toInt().coerceIn(0, 255) shl 8) or
                    (b0 * sc).toInt().coerceIn(0, 255)
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
            deg > 20f && deg < 90f -> 1.18f
            deg >= 90f && deg < 150f -> 1.08f
            deg >= -180f && deg < -120f -> 1.25f
            deg >= -120f && deg < -40f -> 1.15f
            else -> 1.08f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

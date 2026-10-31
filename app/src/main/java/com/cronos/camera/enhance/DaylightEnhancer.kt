package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * v24 photographic render: denoise → WB → filmic tone → natural chroma → safe detail.
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

        edgeAwareBilateralLite(pixels, w, h, singleFrame, s)

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
        val thresh = meanY0 * 1.15f + 30f
        for (i in 0 until n) {
            val y = lumaTmp[i]
            if (y < thresh || y > 250f) continue
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (max(r, max(g, b)) - min(r, min(g, b)) > 20f) continue
            wpR += r; wpG += g; wpB += b; wpN++
        }

        val avg = (avgR + avgG + avgB) / 3f
        var gainR: Float
        var gainG: Float
        var gainB: Float
        if (wpN > 50) {
            val wr = (wpR / wpN).toFloat()
            val wg = (wpG / wpN).toFloat()
            val wb = (wpB / wpN).toFloat()
            val wavg = (wr + wg + wb) / 3f
            gainR = (wavg / max(wr, 1f)).coerceIn(0.94f, 1.06f)
            gainG = (wavg / max(wg, 1f)).coerceIn(0.95f, 1.05f)
            gainB = (wavg / max(wb, 1f)).coerceIn(0.94f, 1.06f)
        } else {
            gainR = (avg / max(avgR, 1f)).coerceIn(0.95f, 1.05f)
            gainG = (avg / max(avgG, 1f)).coerceIn(0.96f, 1.04f)
            gainB = (avg / max(avgB, 1f)).coerceIn(0.95f, 1.05f)
        }
        // Slight warm daylight bias without yellow cast
        gainR = lerp(1f, gainR * 1.01f, 0.55f * s)
        gainG = lerp(1f, gainG, 0.55f * s)
        gainB = lerp(1f, gainB * 0.995f, 0.55f * s)

        val out = IntArray(n)
        var sumY1 = 0.0
        val chromaAmt = 0.72f * s

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

            y = filmicTone(y, s)

            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)
            val hue = atan2(dCr, dCb)
            val skin = isSkinHue(hue, chroma, y)
            val hb = if (skin) 0.35f else selectiveChroma(hue)
            val normC = (chroma / 70f).coerceIn(0f, 1f)
            // Vibrance: boost weak colors more than already saturated
            val vib = 1f + chromaAmt * hb * (1f - normC * 0.55f)
            val protect = when {
                skin -> 0.18f
                chroma < 5f -> 0.10f
                chroma < 12f -> 0.35f
                else -> 0.85f
            }
            val factor = 1f + (vib - 1f) * protect
            cb = 128f + dCb * factor
            cr = 128f + dCr * factor

            r = (y + 1.402f * (cr - 128f)).coerceIn(0f, 255f)
            g = (y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)).coerceIn(0f, 255f)
            b = (y + 1.772f * (cb - 128f)).coerceIn(0f, 255f)

            sumY1 += 0.299 * r + 0.587 * g + 0.114 * b
            out[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }

        val meanY1 = (sumY1 / n).toFloat().coerceAtLeast(1f)
        val target = meanY0 * (1f + 0.05f * s)
        val scale = (target / meanY1).coerceIn(0.97f, 1.14f)
        if (abs(scale - 1f) > 0.008f) {
            for (i in 0 until n) {
                val p = out[i]
                val a = (p ushr 24) and 0xFF
                out[i] = (a shl 24) or
                    ((((p shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255) shl 16) or
                    ((((p shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255) shl 8) or
                    ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
            }
        }

        detailConfidenceSharpen(out, w, h, amount = 0.14f * s)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Soft shoulder highlights, open shadows — filmic-ish. */
    private fun filmicTone(y: Float, s: Float): Float {
        val yn = (y / 255f).coerceIn(0f, 1f)
        // Shadow lift
        val lifted = yn + (0.08f * s) * (1f - yn).pow(2.2f)
        // Mild mid contrast
        val mid = lifted + 0.04f * s * (lifted - 0.5f) * 4f * lifted * (1f - lifted)
        // Highlight shoulder
        var out = mid
        if (out > 0.78f) {
            val t = ((out - 0.78f) / 0.22f).coerceIn(0f, 1f)
            out = 0.78f + 0.22f * (t / (1f + 1.1f * s * t))
        }
        return (out * 255f).coerceIn(0f, 255f)
    }

    private fun selectiveChroma(hue: Float): Float {
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return when {
            deg > 15f && deg < 85f -> 1.06f   // greens — careful
            deg >= 85f && deg < 150f -> 1.04f
            deg >= -180f && deg < -100f -> 1.12f // blues
            deg >= -100f && deg < -20f -> 1.08f
            else -> 0.95f // reds/pinks — avoid cotton-candy
        }
    }

    private fun edgeAwareBilateralLite(px: IntArray, w: Int, h: Int, singleFrame: Boolean, s: Float) {
        val copy = px.copyOf()
        val edgeLimit = if (singleFrame) 13 else 17
        val blendFlat = if (singleFrame) 0.38f * s else 0.20f * s
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(copy[i])
                val edge = abs(l - luma(copy[i - 1])) + abs(l - luma(copy[i + 1])) +
                    abs(l - luma(copy[i - w])) + abs(l - luma(copy[i + w]))
                if (edge > edgeLimit) continue
                var sr = 0; var sg = 0; var sb = 0; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = copy[(y + dy) * w + (x + dx)]
                    if (abs(luma(p) - l) > 16) continue
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                    c++
                }
                if (c < 3) continue
                val p0 = copy[i]
                val t = blendFlat
                val r = (((p0 shr 16) and 0xFF) * (1 - t) + (sr / c) * t).toInt().coerceIn(0, 255)
                val g = (((p0 shr 8) and 0xFF) * (1 - t) + (sg / c) * t).toInt().coerceIn(0, 255)
                val b = ((p0 and 0xFF) * (1 - t) + (sb / c) * t).toInt().coerceIn(0, 255)
                px[i] = ((p0 ushr 24) shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun isSkinHue(hue: Float, chroma: Float, y: Float): Boolean {
        if (y < 45f || y > 225f || chroma < 8f || chroma > 52f) return false
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return deg >= -28f && deg <= 52f
    }

    private fun detailConfidenceSharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        if (amount < 0.02f) return
        val copy = px.copyOf()
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val l = luma(copy[i])
                val lL = luma(copy[i - 1]); val lR = luma(copy[i + 1])
                val lU = luma(copy[i - w]); val lD = luma(copy[i + w])
                val edge = abs(l - lL) + abs(l - lR) + abs(l - lU) + abs(l - lD)
                if (edge < 24) continue
                var varSum = 0
                for (dy in -1..1) for (dx in -1..1)
                    varSum += abs(luma(copy[(y + dy) * w + (x + dx)]) - l)
                val noiseProxy = varSum / 9
                if (noiseProxy > edge * 1.2f && edge < 48) continue
                val confidence = ((edge - 24f) / 48f).coerceIn(0f, 1f) *
                    (1f - ((noiseProxy - 12).coerceAtLeast(0) / 36f).coerceIn(0f, 1f))
                if (confidence < 0.25f) continue
                val blur = (lL + lR + lU + lD + l) / 5
                val nl = (l + (l - blur) * amount * confidence).toInt().coerceIn(0, 255)
                val p = copy[i]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                val y0 = (r0 * 3 + g0 * 6 + b0) / 10
                if (y0 <= 0) continue
                val sc = nl.toFloat() / y0
                px[i] = ((p ushr 24) shl 24) or
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

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

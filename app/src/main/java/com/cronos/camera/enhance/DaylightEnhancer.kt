package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pipeline v7 — COLOR ONLY, never darken.
 *
 * Bug in v6: S-curve contrast crushed midtones → Enhanced looked darker than Original.
 * Fix: preserve mean luminance; boost chroma only; mild shadow lift if needed.
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.75f): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        val w = source.width
        val h = source.height
        val input = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val n = w * h
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        // Original mean luma — we restore this after color so image cannot go darker overall
        var sumY0 = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumY0 += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val meanY0 = (sumY0 / n).toFloat()

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (p in pixels) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val warmCast = avgR > avgB + 6f
        val indoor = meanY0 < 135f || warmCast

        // Very light WB — keep indoor warmth, do not crush
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = 1f
        var gainG = 1f
        var gainB = 1f
        if (indoor && warmCast) {
            // slight warm lean only
            gainR = 1.015f
            gainB = 0.99f
        } else {
            gainR = lerp(1f, (avg / max(avgR, 1f)).coerceIn(0.97f, 1.03f), 0.4f * s)
            gainG = lerp(1f, (avg / max(avgG, 1f)).coerceIn(0.97f, 1.03f), 0.4f * s)
            gainB = lerp(1f, (avg / max(avgB, 1f)).coerceIn(0.97f, 1.03f), 0.4f * s)
        }

        val chromaBoost = if (indoor) 0.70f * s else 0.80f * s

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

            // YCbCr
            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            var cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

            // Optional gentle shadow LIFT only (never darken)
            if (indoor && y < 90f) {
                val t = 1f - (y / 90f)
                y = (y + 12f * s * t).coerceIn(0f, 255f)
            }

            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)
            val hue = atan2(dCr, dCb)
            val hueBoost = hueDependentBoost(hue)

            // Vibrance: more boost when chroma is weak
            val normC = (chroma / 85f).coerceIn(0f, 1f)
            val vibrance = 1f + chromaBoost * hueBoost * (1f - normC * 0.5f)
            val protect = when {
                chroma < 6f -> 0.25f  // near-gray walls: little change
                chroma < 14f -> 0.65f
                else -> 1f
            }
            val factor = 1f + (vibrance - 1f) * protect

            cb = 128f + dCb * factor
            cr = 128f + dCr * factor

            // Back to RGB — LUMA UNCHANGED except shadow lift above
            r = y + 1.402f * (cr - 128f)
            g = y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)
            b = y + 1.772f * (cb - 128f)

            r = r.coerceIn(0f, 255f)
            g = g.coerceIn(0f, 255f)
            b = b.coerceIn(0f, 255f)

            sumY1 += 0.299 * r + 0.587 * g + 0.114 * b

            out[i] = (a shl 24) or
                (r.toInt() shl 16) or
                (g.toInt() shl 8) or
                b.toInt()
        }

        // Force same average brightness as original (or slightly brighter indoors)
        val meanY1 = (sumY1 / n).toFloat().coerceAtLeast(1f)
        val target = if (indoor) meanY0 * (1f + 0.03f * s) else meanY0
        val scale = (target / meanY1).coerceIn(0.92f, 1.25f)

        if (kotlin.math.abs(scale - 1f) > 0.01f) {
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

    private fun hueDependentBoost(hue: Float): Float {
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        return when {
            deg > 20f && deg < 90f -> 1.32f      // reds / wood
            deg >= 90f && deg < 150f -> 1.15f
            deg >= -180f && deg < -120f -> 1.35f // blues
            deg >= -120f && deg < -40f -> 1.20f
            deg >= -40f && deg <= 20f -> 1.08f   // yellows — mild
            else -> 1.18f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

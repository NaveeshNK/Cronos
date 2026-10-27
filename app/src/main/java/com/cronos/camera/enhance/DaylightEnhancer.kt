package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pipeline v6 — COLOR-FIRST, inspired by measured iPhone rendering traits:
 *
 * Research summary (not a pixel-perfect clone of Apple's closed ISP):
 * - Daylight iPhone sits ~120–128% relative saturation (vivid, controlled)
 * - Indoors iPhone KEEPS warmth (~9–13 pts yellow) rather than scrubbing to gray (Pixel-style)
 * - "Vibrant" style deepens chroma without a global Instagram filter
 * - Tone/color adjusted per region of color space, not one matrix on everything
 * - Luma structure preserved; chroma is where the "pop" lives
 *
 * v6 avoids local-contrast on darks (no cracked shadows).
 */
object DaylightEnhancer {

    fun enhance(source: Bitmap, strength: Float = 0.78f): Bitmap {
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
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r; sumG += g; sumB += b
            sumY += (r * 3 + g * 6 + b) / 10
        }
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val avgY = (sumY / n).toFloat()

        val warmCast = avgR > avgB + 6f
        val indoor = avgY < 135f || warmCast

        // iPhone indoor: KEEP warmth; outdoor: mild neutral + slight warmth
        // Do NOT fully gray-wall the scene (that was killing "color difference")
        val avg = (avgR + avgG + avgB) / 3f
        var gainR = (avg / max(avgR, 1f))
        var gainG = (avg / max(avgG, 1f))
        var gainB = (avg / max(avgB, 1f))
        if (indoor) {
            // Partial neutralize only — retain golden indoor bias
            gainR = lerp(1f, gainR.coerceIn(0.94f, 1.06f), 0.35f)
            gainG = lerp(1f, gainG.coerceIn(0.94f, 1.06f), 0.35f)
            gainB = lerp(1f, gainB.coerceIn(0.94f, 1.06f), 0.35f)
            // Explicit warm lean like measured iPhone indoors
            gainR *= 1.02f
            gainB *= 0.98f
        } else {
            gainR = gainR.coerceIn(0.95f, 1.05f)
            gainG = gainG.coerceIn(0.95f, 1.05f)
            gainB = gainB.coerceIn(0.95f, 1.05f)
            gainR *= 1.01f // mild outdoor warmth
        }
        val wbT = s * 0.7f
        val gR = lerp(1f, gainR, wbT)
        val gG = lerp(1f, gainG, wbT)
        val gB = lerp(1f, gainB, wbT)

        // Chroma intensity: stronger than v5 so Before/After is obvious
        // Maps to ~iPhone "Color" axis toward Vibrant
        val chromaBoost = if (indoor) 0.55f * s else 0.65f * s
        val midTonePunch = if (indoor) 0.12f * s else 0.18f * s

        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p shr 16) and 0xFF) * gR
            var g = ((p shr 8) and 0xFF) * gG
            var b = (p and 0xFF) * gB
            r = r.coerceIn(0f, 255f)
            g = g.coerceIn(0f, 255f)
            b = b.coerceIn(0f, 255f)

            // --- YCbCr: process COLOR on chroma, keep structure on luma ---
            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            var cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

            // Soft S-curve on luma only (tone) — mild so no posterization
            val yn = (y / 255f).coerceIn(0f, 1f)
            val yCurve = softSCurve(yn, midTonePunch)
            y = (yCurve * 255f).coerceIn(0f, 255f)

            // Chroma vector from neutral axis
            val dCb = cb - 128f
            val dCr = cr - 128f
            val chroma = sqrt(dCb * dCb + dCr * dCr)

            // Hue angle for selective boost (iPhone-like per-color behavior)
            val hue = atan2(dCr, dCb) // -PI..PI
            val hueBoost = hueDependentBoost(hue) // 0.85..1.35

            // Vibrance: boost weak chroma more than already vivid
            val normC = (chroma / 90f).coerceIn(0f, 1f)
            val vibranceFactor = 1f + chromaBoost * hueBoost * (1f - normC * 0.55f)

            // Protect near-neutrals slightly less than pure gray so wood/cloth still pop
            val neutralProtect = if (chroma < 8f) 0.35f else if (chroma < 18f) 0.7f else 1f
            val factor = 1f + (vibranceFactor - 1f) * neutralProtect

            cb = 128f + dCb * factor
            cr = 128f + dCr * factor

            // YCbCr -> RGB
            val c = y - 16f * 0f // full range JPEG-style
            r = y + 1.402f * (cr - 128f)
            g = y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)
            b = y + 1.772f * (cb - 128f)

            out[i] = (a shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Hue-dependent multipliers (approx):
     * reds/oranges (wood, doors), blues (fabric/sky), greens — stronger;
     * cyans/magentas moderate; pure yellows milder to avoid sickly walls.
     */
    private fun hueDependentBoost(hue: Float): Float {
        // Map atan2(dCr, dCb) roughly to sectors
        val deg = Math.toDegrees(hue.toDouble()).toFloat()
        // Boost profile
        return when {
            deg > 20f && deg < 90f -> 1.28f   // reds / skin-wood
            deg >= 90f && deg < 150f -> 1.12f // magentas
            deg >= -180f && deg < -120f -> 1.30f // blues
            deg >= -120f && deg < -40f -> 1.18f // cyans
            deg >= -40f && deg <= 20f -> 1.05f // yellow-green (walls caution)
            else -> 1.15f
        }
    }

    private fun softSCurve(x: Float, amount: Float): Float {
        if (amount < 0.01f) return x
        val c = x.coerceIn(0f, 1f)
        // smoothstep-based S
        val s = c * c * (3f - 2f * c)
        val mid = 0.5f + (s - 0.5f) * (1f + amount * 1.4f)
        return lerp(c, mid.coerceIn(0f, 1f), 0.85f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

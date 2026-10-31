package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Light cinematic grade on top of daylight color:
 * filmic contrast, warm highlights / cool shadows, soft vignette.
 * Kept mild so it stays photographic, not a heavy filter.
 */
object CinematicLook {

    fun apply(source: Bitmap, amount: Float = 0.72f): Bitmap {
        val a = amount.coerceIn(0f, 1f)
        if (a < 0.05f) return source

        val w = source.width
        val h = source.height
        val src = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val maxR = sqrt(cx * cx + cy * cy)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                var r = ((p shr 16) and 0xFF) / 255f
                var g = ((p shr 8) and 0xFF) / 255f
                var b = (p and 0xFF) / 255f

                // Luma
                var yL = 0.2126f * r + 0.7152f * g + 0.0722f * b

                // Filmic S-curve (lifted blacks, soft highlights)
                val y2 = filmCurve(yL)
                if (yL > 1e-4f) {
                    val scale = y2 / yL
                    r = (r * scale).coerceIn(0f, 1.2f)
                    g = (g * scale).coerceIn(0f, 1.2f)
                    b = (b * scale).coerceIn(0f, 1.2f)
                    yL = y2
                }

                // Split toning: warm highlights, cool shadows
                val hi = smoothstep(0.45f, 0.92f, yL)
                val sh = 1f - smoothstep(0.08f, 0.50f, yL)
                r += 0.045f * a * hi - 0.02f * a * sh
                g += 0.02f * a * hi + 0.01f * a * sh
                b += -0.025f * a * hi + 0.05f * a * sh

                // Soft vignette
                val dx = (x - cx) / maxR
                val dy = (y - cy) / maxR
                val dist = sqrt(dx * dx + dy * dy)
                val vig = 1f - (smoothstep(0.45f, 1.05f, dist) * 0.22f * a)
                r *= vig
                g *= vig
                b *= vig

                // Mild midtone saturation for "shine" / pop
                val mid = 1f - kotlin.math.abs(yL - 0.5f) * 2f
                val sat = 1f + 0.12f * a * mid.coerceIn(0f, 1f)
                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = gray + (r - gray) * sat
                g = gray + (g - gray) * sat
                b = gray + (b - gray) * sat

                // Blend with original by amount
                val oR = ((p shr 16) and 0xFF) / 255f
                val oG = ((p shr 8) and 0xFF) / 255f
                val oB = (p and 0xFF) / 255f
                val fr = (oR * (1 - a * 0.85f) + r.coerceIn(0f, 1f) * (a * 0.85f + (1 - a) * 0.15f))
                    .coerceIn(0f, 1f)
                // simpler mix
                val t = 0.55f + 0.45f * a
                val outR = (oR * (1 - t) + r.coerceIn(0f, 1f) * t)
                val outG = (oG * (1 - t) + g.coerceIn(0f, 1f) * t)
                val outB = (oB * (1 - t) + b.coerceIn(0f, 1f) * t)

                px[i] = Color.argb(
                    255,
                    (outR * 255).toInt().coerceIn(0, 255),
                    (outG * 255).toInt().coerceIn(0, 255),
                    (outB * 255).toInt().coerceIn(0, 255)
                )
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun filmCurve(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        // Soft S: lift black ~0.02, roll off highlights
        val lifted = t * 0.96f + 0.02f
        return (lifted.pow(0.92f) * (1.05f - 0.08f * lifted)).coerceIn(0f, 1f)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

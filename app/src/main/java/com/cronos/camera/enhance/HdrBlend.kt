package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Simple 2-frame HDR: use darker frame for highlights, brighter for shadows.
 * Frames should already be exposure-bracketed when possible.
 */
object HdrBlend {

    fun blend(normal: Bitmap, dark: Bitmap): Bitmap {
        val w = min(normal.width, dark.width)
        val h = min(normal.height, dark.height)
        val a = if (normal.width == w && normal.height == h) normal
        else Bitmap.createScaledBitmap(normal, w, h, true)
        val b = if (dark.width == w && dark.height == h) dark
        else Bitmap.createScaledBitmap(dark, w, h, true)

        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        for (i in out.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val ra = (ca shr 16) and 0xFF
            val ga = (ca shr 8) and 0xFF
            val ba = ca and 0xFF
            val rb = (cb shr 16) and 0xFF
            val gb = (cb shr 8) and 0xFF
            val bb = cb and 0xFF
            val ya = (ra * 3 + ga * 6 + ba) / 10
            val yb = (rb * 3 + gb * 6 + bb) / 10

            // Weight: shadows prefer brighter frame (a), highlights prefer darker (b)
            val wDark = when {
                ya > 210 -> 0.85f
                ya > 180 -> 0.55f
                ya > 140 -> 0.25f
                else -> 0f
            }
            val wNorm = 1f - wDark
            val r = (ra * wNorm + rb * wDark).toInt().coerceIn(0, 255)
            val g = (ga * wNorm + gb * wDark).toInt().coerceIn(0, 255)
            val bl = (ba * wNorm + bb * wDark).toInt().coerceIn(0, 255)
            // Soft shadow open from brighter if normal is dark
            val r2: Int
            val g2: Int
            val b2: Int
            if (ya < 50 && yb > ya) {
                val t = 0.35f
                r2 = (r * (1 - t) + rb * t).toInt().coerceIn(0, 255)
                g2 = (g * (1 - t) + gb * t).toInt().coerceIn(0, 255)
                b2 = (bl * (1 - t) + bb * t).toInt().coerceIn(0, 255)
            } else {
                r2 = r; g2 = g; b2 = bl
            }
            out[i] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}

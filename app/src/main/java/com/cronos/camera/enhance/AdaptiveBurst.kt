package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object AdaptiveBurst {
    /** Returns recommended merge list: may drop blurry/high-motion frames. */
    fun selectFrames(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size <= 1) return frames
        // Score sharpness
        data class Scored(val bmp: Bitmap, val sharp: Double)
        val scored = frames.map { Scored(it, sharp(it)) }.sortedByDescending { it.sharp }
        val best = scored[0]
        // If top frame is much sharper and others are soft, prefer 1-2 best
        val usable = ArrayList<Bitmap>()
        usable.add(best.bmp)
        for (s in scored.drop(1)) {
            if (s.sharp < best.sharp * 0.55) continue // too soft
            // rough global difference vs best
            val diff = meanAbsDiff(best.bmp, s.bmp)
            if (diff > 28.0) continue // likely motion / different
            usable.add(s.bmp)
            if (usable.size >= 3) break
        }
        return usable
    }

    private fun sharp(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                val l = luma(px[i])
                sum += abs(l - luma(px[i + 1])) + abs(l - luma(px[i + w]))
                x += 4
            }
            y += 4
        }
        return sum
    }

    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val w = min(a.width, b.width)
        val h = min(a.height, b.height)
        val sw = max(32, w / 8)
        val sh = max(32, h / 8)
        val sa = Bitmap.createScaledBitmap(a, sw, sh, true)
        val sb = Bitmap.createScaledBitmap(b, sw, sh, true)
        val pa = IntArray(sw * sh)
        val pb = IntArray(sw * sh)
        sa.getPixels(pa, 0, sw, 0, 0, sw, sh)
        sb.getPixels(pb, 0, sw, 0, 0, sw, sh)
        var sum = 0.0
        for (i in pa.indices) sum += abs(luma(pa[i]) - luma(pb[i]))
        return sum / pa.size
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }
}

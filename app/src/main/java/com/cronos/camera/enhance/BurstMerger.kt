package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Simple multi-frame merge for static / near-static scenes.
 * Median of 3 reduces noise; average used for 2 frames.
 * True optical-flow align comes later if needed.
 */
object BurstMerger {

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val w = frames.minOf { it.width }
        val h = frames.minOf { it.height }
        val n = frames.size
        val arrays = frames.map { src ->
            val bmp = if (src.width == w && src.height == h) src
            else Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            px
        }

        val out = IntArray(w * h)
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)

        for (i in out.indices) {
            for (f in 0 until n) {
                val p = arrays[f][i]
                rs[f] = (p shr 16) and 0xFF
                gs[f] = (p shr 8) and 0xFF
                bs[f] = p and 0xFF
            }
            val r: Int
            val g: Int
            val b: Int
            if (n >= 3) {
                rs.sort(); gs.sort(); bs.sort()
                val mid = n / 2
                r = rs[mid]; g = gs[mid]; b = bs[mid]
            } else {
                r = rs.average().toInt()
                g = gs.average().toInt()
                b = bs.average().toInt()
            }
            // slight detail bias: 70% median + 30% sharpest (max local energy frame skipped — keep median clean)
            out[i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}

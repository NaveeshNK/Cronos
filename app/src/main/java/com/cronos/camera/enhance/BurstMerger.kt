package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v10: 3-frame merge with anti-ghost.
 * Align small shifts; if a frame shifted too much, drop it (reduces blur trails).
 */
object BurstMerger {

    private const val MAX_SHIFT_FULL = 24 // px at full res — beyond this, frame is rejected

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val w = frames.minOf { it.width }
        val h = frames.minOf { it.height }
        val baseBmp = scaleTo(frames[0], w, h)
        val basePx = IntArray(w * h)
        baseBmp.getPixels(basePx, 0, w, 0, 0, w, h)

        val aligned = ArrayList<IntArray>()
        aligned.add(basePx)

        for (i in 1 until frames.size) {
            val bmp = scaleTo(frames[i], w, h)
            val (dx, dy) = estimateShift(baseBmp, bmp)
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            if (dist > MAX_SHIFT_FULL) {
                // too much motion — skip frame to avoid ghost trails
                continue
            }
            aligned.add(shiftPixels(bmp, w, h, dx, dy))
        }

        val n = aligned.size
        if (n == 1) {
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(basePx, 0, w, 0, 0, w, h)
            return result
        }

        val out = IntArray(w * h)
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)
        for (i in out.indices) {
            for (f in 0 until n) {
                val p = aligned[f][i]
                rs[f] = (p shr 16) and 0xFF
                gs[f] = (p shr 8) and 0xFF
                bs[f] = p and 0xFF
            }
            if (n >= 3) {
                rs.sort(); gs.sort(); bs.sort()
                val m = n / 2
                out[i] = (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
            } else {
                // 2 frames: average
                out[i] = (0xFF shl 24) or
                    (((rs[0] + rs[1]) / 2) shl 16) or
                    (((gs[0] + gs[1]) / 2) shl 8) or
                    ((bs[0] + bs[1]) / 2)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        return if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun estimateShift(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val sw = max(32, a.width / 8)
        val sh = max(32, a.height / 8)
        val sa = Bitmap.createScaledBitmap(a, sw, sh, true)
        val sb = Bitmap.createScaledBitmap(b, sw, sh, true)
        val pa = IntArray(sw * sh)
        val pb = IntArray(sw * sh)
        sa.getPixels(pa, 0, sw, 0, 0, sw, sh)
        sb.getPixels(pb, 0, sw, 0, 0, sw, sh)

        val maxShift = 5
        var best = Long.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var sad = 0L
                var y = maxShift
                while (y < sh - maxShift) {
                    var x = maxShift
                    while (x < sw - maxShift) {
                        val ia = y * sw + x
                        val ib = (y + dy) * sw + (x + dx)
                        val ca = pa[ia]
                        val cb = pb[ib]
                        sad += abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
                        sad += abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
                        sad += abs((ca and 0xFF) - (cb and 0xFF))
                        x += 2
                    }
                    y += 2
                }
                if (sad < best) {
                    best = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return Pair(bestDx * (a.width / sw), bestDy * (a.height / sh))
    }

    private fun shiftPixels(src: Bitmap, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        if (dx == 0 && dy == 0) return srcPx
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x - dx).coerceIn(0, w - 1)
                val sy = (y - dy).coerceIn(0, h - 1)
                out[y * w + x] = srcPx[sy * w + sx]
            }
        }
        return out
    }
}

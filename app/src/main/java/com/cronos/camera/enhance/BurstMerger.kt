package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Safe fusion: merge only when frames are nearly identical.
 * Any real motion → sharpest single frame (no ghost / overlay / cracks).
 */
object BurstMerger {

    private const val MAX_SHIFT = 3          // px full-res — very strict
    private const val MAX_MEAN_DIFF = 8.0    // luma mean abs diff at small scale

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val w = frames.minOf { it.width }
        val h = frames.minOf { it.height }
        val scaled = frames.map { scaleTo(it, w, h) }

        var refIdx = 0
        var bestSharp = -1.0
        for (i in scaled.indices) {
            val s = sharpnessScore(scaled[i])
            if (s > bestSharp) {
                bestSharp = s
                refIdx = i
            }
        }
        val refBmp = scaled[refIdx]
        val ref = IntArray(w * h)
        refBmp.getPixels(ref, 0, w, 0, 0, w, h)

        val aligned = ArrayList<IntArray>()
        aligned.add(ref)

        for (i in scaled.indices) {
            if (i == refIdx) continue
            val (dx, dy) = estimateShift(refBmp, scaled[i])
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            if (dist > MAX_SHIFT) {
                // Motion — abort entire merge
                return refBmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            val meanDiff = meanLumaDiff(refBmp, scaled[i], dx, dy)
            if (meanDiff > MAX_MEAN_DIFF) {
                return refBmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            aligned.add(shiftPixels(scaled[i], w, h, dx, dy))
        }

        if (aligned.size < 2) {
            return refBmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Simple average only (no tile seams) — safe when locked
        val n = aligned.size
        val out = IntArray(w * h)
        for (i in out.indices) {
            var r = 0; var g = 0; var b = 0
            for (f in 0 until n) {
                val p = aligned[f][i]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            out[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun meanLumaDiff(a: Bitmap, b: Bitmap, dx: Int, dy: Int): Double {
        val sw = max(32, a.width / 8)
        val sh = max(32, a.height / 8)
        val sa = Bitmap.createScaledBitmap(a, sw, sh, true)
        val sb = Bitmap.createScaledBitmap(b, sw, sh, true)
        val pa = IntArray(sw * sh)
        val pb = IntArray(sw * sh)
        sa.getPixels(pa, 0, sw, 0, 0, sw, sh)
        sb.getPixels(pb, 0, sw, 0, 0, sw, sh)
        val sdx = dx / (a.width / sw).coerceAtLeast(1)
        val sdy = dy / (a.height / sh).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        for (y in 2 until sh - 2) {
            for (x in 2 until sw - 2) {
                val ia = y * sw + x
                val xb = (x + sdx).coerceIn(0, sw - 1)
                val yb = (y + sdy).coerceIn(0, sh - 1)
                sum += abs(luma(pa[ia]) - luma(pb[yb * sw + xb]))
                n++
            }
        }
        return if (n == 0) 999.0 else sum / n
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }

    private fun sharpnessScore(bmp: Bitmap): Double {
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
                x += 3
            }
            y += 3
        }
        return sum
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
        val maxShift = 4
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

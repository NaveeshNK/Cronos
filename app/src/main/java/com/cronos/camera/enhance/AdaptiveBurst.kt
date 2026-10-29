package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * P1 frame selection:
 * - Rank by normalized sharpness
 * - After coarse align estimate, reject frames that cannot register
 * - Prefer frames consistent with sharpest reference
 */
object AdaptiveBurst {

    fun selectFrames(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size <= 1) return frames

        data class Scored(val bmp: Bitmap, val sharp: Double)
        val scored = frames.map { Scored(it, sharpNorm(it)) }.sortedByDescending { it.sharp }
        val best = scored[0]
        val usable = ArrayList<Bitmap>()
        usable.add(best.bmp)

        for (s in scored.drop(1)) {
            if (s.sharp < best.sharp * 0.50) continue
            val (dx, dy) = coarseShift(best.bmp, s.bmp)
            val dist = abs(dx) + abs(dy)
            if (dist > 8) continue // cannot register cheaply
            val diff = meanAbsDiffAligned(best.bmp, s.bmp, dx, dy)
            if (diff > 26.0) continue
            usable.add(s.bmp)
            if (usable.size >= 3) break
        }
        return usable
    }

    private fun sharpNorm(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        var n = 0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                val l = luma(px[i])
                sum += abs(l - luma(px[i + 1])) + abs(l - luma(px[i + w]))
                n++
                x += 4
            }
            y += 4
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun coarseShift(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val sw = max(32, a.width / 10)
        val sh = max(32, a.height / 10)
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
                        sad += abs(luma(pa[ia]) - luma(pb[ib]))
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

    private fun meanAbsDiffAligned(a: Bitmap, b: Bitmap, dx: Int, dy: Int): Double {
        val sw = max(32, min(a.width, b.width) / 8)
        val sh = max(32, min(a.height, b.height) / 8)
        val sa = Bitmap.createScaledBitmap(a, sw, sh, true)
        val sb = Bitmap.createScaledBitmap(b, sw, sh, true)
        val pa = IntArray(sw * sh)
        val pb = IntArray(sw * sh)
        sa.getPixels(pa, 0, sw, 0, 0, sw, sh)
        sb.getPixels(pb, 0, sw, 0, 0, sw, sh)
        val sdx = dx / max(1, a.width / sw)
        val sdy = dy / max(1, a.height / sh)
        var sum = 0.0
        var n = 0
        for (y in 2 until sh - 2) {
            for (x in 2 until sw - 2) {
                val xb = x + sdx
                val yb = y + sdy
                if (xb !in 0 until sw || yb !in 0 until sh) continue
                sum += abs(luma(pa[y * sw + x]) - luma(pb[yb * sw + xb]))
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
}

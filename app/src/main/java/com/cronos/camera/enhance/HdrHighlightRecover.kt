package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v25: use an aligned underexposed frame only to recover clipped highlights.
 * Never replaces the whole image (avoids old unaligned HdrBlend bugs).
 */
object HdrHighlightRecover {

    /**
     * @param base merged / best normal exposure
     * @param shortFrame optional underexposed frame (already same size preferred)
     */
    fun recover(base: Bitmap, shortFrame: Bitmap?): Bitmap {
        if (shortFrame == null) return base
        val w = min(base.width, shortFrame.width)
        val h = min(base.height, shortFrame.height)
        if (w < 32 || h < 32) return base

        val baseB = if (base.width == w && base.height == h) base
        else Bitmap.createScaledBitmap(base, w, h, true)
        val shortB = if (shortFrame.width == w && shortFrame.height == h) shortFrame
        else Bitmap.createScaledBitmap(shortFrame, w, h, true)

        val (dx, dy) = estimateShift(baseB, shortB)
        if (abs(dx) + abs(dy) > 12) return base // don't risk ghosts

        val n = w * h
        val bp = IntArray(n)
        val sp = IntArray(n)
        baseB.getPixels(bp, 0, w, 0, 0, w, h)
        shortB.getPixels(sp, 0, w, 0, 0, w, h)

        val out = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = bp[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val mx = max(r, max(g, b))
                if (mx < 235) {
                    out[i] = p
                    continue
                }
                val sx = x - dx
                val sy = y - dy
                if (sx !in 0 until w || sy !in 0 until h) {
                    out[i] = p
                    continue
                }
                val q = sp[sy * w + sx]
                val sr = (q shr 16) and 0xFF
                val sg = (q shr 8) and 0xFF
                val sb = q and 0xFF
                // only pull short where base is near clip and short still has headroom
                val smx = max(sr, max(sg, sb))
                if (smx > 250) {
                    out[i] = p
                    continue
                }
                val t = ((mx - 235) / 20f).coerceIn(0f, 1f) * 0.85f
                val nr = (r * (1 - t) + sr * t).toInt().coerceIn(0, 255)
                val ng = (g * (1 - t) + sg * t).toInt().coerceIn(0, 255)
                val nb = (b * (1 - t) + sb * t).toInt().coerceIn(0, 255)
                val a = (p ushr 24) and 0xFF
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun estimateShift(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val sw = max(40, a.width / 10)
        val sh = max(40, a.height / 10)
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
                        val ca = pa[y * sw + x]
                        val cb = pb[(y + dy) * sw + (x + dx)]
                        val la = ((ca shr 16) and 0xFF) + ((ca shr 8) and 0xFF) + (ca and 0xFF)
                        val lb = ((cb shr 16) and 0xFF) + ((cb shr 8) and 0xFF) + (cb and 0xFF)
                        sad += abs(la - lb)
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
}

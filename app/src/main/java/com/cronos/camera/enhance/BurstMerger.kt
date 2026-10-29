package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion-aware safe merger (P0/P1):
 * - Sharpest frame as reference
 * - Translation align on pyramid
 * - Abort full merge if global motion too high
 * - Tile-local consistency: disagreeing tiles use reference only
 * - Invalid border mask after shift (no edge clamp repeat)
 */
object BurstMerger {

    private const val MAX_SHIFT_PX = 6
    private const val TILE = 32
    private const val TILE_DIFF_ABORT = 18.0  // mean luma diff in tile → use ref

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) {
            return frames[0].copy(Bitmap.Config.ARGB_8888, false)
        }

        val w = frames.minOf { it.width }
        val h = frames.minOf { it.height }
        val scaled = frames.map { scaleTo(it, w, h) }

        var refIdx = 0
        var bestSharp = -1.0
        for (i in scaled.indices) {
            val s = sharpnessNormalized(scaled[i])
            if (s > bestSharp) {
                bestSharp = s
                refIdx = i
            }
        }
        val refBmp = scaled[refIdx]
        val ref = IntArray(w * h)
        refBmp.getPixels(ref, 0, w, 0, 0, w, h)

        data class Aligned(val px: IntArray, val valid: BooleanArray)

        val aligned = ArrayList<Aligned>()
        aligned.add(Aligned(ref, BooleanArray(w * h) { true }))

        for (i in scaled.indices) {
            if (i == refIdx) continue
            val (dx, dy) = estimateShift(refBmp, scaled[i])
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            if (dist > MAX_SHIFT_PX) {
                // Global motion — return sharpest only
                return refBmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            val pair = shiftWithMask(scaled[i], w, h, dx, dy)
            // Global consistency check on valid overlap
            val md = meanDiffValid(ref, pair.first, pair.second)
            if (md > 22.0) {
                return refBmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            aligned.add(Aligned(pair.first, pair.second))
        }

        if (aligned.size < 2) {
            return refBmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Tile-local merge: only average tiles that agree with reference
        val out = IntArray(w * h)
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * TILE
                val y0 = ty * TILE
                val x1 = min(w, x0 + TILE)
                val y1 = min(h, y0 + TILE)

                // Which secondary frames are consistent in this tile?
                val use = BooleanArray(aligned.size)
                use[0] = true
                var count = 1
                for (f in 1 until aligned.size) {
                    var sum = 0.0
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            val i = y * w + x
                            if (aligned[f].valid[i]) {
                                sum += abs(luma(ref[i]) - luma(aligned[f].px[i]))
                                n++
                            }
                            x++
                        }
                        y++
                    }
                    val avg = if (n > 0) sum / n else 999.0
                    if (avg <= TILE_DIFF_ABORT) {
                        use[f] = true
                        count++
                    }
                }

                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val i = y * w + x
                        if (count <= 1) {
                            out[i] = ref[i]
                            continue
                        }
                        var r = 0
                        var g = 0
                        var b = 0
                        var c = 0
                        for (f in aligned.indices) {
                            if (!use[f]) continue
                            if (f > 0 && !aligned[f].valid[i]) continue
                            val p = aligned[f].px[i]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            c++
                        }
                        if (c <= 0) {
                            out[i] = ref[i]
                        } else {
                            out[i] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                        }
                    }
                }
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun meanDiffValid(a: IntArray, b: IntArray, valid: BooleanArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < a.size) {
            if (valid[i]) {
                sum += abs(luma(a[i]) - luma(b[i]))
                n++
            }
            i += 4
        }
        return if (n == 0) 999.0 else sum / n
    }

    private fun sharpnessNormalized(bmp: Bitmap): Double {
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
                x += 3
            }
            y += 3
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        return if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun estimateShift(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val sw = max(48, a.width / 8)
        val sh = max(48, a.height / 8)
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
        val scaleX = a.width / sw
        val scaleY = a.height / sh
        return Pair(bestDx * scaleX, bestDy * scaleY)
    }

    /** Shift without edge clamp-repeat; invalid outside source. */
    private fun shiftWithMask(src: Bitmap, w: Int, h: Int, dx: Int, dy: Int): Pair<IntArray, BooleanArray> {
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val valid = BooleanArray(w * h)
        if (dx == 0 && dy == 0) {
            System.arraycopy(srcPx, 0, out, 0, srcPx.size)
            valid.fill(true)
            return Pair(out, valid)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                val sy = y - dy
                val i = y * w + x
                if (sx in 0 until w && sy in 0 until h) {
                    out[i] = srcPx[sy * w + sx]
                    valid[i] = true
                } else {
                    out[i] = 0
                    valid[i] = false
                }
            }
        }
        return Pair(out, valid)
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }
}

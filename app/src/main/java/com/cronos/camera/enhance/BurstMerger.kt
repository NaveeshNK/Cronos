package com.cronos.camera.enhance

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cronos Fusion v1 — confidence-guided tile merge (HDR+-inspired, RGB path).
 *
 * 1) Pick sharpest frame as reference
 * 2) Global coarse align other frames to reference
 * 3) Per-tile: if alternate frames agree with reference → weighted merge
 *    else → keep reference only (no ghost / soft blur)
 */
object BurstMerger {

    private const val TILE = 32
    private const val MAX_GLOBAL_SHIFT = 12

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val w = frames.minOf { it.width }
        val h = frames.minOf { it.height }
        val scaled = frames.map { scaleTo(it, w, h) }

        // --- 1) Sharpest reference ---
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

        // --- 2) Align others to reference (global translation) ---
        val others = ArrayList<IntArray>()
        for (i in scaled.indices) {
            if (i == refIdx) continue
            val (dx, dy) = estimateShift(refBmp, scaled[i])
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            if (dist > MAX_GLOBAL_SHIFT) {
                // Whole frame too far — skip this alternate entirely
                continue
            }
            others.add(shiftPixels(scaled[i], w, h, dx, dy))
        }

        if (others.isEmpty()) {
            return refBmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        // --- 3) Tile confidence merge ---
        val out = ref.copyOf()
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * TILE
                val y0 = ty * TILE
                val x1 = min(w, x0 + TILE)
                val y1 = min(h, y0 + TILE)

                // Noise-aware threshold from reference tile variance (dark noisier)
                var meanL = 0.0
                var count = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        meanL += luma(ref[y * w + x])
                        count++
                    }
                }
                meanL /= max(1, count)
                // Higher threshold in dark tiles (sensor noise)
                val baseThresh = 12.0 + (40.0 - meanL.coerceIn(0.0, 40.0)) * 0.35

                // Which alternates are consistent for this tile?
                val good = ArrayList<IntArray>()
                for (alt in others) {
                    var sad = 0.0
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            val i = y * w + x
                            sad += abs(luma(ref[i]) - luma(alt[i]))
                            n++
                            x += 2
                        }
                        y += 2
                    }
                    val avgSad = sad / max(1, n)
                    if (avgSad <= baseThresh) {
                        good.add(alt)
                    }
                }

                if (good.isEmpty()) {
                    // keep reference pixels already in out
                    continue
                }

                // Merge: reference + consistent alternates (median if 2+ alts, else average)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val i = y * w + x
                        val n = 1 + good.size
                        if (n == 2) {
                            val p0 = ref[i]
                            val p1 = good[0][i]
                            val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF)) / 2
                            val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF)) / 2
                            val b = ((p0 and 0xFF) + (p1 and 0xFF)) / 2
                            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        } else {
                            val rs = IntArray(n)
                            val gs = IntArray(n)
                            val bs = IntArray(n)
                            rs[0] = (ref[i] shr 16) and 0xFF
                            gs[0] = (ref[i] shr 8) and 0xFF
                            bs[0] = ref[i] and 0xFF
                            for (k in good.indices) {
                                val p = good[k][i]
                                rs[k + 1] = (p shr 16) and 0xFF
                                gs[k + 1] = (p shr 8) and 0xFF
                                bs[k + 1] = p and 0xFF
                            }
                            rs.sort(); gs.sort(); bs.sort()
                            val m = n / 2
                            out[i] = (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
                        }
                    }
                }
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
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

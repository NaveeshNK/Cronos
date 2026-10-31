package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Portrait: clean subject (bright + sharp), soft background, minimal edge ghosting.
 */
object PortraitEffect {

    private val segmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(options)
    }

    fun apply(source: Bitmap, blurStrength: Float = 0.9f): Bitmap {
        val w = source.width
        val h = source.height
        val src = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val rawMask = trySubjectMask(src) ?: fallbackMask(src)
        // Narrower feather = less ghost halo
        val feathered = featherMask(rawMask, w, h, radius = max(2, min(w, h) / 180))
        // Harden mask: subject stays solid longer
        val mask = FloatArray(w * h)
        for (i in mask.indices) {
            val v = feathered[i]
            // smoothstep toward binary — reduces semi-transparent ghost
            val t = ((v - 0.25f) / 0.45f).coerceIn(0f, 1f)
            mask[i] = t * t * (3f - 2f * t)
        }

        val bgBlur = multiPassBlur(src, passes = 4)
        val strength = blurStrength.coerceIn(0.4f, 1f)

        val sp = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        bgBlur.getPixels(bp, 0, w, 0, 0, w, h)

        // Unsharp subject copy
        val sharp = sp.copyOf()
        unsharpSubject(sharp, mask, w, h, amount = 0.55f)

        val outPx = IntArray(w * h)
        for (i in 0 until w * h) {
            val m = mask[i] // 1 = subject
            val a = sharp[i]
            val b = bp[i]
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF

            // Background mix only where mask is low
            val bgMix = ((1f - m) * strength).coerceIn(0f, 1f)

            // Subject: brighter + slightly more vivid
            val pop = 1f + 0.10f * m   // brightness
            val satBoost = 1f + 0.08f * m
            var r = ar * pop
            var g = ag * pop
            var bl = ab * pop
            val gray = 0.299f * r + 0.587f * g + 0.114f * bl
            r = gray + (r - gray) * satBoost
            g = gray + (g - gray) * satBoost
            bl = gray + (bl - gray) * satBoost

            val fr = (r * (1f - bgMix) + br * bgMix).toInt().coerceIn(0, 255)
            val fg = (g * (1f - bgMix) + bg * bgMix).toInt().coerceIn(0, 255)
            val fb = (bl * (1f - bgMix) + bb * bgMix).toInt().coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        runCatching { bgBlur.recycle() }
        return out
    }

    /** Sharpen only solid subject pixels to avoid edge ringing/ghost. */
    private fun unsharpSubject(px: IntArray, mask: FloatArray, w: Int, h: Int, amount: Float) {
        val copy = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i] < 0.55f) continue
                val c = copy[i]
                val l = luma(c)
                var sum = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    sum += luma(copy[(y + dy) * w + (x + dx)])
                    n++
                }
                val blurL = sum / n.toFloat()
                val delta = (l - blurL) * amount * mask[i]
                val r = ((c shr 16) and 0xFF) + delta
                val g = ((c shr 8) and 0xFF) + delta
                val b = (c and 0xFF) + delta
                px[i] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun trySubjectMask(src: Bitmap): FloatArray? {
        return try {
            val image = InputImage.fromBitmap(src, 0)
            val result = Tasks.await(segmenter.process(image), 8, TimeUnit.SECONDS)
            val buf: FloatBuffer = result.foregroundConfidenceMask ?: return null
            buf.rewind()
            val w = src.width
            val h = src.height
            val n = w * h
            if (buf.remaining() < n) return scaleMaskBuffer(buf, src)
            val mask = FloatArray(n)
            var sum = 0f
            for (i in 0 until n) {
                val v = buf.get().coerceIn(0f, 1f)
                mask[i] = v
                sum += v
            }
            if (sum / n < 0.02f) return null
            mask
        } catch (_: Throwable) {
            null
        }
    }

    private fun scaleMaskBuffer(buf: FloatBuffer, src: Bitmap): FloatArray? {
        return try {
            buf.rewind()
            val remaining = buf.remaining()
            val side = sqrt(remaining.toDouble()).toInt()
            if (side * side != remaining) return null
            val small = FloatArray(remaining)
            for (i in 0 until remaining) small[i] = buf.get().coerceIn(0f, 1f)
            val w = src.width
            val h = src.height
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val sx = (x * side / w).coerceIn(0, side - 1)
                    val sy = (y * side / h).coerceIn(0, side - 1)
                    out[y * w + x] = small[sy * side + sx]
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun fallbackMask(src: Bitmap): FloatArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mask = FloatArray(w * h)
        val cx = w * 0.5f
        val cy = h * 0.42f
        val rx = w * 0.32f
        val ry = h * 0.38f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(px[i])
                val edge = abs(l - luma(px[i - 1])) + abs(l - luma(px[i + 1])) +
                    abs(l - luma(px[i - w])) + abs(l - luma(px[i + w]))
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                val center = (1f - ((d - 0.1f) / 0.85f).coerceIn(0f, 1f))
                val edgeBoost = (edge / 90f).coerceIn(0f, 0.25f)
                mask[i] = (center * 0.8f + edgeBoost * center).coerceIn(0f, 1f)
            }
        }
        return mask
    }

    private fun featherMask(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(mask.size)
        val out = FloatArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (dx in -radius..radius) {
                    s += mask[y * w + (x + dx).coerceIn(0, w - 1)]
                    c++
                }
                tmp[y * w + x] = s / c
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (dy in -radius..radius) {
                    s += tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    c++
                }
                out[y * w + x] = (s / c).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun multiPassBlur(src: Bitmap, passes: Int): Bitmap {
        var cur = src
        var own = false
        repeat(passes) {
            val w = cur.width
            val h = cur.height
            val nw = max(24, w / 2)
            val nh = max(24, h / 2)
            val small = Bitmap.createScaledBitmap(cur, nw, nh, true)
            val blurred = boxBlur3(small)
            val full = Bitmap.createScaledBitmap(blurred, w, h, true)
            if (own) runCatching { cur.recycle() }
            runCatching { small.recycle() }
            runCatching { blurred.recycle() }
            cur = full
            own = true
        }
        return cur
    }

    private fun boxBlur3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val p = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h)
        val o = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var c = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val v = p[yy * w + xx]
                    r += (v shr 16) and 0xFF
                    g += (v shr 8) and 0xFF
                    b += v and 0xFF
                    c++
                }
                o[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(o, 0, w, 0, 0, w, h)
        return out
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }
}

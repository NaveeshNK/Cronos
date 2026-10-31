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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Portrait closer to iPhone approach:
 * 1) Subject segmentation mask (ML Kit) — people / pets / prominent objects
 * 2) Soft feathered edge
 * 3) Stronger multi-pass blur only on background
 * 4) Fallback: edge + center guided mask if ML Kit fails / no subject
 */
object PortraitEffect {

    private val segmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(options)
    }

    fun apply(source: Bitmap, blurStrength: Float = 0.85f): Bitmap {
        val w = source.width
        val h = source.height
        val src = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        val mask = trySubjectMask(src) ?: fallbackMask(src)
        val feathered = featherMask(mask, w, h, radius = max(3, min(w, h) / 120))

        val bgBlur = multiPassBlur(src, passes = 3)
        val strength = blurStrength.coerceIn(0.3f, 1f)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sp = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        bgBlur.getPixels(bp, 0, w, 0, 0, w, h)

        for (i in 0 until w * h) {
            // mask: 1 = subject (sharp), 0 = background (blur)
            val m = feathered[i]
            val t = ((1f - m) * strength).coerceIn(0f, 1f)
            val a = sp[i]
            val b = bp[i]
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            // Slight subject lift (iPhone-ish subject pop)
            val lift = 1f + 0.04f * m * strength
            val r = ((ar * (1 - t) + br * t) * lift).toInt().coerceIn(0, 255)
            val g = ((ag * (1 - t) + bg * t) * lift).toInt().coerceIn(0, 255)
            val bl = ((ab * (1 - t) + bb * t) * lift).toInt().coerceIn(0, 255)
            out.setPixel(i % w, i / w, Color.argb(255, r, g, bl))
        }

        runCatching { bgBlur.recycle() }
        return out
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
            if (buf.remaining() < n) {
                // mask may be model size — scale
                val mw = result.foregroundConfidenceMask?.let { /* use bitmap dims if any */ null }
                return scaleMaskBuffer(buf, src)
            }
            val mask = FloatArray(n)
            var sum = 0f
            for (i in 0 until n) {
                val v = buf.get().coerceIn(0f, 1f)
                mask[i] = v
                sum += v
            }
            // No real subject detected
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
            // assume square-ish model output
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

    /** Fallback: center + high local structure kept sharp (works for flowers too). */
    private fun fallbackMask(src: Bitmap): FloatArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mask = FloatArray(w * h)
        val cx = w * 0.5f
        val cy = h * 0.45f
        val rx = w * 0.36f
        val ry = h * 0.40f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(px[i])
                val edge = abs(l - luma(px[i - 1])) + abs(l - luma(px[i + 1])) +
                    abs(l - luma(px[i - w])) + abs(l - luma(px[i + w]))
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                val center = (1f - ((d - 0.15f) / 0.95f).coerceIn(0f, 1f))
                val edgeBoost = (edge / 80f).coerceIn(0f, 0.35f)
                mask[i] = (center * 0.75f + edgeBoost * center).coerceIn(0f, 1f)
            }
        }
        return mask
    }

    private fun featherMask(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val out = mask.copyOf()
        val tmp = FloatArray(mask.size)
        // box blur mask for soft transitions (hair/edges)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    s += mask[y * w + xx]
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
                    val yy = (y + dy).coerceIn(0, h - 1)
                    s += tmp[yy * w + x]
                    c++
                }
                // smoothstep for cleaner subject edge
                val v = (s / c).coerceIn(0f, 1f)
                out[y * w + x] = v * v * (3f - 2f * v)
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
            val nw = max(32, w / 2)
            val nh = max(32, h / 2)
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

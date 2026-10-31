package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight portrait look: mild background soft-focus using radial mask.
 * Not dual-camera depth — still useful futuristic default without ML Kit.
 */
object PortraitEffect {

    fun apply(source: Bitmap, blurStrength: Float = 0.65f): Bitmap {
        val w = source.width
        val h = source.height
        val src = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)

        // Downscale blur layer for speed
        val bw = max(64, w / 6)
        val bh = max(64, h / 6)
        val small = Bitmap.createScaledBitmap(src, bw, bh, true)
        val soft = boxBlur(small, passes = 2)
        val softFull = Bitmap.createScaledBitmap(soft, w, h, true)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sp = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        softFull.getPixels(bp, 0, w, 0, 0, w, h)

        val cx = w * 0.5f
        val cy = h * 0.42f // faces usually upper-center
        val rx = w * 0.38f
        val ry = h * 0.42f
        val s = blurStrength.coerceIn(0f, 1f)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                // 0 at center (sharp), 1 at edges (blur)
                val edge = ((d - 0.55f) / 0.85f).coerceIn(0f, 1f)
                val t = edge * s
                val a = sp[i]
                val b = bp[i]
                val ar = (a shr 16) and 0xFF
                val ag = (a shr 8) and 0xFF
                val ab = a and 0xFF
                val br = (b shr 16) and 0xFF
                val bg = (b shr 8) and 0xFF
                val bb = b and 0xFF
                val r = (ar * (1 - t) + br * t).toInt()
                val g = (ag * (1 - t) + bg * t).toInt()
                val bl = (ab * (1 - t) + bb * t).toInt()
                out.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or bl)
            }
        }
        soft.recycle()
        softFull.recycle()
        if (small !== soft) runCatching { small.recycle() }
        return out
    }

    private fun boxBlur(src: Bitmap, passes: Int): Bitmap {
        var cur = src.copy(Bitmap.Config.ARGB_8888, true)
        repeat(passes) {
            val w = cur.width
            val h = cur.height
            val p = IntArray(w * h)
            cur.getPixels(p, 0, w, 0, 0, w, h)
            val o = IntArray(w * h)
            // horizontal
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0; var g = 0; var b = 0; var c = 0
                    for (dx in -2..2) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val v = p[y * w + xx]
                        r += (v shr 16) and 0xFF
                        g += (v shr 8) and 0xFF
                        b += v and 0xFF
                        c++
                    }
                    o[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                }
            }
            // vertical
            val o2 = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0; var g = 0; var b = 0; var c = 0
                    for (dy in -2..2) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        val v = o[yy * w + x]
                        r += (v shr 16) and 0xFF
                        g += (v shr 8) and 0xFF
                        b += v and 0xFF
                        c++
                    }
                    o2[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                }
            }
            cur.setPixels(o2, 0, w, 0, 0, w, h)
        }
        return cur
    }
}

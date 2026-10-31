package com.cronos.camera.enhance

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow

/**
 * Night lift after merge: open shadows hard, mild denoise, keep highlights from clipping.
 * Does not touch portrait path.
 */
object NightEnhance {

    fun apply(source: Bitmap, amount: Float = 1f): Bitmap {
        val a = amount.coerceIn(0.3f, 1.2f)
        val w = source.width
        val h = source.height
        val src = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Mean luma — if already bright, lighten less
        var sum = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = (sum / n).toFloat()
        // Dark scene (mean < 40): strong lift; brighter: gentler
        val boost = when {
            mean < 18f -> 2.4f * a
            mean < 35f -> 1.9f * a
            mean < 55f -> 1.45f * a
            else -> 1.15f * a
        }

        // First pass: gamma + gain
        for (i in 0 until n) {
            val p = px[i]
            var r = ((p shr 16) and 0xFF) / 255f
            var g = ((p shr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f
            // Gain
            r = (r * boost).coerceIn(0f, 1.5f)
            g = (g * boost).coerceIn(0f, 1.5f)
            b = (b * boost).coerceIn(0f, 1.5f)
            // Shadow gamma (open blacks)
            r = r.pow(0.72f).coerceIn(0f, 1f)
            g = g.pow(0.72f).coerceIn(0f, 1f)
            b = b.pow(0.72f).coerceIn(0f, 1f)
            px[i] = Color.argb(
                255,
                (r * 255).toInt().coerceIn(0, 255),
                (g * 255).toInt().coerceIn(0, 255),
                (b * 255).toInt().coerceIn(0, 255)
            )
        }

        // Light denoise on flat dark areas (3x3)
        val copy = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = luma(copy[i])
                if (l > 140) continue
                var sr = 0; var sg = 0; var sb = 0; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = copy[(y + dy) * w + (x + dx)]
                    if (kotlin.math.abs(luma(p) - l) > 28) continue
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                    c++
                }
                if (c < 4) continue
                val t = 0.45f
                val p0 = copy[i]
                val r = (((p0 shr 16) and 0xFF) * (1 - t) + (sr / c) * t).toInt().coerceIn(0, 255)
                val g = (((p0 shr 8) and 0xFF) * (1 - t) + (sg / c) * t).toInt().coerceIn(0, 255)
                val b = ((p0 and 0xFF) * (1 - t) + (sb / c) * t).toInt().coerceIn(0, 255)
                px[i] = Color.argb(255, r, g, b)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }
}

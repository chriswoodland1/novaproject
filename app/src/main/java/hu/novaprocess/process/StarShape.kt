package hu.novaprocess.process

import hu.novaprocess.image.FloatImage
import hu.novaprocess.stack.StarDetector
import kotlin.math.sqrt

/** Csillagalak (FWHM, elliptikusság) régiónként – fókusz/dőlés diagnosztika. */
object StarShape {
    data class Map(val grid: Int, val fwhm: FloatArray, val ecc: FloatArray, val stars: Int)

    fun measure(src: FloatImage, grid: Int = 5): Map {
        val img = src.downscale(1400)
        val scale = maxOf(src.width, src.height).toFloat() / maxOf(img.width, img.height)
        val mono = img.toMono(); val a = mono.data[0]; val w = mono.width; val h = mono.height
        val bg = Processor.median(a)
        val stars = StarDetector.detect(mono, maxStars = 600, sigmaK = 5f)
        val sumF = FloatArray(grid * grid); val sumE = FloatArray(grid * grid); val n = IntArray(grid * grid)
        for (s in stars) {
            val cx = s.x.toInt(); val cy = s.y.toInt(); if (cx < 4 || cy < 4 || cx >= w - 4 || cy >= h - 4) continue
            var m00 = 0f; var mxx = 0f; var myy = 0f; var mxy = 0f
            for (dy in -4..4) for (dx in -4..4) { val p = (a[(cy + dy) * w + cx + dx] - bg).coerceAtLeast(0f); m00 += p; mxx += p * dx * dx; myy += p * dy * dy; mxy += p * dx * dy }
            if (m00 <= 0f) continue
            val sxx = mxx / m00; val syy = myy / m00; val sxy = mxy / m00
            val tr = sxx + syy; val det = sxx * syy - sxy * sxy
            val disc = sqrt(((tr * tr) / 4f - det).coerceAtLeast(0f))
            val l1 = tr / 2f + disc; val l2 = (tr / 2f - disc).coerceAtLeast(1e-4f)
            val fwhm = 2.355f * sqrt(((l1 + l2) / 2f).coerceAtLeast(1e-4f)) * scale
            val ecc = sqrt((1f - l2 / l1).coerceIn(0f, 1f))
            val gx = (cx * grid / w).coerceIn(0, grid - 1); val gy = (cy * grid / h).coerceIn(0, grid - 1)
            val i = gy * grid + gx
            sumF[i] += fwhm; sumE[i] += ecc; n[i]++
        }
        return Map(grid, FloatArray(grid * grid) { if (n[it] >= 3) sumF[it] / n[it] else 0f }, FloatArray(grid * grid) { if (n[it] >= 3) sumE[it] / n[it] else 0f }, stars.size)
    }
}

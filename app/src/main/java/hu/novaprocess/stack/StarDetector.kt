package hu.novaprocess.stack

import hu.novaprocess.image.FloatImage
import kotlin.math.sqrt

data class Star(val x: Float, val y: Float, val flux: Float)

/**
 * Egyszerű csillagdetektor: háttér+szigma küszöb, lokális maximum, súlypont.
 * Nem Siril-szintű PSF illesztés, de igazításhoz bőven elég.
 */
object StarDetector {

    fun detect(img: FloatImage, maxStars: Int = 200, sigmaK: Float = 5f): List<Star> {
        val mono = img.toMono()
        val w = mono.width; val h = mono.height; val a = mono.data[0]

        // háttér és zaj becslés mintavétellel (medián / MAD)
        val sample = FloatArray(minOf(a.size, 20000)) { a[(it.toLong() * a.size / minOf(a.size, 20000)).toInt()] }
        sample.sort()
        val median = sample[sample.size / 2]
        val dev = FloatArray(sample.size) { kotlin.math.abs(sample[it] - median) }
        dev.sort()
        val sigma = (dev[dev.size / 2] * 1.4826f).coerceAtLeast(1e-5f)
        val thr = median + sigmaK * sigma

        val stars = ArrayList<Star>()
        val r = 3
        for (y in r until h - r step 1) {
            val row = y * w
            for (x in r until w - r) {
                val v = a[row + x]
                if (v <= thr) continue
                // lokális maximum 5×5-ben
                var isMax = true
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    if (a[(y + dy) * w + x + dx] > v) { isMax = false; break@loop }
                }
                if (!isMax) continue
                // súlypont 7×7-ben, háttér levonással
                var sx = 0f; var sy = 0f; var sum = 0f
                for (dy in -r..r) for (dx in -r..r) {
                    val p = (a[(y + dy) * w + x + dx] - median).coerceAtLeast(0f)
                    sx += p * dx; sy += p * dy; sum += p
                }
                if (sum <= 0f) continue
                // telített / túl nagy foltok kiszűrése
                if (v >= 0.98f) continue
                stars.add(Star(x + sx / sum, y + sy / sum, sum))
            }
        }
        stars.sortByDescending { it.flux }
        // túl közeli duplikátumok kidobása
        val out = ArrayList<Star>()
        for (s in stars) {
            if (out.none { o -> dist2(o, s) < 36f }) out.add(s)
            if (out.size >= maxStars) break
        }
        return out
    }

    private fun dist2(a: Star, b: Star): Float { val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy }

    fun dist(a: Star, b: Star): Float = sqrt(dist2(a, b))
}

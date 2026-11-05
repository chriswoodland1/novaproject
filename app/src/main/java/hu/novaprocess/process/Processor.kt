package hu.novaprocess.process

import hu.novaprocess.image.FloatImage
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A teljes feldolgozó lánc. Sorrend (Siril-munkafolyamat szerint):
 * geometria → háttér-kivonás → színkalibráció → stretch → csillagcsökkentés → zajszűrés
 * → fotós beállítások (expozíció, kontraszt, HSL, clarity, dehaze, élesítés, szintek).
 * Minden lépés tiszta függvény egy FloatImage-en; az előnézet és az export ugyanazt futtatja.
 */
object Processor {

    fun process(src: FloatImage, p: ProcessParams): FloatImage {
        var img = geometry(src, p)
        if (p.backgroundExtraction) img = backgroundExtraction(img, p.backgroundDegree)
        if (p.backgroundNeutralize) img = neutralizeBackground(img)
        if (p.whiteBalanceStars) img = whiteBalanceOnStars(img)
        img = stretch(img, p)
        if (p.starReduction > 0f) img = reduceStars(img, p.starReduction, p.starRadius)
        if (p.denoise > 0f) img = denoise(img, p.denoise, p.denoiseRadius)
        img = photoAdjust(img, p)
        img.clampInPlace()
        return img
    }

    // ---------------------------------------------------------------- geometria
    fun geometry(src: FloatImage, p: ProcessParams): FloatImage {
        var img = src
        if (p.rotation != Rotation.R0 || p.flipH) img = rotateFlip(img, p.rotation, p.flipH)
        val l = (p.cropLeft * img.width).toInt().coerceIn(0, img.width - 1)
        val t = (p.cropTop * img.height).toInt().coerceIn(0, img.height - 1)
        val r = (p.cropRight * img.width).toInt().coerceIn(l + 1, img.width)
        val b = (p.cropBottom * img.height).toInt().coerceIn(t + 1, img.height)
        if (l == 0 && t == 0 && r == img.width && b == img.height) return if (img === src) src.copy() else img
        val out = FloatImage(r - l, b - t, img.channels)
        for (c in 0 until img.channels) for (y in t until b)
            System.arraycopy(img.data[c], y * img.width + l, out.data[c], (y - t) * out.width, r - l)
        return out
    }

    private fun rotateFlip(src: FloatImage, rot: Rotation, flipH: Boolean): FloatImage {
        val w = src.width; val h = src.height
        val swap = rot == Rotation.R90 || rot == Rotation.R270
        val out = FloatImage(if (swap) h else w, if (swap) w else h, src.channels)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = if (flipH) w - 1 - x else x
            val (ox, oy) = when (rot) {
                Rotation.R0 -> Pair(sx, y)
                Rotation.R90 -> Pair(h - 1 - y, sx)
                Rotation.R180 -> Pair(w - 1 - sx, h - 1 - y)
                Rotation.R270 -> Pair(y, w - 1 - sx)
            }
            for (c in 0 until src.channels) out.data[c][oy * out.width + ox] = src.data[c][y * w + x]
        }
        return out
    }

    // ---------------------------------------------------------------- statisztika
    fun median(a: FloatArray, samples: Int = 50000): Float {
        val n = min(a.size, samples)
        val s = FloatArray(n) { a[(it.toLong() * a.size / n).toInt()] }
        s.sort(); return s[n / 2]
    }
    fun mad(a: FloatArray, med: Float, samples: Int = 50000): Float {
        val n = min(a.size, samples)
        val s = FloatArray(n) { abs(a[(it.toLong() * a.size / n).toInt()] - med) }
        s.sort(); return s[n / 2]
    }

    // ---------------------------------------------------------------- háttér (gradiens) kivonás
    /** Mintavételes polinomillesztés (1. vagy 2. fokú), 16×16 rács, cellánként alsó 20% medián. */
    fun backgroundExtraction(src: FloatImage, degree: Int): FloatImage {
        val out = src.copy()
        val w = src.width; val h = src.height
        val grid = 16
        for (c in 0 until src.channels) {
            val a = out.data[c]
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val vs = ArrayList<Float>()
            val cw = w / grid; val chh = h / grid
            if (cw < 4 || chh < 4) continue
            for (gy in 0 until grid) for (gx in 0 until grid) {
                val cell = ArrayList<Float>(cw * chh / 4)
                var y = gy * chh
                while (y < (gy + 1) * chh) { var x = gx * cw; while (x < (gx + 1) * cw) { cell.add(a[y * w + x]); x += 2 }; y += 2 }
                cell.sort()
                val v = cell[(cell.size * 0.2f).toInt()]
                xs.add((gx + 0.5f) * cw / w - 0.5f); ys.add((gy + 0.5f) * chh / h - 0.5f); vs.add(v)
            }
            // robusztus: a legfényesebb 25% cella kidobása (objektum)
            val order = vs.indices.sortedBy { vs[it] }.take((vs.size * 0.75f).toInt())
            val terms = if (degree >= 2) 6 else 3
            val coef = fitPoly(order.map { xs[it] }, order.map { ys[it] }, order.map { vs[it] }, terms) ?: continue
            val med = median(a)
            for (y in 0 until h) {
                val fy = y.toFloat() / h - 0.5f
                for (x in 0 until w) {
                    val fx = x.toFloat() / w - 0.5f
                    var bg = coef[0] + coef[1] * fx + coef[2] * fy
                    if (terms == 6) bg += coef[3] * fx * fx + coef[4] * fx * fy + coef[5] * fy * fy
                    val i = y * w + x
                    a[i] = (a[i] - bg + med).coerceAtLeast(0f)
                }
            }
        }
        return out
    }

    /** Legkisebb négyzetes illesztés normálegyenlettel (Gauss-elimináció). */
    private fun fitPoly(xs: List<Float>, ys: List<Float>, vs: List<Float>, terms: Int): FloatArray? {
        val n = xs.size; if (n < terms) return null
        val ata = Array(terms) { DoubleArray(terms) }; val atb = DoubleArray(terms)
        val row = DoubleArray(terms)
        for (k in 0 until n) {
            val x = xs[k].toDouble(); val y = ys[k].toDouble()
            row[0] = 1.0; row[1] = x; row[2] = y
            if (terms == 6) { row[3] = x * x; row[4] = x * y; row[5] = y * y }
            for (i in 0 until terms) { atb[i] += row[i] * vs[k]; for (j in 0 until terms) ata[i][j] += row[i] * row[j] }
        }
        for (i in 0 until terms) {
            var piv = i
            for (r in i + 1 until terms) if (abs(ata[r][i]) > abs(ata[piv][i])) piv = r
            if (abs(ata[piv][i]) < 1e-12) return null
            if (piv != i) { val t = ata[i]; ata[i] = ata[piv]; ata[piv] = t; val tb = atb[i]; atb[i] = atb[piv]; atb[piv] = tb }
            for (r in i + 1 until terms) {
                val f = ata[r][i] / ata[i][i]
                for (cc in i until terms) ata[r][cc] -= f * ata[i][cc]
                atb[r] -= f * atb[i]
            }
        }
        val out = FloatArray(terms)
        for (i in terms - 1 downTo 0) {
            var s = atb[i]
            for (j in i + 1 until terms) s -= ata[i][j] * out[j]
            out[i] = (s / ata[i][i]).toFloat()
        }
        return out
    }

    // ---------------------------------------------------------------- színkalibráció
    fun neutralizeBackground(src: FloatImage): FloatImage {
        if (src.channels != 3) return src
        val out = src.copy()
        val meds = FloatArray(3) { median(out.data[it]) }
        val target = meds.average().toFloat()
        for (c in 0 until 3) { val a = out.data[c]; val d = target - meds[c]; for (i in a.indices) a[i] = (a[i] + d).coerceAtLeast(0f) }
        return out
    }

    /** Fehéregyensúly a fényes (de nem telített) csillagokra: csatorna-átlagok kiegyenlítése. */
    fun whiteBalanceOnStars(src: FloatImage): FloatImage {
        if (src.channels != 3) return src
        val out = src.copy()
        val lum = src.toMono().data[0]
        val sorted = FloatArray(min(lum.size, 50000)) { lum[(it.toLong() * lum.size / min(lum.size, 50000)).toInt()] }
        sorted.sort()
        val lo = sorted[(sorted.size * 0.995f).toInt()]
        val sums = FloatArray(3); var n = 0
        for (i in lum.indices) if (lum[i] > lo && lum[i] < 0.95f) { for (c in 0 until 3) sums[c] += src.data[c][i]; n++ }
        if (n < 10) return out
        val mean = sums.average().toFloat()
        for (c in 0 until 3) { val g = mean / (sums[c] / n).coerceAtLeast(1e-6f); val a = out.data[c]; for (i in a.indices) a[i] *= g }
        return out
    }

    // ---------------------------------------------------------------- stretch
    fun stretch(src: FloatImage, p: ProcessParams): FloatImage {
        val out = src.copy()
        when (p.stretchMode) {
            StretchMode.NONE -> {
                if (p.blackPoint > 0f) for (c in 0 until out.channels) { val a = out.data[c]; for (i in a.indices) a[i] = ((a[i] - p.blackPoint) / (1f - p.blackPoint)).coerceAtLeast(0f) }
            }
            StretchMode.ASINH -> {
                val k = p.asinhStrength.coerceAtLeast(1f)
                val norm = asinh(k)
                // színmegőrző: a luminanciára számolt tényezőt alkalmazzuk minden csatornára
                val lum = out.toMono().data[0]
                for (i in lum.indices) {
                    val l = (lum[i] - p.blackPoint).coerceAtLeast(1e-6f) / (1f - p.blackPoint)
                    val f = (asinh(k * l) / norm) / l
                    for (c in 0 until out.channels) out.data[c][i] = ((out.data[c][i] - p.blackPoint) / (1f - p.blackPoint)) * f
                }
            }
            StretchMode.AUTO, StretchMode.MTF -> {
                // Siril-féle autostretch: shadows = median - k*MAD, midtones úgy, hogy a háttér a célértékre kerüljön
                val lum = out.toMono().data[0]
                val med = median(lum); val madv = mad(lum, med).coerceAtLeast(1e-6f)
                val shadows = if (p.stretchMode == StretchMode.AUTO) (med - p.mtfShadowClip * madv * 1.4826f).coerceIn(0f, 0.99f) else p.blackPoint
                val x = ((med - shadows) / (1f - shadows)).coerceIn(1e-5f, 0.999f)
                val target = p.mtfMidtones.coerceIn(0.01f, 0.99f)
                // MTF(m, x) = target  =>  m = (x*(1-target)) / (x*(1-target) + target*(1-x))... Siril képlete:
                val m = mtfMidtoneFor(x, target)
                for (c in 0 until out.channels) {
                    val a = out.data[c]
                    for (i in a.indices) {
                        val v = ((a[i] - shadows) / (1f - shadows)).coerceIn(0f, 1f)
                        a[i] = mtf(m, v)
                    }
                }
            }
        }
        return out
    }

    /** Midtones Transfer Function (PixInsight/Siril). */
    private fun mtf(m: Float, x: Float): Float {
        if (x <= 0f) return 0f; if (x >= 1f) return 1f
        return ((m - 1f) * x) / ((2f * m - 1f) * x - m)
    }
    private fun mtfMidtoneFor(x: Float, target: Float): Float {
        // MTF inverz: keressük m-et, amelyre mtf(m, x) = target (zárt alak)
        return ((x - 1f) * target) / ((2f * target - 1f) * x - target)
    }

    // ---------------------------------------------------------------- csillagcsökkentés
    /** Morfológiai nyitás (min majd max) adja a "csillagtalan" közelítést; a csillagokat ennek arányában keverjük vissza. */
    fun reduceStars(src: FloatImage, amount: Float, radius: Int): FloatImage {
        val out = src.copy()
        for (c in 0 until src.channels) {
            val a = src.data[c]
            val opened = maxFilter(minFilter(a, src.width, src.height, radius), src.width, src.height, radius)
            val o = out.data[c]
            for (i in a.indices) {
                val star = a[i] - opened[i]
                o[i] = opened[i] + star * (1f - amount)
            }
        }
        return out
    }

    private fun minFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray = sepFilter(a, w, h, r, true)
    private fun maxFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray = sepFilter(a, w, h, r, false)
    private fun sepFilter(a: FloatArray, w: Int, h: Int, r: Int, isMin: Boolean): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        hu.novaprocess.image.Par.rows(h) { y0, y1 ->
          for (y in y0 until y1) for (x in 0 until w) {
            var v = a[y * w + x]
            for (d in -r..r) { val xx = (x + d).coerceIn(0, w - 1); val s = a[y * w + xx]; v = if (isMin) min(v, s) else max(v, s) }
            tmp[y * w + x] = v
          }
        }
        hu.novaprocess.image.Par.rows(h) { y0, y1 ->
          for (y in y0 until y1) for (x in 0 until w) {
            var v = tmp[y * w + x]
            for (d in -r..r) { val yy = (y + d).coerceIn(0, h - 1); val s = tmp[yy * w + x]; v = if (isMin) min(v, s) else max(v, s) }
            out[y * w + x] = v
          }
        }
        return out
    }

    // ---------------------------------------------------------------- zajszűrés
    /** Élmegőrző (bilaterális jellegű) simítás, a sötét, zajos területeken erősebb. */
    fun denoise(src: FloatImage, amount: Float, radius: Int): FloatImage {
        val out = src.copy()
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val sigmaR = 0.02f + 0.12f * amount
        val inv2s = 1f / (2f * sigmaR * sigmaR)
        for (c in 0 until src.channels) {
            val a = src.data[c]; val o = out.data[c]
            hu.novaprocess.image.Par.rows(h) { y0, y1 ->
              for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val center = lum[i]
                var acc = 0f; var wsum = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val j = yy * w + xx
                        val d = lum[j] - center
                        val wt = exp(-d * d * inv2s)
                        acc += a[j] * wt; wsum += wt
                    }
                }
                val smooth = acc / wsum
                // fényes részeken (csillagok, mag) kevésbé simítunk
                val k = amount * (1f - center).coerceIn(0f, 1f)
                o[i] = a[i] * (1f - k) + smooth * k
              }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- fotós beállítások
    fun photoAdjust(src: FloatImage, p: ProcessParams): FloatImage {
        val out = src
        val w = out.width; val h = out.height; val n = out.size
        val rgb = out.channels == 3

        // fehéregyensúly / csatorna-kiegyenlítés
        if (rgb && (p.temperature != 0f || p.tint != 0f || p.redBalance != 0f || p.greenBalance != 0f || p.blueBalance != 0f)) {
            val gr = 1f + 0.25f * p.temperature - 0.15f * p.tint + p.redBalance
            val gg = 1f + 0.3f * p.tint + p.greenBalance
            val gb = 1f - 0.25f * p.temperature - 0.15f * p.tint + p.blueBalance
            val gains = floatArrayOf(gr, gg, gb)
            for (c in 0 until 3) { val a = out.data[c]; val g = gains[c]; for (i in 0 until n) a[i] *= g }
        }

        // dehaze: alsó percentil kivonása + enyhe kontraszt
        if (p.dehaze > 0f) {
            for (c in 0 until out.channels) {
                val a = out.data[c]
                val lo = percentile(a, 0.02f) * p.dehaze
                for (i in 0 until n) a[i] = ((a[i] - lo) / (1f - lo).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
            }
        }

        // expozíció, szintek, gamma, kontraszt, tónusok — LUT-tal gyorsan
        val lut = FloatArray(4096)
        val ev = 2f.pow(p.exposure)
        for (k in 0 until 4096) {
            var v = k / 4095f
            v *= ev
            v = ((v - p.levelsBlack) / (p.levelsWhite - p.levelsBlack).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            if (p.gamma != 1f) v = v.pow(1f / p.gamma)
            // blacks / whites: a végpontok tolása
            if (p.blacks != 0f) v = (v + p.blacks * 0.1f * (1f - v)).coerceIn(0f, 1f)
            if (p.whites != 0f) v = (v + p.whites * 0.1f * v).coerceIn(0f, 1f)
            // shadows / highlights: sima súlyozott emelés/vágás
            if (p.shadows != 0f) { val wgt = (1f - v).pow(2f); v += p.shadows * 0.3f * wgt * v }
            if (p.highlights != 0f) { val wgt = v.pow(2f); v = (v + p.highlights * 0.3f * wgt * (1f - v)).coerceIn(0f, 1f) }
            // kontraszt S-görbe 0.5 körül
            if (p.contrast != 0f) {
                val cst = 1f + p.contrast
                v = ((v - 0.5f) * cst + 0.5f)
                if (p.contrast > 0f) v = smoothstep(v)
            }
            lut[k] = v.coerceIn(0f, 1f)
        }
        for (c in 0 until out.channels) {
            val a = out.data[c]
            for (i in 0 until n) a[i] = lut[(a[i].coerceIn(0f, 1f) * 4095f).toInt()]
        }

        // telítettség / vibrance (luminancia körül)
        if (rgb && (p.saturation != 0f || p.vibrance != 0f)) {
            val r = out.data[0]; val g = out.data[1]; val b = out.data[2]
            for (i in 0 until n) {
                val l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                val mx = max(r[i], max(g[i], b[i])); val mn = min(r[i], min(g[i], b[i]))
                val sat = if (mx > 0f) (mx - mn) / mx else 0f
                val k = 1f + p.saturation + p.vibrance * (1f - sat)
                r[i] = l + (r[i] - l) * k; g[i] = l + (g[i] - l) * k; b[i] = l + (b[i] - l) * k
            }
        }

        // clarity (nagy sugarú helyi kontraszt) és sharpen (kis sugarú unsharp) a luminancián
        if (p.clarity != 0f || p.sharpen > 0f) {
            val lum = out.toMono().data[0]
            val detail = FloatArray(n)
            if (p.clarity != 0f) {
                val blur = boxBlur(lum, w, h, max(4, min(w, h) / 40))
                for (i in 0 until n) detail[i] += p.clarity * 0.6f * (lum[i] - blur[i])
            }
            if (p.sharpen > 0f) {
                val blur = boxBlur(lum, w, h, 1)
                for (i in 0 until n) detail[i] += p.sharpen * 1.5f * (lum[i] - blur[i])
            }
            for (c in 0 until out.channels) { val a = out.data[c]; for (i in 0 until n) a[i] = (a[i] + detail[i]).coerceIn(0f, 1f) }
        }
        return out
    }

    private fun smoothstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    fun percentile(a: FloatArray, p: Float): Float {
        val n = min(a.size, 50000)
        val s = FloatArray(n) { a[(it.toLong() * a.size / n).toInt()] }
        s.sort(); return s[(n * p).toInt().coerceIn(0, n - 1)]
    }

    /** Szeparábilis box blur (két átmenet), sugár r. */
    fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        val div = 1f / (2 * r + 1)
        for (y in 0 until h) {
            var s = 0f
            for (d in -r..r) s += a[y * w + d.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = s * div
                val add = a[y * w + (x + r + 1).coerceIn(0, w - 1)]
                val rem = a[y * w + (x - r).coerceIn(0, w - 1)]
                s += add - rem
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += tmp[d.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s * div
                val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val rem = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                s += add - rem
            }
        }
        return out
    }

    /** 256 oszlopos hisztogram csatornánként (RGB vagy mono). */
    fun histogram(img: FloatImage): Array<IntArray> {
        val hs = Array(img.channels) { IntArray(256) }
        val step = max(1, img.size / 200000)
        for (c in 0 until img.channels) {
            val a = img.data[c]; val hh = hs[c]
            var i = 0
            while (i < a.size) { hh[(a[i].coerceIn(0f, 1f) * 255f).toInt()]++; i += step }
        }
        return hs
    }
}

package hu.novaprocess.process

import hu.novaprocess.image.FloatImage
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A teljes feldolgozó lánc. Sorrend (Siril-munkafolyamat szerint):
 * geometria → háttér-kivonás → színkalibráció → stretch → csillagcsökkentés → zajszűrés
 * → fotós beállítások (expozíció, kontraszt, HSL, clarity, dehaze, élesítés, szintek).
 * Minden lépés tiszta függvény egy FloatImage-en; az előnézet és az export ugyanazt futtatja.
 */
object Processor {

    fun process(src: FloatImage, p: ProcessParams): FloatImage {
        var img = geometry(src, p)
        // --- lineáris lépések ---
        if (p.backgroundExtraction) img = if (p.backgroundDegree >= 3) backgroundRbf(img, p.bgSamples, p.bgSmooth) else backgroundExtraction(img, p.backgroundDegree)
        // előbb a csatorna-erősítés (fehéregyensúly), utána az eltolás (háttér-semlegesítés) – így a háttér semleges marad a stretchig
        if (p.whiteBalanceStars) img = whiteBalanceOnStars(img)
        if (p.backgroundNeutralize) img = neutralizeBackground(img)
        if (p.scnr > 0f) img = scnr(img, p.scnr)
        if (p.deconvIterations > 0) img = deconvolve(img, p.deconvSigma, p.deconvIterations)
        if (p.palette != Palette.NATURAL) img = palette(img, p.palette, p.paletteStrength)
        // --- stretch ---
        img = stretch(img, p)
        if (p.neutralizeAfterStretch) img = neutralizeBackground(img)
        if (p.hdrCompress > 0f) img = hdrCompress(img, p.hdrCompress, p.hdrRadius)
        // --- csillagok ---
        if (p.starless) img = starlessComposite(img, p)
        else if (p.starReduction > 0f) img = reduceStars(img, p.starReduction, p.starRadius)
        if (p.chromaDenoise > 0f) img = chromaDenoise(img, p.chromaDenoise, p.chromaRadius)
        if (p.denoise > 0f) img = denoise(img, p.denoise, p.denoiseRadius)
        if (p.wavelet1 != 0f || p.wavelet2 != 0f || p.wavelet3 != 0f || p.waveletDenoise > 0f) img = wavelets(img, p)
        if (p.clahe > 0f) img = clahe(img, p.clahe, p.claheTiles, p.claheClip)
        // --- görbék + fotós beállítások, opcionális maszkkal ---
        val base = if (p.maskMode != MaskMode.NONE) img.copy() else null
        if (p.curveShadows != 0f || p.curveMidtones != 0f || p.curveHighlights != 0f) img = curves(img, p)
        img = photoAdjust(img, p)
        if (base != null) img = blendByMask(base, img, p)
        img.clampInPlace()
        return img
    }

    // ---------------------------------------------------------------- CLAHE (kontrasztkorlátos adaptív hisztogram-kiegyenlítés) a luminancián
    fun clahe(src: FloatImage, amount: Float, tiles: Int, clip: Float): FloatImage {
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val t = tiles.coerceIn(2, 16)
        val tw = (w + t - 1) / t; val th = (h + t - 1) / t
        val nx = (w + tw - 1) / tw; val ny = (h + th - 1) / th
        val bins = 256
        // csempénkénti CDF
        val cdf = Array(nx * ny) { FloatArray(bins) }
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val hist = IntArray(bins)
            val x0 = tx * tw; val y0 = ty * th; val x1 = minOf(w, x0 + tw); val y1 = minOf(h, y0 + th)
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { hist[(lum[y * w + x].coerceIn(0f, 1f) * (bins - 1)).toInt()]++; n++ }
            // vágás és újraelosztás
            val limit = (clip * n / bins).toInt().coerceAtLeast(1)
            var excess = 0
            for (b in 0 until bins) if (hist[b] > limit) { excess += hist[b] - limit; hist[b] = limit }
            val add = excess / bins; var rem = excess - add * bins
            for (b in 0 until bins) { hist[b] += add; if (rem > 0) { hist[b]++; rem-- } }
            var acc = 0f; val c = cdf[ty * nx + tx]
            for (b in 0 until bins) { acc += hist[b]; c[b] = acc / n }
        }
        val out = src
        val eq = FloatArray(w * h)
        hu.novaprocess.image.Par.rows(h) { r0, r1 ->
            for (y in r0 until r1) {
                val fy = ((y + 0.5f) / th - 0.5f).coerceIn(0f, (ny - 1).toFloat()); val iy = fy.toInt().coerceAtMost(maxOf(0, ny - 2)); val ay = if (ny > 1) fy - iy else 0f
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / tw - 0.5f).coerceIn(0f, (nx - 1).toFloat()); val ix = fx.toInt().coerceAtMost(maxOf(0, nx - 2)); val ax = if (nx > 1) fx - ix else 0f
                    val b = (lum[y * w + x].coerceIn(0f, 1f) * (bins - 1)).toInt()
                    val c00 = cdf[iy * nx + ix][b]; val c10 = cdf[iy * nx + minOf(nx - 1, ix + 1)][b]
                    val c01 = cdf[minOf(ny - 1, iy + 1) * nx + ix][b]; val c11 = cdf[minOf(ny - 1, iy + 1) * nx + minOf(nx - 1, ix + 1)][b]
                    eq[y * w + x] = (c00 * (1 - ax) + c10 * ax) * (1 - ay) + (c01 * (1 - ax) + c11 * ax) * ay
                }
            }
        }
        for (c in 0 until out.channels) { val a = out.data[c]; for (i in a.indices) {
            val l = lum[i]; val target = l + (eq[i] - l) * amount
            val f = if (l > 1e-5f) target / l else 1f
            a[i] = (a[i] * f.coerceIn(0.2f, 5f)).coerceIn(0f, 1f)
        } }
        return out
    }

    // ---------------------------------------------------------------- wavelet (à trous) élesítés, 3 réteg
    fun wavelets(src: FloatImage, p: ProcessParams): FloatImage {
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val b1 = boxBlur(lum, w, h, 1)
        val b2 = boxBlur(b1, w, h, 2)
        val b3 = boxBlur(b2, w, h, 4)
        val gains = floatArrayOf(p.wavelet1, p.wavelet2, p.wavelet3)
        val thr = p.waveletDenoise * 0.02f
        val detail = FloatArray(lum.size)
        for (i in lum.indices) {
            var d1 = lum[i] - b1[i]
            if (thr > 0f) d1 = if (abs(d1) < thr) 0f else d1 - Math.signum(d1) * thr   // soft threshold a finom rétegen
            val d2 = b1[i] - b2[i]; val d3 = b2[i] - b3[i]
            detail[i] = d1 * gains[0] + d2 * gains[1] + d3 * gains[2] + (if (thr > 0f) (d1 - (lum[i] - b1[i])) else 0f)
        }
        val out = src
        for (c in 0 until out.channels) { val a = out.data[c]; for (i in a.indices) a[i] = (a[i] + detail[i]).coerceIn(0f, 1f) }
        return out
    }

    // ---------------------------------------------------------------- SCNR (zöld-elnyomás, average neutral)
    fun scnr(src: FloatImage, amount: Float): FloatImage {
        if (src.channels != 3) return src
        val out = src
        val r = out.data[0]; val g = out.data[1]; val b = out.data[2]
        for (i in 0 until out.size) {
            val m = (r[i] + b[i]) * 0.5f
            if (g[i] > m) g[i] = g[i] + (m - g[i]) * amount
        }
        return out
    }

    // ---------------------------------------------------------------- Richardson–Lucy dekonvolúció (Gauss PSF)
    fun deconvolve(src: FloatImage, sigma: Float, iterations: Int): FloatImage {
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val r = (sigma * 3f).toInt().coerceIn(1, 9)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2f * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        fun conv(a: FloatArray): FloatArray {
            val t = FloatArray(a.size); val o = FloatArray(a.size)
            hu.novaprocess.image.Par.rows(h) { y0, y1 -> for (y in y0 until y1) for (x in 0 until w) { var s = 0f; for (d in -r..r) s += a[y * w + (x + d).coerceIn(0, w - 1)] * k[d + r]; t[y * w + x] = s } }
            hu.novaprocess.image.Par.rows(h) { y0, y1 -> for (y in y0 until y1) for (x in 0 until w) { var s = 0f; for (d in -r..r) s += t[(y + d).coerceIn(0, h - 1) * w + x] * k[d + r]; o[y * w + x] = s } }
            return o
        }
        var est = lum.copyOf()
        val eps = 1e-5f
        repeat(iterations) {
            val blurred = conv(est)
            val ratio = FloatArray(lum.size) { lum[it] / (blurred[it] + eps) }
            val corr = conv(ratio)   // Gauss szimmetrikus → a tükrözött PSF ugyanaz
            for (i in est.indices) est[i] = (est[i] * corr[i]).coerceIn(0f, 1f)
        }
        // a luminancia-arányt visszük át a csatornákra (színmegőrző), csak a háttér fölötti struktúrákon
        val med = median(lum); val madv = mad(lum, med) * 1.4826f
        val lo = med + 2f * madv; val hi = med + 8f * madv
        val out = src
        for (c in 0 until out.channels) { val a = out.data[c]; for (i in a.indices) {
            val wgt = ((lum[i] - lo) / (hi - lo).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            if (wgt <= 0f) continue
            val f = if (lum[i] > eps) (est[i] / lum[i]).coerceIn(0.25f, 4f) else 1f
            a[i] = (a[i] * (1f + (f - 1f) * wgt)).coerceIn(0f, 1f)
        } }
        return out
    }

    // ---------------------------------------------------------------- HDR magkompresszió (helyi tónusleképezés)
    fun hdrCompress(src: FloatImage, amount: Float, radius: Int): FloatImage {
        val lum = src.toMono().data[0]
        val blur = boxBlur(lum, src.width, src.height, radius.coerceAtLeast(2))
        val out = src
        val k = amount * 3f
        for (c in 0 until out.channels) {
            val a = out.data[c]
            for (i in a.indices) {
                // a fényes, nagy kiterjedésű területeket (mag) nyomjuk le, a finom részletet megtartva
                val local = blur[i]
                val gain = (1f + k) / (1f + k * local)
                a[i] = (a[i] * gain * (1f - amount * 0.25f)).coerceIn(0f, 1f)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- csillagtalanítás + visszakeverés
    /**
     * Klasszikus (nem neurális) csillagtalanítás: morfológiai nyitás + a lyukak lágy kitöltése,
     * majd a csillagréteg = eredeti − csillagtalan. A csillagtalan rétegre extra stretch tehető
     * (köd kiemelése), a csillagok pedig tetszőleges arányban visszakeverhetők.
     */
    fun starlessComposite(src: FloatImage, p: ProcessParams): FloatImage {
        val w = src.width; val h = src.height
        val out = src
        val r = p.starlessRadius.coerceIn(2, 12)
        for (c in 0 until src.channels) {
            val a = src.data[c]
            val opened = maxFilter(minFilter(a, w, h, r), w, h, r)
            val filled = boxBlur(opened, w, h, r)   // lágyítás, hogy ne maradjon "lyuk" a csillag helyén
            val starless = FloatArray(a.size) { i -> min(a[i], max(opened[i], filled[i] * 0.5f + opened[i] * 0.5f)) }
            val o = out.data[c]
            for (i in a.indices) {
                var neb = starless[i]
                if (p.nebulaBoost > 0f) {
                    val k = 1f + p.nebulaBoost * 6f
                    neb = (asinh(k * neb) / asinh(k))
                }
                val star = (a[i] - starless[i]).coerceAtLeast(0f)
                o[i] = (neb + star * p.starsBlend).coerceIn(0f, 1f)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- narrowband paletták (dual-band szűrős OSC képekhez)
    fun palette(src: FloatImage, mode: Palette, strength: Float): FloatImage {
        if (src.channels != 3) return src
        val out = src
        val r = out.data[0]; val g = out.data[1]; val b = out.data[2]
        for (i in 0 until out.size) {
            val ha = r[i]                       // dual-band OSC: R ≈ Hα, G+B ≈ OIII
            val o3 = (g[i] + b[i]) * 0.5f
            val (nr, ng, nb) = when (mode) {
                Palette.HOO -> Triple(ha, o3, o3)
                Palette.SHO_LIKE -> Triple(ha * 0.6f + o3 * 0.4f, ha, o3)           // Hubble-szerű arany/kék
                Palette.FORAXX -> { val mix = ha * o3; Triple(ha, (o3 * o3 * ha + mix * (1f - o3)).coerceIn(0f, 1f), o3) }
                Palette.NATURAL -> Triple(r[i], g[i], b[i])
            }
            r[i] = r[i] + (nr - r[i]) * strength
            g[i] = g[i] + (ng - g[i]) * strength
            b[i] = b[i] + (nb - b[i]) * strength
        }
        return out
    }

    // ---------------------------------------------------------------- görbék (3 vezérlőpont, monoton spline)
    fun curves(src: FloatImage, p: ProcessParams): FloatImage {
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val ys = floatArrayOf(0f, (0.25f + p.curveShadows * 0.2f).coerceIn(0f, 1f), (0.5f + p.curveMidtones * 0.25f).coerceIn(0f, 1f), (0.75f + p.curveHighlights * 0.2f).coerceIn(0f, 1f), 1f)
        val lut = FloatArray(4096) { monotoneSpline(xs, ys, it / 4095f) }
        val out = src
        if (p.curveLuminanceOnly && src.channels == 3) {
            val r = out.data[0]; val g = out.data[1]; val b = out.data[2]
            for (i in 0 until out.size) {
                val l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                val nl = lut[(l.coerceIn(0f, 1f) * 4095f).toInt()]
                val f = if (l > 1e-5f) nl / l else 1f
                r[i] = (r[i] * f).coerceIn(0f, 1f); g[i] = (g[i] * f).coerceIn(0f, 1f); b[i] = (b[i] * f).coerceIn(0f, 1f)
            }
        } else for (c in 0 until out.channels) { val a = out.data[c]; for (i in a.indices) a[i] = lut[(a[i].coerceIn(0f, 1f) * 4095f).toInt()] }
        return out
    }

    /** Fritsch–Carlson monoton köbös interpoláció. */
    private fun monotoneSpline(xs: FloatArray, ys: FloatArray, x: Float): Float {
        val n = xs.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val s = a * a + b * b
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        var i = 0; while (i < n - 2 && x > xs[i + 1]) i++
        val hh = xs[i + 1] - xs[i]; val t = ((x - xs[i]) / hh).coerceIn(0f, 1f)
        val t2 = t * t; val t3 = t2 * t
        return ((2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * hh * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * hh * m[i + 1]).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- maszk
    fun mask(img: FloatImage, p: ProcessParams): FloatArray {
        val lum = boxBlur(img.toMono().data[0], img.width, img.height, 2)
        val f = p.maskFeather.coerceAtLeast(0.01f)
        val m = FloatArray(lum.size) { i ->
            val l = lum[i]
            val v = when (p.maskMode) {
                MaskMode.HIGHLIGHTS -> l
                MaskMode.SHADOWS -> 1f - l
                MaskMode.RANGE -> smooth01((l - (p.maskLow - f)) / f) * (1f - smooth01((l - p.maskHigh) / f))
                MaskMode.NONE -> 1f
            }
            if (p.maskInvert) 1f - v else v
        }
        return m
    }
    private fun smooth01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun blendByMask(base: FloatImage, adjusted: FloatImage, p: ProcessParams): FloatImage {
        val m = mask(base, p)
        val out = adjusted
        for (c in 0 until out.channels) { val a = out.data[c]; val b = base.data[c]; for (i in a.indices) a[i] = b[i] + (a[i] - b[i]) * m[i] }
        return out
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
        val out = src
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

    /**
     * GraXpert-szerű háttér: mintapontokban (kézi vagy automatikus rács, a fényes területek kihagyásával)
     * mért háttérértékekre gaussi RBF-et illesztünk (regularizálva), és ezt vonjuk ki.
     */
    fun backgroundRbf(src: FloatImage, samplesNorm: List<Float>, smooth: Float): FloatImage {
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val med = median(lum); val madv = mad(lum, med) * 1.4826f
        // mintapontok képkoordinátában
        val pts = ArrayList<Pair<Int, Int>>()
        if (samplesNorm.size >= 6) {
            var i = 0; while (i + 1 < samplesNorm.size) { pts.add(Pair((samplesNorm[i] * (w - 1)).toInt().coerceIn(0, w - 1), (samplesNorm[i + 1] * (h - 1)).toInt().coerceIn(0, h - 1))); i += 2 }
        } else {
            val g = 9
            for (gy in 0 until g) for (gx in 0 until g) {
                val x = ((gx + 0.5f) * w / g).toInt(); val y = ((gy + 0.5f) * h / g).toInt()
                // kihagyjuk a fényes (objektum/csillag) cellákat
                val v = boxMedian(lum, w, h, x, y, 6)
                if (v < med + 2.5f * madv) pts.add(Pair(x, y))
            }
            if (pts.size < 6) return src
        }
        val n = pts.size
        val box = (minOf(w, h) / 60).coerceIn(3, 12)
        val eps = (1.2f / maxOf(w, h)) * (1f + smooth * 3f)       // RBF szélesség (normalizált térben)
        val lambda = 1e-3f + smooth * 1e-2f                          // regularizáció
        val px = FloatArray(n) { pts[it].first.toFloat() / w }; val py = FloatArray(n) { pts[it].second.toFloat() / h }
        // Gram-mátrix (azonos minden csatornára)
        val A = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) { val dx = px[i] - px[j]; val dy = py[i] - py[j]; A[i][j] = Math.exp(-((dx * dx + dy * dy) / (2.0 * eps * eps)).toDouble() * 50.0) + if (i == j) lambda.toDouble() else 0.0 }
        val lu = luDecompose(A) ?: return src
        for (c in 0 until src.channels) {
            val a = src.data[c]
            val b = DoubleArray(n) { boxMedian(a, w, h, pts[it].first, pts[it].second, box).toDouble() }
            val coef = luSolve(lu, b)
            val cmed = median(a)
            // háttérfelület kiértékelése ritkított rácson, majd bilineáris felskálázás (sebesség)
            val gw = 64; val gh = (64 * h / w).coerceAtLeast(8)
            val grid = FloatArray(gw * gh)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val x = (gx + 0.5f) / gw; val y = (gy + 0.5f) / gh
                var v = 0.0
                for (k in 0 until n) { val dx = x - px[k]; val dy = y - py[k]; v += coef[k] * Math.exp(-((dx * dx + dy * dy) / (2.0 * eps * eps)).toDouble() * 50.0) }
                grid[gy * gw + gx] = v.toFloat()
            }
            hu.novaprocess.image.Par.rows(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val fy = ((y + 0.5f) / h * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat()); val iy = fy.toInt().coerceAtMost(gh - 2); val ty = fy - iy
                    for (x in 0 until w) {
                        val fx = ((x + 0.5f) / w * gw - 0.5f).coerceIn(0f, (gw - 1).toFloat()); val ix = fx.toInt().coerceAtMost(gw - 2); val tx = fx - ix
                        val bg = (grid[iy * gw + ix] * (1 - tx) + grid[iy * gw + ix + 1] * tx) * (1 - ty) + (grid[(iy + 1) * gw + ix] * (1 - tx) + grid[(iy + 1) * gw + ix + 1] * tx) * ty
                        val i = y * w + x
                        a[i] = (a[i] - bg + cmed).coerceAtLeast(0f)
                    }
                }
            }
        }
        return src
    }

    private fun boxMedian(a: FloatArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Float {
        val list = ArrayList<Float>((2 * r + 1) * (2 * r + 1))
        for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(h - 1)) for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(w - 1)) list.add(a[y * w + x])
        list.sort(); return list[list.size / 2]
    }

    private fun luDecompose(A: Array<DoubleArray>): Pair<Array<DoubleArray>, IntArray>? {
        val n = A.size; val lu = Array(n) { A[it].copyOf() }; val piv = IntArray(n) { it }
        for (k in 0 until n) {
            var p = k; for (i in k + 1 until n) if (abs(lu[i][k]) > abs(lu[p][k])) p = i
            if (abs(lu[p][k]) < 1e-12) return null
            if (p != k) { val t = lu[p]; lu[p] = lu[k]; lu[k] = t; val tp = piv[p]; piv[p] = piv[k]; piv[k] = tp }
            for (i in k + 1 until n) { lu[i][k] /= lu[k][k]; for (j in k + 1 until n) lu[i][j] -= lu[i][k] * lu[k][j] }
        }
        return Pair(lu, piv)
    }
    private fun luSolve(d: Pair<Array<DoubleArray>, IntArray>, b: DoubleArray): DoubleArray {
        val (lu, piv) = d; val n = b.size; val x = DoubleArray(n) { b[piv[it]] }
        for (i in 0 until n) for (j in 0 until i) x[i] -= lu[i][j] * x[j]
        for (i in n - 1 downTo 0) { for (j in i + 1 until n) x[i] -= lu[i][j] * x[j]; x[i] /= lu[i][i] }
        return x
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
        val out = src
        // a háttér = az alsó 30. percentilis csatornánként (nagy objektumnál a medián már a ködöt mérné)
        val meds = FloatArray(3) { percentile(out.data[it], 0.3f) }
        val target = meds.average().toFloat()
        for (c in 0 until 3) { val a = out.data[c]; val d = target - meds[c]; for (i in a.indices) a[i] = (a[i] + d).coerceAtLeast(0f) }
        return out
    }

    /** Fehéregyensúly a fényes (de nem telített) csillagokra: csatorna-átlagok kiegyenlítése. */
    fun whiteBalanceOnStars(src: FloatImage): FloatImage {
        if (src.channels != 3) return src
        val out = src
        val lum = src.toMono().data[0]
        val sorted = FloatArray(min(lum.size, 50000)) { lum[(it.toLong() * lum.size / min(lum.size, 50000)).toInt()] }
        sorted.sort()
        val lo = sorted[(sorted.size * 0.995f).toInt()]
        val sums = FloatArray(3); var n = 0
        for (i in lum.indices) if (lum[i] > lo && lum[i] < 0.95f) { for (c in 0 until 3) sums[c] += src.data[c][i]; n++ }
        if (n < 10) return out
        val mean = sums.average().toFloat()
        // erősítés = átlagos csatornaösszeg / csatornaösszeg (1 körüli érték), biztonsági korláttal
        for (c in 0 until 3) { val g = (mean / sums[c].coerceAtLeast(1e-6f)).coerceIn(0.5f, 2f); val a = out.data[c]; for (i in a.indices) a[i] *= g }
        return out
    }

    // ---------------------------------------------------------------- stretch
    fun stretch(src: FloatImage, p: ProcessParams): FloatImage {
        val out = src
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
        // MTF inverz: m, amelyre mtf(m, x) = target.  (m-1)x = t((2m-1)x - m)  →  m = x(1-t) / (x - 2tx + t)
        val den = x - 2f * target * x + target
        return if (abs(den) < 1e-9f) 0.5f else (x * (1f - target) / den).coerceIn(1e-6f, 0.999999f)
    }

    // ---------------------------------------------------------------- csillagcsökkentés
    /** Morfológiai nyitás (min majd max) adja a "csillagtalan" közelítést; a csillagokat ennek arányában keverjük vissza. */
    fun reduceStars(src: FloatImage, amount: Float, radius: Int): FloatImage {
        val out = src
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

    // ---------------------------------------------------------------- színzaj-szűrés (chroma)
    /** A luminanciát érintetlenül hagyja; a csatornák luminanciához viszonyított eltérését (színt) simítja. */
    fun chromaDenoise(src: FloatImage, amount: Float, radius: Int): FloatImage {
        if (src.channels != 3) return src
        val w = src.width; val h = src.height; val n = src.size
        val lum = src.toMono().data[0]
        val out = src
        for (c in 0 until 3) {
            val a = out.data[c]
            val diff = FloatArray(n) { a[it] - lum[it] }
            val sm = boxBlur(boxBlur(diff, w, h, radius), w, h, radius)   // kétszeres box ≈ gauss
            for (i in 0 until n) a[i] = (lum[i] + diff[i] + (sm[i] - diff[i]) * amount).coerceIn(0f, 1f)
        }
        return out
    }

    // ---------------------------------------------------------------- zajszűrés
    /** Élmegőrző (bilaterális jellegű) simítás, a sötét, zajos területeken erősebb. */
    fun denoise(src: FloatImage, amount: Float, radius: Int): FloatImage {
        val out = src   // helyben, csatornánkénti másolattal (export memóriacsúcs csökkentése)
        val w = src.width; val h = src.height
        val lum = src.toMono().data[0]
        val sigmaR = 0.02f + 0.12f * amount
        val inv2s = 1f / (2f * sigmaR * sigmaR)
        for (c in 0 until src.channels) {
            val a = src.data[c].copyOf(); val o = out.data[c]
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

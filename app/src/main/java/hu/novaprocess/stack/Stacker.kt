package hu.novaprocess.stack

import android.content.Context
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

enum class StackMethod { MEAN, SIGMA_CLIP, WINSORIZED }

data class StackOptions(
    val method: StackMethod = StackMethod.SIGMA_CLIP,
    val sigmaLow: Float = 3f,
    val sigmaHigh: Float = 3f,
    val binning: Int = 1,              // 1 = teljes felbontás, 2 = fél (gyors, kevesebb memória)
    val minMatches: Int = 6,           // ennél kevesebb csillag-egyezéssel a kocka kimarad
    val alignFrames: Boolean = true,
    val darkUris: List<Uri> = emptyList(),
    val flatUris: List<Uri> = emptyList(),
    val biasUris: List<Uri> = emptyList()
)

data class StackProgress(val phase: String, val current: Int, val total: Int, val detail: String = "")

data class StackResult(val image: FloatImage, val used: Int, val rejected: Int, val log: List<String>)

/**
 * Két menetes stackelés, hogy a memória ne szaladjon el 8 MP-es kockáknál:
 *  1. menet: kalibrálás, regisztráció, futó átlag + szórás (Welford)
 *  2. menet (sigma-clip esetén): a kiugró pixelek kihagyásával újraátlagolás.
 */
object Stacker {

    suspend fun stack(
        context: Context,
        frames: List<Uri>,
        opts: StackOptions,
        onProgress: (StackProgress) -> Unit
    ): StackResult = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "Nincs kocka kiválasztva" }
        val log = ArrayList<String>()

        // --- mesterkockák ---
        val dark = buildMaster(context, opts.darkUris, opts.binning, "Dark") { onProgress(it) }
        val flat = buildMaster(context, opts.flatUris, opts.binning, "Flat") { onProgress(it) }
        val bias = buildMaster(context, opts.biasUris, opts.binning, "Bias") { onProgress(it) }
        val flatNorm = flat?.let { f ->
            val out = f.copy()
            for (c in 0 until out.channels) {
                val a = out.data[c]
                val mean = a.average().toFloat().coerceAtLeast(1e-6f)
                for (i in a.indices) a[i] = (a[i] / mean).coerceAtLeast(0.05f)
            }
            out
        }
        if (dark != null) log += "Master dark: ${opts.darkUris.size} kocka"
        if (flat != null) log += "Master flat: ${opts.flatUris.size} kocka"
        if (bias != null) log += "Master bias: ${opts.biasUris.size} kocka"

        // --- referencia ---
        onProgress(StackProgress("Referencia betöltése", 0, frames.size))
        val ref = loadCalibrated(context, frames[0], opts.binning, dark, flatNorm, bias)
        val w = ref.width; val h = ref.height; val ch = ref.channels
        val refStars = if (opts.alignFrames) StarDetector.detect(ref) else emptyList()
        log += "Referencia: ${w}×${h}, ${refStars.size} csillag"

        // --- 1. menet: Welford ---
        val mean = Array(ch) { FloatArray(w * h) }
        val m2 = Array(ch) { FloatArray(w * h) }
        val count = IntArray(w * h)
        val transforms = ArrayList<Transform?>()
        var used = 0; var rejected = 0

        for ((idx, uri) in frames.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(StackProgress("1. menet · igazítás", idx + 1, frames.size, ImageLoader.displayName(context, uri)))
            val img = if (idx == 0) ref else loadCalibrated(context, uri, opts.binning, dark, flatNorm, bias)
            if (img.width != w || img.height != h || img.channels != ch) {
                log += "Kihagyva (eltérő méret): ${ImageLoader.displayName(context, uri)}"
                transforms.add(null); rejected++; continue
            }
            val tf = when {
                !opts.alignFrames || idx == 0 -> Transform(0f, 0f, 0f, Int.MAX_VALUE)
                else -> Aligner.estimate(refStars, StarDetector.detect(img), w, h)
            }
            if (tf.matches < opts.minMatches) {
                log += "Elvetve (${tf.matches} egyezés): ${ImageLoader.displayName(context, uri)}"
                transforms.add(null); rejected++; continue
            }
            transforms.add(tf)
            val reg = if (tf.angle == 0f && tf.dx == 0f && tf.dy == 0f) img else Aligner.warp(img, tf)
            accumulateWelford(reg, mean, m2, count)
            used++
        }
        if (used == 0) throw IllegalStateException("Egyetlen kocka sem igazítható a referenciához")

        val result = FloatImage(w, h, ch)
        if (opts.method == StackMethod.MEAN || used < 3) {
            for (c in 0 until ch) System.arraycopy(mean[c], 0, result.data[c], 0, w * h)
        } else {
            // szórás a Welford M2-ből
            val sigma = Array(ch) { c -> FloatArray(w * h) { i -> if (count[i] > 1) sqrt(m2[c][i] / (count[i] - 1)) else 0f } }
            val sum = Array(ch) { FloatArray(w * h) }
            val n = IntArray(w * h)
            // --- 2. menet ---
            for ((idx, uri) in frames.withIndex()) {
                coroutineContext.ensureActive()
                val tf = transforms[idx] ?: continue
                onProgress(StackProgress("2. menet · sigma-clip", idx + 1, frames.size, ImageLoader.displayName(context, uri)))
                val img = loadCalibrated(context, uri, opts.binning, dark, flatNorm, bias)
                val reg = if (tf.angle == 0f && tf.dx == 0f && tf.dy == 0f) img else Aligner.warp(img, tf)
                for (c in 0 until ch) {
                    val a = reg.data[c]; val mu = mean[c]; val sg = sigma[c]; val s = sum[c]
                    for (i in 0 until w * h) {
                        val v = a[i]
                        if (v == 0f && idx != 0 && (tf.dx != 0f || tf.dy != 0f || tf.angle != 0f)) continue // warp-szél
                        val d = v - mu[i]
                        val lim = if (d < 0) opts.sigmaLow * sg[i] else opts.sigmaHigh * sg[i]
                        if (opts.method == StackMethod.SIGMA_CLIP) {
                            if (kotlin.math.abs(d) <= lim || sg[i] == 0f) { s[i] += v; if (c == 0) n[i]++ }
                        } else { // winsorized: a kiugrót a határra húzzuk
                            val vv = if (d > lim) mu[i] + lim else if (d < -lim) mu[i] - lim else v
                            s[i] += vv; if (c == 0) n[i]++
                        }
                    }
                }
            }
            for (c in 0 until ch) {
                val s = sum[c]; val r = result.data[c]
                for (i in 0 until w * h) r[i] = if (n[i] > 0) s[i] / n[i] else mean[c][i]
            }
        }
        log += "Stack kész: $used kocka felhasználva, $rejected elvetve"
        onProgress(StackProgress("Kész", frames.size, frames.size))
        StackResult(result, used, rejected, log)
    }

    private fun accumulateWelford(img: FloatImage, mean: Array<FloatArray>, m2: Array<FloatArray>, count: IntArray) {
        val n = img.size
        for (i in 0 until n) count[i]++
        for (c in 0 until img.channels) {
            val a = img.data[c]; val mu = mean[c]; val s = m2[c]
            for (i in 0 until n) {
                val v = a[i]
                val k = count[i]
                val delta = v - mu[i]
                mu[i] += delta / k
                s[i] += delta * (v - mu[i])
            }
        }
    }

    private fun loadCalibrated(
        context: Context, uri: Uri, binning: Int,
        dark: FloatImage?, flatNorm: FloatImage?, bias: FloatImage?
    ): FloatImage {
        var img = ImageLoader.load(context, uri)
        if (binning > 1) img = img.downscale(maxOf(img.width, img.height) / binning)
        if (dark != null && dark.width == img.width && dark.channels == img.channels) subtract(img, dark)
        else if (bias != null && bias.width == img.width && bias.channels == img.channels) subtract(img, bias)
        if (flatNorm != null && flatNorm.width == img.width && flatNorm.channels == img.channels) divide(img, flatNorm)
        return img
    }

    private fun subtract(a: FloatImage, b: FloatImage) {
        for (c in 0 until a.channels) { val x = a.data[c]; val y = b.data[c]; for (i in x.indices) x[i] = (x[i] - y[i]).coerceAtLeast(0f) }
    }
    private fun divide(a: FloatImage, b: FloatImage) {
        for (c in 0 until a.channels) { val x = a.data[c]; val y = b.data[c]; for (i in x.indices) x[i] = (x[i] / y[i]).coerceIn(0f, 1f) }
    }

    /** Mesterkocka: egyszerű átlag (memóriakímélő). */
    private fun buildMaster(context: Context, uris: List<Uri>, binning: Int, label: String, onProgress: (StackProgress) -> Unit): FloatImage? {
        if (uris.isEmpty()) return null
        var acc: FloatImage? = null
        for ((i, u) in uris.withIndex()) {
            onProgress(StackProgress("Master $label", i + 1, uris.size))
            var img = ImageLoader.load(context, u)
            if (binning > 1) img = img.downscale(maxOf(img.width, img.height) / binning)
            if (acc == null) acc = img
            else for (c in 0 until img.channels) { val x = acc.data[c]; val y = img.data[c]; for (k in x.indices) x[k] += y[k] }
        }
        val out = acc!!
        val inv = 1f / uris.size
        for (c in 0 until out.channels) { val x = out.data[c]; for (k in x.indices) x[k] *= inv }
        return out
    }
}

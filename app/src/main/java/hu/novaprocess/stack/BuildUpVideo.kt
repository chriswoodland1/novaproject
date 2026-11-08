package hu.novaprocess.stack

import android.content.Context
import android.net.Uri
import hu.novaprocess.image.FitsReader
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import hu.novaprocess.video.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * „Így épült fel” – jelenetek a sub-okból: a kockák időrendben, kicsinyítve (≤ 1080 px) regisztrálva és futó átlagba
 * gyűjtve; ellenőrzőpontokon (1, 2, 3, 5, 8, 12, 18, 25, 35, 50, 70, 100 …) a részleges stack a JELENLEGI szerkesztési
 * beállításokkal, a lineáris égzaj méréssel (a grafikonhoz).
 */
object BuildUpVideo {
    data class Progress(val phase: String, val current: Int, val total: Int)

    fun checkpoints(n: Int): List<Int> {
        val out = ArrayList<Int>(); var k = 1.0
        while (k < n) { val v = k.toInt(); if (out.isEmpty() || v > out.last()) out += v; k = if (k < 3) k + 1 else k * 1.42 }
        if (out.isEmpty() || out.last() != n) out += n
        return out
    }

    suspend fun scenes(ctx: Context, frames: List<Uri>, exposureSec: Float, params: ProcessParams, maxSide: Int = 1080,
                       onProgress: (Progress) -> Unit): List<Scene> = withContext(Dispatchers.Default) {
        require(frames.size >= 2) { "Legalább 2 kocka kell" }
        val cps = checkpoints(frames.size).toSet()
        var ref: FloatImage? = null; var refStars: List<Star> = emptyList()
        var sum: Array<FloatArray>? = null; var cnt: IntArray? = null
        var used = 0
        val out = ArrayList<Scene>()
        for ((i, u) in frames.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(Progress("Kockák", i + 1, frames.size))
            val img = runCatching { loadSmall(ctx, u, maxSide) }.getOrNull() ?: continue
            if (ref == null) { ref = img; refStars = StarDetector.detect(img); sum = Array(img.channels) { FloatArray(img.size) }; cnt = IntArray(img.size) }
            val r = ref!!
            if (img.width != r.width || img.height != r.height || img.channels != r.channels) continue
            val reg = if (i == 0) img else {
                val tf = Aligner.estimate(refStars, StarDetector.detect(img), r.width, r.height)
                if (tf.matches < 6) continue
                Aligner.warp(img, tf)
            }
            val s = sum!!; val c = cnt!!
            for (ch in 0 until reg.channels) { val a = reg.data[ch]; val t = s[ch]; for (k in a.indices) if (a[k] != 0f || i == 0) { t[k] += a[k]; if (ch == 0) c[k]++ } }
            used++
            if ((i + 1) in cps || i == frames.lastIndex) {
                val avg = FloatImage(r.width, r.height, r.channels)
                for (ch in 0 until r.channels) { val t = s[ch]; val o = avg.data[ch]; for (k in o.indices) o[k] = if (c[k] > 0) t[k] / c[k] else 0f }
                val noise = runCatching { Processor.noiseSigma(avg) }.getOrNull()
                val bmp = Processor.process(avg, params).toBitmap()
                val tot = used * exposureSec
                out += Scene(bmp, "$used kocka", if (exposureSec > 0f) (if (tot >= 3600) "%.1f óra összidő".format(tot / 3600) else "%.0f perc összidő".format(tot / 60)) else "", noise)
            }
            if (i % 8 == 7) System.gc()
        }
        require(out.isNotEmpty()) { "Egyetlen kocka sem volt igazítható" }
        out
    }

    /** Gyors, kicsinyített betöltés: FITS Bayer → szuperpixel; más formátum → teljes betöltés + kicsinyítés. */
    fun loadSmall(ctx: Context, uri: Uri, maxSide: Int): FloatImage {
        val name = ImageLoader.displayName(ctx, uri)
        if (ImageLoader.isFits(name)) {
            ctx.contentResolver.openInputStream(uri).use { ins ->
                val (img, cards, raw) = FitsReader.readRawOrImage(requireNotNull(ins))
                return if (raw != null) FrameAnalyzer.superpixel(raw.third, raw.first, raw.second, (cards["BAYERPAT"] ?: "GRBG").uppercase()).downscale(maxSide)
                       else img!!.downscale(maxSide)
            }
        }
        return ImageLoader.load(ctx, uri, name).downscale(maxSide)
    }
}

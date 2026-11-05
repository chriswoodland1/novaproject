package hu.novaprocess.planetary

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.image.Par
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

data class PlanetaryOptions(
    val maxFrames: Int = 200,       // videóból ennyi kockát veszünk egyenletesen
    val keepPercent: Int = 30,      // a legjobb X% kerül a stackbe
    val searchRadius: Int = 12,     // finom igazítás keresési sugara (px, a munkafelbontáson)
    val workSide: Int = 1200        // munkafelbontás (hosszabb oldal)
)

data class PlanetaryProgress(val phase: String, val current: Int, val total: Int)

data class PlanetaryResult(val image: FloatImage, val used: Int, val total: Int, val log: List<String>)

/**
 * Lucky imaging bolygóra / Holdra / Napra: kockák kinyerése videóból (MP4/MOV/3GP) vagy képsorozatból,
 * élesség (Laplace-variancia) szerinti rangsor, súlypont + SAD-finomításos igazítás, a legjobb X% átlaga.
 * Az élesítés (wavelet, dekonvolúció) a szerkesztőben történik.
 */
object Planetary {

    suspend fun stackVideo(context: Context, video: Uri, opts: PlanetaryOptions, onProgress: (PlanetaryProgress) -> Unit): PlanetaryResult =
        withContext(Dispatchers.Default) {
            val mmr = MediaMetadataRetriever()
            val frames = ArrayList<FloatImage>()
            try {
                mmr.setDataSource(context, video)
                val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                require(durationMs > 0) { "A videó hossza nem olvasható" }
                val n = opts.maxFrames.coerceAtLeast(5)
                for (i in 0 until n) {
                    coroutineContext.ensureActive()
                    onProgress(PlanetaryProgress("Kockák kinyerése", i + 1, n))
                    val tUs = (durationMs * 1000L * i) / n
                    val bmp: Bitmap = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    var img = FloatImage.fromBitmap(bmp); bmp.recycle()
                    img = img.downscale(opts.workSide)
                    frames.add(img)
                }
            } finally { runCatching { mmr.release() } }
            stackFrames(frames, opts, onProgress)
        }

    suspend fun stackImages(context: Context, uris: List<Uri>, opts: PlanetaryOptions, onProgress: (PlanetaryProgress) -> Unit): PlanetaryResult =
        withContext(Dispatchers.Default) {
            val frames = ArrayList<FloatImage>()
            for ((i, u) in uris.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(PlanetaryProgress("Kockák betöltése", i + 1, uris.size))
                runCatching { frames.add(ImageLoader.load(context, u).downscale(opts.workSide)) }
            }
            stackFrames(frames, opts, onProgress)
        }

    private suspend fun stackFrames(frames: List<FloatImage>, opts: PlanetaryOptions, onProgress: (PlanetaryProgress) -> Unit): PlanetaryResult {
        require(frames.size >= 2) { "Legalább 2 használható kocka kell" }
        val log = ArrayList<String>()
        val w = frames[0].width; val h = frames[0].height; val ch = frames[0].channels
        val valid = frames.filter { it.width == w && it.height == h && it.channels == ch }

        // --- minőség: Laplace-variancia a fényes (objektum) területen ---
        val scored = valid.mapIndexed { i, f ->
            onProgress(PlanetaryProgress("Élesség értékelése", i + 1, valid.size))
            Pair(f, sharpness(f.toMono().data[0], w, h))
        }.sortedByDescending { it.second }
        val keep = (valid.size * opts.keepPercent / 100).coerceIn(1, valid.size)
        val best = scored.take(keep).map { it.first }
        log += "${valid.size} kocka, a legjobb $keep (${opts.keepPercent}%) kerül a stackbe"

        // --- igazítás a legélesebb kockához: súlypont, majd SAD-finomítás ---
        val ref = best[0]
        val refLum = ref.toMono().data[0]
        val (rcx, rcy) = centroid(refLum, w, h)
        val refSmall = downscaleArr(refLum, w, h, 4)
        val acc = FloatImage(w, h, ch)
        val cnt = IntArray(w * h)
        for ((i, f) in best.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(PlanetaryProgress("Igazítás és stack", i + 1, best.size))
            val lum = f.toMono().data[0]
            val (cx, cy) = centroid(lum, w, h)
            var dx = Math.round(rcx - cx); var dy = Math.round(rcy - cy)
            // finomítás: ±searchRadius a 4× kicsinyített képen (gyors), majd ±2 teljes felbontáson
            val small = downscaleArr(lum, w, h, 4)
            val (sdx, sdy) = bestShift(refSmall, small, w / 4, h / 4, dx / 4, dy / 4, opts.searchRadius / 4 + 1)
            dx = sdx * 4; dy = sdy * 4
            val (fdx, fdy) = bestShift(refLum, lum, w, h, dx, dy, 2)
            accumulate(acc, cnt, f, fdx, fdy)
        }
        for (c in 0 until ch) { val a = acc.data[c]; for (i in a.indices) if (cnt[i] > 0) a[i] /= cnt[i] }
        log += "Kész: ${acc.width}×${acc.height}"
        return PlanetaryResult(acc, keep, valid.size, log)
    }

    private fun sharpness(a: FloatArray, w: Int, h: Int): Float {
        // csak a fényes régió (objektum) számít: küszöb a medián fölött
        var sum = 0.0; var sum2 = 0.0; var n = 0
        val thr = 0.08f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (a[i] < thr) continue
            val lap = 4f * a[i] - a[i - 1] - a[i + 1] - a[i - w] - a[i + w]
            sum += lap; sum2 += lap * lap; n++
        }
        if (n < 50) return 0f
        val mean = sum / n
        return (sum2 / n - mean * mean).toFloat()
    }

    private fun centroid(a: FloatArray, w: Int, h: Int): Pair<Float, Float> {
        var sx = 0.0; var sy = 0.0; var s = 0.0
        val thr = 0.08f
        for (y in 0 until h) for (x in 0 until w) { val v = a[y * w + x]; if (v > thr) { sx += v * x; sy += v * y; s += v } }
        return if (s > 0) Pair((sx / s).toFloat(), (sy / s).toFloat()) else Pair(w / 2f, h / 2f)
    }

    private fun downscaleArr(a: FloatArray, w: Int, h: Int, f: Int): FloatArray {
        val nw = w / f; val nh = h / f
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += a[(y * f + dy) * w + x * f + dx]
            out[y * nw + x] = s / (f * f)
        }
        return out
    }

    /** A (dx,dy) körül ±r eltolással keresi a legkisebb abszolút eltérést a referenciához. */
    private fun bestShift(ref: FloatArray, img: FloatArray, w: Int, h: Int, dx0: Int, dy0: Int, r: Int): Pair<Int, Int> {
        var best = Float.MAX_VALUE; var bx = dx0; var by = dy0
        val step = 2
        for (dy in -r..r) for (dx in -r..r) {
            val sx = dx0 + dx; val sy = dy0 + dy
            var sad = 0f; var n = 0
            var y = maxOf(0, -sy); while (y < minOf(h, h - sy)) {
                var x = maxOf(0, -sx); while (x < minOf(w, w - sx)) {
                    sad += abs(ref[(y + sy) * w + x + sx] - img[y * w + x]); n++; x += step
                }
                y += step
            }
            if (n > 0) { val v = sad / n; if (v < best) { best = v; bx = sx; by = sy } }
        }
        return Pair(bx, by)
    }

    private fun accumulate(acc: FloatImage, cnt: IntArray, f: FloatImage, dx: Int, dy: Int) {
        val w = acc.width; val h = acc.height
        Par.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val sy = y - dy; if (sy < 0 || sy >= h) continue
                for (x in 0 until w) {
                    val sx = x - dx; if (sx < 0 || sx >= w) continue
                    val i = y * w + x; val j = sy * w + sx
                    for (c in 0 until acc.channels) acc.data[c][i] += f.data[c][j]
                    cnt[i]++
                }
            }
        }
    }
}

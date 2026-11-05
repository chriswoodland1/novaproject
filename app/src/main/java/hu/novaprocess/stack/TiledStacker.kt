package hu.novaprocess.stack

import android.content.Context
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.image.Par
import hu.novaprocess.image.RawFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Teljes felbontású stack sávokban: a kimenetet vízszintes sávokra bontjuk, és minden sávhoz
 * csak a szükséges forrássorokat debayereljük. A kalibráció a nyers Bayer-síkon történik.
 * Memóriaigény ~ egy nyers kocka + egy sáv gyűjtői, a kocka felbontásától szinte függetlenül.
 */
object TiledStacker {

    /** A teljes felbontású út akkor ajánlott, ha a memóriakeret legalább ennyi. */
    const val MIN_HEAP_MB = 320

    suspend fun stack(context: Context, frames: List<Uri>, opts: StackOptions, onProgress: (StackProgress) -> Unit): StackResult =
        withContext(Dispatchers.Default) {
            val log = ArrayList<String>()
            val total = frames.size

            // --- nyers mesterkockák (Bayer-síkon) ---
            fun master(uris: List<Uri>, label: String): FloatArray? {
                if (uris.isEmpty()) return null
                var acc: FloatArray? = null; var n = 0
                for ((i, u) in uris.withIndex()) {
                    onProgress(StackProgress("Master $label", i + 1, uris.size))
                    val rf = RawFrame.load(context, u)
                    val plane = rf.bayer ?: rf.rgbRows(0, rf.height).toMono().data[0]
                    if (acc == null) acc = plane.copyOf() else if (acc!!.size == plane.size) for (k in plane.indices) acc!![k] += plane[k]
                    n++; rf.close()
                }
                acc?.let { a -> for (k in a.indices) a[k] /= n }
                log += "Master $label: $n kocka"
                return acc
            }
            val dark = master(opts.darkUris, "dark")
            val flatNorm = master(opts.flatUris, "flat")?.also { f -> val m = f.average().toFloat().coerceAtLeast(1e-6f); for (i in f.indices) f[i] = (f[i] / m).coerceAtLeast(0.05f) }
            val bias = if (dark == null) master(opts.biasUris, "bias") else null
            val hot: BooleanArray? = if (opts.hotPixelFix && dark != null) {
                val med = hu.novaprocess.process.Processor.median(dark); val mad = hu.novaprocess.process.Processor.mad(dark, med).coerceAtLeast(1e-6f)
                val thr = med + 6f * mad * 1.4826f
                BooleanArray(dark.size) { dark[it] > thr }.also { m -> log += "Hot pixel: ${m.count { it }}" }
            } else null

            fun loadCal(u: Uri): RawFrame = RawFrame.load(context, u).also { it.calibrate(dark ?: bias, flatNorm, hot) }

            // --- referencia + regisztráció (2× binnelt luminancián, gyors) ---
            onProgress(StackProgress("Referencia", 0, total))
            val ref = loadCal(frames[0])
            val w = ref.width; val h = ref.height
            val refLum = ref.luminanceBinned()
            val refStars = if (opts.alignFrames) StarDetector.detect(refLum) else emptyList()
            log += "Teljes felbontás: ${w}×${h}, ${refStars.size} csillag (referencia)"
            ref.close()

            val transforms = arrayOfNulls<Transform>(total)
            var used = 0; var rejected = 0
            for ((i, u) in frames.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(StackProgress("Regisztráció", i + 1, total, ImageLoader.displayName(context, u)))
                if (!opts.alignFrames || i == 0) { transforms[i] = Transform(0f, 0f, 0f, Int.MAX_VALUE); used++; continue }
                val rf = try { loadCal(u) } catch (e: Exception) { log += "Nem olvasható: ${ImageLoader.displayName(context, u)}"; rejected++; continue }
                if (rf.width != w || rf.height != h) { log += "Eltérő méret: ${ImageLoader.displayName(context, u)}"; rejected++; rf.close(); continue }
                val tf2 = Aligner.estimate(refStars, StarDetector.detect(rf.luminanceBinned()), w / 2, h / 2)
                rf.close()
                if (tf2.matches < opts.minMatches) { log += "Elvetve (${tf2.matches} egyezés): ${ImageLoader.displayName(context, u)}"; rejected++; continue }
                transforms[i] = Transform(tf2.angle, tf2.dx * 2f, tf2.dy * 2f, tf2.matches); used++
            }
            if (used == 0) throw IllegalStateException("Egyetlen kocka sem igazítható")

            // --- sávméret a memóriakeretből ---
            val heap = Runtime.getRuntime().maxMemory()
            val needSigma = opts.method != StackMethod.MEAN && used >= 3
            val perRowAcc = w.toLong() * (3 * 4 * (if (needSigma) 3 else 1) + 4 + (if (needSigma) 4 else 0))
            val maxShift = transforms.filterNotNull().maxOfOrNull { abs(it.dy) + abs(sin(it.angle)) * w / 2f } ?: 0f
            val margin = maxShift.toInt() + 3
            val perRowSrc = w.toLong() * 12
            val rawBytes = w.toLong() * h * 4 + (dark?.size ?: 0) * 4L + (flatNorm?.size ?: 0) * 4L
            val resultBytes = w.toLong() * h * 12
            val free = (heap * 0.6).toLong() - rawBytes - resultBytes - 2L * margin * perRowSrc
            require(free > perRowAcc * 32) { "Nincs elég memória a teljes felbontású stackhez (${heap / 1048576} MB) – válassz 2×2 binninget" }
            val bandH = (free / (perRowAcc + perRowSrc)).toInt().coerceIn(32, h)
            val bands = (h + bandH - 1) / bandH
            log += "Sávos feldolgozás: $bands sáv × $bandH sor, margó $margin"

            val result = FloatImage(w, h, 3)
            val cx = w / 2f; val cy = h / 2f
            val passes = if (needSigma) 2 else 1
            var step = 0; val steps = bands * passes * used

            for (b in 0 until bands) {
                val y0 = b * bandH; val y1 = minOf(h, y0 + bandH); val bh = y1 - y0
                val mean = Array(3) { FloatArray(w * bh) }
                val m2 = if (needSigma) Array(3) { FloatArray(w * bh) } else null
                val count = IntArray(w * bh)

                // forrás-sorok tartománya a sávhoz (inverz leképezés a sáv sarkaira, margóval)
                fun srcRange(tf: Transform): Pair<Int, Int> {
                    val c = cos(-tf.angle); val s = sin(-tf.angle)
                    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                    for ((px, py) in listOf(0f to y0.toFloat(), (w - 1).toFloat() to y0.toFloat(), 0f to (y1 - 1).toFloat(), (w - 1).toFloat() to (y1 - 1).toFloat())) {
                        val rx = px - cx - tf.dx; val ry = py - cy - tf.dy
                        val fy = s * rx + c * ry + cy
                        if (fy < lo) lo = fy; if (fy > hi) hi = fy
                    }
                    return Pair((lo.toInt() - 2).coerceIn(0, h - 1), (hi.toInt() + 3).coerceIn(1, h))
                }

                fun forEachRegistered(block: (idx: Int, reg: FloatImage, tf: Transform) -> Unit) {
                    for ((i, u) in frames.withIndex()) {
                        val tf = transforms[i] ?: continue
                        coroutineContext.ensureActive()
                        val rf = loadCal(u)
                        val (sy0, sy1) = srcRange(tf)
                        val src = rf.rgbRows(sy0, sy1); rf.close()
                        val reg = FloatImage(w, bh, 3)
                        val c = cos(-tf.angle); val s = sin(-tf.angle)
                        val sh = sy1 - sy0
                        Par.rows(bh) { r0, r1 ->
                            for (ry in r0 until r1) {
                                val y = (ry + y0).toFloat()
                                for (x in 0 until w) {
                                    val rx = x - cx - tf.dx; val ryy = y - cy - tf.dy
                                    val fx = c * rx - s * ryy + cx
                                    val fy = s * rx + c * ryy + cy - sy0
                                    if (fx < 0f || fy < 0f || fx >= w - 1 || fy >= sh - 1) continue
                                    val x0 = fx.toInt(); val yy0 = fy.toInt(); val ax = fx - x0; val ay = fy - yy0
                                    val i00 = yy0 * w + x0
                                    for (ch in 0 until 3) {
                                        val a = src.data[ch]
                                        reg.data[ch][ry * w + x] = (a[i00] * (1 - ax) + a[i00 + 1] * ax) * (1 - ay) + (a[i00 + w] * (1 - ax) + a[i00 + w + 1] * ax) * ay
                                    }
                                }
                            }
                        }
                        block(i, reg, tf)
                    }
                }

                // 1. menet: Welford
                forEachRegistered { i, reg, _ ->
                    step++; onProgress(StackProgress("Sáv ${b + 1}/$bands · 1. menet", step, steps, ImageLoader.displayName(context, frames[i])))
                    val n = w * bh
                    for (k in 0 until n) if (reg.data[0][k] != 0f || reg.data[1][k] != 0f || reg.data[2][k] != 0f) count[k]++
                    for (ch in 0 until 3) {
                        val a = reg.data[ch]; val mu = mean[ch]; val sq = m2?.get(ch)
                        Par.range(n) { k0, k1 -> for (k in k0 until k1) {
                            val v = a[k]; if (v == 0f && reg.data[0][k] == 0f && reg.data[1][k] == 0f && reg.data[2][k] == 0f) continue
                            val kk = count[k]; val d = v - mu[k]; mu[k] += d / kk; if (sq != null) sq[k] += d * (v - mu[k])
                        } }
                    }
                }
                if (!needSigma) {
                    for (ch in 0 until 3) System.arraycopy(mean[ch], 0, result.data[ch], y0 * w, w * bh)
                } else {
                    val sigma = m2!!
                    for (ch in 0 until 3) { val a = sigma[ch]; for (k in a.indices) a[k] = if (count[k] > 1) sqrt(a[k] / (count[k] - 1)) else 0f }
                    val sum = Array(3) { FloatArray(w * bh) }; val n2 = IntArray(w * bh)
                    forEachRegistered { i, reg, _ ->
                        step++; onProgress(StackProgress("Sáv ${b + 1}/$bands · 2. menet", step, steps, ImageLoader.displayName(context, frames[i])))
                        for (ch in 0 until 3) {
                            val a = reg.data[ch]; val mu = mean[ch]; val sg = sigma[ch]; val sm = sum[ch]
                            Par.range(w * bh) { k0, k1 -> for (k in k0 until k1) {
                                val v = a[k]
                                if (v == 0f && reg.data[0][k] == 0f && reg.data[1][k] == 0f && reg.data[2][k] == 0f) continue
                                val d = v - mu[k]; val lim = (if (d < 0) opts.sigmaLow else opts.sigmaHigh) * sg[k]
                                if (opts.method == StackMethod.SIGMA_CLIP) { if (abs(d) <= lim || sg[k] == 0f) { sm[k] += v; if (ch == 0) n2[k]++ } }
                                else { sm[k] += if (d > lim) mu[k] + lim else if (d < -lim) mu[k] - lim else v; if (ch == 0) n2[k]++ }
                            } }
                        }
                    }
                    for (ch in 0 until 3) { val r = result.data[ch]; val sm = sum[ch]; val mu = mean[ch]; for (k in 0 until w * bh) r[y0 * w + k] = if (n2[k] > 0) sm[k] / n2[k] else mu[k] }
                }
                System.gc()
            }
            log += "Stack kész (teljes felbontás): $used kocka, $rejected elvetve"
            onProgress(StackProgress("Kész", steps, steps))
            StackResult(result, used, rejected, log)
        }
}

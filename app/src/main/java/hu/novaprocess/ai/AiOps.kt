package hu.novaprocess.ai

import ai.onnxruntime.OnnxTensor
import hu.novaprocess.image.FloatImage
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A modellek futtatása a GraXpert / AstroSleuth eredeti kódjával egyező előkészítéssel:
 *  - GraXpert: bemenet NHWC float, csatornánkénti (x − medián) / MAD × 0.04 normalizálás, „gen_input_image”;
 *    háttér: egész kép 240×240-re kicsinyítve + 8 px tükrözött szél, ±1-re vágva; zajszűrés: 256-os ablak,
 *    128-as lépés, a középső 128×128 kerül vissza, a küszöb (10) feletti pixelek (csillagmagok) érintetlenek.
 *  - AstroSleuth: NCHW 0..1, 256-os csempe + 16 px átfedés, 4× kimenet.
 */
object AiOps {
    // ------------------------------------------------------------------ közös
    private fun medMad(img: FloatImage): Pair<FloatArray, FloatArray> {
        val med = FloatArray(3); val mad = FloatArray(3)
        for (c in 0 until 3) {
            val ch = img.data[min(c, img.channels - 1)]
            val s = FloatArray((img.height + 3) / 4 * ((img.width + 3) / 4)); var n = 0
            for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) s[n++] = ch[y * img.width + x]
            val a = s.copyOf(n); a.sort(); med[c] = a[n / 2]
            for (i in 0 until n) a[i] = abs(a[i] - med[c]); a.sort(); mad[c] = max(a[n / 2], 1e-6f)
        }
        return Pair(med, mad)
    }

    // ------------------------------------------------------------------ GraXpert MI háttér
    private data class BgKey(val w: Int, val h: Int, val sig: Long)
    private val bgCache = object : LinkedHashMap<BgKey, Array<FloatArray>>(4, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<BgKey, Array<FloatArray>>) = size > 3 }
    private fun signature(img: FloatImage): Long { var h = 1125899906842597L; val d = img.data[0]; var i = 0; while (i < d.size) { h = 31 * h + d[i].toRawBits(); i += max(1, d.size / 997) }; return h }

    /** A háttérmodell (a kép méretében) – az eredeti GraXpert eljárás szerint; gyorsítótárazva (előnézet-újrarajzoláshoz). */
    fun graxpertBackground(img: FloatImage, smoothing: Float = 0.1f): Array<FloatArray> {
        val key = BgKey(img.width, img.height, signature(img))
        synchronized(bgCache) { bgCache[key]?.let { return it } }
        val pad = 8; val inner = 256 - 2 * pad
        val small = resize(img, inner, inner)
        // csatornánkénti medián / MAD a kicsinyített képen
        val med = FloatArray(3); val mad = FloatArray(3)
        for (c in 0 until 3) { val a = small[c].copyOf(); a.sort(); med[c] = a[a.size / 2]; for (i in a.indices) a[i] = abs(small[c][i] - med[c]); a.sort(); mad[c] = max(a[a.size / 2], 1e-6f) }
        val buf = FloatBuffer.allocate(256 * 256 * 3)
        for (y in 0 until 256) for (x in 0 until 256) {
            val sy = (y - pad).coerceIn(0, inner - 1); val sx = (x - pad).coerceIn(0, inner - 1)   // „edge” kiterjesztés
            for (c in 0 until 3) buf.put(((small[c][sy * inner + sx] - med[c]) / mad[c] * 0.04f).coerceIn(-1f, 1f))
        }
        buf.rewind()
        val env = AiModels.env; val s = AiModels.session(AiModels.Model.GRAXPERT_BGE)
        val out = OnnxTensor.createTensor(env, buf, longArrayOf(1, 256, 256, 3)).use { t -> s.run(mapOf(s.inputNames.first() to t)).use { r -> (r[0] as OnnxTensor).floatBuffer } }
        val bg = Array(3) { FloatArray(inner * inner) }
        for (y in 0 until inner) for (x in 0 until inner) for (c in 0 until 3) bg[c][y * inner + x] = out.get(((y + pad) * 256 + (x + pad)) * 3 + c) / 0.04f * mad[c] + med[c]
        // simítás (σ = smoothing·20 + a végső σ=3), majd vissza teljes méretre
        val sig = smoothing * 20f
        for (c in 0 until 3) { if (sig > 0.5f) gauss(bg[c], inner, inner, sig); gauss(bg[c], inner, inner, 3f) }
        val full = Array(img.channels) { c -> upscale(bg[min(c, 2)], inner, inner, img.width, img.height) }
        synchronized(bgCache) { bgCache[key] = full }
        return full
    }

    // ------------------------------------------------------------------ GraXpert MI-zajszűrés
    /** Lineáris kép zajszűrése (erősség 0..1): a bemenetet nem módosítja, új képet ad. onProgress(kész csempe, összes). */
    suspend fun graxpertDenoise(img: FloatImage, strength: Float, onProgress: (Int, Int) -> Unit): FloatImage {
        val win = 256; val stride = 128; val off = (win - stride) / 2; val thr = 10f
        val (med, mad) = medMad(img)
        val w = img.width; val h = img.height
        val ith = h / stride + 1; val itw = w / stride + 1
        val env = AiModels.env; val s = AiModels.session(AiModels.Model.GRAXPERT_DENOISE)
        val res = FloatImage(w, h, img.channels)                   // az eredmény tömbjei egyben a munkaterület (nincs harmadik példány)
        for (c in 0 until img.channels) System.arraycopy(img.data[c], 0, res.data[c], 0, w * h)
        val out = res.data
        val total = ith * itw; val batch = 2
        val inBuf = FloatBuffer.allocate(batch * win * win * 3)
        val copies = Array(batch) { FloatArray(win * win * 3) }
        // a GraXpert a képet lent/jobbra tükrözve kiegészíti, körben `off` pixellel – itt index-tükrözéssel
        fun src(y0: Int, x0: Int, c: Int): Float {
            var y = y0 - off; var x = x0 - off
            if (y < 0) y = -y - 1; if (x < 0) x = -x - 1
            if (y >= h) y = 2 * h - y - 1; if (x >= w) x = 2 * w - x - 1
            return img.data[min(c, img.channels - 1)][y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        }
        var b = 0
        while (b < total) {
            coroutineContext.ensureActive()
            val n = min(batch, total - b)
            inBuf.clear()
            for (t in 0 until n) {
                val idx = b + t; val i = idx % ith; val j = idx / ith
                val py = stride * i; val px = stride * j
                val cp = copies[t]; var k = 0
                for (yy in 0 until win) for (xx in 0 until win) for (c in 0 until 3) {
                    val v = (src(py + yy, px + xx, c) - med[c]) / mad[c] * 0.04f
                    cp[k++] = v; inBuf.put(v.coerceIn(-thr, thr))
                }
            }
            inBuf.flip()
            val res = OnnxTensor.createTensor(env, inBuf, longArrayOf(n.toLong(), win.toLong(), win.toLong(), 3)).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r -> val fb = (r[0] as OnnxTensor).floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) } }
            }
            for (t in 0 until n) {
                val idx = b + t; val i = idx % ith; val j = idx / ith
                val py = stride * i; val px = stride * j; val base = t * win * win * 3; val cp = copies[t]
                for (yy in off until off + stride) {
                    val oy = py + yy - off; if (oy >= h) break
                    for (xx in off until off + stride) {
                        val ox = px + xx - off; if (ox >= w) break
                        for (c in 0 until img.channels) {
                            val q = (yy * win + xx) * 3 + c
                            val v = if (cp[q] < thr) res[base + q] else cp[q]
                            out[c][oy * w + ox] = v / 0.04f * mad[c] + med[c]
                        }
                    }
                }
            }
            b += n; onProgress(b, total)
        }
        // keverés az eredetivel (a küszöb feletti pixelek eredetiek maradnak) – az eredménybe írva
        for (c in 0 until img.channels) {
            val cut = thr / 0.04f * mad[c] + med[c]; val o = img.data[c]; val d = out[c]
            for (i in o.indices) { val dn = if (o[i] < cut) d[i] else o[i]; d[i] = (dn * strength + o[i] * (1 - strength)).coerceIn(0f, 1f) }
        }
        return res
    }

    // ------------------------------------------------------------------ AstroSleuth részletjavító
    /**
     * Megnyújtott (0..1) képen: fél felbontásra kicsinyít (gyors mód) vagy marad, a modell 4×-esen felnagyítja,
     * majd vissza az eredeti méretre (területátlag) – így élesebb, kevésbé zajos kép; erősség szerint keverve.
     */
    suspend fun astroSleuth(img: FloatImage, strength: Float, fast: Boolean, onProgress: (Int, Int) -> Unit): FloatImage {
        val inp = if (fast) img.downscale(max(img.width, img.height) / 2) else img
        val w = inp.width; val h = inp.height; val tile = 256; val pad = 16; val sc = 4
        val ow = w * sc; val oh = h * sc
        // a 4×-es kimenetet nem tartjuk egészben: rögtön a cél méretre átlagoljuk
        val fx = ow.toFloat() / img.width; val fy = oh.toFloat() / img.height
        val acc = Array(3) { FloatArray(img.width * img.height) }; val cnt = FloatArray(img.width * img.height)
        val env = AiModels.env; val s = AiModels.session(AiModels.Model.ASTROSLEUTH)
        val tx = (w + tile - 1) / tile; val ty = (h + tile - 1) / tile; val total = tx * ty; var done = 0
        for (ti in 0 until ty) for (tj in 0 until tx) {
            coroutineContext.ensureActive()
            val x0 = tj * tile; val y0 = ti * tile; val x1 = min(x0 + tile, w); val y1 = min(y0 + tile, h)
            val px0 = max(x0 - pad, 0); val py0 = max(y0 - pad, 0); val px1 = min(x1 + pad, w); val py1 = min(y1 + pad, h)
            val tw = px1 - px0; val th = py1 - py0
            val buf = FloatBuffer.allocate(3 * tw * th)
            for (c in 0 until 3) { val ch = inp.data[min(c, inp.channels - 1)]; for (y in py0 until py1) for (x in px0 until px1) buf.put(ch[y * w + x].coerceIn(0f, 1f)) }
            buf.rewind()
            val res = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r -> val fb = (r[0] as OnnxTensor).floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) } }
            }
            val rw = tw * sc; val rh = th * sc
            for (y in (y0 - py0) * sc until (y1 - py0) * sc) {
                val gy = (py0 * sc + y); val ty2 = (gy / fy).toInt().coerceIn(0, img.height - 1)
                for (x in (x0 - px0) * sc until (x1 - px0) * sc) {
                    val gx = (px0 * sc + x); val tx2 = (gx / fx).toInt().coerceIn(0, img.width - 1)
                    val o = ty2 * img.width + tx2
                    for (c in 0 until 3) acc[c][o] += res[c * rw * rh + y * rw + x].coerceIn(0f, 1f)
                    cnt[o] += 1f
                }
            }
            done++; onProgress(done, total)
        }
        for (c in 0 until img.channels) { val o = img.data[c]; val a = acc[min(c, 2)]; for (i in o.indices) if (cnt[i] > 0f) o[i] = (a[i] / cnt[i]) * strength + o[i] * (1 - strength) }
        return img
    }

    // ------------------------------------------------------------------ segédek
    private fun resize(img: FloatImage, tw: Int, th: Int): Array<FloatArray> = Array(3) { c ->
        val ch = img.data[min(c, img.channels - 1)]; val out = FloatArray(tw * th)
        for (y in 0 until th) { val sy = ((y + 0.5f) * img.height / th - 0.5f).coerceIn(0f, img.height - 1f); val y0 = sy.toInt(); val y1 = min(y0 + 1, img.height - 1); val fy = sy - y0
            for (x in 0 until tw) { val sx = ((x + 0.5f) * img.width / tw - 0.5f).coerceIn(0f, img.width - 1f); val x0 = sx.toInt(); val x1 = min(x0 + 1, img.width - 1); val fx = sx - x0
                out[y * tw + x] = (ch[y0 * img.width + x0] * (1 - fx) + ch[y0 * img.width + x1] * fx) * (1 - fy) + (ch[y1 * img.width + x0] * (1 - fx) + ch[y1 * img.width + x1] * fx) * fy } }
        out
    }
    private fun upscale(a: FloatArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        val out = FloatArray(tw * th)
        for (y in 0 until th) { val sy = ((y + 0.5f) * h / th - 0.5f).coerceIn(0f, h - 1f); val y0 = sy.toInt(); val y1 = min(y0 + 1, h - 1); val fy = sy - y0
            for (x in 0 until tw) { val sx = ((x + 0.5f) * w / tw - 0.5f).coerceIn(0f, w - 1f); val x0 = sx.toInt(); val x1 = min(x0 + 1, w - 1); val fx = sx - x0
                out[y * tw + x] = (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy } }
        return out
    }
    private fun gauss(a: FloatArray, w: Int, h: Int, sigma: Float) {
        val r = (sigma * 3).toInt().coerceAtLeast(1); val k = FloatArray(2 * r + 1) { val d = it - r; kotlin.math.exp(-d * d / (2 * sigma * sigma)) }; val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val t = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (i in -r..r) s += a[y * w + (x + i).coerceIn(0, w - 1)] * k[i + r]; t[y * w + x] = s }
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (i in -r..r) s += t[(y + i).coerceIn(0, h - 1) * w + x] * k[i + r]; a[y * w + x] = s }
    }
}

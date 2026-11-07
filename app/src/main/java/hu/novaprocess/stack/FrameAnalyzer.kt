package hu.novaprocess.stack

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import kotlin.math.sqrt

/** Egy nyers kocka minőségi adatai (stackelés előtti osztályozáshoz). */
data class FrameInfo(
    val uri: Uri,
    val name: String,
    val fwhm: Float,          // csillagok átlagos félértékszélessége, px (teljes felbontásban)
    val stars: Int,
    val background: Float,    // medián háttér 0..1
    val eccentricity: Float,  // 0 = kerek, 1 = vonal (elhúzott)
    val thumb: Bitmap?,
    val rejected: Boolean = false,
    val reason: String = "",
    val objectName: String? = null,
    val exposure: Float? = null,
    val dateObs: String? = null,
    val trail: Boolean = false,  // műhold-/repülőcsík
    val meta: hu.novaprocess.image.ImageLoader.Meta? = null
)

/**
 * Kocka-minősítés: lekicsinyített képen csillagdetektálás, FWHM és elliptikusság a
 * másodrendű momentumokból, háttér medián, plusz autostretch-elt miniatűr.
 */
object FrameAnalyzer {

    fun analyze(context: Context, uri: Uri, analysisSide: Int = 1000, thumbSide: Int = 220): FrameInfo {
        val name = ImageLoader.displayName(context, uri)
        var meta: ImageLoader.Meta? = null
        var scaleDown = 1f
        val small: FloatImage = if (ImageLoader.isFits(name)) {
            // gyors út: nyers Bayer-sík → szuperpixel (2×2 → 1 RGB) → lekicsinyítés; nincs teljes debayer
            context.contentResolver.openInputStream(uri).use { ins ->
                requireNotNull(ins) { "Nem nyitható: $name" }
                val (img, cards, raw) = hu.novaprocess.image.FitsReader.readRawOrImage(ins)
                meta = ImageLoader.metaOf(cards)
                if (raw != null) {
                    val (w, h, plane) = raw
                    scaleDown = maxOf(w, h).toFloat() / analysisSide
                    superpixel(plane, w, h, cards["BAYERPAT"]!!.uppercase()).downscale(analysisSide)
                } else { scaleDown = maxOf(img!!.width, img.height).toFloat() / analysisSide; img.downscale(analysisSide) }
            }
        } else ImageLoader.loadWithMeta(context, uri, name).let { (full, m) ->
            meta = m; scaleDown = maxOf(full.width, full.height).toFloat() / analysisSide
            full.downscale(analysisSide)
        }
        val mono = small.toMono()
        val stars = StarDetector.detect(mono, maxStars = 150, sigmaK = 6f)
        val a = mono.data[0]; val w = mono.width; val h = mono.height
        val bg = Processor.median(a)

        var fwhmSum = 0f; var eccSum = 0f; var n = 0
        for (s in stars) {
            val cx = s.x.toInt(); val cy = s.y.toInt()
            if (cx < 4 || cy < 4 || cx >= w - 4 || cy >= h - 4) continue
            var m00 = 0f; var mxx = 0f; var myy = 0f; var mxy = 0f
            for (dy in -4..4) for (dx in -4..4) {
                val p = (a[(cy + dy) * w + cx + dx] - bg).coerceAtLeast(0f)
                m00 += p; mxx += p * dx * dx; myy += p * dy * dy; mxy += p * dx * dy
            }
            if (m00 <= 0f) continue
            val sxx = mxx / m00; val syy = myy / m00; val sxy = mxy / m00
            // sajátértékek
            val tr = sxx + syy; val det = sxx * syy - sxy * sxy
            val disc = sqrt(((tr * tr) / 4f - det).coerceAtLeast(0f))
            val l1 = tr / 2f + disc; val l2 = (tr / 2f - disc).coerceAtLeast(1e-4f)
            val sigma = sqrt(((l1 + l2) / 2f).coerceAtLeast(1e-4f))
            fwhmSum += 2.355f * sigma
            eccSum += sqrt((1f - l2 / l1).coerceIn(0f, 1f))
            n++
        }
        val fwhm = if (n > 0) fwhmSum / n * (if (scaleDown > 1f) scaleDown else 1f) else 0f
        val ecc = if (n > 0) eccSum / n else 0f

        val trail = detectTrail(a, w, h, bg)

        val thumb = try {
            val t = small.downscale(thumbSide)
            Processor.process(t, ProcessParams()).toBitmap()
        } catch (e: Exception) { null }

        return FrameInfo(uri, name, fwhm, stars.size, bg, ecc, thumb, objectName = meta?.objectName, exposure = meta?.exposure, dateObs = meta?.dateObs, trail = trail, meta = meta)
    }

    /**
     * Csíkfelismerés: a háttér fölötti maszkból (csillagok kivágva a morfológiai nyitással) 36 irányban
     * vetületet képzünk; egy hosszú egyenes csík egyetlen irányban kiugró, keskeny csúcsot ad.
     */
    fun detectTrail(a: FloatArray, w: Int, h: Int, bg: Float): Boolean {
        val noise = (Processor.mad(a, bg) * 1.4826f).coerceAtLeast(1e-6f)
        val thr = bg + 3f * noise
        // maszk: fényes pixelek, kivéve a kompakt (csillag) foltokat → 2 px-es nyitás után maradó vonalas struktúrák
        val mask = BooleanArray(w * h) { a[it] > thr }
        val opened = BooleanArray(w * h)
        if (w < 8 || h < 8) return false
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            if (!mask[i]) continue
            // vízszintes VAGY függőleges VAGY átlós 5 hosszú futás → vonal-jelölt (±2 px, a szélektől 2 px-re indulva)
            var runH = 0; var runV = 0; var runD1 = 0; var runD2 = 0
            for (d in -2..2) { if (mask[i + d]) runH++; if (mask[i + d * w]) runV++; if (mask[(y + d) * w + x + d]) runD1++; if (mask[(y + d) * w + x - d]) runD2++ }
            opened[i] = runH == 5 || runV == 5 || runD1 == 5 || runD2 == 5
        }
        val total = opened.count { it }
        if (total < maxOf(w, h) / 2) return false
        // vetületek: Hough-szerű, 36 irány, bin = 2 px
        val diag = Math.hypot(w.toDouble(), h.toDouble()).toInt()
        val bins = diag / 2 + 2
        var best = 0; var bestMean = 1f
        for (t in 0 until 36) {
            val th = Math.PI * t / 36.0; val ct = Math.cos(th); val st = Math.sin(th)
            val acc = IntArray(bins * 2)
            var i = 0
            for (y in 0 until h) for (x in 0 until w) { if (opened[i]) { val r = (x * ct + y * st).toInt() / 2 + bins; if (r in acc.indices) acc[r]++ }; i++ }
            val mx = acc.max(); val mean = total.toFloat() / bins
            if (mx > best) { best = mx; bestMean = mean }
        }
        // egy csík: a legjobb irányban a csúcs sokszorosa az átlagnak, és legalább a kép ~40%-át átéri
        // egyetlen domináns vonal: a csúcs a vonal-jelölt pixelek jelentős része, sokszorosa az átlagnak, és átéri a kép ~40%-át
        return best > 12f * bestMean.coerceAtLeast(1f) && best > minOf(w, h) * 0.4f && best > total * 0.3f
    }

    /** 2×2 Bayer-blokk → 1 RGB pixel (fél felbontás), elemzéshez és bélyegképhez elég. */
    fun superpixel(plane: FloatArray, w: Int, h: Int, pattern: String): FloatImage {
        val w2 = w / 2; val h2 = h / 2
        val out = FloatImage(w2, h2, 3)
        val idx = IntArray(4) { when (pattern[it]) { 'R' -> 0; 'G' -> 1; else -> 2 } }
        val r = out.data[0]; val g = out.data[1]; val b = out.data[2]
        hu.novaprocess.image.Par.rows(h2) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w2) {
                val i = (2 * y) * w + 2 * x
                val v = floatArrayOf(plane[i], plane[i + 1], plane[i + w], plane[i + w + 1])
                var rr = 0f; var gg = 0f; var bb = 0f; var ng = 0
                for (k in 0 until 4) when (idx[k]) { 0 -> rr = v[k]; 1 -> { gg += v[k]; ng++ }; else -> bb = v[k] }
                val o = y * w2 + x
                r[o] = rr; g[o] = if (ng > 0) gg / ng else 0f; b[o] = bb
            }
        }
        return out
    }

    /**
     * Automatikus kizárás a kockák mediánjához képest. Biztonsági szabály: ha a küszöbök a kockák
     * több mint felét kizárnák, a sorozat jellege más (pl. kevés csillag, széles köd) – ilyenkor csak
     * az olvashatatlan kockák maradnak ki, és a kizárási ok jelzi, hogy a küszöböket lazítottuk.
     */
    fun autoReject(frames: List<FrameInfo>, fwhmFactor: Float = 1.6f, minStarsFactor: Float = 0.35f, maxEcc: Float = 0.85f): List<FrameInfo> {
        val manual = frames.filter { it.reason == "kézzel kizárva" }.map { it.uri }.toSet()
        val readable = frames.filter { !it.reason.startsWith("nem olvasható") && it.reason != "elemzés…" }
        val valid = readable.filter { it.stars > 0 }
        if (valid.size < 3) return frames.map { f -> if (f.uri in manual) f else if (f.reason.startsWith("nem olvasható")) f.copy(rejected = true) else f.copy(rejected = false, reason = "") }
        val medFwhm = valid.map { it.fwhm }.sorted()[valid.size / 2]
        val medStars = valid.map { it.stars }.sorted()[valid.size / 2]
        fun reasonOf(f: FrameInfo): String = when {
            f.reason.startsWith("nem olvasható") -> f.reason
            f.stars == 0 -> "nincs csillag"
            f.trail -> "csík (műhold/repülő)"
            f.fwhm > medFwhm * fwhmFactor -> "életlen (FWHM ${"%.1f".format(f.fwhm)} vs medián ${"%.1f".format(medFwhm)})"
            f.stars < medStars * minStarsFactor -> "kevés csillag (${f.stars} vs medián $medStars) – felhő?"
            f.eccentricity > maxEcc && f.stars >= 8 -> "elhúzott csillagok (${"%.0f".format(f.eccentricity * 100)}%)"
            else -> ""
        }
        var out = frames.map { f -> if (f.uri in manual) f else { val r = reasonOf(f); f.copy(rejected = r.isNotEmpty(), reason = r) } }
        val autoRejected = out.count { it.rejected && it.uri !in manual && !it.reason.startsWith("nem olvasható") }
        if (autoRejected > readable.size / 2) {
            // túl szigorú a sorozatra → csak az olvashatatlanokat zárjuk ki
            out = frames.map { f -> if (f.uri in manual) f else if (f.reason.startsWith("nem olvasható")) f.copy(rejected = true) else f.copy(rejected = false, reason = "küszöb lazítva: ${reasonOf(f).ifEmpty { "ok" }}") }
        }
        return out
    }

}

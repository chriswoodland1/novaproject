package hu.novaprocess.catalog

import android.content.Context
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.solve.SolveResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A kép égi elhelyezkedése: középpont, látómező, (ha ismert) tájolás. Forrása a plate solve
 * (pontos: skála + forgatás + paritás) vagy a Seestar FITS fejléc (RA/DEC + FOCALLEN/XPIXSZ → csak középpont + méret).
 */
data class SkyField(
    val ra: Double, val dec: Double,           // középpont, fok
    val pixScale: Double,                       // ívmásodperc / px (a teljes képen)
    val width: Int, val height: Int,            // teljes kép mérete
    val rotationDeg: Double?,                   // kép „fel” iránya északtól kelet felé; null = ismeretlen (fejlécből)
    val parity: Int,                            // 0 normál, 1 tükrözött (ismeretlen tájolásnál közömbös)
    val source: String                          // "plate solve" / "FITS fejléc"
) {
    val fovWDeg get() = pixScale * width / 3600.0
    val fovHDeg get() = pixScale * height / 3600.0
    val oriented get() = rotationDeg != null

    /** Égi koordináta → képpont (gnomonikus vetítés). Null, ha nincs tájolás. */
    fun toPixel(raDeg: Double, decDeg: Double): Pair<Float, Float>? {
        val rot = rotationDeg ?: return null
        val r = Math.PI / 180
        val ra0 = ra * r; val dec0 = dec * r; val a = raDeg * r; val d = decDeg * r
        val cosc = sin(dec0) * sin(d) + cos(dec0) * cos(d) * cos(a - ra0)
        if (cosc <= 0.05) return null
        val xi = cos(d) * sin(a - ra0) / cosc               // kelet felé +
        val eta = (cos(dec0) * sin(d) - sin(dec0) * cos(d) * cos(a - ra0)) / cosc   // észak felé +
        val th = rot * r
        val px = (if (parity == 0) -1.0 else 1.0) * (xi * cos(th) - eta * sin(th))
        val py = -(xi * sin(th) + eta * cos(th))
        val s = pixScale / 3600.0 * r                       // rad / px
        return Pair((width / 2.0 + px / s).toFloat(), (height / 2.0 + py / s).toFloat())
    }

    companion object {
        /** Seestar S30 Pro alapértelmezés, ha a fejlécben nincs fókusz / pixelméret: 2,9 µm, 150 mm → ~3,99″/px. */
        const val DEFAULT_SCALE = 3.99

        fun fromSolve(r: SolveResult): SkyField = SkyField(r.ra, r.dec, r.pixScale, r.width, r.height, r.orientation, r.parity, "plate solve")

        fun fromMeta(m: ImageLoader.Meta?, width: Int, height: Int): SkyField? {
            if (m == null) return null
            val ra = parseRa(m.ra) ?: return null; val dec = parseDec(m.dec) ?: return null
            var scale = if (m.focalMm != null && m.focalMm > 0 && m.pixUm != null && m.pixUm > 0) 206.265 * m.pixUm / m.focalMm else DEFAULT_SCALE
            // binnelt / drizzle-elt stack: a kép pixele nem azonos a szenzor pixelével
            val rawLong = maxOf(m.rawW ?: 0, m.rawH ?: 0)
            if (rawLong > 0) scale *= rawLong.toDouble() / maxOf(width, height)
            return SkyField(ra, dec, scale, width, height, null, 0, "FITS fejléc")
        }

        /** A plate solve annotációiból (katalógusbeli objektumok) finomítja a forgatás/paritás előjelét, hogy a vetítés egyezzen. */
        fun calibrate(ctx: Context, f: SkyField, r: SolveResult): SkyField {
            if (f.rotationDeg == null) return f
            val pts = r.annotations.filter { it.type != "star" && it.type != "hd" }.mapNotNull { a -> Catalog.find(ctx, a.names.firstOrNull())?.let { Triple(it, a.x, a.y) } }
            if (pts.isEmpty()) return f
            var best = f; var bestErr = Double.MAX_VALUE
            for (par in 0..1) for (sgn in listOf(1.0, -1.0)) for (off in listOf(0.0, 180.0)) {
                val c = f.copy(parity = par, rotationDeg = sgn * f.rotationDeg + off)
                var err = 0.0
                for ((o, x, y) in pts) { val p = c.toPixel(o.ra, o.dec); err += if (p == null) 1e9 else hypot((p.first - x).toDouble(), (p.second - y).toDouble()) }
                if (err < bestErr) { bestErr = err; best = c }
            }
            return best
        }

        fun parseRa(s: String?): Double? {
            if (s.isNullOrBlank()) return null
            s.trim().toDoubleOrNull()?.let { return it }
            val p = s.trim().split(Regex("[:\\s]+")).mapNotNull { it.toDoubleOrNull() }
            if (p.size >= 2) return (p[0] + p[1] / 60 + (p.getOrNull(2) ?: 0.0) / 3600) * 15
            return null
        }
        fun parseDec(s: String?): Double? {
            if (s.isNullOrBlank()) return null
            s.trim().toDoubleOrNull()?.let { return it }
            val neg = s.trim().startsWith("-")
            val p = s.trim().removePrefix("-").removePrefix("+").split(Regex("[:\\s]+")).mapNotNull { it.toDoubleOrNull() }
            if (p.size >= 2) { val v = p[0] + p[1] / 60 + (p.getOrNull(2) ?: 0.0) / 3600; return if (neg) -v else v }
            return null
        }
    }
}

/** Vágási javaslat normalizált képkoordinátákban (0..1). */
data class CropSuggestion(val left: Float, val top: Float, val right: Float, val bottom: Float, val reason: String)

object Framing {
    /**
     * Vágási javaslat – óvatosan, hogy a lényeg ne vesszen el:
     *  - csak ha az objektum (katalógus-méret) a rövidebb oldal < 30 %-a;
     *  - a helyét a KÉP TARTALMÁBÓL keressük (csillagmentesített, elmosott fényességfolt a várt hely közelében),
     *    a katalógus-pozíció csak kiindulópont (fejléces RA/DEC-nél a Seestar célpontja, nem a valódi képközép);
     *  - a keret a folt határoló téglalapja + bőséges ráhagyás, legalább a rövidebb oldal 60 %-a és a katalógus-méret 3×-a;
     *  - a javaslat nem vág automatikusan: a vágókeretet nyitja meg előre beállítva.
     * @param preview a (vágatlan) forrás kicsinyített lineáris képe
     */
    fun suggest(f: SkyField, m: Match, preview: FloatImage?): CropSuggestion? {
        val o = m.obj
        if (o.majArcmin <= 0f) return null
        val sizePx = o.majArcmin * 60.0 / f.pixScale
        val shortSide = minOf(f.width, f.height).toDouble()
        if (sizePx / shortSide > 0.30) return null
        val expected = f.toPixel(o.ra, o.dec)?.let { Pair(it.first / f.width, it.second / f.height) } ?: Pair(0.5f, 0.5f)
        val blob = preview?.let { locate(it, expected, (sizePx / shortSide).toFloat()) }
        if (blob == null && !f.oriented && m.offsetDeg > 0.08 * minOf(f.fovWDeg, f.fovHDeg)) return null
        val aspect = f.width.toDouble() / f.height
        // középpont és szükséges méret (normalizált → px)
        val cx = (blob?.cx ?: expected.first) * f.width; val cy = (blob?.cy ?: expected.second) * f.height
        val needW = maxOf((blob?.w ?: 0f) * f.width * 1.8, sizePx * 3.0); val needH = maxOf((blob?.h ?: 0f) * f.height * 1.8, sizePx * 3.0)
        var bw: Double; var bh: Double
        val minShort = shortSide * 0.6
        if (f.width >= f.height) { bh = maxOf(minShort, needH, needW / aspect); bw = bh * aspect } else { bw = maxOf(minShort, needW, needH * aspect); bh = bw / aspect }
        if (bw >= f.width * 0.92 || bh >= f.height * 0.92) return null   // alig vágna – nem éri meg
        var l = (cx - bw / 2).coerceIn(0.0, f.width - bw); var t = (cy - bh / 2).coerceIn(0.0, f.height - bh)
        return CropSuggestion((l / f.width).toFloat(), (t / f.height).toFloat(), ((l + bw) / f.width).toFloat(), ((t + bh) / f.height).toFloat(),
            "${o.id} a kép rövidebb oldalának ~${"%.0f".format(sizePx / shortSide * 100)} %-a" + (if (blob != null) " · helye a képből mérve" else " · hely a koordinátából") +
            " → keret ${(bw / f.width * 100).toInt()} % szélesség, ${bw.toInt()}×${bh.toInt()} px (állítható)")
    }

    data class Blob(val cx: Float, val cy: Float, val w: Float, val h: Float)

    /** A várt hely közelében lévő legnagyobb kiterjedt fényességfolt (csillagok kiszűrve). Normalizált koordináták. */
    fun locate(img: FloatImage, expected: Pair<Float, Float>, sizeFrac: Float): Blob? {
        val small = img.downscale(320)
        val w = small.width; val h = small.height
        val lum = small.toMono().data[0]
        // nyitás (min, majd max) r=2 → csillagok el; utána doboz-elmosás
        fun morph(a: FloatArray, isMin: Boolean, r: Int): FloatArray {
            val t = FloatArray(a.size); val o = FloatArray(a.size)
            for (y in 0 until h) for (x in 0 until w) { var v = a[y * w + x]; for (d in -r..r) { val s = a[y * w + (x + d).coerceIn(0, w - 1)]; v = if (isMin) minOf(v, s) else maxOf(v, s) }; t[y * w + x] = v }
            for (y in 0 until h) for (x in 0 until w) { var v = t[y * w + x]; for (d in -r..r) { val s = t[(y + d).coerceIn(0, h - 1) * w + x]; v = if (isMin) minOf(v, s) else maxOf(v, s) }; o[y * w + x] = v }
            return o
        }
        val sm = hu.novaprocess.process.Processor.boxBlur(morph(morph(lum, true, 2), false, 2), w, h, 2)
        val bg = hu.novaprocess.process.Processor.median(sm)
        val nz = (hu.novaprocess.process.Processor.mad(sm, bg) * 1.4826f).coerceAtLeast(1e-6f)
        val thr = bg + 3f * nz
        val label = IntArray(w * h) { -1 }
        val ex = expected.first * w; val ey = expected.second * h
        var best: Blob? = null; var bestScore = 0.0
        var id = 0
        val qx = IntArray(w * h); val qy = IntArray(w * h)
        for (sy in 0 until h) for (sx in 0 until w) {
            val si = sy * w + sx
            if (label[si] >= 0 || sm[si] <= thr) continue
            var head = 0; var tail = 0; qx[tail] = sx; qy[tail] = sy; tail++; label[si] = id
            var minX = sx; var maxX = sx; var minY = sy; var maxY = sy; var sw = 0.0; var swx = 0.0; var swy = 0.0; var n = 0
            while (head < tail) {
                val x = qx[head]; val y = qy[head]; head++
                val v = (sm[y * w + x] - bg).toDouble(); sw += v; swx += v * x; swy += v * y; n++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                for ((nx, ny) in arrayOf(Pair(x + 1, y), Pair(x - 1, y), Pair(x, y + 1), Pair(x, y - 1))) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (label[ni] < 0 && sm[ni] > thr) { label[ni] = id; qx[tail] = nx; qy[tail] = ny; tail++ }
                }
            }
            id++
            if (n < 12) continue
            val cx = (swx / sw).toFloat(); val cy = (swy / sw).toFloat()
            val dist = kotlin.math.hypot((cx - ex).toDouble(), (cy - ey).toDouble()) / maxOf(w, h)
            val score = sw / (1.0 + 8.0 * dist)          // fényes és közel a várt helyhez
            if (score > bestScore) { bestScore = score; best = Blob(cx / w, cy / h, (maxX - minX + 1).toFloat() / w, (maxY - minY + 1).toFloat() / h) }
        }
        // a teljes képet kitöltő „folt” (gradiens / köd mindenhol) nem használható helymeghatározásra
        return best?.takeIf { it.w < 0.85f || it.h < 0.85f }
    }
}

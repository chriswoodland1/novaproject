package hu.novaprocess.catalog

import android.content.Context
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
            val scale = if (m.focalMm != null && m.focalMm > 0 && m.pixUm != null && m.pixUm > 0) 206.265 * m.pixUm / m.focalMm else DEFAULT_SCALE
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
     * Kis objektumnál (a látómező rövidebb oldalának < 45 %-a) ajánl vágást: az objektum köré ~2,6× nagyobb keret,
     * a kép oldalarányával, a képen belül tartva. Tájolás nélkül csak akkor, ha az objektum a kép közepe közelében van.
     */
    fun suggest(f: SkyField, m: Match): CropSuggestion? {
        val o = m.obj
        if (o.majArcmin <= 0f) return null
        val sizePx = o.majArcmin * 60.0 / f.pixScale
        val shortSide = minOf(f.width, f.height).toDouble()
        if (sizePx / shortSide > 0.45) return null          // már kitölti a képet
        val center = f.toPixel(o.ra, o.dec) ?: run {
            if (m.offsetDeg > 0.12 * minOf(f.fovWDeg, f.fovHDeg)) return null
            Pair(f.width / 2f, f.height / 2f)
        }
        val boxShort = (sizePx * 2.6).coerceIn(shortSide * 0.25, shortSide)
        val aspect = f.width.toDouble() / f.height
        var bw = if (f.width >= f.height) boxShort * aspect else boxShort
        var bh = if (f.width >= f.height) boxShort else boxShort / aspect
        bw = bw.coerceAtMost(f.width.toDouble()); bh = bh.coerceAtMost(f.height.toDouble())
        var l = center.first - bw / 2; var t = center.second - bh / 2
        l = l.coerceIn(0.0, f.width - bw); t = t.coerceIn(0.0, f.height - bh)
        val pct = (bw / f.width * 100).toInt()
        return CropSuggestion((l / f.width).toFloat(), (t / f.height).toFloat(), ((l + bw) / f.width).toFloat(), ((t + bh) / f.height).toFloat(),
            "${o.id} csak a kép ${"%.0f".format(sizePx / shortSide * 100)} %-át tölti ki → vágás az objektumra ($pct % szélesség, ${(bw).toInt()}×${(bh).toInt()} px)")
    }
}

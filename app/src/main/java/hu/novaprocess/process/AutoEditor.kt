package hu.novaprocess.process

import hu.novaprocess.image.FloatImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Automata szerkesztés: képelemzés → objektumtípus → szabályalapú paraméterválasztás →
 * visszacsatolt finomhangolás (háttérszint, kivágás) → magyarázat.
 * Előnézeti méretű képen fut (gyors), az eredmény ProcessParams a teljes képre is érvényes.
 */
object AutoEditor {

    enum class ObjectType(val label: String) {
        GALAXY("galaxis"), EMISSION_NEBULA("emissziós köd"), REFLECTION_NEBULA("reflexiós köd"), PLANETARY_NEBULA("planetáris köd"),
        GLOBULAR("gömbhalmaz"), OPEN_CLUSTER("nyílthalmaz"), STARFIELD("csillagmező"), UNKNOWN("ismeretlen")
    }

    data class Analysis(
        val bg: Float, val noise: Float, val gradient: Float, val cast: FloatArray,
        val stars: Int, val fwhm: Float, val extendedFrac: Float, val extendedHue: String, val dynRange: Float,
        val greenExcess: Float, val type: ObjectType, val typeSource: String
    )

    data class Result(val params: ProcessParams, val analysis: Analysis, val notes: List<String>)

    // --- Messier-típusok (a FITS OBJECT / fájlnév alapján) ---
    private val messierGalaxies = setOf(31,32,33,49,51,58,59,60,61,63,64,65,66,74,77,81,82,83,84,85,86,87,88,89,90,91,94,95,96,98,99,100,101,102,104,105,106,108,109,110)
    private val messierEmission = setOf(8,16,17,20,42,43,78,1)
    private val messierPlanetary = setOf(27,57,76,97)
    private val messierGlobular = setOf(2,3,4,5,9,10,12,13,14,15,19,22,28,30,53,54,55,56,62,68,69,70,71,72,75,79,80,92,107)
    private val messierOpen = setOf(6,7,11,18,21,23,24,25,26,29,34,35,36,37,38,39,41,44,45,46,47,48,50,52,67,73,93,103)
    private val ngcEmission = setOf(7000, 6960, 6992, 6995, 2237, 2244, 281, 7380, 6888, 1499, 7635, 2264, 6914, 6820, 7822, 896, 6823, 2174, 3372)
    private val ngcGalaxies = setOf(253, 891, 4565, 7331, 2403, 5907, 4631, 4656, 6946, 2903, 3628, 4244, 5128, 4449, 4725, 4490, 5033)
    private val ngcGlobular = setOf(5139, 104, 6397, 6752, 5272, 362)
    private val ngcOpen = setOf(869, 884, 457, 7789, 663, 752, 2158, 1528, 6939, 6633)
    private val icEmission = setOf(1396, 1805, 1848, 405, 410, 434, 5070, 1318, 2177, 5146, 63, 59)

    fun typeFromName(name: String?): Pair<ObjectType, String>? {
        if (name.isNullOrBlank()) return null
        val n = name.uppercase().replace(" ", "")
        Regex("M(\\d{1,3})").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { m ->
            val t = when (m) {
                in messierGalaxies -> ObjectType.GALAXY; in messierEmission -> ObjectType.EMISSION_NEBULA; in messierPlanetary -> ObjectType.PLANETARY_NEBULA
                in messierGlobular -> ObjectType.GLOBULAR; in messierOpen -> ObjectType.OPEN_CLUSTER; else -> null
            }
            if (t != null) return Pair(t, "M$m")
        }
        Regex("NGC(\\d{1,4})").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { k ->
            val t = when (k) { in ngcEmission -> ObjectType.EMISSION_NEBULA; in ngcGalaxies -> ObjectType.GALAXY; in ngcGlobular -> ObjectType.GLOBULAR; in ngcOpen -> ObjectType.OPEN_CLUSTER; else -> null }
            if (t != null) return Pair(t, "NGC$k")
        }
        Regex("IC(\\d{1,4})").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { k -> if (k in icEmission) return Pair(ObjectType.EMISSION_NEBULA, "IC$k") }
        val words = mapOf("ANDROMEDA" to ObjectType.GALAXY, "ORION" to ObjectType.EMISSION_NEBULA, "PLEIADES" to ObjectType.REFLECTION_NEBULA, "FIASTYUK" to ObjectType.REFLECTION_NEBULA,
            "NEBULA" to ObjectType.EMISSION_NEBULA, "KOD" to ObjectType.EMISSION_NEBULA, "GALAXY" to ObjectType.GALAXY, "GALAXIS" to ObjectType.GALAXY,
            "CLUSTER" to ObjectType.OPEN_CLUSTER, "HALMAZ" to ObjectType.OPEN_CLUSTER, "HERCULES" to ObjectType.GLOBULAR, "WHIRLPOOL" to ObjectType.GALAXY,
            "TRIANGULUM" to ObjectType.GALAXY, "LAGOON" to ObjectType.EMISSION_NEBULA, "TRIFID" to ObjectType.EMISSION_NEBULA, "ROSETTE" to ObjectType.EMISSION_NEBULA,
            "VEIL" to ObjectType.EMISSION_NEBULA, "HEART" to ObjectType.EMISSION_NEBULA, "SOUL" to ObjectType.EMISSION_NEBULA, "DUMBBELL" to ObjectType.PLANETARY_NEBULA, "RING" to ObjectType.PLANETARY_NEBULA)
        for ((w, t) in words) if (n.contains(w)) return Pair(t, w.lowercase().replaceFirstChar { it.uppercase() })
        return null
    }

    // ------------------------------------------------------------------ elemzés
    fun analyze(src: FloatImage, nameHint: String?): Analysis {
        val img = src.downscale(700)
        val w = img.width; val h = img.height
        val lum = img.toMono().data[0]
        val bg = Processor.median(lum)
        val noise = (Processor.mad(lum, bg) * 1.4826f).coerceAtLeast(1e-6f)

        // gradiens: 4×4 rács alsó-kvartilis mediánjainak terjedelme a zajhoz képest
        val grid = 4; val cells = FloatArray(grid * grid)
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val xs = gx * w / grid; val xe = (gx + 1) * w / grid; val ys = gy * h / grid; val ye = (gy + 1) * h / grid
            val list = ArrayList<Float>(); var y = ys
            while (y < ye) { var x = xs; while (x < xe) { list.add(lum[y * w + x]); x += 3 }; y += 3 }
            list.sort(); cells[gy * grid + gx] = list[(list.size * 0.2f).toInt()]
        }
        val gradient = (cells.max() - cells.min()) / noise

        // színcast: csatorna-mediánok eltérése
        val cast = if (img.channels == 3) FloatArray(3) { Processor.median(img.data[it]) } else floatArrayOf(bg, bg, bg)
        val castMean = cast.average().toFloat().coerceAtLeast(1e-6f)
        val castRel = FloatArray(3) { (cast[it] - castMean) / castMean }

        // csillagok
        val stars = hu.novaprocess.stack.StarDetector.detect(img, maxStars = 400, sigmaK = 5f)
        var fwhm = 0f; var nF = 0
        for (s in stars.take(80)) {
            val cx = s.x.toInt(); val cy = s.y.toInt(); if (cx < 3 || cy < 3 || cx >= w - 3 || cy >= h - 3) continue
            var m00 = 0f; var mxx = 0f; var myy = 0f
            for (dy in -3..3) for (dx in -3..3) { val p = (lum[(cy + dy) * w + cx + dx] - bg).coerceAtLeast(0f); m00 += p; mxx += p * dx * dx; myy += p * dy * dy }
            if (m00 > 0f) { fwhm += 2.355f * sqrt(((mxx + myy) / (2f * m00)).coerceAtLeast(1e-4f)); nF++ }
        }
        if (nF > 0) fwhm /= nF
        val scale = max(src.width, src.height).toFloat() / max(w, h)
        fwhm *= scale

        // kiterjedt jel: csillagmentesített (nyitás) + elmosott kép a háttér + 2.5σ fölött
        val opened = Processor.boxBlur(openArr(lum, w, h, 2), w, h, 3)
        var ext = 0; var rSum = 0f; var gSum = 0f; var bSum = 0f; var maxExt = 0f
        val thr = bg + 2.5f * noise
        for (i in 0 until w * h) if (opened[i] > thr) {
            ext++; if (opened[i] > maxExt) maxExt = opened[i]
            if (img.channels == 3) { rSum += img.data[0][i] - cast[0]; gSum += img.data[1][i] - cast[1]; bSum += img.data[2][i] - cast[2] }
        }
        val extendedFrac = ext.toFloat() / (w * h)
        val tot = (abs(rSum) + abs(gSum) + abs(bSum)).coerceAtLeast(1e-6f)
        val rF = rSum / tot; val gF = gSum / tot; val bF = bSum / tot
        val hue = when {
            ext < 50 -> "nincs"
            rF > 0.45f && bF < 0.3f -> "vörös (Hα)"
            bF > 0.42f && rF < 0.35f -> "kék"
            rF > 0.33f && bF > 0.3f && gF < 0.3f -> "vörös+kék (dual-band)"
            abs(rF - bF) < 0.12f -> "semleges/sárgás"
            else -> "vegyes"
        }
        val greenExcess = if (img.channels == 3 && ext > 50) (gF - (rF + bF) / 2f) else 0f
        val dynRange = ((maxExt - bg) / noise).coerceAtLeast(0f)

        // típus: név > mért jellemzők
        val byName = typeFromName(nameHint)
        val (type, source) = byName ?: Pair(when {
            extendedFrac > 0.04f && hue.startsWith("vörös") -> ObjectType.EMISSION_NEBULA
            extendedFrac > 0.04f && hue == "kék" -> ObjectType.REFLECTION_NEBULA
            extendedFrac > 0.02f && hue == "semleges/sárgás" && dynRange > 25f -> ObjectType.GALAXY
            extendedFrac < 0.01f && stars.size > 250 -> ObjectType.OPEN_CLUSTER
            extendedFrac in 0.002f..0.03f && dynRange > 60f && stars.size > 150 -> ObjectType.GLOBULAR
            extendedFrac < 0.01f -> ObjectType.STARFIELD
            else -> ObjectType.UNKNOWN
        }, "mért jellemzők")
        return Analysis(bg, noise, gradient, castRel, stars.size, fwhm, extendedFrac, hue, dynRange, greenExcess, type, source)
    }

    private fun openArr(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        fun f(src: FloatArray, isMin: Boolean): FloatArray {
            val t = FloatArray(src.size); val o = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) { var v = src[y * w + x]; for (d in -r..r) { val s = src[y * w + (x + d).coerceIn(0, w - 1)]; v = if (isMin) min(v, s) else max(v, s) }; t[y * w + x] = v }
            for (y in 0 until h) for (x in 0 until w) { var v = t[y * w + x]; for (d in -r..r) { val s = t[(y + d).coerceIn(0, h - 1) * w + x]; v = if (isMin) min(v, s) else max(v, s) }; o[y * w + x] = v }
            return o
        }
        return f(f(a, true), false)
    }

    /** Asinh erősség, amelynél a (lineáris) háttér a kívánt kimeneti szintre kerül. */
    fun asinhFor(bgLinear: Float, target: Float): Float {
        val bg = bgLinear.coerceIn(1e-4f, 0.5f)
        var lo = 1f; var hi = 1000f
        repeat(40) {
            val k = (lo + hi) / 2f
            val out = (kotlin.math.asinh(k * bg) / kotlin.math.asinh(k))
            if (out > target) hi = k else lo = k
        }
        return ((lo + hi) / 2f).coerceIn(2f, 1000f)
    }

    // ------------------------------------------------------------------ szabályok + finomhangolás
    fun run(src: FloatImage, nameHint: String?, frames: Int = 0): Result {
        val a = analyze(src, nameHint)
        val bgAfterBe = a.bg   // a háttér-kivonás a mediánt megtartja
        val notes = ArrayList<String>()
        notes += "Objektum: ${a.type.label} (${a.typeSource})"
        var p = ProcessParams()

        // háttér-gradiens
        p = when {
            a.gradient > 12f -> { notes += "Erős gradiens (${"%.0f".format(a.gradient)}σ) → másodfokú háttér-kivonás"; p.copy(backgroundExtraction = true, backgroundDegree = 2) }
            a.gradient > 4f -> { notes += "Enyhe gradiens → sík háttér-kivonás"; p.copy(backgroundExtraction = true, backgroundDegree = 1) }
            else -> { notes += "Egyenletes háttér, nincs gradiens-kivonás"; p }
        }
        // színcast
        val castMax = a.cast.maxOf { abs(it) }
        if (castMax > 0.03f) { notes += "Színcast a háttérben (${"%.0f".format(castMax * 100)}%) → háttér semlegesítés"; p = p.copy(backgroundNeutralize = true) }
        if (a.stars >= 30) { notes += "${a.stars} csillag → fehéregyensúly csillagokra"; p = p.copy(whiteBalanceStars = true) }
        if (a.greenExcess > 0.06f) { notes += "Zöld többlet a jelben → SCNR"; p = p.copy(scnr = 0.6f) }

        // zaj / kockaszám
        val snrLow = a.dynRange < 15f
        val denoise = when { frames in 1..15 -> 0.45f; snrLow -> 0.4f; frames in 16..40 -> 0.3f; else -> 0.2f }
        if (denoise > 0f) notes += "Zajszűrés ${"%.0f".format(denoise * 100)}% (${if (frames > 0) "$frames kocka" else "jel/zaj alapján"})"
        p = p.copy(denoise = denoise, denoiseRadius = if (denoise > 0.35f) 3 else 2)

        // csillagok mérete
        val bloat = a.fwhm > 4.5f
        if (bloat) notes += "Nagy csillagok (FWHM ${"%.1f".format(a.fwhm)} px) → csillagcsökkentés"

        // típus szerinti stretch és eszközök
        p = when (a.type) {
            ObjectType.GALAXY -> {
                notes += "Galaxis: autostretch + HDR magkompresszió + enyhe clarity"
                p.copy(stretchMode = StretchMode.AUTO, mtfMidtones = 0.22f, mtfShadowClip = 2.8f, hdrCompress = if (a.dynRange > 80f) 0.4f else 0.25f, hdrRadius = 24,
                    starReduction = if (bloat) 0.4f else 0.25f, clarity = 0.15f, saturation = 0.15f, vibrance = 0.2f, deconvIterations = if (a.fwhm in 2f..4f) 6 else 0, deconvSigma = (a.fwhm / 2.355f).coerceIn(0.7f, 1.6f))
            }
            ObjectType.EMISSION_NEBULA -> {
                notes += "Emissziós köd: asinh stretch + csillagtalan réteg (köd kiemelés), csillagok 60%-ra"
                p.copy(stretchMode = StretchMode.ASINH, asinhStrength = asinhFor(bgAfterBe, 0.16f), starless = true, starlessRadius = if (bloat) 6 else 4, starsBlend = 0.6f, nebulaBoost = 0.2f,
                    saturation = 0.2f, vibrance = 0.3f)
            }
            ObjectType.REFLECTION_NEBULA -> {
                notes += "Reflexiós köd: asinh stretch, finom csillagcsökkentés, kék telítettség megtartva"
                p.copy(stretchMode = StretchMode.ASINH, asinhStrength = asinhFor(bgAfterBe, 0.17f), starReduction = if (bloat) 0.35f else 0.2f, starRadius = 3, vibrance = 0.35f, scnr = 0f)
            }
            ObjectType.PLANETARY_NEBULA -> {
                notes += "Planetáris köd: MTF stretch, HDR a fényes magra, dekonvolúció"
                p.copy(stretchMode = StretchMode.AUTO, mtfMidtones = 0.18f, hdrCompress = 0.5f, hdrRadius = 10, deconvIterations = 10, deconvSigma = (a.fwhm / 2.355f).coerceIn(0.8f, 1.5f), sharpen = 0.2f, saturation = 0.25f)
            }
            ObjectType.GLOBULAR -> {
                notes += "Gömbhalmaz: asinh (a mag ne égjen ki) + HDR + dekonvolúció"
                p.copy(stretchMode = StretchMode.ASINH, asinhStrength = asinhFor(bgAfterBe, 0.14f), hdrCompress = 0.5f, hdrRadius = 12, deconvIterations = 8, deconvSigma = (a.fwhm / 2.355f).coerceIn(0.8f, 1.5f), sharpen = 0.25f, saturation = 0.3f, denoise = denoise * 0.6f)
            }
            ObjectType.OPEN_CLUSTER, ObjectType.STARFIELD -> {
                notes += "Nyílthalmaz / csillagmező: visszafogott autostretch, csillagszínek kiemelése"
                p.copy(stretchMode = StretchMode.AUTO, mtfMidtones = 0.2f, mtfShadowClip = 3f, saturation = 0.35f, vibrance = 0.2f, sharpen = 0.15f, denoise = denoise * 0.7f, starReduction = if (bloat) 0.25f else 0f)
            }
            ObjectType.UNKNOWN -> {
                notes += "Típus bizonytalan: biztonságos autostretch + enyhe eszközök"
                p.copy(stretchMode = StretchMode.AUTO, mtfMidtones = 0.22f, saturation = 0.15f, starReduction = if (bloat) 0.3f else 0f)
            }
        }
        if (a.extendedHue == "vörös+kék (dual-band)") notes += "Dual-band jellegű színek: a HOO paletta a Paletta fülön egy érintéssel kipróbálható"

        // --- visszacsatolás: háttér 0.14–0.27 (Siril-szerű ~0.2), csúcs-vágás < 0.2 % ---
        val probe = src.downscale(500)
        fun measure(pp: ProcessParams): Pair<Float, Float> {
            val out = Processor.process(probe, pp)
            val lum = out.toMono().data[0]
            val med = Processor.median(lum)
            var clip = 0; for (v in lum) if (v >= 0.995f) clip++
            return Pair(med, clip.toFloat() / lum.size)
        }
        var med = 0f; var clipFrac = 0f; var iters = 0
        for (iter in 0 until 10) {
            val m = measure(p); med = m.first; clipFrac = m.second; iters = iter
            var changed = false
            // arányos szabályozás: minél messzebb a cél (0.16), annál nagyobb lépés
            if (med > 0.27f) { p = cooler(p, (0.20f / med).coerceIn(0.5f, 0.9f)); changed = true }
            else if (med < 0.14f) { p = cooler(p, (0.20f / med.coerceAtLeast(0.02f)).coerceIn(1.1f, 2.2f)); changed = true }
            if (clipFrac > 0.002f) { p = p.copy(highlights = (p.highlights - 0.25f).coerceAtLeast(-0.8f), hdrCompress = min(0.7f, p.hdrCompress + 0.15f)); changed = true }
            if (!changed) break
        }
        // biztonsági háló: ha így sem jó, visszaesés egyszerű autostretch-re
        if (med > 0.4f || med < 0.08f || clipFrac > 0.01f) {
            p = p.copy(stretchMode = StretchMode.AUTO, mtfMidtones = 0.22f, mtfShadowClip = 2.8f, nebulaBoost = 0f, hdrCompress = min(p.hdrCompress, 0.3f), exposure = 0f, highlights = 0f, deconvIterations = 0)
            val m = measure(p); med = m.first; clipFrac = m.second
            notes += "A típus-specifikus stretch túl erős/gyenge volt → biztonságos autostretch"
        }
        notes += "Finomhangolás ($iters lépés): háttér ${"%.2f".format(med)}, kiégés ${"%.2f".format(clipFrac * 100)}%"
        return Result(p, a, notes)
    }

    /** A stretch erősségének léptékezése (k<1 sötétít, k>1 világosít). */
    private fun cooler(p: ProcessParams, k: Float): ProcessParams = when (p.stretchMode) {
        StretchMode.ASINH -> p.copy(asinhStrength = (p.asinhStrength * k).coerceIn(5f, 1000f))
        StretchMode.AUTO, StretchMode.MTF -> p.copy(mtfMidtones = (p.mtfMidtones * k).coerceIn(0.05f, 0.6f))
        StretchMode.NONE -> p.copy(exposure = p.exposure + (if (k > 1f) 0.3f else -0.3f))
    }
}

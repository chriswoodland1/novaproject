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
    fun analyze(src: FloatImage, nameHint: String?, knownType: ObjectType? = null): Analysis {
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
        val byName = knownType?.let { Pair(it, "katalógus") } ?: typeFromName(nameHint)
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

    // ------------------------------------------------------------------ photon-finish motor (MIT, Colin Bryar)
    /**
     * A Seestar "AI Enhance"-hez hasonló, mért (nem névalapú) fejlesztő motor – a nyílt forrású photon-finish
     * (github.com/ColinB-WB/photon-finish, MIT) döntési szabályainak 1:1 portja a saját feldolgozónkra:
     *  1. háttér-kivonás mindig (GraXpert-szerep → saját RBF/poly), semlegesítés mindig
     *  2. zajszűrés kategória szerint: 0.5 alap, köd 0.6, csillagmező 0.6, galaxis 0.65, +0.1 ha zajos
     *  3. szín: PCC (csillag-fehéregyensúly) csak galaxisnál; LP-ködnél és csillagmezőnél KI (ne legyen rózsaszín)
     *  4. stretch: asinh bp = 0.987·medián, D = köd 75 / galaxis 55 / csillagmező 65, majd `mtf 0 m 1`,
     *     ahol m-et felezéssel (0.02..0.25, tűrés 0.5/255, max 10 lépés) úgy oldjuk meg, hogy
     *     a maszkolt objektum p90 ≈ 72/255 (kiterjedt) ill. az ég mediánja ≈ 10/255 (csillagmező);
     *     kétpontos: ha az ég > 18/255, bp-t emeljük (soha a medián fölé) és újraoldjuk
     *  5. telítettség: köd 0.60 / csillagmező 0.55 / galaxis 0.60
     *  6. csillagcsökkentés (screen-blend k≈0.45) csak kiterjedt objektumnál; halmaznál soha
     *  7. QA-kapuk: közel-fekete ≤ 2 %, ég-plafon, beragadt színcsatorna ≤ 20/10/15 % → telítettség 0.85×/0.70×
     */
    enum class Category(val label: String) { NEBULA("köd"), GALAXY("galaxis"), STARFIELD("csillagmező/halmaz") }

    fun categoryOf(t: ObjectType): Category = when (t) {
        ObjectType.GALAXY -> Category.GALAXY
        ObjectType.EMISSION_NEBULA, ObjectType.REFLECTION_NEBULA, ObjectType.PLANETARY_NEBULA, ObjectType.UNKNOWN -> Category.NEBULA
        ObjectType.GLOBULAR, ObjectType.OPEN_CLUSTER, ObjectType.STARFIELD -> Category.STARFIELD
    }

    private const val D_NEBULA = 75f; private const val D_GALAXY = 55f; private const val D_STARFIELD = 65f
    private const val BODY_P90_TARGET = 72f / 255f
    private const val SKY_MEDIAN_TARGET = 10f / 255f
    private const val SKY_MEDIAN_CEIL = 22f / 255f   // photon-finish 18/255 MI-zajszűrt adaton; zajos adatnál kicsit világosabb ég, de nem foltos
    private const val SOLVE_TOL = 0.5f / 255f

    /**
     * @param fullNoise a luminancia zajának szórása TELJES felbontáson (lineáris 0..1). Az elemzés kicsinyített képen fut,
     *                  ahol a zaj többszörösen kisebb – a feketepontot viszont a teljes képhez kell igazítani, különben
     *                  a teljes felbontású égnek akár a fele a feketepont alá esik (foltos, fekete ég).
     */
    fun run(src: FloatImage, nameHint: String?, frames: Int = 0, displayReferred: Boolean = false, knownType: ObjectType? = null, fullNoise: Float? = null): Result {
        val a = analyze(src, nameHint, knownType)
        if (displayReferred) return runDisplayReferred(src, a)
        val notes = ArrayList<String>()
        val cat = categoryOf(a.type)
        notes += "Objektum: ${a.type.label} (${a.typeSource}) → kezelés: ${cat.label}"
        notes += "Motor: photon-finish (MIT) mért döntési szabályai – nem névből, hanem a kép méréseiből"
        val extended = cat != Category.STARFIELD
        val lpLike = a.extendedHue.startsWith("vörös")          // Hα-domináns jel ≈ LP-szűrős köd
        val noisy = a.dynRange < 15f || frames in 1..15

        // 1. háttér + semlegesítés (mindig)
        var p = ProcessParams(backgroundExtraction = true, backgroundDegree = if (a.gradient > 12f) 2 else 1, backgroundNeutralize = true, neutralizeAfterStretch = true)
        notes += "Égbolt-glow kivonás (${if (p.backgroundDegree == 2) "másodfokú" else "sík"}, gradiens ${"%.0f".format(a.gradient)}σ) + háttér semlegesítés"

        // 2. zajszűrés
        var dn = when (cat) { Category.NEBULA -> 0.6f; Category.STARFIELD -> 0.6f; Category.GALAXY -> 0.65f }
        if (noisy) dn += 0.1f
        // a photon-finish értékei a GraXpert MI-zajszűrőjére vonatkoznak; a saját (stretch utáni) szűrőnk ennél jóval erősebben mos,
        // ezért ~0.6× arányban képezzük le, hogy ne legyen „műanyag” a kép
        val dnOwn = (dn * 0.6f).coerceIn(0.25f, 0.5f)
        p = p.copy(denoise = dnOwn, denoiseRadius = 2, chromaDenoise = if (noisy) 0.8f else 0.6f, chromaRadius = 4)
        notes += "Zajszűrés: photon-finish ${"%.2f".format(dn)}${if (noisy) " (+0.1 zajos/rövid)" else ""} → saját szűrő ${"%.0f".format(dnOwn * 100)} % + színzaj-szűrés"

        // 3. szín
        when {
            cat == Category.GALAXY -> { p = p.copy(whiteBalanceStars = a.stars >= 15); notes += "Galaxis: fotometriai jellegű színkorrekció (csillag-fehéregyensúly), hogy ne legyen narancs" }
            cat == Category.NEBULA && lpLike -> notes += "Hα-domináns köd: színkorrekció KI – a Seestar vörös marad vörös, nem rózsaszín"
            cat == Category.NEBULA -> { p = p.copy(whiteBalanceStars = a.stars >= 15); notes += "Köd (szélessávú): csillag-fehéregyensúly" }
            else -> notes += "Halmaz/csillagmező: színkorrekció KI (különben rózsaszínbe fordul)"
        }
        if (a.greenExcess > 0.06f) { p = p.copy(scnr = 0.6f); notes += "Zöld többlet a jelben → SCNR" }

        // 4. stretch: asinh (bp, D) + mtf megoldás
        val D = when (cat) { Category.NEBULA -> D_NEBULA; Category.GALAXY -> D_GALAXY; Category.STARFIELD -> D_STARFIELD }
        val probe = src.downscale(500)
        val pre = Processor.process(probe, p.copy(stretchMode = StretchMode.NONE, denoise = 0f, chromaDenoise = 0f))
        val chMed = FloatArray(pre.channels) { Processor.median(pre.data[it]) }
        val medLin = chMed.min().coerceAtLeast(1e-5f)
        // objektum-maszk a lineáris képen (nyitás + elmosás, háttér + 2.5σ felett) – a p90-et ezen mérjük
        val preLum = pre.toMono().data[0]
        val bgL = Processor.median(preLum); val nz = (Processor.mad(preLum, bgL) * 1.4826f).coerceAtLeast(1e-6f)
        val opened = Processor.boxBlur(openArr(preLum, pre.width, pre.height, 2), pre.width, pre.height, 3)
        val mask = BooleanArray(preLum.size) { opened[it] > bgL + 2.5f * nz }
        val maskN = mask.count { it }
        val useBody = extended && maskN > preLum.size / 400
        val targetLabel = if (useBody) "objektum p90 ≈ 72/255" else "ég medián ≈ 10/255"

        // feketepont: photon-finish 0.987·medián, DE legalább 2.8σ-val a medián alatt (teljes felbontású zaj) – így az ég
        // legfeljebb ~0.3 %-a vágódik feketére. (A photon-finish ezt GraXpert MI-zajszűrés után mérné, ott a zaj elhanyagolható.)
        val sigmaFull = (fullNoise ?: a.noise * 3f).coerceAtLeast(1e-6f)
        var kSig = 2.8f
        fun bpFor(k: Float) = min(0.987f * medLin, medLin - k * sigmaFull).coerceIn(0f, 0.2f)
        p = p.copy(stretchMode = StretchMode.ASINH, asinhStrength = D, blackPoint = bpFor(kSig))

        // a stretch utáni, a mért értéket befolyásoló lépések MOST rögzülnek, hogy a megoldás a végleges láncot mérje
        if (cat == Category.GALAXY && a.dynRange > 20f) { p = p.copy(ghsEnabled = true, ghsD = 1.2f, ghsB = 6f, ghsSP = 0.15f, ghsLP = 0.05f, ghsHP = 0.7f); notes += "Galaxis: GHS (SP 0.15) a halvány korong és porsávok kiemelésére" }
        var sat = when (cat) { Category.NEBULA -> 0.60f; Category.STARFIELD -> 0.55f; Category.GALAXY -> 0.60f }
        p = p.copy(saturation = sat * 0.75f)   // a saját telítettség-csúszkánk erősebb, mint a Siril satu

        data class M(val sky: Float, val body: Float, val nearBlack: Float, val clip: Float, val pegged: Float)
        fun measure(pp: ProcessParams): M {
            val out = Processor.process(probe, pp)
            val lum = out.toMono().data[0]
            val sky = Processor.percentile(lum, 0.5f)
            val bodyVals = if (useBody) { val l = ArrayList<Float>(maskN); for (i in lum.indices) if (mask[i]) l.add(lum[i]); l.sort(); l[(l.size * 0.9f).toInt().coerceAtMost(l.size - 1)] } else sky
            var nb = 0; var cl = 0; var peg = 0
            for (i in lum.indices) { if (lum[i] <= 0.5f / 255f) nb++; if (lum[i] >= 0.995f) cl++ }
            if (out.channels == 3) for (i in lum.indices) { val r = out.data[0][i]; val g = out.data[1][i]; val b = out.data[2][i]; if ((r >= 0.995f || g >= 0.995f || b >= 0.995f) && min(r, min(g, b)) < 0.9f) peg++ }
            return M(sky, bodyVals, nb.toFloat() / lum.size, cl.toFloat() / lum.size, peg.toFloat() / lum.size)
        }
        var iters = 0
        fun solve(): M {
            // m kisebb → világosabb; felezés: ha a metrika a cél alatt van, m-et csökkentjük
            var lo = 0.02f; var hi = 0.95f; var best = p.copy(mtfMid = 0.1f); var bestM = measure(best); var bestErr = Float.MAX_VALUE
            val target = if (useBody) BODY_P90_TARGET else SKY_MEDIAN_TARGET
            for (i in 0 until 10) {
                val m = (lo + hi) / 2f; val pp = p.copy(mtfMid = m); val mm = measure(pp); iters++
                val v = if (useBody) mm.body else mm.sky
                val err = abs(v - target)
                if (err < bestErr) { bestErr = err; best = pp; bestM = mm }
                if (err <= SOLVE_TOL) break
                if (v < target) hi = m else lo = m
            }
            p = best; return bestM
        }
        var m = solve()
        // kétpontos: túl világos ég → bp emelése (soha a medián fölé), újraoldás
        var twoPoint = 0
        // kétpontos: túl világos ég → bp közelebb a mediánhoz, de legalább 1.5σ-ra maradjon alatta
        while (m.sky > SKY_MEDIAN_CEIL && kSig > 2.0f && twoPoint < 3) {
            kSig = (kSig - 0.4f).coerceAtLeast(2.0f); p = p.copy(blackPoint = bpFor(kSig)); m = solve(); twoPoint++
        }
        val clippedSky = 0.5f * erfc(kSig / 1.41421356f)
        notes += "Stretch: asinh (feketepont = medián − ${"%.1f".format(kSig)}σ, D = ${"%.0f".format(D)}) + MTF m = ${"%.3f".format(p.mtfMid)} (${targetLabel}; $iters mérés${if (twoPoint > 0) ", kétpontos bp-emelés ×$twoPoint" else ""}) · teljes felbontáson az ég ~${"%.1f".format(clippedSky * 100)} %-a fekete"

        notes += "Telítettség: photon-finish ${"%.2f".format(sat)} → saját ${"%.0f".format(sat * 75)} %"

        // 6. csillagok: csak kiterjedt objektumnál csökkentés (screen-blend ~0.45); halmaznál soha
        // photon-finish: „az ajánlott fotó minden csillagot megtart” – a csillagcsökkentett változat csak külön opció
        notes += if (extended) "Csillagok: mind megmarad (photon-finish ajánlott változat); csillagcsökkentés kézzel: Csillagok fül" else "Halmaz/csillagmező: a csillagok a téma – nincs csillagcsökkentés"
        if (a.fwhm in 2f..4f && cat == Category.GALAXY) p = p.copy(deconvIterations = 5, deconvSigma = (a.fwhm / 2.355f).coerceIn(0.7f, 1.6f))

        // 7. QA-kapuk
        var q = measure(p)
        val pegLimit = when (cat) { Category.NEBULA -> 0.20f; Category.STARFIELD -> 0.10f; Category.GALAXY -> 0.15f }
        if (q.pegged > pegLimit * 0.05f) { sat *= 0.85f; p = p.copy(saturation = sat * 0.75f); q = measure(p); notes += "QA: beragadt színcsatorna → telítettség 0.85×"
            if (q.pegged > pegLimit * 0.05f) { sat = sat / 0.85f * 0.70f; p = p.copy(saturation = sat * 0.75f); q = measure(p); notes += "QA: még mindig → telítettség 0.70×" } }
        if (q.nearBlack > 0.03f || clippedSky > 0.03f) { kSig += 0.7f; p = p.copy(blackPoint = bpFor(kSig)); q = measure(p); notes += "QA: közel-fekete ég → feketepont lejjebb (medián − ${"%.1f".format(kSig)}σ)" }
        if (q.clip > 0.003f) { p = p.copy(highlights = -0.3f, hdrCompress = 0.25f, hdrRadius = 16); q = measure(p); notes += "QA: kiégett csúcsfények → highlights −0.3, HDR" }
        notes += "Eredmény (előnézeten): ég ${"%.0f".format(q.sky * 255)}/255, ${if (useBody) "objektum p90 ${"%.0f".format(q.body * 255)}/255, " else ""}közel-fekete ${"%.1f".format(q.nearBlack * 100)}%, kiégés ${"%.2f".format(q.clip * 100)}%"
        notes += "Forrás: photon-finish – github.com/ColinB-WB/photon-finish (MIT)"
        return Result(p, a, notes)
    }

    /** Kész (már megnyújtott, pl. Seestar) kép: nincs újranyújtás, csak visszafogott fotós finomítás. */
    private fun runDisplayReferred(src: FloatImage, a: Analysis): Result {
        val notes = ArrayList<String>()
        notes += "Kész kép (JPEG/PNG): a meglévő feldolgozást megtartjuk, csak finomítunk"
        notes += "Objektum: ${a.type.label} (${a.typeSource})"
        var p = ProcessParams(stretchMode = StretchMode.NONE)
        val castMax = a.cast.maxOf { abs(it) }
        if (castMax > 0.04f) { notes += "Színcast a háttérben → semlegesítés"; p = p.copy(backgroundNeutralize = true) }
        if (a.gradient > 10f) { notes += "Gradiens → sík háttér-kivonás"; p = p.copy(backgroundExtraction = true, backgroundDegree = 1) }
        if (a.greenExcess > 0.06f) { notes += "Zöld többlet → SCNR"; p = p.copy(scnr = 0.5f) }
        p = p.copy(chromaDenoise = 0.4f, denoise = 0.15f, clarity = 0.1f, vibrance = 0.15f, sharpen = 0.1f)
        notes += "Enyhe színzaj-szűrés, clarity, vibrance, élesítés"
        val probe = src.downscale(500)
        val out = Processor.process(probe, p); val lum = out.toMono().data[0]; val med = Processor.median(lum)
        if (med < 0.06f) { p = p.copy(shadows = 0.3f, exposure = 0.3f); notes += "Sötét kép → árnyékok emelése" }
        else if (med > 0.35f) { p = p.copy(blacks = -0.3f, contrast = 0.1f); notes += "Világos háttér → feketék mélyítése" }
        return Result(p, a, notes)
    }

    /** Komplementer hibafüggvény (Abramowitz–Stegun 7.1.26), a feketére vágott égrész becsléséhez. */
    private fun erfc(x: Float): Float {
        val t = 1f / (1f + 0.3275911f * abs(x))
        val y = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f)))) * kotlin.math.exp(-x * x)
        return if (x >= 0) y else 2f - y
    }
}

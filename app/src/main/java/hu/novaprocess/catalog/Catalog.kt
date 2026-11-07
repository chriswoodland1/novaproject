package hu.novaprocess.catalog

import android.content.Context
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline mélyég-katalógus: Messier (110) + Caldwell (109) + a fényesebb / nagyobb / névvel bíró NGC–IC objektumok (~750).
 * Forrás: OpenNGC (github.com/mattiaverga/OpenNGC, CC-BY-SA-4.0) kivonata az assets/catalog.csv-ben.
 * Magyar nevek, távolságok és csillagkép-nevek: saját kiegészítő táblák.
 */
data class CatObject(
    val name: String,            // NGC0224 / IC0434 / B033
    val type: String,            // OpenNGC típuskód (G, OCl, GCl, PN, Neb, HII, RfN, SNR, Cl+N, DrkN, *Ass …)
    val ra: Double, val dec: Double,   // fok (J2000)
    val const: String,           // IAU 3 betűs
    val majArcmin: Float, val minArcmin: Float, val paDeg: Float,
    val vmag: Float?,            // V (vagy B) magnitúdó
    val messier: Int, val caldwell: Int,
    val common: String           // angol köznév(ek)
) {
    /** Rövid katalógus-azonosító: M 31 / C 14 / NGC 7000 / IC 1396 / B 33 */
    val id: String get() = when {
        messier > 0 -> "M $messier"
        caldwell > 0 -> "C $caldwell"
        else -> Regex("^([A-Za-z]+)0*(\\d+.*)$").find(name)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: name
    }
    val ngcId: String? get() = Regex("^(NGC|IC)0*(\\d+.*)$").find(name)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
    val huName: String? get() = Catalog.huNames[id] ?: Catalog.huNames[name]
    val distanceLy: Double? get() = Catalog.distances[id] ?: Catalog.distances[name]
    val typeLabel: String get() = Catalog.typeLabel(type)
    val constellationHu: String get() = Catalog.constellations[const] ?: const
    /** Megjelenítendő cím: "M 31 · Androméda-galaxis" vagy "NGC 7000 · North America Nebula" */
    val title: String get() = listOfNotNull(id, huName ?: common.substringBefore(" / ").ifBlank { null }).joinToString(" · ")
    /** Minden alternatív név egy sorban. */
    val allNames: String get() = listOfNotNull(if (messier > 0 || caldwell > 0) ngcId else null, common.ifBlank { null }).joinToString(" · ")
    /** Fontosság a látómező-azonosításhoz: nagyobb és fényesebb előre. */
    val prominence: Double get() = (if (messier > 0) 3.0 else if (caldwell > 0) 2.0 else 0.0) + (if (common.isNotBlank()) 1.0 else 0.0) + kotlin.math.ln(1.0 + majArcmin) - (vmag ?: 12f) * 0.15
}

data class Match(val obj: CatObject, val offsetDeg: Double, val inField: Boolean, val fieldFrac: Float /* az objektum nagyobb átmérője a látómező rövidebb oldalához */)

object Catalog {
    @Volatile private var items: List<CatObject>? = null

    fun load(ctx: Context): List<CatObject> {
        items?.let { return it }
        synchronized(this) {
            items?.let { return it }
            val list = ArrayList<CatObject>(800)
            ctx.assets.open("catalog.csv").bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("#") || l.isBlank()) continue
                    val f = l.split(';'); if (f.size < 12) continue
                    list += CatObject(f[0], f[1], f[2].toDouble(), f[3].toDouble(), f[4], f[5].toFloatOrNull() ?: 0f, f[6].toFloatOrNull() ?: 0f, f[7].toFloatOrNull() ?: -1f,
                        f[8].toFloatOrNull(), f[9].toIntOrNull() ?: 0, f[10].toIntOrNull() ?: 0, f[11])
                }
            }
            items = list; return list
        }
    }

    /** Szögtávolság fokban. */
    fun sep(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = Math.PI / 180
        val a = sin((dec2 - dec1) * r / 2); val b = sin((ra2 - ra1) * r / 2)
        val h = a * a + cos(dec1 * r) * cos(dec2 * r) * b * b
        return 2 * atan2(sqrt(h), sqrt(1 - h)) / r
    }

    /**
     * Mi van a látómezőben? Középpont (fok), látómező szélesség/magasság (fok).
     * A találatok fontosság szerint; a mezőn belüliek előre.
     */
    fun identify(ctx: Context, raDeg: Double, decDeg: Double, fovWDeg: Double, fovHDeg: Double): List<Match> {
        val half = sqrt(fovWDeg * fovWDeg + fovHDeg * fovHDeg) / 2
        val shortSide = minOf(fovWDeg, fovHDeg)
        return load(ctx).mapNotNull { o ->
            val d = sep(raDeg, decDeg, o.ra, o.dec)
            val radius = o.majArcmin / 120.0
            if (d - radius > half * 1.3) return@mapNotNull null
            Match(o, d, d - radius <= half, (o.majArcmin / 60.0 / shortSide).toFloat())
        }.sortedWith(compareByDescending<Match> { it.inField }.thenByDescending { it.obj.prominence - it.offsetDeg * 0.5 })
    }

    /** Név szerinti keresés: "M31", "M 31", "NGC 7000", "ngc7000", "C14", "Andromeda", "Androméda". */
    fun find(ctx: Context, query: String?): CatObject? {
        if (query.isNullOrBlank()) return null
        val q = query.trim().uppercase().replace(Regex("[\\s_-]+"), "")
        val all = load(ctx)
        Regex("^M(\\d{1,3})$").find(q)?.groupValues?.get(1)?.toIntOrNull()?.let { m -> all.firstOrNull { it.messier == m }?.let { return it } }
        Regex("^C(\\d{1,3})$").find(q)?.groupValues?.get(1)?.toIntOrNull()?.let { c -> all.firstOrNull { it.caldwell == c }?.let { return it } }
        Regex("^(NGC|IC|B)(\\d+[A-Z]?)$").find(q)?.let { m -> val key = m.groupValues[1] + m.groupValues[2].padStart(4, '0'); all.firstOrNull { it.name.uppercase() == key }?.let { return it } }
        // köznév (magyar vagy angol) részlete
        val qn = query.trim().lowercase()
        huNames.entries.firstOrNull { it.value.lowercase().contains(qn) || qn.contains(it.value.lowercase().substringBefore('-')) && qn.length >= 5 }?.let { e -> all.firstOrNull { it.id == e.key || it.name == e.key }?.let { return it } }
        return all.firstOrNull { it.common.isNotBlank() && it.common.lowercase().split(" / ").any { c -> c == qn || (qn.length >= 5 && c.contains(qn)) } }
    }

    /** Legjobb hónap: amikor az objektum éjfél körül delel (LST(éjfél) ≈ 6.7h + 2h·(hónap−1)). */
    fun bestMonth(raDeg: Double): String {
        val raH = raDeg / 15.0
        var m = ((raH - 6.7) / 2.0 + 1.0)
        while (m < 0.5) m += 12.0; while (m >= 12.5) m -= 12.0
        return monthsHu[((m + 0.5).toInt() - 1).coerceIn(0, 11)]
    }
    private val monthsHu = listOf("január", "február", "március", "április", "május", "június", "július", "augusztus", "szeptember", "október", "november", "december")

    fun typeLabel(t: String): String = when (t) {
        "G" -> "galaxis"; "GPair" -> "galaxispár"; "GTrpl" -> "galaxishármas"; "GGroup" -> "galaxiscsoport"
        "OCl" -> "nyílthalmaz"; "GCl" -> "gömbhalmaz"; "Cl+N" -> "halmaz köddel"; "*Ass" -> "csillagtársulás"
        "PN" -> "planetáris köd"; "Neb" -> "köd"; "HII" -> "emissziós köd (HII)"; "EmN" -> "emissziós köd"; "RfN" -> "reflexiós köd"
        "SNR" -> "szupernóva-maradvány"; "DrkN" -> "sötét köd"; "**" -> "kettőscsillag"; "*" -> "csillag"; "Dup" -> "duplikált bejegyzés"
        else -> t
    }

    /** Az objektum típusa → AutoEditor objektumtípus. */
    fun autoType(t: String): hu.novaprocess.process.AutoEditor.ObjectType = when (t) {
        "G", "GPair", "GTrpl", "GGroup" -> hu.novaprocess.process.AutoEditor.ObjectType.GALAXY
        "OCl", "*Ass", "**", "*" -> hu.novaprocess.process.AutoEditor.ObjectType.OPEN_CLUSTER
        "GCl" -> hu.novaprocess.process.AutoEditor.ObjectType.GLOBULAR
        "PN" -> hu.novaprocess.process.AutoEditor.ObjectType.PLANETARY_NEBULA
        "RfN" -> hu.novaprocess.process.AutoEditor.ObjectType.REFLECTION_NEBULA
        "HII", "EmN", "Neb", "SNR", "Cl+N", "DrkN" -> hu.novaprocess.process.AutoEditor.ObjectType.EMISSION_NEBULA
        else -> hu.novaprocess.process.AutoEditor.ObjectType.UNKNOWN
    }

    fun formatDistance(ly: Double): String = when {
        ly >= 1e9 -> "%.1f milliárd fényév".format(ly / 1e9)
        ly >= 1e6 -> "%.1f millió fényév".format(ly / 1e6)
        ly >= 1e4 -> "%.0f ezer fényév".format(ly / 1e3)
        else -> "%.0f fényév".format(ly)
    }

    fun formatSize(o: CatObject): String = if (o.majArcmin <= 0f) "—" else if (o.minArcmin > 0f && abs(o.minArcmin - o.majArcmin) > 0.1f) "%s′ × %s′".format(num(o.majArcmin), num(o.minArcmin)) else "%s′".format(num(o.majArcmin))
    private fun num(v: Float) = if (v >= 10f) "%.0f".format(v) else "%.1f".format(v)

    fun formatRa(ra: Double): String { val h = ra / 15; val hh = h.toInt(); val mm = ((h - hh) * 60).toInt(); val ss = (((h - hh) * 60 - mm) * 60); return "%02dh %02dm %02.0fs".format(hh, mm, ss) }
    fun formatDec(dec: Double): String { val s = if (dec < 0) "−" else "+"; val a = abs(dec); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val sec = ((a - d) * 60 - m) * 60; return "%s%02d° %02d′ %02.0f″".format(s, d, m, sec) }

    // -------------------------------------------------------------------------------------------- táblák
    val constellations = mapOf(
        "And" to "Androméda", "Ant" to "Légszivattyú", "Aps" to "Paradicsommadár", "Aqr" to "Vízöntő", "Aql" to "Sas", "Ara" to "Oltár", "Ari" to "Kos", "Aur" to "Szekeres",
        "Boo" to "Ökörhajcsár", "Cae" to "Véső", "Cam" to "Zsiráf", "Cnc" to "Rák", "CVn" to "Vadászebek", "CMa" to "Nagy Kutya", "CMi" to "Kis Kutya", "Cap" to "Bak",
        "Car" to "Hajógerinc", "Cas" to "Kassziopeia", "Cen" to "Kentaur", "Cep" to "Cefeusz", "Cet" to "Cet", "Cha" to "Kaméleon", "Cir" to "Körző", "Col" to "Galamb",
        "Com" to "Bereniké Haja", "CrA" to "Déli Korona", "CrB" to "Északi Korona", "Crv" to "Holló", "Crt" to "Serleg", "Cru" to "Dél Keresztje", "Cyg" to "Hattyú", "Del" to "Delfin",
        "Dor" to "Aranyhal", "Dra" to "Sárkány", "Equ" to "Csikó", "Eri" to "Eridánusz", "For" to "Kemence", "Gem" to "Ikrek", "Gru" to "Daru", "Her" to "Herkules",
        "Hor" to "Ingaóra", "Hya" to "Északi Vízikígyó", "Hyi" to "Déli Vízikígyó", "Ind" to "Indián", "Lac" to "Gyík", "Leo" to "Oroszlán", "LMi" to "Kis Oroszlán", "Lep" to "Nyúl",
        "Lib" to "Mérleg", "Lup" to "Farkas", "Lyn" to "Hiúz", "Lyr" to "Lant", "Men" to "Táblahegy", "Mic" to "Mikroszkóp", "Mon" to "Egyszarvú", "Mus" to "Légy",
        "Nor" to "Szögmérő", "Oct" to "Oktáns", "Oph" to "Kígyótartó", "Ori" to "Orion", "Pav" to "Páva", "Peg" to "Pegazus", "Per" to "Perzeusz", "Phe" to "Főnix",
        "Pic" to "Festő", "Psc" to "Halak", "PsA" to "Déli Hal", "Pup" to "Hajófara", "Pyx" to "Tájoló", "Ret" to "Háló", "Sge" to "Nyíl", "Sgr" to "Nyilas",
        "Sco" to "Skorpió", "Scl" to "Szobrász", "Sct" to "Pajzs", "Ser" to "Kígyó", "Sex" to "Szextáns", "Tau" to "Bika", "Tel" to "Távcső", "Tri" to "Háromszög",
        "TrA" to "Déli Háromszög", "Tuc" to "Tukán", "UMa" to "Nagy Medve", "UMi" to "Kis Medve", "Vel" to "Vitorla", "Vir" to "Szűz", "Vol" to "Repülőhal", "Vul" to "Kis Róka"
    )

    val huNames = mapOf(
        "M 1" to "Rák-köd", "M 8" to "Lagúna-köd", "M 13" to "Herkules-gömbhalmaz", "M 16" to "Sas-köd", "M 17" to "Omega-köd", "M 20" to "Trifid-köd", "M 27" to "Súlyzó-köd",
        "M 31" to "Androméda-galaxis", "M 32" to "Androméda kísérője", "M 33" to "Triangulum-galaxis", "M 42" to "Orion-köd", "M 43" to "De Mairan-köd", "M 44" to "Jászol (Méhkas)",
        "M 45" to "Fiastyúk (Plejádok)", "M 51" to "Örvény-galaxis", "M 57" to "Gyűrűs-köd", "M 63" to "Napraforgó-galaxis", "M 64" to "Feketeszem-galaxis", "M 65" to "Leo-hármas tagja",
        "M 66" to "Leo-hármas tagja", "M 74" to "Fantom-galaxis", "M 76" to "Kis Súlyzó-köd", "M 78" to "Orion reflexiós köde", "M 81" to "Bode-galaxis", "M 82" to "Szivar-galaxis",
        "M 83" to "Déli Szélkerék-galaxis", "M 87" to "Virgo A", "M 97" to "Bagoly-köd", "M 101" to "Szélkerék-galaxis", "M 104" to "Sombrero-galaxis", "M 106" to "M106 galaxis",
        "M 110" to "Androméda kísérője", "M 3" to "M3 gömbhalmaz", "M 5" to "M5 gömbhalmaz", "M 15" to "Pegazus-gömbhalmaz", "M 92" to "M92 gömbhalmaz", "M 35" to "M35 nyílthalmaz",
        "M 37" to "M37 nyílthalmaz", "M 52" to "M52 nyílthalmaz", "M 103" to "M103 nyílthalmaz", "M 11" to "Vadkacsa-halmaz", "M 24" to "Nyilas-csillagfelhő", "M 7" to "Ptolemaiosz-halmaz", "M 6" to "Pillangó-halmaz",
        "C 14" to "Ikerhalmaz (h és χ Persei)", "C 20" to "Észak-Amerika-köd", "C 19" to "Selyemgubó-köd", "C 27" to "Félhold-köd", "C 33" to "Fátyol-köd (keleti)", "C 34" to "Fátyol-köd (nyugati)",
        "C 49" to "Rozetta-köd", "C 50" to "Rozetta-halmaz", "C 31" to "Lángoló Csillag-köd", "C 41" to "Hyadok", "C 9" to "Barlang-köd", "C 11" to "Buborék-köd", "C 13" to "Bagoly-halmaz (ET)",
        "C 23" to "Ezüsttű-galaxis", "C 30" to "NGC 7331 galaxis", "C 38" to "Tű-galaxis", "C 63" to "Hélix-köd", "C 39" to "Eszkimó-köd", "C 55" to "Szaturnusz-köd", "C 6" to "Macskaszem-köd",
        "C 4" to "Írisz-köd", "C 65" to "Sculptor-galaxis", "C 12" to "Tűzijáték-galaxis", "C 7" to "NGC 2403 galaxis", "C 28" to "NGC 752 halmaz", "C 64" to "Tau Canis Majoris-halmaz", "C 46" to "Hubble Változó Köde",
        "C 59" to "Szellem-köd (Jupiter szelleme)", "C 69" to "Poloska-köd", "C 77" to "Centaurus A", "C 80" to "Omega Centauri", "C 106" to "47 Tucanae", "C 92" to "Eta Carinae-köd",
        "IC1396" to "Elefántormány-köd", "IC1805" to "Szív-köd", "IC1848" to "Lélek-köd", "IC0405" to "Lángoló Csillag-köd", "IC0410" to "Ebihal-köd", "IC0434" to "Lófej-köd (környezete)",
        "IC5070" to "Pelikán-köd", "IC1318" to "Pillangó-köd (Sadr)", "IC2177" to "Sirály-köd", "IC5146" to "Selyemgubó-köd", "B033" to "Lófej-köd", "IC0443" to "Medúza-köd", "IC0063" to "Szellem-köd (Kassziopeia)",
        "NGC7000" to "Észak-Amerika-köd", "NGC6960" to "Fátyol-köd (nyugati, Boszorkányseprű)", "NGC6992" to "Fátyol-köd (keleti)", "NGC6995" to "Fátyol-köd (keleti, Denevér)", "NGC2237" to "Rozetta-köd", "NGC2244" to "Rozetta-halmaz",
        "NGC0281" to "Pacman-köd", "NGC7380" to "Varázsló-köd", "NGC6888" to "Félhold-köd", "NGC1499" to "Kalifornia-köd", "NGC7635" to "Buborék-köd", "NGC2264" to "Karácsonyfa-halmaz / Kúp-köd",
        "NGC7822" to "NGC 7822 köd", "NGC0896" to "Szív-köd (Hal-fej)", "NGC2174" to "Majomfej-köd", "NGC3372" to "Eta Carinae-köd", "NGC0253" to "Sculptor-galaxis", "NGC0891" to "Ezüsttű-galaxis",
        "NGC4565" to "Tű-galaxis", "NGC6946" to "Tűzijáték-galaxis", "NGC2903" to "NGC 2903 galaxis", "NGC3628" to "Hamburger-galaxis", "NGC5128" to "Centaurus A", "NGC4631" to "Bálna-galaxis",
        "NGC4656" to "Hokiütő-galaxis", "NGC5139" to "Omega Centauri", "NGC0869" to "Ikerhalmaz (h Persei)", "NGC0884" to "Ikerhalmaz (χ Persei)", "NGC0457" to "Bagoly-halmaz (ET)",
        "NGC6826" to "Pislogó-köd", "NGC7293" to "Hélix-köd", "NGC2392" to "Eszkimó-köd", "NGC6543" to "Macskaszem-köd", "NGC7023" to "Írisz-köd", "NGC1977" to "Futó Ember-köd",
        "NGC2024" to "Láng-köd", "NGC6823" to "NGC 6823 köd", "NGC6820" to "NGC 6820 köd", "NGC6914" to "NGC 6914 reflexiós köd", "NGC1333" to "NGC 1333 reflexiós köd", "NGC6559" to "NGC 6559 köd",
        "NGC1931" to "Légy-köd", "NGC6357" to "Homár-köd", "NGC6334" to "Macskatalp-köd", "NGC2359" to "Thor sisakja", "NGC3576" to "Statue of Liberty-köd", "NGC0246" to "Koponya-köd", "NGC6572" to "Smaragd-köd",
        "NGC2070" to "Tarantula-köd", "NGC0604" to "NGC 604 (M33 HII-régió)", "NGC5907" to "Szálka-galaxis", "NGC4244" to "Ezüsttű (Silver Needle)", "NGC4490" to "Gubó-galaxis", "NGC4449" to "NGC 4449 galaxis",
        "NGC0040" to "Csokornyakkendő-köd", "NGC0147" to "Androméda kísérője", "NGC0185" to "Androméda kísérője", "NGC6210" to "Teknős-köd", "NGC6302" to "Poloska-köd", "NGC7009" to "Szaturnusz-köd",
        "NGC7008" to "Magzat-köd", "NGC7129" to "NGC 7129 reflexiós köd", "NGC7538" to "NGC 7538 köd", "NGC0206" to "NGC 206 (M31 csillagfelhő)", "NGC6939" to "NGC 6939 halmaz", "NGC6633" to "NGC 6633 halmaz"
    )

    /** Távolságok fényévben (kerekített irodalmi értékek). */
    val distances = mapOf<String, Double>(
        "M 1" to 6500.0, "M 2" to 37500.0, "M 3" to 33900.0, "M 4" to 7200.0, "M 5" to 24500.0, "M 6" to 1600.0, "M 7" to 980.0, "M 8" to 4100.0, "M 9" to 25800.0, "M 10" to 14300.0,
        "M 11" to 6200.0, "M 12" to 15700.0, "M 13" to 22200.0, "M 14" to 30300.0, "M 15" to 33600.0, "M 16" to 7000.0, "M 17" to 5500.0, "M 18" to 4900.0, "M 19" to 28700.0, "M 20" to 5200.0,
        "M 21" to 4250.0, "M 22" to 10600.0, "M 23" to 2150.0, "M 24" to 10000.0, "M 25" to 2000.0, "M 26" to 5000.0, "M 27" to 1360.0, "M 28" to 17900.0, "M 29" to 4000.0, "M 30" to 27100.0,
        "M 31" to 2.54e6, "M 32" to 2.49e6, "M 33" to 2.73e6, "M 34" to 1500.0, "M 35" to 2800.0, "M 36" to 4100.0, "M 37" to 4500.0, "M 38" to 4200.0, "M 39" to 825.0, "M 40" to 510.0,
        "M 41" to 2300.0, "M 42" to 1344.0, "M 43" to 1600.0, "M 44" to 577.0, "M 45" to 444.0, "M 46" to 5400.0, "M 47" to 1600.0, "M 48" to 1500.0, "M 49" to 56e6, "M 50" to 3200.0,
        "M 51" to 23e6, "M 52" to 4600.0, "M 53" to 58000.0, "M 54" to 87400.0, "M 55" to 17600.0, "M 56" to 32900.0, "M 57" to 2570.0, "M 58" to 62e6, "M 59" to 60e6, "M 60" to 55e6,
        "M 61" to 52e6, "M 62" to 22200.0, "M 63" to 29e6, "M 64" to 17e6, "M 65" to 35e6, "M 66" to 36e6, "M 67" to 2700.0, "M 68" to 33600.0, "M 69" to 29700.0, "M 70" to 29400.0,
        "M 71" to 13000.0, "M 72" to 55400.0, "M 73" to 2500.0, "M 74" to 32e6, "M 75" to 67500.0, "M 76" to 2500.0, "M 77" to 47e6, "M 78" to 1600.0, "M 79" to 42100.0, "M 80" to 32600.0,
        "M 81" to 11.8e6, "M 82" to 11.5e6, "M 83" to 15e6, "M 84" to 60e6, "M 85" to 60e6, "M 86" to 52e6, "M 87" to 53e6, "M 88" to 47e6, "M 89" to 50e6, "M 90" to 59e6,
        "M 91" to 63e6, "M 92" to 26700.0, "M 93" to 3600.0, "M 94" to 16e6, "M 95" to 33e6, "M 96" to 31e6, "M 97" to 2030.0, "M 98" to 44e6, "M 99" to 50e6, "M 100" to 55e6,
        "M 101" to 21e6, "M 102" to 50e6, "M 103" to 8500.0, "M 104" to 29e6, "M 105" to 32e6, "M 106" to 23.7e6, "M 107" to 20900.0, "M 108" to 46e6, "M 109" to 83e6, "M 110" to 2.69e6,
        "C 14" to 7500.0, "C 20" to 2590.0, "C 19" to 4000.0, "C 27" to 5000.0, "C 33" to 2400.0, "C 34" to 2400.0, "C 49" to 5200.0, "C 50" to 5200.0, "C 31" to 1500.0, "C 41" to 153.0,
        "C 9" to 2400.0, "C 11" to 7100.0, "C 13" to 7900.0, "C 23" to 27e6, "C 30" to 40e6, "C 38" to 40e6, "C 63" to 650.0, "C 39" to 6500.0, "C 55" to 5200.0, "C 6" to 3300.0,
        "C 4" to 1300.0, "C 65" to 11.4e6, "C 12" to 25e6, "C 7" to 10e6, "C 46" to 2500.0, "C 59" to 1400.0, "C 69" to 3400.0, "C 77" to 12e6, "C 80" to 17000.0, "C 106" to 13000.0, "C 92" to 8500.0,
        "IC1396" to 2400.0, "IC1805" to 7500.0, "IC1848" to 7500.0, "IC0405" to 1500.0, "IC0410" to 12000.0, "IC0434" to 1375.0, "B033" to 1375.0, "IC5070" to 1800.0, "IC1318" to 4900.0, "IC2177" to 3650.0, "IC0443" to 5000.0,
        "NGC0281" to 9500.0, "NGC7380" to 7200.0, "NGC1499" to 1000.0, "NGC2264" to 2500.0, "NGC7822" to 3000.0, "NGC0896" to 7500.0, "NGC2174" to 6400.0, "NGC0253" to 11.4e6, "NGC0891" to 27e6,
        "NGC4565" to 40e6, "NGC6946" to 25e6, "NGC2903" to 30e6, "NGC3628" to 35e6, "NGC4631" to 30e6, "NGC4656" to 25e6, "NGC6826" to 2000.0, "NGC7023" to 1300.0, "NGC1977" to 1500.0,
        "NGC2024" to 1350.0, "NGC2359" to 12000.0, "NGC5907" to 50e6, "NGC4244" to 14e6, "NGC4490" to 25e6, "NGC4449" to 12e6, "NGC0040" to 3500.0, "NGC6210" to 6500.0, "NGC2070" to 160000.0
    )
}

package hu.novaprocess.process

enum class StretchMode { NONE, AUTO, ASINH, MTF }
enum class Rotation { R0, R90, R180, R270 }
enum class Palette { NATURAL, HOO, SHO_LIKE, FORAXX }
enum class MaskMode { NONE, HIGHLIGHTS, SHADOWS, RANGE }

/** Minden csúszka értéke. Az alapértékek "semleges" feldolgozást adnak (csak autostretch). */
data class ProcessParams(
    // --- Geometria ---
    val rotation: Rotation = Rotation.R0,
    val flipH: Boolean = false,
    val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 1f, val cropBottom: Float = 1f,

    // --- Asztro (lineáris térben, Siril-szerű) ---
    val backgroundExtraction: Boolean = false,
    val backgroundDegree: Int = 2,            // 1 = sík, 2 = másodfokú, 3 = RBF mintapontokból (GraXpert-szerű)
    val bgSamples: List<Float> = emptyList(), // kézi mintapontok: x0,y0,x1,y1,… (0..1 normalizált), üres = automatikus rács
    val bgSmooth: Float = 0.5f,               // RBF simaság 0..1
    val backgroundNeutralize: Boolean = false,
    val neutralizeAfterStretch: Boolean = false, // háttér-semlegesítés a stretch után is (színcast ellen)
    val whiteBalanceStars: Boolean = false,
    val stretchMode: StretchMode = StretchMode.AUTO,
    val asinhStrength: Float = 100f,          // 1..1000
    val blackPoint: Float = 0f,               // 0..0.2 (lineáris)
    val mtfMidtones: Float = 0.25f,           // AUTO/MTF cél háttér
    val mtfShadowClip: Float = 2.8f,          // MAD-szorzó
    val mtfMid: Float = 0f,                   // asinh UTÁNI MTF középtónus (Siril `mtf 0 m 1`; 0 = ki; kisebb = világosabb)
    val ghsEnabled: Boolean = false,          // Generalized Hyperbolic Stretch (galaxis-korong/porsáv kiemelés)
    val ghsD: Float = 1.5f, val ghsB: Float = 5f, val ghsSP: Float = 0.15f, val ghsLP: Float = 0.06f, val ghsHP: Float = 0.65f,
    val starReduction: Float = 0f,            // 0..1
    val starRadius: Int = 2,                  // 1..5 px
    val denoise: Float = 0f,                  // 0..1
    val denoiseRadius: Int = 2,
    val linearDenoise: Float = 0f,            // zajszűrés a LINEÁRIS képen, stretch előtt (adaptív Wiener / Lee) 0..1
    val chromaDenoise: Float = 0f,            // színzaj-szűrés 0..1 (csak a színinformációt simítja)
    val chromaRadius: Int = 4,

    // --- 1.2: haladó asztro ---
    val scnr: Float = 0f,                     // zöld-elnyomás 0..1
    val deconvIterations: Int = 0,            // Richardson–Lucy iterációk 0..30
    val deconvSigma: Float = 1.2f,            // PSF szigma px 0.5..3
    val hdrCompress: Float = 0f,              // magkompresszió 0..1
    val hdrRadius: Int = 20,                  // helyi tónusleképezés sugara
    val starless: Boolean = false,            // csillagtalanítás külön rétegre
    val starlessRadius: Int = 4,              // csillag-eltávolítás sugara
    val starsBlend: Float = 1f,               // csillagok visszakeverése 0..1
    val nebulaBoost: Float = 0f,              // extra stretch a csillagtalan rétegen 0..1
    val palette: Palette = Palette.NATURAL,
    val paletteStrength: Float = 1f,          // 0..1
    val curveShadows: Float = 0f,             // görbe pontok eltolása -1..1
    val curveMidtones: Float = 0f,
    val curveHighlights: Float = 0f,
    val curveLuminanceOnly: Boolean = true,
    val maskMode: MaskMode = MaskMode.NONE,   // a fotós beállítások maszkolása
    val maskLow: Float = 0.2f, val maskHigh: Float = 0.8f, val maskFeather: Float = 0.1f,
    val maskInvert: Boolean = false,

    // --- 1.4: wavelet élesítés (bolygó/Hold, de DSO-ra is) ---
    val wavelet1: Float = 0f,                 // finom (1 px) réteg erősítés 0..3
    val wavelet2: Float = 0f,                 // 2 px
    val wavelet3: Float = 0f,                 // 4 px
    val waveletDenoise: Float = 0f,           // finom réteg zajküszöb 0..1
    val clahe: Float = 0f,                    // helyi kontraszt (CLAHE) erőssége 0..1
    val claheTiles: Int = 8,                  // csempék száma a rövidebb oldalon
    val claheClip: Float = 2f,                // vágási határ (1 = nincs kiemelés, 4 = erős)

    // --- Fotó (nemlineáris térben, Lightroom-szerű) ---
    val exposure: Float = 0f,                 // EV, -3..3
    val contrast: Float = 0f,                 // -1..1
    val highlights: Float = 0f,               // -1..1
    val shadows: Float = 0f,                  // -1..1
    val whites: Float = 0f,                   // -1..1
    val blacks: Float = 0f,                   // -1..1
    val temperature: Float = 0f,              // -1..1 (hideg..meleg)
    val tint: Float = 0f,                     // -1..1 (zöld..magenta)
    val saturation: Float = 0f,               // -1..1
    val vibrance: Float = 0f,                 // -1..1
    val clarity: Float = 0f,                  // -1..1
    val dehaze: Float = 0f,                   // 0..1
    val sharpen: Float = 0f,                  // 0..1
    val gamma: Float = 1f,                    // 0.5..2
    val levelsBlack: Float = 0f,              // 0..0.5
    val levelsWhite: Float = 1f,              // 0.5..1
    val redBalance: Float = 0f, val greenBalance: Float = 0f, val blueBalance: Float = 0f // -0.5..0.5
)

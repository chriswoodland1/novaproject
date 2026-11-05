package hu.novaprocess.process

enum class StretchMode { NONE, AUTO, ASINH, MTF }
enum class Rotation { R0, R90, R180, R270 }

/** Minden csúszka értéke. Az alapértékek "semleges" feldolgozást adnak (csak autostretch). */
data class ProcessParams(
    // --- Geometria ---
    val rotation: Rotation = Rotation.R0,
    val flipH: Boolean = false,
    val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 1f, val cropBottom: Float = 1f,

    // --- Asztro (lineáris térben, Siril-szerű) ---
    val backgroundExtraction: Boolean = false,
    val backgroundDegree: Int = 2,            // 1 = sík, 2 = másodfokú
    val backgroundNeutralize: Boolean = false,
    val whiteBalanceStars: Boolean = false,
    val stretchMode: StretchMode = StretchMode.AUTO,
    val asinhStrength: Float = 100f,          // 1..1000
    val blackPoint: Float = 0f,               // 0..0.2 (lineáris)
    val mtfMidtones: Float = 0.25f,           // AUTO/MTF cél háttér
    val mtfShadowClip: Float = 2.8f,          // MAD-szorzó
    val starReduction: Float = 0f,            // 0..1
    val starRadius: Int = 2,                  // 1..5 px
    val denoise: Float = 0f,                  // 0..1
    val denoiseRadius: Int = 2,

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

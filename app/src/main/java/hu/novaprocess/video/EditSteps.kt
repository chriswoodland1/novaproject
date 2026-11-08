package hu.novaprocess.video

import hu.novaprocess.image.FloatImage
import hu.novaprocess.process.Palette
import hu.novaprocess.process.MaskMode
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import hu.novaprocess.process.StretchMode

/**
 * „Így készült a szerkesztés” – egy KÉSZ stackből (pl. a Seestar saját Stacked FITS-éből) is: a végleges receptből
 * visszafelé kikapcsolt lépéscsoportokkal készülnek az állomások (nyers → nyújtás → háttér → színek → zajszűrés → kész).
 * Az azonos eredményt adó (a receptben nem használt) lépések kimaradnak.
 */
object EditSteps {
    fun scenes(src: FloatImage, p: ProcessParams): List<Scene> {
        val s6 = p
        val s5 = s6.copy(deconvIterations = 0, wavelet1 = 0f, wavelet2 = 0f, wavelet3 = 0f, waveletDenoise = 0f, clahe = 0f,
            curveShadows = 0f, curveMidtones = 0f, curveHighlights = 0f, exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f,
            whites = 0f, blacks = 0f, clarity = 0f, dehaze = 0f, sharpen = 0f, gamma = 1f, levelsBlack = 0f, levelsWhite = 1f,
            hdrCompress = 0f, starless = false, starReduction = 0f, nebulaBoost = 0f, maskMode = MaskMode.NONE)
        val s4 = s5.copy(linearDenoise = 0f, denoise = 0f, chromaDenoise = 0f)
        val s3 = s4.copy(whiteBalanceStars = false, scnr = 0f, palette = Palette.NATURAL, saturation = 0f, vibrance = 0f,
            temperature = 0f, tint = 0f, redBalance = 0f, greenBalance = 0f, blueBalance = 0f)
        val s2 = s3.copy(backgroundExtraction = false, backgroundNeutralize = false, neutralizeAfterStretch = false)
        val s1 = s2.copy(stretchMode = StretchMode.NONE, mtfMid = 0f, ghsEnabled = false)
        val stretchName = when (p.stretchMode) { StretchMode.ASINH -> "asinh" + (if (p.mtfMid > 0f) " + MTF" else ""); StretchMode.AUTO -> "autostretch"; StretchMode.MTF -> "MTF"; StretchMode.NONE -> "nincs" } + (if (p.ghsEnabled) " + GHS" else "")
        val steps = listOf(
            Triple(s1, "Nyers adat", "lineáris – a jel szinte mind a sötétben van"),
            Triple(s2, "Nyújtás", "stretch: $stretchName"),
            Triple(s3, "Háttér", "égbolt-glow kivonás, semleges háttér"),
            Triple(s4, "Színek", "színkorrekció, telítettség"),
            Triple(s5, "Zajszűrés", "lineáris + utószűrés, színzaj"),
            Triple(s6, "Kész kép", "részletek, csillagok, fények")
        )
        val out = ArrayList<Scene>(); var prev: ProcessParams? = null; var n = 0
        for ((pp, title, sub) in steps) {
            if (pp == prev) continue
            n++
            out += Scene(Processor.process(src.copy(), pp).toBitmap(), "$n. $title", sub)
            prev = pp
        }
        return out
    }
}

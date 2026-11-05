package hu.novaprocess.process

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File

data class Preset(val name: String, val params: ProcessParams, val builtIn: Boolean = false)

/** Presetek és receptek (a ProcessParams JSON-ban). */
object Presets {
    val gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(p: ProcessParams): String = gson.toJson(RecipeFile(params = p))
    fun fromJson(json: String): ProcessParams {
        // elfogadjuk a csupasz ProcessParams-t és a borítékolt receptet is
        return try { gson.fromJson(json, RecipeFile::class.java)?.params ?: gson.fromJson(json, ProcessParams::class.java) }
        catch (e: Exception) { gson.fromJson(json, ProcessParams::class.java) }
    }

    data class RecipeFile(val app: String = "NovaProcess", val version: Int = 1, val params: ProcessParams)

    val builtIns: List<Preset> = listOf(
        Preset("Galaxis", ProcessParams(backgroundExtraction = true, backgroundNeutralize = true, whiteBalanceStars = true,
            stretchMode = StretchMode.AUTO, mtfMidtones = 0.22f, hdrCompress = 0.35f, hdrRadius = 24, starReduction = 0.3f, denoise = 0.25f, clarity = 0.15f, saturation = 0.2f), true),
        Preset("Emissziós köd", ProcessParams(backgroundExtraction = true, backgroundNeutralize = true, scnr = 0.6f,
            stretchMode = StretchMode.ASINH, asinhStrength = 250f, starless = true, starlessRadius = 4, starsBlend = 0.6f, nebulaBoost = 0.3f, denoise = 0.3f, vibrance = 0.3f), true),
        Preset("Dual-band HOO", ProcessParams(backgroundExtraction = true, palette = Palette.HOO, paletteStrength = 1f,
            stretchMode = StretchMode.ASINH, asinhStrength = 300f, starless = true, starsBlend = 0.5f, nebulaBoost = 0.35f, denoise = 0.35f, saturation = 0.25f), true),
        Preset("Nyílthalmaz", ProcessParams(backgroundExtraction = true, backgroundNeutralize = true, whiteBalanceStars = true,
            stretchMode = StretchMode.AUTO, mtfMidtones = 0.18f, mtfShadowClip = 3f, denoise = 0.15f, saturation = 0.35f, sharpen = 0.2f), true),
        Preset("Gömbhalmaz", ProcessParams(backgroundExtraction = true, whiteBalanceStars = true, stretchMode = StretchMode.ASINH, asinhStrength = 120f,
            hdrCompress = 0.5f, hdrRadius = 12, deconvIterations = 8, deconvSigma = 1.1f, sharpen = 0.3f), true),
        Preset("Hold / bolygó", ProcessParams(stretchMode = StretchMode.NONE, blackPoint = 0.02f, deconvIterations = 15, deconvSigma = 1.0f,
            sharpen = 0.6f, clarity = 0.3f, contrast = 0.15f), true),
        Preset("Csak autostretch", ProcessParams(), true)
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "presets").apply { mkdirs() }
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9áéíóöőúüűÁÉÍÓÖŐÚÜŰ _-]"), "_").take(60)

    fun userPresets(ctx: Context): List<Preset> =
        dir(ctx).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.mapNotNull { f ->
            runCatching { Preset(f.nameWithoutExtension, fromJson(f.readText())) }.getOrNull()
        } ?: emptyList()

    fun save(ctx: Context, name: String, p: ProcessParams) { File(dir(ctx), safe(name) + ".json").writeText(toJson(p)) }
    fun delete(ctx: Context, name: String) { File(dir(ctx), safe(name) + ".json").delete() }
}

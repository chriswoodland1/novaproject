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
    val dateObs: String? = null
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
        val small = ImageLoader.loadWithMeta(context, uri, name).let { (full, m) ->
            meta = m; scaleDown = maxOf(full.width, full.height).toFloat() / analysisSide
            full.downscale(analysisSide)   // a teljes kép itt elengedhető
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

        val thumb = try {
            val t = small.downscale(thumbSide)
            Processor.process(t, ProcessParams()).toBitmap()
        } catch (e: Exception) { null }

        return FrameInfo(uri, name, fwhm, stars.size, bg, ecc, thumb, objectName = meta?.objectName, exposure = meta?.exposure, dateObs = meta?.dateObs)
    }

    /** Automatikus kizárás a kockák mediánjához képest. */
    fun autoReject(frames: List<FrameInfo>, fwhmFactor: Float = 1.5f, minStarsFactor: Float = 0.4f, maxEcc: Float = 0.75f): List<FrameInfo> {
        val valid = frames.filter { it.stars > 0 }
        if (valid.size < 3) return frames
        val medFwhm = valid.map { it.fwhm }.sorted()[valid.size / 2]
        val medStars = valid.map { it.stars }.sorted()[valid.size / 2]
        return frames.map { f ->
            val reason = when {
                f.stars == 0 -> "nincs csillag"
                f.fwhm > medFwhm * fwhmFactor -> "életlen (FWHM ${"%.1f".format(f.fwhm)})"
                f.stars < medStars * minStarsFactor -> "kevés csillag / felhő"
                f.eccentricity > maxEcc -> "elhúzott csillagok"
                else -> ""
            }
            f.copy(rejected = reason.isNotEmpty(), reason = reason)
        }
    }
}

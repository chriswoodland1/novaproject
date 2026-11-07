package hu.novaprocess.stack

import android.content.Context
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.process.Processor
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Üstökös mód – kettős regisztráció:
 *  1. csillagokra igazított stack (a megszokott sigma-clip) → csillagok élesek, az üstökös elkenődik;
 *  2. az üstökösmagra igazított stack: a csillag-transzformációt időarányosan eltoljuk úgy, hogy a mag
 *     minden kockán ugyanoda essen (a mag helye az első és az utolsó kockán kézzel bejelölve, közte
 *     a DATE-OBS időbélyegekből lineárisan interpolálva); erős sigma-clip (1.5σ) elnyomja a csillagcsíkokat;
 *  3. keverés: puha maszk az üstökös körül (sugár kézzel vagy automatikusan a jel kiterjedéséből),
 *     a maszkon belül a mag-stack, kívül a csillag-stack.
 */
object CometStacker {

    data class Options(
        val first: Pair<Float, Float>,       // mag normalizált helye az első kockán (0..1)
        val last: Pair<Float, Float>,        // mag normalizált helye az utolsó kockán
        val radiusFrac: Float = 0f,          // maszk sugara a rövidebb oldal arányában; 0 = automatikus
        val feather: Float = 0.35f           // maszk puhasága (a sugár arányában)
    )

    data class Result(val image: FloatImage, val stars: FloatImage, val comet: FloatImage, val used: Int, val log: List<String>,
                      val motionPxPerMin: Float, val motionAngleDeg: Float, val durationMin: Float)

    suspend fun stack(ctx: Context, frames: List<Uri>, times: List<Long?>, opts: StackOptions, co: Options, onProgress: (StackProgress) -> Unit): Result {
        require(frames.size >= 3) { "Üstökös módhoz legalább 3 kocka kell" }
        val log = ArrayList<String>()
        // időarányok: hiányzó időbélyegnél egyenletes
        val t0 = times.firstOrNull() ?: 0L; val t1 = times.lastOrNull() ?: 0L
        val haveTimes = times.all { it != null } && t1 > t0
        val tau = FloatArray(frames.size) { i -> if (haveTimes) ((times[i]!! - t0).toFloat() / (t1 - t0)) else i.toFloat() / (frames.size - 1) }
        log += if (haveTimes) "Időbélyegek a FITS fejlécből: ${"%.1f".format((t1 - t0) / 60000f)} perc" else "Nincs DATE-OBS → egyenletes időköz feltételezve"

        // 1. csillag-stack
        val stars = Stacker.stack(ctx, frames, opts.copy(drizzle = 1, mosaic = false), { p -> onProgress(p.copy(phase = "Csillagok · " + p.phase)) })
        log += stars.log
        val w = stars.image.width; val h = stars.image.height; val cx = w / 2f; val cy = h / 2f
        val tfs = stars.transforms
        val q1 = Pair(co.first.first * w, co.first.second * h)
        val lastIdx = tfs.indices.lastOrNull { tfs[it] != null } ?: (frames.size - 1)
        val pLast = Pair(co.last.first * w, co.last.second * h)
        val q2 = tfs[lastIdx]?.apply(pLast.first, pLast.second, cx, cy) ?: pLast
        val mx = q2.first - q1.first; val my = q2.second - q1.second
        val dur = if (haveTimes) (t1 - t0) / 60000f else 0f
        val speed = if (dur > 0f) hypot(mx, my) / dur else 0f
        val angle = Math.toDegrees(kotlin.math.atan2(-my.toDouble(), mx.toDouble())).toFloat()
        log += "Mag elmozdulása: ${"%.0f".format(hypot(mx, my))} px" + (if (dur > 0f) " · ${"%.1f".format(speed)} px/perc" else "")

        // 2. mag-stack: eltolás az időarány szerint úgy, hogy a mag q1-be kerüljön
        val tauLast = tau[lastIdx].coerceAtLeast(1e-3f)
        val comet = Stacker.stack(ctx, frames, opts.copy(method = StackMethod.SIGMA_CLIP, sigmaLow = 1.5f, sigmaHigh = 1.5f, drizzle = 1, mosaic = false),
            { p -> onProgress(p.copy(phase = "Üstökös · " + p.phase)) }, fixedTransforms = tfs) { i, tf ->
            val k = tau[i] / tauLast
            tf.copy(dx = tf.dx - mx * k, dy = tf.dy - my * k)
        }
        log += "Mag-stack: ${comet.used} kocka, 1.5σ vágás (csillagcsíkok elnyomva)"

        // 3. maszk + keverés
        val lum = comet.image.toMono().data[0]
        val bg = Processor.median(lum); val nz = (Processor.mad(lum, bg) * 1.4826f).coerceAtLeast(1e-6f)
        val shortSide = minOf(w, h).toFloat()
        val radius = if (co.radiusFrac > 0f) co.radiusFrac * shortSide else autoRadius(lum, w, h, q1, bg + 3f * nz, shortSide)
        log += "Üstökös-maszk sugara: ${"%.0f".format(radius)} px"
        val out = FloatImage(w, h, stars.image.channels)
        val feather = (radius * co.feather).coerceAtLeast(2f)
        for (y in 0 until h) for (x in 0 until w) {
            val d = hypot(x - q1.first, y - q1.second)
            val m = if (d <= radius) 1f else exp(-((d - radius) / feather) * ((d - radius) / feather) * 0.5f)
            val i = y * w + x
            for (c in 0 until out.channels) {
                val s = stars.image.data[c][i]; val k = comet.image.data[c][i]
                // a maszkban a mag-stack, de a csillagok fényesebb pixeleit a csillag-stackből megtartjuk (ne vesszenek el)
                out.data[c][i] = s * (1f - m) + m * max(k, if (s > k + 4f * nz) s else k)
            }
        }
        return Result(out, stars.image, comet.image, stars.used, log, speed, angle, dur)
    }

    /** Sugár: a mag körül a 3σ fölötti jel kiterjedése (sugárirányú profil), 4–30 % a rövidebb oldalból. */
    private fun autoRadius(lum: FloatArray, w: Int, h: Int, q: Pair<Float, Float>, thr: Float, shortSide: Float): Float {
        val maxR = (shortSide * 0.3f).toInt()
        var r = 4
        while (r < maxR) {
            var above = 0; var n = 0
            val steps = (2 * Math.PI * r).toInt().coerceAtLeast(16)
            for (k in 0 until steps) {
                val a = 2 * Math.PI * k / steps
                val x = (q.first + r * Math.cos(a)).toInt(); val y = (q.second + r * Math.sin(a)).toInt()
                if (x !in 0 until w || y !in 0 until h) continue
                n++; if (lum[y * w + x] > thr) above++
            }
            if (n == 0 || above < n * 0.15f) break
            r += 2
        }
        return (r * 1.4f).coerceIn(shortSide * 0.04f, shortSide * 0.3f)
    }
}

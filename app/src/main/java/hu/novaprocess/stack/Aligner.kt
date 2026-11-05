package hu.novaprocess.stack

import hu.novaprocess.image.FloatImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Forgatás + eltolás (hasonlósági transzformáció skála nélkül): target -> referencia. */
data class Transform(val angle: Float, val dx: Float, val dy: Float, val matches: Int) {
    fun apply(x: Float, y: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        val rx = x - cx; val ry = y - cy
        return Pair(c * rx - s * ry + cx + dx, s * rx + c * ry + cy + dy)
    }
    companion object { val IDENTITY = Transform(0f, 0f, 0f, 0) }
}

/**
 * Csillagpár-szavazásos regisztráció: a referencia és a célkép fényes csillagaiból
 * olyan párokat keres, amelyek távolsága egyezik, ezekből forgatást+eltolást számol,
 * és a legtöbb egyetértő jelöltet választja. Alt-az (Seestar) mezőforgást is kezel.
 */
object Aligner {

    fun estimate(ref: List<Star>, tgt: List<Star>, w: Int, h: Int, tol: Float = 2.0f, wide: Boolean = false): Transform {
        if (ref.size < 3 || tgt.size < 3) return Transform.IDENTITY
        val cx = w / 2f; val cy = h / 2f
        val take = if (wide) 110 else 60   // mozaiknál kevés a közös csillag → több jelölt
        val r = ref.take(take); val t = tgt.take(take)

        data class Cand(val angle: Float, val dx: Float, val dy: Float)
        val cands = ArrayList<Cand>()
        for (i in r.indices) for (j in i + 1 until r.size) {
            val dRef = StarDetector.dist(r[i], r[j])
            if (dRef < 20f) continue
            val aRef = atan2(r[j].y - r[i].y, r[j].x - r[i].x)
            for (k in t.indices) for (l in t.indices) {
                if (k == l) continue
                val dT = StarDetector.dist(t[k], t[l])
                if (abs(dT - dRef) > tol) continue
                val aT = atan2(t[l].y - t[k].y, t[l].x - t[k].x)
                var ang = aRef - aT
                while (ang > Math.PI) ang -= (2 * Math.PI).toFloat()
                while (ang < -Math.PI) ang += (2 * Math.PI).toFloat()
                if (abs(ang) > 0.35f) continue // ±20° fölött nem reális két sub között
                val c = cos(ang); val s = sin(ang)
                val rx = t[k].x - cx; val ry = t[k].y - cy
                val px = c * rx - s * ry + cx; val py = s * rx + c * ry + cy
                cands.add(Cand(ang, r[i].x - px, r[i].y - py))
            }
        }
        if (cands.isEmpty()) return Transform.IDENTITY

        // durva szavazás: bin szerint csoportosítás
        val bins = HashMap<Long, MutableList<Cand>>()
        for (c in cands) {
            val ka = Math.round(c.angle / 0.004f).toLong()
            val kx = Math.round(c.dx / 3f).toLong()
            val ky = Math.round(c.dy / 3f).toLong()
            val key = (ka and 0xFFFFF) or ((kx and 0xFFFFF) shl 20) or ((ky and 0xFFFFF) shl 40)
            bins.getOrPut(key) { ArrayList() }.add(c)
        }
        val best = bins.values.maxByOrNull { it.size } ?: return Transform.IDENTITY
        if (best.size < 4) return Transform.IDENTITY
        val ang = best.map { it.angle }.average().toFloat()
        val dx = best.map { it.dx }.average().toFloat()
        val dy = best.map { it.dy }.average().toFloat()

        // finomítás: valódi csillag-megfeleltetésekből újraszámolt átlag
        val tf0 = Transform(ang, dx, dy, best.size)
        var sx = 0f; var sy = 0f; var n = 0
        for (s in t) {
            val (px, py) = tf0.apply(s.x, s.y, cx, cy)
            val m = r.minByOrNull { (it.x - px) * (it.x - px) + (it.y - py) * (it.y - py) } ?: continue
            val d = sqrt((m.x - px) * (m.x - px) + (m.y - py) * (m.y - py))
            if (d < tol * 1.5f) { sx += m.x - px; sy += m.y - py; n++ }
        }
        return if (n >= 3) Transform(ang, dx + sx / n, dy + sy / n, n) else tf0
    }

    /** A célképet a referencia koordinátarendszerébe képezi (bilineáris, inverz leképezés). */
    fun warp(src: FloatImage, tf: Transform): FloatImage {
        val w = src.width; val h = src.height
        val out = FloatImage(w, h, src.channels)
        val cx = w / 2f; val cy = h / 2f
        val c = cos(-tf.angle); val s = sin(-tf.angle)
        hu.novaprocess.image.Par.rows(h) { y0, y1 ->
          for (y in y0 until y1) {
            for (x in 0 until w) {
                // inverz: referencia (x,y) -> forrás
                val rx = x - cx - tf.dx; val ry = y - cy - tf.dy
                val fx = c * rx - s * ry + cx
                val fy = s * rx + c * ry + cy
                if (fx < 0f || fy < 0f || fx >= w - 1 || fy >= h - 1) continue
                val x0 = fx.toInt(); val y0 = fy.toInt()
                val ax = fx - x0; val ay = fy - y0
                val i00 = y0 * w + x0
                for (ch in 0 until src.channels) {
                    val a = src.data[ch]
                    val v = (a[i00] * (1 - ax) + a[i00 + 1] * ax) * (1 - ay) +
                            (a[i00 + w] * (1 - ax) + a[i00 + w + 1] * ax) * ay
                    out.data[ch][y * w + x] = v
                }
            }
          }
        }
        return out
    }

    /**
     * Drizzle-szerű 2× felskálázás: a célrácsot `scale`-szer finomabbra vesszük, és a forrást
     * a transzformáció inverzével, bilineárisan mintavételezzük. Sok, kissé eltolt kocka átlaga
     * így a szubpixeles információt részben visszanyeri.
     */
    fun warpScaled(src: FloatImage, tf: Transform, scale: Int): FloatImage {
        if (scale <= 1) return warp(src, tf)
        val w = src.width; val h = src.height
        val ow = w * scale; val oh = h * scale
        val out = FloatImage(ow, oh, src.channels)
        val cx = w / 2f; val cy = h / 2f
        val c = cos(-tf.angle); val s = sin(-tf.angle)
        val inv = 1f / scale
        hu.novaprocess.image.Par.rows(oh) { y0, y1 ->
          for (oy in y0 until y1) {
            val y = (oy + 0.5f) * inv - 0.5f
            for (ox in 0 until ow) {
                val x = (ox + 0.5f) * inv - 0.5f
                val rx = x - cx - tf.dx; val ry = y - cy - tf.dy
                val fx = c * rx - s * ry + cx
                val fy = s * rx + c * ry + cy
                if (fx < 0f || fy < 0f || fx >= w - 1 || fy >= h - 1) continue
                val x0 = fx.toInt(); val yy0 = fy.toInt()
                val ax = fx - x0; val ay = fy - yy0
                val i00 = yy0 * w + x0
                for (ch in 0 until src.channels) {
                    val a = src.data[ch]
                    out.data[ch][oy * ow + ox] = (a[i00] * (1 - ax) + a[i00 + 1] * ax) * (1 - ay) +
                            (a[i00 + w] * (1 - ax) + a[i00 + w + 1] * ax) * ay
                }
            }
          }
        }
        return out
    }
}

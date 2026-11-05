package hu.novaprocess.image

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lineáris, lebegőpontos kép 0..1 tartományban, 1 (mono) vagy 3 (RGB) csatornával.
 * Minden feldolgozó lépés ezen dolgozik; a Bitmap csak megjelenítéshez / exporthoz készül.
 */
class FloatImage(val width: Int, val height: Int, val channels: Int) {
    val data: Array<FloatArray> = Array(channels) { FloatArray(width * height) }
    val size: Int get() = width * height

    fun copy(): FloatImage {
        val out = FloatImage(width, height, channels)
        for (c in 0 until channels) System.arraycopy(data[c], 0, out.data[c], 0, size)
        return out
    }

    fun toMono(): FloatImage {
        if (channels == 1) return this
        val out = FloatImage(width, height, 1)
        val r = data[0]; val g = data[1]; val b = data[2]; val o = out.data[0]
        for (i in 0 until size) o[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        return out
    }

    fun toRgb(): FloatImage {
        if (channels == 3) return this
        val out = FloatImage(width, height, 3)
        for (c in 0 until 3) System.arraycopy(data[0], 0, out.data[c], 0, size)
        return out
    }

    /** Bilineáris lekicsinyítés előnézethez. */
    fun downscale(maxSide: Int): FloatImage {
        val scale = max(width, height).toFloat() / maxSide
        if (scale <= 1f) return this
        val nw = max(1, (width / scale).roundToInt())
        val nh = max(1, (height / scale).roundToInt())
        val out = FloatImage(nw, nh, channels)
        val box = scale.toInt().coerceAtLeast(1)
        for (c in 0 until channels) {
            val src = data[c]; val dst = out.data[c]
            for (y in 0 until nh) {
                val sy0 = (y * scale).toInt().coerceIn(0, height - 1)
                for (x in 0 until nw) {
                    val sx0 = (x * scale).toInt().coerceIn(0, width - 1)
                    var acc = 0f; var n = 0
                    var yy = sy0
                    while (yy < min(height, sy0 + box)) {
                        var xx = sx0
                        val row = yy * width
                        while (xx < min(width, sx0 + box)) { acc += src[row + xx]; n++; xx++ }
                        yy++
                    }
                    dst[y * nw + x] = if (n > 0) acc / n else 0f
                }
            }
        }
        return out
    }

    fun clampInPlace() {
        for (c in 0 until channels) {
            val a = data[c]
            for (i in 0 until size) { val v = a[i]; a[i] = if (v < 0f) 0f else if (v > 1f) 1f else v }
        }
    }

    /** 8 bites ARGB Bitmap megjelenítéshez (gamma nélkül, a stretch már megtörtént). */
    fun toBitmap(): Bitmap {
        val px = IntArray(size)
        if (channels == 3) {
            val r = data[0]; val g = data[1]; val b = data[2]
            for (i in 0 until size) {
                px[i] = (0xFF shl 24) or (to8(r[i]) shl 16) or (to8(g[i]) shl 8) or to8(b[i])
            }
        } else {
            val m = data[0]
            for (i in 0 until size) { val v = to8(m[i]); px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        fun fromBitmap(bmp: Bitmap): FloatImage {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val img = FloatImage(w, h, 3)
            val r = img.data[0]; val g = img.data[1]; val b = img.data[2]
            val inv = 1f / 255f
            for (i in 0 until w * h) {
                val p = px[i]
                r[i] = ((p shr 16) and 0xFF) * inv
                g[i] = ((p shr 8) and 0xFF) * inv
                b[i] = (p and 0xFF) * inv
            }
            return img
        }
    }
}

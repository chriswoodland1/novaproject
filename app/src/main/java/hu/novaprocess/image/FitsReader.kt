package hu.novaprocess.image

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Egyszerű FITS olvasó: 8/16/32 bites egész és 32/64 bites float képek,
 * NAXIS=2 (mono vagy Bayer) és NAXIS=3 (RGB síkok). A Seestar nyers subjai
 * 16 bites Bayer FITS-ek, BAYERPAT kulccsal — ezeket itt debayereljük.
 */
object FitsReader {

    data class Header(
        val bitpix: Int, val naxis: Int, val naxis1: Int, val naxis2: Int, val naxis3: Int,
        val bzero: Double, val bscale: Double, val bayerPat: String?, val cards: Map<String, String>
    )

    fun read(input: InputStream): FloatImage {
        val din = DataInputStream(input.buffered(1 shl 16))
        val header = readHeader(din)
        val w = header.naxis1; val h = header.naxis2
        val planes = if (header.naxis >= 3) header.naxis3 else 1
        require(w > 0 && h > 0) { "Érvénytelen FITS méret" }

        val bytesPerPx = kotlin.math.abs(header.bitpix) / 8
        val planeBytes = ByteArray(w * h * bytesPerPx)
        val raw = Array(planes) { FloatArray(w * h) }
        for (p in 0 until planes) {
            din.readFully(planeBytes)
            val bb = ByteBuffer.wrap(planeBytes).order(ByteOrder.BIG_ENDIAN)
            val dst = raw[p]
            when (header.bitpix) {
                8 -> for (i in 0 until w * h) dst[i] = (bb.get(i).toInt() and 0xFF).toFloat()
                16 -> for (i in 0 until w * h) dst[i] = bb.getShort(i * 2).toFloat()
                32 -> for (i in 0 until w * h) dst[i] = bb.getInt(i * 4).toFloat()
                -32 -> for (i in 0 until w * h) dst[i] = bb.getFloat(i * 4)
                -64 -> for (i in 0 until w * h) dst[i] = bb.getDouble(i * 8).toFloat()
                else -> throw IllegalArgumentException("Nem támogatott BITPIX: ${header.bitpix}")
            }
        }

        // BZERO/BSCALE alkalmazása, majd normalizálás 0..1-re
        val scale = header.bscale.toFloat(); val zero = header.bzero.toFloat()
        var maxVal = when (header.bitpix) {
            8 -> 255f
            16 -> 65535f
            32 -> 4294967295f
            else -> 1f
        }
        if (header.bitpix < 0) {
            // float adat: tényleges maximumot használjuk
            var m = 0f
            for (p in raw) for (v in p) if (v > m) m = v
            maxVal = if (m > 0f) m else 1f
        }
        for (p in raw) for (i in p.indices) p[i] = ((p[i] * scale + zero) / maxVal).coerceIn(0f, 1f)

        // FITS: az első sor a kép ALJA → függőleges tükrözés
        for (p in raw) flipVertical(p, w, h)

        return when {
            planes >= 3 -> FloatImage(w, h, 3).also { img ->
                for (c in 0 until 3) System.arraycopy(raw[c], 0, img.data[c], 0, w * h)
            }
            header.bayerPat != null -> debayer(raw[0], w, h, header.bayerPat)
            else -> FloatImage(w, h, 1).also { System.arraycopy(raw[0], 0, it.data[0], 0, w * h) }
        }
    }

    private fun flipVertical(a: FloatArray, w: Int, h: Int) {
        val row = FloatArray(w)
        for (y in 0 until h / 2) {
            val top = y * w; val bot = (h - 1 - y) * w
            System.arraycopy(a, top, row, 0, w)
            System.arraycopy(a, bot, a, top, w)
            System.arraycopy(row, 0, a, bot, w)
        }
    }

    private fun readHeader(din: DataInputStream): Header {
        val cards = LinkedHashMap<String, String>()
        val block = ByteArray(2880)
        var ended = false
        while (!ended) {
            din.readFully(block)
            for (i in 0 until 36) {
                val card = String(block, i * 80, 80, Charsets.US_ASCII)
                val key = card.substring(0, 8).trim()
                if (key == "END") { ended = true; break }
                if (key.isEmpty() || key == "COMMENT" || key == "HISTORY") continue
                if (card.length > 10 && card[8] == '=') {
                    var value = card.substring(10)
                    val slash = indexOfCommentSlash(value)
                    if (slash >= 0) value = value.substring(0, slash)
                    value = value.trim()
                    if (value.startsWith("'")) value = value.trim('\'').trim()
                    cards[key] = value
                }
            }
        }
        fun int(k: String, def: Int) = cards[k]?.trim()?.toIntOrNull() ?: def
        fun dbl(k: String, def: Double) = cards[k]?.trim()?.toDoubleOrNull() ?: def
        val bitpix = int("BITPIX", 16)
        val naxis = int("NAXIS", 2)
        require(naxis in 2..3) { "Csak 2D/3D FITS támogatott" }
        return Header(
            bitpix, naxis, int("NAXIS1", 0), int("NAXIS2", 0), int("NAXIS3", 1),
            dbl("BZERO", 0.0), dbl("BSCALE", 1.0),
            cards["BAYERPAT"]?.uppercase()?.takeIf { it.length == 4 }, cards
        )
    }

    private fun indexOfCommentSlash(v: String): Int {
        var inStr = false
        for (i in v.indices) {
            val ch = v[i]
            if (ch == '\'') inStr = !inStr
            else if (ch == '/' && !inStr) return i
        }
        return -1
    }

    /** Bilineáris debayer. pattern: a bal felső 2×2 blokk sorrendje, pl. "GRBG". */
    fun debayer(src: FloatArray, w: Int, h: Int, pattern: String): FloatImage {
        val out = FloatImage(w, h, 3)
        // Melyik csatorna melyik (y%2, x%2) pozíción van
        val chAt = Array(2) { IntArray(2) }
        for (i in 0 until 4) {
            val c = when (pattern[i]) { 'R' -> 0; 'G' -> 1; else -> 2 }
            chAt[i / 2][i % 2] = c
        }
        fun at(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, w - 1); val yy = y.coerceIn(0, h - 1)
            return src[yy * w + xx]
        }
        Par.rows(h) { y0, y1 ->
          for (y in y0 until y1) for (x in 0 until w) {
            val own = chAt[y and 1][x and 1]
            val i = y * w + x
            val v = src[i]
            out.data[own][i] = v
            // a hiányzó két csatorna interpolálása
            for (c in 0 until 3) {
                if (c == own) continue
                var sum = 0f; var n = 0
                // 4-szomszéd
                if (chAt[y and 1][(x + 1) and 1] == c) { sum += at(x - 1, y) + at(x + 1, y); n += 2 }
                if (chAt[(y + 1) and 1][x and 1] == c) { sum += at(x, y - 1) + at(x, y + 1); n += 2 }
                // átlós
                if (n == 0 && chAt[(y + 1) and 1][(x + 1) and 1] == c) {
                    sum += at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1); n += 4
                }
                out.data[c][i] = if (n > 0) sum / n else v
            }
          }
        }
        return out
    }
}

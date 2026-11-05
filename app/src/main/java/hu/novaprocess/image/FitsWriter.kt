package hu.novaprocess.image

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 32 bites lebegőpontos FITS író (NAXIS=3 RGB vagy NAXIS=2 mono), Siril/PixInsight-kompatibilis. */
object FitsWriter {
    fun write(img: FloatImage, out: OutputStream, extra: Map<String, String> = emptyMap()) {
        val cards = ArrayList<String>()
        fun card(k: String, v: String, c: String = "") { cards.add((k.padEnd(8) + "= " + v.padStart(20) + (if (c.isNotEmpty()) " / $c" else "")).padEnd(80).take(80)) }
        card("SIMPLE", "T", "NovaProcess")
        card("BITPIX", "-32")
        card("NAXIS", if (img.channels == 3) "3" else "2")
        card("NAXIS1", img.width.toString())
        card("NAXIS2", img.height.toString())
        if (img.channels == 3) card("NAXIS3", "3")
        card("BZERO", "0.0"); card("BSCALE", "1.0")
        card("DATAMIN", "0.0"); card("DATAMAX", "1.0")
        cards.add("CREATOR = 'NovaProcess'".padEnd(80))
        if (img.channels == 3) cards.add("CTYPE3  = 'RGB     '".padEnd(80))
        for ((k, v) in extra) cards.add((k.take(8).padEnd(8) + "= '" + v.replace("'", "''").take(66) + "'").padEnd(80).take(80))
        cards.add("END".padEnd(80))
        val header = StringBuilder(); cards.forEach { header.append(it) }
        while (header.length % 2880 != 0) header.append(" ")
        out.write(header.toString().toByteArray(Charsets.US_ASCII))

        // adatok: BIG-ENDIAN float, a FITS első sora a kép ALJA → fordított sorrend
        val row = ByteBuffer.allocate(img.width * 4).order(ByteOrder.BIG_ENDIAN)
        var written = 0L
        for (c in 0 until img.channels) {
            val a = img.data[c]
            for (y in img.height - 1 downTo 0) {
                row.clear()
                val base = y * img.width
                for (x in 0 until img.width) row.putFloat(a[base + x])
                out.write(row.array()); written += row.capacity()
            }
        }
        val pad = ((2880 - (written % 2880)) % 2880).toInt()
        if (pad > 0) out.write(ByteArray(pad))
        out.flush()
    }
}

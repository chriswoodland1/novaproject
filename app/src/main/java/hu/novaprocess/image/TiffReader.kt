package hu.novaprocess.image

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tömörítetlen 8/16 bites, chunky TIFF olvasó (a saját exportunkhoz és egyszerű külső TIFF-ekhez). */
object TiffReader {
    fun read(input: InputStream): FloatImage {
        val bytes = input.readBytes()
        val bb = ByteBuffer.wrap(bytes)
        val order = if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        bb.order(order)
        require(bb.getShort(2).toInt() == 42) { "Nem TIFF" }
        val ifd = bb.getInt(4)
        val n = bb.getShort(ifd).toInt() and 0xFFFF
        var w = 0; var h = 0; var bps = 8; var spp = 1; var comp = 1; var rowsPerStrip = Int.MAX_VALUE
        var stripOffsets = IntArray(0); var stripCounts = IntArray(0)
        fun readValues(type: Int, count: Int, pos: Int): IntArray {
            val size = when (type) { 3 -> 2; 4 -> 4; else -> 1 }
            val inline = size * count <= 4
            val base = if (inline) pos else bb.getInt(pos)
            return IntArray(count) { i -> when (type) { 3 -> bb.getShort(base + i * 2).toInt() and 0xFFFF; 4 -> bb.getInt(base + i * 4); else -> bb.get(base + i).toInt() and 0xFF } }
        }
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            val tag = bb.getShort(e).toInt() and 0xFFFF
            val type = bb.getShort(e + 2).toInt() and 0xFFFF
            val count = bb.getInt(e + 4)
            val v = readValues(type, count, e + 8)
            when (tag) {
                256 -> w = v[0]; 257 -> h = v[0]; 258 -> bps = v[0]; 259 -> comp = v[0]
                273 -> stripOffsets = v; 277 -> spp = v[0]; 278 -> rowsPerStrip = v[0]; 279 -> stripCounts = v
            }
        }
        require(comp == 1) { "Csak tömörítetlen TIFF támogatott" }
        require(bps == 8 || bps == 16) { "Csak 8/16 bites TIFF támogatott" }
        require(w > 0 && h > 0 && stripOffsets.isNotEmpty()) { "Hibás TIFF" }
        val ch = if (spp >= 3) 3 else 1
        val img = FloatImage(w, h, ch)
        val bytesPerSample = bps / 8
        val rowBytes = w * spp * bytesPerSample
        var y = 0
        val maxV = if (bps == 16) 65535f else 255f
        for ((si, off) in stripOffsets.withIndex()) {
            val rows = if (si < stripOffsets.size - 1) rowsPerStrip.coerceAtMost(h - y) else h - y
            for (r in 0 until rows) {
                val base = off + r * rowBytes
                for (x in 0 until w) for (c in 0 until spp) {
                    val pos = base + (x * spp + c) * bytesPerSample
                    val raw = if (bps == 16) (bb.getShort(pos).toInt() and 0xFFFF) else (bb.get(pos).toInt() and 0xFF)
                    if (c < ch) img.data[c][y * w + x] = raw / maxV
                }
                y++
                if (y >= h) break
            }
            if (y >= h) break
        }
        return img
    }
}

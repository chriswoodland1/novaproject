package hu.novaprocess.image

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tömörítetlen 16 bites TIFF író (mono vagy RGB), little-endian. */
object TiffWriter {

    fun write(img: FloatImage, out: OutputStream) {
        val w = img.width; val h = img.height; val spp = img.channels
        val bytesPerPx = 2 * spp
        val dataSize = w * h * bytesPerPx
        val entries = 10
        val ifdOffset = 8 + dataSize
        val bpsOffset = ifdOffset + 2 + entries * 12 + 4   // BitsPerSample tömb (ha spp>1)

        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        out.write(header.array())

        // pixeladatok, soronként
        val row = ByteBuffer.allocate(w * bytesPerPx).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) {
            row.clear()
            val base = y * w
            for (x in 0 until w) for (c in 0 until spp) {
                val v = (img.data[c][base + x].coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
                row.putShort(v.toShort())
            }
            out.write(row.array())
        }

        val ifd = ByteBuffer.allocate(2 + entries * 12 + 4 + (if (spp > 1) 2 * spp else 0)).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(entries.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            ifd.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3 && count == 1) { ifd.putShort(value.toShort()).putShort(0) } else ifd.putInt(value)
        }
        entry(256, 4, 1, w)                       // ImageWidth
        entry(257, 4, 1, h)                       // ImageLength
        if (spp == 1) entry(258, 3, 1, 16) else entry(258, 3, spp, bpsOffset)  // BitsPerSample
        entry(259, 3, 1, 1)                       // Compression: none
        entry(262, 3, 1, if (spp == 1) 1 else 2)  // Photometric: BlackIsZero / RGB
        entry(273, 4, 1, 8)                       // StripOffsets
        entry(277, 3, 1, spp)                     // SamplesPerPixel
        entry(278, 4, 1, h)                       // RowsPerStrip
        entry(279, 4, 1, dataSize)                // StripByteCounts
        entry(284, 3, 1, 1)                       // PlanarConfiguration: chunky
        ifd.putInt(0)                             // next IFD
        if (spp > 1) for (c in 0 until spp) ifd.putShort(16)
        out.write(ifd.array())
        out.flush()
    }
}

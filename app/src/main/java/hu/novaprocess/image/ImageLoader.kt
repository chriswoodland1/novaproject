package hu.novaprocess.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns

object ImageLoader {

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: uri.lastPathSegment ?: "kép"
        }
        return uri.lastPathSegment ?: "kép"
    }

    fun fileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE); if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
        }
        return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
    }

    fun isTiff(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".tif") || n.endsWith(".tiff") }

    fun isFits(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fit") || n.endsWith(".fits") || n.endsWith(".fts")
    }

    /** (szélesség, magasság, csatornák) a teljes dekódolás nélkül. */
    fun probeSize(context: Context, uri: Uri): Triple<Int, Int, Int> {
        val name = displayName(context, uri)
        val cr = context.contentResolver
        if (isFits(name)) {
            cr.openInputStream(uri)?.use { ins ->
                val block = ByteArray(2880 * 4)
                var read = 0
                while (read < block.size) { val n = ins.read(block, read, block.size - read); if (n <= 0) break; read += n }
                val txt = String(block, 0, read, Charsets.US_ASCII)
                fun key(k: String) = Regex("$k\\s*=\\s*(-?\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull()
                val w = key("NAXIS1") ?: 0; val h = key("NAXIS2") ?: 0
                val naxis = key("NAXIS") ?: 2
                val bayer = Regex("BAYERPAT\\s*=\\s*'").containsMatchIn(txt) || FitsReader.bayerOverride != null
                if (w > 0 && h > 0) return Triple(w, h, if (naxis >= 3 || bayer) 3 else 1)
            }
        } else if (BinReader.isBin(name)) {
            BinReader.inferLayout(fileSize(context, uri), BinReader.dimsOverride(context))?.let { return Triple(it.width, it.height, 3) }
        } else if (!isTiff(name)) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0) return Triple(o.outWidth, o.outHeight, 3)
        }
        val img = load(context, uri, name)
        return Triple(img.width, img.height, img.channels)
    }

    data class Meta(val objectName: String?, val exposure: Float?, val dateObs: String?, val ra: String?, val dec: String?, val instrument: String?,
                    val focalMm: Double? = null, val pixUm: Double? = null, val rawW: Int? = null, val rawH: Int? = null)

    fun metaOf(cards: Map<String, String>): Meta = Meta(
        cards["OBJECT"]?.takeIf { it.isNotBlank() }, cards["EXPTIME"]?.toFloatOrNull() ?: cards["EXPOSURE"]?.toFloatOrNull(),
        cards["DATE-OBS"], cards["RA"] ?: cards["OBJCTRA"] ?: cards["CRVAL1"], cards["DEC"] ?: cards["OBJCTDEC"] ?: cards["CRVAL2"], cards["INSTRUME"] ?: cards["TELESCOP"],
        cards["FOCALLEN"]?.toDoubleOrNull(), cards["XPIXSZ"]?.toDoubleOrNull() ?: cards["PIXSIZE1"]?.toDoubleOrNull(),
        cards["NAXIS1"]?.toIntOrNull(), cards["NAXIS2"]?.toIntOrNull()
    )

    /** Betölt egy képet, FITS esetén a fejléc-metaadatokkal együtt. */
    fun loadWithMeta(context: Context, uri: Uri, name: String = displayName(context, uri)): Pair<FloatImage, Meta?> {
        if (isFits(name)) {
            context.contentResolver.openInputStream(uri).use { ins ->
                requireNotNull(ins) { "Nem nyitható: $name" }
                val (img, cards) = FitsReader.readWithHeader(ins)
                return Pair(img, metaOf(cards))
            }
        }
        return Pair(load(context, uri, name), null)
    }

    fun isDisplayReferred(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") }

    /** Kész (már feldolgozott) kép betöltése úgy, ahogy van: sRGB értékek 0..1, gamma-konverzió nélkül. */
    fun loadDisplayReferred(context: Context, uri: Uri, name: String = displayName(context, uri)): FloatImage {
        if (!isDisplayReferred(name)) return load(context, uri, name)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = context.contentResolver.openInputStream(uri).use { ins -> BitmapFactory.decodeStream(ins, null, opts) } ?: throw IllegalArgumentException("Nem dekódolható kép: $name")
        val img = FloatImage.fromBitmap(bmp); bmp.recycle(); return img
    }

    /** Betölt egy FITS / JPEG / PNG / WebP / TIFF képet lineáris FloatImage-ként. */
    fun load(context: Context, uri: Uri, name: String = displayName(context, uri)): FloatImage {
        val cr = context.contentResolver
        if (isFits(name)) {
            cr.openInputStream(uri).use { ins ->
                requireNotNull(ins) { "Nem nyitható: $name" }
                return FitsReader.read(ins)
            }
        }
        if (isTiff(name)) {
            cr.openInputStream(uri).use { ins ->
                requireNotNull(ins) { "Nem nyitható: $name" }
                return TiffReader.read(ins)   // 16 bites lineáris adat, nincs sRGB-konverzió
            }
        }
        if (BinReader.isBin(name)) {
            val size = fileSize(context, uri)
            cr.openInputStream(uri).use { ins -> requireNotNull(ins) { "Nem nyitható: $name" }; return BinReader.read(context, ins, size) }
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = cr.openInputStream(uri).use { ins -> BitmapFactory.decodeStream(ins, null, opts) }
            ?: throw IllegalArgumentException("Nem dekódolható kép: $name")
        val img = FloatImage.fromBitmap(bmp)
        bmp.recycle()
        // sRGB -> lineáris, hogy a stackelés/feldolgozás lineáris térben történjen
        for (c in 0 until img.channels) {
            val a = img.data[c]
            for (i in 0 until img.size) a[i] = srgbToLinear(a[i])
        }
        return img
    }

    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    fun linearToSrgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else (1.055f * Math.pow(x.toDouble(), 1.0 / 2.4).toFloat() - 0.055f)
    }
}

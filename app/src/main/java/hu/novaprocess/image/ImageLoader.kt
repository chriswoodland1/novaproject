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

    fun isTiff(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".tif") || n.endsWith(".tiff") }

    fun isFits(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fit") || n.endsWith(".fits") || n.endsWith(".fts")
    }

    /** Betölt egy FITS / JPEG / PNG / WebP képet lineáris FloatImage-ként. */
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

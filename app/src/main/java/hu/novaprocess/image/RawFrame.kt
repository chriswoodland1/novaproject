package hu.novaprocess.image

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Memóriakímélő "nyers" kocka a teljes felbontású stackhez.
 * - Bayer FITS: egyetlen mono sík (0..1 float), a debayer csak a kért sávra történik.
 * - Nem-Bayer forrás (JPEG/PNG/TIFF, RGB FITS): a sáv kinyerése a teljes képből vagy régió-dekóderrel.
 */
class RawFrame private constructor(
    val width: Int, val height: Int,
    val bayer: FloatArray?,          // mono Bayer sík (Bayer FITS)
    val pattern: String?,            // pl. "GRBG"
    private val full: FloatImage?,   // nem-Bayer fallback (már betöltött kép)
    private val region: BitmapRegionDecoder?  // JPEG/PNG régió-dekóder
) {
    val channels: Int get() = 3

    /** Kalibráció a nyers Bayer-síkon: (raw - dark) / flatNorm, hot pixelek javítása. */
    fun calibrate(dark: FloatArray?, flatNorm: FloatArray?, hot: BooleanArray?) {
        val a = bayer ?: return
        if (hot != null && hot.size == a.size) {
            for (i in hot.indices) if (hot[i]) {
                val x = i % width; val y = i / width
                // azonos színű szomszédok (2 lépés) mediánja
                val v = floatArrayOf(
                    a[y * width + (x - 2).coerceAtLeast(0)], a[y * width + (x + 2).coerceAtMost(width - 1)],
                    a[(y - 2).coerceAtLeast(0) * width + x], a[(y + 2).coerceAtMost(height - 1) * width + x]
                ); v.sort(); a[i] = (v[1] + v[2]) / 2f
            }
        }
        if (dark != null && dark.size == a.size) for (i in a.indices) a[i] = (a[i] - dark[i]).coerceAtLeast(0f)
        if (flatNorm != null && flatNorm.size == a.size) for (i in a.indices) a[i] = (a[i] / flatNorm[i]).coerceIn(0f, 1f)
    }

    /** RGB sáv [y0,y1) sorokra, bilineáris debayerrel (Bayer) vagy kivágással. */
    fun rgbRows(y0: Int, y1: Int): FloatImage {
        val h = y1 - y0
        if (bayer != null) return FitsReader.debayerRegion(bayer, width, height, pattern!!, y0, y1)
        if (full != null) {
            val out = FloatImage(width, h, 3)
            val src = full.toRgb()
            for (c in 0 until 3) System.arraycopy(src.data[c], y0 * width, out.data[c], 0, width * h)
            return out
        }
        val bmp = region!!.decodeRegion(Rect(0, y0, width, y1), BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
        val img = FloatImage.fromBitmap(bmp); bmp.recycle()
        for (c in 0 until 3) { val a = img.data[c]; for (i in a.indices) a[i] = ImageLoader.srgbToLinear(a[i]) }
        return img
    }

    /** Lekicsinyített (kb. 2×) luminancia a csillagdetektáláshoz – gyors, kevés memória. */
    fun luminanceBinned(): FloatImage {
        val w2 = width / 2; val h2 = height / 2
        val out = FloatImage(w2, h2, 1); val o = out.data[0]
        if (bayer != null) {
            val a = bayer
            for (y in 0 until h2) for (x in 0 until w2) {
                val i = (2 * y) * width + 2 * x
                o[y * w2 + x] = (a[i] + a[i + 1] + a[i + width] + a[i + width + 1]) * 0.25f
            }
        } else {
            val rows = rgbRows(0, height).toMono().data[0]
            for (y in 0 until h2) for (x in 0 until w2) {
                val i = (2 * y) * width + 2 * x
                o[y * w2 + x] = (rows[i] + rows[i + 1] + rows[i + width] + rows[i + width + 1]) * 0.25f
            }
        }
        return out
    }

    fun close() { region?.recycle() }

    companion object {
        fun load(context: Context, uri: Uri): RawFrame {
            val name = ImageLoader.displayName(context, uri)
            val cr = context.contentResolver
            if (ImageLoader.isFits(name)) {
                cr.openInputStream(uri).use { ins ->
                    requireNotNull(ins)
                    val (img, hdr, raw) = FitsReader.readRawOrImage(ins)
                    return if (raw != null) RawFrame(raw.first, raw.second, raw.third, hdr["BAYERPAT"]!!.uppercase(), null, null)
                    else RawFrame(img!!.width, img.height, null, null, img, null)
                }
            }
            if (ImageLoader.isTiff(name)) {
                val img = ImageLoader.load(context, uri, name)
                return RawFrame(img.width, img.height, null, null, img, null)
            }
            val dec = cr.openInputStream(uri).use { BitmapRegionDecoder.newInstance(it!!, false) }
                ?: throw IllegalArgumentException("Nem dekódolható: $name")
            return RawFrame(dec.width, dec.height, null, null, null, dec)
        }
    }
}

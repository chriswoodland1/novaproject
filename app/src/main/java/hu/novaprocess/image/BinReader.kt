package hu.novaprocess.image

import android.content.Context
import java.io.InputStream
import kotlin.math.abs

/**
 * Fejléc nélküli nyers Bayer-kockák (.bin / .raw) olvasása. A méretet a fájlméretből találjuk ki
 * (ismert Seestar szenzorok), a bájtsorrendet a kép simaságából, a Bayer-mintát a Beállításokból.
 * Ha a fájl nagyobb, mint W×H×2, a többletet fejlécnek tekintjük (az elején).
 */
object BinReader {
    private const val PREFS = "nova_ui"; private const val KEY_BAYER = "bin_bayer"; private const val KEY_DIMS = "bin_dims"

    /** Ismert méretek: (szélesség, magasság) – Seestar S30 Pro 4K, S50/S30 FHD, álló változatok, Milky Way (wide) kamera. */
    private val KNOWN = listOf(3840 to 2160, 1920 to 1080, 2160 to 3840, 1080 to 1920, 4096 to 2160, 2560 to 1440, 3264 to 2448, 4000 to 3000, 1280 to 720)

    fun bayerPattern(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BAYER, "GRBG") ?: "GRBG"
    fun setBayerPattern(ctx: Context, p: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BAYER, p).apply() }
    /** Kézi méret felülbírálás "WxH" formában (üres = automatikus). */
    fun dimsOverride(ctx: Context): Pair<Int, Int>? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIMS, "")?.split('x', 'X', '×')?.takeIf { it.size == 2 }?.let { (a, b) -> val w = a.trim().toIntOrNull(); val h = b.trim().toIntOrNull(); if (w != null && h != null && w > 0 && h > 0) Pair(w, h) else null }
    fun setDimsOverride(ctx: Context, s: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DIMS, s.trim()).apply() }

    data class Layout(val width: Int, val height: Int, val bytesPerPx: Int, val headerBytes: Int)

    fun isBin(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".bin") || n.endsWith(".raw") }

    /** Méret-következtetés a fájlhosszból. null, ha nem ismerhető fel. */
    fun inferLayout(sizeBytes: Long, override: Pair<Int, Int>? = null): Layout? {
        val candidates = if (override != null) listOf(override) else KNOWN
        for ((w, h) in candidates) {
            val px = w.toLong() * h
            for (bpp in intArrayOf(2, 1)) {
                val data = px * bpp
                if (sizeBytes == data) return Layout(w, h, bpp, 0)
                val hdr = sizeBytes - data
                if (hdr in 1..65536) return Layout(w, h, bpp, hdr.toInt())   // kis fejléc az elején
            }
        }
        return null
    }

    fun read(ctx: Context, ins: InputStream, sizeBytes: Long): FloatImage {
        val layout = inferLayout(sizeBytes, dimsOverride(ctx)) ?: throw IllegalArgumentException("Ismeretlen .bin méret ($sizeBytes bájt) – add meg a felbontást a Beállításokban")
        val plane = readPlane(ins, layout)
        return FitsReader.debayer(plane, layout.width, layout.height, bayerPattern(ctx))
    }

    /** Nyers Bayer-sík (0..1) – a teljes felbontású sávos stackhez. */
    fun readPlane(ins: InputStream, layout: Layout): FloatArray {
        val w = layout.width; val h = layout.height
        if (layout.headerBytes > 0) { var skipped = 0L; while (skipped < layout.headerBytes) { val n = ins.skip(layout.headerBytes - skipped); if (n <= 0) break; skipped += n } }
        val bytes = ByteArray(w * h * layout.bytesPerPx)
        var read = 0; while (read < bytes.size) { val n = ins.read(bytes, read, bytes.size - read); if (n <= 0) break; read += n }
        val out = FloatArray(w * h)
        if (layout.bytesPerPx == 1) { for (i in out.indices) out[i] = (bytes[i].toInt() and 0xFF) / 255f; return out }
        // bájtsorrend: a simább (kisebb szomszéd-különbségű) értelmezés a helyes
        fun sample(le: Boolean): Float {
            var diff = 0f; var n = 0; var i = 0
            val step = (w * h / 20000).coerceAtLeast(1)
            while (i + 1 < w * h - 1) {
                val a = px16(bytes, i, le); val b = px16(bytes, i + 1, le)
                diff += abs(a - b); n++; i += step
            }
            return if (n > 0) diff / n else Float.MAX_VALUE
        }
        val le = sample(true) <= sample(false)
        // 12/14 bites adat is lehet 16 bites tárolóban → a tényleges maximumhoz normalizálunk, ha az jóval 65535 alatt van
        var mx = 0
        for (i in 0 until w * h) { val v = px16(bytes, i, le); if (v > mx) mx = v }
        val full = when { mx <= 4095 -> 4095f; mx <= 16383 -> 16383f; else -> 65535f }
        for (i in 0 until w * h) out[i] = px16(bytes, i, le) / full
        return out
    }

    private fun px16(b: ByteArray, i: Int, le: Boolean): Int {
        val lo = b[2 * i].toInt() and 0xFF; val hi = b[2 * i + 1].toInt() and 0xFF
        return if (le) (hi shl 8) or lo else (lo shl 8) or hi
    }
}

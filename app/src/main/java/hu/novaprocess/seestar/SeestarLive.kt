package hu.novaprocess.seestar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import hu.novaprocess.image.FloatImage
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import hu.novaprocess.process.StretchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.ZipInputStream

/**
 * Seestar élő kép a 4800-as bináris képcsatornán (a seestarpy projekt visszafejtett protokollja alapján,
 * seestarpy.readthedocs.io → Image Stream Protocol):
 *  - parancsok JSON-sorokként (\r\n): test_connection (szívverés ~4 s), get_stacked_img, begin_streaming, stop_streaming
 *  - keretek: 80 bájtos fejléc (magic 0x03C3, header_size, length (BE), is_big_endian, img_type, data_type, width, height…)
 *      img_type 5 = nyers élő stack (16 bit RGB, esetleg ZIP-be csomagolva), 4 = JPEG stack (firmware 8.46+),
 *      1 = kereső-előnézet (16 bites nyers Bayer), 0 = nyugta
 *  - a keretek közé JSON válaszsorok ékelődnek → a magic-re szinkronizálunk.
 * A nagy (≈50 MB) nyers stacket soronként, menet közben kicsinyítjük, sosem töltjük be egészben.
 */
object SeestarLive {
    const val PORT = 4800
    enum class Mode(val label: String) { STACK("Élő stack"), PREVIEW("Kereső") }

    data class Frame(val bitmap: Bitmap, val type: Int, val width: Int, val height: Int, val hfd: Int, val info: String)

    private class Header(val type: Int, val dataType: Int, val length: Long, val width: Int, val height: Int, val bigEndian: Boolean, val hfd: Int)

    /**
     * Élő munkamenet: amíg a hívó korutin él, kereteket ad. STACK módban `intervalSec`-enként kér új élő stacket,
     * PREVIEW módban folyamatos kereső-képet kér (a dekódolás alatt érkező kereteket átugorja).
     */
    suspend fun run(context: Context, host: String, mode: Mode, intervalSec: Int, maxSide: Int = 720,
                    onStatus: (String) -> Unit, onFrame: (Frame) -> Unit) = withContext(Dispatchers.IO) {
        val sock = SeestarSmb.wifiSocketFactory(context).createSocket() as Socket
        // a blokkoló olvasás nem szakítható meg korutinnal → leállításkor a socketet zárjuk
        val closer = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { sock.close() } }
        try {
            onStatus("Kapcsolódás: $host:$PORT…")
            sock.tcpNoDelay = true; sock.keepAlive = true; sock.receiveBufferSize = 1 shl 16
            sock.connect(InetSocketAddress(host, PORT), 6000)
            sock.soTimeout = 8000
            val out = sock.getOutputStream()
            val ins = BufferedInputStream(sock.getInputStream(), 1 shl 16)
            var id = 1
            fun send(method: String) = synchronized(out) { out.write("{\"method\":\"$method\",\"id\":${id++}}\r\n".toByteArray()); out.flush() }
            coroutineScope {
                val hb = launch { while (isActive) { runCatching { send("test_connection") }; delay(4000) } }
                try {
                    if (mode == Mode.PREVIEW) { send("begin_streaming"); onStatus("Kereső-kép kérése…") }
                    var lastReq = 0L
                    var quietSince = System.currentTimeMillis()
                    while (isActive) {
                        if (mode == Mode.STACK && System.currentTimeMillis() - lastReq > intervalSec * 1000L) {
                            send("get_stacked_img"); lastReq = System.currentTimeMillis(); onStatus("Élő stack kérése…")
                        }
                        val h = try { readHeader(ins) { line -> if (line.contains("\"error\"", true) || line.contains("fail", true)) onStatus("Seestar: " + line.take(140)) } }
                                catch (e: java.net.SocketTimeoutException) {
                                    if (System.currentTimeMillis() - quietSince > 20000) onStatus(if (mode == Mode.STACK) "Nem jön élő stack – fut megfigyelés (csillagászati mód, stackelés) a Seestaron?" else "Nem jön kereső-kép – a Seestar app élő nézete legyen nyitva.")
                                    null
                                } ?: continue
                        quietSince = System.currentTimeMillis()
                        val wanted = when (mode) { Mode.STACK -> h.type == 4 || h.type == 5; Mode.PREVIEW -> h.type == 1 }
                        if (!wanted || h.length <= 0) { skipFully(ins, h.length); continue }
                        val bounded = Bounded(ins, h.length)
                        val t0 = System.currentTimeMillis()
                        val bmp = try {
                            when (h.type) {
                                4 -> decodeJpeg(bounded, maxSide)
                                5 -> decodeRgb16(bounded, h, maxSide) { p -> onStatus("Élő stack letöltése ${p} %") }
                                else -> decodeBayer(bounded, h, maxSide)
                            }
                        } finally { skipFully(ins, bounded.remaining) }
                        if (bmp != null) {
                            val secs = (System.currentTimeMillis() - t0) / 1000f
                            val kind = when (h.type) { 4 -> "élő stack (JPEG)"; 5 -> "élő stack (nyers 16 bit)"; else -> "kereső" }
                            onFrame(Frame(bmp, h.type, h.width, h.height, h.hfd, "$kind · ${if (h.width > 0) "${h.width}×${h.height}" else "${bmp.width}×${bmp.height}"} · ${"%.1f".format(secs)} s" + (if (h.hfd > 0) " · HFD ${h.hfd / 100f}" else "")))
                            onStatus("Frissítve ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                        }
                    }
                } finally {
                    hb.cancel()
                    if (mode == Mode.PREVIEW) runCatching { send("stop_streaming") }
                }
            }
        } finally { closer?.dispose(); runCatching { sock.close() } }
    }

    // ------------------------------------------------------------------ keret-olvasás
    /** Szinkronizálás a magic-re; közben a JSON-sorokat a callbacknek adja. Null: nyugta / értelmezhetetlen. */
    private fun readHeader(ins: InputStream, onJson: (String) -> Unit): Header? {
        while (true) {
            val b = ins.read(); if (b < 0) throw EOFException("A Seestar bontotta a kapcsolatot")
            if (b == '{'.code) {
                val sb = StringBuilder("{"); while (true) { val c = ins.read(); if (c < 0 || c == '\n'.code) break; sb.append(c.toChar()); if (sb.length > 8192) break }
                onJson(sb.toString()); continue
            }
            if (b != 0x03 && b != 0xC3) continue
            val b2 = ins.read()
            val be = b == 0x03 && b2 == 0xC3; val le = b == 0xC3 && b2 == 0x03
            if (!be && !le) continue
            val rest = ByteArray(4); readFully(ins, rest)                 // version, header_size
            fun u16(a: ByteArray, o: Int) = if (be) ((a[o].toInt() and 0xff) shl 8) or (a[o + 1].toInt() and 0xff) else ((a[o + 1].toInt() and 0xff) shl 8) or (a[o].toInt() and 0xff)
            val hsize = u16(rest, 2)
            if (hsize !in 34..512) continue
            val hdr = ByteArray(hsize); hdr[0] = b.toByte(); hdr[1] = b2.toByte(); System.arraycopy(rest, 0, hdr, 2, 4)
            readFully(ins, hdr, 6, hsize - 6)
            val len = if (be) ((hdr[6].toLong() and 0xff) shl 24) or ((hdr[7].toLong() and 0xff) shl 16) or ((hdr[8].toLong() and 0xff) shl 8) or (hdr[9].toLong() and 0xff)
                      else ((hdr[9].toLong() and 0xff) shl 24) or ((hdr[8].toLong() and 0xff) shl 16) or ((hdr[7].toLong() and 0xff) shl 8) or (hdr[6].toLong() and 0xff)
            return Header(type = hdr[13].toInt() and 0xff, dataType = hdr[14].toInt() and 0xff, length = len,
                width = u16(hdr, 16), height = u16(hdr, 18), bigEndian = hdr[12].toInt() != 0, hfd = u16(hdr, 24))
        }
    }

    private class Bounded(val src: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int { if (remaining <= 0) return -1; val r = src.read(); if (r >= 0) remaining--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n
        }
        override fun available(): Int = minOf(src.available().toLong(), remaining).toInt()
    }
    private fun readFully(ins: InputStream, b: ByteArray, off: Int = 0, len: Int = b.size) { var o = off; val end = off + len; while (o < end) { val n = ins.read(b, o, end - o); if (n < 0) throw EOFException(); o += n } }
    private fun skipFully(ins: InputStream, n: Long) { var left = n; val buf = ByteArray(1 shl 16); while (left > 0) { val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r } }

    // ------------------------------------------------------------------ dekódolók
    private fun decodeJpeg(ins: InputStream, maxSide: Int): Bitmap? {
        val bytes = ins.readBytes()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** 16 bites RGB (soronként, menet közben kicsinyítve); a régebbi firmware ZIP-be csomagolja. */
    private fun decodeRgb16(raw: Bounded, h: Header, maxSide: Int, onProgress: (Int) -> Unit): Bitmap? {
        if (h.width <= 0 || h.height <= 0) return null
        val total = raw.remaining
        val buffered = BufferedInputStream(raw, 1 shl 16)
        buffered.mark(8); val sig = ByteArray(4); readFully(buffered, sig); buffered.reset()
        val zipped = sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte() && sig[2].toInt() == 3 && sig[3].toInt() == 4 && total < h.width.toLong() * h.height * 6
        val ins: InputStream = if (zipped) ZipInputStream(buffered).also { it.nextEntry } else buffered
        val w = h.width; val hh = h.height
        val f = maxOf(1, (maxOf(w, hh) + maxSide - 1) / maxSide)
        val ow = w / f; val oh = hh / f
        val acc = FloatImage(ow, oh, 3)
        val row = ByteArray(w * 6)
        var lastPct = -1
        for (y in 0 until oh * f) {
            readFully(ins, row)
            val oy = y / f; val base = oy * ow
            for (x in 0 until ow * f) {
                val i = x * 6; val o = base + x / f
                for (c in 0 until 3) {
                    val j = i + c * 2
                    val v = if (h.bigEndian) ((row[j].toInt() and 0xff) shl 8) or (row[j + 1].toInt() and 0xff) else ((row[j + 1].toInt() and 0xff) shl 8) or (row[j].toInt() and 0xff)
                    acc.data[c][o] += v.toFloat()
                }
            }
            val pct = if (total > 0) ((total - raw.remaining) * 100 / total).toInt() else y * 100 / (oh * f)
            if (pct / 5 != lastPct / 5) { lastPct = pct; onProgress(pct) }
        }
        val norm = 1f / (65535f * f * f)
        for (c in 0 until 3) { val a = acc.data[c]; for (i in a.indices) a[i] *= norm }
        return stretch(acc)
    }

    /** Kereső: 16 bites nyers Bayer (GRBG a Seestar-on) → 2×2 szuperpixel → kicsinyítés. */
    private fun decodeBayer(raw: Bounded, h: Header, maxSide: Int): Bitmap? {
        if (h.width <= 1 || h.height <= 1) return null
        val ins = BufferedInputStream(raw, 1 shl 16)
        val w = h.width; val hh = h.height
        val cw = w / 2; val ch = hh / 2
        val f = maxOf(1, (maxOf(cw, ch) + maxSide - 1) / maxSide)
        val ow = cw / f; val oh = ch / f
        val acc = FloatImage(ow, oh, 3)
        val r0 = ByteArray(w * 2); val r1 = ByteArray(w * 2)
        fun px(r: ByteArray, x: Int) = if (h.bigEndian) ((r[x * 2].toInt() and 0xff) shl 8) or (r[x * 2 + 1].toInt() and 0xff) else ((r[x * 2 + 1].toInt() and 0xff) shl 8) or (r[x * 2].toInt() and 0xff)
        for (cy in 0 until oh * f) {
            readFully(ins, r0); readFully(ins, r1)
            val base = (cy / f) * ow
            for (cx in 0 until ow * f) {
                val x = cx * 2; val o = base + cx / f
                // GRBG: (0,0)=G (0,1)=R (1,0)=B (1,1)=G
                acc.data[0][o] += px(r0, x + 1).toFloat()
                acc.data[1][o] += (px(r0, x) + px(r1, x + 1)) * 0.5f
                acc.data[2][o] += px(r1, x).toFloat()
            }
        }
        val norm = 1f / (65535f * f * f)
        for (c in 0 until 3) { val a = acc.data[c]; for (i in a.indices) a[i] *= norm }
        return stretch(acc)
    }

    private fun stretch(img: FloatImage): Bitmap =
        Processor.process(img, ProcessParams(stretchMode = StretchMode.AUTO, mtfMidtones = 0.22f, backgroundNeutralize = true, neutralizeAfterStretch = true)).toBitmap()
}

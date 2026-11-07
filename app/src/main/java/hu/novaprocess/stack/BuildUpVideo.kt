package hu.novaprocess.stack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import hu.novaprocess.image.FitsReader
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * „Így épült fel” videó: a kockák időrendben, kicsinyítve (≤ 1080 px) regisztrálva és futó átlagba gyűjtve;
 * egyre sűrűbb ellenőrzőpontokon (1, 2, 3, 5, 8, 12, 18, 25, 35, 50, 70, 100 …) a részleges stacket a
 * JELENLEGI szerkesztési beállításokkal feldolgozza, felirattal („25 kocka · 12 perc”) és áttűnéssel MP4-be (H.264) írja.
 */
object BuildUpVideo {
    data class Progress(val phase: String, val current: Int, val total: Int)

    private const val FPS = 15
    private const val HOLD_FRAMES = 8          // egy ellenőrzőpont kb. 0.5 s
    private const val FADE_FRAMES = 5
    private const val FINAL_HOLD = 45          // a végén 3 s

    fun checkpoints(n: Int): List<Int> {
        val out = ArrayList<Int>(); var k = 1.0
        while (k < n) { val v = k.toInt(); if (out.isEmpty() || v > out.last()) out += v; k = if (k < 3) k + 1 else k * 1.42 }
        if (out.isEmpty() || out.last() != n) out += n
        return out
    }

    suspend fun make(ctx: Context, frames: List<Uri>, exposureSec: Float, params: ProcessParams, title: String, out: Uri, maxSide: Int = 1080,
                     onProgress: (Progress) -> Unit): Int = withContext(Dispatchers.Default) {
        require(frames.size >= 2) { "Legalább 2 kocka kell" }
        val cps = checkpoints(frames.size).toSet()
        var ref: FloatImage? = null; var refStars: List<Star> = emptyList()
        var sum: Array<FloatArray>? = null; var cnt: IntArray? = null
        var used = 0
        val rendered = ArrayList<Pair<Int, Bitmap>>()   // (felhasznált kockák, kép)
        // a geometria (vágás, forgatás) a kicsinyített képre is érvényes, mert normalizált
        val pp = params.copy(bgSamples = params.bgSamples)
        for ((i, u) in frames.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(Progress("Kockák", i + 1, frames.size))
            val img = runCatching { loadSmall(ctx, u, maxSide) }.getOrNull() ?: continue
            if (ref == null) {
                ref = img; refStars = StarDetector.detect(img)
                sum = Array(img.channels) { FloatArray(img.size) }; cnt = IntArray(img.size)
            }
            val r = ref!!
            if (img.width != r.width || img.height != r.height || img.channels != r.channels) continue
            val reg = if (i == 0) img else {
                val tf = Aligner.estimate(refStars, StarDetector.detect(img), r.width, r.height)
                if (tf.matches < 6) continue
                Aligner.warp(img, tf)
            }
            val s = sum!!; val c = cnt!!
            for (ch in 0 until reg.channels) { val a = reg.data[ch]; val t = s[ch]; for (k in a.indices) if (a[k] != 0f || i == 0) { t[k] += a[k]; if (ch == 0) c[k]++ } }
            used++
            if ((i + 1) in cps || i == frames.lastIndex) {
                val avg = FloatImage(r.width, r.height, r.channels)
                for (ch in 0 until r.channels) { val t = s[ch]; val o = avg.data[ch]; for (k in o.indices) o[k] = if (c[k] > 0) t[k] / c[k] else 0f }
                val bmp = Processor.process(avg, pp).toBitmap()
                rendered += Pair(used, label(bmp, title, used, exposureSec))
            }
            if (i % 8 == 7) System.gc()
        }
        require(rendered.isNotEmpty()) { "Egyetlen kocka sem volt igazítható" }
        onProgress(Progress("Videó kódolása", 0, rendered.size))
        val tmp = File(ctx.cacheDir, "buildup_${System.currentTimeMillis()}.mp4")
        encode(rendered.map { it.second }, tmp) { k -> onProgress(Progress("Videó kódolása", k, rendered.size)) }
        withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(out)!!.use { os -> tmp.inputStream().use { it.copyTo(os) } }; tmp.delete() }
        rendered.size
    }

    /** Gyors, kicsinyített betöltés: FITS Bayer → szuperpixel; más formátum → teljes betöltés + kicsinyítés. */
    private fun loadSmall(ctx: Context, uri: Uri, maxSide: Int): FloatImage {
        val name = ImageLoader.displayName(ctx, uri)
        if (ImageLoader.isFits(name)) {
            ctx.contentResolver.openInputStream(uri).use { ins ->
                val (img, cards, raw) = FitsReader.readRawOrImage(requireNotNull(ins))
                return if (raw != null) FrameAnalyzer.superpixel(raw.third, raw.first, raw.second, (cards["BAYERPAT"] ?: "GRBG").uppercase()).downscale(maxSide)
                       else img!!.downscale(maxSide)
            }
        }
        return ImageLoader.load(ctx, uri, name).downscale(maxSide)
    }

    private fun label(src: Bitmap, title: String, n: Int, exp: Float): Bitmap {
        val b = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(b); val s = minOf(b.width, b.height) / 26f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = s; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setShadowLayer(s * 0.15f, 0f, 0f, Color.BLACK) }
        val sub = Paint(p).apply { textSize = s * 0.8f; color = Color.rgb(220, 220, 220); typeface = Typeface.DEFAULT }
        val tot = n * exp
        val line = "$n kocka" + (if (exp > 0f) " · " + (if (tot >= 3600) "%.1f óra".format(tot / 3600) else "%.0f perc".format(tot / 60)) else "")
        val m = s * 0.8f
        if (title.isNotBlank()) cv.drawText(title, m, b.height - m - sub.textSize * 1.3f, p)
        cv.drawText(line, m, b.height - m, sub)
        return b
    }

    // ------------------------------------------------------------------ H.264 kódolás (YUV bemenet, saját időbélyegek)
    private fun encode(frames: List<Bitmap>, file: File, onFrame: (Int) -> Unit) {
        val w0 = frames[0].width; val h0 = frames[0].height
        val w = w0 / 16 * 16; val h = h0 / 16 * 16
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 6).coerceIn(2_000_000, 12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = MediaCodec.BufferInfo()
        var pts = 0L; val dt = 1_000_000L / FPS
        val argb = IntArray(w * h); val argb2 = IntArray(w * h); val mix = IntArray(w * h)

        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end) return }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        fun put(px: IntArray, eos: Boolean = false) {
            var idx: Int
            do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
            if (eos) { codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); return }
            val img = codec.getInputImage(idx)!!
            val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
            val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + x]; val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yb.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    ub.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vb.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0); pts += dt
            drain(false)
        }
        fun pixels(b: Bitmap, dst: IntArray) { val s = if (b.width == w && b.height == h) b else Bitmap.createBitmap(b, 0, 0, w, h); s.getPixels(dst, 0, w, 0, 0, w, h) }
        try {
            for ((k, b) in frames.withIndex()) {
                pixels(b, argb)
                if (k > 0) for (f in 1..FADE_FRAMES) {          // áttűnés az előző képről
                    val t = f.toFloat() / (FADE_FRAMES + 1)
                    for (i in mix.indices) {
                        val a = argb2[i]; val c = argb[i]
                        val r = (((a shr 16) and 0xff) * (1 - t) + ((c shr 16) and 0xff) * t).toInt()
                        val g = (((a shr 8) and 0xff) * (1 - t) + ((c shr 8) and 0xff) * t).toInt()
                        val bl = ((a and 0xff) * (1 - t) + (c and 0xff) * t).toInt()
                        mix[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    put(mix)
                }
                repeat(if (k == frames.lastIndex) FINAL_HOLD else HOLD_FRAMES) { put(argb) }
                System.arraycopy(argb, 0, argb2, 0, argb.size)
                onFrame(k + 1)
            }
            put(argb, eos = true); drain(true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxing) runCatching { muxer.stop() }; muxer.release()
        }
    }
}

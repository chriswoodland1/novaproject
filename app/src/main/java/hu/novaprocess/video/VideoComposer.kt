package hu.novaprocess.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class VideoFormat(val label: String) { ORIGINAL("Eredeti arány"), REEL("9:16 Reels / TikTok") }
enum class VideoOutput(val label: String, val mime: String, val ext: String) { MP4("MP4 videó", "video/mp4", "mp4"), GIF("GIF (Messenger)", "image/gif", "gif") }

data class VideoOptions(
    val format: VideoFormat = VideoFormat.ORIGINAL,
    val output: VideoOutput = VideoOutput.MP4,
    val lengthSec: Int = 0,              // 0 = automatikus
    val zoom: Boolean = true,            // ráközelítés a tárgyra a végén
    val beforeAfter: Boolean = true,     // előtte–utána csúszka a végén
    val stats: Boolean = true            // számláló + zaj-grafikon
)

/** Egy állomás: kép + felirat; `stat` a zaj (lineáris σ), ha mérhető – ebből lesz a grafikon. */
data class Scene(val bitmap: Bitmap, val caption: String, val sub: String = "", val stat: Float? = null)

/** Arculat: cím, kontextus-sor, adatsor, logó, kiemelőszín (a megosztás-kártyából / vízjelből). */
data class Branding(val title: String, val context: String?, val info: String, val logoPath: String?, val accent: Int = 0xFF8FE3A8.toInt())

/**
 * Közös videó-összeállító: jelenetek áttűnéssel → (opcionálisan) előtte–utána csúszka → ráközelítés a tárgyra → zárókép
 * beúszó címmel. Kimenet: H.264 MP4 (saját időbélyegek, YUV bemenet) vagy animált GIF (medián-vágásos paletta, LZW).
 */
object VideoComposer {
    private const val FPS = 24

    private sealed class Seg(val frames: Int) {
        class Hold(val s: Int, n: Int) : Seg(n)
        class Fade(val a: Int, val b: Int, n: Int) : Seg(n)
        class Wipe(val before: Int, val after: Int, n: Int) : Seg(n)
        class Zoom(val s: Int, n: Int) : Seg(n)
        class Final(val s: Int, n: Int) : Seg(n)
    }

    fun compose(scenes: List<Scene>, opts: VideoOptions, brand: Branding, zoomRect: RectF?, defaultHoldSec: Float,
                out: File, onProgress: (Int, Int) -> Unit) {
        require(scenes.isNotEmpty())
        val last = scenes.lastIndex
        val zr = if (opts.zoom) zoomRect?.takeIf { it.width() in 0.05f..0.9f && it.height() in 0.05f..0.9f } else null
        val wipe = opts.beforeAfter && scenes.size >= 2
        val fade = (FPS * 0.4f).toInt()
        val fixed = (if (wipe) 3.2f else 0f) + (if (zr != null) 2.4f else 0f) + 2.6f
        val holdSec = if (opts.lengthSec > 0) max(0.35f, (opts.lengthSec - fixed) / scenes.size - 0.4f) else defaultHoldSec
        val segs = ArrayList<Seg>()
        for (i in scenes.indices) {
            if (i > 0) segs += Seg.Fade(i - 1, i, fade)
            if (i < last || (!wipe && zr == null)) segs += Seg.Hold(i, (holdSec * FPS).roundToInt().coerceAtLeast(6))
        }
        if (wipe) segs += Seg.Wipe(0, last, (3.2f * FPS).toInt())
        if (zr != null) segs += Seg.Zoom(last, (2.4f * FPS).toInt())
        segs += Seg.Final(last, (2.6f * FPS).toInt())

        val r = Renderer(scenes, opts, brand, zr)
        val total = segs.sumOf { it.frames }
        val frameAt: (Int, Bitmap) -> Unit = { fi, dst ->
            var k = fi; var seg = segs[0]
            for (s in segs) { if (k < s.frames) { seg = s; break }; k -= s.frames }
            val t = if (seg.frames > 1) k.toFloat() / (seg.frames - 1) else 1f
            r.draw(dst, seg, t)
        }
        when (opts.output) {
            VideoOutput.MP4 -> Mp4.encode(r.w, r.h, total, FPS, frameAt, onProgress, out)
            VideoOutput.GIF -> Gif.encode(r.w, r.h, total, FPS, frameAt, onProgress, out, scenes)
        }
    }

    // ------------------------------------------------------------------ renderelés
    private class Renderer(val scenes: List<Scene>, val opts: VideoOptions, val brand: Branding, val zoom: RectF?) {
        val reel = opts.format == VideoFormat.REEL
        val w: Int; val h: Int
        val content: RectF
        private val logo: Bitmap? = brand.logoPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        private val stats = scenes.map { it.stat }.takeIf { opts.stats && it.count { s -> s != null } >= 2 }
        init {
            if (reel) { w = 1080; h = 1920; content = RectF(0f, 230f, 1080f, 1920f - 330f) }
            else {
                val b = scenes[0].bitmap; val s = min(1f, 1080f / max(b.width, b.height))
                w = ((b.width * s).toInt() / 16 * 16).coerceAtLeast(160); h = ((b.height * s).toInt() / 16 * 16).coerceAtLeast(160)
                content = RectF(0f, 0f, w.toFloat(), h.toFloat())
            }
        }
        private val unit = min(w, h) / 30f
        private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = unit * 1.5f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setShadowLayer(unit * 0.2f, 0f, 0f, Color.BLACK) }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 215, 220); textSize = unit * 0.95f; setShadowLayer(unit * 0.2f, 0f, 0f, Color.BLACK) }
        private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 205); textSize = unit * 0.75f; setShadowLayer(unit * 0.15f, 0f, 0f, Color.BLACK) }
        private val pAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand.accent; strokeWidth = max(2f, unit * 0.12f); style = Paint.Style.STROKE }
        private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** A kép kívánt forrás-téglalapja (zoom) a tartalom-terület arányával. */
        private fun srcRect(b: Bitmap, z: Float): Rect {
            val full = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
            if (zoom == null || z <= 0f) return Rect(0, 0, b.width, b.height)
            val tgt = RectF(zoom.left * b.width, zoom.top * b.height, zoom.right * b.width, zoom.bottom * b.height)
            val ca = content.width() / content.height()
            // a cél kitágítása a tartalom arányára
            var tw = tgt.width(); var th = tgt.height()
            if (tw / th > ca) th = tw / ca else tw = th * ca
            val cx = tgt.centerX(); val cy = tgt.centerY()
            val t2 = RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
            if (t2.left < 0) t2.offset(-t2.left, 0f); if (t2.top < 0) t2.offset(0f, -t2.top)
            if (t2.right > b.width) t2.offset(b.width - t2.right, 0f); if (t2.bottom > b.height) t2.offset(0f, b.height - t2.bottom)
            val e = z * z * (3 - 2 * z)    // smoothstep
            return Rect((full.left + (t2.left - full.left) * e).toInt(), (full.top + (t2.top - full.top) * e).toInt(),
                (full.right + (t2.right - full.right) * e).toInt(), (full.bottom + (t2.bottom - full.bottom) * e).toInt())
        }
        private fun fit(src: Rect): RectF {
            val sa = src.width().toFloat() / src.height(); val ca = content.width() / content.height()
            return if (sa > ca) { val hh = content.width() / sa; RectF(content.left, content.centerY() - hh / 2, content.right, content.centerY() + hh / 2) }
                   else { val ww = content.height() * sa; RectF(content.centerX() - ww / 2, content.top, content.centerX() + ww / 2, content.bottom) }
        }
        private fun image(cv: Canvas, s: Int, alpha: Int = 255, z: Float = 0f, clip: RectF? = null) {
            val b = scenes[s].bitmap; val src = srcRect(b, z); val dst = fit(src)
            pImg.alpha = alpha
            if (clip != null) { cv.save(); cv.clipRect(clip) }
            cv.drawBitmap(b, src, dst, pImg)
            if (clip != null) cv.restore()
        }

        fun draw(dst: Bitmap, seg: Seg, t: Float) {
            val cv = Canvas(dst); cv.drawColor(Color.rgb(6, 7, 10))
            var capIdx = 0; var capAlpha = 255; var progress = 0f
            when (seg) {
                is Seg.Hold -> { image(cv, seg.s); capIdx = seg.s; progress = seg.s.toFloat() }
                is Seg.Fade -> { image(cv, seg.a); image(cv, seg.b, (t * 255).toInt()); capIdx = if (t < 0.5f) seg.a else seg.b; capAlpha = (abs1(t - 0.5f) * 2 * 255).toInt(); progress = seg.a + t }
                is Seg.Wipe -> {
                    // 0→0.35: a vonal jobbról középre; 0.35→0.75: áll; 0.75→1: balra kifut (teljes „utána”)
                    val x = when { t < 0.35f -> 1f - 0.5f * (t / 0.35f); t < 0.75f -> 0.5f; else -> 0.5f - 0.5f * ((t - 0.75f) / 0.25f) }
                    image(cv, seg.after)
                    val img = fit(Rect(0, 0, scenes[seg.before].bitmap.width, scenes[seg.before].bitmap.height))
                    val lineX = img.left + img.width() * x
                    image(cv, seg.before, clip = RectF(img.left, img.top, lineX, img.bottom))
                    cv.drawLine(lineX, img.top, lineX, img.bottom, Paint(pAccent).apply { color = Color.WHITE; strokeWidth = max(3f, unit * 0.15f) })
                    if (t in 0.2f..0.85f) {
                        val pb = Paint(pSub).apply { textAlign = Paint.Align.RIGHT }; val pa = Paint(pSub).apply { textAlign = Paint.Align.LEFT }
                        val y = img.top + unit * 2f
                        cv.drawText(scenes[seg.before].caption, lineX - unit * 0.6f, y, pb)
                        cv.drawText(scenes[seg.after].caption, lineX + unit * 0.6f, y, pa)
                    }
                    capIdx = -1; progress = scenes.lastIndex.toFloat()
                }
                is Seg.Zoom -> { image(cv, seg.s, z = t); capIdx = -1; progress = scenes.lastIndex.toFloat() }
                is Seg.Final -> { image(cv, seg.s, z = if (zoom != null) 1f else 0f); capIdx = -1; progress = scenes.lastIndex.toFloat() }
            }
            if (reel) bars(cv)
            if (capIdx >= 0) caption(cv, scenes[capIdx], capAlpha)
            if (stats != null && seg !is Seg.Final) statsGraph(cv, progress)
            if (seg is Seg.Final && !reel) finalTitle(cv, min(1f, t * 3f))
            if (seg is Seg.Final && reel) finalTitleReel(cv, min(1f, t * 3f))
        }
        private fun abs1(v: Float) = if (v < 0) -v else v

        private fun caption(cv: Canvas, s: Scene, alpha: Int) {
            val x = content.left + unit; var y = if (reel) content.bottom + unit * 2.2f else content.bottom - unit * (if (s.sub.isNotBlank()) 2.4f else 1.2f)
            val pc = Paint(pTitle).apply { textSize = unit * 1.15f; this.alpha = alpha }
            cv.drawText(s.caption, x, y, pc)
            if (s.sub.isNotBlank()) { y += unit * 1.3f; cv.drawText(s.sub, x, y, Paint(pSub).apply { this.alpha = alpha }) }
        }

        private fun statsGraph(cv: Canvas, progress: Float) {
            val v = stats ?: return
            val vals = v.map { it ?: Float.NaN }
            val gw = min(content.width() * 0.34f, unit * 11f); val gh = gw * 0.5f
            val left = content.right - gw - unit * 0.8f; val top = content.top + unit * (if (reel) 0.8f else 0.8f)
            val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
            cv.drawRoundRect(RectF(left - unit * 0.4f, top - unit * 0.4f, left + gw + unit * 0.4f, top + gh + unit * 1.6f), unit * 0.5f, unit * 0.5f, bg)
            val fin = vals.filter { !it.isNaN() }; val mx = fin.max(); val mn = fin.min().coerceAtMost(mx * 0.999f)
            val path = Path(); var started = false
            val n = vals.size
            for (i in 0 until n) {
                if (vals[i].isNaN() || i > progress + 1e-3f) continue
                val px = left + gw * i / (n - 1).coerceAtLeast(1); val py = top + gh * (1f - (vals[i] - mn) / (mx - mn))
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            cv.drawPath(path, pAccent)
            val cur = vals.getOrNull(progress.toInt().coerceIn(0, n - 1))?.takeIf { !it.isNaN() } ?: fin.last()
            val drop = (1f - cur / fin.first()) * 100f
            cv.drawText("zaj −${"%.0f".format(max(0f, drop))} %", left, top + gh + unit * 1.1f, pSmall)
        }

        private fun bars(cv: Canvas) {
            val bar = Paint().apply { color = Color.rgb(9, 10, 14) }
            cv.drawRect(0f, 0f, w.toFloat(), content.top, bar); cv.drawRect(0f, content.bottom, w.toFloat(), h.toFloat(), bar)
            cv.drawLine(0f, content.top, w.toFloat(), content.top, pAccent)
            val pt = Paint(pTitle).apply { textSize = unit * 1.7f; clearShadowLayer() }
            fitText(pt, brand.title.ifBlank { "Asztrofotó" }, w - unit * 2)
            cv.drawText(brand.title.ifBlank { "Asztrofotó" }, unit, content.top - unit * 2.6f, pt)
            brand.context?.let { cv.drawText(ellipsize(Paint(pSub).apply { clearShadowLayer() }, it, w - unit * 2), unit, content.top - unit * 1.1f, Paint(pSub).apply { clearShadowLayer() }) }
            val pi = Paint(pSmall).apply { clearShadowLayer() }
            cv.drawText(ellipsize(pi, brand.info, w - unit * 8), unit, h - unit * 1.4f, pi)
            logo?.let { lg -> val lh = unit * 3.2f; val lw = lh * lg.width / lg.height; cv.drawBitmap(lg, null, RectF(w - unit - lw, h - unit - lh, w - unit, h - unit), pImg.apply { alpha = 255 }) }
        }

        private fun finalTitle(cv: Canvas, a: Float) {
            val al = (a * 255).toInt()
            val grad = Paint().apply { shader = android.graphics.LinearGradient(0f, h * 0.6f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb((a * 200).toInt(), 0, 0, 0), android.graphics.Shader.TileMode.CLAMP) }
            cv.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), grad)
            val pt = Paint(pTitle).apply { alpha = al }; fitText(pt, brand.title.ifBlank { "Asztrofotó" }, w - unit * 2)
            var y = h - unit * (if (brand.context != null) 4.0f else 2.8f)
            cv.drawText(brand.title.ifBlank { "Asztrofotó" }, unit, y, pt)
            brand.context?.let { y += unit * 1.4f; cv.drawText(ellipsize(pSub, it, w - unit * 2), unit, y, Paint(pSub).apply { alpha = al }) }
            y += unit * 1.2f; cv.drawText(ellipsize(pSmall, brand.info, w - unit * 6), unit, y, Paint(pSmall).apply { alpha = al })
            logo?.let { lg -> val lh = unit * 2.4f; val lw = lh * lg.width / lg.height; cv.drawBitmap(lg, null, RectF(w - unit - lw, h - unit - lh, w - unit, h - unit), Paint(pImg).apply { alpha = al }) }
        }
        private fun finalTitleReel(cv: Canvas, a: Float) {
            val pc = Paint(pTitle).apply { textSize = unit * 1.2f; alpha = (a * 255).toInt() }
            cv.drawText("${scenes.last().caption}${if (scenes.last().sub.isNotBlank()) " · " + scenes.last().sub else ""}", unit, content.bottom + unit * 2.2f, pc)
        }
        private fun fitText(p: Paint, t: String, maxW: Float) { while (p.measureText(t) > maxW && p.textSize > 10f) p.textSize *= 0.94f }
        private fun ellipsize(p: Paint, t: String, maxW: Float): String { var s = t; while (p.measureText(s) > maxW && s.length > 4) s = s.dropLast(2).trimEnd() + "…"; return s }
    }

    // ------------------------------------------------------------------ MP4 (H.264)
    private object Mp4 {
        fun encode(w: Int, h: Int, total: Int, fps: Int, frameAt: (Int, Bitmap) -> Unit, onProgress: (Int, Int) -> Unit, file: File) {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 5).coerceIn(2_000_000, 14_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1; var muxing = false
            val info = MediaCodec.BufferInfo(); val dt = 1_000_000L / fps; var pts = 0L
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val px = IntArray(w * h)
            fun drain(end: Boolean) {
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
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
            try {
                for (f in 0..total) {
                    var idx: Int
                    do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
                    if (f == total) { codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                    frameAt(f, bmp); bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val img = codec.getInputImage(idx)!!
                    val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
                    val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
                    for (y in 0 until h) { val yr = y * yP.rowStride; for (x in 0 until w) {
                        val c = px[y * w + x]; val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                        yb.put(yr + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                        if (y and 1 == 0 && x and 1 == 0) {
                            ub.put((y shr 1) * uP.rowStride + (x shr 1) * uP.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                            vb.put((y shr 1) * vP.rowStride + (x shr 1) * vP.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                        }
                    } }
                    codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0); pts += dt
                    drain(false)
                    if (f % 6 == 0) onProgress(f, total)
                }
                drain(true)
            } finally {
                runCatching { codec.stop() }; codec.release()
                if (muxing) runCatching { muxer.stop() }; muxer.release()
            }
        }
    }

    // ------------------------------------------------------------------ GIF (animált)
    private object Gif {
        fun encode(w0: Int, h0: Int, total: Int, fps: Int, frameAt: (Int, Bitmap) -> Unit, onProgress: (Int, Int) -> Unit, file: File, scenes: List<Scene>) {
            // kisebb méret és ~8 fps, hogy Messengeren is átmenjen
            val s = min(1f, 480f / max(w0, h0)); val w = (w0 * s).toInt(); val h = (h0 * s).toInt()
            val step = max(1, fps / 8); val delayCs = (100 * step / fps)
            val big = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            // paletta: medián-vágás néhány jellemző képkocka mintáiból
            val samples = ArrayList<Int>()
            for (f in listOf(0, total / 3, 2 * total / 3, total - 1)) {
                frameAt(f.coerceIn(0, total - 1), big); val sm = Bitmap.createScaledBitmap(big, w, h, true); sm.getPixels(px, 0, w, 0, 0, w, h)
                var i = 0; while (i < px.size) { samples += px[i]; i += 3 }
            }
            val pal = medianCut(samples.toIntArray(), 256)
            val cache = ShortArray(32768) { -1 }
            fun nearest(c: Int): Int {
                val key = ((c shr 9) and 0x7c00) or ((c shr 6) and 0x3e0) or ((c shr 3) and 0x1f)
                val hit = cache[key]; if (hit >= 0) return hit.toInt()
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                var best = 0; var bd = Int.MAX_VALUE
                for (i in pal.indices) { val p = pal[i]; val dr = r - ((p shr 16) and 0xff); val dg = g - ((p shr 8) and 0xff); val db = b - (p and 0xff); val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db; if (d < bd) { bd = d; best = i } }
                cache[key] = best.toShort(); return best
            }
            val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
            BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { os ->
                os.write("GIF89a".toByteArray()); le16(os, w); le16(os, h); os.write(0xF7); os.write(0); os.write(0)
                for (i in 0 until 256) { val p = pal.getOrElse(i) { 0 }; os.write((p shr 16) and 0xff); os.write((p shr 8) and 0xff); os.write(p and 0xff) }
                os.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); os.write("NETSCAPE2.0".toByteArray()); os.write(byteArrayOf(3, 1, 0, 0, 0))
                val idx = ByteArray(w * h)
                var f = 0
                while (f < total) {
                    frameAt(f, big); Bitmap.createScaledBitmap(big, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
                    for (y in 0 until h) for (x in 0 until w) {
                        val d = (bayer[(y and 3) * 4 + (x and 3)] - 8) / 2
                        val c = px[y * w + x]
                        val r = (((c shr 16) and 0xff) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xff) + d).coerceIn(0, 255); val b = ((c and 0xff) + d).coerceIn(0, 255)
                        idx[y * w + x] = nearest((r shl 16) or (g shl 8) or b).toByte()
                    }
                    val delay = if (f + step >= total) 300 else delayCs
                    os.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0)); le16(os, delay); os.write(0); os.write(0)
                    os.write(0x2C); le16(os, 0); le16(os, 0); le16(os, w); le16(os, h); os.write(0)
                    lzw(idx, 8, os)
                    onProgress(f, total); f += step
                }
                os.write(0x3B)
            }
        }
        private fun le16(os: OutputStream, v: Int) { os.write(v and 0xff); os.write((v shr 8) and 0xff) }

        /** GIF LZW (változó kódhossz, a Kevin Weiner-féle kódoló logikája szerint). */
        private fun lzw(data: ByteArray, minCode: Int, os: OutputStream) {
            os.write(minCode)
            val clear = 1 shl minCode; val eoi = clear + 1
            var codeSize = minCode + 1; var maxCode = (1 shl codeSize) - 1; var next = clear + 2; var clearFlag = false
            val dict = HashMap<Int, Int>(8192)
            val block = ByteArray(255); var bn = 0; var acc = 0; var nbits = 0
            fun flushBlock() { if (bn > 0) { os.write(bn); os.write(block, 0, bn); bn = 0 } }
            fun out(code: Int) {
                acc = acc or (code shl nbits); nbits += codeSize
                while (nbits >= 8) { block[bn++] = (acc and 0xff).toByte(); acc = acc ushr 8; nbits -= 8; if (bn == 255) flushBlock() }
                if (clearFlag) { codeSize = minCode + 1; maxCode = (1 shl codeSize) - 1; clearFlag = false }
                else if (next > maxCode) { codeSize++; maxCode = if (codeSize == 12) 4096 else (1 shl codeSize) - 1 }
            }
            out(clear)
            var prefix = data[0].toInt() and 0xff
            for (i in 1 until data.size) {
                val k = data[i].toInt() and 0xff; val key = (prefix shl 8) or k
                val c = dict[key]
                if (c != null) { prefix = c; continue }
                out(prefix)
                if (next < 4096) dict[key] = next++ else { dict.clear(); next = clear + 2; clearFlag = true; out(clear) }
                prefix = k
            }
            out(prefix); out(eoi)
            if (nbits > 0) { block[bn++] = (acc and 0xff).toByte(); if (bn == 255) flushBlock() }
            flushBlock(); os.write(0)
        }

        private fun medianCut(px: IntArray, n: Int): IntArray {
            class Box(val c: IntArray)
            val boxes = ArrayList<Box>(); boxes += Box(px)
            fun range(b: Box, ch: Int): Int { var lo = 255; var hi = 0; for (c in b.c) { val v = (c shr (16 - 8 * ch)) and 0xff; if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo }
            while (boxes.size < n) {
                var bi = -1; var bs = 0L; var bch = 0
                for ((i, b) in boxes.withIndex()) { if (b.c.size < 2) continue; for (ch in 0..2) { val s = range(b, ch).toLong() * b.c.size; if (s > bs) { bs = s; bi = i; bch = ch } } }
                if (bi < 0) break
                val b = boxes.removeAt(bi); val sh = 16 - 8 * bch
                val sorted = b.c.sortedBy { (it shr sh) and 0xff }.toIntArray(); val m = sorted.size / 2
                boxes += Box(sorted.copyOfRange(0, m)); boxes += Box(sorted.copyOfRange(m, sorted.size))
            }
            return IntArray(boxes.size) { i -> val b = boxes[i].c; var r = 0L; var g = 0L; var bl = 0L; for (c in b) { r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; bl += c and 0xff }; val k = b.size.coerceAtLeast(1); ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (bl / k).toInt() }
        }
    }
}

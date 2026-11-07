package hu.novaprocess.process

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** Megosztás-kártya elrendezések. */
enum class CardLayout(val label: String) { BOTTOM_BAR("Alsó sáv"), SIDE_BAR("Oldalsó sáv"), POSTER("Poszter-keret"), MINIMAL("Minimál") }

data class CardSettings(
    val layout: CardLayout = CardLayout.BOTTOM_BAR,
    val showLogo: Boolean = true,          // a vízjel PNG logója (ha van) a kártyán
    val showContext: Boolean = true,       // típus · csillagkép · távolság sor (katalógusból)
    val showQr: Boolean = false,
    val qrText: String = "",               // pl. Astrobin / Instagram link
    val footer: String = "",               // pl. @handle vagy saját név
    val accent: Int = 0xFF8FE3A8.toInt(),  // vékony színes vonal / kiemelés
    val dark: Boolean = true,              // sötét (fekete) vagy világos (krém) háttér
    val fontScale: Float = 1f,
    val favorite: String = ""              // mentett kedvenc neve (csak kijelzéshez)
)

/** A kártyára kerülő adatok: a felirat adatai + katalógus-kontextus. */
data class CardData(val info: InfoData, val context: String? = null)

object ShareCard {
    private const val PREFS = "nova_ui"; private const val KEY = "share_card"; private const val KEY_FAV = "share_card_favs"
    private val gson = Gson()
    fun load(ctx: Context): CardSettings = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { runCatching { gson.fromJson(it, CardSettings::class.java) }.getOrNull() } ?: CardSettings()
    fun save(ctx: Context, s: CardSettings) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(s)).apply() }
    /** Mentett kedvencek (név → beállítás). */
    fun favorites(ctx: Context): Map<String, CardSettings> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAV, null)?.let { runCatching { gson.fromJson(it, Array<CardSettings>::class.java).associateBy { f -> f.favorite } }.getOrNull() } ?: emptyMap()
    fun saveFavorite(ctx: Context, name: String, s: CardSettings) { val m = favorites(ctx).toMutableMap(); m[name] = s.copy(favorite = name); ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAV, gson.toJson(m.values.toTypedArray())).apply() }
    fun deleteFavorite(ctx: Context, name: String) { val m = favorites(ctx).toMutableMap(); m.remove(name); ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAV, gson.toJson(m.values.toTypedArray())).apply() }

    private fun bg(s: CardSettings) = if (s.dark) Color.rgb(9, 10, 14) else Color.rgb(246, 243, 236)
    private fun fg(s: CardSettings) = if (s.dark) Color.WHITE else Color.rgb(24, 24, 28)
    private fun dim(s: CardSettings) = if (s.dark) Color.rgb(165, 170, 180) else Color.rgb(100, 100, 108)

    fun integration(d: InfoData): String = if (d.frames > 0 && d.exposureSec > 0f) {
        val tot = d.frames * d.exposureSec
        "${d.frames} × ${"%.0f".format(d.exposureSec)} s = " + (if (tot >= 3600) "%.1f óra".format(tot / 3600) else "%.0f perc".format(tot / 60))
    } else if (d.frames > 0) "${d.frames} kocka" else ""

    private fun lines(s: CardSettings, d: CardData): List<String> {
        val i = d.info
        return listOfNotNull(
            if (s.showContext) d.context?.ifBlank { null } else null,
            listOf(i.city, i.date).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            listOf(i.instrument, integration(i)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            s.footer.ifBlank { null }
        )
    }

    private fun logo(ctx: Context, s: CardSettings, wm: WatermarkSettings): Bitmap? =
        if (s.showLogo && wm.imagePath != null && File(wm.imagePath).exists()) BitmapFactory.decodeFile(wm.imagePath) else null

    fun qr(text: String, sizePx: Int, dark: Boolean): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val on = if (dark) Color.WHITE else Color.BLACK; val off = Color.TRANSPARENT
        val px = IntArray(sizePx * sizePx) { i -> if (m.get(i % sizePx, i / sizePx)) on else off }
        b.setPixels(px, 0, sizePx, 0, 0, sizePx, sizePx); b
    }.getOrNull()

    /** Kártya renderelése: a képet NEM módosítja, új bitmapet ad (a kép eredeti felbontásban kerül rá). */
    fun render(ctx: Context, img: Bitmap, s: CardSettings, d: CardData, wm: WatermarkSettings): Bitmap = when (s.layout) {
        CardLayout.BOTTOM_BAR -> bottomBar(ctx, img, s, d, wm, false)
        CardLayout.MINIMAL -> bottomBar(ctx, img, s, d, wm, true)
        CardLayout.SIDE_BAR -> sideBar(ctx, img, s, d, wm)
        CardLayout.POSTER -> poster(ctx, img, s, d, wm)
    }

    private fun paints(s: CardSettings, base: Float): Triple<Paint, Paint, Paint> {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg(s); textSize = base * 1.9f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim(s); textSize = base; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.accent; style = Paint.Style.FILL }
        return Triple(title, body, accent)
    }

    private fun fitTitle(p: Paint, text: String, maxW: Float) { while (p.measureText(text) > maxW && p.textSize > 10f) p.textSize *= 0.94f }

    private fun bottomBar(ctx: Context, img: Bitmap, s: CardSettings, d: CardData, wm: WatermarkSettings, minimal: Boolean): Bitmap {
        val W = img.width
        val ls = lines(s, d)
        val base = W * (if (minimal) 0.016f else 0.02f) * s.fontScale
        val (title, body, acc) = paints(s, base)
        val pad = W * 0.035f
        val textH = if (minimal) base * 2.4f else title.textSize * 1.25f + ls.size * base * 1.45f
        val barH = (textH + pad * 1.6f).toInt()
        val out = Bitmap.createBitmap(W, img.height + barH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(bg(s)); cv.drawBitmap(img, 0f, 0f, null)
        cv.drawRect(0f, img.height.toFloat(), W.toFloat(), img.height + base * 0.18f, acc)
        var rightX = W - pad
        // jobb oldal: logó + QR
        val slot = barH - pad * 1.2f
        if (s.showQr && s.qrText.isNotBlank()) qr(s.qrText, slot.toInt().coerceAtLeast(32), s.dark)?.let { q -> cv.drawBitmap(q, rightX - slot, img.height + pad * 0.6f, null); rightX -= slot + pad * 0.6f }
        logo(ctx, s, wm)?.let { lg -> val lh = slot; val lw = lh * lg.width / lg.height; cv.drawBitmap(lg, null, RectF(rightX - lw, img.height + pad * 0.6f, rightX, img.height + pad * 0.6f + lh), Paint(Paint.FILTER_BITMAP_FLAG)); rightX -= lw + pad * 0.6f }
        val maxW = rightX - pad
        if (minimal) {
            val one = (listOf(d.info.objectName) + ls).filter { it.isNotBlank() }.joinToString("   ·   ")
            val p = Paint(body).apply { color = fg(s); textSize = base * 1.25f }; fitTitle(p, one, maxW)
            cv.drawText(one, pad, img.height + barH / 2f + p.textSize * 0.36f, p)
        } else {
            fitTitle(title, d.info.objectName.ifBlank { "Asztrofotó" }, maxW)
            var y = img.height + pad * 0.8f + title.textSize
            cv.drawText(d.info.objectName.ifBlank { "Asztrofotó" }, pad, y, title)
            for (l in ls) { y += base * 1.45f; cv.drawText(ellipsize(body, l, maxW), pad, y, body) }
        }
        return out
    }

    private fun sideBar(ctx: Context, img: Bitmap, s: CardSettings, d: CardData, wm: WatermarkSettings): Bitmap {
        val H = img.height
        val barW = (img.width * 0.30f).toInt().coerceAtLeast(260)
        val base = barW * 0.055f * s.fontScale
        val (title, body, acc) = paints(s, base)
        val pad = barW * 0.1f
        val out = Bitmap.createBitmap(img.width + barW, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(bg(s)); cv.drawBitmap(img, 0f, 0f, null)
        val x0 = img.width + pad
        cv.drawRect(img.width.toFloat(), 0f, img.width + base * 0.2f, H.toFloat(), acc)
        var y = pad + title.textSize
        // cím több sorban tördelve
        for (line in wrap(title, d.info.objectName.ifBlank { "Asztrofotó" }, barW - 2 * pad)) { cv.drawText(line, x0, y, title); y += title.textSize * 1.12f }
        y += base * 0.6f
        for (l in lines(s, d)) for (w in wrap(body, l, barW - 2 * pad)) { cv.drawText(w, x0, y, body); y += base * 1.4f }
        // alul: logó + QR
        var by = H - pad
        if (s.showQr && s.qrText.isNotBlank()) { val q = (barW * 0.38f).toInt(); qr(s.qrText, q, s.dark)?.let { cv.drawBitmap(it, x0, by - q, null) }; by -= q + pad * 0.5f }
        logo(ctx, s, wm)?.let { lg -> val lw = barW - 2 * pad; val lh = lw * lg.height / lg.width; if (by - lh > y) cv.drawBitmap(lg, null, RectF(x0, by - lh, x0 + lw, by), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return out
    }

    private fun poster(ctx: Context, img: Bitmap, s: CardSettings, d: CardData, wm: WatermarkSettings): Bitmap {
        val W = img.width
        val margin = (W * 0.07f).toInt()
        val base = W * 0.02f * s.fontScale
        val (title, body, acc) = paints(s, base)
        title.textAlign = Paint.Align.CENTER; body.textAlign = Paint.Align.CENTER; title.textSize = base * 2.3f
        val ls = lines(s, d)
        val bottom = (title.textSize * 1.3f + ls.size * base * 1.5f + margin * 1.6f).toInt()
        val out = Bitmap.createBitmap(W + 2 * margin, img.height + margin + bottom, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(bg(s))
        cv.drawBitmap(img, margin.toFloat(), margin.toFloat(), null)
        val cx = out.width / 2f
        var y = img.height + margin + margin * 0.55f + title.textSize
        fitTitle(title, d.info.objectName.ifBlank { "Asztrofotó" }, W * 0.9f)
        cv.drawText(d.info.objectName.ifBlank { "Asztrofotó" }, cx, y, title)
        y += base * 0.7f; cv.drawRect(cx - W * 0.06f, y, cx + W * 0.06f, y + base * 0.16f, acc); y += base * 0.5f
        for (l in ls) { y += base * 1.5f; cv.drawText(ellipsize(body, l, W * 0.9f), cx, y, body) }
        logo(ctx, s, wm)?.let { lg -> val lh = margin * 0.6f; val lw = lh * lg.width / lg.height; cv.drawBitmap(lg, null, RectF(out.width - margin - lw, out.height - margin * 0.2f - lh, out.width - margin.toFloat(), out.height - margin * 0.2f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        if (s.showQr && s.qrText.isNotBlank()) { val q = (margin * 0.7f).toInt(); qr(s.qrText, q, s.dark)?.let { cv.drawBitmap(it, margin.toFloat(), out.height - margin * 0.15f - q, null) } }
        return out
    }

    private fun ellipsize(p: Paint, t: String, maxW: Float): String { var s = t; while (p.measureText(s) > maxW && s.length > 4) s = s.dropLast(2).trimEnd() + "…"; return s }
    private fun wrap(p: Paint, t: String, maxW: Float): List<String> {
        val words = t.split(' '); val out = ArrayList<String>(); var cur = ""
        for (w in words) { val c = if (cur.isBlank()) w else "$cur $w"; if (p.measureText(c) <= maxW || cur.isBlank()) cur = c else { out.add(cur); cur = w } }
        if (cur.isNotBlank()) out.add(cur); return out
    }
}

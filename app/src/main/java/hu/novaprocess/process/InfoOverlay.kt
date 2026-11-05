package hu.novaprocess.process

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.gson.Gson

/** Adatfelirat beállításai (a felirat tartalma a képből/stackből jön, a város a beállításból). */
data class InfoOverlaySettings(
    val enabled: Boolean = false,
    val city: String = "",
    val position: WmPosition = WmPosition.BOTTOM_LEFT,
    val sizePercent: Float = 2.2f,     // a kép rövidebb oldalának %-a (betűméret)
    val opacity: Float = 0.85f,
    val showObject: Boolean = true, val showCity: Boolean = true, val showDate: Boolean = true,
    val showInstrument: Boolean = true, val showIntegration: Boolean = true
)

data class InfoData(val objectName: String, val city: String, val date: String, val instrument: String, val frames: Int, val exposureSec: Float)

object InfoOverlay {
    private const val PREFS = "nova_ui"; private const val KEY = "info_overlay"
    private val gson = Gson()
    fun load(ctx: Context): InfoOverlaySettings = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { runCatching { gson.fromJson(it, InfoOverlaySettings::class.java) }.getOrNull() } ?: InfoOverlaySettings()
    fun save(ctx: Context, s: InfoOverlaySettings) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(s)).apply() }

    fun lines(s: InfoOverlaySettings, d: InfoData): List<String> {
        val out = ArrayList<String>()
        if (s.showObject && d.objectName.isNotBlank()) out.add(d.objectName)
        val l2 = listOfNotNull(if (s.showCity && d.city.isNotBlank()) d.city else null, if (s.showDate && d.date.isNotBlank()) d.date else null).joinToString(" · ")
        if (l2.isNotBlank()) out.add(l2)
        val integ = if (s.showIntegration && d.frames > 0) {
            if (d.exposureSec > 0f) {
                val tot = d.frames * d.exposureSec
                "${d.frames} × ${"%.0f".format(d.exposureSec)} s = " + (if (tot >= 3600) "%.1f óra".format(tot / 3600) else "%.0f perc".format(tot / 60))
            } else "${d.frames} kocka"
        } else null
        val l3 = listOfNotNull(if (s.showInstrument && d.instrument.isNotBlank()) d.instrument else null, integ).joinToString(" · ")
        if (l3.isNotBlank()) out.add(l3)
        return out
    }

    /** A feliratot a bitmapra rajzolja (helyben). */
    fun apply(bmp: Bitmap, s: InfoOverlaySettings, d: InfoData): Bitmap {
        if (!s.enabled) return bmp
        val lines = lines(s, d); if (lines.isEmpty()) return bmp
        val target = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(target)
        val W = target.width; val H = target.height
        val base = minOf(W, H) * s.sizePercent / 100f
        val alpha = (s.opacity.coerceIn(0f, 1f) * 255).toInt()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.alpha = alpha; textSize = base * 1.35f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setShadowLayer(base * 0.12f, 0f, 0f, Color.BLACK) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 225, 225); this.alpha = alpha; textSize = base; typeface = Typeface.create("sans-serif", Typeface.NORMAL); setShadowLayer(base * 0.12f, 0f, 0f, Color.BLACK) }
        val margin = minOf(W, H) * 0.03f
        val gap = base * 0.45f
        val heights = lines.mapIndexed { i, _ -> if (i == 0 && s.showObject && d.objectName.isNotBlank()) title.textSize else body.textSize }
        val totalH = heights.sum() + gap * (lines.size - 1)
        val widths = lines.mapIndexed { i, l -> (if (i == 0 && s.showObject && d.objectName.isNotBlank()) title else body).measureText(l) }
        val maxW = widths.max()
        val right = s.position == WmPosition.TOP_RIGHT || s.position == WmPosition.BOTTOM_RIGHT
        val top = s.position == WmPosition.TOP_LEFT || s.position == WmPosition.TOP_RIGHT
        var y = if (top) margin else H - margin - totalH
        for ((i, l) in lines.withIndex()) {
            val p = if (i == 0 && s.showObject && d.objectName.isNotBlank()) title else body
            val x = if (s.position == WmPosition.CENTER) (W - widths[i]) / 2f else if (right) W - margin - widths[i] else margin
            y += heights[i]
            cv.drawText(l, x, y, p)
            y += gap
        }
        return target
    }
}

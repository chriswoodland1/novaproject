package hu.novaprocess.process

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import com.google.gson.Gson
import java.io.File

enum class WmMode { OFF, TEXT, IMAGE }
enum class WmPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }
enum class WmFont { SANS, SERIF, SCRIPT, MONO }

/** Vízjel-beállítások (az alkalmazásban tárolva, minden exportra érvényes). */
data class WatermarkSettings(
    val mode: WmMode = WmMode.OFF,
    val text: String = "",
    val font: WmFont = WmFont.SCRIPT,
    val imagePath: String? = null,       // app-belső másolat a kiválasztott logóról
    val position: WmPosition = WmPosition.BOTTOM_RIGHT,
    val sizePercent: Float = 22f,        // a kép szélességének %-a (kép) ill. a szöveg szélessége (%)
    val opacity: Float = 0.7f,           // 0..1
    val marginPercent: Float = 3f,       // a kép rövidebb oldalának %-a
    val white: Boolean = true,           // szöveg színe fehér/fekete
    val shadow: Boolean = true           // vékony árnyék a láthatóságért
)

object Watermark {
    private const val PREFS = "nova_ui"; private const val KEY = "watermark"
    private val gson = Gson()

    fun load(ctx: Context): WatermarkSettings =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { runCatching { gson.fromJson(it, WatermarkSettings::class.java) }.getOrNull() } ?: WatermarkSettings()

    fun save(ctx: Context, s: WatermarkSettings) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(s)).apply() }

    /** A kiválasztott logót (PNG átlátszósággal is) bemásolja az app tárhelyére. */
    fun importImage(ctx: Context, uri: Uri): String? = runCatching {
        val f = File(ctx.filesDir, "watermark_logo.png")
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: return null
        // max 1600 px széles, hogy ne egyen memóriát
        val scaled = if (bmp.width > 1600) Bitmap.createScaledBitmap(bmp, 1600, bmp.height * 1600 / bmp.width, true) else bmp
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    }.getOrNull()

    private fun typeface(f: WmFont): Typeface = when (f) {
        WmFont.SANS -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        WmFont.SERIF -> Typeface.create("serif", Typeface.ITALIC)
        WmFont.SCRIPT -> Typeface.create("cursive", Typeface.NORMAL)   // aláírás-stílus (Dancing Script az Androidon)
        WmFont.MONO -> Typeface.MONOSPACE
    }

    /** A vízjelet a bitmapra rajzolja (helyben), és visszaadja ugyanazt a bitmapot. */
    fun apply(ctx: Context, bmp: Bitmap, s: WatermarkSettings = load(ctx)): Bitmap {
        if (s.mode == WmMode.OFF) return bmp
        val target = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(target)
        val W = target.width; val H = target.height
        val margin = (min(W, H) * s.marginPercent / 100f)
        val alpha = (s.opacity.coerceIn(0f, 1f) * 255).toInt()

        if (s.mode == WmMode.IMAGE && s.imagePath != null && File(s.imagePath).exists()) {
            val logo = BitmapFactory.decodeFile(s.imagePath) ?: return target
            val lw = (W * s.sizePercent / 100f).coerceAtLeast(8f)
            val lh = lw * logo.height / logo.width
            val (x, y) = place(s.position, W, H, lw, lh, margin)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
            cv.drawBitmap(logo, null, android.graphics.RectF(x, y, x + lw, y + lh), paint)
            logo.recycle()
        } else if (s.mode == WmMode.TEXT && s.text.isNotBlank()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = typeface(s.font); color = if (s.white) Color.WHITE else Color.BLACK; this.alpha = alpha
            }
            // betűméret úgy, hogy a szöveg szélessége ≈ sizePercent
            paint.textSize = 100f
            val bounds = Rect(); paint.getTextBounds(s.text, 0, s.text.length, bounds)
            val targetW = W * s.sizePercent / 100f
            paint.textSize = (100f * targetW / bounds.width().coerceAtLeast(1)).coerceIn(10f, H / 4f)
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            val tw = paint.measureText(s.text); val th = bounds.height().toFloat()
            val (x, y) = place(s.position, W, H, tw, th, margin)
            if (s.shadow) {
                val sh = Paint(paint).apply { color = if (s.white) Color.BLACK else Color.WHITE; this.alpha = (alpha * 0.6f).toInt(); maskFilter = android.graphics.BlurMaskFilter(paint.textSize * 0.06f + 1f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
                cv.drawText(s.text, x + paint.textSize * 0.03f, y + th + paint.textSize * 0.03f, sh)
            }
            cv.drawText(s.text, x, y + th, paint)
        }
        return target
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    private fun place(pos: WmPosition, W: Int, H: Int, w: Float, h: Float, m: Float): Pair<Float, Float> = when (pos) {
        WmPosition.TOP_LEFT -> Pair(m, m)
        WmPosition.TOP_RIGHT -> Pair(W - w - m, m)
        WmPosition.BOTTOM_LEFT -> Pair(m, H - h - m)
        WmPosition.BOTTOM_RIGHT -> Pair(W - w - m, H - h - m)
        WmPosition.CENTER -> Pair((W - w) / 2f, (H - h) / 2f)
    }
}

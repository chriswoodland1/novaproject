package hu.novaprocess.solve

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Annotation(val names: List<String>, val x: Float, val y: Float, val radius: Float, val type: String)

data class SolveResult(
    val ra: Double, val dec: Double, val radiusDeg: Double, val pixScale: Double, val orientation: Double, val parity: Int,
    val annotations: List<Annotation>, val width: Int, val height: Int   // a megoldott (teljes) kép mérete, az annotációk ebben a rendszerben
)

/**
 * Online plate solving a nova.astrometry.net API-val (ingyenes kulcs: nova.astrometry.net → Profile → API key).
 * Feltöltünk egy lekicsinyített JPEG-et, megvárjuk a megoldást, majd az annotációkat visszaskálázzuk a teljes képre.
 */
object PlateSolver {
    private const val BASE = "https://nova.astrometry.net/api"
    private const val PREFS = "nova_ui"; private const val KEY = "astrometry_key"

    fun apiKey(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    fun setApiKey(ctx: Context, k: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, k.trim()).apply() }

    suspend fun solve(ctx: Context, preview: Bitmap, fullW: Int, fullH: Int, onStatus: (String) -> Unit): SolveResult = withContext(Dispatchers.IO) {
        val key = apiKey(ctx); require(key.isNotBlank()) { "Nincs astrometry.net API-kulcs (Beállítások)." }
        onStatus("Bejelentkezés…")
        val login = post("$BASE/login", "request-json=" + URLEncoder.encode(JSONObject().put("apikey", key).toString(), "UTF-8"))
        val session = login.optString("session"); require(session.isNotBlank()) { "Sikertelen bejelentkezés: ${login.optString("errormessage", login.toString())}" }

        // feltöltendő kép: max 1600 px, JPEG 88
        val scale = minOf(1f, 1600f / maxOf(preview.width, preview.height))
        val up = if (scale < 1f) Bitmap.createScaledBitmap(preview, (preview.width * scale).toInt(), (preview.height * scale).toInt(), true) else preview
        val bytes = ByteArrayOutputStream().also { up.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        onStatus("Feltöltés (${bytes.size / 1024} kB)…")
        val req = JSONObject().put("session", session).put("publicly_visible", "n").put("allow_modifications", "d").put("allow_commercial_use", "d")
            .put("scale_units", "degwidth").put("scale_lower", 0.3).put("scale_upper", 5.0)   // Seestar S30/S50 látómező ~0.7–2.5°
        val upload = multipart("$BASE/upload", req.toString(), bytes)
        val subid = upload.optLong("subid", -1L); require(subid > 0) { "Feltöltési hiba: ${upload.optString("errormessage", upload.toString())}" }

        // várakozás a jobra
        var jobId = -1L
        for (i in 0 until 60) {
            delay(3000)
            val sub = get("$BASE/submissions/$subid")
            val jobs = sub.optJSONArray("jobs")
            if (jobs != null && jobs.length() > 0 && !jobs.isNull(0)) { jobId = jobs.getLong(0); break }
            onStatus("Sorban áll… (${(i + 1) * 3} s)")
        }
        require(jobId > 0) { "A szerver nem indította el a megoldást (időtúllépés)." }
        for (i in 0 until 100) {
            delay(3000)
            val st = get("$BASE/jobs/$jobId").optString("status")
            onStatus("Megoldás folyamatban… (${(i + 1) * 3} s)")
            if (st == "success") break
            if (st == "failure") throw IllegalStateException("A plate solve nem sikerült (kevés csillag vagy rossz skála).")
        }
        onStatus("Annotációk letöltése…")
        val cal = get("$BASE/jobs/$jobId/calibration")
        val ann = get("$BASE/jobs/$jobId/annotations").optJSONArray("annotations")
        val sx = fullW.toFloat() / up.width; val sy = fullH.toFloat() / up.height
        val list = ArrayList<Annotation>()
        if (ann != null) for (i in 0 until ann.length()) {
            val a = ann.getJSONObject(i)
            val names = a.optJSONArray("names")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
            list.add(Annotation(names, a.optDouble("pixelx").toFloat() * sx, a.optDouble("pixely").toFloat() * sy, a.optDouble("radius", 20.0).toFloat() * sx, a.optString("type")))
        }
        SolveResult(cal.optDouble("ra"), cal.optDouble("dec"), cal.optDouble("radius"), cal.optDouble("pixscale") / scale, cal.optDouble("orientation"), cal.optInt("parity"), list, fullW, fullH)
    }

    fun formatRa(ra: Double): String { val h = ra / 15.0; val hh = h.toInt(); val m = (h - hh) * 60; val mm = m.toInt(); val ss = (m - mm) * 60; return "%02dh %02dm %04.1fs".format(hh, mm, ss) }
    fun formatDec(dec: Double): String { val sgn = if (dec < 0) "−" else "+"; val d = kotlin.math.abs(dec); val dd = d.toInt(); val m = (d - dd) * 60; val mm = m.toInt(); val ss = (m - mm) * 60; return "%s%02d° %02d′ %02.0f″".format(sgn, dd, mm, ss) }

    private fun post(url: String, form: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000; setRequestProperty("Content-Type", "application/x-www-form-urlencoded") }
        c.outputStream.use { it.write(form.toByteArray()) }
        return JSONObject(c.inputStream.bufferedReader().readText()).also { c.disconnect() }
    }
    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
        return JSONObject(c.inputStream.bufferedReader().readText()).also { c.disconnect() }
    }
    private fun multipart(url: String, json: String, file: ByteArray): JSONObject {
        val boundary = "----NovaProcess${System.currentTimeMillis()}"
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000; setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary") }
        DataOutputStream(c.outputStream).use { o ->
            o.writeBytes("--$boundary\r\nContent-Type: text/plain\r\nMIME-Version: 1.0\r\nContent-disposition: form-data; name=\"request-json\"\r\n\r\n$json\r\n")
            o.writeBytes("--$boundary\r\nContent-Type: application/octet-stream\r\nMIME-Version: 1.0\r\nContent-disposition: form-data; name=\"file\"; filename=\"nova.jpg\"\r\n\r\n")
            o.write(file); o.writeBytes("\r\n--$boundary--\r\n")
        }
        return JSONObject(c.inputStream.bufferedReader().readText()).also { c.disconnect() }
    }
}

package hu.novaprocess.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Letölthető / beépített MI-modellek.
 *  - GraXpert (Steffen Hirtle, graxpert.com): MI háttér-kivonás és MI-zajszűrés – modellek CC BY-NC-SA 4.0,
 *    a Hugging Face tükörből (jromanghf/graxpert-models) töltjük le első használatkor; a fájlnevet a tároló
 *    fájllistájából keressük (név-minta), nem égetjük be az útvonalat.
 *  - AstroSleuth FAST (Aveygo, GPL-2): 4× részletjavító/felskálázó, az app része (assets/models).
 */
object AiModels {
    enum class Model(val label: String, val repo: String?, val pattern: Regex, val approxMb: Int, val license: String, val asset: String? = null) {
        GRAXPERT_BGE("GraXpert MI háttér-kivonás", "jromanghf/graxpert-models", Regex("graxpert-background-[\\d.]+\\.onnx$"), 218, "CC BY-NC-SA 4.0 · GraXpert"),
        GRAXPERT_DENOISE("GraXpert MI-zajszűrés", "jromanghf/graxpert-models", Regex("graxpert-denoise-[\\d.]+\\.onnx$"), 478, "CC BY-NC-SA 4.0 · GraXpert"),
        ASTROSLEUTH("AstroSleuth FAST részletjavító", null, Regex("AstroSleuthFAST\\.onnx$"), 5, "GPL-2 · AstroSleuth (Aveygo)", "models/AstroSleuthFAST.onnx")
    }

    data class DlState(val model: Model? = null, val done: Long = 0, val total: Long = 0, val error: String? = null)
    val download = MutableStateFlow(DlState())
    val installed = MutableStateFlow<Set<Model>>(emptySet())

    private lateinit var dir: File
    private lateinit var appCtx: Context
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<Model, OrtSession>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        dir = File(ctx.filesDir, "models").apply { mkdirs() }
        refresh()
    }
    fun refresh() { installed.value = Model.values().filter { fileOf(it) != null }.toSet() }
    fun isReady(m: Model) = m in installed.value

    /** A modell helyi fájlja (beépítettnél a gyorsítótárba másolva). */
    fun fileOf(m: Model): File? {
        if (!::dir.isInitialized) return null
        if (m.asset != null) {
            val f = File(dir, m.asset.substringAfterLast('/'))
            if (!f.exists()) runCatching { appCtx.assets.open(m.asset).use { i -> f.outputStream().use { i.copyTo(it) } } }
            return f.takeIf { it.exists() && it.length() > 0 }
        }
        return dir.listFiles()?.firstOrNull { m.pattern.containsMatchIn(it.name) && !it.name.endsWith(".part") }
    }
    fun sizeOnDisk(m: Model): Long = fileOf(m)?.length() ?: 0L

    fun delete(m: Model) {
        synchronized(sessions) { sessions.remove(m)?.close() }
        if (m.asset == null) dir.listFiles()?.filter { m.pattern.containsMatchIn(it.name) || it.name.endsWith(".part") }?.forEach { it.delete() }
        refresh()
    }

    /** Letöltés a Hugging Face-ről: fájllista (API) → minta szerinti fájl → resolve/main. Folytatható (.part). */
    suspend fun fetch(m: Model) = withContext(Dispatchers.IO) {
        val repo = m.repo ?: return@withContext
        download.value = DlState(m)
        try {
            val list = JSONArray(httpText("https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
            var path: String? = null; var size = 0L
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i); val p = o.optString("path")
                if (o.optString("type") == "file" && m.pattern.containsMatchIn(p)) {
                    if (path == null || p > path) { path = p; size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size") }
                }
            }
            requireNotNull(path) { "A modell nem található a tárolóban ($repo)" }
            val name = path.substringAfterLast('/')
            val part = File(dir, "$name.part"); val target = File(dir, name)
            var have = if (part.exists()) part.length() else 0L
            val c = (URL("https://huggingface.co/$repo/resolve/main/$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            if (c.responseCode == 200) have = 0L else require(c.responseCode == 206) { "HTTP ${c.responseCode}" }
            val total = if (size > 0) size else have + c.contentLengthLong
            c.inputStream.use { ins -> java.io.FileOutputStream(part, have > 0).use { os ->
                val buf = ByteArray(1 shl 16); var done = have; var last = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = ins.read(buf); if (n < 0) break
                    os.write(buf, 0, n); done += n
                    if (done - last > 1 shl 20) { last = done; download.value = DlState(m, done, total) }
                }
            } }
            require(total <= 0 || part.length() == total) { "Hiányos letöltés (${part.length()} / $total bájt)" }
            target.delete(); part.renameTo(target)
            download.value = DlState(); refresh()
        } catch (e: kotlinx.coroutines.CancellationException) { download.value = DlState(); throw e }
        catch (e: Exception) { download.value = DlState(m, error = e.message ?: e.javaClass.simpleName) }
    }
    private fun httpText(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15000; readTimeout = 30000
        require(responseCode == 200) { "HTTP $responseCode ($url)" }
        inputStream.bufferedReader().readText()
    }

    /** Végrehajtó: sima CPU, XNNPACK (ARM-re optimalizált CPU-kernelek), NNAPI (a telefon NPU/GPU-ja, FP16). */
    enum class Provider(val label: String) { CPU("CPU"), XNNPACK("XNNPACK (optimalizált CPU)"), NNAPI("NNAPI (NPU/GPU)") }
    private val sessionProvider = HashMap<Model, Provider>()
    private val prefs get() = appCtx.getSharedPreferences("ai_models", 0)
    /** A modellhez mért leggyorsabb végrehajtó (null: még nincs mérve). */
    fun bestProvider(m: Model): Provider? = prefs.getString("prov:${m.name}", null)?.let { runCatching { Provider.valueOf(it) }.getOrNull() }
    fun tileMs(m: Model): Long = prefs.getLong("ms:${m.name}", -1)
    fun saveBenchmark(m: Model, p: Provider, ms: Long) { prefs.edit().putString("prov:${m.name}", p.name).putLong("ms:${m.name}", ms).apply() }
    fun clearBenchmark(m: Model) { prefs.edit().remove("prov:${m.name}").remove("ms:${m.name}").apply() }

    /** ONNX munkamenet (gyorsítótárazva) a kért / mért végrehajtóval. */
    fun session(m: Model, provider: Provider? = null): OrtSession = synchronized(sessions) {
        val want = provider ?: bestProvider(m) ?: Provider.CPU
        sessions[m]?.let { if (sessionProvider[m] == want) return it; it.close(); sessions.remove(m) }
        val f = requireNotNull(fileOf(m)) { "${m.label}: a modell nincs letöltve" }
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (want) {
                Provider.CPU -> setIntraOpNumThreads(cores)
                Provider.XNNPACK -> { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to cores.toString())) }
                Provider.NNAPI -> { setIntraOpNumThreads(cores); addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16)) }
            }
        }
        env.createSession(f.path, opts).also { sessions[m] = it; sessionProvider[m] = want }
    }
    fun release(m: Model) = synchronized(sessions) { sessions.remove(m)?.close() }
}

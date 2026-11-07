package hu.novaprocess.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.image.TiffWriter
import hu.novaprocess.library.Library
import hu.novaprocess.planetary.Planetary
import hu.novaprocess.planetary.PlanetaryOptions
import hu.novaprocess.planetary.PlanetaryProgress
import hu.novaprocess.library.LibraryEntry
import hu.novaprocess.process.Presets
import hu.novaprocess.process.AutoEditor
import hu.novaprocess.process.Watermark
import hu.novaprocess.work.ProcessingBus
import java.io.File
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import hu.novaprocess.stack.FrameAnalyzer
import hu.novaprocess.stack.FrameInfo
import hu.novaprocess.stack.StackOptions
import hu.novaprocess.stack.StackProgress
import hu.novaprocess.stack.Stacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class ExportFormat { JPEG, PNG, TIFF16, FRAMED_JPEG, FITS32 }
enum class ExportSize(val label: String, val w: Int, val h: Int) {
    ORIGINAL("Eredeti", 0, 0), IG_SQUARE("Instagram négyzet 1080", 1080, 1080), IG_PORTRAIT("Instagram portré 1080×1350", 1080, 1350),
    ASTROBIN("Astrobin 1920", 1920, 0), UHD("4K 3840", 3840, 0)
}

data class StackUiState(
    val running: Boolean = false,
    val analyzing: Boolean = false,
    val analyzed: Int = 0,
    val analyzeTotal: Int = 0,
    val progress: StackProgress? = null,
    val log: List<String> = emptyList(),
    val error: String? = null
)

data class EditorUiState(
    val hasImage: Boolean = false,
    val sourceInfo: String = "",
    val preview: Bitmap? = null,
    val originalPreview: Bitmap? = null,   // "előtte": csak autostretch
    val histogram: Array<IntArray>? = null,
    val stats: String = "",
    val processing: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("nova_ui", Context.MODE_PRIVATE)
    val nightMode = MutableStateFlow(prefs.getBoolean("night", false))
    fun setNight(v: Boolean) { nightMode.value = v; prefs.edit().putBoolean("night", v).apply() }

    // --- bemenet ---
    val frames = MutableStateFlow<List<FrameInfo>>(emptyList())
    val stackOptions = MutableStateFlow(StackOptions())
    val stackState = MutableStateFlow(StackUiState())

    // --- szerkesztés ---
    private var source: FloatImage? = null
    private var previewSource: FloatImage? = null
    val params = MutableStateFlow(ProcessParams())
    private val undo = ArrayDeque<ProcessParams>()
    private val redo = ArrayDeque<ProcessParams>()
    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui
    private var previewJob: Job? = null
    private var stackJob: Job? = null

    // ---------------------------------------------------------------- kockák hozzáadása + elemzés
    fun addFrames(uris: List<Uri>, replace: Boolean = false) {
        if (replace) { frames.value = emptyList(); stackState.value = StackUiState() }
        val existing = frames.value.map { it.uri }.toSet()
        val new = uris.filter { it !in existing }
        if (new.isEmpty()) return
        val app = getApplication<Application>()
        // azonnal megjelenő helyőrzők, hogy látszódjon: megérkeztek a fájlok
        val placeholders = new.map { u -> FrameInfo(u, runCatching { ImageLoader.displayName(app, u) }.getOrDefault("kép"), 0f, 0, 0f, 0f, null, false, "elemzés…") }
        frames.value = frames.value + placeholders
        viewModelScope.launch {
            stackState.update { it.copy(analyzing = true, analyzed = 0, analyzeTotal = new.size, error = null) }
            val sem = Semaphore(1)
            withContext(Dispatchers.Default) {
                new.map { uri ->
                    async {
                        sem.withPermit {
                            val info = try { FrameAnalyzer.analyze(app, uri) }
                            catch (e: Throwable) { FrameInfo(uri, ImageLoader.displayName(app, uri), 0f, 0, 0f, 0f, null, true, "nem olvasható: ${e.message?.take(40) ?: e.javaClass.simpleName}") }
                            // helyőrző cseréje az eredményre, azonnal
                            frames.update { list -> list.map { if (it.uri == uri) info else it } }
                            stackState.update { it.copy(analyzed = it.analyzed + 1) }
                        }
                    }
                }.awaitAll()
            }
            frames.value = FrameAnalyzer.autoReject(frames.value)
            stackState.update { it.copy(analyzing = false) }
        }
    }

    fun toggleReject(uri: Uri) {
        frames.value = frames.value.map { if (it.uri == uri) it.copy(rejected = !it.rejected, reason = if (it.rejected) "kézzel visszavéve" else "kézzel kizárva") else it }
    }

    fun unrejectAll() { frames.value = frames.value.map { if (it.rejected && !it.reason.startsWith("nem olvasható")) it.copy(rejected = false, reason = "kézzel visszavéve") else it } }

    fun clearFrames() { frames.value = emptyList(); stackState.value = StackUiState() }

    // ---------------------------------------------------------------- stack
    /** A stack magja (pipeline is ezt hívja). Visszaadja az eredményt; a forrást beállítja. */
    private suspend fun stackCore(uris: List<Uri>, progress: (StackProgress) -> Unit): hu.novaprocess.stack.StackResult {
        val o = stackOptions.value
        val heapMb = hu.novaprocess.NovaApp.maxHeapMb()
        val wantFull = o.drizzle == 1 && (o.resolution == hu.novaprocess.stack.Resolution.FULL || (o.resolution == hu.novaprocess.stack.Resolution.AUTO && heapMb >= hu.novaprocess.stack.TiledStacker.MIN_HEAP_MB))
        val res = if (wantFull) {
            try { hu.novaprocess.stack.TiledStacker.stack(getApplication<Application>(), uris, o, progress) }
            catch (e: OutOfMemoryError) {
                System.gc()
                stackState.update { it.copy(log = it.log + "Teljes felbontás nem fért a memóriába – újra 2×2 binninggel") }
                Stacker.stack(getApplication<Application>(), uris, o.copy(binning = 2), progress)
            }
        } else Stacker.stack(getApplication<Application>(), uris, o.copy(binning = if (o.resolution == hu.novaprocess.stack.Resolution.BIN2) 2 else o.binning), progress)
        displayReferred = false
        setSource(res.image, "Stack · ${res.used} kocka · ${res.image.width}×${res.image.height}")
        captionFromFrames(res.used)
        lastFrameCount = res.used
        solve.value = SolveState()
        runCatching {
            setSkyFromMeta(frames.value.firstOrNull { !it.rejected }?.meta, res.image.width, res.image.height)
            if (sky.value.field == null) hu.novaprocess.catalog.Catalog.find(getApplication(), caption.value.title)?.let { o -> caption.value = caption.value.copy(title = o.title) }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val thumb = Processor.process(res.image.downscale(320), ProcessParams()).toBitmap()
                val cap = caption.value
                val entry = Library.saveStack(getApplication<Application>(), res.image, thumb, cap.title, res.used, (res.used * cap.exposure).toInt(), ProcessParams())
                currentEntryId = entry.id
                libraryVersion.value++
            }
        }
        return res
    }

    fun startStack() {
        val uris = frames.value.filter { !it.rejected }.map { it.uri }
        if (uris.isEmpty() || stackState.value.running) return
        stackJob = viewModelScope.launch {
            stackState.update { it.copy(running = true, error = null, log = emptyList()) }
            ProcessingBus.begin(getApplication(), "Stackelés")
            try {
                val res = stackCore(uris) { p ->
                    stackState.update { it.copy(progress = p) }
                    ProcessingBus.progress("Stackelés · ${p.phase}", "${p.current} / ${p.total} ${p.detail}", if (p.total > 0) p.current * 100 / p.total else 0)
                }
                stackState.update { it.copy(running = false, log = res.log) }
                ProcessingBus.done("Stack kész ✓", "${res.used} kocka · ${res.image.width}×${res.image.height} · koppints a szerkesztéshez")
            } catch (e: kotlinx.coroutines.CancellationException) {
                stackState.update { it.copy(running = false, error = "Megszakítva") }
                ProcessingBus.failed("Stack megszakítva", "")
            } catch (e: Throwable) {
                System.gc()
                val msg = if (e is OutOfMemoryError) "Elfogyott a memória (${hu.novaprocess.NovaApp.maxHeapMb()} MB keret). Kapcsold be a 2×2 binninget vagy kevesebb kockával próbáld." else (e.message ?: e.toString())
                stackState.update { it.copy(running = false, error = msg) }
                ProcessingBus.failed("Stack sikertelen", msg)
            }
        }
    }

    fun cancelStack() { stackJob?.cancel(); stackState.update { it.copy(running = false, error = "Megszakítva") } }

    // ---------------------------------------------------------------- egyetlen kép
    fun openSingle(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(processing = true, message = null) }
            try {
                val name = ImageLoader.displayName(getApplication<Application>(), uri)
                val display = ImageLoader.isDisplayReferred(name)
                var meta: ImageLoader.Meta? = null
                val img = withContext(Dispatchers.IO) { if (display) ImageLoader.loadDisplayReferred(getApplication<Application>(), uri, name) else ImageLoader.loadWithMeta(getApplication<Application>(), uri, name).let { meta = it.second; it.first } }
                displayReferred = display
                setSource(img, "$name · ${img.width}×${img.height}" + (if (display) " · kész kép" else ""))
                caption.value = Caption(title = meta?.objectName ?: name.substringBeforeLast('.'), date = meta?.dateObs?.take(10)?.replace('-', '.') ?: java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date()), exposure = meta?.exposure ?: 0f)
                solve.value = SolveState()
                runCatching { setSkyFromMeta(meta, img.width, img.height) }
                if (meta == null) hu.novaprocess.catalog.Catalog.find(getApplication(), name.substringBeforeLast('.'))?.let { o -> caption.value = caption.value.copy(title = o.title) }
                launch(Dispatchers.IO) {
                    runCatching {
                        val thumb = Processor.process(img.downscale(320), ProcessParams()).toBitmap()
                        currentEntryId = Library.saveSingle(getApplication<Application>(), uri.toString(), name, img.width, img.height, thumb, ProcessParams()).id
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(processing = false, message = "Nem sikerült megnyitni: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- plate solving (astrometry.net)
    data class SolveState(val running: Boolean = false, val status: String = "", val result: hu.novaprocess.solve.SolveResult? = null, val error: String? = null, val show: Boolean = true)
    val solve = MutableStateFlow(SolveState())
    fun runSolve() {
        val bmp = _ui.value.preview ?: return
        val src = source ?: return
        if (solve.value.running) return
        viewModelScope.launch {
            solve.value = SolveState(running = true, status = "Indítás…")
            try {
                val r = hu.novaprocess.solve.PlateSolver.solve(getApplication(), bmp, src.width, src.height) { st -> solve.update { it.copy(status = st) } }
                solve.value = SolveState(result = r)
                runCatching { setSkyField(hu.novaprocess.catalog.SkyField.calibrate(getApplication(), hu.novaprocess.catalog.SkyField.fromSolve(r), r)) }
                // felirat/objektumnév frissítése a legnagyobb objektumból
                r.annotations.filter { it.type != "star" && it.type != "hd" }.maxByOrNull { it.radius }?.names?.firstOrNull()?.let { n ->
                    if (caption.value.title.isBlank() || caption.value.title.startsWith("Stack") || caption.value.title.startsWith("Light")) caption.value = caption.value.copy(title = n)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { solve.value = SolveState(error = e.message ?: e.javaClass.simpleName) }
        }
    }
    fun toggleAnnotations() { solve.update { it.copy(show = !it.show) } }

    /** Annotációk rárajzolása egy exportált bitmapra (a forrás méretéhez skálázva). */
    fun annotate(bmp: Bitmap): Bitmap {
        val r = solve.value.result ?: return bmp
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = android.graphics.Canvas(out)
        val sx = out.width.toFloat() / r.width; val sy = out.height.toFloat() / r.height
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = (out.width / 900f).coerceAtLeast(1.5f); color = android.graphics.Color.argb(200, 143, 227, 168) }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(230, 240, 240, 240); textSize = (out.width / 70f).coerceAtLeast(12f); setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) }
        for (a in r.annotations) {
            if (a.type == "star" && a.names.firstOrNull()?.startsWith("HD") == true) continue
            val x = a.x * sx; val y = a.y * sy; val rad = (a.radius * sx).coerceAtLeast(text.textSize)
            cv.drawCircle(x, y, rad, stroke)
            cv.drawText(a.names.firstOrNull() ?: "", x + rad + 4f, y + text.textSize / 3f, text)
        }
        return out
    }

    // ---------------------------------------------------------------- háttér-mintapontok (RBF)
    fun addBgSample(nx: Float, ny: Float) { commitParams { it.copy(bgSamples = it.bgSamples + listOf(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)), backgroundExtraction = true, backgroundDegree = 3) } }
    fun clearBgSamples() { commitParams { it.copy(bgSamples = emptyList()) } }

    // ---------------------------------------------------------------- pipeline + sor
    data class PipelineJob(val name: String, val uris: List<Uri>)
    data class PipelineState(val jobs: List<PipelineJob> = emptyList(), val running: Boolean = false, val current: String = "", val detail: String = "", val done: List<String> = emptyList(), val error: String? = null)
    val pipeline = MutableStateFlow(PipelineState())
    private val pipelineOutputs = ArrayList<Uri>()
    fun enqueueCurrentFrames() {
        val uris = frames.value.filter { !it.rejected }.map { it.uri }
        if (uris.isEmpty()) return
        val first = frames.value.first { !it.rejected }
        val name = first.objectName ?: first.name.substringBefore("_sub").substringBefore("-sub").substringBeforeLast('.')
        pipeline.update { it.copy(jobs = it.jobs + PipelineJob("$name (${uris.size})", uris)) }
        frames.value = emptyList()
    }
    fun removeJob(i: Int) { pipeline.update { it.copy(jobs = it.jobs.filterIndexed { k, _ -> k != i }) } }

    /**
     * Egy (szülő)mappa → objektumonként külön munka: minden olyan almappa, amiben kockák vannak, egy munka lesz
     * a mappa nevével (a `_sub` toldalék nélkül). Típus-prioritás mappánként: FITS > .bin > TIFF > JPEG/PNG.
     * Visszaadja a felvett munkák számát.
     */
    suspend fun enqueueFolder(tree: Uri): Int = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext 0
        // csoport = egy konkrét mappa (nem összevonva); a név a mappa neve
        data class Grp(val folderName: String, val isSub: Boolean, val files: List<androidx.documentfile.provider.DocumentFile>)
        val groups = ArrayList<Grp>()
        fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int) {
            val files = d.listFiles()
            val imgs = files.filter { !it.isDirectory }
            val n = (d.name ?: "mappa").trim()
            val isSub = n.lowercase().endsWith("_sub") || n.lowercase().endsWith("-sub") || n.lowercase() == "sub"
            if (imgs.isNotEmpty()) groups.add(Grp(n, isSub, imgs))
            if (depth < 3) files.filter { it.isDirectory }.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        fun base(n: String) = n.removeSuffix("_sub").removeSuffix("-sub").removeSuffix("_SUB").trim()
        // ha egy objektumhoz van _sub mappa, a sima (kész stacket tartalmazó) mappát kihagyjuk
        val subBases = groups.filter { it.isSub }.map { base(it.folderName).lowercase() }.toSet()
        val newJobs = ArrayList<PipelineJob>()
        for (g in groups) {
            if (!g.isSub && base(g.folderName).lowercase() in subBases) continue
            fun ext(f: androidx.documentfile.provider.DocumentFile) = (f.name ?: "").substringAfterLast('.', "").lowercase()
            fun isThumb(f: androidx.documentfile.provider.DocumentFile) = (f.name ?: "").lowercase().contains("_thn")
            fun isStacked(f: androidx.documentfile.provider.DocumentFile) = (f.name ?: "").lowercase().contains("stacked")
            val fits = g.files.filter { ext(it) in setOf("fit", "fits") && !isStacked(it) }
            val bins = g.files.filter { ext(it) in setOf("bin", "raw") && hu.novaprocess.image.BinReader.inferLayout(it.length(), hu.novaprocess.image.BinReader.dimsOverride(ctx)) != null }
            val tifs = g.files.filter { ext(it) in setOf("tif", "tiff") }
            val jpgs = g.files.filter { ext(it) in setOf("jpg", "jpeg", "png") && !isThumb(it) && !isStacked(it) }
            val (pick, type) = when {
                fits.size >= 2 -> fits to "FITS"
                bins.size >= 2 -> bins to "bin"
                tifs.size >= 2 -> tifs to "TIFF"
                jpgs.size >= 2 -> jpgs to "JPEG"
                else -> continue
            }
            newJobs.add(PipelineJob("${base(g.folderName)} · $type (${pick.size})", pick.map { it.uri }))
        }
        pipeline.update { st -> st.copy(jobs = st.jobs + newJobs.filter { nj -> st.jobs.none { it.name == nj.name } }) }
        newJobs.size
    }

    /** Minden sorban álló munkára: elemzés → stack → automata szerkesztés → export (JPEG + keretes) a kiválasztott mappába. */
    fun runPipeline(outputTree: Uri, framed: Boolean = true) {
        if (pipeline.value.running || pipeline.value.jobs.isEmpty()) return
        viewModelScope.launch {
            pipeline.update { it.copy(running = true, error = null, done = emptyList()) }
            val ctx = getApplication<Application>()
            ProcessingBus.begin(ctx, "Pipeline")
            val totalJobs = pipeline.value.jobs.size
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, outputTree)
            try {
                while (pipeline.value.jobs.isNotEmpty()) {
                    val job = pipeline.value.jobs.first()
                    pipeline.update { it.copy(current = job.name, detail = "Kockák elemzése…") }
                    // elemzés + automatikus kizárás (csík, életlen)
                    val infos = ArrayList<FrameInfo>()
                    for ((i, u) in job.uris.withIndex()) {
                        pipeline.update { it.copy(detail = "Elemzés ${i + 1}/${job.uris.size}") }
                        runCatching { infos.add(withContext(Dispatchers.Default) { FrameAnalyzer.analyze(ctx, u) }) }
                    }
                    val good = FrameAnalyzer.autoReject(infos).filter { !it.rejected }
                    frames.value = FrameAnalyzer.autoReject(infos)
                    if (good.isEmpty()) { pipeline.update { it.copy(done = it.done + "${job.name}: nincs használható kocka", jobs = it.jobs.drop(1)) }; continue }
                    val jobIdx = totalJobs - pipeline.value.jobs.size
                    val res = stackCore(good.map { it.uri }) { p ->
                        pipeline.update { it.copy(detail = "${p.phase} ${p.current}/${p.total}") }
                        ProcessingBus.progress("Pipeline ${jobIdx + 1}/$totalJobs · ${job.name}", "${p.phase} ${p.current}/${p.total}", if (p.total > 0) p.current * 100 / p.total else 0)
                    }
                    pipeline.update { it.copy(detail = "Automata szerkesztés…") }
                    val auto = withContext(Dispatchers.Default) { AutoEditor.run(previewSource!!, caption.value.title.ifBlank { null }, res.used, false, primaryObject?.let { hu.novaprocess.catalog.Catalog.autoType(it.type) }) }
                    params.value = auto.params
                    persistRecipe()
                    pipeline.update { it.copy(detail = "Export…") }
                    val base = caption.value.title.ifBlank { "stack" }.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                    withContext(Dispatchers.Default) {
                        val out = Processor.process(source!!, params.value)
                        val bmp = Watermark.apply(ctx, withInfo(out.toBitmap()), watermark.value)
                        tree?.createFile("image/jpeg", "$base.jpg")?.uri?.let { u -> ctx.contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }; pipelineOutputs.add(u) }
                        if (framed) tree?.createFile("image/jpeg", "${base}_keret.jpg")?.uri?.let { u -> ctx.contentResolver.openOutputStream(u)!!.use { framed(bmp).compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                    }
                    pipeline.update { it.copy(done = it.done + "${job.name}: ${res.used} kocka → $base.jpg (${auto.analysis.type.label})", jobs = it.jobs.drop(1)) }
                    System.gc()
                }
                pipeline.update { it.copy(running = false, current = "", detail = "") }
                // a kész képek a Kötegelt felirat galériába kerülnek, képenként átnézhető / javítható felirattal
                if (pipelineOutputs.isNotEmpty()) { runCatching { batchAdd(pipelineOutputs.toList()) }; pipelineOutputs.clear() }
                ProcessingBus.done("Pipeline kész ✓", pipeline.value.done.joinToString("\n").ifBlank { "Nincs eredmény" })
            } catch (e: kotlinx.coroutines.CancellationException) { pipeline.update { it.copy(running = false, error = "Megszakítva") }; ProcessingBus.failed("Pipeline megszakítva", "") }
            catch (e: Throwable) { System.gc(); pipeline.update { it.copy(running = false, error = (e.message ?: e.toString()), jobs = it.jobs.drop(1)) }; ProcessingBus.failed("Pipeline hiba", e.message ?: e.toString()) }
        }
    }

    // ---------------------------------------------------------------- több éjszaka összevonása
    data class MergeState(val running: Boolean = false, val status: String = "", val error: String? = null, val log: List<String> = emptyList())
    val merge = MutableStateFlow(MergeState())
    fun mergeEntries(entries: List<LibraryEntry>) {
        if (merge.value.running || entries.size < 2) return
        viewModelScope.launch {
            merge.value = MergeState(running = true)
            ProcessingBus.begin(getApplication(), "Összevonás")
            try {
                val r = hu.novaprocess.library.Merger.merge(getApplication(), entries) { st -> merge.update { it.copy(status = st) }; ProcessingBus.progress("Összevonás", st, 0) }
                ProcessingBus.done("Összevonás kész ✓", "${entries.size} éjszaka · ${r.totalFrames} kocka")
                displayReferred = false
                setSource(r.image, "Összevont · ${entries.size} éjszaka · ${r.image.width}×${r.image.height}")
                caption.value = Caption(title = entries[0].title, date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date()), frames = r.totalFrames, exposure = if (r.totalFrames > 0) r.totalSec.toFloat() / r.totalFrames else 0f)
                lastFrameCount = r.totalFrames
                withContext(Dispatchers.IO) {
                    runCatching {
                        val thumb = Processor.process(r.image.downscale(320), ProcessParams()).toBitmap()
                        currentEntryId = Library.saveStack(getApplication<Application>(), r.image, thumb, entries[0].title + " (összevont)", r.totalFrames, r.totalSec, ProcessParams()).id
                        libraryVersion.value++
                    }
                }
                merge.value = MergeState(log = r.log)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { System.gc(); merge.value = MergeState(error = e.message ?: e.toString()); ProcessingBus.failed("Összevonás sikertelen", e.message ?: "") }
        }
    }

    // ---------------------------------------------------------------- A/B összehasonlítás
    val compare = MutableStateFlow<Bitmap?>(null)
    fun loadCompare(uri: Uri) {
        viewModelScope.launch {
            try {
                val img = withContext(Dispatchers.IO) { ImageLoader.load(getApplication<Application>(), uri) }
                val pv = previewSource
                val target = if (pv != null) img.downscale(maxOf(pv.width, pv.height)) else img.downscale(PREVIEW_SIDE)
                // ha lineáris FITS/TIFF jön, autostretch; JPEG-nél csak a lineárisból visszaalakítás
                val name = ImageLoader.displayName(getApplication<Application>(), uri)
                val shown = if (ImageLoader.isFits(name) || ImageLoader.isTiff(name)) Processor.process(target, ProcessParams()) else target.also { t -> for (c in 0 until t.channels) { val a = t.data[c]; for (i in a.indices) a[i] = ImageLoader.linearToSrgb(a[i]) } }
                compare.value = shown.toBitmap()
            } catch (e: Throwable) { _ui.update { it.copy(message = "Összehasonlító kép nem nyitható: ${e.message}") } }
        }
    }
    fun clearCompare() { compare.value = null }

    suspend fun starShapeMap(): hu.novaprocess.process.StarShape.Map {
        val src = previewSource ?: throw IllegalStateException("Nincs kép")
        return withContext(Dispatchers.Default) { hu.novaprocess.process.StarShape.measure(src) }
    }

    // ---------------------------------------------------------------- bejövő megosztás (Megosztás → NovaProcess)
    /** 1 kép → szerkesztő; több → kockalista. A NovaRoot fogyasztja. */
    val incoming = MutableStateFlow<List<Uri>?>(null)
    fun handleIncoming(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) openSingle(uris[0]) else addFrames(uris, replace = true)
        incoming.value = uris
    }
    fun consumeIncoming() { incoming.value = null }

    // ---------------------------------------------------------------- kötegelt vízjel + adatfelirat
    data class BatchItem(val uri: Uri, val name: String, val data: hu.novaprocess.process.InfoData, val width: Int = 0, val height: Int = 0)
    data class BatchState(val items: List<BatchItem> = emptyList(), val running: Boolean = false, val done: Int = 0, val message: String? = null, val png: Boolean = false)
    val batch = MutableStateFlow(BatchState())

    /** Seestar fájlnév-minták: "Stacked_142_M 31_10.0s_IRCUT_20251104-223344.jpg", "M 31_…", "Light_M 31_10.0s_…". */
    private fun infoFromName(name: String, lastModified: Long): hu.novaprocess.process.InfoData {
        val base = name.substringBeforeLast('.')
        var frames = 0; var exp = 0f; var obj = ""; var date = ""
        Regex("^Stacked_(\\d+)_(.+?)_(\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(base)?.let { m -> frames = m.groupValues[1].toInt(); obj = m.groupValues[2]; exp = m.groupValues[3].toFloat() }
        if (obj.isBlank()) Regex("^(?:Light_)?(.+?)_(\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(base)?.let { m -> obj = m.groupValues[1]; exp = m.groupValues[2].toFloat() }
        // katalógusnév a fájlnév bármely részéből: M 31, NGC 6992, IC 410, SH2-171, C 33, Sh2 171 …
        Regex("\\b(M|NGC|IC|SH2|Sh2|C|B|LDN|LBN|Abell|Cr|Mel)[ _-]?(\\d{1,5}[A-Za-z]?)\\b").find(base)?.let { m ->
            val cat = m.groupValues[1].uppercase().let { if (it == "SH2") "Sh2" else it }
            obj = (if (cat == "Sh2") "Sh2-" else "$cat ") + m.groupValues[2]
        }
        if (obj.isBlank()) obj = base.substringBefore("_").substringBefore("-")
        hu.novaprocess.catalog.Catalog.find(getApplication(), obj)?.let { obj = it.title }
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(base)?.let { m -> date = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}" }
        if (date.isBlank() && lastModified > 0) date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date(lastModified))
        obj = obj.replace('_', ' ').trim()
        return hu.novaprocess.process.InfoData(obj, infoOverlay.value.city, date, caption.value.instrument.ifBlank { "Seestar S30 Pro" }, frames, exp)
    }

    fun batchAdd(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val existing = batch.value.items.map { it.uri }.toSet()
        val items = uris.filter { it !in existing }.map { u ->
            val name = ImageLoader.displayName(ctx, u)
            val lm = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, u)?.lastModified() ?: 0L }.getOrDefault(0L)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) } }
            BatchItem(u, name, infoFromName(name, lm), o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
        }
        batch.update { it.copy(items = it.items + items, message = null) }
    }
    fun batchAddFolder(tree: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@launch
            val files = ArrayList<androidx.documentfile.provider.DocumentFile>()
            fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int) { for (f in d.listFiles()) { if (f.isDirectory) { if (depth < 2) walk(f, depth + 1) } else { val n = (f.name ?: "").lowercase(); if ((n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) && !n.contains("_thn")) files.add(f) } } }
            walk(root, 0)
            withContext(Dispatchers.Main) { batchAdd(files.map { it.uri }) }
        }
    }
    fun batchUpdate(uri: Uri, f: (hu.novaprocess.process.InfoData) -> hu.novaprocess.process.InfoData) { batch.update { st -> st.copy(items = st.items.map { if (it.uri == uri) it.copy(data = f(it.data)) else it }) } }
    fun batchRemove(uri: Uri) { batch.update { st -> st.copy(items = st.items.filter { it.uri != uri }) } }

    private fun decodeForBatch(uri: Uri, maxSide: Int): Bitmap? {
        val cr = getApplication<Application>().contentResolver
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        return cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
    }

    /** Előnézet a galériához (lekicsinyítve, vízjel + felirat). */
    fun batchPreview(item: BatchItem): Bitmap? {
        val b = decodeForBatch(item.uri, 1400) ?: return null
        val withInfo = hu.novaprocess.process.InfoOverlay.apply(b, infoOverlay.value, item.data.copy(city = item.data.city.ifBlank { infoOverlay.value.city }))
        return Watermark.apply(getApplication(), withInfo, watermark.value)
    }

    /** Teljes felbontású export minden képre a kiválasztott mappába. */
    fun batchExport(outTree: Uri) {
        if (batch.value.running) return
        viewModelScope.launch {
            batch.update { it.copy(running = true, done = 0, message = null) }
            val ctx = getApplication<Application>()
            ProcessingBus.begin(ctx, "Kötegelt felirat")
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, outTree)
            var ok = 0
            try {
                for ((i, item) in batch.value.items.withIndex()) {
                    withContext(Dispatchers.Default) {
                        val b = decodeForBatch(item.uri, 8000) ?: return@withContext
                        val out = Watermark.apply(ctx, hu.novaprocess.process.InfoOverlay.apply(b, infoOverlay.value, item.data.copy(city = item.data.city.ifBlank { infoOverlay.value.city })), watermark.value)
                        val png = batch.value.png
                        val base = item.name.substringBeforeLast('.') + (if (png) "_nova.png" else "_nova.jpg")
                        tree?.createFile(if (png) "image/png" else "image/jpeg", base)?.uri?.let { u -> ctx.contentResolver.openOutputStream(u)!!.use { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 97, it) }; ok++ }
                        out.recycle()
                    }
                    batch.update { it.copy(done = i + 1) }
                    ProcessingBus.progress("Kötegelt felirat", "${i + 1} / ${batch.value.items.size}", (i + 1) * 100 / batch.value.items.size)
                    System.gc()
                }
                batch.update { it.copy(running = false, message = "Kész: $ok kép exportálva (${if (it.png) "_nova.png, veszteségmentes" else "_nova.jpg, 97%"}).") }
                ProcessingBus.done("Kötegelt export kész ✓", "$ok kép")
            } catch (e: Throwable) { System.gc(); batch.update { it.copy(running = false, message = "Hiba: ${e.message}") }; ProcessingBus.failed("Kötegelt export hiba", e.message ?: "") }
        }
    }

    // ---------------------------------------------------------------- égi azonosítás (katalógus)
    data class SkyState(val field: hu.novaprocess.catalog.SkyField? = null, val matches: List<hu.novaprocess.catalog.Match> = emptyList(),
                        val crop: hu.novaprocess.catalog.CropSuggestion? = null, val show: Boolean = false)
    val sky = MutableStateFlow(SkyState())
    val primaryObject: hu.novaprocess.catalog.CatObject? get() = sky.value.matches.firstOrNull { it.inField }?.obj

    /** Látómező beállítása (fejlécből vagy plate solve-ból) → katalógus-egyeztetés, felirat, vágási javaslat. */
    private fun setSkyField(f: hu.novaprocess.catalog.SkyField?) {
        if (f == null) { sky.value = SkyState(); return }
        val ctx = getApplication<Application>()
        val matches = hu.novaprocess.catalog.Catalog.identify(ctx, f.ra, f.dec, f.fovWDeg, f.fovHDeg)
        val primary = matches.firstOrNull { it.inField }
        val crop = primary?.let { hu.novaprocess.catalog.Framing.suggest(f, it) }
        sky.value = SkyState(f, matches, crop, sky.value.show)
        if (primary != null) {
            val t = caption.value.title
            val generic = t.isBlank() || t.startsWith("Stack") || t.startsWith("Light") || Regex("^(M|NGC|IC|C)\s?\d+$", RegexOption.IGNORE_CASE).matches(t.trim()) || t.contains("_")
            if (generic) caption.value = caption.value.copy(title = primary.obj.title)
        }
    }
    fun setSkyFromMeta(m: hu.novaprocess.image.ImageLoader.Meta?, w: Int, h: Int) = setSkyField(hu.novaprocess.catalog.SkyField.fromMeta(m, w, h))
    fun toggleSky() { sky.update { it.copy(show = !it.show) } }
    fun applySuggestedCrop() { sky.value.crop?.let { c -> commitParams { it.copy(cropLeft = c.left, cropTop = c.top, cropRight = c.right, cropBottom = c.bottom) } } }
    /** Felirat címe a katalógusból (kézi választás a kontextus-lapon). */
    fun useObjectName(o: hu.novaprocess.catalog.CatObject) { caption.value = caption.value.copy(title = o.title) }

    // ---------------------------------------------------------------- automata szerkesztés
    data class AutoState(val running: Boolean = false, val result: AutoEditor.Result? = null, val error: String? = null)
    val auto = MutableStateFlow(AutoState())
    private var lastFrameCount = 0

    /** Elemzés + javaslat az előnézeti képen; nem módosít, amíg nem fogadják el. */
    fun runAutoEdit() {
        val src = previewSource ?: return
        if (auto.value.running) return
        viewModelScope.launch {
            auto.value = AutoState(running = true)
            try {
                val hint = solve.value.result?.annotations?.filter { it.type != "star" }?.maxByOrNull { it.radius }?.names?.firstOrNull() ?: caption.value.title.ifBlank { null }
                val known = primaryObject?.let { hu.novaprocess.catalog.Catalog.autoType(it.type) }
                val res = withContext(Dispatchers.Default) { AutoEditor.run(src, hint, lastFrameCount, displayReferred, known) }
                auto.value = AutoState(result = res)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { auto.value = AutoState(error = e.message ?: e.javaClass.simpleName) }
        }
    }
    fun applyAuto() { auto.value.result?.let { applyPreset(it.params) }; auto.value = AutoState() }
    /** Az automata eredményének mentése presetként (az objektum nevével). */
    fun saveAutoAsPreset(name: String? = null): String? {
        val r = auto.value.result ?: return null
        val n = name?.ifBlank { null } ?: ("Auto · " + (primaryObject?.id ?: r.analysis.type.label) + " · " + java.text.SimpleDateFormat("MM.dd", java.util.Locale.US).format(java.util.Date()))
        hu.novaprocess.process.Presets.save(getApplication(), n, r.params)
        return n
    }
    fun dismissAuto() { auto.value = AutoState() }

    // ---------------------------------------------------------------- vízjel
    val watermark = MutableStateFlow(Watermark.load(app))
    fun setWatermark(s: hu.novaprocess.process.WatermarkSettings) { watermark.value = s; Watermark.save(getApplication(), s) }
    fun importWatermarkImage(uri: Uri): Boolean { val p = Watermark.importImage(getApplication(), uri) ?: return false; setWatermark(watermark.value.copy(mode = hu.novaprocess.process.WmMode.IMAGE, imagePath = p)); return true }
    /** Kis előnézet a vízjellel (beállító laphoz). */
    fun watermarkPreview(): Bitmap? = _ui.value.preview?.let { Watermark.apply(getApplication(), it.copy(Bitmap.Config.ARGB_8888, true), watermark.value) }

    // ---------------------------------------------------------------- felirat-adatok (keretes exporthoz)
    data class Caption(var title: String = "", var date: String = "", var frames: Int = 0, var exposure: Float = 0f, var instrument: String = "Seestar S30 Pro")
    val caption = MutableStateFlow(Caption())

    // ---------------------------------------------------------------- adatfelirat (export)
    val infoOverlay = MutableStateFlow(hu.novaprocess.process.InfoOverlay.load(app))
    fun setInfoOverlay(s: hu.novaprocess.process.InfoOverlaySettings) { infoOverlay.value = s; hu.novaprocess.process.InfoOverlay.save(getApplication(), s) }
    private fun infoData(): hu.novaprocess.process.InfoData { val c = caption.value; return hu.novaprocess.process.InfoData(c.title, infoOverlay.value.city, c.date, c.instrument, c.frames, c.exposure) }
    private fun withInfo(b: Bitmap): Bitmap = hu.novaprocess.process.InfoOverlay.apply(b, infoOverlay.value, infoData())
    fun infoPreview(): Bitmap? = _ui.value.preview?.let { withInfo(it.copy(Bitmap.Config.ARGB_8888, true)) }

    private fun captionFromFrames(used: Int) {
        val fs = frames.value.filter { !it.rejected }
        val first = fs.firstOrNull()
        caption.value = Caption(
            title = first?.objectName ?: first?.name?.substringBefore("_sub")?.substringBefore("-sub")?.substringBeforeLast('.') ?: "Stack",
            date = first?.dateObs?.take(10)?.replace('-', '.') ?: java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date()),
            frames = used, exposure = first?.exposure ?: 0f
        )
    }

    // ---------------------------------------------------------------- bolygó / Hold (lucky imaging)
    val planetaryOptions = MutableStateFlow(PlanetaryOptions())
    val planetaryProgress = MutableStateFlow<PlanetaryProgress?>(null)
    val planetaryError = MutableStateFlow<String?>(null)
    val planetaryRunning = MutableStateFlow(false)
    private var planetaryJob: Job? = null

    fun startPlanetary(video: Uri?, images: List<Uri>) {
        if (planetaryRunning.value) return
        planetaryJob = viewModelScope.launch {
            planetaryRunning.value = true; planetaryError.value = null
            ProcessingBus.begin(getApplication(), "Bolygó / Hold stack")
            try {
                val onP: (PlanetaryProgress) -> Unit = { planetaryProgress.value = it; ProcessingBus.progress("Bolygó / Hold · ${it.phase}", "${it.current} / ${it.total}", if (it.total > 0) it.current * 100 / it.total else 0) }
                val res = if (video != null) Planetary.stackVideo(getApplication<Application>(), video, planetaryOptions.value, onP)
                           else Planetary.stackImages(getApplication<Application>(), images, planetaryOptions.value, onP)
                ProcessingBus.done("Bolygó stack kész ✓", "${res.used}/${res.total} kocka")
                displayReferred = false
                setSource(res.image, "Bolygó stack · ${res.used}/${res.total} kocka · ${res.image.width}×${res.image.height}")
                // bolygóhoz a lineáris megjelenítés + wavelet a kiindulás
                params.value = ProcessParams(stretchMode = hu.novaprocess.process.StretchMode.NONE, wavelet1 = 0.8f, wavelet2 = 0.6f, waveletDenoise = 0.2f)
                caption.value = Caption(title = "Bolygó / Hold", date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date()), frames = res.used)
                schedulePreview(immediate = true)
                launch(Dispatchers.IO) {
                    runCatching {
                        val thumb = Processor.process(res.image.downscale(320), params.value).toBitmap()
                        currentEntryId = Library.saveStack(getApplication<Application>(), res.image, thumb, "Bolygó/Hold stack", res.used, 0, params.value).id
                        libraryVersion.value++
                    }
                }
            } catch (e: Throwable) { System.gc(); planetaryError.value = if (e is OutOfMemoryError) "Elfogyott a memória – csökkentsd a munkafelbontást vagy a kockaszámot." else (e.message ?: e.toString()); ProcessingBus.failed("Bolygó stack sikertelen", planetaryError.value ?: "") }
            planetaryRunning.value = false; planetaryProgress.value = null
        }
    }
    fun cancelPlanetary() { planetaryJob?.cancel(); planetaryRunning.value = false; planetaryProgress.value = null }

    // ---------------------------------------------------------------- könyvtár
    private var currentEntryId: String? = null
    val libraryVersion = MutableStateFlow(0)

    fun openEntry(e: LibraryEntry) {
        viewModelScope.launch {
            _ui.update { it.copy(processing = true, message = null) }
            try {
                val img = withContext(Dispatchers.IO) {
                    if (e.sourcePath != null) hu.novaprocess.image.TiffReader.read(File(e.sourcePath).inputStream())
                    else { val n = ImageLoader.displayName(getApplication<Application>(), Uri.parse(e.sourceUri!!)); if (ImageLoader.isDisplayReferred(n)) ImageLoader.loadDisplayReferred(getApplication<Application>(), Uri.parse(e.sourceUri), n) else ImageLoader.load(getApplication<Application>(), Uri.parse(e.sourceUri)) }
                }
                displayReferred = e.sourcePath == null && ImageLoader.isDisplayReferred(ImageLoader.displayName(getApplication<Application>(), Uri.parse(e.sourceUri!!)))
                setSource(img, "${e.title} · ${img.width}×${img.height}")
                currentEntryId = e.id
                runCatching { Presets.gson.fromJson(e.paramsJson, ProcessParams::class.java) }.getOrNull()?.let { p -> params.value = p; schedulePreview(immediate = true) }
            } catch (ex: Exception) {
                _ui.update { it.copy(processing = false, message = "Nem nyitható (törölt vagy elérhetetlen forrás): ${ex.message}") }
            }
        }
    }

    fun deleteEntry(id: String) { Library.delete(getApplication(), id); libraryVersion.value++ }

    /** A jelenlegi receptet elmenti a könyvtár aktuális bejegyzéséhez (export után automatikus). */
    private fun persistRecipe() { currentEntryId?.let { Library.updateParams(getApplication(), it, Presets.gson.toJson(params.value)) } }

    fun applyPreset(p: ProcessParams) { commitParams { p } }
    fun recipeJson(): String = Presets.toJson(params.value)
    fun loadRecipe(json: String): Boolean = runCatching { applyPreset(Presets.fromJson(json)); true }.getOrDefault(false)

    /** Kész (JPEG/PNG) kép: nincs automatikus stretch, 1:1 megjelenítés. Stack / FITS / TIFF: lineáris út autostretch-csel. */
    var displayReferred: Boolean = false
        private set
    private fun defaultParams(): ProcessParams = if (displayReferred) ProcessParams(stretchMode = hu.novaprocess.process.StretchMode.NONE) else ProcessParams()

    private fun setSource(img: FloatImage, info: String) {
        source = img
        previewSource = img.downscale(PREVIEW_SIDE)
        undo.clear(); redo.clear()
        params.value = defaultParams()
        _ui.update { it.copy(hasImage = true, sourceInfo = info, canUndo = false, canRedo = false) }
        viewModelScope.launch {
            val orig = withContext(Dispatchers.Default) { Processor.process(previewSource!!, defaultParams()).toBitmap() }
            _ui.update { it.copy(originalPreview = orig) }
        }
        schedulePreview(immediate = true)
    }

    // ---------------------------------------------------------------- paraméterek, undo/redo
    /** Csúszka-húzás közben: nem kerül az undo-ba. */
    fun previewParams(transform: (ProcessParams) -> ProcessParams) {
        params.value = transform(params.value)
        schedulePreview()
    }

    /** Lezárt változtatás (gomb, csúszka elengedése): undo-lépés. */
    fun commitParams(transform: (ProcessParams) -> ProcessParams = { it }) {
        val next = transform(params.value)
        if (next == params.value) return
        undo.addLast(params.value); if (undo.size > 60) undo.removeFirst()
        redo.clear()
        params.value = next
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = false) }
        schedulePreview()
    }

    /** Csúszka: elengedéskor az indulási értéket tesszük az undo-ba. */
    private var dragStart: ProcessParams? = null
    fun beginDrag() { if (dragStart == null) dragStart = params.value }
    fun endDrag() {
        val start = dragStart ?: return
        dragStart = null
        if (start != params.value) { undo.addLast(start); if (undo.size > 60) undo.removeFirst(); redo.clear() }
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(params.value); params.value = prev
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = true) }
        schedulePreview(immediate = true)
    }
    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(params.value); params.value = next
        _ui.update { it.copy(canUndo = true, canRedo = redo.isNotEmpty()) }
        schedulePreview(immediate = true)
    }
    fun resetParams() { commitParams { defaultParams() } }

    private fun schedulePreview(immediate: Boolean = false) {
        val src = previewSource ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(100)
            _ui.update { it.copy(processing = true) }
            val p = params.value
            val result = try { withContext(Dispatchers.Default) {
                val out = Processor.process(src, p)
                val lum = out.toMono().data[0]
                val med = Processor.median(lum); val mad = Processor.mad(lum, med)
                var clipped = 0; for (v in lum) if (v >= 0.999f) clipped++
                Triple(out.toBitmap(), Processor.histogram(out), "MED %.3f · MAD %.3f · CLIP %.2f%%".format(med, mad, 100f * clipped / lum.size))
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                _ui.update { it.copy(processing = false, message = "Előnézet hiba: ${e.message ?: e.javaClass.simpleName}") }; return@launch
            }
            _ui.update { it.copy(preview = result.first, histogram = result.second, stats = result.third, processing = false) }
        }
    }

    // ---------------------------------------------------------------- export
    val exportAnnotations = MutableStateFlow(false)
    val exportSize = MutableStateFlow(ExportSize.ORIGINAL)

    /** Méret-preset: hosszabb oldal skálázása, négyzet/portré esetén középre vágás. */
    private fun sized(b: Bitmap): Bitmap {
        val sp = exportSize.value
        if (sp == ExportSize.ORIGINAL) return b
        if (sp.h == 0) {
            val sc = sp.w.toFloat() / maxOf(b.width, b.height); if (sc >= 1f) return b
            return Bitmap.createScaledBitmap(b, (b.width * sc).toInt(), (b.height * sc).toInt(), true)
        }
        val targetAspect = sp.w.toFloat() / sp.h
        val srcAspect = b.width.toFloat() / b.height
        val cw: Int; val ch: Int
        if (srcAspect > targetAspect) { ch = b.height; cw = (ch * targetAspect).toInt() } else { cw = b.width; ch = (cw / targetAspect).toInt() }
        val crop = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw, ch)
        return Bitmap.createScaledBitmap(crop, sp.w, sp.h, true)
    }

    fun export(uri: Uri, format: ExportFormat, quality: Int = 95) {
        val src = source ?: return
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true, message = null) }
            try {
                withContext(Dispatchers.Default) {
                    val out = Processor.process(src, params.value)
                    val cr = getApplication<Application>().contentResolver
                    cr.openOutputStream(uri)!!.use { os ->
                        when (format) {
                            ExportFormat.TIFF16 -> TiffWriter.write(out, os)
                            ExportFormat.FITS32 -> hu.novaprocess.image.FitsWriter.write(out, os, mapOf("OBJECT" to caption.value.title, "DATE-OBS" to caption.value.date, "INSTRUME" to caption.value.instrument))
                            ExportFormat.PNG -> Watermark.apply(getApplication(), deco(out.toBitmap()), watermark.value).compress(Bitmap.CompressFormat.PNG, 100, os)
                            ExportFormat.JPEG -> Watermark.apply(getApplication(), deco(out.toBitmap()), watermark.value).compress(Bitmap.CompressFormat.JPEG, quality, os)
                            ExportFormat.FRAMED_JPEG -> framed(Watermark.apply(getApplication(), deco(out.toBitmap()), watermark.value)).compress(Bitmap.CompressFormat.JPEG, quality, os)
                        }
                    }
                }
                persistRecipe()
                _ui.update { it.copy(exporting = false, message = "Exportálva.") }
            } catch (e: Throwable) {
                System.gc()
                _ui.update { it.copy(exporting = false, message = if (e is OutOfMemoryError) "Export: elfogyott a memória." else "Export hiba: ${e.message}") }
            }
        }
    }

    private fun deco(b: Bitmap): Bitmap = withInfo(sized(if (exportAnnotations.value) annotate(b) else b))

    /** Fekete keret + felirat: objektum · dátum · kockák × expozíció = integráció · eszköz · NovaProcess. */
    private fun framed(src: Bitmap): Bitmap {
        val cap = caption.value
        val margin = (src.width * 0.04f).toInt().coerceAtLeast(24)
        val bar = (src.width * 0.09f).toInt().coerceAtLeast(70)
        val out = Bitmap.createBitmap(src.width + 2 * margin, src.height + margin + bar, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawColor(android.graphics.Color.BLACK)
        cv.drawBitmap(src, margin.toFloat(), margin.toFloat(), null)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = bar * 0.32f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val sub = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(154, 154, 154); textSize = bar * 0.22f }
        val integ = if (cap.frames > 0 && cap.exposure > 0f) {
            val tot = cap.frames * cap.exposure
            "${cap.frames} × ${"%.0f".format(cap.exposure)} s = " + (if (tot >= 3600) "%.1f óra".format(tot / 3600) else "%.0f perc".format(tot / 60))
        } else if (cap.frames > 0) "${cap.frames} kocka" else ""
        val line2 = listOf(cap.date, integ, cap.instrument, "NovaProcess").filter { it.isNotBlank() }.joinToString("  ·  ")
        val y1 = src.height + margin + bar * 0.45f
        cv.drawText(cap.title.ifBlank { "Asztrofotó" }, margin.toFloat(), y1, paint)
        cv.drawText(line2, margin.toFloat(), y1 + bar * 0.32f, sub)
        return out
    }

    fun sourceWidth(): Int = source?.width ?: 1
    fun sourceHeight(): Int = source?.height ?: 1

    fun clearMessage() { _ui.update { it.copy(message = null) } }

    companion object { const val PREVIEW_SIDE = 1100 }
}

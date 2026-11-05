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
import hu.novaprocess.library.LibraryEntry
import hu.novaprocess.process.Presets
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

enum class ExportFormat { JPEG, PNG, TIFF16 }

data class StackUiState(
    val running: Boolean = false,
    val analyzing: Boolean = false,
    val analyzed: Int = 0,
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
    fun addFrames(uris: List<Uri>) {
        val existing = frames.value.map { it.uri }.toSet()
        val new = uris.filter { it !in existing }
        if (new.isEmpty()) return
        viewModelScope.launch {
            stackState.update { it.copy(analyzing = true, analyzed = 0, error = null) }
            val sem = Semaphore(2)
            val infos = withContext(Dispatchers.Default) {
                new.map { uri ->
                    async {
                        sem.withPermit {
                            val info = try { FrameAnalyzer.analyze(getApplication<Application>(), uri) }
                            catch (e: Exception) { FrameInfo(uri, ImageLoader.displayName(getApplication<Application>(), uri), 0f, 0, 0f, 0f, null, true, "nem olvasható") }
                            stackState.update { it.copy(analyzed = it.analyzed + 1) }
                            info
                        }
                    }
                }.awaitAll()
            }
            frames.value = FrameAnalyzer.autoReject(frames.value + infos)
            stackState.update { it.copy(analyzing = false) }
        }
    }

    fun toggleReject(uri: Uri) {
        frames.value = frames.value.map { if (it.uri == uri) it.copy(rejected = !it.rejected, reason = if (it.rejected) "" else "kézzel kizárva") else it }
    }

    fun clearFrames() { frames.value = emptyList(); stackState.value = StackUiState() }

    // ---------------------------------------------------------------- stack
    fun startStack() {
        val uris = frames.value.filter { !it.rejected }.map { it.uri }
        if (uris.isEmpty() || stackState.value.running) return
        stackJob = viewModelScope.launch {
            stackState.update { it.copy(running = true, error = null, log = emptyList()) }
            try {
                val res = Stacker.stack(getApplication<Application>(), uris, stackOptions.value) { p ->
                    stackState.update { it.copy(progress = p) }
                }
                setSource(res.image, "Stack · ${res.used} kocka · ${res.image.width}×${res.image.height}")
                stackState.update { it.copy(running = false, log = res.log) }
                // könyvtárba: 16 bites TIFF + miniatűr
                launch(Dispatchers.IO) {
                    runCatching {
                        val thumb = Processor.process(res.image.downscale(320), ProcessParams()).toBitmap()
                        val title = frames.value.firstOrNull()?.name?.substringBefore("_sub")?.substringBefore("-sub")?.substringBeforeLast('.') ?: "Stack"
                        val entry = Library.saveStack(getApplication<Application>(), res.image, thumb, title, res.used, 0, ProcessParams())
                        currentEntryId = entry.id
                    }
                }
            } catch (e: Exception) {
                stackState.update { it.copy(running = false, error = e.message ?: e.toString()) }
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
                val img = withContext(Dispatchers.IO) { ImageLoader.load(getApplication<Application>(), uri, name) }
                setSource(img, "$name · ${img.width}×${img.height}")
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

    // ---------------------------------------------------------------- könyvtár
    private var currentEntryId: String? = null
    val libraryVersion = MutableStateFlow(0)

    fun openEntry(e: LibraryEntry) {
        viewModelScope.launch {
            _ui.update { it.copy(processing = true, message = null) }
            try {
                val img = withContext(Dispatchers.IO) {
                    if (e.sourcePath != null) hu.novaprocess.image.TiffReader.read(File(e.sourcePath).inputStream())
                    else ImageLoader.load(getApplication<Application>(), Uri.parse(e.sourceUri!!))
                }
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

    private fun setSource(img: FloatImage, info: String) {
        source = img
        previewSource = img.downscale(PREVIEW_SIDE)
        undo.clear(); redo.clear()
        params.value = ProcessParams()
        _ui.update { it.copy(hasImage = true, sourceInfo = info, canUndo = false, canRedo = false) }
        viewModelScope.launch {
            val orig = withContext(Dispatchers.Default) { Processor.process(previewSource!!, ProcessParams()).toBitmap() }
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
    fun resetParams() { commitParams { ProcessParams() } }

    private fun schedulePreview(immediate: Boolean = false) {
        val src = previewSource ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(100)
            _ui.update { it.copy(processing = true) }
            val p = params.value
            val result = withContext(Dispatchers.Default) {
                val out = Processor.process(src, p)
                val lum = out.toMono().data[0]
                val med = Processor.median(lum); val mad = Processor.mad(lum, med)
                var clipped = 0; for (v in lum) if (v >= 0.999f) clipped++
                Triple(out.toBitmap(), Processor.histogram(out), "MED %.3f · MAD %.3f · CLIP %.2f%%".format(med, mad, 100f * clipped / lum.size))
            }
            _ui.update { it.copy(preview = result.first, histogram = result.second, stats = result.third, processing = false) }
        }
    }

    // ---------------------------------------------------------------- export
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
                            ExportFormat.PNG -> out.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, os)
                            ExportFormat.JPEG -> out.toBitmap().compress(Bitmap.CompressFormat.JPEG, quality, os)
                        }
                    }
                }
                persistRecipe()
                _ui.update { it.copy(exporting = false, message = "Exportálva.") }
            } catch (e: Exception) {
                _ui.update { it.copy(exporting = false, message = "Export hiba: ${e.message}") }
            }
        }
    }

    fun clearMessage() { _ui.update { it.copy(message = null) } }

    companion object { const val PREVIEW_SIDE = 1100 }
}

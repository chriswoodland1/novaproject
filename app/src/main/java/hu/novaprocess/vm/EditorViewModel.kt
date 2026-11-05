package hu.novaprocess.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.image.TiffWriter
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Processor
import hu.novaprocess.stack.StackOptions
import hu.novaprocess.stack.StackProgress
import hu.novaprocess.stack.Stacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportFormat { JPEG, PNG, TIFF16 }

data class StackUiState(
    val running: Boolean = false,
    val progress: StackProgress? = null,
    val log: List<String> = emptyList(),
    val error: String? = null
)

data class EditorUiState(
    val hasImage: Boolean = false,
    val sourceInfo: String = "",
    val preview: Bitmap? = null,
    val histogram: Array<IntArray>? = null,
    val processing: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    // --- bemenet ---
    val frames = MutableStateFlow<List<Uri>>(emptyList())
    val stackOptions = MutableStateFlow(StackOptions())
    val stackState = MutableStateFlow(StackUiState())

    // --- szerkesztés ---
    private var source: FloatImage? = null
    private var previewSource: FloatImage? = null
    val params = MutableStateFlow(ProcessParams())
    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui
    private var previewJob: Job? = null
    private var stackJob: Job? = null

    // ---------------------------------------------------------------- stack
    fun startStack() {
        val uris = frames.value
        if (uris.isEmpty() || stackState.value.running) return
        stackJob = viewModelScope.launch {
            stackState.value = StackUiState(running = true)
            try {
                val res = Stacker.stack(getApplication<Application>(), uris, stackOptions.value) { p ->
                    stackState.update { it.copy(progress = p) }
                }
                setSource(res.image, "Stack: ${res.used} kocka · ${res.image.width}×${res.image.height}")
                stackState.update { it.copy(running = false, log = res.log) }
            } catch (e: Exception) {
                stackState.update { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancelStack() { stackJob?.cancel(); stackState.update { it.copy(running = false, error = "Megszakítva") } }

    // ---------------------------------------------------------------- egyetlen kép megnyitása
    fun openSingle(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(processing = true, message = null) }
            try {
                val name = ImageLoader.displayName(getApplication<Application>(), uri)
                val img = withContext(Dispatchers.IO) { ImageLoader.load(getApplication<Application>(), uri, name) }
                setSource(img, "$name · ${img.width}×${img.height}")
            } catch (e: Exception) {
                _ui.update { it.copy(processing = false, message = "Nem sikerült megnyitni: ${e.message}") }
            }
        }
    }

    private fun setSource(img: FloatImage, info: String) {
        source = img
        previewSource = img.downscale(PREVIEW_SIDE)
        _ui.update { it.copy(hasImage = true, sourceInfo = info) }
        params.value = ProcessParams()
        schedulePreview(immediate = true)
    }

    // ---------------------------------------------------------------- paraméterek
    fun updateParams(transform: (ProcessParams) -> ProcessParams) {
        params.value = transform(params.value)
        schedulePreview()
    }

    fun resetParams() { params.value = ProcessParams(); schedulePreview(immediate = true) }

    private fun schedulePreview(immediate: Boolean = false) {
        val src = previewSource ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(120) // csúszka-húzás közben ne számoljunk minden mozdulatra
            _ui.update { it.copy(processing = true) }
            val p = params.value
            val result = withContext(Dispatchers.Default) {
                val out = Processor.process(src, p)
                Pair(out.toBitmap(), Processor.histogram(out))
            }
            _ui.update { it.copy(preview = result.first, histogram = result.second, processing = false) }
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
                _ui.update { it.copy(exporting = false, message = "Exportálva.") }
            } catch (e: Exception) {
                _ui.update { it.copy(exporting = false, message = "Export hiba: ${e.message}") }
            }
        }
    }

    fun clearMessage() { _ui.update { it.copy(message = null) } }

    companion object { const val PREVIEW_SIDE = 1100 }
}

package hu.novaprocess.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Rotation
import hu.novaprocess.process.StretchMode
import hu.novaprocess.ui.theme.LocalNightMode
import hu.novaprocess.vm.EditorViewModel
import hu.novaprocess.vm.ExportFormat

/** Egy eszköz: név + hogyan olvassa/írja a paramétert. Csúszka vagy kapcsoló/választó. */
private data class Tool(
    val id: String, val label: String,
    val min: Float = 0f, val max: Float = 1f,
    val fmt: (Float) -> String = { "%.2f".format(it) },
    val get: (ProcessParams) -> Float, val set: (ProcessParams, Float) -> ProcessParams,
    val options: List<Pair<String, Float>>? = null   // ha nem null: választó gombok csúszka helyett
)

private val CATEGORIES: List<Pair<String, List<Tool>>> = listOf(
    "Fény" to listOf(
        Tool("exposure", "Expozíció (EV)", -3f, 3f, get = { it.exposure }, set = { p, v -> p.copy(exposure = v) }),
        Tool("contrast", "Kontraszt", -1f, 1f, get = { it.contrast }, set = { p, v -> p.copy(contrast = v) }),
        Tool("highlights", "Csúcsfények", -1f, 1f, get = { it.highlights }, set = { p, v -> p.copy(highlights = v) }),
        Tool("shadows", "Árnyékok", -1f, 1f, get = { it.shadows }, set = { p, v -> p.copy(shadows = v) }),
        Tool("whites", "Fehérek", -1f, 1f, get = { it.whites }, set = { p, v -> p.copy(whites = v) }),
        Tool("blacks", "Feketék", -1f, 1f, get = { it.blacks }, set = { p, v -> p.copy(blacks = v) }),
        Tool("gamma", "Gamma", 0.5f, 2f, get = { it.gamma }, set = { p, v -> p.copy(gamma = v) }),
        Tool("lb", "Szintek · fekete", 0f, 0.5f, get = { it.levelsBlack }, set = { p, v -> p.copy(levelsBlack = v) }),
        Tool("lw", "Szintek · fehér", 0.5f, 1f, get = { it.levelsWhite }, set = { p, v -> p.copy(levelsWhite = v) })
    ),
    "Szín" to listOf(
        Tool("temp", "Hőmérséklet", -1f, 1f, get = { it.temperature }, set = { p, v -> p.copy(temperature = v) }),
        Tool("tint", "Árnyalat", -1f, 1f, get = { it.tint }, set = { p, v -> p.copy(tint = v) }),
        Tool("sat", "Telítettség", -1f, 1f, get = { it.saturation }, set = { p, v -> p.copy(saturation = v) }),
        Tool("vib", "Vibrance", -1f, 1f, get = { it.vibrance }, set = { p, v -> p.copy(vibrance = v) }),
        Tool("r", "Vörös", -0.5f, 0.5f, get = { it.redBalance }, set = { p, v -> p.copy(redBalance = v) }),
        Tool("g", "Zöld", -0.5f, 0.5f, get = { it.greenBalance }, set = { p, v -> p.copy(greenBalance = v) }),
        Tool("b", "Kék", -0.5f, 0.5f, get = { it.blueBalance }, set = { p, v -> p.copy(blueBalance = v) }),
        Tool("bgn", "Háttér semlegesítés", get = { if (it.backgroundNeutralize) 1f else 0f }, set = { p, v -> p.copy(backgroundNeutralize = v > 0.5f) }, options = listOf("Ki" to 0f, "Be" to 1f)),
        Tool("wb", "Fehéregyensúly csillagokra", get = { if (it.whiteBalanceStars) 1f else 0f }, set = { p, v -> p.copy(whiteBalanceStars = v > 0.5f) }, options = listOf("Ki" to 0f, "Be" to 1f))
    ),
    "Asztro" to listOf(
        Tool("stretch", "Stretch módja", get = { it.stretchMode.ordinal.toFloat() }, set = { p, v -> p.copy(stretchMode = StretchMode.values()[v.toInt()]) },
            options = listOf("Nincs" to 0f, "Auto" to 1f, "Asinh" to 2f, "MTF" to 3f)),
        Tool("target", "Cél háttér (Auto/MTF)", 0.05f, 0.6f, get = { it.mtfMidtones }, set = { p, v -> p.copy(mtfMidtones = v) }),
        Tool("clip", "Árnyék vágás (MAD×)", 0f, 5f, { "%.1f".format(it) }, get = { it.mtfShadowClip }, set = { p, v -> p.copy(mtfShadowClip = v) }),
        Tool("asinh", "Asinh erősség", 1f, 1000f, { "%.0f".format(it) }, get = { it.asinhStrength }, set = { p, v -> p.copy(asinhStrength = v) }),
        Tool("bp", "Feketepont", 0f, 0.3f, { "%.3f".format(it) }, get = { it.blackPoint }, set = { p, v -> p.copy(blackPoint = v) }),
        Tool("bge", "Háttér-gradiens kivonás", get = { if (!it.backgroundExtraction) 0f else it.backgroundDegree.toFloat() },
            set = { p, v -> p.copy(backgroundExtraction = v > 0f, backgroundDegree = if (v > 0f) v.toInt() else p.backgroundDegree) },
            options = listOf("Ki" to 0f, "Sík" to 1f, "Másodfokú" to 2f))
    ),
    "Csillag" to listOf(
        Tool("sr", "Csillagcsökkentés", 0f, 1f, get = { it.starReduction }, set = { p, v -> p.copy(starReduction = v) }),
        Tool("srr", "Csillag sugár (px)", 1f, 5f, { "%.0f".format(it) }, get = { it.starRadius.toFloat() }, set = { p, v -> p.copy(starRadius = v.toInt()) })
    ),
    "Részlet" to listOf(
        Tool("dn", "Zajszűrés", 0f, 1f, get = { it.denoise }, set = { p, v -> p.copy(denoise = v) }),
        Tool("dnr", "Zajszűrés sugár", 1f, 4f, { "%.0f".format(it) }, get = { it.denoiseRadius.toFloat() }, set = { p, v -> p.copy(denoiseRadius = v.toInt()) }),
        Tool("clar", "Clarity", -1f, 1f, get = { it.clarity }, set = { p, v -> p.copy(clarity = v) }),
        Tool("dehaze", "Dehaze", 0f, 1f, get = { it.dehaze }, set = { p, v -> p.copy(dehaze = v) }),
        Tool("sharp", "Élesítés", 0f, 1f, get = { it.sharpen }, set = { p, v -> p.copy(sharpen = v) })
    ),
    "Geometria" to listOf(
        Tool("rot", "Forgatás", get = { it.rotation.ordinal.toFloat() }, set = { p, v -> p.copy(rotation = Rotation.values()[v.toInt()]) },
            options = listOf("0°" to 0f, "90°" to 1f, "180°" to 2f, "270°" to 3f)),
        Tool("flip", "Tükrözés", get = { if (it.flipH) 1f else 0f }, set = { p, v -> p.copy(flipH = v > 0.5f) }, options = listOf("Ki" to 0f, "Vízszintes" to 1f)),
        Tool("cl", "Vágás · bal", 0f, 0.45f, get = { it.cropLeft }, set = { p, v -> p.copy(cropLeft = v) }),
        Tool("ct", "Vágás · felső", 0f, 0.45f, get = { it.cropTop }, set = { p, v -> p.copy(cropTop = v) }),
        Tool("cr", "Vágás · jobb", 0.55f, 1f, get = { it.cropRight }, set = { p, v -> p.copy(cropRight = v) }),
        Tool("cb", "Vágás · alsó", 0.55f, 1f, get = { it.cropBottom }, set = { p, v -> p.copy(cropBottom = v) })
    )
)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun EditScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val p by vm.params.collectAsState()
    val cs = MaterialTheme.colorScheme
    val night = LocalNightMode.current
    var cat by remember { mutableIntStateOf(2) }       // Asztro az alapértelmezett
    var toolIdx by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showHist by remember { mutableStateOf(true) }

    LaunchedEffect(ui.message) { ui.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() } }

    if (!ui.hasImage) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nincs megnyitott kép.", color = cs.onSurfaceVariant)
                TextButton(onClick = onBack) { Text("Vissza a kockákhoz") }
            }
        }
        return
    }

    val tools = CATEGORIES[cat].second
    val tool = tools[toolIdx.coerceIn(0, tools.lastIndex)]

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ----- kép (teljes szélesség, pinch zoom, nyomva tartás = eredeti) -----
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale == 1f) Offset.Zero else offset + pan } }
                .pointerInput(Unit) { detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false }, onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            contentAlignment = Alignment.Center
        ) {
            val bmp = if (showOriginal) ui.originalPreview ?: ui.preview else ui.preview
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = "Előnézet", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                        .let { m -> if (night) m.graphicsLayer(alpha = 0.85f) else m })
            }
            // felső sáv a kép fölött
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.8f))) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = cs.onSurface) }
                Text(if (showOriginal) "EREDETI" else ui.sourceInfo, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                IconButton(onClick = { showExport = true }, modifier = Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.8f))) { Icon(Icons.Filled.Download, contentDescription = "Export", tint = cs.onSurface) }
            }
            if (ui.processing) CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(22.dp), color = cs.onSurfaceVariant, strokeWidth = 2.dp)
            if (showHist) ui.histogram?.let { Histogram(it, Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.55f).background(Color.Black.copy(alpha = 0.35f))) }
        }

        // ----- alsó eszközpanel -----
        Column(Modifier.fillMaxWidth().background(cs.background).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CATEGORIES.forEachIndexed { i, (name, _) ->
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = if (i == cat) cs.onBackground else cs.onSurfaceVariant,
                        modifier = Modifier.pointerInput(i) { detectTapGestures { cat = i; toolIdx = 0 } })
                }
                Spacer(Modifier.size(8.dp))
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                if (tool.options == null) {
                    BigSlider(tool.label, tool.get(p), tool.min, tool.max, tool.fmt,
                        onDragStart = { vm.beginDrag() }, onChange = { v -> vm.previewParams { tool.set(it, v) } }, onDragEnd = { vm.endDrag() })
                } else {
                    Text(tool.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        tool.options.forEach { (lbl, v) -> Pill(lbl, tool.get(p) == v) { vm.commitParams { tool.set(it, v) } } }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tools.forEachIndexed { i, t -> Pill(t.label.substringBefore(" (").substringBefore(" ·"), i == toolIdx) { toolIdx = i } }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row {
                    IconButton(onClick = { vm.undo() }, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Visszavonás", tint = if (ui.canUndo) cs.onSurface else cs.outline) }
                    IconButton(onClick = { vm.redo() }, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Újra", tint = if (ui.canRedo) cs.onSurface else cs.outline) }
                }
                Text(ui.stats, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.pointerInput(Unit) { detectTapGestures { showHist = !showHist } })
                Row {
                    IconButton(onClick = { vm.setNight(!night) }) { Icon(Icons.Filled.Compare, contentDescription = "Éjszakai mód", tint = if (night) cs.primary else cs.onSurfaceVariant) }
                    IconButton(onClick = { vm.resetParams() }) { Icon(Icons.Filled.RestartAlt, contentDescription = "Nullázás", tint = cs.onSurfaceVariant) }
                }
            }
        }
    }

    if (showExport) ExportSheet(vm, ui.exporting) { showExport = false }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun ExportSheet(vm: EditorViewModel, exporting: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var format by remember { mutableStateOf(ExportFormat.JPEG) }
    val mime = when (format) { ExportFormat.JPEG -> "image/jpeg"; ExportFormat.PNG -> "image/png"; ExportFormat.TIFF16 -> "image/tiff" }
    val ext = when (format) { ExportFormat.JPEG -> "jpg"; ExportFormat.PNG -> "png"; ExportFormat.TIFF16 -> "tif" }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> if (uri != null) { vm.export(uri, format); onClose() } }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Export", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("JPEG", format == ExportFormat.JPEG) { format = ExportFormat.JPEG }
                Pill("PNG", format == ExportFormat.PNG) { format = ExportFormat.PNG }
                Pill("TIFF 16 bit", format == ExportFormat.TIFF16) { format = ExportFormat.TIFF16 }
            }
            Text("A teljes felbontású képre fut le minden beállítás; nagy stacknél ez több tíz másodperc.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            androidx.compose.material3.Button(onClick = { save.launch("novaprocess_${System.currentTimeMillis()}.$ext") }, enabled = !exporting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                Text(if (exporting) "Exportálás…" else "Mentés…")
            }
        }
    }
}

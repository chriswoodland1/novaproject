package hu.novaprocess.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.process.ProcessParams
import hu.novaprocess.process.Rotation
import hu.novaprocess.process.StretchMode
import hu.novaprocess.vm.EditorViewModel
import hu.novaprocess.vm.ExportFormat

@Composable
fun EditScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val p by vm.params.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var exportFormat by remember { mutableStateOf(ExportFormat.JPEG) }

    LaunchedEffect(ui.message) { ui.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() } }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeFor(exportFormat))) { uri ->
        if (uri != null) vm.export(uri, exportFormat)
    }

    if (!ui.hasImage) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Előbb stackelj, vagy nyiss meg egy képet a Stack fülön.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // --- előnézet (csípéssel nagyítható) ---
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxWidth().height(300.dp).background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            ui.preview?.let { bmp ->
                Image(bmp.asImageBitmap(), contentDescription = "Előnézet", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            }
            if (ui.processing) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        ui.histogram?.let { Histogram(it) }
        Text(ui.sourceInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))

        TabRow(selectedTabIndex = tab) {
            listOf("Asztro", "Fotó", "Geometria", "Export").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> AstroTab(p) { vm.updateParams(it) }
                1 -> PhotoTab(p) { vm.updateParams(it) }
                2 -> GeometryTab(p) { vm.updateParams(it) }
                else -> ExportTab(exportFormat, ui.exporting, onFormat = { exportFormat = it }) { save.launch("novaprocess_${System.currentTimeMillis()}." + ext(exportFormat)) }
            }
            TextButton(onClick = { vm.resetParams() }) { Text("Minden beállítás visszaállítása") }
        }
    }
}

private fun mimeFor(f: ExportFormat) = when (f) { ExportFormat.JPEG -> "image/jpeg"; ExportFormat.PNG -> "image/png"; ExportFormat.TIFF16 -> "image/tiff" }
private fun ext(f: ExportFormat) = when (f) { ExportFormat.JPEG -> "jpg"; ExportFormat.PNG -> "png"; ExportFormat.TIFF16 -> "tif" }

@Composable
private fun Histogram(h: Array<IntArray>) {
    val colors = if (h.size == 3) listOf(Color(0xFFFF6B6B), Color(0xFF7CF2B0), Color(0xFF6BA8FF)) else listOf(Color(0xFFE8ECF4))
    Canvas(Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF060910))) {
        val maxV = h.maxOf { ch -> ch.max() }.coerceAtLeast(1).toFloat()
        val stepX = size.width / 255f
        h.forEachIndexed { ci, ch ->
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val y = size.height - (kotlin.math.ln(1f + ch[i]) / kotlin.math.ln(1f + maxV)) * size.height
                path.lineTo(i * stepX, y)
            }
            path.lineTo(size.width, size.height); path.close()
            drawPath(path, colors[ci].copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun AstroTab(p: ProcessParams, set: ((ProcessParams) -> ProcessParams) -> Unit) {
    Section("Háttér és színek (lineáris)") {
        Check("Háttér-gradiens kivonás (polinom)", p.backgroundExtraction) { v -> set { it.copy(backgroundExtraction = v) } }
        if (p.backgroundExtraction) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = p.backgroundDegree == 1, onClick = { set { it.copy(backgroundDegree = 1) } }, label = { Text("Sík") })
            FilterChip(selected = p.backgroundDegree == 2, onClick = { set { it.copy(backgroundDegree = 2) } }, label = { Text("Másodfokú") })
        }
        Check("Háttér semlegesítése (csatorna-mediánok)", p.backgroundNeutralize) { v -> set { it.copy(backgroundNeutralize = v) } }
        Check("Fehéregyensúly csillagokra", p.whiteBalanceStars) { v -> set { it.copy(whiteBalanceStars = v) } }
    }
    Section("Stretch") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(StretchMode.NONE to "Nincs", StretchMode.AUTO to "Auto", StretchMode.ASINH to "Asinh", StretchMode.MTF to "MTF").forEach { (m, l) ->
                FilterChip(selected = p.stretchMode == m, onClick = { set { it.copy(stretchMode = m) } }, label = { Text(l) })
            }
        }
        when (p.stretchMode) {
            StretchMode.ASINH -> {
                LabeledSlider("Asinh erősség", p.asinhStrength, 1f, 1000f, { "%.0f".format(it) }) { v -> set { it.copy(asinhStrength = v) } }
                LabeledSlider("Feketepont", p.blackPoint, 0f, 0.2f, { "%.3f".format(it) }) { v -> set { it.copy(blackPoint = v) } }
            }
            StretchMode.AUTO -> {
                LabeledSlider("Cél háttér", p.mtfMidtones, 0.05f, 0.6f) { v -> set { it.copy(mtfMidtones = v) } }
                LabeledSlider("Árnyék vágás (MAD×)", p.mtfShadowClip, 0f, 5f) { v -> set { it.copy(mtfShadowClip = v) } }
            }
            StretchMode.MTF -> {
                LabeledSlider("Cél háttér", p.mtfMidtones, 0.05f, 0.6f) { v -> set { it.copy(mtfMidtones = v) } }
                LabeledSlider("Feketepont", p.blackPoint, 0f, 0.3f, { "%.3f".format(it) }) { v -> set { it.copy(blackPoint = v) } }
            }
            StretchMode.NONE -> LabeledSlider("Feketepont", p.blackPoint, 0f, 0.2f, { "%.3f".format(it) }) { v -> set { it.copy(blackPoint = v) } }
        }
    }
    Section("Csillagok és zaj") {
        LabeledSlider("Csillagcsökkentés", p.starReduction, 0f, 1f) { v -> set { it.copy(starReduction = v) } }
        LabeledSlider("Csillag sugár (px)", p.starRadius.toFloat(), 1f, 5f, { "%.0f".format(it) }) { v -> set { it.copy(starRadius = v.toInt()) } }
        LabeledSlider("Zajszűrés", p.denoise, 0f, 1f) { v -> set { it.copy(denoise = v) } }
        LabeledSlider("Zajszűrés sugár", p.denoiseRadius.toFloat(), 1f, 4f, { "%.0f".format(it) }) { v -> set { it.copy(denoiseRadius = v.toInt()) } }
    }
}

@Composable
private fun PhotoTab(p: ProcessParams, set: ((ProcessParams) -> ProcessParams) -> Unit) {
    Section("Fény") {
        LabeledSlider("Expozíció (EV)", p.exposure, -3f, 3f) { v -> set { it.copy(exposure = v) } }
        LabeledSlider("Kontraszt", p.contrast, -1f, 1f) { v -> set { it.copy(contrast = v) } }
        LabeledSlider("Csúcsfények", p.highlights, -1f, 1f) { v -> set { it.copy(highlights = v) } }
        LabeledSlider("Árnyékok", p.shadows, -1f, 1f) { v -> set { it.copy(shadows = v) } }
        LabeledSlider("Fehérek", p.whites, -1f, 1f) { v -> set { it.copy(whites = v) } }
        LabeledSlider("Feketék", p.blacks, -1f, 1f) { v -> set { it.copy(blacks = v) } }
        LabeledSlider("Gamma", p.gamma, 0.5f, 2f) { v -> set { it.copy(gamma = v) } }
        LabeledSlider("Szintek · fekete", p.levelsBlack, 0f, 0.5f) { v -> set { it.copy(levelsBlack = v) } }
        LabeledSlider("Szintek · fehér", p.levelsWhite, 0.5f, 1f) { v -> set { it.copy(levelsWhite = v) } }
    }
    Section("Szín") {
        LabeledSlider("Hőmérséklet", p.temperature, -1f, 1f) { v -> set { it.copy(temperature = v) } }
        LabeledSlider("Árnyalat", p.tint, -1f, 1f) { v -> set { it.copy(tint = v) } }
        LabeledSlider("Telítettség", p.saturation, -1f, 1f) { v -> set { it.copy(saturation = v) } }
        LabeledSlider("Vibrance", p.vibrance, -1f, 1f) { v -> set { it.copy(vibrance = v) } }
        LabeledSlider("Vörös", p.redBalance, -0.5f, 0.5f) { v -> set { it.copy(redBalance = v) } }
        LabeledSlider("Zöld", p.greenBalance, -0.5f, 0.5f) { v -> set { it.copy(greenBalance = v) } }
        LabeledSlider("Kék", p.blueBalance, -0.5f, 0.5f) { v -> set { it.copy(blueBalance = v) } }
    }
    Section("Részletek") {
        LabeledSlider("Clarity", p.clarity, -1f, 1f) { v -> set { it.copy(clarity = v) } }
        LabeledSlider("Dehaze", p.dehaze, 0f, 1f) { v -> set { it.copy(dehaze = v) } }
        LabeledSlider("Élesítés", p.sharpen, 0f, 1f) { v -> set { it.copy(sharpen = v) } }
    }
}

@Composable
private fun GeometryTab(p: ProcessParams, set: ((ProcessParams) -> ProcessParams) -> Unit) {
    Section("Forgatás / tükrözés") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Rotation.R0 to "0°", Rotation.R90 to "90°", Rotation.R180 to "180°", Rotation.R270 to "270°").forEach { (r, l) ->
                FilterChip(selected = p.rotation == r, onClick = { set { it.copy(rotation = r) } }, label = { Text(l) })
            }
        }
        Check("Vízszintes tükrözés", p.flipH) { v -> set { it.copy(flipH = v) } }
    }
    Section("Vágás (arányos)") {
        LabeledSlider("Bal", p.cropLeft, 0f, 0.45f) { v -> set { it.copy(cropLeft = v) } }
        LabeledSlider("Felső", p.cropTop, 0f, 0.45f) { v -> set { it.copy(cropTop = v) } }
        LabeledSlider("Jobb", p.cropRight, 0.55f, 1f) { v -> set { it.copy(cropRight = v) } }
        LabeledSlider("Alsó", p.cropBottom, 0.55f, 1f) { v -> set { it.copy(cropBottom = v) } }
    }
}

@Composable
private fun ExportTab(format: ExportFormat, exporting: Boolean, onFormat: (ExportFormat) -> Unit, onSave: () -> Unit) {
    Section("Formátum") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = format == ExportFormat.JPEG, onClick = { onFormat(ExportFormat.JPEG) }, label = { Text("JPEG") })
            FilterChip(selected = format == ExportFormat.PNG, onClick = { onFormat(ExportFormat.PNG) }, label = { Text("PNG") })
            FilterChip(selected = format == ExportFormat.TIFF16, onClick = { onFormat(ExportFormat.TIFF16) }, label = { Text("TIFF 16 bit") })
        }
        Text("A teljes felbontású képre fut le az összes beállítás; nagy stacknél ez több tíz másodperc is lehet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSave, enabled = !exporting, modifier = Modifier.fillMaxWidth()) { Text(if (exporting) "Exportálás…" else "Mentés…") }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

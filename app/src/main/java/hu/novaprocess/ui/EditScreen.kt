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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import hu.novaprocess.process.MaskMode
import hu.novaprocess.process.Palette
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
        Tool("bgn", "Háttér semlegesítés", get = { if (!it.backgroundNeutralize) 0f else if (it.neutralizeAfterStretch) 2f else 1f },
            set = { p, v -> p.copy(backgroundNeutralize = v > 0f, neutralizeAfterStretch = v > 1.5f) }, options = listOf("Ki" to 0f, "Stretch előtt" to 1f, "Előtt + után" to 2f)),
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
            options = listOf("Ki" to 0f, "Sík" to 1f, "Másodfokú" to 2f, "RBF (mintapontok)" to 3f)),
        Tool("bgs", "RBF simaság", 0f, 1f, get = { it.bgSmooth }, set = { p, v -> p.copy(bgSmooth = v) }),
        Tool("scnr", "SCNR zöld-elnyomás", 0f, 1f, get = { it.scnr }, set = { p, v -> p.copy(scnr = v) }),
        Tool("hdr", "HDR magkompresszió", 0f, 1f, get = { it.hdrCompress }, set = { p, v -> p.copy(hdrCompress = v) }),
        Tool("hdrr", "HDR sugár", 4f, 60f, { "%.0f".format(it) }, get = { it.hdrRadius.toFloat() }, set = { p, v -> p.copy(hdrRadius = v.toInt()) }),
        Tool("dci", "Dekonvolúció · iteráció", 0f, 30f, { "%.0f".format(it) }, get = { it.deconvIterations.toFloat() }, set = { p, v -> p.copy(deconvIterations = v.toInt()) }),
        Tool("dcs", "Dekonvolúció · PSF szigma", 0.5f, 3f, get = { it.deconvSigma }, set = { p, v -> p.copy(deconvSigma = v) })
    ),
    "Csillag" to listOf(
        Tool("stl", "Csillagtalanítás (külön réteg)", get = { if (it.starless) 1f else 0f }, set = { p, v -> p.copy(starless = v > 0.5f) }, options = listOf("Ki" to 0f, "Be" to 1f)),
        Tool("stlr", "Csillag-eltávolítás sugara", 2f, 12f, { "%.0f".format(it) }, get = { it.starlessRadius.toFloat() }, set = { p, v -> p.copy(starlessRadius = v.toInt()) }),
        Tool("stb", "Csillagok visszakeverése", 0f, 1f, get = { it.starsBlend }, set = { p, v -> p.copy(starsBlend = v) }),
        Tool("neb", "Köd kiemelése (csillagtalan réteg)", 0f, 1f, get = { it.nebulaBoost }, set = { p, v -> p.copy(nebulaBoost = v) }),
        Tool("sr", "Csillagcsökkentés (egyszerű)", 0f, 1f, get = { it.starReduction }, set = { p, v -> p.copy(starReduction = v) }),
        Tool("srr", "Csillag sugár (px)", 1f, 5f, { "%.0f".format(it) }, get = { it.starRadius.toFloat() }, set = { p, v -> p.copy(starRadius = v.toInt()) })
    ),
    "Paletta" to listOf(
        Tool("pal", "Narrowband paletta", get = { it.palette.ordinal.toFloat() }, set = { p, v -> p.copy(palette = Palette.values()[v.toInt()]) },
            options = listOf("Természetes" to 0f, "HOO" to 1f, "SHO-szerű" to 2f, "Foraxx" to 3f)),
        Tool("pals", "Paletta erőssége", 0f, 1f, get = { it.paletteStrength }, set = { p, v -> p.copy(paletteStrength = v) })
    ),
    "Görbék" to listOf(
        Tool("cs", "Görbe · árnyékok", -1f, 1f, get = { it.curveShadows }, set = { p, v -> p.copy(curveShadows = v) }),
        Tool("cm", "Görbe · középtónusok", -1f, 1f, get = { it.curveMidtones }, set = { p, v -> p.copy(curveMidtones = v) }),
        Tool("ch", "Görbe · csúcsfények", -1f, 1f, get = { it.curveHighlights }, set = { p, v -> p.copy(curveHighlights = v) }),
        Tool("clo", "Görbe célja", get = { if (it.curveLuminanceOnly) 1f else 0f }, set = { p, v -> p.copy(curveLuminanceOnly = v > 0.5f) }, options = listOf("RGB" to 0f, "Luminancia" to 1f))
    ),
    "Maszk" to listOf(
        Tool("mm", "Maszk (Fény/Szín/Görbék hatására)", get = { it.maskMode.ordinal.toFloat() }, set = { p, v -> p.copy(maskMode = MaskMode.values()[v.toInt()]) },
            options = listOf("Nincs" to 0f, "Csúcsfények" to 1f, "Árnyékok" to 2f, "Tartomány" to 3f)),
        Tool("ml", "Tartomány · alsó", 0f, 1f, get = { it.maskLow }, set = { p, v -> p.copy(maskLow = v) }),
        Tool("mh", "Tartomány · felső", 0f, 1f, get = { it.maskHigh }, set = { p, v -> p.copy(maskHigh = v) }),
        Tool("mf", "Lágyítás", 0.01f, 0.5f, get = { it.maskFeather }, set = { p, v -> p.copy(maskFeather = v) }),
        Tool("mi", "Invertálás", get = { if (it.maskInvert) 1f else 0f }, set = { p, v -> p.copy(maskInvert = v > 0.5f) }, options = listOf("Ki" to 0f, "Be" to 1f))
    ),
    "Részlet" to listOf(
        Tool("dn", "Zajszűrés", 0f, 1f, get = { it.denoise }, set = { p, v -> p.copy(denoise = v) }),
        Tool("dnr", "Zajszűrés sugár", 1f, 4f, { "%.0f".format(it) }, get = { it.denoiseRadius.toFloat() }, set = { p, v -> p.copy(denoiseRadius = v.toInt()) }),
        Tool("clar", "Clarity", -1f, 1f, get = { it.clarity }, set = { p, v -> p.copy(clarity = v) }),
        Tool("dehaze", "Dehaze", 0f, 1f, get = { it.dehaze }, set = { p, v -> p.copy(dehaze = v) }),
        Tool("sharp", "Élesítés", 0f, 1f, get = { it.sharpen }, set = { p, v -> p.copy(sharpen = v) }),
        Tool("w1", "Wavelet · finom (1 px)", 0f, 3f, get = { it.wavelet1 }, set = { p, v -> p.copy(wavelet1 = v) }),
        Tool("w2", "Wavelet · közepes (2 px)", 0f, 3f, get = { it.wavelet2 }, set = { p, v -> p.copy(wavelet2 = v) }),
        Tool("w3", "Wavelet · durva (4 px)", 0f, 3f, get = { it.wavelet3 }, set = { p, v -> p.copy(wavelet3 = v) }),
        Tool("wd", "Wavelet zajküszöb", 0f, 1f, get = { it.waveletDenoise }, set = { p, v -> p.copy(waveletDenoise = v) }),
        Tool("clahe", "Helyi kontraszt (CLAHE)", 0f, 1f, get = { it.clahe }, set = { p, v -> p.copy(clahe = v) }),
        Tool("clt", "CLAHE csempék", 2f, 16f, { "%.0f".format(it) }, get = { it.claheTiles.toFloat() }, set = { p, v -> p.copy(claheTiles = v.toInt()) }),
        Tool("clc", "CLAHE vágás", 1f, 4f, { "%.1f".format(it) }, get = { it.claheClip }, set = { p, v -> p.copy(claheClip = v) })
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
    var showPresets by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var sampleMode by remember { mutableStateOf(false) }
    val solve by vm.solve.collectAsState()
    var imgBox by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var showHist by remember { mutableStateOf(true) }
    var showStarMap by remember { mutableStateOf(false) }
    val pickCompare = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.loadCompare(uri) }

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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val compare by vm.compare.collectAsState()
    var split by remember { mutableFloatStateOf(0.5f) }

    val imageArea: @Composable (Modifier) -> Unit = { imgMod ->
        Box(
            imgMod.background(Color.Black)
                .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale == 1f) Offset.Zero else offset + pan } }
                .onSizeChanged { imgBox = it }
                .pointerInput(sampleMode) { detectTapGestures(
                    onPress = { if (!sampleMode) { showOriginal = true; tryAwaitRelease(); showOriginal = false } },
                    onDoubleTap = { scale = 1f; offset = Offset.Zero },
                    onTap = { pos -> if (sampleMode) { val b = ui.preview; if (b != null && imgBox.width > 0) {
                        val fit = fitRect(imgBox.width.toFloat(), imgBox.height.toFloat(), b.width.toFloat(), b.height.toFloat())
                        val nx = (pos.x - fit[0]) / fit[2]; val ny = (pos.y - fit[1]) / fit[3]
                        if (nx in 0f..1f && ny in 0f..1f) vm.addBgSample(nx, ny)
                    } } }
                ) },
            contentAlignment = Alignment.Center
        ) {
            val bmp = if (showOriginal) ui.originalPreview ?: ui.preview else ui.preview
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = "Előnézet", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                        .let { m -> if (night) m.graphicsLayer(alpha = 0.85f) else m })
            }
            // A/B: a másik kép balról a csúszkáig, a kép fölé rajzolva
            compare?.let { cb -> if (scale == 1f && bmp != null) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val fit = fitRect(size.width, size.height, bmp.width.toFloat(), bmp.height.toFloat())
                    val cut = fit[0] + fit[2] * split
                    clipRect(left = 0f, top = 0f, right = cut, bottom = size.height) {
                        drawImage(cb.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(fit[0].toInt(), fit[1].toInt()), dstSize = androidx.compose.ui.unit.IntSize(fit[2].toInt(), fit[3].toInt()))
                    }
                    drawLine(Color.White, Offset(cut, fit[1]), Offset(cut, fit[1] + fit[3]), strokeWidth = 2f)
                }
                androidx.compose.material3.Slider(value = split, onValueChange = { split = it }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.7f).padding(bottom = 56.dp))
                Text("A: összehasonlító  |  B: aktuális", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp))
            } }
            // felső sáv a kép fölött
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.8f))) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = cs.onSurface) }
                Text(if (showOriginal) "EREDETI" else ui.sourceInfo, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
                IconButton(onClick = { vm.runAutoEdit() }, modifier = Modifier.clip(CircleShape).background(cs.primary)) { Icon(Icons.Filled.AutoAwesome, contentDescription = "Automata szerkesztés", tint = cs.onPrimary) }
                Spacer(Modifier.size(6.dp))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.8f))) { Icon(Icons.Filled.MoreVert, contentDescription = "Továbbiak", tint = cs.onSurface) }
                    androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Presetek és receptek") }, onClick = { showMenu = false; showPresets = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(if (solve.running) "Plate solve fut…" else "Plate solve (astrometry.net)") }, onClick = { showMenu = false; vm.runSolve() })
                        if (solve.result != null) androidx.compose.material3.DropdownMenuItem(text = { Text(if (solve.show) "Annotációk elrejtése" else "Annotációk mutatása") }, onClick = { showMenu = false; vm.toggleAnnotations() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(if (sampleMode) "Mintapont mód kikapcsolása" else "Háttér-mintapontok (érintéssel)") }, onClick = { showMenu = false; sampleMode = !sampleMode })
                        if (p.bgSamples.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Mintapontok törlése (auto-rács)") }, onClick = { showMenu = false; vm.clearBgSamples() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Vízjel") }, onClick = { showMenu = false; showWatermark = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(if (compare == null) "Összehasonlítás képpel (A/B)…" else "Összehasonlítás kikapcsolása") }, onClick = { showMenu = false; if (compare == null) pickCompare.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) else vm.clearCompare() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Csillagalak-térkép (fókusz/dőlés)") }, onClick = { showMenu = false; showStarMap = true })
                    }
                }
                Spacer(Modifier.size(6.dp))
                IconButton(onClick = { showExport = true }, modifier = Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.8f))) { Icon(Icons.Filled.Download, contentDescription = "Export", tint = cs.onSurface) }
            }
            // overlay: annotációk (plate solve) és háttér-mintapontok
            ui.preview?.let { b -> if (scale == 1f && ((solve.show && solve.result != null) || p.bgSamples.isNotEmpty())) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val fit = fitRect(size.width, size.height, b.width.toFloat(), b.height.toFloat())
                    solve.result?.let { r -> if (solve.show) for (a in r.annotations) {
                        if (a.type == "star" && a.names.firstOrNull()?.startsWith("HD") == true) continue
                        val x = fit[0] + a.x / r.width * fit[2]; val y = fit[1] + a.y / r.height * fit[3]
                        val rad = (a.radius / r.width * fit[2]).coerceAtLeast(10f)
                        drawCircle(Color(0xFF8FE3A8), rad, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        drawContext.canvas.nativeCanvas.drawText(a.names.firstOrNull() ?: "", x + rad + 6f, y + 10f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 28f; setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) })
                    } }
                    var i = 0; while (i + 1 < p.bgSamples.size) {
                        val x = fit[0] + p.bgSamples[i] * fit[2]; val y = fit[1] + p.bgSamples[i + 1] * fit[3]
                        drawCircle(Color(0xFFE8C547), 9f, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        drawCircle(Color(0xFFE8C547), 2.5f, Offset(x, y)); i += 2
                    }
                }
            } }
            if (sampleMode) Text("Mintapont mód: érints a háttérre (${p.bgSamples.size / 2} pont) · kész: ⋯ menü", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE8C547),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp))
            if (solve.running) Text(solve.status, style = MaterialTheme.typography.labelSmall, color = cs.onSurface, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp))
            solve.result?.let { r -> if (solve.show) Text("RA ${hu.novaprocess.solve.PlateSolver.formatRa(r.ra)} · Dec ${hu.novaprocess.solve.PlateSolver.formatDec(r.dec)} · %.2f″/px · %.1f°".format(r.pixScale, r.orientation),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 60.dp).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp)) }
            if (ui.processing) CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(22.dp), color = cs.onSurfaceVariant, strokeWidth = 2.dp)
            if (showHist) ui.histogram?.let { Histogram(it, Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.55f).background(Color.Black.copy(alpha = 0.35f))) }
        }

    }

    val toolsPanel: @Composable (Modifier) -> Unit = { toolsMod ->
        // ----- alsó eszközpanel -----
        Column(toolsMod.background(cs.background).navigationBarsPadding()) {
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

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                imageArea(Modifier.weight(1f).fillMaxSize())
                toolsPanel(Modifier.width(400.dp).fillMaxSize().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                imageArea(Modifier.fillMaxWidth().weight(1f))
                toolsPanel(Modifier.fillMaxWidth())
            }
        }
    }

    if (showExport) ExportSheet(vm, ui.exporting, onWatermark = { showExport = false; showWatermark = true }) { showExport = false }
    if (showPresets) PresetSheet(vm) { showPresets = false }
    if (showWatermark) WatermarkSheet(vm) { showWatermark = false }
    if (showStarMap) StarMapSheet(vm) { showStarMap = false }
    AutoEditSheet(vm)
}

@Composable
private fun PresetSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var version by remember { mutableIntStateOf(0) }
    val user = remember(version) { hu.novaprocess.process.Presets.userPresets(ctx) }
    var newName by remember { mutableStateOf("") }
    val saveRecipe = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.recipeJson().toByteArray()) } }
    }
    val loadRecipe = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()?.let { vm.loadRecipe(it) } ?: false
            Toast.makeText(ctx, if (ok) "Recept betöltve." else "Nem olvasható recept.", Toast.LENGTH_SHORT).show()
            if (ok) onClose()
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Presetek", style = MaterialTheme.typography.headlineMedium)
            Text("Beépített", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hu.novaprocess.process.Presets.builtIns.forEach { pr -> Pill(pr.name, false) { vm.applyPreset(pr.params); onClose() } }
            }
            if (user.isNotEmpty()) {
                Text("Saját", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                user.forEach { pr ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(pr.name, modifier = Modifier.weight(1f).pointerInput(pr.name) { detectTapGestures { vm.applyPreset(pr.params); onClose() } })
                        TextButton(onClick = { hu.novaprocess.process.Presets.delete(ctx, pr.name); version++ }) { Text("Törlés", color = cs.error) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Jelenlegi mentése mint…") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (newName.isNotBlank()) { hu.novaprocess.process.Presets.save(ctx, newName.trim(), vm.params.value); newName = ""; version++ } }) { Text("Mentés") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { saveRecipe.launch("recept_${System.currentTimeMillis()}.nova.json") }) { Text("Recept exportálása (JSON)") }
                TextButton(onClick = { loadRecipe.launch(arrayOf("application/json", "*/*")) }) { Text("Recept betöltése") }
            }
        }
    }
}

/** ContentScale.Fit elrendezés: [x, y, w, h] a dobozon belül. */
private fun fitRect(bw: Float, bh: Float, iw: Float, ih: Float): FloatArray {
    val s = minOf(bw / iw, bh / ih); val w = iw * s; val h = ih * s
    return floatArrayOf((bw - w) / 2f, (bh - h) / 2f, w, h)
}

@Composable
private fun ExportSheet(vm: EditorViewModel, exporting: Boolean, onWatermark: () -> Unit, onClose: () -> Unit) {
    val wm by vm.watermark.collectAsState()
    val cs = MaterialTheme.colorScheme
    var format by remember { mutableStateOf(ExportFormat.JPEG) }
    val mime = when (format) { ExportFormat.JPEG, ExportFormat.FRAMED_JPEG -> "image/jpeg"; ExportFormat.PNG -> "image/png"; ExportFormat.TIFF16 -> "image/tiff"; ExportFormat.FITS32 -> "application/fits" }
    val ext = when (format) { ExportFormat.JPEG, ExportFormat.FRAMED_JPEG -> "jpg"; ExportFormat.PNG -> "png"; ExportFormat.TIFF16 -> "tif"; ExportFormat.FITS32 -> "fits" }
    val cap by vm.caption.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> if (uri != null) { vm.export(uri, format); onClose() } }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Export", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("JPEG", format == ExportFormat.JPEG) { format = ExportFormat.JPEG }
                Pill("PNG", format == ExportFormat.PNG) { format = ExportFormat.PNG }
                Pill("TIFF 16 bit", format == ExportFormat.TIFF16) { format = ExportFormat.TIFF16 }
                Pill("Keretes JPEG", format == ExportFormat.FRAMED_JPEG) { format = ExportFormat.FRAMED_JPEG }
                Pill("FITS 32 bit", format == ExportFormat.FITS32) { format = ExportFormat.FITS32 }
            }
            val sizeP by vm.exportSize.collectAsState()
            if (format != ExportFormat.TIFF16 && format != ExportFormat.FITS32) {
                Text("Méret", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hu.novaprocess.vm.ExportSize.values().forEach { sp -> Pill(sp.label, sizeP == sp) { vm.exportSize.value = sp } }
                }
            }
            if (format == ExportFormat.FRAMED_JPEG) {
                androidx.compose.material3.OutlinedTextField(value = cap.title, onValueChange = { vm.caption.value = cap.copy(title = it) }, label = { Text("Objektum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = cap.date, onValueChange = { vm.caption.value = cap.copy(date = it) }, label = { Text("Dátum") }, singleLine = true, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(value = cap.instrument, onValueChange = { vm.caption.value = cap.copy(instrument = it) }, label = { Text("Eszköz") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = if (cap.frames > 0) cap.frames.toString() else "", onValueChange = { vm.caption.value = cap.copy(frames = it.toIntOrNull() ?: 0) }, label = { Text("Kockák") }, singleLine = true, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(value = if (cap.exposure > 0f) "%.0f".format(cap.exposure) else "", onValueChange = { vm.caption.value = cap.copy(exposure = it.toFloatOrNull() ?: 0f) }, label = { Text("Expozíció (s)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            val ann by vm.exportAnnotations.collectAsState()
            if (vm.solve.value.result != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Annotációk (objektumnevek) a képre", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                androidx.compose.material3.Switch(checked = ann, onCheckedChange = { vm.exportAnnotations.value = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Vízjel: " + when (wm.mode) { hu.novaprocess.process.WmMode.OFF -> "ki"; hu.novaprocess.process.WmMode.TEXT -> "szöveg „${wm.text.take(18)}”"; hu.novaprocess.process.WmMode.IMAGE -> "logó" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                TextButton(onClick = onWatermark) { Text("Beállítás") }
            }
            Text("A teljes felbontású képre fut le minden beállítás; nagy stacknél ez több tíz másodperc.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            androidx.compose.material3.Button(onClick = { save.launch("novaprocess_${System.currentTimeMillis()}.$ext") }, enabled = !exporting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                Text(if (exporting) "Exportálás…" else "Mentés…")
            }
        }
    }
}

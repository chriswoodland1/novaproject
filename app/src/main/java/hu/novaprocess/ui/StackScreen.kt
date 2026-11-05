package hu.novaprocess.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.stack.FrameInfo
import hu.novaprocess.stack.StackMethod
import hu.novaprocess.ui.theme.Dk
import hu.novaprocess.vm.EditorViewModel

private val MIMES = arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")

@Composable
fun StackScreen(vm: EditorViewModel, onOpenEditor: () -> Unit) {
    val ctx = LocalContext.current
    val frames by vm.frames.collectAsState()
    val opts by vm.stackOptions.collectAsState()
    val st by vm.stackState.collectAsState()
    val cs = MaterialTheme.colorScheme
    var sheet by remember { mutableStateOf<String?>(null) }
    var showSeestar by remember { mutableStateOf(false) }
    var showPlanetary by remember { mutableStateOf(false) }

    val pickLights = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            vm.addFrames(uris)
        }
    }
    val pickDarks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(darkUris = it) }
    val pickFlats = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(flatUris = it) }
    val pickBias = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(biasUris = it) }
    val pickSingle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) { vm.openSingle(uri); onOpenEditor() } }

    val good = frames.count { !it.rejected }
    val rejected = frames.size - good

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 190.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item(span = { GridItemSpan(4) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Kockák", style = MaterialTheme.typography.headlineMedium)
                    Text(if (frames.isEmpty()) "nincs kiválasztva" else "$good kiválasztva" + (if (rejected > 0) " · $rejected kizárva" else ""), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (frames.isEmpty()) item(span = { GridItemSpan(4) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Válaszd ki a Seestar _sub FITS kockáit vagy JPEG/PNG sorozatot.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { pickLights.launch(MIMES) }) { Text("Kockák hozzáadása") }
                    OutlinedButton(onClick = { showSeestar = true }) { Text("Tallózás a Seestar-on (Wi-Fi)") }
                    OutlinedButton(onClick = { showPlanetary = true }) { Text("Bolygó / Hold videóból") }
                    TextButton(onClick = { pickSingle.launch(MIMES) }) { Text("Egyetlen kész kép megnyitása") }
                }
            }
            items(frames, key = { it.uri.toString() }) { f -> FrameCell(f) { vm.toggleReject(f.uri) } }
            if (frames.isNotEmpty()) {
                item(span = { GridItemSpan(4) }) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("Felirat: FWHM px · piros keret = kizárt (automatikusan vagy érintésre) · érintés a visszavételhez",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { pickLights.launch(MIMES) }, enabled = !st.running && !st.analyzing) { Text("+ Kockák") }
                            OutlinedButton(onClick = { showSeestar = true }, enabled = !st.running && !st.analyzing) { Text("+ Seestar") }
                            TextButton(onClick = { vm.clearFrames() }, enabled = !st.running) { Text("Lista törlése") }
                        }
                    }
                }
                item(span = { GridItemSpan(4) }) {
                    Column(Modifier.padding(top = 18.dp)) {
                        val cal = buildList {
                            if (opts.darkUris.isNotEmpty()) add("${opts.darkUris.size} dark")
                            if (opts.flatUris.isNotEmpty()) add("${opts.flatUris.size} flat")
                            if (opts.biasUris.isNotEmpty()) add("${opts.biasUris.size} bias")
                        }.joinToString(" · ").ifEmpty { "nincs" }
                        RowItem("Kalibráció", cal) { sheet = "cal" }
                        RowItem("Elvetés", when (opts.method) {
                            StackMethod.MEAN -> "Átlag"; StackMethod.SIGMA_CLIP -> "Sigma-clip %.1f / %.1f".format(opts.sigmaLow, opts.sigmaHigh)
                            StackMethod.WINSORIZED -> "Winsorized %.1f / %.1f".format(opts.sigmaLow, opts.sigmaHigh) }) { sheet = "rej" }
                        RowItem("Igazítás", if (opts.alignFrames) "Csillag · forgatással" else "Ki") { sheet = "align" }
                        RowItem("Felbontás", (if (opts.binning == 2) "2×2 binning" else "Teljes") + (if (opts.drizzle == 2) " · drizzle 2×" else "")) { sheet = "res" }
                    }
                }
            }
        }

        // alsó sáv: becslés + Stack gomb (a lebegő nav fölött)
        if (frames.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(cs.background).padding(start = 20.dp, end = 20.dp, bottom = 96.dp, top = 8.dp)) {
            if (st.analyzing) {
                LinearProgressIndicator(progress = { st.analyzed.toFloat() / (frames.size + (st.analyzed - frames.size).coerceAtLeast(0)).coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Text("Kockák elemzése… ${st.analyzed}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } else if (st.running) {
                val p = st.progress
                LinearProgressIndicator(progress = { if (p != null && p.total > 0) p.current.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${p?.phase ?: "Indítás"} · ${p?.current ?: 0}/${p?.total ?: 0}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("Megszakítás", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.clickable { vm.cancelStack() })
                }
            } else {
                st.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Becsült idő", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        val secs = good * (if (opts.binning == 2) 0.7 else 2.4) * (if (opts.method == StackMethod.MEAN) 1 else 2) * (if (opts.drizzle == 2) 1.8 else 1.0)
                        Text("≈ ${(secs / 60).toInt()} perc ${(secs % 60).toInt()} mp", style = MaterialTheme.typography.titleMedium)
                    }
                    if (st.log.isNotEmpty()) TextButton(onClick = onOpenEditor) { Text("Szerkesztő →") }
                    Button(onClick = { vm.startStack() }, enabled = good > 0, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary),
                        modifier = Modifier.height(52.dp)) { Text("Stack", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    if (showPlanetary) PlanetarySheet(vm, onDone = onOpenEditor, onClose = { showPlanetary = false })
    if (showSeestar) SeestarSheet(onPicked = { vm.addFrames(it) }, onClose = { showSeestar = false })

    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sheet) {
                "cal" -> {
                    Text("Kalibráló kockák", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDarks.launch(MIMES) }) { Text("Dark ${opts.darkUris.size}") }
                        OutlinedButton(onClick = { pickFlats.launch(MIMES) }) { Text("Flat ${opts.flatUris.size}") }
                        OutlinedButton(onClick = { pickBias.launch(MIMES) }) { Text("Bias ${opts.biasUris.size}") }
                    }
                    CheckRow("Mesterkockák mediánnal (memóriától függően)", opts.medianMasters) { vm.stackOptions.value = opts.copy(medianMasters = it) }
                    CheckRow("Hot pixel javítás a master darkból", opts.hotPixelFix) { vm.stackOptions.value = opts.copy(hotPixelFix = it) }
                    if (opts.darkUris.isNotEmpty() || opts.flatUris.isNotEmpty() || opts.biasUris.isNotEmpty())
                        TextButton(onClick = { vm.stackOptions.value = opts.copy(darkUris = emptyList(), flatUris = emptyList(), biasUris = emptyList()) }) { Text("Kalibrálók törlése") }
                }
                "rej" -> {
                    Text("Pixel-elvetés", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Sigma-clip", opts.method == StackMethod.SIGMA_CLIP) { vm.stackOptions.value = opts.copy(method = StackMethod.SIGMA_CLIP) }
                        Pill("Winsorized", opts.method == StackMethod.WINSORIZED) { vm.stackOptions.value = opts.copy(method = StackMethod.WINSORIZED) }
                        Pill("Átlag", opts.method == StackMethod.MEAN) { vm.stackOptions.value = opts.copy(method = StackMethod.MEAN) }
                    }
                    if (opts.method != StackMethod.MEAN) {
                        SmallSlider("Sigma alsó", opts.sigmaLow, 1f, 6f) { vm.stackOptions.value = opts.copy(sigmaLow = it) }
                        SmallSlider("Sigma felső", opts.sigmaHigh, 1f, 6f) { vm.stackOptions.value = opts.copy(sigmaHigh = it) }
                    }
                    Text("Sigma-clip: a kiugró pixelek (műhold, kozmikus sugár, repülő) kimaradnak. Winsorized: a határra húzza őket – kevés kockánál stabilabb.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                "align" -> {
                    Text("Igazítás", style = MaterialTheme.typography.headlineMedium)
                    CheckRow("Csillag-alapú regisztráció (eltolás + forgatás)", opts.alignFrames) { vm.stackOptions.value = opts.copy(alignFrames = it) }
                    SmallSlider("Min. csillag-egyezés", opts.minMatches.toFloat(), 3f, 20f, { "%.0f".format(it) }) { vm.stackOptions.value = opts.copy(minMatches = it.toInt()) }
                }
                "res" -> {
                    Text("Felbontás", style = MaterialTheme.typography.headlineMedium)
                    CheckRow("2×2 binning (gyors, fele felbontás)", opts.binning == 2) { vm.stackOptions.value = opts.copy(binning = if (it) 2 else 1, drizzle = if (it) 1 else opts.drizzle) }
                    CheckRow("Drizzle 2× (kétszeres kimenet, sok kockánál ad részletet; lassabb, 4× memória)", opts.drizzle == 2) { vm.stackOptions.value = opts.copy(drizzle = if (it) 2 else 1, binning = if (it) 1 else opts.binning) }
                }
            }
        }
    }
}

@Composable
private fun FrameCell(f: FrameInfo, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.aspectRatio(1f).background(Dk.Panel).let { if (f.rejected) it.border(2.dp, cs.error) else it }.clickable(onClick = onToggle)
    ) {
        f.thumb?.let { Image(it.asImageBitmap(), contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            if (f.rejected && f.reason.isNotEmpty() && f.fwhm == 0f) "✕" else if (f.fwhm > 0f) "%.1f".format(f.fwhm) else "–",
            style = MaterialTheme.typography.labelSmall, color = if (f.rejected) cs.error else cs.onSurface,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
        )
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.height(0.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

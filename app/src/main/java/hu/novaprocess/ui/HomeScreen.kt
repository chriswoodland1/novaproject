package hu.novaprocess.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.stack.StackMethod
import hu.novaprocess.ui.theme.Panel
import hu.novaprocess.vm.EditorViewModel

@Composable
fun HomeScreen(vm: EditorViewModel, onOpenEditor: () -> Unit) {
    val ctx = LocalContext.current
    val frames by vm.frames.collectAsState()
    val opts by vm.stackOptions.collectAsState()
    val st by vm.stackState.collectAsState()

    val pickLights = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            vm.frames.value = vm.frames.value + uris
        }
    }
    val pickDarks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(darkUris = it) }
    val pickFlats = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(flatUris = it) }
    val pickBias = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.stackOptions.value = opts.copy(biasUris = it) }
    val pickSingle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { vm.openSingle(uri); onOpenEditor() }
    }
    val mimes = arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Stackelés", style = MaterialTheme.typography.headlineMedium)
        Text("Nyers kockák (FITS / JPEG / PNG) → regisztráció → sigma-clip átlag. A Seestar _sub FITS-eit közvetlenül olvassa.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("Light kockák · ${frames.size} db") {
            if (frames.isNotEmpty()) {
                frames.take(6).forEach { Text("• " + ImageLoader.displayName(ctx, it), style = MaterialTheme.typography.bodySmall) }
                if (frames.size > 6) Text("… és még ${frames.size - 6}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickLights.launch(mimes) }, enabled = !st.running) { Text("Kockák hozzáadása") }
                if (frames.isNotEmpty()) TextButton(onClick = { vm.frames.value = emptyList() }, enabled = !st.running) { Text("Törlés") }
            }
        }

        Section("Kalibráló kockák (opcionális)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDarks.launch(mimes) }) { Text("Dark ${opts.darkUris.size}") }
                OutlinedButton(onClick = { pickFlats.launch(mimes) }) { Text("Flat ${opts.flatUris.size}") }
                OutlinedButton(onClick = { pickBias.launch(mimes) }) { Text("Bias ${opts.biasUris.size}") }
            }
        }

        Section("Módszer") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StackMethod.values().forEach { m ->
                    FilterChip(selected = opts.method == m, onClick = { vm.stackOptions.value = opts.copy(method = m) },
                        label = { Text(when (m) { StackMethod.MEAN -> "Átlag"; StackMethod.SIGMA_CLIP -> "Sigma-clip"; StackMethod.WINSORIZED -> "Winsorized" }) })
                }
            }
            if (opts.method != StackMethod.MEAN) {
                LabeledSlider("Sigma alsó", opts.sigmaLow, 1f, 6f) { vm.stackOptions.value = opts.copy(sigmaLow = it) }
                LabeledSlider("Sigma felső", opts.sigmaHigh, 1f, 6f) { vm.stackOptions.value = opts.copy(sigmaHigh = it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = opts.alignFrames, onCheckedChange = { vm.stackOptions.value = opts.copy(alignFrames = it) })
                Text("Kockák igazítása csillagok alapján (eltolás + forgatás)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = opts.binning == 2, onCheckedChange = { vm.stackOptions.value = opts.copy(binning = if (it) 2 else 1) })
                Text("2×2 binning (gyorsabb, fele felbontás, kevesebb memória)")
            }
        }

        if (st.running) {
            val p = st.progress
            Section(p?.phase ?: "Indítás…") {
                LinearProgressIndicator(progress = { if (p != null && p.total > 0) p.current.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${p?.current ?: 0} / ${p?.total ?: 0}  ${p?.detail ?: ""}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.cancelStack() }) { Text("Megszakítás") }
            }
        } else {
            Button(onClick = { vm.startStack() }, enabled = frames.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stackelés indítása") }
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (st.log.isNotEmpty()) Section("Napló") {
                st.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onOpenEditor) { Text("Megnyitás a szerkesztőben") }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pickSingle.launch(mimes) }, modifier = Modifier.fillMaxWidth()) { Text("Egyetlen kép megnyitása (kész stack / JPEG)") }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, min: Float, max: Float, format: (Float) -> String = { "%.2f".format(it) }, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

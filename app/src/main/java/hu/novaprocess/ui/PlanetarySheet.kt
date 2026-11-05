package hu.novaprocess.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/** Bolygó / Hold / Nap: videóból vagy képsorozatból lucky imaging. */
@Composable
fun PlanetarySheet(vm: EditorViewModel, onDone: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val opts by vm.planetaryOptions.collectAsState()
    val running by vm.planetaryRunning.collectAsState()
    val progress by vm.planetaryProgress.collectAsState()
    val error by vm.planetaryError.collectAsState()
    var video by remember { mutableStateOf<Uri?>(null) }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var started by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) { video = it; images = emptyList() } }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) { images = it; video = null } }

    LaunchedEffect(running) { if (started && !running && error == null) { onDone(); onClose() } }

    ModalBottomSheet(onDismissRequest = { if (!running) onClose() }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bolygó / Hold / Nap", style = MaterialTheme.typography.headlineMedium)
            Text("Lucky imaging: a videó (vagy képsorozat) kockáit élesség szerint rangsorolja, a legjobbakat igazítja és átlagolja. Az élesítést utána a szerkesztő Részlet → Wavelet csúszkáival végezd.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, enabled = !running) { Text(if (video != null) "Videó ✓" else "Videó (MP4)") }
                OutlinedButton(onClick = { pickImages.launch(arrayOf("image/*", "application/fits", "*/*")) }, enabled = !running) { Text(if (images.isNotEmpty()) "${images.size} kép ✓" else "Képsorozat") }
            }
            SmallSlider("Kockák a videóból", opts.maxFrames.toFloat(), 20f, 600f, { "%.0f".format(it) }) { vm.planetaryOptions.value = opts.copy(maxFrames = it.toInt()) }
            SmallSlider("Legjobb kockák aránya", opts.keepPercent.toFloat(), 5f, 100f, { "%.0f %%".format(it) }) { vm.planetaryOptions.value = opts.copy(keepPercent = it.toInt()) }
            SmallSlider("Munkafelbontás (hosszabb oldal)", opts.workSide.toFloat(), 600f, 2400f, { "%.0f px".format(it) }) { vm.planetaryOptions.value = opts.copy(workSide = it.toInt()) }
            progress?.let { p ->
                LinearProgressIndicator(progress = { if (p.total > 0) p.current.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${p.phase} · ${p.current}/${p.total}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
            if (running) TextButton(onClick = { vm.cancelPlanetary() }) { Text("Megszakítás") }
            else Button(onClick = { started = true; vm.startPlanetary(video, images) }, enabled = video != null || images.size >= 2, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stack") }
        }
    }
}

package hu.novaprocess.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/** Egygombos pipeline és sor: elemzés → stack → automata szerkesztés → export a kiválasztott mappába. */
@Composable
fun PipelineSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val st by vm.pipeline.collectAsState()
    val frames by vm.frames.collectAsState()
    val prefs = ctx.getSharedPreferences("nova_ui", 0)
    var outTree by remember { mutableStateOf(prefs.getString("pipeline_out", null)?.let { Uri.parse(it) }) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("pipeline_out", uri.toString()).apply(); outTree = uri
        }
    }
    val addFrames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            val name = hu.novaprocess.image.ImageLoader.displayName(ctx, uris[0]).substringBefore("_sub").substringBefore("-sub").substringBeforeLast('.')
            vm.pipeline.value = st.copy(jobs = st.jobs + EditorViewModel.PipelineJob("$name (${uris.size})", uris))
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!st.running) onClose() }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pipeline · sor", style = MaterialTheme.typography.headlineMedium)
            Text("Minden munkára: kocka-elemzés és kizárás → stack (a Kockák képernyő beállításaival) → automata szerkesztés → JPEG + keretes JPEG export vízjellel a kiválasztott mappába. Az eredmények a Könyvtárban is megjelennek.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Kimeneti mappa: " + (outTree?.lastPathSegment?.substringAfterLast(':') ?: "nincs"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickTree.launch(null) }) { Text("Választás") }
            }
            HorizontalDivider(color = cs.outline)
            Text("Sor (${st.jobs.size})", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            st.jobs.forEachIndexed { i, j ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text((if (st.running && i == 0) "▶ " else "") + j.name, style = MaterialTheme.typography.bodyMedium)
                    if (!st.running) TextButton(onClick = { vm.removeJob(i) }) { Text("Törlés", color = cs.error) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addFrames.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) }, enabled = !st.running) { Text("+ Kockák sorba") }
                if (frames.isNotEmpty()) OutlinedButton(onClick = { vm.enqueueCurrentFrames() }, enabled = !st.running) { Text("+ Jelenlegi kockák") }
            }
            if (st.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${st.current} · ${st.detail}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            st.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
            if (st.done.isNotEmpty()) {
                Text("Kész", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                st.done.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
            }
            Button(onClick = { outTree?.let { vm.runPipeline(it) } }, enabled = !st.running && st.jobs.isNotEmpty() && outTree != null, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (st.running) "Fut…" else "Sor indítása (${st.jobs.size} munka)")
            }
            Text("Tipp: tartsd töltőn és ne zárd be az appot; a feldolgozás előtérben fut.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

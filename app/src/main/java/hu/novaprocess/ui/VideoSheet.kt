package hu.novaprocess.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.novaprocess.video.VideoFormat
import hu.novaprocess.video.VideoOutput
import hu.novaprocess.vm.EditorViewModel

/** Videó készítése: forrás (kockák / szerkesztés lépései / élő felvétel), formátum, hossz, effektek, MP4 vagy GIF. */
@Composable
fun VideoSheet(vm: EditorViewModel, initial: EditorViewModel.VideoKind? = null, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val kinds = remember { vm.availableVideoKinds() }
    var kind by remember { mutableStateOf(initial?.takeIf { it in kinds } ?: kinds.firstOrNull()) }
    val o by vm.videoOptions.collectAsState()
    val st by vm.buildUp.collectAsState()
    val name = "novaprocess_${System.currentTimeMillis()}"
    val saveMp4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri -> if (uri != null) kind?.let { vm.makeVideo(it, uri) } }
    val saveGif = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri -> if (uri != null) kind?.let { vm.makeVideo(it, uri) } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Videó", style = MaterialTheme.typography.headlineMedium)
            if (kinds.isEmpty()) { Text("Nincs forrás: tölts be kockákat, nyiss meg egy képet, vagy rögzíts élő stacket a Seestar lapon.", color = cs.onSurfaceVariant); return@Column }
            Text("Forrás", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k -> Pill(k.label, kind == k) { kind = k } }
            }
            Text(when (kind) {
                EditorViewModel.VideoKind.BUILD_UP -> "A kockák időrendben összeadva: 1 → 2 → 3 → 5 → 8 … → mind, a jelenlegi szerkesztéssel; a zaj csökkenése grafikonon."
                EditorViewModel.VideoKind.EDIT_STEPS -> "Egy kész képből (pl. a Seestar Stacked FITS-éből): nyers adat → nyújtás → háttér → színek → zajszűrés → kész kép."
                EditorViewModel.VideoKind.LIVE -> "A Seestar élő stackjének rögzített frissítései, időbélyeggel."
                null -> ""
            }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            Text("Formátum", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoFormat.values().forEach { f -> Pill(f.label, o.format == f) { vm.videoOptions.value = o.copy(format = f) } }
                VideoOutput.values().forEach { f -> Pill(f.label, o.output == f) { vm.videoOptions.value = o.copy(output = f) } }
            }
            Text("Hossz", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Auto", 10 to "10 s", 20 to "20 s", 30 to "30 s").forEach { (v, l) -> Pill(l, o.lengthSec == v) { vm.videoOptions.value = o.copy(lengthSec = v) } }
            }
            Text("Effektek", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Előtte–utána csúszka", o.beforeAfter) { vm.videoOptions.value = o.copy(beforeAfter = !o.beforeAfter) }
                Pill("Ráközelítés a tárgyra", o.zoom) { vm.videoOptions.value = o.copy(zoom = !o.zoom) }
                Pill("Számláló + zaj-grafikon", o.stats) { vm.videoOptions.value = o.copy(stats = !o.stats) }
            }
            Text("A cím, a kontextus-sor (katalógus) és a logó a megosztás-kártya / vízjel beállításaiból jön. A ráközelítés a katalógus vágási javaslatát használja, ha van.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            if (st.running) {
                LinearProgressIndicator(progress = { if (st.total > 0) st.current.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${st.phase} ${st.current}/${st.total}", style = MaterialTheme.typography.bodySmall)
            }
            st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Hiba")) cs.error else cs.onSurfaceVariant) }
            Button(onClick = { if (o.output == VideoOutput.GIF) saveGif.launch("$name.gif") else saveMp4.launch("$name.mp4") },
                enabled = kind != null && !st.running, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (st.running) "Készül…" else "Videó készítése és mentése")
            }
        }
    }
}

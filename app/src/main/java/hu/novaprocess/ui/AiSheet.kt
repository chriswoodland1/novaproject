package hu.novaprocess.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import hu.novaprocess.ai.AiModels
import hu.novaprocess.vm.EditorViewModel

/** MI-eszközök: GraXpert háttér és zajszűrés (letölthető modellek), AstroSleuth részletjavító (beépített). */
@Composable
fun AiSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val installed by AiModels.installed.collectAsState()
    val dl by AiModels.download.collectAsState()
    val st by vm.ai.collectAsState()
    val sh by vm.aiSharpen.collectAsState()
    val sample by vm.aiSample.collectAsState()
    val p by vm.params.collectAsState()
    var dnStrength by remember { mutableFloatStateOf(0.5f) }
    var dnFast by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = { if (!st.running) onClose() }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MI-eszközök", style = MaterialTheme.typography.headlineMedium)
            Text("Nyílt forrású asztro-MI modellek a telefonon, internet nélkül futtatva (a GraXpert modelleket egyszer le kell tölteni).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            // ---- modellek
            AiModels.Model.values().forEach { m ->
                val ok = m in installed
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium)
                        Text((if (ok) (if (m.asset != null) "beépített" else "letöltve · ${AiModels.sizeOnDisk(m) / 1048576} MB") else "~${m.approxMb} MB letöltés") + " · ${m.license}",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    when {
                        dl.model == m && dl.error == null -> TextButton(onClick = {}, enabled = false) { Text(if (dl.total > 0) "${dl.done * 100 / dl.total} %" else "…") }
                        !ok && m.repo != null -> TextButton(onClick = { vm.aiFetch(m) }, enabled = dl.model == null || dl.error != null) { Text("Letöltés") }
                        ok && m.asset == null -> TextButton(onClick = { AiModels.delete(m) }) { Text("Törlés", color = cs.error) }
                    }
                }
                if (dl.model == m) {
                    if (dl.error != null) Text("Letöltési hiba: ${dl.error} (Wi-Fi ajánlott; újrapróbálva onnan folytatja)", style = MaterialTheme.typography.labelSmall, color = cs.error)
                    else LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider()

            // ---- MI háttér
            Text("MI háttér-kivonás (GraXpert)", style = MaterialTheme.typography.titleSmall)
            Text("A fényszennyezés és a Hold okozta égbolt-glow-t egy neurális háló becsüli meg – a photon-finish is ezt használja. Bekapcsolva az automata (✦) is ezt alkalmazza.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            val bgOn = p.backgroundExtraction && p.backgroundDegree >= 4
            Button(onClick = { vm.commitParams { it.copy(backgroundExtraction = !bgOn, backgroundDegree = if (bgOn) 2 else 4) } }, enabled = AiModels.Model.GRAXPERT_BGE in installed, shape = CircleShape) {
                Text(if (bgOn) "MI háttér kikapcsolása" else "MI háttér bekapcsolása")
            }
            HorizontalDivider()

            // ---- MI-zajszűrés
            Text("MI-zajszűrés a lineáris képen (GraXpert)", style = MaterialTheme.typography.titleSmall)
            Text("A teljes felbontású, még nem nyújtott képen fut, 256 px-es csempékben (mint a GraXpert asztali program). Telefonon percekig tarthat – a háttérben is fut, értesítést kapsz. Visszavonható.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            SmallSlider("Erősség", dnStrength, 0.1f, 1f, { "%.0f %%".format(it * 100) }) { dnStrength = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Gyors (~2,3× kevesebb csempe)", dnFast) { dnFast = true }
                Pill("Pontos (GraXpert-azonos)", !dnFast) { dnFast = false }
            }
            val prov = AiModels.bestProvider(AiModels.Model.GRAXPERT_DENOISE)
            val est = vm.aiDenoiseEstimate(dnFast)
            Text(if (prov == null) "Első indításkor lemérem a telefon gyorsítóit (NNAPI / XNNPACK / CPU) – ez ~1 perc, utána a leggyorsabbal fut."
                 else "Gyorsító: ${prov.label} · ${AiModels.tileMs(AiModels.Model.GRAXPERT_DENOISE)} ms / csempe" + (est?.let { " · becsült idő ~${it / 60} perc ${it % 60} mp" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            if (prov != null) TextButton(onClick = { vm.aiBenchmark() }, enabled = !st.running && AiModels.Model.GRAXPERT_DENOISE in installed) { Text("Gyorsítók újramérése") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.aiDenoise(dnStrength, dnFast) }, enabled = AiModels.Model.GRAXPERT_DENOISE in installed && !st.running, shape = CircleShape) { Text("MI-zajszűrés indítása") }
                if (vm.canUndoAiDenoise) OutlinedButton(onClick = { vm.undoAiDenoise() }, enabled = !st.running) { Text("Visszavonás") }
            }
            HorizontalDivider()

            // ---- AstroSleuth
            Text("MI-részletjavítás (AstroSleuth FAST)", style = MaterialTheme.typography.titleSmall)
            Text("A kész (nyújtott) képen: a modell 4×-esen újrarajzolja, majd vissza az eredeti méretre → élesebb csillagok és struktúra, kevesebb zaj. Exportáláskor fut (JPEG/PNG/kártya).",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (sh.enabled) "Exportnál: BE" else "Exportnál: ki", sh.enabled) { vm.aiSharpen.value = sh.copy(enabled = !sh.enabled) }
                Pill(if (sh.fast) "Gyors (½ felbontáson)" else "Teljes felbontás (lassú)", sh.fast) { vm.aiSharpen.value = sh.copy(fast = !sh.fast) }
            }
            SmallSlider("Erősség", sh.strength, 0.1f, 1f, { "%.0f %%".format(it * 100) }) { vm.aiSharpen.value = sh.copy(strength = it) }
            TextButton(onClick = { vm.aiSharpenSample() }, enabled = !st.running) { Text("Minta a kép közepéből (előtte / utána)") }
            sample?.let { (a, b) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) { Image(a.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(6.dp))); Text("Előtte", style = MaterialTheme.typography.labelSmall) }
                    Column(Modifier.weight(1f)) { Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(6.dp))); Text("AstroSleuth", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // ---- állapot
            if (st.running) {
                LinearProgressIndicator(progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${st.phase} · ${st.done} / ${st.total}" + (if (st.etaSec >= 0) " · hátra ~${st.etaSec / 60} perc ${st.etaSec % 60} mp" else ""), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.aiCancel() }) { Text("Megszakítás", color = cs.error) }
            }
            st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Hiba")) cs.error else cs.onSurfaceVariant) }
            Text("Források: GraXpert (graxpert.com, modellek CC BY-NC-SA 4.0) · AstroSleuth (github.com/Aveygo/AstroSleuth, GPL-2).", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Button(onClick = onClose, enabled = !st.running, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("Kész") }
        }
    }
}

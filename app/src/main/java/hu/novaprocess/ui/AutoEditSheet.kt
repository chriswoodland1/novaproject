package hu.novaprocess.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/** Az automata szerkesztés eredménye: mit ismert fel, mit miért állított be; Alkalmaz / Mégse. */
@Composable
fun AutoEditSheet(vm: EditorViewModel) {
    val st by vm.auto.collectAsState()
    val cs = MaterialTheme.colorScheme
    if (!st.running && st.result == null && st.error == null) return
    ModalBottomSheet(onDismissRequest = { if (!st.running) vm.dismissAuto() }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Automata szerkesztés", style = MaterialTheme.typography.headlineMedium)
            when {
                st.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
                    Text(if (st.result != null) "Változatok készítése (photon-finish, Siril, Seestar-szerű, GHS)…" else "Elemzés: háttér, gradiens, színek, csillagok, kiterjedt jel… majd próbafeldolgozás és finomhangolás.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                st.error != null -> Text("Hiba: ${st.error}", color = cs.error)
                else -> {
                    val r = st.result!!
                    val a = r.analysis
                    Text("Felismert objektum: ${a.type.label}", style = MaterialTheme.typography.titleMedium)
                    Text("Csillagok: ${a.stars} · FWHM %.1f px · gradiens %.0fσ · kiterjedt jel %.1f%% (%s) · dinamika %.0fσ".format(a.fwhm, a.gradient, a.extendedFrac * 100, a.extendedHue, a.dynRange),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    vm.primaryObject?.let { o -> Text("Katalógus: ${o.title} · ${o.typeLabel} · ${o.constellationHu}", style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                    if (st.candidates.size > 1) {
                        Text("Válassz stílust – az app megjegyzi, mit szoktál választani ennél a típusnál", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            st.candidates.forEachIndexed { i, c ->
                                val sel = i == st.selected
                                Column(Modifier.width(150.dp).border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(10.dp)).clickable { vm.selectCandidate(i) }.padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.fillMaxWidth().height(150.dp).background(Color.Black, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                        st.thumbs.getOrNull(i)?.let { Image(it.asImageBitmap(), contentDescription = c.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                            ?: CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                                    }
                                    Text(c.label, style = MaterialTheme.typography.labelMedium, color = if (sel) cs.primary else cs.onSurface)
                                    val n = st.picks[c.key] ?: 0
                                    if (n > 0) Text("★ eddig ${n}× választottad", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE8C547))
                                }
                            }
                        }
                        st.candidates.getOrNull(st.selected)?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    }
                    var expanded by remember(r) { mutableStateOf(false) }
                    val shown = if (expanded) r.notes else r.notes.take(4)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Miért ezt választotta (${r.notes.size} lépés)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Kevesebb" else "Mind") }
                    }
                    shown.forEach { n -> NoteRow(n) }
                    Text("Alkalmazás után minden csúszka kézzel tovább finomítható; a Visszavonás gombbal az előző állapot visszaáll.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.dismissAuto() }) { Text("Mégse") }
                        TextButton(onClick = { vm.saveAutoAsPreset()?.let { android.widget.Toast.makeText(ctx, "Preset mentve: $it", android.widget.Toast.LENGTH_SHORT).show() } }) { Text("Preset") }
                        Button(onClick = { vm.applyAuto() }, shape = CircleShape, modifier = Modifier.weight(1f).height(50.dp)) { Text("Alkalmazás") }
                    }
                }
            }
        }
    }
}

/** Egy döntés sora: a rövid címke látszik, érintésre a teljes magyarázat kinyílik. */
@Composable
private fun NoteRow(note: String) {
    val cs = MaterialTheme.colorScheme
    var open by remember(note) { mutableStateOf(false) }
    val head = note.substringBefore(":").let { if (it.length < note.length && it.length <= 48) it else note.take(60) + if (note.length > 60) "…" else "" }
    val hasMore = head.length < note.length
    Column(Modifier.fillMaxWidth().let { if (hasMore) it.clickable { open = !open } else it }) {
        Text((if (hasMore) (if (open) "▾ " else "▸ ") else "• ") + (if (open || !hasMore) note else head), style = MaterialTheme.typography.bodyMedium, color = if (open || !hasMore) cs.onSurface else cs.onSurface.copy(alpha = 0.85f))
    }
}

package hu.novaprocess.ui

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
                    Text("Elemzés: háttér, gradiens, színek, csillagok, kiterjedt jel… majd próbafeldolgozás és finomhangolás.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                st.error != null -> Text("Hiba: ${st.error}", color = cs.error)
                else -> {
                    val r = st.result!!
                    val a = r.analysis
                    Text("Felismert objektum: ${a.type.label}", style = MaterialTheme.typography.titleMedium)
                    Text("Csillagok: ${a.stars} · FWHM %.1f px · gradiens %.0fσ · kiterjedt jel %.1f%% (%s) · dinamika %.0fσ".format(a.fwhm, a.gradient, a.extendedFrac * 100, a.extendedHue, a.dynRange),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("Döntések", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("Alkalmazás után minden csúszka kézzel tovább finomítható; a Visszavonás gombbal az előző állapot visszaáll.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { vm.dismissAuto() }) { Text("Mégse") }
                        Button(onClick = { vm.applyAuto() }, shape = CircleShape, modifier = Modifier.weight(1f).height(50.dp)) { Text("Alkalmazás") }
                    }
                }
            }
        }
    }
}

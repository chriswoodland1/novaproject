package hu.novaprocess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.BuildConfig
import hu.novaprocess.update.UpdateManager
import hu.novaprocess.vm.EditorViewModel

@Composable
fun SettingsScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val night by vm.nightMode.collectAsState()
    var repo by remember { mutableStateOf(UpdateManager.repo(ctx)) }
    var trigger by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 120.dp)) {
        Text("Beállítások", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 14.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Éjszakai mód", style = MaterialTheme.typography.titleMedium)
                Text("Vörös felület a sötétadaptált szemnek, a kép kicsit tompítva", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = night, onCheckedChange = { vm.setNight(it) })
        }
        HorizontalDivider(color = cs.outline)

        RowItem("Verzió", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Indításkor legfeljebb 6 óránként ellenőrzi a GitHub Releases-t; új verziót engedély után tölt le és telepít.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { trigger++ }) { Text("Keresés most") }
        }
        HorizontalDivider(color = cs.outline)

        Text("Frissítési forrás", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub repo (tulajdonos/név)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { UpdateManager.setRepo(ctx, repo) }, modifier = Modifier.padding(top = 8.dp)) { Text("Mentés") }

        Text("Tippek", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
        Text("• A szerkesztőben a képet nyomva tartva az eredetit látod; dupla érintés = nagyítás vissza.\n" +
             "• A kockák rácsában a piros keretes kockák kimaradnak; érintésre visszaveheted őket.\n" +
             "• Drizzle 2× csak sok (50+) kockánál és teljes felbontásban ad látható részletet.\n" +
             "• 16 bites TIFF export a további szerkesztéshez (Siril, GIMP, Lightroom).",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    UpdatePrompt(auto = false, manualTrigger = trigger)
}

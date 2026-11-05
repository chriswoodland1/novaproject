package hu.novaprocess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.BuildConfig
import hu.novaprocess.update.UpdateManager

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var repo by remember { mutableStateOf(UpdateManager.repo(ctx)) }
    var trigger by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Beállítások", style = MaterialTheme.typography.headlineMedium)

        Section("Verzió") {
            Text("NovaProcess ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Text("Az app indításkor legfeljebb 6 óránként ellenőrzi a GitHub Releases-t, és engedély után tölti le és telepíti az új APK-t.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { trigger++ }) { Text("Frissítés keresése most") }
        }

        Section("Frissítési forrás") {
            OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub repo (tulajdonos/név)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { UpdateManager.setRepo(ctx, repo) }) { Text("Mentés") }
        }

        Section("Tudnivalók") {
            Text("• Nyers formátum: Seestar FITS (16 bit, Bayer), továbbá JPEG/PNG.\n" +
                 "• Stack: csillag-alapú regisztráció, sigma-clip / winsorized / átlag, dark–flat–bias kalibráció.\n" +
                 "• Asztro eszközök: háttér-kivonás, színkalibráció, autostretch/asinh/MTF, csillagcsökkentés, zajszűrés.\n" +
                 "• Fotó eszközök: expozíció, kontraszt, tónusok, fehéregyensúly, HSL, clarity, dehaze, élesítés, szintek, gamma.\n" +
                 "• Export: JPEG, PNG, 16 bites TIFF.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    UpdatePrompt(auto = false, manualTrigger = trigger)
}

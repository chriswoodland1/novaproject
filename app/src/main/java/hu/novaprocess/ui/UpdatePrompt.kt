package hu.novaprocess.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.update.ReleaseInfo
import hu.novaprocess.update.UpdateManager

/**
 * auto = true: indításkor csendben ellenőriz (legfeljebb 6 óránként), csak akkor szól, ha van újabb.
 * manualTrigger: minden növelésre azonnal ellenőriz, és Toast-tal jelzi, ha nincs frissítés.
 */
@Composable
fun UpdatePrompt(auto: Boolean, manualTrigger: Int = 0) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(manualTrigger) {
        val manual = manualTrigger > 0
        if (!manual && (!auto || !UpdateManager.shouldAutoCheck(ctx))) return@LaunchedEffect
        try {
            val r = UpdateManager.checkForUpdate(ctx, ignoreSkipped = manual)
            if (r != null) info = r
            else if (manual) Toast.makeText(ctx, "Ez a legfrissebb verzió.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (manual) Toast.makeText(ctx, "Nem sikerült ellenőrizni: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val rel = info ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) info = null },
        title = { Text("Új verzió: ${rel.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("A GitHub-on megjelent a(z) ${rel.tag} kiadás. Letöltsem és telepítsem?", style = MaterialTheme.typography.bodyMedium)
                if (rel.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(rel.notes.take(1500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (downloading) { Spacer(Modifier.height(10.dp)); Text("Letöltés folyamatban… a telepítő automatikusan megnyílik.") }
            }
        },
        confirmButton = {
            Button(enabled = !downloading, onClick = {
                if (!UpdateManager.canInstallUnknownApps(ctx)) {
                    Toast.makeText(ctx, "Engedélyezd az ismeretlen forrásból telepítést a NovaProcess számára, majd próbáld újra.", Toast.LENGTH_LONG).show()
                    UpdateManager.openInstallPermissionSettings(ctx)
                    return@Button
                }
                downloading = true
                UpdateManager.downloadAndInstall(ctx, rel) { err ->
                    downloading = false
                    if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show() else info = null
                }
            }) { Text(if (downloading) "Letöltés…" else "Frissítés") }
        },
        dismissButton = {
            Column {
                TextButton(enabled = !downloading, onClick = { info = null }) { Text("Később") }
                TextButton(enabled = !downloading, onClick = { UpdateManager.skipTag(ctx, rel.tag); info = null }) { Text("Ezt kihagyom") }
            }
        }
    )
}

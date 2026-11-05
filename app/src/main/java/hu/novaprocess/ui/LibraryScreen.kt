package hu.novaprocess.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/** 1.1: egyszerű belépő; az 1.3-as csomagban projekt-könyvtár lesz belőle. */
@Composable
fun LibraryScreen(vm: EditorViewModel, onOpenEditor: () -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) { vm.openSingle(uri); onOpenEditor() } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Könyvtár", style = MaterialTheme.typography.headlineMedium)
        Text("Kész stackek, JPEG-ek, korábbi munkák. A projekt-előzmények az 1.3-as frissítéssel érkeznek.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { pick.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) }, modifier = Modifier.align(Alignment.Start)) { Text("Kép megnyitása") }
    }
}

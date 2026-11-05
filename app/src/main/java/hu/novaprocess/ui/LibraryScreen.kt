package hu.novaprocess.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.library.Library
import hu.novaprocess.library.LibraryEntry
import hu.novaprocess.ui.theme.Dk
import hu.novaprocess.vm.EditorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(vm: EditorViewModel, onOpenEditor: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version by vm.libraryVersion.collectAsState()
    val entries = remember(version) { Library.list(ctx) }
    var toDelete by remember { mutableStateOf<LibraryEntry?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) { vm.openSingle(uri); onOpenEditor() } }
    val df = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Könyvtár", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { pick.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) }) { Text("Kép megnyitása") }
            }
        }
        if (entries.isEmpty()) item(span = { GridItemSpan(2) }) {
            Text("Még nincs mentett munka. Minden stack automatikusan ide kerül (16 bites TIFF + recept), és bármikor újranyitható.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 30.dp))
        }
        items(entries, key = { it.id }) { e ->
            Column(Modifier.background(Dk.Panel).combinedClickable(onClick = { vm.openEntry(e); onOpenEditor() }, onLongClick = { toDelete = e })) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(Dk.Black)) {
                    Library.loadThumb(e.thumbPath)?.let { Image(it.asImageBitmap(), contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.padding(10.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text((if (e.frames > 0) "${e.frames} kocka · " else "") + "${e.width}×${e.height}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(df.format(Date(e.createdAt)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text("Hosszú érintés = törlés · tárhely: %.0f MB".format(Library.usedBytes(ctx) / 1048576.0), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }

    toDelete?.let { e ->
        AlertDialog(onDismissRequest = { toDelete = null }, title = { Text("Törlés") }, text = { Text("Törlöd a(z) „${e.title}” bejegyzést a könyvtárból (a stack TIFF-jével együtt)?") },
            confirmButton = { TextButton(onClick = { vm.deleteEntry(e.id); toDelete = null }) { Text("Törlés", color = cs.error) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Mégse") } })
    }
}

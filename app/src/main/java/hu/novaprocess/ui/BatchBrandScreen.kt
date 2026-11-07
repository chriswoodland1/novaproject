package hu.novaprocess.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.process.InfoData
import hu.novaprocess.process.InfoOverlay
import hu.novaprocess.process.Watermark
import hu.novaprocess.vm.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kötegelt logó + adatfelirat: több kész kép importálása → lapozható galéria (élő előnézet a vízjellel és felirattal,
 * képenként szerkeszthető adatok, a Seestar fájlnévből automatikusan kitöltve) → export mappába egy gombbal.
 */
@Composable
fun BatchBrandScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val st by vm.batch.collectAsState()
    val info by vm.infoOverlay.collectAsState()
    val wm by vm.watermark.collectAsState()
    var showWm by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    val cardS by vm.card.collectAsState()
    val prefs = ctx.getSharedPreferences("nova_ui", 0)
    var outTree by remember { mutableStateOf(prefs.getString("batch_out", null)?.let { Uri.parse(it) }) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }; vm.batchAdd(uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.batchAddFolder(uri) }
    }
    val pickOut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); prefs.edit().putString("batch_out", uri.toString()).apply(); outTree = uri }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Vissza") }
            Text("Kötegelt felirat · ${st.items.size} kép", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showWm = true }) { Text("Vízjel") }
        }

        if (st.items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Importálj kész képeket (pl. a Seestar JPEG-jeit): minden képre rákerül a vízjel és az adatfelirat, az adatok a fájlnévből automatikusan kitöltődnek, képenként átírhatók.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFiles.launch(arrayOf("image/*")) }) { Text("Képek kiválasztása") }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Mappa") }
                }
            }
            return@Column
        }

        val pager = rememberPagerState(pageCount = { st.items.size })
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
            val item = st.items[page]
            var preview by remember(item.uri, info, wm, item.data, st.card, cardS) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(item.uri, info, wm, item.data, st.card, cardS) {
                preview = withContext(Dispatchers.Default) { vm.batchPreview(item) }
            }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                preview?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: CircularProgressIndicator(strokeWidth = 2.dp)
                Text("${page + 1} / ${st.items.size} · ${item.name} · forrás ${item.width}×${item.height}" + (if (maxOf(item.width, item.height) in 1..2000) "  ⚠ kis felbontás (nem az eredeti 4K?)" else ""),
                    style = MaterialTheme.typography.labelSmall, color = if (maxOf(item.width, item.height) in 1..2000) Color(0xFFE8C547) else cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp))
            }
        }

        val item = st.items[pager.currentPage]
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = item.data.objectName, onValueChange = { vm.batchUpdate(item.uri) { d -> d.copy(objectName = it) } }, label = { Text("Objektum") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = item.data.date, onValueChange = { vm.batchUpdate(item.uri) { d -> d.copy(date = it) } }, label = { Text("Dátum") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = if (item.data.frames > 0) item.data.frames.toString() else "", onValueChange = { vm.batchUpdate(item.uri) { d -> d.copy(frames = it.toIntOrNull() ?: 0) } }, label = { Text("Kockák") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = if (item.data.exposureSec > 0f) "%.0f".format(item.data.exposureSec) else "", onValueChange = { vm.batchUpdate(item.uri) { d -> d.copy(exposureSec = it.toFloatOrNull() ?: 0f) } }, label = { Text("Exp. (s)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = item.data.city, onValueChange = { vm.batchUpdate(item.uri) { d -> d.copy(city = it) } }, label = { Text("Hely") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Felirat: ${if (info.enabled) "be" else "ki"}", info.enabled) { vm.setInfoOverlay(info.copy(enabled = !info.enabled)) }
                listOf("Bal lent" to hu.novaprocess.process.WmPosition.BOTTOM_LEFT, "Jobb lent" to hu.novaprocess.process.WmPosition.BOTTOM_RIGHT, "Bal fent" to hu.novaprocess.process.WmPosition.TOP_LEFT, "Jobb fent" to hu.novaprocess.process.WmPosition.TOP_RIGHT).forEach { (l, pos) -> Pill(l, info.position == pos) { vm.setInfoOverlay(info.copy(position = pos)) } }
                Pill(if (st.png) "PNG (veszteségmentes)" else "JPEG 97%", st.png) { vm.batch.value = st.copy(png = !st.png) }
                Pill(if (st.card) "Kártya: ${cardS.layout.label}" else "Kártya: ki", st.card) { vm.batch.value = st.copy(card = !st.card) }
                if (st.card) TextButton(onClick = { showCard = true }) { Text("Kártya beállítás") }
                TextButton(onClick = { vm.batchRemove(item.uri) }) { Text("Kép eltávolítása", color = cs.error) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Kimenet: " + (outTree?.lastPathSegment?.substringAfterLast(':') ?: "nincs mappa"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickOut.launch(null) }) { Text("Mappa") }
                TextButton(onClick = { pickFiles.launch(arrayOf("image/*")) }) { Text("+ Képek") }
            }
            if (st.running) { LinearProgressIndicator(progress = { if (st.items.isNotEmpty()) st.done.toFloat() / st.items.size else 0f }, modifier = Modifier.fillMaxWidth()); Text("Export ${st.done} / ${st.items.size}", style = MaterialTheme.typography.bodySmall) }
            st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Hiba")) cs.error else cs.onSurfaceVariant) }
            Button(onClick = { outTree?.let { vm.batchExport(it) } }, enabled = !st.running && outTree != null && st.items.isNotEmpty(), shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 4.dp)) {
                Text(if (st.running) "Export…" else if (st.card) "Összes exportálása (${st.items.size}) kártyaként" else "Összes exportálása (${st.items.size}) vízjellel + felirattal")
            }
        }
    }
    if (showWm) WatermarkSheet(vm) { showWm = false }
    if (showCard) CardSheet(vm) { showCard = false }
}

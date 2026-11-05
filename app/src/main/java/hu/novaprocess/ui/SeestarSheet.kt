package hu.novaprocess.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.novaprocess.seestar.SeestarSmb
import hu.novaprocess.seestar.SmbEntry
import kotlinx.coroutines.launch

/** Seestar (SMB) böngésző: megosztás → MyWorks → objektum → _sub mappa → FITS-ek kijelölése → letöltés. */
@Composable
fun SeestarSheet(onPicked: (List<Uri>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(ctx.getSharedPreferences("nova_ui", 0).getString("smb_host", "seestar.local") ?: "seestar.local") }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun open(p: String) {
        loading = true; error = null
        scope.launch {
            try { entries = SeestarSmb.list(p); path = p; selected = emptySet() }
            catch (e: Exception) { error = "Nem érhető el: ${e.message ?: e.javaClass.simpleName}" }
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Seestar tallózása (SMB)", style = MaterialTheme.typography.headlineMedium)
            if (path == null) {
                Text("Csatlakozz a Seestar Wi-Fi-jére (vagy legyen egy hálózaton veled állomás módban). A felvételek az „EMMC Images” megosztás MyWorks mappájában vannak.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Cím (seestar.local vagy 10.0.0.1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.getSharedPreferences("nova_ui", 0).edit().putString("smb_host", host).apply(); open("smb://$host/") }, enabled = !loading) { Text(if (loading) "Kapcsolódás…" else "Kapcsolódás") }
                    SeestarSmb.defaultHosts.filter { it != host }.forEach { h -> TextButton(onClick = { host = h }) { Text(h) } }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(path!!.removePrefix("smb://").take(48), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    TextButton(onClick = {
                        val up = path!!.trimEnd('/').substringBeforeLast('/') + "/"
                        if (up.length < "smb://".length + 2 || up == "smb:/") path = null else open(up)
                    }) { Text("↑ Fel") }
                }
                val fits = entries.filter { !it.isDir && (it.name.lowercase().endsWith(".fit") || it.name.lowercase().endsWith(".fits")) }
                if (fits.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = fits.map { it.path }.toSet() }) { Text("Összes FITS (${fits.size})") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Semmi") }
                }
                LazyColumn(Modifier.fillMaxWidth().height(340.dp)) {
                    items(entries, key = { it.path }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { if (e.isDir) open(e.path) else if (e.path in selected) selected = selected - e.path else selected = selected + e.path }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!e.isDir) Checkbox(checked = e.path in selected, onCheckedChange = { c -> selected = if (c) selected + e.path else selected - e.path })
                            Column(Modifier.weight(1f)) {
                                Text((if (e.isDir) "▸ " else "") + e.name, style = MaterialTheme.typography.bodyMedium)
                                if (!e.isDir) Text("%.1f MB".format(e.size / 1048576.0), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = cs.outline)
                    }
                }
                dl?.let { (done, total) ->
                    LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Letöltés $done / $total", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    val list = selected.toList(); if (list.isEmpty()) return@Button
                    scope.launch {
                        val uris = ArrayList<Uri>()
                        dl = 0 to list.size
                        for ((i, p) in list.withIndex()) {
                            try { uris.add(Uri.fromFile(SeestarSmb.download(ctx, p))) } catch (e: Exception) { error = "Hiba: ${e.message}" }
                            dl = (i + 1) to list.size
                        }
                        dl = null
                        if (uris.isNotEmpty()) { onPicked(uris); onClose() }
                    }
                }, enabled = selected.isNotEmpty() && dl == null, modifier = Modifier.fillMaxWidth()) { Text("Letöltés és hozzáadás (${selected.size})") }
            }
            error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

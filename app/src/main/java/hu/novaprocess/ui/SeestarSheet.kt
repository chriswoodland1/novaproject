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
    var host by remember { mutableStateOf(ctx.getSharedPreferences("nova_ui", 0).getString("smb_host", "10.0.0.1")?.let { if (it == "seestar.local") "10.0.0.1" else it } ?: "10.0.0.1") }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    val net = remember { SeestarSmb.netInfo(ctx) }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun open(p: String) {
        loading = true; error = null
        scope.launch {
            try { entries = SeestarSmb.list(ctx, p); path = p; selected = emptySet() }
            catch (e: Exception) { error = SeestarSmb.explain(e, SeestarSmb.netInfo(ctx)) }
            loading = false
        }
    }
    /** Kapcsolódás egy címhez: először az „EMMC Images/MyWorks”, aztán a megosztás gyökere, végül a megosztáslista. */
    fun connect(h: String) {
        loading = true; error = null; status = "Kapcsolódás: $h…"
        ctx.getSharedPreferences("nova_ui", 0).edit().putString("smb_host", h).apply()
        scope.launch {
            val candidates = listOf("smb://$h/${SeestarSmb.SHARE}/MyWorks/", "smb://$h/${SeestarSmb.SHARE}/", "smb://$h/")
            var lastErr: Exception? = null
            for (c in candidates) {
                try { entries = SeestarSmb.list(ctx, c); path = c; selected = emptySet(); lastErr = null; break }
                catch (e: Exception) { lastErr = e }
            }
            lastErr?.let { error = SeestarSmb.explain(it, SeestarSmb.netInfo(ctx)) }
            status = null; loading = false
        }
    }
    fun autoFind() {
        loading = true; error = null; found = emptyList()
        scope.launch {
            try {
                val hits = SeestarSmb.discover(ctx, host) { status = it }
                found = hits
                if (hits.isEmpty()) error = "Nem találtam SMB-eszközt a hálózaton. " + (if (!SeestarSmb.netInfo(ctx).wifiConnected) "Nincs Wi-Fi kapcsolat." else "Ellenőrizd, hogy a Seestar be van kapcsolva és ugyanazon a Wi-Fi-n van (hotspoton: a telefon a Seestar-hoz csatlakozik).")
                else { status = "Megosztás ellenőrzése…"; val ok = hits.firstOrNull { SeestarSmb.probe(ctx, it) != null } ?: hits.first(); host = ok; connect(ok); return@launch }
            } catch (e: Exception) { error = SeestarSmb.explain(e, SeestarSmb.netInfo(ctx)) }
            status = null; loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Seestar tallózása (SMB)", style = MaterialTheme.typography.headlineMedium)
            if (path == null) {
                Text("Csatlakozz a telefonnal a Seestar Wi-Fi-jére (hotspot, cím 10.0.0.1), vagy legyen a Seestar állomás módban a házi hálózaton. A felvételek az „EMMC Images” megosztás MyWorks mappájában vannak; vendég hozzáférés, jelszó nélkül.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text("Hálózat: " + (if (net.wifiConnected) "Wi-Fi ✓" else "nincs Wi-Fi ✗") + (if (net.wifiConnected && !net.hasInternet) " (internet nélkül – ez a Seestar hotspotra jellemző, rendben)" else "") + (net.gateway?.let { " · átjáró $it" } ?: "") + (net.myIp?.let { " · saját IP $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Button(onClick = { autoFind() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) (status ?: "Keresés…") else "Automatikus keresés a hálózaton") }
                if (found.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("Találatok:", style = MaterialTheme.typography.labelSmall); found.forEach { h -> TextButton(onClick = { host = h; connect(h) }) { Text(h) } } }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Cím kézzel (IP vagy seestar)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { connect(host.trim()) }, enabled = !loading && host.isNotBlank()) { Text(if (loading) "Kapcsolódás…" else "Kapcsolódás") }
                    SeestarSmb.defaultHosts.filter { it != host }.forEach { h -> TextButton(onClick = { host = h }) { Text(h) } }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(path!!.removePrefix("smb://").take(48), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    TextButton(onClick = {
                        val up = path!!.trimEnd('/').substringBeforeLast('/') + "/"
                        if (up.length < "smb://".length + 2 || up == "smb:/") path = null else open(up)
                    }) { Text("↑ Fel") }
                }
                val fits = entries.filter { !it.isDir && (it.name.lowercase().endsWith(".fit") || it.name.lowercase().endsWith(".fits")) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fits.isNotEmpty()) TextButton(onClick = { selected = fits.map { it.path }.toSet() }) { Text("Összes FITS (${fits.size})") }
                    TextButton(enabled = !loading, onClick = {
                        loading = true; error = null
                        scope.launch {
                            try { val all = SeestarSmb.collectFits(ctx, path!!); entries = all; selected = all.map { it.path }.toSet(); if (all.isEmpty()) error = "Nincs FITS ebben a mappában (almappákkal együtt)." }
                            catch (e: Exception) { error = SeestarSmb.explain(e, SeestarSmb.netInfo(ctx)) }
                            loading = false
                        }
                    }) { Text("Mappa szinkron (almappák)") }
                    if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Semmi") }
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
                            try { uris.add(Uri.fromFile(SeestarSmb.download(ctx, p))) } catch (e: Exception) { error = SeestarSmb.explain(e, SeestarSmb.netInfo(ctx)) }
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

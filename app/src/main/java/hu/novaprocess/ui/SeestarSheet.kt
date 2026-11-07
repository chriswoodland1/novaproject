package hu.novaprocess.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
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
    var dl by remember { mutableStateOf<DlState?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var previewLabel by remember { mutableStateOf("") }
    var previewBusy by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** Előnézet egy fájlról (letöltés a gyorsítótárba, ha még nincs ott – a későbbi kijelölésnél nem kell újra letölteni). */
    fun showPreview(e: SmbEntry, label: String = e.name) {
        previewJob?.cancel()
        previewJob = scope.launch {
            previewBusy = true; previewLabel = "$label · betöltés…"
            try {
                val f = SeestarSmb.download(ctx, e.path) { d, t -> previewLabel = "$label · ${pct(d, t)}" }
                val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SeestarSmb.previewOf(f) }
                if (b != null) { preview = b; previewLabel = label } else previewLabel = "$label · nem jeleníthető meg"
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
            catch (ex: Exception) { previewLabel = "$label · ${SeestarSmb.explain(ex, SeestarSmb.netInfo(ctx))}" }
            previewBusy = false
        }
    }
    /** Mappa megnyitásakor: ha van benne kész kép (Seestar JPG / bélyegkép), azt mutatjuk azonnal. */
    fun autoPreview(list: List<SmbEntry>) {
        val jpg = list.filter { !it.isDir && it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".png") } }
        val pick = jpg.firstOrNull { it.name.lowercase().contains("_thn") } ?: jpg.minByOrNull { it.size }
        pick?.let { showPreview(it, "Mappa képe: ${it.name}") }
    }

    fun open(p: String) {
        loading = true; error = null
        scope.launch {
            try { entries = SeestarSmb.list(ctx, p); path = p; selected = emptySet(); autoPreview(entries) }
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
                try { entries = SeestarSmb.list(ctx, c); path = c; selected = emptySet(); lastErr = null; autoPreview(entries); break }
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
                SeestarLivePanel(host)
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
                // élő előnézet: a mappa képe, a koppintott fájl, vagy letöltés közben az épp letöltött kocka
                if (preview != null || previewBusy || dl != null) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(170.dp).background(androidx.compose.ui.graphics.Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        preview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Előnézet", contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        if (previewBusy && preview == null) androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(previewLabel, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().height(if (preview != null || previewBusy || dl != null) 230.dp else 340.dp)) {
                    items(entries, key = { it.path }) { e ->
                        val isImg = !e.isDir && e.name.lowercase().let { it.endsWith(".fit") || it.endsWith(".fits") || it.endsWith(".jpg") || it.endsWith(".png") }
                        Row(Modifier.fillMaxWidth().clickable { if (e.isDir) open(e.path) else if (e.path in selected) selected = selected - e.path else selected = selected + e.path }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!e.isDir) Checkbox(checked = e.path in selected, onCheckedChange = { c -> selected = if (c) selected + e.path else selected - e.path })
                            Column(Modifier.weight(1f)) {
                                Text((if (e.isDir) "▸ " else "") + e.name, style = MaterialTheme.typography.bodyMedium)
                                if (!e.isDir) Text("%.1f MB".format(e.size / 1048576.0), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            if (isImg) TextButton(onClick = { showPreview(e) }) { Text("Nézd") }
                        }
                        HorizontalDivider(color = cs.outline)
                    }
                }
                dl?.let { d ->
                    LinearProgressIndicator(progress = { if (d.totalBytes > 0) (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Fájl ${d.index} / ${d.count} · ${d.name}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (d.fileTotal > 0) (d.fileDone.toFloat() / d.fileTotal).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = cs.tertiary)
                    Text("%s / %s · %.1f MB/s · hátra ~%s%s".format(mb(d.doneBytes), mb(d.totalBytes), d.speed / 1048576.0, eta(d), if (d.failed > 0) " · ${d.failed} hiba" else ""), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    TextButton(onClick = { d.job?.cancel() }) { Text("Letöltés megszakítása", color = cs.error) }
                }
                Button(onClick = {
                    val list = entries.filter { it.path in selected }; if (list.isEmpty()) return@Button
                    val totalBytes = list.sumOf { it.size }.coerceAtLeast(1L)
                    val job = scope.launch {
                        val uris = ArrayList<Uri>()
                        val t0 = System.currentTimeMillis(); var base = 0L; var failed = 0
                        for ((i, e) in list.withIndex()) {
                            dl = DlState(i + 1, list.size, e.name, 0, e.size, base, totalBytes, 0.0, failed, dl?.job)
                            try {
                                val f = SeestarSmb.download(ctx, e.path) { done, tot ->
                                    val now = System.currentTimeMillis(); val sp = (base + done) * 1000.0 / (now - t0).coerceAtLeast(1)
                                    dl = dl?.copy(fileDone = done, fileTotal = tot, doneBytes = base + done, speed = sp)
                                }
                                uris.add(Uri.fromFile(f))
                                // élő előnézet: a most letöltött kocka (az előző még renderelődhet – nem várunk rá)
                                if (!previewBusy) { previewBusy = true; scope.launch { val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SeestarSmb.previewOf(f, 360) }; if (b != null) { preview = b; previewLabel = "Letöltve: ${e.name}" }; previewBusy = false } }
                            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
                            catch (ex: Exception) { failed++; error = SeestarSmb.explain(ex, SeestarSmb.netInfo(ctx)) }
                            base += e.size
                        }
                        dl = null
                        if (uris.isNotEmpty()) { onPicked(uris); onClose() }
                    }
                    dl = DlState(0, list.size, "", 0, 0, 0, totalBytes, 0.0, 0, job)
                    job.invokeOnCompletion { if (it is kotlinx.coroutines.CancellationException) { dl = null; error = "Letöltés megszakítva." } }
                }, enabled = selected.isNotEmpty() && dl == null, modifier = Modifier.fillMaxWidth()) {
                    val sel = entries.filter { it.path in selected }
                    Text("Letöltés és hozzáadás (${selected.size} · ${mb(sel.sumOf { it.size })})")
                }
            }
            error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private data class DlState(val index: Int, val count: Int, val name: String, val fileDone: Long, val fileTotal: Long,
                           val doneBytes: Long, val totalBytes: Long, val speed: Double, val failed: Int, val job: kotlinx.coroutines.Job?)
private fun mb(b: Long): String = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)
private fun pct(d: Long, t: Long): String = if (t > 0) "${d * 100 / t} %" else mb(d)
private fun eta(d: DlState): String {
    if (d.speed <= 1.0) return "?"
    val s = ((d.totalBytes - d.doneBytes) / d.speed).toLong().coerceAtLeast(0)
    return if (s >= 60) "${s / 60} perc ${s % 60} mp" else "$s mp"
}

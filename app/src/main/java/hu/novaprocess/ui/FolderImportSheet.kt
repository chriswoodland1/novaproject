package hu.novaprocess.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import hu.novaprocess.image.BinReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mappa megnyitása: az app felméri a tartalmat (almappákkal), típus szerint csoportosít,
 * és ajánlást ad, melyik csoport való stackhez. A .bin fájlokat méretből azonosítja.
 */
@Composable
fun FolderImportSheet(onPicked: (List<Uri>, replace: Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    data class Group(val key: String, val label: String, val files: List<DocumentFile>, val note: String, val score: Int)
    var tree by remember { mutableStateOf<Uri?>(null) }
    var groups by remember { mutableStateOf<List<Group>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var bayer by remember { mutableStateOf(BinReader.bayerPattern(ctx)) }
    var dims by remember { mutableStateOf(ctx.getSharedPreferences("nova_ui", 0).getString("bin_dims", "") ?: "") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; tree = uri }
    }
    LaunchedEffect(Unit) { if (tree == null) pick.launch(null) }

    LaunchedEffect(tree, dims) {
        val t = tree ?: return@LaunchedEffect
        scanning = true
        groups = withContext(Dispatchers.IO) {
            val all = ArrayList<DocumentFile>()
            fun walk(d: DocumentFile, depth: Int) { for (f in d.listFiles()) { if (f.isDirectory) { if (depth < 3) walk(f, depth + 1) } else all.add(f) } }
            DocumentFile.fromTreeUri(ctx, t)?.let { walk(it, 0) }
            val byExt = all.groupBy { (it.name ?: "").substringAfterLast('.', "").lowercase() }
            val out = ArrayList<Group>()
            byExt["fit"].orEmpty().plus(byExt["fits"].orEmpty()).let { fits ->
                val subs = fits.filter { !(it.name ?: "").lowercase().contains("stacked") }
                if (subs.isNotEmpty()) out.add(Group("fit", "FITS nyers kockák", subs, "Legjobb választás: 16 bites nyers adat teljes fejléccel (expozíció, dátum, objektum).", 100))
                val stacked = fits - subs.toSet()
                if (stacked.isNotEmpty()) out.add(Group("fitstacked", "FITS kész stack (Seestar)", stacked, "A Seestar saját stackje – egyetlen képként nyisd meg, vagy több éjszaka összevonásához.", 40))
            }
            byExt["bin"].orEmpty().plus(byExt["raw"].orEmpty()).let { bins ->
                if (bins.isNotEmpty()) {
                    val ov = BinReader.dimsOverride(ctx)
                    val layouts = bins.map { BinReader.inferLayout(it.length(), ov) }
                    val ok = layouts.count { it != null }
                    val l0 = layouts.firstOrNull { it != null }
                    val note = if (ok == bins.size && l0 != null) "Fejléc nélküli nyers Bayer-adat, felismert méret ${l0.width}×${l0.height} (${l0.bytesPerPx * 8} bit)${if (l0.headerBytes > 0) ", ${l0.headerBytes} bájt fejléc" else ""}. Bayer-minta: $bayer."
                    else "Ismeretlen méret: ${bins.first().length()} bájt. Add meg a felbontást lent (pl. 3840x2160)."
                    out.add(Group("bin", ".bin nyers kockák", bins, note, if (ok == bins.size) 90 else 10))
                }
            }
            byExt["jpg"].orEmpty().plus(byExt["jpeg"].orEmpty()).let { jpgs ->
                val thumbs = jpgs.filter { (it.name ?: "").lowercase().contains("_thn") }
                val real = jpgs - thumbs.toSet()
                if (real.isNotEmpty()) out.add(Group("jpg", "JPEG képek", real, "8 bites, tömörített – csak akkor stackelj belőle, ha nincs FITS/bin. Bélyegképek (_thn) kihagyva: ${thumbs.size}.", 30))
            }
            byExt["png"].orEmpty().let { if (it.isNotEmpty()) out.add(Group("png", "PNG képek", it, "8 bites; használható, ha nincs nyers.", 25)) }
            byExt["tif"].orEmpty().plus(byExt["tiff"].orEmpty()).let { if (it.isNotEmpty()) out.add(Group("tif", "TIFF képek", it, "16 bites lineáris adatnak tekintve.", 50)) }
            byExt["mp4"].orEmpty().plus(byExt["avi"].orEmpty()).let { if (it.isNotEmpty()) out.add(Group("video", "Videók", it, "Bolygó/Hold módhoz (Bolygó / Hold videóból gomb).", 5)) }
            out.sortedByDescending { it.score }
        }
        scanning = false
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mappa megnyitása", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tree?.lastPathSegment?.substringAfterLast(':') ?: "Nincs mappa", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { pick.launch(null) }) { Text("Másik mappa") }
            }
            if (scanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(strokeWidth = 2.dp); Text("Felmérés (almappákkal)…") }
            groups?.let { gs ->
                if (gs.isEmpty()) Text("Nem találtam képfájlt ebben a mappában.", color = cs.onSurfaceVariant)
                gs.forEachIndexed { i, g ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (i == 0 && g.score >= 50) "★ Ajánlott · " else "") + "${g.label} (${g.files.size})", style = MaterialTheme.typography.titleMedium)
                                Text(g.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        if (g.key != "video") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            if (g.key == "fitstacked") OutlinedButton(onClick = { onPicked(g.files.map { it.uri }, true); onClose() }) { Text("Megnyitás / kockaként") }
                            else {
                                Button(onClick = { onPicked(g.files.map { it.uri }, true); onClose() }, enabled = g.score > 10) { Text("Stackhez (új lista)") }
                                OutlinedButton(onClick = { onPicked(g.files.map { it.uri }, false); onClose() }, enabled = g.score > 10) { Text("+ Hozzáadás") }
                            }
                        }
                        HorizontalDivider(color = cs.outline, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (gs.any { it.key == "bin" }) {
                    Text(".bin beállítások", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("GRBG", "RGGB", "BGGR", "GBRG").forEach { pat -> Pill(pat, bayer == pat) { bayer = pat; BinReader.setBayerPattern(ctx, pat) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = dims, onValueChange = { dims = it }, label = { Text("Felbontás, ha nem ismeri fel (pl. 3840x2160)") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { BinReader.setDimsOverride(ctx, dims) }) { Text("Alkalmaz") }
                    }
                    Text("Ha a kép színei furcsák (pl. zöld-magenta sakktábla vagy felcserélt vörös/kék), válts Bayer-mintát. A Seestar FITS-ek GRBG mintát használnak.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

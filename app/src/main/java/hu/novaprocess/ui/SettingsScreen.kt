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
    var showWm by remember { mutableStateOf(false) }
    val wm by vm.watermark.collectAsState()

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

        RowItem("Vízjel exportnál", when (wm.mode) { hu.novaprocess.process.WmMode.OFF -> "ki"; hu.novaprocess.process.WmMode.TEXT -> "szöveg"; hu.novaprocess.process.WmMode.IMAGE -> "logó" }) { showWm = true }
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Háttérben futás", style = MaterialTheme.typography.titleMedium)
                Text(if (ignoring) "Akkumulátor-optimalizálás kikapcsolva – a stack háttérben is végigfut." else "Kapcsold ki az akkumulátor-optimalizálást az apphoz, különben a rendszer háttérben megszakíthatja a hosszú stacket.", style = MaterialTheme.typography.bodySmall, color = if (ignoring) cs.onSurfaceVariant else cs.error)
            }
            if (!ignoring) TextButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))) }
                ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }) { Text("Beállítás") }
        }
        HorizontalDivider(color = cs.outline)
        RowItem("Verzió", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Indításkor legfeljebb 6 óránként ellenőrzi a GitHub Releases-t; új verziót engedély után tölt le és telepít.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { trigger++ }) { Text("Keresés most") }
        }
        HorizontalDivider(color = cs.outline)

        var astroKey by remember { mutableStateOf(hu.novaprocess.solve.PlateSolver.apiKey(ctx)) }
        Text("Plate solving (astrometry.net)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Text("Ingyenes API-kulcs: nova.astrometry.net → bejelentkezés → Profile → API key. A kép lekicsinyítve kerül feltöltésre, az eredmény nem publikus.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedTextField(value = astroKey, onValueChange = { astroKey = it }, label = { Text("API-kulcs") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Button(onClick = { hu.novaprocess.solve.PlateSolver.setApiKey(ctx, astroKey) }, modifier = Modifier.padding(top = 8.dp)) { Text("Mentés") }

        var binBayer by remember { mutableStateOf(hu.novaprocess.image.BinReader.bayerPattern(ctx)) }
        var binDims by remember { mutableStateOf(ctx.getSharedPreferences("nova_ui", 0).getString("bin_dims", "") ?: "") }
        var fitsBayer by remember { mutableStateOf(hu.novaprocess.image.FitsReader.bayerOverride ?: "AUTO") }
        Text("FITS Bayer-minta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Text("Alapból a fejléc (BAYERPAT + ROWORDER) dönt. Ha a színek felcserélődnek (pl. a vörös Hα-köd zöld vagy kék), válassz mintát kézzel; a változás a következő stacktől/megnyitástól él.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf("AUTO", "GRBG", "RGGB", "BGGR", "GBRG").forEach { pat -> Pill(pat, fitsBayer == pat) {
                fitsBayer = pat
                val v = if (pat == "AUTO") null else pat
                hu.novaprocess.image.FitsReader.bayerOverride = v
                ctx.getSharedPreferences("nova_ui", 0).edit().putString("fits_bayer", v).apply()
            } }
        }

        Text("Nyers .bin kockák", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Text("Fejléc nélküli nyers Bayer-fájlok: a méretet a fájlhosszból ismeri fel (pl. 3840×2160×2 bájt). Bayer-minta és kézi felbontás:", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf("GRBG", "RGGB", "BGGR", "GBRG").forEach { pat -> Pill(pat, binBayer == pat) { binBayer = pat; hu.novaprocess.image.BinReader.setBayerPattern(ctx, pat) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = binDims, onValueChange = { binDims = it }, label = { Text("Felbontás (üres = automatikus)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { hu.novaprocess.image.BinReader.setDimsOverride(ctx, binDims) }) { Text("Mentés") }
        }

        Text("Frissítési forrás", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub repo (tulajdonos/név)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { UpdateManager.setRepo(ctx, repo) }, modifier = Modifier.padding(top = 8.dp)) { Text("Mentés") }

        val app = ctx.applicationContext as android.app.Application
        var crash by remember { mutableStateOf(hu.novaprocess.NovaApp.lastCrash(app)) }
        Text("Diagnosztika", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
        Text("Memóriakeret: ${hu.novaprocess.NovaApp.maxHeapMb()} MB · processzormagok: ${Runtime.getRuntime().availableProcessors()}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (crash != null) {
            Text("Utolsó összeomlás naplója:", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 8.dp))
            Text(crash!!.take(1200), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("NovaProcess crash", crash))
                }) { Text("Másolás vágólapra") }
                TextButton(onClick = { hu.novaprocess.NovaApp.clearCrash(app); crash = null }) { Text("Törlés") }
            }
        } else Text("Nincs rögzített összeomlás.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        Text("Tippek", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
        Text("• Stack / pipeline közben nyugodtan válts másik appra: előtérbeli szolgáltatás viszi tovább, és értesítés jön, ha kész. Az appot a feladatlistából elhúzva viszont az Android leállítja.\n" +
             "• A szerkesztőben a képet nyomva tartva az eredetit látod; dupla érintés = nagyítás vissza.\n" +
             "• A kockák rácsában a piros keretes kockák kimaradnak; érintésre visszaveheted őket.\n" +
             "• Drizzle 2× csak sok (50+) kockánál és teljes felbontásban ad látható részletet.\n" +
             "• 16 bites TIFF export a további szerkesztéshez (Siril, GIMP, Lightroom).",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    UpdatePrompt(auto = false, manualTrigger = trigger)
    if (showWm) WatermarkSheet(vm) { showWm = false }
}

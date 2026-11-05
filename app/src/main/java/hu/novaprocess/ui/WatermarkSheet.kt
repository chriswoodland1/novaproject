package hu.novaprocess.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import hu.novaprocess.process.WmFont
import hu.novaprocess.process.WmMode
import hu.novaprocess.process.WmPosition
import hu.novaprocess.vm.EditorViewModel

/** Vízjel: szöveg (aláírás-stílus) vagy saját logó (PNG), pozíció, méret, átlátszóság – élő előnézettel. */
@Composable
fun WatermarkSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s by vm.watermark.collectAsState()
    val ui by vm.ui.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importWatermarkImage(uri) }
    val preview = remember(s, ui.preview) { vm.watermarkPreview() }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Vízjel", style = MaterialTheme.typography.headlineMedium)
            Text("Minden JPEG/PNG/keretes exportra rákerül (a 16 bites TIFF-re nem).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                preview?.let { Image(it.asImageBitmap(), contentDescription = "Vízjel előnézet", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
                    ?: Text("Nyiss meg egy képet az előnézethez", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Ki", s.mode == WmMode.OFF) { vm.setWatermark(s.copy(mode = WmMode.OFF)) }
                Pill("Szöveg / aláírás", s.mode == WmMode.TEXT) { vm.setWatermark(s.copy(mode = WmMode.TEXT)) }
                Pill("Logó (PNG)", s.mode == WmMode.IMAGE) { if (s.imagePath != null) vm.setWatermark(s.copy(mode = WmMode.IMAGE)) else pick.launch(arrayOf("image/png", "image/*")) }
            }
            if (s.mode == WmMode.TEXT) {
                OutlinedTextField(value = s.text, onValueChange = { vm.setWatermark(s.copy(text = it)) }, label = { Text("Szöveg (pl. a neved)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Aláírás (kézírás)", s.font == WmFont.SCRIPT) { vm.setWatermark(s.copy(font = WmFont.SCRIPT)) }
                    Pill("Serif dőlt", s.font == WmFont.SERIF) { vm.setWatermark(s.copy(font = WmFont.SERIF)) }
                    Pill("Sans", s.font == WmFont.SANS) { vm.setWatermark(s.copy(font = WmFont.SANS)) }
                    Pill("Mono", s.font == WmFont.MONO) { vm.setWatermark(s.copy(font = WmFont.MONO)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Fehér", s.white) { vm.setWatermark(s.copy(white = true)) }
                    Pill("Fekete", !s.white) { vm.setWatermark(s.copy(white = false)) }
                    Pill("Árnyék", s.shadow) { vm.setWatermark(s.copy(shadow = !s.shadow)) }
                }
            }
            if (s.mode == WmMode.IMAGE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick.launch(arrayOf("image/png", "image/*")) }) { Text(if (s.imagePath != null) "Másik logó…" else "Logó kiválasztása…") }
                }
                Text("Átlátszó hátterű PNG a legjobb (pl. fehér aláírás-logó). A méret a kép szélességének százalékában értendő.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (s.mode != WmMode.OFF) {
                Text("Pozíció", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Bal lent", s.position == WmPosition.BOTTOM_LEFT) { vm.setWatermark(s.copy(position = WmPosition.BOTTOM_LEFT)) }
                    Pill("Jobb lent", s.position == WmPosition.BOTTOM_RIGHT) { vm.setWatermark(s.copy(position = WmPosition.BOTTOM_RIGHT)) }
                    Pill("Bal fent", s.position == WmPosition.TOP_LEFT) { vm.setWatermark(s.copy(position = WmPosition.TOP_LEFT)) }
                    Pill("Jobb fent", s.position == WmPosition.TOP_RIGHT) { vm.setWatermark(s.copy(position = WmPosition.TOP_RIGHT)) }
                    Pill("Közép", s.position == WmPosition.CENTER) { vm.setWatermark(s.copy(position = WmPosition.CENTER)) }
                }
                SmallSlider("Méret (a kép szélességének %-a)", s.sizePercent, 5f, 60f, { "%.0f %%".format(it) }) { vm.setWatermark(s.copy(sizePercent = it)) }
                SmallSlider("Átlátszatlanság", s.opacity, 0.1f, 1f, { "%.0f %%".format(it * 100) }) { vm.setWatermark(s.copy(opacity = it)) }
                SmallSlider("Margó", s.marginPercent, 0f, 10f, { "%.1f %%".format(it) }) { vm.setWatermark(s.copy(marginPercent = it)) }
            }
        }
    }
}

package hu.novaprocess.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import hu.novaprocess.process.CardLayout
import hu.novaprocess.process.ShareCard
import hu.novaprocess.vm.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Megosztás-kártya beállításai: sablon, logó, kontextus-sor, QR, lábléc, szín, kedvencek. */
@Composable
fun CardSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val s by vm.card.collectAsState()
    val cap by vm.caption.collectAsState()
    var favVersion by remember { mutableIntStateOf(0) }
    val favs = remember(favVersion) { ShareCard.favorites(ctx) }
    var favName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(s, cap) { preview = withContext(Dispatchers.Default) { vm.cardPreview() } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Megosztás-kártya", style = MaterialTheme.typography.headlineMedium)
            preview?.let { Image(it.asImageBitmap(), contentDescription = "Kártya", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(240.dp).background(Color.Black)) }

            Text("Sablon", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardLayout.values().forEach { l -> Pill(l.label, s.layout == l) { vm.setCard(s.copy(layout = l)) } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (s.dark) "Sötét háttér" else "Világos háttér", true) { vm.setCard(s.copy(dark = !s.dark)) }
                Pill("Logó", s.showLogo) { vm.setCard(s.copy(showLogo = !s.showLogo)) }
                Pill("Kontextus sor", s.showContext) { vm.setCard(s.copy(showContext = !s.showContext)) }
                Pill("QR", s.showQr) { vm.setCard(s.copy(showQr = !s.showQr)) }
            }
            Text("Kiemelőszín", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFF8FE3A8, 0xFFE8C547, 0xFF7FB3FF, 0xFFFF7A7A, 0xFFC792EA, 0xFFBDBDBD).forEach { c ->
                    Box(Modifier.size(30.dp).background(Color(c), CircleShape).border(if (s.accent == c.toInt()) 3.dp else 0.dp, Color.White, CircleShape).clickable { vm.setCard(s.copy(accent = c.toInt())) })
                }
            }
            SmallSlider("Betűméret", s.fontScale, 0.7f, 1.6f, { "%.0f %%".format(it * 100) }) { vm.setCard(s.copy(fontScale = it)) }
            OutlinedTextField(value = s.footer, onValueChange = { vm.setCard(s.copy(footer = it)) }, label = { Text("Lábléc (pl. @felhasználó, saját név)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (s.showQr) OutlinedTextField(value = s.qrText, onValueChange = { vm.setCard(s.copy(qrText = it)) }, label = { Text("QR tartalom (Astrobin / Instagram link)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            vm.contextLine(vm.primaryObject)?.let { Text("Kontextus a katalógusból: $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            if (s.showLogo) Text("A logó a Vízjel lapon beállított PNG (⋯ → Vízjel → Kép).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            Text("Kedvencek", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            if (favs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                favs.values.forEach { f -> Pill(f.favorite, s.favorite == f.favorite) { vm.setCard(f) } }
                if (s.favorite.isNotBlank() && favs.containsKey(s.favorite)) TextButton(onClick = { ShareCard.deleteFavorite(ctx, s.favorite); vm.setCard(s.copy(favorite = "")); favVersion++ }) { Text("Törlés", color = cs.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = favName, onValueChange = { favName = it }, label = { Text("Mentés kedvencként…") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (favName.isNotBlank()) { ShareCard.saveFavorite(ctx, favName.trim(), s); vm.setCard(s.copy(favorite = favName.trim())); favName = ""; favVersion++ } }) { Text("Mentés") }
            }
            Button(onClick = onClose, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Kész") }
        }
    }
}

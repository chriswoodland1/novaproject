package hu.novaprocess.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/**
 * Üstökös mód: a mag bejelölése az első és az utolsó kockán (érintés), a köztes kockákon az időbélyegből
 * interpolál a stacker. Kettős regisztráció → éles csillagok ÉS éles üstökös.
 */
@Composable
fun CometSheet(vm: EditorViewModel, onDone: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st by vm.stackState.collectAsState()
    val co by vm.comet.collectAsState()
    val fs = remember { vm.cometFrames() }
    var firstBmp by remember { mutableStateOf<Bitmap?>(null) }
    var lastBmp by remember { mutableStateOf<Bitmap?>(null) }
    var p1 by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var p2 by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var radius by remember { mutableStateOf(0f) }
    LaunchedEffect(fs) {
        if (fs.size >= 3) { firstBmp = vm.framePreview(fs.first().uri); lastBmp = vm.framePreview(fs.last().uri) }
    }
    LaunchedEffect(co.result) { if (co.result != null && !co.running) onDone() }

    ModalBottomSheet(onDismissRequest = { if (!st.running) onClose() }, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Üstökös mód", style = MaterialTheme.typography.headlineMedium)
            if (fs.size < 3) { Text("Legalább 3 kocka kell (időrendben, DATE-OBS-szal).", color = cs.onSurfaceVariant); TextButton(onClick = onClose) { Text("Bezárás") }; return@Column }
            val t0 = vm.parseDateObs(fs.first().dateObs); val t1 = vm.parseDateObs(fs.last().dateObs)
            Text("${fs.size} kocka · " + (if (t0 != null && t1 != null) "${"%.0f".format((t1 - t0) / 60000f)} perc időtartam (FITS időbélyegek)" else "nincs időbélyeg → egyenletes időköz"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text("Koppints az üstökös magjára mindkét képen. A köztes kockákon a mozgást az időből interpoláljuk, így halvány üstökösnél sem kell minden kockán megtalálni.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            Text("1. Első kocka · ${fs.first().name}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Picker(firstBmp, p1) { p1 = it }
            Text("2. Utolsó kocka · ${fs.last().name}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Picker(lastBmp, p2) { p2 = it }

            SmallSlider("Üstökös-maszk sugara", radius, 0f, 0.3f, { if (it <= 0f) "automatikus" else "%.0f %% (rövidebb oldal)".format(it * 100) }) { radius = it }
            if (p1 != null && p2 != null && firstBmp != null) {
                val dx = (p2!!.first - p1!!.first) * firstBmp!!.width; val dy = (p2!!.second - p1!!.second) * firstBmp!!.height
                Text("Elmozdulás az előnézeten: ${"%.0f".format(kotlin.math.hypot(dx, dy))} px (a stack ezt a csillag-igazítás után pontosítja)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (st.running) {
                st.progress?.let { p -> LinearProgressIndicator(progress = { if (p.total > 0) p.current.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth()); Text("${p.phase} ${p.current}/${p.total}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { vm.cancelStack() }) { Text("Megszakítás", color = cs.error) }
            }
            co.error?.let { Text("Hiba: $it", color = cs.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose, enabled = !st.running) { Text("Mégse") }
                Button(onClick = { vm.startCometStack(p1!!, p2!!, radius) }, enabled = p1 != null && p2 != null && !st.running, shape = CircleShape, modifier = Modifier.weight(1f).height(50.dp)) { Text("Üstökös stack (kettős regisztráció)") }
            }
            Text("Eredmény: a szerkesztőben a ⋯ menüből külön megnyitható a csillag-réteg és a mag-réteg is.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Picker(bmp: Bitmap?, p: Pair<Float, Float>?, onPick: (Pair<Float, Float>) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black).onSizeChanged { box = it }
        .pointerInput(bmp) { detectTapGestures { pos -> val b = bmp ?: return@detectTapGestures
            val fit = fitRect(box.width.toFloat(), box.height.toFloat(), b.width.toFloat(), b.height.toFloat())
            val nx = (pos.x - fit[0]) / fit[2]; val ny = (pos.y - fit[1]) / fit[3]
            if (nx in 0f..1f && ny in 0f..1f) onPick(Pair(nx, ny)) } }, contentAlignment = Alignment.Center) {
        if (bmp == null) CircularProgressIndicator(strokeWidth = 2.dp)
        else {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            p?.let { pt -> Canvas(Modifier.fillMaxSize()) {
                val fit = fitRect(size.width, size.height, bmp.width.toFloat(), bmp.height.toFloat())
                val c = Offset(fit[0] + pt.first * fit[2], fit[1] + pt.second * fit[3])
                drawCircle(Color(0xFFFFD54F), 18f, c, style = Stroke(3f)); drawCircle(Color(0xFFFFD54F), 3f, c)
                drawLine(Color(0xFFFFD54F), Offset(c.x - 30f, c.y), Offset(c.x - 20f, c.y), 2f); drawLine(Color(0xFFFFD54F), Offset(c.x + 20f, c.y), Offset(c.x + 30f, c.y), 2f)
                drawLine(Color(0xFFFFD54F), Offset(c.x, c.y - 30f), Offset(c.x, c.y - 20f), 2f); drawLine(Color(0xFFFFD54F), Offset(c.x, c.y + 20f), Offset(c.x, c.y + 30f), 2f)
            } } ?: Text("Koppints a magra", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
        }
    }
}

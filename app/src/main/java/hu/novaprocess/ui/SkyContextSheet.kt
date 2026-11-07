package hu.novaprocess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import hu.novaprocess.catalog.CatObject
import hu.novaprocess.catalog.Catalog
import hu.novaprocess.catalog.Match
import hu.novaprocess.catalog.SkyField
import hu.novaprocess.vm.EditorViewModel
import kotlin.math.cos

/**
 * „Hol van az égen”: a kép látómezejében azonosított objektumok a katalógusból (típus, csillagkép, távolság,
 * méret, fényesség, legjobb hónap), mini-égtérkép a környező objektumokkal és a látómező keretével, vágási javaslat.
 */
@Composable
fun SkyContextSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val st by vm.sky.collectAsState()
    val cs = MaterialTheme.colorScheme
    val f = st.field
    var selected by remember(st.matches) { mutableStateOf(st.matches.firstOrNull { it.inField }) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hol van az égen", style = MaterialTheme.typography.headlineMedium)
            if (f == null) {
                Text("Nincs égi pozíció ehhez a képhez. FITS fejlécből (Seestar RA/DEC) automatikusan megvan; JPEG-nél a ⋯ → Plate solve adja meg.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                TextButton(onClick = onClose) { Text("Bezárás") }
                return@Column
            }
            Text("Középpont ${Catalog.formatRa(f.ra)}  ${Catalog.formatDec(f.dec)} · látómező %.2f° × %.2f° · %.2f″/px · %s%s".format(f.fovWDeg, f.fovHDeg, f.pixScale, f.source, if (f.oriented) "" else " (tájolás ismeretlen)"),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            MiniSkyMap(f, st.matches, selected) { selected = it }

            val inField = st.matches.filter { it.inField }
            val near = st.matches.filter { !it.inField }.take(6)
            if (inField.isEmpty()) Text("A katalógusban nincs ismert mélyég-objektum a látómezőben.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            else {
                Text("A látómezőben (${inField.size})", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                inField.forEach { m -> ObjectRow(m, m == selected) { selected = m } }
            }
            if (near.isNotEmpty()) {
                Text("A közelben", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                near.forEach { m -> ObjectRow(m, m == selected) { selected = m } }
            }

            selected?.let { m -> ObjectDetails(m.obj, f) }

            st.crop?.let { c ->
                Column(Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vágási javaslat", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text(c.reason, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.applySuggestedCrop(); onClose() }, shape = CircleShape) { Text("Vágás az objektumra") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                selected?.let { m -> OutlinedButton(onClick = { vm.useObjectName(m.obj); onClose() }, shape = CircleShape, modifier = Modifier.weight(1f)) { Text("Ez legyen a felirat") } }
                TextButton(onClick = onClose) { Text("Bezárás") }
            }
            Text("Katalógus: OpenNGC (CC-BY-SA-4.0) kivonat – Messier, Caldwell és ~500 fényesebb NGC/IC objektum, offline.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ObjectRow(m: Match, sel: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val o = m.obj
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (sel) cs.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(o.title, style = MaterialTheme.typography.bodyMedium)
            Text("${o.typeLabel} · ${o.constellationHu} · ${Catalog.formatSize(o)}" + (o.vmag?.let { " · %.1f mag".format(it) } ?: "") + (if (!m.inField) " · %.1f° a széltől".format(m.offsetDeg) else ""), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (m.inField && m.fieldFrac > 0f) Text("%.0f %%".format(m.fieldFrac * 100), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ObjectDetails(o: CatObject, f: SkyField) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(o.title, style = MaterialTheme.typography.titleMedium)
        if (o.allNames.isNotBlank()) Text(o.allNames, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        fun line(k: String, v: String) = Row { Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodySmall) }
        line("Típus", o.typeLabel)
        line("Csillagkép", "${o.constellationHu} (${o.const})")
        o.distanceLy?.let { line("Távolság", Catalog.formatDistance(it)) }
        line("Látszó méret", Catalog.formatSize(o) + (if (o.majArcmin > 0f) "  (a Hold %.1f×)".format(o.majArcmin / 31f) else ""))
        o.vmag?.let { line("Fényesség", "%.1f mag".format(it)) }
        line("Koordináta", "${Catalog.formatRa(o.ra)}  ${Catalog.formatDec(o.dec)}")
        line("Legjobb hónap", Catalog.bestMonth(o.ra) + " (éjfélkor delel)")
        if (o.majArcmin > 0f) {
            val px = o.majArcmin * 60.0 / f.pixScale
            line("A képen", "~%.0f px átmérő · a rövidebb oldal %.0f %%-a".format(px, px / minOf(f.width, f.height) * 100))
        }
    }
}

/** Mini-égtérkép: a látómező kerete középen, a katalógus-objektumok a környéken (gnomonikus, észak felül, kelet balra). */
@Composable
private fun MiniSkyMap(f: SkyField, matches: List<Match>, selected: Match?, onPick: (Match) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val radiusDeg = (maxOf(f.fovWDeg, f.fovHDeg) * 2.2).coerceIn(2.0, 12.0)
    val textColor = cs.onSurface.toArgb()
    val dimColor = cs.onSurfaceVariant.toArgb()
    val accent = cs.primary
    Canvas(Modifier.fillMaxWidth().height(230.dp).background(Color(0xFF07080C), RoundedCornerShape(12.dp))) {
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val scale = (minOf(w, h) / 2f) / radiusDeg.toFloat()      // px / fok
        val r = Math.PI / 180
        fun proj(ra: Double, dec: Double): Offset? {
            val cosc = Math.sin(f.dec * r) * Math.sin(dec * r) + Math.cos(f.dec * r) * Math.cos(dec * r) * Math.cos((ra - f.ra) * r)
            if (cosc <= 0.2) return null
            val xi = Math.cos(dec * r) * Math.sin((ra - f.ra) * r) / cosc / r
            val eta = (Math.cos(f.dec * r) * Math.sin(dec * r) - Math.sin(f.dec * r) * Math.cos(dec * r) * Math.cos((ra - f.ra) * r)) / cosc / r
            return Offset(cx - (xi * scale).toFloat(), cy - (eta * scale).toFloat())
        }
        // rács: 1° körök
        for (d in 1..radiusDeg.toInt()) drawCircle(Color.White.copy(alpha = 0.06f), d * scale, Offset(cx, cy), style = Stroke(1f))
        // látómező kerete (forgatva, ha ismert)
        val fw = (f.fovWDeg * scale).toFloat(); val fh = (f.fovHDeg * scale).toFloat()
        val rot = (f.rotationDeg ?: 0.0).toFloat()
        drawContext.canvas.nativeCanvas.apply {
            save(); rotate(-rot, cx, cy)
            val p = android.graphics.Paint().apply { color = accent.copy(alpha = 0.9f).toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }
            drawRect(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2, p)
            restore()
        }
        // objektumok
        val tp = android.graphics.Paint().apply { color = dimColor; textSize = 26f; isAntiAlias = true }
        val tpSel = android.graphics.Paint().apply { color = textColor; textSize = 28f; isAntiAlias = true; isFakeBoldText = true }
        for (m in matches.asReversed()) {
            val o = m.obj; val p = proj(o.ra, o.dec) ?: continue
            val rr = (o.majArcmin / 120f * scale).coerceAtLeast(4f)
            val sel = m == selected
            val col = if (sel) Color(0xFFFFD54F) else if (m.inField) Color(0xFF8FE3A8) else Color(0xFF9AA4B2)
            drawCircle(col.copy(alpha = if (sel) 0.9f else 0.6f), rr, p, style = Stroke(if (sel) 3f else 1.5f))
            if (o.majArcmin / 120f * scale < 4f) drawCircle(col, 3f, p)
            drawContext.canvas.nativeCanvas.drawText(o.id, p.x + rr + 6f, p.y + 9f, if (sel) tpSel else tp)
        }
        drawContext.canvas.nativeCanvas.apply {
            val lp = android.graphics.Paint().apply { color = dimColor; textSize = 24f; isAntiAlias = true }
            drawText("É", cx - 8f, 28f, lp); drawText("K", 12f, cy + 8f, lp); drawText("%.0f° sugár".format(radiusDeg), w - 120f, h - 12f, lp)
        }
    }
    // kattintás a listában választ; a térkép csak megjelenít
    Spacer(Modifier.height(0.dp))
}

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

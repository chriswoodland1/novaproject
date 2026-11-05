package hu.novaprocess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hu.novaprocess.vm.EditorViewModel

/** Csillagalak-térkép: 5×5 régió FWHM és elliptikusság – fókusz, szenzordőlés, optikai hibák diagnosztikája. */
@Composable
fun StarMapSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var map by remember { mutableStateOf<hu.novaprocess.process.StarShape.Map?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { map = vm.starShapeMap() } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Csillagalak-térkép", style = MaterialTheme.typography.headlineMedium)
            Text("Régiónként a csillagok félértékszélessége (FWHM, px) és elliptikussága. Egyenletesen zöld = jó fókusz és sík mező; egyik oldalon romló = szenzordőlés vagy kollimáció; sarkokban megnyúlt = optikai hiba (coma).",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            when {
                error != null -> Text("Hiba: $error", color = cs.error)
                map == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(strokeWidth = 2.dp); Text("Mérés…") }
                else -> {
                    val m = map!!
                    val valid = m.fwhm.filter { it > 0f }
                    val best = valid.minOrNull() ?: 1f
                    Column(Modifier.fillMaxWidth().aspectRatio(16f / 9f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (gy in 0 until m.grid) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (gx in 0 until m.grid) {
                                val i = gy * m.grid + gx
                                val f = m.fwhm[i]; val e = m.ecc[i]
                                val ratio = if (f > 0f) f / best else 0f
                                val color = when { f <= 0f -> Color(0xFF2A2A2A); ratio < 1.15f -> Color(0xFF2E7D4F); ratio < 1.4f -> Color(0xFF9A8A2A); else -> Color(0xFF9A3A32) }
                                Box(Modifier.weight(1f).fillMaxWidth().background(color), contentAlignment = Alignment.Center) {
                                    Text(if (f > 0f) "%.1f\n%.0f%%".format(f, e * 100) else "–", style = MaterialTheme.typography.labelSmall, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                    }
                    Text("Cellánként: FWHM px / elliptikusság %. Legjobb FWHM %.2f px · átlag %.2f px · csillagok: %d".format(best, valid.average(), m.stars), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

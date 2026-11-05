package hu.novaprocess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import hu.novaprocess.ui.theme.LocalNightMode

/** Darkroom lista-sor: cím balra, érték jobbra, vékony elválasztó. */
@Composable
fun RowItem(title: String, value: String, onClick: (() -> Unit)? = null, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** Kapszula gomb / chip. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(CircleShape).background(if (selected) cs.primary else cs.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (selected) cs.onPrimary else cs.onSurface)
    }
}

/** Nagy, egyetlen csúszka: név és érték fölötte, vékony sáv, keskeny fogantyú (Lightroom mobil). */
@Composable
fun BigSlider(
    label: String, value: Float, min: Float, max: Float,
    format: (Float) -> String = { "%.2f".format(it) },
    onDragStart: () -> Unit = {}, onChange: (Float) -> Unit, onDragEnd: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(format(value), style = MaterialTheme.typography.headlineMedium)
        }
        Slider(
            value = value, onValueChange = { onDragStart(); onChange(it) }, onValueChangeFinished = onDragEnd,
            valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary, inactiveTrackColor = cs.outline)
        )
    }
}

/** Kompakt csúszka listákhoz (stack-beállítások). */
@Composable
fun SmallSlider(label: String, value: Float, min: Float, max: Float, format: (Float) -> String = { "%.1f".format(it) }, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

/** RGB/mono hisztogram, logaritmikus magasság. */
@Composable
fun Histogram(h: Array<IntArray>, modifier: Modifier = Modifier) {
    val night = LocalNightMode.current
    val colors = if (night) listOf(Color(0xFFE25A4A), Color(0xFFB8463A), Color(0xFF8A3A32))
        else if (h.size == 3) listOf(Color(0xFFFF6B6B), Color(0xFF8FE3A8), Color(0xFF6BA8FF)) else listOf(Color(0xFFF0F0F0))
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val maxV = h.maxOf { ch -> ch.max() }.coerceAtLeast(1).toFloat()
        val stepX = size.width / 255f
        val lnMax = kotlin.math.ln(1f + maxV)
        h.forEachIndexed { ci, ch ->
            val path = Path(); path.moveTo(0f, size.height)
            for (i in 0 until 256) path.lineTo(i * stepX, size.height - (kotlin.math.ln(1f + ch[i]) / lnMax) * size.height)
            path.lineTo(size.width, size.height); path.close()
            drawPath(path, colors[ci % colors.size].copy(alpha = 0.45f))
        }
    }
}

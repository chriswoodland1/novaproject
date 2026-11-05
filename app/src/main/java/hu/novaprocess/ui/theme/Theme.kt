package hu.novaprocess.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ground = Color(0xFF0A0E17)
val Panel = Color(0xFF0F1626)
val Line = Color(0xFF1F2A40)
val Amber = Color(0xFFF2B544)
val Ice = Color(0xFF9CCBFF)
val Mint = Color(0xFF7CF2B0)
val Text = Color(0xFFE8ECF4)
val Muted = Color(0xFF8A94A8)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    secondary = Ice,
    onSecondary = Color(0xFF061120),
    background = Ground,
    onBackground = Text,
    surface = Panel,
    onSurface = Text,
    surfaceVariant = Color(0xFF141B2B),
    onSurfaceVariant = Muted,
    outline = Line,
    error = Color(0xFFFF8A80)
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

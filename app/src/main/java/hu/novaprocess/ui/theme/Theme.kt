package hu.novaprocess.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Darkroom paletta: tiszta fekete, fehér, egy visszafogott vörös (éjszakai mód). */
object Dk {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF141414)
    val Panel2 = Color(0xFF1C1C1C)
    val Line = Color(0xFF262626)
    val Text = Color(0xFFF0F0F0)
    val Muted = Color(0xFF9A9A9A)
    val Dim = Color(0xFF6E6E6E)
    val Red = Color(0xFFE25A4A)
    val Ok = Color(0xFF8FE3A8)
    val Warn = Color(0xFFE8C547)
}

/** Éjszakai mód: minden fehér → tompa vörös, hogy a sötétadaptált szem megmaradjon. */
val LocalNightMode = staticCompositionLocalOf { false }

private fun scheme(night: Boolean) = if (!night) darkColorScheme(
    primary = Dk.Text, onPrimary = Dk.Black,
    secondary = Dk.Muted, onSecondary = Dk.Black,
    background = Dk.Black, onBackground = Dk.Text,
    surface = Dk.Panel, onSurface = Dk.Text,
    surfaceVariant = Dk.Panel2, onSurfaceVariant = Dk.Muted,
    outline = Dk.Line, error = Dk.Red
) else darkColorScheme(
    primary = Color(0xFFE25A4A), onPrimary = Dk.Black,
    secondary = Color(0xFF8A3A32), onSecondary = Dk.Black,
    background = Dk.Black, onBackground = Color(0xFFE08A7E),
    surface = Color(0xFF120807), onSurface = Color(0xFFE08A7E),
    surfaceVariant = Color(0xFF1A0C0A), onSurfaceVariant = Color(0xFF9A5A52),
    outline = Color(0xFF2A1512), error = Color(0xFFFF7A6A)
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.6.sp)
)

@Composable
fun NovaTheme(night: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalNightMode provides night) {
        MaterialTheme(colorScheme = scheme(night), typography = Type, content = content)
    }
}

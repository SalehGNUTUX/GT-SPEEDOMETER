package net.gnutux.speedometer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// لوحة داكنة ثابتة: العداد يُقرأ على المقود تحت الشمس وليلًا، والخلفية الفاتحة
// تنعكس على الشاشة وتُتعب العين. لا وضع فاتح عمدًا.
val Bg = Color(0xFF070B0E)
val Surface = Color(0xFF111A20)
val SurfaceHigh = Color(0xFF18242C)
val Accent = Color(0xFF00E5C7)
val AccentDim = Color(0xFF0A6E62)
val Warn = Color(0xFFFFB020)
val Danger = Color(0xFFFF5A45)
val TextPrimary = Color(0xFFF2F7F9)
val TextSecondary = Color(0xFF8DA0AC)
val TrackDim = Color(0xFF223038)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    secondary = AccentDim,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = Danger,
)

@Composable
fun GtSpeedometerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

package net.gnutux.speedometer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import net.gnutux.speedometer.core.settings.ThemeMode
import java.util.Calendar

/**
 * لوحة الألوان الدلاليّة.
 *
 * مُرّرت عبر `CompositionLocal` لا كثوابتَ عامّة، كي يعمل الوضعان الفاتح والداكن بلا
 * تغيير موضع استعمالٍ واحد: الأسماء القديمة (`Bg`, `Accent`, …) بقيت كما هي وصارت
 * خصائصَ تُقرأ داخل التركيب.
 */
data class GtColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val accentDim: Color,
    val warn: Color,
    val danger: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val trackDim: Color,
    val isDark: Boolean,
)

/**
 * اللوحة الداكنة. تُقرأ أيضًا خارج التركيب — طبقة الفيديو المحروقة والإشعار والبلاطة
 * السريعة تُرسم كلّها على خلفيّة داكنة مهما كانت سمة التطبيق.
 */
val DarkPalette = GtColors(
    bg = Color(0xFF070B0E),
    surface = Color(0xFF111A20),
    surfaceHigh = Color(0xFF18242C),
    accent = Color(0xFF00E5C7),
    accentDim = Color(0xFF0A6E62),
    warn = Color(0xFFFFB020),
    danger = Color(0xFFFF5A45),
    textPrimary = Color(0xFFF2F7F9),
    textSecondary = Color(0xFF8DA0AC),
    trackDim = Color(0xFF223038),
    isDark = true,
)

/**
 * اللوحة الفاتحة. ليست عكسًا حسابيًّا للداكنة: الفيروزيّ الفاتح (`00E5C7`) يذوب على
 * الأبيض، فأُنزلت إضاءته حتّى بلغ تباينه مع الخلفيّة حدًّا يُقرأ تحت الشمس.
 */
val LightPalette = GtColors(
    bg = Color(0xFFF1F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE2EBEF),
    accent = Color(0xFF00796B),
    accentDim = Color(0xFF9AD9CF),
    warn = Color(0xFFA85C00),
    danger = Color(0xFFC62828),
    textPrimary = Color(0xFF0A1519),
    textSecondary = Color(0xFF4C5F69),
    trackDim = Color(0xFFC7D6DD),
    isDark = false,
)

val LocalGtColors = staticCompositionLocalOf { DarkPalette }

/** الوصول المختصر داخل التركيب */
object GtTheme {
    val colors: GtColors
        @Composable @ReadOnlyComposable get() = LocalGtColors.current
}

// الأسماء التاريخيّة، محفوظةً كي لا يتغيّر موضع استعمالٍ واحد في الشاشات.
val Bg: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.bg
val Surface: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.surface
val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.surfaceHigh
val Accent: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.accent
val AccentDim: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.accentDim
val Warn: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.warn
val Danger: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.danger
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.textSecondary
val TrackDim: Color @Composable @ReadOnlyComposable get() = LocalGtColors.current.trackDim

private fun schemeOf(c: GtColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.bg,
        secondary = c.accentDim,
        background = c.bg,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textSecondary,
        error = c.danger,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        secondary = c.accentDim,
        background = c.bg,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textSecondary,
        error = c.danger,
    )
}

/**
 * أفي الليل نحن حسب ساعة الجدار؟
 *
 * ساعة الجدار زمنٌ مدنيّ لا زمن قياس، فاستعمال [Calendar] هنا لا يخالف قاعدة
 * «`elapsedRealtimeNanos` محور الزمن الوحيد» — تلك للمسافة والسرعة والمزامنة.
 *
 * وتُعاد القراءة كلّ دقيقة: من يقود عند الغروب يجب أن تتبدّل سمته وهو سائر، لا عند
 * إعادة تشغيل التطبيق.
 */
@Composable
private fun isNightNow(dayStartHour: Int, nightStartHour: Int): Boolean {
    var night by remember(dayStartHour, nightStartHour) {
        mutableStateOf(computeNight(dayStartHour, nightStartHour))
    }
    LaunchedEffect(dayStartHour, nightStartHour) {
        while (true) {
            night = computeNight(dayStartHour, nightStartHour)
            delay(60_000L)
        }
    }
    return night
}

private fun computeNight(dayStartHour: Int, nightStartHour: Int): Boolean {
    // ساعتان متساويتان تعنيان «لا ليل ولا نهار»؛ بلا هذا الحارس يصير الشرط
    // صادقًا دائمًا فيُقفل التطبيق على الوضع الداكن بلا سببٍ ظاهر للمستعمل
    if (dayStartHour == nightStartHour) return false
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    // النهار قد يلتفّ حول منتصف الليل (نهارٌ من 22 إلى 5 مثلًا)، فالمقارنة على حالين
    return if (dayStartHour <= nightStartHour) {
        hour < dayStartHour || hour >= nightStartHour
    } else {
        hour in nightStartHour until dayStartHour
    }
}

/**
 * @param mode وضع السمة المختار
 * @param dayStartHour ساعة بدء النهار في الوضع التلقائيّ
 * @param nightStartHour ساعة بدء الليل في الوضع التلقائيّ
 */
@Composable
fun GtSpeedometerTheme(
    mode: ThemeMode = ThemeMode.DEFAULT,
    dayStartHour: Int = 6,
    nightStartHour: Int = 19,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val timeDark = isNightNow(dayStartHour, nightStartHour)
    val dark = when (mode) {
        ThemeMode.AUTO_TIME -> timeDark
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (dark) DarkPalette else LightPalette

    // `LocalContentColor` كان غائبًا، وافتراضه في Material 3 أسودُ صريح — وهو سبب
    // اختفاء نصوص سجلّ الرحلات على الخلفيّة الداكنة. توفيره هنا يُصلح كلّ نصٍّ بلا
    // لونٍ صريح في التطبيق دفعةً واحدة.
    CompositionLocalProvider(
        LocalGtColors provides colors,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(colorScheme = schemeOf(colors), content = content)
    }
}

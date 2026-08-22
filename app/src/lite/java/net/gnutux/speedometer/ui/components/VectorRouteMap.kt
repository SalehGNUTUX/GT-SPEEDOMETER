package net.gnutux.speedometer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File
import net.gnutux.speedometer.core.trip.TrackPoint

/**
 * بديلُ النكهة الخفيفة: **لا شيء**.
 *
 * الخفيفة لا تحمل MapLibre أصلًا (‎21‎ م.ب من المكتبات الأصليّة)، فلا محرّك يرسم
 * المتجهيّ فيها. والتوقيع هنا مطابقٌ لتوقيع النسخة الكاملة حرفًا بحرف، فتُترجم شاشة
 * الرحلات في النكهتين بلا شرطٍ ولا `BuildConfig` متناثرٍ في الواجهة.
 *
 * ولا يقع نداؤها عمليًّا: [VectorMapsAvailable] كاذبةٌ في هذه النكهة، فلا يُعرض خيار
 * «متجهيّة» ولا يُختار. وهي هنا لتصحّ الترجمة لا لتُنفَّذ — وإن نُوديت رسمت فراغًا،
 * وهو أهون من انهيار.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun VectorRouteMap(
    archive: File,
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    showControls: Boolean = true,
) {
    // لا شيء عمدًا
}

/** هل تحمل هذه النكهة محرّكًا متجهيًّا؟ — لا. */
const val VectorMapsAvailable = false

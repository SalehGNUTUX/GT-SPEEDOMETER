package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.map.MapSource
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.AccentDim
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

// خريطة المسار المحفوظ. اخترنا osmdroid لأنّ القاعدة تمنع خدمات Google،
// ولأنّها الوحيدة التي تقرأ أرشيفات البلاط المحلّيّة (mbtiles وأخواتها) مباشرة.
//
// الخريطة عرض Android تقليديّ ذو دورة حياة خاصّة به، فأربعة قيود تحكم هذا الملفّ:
// — لا يُنشأ [MapView] قبل أن يُحسم المصدر: مُنشئه يبني مزوّدًا شبكيًّا افتراضيًّا،
//   فإنشاؤه ثمّ تبديل مزوّده كان يعني بلاطاتٍ تُنزَّل في اللحظة التي نريد فيها ألّا
//   يُلمس الإنترنت أصلًا.
// — لا شيء يمسّ التكبير بعد أوّل ملاءمة، فتبقى حركة المستعمل ملكه.
// — الاستئناف والإيقاف والتحرير تُشتقّ من دورة حياة المالك لا من إعادة التأليف.
// — المصدر الحيّ يُكتب على الخريطة نفسها: خريطةٌ فارغة يجب أن تُشخَّص بنظرة، لا أن
//   تُترك للمستعمل يظنّ التطبيق معطوبًا.

/** الزوايا مستديرة داخل المُركّب نفسه كي لا تطغى البلاطات المربّعة على أيّ موضع استعمال */
private val MapShape = RoundedCornerShape(16.dp)

/** المزوّد ومصدرُه معًا: تبديل أحدهما دون الآخر يعني شارةً تكذب على ما يُرسم */
private class MapBinding(val source: MapSource, val provider: MapTileProviderBase)

/**
 * يرسم مسار الرحلة فوق بلاطات محلّيّة إن وُجدت، وإلّا فوق بلاطات OSM.
 *
 * @param points نقاط المسار كما قرأها [net.gnutux.speedometer.core.trip.GpxReader].
 * @param invertTiles قلب ألوان البلاطات؛ يليق بالسمة الداكنة ويُتعب العين في الفاتحة.
 * @param preferOffline تفضيل الأرشيف المحلّيّ على الإنترنت متى غطّى موضع الرحلة.
 * @param modifier القياس يأتي من المُستدعي؛ [MapView] نفسه لا يفرض ارتفاعًا.
 */
@Composable
fun RouteMap(
    points: List<TrackPoint>,
    invertTiles: Boolean,
    preferOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backdrop = SurfaceHigh

    // أوّل من يسأل يُطلق المسح. المكان الأصحّ `SpeedoApp.onCreate`، وهو خارج نطاق
    // هذا التغيير؛ والمسح على أيّ حال يقع على خيط قرصٍ لا على الخيط الرئيس.
    val offlineMaps = remember(context) { OfflineMaps.of(context) }
    val library by offlineMaps.library.collectAsStateWithLifecycle()

    // ثلاثة مواضع لا موضعٌ واحد: البداية والوسط والنهاية. أرشيفٌ يغطّي المنطلق دون
    // المقصد يُخرج نصف خريطةٍ ونصف فراغ، وهو ما لا يجوز أن يُعرض على أنّه «محلّيّة».
    val probes = remember(points) {
        if (points.isEmpty()) {
            emptyList()
        } else {
            listOf(points.first(), points[points.size / 2], points.last())
                .map { it.latitude to it.longitude }
        }
    }

    // القرار كلّه خارج الخيط الرئيس: جسّ التغطية يفتح قواعد sqlite، وبناء المزوّد
    // يفتحها ثانيةً. وقيمته الأولى `null` تعني «لم يُحسم بعد» لا «إنترنت»، فلا تُنزَّل
    // بلاطةٌ واحدة قبل أن نعرف أنّ المحلّيّة لا تكفي.
    val binding by produceState<MapBinding?>(null, library, preferOffline, probes, offlineMaps) {
        if (!library.scanned) {
            value = null
            return@produceState
        }
        val useOffline = preferOffline && library.hasArchives && offlineMaps.covers(probes)
        val source = if (useOffline) MapSource.OFFLINE else MapSource.ONLINE
        val provider = withContext(Dispatchers.IO) { offlineMaps.providerFor(source) }
        // إلغاءٌ يقع بين بناء المزوّد وإسناده يترك قواعد sqlite مفتوحةً بلا مالكٍ
        // يُغلقها: لا `MapView` سيستلمه، ولا `onDetach` سيُنادى عليه.
        if (!isActive) {
            provider.detach()
            return@produceState
        }
        value = MapBinding(source, provider)
    }

    Box(
        modifier
            .clip(MapShape)
            .background(backdrop, MapShape)
    ) {
        val current = binding
        if (current != null) {
            // `key` لا `remember` وحده: `AndroidView` يمسك عرضه مدى حياة عقدته، فتبديل
            // المزوّد بعد رصدِ ملفٍّ جديد يحتاج عقدةً جديدة لا وسمًا جديدًا.
            key(current) {
                RouteMapSurface(
                    points = points,
                    invertTiles = invertTiles,
                    binding = current,
                    modifier = Modifier.matchParentSize(),
                )
            }
            MapSourceBadge(
                source = current.source,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
    }
}

/** شارة المصدر الحيّ: سطرٌ واحد يفصل «الأرشيف لا يغطّي هنا» عن «التطبيق معطوب» */
@Composable
private fun MapSourceBadge(source: MapSource, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(source.label),
        style = MaterialTheme.typography.labelSmall.copy(
            color = Bg,
            fontWeight = FontWeight.Bold,
        ),
        modifier = modifier
            .background(Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** [MapView] وحدها: تُنشأ بمزوّدٍ محسوم، وتموت بموت عقدتها. */
@Composable
private fun RouteMapSurface(
    points: List<TrackPoint>,
    invertTiles: Boolean,
    binding: MapBinding,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ألوان السمة ونصوص الموارد تُقرأ داخل التركيب وحده، فتُرفع إلى متغيّرات محلّية
    // تلتقطها لامبدا `update` غير المُركّبة. و`toArgb` لا `value.toInt()`: القيمة
    // المعبّأة 64-بت تُقتطع إلى صفر، فيخرج الخطّ شفّافًا تمامًا.
    val routeColor = Accent.toArgb()
    val voidColor = SurfaceHigh.toArgb()
    val voidGridColor = AccentDim.toArgb()
    val startTitle = stringResource(R.string.map_start)
    val endTitle = stringResource(R.string.map_end)

    // المزوّد يُمرَّر إلى المُنشئ لا بعده: `MapView(context)` وحده يبني مزوّدًا شبكيًّا
    // ويفتح ذاكرة بلاطاته فورًا، وهو ما نتجنّبه في الوضع المحلّيّ.
    val map = remember(context, binding) {
        MapView(context, binding.provider).apply {
            setMultiTouchControls(true)
            // القطع صريح في الوضع المحلّيّ: المزوّد بلا وحدة تنزيل أصلًا، وهذه
            // طبقة أمانٍ ثانية تمنع أيّ وحدةٍ شبكيّة تُضاف لاحقًا من الانفلات.
            setUseDataConnection(binding.source == MapSource.ONLINE)
        }
    }

    // `onDetach` يوقف خيوط جلب البلاطات ويحرّر ذاكرتها والأرشيفات المفتوحة. يُستدعى
    // من `onDispose` ومن `onRelease` معًا، وقد يجتمعان، فالراية تمنع تحريرًا مزدوجًا.
    val detached = remember(map) { AtomicBoolean(false) }
    val detachOnce = remember(map) {
        {
            if (detached.compareAndSet(false, true)) {
                map.onPause()
                map.onDetach()
            }
        }
    }

    DisposableEffect(lifecycleOwner, map) {
        // الاستئناف مربوط بدورة حياة المالك لا بإعادة التأليف: نداؤه في `update`
        // كان يعيد تشغيل خيوط البلاطات مع كلّ تأليفٍ جديد.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            detachOnce()
        }
    }

    // ملاءمة الحدود مرّةً واحدة لكلّ قائمة نقاط لا في كلّ تأليف: `zoomToBoundingBox`
    // تستدعي `requestLayout` داخليًّا، فأيّ تأليفٍ للأب — فتح حوارٍ مثلًا — كان
    // ينتزع تكبير المستعمل وإزاحته. المفتاح هو القائمة نفسها، فلا تتكرّر الملاءمة
    // ما لم تتبدّل الرحلة المعروضة.
    LaunchedEffect(map, points) {
        if (points.size < 2) return@LaunchedEffect
        val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        // القياس الفعليّ للعرض شرط الملاءمة، فنؤجّلها إلى ما بعد أوّل تخطيط عبر post،
        // ونلفّها بـ runCatching لأنّها ترمي إن كان الصندوق منحلًّا.
        map.post { runCatching { map.zoomToBoundingBox(box.increaseByScale(1.25f), false) } }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            val tiles = view.overlayManager.tilesOverlay
            tiles.setColorFilter(if (invertTiles) TilesOverlay.INVERT_COLORS else null)

            // بلاطةٌ لا مصدر لها تُرسم بلون سطح التطبيق وشبكةٍ خافتة بدل الرماديّ
            // الأصمّ: الفراغ حينها يُقرأ «لا بيانات هنا» لا «الخريطة تعطّلت».
            tiles.setLoadingBackgroundColor(voidColor)
            tiles.setLoadingLineColor(voidGridColor)

            // نمسح طبقاتنا وحدها كي لا نُتلف طبقة البلاطات التي يديرها osmdroid.
            view.overlays.removeAll { it is Polyline || it is Marker }

            if (points.size < 2) {
                view.invalidate()
                return@AndroidView
            }

            val geo = points.map { GeoPoint(it.latitude, it.longitude) }

            view.overlays.add(
                Polyline(view).apply {
                    setPoints(geo)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = routeColor
                }
            )

            view.overlays.add(
                Marker(view).apply {
                    position = geo.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = startTitle
                }
            )

            view.overlays.add(
                Marker(view).apply {
                    position = geo.last()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = endTitle
                }
            )

            view.invalidate()
        },
        onRelease = { detachOnce() },
    )
}

package net.gnutux.speedometer.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

// خريطة المسار المحفوظ. اخترنا osmdroid لأنّ القاعدة تمنع خدمات Google،
// وبلاطات OSM تُجلب مباشرة دون أيّ SDK مغلق.
//
// الخريطة عرض Android تقليديّ ذو دورة حياة خاصّة به، فثلاثة قيود تحكم هذا الملفّ:
// — تهيئة osmdroid تسبق أوّل `MapView`، لأنّ مُنشئه يفتح قاعدة ذاكرة البلاطات فورًا.
// — لا شيء يمسّ التكبير بعد أوّل ملاءمة، فتبقى حركة المستعمل ملكه.
// — الاستئناف والإيقاف والتحرير تُشتقّ من دورة حياة المالك لا من إعادة التأليف.

/** الزوايا مستديرة داخل المُركّب نفسه كي لا تطغى البلاطات المربّعة على أيّ موضع استعمال */
private val MapShape = RoundedCornerShape(16.dp)

/**
 * يرسم مسار الرحلة فوق بلاطات OSM.
 *
 * @param points نقاط المسار كما قرأها [net.gnutux.speedometer.core.trip.GpxReader].
 * @param invertTiles قلب ألوان البلاطات؛ يليق بالسمة الداكنة ويُتعب العين في الفاتحة.
 * @param modifier القياس يأتي من المُستدعي؛ [MapView] نفسه لا يفرض ارتفاعًا.
 */
@Composable
fun RouteMap(points: List<TrackPoint>, invertTiles: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ألوان السمة ونصوص الموارد تُقرأ داخل التركيب وحده، فتُرفع إلى متغيّرات محلّية
    // تلتقطها لامبدا `update` غير المُركّبة. و`toArgb` لا `value.toInt()`: القيمة
    // المعبّأة 64-بت تُقتطع إلى صفر، فيخرج الخطّ شفّافًا تمامًا.
    val routeColor = Accent.toArgb()
    val backdrop = SurfaceHigh
    val startTitle = stringResource(R.string.map_start)
    val endTitle = stringResource(R.string.map_end)

    // التهيئة داخل كتلة إنشاء العرض نفسها: هذا وحده يضمن سبقها للمُنشئ. لو تُركت
    // في تأثيرٍ جانبيّ لجرت بعد `factory`، وقد فتح المُنشئ حينها ذاكرة البلاطات
    // على المسار الافتراضيّ خارج مجلّد التطبيق.
    val map = remember(context) {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
        }
    }

    // `onDetach` يوقف خيوط جلب البلاطات ويحرّر ذاكرتها. يُستدعى من `onDispose`
    // ومن `onRelease` معًا، وقد يجتمعان، فالراية تمنع تحريرًا مزدوجًا.
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
        modifier = modifier
            .clip(MapShape)
            .background(backdrop, MapShape),
        update = { view ->
            view.overlayManager.tilesOverlay.setColorFilter(
                if (invertTiles) TilesOverlay.INVERT_COLORS else null
            )

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

/** مسار البلاطات داخل الذاكرة المؤقّتة للتطبيق، ووكيل مستخدم باسم الحزمة كما تشترط OSM. */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

    val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
    config.osmdroidBasePath = base

    val tiles = File(config.osmdroidBasePath, "tiles").apply { mkdirs() }
    config.osmdroidTileCache = tiles

    config.userAgentValue = context.packageName
}

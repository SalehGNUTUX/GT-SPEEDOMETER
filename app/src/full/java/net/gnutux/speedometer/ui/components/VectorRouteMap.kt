package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import net.gnutux.speedometer.R
import net.gnutux.speedometer.ui.theme.TextSecondary
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import net.gnutux.speedometer.core.map.VectorMaps
import net.gnutux.speedometer.core.trip.TrackPoint

/**
 * خريطة مسارٍ **متجهيّة** تُرسم من أرشيف ‎.pmtiles‎ محلّيّ.
 *
 * ## بجانب [RouteMap] النقطيّة لا بديلًا عنها
 * من عنده أرشيفٌ نقطيٌّ يعمل اليوم يجب ألّا ينكسر غدًا. فهذه تُستدعى حين يوجد
 * ‎.pmtiles‎ صالحٌ ويكون مصدر الخريطة المختار متجهيًّا، وفيما عدا ذلك تبقى النقطيّة.
 *
 * ## النمط يُضبط **مرّةً** لا في كلّ إعادة تركيب
 * كان `setStyle` داخل `update` فيُعاد بناء النمط كلَّه مع كلّ إعادة تركيبٍ للشاشة:
 * تُهدَم مصادرُه وتُبنى، وتُلغى طلبات البلاطات الجارية قبل أن تكتمل. والنتيجة خريطةٌ
 * أساسيّةٌ لا تظهر أبدًا بينما يظهر المسار — لأنّ المسار `GeoJsonSource` في الذاكرة
 * يُرسم فورًا، والبلاطات تُقرأ من ملفٍّ بمئات الميغابايت فتحتاج زمنًا لا تُمنحه.
 *
 * فصار النمط يُضبط عند أوّل تهيئةٍ لكلّ أرشيف، وتحديثُ المسار استبدالَ بياناتٍ في
 * مصدرٍ قائم لا إعادةَ بناء.
 *
 * ## ولا يُترك الفشل صامتًا
 * حين يتعذّر تحميل النمط أو مصدره لا تظهر رسالةٌ من المحرّك، بل خريطةٌ سوداء. فتُلتقط
 * أخطاؤه ([onError]) ليقولها المستدعي للمستعمل — «لا تدّعِ ما لا تملك» تقتضي أن نقول
 * «تعذّر» لا أن نعرض فراغًا يظنّه المستعمل خريطةً فارغة.
 *
 * ## دورة حياة `MapView` يدويّة
 * `MapView` عرضٌ تقليديٌّ يشترط تمرير أحداث دورة الحياة إليه بيدك، و`AndroidView` لا
 * تعرف عنها شيئًا. وإغفالُ ذلك يُبقي محرّك الرسم الأصليّ حيًّا بعد مغادرة الشاشة.
 */
@Composable
fun VectorRouteMap(
    archive: File,
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    showControls: Boolean = true,
) {
    val context = LocalContext.current

    // التهيئة مرّةً لكلّ عمليّة؛ `getInstance` آمنٌ للتكرار
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    var styledFor by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf<MapLibreMap?>(null) }
    var probe by remember { mutableStateOf<String?>(null) }

    DisposableEffect(mapView) {
        // الفشل يُبلَّغ لا يُبتلع: المحرّك يرسم سوادًا ولا يقول شيئًا. والمستمع على
        // `MapView` لا على `MapLibreMap` — هناك موضعه في هذه المكتبة.
        val failure = MapView.OnDidFailLoadingMapListener { reason -> onError(reason.orEmpty()) }
        mapView.addOnDidFailLoadingMapListener(failure)
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failure)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
            update = { view ->
                view.getMapAsync { map ->
                    held = map
                    val key = archive.absolutePath
                    if (styledFor != key) {
                        styledFor = key
                        map.setStyle(
                            Style.Builder().fromJson(VectorMaps.styleJson(context, archive))
                        ) { style ->
                            drawRoute(style, points)
                            frameRoute(map, points)
                            map.addOnCameraIdleListener { probe = probeOf(map) }
                        }
                    } else {
                        // النمط قائم: تُستبدل بيانات المسار وحدها
                        map.style?.let { style ->
                            drawRoute(style, points)
                            frameRoute(map, points)
                        }
                    }
                }
            },
        )

        if (showControls) {
            // الأزرار لازمةٌ ولو كانت الإيماءة تكفي: من يقود بيدٍ واحدة لا يقرص
            // بإصبعين، وهي عين ما في خريطة OsmAnd — فلا يتعلّم المستعمل خريطتين.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                MapControlButton(
                    icon = Icons.Filled.Add,
                    label = R.string.map_zoom_in,
                    onClick = { held?.animateCamera(CameraUpdateFactory.zoomBy(1.0)) },
                )
                MapControlButton(
                    icon = Icons.Filled.Remove,
                    label = R.string.map_zoom_out,
                    onClick = { held?.animateCamera(CameraUpdateFactory.zoomBy(-1.0)) },
                )
                MapControlButton(
                    icon = Icons.Filled.FitScreen,
                    label = R.string.map_reset_zoom,
                    onClick = { held?.let { frameRoute(it, points) } },
                )
            }

            // **قياسٌ لا زينة.** الخريطة تُرسم عند التقريب الشديد وتسوَدّ عند الواسع،
            // ولا نعرف أعند حدٍّ بعينه تقف البلاطات أم تصل ولا تُرسم. فهذا السطر
            // يقول درجة التكبير وعدد المعالم التي بلغت المحرّك فعلًا من المصدر:
            // صفرٌ عند ‎z13‎ وألوفٌ عند ‎z15‎ يعني أنّ البلاطة لم تصل، وعددٌ كبيرٌ
            // مع سوادٍ يعني أنّها وصلت والنمط لا يرسمها. وهما علّتان لا علّة.
            probe?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * درجةُ التكبير وعددُ المعالم الواصلة من المصدر، سطرًا واحدًا.
 *
 * `querySourceFeatures` تسأل **ما وصل إلى المحرّك** لا ما في الملفّ، وذلك بالضبط ما
 * نحتاج معرفته. وتُسأل عند سكون الكاميرا لا في كلّ إطار: هي مسحٌ على البلاطات
 * المحمَّلة، وتكرارها ستّين مرّةً في الثانية يقتل الرسم الذي نقيسه.
 */
private fun probeOf(map: MapLibreMap): String {
    val zoom = map.cameraPosition.zoom
    val style = map.style ?: return "z%.1f · —".format(Locale.US, zoom)
    val source = style.getSourceAs<org.maplibre.android.style.sources.VectorSource>(STYLE_SOURCE_ID)
    val counts = PROBED_LAYERS.associateWith { layer ->
        runCatching { source?.querySourceFeatures(arrayOf(layer), null)?.size ?: 0 }.getOrDefault(0)
    }
    val total = counts.values.sum()
    return "z%.1f · معالم %d (طرق %d · أرض %d)".format(
        Locale.US,
        zoom,
        total,
        counts["streets"] ?: 0,
        counts["land"] ?: 0,
    )
}

/** الطبقتان اللتان تُظهران السوادَ من عدمه: الطرق والغطاء الأرضيّ */
private val PROBED_LAYERS = listOf("streets", "land")

/** معرّف المصدر في كلا النمطين؛ لو تبدّل هناك سكت القياس هنا */
private const val STYLE_SOURCE_ID = "src"

/** يضع المسار في مصدره، ويُنشئ الطبقة أوّل مرّة فقط */
private fun drawRoute(style: Style, points: List<TrackPoint>) {
    if (points.size < 2) return
    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
    val feature = Feature.fromGeometry(line)

    val existing = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(feature)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ID, feature))
    }

    if (style.getLayer(LAYER_ID) == null) {
        style.addLayer(
            LineLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.lineColor(ROUTE_COLOR),
                PropertyFactory.lineWidth(ROUTE_WIDTH),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            )
        )
    }
}

/** الإطار على المسار كلِّه: من يفتح رحلةً يريد أن يراها كاملةً لا أن يبحث عنها */
private fun frameRoute(map: MapLibreMap, points: List<TrackPoint>) {
    if (points.size < 2) return
    val bounds = LatLngBounds.Builder()
        .includes(points.map { LatLng(it.latitude, it.longitude) })
        .build()
    runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING)) }
}

/** فيروزيّ اللوحة نفسه: المسار على الشاشة والمسار في الملفّ لونٌ واحد */
private const val ROUTE_COLOR = 0xFF00E5C7.toInt()
private const val ROUTE_WIDTH = 4f
private const val BOUNDS_PADDING = 48
private const val SOURCE_ID = "gt-route"
private const val LAYER_ID = "gt-route-line"

/** هل تحمل هذه النكهة محرّكًا متجهيًّا؟ — نعم. */
const val VectorMapsAvailable = true

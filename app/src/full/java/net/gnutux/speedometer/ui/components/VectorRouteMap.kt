package net.gnutux.speedometer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
) {
    val context = LocalContext.current

    // التهيئة مرّةً لكلّ عمليّة؛ `getInstance` آمنٌ للتكرار
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    var styledFor by remember { mutableStateOf<String?>(null) }

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

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                val key = archive.absolutePath
                if (styledFor != key) {
                    styledFor = key
                    map.setStyle(
                        Style.Builder().fromJson(VectorMaps.styleJson(context, archive))
                    ) { style ->
                        drawRoute(style, points)
                        frameRoute(map, points)
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
}

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
private fun frameRoute(map: org.maplibre.android.maps.MapLibreMap, points: List<TrackPoint>) {
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

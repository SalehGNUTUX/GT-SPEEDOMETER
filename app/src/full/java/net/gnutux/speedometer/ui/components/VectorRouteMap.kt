package net.gnutux.speedometer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
 * ## دورة حياة `MapView` يدويّة
 * `MapView` عرضٌ تقليديٌّ يشترط تمرير أحداث دورة الحياة إليه بيدك. و`AndroidView`
 * وحدها لا تفعل: تُنشئ العرض وتُحدّثه ولا تعرف عن `onStart`/`onStop` شيئًا. وإغفال
 * ذلك يُبقي محرّك الرسم الأصليّ حيًّا بعد مغادرة الشاشة — تسريبٌ يظهر بطاريّةً تنفد
 * وذاكرةً تُملأ. فـ`DisposableEffect` يتولّاها، و`onDestroy` في تنظيفه.
 *
 * ## لماذا `LineLayer` لا `Polyline`
 * الرسم المتجهيّ يقع على وحدة معالجة الرسوميّات، فمسارٌ من آلاف النقاط يُرسم بلا
 * تعثّر — بينما `Polyline` النقطيّة تُعيد رسم كلّ نقطةٍ على وحدة المعالجة المركزيّة.
 * والمسار مصدرُ `GeoJsonSource` واحد، فتحديثه استبدالُ بياناتٍ لا إعادةُ بناء طبقة.
 */
@Composable
fun VectorRouteMap(
    archive: File,
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // التهيئة مرّةً لكلّ عمليّة: `MapLibre.getInstance` آمنٌ للتكرار، و`remember`
    // يمنع تكرارًا بلا فائدة عند كلّ إعادة تركيب.
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
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
                // النمط نصًّا لا عنوانًا: فيه مسار الأرشيف المحلّيّ محلولًا
                map.setStyle(Style.Builder().fromJson(VectorMaps.styleJson(context, archive))) { style ->
                    if (points.size < 2) return@setStyle

                    val line = LineString.fromLngLats(
                        points.map { Point.fromLngLat(it.longitude, it.latitude) }
                    )
                    // المصدر يُستبدَل لا يُضاف مرّتين: `setStyle` تُعيد بناء النمط عند
                    // كلّ تحديث، وإضافةُ مصدرٍ بمعرّفٍ قائم تُلقي استثناءً
                    style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(Feature.fromGeometry(line))
                        ?: style.addSource(GeoJsonSource(SOURCE_ID, Feature.fromGeometry(line)))

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

                    // الإطار على المسار كلِّه: من يفتح رحلةً يريد أن يراها كاملةً، لا
                    // أن يبحث عنها بالتقريب والتحريك
                    val bounds = LatLngBounds.Builder()
                        .includes(points.map { LatLng(it.latitude, it.longitude) })
                        .build()
                    runCatching {
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING))
                    }
                }
            }
        },
    )
}

/** فيروزيّ اللوحة نفسه: المسار على الشاشة والمسار في الملفّ لونٌ واحد */
private const val ROUTE_COLOR = 0xFF00E5C7.toInt()
private const val ROUTE_WIDTH = 4f
private const val BOUNDS_PADDING = 48
private const val SOURCE_ID = "gt-route"
private const val LAYER_ID = "gt-route-line"

/** هل تحمل هذه النكهة محرّكًا متجهيًّا؟ — نعم. */
const val VectorMapsAvailable = true

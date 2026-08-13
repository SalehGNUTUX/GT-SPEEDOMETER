package net.gnutux.speedometer.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.theme.Accent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * خريطة مسار الرحلة بـ osmdroid (رخصة Apache 2.0) على بلاطات OpenStreetMap.
 *
 * البلاطات تُخزَّن في ذاكرة التطبيق المؤقّتة، فالمسار الذي فُتح مرّةً يُعرض بعدها
 * دون إنترنت. ولو لم تصل بلاطة قطّ بقي الخطّ مرسومًا على خلفيّة فارغة — المسار
 * نفسه لا يعتمد على الشبكة.
 */
@Composable
fun RouteMap(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        configureOsmdroid(context)
        onDispose { }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // قلب ألوان البلاطات: خريطة داكنة تُناسب سمة التطبيق ولا تُبهر ليلًا
                overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                setUseDataConnection(true)
            }
        },
        update = { map ->
            map.overlays.removeAll { it is Polyline || it is Marker }
            if (points.size < 2) {
                map.invalidate()
                return@AndroidView
            }

            val geo = points.map { GeoPoint(it.latitude, it.longitude) }

            map.overlays.add(
                Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = Accent.value.toInt()
                }
            )
            map.overlays.add(
                Marker(map).apply {
                    position = geo.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "البداية"
                }
            )
            map.overlays.add(
                Marker(map).apply {
                    position = geo.last()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "النهاية"
                }
            )

            map.post {
                runCatching {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.25f), false)
                }
            }
            map.onResume()
            map.invalidate()
        },
        onRelease = { map -> map.onPause() },
    )
}

private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // مسار الذاكرة المؤقّتة داخل التطبيق: لا إذن تخزين ولا تلويث لذاكرة الجهاز
    config.osmdroidBasePath = File(context.cacheDir, "osmdroid").apply { mkdirs() }
    config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles").apply { mkdirs() }
    // خوادم OSM ترفض الطلبات مجهولة الهوية
    config.userAgentValue = context.packageName
}

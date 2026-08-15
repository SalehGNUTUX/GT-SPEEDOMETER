package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.TrackDim

// المسار بلا خريطة أساس: آخر ما يُعرض حين لا يوجد أرشيفٌ محلّيّ ولا OsmAnd ولا اتّصال.
//
// لماذا يستحقّ هذا مُركّبًا كاملًا بدل رسالة «لا خريطة»؟ لأنّ شكل المسار وحده يُجيب
// عن أكثر ما يُسأل عن رحلةٍ محفوظة: أين درتُ، وهل عدتُ من حيث بدأت، وكم كانت
// المسافة. البلاطات زينةٌ لهذا الجواب لا شرطٌ له.

private val SketchShape = RoundedCornerShape(16.dp)

/** نصف قطر الأرض بالمتر عند خطّ الاستواء — أساس تحويل درجات الإسقاط إلى أمتار */
private const val METERS_PER_DEGREE = 111_320.0

/** حدّ ميركاتور المعتاد: ما بعده يتمدّد بلا نهاية */
private const val MERCATOR_MAX_LAT = 85.05112878

/** فرقٌ أصغر من هذا في درجات الإسقاط لا يصنع خطًّا على شاشة: مسارٌ واقفٌ في مكانه */
private const val SPAN_EPSILON = 1e-9

/**
 * يرسم مسار الرحلة وحده على سطحٍ خالٍ.
 *
 * @param points نقاط المسار كما قرأها [net.gnutux.speedometer.core.trip.GpxReader].
 * @param modifier القياس من المُستدعي؛ الرسم يملأ ما يُعطى.
 */
@Composable
fun RouteSketch(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    // ألوان اللوحة تُقرأ من التركيب وتُرفع قبل الرسم: `DrawScope` ليس تركيبًا.
    val backdrop = SurfaceHigh
    val gridColor = TrackDim
    val routeColor = Accent
    val startColor = Accent
    val endColor = Danger
    val haloColor = Bg

    val density = LocalDensity.current
    val padPx = with(density) { SKETCH_PADDING.toPx() }
    val strokePx = with(density) { 3.dp.toPx() }
    val gridPx = with(density) { 1.dp.toPx() }
    val startRadiusPx = with(density) { 11.5f.dp.toPx() }
    val endRadiusPx = with(density) { 13.dp.toPx() }
    val haloPx = with(density) { 2.dp.toPx() }

    // القياس يأتي من التخطيط لا من `BoxWithConstraints`: قراءة خصائص مُستقبِلٍ ضمنيّ
    // من داخل لامدا الرسم بنيةٌ هشّة، وهذه حالةٌ صريحة يراها المصرّف كاملةً.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // الإسقاط في `remember` لا في `Canvas`: مسارٌ فيه خمسة آلاف نقطة يُعاد إسقاطه
    // ستّين مرّةً في الثانية بلا سبب، وهو أثقل ما في هذه الشاشة.
    val sketch = remember(points, canvasSize, padPx) { buildSketch(points, canvasSize, padPx) }

    Box(
        modifier
            .clip(SketchShape)
            .background(backdrop, SketchShape)
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { canvasSize = it }
        ) {
            drawSketchGrid(gridColor, gridPx)
            val plan = sketch ?: return@Canvas

            if (!plan.single) {
                drawPath(
                    path = plan.path,
                    color = routeColor,
                    style = Stroke(width = strokePx),
                )
            }
            // النهاية بعد البداية: في رحلةٍ دائريّة يقع الطرفان على موضعٍ واحد،
            // والأحدث أولى بأن يُرى — كما في `RouteMap` سواءً بسواء.
            drawRingMarker(plan.start, startColor, haloColor, startRadiusPx, haloPx)
            if (!plan.single) {
                drawDiamondMarker(plan.end, endColor, haloColor, endRadiusPx, haloPx)
            }
        }

        if (points.isEmpty()) {
            Text(
                text = stringResource(R.string.trip_no_route),
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Text(
            text = stringResource(R.string.map_sketch_badge),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Bg,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Accent, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Text(
            text = stringResource(R.string.map_sketch_note),
            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )

        if (sketch != null && sketch.barPx > 0f) {
            ScaleBar(
                lengthPx = sketch.barPx,
                meters = sketch.barMeters,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * مقياس الرسم: خطٌّ بطولٍ معلوم ورقمُه فوقه.
 *
 * مُركّبٌ لا رسمٌ داخل [Canvas]، لأنّ النصّ في `DrawScope` يحتاج `Paint` أصليًّا
 * وقياسًا يدويًّا، وهنا يكفي أن نحوّل الطول إلى `dp` ونترك التخطيط يفعل الباقي.
 */
@Composable
private fun ScaleBar(
    lengthPx: Float,
    meters: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val lengthDp = with(LocalDensity.current) { lengthPx.toDp() }
    val label = if (meters >= 1_000.0) {
        stringResource(R.string.map_scale_km, Fmt.count((meters / 1_000.0).toInt()))
    } else {
        stringResource(R.string.map_scale_m, Fmt.count(meters.toInt().coerceAtLeast(1)))
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = color),
        )
        Box(
            Modifier
                .width(lengthDp)
                .height(2.dp)
                .background(color)
        )
    }
}

/** شبكةٌ خافتة: مرجعٌ بصريّ يمنع قراءة السطح على أنّه فراغ، بلا أن تزاحم المسار */
private fun DrawScope.drawSketchGrid(color: Color, strokeWidth: Float) {
    val step = min(size.width, size.height) / GRID_CELLS
    if (step <= 0f) return
    var x = step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth, alpha = GRID_ALPHA)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth, alpha = GRID_ALPHA)
        y += step
    }
}

/**
 * علامة البداية: حلقةٌ مفرَّغة بلون التمييز داخل هالةٍ بلون الخلفيّة.
 * الشكل نفسه المرسوم في [RouteMap]، فلا يتعلّم المستعمل رمزين لمعنًى واحد.
 *
 * وصارت `internal` لأنّ [RouteMap] صار يرسم العلامتين بنفسه فوق صورة OsmAnd أيضًا:
 * نسخُ الرسم هناك يعني شكلين ينحرف أحدهما عن الآخر عند أوّل تعديل.
 */
internal fun DrawScope.drawRingMarker(
    center: Offset,
    fill: Color,
    halo: Color,
    radius: Float,
    haloWidth: Float,
) {
    drawCircle(halo, radius, center)
    drawCircle(fill, radius - haloWidth, center)
    drawCircle(halo, (radius - haloWidth) * 0.38f, center)
}

/** علامة النهاية: معيّنٌ مصمت بلون الخطر، وهي مقابل الحلقة في [RouteMap] */
internal fun DrawScope.drawDiamondMarker(
    center: Offset,
    fill: Color,
    halo: Color,
    radius: Float,
    haloWidth: Float,
) {
    drawPath(diamondPath(center, radius), halo)
    drawPath(diamondPath(center, radius - haloWidth), fill)
}

private fun diamondPath(center: Offset, radius: Float): Path = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius, center.y)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius, center.y)
    close()
}

/** كلّ ما يُرسم، محسوبًا مرّةً لكلّ (مسار × قياس) */
private class Sketch(
    val path: Path,
    val start: Offset,
    val end: Offset,
    /** نقطةٌ واحدة أو مسارٌ لا يتحرّك: تُرسم علامةٌ واحدة ولا خطّ ولا مقياس */
    val single: Boolean,
    val barPx: Float,
    val barMeters: Double,
)

/**
 * الإسقاط والقياس.
 *
 * ميركاتور لا إسقاطًا خطّيًّا: تصحيح `cos(lat)` وحده يستقيم في مسارٍ قصير، أمّا رحلةٌ
 * تمتدّ شمالًا فيتغيّر فيها التصحيح مع كلّ نقطة. ومقياسٌ واحد للمحورين مع توسيطٍ في
 * الفائض، وإلّا خرجت دائرةٌ بيضاويّة ومربّعٌ مستطيلًا.
 *
 * والحرس هنا لا تجميل: مسارٌ بنقطةٍ واحدة، أو نقاطٌ متطابقة لراكبٍ وقف مكانه، كلاهما
 * امتدادٌ صفريّ — والقسمة عليه تُخرج `Infinity` يسري في الإحداثيّات كلّها.
 */
private fun buildSketch(points: List<TrackPoint>, size: IntSize, padPx: Float): Sketch? {
    if (points.isEmpty() || size.width <= 0 || size.height <= 0) return null

    val count = points.size
    val projectedX = DoubleArray(count)
    val projectedY = DoubleArray(count)
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    var latitudeSum = 0.0

    for (i in 0 until count) {
        val point = points[i]
        val x = point.longitude
        val y = mercatorY(point.latitude)
        projectedX[i] = x
        projectedY[i] = y
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        latitudeSum += point.latitude
    }

    val availableW = (size.width - 2f * padPx).coerceAtLeast(1f).toDouble()
    val availableH = (size.height - 2f * padPx).coerceAtLeast(1f).toDouble()
    val spanX = maxX - minX
    val spanY = maxY - minY

    // مقياسٌ واحد للمحورين. وحين ينعدم أحد الامتدادين يُؤخذ من الآخر، وحين ينعدمان
    // معًا لا مقياس أصلًا — وهذا هو الحارس الذي يمنع القسمة على صفر.
    val scale = when {
        spanX > SPAN_EPSILON && spanY > SPAN_EPSILON ->
            min(availableW / spanX, availableH / spanY)

        spanX > SPAN_EPSILON -> availableW / spanX
        spanY > SPAN_EPSILON -> availableH / spanY
        else -> 0.0
    }

    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0
    val midW = size.width / 2.0
    val midH = size.height / 2.0

    // نُسقط حول مركز الصندوق: التوسيط يكفي عن حساب إزاحةٍ مستقلّة لكلّ محور،
    // ويصحّ من نفسه حين يكون المقياس صفرًا (نقطةٌ واحدة في القلب).
    fun screenX(value: Double): Float = (midW + (value - centerX) * scale).toFloat()

    // المحور الرأسيّ مقلوب: الشمال أعلى، وإحداثيّ الشاشة يزيد نزولًا.
    fun screenY(value: Double): Float = (midH - (value - centerY) * scale).toFloat()

    val single = scale <= 0.0 || count < 2
    val path = Path()
    if (!single) {
        path.moveTo(screenX(projectedX[0]), screenY(projectedY[0]))
        for (i in 1 until count) {
            path.lineTo(screenX(projectedX[i]), screenY(projectedY[i]))
        }
    }

    val start = Offset(screenX(projectedX[0]), screenY(projectedY[0]))
    val end = Offset(screenX(projectedX[count - 1]), screenY(projectedY[count - 1]))

    // متر لكلّ بكسل: درجة الطول تساوي `111320 × cos(lat)` مترًا، وميركاتور يقسم
    // على `cos(lat)` نفسه — فتصحّ النسبة عند خطّ العرض الوسطيّ للمسار.
    var barPx = 0f
    var barMeters = 0.0
    if (!single) {
        val centerLatitude = latitudeSum / count
        val metersPerUnit = METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))
        val metersPerPixel = abs(metersPerUnit / scale)
        if (metersPerPixel > 0.0 && metersPerPixel.isFinite()) {
            barMeters = niceLength(metersPerPixel * size.width * BAR_TARGET_FRACTION)
            barPx = (barMeters / metersPerPixel).toFloat()
            if (!barPx.isFinite() || barPx <= 1f || barPx > size.width * 0.6f) {
                barPx = 0f
                barMeters = 0.0
            }
        }
    }

    return Sketch(path, start, end, single, barPx, barMeters)
}

/** خطّ العرض في وحدات ميركاتور، معبَّرًا عنه بالدرجات كي يشترك مع الطول في مقياسٍ واحد */
private fun mercatorY(latitude: Double): Double {
    val clamped = latitude.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
    return Math.toDegrees(asinh(tan(Math.toRadians(clamped))))
}

/**
 * أقرب طولٍ «مقروء» لا يزيد على المطلوب: 1 أو 2 أو 5 مضروبةً في قوّة عشرة.
 * مقياسٌ يقول «٣٤٧ م» يُقرأ رقمًا لا مسافة.
 */
private fun niceLength(raw: Double): Double {
    if (raw <= 0.0 || !raw.isFinite()) return 0.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val step = when {
        normalized >= 5.0 -> 5.0
        normalized >= 2.0 -> 2.0
        else -> 1.0
    }
    return step * magnitude
}

private val SKETCH_PADDING = 22.dp
private const val GRID_CELLS = 6f
private const val GRID_ALPHA = 0.28f
private const val BAR_TARGET_FRACTION = 0.28

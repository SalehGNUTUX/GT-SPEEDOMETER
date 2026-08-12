package net.gnutux.speedometer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.TrackDim
import net.gnutux.speedometer.ui.theme.Warn
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f
private const val TICK_COUNT = 8

/**
 * قرص العداد. القوس يتلوّن بحسب المنطقة: عادي، فوق عتبة التحذير، ثم الحمراء.
 * التلوّن هنا مقصود لقارئ عجلان: راكب الدراجة يلمح اللون قبل أن يقرأ الرقم.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun SpeedGauge(
    speedKmh: Float,
    maxKmh: Int,
    warnKmh: Int,
    unitLabel: String,
    modifier: Modifier = Modifier,
) {
    val target = (speedKmh / maxKmh).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 140),
        label = "gaugeSweep",
    )
    val warnFraction = (warnKmh.toFloat() / maxKmh).coerceIn(0f, 1f)
    val activeColor = when {
        speedKmh >= maxKmh * 0.92f -> Danger
        speedKmh >= warnKmh -> Warn
        else -> Accent
    }

    val measurer = rememberTextMeasurer()
    val tickStyle = remember {
        TextStyle(color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.075f
            val labelRoom = stroke * 2.15f
            val radius = (size.minDimension - stroke) / 2f - labelRoom
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // المسار الخلفي كاملًا
            drawArc(
                color = TrackDim,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )

            // المنطقة الحمراء: من عتبة التحذير إلى النهاية، باهتة كي لا تزاحم القيمة الحيّة
            drawArc(
                color = Danger.copy(alpha = 0.30f),
                startAngle = START_ANGLE + SWEEP_ANGLE * warnFraction,
                sweepAngle = SWEEP_ANGLE * (1f - warnFraction),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Butt),
            )

            // القيمة الحيّة
            if (animated > 0.001f) {
                drawArc(
                    color = activeColor,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }

            drawTicks(
                center = center,
                radius = radius,
                stroke = stroke,
                maxKmh = maxKmh,
                measurer = measurer,
                style = tickStyle,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = Fmt.speed(speedKmh),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = activeColor,
                ),
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.titleMedium.copy(color = TextSecondary),
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    stroke: Float,
    maxKmh: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    for (i in 0..TICK_COUNT) {
        val fraction = i.toFloat() / TICK_COUNT
        val angleRad = Math.toRadians((START_ANGLE + SWEEP_ANGLE * fraction).toDouble())
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()

        val outer = radius - stroke / 2f - 2.dp.toPx()
        val inner = outer - stroke * 0.45f
        drawLine(
            color = TextPrimary.copy(alpha = 0.55f),
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth = 2.dp.toPx(),
        )

        val labelRadius = radius + stroke * 0.95f
        val value = (maxKmh * fraction).toInt()
        val text = String.format(Locale.US, "%d", value)
        val layout = measurer.measure(text, style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                center.x + cosA * labelRadius - layout.size.width / 2f,
                center.y + sinA * labelRadius - layout.size.height / 2f,
            ),
        )
    }
}

/** نسخة مصغّرة للطبقة فوق الكاميرا، بلا تدرّجات ولا أرقام صغيرة */
@Composable
fun CompactGauge(
    speedKmh: Float,
    maxKmh: Int,
    warnKmh: Int,
    modifier: Modifier = Modifier,
) {
    val target = (speedKmh / maxKmh).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(130),
        label = "compactSweep",
    )
    val activeColor: Color = when {
        speedKmh >= maxKmh * 0.92f -> Danger
        speedKmh >= warnKmh -> Warn
        else -> Accent
    }
    Canvas(modifier.aspectRatio(1f)) {
        val stroke = size.minDimension * 0.09f
        val radius = (size.minDimension - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(
            color = Color.Black.copy(alpha = 0.45f),
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        if (animated > 0.001f) {
            drawArc(
                color = activeColor,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

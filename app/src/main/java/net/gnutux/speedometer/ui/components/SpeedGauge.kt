package net.gnutux.speedometer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import net.gnutux.speedometer.core.alert.SpeedScale
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.settings.GaugeStyle
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.TrackDim
import net.gnutux.speedometer.ui.theme.Warn

/**
 * حجم رقم السرعة في التصميم الكلاسيكيّ وحده. مقاسٌ مطلق لا كسرٌ من الضلع، وهكذا
 * كان قبل 0.9.4: القرص يتراوح بين ‎200dp‎ و‎400dp‎ والرقم ‎96sp‎ فيهما جميعًا. أمّا
 * التصاميم الخمسة الجديدة فتشتقّ رقمها من ضلع الوجه، والعلّة عند
 * [numberFractionOfSide].
 */
private const val BASE_NUMBER_SP = 96f

/**
 * قرص العداد. القوس يتلوّن بحسب المنطقة: عادي، فوق عتبة التحذير، ثم الحمراء.
 * التلوّن هنا مقصود لقارئ عجلان: راكب الدراجة يلمح اللون قبل أن يقرأ الرقم.
 *
 * الهندسة كلّها في [drawGaugeFace] لا هنا: ثلاثة مواضع تعرض قرصًا (هذه الشاشة،
 * وطبقة الكاميرا، ونافذة «صورة في صورة»)، ولو رسم كلٌّ منها لنفسه لتباعدت الأوجه
 * بعد أوّل تعديل. وما بقي في هذا المركّب هو ما لا يقدر عليه القماش: قراءة ألوان
 * السمة، والحركة، والنصّ الذي يجب أن يتبع مقياس خطّ النظام وقارئَ الشاشة.
 *
 * @param limitKmh حدّ السائق، وصفرٌ يعني بلا حدّ. حين يُضبط تُرسم عنده علامةٌ حمراء
 *   عريضة على مسار القوس، ويصير الحكم على اللون للحدّ لا لنسبةٍ من المدى. والمدى
 *   نفسه ([maxKmh]) يأتي مضبوطًا على الحدّ من
 *   [net.gnutux.speedometer.core.alert.SpeedScale.of] — لا يُشتقّ هنا.
 * @param style تصميم الوجه من الإعدادات. افتراضُه [GaugeStyle.CLASSIC] وهو قرص ما
 *   قبل 0.9.4 بالبكسل الواحد: من لم يختر شيئًا لا يتبدّل عليه شيء.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun SpeedGauge(
    speedKmh: Float,
    maxKmh: Int,
    warnKmh: Int,
    unitLabel: String,
    modifier: Modifier = Modifier,
    limitKmh: Int = 0,
    style: GaugeStyle = GaugeStyle.CLASSIC,
) {
    val target = (speedKmh / maxKmh).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 140),
        label = "gaugeSweep",
    )
    val warnFraction = (warnKmh.toFloat() / maxKmh).coerceIn(0f, 1f)
    // الحكم من العقد المشترك لا من شرطٍ محلّيّ: هو نفسه الذي يلوّن به الراسمُ
    // المحروق قوسَه، فلا يختلف اللون بين شاشةٍ وملفّ في المشهد الواحد. وهو كذلك
    // اللون الوحيد الذي تعرفه التصاميم الستّة، فلا تختلف تصميمتان في الحكم
    val activeColor = when (SpeedScale.zoneOf(speedKmh, maxKmh, warnKmh, limitKmh)) {
        SpeedZone.DANGER -> Danger
        SpeedZone.WARN -> Warn
        SpeedZone.NORMAL -> Accent
    }

    // صفرٌ سالب يعني «لا علامة». يُرفع هنا لا داخل `DrawScope`: الألوان تُقرأ من
    // التركيب (قاعدة المشروع)، وحساب النسبة يجاور لونها
    val limitFraction = if (limitKmh in 1..maxKmh) limitKmh.toFloat() / maxKmh else -1f

    val measurer = rememberTextMeasurer()
    // ألوان اللوحة صارت تُقرأ من التركيب، و`DrawScope` ليس تركيبًا: تُرفع هنا مرّة
    // ثمّ تُمرَّر إلى الرسم، فيعمل الوضعان الفاتح والداكن بلا شرطٍ في حلقة الرسم.
    val tickColor = TextSecondary
    val tickLineColor = TextPrimary.copy(alpha = 0.55f)
    val trackColor = TrackDim
    val redZoneColor = Danger.copy(alpha = 0.30f)
    val limitColor = Danger
    val palette = GaugePalette(
        active = activeColor,
        track = trackColor,
        redZone = redZoneColor,
        tick = tickColor,
        tickLine = tickLineColor,
        limit = limitColor,
    )
    // النمط للطباعة وحدها بلا لون: اللون من اللوحة، والمفتاح هنا هو المدى لأنّه ما
    // يتبدّل به نصُّ آخر تدريجة
    val tickStyle = remember { TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    val ticks = remember(measurer, tickStyle, maxKmh) {
        GaugeTicks(measurer = measurer, style = tickStyle, maxKmh = maxKmh)
    }

    // القياس من العرض وحده لا من الارتفاع: هذا المركّب يقع داخل عمودٍ متمرِّر،
    // والمتمرِّر يقيس أبناءه بارتفاعٍ لا نهائيّ — فـ `maxHeight` هناك لا معنى له.
    // والوجه يشتقّ ارتفاعه من نسبته على كلّ حال.
    BoxWithConstraints(modifier = modifier) {
        val faceSide = maxWidth / style.aspect
        val density = LocalDensity.current

        // `Dp.toSp()` لا `.value.sp` (كما في نافذة «صورة في صورة»): مقياس الخطّ في
        // إعدادات النظام يكبّر sp، ولو مرّرنا الـ dp خامًا لفاض رقمُ من يضبط جهازه
        // على ‎1.3×‎ خارج قرصه.
        //
        // والكلاسيكيّ وحده مستثنًى بمقاسٍ مطلق: كان ‎96sp‎ في كلّ المقاسات قبل 0.9.4،
        // واشتقاقُه اليوم — وإن كان أصحّ — يبدّل قرصًا لم يطلب أحدٌ تبديله. والسبب
        // مبسوطٌ عند [numberFractionOfSide].
        val numberSize = if (style == GaugeStyle.CLASSIC) {
            BASE_NUMBER_SP.sp
        } else {
            with(density) { (faceSide * style.numberFractionOfSide).toSp() }
        }
        val numberStyle = MaterialTheme.typography.displayLarge.copy(
            fontSize = numberSize,
            fontWeight = FontWeight.Bold,
            color = activeColor,
        )
        val unitStyle = MaterialTheme.typography.titleMedium.copy(color = TextSecondary)

        if (style.numberInsideFace) {
            // النسبة من التصميم لا مثبَّتةً على ‎1‎، وإن كانت اليوم ‎1‎ في كلّ ما يدخل
            // هذا الفرع: مصدر النسبة واحدٌ فلا يُنسى موضعٌ عند إضافة تصميم
            Box(Modifier.aspectRatio(style.aspect), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawGaugeFace(
                        style = style,
                        fraction = animated,
                        warnFraction = warnFraction,
                        limitFraction = limitFraction,
                        palette = palette,
                        ticks = ticks,
                    )
                }
                Column(
                    modifier = Modifier.offset(y = faceSide * style.numberBiasOfSide),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = Fmt.speed(speedKmh), style = numberStyle)
                    Text(text = unitLabel, style = unitStyle)
                }
            }
        } else {
            // الشريط: لا وسط له يسع رقمًا، فالرقم فوقه في عمودٍ واحد. والوجه يأخذ
            // عرض العمود كلَّه وارتفاعُه من نسبته، فلا يتمدّد في الارتفاع الفائض
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = Fmt.speed(speedKmh), style = numberStyle)
                Text(text = unitLabel, style = unitStyle)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(style.aspect)
                ) {
                    drawGaugeFace(
                        style = style,
                        fraction = animated,
                        warnFraction = warnFraction,
                        limitFraction = limitFraction,
                        palette = palette,
                        ticks = ticks,
                    )
                }
            }
        }
    }
}

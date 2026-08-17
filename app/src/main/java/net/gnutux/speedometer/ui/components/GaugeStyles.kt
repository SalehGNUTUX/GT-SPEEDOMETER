package net.gnutux.speedometer.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.settings.GaugeStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * هندسة أوجه العدّاد الستّة — **موضعٌ واحد** يرسم منه كلُّ من يعرض قرصًا.
 *
 * ثلاثة مستهلكين لا يشبه أحدهم الآخر: القرص الكبير في `SpeedometerScreen` (ألوانه
 * من السمة الحيّة، وله تدريجٌ رقميّ)، وحلقةُ طبقة الكاميرا (ألوانٌ بيضاء/سوداء
 * محروقة لا تتبع السمة، وسببُ ذلك موثَّقٌ عندها)، ونافذةُ «صورة في صورة» (ضئيلة
 * بلا أرقام). ولو نُسخت الهندسة عندهم ثلاث مرّات لتباعدت النسخ بعد أوّل تعديل،
 * فصار «المؤشّر» على الشاشة غيرَه في الملفّ المسجَّل — وهو ما وقع من قبلُ في القوس
 * الكلاسيكيّ حتّى وُحّد.
 *
 * ## العقد
 * - **الزاوية**: كلّ الأوجه المستديرة تبدأ من [HudMetrics.ARC_START] وتمسح
 *   [HudMetrics.ARC_SWEEP]، فمعنى «النسبة ← الزاوية» واحدٌ في التصاميم كلّها. ومن
 *   بدّل هذين الثابتين بدّل التصاميم الستّة معًا، وهذا مقصود.
 * - **اللون**: لون المنطقة يأتي جاهزًا في [GaugePalette.active] بعد أن يقرّره
 *   [net.gnutux.speedometer.core.alert.SpeedScale.zoneOf] عند المستهلك. لا تصميم
 *   يحكم على السرعة بنفسه، وإلّا قال وجهٌ «تجاوزتَ» وقال آخرُ «لم تتجاوز» للسرعة
 *   نفسها — وهو أسوأ ما يقع في أداةٍ تُقرأ بلمحة.
 * - **الألوان تُرفع من التركيب**: `DrawScope` ليس تركيبًا فلا يقرأ سمة، فتصل
 *   الألوان كلّها في [GaugePalette]. لا لون مكتوبًا هنا بالأرقام.
 * - **علامة الحدّ لا تسقط من أيّ تصميم**: هي معلومة سلامة لا زينة، فحتّى الوجه
 *   الأقلّ زخرفًا ([GaugeStyle.MINIMAL]) يرسمها.
 *
 * ## مزالق `DrawScope` التي تراعيها الحسابات هنا
 * `drawArc` يأخذ `topLeft` و`size` لا نصفَ قطر، ويمسح باتّجاه عقارب الساعة من
 * الساعة الثالثة (‎0°‎ = يمين المركز، والزاوية الموجبة تنزل لأنّ المحور الرأسيّ
 * إلى أسفل). و`Stroke(cap = Round)` يمدّ القوس بنصف سماكته عند طرفيه، فلا يجوز
 * حساب نصف القطر من الضلع مباشرةً بل من `(الضلع − السماكة) / 2`.
 */

// ===== الألوان والتدريج =====

/**
 * ألوان الوجه، مرفوعةً من التركيب.
 *
 * @param active لون القيمة الحيّة، وهو **لون المنطقة** كما قرّره
 *   [net.gnutux.speedometer.core.alert.SpeedScale.zoneOf] عند المستهلك: فيروزيّ أو
 *   برتقاليّ أو أحمر. لا يُشتقّ هنا.
 * @param track لون المسار الخامل تحت القيمة.
 * @param redZone لون منطقة ما فوق عتبة التحذير على المسار — باهتٌ عادةً كي لا
 *   يزاحم القيمة الحيّة. **ومن أراد إلغاءها** (كطبقة الكاميرا، إذ لا يرسمها
 *   الراسم المحروق) فليمرّر [Color.Transparent]: الرسم يتخطّاها بلا وسيطٍ ثانٍ.
 * @param tick لون أرقام التدريج. هو المصدر الوحيد للونها، فنمط [GaugeTicks.style]
 *   للطباعة وحدها — ولو حمل الطرفان لونًا لاختلفا.
 * @param tickLine لون خطوط التدريج والشرات الصغيرة.
 * @param limit لون علامة حدّ السائق.
 */
data class GaugePalette(
    val active: Color,
    val track: Color,
    val redZone: Color,
    val tick: Color,
    val tickLine: Color,
    val limit: Color,
)

/**
 * التدريج الرقميّ على الوجه.
 *
 * تمريرُ `null` يعني «بلا أرقام»، وهو حال النسخ المصغّرة: رقمٌ بحجم ‎13sp‎ على حلقةٍ
 * قطرها ‎124dp‎ في زاوية الشاشة لا يُقرأ، وإنّما يُوسّخ. والنسخ التي لا تدرّج تكسب
 * فوق ذلك حيّزًا: [drawGaugeFace] لا يقتطع «مطرحَ الأرقام» من نصف القطر إلّا حين
 * تكون هذه القيمة غير معدومة، فتملأ الحلقةُ صندوقَها كما ينصّ عقد
 * [HudMetrics.RING_DIAMETER] (الضلع هو القطر الخارجيّ).
 *
 * صنفٌ لا `data class`: [TextMeasurer] لا يعرّف تساويًا ذا معنًى، فمقارنةٌ مولَّدة
 * عليه توهم باستقرارٍ لا وجود له.
 *
 * @param measurer قائس النصّ من `rememberTextMeasurer()` عند المستهلك.
 * @param style نمط الطباعة (الحجم والوزن) بلا لون — اللون من [GaugePalette.tick].
 * @param maxKmh أقصى المدى، وهو ما يُكتب عند آخر تدريجة.
 * @param count عدد الفواصل لا عدد العلامات: العلامات `count + 1` لأنّ الطرفين
 *   يُكتبان معًا (‎0‎ و[maxKmh]).
 */
class GaugeTicks(
    val measurer: TextMeasurer,
    val style: TextStyle,
    val maxKmh: Int,
    val count: Int = DEFAULT_COUNT,
) {
    companion object {
        /** ثمانية فواصل = تسع علامات، وهو تدريج ما قبل 0.9.4 بحذافيره */
        const val DEFAULT_COUNT = 8
    }
}

// ===== واصفات التصميم التي يحتاجها المخطِّط =====

/**
 * نسبة عرض **وجه** القرص إلى ارتفاعه — لا نسبة صندوق المركّب كلّه.
 *
 * الفرق يهمّ [GaugeStyle.BAR] وحده: رقمه خارج الوجه (انظر [numberInsideFace])،
 * فصندوقه = كتلة الرقم + الوجه، والوجه وحده هو الذي نسبته ‎2.6‎. ولو خلطنا
 * الاثنين لخرج الشريط أعرض من صندوقه أو خرج الرقم من أسفله.
 */
val GaugeStyle.aspect: Float
    get() = if (this == GaugeStyle.BAR) 2.6f else 1f

/** هل يقع رقم السرعة داخل الوجه؟ لا يقع إلّا خارجه في الشريط: ليس للشريط وسطٌ يسع رقمًا */
val GaugeStyle.numberInsideFace: Boolean
    get() = this != GaugeStyle.BAR

/**
 * حجم رقم السرعة كسرًا من **الضلع الأقصر للوجه**: قطرِ الحلقة في الأوجه المستديرة،
 * وارتفاعِ الوجه في الشريط.
 *
 * ## استثناء الكلاسيكيّ
 * [GaugeStyle.CLASSIC] له هنا قيمةٌ يستعملها من يبني مقاسه من الصندوق (المصغّرات)،
 * **ولا يستعملها القرص الكبير**: هناك يبقى الرقم ‎96sp‎ مقاسًا مطلقًا كما كان قبل
 * 0.9.4 — أي ‎%48‎ من قرصٍ ‎200dp‎ و‎%24‎ من قرصٍ ‎400dp‎. وهو اشتقاقٌ سيّئ بالمقاييس
 * كلّها (الرقم يفيض عن الحلقة في أصغر المقاسات)، لكنّ إصلاحه اليوم يبدّل ما رآه
 * كلُّ مستعملٍ منذ 0.7.0 وهو لم يطلب تبديلًا. فبقي على سوئه، وجاءت التصاميم
 * الخمسة الجديدة مشتقّةً كما ينبغي: لا عهد لأحدٍ بها فلا شيء تكسره.
 *
 * والقيم معايَرةٌ على ثلاث خانات (‎199‎): هي أعرض ما يُعرض، فما وسعها وسع ما دونها.
 * وحسابها من عرض المحرف في Roboto (نحو ‎0.556‎ من حجمه): ثلاث خانات عند ‎0.36‎ من
 * الضلع تشغل ‎%60‎ من عرض الصندوق، فيبقى للحلقة متّسع.
 */
val GaugeStyle.numberFractionOfSide: Float
    get() = when (this) {
        GaugeStyle.CLASSIC -> 0.26f
        // البطولة للمؤشّر: الرقم شاهدٌ تحت المحور لا صاحبُ الوجه
        GaugeStyle.NEEDLE -> 0.17f
        // ليس في الوجه غيرُ حلقةٍ رفيعة، فالرقم هو التصميم
        GaugeStyle.MINIMAL -> 0.36f
        GaugeStyle.SEGMENTS -> 0.25f
        // أضيقُ من المقطَّع: حلقة التقدّم أقربُ إلى المركز فالمتّسع الداخليّ أقلّ
        GaugeStyle.DUAL_RING -> 0.21f
        // من ارتفاع الوجه لا من عرضه، والرقم فوقه لا فيه فلا يزاحمه شيء
        GaugeStyle.BAR -> 0.62f
    }

/**
 * إزاحة كتلة الرقم عن مركز الوجه إلى أسفل، كسرًا من الضلع الأقصر.
 *
 * لا يحتاجها إلّا [GaugeStyle.NEEDLE]: مركزُ الميناء مشغولٌ بالصرّة والمؤشّر، ورقمٌ
 * موسَّطٌ عليه يختفي تحت المؤشّر كلّما مرّ به — أي عند كلّ سرعة. وموضعُه في ثلث
 * الوجه الأسفل ليس حيلةً بل عُرفُ العدّادات: هناك يضع صانعُ السيّارة عدّادَ
 * المسافة، وهناك بالضبط فتحةُ القوس (المسح ‎240°‎ يترك ‎120°‎ سفليّةً فارغة) فلا
 * يزاحم الرقمُ تدريجًا ولا قوسًا.
 */
val GaugeStyle.numberBiasOfSide: Float
    get() = if (this == GaugeStyle.NEEDLE) 0.22f else 0f

/**
 * معامل سماكة المسار في هذا التصميم، مضروبًا في `strokeOfSide` الذي يمرّره
 * المستهلك إلى [drawGaugeFace].
 *
 * الضربُ داخل الرسم لا عند المستهلك: المستهلك يعرف سماكةً مرجعيّةً واحدة تليق
 * بصندوقه (‎0.075‎ للقرص الكبير، [HudMetrics.RING_STROKE_OF_DIAMETER] للطبقة)، ولا
 * يعرف أنّ «الرفيع» أرفعُ من «المقطَّع» بنسبة كذا. ولولا ذلك لوجب على كلّ مستهلٍّ
 * أن ينسخ جدولًا كهذا — أي ثلاث نسخ تتباعد.
 */
val GaugeStyle.strokeScale: Float
    get() = when (this) {
        GaugeStyle.CLASSIC -> 1f
        GaugeStyle.NEEDLE -> 1f
        // «رفيعة» هي التصميم كلّه: ما دونها يختفي وما فوقها يصير كلاسيكيًّا بلا أرقام
        GaugeStyle.MINIMAL -> 0.42f
        // الشرات تُقرأ كتلًا لا خطوطًا، فتحتمل سماكةً أزيد
        GaugeStyle.SEGMENTS -> 1.15f
        GaugeStyle.DUAL_RING -> 1f
        GaugeStyle.BAR -> 1f
    }

/** سماكة المسار المرجعيّة في القرص الكبير: ‎%7.5‎ من ضلعه، وهي قيمة ما قبل 0.9.4 */
const val GAUGE_STROKE_OF_SIDE = 0.075f

// ===== ثوابت داخليّة للتصاميم =====

/** عدد شرات [GaugeStyle.SEGMENTS]. ثمانٍ وعشرون تقسم المسح ‎240°‎ إلى ‎8.57°‎ للشرة */
private const val SEGMENT_COUNT = 28

/** نصيب الفجوة من خطوة الشرة. دونه تلتحم الشرات قوسًا، وفوقه تتناثر */
private const val SEGMENT_GAP_RATIO = 0.30f

/** أدنى ما يُعتدّ به من نسبةٍ حيّة: دونه لا يُرسم شيء فلا تبقى نقطةٌ عند الصفر */
private const val EPSILON = 0.001f

// ===== المدخل الواحد =====

/**
 * يرسم وجه العدّاد بتصميمه المختار داخل حدود [DrawScope] الحاليّة.
 *
 * الرقم المركزيّ **ليس** من عمل هذه الدالّة: يرسمه المستهلك بـ `Text` من Compose لا
 * على القماش، كي يتبع مقياسَ خطّ النظام وقارئَ الشاشة. وهذه الدالّة ترسم ما لا
 * يقدر عليه المركّب: القوس والتدريج والمؤشّر والعلامة.
 *
 * @param style التصميم المختار من الإعدادات.
 * @param fraction السرعة على المدى ‎[0..1]‎ بعد تنعيمها بالحركة عند المستهلك
 *   (`animateFloatAsState`)، لا القيمة الخام: التنعيم شأن المركّب لا القماش.
 * @param warnFraction عتبة التحذير على المدى نفسه ‎[0..1]‎.
 * @param limitFraction موضع حدّ السائق، و**السالب يعني «لا حدّ»** — وهو الاصطلاح
 *   نفسه في [net.gnutux.speedometer.core.alert.SpeedScale.limitFraction].
 * @param palette ألوان الوجه، مرفوعةً من التركيب.
 * @param strokeOfSide السماكة المرجعيّة نسبةً إلى الضلع الأقصر؛ يضربها كلُّ تصميمٍ
 *   في [GaugeStyle.strokeScale] الخاصّ به.
 * @param ticks التدريج الرقميّ، و`null` يعني «بلا أرقام» فيتّسع الوجه لصندوقه.
 */
@OptIn(ExperimentalTextApi::class)
fun DrawScope.drawGaugeFace(
    style: GaugeStyle,
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    strokeOfSide: Float = GAUGE_STROKE_OF_SIDE,
    ticks: GaugeTicks? = null,
) {
    val f = fraction.coerceIn(0f, 1f)
    val warn = warnFraction.coerceIn(0f, 1f)
    val stroke = size.minDimension * strokeOfSide * style.strokeScale
    when (style) {
        GaugeStyle.CLASSIC -> drawClassicFace(f, warn, limitFraction, palette, stroke, ticks)
        GaugeStyle.NEEDLE -> drawNeedleFace(f, warn, limitFraction, palette, stroke, ticks)
        GaugeStyle.MINIMAL -> drawMinimalFace(f, limitFraction, palette, stroke)
        GaugeStyle.SEGMENTS -> drawSegmentsFace(f, warn, limitFraction, palette, stroke, ticks)
        GaugeStyle.DUAL_RING -> drawDualRingFace(f, warn, limitFraction, palette, stroke, ticks)
        GaugeStyle.BAR -> drawBarFace(f, warn, limitFraction, palette, ticks)
    }
}

// ===== أدواتٌ مشتركة =====

/** زاوية نسبةٍ على المسح المتّفق عليه، بدرجات `drawArc` */
private fun angleOf(fraction: Float): Float =
    HudMetrics.ARC_START + HudMetrics.ARC_SWEEP * fraction

/** جيبا زاويةِ نسبةٍ معًا: تُطلبان دائمًا معًا، وحسابهما مرّتين إغراءٌ بخطأ مطابقة */
private fun unitVector(fraction: Float): Offset {
    val rad = Math.toRadians(angleOf(fraction).toDouble())
    return Offset(cos(rad).toFloat(), sin(rad).toFloat())
}

/** قوسٌ بنصف قطرٍ لا بـ `topLeft`/`size`: تحويلٌ يتكرّر في كلّ تصميم فلا يُكتب ستّ مرّات */
private fun DrawScope.arc(
    color: Color,
    center: Offset,
    radius: Float,
    fromFraction: Float,
    toFraction: Float,
    width: Float,
    cap: StrokeCap,
) {
    if (color.alpha <= 0f || toFraction <= fromFraction) return
    drawArc(
        color = color,
        startAngle = angleOf(fromFraction),
        sweepAngle = HudMetrics.ARC_SWEEP * (toFraction - fromFraction),
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = width, cap = cap),
    )
}

/** خطٌّ شعاعيّ عند نسبةٍ ما، من نصف قطرٍ إلى آخر */
private fun DrawScope.radialLine(
    color: Color,
    center: Offset,
    fraction: Float,
    innerRadius: Float,
    outerRadius: Float,
    width: Float,
) {
    val v = unitVector(fraction)
    drawLine(
        color = color,
        start = Offset(center.x + v.x * innerRadius, center.y + v.y * innerRadius),
        end = Offset(center.x + v.x * outerRadius, center.y + v.y * outerRadius),
        strokeWidth = width,
    )
}

/**
 * علامة حدّ السائق على قوسٍ سماكته [stroke] ونصف قطره [radius].
 *
 * **خطٌّ لا مجرّد تبدّل لون**: اللون يقول «تجاوزتَ» بعد فوات الأمر، والعلامة تقول
 * «هنا الحدّ» قبله. ونسبتا العرض والطول من [HudMetrics] — وهي النسب التي يرسم بها
 * الراسم المحروق — فتنتأ عن القوس من طرفيه فتُقرأ علامةً لا فجوةً فيه.
 *
 * وتُرسم **بعد** القوس الحيّ دائمًا: تحته كان يغطّيها القوسُ عند التجاوز، أي في
 * اللحظة الوحيدة التي تُقرأ فيها.
 */
private fun DrawScope.limitMark(
    center: Offset,
    radius: Float,
    stroke: Float,
    fraction: Float,
    color: Color,
    lengthOfStroke: Float = HudMetrics.LIMIT_MARK_LENGTH_OF_STROKE,
) {
    val half = stroke * lengthOfStroke / 2f
    radialLine(
        color = color,
        center = center,
        fraction = fraction,
        innerRadius = radius - half,
        outerRadius = radius + half,
        width = stroke * HudMetrics.LIMIT_MARK_WIDTH_OF_STROKE,
    )
}

/**
 * رقم تدريجةٍ موسَّطًا على نقطة.
 *
 * اللون من اللوحة لا من [GaugeTicks.style]: النمط للطباعة وحدها، ولو حمل الطرفان
 * لونًا لاختلف لون الأرقام عن سائر الوجه في السمة الفاتحة.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.tickLabel(ticks: GaugeTicks, style: TextStyle, at: Offset, fraction: Float) {
    val value = (ticks.maxKmh * fraction).toInt()
    val layout = ticks.measurer.measure(String.format(Locale.US, "%d", value), style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
    )
}

// ===== ١) الكلاسيكيّ =====

/**
 * قوسٌ مدرَّج بأرقام — تصميم ما قبل 0.9.4 **بحذافيره**.
 *
 * منقولٌ من `SpeedGauge` كما كان: السماكة ‎%7.5‎ من الضلع، ومطرح الأرقام
 * ‎2.15‎ ضعف السماكة، ونصف القطر `(الضلع − السماكة) / 2 − مطرح الأرقام`، والترتيب
 * مسارٌ فمنطقةٌ حمراء فقيمةٌ حيّة فتدريجٌ فعلامة. من غيّر رقمًا هنا غيّر ما رآه كلُّ
 * مستعملٍ منذ 0.7.0 وهو لم يطلب تغييرًا.
 *
 * والاستثناء الوحيد أنّ مطرح الأرقام يسقط حين لا تدريج ([ticks] معدومة): القرص
 * الكبير يمرّر تدريجًا دائمًا فلا يتبدّل شيء، والمصغّرات تكسب حلقةً تملأ صندوقها
 * كما ينصّ عقد [HudMetrics.RING_DIAMETER].
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawClassicFace(
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    stroke: Float,
    ticks: GaugeTicks?,
) {
    val labelRoom = if (ticks != null) stroke * 2.15f else 0f
    val radius = (size.minDimension - stroke) / 2f - labelRoom
    val center = Offset(size.width / 2f, size.height / 2f)

    arc(palette.track, center, radius, 0f, 1f, stroke, StrokeCap.Round)
    // المنطقة الحمراء: من عتبة التحذير إلى النهاية، باهتة كي لا تزاحم القيمة الحيّة.
    // وطرفاها مقطوعان (`Butt`) كي يبدأ لونها عند العتبة بالضبط لا قبلها بنصف سماكة
    arc(palette.redZone, center, radius, warnFraction, 1f, stroke, StrokeCap.Butt)
    if (fraction > EPSILON) {
        arc(palette.active, center, radius, 0f, fraction, stroke, StrokeCap.Round)
    }

    if (ticks != null) {
        val labelStyle = ticks.style.copy(color = palette.tick)
        // ‎2dp‎ مقاسًا مطلقًا لا نسبةً من الضلع: هكذا كانت قبل 0.9.4، وأيّ اشتقاقٍ
        // «أصحّ» منها يبدّل قرصًا لم يطلب أحدٌ تبديله
        val tickWidth = 2.dp.toPx()
        val outer = radius - stroke / 2f - tickWidth
        val inner = outer - stroke * 0.45f
        val labelRadius = radius + stroke * 0.95f
        for (i in 0..ticks.count) {
            val t = i.toFloat() / ticks.count
            radialLine(palette.tickLine, center, t, inner, outer, tickWidth)
            val v = unitVector(t)
            tickLabel(
                ticks = ticks,
                style = labelStyle,
                at = Offset(center.x + v.x * labelRadius, center.y + v.y * labelRadius),
                fraction = t,
            )
        }
    }

    if (limitFraction >= 0f) {
        limitMark(center, radius, stroke, limitFraction, palette.limit)
    }
}

// ===== ٢) المؤشّر التناظريّ =====

/**
 * وجهٌ تناظريّ بمؤشّرٍ يدور — أقربُ ما يكون إلى عدّاد السيّارة.
 *
 * ليس قوسًا بمؤشّرٍ فوقه، بل ميناءٌ كامل: إطارٌ رفيع، وتدريجٌ كبيرٌ مرقَّم بينه
 * تدريجٌ صغير، ومؤشّرٌ مدبَّب من مسار (`Path`) لا خطًّا بسماكةٍ واحدة، وصُرّةٌ في
 * المركز تُخفي مأخذ المؤشّر.
 *
 * **الدوران**: المؤشّر يُبنى أفقيًّا إلى اليمين (‎0°‎ = الساعة الثالثة) ثمّ يُدار
 * بـ [rotate] حول المركز بالزاوية نفسها التي يمسح بها `drawArc`. فالاصطلاحان واحد
 * — الزاوية الموجبة تنزل لأنّ المحور الرأسيّ إلى أسفل — ولا يقع الانقلاب ‎180°‎
 * الذي أفلت مرّةً إلى إصدارٍ سابق.
 *
 * والقيمة الحيّة يقولها المؤشّر ويؤكّدها خيطٌ رفيع على الإطار الخارجيّ: المؤشّر
 * وحده يترك السائق يقدّر الزاوية، والخيط يُريه القدر مملوءًا بلمحة.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawNeedleFace(
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    stroke: Float,
    ticks: GaugeTicks?,
) {
    val side = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)

    val rim = stroke * 0.28f              // خيط الإطار الخارجيّ
    // الأرقام **خارج** الإطار لا داخله. جرّبناها داخله كما في مينا السيّارة فوجدنا
    // المؤشّر يمرّ فوقها فيحجبها، وأسوأُ ما فيه أنّه يحجب «‎0‎» في وضع السكون — أي
    // في أكثر الحالات وقوعًا. وخارجَه يقتطع الرقمُ من القطر ولا يحجبه شيء.
    val labelRoom = if (ticks != null) stroke * 1.8f else 0f
    val rimRadius = (side - rim) / 2f - side * 0.01f - labelRoom
    val band = stroke * 0.46f             // شريط التدريج تحت الإطار
    val bandRadius = rimRadius - rim / 2f - band / 2f - side * 0.012f

    // الإطار: مسارٌ كاملٌ خافت، وفوقه خيط القيمة الحيّة
    arc(palette.track, center, rimRadius, 0f, 1f, rim, StrokeCap.Round)
    if (fraction > EPSILON) {
        arc(palette.active, center, rimRadius, 0f, fraction, rim, StrokeCap.Round)
    }

    // شريط التدريج، وعليه الخطّ الأحمر من عتبة التحذير — وهو عرف عدّادات الدوران
    arc(palette.track.copy(alpha = palette.track.alpha * 0.55f), center, bandRadius, 0f, 1f, band, StrokeCap.Butt)
    arc(palette.redZone, center, bandRadius, warnFraction, 1f, band, StrokeCap.Butt)

    // التدريج: كبيرٌ مرقَّم، وبينه أربعة صغار. الصغار هي التي تجعل الميناء ميناءً:
    // بدونها يصير الوجه قرصًا بثماني علامات، وهو ما يشبه الكلاسيكيّ لا العدّاد
    val majors = ticks?.count ?: GaugeTicks.DEFAULT_COUNT
    val minorsPer = 4
    val bandOuter = bandRadius + band / 2f
    val majorInner = bandOuter - band * 1.35f
    val minorInner = bandOuter - band * 0.62f
    for (i in 0..majors * minorsPer) {
        val t = i.toFloat() / (majors * minorsPer)
        val isMajor = i % minorsPer == 0
        radialLine(
            color = palette.tickLine,
            center = center,
            fraction = t,
            innerRadius = if (isMajor) majorInner else minorInner,
            outerRadius = bandOuter,
            width = if (isMajor) side * 0.009f else side * 0.004f,
        )
    }

    if (ticks != null) {
        val labelStyle = ticks.style.copy(color = palette.tick)
        val labelRadius = rimRadius + rim / 2f + labelRoom * 0.55f
        for (i in 0..majors) {
            val t = i.toFloat() / majors
            val v = unitVector(t)
            tickLabel(
                ticks = ticks,
                style = labelStyle,
                at = Offset(center.x + v.x * labelRadius, center.y + v.y * labelRadius),
                fraction = t,
            )
        }
    }

    if (limitFraction >= 0f) {
        limitMark(center, bandRadius, band, limitFraction, palette.limit, lengthOfStroke = 2.2f)
    }

    // المؤشّر: مدبَّبٌ إلى الأمام، ذو ذَنَبٍ قصير خلف المركز يوازن الشكل بصريًّا
    val hubRadius = side * 0.052f
    val halfWidth = side * 0.016f
    val tail = hubRadius * 1.45f
    val length = majorInner - side * 0.01f
    val needle = Path().apply {
        moveTo(center.x + length, center.y)
        lineTo(center.x + hubRadius * 0.35f, center.y - halfWidth)
        lineTo(center.x - tail, center.y - halfWidth * 0.42f)
        lineTo(center.x - tail, center.y + halfWidth * 0.42f)
        lineTo(center.x + hubRadius * 0.35f, center.y + halfWidth)
        close()
    }
    rotate(degrees = angleOf(fraction), pivot = center) {
        drawPath(needle, palette.active)
    }
    drawCircle(palette.active, radius = hubRadius, center = center)
    drawCircle(palette.track, radius = hubRadius * 0.42f, center = center)
}

// ===== ٣) الرفيع =====

/**
 * حلقةٌ رفيعة بلا تدريجٍ ولا منطقةٍ حمراء — الرقم هو التصميم.
 *
 * تُهمَل [GaugeTicks] هنا ولو مُرّرت، وهذا مقصود لا سهو: من اختار «الرفيع» اختار
 * ألّا يرى أرقامًا صغيرة، ولو أضفناها لصار نسخةً باهتة من الكلاسيكيّ.
 *
 * **لكنّ علامة الحدّ تبقى**: هي معلومة سلامة لا زخرف. وطولها هنا من مضاعفٍ خاصّ
 * (‎2.6‎ بدل [HudMetrics.LIMIT_MARK_LENGTH_OF_STROKE]) لأنّ المضاعف الأصليّ محسوبٌ
 * على قوسٍ سميك؛ على حلقةٍ بأربعة أعشار سماكته يخرج شرطةً لا تكاد تُرى.
 */
private fun DrawScope.drawMinimalFace(
    fraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    stroke: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.minDimension - stroke) / 2f - size.minDimension * 0.015f

    arc(palette.track, center, radius, 0f, 1f, stroke, StrokeCap.Round)
    if (fraction > EPSILON) {
        arc(palette.active, center, radius, 0f, fraction, stroke, StrokeCap.Round)
    }
    if (limitFraction >= 0f) {
        limitMark(center, radius, stroke, limitFraction, palette.limit, lengthOfStroke = 2.6f)
    }
}

// ===== ٤) الشرات =====

/**
 * قوسٌ مقطَّع إلى شراتٍ تُضاء تباعًا — يُقرأ كعدّاد دورانٍ ضوئيّ في طائرة.
 *
 * الشرة تُضاء إذا بلغتها السرعة، ولونها حينئذٍ [GaugePalette.active] وحده: أي لون
 * المنطقة الآتي من `SpeedScale`. فالشرات التي فوق العتبة تخرج برتقاليّةً ثمّ حمراء
 * من تلقائها متى بلغتها السرعة، لأنّ بلوغها **هو** تجاوز العتبة. ولم نحمل في
 * اللوحة لونًا ثانيًا للتحذير كي لا تحكم هذه التصميمة على السرعة بنفسها، فتخالف
 * سائر التصاميم في مشهدٍ واحد.
 *
 * أمّا الشرات المطفأة فوق العتبة فتأخذ [GaugePalette.redZone]: هي «المنطقة
 * الحمراء» نفسها التي يرسمها الكلاسيكيّ، مقطَّعةً لا متّصلة.
 *
 * والشرة تُحسب بمركزها لا بطرفها: بالطرف تُضاء شرةٌ لم تبلغها السرعة إلّا بحافّتها،
 * فيُقرأ العدّاد أعلى من حقيقته دائمًا.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawSegmentsFace(
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    stroke: Float,
    ticks: GaugeTicks?,
) {
    val labelRoom = if (ticks != null) stroke * 1.9f else 0f
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.minDimension - stroke) / 2f - labelRoom

    val pitch = 1f / SEGMENT_COUNT
    val gap = pitch * SEGMENT_GAP_RATIO
    for (i in 0 until SEGMENT_COUNT) {
        val from = i * pitch
        val mid = from + pitch / 2f
        val lit = mid <= fraction
        val color = when {
            lit -> palette.active
            mid >= warnFraction -> palette.redZone
            else -> palette.track
        }
        // الطرفان مقطوعان: الفجوة بين شرتين هي التصميم، والطرف المدوَّر يبتلعها
        arc(color, center, radius, from, from + pitch - gap, stroke, StrokeCap.Butt)
    }

    // أرقامٌ بلا خطوط تدريج: الشرات نفسها تدريج، وخطوطٌ فوقها ازدحام
    if (ticks != null) {
        val labelStyle = ticks.style.copy(color = palette.tick)
        val labelRadius = radius + stroke * 0.5f + labelRoom * 0.55f
        for (i in 0..ticks.count) {
            val t = i.toFloat() / ticks.count
            val v = unitVector(t)
            tickLabel(
                ticks = ticks,
                style = labelStyle,
                at = Offset(center.x + v.x * labelRadius, center.y + v.y * labelRadius),
                fraction = t,
            )
        }
    }

    // أطولُ من المضاعف الأصليّ (‎1.9‎ لا ‎1.5‎): الشرة سميكة، والعلامة بلون الخطر
    // فوق شراتٍ صارت حمراءَ عند التجاوز لا تُرى إلّا بما ينتأ منها فوقها وتحتها
    if (limitFraction >= 0f) {
        limitMark(center, radius, stroke, limitFraction, palette.limit, lengthOfStroke = 1.9f)
    }
}

// ===== ٥) الحلقتان =====

/**
 * حلقتان متراكزتان: مسارٌ خارجيّ رفيع يحمل السلّم، وتقدّمٌ داخليّ سميك يحمل القيمة.
 *
 * تقسيمٌ للعمل لا تكرارٌ لحلقة: الخارجيّة **سلّمٌ ساكن** — عليها المنطقة الحمراء
 * وعلامة الحدّ، فتبقى مقروءةً كاملةً مهما بلغت السرعة؛ والداخليّة **قيمةٌ متحرّكة**
 * لا شيء عليها غير التقدّم وشراتٍ صغيرة تُقدّر بها الزاوية. ولذلك لا تغطّي القيمةُ
 * الحدَّ عند تجاوزه، وهي علّة رسم العلامة أخيرًا في التصاميم الأخرى.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawDualRingFace(
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    stroke: Float,
    ticks: GaugeTicks?,
) {
    val side = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)

    val outerStroke = stroke * 0.34f
    val labelRoom = if (ticks != null) stroke * 2.2f else 0f
    val outerRadius = (side - outerStroke) / 2f - labelRoom
    val innerStroke = stroke * 1.05f
    val innerRadius = outerRadius - outerStroke / 2f - innerStroke / 2f - side * 0.035f

    // الخارجيّة: سلّم
    arc(palette.track, center, outerRadius, 0f, 1f, outerStroke, StrokeCap.Round)
    arc(palette.redZone, center, outerRadius, warnFraction, 1f, outerStroke, StrokeCap.Butt)

    // الداخليّة: قيمة
    arc(
        palette.track.copy(alpha = palette.track.alpha * 0.55f),
        center, innerRadius, 0f, 1f, innerStroke, StrokeCap.Round,
    )
    if (fraction > EPSILON) {
        arc(palette.active, center, innerRadius, 0f, fraction, innerStroke, StrokeCap.Round)
    }

    // شراتٌ محزوزة **على** الحلقة الداخليّة لا تحتها، وبعد التقدّم لا قبله.
    // تحتها كانت تقتطع من قرص الرقم في أصغر المقاسات فيمرّ الرقم فوقها، وقبل
    // التقدّم كان التقدّمُ يبتلعها. وحزُّها في النصف الداخليّ من سماكة الحلقة
    // يقسّمها بلا أن تقطعها، فلا تلتبس بتصميم الشرات
    val pips = ticks?.count ?: GaugeTicks.DEFAULT_COUNT
    val pipInner = innerRadius - innerStroke / 2f
    for (i in 0..pips) {
        val t = i.toFloat() / pips
        radialLine(palette.tickLine, center, t, pipInner, pipInner + innerStroke * 0.45f, side * 0.006f)
    }

    if (ticks != null) {
        val labelStyle = ticks.style.copy(color = palette.tick)
        val labelRadius = outerRadius + outerStroke / 2f + labelRoom * 0.55f
        for (i in 0..ticks.count) {
            val t = i.toFloat() / ticks.count
            val v = unitVector(t)
            tickLabel(
                ticks = ticks,
                style = labelStyle,
                at = Offset(center.x + v.x * labelRadius, center.y + v.y * labelRadius),
                fraction = t,
            )
        }
    }

    // على الخارجيّة لا الداخليّة: هناك السلّم، وهناك يبقى الحدّ ظاهرًا فوق كلّ سرعة
    if (limitFraction >= 0f) {
        limitMark(center, outerRadius, outerStroke, limitFraction, palette.limit, lengthOfStroke = 3.4f)
    }
}

// ===== ٦) الشريط =====

/**
 * شريطٌ أفقيّ يمتلئ — أقلّ التصاميم ارتفاعًا وأوضحُها في صندوقٍ عريضٍ قصير.
 *
 * ## اتّجاه الامتلاء
 * **يمتلئ إلى اليمين دائمًا، ولا يتبع اتّجاه التخطيط.** والتطبيق عربيّ، فهذا
 * قرارٌ يحتاج تعليلًا:
 * 1. الأوجه الخمسة الأخرى تمسح باتّجاه عقارب الساعة من [HudMetrics.ARC_START]، أي
 *    أنّ القاع في يسار الوجه والذروة في يمينه. فشريطٌ يمتلئ إلى اليسار كان
 *    سيناقض كلّ ما سواه في التطبيق نفسه، والمستعمل يبدّل التصاميم فيقارن.
 * 2. الوجه نفسه يُرسم في طبقة الكاميرا وفي الملفّ المسجَّل، وليس لهما «اتّجاه
 *    تخطيط» أصلًا: الملفّ يُشاهَد في أيّ مشغّل. فلو تبع الشريط الاتّجاه لاختلف
 *    ما على الشاشة عمّا في الملفّ.
 * 3. الأرقام تحته (‎0‎ … [GaugeTicks.maxKmh]) تُكتب بأرقامٍ لاتينيّة تُقرأ من
 *    اليسار، فالسلّم الصاعد إلى اليمين هو ما يوافقها.
 *
 * ## التخطيط
 * الرقم خارج الوجه ([GaugeStyle.numberInsideFace] معدومة): الوجه هنا كبسولةٌ في
 * أعلاه وسلّمٌ رقميّ تحتها، ولا وسط فيه يسع رقمًا. ويضعه المركّب فوقه.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBarFace(
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    ticks: GaugeTicks?,
) {
    val margin = size.width * 0.02f
    val left = margin
    val right = size.width - margin
    val span = right - left

    // ارتفاع الكبسولة: من ارتفاع الوجه لا من عرضه، وإلّا سمنت في الصناديق العريضة.
    // والحدّ الأعلى بالعرض يمنع كبسولةً أطول من عرضها في صندوقٍ شاذّ النسبة
    val barHeight = min(size.height * 0.38f, size.width * 0.16f)
    val top = size.height * 0.10f
    val bottom = top + barHeight
    val corner = barHeight / 2f

    fun capsule(fromFraction: Float, toFraction: Float, color: Color) {
        if (color.alpha <= 0f || toFraction <= fromFraction) return
        drawRoundRect(
            color = color,
            topLeft = Offset(left + span * fromFraction, top),
            size = Size(span * (toFraction - fromFraction), barHeight),
            cornerRadius = CornerRadius(corner, corner),
        )
    }

    capsule(0f, 1f, palette.track)
    capsule(warnFraction, 1f, palette.redZone)
    if (fraction > EPSILON) capsule(0f, fraction, palette.active)

    // علامة الحدّ: خطٌّ رأسيّ ينتأ عن الكبسولة من طرفيها فيُقرأ علامةً لا فجوة
    if (limitFraction >= 0f) {
        val x = left + span * limitFraction.coerceIn(0f, 1f)
        val over = barHeight * 0.28f
        drawLine(
            color = palette.limit,
            start = Offset(x, top - over),
            end = Offset(x, bottom + over),
            strokeWidth = barHeight * HudMetrics.LIMIT_MARK_WIDTH_OF_STROKE,
        )
    }

    // السلّم تحت الكبسولة. الشرات كلّها تُرسم — خطوطٌ رفيعة لا تتزاحم — أمّا الأرقام
    // فتُسقَط منها ما يتراكب: تسع خانات على شريطٍ عرضه ‎200dp‎ تلتحم سطرًا واحدًا لا
    // يُقرأ منه شيء («‎1001251517200‎»)، وسلّمٌ بأربعة أرقام مقروءة خيرٌ من تسعةٍ
    // ممسوخة. والطرفان لا يسقطان: هما مدى القرص، وبهما يُفهم ما بينهما.
    if (ticks != null) {
        val labelStyle = ticks.style.copy(color = palette.tick)
        val y = bottom + (size.height - bottom) * 0.46f
        val minGap = size.width * 0.012f

        fun layoutAt(i: Int): Pair<Float, TextLayoutResult> {
            val t = i.toFloat() / ticks.count
            val value = (ticks.maxKmh * t).toInt()
            val layout = ticks.measurer.measure(String.format(Locale.US, "%d", value), labelStyle)
            val half = layout.size.width / 2f
            // الطرفان محبوسان داخل الوجه: بلا الحبس يخرج نصف «‎0‎» ونصف الأقصى عنه
            return (left + span * t).coerceIn(left + half, right - half) to layout
        }

        fun put(center: Float, layout: TextLayoutResult) {
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    center - layout.size.width / 2f,
                    y - layout.size.height / 2f,
                ),
            )
        }

        // حيّز الرقم الأخير يُحجز قبل الجولة، فلا يُرسم قبله ما يزاحمه ثمّ يُرسم فوقه
        val (lastCenter, lastLayout) = layoutAt(ticks.count)
        val lastLeft = lastCenter - lastLayout.size.width / 2f
        val (firstCenter, firstLayout) = layoutAt(0)
        put(firstCenter, firstLayout)
        var occupiedTo = firstCenter + firstLayout.size.width / 2f
        for (i in 1 until ticks.count) {
            val (cx, layout) = layoutAt(i)
            val half = layout.size.width / 2f
            if (cx - half >= occupiedTo + minGap && cx + half <= lastLeft - minGap) {
                put(cx, layout)
                occupiedTo = cx + half
            }
        }
        put(lastCenter, lastLayout)

        for (i in 0..ticks.count) {
            val x = left + span * (i.toFloat() / ticks.count)
            // شرةٌ قصيرة تربط الرقم بموضعه على الكبسولة
            drawLine(
                color = palette.tickLine,
                start = Offset(x, bottom + barHeight * 0.10f),
                end = Offset(x, bottom + barHeight * 0.34f),
                strokeWidth = size.width * 0.003f,
            )
        }
    }
}

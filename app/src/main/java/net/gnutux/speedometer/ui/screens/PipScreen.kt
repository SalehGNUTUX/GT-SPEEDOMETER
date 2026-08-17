package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.settings.GaugeStyle
import net.gnutux.speedometer.core.settings.PipSize
import net.gnutux.speedometer.core.settings.PipStyle
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GaugePalette
import net.gnutux.speedometer.ui.components.aspect
import net.gnutux.speedometer.ui.components.drawGaugeFace
import net.gnutux.speedometer.ui.components.numberBiasOfSide
import net.gnutux.speedometer.ui.components.numberFractionOfSide
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.TrackDim
import net.gnutux.speedometer.ui.theme.Warn

/**
 * نسبة نافذة «صورة في صورة»، مبسوطةً عددين لأنّ `PictureInPictureParams` يطلب
 * [android.util.Rational] لا كسرًا عشريًّا. مصدرها هنا لا في `MainActivity` كي تبقى
 * النسبة التي يطلبها النظام والنسبة التي يفترضها التخطيط شيئًا واحدًا.
 *
 * ولماذا 3:2؟ لأنّ محتوى النافذة رقمٌ من ثلاث خانات وسطرُ وحدةٍ تحته: شكلٌ عريض
 * قصير. المربّع يهدر الارتفاع فيصغر الرقم بلا فائدة، والعريض جدًّا (16:9) يخنق
 * الارتفاع فيصير الارتفاعُ — لا العرضُ — هو ما يحدّ حجم الخانة. و3:2 يقارب صندوق
 * «199» مع سطر الوحدة، فيخرج الرقم أكبر ما يمكن في المساحتين معًا.
 */
const val PIP_ASPECT_W = 3
const val PIP_ASPECT_H = 2

private const val PIP_ASPECT = PIP_ASPECT_W.toFloat() / PIP_ASPECT_H

/**
 * نصيب سطر الوحدة من حجم الرقم.
 *
 * كان ثابتًا من ضلع النافذة (‎0.10‎) وصار نسبةً من الرقم، والقيمة مختارةٌ كي **لا
 * يتبدّل شيء** عند الافتراضيّ: ‎0.10 ÷ 0.38‎ هي بعينها نسبة الوحدة إلى رقم
 * [PipSize.MEDIUM]، فمن لم يبدّل حجمًا يرى ما كان يراه بالبكسل الواحد. والاشتقاق من
 * الرقم لا من الضلع لازمٌ للأشكال الجديدة: رقمٌ محبوسٌ داخل حلقةٍ صغيرة مع وحدةٍ
 * ثابتة الحجم يخرج أحدهما عن الحلقة.
 */
private const val UNIT_OF_NUMBER = 0.10f / 0.38f

private const val DOT_FRACTION = 0.08f
private const val DOT_MARGIN_FRACTION = 0.05f

/**
 * عرض المحرف الرقميّ نسبةً إلى حجم الخطّ في Roboto، وأقصى عدد خاناتٍ يُعرض.
 *
 * ‎199‎ ثلاث خانات، و[Fmt.speed] لا يطبع كسورًا؛ فما وسع ثلاثًا وسع ما دونها. وهما
 * معًا يحوّلان «كم يتّسع الصندوق عرضًا؟» إلى حدٍّ أعلى لحجم الخطّ، وهو الحدّ الذي
 * لولاه لخرج الرقم الكبير من نافذةٍ لا تتّسع له بدل أن يصغر.
 */
private const val DIGIT_ADVANCE = 0.556f
private const val MAX_DIGITS = 3

/** نصيب المحتوى من الضلع، وما بقي هامشٌ يمنع ملامسة الرقم لحافّة النافذة */
private const val CONTENT_OF_BOX = 0.96f

/**
 * سماكة المسار المرجعيّة في المصغّرات: أغلظ من القرص الكبير (‎0.075‎) ومن حلقة
 * الكاميرا (‎0.09‎).
 *
 * لأنّ الوجه هنا قد يهبط إلى ‎90dp‎ قطرًا في نافذةٍ بعرض إصبعين، وقوسٌ سماكته ‎%7.5‎
 * منه يخرج خيطًا بـ ‎7dp‎ يُقرأ خدشًا لا مؤشّرًا. و‎0.13‎ تعطي عند ذلك القطر ‎12dp‎،
 * وهو أدنى ما يُلمح بطرف العين وسيّارةٌ تسير.
 */
private const val PIP_STROKE_OF_SIDE = 0.13f

/** نصيب الوجه المستدير من الضلع الأقصر للنافذة؛ وما بقي هامشٌ يمنع القصّ */
private const val FACE_OF_BOX = 0.96f

/** نصيب وجه الشريط من عرض النافذة */
private const val BAR_WIDTH_OF_BOX = 0.92f

/**
 * أكبر مربّعٍ تسعه كتلةُ الرقم داخل وجهٍ مستدير، كسرًا من قطره.
 *
 * الفراغ داخل الحلقة نصف قطره `0.5 − السماكة×1.5 ≈ 0.37` من القطر، والمربّع
 * المرسوم فيه ضلعه `r√2 ≈ 0.52`. وهو حدٌّ لا تقدير: كتلةٌ أعرض منه تلامس القوس عند
 * أركانها الأربعة — وهو ما يقع عند [PipSize.LARGE] لولا هذا الحدّ.
 */
private const val ROUND_INNER_OF_SIDE = 0.52f

/**
 * حيّز كتلة الرقم في وجه المؤشّر، كسرًا من قطره.
 *
 * ليس المربّع المحاط: كتلة المؤشّر تُزاح إلى أسفل بـ [GaugeStyle.numberBiasOfSide]،
 * وتحت المركز لا قوسَ أصلًا — المسح ‎240°‎ يترك ‎120°‎ سفليّةً مفتوحة. فالحدّ هناك
 * هو الصرّة من فوق وحافّة الصندوق من تحت، و‎0.33‎ ما يسعه الطرفان معًا.
 */
private const val NEEDLE_INNER_OF_SIDE = 0.33f

/**
 * نصيب المرسوم فعلًا من ارتفاع صندوق وجه [GaugeStyle.BAR].
 *
 * الكبسولة تشغل من ‎%10‎ إلى ‎%48‎ من ارتفاع الصندوق، وما تحتها محجوزٌ للسلّم
 * الرقميّ. والمصغّرات تمرّر `ticks = null` فلا سلّم، لكنّ هذا الوجه — بخلاف الأوجه
 * المستديرة التي تُسقط مطرح أرقامها حينئذٍ — لا يسترجع ذلك النصف. فيبقى تحت
 * الشريط فراغٌ يعادل نصف الصندوق، وهو في نافذةٍ ارتفاعها ‎147dp‎ خسارةٌ لا تُحتمل:
 * يصغر الشريط والرقم معًا كي يتّسع حيّزٌ لا يُرسم فيه شيء.
 *
 * فالحلّ قصُّ الصندوق على القدر المستعمل. و‎0.60‎ لا ‎0.48‎ لأنّ علامة الحدّ تنتأ عن
 * الكبسولة بـ ‎%28‎ من سماكتها، فتبلغ ‎%59‎ من الارتفاع؛ والقصّ عند ‎0.48‎ كان
 * سيقطع طرفها السفليّ — أي يمحو نصف معلومة السلامة الوحيدة في هذا الوجه.
 */
internal const val BAR_FACE_USED_OF_HEIGHT = 0.60f

/**
 * محتوى نافذة «صورة في صورة»: رقم السرعة، ووحدته، ووجهٌ اختياريّ حوله، ونقطةٌ
 * حمراء إن كان التسجيل جاريًا. لا تبويبات ولا بلاطات ولا معاينة كاميرا.
 *
 * سببُ التقشّف أنّ النافذة بعرض إصبعين وتُقرأ بطرف العين: كلّ عنصرٍ إضافيّ يقتطع من
 * حجم الخانة، والخانة هي كلّ ما يريده من غادر التطبيق وهو يقيس.
 *
 * ## الأشكال الأربعة (0.9.4)
 * [PipStyle.NUMBER] هو ما كان قبل هذا الإصدار **بالبكسل الواحد**، وهو الافتراضيّ:
 * من لم يطلب تبديلًا لا يرى تبديلًا. والثلاثة الأخرى تستدعي [drawGaugeFace] نفسه
 * الذي يرسم القرص الكبير وطبقة الكاميرا — لا هندسةً رابعة — بلا تدريجٍ رقميّ
 * (`ticks = null`: رقمٌ بـ ‎8sp‎ في نافذةٍ كهذه يُوسّخ ولا يُقرأ) وبسماكةٍ مرجعيّةٍ
 * أغلظ ([PIP_STROKE_OF_SIDE]). والرقم يبقى العنصر الأوّل في الأربعة: الوجه يقول
 * «كم من المدى» بلمحة، والرقم يقول «كم» حين يُنظر.
 *
 * ## حجم الرقم
 * أساسُه من [PipSize] كما اختاره المستعمل، ثمّ يُقصّ بما يسعه الصندوق فعلًا
 * ([fitNumber]): بلا القصّ يفيض [PipSize.LARGE] عن نافذةٍ صغيرة — الخانة الثالثة
 * تدخل تحت النقطة الحمراء، وأعلى الرقم وأسفله يُقصّان. والقصّ لا يعمل عند
 * الافتراضيّ فلا يبدّل شيئًا: ‎%38‎ من ‎220dp‎ تبقى دون كلّ حدوده.
 *
 * ## الشفافيّة
 * راجع [net.gnutux.speedometer.MainActivity] لما يجري على مستوى النافذة، فهو الشطر
 * الذي لا تقدر عليه شجرةُ التركيب: خلفيّةٌ شفّافة في Compose فوق نافذةٍ صمّاء تمزج
 * اللون بأسودها لا بما وراءها.
 *
 * @param modifier يُمرَّر من الجذر ملءَ المساحة؛ ولا حشو نظامٍ هنا لأنّ النافذة
 *   المصغَّرة بلا أشرطة نظام.
 */
@Composable
fun PipScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val liveSpeedMps by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val scale by vm.speedScale.collectAsStateWithLifecycle()
    val isRecording by vm.isRecordingSession.collectAsStateWithLifecycle()
    val trip by vm.tripState.collectAsStateWithLifecycle()

    val pipStyle by vm.settings.pipStyle.collectAsStateWithLifecycle()
    val pipSize by vm.settings.pipSize.collectAsStateWithLifecycle()
    val transparent by vm.settings.pipTransparent.collectAsStateWithLifecycle()
    val opacity by vm.settings.pipOpacity.collectAsStateWithLifecycle()

    val tripActive = trip.status == TripStatus.RUNNING || trip.status == TripStatus.PAUSED

    // نافذةٌ منكمشة بلا رحلةٍ ولا تسجيل لا تعرض إلّا رقمًا متجمّدًا على آخر قراءة، بلا
    // ما يُنبئ أنّ شيئًا انتهى. يقع هذا حين ينتهي التسجيل من تلقائه ونحن في الانكماش
    // — امتلاء القرص مثلًا — فنطوي النافذة بدل أن نكذب على من يراها.
    val context = LocalContext.current
    LaunchedEffect(tripActive, isRecording) {
        if (!tripActive && !isRecording) {
            (context as? Activity)?.moveTaskToBack(true)
        }
    }

    val kmh = liveSpeedMps * 3.6f

    // العتبات نفسها التي يلوّن بها القرص الكبير، حرفًا بحرف: النافذة امتدادٌ للعداد لا
    // شاشةٌ أخرى، ولو اختلف اللون بينهما لاختلف المعنى في عين سائقٍ يلمح ولا يقرأ.
    // ولذلك صار «حرفًا بحرف» دالّةً واحدة لا نسختين متطابقتين بالمصادفة.
    val speedColor = when (scale.zoneOf(kmh)) {
        SpeedZone.DANGER -> Danger
        SpeedZone.WARN -> Warn
        SpeedZone.NORMAL -> Accent
    }

    val recLabel = stringResource(R.string.pip_recording)

    // الشفافيّة على الخلفيّة وحدها، ولا تمسّ الرقم ولا الوجه: هذا ما يجعل فشلها
    // فشلًا هيّنًا. فإن ركّب النظامُ النافذةَ فوق أسودَ صلب — وكثيرٌ من الأجهزة يفعل،
    // وسطرُ الإعداد يقول ذلك صراحةً — خرجت الخلفيّة أغمق ممّا هي، لا خرج الرقم شبحًا.
    val background = if (transparent) Bg.copy(alpha = opacity / 100f) else Bg

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        // النافذة يعيد المستعمل تحجيمها بأصبعين، فحجمٌ ثابت بـ sp يخرج ضئيلًا على
        // نافذةٍ مكبَّرة وفائضًا على مصغَّرة. الاشتقاق من العرض هو المقياس، والارتفاع
        // حارسٌ فحسب: لو خالف النظامُ النسبةَ المطلوبة (وبعض المصنّعين يخالفها) بقي
        // العمود داخل حدوده بدل أن يُقصّ رأسه.
        val side = maxWidth.coerceAtMost(maxHeight * PIP_ASPECT)
        val boxW = maxWidth
        val boxH = maxHeight

        // حيّز النقطة الحمراء محجوزٌ من الطرفين دائمًا، ولو لم يكن ثمّة تسجيل: الرقم
        // موسَّطٌ، فحجزُه من جهةٍ واحدة كان سيزيحه عن المركز؛ وحجزُه عند بدء التسجيل
        // وحده كان سيَقفز بحجم الخطّ في اللحظة التي ينظر فيها المستعمل إلى النافذة.
        val dotEdge = side * (DOT_FRACTION + DOT_MARGIN_FRACTION)
        val freeWidth = (boxW - dotEdge * 2f).coerceAtLeast(0.dp)

        val gaugeStyle = pipStyle.faceStyle

        // ضلع الوجه الأقصر، وما يسعه من كتلة الرقم. الحالات الثلاث مختلفة اختلافًا
        // حقيقيًّا فلا تُجمع: الرقم داخل الحلقة محبوسٌ بقطرها، وفي المؤشّر بفتحة
        // القوس السفليّة، وفوق الشريط بما يفضل من ارتفاع النافذة بعد الشريط نفسه.
        val faceSide: Dp
        val roomW: Dp
        val roomH: Dp
        val baseNumber: Dp
        when (pipStyle) {
            PipStyle.NUMBER -> {
                faceSide = 0.dp
                roomW = freeWidth
                roomH = boxH * CONTENT_OF_BOX
                baseNumber = side * pipSize.numberFraction
            }

            PipStyle.BAR -> {
                faceSide = boxW * BAR_WIDTH_OF_BOX / GaugeStyle.BAR.aspect
                roomW = freeWidth
                roomH = (boxH * CONTENT_OF_BOX - faceSide * BAR_FACE_USED_OF_HEIGHT)
                    .coerceAtLeast(0.dp)
                baseNumber = faceSide * gaugeStyle.numberFractionOfSide * pipSize.step
            }

            PipStyle.RING, PipStyle.NEEDLE -> {
                faceSide = minOf(boxW, boxH) * FACE_OF_BOX
                val inner = if (pipStyle == PipStyle.NEEDLE) {
                    NEEDLE_INNER_OF_SIDE
                } else {
                    ROUND_INNER_OF_SIDE
                }
                roomW = faceSide * inner
                roomH = faceSide * inner
                baseNumber = faceSide * gaugeStyle.numberFractionOfSide * pipSize.step
            }
        }

        val numberDp = fitNumber(baseNumber, roomW, roomH)
        val unitDp = numberDp * UNIT_OF_NUMBER

        // `Dp.toSp()` لا `.value.sp`: مقياس الخطّ في إعدادات النظام يكبّر sp، ولو
        // مررناها خامًا لفاض رقمُ من يضبط جهازه على 1.3× خارج نافذةٍ لا تتّسع له.
        val density = LocalDensity.current
        val numberSize = with(density) { numberDp.toSp() }
        val unitSize = with(density) { unitDp.toSp() }

        // ظلٌّ تحت النصّ حين تُطلب الشفافيّة وحدها: ما وراء النافذة حينئذٍ مجهول —
        // خريطةٌ فاتحة أو صورةٌ أو أسود — والفيروزيّ على فاتحٍ يذوب. ومع الخلفيّة
        // الصلبة لا ظلّ، فيبقى الشكل الافتراضيّ كما كان بالبكسل الواحد. ونسبتا
        // النصف القطر والإزاحة من عقد الطبقة، فهما معايَرتان على نصٍّ فوق صورة —
        // وهي الحال بعينها هنا.
        val textShadow = if (transparent) {
            with(density) {
                Shadow(
                    color = Color.Black.copy(alpha = 0.65f),
                    offset = Offset(0f, (numberDp * HudMetrics.SHADOW_DY_OF_TEXT).toPx()),
                    blurRadius = (numberDp * HudMetrics.SHADOW_BLUR_OF_TEXT).toPx(),
                )
            }
        } else {
            null
        }

        // نمطٌ مبنيّ لا `displayLarge.copy`: أنماط Material تحمل `lineHeight` ثابتًا
        // بـ sp، فتكبيرُ الخطّ وحده يترك سطرًا أقصر من حروفه فيُقصّ.
        val numberStyle = TextStyle(
            fontSize = numberSize,
            fontWeight = FontWeight.Black,
            color = speedColor,
            shadow = textShadow,
        )
        val unitStyle = TextStyle(
            fontSize = unitSize,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            shadow = textShadow,
        )

        // ألوان الوجه من السمة الحيّة لا من ثوابت الطبقة المحروقة: هذه نافذةُ تطبيقٍ
        // تقع على خلفيّته، لا طبقةٌ على صورةٍ فوتوغرافيّة. ولا منطقةَ حمراء باهتة
        // فيها ([Color.Transparent]): على ‎120dp‎ تُقرأ لطخةً لا معلومة.
        val palette = GaugePalette(
            active = speedColor,
            track = TrackDim,
            redZone = Color.Transparent,
            tick = TextSecondary,
            tickLine = TextSecondary,
            limit = Danger,
        )
        val fraction = (kmh / scale.gaugeMaxKmh).coerceIn(0f, 1f)
        val warnFraction = (scale.warnKmh.toFloat() / scale.gaugeMaxKmh).coerceIn(0f, 1f)

        val stack: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = Fmt.speed(kmh),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    style = numberStyle,
                )
                Text(
                    text = stringResource(R.string.unit_kmh),
                    maxLines = 1,
                    style = unitStyle,
                )
            }
        }

        when (pipStyle) {
            // بلا وجه: الشجرة هي شجرة ما قبل 0.9.4 بعينها
            PipStyle.NUMBER -> stack()

            PipStyle.BAR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                stack()
                MiniBarFace(
                    width = boxW * BAR_WIDTH_OF_BOX,
                    fraction = fraction,
                    warnFraction = warnFraction,
                    limitFraction = scale.limitFraction,
                    palette = palette,
                )
            }

            PipStyle.RING, PipStyle.NEEDLE -> Box(
                modifier = Modifier
                    .width(faceSide)
                    .aspectRatio(gaugeStyle.aspect),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawGaugeFace(
                        style = gaugeStyle,
                        fraction = fraction,
                        warnFraction = warnFraction,
                        limitFraction = scale.limitFraction,
                        palette = palette,
                        strokeOfSide = PIP_STROKE_OF_SIDE,
                        ticks = null,
                    )
                }
                // الإزاحة من التصميم لا من تقديرٍ هنا: المؤشّر وحده يطلبها، وسببها
                // أنّ مركز الميناء مشغولٌ بالصرّة فيختفي الرقمُ الموسَّط تحت المؤشّر
                Box(Modifier.offset(y = faceSide * gaugeStyle.numberBiasOfSide)) { stack() }
            }
        }

        // نقطةٌ ساكنة لا وامضة: الوميض يوجب إعادة رسمٍ متّصلة ما دامت النافذة قائمة،
        // وهذا يُنقص بطّاريّةً تحتاجها رحلةٌ جارية. والنقطة الحمراء وحدها لا تلتبس.
        // وموضعها في الزاوية لا في العمود كي لا يزيح ظهورُها الرقمَ عن مركزه.
        if (isRecording) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(side * DOT_MARGIN_FRACTION)
                    .size(side * DOT_FRACTION)
                    .background(Danger, CircleShape)
                    .semantics { contentDescription = recLabel },
            )
        }
    }
}

/**
 * أكبر حجم خطٍّ تسعه كتلةُ «الرقم + وحدته» في صندوقٍ بأبعادٍ معلومة.
 *
 * لماذا حدّان لا حدٌّ واحد: النافذة عريضةٌ قصيرة، فقد يفيض الرقم عرضًا (ثلاث خانات
 * تلامس النقطة الحمراء) أو ارتفاعًا (سطران يتجاوزان علوّ النافذة)، وأيّهما وقع
 * كفى لإفساد المنظر. والحدّان مشتقّان لا مقدَّران:
 * - **عرضًا**: [MAX_DIGITS] خانةً عرضُ كلٍّ منها [DIGIT_ADVANCE] من حجم الخطّ.
 * - **ارتفاعًا**: صندوق الرقم `حجم × FONT_BOX` وصندوق الوحدة `حجم × UNIT_OF_NUMBER
 *   × FONT_BOX`، ومجموعهما `حجم × FONT_BOX × (1 + UNIT_OF_NUMBER)`. وحلُّ المعادلة
 *   لـ«الحجم» يزيل الدور: لولاه لتوقّف حجم الوحدة على الرقم وتوقّف الرقم على
 *   الوحدة.
 *
 * ولا حدَّ أدنى هنا: أصغر نافذةٍ يسمح بها النظام تعطي رقمًا فوق ‎20dp‎، وحدٌّ أدنى
 * كان سيسمح بالفيض في حالٍ نادرة بدل أن يمنعه.
 */
private fun fitNumber(base: Dp, roomWidth: Dp, roomHeight: Dp): Dp {
    val byWidth = roomWidth / (MAX_DIGITS * DIGIT_ADVANCE)
    val byHeight = roomHeight / (HudMetrics.FONT_BOX * (1f + UNIT_OF_NUMBER))
    return minOf(base, byWidth, byHeight)
}

/**
 * تصميم الوجه الذي يرسمه كلُّ شكلٍ من أشكال النافذة المصغَّرة.
 *
 * [PipStyle] ليس [GaugeStyle]، وهذا مقصودٌ في الإعدادات: النافذة تعرض أربعةَ
 * خياراتٍ مختصرة لا ستّة. وهذه الخاصّيّة هي الجسر بينهما، **موضعًا واحدًا**: لو
 * تفرّق التحويل بين موضع القياس وموضع الرسم لخرج الرقم مقيسًا على وجهٍ وموضوعًا على
 * غيره.
 *
 * و[PipStyle.NUMBER] يعطي [GaugeStyle.MINIMAL] لا معدومًا كي تبقى الخاصّيّة
 * كلّيّةً، ولا يُرسم به شيء: فرعُه في [PipScreen] لا يستدعي [drawGaugeFace] أصلًا.
 */
private val PipStyle.faceStyle: GaugeStyle
    get() = when (this) {
        // «حلقة» هي القوس الكلاسيكيّ بلا أرقامه: سميكٌ يُقرأ بلمحة، لا الخيط الرفيع
        PipStyle.RING -> GaugeStyle.CLASSIC
        PipStyle.NEEDLE -> GaugeStyle.NEEDLE
        PipStyle.BAR -> GaugeStyle.BAR
        PipStyle.NUMBER -> GaugeStyle.MINIMAL
    }

/**
 * درجة الحجم منسوبةً إلى [PipSize.MEDIUM].
 *
 * الأشكال ذات الوجه لا تشتقّ رقمها من ضلع النافذة بل من ضلع الوجه عبر
 * [GaugeStyle.numberFractionOfSide] — وتلك نسبٌ معايَرةٌ بحيث لا يلامس الرقمُ
 * القوس. فلو ضربناها في [PipSize.numberFraction] خامًا (‎0.28‎…‎0.50‎) لانكمش الرقم
 * إلى ثلث ما ينبغي. والنسبة إلى الوسط تجعل «متوسّط» هو المعايرة نفسها، و«صغير»
 * و«كبير» خطوتين حولها.
 */
private val PipSize.step: Float
    get() = numberFraction / PipSize.MEDIUM.numberFraction

/**
 * وجه [GaugeStyle.BAR] مقصوصًا على القدر المرسوم منه فعلًا.
 *
 * مشتركٌ بين المصغَّرتين — نافذة «صورة في صورة» وطبقة الكاميرا — لأنّ القصّ حسابٌ
 * هندسيّ لا زينة، ونسختان منه كانتا ستتباعدان عند أوّل تعديل كما تباعدت من قبلُ
 * نسخُ القوس الكلاسيكيّ. وعلّة القصّ ومقداره عند [BAR_FACE_USED_OF_HEIGHT].
 *
 * **`requiredWidth`/`requiredHeight` لا `width`/`height`**: هاتان تحصران نفسيهما في
 * قيود الأب، فكان القماش ينكمش إلى ارتفاع الصندوق المقصوص — أي يُرسم شريطٌ أقصر بدل
 * أن يُقصّ شريطٌ كامل، فتعود الكبسولة نحيلةً ويعود الفراغ معها. و«المطلوب» يتجاوز
 * القيود، فيُرسم الوجه بنسبته ثمّ يقصّه [clipToBounds].
 *
 * @param width عرض الوجه؛ ارتفاعه يُشتقّ من [GaugeStyle.aspect] فلا يُمرَّر.
 * @param limitFraction موضع حدّ السائق، والسالب يعني «لا حدّ» — اصطلاح
 *   [net.gnutux.speedometer.core.alert.SpeedScale.limitFraction] نفسه.
 */
@Composable
internal fun MiniBarFace(
    width: Dp,
    fraction: Float,
    warnFraction: Float,
    limitFraction: Float,
    palette: GaugePalette,
    modifier: Modifier = Modifier,
) {
    val faceHeight = width / GaugeStyle.BAR.aspect
    Box(
        modifier = modifier
            .width(width)
            .height(faceHeight * BAR_FACE_USED_OF_HEIGHT)
            .clipToBounds(),
    ) {
        Canvas(
            Modifier
                .align(Alignment.TopStart)
                .requiredWidth(width)
                .requiredHeight(faceHeight)
        ) {
            drawGaugeFace(
                style = GaugeStyle.BAR,
                fraction = fraction,
                warnFraction = warnFraction,
                limitFraction = limitFraction,
                palette = palette,
                ticks = null,
            )
        }
    }
}

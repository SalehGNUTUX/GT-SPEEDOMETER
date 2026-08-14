package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextSecondary
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

/** الرقم: كسرٌ من ضلع النافذة المرجعيّ. ثلاث خانات عند 0.38 تشغل نحو ثلثي العرض */
private const val NUMBER_FRACTION = 0.38f
private const val UNIT_FRACTION = 0.10f
private const val DOT_FRACTION = 0.08f
private const val DOT_MARGIN_FRACTION = 0.05f

/**
 * محتوى نافذة «صورة في صورة»: رقم السرعة وحده، ووحدته، ونقطةٌ حمراء إن كان التسجيل
 * جاريًا. لا تبويبات ولا بلاطات ولا معاينة كاميرا.
 *
 * سببُ التقشّف أنّ النافذة بعرض إصبعين وتُقرأ بطرف العين: كلّ عنصرٍ إضافيّ يقتطع من
 * حجم الخانة، والخانة هي كلّ ما يريده من غادر التطبيق وهو يقيس.
 */
@Composable
fun PipScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    // نافذةٌ منكمشة بلا رحلةٍ ولا تسجيل لا تعرض إلّا رقمًا متجمّدًا على آخر قراءة،
    // بلا ما يُنبئ أنّ شيئًا انتهى. يقع هذا حين ينتهي التسجيل من تلقائه ونحن في
    // الانكماش — امتلاء القرص مثلًا — فنطوي النافذة بدل أن نكذب.
    val liveSpeedMps by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val scale by vm.speedScale.collectAsStateWithLifecycle()
    val isRecording by vm.isRecordingSession.collectAsStateWithLifecycle()
    val trip by vm.tripState.collectAsStateWithLifecycle()

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        // النافذة يعيد المستعمل تحجيمها بأصبعين، فحجمٌ ثابت بـ sp يخرج ضئيلًا على
        // نافذةٍ مكبَّرة وفائضًا على مصغَّرة. الاشتقاق من العرض هو المقياس، والارتفاع
        // حارسٌ فحسب: لو خالف النظامُ النسبةَ المطلوبة (وبعض المصنّعين يخالفها) بقي
        // العمود داخل حدوده بدل أن يُقصّ رأسه.
        val side = maxWidth.coerceAtMost(maxHeight * PIP_ASPECT)

        // `Dp.toSp()` لا `.value.sp`: مقياس الخطّ في إعدادات النظام يكبّر sp، ولو
        // مررناها خامًا لفاض رقمُ من يضبط جهازه على 1.3× خارج نافذةٍ لا تتّسع له.
        val density = LocalDensity.current
        val numberSize = with(density) { (side * NUMBER_FRACTION).toSp() }
        val unitSize = with(density) { (side * UNIT_FRACTION).toSp() }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = Fmt.speed(kmh),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                // نمطٌ مبنيّ لا `displayLarge.copy`: أنماط Material تحمل `lineHeight`
                // ثابتًا بـ sp، فتكبيرُ الخطّ وحده يترك سطرًا أقصر من حروفه فيُقصّ.
                style = TextStyle(
                    fontSize = numberSize,
                    fontWeight = FontWeight.Black,
                    color = speedColor,
                ),
            )
            Text(
                text = stringResource(R.string.unit_kmh),
                maxLines = 1,
                style = TextStyle(
                    fontSize = unitSize,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                ),
            )
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

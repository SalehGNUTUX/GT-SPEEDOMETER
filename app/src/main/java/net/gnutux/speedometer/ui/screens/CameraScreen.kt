package net.gnutux.speedometer.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.alert.SpeedScale
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.location.FixQuality
import net.gnutux.speedometer.core.location.GnssInfo
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.settings.CameraScene
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import kotlin.math.cos
import kotlin.math.sin

// ===== شبكة الطبقة =====
//
// المقاسات لم تعد أرقامًا مطلقة هنا: كانت الشاشة تحمل ‎124dp‎ و‎16dp‎ و‎44sp‎ بينما
// يحمل الراسم المحروق نسبًا من الضلع الأقصر، فتطابق الرسمان على هاتفٍ عرضه ‎411dp‎
// وحده. القرص نفسه كان يشغل ‎%34‎ من هاتفٍ عرضه ‎360dp‎ و‎%26‎ من لوحٍ عرضه ‎480dp‎،
// والملفّ يحرقه ‎%30‎ أبدًا. مصدر المقاسات الآن `HudMetrics` — العقد الذي يقرأ منه
// الراسمان معًا — ويُضرب هنا في الضلع الأقصر لمساحة المعاينة (`BoxWithConstraints`)
// كما يُضرب هناك في `min(w, h)` للإطار. مرجع التخطيط `HUD-SPEC.md`.

private val CHIP_HEIGHT = 36.dp
private val CHIP_PAD_H = 12.dp
private val CHIP_GAP = 8.dp
private val CHIP_ICON = 16.dp

// أحجام الأزرار والحبّات تبقى مطلقة عمدًا: هي قواعد إصبعٍ لا نسبَ تصميم، والقاعدة
// السادسة تشترط ‎56dp‎ و‎72dp‎ فعليّة على كلّ جهاز. ولا تُحرق في الملفّ أصلًا.

/** القاعدة السادسة: أصغر مساحة لمس */
private val TOUCH_MIN = 56.dp

private val ACTION_ROW_HEIGHT = 80.dp
private val RECORD_SIZE = 80.dp

/** القاعدة السادسة: أزرار الفعل الرئيسة ≥ 72dp */
private val SHUTTER_SIZE = 72.dp

/**
 * أقصى عرضٍ للوحة اختيار وضع التصوير. مطلقٌ عمدًا كسائر عناصر التحكّم: هي لا تُحرق،
 * وثلاثة أسطرٍ قصيرة لا تحتاج أكثر من هذا مهما اتّسعت الشاشة — واللوحة الممتدّة على
 * عرض لوحٍ كامل تُقرأ نافذةَ خطأٍ لا قائمةَ اختيار.
 */
private val PICKER_MAX_WIDTH = 320.dp

/**
 * دون هذا العرض لا يتّسع الشريط العلويّ لحبّاته بنصوصها، فتُقصّ آخرُها. والقصّ
 * أسوأ من الحذف لأنّه يوهم بعطبٍ في الرسم، فما دون الحدّ تسقط النصوص الثانويّة
 * وتبقى الأيقونات.
 *
 * ارتفع من ‎360dp‎ إلى ‎430dp‎ في 0.8.0: صارت الحبّات خمسًا بإضافة وضع التصوير،
 * وأقصى ما تطلبه مجتمعةً بنصوصها نحو ‎420dp‎ مع الهوامش. أثرُ ذلك أنّ الهاتف
 * المرجعيّ (‎411dp‎ رأسيًّا) صار مضغوطًا — أيقوناتٌ بلا نصّ — بينما يبقى الوضع
 * الأفقيّ، وهو وضع التثبيت على الزجاج، على نصوصه كاملة.
 */
private val COMPACT_WIDTH = 430.dp

/**
 * عمود التحكّم الجانبيّ: الكشّاف والتبديل والمبادلة.
 *
 * على الحافّة اليمنى **المطلقة** لا المنطقيّة، كسائر ما في هذه الشاشة: القرص في أقصى
 * اليسار ولوح الإحصاءات في أقصى اليمين وكلاهما مثبَّتٌ بإحداثيّات، ولو تبع هذا العمود
 * اتّجاهَ الكتابة لقفز فوق القرص على جهازٍ لغته لاتينيّة. وهو في المنتصف رأسيًّا
 * فلا يزاحم الشريطين العلويّ والسفليّ.
 *
 * ولم يُوضع في الشريط العلويّ لأنّ حبّاته صارت خمسًا، ولا في صفّ الأزرار السفليّ
 * لأنّه أثلاثٌ متساوية يقع زرّ التسجيل في وسطها بالبناء — وإقحام رابعٍ يزيح الوسط.
 */
private val SIDE_GAP = 10.dp

/** عرض سطر «لا مصباح»: جملةٌ قصيرة على سطرين، ولا تتمدّد فتحجب المشهد */
private val SIDE_NOTE_WIDTH = 150.dp

/** مهلة السطر التعليميّ عن لفّ المقطع — كمهلة رسائل المصير نفسها تقريبًا */
private const val SWITCH_HINT_MS = 4_500L

/**
 * سماكة إطار وميض الشاشة، نسبةً إلى الضلع الأقصر.
 *
 * إطارٌ لا حجاب: تغطية المعاينة بأبيضَ كامل تُعمي المصوِّر عمّا يصوّره. و‎%16‎ من
 * الضلع تعطي على هاتفٍ متوسّط مساحةً بيضاء تقارب ثلث الشاشة، وهي ما يكفي لإضاءة
 * وجهٍ على مسافة ذراع. وهي نسبةٌ لا رقم ثابت كي يتساوى أثرها على الهاتف واللوح —
 * وليست من عقد `HudMetrics` لأنّها ليست ممّا يُحرق في الملفّ أصلًا.
 */
private const val FLASH_RING_OF_SHORT_SIDE = 0.16f

/**
 * تباعدُ الحروف صفرٌ في نصوص الطبقة المحروقة.
 *
 * جدول Material 3 يضيف ‎0.5sp‎ لـ `labelMedium` و‎0.15sp‎ لـ `titleMedium`، و`Paint`
 * لا يملك مقابلًا لذلك في مقطعٍ دون مقطع (`letterSpacing` خاصّيّةُ الأداة كلّها لا
 * مقطعٍ منها). فبدل أن يخرج نصّ الشاشة أعرض من المحروق بنحو ‎%2‎ في كلّ سطر،
 * نُصفّر التباعد في الطرفين. النصوص التي لا تُحرق (حبّات الشريط العلويّ) تُترك
 * على الجدول.
 */
private val NO_TRACKING = 0.sp

/** المواصفة: ألواحٌ شبه شفّافة، أسود بشفافيّة 0.50 */
private val PANEL = Color.Black.copy(alpha = HudMetrics.PANEL_ALPHA)

/** مسار القوس: المواصفة نفسها، ‎0.50‎ — لا ‎0.51‎ كما كان في الحرق */
private val RING_TRACK = Color.Black.copy(alpha = HudMetrics.TRACK_ALPHA)

// ألوان الطبقة ثابتة لا تتبع سمة التطبيق: اللوحة الفاتحة تُنزل إضاءة الفيروزيّ كي
// يُقرأ على أبيض، لكنّ الطبقة تقع على صورةٍ فوتوغرافيّة لا على خلفيّة التطبيق،
// والملفّ المحروق يستعمل الثلاثيّ الداكن أبدًا. اختلافهما يجعل الشاشة تكذب على الملفّ.
// ولذلك تُقرأ من العقد نفسه لا من `DarkPalette`: قيمةٌ واحدة لا نسختان متطابقتان
// بالمصادفة.
private val HUD_ACCENT = Color(HudMetrics.COLOR_ACCENT)
private val HUD_WARN = Color(HudMetrics.COLOR_WARN)
private val HUD_DANGER = Color(HudMetrics.COLOR_DANGER)

/**
 * مقاسات الطبقة بوحدات Compose، مشتقّةً من نسب [HudMetrics] بضلعٍ أقصرَ بعينه.
 *
 * **لماذا `Dp.toSp()` للنصوص:** الطبقة رسمٌ لا نصُّ متن، وما يُحرق في الملفّ لا
 * يعرف مقياس خطّ النظام. لو تُركت الأحجام بـ `sp` خام لكبر نصّ الشاشة عند من رفع
 * مقياس الخطّ وحده وخالف ملفّه. والتحويل من `dp` يُلغي أثر المقياس فيبقى الرسمان
 * واحدًا؛ ومقروئيّة العدّاد مضمونةٌ بحجمه أصلًا (رقم القرص ‎%10.7‎ من الضلع).
 *
 * و`lineHeight` مثبَّتٌ صراحةً للسبب نفسه: صناديق السطور في Material 3 بـ `sp`،
 * فتتمدّد بمقياس الخطّ وتزيح الكتلة عمّا يحرقه الراسم.
 */
@Immutable
private class HudDim(shortSide: Dp, density: Density) {
    private val s = HudMetrics.of(shortSide.value)

    val margin: Dp = s.margin.dp
    val blockGap: Dp = s.blockGap.dp
    val corner: Dp = s.panelCorner.dp
    val ringDiameter: Dp = s.ringDiameter.dp
    val panelPadH: Dp = s.panelPadH.dp
    val panelPadV: Dp = s.panelPadV.dp
    val lineGap: Dp = s.lineGap.dp
    val statGap: Dp = s.statGap.dp
    val statLineBox: Dp = s.statLineBox.dp
    val gaugeStackGap: Dp = s.gaugeStackGap.dp

    val statLabel: TextUnit = with(density) { s.statLabelText.dp.toSp() }
    val statValue: TextUnit = with(density) { s.statValueText.dp.toSp() }
    val statLabelBox: TextUnit = with(density) { s.statLabelBox.dp.toSp() }
    val statValueBox: TextUnit = with(density) { s.statValueBox.dp.toSp() }
    val gaugeValue: TextUnit = with(density) { s.gaugeValueText.dp.toSp() }
    val gaugeUnit: TextUnit = with(density) { s.gaugeUnitText.dp.toSp() }
    val gaugeValueBox: TextUnit = with(density) { s.gaugeValueBox.dp.toSp() }
    val gaugeUnitBox: TextUnit = with(density) { s.gaugeUnitBox.dp.toSp() }

    // الخلفيّة صورةٌ متحرّكة، والنصّ بلا ظلّ يذوب في المشهد الفاتح. الظلّ للنصّ
    // الواقع على الصورة مباشرةً (رقم القرص ووحدته)، أمّا ما فوق لوحٍ داكن فيكفيه
    // اللوح. ونصف قطره وإزاحته من حجم الحرف لا بالبكسل: القيمة الثابتة كانت تعطي
    // ظلًّا باهتًا على الشاشة وثقيلًا في ملفٍّ بدقّة 1080.
    val valueShadow: Shadow = shadowFor(density, s.gaugeValueText)
    val unitShadow: Shadow = shadowFor(density, s.gaugeUnitText)

    private fun shadowFor(density: Density, textSize: Float): Shadow = with(density) {
        Shadow(
            color = Color.Black,
            offset = Offset(0f, (HudMetrics.SHADOW_DY_OF_TEXT * textSize).dp.toPx()),
            blurRadius = (HudMetrics.SHADOW_BLUR_OF_TEXT * textSize).dp.toPx(),
        )
    }
}

/**
 * الكاميرا الحيّة وفوقها طبقة العدّاد.
 *
 * الفيديو نظيف بطبيعته: ما تراه هنا طبقةُ شاشةٍ بـ Compose، والمسار يُحفَظ منفصلًا
 * بـ GPX، ولحظة بدء الترميز تُثبَّت على محور elapsedRealtime فتُعاد محاذاة الطبقة
 * على الفيديو لاحقًا بلا تخمين.
 *
 * توگل «محروق / نظيف» في الشريط العلويّ. الحرق الفعليّ يجري في `CameraSession` عبر
 * `OverlayEffect`، لا هنا؛ هذه الشاشة تعرض الحالة وتبدّلها فقط. والتوگل يُقفَل أثناء
 * التسجيل لأنّ تغييره يستلزم إعادة ربط الكاميرا.
 *
 * زرّ اللقطة يلتقط الشاشة كما هي — الكاميرا والطبقة معًا — فيعطي صورةً محروقة
 * مهما كانت حالة التوگل.
 *
 * جديد في 0.4.0: رسالة مصير التسجيل صارت خمس حالات لا اثنتين، لأنّ «حُفظ» و«حُفظ
 * ناقصًا» و«لا شيء» مصائر مختلفة لا يجوز جمعها تحت «تعذّر الحفظ». ومعها مؤشّرُ
 * تقسيمٍ صغير بجوار شارة التسجيل يُنذر بأنّ الملفّ سيُلَفّ.
 *
 * ودورة حياة الكاميرا لم تعد دورة حياة هذا النشاط: نُمرّر [LocalLifecycleOwner]
 * إلى الجلسة لتتبعه في الأحوال العاديّة لا لتربط به حالات الاستعمال. الفرق أنّ
 * الجلسة تستطيع أن تعصي المضيف حين يجب — وهو ما يُبقي التصوير حيًّا بعد ضغطة زرّ
 * الشاشة الرئيسة أو انطفاء الشاشة — بينما كان الربط المباشر يُنهي التسجيل بـ
 * `SOURCE_INACTIVE` قبل أن تصل أيّ شفرةٍ منّا.
 *
 * ## التخطيط (إعادة بناء 0.4.0)
 * الطبقة صارت شبكةً واحدة لا كتلًا عائمة، وفق `HUD-SPEC.md`:
 * - شريطٌ علويّ واحد: حبّاتٌ متساوية الارتفاع ونصف القطر والحشو، الأقمار في أقصى
 *   اليسار، ثمّ يمينًا: الحرق، فالتقسيم، فشارة التسجيل.
 * - شريطٌ سفليّ واحد: القرص خليّةً يسارًا والإحصاءات خليّةً يمينًا، على قاعدةٍ
 *   واحدة وهامشٍ واحد ونصف قطرٍ واحد. **المواضع أعلاه مطلقة لا منطقيّة**
 *   (`Arrangement.Absolute`): الملفّ المحروق يُرسم بإحداثيّات بكسل، فلو تبدّل
 *   اتّجاه التخطيط يومًا لانعكست الشاشة وحدها وخالفت الملفّ.
 *
 * @param onPreviewTap لمسةٌ على المعاينة — يستعملها الجذر لإظهار شريط التبويبات
 *   بعد إخفائه أثناء الركوب. تُلتقط من طبقةٍ شفّافة فوق المعاينة وتحت عناصر
 *   التحكّم، فلا تسرقها المعاينة ولا تحجب الأزرار.
 */
@Composable
fun CameraScreen(
    vm: SpeedoViewModel,
    modifier: Modifier = Modifier,
    onPreviewTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    // المدى والعتبة والحدّ من الاشتقاق الواحد الذي يقرأ منه الراسم المحروق كذلك
    // (عبر `HudSnapshot`)، فيبقى المرسوم هو المحروق كما توجب المواصفة
    val scale by vm.speedScale.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val isPaused by vm.isVideoPaused.collectAsStateWithLifecycle()
    val cameraMessage by vm.cameraMessage.collectAsStateWithLifecycle()
    val burnOverlay by vm.burnOverlay.collectAsStateWithLifecycle()
    val segmentMinutes by vm.camera.segmentMinutes.collectAsStateWithLifecycle()
    val scene by vm.cameraScene.collectAsStateWithLifecycle()
    val torchOn by vm.camera.torchOn.collectAsStateWithLifecycle()
    val hasTorch by vm.camera.hasTorch.collectAsStateWithLifecycle()
    val screenFlashOn by vm.camera.screenFlashOn.collectAsStateWithLifecycle()
    val screenFlashEnabled by vm.camera.screenFlashEnabled.collectAsStateWithLifecycle()
    val dualActive by vm.camera.dualActive.collectAsStateWithLifecycle()
    val confirmRecording by vm.settings.confirmRecording.collectAsStateWithLifecycle()

    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var captureTick by remember { mutableIntStateOf(0) }
    var hideControls by remember { mutableStateOf(false) }
    var scenePickerOpen by remember { mutableStateOf(false) }
    var switchHint by remember { mutableStateOf(false) }
    var confirmRecord by remember { mutableStateOf(false) }

    // COMPATIBLE يفرض TextureView: SurfaceView لا يظهر في PixelCopy لنافذة التطبيق،
    // فكانت اللقطة تخرج بمستطيل أسود مكان المعاينة.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // المفتاح هو حالة الحرق لا Unit: إضافة `OverlayEffect` أو نزعه تستلزم إعادة بناء
    // `UseCaseGroup`، ولا سبيل إلى ذلك إلّا بربطٍ جديد. بدونه كان التوگل يقلب الشارة
    // ويترك الملفّ المسجَّل على حاله — يَعِد بالحرق ويسلّم نظيفًا، أو العكس.
    //
    // ولا تُضاف حالة المضيف إلى المفاتيح: الأثر يجب ألّا يُعاد تشغيله عند كلّ
    // ذهابٍ إلى الخلفيّة وعودة، فإعادة الربط في منتصف التسجيل تقتله. الجلسة تتابع
    // المضيف بنفسها، وهذا الأثر يقول لها «ثمّة شاشة» و«زالت الشاشة» لا أكثر.
    DisposableEffect(burnOverlay) {
        vm.camera.bind(lifecycleOwner, previewView) { failed = true }
        onDispose { vm.camera.detach() }
    }

    // رسالة صريحة عن مصير التسجيل. الصمت هنا هو ما جعل المستخدم يظنّ أن
    // شيئًا لم يُحفظ بينما كان الملف يُكتب في مجلد لا يراه. ولأنّ ثلاثًا من الحالات
    // تُنتج ملفًّا على القرص، تُحدَّث مكتبة الوسائط فيها جميعًا لا عند النجاح وحده.
    LaunchedEffect(cameraMessage) {
        when (val m = cameraMessage) {
            is CameraSession.Message.Saved -> {
                toast = context.getString(R.string.recording_saved, m.name)
                vm.refreshMedia()
            }

            is CameraSession.Message.Truncated -> {
                toast = context.getString(R.string.recording_truncated, m.name)
                vm.refreshMedia()
            }

            is CameraSession.Message.Segment -> {
                toast = context.getString(R.string.recording_segment, m.name)
                vm.refreshMedia()
            }

            is CameraSession.Message.Failed ->
                toast = context.getString(R.string.recording_failed, context.getString(m.reason))

            CameraSession.Message.BurnUnsupported ->
                toast = context.getString(R.string.burn_unsupported)

            CameraSession.Message.SceneUnsupported ->
                toast = context.getString(R.string.camera_scene_unsupported)

            // خبرٌ عن الكاميرا لا عن الملفّ: يُعرض كما هو بلا «تعذّر حفظ التسجيل»
            is CameraSession.Message.Notice -> toast = context.getString(m.text)

            null -> return@LaunchedEffect
        }
        delay(3500)
        toast = null
        // الاستهلاك بعد الانتظار لا قبله: هو يُصفّر `cameraMessage` وهو مفتاح هذا
        // الأثر، فاستهلاكُه في البداية كان يُلغي الأثر عند `delay` فتبقى الشارة
        // معلّقة على الشاشة إلى الأبد. ووصولُ رسالةٍ جديدة أثناء الانتظار يُعيد
        // تشغيل الأثر قبل الاستهلاك، فلا تضيع رسالة.
        vm.consumeCameraMessage()
    }

    // السطر التعليميّ عن لفّ المقطع: مرّةً واحدة مع بدء كلّ تسجيل. المفتاح هو
    // [isRecording] وهو لا يُخفض بين مقطعين، فلا يتكرّر السطر مع كلّ لفّة ملفّ.
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            switchHint = false
            return@LaunchedEffect
        }
        switchHint = true
        delay(SWITCH_HINT_MS)
        switchHint = false
    }

    LaunchedEffect(captureTick) {
        if (captureTick == 0) return@LaunchedEffect
        hideControls = true
        // لوحة الاختيار ليست جزءًا من المشهد: هي عنصر تحكّم كالأزرار، ولا يجوز أن
        // تُخلَّد في لقطةٍ يُشارِكها المصوِّر
        scenePickerOpen = false
        // إطاران حتى تختفي الأزرار فعلًا قبل الالتقاط
        withFrameNanos { }
        withFrameNanos { }
        captureWindow(view) { bitmap ->
            if (bitmap == null) {
                toast = context.getString(R.string.shot_failed)
                hideControls = false
            } else {
                vm.saveScreenshot(bitmap) { ok ->
                    toast = context.getString(if (ok) R.string.shot_saved else R.string.shot_failed)
                    hideControls = false
                }
            }
        }
        delay(3000)
        toast = null
    }

    // الضلع الأقصر من قيود هذا المركّب لا من إعدادات الجهاز: هو الضلع نفسه الذي
    // يقيس عليه الراسم المحروق (`min(w, h)` للإطار)، فيخرج التصميم بالنسب ذاتها
    // على هاتفٍ ‎360dp‎ وعلى لوحٍ ‎480dp‎ وفي الوضعين الرأسيّ والأفقيّ.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val shortSide = if (maxHeight == Dp.Infinity) maxWidth else minOf(maxWidth, maxHeight)
        val dim = remember(shortSide, density) { HudDim(shortSide, density) }
        val compact = maxWidth < COMPACT_WIDTH
        // يُقرأ هنا لا في عمق الشجرة: `maxWidth` خاصّيّةُ مُستقبِلٍ ضمنيّ
        // (`BoxWithConstraintsScope`)، وقراءتها داخل لامدا متداخلة تتطلّب بقاء ذلك
        // المُستقبِل مرئيًّا. المتغيّر المحلّيّ يُلتقط بالإغلاق فلا يتعلّق بشيء.
        val availableWidth = maxWidth

        if (failed) {
            Text(
                text = stringResource(R.string.camera_unavailable),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        // طبقة اللمس: تُعلن قبل عناصر التحكّم فتقع تحتها في ترتيب الرسم والإصابة،
        // وفوق `AndroidView` فلا تبتلع `PreviewView` اللمسة. هذا هو مخرج المستعمل
        // الوحيد حين يختفي شريط التبويبات أثناء التسجيل، فلا يجوز أن يعتمد على
        // سحبٍ أو على زرّ قد يكون معطَّلًا.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { onPreviewTap() } }
        )

        // ===== وميض الشاشة =====
        //
        // إطارٌ أبيض **حول** المعاينة لا فوقها: هو مصدرُ ضوءٍ يقوم مقام مصباحٍ لا
        // تملكه العدسة الأماميّة، ومن يستعمله يريد أن يرى ما يصوّره في الوقت نفسه.
        // ورفعُ سطوع النافذة إلى أقصاه يقع في `MainActivity` لأنّه صفةٌ للنافذة لا
        // للتركيب، ويُعاد إلى ما كان في كلّ مسارات الخروج.
        //
        // ولا يُلتقط بالحجاب في اللقطة: `Modifier.border` طلاءٌ في حدود الصندوق نفسه،
        // فيظهر في `PixelCopy` كما يراه المصوِّر — وهذا مقصود، الضوء جزءٌ من المشهد.
        if (screenFlashOn) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(shortSide * FLASH_RING_OF_SHORT_SIDE, Color.White)
            )
        }

        // ===== عمود التحكّم الجانبيّ =====
        //
        // يختفي مع سائر الأزرار قبل اللقطة: هو تحكّمٌ لا مشهد.
        if (!hideControls) {
            Column(
                modifier = Modifier
                    // مطلقٌ لا منطقيّ، كعقد هذه الشاشة كلّه
                    .align(AbsoluteAlignment.CenterRight)
                    .padding(end = dim.margin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SIDE_GAP),
            ) {
                // الزرّ يظهر إن كان ثمّة ما يُضيء: مصباحٌ حقيقيّ، أو شاشةٌ أذن
                // المستعمل بأن تقوم مقامه. وإلّا فسطرٌ يقول لماذا لا زرّ هنا، فغيابُ
                // الزرّ بلا تفسير يُقرأ عطبًا
                if (hasTorch || screenFlashEnabled) {
                    TorchButton(
                        on = torchOn,
                        screenFlash = !hasTorch,
                        onClick = vm::toggleTorch,
                    )
                } else {
                    SideNote(dim, stringResource(R.string.camera_torch_none))
                }

                // في الوضع المزدوج لا معنى لتبديل العدسة — كلتاهما تعمل — وإنّما
                // لمبادلة الكبيرة بالمصغَّرة
                if (dualActive) {
                    SideButton(
                        icon = Icons.Filled.FlipCameraAndroid,
                        label = stringResource(R.string.camera_dual_swap),
                        onClick = vm::swapDualPrimary,
                    )
                } else {
                    SideButton(
                        icon = Icons.Filled.Cameraswitch,
                        label = stringResource(R.string.camera_switch),
                        onClick = vm::switchLens,
                    )
                }
            }
        }

        // السطر التعليميّ: التبديل أثناء التسجيل يُغلق المقطع ويبدأ آخر. يُقال مرّةً
        // لأنّ الراكب يظنّ أوّل مرّةٍ أنّ التسجيل انقطع، ولا يحتاج أن يُقال بعدها
        if (switchHint && !hideControls) {
            Text(
                text = stringResource(R.string.camera_switch_rolls),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = PICKER_MAX_WIDTH)
                    .background(PANEL, RoundedCornerShape(dim.corner))
                    .padding(horizontal = dim.panelPadH, vertical = dim.panelPadV),
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            )
        }

        // ===== الشريط العلويّ: صفٌّ واحد، حبّاتٌ متساوية =====
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = dim.margin, vertical = 8.dp),
            // مطلقة لا منطقيّة: الأقمار في أقصى اليسار مهما كان اتّجاه التخطيط،
            // كما ينصّ عقد الطبقة
            horizontalArrangement = Arrangement.Absolute.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SatellitesChip(dim, gnss)
            Row(
                horizontalArrangement = Arrangement.Absolute.spacedBy(CHIP_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SceneChip(
                    dim = dim,
                    compact = compact,
                    scene = scene,
                    onClick = { scenePickerOpen = !scenePickerOpen },
                )
                BurnChip(
                    dim = dim,
                    compact = compact,
                    enabled = burnOverlay,
                    locked = isRecording,
                    onToggle = { vm.setBurnOverlay(!burnOverlay) },
                )
                if (segmentMinutes > AppSettings.SEGMENT_CONTINUOUS) SegmentChip(dim, segmentMinutes)
                // شارةٌ واحدة لا شارتان: «موقوف» تحلّ محلّ REC في الخانة نفسها.
                // إظهارهما معًا يقول «يسجّل» و«لا يسجّل» في سطرٍ واحد، ويزيد عرض
                // الشريط في اللحظة التي لا تحتمل زيادة
                if (isRecording) {
                    if (isPaused) PausedChip(dim, compact) else RecordingChip(dim, compact)
                }
            }
        }

        toast?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    // تحت الشريط العلويّ تمامًا: 8 حشو + 56 مساحة لمس + 8 حشو + 8 فرجة
                    .padding(top = 80.dp, start = dim.margin, end = dim.margin)
                    .background(PANEL, RoundedCornerShape(dim.corner))
                    .padding(horizontal = dim.panelPadH, vertical = dim.panelPadV),
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            )
        }

        // ===== الشريط السفليّ: خليّتان وشبكةٌ واحدة، ثمّ الأزرار =====
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = dim.margin)
                // الطبقة هنا ترتفع فوق صفّ الأزرار، والمحروق يلتصق بهامش القاع.
                // **فرقٌ متعمَّد**: الأزرار لا تُحرق.
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // القرص يسارًا والإحصاءات يمينًا بإحداثيّات مطلقة: هذا ما يراه
                // المصوِّر وما يجب أن يخرج في الملفّ (عقد الطبقة)
                horizontalArrangement = Arrangement.Absolute.SpaceBetween,
                // قاعدةٌ واحدة للخليّتين: أسفلُ صندوق القرص هو أسفلُ لوح الإحصاءات،
                // وهي المحاذاة نفسها التي يبني عليها الراسم المحروق تخطيطه
                verticalAlignment = Alignment.Bottom,
            ) {
                SpeedRing(
                    dim = dim,
                    speedKmh = liveSpeed * 3.6f,
                    maxKmh = scale.gaugeMaxKmh,
                    warnKmh = scale.warnKmh,
                    limitKmh = scale.limitKmh,
                )
                StatsPanel(
                    dim = dim,
                    // شرط اللامتراكب نفسه الذي يحسبه الراسم: ما يتبقّى من العرض
                    // بعد الهامشين والقرص وأدنى فجوةٍ بين الكتلتين. من غيره كان
                    // اللوح ينمو حتّى يلامس القوس على الشاشات الضيّقة، بينما يقصّه
                    // الملفّ — فيختلف الاثنان حيث يجب أن يتّفقا
                    maxWidth = (availableWidth - dim.margin * 2 - dim.ringDiameter - dim.blockGap)
                        .coerceAtLeast(0.dp),
                    distance = Fmt.distance(trip.distanceKm),
                    maxSpeed = Fmt.speed(trip.maxSpeedKmh),
                    avgSpeed = Fmt.avg(trip.avgSpeedKmh),
                    duration = Fmt.duration(trip.elapsedMs),
                )
            }

            // ارتفاع الصفّ محجوزٌ دائمًا وإن غابت الأزرار: إخفاؤها قبل اللقطة كان
            // ينكمش بالعمود فيقفز الشريط السفليّ إلى أسفل، فتخرج الصورة بتخطيطٍ
            // غير الذي رآه المصوِّر — وغير الذي يُحرق في الفيديو.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ACTION_ROW_HEIGHT)
            ) {
                // الأزرار وحدها تختفي، لا الطبقة: اللقطة يجب أن تحمل العدّاد
                // والإحصاءات — وهي وعد «صورةٍ محروقة» — وليس مشهدًا عاريًا
                // تلاشٍ بالشفافيّة لا إظهارٌ متحرّك: لتلك تحميلاتٌ زائدة على
                // `ColumnScope` و`RowScope` والعامّ، واختيارُ المصرّف بينها داخل
                // `Box` متداخلٍ في `Column` هشّ. الشفافيّة أثرٌ واحد بلا نطاق.
                val controlsAlpha by animateFloatAsState(
                    targetValue = if (hideControls) 0f else 1f,
                    animationSpec = tween(durationMillis = 160),
                    label = "controlsAlpha",
                )
                if (controlsAlpha > 0.01f) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = controlsAlpha },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // أثلاثٌ متساوية: زرّ التسجيل في منتصف الشاشة بالبناء لا
                        // بفراغاتٍ محسوبة يدويًّا كما كان
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ShutterButton(onClick = { captureTick++ })
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            // البدء والإنهاء وحدهما يمرّان بالتأكيد: الأوّل يفتح ملفًّا
                            // ورحلةً بلا قصد، والثاني يُنهي تصويرًا لا يُستعاد. أمّا
                            // الإيقاف المؤقّت والاستئناف واللقطة فتُصحَّح كلّها بلمسةٍ
                            // ثانية، وتأكيدٌ عليها ضريبةٌ على الفعل الصحيح.
                            RecordButton(
                                isRecording = isRecording,
                                onClick = {
                                    if (confirmRecording) {
                                        confirmRecord = true
                                    } else {
                                        vm.toggleRecording()
                                    }
                                },
                            )
                        }
                        // الخانة الثالثة كانت فراغًا محجوزًا لتوسيط زرّ التسجيل؛
                        // صارت تحمل زرّ الإيقاف المؤقّت حين يكون له معنى وحده،
                        // فلا يتزحزح زرّ التسجيل عن منتصف الشاشة بظهوره أو غيابه
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (isRecording) {
                                PauseButton(paused = isPaused, onClick = vm::toggleVideoPause)
                            }
                        }
                    }
                }
            }
        }

        // ===== لوحة وضع التصوير =====
        //
        // تُعلن آخرَ أبناء الصندوق فتقع فوق الجميع في ترتيب الرسم والإصابة. ولا
        // تُستعمل هنا `ModalBottomSheet`: هي واجهة تجريبيّة في Material 3 تحتاج
        // إقرارًا صريحًا، ولا تُقدّم على لوحةٍ بسيطةٍ فوق المعاينة شيئًا يستحقّه.
        if (scenePickerOpen) {
            // حجابٌ يبتلع اللمس خارج اللوحة ويُغلقها. بدونه كانت اللمسة تنفذ إلى
            // طبقة اللمس تحتها فتُظهر شريط التبويبات واللوحة مفتوحة فوقه
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { scenePickerOpen = false } }
            )
            ScenePicker(
                dim = dim,
                selected = scene,
                modifier = Modifier.align(Alignment.Center),
                onPick = { picked ->
                    vm.setCameraScene(picked)
                    scenePickerOpen = false
                },
            )
        }
    }

    // ===== تأكيد التسجيل =====
    //
    // خارج `BoxWithConstraints`: `AlertDialog` نافذةٌ مستقلّة لا ابنٌ في التخطيط،
    // فوضعها داخل الصندوق يوهم بترتيبٍ في الرسم لا وجود له.
    //
    // والسؤال يُصاغ من [isRecording] لحظةَ العرض لا من رايةٍ تُحفظ عند الضغط: لو
    // انتهى التسجيل من تلقاء نفسه — قرصٌ امتلأ أو خطأٌ في الترميز — والمربّع مفتوح،
    // لسأل «إنهاء التسجيل؟» عن تسجيلٍ منتهٍ ثمّ بدأ واحدًا جديدًا بضغطة «أنهِ».
    if (confirmRecord) {
        val stopping = isRecording
        AlertDialog(
            onDismissRequest = { confirmRecord = false },
            title = {
                Text(
                    stringResource(
                        if (stopping) {
                            R.string.confirm_record_stop_title
                        } else {
                            R.string.confirm_record_start_title
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (stopping) {
                            R.string.confirm_record_stop_body
                        } else {
                            R.string.confirm_record_start_body
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRecord = false
                        vm.toggleRecording()
                    }
                ) {
                    Text(
                        stringResource(
                            if (stopping) R.string.confirm_yes_stop else R.string.confirm_yes_start
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRecord = false }) {
                    Text(stringResource(R.string.confirm_no))
                }
            },
        )
    }
}

private fun captureWindow(view: android.view.View, onResult: (Bitmap?) -> Unit) {
    val window = (view.context as? Activity)?.window
    if (window == null || view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val rect = Rect(
        location[0],
        location[1],
        location[0] + view.width,
        location[1] + view.height,
    )
    runCatching {
        PixelCopy.request(
            window,
            rect,
            bitmap,
            { result -> onResult(if (result == PixelCopy.SUCCESS) bitmap else null) },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { onResult(null) }
}

// ===== الشريط السفليّ =====

/**
 * خليّة الإحصاءات: أسطرٌ فوق لوحٍ داكن، محاذاةً إلى بداية السطر — وهي اليمين
 * في العربيّة، كما يرسمها الراسم المحروق تمامًا.
 *
 * كلّ سطرٍ نصّان لا نصٌّ واحد: الرقم مقطع LTR داخل جملة RTL، وفصلُه في `Text`
 * مستقلّ يمنع محرّك الاتّجاه ثنائيّ الجهة من قلب `00:00:03` إلى `30:00:00`.
 *
 * وترتيب الأسطر هو ترتيب `LABELS` في الراسم المحروق حرفًا بحرف: أيّ اختلافٍ بينهما
 * يعني لوحين مختلفين في مشهدٍ واحد.
 */
@Composable
private fun StatsPanel(
    dim: HudDim,
    maxWidth: Dp,
    distance: String,
    maxSpeed: String,
    avgSpeed: String,
    duration: String,
) {
    // اتّجاهٌ مصرَّحٌ به لا موروث: الراسم المحروق يرسم الأسطر بالعربيّة ومن اليمين
    // أبدًا (`textLocale = ar` واتّجاه فقرةٍ RTL مثبَّت)، فلو فُتح التطبيق على
    // هاتفٍ لغته لاتينيّة لانقلب ترتيب «التسمية ثمّ القيمة» على الشاشة وحدها
    // وخالف الملفّ. والأرقام تبقى مقاطع LTR داخله يتولّاها محرّك النصّ.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(PANEL, RoundedCornerShape(dim.corner))
                .padding(horizontal = dim.panelPadH, vertical = dim.panelPadV),
            verticalArrangement = Arrangement.spacedBy(dim.lineGap),
        ) {
            OverlayStat(dim, stringResource(R.string.stat_distance), distance, stringResource(R.string.unit_km))
            OverlayStat(dim, stringResource(R.string.stat_max_speed), maxSpeed, stringResource(R.string.unit_kmh))
            // التسمية القصيرة لا `stat_avg_speed`: اللوح مقيَّدٌ بعرضٍ أقصى، و«متوسّط
            // السرعة» تدفع السطر إلى القصّ على الشاشات الضيّقة
            OverlayStat(dim, stringResource(R.string.stat_avg_speed_short), avgSpeed, stringResource(R.string.unit_kmh))
            OverlayStat(dim, stringResource(R.string.stat_duration), duration, "")
        }
    }
}

/**
 * سطرُ إحصاءةٍ واحد: تسميةٌ خافتة صغيرة ثمّ قيمةٌ بيضاءُ عريضة.
 *
 * ارتفاع الصفّ مثبَّتٌ على صندوق السطر لا متروكٌ لجدول الطباعة: الراسم المحروق
 * يتقدّم بالمقدار نفسه من العقد، وأيّ فرقٍ هنا يتراكم ثلاث مرّات فيختلف ارتفاع
 * اللوح بين الشاشة والملفّ.
 *
 * والنصّان في الصفّ لا نصٌّ واحد: الرقم مقطع LTR داخل جملة RTL، وفصلُه يمنع محرّك
 * الاتّجاه ثنائيّ الجهة من قلب `00:00:03` إلى `30:00:00`. الراسم المحروق يبلغ
 * الغاية نفسها بمقاطع `Span` داخل فقرةٍ واحدة لأنّ `StaticLayout` لا يقبل غيرها.
 */
@Composable
private fun OverlayStat(dim: HudDim, label: String, value: String, unit: String) {
    Row(
        modifier = Modifier.height(dim.statLineBox),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dim.statGap),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = HudMetrics.LABEL_ALPHA),
                fontSize = dim.statLabel,
                lineHeight = dim.statLabelBox,
                letterSpacing = NO_TRACKING,
            ),
        )
        Text(
            text = if (unit.isEmpty()) value else "$value $unit",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dim.statValue,
                lineHeight = dim.statValueBox,
                letterSpacing = NO_TRACKING,
            ),
        )
    }
}

/**
 * خليّة القرص.
 *
 * لا تستعمل `CompactGauge`: تلك تقرأ ألوان اللوحة الحيّة، فتصير في السمة الفاتحة
 * `#00796B` بينما يحرق الملفّ `#00E5C7` أبدًا — فيختلف ما رآه المصوِّر عمّا حُفظ.
 * وهنا نملك كذلك سماكة القوس ونسبها، فتطابق ثوابت الراسم بلا وسيط.
 */
@Composable
private fun SpeedRing(dim: HudDim, speedKmh: Float, maxKmh: Int, warnKmh: Int, limitKmh: Int) {
    val target = (speedKmh / maxKmh).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 130),
        label = "hudRingSweep",
    )
    // عتبات المواصفة نفسها التي يستعملها الراسم المحروق، من العقد لا من نسخةٍ ثانية
    val activeColor = when (SpeedScale.zoneOf(speedKmh, maxKmh, warnKmh, limitKmh)) {
        SpeedZone.DANGER -> HUD_DANGER
        SpeedZone.WARN -> HUD_WARN
        SpeedZone.NORMAL -> HUD_ACCENT
    }
    // سالبٌ يعني «لا علامة». يُحسب هنا لا داخل `DrawScope` كي يبقى الرسم بلا شرط
    val limitFraction = if (limitKmh in 1..maxKmh) limitKmh.toFloat() / maxKmh else -1f
    Box(
        // القطر خارجيّ: ضلع الخليّة هو حدّ القوس الخارجيّ، وهو تعريف العقد نفسه
        modifier = Modifier.size(dim.ringDiameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * HudMetrics.RING_STROKE_OF_DIAMETER
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            drawArc(
                color = RING_TRACK,
                startAngle = HudMetrics.ARC_START,
                sweepAngle = HudMetrics.ARC_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0.001f) {
                drawArc(
                    color = activeColor,
                    startAngle = HudMetrics.ARC_START,
                    sweepAngle = HudMetrics.ARC_SWEEP * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            // علامة الحدّ فوق القوس الحيّ: تحته كان يغطّيها عند التجاوز، وهو
            // بالضبط الحال الذي تُقرأ فيه. والنسب من العقد فتطابق ما يحرقه الراسم
            if (limitFraction >= 0f) {
                val angleRad = Math.toRadians(
                    (HudMetrics.ARC_START + HudMetrics.ARC_SWEEP * limitFraction).toDouble()
                )
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()
                val half = stroke * HudMetrics.LIMIT_MARK_LENGTH_OF_STROKE / 2f
                drawLine(
                    color = HUD_DANGER,
                    start = Offset(
                        center.x + cosA * (radius - half),
                        center.y + sinA * (radius - half),
                    ),
                    end = Offset(
                        center.x + cosA * (radius + half),
                        center.y + sinA * (radius + half),
                    ),
                    strokeWidth = stroke * HudMetrics.LIMIT_MARK_WIDTH_OF_STROKE,
                )
            }
        }
        // الفجوة بين الرقم ووحدته مصرَّحٌ بها لا متروكةٌ لصناديق جدول الطباعة:
        // الراسم المحروق يوسّط الكتلة نفسها (رقم + فجوة + وحدة) على مركز القوس،
        // فلزم أن يعرف الطرفان الفجوة بالرقم نفسه
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dim.gaugeStackGap),
        ) {
            Text(
                text = Fmt.speed(speedKmh),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = dim.gaugeValue,
                    lineHeight = dim.gaugeValueBox,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    shadow = dim.valueShadow,
                ),
            )
            Text(
                text = stringResource(R.string.unit_kmh),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = dim.gaugeUnit,
                    lineHeight = dim.gaugeUnitBox,
                    letterSpacing = NO_TRACKING,
                    color = Color.White,
                    shadow = dim.unitShadow,
                ),
            )
        }
    }
}

// ===== الأزرار (لا تُحرق) =====

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(RECORD_SIZE)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (isRecording) 30.dp else 56.dp)
                .background(HUD_DANGER, if (isRecording) RoundedCornerShape(7.dp) else CircleShape)
        )
    }
}

/**
 * إيقاف الفيديو مؤقّتًا واستئنافه.
 *
 * **مداه الفيديو وحده**: الرحلة تمضي — المسار والمسافة والزمن — فمن يقف عند إشارةٍ
 * يوفّر مساحة القرص ولا يُفسد إحصاءات رحلته. ولذلك لا يشبه زرَّ التسجيل الأحمر
 * شكلًا: هو أصغر منه وأخفت، كي لا يُقرأ «إيقافًا» للركوبة كلّها.
 *
 * لا يظهر إلّا والتسجيل جارٍ — لا معنى لإيقاف ما لم يبدأ — ومقاسه [TOUCH_MIN]
 * بتمامه، فمساحة لمسه هي القاعدة السادسة نفسها لا أقلّ.
 */
@Composable
private fun PauseButton(paused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TOUCH_MIN)
            .background(PANEL, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(
                if (paused) R.string.camera_resume else R.string.camera_pause
            ),
            // لون التحذير حين يكون موقوفًا: الحال شاذّة ويجب أن تُرى، وهو اللون
            // نفسه الذي تحمله شارة «موقوف» أعلى الشاشة فيقرأ الاثنين خبرًا واحدًا
            tint = if (paused) HUD_WARN else Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * زرّ العمود الجانبيّ: دائرةٌ داكنة بمساحة لمسٍ كاملة.
 *
 * `contentDescription` هو النصّ نفسه لا `null`: هذه أزرارٌ بلا كلمات، وقارئ الشاشة
 * لا يملك عنها خبرًا سواه.
 */
@Composable
private fun SideButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TOUCH_MIN)
            .background(PANEL, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * الكشّاف.
 *
 * @param screenFlash الإضاءة ستكون بالشاشة لا بمصباح. يُلوَّن حينها بلون التحذير:
 *   الحال ليست عطبًا، لكنّها ليست ما يتوقّعه من ضغط «كشّافًا» — ولها ثمنٌ في عين
 *   من تقابله الكاميرا. اللون وحده يقول ذلك بلا كلمةٍ تحجب المشهد.
 */
@Composable
private fun TorchButton(on: Boolean, screenFlash: Boolean, onClick: () -> Unit) {
    SideButton(
        icon = if (on) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
        label = stringResource(
            if (on) R.string.camera_torch_on else R.string.camera_torch_off
        ),
        tint = when {
            on && screenFlash -> HUD_WARN
            on -> HUD_ACCENT
            else -> Color.White.copy(alpha = 0.85f)
        },
        onClick = onClick,
    )
}

/** سطرُ خبرٍ في العمود الجانبيّ: عرضٌ للحالة لا لمس فيه، فلا تسري عليه قاعدة اللمس */
@Composable
private fun SideNote(dim: HudDim, text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Text(
            text = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = SIDE_NOTE_WIDTH)
                .background(PANEL, RoundedCornerShape(dim.corner))
                .padding(horizontal = CHIP_PAD_H, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = HudMetrics.LABEL_ALPHA),
            ),
        )
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SHUTTER_SIZE)
            .background(PANEL, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(R.string.action_screenshot),
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

// ===== حبّات الشريط العلويّ =====

/**
 * الحبّة الواحدة: ارتفاعٌ ونصف قطرٌ وحشوٌ واحد للجميع.
 *
 * الشكوى كانت أربع حبّاتٍ بأحجامٍ مختلفة في صفٍّ واحد؛ وسببها أنّ كلًّا منها كانت
 * تحسب حشوها بنفسها. الشكل هنا واحد، والاختلاف في المحتوى وحده.
 *
 * الاتّجاه مثبَّتٌ داخلها كما هو مثبَّتٌ في لوح الإحصاءات: ترتيب أبنائها كان
 * منطقيًّا (`Arrangement.spacedBy`) بينما كلّ ما حولها مطلق، فكانت الأيقونة والنقطة
 * تقفزان إلى الجهة الأخرى على جهازٍ لغته لاتينيّة بينما يبقى موضع الحبّة نفسها
 * كما هو — فيخرج شريطٌ نصفه مرآةٌ لنصفه. التطبيق عربيّ ونصوص الحبّات عربيّة،
 * فالجهة المقرَّرة هي جهة العربيّة أيًّا كان إعداد الجهاز.
 */
@Composable
private fun HudChip(
    dim: HudDim,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = modifier
                .height(CHIP_HEIGHT)
                .background(PANEL, RoundedCornerShape(dim.corner))
                .padding(horizontal = CHIP_PAD_H),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * عدّاد الأقمار في أقصى اليسار.
 *
 * عرضٌ للحالة لا لمس فيه، فلا تسري عليه قاعدة مساحة اللمس. واللون وحده يحمل جودة
 * التثبيت كي يلمحها الراكب قبل أن يقرأ العدد.
 */
@Composable
private fun SatellitesChip(dim: HudDim, info: GnssInfo) {
    val color = when (info.quality) {
        FixQuality.NONE -> HUD_DANGER
        FixQuality.POOR, FixQuality.FAIR -> HUD_WARN
        FixQuality.GOOD -> HUD_ACCENT
    }
    HudChip(dim) {
        Icon(
            imageVector = Icons.Filled.SatelliteAlt,
            contentDescription = stringResource(
                R.string.gps_satellites,
                info.satellitesUsed,
                info.satellitesVisible,
            ),
            tint = color,
            modifier = Modifier.size(CHIP_ICON),
        )
        Text(
            // القاعدة الرابعة: الأرقام بـ Locale.US كي تخرج 0-9 لا ١-٩
            text = "${Fmt.count(info.satellitesUsed)}/${Fmt.count(info.satellitesVisible)}",
            style = MaterialTheme.typography.labelMedium.copy(color = Color.White),
        )
    }
}

/**
 * حبّة «محروق / نظيف».
 *
 * @param locked يُعطَّل اللمس أثناء التسجيل: تبديل الحرق يعيد بناء `UseCaseGroup`،
 *   وإعادة الربط تقتل التسجيل الجاري. نُسقط النقر بدل إظهار خطأ، ونُبهت المحتوى
 *   كي يعرف المستعمل أنّ الحبّة ليست مغلقةً عبثًا.
 *
 * الحبّة المرئيّة نحيلة عمدًا كي لا تحجب المشهد، وارتفاعها 36dp — دون حدّ القاعدة
 * السادسة (56dp)، والإصبع على طريقٍ مهتزّ لا يصيبها. الحلّ توسيع منطقة اللمس وحدها:
 * صندوقٌ خارجيّ يحمل النقر ويبلغ 56dp، والطلاء يبقى على الشبكة كما هو.
 *
 * @param compact على الشاشات الضيّقة تسقط الكلمة وتبقى الأيقونة: حالتا «محروق»
 *   و«نظيف» تفترقان باللون (فيروزيّ / أبيضُ خافت)، ووصفُ المحتوى باقٍ لقارئ
 *   الشاشة. ومساحة اللمس لا تنقص لأنّ الصندوق الخارجيّ يبقى ‎56dp‎.
 */
@Composable
private fun BurnChip(
    dim: HudDim,
    compact: Boolean,
    enabled: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
) {
    val tint = when {
        locked -> (if (enabled) HUD_ACCENT else Color.White).copy(alpha = 0.45f)
        enabled -> HUD_ACCENT
        else -> Color.White.copy(alpha = 0.62f)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = TOUCH_MIN, minHeight = TOUCH_MIN)
            // القصّ قبل النقر ليبقى أثر اللمس داخل شكل الشبكة لا مستطيلٍ فجّ
            .clip(RoundedCornerShape(dim.corner))
            .then(if (locked) Modifier else Modifier.clickable(onClick = onToggle)),
        contentAlignment = Alignment.Center,
    ) {
        HudChip(dim) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.burn_overlay),
                tint = tint,
                modifier = Modifier.size(CHIP_ICON),
            )
            if (!compact) {
                Text(
                    text = stringResource(if (enabled) R.string.burn_on else R.string.burn_off),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium.copy(color = tint),
                )
            }
        }
    }
}

/**
 * مؤشّر التقسيم بجوار شارة التسجيل.
 *
 * غرضه أن يعلم الراكب أنّ الملفّ سيُلَفّ إلى ملفٍّ تالٍ عند بلوغ هذا الطول، فلا
 * يظنّ انقطاعَ التصوير عطبًا. عرضٌ للحالة فقط، لا لمس فيه.
 */
@Composable
private fun SegmentChip(dim: HudDim, minutes: Int) {
    HudChip(dim) {
        Text(
            // القاعدة الرابعة: الأرقام بـ Locale.US
            text = stringResource(R.string.segment_minutes, Fmt.count(minutes)),
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = 0.82f),
            ),
        )
    }
}

/**
 * @param compact النقطة الحمراء وحدها على الشاشات الضيّقة: كانت كلمة REC هي أوّل
 *   ما يُقصّ حين تضيق الأربع حبّات عن الصفّ، والقصّ أسوأ من الحذف لأنّه يوهم
 *   بعطبٍ في الرسم. والنقطة الحمراء وحدها كافية: هي علامة التسجيل المتعارفة،
 *   ويؤكّدها زرُّ التسجيل نفسه أسفل الشاشة وقد صار مربّعًا.
 */
@Composable
private fun RecordingChip(dim: HudDim, compact: Boolean) {
    HudChip(dim) {
        Box(
            Modifier
                .size(10.dp)
                .background(HUD_DANGER, CircleShape)
        )
        if (!compact) {
            Text(
                text = "REC",
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/**
 * شارة «موقوف» — تحلّ محلّ شارة REC ما دام الفيديو موقوفًا.
 *
 * لونها التحذير (`Warn`) لا الخطر: الحال ليست عطبًا بل اختيارًا للراكب، لكنّها
 * حالٌ **لا يُسجَّل فيها شيء**، ونسيانُها هو العطب الحقيقيّ — أن يظنّ المصوِّر أنّه
 * يصوّر منذ نصف ساعة والملفّ واقفٌ عند الإشارة الأولى.
 *
 * **ولا تُحرق في الملفّ**، ولا معنى لحرقها: لا إطارات تُكتب أصلًا وهي موقوفة،
 * فالشارة التي تعلن التوقّف لا يمكن أن تظهر في تسجيلٍ متوقّف. وهذا لا يخرق «المرسوم
 * هو المحروق»: تلك المواصفة تحكم القرص والإحصاءات، وشارات الشريط العلويّ (REC
 * والأقمار والحرق والتقسيم) خارج المحروق أصلًا كما هي الأزرار.
 */
@Composable
private fun PausedChip(dim: HudDim, compact: Boolean) {
    HudChip(dim) {
        Box(
            Modifier
                .size(10.dp)
                .background(HUD_WARN, CircleShape)
        )
        if (!compact) {
            Text(
                text = stringResource(R.string.camera_paused_badge),
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = HUD_WARN,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// ===== وضع التصوير =====

/** أيقونة الوضع: شمسٌ للنهار وهلالٌ لليل و«تلقائيّ» للتابع للساعة */
private fun sceneIcon(scene: CameraScene): ImageVector = when (scene) {
    CameraScene.AUTO -> Icons.Filled.BrightnessAuto
    CameraScene.DAY -> Icons.Filled.WbSunny
    CameraScene.NIGHT -> Icons.Filled.Bedtime
}

private fun sceneLabel(scene: CameraScene): Int = when (scene) {
    CameraScene.AUTO -> R.string.camera_scene_auto
    CameraScene.DAY -> R.string.camera_scene_day
    CameraScene.NIGHT -> R.string.camera_scene_night
}

/**
 * حبّة وضع التصوير: تعرض الوضع الجاري وتفتح لوحة اختياره بلمسة.
 *
 * الاختيار من شاشة الكاميرا لا من الإعدادات وحدها، لأنّ الحاجة إليه تقع والراكب
 * على الطريق: يدخل نفقًا أو يخرج من مدينةٍ مضاءة، فلا يُعقل أن يترك الشاشة ويغوص
 * في قائمة. والتبديل لا يقطع تسجيلًا جاريًا — تعويض الإضاءة يُضبط على
 * `CameraControl` بلا إعادة ربط — فلا يُقفَل الزرّ أثناء التصوير كما يُقفَل الحرق.
 */
@Composable
private fun SceneChip(dim: HudDim, compact: Boolean, scene: CameraScene, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = TOUCH_MIN, minHeight = TOUCH_MIN)
            .clip(RoundedCornerShape(dim.corner))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HudChip(dim) {
            Icon(
                imageVector = sceneIcon(scene),
                contentDescription = stringResource(R.string.camera_scene_title),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(CHIP_ICON),
            )
            if (!compact) {
                Text(
                    text = stringResource(sceneLabel(scene)),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color.White.copy(alpha = 0.85f),
                    ),
                )
            }
        }
    }
}

/**
 * لوحة اختيار الوضع: عنوانٌ وثلاثة خيارات وملاحظة.
 *
 * الملاحظة (`camera_scene_note`) ليست زينة: «تلقائيّ» يتبع ساعتَي النهار والليل
 * المضبوطتين للمظهر، ومن لم يعرف ذلك سيظنّ أنّ الوضع يقرأ الضوء بالحسّاس فيستغرب
 * أن يبقى «نهارًا» في نفقٍ مظلم.
 */
@Composable
private fun ScenePicker(
    dim: HudDim,
    selected: CameraScene,
    modifier: Modifier = Modifier,
    onPick: (CameraScene) -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .widthIn(max = PICKER_MAX_WIDTH)
                .background(PANEL, RoundedCornerShape(dim.corner))
                .padding(horizontal = dim.panelPadH, vertical = dim.panelPadV),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.camera_scene_title),
                style = MaterialTheme.typography.titleSmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            // ترتيب التعداد نفسه: تلقائيّ ثمّ نهار ثمّ ليل، كما في الإعدادات
            for (option in CameraScene.entries) {
                SceneOption(
                    scene = option,
                    selected = option == selected,
                    onClick = { onPick(option) },
                )
            }
            Text(
                text = stringResource(R.string.camera_scene_note),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White.copy(alpha = HudMetrics.LABEL_ALPHA),
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** خيارٌ واحد: أيقونةٌ فتسمية، وعلامة صحٍّ للمختار — ومساحة لمسٍ لا تقلّ عن القاعدة */
@Composable
private fun SceneOption(scene: CameraScene, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) HUD_ACCENT else Color.White.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TOUCH_MIN)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = sceneIcon(scene),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(CHIP_ICON),
        )
        Text(
            text = stringResource(sceneLabel(scene)),
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall.copy(color = tint),
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = HUD_ACCENT,
                modifier = Modifier.size(CHIP_ICON),
            )
        }
    }
}

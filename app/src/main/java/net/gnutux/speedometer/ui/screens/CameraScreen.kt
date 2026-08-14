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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.location.FixQuality
import net.gnutux.speedometer.core.location.GnssInfo
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel

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
 * دون هذا العرض لا يتّسع الشريط العلويّ لحبّاته الأربع بنصوصها، فكانت شارة REC
 * تُقصّ. أقصى عرضٍ تطلبه الحبّات مجتمعةً نحو ‎350dp‎ مع الهوامش، فجعلنا الحدّ عند
 * ‎360dp‎: ما دونه تسقط النصوص الثانويّة وتبقى الأيقونات.
 */
private val COMPACT_WIDTH = 360.dp

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
    val profile by vm.profile.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val cameraMessage by vm.cameraMessage.collectAsStateWithLifecycle()
    val burnOverlay by vm.burnOverlay.collectAsStateWithLifecycle()
    val segmentMinutes by vm.camera.segmentMinutes.collectAsStateWithLifecycle()

    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var captureTick by remember { mutableIntStateOf(0) }
    var hideControls by remember { mutableStateOf(false) }

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

    LaunchedEffect(captureTick) {
        if (captureTick == 0) return@LaunchedEffect
        hideControls = true
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
                BurnChip(
                    dim = dim,
                    compact = compact,
                    enabled = burnOverlay,
                    locked = isRecording,
                    onToggle = { vm.setBurnOverlay(!burnOverlay) },
                )
                if (segmentMinutes > AppSettings.SEGMENT_CONTINUOUS) SegmentChip(dim, segmentMinutes)
                if (isRecording) RecordingChip(dim, compact)
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
                    maxKmh = profile.gaugeMaxKmh,
                    warnKmh = profile.defaultWarnKmh,
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
                            RecordButton(isRecording = isRecording, onClick = vm::toggleRecording)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
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
private fun SpeedRing(dim: HudDim, speedKmh: Float, maxKmh: Int, warnKmh: Int) {
    val target = (speedKmh / maxKmh).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 130),
        label = "hudRingSweep",
    )
    // عتبات المواصفة نفسها التي يستعملها الراسم المحروق، من العقد لا من نسخةٍ ثانية
    val activeColor = when {
        speedKmh >= maxKmh * HudMetrics.DANGER_OF_MAX -> HUD_DANGER
        speedKmh >= warnKmh -> HUD_WARN
        else -> HUD_ACCENT
    }
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

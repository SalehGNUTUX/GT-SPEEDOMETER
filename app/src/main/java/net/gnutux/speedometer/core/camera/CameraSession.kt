package net.gnutux.speedometer.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.view.WindowManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.LayoutSettings
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.media.MediaRepository
import net.gnutux.speedometer.core.DeviceTier
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.settings.CameraLens
import net.gnutux.speedometer.core.settings.CameraScene
import net.gnutux.speedometer.core.settings.DualLayout
import net.gnutux.speedometer.ui.theme.computeNight
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * جلسة الكاميرا. تعيش خارج التركيب (Composition) عمدًا.
 *
 * كانت النسخة الأولى تُنشئ حالات الاستعمال داخل الشاشة وتستدعي unbindAll كلّما
 * عاد المستخدم إلى تبويب الكاميرا — فكان الانتقال بين التبويبات أثناء التسجيل
 * يُجهض التسجيل الجاري. هنا حالات الاستعمال تُنشأ مرّة واحدة، ولا نفكّ الارتباط
 * أبدًا ما دام التسجيل جاريًا.
 *
 * حرقُ طبقة العدّاد داخل ملفّ الفيديو اختياريّ ويُنفَّذ عبر `OverlayEffect` موجَّه إلى
 * `VIDEO_CAPTURE` وحده، فتبقى المعاينة على الشاشة نظيفة وتُرسم طبقتُها بـ Compose.
 * وإن رفض الجهاز التأثير، نرتدّ إلى ربطٍ نظيفٍ ونُبلّغ بدل أن نترك الكاميرا معطّلة.
 *
 * جديد في 0.4.0:
 * - التفضيلات كلّها من [AppSettings]، لا `SharedPreferences` خاصّة بالكاميرا. مصدر
 *   حقيقة واحد يمنع أن تتناقض شاشة الإعدادات مع التوگل الذي في الشريط العلويّ.
 * - **تقسيم اختياريّ للفيديو**: سقف الطول يُملى على المسجّل نفسه، وعند بلوغه يُنهي
 *   المسجّل الملفّ سليمًا ثم نبدأ التالي فورًا داخل الجلسة نفسها.
 * - **تصنيف أسباب الإنهاء**: ليس كلّ رمز خطأ فشلًا. أكثرها شيوعًا `SOURCE_INACTIVE`،
 *   وهو يعني أنّ الملفّ حُفظ سليمًا وأنّ المصدر هو ما توقّف. إظهاره كفشلٍ كان يدفع
 *   المستخدم إلى إعادة تسجيل رحلةٍ محفوظة أصلًا.
 * - **دورة حياةٍ مملوكة**: الجلسة لا تُربط بدورة حياة النشاط بعد اليوم، بل بسجلٍّ
 *   ([LifecycleRegistry]) تملكه هي وتقوده بنفسها. علّةُ ذلك أنّ فكّ الارتباط عند
 *   هبوط المضيف دون STARTED يقع **فوق** [detach]، فلا يملك [detach] ردَّه: يكفي
 *   أن يضغط الراكب زرّ الشاشة الرئيسة أو تصله مكالمة حتى يُعطَّل مصدر الصورة
 *   ويُنهى التسجيل بـ `SOURCE_INACTIVE`. الجلسة الآن تتبع المضيف في الأحوال
 *   العاديّة، وتُثبّت سجلّها عند RESUMED ما دام تسجيلٌ قائمًا، ثمّ تُحرّر العدسة
 *   حقًّا حين ينتهي التسجيل ولا شاشة تنتظرها.
 *
 * جديد في 0.8.0:
 * - **إيقافٌ مؤقّت للفيديو وحده** ([pause]/[resume]). مداه مقرَّر: الملفّ يتوقّف
 *   والرحلة تمضي. من يقف عند إشارةٍ يريد توفير القرص لا إفساد إحصاءاته، وملفّ
 *   المسار يبقى متّصلًا لأنّه لم يُمسّ أصلًا.
 * - **وضع تصويرٍ ليليّ/نهاريّ** عبر تعويض الإضاءة على `CameraControl`. اختير هذا
 *   الطريق لأنّه **لا يستلزم إعادة ربط**، فتبديل الوضع لا يقتل تسجيلًا جاريًا —
 *   بخلاف الحرق الذي يُعيد بناء `UseCaseGroup` ولذلك يُقفل أثناء التصوير.
 *
 * جديد في 0.9.0:
 * - **خطّة ربطٍ واحدة** ([BindPlan]) تجمع كلّ ما يستلزم إعادة الربط: الحرق والعدسة
 *   والازدواج وتخطيطه وأيّهما الكبيرة. كانت راية `boundWithBurn` وحدها تكفي حين لم
 *   يكن يتبدّل إلّا الحرق؛ ومع خمسة مدخلات تحتاج المقارنة إلى قيمةٍ واحدة تُقارَن
 *   بالمساواة، وإلّا نسي أحدُ المسارات مدخلًا فبقيت الكاميرا على حالٍ لم تعد مطلوبة.
 * - **لفُّ المقطع عند إعادة الربط**: تبديل العدسة (وكذلك مبادلة الكبيرة بالمصغَّرة
 *   في الوضع المزدوج) يستلزم `unbindAll` وربطًا جديدًا، وذلك يهدم سطح المسجّل. القرار
 *   المُقَرّ أن يُغلق المقطع الجاري سليمًا ثمّ يبدأ التالي بالعدسة الجديدة، بآليّة
 *   التقسيم نفسها. الرحلة ومسار GPX لا يُمسّان لأنّهما لا يعرفان بالمسجّل شيئًا.
 * - **الكاميرتان في ملفٍّ واحد** عبر مسار التركيب (composition) في CameraX: تيّاران
 *   يُمزجان في تيّارٍ واحد يذهب إلى `Preview` و`VideoCapture` معًا، لا معاينتان
 *   منفصلتان بملفّين. شروط ذلك المسار مشروحةٌ عند [bindConcurrent].
 * - **الكشّاف**، ووميضُ الشاشة بديلًا عنه على عدسةٍ بلا مصباح.
 */
class CameraSession(
    private val context: Context,
    private val media: MediaRepository,
    private val settings: AppSettings,
) {

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    /** هل يعمل التخفيف الآن؟ يُسأل عند بناء المسجّل لا في كلّ إطار */
    private fun liteActive(): Boolean =
        DeviceTier.liteActive(context, settings.liteMode.value)

    /**
     * الكاميرا التي أعادها آخر ربطٍ ناجح. منها وحدها يُقرأ `CameraInfo` ويُضبط
     * `CameraControl`، ولا معنى لأيّ منهما قبل الربط أو بعد التحرير.
     */
    private var boundCamera: Camera? = null

    private var overlayEffect: OverlayEffect? = null

    /**
     * كلّ ما يستلزم تبديلُه إعادةَ ربط، في قيمةٍ واحدة تُقارَن بالمساواة.
     *
     * التأثيرات لا تُضاف ولا تُنزع من ارتباطٍ قائم، ولا العدسة تُبدَّل، ولا تخطيط
     * التركيب يُعدَّل (فهو حقلٌ نهائيّ في `CameraUseCaseAdapter` يُمرَّر إلى مُركِّب
     * التيّارين عند بنائه). فما دام هذا الوصف مطابقًا لِما رُبط فعلًا، لا نمسّ الربط.
     */
    private data class BindPlan(
        val burn: Boolean,
        val lens: CameraLens,
        val dual: Boolean,
        val layout: DualLayout,
        val primary: CameraLens,
    ) {
        /**
         * الوصف لا الطلب: في الوضع المفرد لا يدخل التخطيط ولا «أيّهما الكبيرة» في
         * الربط بشيء، فتُحيَّد قيمتاهما. ولولا ذلك لظلّت خطّةٌ ارتدّت إلى المفرد
         * مخالفةً للمطلوب في حقلٍ لا أثر له، فيُعاد الربط عند كلّ فتحٍ للشاشة بلا نتيجة.
         */
        fun normalized(): BindPlan =
            if (dual) this else copy(layout = DualLayout.DEFAULT, primary = CameraLens.DEFAULT)
    }

    /**
     * الخطّة التي جرى الربط عليها فعلًا. `null` تعني «الربط لم يعد موثوقًا»: إمّا لم
     * يحدث بعد، أو حُرّرت العدسة.
     */
    private var boundPlan: BindPlan? = null

    /**
     * الكتابة في DataStore غير متزامنة، فقيم [AppSettings] تتأخّر دورةً عن ضغطة
     * المستخدم. نحتفظ بالمطلوب حتى يلحق به المخزن، وإلّا رُبط المشهد على القيمة
     * القديمة فبدا الزرّ كأنّه لا يفعل شيئًا — أو أسوأ: ارتدّت الخطّة إلى ما فشل
     * لتوّه فدارت إعادة الربط بلا نهاية.
     */
    private var pendingBurn: Boolean? = null
    private var pendingLens: CameraLens? = null
    private var pendingDual: Boolean? = null
    private var pendingPrimary: CameraLens? = null

    private val painter = VideoOverlayPainter()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    /**
     * الفيديو موقوفٌ مؤقّتًا والجلسة قائمة.
     *
     * لا يُخفض [isRecording] معه عمدًا: ذاك يعني «جلسة ترميزٍ قائمة» وعليه يُبنى
     * ظهور زرّ الإيقاف نفسه وحبّة الشريط العلويّ، فلو أطفأناه لاختفى الزرّ الذي
     * يُستأنف به. والفرق بين الحالين يقوله [isPaused] وحده.
     */
    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    /** الحرق مُفوَّض إلى التفضيلات؛ الجلسة لا تملك نسخةً ثانية منه */
    val burnOverlay: StateFlow<Boolean> = settings.burnOverlay

    /** طول المقطع بالدقائق كما هو مضبوط الآن؛ الشاشة تعرضه ليعلم الراكب أنّ الملفّ سيُلَفّ */
    val segmentMinutes: StateFlow<Int> = settings.videoSegmentMinutes

    /** وضع التصوير مُفوَّضٌ إلى التفضيلات كالحرق: مصدرُ حقيقةٍ واحد لا نسخةٌ ثانية */
    val cameraScene: StateFlow<CameraScene> = settings.cameraScene

    /** العدسة المختارة كما في التفضيلات؛ في الوضع المزدوج لا معنى لها */
    val cameraLens: StateFlow<CameraLens> = settings.cameraLens

    /** أيّ العدستين تملأ الإطار الآن في الوضع المزدوج */
    val dualPrimary: StateFlow<CameraLens> = settings.dualPrimary

    /** راية التفضيل لا الواقع؛ الواقع في [dualActive] */
    val dualRequested: StateFlow<Boolean> = settings.dualCamera

    /** وميض الشاشة مسموحٌ به في التفضيلات؛ لا أنّه مضاءٌ الآن */
    val screenFlashEnabled: StateFlow<Boolean> = settings.screenFlash

    // ===== الكشّاف =====

    /**
     * الكشّاف مضاءٌ الآن — سواءٌ أكان مصباحًا حقيقيًّا أم وميضَ شاشة.
     *
     * حالةٌ نملكها نحن لا نقرؤها من `CameraInfo.torchState`: الأخيرة `LiveData` تصل
     * متأخّرةً عن اللمسة، ولا وجود لها أصلًا في مسار وميض الشاشة. وهي **تُصفَّر
     * حتمًا** عند كلّ ارتباطٍ جديد وعند التحرير، لأنّ `CameraControl` الجديد لا يرث
     * كشّافَ سابقه — وحالةٌ باقيةٌ عندها كذبٌ صريح على المستعمل.
     */
    private val _torchOn = MutableStateFlow(false)
    val torchOn = _torchOn.asStateFlow()

    /** هل في الكاميرا المربوطة مصباح؟ يُسأل عند الربط لا عند كلّ لمسة */
    private val _hasTorch = MutableStateFlow(false)
    val hasTorch = _hasTorch.asStateFlow()

    /**
     * الشاشة تُضاء بيضاءَ بديلًا عن مصباحٍ لا تملكه العدسة.
     *
     * تقرؤها الشاشة (طبقةٌ بيضاء) والنشاط (رفع سطوع النافذة إلى ‎1f‎). فصلُها عن
     * [torchOn] مقصود: من يقرأ السطوع لا يعنيه المصباح العتاديّ في شيء.
     */
    private val _screenFlashOn = MutableStateFlow(false)
    val screenFlashOn = _screenFlashOn.asStateFlow()

    // ===== الكاميرتان معًا =====

    /**
     * هل يدعم الجهاز تشغيل الكاميرتين معًا (أماميّة وخلفيّة)؟
     *
     * شرطان مجتمعان: ميزةُ النظام، **و**زوجٌ فعليّ في
     * `getAvailableConcurrentCameraInfos` يجمع الجهتين. الأولى وحدها لا تكفي: جهازٌ
     * قد يعلن الميزة ثمّ لا يعرض إلّا أزواجًا من جهةٍ واحدة (عدستان خلفيّتان)، وذاك
     * ليس ما نطلبه. `false` قبل وصول المزوّد، لأنّ السؤال لا جواب له قبله.
     */
    private val _dualSupported = MutableStateFlow(false)
    val dualSupported = _dualSupported.asStateFlow()

    /** الكاميرتان مربوطتان فعلًا الآن. الشاشة تعرض زرّ المبادلة عليها لا على التفضيل */
    private val _dualActive = MutableStateFlow(false)
    val dualActive = _dualActive.asStateFlow()

    /** آخر رسالة للمستخدم: مصير التسجيل، لا رمزُ خطأٍ عارٍ */
    private val _message = MutableStateFlow<Message?>(null)
    val message = _message.asStateFlow()

    /**
     * مصير التسجيل من وجهة نظر المستخدم لا من وجهة نظر المُرمِّز.
     *
     * الفرق بين [Saved] و[Truncated] و[Failed] هو الفرق بين «ملفّك جاهز»، و«ملفّك
     * جاهز لكنّه أقصر ممّا أردت»، و«لا ملفّ». دمجُها كلّها في فشلٍ واحد كان يُخفي
     * تسجيلاتٍ سليمة عن صاحبها.
     */
    sealed interface Message {
        /** اكتمل التسجيل كما طُلب */
        data class Saved(val name: String) : Message

        /** حُفظ الملفّ لكنّه انقطع قبل الأوان */
        data class Truncated(val name: String) : Message

        /** اكتمل مقطع وبدأ التالي */
        data class Segment(val name: String) : Message

        /** لا ملفّ؛ [reason] موردُ نصٍّ لا رمزٌ رقميّ، فالرقم لا يعني للمستخدم شيئًا */
        data class Failed(@StringRes val reason: Int) : Message

        /**
         * خبرٌ عن الكاميرا لا عن الملفّ، يُعرض كما هو.
         *
         * [Failed] تُغلَّف بـ«تعذّر حفظ التسجيل: …» وهي جملةٌ كاذبة حين يكون الخبر
         * «تعذّر تبديل الكاميرا» أو «لا مصباح في هذه الكاميرا»: لا تسجيلَ ضاع.
         */
        data class Notice(@StringRes val text: Int) : Message

        /** الجهاز لا يدعم حرق الطبقة، وقد رُبطت الكاميرا نظيفة */
        data object BurnUnsupported : Message

        /**
         * الجهاز لا يقبل تعويض إضاءةٍ يدويًّا، فوضع التصوير لا أثر له عليه.
         * يُقال عند لمسة الاختيار وحدها لا عند كلّ إعادة تقييم: تكرارُه مع كلّ
         * دقيقةٍ في الوضع التلقائيّ إزعاجٌ بلا خبرٍ جديد.
         */
        data object SceneUnsupported : Message
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** تُنادى من محرّك الرحلة مع كلّ تحديث موقع؛ الراسم يلتقط آخر قيمة فقط */
    fun updateHud(snapshot: HudSnapshot) {
        painter.snapshot = snapshot
    }

    fun setBurnOverlay(enabled: Boolean) {
        // تغيير التأثير يستلزم إعادة الربط، وإعادة الربط أثناء التسجيل تقتله.
        // والعبرة بالجلسة لا بالترميز: ذيل الملفّ يُكتب بعد انطفاء [isRecording]
        //
        // **ولا يُلَفّ المقطع من أجله** كما يُلَفّ لتبديل العدسة: التبديل خدمةٌ يطلبها
        // الراكب وهو ينظر إلى الطريق فلا بديل عنها، والحرق إعدادُ إخراجٍ يُضبط قبل
        // الانطلاق — وشقُّ الملفّ نصفين ليحمل نصفُه عدّادًا ونصفُه لا شيء عبثٌ.
        if (_sessionHolding.value || _isRecording.value || requestedBurn() == enabled) return
        persistBurn(enabled)
        requestRebind()
    }

    /** القيمة المطلوبة الآن: ما طلبه المستخدم إن لم يلحق به القرص بعد، وإلّا المحفوظة */
    private fun requestedBurn(): Boolean {
        val stored = settings.burnOverlay.value
        if (pendingBurn == stored) pendingBurn = null
        return pendingBurn ?: stored
    }

    private fun persistBurn(enabled: Boolean) {
        // `pendingBurn` يسدّ فجوة الكتابة على القرص: الربط قد يقع قبل أن يعود
        // `settings.burnOverlay` بالقيمة الجديدة، فنقرأ المطلوب لا المحفوظ
        pendingBurn = enabled
        settings.setBurnOverlay(enabled)
    }

    private fun requestedLens(): CameraLens {
        val stored = settings.cameraLens.value
        if (pendingLens == stored) pendingLens = null
        return pendingLens ?: stored
    }

    private fun persistLens(lens: CameraLens) {
        pendingLens = lens
        settings.setCameraLens(lens)
    }

    private fun requestedDual(): Boolean {
        val stored = settings.dualCamera.value
        if (pendingDual == stored) pendingDual = null
        return pendingDual ?: stored
    }

    private fun persistDual(enabled: Boolean) {
        pendingDual = enabled
        settings.setDualCamera(enabled)
    }

    private fun requestedPrimary(): CameraLens {
        val stored = settings.dualPrimary.value
        if (pendingPrimary == stored) pendingPrimary = null
        return pendingPrimary ?: stored
    }

    private fun persistPrimary(lens: CameraLens) {
        pendingPrimary = lens
        settings.setDualPrimary(lens)
    }

    /** ما ينبغي أن يكون مربوطًا الآن، من التفضيلات وما لم يلحق بها القرص بعد */
    private fun desiredPlan(): BindPlan = BindPlan(
        burn = requestedBurn(),
        lens = requestedLens(),
        dual = requestedDual(),
        layout = settings.dualLayout.value,
        primary = requestedPrimary(),
    ).normalized()

    /**
     * جيل الربط. `ProcessCameraProvider.getInstance` غير متزامن، وقد يغادر المستعمل
     * تبويب الكاميرا قبل أن يصل المزوّد: عندها كان المستمع يربط الكاميرا بدورة حياة
     * لا شاشة لها، فتبقى العدسة مفتوحة وتُستنزف البطّاريّة ويُحجب الجهاز عن غيرنا.
     * كلّ [detach] يزيد الجيل، والمستمع المتأخّر ينسحب صامتًا.
     */
    private var bindGeneration = 0

    // ===== دورة الحياة المملوكة =====

    /**
     * مالك دورة حياةٍ لا شاشة له ولا نشاط: سجلٌّ عارٍ نقوده بأيدينا. هو الجواب
     * الوحيد عن «كيف تبقى الكاميرا مرتبطة والتطبيق في الخلفيّة»، إذ أنّ CameraX
     * يفكّ الارتباط بمجرّد هبوط المالك دون STARTED مهما فعلنا نحن.
     */
    private class SessionOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraOwner: SessionOwner? = null

    /** المضيف الحقيقيّ (النشاط) نتابعه ولا نرتبط به */
    private var hostOwner: LifecycleOwner? = null
    private var hostState: Lifecycle.State = Lifecycle.State.INITIALIZED

    /** هل ما تزال شاشةُ كاميرا في التركيب تطلب معاينة؟ */
    private var attached = false

    /**
     * إشارتان ضعيفتان لا قويّتان: الجلسة تعيش بعمر التطبيق، وإمساكُ `PreviewView`
     * بقوّةٍ يعني إمساكَ النشاط كلّه معها بعد زواله.
     */
    private var wantedPreview: WeakReference<PreviewView>? = null
    private var installedPreview: WeakReference<PreviewView>? = null

    /**
     * [LifecycleRegistry] محبوس على الخيط الرئيس: أيّ انتقالٍ من خيطٍ آخر يرمي.
     * منافذ الاستدعاء كلّها رئيسة اليوم (التركيب، مراقب المضيف، مُنفّذ الكاميرا
     * الرئيس)، وهذا الحارس يجعل الأمر عقدًا لا مصادفة.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private val hostObserver = LifecycleEventObserver { source, event ->
        hostState = source.lifecycle.currentState
        // نشاطٌ مهدوم = لا شاشة البتّة: نافذتُه زالت ومعها كلّ `PreviewView` فيها.
        // نعامله معاملة [detach] كي لا تبقى العدسة معلّقة إن سبق الهدمُ تفكيكَ
        // التركيب، ولئلّا نمسك بمضيفٍ ميّت فنُسرّبه
        if (event == Lifecycle.Event.ON_DESTROY) attached = false
        // المعاينة أوّلًا ثمّ الحالة: نزعُ السطح قبل الهبوط يمنع رسمةً في سطحٍ ميّت
        applySurface()
        if (event == Lifecycle.Event.ON_DESTROY) forgetHost()
        syncLifecycle()
    }

    /**
     * قلب الآلة. الحالة المطلوبة تُشتقّ من ثلاثة مدخلات لا غير:
     *
     * - **جلسة تسجيل قائمة** ⇒ RESUMED مهما فعل المضيف. هذا هو الوعد كلّه.
     * - **لا جلسة ولا شاشة** ⇒ DESTROYED، أي تحريرٌ فعليّ للعدسة. ولأنّ السجلّ
     *   المهدوم لا يُبعث، يُنشأ سواه عند الربط التالي.
     * - **لا جلسة وثمّة شاشة** ⇒ حالة المضيف نفسها، فيبقى سلوك «غادرتُ التبويب
     *   فأُطفئت الكاميرا» كما كان بلا استنزاف بطّاريّة.
     */
    private fun syncLifecycle() = onMain {
        val target = when {
            _sessionHolding.value -> Lifecycle.State.RESUMED
            !attached -> Lifecycle.State.DESTROYED
            else -> hostState
        }
        if (target == Lifecycle.State.DESTROYED) {
            releaseNow()
            return@onMain
        }
        // INITIALIZED ليست حالةً يقبلها السجلّ بعد إنشائه، وأدنى ما يُطلب CREATED
        cameraOwner?.registry?.currentState = target.coerceAtLeast(Lifecycle.State.CREATED)
    }


    /**
     * إبلاغ حالات الاستعمال باتّجاه الشاشة الحاليّ.
     *
     * `targetRotation` ليس تفصيلًا تجميليًّا: هو ما يُكتب في بيانات الملفّ فيقرؤه كلّ
     * مشغّل. ولا يتحدّث من تلقائه هنا لأنّ النشاط يعلن `configChanges` للاتّجاه
     * فيعالجه بنفسه بلا إعادة إنشاء — والقيمة الافتراضيّة تُلتقط لحظة البناء وتبقى.
     *
     * ولا يُستدعى أثناء تسجيلٍ جارٍ: تبديل الاتّجاه في منتصف مقطعٍ يُخرج ملفًّا نصفُه
     * بميلٍ ونصفُه بآخر. من بدأ التسجيل طولًا يُكمله طولًا، والاتّجاه الجديد لمقطعٍ جديد.
     */
    private fun applyRotation() {
        if (_sessionHolding.value) return
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }.getOrNull() ?: return
        preview?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /**
     * السطح يُركَّب حين يكون هناك ما يُرسم فيه فقط. حين يغيب المضيف تحت STARTED —
     * أو تغادر الشاشة أصلًا — يُنزع المزوّد ويبقى الارتباط: هذا ما يجعل التسجيل
     * يستمرّ بلا معاينةٍ في نافذةٍ لا تُرسم.
     *
     * والمقارنة قبل التركيب مقصودة: `setSurfaceProvider` بمزوّدٍ جديد يُعيد بناء
     * جلسة الالتقاط، وتكرارها عند كلّ حدث مضيفٍ رجّةٌ في منتصف التسجيل بلا سبب.
     */
    private fun applySurface() = onMain {
        // لا حالة معاينة بعدُ: لا شيء يُركَّب، ولا يجوز أن ندّعي أنّنا ركّبنا —
        // وإلّا رأت الاستدعاءة التالية «مركَّبٌ أصلًا» وبقيت الشاشة سوداء
        val target = preview ?: run {
            installedPreview = null
            return@onMain
        }
        val wanted = if (attached && hostState.isAtLeast(Lifecycle.State.STARTED)) {
            wantedPreview?.get()
        } else {
            null
        }
        if (installedPreview?.get() === wanted) return@onMain
        target.setSurfaceProvider(wanted?.surfaceProvider)
        installedPreview = wanted?.let { WeakReference(it) }
    }

    private fun observeHost(newHost: LifecycleOwner) {
        if (hostOwner === newHost) return
        hostOwner?.lifecycle?.removeObserver(hostObserver)
        hostOwner = newHost
        // الإضافة تُطلق الأحداث الفائتة فورًا، فتصل [hostState] إلى الحقيقة بلا انتظار
        newHost.lifecycle.addObserver(hostObserver)
    }

    /**
     * فكُّ متابعة المضيف. لا نتركها معلّقة: المراقب يمسك بالنشاط، والجلسة تعيش
     * بعمر التطبيق، فالمتابعةُ المنسيّة تسريبُ نشاطٍ كامل.
     */
    private fun forgetHost() {
        hostOwner?.lifecycle?.removeObserver(hostObserver)
        hostOwner = null
        hostState = Lifecycle.State.INITIALIZED
    }

    /**
     * التحرير الحقيقيّ: هدمُ السجلّ يجعل CameraX يفكّ حالات الاستعمال ويغلق العدسة،
     * ثمّ نُنهي ما نملكه نحن. تُنادى من [syncLifecycle] وحدها كي لا يوجد إلّا طريقٌ
     * واحد إلى الإغلاق.
     */
    private fun releaseNow() {
        // يُبطل أيّ مستمع ربطٍ لم يصل بعد
        bindGeneration++
        installedPreview = null
        wantedPreview = null
        runCatching { preview?.setSurfaceProvider(null) }
        cameraOwner?.registry?.currentState = Lifecycle.State.DESTROYED
        cameraOwner = null
        // لا كاميرا ⇒ لا معنى لمراقبة وضع التصوير: المراقبة تُوقظ الخيط كلّ دقيقة
        // في الوضع التلقائيّ، وإبقاؤها بعد إغلاق العدسة استنزافٌ بلا مقابل
        sceneJob?.cancel()
        sceneJob = null
        planJob?.cancel()
        planJob = null
        boundCamera = null
        appliedExposureIndex = null
        // لا عدسة ⇒ لا كشّاف ولا وميض. وهذا أحد «مسارات الخروج» التي يجب أن يُعاد
        // فيها سطوعُ النافذة إلى ما كان، والنشاط يفعل ذلك متابعًا لهذه الراية
        _torchOn.value = false
        _screenFlashOn.value = false
        _hasTorch.value = false
        _dualActive.value = false
        runCatching { provider?.unbindAll() }
        // `OverlayEffect` يملك خيط GL وقائمة إطارات، وهو `AutoCloseable` نملك عمره.
        // تركُه مفتوحًا بعد فكّ الارتباط يُبقي سياق GL حيًّا طول عمر العمليّة.
        runCatching { overlayEffect?.close() }
        overlayEffect = null
        boundPlan = null
        _isReady.value = false
    }

    /**
     * @param owner دورة حياة المضيف. تُتابَع ولا يُربط بها شيء — الارتباط لسجلّ
     *   الجلسة وحده. اسمُ الوسيط باقٍ كما كان لأنّ المتصل يمرّر الشيء نفسه.
     */
    fun bind(owner: LifecycleOwner, previewView: PreviewView, onFailure: () -> Unit) {
        attached = true
        wantedPreview = WeakReference(previewView)
        observeHost(owner)
        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (generation != bindGeneration) return@addListener
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                onFailure()
                return@addListener
            }
            provider = cameraProvider
            // يُسأل مرّةً عند وصول المزوّد: الجواب صفةُ جهازٍ لا حالةٌ تتبدّل، وقسم
            // الإعدادات يقرأ الراية فور فتحه
            probeDualSupport(cameraProvider)

            if (preview == null) preview = Preview.Builder().build()
            if (videoCapture == null) {
                videoCapture = VideoCapture.withOutput(
                    Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                // الوضع المخفَّف يُنزل الدقّة إلى ‎720p‎: المرمِّز
                                // العتاديّ على جهازٍ محدود يُسقط إطاراتٍ عند ‎1080p‎
                                // فيخرج الملفّ متقطّعًا، و‎720p‎ سليمةٌ خيرٌ من ‎1080p‎
                                // متعثّرة. ويُقرأ القرار مرّةً عند بناء المسجّل:
                                // تبديله يوجب إعادة ربط، وهو إعدادٌ لا يُقلَب في
                                // الطريق فلا يُدفع ثمنُ متابعته لحظةً بلحظة.
                                if (liteActive()) Quality.HD else Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            )
                        )
                        .build()
                )
            }
            // الاتّجاه يُبلَّغ عند كلّ تهيئة: `targetRotation` يُقرأ افتراضًا **مرّةً عند
            // البناء**، والنشاط يعالج تبدّل الاتّجاه بنفسه (`configChanges`) فلا يُعاد
            // إنشاؤه ولا تُعاد الحالات. فبلا هذا السطر يخرج الملفّ المسجَّل بعد إدارة
            // الجهاز بميلٍ قدره تسعون درجة — الصورة على الشاشة سليمة والملفّ مقلوب.
            applyRotation()

            // إعادة تركيب السطح وحدها كافية للعودة إلى التبويب أثناء التسجيل: تُعيد
            // الصورة إلى الشاشة بلا ربطٍ جديد، وإعادة الربط تقتل التسجيل الجاري
            applySurface()

            obtainOwner()
            val alreadyBound = boundPlan == desiredPlan() &&
                cameraProvider.isBound(preview!!) &&
                cameraProvider.isBound(videoCapture!!)

            // الشرط الثاني هو صمّام الأمان: لا نفكّ ارتباطًا يحمل تسجيلًا جاريًا
            if (!alreadyBound && !_sessionHolding.value && !applyBinding()) {
                onFailure()
                return@addListener
            }
            _isReady.value = true
            // بعد الربط لا قبله: الحالة هي ما يفتح العدسة، والربط على سجلٍّ ساكن
            // ثمّ رفعُه هو الترتيب الذي يضمن ألّا تُفتح العدسة قبل اكتمال المجموعة
            syncLifecycle()
            // وضع التصوير يُطبَّق بعد أن تصير كاميرا: `CameraControl` لا يوجد قبلها
            applyScene()
            watchScene()
            watchPlan()
        }, ContextCompat.getMainExecutor(context))
    }

    // ===== الربط: الخطّة وارتداداتها =====

    /**
     * محاولةُ ربطٍ واحدة بخطّةٍ بعينها. لا ارتداد فيها ولا رسائل: هي اللبنة، والقرار
     * في [applyBinding].
     *
     * `unbindAll` قبل كلّ محاولة لا مرّةً واحدة: المزوّد يرفض ربطًا مفردًا وهو في وضع
     * الكاميرتين ويرفض العكس، ويرمي `UnsupportedOperationException` صريحة تقول «فُكّ
     * أوّلًا». وهي كذلك ما يمحو قائمة الكاميرات المتزامنة النشطة، فيجوز بعدها أن
     * نُعيد الربط بترتيبٍ مقلوب حين يبادل المستعمل الكبيرة بالمصغَّرة.
     */
    private fun tryBind(cameraProvider: ProcessCameraProvider, requested: BindPlan): Boolean {
        val plan = requested.normalized()
        val previewCase = preview ?: return false
        val captureCase = videoCapture ?: return false
        val owner = cameraOwner ?: return false
        // لا نطلب من الجهاز ما أعلن أنّه لا يملكه: الفشل هنا مسارٌ متوقَّع لا خطأ
        if (plan.dual && !_dualSupported.value) return false
        // **حالتا استعمالٍ اثنتان لا غير**: مسار التركيب يشترط ذلك (انظر
        // [bindConcurrent])، والمفرد لا يضرّه
        val group = UseCaseGroup.Builder()
            .addUseCase(previewCase)
            .addUseCase(captureCase)
            .also { builder -> if (plan.burn) builder.addEffect(obtainOverlayEffect()) }
            .build()
        return runCatching {
            cameraProvider.unbindAll()
            adoptCamera(
                if (plan.dual) {
                    bindConcurrent(cameraProvider, owner, group, plan)
                } else {
                    cameraProvider.bindToLifecycle(owner, selectorOf(plan.lens), group)
                }
            )
            boundPlan = plan
            _dualActive.value = plan.dual
        }.isSuccess
    }

    /**
     * الربط بما هو مطلوب، وإلّا فبأقربِ ما يُقبل — ولا تُترك الجلسة بلا ربطٍ أبدًا.
     *
     * الارتدادات مرتّبةٌ من الأقلّ خسارةً إلى الأكثر، وكلٌّ منها يحفظ ما ارتدّ إليه في
     * التفضيلات: الجهاز الذي رفض شيئًا مرّة سيرفضه دائمًا، وإعادة المحاولة عند كلّ
     * إقلاع تعني تكرار الرسالة نفسها إلى الأبد.
     */
    private fun applyBinding(): Boolean {
        val cameraProvider = provider ?: return false
        if (cameraOwner == null) return false
        val previous = boundPlan
        val plan = desiredPlan()

        if (tryBind(cameraProvider, plan)) return true

        // 1) الحرق: أجهزة كثيرة لا تدعم `CameraEffect` على مسار الفيديو. الأولى أن
        //    نسجّل نظيفًا ونُخبر، لا أن نترك الشاشة سوداء
        if (plan.burn && tryBind(cameraProvider, plan.copy(burn = false))) {
            persistBurn(false)
            _message.value = Message.BurnUnsupported
            return true
        }
        // 2) الازدواج: المطلوب كاميرتان ولم يقبلهما الجهاز — عدسةٌ واحدة بهدوء
        if (plan.dual && tryBind(cameraProvider, plan.copy(dual = false))) {
            persistDual(false)
            _message.value = Message.Notice(R.string.camera_dual_unsupported)
            return true
        }
        if (plan.dual && plan.burn && tryBind(cameraProvider, plan.copy(dual = false, burn = false))) {
            persistDual(false)
            persistBurn(false)
            _message.value = Message.Notice(R.string.camera_dual_unsupported)
            return true
        }
        // 3) العدسة الجديدة هي ما رُفض: عودةٌ إلى ما كان يعمل قبل قليل
        if (previous != null && previous != plan && tryBind(cameraProvider, previous)) {
            persistLens(previous.lens)
            persistDual(previous.dual)
            persistPrimary(previous.primary)
            persistBurn(previous.burn)
            _message.value = Message.Notice(R.string.camera_switch_failed)
            return true
        }
        // 4) آخر ما يملكه جهازٌ فيه كاميرا: خلفيّةٌ مفردة نظيفة. أبشعُ من ذلك شاشةٌ سوداء
        val bare = BindPlan(
            burn = false,
            lens = CameraLens.BACK,
            dual = false,
            layout = DualLayout.DEFAULT,
            primary = CameraLens.DEFAULT,
        )
        if (bare != plan && tryBind(cameraProvider, bare)) {
            persistBurn(false)
            persistLens(CameraLens.BACK)
            persistDual(false)
            _message.value = Message.Notice(R.string.camera_switch_failed)
            return true
        }
        _dualActive.value = false
        boundPlan = null
        return false
    }

    /**
     * الكاميرتان في تيّارٍ واحد — أي في **ملفٍّ واحد**.
     *
     * `bindToLifecycle(List)` في CameraX ‎1.4.1‎ له ثلاثة مسارات، وما نريده أضيقُها:
     * مسار **التركيب** (composition) الذي يمزج التيّارين في تيّارٍ واحد ثمّ يوزّعه على
     * حالات الاستعمال. قرأتُ شروطه من بايتكود `ProcessCameraProvider` لا من التوثيق
     * (وهي أدقّ ممّا في الوثائق، فالصنف `LayoutSettings` لا وجود له في سطح الواجهة
     * المُعلَن أصلًا):
     *
     * 1. القائمة **عنصران بالضبط** — أقلّ أو أكثر يرمي.
     * 2. `lensFacing` **مختلف** بين العنصرين. لو تساوى لسلك مسارَ العدستين
     *    الفيزيائيّتين من جهةٍ واحدة (`physicalCameraId`)، وهو شيءٌ آخر تمامًا.
     * 3. ميزةُ النظام `FEATURE_CAMERA_CONCURRENT`، وإلّا رمى.
     * 4. **قائمتا حالات الاستعمال متساويتان** (`Objects.equals`)، وطولُهما **اثنان**،
     *    وهما `Preview` و`VideoCapture` لا غير. وهذا هو الشرط الذي يفرّق بين تيّارٍ
     *    واحد وتيّارين، ولذلك نُمرّر **كائن `UseCaseGroup` نفسه** إلى العنصرين: قائمةٌ
     *    واحدة بعينها لا نسختان متساويتان بالمصادفة، ومعها يتطابق `ViewPort`
     *    والتأثيرات ودورة الحياة بلا عناء.
     *
     * وإن اختلّ شرطٌ من الأربعة رُبطت كلّ كاميرا وحدها بمعاينتها وملفّها — وهو
     * بالضبط ما لا نريد، ولا يرمي استثناءً يُنبّهنا. فلا يُضاف إلى المجموعة حالةُ
     * استعمالٍ ثالثة أبدًا.
     *
     * الترتيب حاملُ المعنى: العنصر الأوّل هو **الأساس** ويُرسم أوّلًا، والثاني يُرسم
     * فوقه بمزجٍ شفّاف. فالمصغَّرة يجب أن تكون الثانية وإلّا غطّاها الأساسُ الكبير.
     */
    @Suppress("RestrictedApi")
    private fun bindConcurrent(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        group: UseCaseGroup,
        plan: BindPlan,
    ): Camera {
        val configs = listOf(
            ConcurrentCamera.SingleCameraConfig(
                selectorOf(plan.primary),
                group,
                layoutOf(plan.layout, primary = true),
                owner,
            ),
            ConcurrentCamera.SingleCameraConfig(
                selectorOf(plan.primary.other),
                group,
                layoutOf(plan.layout, primary = false),
                owner,
            ),
        )
        val concurrent = cameraProvider.bindToLifecycle(configs)
        // مسار التركيب يعيد كاميرا واحدة (التيّار المركَّب). القائمة الفارغة تعني أنّ
        // شيئًا تغيّر تحتنا، وهي فشلٌ يلتقطه `runCatching` في [tryBind]
        return concurrent.cameras.first()
    }

    /**
     * تخطيط أحد التيّارين داخل الإطار.
     *
     * **افتراض المحاور — مقروءٌ من `DualOpenGlRenderer` لا مُخمَّن:** المصفوفة المبنيّة
     * هناك تجعل الشكل يشغل من إحداثيّات الجهاز المُسوّاة `[offset - size, offset + size]`
     * في كلّ محور. أي أنّ:
     * - `width`/`height` **نسبةٌ من ضلع الإطار** (‎1‎ = الإطار كلّه)، ومداها ‎0..1‎.
     * - `offsetX`/`offsetY` **مركزُ الشكل** في مدى ‎‎-1..+1‎‎ لا ‎0..1‎، والصفر هو المركز.
     * - `x` يزداد يمينًا و`y` يزداد **أعلى** (اصطلاح OpenGL، وهو ما يوافق مثال
     *   Google الرسميّ إذ يضع المصغَّرة أسفل اليمين بـ`y` سالبة).
     *
     * ولم أجرّبه على جهاز. فإن خرجت المصغَّرة أسفلَ اليسار بدل أعلاه فالمحور الرأسيّ
     * مقلوب، والإصلاح كلُّه سالبٌ واحد على [PIP_CENTER] هنا.
     *
     * و[DualLayout.SPLIT] يملأ العرض ويناصف الارتفاع، فتُضغط كلُّ صورةٍ رأسيًّا إلى
     * النصف. هذا ثمن «مناصفةً فوق وتحت» في واجهةٍ لا تملك قصًّا: البديل شريطان
     * أسودان على الجانبين، وهو أسوأ في مسجّل طريق.
     */
    @Suppress("RestrictedApi")
    private fun layoutOf(layout: DualLayout, primary: Boolean): LayoutSettings {
        val builder = LayoutSettings.Builder().setAlpha(1f)
        return when {
            layout == DualLayout.SPLIT -> builder
                .setOffsetX(0f)
                .setOffsetY(if (primary) SPLIT_HALF else -SPLIT_HALF)
                .setWidth(1f)
                .setHeight(SPLIT_HALF)
                .build()

            primary -> builder
                .setOffsetX(0f)
                .setOffsetY(0f)
                .setWidth(1f)
                .setHeight(1f)
                .build()

            // المصغَّرة أعلى اليسار: `x` سالبة (يسارًا) و`y` موجبة (أعلى)
            else -> builder
                .setOffsetX(-PIP_CENTER)
                .setOffsetY(PIP_CENTER)
                .setWidth(PIP_SCALE)
                .setHeight(PIP_SCALE)
                .build()
        }
    }

    private fun selectorOf(lens: CameraLens): CameraSelector = when (lens) {
        CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    /**
     * `FEATURE_CAMERA_CONCURRENT` نصٌّ ثابت يُدمج وقت الترجمة، فسؤاله على جهازٍ دون
     * أندرويد ‎11‎ لا يرمي بل يعيد `false` — وهو الجواب الصحيح هناك على كلّ حال.
     *
     * والميزة وحدها لا تكفي: نطلب زوجًا يجمع أماميّةً وخلفيّة بعينه، فجهازٌ لا يتيح
     * إلّا عدستين خلفيّتين معًا يعلن الميزة ولا يخدم غرضنا.
     */
    private fun probeDualSupport(cameraProvider: ProcessCameraProvider) {
        val feature = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        }.getOrDefault(false)
        _dualSupported.value = feature && runCatching {
            cameraProvider.availableConcurrentCameraInfos.any { combo ->
                combo.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    combo.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            }
        }.getOrDefault(false)
    }

    // ===== تبديل العدسة والمبادلة =====

    /**
     * مهمّةُ متابعة التفضيلات التي يستلزم تبديلُها إعادة ربط.
     *
     * متابعةٌ لا دوالُّ ضبطٍ فقط: قسم الإعدادات يكتب في [AppSettings] مباشرةً، ولو
     * بنينا إعادة الربط على نداءٍ من الشاشة وحدها لبقيت الكاميرا على حالها حين
     * يبدّل المستعمل التخطيط من الإعدادات — وهو أوّل ما سيفعله.
     */
    private var planJob: Job? = null

    private fun watchPlan() {
        if (planJob != null) return
        planJob = sceneScope.launch {
            combine(
                settings.cameraLens,
                settings.dualCamera,
                settings.dualLayout,
                settings.dualPrimary,
            ) { _, _, _, _ -> Unit }.collectLatest { requestRebind() }
        }
    }

    /**
     * «ما هو مربوطٌ لم يعد ما هو مطلوب».
     *
     * خارج التسجيل إعادةُ ربطٍ بسيطة. وأثناءه **لفُّ مقطع**: يُغلق الجاري سليمًا ثمّ
     * يُعاد الربط ثمّ يبدأ التالي — بآليّة التقسيم نفسها الموجودة سلفًا، لا بآليّةٍ
     * ثانية. علّةُ ذلك أنّ التخطيط والعدسة حقول نهائيّة في مُهيّئ حالات الاستعمال،
     * فتبديلها يهدم سطح المسجّل حتمًا، وأنظفُ ما يُصنع بملفٍّ سيُقطع أن يُختم أوّلًا.
     *
     * والرحلة ومسار GPX لا يُمسّان: هما لا يعرفان بالمسجّل شيئًا أصلًا.
     */
    private fun requestRebind() = onMain {
        if (!_isReady.value || provider == null || cameraOwner == null) return@onMain
        if (desiredPlan() == boundPlan) return@onMain
        if (_sessionHolding.value) {
            val current = recording
            // ثلاث حالاتٍ لا يُلَفّ فيها شيء، ويُؤجَّل الربط إلى [endSessionHold]:
            // جلسةٌ ممسكةٌ بلا مسجّل (بين طلب التوقّف وكتابة الذيل)، ولفّةٌ منتظرةٌ
            // أصلًا، وترميزٌ لم يبدأ بعد — والأخيرة أهمّها: قطعُ مقطعٍ قبل حدث `Start`
            // يُنتج ملفًّا فارغًا يصير هو مرساةَ المزامنة في GPX، فيكذب على المسار كلّه
            if (!sessionActive || current == null || rebindPending || !_isRecording.value) {
                return@onMain
            }
            rebindPending = true
            runCatching { current.stop() }.onFailure { rebindPending = false }
            return@onMain
        }
        if (!applyBinding()) {
            _isReady.value = false
            _message.value = Message.Notice(R.string.camera_switch_failed)
        }
    }

    /** إعادةُ ربطٍ منتظرة تُنفَّذ في `Finalize` بين مقطعين */
    private var rebindPending = false

    /**
     * تبديل العدسة. لا يُعطَّل أثناء التسجيل — بخلاف الحرق — لأنّ الحاجة إليه تقع
     * والراكب على الطريق: يريد وجهه أو الطريق خلفه في اللحظة التي يقع فيها ما يستحقّ
     * التصوير، ولا يُعقل أن يُطالَب بإيقاف التسجيل أوّلًا.
     */
    fun switchLens() {
        if (_dualActive.value) return
        persistLens(requestedLens().other)
        requestRebind()
    }

    /**
     * مبادلة الكبيرة بالمصغَّرة في الوضع المزدوج.
     *
     * **تلُفّ المقطع كتبديل العدسة**: كان الظنّ أنّها تغييرُ تركيبٍ لا إعادةُ ربط،
     * والبايتكود يقول غيره — `LayoutSettings` حقلٌ نهائيّ في `CameraUseCaseAdapter`
     * يُمرَّر إلى مُركِّب التيّارين عند بنائه، ولا سبيل إلى تبديله بعده إلّا ببناء
     * مُهيّئٍ جديد، أي `unbindAll` وربطٍ جديد.
     */
    fun swapDualPrimary() {
        if (!_dualActive.value) return
        persistPrimary(requestedPrimary().other)
        requestRebind()
    }

    // ===== الكشّاف =====

    fun toggleTorch() = setTorch(!_torchOn.value)

    /**
     * الكشّاف: مصباحٌ حقيقيّ إن وُجد، وإلّا فوميضُ شاشةٍ إن أذن به المستعمل، وإلّا فخبر.
     *
     * ووميض الشاشة مطفأٌ افتراضًا بقرارٍ سابق: الكاميرا الأماميّة في مسجّل طريقٍ
     * موجَّهةٌ إلى وجه السائق، وشاشةٌ بيضاء بإضاءةٍ قصوى في وجهه ليلًا خطرٌ لا ميزة.
     */
    fun setTorch(on: Boolean) {
        val camera = boundCamera
        if (camera != null && _hasTorch.value) {
            // النداء يعبر حدود الجهاز وقد يُرفض؛ ولا نرفع الحالة إلّا إن قُبل الطلب
            runCatching { camera.cameraControl.enableTorch(on) }
                .onSuccess {
                    _torchOn.value = on
                    _screenFlashOn.value = false
                }
            return
        }
        if (settings.screenFlash.value) {
            _torchOn.value = on
            _screenFlashOn.value = on
            return
        }
        _message.value = Message.Notice(R.string.camera_torch_none)
    }

    /**
     * إطفاءٌ قسريّ لوميض الشاشة من خارج الجلسة.
     *
     * يناديه النشاط عند مغادرة المقدّمة: سطوع النافذة صفةٌ للنافذة لا للجلسة، وإعادتُه
     * عند `onStop` بلا إطفاء الراية كانت ستُبقي الطبقة البيضاء على شاشةٍ عادت بلا سطوع.
     */
    fun clearScreenFlash() {
        if (!_screenFlashOn.value) return
        _screenFlashOn.value = false
        _torchOn.value = false
    }

    /**
     * ارتباطٌ جديد يعني `CameraControl` جديدًا وتعويض إضاءةٍ عاد إلى الصفر، فالفهرس
     * المطبَّق سابقًا لم يعد يصف الجهاز. تصفيرُه هنا يمنع أن تظنّ [applyScene] أنّ
     * ما تريده مضبوطٌ أصلًا فتسكت وقد عادت العدسة إلى إضاءة النهار.
     */
    private fun adoptCamera(camera: Camera) {
        boundCamera = camera
        appliedExposureIndex = null
        // `CameraControl` جديد لا يرث كشّاف سابقه: المصباح انطفأ فعلًا مع فكّ
        // الارتباط، وإبقاء الحالة مرفوعةً يجعل الزرّ يقول «مضاء» وليس في الطريق ضوء
        _torchOn.value = false
        _screenFlashOn.value = false
        _hasTorch.value = runCatching { camera.cameraInfo.hasFlashUnit() }.getOrDefault(false)
    }

    /**
     * السجلّ المهدوم لا يُبعث، فكلّ دورةِ «حرّرنا ثمّ عدنا» تحتاج مالكًا جديدًا.
     * ولأنّ الهدم لا يقع إلّا في [releaseNow] — ومعه فكُّ الارتباط وتصفير
     * [boundWithBurn] — لا يمكن أن يبقى ارتباطٌ معلّقًا على مالكٍ ميّت.
     */
    private fun obtainOwner(): SessionOwner {
        cameraOwner?.let { return it }
        val fresh = SessionOwner()
        // CREATED لا INITIALIZED: الأخيرة تجعل CameraX يؤجّل حتى أوّل حدث، والأولى
        // حالةٌ صريحة نرفعها إلى RESUMED بعد اكتمال الربط
        fresh.registry.currentState = Lifecycle.State.CREATED
        cameraOwner = fresh
        return fresh
    }

    /**
     * `OverlayEffect` غالٍ في الإنشاء (خيط GL وقائمة انتظار إطارات)، فنبنيه مرّة
     * واحدة ونحتفظ به عبر إعادات الربط. الهدف `VIDEO_CAPTURE` وحده: المعاينة على
     * الشاشة تحمل طبقة Compose أصلًا، وحرقُها مرّتين تشويش.
     */
    private fun obtainOverlayEffect(): OverlayEffect {
        overlayEffect?.let { return it }
        val effect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            0,
            Handler(Looper.getMainLooper()),
        ) {
            // إسقاط إطارٍ واحد لا يستحقّ إجهاض التسجيل
        }
        effect.setOnDrawListener { frame -> painter.onDraw(frame) }
        overlayEffect = effect
        return effect
    }

    // ===== وضع التصوير: ليل / نهار =====

    /**
     * نطاقٌ صغير للمراقبة وحدها، رئيسُ الخيط لأنّ `CameraControl` وحالة الجلسة
     * كلّها تُمسّ من الخيط الرئيس. لا يُلغى أبدًا — الجلسة تعيش بعمر التطبيق —
     * وإنّما تُلغى مهمّتُه عند تحرير العدسة.
     */
    private val sceneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sceneJob: Job? = null

    /**
     * آخر فهرس تعويضٍ ضُبط فعلًا على هذه الكاميرا. المقارنة به تمنع نداءً إلى
     * `CameraControl` كلّ دقيقةٍ بالقيمة نفسها، وكلّ نداءٍ منها طلبُ التقاطٍ جديد.
     */
    private var appliedExposureIndex: Int? = null

    /**
     * يتابع الوضع المختار وساعتَي النهار والليل، ويعيد التقييم كلّ دقيقة في الوضع
     * التلقائيّ وحده: من يقود عند الغروب يجب أن تتبدّل إضاءة عدسته وهو سائر لا عند
     * إعادة تشغيل التطبيق. والوضع الصريح (نهار/ليل) لا يتبدّل بالساعة فلا نبضة له.
     */
    private fun watchScene() {
        if (sceneJob != null) return
        sceneJob = sceneScope.launch {
            combine(
                settings.cameraScene,
                settings.dayStartHour,
                settings.nightStartHour,
            ) { scene, _, _ -> scene }.collectLatest { scene ->
                while (true) {
                    applyScene(scene)
                    if (scene != CameraScene.AUTO) break
                    delay(SCENE_RECHECK_MS)
                }
            }
        }
    }

    /**
     * اختيار المستعمل. لا إعادة ربط هنا ولا تأجيل إلى المقطع التالي: التعويض يُضبط
     * على `CameraControl` لكاميرا قائمة، فيسري وسط تسجيلٍ جارٍ بلا أن يمسّه.
     *
     * والتطبيق فوريّ من القيمة المطلوبة لا من المحفوظة: كتابة DataStore غير
     * متزامنة، وانتظارها كان سيؤخّر أثر اللمسة على الصورة دورةً كاملة.
     */
    fun setCameraScene(scene: CameraScene) {
        if (settings.cameraScene.value != scene) settings.setCameraScene(scene)
        if (exposureSupported() == false) {
            _message.value = Message.SceneUnsupported
            return
        }
        applyScene(scene)
    }

    /**
     * `null` تعني «لا كاميرا فلا رأي»: قبل الربط لا يُسأل الجهاز ولا يُتّهم بأنّه
     * لا يدعم شيئًا.
     */
    private fun exposureSupported(): Boolean? {
        val camera = boundCamera ?: return null
        return runCatching { camera.cameraInfo.exposureState.isExposureCompensationSupported }
            .getOrNull()
    }

    /**
     * ترجمة الوضع إلى فهرس تعويض إضاءة.
     *
     * **الزيادة تُحسب بالـEV لا برقمٍ ثابت**: خطوة التعويض تختلف بين الأجهزة
     * (‎1/3‎ EV أو ‎1/6‎ EV أو غيرهما)، فالفهرس ‎+3‎ يعني ‎+1‎ EV على جهازٍ و‎+0.5‎ EV
     * على آخر. نقسم الزيادة المطلوبة على الخطوة المعلَنة فيخرج الأثر البصريّ نفسه
     * على الجهازين، ثمّ نحصر الناتج في المدى الذي يعلنه الجهاز.
     */
    private fun applyScene(scene: CameraScene = settings.cameraScene.value) {
        val camera = boundCamera ?: return
        val state = runCatching { camera.cameraInfo.exposureState }.getOrNull() ?: return
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep
        // خطوةٌ صفريّة أو شاذّة تعني قسمةً على صفر؛ الجهاز الذي يعلنها لا تعويض له
        if (!step.isFinite || step.isZero) return
        val ev = if (nightNow(scene)) NIGHT_EV else DAY_EV
        val range = state.exposureCompensationRange
        val index = (ev / step.toFloat()).roundToInt().coerceIn(range.lower, range.upper)
        if (index == appliedExposureIndex) return
        // النداء يعبر حدود الجهاز، وقد يُلغى بربطٍ جديد يقع في اللحظة نفسها
        runCatching { camera.cameraControl.setExposureCompensationIndex(index) }
            .onSuccess { appliedExposureIndex = index }
    }

    /**
     * التلقائيّ يتبع ساعتَي النهار والليل نفسَيهما اللتين تتبعهما السمة، ويستدعي
     * دالّتها بعينها: نسختان من الشرط تنحرف إحداهما عن الأخرى بعد أوّل تعديل،
     * فتصير الشاشة ليلًا والعدسة نهارًا في اللحظة ذاتها.
     */
    private fun nightNow(scene: CameraScene): Boolean = when (scene) {
        CameraScene.DAY -> false
        CameraScene.NIGHT -> true
        CameraScene.AUTO -> computeNight(
            settings.dayStartHour.value,
            settings.nightStartHour.value,
        )
    }

    /**
     * تُنادى عند مغادرة شاشة الكاميرا. لا تفكّ الارتباط أثناء التسجيل، وإلا ضاع
     * التسجيل الجاري بمجرّد أن يبدّل المستخدم التبويب.
     *
     * ما دام تسجيلٌ قائمًا فكلّ ما تفعله هذه الدالّة نزعُ السطح وتسجيلُ النيّة:
     * «لا شاشة تنتظر». والتحرير يقع لاحقًا في [syncLifecycle] لحظةَ انتهاء
     * الجلسة، ولو كان التطبيق في الخلفيّة حينها.
     */
    fun detach() {
        attached = false
        // وميض الشاشة شيءٌ يخصّ شاشةً تُعرض؛ ولا شاشة الآن. وهو أحد مسارات الخروج
        // التي يجب أن يُعاد فيها السطوع، فلا يُترك على ‎1f‎ في تبويبٍ آخر
        _screenFlashOn.value = false
        applySurface()
        // متابعةُ مضيفٍ لا شاشة لنا فيه بلا معنى، وهي تسريبُ نشاطٍ إن دامت
        forgetHost()
        syncLifecycle()
    }

    // ===== التسجيل =====

    /**
     * الجلسة أوسع من الملفّ: مع التقسيم تتعاقب عدّة ملفّات داخل جلسةٍ واحدة، فلا
     * يصلح [_isRecording] وحده للتمييز بين «انتهى المقطع» و«انتهى التصوير».
     */
    private var sessionActive = false

    /** هل ننتظر `Finalize`؟ ما دمنا ننتظرها فالتثبيت لا يُرفع بأيّ طريقٍ آخر */
    private var finalizePending = false

    /**
     * «الجلسة تمسك الكاميرا».
     *
     * يُرفع تزامنيًّا لحظةَ طلب التسجيل — قبل أن يصل `VideoRecordEvent.Start` غير
     * المتزامن — ولا يُخفض إلّا بعد آخر `Finalize`. الفارق عن [isRecording] مقصود:
     * ذاك للواجهة (هل يُرمَّز الآن؟)، وهذا للحياة (هل يجوز إغلاق العدسة؟). ولو
     * بنينا التثبيت على [isRecording] لوُجدت فجوتان قاتلتان: واحدة بين الضغطة
     * وبدء الترميز، وأخرى بين طلب التوقّف وكتابة ذيل الملفّ.
     *
     * وهو أيضًا ما تقرؤه [net.gnutux.speedometer.service.TripService] لتقرّر بقاءها.
     */
    private val _sessionHolding = MutableStateFlow(false)
    val isSessionActive = _sessionHolding.asStateFlow()

    /**
     * اسم أوّل ملفّ في الجلسة الجارية أو الأخيرة. يقرؤه [net.gnutux.speedometer.ui.SpeedoViewModel]
     * عند إنهاء الرحلة فيُكتب في `<gtspeedo:video>`، وبه تُعاد محاذاة المسار على
     * الفيديو لاحقًا بلا تخمين — وهو الغرض الذي ثُبّتت المرساة من أجله.
     */
    private val _sessionFirstFile = MutableStateFlow<String?>(null)
    val sessionFirstFile = _sessionFirstFile.asStateFlow()

    /** سقف المقطع للجلسة الجارية؛ `null` يعني تصويرًا متّصلًا */
    private var segmentLimitMs: Long? = null

    /** ترتيب المقطع داخل الجلسة، يبدأ من 1 ويظهر في اسم الملفّ */
    private var segmentIndex = 0

    /** @param onStarted تُنادى لحظة بدء الترميز فعلًا، لتثبيت مرساة المزامنة */
    fun startRecording(onStarted: () -> Unit) {
        // بلا هذا الحارس كان الضغط على «تسجيل» بعد فشل الربط يُثبّت دورة الحياة
        // ويرفع الخدمة إلى نوع «كاميرا»، ثمّ يقف المسجّل منتظرًا سطحًا لن يأتي:
        // لا `Start` ولا `Finalize`، فيبقى `isSessionActive` مرفوعًا أبدًا.
        if (!_isReady.value) {
            _message.value = Message.Failed(R.string.camera_unavailable)
            return
        }
        if (_sessionHolding.value || sessionActive || _isRecording.value) return
        val capture = videoCapture ?: run {
            _message.value = Message.Failed(R.string.camera_unavailable)
            return
        }

        // يُقرأ السقف مرّة واحدة لكلّ جلسة: لو قُرئ عند كلّ مقطع لأنتج تبديلُ الإعداد
        // في منتصف الرحلة مقاطعَ متفاوتة الأطوال، وهي أسوأ من أيّ طولٍ ثابت
        val minutes = settings.videoSegmentMinutes.value
        segmentLimitMs = if (minutes > AppSettings.SEGMENT_CONTINUOUS) {
            minutes * MILLIS_PER_MINUTE
        } else {
            null
        }
        segmentIndex = 0
        sessionActive = true
        _isPaused.value = false
        _sessionFirstFile.value = null

        // التثبيت قبل الإقلاع لا بعده: `prepareRecording` نفسها قد تجد المصدر
        // معطّلًا لو سبقها هبوطُ دورة الحياة
        _sessionHolding.value = true
        syncLifecycle()

        if (!beginSegment(capture, onStarted)) {
            sessionActive = false
            endSessionHold()
            _message.value = Message.Failed(R.string.rec_err_recorder)
        }
    }

    /**
     * إيقافٌ مؤقّت **للفيديو وحده**.
     *
     * المدى مقرَّر: الرحلة تمضي — المسار والمسافة والزمن — ولا يمسّها هذا الزرّ.
     * من يقف عند إشارةٍ يريد توفير مساحة القرص لا إفساد إحصاءات رحلته، وملفّ
     * المسار يبقى متّصلًا لأنّ المسجّل لم يُخطَر بشيء.
     *
     * و`Recording.pause` ترمي `IllegalStateException` على بعض الأجهزة عند التكرار
     * أو في لحظة `Finalize` (المسجّل انتقل ولمّا يصلنا حدثه)، فهي ملفوفة: إيقافٌ
     * مؤقّت متعثّر لا يستحقّ إسقاط التطبيق.
     */
    fun pause() {
        if (_isPaused.value) return
        val current = recording ?: return
        runCatching { current.pause() }.onSuccess { _isPaused.value = true }
    }

    fun resume() {
        if (!_isPaused.value) return
        val current = recording
        if (current == null) {
            // لا مسجّل يُستأنف: العلم كذبٌ الآن مهما كان سببه، وتركُه مرفوعًا يُبقي
            // شارة «موقوف» على شاشةٍ لا تسجّل شيئًا
            _isPaused.value = false
            return
        }
        runCatching { current.resume() }.onSuccess { _isPaused.value = false }
    }

    /**
     * لفّةٌ وقعت والتصوير موقوف: المقطع التالي يبدأ **جاريًا** لأنّ `Recording`
     * جديدة، فيُعاد إيقافه فور بدئه وإلّا سجّل الجهازُ ما ظنّ الراكب أنّه لن يُسجَّل.
     *
     * ولا يقع هذا في المعتاد: سقف المدّة يقيسه المسجّل على المدّة المرمَّزة، وهي لا
     * تتقدّم والتسجيل موقوف — وذاك هو المطلوب أصلًا («مؤقّت المقطع لا يمضي وهو
     * ساكن»). هذا حارسٌ للجهاز الذي يخالف، لا مسارٌ متوقَّع.
     */
    private fun reapplyPause() {
        if (!_isPaused.value) return
        val current = recording ?: return
        runCatching { current.pause() }
    }

    fun stopRecording() {
        // يُطفأ العلم أوّلًا: `stop` تُطلق `Finalize`، ولو بقي مرفوعًا لظنّها المعالج
        // لفّةَ مقطعٍ وبدأ ملفًّا جديدًا بعد أن طلب المستخدم التوقّف
        sessionActive = false
        segmentLimitMs = null
        val current = recording
        recording = null
        if (current == null) {
            // لا مسجّل. لكنّ الغياب وجهان: إخفاقٌ في الإقلاع فلا `Finalize` قادم —
            // وحينها يجب رفع التثبيت وإلّا بقيت العدسة مفتوحة أبدًا — أو نقرةٌ ثانية
            // على «إيقاف» و`Finalize` الأولى في الطريق. رفعُ التثبيت في الحال الثانية
            // يُغلق العدسة والذيل (moov) لم يُكتب بعد، فيخرج ملفٌّ لا يفتحه مشغّل.
            if (!finalizePending) {
                _isRecording.value = false
                _isPaused.value = false
                endSessionHold()
            }
            return
        }
        // التثبيت باقٍ حتى `Finalize`: بين طلب التوقّف وكتابة ذيل الملفّ (moov)
        // زمنٌ، وإغلاق العدسة فيه يُنتج ملفًّا مبتورًا لا يفتحه مشغّل
        current.stop()
    }

    /**
     * نهاية إمساك الجلسة بالعدسة. تُعيد القرار إلى [syncLifecycle]: إن كانت شاشةٌ
     * حاضرة عادت الكاميرا إلى تتبّع المضيف، وإن كان المستخدم قد غادر — أو أوقف
     * التسجيل والتطبيق في الخلفيّة — حُرّرت العدسة هنا والآن لا حين يعود.
     */
    private fun endSessionHold() {
        _sessionHolding.value = false
        syncLifecycle()
        // العودة إلى تتبّع المضيف قد تعني عودةَ المعاينة أيضًا
        applySurface()
        // تفضيلٌ تبدّل والجلسة ممسكةٌ بالعدسة يُؤجَّل حتى تُفلتها: من بدّل التخطيط
        // من الإعدادات أثناء التصوير يجد أثره الآن بلا أن يُعاد فتح الشاشة
        requestRebind()
    }

    /**
     * يبدأ ملفًّا واحدًا. يعيد `false` إن رفض المسجّل الإقلاع، فيقرّر المتصل: بدايةُ
     * جلسةٍ فاشلة أم لفّةٌ متعثّرة.
     */
    @SuppressLint("MissingPermission")
    private fun beginSegment(capture: VideoCapture<Recorder>, onStarted: () -> Unit): Boolean {
        val limit = segmentLimitMs
        segmentIndex++
        val requested = nextFileName(segmented = limit != null)

        var pending = media.prepareRecording(capture.output, requested, limit)
        // الصوت يحتاج رضا المستخدم في الإعدادات **و** إذن النظام؛ أحدهما لا يكفي
        if (audioAllowed()) pending = pending.withAudioEnabled()

        val started = runCatching {
            pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        // المرساة للمقطع الأوّل وحده: ملفّ GPX واحد لا يحمل إلّا إزاحةً
                        // واحدة، فإن ثبّتناها عند كلّ لفّة صارت تشير إلى آخر مقطع
                        // وتكذب على ما قبله.
                        if (segmentIndex == 1) onStarted()
                        _isRecording.value = true
                        reapplyPause()
                    }

                    is VideoRecordEvent.Finalize -> onFinalize(event, capture, requested, onStarted)

                    else -> Unit
                }
            }
        }
        recording = started.getOrNull()
        finalizePending = recording != null
        return started.isSuccess
    }

    /**
     * ترجمة رمز الإنهاء إلى مصير مفهوم، ولفُّ المقطع عند الحاجة.
     *
     * الرموز مُسمّاة لا رقميّة: قيمة `ERROR_SOURCE_INACTIVE` تفصيلٌ في المكتبة قد
     * يتبدّل، والمعنى وحده هو العقد.
     */
    private fun onFinalize(
        event: VideoRecordEvent.Finalize,
        capture: VideoCapture<Recorder>,
        requested: String,
        onStarted: () -> Unit,
    ) {
        recording = null
        finalizePending = false
        val name = savedName(event, requested)
        // أوّل ملفّ في الجلسة هو ما يُذكر في GPX، لأنّ مرساته هي المثبَّتة
        if (_sessionFirstFile.value == null) _sessionFirstFile.value = name

        // إعادةُ ربطٍ كانت تنتظر إغلاق الملفّ. تُستهلك هنا مهما كان مصير المقطع، وإلّا
        // بقيت معلّقةً تلُفّ المقطع التالي بلا سبب
        val rebindRequested = rebindPending
        rebindPending = false

        val rolling = sessionActive &&
            (rebindRequested || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED)
        if (rolling) {
            // الربط الجديد **بين** المقطعين: بعد إغلاق الملفّ سليمًا وقبل فتح التالي.
            // فشلُه يعني أنّ آخر ارتدادٍ أيضًا سقط ولا كاميرا البتّة، فلا معنى لمقطعٍ
            // تالٍ — نُنهي الجلسة بخبرٍ صريح بدل مسجّلٍ ينتظر سطحًا لن يأتي
            if (rebindRequested && !applyBinding()) {
                sessionActive = false
                segmentLimitMs = null
                _isRecording.value = false
                _isPaused.value = false
                _isReady.value = false
                endSessionHold()
                _message.value = Message.Notice(R.string.camera_switch_failed)
                return
            }
            // `_isRecording` يبقى مرفوعًا عبر اللفّة كي لا يومض زرّ التسجيل بين ملفّين.
            // و[_isPaused] كذلك: [reapplyPause] عند بدء المقطع التالي يُنزل عليه
            // حالةَ الإيقاف نفسها، فلا يستأنف التصوير من تلقائه بلفّةِ ملفّ
            _message.value = Message.Segment(name)
            if (beginSegment(capture, onStarted)) return
            // تعثّرت اللفّة: نُنهي الجلسة بدل محاولةٍ تلو أخرى بلا نهاية
            sessionActive = false
            segmentLimitMs = null
            _isRecording.value = false
            _isPaused.value = false
            endSessionHold()
            _message.value = Message.Failed(R.string.rec_err_recorder)
            return
        }

        sessionActive = false
        segmentLimitMs = null
        _isRecording.value = false
        _isPaused.value = false
        // هنا وحدها تُفكّ قبضة الجلسة على العدسة: الملفّ أُغلق فعلًا. والمُنفّذ رئيسٌ
        // (`getMainExecutor`) فالانتقال يقع على خيطه المسموح
        endSessionHold()
        // الصمت عند الفشل هو ما جعل المستخدم يظنّ أن شيئًا لم يُسجَّل
        _message.value = outcomeOf(event.error, name)
    }

    /**
     * ليس كلّ رمزٍ فشلًا: أربعةٌ منها تُنهي ملفًّا صالحًا للتشغيل وتخبر عن **سبب**
     * توقّف التصوير لا عن ضياعه. أشهرها `SOURCE_INACTIVE` (فكّ الارتباط أو ذهاب
     * التطبيق إلى الخلفيّة)، وقد كان يُعرض «تعذّر حفظ التسجيل (4)» والملفّ في المعرض.
     */
    private fun outcomeOf(error: Int, name: String): Message = when (error) {
        VideoRecordEvent.Finalize.ERROR_NONE -> Message.Saved(name)

        // بلوغ حدّ المدّة خارج جلسةٍ نشطة (أُوقفت بالتزامن مع الحدّ): الملفّ كامل كما طُلب
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> Message.Saved(name)

        // الملفّ محفوظ لكنّه أقصر ممّا أراد المستخدم؛ الاسم جزءٌ من الرسالة كي يجده
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
        -> Message.Truncated(name)

        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
            Message.Failed(R.string.rec_err_invalid)

        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
            Message.Failed(R.string.rec_err_encoding)

        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ->
            Message.Failed(R.string.rec_err_recorder)

        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
            Message.Failed(R.string.rec_err_no_data)

        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED ->
            Message.Failed(R.string.rec_err_collected)

        // `ERROR_UNKNOWN` وأيّ رمزٍ يستجدّ في إصدارٍ لاحق من المكتبة
        else -> Message.Failed(R.string.rec_err_unknown)
    }

    /**
     * الاسم الفعليّ للملفّ. MediaStore قد يُعيد التسمية عند التصادم (`ride-… (1).mp4`)
     * فنقرأ ما استقرّ عليه، وننكص إلى المطلوب حين لا يُنتج الإنهاء مسارًا أصلًا (فشلٌ
     * قبل إنشاء الملفّ).
     */
    private fun savedName(event: VideoRecordEvent.Finalize, requested: String): String {
        val uri: Uri = event.outputResults.outputUri
        if (uri == Uri.EMPTY) return requested
        return media.displayName(uri) ?: requested
    }

    /**
     * ختمٌ زمنيّ لكلّ مقطع ليعكس لحظةَ بدئه هو، يليه ترتيبٌ مصفوف بالأصفار. الختم
     * وحده يكفي للفرز، والترتيب يجعل تتابعَ المقاطع ظاهرًا للعين بلا مقارنة ثوانٍ.
     */
    private fun nextFileName(segmented: Boolean): String {
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date())
        if (!segmented) return "ride-$stamp.mp4"
        val index = String.format(Locale.US, "%02d", segmentIndex)
        return "ride-$stamp-$index.mp4"
    }

    private fun audioAllowed(): Boolean =
        settings.recordAudio.value && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
        const val MILLIS_PER_MINUTE = 60_000L

        /**
         * زيادة الإضاءة ليلًا بوحدة EV، لا بفهرسٍ ثابت (الخطوة تختلف بين الأجهزة).
         *
         * ‎+1.5 EV‎ تضاعف الضوء الداخل نحو ثلاث مرّات، وهو حدٌّ محسوب: ما دونه لا
         * يُرى أثره على طريقٍ مُنار، وما فوقه يُشبع مصابيح المركبات المقابلة فتصير
         * لطخًا بيضاء تبتلع لوحاتِها — وهي بالضبط ما يُصوَّر من أجله.
         */
        const val NIGHT_EV = 1.5f

        /** النهار هو ما ضبطه المصنّع: الحسّاس يقيس المشهد، ولا نُملي عليه شيئًا */
        const val DAY_EV = 0f

        /** نبضة إعادة التقييم في الوضع التلقائيّ — دقيقةٌ كنبضة السمة نفسها */
        const val SCENE_RECHECK_MS = 60_000L

        /**
         * ضلع المصغَّرة نسبةً إلى ضلع الإطار.
         *
         * ‎%30‎: ما دونه لا يُميّز وجهًا في ملفٍّ بدقّة ‎1080‎، وما فوقه يبتلع من الطريق
         * أكثر ممّا يستحقّ شاهدٌ على الزاوية.
         */
        const val PIP_SCALE = 0.3f

        /**
         * مركز المصغَّرة في المحورين، بالقيمة المطلقة. الشكل يشغل
         * `[offset - size, offset + size]`، فالالتصاق بالحافّة عند `1 - size`
         * وننقص منه هامشًا صغيرًا كي لا تُقصّ حافّتُها على شاشةٍ منحنية.
         */
        const val PIP_CENTER = 1f - PIP_SCALE - 0.04f

        /** نصفُ الإطار رأسيًّا: مركز النصف العلويّ عند ‎+0.5‎ وارتفاعه ‎0.5‎ */
        const val SPLIT_HALF = 0.5f
    }
}

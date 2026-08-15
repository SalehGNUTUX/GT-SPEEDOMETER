package net.gnutux.speedometer.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
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
import net.gnutux.speedometer.core.settings.CameraScene
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
     * قيمة `burnOverlay` التي جرى الربط عليها فعلًا. `null` تعني «الربط لم يعد
     * موثوقًا»: إمّا لم يحدث بعد، أو بدّل المستخدم التوگل فوجب إعادة بناء
     * `UseCaseGroup` لأنّ التأثيرات لا تُضاف ولا تُنزع من ارتباطٍ قائم.
     */
    private var boundWithBurn: Boolean? = null

    /**
     * الكتابة في DataStore غير متزامنة، فقيمة [AppSettings.burnOverlay] تتأخّر دورةً
     * عن ضغطة المستخدم. نحتفظ بالقيمة المطلوبة حتى يلحق بها المخزن، وإلّا رُبط
     * المشهد على القيمة القديمة فبدا التوگل كأنّه لا يفعل شيئًا.
     */
    private var pendingBurn: Boolean? = null

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
        if (_sessionHolding.value || _isRecording.value || requestedBurn() == enabled) return
        persistBurn(enabled)
        boundWithBurn = null
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
        boundCamera = null
        appliedExposureIndex = null
        runCatching { provider?.unbindAll() }
        // `OverlayEffect` يملك خيط GL وقائمة إطارات، وهو `AutoCloseable` نملك عمره.
        // تركُه مفتوحًا بعد فكّ الارتباط يُبقي سياق GL حيًّا طول عمر العمليّة.
        runCatching { overlayEffect?.close() }
        overlayEffect = null
        boundWithBurn = null
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
            // إعادة تركيب السطح وحدها كافية للعودة إلى التبويب أثناء التسجيل: تُعيد
            // الصورة إلى الشاشة بلا ربطٍ جديد، وإعادة الربط تقتل التسجيل الجاري
            applySurface()

            val burn = requestedBurn()
            val sessionOwner = obtainOwner()
            val alreadyBound = boundWithBurn == burn &&
                cameraProvider.isBound(preview!!) &&
                cameraProvider.isBound(videoCapture!!)

            // الشرط الثاني هو صمّام الأمان: لا نفكّ ارتباطًا يحمل تسجيلًا جاريًا
            if (!alreadyBound && !_sessionHolding.value) {
                runCatching {
                    cameraProvider.unbindAll()
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview!!)
                        .addUseCase(videoCapture!!)
                        .also { builder ->
                            if (burn) builder.addEffect(obtainOverlayEffect())
                        }
                        .build()
                    adoptCamera(
                        cameraProvider.bindToLifecycle(
                            sessionOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            group,
                        )
                    )
                    boundWithBurn = burn
                }.onFailure {
                    // أجهزة كثيرة لا تدعم CameraEffect على مسار الفيديو. الأولى أن
                    // نسجّل نظيفًا ونُخبر، لا أن نترك الشاشة سوداء.
                    runCatching {
                        cameraProvider.unbindAll()
                        adoptCamera(
                            cameraProvider.bindToLifecycle(
                                sessionOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                videoCapture,
                            )
                        )
                        boundWithBurn = false
                        // يُحفظ الارتداد لا لتُعاد المحاولة عند كلّ إقلاع: الجهاز الذي
                        // رفض التأثير مرّة سيرفضه دائمًا، وتكرار الرسالة إزعاجٌ بلا فائدة
                        persistBurn(false)
                        _message.value = Message.BurnUnsupported
                    }.onFailure {
                        onFailure()
                        return@addListener
                    }
                }
            }
            _isReady.value = true
            // بعد الربط لا قبله: الحالة هي ما يفتح العدسة، والربط على سجلٍّ ساكن
            // ثمّ رفعُه هو الترتيب الذي يضمن ألّا تُفتح العدسة قبل اكتمال المجموعة
            syncLifecycle()
            // وضع التصوير يُطبَّق بعد أن تصير كاميرا: `CameraControl` لا يوجد قبلها
            applyScene()
            watchScene()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * ارتباطٌ جديد يعني `CameraControl` جديدًا وتعويض إضاءةٍ عاد إلى الصفر، فالفهرس
     * المطبَّق سابقًا لم يعد يصف الجهاز. تصفيرُه هنا يمنع أن تظنّ [applyScene] أنّ
     * ما تريده مضبوطٌ أصلًا فتسكت وقد عادت العدسة إلى إضاءة النهار.
     */
    private fun adoptCamera(camera: Camera) {
        boundCamera = camera
        appliedExposureIndex = null
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

        val rolling = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED &&
            sessionActive
        if (rolling) {
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
    }
}

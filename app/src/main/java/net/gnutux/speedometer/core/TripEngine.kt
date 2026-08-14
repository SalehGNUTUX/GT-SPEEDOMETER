package net.gnutux.speedometer.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.core.camera.HudSnapshot
import net.gnutux.speedometer.core.location.LocationEngine
import net.gnutux.speedometer.core.location.SpeedFilter
import net.gnutux.speedometer.core.media.MediaRepository
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.trip.GpxWriter
import net.gnutux.speedometer.core.trip.TripRecorder
import net.gnutux.speedometer.core.trip.TripStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** دورة النبضة: خانة الثواني وحدها هي ما يُعرض، فأكثرُ من مرّةٍ في الثانية رسمٌ بلا أثر */
private const val TICK_PERIOD_MS = 1_000L

/**
 * الغراء بين مصدر الموقع والمرشّح والمسجّل. نسخة واحدة تعيش في [net.gnutux.speedometer.SpeedoApp]،
 * تشترك فيها الواجهة والخدمة الأمامية — حقن يدوي بلا إطار.
 */
class TripEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** التفضيلات تُنشأ أوّلًا: جلسة الكاميرا تقرأ منها لحظة إنشائها */
    val settings = AppSettings(context, scope)

    val location = LocationEngine(context)
    val recorder = TripRecorder()
    val media = MediaRepository(context)
    val camera = CameraSession(context, media, settings)
    private val filter = SpeedFilter()

    private var collectJob: Job? = null

    private val _profile = MutableStateFlow(VehicleProfile.DEFAULT)
    val profile = _profile.asStateFlow()

    /** السرعة الحيّة، تعمل حتى قبل بدء الرحلة كي يرى المستعمل أن الجهاز يقيس فعلًا */
    private val _liveSpeedMps = MutableStateFlow(0f)
    val liveSpeedMps = _liveSpeedMps.asStateFlow()

    fun setProfile(p: VehicleProfile) {
        // الاختيار يُحفظ: ملفّ المركبة يضبط مدى القرص وعتبة التوقّف وشدّة التنعيم،
        // أي القياس نفسه، فعودته إلى الافتراضيّ عند كلّ إقلاع تغيّر الأرقام صامتةً
        settings.setVehicleName(p.name)
        _profile.value = p
        filter.setProfile(p)
        recorder.setProfile(p)
        // مدى القرص وعتبة التحذير تغيّرا، فتُدفَع لقطة فورًا كي لا تبقى الطبقة
        // المحروقة على مدى المركبة السابقة حتّى وصول العيّنة التالية
        pushHud(_liveSpeedMps.value)
    }

    /**
     * الطبقة المحروقة في الفيديو تُرسم في خيط الكاميرا، فلا تقرأ الحالة بنفسها:
     * نناولها لقطةً جاهزة عند كلّ تغيّر، فيبقى الرسم بلا قفلٍ ولا تبعيّةٍ عكسيّة
     * على المحرّك، وتبقى قاعدة «الفيديو نظيف والمسار منفصل» قائمةً بالفصل ذاته.
     */
    private fun pushHud(smoothedMps: Float) {
        val trip = recorder.state.value
        val p = _profile.value
        camera.updateHud(
            HudSnapshot(
                speedKmh = smoothedMps * 3.6f,
                distanceKm = trip.distanceKm,
                maxSpeedKmh = trip.maxSpeedKmh,
                // المتوسّط من [net.gnutux.speedometer.core.trip.TripState] لا من حسابٍ
                // ثانٍ هنا: هو المسافة على زمن الحركة، وكلاهما مشتقٌّ من فروق
                // `elapsedRealtimeNanos` (القاعدة الأولى). ونسخةٌ محلّيّة كانت ستُخرج
                // في الفيديو رقمًا يخالف ما تعرضه شاشتا العدّاد والرحلات.
                avgSpeedKmh = trip.avgSpeedKmh,
                durationMs = trip.elapsedMs,
                gaugeMaxKmh = p.gaugeMaxKmh,
                warnKmh = p.defaultWarnKmh,
            )
        )
    }

    init {
        // استعادة المركبة المحفوظة. تُقرأ عبر التدفّق لأنّ القرص لا يُجيب فورًا،
        // والقيمة الأولى هي الافتراضيّ ريثما يصل المحفوظ.
        scope.launch {
            settings.vehicleName.collect { name ->
                if (name.isEmpty()) return@collect
                val saved = VehicleProfile.entries.firstOrNull { it.name == name } ?: return@collect
                if (saved != _profile.value) {
                    _profile.value = saved
                    filter.setProfile(saved)
                    recorder.setProfile(saved)
                    pushHud(_liveSpeedMps.value)
                }
            }
        }

        // نبضة المدّة. `_state` كان لا ينبعث إلّا بوصول عيّنة، فالعدّاد على الشاشة
        // وفي الطبقة المحروقة كان يتجمّد كلّما تجمّد الاستقبال — وهو أسوأ ما يكون
        // في النفق والمرآب، حيث المستعمل أحوج ما يكون إلى دليلٍ على أنّ الجهاز يعمل.
        //
        // النطاق نطاق المحرّك نفسه (`Dispatchers.Main.immediate`) لا خيطًا جديدًا،
        // وهو عين النطاق الذي تجري فيه `onSample`: فلا تتشابك كتابتان على `_state`
        // إذ لا تتداخل روتينتان مشتركتان على مرسِلٍ واحد إلّا عند نقطة تعليق.
        //
        // و`collectLatest` على الحالة وحدها هي المفتاح: كلّ انتقالٍ يُلغي حلقة
        // النبض السابقة، فتموت النبضة تلقائيًّا عند «إيقاف» و«إنهاء» و«تصفير»
        // أيًّا كان من أمر بها — الواجهة أو مربّع الإعدادات السريعة أو خروج التطبيق.
        scope.launch {
            recorder.state
                .map { it.status }
                .distinctUntilChanged()
                .collectLatest { status ->
                    if (status != TripStatus.RUNNING) return@collectLatest
                    while (true) {
                        // انتظارٌ إلى حدّ الثانية التالية لا ألف ملّي ثانية عمياء:
                        // الرقم المعروض ينقلب عند ثانيته الحقيقيّة فلا يتراكم انزياح
                        delay(TICK_PERIOD_MS - recorder.elapsedNowMs() % TICK_PERIOD_MS)
                        recorder.refreshElapsed()
                        // الطبقة المحروقة تُرسم من لقطةٍ لا من الحالة، فلولا دفعُها
                        // هنا لبقيت ساعة الفيديو واقفةً بينما ساعة الشاشة تعدّ
                        pushHud(_liveSpeedMps.value)
                    }
                }
        }
    }

    /**
     * تشغيل مصدر الموقع. الاستدعاء مُتكرَّرٌ بلا ضرر عمدًا: تناديه الواجهة عند منح
     * الإذن، ومربّع الإعدادات السريعة، والخدمة الأماميّة عند إقلاعها — وكلٌّ منها
     * قد يكون الأوّل. [collectJob] يعيش على نطاق التطبيق لا على نطاق شاشة، فزوالُ
     * الواجهة لا يقطع الجمع، وهو شرط أن يبقى المسار يُكتب والعدّاد يتحدّث والطبقة
     * المحروقة صادقة بينما التطبيق في الخلفيّة.
     */
    fun startLocation(): Boolean {
        if (collectJob == null) {
            collectJob = scope.launch {
                location.samples.collect { sample ->
                    if (!filter.accepts(sample)) return@collect
                    val smoothed = filter.update(sample.speedMps)
                    _liveSpeedMps.value = smoothed
                    recorder.onSample(sample, smoothed)
                    pushHud(smoothed)
                }
            }
        }
        return location.start()
    }

    /**
     * لا يناديها اليوم أحد، وهذا مقصود: إطفاء الموقع عند مغادرة الواجهة كان يعني
     * رحلةً بلا نقاط وفيديو بلا سرعة. الإطفاء الوحيد المشروع هو موت العمليّة.
     */
    fun stopLocation() {
        location.stop()
        collectJob?.cancel()
        collectJob = null
        _liveSpeedMps.value = 0f
    }

    fun startTrip() {
        filter.reset()
        recorder.start()
    }

    fun pauseTrip() = recorder.pause()
    fun resumeTrip() = recorder.resume()

    /**
     * يُنهي الرحلة ويكتب ملف GPX. يعيد null إن لم تُسجَّل نقاط.
     *
     * @param videoName اسم أوّل ملفّ فيديو رافق الرحلة. اسمٌ لا ملفّ، لأنّ التسجيل
     *   يذهب إلى MediaStore على أندرويد 10 فما فوق فلا `File` له أصلًا.
     */
    fun finishTrip(videoName: String? = null): File? {
        recorder.finish()
        val points = recorder.points
        if (points.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val gpx = File(tracksDir, "trip-$stamp.gpx")
        val anchor = recorder.videoAnchorNanos
        val start = recorder.trackStartNanos
        val offsetMs = if (anchor != null && start != null) (anchor - start) / 1_000_000 else null

        GpxWriter.write(
            file = gpx,
            name = "رحلة $stamp",
            points = points,
            trackStartUtcMillis = recorder.trackStartUtcMillis,
            trackStartNanos = start,
            videoFileName = videoName,
            durationMs = recorder.state.value.elapsedMs,
            videoOffsetMs = offsetMs,
        )
        return gpx
    }

    fun resetTrip() = recorder.reset()

    // ===== ما يوجب بقاء الخدمة الأماميّة =====

    /** رحلةٌ قائمة: جاريةً كانت أو موقوفةً مؤقّتًا — كلتاهما تنتظر نقاطًا */
    val isTripActive: Boolean
        get() = recorder.state.value.status == TripStatus.RUNNING ||
            recorder.state.value.status == TripStatus.PAUSED

    /**
     * الخدمة الأماميّة تحيا لأحد سببين لا واحد: رحلةٌ تُقاس، أو جلسة تصويرٍ تمسك
     * العدسة. كان الشرط الأوّل وحده، فمن أطفأ «بدء رحلة مع التسجيل» ثمّ صوّر ثمّ
     * غادر التطبيق وجد أندرويد قد أغلق عليه الكاميرا — لا خدمةَ تحميها.
     *
     * قراءة تزامنيّة لا تدفّق: القرار يُتَّخذ في اللحظة نفسها التي يلمس فيها
     * المستخدم الزرّ، وتدفّقٌ يتأخّر دورةً كان سيُطلق خدمةً ثمّ يقتلها في الحال.
     */
    val needsForegroundService: Boolean
        get() = isTripActive || camera.isSessionActive.value

    /** تُنادى لحظة بدء التسجيل فعليًا، على نفس محور زمن عيّنات الموقع */
    fun markVideoStart() = recorder.markVideoStart(SystemClock.elapsedRealtimeNanos())

    val tracksDir: File get() = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
}

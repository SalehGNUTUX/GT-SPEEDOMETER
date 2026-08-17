package net.gnutux.speedometer.core

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.gnutux.speedometer.core.alert.AlertAction
import net.gnutux.speedometer.core.alert.SpeedAlert
import net.gnutux.speedometer.core.alert.SpeedAlertPlayer
import net.gnutux.speedometer.core.alert.SpeedScale
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.core.camera.HudSnapshot
import net.gnutux.speedometer.core.location.LocationEngine
import net.gnutux.speedometer.core.location.SpeedFilter
import net.gnutux.speedometer.core.media.MediaRepository
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AlertTone
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

    /**
     * مدى القرص وعتباته وحدّ السائق — جوابٌ واحد تقرؤه الشاشات الأربع والطبقة
     * المحروقة. اشتقاقه في [SpeedScale.of] وحدها، فلا تحسبه شاشةٌ لنفسها.
     *
     * `StateFlow` لا `Flow`: الطبقة المحروقة تُدفَع من [pushHud] وهو نداءٌ متزامن
     * على خيط الموقع، فيحتاج القيمة **الآن**. و`Eagerly` كي تكون جاهزةً قبل أوّل
     * عيّنة.
     */
    val speedScale: StateFlow<SpeedScale> =
        combine(_profile, settings.speedLimitKmh) { p, limit -> SpeedScale.of(p, limit) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                SpeedScale.of(VehicleProfile.DEFAULT, AppSettings.NO_SPEED_LIMIT),
            )

    /**
     * التنبيه في المحرّك لا في الشاشة، وهذا شرطُ صحّته: [TripEngine] هو من يرصد
     * السرعة ويعيش على نطاق التطبيق، فيبقى التنبيه عاملًا والتطبيق في الخلفيّة أو
     * في النافذة المصغّرة أو والشاشة مطفأة. تركيبةٌ في `@Composable` كانت تصمت عند
     * أوّل انطفاءٍ للشاشة — أي حين يحتاجها السائق.
     */
    private val alert = SpeedAlert()
    private val alertPlayer = SpeedAlertPlayer(context)

    /**
     * مستوى مجرى «المنبّه» في النظام صفر؟ عندئذٍ لن يُسمع التنبيه مهما ضُبطت شدّته
     * عندنا، لأنّ شدّتنا نسبةٌ من ذلك المجرى لا مستوًى مطلق.
     *
     * قراءةٌ عند الطلب لا `StateFlow` محفوظ: المستعمل قد يرفع المستوى من إعدادات
     * النظام وشاشتُنا مفتوحة، وقيمةٌ مخزَّنة كانت ستُبقي التحذير معروضًا بعد زوال
     * سببه — أو تُخفيه بعد حدوثه.
     */
    fun isAlarmStreamMuted(): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getStreamVolume(AudioManager.STREAM_ALARM) == 0
    }.getOrDefault(false)

    /**
     * سماع نغمةٍ قبل اختيارها. الشدّة من التفضيلات لا من وسيط: المعاينة يجب أن تكون
     * بعينها ما سيُسمع على الطريق، وشدّةٌ أخرى في المعاينة تُضلّل من يختار.
     */
    fun previewAlert(tone: AlertTone) {
        alertPlayer.preview(tone, settings.alertVolume.value)
    }

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
        val scale = speedScale.value
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
                // المدى والعتبة والحدّ من الاشتقاق الواحد لا من ملفّ المركبة مباشرةً:
                // المواصفة توجب أن يكون المحروق هو المرسوم، فلو أخذت الشاشة مداها من
                // حدّ السائق وأخذه الملفّ من المركبة لخرج قوسان مختلفان لمشهدٍ واحد
                gaugeMaxKmh = scale.gaugeMaxKmh,
                warnKmh = scale.warnKmh,
                limitKmh = scale.limitKmh,
            )
        )
    }

    /**
     * التنبيه الصوتيّ.
     *
     * **تبدّل شرط الدخول في 0.9.4.** كانت تُنادى للعيّنات التي تمرّ ببوّابة الدقّة
     * في [SpeedFilter.accepts] وحدها، وكان يُقال هنا إنّ شرط «لا تنبيه والإشارة
     * رديئة» مستوفًى قبل الدخول. وتبيّن أنّ ذلك كان يُسكت التنبيه حيث يجب أن ينطق:
     * رداءةُ دقّة التموضع لا تعني رداءة السرعة حين تأتي السرعة من الشريحة — انظر
     * شرح البوّابة في [startLocation] — فصارت تُنادى للمرفوضة أيضًا ما دامت سرعتها
     * من الشريحة.
     *
     * ويبقى شرط «لا تنبيه والمركبة واقفة» أدناه، وهو الشرط الذي يحمي فعلًا من
     * الصفير الكاذب.
     *
     * القرار كلّه في [SpeedAlert.onSample]، وهذه الدالّة غلافٌ يجمع الشروط ويناول
     * الناتج إلى المشغّل.
     */
    private fun onSpeedForAlert(smoothedMps: Float, nowNanos: Long) {
        val limit = settings.speedLimitKmh.value
        if (!settings.speedAlertEnabled.value || limit <= 0) {
            // الخيار مطفأ أو لا حدّ: لا نُبقي حالةً معلّقة تنفجر صفيرةً عند إعادة
            // التفعيل، والمورد الصوتيّ يُحرَّر فلا يبقى مسار صوتٍ محجوزًا بلا عمل
            alert.reset()
            alertPlayer.release()
            return
        }
        // النغمة تُهيَّأ الآن لا عند العبور: `SoundPool.load` غير متزامنة، وأوّل صفيرةٍ
        // تُطلب قبل تمام فكّ الترميز صفيرةٌ تضيع — وهي أهمّها، صفيرةُ العبور. والنداء
        // لا يفعل شيئًا بعد أوّل مرّة، فثمنه نظرةٌ في خريطة لكلّ عيّنة
        val tone = settings.alertTone.value
        alertPlayer.prepare(tone)

        // واقفة: العدّاد يصفّر ما دون عتبة التوقّف، ومع ذلك نشترطها صراحةً — تذبذبُ
        // التموضع وقوفًا يُخرج قفزاتٍ كاذبة، وصفيرةٌ والسيّارة ساكنة تُفقد الثقة كلّها
        if (smoothedMps <= _profile.value.stopThresholdMps) {
            alert.reset()
            return
        }
        val action = alert.onSample(smoothedMps * 3.6f, limit, nowNanos)
        // النغمة والشدّة تُقرآن لحظة التشغيل من `‎.value`: من بدّلهما في الإعدادات
        // وسيّارتُه سائرة يسمع الجديد في الصفيرة التالية بلا إعادة تشغيل
        if (action != AlertAction.SILENT) {
            alertPlayer.play(action, tone, settings.alertVolume.value)
        }
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

        // تبدّل الحدّ يغيّر مدى القرص وعتبته وموضع علامته الحمراء، فتُدفَع لقطةٌ
        // فورًا: الطبقة المحروقة ترسم من اللقطة لا من الحالة، فلولا هذا لبقي قوس
        // الملفّ على المدى القديم حتّى وصول العيّنة التالية — وقد لا تصل في نفق
        scope.launch {
            speedScale.collect { pushHud(_liveSpeedMps.value) }
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
                    // بوّابة الدقّة تحمي **المسار** لا السرعة: نقطةٌ دقّتها أربعون
                    // مترًا تزيد المسافة كيلومتراتٍ وهميّة وتُعوّج الخطّ في الخريطة.
                    // أمّا السرعة فمن إزاحة دوبلر في الإشارة لا من فرق موضعين،
                    // ودقّتها مستقلّة عن دقّة التموضع.
                    //
                    // وكان الرفض يُسقط العيّنة كلّها، فيقع أمران معًا تحت الأشجار
                    // وبين الأبراج: العدّاد يتجمّد على آخر رقمٍ سبق الرفض فيرى
                    // السائق «‎62‎» ثابتةً وهي كذبة، والتنبيه يُحرَم العيّنات فلا
                    // يصفّر أصلًا — وهذا وجهٌ من وجوه «التنبيه أحيانًا لا يعمل»،
                    // وأسوؤها لأنّ الشاشة تشهد بالتجاوز والصوت صامت. بل إنّ جهازًا
                    // لا يبلّغ دقّةً أصلًا — فتصير `accuracyM` تسعمئةً وتسعًا
                    // وتسعين — كان يُرفض على الدوام فلا تنبيه فيه البتّة.
                    //
                    // فالفصل الآن صريح: المرفوضة لا تُكتب في المسار بحال، لكنّها إن
                    // جاءت سرعتها من الشريحة حُسبت في العدّاد والطبقة والتنبيه.
                    val accepted = filter.accepts(sample)
                    if (!accepted && !sample.speedFromChip) return@collect
                    val smoothed = filter.update(sample.speedMps)
                    _liveSpeedMps.value = smoothed
                    if (accepted) recorder.onSample(sample, smoothed)
                    pushHud(smoothed)
                    // زمن العيّنة لا زمن الآن: كلاهما على محور `elapsedRealtimeNanos`،
                    // وزمن العيّنة هو اللحظة التي كانت فيها السرعة هذه فعلًا
                    onSpeedForAlert(smoothed, sample.elapsedRealtimeNanos)
                }
            }
        }
        // يُقرأ قبل البدء لا بعده: [LocationEngine.start] تسجّل مزوّديها مرّةً، فتبديل
        // التفضيل بعدها لا يسري إلّا على تشغيلٍ لاحق — وهو مقبول، فالإعداد يخصّ أوّل
        // ثوانٍ من الجلسة لا مجراها.
        location.fastFirstFix = settings.fastFirstFix.value
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
        // مسار التحرير: لا عيّنات بعد اليوم فلا صفير، والمجمّع يمسك عيّناتٍ مفكوكة
        // الترميز ومسارَ صوتٍ في النظام فلا يُترك معلّقًا
        alert.reset()
        alertPlayer.release()
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

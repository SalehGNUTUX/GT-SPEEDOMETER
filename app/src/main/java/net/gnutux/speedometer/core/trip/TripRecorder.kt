package net.gnutux.speedometer.core.trip

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.gnutux.speedometer.core.location.SpeedSample
import net.gnutux.speedometer.core.profile.VehicleProfile
import kotlin.math.max

/**
 * يجمّع عيّنات الموقع في رحلة: مسافة، زمن، أقصى سرعة، ومسار كامل.
 *
 * كل الأزمنة من [SpeedSample.elapsedRealtimeNanos]. لا تستعمل ساعة الحائط هنا أبدًا:
 * مزامنة الشبكة قد تقفز بها ثوانيَ فتفسد المسافة والزمن ومحاذاة الفيديو معًا.
 *
 * تمييزٌ لازم بين زمنين: **المسافة وزمن الحركة** مشتقّان من العيّنات لأنّهما لا
 * يُعرفان بغير قمر صناعيّ، أمّا **المدّة** فخاصّيّة ساعةٍ لا خاصّيّة استقبال: من
 * ضغط «ابدأ» في مرآب فرأى `00:00:00` ثابتة استنتج أنّ الرحلة لم تبدأ أصلًا، وهو
 * استنتاجٌ سليم — ساعةُ إيقافٍ لا تدقّ هي ساعةٌ مطفأة. فالمدّة تُقاس من
 * [SystemClock.elapsedRealtimeNanos] مباشرةً، وهو المحور الوحيد المسموح (القاعدة 1)
 * وهو الصواب هنا بعينه: لا يقفز بتغيير المستعمل للساعة، ويواصل العدّ والجهاز نائم.
 */
class TripRecorder(private var profile: VehicleProfile = VehicleProfile.DEFAULT) {

    private val _state = MutableStateFlow(TripState())
    val state = _state.asStateFlow()

    private val _points = mutableListOf<SpeedSample>()
    val points: List<SpeedSample> get() = _points

    private var previous: SpeedSample? = null
    private var previousSpeedMps = 0f

    /** لحظة بدء تسجيل الفيديو على نفس محور الزمن — مفتاح المزامنة كلّه */
    var videoAnchorNanos: Long? = null
        private set

    /** لحظة أول عيّنة في الرحلة، مرجع كل الإزاحات */
    var trackStartNanos: Long? = null
        private set

    var trackStartUtcMillis: Long = 0L
        private set

    /**
     * بداية القطعة الجارية الحاليّة، و`null` إذا لم تكن الرحلة جارية. فصلُ «بداية
     * القطعة» عن «بداية الرحلة» هو ما يجعل زمن الإيقاف المؤقّت غير محسوب: كلّ
     * إيقافٍ يطوي قطعته في [accruedMs] ثمّ يمحو البداية، فلا يعدّ أحدٌ ما بين
     * القطعتين.
     */
    private var segmentStartNanos: Long? = null

    /** مجموع القطع المنقضية بالملّي ثانية — ما استقرّ ولا يتغيّر إلّا بطيّ قطعةٍ جديدة */
    private var accruedMs = 0L

    fun setProfile(p: VehicleProfile) {
        profile = p
    }

    /**
     * المدّة في هذه اللحظة. تُقرأ خارج المسجّل أيضًا (نبضة المحرّك) كي تُعرض قيمةٌ
     * صادقة بين عيّنتين، أو بلا عيّنةٍ أصلًا.
     */
    fun elapsedNowMs(): Long {
        val startedAt = segmentStartNanos ?: return accruedMs
        return accruedMs + (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
    }

    /** يطوي القطعة الجارية في المجموع. آمنٌ عند التكرار: البداية تُمحى فلا تُحسب مرّتين */
    private fun sealSegment() {
        val startedAt = segmentStartNanos ?: return
        accruedMs += (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
        segmentStartNanos = null
    }

    fun start() {
        _points.clear()
        previous = null
        previousSpeedMps = 0f
        videoAnchorNanos = null
        trackStartNanos = null
        trackStartUtcMillis = 0L
        accruedMs = 0L
        // الساعة تدقّ من لحظة اللمس لا من أوّل قمرٍ يُرى: التثبيت قد يتأخّر دقيقة
        segmentStartNanos = SystemClock.elapsedRealtimeNanos()
        _state.value = TripState(status = TripStatus.RUNNING)
    }

    fun pause() {
        if (_state.value.status != TripStatus.RUNNING) return
        previous = null // كي لا تُحسب فجوة التوقّف مسافةً عند المتابعة
        sealSegment()
        _state.value = _state.value.copy(status = TripStatus.PAUSED, elapsedMs = accruedMs)
    }

    fun resume() {
        if (_state.value.status != TripStatus.PAUSED) return
        segmentStartNanos = SystemClock.elapsedRealtimeNanos()
        _state.value = _state.value.copy(status = TripStatus.RUNNING)
    }

    fun finish() {
        // قد تُنهى الرحلة وهي موقوفة مؤقّتًا: القطعة مطويّة سلفًا و[sealSegment] لا
        // يفعل شيئًا حينها، فلا يُضاف زمن التوقّف إلى المدّة المحفوظة
        sealSegment()
        _state.value = _state.value.copy(status = TripStatus.FINISHED, elapsedMs = accruedMs)
    }

    fun reset() {
        _points.clear()
        previous = null
        previousSpeedMps = 0f
        videoAnchorNanos = null
        trackStartNanos = null
        accruedMs = 0L
        segmentStartNanos = null
        _state.value = TripState()
    }

    /**
     * تُنادى من نبضة المحرّك كي تصل المدّة إلى الواجهة والإشعار والطبقة المحروقة
     * بين العيّنات. لا تعمل إلّا على رحلةٍ جارية: نبضةٌ متأخّرة وصلت بعد «إنهاء»
     * لا يجوز أن تبعث الرحلة من جديد ولا أن تحرّك رقمًا استقرّ.
     */
    fun refreshElapsed() {
        val current = _state.value
        if (current.status != TripStatus.RUNNING) return
        val elapsed = elapsedNowMs()
        if (elapsed == current.elapsedMs) return
        _state.value = current.copy(elapsedMs = elapsed)
    }

    fun markVideoStart(elapsedRealtimeNanos: Long) {
        videoAnchorNanos = elapsedRealtimeNanos
    }

    fun clearVideoAnchor() {
        videoAnchorNanos = null
    }

    /** إزاحة العيّنة عن بداية الفيديو بالملّي ثانية، أو null إن لم يكن ثمّة فيديو */
    fun videoOffsetMsOf(sample: SpeedSample): Long? {
        val anchor = videoAnchorNanos ?: return null
        return (sample.elapsedRealtimeNanos - anchor) / 1_000_000
    }

    /**
     * @param smoothedMps السرعة بعد التنعيم — هي المعتمدة في الإحصاءات كي لا ترفع
     *        قفزةٌ واحدة شاذّة رقمَ «أقصى سرعة» إلى قيمة لم تحدث.
     */
    fun onSample(sample: SpeedSample, smoothedMps: Float) {
        val current = _state.value
        if (current.status != TripStatus.RUNNING) return

        if (trackStartNanos == null) {
            trackStartNanos = sample.elapsedRealtimeNanos
            trackStartUtcMillis = sample.utcMillis
        }
        _points += sample

        val prev = previous
        var distance = current.distanceM
        var moving = current.movingTimeMs

        if (prev != null) {
            val dtSec = (sample.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            if (dtSec > 0.0) {
                val isMoving = smoothedMps >= profile.stopThresholdMps
                if (isMoving) {
                    moving += (dtSec * 1000).toLong()
                    val step = distanceBetween(prev, sample)
                    // سقف معقول: قفزة تحديد الموقع قد تعطي مئات الأمتار في ثانية
                    val plausible = max(smoothedMps, previousSpeedMps) * dtSec * 1.5 + 5.0
                    if (step <= plausible) distance += step
                }
            }
        }

        previous = sample
        previousSpeedMps = smoothedMps

        _state.value = current.copy(
            speedMps = smoothedMps,
            distanceM = distance,
            // المسافة وزمن الحركة من فروق العيّنات — لا سبيل إليهما بغيرها. أمّا
            // المدّة فتُقرأ من الساعة: جمعُ الفروق كان يُسقط كلّ ما سبق أوّل عيّنة
            // وكلّ انقطاعٍ في الإشارة من العدّ
            elapsedMs = elapsedNowMs(),
            movingTimeMs = moving,
            maxSpeedMps = max(current.maxSpeedMps, smoothedMps),
            pointCount = _points.size,
        )
    }

    private fun distanceBetween(a: SpeedSample, b: SpeedSample): Double {
        val out = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0].toDouble()
    }
}

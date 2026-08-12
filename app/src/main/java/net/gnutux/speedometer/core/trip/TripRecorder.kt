package net.gnutux.speedometer.core.trip

import android.location.Location
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

    fun setProfile(p: VehicleProfile) {
        profile = p
    }

    fun start() {
        _points.clear()
        previous = null
        previousSpeedMps = 0f
        videoAnchorNanos = null
        trackStartNanos = null
        trackStartUtcMillis = 0L
        _state.value = TripState(status = TripStatus.RUNNING)
    }

    fun pause() {
        if (_state.value.status != TripStatus.RUNNING) return
        previous = null // كي لا تُحسب فجوة التوقّف مسافةً عند المتابعة
        _state.value = _state.value.copy(status = TripStatus.PAUSED)
    }

    fun resume() {
        if (_state.value.status != TripStatus.PAUSED) return
        _state.value = _state.value.copy(status = TripStatus.RUNNING)
    }

    fun finish() {
        _state.value = _state.value.copy(status = TripStatus.FINISHED)
    }

    fun reset() {
        _points.clear()
        previous = null
        previousSpeedMps = 0f
        videoAnchorNanos = null
        trackStartNanos = null
        _state.value = TripState()
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
        var elapsed = current.elapsedMs
        var moving = current.movingTimeMs

        if (prev != null) {
            val dtSec = (sample.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            if (dtSec > 0.0) {
                elapsed += (dtSec * 1000).toLong()
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
            elapsedMs = elapsed,
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

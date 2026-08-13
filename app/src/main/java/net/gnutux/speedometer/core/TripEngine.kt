package net.gnutux.speedometer.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.core.camera.HudSnapshot
import net.gnutux.speedometer.core.location.LocationEngine
import net.gnutux.speedometer.core.location.SpeedFilter
import net.gnutux.speedometer.core.media.MediaRepository
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.trip.GpxWriter
import net.gnutux.speedometer.core.trip.TripRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الغراء بين مصدر الموقع والمرشّح والمسجّل. نسخة واحدة تعيش في [net.gnutux.speedometer.SpeedoApp]،
 * تشترك فيها الواجهة والخدمة الأمامية — حقن يدوي بلا إطار.
 */
class TripEngine(private val context: Context) {

    val location = LocationEngine(context)
    val recorder = TripRecorder()
    val media = MediaRepository(context)
    val camera = CameraSession(context, media)
    private val filter = SpeedFilter()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private val _profile = MutableStateFlow(VehicleProfile.DEFAULT)
    val profile = _profile.asStateFlow()

    /** السرعة الحيّة، تعمل حتى قبل بدء الرحلة كي يرى المستعمل أن الجهاز يقيس فعلًا */
    private val _liveSpeedMps = MutableStateFlow(0f)
    val liveSpeedMps = _liveSpeedMps.asStateFlow()

    fun setProfile(p: VehicleProfile) {
        _profile.value = p
        filter.setProfile(p)
        recorder.setProfile(p)
        pushHud(_liveSpeedMps.value)
    }

    /** يُغذّي الطبقة المحروقة في الفيديو بالقيم الحيّة بعد تحديث المسجّل */
    private fun pushHud(smoothedMps: Float) {
        val trip = recorder.state.value
        val p = _profile.value
        camera.updateHud(
            HudSnapshot(
                speedKmh = smoothedMps * 3.6f,
                distanceKm = trip.distanceKm,
                maxSpeedKmh = trip.maxSpeedKmh,
                durationMs = trip.elapsedMs,
                gaugeMaxKmh = p.gaugeMaxKmh,
                warnKmh = p.defaultWarnKmh,
            )
        )
    }

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

    /** يُنهي الرحلة ويكتب ملف GPX. يعيد null إن لم تُسجَّل نقاط. */
    fun finishTrip(videoFile: File? = null): File? {
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
            videoFileName = videoFile?.name,
            videoOffsetMs = offsetMs,
        )
        return gpx
    }

    fun resetTrip() = recorder.reset()

    /** تُنادى لحظة بدء التسجيل فعليًا، على نفس محور زمن عيّنات الموقع */
    fun markVideoStart() = recorder.markVideoStart(SystemClock.elapsedRealtimeNanos())

    val tracksDir: File get() = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
}

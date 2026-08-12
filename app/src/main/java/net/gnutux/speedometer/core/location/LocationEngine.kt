package net.gnutux.speedometer.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * مصدر الموقع.
 *
 * نقرأ من [LocationManager] ومزوّد GPS مباشرة، لا من FusedLocationProvider الخاصّ
 * بخدمات جوجل، لسببين: أوّلهما أن أداة القياس تحتاج أعلى معدّل تسلّمه الشريحة وعدد
 * الأقمار الخام، والمزوّد المدمج يخفي الاثنين؛ وثانيهما أن هذا يُبقي التطبيق عاملًا
 * على أجهزة بلا خدمات جوجل.
 *
 * نطلب minTime = 0 لأن بعض الشرائح تسلّم 5-10 هرتز، وأغلبها يسلّم 1 هرتز مهما طلبنا.
 * المعدّل المتحقّق يُقاس ويُعرض في [GnssInfo.updateHz] بدل ادّعاء رقم لا نملكه.
 *
 * المصدر الاحتياطي: [LocationManager.FUSED_PROVIDER] — وهو مزوّد **إطاريّ** في أندرويد 12
 * فما فوق، لا علاقة له بخدمات جوجل، فلا يكسر ما سبق. لا يُستعمل إلّا إذا انقطع GPS
 * أكثر من [FUSED_TAKEOVER_MS] (نفق، أو ازدحام مبانٍ)، وعيّناته تُوسَم بمصدرها في
 * ملفّ GPX كي لا تختلط بعيّنات القياس الدقيق. الفكرة مقتبسة من مشروع
 * Status-Bar-Tachometer (رخصة MIT).
 */
class LocationEngine(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private val _samples = MutableSharedFlow<SpeedSample>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples = _samples.asSharedFlow()

    private val _gnss = MutableStateFlow(GnssInfo())
    val gnss = _gnss.asStateFlow()

    private var running = false
    private var previous: Location? = null
    private var lastGpsFixMs = 0L

    /** آخر فترات الوصول بالنانو ثانية، لحساب المعدّل المتحقّق */
    private val intervals = ArrayDeque<Long>()

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val isProviderEnabled: Boolean
        get() = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    // LocationListenerCompat من androidx تتكفّل بالدوالّ التي صارت default في أندرويد 11:
    // تنفيذها يدويًّا ضروريّ وإلّا سقط التطبيق على أندرويد 8-10 بـ AbstractMethodError،
    // وهذه الواجهة تكفينا المسألة رسميًّا.
    private val gpsListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            lastGpsFixMs = SystemClock.elapsedRealtime()
            onFix(location, SpeedSample.PROVIDER_GPS)
        }

        override fun onProviderDisabled(provider: String) {
            _gnss.value = GnssInfo()
            previous = null
        }
    }

    private val fusedListener = LocationListenerCompat { location ->
        // لا يتدخّل إلّا حين ينقطع GPS فعليًّا
        if (SystemClock.elapsedRealtime() - lastGpsFixMs > FUSED_TAKEOVER_MS) {
            onFix(location, SpeedSample.PROVIDER_FUSED)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            _gnss.value = _gnss.value.copy(
                satellitesUsed = used,
                satellitesVisible = status.satelliteCount,
            )
        }

        override fun onStopped() {
            _gnss.value = GnssInfo()
        }
    }

    /** يُبطل الإشارة إن انقطع الوصول، وإلا بقي العدّاد يعرض آخر قيمة كأنها حيّة */
    private val staleWatcher = object : Runnable {
        override fun run() {
            if (!running) return
            val ageMs = SystemClock.elapsedRealtime() - lastFixMs
            if (lastFixMs != 0L && ageMs > STALE_MS) {
                intervals.clear()
                _gnss.value = _gnss.value.copy(hasFix = false, updateHz = 0f, accuracyM = Float.NaN)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private var lastFixMs = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission) return false
        running = true
        previous = null
        lastFixMs = 0L
        lastGpsFixMs = 0L
        intervals.clear()
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                gpsListener,
                Looper.getMainLooper(),
            )
            lm.registerGnssStatusCallback(gnssCallback, handler)
        }.onFailure {
            running = false
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    0L,
                    0f,
                    fusedListener,
                    Looper.getMainLooper(),
                )
            }
        }
        handler.postDelayed(staleWatcher, 1_000)
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(staleWatcher)
        runCatching { lm.removeUpdates(gpsListener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { lm.removeUpdates(fusedListener) }
        }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        _gnss.value = GnssInfo()
        previous = null
    }

    private fun onFix(location: Location, provider: String) {
        val nowNanos = location.elapsedRealtimeNanos
        val prev = previous

        // السرعة من الشريحة أدقّ بكثير من اشتقاقها من فرق الموضع، لأنها تُقاس
        // بإزاحة دوبلر في الإشارة لا بقسمة مسافة على زمن
        val speedMps: Float
        val fromChip: Boolean
        if (location.hasSpeed() && location.speed >= 0f) {
            speedMps = location.speed
            fromChip = true
        } else if (prev != null) {
            val dtSec = (nowNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            speedMps = if (dtSec > 0.05) (location.distanceTo(prev) / dtSec).toFloat() else 0f
            fromChip = false
        } else {
            speedMps = 0f
            fromChip = false
        }

        if (prev != null) {
            val deltaNanos = nowNanos - prev.elapsedRealtimeNanos
            if (deltaNanos > 0) {
                intervals.addLast(deltaNanos)
                while (intervals.size > RATE_WINDOW) intervals.removeFirst()
            }
        }
        previous = location
        lastFixMs = SystemClock.elapsedRealtime()

        val hz = if (intervals.isEmpty()) 0f else {
            val meanNanos = intervals.sum().toDouble() / intervals.size
            if (meanNanos > 0) (1_000_000_000.0 / meanNanos).toFloat() else 0f
        }

        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        _gnss.value = _gnss.value.copy(
            accuracyM = accuracy,
            updateHz = hz,
            hasFix = true,
        )

        _samples.tryEmit(
            SpeedSample(
                speedMps = speedMps,
                speedFromChip = fromChip,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeM = if (location.hasAltitude()) location.altitude else 0.0,
                bearingDeg = if (location.hasBearing()) location.bearing else 0f,
                accuracyM = if (accuracy.isNaN()) 999f else accuracy,
                elapsedRealtimeNanos = nowNanos,
                utcMillis = location.time,
                provider = provider,
            )
        )
    }

    private companion object {
        const val RATE_WINDOW = 10
        const val STALE_MS = 4_000L
        const val FUSED_TAKEOVER_MS = 10_000L
    }
}

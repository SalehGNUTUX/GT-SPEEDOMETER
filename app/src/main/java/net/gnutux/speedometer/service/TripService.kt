package net.gnutux.speedometer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.gnutux.speedometer.MainActivity
import net.gnutux.speedometer.R
import net.gnutux.speedometer.SpeedoApp
import net.gnutux.speedometer.ui.Fmt

/**
 * خدمة أمامية تُبقي القياس حيًّا والشاشة مطفأة أو التطبيق في الخلفية،
 * وتعرض السرعة الحالية رقمًا في شريط الحالة.
 *
 * النوع مصرَّح به في البيان (location|camera) لأن أندرويد 14 فما فوق يرفض
 * تشغيل الخدمة بدونه.
 */
class TripService : LifecycleService() {

    private var lastShownSpeed = -1

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        observeTrip()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startAsForeground() {
        val notification = buildNotification(0, "0.00")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun observeTrip() {
        val engine = (application as SpeedoApp).engine
        lifecycleScope.launch {
            combine(engine.liveSpeedMps, engine.recorder.state) { speed, trip -> speed to trip }
                .collect { (speedMps, trip) ->
                    val kmh = (speedMps * 3.6f).toInt()
                    // لا نُعيد بناء الإشعار إلا حين يتغيّر الرقم المعروض فعلًا
                    if (kmh == lastShownSpeed) return@collect
                    lastShownSpeed = kmh
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(kmh, Fmt.distance(trip.distanceKm)))
                }
        }
    }

    private fun buildNotification(speedKmh: Int, distanceKm: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_trip_body, distanceKm, "$speedKmh"))
            .setContentText(getString(R.string.notif_trip_title))
            .setSmallIcon(SpeedIcon.forSpeed(speedKmh))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_trip),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "trip"
        const val NOTIF_ID = 1001
    }
}

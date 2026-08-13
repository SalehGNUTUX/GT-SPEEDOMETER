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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.gnutux.speedometer.MainActivity
import net.gnutux.speedometer.R
import net.gnutux.speedometer.SpeedoApp
import net.gnutux.speedometer.core.TripEngine
import net.gnutux.speedometer.core.trip.TripState
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.Fmt

/**
 * خدمة أمامية تُبقي القياس والتصوير حيّين والشاشة مطفأة أو التطبيق في الخلفية،
 * وتعرض السرعة الحالية رقمًا في شريط الحالة.
 *
 * النوع مصرَّح به في البيان (location|camera) لأن أندرويد 14 فما فوق يرفض
 * تشغيل الخدمة بدونه.
 *
 * جديد في 0.4.0 — **عمر الخدمة صار لسببين لا لسببٍ واحد**: كانت تحيا مع الرحلة
 * وحدها، فمن أطفأ «بدء رحلة مع التسجيل» ثمّ صوّر بلا رحلة بقي بلا خدمة، وأغلق
 * أندرويد عليه الكاميرا أوّلَ ما غادر التطبيق. الآن تحيا ما دام أحدهما قائمًا،
 * وتُعلن نوعها بحسب ما يجري: الموقع دائمًا، والكاميرا تُضاف أثناء التصوير وحده،
 * لأنّ إعلان نوعٍ لا نستعمله دعوى كاذبة على النظام.
 *
 * وهي التي تُنهي نفسها حين يسقط السببان معًا، ولا تنتظر أمرًا من الواجهة: إيقاف
 * التسجيل قد يقع والتطبيق في الخلفيّة ولا شاشة تُصدر الأمر.
 */
class TripService : LifecycleService() {

    /** أنواعُ الواجهة المعلَنة الآن؛ لا نُعيد الإعلان إلّا حين تتبدّل فعلًا */
    private var shownTypes = 0

    private var observing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = (application as SpeedoApp).engine
        // خمس ثوانٍ يمهلها النظام قبل أن يقتلنا، فالترقية أوّل ما نفعل. ومن الحالة
        // الجارية لا من أصفار: الأمر الثاني (بدء تسجيلٍ فوق رحلةٍ قائمة) كان يُعيد
        // الإشعار إلى «0.00 كم» حتى تصل العيّنة التالية
        apply(snapshotOf(engine))
        // الإصلاح الملاحيّ بعد الترقية لا قبلها: بصفتنا خدمةً أماميّةً من نوع
        // الموقع نملك الوصول ونحن في الخلفيّة، وقبلها قد يُرفض الطلب صامتًا.
        // والاستدعاء مأمون التكرار — قد تكون الواجهة سبقتنا إليه.
        engine.startLocation()
        if (!observing) {
            observing = true
            observe(engine)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * لقطةٌ واحدة تجمع كلّ ما يُغيّر الإشعار أو عمر الخدمة. المقارنة عليها كاملةً
     * تُغني عن حراسة كلّ حقلٍ على حدة، وتمنع إعادةَ بناء الإشعار عشرَ مرّاتٍ في
     * الثانية لأنّ السرعة تذبذبت في خانةٍ عشريّة لا تُعرض أصلًا.
     */
    private data class Snapshot(
        val speedKmh: Int,
        val distanceKm: String,
        val trip: Boolean,
        val recording: Boolean,
    )

    /** القراءة التزامنيّة للحالة الجارية، لِما قبل أوّل انبعاثٍ من التدفّق */
    private fun snapshotOf(engine: TripEngine): Snapshot = snapshotOf(
        speedMps = engine.liveSpeedMps.value,
        trip = engine.recorder.state.value,
        recording = engine.camera.isSessionActive.value,
    )

    private fun snapshotOf(speedMps: Float, trip: TripState, recording: Boolean) = Snapshot(
        speedKmh = (speedMps * 3.6f).toInt(),
        distanceKm = Fmt.distance(trip.distanceKm),
        trip = trip.status == TripStatus.RUNNING || trip.status == TripStatus.PAUSED,
        recording = recording,
    )

    private fun observe(engine: TripEngine) {
        lifecycleScope.launch {
            combine(
                engine.liveSpeedMps,
                engine.recorder.state,
                engine.camera.isSessionActive,
            ) { speedMps, trip, recording -> snapshotOf(speedMps, trip, recording) }
                .distinctUntilChanged()
                .collect { snapshot -> apply(snapshot) }
        }
    }

    private fun apply(snapshot: Snapshot) {
        if (!snapshot.trip && !snapshot.recording) {
            // سقط السببان: لا نبقى إشعارًا معلّقًا في الشريط ولا خدمةً تستهلك.
            // وتُصفَّر الشارة قبل ذلك: `stopSelf` لا تُهلك النسخة في الحال، فلو جاء
            // أمرُ بدءٍ جديد قبل `onDestroy` وجد `shownTypes` قديمًا فظنّ نفسه
            // مرقّى وترك `startForeground` — والنظام يقتل من لم يرقِّ في خمس ثوانٍ.
            shownTypes = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        promote(snapshot)
    }

    /**
     * الترقية أو التحديث. تبديل الأنواع يمرّ بـ `startForeground` وحدها — و`notify`
     * لا تكفي له — أمّا تغيّر الرقم المعروض فيكفيه إشعارٌ محدَّث.
     *
     * إضافة نوع الكاميرا لا تقع إلّا ونحن في المقدّمة، لأنّ التصوير لا يبدأ إلّا
     * بلمسة المستخدم على الشاشة؛ ولو منعها النظام يومًا لبقينا خدمةَ موقعٍ حيّة
     * بدل أن نموت وسط تسجيل.
     */
    private fun promote(snapshot: Snapshot) {
        val notification = buildNotification(snapshot)
        val types = if (snapshot.recording) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (shownTypes == 0) {
                startForeground(NOTIF_ID, notification)
                shownTypes = types
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
            }
            return
        }
        if (types == shownTypes) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
            return
        }
        val promoted = runCatching { startForeground(NOTIF_ID, notification, types) }
        if (promoted.isSuccess) {
            shownTypes = types
            return
        }
        // رُفض النوع الجديد.
        if (shownTypes == 0) {
            // لم نرقَّ قطّ. محاولةٌ أخيرة بنوع الموقع وحده، فإن سقطت أيضًا وجب
            // `stopSelf` لا الصمت: النظام يعدّ خمس ثوانٍ لمن استُدعي بـ
            // `startForegroundService` ولم يرقِّ، ثمّ يقتل العمليّة.
            val fallback = runCatching {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
            if (fallback.isSuccess) {
                shownTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                stopSelf()
            }
            return
        }
        if (snapshot.recording) {
            // كنّا خدمةَ موقعٍ ورُفضت إضافة «كاميرا». التشبّث هنا كذبٌ على المستخدم:
            // أندرويد 11 فما فوق يقطع الكاميرا عن تطبيقٍ في الخلفيّة لا تحمل خدمته
            // هذا النوع، فالتسجيل ميّتٌ لا محالة عند أوّل مغادرة. نوقفه الآن ونحن
            // نملك كتابة ذيله، ونُخبر، بدل بترٍ يقع بعد حين بلا تفسير.
            (application as SpeedoApp).engine.camera.stopRecording()
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        // النسخة قد تُبعث من جديد بأمرٍ لاحق؛ شارةٌ قديمة تعني ترقيةً لا تقع
        shownTypes = 0
        super.onDestroy()
    }

    private fun buildNotification(snapshot: Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(R.string.notif_trip_body, snapshot.distanceKm, "${snapshot.speedKmh}")
            )
            .setContentText(stateText(snapshot))
            .setSmallIcon(SpeedIcon.forSpeed(snapshot.speedKmh))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * السطر الذي يقول **لماذا** نحن هنا. من يرى إشعارًا لا يعرف سببه يظنّه عالقًا
     * فيقتل التطبيق، وقتلُه أثناء التصوير هو عين ما نحمي منه.
     *
     * الفاصل هو فاصل `notif_trip_body` نفسه، كي يقرأ السطران قراءةً واحدة.
     */
    private fun stateText(snapshot: Snapshot): String {
        val recording = getString(R.string.notif_rec_title)
        val trip = getString(R.string.notif_trip_title)
        return when {
            snapshot.trip && snapshot.recording -> "$trip$SEPARATOR$recording"
            snapshot.recording -> recording
            else -> trip
        }
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
        const val SEPARATOR = " · "
    }
}

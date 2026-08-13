package net.gnutux.speedometer.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.SpeedoApp
import net.gnutux.speedometer.core.media.MediaItem
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.trip.TripLibrary
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.core.trip.TripTrack
import net.gnutux.speedometer.service.TripService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeedoViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = (app as SpeedoApp).engine

    val tripState = engine.recorder.state
    val gnss = engine.location.gnss
    val liveSpeedMps = engine.liveSpeedMps
    val profile = engine.profile

    /** التفضيلات: مصدر حقيقة واحد تقرؤه الشاشات مباشرة بلا وسيطٍ في هذا الصنف */
    val settings: AppSettings = engine.settings

    val camera = engine.camera
    val isRecording = engine.camera.isRecording
    val cameraMessage = engine.camera.message

    /**
     * جلسة التصوير أوسع من الترميز: تبدأ باللمسة وتنتهي بإغلاق آخر ملفّ. عليها
     * تُبنى قرارات العمر (الخدمة الأماميّة، تثبيت الكاميرا)، لا على [isRecording]
     * الذي يخدم الواجهة وحدها.
     */
    val isRecordingSession = engine.camera.isSessionActive

    /** حرق الطبقة داخل الفيديو اختيار المستعمل، والجلسة هي التي تحفظه */
    val burnOverlay = engine.camera.burnOverlay

    fun setBurnOverlay(enabled: Boolean) = engine.camera.setBurnOverlay(enabled)

    private val _lastSavedTrack = MutableStateFlow<File?>(null)
    val lastSavedTrack = _lastSavedTrack.asStateFlow()

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems = _mediaItems.asStateFlow()

    private val library = TripLibrary(engine.tracksDir)

    private val _trips = MutableStateFlow<List<TripTrack>>(emptyList())
    val trips = _trips.asStateFlow()

    /**
     * الرحلة المنتظرة للحذف. تختفي من [trips] فورًا ولا يُمسّ ملفّها حتّى تنقضي
     * المهلة — فالحذف الفوريّ مع «تراجع» كاذب أسوأ من غياب التراجع أصلًا.
     */
    private val _pendingTripDelete = MutableStateFlow<TripTrack?>(null)
    val pendingTripDelete = _pendingTripDelete.asStateFlow()

    private var deleteJob: Job? = null

    /** نطاق يعيش أطول من النموذج، لعمليّات القرص التي لا يجوز أن تُلغى في منتصفها */
    private val fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** مصدرُ الحكم واحد: المحرّك. الخدمة تقرأ منه أيضًا، فلا تتناقض قراءتان */
    val isTripActive: Boolean
        get() = engine.isTripActive

    fun onLocationPermissionGranted() {
        engine.startLocation()
    }

    fun setProfile(p: VehicleProfile) = engine.setProfile(p)

    fun toggleTrip() {
        when (tripState.value.status) {
            TripStatus.IDLE, TripStatus.FINISHED -> {
                engine.startTrip()
                syncService()
            }

            TripStatus.RUNNING -> engine.pauseTrip()
            TripStatus.PAUSED -> engine.resumeTrip()
        }
    }

    fun finishTrip() {
        // النيّة استُهلكت: لولا هذا لأنهى إيقافُ التسجيل لاحقًا رحلةً أخرى، أو كتب
        // نسخةً ثانية من الرحلة نفسها
        tripStartedByRecording = false
        _lastSavedTrack.value = engine.finishTrip(videoName = engine.camera.sessionFirstFile.value)
        // ليس إيقافًا مطلقًا: قد يكون التصوير مستمرًّا بعد انتهاء الرحلة، وقتلُ
        // الخدمة حينها يسحب حماية الكاميرا من تحت تسجيلٍ جارٍ
        syncService()
        // الملفّ كُتب لتوّه، فتُحدَّث القائمة كي يجد المستعمل رحلته في المكتبة فورًا
        refreshTrips()
    }

    // ===== المكتبة =====

    /** القراءة تمسّ القرص وتحلّل عشرات الملفّات، فلا تقع على الخيط الرئيس */
    fun refreshTrips() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { library.list() }
            // الرحلة المنتظرة للحذف ما زالت على القرص، فقراءةٌ ساذجة تُعيدها إلى
            // القائمة وشريط التراجع ما يزال يعدّ — تُستثنى صراحةً
            val pending = _pendingTripDelete.value?.file
            _trips.value = if (pending == null) all else all.filterNot { it.file == pending }
        }
    }

    /**
     * حذفٌ مؤجَّل بمهلة تراجع. المهلة من الإعدادات، وصفرٌ يعني حذفًا فوريًّا.
     *
     * لو ضغط المستعمل «حذف» على رحلةٍ ثانية والمهلة الأولى جارية، أُنجزت الأولى
     * على الفور: مهلتان متزامنتان تحتاجان شريطين، وشريطٌ واحد يكذب على إحداهما.
     */
    fun requestDeleteTrip(trip: TripTrack) {
        val seconds = settings.undoSeconds.value
        if (seconds <= 0) {
            deleteNow(trip)
            return
        }
        _pendingTripDelete.value?.let { previous ->
            deleteJob?.cancel()
            deleteNow(previous)
        }
        _pendingTripDelete.value = trip
        _trips.value = _trips.value.filterNot { it.file == trip.file }
        deleteJob = viewModelScope.launch {
            delay(seconds * 1000L)
            _pendingTripDelete.value = null
            deleteNow(trip)
        }
    }

    fun undoTripDelete() {
        deleteJob?.cancel()
        deleteJob = null
        _pendingTripDelete.value = null
        // الملفّ لم يُمسّ، فإعادة القراءة وحدها تكفي لعودة الصفّ إلى مكانه
        refreshTrips()
    }

    /**
     * المحو نفسه خارج [viewModelScope]: إغلاق الشاشة يُلغي نطاق النموذج، وقد يقع
     * الإلغاء قبل أن يمسّ القرصَ شيء — فيبقى ملفٌّ حذفه المستعمل فعلًا.
     */
    private fun deleteNow(trip: TripTrack) {
        fileScope.launch {
            library.delete(trip)
            withContext(Dispatchers.Main) { refreshTrips() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // «رحلة جارية — الخروج ينهي تتبّعها» وعدٌ في نصّ محاورة الخروج، ولم يكن
        // يُوفى: كانت النقاط تبقى في المسجّل بلا ملفّ GPX حتّى تُقتل العمليّة فتضيع،
        // وتبقى الخدمة والإشعار قائمين بلا نهاية.
        if (engine.camera.isSessionActive.value) engine.camera.stopRecording()
        if (isTripActive) finishTrip()
        // إغلاق الشاشة لا يُلغي نيّة الحذف: تُنجَز على نطاقٍ مستقلّ لا يُلغى معه،
        // وخارج الخيط الرئيس لأنّ محو ملفٍّ عمل قرص
        deleteJob?.cancel()
        _pendingTripDelete.value?.let { trip ->
            _pendingTripDelete.value = null
            fileScope.launch { library.delete(trip) }
        }
    }

    /** مشاركة ملفّ المسار تحتاج URI من مزوّد الملفّات، لا مسارًا مباشرًا */
    fun uriForTrack(file: File): Uri = engine.media.uriFor(file)

    fun resetTrip() {
        engine.resetTrip()
        _lastSavedTrack.value = null
    }

    // ===== الكاميرا =====

    /**
     * التسجيل ورحلة المسار وجهان لركوبةٍ واحدة: من يسجّل فيديو يريد مساره في السجلّ.
     * فإن كان الخيار مفعَّلًا بدأنا رحلةً مع التسجيل وأنهيناها معه، وإن كانت رحلةٌ
     * جارية أصلًا تُركت وشأنها — التسجيل لا يقطع ما لم يبدأه.
     */
    fun toggleRecording() {
        val autoTrip = settings.autoTripWithRecording.value
        // العبرة بالجلسة لا بالترميز: لمسةٌ ثانية قبل وصول حدث البدء يجب أن تُوقف
        // ما بدأ لا أن تفتح جلسةً ثانية فوقه
        if (engine.camera.isSessionActive.value) {
            engine.camera.stopRecording()
            if (autoTrip && tripStartedByRecording) {
                tripStartedByRecording = false
                finishTrip()
            }
            // ولا نقتل الخدمة هنا: الجلسة تبقى ممسكةً بالعدسة حتّى يُكتب ذيل الملفّ،
            // والخدمة هي من يُنهي نفسه حين يسقط السببان — ولو كنّا في الخلفيّة حينها
        } else {
            if (autoTrip && !isTripActive) {
                engine.startTrip()
                tripStartedByRecording = true
            }
            // التسجيل أوّلًا ثمّ الخدمة: علم الجلسة يُرفع تزامنيًّا داخل
            // `startRecording`، فلو سبقناه بالخدمة لوجدت الأسباب كلّها ساقطة
            // فأنهت نفسها في اللحظة التالية
            engine.camera.startRecording(onStarted = { engine.markVideoStart() })
            syncService()
        }
    }

    /** هل نحن من بدأ الرحلة الجارية؟ فلا نُنهي رحلةً بدأها المستعمل بيده */
    private var tripStartedByRecording = false

    fun consumeCameraMessage() = engine.camera.consumeMessage()

    /** يحفظ لقطة الشاشة كما هي: الكاميرا وفوقها الطبقة */
    fun saveScreenshot(bitmap: Bitmap, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                engine.media.saveImage(bitmap, "shot-$stamp.jpg") != null
            }
            if (ok) refreshMedia()
            onDone(ok)
        }
    }

    // ===== الوسائط =====

    fun refreshMedia() {
        viewModelScope.launch {
            _mediaItems.value = withContext(Dispatchers.IO) { engine.media.list() }
        }
    }

    suspend fun thumbnailOf(item: MediaItem): Bitmap? =
        withContext(Dispatchers.IO) { engine.media.thumbnail(item) }

    fun deleteMedia(item: MediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.media.delete(item) }
            refreshMedia()
        }
    }

    /**
     * الخدمة الأماميّة تتبع الحاجة لا الحدث: رحلةٌ قائمة أو جلسة تصوير — أيّهما
     * كان يُبقيها، وسقوطهما معًا يُنهيها.
     *
     * تُنادى دائمًا من لمسةٍ على الشاشة، أي والتطبيق في المقدّمة. هذا شرطٌ لا
     * تفصيل: أندرويد 14 فما فوق يمنع بدء خدمةٍ من نوع الكاميرا من الخلفيّة، فلا
     * يجوز أن يُشتقّ من هنا مسارٌ يُنادى من نداءٍ مرتجع في الخلفيّة. وحين ينتهي
     * التسجيل والتطبيق غائب، الخدمة هي من يُنهي نفسه لا نحن.
     */
    private fun syncService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, TripService::class.java)
        if (engine.needsForegroundService) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.stopService(intent)
        }
    }
}

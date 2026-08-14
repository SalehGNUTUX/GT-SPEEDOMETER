package net.gnutux.speedometer.core.map

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.util.LruCache
import android.view.KeyEvent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.gpx.CreateGpxBitmapParams
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult

/**
 * جسرٌ إلى تطبيق OsmAnd: نسأله أن يرسم لنا صورة خريطةٍ لمسار الرحلة.
 *
 * **لماذا جسرٌ أصلًا؟** خرائط OsmAnd بصيغة `.obf` متجهيّة، لا يرسمها osmdroid بحال،
 * ولا يُقرأ مجلّد OsmAnd على أندرويد 11 فما فوق. فالطريق الوحيد إلى تلك الخرائط —
 * وهي غالبًا الخرائط المحلّيّة الوحيدة على جهاز الراكب — أن يرسمها محرّكها بنفسه
 * ويسلّمنا الصورة. النتيجة صورةٌ ساكنة لا خريطةٌ تفاعليّة، وذلك حدُّ الواجهة لا
 * نقصٌ في التنفيذ.
 *
 * وأربعة قرارات تفسّر شكل الملفّ:
 *
 * — **الملفّ يُمرَّر بـ `Uri` لا بـ `File`**: ملفّات الرحلات في
 *   `Android/data/<حزمتنا>/files/tracks`، ولا يقرأ OsmAnd مجلّدنا الخاصّ. فنمنحه
 *   إذن قراءةٍ مؤقّتًا على عنوانٍ من [FileProvider] ثمّ نسحبه فور انتهاء الرسم.
 *
 * — **الربط مؤقّت**: `BIND_AUTO_CREATE` يوقظ عمليّة OsmAnd كاملةً. فلا نربط إلّا عند
 *   السؤال، ونفكّ بعد [IDLE_UNBIND_MS] من آخر استعمال. ولهذا أيضًا لا يُنشأ الجسر في
 *   `SpeedoApp.onCreate`: إيقاظ تطبيقٍ آخر عند كلّ إقلاعٍ ثمنٌ لا يدفعه من لم يفتح
 *   رحلةً أصلًا.
 *
 * — **الردّ غير متزامن وقد لا يأتي**: `getBitmapForGpx` تُعيد `false` أحيانًا، وقد
 *   تُعيد `true` ثمّ لا يُستدعى ردّ النداء أبدًا (خريطة المنطقة غير منزَّلة عنده،
 *   أو محرّكه ما زال يُهيَّأ). فالمهلة إلزاميّة، والفشل نظيفٌ يُعيد `null`.
 *
 * — **لا شيء منها يُسقط التطبيق**: غياب الحزمة، ونسخةٌ قديمة بلا هذه الدالّة
 *   (`NoSuchMethodError`), و`SecurityException` من إذنٍ لم يُمنح، و`DeadObjectException`
 *   حين تُقتل عمليّة OsmAnd أثناء الرسم — كلّها واقعة، وكلّها ملفوفة.
 */
class OsmAndBridge private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق بعمر العمليّة: فكّ الربط المؤجَّل لا يتبع شاشةً بعينها ولا رحلةً بعينها */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(OsmAndStatus())

    /** حالة الجسر كما تعرضها الإعدادات والخريطة معًا؛ مصدرٌ واحد فلا يختلف جوابان */
    val status: StateFlow<OsmAndStatus> = _status.asStateFlow()

    /** كلّ ما يمسّ الربط يمرّ من هنا: طلبان متزامنان على خدمةٍ واحدة يتنازعان الفكّ */
    private val lock = Mutex()

    /** محروسان بـ[lock] وحده */
    private var bound: Bound? = null
    private var idleUnbind: Job? = null

    /**
     * صورةٌ واحدة لكلّ (مسار × مقاس × لون).
     *
     * بلا خبيئةٍ يُعاد النداء عبر العمليّتين مع كلّ إعادة تركيب، وهو نداءٌ ثمنه ثوانٍ.
     * والحدّ ثلاثٌ لأنّ الصور بحجم الشاشة: أربعٌ منها تقارب عشرة ميغابايت.
     */
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    // ————————————————————————— الحالة —————————————————————————

    /**
     * إعادة الفحص من الصفر: يُنادى من الإعدادات بعد أن يثبّت المستعمل OsmAnd أو
     * يُفعّل واجهته الخارجيّة. ولا يكفي فحص الحزمة: «مثبَّت» غير «يستجيب».
     */
    fun recheck() {
        scope.launch {
            _status.value = OsmAndStatus(OsmAndState.CHECKING, _status.value.packageName)
            lock.withLock {
                idleUnbind?.cancel()
                idleUnbind = null
                // نطرح الربط القائم عمدًا: الشكوى التي دفعت المستعمل إلى «إعادة الفحص»
                // قد تكون في الربط نفسه، ففحصُه بإعادة استعماله لا يفحص شيئًا.
                unbindLocked()
                try {
                    ensureBoundLocked()
                } finally {
                    scheduleUnbindLocked()
                }
            }
        }
    }

    // ————————————————————————— الرسم —————————————————————————

    /**
     * يطلب من OsmAnd صورة الخريطة لملفّ GPX موجودٍ على القرص.
     *
     * @param gpxFile ملفّ الرحلة؛ يجب أن يقع تحت مسارٍ يعلنه مزوّد الملفّات (انظر
     *   `res/xml/file_paths.xml`) وإلّا تعذّر بناء عنوانه ورجعنا بـ`null`.
     * @return الصورة، أو `null` عند أيّ فشل — بلا رمي في أيّ حال.
     */
    suspend fun renderGpx(
        gpxFile: File,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        trackColor: Int,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val key = "${gpxFile.absolutePath}|$widthPx|$heightPx|$trackColor"
        cache.get(key)?.let { return it }

        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", gpxFile)
        }.getOrNull() ?: return null

        val bitmap = withService { service, pkg ->
            // المنح والسحب متلازمان: إذنٌ يبقى بعد الرسم وصولٌ دائم إلى ملفّ رحلةٍ
            // منحناه لتطبيقٍ آخر بلا حاجة.
            runCatching { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try {
                awaitBitmap(service, uri, widthPx, heightPx, density, trackColor)
            } finally {
                runCatching {
                    app.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    /**
     * الرسم من نقاطٍ في الذاكرة حين لا يملك المُستدعي ملفّ الرحلة نفسه.
     *
     * نكتب نسخةً صغيرة (إحداثيّات فقط) في مخبأ التطبيق ونمرّرها إلى OsmAnd: الصورة
     * المطلوبة خطُّ مسارٍ لا أكثر، ولا حاجة بها إلى الارتفاع ولا السرعة ولا الزمن.
     * والاسم مشتقٌّ من محتوى النقاط، فالرحلة الواحدة تُكتب مرّةً وتُقرأ بعدها.
     */
    suspend fun renderTrack(
        positions: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        trackColor: Int,
    ): Bitmap? {
        val file = withContext(Dispatchers.IO) { materialize(positions) } ?: return null
        return renderGpx(file, widthPx, heightPx, density, trackColor)
    }

    /**
     * نداءٌ واحد وانتظار ردّه.
     *
     * ردّ النداء يصل على خيط الرابط لا على خيطنا، و[CompletableDeferred] هو الجسر
     * بينهما. ونحن ننتظره بمهلةٍ لأنّ عدم وصوله حالةٌ عاديّة لا استثنائيّة: يكفي أن
     * تكون خريطة المنطقة غير منزَّلة عند OsmAnd فيصمت.
     */
    private suspend fun awaitBitmap(
        service: IOsmAndAidlInterface,
        uri: Uri,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        trackColor: Int,
    ): Bitmap? {
        val shot = CompletableDeferred<Bitmap?>()
        // تُنفَّذ الدوالّ التسع كلُّها: `Stub` صنفٌ مجرّد، وترك واحدةٍ منها لا يُصرَّف.
        val callback = object : IOsmAndAidlCallback.Stub() {
            override fun onSearchComplete(resultSet: MutableList<SearchResult>?) = Unit

            override fun onUpdate() = Unit

            override fun onAppInitialized() = Unit

            override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {
                shot.complete(runCatching { bitmap?.bitmap }.getOrNull())
            }

            override fun updateNavigationInfo(directionInfo: ADirectionInfo?) = Unit

            override fun onContextMenuButtonClicked(
                buttonId: Int,
                pointId: String?,
                layerId: String?,
            ) = Unit

            override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) = Unit

            override fun onKeyEvent(params: KeyEvent?) = Unit

            override fun onLogcatMessage(params: OnLogcatMessageParams?) = Unit
        }

        val params = CreateGpxBitmapParams(uri, density, widthPx, heightPx, trackColor)
        val accepted = runCatching { service.getBitmapForGpx(params, callback) }.getOrDefault(false)
        if (!accepted) return null
        return withTimeoutOrNull(RENDER_TIMEOUT_MS) { shot.await() }
    }

    /** ملفّ GPX مصغَّر في المخبأ. يُرجع `null` لمسارٍ لا يصنع خطًّا أصلًا. */
    private fun materialize(positions: List<Pair<Double, Double>>): File? {
        if (positions.size < 2) return null
        return runCatching {
            val dir = File(app.cacheDir, GPX_CACHE_DIR).apply { mkdirs() }
            val stamp = Integer.toHexString(positions.hashCode())
            val target = File(dir, "track-${positions.size}-$stamp.gpx")
            if (target.length() <= 0L) {
                // مخبأٌ لا مستودع: من فتح عشر رحلاتٍ لا يحتاج عشر نسخ.
                dir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP_CACHED_GPX)
                    ?.forEach { runCatching { it.delete() } }
                target.writeText(buildGpx(positions))
            }
            target
        }.getOrNull()
    }

    /** الأرقام بـ[Locale.US] دائمًا: فاصلةٌ عشريّة عربيّة تُخرج ملفًّا لا يقرؤه قارئ GPX */
    private fun buildGpx(positions: List<Pair<Double, Double>>): String {
        val sb = StringBuilder(positions.size * 56 + 256)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GT-SPEEDOMETER\"")
            .append(" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <trkseg>\n")
        for ((latitude, longitude) in positions) {
            sb.append("      <trkpt lat=\"")
                .append(String.format(Locale.US, "%.6f", latitude))
                .append("\" lon=\"")
                .append(String.format(Locale.US, "%.6f", longitude))
                .append("\" />\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    // ————————————————————————— الربط —————————————————————————

    /**
     * تنفيذ عملٍ على الخدمة، مع ضمان الربط قبله وجدولة الفكّ بعده.
     *
     * كلّه على [Dispatchers.IO] لا على خيط المُستدعي: `getBitmapForGpx` نداءٌ متزامن
     * يعبر بين عمليّتين ولا يعود حتّى تردّ الأخرى، و`bindService` قد يُقلع عمليّة
     * OsmAnd كاملةً. ومُستدعينا الأوّل مُركّبٌ يعمل على الخيط الرئيس.
     *
     * والفكّ في `finally`: من ألغى الشاشة أثناء الرسم لا يترك خدمةً مربوطةً إلى الأبد.
     */
    private suspend fun <T> withService(
        block: suspend (IOsmAndAidlInterface, String) -> T?,
    ): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            idleUnbind?.cancel()
            idleUnbind = null
            try {
                val live = ensureBoundLocked() ?: return@withLock null
                try {
                    block(live.service, live.pkg)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // `DeadObjectException` و`SecurityException` و`NoSuchMethodError`
                    // عند نسخةٍ قديمة: ثلاثتها تعني «لا صورة» لا «التطبيق معطوب».
                    markUnreachable(live.pkg)
                    null
                }
            } finally {
                scheduleUnbindLocked()
            }
        }
    }

    private suspend fun ensureBoundLocked(): Bound? {
        bound?.let { if (it.alive()) return it }
        unbindLocked()

        val pkg = installedPackage()
        if (pkg == null) {
            _status.value = OsmAndStatus(OsmAndState.MISSING)
            return null
        }
        val fresh = bindTo(pkg)
        if (fresh == null) {
            _status.value = OsmAndStatus(OsmAndState.UNREACHABLE, pkg)
            return null
        }
        bound = fresh
        _status.value = OsmAndStatus(OsmAndState.READY, pkg)
        return fresh
    }

    /**
     * ربطٌ واحد بمهلة.
     *
     * `bindService` تُرجع `true` بمعنى «قُبِل الطلب» لا «تمّ الربط»؛ والربط نفسه يصل
     * لاحقًا على الخيط الرئيس. وقد لا يصل أبدًا إن كانت الخدمة معطّلة عند المستعمل،
     * فننتظره بمهلةٍ ثمّ نفكّ ما بدأناه — رابطٌ مُعلَّق لا يُفكّ تسريبٌ يُنبّه عليه النظام.
     */
    private suspend fun bindTo(pkg: String): Bound? {
        val handshake = CompletableDeferred<IOsmAndAidlInterface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                handshake.complete(
                    runCatching { IOsmAndAidlInterface.Stub.asInterface(binder) }.getOrNull()
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            /** خدمةٌ ردّت بلا رابط: تعني «موجودة ولا تخدمك»، وهي حالة فشلٍ لا انتظار */
            override fun onNullBinding(name: ComponentName?) {
                handshake.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                handshake.complete(null)
            }
        }

        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        val accepted = runCatching {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!accepted) {
            runCatching { app.unbindService(connection) }
            return null
        }
        val service = try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) { handshake.await() }
        } catch (interrupted: Throwable) {
            // إلغاءٌ يقع ونحن ننتظر الربط يترك اتّصالًا مسجّلًا لا يملكه أحد: لا هو
            // في [bound] فيُفكّ مع الخمول، ولا هو معروفٌ لمن ألغى. والنظام يشكو تسريبه.
            runCatching { app.unbindService(connection) }
            throw interrupted
        }
        if (service == null) {
            runCatching { app.unbindService(connection) }
            return null
        }
        return Bound(pkg, connection, service)
    }

    private fun scheduleUnbindLocked() {
        idleUnbind = scope.launch {
            delay(IDLE_UNBIND_MS)
            lock.withLock { unbindLocked() }
        }
    }

    private fun unbindLocked() {
        val live = bound ?: return
        bound = null
        runCatching { app.unbindService(live.connection) }
    }

    private fun markUnreachable(pkg: String) {
        _status.value = OsmAndStatus(OsmAndState.UNREACHABLE, pkg)
    }

    /**
     * أوّل حزمة OsmAnd مثبَّتة.
     *
     * الترتيب مقصود: النسخة الكاملة أوّلًا فالمجّانيّة فنسخة المطوّرين. ولا يعمل هذا
     * على أندرويد 11 فما فوق إلّا بعناصر `<queries>` في البيان — بدونها يكذب
     * `PackageManager` ويقول «غير مثبَّت» عن تطبيقٍ قائم.
     */
    private fun installedPackage(): String? = CANDIDATE_PACKAGES.firstOrNull { pkg ->
        runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /** ربطٌ قائم: الاتّصال يلزم للفكّ، والحزمة تلزم لمنح إذن العنوان */
    private class Bound(
        val pkg: String,
        val connection: ServiceConnection,
        val service: IOsmAndAidlInterface,
    ) {
        /** عمليّة OsmAnd قد تموت ونحن مربوطون؛ الرابط الميّت يرمي عند أوّل نداء */
        fun alive(): Boolean =
            runCatching { service.asBinder().isBinderAlive }.getOrDefault(false)
    }

    companion object {
        /** فعل خدمة الواجهة الخارجيّة في OsmAnd، الإصدار الثاني */
        private const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        private val CANDIDATE_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

        /**
         * لاحقة سلطة مزوّد الملفّات؛ تطابق ما في البيان حرفيًّا.
         *
         * هي سلطة المشاركة نفسها لا سلطةٌ خاصّة بالجسر: عنصرا `<provider>` باسم
         * الصنف الواحد يُدمجان عند التركيب فلا يُحسم أيّ إعداد مساراتٍ يخدم أيّهما.
         * والتضييق يقع على العنوان الواحد الممنوح لا على المزوّد كلّه.
         */
        private const val AUTHORITY_SUFFIX = ".files"

        private const val GPX_CACHE_DIR = "osmand-gpx"
        private const val KEEP_CACHED_GPX = 2
        private const val CACHE_ENTRIES = 3

        /** الربط لحظيّ عادةً؛ ما تجاوز هذا فخدمةٌ لا تستجيب */
        private const val BIND_TIMEOUT_MS = 4_000L

        /** رسم خريطةٍ متجهيّة عملٌ ثقيل، لكنّ ثماني ثوانٍ حدُّ صبر من ينتظر خريطة */
        private const val RENDER_TIMEOUT_MS = 8_000L

        /** مهلة الخمول: من يفتح رحلتين متتاليتين لا يوقظ OsmAnd مرّتين */
        private const val IDLE_UNBIND_MS = 15_000L

        @Volatile
        private var instance: OsmAndBridge? = null

        /**
         * النسخة الوحيدة، وأوّل من يسألها يُطلق الفحص.
         *
         * ولا تُنشأ في `SpeedoApp.onCreate`: الفحص يوقظ عمليّة OsmAnd، وذلك ثمنٌ
         * لا يُدفع إلّا عند من فتح رحلةً أو شاشة الإعدادات فعلًا.
         */
        fun of(context: Context): OsmAndBridge =
            instance ?: synchronized(this) {
                instance ?: OsmAndBridge(context).also {
                    it.recheck()
                    instance = it
                }
            }
    }
}

/** حالة الجسر الأربع. الفرق بين [MISSING] و[UNREACHABLE] هو الفرق بين نصيحتين مختلفتين. */
enum class OsmAndState { CHECKING, MISSING, UNREACHABLE, READY }

/**
 * الحالة واسم الحزمة معًا.
 *
 * الاسم يُعرض في الإعدادات: «مثبَّت: net.osmand.plus» يفصل النسخة الكاملة عن
 * المجّانيّة عن نسخة المطوّرين حين يشكو المستعمل من سلوكٍ لا نراه.
 */
data class OsmAndStatus(
    val state: OsmAndState = OsmAndState.CHECKING,
    val packageName: String = "",
) {
    /** هل يُطلب منه رسمُ خريطةٍ الآن؟ */
    val usable: Boolean get() = state == OsmAndState.READY
}

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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
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
 * وستّة قرارات تفسّر شكل الملفّ:
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
 * — **الربط لا يكفي، والإذن شرطٌ ثانٍ**: `OsmandAidlServiceV2.getApi` يردّ `null` لكلّ
 *   نداءٍ من حزمةٍ لم يفعّلها المستعمل في «التطبيقات المتّصلة»، وأوّل اتّصالٍ بنا يسجّلنا
 *   عنده **معطَّلين**. فبعد كلّ ربطٍ نصافحه بـ`registerForOsmandInitListener`: رجوعها
 *   `false` دليلٌ قاطع على المنع لا على تأخّر التهيئة — انظر [handshake].
 *
 * — **لا صورة قبل التهيئة**: المصافحة نفسها تنتظر `onAppInitialized` بمهلة، فلا
 *   يُطلب الرسم من محرّكٍ ما زال يُقلع. وهذا وحده يُصلح الإقلاع البارد.
 *
 * — **الردّ غير متزامن وقد لا يأتي**: `getBitmapForGpx` قد تُعيد `true` ثمّ لا يُستدعى
 *   ردّ النداء أبدًا (خريطة المنطقة غير منزَّلة عنده). فالمهلة إلزاميّة، والفشل نظيفٌ
 *   يُعيد `null`.
 *
 * — **لا شيء منها يُسقط التطبيق**: غياب الحزمة، ونسخةٌ قديمة بلا هذه الدالّة
 *   (`NoSuchMethodError`)، و`SecurityException` من إذنٍ لم يُمنح، و`DeadObjectException`
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
     * صورةٌ واحدة لكلّ (مسار × مقاس × لون)، والحدّ بالبايت لا بالعدد.
     *
     * بلا خبيئةٍ يُعاد النداء عبر العمليّتين مع كلّ إعادة تركيب، وهو نداءٌ ثمنه ثوانٍ.
     * وصار الحدّ بالبايت منذ صرنا نطلب صورةً بأضعاف مقاس الإطار (انظر [detailFactor]):
     * «ثلاث صور» كانت عشرة ميغابايت فصارت مئةً وخمسين، وذلك يقتل العمليّة على جهازٍ
     * ضعيف قبل أن يُعرض شيء.
     */
    private val cache = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // ————————————————————————— الحالة —————————————————————————

    /**
     * إعادة الفحص من الصفر: يُنادى من الإعدادات بعد أن يثبّت المستعمل OsmAnd أو
     * يفعّلنا في «التطبيقات المتّصلة». ولا يكفي فحص الحزمة: «مثبَّت» غير «يستجيب»،
     * و«يستجيب» غير «أذِن لنا».
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
     * المقاس المُمرَّر هو **مقاس الإطار** لا مقاس الصورة: الصورة تُطلب بأضعافه
     * (انظر [detailFactor]) كي يبقى فيها ما يُقرَّب إليه، وعامل التضعيف يُعاد في
     * [OsmAndShot.detail] لأنّ العارض يحتاجه ليحدّ التقريب وليُسقط الإحداثيّات.
     *
     * ولا يُطلب شيء قبل أن تصير الحالة [OsmAndState.READY]: [withService] لا يُنفّذ
     * كتلتَه إلّا بعد مصافحةٍ ناجحة، فالإقلاع البارد ينتظر ولا يفشل.
     *
     * @param gpxFile ملفّ الرحلة؛ يجب أن يقع تحت مسارٍ يعلنه مزوّد الملفّات (انظر
     *   `res/xml/file_paths.xml`) وإلّا تعذّر بناء عنوانه ورجعنا بـ`null`.
     * @return الصورة وما يلزم لقراءتها، أو `null` عند أيّ فشل — بلا رمي في أيّ حال.
     */
    suspend fun renderGpx(
        gpxFile: File,
        frameWidthPx: Int,
        frameHeightPx: Int,
        density: Float,
        trackColor: Int,
    ): OsmAndShot? {
        if (frameWidthPx <= 0 || frameHeightPx <= 0) return null
        val detail = detailFactor(frameWidthPx, frameHeightPx)
        val widthPx = frameWidthPx * detail
        val heightPx = frameHeightPx * detail
        // الكثافة تُضاعَف مع المقاس عمدًا: لو تُركت على حالها لبقي صندوق البلاطات
        // نفسه بمقاسٍ ثلاثة أضعاف، فارتفع تقريب OsmAnd درجةً أو درجتين وخرجت صورةٌ
        // بمحتوًى آخر — أدقّ خرائطيًّا وأصغر خطًّا وأشدّ زحامًا حين تُلاءم الإطار.
        // ومضاعفتهما معًا تُبقي المحتوى كما هو وتزيد دقّته وحدها، وهو المطلوب من
        // صورةٍ تُعرض ملائمةً للإطار أوّلًا ثمّ تُقرَّب.
        val shotDensity = density * detail

        val key = "${gpxFile.absolutePath}|$widthPx|$heightPx|$trackColor"
        cache.get(key)?.let { return OsmAndShot(it, detail, widthPx, heightPx, shotDensity) }

        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", gpxFile)
        }.getOrNull() ?: return null

        val shot = withService { service, pkg ->
            // المنح والسحب متلازمان: إذنٌ يبقى بعد الرسم وصولٌ دائم إلى ملفّ رحلةٍ
            // منحناه لتطبيقٍ آخر بلا حاجة.
            runCatching { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try {
                val outcome = awaitBitmap(service, uri, widthPx, heightPx, shotDensity, trackColor)
                // رفضٌ بعد أن كانت الحالة `READY` يعني أنّ الإذن سُحب بين المصافحة
                // والنداء، لا أنّ الخدمة تعطّلت: النصيحتان مختلفتان تمامًا.
                if (outcome is Shot.Refused) _status.value = OsmAndStatus(OsmAndState.DENIED, pkg)
                outcome
            } finally {
                runCatching {
                    app.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        val bitmap = (shot as? Shot.Drawn)?.bitmap ?: return null
        cache.put(key, bitmap)
        return OsmAndShot(bitmap, detail, widthPx, heightPx, shotDensity)
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
        frameWidthPx: Int,
        frameHeightPx: Int,
        density: Float,
        trackColor: Int,
    ): OsmAndShot? {
        val file = withContext(Dispatchers.IO) { materialize(positions, trackColor) } ?: return null
        return renderGpx(file, frameWidthPx, frameHeightPx, density, trackColor)
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
    ): Shot {
        val drawn = CompletableDeferred<Bitmap?>()
        val callback = object : BridgeCallback() {
            override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {
                drawn.complete(runCatching { bitmap?.bitmap }.getOrNull())
            }
        }

        val params = CreateGpxBitmapParams(uri, density, widthPx, heightPx, trackColor)
        val accepted = runCatching { service.getBitmapForGpx(params, callback) }.getOrDefault(false)
        if (!accepted) return Shot.Refused
        val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) { drawn.await() }
        return if (bitmap != null) Shot.Drawn(bitmap) else Shot.Silent
    }

    /** ملفّ GPX مصغَّر في المخبأ. يُرجع `null` لمسارٍ لا يصنع خطًّا أصلًا. */
    private fun materialize(positions: List<Pair<Double, Double>>, trackColor: Int): File? {
        if (positions.size < 2) return null
        return runCatching {
            val dir = File(app.cacheDir, GPX_CACHE_DIR).apply { mkdirs() }
            val stamp = Integer.toHexString(positions.hashCode())
            // اللون في الاسم لأنّه صار يُكتب داخل الملفّ نفسه (انظر [osmAndExtensions]):
            // ملفٌّ مخبوءٌ بلونٍ قديم يُعيد خطًّا بلونٍ لم يُطلب.
            val target = File(dir, "track-${positions.size}-$stamp-$trackColor.gpx")
            if (target.length() <= 0L) {
                // مخبأٌ لا مستودع: من فتح عشر رحلاتٍ لا يحتاج عشر نسخ.
                dir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP_CACHED_GPX)
                    ?.forEach { runCatching { it.delete() } }
                target.writeText(buildGpx(positions, trackColor))
            }
            target
        }.getOrNull()
    }

    /** الأرقام بـ[Locale.US] دائمًا: فاصلةٌ عشريّة عربيّة تُخرج ملفًّا لا يقرؤه قارئ GPX */
    private fun buildGpx(positions: List<Pair<Double, Double>>, trackColor: Int): String {
        val sb = StringBuilder(positions.size * 56 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GT-SPEEDOMETER\"")
            .append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
            .append(" xmlns:osmand=\"").append(NS_OSMAND).append("\">\n")
        sb.append("  <trk>\n")
        // امتدادات المسار قبل `trkseg`: هكذا يرتّبها مخطَّط GPX 1.1.
        sb.append(osmAndExtensions("    ", trackColor))
        sb.append("    <trkseg>\n")
        for ((latitude, longitude) in positions) {
            sb.append("      <trkpt lat=\"")
                .append(String.format(Locale.US, "%.6f", latitude))
                .append("\" lon=\"")
                .append(String.format(Locale.US, "%.6f", longitude))
                .append("\" />\n")
        }
        sb.append("    </trkseg>\n  </trk>\n")
        // وامتدادات الجذر بعد `</trk>`: المخطّط يجعلها آخر أبناء `gpx`، وقارئ
        // OsmAnd متسلسلٌ لا يبالي بالترتيب فلا نخسر شيئًا بالالتزام به.
        sb.append(osmAndExtensions("  ", trackColor))
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * امتدادات OsmAnd للمظهر.
     *
     * **قرأتُ مصدره فلم أجد لأكثرها أثرًا في هذا المسار**: `TrackBitmapDrawer` يرسم
     * خطًّا مصمتًا وحسب، فلا أسهم ولا علامتَي بداية ونهاية مهما كُتب هنا، وعرض قلمه
     * ثابتٌ (‎4dp‎) لا يقرأ `width`. ولا يبقى منها عاملًا إلّا `color`، وهو
     * **يتقدّم** على اللون المُمرَّر في `CreateGpxBitmapParams` — فلذلك يُكتب باللون
     * نفسه لا بغيره، وإلّا خالف الخطُّ ما طُلب. وتُكتب البقيّة لأنّها لا تضرّ وتنفع من
     * فتح الملفّ في OsmAnd نفسه، لا لأنّنا نعوّل عليها: العلامات والأسهم نرسمها
     * بأنفسنا فوق الصورة.
     */
    private fun osmAndExtensions(indent: String, trackColor: Int): String {
        val hex = String.format(Locale.US, "#%08X", trackColor)
        return buildString {
            append(indent).append("<extensions>\n")
            append(indent).append("  <osmand:color>").append(hex).append("</osmand:color>\n")
            append(indent).append("  <osmand:width>bold</osmand:width>\n")
            append(indent).append("  <osmand:show_arrows>true</osmand:show_arrows>\n")
            append(indent).append("  <osmand:show_start_finish>true</osmand:show_start_finish>\n")
            append(indent).append("</extensions>\n")
        }
    }

    // ————————————————————————— الربط —————————————————————————

    /**
     * تنفيذ عملٍ على الخدمة، مع ضمان الربط والإذن قبله وجدولة الفكّ بعده.
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

    /**
     * ربطٌ صالحٌ للاستعمال، أو `null`.
     *
     * و«صالح» ثلاثة شروط لا شرط: حزمةٌ مثبَّتة، وخدمةٌ تُسلّم رابطًا، وإذنٌ تُثبته
     * المصافحة. ولكلٍّ منها حالتُه لأنّ لكلٍّ منها علاجًا غير علاج أخيه.
     */
    private suspend fun ensureBoundLocked(): Bound? {
        bound?.let { if (it.alive()) return if (it.authorized) it else null }
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
        if (!handshake(fresh.service)) {
            fresh.authorized = false
            _status.value = OsmAndStatus(OsmAndState.DENIED, pkg)
            return null
        }
        fresh.authorized = true
        _status.value = OsmAndStatus(OsmAndState.READY, pkg)
        return fresh
    }

    /**
     * المصافحة: تُثبت الإذن، ثمّ تنتظر التهيئة.
     *
     * `registerForOsmandInitListener` تمرّ بـ`getApi` نفسها التي تمرّ بها
     * `getBitmapForGpx`، ولا تُعيد `false` إلّا حين يردّ `getApi` بـ`null` — أي حين لا
     * نكون مأذونًا لنا. فهي كاشفٌ قاطع للمنع، بخلاف `getBitmapForGpx` التي تخلط المنع
     * بغيره من أسباب الفشل.
     *
     * وحين نكون مأذونًا لنا فهي إمّا تنادي `onAppInitialized` فورًا (المحرّك جاهز) أو
     * تسجّلنا عند `AppInitializer` فيصلنا الردّ عند تمام الإقلاع. وننتظره
     * [INIT_TIMEOUT_MS] لأنّ إقلاعًا باردًا على جهازٍ ضعيف يطول؛ وانقضاء المهلة لا
     * يعني منعًا — الإذن ثبت سلفًا — فنمضي، ومهلة الرسم بعدها هي الحكم.
     *
     * والردّ قد يصل **داخل** النداء نفسه على خيط الرابط، فيُبنى المُنتَظَر قبله لا بعده.
     */
    private suspend fun handshake(service: IOsmAndAidlInterface): Boolean {
        val initialized = CompletableDeferred<Unit>()
        val callback = object : BridgeCallback() {
            override fun onAppInitialized() {
                initialized.complete(Unit)
            }
        }
        val allowed = runCatching {
            service.registerForOsmandInitListener(callback)
        }.getOrDefault(false)
        if (!allowed) return false
        withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() }
        return true
    }

    /**
     * ربطٌ واحد بمهلة.
     *
     * `bindService` تُرجع `true` بمعنى «قُبِل الطلب» لا «تمّ الربط»؛ والربط نفسه يصل
     * لاحقًا على الخيط الرئيس. وقد لا يصل أبدًا إن كانت الخدمة معطّلة عند المستعمل،
     * فننتظره بمهلةٍ ثمّ نفكّ ما بدأناه — رابطٌ مُعلَّق لا يُفكّ تسريبٌ يُنبّه عليه النظام.
     */
    private suspend fun bindTo(pkg: String): Bound? {
        val connected = CompletableDeferred<IOsmAndAidlInterface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(
                    runCatching { IOsmAndAidlInterface.Stub.asInterface(binder) }.getOrNull()
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            /** خدمةٌ ردّت بلا رابط: تعني «موجودة ولا تخدمك»، وهي حالة فشلٍ لا انتظار */
            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                connected.complete(null)
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
            withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
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
        /** نتيجة المصافحة، تُحفظ فلا نصافح مرّتين على ربطٍ واحد */
        var authorized: Boolean = false

        /** عمليّة OsmAnd قد تموت ونحن مربوطون؛ الرابط الميّت يرمي عند أوّل نداء */
        fun alive(): Boolean =
            runCatching { service.asBinder().isBinderAlive }.getOrDefault(false)
    }

    /**
     * جواب `getBitmapForGpx` الثلاثيّ.
     *
     * الفرق بين [Refused] و[Silent] هو الفرق بين «سُحب إذننا» و«لا خريطة للمنطقة
     * عنده»، وللمستعمل في كلٍّ منهما نصيحةٌ غير الأخرى. ولا تقول `Bitmap?` وحدها ذلك.
     */
    private sealed interface Shot {
        class Drawn(val bitmap: Bitmap) : Shot
        object Refused : Shot
        object Silent : Shot
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

        /** فضاء أسماء امتدادات OsmAnd كما يكتبها هو في ملفّاته */
        private const val NS_OSMAND = "https://osmand.net"

        private const val GPX_CACHE_DIR = "osmand-gpx"
        private const val KEEP_CACHED_GPX = 2

        /** الربط لحظيّ عادةً؛ ما تجاوز هذا فخدمةٌ لا تستجيب */
        private const val BIND_TIMEOUT_MS = 4_000L

        /** إقلاعٌ باردٌ لمحرّك OsmAnd على جهازٍ ضعيف يبلغ هذا الحدّ ولا يتجاوزه غالبًا */
        private const val INIT_TIMEOUT_MS = 10_000L

        /** رسم خريطةٍ متجهيّة عملٌ ثقيل، لكنّ ثماني ثوانٍ حدُّ صبر من ينتظر خريطة */
        private const val RENDER_TIMEOUT_MS = 8_000L

        /** مهلة الخمول: من يفتح رحلتين متتاليتين لا يوقظ OsmAnd مرّتين */
        private const val IDLE_UNBIND_MS = 15_000L

        /**
         * عامل الدقّة المطلوب: صورةٌ بثلاثة أضعاف مقاس الإطار في كلّ محور.
         *
         * صورة OsmAnd ساكنة بحكم واجهته — لا مركز ولا تقريب في
         * `CreateGpxBitmapParams` — فالسبيل الوحيد إلى تقريبٍ لا يُهرِّئ الصورة أن
         * تُطلب أكبر من الإطار ويُقرَّب داخلها.
         */
        const val DETAIL_FACTOR = 3

        /** أربع بايتات للبكسل: `ARGB_8888` هي ما يرسم به OsmAnd (`MapRenderRepositories`) */
        private const val BYTES_PER_PIXEL = 4L

        /** سقف عدد البكسلات: أربعون ميغابكسل حدٌّ لا يُعبر بتخصيصٍ واحد بلا خطر */
        private const val MAX_BITMAP_PIXELS = 40_000_000L

        /**
         * سقف البايتات: ثمانيةٌ وأربعون ميغابايت.
         *
         * وهو الأشدّ من السقفين عمليًّا (اثنا عشر ميغابكسل)، وذلك مقصود:
         * `OutOfMemoryError` عند تخصيص صورةٍ بهذا الحجم على جهازٍ ضعيف ليس احتمالًا
         * نظريًّا، والتخصيص يقع عند OsmAnd لا عندنا — فيموت هو ولا يصلنا خبر.
         */
        private const val MAX_BITMAP_BYTES = 48 * 1024 * 1024

        /**
         * عامل الدقّة الذي يدخل في السقف لهذا الإطار.
         *
         * يُنزَّل درجةً درجة حتّى يدخل، ولا ينزل عن واحد: صورةٌ بمقاس الإطار خيرٌ من
         * لا صورة.
         */
        fun detailFactor(frameWidthPx: Int, frameHeightPx: Int): Int {
            var factor = DETAIL_FACTOR
            while (factor > 1) {
                val pixels =
                    frameWidthPx.toLong() * factor * (frameHeightPx.toLong() * factor)
                if (pixels <= MAX_BITMAP_PIXELS && pixels * BYTES_PER_PIXEL <= MAX_BITMAP_BYTES) {
                    break
                }
                factor--
            }
            return factor
        }

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

/**
 * ردّ نداءٍ فارغ يُشتقّ منه.
 *
 * `IOsmAndAidlCallback.Stub` صنفٌ مجرّد، فترك دالّةٍ من التسع لا يُصرَّف — ولا يعني
 * الجسرَ كلَّه منها إلّا اثنتان. فتُنفَّذ كلُّها هنا مرّةً واحدة، ويَرِث كلُّ نداءٍ ما يعنيه.
 */
private open class BridgeCallback : IOsmAndAidlCallback.Stub() {
    override fun onSearchComplete(resultSet: MutableList<SearchResult>?) = Unit

    override fun onUpdate() = Unit

    override fun onAppInitialized() = Unit

    override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) = Unit

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

/**
 * صورة OsmAnd ومعها ما لا تُقرأ بدونه.
 *
 * الصورة وحدها لا تكفي: من يعرضها يحتاج عامل التضعيف ليحدّ التقريب، ويحتاج المقاس
 * والكثافة اللذين طُلبت بهما ليُعيد بناء إسقاطها (انظر [projection]) فيضع العلامات في
 * مواضعها لا قريبًا منها.
 */
class OsmAndShot(
    val bitmap: Bitmap,
    val detail: Int,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
) {
    /** إسقاط هذه الصورة بعينها؛ `null` لمسارٍ لا نقاط له */
    fun projection(positions: List<Pair<Double, Double>>): OsmAndProjection? =
        OsmAndProjection.of(positions, widthPx, heightPx, density)
}

/**
 * إسقاط صورة OsmAnd، مُكرَّرًا حرفًا بحرف.
 *
 * **لماذا يُكرَّر ولا يُسأل عنه؟** واجهة `getBitmapForGpx` تُسلّم صورةً ولا تُسلّم معها
 * مركزًا ولا تقريبًا، فلو أردنا رسم علامةٍ فوقها لم نعرف أين تقع. والبديل عن التكرار
 * ألّا نرسم شيئًا — أو أن نرسم في موضعٍ خاطئ، وهو شرٌّ من الاثنين.
 *
 * والحساب مأخوذٌ من `TrackBitmapDrawer.createTileBox` و`RotatedTileBox` في مصدر
 * OsmAnd، وهو قصيرٌ محدَّد لا عشوائيّ فيه:
 *
 * 1. صندوق المسار: أدنى وأقصى خطّي الطول والعرض من نقاطه (`GpxFile.getRect`).
 * 2. المركز وسطُ الصندوق حسابيًّا لا وسطُه في مركاتور — هكذا `KQuadRect.centerX/Y`.
 * 3. التقريب صحيحٌ لا كسريّ: يبدأ من ‎15‎ فيصعد ما دام الصندوق داخلًا (بحدّ ‎17‎) ثمّ
 *    ينزل ما دام خارجًا (بحدّ ‎7‎). واللفّتان بهذا الترتيب حرفيًّا: الأولى تتجاوز
 *    الحدّ درجةً والثانية تردّها، وأيّ اختصارٍ «أذكى» يخالفها في الحالات الحدّيّة.
 * 4. البكسل: `(بلاطةُ النقطة − بلاطةُ المركز) × 256 × الكثافة + نصفُ المقاس`.
 *    و«الكثافة» هنا هي التي مرّرناها في `CreateGpxBitmapParams`، لا كثافة الشاشة.
 *
 * ولا دوران: `TrackBitmapDrawer` لا يضبط `setRotate`، فالصورة شماليّة دائمًا.
 */
class OsmAndProjection private constructor(
    private val zoom: Int,
    private val centerTileX: Double,
    private val centerTileY: Double,
    private val pixelsPerTile: Double,
    private val originX: Int,
    private val originY: Int,
) {
    /** موضع نقطةٍ بالبكسل داخل الصورة كما رسمها OsmAnd */
    fun xOf(longitude: Double): Float =
        ((tileX(zoom, longitude) - centerTileX) * pixelsPerTile + originX).toFloat()

    fun yOf(latitude: Double): Float =
        ((tileY(zoom, latitude) - centerTileY) * pixelsPerTile + originY).toFloat()

    companion object {
        private const val TILE_SIZE = 256.0
        private const val START_ZOOM = 15
        private const val MAX_ZOOM = 17
        private const val MIN_ZOOM = 7
        private const val MIN_LATITUDE = -85.0511
        private const val MAX_LATITUDE = 85.0511

        /**
         * @param widthPx و[heightPx] مقاس الصورة **كما طُلبت**، لا مقاس الإطار.
         * @param density الكثافة الممرَّرة في `CreateGpxBitmapParams` نفسها.
         */
        fun of(
            positions: List<Pair<Double, Double>>,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ): OsmAndProjection? {
            if (positions.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for ((latitude, longitude) in positions) {
                if (latitude < minLat) minLat = latitude
                if (latitude > maxLat) maxLat = latitude
                if (longitude < minLon) minLon = longitude
                if (longitude > maxLon) maxLon = longitude
            }
            val centerLatitude = (maxLat + minLat) / 2.0
            val centerLongitude = (minLon + maxLon) / 2.0
            val pixelsPerTile = TILE_SIZE * density
            val originX = (widthPx * 0.5).toInt()
            val originY = (heightPx * 0.5).toInt()

            // النقطتان اللتان يفحصهما OsmAnd: الزاوية العليا اليسرى (أقصى عرضٍ وأدنى
            // طول) والسفلى اليمنى. والتحويل إلى `Float` مقصود لا زائد: مقارنة OsmAnd
            // تقع على قيمةٍ أحاديّة الدقّة، وعندها وحدها يتقرّر التقريب في الحالات الحدّيّة.
            fun fits(candidate: Int): Boolean {
                val ox = tileX(candidate, centerLongitude)
                val oy = tileY(candidate, centerLatitude)
                val leftX = ((tileX(candidate, minLon) - ox) * pixelsPerTile + originX).toFloat()
                val topY = ((tileY(candidate, maxLat) - oy) * pixelsPerTile + originY).toFloat()
                val rightX = ((tileX(candidate, maxLon) - ox) * pixelsPerTile + originX).toFloat()
                val bottomY = ((tileY(candidate, minLat) - oy) * pixelsPerTile + originY).toFloat()
                return leftX >= 0f && leftX <= widthPx && topY >= 0f && topY <= heightPx &&
                    rightX >= 0f && rightX <= widthPx && bottomY >= 0f && bottomY <= heightPx
            }

            var zoom = START_ZOOM
            while (zoom < MAX_ZOOM && fits(zoom)) zoom++
            while (zoom >= MIN_ZOOM && !fits(zoom)) zoom--

            return OsmAndProjection(
                zoom = zoom,
                centerTileX = tileX(zoom, centerLongitude),
                centerTileY = tileY(zoom, centerLatitude),
                pixelsPerTile = pixelsPerTile,
                originX = originX,
                originY = originY,
            )
        }

        /** مركاتور كما في `MapUtils.getTileNumberX` */
        private fun tileX(zoom: Int, longitude: Double): Double {
            val powZoom = powZoom(zoom)
            val dz = (longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * powZoom
            return if (dz >= powZoom) powZoom - 0.01 else dz
        }

        /** مركاتور كما في `MapUtils.getTileNumberY` */
        private fun tileY(zoom: Int, latitude: Double): Double {
            val safe = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE)
            val radians = safe * PI / 180.0
            val eval = ln(tan(radians) + 1.0 / cos(radians))
            return (1.0 - eval / PI) / 2.0 * powZoom(zoom)
        }

        private fun powZoom(zoom: Int): Double = (1L shl zoom).toDouble()
    }
}

/**
 * حالات الجسر الخمس.
 *
 * والفرق بين [MISSING] و[UNREACHABLE] و[DENIED] هو الفرق بين ثلاث نصائح: «ثبّته»،
 * و«خدمته لا تردّ»، و«افتحه وفعّلنا في التطبيقات المتّصلة». والثالثة أكثرها وقوعًا
 * وأقلّها وضوحًا: OsmAnd يسجّل كلّ حزمةٍ تسأله أوّل مرّة **معطَّلة**، فأوّل اتّصالٍ بنا هو
 * نفسه ما يمنعنا، ولا يُرفع المنع إلّا بيد المستعمل.
 */
enum class OsmAndState { CHECKING, MISSING, UNREACHABLE, DENIED, READY }

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

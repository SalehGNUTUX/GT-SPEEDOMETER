package net.gnutux.speedometer.core.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.gnutux.speedometer.R

/**
 * تنزيل أرشيف بلاطاتٍ يشير إليه المستعمل برابطه.
 *
 * **لماذا لا «نزّل خريطة دولة»؟** لأنّ جلب البلاطات بالجملة من `tile.openstreetmap.org`
 * ممنوعٌ صراحةً في سياسة استعمالها — «الاستعمال دون اتّصال غير مسموح… وستُحجب بلا
 * إشعار» — ولا كتالوج حرًّا موثوقًا لأرشيفات ‎.mbtiles‎ مرتّبةً حسب الدولة. فزرٌّ اسمه
 * «نزّل خريطة بلدك» إمّا أن يخالف سياسة الخادم فيُحجب مستعملونا، وإمّا أن يعتمد على
 * مرآةٍ مجهولة العمر. والذي في أيدينا بلا خطر: **المستعمل يدلّنا على الأرشيف، ونحن
 * نُحسن جلبه**. ولهذا كلّ ما في هذا الملفّ حرّاسٌ حول نقلٍ واحد، لا كتالوج ولا جالبُ
 * بلاطات.
 *
 * وستّة قرارات تفسّر شكله:
 *
 * — **الحكم بالامتداد لا بنوع المحتوى**: خوادم المشاركة تردّ `text/html` أو
 *   `application/octet-stream` على أرشيفٍ سليم، وتردّ `application/octet-stream` على
 *   صفحة خطأ. فالامتداد يُقرأ من **مسار الرابط**، وصحّة الملفّ تُثبَت من رأسه بعد
 *   التنزيل لا من ترويسةٍ يكتبها الخادم.
 *
 * — **الرأس يُفحص قبل النقل إلى موضعه**: صفحة خطأ HTML بحجم ‎2‎ ك.ب باسم
 *   `country.mbtiles` في مجلّد الخرائط ليست عدمًا بل ضررًا: [OfflineMaps] تفتحها في
 *   كلّ مسحٍ عند كلّ إقلاع وتفشل. فما لم يبدأ بـ`SQLite format 3` أو بـ`PK` يُحذف.
 *
 * — **الاستئناف يشترط ‎206‎**: نطلب `Range` حين نجد جزءًا سابقًا، فإن ردّ الخادم ‎200‎
 *   فقد أرسل الملفّ **من أوّله** — وإلحاق ذلك بالجزء المحفوظ يبني ملفًّا مشوّهًا يمرّ
 *   من فحص الرأس ويفشل عند أوّل بلاطة. فالردّ ‎200‎ على طلب مدًى يعني: امحُ الجزء وابدأ.
 *
 * — **التحويل يُتبع بأيدينا**: `HttpURLConnection` لا تتبع تحويلًا يعبر من `http` إلى
 *   `https` ولا العكس، وأكثر روابط المرايا اليوم تفعل ذلك. ونشترط في الوجهة ما نشترطه
 *   في الأصل: `http` أو `https` لا غير.
 *
 * — **التقدّم مخنوق**: [StateFlow] يُعيد تركيب الواجهة مع كلّ إصدار. وإصدار حالةٍ مع
 *   كلّ كتلةٍ من ‎64‎ ك.ب على وصلةٍ سريعة آلافُ الإصدارات في الثانية، وهي تجمّد الشاشة
 *   لا «تُظهر تقدّمًا سلسًا». فالإصدار كلّ [PROGRESS_INTERVAL_NANOS] أو كلّ
 *   [PROGRESS_STEP_BYTES]، أيّهما سبق.
 *
 * — **‎.gz‎ يُفكّ ولا يُترك**: أكثر مرايا الأرشيفات توزّع `x.mbtiles.gz`، وملفٌّ مضغوط
 *   في مجلّد الخرائط لا يقرؤه [OfflineMaps] بحال. فالامتداد المقبول مركّبٌ يُحتكم فيه
 *   إلى ما قبل `.gz`، والفكّ تدفّقٌ من ملفٍّ إلى ملفّ — لا في الذاكرة، فأرشيفُ دولةٍ
 *   مفكوكًا في كومة تطبيقٍ يقتله `OutOfMemoryError` قبل أن يبلغ نصفه.
 *
 * — **البلاطات المتجهيّة تُرفض صراحةً**: أشهر ما يُدلّ عليه المستعمل اليوم
 *   («OSM QA tiles») ملفّاتُ ‎.mbtiles‎ سليمةُ الرأس، لكنّ ما في جدول `tiles` بروتوبَف
 *   MVT لا صور. قارئنا يُخرج البايتات صورةً فيفشل فكّ ترميزها **صامتًا**، فيرى
 *   المستعمل خريطةً بيضاء ولا يعرف لماذا. فيُسأل `metadata` عن `format` قبل الاعتماد،
 *   ويُستأنس ببايتات أوّل بلاطة حين يسكت الجدول — و«لا أدري» تعني: امضِ. منعُ أرشيفٍ
 *   سليمٍ أسوأ من قبول واحدٍ مشكوكٍ فيه، لأنّ الأوّل خسارةٌ مؤكّدة والثاني احتمال.
 *
 * — **والبيانات الخام مثلها**: `PK` في رأس الملفّ يقول «مضغوط» ولا يقول ما فيه، وأشهر
 *   ما يُنزّله المستعمل اليوم أرشيفُ Geofabrik بامتداد ‎_shp.zip‎ — وفيه ‎.shp‎ و‎.dbf‎
 *   لا صور. فيُفتح فهرسه ويُطلب فيه مدخلٌ على هيئة `z/x/y.png` قبل أن يبلغ اسمه
 *   النهائيّ. والاتّجاه الآمن هنا معكوسٌ عن سابقه: مضغوطٌ لا نرى فيه بلاطة يُرفض، لأنّ
 *   قبوله وعدٌ بخريطةٍ لا وجود لها — وهي شكوى المستعمل التي أنشأت هذا الحارس.
 *
 * — **قيدٌ معترَفٌ به**: التنزيل يعيش في نطاق العمليّة لا في خدمةٍ أماميّة، فمغادرة
 *   التطبيق طويلًا قد يقتله النظام قبل أن يكتمل. هذا مقبولٌ هنا لأنّ ملفّ `.part` يبقى
 *   كما هو ويُستأنف بالرابط نفسه، والنصّ `mapdl_background_note` يقول ذلك للمستعمل
 *   بصراحة. وخدمةٌ أماميّة لتنزيلٍ اختياريّ إشعارٌ دائم وإذنٌ زائد، وهي خارج هذا الإصدار.
 */
class MapDownloader private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق بعمر العمليّة: التنزيل لا يتبع شاشةً بعينها، وطيُّ الإعدادات لا يقطعه */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** حالة التنزيل الجاري أو حصيلة آخره؛ مصدرُ حقيقةٍ واحد تقرؤه الإعدادات */
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /**
     * تنزيلٌ واحد في كلّ وقت.
     *
     * رايةٌ ذرّيّة لا `Mutex`: المطلوب **رفض** الثاني لا اصطفافه. من ضغط «نزّل» مرّتين
     * لا يريد أرشيفين متتابعين، ولو اصطفّا لتقاسما الحزمة وأبطأ كلٌّ منهما الآخر.
     */
    private val busy = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    /**
     * بدء تنزيل، أو استئناف ما بقي من محاولةٍ سابقة بالرابط نفسه.
     *
     * الحُرّاس كلّها داخل المهمّة لا هنا: نتيجتها حالةٌ تُعرض، لا استثناءٌ يُرمى على
     * من ضغط الزرّ.
     */
    fun start(url: String, wifiOnly: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        job = scope.launch {
            try {
                download(url, wifiOnly)
            } catch (cancelled: CancellationException) {
                // الإلغاء ليس فشلًا: لا سطر خطأ، والجزء المحفوظ يبقى ليُستأنف
                _state.value = DownloadState.Idle
                throw cancelled
            } catch (error: Throwable) {
                // شبكةٌ أو قرصٌ أو رابطٌ مشوّه — لا شيء منها يُسقط التطبيق
                _state.value = DownloadState.Failed(R.string.mapdl_err_net)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * جلب أرشيفٍ من تخزين الجهاز بدل الشبكة.
     *
     * **لماذا هنا لا في ملفٍّ جديد؟** لأنّ ما بعد آخر بايتٍ يصل هو الأهمّ في هذا الملفّ
     * كلِّه: الفكّ، وفحص الرأس، ورفض المتجهيّة، ورفض البيانات الخام، ثمّ النقل والمسح.
     * وهذه كلُّها في [finish] — فالجلب المحلّيّ يستبدل **مصدر البايتات وحده** ويمرّ من
     * الحرّاس نفسها. ولو نُسخت هنا لصار في التطبيق حكمان على أرشيفٍ واحد، ولاختلفا عند
     * أوّل تعديلٍ على أحدهما: فيُقبل من التخزين ما يُرفض من الشبكة.
     *
     * ولا استئناف ولا واي‑فاي هنا: النسخ محلّيّ، ينتهي في ثوانٍ ولا يستنزف حزمة.
     */
    fun importFrom(uri: Uri) {
        if (!busy.compareAndSet(false, true)) return
        job = scope.launch {
            try {
                copyIn(uri)
            } catch (cancelled: CancellationException) {
                _state.value = DownloadState.Idle
                throw cancelled
            } catch (error: Throwable) {
                _state.value = DownloadState.Failed(R.string.mapdl_err_read)
            } finally {
                busy.set(false)
            }
        }
    }

    /** الإلغاء يُبقي `.part` كما هو: هو رأس مال الاستئناف، وحذفه يُهدر ما نُزّل */
    fun cancel() {
        job?.cancel()
    }

    /** إعادة الحالة إلى [DownloadState.Idle] بعد نجاحٍ أو فشل؛ ولا تمسّ تنزيلًا جاريًا */
    fun clear() {
        if (busy.get()) return
        _state.value = DownloadState.Idle
    }

    // ————————————————————————— الحُرّاس ثمّ النقل —————————————————————————

    private suspend fun download(rawUrl: String, wifiOnly: Boolean) {
        // (1) الرابط
        val url = parseUrl(rawUrl.trim()) ?: return fail(R.string.mapdl_err_url)

        // (2) الامتداد — من مسار الرابط وحده
        val name = fileNameOf(url) ?: return fail(R.string.mapdl_err_ext)
        // `x.mbtiles.gz`: اسمُ الوجهة ما قبل `.gz`، والامتداد الذي تُقاس عليه الفحوص
        // امتدادُ **الناتج** لا المضغوط — فالرأس والصيغة صفتان لما سيُقرأ لا لغلافه
        val compressed = name.endsWith(GZ_SUFFIX)
        val plainName = if (compressed) name.dropLast(GZ_SUFFIX.length) else name
        val extension = plainName.substringAfterLast('.', "").lowercase(Locale.US)

        // (3) الواي‑فاي
        if (wifiOnly && !onUnmeteredTransport()) return fail(R.string.mapdl_err_wifi)

        val folder = OfflineMaps.primaryFolder(app)
        // الجزء يحمل بصمة الرابط في اسمه: الاستئناف مشروطٌ بأن يكون **الرابط نفسه**، و
        // `map.mbtiles` اسمٌ يشيع عند مرايا شتّى — فلولا البصمة لأُلحق نصفُ أرشيفٍ بنصف
        // أرشيفٍ آخر، ولخرج ملفٌّ رأسُه سليم وجوفه خليط لا يكشفه فحص الرأس.
        val stamp = Integer.toHexString(url.toString().hashCode())
        val part = File(folder, "$name.$stamp$PART_SUFFIX")
        // ناتج الفكّ يُكتب هو أيضًا في ملفّ `.part`: لا يبلغ اسمه النهائيّ إلّا بعد أن
        // يجتاز فحص الرأس والصيغة، وإلّا لمسحه [OfflineMaps] نصفَ مفكوكٍ ففشل عند كلّ
        // إقلاع. وبلا ضغطٍ الملفّان واحد، فيبقى المسار الأصليّ كما كان بلا نسخةٍ زائدة.
        val plainPart = if (compressed) File(folder, "$plainName.$stamp$PART_SUFFIX") else part
        val target = File(folder, plainName)
        val saved = runCatching { if (part.isFile) part.length() else 0L }.getOrDefault(0L)

        // حالةٌ تظهر قبل أوّل حزمة تصل: الضغطة يجب أن يُرى أثرها فورًا
        _state.value = DownloadState.Running(saved, null, resumed = saved > 0L)
        transfer(url, folder, part, plainPart, target, extension, compressed, saved)
    }

    /**
     * نسخُ ما اختاره المستعمل إلى مجلّد الخرائط، ثمّ تسليمُه إلى [finish].
     *
     * الاسم يُؤخذ من مزوّد المستندات لا من مسار الـ`Uri`: مزوّدات SAF تعطي عناوين مثل
     * `content://…/document/1234` لا اسم فيها. وحين يسكت المزوّد عن الاسم لا نخترع
     * امتدادًا — نردّ «امتدادٌ غير مدعوم»، لأنّ الامتداد هو ما تُقاس عليه كلّ الفحوص بعدُ.
     */
    private suspend fun copyIn(uri: Uri) {
        // (1) الاسم والامتداد — بنفس تنقية [safeNameOf] التي تمرّ منها روابط الشبكة
        val declared = displayNameOf(uri) ?: return fail(R.string.mapdl_err_ext)
        val name = safeNameOf(declared) ?: return fail(R.string.mapdl_err_ext)
        val compressed = name.endsWith(GZ_SUFFIX)
        val plainName = if (compressed) name.dropLast(GZ_SUFFIX.length) else name
        val extension = plainName.substringAfterLast('.', "").lowercase(Locale.US)

        val folder = OfflineMaps.primaryFolder(app)
        val size = sizeOf(uri)

        // (2) المساحة — قبل أوّل بايتٍ يُكتب. والمضغوط يحتاج موضعًا لنفسه ولناتجه معًا،
        // وهو ما يقدّره [finish] ثانيةً قبل الفكّ؛ وهذا الحارس للنسخ وحده.
        if (size != null) {
            val free = runCatching { folder.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (size + SPACE_MARGIN_BYTES > free) {
                return fail(R.string.mapdl_err_space, formatBytes(size))
            }
        }

        // البصمة من الـ`Uri` لا من الاسم: ملفّان باسم `map.mbtiles` من مجلّدين مختلفين
        // لا يتقاسمان ملفَّ جزءٍ واحدًا.
        val stamp = Integer.toHexString(uri.toString().hashCode())
        val part = File(folder, "$name.$stamp$PART_SUFFIX")
        val plainPart = if (compressed) File(folder, "$plainName.$stamp$PART_SUFFIX") else part
        val target = File(folder, plainName)

        // نسخةٌ جديدة لا استئناف: `.part` قديمٌ من محاولةٍ سابقة يُلحَق به فيخرج خليط
        runCatching { if (part.isFile) part.delete() }
        // حين يسكت المزوّد عن الحجم لا شريطَ تقدّمٍ يُقاس، فيُقال «نسخ» صراحةً بدل
        // شريطٍ فارغٍ يُقرأ توقّفًا. وحين يُعلنه يمضي الشريط كما يمضي في التنزيل.
        _state.value = if (size == null) {
            DownloadState.Working(R.string.mapdl_copying)
        } else {
            DownloadState.Running(0L, size, resumed = false)
        }

        val opened = runCatching { app.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return fail(R.string.mapdl_err_read)
        opened.use { input ->
            FileOutputStream(part).use { output ->
                pump(input, output, 0L, size, resumed = false)
            }
        }

        finish(folder, part, plainPart, target, extension, compressed)
    }

    private suspend fun transfer(
        url: URL,
        folder: File,
        part: File,
        plainPart: File,
        target: File,
        extension: String,
        compressed: Boolean,
        savedBytes: Long,
    ) {
        var offset = savedBytes
        var connection = open(url, offset) ?: return fail(R.string.mapdl_err_net)
        var code = statusOf(connection)

        // ‎416‎ يعني أنّ الخادم لا يقبل مدانا: إمّا أنّ الجزء بلغ حجم الملفّ كلَّه، وإمّا
        // أنّ الملفّ على الخادم تبدّل. وبلا هذه المحاولة يبقى `.part` عقدةً لا تُحلّ:
        // كلّ إعادة محاولةٍ تطلب المدى نفسه فتُردّ بالرمز نفسه إلى الأبد.
        if (code == HTTP_RANGE_NOT_SATISFIABLE && offset > 0L) {
            close(connection)
            runCatching { part.delete() }
            offset = 0L
            connection = open(url, 0L) ?: return fail(R.string.mapdl_err_net)
            code = statusOf(connection)
        }

        // (6) رمز HTTP
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            close(connection)
            // رمزٌ سالب يعني أنّنا لم نبلغ الخادم أصلًا (انقطاعٌ أو مضيفٌ لا يُحلّ)، و«ردَّ
            // الخادم بالرمز ‎-1‎» جملةٌ كاذبة تُرسل المستعمل يبحث عن رمزٍ لا وجود له
            return if (code <= 0) {
                fail(R.string.mapdl_err_net)
            } else {
                fail(R.string.mapdl_err_http, code.toString())
            }
        }

        // الخطأ المميت الذي يحرسه هذان السطران: طلبنا مدًى فردّ الخادم ‎200‎، أي أرسل
        // الملفّ من أوّله. الإلحاق هنا يبني ملفًّا نصفُه مكرَّر — ويمرّ من فحص الرأس.
        val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
        if (offset > 0L && !append) {
            runCatching { part.delete() }
            offset = 0L
        }

        val remaining = runCatching { connection.contentLengthLong }.getOrDefault(-1L)
        val total = if (remaining >= 0L) offset + remaining else null

        // (4) المساحة — قبل أن نكتب بايتًا واحدًا. ومجهولُ الطول يمضي بلا فحص: منعُ
        // تنزيلٍ قد ينجح أسوأ من قرصٍ يمتلئ فيفشل بـ`mapdl_err_net`.
        if (remaining > 0L) {
            val free = runCatching { folder.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (remaining + SPACE_MARGIN_BYTES > free) {
                close(connection)
                return fail(R.string.mapdl_err_space, formatBytes(remaining))
            }
        }

        val resumed = append
        _state.value = DownloadState.Running(offset, total, resumed)

        val outcome = runCatching {
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    pump(input, output, offset, total, resumed)
                }
            }
        }
        close(connection)

        outcome.exceptionOrNull()?.let { error ->
            // `runCatching` تبتلع الإلغاء أيضًا، ولو تركناه مبتلَعًا لظهر الإلغاء خطأَ شبكة
            if (error is CancellationException) throw error
            return fail(R.string.mapdl_err_net)
        }

        finish(folder, part, plainPart, target, extension, compressed)
    }

    /**
     * ما بعد آخر بايتٍ يصل: الفكّ إن لزم، ثمّ الفحوص، ثمّ النقل إلى الاسم النهائيّ.
     *
     * فُصلت عن [transfer] لأنّها لا تعرف عن الشبكة شيئًا — وهذا يجعل ترتيبها صريحًا:
     * لا فحصَ على ملفٍّ مضغوط، ولا اسمَ نهائيًّا قبل الفحص.
     */
    private suspend fun finish(
        folder: File,
        part: File,
        plainPart: File,
        targetIn: File,
        extension: String,
        compressed: Boolean,
    ) {
        // الوجهة قد يتبدّل امتدادها إن كان المنزَّل غلافًا حول أرشيفٍ متجهيّ
        var target = targetIn

        if (compressed) {
            _state.value = DownloadState.Working(R.string.mapdl_decompressing)

            // حارس المساحة قبل أوّل بايتٍ يُكتب: القرص يمتلئ في منتصف الفكّ وإلّا،
            // فيُعطب ما ليس لنا لا تنزيلَنا وحده. والمضغوط باقٍ أثناء الفكّ فالمطلوب
            // مساحةٌ للاثنين — وهو محسوبٌ ضمنًا لأنّ الحرّ يُقاس والمضغوط على القرص.
            val packed = runCatching { part.length() }.getOrDefault(0L)
            val estimate = estimateUnpacked(part, packed)
            val free = runCatching { folder.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (estimate + SPACE_MARGIN_BYTES > free) {
                runCatching { part.delete() }
                return fail(R.string.mapdl_err_space, formatBytes(estimate))
            }

            if (!gunzip(part, plainPart)) {
                // عطبٌ في منتصف الفكّ يترك نصفَ أرشيفٍ باسمٍ سليم؛ يُمحى الطرفان معًا
                runCatching { part.delete() }
                runCatching { plainPart.delete() }
                return fail(R.string.mapdl_err_gzip)
            }
            // المضغوط لا يُبقى: الاستئناف انتهى، وإبقاؤه يشغل ربع حجم الأرشيف بلا فائدة
            runCatching { part.delete() }
        }

        // (5) صحّة المحتوى — على الناتج لا على المضغوط، وقبل النقل إلى موضعه النهائيّ
        if (!headerLooksRight(plainPart, extension)) {
            runCatching { plainPart.delete() }
            return fail(R.string.mapdl_err_content)
        }

        // (6) نقطيّةٌ لا متجهيّة — على قواعد SQLite وحدها؛ `zip` و`gemf` لا `metadata`
        // فيهما ولا جدولَ بلاطاتٍ يُستعلم عنه بـSQL
        if (extension in SQLITE_EXTENSIONS && OfflineMaps.tileFormatOf(plainPart) == TileFormat.VECTOR) {
            runCatching { plainPart.delete() }
            return fail(R.string.mapdl_err_vector)
        }

        // (7) وبلاطاتٌ لا بياناتٍ خامًا — على `zip` وحدها، وهو نظير الحارس الذي قبله:
        // `PK` في الرأس يقول «مضغوط» ولا يقول ما فيه، وأشهر ما يُدلّ عليه اليوم أرشيفُ
        // Geofabrik بامتداد ‎_shp.zip‎: يجتاز الرأس، ويُنقل إلى مجلّد الخرائط، ويُعلَن
        // خريطةً محلّيّة، ثمّ لا تظهر منه بلاطةٌ واحدة. فيُفتح فهرسه ويُطلب فيه مدخلٌ
        // على هيئة `z/x/y.png` — والحكم واحدٌ مع [OfflineMaps.holdsTiles] لا نسخةٌ منه.
        if (extension == ZIP_EXTENSION && !OfflineMaps.holdsTiles(plainPart)) {
            // مضغوطٌ لا بلاطاتٍ فيه: قد يكون **غلافًا** حول أرشيفٍ متجهيّ لا بياناتٍ
            // خامًا. وهكذا توزّع BBBike خرائطها لكلّ بلد:
            // `morocco.osm.pmtiles-shortbread.zip` وفيه `morocco.pmtiles` ومعه README.
            // فلولا هذا الاستخراج لرُدّ أنفعُ ما يبلغه المستعمل اليوم — والمبدأ نفسه
            // الذي يُفكّ به `.gz`: الغلاف ليس صيغة.
            val unwrapped = unwrapVectorArchive(plainPart, folder)
            runCatching { plainPart.delete() }
            if (unwrapped == null) return fail(R.string.mapdl_err_rawdata)
            target = File(folder, target.nameWithoutExtension + "." + VectorMaps.EXTENSION)
            runCatching { if (target.exists()) target.delete() }
            val moved = runCatching { unwrapped.renameTo(target) }.getOrDefault(false)
            if (!moved) return fail(R.string.mapdl_err_net)
            runCatching { OfflineMaps.of(app).rescan() }
            _state.value = DownloadState.Done(target.name)
            return
        }

        // (8) والمتجهيّ يُفحص بتوقيعه: `PMTiles` في أوّل البايتات ثمّ رقم النسخة.
        // الحارس نفسه الذي يمنع ملفًّا أُعيدت تسميته من دخول مجلّد الخرائط، لصيغةٍ
        // أخرى — لا استثناء لها لأنّها الأحدث.
        if (extension == VectorMaps.EXTENSION && !VectorMaps.looksValid(plainPart)) {
            runCatching { plainPart.delete() }
            return fail(R.string.mapdl_err_content)
        }

        runCatching { if (target.exists()) target.delete() }
        val moved = runCatching { plainPart.renameTo(target) }.getOrDefault(false)
        if (!moved) return fail(R.string.mapdl_err_net)

        // المسح فورًا: من نزّل أرشيفًا يريد أن يراه في السطر نفسه، لا أن يُغلق الشاشة
        // ويفتحها ليعرف أنّ التطبيق رآه
        runCatching { OfflineMaps.of(app).rescan() }
        _state.value = DownloadState.Done(target.name)
    }

    /**
     * استخراج أرشيفٍ متجهيٍّ من داخل مضغوط.
     *
     * يُقبل مدخلٌ واحدٌ فقط بامتداد ‎.pmtiles‎، ويُتحقّق من توقيعه بعد الاستخراج كما
     * يُتحقّق من كلّ ما يدخل مجلّد الخرائط. والاسم يُبنى من اسم الوجهة لا من اسم
     * المدخل: مسارٌ داخل مضغوطٍ قد يحمل `..` فيكتب خارج المجلّد.
     *
     * والمساحة تُفحص قبل أوّل بايت: أرشيف بلدٍ يبلغ مئات الميغابايت، وامتلاء القرص
     * في منتصف الاستخراج يُعطب ما ليس لنا.
     */
    private suspend fun unwrapVectorArchive(archive: File, folder: File): File? {
        val entryName = runCatching {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .firstOrNull {
                        !it.isDirectory &&
                            it.name.substringAfterLast('.').lowercase(Locale.US) ==
                            VectorMaps.EXTENSION
                    }
                    ?.let { it.name to it.size }
            }
        }.getOrNull() ?: return null

        val (name, declaredSize) = entryName
        if (declaredSize > 0) {
            val free = runCatching { folder.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (declaredSize + SPACE_MARGIN_BYTES > free) {
                fail(R.string.mapdl_err_space, formatBytes(declaredSize))
                return null
            }
        }

        _state.value = DownloadState.Working(R.string.mapdl_unwrapping)
        val out = File(folder, "unwrapped." + VectorMaps.EXTENSION + PART_SUFFIX)
        val ok = runCatching {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(name) ?: return@runCatching false
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output ->
                        pump(input, output, 0L, declaredSize.takeIf { it > 0 }, resumed = false)
                    }
                }
            }
            true
        }.getOrDefault(false)

        if (!ok || !VectorMaps.looksValid(out)) {
            runCatching { out.delete() }
            return null
        }
        return out
    }

    /**
     * تقديرٌ محافظ لحجم المفكوك.
     *
     * أكبرُ رقمين لا أحدهما:
     *
     * — **أرضيّةٌ نسبيّة**: أرشيفات البلاطات لا تقلّ عن ثلاثة أضعافٍ عمليًّا، والأربعة
     *   تحفّظٌ مقصود. وهي الأرضيّة لأنّها لا تكذب أبدًا إلى أدنى بالقدر الذي يكذبه
     *   الرقم التالي.
     *
     * — **حقل `ISIZE`** في ذيل gzip، وهو الحجم الحقيقيّ. ولا يُؤخذ وحده لأنّه أربع
     *   بايتاتٍ **بباقي ‎2^32‎**: أرشيفٌ حجمه ‎5‎ ج.ب يُعلن ‎0.7‎ ج.ب، وملفٌّ متعدّد
     *   الأعضاء يُعلن حجم آخر عضوٍ فقط. فيُؤخذ حين يزيد على الأرضيّة، ويُطرح حين ينقص.
     *
     * وله سقفٌ فوق ذلك: [GZIP_MAX_RATIO] هو أقصى ما يبلغه DEFLATE نظريًّا، فما جاوزه
     * ليس `ISIZE` أصلًا بل أربعُ بايتاتٍ من ملفٍّ مقصوصٍ أو من صفحة خطأٍ سُمّيت `.gz`.
     * وبلا السقف كانت تلك الملفّات تُردّ بـ`mapdl_err_space` — وهو خبرٌ كاذب يرسل
     * المستعمل يمسح صوره — بدل `mapdl_err_gzip` الذي يقول ما جرى فعلًا.
     *
     * وخطأ التقدير إلى أدنى ليس كارثة على كلّ حال: الفكّ يفشل بـ`IOException` فتُمحى
     * كتاباتُه كلّها وتعود المساحة. وخطؤه إلى أعلى يمنع تنزيلًا كان سينجح.
     */
    private fun estimateUnpacked(file: File, packed: Long): Long {
        val floor = packed * GZIP_EXPANSION_GUESS
        val declared = runCatching {
            FileInputStream(file).use { input ->
                val trailer = ByteArray(GZIP_ISIZE_BYTES)
                var skipped = 0L
                val target = packed - GZIP_ISIZE_BYTES
                if (target < 0L) return@use 0L
                while (skipped < target) {
                    val jumped = input.skip(target - skipped)
                    if (jumped <= 0L) return@use 0L
                    skipped += jumped
                }
                var filled = 0
                while (filled < GZIP_ISIZE_BYTES) {
                    val read = input.read(trailer, filled, GZIP_ISIZE_BYTES - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < GZIP_ISIZE_BYTES) {
                    0L
                } else {
                    // صغير الطرف أوّلًا (little‑endian)، وبلا إشارة
                    var value = 0L
                    for (index in GZIP_ISIZE_BYTES - 1 downTo 0) {
                        value = (value shl 8) or (trailer[index].toLong() and 0xFF)
                    }
                    value
                }
            }
        }.getOrDefault(0L)
        return declared.coerceIn(floor, packed * GZIP_MAX_RATIO)
    }

    /**
     * فكُّ ضغطٍ من ملفٍّ إلى ملفّ بمخزنٍ مؤقّت.
     *
     * لا `readBytes()` ولا `ByteArrayOutputStream`: أرشيف بلاطاتٍ لدولةٍ يبلغ مفكوكًا
     * غيغابايتاتٍ، وكومة التطبيق لا تحتمل جزءًا من ذلك. و[GZIPInputStream] لا تُعلن
     * طولًا يُقاس عليه تقدّم، فالحالة سطرٌ واحد لا شريط.
     *
     * والإلغاء يُسأل مع كلّ كتلة كما في [pump]: `read` حاجزةٌ لا معلَّقة.
     */
    private suspend fun gunzip(source: File, destination: File): Boolean {
        val outcome = runCatching {
            FileInputStream(source).use { raw ->
                GZIPInputStream(raw, BUFFER_BYTES).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
        }
        outcome.exceptionOrNull()?.let { error ->
            runCatching { destination.delete() }
            // `runCatching` تبتلع الإلغاء أيضًا؛ ولو تُرك مبتلَعًا لصار الإلغاءُ عطبَ فكّ
            if (error is CancellationException) throw error
            return false
        }
        return true
    }

    /**
     * أنقطيّةٌ بلاطاتُ هذا الأرشيف أم متجهيّة؟
     *
     * `metadata` أوّلًا لأنّه إعلانُ الصيغة نفسه في مواصفة MBTiles. فإن غاب الجدول أو
     * المفتاح فلا حكم: أرشيفات OsmAnd وLocus وRMaps لا تكتب `metadata` أصلًا، ورفضُها
     * لسكوتها خطأٌ أفدح من قبول أرشيفٍ مشكوكٍ فيه. وحينها تُستخرج بلاطةٌ واحدة ويُنظر
     * في أوّل بايتاتها: توقيعٌ نقطيٌّ معروف يُقبل، و`\x1f\x8b` — وهو غلاف gzip الذي
     * تُخزَّن به بلاطات MVT عادةً — يُرفض، وما لا يطابق شيئًا يُرفض كذلك.
     *
     * و[TileFormat.UNKNOWN] تعني «امضِ»: عجزُنا عن الحكم ليس حكمًا.
     */
    /**
     * نقل البايتات مع تقدّمٍ مخنوق.
     *
     * `read` حاجزةٌ لا معلَّقة، فالإلغاء لا يقع من تلقاء نفسه داخلها:
     * [currentCoroutineContext] يُسأل مع كلّ كتلة، وإغلاق التدفّق في `use` يوقف
     * القراءة الجارية.
     */
    private suspend fun pump(
        input: InputStream,
        output: OutputStream,
        startBytes: Long,
        total: Long?,
        resumed: Boolean,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = startBytes
        var markNanos = SystemClock.elapsedRealtimeNanos()
        var markBytes = written

        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read

            val now = SystemClock.elapsedRealtimeNanos()
            if (now - markNanos >= PROGRESS_INTERVAL_NANOS ||
                written - markBytes >= PROGRESS_STEP_BYTES
            ) {
                markNanos = now
                markBytes = written
                _state.value = DownloadState.Running(written, total, resumed)
            }
        }
        output.flush()
        // آخر قيمةٍ صادقة: بلا هذا السطر يبقى الشريط عالقًا دون النهاية بكتلةٍ أو كتلتين
        _state.value = DownloadState.Running(written, total, resumed)
    }

    private fun fail(@StringRes reason: Int, arg: String? = null) {
        _state.value = DownloadState.Failed(reason, arg)
    }

    // ————————————————————————— الشبكة —————————————————————————

    /**
     * فتح الاتّصال متبوعًا تحويلاتُه.
     *
     * `Accept-Encoding: identity` مقصود: لو ضغط الخادم الجسم لاختلف `Content-Length`
     * عن عدد البايتات المكتوبة، ولفسد حساب الاستئناف كلّه في المحاولة التالية.
     */
    private fun open(url: URL, offset: Long): HttpURLConnection? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = runCatching {
                (current.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", app.packageName)
                    setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                }
            }.getOrNull() ?: return null

            val code = statusOf(connection)
            if (code !in REDIRECT_CODES) return connection

            val location = runCatching { connection.getHeaderField("Location") }.getOrNull()
            close(connection)
            val next = location
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { URL(current, it) }.getOrNull() }
                ?: return null
            // الوجهة تخضع لحارس الرابط نفسه: تحويلٌ إلى `file:` أو `ftp:` لا يُتبع
            if (!isHttp(next.protocol)) return null
            current = next
        }
        return null
    }

    private fun statusOf(connection: HttpURLConnection): Int =
        runCatching { connection.responseCode }.getOrDefault(-1)

    private fun close(connection: HttpURLConnection) {
        runCatching { connection.disconnect() }
    }

    /**
     * وصلةٌ لا تُحاسَب بالحزمة.
     *
     * الإيثرنت مع الواي‑فاي لأنّ المحطّات المثبَّتة في السيّارات والحوامل تُوصَل به
     * أحيانًا، وهو غير محاسَبٍ مثله. وما عداهما — الجوّال والبلوتوث — يُعدّ محاسَبًا.
     */
    private fun onUnmeteredTransport(): Boolean = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    // ————————————————————————— الرابط والاسم والرأس —————————————————————————

    private fun parseUrl(raw: String): URL? {
        val url = runCatching { URL(raw) }.getOrNull() ?: return null
        if (!isHttp(url.protocol)) return null
        return url.takeIf { !it.host.isNullOrBlank() }
    }

    private fun isHttp(protocol: String?): Boolean =
        protocol?.lowercase(Locale.US) in HTTP_PROTOCOLS

    /**
     * اسم الملفّ من مسار الرابط، مُطهَّرًا.
     *
     * كلّ ما ليس حرفًا ولا رقمًا ولا شرطة يصير `_` — ومنه النقطة: بذلك يستحيل أن يخرج
     * اسمٌ فيه `..` أو `/` فيكتب خارج مجلّد الخرائط. والامتداد يُلحق من قائمتنا نحن،
     * بعد أن يُقبل، لا يُنقل كما جاء.
     *
     * و`.gz` امتدادٌ **مركّب** لا بديل: `map.gz` وحدها لا تقول ما بداخلها، فالحكم على
     * ما قبلها — وهو الامتداد الذي سيحمله الملفّ بعد الفكّ.
     */
    private fun fileNameOf(url: URL): String? {
        val path = url.path.orEmpty()
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return safeNameOf(decoded.substringAfterLast('/'))
    }

    /**
     * الاسم النهائيّ من اسمٍ خام — أيًّا كان مصدره: مسارُ رابطٍ أو مستندٌ اختاره المستعمل.
     *
     * وهو مشترَكٌ بينهما عمدًا: مصدرا البايتات اثنان والحكم على الاسم واحد، وإلّا قَبِل
     * أحدهما امتدادًا يرفضه الآخر أو كتب حيث لا يكتب.
     */
    private fun safeNameOf(raw: String): String? {
        val last = raw.substringAfterLast('/')
        val compressed = last.lowercase(Locale.US).endsWith(GZ_SUFFIX)
        val stem = if (compressed) last.dropLast(GZ_SUFFIX.length) else last

        val extension = stem.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension !in ALLOWED_EXTENSIONS) return null

        val base = stem.dropLast(extension.length + 1)
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_BASE_NAME)
        val name = (base.ifEmpty { FALLBACK_BASE_NAME }) + "." + extension
        return if (compressed) name + GZ_SUFFIX else name
    }

    /** اسم المستند كما يعلنه مزوّده؛ و`null` حين يسكت عنه فلا نخترع امتدادًا */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0) null else cursor.getString(column)
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** الحجم المعلَن — و`null` حين يجهله المزوّد، فيمضي النسخ بشريطٍ بلا نهايةٍ معلومة */
    private fun sizeOf(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column)
            }
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * هل يبدأ الملفّ بما يبدأ به نوعه؟
     *
     * `gemf` وحدها بلا توقيعٍ نقيسه: رأسها عددٌ صحيح (رقم الصيغة) لا سلسلة، ورفضُ
     * أرشيفٍ سليم أسوأ من قبول ملفٍّ سيرفضه [OfflineMaps] عند المسح على كلّ حال —
     * فالطول وحده شرطها.
     */
    private fun headerLooksRight(file: File, extension: String): Boolean {
        val header = readHeader(file, SQLITE_MAGIC.size) ?: return false
        return when (extension) {
            ZIP_EXTENSION -> header.startsWith(ZIP_MAGIC)
            in SQLITE_EXTENSIONS -> header.startsWith(SQLITE_MAGIC)
            else -> true
        }
    }

    /** `read` لا تَعِد بملء المخزن دفعةً واحدة، ورأسٌ منقوصٌ يُقرأ خطأً في المقارنة */
    private fun readHeader(file: File, count: Int): ByteArray? = runCatching {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = input.read(buffer, filled, count - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled < count) null else buffer
        }
    }.getOrNull()

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val FALLBACK_BASE_NAME = "map"
        private const val MAX_BASE_NAME = 64

        private const val ZIP_EXTENSION = "zip"
        private val SQLITE_EXTENSIONS = setOf("mbtiles", "sqlite", "sqlitedb")

        /** ما يقبله [OfflineMaps] في مسحه؛ ما عداه يُرفض قبل أوّل بايت */
        private val ALLOWED_EXTENSIONS =
            SQLITE_EXTENSIONS + ZIP_EXTENSION + "gemf" + VectorMaps.EXTENSION

        /** غلافٌ لا صيغة: يُقبل فوق كلٍّ من [ALLOWED_EXTENSIONS] ويُنزع قبل الاعتماد */
        private const val GZ_SUFFIX = ".gz"

        /**
         * مُضاعِفُ تقدير حجم المفكوك.
         *
         * أرشيفات البلاطات صورٌ مضغوطةٌ سلفًا في قاعدةٍ فيها نصٌّ وفهارس، ونسبتها
         * العمليّة ثلاثة أضعافٍ فأكثر. والأربعة تحفّظٌ مقصود: خطأ التقدير إلى أعلى
         * يُظهر «لا مساحة» فيمسح المستعمل شيئًا، وخطؤه إلى أدنى يملأ القرص في منتصف
         * الفكّ فيُعطب ما ليس لنا.
         */
        private const val GZIP_EXPANSION_GUESS = 4L

        /** طول حقل `ISIZE` في ذيل gzip */
        private const val GZIP_ISIZE_BYTES = 4

        /** أقصى نسبة انضغاطٍ يبلغها DEFLATE نظريًّا (‎1032:1‎)؛ ما جاوزها ليس حجمًا */
        private const val GZIP_MAX_RATIO = 1032L

        private val HTTP_PROTOCOLS = setOf("http", "https")

        /** ‎303‎ و‎307‎ و‎308‎ معها: المرايا تستعملها كلّها، و`Location` واحدةٌ فيها جميعًا */
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 64 * 1024

        /** هامشٌ فوق حجم الملفّ: قرصٌ يمتلئ تمامًا يُعطب نظام الملفّات لا تنزيلَنا وحده */
        private const val SPACE_MARGIN_BYTES = 32L * 1024L * 1024L

        /** ربع ثانية بين إصدارين، أو نصف ميغابايت — أيّهما سبق */
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val PROGRESS_STEP_BYTES = 512L * 1024L

        /** `SQLite format 3` وبعدها بايتٌ صفر — ستّة عشر بايتًا تفرضها الصيغة نصًّا */
        private val SQLITE_MAGIC =
            "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0x00.toByte()

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

        @Volatile
        private var instance: MapDownloader? = null

        /**
         * النسخة الوحيدة. لا تُنشأ في `SpeedoApp.onCreate` — كما [MapApps] — لأنّ من لم
         * يفتح الإعدادات لا يُنزّل شيئًا؛ ووحدتُها هي التي تُبقي التنزيل حيًّا بعد أن
         * تُطوى الشاشة التي بدأته.
         */
        fun of(context: Context): MapDownloader =
            instance ?: synchronized(this) {
                instance ?: MapDownloader(context).also { instance = it }
            }

        /**
         * حجمٌ بالعربيّة.
         *
         * موضعها هنا لا في الواجهة لأنّ [DownloadState.Failed] تحمل المقدار المطلوب
         * نصًّا جاهزًا في `mapdl_err_space`، فلو كُتبت في الشاشة لَلَزِمت نسختان من
         * المنطق نفسه — والإعدادات تنادي هذه.
         *
         * والأرقام بـ[Locale.US] كسائر أرقام التطبيق (قاعدة 4): ‎12.5‎ لا ‎١٢٫٥‎.
         */
        fun formatBytes(bytes: Long): String {
            val value = bytes.coerceAtLeast(0L)
            return when {
                value < 1024L -> String.format(Locale.US, "%d بايت", value)
                value < 1024L * 1024L ->
                    String.format(Locale.US, "%.0f ك.ب", value / 1024.0)
                value < 1024L * 1024L * 1024L ->
                    String.format(Locale.US, "%.1f م.ب", value / (1024.0 * 1024.0))
                else ->
                    String.format(Locale.US, "%.2f ج.ب", value / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}

/** مقارنة توقيعٍ ثنائيّ: `startsWith` النصّيّة لا تصلح لبايتاتٍ فيها صفر */
/**
 * حكم فحص الصيغة.
 *
 * ثلاثةٌ لا اثنتان: [UNKNOWN] ليست «مرفوضة حتّى يثبت العكس» بل «لا رأي لنا»، وهي حال
 * الأرشيف الذي لا `metadata` فيه ولا بلاطةَ تُستخرج. وجمعُها مع [VECTOR] كان يعني رفضَ
 * كلّ أرشيف OsmAnd و Locus سليمٍ نزّله المستعمل بنفسه.
 */

/**
 * حالة التنزيل كما تُعرض.
 *
 * [Running.totalBytes] قد يكون `null` وليس صفرًا: خادمٌ بلا `Content-Length` — وهو حال
 * كلّ ردٍّ مقطّعٍ بالأجزاء — يعني تقدّمًا بلا نسبة. والفرق بينهما سطرٌ يقول «نُزّل 40 م.ب»
 * بدل شريطٍ يكذب بنسبةٍ مخترَعة.
 *
 * و[Failed.reason] موردُ نصٍّ لا رمزٌ رقميّ ولا رسالةُ استثناء: الرمز لا يعني للمستعمل
 * شيئًا، ورسالة الاستثناء إنجليزيّةٌ في تطبيقٍ عربيّ.
 */
sealed interface DownloadState {

    data object Idle : DownloadState

    /** [resumed] ليست زينة: من يرى العدّاد يبدأ من ‎80‎ م.ب يظنّه عطبًا ما لم يُقل له لماذا */
    data class Running(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val resumed: Boolean = false,
    ) : DownloadState {
        /** `null` حين يُجهل الطول: الشريط حينها مسارٌ بلا تعبئة لا شريطٌ بنسبةٍ مخمَّنة */
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }

    /**
     * عملٌ محلّيّ بعد آخر بايتٍ يصل — فكُّ ضغطٍ اليومَ وحده.
     *
     * لا عدّاد فيها ولا نسبة: [GZIPInputStream] لا تُعلن طول ناتجها، وشريطٌ يتحرّك
     * بلا مرجعٍ يكذب. وسطرٌ واحد يقول ما يجري خيرٌ من شاشةٍ تبدو جامدة.
     */
    data class Working(@StringRes val label: Int) : DownloadState

    data class Done(val fileName: String) : DownloadState

    data class Failed(@StringRes val reason: Int, val arg: String? = null) : DownloadState
}

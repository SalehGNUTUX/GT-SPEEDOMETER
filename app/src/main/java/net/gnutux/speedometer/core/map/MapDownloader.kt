package net.gnutux.speedometer.core.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
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
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)

        // (3) الواي‑فاي
        if (wifiOnly && !onUnmeteredTransport()) return fail(R.string.mapdl_err_wifi)

        val folder = OfflineMaps.primaryFolder(app)
        // الجزء يحمل بصمة الرابط في اسمه: الاستئناف مشروطٌ بأن يكون **الرابط نفسه**، و
        // `map.mbtiles` اسمٌ يشيع عند مرايا شتّى — فلولا البصمة لأُلحق نصفُ أرشيفٍ بنصف
        // أرشيفٍ آخر، ولخرج ملفٌّ رأسُه سليم وجوفه خليط لا يكشفه فحص الرأس.
        val stamp = Integer.toHexString(url.toString().hashCode())
        val part = File(folder, "$name.$stamp$PART_SUFFIX")
        val target = File(folder, name)
        val saved = runCatching { if (part.isFile) part.length() else 0L }.getOrDefault(0L)

        // حالةٌ تظهر قبل أوّل حزمة تصل: الضغطة يجب أن يُرى أثرها فورًا
        _state.value = DownloadState.Running(saved, null, resumed = saved > 0L)
        transfer(url, folder, part, target, extension, saved)
    }

    private suspend fun transfer(
        url: URL,
        folder: File,
        part: File,
        target: File,
        extension: String,
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

        // (5) صحّة المحتوى — قبل النقل إلى موضعه النهائيّ
        if (!headerLooksRight(part, extension)) {
            runCatching { part.delete() }
            return fail(R.string.mapdl_err_content)
        }

        runCatching { if (target.exists()) target.delete() }
        val moved = runCatching { part.renameTo(target) }.getOrDefault(false)
        if (!moved) return fail(R.string.mapdl_err_net)

        // المسح فورًا: من نزّل أرشيفًا يريد أن يراه في السطر نفسه، لا أن يُغلق الشاشة
        // ويفتحها ليعرف أنّ التطبيق رآه
        runCatching { OfflineMaps.of(app).rescan() }
        _state.value = DownloadState.Done(target.name)
    }

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
     */
    private fun fileNameOf(url: URL): String? {
        val path = url.path.orEmpty()
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val last = decoded.substringAfterLast('/')
        val extension = last.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension !in ALLOWED_EXTENSIONS) return null

        val base = last.dropLast(extension.length + 1)
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_BASE_NAME)
        return (base.ifEmpty { FALLBACK_BASE_NAME }) + "." + extension
    }

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
        private val ALLOWED_EXTENSIONS = SQLITE_EXTENSIONS + ZIP_EXTENSION + "gemf"

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
private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

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

    data class Done(val fileName: String) : DownloadState

    data class Failed(@StringRes val reason: Int, val arg: String? = null) : DownloadState
}

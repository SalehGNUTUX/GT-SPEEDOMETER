package net.gnutux.speedometer.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.MainActivity
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * تحديثُ التطبيق من صفحة إصداراته على GitHub: فحصٌ، ثمّ تنزيلٌ، ثمّ تسليمٌ لمثبّت
 * النظام. التطبيق يُوزَّع خارج أيّ متجر، فمن لا يتابع المستودع بنفسه يبقى على نسخةٍ
 * قديمة إلى الأبد ما لم يُخبره التطبيق.
 *
 * وستّة قرارات تفسّر شكل هذا الملفّ:
 *
 * — **`HttpURLConnection` و`org.json` وحدهما**: كلاهما في المنصّة، فلا تبعيّة جديدة
 *   في حزمةٍ يُنزّلها الناس على وصلاتٍ محدودة. وردّ الإصدارات صغير (عشرة عناصر)
 *   فقارئٌ متدفّق لا يشتري شيئًا.
 *
 * — **`User-Agent` ليس زينة**: واجهة GitHub تردّ ‎403‎ على كلّ طلبٍ بلا وكيل. وهي
 *   واجهةٌ عامّة بلا مصادقة، فلا رمز وصولٍ يُخزَّن في الحزمة — ورمزٌ مُضمَّن في APK
 *   عامّ ليس سرًّا على أحد.
 *
 * — **المقارنة بالأعداد لا بالنصوص**: `"0.10.0" < "0.9.9"` صحيحٌ نصًّا وكارثةٌ هنا،
 *   إذ يبقى المستعمل على ‎0.9.9‎ ولا يُعرض عليه ‎0.10.0‎ أبدًا. فالوسم يُفكَّك أعدادًا
 *   وتُقارَن جزءًا جزءًا. انظر [compareVersions].
 *
 * — **حزمة `release` تُفضَّل دائمًا**: نسخة `debug` موقَّعةٌ بمفتاح التصحيح ونسخة
 *   `release` بمفتاح الإصدار، ومثبّت أندرويد يرفض تثبيت إحداهما فوق الأخرى بـ
 *   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — لا لأنّ الحزمة تالفة بل لأنّ التوقيع
 *   اختلف، وعلاجُه الوحيد إزالة التطبيق وفقدُ بياناته. فما دام في الإصدار حزمةُ
 *   `release` فهي المقصودة، ولا تُختار `debug` إلّا حين لا بديل.
 *
 * — **الحزمة في `cacheDir/updates` لا في مجلّد الخرائط**: مجلّد الذاكرة المؤقّتة
 *   يمحوه النظام حين تضيق المساحة، وهذا بالضبط ما نريده لملفٍّ عمرُه لحظة التثبيت.
 *   وكلّ تنزيلٍ يمسح ما قبله فلا تتراكم عشرات الميغابايت في جهاز أحد.
 *
 * — **لا شيء يُسقط التطبيق**: الشبكة والقرص وحدود العمليّات كلّها ملفوفةٌ
 *   بـ`runCatching`، وحصيلةُ كلّ فشلٍ حالةٌ تُعرض لا استثناءٌ يُرمى (قاعدة المشروع).
 */
class UpdateChecker private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق بعمر العمليّة: التنزيل لا يتبع شاشةً بعينها، وإغلاق الإعدادات لا يقطعه */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** حالة الفحص أو التنزيل الجاري؛ مصدرُ حقيقةٍ واحد تقرؤه شاشة الإعدادات */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * هل رُفض بدءُ التثبيت لغياب إذن «تثبيت من مصادر غير معروفة»؟
     *
     * رايةٌ منفصلة لا حالةٌ في [UpdateState]: لو صار المنعُ `Failed` لضاعت
     * [UpdateState.Ready] ومعها الملفُّ المنزَّل، فيضطرّ من عاد بعد أن أذِن إلى
     * تنزيل الحزمة كلّها من جديد.
     */
    private val _installBlocked = MutableStateFlow(false)
    val installBlocked: StateFlow<Boolean> = _installBlocked.asStateFlow()

    /** عمليّةٌ واحدة في كلّ وقت: فحصٌ أو تنزيل. والثاني يُرفض لا يصطفّ. */
    private val busy = AtomicBoolean(false)

    // ————————————————————————— الفحص —————————————————————————

    /** فحصٌ بطلب المستعمل: حالتُه تظهر كلّها، ونتيجتُه — نجحت أو فشلت — تُعرض */
    fun check(settings: AppSettings) {
        if (!busy.compareAndSet(false, true)) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                runCheck(settings, silent = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = UpdateState.Failed(R.string.update_err_net)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * الفحص اليوميّ، يُنادى من شاشة الإعدادات عند فتحها.
     *
     * موضعُه هنا لا في `MainActivity` مقصود: من يفتح الإعدادات جالسٌ ينظر إلى شاشته،
     * ومن يفتح التطبيق قد يكون خلف المقود — وطلبُ شبكةٍ عند كلّ إقلاع ثمنٌ لا يشتري
     * شيئًا في تطبيقٍ يعمل دون اتّصال.
     *
     * و«صامت» تعني: لا وميضَ «جارٍ البحث»، ولا سطرَ خطأ لمن لم يطلب فحصًا. ولا
     * تُنشر إلّا [UpdateState.Available] وحدها، وبشرط أن تكون الحالة خاملة — فلا
     * تدهس تنزيلًا جاريًا ولا حزمةً جاهزة.
     */
    fun maybeCheckDaily(settings: AppSettings) {
        if (!settings.updateNotify.value) return
        // زمنٌ مدنيّ لا قياس: المطلوب «هل مضى يوم بتقويم المستعمل»، وهو سؤالٌ لا
        // يجيب عنه `elapsedRealtimeNanos` لأنّه يصفر مع كلّ إقلاعٍ للجهاز.
        val last = settings.updateLastCheck.value
        val now = System.currentTimeMillis()
        // ساعةٌ رُدّت إلى الوراء تجعل الفارق سالبًا، وحينها يُفحص لا يُنتظر يومٌ لن يجيء
        if (last > 0L && now >= last && now - last < DAY_MILLIS) return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCheck(settings, silent = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // فحصٌ لم يطلبه أحد لا يُبلَّغ عن فشله
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * محو جوابٍ صار قديمًا — كأن يبدّل المستعمل مفتاح النسخ التجريبيّة فيصير «أنت
     * على أحدث إصدار» محسوبًا بمرشِّحٍ لم يعد قائمًا.
     *
     * الأجوبة وحدها تُمحى: حزمةٌ نُزّلت تبقى معروضةً للتثبيت، وقلبُ مفتاحٍ لا يجوز أن
     * يُهدر ما نُقل على حساب المستعمل. وعملٌ جارٍ لا يُمسّ أصلًا.
     */
    fun clear() {
        if (busy.get()) return
        when (_state.value) {
            is UpdateState.UpToDate,
            is UpdateState.Available,
            is UpdateState.Failed,
            -> _state.value = UpdateState.Idle

            else -> Unit
        }
        _installBlocked.value = false
    }

    private suspend fun runCheck(settings: AppSettings, silent: Boolean) {
        val body = fetchReleases()
        if (body == null) {
            if (!silent) _state.value = UpdateState.Failed(R.string.update_err_net)
            return
        }
        settings.setUpdateLastCheck(System.currentTimeMillis())

        val current = versionPartsOf(BuildConfig.VERSION_NAME)
        val candidates = parseReleases(body).filter { release ->
            // مطفأً يعني: لا تُرِني ما وسمه GitHub تجريبيًّا
            (settings.updateBeta.value || !release.prerelease) && release.version.isNotEmpty()
        }
        val newest = candidates.maxWithOrNull(BY_VERSION)

        if (newest == null || compareVersions(newest.version, current) <= 0) {
            if (!silent) _state.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)
            return
        }

        val asset = pickAsset(newest.assets)
        if (asset == null) {
            if (!silent) _state.value = UpdateState.Failed(R.string.update_err_none)
            return
        }

        val shownVersion = newest.version.joinToString(".")
        val available = UpdateState.Available(
            version = shownVersion,
            notes = newest.notes,
            downloadUrl = asset.url,
            sizeBytes = asset.size,
        )

        if (silent) {
            if (_state.value is UpdateState.Idle) _state.value = available
            // الإشعار في الطريق الصامت وحده: من فحص بيده ينظر إلى الجواب على الشاشة،
            // وإشعارٌ فوقه ضجيج. والوسم يُكتب بعد الإطلاق فلا يتكرّر الخبر كلّ يوم.
            if (settings.updateNotifiedTag.value != newest.tag) {
                if (notifyUpdate(shownVersion)) settings.setUpdateNotifiedTag(newest.tag)
            }
        } else {
            _state.value = available
        }
    }

    /** جسم الردّ نصًّا، أو `null` إن لم نبلغ الخادم أو ردّ بغير ‎200‎ */
    private fun fetchReleases(): String? {
        val url = runCatching { URL(RELEASES_URL) }.getOrNull() ?: return null
        val connection = open(url) ?: return null
        val code = statusOf(connection)
        if (code != HttpURLConnection.HTTP_OK) {
            close(connection)
            return null
        }
        val text = runCatching {
            connection.inputStream.use { input -> readBounded(input) }
        }.getOrNull()
        close(connection)
        return text
    }

    /**
     * قراءة الجسم بحدٍّ أعلى.
     *
     * بلا حدّ يكفي وسيطٌ يحقن صفحةً أو خادمٌ معطوب ليملأ ذاكرة تطبيقٍ يعمل على أجهزةٍ
     * محدودة. وردُّ عشرة إصدارات لا يقارب هذا الحدّ، فبلوغُه بذاته دليلُ أنّ ما وصلنا
     * ليس ما طلبنا — والتحليل يفشل بعده فيُقرأ خطأَ شبكة.
     *
     * والبايتات تُجمع كلّها ثمّ تُفكّ ترميزًا مرّةً واحدة: فكُّ كلّ كتلةٍ على حدة يقطع
     * حرفًا عربيًّا بين كتلتين — وملاحظات الإصدار عربيّة — فيخرج محرفُ استبدالٍ مكان
     * الحرف.
     */
    private fun readBounded(input: InputStream): String {
        val buffer = ByteArray(BUFFER_BYTES)
        val bytes = ByteArrayOutputStream(BUFFER_BYTES)
        var total = 0
        while (total < MAX_BODY_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - total))
            if (read < 0) break
            bytes.write(buffer, 0, read)
            total += read
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /**
     * تحليل مصفوفة الإصدارات.
     *
     * `opt*` لا `get*` في كلّ حقل: صيغة الردّ عقدٌ مع طرفٍ آخر، وحقلٌ ناقصٌ في إصدارٍ
     * واحد يجب أن يُسقط ذلك الإصدار وحده لا الفحص كلّه.
     */
    private fun parseReleases(body: String): List<Release> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val releases = ArrayList<Release>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val tag = item.optString("tag_name").orEmpty()
            releases += Release(
                tag = tag,
                name = item.optString("name").orEmpty(),
                notes = item.optString("body").orEmpty().trim().take(NOTES_LIMIT),
                prerelease = item.optBoolean("prerelease", false),
                version = versionPartsOf(tag),
                assets = parseAssets(item),
            )
        }
        return releases
    }

    private fun parseAssets(release: JSONObject): List<Asset> {
        val array = release.optJSONArray("assets") ?: return emptyList()
        val assets = ArrayList<Asset>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("browser_download_url").orEmpty()
            if (url.isEmpty()) continue
            assets += Asset(
                name = item.optString("name").orEmpty(),
                url = url,
                size = item.optLong("size", 0L),
            )
        }
        return assets
    }

    /** انظر تحذير التوقيع في رأس الملفّ: `release` أوّلًا دائمًا، و`debug` عند العدم */
    /**
     * اختيار الحزمة التي تخصّ **هذه النسخة** من بين حزم الإصدار.
     *
     * منذ ‎0.10.0‎ يحمل الإصدار حزمتين: `lite` بلا محرّكٍ متجهيّ و`full` معه. وحزمة
     * النكهة الأخرى ليست تحديثًا لهذه: أحسنُ ما يقع أن يرفضها المثبّت لاختلاف
     * التوقيع، وأسوؤه أن يجد صاحبُ الخفيفة نفسه يحمّل ‎22‎ ميغابايت زائدة لم يطلبها،
     * أو يفقد صاحبُ الكاملة محرّكه في «تحديث».
     *
     * فالمرشَّح يجب أن يحمل **اسم نكهتنا**، ومتى كان الإصدار بحزم نكهاتٍ ولم نجد
     * نكهتنا فيه فلا تحديث — ولا تُقترَح البديلة. و«لا شيء» أصدق من حزمةٍ خاطئة.
     *
     * والإصدارات القديمة (ما قبل ‎0.10.0‎) حزمةٌ واحدة بلا اسم نكهة، فتُقبل كما كانت:
     * من ثبّت واحدةً منها يُحدَّث إلى ما يليها بلا انقطاع.
     */
    private fun pickAsset(assets: List<Asset>): Asset? {
        val packages = assets.filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }
        if (packages.isEmpty()) return null

        val flavored = packages.filter { it.name.containsFlavorMark() }
        val pool = if (flavored.isEmpty()) {
            packages   // إصدارٌ قديمٌ بحزمةٍ واحدة
        } else {
            val mine = flavored.filter { it.name.contains(BuildConfig.FLAVOR, ignoreCase = true) }
            if (mine.isEmpty()) return null   // نكهتنا ليست في هذا الإصدار
            mine
        }

        return pool.firstOrNull { it.name.contains(RELEASE_MARK, ignoreCase = true) }
            ?: pool.firstOrNull { it.name.contains(DEBUG_MARK, ignoreCase = true) }
            ?: pool.firstOrNull()
    }

    /** هل في الاسم علامةُ نكهة؟ — به يُفرَّق إصدارُ النكهتين عن إصدارٍ قديمٍ بحزمةٍ واحدة */
    private fun String.containsFlavorMark(): Boolean =
        FLAVOR_MARKS.any { contains(it, ignoreCase = true) }

    // ————————————————————————— التنزيل —————————————————————————

    fun download(available: UpdateState.Available) {
        if (!busy.compareAndSet(false, true)) return
        _installBlocked.value = false
        _state.value = UpdateState.Downloading(0L, available.sizeBytes.takeIf { it > 0L })
        scope.launch {
            try {
                runDownload(available)
            } catch (cancelled: CancellationException) {
                _state.value = UpdateState.Idle
                throw cancelled
            } catch (error: Throwable) {
                _state.value = UpdateState.Failed(R.string.update_err_net)
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun runDownload(available: UpdateState.Available) {
        val url = runCatching { URL(available.downloadUrl) }.getOrNull()
            ?: return fail(R.string.update_err_net)
        if (!isHttp(url.protocol)) return fail(R.string.update_err_net)

        val folder = File(app.cacheDir, UPDATE_DIR)
        runCatching { folder.mkdirs() }
        // ما سبق يُمحى قبل أن يُكتب بايتٌ جديد: حزمةٌ من إصدارٍ ماضٍ لا تنفع أحدًا،
        // وتركُها يعني عشرات الميغابايت تتراكم في ذاكرةٍ مؤقّتة لا ينظر فيها أحد
        runCatching { folder.listFiles()?.forEach { it.delete() } }

        val target = File(folder, fileNameOf(url, available.version))
        val part = File(folder, target.name + PART_SUFFIX)

        val connection = open(url) ?: return fail(R.string.update_err_net)
        val code = statusOf(connection)
        if (code != HttpURLConnection.HTTP_OK) {
            close(connection)
            return fail(R.string.update_err_net)
        }
        val length = runCatching { connection.contentLengthLong }.getOrDefault(-1L)
        val total = if (length > 0L) length else available.sizeBytes.takeIf { it > 0L }

        val outcome = runCatching {
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output -> pump(input, output, total) }
            }
        }
        close(connection)
        outcome.exceptionOrNull()?.let { error ->
            // `runCatching` تبتلع الإلغاء أيضًا، ولو تُرك مبتلَعًا لظهر خطأَ شبكة
            if (error is CancellationException) throw error
            runCatching { part.delete() }
            return fail(R.string.update_err_net)
        }

        // صفحةُ خطأٍ بامتداد `.apk` تصل إلى مثبّت النظام فيقول «تعذّر التحليل»، وهي
        // رسالةٌ تُقرأ عطبًا في التطبيق لا في الملفّ. والحزمة أرشيف zip، فرأسها `PK`.
        if (!looksLikePackage(part)) {
            runCatching { part.delete() }
            return fail(R.string.update_err_none)
        }

        runCatching { if (target.exists()) target.delete() }
        val moved = runCatching { part.renameTo(target) }.getOrDefault(false)
        if (!moved) return fail(R.string.update_err_net)

        _state.value = UpdateState.Ready(target)
    }

    /**
     * نقل البايتات مع تقدّمٍ مخنوق.
     *
     * إصدار حالةٍ مع كلّ كتلةٍ من ‎64‎ ك.ب يعني آلاف الإصدارات في الثانية على وصلةٍ
     * سريعة، وكلّ إصدارٍ يُعيد تركيب الشاشة — فيُجمّدها بدل أن «يُظهر تقدّمًا سلسًا».
     * والمحور [SystemClock.elapsedRealtimeNanos] كسائر قياسات التطبيق (قاعدة 1).
     */
    private suspend fun pump(input: InputStream, output: OutputStream, total: Long?) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        var markNanos = SystemClock.elapsedRealtimeNanos()
        var markBytes = 0L

        while (true) {
            // `read` حاجزةٌ لا معلَّقة، فالإلغاء لا يقع من تلقاء نفسه داخلها
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
                _state.value = UpdateState.Downloading(written, total)
            }
        }
        output.flush()
        // آخر قيمةٍ صادقة: بلا هذا السطر يبقى الشريط عالقًا دون النهاية بكتلةٍ أو كتلتين
        _state.value = UpdateState.Downloading(written, total)
    }

    private fun looksLikePackage(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(ZIP_MAGIC.size)
            var filled = 0
            while (filled < header.size) {
                val read = input.read(header, filled, header.size - filled)
                if (read <= 0) break
                filled += read
            }
            filled == header.size && header[0] == ZIP_MAGIC[0] && header[1] == ZIP_MAGIC[1]
        }
    }.getOrDefault(false)

    private fun fail(@StringRes reason: Int) {
        _state.value = UpdateState.Failed(reason)
    }

    // ————————————————————————— التثبيت —————————————————————————

    /**
     * هل يأذن النظام لنا ببدء تثبيت حزمة؟
     *
     * بلا شرط نسخة: أدنى ما ندعمه ‎minSdk 26‎ وهو أندرويد ‎8‎ نفسه الذي جاء فيه هذا
     * الإذن، فالفحص قائمٌ على كلّ جهازٍ يشغّلنا.
     */
    fun canInstall(): Boolean =
        runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * تسليم الحزمة لمثبّت النظام.
     *
     * العنوان من [FileProvider] لا `file:` — الثاني يرمي `FileUriExposedException` منذ
     * أندرويد ‎7‎ — ومعه إذن قراءةٍ مؤقّت، و`NEW_TASK` لأنّ السياق سياق تطبيقٍ لا نشاط.
     */
    fun install(file: File) {
        if (!canInstall()) {
            _installBlocked.value = true
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", file)
        }.getOrNull() ?: return fail(R.string.update_err_install)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { app.startActivity(intent); true }.getOrDefault(false)
        if (started) _installBlocked.value = false else fail(R.string.update_err_install)
    }

    /** صفحة «السماح بتثبيت التطبيقات» لحزمتنا وحدها، لا إعدادات الأمان كلّها */
    fun openInstallSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { app.startActivity(intent); true }.getOrDefault(false)
        if (started) return
        // نسخٌ معدَّلة من أندرويد لا تُعلن هذه الشاشة لحزمةٍ بعينها؛ الصفحة العامّة
        // أهون من لا شيء
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ————————————————————————— الإشعار —————————————————————————

    /**
     * إشعارٌ بإصدارٍ أحدث. يُرجع `true` إن أُطلق فعلًا — وعلى ذلك وحده يُكتب الوسم
     * المُشعَر به، فمن مُنع الإذنَ اليوم يُشعَر حين يمنحه غدًا.
     */
    private fun notifyUpdate(version: String): Boolean {
        if (!canPostNotifications()) return false
        return runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
                ?: return false
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val open = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setContentTitle(app.getString(R.string.update_notif_title))
                .setContentText(app.getString(R.string.update_notif_body, version))
                .setSmallIcon(R.drawable.ic_tile_speed)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIF_ID, notification)
            true
        }.getOrDefault(false)
    }

    /** دون أندرويد ‎13‎ الإذن مُعطًى بالإعلان وحده، وفوقه يُمنح بيد المستعمل */
    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            app.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    // ————————————————————————— الشبكة —————————————————————————

    /**
     * فتح اتّصالٍ متبوعًا تحويلاتُه.
     *
     * التحويل يُتبع بأيدينا لأنّ `HttpURLConnection` لا تتبع عبورًا من `http` إلى
     * `https` ولا العكس، ورابطُ الأصل في GitHub يُحوَّل إلى مضيف تخزينٍ آخر دائمًا.
     */
    private fun open(url: URL): HttpURLConnection? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = runCatching {
                (current.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", GITHUB_ACCEPT)
                    // بلا وكيلٍ تردّ واجهة GitHub ‎403‎ على كلّ طلب
                    setRequestProperty("User-Agent", USER_AGENT)
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
            // تحويلٌ إلى `file:` أو `ftp:` لا يُتبع
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

    private fun isHttp(protocol: String?): Boolean =
        protocol?.lowercase(Locale.US) in HTTP_PROTOCOLS

    /**
     * اسم الملفّ المنزَّل، مُطهَّرًا.
     *
     * كلّ ما ليس حرفًا لاتينيًّا ولا رقمًا ولا شرطة يصير `_` — ومنه النقطة والشرطة
     * المائلة: بذلك يستحيل أن يخرج اسمٌ فيه `..` أو `/` فيُكتب خارج مجلّد التحديثات.
     * والامتداد يُلحق من عندنا لا يُنقل كما جاء.
     */
    private fun fileNameOf(url: URL, version: String): String {
        val path = url.path.orEmpty()
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val base = decoded.substringAfterLast('/')
            .removeSuffix(APK_SUFFIX)
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_BASE_NAME)
        val safeVersion = version.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return (base.ifEmpty { "update-$safeVersion" }) + APK_SUFFIX
    }

    private data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val prerelease: Boolean,
        /** أجزاء الوسم أعدادًا؛ فارغةٌ حين لا رقم فيه، وعندها يُهمَل الإصدار */
        val version: List<Int>,
        val assets: List<Asset>,
    )

    private data class Asset(val name: String, val url: String, val size: Long)

    companion object {

        private const val RELEASES_URL =
            "https://api.github.com/repos/SalehGNUTUX/GT-SPEEDOMETER/releases?per_page=10"

        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val USER_AGENT = "GT-SPEEDOMETER/${BuildConfig.VERSION_NAME}"

        /** إذنُ أندرويد ‎13‎ مكتوبًا نصًّا: ثابتُه غير موجودٍ في أصنافٍ أقدم منه */
        private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        private const val UPDATE_DIR = "updates"
        private const val PART_SUFFIX = ".part"
        private const val APK_SUFFIX = ".apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val AUTHORITY_SUFFIX = ".files"
        private const val MAX_BASE_NAME = 80

        private const val RELEASE_MARK = "release"
        private const val DEBUG_MARK = "debug"

        /**
         * أسماء النكهات كما تظهر في أسماء الحزم.
         *
         * مكتوبةٌ لا مشتقّةٌ من `BuildConfig`: النسخة العاملة تعرف نكهتها هي وحدها،
         * وهذه القائمة تُميّز «إصدارٌ بحزم نكهات» من «إصدارٌ قديمٌ بحزمةٍ واحدة» —
         * وذلك حكمٌ على أسماء غيرنا لا على أنفسنا.
         */
        private val FLAVOR_MARKS = listOf("lite", "full")

        private const val CHANNEL_ID = "update_channel"

        /** ‎1001‎ للرحلة في `TripService`؛ هذا جارُه فلا يدهس أحدهما إشعار الآخر */
        private const val NOTIF_ID = 1002

        /** الفاصل بين فحصين صامتين */
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** عشر ثوانٍ للاتّصال وللقراءة: من ينتظر أكثر ظنّ التطبيق معلّقًا */
        private const val TIMEOUT_MS = 10_000

        private const val MAX_REDIRECTS = 5
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 512 * 1024

        /** ربع ثانية بين إصدارين، أو نصف ميغابايت — أيّهما سبق */
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val PROGRESS_STEP_BYTES = 512L * 1024L

        /** حدُّ ملاحظات الإصدار: سجلُّ تغييرٍ من صفحتين لا يُقرأ في بطاقة إعدادات */
        private const val NOTES_LIMIT = 600

        private val HTTP_PROTOCOLS = setOf("http", "https")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

        private val BY_VERSION = Comparator<Release> { a, b -> compareVersions(a.version, b.version) }

        @Volatile
        private var instance: UpdateChecker? = null

        /**
         * النسخة الوحيدة. لا تُنشأ في `SpeedoApp.onCreate` — كما `MapDownloader` —
         * لأنّ من لم يفتح الإعدادات لا يفحص شيئًا؛ ووحدتُها هي التي تُبقي التنزيل
         * حيًّا بعد أن تُطوى الشاشة التي بدأته.
         */
        fun of(context: Context): UpdateChecker =
            instance ?: synchronized(this) {
                instance ?: UpdateChecker(context).also { instance = it }
            }

        /**
         * أجزاء رقم الإصدار من نصٍّ كيفما جاء.
         *
         * تقبل `v0.10.0-beta` و`0.10.0` و`release-0.10` سواءً: يُلتقط أوّل تتابعٍ من
         * أعدادٍ تفصلها نقاط. وما لا رقم فيه يُرجع قائمةً فارغة، وهي إشارةُ «أهملني».
         */
        fun versionPartsOf(text: String): List<Int> {
            val match = VERSION_PATTERN.find(text) ?: return emptyList()
            return match.value.split('.').mapNotNull { it.toIntOrNull() }
        }

        /**
         * مقارنة إصدارين جزءًا جزءًا بالأعداد.
         *
         * **هذا هو موضع الخطأ الشائع**: المقارنة النصّيّة تجعل `"0.10.0" < "0.9.9"`
         * لأنّ `'1' < '9'`، فيبقى المستعمل على القديم ولا يُعرض عليه الجديد أبدًا.
         * والأجزاء الناقصة تُقرأ أصفارًا، فـ`1.2` و`1.2.0` سواء.
         */
        fun compareVersions(left: List<Int>, right: List<Int>): Int {
            val count = maxOf(left.size, right.size)
            for (index in 0 until count) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) return a.compareTo(b)
            }
            return 0
        }

        private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)*""")
    }
}

/**
 * حالة التحديث كما تُعرض.
 *
 * [Downloading.total] قد يكون `null` وليس صفرًا: خادمٌ بلا `Content-Length` يعني
 * تقدّمًا بلا نسبة، وسطرٌ يقول «نُزّل ‎12‎ م.ب» أصدق من شريطٍ بنسبةٍ مخترَعة.
 *
 * و[Failed.reason] موردُ نصٍّ لا رسالةُ استثناء: الثانية إنجليزيّةٌ في تطبيقٍ عربيّ
 * ولا تعني للمستعمل شيئًا.
 */
sealed interface UpdateState {

    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val current: String) : UpdateState

    data class Available(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    ) : UpdateState

    data class Downloading(val bytes: Long, val total: Long?) : UpdateState {
        /** `null` حين يُجهل الطول: الشريط حينها مسارٌ بلا تعبئة */
        val fraction: Float?
            get() = total
                ?.takeIf { it > 0L }
                ?.let { (bytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }

    data class Ready(val file: File) : UpdateState

    data class Failed(@StringRes val reason: Int) : UpdateState
}

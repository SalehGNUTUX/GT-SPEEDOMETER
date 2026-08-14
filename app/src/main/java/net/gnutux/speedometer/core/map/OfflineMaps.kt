package net.gnutux.speedometer.core.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex

/**
 * الخرائط دون اتّصال: أين تُوضع، وكيف تُكتشف، وما الذي يمكن رسمه منها حقًّا.
 *
 * القاعدة التي يفرضها هذا الملفّ: **البيانات المحلّيّة تسبق دائمًا حين تغطّي الموضع**،
 * والإنترنت بديلٌ لا أصل. الراكب يخرج من التغطية كثيرًا، وكلّ بلاطةٍ تُجلب تُستهلك
 * حزمةً وبطّاريّة.
 *
 * أربعة قرارات تفسّر شكل الملفّ:
 *
 * — **المجلّدات**: «الخرائط المحلّيّة» عند الراكب هي ما نزّلته OsmAnd وأخواتها، لا ما
 *   نسخه هو إلى مجلّدنا. فنمسح — إضافةً إلى `Android/data/<الحزمة>/files/maps` —
 *   المجلّدات العامّة التي تكتب فيها تلك التطبيقات (`osmand`، `Locus`، `osmdroid`…)
 *   ومجلّدات `Android/data` لحزمها. والوصول إلى ما هو خارج مجلّدنا **ليس مضمونًا**:
 *   انظر [sharedStorageReadable] و[otherAppDataReadable]؛ وحين يتعذّر نصمت ونُبلّغ
 *   عبر [OfflineMapLibrary.sharedStorageBlocked] بدل أن نرمي أو نَعِد بما لا نملك.
 *
 * — **التصنيف**: ليس كلّ ما نزّلته OsmAnd قابلًا للرسم هنا. ملفّ `.obf` صيغةٌ
 *   **متجهيّة** خاصّة بـ OsmAnd، وosmdroid لا تفهمها بحال؛ فتُصنَّف على حدة وتُعرض
 *   على أنّها «موجودة ولا تُرسم» — والكذب على المستعمل أسوأ من خريطةٍ فارغة. أمّا
 *   أرشيفات البلاط النقطيّة فتُقبل، ومنها ما لا تعرفه osmdroid بامتداده (انظر
 *   [RasterSqliteArchive]).
 *
 * — **الكشف**: مسحٌ على [Dispatchers.IO] لا على الخيط الرئيس، ونتيجته [StateFlow]
 *   واحدة تراها الإعدادات والخريطة معًا فلا يختلف جوابان عن سؤالٍ واحد.
 *
 * — **الاختيار**: وجود الأرشيف لا يكفي؛ نتحقّق أنّه يغطّي موضع الرحلة فعلًا. أرشيف
 *   مدينةٍ أخرى يعني خريطةً رماديّة صمّاء، وهذا أسوأ من بلاطة إنترنت. والجسّ والرسم
 *   يقعان في فتحةٍ واحدة للأرشيفات — انظر [bind].
 */
class OfflineMaps private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق يعيش بعمر العمليّة: المسح لا يتبع شاشةً بعينها، والإعدادات والخريطة تشتركان فيه */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _library = MutableStateFlow(OfflineMapLibrary())

    /** نتيجة آخر مسح. قيمتها الأولى `scanned = false`، أي «لم نسأل القرص بعد» لا «لا يوجد» */
    val library: StateFlow<OfflineMapLibrary> = _library.asStateFlow()

    /** مسحان متزامنان يقرآن المجلّدات نفسها بلا فائدة، فالثاني يُهمَل ما دام الأوّل جاريًا */
    private val scanning = AtomicBoolean(false)

    /**
     * إعادة الفحص. تُنادى مرّةً عند أوّل استعمال، ثمّ كلّما ضغط المستعمل «إعادة الفحص»
     * بعد نسخ ملفٍّ جديد — فمجلّدات الخرائط لا تُبلّغنا بتغيّرها.
     */
    fun rescan() {
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                // التهيئة هنا لا عند إنشاء الخريطة: `Configuration.load` يقرأ تفضيلات
                // مشتركة من القرص، ولا يجوز أن يقع ذلك على الخيط الرئيس قبل أوّل إطار.
                ensureConfigured(app)
                _library.value = scanNow()
            } finally {
                scanning.set(false)
            }
        }
    }

    // ————————————————————————— المسح والتصنيف —————————————————————————

    private fun scanNow(): OfflineMapLibrary {
        val primary = primaryFolder(app)
        val brandFolders = publicBrandFolders()

        val roots = mutableListOf<ScanRoot>()
        // مجلّدنا أوّلًا: هو الوحيد المقروء بلا إذنٍ على كلّ إصدارات أندرويد،
        // وترتيبُه أوّلًا يجعل ملفّ المستعمل يسبق أيّ ملفٍّ وجدناه عند غيرنا.
        roots += ScanRoot(primary, allowZip = true, depth = 1)
        brandFolders.forEach { roots += ScanRoot(it, allowZip = true, depth = 1) }

        val sharedOk = sharedStorageReadable()
        val neighbourOk = otherAppDataReadable()
        if (sharedOk) roots += publicNeighbourRoots()
        if (neighbourOk) roots += privateNeighbourRoots()

        val candidates = LinkedHashMap<String, Candidate>()
        for (root in roots) {
            collectInto(root, candidates)
        }

        val rasters = mutableListOf<File>()
        val vectors = mutableListOf<File>()

        for (candidate in candidates.values) {
            val file = candidate.file
            if (file.extension.equals(OBF_EXT, ignoreCase = true)) {
                vectors += file
                continue
            }
            // الامتداد وعدٌ لا برهان: نفتح الأرشيف فعلًا ونغلقه، فلا يظهر ملفٌّ تالفٌ
            // أو منقوص النسخ — ولا أرشيفُ سكليت بمخطّطٍ لا نقرؤه — على أنّه خريطة.
            val archive = openArchive(file, candidate.allowZip) ?: continue
            runCatching { archive.close() }
            rasters += file
        }

        return OfflineMapLibrary(
            folderPath = primary.absolutePath,
            altFolderPath = brandFolders.firstOrNull()?.absolutePath.orEmpty(),
            files = rasters.sortedBy { it.name },
            vectorFiles = vectors.sortedBy { it.name },
            // «محجوب» لا «غير موجود»: على أندرويد 11 فما فوق لا يُقرأ `Android/data`
            // لتطبيقٍ آخر بحال، ولا تُقرأ الملفّات غير الإعلاميّة في التخزين المشترك
            // بلا إذن «كلّ الملفّات». الفرق يجب أن يصل إلى المستعمل كما هو.
            sharedStorageBlocked = !sharedOk || !neighbourOk,
            scanned = true,
        )
    }

    /** ملفّ مرشَّح مع سياق مجلّده: `.zip` يُقبل من مجلّداتنا وحدها (انظر [ScanRoot]) */
    private class Candidate(val file: File, val allowZip: Boolean)

    /**
     * جذر مسحٍ واحد.
     *
     * [allowZip] لأنّ `ZipFileArchive` يفتح **أيّ** ملفّ مضغوط بنجاح: قبولُه من مجلّد
     * تطبيقٍ آخر يعني عرض أرشيف صورٍ عشوائيّ على أنّه خريطة. فداخل مجلّداتنا يُقبل
     * (المستعمل وضعه قاصدًا)، وخارجها لا.
     */
    private class ScanRoot(val dir: File, val allowZip: Boolean, val depth: Int)

    /**
     * تعداد الملفّات ذات الامتدادات المعنيّة تحت جذرٍ واحد، بعمقٍ وعددٍ محدودين.
     *
     * الحدّان ليسا تجمّلًا: مجلّد `osmand` عند راكبٍ قديم يحوي آلاف البلاطات المفكوكة،
     * ومسحُه كاملًا يؤخّر ظهور الخريطة وهو ما نصلحه هنا لا ما نزيده.
     *
     * ولا يرمي شيئًا: `listFiles` تُرجع `null` حين يُمنع الوصول، فنمضي بصمت.
     */
    private fun collectInto(root: ScanRoot, out: MutableMap<String, Candidate>) {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root.dir to 0
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_ENTRIES) {
            val (dir, level) = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                visited++
                if (visited >= MAX_SCAN_ENTRIES) break
                val isDir = runCatching { child.isDirectory }.getOrDefault(false)
                if (isDir) {
                    if (level < root.depth) queue += child to (level + 1)
                    continue
                }
                val ext = child.extension.lowercase()
                if (ext !in INTERESTING_EXT) continue
                if (ext == ZIP_EXT && !root.allowZip) continue
                if (runCatching { child.length() }.getOrDefault(0L) <= 0L) continue
                // المفتاح المسار المطلق: المجلّد نفسه قد يُبلَغ من جذرين (مجلّدنا
                // ومجلّد عامّ يرمز إليه)، ولا يُعرض الملفّ مرّتين.
                val key = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                if (!out.containsKey(key)) out[key] = Candidate(child, root.allowZip)
            }
        }
    }

    /**
     * فتح أرشيفٍ نقطيّ.
     *
     * لماذا لا نكتفي بـ [ArchiveFileFactory]؟ لأنّها في 6.1.20 تسجّل أربعة امتدادات
     * فقط — `zip`, `sqlite`, `mbtiles`, `gemf` — وليس فيها `sqlitedb` وهو امتداد
     * مخبأ البلاط النقطيّ عند OsmAnd وLocus. ثمّ إنّ `sqlite` عندها يعني مخطّط
     * osmdroid نفسه (`tiles(key, provider, tile)`) لا مخطّط OsmAnd
     * (`tiles(x, y, z, s, image)`). فنقرّر بالمخطّط الحقيقيّ داخل الملفّ لا بالامتداد.
     */
    private fun openArchive(file: File, allowZip: Boolean): IArchiveFile? {
        val ext = file.extension.lowercase()
        if (ext == ZIP_EXT && !allowZip) return null
        if (ext in SQLITE_EXT) return openSqliteArchive(file)
        if (ext !in registeredExtensions()) return null
        return runCatching { ArchiveFileFactory.getArchiveFile(file) }.getOrNull()
    }

    // ————————————————————————— الاختيار والربط —————————————————————————

    /**
     * قرارٌ واحد: أيّ مصدرٍ يُرسم، وبأيّ مزوّد.
     *
     * كان الطريق قبل هذا ثلاث مراحل متتابعة تفتح الأرشيفات نفسها ثلاث مرّات — مرّةً
     * للتحقّق في المسح، ومرّةً لجسّ التغطية، ومرّةً لبناء المزوّد — والمستعمل ينتظر
     * أمام صندوقٍ فارغ طوال ذلك. هنا فتحةٌ واحدة: نفتح، نجسّ، ونسلّم عين الأرشيفات
     * المفتوحة إلى المزوّد. ومن رفضنا استعماله نغلقه في مكانه.
     *
     * @param positions مواضع يجب أن يغطّيها الأرشيف كلَّها (طرفا الرحلة ووسطها).
     * @param preferOffline تفضيل المستعمل؛ إطفاؤه يعني إنترنتًا بلا فتح قرصٍ أصلًا.
     */
    suspend fun bind(
        positions: List<Pair<Double, Double>>,
        preferOffline: Boolean,
    ): MapBinding = withContext(Dispatchers.IO) {
        ensureConfigured(app)
        val online = TileSourceFactory.MAPNIK
        val snapshot = _library.value

        if (!preferOffline || !snapshot.hasArchives || positions.isEmpty()) {
            return@withContext MapBinding(MapSource.ONLINE, MapTileProviderBasic(app, online))
        }

        val archives = snapshot.files.mapNotNull { openArchive(it, allowZip = true) }
        if (archives.isEmpty()) {
            return@withContext MapBinding(MapSource.ONLINE, MapTileProviderBasic(app, online))
        }
        // الأرشيف يخزّن البلاط تحت اسم مصدرٍ لا نعرفه سلفًا (اسم من صنع أداة التصدير)،
        // ومطابقة الاسم كانت تُرجع خريطةً فارغة من ملفٍّ سليم.
        archives.forEach { runCatching { it.setIgnoreTileSource(true) } }

        val tileSource = tileSourceFor(archives)
        if (!coversWith(archives, tileSource, positions)) {
            archives.forEach { runCatching { it.close() } }
            return@withContext MapBinding(MapSource.ONLINE, MapTileProviderBasic(app, online))
        }

        MapBinding(MapSource.OFFLINE, offlineProvider(archives, tileSource))
    }

    /**
     * هل تغطّي الأرشيفات هذه المواضع كلَّها؟
     *
     * تبقى علنيّةً لأنّ الإعدادات وشرحَ التفضيل يشيران إليها، ولأنّها السؤال الذي
     * يفصل «يوجد أرشيف» عن «يوجد بلاطٌ هنا». والمسار العمليّ للرسم هو [bind].
     */
    suspend fun covers(positions: List<Pair<Double, Double>>): Boolean = withContext(Dispatchers.IO) {
        if (positions.isEmpty()) return@withContext false
        val archives = _library.value.files.mapNotNull { openArchive(it, allowZip = true) }
        if (archives.isEmpty()) return@withContext false
        archives.forEach { runCatching { it.setIgnoreTileSource(true) } }
        try {
            coversWith(archives, tileSourceFor(archives), positions)
        } finally {
            archives.forEach { runCatching { it.close() } }
        }
    }

    /**
     * الجسّ نفسه على أرشيفاتٍ مفتوحةٍ سلفًا.
     *
     * نجسّ بلاطة كلّ موضعٍ على سلّم تكبيرٍ نازل: إصابةُ مستوًى واحد تكفي، لأنّ
     * [MapTileApproximater] يكبّر البلاطة الأدنى ليملأ ما فوقها — فالتغطية بمستوًى
     * واحد تعني صورةً باهتة لا فراغًا. والحكم `all` لا `any`: طرفُ رحلةٍ خارج الأرشيف
     * يعني فراغًا يراه المستعمل بمجرّد أن يُزيح إصبعه.
     */
    private fun coversWith(
        archives: List<IArchiveFile>,
        probe: ITileSource,
        positions: List<Pair<Double, Double>>,
    ): Boolean = positions.all { (latitude, longitude) ->
        archives.any { archive ->
            PROBE_ZOOMS.any { zoom ->
                val index = MapTileIndex.getTileIndex(
                    zoom,
                    tileX(longitude, zoom),
                    tileY(latitude, zoom),
                )
                val stream = runCatching { archive.getInputStream(probe, index) }.getOrNull()
                stream?.use { true } ?: false
            }
        }
    }

    /**
     * سلسلة المزوّد المحلّيّ.
     *
     * نبنيها بأيدينا بدل [org.osmdroid.tileprovider.modules.OfflineTileProvider] الجاهز:
     * الجاهز أرشيفٌ محض، وما نحتاجه ثلاث حلقات — الأرشيف أوّلًا، ثمّ ذاكرة البلاطات
     * المحفوظة من جلساتٍ سابقة، ثمّ التكبير التقريبيّ من مستوًى أدنى. الحلقتان
     * الأخيرتان لا تمسّان الشبكة، وهما ما يمنع الفراغ الرماديّ حين يملك الأرشيف
     * المدينة بمستوًى واحدٍ لا بكلّ المستويات.
     */
    private fun offlineProvider(
        archives: List<IArchiveFile>,
        tileSource: ITileSource,
    ): MapTileProviderBase {
        val receiver = SimpleRegisterReceiver(app)
        val archiveProvider = MapTileFileArchiveProvider(receiver, tileSource, archives.toTypedArray())
        val cacheProvider = MapTileSqlCacheProvider(receiver, tileSource)
        val approximater = MapTileApproximater().apply {
            addProvider(archiveProvider)
            addProvider(cacheProvider)
        }
        return MapTileProviderArray(
            tileSource,
            receiver,
            arrayOf(archiveProvider, cacheProvider, approximater),
        )
    }

    /**
     * المصدر الذي يفهمه الأرشيف.
     *
     * شجرة بلاطٍ مضغوطة تُخزّن ملفّاتها تحت «اسم المصدر/z/x/y.png»، ولا ينفع معها
     * تجاهل الاسم لأنّ المسار نفسه مبنيٌّ عليه؛ فنسأل الأرشيف عن الاسم الذي بداخله
     * ونبني منه مصدرًا ملفّيًّا. أمّا mbtiles وsqlite فتُجيب بلا شيء وتعمل بالتجاهل،
     * فيبقى [TileSourceFactory.MAPNIK] مجرّد مفتاحٍ صوريّ لا يُطلب من الشبكة.
     */
    private fun tileSourceFor(archives: List<IArchiveFile>): ITileSource {
        val declared = archives.firstNotNullOfOrNull { archive ->
            runCatching { archive.tileSources }.getOrNull()
                ?.firstOrNull { !it.isNullOrBlank() }
        }
        return declared?.let { FileBasedTileSource.getSource(it) } ?: TileSourceFactory.MAPNIK
    }

    // ————————————————————————— أين نبحث، وهل يُسمح لنا —————————————————————————

    /**
     * مجلّدانا العامّان: `Documents/GT-SPEEDOMETER` و`Download/GT-SPEEDOMETER`.
     * يعملان على أندرويد 9 فما دون وحيثما مُنح التطبيق وصولًا كاملًا؛ وفحصهما بلا
     * إذنٍ يُرجع فراغًا ولا يُسقط شيئًا.
     */
    @Suppress("DEPRECATION")
    private fun publicBrandFolders(): List<File> = runCatching {
        listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BRAND_DIR),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BRAND_DIR),
        )
    }.getOrDefault(emptyList())

    /**
     * المجلّدات العامّة القديمة لتطبيقات OSM.
     *
     * OsmAnd قبل أندرويد 11 كانت تكتب في `/sdcard/osmand`، وLocus في `/sdcard/Locus`،
     * وكثيرٌ من أدوات التصدير تضع mbtiles في `/sdcard/osmdroid`. هذه المسارات ما تزال
     * موجودةً على أجهزةٍ رُقّيت من إصدارٍ قديم، والملفّ الذي فيها ملفٌّ حقيقيّ يُفتح
     * بـ `java.io.File` — متى سُمح لنا.
     */
    private fun publicNeighbourRoots(): List<ScanRoot> {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return emptyList()
        return PUBLIC_MAP_DIRS.map { ScanRoot(File(root, it), allowZip = false, depth = 2) }
    }

    /**
     * مجلّدات `Android/data` لحزم OsmAnd وLocus.
     *
     * هي الموضع الحقيقيّ للخرائط على أندرويد 11 فما فوق — ولا تُقرأ هناك بحال، لا
     * بإذن التخزين ولا بإذن «كلّ الملفّات». فلا نحاول إلّا حيث تنفع المحاولة.
     */
    private fun privateNeighbourRoots(): List<ScanRoot> {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return emptyList()
        val data = File(root, ANDROID_DATA_DIR)
        val children = runCatching { data.listFiles() }.getOrNull().orEmpty()
        return children
            .filter { child ->
                runCatching { child.isDirectory }.getOrDefault(false) &&
                    NEIGHBOUR_PACKAGES.any { child.name.startsWith(it) }
            }
            // العمق ثلاثة: `files/tiles/<الاسم>.sqlitedb` عند OsmAnd،
            // و`files/Locus/maps/<الاسم>.sqlitedb` عند Locus.
            .map { ScanRoot(File(it, APP_FILES_DIR), allowZip = false, depth = 3) }
    }

    /**
     * هل يُقرأ التخزين المشترك بـ `java.io.File`؟
     *
     * على أندرويد 11 فما فوق: لا، إلّا بإذن «الوصول إلى كلّ الملفّات». وإذن القراءة
     * العاديّ لا يكفي لأنّ ملفّات الخرائط ليست وسائط. وقبل ذلك: يكفي إذن القراءة —
     * وهو غير مُعلَنٍ في بيان التطبيق أصلًا، فالجواب عمليًّا «لا» حتّى يُعلَن.
     */
    private fun sharedStorageReadable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            hasLegacyReadPermission()
        }

    /**
     * هل يُقرأ `Android/data` لتطبيقٍ آخر؟
     *
     * حتّى أندرويد 10 نعم بإذن القراءة، وبعده لا بأيّ إذن — أغلقه النظام على كلّ
     * التطبيقات، وحتّى منتقي المستندات مُنع منه في أندرويد 13.
     */
    private fun otherAppDataReadable(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R && hasLegacyReadPermission()

    @Suppress("DEPRECATION")
    private fun hasLegacyReadPermission(): Boolean = runCatching {
        app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    companion object {
        private const val BRAND_DIR = "GT-SPEEDOMETER"
        private const val MAPS_DIR = "maps"
        private const val ANDROID_DATA_DIR = "Android/data"
        private const val APP_FILES_DIR = "files"
        private const val OBF_EXT = "obf"
        private const val ZIP_EXT = "zip"

        /** سقفٌ على المسح: مجلّد بلاطٍ مفكوك يحوي عشرات الآلاف من الملفّات */
        private const val MAX_SCAN_ENTRIES = 4_000

        /** امتدادات أرشيفات سكليت التي نقرّر مخطّطها بأنفسنا لا بامتدادها */
        private val SQLITE_EXT = setOf("sqlitedb", "sqlite")

        /** ما يستحقّ أن نفتحه أو نصنّفه؛ ما عداه يُتخطّى بلا فتحٍ ولا كلفة */
        private val INTERESTING_EXT =
            setOf("mbtiles", "gemf", "zip", "sqlitedb", "sqlite", OBF_EXT)

        private val PUBLIC_MAP_DIRS = listOf("osmand", "osmand_data", "Locus", "osmdroid")

        private val NEIGHBOUR_PACKAGES = listOf("net.osmand", "menion.android.locus")

        /** سلّم الجسّ نازل: أعلى مستوًى يُصيب يكفي، وما دونه يكفي بالتقريب */
        private val PROBE_ZOOMS = listOf(16, 14, 12, 10, 8, 6, 4)

        /** ما تعرفه osmdroid بامتداده؛ يُسأل عنها لحظتها ولا تُكتب عندنا */
        private fun registeredExtensions(): Set<String> =
            runCatching { ArchiveFileFactory.getRegisteredExtensions() }.getOrNull().orEmpty()

        @Volatile
        private var instance: OfflineMaps? = null

        /**
         * النسخة الوحيدة. تُنشأ في `SpeedoApp.onCreate` مع سائر الحقن اليدويّ، ومن
         * سألها بعد ذلك — الخريطة أو الإعدادات أو سجلّ الرحلات — وجد المسح جاريًا
         * أو منتهيًا، وكلّه قبل أن يرى المستعمل بلاطةً واحدة.
         */
        fun of(context: Context): OfflineMaps =
            instance ?: synchronized(this) {
                instance ?: OfflineMaps(context).also {
                    it.rescan()
                    instance = it
                }
            }

        /** `Android/data/<الحزمة>/files/maps` — نفس نمط `tracks` في محرّك الرحلات */
        fun primaryFolder(context: Context): File =
            File(context.getExternalFilesDir(null), MAPS_DIR).apply { runCatching { mkdirs() } }

        private val configured = AtomicBoolean(false)

        /**
         * تهيئة osmdroid: وكيل مستخدمٍ باسم الحزمة كما تشترط OSM، وذاكرة بلاطاتٍ
         * داخل مخبأ التطبيق لا في جذر التخزين. تسبق أوّل `MapView` وأوّل مزوّد،
         * لأنّ كليهما يفتح قاعدة البلاطات على المسار الساري لحظتَه.
         */
        fun ensureConfigured(context: Context) {
            if (!configured.compareAndSet(false, true)) return
            val app = context.applicationContext
            val config = Configuration.getInstance()
            config.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            val base = File(app.cacheDir, "osmdroid").apply { runCatching { mkdirs() } }
            config.osmdroidBasePath = base
            config.osmdroidTileCache = File(base, "tiles").apply { runCatching { mkdirs() } }
            config.userAgentValue = app.packageName
        }

        /**
         * فتح أرشيف سكليت بالمخطّط الحقيقيّ داخله.
         *
         * ثلاثة مخطّطاتٍ تحمل الامتداد نفسه: مخطّط osmdroid
         * (`tiles(key, provider, tile)`)، ومخطّط RMaps الذي تكتبه OsmAnd وLocus
         * (`tiles(x, y, z, s, image)`)، وما ليس بخريطةٍ أصلًا. نسأل `PRAGMA` أوّلًا
         * فلا نَعِد المستعمل بخريطةٍ من ملفٍّ لا نقرؤه.
         */
        private fun openSqliteArchive(file: File): IArchiveFile? {
            val db = openReadOnly(file) ?: return null
            val columns = runCatching { columnNames(db, TILES_TABLE) }.getOrDefault(emptySet())
            return when {
                columns.containsAll(RASTER_COLUMNS) -> RasterSqliteArchive.wrap(db)
                columns.containsAll(OSMDROID_COLUMNS) -> {
                    // مخطّط osmdroid: قارئُها أدرى به. نغلق نسختنا أوّلًا كي لا يبقى
                    // اتّصالان مفتوحان على ملفٍّ واحد بلا حاجة.
                    runCatching { db.close() }
                    runCatching { DatabaseFileArchive.getDatabaseFileArchive(file) }.getOrNull()
                }

                else -> {
                    runCatching { db.close() }
                    null
                }
            }
        }

        /**
         * القراءة فقط، وبلا مُقارِناتٍ محلّيّة.
         *
         * osmdroid تفتح بوضع القراءة والكتابة، فتفشل على بطاقةٍ مركّبة للقراءة أو على
         * ملفٍّ يملكه تطبيقٌ آخر. ونحن لا نكتب في خرائط غيرنا بحال.
         */
        private fun openReadOnly(file: File): SQLiteDatabase? = runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrNull()

        private const val TILES_TABLE = "tiles"
        private val RASTER_COLUMNS = setOf("x", "y", "z", "image")
        private val OSMDROID_COLUMNS = setOf("key", "tile")

        internal fun columnNames(db: SQLiteDatabase, table: String): Set<String> =
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val names = mutableSetOf<String>()
                val nameColumn = cursor.getColumnIndex("name")
                if (nameColumn >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameColumn)?.let { names += it.lowercase() }
                    }
                }
                names
            }

        /** إحداثيّات البلاطة في شبكة Web Mercator القياسيّة (XYZ) */
        private fun tileX(longitude: Double, zoom: Int): Int {
            val n = 1 shl zoom
            val x = floor((longitude + 180.0) / 360.0 * n).toInt()
            return x.coerceIn(0, n - 1)
        }

        private fun tileY(latitude: Double, zoom: Int): Int {
            val n = 1 shl zoom
            val lat = latitude.coerceIn(-85.05112878, 85.05112878)
            val y = floor((1.0 - asinh(tan(lat * PI / 180.0)) / PI) / 2.0 * n).toInt()
            return y.coerceIn(0, n - 1)
        }
    }
}

/**
 * قارئ مخبأ البلاط النقطيّ الذي تكتبه OsmAnd وLocus وRMaps في ملفّات `.sqlitedb`.
 *
 * **لماذا كتبناه؟** لأنّ osmdroid 6.1.20 لا تسجّل الامتداد `sqlitedb` أصلًا، وقارئها
 * `DatabaseFileArchive` — الذي يخدم الامتداد `sqlite` — يستعلم عن أعمدة
 * `key/provider/tile`، وهي غير أعمدة هذه الملفّات (`x/y/z/s/image`). فبلا هذا الصنف
 * تُعرض خرائط الراكب المنزَّلة على أنّها «غير موجودة»، وهو خبرٌ كاذب.
 *
 * **ترقيم التكبير**: هذه الصيغة موروثة عن BigPlanet، وتخزّن العمود `z` مقلوبًا
 * (`17 - التكبير`). وOsmAnd الحديثة قد تكتب `tilenumbering = 'simple'` في جدول `info`
 * وحينها `z` هو التكبير نفسه. نقرأ الجدول إن وُجد، وإلّا **نعايِر بالبيانات**: بلاطةٌ
 * بإحداثيٍّ `x ≥ 2^z` مستحيلةٌ في الترقيم المباشر، فوجودها برهانُ الترقيم المقلوب.
 * التخمين هنا مرفوض: نصف الملفّات تُقرأ سوداءَ إن أخطأنا.
 */
private class RasterSqliteArchive private constructor(
    private val db: SQLiteDatabase,
    private val bigPlanetZoom: Boolean,
    private val invertedY: Boolean,
) : IArchiveFile {

    /** لا مصدر مُسمًّى في هذه الصيغة: البلاطة تُعرَّف بموضعها وحده */
    override fun init(pFile: File?) = Unit

    override fun setIgnoreTileSource(pIgnoreTileSource: Boolean) = Unit

    override fun getTileSources(): MutableSet<String> = mutableSetOf()

    override fun getInputStream(pTileSource: ITileSource?, pMapTileIndex: Long): InputStream? {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        if (zoom !in 0..MAX_ZOOM) return null
        val storedZoom = if (bigPlanetZoom) BIG_PLANET_BASE - zoom else zoom
        if (storedZoom < 0) return null
        val span = 1 shl zoom
        val x = MapTileIndex.getX(pMapTileIndex)
        val rawY = MapTileIndex.getY(pMapTileIndex)
        val y = if (invertedY) span - 1 - rawY else rawY
        if (x < 0 || y < 0 || x >= span || y >= span) return null

        return runCatching {
            db.query(
                TABLE,
                arrayOf(IMAGE_COLUMN),
                "x = ? AND y = ? AND z = ?",
                arrayOf(x.toString(), y.toString(), storedZoom.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getBlob(0)?.let { ByteArrayInputStream(it) }
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { db.close() }
    }

    override fun toString(): String = "RasterSqliteArchive[bigPlanet=$bigPlanetZoom]"

    companion object {
        private const val TABLE = "tiles"
        private const val IMAGE_COLUMN = "image"
        private const val BIG_PLANET_BASE = 17
        private const val MAX_ZOOM = 24

        /**
         * يبتلع قاعدةً مفتوحةً سلفًا بعد أن تحقّق المُستدعي من مخطّطها، ويصير مالكها.
         * يُرجع `null` — بعد الإغلاق — إن كان الجدول فارغًا: ملفٌّ بلا بلاطةٍ واحدة
         * خريطةٌ صمّاء، وعرضُه «محلّيّة» كذب.
         */
        fun wrap(db: SQLiteDatabase): IArchiveFile? {
            if (!hasRows(db)) {
                runCatching { db.close() }
                return null
            }
            val simple = readTileNumbering(db)
            val bigPlanet = simple?.not() ?: calibrateBigPlanet(db)
            return RasterSqliteArchive(db, bigPlanet, readInvertedY(db))
        }

        private fun hasRows(db: SQLiteDatabase): Boolean = runCatching {
            db.rawQuery("SELECT 1 FROM $TABLE LIMIT 1", null).use { it.moveToFirst() }
        }.getOrDefault(false)

        /** `true` = ترقيمٌ مباشر، `false` = BigPlanet، `null` = لا جدول `info` يُسأل */
        private fun readTileNumbering(db: SQLiteDatabase): Boolean? = runCatching {
            db.rawQuery("SELECT tilenumbering FROM info LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val value = cursor.getString(0)?.trim()?.lowercase()
                if (value.isNullOrEmpty()) null else value == "simple"
            }
        }.getOrNull()

        private fun readInvertedY(db: SQLiteDatabase): Boolean = runCatching {
            db.rawQuery("SELECT inverted_y FROM info LIMIT 1", null).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 1
            }
        }.getOrDefault(false)

        /**
         * المعايرة بالبيانات حين يسكت جدول `info`.
         *
         * في الترقيم المباشر لا يتجاوز `x` ولا `y` العدد `2^z - 1`. فإن تجاوزه صفٌّ
         * واحد، فالعمود `z` ليس تكبيرًا بل مقلوبه. والافتراض عند الشكّ هو BigPlanet
         * لأنّه ما تكتبه OsmAnd وLocus افتراضًا.
         */
        private fun calibrateBigPlanet(db: SQLiteDatabase): Boolean = runCatching {
            db.rawQuery("SELECT x, y, z FROM $TABLE LIMIT 16", null).use { cursor ->
                var sawRow = false
                while (cursor.moveToNext()) {
                    sawRow = true
                    val z = cursor.getInt(2)
                    if (z !in 0..MAX_ZOOM) return@use true
                    val span = 1L shl z
                    if (cursor.getLong(0) >= span || cursor.getLong(1) >= span) return@use true
                }
                !sawRow
            }
        }.getOrDefault(true)
    }
}

/** مصدر البلاطات الحيّ. يُعرض على الخريطة نفسها كي تُشخَّص خريطةٌ فارغة بنظرة. */
enum class MapSource(@StringRes val label: Int) {
    OFFLINE(R.string.map_source_offline),
    ONLINE(R.string.map_source_online),
}

/**
 * المزوّد ومصدرُه معًا.
 *
 * لا يُفصلان: تبديل أحدهما دون الآخر يعني شارةً تكذب على ما يُرسم. ومالك هذا الكائن
 * مسؤولٌ عن `detach` على المزوّد، وإلّا بقيت قواعد سكليت مفتوحةً بلا مالك.
 */
class MapBinding internal constructor(
    val source: MapSource,
    val provider: MapTileProviderBase,
)

/**
 * حصيلة المسح.
 *
 * [scanned] تفصل «لم نسأل بعد» عن «سألنا فلم نجد»: الخريطة تنتظر الجواب بدل أن تفترض
 * الإنترنت ثمّ تتراجع — وافتراضٌ خاطئٌ لثانية يعني بلاطاتٍ مُنزَّلة بلا حاجة.
 *
 * و[vectorFiles] منفصلة عن [files] لأنّ الفرق بينهما ليس تفصيلًا تقنيًّا: الأولى
 * موجودةٌ ولا تُرسم هنا، والثانية تُرسم. جمعُهما في عدّادٍ واحد يَعِد بما لا يُوفى.
 */
data class OfflineMapLibrary(
    val folderPath: String = "",
    val altFolderPath: String = "",
    /** أرشيفات بلاطٍ نقطيّة فُتحت فعلًا وفيها بلاط */
    val files: List<File> = emptyList(),
    /** خرائط OsmAnd المتجهيّة `.obf`: وُجدت، ولا يرسمها osmdroid */
    val vectorFiles: List<File> = emptyList(),
    /** تعذّر بلوغ مجلّدات التطبيقات الأخرى: قيدُ نظامٍ لا خطأُ مستعمل */
    val sharedStorageBlocked: Boolean = false,
    val scanned: Boolean = false,
) {
    val hasArchives: Boolean get() = files.isNotEmpty()

    /** وُجدت خرائط OsmAnd متجهيّة ولا أرشيف نقطيًّا معها: حالةٌ لها قولٌ خاصّ */
    val hasVectorOnly: Boolean get() = files.isEmpty() && vectorFiles.isNotEmpty()

    /** أسماء الملفّات للعرض في الإعدادات؛ المسار الكامل لا يُقرأ على شاشة هاتف */
    val names: String get() = files.joinToString("، ") { it.name }

    val vectorNames: String get() = vectorFiles.joinToString("، ") { it.name }
}

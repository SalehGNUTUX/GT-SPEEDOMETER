package net.gnutux.speedometer.core.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
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
 *   على أنّها «موجودة ولا تُرسم» — والكذب على المستعمل أسوأ من خريطةٍ فارغة. ومثلها
 *   **البيانات الخام** (‎.osm.pbf‎ و‎.shp.zip‎ و‎.gpkg‎…): بياناتٌ لا صور، تُصنَّف في
 *   [OfflineMapLibrary.rawDataFiles] ويُقال فيها ما يُقال في ‎.obf‎ — انظر [holdsTiles]
 *   للسبب الذي جعل الامتداد وحده لا يكفي في `.zip`. أمّا أرشيفات البلاط النقطيّة
 *   فتُقبل، ومنها ما لا تعرفه osmdroid بامتداده (انظر [RasterSqliteArchive]).
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

    // ————————————————————————— التصرّف في الملفّات —————————————————————————

    /**
     * هل يجوز لنا حذف هذا الملفّ؟
     *
     * **مجلّدنا وحده.** المسح يبلغ مجلّدات OsmAnd وLocus وosmdroid ليقرأ منها، وحذفُ
     * ملفٍّ من هناك يمحو خريطةً نزّلها المستعمل في تطبيقٍ آخر ودفع فيها من حزمته —
     * وقد لا يخطر له أنّ عدّاد سرعةٍ هو من محاها. القراءة ضيافة، والحذف اعتداء.
     *
     * والفحص بالمسار المتعارَف عليه ([File.getCanonicalFile]) لا بالنصّ: `..` في
     * مسارٍ ملفَّق يخرج من مجلّدنا وهو يبدو داخله. وحين تتعذّر قراءة المسار
     * المتعارَف — وهي تتعذّر بقيدِ نظامٍ لا بخطأ — يكون الجواب «لا»، فالمنعُ عند
     * الشكّ أسلم من محوٍ لا يُستدرك.
     */
    fun deletable(file: File): Boolean = runCatching {
        val own = primaryFolder(app).canonicalFile
        var parent: File? = file.canonicalFile.parentFile
        while (parent != null) {
            if (parent == own) return@runCatching true
            parent = parent.parentFile
        }
        false
    }.getOrDefault(false)

    /**
     * حذف أرشيفٍ من مجلّدنا ثمّ إعادة المسح.
     *
     * تُنفَّذ على [Dispatchers.IO]: الملفّ قد يبلغ مئات الميغابايت، وحذفُ ملفٍّ بهذا
     * الحجم على الخيط الرئيس يجمّد الواجهة ثوانيَ على تخزينٍ بطيء.
     *
     * وإعادة المسح من داخل الدالّة لا من نداءٍ ثانٍ في الواجهة: من حذف يريد القائمة
     * صادقةً في الحال، وترْكُ ذلك للمستدعي بابُ قائمةٍ تعرض ملفًّا لم يعد موجودًا.
     */
    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!deletable(file)) return@withContext false
        val gone = runCatching { file.delete() }.getOrDefault(false) || !file.exists()
        if (gone) rescan()
        gone
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
        val rawData = mutableListOf<File>()
        val pmtiles = mutableListOf<File>()

        for (candidate in candidates.values) {
            val file = candidate.file
            val lower = file.name.lowercase()
            if (file.extension.equals(OBF_EXT, ignoreCase = true)) {
                vectors += file
                continue
            }
            // ‎.pmtiles‎ متجهيٌّ أيضًا، لكنّه **يُرسم عندنا** بمحرّك MapLibre لا يُحال
            // إلى OsmAnd. فيُفصل عن `vectors` كي لا يُقال لصاحبه «افتحها في تطبيقٍ
            // آخر» وهي تعمل هنا. والتوقيع يُفحص كما يُفحص رأس النقطيّ: الامتداد وعدٌ
            // لا برهان.
            if (file.extension.equals(VectorMaps.EXTENSION, ignoreCase = true)) {
                // **ويُفتح فعلًا لا يُصدَّق امتدادُه.** صار المحرّك النقطيّ يرسم منه
                // بنفسه (انظر [PmtilesRasterArchive])، فمن فُتح دخل الأرشيفات
                // العاملة، ومن لم يُفتح فبياناتٌ لا خريطة.
                val opened = PmtilesRasterArchive.open(file, density)
                if (opened != null) {
                    runCatching { opened.close() }
                    pmtiles += file
                } else {
                    rawData += file
                }
                continue
            }
            // أرشيفُ سكليت متجهيُّ البلاطات (MVT) — كـ`shortbread` من Geofabrik: رأسه
            // سليمٌ ويُفتح، لكنّ قارئنا يُخرج بروتوبَف صورةً فيفشل فكّ ترميزها صامتًا.
            // فيُصنَّف متجهيًّا هنا كما يُرفض في المُنزِّل، بالحكم نفسه لا بنسخةٍ عنه.
            if (file.extension.lowercase() in SQLITE_TILE_EXT &&
                tileFormatOf(file) == TileFormat.VECTOR
            ) {
                vectors += file
                continue
            }
            // بياناتٌ خام يفضحها اسمها: ‎.osm.pbf‎ و‎.gpkg‎ و‎.shp‎ ليست بلاطًا بحال،
            // ولا يفتحها قارئٌ عندنا. ولا تُبتلع صمتًا: من نزّلها ظنّها خريطة، وسكوتُنا
            // يتركه أمام مستطيلٍ رماديّ لا يعرف سببه.
            if (RAW_DATA_SUFFIXES.any { lower.endsWith(it) }) {
                rawData += file
                continue
            }
            // و`.zip` أخطرها: `ZipFileArchive` يفتح **أيّ** مضغوطٍ بنجاح، فأرشيف
            // Geofabrik بامتداد ‎_shp.zip‎ كان يُعدّ خريطةً عاملة ويُعلن باسمه، ثمّ لا
            // يُرسم منه شيء. فلا يُقبل مضغوطٌ حتّى نرى فيه بلاطةً بعيننا.
            if (file.extension.equals(ZIP_EXT, ignoreCase = true) && !holdsTiles(file)) {
                rawData += file
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
            // ‎.pmtiles‎ من الأرشيفات العاملة الآن: يُجسّ بها ويُبنى منها المزوّد.
            // وتبقى مفصولةً في [pmtilesFiles] كي تُعرَّف بصيغتها في قائمة المجلّد.
            files = (rasters + pmtiles).sortedBy { it.name },
            vectorFiles = vectors.sortedBy { it.name },
            pmtilesFiles = pmtiles.sortedBy { it.name },
            rawDataFiles = rawData.sortedBy { it.name },
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
                // `.zip` والبيانات الخام من مجلّداتنا وحدها: الأوّل لأنّ أيّ مضغوطٍ
                // يُفتح (انظر [ScanRoot])، والثاني لأنّ قولنا «وجدنا ملفًّا لا يُرسم»
                // خطابٌ لمن وضع الملفّ بيده — أمّا ملفّ بياناتٍ في مجلّد تطبيقٍ آخر
                // فليس شأنه ولا شأننا، والتنبيه عليه ضجيجٌ لا خبر.
                if (ext in OWN_FOLDER_ONLY_EXT && !root.allowZip) continue
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
    /** كثافة الشاشة: بها تُقاس عروض الطرق ومقاسات الأسماء في الراسم */
    private val density: Float get() = app.resources.displayMetrics.density

    private fun openArchive(file: File, allowZip: Boolean): IArchiveFile? {
        val ext = file.extension.lowercase()
        if (ext == ZIP_EXT && !allowZip) return null
        // المتجهيّ يُرسم نقطيًّا عندنا، فيدخل من المدخل نفسه ولا يحتاج مسارًا ثانيًا
        if (ext == VectorMaps.EXTENSION) return PmtilesRasterArchive.open(file, density)
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
     * و[force] تجاوزٌ صريح من المستعمل لا تفضيلٌ ضمنيّ، ولذلك هو معاملٌ ثانٍ مستقلّ عن
     * [preferOffline] لا قيمةٌ تُدسّ فيه: الأوّل إعدادٌ عامّ («فضّل المحلّيّ متى غطّى»)
     * والثاني اختيارُ هذه اللحظة من قائمة الطبقات. وقيمته الافتراضيّة `null` تعني
     * «قرّر أنت كما كنت تقرّر»، فمسار من لم يفتح القائمة قطّ لم يتبدّل منه حرف.
     *
     * @param positions مواضع يجب أن يغطّيها الأرشيف كلَّها (طرفا الرحلة ووسطها).
     * @param preferOffline تفضيل المستعمل؛ إطفاؤه يعني إنترنتًا بلا فتح قرصٍ أصلًا.
     * @param force إجبارٌ على مصدرٍ بعينه؛ انظر [MapBindForce].
     */
    suspend fun bind(
        positions: List<Pair<Double, Double>>,
        preferOffline: Boolean,
        force: MapBindForce? = null,
    ): MapBinding = withContext(Dispatchers.IO) {
        ensureConfigured(app)
        val online = TileSourceFactory.MAPNIK
        val snapshot = _library.value

        // من طلب الإنترنت صراحةً لا يُفتح له قرصٌ أصلًا: ولا حتّى للجسّ. وهذا معنى
        // «إنترنت» في القائمة — لا «إنترنت إن لم يكن عندك أرشيف».
        if (force == MapBindForce.ONLINE) {
            return@withContext MapBinding(MapSource.ONLINE, MapTileProviderBasic(app, online))
        }

        // الإجبار على المحلّيّ يفتح الأرشيف ولو كان التفضيل العامّ إنترنتًا؛ وما دونه
        // يبقى [preferOffline] وحده حَكَمًا كما كان.
        val wantArchive = force == MapBindForce.OFFLINE || preferOffline

        if (!wantArchive || !snapshot.hasArchives || positions.isEmpty()) {
            return@withContext MapBinding(
                MapSource.ONLINE,
                MapTileProviderBasic(app, online),
                // لا أرشيف أصلًا = «لا يغطّي» يقينًا؛ وما عداه لم نجسّه فلا ندّعي علمه
                archiveCovers = if (snapshot.hasArchives) null else false,
            )
        }

        val archives = snapshot.files.mapNotNull { openArchive(it, allowZip = true) }
        if (archives.isEmpty()) {
            return@withContext MapBinding(
                MapSource.ONLINE,
                MapTileProviderBasic(app, online),
                archiveCovers = false,
            )
        }
        // الأرشيف يخزّن البلاط تحت اسم مصدرٍ لا نعرفه سلفًا (اسم من صنع أداة التصدير)،
        // ومطابقة الاسم كانت تُرجع خريطةً فارغة من ملفٍّ سليم.
        archives.forEach { runCatching { it.setIgnoreTileSource(true) } }

        val tileSource = tileSourceFor(archives)
        if (!coversWith(archives, tileSource, positions)) {
            archives.forEach { runCatching { it.close() } }
            return@withContext MapBinding(
                MapSource.ONLINE,
                MapTileProviderBasic(app, online),
                archiveCovers = false,
            )
        }

        MapBinding(
            MapSource.OFFLINE,
            offlineProvider(archives, tileSource),
            archiveCovers = true,
            maxZoom = deepestZoom(archives, tileSource, positions),
        )
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
     * أعلى تكبيرٍ يملك الأرشيف بلاطةً به فوق هذا المسار، أو `null` إن لم نجد شيئًا.
     *
     * **بلا هذا الحدّ تُطمس الخريطة.** الملاءمة تذهب إلى ما يسع المسار — ورحلةٌ في
     * حيٍّ واحدٍ تعني ‎z16‎ أو ‎z17‎ — والأرشيف يقف عند ‎z14‎، فيُمطَّط ما دونه أربعة
     * أضعافٍ فيخرج مربّعاتٍ رماديّةً لا خريطة. وقد رآها المستعمل كذلك.
     *
     * ويُسأل الأرشيف نفسه لا بياناتُه: `metadata` في mbtiles قد تكذب أو تغيب، وصيغُ
     * الأرشيفات الأخرى لا `metadata` فيها أصلًا. فتُطلب بلاطةُ منتصف المسار من الأعلى
     * نزولًا، وأوّلُ ما يُجاب هو الحقّ — عشرون طلبًا على ملفٍّ مفتوحٍ لا يُحسّ.
     */
    private fun deepestZoom(
        archives: List<IArchiveFile>,
        probe: ITileSource,
        positions: List<Pair<Double, Double>>,
    ): Int? {
        val (latitude, longitude) = positions[positions.size / 2]
        for (zoom in MAX_PROBE_ZOOM downTo 0) {
            val index = MapTileIndex.getTileIndex(zoom, tileX(longitude, zoom), tileY(latitude, zoom))
            val found = archives.any { archive ->
                runCatching { archive.getInputStream(probe, index) }.getOrNull()
                    ?.use { true } ?: false
            }
            if (found) return zoom
        }
        return null
    }

    /**
     * سلسلة المزوّد المحلّيّ.
     *
     * نبنيها بأيدينا بدل [org.osmdroid.tileprovider.modules.OfflineTileProvider] الجاهز:
     * الجاهز أرشيفٌ محض، وما نحتاجه ثلاث حلقات — الأرشيف أوّلًا، ثمّ ذاكرة البلاطات
     * ثمّ التكبير التقريبيّ من مستوًى أدنى — وهو ما يمنع الفراغ الرماديّ حين يملك
     * الأرشيف المدينة بمستوًى واحدٍ لا بكلّ المستويات.
     *
     * ## ومخبأ osmdroid المشترك **ليس منها**
     * كان ثالثَ الحلقات بحجّة سدّ الفراغ، وكان يكذب: بلاطات الإنترنت تُحفظ فيه عند
     * كلّ عرضٍ متّصل، فمن عاد بعدها إلى «محلّيّة» رأى بلاطات الشبكة بأسمائها ورموزها
     * تحت شارةٍ تقول «محلّيّة». فيظنّ أرشيفه يغطّي ما لا يغطّي، ولا يكتشف الحقيقة إلّا
     * حيث لا إنترنت — أي حيث لا رجعة.
     *
     * وهو فوق ذلك يُخفي العطب عنّا نحن: أرشيفٌ لا يُقرأ أصلًا يبدو عاملًا ما دام
     * المخبأ ممتلئًا. **من اختار «محلّيّة» يريد أن يرى أرشيفه**، بفراغه إن كان فيه
     * فراغ. والمخبأ يبقى حيث موضعه: في مزوّد الإنترنت.
     */
    private fun offlineProvider(
        archives: List<IArchiveFile>,
        tileSource: ITileSource,
    ): MapTileProviderBase {
        val receiver = SimpleRegisterReceiver(app)
        val archiveProvider = MapTileFileArchiveProvider(receiver, tileSource, archives.toTypedArray())
        val approximater = MapTileApproximater().apply {
            addProvider(archiveProvider)
        }
        return MapTileProviderArray(
            tileSource,
            receiver,
            arrayOf(archiveProvider, approximater),
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

        /** امتدادات أرشيفات سكليت التي يُسأل عن صيغة بلاطاتها */
        private val SQLITE_TILE_EXT = setOf("mbtiles", "sqlite", "sqlitedb")
        private const val ZIP_EXT = "zip"

        /** سقفٌ على المسح: مجلّد بلاطٍ مفكوك يحوي عشرات الآلاف من الملفّات */
        private const val MAX_SCAN_ENTRIES = 4_000

        /** امتدادات أرشيفات سكليت التي نقرّر مخطّطها بأنفسنا لا بامتدادها */
        private val SQLITE_EXT = setOf("sqlitedb", "sqlite")

        /**
         * لواحق البيانات الخام.
         *
         * لواحقُ لا امتدادات: `morocco-latest.osm.pbf` امتدادُه `pbf` وامتدادُ
         * `x.osm.bz2` هو `bz2` — و`bz2` وحدها ليست بيانات OSM بل غلافُ ضغطٍ لأيّ شيء،
         * فلا يُحكم عليها إلّا مقرونةً بـ`.osm`. والمقارنة على الاسم كلِّه مصغَّرًا.
         */
        private val RAW_DATA_SUFFIXES =
            listOf(".osm.pbf", ".pbf", ".gpkg", ".shp", ".osm", ".osm.bz2")

        /**
         * امتدادات تلك اللواحق كما يراها المسح.
         *
         * المسح يصفّي بالامتداد وحده (فهو أرخص من مطابقة اسمٍ كاملٍ لآلاف الملفّات)،
         * والحكم النهائيّ بعده على اللاحقة في [RAW_DATA_SUFFIXES]. ولذلك `bz2` هنا
         * وليست هناك إلّا مقرونةً بـ`.osm`: ما مرّ ولم يُطابق لاحقةً يسقط عند
         * [openArchive] بلا ضرر.
         */
        private val RAW_DATA_EXT = setOf("pbf", "gpkg", "shp", "osm", "bz2")

        /** ما يستحقّ أن نفتحه أو نصنّفه؛ ما عداه يُتخطّى بلا فتحٍ ولا كلفة */
        private val INTERESTING_EXT =
            setOf("mbtiles", "gemf", "zip", "sqlitedb", "sqlite", OBF_EXT, VectorMaps.EXTENSION) +
                RAW_DATA_EXT

        /** ما لا يُلتفت إليه خارج مجلّداتنا: انظر تعليل التخطّي في [collectInto] */
        private val OWN_FOLDER_ONLY_EXT = RAW_DATA_EXT + ZIP_EXT

        private val PUBLIC_MAP_DIRS = listOf("osmand", "osmand_data", "Locus", "osmdroid")

        private val NEIGHBOUR_PACKAGES = listOf("net.osmand", "menion.android.locus")

        /** سلّم الجسّ نازل: أعلى مستوًى يُصيب يكفي، وما دونه يكفي بالتقريب */
        private val PROBE_ZOOMS = listOf(16, 14, 12, 10, 8, 6, 4)

        /** ‎20‎ أبعدُ ما تبلغه أرشيفات OSM عمليًّا؛ وما فوقه لا يُطلب فلا يُنتظر */
        private const val MAX_PROBE_ZOOM = 20

        /** ما تعرفه osmdroid بامتداده؛ يُسأل عنها لحظتها ولا تُكتب عندنا */
        private fun registeredExtensions(): Set<String> =
            runCatching { ArchiveFileFactory.getRegisteredExtensions() }.getOrNull().orEmpty()

        /**
         * مدخلٌ يشبه بلاطة: `z/x/y.png` في تخطيط osmdroid وأدوات التصدير كلِّها.
         *
         * التكبير رقمان على الأكثر (لا تكبير فوق ‎30‎ في العالم)، وذلك وحده يمنع أن
         * يُقرأ مسارٌ مثل `data/1234/5/6.png` بلاطةً. والبداية `^` أو `/` كي يُقبل
         * التخطيط المُسمّى (`mapnik/12/2045/1023.png`) وهو الشائع في `ZipFileArchive`.
         */
        private val TILE_ENTRY =
            Regex("""(^|/)\d{1,2}/\d+/\d+\.(png|jpe?g|webp)$""", RegexOption.IGNORE_CASE)

        /**
         * سقفٌ على عدد المداخل التي نفحصها في مضغوطٍ واحد.
         *
         * أرشيف Geofabrik لبلدٍ يبلغ مئات الميغابايتات وفهرسُه المركزيّ عشرات الآلاف
         * من المداخل، وتعدادُه كاملًا يُجمّد المسح — وهو ما يجب أن يبقى وراء الشاشة لا
         * أمامها. وأرشيف البلاط يضع بلاطته الأولى في أوّل مداخله دائمًا (البنية
         * `z/x/y` تُكتب بترتيبها)، فألفان كافيةٌ بمراحل. والحدّ **يُقرّ بحدِّه**: مضغوطٌ
         * يخبّئ أوّل بلاطةٍ بعد ألفَي مدخل يُصنَّف بياناتٍ خامًا، ويظهر باسمه في
         * الملاحظة — وذلك أهون من مسحٍ يقف.
         */
        private const val MAX_ZIP_ENTRIES = 2_000

        /**
         * صيغةُ بلاطات أرشيف MBTiles: نقطيّةٌ تُرسم، أم متجهيّةٌ لا يقرؤها محرّكنا؟
         *
         * **حكمٌ واحدٌ لا اثنان.** كان في [MapDownloader] وحده، فكان الأرشيف المتجهيّ
         * يُرفض عند التنزيل ويُقبل حين يضعه المستعمل بيده في المجلّد — فيُبشَّر
         * بخريطةٍ لن تُرسم له أبدًا، ثمّ يُقال له في موضعٍ آخر «لا أرشيف يغطّي المسار».
         * خبران متناقضان عن ملفٍّ واحد. فصار الحكم هنا، يناديه الاثنان.
         *
         * ويُسأل `metadata` عن `format` أوّلًا، ويُستأنس ببايتات أوّل بلاطة حين يسكت
         * الجدول. و«لا أدري» تعني: امضِ — فمنعُ أرشيفٍ سليمٍ أسوأ من قبول واحدٍ
         * مشكوكٍ فيه، لأنّ الأوّل خسارةٌ مؤكّدة والثاني احتمال.
         */
    internal fun tileFormatOf(file: File): TileFormat = runCatching {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            // كما في [OfflineMaps]: قراءةٌ فقط وبلا مُقارِناتٍ محلّيّة، فلا نكتب في
            // أرشيفٍ لم نُنشئه ولا نفشل على بطاقةٍ مركّبة للقراءة
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            declaredFormat(db)?.let { declared ->
                return@runCatching if (declared in RASTER_FORMATS) {
                    TileFormat.RASTER
                } else {
                    TileFormat.VECTOR
                }
            }
            firstTileFormat(db)
        } finally {
            runCatching { db.close() }
        }
    }.getOrDefault(TileFormat.UNKNOWN)

    private fun declaredFormat(db: SQLiteDatabase): String? = runCatching {
        db.rawQuery("SELECT value FROM metadata WHERE name = 'format' LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

    private fun firstTileFormat(db: SQLiteDatabase): TileFormat {
        val blob = runCatching {
            db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getBlob(0) else null
            }
        }.getOrNull()
        if (blob == null || blob.isEmpty()) return TileFormat.UNKNOWN
        return when {
            blob.startsWith(PNG_MAGIC) -> TileFormat.RASTER
            blob.startsWith(JPEG_MAGIC) -> TileFormat.RASTER
            // `RIFF‹أربع بايتات طول›WEBP`: العلامتان معًا شرط، فـ`RIFF` وحدها تسبق
            // صيغًا شتّى ليس منها صورة
            blob.startsWith(RIFF_MAGIC) &&
                blob.size >= WEBP_TAG_OFFSET + WEBP_TAG.size &&
                blob.matchesAt(WEBP_TAG_OFFSET, WEBP_TAG) -> TileFormat.RASTER

            else -> TileFormat.VECTOR
        }
    }

        /** ما تعلنه مواصفة MBTiles صيغةً نقطيّة؛ وما عداه — `pbf` و`mvt` — متجهيّ */
        private val RASTER_FORMATS = setOf("png", "jpg", "jpeg", "webp")

        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_TAG = "WEBP".toByteArray(Charsets.US_ASCII)

        /** `RIFF` ثمّ أربع بايتاتِ طولٍ ثمّ `WEBP` */
        private const val WEBP_TAG_OFFSET = 8

        /**
         * أفي هذا المضغوط بلاطاتٌ فعلًا؟
         *
         * السؤال ليس ترفًا: راكبٌ وضع `morocco-latest-free_shp.zip` — وهو أرشيف أشكال
         * ESRI من Geofabrik — في مجلّد الخرائط، فقال له التطبيق «وُجدت خريطة محلّيّة»
         * ثمّ لم يرسم شيئًا. السبب أنّ `ZipFileArchive` يفتح أيّ مضغوطٍ بنجاح ولا يعِد
         * بمحتواه؛ فالفتح ليس برهانًا، والبرهان أن نرى مدخلًا على هيئة بلاطة.
         *
         * ولا نقرأ بايتًا من المحتوى: تعدادُ أسماء المداخل يقرأ الفهرس المركزيّ وحده،
         * ونقف عند أوّل إصابة. والحدّ الأعلى في [MAX_ZIP_ENTRIES].
         *
         * والعطب يُقرأ «لا»: مضغوطٌ مقصوصٌ أو لا يُفتح ليس خريطةً حتّى يثبت العكس —
         * وهذا هو الاتّجاه الآمن هنا، خلافًا لسائر فحوصنا، لأنّ الخطأ في الاتّجاه الآخر
         * وعدٌ بخريطةٍ لا وجود لها.
         *
         * وهي `internal` لا `private` لأنّ [MapDownloader] يحرس بها الملفّ المُنزَّل
         * بالحكم نفسه: فحصان بمنطقين كانا سيفترقان عند أوّل تعديل.
         */
        internal fun holdsTiles(file: File): Boolean = runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var examined = 0
                while (entries.hasMoreElements() && examined < MAX_ZIP_ENTRIES) {
                    examined++
                    val name = runCatching { entries.nextElement().name }.getOrNull() ?: continue
                    if (TILE_ENTRY.containsMatchIn(name)) return@use true
                }
                false
            }
        }.getOrDefault(false)

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
 * إجبار [OfflineMaps.bind] على مصدرٍ بعينه.
 *
 * تعدادٌ خاصّ بطبقة الخرائط لا `MapSourcePreference` من الإعدادات: تفضيل المستعمل فيه
 * «تلقائيّ» و«OsmAnd» وليس لهما هنا معنًى — `bind` لا يبني إلّا مزوّد بلاطات — وربطُ
 * هذه الطبقة بتعداد الإعدادات كان يعني حالتين ميّتتين في كلّ `when` هنا. والترجمة بين
 * التعدادين تقع عند المُستدعي، حيث تُفهم دلالةُ كلٍّ منهما على حدة.
 */
enum class MapBindForce {
    /** بلاطات الشبكة، ولا يُفتح قرصٌ ولو كان الأرشيف يغطّي */
    ONLINE,

    /** أرشيف المجلّد، ولو كان التفضيل العامّ إنترنتًا؛ ويسقط إلى الشبكة إن لم يغطِّ */
    OFFLINE,
}

/**
 * المزوّد ومصدرُه معًا.
 *
 * لا يُفصلان: تبديل أحدهما دون الآخر يعني شارةً تكذب على ما يُرسم. ومالك هذا الكائن
 * مسؤولٌ عن `detach` على المزوّد، وإلّا بقيت قواعد سكليت مفتوحةً بلا مالك.
 *
 * و[archiveCovers] ثلاثيّةٌ لا ثنائيّة، ولها ثمنٌ تدفعه الواجهة إن أهملتها: القائمة
 * تحتاج أن تقول «لا أرشيف يغطّي هذا المسار» أو تسكت، والجسّ هو الجواب الوحيد. فبدل أن
 * يُعاد فتح الأرشيفات وجسُّها مرّةً ثانية لأجل سطرٍ في قائمة، يخرج ما عرفه [bind] معه.
 */
class MapBinding internal constructor(
    val source: MapSource,
    val provider: MapTileProviderBase,
    /** `true` يغطّي، `false` لا يغطّي أو لا أرشيف، و`null` لم نجسّ فلا علم لنا */
    val archiveCovers: Boolean? = null,
    /** أعلى تكبيرٍ في الأرشيف فوق هذا المسار؛ `null` حين لا أرشيف ولا حدّ */
    val maxZoom: Int? = null,
)

/**
 * حصيلة المسح.
 *
 * [scanned] تفصل «لم نسأل بعد» عن «سألنا فلم نجد»: الخريطة تنتظر الجواب بدل أن تفترض
 * الإنترنت ثمّ تتراجع — وافتراضٌ خاطئٌ لثانية يعني بلاطاتٍ مُنزَّلة بلا حاجة.
 *
 * و[vectorFiles] منفصلة عن [files] لأنّ الفرق بينهما ليس تفصيلًا تقنيًّا: الأولى
 * موجودةٌ ولا تُرسم هنا، والثانية تُرسم. جمعُهما في عدّادٍ واحد يَعِد بما لا يُوفى.
 * و[rawDataFiles] ثالثةٌ لها القول نفسه ولها سببٌ آخر: ملفّات Geofabrik ‏(‎.shp.zip‎
 * و‎.osm.pbf‎ و‎.gpkg‎) بياناتٌ لا صور، وكانت تُعدّ خرائط عاملة فيُقال للمستعمل «وُجدت
 * خريطة محلّيّة» ثمّ لا يُرسم منها شيء.
 */
data class OfflineMapLibrary(
    val folderPath: String = "",
    val altFolderPath: String = "",
    /** أرشيفات بلاطٍ نقطيّة فُتحت فعلًا وفيها بلاط */
    val files: List<File> = emptyList(),
    /** خرائط OsmAnd المتجهيّة `.obf`: وُجدت، ولا يرسمها osmdroid */
    val vectorFiles: List<File> = emptyList(),
    /** أرشيفات ‎.pmtiles‎ الصالحة — يرسمها المحرّك المتجهيّ عندنا */
    val pmtilesFiles: List<File> = emptyList(),
    /** بياناتُ OSM خام: وُجدت، وليست خريطةً أصلًا — لا صورةَ فيها تُعرض */
    val rawDataFiles: List<File> = emptyList(),
    /** تعذّر بلوغ مجلّدات التطبيقات الأخرى: قيدُ نظامٍ لا خطأُ مستعمل */
    val sharedStorageBlocked: Boolean = false,
    val scanned: Boolean = false,
) {
    val hasArchives: Boolean get() = files.isNotEmpty()

    /** وُجدت خرائط OsmAnd متجهيّة ولا أرشيف نقطيًّا معها: حالةٌ لها قولٌ خاصّ */
    val hasVectorOnly: Boolean get() = files.isEmpty() && vectorFiles.isNotEmpty()

    /**
     * لا أرشيف ولا `.obf`، وإنّما بياناتٌ خام وحدها.
     *
     * و`.obf` تسبقها في الشرط عمدًا حين تجتمعان: صاحبُها يُحال إلى OsmAnd فتُفتح خريطته
     * فعلًا، أمّا البيانات الخام فلا يفتحها شيءٌ على الهاتف — فالنصيحة الأنفع أولى.
     */
    val hasRawDataOnly: Boolean
        get() = files.isEmpty() && vectorFiles.isEmpty() && rawDataFiles.isNotEmpty()

    /** أسماء الملفّات للعرض في الإعدادات؛ المسار الكامل لا يُقرأ على شاشة هاتف */
    val names: String get() = files.joinToString("، ") { it.name }

    val vectorNames: String get() = vectorFiles.joinToString("، ") { it.name }

    val rawDataNames: String get() = rawDataFiles.joinToString("، ") { it.name }

    /**
     * كلّ ما وُجد في مجلّدات الخرائط مصنَّفًا، لتعرضه الإعدادات قائمةً واحدة.
     *
     * القوائم الثلاث تُدمج هنا لا في الواجهة: الترتيب جزءٌ من المعنى — ما يُرسم
     * أوّلًا، ثمّ ما وُجد ولا يُرسم — ولو تُرك للواجهة لاختلف بين شاشةٍ وأخرى.
     */
    val entries: List<MapFileEntry>
        get() = files.filterNot { it in pmtilesFiles }
            .map { MapFileEntry(it, MapFileKind.ARCHIVE) } +
            // أرشيفات ‎.pmtiles‎ كانت تُصنَّف ولا تُعرض: يجدها المحرّك المتجهيّ ويرسم
            // منها، ثمّ يقول قسمُ «ما في مجلّد الخرائط» «لا ملفّات بعد». فلا يستطيع
            // صاحبها حذفَها ولا مشاركتَها، وهي أكبر ما في المجلّد حجمًا.
            pmtilesFiles.map { MapFileEntry(it, MapFileKind.PMTILES) } +
            vectorFiles.map { MapFileEntry(it, MapFileKind.VECTOR) } +
            rawDataFiles.map { MapFileEntry(it, MapFileKind.RAW_DATA) }
}

/** تصنيف ملفٍّ وُجد في مجلّد الخرائط: أيرسمه التطبيق أم لا، ولماذا */
/** صيغة بلاطات أرشيف MBTiles كما يحكم بها [OfflineMaps.tileFormatOf] */
internal fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(0, prefix)

internal fun ByteArray.matchesAt(offset: Int, pattern: ByteArray): Boolean {
    if (offset < 0 || size < offset + pattern.size) return false
    for (index in pattern.indices) {
        if (this[offset + index] != pattern[index]) return false
    }
    return true
}

internal enum class TileFormat { RASTER, VECTOR, UNKNOWN }

enum class MapFileKind {
    /** أرشيف بلاطٍ نقطيّ فُتح فعلًا: هذا وحده ما يُرسم */
    ARCHIVE,

    /** خريطة OsmAnd متجهيّة `.obf`: تُفتح في OsmAnd ولا يرسمها osmdroid */
    VECTOR,

    /** أرشيف ‎.pmtiles‎ متجهيّ: يُفكّ ويُرسم على الجهاز — خريطة بلدٍ في ملفّ */
    PMTILES,

    /** بيانات OSM خام (‎shp‎ · ‎gpkg‎ · ‎pbf‎): ليست خريطةً أصلًا */
    RAW_DATA,
}

/**
 * سطرٌ في قائمة الخرائط المحلّيّة.
 *
 * الحجم يُقرأ هنا مرّةً عند بناء السطر لا في كلّ إعادة تركيب: `File.length` نداءُ
 * نظامٍ على القرص، وقائمةٌ من عشرة ملفّات تُعيد تركيبها Compose عشرات المرّات في
 * الثانية تصير عشرات النداءات في الثانية على مسارٍ لا يتبدّل.
 */
data class MapFileEntry(val file: File, val kind: MapFileKind) {
    val sizeBytes: Long = runCatching { file.length() }.getOrDefault(0L)
}

package net.gnutux.speedometer.core.map

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import java.io.File
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
 * الخرائط دون اتّصال: أين تُوضع، وكيف تُكتشف، ومتى تُقدَّم على بلاطات الإنترنت.
 *
 * القاعدة التي يفرضها هذا الملفّ: **البيانات المحلّيّة تسبق دائمًا حين تغطّي الموضع**،
 * والإنترنت بديلٌ لا أصل. الراكب يخرج من التغطية كثيرًا، وكلّ بلاطةٍ تُجلب تُستهلك
 * حزمةً وبطّاريّة.
 *
 * ثلاثة قرارات تفسّر شكل الملفّ:
 *
 * — **المجلّد**: `Android/data/<الحزمة>/files/maps`. وهو الموضع الوحيد على أندرويد 10
 *   فما فوق الذي نقرؤه بـ `java.io.File` بلا إذنٍ البتّة؛ والأرشيف يُفتح ملفًّا حقيقيًّا
 *   لا `Uri`، فمكتبة الوسائط لا تنفع هنا كما تنفع للفيديو في [net.gnutux.speedometer.core.media.MediaRepository].
 *   ويُفحص معه مجلّدان عامّان (`Documents/GT-SPEEDOMETER` و`Download/GT-SPEEDOMETER`)
 *   لأنّهما يعملان على أندرويد 9 فما دون وحيثما مُنح التطبيق وصولًا كاملًا؛ فحصهما
 *   بلا إذنٍ يُرجع فراغًا ولا يُسقط شيئًا.
 *
 * — **الكشف**: مسحٌ على [Dispatchers.IO] لا على الخيط الرئيس، ونتيجته [StateFlow]
 *   واحدة تراها الإعدادات والخريطة معًا فلا يختلف جوابان عن سؤالٍ واحد.
 *
 * — **الاختيار**: وجود الأرشيف لا يكفي؛ نتحقّق أنّه يغطّي موضع الرحلة فعلًا عبر
 *   [covers]. أرشيف مدينةٍ أخرى يعني خريطةً رماديّة صمّاء، وهذا أسوأ من بلاطة إنترنت.
 */
class OfflineMaps private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق يعيش بعمر العمليّة: المسح لا يتبع شاشةً بعينها، والإعدادات والخريطة تشتركان فيه */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _library = MutableStateFlow(OfflineMapLibrary())

    /** نتيجة آخر مسح. قيمتها الأولى `scanned = false`، أي «لم نسأل القرص بعد» لا «لا يوجد» */
    val library: StateFlow<OfflineMapLibrary> = _library.asStateFlow()

    /** مسحان متزامنان يقرآن المجلّد نفسه بلا فائدة، فالثاني يُهمَل ما دام الأوّل جاريًا */
    private val scanning = AtomicBoolean(false)

    /**
     * إعادة الفحص. تُنادى مرّةً عند أوّل استعمال، ثمّ كلّما ضغط المستعمل «إعادة الفحص»
     * بعد نسخ ملفٍّ جديد — فمجلّد التطبيق لا يُبلّغنا بتغيّره.
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

    private fun scanNow(): OfflineMapLibrary {
        val primary = primaryFolder(app)
        val shared = publicFolders()
        val folders = listOf(primary) + shared

        // الامتدادات المقبولة تُسأل عنها المكتبة لحظتها ولا تُكتب عندنا: قائمةٌ مكتوبة
        // بأيدينا تتخلّف عن أيّ صيغةٍ تضيفها osmdroid لاحقًا، أو تَعِد بما لا تفي به.
        val supported = runCatching { ArchiveFileFactory.getRegisteredExtensions() }
            .getOrNull().orEmpty()

        val found = folders
            .flatMap { dir -> dir.listFiles().orEmpty().asIterable() }
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in supported }
            // الامتداد وعدٌ لا برهان: نفتح الأرشيف فعلًا ونغلقه، فلا يظهر في الإعدادات
            // ملفٌّ تالفٌ أو منقوصُ النسخ على أنّه خريطةٌ جاهزة.
            .filter { file ->
                val archive = runCatching { ArchiveFileFactory.getArchiveFile(file) }.getOrNull()
                archive?.also { runCatching { it.close() } } != null
            }
            .sortedBy { it.name }

        return OfflineMapLibrary(
            folderPath = primary.absolutePath,
            altFolderPath = shared.firstOrNull()?.absolutePath.orEmpty(),
            files = found,
            scanned = true,
        )
    }

    /**
     * هل تغطّي الأرشيفات هذه المواضع كلَّها؟
     *
     * السؤال لازم لأنّ «يوجد أرشيف» لا يعني «يوجد بلاطٌ هنا»: أرشيفُ مدينةٍ أخرى
     * يُخرج خريطةً رماديّة صمّاء، وهي أسوأ من بلاطة إنترنت.
     *
     * نجسّ بلاطة كلّ موضعٍ على سلّم تكبيرٍ نازل: إصابةُ مستوًى واحد تكفي، لأنّ
     * [MapTileApproximater] يكبّر البلاطة الأدنى ليملأ ما فوقها — فالتغطية بمستوًى
     * واحد تعني صورةً باهتة لا فراغًا. والحكم `all` لا `any`: طرفُ رحلةٍ خارج الأرشيف
     * يعني فراغًا يراه المستعمل بمجرّد أن يُزيح إصبعه.
     */
    suspend fun covers(positions: List<Pair<Double, Double>>): Boolean = withContext(Dispatchers.IO) {
        if (positions.isEmpty()) return@withContext false
        val archives = _library.value.files
            .mapNotNull { runCatching { ArchiveFileFactory.getArchiveFile(it) }.getOrNull() }
        if (archives.isEmpty()) return@withContext false
        // الجسّ يستعمل عين المصدر الذي سيستعمله الرسم، وإلّا حكمنا بعدم التغطية على
        // أرشيفٍ يغطّي فعلًا — أو بالعكس
        val probe = tileSourceFor(archives)
        try {
            archives.forEach { it.setIgnoreTileSource(true) }
            positions.all { (latitude, longitude) ->
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
        } finally {
            archives.forEach { runCatching { it.close() } }
        }
    }

    /**
     * مزوّد البلاطات المطابق للمصدر المطلوب.
     *
     * في الحالة المحلّيّة نبني [MapTileProviderArray] بأيدينا بدل [org.osmdroid.tileprovider.modules.OfflineTileProvider]
     * الجاهز: الجاهز أرشيفٌ محض، وما نحتاجه سلسلةٌ من ثلاث حلقات — الأرشيف أوّلًا،
     * ثمّ ذاكرة البلاطات المحفوظة من جلساتٍ سابقة، ثمّ التكبير التقريبيّ من مستوًى
     * أدنى. الحلقتان الأخيرتان لا تمسّان الشبكة، وهما ما يمنع الفراغ الرماديّ حين
     * يملك الأرشيف المدينة بمستوًى واحدٍ لا بكلّ المستويات.
     */
    fun providerFor(wanted: MapSource): MapTileProviderBase {
        ensureConfigured(app)
        val online = TileSourceFactory.MAPNIK

        if (wanted == MapSource.ONLINE) return MapTileProviderBasic(app, online)

        val archives: Array<IArchiveFile> = _library.value.files
            .mapNotNull { runCatching { ArchiveFileFactory.getArchiveFile(it) }.getOrNull() }
            .onEach {
                // الأرشيف يخزّن البلاط تحت اسم مصدرٍ لا نعرفه سلفًا (اسم من صنع أداة
                // التصدير)، ومطابقة الاسم كانت تُرجع خريطةً فارغة من ملفٍّ سليم.
                it.setIgnoreTileSource(true)
            }
            .toTypedArray()

        if (archives.isEmpty()) return MapTileProviderBasic(app, online)

        val tileSource = tileSourceFor(archives.asList())

        val receiver = SimpleRegisterReceiver(app)
        val archiveProvider = MapTileFileArchiveProvider(receiver, tileSource, archives)
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
     * ونبني منه مصدرًا ملفّيًّا. أمّا mbtiles و sqlite فتُجيب بلا شيء وتعمل بالتجاهل،
     * فيبقى [TileSourceFactory.MAPNIK] مجرّد مفتاحٍ صوريّ لا يُطلب من الشبكة.
     */
    private fun tileSourceFor(archives: List<IArchiveFile>): ITileSource {
        val declared = archives.firstNotNullOfOrNull { archive ->
            runCatching { archive.tileSources }.getOrNull()
                ?.firstOrNull { !it.isNullOrBlank() }
        }
        return declared?.let { FileBasedTileSource.getSource(it) } ?: TileSourceFactory.MAPNIK
    }

    @Suppress("DEPRECATION")
    private fun publicFolders(): List<File> = runCatching {
        listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BRAND_DIR),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BRAND_DIR),
        )
    }.getOrDefault(emptyList())

    companion object {
        private const val BRAND_DIR = "GT-SPEEDOMETER"
        private const val MAPS_DIR = "maps"

        /** سلّم الجسّ نازل: أعلى مستوًى يُصيب يكفي، وما دونه يكفي بالتقريب */
        private val PROBE_ZOOMS = listOf(16, 14, 12, 10, 8, 6, 4)

        @Volatile
        private var instance: OfflineMaps? = null

        /**
         * النسخة الوحيدة. المكان الأصحّ لإنشائها ولإطلاق أوّل مسحٍ هو
         * `SpeedoApp.onCreate` مع سائر الحقن اليدويّ، وهو خارج نطاق هذا التغيير؛
         * فالمسح يبدأ عند أوّل من يسأل — الخريطة أو الإعدادات أو سجلّ الرحلات —
         * وكلّه قبل أن يرى المستعمل بلاطةً واحدة.
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

/** مصدر البلاطات الحيّ. يُعرض على الخريطة نفسها كي تُشخَّص خريطةٌ فارغة بنظرة. */
enum class MapSource(@StringRes val label: Int) {
    OFFLINE(R.string.map_source_offline),
    ONLINE(R.string.map_source_online),
}

/**
 * حصيلة المسح.
 *
 * [scanned] تفصل «لم نسأل بعد» عن «سألنا فلم نجد»: الخريطة تنتظر الجواب بدل أن تفترض
 * الإنترنت ثمّ تتراجع — وافتراضٌ خاطئٌ لثانية يعني بلاطاتٍ مُنزَّلة بلا حاجة.
 */
data class OfflineMapLibrary(
    val folderPath: String = "",
    val altFolderPath: String = "",
    val files: List<File> = emptyList(),
    val scanned: Boolean = false,
) {
    val hasArchives: Boolean get() = files.isNotEmpty()

    /** أسماء الملفّات للعرض في الإعدادات؛ المسار الكامل لا يُقرأ على شاشة هاتف */
    val names: String get() = files.joinToString("، ") { it.name }
}

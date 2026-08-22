package net.gnutux.speedometer.core.map

import android.graphics.Bitmap
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import net.gnutux.speedometer.core.map.pmtiles.PmtilesReader
import net.gnutux.speedometer.core.map.render.ShortbreadPainter
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex

/**
 * أرشيف ‎.pmtiles‎ **متجهيٌّ يُقدَّم نقطيًّا**: يقرأ المربّع المتجهيّ ويرسمه صورةً.
 *
 * ## لماذا `IArchiveFile` لا مزوّدٌ جديد
 * لأنّ الواجهة عقدُها سطرٌ واحد: «أعطني بلاطةً بهذا الرقم». فمن ورائها تدخل الصيغةُ
 * الجديدة في الآلة القائمة كلِّها بلا تغيير — التقريبُ من مستوًى أدنى، وحدُّ التكبير،
 * وقلبُ الألوان، والشارةُ التي تقول «محلّيّة». وأيُّ مزوّدٍ جديدٍ كان سيعيد بناء ذلك
 * كلِّه لينتهي إلى المكان نفسه.
 *
 * ## والرسم يقع في خيط البلاطات
 * osmdroid يسأل عن البلاطات في خيوطٍ خلفيّة، فالفكّ والرسم يقعان هناك لا في الخيط
 * الرئيس. وبلاطةٌ حضريّةٌ فيها ألفٌ وخمسمئة طريقٍ وثلاثة آلاف مبنًى، فرسمُها ليس
 * مجّانيًّا — ولذلك المخبأ أدناه.
 *
 * ## المخبأ بالصور المضغوطة لا بالصور الحيّة
 * البلاطة الحيّة ‎256×256‎ بأربعة بايتات للبكسل = ربعُ ميغابايت، وPNG منها عشرون
 * كيلوبايتًا. والعقد يطلب `InputStream` أصلًا، فالضغطُ ليس ثمنًا زائدًا بل شرطُ
 * الواجهة — ويُدفع مرّةً لا في كلّ طلب.
 */
class PmtilesRasterArchive private constructor(
    private val reader: PmtilesReader,
    private val painter: ShortbreadPainter,
) : IArchiveFile {

    /**
     * أعلى تكبيرٍ يُقدَّم.
     *
     * أعلى من مدى الأرشيف عمدًا: المربّع المتجهيّ يُرسم بأيّ مقاسٍ شئنا، فبلاطة ‎z14‎
     * تُعطي ‎z18‎ بخطوطٍ حادّةٍ وأسماءٍ مقروءة، لا تمطيطَ صورةٍ جاهزة. وهذا ما لا
     * يستطيعه أرشيفٌ نقطيٌّ بحال، وهو الفرق الذي يراه المستعمل حين يقرّب على رحلةٍ
     * في حيٍّ واحد.
     */
    val deepestZoom: Int get() = reader.maxZoom + OVERZOOM_LEVELS

    private val cache = object : LruCache<Long, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size
    }

    override fun init(pFile: File?) = Unit

    /** لا مصدر مُسمًّى: البلاطة تُعرَّف بموضعها وحده، كما في mbtiles */
    override fun setIgnoreTileSource(pIgnoreTileSource: Boolean) = Unit

    override fun getTileSources(): MutableSet<String> = mutableSetOf()

    /**
     * **متزامنةٌ لأنّ الراسم ليس آمنًا للخيوط.** osmdroid يسأل عن البلاطات في خيوطٍ
     * متوازية، والراسم يحمل `Paint` و`Path` وإزاحةَ نافذةٍ مشتركة — فخيطان فيه يعني
     * بلاطةً تُرسم بإزاحة أختها. والتزامن هنا لا يكلّف: القراءة من الملفّ متزامنةٌ
     * أصلًا، والمخبأ يجيب أكثر الطلبات قبل بلوغ هذا القفل.
     */
    @Synchronized
    override fun getInputStream(pTileSource: ITileSource?, pMapTileIndex: Long): InputStream? {
        cache.get(pMapTileIndex)?.let { return ByteArrayInputStream(it) }

        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        if (zoom < 0 || zoom > deepestZoom) return null

        // ما فوق مدى الأرشيف يُرسم من بلاطة الأب: يُهبط بالمستوى ويُقصّ الربع الموافق
        val depth = (zoom - reader.maxZoom).coerceAtLeast(0)
        val sourceZoom = zoom - depth
        val sourceX = x shr depth
        val sourceY = y shr depth

        val tile = reader.tile(sourceZoom, sourceX, sourceY) ?: return null
        val png = runCatching {
            val span = 1 shl depth
            val window = ShortbreadPainter.Window(span, x % span, y % span)
            val bitmap = painter.paint(tile, zoom, window)
            ByteArrayOutputStream(PNG_GUESS).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull() ?: return null

        cache.put(pMapTileIndex, png)
        return ByteArrayInputStream(png)
    }

    override fun close() {
        cache.evictAll()
        reader.close()
    }

    override fun toString(): String = "PmtilesRasterArchive"

    companion object {

        /** امتداد الأرشيف؛ الحكم النهائيّ لتوقيع الملفّ لا له */
        const val EXTENSION = "pmtiles"

        /**
         * فحصٌ رخيص: `PMTiles` نصًّا ثمّ رقم النسخة بايتًا.
         *
         * الامتداد وحده يكذب — من يعيد تسمية ملفٍّ يُدخله مجلّد الخرائط — والفحص
         * الكامل يفتح الملفّ ويقرأ دليله. فهذا للمُنزِّل حيث يكفي أن نعرف أنّ ما
         * وصل ليس صفحة خطأٍ من الخادم، وذاك للفتح حيث يجب أن يُقرأ فعلًا.
         */
        fun looksLikePmtiles(file: File): Boolean = runCatching {
            if (!file.isFile || file.length() < MAGIC.size + 1) return false
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size + 1)
                var filled = 0
                while (filled < head.size) {
                    val read = input.read(head, filled, head.size - filled)
                    if (read <= 0) return false
                    filled += read
                }
                for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
                head[MAGIC.size].toInt() == SPEC_VERSION
            }
        }.getOrDefault(false)

        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
        private const val SPEC_VERSION = 3

        /**
         * أربع درجاتٍ فوق مدى الأرشيف: ‎z14‎ في الملفّ تصير ‎z18‎ على الشاشة.
         *
         * وكانت درجتين حين كانت البلاطة تُرسم كاملةً بضلعٍ مضاعَفٍ ثمّ تُقصّ — وذاك
         * يضاعف الذاكرة أربعًا مع كلّ درجة. أمّا وقد صارت النافذة تدخل في حساب الرسم
         * (انظر [ShortbreadPainter.Window]) فالصورة ‎256‎ بكسلًا مهما عمُق التقريب،
         * والثمن الباقي زمنُ رسمِ هندسةٍ يقع أكثرُها خارج النافذة — وSkia يُسقطه سريعًا.
         *
         * وأربعٌ لا خمس: عند ‎1/1024‎ من مساحة البلاطة يصير الشارع الواحد شريطًا عريضًا
         * بلا معنًى، وليس في البيانات ما يُظهره — المربّع المتجهيّ نفسه مبسَّطٌ لمقاسه.
         */
        private const val OVERZOOM_LEVELS = 4

        private const val CACHE_BYTES = 12 * 1024 * 1024
        private const val PNG_GUESS = 24 * 1024

        /**
         * يفتح الأرشيف إن كان ‎.pmtiles‎ متجهيًّا صالحًا، وإلّا `null`.
         *
         * والنقطيّ منه يُردّ: أرشيف PMTiles قد يحمل صور PNG بدل المربّعات، وذاك لا
         * يمرّ من هنا — لا لأنّه لا يعمل، بل لأنّ رسمَه ليس عملَ هذا الصنف.
         */
        fun open(archive: File, density: Float): PmtilesRasterArchive? {
            val reader = PmtilesReader.open(archive) ?: return null
            if (!reader.isVector) {
                reader.close()
                return null
            }
            return PmtilesRasterArchive(reader, ShortbreadPainter(density))
        }
    }
}

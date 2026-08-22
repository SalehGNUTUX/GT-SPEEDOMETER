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
     * أعلى من مدى الأرشيف بدرجتين عمدًا: المربّع المتجهيّ يُرسم بأيّ مقاسٍ شئنا، فبلاطة
     * ‎z14‎ تُرسم أربعَ مرّاتٍ بتفاصيلها كاملةً لتغطّي ‎z15‎ — خطوطٌ حادّةٌ وأسماءٌ
     * مقروءة، لا تمطيطُ صورةٍ جاهزة. وهذا ما لا يستطيعه أرشيفٌ نقطيّ أصلًا، وهو
     * الفرق الذي يراه المستعمل حين يقرّب على رحلةٍ في حيٍّ واحد.
     */
    val deepestZoom: Int get() = reader.maxZoom + OVERZOOM_LEVELS

    private val cache = object : LruCache<Long, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size
    }

    override fun init(pFile: File?) = Unit

    /** لا مصدر مُسمًّى: البلاطة تُعرَّف بموضعها وحده، كما في mbtiles */
    override fun setIgnoreTileSource(pIgnoreTileSource: Boolean) = Unit

    override fun getTileSources(): MutableSet<String> = mutableSetOf()

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
            val size = TILE_SIZE shl depth
            if (size > MAX_RENDER_SIZE) return null
            val full = painter.paint(tile, zoom, size)
            val slice = if (depth == 0) {
                full
            } else {
                val span = 1 shl depth
                Bitmap.createBitmap(
                    full,
                    (x % span) * TILE_SIZE,
                    (y % span) * TILE_SIZE,
                    TILE_SIZE,
                    TILE_SIZE,
                ).also { full.recycle() }
            }
            ByteArrayOutputStream(PNG_GUESS).use { out ->
                slice.compress(Bitmap.CompressFormat.PNG, 100, out)
                slice.recycle()
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
        private const val TILE_SIZE = 256

        /**
         * درجتان فوق مدى الأرشيف.
         *
         * وليست ثلاثًا: كلُّ درجةٍ تضاعف ضلع الصورة المرسومة، فالثالثة تعني ‎2048×2048‎
         * — ستّة عشر ميغابايتًا حيّةً لبلاطةٍ واحدة، وذلك ثمنٌ لا يُدفع على هاتف.
         */
        private const val OVERZOOM_LEVELS = 2

        private const val MAX_RENDER_SIZE = 1024
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

package net.gnutux.speedometer.core.map.pmtiles

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * قارئ أرشيف PMTiles نسخة ‎3‎: يُعطي بايتات بلاطةٍ بعينها من ملفٍّ بمئات الميغابايت.
 *
 * ## لماذا نكتبه بأنفسنا
 * الصيغة **فهرسٌ داخل الملفّ** لا قاعدة بيانات: ترويسةٌ بمئةٍ وسبعةٍ وعشرين بايتًا،
 * ثمّ دليلٌ جذرٌ يقود إلى أدلّةٍ ورقيّة تقود إلى البلاطات. وقراءتها لا تحتاج مكتبةً
 * أصليّة ولا محرّك رسم — وهذا بيت القصيد: به تُقرأ خريطة بلدٍ كاملة في النكهة
 * الخفيفة، بلا واحدٍ وعشرين ميغابايت من `libmaplibre.so`.
 *
 * ## ترتيب البلاطات منحنى هلبرت لا صفوفًا
 * البلاطات مرقّمةٌ برقمٍ واحدٍ متسلسلٍ عبر المستويات كلِّها، مرتّبةٍ على منحنى هلبرت
 * كي يقع المتجاوران في المكان متجاورَين في الملفّ. فتحويل ‎(z, x, y)‎ إلى ذلك الرقم
 * ([tileId]) شرطُ العثور على أيّ شيء، وخطأٌ فيه يُخرج بلاطةً من مكانٍ آخر لا فراغًا —
 * وذلك أسوأ ما يكون: خريطةٌ تُرسم صحيحةَ الشكل خاطئةَ الموضع.
 *
 * ## والدليل مضغوطٌ ومُرمَّزٌ فرقيًّا
 * الأدلّة ليست جداول: عدد المدخلات ثمّ فروقُ الأرقام ثمّ الأطوال ثمّ الإزاحات، كلُّها
 * أعدادٌ متغيّرة الطول، والإزاحةُ صفرًا تعني «تلي التي قبلها». وهي فوق ذلك مضغوطةٌ
 * بـgzip غالبًا. فلا يُقرأ من الملفّ إلّا ما يلزم: الجذر مرّةً، ثمّ ورقةٌ عند الحاجة.
 */
class PmtilesReader private constructor(
    private val file: RandomAccessFile,
    private val header: Header,
) : Closeable {

    /** ما يعنينا من الترويسة؛ وما عداه أطوالٌ لا نستعملها */
    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val leafOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    val minZoom: Int get() = header.minZoom
    val maxZoom: Int get() = header.maxZoom

    /** هل محتواه مربّعاتٌ متجهيّة؟ ‎1‎ = MVT، وما عداه صورٌ لا نرسمها بأنفسنا */
    val isVector: Boolean get() = header.tileType == TILE_TYPE_MVT

    /** الدليل الجذر يُقرأ ويُفكّ مرّةً واحدة: هو مقروءٌ في كلّ طلب بلاطة */
    private val root: Directory by lazy {
        parseDirectory(readBlock(header.rootOffset, header.rootLength.toInt()))
    }

    /**
     * بايتات البلاطة **بعد فكّ ضغطها**، أو `null` إن لم تكن في الأرشيف.
     *
     * وغيابُها ليس عطبًا: أرشيفُ بلدٍ لا يحمل بلاطات البحر ولا ما وراء حدوده، وذلك
     * هو الوضع الطبيعيّ لا الاستثناء.
     */
    @Synchronized
    fun tile(zoom: Int, x: Int, y: Int): ByteArray? {
        if (zoom < header.minZoom || zoom > header.maxZoom) return null
        val span = 1L shl zoom
        if (x < 0 || y < 0 || x >= span || y >= span) return null

        val id = tileId(zoom, x, y)
        var directory = root
        // ثلاث طبقاتٍ من الأدلّة تكفي أضخمَ أرشيف؛ والحدُّ حارسٌ على ملفٍّ مشوّهٍ
        // يُحيل دليلًا إلى نفسه فيدور القارئ إلى الأبد.
        repeat(MAX_DIRECTORY_DEPTH) {
            val entry = directory.find(id) ?: return null
            if (entry.runLength == 0L) {
                directory = parseDirectory(
                    readBlock(header.leafOffset + entry.offset, entry.length.toInt())
                )
            } else {
                if (id >= entry.tileId + entry.runLength) return null
                val raw = readBlock(header.tileDataOffset + entry.offset, entry.length.toInt())
                return decompress(raw, header.tileCompression)
            }
        }
        return null
    }

    override fun close() {
        runCatching { file.close() }
    }

    private fun readBlock(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        file.seek(offset)
        file.readFully(bytes)
        return bytes
    }

    private fun parseDirectory(raw: ByteArray): Directory =
        Directory.parse(decompress(raw, header.internalCompression))

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_GZIP -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        else -> bytes
    }

    // ————————————————————————————— الدليل —————————————————————————————

    /** مدخلةٌ واحدة: أوّلُ رقمٍ تغطّيه، وكم تغطّي، وأين هي وكم طولها */
    private class Entry(
        val tileId: Long,
        val offset: Long,
        val length: Long,
        val runLength: Long,
    )

    private class Directory(private val entries: List<Entry>) {

        /**
         * أكبرُ مدخلةٍ رقمُها لا يتجاوز المطلوب — بحثٌ ثنائيّ.
         *
         * ولا يصحّ أن تُطلب المدخلةُ المساوية وحدها: مدخلةٌ واحدة قد تغطّي مئاتِ
         * بلاطاتٍ متتابعةٍ متطابقةٍ (بحرٌ كلُّه، أو صحراءُ)، وهي `runLength` — ومن
         * طلب المساواة وحدها وجد البحر فارغًا.
         */
        fun find(id: Long): Entry? {
            var low = 0
            var high = entries.size - 1
            var found: Entry? = null
            while (low <= high) {
                val mid = (low + high) ushr 1
                val entry = entries[mid]
                when {
                    entry.tileId == id -> return entry
                    entry.tileId < id -> {
                        found = entry
                        low = mid + 1
                    }
                    else -> high = mid - 1
                }
            }
            return found
        }

        companion object {
            /**
             * الدليل أربعةُ أعمدةٍ متتابعةٍ لا صفوفٌ متجاورة: الفروقُ ثمّ الامتدادات
             * ثمّ الأطوال ثمّ الإزاحات. وذلك مقصودٌ في الصيغة — أعمدةٌ متشابهةُ القيم
             * تنضغط أحسنَ من صفوفٍ مختلطة.
             */
            fun parse(bytes: ByteArray): Directory {
                val cursor = Varint(bytes)
                val count = cursor.next().toInt()
                if (count <= 0 || count > MAX_DIRECTORY_ENTRIES) return Directory(emptyList())

                val ids = LongArray(count)
                var previous = 0L
                for (i in 0 until count) {
                    previous += cursor.next()
                    ids[i] = previous
                }

                val runs = LongArray(count) { cursor.next() }
                val lengths = LongArray(count) { cursor.next() }

                val offsets = LongArray(count)
                for (i in 0 until count) {
                    val raw = cursor.next()
                    // صفرٌ يعني «ألصِقها بذيل التي قبلها»، وما عداه الإزاحةُ زائدًا واحدًا
                    offsets[i] = if (raw == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else raw - 1
                }

                return Directory(
                    List(count) { i -> Entry(ids[i], offsets[i], lengths[i], runs[i]) }
                )
            }
        }
    }

    /** قارئ أعدادٍ متغيّرة الطول (LEB128) على مصفوفةٍ واحدة، بلا تخصيصٍ لكلّ رقم */
    private class Varint(private val bytes: ByteArray) {
        private var at = 0

        fun next(): Long {
            var result = 0L
            var shift = 0
            while (at < bytes.size) {
                val b = bytes[at++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) return result
            }
            return result
        }
    }

    companion object {
        private const val HEADER_BYTES = 127
        private const val SPEC_VERSION = 3
        private const val COMPRESSION_GZIP = 2
        private const val TILE_TYPE_MVT = 1
        private const val MAX_DIRECTORY_DEPTH = 4
        private const val MAX_DIRECTORY_ENTRIES = 1 shl 22

        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        /** يفتح الأرشيف، أو يردّ `null` إن لم يكن أرشيفًا صالحًا — بلا رمي */
        fun open(archive: File): PmtilesReader? = runCatching {
            val handle = RandomAccessFile(archive, "r")
            val head = ByteArray(HEADER_BYTES)
            runCatching { handle.readFully(head) }.getOrElse {
                handle.close()
                return null
            }
            for (i in MAGIC.indices) {
                if (head[i] != MAGIC[i]) {
                    handle.close()
                    return null
                }
            }
            if (head[MAGIC.size].toInt() != SPEC_VERSION) {
                handle.close()
                return null
            }
            PmtilesReader(handle, headerOf(head))
        }.getOrNull()

        private fun headerOf(head: ByteArray): Header = Header(
            rootOffset = readLong(head, 8),
            rootLength = readLong(head, 16),
            leafOffset = readLong(head, 40),
            tileDataOffset = readLong(head, 56),
            internalCompression = head[97].toInt() and 0xFF,
            tileCompression = head[98].toInt() and 0xFF,
            tileType = head[99].toInt() and 0xFF,
            minZoom = head[100].toInt() and 0xFF,
            maxZoom = head[101].toInt() and 0xFF,
        )

        /** ثمانيةُ بايتاتٍ صغيرةُ الطرف أوّلًا، كما تشترط الصيغة */
        private fun readLong(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            }
            return value
        }

        /**
         * رقم البلاطة على منحنى هلبرت.
         *
         * أوّلًا تُقفز مستوياتُ التكبير الأدنى كلُّها (‎4^0 + 4^1 + …‎)، ثمّ يُحسب موضعُ
         * البلاطة داخل مستواها على المنحنى. وهي الخوارزميّة المرجعيّة نفسها حرفًا
         * بحرف: كلّ اختلافٍ في الدوران يُخرج بلاطةً صحيحةَ الرسم من موضعٍ آخر.
         */
        fun tileId(zoom: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (level in 0 until zoom) acc += (1L shl level) * (1L shl level)

            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var n = (1L shl zoom) shr 1
            while (n > 0) {
                val rx = if (tx and n > 0L) 1L else 0L
                val ry = if (ty and n > 0L) 1L else 0L
                d += n * n * ((3L * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = n - 1 - tx
                        ty = n - 1 - ty
                    }
                    val swap = tx
                    tx = ty
                    ty = swap
                }
                n = n shr 1
            }
            return acc + d
        }
    }
}

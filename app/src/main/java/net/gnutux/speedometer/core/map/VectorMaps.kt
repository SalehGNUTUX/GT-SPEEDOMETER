package net.gnutux.speedometer.core.map

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * أرشيفات البلاطات **المتجهيّة** بصيغة PMTiles.
 *
 * ## لماذا PMTiles لا MBTiles متجهيّة
 * MapLibre أندرويد تدعم `pmtiles://` **رسميًّا** منذ ‎11.7.0‎، ولا تدعم MBTiles
 * المتجهيّة توثيقًا. والصيغة نفسها ملفٌّ واحدٌ بفهرسٍ داخليّ لا قاعدة SQLite، فقراءته
 * من القرص لا تحتاج محرّك قواعد بيانات ولا فتحَ اتّصال.
 *
 * ## لماذا هذا الملفّ أصلًا
 * أرشيفات البلاطات **النقطيّة** المجّانيّة لبلدٍ كامل لا وجود لها عمليًّا: سياسة بلاطات
 * OSM تمنع الجلب بالجملة، و`osm-qa-tiles` متوقّفة، وGeofabrik كلُّه خامٌّ أو متجهيّ.
 * فالطريق الوحيد إلى «خريطة بلدٍ كاملة دون اتّصال» أن نرسم المتجهيّ بأنفسنا.
 *
 * ## التعايش لا الاستبدال
 * `osmdroid` يبقى كما هو. من عنده أرشيفٌ نقطيٌّ يعمل اليوم يجب ألّا ينكسر غدًا،
 * والاستبدال يُقيَّم بعد أن يثبت المتجهيّ على أجهزةٍ حقيقيّة لا قبله.
 */
object VectorMaps {

    const val EXTENSION = "pmtiles"

    /**
     * توقيع الملفّ: `PMTiles` نصًّا ثمّ رقم النسخة بايتًا واحدًا.
     *
     * يُفحص لأنّ الامتداد وحده يكذب: من يعيد تسمية ملفٍّ إلى ‎.pmtiles‎ يُدخله مجلّد
     * الخرائط، ثمّ يفشل المحرّك عند أوّل بلاطةٍ بخطأٍ لا يفهمه المستعمل. والفحص هنا
     * نظير `holdsTiles` للنقطيّ و`headerLooksRight` في المُنزِّل — الحارس نفسه لصيغةٍ
     * أخرى، لا استثناءٌ لها.
     */
    private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

    /** النسخة الثالثة هي ما تقرؤه MapLibre؛ ما دونها صيغةٌ مهجورة لا تُفتح */
    private const val SUPPORTED_SPEC_VERSION = 3

    /** هل هذا الملفّ أرشيف PMTiles صالحًا في ظاهره؟ */
    fun looksValid(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MAGIC.size + 1) return false
        FileInputStream(file).use { input ->
            val header = ByteArray(MAGIC.size + 1)
            var filled = 0
            while (filled < header.size) {
                val read = input.read(header, filled, header.size - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled < header.size) return false
            for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return false
            header[MAGIC.size].toInt() == SUPPORTED_SPEC_VERSION
        }
    }.getOrDefault(false)

    /**
     * عنوان المصدر كما تفهمه MapLibre: `pmtiles://file:///…`.
     *
     * و`asset://` **لا يصلح** للـpmtiles — المحرّك يقرأ الملفّ بمواضعَ عشوائيّة
     * (`ranged reads`) وأصول الحزمة لا تُقرأ كذلك. وهو ما يناسبنا تمامًا: الأرشيف
     * بمئات الميغابايت لا يُحزَم مع التطبيق أصلًا بل يُنزّله المستعمل.
     */
    fun sourceUrl(file: File): String = "pmtiles://file://${file.absolutePath}"

    /** أوّل أرشيفٍ متجهيٍّ صالحٍ في مجلّدات الخرائط، أو `null` إن لم يوجد */
    fun firstAvailable(context: Context): File? =
        OfflineMaps.primaryFolder(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.extension.lowercase() == EXTENSION }
            ?.firstOrNull { looksValid(it) }

    /**
     * النمط بعد إحلال مصدر الأرشيف فيه.
     *
     * النمط أصلٌ في الحزمة (`asset://`) والأرشيف في نظام الملفّات، فلا بدّ من دمجهما
     * لحظةَ التشغيل: MapLibre تقبل نمطًا نصًّا كما تقبله عنوانًا، فيُقرأ الأصل ويُستبدل
     * فيه المعلَم ثمّ يُسلَّم نصًّا. وبديلُه كتابةُ نسخةٍ إلى المخبأ عند كلّ فتح — ملفٌّ
     * زائدٌ بلا فائدة.
     */
    fun styleJson(context: Context, archive: File): String =
        context.assets.open(styleAssetFor(archive)).bufferedReader().use { it.readText() }
            .replace(URL_PLACEHOLDER, sourceUrl(archive))

    /**
     * أيّ نمطٍ يوافق هذا الأرشيف؟
     *
     * **مخطّطان شائعان لا واحد**، وأسماء طبقاتهما مختلفةٌ كلّيًّا: `shortbread` يسمّيها
     * `place_labels` و`streets` و`water_polygons`، وProtomaps يسمّيها `places` و`roads`
     * و`water`. ونمطٌ على أحدهما يُطبَّق على الآخر **يُخرج خريطةً فارغةً بلا خطأ** —
     * كلُّ طبقةٍ تشير إلى مصدرٍ لا وجود له فلا تُرسم، والمحرّك لا يشتكي.
     *
     * فالمخطّط يُقرأ من بيانات الملفّ نفسه لا يُخمَّن. و`shortbread` هو ما توزّعه
     * BBBike لكلّ بلد — وهو أيسر ما يبلغه المستعمل — فيكون الافتراضيّ عند الشكّ.
     *
     * ## ما قيس فعلًا في أرشيف shortbread (BBBike، المغرب، ‎190‎ ميغابايت)
     * فُكَّت بلاطاته وعُدَّت معالمها، فتبيّن ما لم يكن ليُعرف بالتخمين:
     *
     * - **لا طبقةَ يابسةٍ أصلًا.** `land` في shortbread **غطاءُ أرضٍ** لا يابسة:
     *   ‎forest‎ و‎farmland‎ و‎grass‎ و‎residential‎ بُقعًا متفرّقة. فاليابسة هي
     *   **لون الخلفيّة** والبحرُ (`ocean`) يُرسم فوقها. وكان النمط يعكس ذلك: خلفيّةٌ
     *   شبه سوداء و`land` أدكن منها — فالخريطة كلُّها سوداء، وهو عين ما رآه المستعمل.
     * - **`vector_layers` في البيانات تكذب في المدى.** تعلن `streets` من ‎z14‎، وفي
     *   بلاطة ‎z7‎ ثلاثةَ عشرَ طريقًا سيّارًا ومزدوجًا، وفي ‎z10‎ ستّةً وعشرين. فالمدى
     *   لا يُؤخذ منها، والطبقاتُ تُرسم من ‎z0‎ ويُترك للبيانات أن تَشحّ.
     * - **لا `_link` في `kind`.** الوصلات صفةٌ منطقيّة مستقلّة (`link`)، وقيمُ `kind`
     *   مفردةٌ: ‎motorway‎، ‎trunk‎، ‎primary‎… وكان النمط يرشّح ‎"primary_link"‎ ونظائرَه
     *   فلا تُطابق شيئًا.
     * - **السكك داخل `streets`** بـ‎kind = rail | tram‎، فتُفصل بطبقةٍ متقطّعة وإلّا
     *   رُسمت طرقًا.
     * - **حدود `maritime`** تلفّ الساحل كلَّه؛ تُستثنى وإلّا بدا البحر مسيَّجًا.
     */
    private fun styleAssetFor(archive: File): String {
        val layers = layerNamesOf(archive)
        return when {
            PROTOMAPS_MARKERS.any { it in layers } -> PROTOMAPS_STYLE
            else -> SHORTBREAD_STYLE
        }
    }

    /**
     * أسماء طبقات المتّجهات المعلَنة في بيانات الأرشيف.
     *
     * ترويسة PMTiles ‎3‎: ‎127‎ بايتًا فيها موضعُ بيانات JSON وطولُها، وبايتُ ضغطٍ
     * داخليّ عند الإزاحة ‎97‎ (‎2‎ = gzip). فلا يُقرأ من الملفّ إلّا ترويسته وكتلةُ
     * بياناته — لا الأرشيف كلُّه، وهو بمئات الميغابايت.
     */
    private fun layerNamesOf(archive: File): Set<String> = runCatching {
        RandomAccessFile(archive, "r").use { file ->
            val header = ByteArray(HEADER_BYTES)
            file.readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val metaOffset = buffer.getLong(METADATA_OFFSET_AT)
            val metaLength = buffer.getLong(METADATA_LENGTH_AT).toInt()
            if (metaLength <= 0 || metaLength > MAX_METADATA_BYTES) return emptySet()

            val raw = ByteArray(metaLength)
            file.seek(metaOffset)
            file.readFully(raw)

            val text = if (header[INTERNAL_COMPRESSION_AT].toInt() == COMPRESSION_GZIP) {
                GZIPInputStream(raw.inputStream()).bufferedReader().use { it.readText() }
            } else {
                raw.toString(Charsets.UTF_8)
            }

            val layers = JSONObject(text).optJSONArray("vector_layers") ?: return emptySet()
            buildSet {
                for (i in 0 until layers.length()) {
                    layers.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
    }.getOrDefault(emptySet())

    /** طبقاتٌ لا توجد إلّا في مخطّط Protomaps؛ ما عداها يُقرأ shortbread */
    private val PROTOMAPS_MARKERS = setOf("earth", "landcover", "places")

    private const val SHORTBREAD_STYLE = "map/style-shortbread.json"
    private const val PROTOMAPS_STYLE = "map/style-vector.json"
    private const val URL_PLACEHOLDER = "__PMTILES_URL__"

    private const val HEADER_BYTES = 127
    private const val METADATA_OFFSET_AT = 24
    private const val METADATA_LENGTH_AT = 32
    private const val INTERNAL_COMPRESSION_AT = 97
    private const val COMPRESSION_GZIP = 2

    /** حارسٌ على قراءةٍ من ملفٍّ قد يكون مشوّهًا: بياناتُ أرشيفِ بلدٍ لا تبلغ هذا */
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
}

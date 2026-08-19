package net.gnutux.speedometer.core.map

import android.content.Context
import java.io.File
import java.io.FileInputStream

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
        context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
            .replace(URL_PLACEHOLDER, sourceUrl(archive))

    private const val STYLE_ASSET = "map/style-vector.json"
    private const val URL_PLACEHOLDER = "__PMTILES_URL__"
}

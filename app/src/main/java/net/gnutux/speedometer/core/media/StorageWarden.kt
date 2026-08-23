package net.gnutux.speedometer.core.media

import android.content.Context
import java.io.File

/**
 * حارسُ المساحة: يُبقي على القرص متّسعًا للتسجيل الجاري بحذف أقدم التسجيلات.
 *
 * ## لماذا الحذف لا التوقّف
 * كاميرا الطريق تُترك تعمل. ومن يركب ساعتين لا يقف كلَّ ربع ساعةٍ ليحذف ملفًّا، ولا
 * يريد أن يجد التصوير قد توقّف عند الدقيقة العشرين لأنّ القرص امتلأ — وأهمُّ ما في
 * الرحلة آخرُها لا أوّلُها. فالسلوك المعروف في هذا الصنف من الأدوات أن يُدار الملفّ
 * دورةً: يُحذف الأقدم ليُفسح للأحدث.
 *
 * ## ولا يُحذف إلّا ما صنعناه
 * `MediaRepository` لا يرى إلّا `Movies/GT-SPEEDOMETER` — مجلَّدنا وحده. فلا يمسّ
 * الحارسُ فيديو المستعمل ولا صورَه، مهما ضاق القرص. وهذا شرطٌ لا تحسين: أداةُ قياسٍ
 * تحذف ذكرياتِ صاحبها لا تُستعمل مرّتين.
 *
 * ## وما يُصوَّر الآن محميّ باسمه
 * الملفّ الجاري تصويره موجودٌ في المكتبة منذ لحظة البدء، فلولا حمايةٌ صريحة لَجاز أن
 * يُحذف من تحت المسجّل حين يكون هو أقدمَ ما في المجلّد — وذلك يقع فعلًا في أوّل
 * تصويرٍ على جهازٍ نظيف.
 */
class StorageWarden(
    private val context: Context,
    private val media: MediaRepository,
) {

    /** البايتات الحرّة على وحدة التخزين التي نكتب فيها، أو `null` إن تعذّر القياس */
    fun freeBytes(): Long? = runCatching {
        // مجلّد التطبيق الخارجيّ يقع على الوحدة التي تحمل `Movies` نفسها، وقياسُه
        // لا يحتاج إذنًا. أمّا `Environment.getExternalStorageDirectory` فيرمي على
        // بعض الأجهزة ويُقاس عليه ما ليس منه حين تكون بطاقةٌ ثانية.
        val probe: File = context.getExternalFilesDir(null) ?: context.filesDir
        probe.usableSpace.takeIf { it > 0L }
    }.getOrNull()

    /** هل بقي متّسعٌ يُطمأنّ إليه؟ عند تعذّر القياس **نعم**: لا نوقف تصويرًا بالشكّ */
    fun hasRoom(): Boolean = (freeBytes() ?: Long.MAX_VALUE) > LOW_WATER_BYTES

    /**
     * يحذف الأقدم فالأقدم حتّى يبلغ الحرُّ [TARGET_BYTES]، ويردّ اسم آخر ما حُذف.
     *
     * ويردّ `null` إن لم يُحذف شيء — إمّا لأنّ المساحة كافية، وإمّا لأنّه لم يبقَ في
     * مجلَّدنا ما يُحذف. والحالان مختلفان عند المتصل: الأولى لا تستدعي خبرًا،
     * والثانية تعني أنّ التصوير سيقف قريبًا مهما فعلنا.
     *
     * @param protect أسماء ملفّاتٍ لا تُمسّ — تسجيلاتُ الجلسة الجارية.
     */
    fun makeRoom(protect: Set<String> = emptySet()): Freed? {
        var free = freeBytes() ?: return null
        if (free > TARGET_BYTES) return null

        // الأقدم أوّلًا. والفيديو وحده: اللقطات لا تُذكر في المساحة (كيلوباتٌ أمام
        // ميغابايتات)، وحذفُها يُخسر المستعمل صورةً مقابل ثانيتين من التصوير.
        val oldest = media.list()
            .filter { it.isVideo && it.name !in protect }
            .sortedBy { it.dateMs }

        var count = 0
        var last: String? = null
        for (item in oldest) {
            if (free > TARGET_BYTES) break
            // القياس قبل الحذف: `MediaStore` لا يعطينا الحجم في [MediaItem]، فنقرأ
            // الحرَّ من جديد بعد كلّ حذف. نداءٌ واحد على القرص لكلّ ملفٍّ محذوف،
            // وهو أرخص من حذفٍ زائدٍ لا يُستعاد.
            if (!media.delete(item)) continue
            count++
            last = item.name
            free = freeBytes() ?: break
        }
        return last?.let { Freed(name = it, count = count, freeBytes = free) }
    }

    /** ما حُذف فعلًا: اسمُ آخره وعددُها والحرُّ بعدها */
    data class Freed(val name: String, val count: Int, val freeBytes: Long)

    companion object {
        /**
         * ‎600‎ ميغابايت: دون هذا نتصرّف.
         *
         * وليس رقمًا اعتباطيًّا: التصوير ‎1080p‎ عند ‎12‎ ميغابت في الثانية يبتلع نحو
         * ‎90‎ ميغابايت في الدقيقة، فستّمئةٍ تكفي قرابة ستّ دقائق — وهي مهلةٌ تكفي
         * لحذف ملفٍّ أو ملفّين وبدء مقطعٍ جديدٍ بلا انقطاع.
         */
        const val LOW_WATER_BYTES = 600L * 1024 * 1024

        /** نحذف حتّى يبلغ الحرُّ غيغابايتًا ونصفًا، فلا نعود إلى الحذف بعد دقيقتين */
        const val TARGET_BYTES = 1536L * 1024 * 1024

        /**
         * المقطع الاضطراريّ: ‎3‎ دقائق.
         *
         * وهو أصغر خيارٍ في [net.gnutux.speedometer.core.settings.AppSettings.SEGMENT_CHOICES]
         * لا قيمةٌ مخترعة: من يفتح الإعدادات بعدها يجد قيمةً يعرفها ويستطيع تغييرها.
         */
        const val EMERGENCY_SEGMENT_MINUTES = 3
    }
}

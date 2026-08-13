package net.gnutux.speedometer.ui

import java.util.Locale

/**
 * كل الأرقام المعروضة تُنسَّق بـ [Locale.US] عمدًا، فتخرج بالأرقام 0-9 لا ١-٩،
 * وبنقطة عشرية لا فاصلة، مهما كانت لغة الجهاز.
 */
object Fmt {
    fun speed(kmh: Float): String = String.format(Locale.US, "%d", kmh.toInt())

    fun distance(km: Double): String = String.format(Locale.US, "%.2f", km)

    fun avg(kmh: Float): String = String.format(Locale.US, "%d", kmh.toInt())

    fun hz(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun odometer(km: Double): String = String.format(Locale.US, "%06d", km.toInt())

    /** عددٌ صحيح عامّ (طول مقطع، ثوانٍ، عدد نقاط) — يمرّ من هنا كي لا تُكتب أرقامٌ هنديّة */
    fun count(value: Int): String = String.format(Locale.US, "%d", value)

    /** ساعة الجدار بخانتين: 06 لا 6، كي تستوي أعمدة الاختيار */
    fun hour(value: Int): String = String.format(Locale.US, "%02d", value)
}

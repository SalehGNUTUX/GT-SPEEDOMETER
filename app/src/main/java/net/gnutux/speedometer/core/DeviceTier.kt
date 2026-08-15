package net.gnutux.speedometer.core

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import net.gnutux.speedometer.core.settings.LiteMode

/**
 * هل نحن على جهازٍ محدود؟ وماذا نُخفّف إن كنّا؟
 *
 * **لماذا صنفٌ قائم بذاته ولا شرطٌ في كلّ موضع.** التخفيف يمسّ خمسة مواضع لا تعرف
 * بعضها: دقّة الفيديو في `CameraSession`، وحرقُ الطبقة، وعدد نقاط المسار في
 * `RouteMap`، وتحويل الصورة في جسر OsmAnd، ومعدّل تحديث الطبقة. ولو سأل كلٌّ منها
 * `isLowRamDevice` بنفسه لتفرّق الجوابُ عند أوّل تجاوزٍ يدويّ من المستعمل: يُطفئ
 * الوضع المخفَّف فتخفّ الخريطة وتبقى دقّة الفيديو منخفضة، أو العكس. فالسؤال يُسأل
 * هنا مرّةً ويُقرأ الجواب في الخمسة.
 *
 * **ولماذا `isLowRamDevice` لا عدد الأنوية ولا حجم الذاكرة.** هي الراية التي يضبطها
 * صانع الجهاز نفسه على نسخ Android (Go edition)، ويضبطها النظام على ما دون
 * جيغابايتٍ من الذاكرة. وقياسُنا نحن لعدد الأنوية أو التردّد تخمينٌ يُخطئ في
 * الاتّجاهين: هاتفٌ حديثٌ رخيص بثمانية أنوية بطيئة يُعدّ قويًّا، وجهازٌ قديمٌ قويّ
 * يُعدّ ضعيفًا. والراية على الأقلّ تقولها الجهة التي تعرف.
 *
 * ولا يُقاس هذا مرّةً واحدةً في العمليّة فحسب — بل هو ثابتٌ لعمر الجهاز. لذلك
 * `remember` حوله في التركيب كافٍ، ولا حاجة إلى `Flow`.
 */
object DeviceTier {

    /**
     * راية النظام. تُقرأ من `ActivityManager`، وغيابه (وهو لا يقع عمليًّا) يُقرأ
     * «ليس محدودًا»: التخفيف على جهازٍ قويّ خسارةٌ في الجودة بلا مقابل، وتركُه على
     * جهازٍ ضعيف بطءٌ يراه المستعمل ويشكو منه — والخطأ الثاني أهون لأنّه ظاهر.
     */
    fun isLowRamDevice(context: Context): Boolean = runCatching {
        context.applicationContext.getSystemService<ActivityManager>()?.isLowRamDevice == true
    }.getOrDefault(false)

    /** هل يعمل التخفيف الآن؟ [LiteMode.AUTO] يعني «اسأل الجهاز». */
    fun liteActive(context: Context, mode: LiteMode): Boolean = when (mode) {
        LiteMode.ON -> true
        LiteMode.OFF -> false
        LiteMode.AUTO -> isLowRamDevice(context)
    }

    /**
     * أقصى عدد نقاطٍ تُرسم في مسارٍ واحد.
     *
     * رحلةٌ فيها ‎5000‎ نقطة على شاشةٍ عرضها ‎1080‎ بكسل تعني نقاطًا كثيرةً تقع على
     * البكسل الواحد: رسمُها كلِّها عملٌ لا يُرى أثره. والتخفيف ليس حذفًا للبيانات —
     * ملفّ GPX يبقى كاملًا — وإنّما أخذُ واحدةٍ من كلّ `n` عند **الرسم** وحده.
     */
    fun maxRoutePoints(lite: Boolean): Int = if (lite) 400 else 2_000

    /**
     * عيّنةٌ من كلّ `n` نقطة، على أن تبقى الأولى والأخيرة أبدًا: هما نقطتا البداية
     * والنهاية، وإسقاط إحداهما يُزيح علامتها عن موضعها الحقيقيّ.
     */
    fun <T> thin(points: List<T>, max: Int): List<T> {
        if (max <= 2 || points.size <= max) return points
        val step = (points.size + max - 1) / max
        if (step <= 1) return points
        val out = ArrayList<T>(points.size / step + 2)
        var i = 0
        while (i < points.size) {
            out.add(points[i])
            i += step
        }
        val last = points.last()
        if (out.lastOrNull() !== last) out.add(last)
        return out
    }
}

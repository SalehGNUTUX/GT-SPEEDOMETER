package net.gnutux.speedometer.core.alert

import net.gnutux.speedometer.core.profile.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات التنبيه والمقياس.
 *
 * **لماذا يبدأ المستودع باختباره هذا دون غيره؟** لأنّه أكثر ما انكسر في المشروع:
 * ثلاثة أعطالٍ مستقلّة في ‎0.9.4‎ وحدها. ولأنّه المرشّح الطبيعيّ لأوّل اختبار:
 * [SpeedAlert.onSample] دالّةٌ نقيّة لا تلمس صوتًا ولا ساعةً ولا تفضيلًا، فمسار سرعةٍ
 * محاكًى يكشف فيها في ملّي ثانية ما لا تكشفه قيادةُ ساعة.
 *
 * وكلّ حالةٍ هنا مشدودةٌ إلى عقدٍ مكتوبٍ في الشيفرة أو إلى عطبٍ وقع فعلًا — لا إلى ما
 * تفعله الدالّة اليوم. الاختبار الذي يصف السلوك القائم يمنع تغييره ولا يمنع خطأه.
 */
class SpeedAlertTest {

    private val second = 1_000_000_000L

    // ————————————————————————— SpeedScale —————————————————————————

    /**
     * المدى = الحدّ + ‎%20‎ مقرَّبًا إلى أعلى إلى مضاعف ‎10‎.
     *
     * والقيم مختارةٌ لتصطاد الخطأ الذي دفع إلى الحساب الصحيح: ‎50 × 1.2f‎ تساوي
     * ‎60.000002‎ في الفاصلة العائمة، و`ceil` عليها تعطي ‎70‎ لا ‎60‎.
     */
    @Test
    fun `المدى يُشتقّ من الحدّ بأعدادٍ صحيحة لا بفاصلةٍ عائمة`() {
        assertEquals(60, SpeedScale.of(VehicleProfile.CAR, 50).gaugeMaxKmh)
        assertEquals(150, SpeedScale.of(VehicleProfile.CAR, 120).gaugeMaxKmh)
        assertEquals(50, SpeedScale.of(VehicleProfile.CAR, 40).gaugeMaxKmh)
        assertEquals(40, SpeedScale.of(VehicleProfile.CAR, 30).gaugeMaxKmh)
    }

    /** العتبة عند ‎%90‎ من الحدّ، ولا تنزل إلى الصفر مهما صغر الحدّ */
    @Test
    fun `العتبة تسعة أعشار الحدّ وأدناها واحد`() {
        assertEquals(45, SpeedScale.of(VehicleProfile.CAR, 50).warnKmh)
        assertEquals(1, SpeedScale.of(VehicleProfile.WALK, 1).warnKmh)
    }

    /** حدٌّ بصفرٍ يعني «بلا حدّ»: يعود كلّ شيء إلى ملفّ المركبة كما كان قبل ‎0.7.0‎ */
    @Test
    fun `بلا حدٍّ يعود المقياس إلى ملفّ المركبة`() {
        val profile = VehicleProfile.BICYCLE
        val scale = SpeedScale.of(profile, 0)
        assertEquals(profile.gaugeMaxKmh, scale.gaugeMaxKmh)
        assertEquals(profile.defaultWarnKmh, scale.warnKmh)
        assertEquals(0, scale.limitKmh)
        assertTrue("لا علامة حدٍّ تُرسم بلا حدّ", scale.limitFraction < 0f)
    }

    /**
     * بحدٍّ مضبوط يصير الحكم حكمَ الحدّ وحده: دونه فيروزيّ وفوقه أحمرُ في الحال.
     * وعتبة النسبة من المدى تبقى لحالة «بلا حدّ» وحدها — وإلّا تأخّر الأحمر إلى
     * ‎%110‎ من الحدّ، لأنّ المدى صار الحدَّ زائدَ ‎%20‎.
     */
    @Test
    fun `الأحمر عند الحدّ لا عند نسبةٍ من المدى`() {
        val scale = SpeedScale.of(VehicleProfile.CAR, 50)   // المدى 60، العتبة 45
        assertEquals(SpeedZone.NORMAL, scale.zoneOf(44f))
        assertEquals(SpeedZone.WARN, scale.zoneOf(45f))
        assertEquals(SpeedZone.DANGER, scale.zoneOf(50f))
        assertEquals(SpeedZone.DANGER, scale.zoneOf(51f))
    }

    // ————————————————————————— SpeedAlert —————————————————————————

    @Test
    fun `العبور يُصفّر مرّةً واحدة ثمّ يسكت`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.SILENT, alert.onSample(49f, 50, second))
        assertEquals(AlertAction.CROSSED, alert.onSample(50f, 50, 2 * second))
        assertEquals(AlertAction.SILENT, alert.onSample(55f, 50, 3 * second))
    }

    /** التكرار لا يبدأ قبل [SpeedAlert.SUSTAINED_SECONDS]، ثمّ يقع كلّ [SpeedAlert.REPEAT_SECONDS] */
    @Test
    fun `التجاوز الممتدّ يُكرّر الصفير بدورته`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.CROSSED, alert.onSample(60f, 50, 0L))

        val justBefore = (SpeedAlert.SUSTAINED_SECONDS - 1) * second
        assertEquals(AlertAction.SILENT, alert.onSample(60f, 50, justBefore))

        val sustained = SpeedAlert.SUSTAINED_SECONDS * second
        assertEquals(AlertAction.SUSTAINED, alert.onSample(60f, 50, sustained))

        // الدورة تبدأ من الصفيرة لا من لحظة العبور
        val tooSoon = sustained + (SpeedAlert.REPEAT_SECONDS - 1) * second
        assertEquals(AlertAction.SILENT, alert.onSample(60f, 50, tooSoon))

        val next = sustained + SpeedAlert.REPEAT_SECONDS * second
        assertEquals(AlertAction.SUSTAINED, alert.onSample(60f, 50, next))
    }

    /**
     * حارس التذبذب: سيّارةٌ تلازم الحدّ لا تُصفَّر لها صفيرةُ عبورٍ عشرات المرّات.
     * النزول إلى ‎49‎ لا يُصفّر الحالة، فالعودة فوق الحدّ لا صفيرة لها.
     */
    @Test
    fun `الملازمة للحدّ لا تُعيد صفيرة العبور`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.CROSSED, alert.onSample(50f, 50, 0L))
        assertEquals(AlertAction.SILENT, alert.onSample(49f, 50, second))
        assertEquals(AlertAction.SILENT, alert.onSample(51f, 50, 2 * second))
    }

    /**
     * **العطب الثالث في ‎0.9.4‎.** الحكم على الرقم المعروض لا على الكسر تحته: من هبط
     * إلى ‎48.7‎ — وعدّاده يقول «‎48‎»، أي «عدتُ» بعينه — ثمّ عاد فتجاوز، كان لا يسمع
     * صفيرة عبورٍ ثانية لأنّ الكسر لم يبلغ ‎48.0‎.
     */
    @Test
    fun `الحكم على الرقم المعروض لا على الكسر`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.CROSSED, alert.onSample(50f, 50, 0L))
        assertEquals(AlertAction.SILENT, alert.onSample(48.7f, 50, second))
        assertEquals(
            "العودة إلى ‎48‎ معروضةً تُصفّر الحالة، فالعبور التالي له صفيرته",
            AlertAction.CROSSED,
            alert.onSample(50f, 50, 2 * second),
        )
    }

    /** رفع الحدّ أثناء التجاوز يُسكت التنبيه فورًا ويُصفّر حالته */
    @Test
    fun `إلغاء الحدّ يُصفّر الحالة`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.CROSSED, alert.onSample(60f, 50, 0L))
        assertEquals(AlertAction.SILENT, alert.onSample(60f, 0, second))
        // وبعد إعادة الحدّ يبدأ العقد من جديد: صفيرة عبورٍ لا تكرار
        assertEquals(AlertAction.CROSSED, alert.onSample(60f, 50, 2 * second))
    }

    /**
     * `NaN` تعطي صفرًا عند البتر في آلة جافا الافتراضيّة، وهو المطلوب: عيّنةٌ بلا
     * سرعةٍ معلومة لا تُصفِّر ولا تُصفّر الحالة عن غير قصد.
     */
    @Test
    fun `عيّنةٌ بلا سرعةٍ معلومة لا تُصفّر`() {
        val alert = SpeedAlert()
        assertEquals(AlertAction.SILENT, alert.onSample(Float.NaN, 50, 0L))
        assertEquals(AlertAction.CROSSED, alert.onSample(50f, 50, second))
    }

    /**
     * الزمن `elapsedRealtimeNanos` لا يعود إلى الوراء، لكنّ الاختبار يوثّق ما يقع لو
     * عاد: لا صفير إضافيّ ولا انهيار — الفرق السالب أصغر من الدورة فيُقرأ سكوتًا.
     */
    @Test
    fun `زمنٌ إلى الوراء لا يُطلق صفيرًا زائدًا`() {
        val alert = SpeedAlert()
        val start = 100 * second
        assertEquals(AlertAction.CROSSED, alert.onSample(60f, 50, start))
        assertEquals(AlertAction.SILENT, alert.onSample(60f, 50, start - 50 * second))
    }
}

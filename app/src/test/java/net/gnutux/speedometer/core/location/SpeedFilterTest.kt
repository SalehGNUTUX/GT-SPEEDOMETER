package net.gnutux.speedometer.core.location

import net.gnutux.speedometer.core.profile.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * بوّابة الدقّة والتنعيم الأسّي للسرعة.
 *
 * ## ما يُحرس هنا
 * الرقم الذي يقرؤه الراكب. وله عدوّان متضادّان: تذبذبٌ يجعل العدّاد يرقص وقوفًا،
 * وتنعيمٌ شديد يجعله يتخلّف عن التسارع الحقيقيّ فيقرأ ‎20‎ والدرّاجة على ‎35‎. وهذا
 * الصنف يقف بينهما، فكلُّ ثابتٍ فيه مقايضةٌ تستحقّ اختبارًا.
 */
class SpeedFilterTest {

    private fun sample(accuracyM: Float) = SpeedSample(
        speedMps = 10f,
        speedFromChip = true,
        latitude = 34.0,
        longitude = -6.8,
        altitudeM = 100.0,
        bearingDeg = 0f,
        accuracyM = accuracyM,
        elapsedRealtimeNanos = 1_000_000_000L,
        utcMillis = 1_700_000_000_000L,
    )

    // ————————————————————————— بوّابة الدقّة —————————————————————————

    /**
     * دقّةٌ صفرًا تعني «لا دقّة معلومة» لا «دقّةٌ مثاليّة».
     *
     * وهي قيمةٌ تردّها بعض الأجهزة فعلًا، وقبولُها يعني إدخال عيّنةٍ لا نعرف عنها
     * شيئًا في حساب المسافة — وذلك أسوأ من إسقاطها.
     */
    @Test
    fun `الدقّة صفرًا تُرفض`() {
        assertFalse(SpeedFilter().accepts(sample(0f)))
    }

    @Test
    fun `الدقّة السيّئة تُرفض والجيّدة تُقبل`() {
        val filter = SpeedFilter()
        val limit = VehicleProfile.DEFAULT.maxAccuracyM
        assertTrue(filter.accepts(sample(limit - 1f)))
        assertTrue("الحدّ نفسه مقبول", filter.accepts(sample(limit)))
        assertFalse(filter.accepts(sample(limit + 1f)))
    }

    // ————————————————————————— التنعيم —————————————————————————

    /** أوّل قراءةٍ تمرّ كما هي: لا سابقَ لها يُنعَّم عليه */
    @Test
    fun `أوّل قراءةٍ تمرّ بلا تنعيم`() {
        assertEquals(12f, SpeedFilter().update(12f), 0.001f)
    }

    /**
     * القفزة الكبيرة تمرّ شبه كاملة.
     *
     * التسارع على الدرّاجة حقيقيٌّ وسريع، وتنعيمُه بشدّةٍ يجعل العدّاد يتخلّف تخلّفًا
     * يشعر به الراكب. فما تجاوز ‎4‎ م/ث يمرّ بوزن ‎0.95‎.
     */
    @Test
    fun `القفزة الكبيرة تمرّ شبه كاملة`() {
        val filter = SpeedFilter()
        filter.update(5f)
        val after = filter.update(15f)
        // ‎5 + (15 − 5) × 0.95 = 14.5‎
        assertEquals(14.5f, after, 0.01f)
    }

    /** والتذبذب الصغير يُهدَّأ بوزن المركبة، فلا يرقص الرقم على المتر الواحد */
    @Test
    fun `التذبذب الصغير يُهدَّأ بوزن المركبة`() {
        val profile = VehicleProfile.DEFAULT
        val filter = SpeedFilter(profile)
        filter.update(10f)
        val after = filter.update(11f)

        val expected = 10f + 1f * profile.responsiveness
        assertEquals(expected, after, 0.01f)
        assertTrue("يجب أن يتخلّف عن القراءة الخام", after < 11f)
    }

    /**
     * ما دون عتبة التوقّف يُقرأ صفرًا.
     *
     * وهي العلّة التي يراها كلُّ من ترك هاتفًا على طاولة: تذبذبُ التثبيت يعطي
     * ‎2-3‎ كم/س دائمًا، فيبدو العدّاد كأنّه يتحرّك أبدًا.
     */
    @Test
    fun `ما دون عتبة التوقّف يصير صفرًا`() {
        val profile = VehicleProfile.DEFAULT
        val filter = SpeedFilter(profile)
        filter.update(profile.stopThresholdMps * 3f)
        val after = filter.update(profile.stopThresholdMps / 4f)
        assertEquals(0f, after, 0.001f)
    }

    // ————————————————————————— التصفير وتبديل المركبة —————————————————————————

    /** بعد التصفير تُعامل القراءة التالية معاملة الأولى: لا ذاكرةَ من رحلةٍ مضت */
    @Test
    fun `التصفير يمحو الذاكرة`() {
        val filter = SpeedFilter()
        filter.update(20f)
        filter.reset()
        assertEquals(3f, filter.update(3f), 0.001f)
    }

    /**
     * تبديل المركبة يُصفّر ضمنًا.
     *
     * ثوابت السيّارة غير ثوابت الدرّاجة الهوائيّة، وحملُ قيمةٍ منعَّمةٍ بأحدهما إلى
     * الآخر يعطي قراءةً لا تخصّ أيًّا منهما.
     */
    @Test
    fun `تبديل المركبة يصفّر التنعيم`() {
        val filter = SpeedFilter(VehicleProfile.DEFAULT)
        filter.update(25f)
        filter.setProfile(VehicleProfile.entries.first { it != VehicleProfile.DEFAULT })
        assertEquals(4f, filter.update(4f), 0.001f)
    }

    /** سلسلةٌ ثابتة تستقرّ على قيمتها: التنعيم يؤخّر ولا يُزيح */
    @Test
    fun `القراءة الثابتة تستقرّ عليها`() {
        val filter = SpeedFilter()
        repeat(40) { filter.update(14f) }
        assertEquals(14f, filter.update(14f), 0.05f)
    }
}

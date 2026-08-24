package net.gnutux.speedometer.core.trip

import net.gnutux.speedometer.core.location.SpeedSample
import net.gnutux.speedometer.core.profile.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * محرّك القياس: المسافة والمدّة وزمن الحركة وأقصى سرعة.
 *
 * ## لماذا هذا أهمّ اختبارٍ في المشروع
 * التطبيق **أداة قياس**. وكلُّ ما عداه — الكاميرا والخرائط والطبقة — خدمةٌ لرقمٍ
 * يخرج من هنا. وخطأٌ في هذا الصنف لا يُسقط شيئًا ولا يُظهر رسالة: يُخرج رحلةً
 * تبدو سليمةً ومسافتُها خاطئة، فيُبنى عليها متوسّطُ سرعةٍ خاطئ ويُصدَّق.
 *
 * ## الزمن والمسافة محقونان
 * `SystemClock` و`Location` عصا أندرويد ترمي على آلة جافا. فيُمرَّر عدّادٌ صناعيّ
 * **على المحور نفسه** (نانو ثانية تصاعديّة، لا ساعة حائط — القاعدة الأولى)، ودالّةُ
 * مسافةٍ نعرف جوابها سلفًا. وبذلك يُختبر المنطق لا مكتبةُ أندرويد.
 */
class TripRecorderTest {

    /** ساعةٌ نحرّكها بأيدينا، على محور `elapsedRealtime` نفسه */
    private class FakeClock(var nanos: Long = 1_000_000_000L) {
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000
        }
    }

    /** كلّ خطوةٍ عشرة أمتار: رقمٌ نعرفه فنحاسب عليه */
    private fun fixedMeters(step: Double) = { _: SpeedSample, _: SpeedSample -> step }

    private fun recorder(
        clock: FakeClock,
        stepMeters: Double = 10.0,
        profile: VehicleProfile = VehicleProfile.DEFAULT,
    ) = TripRecorder(profile, { clock.nanos }, fixedMeters(stepMeters))

    private fun sample(
        clock: FakeClock,
        speedMps: Float,
        latitude: Double = 34.0,
        longitude: Double = -6.8,
    ) = SpeedSample(
        speedMps = speedMps,
        speedFromChip = true,
        latitude = latitude,
        longitude = longitude,
        altitudeM = 100.0,
        bearingDeg = 0f,
        accuracyM = 5f,
        elapsedRealtimeNanos = clock.nanos,
        utcMillis = 1_700_000_000_000L,
    )

    // ————————————————————————— الحالة —————————————————————————

    @Test
    fun `العيّنات تُهمَل ما لم تكن الرحلة جارية`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.onSample(sample(clock, 10f), 10f)
        assertEquals(0, recorder.points.size)
        assertEquals(TripStatus.IDLE, recorder.state.value.status)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        assertEquals(1, recorder.points.size)
    }

    // ————————————————————————— المدّة —————————————————————————

    /**
     * المدّة تدقّ من لحظة اللمس لا من أوّل قمرٍ يُرى.
     *
     * من ضغط «ابدأ» في مرآب فرأى `00:00:00` ثابتةً استنتج أنّ الرحلة لم تبدأ، وهو
     * استنتاجٌ سليم: ساعةُ إيقافٍ لا تدقّ هي ساعةٌ مطفأة.
     */
    @Test
    fun `المدّة تجري بلا عيّنةٍ واحدة`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        clock.advanceMs(5_000)
        assertEquals(5_000, recorder.elapsedNowMs())
    }

    /**
     * زمن الوقفة لا يُحسب في المدّة — وهو الفرق بين «ساعة إيقاف» و«ساعة حائط».
     */
    @Test
    fun `الإيقاف المؤقّت يطوي القطعة ولا يعدّ ما بعدها`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        clock.advanceMs(3_000)
        recorder.pause()
        clock.advanceMs(60_000)          // وقفةٌ طويلة لا تُحسب
        assertEquals(3_000, recorder.elapsedNowMs())

        recorder.resume()
        clock.advanceMs(2_000)
        assertEquals(5_000, recorder.elapsedNowMs())

        recorder.finish()
        clock.advanceMs(10_000)          // بعد الإنهاء لا شيء يزيد
        assertEquals(5_000, recorder.state.value.elapsedMs)
    }

    /** إنهاءُ رحلةٍ موقوفةٍ لا يُضيف زمن الوقفة: القطعة مطويّةٌ سلفًا */
    @Test
    fun `الإنهاء وهي موقوفة لا يضيف زمن الوقفة`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        clock.advanceMs(4_000)
        recorder.pause()
        clock.advanceMs(30_000)
        recorder.finish()

        assertEquals(4_000, recorder.state.value.elapsedMs)
    }

    // ————————————————————————— المسافة —————————————————————————

    @Test
    fun `أوّل عيّنةٍ لا تُضيف مسافة`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        assertEquals(0.0, recorder.state.value.distanceM, 0.001)
    }

    @Test
    fun `المسافة تتراكم بين العيّنات المتحرّكة`() {
        val clock = FakeClock()
        val recorder = recorder(clock, stepMeters = 10.0)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        repeat(3) {
            clock.advanceMs(1_000)
            recorder.onSample(sample(clock, 10f), 10f)
        }
        assertEquals(30.0, recorder.state.value.distanceM, 0.001)
    }

    /**
     * دون عتبة التوقّف لا تُحسب مسافة.
     *
     * وهي علّةٌ معروفة في عدّادات GPS: الهاتف واقفٌ على طاولة، وتذبذبُ التثبيت
     * يزحف بالمسافة كيلومترًا في الساعة. فمن ترك التطبيق يعمل وجد رحلةً لم يمشِها.
     */
    @Test
    fun `الوقوف لا يراكم مسافةً ولا زمن حركة`() {
        val clock = FakeClock()
        val recorder = recorder(clock, stepMeters = 10.0)
        val below = VehicleProfile.DEFAULT.stopThresholdMps / 2f

        recorder.start()
        recorder.onSample(sample(clock, below), below)
        repeat(5) {
            clock.advanceMs(1_000)
            recorder.onSample(sample(clock, below), below)
        }

        assertEquals(0.0, recorder.state.value.distanceM, 0.001)
        assertEquals(0L, recorder.state.value.movingTimeMs)
        // والنقاط تُحفظ كلُّها: الوقوف جزءٌ من المسار وإن لم يكن جزءًا من المسافة
        assertEquals(6, recorder.state.value.pointCount)
    }

    /**
     * قفزةُ تحديد موقعٍ لا تدخل المسافة.
     *
     * السقف `max(السرعة) × الزمن × 1.5 + 5`: خطوةٌ أكبر منه مستحيلةٌ فيزيائيًّا
     * بالسرعة المقيسة، فهي قفزةُ إشارةٍ لا حركة. وبلا هذا السقف تُضيف قفزةٌ واحدة
     * مئات الأمتار إلى رحلةٍ قصيرة.
     */
    @Test
    fun `القفزة غير المعقولة تُرفض`() {
        val clock = FakeClock()
        // عشر ثوانٍ عند ‎10‎ م/ث تسمح بنحو ‎155‎ مترًا؛ و‎5000‎ قفزةٌ صريحة
        val recorder = recorder(clock, stepMeters = 5_000.0)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        clock.advanceMs(1_000)
        recorder.onSample(sample(clock, 10f), 10f)

        assertEquals(0.0, recorder.state.value.distanceM, 0.001)
    }

    /** والخطوة التي تقع تحت السقف تُقبل، فالسقف حارسٌ لا مانع */
    @Test
    fun `الخطوة المعقولة تُقبل`() {
        val clock = FakeClock()
        val recorder = recorder(clock, stepMeters = 12.0)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        clock.advanceMs(1_000)
        recorder.onSample(sample(clock, 10f), 10f)

        assertEquals(12.0, recorder.state.value.distanceM, 0.001)
    }

    /**
     * الاستئناف بعد وقفةٍ لا يُقفز على فجوتها.
     *
     * `previous` يُمحى عند الإيقاف عمدًا: لولا ذلك لَحُسبت المسافة بين آخر نقطةٍ
     * قبل الوقفة وأوّل نقطةٍ بعدها — وقد تكون مدينةً أخرى.
     */
    @Test
    fun `فجوة الوقفة لا تُحسب مسافة`() {
        val clock = FakeClock()
        val recorder = recorder(clock, stepMeters = 10.0)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)
        clock.advanceMs(1_000)
        recorder.onSample(sample(clock, 10f), 10f)
        assertEquals(10.0, recorder.state.value.distanceM, 0.001)

        recorder.pause()
        clock.advanceMs(600_000)
        recorder.resume()
        recorder.onSample(sample(clock, 10f), 10f)

        // لا شيء أُضيف: أوّل عيّنةٍ بعد الاستئناف مرجعٌ جديد
        assertEquals(10.0, recorder.state.value.distanceM, 0.001)
    }

    // ————————————————————————— أقصى سرعة —————————————————————————

    /** أقصى سرعةٍ من القيمة **المنعَّمة**: قفزةٌ شاذّة لا ترفع رقمًا يُعرض ويُصدَّق */
    @Test
    fun `أقصى سرعة تحفظ الذروة ولا تنزل بعدها`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        for (speed in listOf(5f, 18f, 12f, 3f)) {
            recorder.onSample(sample(clock, speed), speed)
            clock.advanceMs(1_000)
        }
        assertEquals(18f, recorder.state.value.maxSpeedMps, 0.001f)
    }

    // ————————————————————————— مرساة الفيديو —————————————————————————

    @Test
    fun `إزاحة الفيديو تُقاس عن المرساة لا عن بداية المسار`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        recorder.onSample(sample(clock, 10f), 10f)

        clock.advanceMs(2_000)
        recorder.markVideoStart(clock.nanos)

        clock.advanceMs(3_000)
        val later = sample(clock, 10f)
        assertEquals(3_000L, recorder.videoOffsetMsOf(later))
    }

    /** فيديو بدأ **قبل** أوّل عيّنة يعطي إزاحةً سالبة — وهي صحيحة لا خطأ */
    @Test
    fun `الإزاحة تكون سالبة حين يسبق الفيديو أوّل عيّنة`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        recorder.markVideoStart(clock.nanos + 5_000_000_000L)
        val early = sample(clock, 10f)

        assertEquals(-5_000L, recorder.videoOffsetMsOf(early))
    }

    @Test
    fun `لا إزاحة بلا مرساة`() {
        val clock = FakeClock()
        val recorder = recorder(clock)
        recorder.start()
        assertEquals(null, recorder.videoOffsetMsOf(sample(clock, 10f)))
    }

    // ————————————————————————— البدء والتصفير —————————————————————————

    /** بدايةٌ ثانية تمحو الأولى محوًا تامًّا: رحلةٌ تحمل بقايا سابقتها كذبٌ صامت */
    @Test
    fun `البدء يمحو كلّ أثرٍ لرحلةٍ سابقة`() {
        val clock = FakeClock()
        val recorder = recorder(clock, stepMeters = 10.0)

        recorder.start()
        recorder.onSample(sample(clock, 20f), 20f)
        clock.advanceMs(1_000)
        recorder.onSample(sample(clock, 20f), 20f)
        recorder.markVideoStart(clock.nanos)
        recorder.finish()

        clock.advanceMs(1_000)
        recorder.start()

        val state = recorder.state.value
        assertEquals(TripStatus.RUNNING, state.status)
        assertEquals(0.0, state.distanceM, 0.001)
        assertEquals(0f, state.maxSpeedMps, 0.001f)
        assertEquals(0, state.pointCount)
        assertEquals(0L, state.movingTimeMs)
        assertEquals(null, recorder.videoAnchorNanos)
        assertEquals(null, recorder.trackStartNanos)
        assertTrue(recorder.points.isEmpty())
    }

    /** أوّل عيّنةٍ هي مرجع المسار، وتُلتقط مرّةً لا تُحدَّث مع كلّ عيّنة */
    @Test
    fun `مرجع المسار أوّل عيّنةٍ لا آخرها`() {
        val clock = FakeClock()
        val recorder = recorder(clock)

        recorder.start()
        val first = clock.nanos
        recorder.onSample(sample(clock, 10f), 10f)
        clock.advanceMs(5_000)
        recorder.onSample(sample(clock, 10f), 10f)

        assertEquals(first, recorder.trackStartNanos)
    }
}

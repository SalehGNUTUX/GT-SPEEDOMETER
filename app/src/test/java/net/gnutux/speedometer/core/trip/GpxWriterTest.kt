package net.gnutux.speedometer.core.trip

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.gnutux.speedometer.core.location.SpeedSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * كاتب GPX: الملفّ الذي يخرج من التطبيق إلى العالم.
 *
 * ## لماذا يُختبر بمحلِّلٍ حقيقيّ لا بمطابقة نصّ
 * لأنّ الوعد ليس «أن تحتوي السلسلة كذا»، بل **أن يقرأ الملفَّ كلُّ قارئ GPX**. فيُحلَّل
 * الناتج بمحلِّل آلة جافا نفسه: ما لا يمرّ منه لا يمرّ من Strava ولا من OsmAnd.
 *
 * ## والفاصلة العشريّة مسألةُ حياةٍ أو موت
 * القاعدة الخامسة تشترط `Locale.US` في الملفّ كما في الواجهة. وعلى جهازٍ عربيّ
 * التنسيق الافتراضيّ يكتب `34,0123` — وكلُّ قارئ GPX في الدنيا يقرؤها خطأً أو
 * يرفض الملفّ. وهو عطبٌ لا يظهر إلّا عند مستعملٍ بلغةٍ بعينها، فلا يُمسك إلّا هنا.
 */
class GpxWriterTest {

    private val file: File = File.createTempFile("gt-test-", ".gpx")

    @After
    fun cleanUp() {
        file.delete()
    }

    private fun sample(
        offsetMs: Long,
        latitude: Double = 34.0123456,
        longitude: Double = -6.8234567,
        speedMps: Float = 12.345f,
        fromChip: Boolean = true,
        provider: String = SpeedSample.PROVIDER_GPS,
    ) = SpeedSample(
        speedMps = speedMps,
        speedFromChip = fromChip,
        latitude = latitude,
        longitude = longitude,
        altitudeM = 123.456,
        bearingDeg = 90f,
        accuracyM = 4.25f,
        elapsedRealtimeNanos = START_NANOS + offsetMs * 1_000_000,
        utcMillis = START_UTC + offsetMs,
        provider = provider,
    )

    private fun write(
        points: List<SpeedSample>,
        videoFileName: String? = null,
        videoOffsetMs: Long? = null,
        durationMs: Long? = null,
    ) {
        GpxWriter.write(
            file = file,
            name = "رحلة الاختبار",
            points = points,
            trackStartUtcMillis = START_UTC,
            trackStartNanos = START_NANOS,
            videoFileName = videoFileName,
            videoOffsetMs = videoOffsetMs,
            durationMs = durationMs,
        )
    }

    private fun parse(): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.firstText(tag: String): String? =
        getElementsByTagNameNS("*", tag).item(0)?.textContent

    // ————————————————————————— السلامة —————————————————————————

    @Test
    fun `الملفّ الناتج XML صحيحٌ يقرؤه محلِّلٌ قياسيّ`() {
        write(listOf(sample(0), sample(1_000), sample(2_000)))
        val root = parse()

        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("GT-SPEEDOMETER", root.getAttribute("creator"))
        assertEquals(3, root.getElementsByTagNameNS("*", "trkpt").length)
    }

    /** مسارٌ بلا نقطة: ملفٌّ صالحٌ فارغ، لا ملفٌّ مشوَّه ولا انهيار */
    @Test
    fun `مسارٌ بلا نقاطٍ يُكتب ملفًّا صالحًا`() {
        write(emptyList())
        val root = parse()
        assertEquals(0, root.getElementsByTagNameNS("*", "trkpt").length)
    }

    /** الاسم يُهرَّب: `&` و`<` في اسم رحلةٍ تكسر الملفّ لولا التهريب */
    @Test
    fun `الاسم يُهرَّب فلا يكسر الملفّ`() {
        GpxWriter.write(
            file = file,
            name = "رحلة <الصباح> & المساء",
            points = listOf(sample(0)),
            trackStartUtcMillis = START_UTC,
            trackStartNanos = START_NANOS,
        )
        assertEquals("رحلة <الصباح> & المساء", parse().firstText("name"))
    }

    // ————————————————————————— الأرقام —————————————————————————

    /**
     * لا فاصلةَ عشريّة في الملفّ مهما كانت لغة الجهاز.
     *
     * يُختبر بلغةٍ عربيّةٍ مفروضة: هي الحال التي يقع فيها العطب فعلًا عند مستعملينا.
     */
    @Test
    fun `الأرقام بنقطةٍ عشريّة ولو كانت لغة الجهاز عربيّة`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "MA"))
            write(listOf(sample(0)))
        } finally {
            java.util.Locale.setDefault(original)
        }

        val text = file.readText()
        assertTrue("لا فاصلةَ في إحداثيّات", !text.contains("lat=\"34,"))
        assertTrue("لا أرقامَ هنديّة", text.none { it in '٠'..'٩' })

        val point = parse().getElementsByTagNameNS("*", "trkpt").item(0) as Element
        assertEquals(34.0123456, point.getAttribute("lat").toDouble(), 1e-7)
        assertEquals(-6.8234567, point.getAttribute("lon").toDouble(), 1e-7)
    }

    /**
     * لكلّ حقلٍ منازلُه، والتقريب إلى أقرب لا بالبتر.
     *
     * والمنازل ليست زينة: `ele` بمنزلتين لأنّ سنتيمترات الارتفاع ضجيجٌ، و`hdop`
     * بمنزلةٍ لأنّ الدقّة نفسها تقديرٌ، و`speed` بثلاث لأنّها بالمتر في الثانية
     * فالمنزلة الثالثة نحو ‎3.6‎ كم/س من ألف — وهي تُقرأ.
     */
    @Test
    fun `الارتفاع والدقّة والسرعة تُكتب بمنازلها`() {
        write(listOf(sample(0)))
        val root = parse()
        // ‎123.456‎ → منزلتان
        assertEquals("123.46", root.firstText("ele"))
        // ‎4.25‎ → منزلةٌ واحدة، تقريبًا إلى أقرب لا بترًا
        assertEquals("4.3", root.firstText("hdop"))
        // ‎12.345‎ → ثلاث منازل بلا تبديل
        assertEquals("12.345", root.firstText("speed"))
    }

    // ————————————————————————— الزمن —————————————————————————

    /**
     * زمن كلّ نقطةٍ من **فرق `elapsedRealtime`** لا من `utcMillis` الخاصّ بها.
     *
     * فلو قفزت ساعة الجهاز في منتصف الرحلة — مزامنةُ شبكةٍ أو تبديلُ منطقةٍ زمنيّة —
     * بقي تسلسل الزمن في الملفّ سليمًا. وهنا نُعطي عيّنةً ساعتُها المدنيّة مكذوبة
     * ونتحقّق أنّ الملفّ لم يصدّقها.
     */
    @Test
    fun `زمن النقطة يُشتقّ من المحور لا من ساعة العيّنة`() {
        val liar = sample(1_000).copy(utcMillis = START_UTC + 900_000)
        write(listOf(sample(0), liar))

        val times = parse().getElementsByTagNameNS("*", "trkpt").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).firstText("time")!! }
        }
        assertEquals("2023-11-14T22:13:20.000Z", times[0])
        assertEquals("2023-11-14T22:13:21.000Z", times[1])
    }

    @Test
    fun `الزمن يُكتب UTC بصيغة ISO`() {
        write(listOf(sample(0)))
        val time = parse().firstText("time")!!
        assertTrue("يجب أن ينتهي بـZ: $time", time.endsWith("Z"))
        assertEquals("2023-11-14T22:13:20.000Z", time)
    }

    // ————————————————————————— بيانات الفيديو —————————————————————————

    /**
     * إزاحة الفيديو تُحفظ صراحةً، وتصحّ سالبةً.
     *
     * وهي مفتاح المزامنة كلِّه: بها تُعاد محاذاة الطبقة على الملفّ لاحقًا بلا تخمين.
     */
    @Test
    fun `اسم الفيديو وإزاحته يُحفظان`() {
        write(listOf(sample(0)), videoFileName = "ride-01.mp4", videoOffsetMs = -1_500)
        val root = parse()
        assertEquals("ride-01.mp4", root.firstText("video"))
        assertEquals("-1500", root.firstText("videoOffsetMs"))
    }

    /** بلا فيديو لا تُكتب حقولُه: حقلٌ فارغ يُقرأ «فيديو اسمُه فراغ» */
    @Test
    fun `بلا فيديو لا تُكتب حقوله`() {
        write(listOf(sample(0)))
        val root = parse()
        assertEquals(0, root.getElementsByTagNameNS("*", "video").length)
        assertEquals(0, root.getElementsByTagNameNS("*", "videoOffsetMs").length)
    }

    /**
     * المدّة تُكتب صراحةً لأنّها لا تُستنتج من فروق النقاط.
     *
     * تُحسب من لحظة الضغط وتُخصم منها الوقفات، فجمعُ فروق النقاط يُسقط ما قبل أوّل
     * عيّنةٍ وكلَّ انقطاعٍ في الإشارة — وكان السجلّ يعرض مدّةً تخالف ما رآه صاحب
     * الرحلة على الشاشة.
     */
    @Test
    fun `المدّة تُكتب صراحةً ولو خالفت فروق النقاط`() {
        write(listOf(sample(0), sample(2_000)), durationMs = 95_000)
        assertEquals("95000", parse().firstText("durationMs"))
    }

    // ————————————————————————— مصدر العيّنة —————————————————————————

    /**
     * المصدر يُكتب لكلّ نقطة: من الشريحة أم مشتقٌّ، ومن `gps` أم من `fused`.
     *
     * وهو تطبيقٌ للقاعدة الثامنة في الملفّ لا في الواجهة وحدها: من يفحص مسارَه
     * بعد شهرٍ يجب أن يعرف أيَّ نقطةٍ جاءت من المصدر الاحتياطيّ.
     */
    @Test
    fun `مصدر كلّ نقطةٍ يُكتب`() {
        write(
            listOf(
                sample(0, fromChip = true, provider = SpeedSample.PROVIDER_GPS),
                sample(1_000, fromChip = false, provider = "fused"),
            )
        )
        val points = parse().getElementsByTagNameNS("*", "trkpt")
        val first = points.item(0) as Element
        val second = points.item(1) as Element

        assertEquals("chip", first.firstText("src"))
        assertEquals(SpeedSample.PROVIDER_GPS, first.firstText("provider"))
        assertEquals("derived", second.firstText("src"))
        assertEquals("fused", second.firstText("provider"))
    }

    @Test
    fun `فضاءات الأسماء معلَنة`() {
        write(listOf(sample(0)))
        val text = file.readText()
        assertNotNull(text)
        assertTrue(text.contains("http://www.topografix.com/GPX/1/1"))
        assertTrue(text.contains("garmin.com/xmlschemas/TrackPointExtension"))
    }

    private companion object {
        /** ‎2023-11-14T22:13:20Z‎ — لحظةٌ ثابتة كي تكون النصوص المتوقَّعة ثابتة */
        const val START_UTC = 1_700_000_000_000L
        const val START_NANOS = 5_000_000_000L
    }
}

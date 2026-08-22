package net.gnutux.speedometer.core.map

import java.io.File
import net.gnutux.speedometer.core.map.mvt.MvtTile
import net.gnutux.speedometer.core.map.pmtiles.PmtilesReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * قراءةُ أرشيفٍ **حقيقيّ** من طرفه إلى طرفه: ترويسة، فدليل، فبلاطة، ففكُّ مربّعها.
 *
 * ## لماذا لا يكفي اختبار الفهرسة
 * [PmtilesIndexTest] يُثبت أنّ الرقم صحيح، ولا يُثبت أنّ الدليل يُقرأ. وبين الاثنين
 * أشياءُ لا تُمسك إلّا بملفٍّ حقيقيّ: الترميز الفرقيّ للأرقام، والإزاحةُ صفرًا التي
 * تعني «تلي ما قبلها»، والأدلّةُ الورقيّة، وضغطُ gzip داخل الملفّ. وكلُّها تفشل بصمتٍ
 * فتُخرج `null` — أي خريطةً بيضاء لا رسالةَ خطأ.
 *
 * ## والأرشيف لا يُودَع في المستودع
 * مئةٌ وتسعون ميغابايتًا لا تُودَع لأجل اختبار. فيُقرأ مسارُه من البيئة، ويُتخطّى
 * الاختبار إن لم يوجد — لا يُخفق. وهذا تنازلٌ معلومٌ: من لم يُشغّله لم يفحص شيئًا.
 *
 * ```bash
 * GT_PMTILES=/مسار/morocco.pmtiles ./gradlew :app:testLiteDebugUnitTest
 * ```
 *
 * والأرقام أدناه مقيسةٌ بتنفيذٍ مستقلٍّ (مكتبة `pmtiles` وفاكُّ MVT في بايثون) على
 * أرشيف BBBike للمغرب، لا بتشغيل شيفرتنا وتدوين ما خرج منها.
 */
class PmtilesArchiveReadTest {

    private val archive: File?
        get() = System.getenv(ARCHIVE_ENV)?.let(::File)?.takeIf { it.isFile }

    @Test
    fun `يقرأ الترويسة ومدى التكبير`() {
        val file = archive
        assumeTrue("لا أرشيف: عيّن $ARCHIVE_ENV", file != null)

        val reader = PmtilesReader.open(file!!)
        assertNotNull("الأرشيف لم يُفتح", reader)
        reader!!.use {
            assertTrue("محتواه ليس مربّعاتٍ متجهيّة", it.isVector)
            assertEquals(0, it.minZoom)
            assertEquals(14, it.maxZoom)
        }
    }

    /**
     * ثلاث بلاطاتٍ على مستوياتٍ مختلفة فوق الرباط، بأطوالها بعد الفكّ.
     *
     * والمستويات الثلاثة مقصودة: ‎z10‎ يقع في الدليل الجذر، و‎z14‎ في دليلٍ ورقيّ.
     * فمن قرأ الجذر ولم يتبع الورق ينجح في الأوّل ويفشل في الثالث — وهو عطبٌ يُخرج
     * خريطةً تعمل عند التكبير الواسع وتبيضّ عند القريب.
     */
    @Test
    fun `يستخرج بلاطاتٍ من الجذر ومن الأدلّة الورقيّة`() {
        val file = archive
        assumeTrue("لا أرشيف: عيّن $ARCHIVE_ENV", file != null)

        PmtilesReader.open(file!!)!!.use { reader ->
            for ((coords, expected) in EXPECTED_TILE_BYTES) {
                val (zoom, x, y) = coords
                val tile = reader.tile(zoom, x, y)
                assertNotNull("لا بلاطة عند z$zoom/$x/$y", tile)
                assertEquals("طول بلاطة z$zoom/$x/$y بعد الفكّ", expected, tile!!.size)
            }
        }
    }

    /**
     * ما خارج الحدود يردّ `null` ولا يرمي.
     *
     * أرشيفُ بلدٍ لا يحمل بلاطات ما وراء حدوده، وذلك هو الوضع الطبيعيّ لا الاستثناء.
     * ورميُ استثناءٍ هنا يعني انهيارًا في خيط البلاطات كلَّما اقترب المستعمل من الحدّ.
     */
    @Test
    fun `ما لا وجود له يردّ فراغًا لا استثناءً`() {
        val file = archive
        assumeTrue("لا أرشيف: عيّن $ARCHIVE_ENV", file != null)

        PmtilesReader.open(file!!)!!.use { reader ->
            assertEquals(null, reader.tile(14, 0, 0))
            assertEquals(null, reader.tile(20, 5, 5))
            assertEquals(null, reader.tile(-1, 0, 0))
            assertEquals(null, reader.tile(10, 1 shl 10, 0))
        }
    }

    /**
     * المربّع المتجهيّ يُفكّ إلى الطبقات نفسها بالأعداد نفسها.
     *
     * والأعداد هنا هي البرهان على أنّ فكّ الهندسة سليم: الأمرُ والعدّادُ والفروقُ
     * المرمَّزة بترميز الإشارة المتعرّج. وخطأٌ في أيٍّ منها يُخرج معالمَ أقلَّ أو أكثر،
     * أو خطوطًا تنطلق من زاوية البلاطة إلى كلّ اتّجاه.
     */
    @Test
    fun `يفكّ المربّع المتجهيّ إلى طبقاتٍ بأعدادها`() {
        val file = archive
        assumeTrue("لا أرشيف: عيّن $ARCHIVE_ENV", file != null)

        PmtilesReader.open(file!!)!!.use { reader ->
            val tile = reader.tile(14, 7880, 6543)!!
            val layers = MvtTile.decode(tile)

            assertEquals(1555, layers["streets"]?.features?.size)
            assertEquals(2907, layers["buildings"]?.features?.size)
            assertEquals(491, layers["street_labels"]?.features?.size)
            assertEquals(373, layers["land"]?.features?.size)
            assertEquals(4096, layers["streets"]?.extent)

            // والترشيح يُسقط ما لم يُطلب ولا يمسّ ما طُلب
            val wanted = MvtTile.decode(tile, setOf("streets", "buildings"))
            assertEquals(setOf("streets", "buildings"), wanted.keys)
            assertEquals(1555, wanted["streets"]?.features?.size)
        }
    }

    /**
     * الخصائص تصل بأسمائها وقيمها، والهندسة بإحداثيّاتٍ داخل مدى البلاطة.
     *
     * `kind` هو ما يُبنى عليه الرسم كلُّه — لونُ الطريق وعرضُه وترتيبُه — فوصولُه
     * فارغًا يعني خريطةً بلا طرق. وحدودُ الإحداثيّات تكشف خطأ التراكم: قيمٌ مطلقةٌ
     * قُرئت فروقًا تخرج عن المدى بأضعافٍ فورًا.
     */
    @Test
    fun `الخصائص والهندسة تصل سليمة`() {
        val file = archive
        assumeTrue("لا أرشيف: عيّن $ARCHIVE_ENV", file != null)

        PmtilesReader.open(file!!)!!.use { reader ->
            val layers = MvtTile.decode(reader.tile(14, 7880, 6543)!!, setOf("streets"))
            val streets = layers["streets"]!!

            val kinds = streets.features.mapNotNull { it.properties["kind"] }.toSet()
            assertTrue("لا قيمة kind واحدة", kinds.isNotEmpty())
            assertTrue("‏primary مفقود من $kinds", "primary" in kinds)
            // ولا وصلاتٍ في `kind`: هي صفةٌ منطقيّة مستقلّة في shortbread
            assertTrue("‏primary_link لا ينبغي أن يوجد", "primary_link" !in kinds)

            val coordinates = streets.features.flatMap { it.rings.toList() }
            assertTrue("لا هندسة", coordinates.isNotEmpty())
            val outOfRange = coordinates.count { ring ->
                ring.any { it < -MARGIN || it > 4096 + MARGIN }
            }
            assertTrue("$outOfRange حلقةً خارج مدى البلاطة", outOfRange == 0)
        }
    }

    private companion object {
        const val ARCHIVE_ENV = "GT_PMTILES"

        /** المربّعات تمتدّ قليلًا خارج البلاطة عمدًا كي تلتحم عند الحواف */
        const val MARGIN = 1024

        /** ‎(z, x, y)‎ فوق الرباط، وطولُ كلٍّ منها بعد فكّ الضغط */
        val EXPECTED_TILE_BYTES = listOf(
            Triple(10, 492, 408) to 17760,
            Triple(12, 1970, 1635) to 76434,
            Triple(14, 7880, 6543) to 237643,
        )
    }
}

package net.gnutux.speedometer.core.map

import net.gnutux.speedometer.core.map.pmtiles.PmtilesReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * منحنى هلبرت: تحويل ‎(z, x, y)‎ إلى رقم البلاطة في PMTiles.
 *
 * ## لماذا يُختبَر هذا بالذات
 * لأنّ خطأه **لا يظهر عطبًا**. دليلُ الأرشيف مرتّبٌ على هذا الرقم، فبحثٌ برقمٍ مزاحٍ
 * يجد بلاطةً موجودةً سليمةَ الرسم — من مكانٍ آخر. فتخرج خريطةٌ كاملةُ الشوارع
 * والأسماء لمدينةٍ ليست مدينتَك، ولا شيء في الشاشة يقول ذلك. وما لا يُعلن عن نفسه
 * يُمسَك بالاختبار أو لا يُمسَك أبدًا.
 *
 * ## والقيم من المواصفة لا من شيفرتنا
 * حُسبت بتنفيذٍ مستقلٍّ للخوارزميّة المرجعيّة، لا بتشغيل ما نختبره وتدوين ما خرج —
 * وذاك اختبارٌ يُثبّت الخطأ ولا يكشفه.
 */
class PmtilesIndexTest {

    @Test
    fun `الجذر صفر`() {
        assertEquals(0L, PmtilesReader.tileId(0, 0, 0))
    }

    /**
     * المستوى الأوّل يدور: ‎(0,0)‎ ثمّ ‎(0,1)‎ ثمّ ‎(1,1)‎ ثمّ ‎(1,0)‎.
     *
     * وهذا الدوران بعينه هو ما يُخطئ فيه من نقل الخوارزميّة على عجل: ترتيبٌ صفّيٌّ
     * بسيط يعطي ‎(1,0) = 2‎ وهو هنا ‎4‎.
     */
    @Test
    fun `المستوى الأوّل يتبع دوران المنحنى لا ترتيب الصفوف`() {
        assertEquals(1L, PmtilesReader.tileId(1, 0, 0))
        assertEquals(2L, PmtilesReader.tileId(1, 0, 1))
        assertEquals(3L, PmtilesReader.tileId(1, 1, 1))
        assertEquals(4L, PmtilesReader.tileId(1, 1, 0))
    }

    @Test
    fun `مستوياتٌ عميقة تطابق المرجع`() {
        assertEquals(7L, PmtilesReader.tileId(2, 1, 1))
        assertEquals(506L, PmtilesReader.tileId(5, 15, 12))
        assertEquals(519301L, PmtilesReader.tileId(10, 492, 408))
        assertEquals(8308830L, PmtilesReader.tileId(12, 1970, 1635))
        assertEquals(132941290L, PmtilesReader.tileId(14, 7880, 6543))
    }

    /**
     * كلُّ مستوًى يبدأ بعد آخر ما قبله بواحد.
     *
     * وهو ما يمنع تداخل المستويات: بلاطةُ ‎z10‎ ورقمُها يقع في مدى ‎z11‎ تعني أنّ
     * أرشيفَ بلدٍ كامل يقرأ بعضُه بعضًا.
     */
    @Test
    fun `مدى كلّ مستوًى يلي ما قبله بلا تداخل`() {
        for (zoom in 0..12) {
            val first = PmtilesReader.tileId(zoom, 0, 0)
            val span = 1 shl zoom
            val previousLast = if (zoom == 0) -1L else PmtilesReader.tileId(zoom - 1, span / 2 - 1, 0)
            assertEquals(previousLast + 1, first)
        }
    }
}

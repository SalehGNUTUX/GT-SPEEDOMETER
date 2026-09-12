package net.gnutux.speedometer.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * بصمةُ الشهادة نصًّا — الجزءُ النقيُّ من بوّابة التوقيع.
 *
 * **ولماذا يستحقّ هذا اختبارًا؟** لأنّ البوّابة تقارن نصًّا بنصّ، وخطأٌ في التحويل
 * لا يظهر عطبًا بل يظهر **صمتًا**: تُخفى واجهةُ التحديث عن كلّ المستعملين، وهم
 * لا يشتكون ممّا لا يرونه. والاختبار هنا يقع في ثانيةٍ على آلة جافا، والعطبُ
 * بدونه يُكتشف بعد إصدار.
 *
 * وبقيّةُ البوّابة — قراءةُ شهادة الحزمة الجارية — تحتاج `PackageManager`، فتُفحص
 * على جهازٍ في `TESTING.md`.
 */
class UpdateFingerprintTest {

    /** متّجهٌ محسوبٌ خارج الشيفرة: `printf 'gt-speedometer' | sha256sum` */
    @Test
    fun `البصمة تطابق ما يقوله sha256sum`() {
        assertEquals(
            "ca43e7445a1ba96659a6ffaca2f8cf5e94e32011e593359b1e450dfbcf05cb44",
            UpdateChecker.fingerprintOf("gt-speedometer".toByteArray()),
        )
    }

    /**
     * **العطب الكلاسيكيّ:** بايتٌ دون ‎0x10‎ يُكتب بحرفٍ واحد إن استُعملت
     * `Integer.toHexString`، فتنزاح البصمةُ كلُّها ولا تطابق شيئًا أبدًا.
     *
     * والمتّجه أعلاه فيه بايتان دون ‎0x10‎ (`0d` و`05`)، فالطول وحده يكشفها.
     */
    @Test
    fun `الصفر البادئ لا يسقط`() {
        val hex = UpdateChecker.fingerprintOf("fdroid".toByteArray())
        assertEquals(
            "23b76c9915cf03ba1674df9c9c95e9f814c21305addc45aab553aa0103a472af",
            hex,
        )
        assertEquals(64, hex.length)
    }

    /** الحروف صغيرةٌ بلا نقطتين: هكذا تُكتب في `signing-fingerprints.txt` */
    @Test
    fun `الصيغة صغيرةٌ بلا فواصل`() {
        val hex = UpdateChecker.fingerprintOf(byteArrayOf(0, 1, 2))
        assertEquals(hex.lowercase(), hex)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

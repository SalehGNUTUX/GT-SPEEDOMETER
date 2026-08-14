package net.gnutux.speedometer

import android.app.Application
import net.gnutux.speedometer.core.TripEngine
import net.gnutux.speedometer.core.map.OfflineMaps

class SpeedoApp : Application() {

    /** حقن يدوي: نسخة واحدة يشترك فيها كل من الواجهة والخدمة */
    lateinit var engine: TripEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = TripEngine(this)
        // فحصُ أرشيفات الخرائط يبدأ الآن لا عند فتح تبويب الرحلات: المسح يقع على
        // خيط الإدخال/الإخراج، فحين يفتح المستعمل خريطةً أوّل مرّة يكون الجواب جاهزًا
        // ولا تُطلب بلاطةٌ من الإنترنت ريثما نعرف أنّ عندنا نسخةً محلّيّة.
        OfflineMaps.of(this)
    }
}

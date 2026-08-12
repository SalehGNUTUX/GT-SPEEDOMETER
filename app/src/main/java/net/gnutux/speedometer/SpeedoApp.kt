package net.gnutux.speedometer

import android.app.Application
import net.gnutux.speedometer.core.TripEngine

class SpeedoApp : Application() {

    /** حقن يدوي: نسخة واحدة يشترك فيها كل من الواجهة والخدمة */
    lateinit var engine: TripEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = TripEngine(this)
    }
}

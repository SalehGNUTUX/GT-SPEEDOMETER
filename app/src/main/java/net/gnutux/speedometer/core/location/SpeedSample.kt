package net.gnutux.speedometer.core.location

/**
 * عيّنة موقع واحدة.
 *
 * [elapsedRealtimeNanos] هو محور الزمن الوحيد المعتمد في التطبيق: هو ساعة تصاعدية
 * لا تقفز مع مزامنة الشبكة، وعليها تقوم مزامنة المسار بالفيديو. أما [utcMillis]
 * فلا يُستعمل إلا في كتابة ملف GPX الذي يوجب زمنًا مدنيًّا.
 */
data class SpeedSample(
    val speedMps: Float,
    /** هل جاءت السرعة من الشريحة (إزاحة دوبلر) أم اشتُقّت من فرق الموضع؟ */
    val speedFromChip: Boolean,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val bearingDeg: Float,
    val accuracyM: Float,
    val elapsedRealtimeNanos: Long,
    val utcMillis: Long,
    /** "gps" أو "fused" — يُكتب في GPX كي تُعرف العيّنات التي جاءت من المصدر الاحتياطي */
    val provider: String = PROVIDER_GPS,
) {
    companion object {
        const val PROVIDER_GPS = "gps"
        const val PROVIDER_FUSED = "fused"
    }

    val speedKmh: Float get() = speedMps * 3.6f
}

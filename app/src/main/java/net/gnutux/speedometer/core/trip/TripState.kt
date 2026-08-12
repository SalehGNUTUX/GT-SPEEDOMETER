package net.gnutux.speedometer.core.trip

enum class TripStatus { IDLE, RUNNING, PAUSED, FINISHED }

data class TripState(
    val status: TripStatus = TripStatus.IDLE,
    /** السرعة الآنية بعد التنعيم */
    val speedMps: Float = 0f,
    val distanceM: Double = 0.0,
    /** الزمن الكلي منذ البداية، دون فترات الإيقاف المؤقّت */
    val elapsedMs: Long = 0L,
    /** زمن الحركة فقط — عليه يُحسب المتوسّط، وإلا سحبته إشارات المرور إلى أسفل */
    val movingTimeMs: Long = 0L,
    val maxSpeedMps: Float = 0f,
    val pointCount: Int = 0,
) {
    val speedKmh: Float get() = speedMps * 3.6f
    val maxSpeedKmh: Float get() = maxSpeedMps * 3.6f
    val distanceKm: Double get() = distanceM / 1000.0
    val avgSpeedKmh: Float
        get() = if (movingTimeMs < 1000L) 0f else (distanceM / (movingTimeMs / 1000.0) * 3.6).toFloat()
}

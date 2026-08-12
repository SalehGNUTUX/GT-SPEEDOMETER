package net.gnutux.speedometer.core.profile

import androidx.annotation.StringRes
import net.gnutux.speedometer.R

/**
 * ملف المركبة. يغيّر مدى العداد وعتبة التحذير وشدة التنعيم وعتبة اعتبار المركبة واقفة.
 *
 * الدراجة أولًا: تسارعها أعلى من السيارة وأقل كتلة، فتحتاج تنعيمًا أخف (استجابة أسرع)
 * وعتبة توقّف أعلى قليلًا لأن اهتزاز المحرك في التوقّف يربك تحديد الموقع.
 */
enum class VehicleProfile(
    @StringRes val label: Int,
    /** أقصى قيمة على قرص العداد (كم/س) */
    val gaugeMaxKmh: Int,
    /** عتبة التحذير الافتراضية (كم/س) */
    val defaultWarnKmh: Int,
    /**
     * وزن العيّنة الجديدة في التنعيم (0-1). كلّما ارتفع كانت الاستجابة أسرع
     * والتذبذب أكثر. الدراجة أعلى من السيارة لأن تسارعها أسرع.
     */
    val responsiveness: Float,
    /** ما دون هذه السرعة تُعتبر المركبة واقفة (كم/س) */
    val stopThresholdKmh: Float,
    /** أسوأ دقة مقبولة للعيّنة (متر)؛ ما فوقها تُرفض */
    val maxAccuracyM: Float,
) {
    CAR(R.string.vehicle_car, gaugeMaxKmh = 200, defaultWarnKmh = 120, responsiveness = 0.62f, stopThresholdKmh = 2.0f, maxAccuracyM = 30f),
    MOTORCYCLE(R.string.vehicle_motorcycle, gaugeMaxKmh = 200, defaultWarnKmh = 100, responsiveness = 0.78f, stopThresholdKmh = 2.5f, maxAccuracyM = 30f),
    BICYCLE(R.string.vehicle_bicycle, gaugeMaxKmh = 60, defaultWarnKmh = 40, responsiveness = 0.55f, stopThresholdKmh = 1.5f, maxAccuracyM = 25f),
    WALK(R.string.vehicle_walk, gaugeMaxKmh = 20, defaultWarnKmh = 10, responsiveness = 0.40f, stopThresholdKmh = 0.7f, maxAccuracyM = 20f);

    val stopThresholdMps: Float get() = stopThresholdKmh / 3.6f

    companion object {
        /** الافتراضي: الدراجة، وهي حالة الاستعمال الأولى للتطبيق */
        val DEFAULT = MOTORCYCLE
    }
}

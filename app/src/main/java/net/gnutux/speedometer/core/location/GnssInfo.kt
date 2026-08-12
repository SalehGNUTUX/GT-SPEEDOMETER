package net.gnutux.speedometer.core.location

/**
 * حالة تحديد الموقع كما تُعرض للمستخدم.
 *
 * [updateHz] معدّل الوصول المتحقّق فعلًا، لا المطلوب. عرضه ليس ترفًا: أغلب الهواتف
 * تسلّم عيّنة واحدة في الثانية مهما طلبنا أسرع، وأداة قياس تُخفي ذلك تكذب على مستعملها.
 */
data class GnssInfo(
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val accuracyM: Float = Float.NaN,
    val updateHz: Float = 0f,
    val hasFix: Boolean = false,
) {
    val quality: FixQuality
        get() = when {
            !hasFix -> FixQuality.NONE
            accuracyM.isNaN() || accuracyM > 20f -> FixQuality.POOR
            accuracyM > 8f -> FixQuality.FAIR
            else -> FixQuality.GOOD
        }
}

enum class FixQuality { NONE, POOR, FAIR, GOOD }

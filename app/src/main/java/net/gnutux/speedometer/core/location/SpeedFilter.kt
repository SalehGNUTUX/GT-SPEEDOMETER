package net.gnutux.speedometer.core.location

import net.gnutux.speedometer.core.profile.VehicleProfile
import kotlin.math.abs

/**
 * تنعيم أسّي للسرعة مع بوّابة دقّة.
 *
 * [VehicleProfile.responsiveness] هو وزن العيّنة الجديدة: 1 يعني تمريرًا كاملًا
 * بلا تنعيم. القفزة الكبيرة تمرّ كاملة تقريبًا لأن التسارع على الدراجة حقيقيّ
 * وسريع، وتنعيمه بشدّة يجعل العدّاد يتخلّف عن الواقع تخلّفًا يشعر به الراكب.
 * التنعيم إنما هو لتهدئة تذبذب المتر الواحد وقوفًا، لا لكبح التسارع.
 */
class SpeedFilter(private var profile: VehicleProfile = VehicleProfile.DEFAULT) {

    private var smoothedMps = 0f
    private var initialized = false

    fun setProfile(p: VehicleProfile) {
        profile = p
        reset()
    }

    fun reset() {
        smoothedMps = 0f
        initialized = false
    }

    /** هل العيّنة صالحة للاستعمال؟ */
    fun accepts(sample: SpeedSample): Boolean =
        sample.accuracyM > 0f && sample.accuracyM <= profile.maxAccuracyM

    fun update(rawMps: Float): Float {
        if (!initialized) {
            smoothedMps = rawMps
            initialized = true
            return smoothedMps
        }

        // فرق كبير = تسارع أو فرملة حقيقيّان، فمرّرهما شبه كاملين
        val jump = abs(rawMps - smoothedMps)
        val weight = when {
            jump > 4f -> 0.95f
            jump > 2f -> (profile.responsiveness + 0.95f) / 2f
            else -> profile.responsiveness
        }
        smoothedMps += (rawMps - smoothedMps) * weight

        // ما دون عتبة التوقّف اعتبرها صفرًا، وإلا رقص العدّاد على 2-3 كم/س وقوفًا
        if (smoothedMps < profile.stopThresholdMps) smoothedMps = 0f
        return smoothedMps
    }
}

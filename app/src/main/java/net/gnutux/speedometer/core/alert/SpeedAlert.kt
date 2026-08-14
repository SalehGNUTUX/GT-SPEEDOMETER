package net.gnutux.speedometer.core.alert

import android.media.AudioManager
import android.media.ToneGenerator
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.profile.VehicleProfile

/**
 * الحدّ الذي يضعه السائق لنفسه: اشتقاق مدى القرص وعتباته، ومنطق التنبيه الصوتيّ.
 *
 * الاشتقاق هنا في موضعٍ واحد لا في كلّ شاشة. أربع شاشاتٍ تحسب المدى والعتبة بنفسها
 * هي أربع فرصٍ لأن تختلف: عدّاد يقول «تجاوزت» وطبقة كاميرا تقول «لم تتجاوز» في
 * المشهد نفسه. [SpeedScale] هي الجواب الوحيد، وتستهلكه الشاشات كما هو.
 */

/** منطقة اللون على القوس. الألوان نفسها تختلف بين الشاشة والطبقة، أمّا الحكم فواحد */
enum class SpeedZone { NORMAL, WARN, DANGER }

/**
 * مدى القرص وعتباته لحالةٍ بعينها: مركبةٌ مختارة وحدٌّ اختاره السائق.
 *
 * @param gaugeMaxKmh أقصى ما يبلغه القوس
 * @param warnKmh عتبة اللون البرتقاليّ
 * @param limitKmh حدّ السائق، وصفرٌ يعني بلا حدّ — وعندئذٍ يعود كلّ شيء إلى
 *   [VehicleProfile] كما كان قبل 0.7.0، فلا يتبدّل سلوك من لم يضبط شيئًا.
 */
data class SpeedScale(
    val gaugeMaxKmh: Int,
    val warnKmh: Int,
    val limitKmh: Int,
) {

    /**
     * موضع علامة الحدّ على القوس كنسبةٍ من مسحه، وقيمةٌ سالبة تعني «لا علامة».
     *
     * نسبةٌ لا زاوية: الشاشة والراسم المحروق يبدآن من [HudMetrics.ARC_START] نفسه
     * ويمسحان [HudMetrics.ARC_SWEEP] نفسه، فالنسبة هي القدر المشترك بينهما.
     */
    val limitFraction: Float
        get() = if (limitKmh in 1..gaugeMaxKmh) limitKmh.toFloat() / gaugeMaxKmh else -1f

    fun zoneOf(speedKmh: Float): SpeedZone = zoneOf(speedKmh, gaugeMaxKmh, warnKmh, limitKmh)

    companion object {

        /**
         * **الدالّة الواحدة.** كلّ ما تعرضه الشاشات والطبقة المحروقة من مدًى وعتبةٍ
         * وحدٍّ يخرج من هنا.
         *
         * حين يضع السائق حدًّا نُعيد بناء القرص حوله:
         * - **المدى = الحدّ + ‎%20‎ مقرَّبًا إلى أعلى إلى مضاعف ‎10‎** (‎120 → 144 →
         *   150‎). القرص المصنعيّ ‎200‎ يترك سرعات المدينة في ثلث القوس الأوّل حيث لا
         *   تكاد تُقرأ؛ وبالمدى الجديد تشغل السرعاتُ المعتادة معظمه، ويبقى فوق الحدّ
         *   متّسعٌ يُظهر قدر التجاوز.
         * - **العتبة عند ‎%90‎ من الحدّ**، فيُنذر السائق قبل أن يتجاوز لا بعده.
         *
         * والحساب بأعداد صحيحة لا بـ `Float`: ‎50 × 1.2f‎ تساوي ‎60.000002‎ في الفاصلة
         * العائمة، و`ceil` عليها تقفز بالمدى إلى ‎70‎ بدل ‎60‎. `(limit×12 + 99) / 100`
         * هي `ceil(limit × 1.2 / 10)` بالضبط وبلا كسور.
         */
        fun of(profile: VehicleProfile, limitKmh: Int): SpeedScale {
            if (limitKmh <= 0) {
                return SpeedScale(profile.gaugeMaxKmh, profile.defaultWarnKmh, 0)
            }
            val max = ((limitKmh * 12 + 99) / 100) * 10
            val warn = (limitKmh * 9 / 10).coerceAtLeast(1)
            return SpeedScale(gaugeMaxKmh = max, warnKmh = warn, limitKmh = limitKmh)
        }

        /**
         * الحكم على السرعة بقيمٍ بدائيّة لا بكائن: الراسم المحروق ينادي هذه الدالّة
         * على خيط الرسم ستّين مرّةً في الثانية، وبناءُ [SpeedScale] هناك تخصيصٌ بلا
         * مقابل. والمنطق واحدٌ في الحالين لأنّ [zoneOf] العضويّة تُفوّض إليها.
         *
         * بحدٍّ مضبوط يصير الحكمُ حكمَ الحدّ وحده: دونه فيروزيّ وفوقه أحمرُ في الحال.
         * وعتبة [HudMetrics.DANGER_OF_MAX] النسبيّة تبقى لحالة «بلا حدّ» وحدها،
         * وإلّا لتأخّر الأحمر إلى ‎%110‎ من الحدّ لأنّ المدى صار الحدَّ زائدَ ‎%20‎.
         */
        fun zoneOf(speedKmh: Float, gaugeMaxKmh: Int, warnKmh: Int, limitKmh: Int): SpeedZone =
            when {
                limitKmh > 0 -> when {
                    speedKmh >= limitKmh -> SpeedZone.DANGER
                    speedKmh >= warnKmh -> SpeedZone.WARN
                    else -> SpeedZone.NORMAL
                }

                speedKmh >= gaugeMaxKmh * HudMetrics.DANGER_OF_MAX -> SpeedZone.DANGER
                speedKmh >= warnKmh -> SpeedZone.WARN
                else -> SpeedZone.NORMAL
            }
    }
}

/** ما يجب فعله بعد عيّنةٍ واحدة. [SpeedAlert] تقرّره، والمشغّل ينفّذه */
enum class AlertAction {
    /** لا صوت */
    SILENT,

    /** صفيرةٌ واحدة: لحظة عبور الحدّ */
    CROSSED,

    /** صفيرةٌ متكرّرة: التجاوز دام فوق [SpeedAlert.SUSTAINED_SECONDS] ثانية */
    SUSTAINED,
}

/**
 * حالة التنبيه عند تجاوز الحدّ.
 *
 * **دالّةٌ نقيّة وحالةٌ بدائيّة.** [onSample] لا تلمس صوتًا ولا ساعةً ولا تفضيلًا:
 * تأخذ السرعة والحدّ واللحظة وتُعيد ما يجب فعله. فيبقى المنطق — وهو أدقّ ما في
 * الميزة — مقروءًا ومُتحقَّقًا منه بالقراءة، ويبقى الصوت تفصيلًا في [SpeedAlertPlayer].
 *
 * **الزمن `elapsedRealtimeNanos` وحده** (القاعدة الأولى في المشروع): `currentTimeMillis`
 * تقفز مع مزامنة الشبكة وتغيّر المنطقة الزمنيّة، فقفزةٌ إلى الوراء كانت تُسكت
 * التنبيه دقائق، وقفزةٌ إلى الأمام تُطلقه فورًا.
 *
 * **حارس التذبذب.** العودة تحت الحدّ لا تُقبل إلّا بنزول [HYSTERESIS_KMH] تحته على
 * الأقلّ. سيّارةٌ تلازم الحدّ تتذبذب حوله بكسور الكيلومتر، فبلا هذا الحارس تُصفَّر
 * الحالة وتُعاد صفيرة العبور عشرات المرّات في الدقيقة — وهو أسوأ من غياب التنبيه.
 */
class SpeedAlert {

    private var isOver = false
    private var overSinceNanos = 0L
    private var lastBeepNanos = 0L

    /** تُنادى حين يسقط شرط التنبيه أصلًا: وقوفٌ، أو إطفاء الخيار، أو رفع الحدّ */
    fun reset() {
        isOver = false
        overSinceNanos = 0L
        lastBeepNanos = 0L
    }

    /**
     * @param speedKmh السرعة المنعَّمة كما يعرضها العدّاد — لا الخام: التنبيه يجب أن
     *   يوافق الرقم الذي يراه السائق، وقفزةُ عيّنةٍ واحدة لا تستحقّ صفيرة.
     * @param limitKmh حدّ السائق؛ صفرٌ أو أقلّ يعني «بلا حدّ» فتُصفَّر الحالة.
     * @param nowNanos `SystemClock.elapsedRealtimeNanos()` أو زمن العيّنة نفسه.
     */
    fun onSample(speedKmh: Float, limitKmh: Int, nowNanos: Long): AlertAction {
        if (limitKmh <= 0) {
            reset()
            return AlertAction.SILENT
        }

        if (speedKmh >= limitKmh) {
            if (!isOver) {
                // لحظة العبور: صفيرةٌ واحدة. و`lastBeepNanos` تُضبط هنا كي لا تتلوها
                // صفيرةُ التكرار الأولى قبل انقضاء دورتها كاملةً
                isOver = true
                overSinceNanos = nowNanos
                lastBeepNanos = nowNanos
                return AlertAction.CROSSED
            }
            if (nowNanos - overSinceNanos < SUSTAINED_NANOS) return AlertAction.SILENT
            if (nowNanos - lastBeepNanos < REPEAT_NANOS) return AlertAction.SILENT
            lastBeepNanos = nowNanos
            return AlertAction.SUSTAINED
        }

        // تحت الحدّ: لا صوت في الحالين، والفرق أنّ التصفير لا يقع إلّا بعد هبوطٍ
        // كافٍ. وفي المنطقة الرماديّة (بين `limit − 2` والحدّ) تبقى الحالة معلّقة:
        // لا صفير، فإن عاد فوق الحدّ استأنف تكراره بلا صفيرة عبورٍ جديدة
        if (isOver && speedKmh <= limitKmh - HYSTERESIS_KMH) reset()
        return AlertAction.SILENT
    }

    companion object {
        /** ما دام التجاوز هذه المدّة صار الصفير متكرّرًا */
        const val SUSTAINED_SECONDS = 10L

        /** دورة التكرار بعد ذلك */
        const val REPEAT_SECONDS = 4L

        /** أدنى هبوطٍ تحت الحدّ يُعتدّ به عودةً (كم/س) */
        const val HYSTERESIS_KMH = 2f

        private const val SUSTAINED_NANOS = SUSTAINED_SECONDS * 1_000_000_000L
        private const val REPEAT_NANOS = REPEAT_SECONDS * 1_000_000_000L
    }
}

/**
 * المشغّل: يترجم [AlertAction] إلى صوت، ولا يعرف شيئًا عن الحدّ ولا عن السرعة.
 *
 * `ToneGenerator` لا ملفّ مورد: نغمةٌ يولّدها النظام فلا تزيد حجم الحزمة ولا تحتاج
 * فكّ ترميز، وهي مسموعةٌ فوق ضجيج الطريق.
 *
 * **`STREAM_ALARM` لا `STREAM_MUSIC`:** التنبيه يخصّ سلامة السائق فلا يجوز أن يبتلعه
 * وضعُ الصامت ولا خفضُ صوت الوسائط أثناء الملاحة.
 *
 * **الإنشاء كسول والتحرير صريح:** `ToneGenerator` يمسك مسار صوتٍ في النظام، وتسريبه
 * معروفٌ ويُفقد على بعض الأجهزة بعد عشرات النسخ. فلا يُنشأ إلّا عند أوّل صفيرة،
 * ويُحرَّر في [release] — ويناديها المحرّك عند إطفاء الخيار وعند إيقاف مصدر الموقع.
 *
 * **كلّ نداءٍ ملفوفٌ بـ `runCatching`:** البانية ترمي `RuntimeException` عند نفاد
 * موارد الصوت (مكالمةٌ جارية، أو تطبيقٌ آخر أمسك المسارات)، و`startTone` قد ترمي
 * `IllegalStateException` على نسخةٍ ماتت من تحتنا. وسقوطُ التطبيق لأنّ صفيرةً تعذّرت
 * مقايضةٌ خاسرة.
 */
class SpeedAlertPlayer {

    @Volatile
    private var tone: ToneGenerator? = null

    fun play(action: AlertAction) {
        val type = when (action) {
            AlertAction.SILENT -> return
            // نغمتان متمايزتان: الأولى إخبارٌ بالعبور، والثانية إلحاحٌ لأنّ التجاوز دام
            AlertAction.CROSSED -> ToneGenerator.TONE_PROP_BEEP
            AlertAction.SUSTAINED -> ToneGenerator.TONE_PROP_BEEP2
        }
        val generator = tone ?: runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, VOLUME)
        }.getOrNull()?.also { tone = it } ?: return
        runCatching { generator.startTone(type, BEEP_MS) }
    }

    fun release() {
        val generator = tone ?: return
        tone = null
        runCatching { generator.release() }
    }

    private companion object {
        /** من ‎0‎ إلى ‎100‎. دون الأقصى كي لا تُفزع صفيرةٌ سائقًا سمّاعتُه في أذنه */
        const val VOLUME = 80

        /** طول الصفيرة: يُسمع فوق ضجيج الطريق ولا يطغى على تعليمات الملاحة */
        const val BEEP_MS = 220
    }
}

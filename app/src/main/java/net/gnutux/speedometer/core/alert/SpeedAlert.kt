package net.gnutux.speedometer.core.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import net.gnutux.speedometer.core.camera.HudMetrics
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AlertTone

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
 *
 * **الحكم على الرقم المعروض لا على الكسر.** كلّ المقارنات هنا تجري على
 * `speedKmh.toInt()`، وهو عين ما يطبعه [net.gnutux.speedometer.ui.Fmt.speed] على
 * القرص وفي الطبقة المحروقة. المقارنة على الكسر كانت — بحكم أنّ `Fmt.speed` تبتر
 * ولا تقرّب — توافق العبورَ صدفةً لا عقدًا: يكفي أن تُبدَّل `Fmt.speed` يومًا إلى
 * تقريبٍ حتّى يصير العدّاد يقول «‎50‎» عند ‎49.6‎ ولا صفير. الاعتماد على الرقم
 * المعروض يجعل العقد صريحًا فلا ينكسر بتبديلٍ في مكانٍ آخر.
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
     *   يوافق الرقم الذي يراه السائق، وقفزةُ عيّنةٍ واحدة لا تستحقّ صفيرة. والبتر
     *   إلى عددٍ صحيح يقع هنا لا عند المنادي، كي يبقى العقد في موضعٍ واحد.
     * @param limitKmh حدّ السائق؛ صفرٌ أو أقلّ يعني «بلا حدّ» فتُصفَّر الحالة.
     * @param nowNanos `SystemClock.elapsedRealtimeNanos()` أو زمن العيّنة نفسه.
     */
    fun onSample(speedKmh: Float, limitKmh: Int, nowNanos: Long): AlertAction {
        if (limitKmh <= 0) {
            reset()
            return AlertAction.SILENT
        }

        // الرقم المعروض. `toInt` على `NaN` تعطي صفرًا في آلة جافا الافتراضيّة، وهو
        // ما نريده بالضبط: عيّنةٌ بلا سرعةٍ معلومة لا تُصفّر ولا تُصفِّر
        val shownKmh = speedKmh.toInt()

        if (shownKmh >= limitKmh) {
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
        // لا صفير، فإن عاد فوق الحدّ استأنف تكراره بلا صفيرة عبورٍ جديدة.
        //
        // والمقارنة على المعروض تُوسّع بابَ العودة قليلًا وهذا مقصود: بالكسر كان
        // التصفير يوجب هبوطًا إلى ‎48.0‎ بالضبط فما دون، فمن نزل إلى ‎48.7‎ — والعدّاد
        // يقول له «‎48‎»، أي «عدتُ إلى ما دون الحدّ» — ثمّ عاد فتجاوز، لم تُصفَّر له
        // صفيرة عبورٍ ثانية لأنّ الحالة بقيت معلّقة من العبور الأوّل. وهذا وجهٌ من
        // وجوه «التنبيه أحيانًا لا يعمل»: عبورٌ ثانٍ حقيقيّ بلا صوت
        if (isOver && shownKmh <= limitKmh - HYSTERESIS_KMH) reset()
        return AlertAction.SILENT
    }

    companion object {
        /** ما دام التجاوز هذه المدّة صار الصفير متكرّرًا */
        const val SUSTAINED_SECONDS = 10L

        /** دورة التكرار بعد ذلك */
        const val REPEAT_SECONDS = 4L

        /**
         * أدنى هبوطٍ تحت الحدّ يُعتدّ به عودةً (كم/س).
         *
         * عددٌ صحيح لا `Float`: المقارنة كلّها على الرقم المعروض، ونصفُ كيلومترٍ
         * في حارس تذبذبٍ لا معنى له إذ لا يعرض العدّاد أنصافًا أصلًا.
         */
        const val HYSTERESIS_KMH = 2

        private const val SUSTAINED_NANOS = SUSTAINED_SECONDS * 1_000_000_000L
        private const val REPEAT_NANOS = REPEAT_SECONDS * 1_000_000_000L
    }
}


/**
 * المشغّل: يترجم [AlertAction] إلى صوت، ولا يعرف شيئًا عن الحدّ ولا عن السرعة.
 *
 * **ملفّاتُ `res/raw` لا `ToneGenerator`.** النغمة المولَّدة لا تُختار — نوعُها ثابتٌ
 * في الشيفرة — ولا تُضبط شدّتها إلّا في بانيتها، فتغييرُ الشدّة يوجب هدم النسخة
 * وبناءها. وهما بالضبط ما طلبه صاحب التطبيق: نغماتٌ يختار منها، وشدّةٌ يضبطها.
 * و[SoundPool] يعطي الاثنين: الملفّ مفكوكُ الترميز في الذاكرة، والشدّة وسيطٌ في كلّ
 * تشغيلة لا في البانية.
 *
 * **`USAGE_ALARM` لا `USAGE_MEDIA`:** التنبيه يخصّ سلامة السائق فلا يجوز أن يبتلعه
 * وضعُ الصامت ولا خفضُ صوت الوسائط أثناء الملاحة. وهذا يجعل مستوى «المنبّه» في
 * النظام سقفًا لا يتجاوزه التطبيق — ومن أنزله إلى الصفر لن يسمع شيئًا مهما ضبط
 * الشدّة عندنا، ولذلك تُعرض له `alert_stream_muted` من الإعدادات.
 * و`CONTENT_TYPE_SONIFICATION` وسمٌ صادق: هذه إشارةٌ وظيفيّة قصيرة لا موسيقى، وبه
 * تعرف السيّارةُ ونظامُ الصوت كيف يخفضان ما سواها بدل أن يخفضاها هي.
 *
 * **المورد يُحلّ بالاسم لا بـ `R.raw.x`.** `getIdentifier` بطيءٌ نسبيًّا (بحثٌ في
 * جدول الموارد) لكنّه يُنادى مرّةً لكلّ نغمةٍ في عمر العمليّة لا في كلّ صفيرة، وثمنُه
 * مقابلَ أن تصير إضافةُ نغمةٍ سادسة ملفًّا في `res/raw` وسطرًا في
 * [net.gnutux.speedometer.core.settings.AlertTone] ونصًّا — بلا مساسٍ بهذا الملفّ —
 * صفقةٌ رابحة. ولو غاب المورد عاد `getIdentifier` بصفر، وعندها يعمل المسار
 * الاحتياطيّ بدل أن يصمت التطبيق لخطأٍ في التحزيم.
 *
 * **التحميل كسولٌ لا مسبق.** الخيار مطفأٌ افتراضًا، وتحميلُ خمس عيّناتٍ عند إقلاع كلّ
 * جلسةٍ لميزةٍ قد لا تُفعَّل هدرٌ في الذاكرة وفي زمن الإقلاع (الملفّات ‎22‎ كيلوبايت
 * على القرص، لكنّها مفكوكةَ الترميز تقارب ‎200‎ كيلوبايت من `PCM`). ولا تُحمَّل إلّا
 * النغمة المختارة، وتُحمَّل من [prepare] الذي يناديه المحرّك مع كلّ عيّنةٍ والتنبيه
 * مفعَّل — أي **قبل** التجاوز بكثير، فيجد العبورُ العيّنةَ جاهزة.
 *
 * **`SoundPool.load` غير متزامنة**، وصفيرةٌ تُطلب قبل تمام فكّ الترميز صفيرةٌ ضائعة.
 * فلا نتجاهل الحالة: يُسجَّل الطلب في [pendingTone] ويُنفّذه [onLoaded] عند تمام
 * التحميل. تأخّرٌ بعشرات الملّي ثانية في أسوأ الأحوال، وهو لا يُدرَك في تنبيه قيادة،
 * أمّا سقوطُ أوّل صفيرة فيُدرَك ويُفسَّر «التنبيه لا يعمل».
 *
 * **`play` تعيد صفرًا عند الفشل، وهذا هو موضع العطب القديم.** المشغّل السابق كان
 * يهمل قيمة `startTone` المرتجعة ويحتفظ بنسخة `ToneGenerator` واحدة طولَ عمر
 * العمليّة؛ فإذا ماتت النسخة — تبدُّلُ مسار الصوت عند وصل بلوتوث أو فصله، أو تطبيقٌ
 * آخر أمسك المسارات، أو استرجاعُ النظام لمسارٍ خامل — صار كلّ نداءٍ بعدها فشلًا
 * صامتًا إلى الأبد. وهذا عين وصف الشكوى: «أحيانًا يعمل وكثيرًا لا». فهنا تُفحص
 * القيمة المرتجعة، وعند الصفر يُهدم المجمّع ويُبنى ويُعاد التحميل ويُعاد التشغيل
 * **مرّةً واحدة**. مرّةً لا حلقة: مجمّعٌ يفشل مرّتين متتاليتين عطبٌ في النظام لا
 * حالةٌ عابرة، والحلقة عليه تشغل الخيط الرئيس في سيّارةٍ سائرة.
 *
 * **المولّد الاحتياطيّ باقٍ.** إن تعذّر مسار [SoundPool] كلّه — مورد مفقود، أو فشل
 * فكّ الترميز، أو تعذّر بناء المجمّع — صفّرنا بـ `ToneGenerator` كما قبل 0.9.4.
 * صفيرةٌ لا يختارها المستعمل خيرٌ من صمتٍ تامّ سببه خطأ تحزيم.
 *
 * **كلّ نداءٍ ملفوفٌ بـ `runCatching`:** البانية ترمي `RuntimeException` عند نفاد
 * موارد الصوت (مكالمةٌ جارية، أو تطبيقٌ آخر أمسك المسارات)، و`startTone` قد ترمي
 * `IllegalStateException` على نسخةٍ ماتت من تحتنا. وسقوطُ التطبيق لأنّ صفيرةً تعذّرت
 * مقايضةٌ خاسرة.
 *
 * **خيطٌ واحد.** مداخل هذا الصنف كلّها تُنادى من الخيط الرئيس: المحرّك يجمع العيّنات
 * على `Dispatchers.Main.immediate`، والمعاينة تأتي من لمسةٍ في الإعدادات. ومستمعُ
 * [SoundPool.setOnLoadCompleteListener] يُسلَّم على خيط بانيه — وهو الرئيس نفسه.
 * فلا قفلَ هنا ولا `@Volatile`؛ ولو نودي يومًا من خيطٍ آخر لوجب مراجعة هذا السطر.
 */
class SpeedAlertPlayer(private val context: Context) {

    private var pool: SoundPool? = null

    /** النغمات التي طُلب تحميلها: النغمة ← معرّف العيّنة في المجمّع */
    private val soundIds = HashMap<AlertTone, Int>()

    /** ما تمّ فكّ ترميزه فعلًا. `soundIds` وحدها لا تكفي: المعرّف يُعطى فور الطلب */
    private val readyIds = HashSet<Int>()

    /** طلبٌ وصل والعيّنة تُفكّ بعدُ. واحدٌ لا طابور: الأحدث يُلغي ما قبله بطبعه */
    private var pendingTone: AlertTone? = null
    private var pendingGain = 0f
    private var pendingLoop = 0
    private var pendingRebuild = false

    private var fallback: ToneGenerator? = null

    /** الشدّة التي بُني بها المولّد الاحتياطيّ؛ تبدّلُها يوجب هدمه */
    private var fallbackVolume = -1

    /**
     * تهيئة النغمة المختارة قبل الحاجة إليها. يُنادى مع كلّ عيّنةٍ والتنبيه مفعَّل،
     * وهو لا يفعل شيئًا بعد المرّة الأولى — فالثمن نداءُ دالّةٍ ونظرةٌ في خريطة.
     *
     * تبديل المستعمل للنغمة يُحمّل الجديدة عند أوّل عيّنةٍ بعده وتبقى القديمة في
     * المجمّع: خمسُ عيّناتٍ في أسوأ الأحوال، وهدمُ القديمة لا يوفّر ما يستحقّ خطر
     * هدم عيّنةٍ ما زالت تُشغَّل.
     */
    fun prepare(tone: AlertTone) {
        val sp = ensurePool() ?: return
        soundIdOf(tone, sp)
    }

    /**
     * @param volumePercent شدّة التنبيه من الإعدادات (‎10‎..‎100‎)، وهي **نسبةٌ من مجرى
     *   المنبّه** لا مستوًى مطلقًا: مستوى النظام يبقى السقف.
     */
    fun play(action: AlertAction, tone: AlertTone, volumePercent: Int) {
        val loop = when (action) {
            AlertAction.SILENT -> return
            AlertAction.CROSSED -> 0
            AlertAction.SUSTAINED -> SUSTAINED_REPEATS
        }
        dispatch(tone, gainOf(volumePercent), loop, allowRebuild = true)
    }

    /**
     * معاينةٌ من شاشة الإعدادات: تشغيلةٌ واحدة كصفيرة العبور تمامًا.
     *
     * وعمدًا من [dispatch] نفسها لا من مسارٍ ثانٍ: معاينةٌ تعمل بينما التنبيه الحقيقيّ
     * معطوب أسوأ من غياب المعاينة، إذ تشهد زورًا أنّ الصوت سليم.
     */
    fun preview(tone: AlertTone, volumePercent: Int) {
        dispatch(tone, gainOf(volumePercent), loop = 0, allowRebuild = true)
    }

    /** يُحرّر كلّ شيء: يناديه المحرّك عند إطفاء الخيار وعند إيقاف مصدر الموقع */
    fun release() {
        discardPool()
        releaseFallback()
    }

    // ===== الداخل =====

    /**
     * المسار الوحيد للصوت. `allowRebuild` هي «هل بقيت لك محاولةٌ ثانية؟» — تُمرَّر
     * `false` بعد إعادة البناء فينتهي التسلسل عند المولّد الاحتياطيّ لا في حلقة.
     */
    private fun dispatch(tone: AlertTone, gain: Float, loop: Int, allowRebuild: Boolean) {
        val sp = ensurePool() ?: run { fallbackBeep(loop > 0, gain); return }
        val id = soundIdOf(tone, sp) ?: run { fallbackBeep(loop > 0, gain); return }
        if (id !in readyIds) {
            // ما زالت تُفكّ: يُعلَّق الطلب ويُنفّذه المستمع، فلا تضيع أوّل صفيرة
            pendingTone = tone
            pendingGain = gain
            pendingLoop = loop
            pendingRebuild = allowRebuild
            return
        }
        val stream = runCatching { sp.play(id, gain, gain, PRIORITY, loop, 1f) }.getOrDefault(0)
        if (stream != 0) return
        // صفرٌ = لم يُشغَّل شيء. مجمّعٌ ماتت مساراته من تحتنا (تبدّل مسار الصوت مثلًا)
        if (!allowRebuild) {
            fallbackBeep(loop > 0, gain)
            return
        }
        discardPool()
        dispatch(tone, gain, loop, allowRebuild = false)
    }

    private fun ensurePool(): SoundPool? {
        pool?.let { return it }
        val created = runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attrs).build()
        }.getOrNull() ?: return null
        created.setOnLoadCompleteListener { _, sampleId, status -> onLoaded(sampleId, status) }
        pool = created
        return created
    }

    /** معرّف العيّنة، ويُطلب تحميلها إن لم تُطلب. `null` = لا مورد ولا سبيل */
    private fun soundIdOf(tone: AlertTone, sp: SoundPool): Int? {
        soundIds[tone]?.let { return it }
        val resId = runCatching {
            context.resources.getIdentifier(tone.resName, "raw", context.packageName)
        }.getOrDefault(0)
        if (resId == 0) return null
        val id = runCatching { sp.load(context, resId, PRIORITY) }.getOrDefault(0)
        if (id == 0) return null
        soundIds[tone] = id
        return id
    }

    private fun onLoaded(sampleId: Int, status: Int) {
        if (status != LOAD_OK) {
            // فكّ الترميز فشل: مورد تالف أو حزمةٌ ناقصة. يُنسى المعرّف كي لا يُنتظر
            // إلى الأبد، ويُنجَّى الطلب المعلّق بالمولّد الاحتياطيّ
            soundIds.entries.removeAll { it.value == sampleId }
            if (pendingTone != null) {
                pendingTone = null
                fallbackBeep(pendingLoop > 0, pendingGain)
            }
            return
        }
        readyIds.add(sampleId)
        val tone = pendingTone ?: return
        // عيّنةٌ أخرى تمّت (تبديل نغمةٍ أثناء الانتظار مثلًا): الطلب ينتظر عيّنته هو
        if (soundIds[tone] != sampleId) return
        pendingTone = null
        dispatch(tone, pendingGain, pendingLoop, pendingRebuild)
    }

    private fun discardPool() {
        val sp = pool
        pool = null
        soundIds.clear()
        readyIds.clear()
        pendingTone = null
        if (sp != null) runCatching { sp.release() }
    }

    /**
     * الصفيرة المولَّدة: شبكة أمانٍ لا خيارًا. لا تعرف نغمة المستعمل ولا تستطيعها،
     * لكنّها تُبقي التنبيه مسموعًا حين يتعذّر كلّ ما سواه.
     *
     * وهنا أيضًا تُفحص القيمة المرتجعة: `startTone` تعيد `false` على نسخةٍ ماتت،
     * فتُحرَّر وتُبنى وتُعاد المحاولة مرّةً — العطب نفسه الذي كان يُسكت التطبيق.
     */
    private fun fallbackBeep(insistent: Boolean, gain: Float) {
        val percent = (gain * 100f).toInt().coerceIn(1, 100)
        val type = if (insistent) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
        if (startFallbackTone(type, percent)) return
        releaseFallback()
        startFallbackTone(type, percent)
    }

    private fun startFallbackTone(type: Int, percent: Int): Boolean {
        // شدّة `ToneGenerator` تُثبَّت في بانيتها ولا تُضبط بعدها، فتبدّلُها هدمٌ وبناء
        if (fallbackVolume != percent) releaseFallback()
        val generator = fallback ?: runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, percent)
        }.getOrNull()?.also {
            fallback = it
            fallbackVolume = percent
        } ?: return false
        return runCatching { generator.startTone(type, BEEP_MS) }.getOrDefault(false)
    }

    private fun releaseFallback() {
        val generator = fallback ?: return
        fallback = null
        fallbackVolume = -1
        runCatching { generator.release() }
    }

    private fun gainOf(volumePercent: Int): Float = volumePercent.coerceIn(0, 100) / 100f

    private companion object {
        /**
         * مساران يكفيان: صفيرةٌ واحدة في المرّة، والثاني متّسعٌ كي لا تقطع صفيرةُ
         * تكرارٍ ذيلَ سابقتها على جهازٍ بطيء. وزيادتهما تحجز مسارات صوتٍ بلا عمل.
         */
        const val MAX_STREAMS = 2

        /** أولويّةٌ واحدة لكلّ ما نشغّله: لا تزاحم عندنا أصلًا */
        const val PRIORITY = 1

        /** `SoundPool` تُبلّغ عن نجاح التحميل بصفر لا بواحد */
        const val LOAD_OK = 0

        /**
         * الإلحاح تكرارٌ لا تسريع.
         *
         * `loop = 1` تعني تشغيلتين لا واحدة (العدد هو التكرار بعد الأولى). وقد كان
         * البديل رفعَ `rate` قليلًا، ورُفض: رفعُ المعدّل يرفع الطبقة ويقصّر المدّة،
         * فيسمع المستعمل نغمةً غير التي اختارها — و«الجرس» عند ‎1.2×‎ ليس جرسًا. أمّا
         * التكرار فيبقي هويّة النغمة ويقرأه السائق فورًا إلحاحًا، ويعمل على النغمات
         * الخمس جميعًا بلا استثناء.
         */
        const val SUSTAINED_REPEATS = 1

        /** طول صفيرة الاحتياط: تُسمع فوق ضجيج الطريق ولا تطغى على تعليمات الملاحة */
        const val BEEP_MS = 220
    }
}

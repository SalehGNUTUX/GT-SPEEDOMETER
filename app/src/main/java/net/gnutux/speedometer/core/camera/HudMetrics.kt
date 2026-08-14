package net.gnutux.speedometer.core.camera

/**
 * نِسَب طبقة العدّاد — عقدٌ واحد بين راسمَيها.
 *
 * الطبقة تُرسم مرّتين: مرّةً بـ Compose على الشاشة (`ui/screens/CameraScreen`)
 * ومرّةً على قماشٍ داخل الملفّ ([VideoOverlayPainter]). وحين كانت الشاشة تحمل
 * مقاساتٍ مطلقة (‎124dp‎ و‎16dp‎ و‎16sp‎) والراسمُ نسبًا من الضلع الأقصر، تطابق
 * الرسمان على هاتفٍ واحدٍ عرضه ‎411dp‎ وتباعدا على ما سواه: القرص يشغل ‎%34.4‎ من
 * هاتفٍ عرضه ‎360dp‎ و‎%25.8‎ من لوحٍ عرضه ‎480dp‎، بينما يشغل ‎%30‎ في الملفّ أبدًا.
 * فالمقاس المطلق ليس تصميمًا واحدًا بل تصاميمُ بعدد الأجهزة.
 *
 * لذلك صار كلّ مقياسٍ هنا **نسبةً من الضلع الأقصر** للسطح المرسوم عليه: الشاشة
 * تأخذ ضلعها من قيود مركّبها (`BoxWithConstraints`)، والراسم من `min(w, h)` للإطار
 * الخارج. عددٌ واحد يُضرب في ضلعين مختلفين فيخرج التصميم نفسه في الموضعين.
 *
 * ## من أين جاءت النسب
 * من تصميم **الشاشة** عند العرض المرجعيّ [REFERENCE]، لأنّه التصميم الذي أقرّه
 * المستعمل بعينه. ولذلك تُكتب كقسمةٍ صريحة على [REFERENCE] لا كأعدادٍ عشريّة
 * مجرّدة: يبقى أصل كلّ رقمٍ ظاهرًا فلا يُنسى بعد شهر من أين جاء ‎0.0389‎.
 *
 * ## لماذا لا أنواع Android هنا
 * `Float` صرف بلا `Dp` ولا `Color` ولا `Paint`: الشاشة تحوّل إلى `Dp`/`TextUnit`
 * والراسم إلى بكسل. أيّ نوعٍ من طرفٍ واحد كان سيجرّ الطرف الآخر إلى تحويلٍ زائد،
 * وهو بابُ الاختلاف الذي نغلقه.
 */
object HudMetrics {

    /** عرض الهاتف المرجعيّ الذي أُقرّ عليه التصميم بصريًّا (بالـ dp) */
    const val REFERENCE = 411f

    // ===== نسبٌ من الضلع الأقصر =====

    /** هامش الطبقة عن حافّة السطح: ‎16dp‎ على المرجع */
    const val MARGIN = 16f / REFERENCE

    /**
     * أدنى فجوةٍ مضمونة بين كتلة القرص وكتلة الإحصاءات.
     * على الشاشة الصفُّ `SpaceBetween` فالفجوة الفعليّة أوسع، وهذه حدُّها الأدنى
     * الذي يقيّد به الطرفان أقصى عرضٍ للوحة الإحصاءات فلا تتراكب الكتلتان أبدًا.
     */
    const val BLOCK_GAP = 12f / REFERENCE

    /** القطر **الخارجيّ** للقرص (يشمل عرض القوس): ‎124dp‎ على المرجع */
    const val RING_DIAMETER = 124f / REFERENCE

    /** سماكة القوس نسبةً إلى قطره لا إلى الضلع: تعادل ‎0.20‎ من نصف القطر الأوسط */
    const val RING_STROKE_OF_DIAMETER = 0.09f

    /** نصف قطر زوايا الألواح (المواصفة: ‎16dp‎) */
    const val PANEL_CORNER = 16f / REFERENCE

    /** حشو لوح الإحصاءات: ‎14dp‎ أفقيًّا و‎12dp‎ رأسيًّا على المرجع */
    const val PANEL_PAD_H = 14f / REFERENCE
    const val PANEL_PAD_V = 12f / REFERENCE

    /** الفراغ بين سطور الإحصاءات (‎6dp‎) */
    const val STAT_LINE_GAP = 6f / REFERENCE

    /** الفراغ بين التسمية وقيمتها داخل السطر (‎6dp‎) */
    const val STAT_GAP = 6f / REFERENCE

    /**
     * ارتفاع صندوق السطر الواحد: ‎24dp‎ لنصٍّ ‎16dp‎.
     * وهو `lineHeight` الذي تعطيه Material 3 لـ `titleMedium`، والنصّ موسَّطٌ فيه.
     * كُتب هنا صراحةً كي يتقدّم الراسم المحروق بالمقدار نفسه: لو تقدّم بارتفاع
     * المحارف وحده (‎1.17×‎ الحجم) لخرجت كتلته أقصر من كتلة الشاشة بنحو ‎%15‎.
     */
    const val STAT_LINE_BOX = 24f / REFERENCE

    /** تسمية الإحصاءة: أصغر من قيمتها وأخفت — وهذا هو التراتب الذي كان الحرق يُسقطه */
    const val STAT_LABEL_TEXT = 12f / REFERENCE
    const val STAT_VALUE_TEXT = 16f / REFERENCE

    /** رقم القرص ووحدته: ‎44dp‎ و‎12dp‎ على المرجع */
    const val GAUGE_VALUE_TEXT = 44f / REFERENCE
    const val GAUGE_UNIT_TEXT = 12f / REFERENCE

    /**
     * الخلوص بين صندوق الرقم وصندوق الوحدة داخل القرص.
     * مأخوذٌ من الشاشة: صندوقا Material 3 (‎52dp‎ لرقمٍ ‎44dp‎ و‎16dp‎ لوحدةٍ ‎12dp‎)
     * يتركان بين محارفهما نحو ‎1.2dp‎. أُثبت هنا رقمًا صريحًا كي لا يتبدّل المظهر
     * بتبدّل جدول الطباعة، وكي يقدر الراسم على مطابقته.
     */
    const val GAUGE_STACK_GAP = 1.2f / REFERENCE

    // ===== نسبٌ لا تتبع الضلع =====

    /**
     * ارتفاع صندوق محارف الخطّ نسبةً إلى حجمه (`descent − ascent`).
     * الراسم يقيسه من الخطّ نفسه، والشاشة تحتاج رقمًا مسبقًا لتثبيت `lineHeight`
     * فلا يتمدّد بمقياس خطّ النظام. وهو قياس Roboto، وأيّ فرقٍ يسير عنه لا يزيح
     * شيئًا لأنّ الطرفين يوسّطان الصندوق لا يعلّقانه من أعلاه.
     */
    const val FONT_BOX = 1.171f

    /** أقصى عرضٍ لرقم القرص نسبةً إلى نصف القطر، قبل تصغيره كي لا يلامس القوس */
    const val VALUE_MAX_WIDTH_OF_RADIUS = 1.40f

    /** قاع تصغير كتلة الإحصاءات في النسب الشاذّة قبل القصّ */
    const val STATS_MIN_SCALE = 0.60f

    /**
     * نسبة الضلع الأقصر إلى الأطول في **المنطقة الآمنة** للطبقة المحروقة: ‎9:20‎.
     *
     * الملفّ يُسجَّل ‎16:9‎ (‎0.5625‎) وشاشات الهواتف اليوم ‎20:9‎ (‎0.45‎) وأنحف،
     * ومشغّلات الفيديو تملأ الشاشة افتراضًا فتقتطع من كلّ جانبٍ
     * `(0.5625 − 0.45)/2 / 0.5625 = %10` من العرض. فما رُسم في تلك العشرة لا يبلغ
     * عين المشاهد وإن كان في الملفّ. و‎9:20‎ حدٌّ لا مبالغةَ فيه: هو نسبة أطول
     * الشاشات الشائعة، وما هو أنحف منها (‎21:9‎) نادرٌ في الهواتف.
     *
     * ولا تُطبَّق هذه النسبة على طبقة الشاشة: تلك تُرسم داخل حدود المركّب نفسه فلا
     * اقتطاع عليها، وحصرُها كان سيبعد عناصرها عن الحافّة بلا سبب.
     */
    const val SAFE_SHORT_OF_LONG = 9f / 20f

    /** ظلّ النصّ الواقع على الصورة مباشرةً، نسبةً إلى حجم حرفه */
    const val SHADOW_BLUR_OF_TEXT = 10f / 44f
    const val SHADOW_DY_OF_TEXT = 2f / 44f

    // ===== الألوان والشفافيّات والعتبات =====

    /** المواصفة: ألواحٌ سوداء بشفافيّة ‎0.50‎، ومسار القوس مثلها */
    const val PANEL_ALPHA = 0.50f
    const val TRACK_ALPHA = 0.50f

    /** خفوت تسمية الإحصاءة: هي دلالةٌ لا قيمة، فتتراجع خطوةً خلف رقمها */
    const val LABEL_ALPHA = 0.72f

    /** المواصفة: القوس يبدأ ‎150°‎ ويمسح ‎240°‎ — يفتح من أسفل فلا يزاحم الرقم */
    const val ARC_START = 150f
    const val ARC_SWEEP = 240f

    /** عتبة الخطر: نسبةٌ من أقصى القرص. وعتبة التحذير `warnKmh` تأتي مع اللقطة */
    const val DANGER_OF_MAX = 0.92f

    /**
     * الثلاثيّ الداكن حرفيًّا (`DarkPalette`). الطبقة لا تتبع سمة التطبيق: هي تقع
     * على صورةٍ فوتوغرافيّة لا على خلفيّة التطبيق، والملفّ المحروق يستعمل هذا
     * الثلاثيّ أبدًا — فلو تبدّلت ألوان الشاشة بالسمة لكذبت الشاشةُ على الملفّ.
     */
    const val COLOR_ACCENT = 0xFF00E5C7.toInt()
    const val COLOR_WARN = 0xFFFFB020.toInt()
    const val COLOR_DANGER = 0xFFFF5A45.toInt()

    /** المقاسات المطلقة لضلعٍ أقصر بعينه، محسوبةً مرّةً واحدة */
    fun of(shortSide: Float): HudSizes = HudSizes(shortSide)
}

/**
 * نسب [HudMetrics] مضروبةً في ضلعٍ أقصر بعينه.
 *
 * الوحدة هي وحدة [shortSide] نفسها: `dp` حين تأتي من قيود المركّب، وبكسل حين تأتي
 * من `min(w, h)` للإطار. الصنف لا يعرف أيّهما، وهذا هو المقصود.
 *
 * القيم محسوبةٌ في البناء لا في قارئاتٍ (`get()`): الراسم يقرؤها ستّين مرّةً في
 * الثانية، ويحتفظ بالكائن ما دام الضلع ثابتًا — وهو ثابتٌ طوال جلسة التسجيل.
 */
class HudSizes(val shortSide: Float) {
    val margin: Float = HudMetrics.MARGIN * shortSide
    val blockGap: Float = HudMetrics.BLOCK_GAP * shortSide

    val ringDiameter: Float = HudMetrics.RING_DIAMETER * shortSide
    val ringStroke: Float = ringDiameter * HudMetrics.RING_STROKE_OF_DIAMETER

    val panelCorner: Float = HudMetrics.PANEL_CORNER * shortSide
    val panelPadH: Float = HudMetrics.PANEL_PAD_H * shortSide
    val panelPadV: Float = HudMetrics.PANEL_PAD_V * shortSide

    val lineGap: Float = HudMetrics.STAT_LINE_GAP * shortSide
    val statGap: Float = HudMetrics.STAT_GAP * shortSide
    val statLineBox: Float = HudMetrics.STAT_LINE_BOX * shortSide
    val statLabelText: Float = HudMetrics.STAT_LABEL_TEXT * shortSide
    val statValueText: Float = HudMetrics.STAT_VALUE_TEXT * shortSide

    val gaugeValueText: Float = HudMetrics.GAUGE_VALUE_TEXT * shortSide
    val gaugeUnitText: Float = HudMetrics.GAUGE_UNIT_TEXT * shortSide
    val gaugeStackGap: Float = HudMetrics.GAUGE_STACK_GAP * shortSide

    /** صناديق السطور المشتقّة من [HudMetrics.FONT_BOX] — تحتاجها الشاشة لتثبيت `lineHeight` */
    val statLabelBox: Float = statLabelText * HudMetrics.FONT_BOX
    val statValueBox: Float = statValueText * HudMetrics.FONT_BOX
    val gaugeValueBox: Float = gaugeValueText * HudMetrics.FONT_BOX
    val gaugeUnitBox: Float = gaugeUnitText * HudMetrics.FONT_BOX
}

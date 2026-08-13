package net.gnutux.speedometer.core.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.camera.effects.Frame
import net.gnutux.speedometer.ui.Fmt
import java.util.Locale


/**
 * لقطة ساكنة من حالة العدّاد تُسلَّم إلى راسم الطبقة المحروقة.
 *
 * الرسم يجري على خيط معالجة الإطارات (GL)، لا على خيط الواجهة، فلا يجوز أن يقرأ
 * الراسم `TripState` أو `StateFlow` مباشرةً: قراءةٌ نصفَ محدَّثة تُنتج إطارًا
 * متناقضًا. لذا نمرّر قيمًا بدائيّة غير قابلة للتغيّر تُستبدَل ذرّيًّا مرّةً واحدة
 * لكلّ تحديث موقع، ويقرؤها الراسم كما هي.
 *
 * `gaugeMaxKmh` و`warnKmh` جزءٌ من اللقطة لأنّهما يتبعان مركبةَ المستخدم، وقد
 * يتغيّران أثناء التسجيل.
 */
data class HudSnapshot(
    val speedKmh: Float = 0f,
    val distanceKm: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val durationMs: Long = 0L,
    val gaugeMaxKmh: Int = 200,
    val warnKmh: Int = 100,
)

/**
 * راسم الطبقة المحروقة داخل الفيديو.
 *
 * لا يستعمل Compose ولا موارد النصوص: يُنادى من `OverlayEffect` على خيط الرسم
 * لكلّ إطار، فلا مجال هناك لتركيبٍ ولا لقراءة `Context`. لذلك الألوان والعبارات
 * مكتوبة هنا حرفيًّا، والأدوات (Paint وMatrix والمستطيلات) تُنشأ مرّةً واحدة
 * وتُعاد تدويرها كي لا نخصّص ذاكرةً ستّين مرّة في الثانية.
 *
 * ## الوحدة المرجعيّة
 * `unit` هي واحد بالمئة من **الضلع الأقصر** للإطار الخارج، لا من ارتفاعه.
 * الكتلتان — صندوق الإحصاءات يسارًا وحلقة السرعة يمينًا — مرصوصتان أفقيًّا على
 * القاعدة نفسها، فاشتقاق المقاس من الارتفاع وحده كان يُنتج في الوضع الرأسيّ حلقةً
 * بعرض ‎47%‎ من الإطار وصندوقًا بعرض ‎78%‎ فيتراكبان، بينما يبدو الأمر سليمًا في
 * الوضع الأفقيّ بالشفرة ذاتها. مع `min(w, h)` يخرج التخطيط بالنسب نفسها في
 * الوضعين وعند أيّ دقّة.
 *
 * النسب أدناه مأخوذة من طبقة الشاشة (`CameraScreen`) عند عرضٍ نموذجيّ ‎411dp‎:
 * شارة السرعة ‎124dp ≈ 30%‎، ورقمها ‎44sp ≈ 10.7%‎، ونصّ الإحصاءات ‎16sp ≈ 3.9%‎.
 * فالمحروق والمعروض على الشاشة يخرجان بالإحساس نفسه.
 */
class VideoOverlayPainter {

    /**
     * تُكتب من خيط الموقع وتُقرأ من خيط الرسم، فلزم `@Volatile`. الاستبدال ذرّيّ
     * لأنّ `HudSnapshot` غير قابلة للتغيير.
     */
    @Volatile
    var snapshot: HudSnapshot = HudSnapshot()

    /**
     * أداة لكلّ دور. النسخة السابقة كانت تتشارك Paint واحدة وتقلب `textAlign`
     * داخل `drawGauge` ثمّ تعيدها يدويًّا، فصار صحّة الرسم رهينةَ ترتيب النداءات.
     * هنا لا يعدّل دورٌ خاصّيّةً يعتمد عليها دورٌ آخر، وما يتغيّر بحسب الإطار
     * (حجم الخطّ، عرض القوس، اللون) يُضبط عند كلّ استعمال.
     */
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = PANEL_ALPHA
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        alpha = TRACK_ALPHA
    }

    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * `TextPaint` لا `Paint` كي يقبله `TextUtils.ellipsize` في مسار القصّ الاحتياطيّ.
     * المحاذاة تبقى `LEFT` أبدًا: نضع الأسطر بأنفسنا انطلاقًا من عرضها المقيس،
     * فلا نعتمد على محاذاةٍ قد يقلبها دورٌ آخر.
     */
    private val statsText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = ARABIC
    }

    private val gaugeValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = Locale.US
    }

    private val gaugeUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = ARABIC
    }

    private val statsFm = Paint.FontMetrics()
    private val valueFm = Paint.FontMetrics()
    private val unitFm = Paint.FontMetrics()

    private val panelBox = RectF()
    private val arcBox = RectF()

    private val matrix = Matrix()
    private val corners = FloatArray(8)

    /** آخر لقطة نُسّقت نصوصها. اللقطة تتبدّل مع كلّ موقع (~1Hz) لا مع كلّ إطار */
    private var formattedFor: HudSnapshot? = null
    private val statsLines = arrayOf("", "", "")

    /** رقم القرص يُخبَّأ مع الأسطر: `String.format` ستّين مرّة في الثانية تخصيصٌ بلا داعٍ */
    private var gaugeText = "0"

    /**
     * @return true دائمًا: نطلب من `OverlayEffect` إخراج الإطار حتّى لو لم نرسم
     *   شيئًا، وإلّا سقط الإطار من التسجيل.
     */
    fun onDraw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val crop = frame.cropRect
        val rotation = frame.rotationDegrees
        val swapped = rotation == 90 || rotation == 270
        val outW = (if (swapped) crop.height() else crop.width()).toFloat()
        val outH = (if (swapped) crop.width() else crop.height()).toFloat()
        if (outW <= 0f || outH <= 0f) return true

        canvas.setMatrix(
            outputToBuffer(rotation, outW, outH, crop.left.toFloat(), crop.top.toFloat())
        )
        drawHud(canvas, outW, outH)
        return true
    }

    /**
     * تحويل من إحداثيّات الإطار الخارج (بعد الدوران والقصّ) إلى إحداثيّات المخزن
     * الذي يرسم فيه `OverlayEffect`. ندوّر بعكس دوران المستشعر ثمّ نزيح الناتج
     * حتّى يقع ركن الصندوق المحيط الأصغر على ركن القصّ.
     *
     * بديلُه `Frame.sensorToBufferTransform` مقلوبًا، وهو أعمّ نظريًّا (يستوعب
     * الانعكاس الأفقيّ للكاميرا الأماميّة). لم نُبدّل: الكاميرا مثبَّتة على العدسة
     * الخلفيّة في `CameraSession`، فهذا الحساب اليدويّ كافٍ ومُختبَر، والصحّة
     * أولى من الأناقة. إن أُضيفت الكاميرا الأماميّة يومًا فهنا موضع التبديل.
     */
    private fun outputToBuffer(
        rotationDegrees: Int,
        outW: Float,
        outH: Float,
        cropLeft: Float,
        cropTop: Float,
    ): Matrix {
        matrix.reset()
        matrix.setRotate(-rotationDegrees.toFloat())

        corners[0] = 0f
        corners[1] = 0f
        corners[2] = outW
        corners[3] = 0f
        corners[4] = outW
        corners[5] = outH
        corners[6] = 0f
        corners[7] = outH
        matrix.mapPoints(corners)

        var minX = corners[0]
        var minY = corners[1]
        for (i in 2 until corners.size step 2) {
            if (corners[i] < minX) minX = corners[i]
            if (corners[i + 1] < minY) minY = corners[i + 1]
        }
        matrix.postTranslate(cropLeft - minX, cropTop - minY)
        return matrix
    }

    /**
     * تخطيط الكتلتين. نحسب الحلقة أوّلًا لأنّ مقاسها ثابت لا يتبع طول النصّ، ثمّ
     * نمنح صندوق الإحصاءات ما تبقّى من العرض.
     *
     * **اللامتراكب (الثابتة المضمونة):** أقصى عرضٍ يُسمح به للصندوق هو
     * `statsMaxWidth = w − 2·margin − blockGap − ringDiameter`، وفي `drawStats`
     * لا تتجاوز اللوحة هذا العرض أبدًا (تصغير ثمّ قصّ). إذن
     * `يمينُ الصندوق ≤ margin + statsMaxWidth = يسارُ الحلقة − blockGap`،
     * فبينهما فجوةٌ موجبة دائمًا. وهذا العرض موجب في الوضعين: ما تحجزه الهوامش
     * والفجوة والحلقة هو `(2×5 + 3 + 26.4)·unit = 0.394·min(w, h) ≤ 0.394·w`،
     * فيبقى للصندوق ‎0.606·w‎ على أسوأ تقدير (الوضع الرأسيّ).
     */
    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val s = snapshot
        val unit = minOf(w, h) / 100f
        val margin = MARGIN_UNITS * unit
        val radius = RING_RADIUS_UNITS * unit
        // نصف القطر الظاهر يشمل نصف عرض القوس، وإلّا خرج الحدّ الخارجيّ عن الهامش
        val ringOuter = radius * (1f + RING_THICKNESS_RATIO / 2f)

        val bottom = h - margin
        val cx = w - margin - ringOuter
        val cy = bottom - ringOuter
        val statsMaxWidth = (cx - ringOuter) - BLOCK_GAP_UNITS * unit - margin

        // التنسيق مرّةً هنا لا داخل كلّ كتلة: القرص يقرأ `gaugeValue` المخبَّأ، فلا
        // يبقى صحيحًا بالصدفة لأنّ الإحصاءات تُرسم قبله
        formatStats(s)
        drawStats(canvas, margin, bottom, statsMaxWidth, unit, s)
        drawGauge(canvas, cx, cy, radius, s)
    }

    /**
     * كتلة الإحصاءات في الركن السفليّ الأيسر: ثلاثة أسطر فوق لوحةٍ داكنة.
     *
     * الحشو مشتقّ من حجم الخطّ لا من الإطار، فعرض اللوحة يتناسب خطّيًّا مع حجم
     * النصّ. لذلك يكفي معامل تصغير واحد لإدخال الكتلة كلّها في العرض المتاح.
     */
    private fun drawStats(
        canvas: Canvas,
        left: Float,
        bottom: Float,
        maxWidth: Float,
        unit: Float,
        s: HudSnapshot,
    ) {
        val lines = formatStats(s)

        val baseSize = STATS_TEXT_UNITS * unit
        statsText.textSize = baseSize
        val natural = widestLine(lines) + 2f * STATS_PAD_H_RATIO * baseSize
        // القاع عند 0.6 كي لا يصغر النصّ إلى ما لا يُقرأ؛ ما دونه نقصّ الأسطر
        val scale = (maxWidth / natural).coerceIn(STATS_MIN_SCALE, 1f)

        val size = baseSize * scale
        statsText.textSize = size
        val padH = STATS_PAD_H_RATIO * size
        val padV = STATS_PAD_V_RATIO * size
        val lineGap = STATS_LINE_GAP_RATIO * size

        // إعادة قياس بعد تثبيت الحجم: عرض المحارف لا يتناسب خطّيًّا تناسبًا تامًّا
        // بسبب التلميح (hinting)، والثابتة أعلاه لا تحتمل تقريبًا
        val panelWidth = minOf(widestLine(lines) + 2f * padH, maxWidth)
        val innerWidth = (panelWidth - 2f * padH).coerceAtLeast(0f)

        // ارتفاع السطر من مقاييس الخطّ الحقيقيّة: `descent − ascent` ≈ 1.17 من حجم
        // الخطّ، والنسخة السابقة كانت تعدّه مساويًا للحجم فتخرج الكتلة أقصر ممّا
        // تشغله فعلًا، ولم ينكشف الخلل إلّا لأنّ الفراغ بين الأسطر كان سخيًّا
        statsText.getFontMetrics(statsFm)
        val lineHeight = statsFm.descent - statsFm.ascent
        val contentHeight = lines.size * lineHeight + (lines.size - 1) * lineGap
        val top = bottom - contentHeight - 2f * padV

        panelBox.set(left, top, left + panelWidth, bottom)
        val corner = STATS_CORNER_RATIO * size
        canvas.drawRoundRect(panelBox, corner, corner, panel)

        // التماثل الرأسيّ بالبناء: أوّل خطّ أساس عند `top + padV − ascent`، وآخر
        // نزولٍ ينتهي عند `bottom − padV` بالضبط
        var baseline = top + padV - statsFm.ascent
        val right = left + panelWidth
        for (line in lines) {
            val shown = fitToWidth(line, innerWidth)
            val width = statsText.measureText(shown, 0, shown.length)
            // الأسطر عربيّة، فمحاذاتها إلى يمين اللوحة هي المحاذاة الطبيعيّة لها
            canvas.drawTextRun(shown, 0, shown.length, 0, shown.length, right - padH - width, baseline, true, statsText)
            baseline += lineHeight + lineGap
        }
    }

    /** قوس السرعة في الركن السفليّ الأيمن، وفي وسطه الرقم ووحدته */
    private fun drawGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float, s: HudSnapshot) {
        val thickness = RING_THICKNESS_RATIO * radius
        arcBox.set(cx - radius, cy - radius, cx + radius, cy + radius)

        track.strokeWidth = thickness
        canvas.drawArc(arcBox, START_ANGLE, SWEEP_ANGLE, false, track)

        val fraction = (s.speedKmh / s.gaugeMaxKmh).coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            progress.strokeWidth = thickness
            progress.color = when {
                s.speedKmh >= s.gaugeMaxKmh * 0.92f -> COLOR_DANGER
                s.speedKmh >= s.warnKmh.toFloat() -> COLOR_WARN
                else -> COLOR_ACCENT
            }
            canvas.drawArc(arcBox, START_ANGLE, fraction * SWEEP_ANGLE, false, progress)
        }

        val value = gaugeText
        gaugeValue.textSize = VALUE_TEXT_RATIO * radius
        // ثلاثة أرقام عند السرعات العالية أعرض من وترِ الدائرة الداخليّة عند ارتفاع
        // رؤوس الأرقام، فنصغّرها بقدرٍ محسوب بدل أن نتركها تلامس القوس
        val naturalWidth = gaugeValue.measureText(value)
        val maxWidth = VALUE_MAX_WIDTH_RATIO * radius
        if (naturalWidth > maxWidth) gaugeValue.textSize *= maxWidth / naturalWidth
        gaugeUnit.textSize = UNIT_TEXT_RATIO * radius

        applyShadow(gaugeValue)
        applyShadow(gaugeUnit)

        gaugeValue.getFontMetrics(valueFm)
        gaugeUnit.getFontMetrics(unitFm)

        // الكتلة (رقم + فجوة + وحدة) تُوسَّط على مركز الحلقة بمقاييس الخطّ لا
        // بمعاملات تخمينيّة. الخلوص بين الاثنين = `descent(الرقم) + |ascent(الوحدة)|`
        // زائد الفجوة، فهو موجبٌ بالبناء؛ الحساب القديم (`0.5 × نصف القطر`) كان
        // ينقص عن هذا المطلوب بنحو ‎0.016‎ من نصف القطر فتتلامس الحروف بالنزول
        val valueHeight = valueFm.descent - valueFm.ascent
        val unitHeight = unitFm.descent - unitFm.ascent
        val innerGap = RING_INNER_GAP_RATIO * gaugeUnit.textSize
        val stackTop = cy - (valueHeight + innerGap + unitHeight) / 2f

        val valueBaseline = stackTop - valueFm.ascent
        val unitBaseline = valueBaseline + valueFm.descent + innerGap - unitFm.ascent

        val valueWidth = gaugeValue.measureText(value)
        canvas.drawTextRun(value, 0, value.length, 0, value.length, cx - valueWidth / 2f, valueBaseline, false, gaugeValue)

        val unitWidth = gaugeUnit.measureText(UNIT_KMH)
        canvas.drawTextRun(UNIT_KMH, 0, UNIT_KMH.length, 0, UNIT_KMH.length, cx - unitWidth / 2f, unitBaseline, true, gaugeUnit)
    }

    /**
     * ظلّ ناعم بديلًا عن النسخة السوداء المزاحة التي كانت تبدو لطخة: كان الإزاحة
     * مشتقّة من ارتفاع الإطار لا من حجم الحرف، وبلا أيّ تنعيم.
     *
     * اخترنا `setShadowLayer` لا `BlurMaskFilter`: قماش `OverlayEffect` مقفول من
     * `Surface` (معجَّل بالعتاد)، وظلال النصّ هي مسار الظلّ الوحيد المدعوم هناك،
     * بينما مرشّحات القناع قد تُتجاهَل بصمت. النصف القطر والإزاحة من حجم الحرف
     * فيثبت المظهر عند أيّ دقّة، وبنسبة ‎10/44‎ و‎2/44‎ نفسِها التي تستعملها
     * `SpeedBadge` على الشاشة.
     */
    private fun applyShadow(paint: Paint) {
        paint.setShadowLayer(
            SHADOW_BLUR_RATIO * paint.textSize,
            0f,
            SHADOW_DY_RATIO * paint.textSize,
            Color.BLACK,
        )
    }

    private fun widestLine(lines: Array<String>): Float {
        var widest = 0f
        for (line in lines) widest = maxOf(widest, statsText.measureText(line, 0, line.length))
        return widest
    }

    /**
     * مسار احتياطيّ لا يُسلك إلّا عند نسبِ أبعادٍ شاذّة: بعد بلوغ قاع التصغير قد
     * يبقى السطر أعرض من اللوحة، فالقصّ بثلاث نقاط أهون من الخروج فوق الحلقة.
     */
    private fun fitToWidth(line: String, available: Float): CharSequence =
        if (statsText.measureText(line, 0, line.length) <= available) {
            line
        } else {
            TextUtils.ellipsize(line, statsText, available, TextUtils.TruncateAt.END)
        }

    /** التنسيق يتبع اللقطة لا الإطار، واللقطة تتبدّل مع كلّ تحديث موقع لا ستّين مرّة في الثانية */
    private fun formatStats(s: HudSnapshot): Array<String> {
        if (s != formattedFor) {
            statsLines[0] = "$LABEL_DISTANCE  ${Fmt.distance(s.distanceKm)} $UNIT_KM"
            statsLines[1] = "$LABEL_MAX_SPEED  ${Fmt.speed(s.maxSpeedKmh)} $UNIT_KMH"
            statsLines[2] = "$LABEL_DURATION  ${Fmt.duration(s.durationMs)}"
            gaugeText = Fmt.speed(s.speedKmh)
            formattedFor = s
        }
        return statsLines
    }

    private companion object {
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f

        /** نفس ألوان `ui/theme/Theme.kt`، مكرّرة هنا لأنّ الرسم خارج Compose */
        const val COLOR_ACCENT = 0xFF00E5C7.toInt()
        const val COLOR_WARN = 0xFFFFB020.toInt()
        const val COLOR_DANGER = 0xFFFF5A45.toInt()

        const val PANEL_ALPHA = 120
        const val TRACK_ALPHA = 130

        /** كلّها بوحدات `unit` أو بنسبةٍ من نصف القطر / حجم الخطّ، لا بالبكسل */
        const val MARGIN_UNITS = 5f
        const val BLOCK_GAP_UNITS = 3f
        const val RING_RADIUS_UNITS = 12f
        const val STATS_TEXT_UNITS = 4.2f

        const val RING_THICKNESS_RATIO = 0.20f
        const val VALUE_TEXT_RATIO = 0.86f
        const val VALUE_MAX_WIDTH_RATIO = 1.40f
        const val UNIT_TEXT_RATIO = 0.26f
        const val RING_INNER_GAP_RATIO = 0.22f

        const val STATS_PAD_H_RATIO = 0.70f
        const val STATS_PAD_V_RATIO = 0.55f
        const val STATS_LINE_GAP_RATIO = 0.36f
        const val STATS_CORNER_RATIO = 0.50f
        const val STATS_MIN_SCALE = 0.60f

        const val SHADOW_BLUR_RATIO = 10f / 44f
        const val SHADOW_DY_RATIO = 2f / 44f

        /** نفس نصوص `res/values/strings.xml`، مكرّرة لأنّ الراسم بلا `Context` */
        const val LABEL_DISTANCE = "المسافة"
        const val LABEL_MAX_SPEED = "أقصى سرعة"
        const val LABEL_DURATION = "المدة"
        const val UNIT_KM = "كم"
        const val UNIT_KMH = "كم/س"

        /**
         * لغة النصّ العربيّ صراحةً، ومعها `drawTextRun` باتّجاه RTL: `drawText`
         * يفترض فقرةً يسارًا-يمينًا افتراضًا، فتقع الأرقام داخل الجملة العربيّة
         * حيث تُلقيها خوارزميّة الاتّجاه ثنائيّ الجهة لا حيث نريد.
         */
        val ARABIC: Locale = Locale.forLanguageTag("ar")
    }
}

package net.gnutux.speedometer.core.camera

import android.graphics.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import androidx.camera.effects.Frame
import net.gnutux.speedometer.core.alert.SpeedScale
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.settings.GaugeStyle
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.components.GaugePalette
import net.gnutux.speedometer.ui.components.drawGaugeFace
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin


/**
 * لقطة ساكنة من حالة العدّاد تُسلَّم إلى راسم الطبقة المحروقة.
 *
 * الرسم يجري على خيط معالجة الإطارات (GL)، لا على خيط الواجهة، فلا يجوز أن يقرأ
 * الراسم `TripState` أو `StateFlow` مباشرةً: قراءةٌ نصفَ محدَّثة تُنتج إطارًا
 * متناقضًا. لذا نمرّر قيمًا بدائيّة غير قابلة للتغيّر تُستبدَل ذرّيًّا مرّةً واحدة
 * لكلّ تحديث موقع، ويقرؤها الراسم كما هي.
 *
 * `gaugeMaxKmh` و`warnKmh` و`limitKmh` جزءٌ من اللقطة لأنّها تتبع مركبةَ المستخدم
 * وحدَّه المضبوط، وقد تتغيّر أثناء التسجيل. ومصدرها جميعًا
 * [net.gnutux.speedometer.core.alert.SpeedScale] لا حسابٌ ثانٍ هنا.
 */
data class HudSnapshot(
    val speedKmh: Float = 0f,
    val distanceKm: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    /** متوسّط الرحلة الجارية كما يحسبه المسجّل؛ لا محورَ زمنٍ جديدًا هنا */
    val avgSpeedKmh: Float = 0f,
    val durationMs: Long = 0L,
    val gaugeMaxKmh: Int = 200,
    val warnKmh: Int = 100,
    /** حدّ السائق؛ صفرٌ يعني بلا حدّ فلا علامةَ حمراء ولا حكمَ لها على اللون */
    val limitKmh: Int = 0,
    /**
     * التصميم المختار من الإعدادات — يُحرَق كما يُرسم على الشاشة.
     *
     * في اللقطة لا في حقلٍ مستقلٍّ على الراسم: هو من «ما يُرسم» لا من «كيف يُهيَّأ
     * الراسم»، ولقطةٌ واحدةٌ لا تتغيّر تعني أنّ الإطار الواحد لا يُرسم نصفُه بتصميمٍ
     * ونصفُه بآخر إن بدّل المستعمل الاختيار وهو يسجّل.
     */
    val gaugeStyle: GaugeStyle = GaugeStyle.CLASSIC,
)

/**
 * راسم الطبقة المحروقة داخل الفيديو.
 *
 * لا يستعمل Compose ولا موارد النصوص: يُنادى من `OverlayEffect` على خيط الرسم
 * لكلّ إطار، فلا مجال هناك لتركيبٍ ولا لقراءة `Context`. لذلك العبارات مكتوبة هنا
 * حرفيًّا، والأدوات (Paint وMatrix والمستطيلات) تُنشأ مرّةً واحدة وتُعاد تدويرها
 * كي لا نخصّص ذاكرةً ستّين مرّة في الثانية.
 *
 * ## الوحدة المرجعيّة
 * كلّ مقياسٍ نسبةٌ من **الضلع الأقصر** للإطار الخارج، لا من ارتفاعه. الكتلتان —
 * حلقة السرعة يسارًا وصندوق الإحصاءات يمينًا — مرصوصتان أفقيًّا على القاعدة نفسها،
 * فاشتقاق المقاس من الارتفاع وحده كان يُنتج في الوضع الرأسيّ حلقةً بعرض ‎%47‎ من
 * الإطار وصندوقًا بعرض ‎%78‎ فيتراكبان، بينما يبدو الأمر سليمًا في الوضع الأفقيّ
 * بالشفرة ذاتها. مع `min(w, h)` يخرج التخطيط بالنسب نفسها في الوضعين وعند أيّ دقّة.
 *
 * والنسب نفسها لم تعد مكتوبةً هنا: مصدرها [HudMetrics]، وهو العقد الذي تقرأ منه
 * طبقة الشاشة أيضًا. كانت الشاشة تحمل مقاساتٍ مطلقة والراسم نسبًا مستقلّة، فتوافقا
 * على هاتفٍ واحد وتباعدا على غيره — وهذا الملفّ لم يعد يملك رأيًا خاصًّا في المقاس.
 *
 * ## جهة الكتلتين
 * التطبيق عربيّ الاتّجاه، وصفّ الشاشة في RTL يضع أوّل أبنائه — كتلة الإحصاءات —
 * يمينًا والحلقة يسارًا. النسخة السابقة حرقت العكس، فخرج الملفّ مخالفًا لما رآه
 * المصوِّر على شاشته وهو يسجّل. المرجع الموحَّد (HUD-SPEC) يوجب تطابق المحروق
 * والمعروض: **الإحصاءات يمينًا والقرص يسارًا**.
 */
class VideoOverlayPainter {

    /**
     * تُكتب من خيط الموقع وتُقرأ من خيط الرسم، فلزم `@Volatile`. الاستبدال ذرّيّ
     * لأنّ `HudSnapshot` غير قابلة للتغيير.
     */
    @Volatile
    var snapshot: HudSnapshot = HudSnapshot()

    // ————— جسر رسم وجه العدّاد —————
    //
    // تُنشأ مرّةً وتُعاد تدويرها كبقيّة أدوات هذا الملفّ: خيط الرسم يُنادى لكلّ إطار،
    // وتخصيصٌ في كلّ نداءٍ يُغذّي كنّاسَ المهملات فيقطّع البثّ.

    /** يشغّل أوامر `DrawScope` على قماشٍ أصليّ. بلا حالةٍ بين النداءات، فتكفي نسخة */
    private val drawScope = CanvasDrawScope()

    /**
     * كثافةٌ ‎1‎: مقاييس الوجه كلُّها نِسبٌ من الضلع لا وحدات `dp`، والإطار يُقاس
     * بالبكسل. فالكثافة الحقيقيّة للجهاز لا معنى لها هنا، وتمريرها كان يضاعف
     * السماكات على الشاشات عالية الكثافة.
     */
    private val unitDensity = Density(1f)

    /**
     * ألوان الوجه المحروق — ثوابت [HudMetrics] نفسها التي كانت تُرسم بها الأقواس،
     * لا ألوان السمة: الملفّ يُشاهَد خارج التطبيق، فلا سمةَ فاتحةً ولا داكنة له.
     */
    private val burnPalette = GaugePalette(
        active = ComposeColor(HudMetrics.COLOR_ACCENT),
        // المسار أسودُ شفيف كما كان `track` بالضبط: الطبقة تقع على صورةٍ فوتوغرافيّة،
        // فمسارٌ صلبٌ يحجب المشهد ومسارٌ فاتحٌ يذوب في السماء
        track = ComposeColor.Black.copy(alpha = HudMetrics.TRACK_ALPHA),
        redZone = ComposeColor(HudMetrics.COLOR_DANGER),
        tick = ComposeColor.White.copy(alpha = HudMetrics.TRACK_ALPHA),
        tickLine = ComposeColor.White.copy(alpha = HudMetrics.TRACK_ALPHA),
        limit = ComposeColor(HudMetrics.COLOR_DANGER),
    )

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
     * علامة حدّ السائق. لونها ثابتٌ فلا يُضبط عند كلّ إطار، وطرفها مستوٍ
     * (`BUTT`) لا مستدير: هي خطٌّ حادّ يقول «هنا الحدّ» لا امتدادٌ للقوس.
     */
    private val limitMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = HudMetrics.COLOR_DANGER
    }

    /**
     * `TextPaint` لا `Paint` لأنّ `StaticLayout` يشترطه.
     * المحاذاة تبقى `LEFT` أبدًا: `Layout` يحسب مواضع الأسطر بنفسه ثمّ يرسمها
     * مفترضًا مِنصَبًا يساريًّا، فأيّ قيمةٍ أخرى هنا تزيح النصّ عن لوحته.
     * المحاذاة البصريّة إلى اليمين تأتي من اتّجاه الفقرة لا من هذه الخاصّيّة.
     *
     * وزنُها **متوسّط** لا عريض: هذا وزن التسمية على الشاشة (`labelMedium`)، أمّا
     * القيمة فتُعرَّض بمقطعٍ (`StyleSpan`) داخل السطر. النسخة السابقة عرّضت السطر
     * كلّه فذابت التسمية في قيمتها.
     */
    private val statsText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = weighted(WEIGHT_MEDIUM)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = ARABIC
    }

    /**
     * رقم القرص بأثقل وزنٍ يبلغه الخطّ: الشاشة ترسمه بـ `FontWeight.Black` (‎900‎)،
     * و`Typeface.BOLD` وحده (‎700‎) كان يُخرجه في الملفّ أنحلَ ممّا رآه المصوِّر.
     */
    private val gaugeValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = weighted(WEIGHT_BLACK)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = Locale.US
    }

    private val gaugeUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = weighted(WEIGHT_MEDIUM)
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textLocale = ARABIC
    }

    private val valueFm = Paint.FontMetrics()
    private val unitFm = Paint.FontMetrics()

    private val panelBox = RectF()
    private val arcBox = RectF()

    private val matrix = Matrix()
    private val corners = FloatArray(8)

    /**
     * مقاسات الإطار الحاليّ. تُشتقّ عند تبدّل الضلع الأقصر لا في كلّ إطار: الضلع
     * ثابتٌ طوال جلسة التسجيل الواحدة، فلا داعي لتخصيص كائنٍ ستّين مرّة في الثانية.
     */
    private var sizes: HudSizes = HudMetrics.of(0f)

    /** آخر لقطة نُسّقت نصوصها. اللقطة تتبدّل مع كلّ موقع (~1Hz) لا مع كلّ إطار */
    private var formattedFor: HudSnapshot? = null

    /** قيم الأسطر منسَّقةً، بلا تسمياتها: التسميات ثابتة في [LABELS] */
    private val statValues = Array(LINES) { "" }

    /** رقم القرص يُخبَّأ مع الأسطر: `String.format` ستّين مرّة في الثانية تخصيصٌ بلا داعٍ */
    private var gaugeText = "0"

    /** الأسطر بمقاطعها المنسَّقة (تسميةٌ خافتة صغيرة + قيمةٌ عريضة) */
    private val spanned = arrayOfNulls<SpannableString>(LINES)

    /**
     * تخطيطات الأسطر، مخبَّأةً كما تُخبَّأ نصوصها ولسببٍ أقوى: `StaticLayout`
     * يخصّص ذاكرةً عند كلّ بناء (مصفوفات الأسطر ومخزن المحارف المشكَّلة)، و`onDraw`
     * يعمل على خيط GL بمعدّل الإطارات.
     *
     * **مفتاح التخبئة هو نصّ كلّ سطرٍ على حدة** لا اللقطة كلّها. كانت التخبئة على
     * `HudSnapshot`، فلمّا صارت المدّة تتقدّم كلّ ثانية أُعيد بناء التخطيطات
     * جميعًا كلّ ثانية — وسائر الأسطر لم تتبدّل. الآن يُعاد بناء السطر
     * المتبدّل وحده، ولا تُعاد جميعًا إلّا إن تبدّلت الهندسة (حجم الخطّ أو عرض
     * القصّ) وهي تابعةٌ لأعرض سطرٍ ولمقاس الإطار.
     *
     * ومقاس الإطار في المفتاح لأنّ حجم الخطّ وعرض القصّ مشتقّان منه، فتخبئةٌ على
     * النصّ وحده كانت ستُبقي تخطيطًا مبنيًّا لمقاسٍ سابق لو تبدّلت الدقّة أو انقلب
     * الضلع الأقصر بالدوران من غير أن يتبدّل النصّ.
     */
    private var statsLayouts: Array<StaticLayout>? = null
    private val layoutValues = arrayOfNulls<String>(LINES)
    private var layoutShortSide = 0f
    private var layoutMaxWidth = 0f
    private var layoutWidthPx = 0

    /** هندسة الكتلة المحسوبة وقت البناء، تُقرأ في كلّ إطارٍ بلا إعادة قياس */
    private var layoutTextSize = 0f
    private var layoutGlyphBox = 0f
    private var layoutLineBox = 0f
    private var layoutLineGap = 0f
    private var layoutPadH = 0f
    private var layoutPadV = 0f
    private var layoutCorner = 0f
    private var layoutPanelWidth = 0f
    private var layoutContentHeight = 0f

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
     * **الحلقة عند الحافّة اليسرى والإحصاءات عند اليمنى**، لأنّ الشاشة في RTL
     * تضعهما هكذا، والمحروق يجب أن يطابقها.
     *
     * **اللامتراكب (الثابتة المضمونة):** لتكن `L = insetX + margin` حافّةَ المنطقة
     * الآمنة اليسرى بعد هامشها و`R = w − insetX − margin` حافّتَها اليمنى، فعرضها
     * `R − L = safeShort − 2·margin` في الوضع الرأسيّ. الحلقة مثبَّتة عند `L` فحدّها
     * الأيمن `L + ringDiameter` (قيمةٌ ثابتة لا تتبع النصّ)، والإحصاءات مثبَّتة عند
     * `R` وإنّما تنمو يسارًا. أقصى عرضٍ يُسمح به للوحة هو
     * `statsMaxWidth = R − (L + ringDiameter) − blockGap`،
     * وفي [drawStats] لا تتجاوز اللوحة هذا العرض أبدًا (تصغير ثمّ قصّ). إذن
     * `يسارُ اللوحة = R − panelWidth ≥ L + ringDiameter + blockGap = يمينُ الحلقة +
     * blockGap`، فبينهما فجوةٌ موجبة لا تقلّ عن `blockGap`. وهو موجبٌ في الوضعين:
     * ما تحجزه الهوامش والفجوة والحلقة `(2×16 + 12 + 124)/411 · safeShort =
     * 0.4088·safeShort`، فيبقى للصندوق ‎0.591·safeShort‎. والرقم يتبع نسب
     * [HudMetrics]، وكان ‎0.394‎ قبل توحيدها مع الشاشة — فبقاؤه في التعليق بعد
     * تغيّر النسب كان يجعل البرهان يشير إلى شفرةٍ لم تعد موجودة.
     */
    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val s = snapshot

        // **المنطقة الآمنة.** الملفّ يُسجَّل ‎16:9‎ بينما شاشة الهاتف اليوم ‎20:9‎ أو
        // أنحف، ومشغّلات الفيديو تملأ الشاشة افتراضًا فتقتطع من الضلع الأقصر نحو
        // ‎%10‎ من كلّ جانب. وكانت الطبقة تُرسم على الإطار كلّه فيبتلع هذا الاقتطاعُ
        // حافّتيها: ظهرت «المسافة» ‏«سافة» وابتُلع يسارُ القرص — والملفّ سليمٌ في
        // ذاته، العطب في العرض لا في الرسم. فنحصر الطبقة داخل مستطيلٍ موسَّطٍ نسبةُ
        // ضلعه الأقصر إلى الأطول [HudMetrics.SAFE_SHORT_OF_LONG] كي تنجو من
        // الاقتطاع، ولا تكلّف ذلك — إن عُرض الملفّ كاملًا — غير إزاحةٍ يسيرة عن
        // الحافّة.
        val frameShort = minOf(w, h)
        val frameLong = maxOf(w, h)
        val safeShort = minOf(frameShort, frameLong * HudMetrics.SAFE_SHORT_OF_LONG)
        val inset = (frameShort - safeShort) / 2f
        // الاقتطاع يقع على الضلع الأقصر وحده، وهو العرض في الوضع الرأسيّ والارتفاع
        // في الأفقيّ — فالإزاحة تنتقل بين المحورين بحسب وضع الإطار
        val insetX = if (w <= h) inset else 0f
        val insetY = if (w <= h) 0f else inset

        // المقاسات تتبع الضلع الأقصر **للمنطقة الآمنة** لا للإطار: التصميم أُقرّ
        // على ما يراه المشاهد، لا على ما يحمله الملفّ ولا يبلغ عينه.
        val m = sizesFor(safeShort)

        // الطبقة مثبَّتة على هامش القاع مباشرةً، بخلاف الشاشة التي ترفعها فوق صفّ
        // الأزرار. **فرقٌ متعمَّد**: الأزرار لا تُحرق، فحجزُ ارتفاعها في الملفّ
        // يترك شريطًا فارغًا أسفل الصورة بلا سببٍ يراه المشاهد. لا يُضاف هنا
        // إزاحةُ صفّ أزرارٍ وهميّ.
        val bottom = h - insetY - m.margin
        val cx = insetX + m.margin + m.ringDiameter / 2f
        val cy = bottom - m.ringDiameter / 2f
        val statsRight = w - insetX - m.margin
        val statsMaxWidth = statsRight - (insetX + m.margin + m.ringDiameter) - m.blockGap

        // التنسيق مرّةً هنا لا داخل كلّ كتلة: القرص يقرأ `gaugeText` المخبَّأ، فلا
        // يبقى صحيحًا بالصدفة لأنّ الإحصاءات تُرسم قبله
        formatStats(s)
        drawStats(canvas, statsRight, bottom, statsMaxWidth, m, s)
        drawGauge(canvas, cx, cy, m, s)
    }

    /** المقاسات المشتقّة، مخبَّأةً ما دام الضلع الأقصر على حاله */
    private fun sizesFor(shortSide: Float): HudSizes {
        val cached = sizes
        if (cached.shortSide == shortSide) return cached
        val fresh = HudMetrics.of(shortSide)
        sizes = fresh
        return fresh
    }

    /**
     * كتلة الإحصاءات في الركن السفليّ **الأيمن**: أسطر [LINES] فوق لوحةٍ داكنة.
     *
     * `right` هو الحدّ الأيمن الثابت للوحة (‎w − margin‎)، واللوحة تنمو يسارًا
     * بمقدار عرضها المقيس.
     *
     * الرسم هنا لا يقيس ولا يبني شيئًا: كلّ ما يلزم محسوبٌ في [ensureStatsLayouts]
     * ومخبَّأ حتّى يتبدّل نصُّ سطرٍ أو مقاسُ الإطار.
     */
    private fun drawStats(
        canvas: Canvas,
        right: Float,
        bottom: Float,
        maxWidth: Float,
        m: HudSizes,
        s: HudSnapshot,
    ) {
        val layouts = ensureStatsLayouts(s, m, maxWidth)

        // `Layout` يحتفظ بمرجع الـ Paint لا بنسخةٍ منه، فيرسم بحجم الخطّ الحاليّ لا
        // بالذي بُني عليه. الحارس يعيد الحجم إن عبث به أحدٌ بين البناء والرسم،
        // وهو مقارنةٌ لا تكلّف شيئًا حين لا يتغيّر شيء
        if (statsText.textSize != layoutTextSize) statsText.textSize = layoutTextSize

        val top = bottom - layoutContentHeight - 2f * layoutPadV
        panelBox.set(right - layoutPanelWidth, top, right, bottom)
        canvas.drawRoundRect(panelBox, layoutCorner, layoutCorner, panel)

        // التماثل الرأسيّ بالبناء: الكتلة تتقدّم بصندوق السطر (`STAT_LINE_BOX`) لا
        // بارتفاع المحارف، لأنّ الشاشة تعطي كلّ سطرٍ صندوقًا بهذا الارتفاع وتوسّط
        // نصّه فيه. ولذلك نوسّط صندوق المحارف هنا كذلك: بغيره تخرج الكتلة أقصر
        // بنحو ‎%15‎ من كتلة الشاشة وتعلو الأسطر عن مواضعها.
        // أفقيًّا: عرض التخطيط هو العرض الداخليّ ومحاذاته ALIGN_NORMAL في فقرةٍ
        // RTL أي إلى يمينه، فالسطر يلتصق بالحافّة الداخليّة اليمنى للوحة
        val innerLeft = panelBox.left + layoutPadH
        val glyphOffset = (layoutLineBox - layoutGlyphBox) / 2f
        var y = top + layoutPadV
        for (layout in layouts) {
            val save = canvas.save()
            canvas.translate(innerLeft, y + glyphOffset)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            y += layoutLineBox + layoutLineGap
        }
    }

    /**
     * يبني تخطيطات الأسطر ويخبّئها مع هندستها، ولا يعيد بناء سطرٍ إلّا إن
     * تبدّل نصّه أو تبدّلت الهندسة المشتركة.
     *
     * **لماذا `StaticLayout` لا `drawTextRun`:** كلّ سطرٍ هنا مختلط الاتّجاه —
     * تسميةٌ عربيّة (RTL) يليها رقمٌ لاتينيّ (مقطع LTR) تليه وحدةٌ عربيّة. النسخة
     * السابقة كانت ترسم السطر كلّه بنداءٍ واحد `drawTextRun(isRtl = true)`، وهذا
     * يفرض على المقطع الرقميّ اتّجاه ما حوله فيخرج معكوسًا: ظهرت ‎00:00:03‎ في
     * الملفّ المسجَّل ‎30:00:00‎، و‎0.00‎ ظهرت ‎00.0‎. ترتيب المقاطع المختلطة عملُ
     * خوارزميّة الاتّجاه ثنائيّ الجهة (UBA)، و`drawTextRun` لا يشغّلها: هو يرسم
     * مقطعًا واحدًا باتّجاهٍ نُمليه نحن.
     *
     * `StaticLayout` يشغّل الخوارزميّة كاملةً — إعادة ترتيب المقاطع **وتشكيل**
     * الحروف العربيّة ووصلها — فيضع التسمية يمينًا والرقم يساره مقروءًا من اليسار
     * إلى اليمين كما هو. البديل اليدويّ (تقطيع كلّ سطر إلى مقاطعه العربيّة
     * والرقميّة وقياس كلٍّ منها ورصفها) شفرةٌ أطول وأوجهُ خطئها أكثر، ويعيد كتابة
     * ما في المنصّة أصلًا.
     *
     * `TextDirectionHeuristics.RTL` لا `FIRSTSTRONG_RTL`: اتّجاه الفقرة عندنا معلوم
     * مسبقًا (التطبيق عربيّ)، فتثبيته يمنع انقلاب المحاذاة لو بدأ سطرٌ يومًا بمحرفٍ
     * قويّ لاتينيّ. والخوارزميّة تبقى هي التي ترتّب المقاطع داخل الفقرة.
     *
     * `StaticLayout.Builder` متاح من API 23 و`minSdk` هنا 26، فلا مسار احتياطيّ.
     */
    private fun ensureStatsLayouts(s: HudSnapshot, m: HudSizes, maxWidth: Float): Array<StaticLayout> {
        val values = formatStats(s)
        val cached = statsLayouts
        var contentStale = false
        for (i in 0 until LINES) if (values[i] != layoutValues[i]) contentStale = true
        val frameStale = cached == null || m.shortSide != layoutShortSide || maxWidth != layoutMaxWidth
        if (cached != null && !contentStale && !frameStale) return cached

        statsText.textSize = m.statValueText

        // فجوة التسمية عن قيمتها مقاسٌ من التصميم (‎6dp‎ على المرجع) لا عرضَ مسافة:
        // نمطّ مسافةً واحدة بـ `ScaleXSpan` حتّى تبلغها. والمعامل مستقلّ عن حجم
        // الخطّ لأنّ عرض المسافة يتناسب معه كما تتناسب الفجوة، فيبقى صحيحًا بعد
        // أيّ تصغير — ولذلك يُحسب مرّةً مع بناء المقطع لا مع كلّ قياس.
        val space = statsText.measureText(" ")
        val gapScale = if (space > 0f) m.statGap / space else 1f
        for (i in 0 until LINES) {
            if (spanned[i] == null || values[i] != layoutValues[i]) {
                spanned[i] = buildSpanned(LABELS[i], values[i], gapScale)
            }
        }

        val naturalWidth = widestLine() + 2f * m.panelPadH
        // القاع عند 0.6 كي لا يصغر النصّ إلى ما لا يُقرأ؛ ما دونه نقصّ الأسطر
        val scale = (maxWidth / naturalWidth).coerceIn(HudMetrics.STATS_MIN_SCALE, 1f)

        val size = m.statValueText * scale
        statsText.textSize = size
        // الحشو ونصف القطر يتبعان معامل التصغير كما يتبعه النصّ: تصغير النصّ وحده
        // كان يترك لوحةً منتفخة حول كتلةٍ ضامرة
        val padH = m.panelPadH * scale
        val padV = m.panelPadV * scale
        val lineGap = m.lineGap * scale
        val lineBox = m.statLineBox * scale
        val corner = m.panelCorner * scale

        // إعادة قياس بعد تثبيت الحجم: عرض المحارف لا يتناسب خطّيًّا تناسبًا تامًّا
        // بسبب التلميح (hinting)، والثابتة أعلاه لا تحتمل تقريبًا.
        // العرض الداخليّ هو الأصغر بين أعرض سطرٍ وما تسمح به اللوحة، فيبقى
        // `panelWidth = innerWidth + 2·padH ≤ maxWidth` وهو شرط اللامتراكب
        val innerWidth = minOf(widestLine(), maxWidth - 2f * padH).coerceAtLeast(0f)

        // التقريب لأعلى لا لأسفل: عرض التخطيط عددٌ صحيح، والتقريب لأسفل قد يُنقص
        // كسرَ بكسلٍ عن أعرض سطرٍ فيقصّه بثلاث نقاط بلا داعٍ. الفائض (< 1px) يقع
        // داخل الحشو الجانبيّ لا خارج اللوحة، و`layoutPanelWidth` يبقى على الكسر
        // الحقيقيّ فلا يختلّ شرط `panelWidth ≤ maxWidth`
        val layoutWidth = ceil(innerWidth).toInt()

        // الهندسة المشتركة (حجم الخطّ وعرض القصّ) تلزم الأسطر كلّها معًا؛ ما
        // دامت على حالها فالسطر المتبدّل وحده يُبنى من جديد
        val geometryChanged = cached == null || size != layoutTextSize || layoutWidth != layoutWidthPx
        val built = if (geometryChanged) {
            Array(LINES) { i -> buildLine(spanned[i]!!, layoutWidth) }
        } else {
            val kept = cached!!
            for (i in 0 until LINES) {
                if (values[i] != layoutValues[i]) kept[i] = buildLine(spanned[i]!!, layoutWidth)
            }
            kept
        }

        statsLayouts = built
        for (i in 0 until LINES) layoutValues[i] = values[i]
        layoutShortSide = m.shortSide
        layoutMaxWidth = maxWidth
        layoutWidthPx = layoutWidth
        layoutTextSize = size
        // ارتفاع صندوق المحارف من التخطيط نفسه لا من مقاييس الأداة: مقاطع السطر
        // مختلفة الحجم والوزن، والتخطيط هو من يعرف أكبرها. ويُوسَّط داخل صندوق
        // السطر عند الرسم، تمامًا كما توسّط الشاشة نصَّها في صندوقه
        layoutGlyphBox = built[0].height.toFloat()
        layoutLineBox = lineBox
        layoutLineGap = lineGap
        layoutPadH = padH
        layoutPadV = padV
        layoutCorner = corner
        layoutPanelWidth = innerWidth + 2f * padH
        layoutContentHeight = LINES * lineBox + (LINES - 1) * lineGap
        return built
    }

    /**
     * السطر الواحد: تسميةٌ ثمّ قيمة، بتراتبٍ بصريّ لا بحجمٍ واحد.
     *
     * على الشاشة نصّان في صفّ: تسميةٌ ‎12dp‎ بأبيضَ ‎0.72‎ ووزنٍ عاديّ، وقيمةٌ ‎16dp‎
     * بيضاءُ عريضة. وفي الملفّ كان السطر كلّه مقطعًا واحدًا بحجمٍ واحد ولونٍ واحد
     * ووزنٍ عريض، فيختفي التراتب ويصير السطر كتلةً مصمتة.
     *
     * ولا سبيل إلى نصّين هنا: الاتّجاه ثنائيّ الجهة يُحسب على **الفقرة** كلّها،
     * فتقطيعُ السطر إلى تخطيطين يعيدنا إلى رصفٍ يدويّ هو بعينه ما أخرج
     * ‎00:00:03‎ مقلوبةً. الحلّ أن يبقى السطر فقرةً واحدة وتُلبَس مقاطعُه أنماطَها:
     * - [RelativeSizeSpan] نسبةً لا حجمًا مطلقًا، فيتبع المقطعُ الصغير أيَّ تصغيرٍ
     *   يقع على الفقرة كلّها بلا حسابٍ ثانٍ.
     * - [ForegroundColorSpan] للخفوت، فيبقى لون الـ Paint للقيمة.
     * - [StyleSpan] للتعريض على القيمة وحدها؛ والـ Paint نفسه متوسّط الوزن.
     *
     * ومقاييس السطر تتبع أكبر مقاطعه (القيمة)، فارتفاعه لا يتغيّر بخفض التسمية.
     */
    private fun buildSpanned(label: String, value: String, gapScale: Float): SpannableString {
        val text = "$label $value"
        val gapAt = label.length
        val span = SpannableString(text)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        span.setSpan(RelativeSizeSpan(LABEL_OF_VALUE), 0, gapAt, flag)
        span.setSpan(ForegroundColorSpan(LABEL_COLOR), 0, gapAt, flag)
        span.setSpan(ScaleXSpan(gapScale), gapAt, gapAt + 1, flag)
        span.setSpan(StyleSpan(Typeface.BOLD), gapAt + 1, text.length, flag)
        return span
    }

    /**
     * سطرٌ واحد بارتفاع سطرٍ واحد. `maxLines(1)` مع `ellipsize(END)` يحلّان محلّ
     * القصّ اليدويّ السابق: التخطيط يقصّ بنفسه إن بقي السطر أعرض من اللوحة بعد
     * بلوغ قاع التصغير — وهو مسارٌ لا يُسلك إلّا عند نسبِ أبعادٍ شاذّة.
     *
     * `includePad = false` كي يكون ارتفاع السطر `descent − ascent` تمامًا لا
     * `bottom − top`، فيبقى توسيطه داخل صندوق السطر مطابقًا لما تفعله الشاشة.
     * والوصل والتقطيع مُعطَّلان: سطرٌ واحدٌ لا يحتاجهما، وهما كلفةٌ بلا مقابل.
     */
    private fun buildLine(line: CharSequence, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(line, 0, line.length, statsText, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /**
     * قوس السرعة في الركن السفليّ **الأيسر**، وفي وسطه الرقم ووحدته.
     *
     * هنا يبقى `drawTextRun` على حاله ولا حاجة إلى `StaticLayout`: كلٌّ من النصّين
     * مقطعٌ أحاديّ الاتّجاه لا اختلاط فيه — الرقم أرقامٌ لاتينيّة صرفة (LTR)
     * و`كم/س` عربيّةٌ صرفة تتوسّطها شرطةٌ محايدة تأخذ اتّجاه جاريها (RTL). وإذ لا
     * مقطعين مختلفَي الاتّجاه في سطرٍ واحد فلا شيء تعيد الخوارزميّة ترتيبه، ويكفي
     * أن نُملي الاتّجاه الصحيح لكلٍّ منهما. وهذا هو ما يجعل الكتلتين مختلفتين:
     * الاختلاط هو ما يوجب محرّك التخطيط، لا مجرّد وجود العربيّة.
     */
    private fun drawGauge(canvas: Canvas, cx: Float, cy: Float, m: HudSizes, s: HudSnapshot) {
        // القطر في العقد خارجيّ (يشمل عرض القوس) كما هو مقاس الخليّة على الشاشة،
        // فنصف قطر مسار القوس هو المتبقّي بعد نصف سماكته من كلّ جانب
        val thickness = m.ringStroke
        val radius = (m.ringDiameter - thickness) / 2f

        val fraction = (s.speedKmh / s.gaugeMaxKmh).coerceIn(0f, 1f)

        // **الوجه يرسمه `drawGaugeFace` نفسه الذي يرسم الشاشة.**
        //
        // كان هنا قوسٌ كلاسيكيٌّ مكتوبٌ بيده، فمن اختار «مؤشّرًا» أو «شراتٍ» وجد في
        // ملفّه قوسًا لا يشبه شاشته (الدَّين الأوّل في خارطة الطريق). ونسخُ التصاميم
        // الستّة إلى `android.graphics.Canvas` كان يعني هندستين تتباعدان عند أوّل
        // تعديل — وهو عين ما تمنعه قاعدة «الاشتقاق الواحد».
        //
        // والجسر `CanvasDrawScope`: يشغّل أوامر `DrawScope` على أيّ قماشٍ أصليّ، فلا
        // تركيبَ هنا ولا `Context` — وهما وحدهما ما يمنعه هذا الملفّ، لا Compose كلُّها.
        val side = m.ringDiameter
        canvas.save()
        canvas.translate(cx - side / 2f, cy - side / 2f)
        drawScope.draw(unitDensity, LayoutDirection.Ltr, ComposeCanvas(canvas), Size(side, side)) {
            drawGaugeFace(
                style = s.gaugeStyle,
                fraction = fraction,
                warnFraction = (s.warnKmh.toFloat() / s.gaugeMaxKmh).coerceIn(0f, 1f),
                // الاصطلاح نفسه في `SpeedScale.limitFraction`: السالب يعني «لا حدّ»
                limitFraction = if (s.limitKmh in 1..s.gaugeMaxKmh) {
                    s.limitKmh.toFloat() / s.gaugeMaxKmh
                } else {
                    -1f
                },
                palette = burnPalette,
                // بلا تدريجٍ رقميّ: أرقامه تحتاج `TextMeasurer`، وقياسُ نصٍّ لكلّ شرطةٍ
                // في كلّ إطار عملٌ لا يحتمله خيط الرسم. والقوس وحده يكفي في ملفٍّ
                // يُشاهَد لا يُقرأ منه رقمُ تدريج.
                ticks = null,
            )
        }
        canvas.restore()

        val value = gaugeText
        gaugeValue.textSize = m.gaugeValueText
        // ثلاثة أرقام عند السرعات العالية أعرض من وترِ الدائرة الداخليّة عند ارتفاع
        // رؤوس الأرقام، فنصغّرها بقدرٍ محسوب بدل أن نتركها تلامس القوس
        val naturalWidth = gaugeValue.measureText(value)
        val maxWidth = HudMetrics.VALUE_MAX_WIDTH_OF_RADIUS * radius
        if (naturalWidth > maxWidth) gaugeValue.textSize *= maxWidth / naturalWidth
        gaugeUnit.textSize = m.gaugeUnitText

        applyShadow(gaugeValue)
        applyShadow(gaugeUnit)

        gaugeValue.getFontMetrics(valueFm)
        gaugeUnit.getFontMetrics(unitFm)

        // الكتلة (رقم + فجوة + وحدة) تُوسَّط على مركز الحلقة بمقاييس الخطّ لا
        // بمعاملات تخمينيّة، والفجوة من العقد لا من تقديرٍ محلّيّ: هي الخلوص نفسه
        // الذي يتركه عمود الشاشة بين صندوقَي النصّين
        val valueHeight = valueFm.descent - valueFm.ascent
        val unitHeight = unitFm.descent - unitFm.ascent
        val innerGap = m.gaugeStackGap
        val stackTop = cy - (valueHeight + innerGap + unitHeight) / 2f

        val valueBaseline = stackTop - valueFm.ascent
        val unitBaseline = valueBaseline + valueFm.descent + innerGap - unitFm.ascent

        val valueWidth = gaugeValue.measureText(value)
        canvas.drawTextRun(value, 0, value.length, 0, value.length, cx - valueWidth / 2f, valueBaseline, false, gaugeValue)

        val unitWidth = gaugeUnit.measureText(UNIT_KMH)
        canvas.drawTextRun(UNIT_KMH, 0, UNIT_KMH.length, 0, UNIT_KMH.length, cx - unitWidth / 2f, unitBaseline, true, gaugeUnit)
    }

    /**
     * الخطّ الشعاعيّ الأحمر عند حدّ السائق، على مسار القوس نفسه.
     *
     * علامةٌ لا تبدّلَ لون: اللون يقول «تجاوزتَ» بعد فوات الأمر، والعلامة تقول
     * «هنا الحدّ» قبله فيُهدّئ السائق قبل بلوغه. وهي في الملفّ المحروق كما هي على
     * الشاشة، لأنّ المواصفة توجب أن يكون المرسوم هو المحروق.
     *
     * النسب من [HudMetrics] لا من تقديرٍ محلّيّ: العرض والطول محسوبان من سماكة
     * القوس، فتخرج العلامة بالنسبة ذاتها على الشاشة وفي الملفّ وعند أيّ دقّة.
     */
    private fun drawLimitMark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        thickness: Float,
        s: HudSnapshot,
    ) {
        // خارج المدى أو بلا حدّ: لا شيء يُرسم
        if (s.limitKmh <= 0 || s.limitKmh > s.gaugeMaxKmh) return
        val fraction = s.limitKmh.toFloat() / s.gaugeMaxKmh
        val angle = Math.toRadians((HudMetrics.ARC_START + HudMetrics.ARC_SWEEP * fraction).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val half = thickness * HudMetrics.LIMIT_MARK_LENGTH_OF_STROKE / 2f
        limitMark.strokeWidth = thickness * HudMetrics.LIMIT_MARK_WIDTH_OF_STROKE
        canvas.drawLine(
            cx + cosA * (radius - half),
            cy + sinA * (radius - half),
            cx + cosA * (radius + half),
            cy + sinA * (radius + half),
            limitMark,
        )
    }

    /**
     * ظلّ ناعم بديلًا عن النسخة السوداء المزاحة التي كانت تبدو لطخة: كان الإزاحة
     * مشتقّة من ارتفاع الإطار لا من حجم الحرف، وبلا أيّ تنعيم.
     *
     * اخترنا `setShadowLayer` لا `BlurMaskFilter`: قماش `OverlayEffect` مقفول من
     * `Surface` (معجَّل بالعتاد)، وظلال النصّ هي مسار الظلّ الوحيد المدعوم هناك،
     * بينما مرشّحات القناع قد تُتجاهَل بصمت. النصف القطر والإزاحة من حجم الحرف
     * فيثبت المظهر عند أيّ دقّة، وبالنسبتين نفسِهما اللتين تستعملهما الشاشة.
     */
    private fun applyShadow(paint: Paint) {
        paint.setShadowLayer(
            HudMetrics.SHADOW_BLUR_OF_TEXT * paint.textSize,
            0f,
            HudMetrics.SHADOW_DY_OF_TEXT * paint.textSize,
            Color.BLACK,
        )
    }

    /** أعرض سطرٍ بمقاطعه المنسَّقة: `getDesiredWidth` يحسب المقاطع كما يحسبها التخطيط */
    private fun widestLine(): Float {
        var widest = 0f
        for (line in spanned) {
            if (line == null) continue
            widest = maxOf(widest, Layout.getDesiredWidth(line, 0, line.length, statsText))
        }
        return widest
    }

    /** التنسيق يتبع اللقطة لا الإطار، واللقطة تتبدّل مع كلّ تحديث موقع لا ستّين مرّة في الثانية */
    private fun formatStats(s: HudSnapshot): Array<String> {
        if (s != formattedFor) {
            statValues[0] = "${Fmt.distance(s.distanceKm)} $UNIT_KM"
            statValues[1] = "${Fmt.speed(s.maxSpeedKmh)} $UNIT_KMH"
            statValues[2] = "${Fmt.avg(s.avgSpeedKmh)} $UNIT_KMH"
            statValues[3] = Fmt.duration(s.durationMs)
            gaugeText = Fmt.speed(s.speedKmh)
            formattedFor = s
        }
        return statValues
    }

    private companion object {
        /**
         * عدد أسطر اللوحة. صار أربعةً في 0.6.0 بإضافة المتوسّط، واللوحة تسع الزيادة
         * في الوضعين: ارتفاعها `4·24 + 3·6 + 2·12 = 138` من ‎411‎، أي ‎0.336‎ من
         * الضلع الأقصر، ومع هامش القاع ‎0.375‎ منه — وهو أقلّ من الارتفاع مهما كان
         * الضلع الأقصر (في الوضع الأفقيّ الضلعُ هو الارتفاع نفسه). فلا حاجة إلى
         * تعديل نسب [HudMetrics] ولا إلى إعادة ترتيب الأسطر.
         */
        const val LINES = 4

        /** ألفا من ‎0..1‎ إلى ‎0..255‎: ‎0.50‎ → ‎128‎. النسخة السابقة كتبت 120 و130 يدويًّا فخالفت المواصفة في الاثنتين */
        fun alpha255(alpha: Float): Int = (alpha * 255f).roundToInt()

        val PANEL_ALPHA = alpha255(HudMetrics.PANEL_ALPHA)
        val TRACK_ALPHA = alpha255(HudMetrics.TRACK_ALPHA)
        val LABEL_COLOR = Color.argb(alpha255(HudMetrics.LABEL_ALPHA), 255, 255, 255)

        /** حجم التسمية نسبةً إلى حجم القيمة — نسبةٌ لا مقاس، فتصمد أمام التصغير */
        const val LABEL_OF_VALUE = HudMetrics.STAT_LABEL_TEXT / HudMetrics.STAT_VALUE_TEXT

        const val WEIGHT_MEDIUM = 500
        const val WEIGHT_BLACK = 900

        /**
         * وزنٌ رقميّ حيث تسمح المنصّة. `Typeface.create(Typeface, int, boolean)`
         * من API 28، و`minSdk` هنا 26 — فدونها نقرّب: العريض للأوزان الثقيلة
         * والعاديّ لما دونها، وهو أقصى ما يبلغه `Typeface` القديم.
         */
        fun weighted(weight: Int): Typeface =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, weight, false)
            } else if (weight >= 600) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }

        /** نفس نصوص `res/values/strings.xml`، مكرّرة لأنّ الراسم بلا `Context` */
        const val LABEL_DISTANCE = "المسافة"
        const val LABEL_MAX_SPEED = "أقصى سرعة"
        const val LABEL_AVG_SPEED = "المتوسّط"
        const val LABEL_DURATION = "المدة"
        const val UNIT_KM = "كم"
        const val UNIT_KMH = "كم/س"

        /**
         * ترتيب المرجع الموحَّد محفوظ (مسافة ← أقصى سرعة ← مدّة)، والمتوسّط أُقحم
         * قبل المدّة: هو سرعةٌ فيجاور أختها، والمدّة تبقى آخر سطرٍ كما اعتادها العين.
         */
        val LABELS = arrayOf(LABEL_DISTANCE, LABEL_MAX_SPEED, LABEL_AVG_SPEED, LABEL_DURATION)

        /**
         * لغة النصّ العربيّ صراحةً كي يقع اختيار الخطّ والتشكيل على الشكل العربيّ
         * لا على شكلٍ فارسيّ أو أرديّ من خطوطٍ احتياطيّة.
         *
         * وهي لا تمسّ الأرقام: المحارف التي نرسمها ‎0-9‎ اللاتينيّة كما تُخرجها
         * `Fmt` بـ `Locale.US` (قاعدة المشروع 4)، و`textLocale` لا يستبدلها
         * بأرقامٍ هنديّة.
         */
        val ARABIC: Locale = Locale.forLanguageTag("ar")
    }
}

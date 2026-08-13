package net.gnutux.speedometer.core.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import androidx.camera.effects.Frame
import java.util.Locale

/** ما يُرسم في الإطار. حقل واحد متقلّب تكتبه خيوط القياس وتقرأه خيط الرسم. */
data class HudSnapshot(
    val speedKmh: Float = 0f,
    val distanceKm: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val durationMs: Long = 0L,
    val gaugeMaxKmh: Int = 200,
    val warnKmh: Int = 100,
)

/**
 * يرسم طبقة العدّاد **داخل** إطار الفيديو عبر [androidx.camera.effects.OverlayEffect].
 *
 * الرسم يقع في فضاء الإطار الخارج (بعد القصّ والدوران) لا في فضاء المخزن الخام،
 * وإلا خرجت الطبقة مائلة أو مقلوبة بحسب مسك الهاتف. المصفوفة أدناه تعكس مسار
 * المعالجة: مخزن ← قصّ ← دوران ← خارج، فنبني معكوسها لنرسم في الفضاء الأخير.
 *
 * كل الأرقام بـ [Locale.US]: أرقام 0-9 لا ١-٩، ونقطة عشرية لا فاصلة.
 */
class VideoOverlayPainter {

    @Volatile
    var snapshot: HudSnapshot = HudSnapshot()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
        alpha = 170
    }
    private val matrix = Matrix()
    private val corners = FloatArray(8)

    fun onDraw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val crop = frame.cropRect
        val rotation = frame.rotationDegrees
        val swapped = rotation == 90 || rotation == 270
        val outW = (if (swapped) crop.height() else crop.width()).toFloat()
        val outH = (if (swapped) crop.width() else crop.height()).toFloat()
        if (outW <= 0f || outH <= 0f) return true

        canvas.setMatrix(outputToBuffer(rotation, outW, outH, crop.left.toFloat(), crop.top.toFloat()))
        drawHud(canvas, outW, outH)
        return true
    }

    /**
     * مصفوفة تحوّل إحداثيات الإطار الخارج إلى إحداثيات المخزن.
     * نُدير بعكس زاوية الخرج، ثم نزيح الناتج ليبدأ من ركن القصّ.
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
        corners[0] = 0f; corners[1] = 0f
        corners[2] = outW; corners[3] = 0f
        corners[4] = outW; corners[5] = outH
        corners[6] = 0f; corners[7] = outH
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

    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val s = snapshot
        // كل المقاسات نسبةً إلى ارتفاع الإطار، فتتطابق الطبقة في 720p و1080p
        val unit = h / 100f
        val margin = unit * 5f

        drawStats(canvas, margin, h - margin, unit, s)
        drawGauge(canvas, w - margin, h - margin, unit, s)
    }

    private fun drawStats(canvas: Canvas, left: Float, bottom: Float, unit: Float, s: HudSnapshot) {
        val lineSize = unit * 4.2f
        val gap = unit * 1.6f
        val padding = unit * 2f
        val lines = listOf(
            "المسافة  ${fmt(s.distanceKm, 2)} كم",
            "أقصى سرعة  ${s.maxSpeedKmh.toInt()} كم/س",
            "المدة  ${duration(s.durationMs)}",
        )

        text.textSize = lineSize
        text.textAlign = Paint.Align.LEFT
        var maxWidth = 0f
        lines.forEach { maxWidth = maxOf(maxWidth, text.measureText(it)) }

        val blockHeight = lines.size * lineSize + (lines.size - 1) * gap
        val top = bottom - blockHeight - padding * 2

        fill.color = Color.BLACK
        fill.alpha = 120
        canvas.drawRoundRect(
            RectF(left, top, left + maxWidth + padding * 2, bottom),
            unit * 2f,
            unit * 2f,
            fill,
        )

        text.color = Color.WHITE
        text.alpha = 255
        var y = top + padding + lineSize * 0.85f
        lines.forEach { line ->
            canvas.drawText(line, left + padding, y, text)
            y += lineSize + gap
        }
    }

    private fun drawGauge(canvas: Canvas, right: Float, bottom: Float, unit: Float, s: HudSnapshot) {
        val radius = unit * 12f
        val thickness = unit * 2.4f
        val cx = right - radius
        val cy = bottom - radius
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        stroke.strokeWidth = thickness
        stroke.strokeCap = Paint.Cap.ROUND

        stroke.color = Color.BLACK
        stroke.alpha = 130
        canvas.drawArc(box, START_ANGLE, SWEEP_ANGLE, false, stroke)

        val fraction = (s.speedKmh / s.gaugeMaxKmh).coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            stroke.color = when {
                s.speedKmh >= s.gaugeMaxKmh * 0.92f -> COLOR_DANGER
                s.speedKmh >= s.warnKmh -> COLOR_WARN
                else -> COLOR_ACCENT
            }
            stroke.alpha = 255
            canvas.drawArc(box, START_ANGLE, SWEEP_ANGLE * fraction, false, stroke)
        }

        val value = String.format(Locale.US, "%d", s.speedKmh.toInt())
        text.textAlign = Paint.Align.CENTER
        shadow.textAlign = Paint.Align.CENTER
        text.textSize = radius * 0.95f
        shadow.textSize = text.textSize
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        // ظلّ خلف الرقم: الخلفية مشهدٌ متحرّك، والنص العاري يذوب في الفاتح منه
        canvas.drawText(value, cx + unit * 0.35f, baseline + unit * 0.35f, shadow)
        text.color = Color.WHITE
        text.alpha = 255
        canvas.drawText(value, cx, baseline, text)

        text.textSize = radius * 0.32f
        canvas.drawText("كم/س", cx, baseline + radius * 0.5f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

    private fun duration(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    private companion object {
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f
        const val COLOR_ACCENT = 0xFF00E5C7.toInt()
        const val COLOR_WARN = 0xFFFFB020.toInt()
        const val COLOR_DANGER = 0xFFFF5A45.toInt()
    }
}

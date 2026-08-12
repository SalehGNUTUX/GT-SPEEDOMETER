package net.gnutux.speedometer.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import java.util.Locale

/**
 * يرسم رقم السرعة أيقونةً لشريط الحالة.
 *
 * مشروع Status-Bar-Tachometer يحلّ هذا بتوليد ألف ملفّ رسوميّ مسبقًا
 * (icon000…icon999) ويختار بالاسم. النتيجة نفسها تُنال برسم الرقم وقت التشغيل
 * وتخزين ما رُسم، فيبقى الحجم صفرًا في الحزمة. النظام يلوّن الأيقونة بنفسه،
 * فنرسمها بيضاء على شفّاف.
 */
object SpeedIcon {

    private const val SIZE = 96
    private val cache = HashMap<Int, Icon>(64)

    fun forSpeed(kmh: Int): Icon {
        val value = kmh.coerceIn(0, 999)
        cache[value]?.let { return it }

        val text = String.format(Locale.US, "%d", value)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = when (text.length) {
                1 -> SIZE * 0.92f
                2 -> SIZE * 0.80f
                else -> SIZE * 0.60f
            }
        }
        val baseline = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, SIZE / 2f, baseline, paint)

        val icon = Icon.createWithBitmap(bitmap)
        // الحدّ الأقصى للسرعات المعقولة؛ ما فوقها نادر ولا يستحقّ إبقاءه محفوظًا
        if (value <= 300) cache[value] = icon
        return icon
    }
}

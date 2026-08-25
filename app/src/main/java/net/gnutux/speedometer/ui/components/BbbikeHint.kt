package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import net.gnutux.speedometer.R
import androidx.compose.ui.res.stringResource
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.TextSecondary

/**
 * صورةٌ توضيحيّة لجدول التنزيل في BBBike، والصفُّ المقصود مؤطَّرٌ وسهمٌ إليه.
 *
 * ## لماذا رسمٌ لا لقطةُ شاشة
 * ثلاثة أسباب، مرتّبةً بأثرها:
 *
 * 1. **الحجم صفر.** لقطةٌ مقروءةٌ لجدولٍ بعشرة صفوفٍ لا تنزل عن مئة كيلوبايت،
 *    وهذا الرسم بضعةُ آلافٍ من الشيفرة. والحزمة ‎14‎ م.ب نحرسها.
 * 2. **يبقى حادًّا في كلّ كثافة.** اللقطة تُمطَّط على لوحٍ وتُصغَّر على هاتفٍ صغير.
 * 3. **يتبع سمة التطبيق.** لقطةُ صفحةٍ بيضاء وسط واجهةٍ داكنة رقعةٌ غريبة.
 *
 * ولا يقصد الرسم أن يخدع بأنّه اللقطة نفسها: هو **مخطَّطٌ للصفوف** — أسماؤها
 * وأحجامها كما هي في الصفحة — يعرف به المستعمل الصفَّ حين يراه لا قبله.
 *
 * ## والنقر يُكبّرها
 * الصفوف عشرة، وأسماؤها لاتينيّةٌ متشابهة (`MB vector tiles` بجوار
 * `PM vector tiles`). فمقاسُها داخل قسمٍ في الإعدادات لا يكفي لتمييز حرفٍ واحد،
 * والنقر يفتحها ملءَ الشاشة.
 */
@Composable
fun BbbikeHint(modifier: Modifier = Modifier) {
    var enlarged by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SHEET)
            .clickable { enlarged = true }
            .padding(12.dp),
    ) {
        BbbikeTable(scale = 1f)
        Text(
            text = stringResource(R.string.mapdl_hint_tap),
            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }

    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(SHEET)
                    .clickable { enlarged = false }
                    .padding(18.dp),
            ) {
                BbbikeTable(scale = 1.5f)
            }
        }
    }
}

/**
 * الجدول كما يظهر في الصفحة: صفٌّ لكلّ صيغة، والمقصود مؤطَّرٌ ومعه سهم.
 *
 * و[scale] معاملٌ واحد يكبّر كلّ شيء معًا — المقاسات والحروف والأطر — فلا تتباعد
 * النسب بين الصورة المصغَّرة والمكبَّرة.
 */
@Composable
private fun BbbikeTable(scale: Float) {
    // اتّجاه الجدول لاتينيٌّ دائمًا: هو صورةٌ لصفحةٍ إنجليزيّة، وقلبُه يجعله يخالف
    // ما سيراه المستعمل بعينه — والغرض كلُّه أن يطابقه
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Download OpenStreetMap data for …",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * scale).sp,
                ),
                modifier = Modifier.padding(bottom = (8 * scale).dp),
            )

            for (row in ROWS) {
                if (row.target) TargetRow(row, scale) else PlainRow(row, scale)
                Spacer(Modifier.height((4 * scale).dp))
            }
        }
    }
}

/** صفٌّ عاديّ: إطارٌ رماديٌّ رفيع كما في الصفحة، بلا لفتِ نظر */
@Composable
private fun PlainRow(row: Row, scale: Float) {
    Box(
        Modifier
            .border(1.dp, ROW_BORDER, RoundedCornerShape(3.dp))
            .padding(horizontal = (7 * scale).dp, vertical = (4 * scale).dp)
    ) {
        RowText(row, scale, dim = true)
    }
}

/**
 * الصفّ المقصود: إطارٌ بلون اللوحة وسهمٌ يشير إليه.
 *
 * السهم من اليمين لا من الأسفل: الصفوف متلاصقة، وسهمٌ من تحتٍ يقع على الصفّ الذي
 * يليه فيلتبس المشار إليه. ومن اليمين يقع في فراغ العمود فلا يزاحم شيئًا.
 */
@Composable
private fun TargetRow(row: Row, scale: Float) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .border(width = (2 * scale).dp, color = Accent, RoundedCornerShape(5.dp))
                .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
                .padding(horizontal = (7 * scale).dp, vertical = (5 * scale).dp)
        ) {
            RowText(row, scale, dim = false)
        }

        androidx.compose.foundation.Canvas(
            Modifier
                .padding(start = (6 * scale).dp)
                .width((44 * scale).dp)
                .height((18 * scale).dp)
        ) {
            drawPointer(scale)
        }
    }
}

@Composable
private fun RowText(row: Row, scale: Float, dim: Boolean) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (dim) LINK_DIM else LINK,
                fontSize = (10.5f * scale).sp,
                fontWeight = if (dim) FontWeight.Normal else FontWeight.Bold,
            ),
        )
        Text(
            text = "  ${row.size}",
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextSecondary,
                fontSize = (9 * scale).sp,
            ),
        )
    }
}

/**
 * سهمٌ مصمت برأسٍ مثلَّث وذيلٍ يدقّ.
 *
 * يُرسم مسارًا واحدًا لا خطًّا برأسٍ ملصوق: الخطّ ذو السُّمك الواحد يبدو مرسومًا
 * بعجل، والمسار المدبَّب يُقرأ إشارةً مقصودة. ويشير إلى اليسار لأنّ الصفّ عن يساره.
 */
private fun DrawScope.drawPointer(scale: Float) {
    val h = size.height
    val w = size.width
    val head = w * 0.42f
    val tailThickness = h * 0.16f
    val midY = h / 2f

    val arrow = Path().apply {
        // رأسُ المثلَّث عند الحافّة اليسرى، ملتصقًا بالإطار
        moveTo(0f, midY)
        lineTo(head, midY - h * 0.42f)
        lineTo(head, midY - tailThickness)
        // الذيل يمتدّ يمينًا ويدقّ قليلًا عند طرفه
        lineTo(w, midY - tailThickness * 0.55f)
        lineTo(w, midY + tailThickness * 0.55f)
        lineTo(head, midY + tailThickness)
        lineTo(head, midY + h * 0.42f)
        close()
    }
    // حافّةٌ داكنةٌ رفيعة تفصله عن خلفيّة الصفحة الفاتحة، ثمّ الجسم بلون اللوحة.
    // (وكان ظلًّا مُزاحًا فحُذف: `translate` داخل `DrawScope` تشتبك بالتحميلات
    // فيظنّها المصرّف نداءً تركيبيًّا، وحافّةٌ واحدة تكفي للفصل.)
    drawPath(arrow, ARROW_EDGE, style = Stroke(width = 2.4f * scale))
    drawPath(arrow, ARROW)
}

/** صفٌّ في الجدول: اسمُه وحجمُه، وهل هو المقصود */
private class Row(val label: String, val size: String, val target: Boolean = false)

/**
 * الصفوف كما في صفحة BBBike، بأسمائها وأحجامها التقريبيّة.
 *
 * والأحجام تُذكر لأنّها جزءٌ ممّا يراه المستعمل فيطابق به الصفّ. وهي تتبدّل مع كلّ
 * تحديثٍ للبيانات، فلا تُقرأ وعدًا — والنصّ حولها لا يبني عليها شيئًا.
 */
private val ROWS = listOf(
    Row("Protocolbuffer (PBF)", "263M"),
    Row("Garmin Ontrail (latin1)", "48M"),
    Row("Garmin BBBike (latin1)", "107M"),
    Row("Mapsforge OSM", "131M"),
    Row("MB vector tiles shortbread", "187M"),
    Row("PM vector tiles shortbread", "177M", target = true),
    Row("GeoParquet", "626M"),
    Row("GeoPackage", "545M"),
)

/** ألوانُ صفحةٍ ويبٍّ فاتحة، لا ألوانُ سمتنا: الغرض أن تُشبه ما سيراه */
private val SHEET = Color(0xFFF3F4F2)
private val ROW_BORDER = Color(0xFFCBCFC9)
private val LINK = Color(0xFF1B7A3E)
private val LINK_DIM = Color(0xFF6E8C77)

/** أحمرُ إشارةٍ لا فيروزيُّ اللوحة: السهم يقع على صفحةٍ خضراءَ فاتحة فيذوب فيها */
private val ARROW = Color(0xFFE03131)
private val ARROW_EDGE = Color(0xFF8C1D1D)


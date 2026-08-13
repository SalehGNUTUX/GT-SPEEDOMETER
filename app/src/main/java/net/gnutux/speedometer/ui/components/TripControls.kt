package net.gnutux.speedometer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.theme.GtTheme

/**
 * أزرار الرحلة: زوجٌ واحد يخدم شاشة العدّاد والشاشة الرقميّة معًا.
 *
 * استُخرج إلى مكوّنٍ مشترك لأنّ الشاشتين كانتا تفترقان: الرقميّة لم تكن تملك أيّ سبيلٍ
 * لبدء رحلةٍ أو إنهائها، فكانت الرحلة التي تبدأ عندها مستحيلة ولا يصل منها شيء إلى
 * السجلّ. موضعٌ واحد للسلوك يمنع عودة هذا الافتراق.
 *
 * الشكل: زرّان متساويان في صفٍّ واحد، حوافّهما المتلاقية حادّة وأطرافهما الخارجيّة
 * موقوصة، فيُقرآن كمفتاحٍ مجزّأ لا كزرّين متجاورين مصادفةً.
 */

/** نصف الارتفاع: يجعل الطرف الخارجيّ نصف دائرةٍ تامّة فيبدو الزوج قرصًا واحدًا مشقوقًا */
private val OuterRadius = 36.dp

/** القاعدة 7: ≥72 نقطة كي تُضغط بقفّازٍ على المقود دون تصويب */
private val ControlHeight = 72.dp

/**
 * فاصلٌ رفيع لا التصاق: الفيروزيّ والأحمر شبه متتامّين، وتماسّهما يُحدث اهتزازًا
 * لونيًّا عند الحدّ. الفجوة تُظهر الشقّ وتترك منطقةً ميّتة صغيرة تمنع إصابة «إنهاء»
 * سهوًا حين يقع الإبهام على الحدّ.
 */
private val SeamGap = 4.dp

@Composable
fun TripControls(
    status: TripStatus,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GtTheme.colors

    // التباين يُقرأ في اللوحتين: الفيروزيّ الفاتح (داكنة) يحمل نصًّا داكنًا، والأخضر
    // الغامق والأحمر القاني (فاتحة) يحملان أبيض. لا افتراض بأنّ السمة داكنة.
    val onFill = if (colors.isDark) colors.bg else Color.White

    val primaryLabel = when (status) {
        TripStatus.IDLE, TripStatus.FINISHED -> stringResource(R.string.action_start)
        TripStatus.RUNNING -> stringResource(R.string.action_pause)
        TripStatus.PAUSED -> stringResource(R.string.action_resume)
    }
    val primaryIcon = if (status == TripStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow

    // لا رحلة بعدُ ⇒ لا إنهاء. يبقى الزرّ في مكانه معطَّلًا: الهندسة ثابتة فلا يتزحزح
    // هدف الإبهام عند بدء الرحلة، ويتعلّم المستعمل أنّ للإنهاء موضعًا قبل أن يحتاجه.
    val canFinish = status == TripStatus.RUNNING || status == TripStatus.PAUSED

    // الانتقال لونٌ متدرّج لا قفزة تخطيط: الزرّ لا يظهر فجأةً ولا يزيح جاره.
    val finishContainer by animateColorAsState(
        targetValue = if (canFinish) colors.danger else colors.surfaceHigh,
        animationSpec = tween(220),
        label = "finishContainer",
    )
    val finishContent by animateColorAsState(
        targetValue = if (canFinish) onFill else colors.textSecondary,
        animationSpec = tween(220),
        label = "finishContent",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ControlHeight),
        horizontalArrangement = Arrangement.spacedBy(SeamGap),
    ) {
        // `topStart`/`topEnd` لا `topLeft`: التطبيق عربيّ، والقوس يتبع اتّجاه التخطيط
        // فيبقى الطرف الخارجيّ موقوصًا والحدّ الداخليّ حادًّا بعد الانعكاس.
        Button(
            onClick = onToggle,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(
                topStart = OuterRadius,
                bottomStart = OuterRadius,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = onFill,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            ControlLabel(icon = primaryIcon, label = primaryLabel)
        }

        Button(
            onClick = onFinish,
            enabled = canFinish,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(
                topStart = 0.dp,
                bottomStart = 0.dp,
                topEnd = OuterRadius,
                bottomEnd = OuterRadius,
            ),
            // ألوان التعطيل هي ذاتها المتحرّكة كي لا يفرض Material طبقة إخفاتٍ
            // تُفسد التدرّج وتُنقص تباين النصّ.
            colors = ButtonDefaults.buttonColors(
                containerColor = finishContainer,
                contentColor = finishContent,
                disabledContainerColor = finishContainer,
                disabledContentColor = finishContent,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            ControlLabel(icon = Icons.Filled.Stop, label = stringResource(R.string.action_stop))
        }
    }
}

/**
 * نصف العرض أضيق من الزرّ الممتدّ، فالنصّ سطرٌ واحد لا غير: «إيقاف مؤقت» في شاشةٍ
 * ضيّقة يُقتطع بنقاطٍ ولا يلتفّ سطرين فيُزيح الأيقونة.
 */
@Composable
private fun ControlLabel(icon: ImageVector, label: String) {
    Icon(imageVector = icon, contentDescription = null)
    Text(
        text = label,
        modifier = Modifier.padding(start = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
        ),
    )
}

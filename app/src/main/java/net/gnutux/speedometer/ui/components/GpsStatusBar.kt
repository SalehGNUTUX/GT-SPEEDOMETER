package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.location.FixQuality
import net.gnutux.speedometer.core.location.GnssInfo
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.Warn

/**
 * شريط حالة التموضع. يعرض المعدّل المتحقّق فعلًا لا المطلوب: أداة قياس تُخفي
 * أن جهازها يسلّم عيّنة واحدة في الثانية إنما تُوهم مستعملها بدقّة لا يملكها.
 */
@Composable
fun GpsStatusBar(info: GnssInfo, modifier: Modifier = Modifier) {
    val color = when (info.quality) {
        FixQuality.NONE -> Danger
        FixQuality.POOR -> Warn
        FixQuality.FAIR -> Warn
        FixQuality.GOOD -> Accent
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Text(
            // «تقريبيّ» بدل «لا إشارة» حين يكون عندنا موضعٌ من الشبكة: الخبران
            // مختلفان، والأوّل يقول للراكب إنّ التطبيق يعمل وينتظر أقمارًا، لا إنّه
            // أعمى. ولا يُكتب «GPS» عليه بحال — تلك كذبةٌ في أداة قياس.
            text = when {
                info.hasFix -> "GPS"
                info.hasCoarse -> stringResource(R.string.gps_coarse)
                else -> stringResource(R.string.gps_none)
            },
            style = MaterialTheme.typography.labelLarge.copy(color = color),
        )
        Text(
            text = stringResource(R.string.gps_satellites, info.satellitesUsed, info.satellitesVisible),
            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
        )
        if (info.hasFix) {
            Text(
                text = stringResource(R.string.gps_rate, Fmt.hz(info.updateHz)),
                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
            )
            if (!info.accuracyM.isNaN()) {
                Text(
                    text = stringResource(R.string.gps_accuracy, info.accuracyM.toInt()),
                    style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
                )
            }
        }
    }
}

/** نسخة مضغوطة للطبقة فوق الكاميرا */
@Composable
fun GpsDot(info: GnssInfo, modifier: Modifier = Modifier) {
    val color: Color = when (info.quality) {
        FixQuality.NONE -> Danger
        FixQuality.POOR, FixQuality.FAIR -> Warn
        FixQuality.GOOD -> Accent
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = "${info.satellitesUsed}/${info.satellitesVisible}",
            style = MaterialTheme.typography.labelMedium.copy(color = Color.White),
        )
    }
}

package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.alert.SpeedZone
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GpsStatusBar
import net.gnutux.speedometer.ui.components.StatTile
import net.gnutux.speedometer.ui.components.TripControls
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.Warn

/** رقم واحد ضخم — أوضح ما يمكن قراءته بلمحة على المقود */
@Composable
fun DigitalScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val scale by vm.speedScale.collectAsStateWithLifecycle()

    val kmh = liveSpeed * 3.6f
    // الحكم من العقد المشترك حرفًا بحرف: هذه الشاشة والقرص والنافذة المصغّرة وطبقة
    // الكاميرا تقول اللون نفسه للسرعة نفسها، وإلّا اختلف المعنى في عين من يلمح
    val color = when (scale.zoneOf(kmh)) {
        SpeedZone.DANGER -> Danger
        SpeedZone.WARN -> Warn
        SpeedZone.NORMAL -> Accent
    }

    // شريط الحالة أعلى، والأزرار أسفل بارتفاعٍ ثابت، وما بينهما `weight(1f)` يتوسّط
    // نفسه. هكذا يُفسَح للأزرار من الفراغ المحيط لا من الرقم: الرقم يبقى على حجمه
    // الكامل لأنّه سبب وجود هذه الشاشة.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GpsStatusBar(
            info = gnss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = Fmt.speed(kmh),
                maxLines = 1,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 170.sp,
                    fontWeight = FontWeight.Black,
                    color = color,
                ),
            )
            Text(
                text = stringResource(R.string.unit_kmh),
                style = MaterialTheme.typography.titleLarge.copy(color = TextSecondary),
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile(
                    icon = Icons.Filled.Route,
                    label = stringResource(R.string.stat_distance),
                    value = Fmt.distance(trip.distanceKm),
                    unit = stringResource(R.string.unit_km),
                )
                StatTile(
                    icon = Icons.Filled.Timer,
                    label = stringResource(R.string.stat_duration),
                    value = Fmt.duration(trip.elapsedMs),
                    unit = "",
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    label = stringResource(R.string.stat_avg_speed),
                    value = Fmt.avg(trip.avgSpeedKmh),
                    unit = stringResource(R.string.unit_kmh),
                )
                StatTile(
                    icon = Icons.Filled.Speed,
                    label = stringResource(R.string.stat_max_speed),
                    value = Fmt.speed(trip.maxSpeedKmh),
                    unit = stringResource(R.string.unit_kmh),
                )
            }
        }

        // قبل أوّل رحلة لا يعرف المستعمل أنّ الضغط يحفظ المسار كلّه، فيُقال له صراحةً.
        // ويختفي التلميح بعد البداية كي لا يزاحم الأرقام.
        if (trip.status == TripStatus.IDLE) {
            Text(
                text = stringResource(R.string.trip_start_hint),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }

        TripControls(
            status = trip.status,
            onToggle = vm::toggleTrip,
            onFinish = vm::finishTrip,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

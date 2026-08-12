package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GpsStatusBar
import net.gnutux.speedometer.ui.components.SpeedGauge
import net.gnutux.speedometer.ui.components.StatTile
import net.gnutux.speedometer.ui.components.VehicleSelector
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger

@Composable
fun SpeedometerScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GpsStatusBar(
            info = gnss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        SpeedGauge(
            speedKmh = liveSpeed * 3.6f,
            maxKmh = profile.gaugeMaxKmh,
            warnKmh = profile.defaultWarnKmh,
            unitLabel = stringResource(R.string.unit_kmh),
            modifier = Modifier.fillMaxWidth(0.92f),
        )

        VehicleSelector(selected = profile, onSelect = vm::setProfile)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatTile(
                icon = Icons.Filled.Timer,
                label = stringResource(R.string.stat_duration),
                value = Fmt.duration(trip.elapsedMs),
                unit = "",
            )
            StatTile(
                icon = Icons.Filled.Route,
                label = stringResource(R.string.stat_distance),
                value = Fmt.distance(trip.distanceKm),
                unit = stringResource(R.string.unit_km),
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

        TripControls(
            status = trip.status,
            onToggle = vm::toggleTrip,
            onFinish = vm::finishTrip,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun TripControls(
    status: TripStatus,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (status) {
        TripStatus.IDLE, TripStatus.FINISHED -> stringResource(R.string.action_start)
        TripStatus.RUNNING -> stringResource(R.string.action_pause)
        TripStatus.PAUSED -> stringResource(R.string.action_resume)
    }
    val icon = if (status == TripStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 72 نقطة: تُضغط بقفّاز الدراجة دون تصويب
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(36.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = label,
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
            )
        }

        if (status == TripStatus.RUNNING || status == TripStatus.PAUSED) {
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(30.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = Danger)
                Text(
                    text = stringResource(R.string.action_stop),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GpsStatusBar
import net.gnutux.speedometer.ui.components.StatTile
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
    val profile by vm.profile.collectAsStateWithLifecycle()

    val kmh = liveSpeed * 3.6f
    val color = when {
        kmh >= profile.gaugeMaxKmh * 0.92f -> Danger
        kmh >= profile.defaultWarnKmh -> Warn
        else -> Accent
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GpsStatusBar(info = gnss, modifier = Modifier.fillMaxWidth())

        Text(
            text = Fmt.speed(kmh),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 170.sp,
                fontWeight = FontWeight.Black,
                color = color,
            ),
        )
        Text(
            text = stringResource(R.string.unit_kmh),
            style = MaterialTheme.typography.titleLarge.copy(color = TextSecondary),
            modifier = Modifier.padding(bottom = 32.dp),
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
}

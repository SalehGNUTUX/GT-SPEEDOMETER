package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GpsStatusBar
import net.gnutux.speedometer.ui.components.SpeedGauge
import net.gnutux.speedometer.ui.components.StatTile
import net.gnutux.speedometer.ui.components.TripControls
import net.gnutux.speedometer.ui.components.VehicleSelector

@Composable
fun SpeedometerScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    // عمودٌ خارجيّ لا يتمرّر: القرص والبلاطات وحدها تتمرّر داخل `weight(1f)`، وصفّ
    // الأزرار مثبَّت تحته خارج منطقة التمرير. قبلها كان «إنهاء» ينزلق تحت حافّة الشاشة
    // بمجرّد بدء الرحلة فيضطرّ الراكب إلى التمرير بحثًا عنه وهو سائر.
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
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

            // القرص مربّع بنسبة عرضه، فهو لا ينضغط على الشاشات القصيرة: يأخذ حجمه
            // كاملًا وما فاض عن الارتفاع يُدرَك بالتمرير.
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
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

        TripControls(
            status = trip.status,
            onToggle = vm::toggleTrip,
            onFinish = vm::finishTrip,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        )
    }
}

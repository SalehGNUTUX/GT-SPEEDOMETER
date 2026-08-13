package net.gnutux.speedometer.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.trip.TripTrack
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.RouteMap
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجلّ الرحلات المحفوظة، وخريطة مسار كلّ رحلة. */
@Composable
fun TripsScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<TripTrack?>(null) }

    LaunchedEffect(Unit) { vm.refreshTrips() }

    val current = selected
    if (current != null) {
        BackHandler { selected = null }
        TripDetail(
            vm = vm,
            trip = current,
            onBack = { selected = null },
            onDeleted = { selected = null },
            modifier = modifier,
        )
        return
    }

    if (trips.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.trips_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.trips_hint),
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(trips, key = { it.file.absolutePath }) { trip ->
            TripCard(trip = trip, onClick = { selected = trip })
        }
    }
}

@Composable
private fun TripCard(trip: TripTrack, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDate(trip.startMs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Icon(Icons.Filled.Map, contentDescription = null, tint = Accent)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiniStat(stringResource(R.string.stat_distance), Fmt.distance(trip.distanceKm), stringResource(R.string.unit_km))
            MiniStat(stringResource(R.string.stat_duration), Fmt.duration(trip.durationMs), "")
            MiniStat(stringResource(R.string.stat_max_speed), Fmt.speed(trip.maxSpeedKmh), stringResource(R.string.unit_kmh))
            MiniStat(stringResource(R.string.stat_avg_speed), Fmt.avg(trip.avgSpeedKmh), stringResource(R.string.unit_kmh))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
        )
        Text(
            text = if (unit.isEmpty()) value else "$value $unit",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun TripDetail(
    vm: SpeedoViewModel,
    trip: TripTrack,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Accent,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Text(
                text = formatDate(trip.startMs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        if (trip.points.size < 2) {
            Text(
                text = stringResource(R.string.trip_no_route),
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
            )
        } else {
            RouteMap(
                points = trip.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(SurfaceHigh, RoundedCornerShape(16.dp)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(16.dp))
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MiniStat(stringResource(R.string.stat_distance), Fmt.distance(trip.distanceKm), stringResource(R.string.unit_km))
            MiniStat(stringResource(R.string.stat_duration), Fmt.duration(trip.durationMs), "")
            MiniStat(stringResource(R.string.stat_max_speed), Fmt.speed(trip.maxSpeedKmh), stringResource(R.string.unit_kmh))
            MiniStat(stringResource(R.string.stat_avg_speed), Fmt.avg(trip.avgSpeedKmh), stringResource(R.string.unit_kmh))
        }

        Text(
            text = stringResource(R.string.trip_points, trip.points.size),
            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionChip(
                icon = Icons.Filled.Share,
                label = stringResource(R.string.trip_share),
                modifier = Modifier.weight(1f),
            ) {
                val uri = vm.uriForTrack(trip.file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(send, context.getString(R.string.trip_share))
                    )
                }
            }
            ActionChip(
                icon = Icons.Filled.Map,
                label = stringResource(R.string.trip_open_osmand),
                modifier = Modifier.weight(1f),
            ) {
                // OsmAnd يستقبل ملفّ GPX كأي تطبيق عرض. إن لم يكن مثبَّتًا يتولّى
                // النظام عرض بدائله بدل أن يفشل الفعل صامتًا.
                val uri = vm.uriForTrack(trip.file)
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/gpx+xml")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(view, context.getString(R.string.trip_open_osmand))
                    )
                }
            }
            ActionChip(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.media_delete),
                modifier = Modifier.weight(1f),
            ) { confirmDelete = true }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trip_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrip(trip)
                    confirmDelete = false
                    onDeleted()
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = Surface,
        )
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(SurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Accent)
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(ms))

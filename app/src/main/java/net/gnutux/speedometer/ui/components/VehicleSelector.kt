package net.gnutux.speedometer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.TextSecondary

fun VehicleProfile.icon(): ImageVector = when (this) {
    VehicleProfile.CAR -> Icons.Filled.DirectionsCar
    VehicleProfile.MOTORCYCLE -> Icons.Filled.TwoWheeler
    VehicleProfile.BICYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
    VehicleProfile.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
}

/**
 * مبدّل ملف المركبة. الأزرار كبيرة (56 نقطة) عمدًا: تُضغط بقفّاز الدراجة.
 */
@Composable
fun VehicleSelector(
    selected: VehicleProfile,
    onSelect: (VehicleProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VehicleProfile.entries.forEach { profile ->
            val active = profile == selected
            Icon(
                imageVector = profile.icon(),
                contentDescription = stringResource(profile.label),
                tint = if (active) Accent else TextSecondary,
                modifier = Modifier
                    .height(56.dp)
                    .then(
                        if (active) {
                            Modifier.border(1.5.dp, Accent, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(profile) }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .size(28.dp),
            )
        }
    }
}

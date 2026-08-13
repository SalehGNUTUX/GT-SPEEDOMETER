package net.gnutux.speedometer.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import net.gnutux.speedometer.R
import net.gnutux.speedometer.SpeedoApp
import net.gnutux.speedometer.core.trip.TripStatus

/**
 * مربّع في لوحة الإعدادات السريعة يبدأ الرحلة وينهيها بلمسة، دون فتح التطبيق.
 * نافع لراكب الدراجة: يسحب اللوحة ويلمس، ثم يضع الهاتف في مكانه.
 */
class SpeedTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!hasLocationPermission()) {
            updateTile()
            return
        }
        val engine = (application as SpeedoApp).engine
        when (engine.recorder.state.value.status) {
            TripStatus.RUNNING, TripStatus.PAUSED -> {
                engine.finishTrip()
                // إيقافٌ غير مشروط كان يسحب الخدمة ذات نوع «الكاميرا» من تحت تسجيلٍ
                // جارٍ، ولا سبيل إلى إعادة تشغيلها من نداء مربّعٍ في أندرويد 14.
                // فالخدمة تبقى ما بقي للتسجيل حاجةٌ إليها.
                if (!engine.needsForegroundService) {
                    stopService(Intent(this, TripService::class.java))
                }
            }

            TripStatus.IDLE, TripStatus.FINISHED -> {
                engine.startLocation()
                engine.startTrip()
                ContextCompat.startForegroundService(this, Intent(this, TripService::class.java))
            }
        }
        updateTile()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateTile() {
        val tile = qsTile ?: return
        if (!hasLocationPermission()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.tile_label)
            tile.updateTile()
            return
        }
        val status = (application as SpeedoApp).engine.recorder.state.value.status
        val running = status == TripStatus.RUNNING || status == TripStatus.PAUSED
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(if (running) R.string.tile_running else R.string.tile_idle)
        tile.updateTile()
    }
}

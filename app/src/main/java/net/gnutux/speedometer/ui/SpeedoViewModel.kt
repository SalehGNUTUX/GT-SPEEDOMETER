package net.gnutux.speedometer.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.SpeedoApp
import net.gnutux.speedometer.core.media.MediaItem
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.trip.TripStatus
import net.gnutux.speedometer.service.TripService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeedoViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = (app as SpeedoApp).engine

    val tripState = engine.recorder.state
    val gnss = engine.location.gnss
    val liveSpeedMps = engine.liveSpeedMps
    val profile = engine.profile

    val camera = engine.camera
    val isRecording = engine.camera.isRecording
    val cameraMessage = engine.camera.message

    private val _lastSavedTrack = MutableStateFlow<File?>(null)
    val lastSavedTrack = _lastSavedTrack.asStateFlow()

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems = _mediaItems.asStateFlow()

    val isTripActive: Boolean
        get() = tripState.value.status == TripStatus.RUNNING ||
            tripState.value.status == TripStatus.PAUSED

    fun onLocationPermissionGranted() {
        engine.startLocation()
    }

    fun setProfile(p: VehicleProfile) = engine.setProfile(p)

    fun toggleTrip() {
        when (tripState.value.status) {
            TripStatus.IDLE, TripStatus.FINISHED -> {
                engine.startTrip()
                startService()
            }

            TripStatus.RUNNING -> engine.pauseTrip()
            TripStatus.PAUSED -> engine.resumeTrip()
        }
    }

    fun finishTrip() {
        _lastSavedTrack.value = engine.finishTrip()
        stopService()
    }

    fun resetTrip() {
        engine.resetTrip()
        _lastSavedTrack.value = null
    }

    // ===== الكاميرا =====

    fun toggleRecording() {
        if (engine.camera.isRecording.value) {
            engine.camera.stopRecording()
        } else {
            engine.camera.startRecording(onStarted = { engine.markVideoStart() })
        }
    }

    fun consumeCameraMessage() = engine.camera.consumeMessage()

    /** يحفظ لقطة الشاشة كما هي: الكاميرا وفوقها الطبقة */
    fun saveScreenshot(bitmap: Bitmap, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                engine.media.saveImage(bitmap, "shot-$stamp.jpg") != null
            }
            if (ok) refreshMedia()
            onDone(ok)
        }
    }

    // ===== الوسائط =====

    fun refreshMedia() {
        viewModelScope.launch {
            _mediaItems.value = withContext(Dispatchers.IO) { engine.media.list() }
        }
    }

    suspend fun thumbnailOf(item: MediaItem): Bitmap? =
        withContext(Dispatchers.IO) { engine.media.thumbnail(item) }

    fun deleteMedia(item: MediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.media.delete(item) }
            refreshMedia()
        }
    }

    private fun startService() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java))
    }

    private fun stopService() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, TripService::class.java))
    }
}

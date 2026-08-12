package net.gnutux.speedometer.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.camera.CameraSession
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.CompactGauge
import net.gnutux.speedometer.ui.components.GpsDot
import net.gnutux.speedometer.ui.theme.Danger

/**
 * الكاميرا الحيّة وفوقها طبقة العداد.
 *
 * الفيديو المسجَّل **نظيف**: الطبقة تظهر على الشاشة ولا تُحرق داخل الملف. المسار
 * يُحفظ منفصلًا بـ GPX، ولحظة بدء الترميز تُثبَّت على محور elapsedRealtime، فتُعاد
 * محاذاة الطبقة على الفيديو لاحقًا بلا تخمين. حرق الطبقة يأتي في v0.2.
 *
 * زرّ اللقطة يلتقط الشاشة كما هي — الكاميرا والطبقة معًا — فيعطي صورةً محروقة
 * الآن، ريثما يجيء الحرق في الفيديو.
 */
@Composable
fun CameraScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val cameraMessage by vm.cameraMessage.collectAsStateWithLifecycle()
    val burnOverlay by vm.burnOverlay.collectAsStateWithLifecycle()

    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var captureTick by remember { mutableIntStateOf(0) }
    var hideControls by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE يجعل المعاينة TextureView داخل شجرة العرض. في الوضع
            // الافتراضي (SurfaceView) كانت لقطة الشاشة تخرج سوداء المشهد: الطبقة
            // تُلتقط والصورة لا، لأن سطح الكاميرا يُركَّب خارج نافذة التطبيق.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        vm.camera.bind(lifecycleOwner, previewView) { failed = true }
        onDispose { vm.camera.detach() }
    }

    // رسالة صريحة عن مصير التسجيل. الصمت هنا هو ما جعل المستخدم يظنّ أن
    // شيئًا لم يُحفظ بينما كان الملف يُكتب في مجلد لا يراه.
    LaunchedEffect(cameraMessage) {
        when (val m = cameraMessage) {
            is CameraSession.Message.Saved -> {
                toast = context.getString(R.string.recording_saved, m.name)
                vm.refreshMedia()
            }

            is CameraSession.Message.Failed ->
                toast = context.getString(R.string.recording_failed, m.reason)

            null -> return@LaunchedEffect
        }
        vm.consumeCameraMessage()
        delay(3500)
        toast = null
    }

    LaunchedEffect(captureTick) {
        if (captureTick == 0) return@LaunchedEffect
        hideControls = true
        // إطاران حتى تختفي الأزرار فعلًا قبل الالتقاط
        withFrameNanos { }
        withFrameNanos { }
        captureWindow(view) { bitmap ->
            if (bitmap == null) {
                toast = context.getString(R.string.shot_failed)
                hideControls = false
            } else {
                vm.saveScreenshot(bitmap) { ok ->
                    toast = context.getString(if (ok) R.string.shot_saved else R.string.shot_failed)
                    hideControls = false
                }
            }
        }
        delay(3000)
        toast = null
    }

    Box(modifier.fillMaxSize()) {
        if (failed) {
            Text(
                text = stringResource(R.string.camera_unavailable),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        // ===== الشريط العلوي =====
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GpsDot(gnss)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BurnToggle(
                    enabled = burnOverlay,
                    locked = isRecording,
                    onToggle = { vm.setBurnOverlay(!burnOverlay) },
                )
                if (isRecording) RecordingBadge()
            }
        }

        toast?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 58.dp, start = 20.dp, end = 20.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            )
        }

        // ===== الجزء السفلي: إحصاءات ثم سرعة ثم أزرار، بلا تداخل =====
        AnimatedVisibility(
            visible = !hideControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    StatsCard(
                        distance = Fmt.distance(trip.distanceKm),
                        maxSpeed = Fmt.speed(trip.maxSpeedKmh),
                        duration = Fmt.duration(trip.elapsedMs),
                    )
                    SpeedBadge(
                        speedKmh = liveSpeed * 3.6f,
                        maxKmh = profile.gaugeMaxKmh,
                        warnKmh = profile.defaultWarnKmh,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(
                        size = 62,
                        onClick = { captureTick++ },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = stringResource(R.string.action_screenshot),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Spacer(Modifier.size(34.dp))

                    RecordButton(isRecording = isRecording, onClick = vm::toggleRecording)

                    // فراغ مكافئ لزرّ اللقطة كي يبقى زرّ التسجيل في المنتصف تمامًا
                    Spacer(Modifier.size(34.dp))
                    Spacer(Modifier.size(62.dp))
                }
            }
        }
    }
}

private fun captureWindow(view: android.view.View, onResult: (Bitmap?) -> Unit) {
    val window = (view.context as? Activity)?.window
    if (window == null || view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val rect = Rect(
        location[0],
        location[1],
        location[0] + view.width,
        location[1] + view.height,
    )
    runCatching {
        PixelCopy.request(
            window,
            rect,
            bitmap,
            { result -> onResult(if (result == PixelCopy.SUCCESS) bitmap else null) },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { onResult(null) }
}

@Composable
private fun StatsCard(distance: String, maxSpeed: String, duration: String) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OverlayStat(stringResource(R.string.stat_distance), distance, stringResource(R.string.unit_km))
        OverlayStat(stringResource(R.string.stat_max_speed), maxSpeed, stringResource(R.string.unit_kmh))
        OverlayStat(stringResource(R.string.stat_duration), duration, "")
    }
}

@Composable
private fun SpeedBadge(speedKmh: Float, maxKmh: Int, warnKmh: Int) {
    Box(
        modifier = Modifier.size(124.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompactGauge(
            speedKmh = speedKmh,
            maxKmh = maxKmh,
            warnKmh = warnKmh,
            modifier = Modifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Fmt.speed(speedKmh),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    // الخلفية فيديو متحرّك، والنص بلا ظلّ يذوب في المشهد الفاتح
                    shadow = Shadow(Color.Black, Offset(0f, 2f), blurRadius = 10f),
                ),
            )
            Text(
                text = stringResource(R.string.unit_kmh),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color.White,
                    shadow = Shadow(Color.Black, Offset(0f, 1f), blurRadius = 6f),
                ),
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (isRecording) 30.dp else 56.dp)
                .background(Danger, if (isRecording) RoundedCornerShape(7.dp) else CircleShape)
        )
    }
}

@Composable
private fun RoundButton(size: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun OverlayStat(label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(color = Color.White.copy(alpha = 0.72f)),
        )
        Text(
            text = if (unit.isEmpty()) value else "$value $unit",
            style = MaterialTheme.typography.titleMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** توگل حرق العدّاد داخل ملفّ الفيديو. يُقفَل أثناء التسجيل لأن تبديله يعيد الربط. */
@Composable
private fun BurnToggle(enabled: Boolean, locked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                if (enabled) Danger.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.45f),
                CircleShape,
            )
            .then(if (locked) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Layers,
            contentDescription = stringResource(R.string.burn_overlay),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = stringResource(if (enabled) R.string.burn_on else R.string.burn_off),
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun RecordingBadge() {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Danger, CircleShape)
        )
        Text(
            text = "REC",
            style = MaterialTheme.typography.labelLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

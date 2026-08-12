package net.gnutux.speedometer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.screens.CameraScreen
import net.gnutux.speedometer.ui.screens.DigitalScreen
import net.gnutux.speedometer.ui.screens.MediaScreen
import net.gnutux.speedometer.ui.screens.SpeedometerScreen
import net.gnutux.speedometer.ui.screens.TripsScreen
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.GtSpeedometerTheme
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.TextSecondary

private const val PAGE_COUNT = 5
private const val PAGE_CAMERA = 2

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // الرسم خلف أشرطة النظام، مع حشوٍ صريح في الواجهة. بدونه كان شريط
        // التبويبات يختفي تحت شريط الحالة وزرّ الإنهاء تحت شريط التنقّل.
        enableEdgeToEdge()
        // القياس يجري والشاشة على المقود؛ إطفاؤها يقطع متابعة الراكب
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            GtSpeedometerTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(vm: SpeedoViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { PAGE_COUNT }
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()

    var showExitDialog by remember { mutableStateOf(false) }

    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) vm.onLocationPermissionGranted()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (locationGranted) {
            vm.onLocationPermissionGranted()
        } else {
            val asked = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            locationLauncher.launch(asked.toTypedArray())
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == PAGE_CAMERA && !cameraGranted) {
            cameraLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    BackHandler(enabled = !showExitDialog) { showExitDialog = true }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        SegmentedTabs(
            selected = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (!locationGranted) {
            PermissionGate(
                body = stringResource(R.string.perm_location_body),
                onGrant = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // السحب أثناء التسجيل قد يقع سهوًا على المقود
                userScrollEnabled = !isRecording,
            ) { page ->
                val padded = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                when (page) {
                    0 -> SpeedometerScreen(vm, padded)
                    1 -> DigitalScreen(vm, padded)
                    2 -> if (cameraGranted) {
                        // الكاميرا وحدها بلا حشو: المعاينة تملأ الشاشة
                        // والأزرار تحشو نفسها من الداخل
                        CameraScreen(vm, Modifier.fillMaxSize())
                    } else {
                        PermissionGate(
                            body = stringResource(R.string.perm_camera_body),
                            onGrant = {
                                cameraLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO,
                                    )
                                )
                            },
                            modifier = padded,
                        )
                    }

                    3 -> TripsScreen(vm, padded)
                    else -> MediaScreen(vm, padded)
                }
            }
        }
    }

    if (showExitDialog) {
        ExitDialog(
            isRecording = isRecording,
            isTripActive = vm.isTripActive,
            onDismiss = { showExitDialog = false },
            onConfirm = {
                showExitDialog = false
                (context as? ComponentActivity)?.finish()
            },
        )
    }
}

@Composable
private fun ExitDialog(
    isRecording: Boolean,
    isTripActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val body = when {
        isRecording -> stringResource(R.string.exit_body_recording)
        isTripActive -> stringResource(R.string.exit_body_trip)
        else -> stringResource(R.string.exit_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exit_title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.exit_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = Surface,
    )
}

@Composable
private fun SegmentedTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles = listOf(
        stringResource(R.string.tab_gauge),
        stringResource(R.string.tab_digital),
        stringResource(R.string.tab_camera),
        stringResource(R.string.tab_trips),
        stringResource(R.string.tab_media),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(18.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            val bg by animateColorAsState(
                targetValue = if (active) Accent else Surface,
                label = "tabBg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bg, RoundedCornerShape(14.dp))
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = if (active) Bg else TextSecondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(body: String, onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.perm_location_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.copy(color = TextSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.perm_grant))
        }
    }
}

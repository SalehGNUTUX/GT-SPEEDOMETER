package net.gnutux.speedometer

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.screens.CameraScreen
import net.gnutux.speedometer.ui.screens.PIP_ASPECT_H
import net.gnutux.speedometer.ui.screens.PIP_ASPECT_W
import net.gnutux.speedometer.ui.screens.PipScreen
import net.gnutux.speedometer.ui.screens.SettingsScreen
import net.gnutux.speedometer.ui.screens.DigitalScreen
import net.gnutux.speedometer.ui.screens.MediaScreen
import net.gnutux.speedometer.ui.screens.SpeedometerScreen
import net.gnutux.speedometer.ui.screens.TripsScreen
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.GtSpeedometerTheme
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.LocalGtColors

// خمس صفحات منذ 0.3.0: أُدرجت «الرحلات» قبل «الوسائط» كي يبقى ترتيب التبويبات
// موافقًا لمسار الاستعمال: تقيس، ثم تنهي الرحلة، ثم تراجع مسارها، ثم لقطاتها.
private const val PAGE_COUNT = 5
private const val PAGE_CAMERA = 2

class MainActivity : ComponentActivity() {

    /**
     * النموذج مملوكٌ للنشاط لا للتركيب: `onUserLeaveHint` نداءٌ خارج Compose يحتاج
     * حالة الرحلة والتسجيل والتفضيل **الآن**. وهو النسخة نفسها التي كانت `viewModel()`
     * تعطيها للتركيب، فمخزن النماذج واحد.
     */
    private val vm: SpeedoViewModel by viewModels()

    /**
     * وضع النافذة المصغَّرة، مرفوعًا حالةَ تركيب. النظام لا يخبر Compose مباشرةً،
     * و`ComponentActivity` يعرضه مستمعًا؛ فيُربَط هنا مرّةً ويُقرأ في `setContent`.
     */
    private val inPipMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // الرسم خلف أشرطة النظام، مع حشوٍ صريح في الواجهة. بدونه كان شريط
        // التبويبات يختفي تحت شريط الحالة وزرّ الإنهاء تحت شريط التنقّل.
        enableEdgeToEdge()

        // المستمع لا تجاوزُ `onPictureInPictureModeChanged`: `ComponentActivity` يوزّع
        // الحدث على مشتركيه، والتجاوز يسرقه ممّن يحتاجه من المكتبات.
        // النشاط قد يُنشأ وهو منكمشٌ أصلًا (استعادةٌ بعد موت العمليّة)، ولولا هذه
        // البذرة لرُسمت واجهة التبويبات كاملةً داخل نافذةٍ بحجم علبة الثقاب
        inPipMode.value = isInPictureInPictureMode
        addOnPictureInPictureModeChangedListener { info ->
            inPipMode.value = info.isInPictureInPictureMode
        }

        setContent {
            val mode by vm.settings.themeMode.collectAsStateWithLifecycle()
            val dayStart by vm.settings.dayStartHour.collectAsStateWithLifecycle()
            val nightStart by vm.settings.nightStartHour.collectAsStateWithLifecycle()
            val keepOn by vm.settings.keepScreenOn.collectAsStateWithLifecycle()

            // القياس يجري والشاشة على المقود؛ إطفاؤها يقطع متابعة الراكب. صار خيارًا
            // في 0.4.0 لأنّ من يشحن بطّاريّةً صغيرة يحتاج عكسه.
            LaunchedEffect(keepOn) {
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // الدخول التلقائيّ (أندرويد 12 فما فوق) ليس نداءً بل صفةٌ في معاملات
            // النافذة، فلا بدّ من تحديثها كلّما تبدّل الشرط. والقراءات الثلاث هنا هي
            // اشتراك التركيب، والقرار نفسه يُتَّخذ في `isPipArmed` كي لا يتفرّق الشرط
            // بين مسارَي الدخول.
            val trip by vm.tripState.collectAsStateWithLifecycle()
            val recordingSession by vm.isRecordingSession.collectAsStateWithLifecycle()
            val pipOnLeave by vm.settings.pipOnLeave.collectAsStateWithLifecycle()
            LaunchedEffect(trip.status, recordingSession, pipOnLeave) { applyPipParams() }

            GtSpeedometerTheme(
                mode = mode,
                dayStartHour = dayStart,
                nightStartHour = nightStart,
            ) {
                AppRoot(vm, inPipMode.value)
            }
        }
    }

    /**
     * مسار الدخول الأوّل: زرّ «الرئيسة» وفتح تطبيقٍ آخر يمرّان من هنا.
     *
     * ولا يكفي وحده: إيماءة الرجوع إلى الرئيسة لا تستدعيه على كثيرٍ من الأجهزة، وهي
     * عين ما يفعله من تصله مكالمة. لذلك يُضاف إليه `setAutoEnterEnabled` لا بدلًا منه:
     * الأوّل يغطّي ما قبل أندرويد 12 والأزرار، والثاني يغطّي الإيماءة بانتقالٍ ناعم.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPipArmed()) enterPipSafely()
    }

    /** الشرط الواحد: التفضيل مفعَّل، وثمّة ما يستحقّ المتابعة — رحلةٌ أو تسجيل */
    private fun isPipArmed(): Boolean =
        // `isRecordingSession` لا `isRecording`: الأوّل يُرفع لحظةَ الضغط، والثاني لا
        // يُرفع إلّا مع `VideoRecordEvent.Start` بعد مئات الملّي ثانية — ومَن يضغط ثمّ
        // يغادر فورًا كان يفوته الانكماش. وكلّ قراراتِ العمر في هذا الإصدار على الأوّل.
        vm.settings.pipOnLeave.value && (vm.isTripActive || vm.isRecordingSession.value)

    /** أجهزةٌ كثيرة (وكلّ Android Go) بلا هذه الميزة، ونداؤها عليها يرمي */
    private fun supportsPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(PIP_ASPECT_W, PIP_ASPECT_H))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            // المحتوى نصٌّ لا فيديو: التحجيم «السلس» يمطّ آخر إطارٍ أثناء التبديل
            // فيخرج الرقم مشوَّشًا لحظةً. المزج أنظف هنا.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /**
     * تحديث المعاملات وحده، بلا دخول. قبل أندرويد 12 لا معنى له: النسبة تُمرَّر لحظة
     * الدخول، ولا دخول تلقائيًّا أصلًا.
     */
    private fun applyPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!supportsPip()) return
        runCatching { setPictureInPictureParams(pipParams(autoEnter = isPipArmed())) }
    }

    /**
     * الدخول عمليّةٌ قد تفشل لأسبابٍ خارجةٍ عنّا: نشاطٌ في طور الإنهاء، أو سياسة
     * مؤسّسيّة تمنع النافذة، أو تخصيصُ مصنّعٍ يرمي `IllegalStateException`. وفشلها
     * ليس عطبًا: أسوأ ما يقع أن يغيب التطبيق كما كان يغيب قبل هذه الميزة.
     */
    private fun enterPipSafely() {
        if (!supportsPip()) return
        if (isInPictureInPictureMode || isFinishing || isDestroyed) return
        runCatching { enterPictureInPictureMode(pipParams(autoEnter = true)) }
    }
}

@Composable
private fun AppRoot(vm: SpeedoViewModel, isInPip: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { PAGE_COUNT }
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()

    var showExitDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // السمة صارت تتبدّل وقت التشغيل (النهار/الليل)، فلا يكفي ضبط النافذة في البيان:
    // أيقونات شريط الحالة وخلفيّة النافذة تتبعان اللوحة الحيّة، وإلّا رأى المستعمل
    // أيقوناتٍ بيضاء على شريطٍ أبيض عند الظهيرة، ووميضًا داكنًا عند الإقلاع.
    val palette = LocalGtColors.current
    val window = (context as? Activity)?.window
    LaunchedEffect(palette.isDark, window) {
        val w = window ?: return@LaunchedEffect
        w.setBackgroundDrawable(ColorDrawable(palette.bg.toArgb()))
        WindowCompat.getInsetsController(w, w.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

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

    // الرجوع من الإعدادات يغلقها ولا يخرج من التطبيق. ويُعطَّلان في النافذة المصغَّرة:
    // لا زرّ رجوعٍ فيها، ولو وصل حدثٌ لفتح محاورةَ خروجٍ لا تُرى ثمّ تفاجئ المستعمل
    // عند العودة إلى ملء الشاشة.
    BackHandler(enabled = !isInPip && showSettings) { showSettings = false }
    BackHandler(enabled = !isInPip && !showExitDialog && !showSettings) { showExitDialog = true }

    // النافذة المصغَّرة تستبدل الشجرة كلّها ولا تغطّيها: المسطّر والخريطة ومعاينة
    // الكاميرا تبقى تقيس وتخطّط لو تُركت مركَّبةً تحتها، وشيءٌ من ذلك لا يُرى. وما فوق
    // هذا السطر من `remember` محفوظٌ عمدًا، فتعود الصفحة الحاليّة والأذون كما كانت
    // بلا استئنافٍ من الصفر.
    if (isInPip) {
        PipScreen(vm, Modifier.fillMaxSize())
        return
    }

    // صندوقٌ صريح لا اتّكال على ترتيب الجذر الضمنيّ: طبقة الإعدادات تعلو المحتوى
    Box(Modifier.fillMaxSize().background(Bg)) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedTabs(
                selected = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                modifier = Modifier.weight(1f),
            )
            SettingsButton(onClick = { showSettings = true })
        }

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

    // الإعدادات طبقةٌ فوق كلّ شيء لا تبويبٌ سادس: التبويبات للقيادة، والضبط يُفتح ويُغلق
    if (showSettings) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            SettingsScreen(vm = vm, onClose = { showSettings = false })
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
    // نصّ المحاورة يتبدّل بحسب ما يضيع بالخروج: تسجيلٌ جارٍ أولًا لأنه الأثمن،
    // ثم رحلةٌ جارية، ثم الحالة الخاملة.
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

/** مساحة اللمس 56dp كاملة وإن كانت الأيقونة 22: القاعدة السابعة، ويدٌ بقفّاز */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.action_settings),
            tint = LocalGtColors.current.textSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
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
                    // القاعدة السادسة: ≥56 نقطة. كان الحشو وحده يعطي نحو 40،
                    // وزرّ المسنّن بجانبه 56 — فبانت الصفّة غير مستوية أيضًا.
                    .defaultMinSize(minHeight = 56.dp)
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

package net.gnutux.speedometer.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.Activity
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.DeviceTier
import net.gnutux.speedometer.core.map.DownloadState
import net.gnutux.speedometer.core.map.MapAppInfo
import net.gnutux.speedometer.core.map.MapApps
import net.gnutux.speedometer.core.map.MapDownloader
import net.gnutux.speedometer.core.map.MapFileEntry
import net.gnutux.speedometer.core.map.MapFileKind
import net.gnutux.speedometer.core.map.OfflineMapLibrary
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.map.OsmAndBridge
import net.gnutux.speedometer.core.map.OsmAndState
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AlertTone
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.settings.CameraLens
import net.gnutux.speedometer.core.settings.CameraScene
import net.gnutux.speedometer.core.settings.DualLayout
import net.gnutux.speedometer.core.settings.GaugeStyle
import net.gnutux.speedometer.core.settings.LiteMode
import net.gnutux.speedometer.core.settings.PipSize
import net.gnutux.speedometer.core.settings.ScreenOrientation
import net.gnutux.speedometer.core.settings.PipStyle
import net.gnutux.speedometer.core.settings.ThemeMode
import net.gnutux.speedometer.core.update.UpdateChecker
import net.gnutux.speedometer.core.update.UpdateState
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GaugePalette
import net.gnutux.speedometer.ui.components.VectorMapsAvailable
import net.gnutux.speedometer.ui.components.aspect
import net.gnutux.speedometer.ui.components.drawGaugeFace
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.TrackDim
import net.gnutux.speedometer.ui.theme.Warn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة الإعدادات الشاملة.
 *
 * ليست تبويبًا سادسًا: التبويبات للقيادة، والإعدادات تُفتح من المسنّن وتُغلق. صُنّفت
 * أقسامًا لأنّ قائمةً مسطّحة من خمسة عشر خيارًا لا تُقرأ على الطريق.
 *
 * كلّ خيار يحمل سطر شرحٍ تحت اسمه: من يضبط جهازه على المقود لا يفتح دليلًا.
 */
@Composable
fun SettingsScreen(vm: SpeedoViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = vm.settings

    val themeMode by s.themeMode.collectAsStateWithLifecycle()
    val dayStart by s.dayStartHour.collectAsStateWithLifecycle()
    val nightStart by s.nightStartHour.collectAsStateWithLifecycle()
    val keepScreenOn by s.keepScreenOn.collectAsStateWithLifecycle()
    val pipOnLeave by s.pipOnLeave.collectAsStateWithLifecycle()
    val burn by vm.burnOverlay.collectAsStateWithLifecycle()
    val audio by s.recordAudio.collectAsStateWithLifecycle()
    val segment by s.videoSegmentMinutes.collectAsStateWithLifecycle()
    val autoTrip by s.autoTripWithRecording.collectAsStateWithLifecycle()
    val invertTiles by s.invertMapTiles.collectAsStateWithLifecycle()
    val showMapControls by s.showMapControls.collectAsStateWithLifecycle()
    val preferOffline by s.preferOfflineMaps.collectAsStateWithLifecycle()
    val undoSeconds by s.undoSeconds.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val speedLimit by s.speedLimitKmh.collectAsStateWithLifecycle()
    val speedAlert by s.speedAlertEnabled.collectAsStateWithLifecycle()
    val alertTone by s.alertTone.collectAsStateWithLifecycle()
    val alertVolume by s.alertVolume.collectAsStateWithLifecycle()
    val gaugeStyle by s.gaugeStyle.collectAsStateWithLifecycle()
    val pipStyle by s.pipStyle.collectAsStateWithLifecycle()
    val pipSize by s.pipSize.collectAsStateWithLifecycle()
    val pipTransparent by s.pipTransparent.collectAsStateWithLifecycle()
    val pipOpacity by s.pipOpacity.collectAsStateWithLifecycle()
    val speedTextScale by s.speedTextScale.collectAsStateWithLifecycle()
    val orientation by s.screenOrientation.collectAsStateWithLifecycle()

    // مصدر الحقيقة نفسه الذي تقرؤه الخريطة، فلا تقول الإعدادات «وُجدت» بينما ترسم
    // الخريطة بلاطات إنترنت
    val context = LocalContext.current
    val offlineMaps = remember(context) { OfflineMaps.of(context) }
    val offlineLibrary by offlineMaps.library.collectAsStateWithLifecycle()

    // المُنزِّل نسخةٌ واحدة بعمر العمليّة، فطيُّ القسم أو الخروج من الشاشة لا يقطع نقلًا
    // جاريًا؛ والحقل محفوظٌ هنا لا داخل بطاقة القائمة الكسولة، وإلّا ضاع ما كتبه
    // المستعمل بمجرّد أن يمرّر القسم خارج الشاشة.
    val downloader = remember(context) { MapDownloader.of(context) }
    val downloadState by downloader.state.collectAsStateWithLifecycle()
    val downloadWifiOnly by s.mapDownloadWifiOnly.collectAsStateWithLifecycle()
    var downloadUrl by remember { mutableStateOf("") }

    /*
     * مُنتقي الملفّات: **مُختارٌ بين التطبيقات** لا منتقي المستندات وحده.
     *
     * كان `ACTION_OPEN_DOCUMENT` وحده، وهو لا يعرض إلّا مزوّدات `DocumentsProvider` —
     * أي منتقي النظام. وأكثر مديري الملفّات لا يُعلن نفسه مزوّدًا، فلا يظهر أصلًا؛
     * ومنتقي النظام يمنع تصفّح `Android/data` منذ أندرويد ‎11‎ فيردّ «الوصول محدود»،
     * وهناك بالضبط تسكن خرائطُ النكهة الأخرى.
     *
     * فأُضيف `ACTION_GET_CONTENT` إلى مُختارٍ واحد: يقبله كلُّ مدير ملفّاتٍ تقريبًا،
     * فيختار المستعمل الذي يبلغ ما يريد — ومن عنده مديرٌ بإذن `MANAGE_EXTERNAL_STORAGE`
     * يصل إلى ما يمنعه منتقي النظام. والاختيار يُعرض في كلّ مرّة لا يُحفَظ: مديرُ
     * الملفّات الذي يكفي اليوم قد لا يبلغ موضعَ الغد.
     *
     * و`EXTRA_INITIAL_URI` يفتحه على مجلّد خرائطنا حين يحترمه المزوّد — وهو رجاءٌ لا
     * أمر، فبعضهم يتجاهله. ومن هناك يتنقّل المستعمل كيف شاء.
     *
     * والعنوان العائد قد يكون مؤقّتًا مع `GET_CONTENT` (لا إذنَ دائمًا فيه)، وذلك
     * مقبولٌ هنا: النسخ يبدأ فورًا وينتهي قبل أن يُغلق التطبيق.
     */
    val pickMap = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) downloader.importFrom(uri)
    }

    // ————— قائمة الخرائط المحلّيّة: حالةٌ مرفوعة إلى الشاشة لا إلى بطاقتها —————
    //
    // للسبب الذي رُفع من أجله حقلُ الرابط أعلاه: البطاقة عنصرٌ في قائمةٍ كسولة،
    // فإذا مرّرها المستعمل خارج الشاشة تُتلَف تركيبتها ومعها كلُّ `remember` فيها.
    // ولو سكن هنا طلبُ الحذف لضاع الحوارُ بمجرّد تمريرةٍ بالإصبع، ولضاع سطرُ
    // «حُذف كذا» قبل أن يُقرأ.
    //
    // ونطاقُ الإجراء نطاقُ التركيب: من أغلق الإعدادات وسط الحذف يُلغى تعليقُه.
    // وهذا حدٌّ معلوم لا خطأ مستور: `File.delete` نفسها لا تُقطع في منتصفها، وأسوأ
    // ما يقع أن يُمحى الملفّ ولا تصل إعادةُ المسح — ويصلحها فتحُ الشاشة من جديد.
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<MapFileEntry?>(null) }
    var mapsNotice by remember { mutableStateOf<MapsNotice?>(null) }

    // خبرٌ عابر لا خبرٌ مقيم: هذه الشاشة لا `Scaffold` لها فلا `Snackbar` فيها،
    // وسطرٌ يبقى إلى الأبد يصير بعد دقيقةٍ كذبًا («حُذف كذا» وقد حُذف غيرُه بعده).
    // فيُمحى بعد [MAPS_NOTICE_MILLIS]، وهي مدّةٌ تكفي لقراءة سطرٍ واحد.
    LaunchedEffect(mapsNotice) {
        if (mapsNotice != null) {
            delay(MAPS_NOTICE_MILLIS)
            mapsNotice = null
        }
    }

    // ————— إسماعُ الشدّة بعد وصولها لا قبله —————
    //
    // `previewAlert` يقرأ الشدّة من التفضيلات لا من وسيط (وذلك مقصودٌ عنده: المعاينة
    // يجب أن تكون بعينها ما يُسمع على الطريق)، والكتابة إلى التفضيلات غير متزامنة.
    // فنداءُ الإسماع عقب `setAlertVolume` مباشرةً يُسمع بالشدّة **السابقة** — أي
    // يُضلّل من يعاير شدّته بالضبط في اللحظة التي يعاير فيها. فتُعلَّم النيّة هنا،
    // وتُنفَّذ حين تصل القيمة الجديدة إلى التدفّق فعلًا.
    //
    // والسالب يعني «لا نيّة»: الشدّات كلّها موجبة، فلا تلتبس قيمةٌ بغياب قيمة.
    var pendingVolumePreview by remember { mutableStateOf(NO_PENDING_PREVIEW) }
    LaunchedEffect(alertVolume) {
        if (pendingVolumePreview == alertVolume) {
            pendingVolumePreview = NO_PENDING_PREVIEW
            vm.previewAlert(alertTone)
        }
    }

    // فتحُ الإعدادات أحد الموضعين اللذين يُوقظان جسر OsmAnd؛ والآخر فتحُ رحلة.
    // ولا يُنشأ في `SpeedoApp` كي لا يوقظ عمليّة OsmAnd عند كلّ إقلاع.
    val osmAndBridge = remember(context) { OsmAndBridge.of(context) }
    val osmAnd by osmAndBridge.status.collectAsStateWithLifecycle()

    // تطبيقات الخرائط: كشفٌ عند أوّل فتحٍ للإعدادات، وهي الشاشة الوحيدة التي تعرضها
    val mapAppsSource = remember(context) { MapApps.of(context) }
    val mapAppLibrary by mapAppsSource.library.collectAsStateWithLifecycle()
    val mapAppPackage by s.mapAppPackage.collectAsStateWithLifecycle()

    // القسم المفتوح: واحدٌ لا أكثر، ومحفوظٌ فيعود المستعمل إلى حيث ترك.
    // ونقرُ الرأس المفتوح يطويه — فيصير المحفوظ فراغًا وهو حال «كلّها مطويّة».
    val openSection by s.settingsOpenSection.collectAsStateWithLifecycle()
    val toggleSection: (String) -> Unit = { id ->
        s.setSettingsOpenSection(if (openSection == id) "" else id)
    }

    val listState = rememberLazyListState()

    // تمريرٌ إلى ما فُتح لا إلى ما طُوي: من فتح «عن التطبيق» وهو آخر اثني عشر رأسًا
    // يجب أن يرى محتواه لا رأسه وحده؛ ومن طوى قسمًا لم يطلب أن تتحرّك القائمة تحته.
    // والمفتاح معرّف القسم لا فهرسه، فيقع مرّةً عند الفتح — ويقع عند وصول القيمة
    // المحفوظة بعد أوّل تركيب، وذلك مقصود: استعادة الموضع تمام استعادة الحالة.
    //
    // والفهرس هو ترتيب القسم في [SECTION_ORDER] لأنّ المفتوح واحدٌ لا أكثر: كلّ ما
    // قبله مطويّ، والمطويّ لا يُصدر إلّا رأسه، ولا شيء في القائمة يسبق أوّل رأس.
    LaunchedEffect(openSection) {
        val index = SECTION_ORDER.indexOf(openSection)
        if (index >= 0) runCatching { listState.animateScrollToItem(index) }
    }

    val cameraScene by s.cameraScene.collectAsStateWithLifecycle()
    val lens by s.cameraLens.collectAsStateWithLifecycle()
    val dual by s.dualCamera.collectAsStateWithLifecycle()
    val dualLayout by s.dualLayout.collectAsStateWithLifecycle()
    val dualPrimary by s.dualPrimary.collectAsStateWithLifecycle()
    val screenFlash by s.screenFlash.collectAsStateWithLifecycle()
    val confirmRecording by s.confirmRecording.collectAsStateWithLifecycle()
    // من الجلسة لا من التفضيلات: قدرةُ الجهاز حقيقةٌ يقولها CameraX، والتسجيلُ
    // الجاري حالةٌ لحظيّة يُقفل عليها نصف هذا القسم
    val dualSupported by vm.camera.dualSupported.collectAsStateWithLifecycle()
    val recording by vm.camera.isRecording.collectAsStateWithLifecycle()
    val liteMode by s.liteMode.collectAsStateWithLifecycle()
    val fastFix by s.fastFirstFix.collectAsStateWithLifecycle()

    // التحديث: نسخةٌ واحدة بعمر العمليّة كالمُنزِّل، فطيُّ القسم لا يقطع تنزيلًا جاريًا.
    // والفحص اليوميّ يبدأ من هنا لا من `MainActivity`: من يفتح الإعدادات جالسٌ ينظر،
    // ومن يفتح التطبيق قد يكون خلف المقود.
    val updates = remember(context) { UpdateChecker.of(context) }
    val updateState by updates.state.collectAsStateWithLifecycle()
    val installBlocked by updates.installBlocked.collectAsStateWithLifecycle()
    val updateNotify by s.updateNotify.collectAsStateWithLifecycle()
    val updateBeta by s.updateBeta.collectAsStateWithLifecycle()
    val updateLastCheck by s.updateLastCheck.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updates.maybeCheckDaily(s) }
    // رايةُ النظام ثابتةٌ لعمر الجهاز، فتُقرأ مرّةً لا مع كلّ إعادة تركيب
    val lowRam = remember(context) { DeviceTier.isLowRamDevice(context) }

    Column(modifier.fillMaxSize()) {
        SettingsHeader(onClose)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== المظهر =====
            settingsSection(
                id = SECTION_APPEARANCE,
                openId = openSection,
                title = R.string.settings_section_appearance,
                onToggle = toggleSection,
            ) {
                item(key = "appearance-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_theme_mode),
                            note = stringResource(themeMode.summary),
                        )
                        ChoiceRow(
                            options = ThemeMode.entries.map { stringResource(it.label) },
                            selectedIndex = ThemeMode.entries.indexOf(themeMode),
                            onSelect = { s.setThemeMode(ThemeMode.entries[it]) },
                        )
                    }
                }
                // ساعتا التبديل لا معنى لهما خارج الوضع التلقائيّ، فتظهران معه وحده
                if (themeMode == ThemeMode.AUTO_TIME) {
                    item(key = "appearance-2") {
                        SettingCard {
                            RowLabel(stringResource(R.string.settings_day_start), null)
                            HourRow(selected = dayStart, onSelect = s::setDayStartHour)
                            Spacer(Modifier.height(10.dp))
                            RowLabel(stringResource(R.string.settings_night_start), null)
                            HourRow(selected = nightStart, onSelect = s::setNightStartHour)
                        }
                    }
                }
            }

            // ===== تصميم العدّاد =====
            //
            // جارُ «المظهر» لا قسمٌ في ذيل القائمة: هو مظهرٌ أيضًا، ومن جاء يبدّل
            // سمةً هو نفسه من يبدّل شكل قرصه. وقسمٌ قائمٌ بذاته لا بطاقةٌ تُلحق
            // بالمظهر، لأنّ محتواه ستّة أوجهٍ مرسومة وأربعةُ إعدادات للنافذة
            // المصغّرة — إلحاقُها كان يُغرق سطرَي السمة فيما لا يبحث عنه من فتحه.
            settingsSection(
                id = SECTION_GAUGE,
                openId = openSection,
                title = R.string.settings_section_gauge,
                onToggle = toggleSection,
            ) {
                item(key = "gauge-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_gauge_style),
                            note = stringResource(R.string.settings_gauge_style_note),
                        )
                        GaugeStylePicker(selected = gaugeStyle, onSelect = s::setGaugeStyle)
                        RowLabel(
                            title = stringResource(R.string.settings_speed_text),
                            note = stringResource(R.string.settings_speed_text_note),
                        )
                        ChoiceRow(
                            options = AppSettings.SPEED_TEXT_CHOICES.map {
                                stringResource(R.string.percent_value, it)
                            },
                            selectedIndex = AppSettings.SPEED_TEXT_CHOICES
                                .indexOf(speedTextScale)
                                .coerceAtLeast(0),
                            onSelect = { s.setSpeedTextScale(AppSettings.SPEED_TEXT_CHOICES[it]) },
                        )
                        RowLabel(
                            title = stringResource(R.string.settings_orientation),
                            note = stringResource(R.string.settings_orientation_note),
                        )
                        ChoiceRow(
                            options = ScreenOrientation.entries.map { orientationLabel(it) },
                            selectedIndex = ScreenOrientation.entries.indexOf(orientation)
                                .coerceAtLeast(0),
                            onSelect = { s.setScreenOrientation(ScreenOrientation.entries[it]) },
                        )
                    }
                }
                // بطاقةٌ ثانية: النافذة المصغّرة سطحٌ آخر غير الشاشة، وخلطُ إعداداتها
                // بالقرص كان يجعل «شكل» و«حجم» يبدوان صفتين للقرص نفسه
                item(key = "gauge-2") {
                    SettingCard {
                        RowLabel(title = stringResource(R.string.settings_pip_style))
                        ChoiceRow(
                            options = PipStyle.entries.map { pipStyleLabel(it) },
                            selectedIndex = PipStyle.entries.indexOf(pipStyle).coerceAtLeast(0),
                            onSelect = { s.setPipStyle(PipStyle.entries[it]) },
                        )
                        RowLabel(title = stringResource(R.string.settings_pip_size))
                        ChoiceRow(
                            options = PipSize.entries.map { pipSizeLabel(it) },
                            selectedIndex = PipSize.entries.indexOf(pipSize).coerceAtLeast(0),
                            onSelect = { s.setPipSize(PipSize.entries[it]) },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_pip_transparent),
                            note = stringResource(R.string.settings_pip_transparent_note),
                            checked = pipTransparent,
                            onChange = s::setPipTransparent,
                        )
                        // الكثافة تظهر مع الشفافيّة وحدها، كما يظهر مفتاح التنبيه مع
                        // الحدّ وحده: «كثافة الخلفيّة» على خلفيّةٍ صلبة لا تصف شيئًا،
                        // وعرضُها معطَّلةً يترك المستعمل يلمس صفًّا كاملًا فلا يتبدّل
                        // شيء — وهو أسوأ من غيابه، إذ يوهمه أنّ في التطبيق عطبًا.
                        if (pipTransparent) {
                            RowLabel(title = stringResource(R.string.settings_pip_opacity))
                            ChoiceRow(
                                options = AppSettings.PIP_OPACITY_CHOICES.map {
                                    stringResource(R.string.percent_value, it)
                                },
                                selectedIndex = AppSettings.PIP_OPACITY_CHOICES
                                    .indexOf(pipOpacity)
                                    .coerceAtLeast(0),
                                onSelect = {
                                    s.setPipOpacity(AppSettings.PIP_OPACITY_CHOICES[it])
                                },
                            )
                        }
                    }
                }
            }

            // ===== القيادة =====
            settingsSection(
                id = SECTION_DRIVING,
                openId = openSection,
                title = R.string.settings_section_driving,
                onToggle = toggleSection,
            ) {
                item(key = "driving-1") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_keep_screen_on),
                            note = stringResource(R.string.settings_keep_screen_on_note),
                            checked = keepScreenOn,
                            onChange = s::setKeepScreenOn,
                        )
                        // جارُ «إبقاء الشاشة مضاءة» عمدًا: كلاهما جوابٌ عن سؤالٍ واحد —
                        // ماذا يحدث للعداد حين تتحوّل الشاشة عنه؟
                        SwitchRow(
                            title = stringResource(R.string.settings_pip),
                            note = stringResource(R.string.settings_pip_note),
                            checked = pipOnLeave,
                            onChange = s::setPipOnLeave,
                        )
                    }
                }
                item(key = "driving-2") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_vehicle),
                            note = stringResource(R.string.settings_vehicle_note),
                        )
                        ChoiceRow(
                            options = VehicleProfile.entries.map { stringResource(it.label) },
                            selectedIndex = VehicleProfile.entries.indexOf(profile),
                            onSelect = { vm.setProfile(VehicleProfile.entries[it]) },
                        )
                    }
                }
            }

            // ===== السرعة القصوى =====
            //
            // قسمٌ قائم بذاته لا سطرٌ في «القيادة»: الحدُّ يغيّر مدى القرص وألوانه
            // وصوت التطبيق، وهو أثرٌ أوسع من أن يُخبَّأ بين مفاتيح.
            settingsSection(
                id = SECTION_LIMIT,
                openId = openSection,
                title = R.string.settings_section_limit,
                onToggle = toggleSection,
            ) {
                item(key = "limit-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_speed_limit),
                            note = stringResource(R.string.settings_speed_limit_note),
                        )
                        ChoiceRow(
                            options = AppSettings.LIMIT_CHOICES.map { limitLabel(it) },
                            // القيمة المكتوبة يدويًّا إن وافقت خيارًا أبرزت ذلك الخيار:
                            // الفهرس يُشتقّ من الحدّ المعتمَد وحده، فلا حالتان لقيمة.
                            selectedIndex = AppSettings.LIMIT_CHOICES.indexOf(speedLimit)
                                .coerceAtLeast(0),
                            onSelect = { s.setSpeedLimitKmh(AppSettings.LIMIT_CHOICES[it]) },
                        )
                        ManualLimitField(current = speedLimit, onApply = s::setSpeedLimitKmh)
                        // المفتاح يظهر مع الحدّ وحده: تنبيهٌ صوتيّ بلا حدٍّ يُنبّه على
                        // ماذا؟ وإظهاره معطَّلًا يترك المستعمل يقلبه فلا يقع شيء.
                        if (speedLimit > 0) {
                            SwitchRow(
                                title = stringResource(R.string.settings_speed_alert),
                                note = stringResource(R.string.settings_speed_alert_note),
                                checked = speedAlert,
                                onChange = s::setSpeedAlertEnabled,
                            )
                            // النغمة والشدّة تتبعان المفتاح كما يتبع المفتاحُ الحدَّ:
                            // درجةٌ ثانية من الشرط نفسه. ومعايرةُ صوتٍ مطفأ عبثٌ،
                            // وأسوأ منها أن يلمس المستعمل «استمع» فيسمع نغمةً ثمّ لا
                            // يسمع على الطريق شيئًا — فيظنّ التنبيه عاملًا وهو مطفأ.
                            if (speedAlert) {
                                RowLabel(title = stringResource(R.string.settings_alert_tone))
                                ChoiceRow(
                                    options = AlertTone.entries.map { alertToneLabel(it) },
                                    selectedIndex = AlertTone.entries.indexOf(alertTone)
                                        .coerceAtLeast(0),
                                    // الاختيار يُسمع في الحال: أسماء النغمات
                                    // («جرس»، «نبضة خفيفة») لا تنقل صوتًا، والسماع
                                    // هو الاختيار نفسه. والنغمة تُمرَّر وسيطًا إلى
                                    // المعاينة فلا تنتظر كتابةَ التفضيلات — بخلاف
                                    // الشدّة أدناه، وسببُ الفرق مبسوطٌ عند
                                    // [pendingVolumePreview].
                                    onSelect = {
                                        val tone = AlertTone.entries[it]
                                        s.setAlertTone(tone)
                                        vm.previewAlert(tone)
                                    },
                                )
                                ActionRow(
                                    label = stringResource(R.string.alert_preview),
                                    onClick = { vm.previewAlert(alertTone) },
                                )
                                RowLabel(
                                    title = stringResource(R.string.settings_alert_volume),
                                    note = stringResource(R.string.settings_alert_volume_note),
                                )
                                ChoiceRow(
                                    options = AppSettings.ALERT_VOLUME_CHOICES.map {
                                        stringResource(R.string.percent_value, it)
                                    },
                                    selectedIndex = AppSettings.ALERT_VOLUME_CHOICES
                                        .indexOf(alertVolume)
                                        .coerceAtLeast(0),
                                    // شدّةٌ تُضبط بلا أن تُسمع رقمٌ بلا معنى: ‎%40‎
                                    // لا تقول شيئًا عن مقصورةٍ بعينها. ومن أعاد لمس
                                    // الشدّة القائمة يُسمَع في الحال: لا كتابة هناك
                                    // فلا قيمة تُنتظر، ولو انتظرناها لصمت الصفّ.
                                    onSelect = {
                                        val percent = AppSettings.ALERT_VOLUME_CHOICES[it]
                                        if (percent == alertVolume) {
                                            vm.previewAlert(alertTone)
                                        } else {
                                            pendingVolumePreview = percent
                                            s.setAlertVolume(percent)
                                        }
                                    },
                                )
                                // قراءةٌ حيّة عند كلّ إعادة تركيب، بلا `remember`:
                                // المستعمل قد يرفع مستوى «المنبّه» بأزرار جهازه
                                // وشاشتُنا مفتوحة، وقيمةٌ محفوظة كانت ستُبقي التحذير
                                // بعد زوال سببه. والثمن نداءُ `AudioManager` رخيص.
                                if (vm.isAlarmStreamMuted()) {
                                    // بلون التحذير لا بلون الخطر: لا شيء عندنا عطب،
                                    // وإنّما إعدادٌ في النظام يُبطل مفعول ما ضُبط هنا.
                                    // ولا بلون الشرح الرمادي أيضًا: من لا يقرؤه لا
                                    // يُنبَّه على الطريق أصلًا.
                                    Text(
                                        text = stringResource(R.string.alert_stream_muted),
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(color = Warn),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===== الفيديو =====
            settingsSection(
                id = SECTION_VIDEO,
                openId = openSection,
                title = R.string.settings_section_video,
                onToggle = toggleSection,
            ) {
                item(key = "video-1") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.burn_overlay),
                            note = stringResource(R.string.settings_burn_note),
                            checked = burn,
                            onChange = vm::setBurnOverlay,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_audio),
                            note = stringResource(R.string.settings_audio_note),
                            checked = audio,
                            onChange = s::setRecordAudio,
                        )
                    }
                }
                item(key = "video-2") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_segment),
                            note = stringResource(R.string.settings_segment_note),
                        )
                        ChoiceRow(
                            options = AppSettings.SEGMENT_CHOICES.map { segmentLabel(it) },
                            selectedIndex = AppSettings.SEGMENT_CHOICES.indexOf(segment)
                                .coerceAtLeast(0),
                            onSelect = { s.setVideoSegmentMinutes(AppSettings.SEGMENT_CHOICES[it]) },
                        )
                    }
                }
                item(key = "video-3") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_auto_trip),
                            note = stringResource(R.string.settings_auto_trip_note),
                            checked = autoTrip,
                            onChange = s::setAutoTripWithRecording,
                        )
                    }
                }
            }

            // ===== الخريطة والسجلّ =====
            settingsSection(
                id = SECTION_MAP,
                openId = openSection,
                title = R.string.settings_section_map,
                onToggle = toggleSection,
            ) {
                item(key = "map-1") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_invert_tiles),
                            note = stringResource(R.string.settings_invert_tiles_note),
                            checked = invertTiles,
                            onChange = s::setInvertMapTiles,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_map_controls),
                            note = stringResource(R.string.settings_map_controls_note),
                            checked = showMapControls,
                            onChange = s::setShowMapControls,
                        )
                    }
                }
                item(key = "map-2") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_undo),
                            note = stringResource(R.string.settings_undo_note),
                        )
                        ChoiceRow(
                            options = UNDO_CHOICES.map { undoLabel(it) },
                            selectedIndex = UNDO_CHOICES.indexOf(undoSeconds).coerceAtLeast(0),
                            onSelect = { s.setUndoSeconds(UNDO_CHOICES[it]) },
                        )
                    }
                }
            }

            // ===== الخرائط دون اتّصال =====
            //
            // قسمٌ قائم بذاته لا سطرٌ في «الخريطة والسجلّ»: هو المكان الوحيد الذي
            // يُخبر المستعمل **أين** يضع ملفّه و**هل** رآه التطبيق. بلا هذين السطرين
            // يصير الأمر تخمينًا، وملفٌّ لا يُعثر عليه كأنّه لم يُنسخ.
            settingsSection(
                id = SECTION_OFFLINE,
                openId = openSection,
                title = R.string.settings_section_offline,
                onToggle = toggleSection,
            ) {
                item(key = "offline-1") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_offline_maps),
                            note = stringResource(R.string.settings_offline_maps_note),
                            checked = preferOffline,
                            onChange = s::setPreferOfflineMaps,
                        )
                        RowLabel(
                            title = if (offlineLibrary.hasArchives) {
                                stringResource(
                                    R.string.settings_offline_status_found,
                                    offlineLibrary.names,
                                )
                            } else {
                                stringResource(R.string.settings_offline_status_none)
                            },
                            // المسار يظهر بعد أوّل مسحٍ فقط: قراءته من القرص لا تقع على
                            // الخيط الرئيس، فحتّى تصل نُبقي السطر فارغًا بدل مسارٍ مخمَّن
                            note = offlineLibrary.folderPath
                                .takeIf { it.isNotEmpty() }
                                ?.let { stringResource(R.string.settings_offline_folder, it) },
                        )
                        // «لا خريطة محلّيّة» جوابٌ ناقص لمن نزّل خرائطه بـ OsmAnd فعلًا:
                        // الملفّات موجودة والتطبيق رآها، وإنّما صيغتها متجهيّة لا
                        // يرسمها osmdroid. بلا هذا السطر يظنّ المستعمل الفحصَ فاشلًا
                        // فيعيده أبدًا.
                        if (offlineLibrary.hasVectorOnly) {
                            RowLabel(
                                title = stringResource(
                                    R.string.map_obf_found,
                                    offlineLibrary.vectorNames,
                                ),
                            )
                        }
                        // الحال الموازية: بياناتٌ خام وحدها. من أنزل ‎*.shp.zip‎ من
                        // Geofabrik ووضعه في المجلّد صار لا يرى «وُجدت خريطة» — وهذا
                        // صوابٌ في نفسه — لكنّه بلا هذا السطر لا يُخبَر **لماذا**،
                        // فيعيد النسخ والفحص أبدًا ظنًّا أنّ التطبيق لم يره. وهو ما
                        // وقع فعلًا. والسطر يقول: رآه، ولا يرسمه، وهذه صيغته.
                        if (offlineLibrary.hasRawDataOnly) {
                            RowLabel(
                                title = stringResource(
                                    R.string.map_rawdata_found,
                                    offlineLibrary.rawDataNames,
                                ),
                            )
                        }
                        if (offlineLibrary.altFolderPath.isNotEmpty()) {
                            RowLabel(
                                title = stringResource(R.string.settings_offline_folder_alt),
                                note = offlineLibrary.altFolderPath,
                            )
                        }
                        ActionRow(
                            label = stringResource(R.string.settings_offline_rescan),
                            onClick = offlineMaps::rescan,
                        )
                    }
                }
                // بطاقةٌ ثانية لا أسطرٌ تُلحَق بالأولى: تلك تقول «ماذا عندك»، وهذه فعلٌ
                // يجلب شيئًا جديدًا. وخلطُهما يجعل حقل الرابط يبدو جزءًا من حالة المسح.
                item(key = "offline-2") {
                    SettingCard {
                        MapDownloadRows(
                            state = downloadState,
                            url = downloadUrl,
                            onUrlChange = {
                                downloadUrl = it
                                // نتيجةٌ سابقة فوق رابطٍ تبدّل تكذب: خطأُ الرابط القديم
                                // يزول مع أوّل حرفٍ يُكتب، كما يزول تحذير حدّ السرعة
                                downloader.clear()
                            },
                            onStart = { downloader.start(downloadUrl, downloadWifiOnly) },
                            onPick = { pickMap.launch(mapPickIntent(context)) },
                            onCancel = downloader::cancel,
                            wifiOnly = downloadWifiOnly,
                            onWifiOnlyChange = s::setMapDownloadWifiOnly,
                        )
                    }
                }
                // بطاقةٌ ثالثة لهمٍّ ثالث: الأولى «ماذا عندك» جوابًا مختصرًا، والثانية
                // «هات جديدًا»، وهذه «دبّر ما عندك». وإلحاقُ القائمة بالأولى كان
                // يُغرق سطرَ الحالة في عشرة أسماء ملفّات، وهو أوّل ما يُقرأ في القسم.
                item(key = "offline-3") {
                    SettingCard {
                        MapFilesCard(
                            library = offlineLibrary,
                            notice = mapsNotice,
                            deletable = offlineMaps::deletable,
                            onShare = { entry ->
                                // الفشل يُقال ولا يُسقط التطبيق: تفصيلُ الحالين عند
                                // [shareMapFile]
                                if (!shareMapFile(context, entry.file)) {
                                    mapsNotice = MapsNotice(
                                        text = R.string.map_file_share_failed,
                                        fileName = null,
                                        ok = false,
                                    )
                                }
                            },
                            onDelete = { entry -> pendingDelete = entry },
                        )
                    }
                }
            }

            // ===== خرائط OsmAnd =====
            //
            // قسمٌ منفصلٌ عن «الخرائط دون اتّصال» رغم قربهما: ذاك عن أرشيفِ بلاطاتٍ
            // يضعه المستعمل بيده ويرسمه تطبيقنا، وهذا عن خرائطَ متجهيّة يملكها
            // تطبيقٌ آخر ويرسمها بنفسه. خلطُهما كان يجعل «لا خريطة محلّيّة» جوابًا
            // واحدًا عن سؤالين مختلفين، وعلاجُ كلٍّ منهما غير علاج الآخر.
            settingsSection(
                id = SECTION_OSMAND,
                openId = openSection,
                title = R.string.settings_section_osmand,
                onToggle = toggleSection,
            ) {
                item(key = "osmand-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_osmand_title),
                            note = stringResource(R.string.settings_osmand_note),
                        )
                        RowLabel(
                            title = when (osmAnd.state) {
                                OsmAndState.CHECKING ->
                                    stringResource(R.string.settings_osmand_checking)
                                OsmAndState.MISSING ->
                                    stringResource(R.string.settings_osmand_missing)
                                OsmAndState.UNREACHABLE ->
                                    stringResource(R.string.settings_osmand_unreachable)
                                // «لم يأذن» ليست «لا تستجيب»: الأولى فعلٌ ينتظر المستعمل
                                // في تطبيقٍ آخر، والثانية عطبٌ لا يملك له شيئًا. وخلطهما
                                // كان يترك من عليه خطوةٌ واحدة يظنّ أنّ لا سبيل.
                                OsmAndState.DENIED ->
                                    stringResource(R.string.settings_osmand_denied)
                                OsmAndState.READY ->
                                    stringResource(R.string.settings_osmand_ready)
                            },
                            // اسم الحزمة يُعرض عند وجودها وحدها: سطرٌ فارغ تحت «غير
                            // مثبَّت» يوحي بأنّ شيئًا نقص من العرض
                            note = osmAnd.packageName
                                .takeIf { it.isNotEmpty() }
                                ?.let { stringResource(R.string.settings_osmand_installed, it) },
                        )
                        if (osmAnd.state == OsmAndState.MISSING) {
                            LinkRow(
                                title = stringResource(R.string.settings_osmand_get),
                                url = stringResource(R.string.settings_osmand_url),
                            )
                        } else {
                            ActionRow(
                                label = stringResource(R.string.settings_osmand_recheck),
                                onClick = osmAndBridge::recheck,
                            )
                        }
                    }
                }
            }

            // ===== تطبيقات الخرائط =====
            //
            // كشفٌ وتصنيفٌ واختيارُ افتراضيّ، لا وعدَ بأكثر: الرسمُ داخل شاشتنا واجهةٌ
            // لا يعرضها إلّا OsmAnd، وما عداه يفتح المسار عنده هو. والقائمة تقول عن
            // كلّ تطبيقٍ أيّهما يقدر عليه بدل أن تسوّي بينهما بكلمة «مدعوم».
            settingsSection(
                id = SECTION_MAPAPPS,
                openId = openSection,
                title = R.string.settings_section_mapapps,
                onToggle = toggleSection,
            ) {
                item(key = "mapapps-1") {
                    SettingCard {
                        Text(
                            text = stringResource(R.string.mapapps_note),
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                        )
                        // قبل تمام المسح لا يُقال شيء: «لا تطبيق خرائط» قبل أن
                        // نسأل `PackageManager` خبرٌ كاذب، وفراغٌ لجزءٍ من ثانية
                        // أهون منه.
                        if (mapAppLibrary.hasApps) {
                            // «اسألني» خيارٌ في القائمة لا غيابُ خيار: من ثبّته
                            // افتراضيًّا ثمّ ندم يحتاج طريق رجعةٍ ظاهرة.
                            MapAppRow(
                                title = stringResource(R.string.mapapps_ask),
                                note = null,
                                icon = null,
                                selected = mapAppPackage.isEmpty(),
                                onClick = { s.setMapAppPackage("") },
                            )
                            mapAppLibrary.apps.forEach { app ->
                                MapAppRow(
                                    title = app.label,
                                    note = capabilityNote(app),
                                    icon = app.icon,
                                    selected = app.packageName == mapAppPackage,
                                    // ما لا يفتح مسارًا لا يُنقر: الاختيار الذي
                                    // يُخلَف عند أوّل رحلة أسوأ من اختيارٍ لا يُتاح،
                                    // وسطرُ القدرة يقول لماذا.
                                    onClick = if (app.placesOnly) {
                                        null
                                    } else {
                                        { s.setMapAppPackage(app.packageName) }
                                    },
                                )
                            }
                            RowLabel(
                                title = stringResource(
                                    R.string.mapapps_count,
                                    Fmt.count(mapAppLibrary.apps.size),
                                ),
                            )
                        } else if (mapAppLibrary.scanned) {
                            RowLabel(title = stringResource(R.string.mapapps_none))
                        }
                        ActionRow(
                            label = stringResource(R.string.mapapps_rescan),
                            onClick = mapAppsSource::rescan,
                        )
                    }
                }
            }

            // ===== الكاميرا =====
            //
            // الوضع يُختار من شاشة الكاميرا أيضًا، وهذا الموضع للاطّلاع والضبط
            // البارد: من يعدّ جهازه قبل الركوب لا يفتح المعاينة لضبطها.
            settingsSection(
                id = SECTION_CAMERA,
                openId = openSection,
                title = R.string.settings_section_camera,
                onToggle = toggleSection,
            ) {
                item(key = "camera-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.camera_scene_title),
                            note = stringResource(R.string.camera_scene_note),
                        )
                        ChoiceRow(
                            options = listOf(
                                stringResource(R.string.camera_scene_auto),
                                stringResource(R.string.camera_scene_day),
                                stringResource(R.string.camera_scene_night),
                            ),
                            selectedIndex = CameraScene.entries.indexOf(cameraScene)
                                .coerceAtLeast(0),
                            onSelect = { s.setCameraScene(CameraScene.entries[it]) },
                        )
                        // موضعه هنا لا في «القيادة»: هو صفةٌ لزرّ التسجيل في شاشة
                        // الكاميرا، ومن يبحث عنه يبحث حيث تُضبط الكاميرا
                        SwitchRow(
                            title = stringResource(R.string.settings_confirm_record),
                            note = stringResource(R.string.settings_confirm_record_note),
                            checked = confirmRecording,
                            onChange = s::setConfirmRecording,
                        )
                    }
                }
            }

            // ===== الكاميرتان معًا =====
            //
            // الصفوف كلّها مقفلةٌ أثناء التسجيل، كما يُقفل توگل الحرق: كلٌّ منها
            // يستلزم إعادة ربطٍ تقتل جلسة الترميز، فتُلَفّ المقاطع. وقفلُها أصدق من
            // مفتاحٍ يقلبه المستعمل فيجد ملفّه انقسم بلا أن يطلب ذلك.
            settingsSection(
                id = SECTION_DUAL,
                openId = openSection,
                title = R.string.settings_section_dual,
                onToggle = toggleSection,
            ) {
                item(key = "dual-1") {
                    SettingCard {
                        RowLabel(
                            title = if (dualSupported) {
                                stringResource(R.string.camera_dual_supported)
                            } else {
                                stringResource(R.string.camera_dual_unsupported)
                            },
                            note = stringResource(R.string.camera_dual_note),
                        )
                        // بلا دعمٍ من الجهاز لا يُعرض المفتاح أصلًا: مفتاحٌ لا يفعل
                        // شيئًا أسوأ من غيابه، والسطر أعلاه قال السبب.
                        if (dualSupported) {
                            SwitchRow(
                                title = stringResource(R.string.camera_dual),
                                note = if (recording) {
                                    stringResource(R.string.camera_switch_rolls)
                                } else {
                                    stringResource(R.string.camera_dual_note)
                                },
                                checked = dual,
                                onChange = { if (!recording) s.setDualCamera(it) },
                            )
                            if (dual) {
                                RowLabel(title = stringResource(R.string.camera_dual_layout))
                                ChoiceRow(
                                    options = listOf(
                                        stringResource(R.string.camera_dual_pip),
                                        stringResource(R.string.camera_dual_split),
                                    ),
                                    selectedIndex = DualLayout.entries.indexOf(dualLayout)
                                        .coerceAtLeast(0),
                                    onSelect = {
                                        if (!recording) s.setDualLayout(DualLayout.entries[it])
                                    },
                                )
                                ChoiceRow(
                                    options = listOf(
                                        stringResource(R.string.camera_dual_primary_back),
                                        stringResource(R.string.camera_dual_primary_front),
                                    ),
                                    selectedIndex = if (dualPrimary == CameraLens.FRONT) 1 else 0,
                                    onSelect = {
                                        if (!recording) {
                                            s.setDualPrimary(
                                                if (it == 1) CameraLens.FRONT else CameraLens.BACK
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        // العدسة المفردة تبقى معروضةً في الوضعين: هي التي تعمل حين
                        // يُطفأ المزدوج، ومن يضبطها الآن لا يريد أن يعود ليضبطها بعده.
                        if (!dual) {
                            RowLabel(title = stringResource(R.string.camera_switch))
                            ChoiceRow(
                                options = listOf(
                                    stringResource(R.string.camera_lens_back),
                                    stringResource(R.string.camera_lens_front),
                                ),
                                selectedIndex = if (lens == CameraLens.FRONT) 1 else 0,
                                onSelect = {
                                    s.setCameraLens(if (it == 1) CameraLens.FRONT else CameraLens.BACK)
                                },
                            )
                        }
                        SwitchRow(
                            title = stringResource(R.string.camera_screen_flash),
                            note = stringResource(R.string.camera_screen_flash_note),
                            checked = screenFlash,
                            onChange = s::setScreenFlash,
                        )
                    }
                }
            }

            // ===== الأجهزة المحدودة =====
            settingsSection(
                id = SECTION_LOWEND,
                openId = openSection,
                title = R.string.settings_section_lowend,
                onToggle = toggleSection,
            ) {
                item(key = "lowend-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_lite_mode),
                            note = stringResource(R.string.settings_lite_mode_note),
                        )
                        ChoiceRow(
                            options = LiteMode.entries.map { liteLabel(it) },
                            selectedIndex = LiteMode.entries.indexOf(liteMode).coerceAtLeast(0),
                            onSelect = { s.setLiteMode(LiteMode.entries[it]) },
                        )
                        // «ما الذي وقع فعلًا» لا «ما الذي اخترتَه»: مع [LiteMode.AUTO]
                        // لا يعرف المستعمل أيّهما جرى، وتخفيفٌ صامتٌ يُقرأ عطبًا في
                        // الجودة لا خدمةً في السرعة.
                        RowLabel(
                            title = if (lowRam) {
                                stringResource(R.string.settings_lite_detected)
                            } else {
                                stringResource(R.string.settings_lite_manual)
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_fast_fix),
                            note = stringResource(R.string.settings_fast_fix_note),
                            checked = fastFix,
                            onChange = s::setFastFirstFix,
                        )
                    }
                }
            }

            // ===== تحديثات التطبيق =====
            //
            // قبل «عن التطبيق» لا بعده: ذاك سطرُ نسخةٍ يُقرأ، وهذا فعلٌ يُعمل.
            settingsSection(
                id = SECTION_UPDATES,
                openId = openSection,
                title = R.string.settings_section_updates,
                onToggle = toggleSection,
            ) {
                item(key = "updates-1") {
                    SettingCard {
                        UpdateRows(
                            state = updateState,
                            lastCheck = updateLastCheck,
                            installBlocked = installBlocked,
                            onCheck = { updates.check(s) },
                            onDownload = updates::download,
                            onInstall = updates::install,
                            onAllowInstall = updates::openInstallSettings,
                        )
                    }
                }
                item(key = "updates-2") {
                    SettingCard {
                        SwitchRow(
                            title = stringResource(R.string.update_auto),
                            note = stringResource(R.string.update_auto_note),
                            checked = updateNotify,
                            onChange = s::setUpdateNotify,
                        )
                        // الجواب القديم يُمحى مع تبدّل المرشِّح: «أنت على أحدث إصدار»
                        // محسوبةً بمفتاحٍ مطفأ تكذب بمجرّد أن يُشعَل
                        SwitchRow(
                            title = stringResource(R.string.update_beta),
                            note = stringResource(R.string.update_beta_note),
                            checked = updateBeta,
                            onChange = {
                                s.setUpdateBeta(it)
                                updates.clear()
                            },
                        )
                    }
                }
            }

            // ===== عن التطبيق =====
            settingsSection(
                id = SECTION_ABOUT,
                openId = openSection,
                title = R.string.settings_section_about,
                onToggle = toggleSection,
            ) {
                item(key = "about-1") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_version),
                            note = BuildConfig.VERSION_NAME,
                        )
                        RowLabel(
                            title = stringResource(R.string.settings_license),
                            note = stringResource(R.string.settings_osm_notice),
                        )
                        RowLabel(
                            title = stringResource(R.string.settings_about_developer),
                            note = stringResource(R.string.settings_about_developer_name),
                        )
                        LinkRow(
                            title = stringResource(R.string.settings_about_repo),
                            url = stringResource(R.string.settings_about_repo_url),
                        )
                    }
                }
                item(key = "about-2") {
                    SettingCard {
                        RowLabel(
                            title = stringResource(R.string.settings_share_app),
                            note = stringResource(R.string.settings_share_note),
                        )
                        ActionRow(
                            label = stringResource(R.string.settings_share_link),
                            onClick = {
                                if (!shareApp(context, BuildConfig.VERSION_NAME, withApk = false)) {
                                    mapsNotice = MapsNotice(R.string.settings_share_failed, null, ok = false)
                                }
                            },
                        )
                        ActionRow(
                            label = stringResource(R.string.settings_share_apk),
                            onClick = {
                                if (!shareApp(context, BuildConfig.VERSION_NAME, withApk = true)) {
                                    mapsNotice = MapsNotice(R.string.settings_share_failed, null, ok = false)
                                }
                            },
                        )
                    }
                }
            }
            item(key = "tail-spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }

    // الحوار خارج القائمة الكسولة لا داخل بطاقتها: عنصرُ القائمة يُتلَف إذا خرج عن
    // الشاشة، فلو سكن الحوارُ هناك لاختفى السؤالُ بتمريرةٍ عارضة وبقي الملفّ. وموضعه
    // بعد [Column] لا يشغل حيّزًا في التخطيط: الحوار نافذةٌ للنظام لا ابنٌ للعمود.
    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.map_file_delete_title)) },
            // الاسم والحجم في نصّ السؤال لا في عنوانه: هذه ملفّاتٌ بمئات
            // الميغابايت دُفع في تنزيلها من حزمة بياناتٍ محدودة، ومن يؤكّد المحو
            // يجب أن يرى **أيّها** و**كم** قبل أن يضغط، لا بعده.
            text = {
                Text(
                    stringResource(
                        R.string.map_file_delete_body,
                        doomed.file.name,
                        MapDownloader.formatBytes(doomed.sizeBytes),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = doomed.file
                    val name = file.name
                    pendingDelete = null
                    // لا حذف مؤجّلًا هنا بخلاف الرحلات: مهلةُ التراجع تعني إبقاء
                    // مئات الميغابايت على القرص بعد أن طلب المستعمل تفريغه، وهو
                    // نقيض ما جاء يفعل. فالسؤال قبل الفعل، والفعل قاطع.
                    scope.launch {
                        val gone = offlineMaps.delete(file)
                        mapsNotice = if (gone) {
                            MapsNotice(R.string.map_file_deleted, name, ok = true)
                        } else {
                            MapsNotice(R.string.map_file_delete_failed, null, ok = false)
                        }
                    }
                }) { Text(stringResource(R.string.map_file_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** ساعات التبديل: الفهرس هو الساعة نفسها، وعليه يعتمد القفز إلى العنصر المختار */
private val HOUR_CHOICES = (0..23).toList()
private val UNDO_CHOICES = listOf(0, 5, 10, 20, 30)

@Composable
private fun segmentLabel(minutes: Int): String =
    if (minutes == AppSettings.SEGMENT_CONTINUOUS) {
        stringResource(R.string.segment_continuous)
    } else {
        stringResource(R.string.segment_minutes, Fmt.count(minutes))
    }

@Composable
private fun undoLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.settings_undo_off)
    } else {
        stringResource(R.string.settings_undo_seconds, Fmt.count(seconds))
    }

/** «تلقائيّ» يُعرض بنصّ الوضع التلقائيّ نفسه الذي في السمة، فلا يتعلّم المستعمل اسمين لمعنًى */
@Composable
private fun liteLabel(mode: LiteMode): String = when (mode) {
    LiteMode.AUTO -> stringResource(R.string.camera_scene_auto)
    LiteMode.ON -> stringResource(R.string.lite_on)
    LiteMode.OFF -> stringResource(R.string.lite_off)
}

/** صفرٌ ليس سرعةً بل غيابُ حدٍّ، فله نصُّه لا الرقم «0 كم/س» */
@Composable
private fun limitLabel(kmh: Int): String =
    if (kmh <= AppSettings.NO_SPEED_LIMIT) {
        stringResource(R.string.settings_speed_limit_off)
    } else {
        stringResource(R.string.speed_limit_value, Fmt.count(kmh))
    }

/**
 * أسماء التعدادات الأربعة، كلٌّ في دالّةٍ على حدة بـ`when` شاملة.
 *
 * `when` بلا `else` عمدًا في الأربع: من زاد وجهًا أو نغمةً لا يُصرَّف مشروعُه حتّى
 * يسمّيه، فلا يظهر خيارٌ بلا اسم — وهو ما كان يقع لو كُتبت القوائم `map` على
 * مصفوفة نصوصٍ موازية، إذ يصمت المصرِّف ويكذب الترتيب.
 */
@Composable
private fun gaugeStyleLabel(style: GaugeStyle): String = when (style) {
    GaugeStyle.CLASSIC -> stringResource(R.string.gauge_style_classic)
    GaugeStyle.NEEDLE -> stringResource(R.string.gauge_style_needle)
    GaugeStyle.MINIMAL -> stringResource(R.string.gauge_style_minimal)
    GaugeStyle.SEGMENTS -> stringResource(R.string.gauge_style_segments)
    GaugeStyle.DUAL_RING -> stringResource(R.string.gauge_style_dual_ring)
    GaugeStyle.BAR -> stringResource(R.string.gauge_style_bar)
}

@Composable
private fun pipStyleLabel(style: PipStyle): String = when (style) {
    PipStyle.NUMBER -> stringResource(R.string.pip_style_number)
    PipStyle.RING -> stringResource(R.string.pip_style_ring)
    PipStyle.NEEDLE -> stringResource(R.string.pip_style_needle)
    PipStyle.BAR -> stringResource(R.string.pip_style_bar)
}

@Composable
private fun orientationLabel(value: ScreenOrientation): String = stringResource(
    when (value) {
        ScreenOrientation.AUTO -> R.string.settings_orientation_auto
        ScreenOrientation.PORTRAIT -> R.string.settings_orientation_portrait
        ScreenOrientation.LANDSCAPE -> R.string.settings_orientation_landscape
    }
)

@Composable
private fun pipSizeLabel(size: PipSize): String = when (size) {
    PipSize.SMALL -> stringResource(R.string.pip_size_small)
    PipSize.MEDIUM -> stringResource(R.string.pip_size_medium)
    PipSize.LARGE -> stringResource(R.string.pip_size_large)
}

@Composable
private fun alertToneLabel(tone: AlertTone): String = when (tone) {
    AlertTone.BEEP -> stringResource(R.string.alert_tone_beep)
    AlertTone.DOUBLE -> stringResource(R.string.alert_tone_double)
    AlertTone.CHIME -> stringResource(R.string.alert_tone_chime)
    AlertTone.DIGITAL -> stringResource(R.string.alert_tone_digital)
    AlertTone.SOFT -> stringResource(R.string.alert_tone_soft)
}

@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_close),
                tint = Accent,
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge.copy(
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * معرّفات الأقسام: نصٌّ ثابت لا فهرس.
 *
 * الفهرس كان يعني أنّ إدراج قسمٍ جديدٍ في الوسط يفتح عند المستعمل قسمًا غير الذي
 * تركه مفتوحًا — والقيمة محفوظةٌ في القرص فتعبر التحديثات. والنصّ يعبرها سالمًا،
 * وأسوأ ما يقع عند حذف قسمٍ أن تُطوى الأقسام كلّها.
 */
private const val SECTION_APPEARANCE = "appearance"
private const val SECTION_GAUGE = "gauge"
private const val SECTION_DRIVING = "driving"
private const val SECTION_LIMIT = "limit"
private const val SECTION_VIDEO = "video"
private const val SECTION_MAP = "map"
private const val SECTION_OFFLINE = "offline"
private const val SECTION_OSMAND = "osmand"
private const val SECTION_MAPAPPS = "mapapps"
private const val SECTION_CAMERA = "camera"
private const val SECTION_DUAL = "dual"
private const val SECTION_LOWEND = "lowend"
private const val SECTION_UPDATES = "updates"
private const val SECTION_ABOUT = "about"

/**
 * ترتيب الأقسام على الشاشة.
 *
 * يُستعمل للتمرير إلى القسم المفتوح، ويجب أن يبقى مطابقًا لترتيب النداءات في
 * [SettingsScreen]: فهرس الرأس هو موضعُه هنا ما دام المفتوح واحدًا لا أكثر.
 */
private val SECTION_ORDER = listOf(
    SECTION_APPEARANCE,
    SECTION_GAUGE,
    SECTION_DRIVING,
    SECTION_LIMIT,
    SECTION_VIDEO,
    SECTION_MAP,
    SECTION_OFFLINE,
    SECTION_OSMAND,
    SECTION_MAPAPPS,
    SECTION_CAMERA,
    SECTION_DUAL,
    SECTION_LOWEND,
    SECTION_UPDATES,
    SECTION_ABOUT,
)

/** أوسع حدٍّ يُكتب يدويًّا؛ هو سقف [AppSettings.setSpeedLimitKmh] نفسه */
private const val MAX_MANUAL_LIMIT = 300

// ===== ثوابت ما أُضيف في 0.9.4 =====

/**
 * القراءة الموضوعة في بلاطات المعاينة: ‎%62‎ من المدى.
 *
 * ليست اعتباطًا: هي دون عتبة التحذير الموضوعة ([GAUGE_PREVIEW_WARN]) فيبقى القوس
 * بلون المنطقة العاديّة، ودون علامة الحدّ ([GAUGE_PREVIEW_LIMIT]) فتبقى العلامة
 * ظاهرةً أمام رأس القوس لا مطموسةً تحته. وهي فوق نصف المدى فيدور مؤشّرُ الوجه
 * التناظريّ إلى الشقّ الأيمن حيث يُقرأ دورانُه.
 */
private const val GAUGE_PREVIEW_FRACTION = 0.62f

/** عتبة التحذير في المعاينة: بها تظهر المنطقة الباهتة في التصاميم التي ترسمها */
private const val GAUGE_PREVIEW_WARN = 0.75f

/** موضع علامة الحدّ في المعاينة؛ لا يُمرَّر سالبًا وإلّا اختفت من التصاميم الستّة */
private const val GAUGE_PREVIEW_LIMIT = 0.80f

/** عرض بلاطة المعاينة. أضيق منه يخنق اسم «مؤشّر تناظريّ» في سطرين */
private val GAUGE_TILE_WIDTH = 96.dp

/**
 * ارتفاع صندوق الوجه في البلاطة.
 *
 * مربّعٌ للأوجه المستديرة (العرض بعد الحاشية ‎80dp‎)، ويُوسَّط فيه الشريطُ العريض
 * القصير. وأقلُّ منه يُذيب الفرق بين «مقتضب» و«كلاسيكيّ» فيبطل مقصد المنتقي.
 */
private val GAUGE_TILE_FACE = 80.dp

/**
 * نوع ملفّ الخريطة عند المشاركة.
 *
 * ثنائيٌّ عامّ لا نوعٌ مدَّعًى: لا نوع MIME مسجَّلًا لـ‎.mbtiles‎ ولا لـ‎.obf‎،
 * والادّعاء يُظهر في المُختار تطبيقاتٍ لا تفتح الملفّ.
 */
private const val MAP_FILE_MIME = "application/octet-stream"

/** عمر سطر «حُذف كذا»: يكفي لقراءة سطرٍ واحد، ولا يبقى حتّى يكذب */
private const val MAPS_NOTICE_MILLIS = 4_000L

/** «لا نيّة إسماعٍ معلّقة»؛ سالبٌ لأنّ الشدّات كلّها موجبة فلا تلتبس بقيمة */
private const val NO_PENDING_PREVIEW = -1

/** حارس فيضٍ عند التحليل: ما بلغه فقد خرج عن المدى قطعًا */
private const val LIMIT_OVERFLOW_GUARD = 9_999

/**
 * قسمٌ مطويّ داخل [LazyColumn].
 *
 * **لماذا لا `AnimatedVisibility`؟** لأنّ الطيّ هنا أبسط منها وأصحّ: حين يكون
 * القسم مطويًّا لا تُصدَر عناصره أصلًا، فلا تُركّب بطاقاتٌ لا تُرى ولا يقيسها
 * التخطيط الكسول. وبطاقةٌ فيها منتقي ساعاتٍ كسول داخل مُحرّكِ ظهورٍ متداخل ثمنٌ
 * بلا مقابل.
 *
 * و[id] نصٌّ ثابت لا فهرس، و`key` في كلّ عنصرٍ كي لا تنتقل حالة عنصرٍ إلى جاره حين
 * يُطوى قسمٌ ويُفتح آخر.
 */
private fun LazyListScope.settingsSection(
    id: String,
    openId: String,
    @StringRes title: Int,
    onToggle: (String) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val expanded = openId == id
    item(key = "section-$id") {
        SectionHeader(
            title = stringResource(title),
            expanded = expanded,
            onClick = { onToggle(id) },
        )
    }
    if (expanded) content()
}

/**
 * رأس قسمٍ يُنقر.
 *
 * السهم يدور مع الحالة بدل أن تُستبدل أيقونةٌ بأخرى: الدوران يربط الحالتين في عين
 * المستعمل، والوصف يقول أيّهما سيقع عند النقر لا أيّهما قائم — فذلك ما ينفع قارئ
 * الشاشة.
 */
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settings-section-arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (expanded) SurfaceHigh else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                color = Accent,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_collapse else R.string.settings_expand
            ),
            tint = Accent,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun RowLabel(title: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) { RowLabel(title, note) }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Bg,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceHigh,
            ),
        )
    }
}

/**
 * صفّ فعلٍ بلا حالة: نصٌّ بلون التمييز داخل مساحة لمسٍ لا تقلّ عن 56dp (قاعدة 6).
 *
 * فُضّل على زرّ Material لأنّ البطاقة كلّها أسطرٌ متراصّة، وزرٌّ مؤطَّر وسطها يكسر
 * الإيقاع البصريّ للقائمة.
 *
 * و[modifier] و[tint] وسيطان بقيمتين افتراضيّتين، أُضيفا في 0.9.4 لسطر ملفّ الخريطة
 * ولم يمسّا نداءً قائمًا: هناك فعلان يقتسمان سطرًا واحدًا (مشاركة وحذف) فيحتاج
 * كلٌّ منهما `weight`، والحذفُ يحتاج لون الخطر لا لون التمييز. ومضاعفةُ المركّب
 * بنسخةٍ ثانية كانت تعني تخطيطين يتباعدان عند أوّل تعديل على أحدهما.
 */
@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Accent,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                color = tint,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * سطرٌ يفتح رابطًا في المتصفّح.
 *
 * `runCatching` ليست زينة: جهازٌ مثبَّت على المقود قد يخلو من أيّ متصفّح، وعندها
 * يرمي `startActivity` استثناء `ActivityNotFoundException` فيسقط التطبيق كلّه
 * لأنّ المستخدم لمس سطر «المستودع».
 */
@Composable
private fun LinkRow(title: String, url: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                runCatching { context.startActivity(intent) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )
            // التسطير مقصود: بلا إشارةٍ بصريّة لا يخطر ببال أحدٍ أنّ السطر يُنقر
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Accent,
                    textDecoration = TextDecoration.Underline,
                ),
            )
        }
    }
}

/**
 * صفّ اختيارٍ أفقيّ قابل للتمرير. فُضّل على قائمة منسدلة لأنّ الخيارات قليلة
 * ومرئيّة دفعةً واحدة، والقائمة المنسدلة تحتاج ضغطتين ولمسًا دقيقًا.
 */
@Composable
private fun ChoiceRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 56.dp, minWidth = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Accent else SurfaceHigh)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = if (active) Bg else TextPrimary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * منتقي وجه العدّاد: بلاطاتٌ **مرسومة** لا شراتُ أسماء.
 *
 * ## لماذا لا [ChoiceRow]؟
 * لأنّ «حلقتان» و«مقتضب» و«شرات مضيئة» أسماءٌ لا تصف شكلًا لمن لم يره. وصفُّ نصوصٍ
 * هنا يعني أن يجرّب المستعملُ الستّة واحدًا واحدًا، ويخرج في كلّ مرّة من الإعدادات
 * إلى الشاشة ثمّ يعود — أي ستّ رحلاتٍ ليختار مرّة. والبلاطة تريه ما سيصير إليه
 * قرصُه قبل أن يلمس.
 *
 * ## الرسم من مصدر الحقيقة لا من رسمٍ تقريبيّ
 * كلّ بلاطة تنادي [drawGaugeFace] نفسها التي ترسم القرص الكبير وطبقة الكاميرا
 * والنافذة المصغّرة. ولو رُسمت هنا أشكالٌ «تشبه» التصاميم لتباعدت عن أصلها بعد
 * أوّل تعديلٍ في الهندسة، فصارت المعاينة تعد بما لا يقع — وهو أسوأ من غياب
 * المعاينة رأسًا.
 *
 * ## حدودٌ صريحة للمعاينة
 * - **بلا تدريجٍ رقميّ** (`ticks = null`): على ‎80dp‎ تصير الأرقام لطخًا، والحجم
 *   المصغَّر هو نفسه سببُ إتاحة `null` في [GaugeTicks].
 * - **بقراءةٍ ثابتة موضوعة** ([GAUGE_PREVIEW_FRACTION]): قرصٌ ساكن عند الصفر يُخرج
 *   التصاميم الستّة متشابهةً — لا قوس ولا مؤشّر ولا شرات مضيئة — فلا يُقارن بينها.
 * - **بعلامة حدٍّ ظاهرة** ([GAUGE_PREVIEW_LIMIT]): هي معلومةُ السلامة في كلّ وجه،
 *   ومعاينةٌ تخفيها تُخفي أهمَّ ما يفترق فيه وجهٌ عن وجه.
 *
 * ## التمييز بالإطار لا بالتعبئة
 * [ChoiceRow] يملأ المختار بلون التمييز؛ وذلك هنا يبتلع القوسَ الذي جاءت البلاطةُ
 * لتُريه (لونه لون التمييز نفسه). فالإطارُ حول البلاطة واسمُها بلونه، وهما إشارتان
 * لا واحدة — كحلقة [HourRow] وللسبب نفسه: شاشةٌ تحت الشمس وعينٌ لا تميّز الألوان.
 */
@Composable
private fun GaugeStylePicker(selected: GaugeStyle, onSelect: (GaugeStyle) -> Unit) {
    // اللوحة هي لوحةُ [net.gnutux.speedometer.ui.components.SpeedGauge] بحذافيرها،
    // ولون القيمة الحيّة [Accent] لأنّ القراءة الموضوعة دون عتبة التحذير: منطقةٌ
    // عاديّة فلونٌ عاديّ. ولو رُسمت المعاينة برتقاليّةً أو حمراء لفهم المستعمل أنّ
    // ذلك لونُ التصميم، واللونُ إنّما هو لون السرعة في كلّ التصاميم سواء.
    val palette = GaugePalette(
        active = Accent,
        track = TrackDim,
        redZone = Danger.copy(alpha = 0.30f),
        tick = TextSecondary,
        tickLine = TextSecondary,
        limit = Danger,
    )
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GaugeStyle.entries.forEach { style ->
            val active = style == selected
            Column(
                modifier = Modifier
                    .width(GAUGE_TILE_WIDTH)
                    .clip(shape)
                    .background(SurfaceHigh)
                    .border(
                        width = if (active) 2.dp else 0.dp,
                        color = if (active) Accent else Color.Transparent,
                        shape = shape,
                    )
                    .clickable { onSelect(style) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // صندوقٌ ثابت الارتفاع للجميع، والقماشُ داخله بنسبة تصميمه: بذلك
                // يظلّ [GaugeStyle.BAR] شريطًا عريضًا قصيرًا كما هو على الشاشة —
                // ولو فُرضت عليه نسبة الأوجه المستديرة لانضغط فبدا كبسولةً سمينة
                // لا تشبه ما سيراه. وهو التخطيط نفسه الذي تبني به النافذةُ
                // المصغَّرة وجهَها (`width` ثمّ `aspectRatio`).
                //
                // وحدٌّ معلوم: الشريط يحجز في وجهه لِسانًا سفليًّا لسلّم الأرقام،
                // وهو فارغٌ هنا لأنّ المعاينة بلا تدريج — فيبدو الشريط أعلى من وسط
                // بلاطته قليلًا. تصحيحُه يقتضي إزاحةً تخالف هندسة الوجه الحقيقيّة،
                // وأن تكذب المعاينة في موضعٍ أسوأ من أن تُزاح قليلًا في بلاطة.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GAUGE_TILE_FACE),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(style.aspect)
                    ) {
                        drawGaugeFace(
                            style = style,
                            fraction = GAUGE_PREVIEW_FRACTION,
                            warnFraction = GAUGE_PREVIEW_WARN,
                            limitFraction = GAUGE_PREVIEW_LIMIT,
                            palette = palette,
                            ticks = null,
                        )
                    }
                }
                Text(
                    text = gaugeStyleLabel(style),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (active) Accent else TextPrimary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * بطاقة «ما في مجلّد الخرائط»: كلّ ما وجده المسح، وما يُفعل به.
 *
 * القائمة تُبنى مرّةً لكلّ مكتبةٍ لا مع كلّ إعادة تركيب: [OfflineMapLibrary.entries]
 * خاصّيّةٌ محسوبة (`get()`) تبني [MapFileEntry] لكلّ ملفّ، وباني السطر يقرأ
 * `File.length` — أي نداءُ نظامٍ على القرص. وCompose يعيد تركيب هذه البطاقة عند كلّ
 * تبدّلٍ في الشاشة، فقراءةٌ مباشرة كانت تعني عشرات نداءات القرص في الثانية على
 * **الخيط الرئيس**. والمفتاح هو المكتبة نفسها، فيُعاد البناء عند إعادة المسح وحدها
 * — وذلك بالضبط حين تتبدّل الحقيقة.
 *
 * و[deletable] دالّةٌ تُمرَّر لا رايةٌ في [MapFileEntry]: الحكم يقارن مسار الملفّ
 * بمسار مجلّدنا الحقيقيّ، وهو من شأن [OfflineMaps] لا من شأن بنيةٍ تُنقل. **وهو
 * نداءُ قرصٍ كذلك**: `canonicalFile` تحلّ الوصلات الرمزيّة بنداء نظام، فيُحسب
 * الحكم داخل [remember] نفسه مع بناء القائمة لا في جسم كلّ سطر. ولو حُسب عند
 * الرسم لصار حكمُ عشرة ملفّاتٍ عشرةَ نداءاتٍ في كلّ إطار.
 *
 * والمفتاح [library] وحدها ولو تبدّلت [deletable]: تلك مرجعُ دالّةٍ يُبنى من جديد
 * مع كلّ إعادة تركيب، فجعلُه مفتاحًا يُبطل الحفظ من أصله — وسلوكُها لا يتبدّل ما
 * دام مجلّدنا هو هو.
 */
@Composable
private fun MapFilesCard(
    library: OfflineMapLibrary,
    notice: MapsNotice?,
    deletable: (File) -> Boolean,
    onShare: (MapFileEntry) -> Unit,
    onDelete: (MapFileEntry) -> Unit,
) {
    val rows = remember(library) { library.entries.map { it to deletable(it.file) } }

    RowLabel(
        title = stringResource(R.string.settings_maps_list),
        note = stringResource(R.string.settings_maps_list_note),
    )
    if (rows.isEmpty()) {
        // «لا ملفّات بعد» لا قائمةٌ فارغة: فراغٌ تحت عنوانٍ يُقرأ عطبًا في العرض
        RowLabel(title = stringResource(R.string.settings_maps_list_empty))
    } else {
        rows.forEach { (entry, canDelete) ->
            // المفتاح المسار الكامل لا الاسم: ملفّان باسمٍ واحد في مجلّدين اثنين
            // (مجلّدنا ومجلّد OsmAnd) حالٌ واقعة، وحالةُ أحدهما لا تخصّ الآخر.
            key(entry.file.path) {
                MapFileRow(
                    entry = entry,
                    deletable = canDelete,
                    onShare = { onShare(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }
    if (notice != null) {
        // نصٌّ بمعامل ونصٌّ بلا معامل نداءان مختلفان، كما في نتيجة التنزيل: تمرير
        // معاملٍ إلى نصٍّ لا يقبله يُلقي استثناءً في التنسيق على بعض الأجهزة
        val name = notice.fileName
        Text(
            text = if (name != null) {
                stringResource(notice.text, name)
            } else {
                stringResource(notice.text)
            },
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (notice.ok) Accent else Danger,
            ),
        )
    }
}

/**
 * سطر ملفٍّ واحد: اسمُه، ثمّ صنفُه وحجمُه، ثمّ ما يُفعل به.
 *
 * ## سطر الصنف ولونه
 * الصنف بلون التمييز حين يكون أرشيفًا يُرسم، وبلون الشرح فيما عداه. **ولا لون
 * خطرٍ لغير المرسوم**: ملفّ ‎.obf‎ أو ‎.shp.zip‎ في المجلّد ليس عطبًا ولا خطأ من
 * صاحبه — هو ملفٌّ صالح لغير هذا المحرّك، وهذا ما يقوله السطر بالضبط. وهي المعاملة
 * نفسها التي يُعامَل بها `map_obf_found` حيثما عُرض: خبرٌ لا إنذار.
 *
 * ## لماذا يغيب الحذف عن ملفّات الغير؟
 * قراءتنا لمجلّد OsmAnd (وأمثاله) ضيافةٌ محضة: نحن ننظر فيه ولا نملكه. وزرُّ حذفٍ
 * هناك يمحو خريطةً نزّلها المستعمل في تطبيقٍ آخر ودفع في تنزيلها، من شاشةِ تطبيقٍ
 * لا علاقة له بها — وهو أذًى صامت لا يُستدرك. فيُعرض السببُ مكان الزرّ، لا زرٌّ
 * معطَّل يُلمس فلا يقع شيء.
 *
 * **والمشاركة تغيب معه للسبب الجذريّ نفسه** ولسببٍ تقنيّ يوافقه: مزوّد الملفّات
 * لا يعلن إلّا مجلّدنا (`file_paths.xml`)، فطلبُ عنوانٍ لملفٍّ خارجه يُلقي
 * `IllegalArgumentException`. فلا يُعرض فعلٌ يُعلم أنّه يفشل.
 */
@Composable
private fun MapFileRow(
    entry: MapFileEntry,
    deletable: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RowLabel(title = entry.file.name)
        Text(
            text = stringResource(
                R.string.map_file_line,
                mapKindLabel(entry.kind),
                // التنسيق نفسه الذي يعرض به المُنزِّل تقدّمه، فلا يختلف رقمان في
                // شاشةٍ واحدة على ملفٍّ واحد
                MapDownloader.formatBytes(entry.sizeBytes),
            ),
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (entry.kind == MapFileKind.ARCHIVE) Accent else TextSecondary,
            ),
        )
        if (deletable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionRow(
                    label = stringResource(R.string.map_file_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
                // بلون الخطر لا بلون التمييز: هو الفعل الوحيد في هذه الشاشة الذي
                // يمحو ما لا يُستعاد، فلا يستوي في العين مع جاره
                ActionRow(
                    label = stringResource(R.string.map_file_delete),
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    tint = Danger,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.map_file_foreign),
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            )
        }
    }
}

/** صنف الملفّ بعبارةٍ تقول ما يقع به: أيُرسم أم لا، لا اسمَ امتدادٍ مجرَّدًا */
@Composable
private fun mapKindLabel(kind: MapFileKind): String = when (kind) {
    MapFileKind.ARCHIVE -> stringResource(R.string.map_kind_archive)
    MapFileKind.PMTILES -> stringResource(R.string.map_kind_pmtiles)
    MapFileKind.VECTOR -> stringResource(R.string.map_kind_vector)
    MapFileKind.RAW_DATA -> stringResource(R.string.map_kind_rawdata)
}

/**
 * مشاركة ملفّ خريطةٍ عبر مزوّد الملفّات، بالطريق نفسه الذي تشارك به شاشةُ الرحلات
 * ملفَّ الـ GPX: عنوانٌ من [FileProvider] بالسلطة `${packageName}.files`، ثمّ
 * `ACTION_SEND` بعلَم منح القراءة داخل `createChooser`. ولا نسخة ثانية تُصدَّر —
 * الملفّ قد يبلغ مئات الميغابايت، ونسخُه لمشاركته يملأ القرص الذي جاء المستعمل
 * يفرّغه.
 *
 * ## `runCatching` مرّتين لعلّتين مختلفتين
 * الأولى على بناء العنوان: `file_paths.xml` لا يعلن إلّا `maps/` من مجلّدنا، وملفٌّ
 * خارجه يُلقي `IllegalArgumentException` — وهو استثناءٌ يقع **قبل** أيّ نيّة، فيُردّ
 * منه `false`. وواجهةُ اليوم لا تعرض المشاركة إلّا على ملفٍّ في مجلّدنا (وهو `maps/`
 * بعينه)، فالحارس اليوم للنادر: تخزينٌ خارجيّ غير مهيّأ يُخرج مسارًا لا يطابق ما
 * أُعلن. وغدًا لمن ينادي الدالّة من موضعٍ آخر بلا أن يقرأ هذا كلَّه — فيجد رسالةً
 * لا سقوطًا.
 * والثانية على `startActivity`: جهازٌ على المقود قد يخلو من أيّ تطبيقٍ يقبل
 * الإرسال، فيرمي `ActivityNotFoundException` ويُسقط التطبيق كلَّه لأنّ المستعمل
 * لمس «مشاركة» — وهو الحذر نفسه المبسوط عند [LinkRow].
 *
 * والنوع `application/octet-stream` لأنّه صادق: لا نوع MIME متّفقًا عليه لـ‎.mbtiles‎
 * ولا لـ‎.obf‎، وادّعاء `application/zip` على ‎.obf‎ يُظهر في المُختار تطبيقاتٍ لا
 * تفتحه. و«ملفّ ثنائيّ» تقبله تطبيقات النقل والتخزين كلّها، وهي المقصودة هنا.
 *
 * @return هل أُطلق المُختار فعلًا؟ و`false` تعني «قل للمستعمل إنّها تعذّرت».
 */
/**
 * مشاركة التطبيق: نصًّا وحده، أو نصًّا مع حزمة التثبيت.
 *
 * **ولماذا الملفّ خيارٌ ثانٍ لا وحيد؟** أكثرُ من يُشارك يريد رابطًا يُنقر، وحزمةٌ بحجم
 * ‎14‎ ميغابايت في محادثةٍ عبءٌ لا يُطلب. لكنّ الملفّ هو الطريق الوحيد لمن لا إنترنت
 * عنده — وهو حالٌ شائعةٌ في الطريق نفسه الذي بُني هذا التطبيق له.
 *
 * وحزمةُ التطبيق تُقرأ من `applicationInfo.sourceDir`: هي النسخة العاملة بعينها، فلا
 * تنزيلَ ولا نسخةَ ثانية تُبنى. وتُنسخ إلى المخبأ باسمٍ مفهوم لأنّ `base.apk` اسمٌ
 * يُربك من يستقبله، ولأنّ `sourceDir` خارج ما يُعلنه `file_paths.xml` أصلًا.
 *
 * @return هل أُطلق المُختار فعلًا؟ و`false` تعني «قل للمستعمل إنّها تعذّرت».
 */
private fun shareApp(context: Context, versionName: String, withApk: Boolean): Boolean {
    val text = context.getString(R.string.settings_share_text, versionName)
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
        type = "text/plain"
    }

    if (withApk) {
        val uri = runCatching {
            val source = File(context.applicationInfo.sourceDir)
            val staged = File(context.cacheDir, "share").apply { mkdirs() }
                .resolve("GT-SPEEDOMETER-$versionName.apk")
            // النسخ في كلّ مرّة لا مرّةً واحدة: تحديثُ التطبيق يُبقي الاسم نفسه، فملفٌّ
            // مخبَّأٌ من إصدارٍ سابق يُرسل قديمًا باسمٍ يقول إنّه الجديد
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
        }.getOrNull() ?: return false

        send.apply {
            // النوع الرسميّ لحزم أندرويد؛ به تظهر تطبيقات النقل والتخزين في المُختار
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    return runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.settings_share_chooser))
        )
    }.isSuccess
}

private fun shareMapFile(context: Context, file: File): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull() ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = MAP_FILE_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.map_file_share))
        )
    }.isSuccess
}

/**
 * خبرٌ عابر عن آخر فعلٍ في قائمة الخرائط.
 *
 * صنفٌ صغير لا ثلاث حالاتٍ متفرّقة: النصّ ومعامله وحكمُه يتبدّلون معًا دائمًا،
 * وتفريقُهم يفتح باب سطرٍ ناجح بلونٍ فاشل أو نصٍّ ذي معاملٍ بلا معامله.
 *
 * @param text مورد النصّ.
 * @param fileName معامل النصّ إن كان يقبله، و`null` لما لا يقبل — والفرق يُحترم عند
 *   العرض لأنّ التنسيق يُلقي استثناءً على بعض الأجهزة إن خُلط.
 * @param ok نجح الفعل؟ فيُعرض بلون التمييز لا بلون الخطر.
 */
private class MapsNotice(
    @StringRes val text: Int,
    val fileName: String?,
    val ok: Boolean,
)

/**
 * سطر تطبيق خرائطٍ مثبَّت: اختيارٌ بحالةٍ ظاهرة.
 *
 * التمييز بالخلفيّة **وبشارةٍ نصّيّة** معًا لا باللون وحده: الشاشة تُقرأ تحت الشمس،
 * ومن لا يميّز الألوان يجب أن يعرف أيّها المضبوط.
 *
 * و[onClick] بقيمة `null` تعني سطرًا يُعرض ولا يُنقر: تطبيقٌ لا يفتح مسارنا يُذكر
 * لأنّه على الجهاز، ولا يُعرض عليه اختيارٌ يُخلَف. ولا شارة افتراضيٍّ لما لا يصير
 * افتراضيًّا أصلًا.
 */
@Composable
private fun MapAppRow(
    title: String,
    note: String?,
    icon: Bitmap?,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .clip(shape)
        .background(if (selected && onClick != null) SurfaceHigh else Color.Transparent)
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            // اللفّ لا يُعيد رسم الصورة، لكنّه يخصّص كائنًا مع كلّ تركيب؛ والأيقونة
            // ثابتةٌ بين مسحين فتُلَفّ مرّةً وتُحفظ.
            val image = remember(icon) { icon.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
        Box(Modifier.weight(1f)) { RowLabel(title, note) }
        if (selected) {
            Text(
                text = stringResource(R.string.mapapps_default_badge),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/**
 * ما يقدر عليه التطبيق، بلا زيادة.
 *
 * القدرتان تُجتمعان في سطرٍ واحد حين تجتمعان (OsmAnd وحده). ومن لا يعلن إلّا
 * `geo:` يقول عنه السطر إنّه يفتح المواضع لا المسارات — وهو سببُ كون سطره غير
 * قابلٍ للنقر، فيُقرأ الحكم مع علّته في موضعٍ واحد.
 */
@Composable
private fun capabilityNote(app: MapAppInfo): String {
    val lines = mutableListOf<String>()
    if (app.canRender) lines += stringResource(R.string.mapapps_can_render)
    if (app.canOpenTrack) lines += stringResource(R.string.mapapps_can_open)
    return lines.takeIf { it.isNotEmpty() }?.joinToString("، ")
        ?: stringResource(R.string.mapapps_places_only)
}

/**
 * حدُّ سرعةٍ يُكتب بالرقم.
 *
 * ثلاثة قرارات فيه:
 *
 * — **الحقل يتبع الحدَّ المعتمَد**: بذرتُه مفتاحُها [current]، فمن لمس خيارًا من
 *   الصفّ رأى الحقل يفرغ، ومن اعتمد رقمًا خارج الصفّ رآه مكتوبًا فيه. ولا تبقى في
 *   الحقل قيمةٌ تخالف ما يعمل به التطبيق.
 *
 * — **الأرقام تُحلَّل لا تُقارَن**: لوحة المفاتيح العربيّة تُدخل ‎٤٠‎ لا ‎40‎،
 *   و`toIntOrNull` تردّ `null` عليها. و[parseLimit] تمرّ بـ`Character.digit` وهي
 *   تعرف كلّ أرقام يونيكود العشريّة — العربيّة والهنديّة والفارسيّة سواء.
 *
 * — **الخطأ يُعرض ولا يُعتمد**: ما خرج عن المدى يُترك في الحقل مع سطرٍ يقول المدى،
 *   فلا يُمحى ما كتبه المستعمل ولا يُضبط حدٌّ لم يقصده.
 */
@Composable
private fun ManualLimitField(current: Int, onApply: (Int) -> Unit) {
    var text by remember(current) {
        mutableStateOf(
            if (current in AppSettings.LIMIT_CHOICES) "" else Fmt.count(current)
        )
    }
    var outOfRange by remember(current) { mutableStateOf(false) }

    RowLabel(title = stringResource(R.string.limit_manual))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceHigh)
                .border(
                    width = if (outOfRange) 2.dp else 0.dp,
                    color = if (outOfRange) Danger else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.limit_manual_hint),
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                )
            }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    // التحذير يزول مع أوّل تعديل: تحذيرٌ يبقى على نصٍّ تبدّل يكذب
                    outOfRange = false
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleSmall.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 56.dp, minWidth = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent)
                .clickable {
                    val value = parseLimit(text)
                    if (value != null && value in 1..MAX_MANUAL_LIMIT) {
                        onApply(value)
                        outOfRange = false
                    } else {
                        outOfRange = true
                    }
                }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.limit_manual_apply),
                style = MaterialTheme.typography.titleSmall.copy(
                    color = Bg,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
    if (outOfRange) {
        Text(
            text = stringResource(R.string.limit_manual_range),
            style = MaterialTheme.typography.bodySmall.copy(color = Danger),
        )
    }
}

/**
 * تحليل عددٍ مكتوبٍ بأيّ أرقامٍ عشريّة.
 *
 * `Character.digit` تعرف الأرقام العربيّة (‎0-9‎) والهنديّة (‎٠-٩‎) والفارسيّة
 * (‎۰-۹‎) وغيرها، فلا نكتب جدول تحويلٍ بأيدينا ولا ننسى نصفه. وما ليس رقمًا يردّ
 * `null` فيُعرض المدى — لا يُعتمد شيءٌ مخمَّن.
 */
private fun parseLimit(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    var value = 0
    for (ch in trimmed) {
        val digit = Character.digit(ch, 10)
        if (digit < 0) return null
        value = value * 10 + digit
        // من كتب رقمًا طويلًا خرج عن المدى قطعًا، ومتابعة الضرب تفيض بلا فائدة
        if (value >= LIMIT_OVERFLOW_GUARD) return LIMIT_OVERFLOW_GUARD
    }
    return value
}

/**
 * منتقي الساعة.
 *
 * `LazyRow` لا `Row` مُمرَّر: التمرير الكسول وحده يملك `LazyListState` القادر على
 * القفز إلى فهرسٍ بعينه. كان الصفّ يُفتح دائمًا عند 00:00، فمن ضبط الليل على 22
 * لا يرى اختياره ولا يعرف ما هو مضبوطٌ أصلًا حتى يمرّر الصفّ كلّه.
 */
@Composable
private fun HourRow(selected: Int, onSelect: (Int) -> Unit) {
    val target = selected.coerceIn(0, HOUR_CHOICES.lastIndex)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = target)

    // القفز مشروطٌ بغياب العنصر عن الشاشة: بلا هذا الشرط يرتدّ الصفّ إلى أوّله مع
    // كلّ نقرةٍ على ساعةٍ مرئيّة. ويبقى مفيدًا حين تصل القيمة المحفوظة بعد أوّل تركيب
    LaunchedEffect(target) {
        val visible = state.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!visible) state.scrollToItem(target)
    }

    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HOUR_CHOICES.size) { index ->
            val hour = HOUR_CHOICES[index]
            val active = hour == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 56.dp)
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Accent else SurfaceHigh)
                    // حلقةٌ حول المختار زيادةً على لون التعبئة: التمييز باللون وحده
                    // يضيع على شاشةٍ تحت الشمس أو لمن لا يميّز الألوان
                    .border(
                        width = if (active) 2.dp else 0.dp,
                        color = if (active) TextPrimary else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(hour) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_hour_value, Fmt.hour(hour)),
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = if (active) Bg else TextPrimary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * أسطر تنزيل أرشيف الخرائط.
 *
 * ثلاثة قرارات تفسّر شكلها:
 *
 * — **الحقل والزرّ يختفيان أثناء النقل**: تنزيلان لا يجتمعان (المُنزِّل يرفض الثاني
 *   صامتًا)، وزرٌّ يُضغط فلا يقع شيء أسوأ من زرٍّ غائب. فما دام النقل جاريًا فالمكان
 *   للتقدّم والإلغاء وحدهما.
 *
 * — **الشريط بلا تعبئة حين يُجهل الطول**: خادمٌ بلا `Content-Length` لا يُعرف منه
 *   نصيبٌ من مئة، ونسبةٌ مخترَعة تُوهم المستعمل بقرب النهاية. فيُعرض المسار فارغًا
 *   ويقول النصّ ما نُزّل فعلًا (`mapdl_progress_unknown`).
 *
 * — **سطر الخلفيّة أثناء النقل وحده**: «أبقِ التطبيق مفتوحًا» تحذيرٌ لا معنى له قبل
 *   أن يبدأ شيء، وتكرارُه دائمًا يُعلّم العين تخطّيه فلا يُقرأ حين يهمّ.
 */
@Composable
private fun MapDownloadRows(
    state: DownloadState,
    url: String,
    onUrlChange: (String) -> Unit,
    onStart: () -> Unit,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
) {
    RowLabel(
        title = stringResource(R.string.mapdl_title),
        note = stringResource(R.string.mapdl_note),
    )
    // سطرٌ زائدٌ في النكهة الكاملة وحدها: وعدُ ‎.pmtiles‎ في نكهةٍ لا تحمل محرّكًا
    // متجهيًّا كذبٌ صريح — يُنزَّل الملفّ ثمّ لا يُرسم منه شيء
    if (VectorMapsAvailable) {
        Text(
            text = stringResource(R.string.mapdl_note_vector),
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
        )
    }

    val running = state as? DownloadState.Running
    val working = state as? DownloadState.Working
    if (running != null) {
        DownloadProgress(running)
        if (running.resumed) {
            Text(
                text = stringResource(R.string.mapdl_resuming),
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            )
        }
        ActionRow(label = stringResource(R.string.mapdl_cancel), onClick = onCancel)
        Text(
            text = stringResource(R.string.mapdl_background_note),
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
        )
    } else if (working != null) {
        // فكُّ الضغط بلا شريطٍ ولا نسبة (لا طول للناتج يُقاس عليه)، لكنّه ليس أقلّ
        // من التنزيل في الطول على أرشيفٍ كبير — فيبقى الإلغاء معروضًا، ويبقى حقل
        // الرابط غائبًا كما يغيب أثناء النقل: تنزيلان لا يجتمعان.
        Text(
            text = stringResource(working.label),
            style = MaterialTheme.typography.titleSmall.copy(
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        ActionRow(label = stringResource(R.string.mapdl_cancel), onClick = onCancel)
    } else {
        MapUrlField(url = url, onUrlChange = onUrlChange, onStart = onStart)
        // الجلب من التخزين سطرٌ ثانٍ لا زرٌّ ثالث في صفّ الرابط: الصفّ فيه حقلٌ وزرّ
        // بمساحتَي لمسٍ كاملتين، وثالثٌ يضغطهما تحت الإصبع — وهذا تطبيقٌ يُستعمل بقفّاز.
        ActionRow(label = stringResource(R.string.mapdl_pick), onClick = onPick)
        Text(
            text = stringResource(R.string.mapdl_pick_note),
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
        )
    }

    SwitchRow(
        title = stringResource(R.string.mapdl_wifi_only),
        note = stringResource(R.string.mapdl_wifi_only_note),
        checked = wifiOnly,
        onChange = onWifiOnlyChange,
    )

    when (state) {
        is DownloadState.Failed -> {
            val arg = state.arg
            Text(
                // النصّ ذو المعامل والنصّ بلا معامل نداءان مختلفان: تمرير معاملٍ إلى
                // نصٍّ لا يقبله يُلقي استثناءً في التنسيق على بعض الأجهزة
                text = if (arg != null) {
                    stringResource(state.reason, arg)
                } else {
                    stringResource(state.reason)
                },
                style = MaterialTheme.typography.bodySmall.copy(color = Danger),
            )
        }

        is DownloadState.Done -> Text(
            text = stringResource(R.string.mapdl_done, state.fileName),
            style = MaterialTheme.typography.bodySmall.copy(color = Accent),
        )

        else -> Unit
    }

    RowLabel(title = stringResource(R.string.mapdl_where))

    // **المصدر الأوّل هو ما يُنزَّل منه ملفٌّ يعمل بلا أداةٍ على الحاسوب.**
    //
    // كان الويكي أوّلًا، وهو صفحة شرحٍ لا يُنزَّل منها شيء — فيذهب إليها من يريد
    // خريطةً فيعود بلا شيء. وBBBike تعطي `pmtiles` جاهزةً لكلّ بلدٍ ومدينة، وهو
    // بالضبط ما يقرؤه محرّكنا. فصار أوّلًا، ومعه **اسم الملفّ الذي يُنزَّل** لا
    // إحالةٌ إلى جدولٍ فيه سبع صيغٍ ستٌّ منها لا تعمل — وهي التجربة التي مرّ بها
    // المستعمل فعلًا: نزّل ثلاثة ملفّاتٍ فرُدّت كلُّها.
    LinkRow(
        title = stringResource(R.string.mapdl_where_bbbike_label),
        url = stringResource(R.string.mapdl_where_bbbike),
    )
    Text(
        text = stringResource(R.string.mapdl_where_bbbike_note),
        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
    )

    LinkRow(
        title = stringResource(R.string.mapdl_where_label),
        url = stringResource(R.string.mapdl_where_url),
    )
    Text(
        text = stringResource(R.string.mapdl_where_note),
        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
    )

    // مصدرٌ ثانٍ يُذكر مع وصفٍ صادق، لا مصدرٌ يُوعَد به.
    //
    // Geofabrik أوسع مصادر بيانات OSM الحرّة وأنظمها، ولا واحد من ملفّاته يرسمه
    // هذا التطبيق: ‎.osm.pbf‎ و‎.shp.zip‎ و‎.gpkg.zip‎ بياناتٌ خام لا صور، وملفّ
    // ‎shortbread‎ وإن كان MBTiles فمحتواه مربّعاتٌ متجهيّة (MVT) وosmdroid نقطيّ
    // لا غير. فالسطر يقول ذلك صراحةً في `mapdl_where_geofabrik_note` ويحيل على
    // 0.10.0 حيث المحرّك المتجهيّ.
    //
    // ولون النصّ لون الشرح لا لون الخطأ: ليس هنا عطبٌ ولا فعلٌ فاشل، وإنّما مصدرٌ
    // نافع بحدٍّ معلوم. وحمرةُ الخطر عليه كانت ستقول للمستعمل «لا تذهب»، وليس هذا
    // المقصود — بل «اذهب وأنت تعلم ما تجد».
    LinkRow(
        title = stringResource(R.string.mapdl_where_geofabrik_label),
        url = stringResource(R.string.mapdl_where_geofabrik),
    )
    Text(
        text = stringResource(R.string.mapdl_where_geofabrik_note),
        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
    )
}

/** حقل الرابط وزرّ البدء؛ نفس تخطيط [ManualLimitField] كي لا تختلف حقول الشاشة الواحدة */
@Composable
private fun MapUrlField(url: String, onUrlChange: (String) -> Unit, onStart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (url.isEmpty()) {
                Text(
                    text = stringResource(R.string.mapdl_url_hint),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                )
            }
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                // النمط أصغر من نمط حقل الحدّ: الرابط طويل، والعنوان المقصوص لا يُتحقّق منه
                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 56.dp, minWidth = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent)
                .clickable(onClick = onStart)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.mapdl_start),
                style = MaterialTheme.typography.titleSmall.copy(
                    color = Bg,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/** شريط التقدّم وسطرُه. القيم تُنسَّق بـ[MapDownloader.formatBytes] فلا تختلف عن نصّ الخطأ. */
@Composable
private fun DownloadProgress(running: DownloadState.Running) {
    val fraction = running.fraction
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_BAR_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceHigh),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(PROGRESS_BAR_HEIGHT)
                    .background(Accent),
            )
        }
    }
    val total = running.totalBytes
    Text(
        text = if (total != null) {
            stringResource(
                R.string.mapdl_progress,
                MapDownloader.formatBytes(running.downloadedBytes),
                MapDownloader.formatBytes(total),
            )
        } else {
            stringResource(
                R.string.mapdl_progress_unknown,
                MapDownloader.formatBytes(running.downloadedBytes),
            )
        },
        style = MaterialTheme.typography.titleSmall.copy(
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** رفيعٌ عمدًا: هو خبرٌ لا عنصر تحكّم، ولا يُلمس فلا يخضع لحدّ الـ56dp */
private val PROGRESS_BAR_HEIGHT = 8.dp

/**
 * أسطر قسم التحديث: النسخة، وآخر فحص، ثمّ فعلٌ واحد يناسب الحالة.
 *
 * فعلٌ واحدٌ ظاهرٌ في كلّ لحظة عمدًا — «ابحث» أو «نزّل» أو «ثبّت» — فمن ينظر إلى
 * البطاقة يعرف خطوته التالية بلا قراءة. وأثناء الفحص يزول الزرّ ويحلّ محلّه سطرُ
 * حاله، فلا يُضغط مرّتين.
 *
 * و[installBlocked] راية منفصلة عن [UpdateState]: منعُ النظام للتثبيت لا يُلغي
 * الحزمة المنزَّلة، فيبقى زرّ «ثبّت» قائمًا ويُضاف تحته طريقُ الإذن. ومن أذِن ثمّ عاد
 * يضغط الزرّ نفسه فيمضي، بلا تنزيلٍ ثانٍ.
 */
@Composable
private fun UpdateRows(
    state: UpdateState,
    lastCheck: Long,
    installBlocked: Boolean,
    onCheck: () -> Unit,
    onDownload: (UpdateState.Available) -> Unit,
    onInstall: (File) -> Unit,
    onAllowInstall: () -> Unit,
) {
    RowLabel(
        title = stringResource(R.string.settings_version),
        note = BuildConfig.VERSION_NAME,
    )
    RowLabel(title = lastCheckLabel(lastCheck))

    if (state is UpdateState.Checking) {
        Text(
            text = stringResource(R.string.update_checking),
            style = MaterialTheme.typography.titleSmall.copy(color = TextSecondary),
        )
    } else {
        ActionRow(label = stringResource(R.string.update_check), onClick = onCheck)
    }

    when (state) {
        is UpdateState.UpToDate -> Text(
            text = stringResource(R.string.update_current, state.current),
            style = MaterialTheme.typography.bodySmall.copy(color = Accent),
        )

        is UpdateState.Available -> {
            RowLabel(
                title = stringResource(R.string.update_available, state.version),
                // الحجم تحت العنوان: من على حزمة بيانات محدودة يقرّر قبل أن يبدأ
                note = state.sizeBytes
                    .takeIf { it > 0L }
                    ?.let { MapDownloader.formatBytes(it) },
            )
            ActionRow(
                label = stringResource(R.string.update_download),
                onClick = { onDownload(state) },
            )
            // النصّ مقصوصٌ سلفًا في [UpdateChecker]؛ وسجلّ تغييرٍ فارغ لا يستحقّ عنوانًا
            if (state.notes.isNotEmpty()) {
                RowLabel(
                    title = stringResource(R.string.update_notes),
                    note = state.notes,
                )
            }
        }

        is UpdateState.Downloading -> UpdateProgress(state)

        is UpdateState.Ready -> ActionRow(
            label = stringResource(R.string.update_install),
            onClick = { onInstall(state.file) },
        )

        is UpdateState.Failed -> Text(
            text = stringResource(state.reason),
            style = MaterialTheme.typography.bodySmall.copy(color = Danger),
        )

        else -> Unit
    }

    if (installBlocked) {
        Text(
            text = stringResource(R.string.update_err_install),
            style = MaterialTheme.typography.bodySmall.copy(color = Danger),
        )
        ActionRow(
            label = stringResource(R.string.update_allow_install),
            onClick = onAllowInstall,
        )
    }
}

/** شريط تنزيل الحزمة؛ القيم بـ[MapDownloader.formatBytes] فلا يختلف رقمان في شاشةٍ واحدة */
@Composable
private fun UpdateProgress(state: UpdateState.Downloading) {
    val fraction = state.fraction
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_BAR_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceHigh),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(PROGRESS_BAR_HEIGHT)
                    .background(Accent),
            )
        }
    }
    val total = state.total
    val done = MapDownloader.formatBytes(state.bytes)
    Text(
        text = stringResource(
            R.string.update_downloading,
            if (total != null) "$done / ${MapDownloader.formatBytes(total)}" else done,
        ),
        style = MaterialTheme.typography.titleSmall.copy(
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** صفرٌ ليس تاريخًا بل غيابُ فحص، فله نصُّه لا «1970-01-01» */
@Composable
private fun lastCheckLabel(millis: Long): String =
    if (millis <= 0L) {
        stringResource(R.string.update_never)
    } else {
        stringResource(
            R.string.update_last_check,
            // التنسيق نفسه الذي تعرض به شاشة الرحلات تواريخها، وبـ[Locale.US] كسائر
            // أرقام التطبيق (قاعدة 4)
            SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(millis)),
        )
    }

/**
 * نيّةُ اختيار أرشيفٍ من التخزين: منتقي المستندات ومديرو الملفّات في مُختارٍ واحد.
 *
 * `EXTRA_INITIAL_URI` يُبنى من مجلّد خرائطنا عبر `DocumentsContract`: يُفتح المنتقي
 * هناك مباشرةً عند من يحترمه، فلا يبدأ المستعمل تصفّحه من جذر الجهاز في كلّ مرّة.
 */
private fun mapPickIntent(context: Context): Intent {
    val base = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = MAP_PICK_MIME
        putExtra(Intent.EXTRA_MIME_TYPES, MAP_PICK_MIME_TYPES)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            val folder = OfflineMaps.primaryFolder(context)
            val docs = DocumentsContract.buildDocumentUri(
                EXTERNAL_DOCS_AUTHORITY,
                "primary:${folder.absolutePath.substringAfter("/storage/emulated/0/", "")}",
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, docs)
        }
    }
    // البديل يظهر في المُختار بجانب منتقي النظام: مديرو الملفّات يُعلنون هذا لا ذاك
    val alternative = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = MAP_PICK_MIME
        putExtra(Intent.EXTRA_MIME_TYPES, MAP_PICK_MIME_TYPES)
    }
    return Intent.createChooser(base, context.getString(R.string.mapdl_pick))
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(alternative))
}

private const val MAP_PICK_MIME = "*/*"
private const val EXTERNAL_DOCS_AUTHORITY = "com.android.externalstorage.documents"

/**
 * ما يُعرَض في منتقي المستندات: **كلّ الأنواع** لا نوعًا بعينه.
 *
 * أرشيفات البلاطات (‎.mbtiles‎ و‎.gemf‎ و‎.sqlitedb‎) لا نوع MIME مسجَّلًا لها، فمزوّدات
 * التخزين تُعلنها `application/octet-stream` أو لا تُعلن شيئًا — وتصفيةٌ بالنوع كانت
 * تُخفي عن المستعمل الملفَّ الذي جاء يختاره فيظنّه مفقودًا. والحكم على الامتداد بعد
 * الاختيار في [MapDownloader] على كلّ حال، وهو الحكم الذي يُعتدّ به.
 *
 * (ولا تُكتب النجمةُ والمائلة داخل تعليقٍ كهذا: تسلسلُ إغلاقه يقع في وسطها فيُقصّ
 * التعليق ويُكسر البناء — وهو ما وقع فعلًا هنا.)
 */
private val MAP_PICK_MIME_TYPES = arrayOf("*/*")

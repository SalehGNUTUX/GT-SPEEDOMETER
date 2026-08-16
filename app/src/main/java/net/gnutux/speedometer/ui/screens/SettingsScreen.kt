package net.gnutux.speedometer.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.DeviceTier
import net.gnutux.speedometer.core.map.DownloadState
import net.gnutux.speedometer.core.map.MapAppInfo
import net.gnutux.speedometer.core.map.MapApps
import net.gnutux.speedometer.core.map.MapDownloader
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.map.OsmAndBridge
import net.gnutux.speedometer.core.map.OsmAndState
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.settings.CameraLens
import net.gnutux.speedometer.core.settings.CameraScene
import net.gnutux.speedometer.core.settings.DualLayout
import net.gnutux.speedometer.core.settings.LiteMode
import net.gnutux.speedometer.core.settings.ThemeMode
import net.gnutux.speedometer.core.update.UpdateChecker
import net.gnutux.speedometer.core.update.UpdateState
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val preferOffline by s.preferOfflineMaps.collectAsStateWithLifecycle()
    val undoSeconds by s.undoSeconds.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val speedLimit by s.speedLimitKmh.collectAsStateWithLifecycle()
    val speedAlert by s.speedAlertEnabled.collectAsStateWithLifecycle()

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
                            onCancel = downloader::cancel,
                            wifiOnly = downloadWifiOnly,
                            onWifiOnlyChange = s::setMapDownloadWifiOnly,
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
            }
            item(key = "tail-spacer") { Spacer(Modifier.height(24.dp)) }
        }
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
 */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
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
                color = Accent,
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
    onCancel: () -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
) {
    RowLabel(
        title = stringResource(R.string.mapdl_title),
        note = stringResource(R.string.mapdl_note),
    )

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

    LinkRow(
        title = stringResource(R.string.mapdl_where),
        url = stringResource(R.string.mapdl_where_url),
    )
    Text(
        text = stringResource(R.string.mapdl_where_note),
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

package net.gnutux.speedometer.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.map.OsmAndBridge
import net.gnutux.speedometer.core.map.OsmAndState
import net.gnutux.speedometer.core.profile.VehicleProfile
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.settings.ThemeMode
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary

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

    // فتحُ الإعدادات أحد الموضعين اللذين يُوقظان جسر OsmAnd؛ والآخر فتحُ رحلة.
    // ولا يُنشأ في `SpeedoApp` كي لا يوقظ عمليّة OsmAnd عند كلّ إقلاع.
    val osmAndBridge = remember(context) { OsmAndBridge.of(context) }
    val osmAnd by osmAndBridge.status.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        SettingsHeader(onClose)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== المظهر =====
            item { SectionTitle(stringResource(R.string.settings_section_appearance)) }
            item {
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
                item {
                    SettingCard {
                        RowLabel(stringResource(R.string.settings_day_start), null)
                        HourRow(selected = dayStart, onSelect = s::setDayStartHour)
                        Spacer(Modifier.height(10.dp))
                        RowLabel(stringResource(R.string.settings_night_start), null)
                        HourRow(selected = nightStart, onSelect = s::setNightStartHour)
                    }
                }
            }

            // ===== القيادة =====
            item { SectionTitle(stringResource(R.string.settings_section_driving)) }
            item {
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
            item {
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

            // ===== السرعة القصوى =====
            //
            // قسمٌ قائم بذاته لا سطرٌ في «القيادة»: الحدُّ يغيّر مدى القرص وألوانه
            // وصوت التطبيق، وهو أثرٌ أوسع من أن يُخبَّأ بين مفاتيح.
            item { SectionTitle(stringResource(R.string.settings_section_limit)) }
            item {
                SettingCard {
                    RowLabel(
                        title = stringResource(R.string.settings_speed_limit),
                        note = stringResource(R.string.settings_speed_limit_note),
                    )
                    ChoiceRow(
                        options = AppSettings.LIMIT_CHOICES.map { limitLabel(it) },
                        selectedIndex = AppSettings.LIMIT_CHOICES.indexOf(speedLimit)
                            .coerceAtLeast(0),
                        onSelect = { s.setSpeedLimitKmh(AppSettings.LIMIT_CHOICES[it]) },
                    )
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

            // ===== الفيديو =====
            item { SectionTitle(stringResource(R.string.settings_section_video)) }
            item {
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
            item {
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
            item {
                SettingCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_auto_trip),
                        note = stringResource(R.string.settings_auto_trip_note),
                        checked = autoTrip,
                        onChange = s::setAutoTripWithRecording,
                    )
                }
            }

            // ===== الخريطة والسجلّ =====
            item { SectionTitle(stringResource(R.string.settings_section_map)) }
            item {
                SettingCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_invert_tiles),
                        note = stringResource(R.string.settings_invert_tiles_note),
                        checked = invertTiles,
                        onChange = s::setInvertMapTiles,
                    )
                }
            }
            item {
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

            // ===== الخرائط دون اتّصال =====
            //
            // قسمٌ قائم بذاته لا سطرٌ في «الخريطة والسجلّ»: هو المكان الوحيد الذي
            // يُخبر المستعمل **أين** يضع ملفّه و**هل** رآه التطبيق. بلا هذين السطرين
            // يصير الأمر تخمينًا، وملفٌّ لا يُعثر عليه كأنّه لم يُنسخ.
            item { SectionTitle(stringResource(R.string.settings_section_offline)) }
            item {
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

            // ===== خرائط OsmAnd =====
            //
            // قسمٌ منفصلٌ عن «الخرائط دون اتّصال» رغم قربهما: ذاك عن أرشيفِ بلاطاتٍ
            // يضعه المستعمل بيده ويرسمه تطبيقنا، وهذا عن خرائطَ متجهيّة يملكها
            // تطبيقٌ آخر ويرسمها بنفسه. خلطُهما كان يجعل «لا خريطة محلّيّة» جوابًا
            // واحدًا عن سؤالين مختلفين، وعلاجُ كلٍّ منهما غير علاج الآخر.
            item { SectionTitle(stringResource(R.string.settings_section_osmand)) }
            item {
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

            // ===== عن التطبيق =====
            item { SectionTitle(stringResource(R.string.settings_section_about)) }
            item {
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
            item { Spacer(Modifier.height(24.dp)) }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            color = Accent,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
    )
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

package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.R
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
    val undoSeconds by s.undoSeconds.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

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
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** ساعات التبديل المعروضة: كلّ ساعة زوجيّة تكفي، والقائمة الكاملة لا تُمرَّر بقفّاز */
private val HOUR_CHOICES = (0..23 step 1).toList()
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
private fun RowLabel(title: String, note: String?) {
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

@Composable
private fun HourRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HOUR_CHOICES.forEach { hour ->
            val active = hour == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 56.dp)
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Accent else SurfaceHigh)
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

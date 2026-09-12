package net.gnutux.speedometer.ui.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.BuildConfig
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.map.MapDownloader
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.core.update.UpdateChecker
import net.gnutux.speedometer.core.update.UpdateState
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.screens.ActionRow
import net.gnutux.speedometer.ui.screens.ChoiceRow
import net.gnutux.speedometer.ui.screens.PROGRESS_BAR_HEIGHT
import net.gnutux.speedometer.ui.screens.RowLabel
import net.gnutux.speedometer.ui.screens.SettingCard
import net.gnutux.speedometer.ui.screens.SwitchRow
import net.gnutux.speedometer.ui.screens.settingsSection
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * ============================================================================
 *  واجهةُ التحديث الذاتيّ — **نكهة `libre` وحدها**
 * ============================================================================
 *
 * هذا الملفّ ونظيرُه في `app/src/play/` يعرضان **الأسماء الأربعة نفسها**، ولا
 * تعرف بهما `MainActivity` ولا `SettingsScreen` أيَّ نكهةٍ تُبنى:
 *
 * | | `libre` (هنا) | `play` |
 * |---|---|---|
 * | [updateSectionIds] | قسمٌ واحد | فارغة |
 * | [UpdateBanner] | شريطُ الخبر بحالاته الثلاث | لا يرسم شيئًا |
 * | [UpdateAutoCheck] | يبدأ الفحص الدوريّ | لا يفعل شيئًا |
 * | [updateSection] | بطاقتا القسم | لا تُصدر عنصرًا |
 *
 * **والأربعةُ هنا مشروطةٌ بـ[UpdateChecker.selfUpdateSupported]**: النسخة الحرّة
 * واحدةٌ تخدم التنزيل المباشر وF-Droid معًا، وحزمةُ F-Droid يوقّعها F-Droid — فلا
 * يُحدِّثها ما نجلبه نحن. فتُخفى الواجهة عند كلّ توقيعٍ ليس توقيعَنا، ويبقى
 * التحديث لمن يملكه. (وذلك بوّابةُ تشغيلٍ تكفي بين مسارين حرَّين؛ أمّا المتجر
 * فتخرج الشيفرة من حزمته كلِّها لأنّ مراجعته تفحص ما فيها لا ما يعمل منها.)
 *
 * **ولماذا شجرتا مصدرٍ لا رايةٌ في `BuildConfig`؟** لأنّ الرايةَ تُطفئ الواجهة
 * وتُبقي الشيفرة: يبقى `UpdateChecker` في الحزمة المرفوعة إلى بلاي، ومعه إذنُ
 * `REQUEST_INSTALL_PACKAGES` في البيان ونصوصُ «نزّل» و«ثبّت» في الموارد. وسياسة
 * بلاي تمنع أن يحدّث التطبيق نفسه بغير بلاي، ومراجعتُها تفحص ما في الحزمة لا ما
 * يعمل منه. فالحزمةُ الذاهبة إلى المتجر **خاليةٌ من هذا كلِّه أصلًا**: لا مصدرًا
 * ولا إذنًا ولا نصًّا.
 *
 * والشيفرة أدناه منقولةٌ كما هي من `MainActivity` و`SettingsScreen`: لا سطرَ
 * جديدًا في منطق التحديث، والنقلُ حدٌّ في مكان الشيفرة لا تعديلٌ لسلوكها.
 */

/** معرّف قسم التحديث في شاشة الإعدادات؛ نصٌّ ثابت كسائر معرّفات الأقسام */
internal const val SECTION_UPDATES = "updates"

/**
 * ما يُضيفه هذا الملفّ إلى ترتيب أقسام الإعدادات.
 *
 * يدخل في ترتيب الأقسام قبل «عن التطبيق»، وذلك الترتيبُ هو الذي يُمرَّر إليه
 * القسمُ المفتوح — فلو بقي القسم في القائمة ولم يُصدَر لَمرَّر التمريرُ عنصرًا
 * زائدًا فانزلق كلُّ ما بعده.
 *
 * **ودالّةٌ لا ثابت**: البوّابة تُقرأ من شهادة الحزمة الجارية، فالقسم يسقط من
 * الترتيب في النسخة التي وقّعها غيرُنا كما يسقط من الشاشة.
 */
internal fun updateSectionIds(context: Context): List<String> =
    if (UpdateChecker.selfUpdateSupported(context)) listOf(SECTION_UPDATES) else emptyList()

/**
 * شريطُ خبرِ التحديث فوق التبويبات.
 *
 * ## لماذا في الجذر لا في الإعدادات
 * الفحص كان يقع في `LaunchedEffect` داخل شاشة الإعدادات، فمن لا يفتحها لا يعلم
 * بتحديثٍ أبدًا — وهو حال أكثر المستعملين. فالفحص هنا عند الإقلاع، بمدّةٍ يضبطها
 * المستعمل، والجواب يظهر حيث هو لا حيث يجب أن يذهب.
 *
 * ## ثلاث حالاتٍ لا واحدة
 * «متوفّر» يعرض زرَّ تنزيل، و«يُنزَّل» يعرض التقدّم، و«جاهز» يعرض زرَّ تثبيت. ولو
 * جُمعت في زرٍّ واحد لَما عرف الضاغطُ عليه أينزّل أم يثبّت.
 *
 * ## و«لاحقًا» تخصّ إصدارًا بعينه
 * تُحفظ في التفضيلات باسم الإصدار لا رايةً عامّة، فلا يعود الشريط بهذا الإصدار
 * ويعود بما بعده. وبلا ذلك يصير الخبر إلحاحًا يُطفئه المستعمل من أصله.
 */
@Composable
internal fun UpdateBanner(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // من وقّعها هو الذي يحدّثها؛ ولا نُنشئ المحدِّث أصلًا حين لا يكون ذلك نحن
    if (!UpdateChecker.selfUpdateSupported(context)) return
    val updates = remember(context) { UpdateChecker.of(context) }
    val state by updates.state.collectAsStateWithLifecycle()
    val notify by vm.settings.updateNotify.collectAsStateWithLifecycle()
    val snoozed by vm.settings.updateSnoozed.collectAsStateWithLifecycle()

    // الفحص عند الإقلاع، ويُعاد عند كلّ تبدّلٍ للمفتاح: من شغّله الآن يريد جوابًا
    // الآن لا بعد المدّة القادمة
    LaunchedEffect(notify) { if (notify) updates.maybeCheckDue(vm.settings) }

    if (!notify) return
    val current = state
    val version = when (current) {
        is UpdateState.Available -> current.version
        else -> null
    }
    if (version != null && version == snoozed) return

    val body: @Composable RowScope.() -> Unit = when (current) {
        is UpdateState.Available -> {
            {
                Text(
                    text = stringResource(R.string.update_banner_available, current.version),
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.settings.setUpdateSnoozed(current.version) }) {
                    Text(stringResource(R.string.update_banner_later), color = TextSecondary)
                }
                Button(onClick = { updates.download(current) }) {
                    Text(stringResource(R.string.update_banner_get))
                }
            }
        }

        is UpdateState.Downloading -> {
            {
                Text(
                    text = stringResource(R.string.update_banner_downloading),
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                current.fraction
                    ?.let { CircularProgressIndicator(progress = { it }, color = Accent) }
                    ?: CircularProgressIndicator(color = Accent)
            }
        }

        is UpdateState.Ready -> {
            {
                Text(
                    text = stringResource(R.string.update_banner_ready),
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { updates.install(current.file) }) {
                    Text(stringResource(R.string.update_banner_install))
                }
            }
        }

        else -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = body,
    )
}

/**
 * الفحص الدوريّ من داخل شاشة الإعدادات.
 *
 * كان `LaunchedEffect` في جسم [net.gnutux.speedometer.ui.screens.SettingsScreen]،
 * ولا يجوز أن يسكن عنصرًا كسولًا: عنصرُ القسم لا يُركَّب إلّا إن فُتح القسم ورآه
 * التخطيط، فيصير الفحص معلّقًا بتمريرة إصبع. فبقي في جسم الشاشة، ودخل من هذا
 * الباب وحده.
 *
 * ومن يفتح الإعدادات جالسٌ ينظر، ومن يفتح التطبيق قد يكون خلف المقود — ولذلك
 * يُفحص من الموضعين كليهما، و`maybeCheckDue` هي التي تحكم أحانت المدّة أم لا.
 */
@Composable
internal fun UpdateAutoCheck(settings: AppSettings) {
    val context = LocalContext.current
    // ولا فحصَ صامتًا ولا إشعارَ في نسخةٍ لا نملك تحديثها: خبرٌ لا يُعمل به إزعاج
    if (!UpdateChecker.selfUpdateSupported(context)) return
    // نسخةٌ واحدة بعمر العمليّة كالمُنزِّل، فطيُّ القسم لا يقطع تنزيلًا جاريًا
    val updates = remember(context) { UpdateChecker.of(context) }
    LaunchedEffect(Unit) { updates.maybeCheckDue(settings) }
}

/**
 * قسم «تحديثات التطبيق» في شاشة الإعدادات.
 *
 * الحالةُ تُقرأ داخل العنصرين لا في جسم الشاشة: قسمٌ مطويٌّ لا يُصدر عناصره أصلًا،
 * فلا تُجمَع لأجله مجارٍ لا يقرؤها أحد.
 */
internal fun LazyListScope.updateSection(
    context: Context,
    settings: AppSettings,
    openId: String,
    onToggle: (String) -> Unit,
) {
    // الشرطُ نفسه الذي أسقط القسم من [updateSectionIds]، وإلّا بقي رأسٌ بلا ترتيب
    if (!UpdateChecker.selfUpdateSupported(context)) return
    settingsSection(
        id = SECTION_UPDATES,
        openId = openId,
        title = R.string.settings_section_updates,
        onToggle = onToggle,
    ) {
        item(key = "updates-1") {
            val context = LocalContext.current
            val updates = remember(context) { UpdateChecker.of(context) }
            val state by updates.state.collectAsStateWithLifecycle()
            val installBlocked by updates.installBlocked.collectAsStateWithLifecycle()
            val lastCheck by settings.updateLastCheck.collectAsStateWithLifecycle()
            SettingCard {
                UpdateRows(
                    state = state,
                    lastCheck = lastCheck,
                    installBlocked = installBlocked,
                    onCheck = { updates.check(settings) },
                    onDownload = updates::download,
                    onInstall = updates::install,
                    onAllowInstall = updates::openInstallSettings,
                )
            }
        }
        item(key = "updates-2") {
            val context = LocalContext.current
            val updates = remember(context) { UpdateChecker.of(context) }
            val notify by settings.updateNotify.collectAsStateWithLifecycle()
            val every by settings.updateIntervalHours.collectAsStateWithLifecycle()
            val beta by settings.updateBeta.collectAsStateWithLifecycle()
            SettingCard {
                SwitchRow(
                    title = stringResource(R.string.update_auto),
                    note = stringResource(R.string.update_auto_note),
                    checked = notify,
                    onChange = settings::setUpdateNotify,
                )
                // المدّة تُعرض ما دام الفحص مشتغلًا: خيارٌ يضبط شيئًا مطفأً
                // يُقرأ عطبًا لا خيارًا
                if (notify) {
                    RowLabel(
                        title = stringResource(R.string.settings_update_every),
                        note = stringResource(R.string.settings_update_every_note),
                    )
                    ChoiceRow(
                        options = AppSettings.UPDATE_EVERY_CHOICES.map { everyLabel(it) },
                        selectedIndex = AppSettings.UPDATE_EVERY_CHOICES
                            .indexOf(every)
                            .coerceAtLeast(0),
                        onSelect = {
                            settings.setUpdateIntervalHours(AppSettings.UPDATE_EVERY_CHOICES[it])
                        },
                    )
                }
                // الجواب القديم يُمحى مع تبدّل المرشِّح: «أنت على أحدث إصدار»
                // محسوبةً بمفتاحٍ مطفأ تكذب بمجرّد أن يُشعَل
                SwitchRow(
                    title = stringResource(R.string.update_beta),
                    note = stringResource(R.string.update_beta_note),
                    checked = beta,
                    onChange = {
                        settings.setUpdateBeta(it)
                        updates.clear()
                    },
                )
            }
        }
    }
}

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

/** «كلّ ٦ ساعة» · «كلّ ٣ أيّام» · «كلّ أسبوع» — تُقرأ بلا حسابٍ ذهنيّ */
@Composable
private fun everyLabel(hours: Int): String = when {
    hours % 168 == 0 && hours == 168 -> stringResource(R.string.settings_update_every_week)
    hours % 24 == 0 -> stringResource(R.string.settings_update_every_days, Fmt.count(hours / 24))
    else -> stringResource(R.string.settings_update_every_hours, Fmt.count(hours))
}

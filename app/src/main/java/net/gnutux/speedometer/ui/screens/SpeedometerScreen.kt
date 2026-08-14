package net.gnutux.speedometer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.components.GpsStatusBar
import net.gnutux.speedometer.ui.components.SpeedGauge
import net.gnutux.speedometer.ui.components.StatTile
import net.gnutux.speedometer.ui.components.TripControls
import net.gnutux.speedometer.ui.components.VehicleSelector

/**
 * ميزانية الارتفاع. كلّ ما تحت القرص ثابت الارتفاع، فالقرص وحده هو المتغيّر:
 * يأخذ ما يبقى بعد اقتطاع هذه القيم بدل كسرٍ من العرض. الكسر من العرض كان يعطي
 * قرصًا واحدًا في كلّ الأجهزة بلا نظرٍ إلى ارتفاعها، فتسقط البلاطات تحت الحافّة
 * ويضطرّ المستعمل إلى التمرير ليرى إحصاءات رحلته.
 */
private val GpsBandHeight = 24.dp
private val VehicleBandHeight = 68.dp
private val StatRowHeight = 80.dp
private val ItemSpacing = 8.dp
private val ScreenPadding = 16.dp

/** مجموع ما ليس قرصًا: شريط التموضع + مبدّل المركبة + صفّا البلاطات + الفواصل والحشو */
private val FixedBandHeight =
    GpsBandHeight + VehicleBandHeight + StatRowHeight * 2 + ItemSpacing * 4 + ScreenPadding

/**
 * حدّ أدنى لا ينزل عنه القرص: الرقم الكبير (96sp) هو المنتَج نفسه، ويجب أن يُقرأ
 * من مسافة ذراعٍ على المقود. قرصٌ أصغر من هذا يقصّ الرقم أو يزاحمه بالقوس، وعندئذٍ
 * يعود التمرير — وهو أهون من رقمٍ لا يُقرأ.
 */
private val MinGaugeSize = 200.dp

/** وسقفٌ أعلى: على لوحٍ عريض قرصٌ بارتفاع الشاشة كلّها تشتيتٌ لا وضوح */
private val MaxGaugeSize = 400.dp

@Composable
fun SpeedometerScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val trip by vm.tripState.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val liveSpeed by vm.liveSpeedMps.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    // المدى والعتبة والحدّ من اشتقاقٍ واحد لا من ملفّ المركبة مباشرةً: حين يضبط
    // السائق حدًّا يُعاد بناء القرص حوله، والشاشات الأربع تقرأ الجواب نفسه
    val scale by vm.speedScale.collectAsStateWithLifecycle()

    // عمودٌ خارجيّ لا يتمرّر: القرص والبلاطات وحدها تتمرّر داخل `weight(1f)`، وصفّ
    // الأزرار مثبَّت تحته خارج منطقة التمرير. قبلها كان «إنهاء» ينزلق تحت حافّة الشاشة
    // بمجرّد بدء الرحلة فيضطرّ الراكب إلى التمرير بحثًا عنه وهو سائر.
    Column(modifier = modifier) {
        // القياس هنا لا داخل التمرير: العمود المتمرِّر يُقاس بارتفاعٍ لا نهائيّ فلا
        // يعرف كم بقي من الشاشة. الصندوق فوقه يعرف، فمنه تُشتقّ ميزانية القرص.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val widthCap = minOf(maxWidth - ScreenPadding * 2, MaxGaugeSize)
            // الترتيب مقصود: يُرفع الباقي إلى الحدّ الأدنى أوّلًا ثمّ يُقصّ بالعرض،
            // فلا يخرج القرص عن الشاشة عرضًا مهما ضاقت
            val gaugeSize = minOf(maxOf(maxHeight - FixedBandHeight, MinGaugeSize), widthCap)

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ItemSpacing),
            ) {
                // القرص أوّلًا وفي أعلى الشاشة: هو ما تقع عليه العين في لمحةٍ خاطفة
                SpeedGauge(
                    speedKmh = liveSpeed * 3.6f,
                    maxKmh = scale.gaugeMaxKmh,
                    warnKmh = scale.warnKmh,
                    unitLabel = stringResource(R.string.unit_kmh),
                    modifier = Modifier
                        .padding(top = ItemSpacing)
                        .size(gaugeSize),
                    limitKmh = scale.limitKmh,
                )

                // شريط التموضع تحت القرص: كان يحتلّ شريطًا كاملًا في الأعلى قبل
                // أهمّ عنصرٍ في الشاشة، وهو خبرٌ ثانويّ لا يُقرأ إلّا عند الشكّ.
                // وموضعه هنا يملأ الفراغ تحت القرص المستدير بدل تركه بياضًا.
                GpsStatusBar(info = gnss, modifier = Modifier.fillMaxWidth())

                VehicleSelector(selected = profile, onSelect = vm::setProfile)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatTile(
                        icon = Icons.Filled.Timer,
                        label = stringResource(R.string.stat_duration),
                        value = Fmt.duration(trip.elapsedMs),
                        unit = "",
                    )
                    StatTile(
                        icon = Icons.Filled.Route,
                        label = stringResource(R.string.stat_distance),
                        value = Fmt.distance(trip.distanceKm),
                        unit = stringResource(R.string.unit_km),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = ItemSpacing),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatTile(
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        label = stringResource(R.string.stat_avg_speed),
                        value = Fmt.avg(trip.avgSpeedKmh),
                        unit = stringResource(R.string.unit_kmh),
                    )
                    StatTile(
                        icon = Icons.Filled.Speed,
                        label = stringResource(R.string.stat_max_speed),
                        value = Fmt.speed(trip.maxSpeedKmh),
                        unit = stringResource(R.string.unit_kmh),
                    )
                }
            }
        }

        TripControls(
            status = trip.status,
            onToggle = vm::toggleTrip,
            onFinish = vm::finishTrip,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        )
    }
}

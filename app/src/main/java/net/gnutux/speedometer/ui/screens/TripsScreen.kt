package net.gnutux.speedometer.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.DeviceTier
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.trip.TripTrack
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.core.map.VectorMaps
import net.gnutux.speedometer.core.settings.MapSourcePreference
import net.gnutux.speedometer.ui.components.RouteMap
import net.gnutux.speedometer.ui.components.VectorMapsAvailable
import net.gnutux.speedometer.ui.components.VectorRouteMap
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextSecondary

// أرشيف الرحلات: قائمة مسطّحة ثمّ تفصيل برحلة واحدة. لا تنقّل Navigation
// لأنّ الشاشة كلّها تبويب واحد داخل AppRoot؛ الحالة المختارة تكفي، وزرّ
// الرجوع في النظام يُلغيها عبر BackHandler.

/** ارتفاع شريط التراجع تقريبًا، يُحجز أسفل القائمة كي لا يحجب آخر بطاقة */
private val UndoBarReserve = 84.dp

/** قسم الرحلات المحفوظة: بطاقة لكلّ رحلة، ولمسةٌ تفتح تفصيلها مع الخريطة. */
@Composable
fun TripsScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val trips by vm.trips.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingTripDelete.collectAsStateWithLifecycle()
    val undoSeconds by vm.settings.undoSeconds.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<TripTrack?>(null) }

    // ملاحظة الخرائط المحلّيّة تُعلِّم مرّةً ثمّ تصمت.
    //
    // موضع الحالة هنا لا داخل `TripDetail` عن قصد: لو كانت هناك لعادت مع كلّ رحلة
    // يفتحها الراكب، وهو إلحاحٌ لا تعليم. وهي `remember` لا حفظٌ دائم لأنّ المقصود
    // «مرّةً في هذه الزيارة»: من ترك التبويب ثمّ عاد بعد أن نزّل خريطةً يستحقّ أن
    // يعرف أنّها لم تُر بعد.
    var mapNoticeUsed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshTrips() }

    // تسخين مبكّر: أوّل نداءٍ لـ `of` يُطلق مسح مجلّد الخرائط على خيط قرص. نفعله عند
    // فتح التبويب لا عند فتح الرحلة، كي يكون الجواب حاضرًا قبل أن تُطلب أوّل بلاطة.
    LaunchedEffect(Unit) { OfflineMaps.of(context) }

    // مهلة صفر تعني حذفًا فوريًّا، فلا شريط أصلًا حينها.
    val pending = pendingDelete?.takeIf { undoSeconds > 0 }

    // الشريط مثبّت أسفل الشاشة فوق المحتوى أيًّا كان: قائمة، أو حالة فراغ بعد حذف
    // آخر رحلة، أو تفصيلًا. لذلك يعلو الجميع داخل Box واحد.
    Box(modifier) {
        val content = Modifier.fillMaxSize()
        val current = selected

        if (current != null) {
            // مغادرة التفصيل تستهلك الملاحظة: رآها الراكب أو لم يرها، وقد أُتيحت له
            // مرّة، فلا تلاحقه في كلّ رحلةٍ يفتحها بعدها.
            val leave = {
                mapNoticeUsed = true
                selected = null
            }
            BackHandler(onBack = leave)
            TripDetail(
                vm = vm,
                trip = current,
                noticeVisible = !mapNoticeUsed,
                onDismissNotice = { mapNoticeUsed = true },
                onBack = leave,
                onDeleted = leave,
                modifier = content,
            )
        } else if (trips.isEmpty()) {
            Column(
                modifier = content.padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.trips_empty),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.trips_hint),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = content.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = if (pending != null) UndoBarReserve else 12.dp,
                ),
            ) {
                // المسار المطلق مفتاح مستقرّ: ملفّ الـ GPX هو هويّة الرحلة الوحيدة على القرص.
                items(trips, key = { it.file.absolutePath }) { trip ->
                    TripCard(trip) { selected = trip }
                }
            }
        }

        if (pending != null) {
            UndoDeleteBar(
                pending = pending,
                totalSeconds = undoSeconds,
                onUndo = { vm.undoTripDelete() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * شريط التراجع عن الحذف.
 *
 * لا يملك مؤقّت الحذف ولا يعرف موعده: نموذج العرض هو من يحذف فعلًا بعد المهلة،
 * والشريط يعرض ما بقي منها ويعرض الفعل. لذا العدّاد هنا عرضٌ محض، مفتاحه الرحلة
 * المعلّقة نفسها فيبدأ من جديد مع كلّ حذفٍ جديد.
 */
@Composable
private fun UndoDeleteBar(
    pending: TripTrack,
    totalSeconds: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(pending, totalSeconds) { mutableIntStateOf(totalSeconds) }

    LaunchedEffect(pending, totalSeconds) {
        remaining = totalSeconds
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .background(SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.trip_deleted),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        // الرقم وحده بلا وحدة: يتبدّل كلّ ثانية، وأيّ لفظٍ للثواني يختلّ عند الواحد والاثنين.
        Text(
            text = String.format(Locale.US, "%d", remaining),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Accent,
            ),
        )
        TextButton(
            onClick = onUndo,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.action_undo))
        }
    }
}

/** بطاقة رحلة في القائمة: التاريخ في الأعلى وأربعة أرقام في سطر واحد. */
@Composable
private fun TripCard(trip: TripTrack, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDate(trip.startMs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Icon(Icons.Filled.Map, contentDescription = null, tint = Accent)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiniStat(stringResource(R.string.stat_distance), Fmt.distance(trip.distanceKm), stringResource(R.string.unit_km))
            MiniStat(stringResource(R.string.stat_duration), Fmt.duration(trip.durationMs), "")
            MiniStat(stringResource(R.string.stat_max_speed), Fmt.speed(trip.maxSpeedKmh), stringResource(R.string.unit_kmh))
            MiniStat(stringResource(R.string.stat_avg_speed), Fmt.avg(trip.avgSpeedKmh), stringResource(R.string.unit_kmh))
        }
    }
}

/** نسخة مصغّرة من [net.gnutux.speedometer.ui.components.StatTile] بلا أيقونة: أربعة أرقام لا تتّسع لها البطاقة بالحجم الكامل. */
@Composable
private fun MiniStat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
        )
        Text(
            text = if (unit.isEmpty()) value else "$value $unit",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** تفصيل رحلة واحدة: خريطة المسار، الأرقام، ثمّ أفعال المشاركة والفتح والحذف. */
@Composable
private fun TripDetail(
    vm: SpeedoViewModel,
    trip: TripTrack,
    noticeVisible: Boolean,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val invertTiles by vm.settings.invertMapTiles.collectAsStateWithLifecycle()
    // القرار تفضيلٌ لا حالة خريطة، فيُقرأ هنا ويُمرَّر: `RouteMap` مُركّب عرضٍ لا يقرأ
    // مخزن التفضيلات بنفسه، تمامًا كما هو حال قلب الألوان.
    val preferOffline by vm.settings.preferOfflineMaps.collectAsStateWithLifecycle()
    // وتجاوز ترتيب المصادر مثلهما: يُقرأ هنا ويُكتب من زرّ الخريطة نفسها، فيبقى
    // الاختيار بعد إغلاق الرحلة.
    val mapSource by vm.settings.mapSource.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // التخفيف يقع عند الرسم لا عند القراءة: `trip.points` تبقى كاملةً للإحصاءات
    // ولعدّاد النقاط، ولا تُخفَّف إلّا النسخة التي تذهب إلى الخريطة.
    val liteMode by vm.settings.liteMode.collectAsStateWithLifecycle()
    val drawPoints = remember(trip, liteMode) {
        DeviceTier.thin(
            trip.points,
            DeviceTier.maxRoutePoints(DeviceTier.liteActive(context, liteMode)),
        )
    }

    // تطبيق الخرائط المفضَّل؛ فراغٌ يعني «اسألني في كلّ مرّة» وهو الافتراض.
    val mapApp by vm.settings.mapAppPackage.collectAsStateWithLifecycle()

    // فعلٌ واحد لموضعَي نداء: زرّ «فتح في OsmAnd»، وملاحظةُ الخريطة حين لا يملك
    // الراكب إلّا خرائط `.obf` المتجهيّة — فتلك OsmAnd وحدها ترسمها.
    val openInOsmAnd = {
        // ACTION_VIEW على نوع GPX يلتقطه OsmAnd وأمثاله.
        val uri = vm.uriForTrack(trip.file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, GPX_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // التوجيه إلى الحزمة المختارة أوّلًا، والارتداد إلى منتقي النظام إن فشل.
        // والفشل واقعٌ لا نظريّ: التفضيل يبقى في القرص بعد أن يُزال التطبيق من
        // الجهاز، أو بعد أن يُسقط مرشِّح GPX في تحديثٍ له. والإعدادات لا تعرض
        // للاختيار إلّا من يفتح مسارًا، فلا يبقى للارتداد إلّا هذا الباب.
        val direct = mapApp.isNotEmpty() && runCatching {
            context.startActivity(Intent(view).setPackage(mapApp))
        }.isSuccess
        if (!direct) {
            runCatching {
                context.startActivity(
                    Intent.createChooser(view, context.getString(R.string.trip_open_osmand))
                )
            }
        }
        Unit
    }

    Column(
        modifier = modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // الأيقونة 24dp، ومساحة اللمس لا تقلّ عن 56dp (قاعدة 6): الحجم على العقدة
            // نفسها والحشو داخلها، فتكبر المساحة دون أن تكبر الأيقونة.
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                tint = Accent,
            )
            Text(
                text = formatDate(trip.startMs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        // نقطة واحدة لا تصنع خطًّا؛ نُظهر رسالةً بدل خريطة فارغة تُوهم بعطل.
        if (trip.points.size < 2) {
            Text(
                text = stringResource(R.string.trip_no_route),
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
            )
        } else {
            // الخلفيّة والزوايا صارتا داخل RouteMap نفسه، وإلّا رسمت البلاطات
            // المربّعة فوق الاستدارة فبدا الإطار متبدّل الشكل مع كلّ تكبير.
            // المتجهيّ حين يُختار **ويوجد** أرشيفٌ صالح؛ وإلّا فالنقطيّة كما كانت.
            // شرطان لا واحد: اختيارٌ بلا أرشيفٍ يُخرج شاشةً سوداء، وأرشيفٌ بلا اختيارٍ
            // يخطف الخريطة ممّن لم يطلبها. وقاعدة «الافتراضيّ يحفظ ما كان» تقتضي
            // أنّ من لم يبدّل المصدر لا يتبدّل عليه شيء.
            val vectorArchive = remember(mapSource) {
                if (VectorMapsAvailable && mapSource == MapSourcePreference.VECTOR) {
                    VectorMaps.firstAvailable(context)
                } else {
                    null
                }
            }
            if (vectorArchive != null) {
                VectorRouteMap(
                    archive = vectorArchive,
                    points = drawPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            } else
            RouteMap(
                // نقاطُ **الرسم** لا نقاط الرحلة: التخفيف هنا لا في `TripTrack`،
                // فالملفّ والإحصاءات وعدّاد النقاط أسفل الشاشة تبقى على الأصل
                // كاملًا. ورحلةٌ فيها ألفُ نقطةٍ على شاشةٍ عرضها ‎1080‎ بكسل ترسم ما
                // ترسمه عشرة آلاف؛ الفرق يظهر في زمن الرسم وحده.
                points = drawPoints,
                invertTiles = invertTiles,
                preferOffline = preferOffline,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                mapSource = mapSource,
                onMapSourceChange = vm.settings::setMapSource,
                // ملفّ الرحلة نفسه لا نسخةٌ مصغَّرة في المخبأ: OsmAnd يقرؤه مباشرةً
                // حين يرسم الخريطة، فتُوفَّر كتابةُ ملفٍّ ثانٍ لكلّ رحلةٍ تُفتح.
                gpxFile = trip.file,
                noticeVisible = noticeVisible,
                onDismissNotice = onDismissNotice,
                onOpenOsmAnd = openInOsmAnd,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(16.dp))
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MiniStat(stringResource(R.string.stat_distance), Fmt.distance(trip.distanceKm), stringResource(R.string.unit_km))
            MiniStat(stringResource(R.string.stat_duration), Fmt.duration(trip.durationMs), "")
            MiniStat(stringResource(R.string.stat_max_speed), Fmt.speed(trip.maxSpeedKmh), stringResource(R.string.unit_kmh))
            MiniStat(stringResource(R.string.stat_avg_speed), Fmt.avg(trip.avgSpeedKmh), stringResource(R.string.unit_kmh))
        }

        Text(
            text = stringResource(R.string.trip_points, trip.points.size),
            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionChip(
                icon = Icons.Filled.Share,
                label = stringResource(R.string.trip_share),
                modifier = Modifier.weight(1f),
            ) {
                // نشارك ملفّ الـ GPX نفسه عبر FileProvider؛ لا نُصدّر نسخةً ثانية.
                val uri = vm.uriForTrack(trip.file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = GPX_MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(send, context.getString(R.string.trip_share))
                    )
                }
            }

            ActionChip(
                icon = Icons.Filled.Map,
                label = stringResource(R.string.trip_open_osmand),
                modifier = Modifier.weight(1f),
                onClick = openInOsmAnd,
            )

            ActionChip(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.media_delete),
                modifier = Modifier.weight(1f),
            ) { confirmDelete = true }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trip_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    // حذف مؤجّل: تختفي الرحلة من القائمة فورًا ويبقى الملفّ حتّى تنقضي
                    // مهلة التراجع. نموذج العرض يملك المؤقّت والمحو.
                    vm.requestDeleteTrip(trip)
                    confirmDelete = false
                    onDeleted()
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = Surface,
        )
    }
}

/** زرّ فعل مربّع: أيقونة فوق نصّ، يملأ ثلث الصفّ عبر weight من المُستدعي. */
@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(SurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Accent)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private const val GPX_MIME = "application/gpx+xml"

/** زمن مدنيّ للعرض فقط؛ القياس كلّه على elapsedRealtimeNanos داخل المحرّك. */
private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(ms))

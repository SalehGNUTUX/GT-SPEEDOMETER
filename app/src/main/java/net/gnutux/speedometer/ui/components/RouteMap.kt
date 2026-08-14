package net.gnutux.speedometer.ui.components

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.map.MapBinding
import net.gnutux.speedometer.core.map.MapSource
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.AccentDim
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextSecondary
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

// خريطة المسار المحفوظ. اخترنا osmdroid لأنّ القاعدة تمنع خدمات Google،
// ولأنّها الوحيدة التي تقرأ أرشيفات البلاط المحلّيّة (mbtiles وأخواتها) مباشرة.
//
// الخريطة عرض Android تقليديّ ذو دورة حياة خاصّة به، فخمسة قيود تحكم هذا الملفّ:
// — لا يُنشأ [MapView] قبل أن يُحسم المصدر: مُنشئه يبني مزوّدًا شبكيًّا افتراضيًّا،
//   فإنشاؤه ثمّ تبديل مزوّده كان يعني بلاطاتٍ تُنزَّل في اللحظة التي نريد فيها ألّا
//   يُلمس الإنترنت أصلًا.
// — كلّ ما يسبق أوّل إطار يقع على خيط قرص: القرار، وتحويل النقاط إلى إحداثيّات،
//   وحساب الصندوق الحاوي. وما دام ذلك جاريًا يُكتب «تُحمَّل الخريطة…» لا فراغ.
// — لا شيء يمسّ التكبير بعد أوّل ملاءمة، فتبقى حركة المستعمل ملكه.
// — الاستئناف والإيقاف والتحرير تُشتقّ من دورة حياة المالك لا من إعادة التأليف.
// — المصدر الحيّ يُكتب على الخريطة نفسها: خريطةٌ فارغة يجب أن تُشخَّص بنظرة، لا أن
//   تُترك للمستعمل يظنّ التطبيق معطوبًا.

/** الزوايا مستديرة داخل المُركّب نفسه كي لا تطغى البلاطات المربّعة على أيّ موضع استعمال */
private val MapShape = RoundedCornerShape(16.dp)

/**
 * كلّ ما يلزم لرسم الخريطة، محسوبًا دفعةً واحدة خارج الخيط الرئيس.
 *
 * [geo] و[box] هنا لا في `update`: تحويل بضعة آلاف من نقاط الرحلة إلى [GeoPoint]
 * وحساب صندوقها كانا يقعان على الخيط الرئيس، ومرّتين لا مرّة — مرّةً للملاءمة ومرّةً
 * للخطّ. وهذا وحده يُسقط إطاراتٍ في اللحظة التي ينتظر فيها المستعمل الخريطة.
 */
private class MapReady(
    val binding: MapBinding,
    val geo: List<GeoPoint>,
    val box: BoundingBox?,
)

/** ما يستحقّ أن يُقال تحت الخريطة، مرّةً في الزيارة لا مع كلّ رحلة */
private enum class MapNotice { NONE, NO_OFFLINE, VECTOR_ONLY }

/**
 * يرسم مسار الرحلة فوق بلاطات محلّيّة إن وُجدت، وإلّا فوق بلاطات OSM.
 *
 * @param points نقاط المسار كما قرأها [net.gnutux.speedometer.core.trip.GpxReader].
 * @param invertTiles قلب ألوان البلاطات؛ يليق بالسمة الداكنة ويُتعب العين في الفاتحة.
 * @param preferOffline تفضيل الأرشيف المحلّيّ على الإنترنت متى غطّى موضع الرحلة.
 * @param noticeVisible هل يُسمح بإظهار ملاحظة الخرائط المحلّيّة الآن؟ القرار عند
 *   المُستدعي لا هنا: «مرّةً في الزيارة» حالةٌ تخصّ الشاشة كلَّها لا رحلةً بعينها.
 * @param onDismissNotice إخفاء الملاحظة لبقيّة الزيارة.
 * @param onOpenOsmAnd فتح المسار في OsmAnd؛ هو الجواب الوحيد لمن يملك خرائط `.obf`.
 * @param modifier القياس يأتي من المُستدعي؛ [MapView] نفسه لا يفرض ارتفاعًا.
 */
@Composable
fun RouteMap(
    points: List<TrackPoint>,
    invertTiles: Boolean,
    preferOffline: Boolean,
    modifier: Modifier = Modifier,
    noticeVisible: Boolean = false,
    onDismissNotice: () -> Unit = {},
    onOpenOsmAnd: () -> Unit = {},
) {
    val context = LocalContext.current
    val backdrop = SurfaceHigh

    // أوّل من يسأل يُطلق المسح. المُنشئ الحقيقيّ في `SpeedoApp.onCreate`، وهذا
    // احتياطٌ لا أكثر؛ والمسح على أيّ حال يقع على خيط قرصٍ لا على الخيط الرئيس.
    val offlineMaps = remember(context) { OfflineMaps.of(context) }
    val library by offlineMaps.library.collectAsStateWithLifecycle()

    // ثلاثة مواضع لا موضعٌ واحد: البداية والوسط والنهاية. أرشيفٌ يغطّي المنطلق دون
    // المقصد يُخرج نصف خريطةٍ ونصف فراغ، وهو ما لا يجوز أن يُعرض على أنّه «محلّيّة».
    val probes = remember(points) {
        if (points.isEmpty()) {
            emptyList()
        } else {
            listOf(points.first(), points[points.size / 2], points.last())
                .map { it.latitude to it.longitude }
        }
    }

    // القرار والهندسة كلاهما خارج الخيط الرئيس، وفي خطوةٍ واحدة: `bind` يفتح
    // الأرشيفات مرّةً واحدة فيجسّ بها ويبني المزوّد منها. وقيمته الأولى `null` تعني
    // «لم يُحسم بعد» لا «إنترنت»، فلا تُنزَّل بلاطةٌ واحدة قبل أن نعرف أنّ المحلّيّة
    // لا تكفي.
    val ready by produceState<MapReady?>(null, library, preferOffline, points, offlineMaps) {
        if (!library.scanned) {
            value = null
            return@produceState
        }
        val geometry = withContext(Dispatchers.IO) {
            val geo = points.map { GeoPoint(it.latitude, it.longitude) }
            val box = if (geo.size >= 2) {
                runCatching { BoundingBox.fromGeoPoints(geo).increaseByScale(1.25f) }.getOrNull()
            } else {
                null
            }
            geo to box
        }
        val binding = offlineMaps.bind(probes, preferOffline)
        // إلغاءٌ يقع بين بناء المزوّد وإسناده يترك قواعد sqlite مفتوحةً بلا مالكٍ
        // يُغلقها: لا `MapView` سيستلمه، ولا `onDetach` سيُنادى عليه.
        if (!isActive) {
            binding.provider.detach()
            return@produceState
        }
        value = MapReady(binding, geometry.first, geometry.second)
    }

    val current = ready
    val notice = when {
        !noticeVisible || !library.scanned -> MapNotice.NONE
        // قبل أن يُحسم المصدر لا نقول شيئًا: ملاحظةٌ تظهر ثمّ تختفي أسوأ من الصمت.
        current == null || current.binding.source == MapSource.OFFLINE -> MapNotice.NONE
        library.hasVectorOnly -> MapNotice.VECTOR_ONLY
        !library.hasArchives -> MapNotice.NO_OFFLINE
        // عنده أرشيفٌ لكنّه لا يغطّي هنا: الشارة تقول «إنترنت» وذلك كافٍ، ودعوته
        // إلى تنزيل خرائطَ وهو قد نزّلها نصيحةٌ في غير موضعها.
        else -> MapNotice.NONE
    }

    Box(
        modifier
            .clip(MapShape)
            .background(backdrop, MapShape)
    ) {
        if (current != null) {
            // `key` لا `remember` وحده: `AndroidView` يمسك عرضه مدى حياة عقدته، فتبديل
            // المزوّد بعد رصدِ ملفٍّ جديد يحتاج عقدةً جديدة لا وسمًا جديدًا.
            key(current) {
                RouteMapSurface(
                    ready = current,
                    invertTiles = invertTiles,
                    modifier = Modifier.matchParentSize(),
                )
            }
            MapSourceBadge(
                source = current.binding.source,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        } else {
            // صندوقٌ فارغ يُقرأ «معطوب». سطرٌ واحد يُقرأ «انتظر».
            Text(
                text = stringResource(R.string.map_loading),
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (notice != MapNotice.NONE) {
            OfflineMapNote(
                notice = notice,
                vectorLabel = if (library.vectorFiles.size == 1) {
                    library.vectorNames
                } else {
                    Fmt.count(library.vectorFiles.size)
                },
                folderPath = library.folderPath,
                onOpenOsmAnd = onOpenOsmAnd,
                onDismiss = onDismissNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )
        }
    }
}

/** شارة المصدر الحيّ: سطرٌ واحد يفصل «الأرشيف لا يغطّي هنا» عن «التطبيق معطوب» */
@Composable
private fun MapSourceBadge(source: MapSource, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(source.label),
        style = MaterialTheme.typography.labelSmall.copy(
            color = Bg,
            fontWeight = FontWeight.Bold,
        ),
        modifier = modifier
            .background(Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * ملاحظةٌ تُعلِّم ولا تُلحّ.
 *
 * ليست حوارًا حاجبًا: من فتح رحلةً أراد أن يراها، لا أن يُستجوَب عن خرائطه. وهي
 * قابلة للإخفاء، ومن أخفاها لا تعود في هذه الزيارة — القرار عند الشاشة لا هنا.
 *
 * وفي حالة `.obf` لا نَعِد بشيء: نقول إنّها وُجدت وإنّها لا تُرسم هنا، ونحيل إلى
 * OsmAnd نفسها لأنّها الوحيدة التي تفهمها.
 */
@Composable
private fun OfflineMapNote(
    notice: MapNotice,
    vectorLabel: String,
    folderPath: String,
    onOpenOsmAnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFolder by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (notice == MapNotice.NO_OFFLINE) {
            Text(
                text = stringResource(R.string.map_no_offline_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = if (notice == MapNotice.VECTOR_ONLY) {
                stringResource(R.string.map_obf_found, vectorLabel)
            } else {
                stringResource(R.string.map_no_offline_body)
            },
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
        )
        // المسار يظهر بالطلب لا دائمًا: سطرٌ طويل من الرموز يزاحم الرسالة نفسها.
        if (showFolder && folderPath.isNotEmpty()) {
            Text(
                text = folderPath,
                style = MaterialTheme.typography.labelSmall.copy(color = Accent),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (notice == MapNotice.VECTOR_ONLY) onOpenOsmAnd() else showFolder = !showFolder
                },
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text(
                    text = if (notice == MapNotice.VECTOR_ONLY) {
                        stringResource(R.string.trip_open_osmand)
                    } else {
                        stringResource(R.string.map_no_offline_action)
                    },
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** [MapView] وحدها: تُنشأ بمزوّدٍ محسوم وهندسةٍ جاهزة، وتموت بموت عقدتها. */
@Composable
private fun RouteMapSurface(
    ready: MapReady,
    invertTiles: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ألوان السمة ونصوص الموارد تُقرأ داخل التركيب وحده، فتُرفع إلى متغيّرات محلّية
    // تلتقطها لامبدا `update` غير المُركّبة. و`toArgb` لا `value.toInt()`: القيمة
    // المعبّأة 64-بت تُقتطع إلى صفر، فيخرج الخطّ شفّافًا تمامًا.
    val routeColor = Accent.toArgb()
    val startColor = Accent.toArgb()
    val endColor = Danger.toArgb()
    val haloColor = Bg.toArgb()
    val voidColor = SurfaceHigh.toArgb()
    val voidGridColor = AccentDim.toArgb()
    val startTitle = stringResource(R.string.map_start)
    val endTitle = stringResource(R.string.map_end)

    val resources = context.resources
    val density = resources.displayMetrics.density
    // الأيقونتان تُرسمان مرّةً ويُعاد استعمالهما: `update` تُستدعى مع كلّ تأليف،
    // ورسم نقطيّتين في كلّ مرّة تخصيصٌ لا داعي له في مسار حسّاسٍ للزمن.
    val startIcon = remember(startColor, haloColor, density) {
        endpointIcon(resources, density, startColor, haloColor, diamond = false)
    }
    val endIcon = remember(endColor, haloColor, density) {
        endpointIcon(resources, density, endColor, haloColor, diamond = true)
    }

    // المزوّد يُمرَّر إلى المُنشئ لا بعده: `MapView(context)` وحده يبني مزوّدًا شبكيًّا
    // ويفتح ذاكرة بلاطاته فورًا، وهو ما نتجنّبه في الوضع المحلّيّ.
    val map = remember(context, ready) {
        MapView(context, ready.binding.provider).apply {
            setMultiTouchControls(true)
            // القطع صريح في الوضع المحلّيّ: المزوّد بلا وحدة تنزيل أصلًا، وهذه
            // طبقة أمانٍ ثانية تمنع أيّ وحدةٍ شبكيّة تُضاف لاحقًا من الانفلات.
            setUseDataConnection(ready.binding.source == MapSource.ONLINE)
        }
    }

    // `onDetach` يوقف خيوط جلب البلاطات ويحرّر ذاكرتها والأرشيفات المفتوحة. يُستدعى
    // من `onDispose` ومن `onRelease` معًا، وقد يجتمعان، فالراية تمنع تحريرًا مزدوجًا.
    val detached = remember(map) { AtomicBoolean(false) }
    val detachOnce = remember(map) {
        {
            if (detached.compareAndSet(false, true)) {
                map.onPause()
                map.onDetach()
            }
        }
    }

    DisposableEffect(lifecycleOwner, map) {
        // الاستئناف مربوط بدورة حياة المالك لا بإعادة التأليف: نداؤه في `update`
        // كان يعيد تشغيل خيوط البلاطات مع كلّ تأليفٍ جديد.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            detachOnce()
        }
    }

    // ملاءمة الحدود مرّةً واحدة لكلّ مسار لا في كلّ تأليف: `zoomToBoundingBox`
    // تستدعي `requestLayout` داخليًّا، فأيّ تأليفٍ للأب — فتح حوارٍ مثلًا — كان
    // ينتزع تكبير المستعمل وإزاحته. والصندوق محسوبٌ سلفًا خارج الخيط الرئيس.
    LaunchedEffect(map, ready) {
        val box = ready.box ?: return@LaunchedEffect
        // القياس الفعليّ للعرض شرط الملاءمة، فنؤجّلها إلى ما بعد أوّل تخطيط عبر post،
        // ونلفّها بـ runCatching لأنّها ترمي إن كان الصندوق منحلًّا.
        map.post { runCatching { map.zoomToBoundingBox(box, false) } }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            val tiles = view.overlayManager.tilesOverlay
            tiles.setColorFilter(if (invertTiles) TilesOverlay.INVERT_COLORS else null)

            // بلاطةٌ لا مصدر لها تُرسم بلون سطح التطبيق وشبكةٍ خافتة بدل الرماديّ
            // الأصمّ: الفراغ حينها يُقرأ «لا بيانات هنا» لا «الخريطة تعطّلت».
            tiles.setLoadingBackgroundColor(voidColor)
            tiles.setLoadingLineColor(voidGridColor)

            // نمسح طبقاتنا وحدها كي لا نُتلف طبقة البلاطات التي يديرها osmdroid.
            view.overlays.removeAll { it is Polyline || it is Marker }

            val geo = ready.geo
            if (geo.size < 2) {
                view.invalidate()
                return@AndroidView
            }

            view.overlays.add(
                Polyline(view).apply {
                    setPoints(geo)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = routeColor
                }
            )

            // البداية أوّلًا ثمّ النهاية: في رحلةٍ دائريّة يقع الطرفان على موضعٍ
            // واحد، والأحدث أولى بأن يُرى.
            view.overlays.add(
                Marker(view).apply {
                    position = geo.first()
                    // مركزٌ على مركز: الرمز دائرة لا دبّوس، فلا معنى لتعليقه من أسفله.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setIcon(startIcon)
                    title = startTitle
                }
            )

            view.overlays.add(
                Marker(view).apply {
                    position = geo.last()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setIcon(endIcon)
                    title = endTitle
                }
            )

            view.invalidate()
        },
        onRelease = { detachOnce() },
    )
}

/**
 * رمزا طرفَي المسار.
 *
 * كانا دبّوسين أخضرين متطابقين، فلم يكن في الخريطة ما يقول أين بدأ الراكب وأين
 * انتهى. والتمييز هنا ثلاثيّ عن قصد، لأنّ الخريطة صغيرةٌ داكنةٌ تُقرأ بنظرةٍ خاطفة
 * وقد تُقلب ألوانها بتفضيل «قلب البلاطات»:
 *
 * — **الشكل**: البداية دائرة، والنهاية معيّن. الظلّ الخارجيّ وحده يفرّق بينهما ولو
 *   ذهب اللون كلّه.
 * — **اللون**: فيروزيّ السمة للبداية، وأحمرها للنهاية — وهما طرفا اللوحة تباينًا.
 * — **الملء**: البداية حلقة مفرَّغة (قلبها بلون الخلفيّة) والنهاية مصمتة.
 *
 * وحول كليهما هالةٌ بلون خلفيّة التطبيق: بلا هالةٍ يذوب الرمز في بلاطةٍ فاتحة أو
 * في خطّ المسار نفسه.
 */
private fun endpointIcon(
    resources: Resources,
    density: Float,
    fill: Int,
    halo: Int,
    diamond: Boolean,
): Drawable {
    val sizeDp = if (diamond) 26f else 23f
    val size = (sizeDp * density).toInt().coerceAtLeast(8)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    val haloWidth = (2f * density).coerceAtLeast(1f)

    if (diamond) {
        paint.color = halo
        canvas.drawPath(diamondPath(center, center - 0.5f), paint)
        paint.color = fill
        canvas.drawPath(diamondPath(center, center - 0.5f - haloWidth), paint)
    } else {
        paint.color = halo
        canvas.drawCircle(center, center, center - 0.5f, paint)
        paint.color = fill
        canvas.drawCircle(center, center, center - 0.5f - haloWidth, paint)
        // القلب المفرَّغ: يجعل الدائرة حلقةً تُميَّز عن أيّ قرصٍ مصمت على الخريطة.
        paint.color = halo
        canvas.drawCircle(center, center, (center - 0.5f - haloWidth) * 0.38f, paint)
    }

    return BitmapDrawable(resources, bitmap)
}

/** معيّن (مربّع مُدار 45 درجة) حول مركزٍ بنصف قطرٍ معطى */
private fun diamondPath(center: Float, radius: Float): Path = Path().apply {
    moveTo(center, center - radius)
    lineTo(center + radius, center)
    lineTo(center, center + radius)
    lineTo(center - radius, center)
    close()
}

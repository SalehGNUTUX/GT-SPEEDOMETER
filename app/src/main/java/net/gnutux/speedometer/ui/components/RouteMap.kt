package net.gnutux.speedometer.ui.components

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.map.MapBindForce
import net.gnutux.speedometer.core.map.MapBinding
import net.gnutux.speedometer.core.map.MapSource
import net.gnutux.speedometer.core.map.OfflineMaps
import net.gnutux.speedometer.core.map.VectorMaps
import net.gnutux.speedometer.core.map.OsmAndBridge
import net.gnutux.speedometer.core.map.OsmAndProjection
import net.gnutux.speedometer.core.map.OsmAndShot
import net.gnutux.speedometer.core.map.OsmAndState
import net.gnutux.speedometer.core.settings.MapSourcePreference
import net.gnutux.speedometer.core.trip.TrackPoint
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.AccentDim
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary
import net.gnutux.speedometer.ui.theme.Warn
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
//
// وفوق ذلك كلّه طبقةُ ترتيبٍ للمصادر (انظر [chooseMode]) لا تمسّ مسار الأرشيف: هي
// تقرّر أيّها يُعرض، لا كيف يُبنى. وللمستعمل عليها تجاوزٌ صريح يُحفظ في التفضيلات.

/** الزوايا مستديرة داخل المُركّب نفسه كي لا تطغى البلاطات المربّعة على أيّ موضع استعمال */
private val MapShape = RoundedCornerShape(16.dp)

/**
 * أزرق المسار على صورة OsmAnd.
 *
 * **وهو اللون الوحيد في هذا الملفّ الذي لا يُقرأ من التركيب**، خلافًا للقاعدة، وعن
 * قصد: الصورة خريطةٌ رسمها محرّك OsmAnd بألوانه هو، والخطّ عليها يجب أن يُقرأ خطَّ
 * مسارٍ لا لونَ تمييزٍ لتطبيقنا. والفيروزيّ يذوب في المسطّحات المائيّة والمساحات
 * الخضراء عند OsmAnd، وأزرقُه هذا هو ما يعرفه المستعمل من OsmAnd نفسه.
 */
private val RouteBlue = Color(0xFF1E88E5)

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
    /** هل ثمّة اتّصالٌ أصلًا؟ بلا هذا نعرض بلاطاتٍ لا تصل ونسمّيها «إنترنت» */
    val network: Boolean,
    /**
     * هل يغطّي أرشيفُ المجلّد هذا المسار؟
     *
     * ليست نسخةً من `binding.source`: المزوّد قد يكون شبكيًّا لأنّ المستعمل طلب
     * الإنترنت صراحةً، والأرشيف مع ذلك يغطّي. وبهذا الفرق وحده تقول قائمةُ الطبقات
     * الصدق في سطر «محلّيّة» بدل أن تُطفئها لأنّ المرسوم الآن شبكيّ.
     */
    val offlineCovers: Boolean,
) {
    /**
     * هل استلم [MapView] هذا المزوّد؟
     *
     * المزوّد يُبنى قبل أن يُعرف أيّ مصدرٍ سيُعرض، وقد ننتهي إلى صورةٍ من OsmAnd أو
     * إلى مخطَّط فلا تُنشأ خريطةٌ أصلًا. وحينها لا مالك يستدعي `onDetach`، فتبقى
     * أرشيفات سكليت مفتوحة. الراية تفصل الحالتين عند التحرير.
     */
    var consumed: Boolean = false
}

/**
 * ما يستحقّ أن يُقال تحت الخريطة، مرّةً في الزيارة لا مع كلّ رحلة.
 *
 * و[RAW_DATA] أخت [VECTOR_ONLY] لا حالةٌ ثالثة غريبة عنهما: كلتاهما «وُجد ملفٌّ ولا
 * يُرسم»، والفرق أنّ ‎.obf‎ تفتحها OsmAnd وبيانات Geofabrik الخام لا يفتحها شيءٌ على
 * الهاتف. وسكوتُنا عنها هو ما جعل المستعمل ينتظر خريطةً من ملفّ أشكال ESRI.
 */
private enum class MapNotice { NONE, NO_OFFLINE, VECTOR_ONLY, RAW_DATA }

/** ما يُعرض فعلًا في هذا الإطار. الترتيب بينها في [chooseMode]. */
private enum class MapMode {
    /** لم يُحسم شيء بعد: لا بلاطة تُطلب ولا صورة */
    PENDING,

    /** بلاطات، محلّيّةً كانت أو من الإنترنت — وهي وحدها التفاعليّة */
    TILES,

    /** صورةٌ ساكنة رسمها OsmAnd من خرائطه المتجهيّة */
    OSMAND,

    /** المسار وحده بلا خريطة أساس */
    SKETCH,
}

/**
 * ترتيب المصادر، وتجاوز المستعمل له.
 *
 * [MapSourcePreference.AUTO] هو الترتيب المُقرّ: أرشيف بلاطاتٍ يغطّي المسار، ثمّ صورة
 * OsmAnd، ثمّ الإنترنت، ثمّ المخطَّط. والمنطق واحد: ما لا يحتاج شبكةً يسبق ما يحتاجها،
 * والتفاعليّ يسبق الساكن عند تساوي الكلفة. وعند تفضيل الإنترنت ينقلب الأوّلان وحدهما.
 *
 * وأمّا التجاوزات الثلاثة فحدُّها أنّها **تفضيلٌ لا تعبّد**:
 * — [MapSourcePreference.OSMAND] يقدّم صورة OsmAnd ما دامت متاحة، ثمّ يسقط إلى
 *   الترتيب المعتاد. من فضّلها لا يريد شاشةً فارغة حين لا يردّ OsmAnd.
 * — [MapSourcePreference.ONLINE] **لا يسأل OsmAnd أصلًا**، لا يؤخّره: السؤال وحده
 *   يوقظ عمليّته كاملة، ومن اختار الشبكة صراحةً لا يدفع ذلك الثمن. ولا يسقط إلى
 *   الأرشيف حين تنقطع الشبكة بل إلى المخطَّط: «إنترنت» و«محلّيّة» خياران في القائمة
 *   ولو تبادلا صمتًا لصارا خيارًا واحدًا في عين المستعمل — والقائمة على كلّ حال قد
 *   أخبرته «لا اتّصال الآن» قبل أن يختار، فالمخطَّط جوابٌ متوقَّع لا مفاجأة.
 * — [MapSourcePreference.OFFLINE] بلاطات الأرشيف؛ فإن لم يغطِّ المسارَ أرشيفٌ عاد إلى
 *   الترتيب المعتاد كاملًا. والفرق عن سابقه مقصود: انقطاع الشبكة حالٌ يعرفها الراكب
 *   من هاتفه، أمّا مستطيلٌ رماديّ أصمّ فيُقرأ عطبًا في التطبيق لا حدًّا في بياناته.
 *
 * و[osmAndPending] تُعيد «انتظر» لا «تخطَّ»: قولُ «لا خريطة» ثمّ إظهارها بعد ثانيةٍ
 * وميضٌ أسوأ من انتظارٍ معلن.
 */
private fun chooseMode(
    ready: MapReady?,
    preferOffline: Boolean,
    preference: MapSourcePreference,
    osmAndReady: Boolean,
    osmAndPending: Boolean,
): MapMode {
    if (ready == null) return MapMode.PENDING
    val offlineTiles = ready.binding.source == MapSource.OFFLINE
    if (preference == MapSourcePreference.ONLINE) {
        // المزوّد هنا شبكيٌّ يقينًا: `bind` جاءه [MapBindForce.ONLINE] فلم يفتح قرصًا.
        return if (ready.network) MapMode.TILES else MapMode.SKETCH
    }
    if (preference == MapSourcePreference.OSMAND) {
        if (osmAndReady) return MapMode.OSMAND
        if (osmAndPending) return MapMode.PENDING
    }
    // الأرشيف لا يُبنى إلّا حين يغطّي المسار فعلًا، فوجوده هنا يعني خريطةً كاملة.
    // وهو الجواب أيضًا لمن اختار «محلّيّة»؛ ومن لم يغطِّ أرشيفُه يمضي في الترتيب
    // المعتاد أسفلَه بلا فرعٍ خاصّ به.
    if (offlineTiles) return MapMode.TILES
    if (!preferOffline && ready.network) return MapMode.TILES
    if (osmAndReady) return MapMode.OSMAND
    if (osmAndPending) return MapMode.PENDING
    if (ready.network) return MapMode.TILES
    return MapMode.SKETCH
}

/**
 * سطرٌ واحد في قائمة المصادر: ماذا يُسمّى، وماذا يُقال تحته، وأيُختار الآن.
 *
 * [note] موردٌ واحد لا اثنان لأنّ الموضع على الشاشة واحد: وصفُ المصدر حين يعمل، وسببُ
 * تعطّله حين لا يعمل. وسطرٌ يقول «لا أرشيف بلاطاتٍ يغطّي هذا المسار» أنفع من وصفٍ
 * جميلٍ لخيارٍ مطفأ لا يُدرى لماذا أُطفئ.
 */
private class MapSourceOption(
    val source: MapSourcePreference,
    @StringRes val label: Int,
    @StringRes val note: Int,
    val enabled: Boolean,
)

/**
 * حال المصادر الأربعة **في هذه الرحلة**.
 *
 * كانت هذه الدالّة تُرجع قائمةً مصفّاةً تُحذف منها الخيارات المتعذّرة، وكان ذلك أصل
 * الشكوى: من نزّل أرشيفًا محلّيًّا فلم يغطِّ مسارَ رحلته لم يجد «محلّيّة» في الزرّ
 * أصلًا، فاستنتج أنّ التطبيق لا يعرف الخرائط المحلّيّة ولا يقرأ ما نزّل. **الخيار
 * المحذوف لغزٌ والخيار المطفأ خبر**، فصارت تُرجع الأربعة كلَّها ومع كلٍّ حالُه.
 *
 * وقبل أن يُحسم الربط ([ready] فارغة) تُعرض كلّها صالحة: التعطيلُ ثمّ التمكينُ بعد
 * جزءٍ من ثانية وميضٌ يقرؤه المستعمل عطبًا، والسكوت أصدق من حكمٍ لم نتحقّقه بعد.
 */
private fun mapSourceOptions(
    ready: MapReady?,
    osmAndReady: Boolean,
    vectorReady: Boolean,
): List<MapSourceOption> {
    fun option(
        source: MapSourcePreference,
        @StringRes label: Int,
        @StringRes note: Int,
        @StringRes reason: Int,
        available: Boolean,
    ) = MapSourceOption(source, label, if (available) note else reason, available)

    return listOf(
        // «تلقائيّ» لا يُطفأ بحال: هو الترتيب نفسه، وآخرُه المخطَّط الذي يعمل دائمًا.
        MapSourceOption(
            source = MapSourcePreference.AUTO,
            label = R.string.map_source_auto,
            note = R.string.map_source_auto_note,
            enabled = true,
        ),
        option(
            source = MapSourcePreference.ONLINE,
            label = R.string.map_source_online,
            note = R.string.map_source_online_note,
            reason = R.string.map_source_na_online,
            available = ready?.network ?: true,
        ),
        option(
            source = MapSourcePreference.OSMAND,
            label = R.string.map_source_osmand,
            note = R.string.map_source_osmand_note,
            reason = R.string.map_source_na_osmand,
            available = osmAndReady,
        ),
        option(
            source = MapSourcePreference.OFFLINE,
            label = R.string.map_source_offline,
            note = R.string.map_source_offline_note,
            reason = R.string.map_source_na_offline,
            // التغطية لا مجرّد وجود الملفّ: أرشيف مدينةٍ أخرى يُرضي شرط «عندي خريطة»
            // ويُخرج فراغًا رماديًّا، وذلك أسوأ ما يمكن أن يُعرض على من اختار بنفسه.
            available = ready?.offlineCovers ?: true,
        ),
        option(
            source = MapSourcePreference.VECTOR,
            label = R.string.map_source_vector,
            note = R.string.map_source_vector_note,
            reason = R.string.map_source_na_vector,
            // وجودُ أرشيفٍ صالحٍ شرطٌ للعرض: اختيارٌ بلا أرشيفٍ يُخرج شاشةً سوداء،
            // وهو ما تمنعه القاعدة نفسها المبسوطة عند خيار «محلّيّة» فوقه.
            available = vectorReady,
        ),
    )
}

/**
 * هل من اتّصالٍ يحمل بلاطة؟
 *
 * الجواب عند الشكّ «نعم»: خطأٌ في فحص الشبكة يجب أن يُبقي السلوك القديم — بلاطات
 * إنترنت — لا أن يُنزل المستعمل إلى المخطَّط بلا سبب.
 */
private fun hasNetwork(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
    capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

/**
 * يرسم مسار الرحلة فوق بلاطات محلّيّة إن وُجدت، وإلّا فوق بلاطات OSM.
 *
 * @param points نقاط المسار كما قرأها [net.gnutux.speedometer.core.trip.GpxReader].
 * @param invertTiles قلب ألوان البلاطات؛ يليق بالسمة الداكنة ويُتعب العين في الفاتحة.
 * @param preferOffline تفضيل الأرشيف المحلّيّ على الإنترنت متى غطّى موضع الرحلة.
 * @param mapSource تجاوز المستعمل لترتيب المصادر؛ يُقرأ من التفضيلات ويُمرَّر كما
 *   يُمرَّر [invertTiles]: هذا مُركّب عرضٍ لا يقرأ المخزن بنفسه.
 * @param onMapSourceChange يكتب ما اختير من قائمة المصادر في التفضيل، فيبقى بعد
 *   الخروج. والقائمة تُعرض بأربعة خيارات دائمًا — انظر [MapSourceMenu].
 * @param gpxFile ملفّ الرحلة إن كان عند المُستدعي. يُمرَّر إلى OsmAnd كما هو، وغيابه
 *   لا يُعطّل شيئًا: نكتب حينها نسخةً مصغَّرة في المخبأ من النقاط نفسها.
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
    mapSource: MapSourcePreference = MapSourcePreference.AUTO,
    onMapSourceChange: (MapSourcePreference) -> Unit = {},
    gpxFile: File? = null,
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

    // الجسر يُنشأ عند أوّل خريطةٍ تُفتح لا عند إقلاع التطبيق: أوّل فحصٍ له يوقظ
    // عمليّة OsmAnd، ولا يُدفع ذلك الثمن عمّن لم يفتح رحلةً أصلًا.
    val osmAnd = remember(context) { OsmAndBridge.of(context) }
    val osmAndStatus by osmAnd.status.collectAsStateWithLifecycle()

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

    // ما يُجبَر عليه الربط، مشتقًّا من التفضيل لا هو نفسه.
    //
    // وهو مفتاح إعادة الربط أدناه، وذلك موضع دقّة: بلا مفتاحٍ أصلًا — وهو ما كان —
    // يبقى المزوّد القديم يرسم بعد أن يختار المستعمل «إنترنت» أو «محلّيّة»، فيبدو
    // الاختيار بلا أثر. وبالتفضيل الخام مفتاحًا تُهدم الخريطة وتُبنى عند كلّ اختيارٍ
    // ولو لم يتبدّل المزوّد المطلوب — وتبديلُ «تلقائيّ» بـ«OsmAnd» لا يمسّ البلاطات
    // بحال. فالمفتاح هو **ما يطلبه `bind`** وحده: قيمتان متساويتان لا تُعيدان ربطًا.
    val force = when (mapSource) {
        MapSourcePreference.ONLINE -> MapBindForce.ONLINE
        MapSourcePreference.OFFLINE -> MapBindForce.OFFLINE
        else -> null
    }

    // القرار والهندسة كلاهما خارج الخيط الرئيس، وفي خطوةٍ واحدة: `bind` يفتح
    // الأرشيفات مرّةً واحدة فيجسّ بها ويبني المزوّد منها. وقيمته الأولى `null` تعني
    // «لم يُحسم بعد» لا «إنترنت»، فلا تُنزَّل بلاطةٌ واحدة قبل أن نعرف أنّ المحلّيّة
    // لا تكفي.
    val ready by produceState<MapReady?>(null, library, preferOffline, points, offlineMaps, force) {
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
        val network = withContext(Dispatchers.IO) { hasNetwork(context) }
        // «الإنترنت أوّلًا» تفضيلٌ لا تعبّد: حين لا اتّصال أصلًا نسأل الأرشيف المحلّيّ،
        // فبلاطةٌ محفوظة خيرٌ من مستطيلٍ رماديّ يعتذر عن الشبكة.
        val binding = offlineMaps.bind(probes, preferOffline || !network, force)
        // ما لم يجسّه `bind` نجسّه هنا: من طلب الإنترنت صراحةً لم يُفتح له قرص، وقائمةُ
        // الطبقات مع ذلك تحتاج أن تعرف أيغطّي أرشيفُه هذا المسار أم لا. والكلفة محصورة
        // بمن عنده أرشيفٌ أصلًا (`covers` تُجيب بلا قرصٍ حين لا ملفّات)، والبديل قائمةٌ
        // تُطفئ «محلّيّة» بلا علمٍ أو تُشعلها بلا علم — وكلاهما كذب.
        //
        // والإلغاء أثناءه يترك المزوّد بلا `MapView` يملكه، فيُحرَّر في موضعه قبل أن
        // يمضي الاستثناء.
        val covers = binding.archiveCovers ?: runCatching { offlineMaps.covers(probes) }
            .getOrElse { error ->
                runCatching { binding.provider.detach() }
                throw error
            }
        // إلغاءٌ يقع بين بناء المزوّد وإسناده يترك قواعد sqlite مفتوحةً بلا مالكٍ
        // يُغلقها: لا `MapView` سيستلمه، ولا `onDetach` سيُنادى عليه.
        if (!isActive) {
            binding.provider.detach()
            return@produceState
        }
        value = MapReady(binding, geometry.first, geometry.second, network, covers)
    }

    val current = ready

    // فشلُ OsmAnd يُحفظ لهذه الرحلة: بلا هذا نُعاود سؤاله مع كلّ إعادة تركيب، فنقف
    // ثماني ثوانٍ في كلّ مرّة على جوابٍ نعلم أنّه لن يأتي.
    var osmAndFailed by remember(points) { mutableStateOf(false) }
    val osmAndReady = osmAndStatus.usable && !osmAndFailed
    val mode = chooseMode(
        ready = current,
        preferOffline = preferOffline,
        preference = mapSource,
        osmAndReady = osmAndReady,
        osmAndPending = osmAndStatus.state == OsmAndState.CHECKING,
    )
    // يُسأل مرّةً عند تبدّل المكتبة لا في كلّ إعادة تركيب: `firstAvailable` تقرأ
    // القرص وتفحص توقيع الملفّ، وذلك في كلّ إطارٍ كلفةٌ بلا مقابل
    // شرطان: النكهة تحمل محرّكًا، ويوجد أرشيفٌ صالح. وفي الخفيفة الأوّل كاذبٌ ثابتًا
    // فيُحذف الخيار من القائمة أصلًا — لا خيارٌ معطَّلٌ يسأل المستعمل عن سببه
    val vectorReady = remember(context) {
        VectorMapsAvailable && VectorMaps.firstAvailable(context) != null
    }
    val sourceOptions = mapSourceOptions(current, osmAndReady, vectorReady)

    // مقاس الصورة يُطلب بالبكسل، ولا يُعرف قبل أوّل تخطيط. نأخذه من التخطيط نفسه
    // بدل `BoxWithConstraints` كي لا تُقرأ خصائص مُستقبِلٍ ضمنيّ من لامدا متداخلة.
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val screenDensity = LocalDensity.current.density
    val trackColor = RouteBlue.toArgb()

    val osmAndShot by produceState<OsmAndShot?>(null, mode, boxSize, current, gpxFile, trackColor) {
        value = null
        if (mode != MapMode.OSMAND || boxSize.width <= 0 || boxSize.height <= 0) {
            return@produceState
        }
        val shot = if (gpxFile != null) {
            osmAnd.renderGpx(gpxFile, boxSize.width, boxSize.height, screenDensity, trackColor)
        } else {
            val positions = withContext(Dispatchers.IO) {
                points.map { it.latitude to it.longitude }
            }
            osmAnd.renderTrack(positions, boxSize.width, boxSize.height, screenDensity, trackColor)
        }
        // الفشل ليس فراغًا: نُسقط الرتبة إلى ما بعدها في الترتيب بدل صندوقٍ أبيض.
        if (shot == null) osmAndFailed = true else value = shot
    }

    // من لم يستلمه `MapView` لا مالك له. الشرط لا يُقلب: تحريرٌ مزدوج يغلق قواعد
    // سكليت من تحت خيوط البلاطات.
    DisposableEffect(current) {
        onDispose {
            if (current != null && !current.consumed) {
                runCatching { current.binding.provider.detach() }
            }
        }
    }

    val notice = when {
        !noticeVisible || !library.scanned -> MapNotice.NONE
        // قبل أن يُحسم المصدر لا نقول شيئًا: ملاحظةٌ تظهر ثمّ تختفي أسوأ من الصمت.
        current == null || current.binding.source == MapSource.OFFLINE -> MapNotice.NONE
        // ومن رُسمت خريطته من OsmAnd لا يُدعى إلى فتحها في OsmAnd.
        mode == MapMode.OSMAND -> MapNotice.NONE
        library.hasVectorOnly -> MapNotice.VECTOR_ONLY
        // بيانات Geofabrik الخام: كان يُقال فيها «وُجدت خريطة محلّيّة» ثمّ لا يُرسم
        // شيء، وهي الآن مصنّفةٌ على حقيقتها فتُقال على حقيقتها.
        library.hasRawDataOnly -> MapNotice.RAW_DATA
        !library.hasArchives -> MapNotice.NO_OFFLINE
        // عنده أرشيفٌ لكنّه لا يغطّي هنا: الشارة تقول «إنترنت» وذلك كافٍ، ودعوته
        // إلى تنزيل خرائطَ وهو قد نزّلها نصيحةٌ في غير موضعها.
        else -> MapNotice.NONE
    }

    Box(
        modifier
            .clip(MapShape)
            .background(backdrop, MapShape)
            .onSizeChanged { boxSize = it }
    ) {
        when {
            mode == MapMode.TILES && current != null -> {
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
                    label = current.binding.source.label,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }

            mode == MapMode.OSMAND -> {
                val shot = osmAndShot
                if (shot != null) {
                    OsmAndCanvas(
                        shot = shot,
                        points = points,
                        frame = boxSize,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.map_osmand_rendering),
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                MapSourceBadge(
                    label = R.string.map_source_osmand,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }

            // المخطَّط يحمل شارته ووصفه بنفسه، فلا شارة مصدرٍ فوقه.
            mode == MapMode.SKETCH -> RouteSketch(
                points = points,
                modifier = Modifier.matchParentSize(),
            )

            else -> {
                // صندوقٌ فارغ يُقرأ «معطوب». سطرٌ واحد يُقرأ «انتظر».
                Text(
                    text = stringResource(R.string.map_loading),
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // الزرّ يظهر دائمًا لا حين يزيد الصالح على واحد: القائمة تُخبر عن المصادر كما
        // تبدّلها، ومن لا يعمل عنده إلّا مصدرٌ واحد هو أحوج الناس إلى معرفة السبب.
        MapSourceMenu(
            options = sourceOptions,
            selected = mapSource,
            onSelect = onMapSourceChange,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        )

        if (notice != MapNotice.NONE) {
            OfflineMapNote(
                notice = notice,
                fileLabel = when (notice) {
                    MapNotice.RAW_DATA -> foundFileLabel(library.rawDataFiles, library.rawDataNames)
                    else -> foundFileLabel(library.vectorFiles, library.vectorNames)
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

// ————————————————————————— صورة OsmAnd —————————————————————————

/**
 * صورة OsmAnd معروضةً: تُلاءم الإطار أوّلًا، وتُقرَّب ويُتنقَّل فيها بعد ذلك.
 *
 * ثلاثة قرارات تفسّر شكلها:
 *
 * — **الصورة تُطلب بأضعاف مقاس الإطار** (انظر `OsmAndBridge.detailFactor`) لأنّ
 *   واجهة OsmAnd لا تقبل مركزًا ولا تقريبًا: لا سبيل إلى تقريبٍ حقيقيّ إلّا أن نملك
 *   بكسلاتٍ زائدة نقرّب إليها. فحدُّ التقريب الأعلى هو عامل التضعيف نفسه: ما بعده
 *   تمديدٌ يُهرِّئ الصورة، لا تقريب.
 *
 * — **الطبقة الثانية خارج تحويل الصورة**. الصورة تُحوَّل بـ`graphicsLayer`، وأمّا
 *   الخطّ والعلامتان والأسهم فتُرسم في [androidx.compose.foundation.Canvas] يطبّق
 *   التحويل بنفسه على المواضع وحدها. ولذلك يبقى سمك الخطّ ومقاس العلامة ثابتَين
 *   مهما قُرِّب — وهو ما تفعله الخرائط الحقيقيّة، بخلاف صورةٍ تُكبَّر بما فيها.
 *
 * — **الإزاحة محصورة** في [clampPan]: بلا حصرٍ ينزلق الإصبع بالصورة خارج الإطار
 *   فيبقى فراغٌ من لون السطح، ولا يعرف المستعمل أضاع الخريطة أم تعطّل التطبيق.
 */
@Composable
private fun OsmAndCanvas(
    shot: OsmAndShot,
    points: List<TrackPoint>,
    frame: IntSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokePx = with(density) { 4.dp.toPx() }
    val arrowPx = with(density) { 7.dp.toPx() }
    val arrowStrokePx = with(density) { 2.dp.toPx() }
    val arrowSpacingPx = with(density) { 76.dp.toPx() }
    val startRadiusPx = with(density) { 11.5f.dp.toPx() }
    val endRadiusPx = with(density) { 13.dp.toPx() }
    val haloPx = with(density) { 2.dp.toPx() }

    // الألوان تُرفع قبل `DrawScope`: هو ليس تركيبًا ولا يقرأ [Bg] ولا [Accent].
    val startColor = Accent
    val endColor = Danger
    val haloColor = Bg

    // نسبة بكسل الصورة إلى بكسل الإطار. لا تُشتقّ من `shot.detail` وحده: لو ردّ
    // OsmAnd صورةً بمقاسٍ غير المطلوب لبقي الإسقاط صحيحًا والنسبةُ خاطئة.
    val toFrame = if (shot.widthPx > 0) frame.width.toFloat() / shot.widthPx else 0f

    // الإسقاط والمسار يُحسبان مرّةً لكلّ (صورة × مقاس): إسقاط آلاف النقاط ستّين مرّةً
    // في الثانية أثقل ما في هذه الشاشة، وهو يقع أثناء الإيماءة نفسها.
    val overlay = remember(shot, points, toFrame, arrowSpacingPx) {
        val projection: OsmAndProjection? =
            shot.projection(points.map { it.latitude to it.longitude })
        if (projection == null || toFrame <= 0f) {
            null
        } else {
            buildOverlay(points, projection, toFrame, arrowSpacingPx)
        }
    }

    var scale by remember(shot) { mutableStateOf(1f) }
    var pan by remember(shot) { mutableStateOf(Offset.Zero) }
    val maxScale = shot.detail.toFloat().coerceAtLeast(1f)

    Box(
        modifier.pointerInput(shot, frame) {
            detectTransformGestures { centroid, drag, zoom, _ ->
                val next = (scale * zoom).coerceIn(1f, maxScale)
                // النقطة التي تحت الإصبعين تبقى تحتهما: بلا هذا يقفز المشهد إلى
                // مركز الإطار مع كلّ قرصة.
                val anchored = (centroid - pan) / scale
                scale = next
                pan = clampPan(centroid + drag - anchored * next, next, frame)
            }
        }
    ) {
        Image(
            bitmap = shot.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.map_source_osmand),
            // الصورة تُطلب بنسبة الإطار نفسها، والاقتصاص احتياطٌ لو أعادها OsmAnd
            // بمقاسٍ مخالف: إطارٌ أسود أسوأ من حافّةٍ مقصوصة.
            contentScale = ContentScale.Crop,
            // `Medium` لا `None`: الصورة تُصغَّر إلى ثلث مقاسها عند الملاءمة، وبلا
            // ترشيحٍ تظهر أسنانٌ على كلّ حرفٍ وخطّ.
            filterQuality = FilterQuality.Medium,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // المبدأ في الزاوية لا في المركز، فيوافق حساب [clampPan] حرفيًّا.
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = pan.x
                    translationY = pan.y
                },
        )

        if (overlay != null) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                withTransform({
                    translate(pan.x, pan.y)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    // السمك مقسومٌ على التقريب فيخرج ثابتًا على الشاشة بعد التحويل.
                    drawPath(
                        path = overlay.path,
                        color = RouteBlue,
                        style = Stroke(width = strokePx / scale),
                    )
                }
                for (arrow in overlay.arrows) {
                    drawDirectionArrow(
                        at = arrow.at * scale + pan,
                        dirX = arrow.dirX,
                        dirY = arrow.dirY,
                        color = haloColor,
                        length = arrowPx,
                        strokeWidth = arrowStrokePx,
                    )
                }
                // النهاية بعد البداية: في رحلةٍ دائريّة يقع الطرفان على موضعٍ واحد،
                // والأحدث أولى بأن يُرى — كما في [RouteSketch] سواءً بسواء.
                drawRingMarker(
                    overlay.start * scale + pan,
                    startColor,
                    haloColor,
                    startRadiusPx,
                    haloPx,
                )
                if (overlay.hasLine) {
                    drawDiamondMarker(
                        overlay.end * scale + pan,
                        endColor,
                        haloColor,
                        endRadiusPx,
                        haloPx,
                    )
                }
            }
        }

        // الأزرار لازمةٌ ولو كانت الإيماءة تكفي: من يقود بيدٍ واحدة لا يقرص بإصبعين.
        if (maxScale > 1f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                MapControlButton(
                    icon = Icons.Filled.Add,
                    label = R.string.map_zoom_in,
                    onClick = {
                        val next = (scale * ZOOM_STEP).coerceIn(1f, maxScale)
                        pan = clampPan(zoomAtCenter(pan, scale, next, frame), next, frame)
                        scale = next
                    },
                )
                MapControlButton(
                    icon = Icons.Filled.Remove,
                    label = R.string.map_zoom_out,
                    onClick = {
                        val next = (scale / ZOOM_STEP).coerceIn(1f, maxScale)
                        pan = clampPan(zoomAtCenter(pan, scale, next, frame), next, frame)
                        scale = next
                    },
                )
                MapControlButton(
                    icon = Icons.Filled.FitScreen,
                    label = R.string.map_reset_zoom,
                    onClick = {
                        scale = 1f
                        pan = Offset.Zero
                    },
                )
            }
        }
    }
}

/** خطوة الزرّ الواحد؛ أصغر من ضعفٍ كي لا يقفز المشهد من الملاءمة إلى أقصى تقريب */
private const val ZOOM_STEP = 1.6f

/** التقريب بالزرّ يقع حول مركز الإطار: لا إصبع يدلّ على موضعٍ آخر */
private fun zoomAtCenter(pan: Offset, from: Float, to: Float, frame: IntSize): Offset {
    val center = Offset(frame.width / 2f, frame.height / 2f)
    val anchored = (center - pan) / from
    return center - anchored * to
}

/**
 * حصر الإزاحة داخل حدود الصورة.
 *
 * الصورة بعد التحويل تشغل `مقاس الإطار × التقريب` ومبدؤها عند [pan]. فكي لا يظهر
 * فراغٌ يجب أن يبقى المبدأ في السالب أو الصفر، وأن يبلغ طرفها الآخر حافّة الإطار على
 * الأقلّ. وعند تقريبٍ يساوي واحدًا يلتقي الحدّان عند الصفر فتُشلّ الإزاحة من نفسها.
 */
private fun clampPan(pan: Offset, scale: Float, frame: IntSize): Offset = Offset(
    pan.x.coerceIn(frame.width * (1f - scale), 0f),
    pan.y.coerceIn(frame.height * (1f - scale), 0f),
)

/** سهم اتّجاهٍ واحد: رأسُ سهمٍ مفتوح عند النقطة، جناحاه إلى الخلف */
private fun DrawScope.drawDirectionArrow(
    at: Offset,
    dirX: Float,
    dirY: Float,
    color: Color,
    length: Float,
    strokeWidth: Float,
) {
    // جناحان بزاوية ‎±35°‎ عن اتّجاه السير، محسوبان بدوران المتّجه لا بحساب مثلّثات:
    // الاتّجاه معياريٌّ سلفًا، فالدوران ضربٌ وجمعٌ لا أكثر.
    val cosine = 0.819f
    val sine = 0.574f
    val backX = -dirX * length
    val backY = -dirY * length
    val leftX = backX * cosine - backY * sine
    val leftY = backX * sine + backY * cosine
    val rightX = backX * cosine + backY * sine
    val rightY = -backX * sine + backY * cosine
    drawLine(color, at, Offset(at.x + leftX, at.y + leftY), strokeWidth)
    drawLine(color, at, Offset(at.x + rightX, at.y + rightY), strokeWidth)
}

/** طبقة المسار فوق الصورة: خطٌّ وطرفاه وأسهم اتّجاهه، بإحداثيّات الإطار لا الصورة */
private class RouteOverlay(
    val path: androidx.compose.ui.graphics.Path,
    val start: Offset,
    val end: Offset,
    val arrows: List<DirectionArrow>,
    /** مسارٌ من نقطةٍ واحدة أو نقاطٍ متطابقة: علامةُ بدايةٍ وحدها ولا خطّ ولا نهاية */
    val hasLine: Boolean,
)

private class DirectionArrow(val at: Offset, val dirX: Float, val dirY: Float)

/**
 * بناء الطبقة مرّةً واحدة.
 *
 * والتخفيف مقصود: نقطتان متجاورتان أقرب من بكسلٍ ونصف على الشاشة لا تضيفان إلى
 * الخطّ شيئًا، وقد تبلغان في رحلةٍ طويلة عشرات الآلاف. وهو تخفيفٌ في الرسم وحده،
 * فصندوق الإسقاط محسوبٌ من كلّ النقاط قبله.
 */
private fun buildOverlay(
    points: List<TrackPoint>,
    projection: OsmAndProjection,
    toFrame: Float,
    arrowSpacing: Float,
): RouteOverlay? {
    if (points.isEmpty()) return null
    fun at(index: Int): Offset {
        val point = points[index]
        return Offset(
            projection.xOf(point.longitude) * toFrame,
            projection.yOf(point.latitude) * toFrame,
        )
    }

    val path = androidx.compose.ui.graphics.Path()
    val start = at(0)
    path.moveTo(start.x, start.y)
    var previous = start
    var last = start
    var drawn = false
    val arrows = mutableListOf<DirectionArrow>()
    // نصفُ المسافة في البداية: أوّل سهمٍ لا يقع فوق علامة البداية فيحجبها.
    var since = arrowSpacing * 0.5f

    for (index in 1 until points.size) {
        val here = at(index)
        last = here
        val stepX = here.x - previous.x
        val stepY = here.y - previous.y
        val step = hypot(stepX, stepY)
        if (step < MIN_SEGMENT_PX) continue
        path.lineTo(here.x, here.y)
        drawn = true
        previous = here
        since += step
        if (since >= arrowSpacing) {
            since = 0f
            arrows += DirectionArrow(here, stepX / step, stepY / step)
        }
    }
    // آخر نقطةٍ خُفّفت لا تُترك خارج الخطّ: طرفُ المسار هو موضع علامة النهاية.
    if (drawn && last != previous) path.lineTo(last.x, last.y)

    return RouteOverlay(path, start, last, arrows, drawn)
}

/** أقلّ ما يستحقّ ضلعًا في الخطّ، بالبكسل على الشاشة */
private const val MIN_SEGMENT_PX = 1.5f

/**
 * زرّ فوق الخريطة: مساحة اللمس ‎56dp‎ (قاعدة 6) والأيقونة ‎24dp‎ داخلها.
 *
 * وخلفيّته مصمتةٌ لا شفّافة: الأيقونة فوق خريطةٍ متبدّلة الألوان لا تُرى إلّا بقرصٍ
 * تحتها، وهذا موضعٌ يُضغط أثناء القيادة.
 */
@Composable
private fun MapControlButton(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Surface, CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = Accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * قائمة مصدر الخريطة، معلَّقةً بزرّ الطبقات.
 *
 * كانت دورةً على الزرّ نفسه، وكان في ذلك عيبان اجتمعا على المستعمل: الدورة لا تُري
 * أحدًا ما لم يُختَر بعدُ ولا ما هو مختارٌ الآن، وكانت تُسقط من نفسها كلَّ خيارٍ لا
 * يعمل في هذه اللحظة. فمن وضع أرشيفًا محلّيًّا في مجلّده ثمّ لم يجد «محلّيّة» في الزرّ
 * استنتج — وهو محقٌّ فيما رأى — أنّ التطبيق لا يعرف الخرائط المحلّيّة أصلًا وأنّ
 * الزرّ يبدّل بين الإنترنت وOsmAnd لا غير.
 *
 * فالقاعدة هنا معكوسة: **تُعرض المصادر الأربعة دائمًا**، ويُطفأ ما لا يعمل ويُكتب
 * تحته سببُ إطفائه، ويُعلَّم المختار بعلامةٍ وبلون التمييز. والخيار المطفأ ليس ضجيجًا
 * بل خبرٌ لا يبلغه المستعمل من موضعٍ آخر: «لا أرشيف بلاطاتٍ يغطّي هذا المسار» يقول له
 * إنّ ملفّه مقروءٌ وإنّ المشكلة في موضعه لا في وجوده.
 *
 * والزرّ يظهر ولو لم يعمل إلّا مصدرٌ واحد: من ليس أمامه إلّا خيارٌ واحد أحوجُ الناس
 * إلى معرفة لماذا.
 */
@Composable
private fun MapSourceMenu(
    options: List<MapSourceOption>,
    selected: MapSourcePreference,
    onSelect: (MapSourcePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    // القائمة نافذةٌ مستقلّة، لكنّ موضعها يُشتقّ من هذا الصندوق: تعليقها بالزرّ نفسه
    // يُبقيها في الزاوية التي ضغط فيها إبهامُه بدل أن تنبت في وسط الخريطة.
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        MapControlButton(
            icon = Icons.Filled.Layers,
            label = R.string.map_source_switch,
            onClick = { open = true },
        )

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // لون التركيب صراحةً: خانة `surfaceContainer` في Material 3 غير مضبوطةٍ
            // في تركيبنا، فتُترك القائمة على لون Material الافتراضيّ وهو غريبٌ عن
            // بقيّة الشاشة.
            containerColor = Surface,
        ) {
            // عنوانٌ لا عنصرٌ يُضغط: القائمة تُفتح فوق خريطةٍ متبدّلة الألوان، وبلا سطرٍ
            // يقول ما هي تُقرأ قائمةَ «طبقاتٍ» تُعرض فوق الخريطة لا مصادرَ لها.
            Text(
                text = stringResource(R.string.map_source_menu),
                style = MaterialTheme.typography.titleSmall.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 10.dp,
                    bottom = 4.dp,
                ),
            )

            options.forEach { option ->
                val chosen = option.source == selected
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = stringResource(option.label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = when {
                                        chosen -> Accent
                                        option.enabled -> TextPrimary
                                        else -> TextSecondary
                                    },
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                            // السطر الثاني هو الفرق بين قائمةٍ تُبدّل وقائمةٍ تشرح:
                            // وصفُ المصدر حين يعمل بلون النصّ الثانويّ، وسببُ تعطّله
                            // حين لا يعمل بلون التنبيه كي تُقرأ العلّة بنظرة.
                            Text(
                                text = stringResource(option.note),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (option.enabled) TextSecondary else Warn,
                                ),
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(option.source)
                    },
                    enabled = option.enabled,
                    // العلامة على المختار وحده، و`null` لا عنصرٌ فارغ: عنصرٌ فارغ يحجز
                    // عرضه فتُزاح نصوص بقيّة السطور عن محاذاتها.
                    trailingIcon = if (chosen) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                // زينةٌ لا خبر: اسم الخيار مقروءٌ سلفًا، وقارئ الشاشة
                                // يُعلن حالة الاختيار من العنصر نفسه.
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        null
                    },
                    // حدٌّ للعرض: سطر السبب جملةٌ لا كلمة، وبلا حدٍّ تمتدّ القائمة إلى
                    // عرض الشاشة كلِّه فتحجب الخريطة التي تصفها.
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
        }
    }
}

/**
 * اسم الملفّ حين يكون واحدًا، وعددُه حين يكونون جمعًا.
 *
 * الاسم أنفع ما يُقال لمن نسي ما وضعه في المجلّد — وشكوى «‎morocco-latest-free_shp.zip‎»
 * لم تُفهم إلّا لأنّ التطبيق كتب الاسم — لكنّ ثلاثة أسماء بهذا الطول في سطرٍ واحد تدفع
 * الرسالة نفسها خارج الشاشة.
 */
private fun foundFileLabel(files: List<File>, names: String): String =
    if (files.size == 1) names else Fmt.count(files.size)

/**
 * شارة المصدر الحيّ: سطرٌ واحد يفصل «الأرشيف لا يغطّي هنا» عن «التطبيق معطوب».
 *
 * تأخذ نصًّا لا [MapSource]: المصادر المعروضة صارت أكثر من مصادر البلاطات — صورةُ
 * OsmAnd ليست مزوّدَ بلاطات، وتوسيع ذلك التعداد لأجل شارةٍ يخلط عقدَين مختلفين.
 */
@Composable
private fun MapSourceBadge(@StringRes label: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(label),
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
 *
 * وفي حالة البيانات الخام لا إحالة أصلًا: ملفّ ‎.shp.zip‎ أو ‎.osm.pbf‎ لا يفتحه
 * OsmAnd ولا غيره على الهاتف، فزرُّه هو زرّ «أين أضع الخرائط؟» — أي الطريق إلى ملفٍّ
 * صالح — لا وعدٌ بفتح ما لا يُفتح.
 */
@Composable
private fun OfflineMapNote(
    notice: MapNotice,
    fileLabel: String,
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
            text = when (notice) {
                MapNotice.VECTOR_ONLY -> stringResource(R.string.map_obf_found, fileLabel)
                MapNotice.RAW_DATA -> stringResource(R.string.map_rawdata_found, fileLabel)
                else -> stringResource(R.string.map_no_offline_body)
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
        // من هنا فصاعدًا المزوّد ملك العرض: `onDetach` وحده يحرّره.
        ready.consumed = true
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

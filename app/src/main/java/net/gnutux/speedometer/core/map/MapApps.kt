package net.gnutux.speedometer.core.map

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * تطبيقات الخرائط المثبَّتة على الجهاز: كشفٌ وتصنيفٌ لا أكثر.
 *
 * **ما الذي تقدر عليه هذه الميزة، وما الذي لا تقدر عليه؟** الرسمُ داخل تطبيقنا لا
 * يقدر عليه إلّا OsmAnd، لأنّه وحده يعرض واجهة AIDL نطلب بها صورة خريطة (انظر
 * [OsmAndBridge]). ولا نظير لها في Organic Maps ولا Magic Earth ولا سواهما — فلا
 * جسر لها هنا ولا وعد به. والذي يقدر عليه كلُّ تطبيقٍ خرائط هو أن **نُحيل** إليه
 * المسار بنيّة `ACTION_VIEW`، وذلك ما نكشفه ونصنّفه.
 *
 * وأربعة قرارات تفسّر شكل الملفّ:
 *
 * — **نيّتان لا واحدة**: نسأل `PackageManager` عن `ACTION_VIEW` على عنوان ملفٍّ
 *   بنوع `application/gpx+xml`، وعن `ACTION_VIEW` على مخطّط `geo:`. الأولى تكشف من
 *   يفتح مسارنا، والثانية تكشف تطبيقات الخرائط عمومًا. ولا شيء منهما يُرى على
 *   أندرويد ‎11‎ فما فوق بلا عنصرَي `<intent>` في `<queries>` بالبيان.
 *
 * — **العنوان في النيّة لا النوع وحده**: مرشِّح النيّات يرفض نيّةً بلا مخطّط إن كان
 *   يعلن `scheme` (وأكثر مرشِّحات GPX تعلن `content`)، وبعضها يشترط مسارًا ينتهي
 *   بـ`.gpx`. فنجسّ بعنوانٍ من سلطة مزوّدنا نفسها بمسارٍ ينتهي بـ`.gpx`، وهو عين
 *   النيّة التي ستُطلق حين يفتح المستعمل رحلته — فما رأيناه هو ما سيعمل.
 *
 * — **تصفية مديري الملفّات**: من أعلن نوعًا شاملًا — نجمةً مكان النوع أو مكان نوعه
 *   الفرعيّ — التقط GPX والتقط كلّ شيءٍ آخر، وهو مدير ملفّاتٍ أو متصفّح لا تطبيق
 *   خرائط. فلا يُقبل إلّا من أعلن نوعًا
 *   **محدَّدًا** (فيه مائلة، انظر [declaresConcreteType])، أو شهد له `geo:` بأنّه
 *   تطبيق خرائط. ولهذا وحده يُسأل عن `application/octet-stream` أيضًا: هو نوع من
 *   يقبل ملفّات GPX بلا أن يعرف نوعها، ولا يُعتدّ به إلّا مع شهادة `geo:` — ولذلك
 *   لا يحتاج عنصرًا ثالثًا في `<queries>`: من شهد له `geo:` صار مرئيًّا سلفًا.
 *
 * — **المسح على خيط قرص**: `queryIntentActivities` و`loadLabel` و`loadIcon` تقرأ
 *   موارد تطبيقاتٍ أخرى من القرص، ولا يقع ذلك على الخيط الرئيس. والنتيجة
 *   [StateFlow] واحدة كما في [OfflineMaps]، فلا يختلف جوابان عن سؤالٍ واحد.
 */
class MapApps private constructor(context: Context) {

    private val app = context.applicationContext

    /** نطاق بعمر العمليّة: المسح لا يتبع شاشةً بعينها */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _library = MutableStateFlow(MapAppLibrary())

    /**
     * حصيلة آخر مسح. قيمتها الأولى `scanned = false`، أي «لم نسأل `PackageManager`
     * بعد» لا «لا تطبيق خرائط على الجهاز»: الفرق بينهما سطرٌ يُعرض أو لا يُعرض.
     */
    val library: StateFlow<MapAppLibrary> = _library.asStateFlow()

    /** مسحان متزامنان يسألان `PackageManager` السؤال نفسه؛ الثاني يُهمَل */
    private val scanning = AtomicBoolean(false)

    /**
     * إعادة الفحص. تُنادى مرّةً عند أوّل استعمال، ثمّ كلّما ضغط المستعمل «إعادة الفحص»
     * بعد أن يثبّت تطبيق خرائطٍ جديدًا — فتثبيت الحزم لا يُبلّغنا به أحد.
     */
    fun rescan() {
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                _library.value = MapAppLibrary(apps = scanNow(), scanned = true)
            } finally {
                scanning.set(false)
            }
        }
    }

    // ————————————————————————— المسح والتصنيف —————————————————————————

    private fun scanNow(): List<MapAppInfo> {
        val pm = app.packageManager
        val probes = LinkedHashMap<String, Probe>()

        /** أوّل نتيجةٍ لحزمةٍ هي التي تُقرأ منها التسمية والأيقونة؛ وما بعدها يزيد قدراتها */
        fun probeOf(info: ResolveInfo): Probe? {
            val pkg = runCatching { info.activityInfo?.packageName }.getOrNull().orEmpty()
            // ولا نعرض أنفسنا: لا نعلن اليوم نيّة فتح GPX، ولو أعلنّاها غدًا لكان
            // «افتح الرحلة في GT-SPEEDOMETER» سطرًا لا معنى له
            if (pkg.isEmpty() || pkg == app.packageName) return null
            return probes.getOrPut(pkg) { Probe(pkg, info) }
        }

        for (info in query(pm, fileIntent(GPX_MIME))) {
            val probe = probeOf(info) ?: continue
            if (declaresConcreteType(info)) probe.gpxTyped = true else probe.gpxLoose = true
        }
        for (info in query(pm, fileIntent(STREAM_MIME))) {
            probeOf(info)?.stream = true
        }
        for (info in query(pm, Intent(Intent.ACTION_VIEW, Uri.parse(GEO_PROBE)))) {
            probeOf(info)?.geo = true
        }

        return probes.values
            // «يعلن نوع GPX بعينه» أو «يفتح موضعًا على خريطة»: ما عداهما مدير ملفّات
            .filter { it.gpxTyped || it.geo }
            .map { probe ->
                MapAppInfo(
                    packageName = probe.pkg,
                    label = labelOf(pm, probe.info, probe.pkg),
                    icon = iconOf(pm, probe.info),
                    // الرسم داخلنا واجهة AIDL، وهي عند OsmAnd وحده
                    canRender = probe.pkg in RENDER_PACKAGES,
                    // ما نَعِد به هو ما نُرسله فعلًا: نيّة GPX. ومن لم يعلن النوع
                    // صراحةً لا يُوعد عنه إلّا إذا شهد له `geo:` أنّه تطبيق خرائط.
                    canOpenTrack = probe.gpxTyped || ((probe.gpxLoose || probe.stream) && probe.geo),
                )
            }
            .sortedWith(
                // ترتيب الفائدة لا ترتيب الاكتشاف: من يرسم عندنا أوّلًا، ثمّ من يفتح
                // المسار، ثمّ من لا يفتح إلّا موضعًا. والاسم يفصل بين المتساويين.
                compareByDescending<MapAppInfo> { it.canRender }
                    .thenByDescending { it.canOpenTrack }
                    .thenBy { it.label },
            )
    }

    /** قدرات حزمةٍ واحدة كما تجمّعت من النيّات الثلاث */
    private class Probe(val pkg: String, val info: ResolveInfo) {
        /** أعلن `application/gpx+xml` أو نوعًا محدَّدًا آخر يشمله */
        var gpxTyped = false

        /** التقط GPX بنوعٍ شامل فيه نجمة، وهو حال مديري الملفّات */
        var gpxLoose = false

        var stream = false
        var geo = false
    }

    /**
     * نيّة فتح ملفٍّ من مزوّدنا.
     *
     * العنوان صوريّ ولا يُفتح، لكنّ شكله يجب أن يطابق العنوان الحقيقيّ: المخطّط
     * `content`، والسلطة سلطتنا، ومسارٌ ينتهي بـ`.gpx` — لأنّ من المرشِّحات ما يشترط
     * الثلاثة، فيسقط الجسّ بعنوانٍ أنقص ولا يظهر تطبيقٌ قائم.
     */
    private fun fileIntent(mime: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://${app.packageName}$AUTHORITY_SUFFIX/$PROBE_PATH"),
            mime,
        )

    /**
     * هل أعلن هذا المرشِّح نوعًا محدَّدًا؟
     *
     * `IntentFilter` يخزّن النوع الشامل بلا مائلة: نجمةٌ وحدها حين تكون النجمة مكان
     * النوع كلّه، واسم النوع وحده حين تكون مكان الفرعيّ (`application` مثلًا).
     * فوجود المائلة هو الفصل بين «يفهم GPX» و«يفتح
     * كلّ شيء». و[ResolveInfo.filter] لا يُملأ إلّا مع [PackageManager.GET_RESOLVED_FILTER]،
     * وغيابُه رغم الطلب يُعامل معاملة النوع الشامل: الشكّ لا يصنع وعدًا.
     */
    private fun declaresConcreteType(info: ResolveInfo): Boolean {
        val types = runCatching { info.filter?.typesIterator() }.getOrNull() ?: return false
        while (types.hasNext()) {
            val type = types.next() ?: continue
            if (type.contains('/')) return true
        }
        return false
    }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(QUERY_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, QUERY_FLAGS)
        }
    }.getOrDefault(emptyList())

    /** اسم الحزمة بديلًا عند تعذّر التسمية: اسمٌ تقنيّ خيرٌ من سطرٍ فارغ */
    private fun labelOf(pm: PackageManager, info: ResolveInfo, pkg: String): String =
        runCatching { info.loadLabel(pm).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: pkg

    /**
     * الأيقونة إن تيسّرت.
     *
     * تُحوَّل إلى `Bitmap` هنا لا في الواجهة: التحويل يرسم أيقونةً متكيّفة على لوحٍ
     * جديد وذلك عملٌ لا يقع على خيط الإطار، والمقاس ثابتٌ صغير فلا تُخزَّن أيقونةُ
     * مُطلِقٍ بمقاسها الكامل لكلّ تطبيقٍ مثبَّت.
     */
    private fun iconOf(pm: PackageManager, info: ResolveInfo): Bitmap? = runCatching {
        info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)
    }.getOrNull()

    companion object {
        private const val GPX_MIME = "application/gpx+xml"

        /** من يقبل ملفًّا بلا أن يعرف نوعه؛ لا يُعتدّ به وحده (انظر تعليق الصنف) */
        private const val STREAM_MIME = "application/octet-stream"

        /** موضعٌ صوريّ: المطلوب المخطّط لا الإحداثيّات */
        private const val GEO_PROBE = "geo:0,0?q=0,0"

        private const val AUTHORITY_SUFFIX = ".files"
        private const val PROBE_PATH = "tracks/probe.gpx"

        /**
         * `MATCH_DEFAULT_ONLY` لأنّ ما لا يعلن `CATEGORY_DEFAULT` لا يُطلق بنيّةٍ
         * ضمنيّة أصلًا، فعرضُه في القائمة وعدٌ كاذب. و`GET_RESOLVED_FILTER` لأنّ
         * [declaresConcreteType] لا تعمل بدونه.
         */
        private const val QUERY_FLAGS =
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER

        /**
         * حزم OsmAnd الثلاث. تتكرّر هنا وفي [OsmAndBridge] عمدًا: قائمته خاصّةٌ به،
         * والمعنى مختلف — هناك «بمن نتّصل» وهنا «من يرسم داخلنا».
         */
        private val RENDER_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

        /** مقاس الأيقونة المخزَّنة بالبكسل؛ تُعرض في صفٍّ لا تملأ شاشة */
        private const val ICON_PX = 96

        @Volatile
        private var instance: MapApps? = null

        /**
         * النسخة الوحيدة، وأوّل من يسألها يُطلق المسح.
         *
         * ولا تُنشأ في `SpeedoApp.onCreate`: سؤال `PackageManager` وتحميل أيقونات
         * تطبيقاتٍ أخرى ثمنٌ لا يدفعه من لم يفتح الإعدادات أصلًا.
         */
        fun of(context: Context): MapApps =
            instance ?: synchronized(this) {
                instance ?: MapApps(context).also {
                    it.rescan()
                    instance = it
                }
            }
    }
}

/**
 * تطبيق خرائطٍ مثبَّت وما يقدر عليه.
 *
 * القدرتان منفصلتان لأنّهما مختلفتان حقًّا: [canRender] تعني صورة خريطةٍ **داخل
 * شاشتنا** وهي عند OsmAnd وحده، و[canOpenTrack] تعني أن نسلّمه المسار فيفتحه عنده.
 * وجمعُهما في «مدعوم/غير مدعوم» كان يَعِد بالأولى كلَّ من يملك الثانية.
 */
data class MapAppInfo(
    val packageName: String,
    val label: String,
    /** `null` حين تتعذّر: الأيقونة زينةٌ لا تُوقف عرض السطر */
    val icon: Bitmap? = null,
    val canRender: Boolean = false,
    val canOpenTrack: Boolean = false,
) {
    /**
     * تطبيق خرائطٍ لا يفتح مسارًا: أعلن `geo:` ولم يعلن نوع GPX.
     *
     * يُعرض ولا يُختار. إخفاؤه يمنع المستعمل من معرفة ما على جهازه، وعرضُه قابلًا
     * للاختيار وعدٌ يُخلَف عند أوّل رحلة — والوسط أن يُرى ولا يُوعد به.
     */
    val placesOnly: Boolean get() = !canOpenTrack
}

/**
 * حصيلة مسحٍ واحد.
 *
 * [scanned] تفصل «لم نسأل بعد» عن «سألنا فلم نجد» كما في
 * [OfflineMapLibrary]: الأولى تُترك فراغًا على الشاشة، والثانية تقول «لا تطبيق
 * خرائط». ووميضُ سطرٍ كاذبٍ لجزءٍ من ثانية أسوأ من فراغٍ لحظيّ.
 */
data class MapAppLibrary(
    val apps: List<MapAppInfo> = emptyList(),
    val scanned: Boolean = false,
) {
    val hasApps: Boolean get() = apps.isNotEmpty()
}

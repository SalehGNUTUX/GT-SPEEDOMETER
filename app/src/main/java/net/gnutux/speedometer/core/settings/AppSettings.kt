package net.gnutux.speedometer.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * مخزن التفضيلات. مصدر حقيقة واحد يشترك فيه المحرّك والواجهة والخدمة الأمامية.
 *
 * كلّ تفضيل يُعرض `StateFlow` لا `Flow`، لأنّ مواضع القراءة (رسم الطبقة المحروقة في
 * خيط الكاميرا، وقرار تقسيم المقطع لحظة انتهاء التسجيل) تحتاج القيمة **الآن** بلا
 * تعليق. الكتابة وحدها غير متزامنة.
 *
 * القيم الابتدائية هي الافتراضات نفسها، فالإقلاع لا ينتظر القرص: تُقرأ القيمة
 * المحفوظة بعد أوّل دورة وتُبدَّل. هذا يُظهر وميض سمة قصيرًا في أسوأ الحالات، وهو
 * أهون من تعطيل أوّل إطار.
 */
class AppSettings(context: Context, private val scope: CoroutineScope) {

    private val store = context.applicationContext.settingsStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        // قرص ممتلئ أو ملفّ تالف: نُكمل بالافتراضات بدل أن يسقط التطبيق عند الإقلاع
        if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
    }

    private fun <T> pref(key: Preferences.Key<T>, default: T): StateFlow<T> =
        data.map { it[key] ?: default }
            .stateIn(scope, SharingStarted.Eagerly, default)

    /**
     * الكتابة تبتلع عطب القرص كما تبتلعه القراءة. بلا هذا كان قرصٌ ممتلئ يُسقط
     * العمليّة عند أوّل مفتاحٍ يُقلَب — وهو بالضبط ما حُصّنت القراءة منه.
     */
    private fun <T> put(key: Preferences.Key<T>, value: T) {
        scope.launch { runCatching { store.edit { it[key] = value } } }
    }

    // ===== المظهر =====

    val themeMode: StateFlow<ThemeMode> =
        data.map { ThemeMode.from(it[KEY_THEME_MODE]) }
            .stateIn(scope, SharingStarted.Eagerly, ThemeMode.DEFAULT)

    fun setThemeMode(mode: ThemeMode) = put(KEY_THEME_MODE, mode.id)

    /** ساعة بدء النهار في الوضع التلقائيّ (0-23) */
    val dayStartHour: StateFlow<Int> = pref(KEY_DAY_START, DEFAULT_DAY_START)
    fun setDayStartHour(hour: Int) = put(KEY_DAY_START, hour.coerceIn(0, 23))

    /** ساعة بدء الليل في الوضع التلقائيّ (0-23) */
    val nightStartHour: StateFlow<Int> = pref(KEY_NIGHT_START, DEFAULT_NIGHT_START)
    fun setNightStartHour(hour: Int) = put(KEY_NIGHT_START, hour.coerceIn(0, 23))

    // ===== القيادة =====

    val keepScreenOn: StateFlow<Boolean> = pref(KEY_KEEP_SCREEN_ON, true)
    fun setKeepScreenOn(enabled: Boolean) = put(KEY_KEEP_SCREEN_ON, enabled)

    /** اسم قيمة [net.gnutux.speedometer.core.profile.VehicleProfile] المختارة */
    val vehicleName: StateFlow<String> = pref(KEY_VEHICLE, "")
    fun setVehicleName(name: String) = put(KEY_VEHICLE, name)

    /**
     * الانكماش إلى نافذة «صورة في صورة» عند مغادرة التطبيق أثناء رحلةٍ أو تسجيل.
     *
     * افتراضه التفعيل: من غادر التطبيق وهو يقيس لم يقصد ترك القياس، وإنّما ردّ مكالمةً
     * أو فتح خريطة. ومع ذلك بقي خيارًا لأنّ النافذة العائمة تحجب جزءًا من الشاشة، ومن
     * يقود بمرافقٍ يمسك الجهاز قد يفضّل اختفاءها.
     *
     * القراءة تقع في `onUserLeaveHint` وهو نداءٌ متزامن لا يحتمل انتظارًا، ولذلك
     * `StateFlow` لا `Flow` هنا كما في سائر التفضيلات.
     */
    val pipOnLeave: StateFlow<Boolean> = pref(KEY_PIP_ON_LEAVE, true)
    fun setPipOnLeave(enabled: Boolean) = put(KEY_PIP_ON_LEAVE, enabled)

    // ===== السرعة القصوى =====

    /**
     * الحدّ الذي يضعه السائق لنفسه (كم/س)، و[NO_SPEED_LIMIT] يعني بلا حدّ.
     *
     * افتراضه «بلا حدّ» عمدًا: من لم يضبط شيئًا يجب ألّا يتبدّل عنده مدى القرص ولا
     * لونه ولا يُصفر له شيء. والميزة كلّها تنبني على هذا المفتاح — القرص والعلامة
     * الحمراء والتنبيه — فمصدره واحد هنا لا نسخةٌ في كلّ شاشة.
     */
    val speedLimitKmh: StateFlow<Int> = pref(KEY_SPEED_LIMIT, NO_SPEED_LIMIT)

    /**
     * السقف ‎300‎ لا لأنّ أحدًا يبلغه، بل لأنّ الحدّ يضبط مدى القرص: قيمةٌ شاذّة
     * تُخرج قوسًا لا يُقرأ. والقاع صفرٌ لأنّه «بلا حدّ» لا حدٌّ منخفض.
     */
    fun setSpeedLimitKmh(v: Int) = put(KEY_SPEED_LIMIT, v.coerceIn(0, 300))

    /**
     * التنبيه الصوتيّ عند التجاوز. مطفأٌ افتراضًا: صوتٌ لم يطلبه أحدٌ في سيّارةٍ
     * صامتة مفاجأةٌ لا خدمة، فليكن الصمت هو الأصل ويُفعّله من يريده.
     */
    val speedAlertEnabled: StateFlow<Boolean> = pref(KEY_SPEED_ALERT, false)
    fun setSpeedAlertEnabled(enabled: Boolean) = put(KEY_SPEED_ALERT, enabled)

    /**
     * النغمة المختارة للتنبيه. خمسٌ لأنّ المقصورة تختلف: صفيرةٌ حادّة تُسمع فوق
     * ضجيج دراجةٍ ناريّة وتُفزع في سيّارةٍ صامتة، وجرسٌ لطيف عكسها.
     */
    val alertTone: StateFlow<AlertTone> =
        data.map { AlertTone.from(it[KEY_ALERT_TONE]) }
            .stateIn(scope, SharingStarted.Eagerly, AlertTone.DEFAULT)

    fun setAlertTone(tone: AlertTone) = put(KEY_ALERT_TONE, tone.id)

    /**
     * شدّة التنبيه من ‎10‎ إلى ‎100‎، وهي **نسبةٌ من مجرى المنبّه** لا مستوًى مطلقًا:
     * مستوى منبّه النظام يبقى السقف، فلا يتجاوز تطبيقٌ ما ضبطه صاحب الجهاز. والقاع
     * ‎10‎ لا ‎0‎ لأنّ الصفر إطفاءٌ صامت، وللإطفاء مفتاحُه المعلن أعلاه.
     */
    val alertVolume: StateFlow<Int> = pref(KEY_ALERT_VOLUME, DEFAULT_ALERT_VOLUME)
    fun setAlertVolume(percent: Int) = put(KEY_ALERT_VOLUME, percent.coerceIn(10, 100))

    // ===== شكل العدّاد =====

    /**
     * تصميم القرص. [GaugeStyle.CLASSIC] هو ما كان قبل 0.9.4 حرفًا بحرف، وهو
     * الافتراضيّ: من لم يختر شيئًا لا يتبدّل عليه شيء.
     */
    val gaugeStyle: StateFlow<GaugeStyle> =
        data.map { GaugeStyle.from(it[KEY_GAUGE_STYLE]) }
            .stateIn(scope, SharingStarted.Eagerly, GaugeStyle.DEFAULT)

    fun setGaugeStyle(style: GaugeStyle) = put(KEY_GAUGE_STYLE, style.id)

    /**
     * حجم رقم السرعة داخل القرص، نسبةً من الحجم المرجعيّ.
     *
     * نسبةٌ لا حجمٌ مطلق: القرص نفسه يتمدّد مع الشاشة، فرقمٌ بحجمٍ ثابتٍ يصير ضخمًا في
     * لوحٍ وضئيلًا في هاتفٍ صغير. والنسبة تحفظ التناسب في الاثنين.
     *
     * والافتراضيّ ‎100‎ — أي ما كان قبل هذا الخيار حرفًا بحرف.
     */
    val speedTextScale: StateFlow<Int> = pref(KEY_SPEED_TEXT_SCALE, DEFAULT_SPEED_TEXT_SCALE)
    fun setSpeedTextScale(percent: Int) =
        put(KEY_SPEED_TEXT_SCALE, percent.coerceIn(MIN_SPEED_TEXT_SCALE, MAX_SPEED_TEXT_SCALE))

    /**
     * اتّجاه الشاشة: تلقائيٌّ يتبع الجهاز، أو طوليٌّ مثبَّت، أو عرضيٌّ مثبَّت.
     *
     * والتلقائيّ هو الافتراضيّ لا الطوليّ: التصوير العرضيّ هو المعتاد لمشاهد الطريق،
     * ومن ثبّت جهازه على المقود عرضًا كان يجد التطبيق يعاند اتّجاهه.
     */
    val screenOrientation: StateFlow<ScreenOrientation> =
        data.map { ScreenOrientation.from(it[KEY_SCREEN_ORIENTATION]) }
            .stateIn(scope, SharingStarted.Eagerly, ScreenOrientation.DEFAULT)

    fun setScreenOrientation(value: ScreenOrientation) =
        put(KEY_SCREEN_ORIENTATION, value.id)

    /** شكل النافذة المصغَّرة: رقمٌ مجرَّد، أو قرصٌ صغير، أو مؤشّر، أو شريط */
    val pipStyle: StateFlow<PipStyle> =
        data.map { PipStyle.from(it[KEY_PIP_STYLE]) }
            .stateIn(scope, SharingStarted.Eagerly, PipStyle.DEFAULT)

    fun setPipStyle(style: PipStyle) = put(KEY_PIP_STYLE, style.id)

    /** حجم محتوى النافذة المصغَّرة داخل إطارها */
    val pipSize: StateFlow<PipSize> =
        data.map { PipSize.from(it[KEY_PIP_SIZE]) }
            .stateIn(scope, SharingStarted.Eagerly, PipSize.DEFAULT)

    fun setPipSize(size: PipSize) = put(KEY_PIP_SIZE, size.id)

    /**
     * خلفيّةٌ شفّافة للنافذة المصغَّرة.
     *
     * مطفأةٌ افتراضًا لأنّ إظهارها ليس مضمونًا على كلّ جهاز: نافذة «صورة في صورة»
     * يركّبها النظام، وبعض الأجهزة ترسم تحتها أسودَ صلبًا مهما فعل التطبيق. فمن
     * يفعّلها يرى النتيجة بعينه، ومن لا يفعّلها لا يفاجَأ بمربّعٍ أسود.
     */
    val pipTransparent: StateFlow<Boolean> = pref(KEY_PIP_TRANSPARENT, false)
    fun setPipTransparent(enabled: Boolean) = put(KEY_PIP_TRANSPARENT, enabled)

    /** كثافة خلفيّة النافذة المصغَّرة من ‎10‎ إلى ‎100‎؛ أقلُّها أشفُّها */
    val pipOpacity: StateFlow<Int> = pref(KEY_PIP_OPACITY, DEFAULT_PIP_OPACITY)
    fun setPipOpacity(percent: Int) = put(KEY_PIP_OPACITY, percent.coerceIn(10, 100))

    // ===== الفيديو =====

    /**
     * الحرق مفعَّلٌ افتراضيًّا منذ 0.4.0: مَن يصوّر رحلته يريد الرقم داخل الملفّ، لا
     * على شاشةٍ لن يراها أحدٌ بعد النزول. والفيديو النظيف يبقى خيارًا بلمسة.
     */
    val burnOverlay: StateFlow<Boolean> = pref(KEY_BURN, true)
    fun setBurnOverlay(enabled: Boolean) = put(KEY_BURN, enabled)

    val recordAudio: StateFlow<Boolean> = pref(KEY_AUDIO, true)
    fun setRecordAudio(enabled: Boolean) = put(KEY_AUDIO, enabled)

    /** طول المقطع بالدقائق، و[SEGMENT_CONTINUOUS] يعني تصويرًا متّصلًا بلا تقسيم */
    val videoSegmentMinutes: StateFlow<Int> = pref(KEY_SEGMENT, SEGMENT_CONTINUOUS)
    fun setVideoSegmentMinutes(minutes: Int) = put(KEY_SEGMENT, minutes)

    /** بدء رحلة تلقائيًّا مع بدء التسجيل، كي يدخل كلّ فيديو سجلَّ الرحلات بمساره */
    val autoTripWithRecording: StateFlow<Boolean> = pref(KEY_AUTO_TRIP, true)
    fun setAutoTripWithRecording(enabled: Boolean) = put(KEY_AUTO_TRIP, enabled)

    // ===== الخريطة والسجلّ =====

    /**
     * أزرار التكبير والملاءمة فوق الخريطة.
     *
     * الإيماءة تكفي من يستطيعها، ولا يستطيعها من يمسك المقود بيدٍ وينظر بعجل. وهي
     * مع ذلك تحجب ركنًا من الخريطة، فمن يفحص مسارًا على طاولةٍ قد يريد الركن. فخيارٌ
     * لا حكم، وافتراضُه الظهور: الأداةُ التي لا تُرى لا يبحث عنها أحد.
     */
    val showMapControls: StateFlow<Boolean> = pref(KEY_MAP_CONTROLS, true)
    fun setShowMapControls(enabled: Boolean) = put(KEY_MAP_CONTROLS, enabled)

    /** قلب ألوان بلاطات الخريطة؛ يليق بالسمة الداكنة ويُتعب العين في الفاتحة */
    val invertMapTiles: StateFlow<Boolean> = pref(KEY_INVERT_TILES, true)
    fun setInvertMapTiles(enabled: Boolean) = put(KEY_INVERT_TILES, enabled)

    /**
     * تفضيل الخريطة المحلّيّة على بلاطات الإنترنت.
     *
     * مفعَّلٌ افتراضًا لأنّ الراكب يخرج من التغطية أكثر ممّا يبقى فيها، وكلّ بلاطةٍ
     * تُجلب تُستهلك حزمةً وبطّاريّة. وبقي خيارًا لأنّ الأرشيف يتقادم: من يريد شارعًا
     * شُقّ بعد تاريخ ملفّه يُطفئه فيعود إلى بلاطاتٍ حيّة.
     *
     * إطفاؤه لا يعني «إنترنت أفضل» بل «إنترنت دائمًا»؛ وتفعيله لا يعني «محلّيّة
     * دائمًا» بل «محلّيّة حيث تغطّي» — والتحقّق من التغطية في
     * [net.gnutux.speedometer.core.map.OfflineMaps.covers].
     */
    val preferOfflineMaps: StateFlow<Boolean> = pref(KEY_PREFER_OFFLINE, true)
    fun setPreferOfflineMaps(enabled: Boolean) = put(KEY_PREFER_OFFLINE, enabled)

    /** مهلة التراجع عن الحذف بالثواني؛ صفر يعني حذفًا فوريًّا */
    val undoSeconds: StateFlow<Int> = pref(KEY_UNDO_SECONDS, DEFAULT_UNDO_SECONDS)
    fun setUndoSeconds(seconds: Int) = put(KEY_UNDO_SECONDS, seconds.coerceIn(0, 60))

    /** تجاوزٌ صريح لترتيب مصادر الخريطة الذي أقرّته 0.7.0 */
    val mapSource: StateFlow<MapSourcePreference> =
        data.map { MapSourcePreference.from(it[KEY_MAP_SOURCE]) }
            .stateIn(scope, SharingStarted.Eagerly, MapSourcePreference.DEFAULT)

    fun setMapSource(source: MapSourcePreference) = put(KEY_MAP_SOURCE, source.id)

    /**
     * حزمةُ تطبيق الخرائط المفضَّل لفتح المسار فيه؛ فراغٌ يعني «اسألني في كلّ مرّة».
     *
     * فراغٌ هو الافتراض عمدًا: منتقي النظام يعرض كلّ ما يقدر على فتح GPX، ومن ثبّت
     * تطبيقًا جديدًا يراه فيه بلا أن يعود إلى إعداداتنا. وإنّما يُثبَّت الاختيار لمن
     * سئم المنتقي في كلّ رحلة.
     */
    val mapAppPackage: StateFlow<String> = pref(KEY_MAP_APP, "")
    fun setMapAppPackage(pkg: String) = put(KEY_MAP_APP, pkg)

    /**
     * حصرُ تنزيل أرشيف الخرائط على الواي‑فاي.
     *
     * مفعَّلٌ افتراضًا: الأرشيف يُقاس بمئات الميغابايت، وتنزيلُه على بيانات الجوّال
     * بلا سؤالٍ يستنزف حزمةً دفع صاحبها ثمنها. ومن أراد غير ذلك يُطفئه بلمسة.
     */
    val mapDownloadWifiOnly: StateFlow<Boolean> = pref(KEY_DL_WIFI, true)
    fun setMapDownloadWifiOnly(enabled: Boolean) = put(KEY_DL_WIFI, enabled)

    /**
     * تأكيدٌ قبل بدء التصوير وقبل إنهائه.
     *
     * مفعَّلٌ افتراضًا: زرّ التسجيل كبيرٌ في وسط الشاشة والهاتف على المقود، فلمسةٌ
     * عارضة تبدأ تصويرًا لا يريده صاحبه أو — وهو الأسوأ — تُنهي تسجيلَ حادثةٍ وهو
     * يحتاجه. ومن سئم المربّع أطفأه.
     */
    val confirmRecording: StateFlow<Boolean> = pref(KEY_CONFIRM_REC, true)
    fun setConfirmRecording(enabled: Boolean) = put(KEY_CONFIRM_REC, enabled)

    // ===== تحديثات التطبيق =====

    /** فحصٌ يوميّ عند فتح التطبيق، وإشعارٌ حين يصدر أحدث */
    val updateNotify: StateFlow<Boolean> = pref(KEY_UPD_NOTIFY, true)
    fun setUpdateNotify(enabled: Boolean) = put(KEY_UPD_NOTIFY, enabled)

    /**
     * قبولُ النسخ التجريبيّة في الفحص.
     *
     * مفعَّلٌ اليوم لأنّ **كلّ** إصداراتنا تجريبيّة؛ ولو كان مطفأً لما وجد الفاحص
     * شيئًا أبدًا. ويصير الفارقَ الحقيقيّ يوم يصدر أوّل إصدارٍ مستقرّ.
     */
    val updateBeta: StateFlow<Boolean> = pref(KEY_UPD_BETA, true)
    fun setUpdateBeta(enabled: Boolean) = put(KEY_UPD_BETA, enabled)

    /** آخر لحظة فحصٍ ناجحة (ساعة الحائط — هذا زمنٌ مدنيّ يُعرض، لا زمن قياس) */
    val updateLastCheck: StateFlow<Long> = pref(KEY_UPD_LAST, 0L)
    fun setUpdateLastCheck(millis: Long) = put(KEY_UPD_LAST, millis)

    /** آخر إصدارٍ أُشعِر به، فلا يُكرَّر الإشعار نفسه كلّ يوم */
    val updateNotifiedTag: StateFlow<String> = pref(KEY_UPD_TAG, "")
    fun setUpdateNotifiedTag(tag: String) = put(KEY_UPD_TAG, tag)

    /**
     * آخر قسمٍ فُتح في شاشة الإعدادات.
     *
     * يُحفظ لا يُنسى مع إغلاق الشاشة: من يضبط حدَّ سرعته ثمّ يخرج ليجرّبه ثمّ يعود
     * ليعدّله يجد قسمه مفتوحًا. وفراغٌ يعني «كلّها مطويّة» وهو حال أوّل فتحة.
     */
    val settingsOpenSection: StateFlow<String> = pref(KEY_OPEN_SECTION, "")
    fun setSettingsOpenSection(id: String) = put(KEY_OPEN_SECTION, id)

    // ===== الكاميرا =====

    val cameraScene: StateFlow<CameraScene> =
        data.map { CameraScene.from(it[KEY_CAMERA_SCENE]) }
            .stateIn(scope, SharingStarted.Eagerly, CameraScene.DEFAULT)

    fun setCameraScene(scene: CameraScene) = put(KEY_CAMERA_SCENE, scene.id)

    /** العدسة المختارة حين تعمل واحدةٌ فقط */
    val cameraLens: StateFlow<CameraLens> =
        data.map { CameraLens.from(it[KEY_LENS]) }
            .stateIn(scope, SharingStarted.Eagerly, CameraLens.DEFAULT)

    fun setCameraLens(lens: CameraLens) = put(KEY_LENS, lens.id)

    /**
     * طلبُ تشغيل الكاميرتين معًا. **طلبٌ لا إخبار**: الجهاز قد لا يدعمه، والقرار
     * الفعليّ في `CameraSession` بعد سؤال `getAvailableConcurrentCameraInfos`.
     */
    val dualCamera: StateFlow<Boolean> = pref(KEY_DUAL, false)
    fun setDualCamera(enabled: Boolean) = put(KEY_DUAL, enabled)

    val dualLayout: StateFlow<DualLayout> =
        data.map { DualLayout.from(it[KEY_DUAL_LAYOUT]) }
            .stateIn(scope, SharingStarted.Eagerly, DualLayout.DEFAULT)

    fun setDualLayout(layout: DualLayout) = put(KEY_DUAL_LAYOUT, layout.id)

    /** أيّ العدستين تملأ الإطار في الوضع المزدوج؛ الأخرى تُصغَّر أو تُناصف */
    val dualPrimary: StateFlow<CameraLens> =
        data.map { CameraLens.from(it[KEY_DUAL_PRIMARY]) }
            .stateIn(scope, SharingStarted.Eagerly, CameraLens.BACK)

    fun setDualPrimary(lens: CameraLens) = put(KEY_DUAL_PRIMARY, lens.id)

    /**
     * وميض الشاشة بديلًا عن مصباحٍ أماميّ لا تملكه أغلب الهواتف.
     *
     * مطفأٌ افتراضًا وبتحذير: الكاميرا الأماميّة في مسجّل طريقٍ موجَّهةٌ إلى وجه
     * السائق، وشاشةٌ بيضاء بإضاءةٍ قصوى في وجهه ليلًا خطرٌ لا ميزة. من أراده
     * لتصويرٍ داخل المركبة وهي واقفة فله ذلك، ولا يُفرض على أحد.
     */
    val screenFlash: StateFlow<Boolean> = pref(KEY_SCREEN_FLASH, false)
    fun setScreenFlash(enabled: Boolean) = put(KEY_SCREEN_FLASH, enabled)

    // ===== الأجهزة المحدودة =====

    val liteMode: StateFlow<LiteMode> =
        data.map { LiteMode.from(it[KEY_LITE_MODE]) }
            .stateIn(scope, SharingStarted.Eagerly, LiteMode.DEFAULT)

    fun setLiteMode(mode: LiteMode) = put(KEY_LITE_MODE, mode.id)

    /**
     * تثبيت الموقع السريع: نعرض آخر موقعٍ معروف ومزوّد الشبكة ريثما تُثبَّت الأقمار.
     *
     * مفعَّلٌ افتراضًا. الرقم التقريبيّ ليس كذبًا ما دام موسومًا بأنّه تقريبيّ، وهو
     * أصدق من شاشةٍ فارغة تُوهم المستعمل أنّ التطبيق لا يعمل — وعلى الأجهزة المحدودة
     * قد يبلغ انتظار أوّل قمرٍ دقيقةً كاملة.
     */
    val fastFirstFix: StateFlow<Boolean> = pref(KEY_FAST_FIX, true)
    fun setFastFirstFix(enabled: Boolean) = put(KEY_FAST_FIX, enabled)

    companion object {
        const val SEGMENT_CONTINUOUS = 0
        const val DEFAULT_DAY_START = 6
        const val DEFAULT_NIGHT_START = 19
        const val DEFAULT_UNDO_SECONDS = 10

        /** أطوال المقاطع المعروضة في الإعدادات */
        val SEGMENT_CHOICES = listOf(SEGMENT_CONTINUOUS, 3, 5, 7, 10)

        /** [speedLimitKmh] بهذه القيمة يعني «بلا حدّ» */
        const val NO_SPEED_LIMIT = 0

        /**
         * الحدود المعروضة في الإعدادات: حدود السرعة الشائعة على اللوحات لا سلّمًا
         * حسابيًّا. من يريد رقمًا خارجها فـ [setSpeedLimitKmh] تقبل أيّ قيمة، وهذه
         * قائمة اختيارٍ بلمسةٍ واحدة لا حصر.
         */
        val LIMIT_CHOICES = listOf(NO_SPEED_LIMIT, 30, 40, 50, 60, 80, 90, 100, 110, 120, 140)

        /** شدّة التنبيه الافتراضيّة: مسموعةٌ فوق ضجيج الطريق ودون أن تُفزع */
        const val DEFAULT_ALERT_VOLUME = 80

        /** كثافة خلفيّة النافذة المصغَّرة الافتراضيّة حين تُفعَّل الشفافيّة */
        const val DEFAULT_PIP_OPACITY = 65

        /**
         * حدود حجم رقم السرعة ‎%‎.
         *
         * الأدنى ‎60‎ لا أقلّ: دونه يصير الرقم أصغر من نصّ الوحدة تحته فينقلب الهرم
         * البصريّ. والأعلى ‎160‎: فوقه يخرج الرقم عن القرص في التصاميم ذات التدريج.
         */
        const val MIN_SPEED_TEXT_SCALE = 60
        const val MAX_SPEED_TEXT_SCALE = 160
        const val DEFAULT_SPEED_TEXT_SCALE = 100

        /** خياراتٌ معروضة لا شريطُ انزلاق: خمسُ درجاتٍ تُختار بقفّاز، والانزلاق لا يُضبط به */
        val SPEED_TEXT_CHOICES = listOf(60, 80, 100, 130, 160)

        /** الشدّات المعروضة في الإعدادات */
        val ALERT_VOLUME_CHOICES = listOf(20, 40, 60, 80, 100)

        /** نِسَب الشفافيّة المعروضة في الإعدادات */
        val PIP_OPACITY_CHOICES = listOf(15, 30, 45, 65, 85, 100)

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DAY_START = intPreferencesKey("day_start_hour")
        private val KEY_NIGHT_START = intPreferencesKey("night_start_hour")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_VEHICLE = stringPreferencesKey("vehicle")
        private val KEY_PIP_ON_LEAVE = booleanPreferencesKey("pip_on_leave")
        private val KEY_BURN = booleanPreferencesKey("burn_overlay")
        private val KEY_AUDIO = booleanPreferencesKey("record_audio")
        private val KEY_SEGMENT = intPreferencesKey("video_segment_minutes")
        private val KEY_AUTO_TRIP = booleanPreferencesKey("auto_trip_with_recording")
        private val KEY_INVERT_TILES = booleanPreferencesKey("invert_map_tiles")
        private val KEY_MAP_CONTROLS = booleanPreferencesKey("show_map_controls")
        private val KEY_PREFER_OFFLINE = booleanPreferencesKey("prefer_offline_maps")
        private val KEY_UNDO_SECONDS = intPreferencesKey("undo_seconds")
        private val KEY_SPEED_LIMIT = intPreferencesKey("speed_limit_kmh")
        private val KEY_SPEED_ALERT = booleanPreferencesKey("speed_alert_enabled")
        private val KEY_CAMERA_SCENE = stringPreferencesKey("camera_scene")
        private val KEY_MAP_SOURCE = stringPreferencesKey("map_source_preference")
        private val KEY_LITE_MODE = stringPreferencesKey("lite_mode")
        private val KEY_FAST_FIX = booleanPreferencesKey("fast_first_fix")
        private val KEY_LENS = stringPreferencesKey("camera_lens")
        private val KEY_DUAL = booleanPreferencesKey("dual_camera")
        private val KEY_DUAL_LAYOUT = stringPreferencesKey("dual_layout")
        private val KEY_DUAL_PRIMARY = stringPreferencesKey("dual_primary_lens")
        private val KEY_SCREEN_FLASH = booleanPreferencesKey("screen_flash")
        private val KEY_MAP_APP = stringPreferencesKey("map_app_package")
        private val KEY_OPEN_SECTION = stringPreferencesKey("settings_open_section")
        private val KEY_DL_WIFI = booleanPreferencesKey("map_download_wifi_only")
        private val KEY_CONFIRM_REC = booleanPreferencesKey("confirm_recording")
        private val KEY_UPD_NOTIFY = booleanPreferencesKey("update_notify")
        private val KEY_UPD_BETA = booleanPreferencesKey("update_beta")
        private val KEY_UPD_LAST = longPreferencesKey("update_last_check")
        private val KEY_UPD_TAG = stringPreferencesKey("update_notified_tag")
        private val KEY_ALERT_TONE = stringPreferencesKey("alert_tone")
        private val KEY_ALERT_VOLUME = intPreferencesKey("alert_volume")
        private val KEY_GAUGE_STYLE = stringPreferencesKey("gauge_style")
        private val KEY_SPEED_TEXT_SCALE = intPreferencesKey("speed_text_scale")
        private val KEY_SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")
        private val KEY_PIP_STYLE = stringPreferencesKey("pip_style")
        private val KEY_PIP_SIZE = stringPreferencesKey("pip_size")
        private val KEY_PIP_TRANSPARENT = booleanPreferencesKey("pip_transparent")
        private val KEY_PIP_OPACITY = intPreferencesKey("pip_opacity")
    }
}

/** العدسة المستعمَلة. القيمة تُحفظ فيعود التطبيق إلى ما تركه المستعمل عليه. */
enum class CameraLens(val id: String) {
    BACK("back"),
    FRONT("front");

    /** الأخرى — التبديل سؤالٌ ثنائيّ فلا يحتاج أكثر من هذا */
    val other: CameraLens get() = if (this == BACK) FRONT else BACK

    companion object {
        val DEFAULT = BACK
        fun from(id: String?): CameraLens = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * تخطيط الكاميرتين حين تعملان معًا.
 *
 * [PIP] هو الافتراض لمسجّل طريق: الطريق هو الموضوع ووجه السائق شاهدٌ عليه، فلا
 * يُعطى نصف الإطار. و[SPLIT] لمن أراد الاثنين بالقدر نفسه.
 */
enum class DualLayout(val id: String) {
    PIP("pip"),
    SPLIT("split");

    companion object {
        val DEFAULT = PIP
        fun from(id: String?): DualLayout = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * وضع تصوير الكاميرا.
 *
 * الليل ليس «أظلمَ» بل **إضاءةٌ أطول**: يُرفع تعويض الإضاءة ويُخفَّض حدُّ الإطارات
 * الأدنى، فيدخل الحسّاسَ ضوءٌ أكثر بثمنِ ضبابةٍ في الحركة. وهي مقايضةٌ لا تصحّ
 * نهارًا، ولذلك لم يكفِ «تلقائيّ» وحده.
 */
enum class CameraScene(val id: String) {
    /** يتبع ساعتَي النهار والليل نفسَهما اللتين تتبعهما السمة */
    AUTO("auto"),
    DAY("day"),
    NIGHT("night");

    companion object {
        val DEFAULT = AUTO
        fun from(id: String?): CameraScene = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * مصدر خريطة الرحلة حين يتوفّر أكثر من واحد.
 *
 * [AUTO] هو الترتيب الذي أقرّته 0.7.0 (أرشيفٌ محلّيّ ← OsmAnd ← إنترنت ← مخطَّط).
 * وما عداه **تجاوزٌ صريح**: صورة OsmAnd أدقّ خريطةً وأفقرُ تفاعلًا، وبلاطات
 * الإنترنت عكسها، والأرشيف المحلّيّ بينهما — فأيّها أفضل سؤالٌ لا جواب واحد له.
 *
 * **لماذا انفصل [ONLINE] عن [OFFLINE] في 0.9.4؟** كان الاثنان قيمةً واحدة
 * (`tiles`) تختار بينهما التغطية، فكان الزرّ يعرض «إنترنت» و«أوسماند» فقط ولا
 * سبيل للمستعمل أن يقول «ارسم من ملفّي أنا». المصادر الثلاثة الآن ثلاثة أسماء،
 * وما لا يتوفّر منها لا يُعرض أصلًا.
 *
 * والمعرّف القديم `tiles` لا يُقابله شيء هنا، فيسقط إلى [DEFAULT]: من ضبط
 * التفضيل قبل 0.9.4 يعود إلى «تلقائيّ» مرّةً واحدة، وهو أسلم من تخمين قصده.
 */
enum class MapSourcePreference(val id: String) {
    AUTO("auto"),

    /** بلاطاتٌ حيّة من الشبكة، ولو كان عنده أرشيفٌ يغطّي الموضع */
    ONLINE("online"),

    /** صورةٌ ساكنة يرسمها OsmAnd من خرائطه المتجهيّة */
    OSMAND("osmand"),

    /** أرشيف البلاطات الذي في مجلّد الخرائط، ولو كانت الشبكة متاحة */
    OFFLINE("offline"),

    /**
     * أرشيف ‎.pmtiles‎ متجهيًّا يرسمه MapLibre عندنا (0.10.0).
     *
     * صنفٌ خامسٌ لا بديلٌ عن `OFFLINE`: النقطيّ والمتجهيّ يتعايشان، ومن عنده أرشيفٌ
     * نقطيٌّ يعمل اليوم لا ينكسر عليه شيء. وحين لا يوجد ‎.pmtiles‎ صالحٌ يعود السلوك
     * إلى ما كان بلا خطأ.
     */
    VECTOR("vector");

    companion object {
        val DEFAULT = AUTO
        fun from(id: String?): MapSourcePreference = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * تصميم قرص العدّاد.
 *
 * ستّة تصاميم لا واحد، لأنّ «العدّاد الجيّد» يختلف باختلاف من يقرأه: راكب الدراجة
 * يلمح لونًا، وسائق السيّارة يقرأ رقمًا، ومن يصوّر يريد أقلَّ ما يمكن من زينة.
 *
 * والقاعدة التي تحكم الستّة جميعًا: **المعنى واحد والشكل مختلف**. المدى والعتبة
 * والحدّ وألوان المناطق تخرج كلّها من [net.gnutux.speedometer.core.alert.SpeedScale]
 * نفسه مهما كان التصميم، فلا يقول تصميمٌ «تجاوزتَ» ويقول آخر «لم تتجاوز».
 */
enum class GaugeStyle(val id: String) {
    /** قوسٌ مدرَّج بأرقام — تصميم ما قبل 0.9.4 بحذافيره */
    CLASSIC("classic"),

    /** وجهٌ تناظريّ بمؤشّرٍ يدور، أقربُ ما يكون إلى عدّاد السيّارة */
    NEEDLE("needle"),

    /** حلقةٌ رفيعة بلا تدريج ورقمٌ كبير في وسطها */
    MINIMAL("minimal"),

    /** قوسٌ مقطَّع إلى شراتٍ تُضاء تباعًا */
    SEGMENTS("segments"),

    /** حلقتان متراكزتان: مسارٌ خارجيّ وتقدّمٌ داخليّ */
    DUAL_RING("dual_ring"),

    /** شريطٌ أفقيّ يمتلئ — أقلُّها ارتفاعًا وأوضحُها في عرضٍ ضيّق */
    BAR("bar");

    companion object {
        val DEFAULT = CLASSIC
        fun from(id: String?): GaugeStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * شكل محتوى النافذة المصغَّرة.
 *
 * ليست هي [GaugeStyle] بعينها، وهذا مقصود: النافذة بعرض إصبعين وتُقرأ بطرف
 * العين، فما يليق بقرصٍ يملأ الشاشة لا يليق بها. تصاميمُها مختصرةٌ عمدًا.
 */
enum class PipStyle(val id: String) {
    /** الرقم وحده ووحدته — شكل ما قبل 0.9.4 */
    NUMBER("number"),

    /** حلقةٌ تحيط بالرقم فتُقرأ النسبة بلا قراءة الرقم */
    RING("ring"),

    /** قوسٌ صغير بمؤشّر */
    NEEDLE("needle"),

    /** شريطٌ رفيع تحت الرقم */
    BAR("bar");

    companion object {
        val DEFAULT = NUMBER
        fun from(id: String?): PipStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * حجم محتوى النافذة المصغَّرة **داخل إطارها**، لا حجم الإطار نفسه: الإطار يحجّمه
 * المستعمل بأصبعيه ويحفظه النظام، ولا يملك التطبيق فرضه. وهذا الإعداد يقرّر كم
 * من الإطار يشغله الرقم.
 */
enum class PipSize(val id: String, val numberFraction: Float) {
    SMALL("small", 0.28f),
    MEDIUM("medium", 0.38f),
    LARGE("large", 0.50f);

    companion object {
        val DEFAULT = MEDIUM
        fun from(id: String?): PipSize = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * نغمة تنبيه تجاوز الحدّ.
 *
 * ملفّاتٌ في `res/raw` لا `ToneGenerator`: النغمة المولَّدة لا تُختار ولا يُضبط
 * مستواها بعد إنشائها، وهما ما طلبه المستعمل. والملفّات الخمسة مجتمعةً دون ‎25‎
 * كيلوبايت لأنّها قصيرةٌ بترميز Vorbis.
 *
 * @param resName اسم المورد في `res/raw` بلا امتداد؛ يُحلّ بـ `getIdentifier`
 *   لا بـ `R.raw.x` كي تبقى الإضافة نغمةً جديدة بملفٍّ ونصٍّ فحسب.
 */
enum class AlertTone(val id: String, val resName: String) {
    /** صفيرةٌ واحدة حادّة — الافتراضيّ، وأقربُ ما يكون إلى نغمة ما قبل 0.9.4 */
    BEEP("beep", "alert_beep"),

    /** نبضتان متتاليتان */
    DOUBLE("double", "alert_double"),

    /** جرسٌ متلاشٍ، أهدؤها */
    CHIME("chime", "alert_chime"),

    /** رنّةٌ رقميّة متعاقبة، أشدُّها إلحاحًا */
    DIGITAL("digital", "alert_digital"),

    /** نبضةٌ خفيفة لمن يريد تذكيرًا لا إنذارًا */
    SOFT("soft", "alert_soft");

    companion object {
        val DEFAULT = BEEP
        fun from(id: String?): AlertTone = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * الوضع المخفَّف للأجهزة المحدودة.
 *
 * ثلاث حالاتٍ لا رايةٌ واحدة: [AUTO] يعني «قرّر عنّي» ويُشتقّ من
 * `ActivityManager.isLowRamDevice`، و[ON] و[OFF] تجاوزٌ صريح. والفرق بين «مطفأٌ
 * لأنّ الجهاز قويّ» و«مطفأٌ لأنّي أطفأتُه» يجب أن يبقى محفوظًا: الأوّل يتبدّل مع
 * الجهاز والثاني لا يتبدّل.
 */
/**
 * اتّجاه الشاشة المطلوب.
 *
 * القيم تُترجَم إلى `ActivityInfo.SCREEN_ORIENTATION_*` في النشاط، ولا تُخزَّن أرقامها:
 * أرقام أندرويد تفصيلُ منصّةٍ قد يتبدّل، والمعرّف النصّيّ عقدُنا مع ملفّ التفضيلات.
 */
enum class ScreenOrientation(val id: String) {
    /** يتبع الجهاز وحسّاسه — وهو الافتراضيّ */
    AUTO("auto"),

    /** طوليٌّ مثبَّت */
    PORTRAIT("portrait"),

    /** عرضيٌّ مثبَّت — وضع التصوير المعتاد لمشاهد الطريق */
    LANDSCAPE("landscape");

    companion object {
        val DEFAULT = AUTO
        fun from(id: String?): ScreenOrientation = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class LiteMode(val id: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off");

    companion object {
        val DEFAULT = AUTO
        fun from(id: String?): LiteMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

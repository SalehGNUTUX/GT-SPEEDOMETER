package net.gnutux.speedometer.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val LIMIT_CHOICES = listOf(NO_SPEED_LIMIT, 30, 50, 60, 80, 90, 100, 110, 120, 140)

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
        private val KEY_PREFER_OFFLINE = booleanPreferencesKey("prefer_offline_maps")
        private val KEY_UNDO_SECONDS = intPreferencesKey("undo_seconds")
        private val KEY_SPEED_LIMIT = intPreferencesKey("speed_limit_kmh")
        private val KEY_SPEED_ALERT = booleanPreferencesKey("speed_alert_enabled")
    }
}

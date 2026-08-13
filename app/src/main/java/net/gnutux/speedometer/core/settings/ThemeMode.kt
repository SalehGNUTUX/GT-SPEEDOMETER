package net.gnutux.speedometer.core.settings

import androidx.annotation.StringRes
import net.gnutux.speedometer.R

/**
 * أوضاع السمة الأربعة.
 *
 * [AUTO_TIME] هو الافتراضيّ لأنّ الجهاز على المقود: النهار يحتاج تباينًا عاليًا على
 * خلفية فاتحة تقاوم وهج الشمس، والليل يحتاج خلفية داكنة لا تنعكس على الزجاج ولا
 * تُعمي العين. ونظام الهاتف وحده لا يعرف أنّ صاحبه يقود.
 */
enum class ThemeMode(val id: String, @StringRes val label: Int, @StringRes val summary: Int) {
    /** فاتح نهارًا وداكن ليلًا، حسب ساعتَي النهار والليل في الإعدادات */
    AUTO_TIME("auto_time", R.string.theme_auto_time, R.string.theme_auto_time_note),

    /** يتبع سمة النظام */
    SYSTEM("system", R.string.theme_system, R.string.theme_system_note),

    /** داكن دائمًا */
    DARK("dark", R.string.theme_dark, R.string.theme_dark_note),

    /** فاتح دائمًا */
    LIGHT("light", R.string.theme_light, R.string.theme_light_note);

    companion object {
        val DEFAULT = AUTO_TIME

        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

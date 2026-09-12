package net.gnutux.speedometer.ui.update

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.gnutux.speedometer.core.settings.AppSettings
import net.gnutux.speedometer.ui.SpeedoViewModel

/*
 * ============================================================================
 *  واجهةُ التحديث الذاتيّ — **نكهة `play`: لا شيء**
 * ============================================================================
 *
 * أسماءٌ أربعةٌ بلا أجساد، هي عينُ أسماء `app/src/libre/.../UpdateSurface.kt`.
 * وبها تُبنى `MainActivity` و`SettingsScreen` بلا سطرٍ شرطيٍّ واحد، ولا يدخل
 * `UpdateChecker` الحزمةَ المرفوعة إلى المتجر أصلًا.
 *
 * **ولماذا لا شيء؟** لأنّ سياسة غوغل بلاي (إساءة استخدام الجهاز والشبكة) تمنع أن
 * يحدّث التطبيقُ نفسه بغير بلاي: لا جلبَ `.apk` ولا تسليمَه لمثبّت النظام. وبلاي
 * نفسه يحدّث، فلا حاجة إلى بديل.
 *
 * **ولا زرَّ «صفحة الإصدارات» هنا أيضًا.** فتحُ المتصفّح على صفحة جيت‌هاب مباحٌ في
 * ذاته، لكنّه يعرض على مستعمل المتجر طريقًا إلى حزمةٍ **موقَّعةٍ بمفتاحٍ آخر**: لا
 * تُحدِّث ما عنده بل يرفضها النظام، فيقف أمام «فشل التثبيت» بلا سبب يفهمه. ومن
 * أراد نكهة جيت‌هاب وجدها في `README` وعلى الموقع.
 *
 * وفراغُ هذا الملفّ مقصودٌ فلا يُملأ: كلُّ ما يُضاف هنا شيفرةٌ لا نظيرَ لها في
 * النكهة الأخرى، وذلك أوّلُ الطريق إلى نكهتين تختلفان في الميزات — وهو ما تمنعه
 * القاعدة التاسعة في `CLAUDE.md`.
 */

/** لا قسمَ تحديثٍ في ترتيب الإعدادات */
internal val UPDATE_SECTION_IDS: List<String> = emptyList()

/** لا شريطَ خبر */
@Composable
internal fun UpdateBanner(vm: SpeedoViewModel, modifier: Modifier = Modifier) = Unit

/** لا فحصَ دوريًّا */
@Composable
internal fun UpdateAutoCheck(settings: AppSettings) = Unit

/** لا بطاقاتٍ في شاشة الإعدادات */
internal fun LazyListScope.updateSection(
    settings: AppSettings,
    openId: String,
    onToggle: (String) -> Unit,
) = Unit

plugins {
    alias(libs.plugins.android.library)
}

/**
 * واجهة OsmAnd الخارجيّة (AIDL) — منسوخةٌ حرفيًّا من وحدة `OsmAnd-api` في مستودع
 * OsmAnd، وهي تحت رخصة غنو العموميّة الثالثة كهذا المشروع. التفصيل والإسناد في
 * `NOTICE-OsmAnd-API.md` بجذر المشروع.
 *
 * ولماذا وحدةٌ مستقلّة لا مجلّدٌ داخل `:app`؟ لثلاثة أسباب:
 * - الشفرة ليست لنا: خلطها بشفرتنا يُضيّع الحدّ بين ما نصونه وما ننسخه، ويُفسد
 *   بيان `CLEANUP.sh` الذي يعدّ ملفّات `:app` واحدًا واحدًا.
 * - تحديثها لاحقًا يصير نسخًا فوق مجلّدٍ واحد لا دمجًا.
 * - `aidl = true` و`java.srcDirs` المخالف للعُرف يبقيان محصورَين هنا.
 *
 * الحزمة كلّها **لا تُستدعى إلّا إن كان OsmAnd مثبَّتًا**؛ وغيابه لا يُعطّل شيئًا.
 */
android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    // البنية هنا هي بنية Android المتعارَفة (`src/main/aidl` و`src/main/java`) لا
    // بنية OsmAnd التي تخلط الصيغتين في شجرةٍ واحدة وتحتاج `sourceSets` مُعاد
    // ضبطها. **متعمَّد**: إعادةُ ضبط `srcDirs` سطرٌ يتبدّل توقيعه بين إصدارات AGP،
    // والبناء هنا يقع على جهاز المطوّر لا عندنا — فالبنية المتعارَفة التي لا تحتاج
    // ضبطًا أصلًا أأمن من سطرٍ أنيق. وطريقة التحديث في `NOTICE-OsmAnd-API.md`.

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}

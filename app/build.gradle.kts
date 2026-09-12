import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * مخازن التوقيع تبقى خارج المستودع. عند غيابها يُبنى بلا توقيع إصدار، فلا يتعطّل
 * البناء على جهاز آخر — ولا يُبنى إصدارٌ للنشر على ذلك الجهاز أيضًا.
 *
 * ومخزنان لا واحد، لأنّ النكهتين تطبيقان في عينَي النظام (انظر `productFlavors`):
 *
 * - `keystore.properties` ← **مفتاح الإصدار الأصليّ** لنكهة `libre`. وهو المفتاح
 *   الذي وُقّع به كلّ ما نُشر على جيت‌هاب منذ ‎0.1.0‎، وضياعُه يعني فقدان القدرة على
 *   تحديث كلّ نسخةٍ منشورة إلى الأبد.
 * - `keystore-full.properties` ← **مفتاح الرفع** لنكهة `play`. وهو مفتاحُ رفعٍ لا
 *   مفتاحُ تطبيق: غوغل بلاي يُعيد توقيع الحزمة بمفتاحه هو (Play App Signing)،
 *   وهذا لا يوقّع إلّا `.aab` الذاهبَ إليه. وضياعُه يُستدرك بطلب إبدالٍ من بلاي،
 *   بخلاف الأوّل.
 */
private fun signingProps(name: String): Properties? {
    val file = rootProject.file(name)
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }
}

val libreProps = signingProps("keystore.properties")
val playProps = signingProps("keystore-full.properties")

android {
    namespace = "net.gnutux.speedometer"

    /**
     * ‎36‎ لا ‎35‎ منذ ‎1.0.0‎: بلاي لا يقبل منذ ‎31‎ غشت ‎2026‎ رفعًا بأقلّ من
     * `targetSdk 36`، و`targetSdk` لا يعلو على `compileSdk`. والنكهتان تُبنيان من
     * الرقم نفسه: نكهةٌ خلف نكهةٍ في سلوك النظام عطبٌ لا يُرى إلّا في الميدان.
     */
    compileSdk = 36

    defaultConfig {
        applicationId = "net.gnutux.speedometer"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.1"

    }

    /**
     * العربيّة وحدها.
     *
     * وكانت `listOf("ar", "en")` ولا `values-en/` في الشجرة: إعلانُ لغةٍ لا ترجمةَ
     * لها يُظهر «الإنجليزيّة» خيارًا في إعدادات لغة التطبيق (أندرويد ‎13‎ فما فوق)
     * ثمّ لا يتبدّل حرفٌ حين تُختار. والقاعدة الثامنة: لا يُعلَن ما لا يُملك.
     *
     * وموارد المكتبات الافتراضيّة (`values/` في Compose وMaterial) لا يمسّها هذا:
     * المحذوف ترجماتُ اللغات الأخرى وحدها.
     */
    androidResources {
        localeFilters += listOf("ar")
    }

    /**
     * مفتاحان: واحدٌ لكلّ نكهة.
     *
     * **ويجب أن يسبق هذا القسمُ `productFlavors`** وإلّا ردّت `findByName` عدمًا
     * بصمت فبُنيت حزمةُ إصدارٍ بلا توقيع.
     *
     * **ولا يحمل `buildTypes.release` توقيعًا**: توقيعُ سمة البناء يتقدّم على توقيع
     * النكهة في AGP، فلو وُضع هناك وُقّعت النكهتان بمفتاحٍ واحد — وهو عين ما لا
     * نريد.
     */
    signingConfigs {
        libreProps?.let { p ->
            create("libreRelease") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
        playProps?.let { p ->
            create("playUpload") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    /**
     * نكهتان: مصدرُ التوزيع، لا مستوى الميزات.
     *
     * **والميزات فيهما سواءٌ حرفًا بحرف**، وهذا شرطُ بقاء هذا الانقسام مقبولًا:
     * جُرِّبت في ‎0.10.0‎ نكهتان تختلفان في المحرّك (`lite`/`full`) فأُزيلتا
     * (`docs/تجربة-الخرائط-المتجهية.md`)، والقاعدةُ التاسعة في `CLAUDE.md` تمنع
     * عودتَهما بلا سببٍ جديد. والسبب الجديد سياسةٌ لا هندسة:
     *
     * **تطبيقٌ على غوغل بلاي لا يجوز أن يحدّث نفسه بغير بلاي.** ومحدِّثُنا يجلب
     * `.apk` ويسلّمه لمثبّت النظام بإذن `REQUEST_INSTALL_PACKAGES` — وهو جوهر ما
     * تمنعه سياسة «إساءة استخدام الجهاز والشبكة». فلا حيلة إلّا شجرتا مصدر:
     *
     * | | `libre` | `play` |
     * |---|---|---|
     * | يُوزَّع من | جيت‌هاب · F-Droid | غوغل بلاي |
     * | المعرّف | `net.gnutux.speedometer` | `net.gnutux.speedometer.play` |
     * | المحدِّث الداخليّ | فيه | **ليس فيه مصدرًا ولا بيانًا ولا نصًّا** |
     * | التوقيع | مفتاح الإصدار الأصليّ | مفتاح رفعٍ يُعيد بلاي توقيعه |
     * | الصيغة | `.apk` | `.aab` |
     *
     * **ومعرّفان لا معرّفٌ واحد، وهذا قرارٌ لا رجعة فيه بعد أوّل رفع:** حزمةٌ واحدة
     * بمفتاحين مختلفين لا تُحدَّث من المصدر الآخر بل يرفضها النظام، وتوحيدُ المفتاح
     * يقتضي تسليم مفتاح الإصدار الأصليّ إلى بلاي بلا استرداد. فتطبيقان مستقلّان
     * يُثبَّتان معًا، ولا يمسّ أحدهما مفتاح الآخر ولا مستعمليه.
     */
    flavorDimensions += "store"
    productFlavors {
        create("libre") {
            dimension = "store"
            signingConfig = signingConfigs.findByName("libreRelease")
        }
        create("play") {
            dimension = "store"
            // لاحقةٌ على المعرّف لا معرّفٌ مكتوب: سلطةُ `FileProvider` وحقلُ
            // `${applicationId}.files` في البيان يتبعانه من تلقائهما
            applicationIdSuffix = ".play"
            signingConfig = signingConfigs.findByName("playUpload")
        }
    }

    buildTypes {
        release {
            // يبقى التصغير معطّلًا: قواعد التقليم لم تُضبط بعد لـ CameraX و Compose،
            // وعطبٌ يظهر في المصغَّر وحده يصعب تتبّعه. والحزمة `.aab` تُقسَّم على
            // الجهاز فتصل مصغَّرةً بغير R8.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // شاشة «عن التطبيق» تعرض رقم الإصدار من مصدرٍ واحد لا من نصٍّ مكرّر
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // المنطق النقيّ (SpeedAlert · SpeedScale) يُختبر على آلة جافا بلا جهازٍ ولا محاكٍ
    testImplementation(libs.junit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.effects)

    implementation(libs.osmdroid.android)

    // واجهة OsmAnd الخارجيّة: تُستدعى وقت التشغيل إن كان OsmAnd مثبَّتًا، وغيابه
    // لا يعطّل شيئًا — الربط بالخدمة يفشل بهدوء فيعود التطبيق إلى مساره الخاصّ
    implementation(project(":osmand-api"))
}

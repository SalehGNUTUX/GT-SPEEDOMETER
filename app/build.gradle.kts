import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// مفاتيح التوقيع تبقى خارج المستودع. عند غيابها يُبنى بلا توقيع إصدار،
// فلا يتعطّل البناء على جهاز آخر.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val keystoreFullPropsFile = rootProject.file("keystore-full.properties")
val keystoreFullProps = Properties().apply {
    if (keystoreFullPropsFile.exists()) keystoreFullPropsFile.inputStream().use { load(it) }
}

android {
    namespace = "net.gnutux.speedometer"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.gnutux.speedometer"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.9.6-beta"

    }

    androidResources {
        localeFilters += listOf("ar", "en")
    }

    /**
     * مفتاحٌ لكلّ نكهة.
     *
     * النكهتان تطبيقان مستقلّان على الجهاز (معرّفان مختلفان)، فلكلٍّ مفتاحُه: توقيعٌ
     * واحدٌ لتطبيقين مستقلّين يعني أنّ تسريب مفتاحٍ واحدٍ يمسّهما معًا.
     *
     * **وضياع أيٍّ من المفتاحين يعني فقدان القدرة على تحديث كلّ نسخةٍ منشورةٍ منه إلى
     * الأبد.** انسخهما خارج جهاز التطوير: `gt-speedometer-release.jks` و
     * `gt-speedometer-full.jks` ومعهما ملفّا خصائصهما.
     */
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        if (keystoreFullPropsFile.exists()) {
            create("releaseFull") {
                storeFile = rootProject.file(keystoreFullProps.getProperty("storeFile"))
                storePassword = keystoreFullProps.getProperty("storePassword")
                keyAlias = keystoreFullProps.getProperty("keyAlias")
                keyPassword = keystoreFullProps.getProperty("keyPassword")
            }
        }
    }

    /**
     * نكهتان: **خفيفة** و**كاملة**.
     *
     * `libmaplibre.so` وحدها ‎21‎ م.ب لمعماريّتَي ARM، فالحزمة تقفز من ‎13‎ إلى ‎35‎ —
     * أي أكثر من ضعف، وهو تجاوزٌ لمعيار القبول في خارطة الطريق. وأكثر المستعملين لا
     * يحتاج المتجهيّ: من يقيس سرعته على دراجةٍ لا يفتح خريطةً كاملةً لبلد.
     *
     * فالخفيفة هي ما كان بحذافيره — لا مكتبة ولا شيفرة متجهيّة أصلًا — والكاملة تزيدها
     * المحرّك. ولا يدفع أحدٌ ثمن ما لا يستعمل.
     *
     * والشيفرة المتجهيّة كلُّها في `src/full/`، عدا [VectorMaps] فهي في `main`: لا
     * تستورد MapLibre بحال (فحصُ توقيعٍ وبناءُ نصّ)، وتحتاجها الخفيفة لتصنيف الملفّات.
     */
    flavorDimensions += "engine"
    productFlavors {
        /**
         * الخفيفة تحتفظ بالمعرّف الأصليّ `net.gnutux.speedometer` **عمدًا**.
         *
         * كلُّ من ثبّت التطبيق قبل هذا الإصدار يحمل هذا المعرّف، وحجم ما عنده ‎13‎ م.ب
         * بلا محرّكٍ متجهيّ — وهو الخفيفة بعينها. فلو أُعطيت لاحقةً لصار كلُّ مستعملٍ
         * قائمٍ يتيمًا: تطبيقه لا يوافق أيّ حزمةٍ نُصدرها بعدُ، ولا يصله تحديثٌ أبدًا.
         *
         * وبهذا يبقى التحديث متّصلًا لمن كان، ولا تتضاعف حزمةُ أحدٍ في ظهره.
         */
        create("lite") {
            dimension = "engine"
            versionNameSuffix = "-lite"
            signingConfig = signingConfigs.findByName("release")
        }

        /**
         * والكاملة تطبيقٌ مستقلّ بمعرّفه ومفتاحه.
         *
         * فتتعايشان على الجهاز الواحد: يجرّب المستعمل المتجهيّ دون أن يمسّ ما عنده،
         * وإن لم يعجبه أزاله وبقيت رحلاته كما هي. وثمنُه أنّهما تطبيقان لا واحد —
         * فتفضيلاتهما وملفّاتهما منفصلة، وهو ما يقتضيه استقلالهما.
         */
        create("full") {
            dimension = "engine"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            signingConfig = signingConfigs.findByName("releaseFull")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }


    buildTypes {
        release {
            // يبقى التصغير معطّلًا في النسخ التجريبية: قواعد التقليم لم تُضبط بعد
            // لـ CameraX و Compose، وعطبٌ يظهر في التجريبيّ وحده يصعب تتبّعه
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // **لا توقيعَ هنا.** توقيعُ نوع البناء يتقدّم على توقيع النكهة في AGP،
            // فلو بقي لوُقّعت النكهتان بمفتاحٍ واحد — وهو نقيض استقلالهما. والتوقيع
            // معلَنٌ في كلّ نكهةٍ على حدة أعلاه.
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
    "fullImplementation"(libs.maplibre.android)

    // واجهة OsmAnd الخارجيّة: تُستدعى وقت التشغيل إن كان OsmAnd مثبَّتًا، وغيابه
    // لا يعطّل شيئًا — الربط بالخدمة يفشل بهدوء فيعود التطبيق إلى مساره الخاصّ
    implementation(project(":osmand-api"))
}

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
        create("lite") {
            dimension = "engine"
            // لا لاحقةَ في المعرّف: النكهتان تطبيقٌ واحد، وتبديلُ المعرّف يجعل
            // الترقية من إحداهما إلى الأخرى تثبيتًا جديدًا تضيع معه التفضيلات
            versionNameSuffix = "-lite"
        }
        create("full") {
            dimension = "engine"
            versionNameSuffix = "-full"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }


    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // يبقى التصغير معطّلًا في النسخ التجريبية: قواعد التقليم لم تُضبط بعد
            // لـ CameraX و Compose، وعطبٌ يظهر في التجريبيّ وحده يصعب تتبّعه
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

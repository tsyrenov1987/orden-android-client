import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "club.orden"
    compileSdk = 36

    defaultConfig {
        applicationId = "club.orden"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "1.9.18"
    }

    // Per-ABI APKs (each ~55 MB vs the 160 MB universal). Enabled only with -PabiSplit so the
    // default debug builds stay universal for emulator testing.
    splits {
        abi {
            isEnable = project.hasProperty("abiSplit")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Сжимаем нативные .so в APK (libbox.so от sing-box = 52 МБ, хранилась НЕСЖАТОЙ). Go-бинарь
    // жмётся ~2.5-3× → APK для скачивания ~61→~30 МБ, что критично для РФ (Cloudflare-троттлинг
    // ~16 КБ/соединение). Чисто упаковка: .so байт-в-байт идентична в рантайме (распаковывается в
    // /data при установке) → НОЛЬ влияния на подключение/качество. Цена — распаковка при установке
    // (кратковременный расход диска), для тяжёлого скачивания в РФ выгодный размен.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file("orden-release.jks")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = false
        disable += setOf(
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            "OldTargetApi", "UseTomlInstead", "MonochromeLauncherIcon"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // sing-box VPN core, built from core/sing-box (v1.13.14) via gomobile. arm64-only for now.
    implementation(files("libs/libbox.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

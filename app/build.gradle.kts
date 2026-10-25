plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号由 CI 的 tools/bump-version.sh 自动叠加（推 main → 基于 v* tag 判断 patch+1）。
// 手动升版本：直接改这里（versionName 领先最新 tag 时 CI 直接按它发版），记得 versionCode 也 +1。
val APP_VERSION_NAME = "1.0.0"
val APP_VERSION_CODE = 1

// 签名：CI 从 GitHub Secrets 注入；本地没配环境变量时 release 用 debug 签名（可安装，便于验证）。
// 注意不能用同名属性：SigningConfig 里有 keyAlias / keyPassword，会遮蔽外层 val 导致自赋值。
val ksPath: String? = System.getenv("FRIDAY_KEYSTORE_PATH")
val ksStorePass: String? = System.getenv("FRIDAY_KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("FRIDAY_KEY_ALIAS")
val ksKeyPass: String? = System.getenv("FRIDAY_KEY_PASSWORD")

android {
    namespace = "com.integemjack.friday"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.integemjack.friday"
        minSdk = 26
        targetSdk = 35
        versionName = APP_VERSION_NAME
        versionCode = APP_VERSION_CODE
        vectorDrawables { useSupportLibrary = true }
    }

    if (ksPath != null && ksStorePass != null && ksAlias != null && ksKeyPass != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null && ksStorePass != null && ksAlias != null && ksKeyPass != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsch)
    debugImplementation(libs.androidx.ui.tooling)
}

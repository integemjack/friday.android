import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 语音唤醒（说「星期五」）：sherpa-onnx 的关键词检测。库（AAR）和模型都从 sherpa-onnx 的 GitHub 发布页下，
// 校验 SHA-256，缓存在 ~/.gradle/caches/friday-kws（clean 不删；CI 每次新下，约 70 MB）。
// AAR 里有 4 种 ABI 的原生库，只打 arm64（见下面 packaging）
val kwsCache = File(gradle.gradleUserHomeDir, "caches/friday-kws")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

fun fetchKws(url: String, sha: String): File {
    val file = File(kwsCache, "$sha/${url.substringAfterLast('/')}")
    if (file.isFile && sha256(file) == sha) return file
    file.parentFile.mkdirs()
    val part = File(file.parentFile, file.name + ".part")
    // GitHub 的下载偶尔握手失败 / 断流：最多试 4 次
    for (attempt in 1..4) {
        logger.lifecycle("语音唤醒：下载 $url（第 $attempt 次）")
        val ok = runCatching {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
        }
        if (ok.isSuccess) break
        if (attempt == 4) throw ok.exceptionOrNull()!!
        Thread.sleep(3_000L * attempt)
    }
    val got = sha256(part)
    check(got == sha) { "${file.name} 的 SHA-256 不对：$got（应为 $sha），删掉 ${part.parent} 重试" }
    check(part.renameTo(file)) { "挪不过去：$part" }
    return file
}

val sherpaAar = fetchKws(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
    "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575",
)
val kwsModel = fetchKws(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2",
    "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6",
)

// 模型包里有好几种变体：只要 chunk-8 的 int8 编码器 / 连接器 + 解码器 + 词表（共约 5.4 MB），放进 assets/kws/。
// 唤醒词表 keywords.txt 在 src/main/assets/kws/ 里（跟代码一起改）
val kwsAssetsDir = layout.buildDirectory.dir("generated/kws-assets")
val kwsAssets = tasks.register<Sync>("kwsAssets") {
    val tag = "epoch-13-avg-2-chunk-8-left-64"
    val names = mapOf(
        "encoder-$tag.int8.onnx" to "encoder.onnx",
        "decoder-$tag.onnx" to "decoder.onnx",
        "joiner-$tag.int8.onnx" to "joiner.onnx",
        "tokens.txt" to "tokens.txt",
    )
    from(tarTree(resources.bzip2(kwsModel))) {
        include(names.keys.map { "*/$it" })
        eachFile { path = "kws/" + names.getValue(name) }
        includeEmptyDirs = false
    }
    into(kwsAssetsDir)
}

// 版本号由 CI 的 tools/bump-version.sh 自动叠加（推 main → 基于 v* tag 判断 patch+1）。
// 手动升版本：直接改这里（versionName 领先最新 tag 时 CI 直接按它发版），记得 versionCode 也 +1。
val APP_VERSION_NAME = "1.0.28"
val APP_VERSION_CODE = 29

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
    testOptions {
        // Robolectric 跑 Compose 界面测试要能读到资源 / manifest
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            // 语音唤醒的原生库只打 arm64（现在的手机都是；别的 ABI 上设置里写「这台手机不支持」）
            excludes += "lib/armeabi-v7a/libsherpa-onnx-jni.so"
            excludes += "lib/x86/libsherpa-onnx-jni.so"
            excludes += "lib/x86/libonnxruntime.so"
            excludes += "lib/x86_64/libsherpa-onnx-jni.so"
            // 原生库压缩着放：不压的话光 arm64 的唤醒库就让安装包大 24 MB（自动更新要下的就是它）
            useLegacyPackaging = true
        }
    }
    sourceSets["main"].assets.srcDir(kwsAssetsDir)
}

tasks.named("preBuild") { dependsOn(kwsAssets) }

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
    // WebRTC 工作通道（REMOTE.md §2）：SChat 信令走 okhttp（HTTPS + WebSocket），
    // 数据通道用 stream 的 WebRTC 构建（org.webrtc 的 API，和 Secret-Chat 安卓端同一个库）。
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(libs.zxing.core)
    // 语音唤醒：sherpa-onnx 关键词检测（文件顶上下载、校验）
    implementation(files(sherpaAar))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

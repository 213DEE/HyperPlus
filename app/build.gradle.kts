plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.dsr213.hyperplus"
    // ★ compileSdk 必须 37：MiuiX 0.9.4 的 aar 元数据写明
    //   依赖方必须 "compile against version 37 or later of the Android APIs"
    //   （实测：compileSdk 36 时 checkDebugAarMetadata 报 16 条此类错误）
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.dsr213.hyperplus"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        // ★ Alpha 阶段统一带 -Alpha 后缀。VersionChecker 会解析这个字符串
        //   去和 GitHub Release 的 tag_name 比较（见 VersionChecker.parse）
        // 0.4.0：引擎搬进 SystemUI 常驻（解掉"回桌面被系统接管"）+ 跨进程配置镜像
        versionName = "0.4.0-Alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 保留 viewBinding：AngleGaugeView 仍是自定义 View，用 AndroidView 包进 Compose
        viewBinding = true
        // ★ 版本检测要读 BuildConfig.VERSION_NAME
        buildConfig = true
    }

    packaging {
        jniLibs {
            // ★★ 必须让 so **真正解包**到 nativeLibraryDir。
            //   原因（探针实测，非推断）：本 App 同时是一个 LSPosed 模块，
            //   注入 SystemUI 后要在**别的进程**里加载 ML Kit 的 native 库。
            //   AGP 默认 useLegacyPackaging=false（lib 以未压缩形式留在 APK 内），
            //   那样 nativeLibraryDir 是**空目录**，注入侧只能靠 ClassLoader 去找 —— 
            //   而"解包后用绝对路径 System.load()"这条路已被探针实测证过（3 个 so 全部 OK）。
            //   代价：装机时多解出约 40MB（4 个 ABI × 4 个 so），仅占 /data。
            useLegacyPackaging = true
        }
    }
}

// Kotlin 2.4 已移除 kotlinOptions，统一走 compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ---------- 基础 ----------
    implementation("androidx.core:core-ktx:1.15.0")
    // appcompat / material 仍被 themes.xml 的 Theme.FaceRotateMvp 引用
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX：只取前摄低分辨率分析流
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // ML Kit 人脸检测：bundled 版，模型打进 APK，不依赖 GMS
    implementation("com.google.mlkit:face-detection:16.1.7")

    // ---------- Compose ----------
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation-android:1.12.0")
    implementation("androidx.compose.ui:ui-android:1.12.0")
    implementation("androidx.compose.ui:ui-graphics-android:1.12.0")
    implementation("androidx.compose.ui:ui-text-android:1.12.0")
    implementation("androidx.compose.runtime:runtime-android:1.12.0")

    // ---------- MiuiX（HyperOS 设计语言，Compose Multiplatform）----------
    // 纯 Android 模块必须用 -android 后缀的 artifact
    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuix")

    // ---------- LSPosed 模块 ----------
    // ★ 本 App 同时是一个 LSPosed 模块：注入 SystemUI 后由它持有相机、判定方向、写屏幕方向。
    //   compileOnly 是刻意的：运行时的 de.robv.android.xposed.* 由 LSPosed 框架在宿主
    //   进程里提供，打进 APK 会造成重复类。
    compileOnly("de.robv.android.xposed:api:82")
}

plugins {
    id("com.android.application")
    // ⛔ 这里**不再**有 `org.jetbrains.kotlin.android`（KGP）—— 2026-10-03 迁移到
    //   **AGP 内置的 Kotlin 支持**（`android.builtInKotlin` 的默认值 true）。
    //   为什么必须去掉它：AGP 9 的官方说明是「KGP 插件与新 DSL 不兼容」，
    //   而旧写法（`android.newDsl=false` + KGP）是在把新 DSL 关掉去迁就插件 ——
    //   那条路 AGP 10 会断。见 gradle.properties 里那段。
    //   ⚠️ Compose 编译器插件仍然要单独声明（它是独立插件，不在内置 Kotlin 里）。
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
        versionCode = 7
        // ★ Alpha 阶段统一带 -Alpha 后缀。VersionChecker 会解析这个字符串
        //   去和 GitHub Release 的 tag_name 比较（见 VersionChecker.parse）
        //
        // ★★ 版本号规矩（用户 2026-10-03 定，**别忘**）：
        //   **发一次 Release 就涨一次号，而且发完立刻涨 —— 不等下次要发版时再涨。**
        //   ⇒ 稳态是**工作区永远领先最新 Release 一个号**（＝"正在开发、尚未发布的那一版"）。
        //   怎么涨：有用户可见的新功能/行为变化 ⇒ 中间位 +1（末位归零）；
        //           只是修 bug / 改文案 / 改文档 ⇒ 末位 +1。
        //   ⚠️ 反面教材（真实发生过）：`v0.4.0-alpha` 发完之后没人推进，这个数字就一直停在 4
        //     ⇒ 用户看到的正是「版本号一直不变」。**别让它再漏第二次。**
        //   ⚠️ 一个号只能发一次：发过的号**封存**，不许重号、不许"改一改重发"、不许只改包不涨号。
        //   ★ 完整流程与自检命令见技能 `github-repo-publish` §7（那里是唯一权威描述）。
        //   ✅ 执行记录：v0.5.0-alpha / v0.6.0-alpha 发布后，均在**同一轮**涨号（没再漏）。
        //
        // 0.4.0：引擎搬进 SystemUI 常驻（解掉"回桌面被系统接管"）+ 跨进程配置镜像
        // 0.5.0：启动熔断（治「装完就崩、系统界面反复重启、进不了桌面」）
        //        ＋ 配置通道改「广播 + 引擎代写」（摆脱对 LSPosed nsp 的依赖，第一步）
        // 0.6.0：迁移到 LSPosed 新 API（libxposed，targetApiVersion 102）——
        //        模块元数据 / 入口 / hook 层整体换血，摘掉"使用了已废弃功能"的横幅
        //        ＋「方向」卡重做（删手动校准与实时角度盘）+ 提示口径修订
        // 0.7.0：开发中，尚未发布
        versionName = "0.7.0-Alpha"

        // ★ 只打 **arm64-v8a 一套**原生库（2026-10-03 加）。
        //
        //   实测：APK 里 4 套 ABI 的 so 合计 31.6MB，而本机（以及 2021 年之后
        //   绝大多数手机）只会加载 arm64-v8a 那一套（8.1MB）——
        //   另外三套（x86 / x86_64 / armeabi-v7a，约 23.5MB）**永远不会被加载**。
        //   ⇒ APK 从 60MB 降到约 37MB，功能一个都不少。
        //
        //   ⚠️ 代价：装不进**纯 32 位**设备（Android 11+ 的纯 32 位机型已经很少见；
        //     真要支持，把 armeabi-v7a 加回来即可，一行）。
        //   ⚠️ 这件事与 `packaging.jniLibs.useLegacyPackaging` **不是一回事**，别混：
        //     那个管"so 解不解包到 nativeLibraryDir"（LSPosed 注入侧要按绝对路径
        //     `System.load`，所以那边必须是 true）；这里管"打进 APK 的是哪几套 ABI"。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // ★ 21 而不是 17：MiuiX 0.9.4 的 aar 全是 JVM 21 字节码（major 65）。
        //   为什么必须跟上、以及 JDK 21 装在哪，见 gradle.properties 的
        //   `org.gradle.java.home` 那一大段（唯一出处，别在这儿再抄一份）。
        //   ⚠️ 降回 17 会让 `entry<Route.X>` 的内联直接编译失败，且单测全部无法加载 main 的类。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // ⛔ 不再开 viewBinding（2026-10-03 关掉）。它此前唯一的理由写着
        //   「AngleGaugeView 仍是自定义 View，用 AndroidView 包进 Compose」——
        //   而 AngleGaugeView 全仓**零引用**（已作为死代码删除），`res/layout/`
        //   也一直是个空目录。开着它只会给每个构建多跑一轮 resource/binding 代码生成。
        //   ⚠️ 将来若真要再加自定义 View，把它打开即可；别顺手开着。
        viewBinding = false
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

    testOptions {
        // ★ 让 `android.jar` 的方法在单测里**返回默认值**，而不是抛 "not mocked"。
        //
        //   加它的**唯一**理由：`ModuleLink.parse` 里要用 `SystemClock.elapsedRealtime()`
        //   算"心跳距今多少秒"，而那个方法是**跨进程契约**的一部分 ——
        //   启动熔断的判据就挂在同一个解析函数上（见 `BootBreakerTest`）。
        //   不在单测里把它钉住，就只能靠真机复现"引擎自己停下来了"这一种状态，
        //   而那正是最难复现的场景。
        //
        //   ⚠️ 本工程其余单测全是纯逻辑，加这一条对它们**没有任何影响**；
        //     它的副作用只是"本该抛异常的 Android 调用会静默返回 0/null"，
        //     所以别用它来掩盖"单测里误用了真 Android API"。
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Kotlin 2.4 已移除 kotlinOptions，统一走 compilerOptions
//
// ★ jvmTarget = 21：跟 `compileOptions` 保持一致（必须一致，理由与 JDK 位置见
//   gradle.properties 的 `org.gradle.java.home` 那一段）。
// ⚠️ 2026-10-03：Kotlin 现在由 **AGP 内置支持**提供（不再有 KGP 插件，见文件头 plugins）。
//   这个顶层 `kotlin { }` 扩展仍然存在、仍然生效（AGP 9.4 内置 Kotlin 提供它），
//   实测编出来的 class 主版本 = 65（JVM 21），与迁移前一致。
//   真正会暴露"jvmTarget 没生效"的地方是 MiuiX 的 `entry<Route.X>` 内联：
//   目标版本低于 21 会直接编译失败（Cannot inline bytecode built with JVM target 21…）。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // ---------- 基础 ----------
    implementation("androidx.core:core-ktx:1.15.0")

    // ---------- 单元测试（只跑 JVM，不涉及 Android 框架）----------
    // ★ 为什么现在才加：投票判据（平票 / 票不足 / 转弯途中分裂）是这一版的核心行为，
    //   而它的边界情况在真机上**不可控**（"脸转了多少度"没法精确复现）。
    //   纯逻辑（VoteTally）抽出来用 JUnit 钉死，比"真机上看着对"可靠得多。
    testImplementation("junit:junit:4.13.2")

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
    // ★ miuix-blur：HyperOS 4「液态玻璃」的实现基础。库自带 blur() / progressiveBlur() /
    //   BloomStroke 高光 / rememberDeviceTilt()（重力跟随光源）。
    //   ⚠️ 2026-09-29 更正：这里原来写着"库自己用 isRuntimeShaderSupported() 兜底，
    //      所以 minSdk = 30 照样能编译能跑" —— **两处都不对**：
    //      ① **编译期**根本过不去：这个 aar 的 AndroidManifest 声明 `minSdkVersion 33`
    //         （同版本其它 miuix 模块都是 23/24，**只有 blur 是 33**），
    //         minSdk 30 会让 `processDebugMainManifest` 直接报清单合并失败；
    //         越过它的办法是 `AndroidManifest.xml` 的
    //         `tools:overrideLibrary="top.yukonga.miuix.kmp.blur"`。
    //      ② override 之后**库的兜底不能依赖**：兜底语义是"效果没了"，而我们要的是
    //         "外观退化成实心、导航照常可用"（底栏整块不画 = 应用没法用）。
    //         ⇒ 调用侧自己要显式判 `SDK_INT >= BLUR_MIN_SDK`，见 ui/LiquidGlassBar.kt
    //           与 ui/HyperPlusApp.kt 里那两道**成对**的闸。
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuix")
    // ★ miuix-nav：MiuiX 自己的导航栈，带 HyperOS 原生转场 + 侧滑返回 + 预测性返回。
    //   用它而不是 androidx.navigation，是为了让二级页的入场/退场动画与系统一致。
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:$miuix")

    // ---------- LSPosed 模块 ----------
    // ★ 本 App 同时是一个 LSPosed 模块：注入 SystemUI 后由它持有相机、判定方向、写屏幕方向。
    //
    // ★★ 2026-10-03 换到**新 API（libxposed，targetApiVersion 102）**：
    //   旧依赖是 `de.robv.android.xposed:api:82`（legacy）。迁移后**一行 legacy 引用都不剩**
    //   —— 这不是风格问题：`targetApiVersion >= 102` 的模块在 **classloader 层**就被禁掉
    //   legacy 包（`XposedHelpers` / `XSharedPreferences` / `XposedBridge.log` 全部不可用），
    //   留着旧依赖只会在运行期抛 `NoClassDefFoundError`。
    //   ⚠️ 顺带摘掉模块页那条「此模块使用了已废弃且即将移除的功能」横幅
    //     —— 那条横幅的判据是"legacy + 声明支持 nsp + 存在 others 可读的 xml"，
    //     三个条件这次一起消失（清单 meta-data 删了、`MODE_WORLD_READABLE` 不用了）。
    //
    // ★ `compileOnly` 是刻意的（与旧依赖同理）：运行时的 libxposed 类由 LSPosed 框架
    //   在宿主进程里提供，打进 APK 会造成重复类。
    //   ⚠️ `isMinifyEnabled = false`（见 buildTypes）⇒ **不需要** libxposed 官方那三条 R8 规则；
    //     将来若开了混淆，必须补上 `-keep ... extends io.github.libxposed.api.XposedModule` 与
    //     `-adaptresourcefilecontents META-INF/xposed/java_init.list`（入口类是**按名字**找的）。
    compileOnly("io.github.libxposed:api:102.0.0")
}

// 顶层构建文件：只声明插件版本，不 apply
//
// ★ 工具链版本依据（全部来自官方兼容表 + 本机实测，非猜测）：
//   MiuiX 0.9.4 的 POM 声明依赖 kotlin-stdlib 2.4.20 + org.jetbrains.compose.foundation 1.12.0
//   ⇒ 而 androidx.compose 1.12.0 的 aar 元数据写明「requires AGP 9.1.0 or higher」
//   （实测：用 AGP 8.13.2 构建直接失败，报 9 条 "requires Android Gradle plugin 9.1.0 or higher"）
//   官方兼容表：AGP 9.1.x → 最低 Gradle 9.3.1 / 最低 JDK 17 / Build Tools 36.0.0 / 最高 API 36.1
//   ⇒ 因此本工程工具链锁定为：
//        AGP 9.1.0 + Gradle 9.3.1 + JDK 17 + compileSdk 36 + Kotlin 2.4.20
//   ⚠️ 关键死结与解法（实测两次失败才定位）：
//     MiuiX 0.9.4 的 aar 元数据要求使用方 **compileSdk >= 37**，
//     但 AGP 9.1 最高只支持 API 36.1 ⇒ 必须用 AGP 9.4.0（支持 API 37）
//     ⇒ AGP 9.4 对应 Gradle 9.6.0（此组合已在 WeType_UI_Enhanced 工程验证过）
//   ⇒ 因此本工程工具链锁定为：
//        AGP 9.4.0 + Gradle 9.6.0 + JDK 21 + compileSdk 37 + Kotlin 2.4.20
//   ⚠️ JDK 为什么是 21 而不是官方最低要求的 17：MiuiX 0.9.4 的**全部** class 文件都是
//     JVM target 21（major 65），17 编不过 —— 完整推导见 gradle.properties 里那段。
plugins {
    id("com.android.application") version "9.4.0" apply false
    // ⛔ 不再声明 `org.jetbrains.kotlin.android`（KGP）：Kotlin 由 AGP 内置支持提供，
    //   版本跟着 AGP 走。⛔ 连"降级回 KGP"也不行 —— 见 gradle.properties 里那段迁移记录。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

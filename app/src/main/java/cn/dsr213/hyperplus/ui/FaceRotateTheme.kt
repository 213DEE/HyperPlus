package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全应用统一的 MiuiX 主题包裹。
 *
 * ★ API 依据（来自 miuix-ui 的 javap 反查，非猜测）：
 *   - `MiuixTheme(controller: ThemeController, textStyles: TextStyles, content)`
 *   - `ThemeController(colorSchemeMode, lightColors, darkColors, keyColor,
 *      colorSpec, paletteStyle, isDark)`，各参数均有默认值
 *   - `ColorSchemeMode` 枚举：System / Light / Dark / MonetSystem / MonetLight / MonetDark
 *   - `ThemeColorSpec` 枚举：Spec2021 / Spec2025
 *   - `ThemePaletteStyle` 枚举：TonalSpot / Neutral / Vibrant / Expressive / Rainbow /
 *      FruitSalad / Monochrome / Fidelity / Content
 *
 * ============================ 为什么用 `System` 而不是 `MonetSystem`（2026-09-30 改）============================
 * ⛔ 原来是 `MonetSystem`（Android 12+ 跟壁纸动态取色）。**那是这次"配色很难看"的直接原因**：
 *   壁纸是暖色调，于是整套界面被染成**土黄 / 卡其色**，主题色、选中态、勾选标记全是黄的。
 *   真机截图核对过（`_probe/ui/before.png`，用户评价「难看」）。
 *
 * ★ Monet（Material You）的设计意图是"让界面跟着壁纸走"，但它有两个对**本工程**致命的副作用：
 *   ① **不可控** —— 配色由用户的壁纸决定，我们没法保证它在任何壁纸上都好看、可读；
 *   ② **品牌感归零** —— 界面上没有一个属于本应用的颜色，看起来像"系统里随便一个什么页"。
 *
 * ⇒ `ColorSchemeMode.System` = **跟随系统深/浅色，但用 MiuiX 内置的那套小米规范配色**。
 *   这是"按小米开发规范配色"最直接的落法：那套色板本来就是按 HyperOS 规范调的，
 *   而且**与壁纸解耦** ⇒ 观感可预期、可复现。
 *
 * ⚠️ 别改回 Monet 系列。想要更鲜明的品牌感，正确的手段是用 `ThemeController` 的
 *   `keyColor`（Monet 取色的种子色）/ `paletteStyle`，而不是换回"跟着壁纸走"。
 */
@Composable
fun FaceRotateTheme(content: @Composable () -> Unit) {
    // ⚠️ 位置参数（不是命名参数）：`colorSchemeMode` 是构造函数的**第一个**参数，
    //   而参数名跨 MiuiX 版本没有强保证 —— 位置传参让这段在库升级时更不容易编译不过。
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

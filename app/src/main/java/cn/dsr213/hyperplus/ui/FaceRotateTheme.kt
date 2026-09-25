package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全应用统一的 MiuiX 主题包裹。
 *
 * ★ API 依据（来自 miuix-ui-android-0.9.4.aar 的 javap 反查，非猜测）：
 *   - `MiuixTheme(controller: ThemeController, textStyles: TextStyles, content)`
 *   - `ThemeController(colorSchemeMode: ColorSchemeMode, ...)` 各参数均有默认值
 *   - `ColorSchemeMode` 枚举：System / Light / Dark / MonetSystem / MonetLight / MonetDark
 *
 * 用 MonetSystem：Android 12+ 走壁纸动态取色（HyperOS 观感一致），
 * 低于 12 时 MiuiX 内部会回落，不需要自己判版本。
 */
@Composable
fun FaceRotateTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = controller, content = content)
}

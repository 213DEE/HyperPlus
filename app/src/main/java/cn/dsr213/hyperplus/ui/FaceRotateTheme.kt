package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * ========================================================================
 * ★★★ 工程铁律：**所有 UI 设计都必须遵循 HyperOS 设计规范**（2026-10-05 立）
 * ========================================================================
 *
 * 用户原话（2026-10-05）：
 *   「高温保护的确认按钮和二次确认的按钮**不符合 HyperOS 的 UI 设计规范**，
 *     优化一下，**并且写入准则，所有 UI 设计都要遵循设计规范**。」
 *
 * ⇒ 这条不是"建议"，是**做界面的前置条件**：任何新增/修改的界面元素，
 *   动手之前先在本节里找它属于哪一类；找不到对应的"规范形状"，**先问用户**，
 *   ⛔ 不许自己发明一个"看起来差不多"的控件。
 *
 * ------------------------------ 为什么必须写死这条 ------------------------------
 * ★ 本工程是**跑在 HyperOS 上的系统增强**：用户天天看系统设置、控制中心、系统弹窗，
 *   他脑子里那把尺子是 HyperOS 的形状。我们自创的控件（哪怕功能正确）会立刻显得"不像
 *   这个系统里的东西"—— 这是**观感上的坏了**，而且不会被任何单测抓到。
 * ★★ 实证：2026-10-05 的「高温保护」卡就是反面例子 ——
 *   ① 输入框的「确认」写成了**裸 `Text` + `clickable`**（无按压态、无最小触控高度、
 *      不满宽、无间距）；
 *   ② 二次确认弹窗的确认按钮写成了**实心红底 `Button`**（HyperOS 弹窗不用实心按钮）；
 *   ③ 「恢复默认」用带 `>` 的 `ArrowPreference`（**动作**被画成了**可跳转行**）。
 *   三条都是"功能对、形状错"。⇒ 从此以后按下面的清单来。
 *
 * ------------------------------ 规范形状清单（照这个写） ------------------------------
 *
 * 【一、按钮】
 *  ① **卡片内按钮 → `TextButton` + [BTN_SLOT]**（满宽 + 上边距 8dp）。
 *     [BTN_SLOT] 就在本包 `UiCommon.kt` 里，全工程一处定义。
 *     ⛔ 别用裸 `Text` + `clickable`：没有 HyperOS 的按压态，也没有最小触控高度。
 *     ⛔ 别在页面文件里另定义一个同义的 val。
 *  ② **动作的"重量"要分级**：
 *     - **同级动作**（同一张卡里两三个并列，都是"改这个值"）→ **默认 `TextButton`**
 *       （浅灰底 + 深色字），⛔ 别一深一浅：那会让用户以为深色那个"才是必须按的"。
 *     - **页面的主要 CTA**（一页只有一个，如捐赠卡的「保存二维码到相册」）→
 *       `TextButton(colors = ButtonDefaults.textButtonColorsPrimary())`（主色填充 + 反白字）。
 *     ⚠️ **名字骗人**：`textButtonColorsPrimary()` 听起来像"主色**字**"，实际是
 *       **主色填充 + 反白字**（javap 反查到的是 `getPrimary` / `getOnPrimary`）——
 *       2026-10-05 就因此把它错用在「温度上限 / 确认」上，已改回默认。
 *     ⛔ 也别自己写 `color = primary` 的裸字（禁用态/按压态/底色全丢）。
 *  ③ **弹窗里的按钮** → **恒为 `TextButton`，一左一右两个，`Arrangement.spacedBy(8.dp)`**。
 *     范例 = `UpdateDialog.kt`。
 *     ⛔ **不用实心 `Button` 当"危险确认"**：HyperOS 的弹窗里，"重"由**位置**
 *     （确认在右、离拇指近）和**文案**（正文写清后果）表达，不靠涂红底 ——
 *     涂红底会在弹窗里造出第二个视觉焦点，跟标题抢注意力。
 *  ④ **取消在左、确认在右**：误触时手指落到的是"取消"。
 *     ⛔ 别把危险动作的确认挪到左边。
 *  ⑤ **互斥取值**（选一个）→ `RadioButtonPreference`；**真开关** → `SwitchPreference`。
 *     ⛔ 别拿开关表达"二选一"（那会凭空多出一个"都不选"态）。
 *
 * 【二、颜色】
 *  ⑥ **一律走主题**：`MiuixTheme.colorScheme.*`（`primary` / `error` / `onSurface` /
 *     `onSurfaceVariantSummary` / `disabledPrimary` …）。
 *     ⛔ **绝不写死 `#FF…` / `Color.Red`**：深浅色模式各有一套正确值，写死必在其中
 *     一个模式下刺眼到看不清。
 *  ⑦ **红色只用于"真错误/真危险"**，用 `colorScheme.error`（红字）或
 *     `CardDefaults.defaultColors(color = colorScheme.errorContainer)`（红底卡，
 *     范例 = [OuterScreenBanner]）。⛔ 别拿红色当强调色。
 *
 * 【三、文字】
 *  ⑧ **字号/行高一律走 `MiuixTheme.textStyles.*`**（`main` / `paragraph` / `body1` /
 *     `footnote1` / `title1` …）；说明性正文用 `Note()`（本包 `UiCommon.kt`）。
 *     ⚠️ 只有**弹窗标题**等少数处用显式 `fontSize`，且必须与既有范例一致
 *     （`UpdateDialog` / `ThermalGuardDialog` 的 20.sp SemiBold）。
 *  ⑨ **文案准则**见 `docs/UI_文案*`：说人话、一句话、只讲"我该怎么办"、
 *     ⛔ 不写 markdown、⛔ 不讲内部机制。
 *  ⑨′ **短提示一律走 [cn.dsr213.hyperplus.AppToast]**（2026-10-05 用户点名立）：
 *     `AppToast.show(ctx, "…")` / `AppToast.showLong(ctx, "…")` —— 系统原生 Toast，
 *     位置/外观/时长全交给系统。
 *     ⛔ **别写 `Toast.makeText(...).show()`**：绕过线程保障（回调里可能在非主线程 ⇒ 直接崩）
 *        与去重表（连折几下会叠好几条）。
 *     ⛔ **别自绘浮层**：本工程曾有一个 `SplitLimitOverlay`，2026-10-05 按用户点名删掉。
 *     ★ 两个进程都能调，但**只有引擎所在的 SystemUI 系统进程**弹得出"App 不在前台时"的提示
 *        （App 进程弹 Toast 会被系统静默丢弃，实测见 [cn.dsr213.hyperplus.AppToast]）。
 *
 * 【四、布局】
 *  ⑩ **卡片**：装偏好控件用 `SectionCard()`（内边距 0，控件自带）；装纯文字用
 *     `TextCard()`（16dp）。⛔ 别手写 `Card` 再自己猜内边距（一定猜错）。
 *     ★★★ **卡里放按钮时先问一句「边距谁给」**：[BTN_SLOT] 只管满宽 + 上边距 8dp，
 *     **左右/底部边距来自卡片的 `insideMargin`**。卡片内边距为 0（`SectionCard` /
 *     手写 `Card(insideMargin = 0.dp)`）⇒ 必须补 [CARD_H_INSET] 的水平内缩，
 *     否则按钮贴住卡片边缘（2026-10-07 用户点名，同一坑已三次）。
 *  ⑪ **页面外壳**：二级/三级页一律 `SubPage()`（顶栏返回 + 为悬浮底栏留位）。
 *     ⛔ 别在页内放返回按钮（会随内容滚走）。
 *  ⑫ **间距**：卡内按钮 [BTN_SLOT]（零内边距卡片再叠 [CARD_H_INSET] 的水平内缩）；
 *     卡与小标题之间由 `SectionCard` 统一给；
 *     ⛔ 别散落魔术数字 —— 16dp 这个卡片内缩只有 [CARD_H_INSET] 一处定义。
 *
 * ------------------------------ 怎么用这一节 ------------------------------
 * ★ 写新界面前：在上面找到你要的控件属于哪一条，**照着范例文件抄形状**。
 * ★ 拿不准时：`docs/UI_设计规范_HyperOS_2026-10-05.md` 有每条的正/反例代码与截图判据。
 * ★ 改动既有界面时：如果发现它违反了上面任一条，**顺手改对**，并在提交说明里点出来 ——
 *   本节的效力靠"每次路过都修一点"，不是靠某一轮集中整改。
 */

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


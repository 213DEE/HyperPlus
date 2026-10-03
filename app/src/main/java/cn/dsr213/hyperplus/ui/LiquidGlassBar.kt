package cn.dsr213.hyperplus.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 悬浮底栏：**液态玻璃**（HyperOS 4 / iOS 26 那套）。
 *
 * ============================ 材质调研结论（2026-09-29，全部有据可查）============================
 * 用户问的是「HyperOS 有没有开放高级材质 API（液态玻璃）」。查完的答案是**分三层**：
 *
 * ① **HyperOS 私有的「高级材质」** —— ❌ **没有面向第三方的公开 SDK**。
 *    它是系统「显示与亮度」里的一个设置，控制的是 SystemUI 自己（控制中心 / 通知栏 /
 *    最近任务）的模糊，第三方拿不到。相关的私有 prop 本机确实存在且为真
 *    （`persist.sys.bionic_material_supported=true`、`persist.sys.gradient_blur_ddk=true`、
 *    `persist.sys.background_blur_supported=true`），但那是系统内部开关，不是 API。
 *
 * ② **标准 Android 的等价能力** —— ✅ 齐全，而且本机全部开着（SDK 37 的 android.jar 实证）：
 *    `WindowManager.LayoutParams.FLAG_BLUR_BEHIND` / `setBlurBehindRadius(int)` /
 *    `WindowManager.isCrossWindowBlurEnabled()` / `Window.setBackgroundBlurRadius(int)` /
 *    `RenderEffect.createRuntimeShaderEffect` + `android.graphics.RuntimeShader`(AGSL)。
 *    本机：`ro.surface_flinger.supports_background_blur=1`、`dumpsys window` 的
 *    `mBlurEnabled=true`、`global background_blur_enable=1` ⇒ **模糊是真能用的**。
 *    （那些是**窗口级**的跨窗口模糊，服务于悬浮窗；App 内的组件用不上，见下。）
 *
 * ③ **MiuiX 的 `miuix-blur`** —— ✅ **这才是本界面实际用的那层**。
 *    它不吃"窗口背后"那块内容（那是给悬浮窗用的、语义也不对），而是**自己抓取**
 *    Compose 子树的内容当模糊源：`rememberLayerBackdrop()` 建一个捕获层，
 *    `Modifier.layerBackdrop(它)` 标出"哪块内容要被抓"，
 *    再在玻璃组件上 `Modifier.drawBackdrop(它, …)` 读取并施加模糊 + 高光。
 *    ⇒ 好处是**模糊的就是页面自己的内容**（滚动时玻璃里的内容跟着动），
 *      而不是壁纸；也完全不依赖窗口标志位，在普通 Activity 里就能用。
 *
 * ★ 本实现只用**库自带的原语**（用户 2026-09-29 选的就是这条）：
 *   `blur()`（BackdropEffects.kt）、`Highlight` / `BloomStroke` / `LightSource`（highlight 包）、
 *   `rememberDeviceTilt()`（sensor 包）。
 *   ⚠️ **刻意不用**官方示例里的 `lens()` / `vibrancy()` / `innerShadow()` ——
 *   那三个住在 `example/shared/…/component/liquid/` 里，**不属于库**（约 400 行示例代码），
 *   抄进来等于把一整套跟着示例版本走的东西背在身上。取舍理由与另一个选项（完整移植）
 *   记在 `docs/UI重构_液态玻璃_2026-09-29.md`。
 *
 * ============================ 能力降级（必须的，不是保险起见）============================
 * 模糊管线建立在 `RuntimeShader`（AGSL，**API 33+**）之上 —— 而本工程 `minSdk = 30`。
 * 库内部虽然也会兜底，但兜底之后**玻璃整块可能不画**（那就等于底栏消失，应用没法用）。
 * ⇒ 这里自己做**显式判定**：`SDK_INT >= 33` 才走 `drawBackdrop`，否则直接
 * `Modifier.background(...)` 画一块不透明底色。
 *   ⚠️ 所以这个判断**不能**交给库去兜：兜底语义是"效果没了"，而我们要的是"外观退化成实心，
 *     但导航照常可用"。这两件事的后果差着一个数量级（一个是丑，一个是不能用）。
 *   本机是 API 37，实际走的是玻璃路径。
 */
@Composable
internal fun LiquidGlassBar(
    /** 被捕获的页面内容层。为 null 时退化为实心底色（不模糊） */
    backdrop: LayerBackdrop?,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape: Shape = remember { RoundedCornerShape(percent = 50) }
    val scheme = MiuixTheme.colorScheme

    // ★ 玻璃底色 = surfaceContainer 降低不透明度。
    //   它必须画在 `onDrawSurface` 里（模糊之上、内容之下那一层），
    //   而不是另起一个 `background()` —— 后者的绘制层级在模糊**之下**，会被模糊糊掉。
    //   ⚠️ 深浅色不自己判断：`surfaceContainer` 本来就是主题色，跟随 Monet 动态取色。
    val surface = scheme.surfaceContainer.copy(alpha = SURFACE_ALPHA)

    val glassUsable = Build.VERSION.SDK_INT >= BLUR_MIN_SDK && backdrop != null

    // 重力跟随的镜面高光（光源随手机倾斜方向游走）
    val specular = rememberGravityHighlight(BAR_SPECULAR, extraDegrees = -45f)

    Row(
        modifier = modifier
            // ★★ 2026-10-02：**不再铺满屏幕**（原 `.fillMaxWidth()`）。
            //   用户原话：「悬浮底栏缩短一点，**就俩按钮没必要这么长**」。
            //   ⇒ 宽度改由**条目数**决定（[BAR_WIDTH]），整条胶囊像一块真正"悬浮"的
            //     药丸贴在底部中央，而不是一条横贯屏幕的栏。
            //   ⚠️ 不能只删 `fillMaxWidth()` 改用 `wrapContentWidth()`：下面每个条目是
            //     `Modifier.weight(1f)`，而 weight 分的是**剩余空间** —— Row 一旦没有确定
            //     宽度，剩余空间为 0，条目会被压成 0 宽（底栏直接消失）。
            //     所以必须给一个**确定的宽度**，weight 才有东西可分。
            .width(BAR_WIDTH)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            // ⚠️ 只剩垂直留白：胶囊已经不再铺满屏幕，"左右边距"这个量没有意义了
            //   （宽度完全由 [BAR_WIDTH] 决定；居中由调用方的 `align(BottomCenter)` 负责）。
            //   ⛔ 别在这里加回 `horizontal =`：`width()` 之后的横向 padding 会从**胶囊内部**
            //     再切掉一块，条目跟着变窄，而胶囊尺寸却不变 —— 那是"看不出来为什么变窄"的错。
            .padding(vertical = BAR_VERTICAL_MARGIN)
            .height(BAR_HEIGHT)
            .shadow(elevation = BAR_ELEVATION, shape = shape, clip = false)
            .then(
                if (glassUsable) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            // ⚠️ 参数是 **px**（`BackdropEffectScope` 实现了 `Density`，
                            //   所以 `dp.toPx()` 直接可用）。库里再按 BLUR_RADIUS_TO_SIGMA 换算成 sigma。
                            blur(BLUR_RADIUS.toPx(), BLUR_RADIUS.toPx())
                        },
                        // 镜面高光：单像素亮边 + BloomStroke 双光源（见 [BAR_SPECULAR]）
                        highlight = { specular.value.copy(alpha = 0.8f) },
                        onDrawSurface = { drawRect(surface) },
                    )
                } else {
                    // 降级：实心胶囊。导航照常可用，只是没有玻璃质感。
                    Modifier.background(surface.copy(alpha = 0.98f), shape)
                },
            )
            .padding(horizontal = BAR_INNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeTab.entries.forEachIndexed { index, tab ->
            BarTab(
                tab = tab,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 单个底栏条目：图标 + 文字，选中时上浮一层主色底 + 主色前景。
 *
 * ★ 选中态用**半透明主色块**而不是"换一个图标变体"：MiuiX 的图标没有实心/描边两套，
 *   而 HyperOS 自己的底栏就是"选中项加一块淡色 pill"。这样也和二级页顶栏的主色一致。
 * ★ 按压反馈用 `graphicsLayer` 缩放（0.94）而不是改颜色：玻璃上叠色会显得脏。
 */
@Composable
private fun BarTab(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "barTabScale",
    )
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariantSummary
    val bg = if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(bg, RoundedCornerShape(percent = 50))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            // 图标是装饰性的：旁边的文字已经念出了名字，不重复播报
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ICON_SIZE),
        )
        Text(
            // ★ 2026-10-03 多语言：标签从 `HomeTab` 里取的是**资源 id**，在这里才解析成文字
            //   （`HomeTab` 是 enum，没有 Context，见那边的注释）。
            text = stringResource(tab.labelRes),
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tint,
        )
    }
}

// ================================================================ 镜面高光（重力跟随）

/**
 * 底栏那块玻璃的镜面高光参数。
 *
 * ★ 数值来源：**照抄 MiuiX 官方示例 `component/liquid/LiquidGlassNavigationBar.kt` 的
 *   `iosIndicatorSpecular`**（Apache-2.0）。没有改任何一个数 ——
 *   这套数是"看起来像玻璃"的调参结果，自己重调只会更差，而且我无法在不看实物的情况下判断优劣。
 *   为了让"照抄"这件事在代码里可追溯，这里逐字保留原常量名与量级。
 */
private val BAR_SPECULAR: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

/** 光源参考点（与上面 `LightPosition` 的基准一致；照抄示例的 `LIGHT_REF_*`） */
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f

/** 手机接近水平时平面内重力方向不稳定（|g_xy| 太小）⇒ 光源钉在正下方 */
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f

/**
 * 重力方向的量化步长（3°）。
 *
 * ★ 为什么要量化：传感器约 50Hz 上报，**每一次读数变化都会让 `Highlight` 变成一个新对象**
 *   ⇒ 每帧重算着色器 uniform。量化到 3° 之后，只有跨过步长的转动才触发重算。
 *   （这条同样是示例里写明的做法，照抄。）
 */
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

/**
 * 平面内的重力方向角（弧度，量化到 3°）。
 *
 * ★ 返回 `State` 而**不是**直接返回 Float：这样读取发生在**绘制阶段**，
 *   而不是组合阶段。传感器 50Hz 写状态，如果在组合里读 `.value`，
 *   整棵子树每 20ms 重组一次 —— 那是纯粹的浪费。
 */
@Composable
private fun rememberGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val gx = tilt.gravityX
            val gy = tilt.gravityY
            val magSq = gx * gx + gy * gy
            if (magSq > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(gy, gx) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

/**
 * 把 [base] 高光的主光源转到重力方向 + [extraDegrees]。
 *
 * ★ 只在**绘制时**读 `.value`（见 [rememberGravityAngle]）；旋转后的 `Highlight` 被缓存，
 *   只有跨过量化步长时才重新分配对象。
 * ★ `style as? BloomStroke` 用安全转换而不是强转：万一将来换成别的 `HighlightStyle`
 *   实现，这里退化成"静止高光"而不是崩溃 —— 玻璃上的一条高光不值得让整个界面挂掉。
 */
@Composable
private fun rememberGravityHighlight(
    base: Highlight,
    extraDegrees: Float,
): State<Highlight> {
    val angle = rememberGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            val style = base.style as? BloomStroke ?: return@derivedStateOf base
            val primary = style.primaryLight
            val rad = angle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = style.copy(
                    primaryLight = primary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = primary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

// ================================================================ 尺寸常量
//
// ★★ 2026-09-30 整体收小（用户反馈「悬浮菜单栏太大了，不美观」）。
//   原来高 64dp / 图标 22dp / 文字 11sp，是一块"厚胶囊"：在外屏（视口 622dp 高）上，
//   胶囊 + 上下留白共占 ~84dp ≈ 13.5% 的屏高，压得内容很紧。
//   现在收到 54dp / 20dp / 10.5sp：
//     - 垂直占用 54 + 8×2 = 70dp ≈ 11.2%，观感更轻。
//     - 内容高度核算：上下 padding 6×2 + 图标 20 + 间距 1 + 文字行高 ~14 = 47dp < 54dp ✓
//   ⚠️ 别把高度压到 48dp 以下：条目是**唯一的导航入口**，命中区太小会开始点不准。
//
// ★★ 2026-10-02 宽度也收（用户反馈「就俩按钮没必要这么长」）。
//   高度那次只把"厚"解决了，宽度还是 `fillMaxWidth()` —— 两个条目把整条屏宽撑满，
//   在外屏上是一条横贯屏幕的栏，跟"悬浮"两个字不搭。现在宽度 = 条目数 × [BAR_TAB_WIDTH]，
//   整条胶囊缩到底部中央，才像一块浮在上面的药丸。

/**
 * 玻璃底栏的高度（含图标 + 文字）。
 *
 * ⚠️ 改这个值要同时看 [BarTab] 的 `padding(vertical =)`、[ICON_SIZE] 和文字行高 ——
 *   三者加起来必须留有余量，否则图标或文字会被裁掉（Column 不会自己缩）。
 */
private val BAR_HEIGHT = 54.dp

/**
 * **单个条目**的宽度。
 *
 * ★ 88dp 是"够点、又不空"的取值：里面最宽的条目是「功能」两个字（10.5sp ≈ 22dp），
 *   加上图标 20dp 与条目自身的 `padding(vertical)`，横向富余很大；而条目是竖着排的
 *   （图标在上面、字在下面），所以宽度只需要给点击区留够 —— 88dp 远大于 48dp 的下限。
 * ⚠️ 2026-10-03 多语言之后**最长的标签不再是中文**：英文 "Features"（8 字符，
 *   10.5sp ≈ 45dp）比「功能」宽一倍。核过：45dp + 两侧留白仍远小于 88dp ⇒ 无需改宽度。
 *   但**以后若把底栏做成三入口或换更长的词**，要照这条线重新核一遍（Column 不会自己换行）。
 * ⚠️ 它与 [BAR_HEIGHT] 一起决定了命中区（88×54dp）。改小要重新按这条线核一遍。
 */
private val BAR_TAB_WIDTH = 88.dp

/** 条目区与胶囊边缘之间的留白（左右各一份，见 [BAR_WIDTH] 的算式） */
private val BAR_INNER_PADDING = 6.dp

/**
 * 整条胶囊的宽度 = **条目数决定的**，不铺满全屏（2026-10-02 用户：「就俩按钮没必要这么长」）。
 *
 * ⚠️ 用 `HomeTab.entries.size` 而不是写死 `2`：以后加第三个入口时这里自动跟着长，
 *   不会出现"新加的那个被挤扁"（高度那条教训的同源写法）。
 */
private val BAR_WIDTH = BAR_TAB_WIDTH * HomeTab.entries.size + BAR_INNER_PADDING * 2

/**
 * 距上方内容 / 下方导航条。
 *
 * ⚠️ 2026-10-02：原来这里还有一个 [BAR_SIDE_MARGIN]（左右各 20dp），随"胶囊不再铺满屏幕"
 *   一并删掉了 —— 既然宽度由 [BAR_WIDTH] 说了算，再留一圈横向 padding 只会把胶囊**内部**
 *   的条目挤窄（`width()` 之后加的横向 padding 是往内切的），却看不出任何视觉理由。
 */
private val BAR_VERTICAL_MARGIN = 8.dp

/** 投影。用经典 `Modifier.shadow`（稳定 API）而不是示例里的 `dropShadow`（较新的实验 API） */
private val BAR_ELEVATION = 8.dp

/**
 * 玻璃底色的不透明度。
 *
 * ⚠️ 0.42 是**跟着示例走的**（示例里 `isBlurActive` 时用 `surfaceContainer.copy(alpha = 0.4f)`）。
 *   太低（<0.3）会让文字压在花哨壁纸上难读；太高（>0.6）就看不出"玻璃里透出内容"。
 */
private const val SURFACE_ALPHA = 0.42f

/**
 * 模糊半径（dp）。
 *
 * ★ 示例的导航栏用的是 4dp —— 但那个值背后还有 `lens()`（24dp 折射）在撑效果，
 *   我们（按用户所选）没有移植 lens ⇒ 模糊得自己把质感扛起来，取大一些。
 *   实测观感在生产验证时用截图核对，取值记录在文档里。
 */
private val BLUR_RADIUS = 18.dp

/** 图标边长。★ 22 → 20dp（与 [BAR_HEIGHT] 一起收小，见那段的核算） */
private val ICON_SIZE = 20.dp

/**
 * RuntimeShader（AGSL）从 API 33 起可用；模糊管线建在它之上（依据见文件头 §能力降级）。
 *
 * ⚠️ 这个常量同时是两个地方的**同一道闸**，改它要一起想：
 *   ① [LiquidGlassBar] 里决定走玻璃还是实心；
 *   ② `HyperPlusApp` 里决定要不要 `rememberLayerBackdrop()`。
 *   ⇒ 所以它是 `internal` 而不是 `private`：**闸必须只有一个值**。
 *   背景：`miuix-blur` 自己声明 `minSdk 33`，本工程用 `tools:overrideLibrary` 越过它
 *   （见 AndroidManifest.xml 那段注释），代价就是这道闸必须真的挡住所有 blur 入口。
 */
internal const val BLUR_MIN_SDK = 33

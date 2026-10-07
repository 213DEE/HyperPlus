package cn.dsr213.hyperplus.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.DisplaySize
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.ScreenForm
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
 *
 * ============================ 尺寸：宽度 = 外屏宽度 − 两侧留白（2026-10-06）============================
 * 用户原话：「悬浮底栏太靠下了，适当往上调节一下；宽度太窄了，应该拉宽到**外屏的屏幕宽度**，
 * 外屏时的**下边距和左右边距相等**；展开到内屏后，**依然按照外屏的宽度限制**悬浮底栏」。
 *
 * ⇒ 三条规则，全部落在本文件：
 *   ① **宽度** = 上限 − [BAR_MARGIN] × 2（居中），其中上限按**当前站在哪块屏**分流：
 *      · **外屏**上 ⇒ 上限 = **这块屏自己的宽度**（外屏横过来时它也变宽，三边留白仍相等）；
 *      · **内屏**上 ⇒ 上限 = **外屏宽度**（用户原话「展开到内屏后，依然按照外屏的宽度限制」），
 *        再取一次 `min(窗口宽, …)` 兜住"窗口比外屏还窄"。
 *      ⚠️ 分流判据走 [ScreenForm.probe]（**全工程唯一形态判据**，判的是**最小边**、
 *      旋转不变）—— ⛔ 别用"窗口宽 < 512dp"自己判：外屏横过来是 685dp，会判错。
 *   ② **下边距 = 左右边距 = [BAR_MARGIN]**；
 *   ③ 「外屏宽」是**运行时量出来的**，⛔ 不是常量 —— 见 [outerScreenWidthDp] 里那段。
 *
 * ★★ 为什么第 ③ 条必须运行时量（这是本次最容易踩的坑）：
 *   项目文档里一直写着「内屏 608dp / 外屏 425dp」，那是 **440dpi** 时期的值。
 *   而本机 `wm density` 的 **Override density 被改成了 400dpi（2.5x）** ⇒ 真实是
 *   **外屏 1168px ÷ 2.5 = 467dp、内屏 1672px ÷ 2.5 = 669dp**。
 *   照文档写死 425 ⇒ 底栏会比屏幕窄 42dp（两侧各多出 21dp 的空档），
 *   而这正是用户抱怨的"太窄"。⇒ 宽度只能**按当前密度现算**。
 *
 * ★★ 为什么"外屏宽"能在**展开态**量到：两块内建屏**任何时刻都在
 *   `DisplayManager.getDisplays()` 里**（登录态与否无关）—— 本轮 `dumpsys display` 实证：
 *   `displayId=1` 1168×1712 在内屏点亮时依然列着（`state OFF` 但元数据完好）。
 *   所以取它的 `mode.physicalWidth/Height` 的**较小边**（物理尺寸，旋转不变）即可。
 *   ⚠️ 认不出来时**退回当前窗口宽**（底栏仍占满它）—— 宁可"只是宽了"，也不要消失。
 *
 * ★ 「内屏也用外屏的宽度」是**用户明确的要求**，不是偷懒：底栏是同一块 UI，
 *   换屏时宽度不变，用户的位置记忆才成立（否则展开一下"两个按钮"就跑到屏幕两头）。
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

    // ------------------------------------------------------------ 宽度
    //
    // ★ 用户两句话的**直译**（算式见文件头「尺寸」那段）：
    //   · 站在**外屏**上 ⇒ 上限 = **这块屏自己的宽度**（外屏横过来时它也变宽，
    //     三边留白仍然相等 —— 用户第 ③ 条要的就是这个）；
    //   · 站在**内屏**上 ⇒ 上限 = **外屏宽度**（原话「展开到内屏后，依然按照外屏的宽度限制」）。
    // ⚠️ 内屏那一支再取一次 `min(窗口, 外屏)`，兜住"窗口比外屏还窄"（万一跑在更小的窗口里）：
    //   那种情况下底栏**不会溢出**（`Modifier.width()` 被父约束夹住时会出现
    //   "两侧留白只剩一半"的不对称，那才是真的难看）。
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val windowWidthDp = LocalConfiguration.current.screenWidthDp.dp
    // 外屏宽只随密度变（物理尺寸是死的）⇒ 按 (ctx, density) 记忆即可，不随折叠重组
    val outerWidthDp: Dp? = remember(ctx, density) {
        outerScreenWidthDp(ctx, density)?.dp
    }
    // 当前站在哪块屏上 —— 走**全工程唯一判据**（[ScreenForm.probe]）。
    // ⛔ 别用"窗口宽 < 512dp"自己判：**外屏横过来时窗口是 685dp**，那种写法会把它判成内屏
    //   ⇒ 底栏退回"按外屏宽度封顶" ⇒ 两侧留白 124dp ≠ 下边距（正好破掉用户第 ③ 条）。
    //   ⚠️ `ScreenForm` 判的是**最小边**（旋转不变）⇒ 外屏横过来仍然判成外屏。
    // ⚠️ 键里带 `windowWidthDp`：折叠 / 转屏都会改它 ⇒ 判据跟着重算。
    val ownForm = remember(ctx, windowWidthDp) { ScreenForm.probe(ctx).form }
    val limitDp = if (ownForm == ScreenForm.OUTER) {
        windowWidthDp
    } else {
        minOf(windowWidthDp, outerWidthDp ?: windowWidthDp)
    }
    val barWidth = (limitDp - BAR_MARGIN * 2).coerceAtLeast(BAR_MIN_WIDTH)

    // 一次一行，值变了才打 —— 这行是"宽度到底算成了多少、外屏量到了多少"的唯一取证点
    LaunchedEffect(barWidth, outerWidthDp, ownForm) {
        Log.i(
            TAG,
            "底栏宽度 ${barWidth.value}dp（当前屏 ${ownForm.label}，上限 ${limitDp.value}dp；" +
                "外屏量到 ${outerWidthDp?.value ?: "认不出"}dp，窗口 ${windowWidthDp.value}dp，" +
                "密度 ${density}，留白 ${BAR_MARGIN.value}dp）",
        )
    }

    Row(
        modifier = modifier
            // ★★ 2026-10-06（取代 10-02 那版"条目数定宽"）：
            //   用户原话「宽度太窄了，应该拉宽到**外屏的屏幕宽度**……外屏时的**下边距和左右边距相等**；
            //   展开到内屏后，**依然按照外屏的宽度限制**」。
            //   ⇒ 宽度不再由条目数决定，而是"外屏宽度 − 两侧留白"，见上面 [barWidth]。
            //   ⚠️ 每个条目是 `Modifier.weight(1f)`，而 weight 分的是**剩余空间** ——
            //     Row 必须有**确定宽度**，否则剩余空间为 0、条目被压成 0 宽（底栏直接消失）。
            //     ⛔ 所以别把它换成 `wrapContentWidth()`。
            .width(barWidth)
            // ⚠️ 导航栏 insets：**本机手势导航下这个值是 0**（`dumpsys window displays` 里
            //   内屏的 InsetsSource 只有 statusBars / displayCutout / ime / mandatorySystemGestures，
            //   **没有 navigationBars**）⇒ 底边实得就是 [BAR_MARGIN]。
            //   留着它是为了"三键导航 / 别家 ROM 报了 inset"时不把底栏压到系统键上 ——
            //   代价是那种机器上底边会变成 `inset + BAR_MARGIN`（比左右略大）。
            //   ★ 这是**刻意**的取舍：宁可底边略高，也不要和系统键叠在一起。
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            // ★★ 下边距 = 左右边距（用户要求）。
            //   ⚠️ 只用 `bottom =`、**不用 `vertical =`**：这里的元素被调用方 `align(BottomCenter)`，
            //     上边那份 padding 既不改变胶囊位置、也不改变观感，只是白白把测量高度撑大一截
            //     （上一版 `padding(vertical = BAR_VERTICAL_MARGIN)` 就是这种写法）。
            //   ⛔ 别在这里加横向 padding：`width()` 之后的横向 padding 是从**胶囊内部**再切掉一块，
            //     条目跟着变窄、胶囊尺寸却不变 —— 那是"看不出来为什么变窄"的错。
            //     （横向留白由 `barWidth` 算式里的 `BAR_MARGIN × 2` 承担。）
            .padding(bottom = BAR_MARGIN)
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
// 演进（每一条都是用户的原话，⛔ 别改回去）：
//   ★★ 2026-09-30 整体收小（「悬浮菜单栏太大了，不美观」）：64→54dp / 22→20dp / 11→10.5sp。
//      ⚠️ 别把高度压到 48dp 以下：条目是**唯一的导航入口**，命中区太小会开始点不准。
//   ★★ 2026-10-02 宽度改"条目数定宽"（「就俩按钮没必要这么长」）⇒ 两个条目时 **188dp**。
//   ★★ 2026-10-06 **宽度又改回"宽"**（「太窄了，应该拉宽到外屏的屏幕宽度；外屏时下边距和
//      左右边距相等；展开到内屏后依然按外屏的宽度限制」）⇒ 现在是
//      `min(窗口宽, 外屏宽) − [BAR_MARGIN] × 2`。**这不是 10-02 那版的反悔**：
//      10-02 抱怨的是"铺满屏、不像悬浮"，10-06 抱怨的是"太窄" —— 现在落在中间：
//      比屏幕窄，但只窄两侧各 [BAR_MARGIN]。⛔ 别用 `fillMaxWidth()` 实现（那会变成 0 留白）。

/**
 * 玻璃底栏的高度（含图标 + 文字）。
 *
 * ⚠️ 改这个值要同时看 [BarTab] 的 `padding(vertical =)`、[ICON_SIZE] 和文字行高 ——
 *   三者加起来必须留有余量，否则图标或文字会被裁掉（Column 不会自己缩）。
 */
private val BAR_HEIGHT = 54.dp

/**
 * **底栏与屏幕边缘的统一留白**：左右各一份、**下边一份**（三边相等，2026-10-06 用户要求）。
 *
 * ★ 为什么取 16dp：
 *   - 它是本工程 UI 规范里"卡片内外留白"的标准档（见 `docs/UI_设计规范_HyperOS_*`），
 *     不是新引入的魔术数字；
 *   - 比上一版**有效的**底边留白（8dp ＋ 0 的导航栏 inset，见下面 [LiquidGlassBar] 里那段
 *     取证）翻了一倍 ⇒ 用户要的"往上调节一下"是**看得见**的，又不至于让底栏跟内容脱开；
 *   - 页面内容的横向留白是 `UiCommon.PAGE_H_PADDING` = 12dp ⇒ 底栏比卡片**每侧窄 4dp**。
 *     ⚠️ 这 4dp 是**有意的**：底栏是"浮"在内容之上的独立一层，跟卡片严丝合缝反而像
 *     "内容的一部分"。要完全对齐就把这里改成 12dp（只此一处）。
 * ★ "三边相等"只在本机这种**没有 navigationBars inset** 的手势导航下成立 ——
 *   别的导航方式下底边会多出那一段 inset（取舍理由见 [LiquidGlassBar] 里的注释）。
 */
private val BAR_MARGIN = 16.dp

/**
 * **单个条目**的宽度 —— 2026-10-06 之后它**只用来算宽度下限**，不再直接决定宽度。
 *
 * ★ 88dp 是"够点、又不空"的取值：里面最宽的条目是「功能」两个字（10.5sp ≈ 22dp），
 *   加上图标 20dp 与条目自身的 `padding(vertical)`，横向富余很大；而条目是竖着排的
 *   （图标在上面、字在下面），所以宽度只需要给点击区留够 —— 88dp 远大于 48dp 的下限。
 * ⚠️ 2026-10-03 多语言之后**最长的标签不再是中文**：英文 "Features"（8 字符，
 *   10.5sp ≈ 45dp）比「功能」宽一倍。核过：45dp + 两侧留白仍远小于 88dp ⇒ 无需改宽度。
 *   但**以后若把底栏做成三入口或换更长的词**，要照这条线重新核一遍（Column 不会自己换行）。
 * ⚠️ 它与 [BAR_HEIGHT] 一起决定了命中区下限（88×54dp）。改小要重新按这条线核一遍。
 */
private val BAR_TAB_WIDTH = 88.dp

/** 条目区与胶囊边缘之间的留白（左右各一份，[BAR_MIN_WIDTH] 的算式用） */
private val BAR_INNER_PADDING = 6.dp

/**
 * 胶囊宽度的**下限** = 2026-10-02 那一版的尺寸（条目数 × [BAR_TAB_WIDTH] ＋ 内侧留白×2）。
 *
 * ★ 留它的理由：正常路径算出来的宽度远大于它（外屏 467dp ⇒ 实得 435dp），
 *   只有"外屏认不出 / 窗口比外屏还窄"那两条退路上才会碰到它。
 * ⚠️ 用 `HomeTab.entries.size` 而不是写死 `2`：以后加第三个入口时下限自动跟着长，
 *   不会出现"新加的那个被挤扁"（高度那条教训的同源写法）。
 */
private val BAR_MIN_WIDTH = BAR_TAB_WIDTH * HomeTab.entries.size + BAR_INNER_PADDING * 2

/** 日志标签（本文件只有一条日志：宽度算成了多少） */
private const val TAG = "HyperPlusLiquidGlassBar"

/**
 * **本机外屏的物理最小边**（像素）—— [outerScreenWidthDp] 的第 ② 条路。
 *
 * ★ 来源：`dumpsys display` 实证 —— 外屏 `DisplayDeviceInfo{"内置屏幕", 1168 x 1712, ...}`
 *   （`displayId=1`，`FLAG_PRESENTATION`）。**是硬件事实，不是可调参数**。
 *
 * ★ 为什么第 ② 条路是"像素 + 除密度"而不是直接写 dp：
 *   用户改「显示大小 / DPI」时 dp 值会整体变（本机现在 Override 就是 **400dpi（2.5x）**，
 *   而不是物理的 440dpi）；写死 dp 会立刻过期，写像素则**永远跟着密度自己算对**。
 *   （同样理由见 `ScreenForm` 类注释里"密度变了 dp 判据会串档"那段。）
 *
 * ⚠️ 这是一台固定机型的常量（和相机 burst、面板朝向偏置那几条同类）。换机型要重新量 ——
 *   不过换机型时第 ① 条路（枚举更小的屏）大概率能自己接上，这个常量只是兜底。
 */
private const val OUTER_MIN_SIDE_PX = 1168

/**
 * 「**外屏有多宽**」（dp，**本界面的 dp 坐标系**）。
 *
 * ★★ 为什么必须现算（本次最容易踩的坑）：项目文档里的「内屏 608dp / 外屏 425dp」是
 *   **440dpi** 时期的值，而本机 `wm density` 的 Override density 现在是 **400dpi（2.5x）**
 *   ⇒ 真实是 **外屏 1168 ÷ 2.5 = 467dp、内屏 1672 ÷ 2.5 = 669dp**。
 *   照文档写死 425 会让底栏比屏幕窄 42dp（两侧各多出 21dp 空档）—— 那正是用户抱怨的"太窄"。
 *
 * ★★ **为什么不能直接用 `DisplayManager.displays`**（第一版就是这么写错的，真机日志
 *   `认不出外屏（共 1 块）` 当场打脸）：**App 进程只看得到"自己那块屏"**。
 *   项目里 `ActiveDisplay` 那一套能认出两块屏，是因为它跑在 **SystemUI 进程**（系统进程
 *   拿得到全部 display）；`ui/` 里这么写就什么也拿不到。⇒ 必须另找口子，见下。
 *
 * ★ 取值顺序（两条路都写得出来，且**结果一致**）：
 *   ① **枚举比本屏更小的那块**：候选 = `displays` ∪ `getDisplays(DISPLAY_CATEGORY_PRESENTATION)`。
 *      本机外屏带 **`FLAG_PRESENTATION`**（`dumpsys display` 实证：
 *      `DisplayInfo{..., FLAG_PRESENTATION, ...}`）⇒ PRESENTATION 类目是 App 进程可能拿到它的口子。
 *      规则是"**取比本屏更小的那块**"而不是"按 id / 按形态认"—— 因为外屏**恒比内屏窄**
 *      （1168 < 1672），"更小"这个判据不需要知道自己在哪块屏上、也不随 id 分配变化。
 *   ② ①拿不到 ⇒ 用本机常量 [OUTER_MIN_SIDE_PX]（外屏物理最小边，`dumpsys display` 实证）。
 *      ⚠️ 常量是**像素**、再除以本界面密度 ⇒ 用户改「显示大小」时它自动跟着对，不会过期。
 *
 * ⚠️ px→dp 用**本界面的密度**（调用方从 `LocalDensity` 取），而不是
 *   `Display.getRealMetrics().densityDpi`：本机那两者是 **2.5 与 2.75**，
 *   而"底栏该有多宽"问的是**本界面坐标系里的宽度** ⇒ 必须用本界面的密度。
 *
 * @return null = 连本屏尺寸都读不到（见 [DisplaySize.of] 的三条退路）⇒ 调用方退当前窗口宽
 */
private fun outerScreenWidthDp(context: Context, density: Float): Int? {
    if (density <= 0f) return null

    // 本屏的物理最小边 —— 走全工程唯一量法（DisplaySize），⛔ 别在 UI 里另写一份
    val ownPx = runCatching { DisplaySize.of(context).smallestSidePx }.getOrNull()
        ?.takeIf { it > 0 } ?: return null

    val cand = smallestCandidateMinSidePx(context)
    val px = if (cand != null && cand < ownPx) cand else OUTER_MIN_SIDE_PX
    val src = if (cand != null && cand < ownPx) "枚举到更小的屏" else "本机常量"
    Log.i(TAG, "外屏宽度取 $px px（$src；本屏 $ownPx px，候选最小 ${cand ?: "读不到"} px）")
    return (px / density + 0.5f).toInt()
}

/**
 * 能枚举到的那些 display 里，**最小的物理最小边**（px）。读不到返回 null。
 *
 * ★ 为什么要凑两个来源：`displays` 在 App 进程通常只有自己那块，
 *   而 PRESENTATION 类目才可能带回别的屏（本机外屏带 `FLAG_PRESENTATION`）。
 *   ⚠️ 两个来源按 `displayId` 去重，避免同一块屏算两次（无害但会让日志读数变乱）。
 */
private fun smallestCandidateMinSidePx(context: Context): Int? {
    val dm = context.getSystemService(DisplayManager::class.java) ?: return null
    val all = buildList {
        runCatching { addAll(dm.displays) }
        runCatching { addAll(dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) }
    }.distinctBy { it.displayId }
    return all.mapNotNull { minSidePxOf(it) }.minOrNull()
}

/**
 * 一块 display 的**物理最小边**（px）——`mode` 优先（物理尺寸、旋转不变），
 * 读不到再退 `getRealMetrics()`（跨屏访问受限时 `mode` 会返回 null）。
 */
private fun minSidePxOf(display: Display): Int? {
    runCatching { display.mode }.getOrNull()?.let { m ->
        val px = minOf(m.physicalWidth, m.physicalHeight)
        if (px > 0) return px
    }
    val dm = DisplayMetrics()
    return runCatching {
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        minOf(dm.widthPixels, dm.heightPixels).takeIf { it > 0 }
    }.getOrNull()
}

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

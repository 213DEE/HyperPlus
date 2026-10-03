package cn.dsr213.hyperplus.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 全部目的地。**唯一真值来源** —— 页面标题、底栏归属、跳转都从这里派生。
 *
 * ★ 用户 2026-09-29 敲定的信息架构（原话：「所有功能不要挤在同一页，做成不同的主页
 *   （功能、设置（包含当前状态、诊断和关于）），功能页设置二级菜单旋转增强，
 *   把旋转的相关设置都放进二级菜单里面，因为以后还要添加更多功能」）：
 *
 * ```
 * 功能（主页） ── 旋转增强（二级）   ← 以后新增的功能都挂在这里
 * 设置（主页） ── 当前状态（二级）
 *            ├─ 诊断（二级）
 *            └─ 关于（二级）
 * ```
 *
 * ★ 2026-10-01：原来的「应用豁免 / 应用名单」二级页**已被合并进「旋转增强」页**
 *   （用户原话：「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」）⇒
 *   `Route.Apps` 删除。功能页底下**只剩一条**二级路径，别照旧图再加回去。
 *
 * ★ 为什么不加 `@Serializable`（官方示例加了）：示例用 `rememberNavBackStack<Route>()`，
 *   那个重载内部走 `rememberSaveable` + kotlinx.serialization，**需要**序列化器。
 *   而本应用用 `navBackStackOf()`（非 Composable 构造，返回普通 `SnapshotStateList`），
 *   省掉一整套 Kotlin 序列化插件依赖。
 *   ⚠️ 代价：进程被杀后返回栈回到第一个目的地。**这在本应用是可接受的**，因为
 *   `MainActivity` 在清单里声明了
 *   `configChanges="orientation|screenSize|screenLayout|…|density"`
 *   ⇒ 转屏 / 折叠展开**不会**重建 Activity，返回栈不会因为最常见的场景丢失。
 *   ⚠️ 这些路由必须是 **data object**（不是普通 object）：`NavEntryBuilder` 用
 *   `contentKey` 做去重与状态作用域标识，`data object` 的值相等语义才成立
 *   （普通 object 也能比引用，但 `data class` 的 `toString()` 会参与 saveable 命名，
 *   用 data 系列是库注释里点名推荐的做法）。
 */
sealed interface Route : NavKey {
    /** 主页：功能 */
    data object Function : Route

    /** 主页：设置（当前状态 / 诊断 / 关于的入口列表） */
    data object Settings : Route

    /** 二级：旋转增强 —— 所有与旋转有关的设置（**应用名单也在这页里**，见 [RotationPage]） */
    data object Rotation : Route

    /** 二级：当前状态 */
    data object Status : Route

    /** 二级：诊断 */
    data object Diagnostics : Route

    /** 二级：关于 */
    data object About : Route

    /** 二级：语言（2026-10-03 新增，入口在「设置」页） */
    data object Language : Route

    /**
     * 二级：**实验功能**（2026-10-03 新增，入口在「功能」主页**最底下**）。
     *
     * 用户原话：「在首页新增一个"实验功能"置底，里面添加"自适应旋转"开关，
     * 打开开关之后，旋转增强里面才显示自适应旋转的选项」。
     *
     * ★ 为什么挂「功能」主页而不是「设置」：它开关的东西（自适应旋转）是**功能**，
     *   不是应用级设置；这一页以后还会长（别的实验功能都进这里）。
     * ⚠️ 与 [Rotation] 的区别：本页只放"要不要显示某功能"的**可见性开关**，
     *   功能本身的参数（模式、时长…）一律还在 [Rotation] 里 —— 别把参数搬过来。
     */
    data object Experimental : Route
}

/**
 * 底部悬浮栏的两个入口。
 *
 * ★ 图标用 `MiuixIcons.Regular.*`（图标库 0.9.4 的 Extended 集合，已由 javap 核实
 *   那两个扩展属性存在于 `MiuixIcons$Regular` 上）——
 *   `Tune` 是滑块状图标（表达"可调的功能"），`Settings` 是齿轮。
 *
 * ⚠️ 标签是 **`@StringRes Int` 而不是 `String`**（2026-10-03 多语言改造）：
 *   本类型是 `enum`，**没有 Context**，字符串一旦在这里取出来就锁死在某个语言上了。
 *   调用处（`LiquidGlassBar`）用 `stringResource(tab.labelRes)` 取。
 *   ⛔ 别为了"少改一处"退回 `String` —— 那会让底栏永远停在进程启动时的语言。
 */
internal enum class HomeTab(
    @StringRes val labelRes: Int,
    val root: Route,
    val icon: ImageVector,
) {
    Function(R.string.tab_function, Route.Function, MiuixIcons.Regular.Tune),
    Settings(R.string.tab_settings, Route.Settings, MiuixIcons.Regular.Settings),
}

/**
 * 当前目的地属于哪一个底栏入口。
 *
 * ★ 这是"底栏高亮跟着二级页走"的判据：停在「诊断」上时，底栏应该仍然亮着「设置」，
 *   否则一进二级页两个入口全灭，用户会以为掉出了导航结构。
 *   未知目的地（理论上不会有）按第一个处理 —— 不抛异常，界面不该因为一个没登记的路由就崩。
 */
internal fun tabOf(route: NavKey?): HomeTab = when (route) {
    Route.Function, Route.Rotation, Route.Experimental -> HomeTab.Function
    Route.Settings, Route.Status, Route.Diagnostics, Route.About, Route.Language -> HomeTab.Settings
    else -> HomeTab.Function
}

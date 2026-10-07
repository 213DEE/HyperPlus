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
 * 功能（主页） ── 旋转增强（二级）── 应用名单（三级，2026-10-06）
 *            ├─ 分屏增强（二级，2026-10-04）── 应用名单（三级，2026-10-06）
 *            └─ 实验功能（二级，置底）
 * 设置（主页） ── 当前状态（二级）
 *            ├─ 诊断（二级）── 运行记录（三级，2026-10-06）
 *            ├─ 权限管理（二级，2026-10-05）
 *            ├─ 语言（二级）
 *            └─ 关于（二级）
 * ```
 *
 * ⚠️ 上面那棵树**只是注释**：返回栈是**平坦**的（`SnapshotStateList<Route>`），
 *   路由之间没有 parent 指针 —— 从三级页返回靠 `removeLastOrNull()` 回到栈里上一项，
 *   不是靠"找父亲"。⇒ ⛔ 别为了"表达层级"给 `Route` 加 `parent` 字段：
 *     那是给层级导航（`HierarchicalNavHost`）用的另一套模型，本工程的
 *     `NavDisplay` + 平坦栈用不上，加了只会多出一份**没人维护的真值**。
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
     * 二级：**权限管理**（2026-10-05 新增，入口在「设置」页）。
     *
     * 用户原话：「在设置页里面做一个权限管理，自动检查该获取的权限有没获取
     * （LSPosed、root、修改系统设置权限），没获取的权限提供跳转按钮，
     * 点击跳转到到相关权限的设置页面」⇒ 逐字实现见 [PermissionsPage]。
     *
     * ★ 为什么住「设置」而不是「诊断」：诊断页是**给排查用的只读快照**（能看不能改）；
     *   这一页是**能动手的入口**（点一行就跳去授权）。两者的读者是同一批人，
     *   但"要不要给用户一个动作"决定了归属。
     * ⚠️ 与 [cn.dsr213.hyperplus.ModuleLink] 的关系：本页只**读**它的 `State` 当判据
     *   （见 [PermissionsPage] 类注释里的三项判据表），⛔ 不在这里发心跳、不改任何状态。
     */
    data object Permissions : Route

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

    /**
     * 二级：**分屏增强**（2026-10-04 新增，入口在「功能」主页）。
     *
     * 用户原话：「直接在 app 里面给选项「2 分屏展开方向：朝左 / 朝右」」。
     *
     * ★ 为什么**不**并进 [Rotation]：分屏管的是"窗口怎么分"，旋转管的是"屏幕转多少度"，
     *   两者只是恰好住在同一个模块里。混一页会让用户以为分屏方向是旋转的一个参数。
     * ⚠️ 它与 [Experimental] 的分工：本页放**能用的功能**的设置；只有"还没做稳、
     *   默认不给新用户看见"的东西才进 [Experimental]。分屏增强是正式功能 ⇒ 住这里。
     */
    data object SplitScreen : Route

    /**
     * 三级：**应用名单**（旋转那一份）—— 2026-10-06 新增，入口在 [Rotation] 页。
     *
     * ★ 用户原话：「**还是很乱，可以适当增加三级菜单，要让所有功能都清晰明了**」＋
     *   「（名单）**默认展开**，但是右边要加一个根据首字母的索引」。
     * ⇒ 这份名单本身就是**一两百行的巨型列表**（`AppWhitelistPage.kt` 的注释里早有这段测算：
     *   一两百行 × 每行 `heightIn(min = 56.dp)` ⇒ 单这一块就 6~12 屏，把上面所有设置推出屏外）。
     *   按 `UI_信息架构优化方案` 的 **R7**：「进阶级内容只能靠三级页，且只给'需要独立流程'或
     *   **'内容是巨型列表'**的东西用」—— 它正是那个被点名的例外。
     *
     * ⚠️ **它修正了 2026-10-01 的旧口径**（「把应用名单合并进旋转增强页，**不再是一个独立的
     *   二级菜单**」）—— 修正的只是"**二级**菜单"那半句：名单**仍然不是一个二级页**，
     *   而是「旋转增强页的一条入口 ＋ 一个三级页」。用户 10-06 亲自放行三级（见本条由头）。
     *   ⛔ 别据此把它搬回二级页；也⛔ 别把入口整个删掉、退回"在二级页里铺开一百多行"。
     *
     * ⚠️ 与 [SplitApps] **同名不同物**：两份名单的数据源、判据、行形状全不一样 ——
     *   旋转这份管的是"**这个应用用哪种方式转**"（一行右侧四选一），
     *   分屏那份管的是"**折不折**"（一行右侧一个开关）。
     *   ⛔ 别为了"少写一页"把两者并成一个页面再按来源分支：它们是两份完全独立的配置
     *   （见 [cn.dsr213.hyperplus.SplitWhitelist] 类注释"各自独立一份"）。
     */
    data object RotationApps : Route

    /**
     * 三级：**应用名单**（分屏那一份）—— 2026-10-06 新增，入口在 [SplitScreen] 页。
     *
     * 与 [RotationApps] 同批落地、同一套页面代码，但服务**另一份完全独立的名单**。
     * 完整由头与"别合并"的告诫见 [RotationApps]。
     */
    data object SplitApps : Route

    /**
     * 三级：**运行记录**（诊断）—— 2026-10-06 新增，入口在 [Diagnostics] 页。
     *
     * ★ 用户 10-06 选了「三级页 3 个 ＋ 设置页也整理」⇒ 诊断页原来那 4 段读数里，
     *   后两段（「数据记录」＋「最近事件」）与其他两段**不是一类东西**：
     *   前两段是**实时快照**（现在的传感器 / 权限 / 门控 / 指标），后两段是**历史**
     *   （记录文件在哪、把运行状态摊成人话）。
     *   ⇒ 后两段拆到这一页，诊断页只留实时快照。
     *
     * ⚠️ **数值口径一字未改**（那是诊断页的硬纪律，见 [DiagnosticsPage] 类注释）：
     *   拆的只是**位置**，两段内容的判断分支、阈值、"读不到时怎么报"全部原样搬过来。
     *   ⛔ 别在搬运时"顺手润色"任何一行读数。
     */
    data object DiagRecords : Route

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
    // ★ **二/三级页都要登记**：底栏必须继续亮着它所属的那个入口 ——
    //   漏登记的后果是"一进里面，两个入口全灭"，用户会以为掉出了导航结构。
    //   ⚠️ 2026-10-06：三级页**回来了**（RotationApps / SplitApps / DiagRecords）——
    //     它们正是当年那句"将来若再加，记得补进这一行"等着的用例 ⇒ 再加页时照此办理。
    Route.Function, Route.Rotation, Route.Experimental, Route.SplitScreen,
    Route.RotationApps, Route.SplitApps,
    -> HomeTab.Function

    Route.Settings, Route.Status, Route.Diagnostics, Route.About, Route.Language,
    Route.Permissions, Route.DiagRecords,
    -> HomeTab.Settings
    else -> HomeTab.Function
}

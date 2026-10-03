package cn.dsr213.hyperplus.ui

import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.VersionChecker
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用外壳：**导航宿主 + 悬浮液态玻璃底栏**。
 *
 * ============================ 结构（2026-09-29 重构）============================
 * ```
 * Box
 *  ├─ NavDisplay（被 layerBackdrop 捕获 ⇒ 它画的内容就是玻璃的模糊源）
 *  │    ├─ 主页 功能 / 设置
 *  │    └─ 二级 旋转增强（含应用名单）/ 当前状态 / 诊断 / 关于
 *  └─ LiquidGlassBar（浮在底部，读取上面那层被捕获的内容）
 * ```
 *
 * ★ 为什么玻璃要**单独放在 Box 层**、而不是放进某个页面的 `Scaffold(bottomBar=)`：
 *   `layerBackdrop` 捕获的是**它所在那棵子树**画出来的东西。如果玻璃条和页面内容在
 *   同一棵被捕获的子树里，玻璃就会把**自己**也糊进去（自己模糊自己）——
 *   表现出来是底栏区域出现一圈发虚的重影。
 *   ⇒ 捕获层与玻璃层必须是两棵平行的子树。这是这套 backdrop 机制的硬约束。
 *
 * ★ 为什么每个页面各有一个 `Scaffold`：页面顶栏（大标题 / 返回箭头）本来就该由页面自己决定，
 *   外壳只管"下面永远有一条玻璃栏"。这样外壳不需要知道任何页面的标题。
 *
 * ★ 版本检测放在**外壳**而不是「关于」页：它原来是"进应用就静默查一次，有新版本主动弹窗"，
 *   这是唯一会主动打扰用户的分支 —— 挪进二级页会让主动提示变成"得先点进关于页才可能看到"。
 */
@Composable
fun HyperPlusApp(
    appVersion: String,
    /** 状态是否已至少读过一次（界面据此决定显示"还没读到"还是真状态） */
    probed: Boolean,
    /** 引擎是否在线（心跳够新鲜） */
    hosted: Boolean,
    hostState: ModuleLink.State?,
    onCalibrate: (Int) -> Unit,
    /** 打开系统设置（用于引导用户去关掉「注视感知」，见旋转增强页的使用提醒） */
    onOpenSettings: () -> Unit,
    /**
     * 界面语言改了 ⇒ 请宿主重建界面（2026-10-03）。
     *
     * ⚠️ 这不是"重画"而是**重建 Activity**：语言是在 `attachBaseContext` 里装到
     *   base context 上的，而那个时刻一个 Activity 只有一次。见 `LanguagePage` 的类注释。
     */
    onRecreate: () -> Unit,
    /** 返回栈已经见底时再按返回 ⇒ 交给宿主结束界面 */
    onExit: () -> Unit,
) {
    // ★ 返回栈用非 Composable 的 `navBackStackOf` + `remember`（不是 `rememberNavBackStack`）：
    //   后者要 kotlinx.serialization 的序列化器（要求路由标 `@Serializable`），
    //   而本工程用不着那套 —— 理由与代价见 [Route] 的类注释。
    val backStack = remember { navBackStackOf(Route.Function) }

    // 玻璃的模糊源：被 NavDisplay 画出来的那一层页面内容。
    //
    // ⚠️⚠️ 这里那两道 `SDK_INT >= 33` 的闸**必须保留**，它和 `AndroidManifest.xml` 里的
    //   `tools:overrideLibrary="top.yukonga.miuix.kmp.blur"` 是**一对**：
    //   `miuix-blur` 自己声明 minSdk 33（整条管线建在 RuntimeShader / AGSL 上），
    //   我们 override 了那个声明、承诺"版本差异自己处理"—— 靠的就是这两道闸。
    //   API < 33 时 `backdrop` 为 null ⇒ `LiquidGlassBar` 走实心胶囊那条分支，
    //   全程不碰 blur 的任何类；导航、转场、返回全部照常。
    //
    //   ⚠️ 为什么必须**条件调用** `rememberLayerBackdrop()` 而不是"调了但不用"：
    //     函数一旦被调用，`miuix-blur` 的相关类就会被加载 —— 在 API 30~32 上那正是
    //     会抛 `NoClassDefFoundError` / `NoSuchMethodError` 的时刻。
    //   ⚠️ SDK_INT 在进程生命周期内不变 ⇒ 这个分支是稳定的，不会因为条件组合而抖动。
    val blurSupported = Build.VERSION.SDK_INT >= BLUR_MIN_SDK
    val backdrop = if (blurSupported) rememberLayerBackdrop() else null

    val ctx = LocalContext.current

    val current = backStack.lastOrNull()
    val currentTab = tabOf(current)

    /**
     * 打开一个二级页。
     *
     * ⚠️ 必须先去重：`NavEntryBuilder` 用 `contentKey` 做**全栈唯一**校验，
     *   同一个路由压两次会在 reconcile 时抛 `IllegalArgumentException`（库注释明确写了）。
     *   ⇒ 连点两下同一个入口应当无事发生，而不是崩掉。
     */
    fun go(route: Route) {
        if (route !in backStack) backStack.add(route)
    }

    /**
     * 切换底栏入口：**清掉二级页回到该入口的主页**。
     *
     * ★ 这是底栏该有的语义（用户点"设置"是想到设置首页，不是想在旋转增强页上换一个高亮）。
     * ⚠️ 实现上只动 `backStack[0]`、不在中途留下空栈：`NavDisplay` 要有一个非空栈才能渲染，
     *   出现过空栈的那一帧会闪白，且栈一旦空了后续 push 的语义也乱。
     */
    fun switchTab(tab: HomeTab) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (backStack.isEmpty()) backStack.add(tab.root) else backStack[0] = tab.root
    }

    // ------------------------------------------------------------ 内外屏形态
    //
    // ★ 模式按内/外屏解耦之后，"现在这块屏算内屏还是外屏"成了**进程间共享的判据**：
    //   界面和引擎必须对同一个形态达成一致（都走 `AppPrefs.syncScreenForm`，见那边的注释）。
    //   界面这半边负责在折叠 / 转屏（= 配置变化）时推它一把；引擎那半边自己注册了同样的监听。
    //
    // ⚠️ 2026-09-29 这次拆分把它从 `EngineScreen` 搬到了**外壳**（原来在主页函数里，
    //   还带着"必须写在诊断页 early return 之前"的告诫）。搬到外壳之后那条告诫自动消失 ——
    //   外壳在所有页面之上，任何页面被渲染时它都已经挂上了，不存在"停在某一页时折一下手机
    //   形态不更新"这种可能。这是拆分带来的一个真实收益，不是纯粹的搬家。
    DisposableEffect(ctx) {
        AppPrefs.syncScreenForm(ctx)
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                AppPrefs.syncScreenForm(ctx)
            }

            /** `ComponentCallbacks` 的必实现项；界面这里没有要随内存压力做的事（同引擎侧，见那边的注释） */
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        }
        ctx.registerComponentCallbacks(cb)
        onDispose { ctx.unregisterComponentCallbacks(cb) }
    }

    // ------------------------------------------------------------ 版本检测
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<VersionChecker.Release?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { VersionChecker.check(appVersion) }
            checking = false
            when (r) {
                // 有新版：弹窗（这是唯一会主动打扰用户的分支）
                is VersionChecker.Result.Newer -> updateInfo = r.remote
                // 已最新 / 查询失败：**静默**，只有用户手动点才给反馈
                // ⚠️ 2026-10-03 多语言：这两句原来是硬编码中文。⚠️ `ctx` 是**被 AppLocale
                //   包过的 Activity**，所以 `ctx.getString` 出来的就是用户选的语言
                //   （别改成 `context.resources` 或全局 `Locale`，那会绕过包装）。
                is VersionChecker.Result.UpToDate -> if (manual) {
                    toast(ctx, ctx.getString(R.string.update_up_to_date, appVersion))
                }
                is VersionChecker.Result.Failed -> if (manual) {
                    // 失败原因分两层：资源 id（可翻译）＋ detail（HTTP 码/异常名，不翻）。
                    // ⚠️ 无占位符的资源多收到一个参数是无害的（`String.format` 忽略多余实参），
                    //   所以这里不用为 [update_fail_no_release] 单独写一个分支。
                    toast(
                        ctx,
                        ctx.getString(
                            R.string.update_check_failed,
                            ctx.getString(r.reasonRes, r.detail),
                        ),
                    )
                }
            }
        }
    }

    // 进应用自动查一次（静默）
    LaunchedEffect(Unit) { checkUpdate(manual = false) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) backStack.removeLastOrNull() else onExit()
            },
            modifier = Modifier
                .fillMaxSize()
                // ★ 这一行就是"把页面内容录进玻璃"的那一步：
                //   没有它，drawBackdrop 读到的是一张空图层 ⇒ 玻璃里什么都透不出来（表现为纯色块）。
                // ⚠️ 与 backdrop 一样必须条件加上，理由同上面那两道闸：
                //   `Modifier.layerBackdrop(...)` 也来自 `miuix-blur`，API < 33 碰它就是加载 33 的类。
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            entry<Route.Function> {
                FunctionPage(
                    appVersion = appVersion,
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                    onOpen = ::go,
                )
            }
            entry<Route.Settings> {
                SettingsPage(onOpen = ::go)
            }
            entry<Route.Rotation> {
                RotationPage(
                    onBack = { backStack.removeLastOrNull() },
                    onCalibrate = onCalibrate,
                    onOpenSettings = onOpenSettings,
                )
            }
            entry<Route.Status> {
                StatusPage(
                    onBack = { backStack.removeLastOrNull() },
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                )
            }
            entry<Route.Diagnostics> {
                DiagnosticsPage(
                    onBack = { backStack.removeLastOrNull() },
                    hostState = hostState,
                )
            }
            entry<Route.About> {
                AboutPage(
                    onBack = { backStack.removeLastOrNull() },
                    appVersion = appVersion,
                    checking = checking,
                    onCheckUpdate = { checkUpdate(manual = true) },
                )
            }
            entry<Route.Language> {
                LanguagePage(
                    onBack = { backStack.removeLastOrNull() },
                    onRecreate = onRecreate,
                )
            }
            // ★ 2026-10-03 新增：实验功能（入口在「功能」主页最底下）。
            //   ⚠️ 它**不需要** AppLocale / onRecreate 那一套，也不需要宿主的状态
            //   （probed / hosted / hostState）—— 它只读自己那个开关。
            //   将来若要在这里显示"引擎在不在"，先想清楚：这一页的可操作性
            //   **不依赖引擎**（开关只影响界面），显示引擎状态反而会误导。
            entry<Route.Experimental> {
                ExperimentalPage(onBack = { backStack.removeLastOrNull() })
            }
        }

        LiquidGlassBar(
            backdrop = backdrop,
            selectedIndex = HomeTab.entries.indexOf(currentTab),
            onSelect = { switchTab(HomeTab.entries[it]) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ------------------------------------------------------------ 新版本弹窗
    //
    // 放在最外层：它应该盖住玻璃底栏（弹窗盖不住底栏会显得很怪）。
    updateInfo?.let { rel ->
        UpdateDialog(
            remote = rel,
            current = appVersion,
            onOpen = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)))
                }
                updateInfo = null
            },
            onDismiss = { updateInfo = null },
        )
    }
}

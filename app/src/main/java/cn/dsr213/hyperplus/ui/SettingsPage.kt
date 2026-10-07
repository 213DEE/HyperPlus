package cn.dsr213.hyperplus.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppToast
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RootShell
import cn.dsr213.hyperplus.RotateMode
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页：**设置**。
 *
 * 用户 2026-09-29 原话：「设置（包含当前状态、诊断和关于）」
 * ⇒ 这一页是**一张目录**，三行分别通向三个二级页，自己不承载任何内容。
 *
 * ★ 为什么不做成"设置页里直接铺开三段"（旧版就是那样）：
 *   当前状态 / 诊断 / 关于三块加起来有两屏多，其中「诊断」四张卡全是排障读数
 *   （触发与让步 / burst 指标 / 数据记录 / 最近事件），日常一条都不用看。
 *   铺在一起的结果是：**真正要动手的东西被读数淹掉** —— 这正是用户这一轮要解决的问题。
 *
 * ★ 唯一的例外是下面那条「配置通道」警告：它不是"内容"，而是**前置条件**
 *   （没它就什么都改不动），所以必须在这一页就看见，不能藏在二级页里 ——
 *   否则用户会一路点进去改半天开关，才发现改的根本没生效。
 *   ⚠️ 这也是旧版主页上那块卡片，只是**只在出问题时才渲染**（正常情况下一个字都不占）。
 *
 * ★★ 2026-10-03（libxposed 迁移）**判据换人**：此前它看的是 `AppPrefs.appChannelOk`
 *   —— "本应用进程有没有被 LSPosed 注入"，靠能不能以 `MODE_WORLD_READABLE` 打开 prefs
 *   来推断。迁移后引擎不再读 prefs 文件（改由 App 推广播），App **也就不再需要被注入**
 *   ⇒ 那个标志恒为假、界面会挂着一条**永久的假警报**。现在改看**引擎自己回传的那一格**
 *   （状态摘要里的 `cfgold` ⇒ [ModuleLink.State.cfgOk]）—— 它才是"改设置到底生不生效"
 *   的唯一真值来源，而且两侧共用同一格，不会出现"App 说好、引擎说坏"。
 *
 * ★ 2026-10-03 多语言：本页全部文案已改为 `stringResource`（硬编码中文一条不留）。
 *   ⚠️ 新加文案时**必须**同时补 `values` / `values-zh-rCN` / `values-zh-rTW` 三套，
 *     漏了就会在对应语言下掉回默认（英文）—— `_probe/check_strings_parity.py` 专查这个。
 */
@Composable
internal fun SettingsPage(
    /** 状态是否已至少读过一次（决定显示"正在读取"还是真状态） */
    probed: Boolean,
    /** 引擎是否在线（心跳够新鲜） */
    hosted: Boolean,
    hostState: ModuleLink.State?,
    onOpen: (Route) -> Unit,
) {
    // ★ 配置通道的判据 = 引擎回传的 `cfgold`（见类注释）。三种取值都要有交代：
    //   `null`（还没读到状态摘要 / 老格式里没这一项）**也提示** —— 这一页不假装它是好的，
    //   只是措辞上不说"引擎读不到"，因为那还没有证据。
    val cfgOk = hostState?.cfgOk

    // ------------------------------------------------------------ 引擎状态格（2026-10-06 从功能页搬来）
    //
    // 🔴 用户原话：「**把功能首页的「运行中」挪进设置**」。
    // ★ 搬运时**那块的东西一字未改**（读数、分支、文案资源全是原来那几条）——
    //   改的只是它住在哪一页。搬运理由见 [FunctionPage] 那段"已搬走"的注释。
    val ctx = LocalContext.current
    val modeInner by AppPrefs.modeInner.collectAsState()
    // ★★ 「分屏增强算不算开着」 —— 它住在 `Settings.Global`（引擎的开关，**分屏还没有
    //   界面开关**），所以这里直接读一次。
    //   ⚠️ 用 `remember(probed, hosted)` 缓存：读 `Settings.Global` 是一次跨进程调用，
    //     而本页每次重组都会重跑函数体 —— 不该让一个静态开关每帧去问一次系统。
    //     两把 key 都是"页面状态真的翻篇了"的信号（首次读到 / 引擎上下线）。
    //   ⚠️ 只在**引擎在线**时才去读：离线时这一格的结论由心跳决定，
    //     不必为一句用不上的话多打一次系统调用。
    val splitOn = remember(probed, hosted) {
        hosted && ModuleLink.splitTriggerMode(ctx) >= ModuleLink.SPLIT_TRIGGER_ACT
    }

    Scaffold(
        topBar = {
            // 与 [FunctionPage] **同一个**自建薄顶栏（两个主页共用形状；⛔ 只改一处会让
            // 两页不一样高 —— 这坑 10-06 早上刚踩过）。理由见 [FunctionPage] 头注释
            // 与 [HomeTopBar] 的 KDoc。
            HomeTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.app_name),
            )
        },
    ) { padding ->
        HomeColumn(padding) {
            // ------------------------------------------------ 配置通道没打通时，先说这件事
            // ⚠️ 判据是 `!= true`：`null` 与 `false` 都要提示。`null` 意味着"按现有证据
            //   它没在工作"，而这一页的职责就是挡住"改了没用的开关"，宁可先说一句。
            if (cfgOk != true) {
                TextCard(title = stringResource(R.string.settings_channel_broken)) {
                    Text(
                        text = stringResource(R.string.settings_channel_default),
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.paragraph,
                    )
                    // ⚠️ 2026-10-01 精简：上面那条已经说了"现在是什么情况"，
                    //   这里只需补一句**影响**（所有开关都不生效）+ 那句最容易被忽略的操作提示。
                    // ⚠️ 2026-10-02：把"修法："换成破折号 —— 少一个冒号级的停顿，读起来更顺。
                    // ⚠️ 2026-10-03：原来这里是两段字符串字面量相加（中间那个换行是为了
                    //   不让单行太长），现在合成**一条**资源 —— 中英文的断句位置不同，
                    //   拆成两半再拼会在英文里拼出别扭的断点。
                    Text(
                        text = stringResource(R.string.settings_channel_fix),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // ------------------------------------------------ 引擎状态（一行摘要 → 当前状态页）
            //
            // ★★ 2026-10-06 **从 [FunctionPage] 整格搬来**（用户原话：「把功能首页的
            //   "运行中"挪进设置」）。⭐ 内容与文案**一字未改**，只是换了一页 ——
            //   所以下面这段 2026-10-04 的原始说明继续有效：
            //
            // ★★ 2026-10-04 晚：这一条的主语从「旋转增强」改成**整个模块**。
            //   用户原话：「运行中改成整个模块的运行状态显示，不要再只显示旋转增强」。
            //   ⇒ 它下面那三句"正常态"的读数（当前模式 / 半自动计数 / 当前方向）**全部撤下**，
            //     换成一句话说清"现在启用了哪些增强"。
            //   ⚠️ 撤下的不是信息，只是那一格的**位置**：`takeoverNote` / `semiStatusText`
            //     在「当前状态」页里照旧在用（见 [StatusPage]）。
            //   ⚠️ 刻意**不**在这句话里说"能转屏幕"之类的机制，也不给"去打开它"这种祈使句 ——
            //     分屏现在**根本没有界面开关**（是 adb 设的），让用户去点一个不存在的东西
            //     正是本工程文案最忌讳的错误（见 `docs/UI_文案*`）。
            //
            // ⚠️ 它被放在「配置通道」警告**之后**：那条警告说的是"你现在改什么都没用"
            //   （前置条件坏了），比"引擎在不在"更靠前、更该先读 —— 这正是方案 R10
            //   「告警一律置顶、且比其他信息优先」那条。
            SectionCard {
                ArrowPreference(
                    title = engineHeadline(ctx, probed, hosted, hostState),
                    summary = moduleOneLiner(
                        ctx, probed, hosted, hostState,
                        rotationOn = modeInner != RotateMode.SYSTEM,
                        splitOn = splitOn,
                    ),
                    onClick = { onOpen(Route.Status) },
                )
            }

            // ------------------------------------------------ 监控与排查
            SectionCard(title = stringResource(R.string.settings_section_monitor)) {
                // ★ 2026-10-05 新增：权限管理（用户原话：「在设置页里面做一个权限管理，
                //   自动检查该获取的权限有没获取（LSPosed、root、修改系统设置权限），
                //   没获取的权限提供跳转按钮，点击跳转到到相关权限的设置页面」）。
                //   ⚠️ 为什么放在这一卡、而且排在最前：这一页查的三个权限是**前置条件**
                //   ——LSPosed 没激活 / root 没给 / 系统设置没授权，后面所有功能都是空转。
                //   把它摆在「当前状态」「诊断」之前，用户第一眼就知道"我是不是还差一步"。
                //   ⚠️ 与上面那条「配置通道」警告的分工：那条讲的是**引擎读不读得到配置**，
                //   这一行讲的是**权限给没给全**；两者都坏时，权限是更靠前的原因。
                //   ⛔ 别把它搬进「应用」卡：它不是应用级偏好（不能选、只能去系统里给）。
                ArrowPreference(
                    title = stringResource(R.string.settings_perm_title),
                    summary = stringResource(R.string.settings_perm_summary),
                    onClick = { onOpen(Route.Permissions) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_status_title),
                    summary = stringResource(R.string.settings_status_summary),
                    onClick = { onOpen(Route.Status) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_diag_title),
                    summary = stringResource(R.string.settings_diag_summary),
                    onClick = { onOpen(Route.Diagnostics) },
                )
            }

            // ------------------------------------------------ 维护（2026-10-05 新增）
            // ★ 用户原话：「设置页加一个重启 systemUI 的按钮」。
            //   用途：模块的改动要生效，得让 SystemUI 重新加载一次；以前只能 adb
            //   `killall com.android.systemui`，现在在手机上就能做。
            //
            // ⚠️ 它**破了本页「只当目录、自己不承载内容」那条约定**（见类注释），
            //   而且这是有意的：本页另外两卡装的都是 [ArrowPreference]（可跳转行），
            //   而这是**一个当场完成的动作** —— 按 UI 规范【一、⑥】「动作别画成可跳转行」，
            //   它必须是按钮形状。形状不同 ⇒ 塞进那两张卡里会让"点这行会发生什么"
            //   变得看不出来 ⇒ 单开一张卡。
            RestartSystemUiSection()

            // ------------------------------------------------ 应用
            SectionCard(title = stringResource(R.string.settings_section_app)) {
                // ★ 2026-10-03 新增：界面语言入口（用户原话：「在设置里面添加语言设置」）。
                //   放在「应用」卡里、关于之上 —— 它和「关于」一样是**应用级**设置，
                //   不属于「监控与排查」那一组。
                ArrowPreference(
                    title = stringResource(R.string.settings_language_title),
                    summary = stringResource(R.string.settings_language_summary),
                    onClick = { onOpen(Route.Language) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_about_title),
                    // ⚠️ 摘要里**不再提「捐赠支持」**（2026-10-01）：捐赠已搬到首页置顶，
                    //   关于页里没有它了，摘要再写着会把用户引到一个空页面上。
                    summary = stringResource(R.string.settings_about_summary),
                    onClick = { onOpen(Route.About) },
                )
            }
        }
    }
}

/**
 * 「重启系统界面」那一张卡 —— 一句说明 ＋ 一个按钮（＋ 二次确认弹窗）。
 *
 * ============================ 它为什么走 root ============================
 * 杀 SystemUI 进程需要 SYSTEM / SHELL / ROOT uid，App 进程没有这个能力。
 * 两条备选的取舍（与 [RootShell] 类注释里那张表同一个取向）：
 *
 * | | 请求引擎自杀（走配置通道） | root 直杀（现在这个） |
 * |---|---|---|
 * | 依赖 | 配置通道通、引擎在跑 | 只要 root 能用 |
 * | 失败面 | 通道是**单向、无回执**的 ⇒ 引擎没读到就是"点了没反应" | 一条命令，成败当场可见 |
 * | 代价 | 不额外要权限 | 首次弹一次 root 授权 |
 *
 * ⇒ 用户此前为「默认方向」拍板过同一句话：「**宁可多要一次授权，也不要"点了没反应"**」
 *   （见 `docs/默认方向*`）。这里沿用同一条取向。
 * ⚠️ 而且**不能**让引擎"自己重启自己"：那个动作没有任何回执 —— 用户点完，
 *   屏幕上什么都不会发生（除非它真的成了），恰好就是本工程反复要避免的那种按钮。
 *
 * ============================ 为什么加二次确认 ============================
 * ⛔ 用户没点名要它，是**有意加的**，理由：
 *  ① 点下去**立即**有可见后果（状态栏、导航栏当场消失，几秒后才回来）——
 *     用户如果不知道会发生什么，第一反应是"我把手机搞坏了"；
 *  ② 这个按钮最常被用的场景恰恰是「**模块刚改过、怀疑它有问题**」，
 *     而 SystemUI 重启是唯一能让一次坏改动**立刻显形**的动作 ——
 *     ⚠️ 万一模块真有问题，重启后状态栏可能起不来。这个代价必须由用户**先知道再决定**。
 *  按 UI 规范【一、③④】：弹窗按钮恒为 [TextButton]、一左一右、**取消在左确认在右**、
 *  ⛔ 不涂实心红底（"重"由位置和文案表达，红底会跟标题抢注意力）。
 *
 * ============================ 成功时不弹提示（有意） ============================
 * ⛔ 别在成功分支加一句 Toast。状态栏消失又回来**本身就是反馈**，再补一句话是噪音；
 * 而且那时 SystemUI 刚死，任何"确认类"提示都在跟用户的直观感受重复。
 * ★ 只有**失败**（这台机器上没有可用的 su）才说话 —— 因为那种情况屏幕上什么都没发生，
 *   不说用户就会一直点。文案见 `settings_restart_no_root`。
 */
@Composable
private fun RestartSystemUiSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 二次确认弹窗的可见性。★ 点了按钮**只弹框、不执行** ——
    //   ⛔ 别写成 `onClick = { scope.launch { … } }` 直接开杀（那就没有第二次机会了）。
    var showDialog by remember { mutableStateOf(false) }

    TextCard(title = stringResource(R.string.settings_restart_section)) {
        Note(stringResource(R.string.settings_restart_note))
        TextButton(
            text = stringResource(R.string.settings_restart_btn),
            onClick = { showDialog = true },
            modifier = BTN_SLOT,
        )
    }

    if (showDialog) {
        RestartSystemUiDialog(
            onDismiss = { showDialog = false },
            onConfirm = {
                showDialog = false
                scope.launch {
                    // ⚠️ `killall` 是 [RootShell.runRaw] 的**设计内用法**（见那边的 KDoc 与
                    //   `SplitProbe.kt` 的注释）——它不是 Settings 读写，只能走这条路。
                    // ★ 返回 null 只代表「这台机器上没有能用的 su」（能力缺失），
                    //   与「命令执行失败」是两回事，别混。
                    val out = RootShell.runRaw(SYSTEMUI_KILL_CMD)
                    if (out == null) {
                        AppToast.show(ctx, ctx.getString(R.string.settings_restart_no_root))
                    }
                }
            },
        )
    }
}

/**
 * 重启前的二次确认框。**形状与 [UpdateDialog] / [ThermalGuardDialog] 完全一致** ——
 * 用户在本应用里见到的所有弹窗都是同一个样子，不必为"这个弹窗的按钮为什么不一样"多花一秒。
 */
@Composable
private fun RestartSystemUiDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_restart_dlg_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_restart_dlg_msg),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 间距 8dp 的写法照抄 [UpdateDialog]（见 UI 规范【一、③】）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                TextButton(text = stringResource(R.string.settings_restart_dlg_cancel), onClick = onDismiss)
                TextButton(text = stringResource(R.string.settings_restart_dlg_confirm), onClick = onConfirm)
            }
        }
    }
}

/**
 * 重启 SystemUI 的那条命令。
 *
 * ★ `killall` 之后**不需要**（也读不到）回执：SystemUI 是 init 的托管服务，
 *   进程一死就会被拉起来，没有任何需要我们接手的收尾。
 */
private const val SYSTEMUI_KILL_CMD = "killall com.android.systemui"

// ================================================================
// 引擎状态格的那三句文案
//
// ★★ 2026-10-06 **从 `FunctionPage.kt` 整段搬来**（用户原话：「把功能首页的
//   "运行中"挪进设置」）。搬迁做到了**逐字复制**：三个函数体、每一个分支、
//   每一处资源引用、连注释都一个字没动 —— 所以它们在功能页时代的所有结论继续成立，
//   那些理由（尤其是"正常态第四句为什么是『启用了哪些增强』而不是实时读数"）也一并搬了过来。
// ⛔ 别在这里改写任何一句：改它们属于**文案变更**，按本工程铁律要用户逐字拍板。
// ⛔ 也别再搬回去（功能页不再需要状态输入，见那边"已搬走"的注释）。
// ================================================================

/**
 * 状态头条 —— 一句话说清**这一整套增强**现在**在不在**。
 *
 * ⚠️ 全量梳理（2026-09-30）：这里原来叫"引擎"，而且「读不到引擎」「引擎已离线」都是
 *   开发者视角的说法。改成"旋转服务"之后，**四句话的意思一个都没动**，只是换了个
 *   用户能立刻对上的主语 —— 界面别处提到同一件事时也必须用这个词（见 [moduleOneLiner]）。
 *
 * ⚠️ **2026-10-04 晚再改一次**：用户原话「运行中改成整个模块的运行状态显示，
 *   不要再只显示旋转增强」⇒ 主语从"旋转服务"再抬一级到**"增强服务"**
 *   （它同时提供旋转与分屏两件事，只叫旋转就把另一个功能说没了）。
 *   四句话的分支结构没动，只换了词。
 */
private fun engineHeadline(
    ctx: Context,
    probed: Boolean,
    hosted: Boolean,
    hs: ModuleLink.State?,
): String = when {
    !probed -> ctx.getString(R.string.function_state_probing)
    hs == null -> ctx.getString(R.string.function_state_noservice)
    !hosted -> ctx.getString(R.string.function_state_offline)
    else -> ctx.getString(R.string.function_state_running)
}

/**
 * 状态副标题 —— 一句人话，说明**为什么是上面那个结果**、以及**它现在管着什么**。
 *
 * ⚠️ 措辞只讲能确证的事：心跳来自增强服务单方面上报（见 `ModuleLink`），
 *   所以"离线"能确定是"心跳过期"，但**不能**由此推断它"死了" ——
 *   系统界面重启后它会自己回来，文案里明说了这一点，避免用户白折腾。
 *
 * ★★ **2026-10-04 晚：前三句照旧，第四句（正常态）整个换掉。**
 *   原来它报的是旋转增强的实时读数（当前模式 / 半自动计数 / 当前方向 / 首次识别中），
 *   而这一格的主语现在是**整个模块**（见 [engineHeadline]）——
 *   拿一个功能的读数值去充当另一个主语的状态，正是这句话被点名改掉的原因。
 *   ⇒ 现在它说"**启用了哪些增强**"（判据见 [enabledSummary]）。
 */
private fun moduleOneLiner(
    ctx: Context,
    probed: Boolean,
    hosted: Boolean,
    hs: ModuleLink.State?,
    /** 旋转增强是否在介入（= 模式不是「跟随系统」） */
    rotationOn: Boolean,
    /** 分屏增强是否已开启（= `SplitTrigger` 处在真动作档） */
    splitOn: Boolean,
): String = when {
    // ⚠️ 2026-10-02 全量精简：原文「旋转服务常驻在系统界面进程里，本界面只负责显示它回传的
    //   状态」是**进程实现**（它住哪个进程、谁给谁回传），用户不关心；上面那行大字已经说了
    //   "正在读取运行状态…"，这里只需把当下这件事再说一遍。
    !probed -> ctx.getString(R.string.function_state_probing_long)
    hs == null ->
        // ⚠️ 2026-10-02 精简：砍掉"旋转服务只在系统界面进程里运行"（进程实现）——
        //   那句唯一对用户有用的推论（"没有它只能保存设置、转不动屏幕"）已并进括号。
        ctx.getString(R.string.function_no_report)
    !hosted ->
        // ⚠️ 2026-10-03 多语言时发现这里原来是**三段**拼接，其中"（超过 N 秒视为离线）；"
        //   那一段一度被漏掉 —— 合成一条资源之后这类"拼接漏段"不会再发生。
        ctx.getString(
            R.string.function_lost,
            hs.heartbeatAgoSec,
            ModuleLink.HOST_LOST_AFTER_SEC,
        )
    else -> enabledSummary(ctx, rotationOn, splitOn)
}

/**
 * 正常态那一句：**现在启用了哪些增强**。
 *
 * ============================ 为什么是这个内容 ============================
 * 这一格在正常态唯一还能回答的问题就是它 —— 引擎活着这件事上面那行大字已经说了，
 * 而"每个功能现在是什么档"各自住在下面那一行里（`旋转增强：跟随系统`）。
 * ★ 判据全部来自**App 读得到的东西**：旋转看 [AppPrefs.modeInner]（不是「跟随系统」就是在介入），
 *   分屏看 `Settings.Global` 的档位（见 [ModuleLink.splitTriggerMode]）。
 *
 * ⚠️ **一个都不启用时不许给祈使句**。曾想写"可在下面按需打开"，但**分屏现在根本没有界面开关**
 *   （是 adb 设的）⇒ 那句话会把用户支使去点一个不存在的东西。
 *   这是本工程文案的硬纪律：写"我该怎么办"之前先问"这动作真的存在吗"。
 */
private fun enabledSummary(ctx: Context, rotationOn: Boolean, splitOn: Boolean): String {
    val names = buildList {
        if (rotationOn) add(ctx.getString(R.string.function_rotation_title))
        if (splitOn) add(ctx.getString(R.string.function_split_title))
    }
    if (names.isEmpty()) return ctx.getString(R.string.function_running_none)
    return ctx.getString(
        R.string.function_running_summary,
        names.joinToString(ctx.getString(R.string.function_running_sep)),
    )
}

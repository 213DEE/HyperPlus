package cn.dsr213.hyperplus.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 主页：**功能**。
 *
 * ============================ 为什么这一页只有几行（2026-09-29）============================
 * 用户原话：「所有功能不要挤在同一页，做成不同的主页（功能、设置（包含当前状态、诊断和关于）），
 * 功能页设置二级菜单旋转增强，把旋转的相关设置都放进二级菜单里面，**因为以后还要添加更多功能**」。
 *
 * ⇒ 这一页的定位是**功能目录**，不是设置面板。它会长大（以后每加一个能力就在这里多一行），
 *   但**不应该变长**（每个能力的细节都属于它自己的二级页）。
 *   所以这里刻意只放「入口 + 当前取值摘要」，一个开关控件都不放 ——
 *   一旦这一页开始出现开关，就说明有内容没被收进二级页。
 *
 * ============================ 首行那个状态入口 ============================
 * 顶上那一条是**唯一**保留在这一页的"仪表盘"元素，理由：
 *   引擎在不在，决定了这一页下面所有入口点进去以后能不能真的改到东西
 *   （模块没被 LSPosed 启用时，所有开关都只是装饰品）。
 *   把它放在最上面，用户第一眼就知道"现在改的东西到底会不会生效"。
 * ⚠️ 它只给**一句话**摘要，不留任何读数 —— 读数全部住在「设置 → 当前状态 / 诊断」，
 *   两处显示同一件事只会让两边都对不上（那正是旧版一个大页面最糟的地方）。
 *
 * 顶栏用 [TopAppBar]（大标题版）而不是 `SmallTopAppBar`：
 *   HyperOS 的原生应用首页就是大标题 + 副标题，二级页才用小标题 —— 这个形状本身就在告诉用户
 *   "你在首页"还是"你在里面"。
 * ⚠️ **刻意不传 `scrollBehavior`**：库的折叠顶栏要配 `MiuixScrollBehavior` +
 *   `nestedScroll` + 把 `heightOffsetLimit` 设对（官方示例用了三个自定义工具函数才拼起来，
 *   见 `_probe/miuix_ex/utils/`）。不传时标题钉在展开态，代价只是"滚下去标题不会缩小"，
 *   而换来的是一处**不需要跟着库版本走**的稳定代码。这个取舍记在
 *   `docs/UI重构_液态玻璃_2026-09-29.md`。
 */
@Composable
internal fun FunctionPage(
    appVersion: String,
    /** 状态是否已至少读过一次（决定显示"正在读取"还是真状态） */
    probed: Boolean,
    /** 引擎是否在线（心跳够新鲜） */
    hosted: Boolean,
    hostState: ModuleLink.State?,
    onOpen: (Route) -> Unit,
) {
    // ★ 读的是**内屏**那一份真身（2026-09-29 起只剩这一份）：
    //   外屏增强已整个删除，摘要只报内屏档位，并明确写出"外屏不介入"，
    //   否则用户在外屏翻设置会以为"模式没生效"是 bug。
    //   （`AppPrefs.mode` 是派生值，只给引擎用，见那边的注释。）
    // ⚠️ 这里原来还读一份 `gateEnabled`（用来给「应用名单」入口行写"名单已关闭"的警告），
    //   2026-10-01 入口行删掉之后它没了用处 ⇒ 一并删除。那句警告没有丢，
    //   它搬到了名单内容块自己的说明卡里（见 [AppWhitelistSection]），
    //   在那里离开关更近、更容易看懂。
    val modeInner by AppPrefs.modeInner.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.function_title),
                largeTitle = stringResource(R.string.function_title),
                // ⚠️ 副标题是「应用名 + 版本号」，两者都不本地化 ⇒ 不用资源拼接
                subtitle = "HyperPlus $appVersion",
            )
        },
    ) { padding ->
        HomeColumn(padding) {
            // ------------------------------------------------ 捐赠（置顶，2026-10-01 用户点名）
            //
            // ⚠️ 这是本页**唯一**直出内容的卡（其余都只是入口），也是**唯一**排在引擎状态
            //   之上的元素 —— 两条例外都是用户点名的，别照这个先例往下加：
            //   用户原话「捐赠信息不要放到二三级菜单，直接在首页置顶，把二维码直接放出来」。
            //   见他原话之前的样子是「设置 → 关于 → 捐赠支持 → 弹窗」，点三层才看得到码。
            DonateCard()

            // ------------------------------------------------ 引擎状态（一行摘要 → 当前状态页）
            SectionCard {
                ArrowPreference(
                    title = engineHeadline(ctx, probed, hosted, hostState),
                    summary = engineOneLiner(ctx, probed, hosted, hostState),
                    onClick = { onOpen(Route.Status) },
                )
            }

            // ------------------------------------------------ 旋转
            SectionCard(title = stringResource(R.string.function_section_rotation)) {
                // ★ 2026-10-01：下面**原本还有一条「应用名单」入口**，已删除 ——
                //   那一页合并进「旋转增强」了。⇒ **这一行摘要现在是用户唯一能知道
                //   "应用名单去哪了"的地方**，所以它必须点名提到应用名单，
                //   否则老用户会以为功能被砍了。⛔ 改摘要的时候别把这几个字删掉。
                ArrowPreference(
                    title = stringResource(R.string.function_rotation_title),
                    // ⚠️ 2026-10-02：原来这里是 `"外屏交给系统处理  " + "（模式 / …）"` ——
                    //   两个空格连在一起，真机上看得出来多空了一块（截图核过）。
                    // ⚠️ 2026-10-03 多语言：改成**一条带占位符的资源**。原来拆两段是
                    //   为了避开那个双空格，而中英文的断句位置本来就不一样，
                    //   拆开拼会在英文里拼出别扭的断点 ⇒ 合成一条，让译者看到整句。
                    summary = stringResource(
                        R.string.function_rotation_summary,
                        stringResource(modeInner.labelRes),
                    ),
                    onClick = { onOpen(Route.Rotation) },
                )
            }

            // ------------------------------------------------ 实验功能（**置底**，2026-10-03 用户点名）
            //
            // 用户原话：「在首页新增一个"实验功能"**置底**，里面添加"自适应旋转"开关，
            //   打开开关之后，旋转增强里面才显示自适应旋转的选项」。
            //
            // ★ 它是**入口**不是开关 —— 与这一页"只放目录、不放控件"的定位一致
            //   （见上面的类注释）。真正的开关在 [ExperimentalPage] 里。
            // ⚠️ 位置**固定在最后**：用户点名"置底"，而且它以后还会长（别的实验功能也进这一页），
            //   放在中间会随着功能变多被挤到看不清的地方。
            SectionCard(title = stringResource(R.string.function_section_experimental)) {
                ArrowPreference(
                    title = stringResource(R.string.experimental_title),
                    summary = stringResource(R.string.function_experimental_summary),
                    onClick = { onOpen(Route.Experimental) },
                )
            }

            // ------------------------------------------------ 「关于这一页」整卡已删除
            //
            // ⚠️ 2026-10-01 用户原话：「把现在的无用的注释优化一下，你现在这样只是给我看的，
            //   要改成给普通用户看的」。
            //   这张卡原来的内容是：「这一页只放「入口」。跟旋转有关的一切都在「旋转增强」里，
            //   **以后新增的能力也会各自有一个二级页**；状态、诊断、关于在「设置」里。」
            //   —— 后半句是**写给维护者的信息架构说明**（"以后新增的能力"跟当下的用户毫无关系），
            //   而这页本身只有三四行，用户扫一眼就明白结构，根本不需要有人解释。
            //   ⇒ 整卡删除（**不是**改写：改写只是把同一句话换个说法，still 是废话）。
            //
            // ★ 但下面这条规则**仍然有效**，只是它的归宿在代码里、不在界面上：
            //   用户那句「因为以后还要添加更多功能」是这一页全部结构取舍的理由，
            //   不写下来的话，下一个人很可能顺手就把新功能的开关堆回这一页。
        }
    }
}

/**
 * 状态头条 —— 一句话说清旋转服务现在**在不在**。
 *
 * ⚠️ 全量梳理（2026-09-30）：这里原来叫"引擎"，而且「读不到引擎」「引擎已离线」都是
 *   开发者视角的说法。改成"旋转服务"之后，**四句话的意思一个都没动**，只是换了个
 *   用户能立刻对上的主语 —— 界面别处提到同一件事时也必须用这个词（见 [engineOneLiner]）。
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
 * 状态副标题 —— 一句人话，说明**为什么是上面那个结果**、以及**现在在干什么**。
 *
 * ⚠️ 措辞只讲能确证的事：心跳来自旋转服务单方面上报（见 `ModuleLink`），
 *   所以"离线"能确定是"心跳过期"，但**不能**由此推断它"死了" ——
 *   系统界面重启后它会自己回来，文案里明说了这一点，避免用户白折腾。
 */
private fun engineOneLiner(
    ctx: Context,
    probed: Boolean,
    hosted: Boolean,
    hs: ModuleLink.State?,
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
    hs.mode == RotateMode.SYSTEM.name -> ctx.getString(R.string.function_mode_system)
    // ★ 未生效时**必须说清原因**（2026-09-29 修）：旧文案一律写"可能缺「修改系统设置」授权"，
    //   而 `takeover=false` 最常见的情形其实是「当前应用在名单里」——
    //   那是**按设计**让位，不是故障。两种情形分开说，判据见 [takeoverNote]。
    !hs.takeover -> takeoverNote(ctx, hs) ?: ctx.getString(R.string.function_not_active)
    hs.mode == RotateMode.SEMI.name ->
        semiStatusText(ctx, hs.semiShown, hs.semiTapped, hs.overlayOk, hs.overlayType)
    hs.rotation < 0 -> ctx.getString(R.string.function_await_first)
    // ⚠️ 原来的 "　状态机 ${hs.decider}" 已删（2026-09-30 文案梳理）：
    //   `STABLE` / `SETTLING` 这类内部状态机名对用户没有任何可操作性，
    //   摆在首屏只会让人以为出了问题。需要它的是「诊断」页，那里有完整读数。
    else -> ctx.getString(R.string.ro_rotation, rotName(ctx, hs.rotation))
}

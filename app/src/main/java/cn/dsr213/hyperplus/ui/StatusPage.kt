package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**当前状态**。
 *
 * 「设置 → 当前状态」。装的是旧版主页最上面那张卡 —— **一句话说清引擎现在在干什么**。
 *
 * ★ 它与 [DiagnosticsPage] 的分工（这两页最容易被人合并，所以写清楚）：
 *   - 这一页回答「**我现在能不能用**」：引擎在不在、有没有接管、半自动按钮弹不弹得出来、
 *     判定的是哪个方向。**四句话以内**，用大字号，给人看。
 *   - [DiagnosticsPage] 回答「**为什么不好用**」：传感器 / 授权 / 配置通道 / 前摄 id /
 *     门控 / 冲突计数 / 票面 / 符号位证据。十几行等宽读数，给排障看。
 *   ⇒ 判断标准很简单：**这一页该出现"次数"和"毫秒"吗？不该。** 那些是诊断页的东西。
 *
 * ⚠️ 2026-09-30 全量文案梳理：这一页原来通篇说"引擎/接管/判定/状态机"，
 *   那是开发者视角。现在统一成「旋转服务 / 方向控制 / 识别 / 当前方向」，
 *   **行为一个字没改，只是换了主谓**。改这一页时别把"引擎"写回来 ——
 *   用户看到"引擎"会以为机器里有个马达在转。
 *
 * ⚠️ 注意"运行中"分支里那三句话**各自独立**（本应用已介入 / 已在控制方向 / 当前在做什么），
 *   它们是三个不同层次的事实，不能合成一句：未生效时方向根本不是本应用决定的，
 *   这时再去说"方向是什么"就是编的。
 *
 * ⚠️ 2026-10-03 多语言：句子里凡是**拼接**的地方都拆成了"先取局部变量、再相加"——
 *   不要在 `buildString { append(stringResource(…)) }` 里直接调 `stringResource`：
 *   `buildString` 的 lambda 虽然是 inline，但在实参位置调用 Composable 属于**边界写法**，
 *   一旦哪天被换成非 inline 的版本就会静默编译失败。显式 val 没有这个隐患。
 */
@Composable
internal fun StatusPage(
    onBack: () -> Unit,
    probed: Boolean,
    hosted: Boolean,
    hostState: ModuleLink.State?,
) {
    /** 界面里反复要用的引擎状态快照（唯一来源） */
    val hs = hostState
    val form by AppPrefs.screenForm.collectAsState()
    val ctx = LocalContext.current

    SubPage(title = stringResource(R.string.status_title), onBack = onBack) {
        TextCard {
            when {
                // ⚠️ 2026-10-01 精简：这里原来还有一句「旋转服务常驻在系统界面进程里，
                //   本界面只负责显示它回传的状态」—— 那是**进程实现**，用户不需要知道它住哪，
                //   而"正在读取"四个字已经说清当下在干什么 ⇒ 删除。
                !probed -> {
                    StatusHeadline(stringResource(R.string.function_state_probing))
                }

                hs == null -> {
                    StatusHeadline(stringResource(R.string.function_state_noservice))
                    // ⚠️ 这句是**排障必需**（它告诉用户去哪儿修），别当废话删掉。
                    // ⚠️ 2026-10-02 精简："系统模块"→"模块"、"本应用"去掉 —— 主语越少越好读。
                    Note(stringResource(R.string.status_no_report))
                }

                !hosted -> {
                    StatusHeadline(stringResource(R.string.function_state_offline))
                    // ⚠️ 与首页 [FunctionPage] 共用同一条资源（两处说的本来就是同一件事）
                    Note(
                        stringResource(
                            R.string.function_lost,
                            hs.heartbeatAgoSec,
                            ModuleLink.HOST_LOST_AFTER_SEC,
                        ),
                    )
                }

                else -> {
                    StatusHeadline(stringResource(R.string.function_state_running))
                    // ⚠️ 2026-10-01 精简：删掉"运行在系统界面进程里"（进程实现属于「诊断」），
                    //   留下用户真正关心的那句 —— **不用一直开着它**。
                    val background = stringResource(R.string.status_background)
                    val phaseSuffix = if (hs.phase != "ready") {
                        stringResource(R.string.status_phase_suffix, phaseText(ctx, hs.phase))
                    } else {
                        ""
                    }
                    Note(background + phaseSuffix)
                    Note(
                        when {
                            hs.mode == RotateMode.SYSTEM.name ->
                                stringResource(R.string.function_mode_system)
                            // ★ 与 [FunctionPage] 用**同一个** [takeoverNote]：
                            //   这句文案曾被写成"可能缺授权"而在"当前应用在名单里"时误报，
                            //   两处各写一份的话下次照样会只改一处。
                            !hs.takeover -> takeoverNote(ctx, hs)
                                ?: stringResource(R.string.function_not_active)
                            hs.mode == RotateMode.SEMI.name ->
                                semiStatusText(
                                    ctx,
                                    hs.semiShown,
                                    hs.semiTapped,
                                    hs.overlayOk,
                                    hs.overlayType,
                                )
                            hs.rotation < 0 -> stringResource(R.string.function_await_first)
                            // ⚠️ 原来的 "　(${hs.decider})" 已删（2026-09-30 文案梳理）：
                            //   状态机名是内部实现，用户看了没有任何可操作性。
                            else -> stringResource(
                                R.string.status_screen_rotation,
                                stringResource(form.labelRes),
                                rotName(ctx, hs.rotation),
                            )
                        },
                    )
                }
            }
        }

        // ⚠️ 2026-10-01 整卡删除。这里原本有一张「这一页和「诊断」有什么不同」，
        //   内容是在解释**信息架构**（哪一页放什么）—— 属于写给维护者看的东西，
        //   用户看两个页面标题就明白了，不需要有人教。理由同 [FunctionPage] 里那条。
        //   ★ 但它讲的那个判断仍然成立，只是归宿在代码里：
        //     **这一页只回答「现在能不能用」，排障读数全部住「诊断」。**
    }
}

/**
 * 状态大字标题（与 HyperOS 设置页里"标题行"的字重一致）。
 *
 * ★ 单独抽出来的原因：四个分支都要它，字号与字重必须完全一致，
 *   写在四处迟早会有一处是 20sp 另一处是 22sp（旧版就是这样）。
 */
@Composable
private fun StatusHeadline(text: String) {
    Text(
        text = text,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

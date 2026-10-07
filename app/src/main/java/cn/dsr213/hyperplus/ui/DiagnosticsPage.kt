package cn.dsr213.hyperplus.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.dsr213.hyperplus.DiagnosticsCollect
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import java.io.File
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**诊断**（用户 2026-09-28 点名要的收纳）。
 *
 * 装的是**实时快照**两段（★ 2026-10-06 起）：
 *   1. 触发与让位 —— 方向传感器 / 修改系统设置权限 / 设置同步 / 前置摄像头编号 /
 *      应用名单控制 / 摄像头被占用 / 服务响应
 *   2. 识别指标 —— 识别轮次与计数、本轮识别结果、方向基准校验、校准值
 *   3. 运行记录 —— **一行入口**（→ 三级页 [Route.DiagRecords]）
 *
 * ★ 2026-10-06：原来的第 3、4 段（「数据记录」＋「最近事件」）**搬去了三级页**
 *   —— 它们是"历史"（记在哪、最近干了什么），与上面两段的"实时快照"
 *   （此刻在怎么工作）不是一类东西。用户原话：「**还是很乱，可以适当增加三级菜单，
 *   要让所有功能都清晰明了**」。
 *   ⚠️ 搬的只是**位置**：读数口径、判断分支、阈值一个字未改（见本注释末尾那条纪律）。
 *
 * ★ 设计原则：**不新增任何数据通道**。这里用的还是同一份 [ModuleLink.State]，
 *   只是换了个地方显示 —— 所以不存在"两页看到的数据不一致"这种可能。
 *   换句话说，进这一页不该改变任何行为，它纯粹是"把读数挪远一点"。
 *
 * ============================ 2026-09-29 这次改造改了什么 ============================
 * ① **返回口换成顶栏导航位**（[SubPage]）。旧版是页内一个 `TextButton("← 返回")`，
 *    当时的理由是"MiuiX 各版本 TopAppBar 导航位参数不一致"；现在用的 0.9.4
 *    明确有 `navigationIcon`，再让用户滚回页面顶部找返回就不合适了。
 * ② **删掉与 `UiCommon.kt` 重复的五个函数**（`kv` / `voteLine` / `signLine` /
 *    `fmt` / `fmtDur` / `rotName` / `hostedEvents`）—— 它们在拆分时被移到了
 *    `UiCommon.kt`（多个页面都要用），这个文件里那份是副本，留着将来必出"改了一处漏一处"。
 * ③ **卡片容器换成 [SectionCard] / [TextCard]**：纯读数的用 `TextCard`（16dp 内边距），
 *    装偏好控件的用 `SectionCard`（0dp，让控件自带边距生效）。
 *
 * ⚠️ **数值口径不许改**（2026-09-30 澄清）。诊断页最容易在重构里"顺手润色"，
 *   而这里的每一行都是排障时被引用过的**判据**（比如 `hs.cfgOk == null → 写"未知"`，
 *   是刻意不假装它是好的）。口径一改，旧结论就失效了。
 *   ⇒ 允许改的是**说法**（把"引擎 / 接管 / 前摄 / 票面"换成用户能懂的主谓，2026-09-30 已做），
 *     ⛔ 不许改的是**判断分支、阈值、以及"读不到时怎么报"**。
 *   ⚠️ 2026-10-03 多语言**同样只动说法**：每一条资源的中文值与原字面量逐字一致，
 *     `when` 的分支顺序、条件、阈值一个都没动。
 */
@Composable
internal fun DiagnosticsPage(
    onBack: () -> Unit,
    hostState: ModuleLink.State?,
    /** 打开三级页「运行记录」—— 与 [RotationPage] 同一个形状，理由见那边。 */
    onOpen: (Route) -> Unit,
) {
    val hs = hostState
    val ctx = LocalContext.current

    // ★ 导出是**一次性的重活**（要跑 logcat，有 root 时还要起一个 su）⇒ 用 busy 守卫挡住连点，
    //   失败时把话说在卡里，⛔ 不弹东西、不假装成功。
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }

    SubPage(title = stringResource(R.string.diag_title), onBack = onBack) {
        // ---------------------------------------------------- 触发与让步
        TextCard(title = stringResource(R.string.diag_section_trigger)) {
            if (hs == null) {
                Note(stringResource(R.string.diag_no_data))
            } else {
                kv(
                    stringResource(R.string.diag_kv_sensor),
                    stringResource(
                        if (hs.sensorAvailable) R.string.diag_sensor_on else R.string.diag_sensor_off,
                    ),
                )
                // ⚠️ **三态**（2026-10-05 修）。原来只有「已具备 / 缺失」两支，
                //   把「状态串里没有 `grant` 这一项」（= 引擎没在跑 / 老格式）也说成了「缺失」——
                //   那是**误报**：用户据此去系统设置里找本应用，根本找不到
                //   （清单刻意没声明 `WRITE_SETTINGS`）。实测 `com.android.systemui` 的
                //   `WRITE_SETTINGS` 是 `granted=true`（`android.uid.systemui` 自带）⇒
                //   真缺的可能性极低，「没读到」才是常见情况。与 `cfgold` 那边同一套做法。
                kv(
                    stringResource(R.string.diag_kv_write),
                    when {
                        !hs.grantReported -> stringResource(R.string.diag_grant_unknown)
                        hs.writeGranted -> stringResource(R.string.diag_granted)
                        else -> stringResource(R.string.diag_missing)
                    },
                )
                // ★ 从前这块叫「配置同步（root）」。配置通道换成 App 推广播之后
                //   不再需要 root，显示的是**引擎有没有读到配置** —— 它才是现在唯一会出问题的地方。
                //   ⚠️ 2026-10-03（libxposed 迁移）**少了一支**：此前这里先看"App 侧自检"
                //      （`appChannelOk`，靠能不能以 `MODE_WORLD_READABLE` 打开 prefs 判断
                //      本应用有没有被注入）。迁移后引擎不再读 prefs 文件 ⇒ 那个判据恒为假，
                //      留着就是一条永久假警报 ⇒ 已随状态一起删除。
                //      ⛔ 别再补回来：需要"被注入"的那个前提已经没了。
                //   ⚠️ 三态（**这是它剩下的全部价值**）：老格式摘要里没有 `cfgold` 字段时
                //      `cfgOk` 为 null ⇒ 写"未知"，不假装它是好的（诊断不许说谎）。
                kv(
                    stringResource(R.string.diag_kv_cfg),
                    when {
                        hs.cfgOk == false -> {
                            val reason = if (hs.cfgMsg.isNotEmpty()) {
                                hs.cfgMsg
                            } else {
                                stringResource(R.string.diag_cfg_no_reason)
                            }
                            stringResource(R.string.diag_cfg_not_ready_engine, reason)
                        }
                        hs.cfgOk == null -> stringResource(R.string.diag_cfg_unknown)
                        else -> stringResource(R.string.diag_cfg_ok)
                    },
                )
                // ★ 当前前摄 id：本机有 5 个前摄（1,5,7,8,9），引擎会在它们之间轮换。
                //   不显示的话，"轮换"这件事在界面上完全不可观测，只能翻日志。
                kv(
                    stringResource(R.string.diag_kv_camera),
                    if (hs.cameraId.isEmpty()) {
                        stringResource(R.string.diag_camera_none)
                    } else {
                        stringResource(R.string.diag_camera_ok, hs.cameraId)
                    },
                )
                // ★ 前台门控（判据 2026-09-28 已从"读声明朝向去推"换成"前台包在不在名单里"）。
                //   ⚠️ 必须把 fgr（读得到吗）一并显示 —— 读不到时门控**根本不会生效**，
                //      不写清楚用户会以为它在工作（"诊断不许说谎"）。
                //   ⚠️ 2026-09-29 改名 + 改内容：原名「白名单门控」、内容一律写"在白名单里"，
                //      而实际有**四条**停手原因（白名单 / 外屏桌面 / 外屏声明 / 实测不可控）。
                //      只写白名单会让"外屏桌面为什么要停手"看起来像 bug —— 这里如实报是哪一条。
                kv(
                    stringResource(R.string.diag_kv_gate),
                    when {
                        !hs.foregroundReadable -> stringResource(R.string.diag_gate_unreadable)
                        hs.foregroundGated -> {
                            val reason = stopReasonText(ctx, hs.foregroundStop)
                            if (hs.handoffRotate) {
                                stringResource(R.string.diag_gate_gated_rotate, hs.foregroundPkg, reason)
                            } else {
                                stringResource(R.string.diag_gate_gated_hold, hs.foregroundPkg, reason)
                            }
                        }
                        else -> {
                            val pkg = if (hs.foregroundPkg.isNotBlank()) {
                                hs.foregroundPkg
                            } else {
                                stringResource(R.string.diag_gate_current_app)
                            }
                            stringResource(R.string.diag_gate_normal, pkg, hs.skipGateCount)
                        }
                    },
                )
                // ★ 前台**自声明**的方向：判据换掉之后它**不再参与任何判断**，纯粹是排查线索 ——
                //   "这个应用为什么不跟手"，第一件要看的就是它自己声明了什么。
                if (hs.foregroundReadable) {
                    kv(
                        stringResource(R.string.diag_kv_fg_orientation),
                        stringResource(
                            R.string.diag_fg_orientation,
                            hs.foregroundOrientation.ifEmpty { "—" },
                        ),
                    )
                }
                // ⛔ 已删掉「本屏安装朝向偏移」这一行（2026-09-30）。它报的判据
                //   （"展开后应当从 0 变 2"）建立在**已被推翻**的前提上 —— 真机 + 源码双重证实
                //   两块屏在 `USER_ROTATION` 坐标系里**没有**朝向差，那个值恒为 0
                //   （见 `PanelOrientation` 类注释）。留着只会让人照着错误前提去推理，
                //   而"恒为 0"本身没有任何信息量。上报通道（总线 `pofs=`）还在，只是不再展示。
                kv(
                    stringResource(R.string.diag_kv_whitelist),
                    stringResource(R.string.diag_whitelist_value, hs.whitelistSize),
                )
                // ★ 冲突计数是「注视感知确实在抢」的直接证据 —— 它统计的是
                //   **整轮一帧都没采到**的次数（= 前摄被别人占着），不掺任何配置猜测。
                kv(
                    stringResource(R.string.diag_kv_conflict),
                    if (hs.conflictCount == 0) {
                        stringResource(R.string.diag_conflict_none)
                    } else {
                        stringResource(R.string.diag_conflict_some, hs.conflictCount)
                    },
                )
                kv(stringResource(R.string.diag_kv_uptime), fmtDur(ctx, hs.uptimeSec))
                // ★ "心跳"只证明引擎活着，"在不在干活"看上面的帧数/人脸数 —— 两件事分开说，
                //   免得引擎闲下来（稳定态不上报）时被误读成失联。
                kv(
                    stringResource(R.string.diag_kv_heartbeat),
                    when {
                        hs.heartbeatAgoSec < 0 -> "—"
                        hs.hostAlive -> stringResource(
                            R.string.diag_heartbeat_ok,
                            hs.heartbeatAgoSec,
                        )
                        else -> stringResource(
                            R.string.diag_heartbeat_bad,
                            hs.heartbeatAgoSec,
                            ModuleLink.HOST_LOST_AFTER_SEC,
                        )
                    },
                )
                // ★ 失败计数（2026-10-03 新增）：引擎的失败路径一律吞异常（跑在 SystemUI 里，
                //   未捕获异常会崩状态栏），所以"静默失败"是常态而不是异常。
                //   这一格让那种失败**有地方可看** —— 它此前只能靠日志，而日志几分钟就被冲掉。
                kv(stringResource(R.string.diag_kv_errs), errsText(ctx, hs.errs))
            }
        }

        // ---------------------------------------------------- burst 指标
        TextCard(title = stringResource(R.string.diag_section_metrics)) {
            if (hs == null) {
                Note(stringResource(R.string.diag_metrics_no_data))
            } else {
                kv(stringResource(R.string.diag_kv_burst), "${hs.burstCount}")
                kv(
                    stringResource(R.string.diag_kv_switch),
                    stringResource(R.string.diag_switch_value, hs.switchCount),
                )
                kv(stringResource(R.string.diag_kv_frames), "${hs.frames} / ${hs.faces}")
                kv(
                    stringResource(R.string.diag_kv_vote),
                    voteLine(ctx, hs.voteCounts, hs.voteWinner, hs.voteValid, hs.voteConfident),
                )
                kv(
                    stringResource(R.string.diag_kv_open_ms),
                    if (hs.lastOpenMs < 0) {
                        "—"
                    } else {
                        stringResource(R.string.diag_open_ms_value, hs.lastOpenMs)
                    },
                )
                kv(
                    stringResource(R.string.diag_kv_sign),
                    signLine(ctx, hs.gravitySector, hs.signSame, hs.signFlip, hs.signConfirmed),
                )
                kv(stringResource(R.string.diag_kv_display), "${hs.display}")
                kv(
                    stringResource(R.string.diag_kv_calib),
                    if (hs.calibrated) {
                        // ⚠️ 不再显示内部的 sign（±1）—— 理由同 RotationPage（2026-09-30 文案梳理）。
                        // ⚠️ 这里原来是"角度偏移 xx°" + 一个括号后缀**两段拼接**，
                        //   而中英文的括号位置与语序可能不同 ⇒ 多语言时合成**两条完整资源**，
                        //   由"基准有没有校验"二选一。
                        stringResource(
                            if (hs.signConfirmed) R.string.diag_calib_done_confirmed
                            else R.string.diag_calib_done_unconfirmed,
                            fmt(hs.calibOffsetDeg),
                        )
                    } else {
                        stringResource(R.string.diag_calib_none)
                    },
                )
            }
        }

        // ---------------------------------------------------- 运行记录（**一行入口**，2026-10-06 改）
        //
        // ★ 原来这里是两段内容（「数据记录」＋「最近事件」）**平铺**在本页 ——
        //   它们与上面两段不是一类东西：上面是**实时快照**（此刻在怎么工作），
        //   这两段是**历史**（记在哪、最近干了什么）。
        //   用户 2026-10-06 放行三级菜单（原话：「还是很乱，可以适当增加三级菜单，
        //   要让所有功能都清晰明了」）⇒ 两段搬进 [Route.DiagRecords]（[DiagRecordsPage]）。
        //
        // ⚠️ 搬的只是**位置**：两段里的每一行读数、每一个判断分支、每一个阈值全部原样，
        //   ⛔ 别在搬运时"顺手润色"（那是本页的硬纪律，见类注释末尾）。
        // ★ 固定在**整页最末**（骨架 R5 的第 ⑤ 段：延伸内容排在设置之后）。
        SectionCard(title = stringResource(R.string.diag_records_section)) {
            ArrowPreference(
                title = stringResource(R.string.diag_records_title),
                summary = stringResource(R.string.diag_records_summary),
                onClick = { onOpen(Route.DiagRecords) },
            )
        }

        // ---------------------------------------------------- 反馈与排查（2026-10-06 新增）
        //
        // ★ 为什么必须有这一段：模块的失败原因**只落在 logcat 里** ——
        //   `SplitStageLimit` 的「❶ 类未找到…⇒ 上限无法提升」、`HyperPlusModule` 的
        //   「拿不到宿主 Application，模块无法工作」，清一色是 Log.w / Log.e；
        //   而 README 教用户跑的 `adb logcat` —— **普通用户做不到**。
        //   ⇒ 没有这一段，「用户说用不了」就只能靠猜。采集内容与分层理由见 `DiagnosticsCollect`。
        //
        // ★ 固定在整页最末：它是「最后一招」的动作；前面那些读数都是在教用户自己先看一眼。
        // ⚠️ 必须用 [TextCard]（内边距 16dp）而不是 [SectionCard]（内边距 0）：
        //   这张卡装的是「文字 + 一个按钮」，没有自带内边距的偏好控件 ⇒ 用 SectionCard
        //   时按钮会**贴住卡片左右与下边缘**（2026-10-07 用户点名「没有左右边距和下边距」）。
        //   规范第 ⑩ 条：装纯文字用 TextCard；契约见 [BTN_SLOT] 那段「边距归谁给」。
        TextCard(title = stringResource(R.string.diag_export_section)) {
            Text(
                text = stringResource(R.string.diag_export_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                // ⚠️ 不写水平边距：外层已是 [TextCard]，16dp 由卡片统一给（原来这里是
                //   自画 16dp，因为当时用的是零内边距的 SectionCard）。这里只留与按钮之间的间隔。
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (exportFailed) {
                Text(
                    text = stringResource(R.string.diag_export_failed),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.paragraph,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            TextButton(
                text = stringResource(
                    if (exporting) R.string.diag_export_busy else R.string.diag_export_title,
                ),
                onClick = {
                    if (!exporting) {
                        exporting = true
                        exportFailed = false
                        scope.launch {
                            // ⚠️ 全程 runCatching：采集里要跑 logcat（偶尔会被 ROM 限制）、
                            //   写文件、起分享面板 —— 任何一步失败都只该落成卡里那一行红字。
                            val ok = runCatching {
                                val (name, text) = DiagnosticsCollect.collect(ctx)
                                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                                val file = File(dir, name)
                                file.writeText(text, Charsets.UTF_8)
                                // ★ authority 用 `${applicationId}.fileprovider` 拼出来，
                                //   与清单里那行保持同一真值源（⛔ 别在两边各写一份字面量）
                                val uri = FileProvider.getUriForFile(
                                    ctx,
                                    ctx.packageName + ".fileprovider",
                                    file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, name)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(
                                    Intent.createChooser(
                                        send,
                                        ctx.getString(R.string.diag_export_picker),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.isSuccess
                            exportFailed = !ok
                            exporting = false
                        }
                    }
                },
                modifier = BTN_SLOT,
            )
        }
    }
}

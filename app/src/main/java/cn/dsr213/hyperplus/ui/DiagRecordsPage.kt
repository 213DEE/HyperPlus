package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 三级页：**运行记录**（诊断）—— 2026-10-06 从 [DiagnosticsPage] 拆出来。
 *
 * ============================ 为什么拆（用户原话）============================
 * 「**还是很乱，可以适当增加三级菜单，要让所有功能都清晰明了**」
 * ⇒ 原「诊断」页把 **4 段读数**平铺在一页里，而那 4 段**不是一类东西**：
 *
 * | | 内容 | 性质 |
 * |---|---|---|
 * | 留在二级页 | 触发与让位 / 识别指标 | **实时快照** —— "此刻它在怎么工作" |
 * | 搬到这里 | 数据记录 / 最近事件 | **历史** —— "它把这些记在了哪、最近都干了什么" |
 *
 * ⇒ 拆完之后，诊断页一眼能看完"现在什么状态"，而"翻旧账"是另一个动作、进另一页。
 *
 * ============================ ⛔ 数值口径一字未改 ============================
 * 这是诊断页的**硬纪律**（见 [DiagnosticsPage] 类注释）：这两张卡里的每一行读数，
 * 其判断分支、阈值、"读不到时怎么报"**全部原样搬过来**，一个字都没润色。
 * 搬运只改了**位置**，没改任何口径 —— 旧结论（照着诊断页做过的那几次排查）依然成立。
 *
 * ⚠️ 它和 [DiagnosticsPage] 共用同一份 [ModuleLink.State]（同一个 `hostState` 参数）——
 *   ⛔ 这里**没有**、也不许有任何新的数据通道：两页看到的东西永远来自同一处，
 *   不可能出现"两页数据对不上"。
 */
@Composable
internal fun DiagRecordsPage(
    onBack: () -> Unit,
    hostState: ModuleLink.State?,
) {
    val ctx = LocalContext.current
    val hs = hostState

    SubPage(title = stringResource(R.string.diag_records_title), onBack = onBack) {
        // ---------------------------------------------------- 数据记录
        // ⚠️ 这张卡存在的唯一意义是"告诉用户**这里没有开关**"，免得他找。
        //   2026-10-01 精简：原来还解释了文件存在谁的目录、我们为什么没有读权限 ——
        //   那是权限模型，用户不需要 ⇒ 只留结论。
        TextCard(title = stringResource(R.string.diag_section_log)) {
            Note(stringResource(R.string.diag_log_note))
        }

        // ---------------------------------------------------- 最近事件
        TextCard(title = stringResource(R.string.diag_section_events)) {
            val lines = engineReadoutLines(ctx, hs)
            if (lines.isEmpty()) {
                Note(stringResource(R.string.diag_events_none))
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

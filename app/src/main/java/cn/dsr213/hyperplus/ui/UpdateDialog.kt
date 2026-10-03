package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.VersionChecker
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新版本弹窗。
 *
 * ★ 它由**外壳** [HyperPlusApp] 渲染，而不是由某个页面 ——
 *   因为触发它的那次检查（进应用静默查一次）是外壳发起的，
 *   而"盖住玻璃底栏"也要求它在最外层。
 *
 * 内容取舍：**只显示远端 Release 的说明正文**，不自己编写"更新了什么"——
 * 那属于猜测。正文为空时给一句中性兜底。
 *
 * ⚠️ 正文最多取 12 行：Release body 可能很长（藏着一整篇更新日志），
 *   不截断会把弹窗撑出屏幕，用户连"去下载"按钮都够不到。
 */
@Composable
internal fun UpdateDialog(
    remote: VersionChecker.Release,
    current: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = remember(remote.body) {
        remote.body.trim().lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp),
        ) {
            Text(
                text = stringResource(R.string.update_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.update_subtitle, remote.name, current),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            // ⚠️ 不要写成 `preview.ifBlank { stringResource(...) }`：`ifBlank` 虽然也是
            //   inline 函数，但在实参位置调 Composable 是**边界写法**（理由同 StatusPage 那条）。
            val notes = if (preview.isBlank()) {
                stringResource(R.string.update_no_notes)
            } else {
                preview
            }
            Text(
                text = notes,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                TextButton(text = stringResource(R.string.update_later), onClick = onDismiss)
                TextButton(text = stringResource(R.string.update_download), onClick = onOpen)
            }
        }
    }
}

package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 二级页：**关于**。
 *
 * 「设置 → 关于」。装版本号、运行方式说明、检查更新、免责声明。
 *
 * ★ 为什么"检查更新"在这里而不是在外壳：外壳只负责**自动**查一次（有新版本才弹窗，
 *   见 [HyperPlusApp]），而"我手动点一下看看有没有新版"是**用户主动发起**的动作，
 *   它的反馈（已是最新 / 查询失败）也该出现在用户点它的地方 —— 所以 `checking` 状态
 *   由外壳持有、传进来，按钮文案跟着它变。
 *
 * ⚠️ 捐赠码与收费口径**已搬走**（2026-10-01，用户原话「捐赠信息不要放到二三级菜单，
 *   直接在首页置顶，把二维码直接放出来」）⇒ 现在住在 [DonateCard]，也就是**功能页置顶**。
 *   ⛔ 别再往这一页加捐赠入口：用户要的是"到首页就能看见"，不是"在关于页里多一个按钮"。
 *   ⚠️ 那条收费口径（"Alpha 阶段将始终保持免费；不排除将来…"）是**固定文案**，
 *   跟着捐赠段走 —— 现在在 [DonateCard] 里，全仓只有那一处。
 *
 * ⚠️ 2026-10-03 多语言：全页文案已改为资源。其中**免责声明那句原来写着"不需要 root"**
 *   —— 那是 2026-10-03 之前的说法，而当天「默认方向」改成由 App 借 root 直写方向槽位之后，
 *   这句话对**那一项功能**已经不成立（对"旋转跟随"这个主功能仍成立）。
 *   现在改成「不改系统文件，核心功能也不需要 root」：既真实，又不把用户吓跑。
 *   ⛔ 这句两边都不能写死：说"需要 root"会把绝大多数用户挡在门外（主功能确实不需要）。
 */
@Composable
internal fun AboutPage(
    onBack: () -> Unit,
    appVersion: String,
    /** 是否正在查更新（由外壳持有，因为自动查询也走同一个状态） */
    checking: Boolean,
    onCheckUpdate: () -> Unit,
) {
    SubPage(title = stringResource(R.string.about_title), onBack = onBack) {
        TextCard {
            kv(
                stringResource(R.string.about_version),
                appVersion + if (checking) stringResource(R.string.about_checking) else "",
            )
            // ⚠️ 2026-10-01：原来写「常驻系统界面进程」—— 那是**实现**，用户看到"系统界面进程"
            //   只会困惑（跟"系统界面"这个 App 有什么关系？）。换成用户能对上的一句：
            //   他关心的是**要不要一直开着**。⛔ 不是改判据，只是换说法。
            // ⚠️ 2026-10-04 再改：原句「**后台常驻**，不用一直开着本应用」里那个「后台常驻」
            //   读起来像"它就是常驻在后台的"，与本模块形态（引擎住在 SystemUI、本 App 只是
            //   设置界面）不符 —— 正是用户当天点名的歧义。⇒ 改成否定句「不需要后台常驻」。
            //   ⛔ 别再写成肯定的"后台常驻"：用户会去给它加自启动 / 省电白名单，那些都没用。
            kv(
                stringResource(R.string.about_run_mode),
                stringResource(R.string.about_run_mode_value),
            )
            TextButton(
                text = stringResource(R.string.about_check_update),
                onClick = onCheckUpdate,
                // 与全工程其他卡片内按钮统一（2026-10-02）：等宽 + 上边距 8dp
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        TextCard(title = stringResource(R.string.about_disclaimer_title)) {
            // ⚠️ 2026-10-02 精简：原文 3 句 81 字。压成 2 句 63 字 —— 砍掉的是
            //   "在系统界面进程内"（进程实现）与"请在了解风险的前提下使用"的铺陈，
            //   **保留了那个真正有用的动作**：出问题就去 LSPosed 停用模块。
            Note(text = stringResource(R.string.about_disclaimer_body))
        }
    }
}

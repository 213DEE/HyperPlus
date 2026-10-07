package cn.dsr213.hyperplus.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RootShell
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**权限管理**（2026-10-05 新增，入口在「设置」主页）。
 *
 * 用户原话：「在设置页里面做一个权限管理，自动检查该获取的权限有没获取
 * （LSPosed、root、修改系统设置权限），没获取的权限提供跳转按钮，
 * 点击跳转到相关权限的设置页面」。
 *
 * ============================ 三项各自的判据（**都必须是可验证的真值**） ============================
 *
 * | 检查项 | 判据 | 为什么是它 |
 * |---|---|---|
 * | **LSPosed 模块** | [ModuleLink.State.hostAlive] | 引擎住在 SystemUI 里、每 5 秒写一次心跳；心跳新鲜 ⇔ **模块真的被加载并在跑**。⛔ 不能再退回 `AppPrefs.appChannelOk`：那是 libxposed 迁移前的旧判据，现在**恒为假**。 |
 * | **Root** | [RootShell.probe]（跑 `id` 看 `uid=0`） | 唯一能区分「有 su 但没授权」与「这台机器根本没有 su」的方法。⛔ 别用 `File("/system/bin/su").exists()` 那种猜测：KernelSU 的 su 不在那个路径上。 |
 * | **修改系统设置** | [ModuleLink.State.grantReported] ＋ [ModuleLink.State.writeGranted] | ★★ **这是「引擎侧」的授权，不是本应用的**；而且它**有三态**，不能拿一个 `Boolean` 顶 —— 理由见下面两段。 |
 *
 * ============================ ★★ 第三项为什么查的是引擎、不是本应用 ============================
 * 本应用的 `AndroidManifest.xml` **刻意没有声明 `WRITE_SETTINGS`**（那里面有整整一段解释），
 * 而且改造成单引擎架构后，**App 进程一行 Settings 写入都没有**：
 *   - 引擎要写 `accelerometer_rotation` / 方向，它跑在 SystemUI 里，`WRITE_SETTINGS`
 *     对它是 `SYSTEM_FIXED` 授予 ⇒ 正常恒为 true（这就是它要查的东西）；
 *   - 「展开后的方向」那个槽位（`user_rotation_inner`）是 WMS 私有键，
 *     **申请了 `WRITE_SETTINGS` 也写不进去**（实证：`WRITE_SETTINGS` 与
 *     `WRITE_SECURE_SETTINGS` 都无效）⇒ 那条路走的是 root，见 [RootShell]。
 *
 * ⛔ **所以这一项不能改成 `Settings.System.canWrite(appContext)`**：
 *   在 App 进程里它**恒为 false**，跳转过去的「修改系统设置」列表里也**找不到本应用**
 *   （因为清单里没声明）。那会做出一格"永远红、点进去还找不到自己"的死格子 ——
 *   正是本工程反复强调要避免的「没反应的开关」。
 *   ⚠️ 真要给 App 申请 WRITE_SETTINGS，先看 manifest 里那段"刻意不声明"的说明，
 *     并且要接受"申请了也写不进私有键"这个事实。
 *
 * ============================ ★★ 第三项为什么有三态（2026-10-05 用户拍板方案 a） ============================
 * **保留这一行，但改掉它的语义。** 触发这件事的是一个外部用户的报障截图：他的机器上
 * 「修改系统设置」显示「缺失」，于是他跑去系统设置里翻，**找不到 HyperPlus**（当然找不到，
 * 清单没声明），就以为装错了。
 *
 * 真相在数据层：`writeGranted = (kv["grant"] == "1")` 把两件不同的事压成了一个 `false` ——
 *   - **状态串里根本没有 `grant` 这一项**（引擎没在跑 / 老格式）⇒ 我们**不知道**；
 *   - **`grant=0`** ⇒ 真的没授权。
 * 而实测（`dumpsys package com.android.systemui` ⇒ `WRITE_SETTINGS: granted=true`，
 * `android.uid.systemui` 自带）说明：这个权限在引擎那边**正常恒为真**，
 * ⇒ 界面上红色那一格**不可能是"要去授权"**，只可能是"引擎没在跑"。
 * 于是原文案（「它跟着上面两项走，先把它们修好」）方向是对的、但没把"缺失"这个假象拆掉。
 *
 * 三态怎么落（与 [DiagnosticsPage] 的 `cfgold` 同一套）：
 * | 判据 | 右边状态字 | 第二行说明 |
 * |---|---|---|
 * | `!grantReported` | **未读到** | 引擎没在跑 ⇒ 先把①修好（这一格的读数才可能出现） |
 * | `writeGranted` | 已获取 | 系统界面自带，正常无需你操作 |
 * | `!writeGranted`（有键且为 0） | 未获取 | **异常**：引擎在跑却没拿到 ⇒ 重启「系统界面」后再看 |
 *
 * ⛔ **别把三态并回 `Boolean`**：一并于「未读到」就退化成"误报缺失"，这正是本次要修的东西。
 *
 * ============================ 交互约定 ============================
 * - **整行就是按钮**（[ArrowPreference] 的官方语义）：缺哪一项，那一行可以直接点，
 *   点下去就去对应的设置页 / 触发授权。⛔ 不另摆一个小按钮 —— 那是"发明控件"。
 * - **已获取的行不可点**（`enabled = false`，箭头随之变灰）。点得动却什么都不发生的行，
 *   和"没反应的开关"是同一种坏体验。
 * - Root 那一项天然有三态（已授权 / 没授权 / 这台机器没有 root），**每态的动作不一样**：
 *   只有"没授权"才给下一步（点一下会重新探测 ⇒ 那正是触发授权框的动作）。
 *   "根本没有 root"时点了也还是弹不出框 ⇒ **不给点**，只如实说。
 * - 进页面会**自动探一次 root**。它有一个用户能感知的副作用：root 从没授权过时
 *   **会弹出授权框** —— 而那恰恰是用户要的（"没获取就给我一个入口去获取"）。
 *   ⛔ 别改成"进页面不探测"：那就成了"要用户先点一下才告诉我缺什么"。
 */
@Composable
internal fun PermissionsPage(
    hostState: ModuleLink.State?,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current

    /** `null` = 正在探测（root 那条要起一个 `su` 进程，几十到几百毫秒） */
    var rootStatus by remember { mutableStateOf<RootShell.RootStatus?>(null) }

    /** 点一次就加一，驱动下面那次 [LaunchedEffect] 重跑（= "重新检测"） */
    var probeTick by remember { mutableStateOf(0) }

    LaunchedEffect(probeTick) {
        rootStatus = null
        rootStatus = RootShell.probe()
    }

    // ★ 这两项**不自己轮询**：它们来自父层定时刷新的宿主状态摘要
    //   （见 `HyperPlusApp` 里那个读 `ModuleLink.currentState` 的循环）⇒ 天然是活的。
    val moduleOk = hostState?.hostAlive == true
    // ★★ **三态**：`null` = 引擎这次没报这一项（**未读到**），与"报了 0"（真的异常）不是一回事。
    //   ⛔ 别写成 `hostState?.writeGranted == true` —— 那正是本次要修的误报（见类注释）。
    val writeState: Boolean? = hostState
        ?.takeIf { it.grantReported }
        ?.writeGranted

    SubPage(title = stringResource(R.string.perm_title), onBack = onBack) {
        // ---------------------------------------------------- 说明
        TextCard(title = stringResource(R.string.perm_section_note)) {
            Text(
                text = stringResource(R.string.perm_explain),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
            )
        }

        // ---------------------------------------------------- 三项检查
        SectionCard(title = stringResource(R.string.perm_section_items)) {
            // ① LSPosed 模块
            PermRow(
                title = stringResource(R.string.perm_lsposed_title),
                ok = moduleOk,
                summary = stringResource(
                    if (moduleOk) R.string.perm_lsposed_ok else R.string.perm_lsposed_missing,
                ),
                onClick = if (moduleOk) null else ({ openLsposedOrAppList(ctx) }),
            )
            HorizontalDivider()

            // ② Root
            val rs = rootStatus
            PermRow(
                title = stringResource(R.string.perm_root_title),
                ok = rs == RootShell.RootStatus.GRANTED,
                summary = when (rs) {
                    // ⚠️ `null` 是"还没测出来"，不是"没授权" —— 两者对用户的含义完全不同
                    null -> stringResource(R.string.perm_probing)
                    RootShell.RootStatus.GRANTED -> stringResource(R.string.perm_root_ok)
                    RootShell.RootStatus.DENIED -> stringResource(R.string.perm_root_denied)
                    RootShell.RootStatus.UNAVAILABLE -> stringResource(R.string.perm_root_unavailable)
                },
                // ★ 只有"有 root、但没授权"这一态才有下一步：点一下 = 重新探测
                //   = 再弹一次授权框（见 `RootShell.probe` 的纪律 ①）。
                //   - 已授权 ⇒ 没什么可点的；
                //   - 根本没有 root ⇒ 点了还是弹不出框，别给一个点不动的行。
                onClick = if (rs == RootShell.RootStatus.DENIED) ({ probeTick++ }) else null,
            )
            HorizontalDivider()

            // ③ 修改系统设置（**引擎侧**授权 —— 判据与三态理由见类注释那两大段）
            PermRow(
                title = stringResource(R.string.perm_write_title),
                ok = writeState,
                summary = stringResource(
                    when (writeState) {
                        // 引擎没在跑 ⇒ 先修①；这一格的读数只有在①修好之后才会出现
                        null -> R.string.perm_write_unknown
                        true -> R.string.perm_write_ok
                        // 引擎在跑却拿不到 ⇒ 异常（SystemUI 本应恒有）
                        else -> R.string.perm_write_missing
                    },
                ),
                // ⛔ 这一格**不给跳转**：没有对口的设置页（本应用没声明那个权限，
                //   引擎那边是 SYSTEM_FIXED 授予）。缺了它只能靠修上面两项来解，
                //   所以文案把用户引回上面，而不是给一个点进去空空如也的跳转。
                onClick = null,
            )
        }

        // ---------------------------------------------------- 重新检测
        // ★ 给这个按钮的理由：LSPosed 作用域 / root 授权都要用户**离开本应用**去改，
        //   改完回来最自然的动作就是"再测一次"。⛔ 别指望用户会退出重进这一页。
        TextButton(
            text = stringResource(R.string.perm_recheck),
            onClick = { probeTick++ },
            modifier = BTN_SLOT,
        )
    }
}

/**
 * 一项检查在界面上的样子：**整行可点 + 右侧状态字**。
 *
 * ★ 用 [ArrowPreference]（Miuix 官方"带箭头的一行"）而不是自己拼 Row：
 *   它的语义本来就是"点这一行会去别的地方"，箭头就是这个意思的视觉表达。
 *   ⛔ 别自己画箭头 / 别外挂一个小按钮。
 *
 * @param ok 是否已获取。**三态**：`true` 已获取 / `false` 未获取 / `null` 未读到。
 *   ⛔ 别把 `null` 并进 `false` —— 「我们不知道」和「确定没拿到」对用户是两件事，
 *   一个要用户去动手、一个要用户先去看为什么读不到（2026-10-05 修的就是这个）。
 *   它同时决定三件事：状态字怎么显示、颜色、这一行点不点得动。
 * @param summary 第二行说明 —— 必须是**"我该怎么办"**，不是"为什么"（见 `docs/UI_文案*`）。
 * @param onClick `null` = 这一项现在没有下一步动作 ⇒ 整行置灰不可点。
 *   ⚠️ **不许**传一个"点了什么都不发生"的 lambda 来充数。
 */
@Composable
private fun PermRow(
    title: String,
    ok: Boolean?,
    summary: String,
    onClick: (() -> Unit)?,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        endActions = {
            Text(
                text = stringResource(
                    when (ok) {
                        true -> R.string.perm_state_ok
                        // ⚠️ 「未读到」用的是**中性色**、不是红色：我们还没读到而已，
                        //   不是"你少做了一个动作"。红字会把人往"去授权"那条错路上引。
                        null -> R.string.perm_state_unknown
                        else -> R.string.perm_state_missing
                    },
                ),
                // ⚠️ 两个颜色都显式给（本项目纪律：别依赖控件的默认色 —— 主题一换就分叉）
                color = if (ok == false) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                style = MiuixTheme.textStyles.body2,
            )
        },
        onClick = onClick,
        enabled = ok != true && onClick != null,
    )
}

/**
 * 跳到 LSPosed 管理器；**找不到管理器就退到系统「已安装应用」列表**。
 *
 * ★ 为什么用 `ACTION_APPLICATION_DETAILS_SETTINGS`（系统标准的"应用详情页"）
 *   而不是 LSPosed 某个私有 Activity：那是别人的内部实现，跨版本不稳，
 *   而且本工程不该依赖第三方应用的组件名。系统标准页对所有管理器都成立。
 *
 * ⚠️ **必须有兜底**：LSPosed 的调试构建用的是另一个 `applicationId`，
 *   而这个包名在不同版本间也改过 ⇒ 两个都试不到时，至少把用户送到
 *   系统应用列表（他在那里自己能找到"LSPosed"）。
 *   ⛔ 别在这里弹 "没安装 LSPosed" 的结论 —— 我们并不知道，
 *   只是**没找到我们能识别的那个包名**，这两件事不一样。
 */
private fun openLsposedOrAppList(ctx: Context) {
    val candidates = listOf(
        "org.lsposed.manager",
        // 调试构建的 applicationId（同一份代码，只是后缀不同）
        "org.lsposed.manager.debug",
    )
    for (pkg in candidates) {
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg"),
        )
        // ⚠️ 本应用有 `QUERY_ALL_PACKAGES`（见 `AppWhitelistPage.loadInstalledApps` 的注释），
        //   所以这里 `resolveActivity` 是可信的。
        if (ctx.packageManager.resolveActivity(i, 0) != null) {
            runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
    }
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

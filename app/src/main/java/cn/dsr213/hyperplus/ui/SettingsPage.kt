package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
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
 * ★ 2026-10-03 多语言：本页全部文案已改为 `stringResource`（硬编码中文一条不留）。
 *   ⚠️ 新加文案时**必须**同时补 `values` / `values-zh-rCN` / `values-zh-rTW` 三套，
 *     漏了就会在对应语言下掉回默认（英文）—— `_probe/check_strings_parity.py` 专查这个。
 */
@Composable
internal fun SettingsPage(
    onOpen: (Route) -> Unit,
) {
    // ★ 配置通道的**本进程**那一侧（App 有没有被 LSPosed 注入）。
    //   引擎侧那一半（读不读得到文件）由状态摘要回传，属于「当前状态 / 诊断」的内容，
    //   在这里不重复报 —— 这一页只回答"我该不该现在就去改设置"。
    val appChannelOk by AppPrefs.appChannelOk.collectAsState()
    val appChannelDiagRes by AppPrefs.appChannelDiagRes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                largeTitle = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.app_name),
            )
        },
    ) { padding ->
        HomeColumn(padding) {
            // ------------------------------------------------ 配置通道没打通时，先说这件事
            if (!appChannelOk) {
                TextCard(title = stringResource(R.string.settings_channel_broken)) {
                    // ⚠️ 不要写成 `appChannelDiag.ifEmpty { stringResource(...) }`：
                    //   虽然 `ifEmpty` 是 inline 函数、Compose 允许在 inline lambda 里调
                    //   composable，但那是**边界写法** —— 一旦哪天它被换成非 inline 的
                    //   `orEmptyOnce()` 之类就会静默编译失败。显式 `if` 没有这个隐患。
                    // ⚠️ `0` = `AppPrefs.init` 还没跑过（见 [AppPrefs.appChannelDiagRes]）。
                    val channelMsg = if (appChannelDiagRes != 0) {
                        stringResource(appChannelDiagRes)
                    } else {
                        stringResource(R.string.settings_channel_default)
                    }
                    Text(
                        text = channelMsg,
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.paragraph,
                    )
                    // ⚠️ 2026-10-01 精简：上面那条 [appChannelDiag] 已经说了"怎么办"，
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

            // ------------------------------------------------ 监控与排查
            SectionCard(title = stringResource(R.string.settings_section_monitor)) {
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

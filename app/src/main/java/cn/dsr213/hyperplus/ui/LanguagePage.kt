package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.dsr213.hyperplus.AppLocale
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.UiLang
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/**
 * 二级页：**语言**（2026-10-03 新增）。
 *
 * ★ 用户原话：「16做简体中文和英文，在设置里面添加语言设置，
 *   可选「跟随系统、简体中文、繁体中文、英文」，**如果系统文字不是以上三种，默认英文**」。
 *
 * ============================ 四条要说清的设计 ============================
 *
 * ① **四档，但只有三套资源**：`跟随系统` 不是一个语言，而是一条**规则** ——
 *    它把系统语言归一成那三档之一（见 `AppLocale.fromSystem`）。
 *    系统语言不在这三种里（日语 / 德语 / 阿拉伯语……）时，归一结果就是英文。
 *
 * ② **改了必须重建 Activity**，不能只重画界面：
 *    `stringResource` 取的是 **Activity 的 base context** 上的资源，
 *    而这个 base context 只在 `attachBaseContext` 那一刻装语言。
 *    ⇒ 换语言的唯一正确做法就是让 Activity 走一遍重建（`onRecreate`）。
 *    ⚠️ 所以本页**不自己调 `recreate()`**：由宿主（`MainActivity`）传进来。
 *      在 Compose 里反向 `LocalContext` 找 Activity 要穿过若干层 `ContextWrapper`，
 *      那条路一旦被哪个壳包一层就静默失效 —— 回调是显式的，不会悄悄坏掉。
 *
 * ③ **选中的一档要看得见**（`selected = v == cur`）：这是本页唯一的功能性表达。
 *    数据源是 `AppLocale.lang` 这个 StateFlow（`MainActivity.onCreate` 里已 init）。
 *
 * ④ **语言名的写法**（见 `AppLocale.labelRes` 的注释，这里不重抄）：
 *    `跟随系统` 跟着界面语言翻译，三种语言名不翻译。
 *
 * ⑤ ⚠️ **已知的副作用：重建之后会回到首页**（不是 bug，是重建的必然结果）。
 *    返回栈是 `HyperPlusApp` 里 `remember { navBackStackOf(Route.Function) }` ——
 *    重建会把它从头来过，于是用户改完语言看到的是**首页**而不是本页。
 *    我（2026-10-03）**刻意没有去修**：
 *      · 要让返回栈活过重建，得把整条栈塞进 `rememberSaveable`（`Route` 于是要可序列化），
 *        而那是 09-29 刚重构完的导航层，铁律写了"别再拆回去"；
 *      · 而"落回首页"本身其实**不坏**：首页同时展示底栏、卡片、按钮三种文字，
 *        是看新语言效果最直观的一屏。
 *    ⇒ 改动前请先想清这一点，⛔ 不要顺手把栈改成可序列化。
 */
@Composable
internal fun LanguagePage(
    onBack: () -> Unit,
    /** 语言已改变 ⇒ 请宿主重建界面（见类注释 ②） */
    onRecreate: () -> Unit,
) {
    val ctx = LocalContext.current
    val current by AppLocale.lang.collectAsState()

    SubPage(title = stringResource(R.string.lang_page_title), onBack = onBack) {
        TextCard {
            Note(stringResource(R.string.lang_page_desc))
        }

        SectionCard {
            // ★ 顺序刻意是「跟随系统 → 简体 → 繁体 → 英文」：
            //   前两个/三个是"中文用户"的常用落点，英文垫底 —— 与 `UiLang` 的枚举顺序一致。
            //   ⚠️ 顺序改了就改了 `UiLang` 的 `entries` 顺序，⛔ 但**别改 `storageKey`**。
            UiLang.entries.forEach { v ->
                RadioButtonPreference(
                    title = stringResource(AppLocale.labelRes(v)),
                    selected = v == current,
                    onClick = {
                        // 点当前项不做事（省掉一次莫名其妙的闪屏）
                        if (v != current) {
                            AppLocale.set(ctx, v)
                            onRecreate()
                        }
                    },
                )
            }
        }

        TextCard {
            Note(stringResource(R.string.lang_page_hint))
        }
    }
}

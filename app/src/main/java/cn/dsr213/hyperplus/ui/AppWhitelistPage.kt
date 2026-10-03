package cn.dsr213.hyperplus.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppWhitelist
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.ScreenForm
import cn.dsr213.hyperplus.Uncontrollable
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用名单（豁免名单）**内容块** —— 2026-10-01 起内嵌在「旋转增强」页里。
 *
 * ============================ 为什么它不再是独立一页（2026-10-01）============================
 * 用户原话：「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」。
 * 它原来是 `Route.Apps` 这另一个二级页，入口在「旋转增强 → 开关 → 管理应用白名单」。
 * ⇒ 现在 `Route.Apps` 已删除，本函数也不再叫 `AppWhitelistPage` ——
 *   **它是内容，不是页面**：滚动、底栏留白、返回箭头全部由调用方
 *   （[RotationPage] 里那个 [SubPage]）统一提供。
 *   ⚠️ 所以这里**不许**再套 `Scaffold` / `SmallTopAppBar` / `verticalScroll` /
 *     `Spacer(BOTTOM_BAR_RESERVE)`：重复的滚动容器会让嵌套滚动打架，重复的留白会多出一大截空白。
 *   ⚠️ 它发射的是一串**兄弟节点**（若干 [SmallTitle] + [Card]），必须被放进一个 Column 里 ——
 *     本项目里就是 [RotationPage] 的 `SubPage` 内容列。别把它塞进 Row 或 Box。
 *
 * ⚠️ 2026-09-30 全量文案梳理：界面上"豁免 / 白名单 / 引擎停手"这类说法统一成
 *   「名单 / 本应用不干预」—— 行为一个字没改，只换说法。
 *   内部仍叫 whitelist（**存储键与类名不动**），⛔ 别为了文案去改存储键，那会让老用户的名单丢光。
 *
 * 2026-09-28 用户点名要的界面（原话：「改成应用白名单…自动读取应用列表，由用户手动开关，
 * 应用列表根据 A-Z 排序，要有搜索功能」）。
 *
 * ============================ 2026-09-29：从"三格"回到"一格" ============================
 * 09-29 上午这一页曾被扩成**三格**（内屏豁免 / 外屏豁免 / 全局豁免），因为那时想让
 * 两块屏各管各的。同一天下午用户拍板「**直接删除外屏的旋转增强，只保留内屏的旋转增强
 * 和应用豁免**」⇒ 外屏一点都不介入了，"外屏豁免"就没有可豁免的对象，三格立刻退化成
 * **一格**（用的还是原来"全局层"那份存储，用户以前勾过的包一个都不用重勾）。
 *
 * ⇒ 一行现在是：应用名 +（包名 · 默认清单）+ **一个开关** + 可能的红字。
 *   ⛔ 别再往这一页加回"按屏分列"：外屏不增强，那个维度没有任何下游。
 *
 * ============================ 三个容易做错的地方 ============================
 *
 * ① **"列表里的应用" ≠ "生效白名单"**
 *    生效集合里还有一堆**没装**的默认包（见 [AppWhitelist.DEFAULT_PACKAGES]）。所以顶部
 *    统计给的是「已装 N 个 · 已豁免 M」，而不是直接把集合大小摆出来 ——
 *    后者会比列表里数出来的勾**多**，用户会以为哪里漏了。
 *
 * ② **开关的状态必须从 `AppPrefs.whitelist` 读，不能自己算**
 *    默认清单 + 用户增删的合成只发生在 `AppPrefs.resolve` 那一处。界面自己推一遍，
 *    就会在"用户手动关过默认项"这种情形上与引擎分歧。
 *
 * ④ **「已开启」分区（2026-09-29 用户要求）**
 *    用户原话：「应用白名单里面，开启的应用要指定」。下面那份 A-Z 列表是**全部已装应用**
 *    （几百行），而"我到底豁免了哪几个"才是用户真正要管的事 —— 在一堆关着的开关里
 *    找那几个开着的太难。⇒ 开着的集中列在**最上面**，一眼看完、就地能关，
 *    并逐行标出它是「默认清单」还是「手动指定」。
 *    ⚠️ 它只列**已安装**的（没装的默认包列出来只有包名、开关也没意义），
 *      所以计数与 A-Z 列表同口径：`已装 + 已豁免`。
 *    ⚠️ 同一个应用会在页面上出现两次（「已开启」里一次、A-Z 里一次）——
 *      两处都绑在同一个 `AppPrefs.whitelist` 上，不会不同步；改一处两处一起变。
 *
 * ⑤ **实测不可控那一格不给点**（红字说明）
 *    它不是用户的偏好，而是**引擎观测到的事实**：「我们真的写过方向、屏幕两次读回都没动」，
 *    所以引擎对该应用静默停手（不弹按钮）。它**不是永恒事实**（系统/应用更新后可能又能转），
 *    因此列表上方给了"清除实测记录"的出口。
 *    ⚠️ 与它相对的是**用户开关**（第 ① 格）：那个随时能点、点了立刻生效。
 *    ⛔ 09-29 上午那版还有第三种"按应用声明强制豁免、不许关" —— 判错率高且无法自救，
 *      已被删除（复盘见 [AppWhitelist] 类注释"记过案"）。**不要再引入"点不动的推断"**。
 */
@Composable
internal fun AppWhitelistSection() {
    val ctx = LocalContext.current

    /**
     * 总开关（「名单里的应用不干预」）。
     *
     * ★ 只用来**说明**，不用来隐藏内容：旧版是"总开关关掉时连入口都不给"，
     *   因为那时名单是一张独立页、关掉后编辑它没有任何下游。
     *   现在名单是本页的一部分内容 —— 让它随开关忽隐忽现会让页面"长出一块又缩回去"，
     *   而且用户想**先配好名单、再打开总开关**时就找不到它了。
     *   ⇒ 内容照常显示，只在说明卡里用红字点出"此刻不生效"。
     */
    val gateEnabled by AppPrefs.gateEnabled.collectAsState()

    // ★ 唯一真值来源（理由见类注释第 ② 条）
    val wl by AppPrefs.whitelist.collectAsState()

    var query by remember { mutableStateOf("") }
    /** `null` = 还没读完（读列表要几十~几百毫秒，别让界面先渲染一个空列表再跳一次） */
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    /**
     * 「实测不可控」名单的整串值（引擎写在 `Settings.System` 里，见 [Uncontrollable]）。
     *
     * ★ App 侧**读**它是零权限的（与状态串 / 标定值走同一条"引擎写、App 读"的通道）；
     *   但**写不了** —— 那正是下面那个"清除"按钮必须绕一圈的原因。
     * ⚠️ 其中**外屏**那些条目是"删掉外屏增强"之前留下的，已无下游 ⇒ 只在**内屏**口径下
     *   显示与计数（见 [Uncontrollable.countFor]）。
     */
    var ucRaw by remember { mutableStateOf("") }

    /** 点过几次"清除"。用它驱动一次**延迟**重读（真正的删除是引擎做的，要等一会儿） */
    var clearTick by remember { mutableStateOf(0) }

    // ⚠️ 即使一进页面就 return（比如立刻又返回主页），这次读取也会跑完 ——
    //   但它只读 PackageManager 与 Settings、不改任何状态，不会留下副作用。
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(ctx) }
        ucRaw = readUncontrollable(ctx)
    }

    // ★ 点"清除"之后**不能立刻重读**：真正删键的是**引擎**（App 没有 WRITE_SETTINGS），
    //   走的是"App 写请求 → 配置通道通知 → 引擎删"这条链，量级几百毫秒到一秒。
    //   立刻重读只会读到旧值，让用户以为点了没反应。
    LaunchedEffect(clearTick) {
        if (clearTick == 0) return@LaunchedEffect
        delay(1_200)
        ucRaw = readUncontrollable(ctx)
    }

    // ⚠️ 只留一个**无装饰**的 Column：唯一作用是把下面那串兄弟节点编成一组
    //   （`SmallTitle` 与 `Card` 是多个并列节点，必须有共同父级）。水平留白、竖向滚动、
    //   底栏留白都交给调用方的 [SubPage] —— 理由见类注释。
    Column(modifier = Modifier.fillMaxWidth()) {
            SmallTitle(text = stringResource(R.string.wl_section_note))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                // ★ 总开关关掉时这份名单**此刻不参与判断**，必须说出来。
                //   旧版是靠"把入口藏起来"来避免这个困惑（关掉了就点不进来）；
                //   合并之后内容一直在，藏不掉 ⇒ 改用红字说明，比藏起来更让人明白。
                if (!gateEnabled) {
                    Text(
                        // ⚠️ 2026-10-02 精简：这句是**状态提示**（名单此刻不生效），
                        //   不是说明，所以只留"关着 + 改了没用"两件事。
                        text = stringResource(R.string.wl_gate_off),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.paragraph,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    // ⚠️ 这里**不能写 markdown**（星号不会被渲染，会原样显示成 `**全局**`）——
                    //   强调一律用「」。实测踩过：旧文案里的星号在真机上就是带着星号显示的。
                    // ⚠️ 2026-10-01 精简：原来**三段**，把"出厂默认清单是怎么来的、
                    //   实测记录是怎么记上的、更新后为什么要重测"讲了一遍 —— 那是我们的实现。
                    // ⚠️ 2026-10-02 再精简成**用户给的这一句**（原话：「名单说明太长了，
                    //   精简，用一句话讲明白，例如：开启后该应用的方向由系统接管，
                    //   本模块不再干涉」）—— 只留"这个开关到底干了什么"。
                    //   ⛔ 别再解释"默认有谁""红字是什么"：前者下面每行都带「默认清单」标签，
                    //     后者那条红字自己就写在行内（`Uncontrollable.note`）。
                    //   ★ 两处按本工程既有口径改了字：`本模块`→`本应用`（界面自称一律是
                    //     "本应用"）、`接管`→`交给系统`（"接管"是内部黑话，
                    //     见 `UiCommon.engineReadoutLines` 那条 2026-09-30 的梳理）。
                    text = stringResource(R.string.wl_explain),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.paragraph,
                )
            }

            // ---------------------------------------------------- 已开启
            //
            // ★ 为什么单独列一块（2026-09-29 用户原话：「应用白名单里面，开启的应用要指定」）：
            //   下面那份 A-Z 列表是**全部已装应用**，几百行；而"我到底豁免了哪几个"
            //   是用户真正要管的事 —— 在一堆关着的开关里找那几个开着的，太难了。
            //   ⇒ 开着的**集中放在最上面**，一眼看完、就地能关。
            //
            // ⚠️ 这里只列**已安装**的那些。生效集合里还有一堆没装的默认包（见
            //   [AppWhitelist.DEFAULT_PACKAGES]），把它们列出来只有包名可显示、
            //   点开关也没有意义（它本来就没装）。所以这一格的计数与 A-Z 列表同口径：
            //   「已装 + 已豁免」。
            val all = apps
            if (all != null) {
                val on = all.filter { it.pkg in wl }
                SmallTitle(text = stringResource(R.string.wl_on_title, on.size))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp),
                ) {
                    if (on.isEmpty()) {
                        // ⚠️ 2026-10-01 精简：原来重复了一遍"名单里的应用本应用不干预"
                        //   （上面那张说明卡刚说过）⇒ 删掉重复，只留"两种标签是什么意思"。
                        Text(
                            text = stringResource(R.string.wl_empty),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.paragraph,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        Text(
                            // ⚠️ 2026-10-02 精简：改成"标签 = 含义"的对照写法，比两个
                            //   "…的是…的" 分句短，也更好扫。
                            text = stringResource(R.string.wl_tag_legend),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        on.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            AppRow(
                                e = e,
                                on = true,
                                // ★ 只认内屏口径：引擎只在内屏干活，外屏那些历史记录已无下游。
                                uncontrollable =
                                    Uncontrollable.isMarked(ucRaw, e.pkg, ScreenForm.INNER),
                                // ⚠️ 本应用自己那句说明只在上面的 A-Z 列表里出现一次 ——
                                //   同一页说两遍会把这一块撑开，扫的时候反而看不到重点。
                                showOwnNote = false,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------- 搜索
            SmallTitle(text = stringResource(R.string.wl_section_search))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.wl_search_label),
                )
                // ⚠️ `all` 用的是上面「已开启」那一块里取的那份（同一作用域），别再取一次。
                if (all != null) {
                    // ★ 统计口径刻意分开数（见类注释第 ① 条）：
                    //   "实测不可控"**不计入**上面的"已豁免"——它不是用户的偏好，
                    //   而是引擎观测到的事实，混进去会让"我到底勾了几个"对不上账。
                    val nOn = all.count { it.pkg in wl }
                    val nUc = Uncontrollable.countFor(ucRaw, ScreenForm.INNER)
                    // ⚠️ 2026-10-03 多语言：三段拼接改成"先取三段资源再相加"——
                    //   分隔符（" · "）留在各段资源里，不同语言可以各自调整。
                    val countText = stringResource(R.string.wl_count, all.size, nOn) +
                        (if (nUc > 0) stringResource(R.string.wl_count_uc, nUc) else "") +
                        (
                            if (query.isBlank()) {
                                ""
                            } else {
                                stringResource(R.string.wl_count_match, filterApps(all, query).size)
                            }
                            )
                    Text(
                        text = countText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.paragraph,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // ★★ 2026-10-02 两个按钮的排版（用户反馈「搜索下面的恢复默认、清除实测记录
                    //   按钮…都没有间距，不够美观」）。原样是两个 `TextButton` **各自套一层
                    //   `Row`**：宽度裹着文字、左右参差，而且上下紧贴（第二个连上边距都没有）。
                    //   ⇒ ① 去掉那两层只起"居中"作用的 `Row`（`TextButton` 自己就是一行）；
                    //     ② 统一 `fillMaxWidth()`（同类操作等宽）+ `top = 8.dp`（彼此分开）。
                    //   ⚠️ `fillMaxWidth` **不会**让按钮变高 —— 高度由 `TextButton` 的
                    //     `minHeight` 决定，撑宽不撑高（已由 `javap` 核实签名）。
                    TextButton(
                        text = stringResource(R.string.wl_reset),
                        onClick = { AppPrefs.resetWhitelistRemovals() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    // ★ 清除出口（必须给它，理由是"观测结论不是永恒事实"）：
                    //   系统更新 / 应用更新 / 用户改了那个应用自己的方向设置，都可能让旧记录失效。
                    //   没有出口的话，一次误记就是**永久**的"这个应用永远不弹按钮"。
                    //   ⚠️ 真正的删除由**引擎**执行（App 没有 WRITE_SETTINGS）—— 见 [Uncontrollable]。
                    if (nUc > 0) {
                        TextButton(
                            text = stringResource(R.string.wl_clear, nUc),
                            onClick = {
                                AppPrefs.requestUncontrollableClear()
                                // 乐观更新：先把红字与这个按钮消掉，否则用户点完看不到任何变化，
                                // 会以为没生效。若引擎最终没删掉，上面那次延迟重读会把它们带回来。
                                ucRaw = ""
                                clearTick++
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.wl_clear_note),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                        )
                    }
                }
            }

            // ---------------------------------------------------- 列表
            // ⚠️ `all` 在上面「已开启」那一块里已经取过了（同一个 Column 作用域）——
            //   这里**别再取一次**，重名会直接编译不过。
            SmallTitle(text = stringResource(R.string.wl_section_installed))
            if (all == null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wl_loading),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.paragraph,
                    )
                }
            } else {
                val shown = filterApps(all, query)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp),
                ) {
                    if (shown.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wl_no_match, query),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.paragraph,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        shown.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            AppRow(
                                e = e,
                                on = e.pkg in wl,
                                // ★ 只认内屏口径：引擎只在内屏干活，外屏那些历史记录已无下游。
                                uncontrollable =
                                    Uncontrollable.isMarked(ucRaw, e.pkg, ScreenForm.INNER),
                            )
                        }
                    }
                }
            }

            // ⚠️ 这一块**不再自己** `Spacer(BOTTOM_BAR_RESERVE)`（2026-10-01 合并后）：
            //   底栏留白由调用方的 `SubPage` 统一给。两处都给的话，页面末尾会多出一大截空白。
        }
}

/**
 * 一行：应用名 +（包名 · 默认清单 / 手动指定 · 实测不可控）+ 一个开关 + 可能的红字。
 *
 * ★ 开关的可点性只有一个例外：**实测不可控**（引擎观测到"写过方向但屏幕没动"）。
 *   那一格置灰 + 红字，并给"清除记录"的出口。
 *   ✅ 其余任何应用都**能点、能关** —— 包括本应用自己、包括默认清单、包括桌面。
 *   ⛔ 不再有"按声明推断出来、点不动的豁免"（那是被删掉的记过案）。
 *
 * @param on 当前是否已豁免。它决定副标题里的第二段标注：
 *   「默认清单」（出厂就带）还是「手动指定」（用户自己开的）。
 *   ⚠️ 两者都**只有开着的那些**才标 —— A-Z 列表里几百行关着的应用都挂一个
 *   「手动指定」的话，这一页就没法扫了。
 * @param showOwnNote 是否渲染「本应用自己」那条说明。它只在 A-Z 列表里出现
 *   （同一页的「已开启」分区里不重复说，见那边注释）。
 */
@Composable
private fun AppRow(
    e: AppEntry,
    on: Boolean,
    uncontrollable: Boolean,
    showOwnNote: Boolean = true,
) {
    // ⚠️ 2026-10-03 多语言：`buildString` 里**不能**直接调 `stringResource`（它只允许
    //   出现在 Composable 的语句位置）。所以先把三段标签取出来，再在 `buildString` 里用。
    val tagDefault = stringResource(R.string.wl_tag_default)
    val tagManual = stringResource(R.string.wl_tag_manual)
    val tagUc = stringResource(R.string.wl_tag_uc)
    val summary = buildString {
        append(e.pkg)
        if (AppWhitelist.isDefault(e.pkg)) {
            append(tagDefault)
        } else if (on) {
            append(tagManual)
        }
        if (uncontrollable) append(tagUc)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = e.label, style = MiuixTheme.textStyles.body1)
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
            Spacer(Modifier.width(12.dp))
            // ★ 置灰只表示"这一格现在改不动"，而**不是**"我们不让改" ——
            //   红字把原因说清楚（"实测转不动"），并且上面有"清除记录"的出口。
            Switch(
                checked = on,
                onCheckedChange = { v -> AppPrefs.setAppWhitelisted(e.pkg, v) },
                enabled = !uncontrollable,
            )
        }
        if (uncontrollable) {
            // ★ 红字（`colorScheme.error`）—— 用户点不动那一格时的**唯一**解释。
            // ⚠️ 2026-10-03 多语言：文案从 `Uncontrollable.note(form)`（返回中文 String）
            //   搬到了资源里。那个函数**已删除** —— 它是纯界面文案，留着只会多一份要同步的真值。
            Text(
                text = stringResource(
                    R.string.uc_note,
                    stringResource(ScreenForm.INNER.labelRes),
                ),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // ★ 本应用自己现在**也是普通一行**（2026-09-29 用户拍板）：
        //   默认不豁免 ⇒ 它的界面也归模块管（半自动模式下能在自己的设置页里看到旋转按钮）。
        //   代价是"模块接管会冻结系统自动旋转"，所以给它一句说明 ——
        //   想让它跟随系统就打开这一格（那正是 09-28 报「设置页没跟随系统旋转」的修法）。
        if (e.pkg == AppWhitelist.OWN_PACKAGE && showOwnNote) {
            Text(
                // ⚠️ 2026-10-02 精简：原句先说"本应用默认不在名单里"再解释"所以也归它管"，
                //   两句说的是一件事 ⇒ 合成一句，只留下"怎么办"（想让设置页跟随系统就打开它）。
                text = stringResource(R.string.wl_own_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 应用名或包名里含关键词就算命中（不区分大小写）—— 包名也搜，是为了让人能按包名核对 */
private fun filterApps(all: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim()
    if (q.isEmpty()) return all
    return all.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
}

/** 一个可勾选的应用 */
internal data class AppEntry(val pkg: String, val label: String)

/**
 * 读**已安装且能从桌面启动**的应用，按 A-Z 排好。
 *
 * ★ 判据用 `ACTION_MAIN` + `CATEGORY_LAUNCHER`：它给出的正是"用户在桌面上看得到的那些
 *   应用"，与"白名单"的语境完全对齐。用 `getInstalledApplications` 会把几百个
 *   没有界面的系统组件（provider / service / 各种 miui 内部包）一起捞进来，
 *   那份列表对用户毫无意义，还会把真正要勾的那个应用淹掉。
 *
 * ⚠️ 需要 `QUERY_ALL_PACKAGES`（见 AndroidManifest 的注释）：Android 11 起没有它，
 *   本函数只会返回"本应用自己 + 少数系统包"，而且**不报任何错**。
 *
 * ★ 排序用 `Collator.getInstance(Locale.CHINA)` 而不是 `String.compareTo`：
 *   后者按 Unicode 码点排，中文名会全部堆在字母后面且内部顺序随机（看着像没排序）；
 *   Collator 对中文给出的是**拼音序**（"微信"在 W 区），这才是用户说的"A-Z"。
 *
 * ⚠️ 2026-09-29：**不再读每个应用的声明朝向**。它曾经是"外屏强制豁免"那一列的判据，
 *   而那条判据已被删除（读错对象 + 不许关，见 [AppWhitelist] 类注释"记过案"）。
 *   少读一个字段，也就少一条"界面与引擎说的不是同一件事"的可能。
 */
private fun loadInstalledApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()

    // 一个包可能有多个启动 Activity（如主界面 + 某插件入口）—— 按包名去重，取第一条
    val byPkg = LinkedHashMap<String, AppEntry>()
    activities.forEach { ri ->
        val ai = ri.activityInfo ?: return@forEach
        val pkg = ai.packageName ?: return@forEach
        if (pkg in byPkg) return@forEach
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
        byPkg[pkg] = AppEntry(pkg = pkg, label = label.ifBlank { pkg })
    }

    val collator = Collator.getInstance(Locale.CHINA)
    return byPkg.values.sortedWith(compareBy(collator) { it.label })
}

/**
 * 读引擎写在 `Settings.System` 里的「实测不可控」名单（见 [Uncontrollable]）。
 *
 * ★ 读系统设置**零权限**（与引擎状态串、标定值走同一条"引擎写、App 读"的通道）；
 *   写才需要特殊身份 —— 那正是"清除"必须请引擎代劳的原因。
 * ★ 读失败返回**空串**：这一格只影响界面提示，读不到时不显示红字比"显示一堆错的"安全 ——
 *   红字错了会直接误导用户（"这个应用不能被控制"）。
 */
private fun readUncontrollable(ctx: Context): String = runCatching {
    Settings.System.getString(ctx.contentResolver, Uncontrollable.KEY)
}.getOrNull().orEmpty()

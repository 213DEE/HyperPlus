package cn.dsr213.hyperplus.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppRotateMode
import cn.dsr213.hyperplus.AppWhitelist
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.SplitWhitelist
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 三级页：**应用名单** —— 旋转那份与分屏那份**共用这一份实现**（2026-10-06）。
 *
 * ============================ 它为什么从"内容块"升级成"页面" ============================
 * 用户原话：「**还是很乱，可以适当增加三级菜单，要让所有功能都清晰明了**」＋
 *   「（名单）**默认展开**，但是右边要加一个根据首字母的索引」。
 *
 * 在此之前它是嵌在二级页里的**内容块**（`AppWhitelistSection` / `SplitWhitelistSection`，
 * 两个函数一前一后铺在 `RotationPage` / `SplitScreenPage` 的末尾）。那条路的问题不是"内容错"，
 * 而是**长度没有上限**：一两百行 × 每行 `heightIn(min = 56.dp)` ≈ 6~12 屏，
 * 把二级页里真正的设置全部推到屏外（这正是"密密麻麻、不停往下滑"的病灶）。
 * ⇒ 按 `UI_信息架构优化方案` 的 **R7**：「进阶级内容只有两条出路……独立三级页只给
 *   '需要独立流程'或**'内容是巨型列表'**的东西用」—— 名单正是那个被点名的例外。
 *
 * ⚠️ **与 2026-10-01 那条旧口径的关系**：旧口径是「把应用名单合并进旋转增强页，
 *   不再是一个独立的**二级**菜单」。本次改的是"**二级**"那半句 ——
 *   名单仍然**不是一个二级页**，而是「二级页的一条入口 ＋ 一个三级页」。
 *   ⛔ 别据此把它搬回二级页；也⛔ 别退回"在二级页里铺开一百多行"。
 *
 * ============================ 为什么两份名单共用这一个函数 ============================
 * 它们**长得必须一样**（一份名单 = 说明卡 ＋「已设置」分区 ＋ 搜索 ＋ 统计 ＋ 重置 ＋ A-Z 列表），
 * 用户在两页看到的是同一种东西，不该为"这一页的列表为什么不一样"多花一秒。
 * 而它们的**差异只有四处**，全部由 [rotation] 一个参数决定：
 *
 * | | 旋转（`rotation = true`） | 分屏（`rotation = false`） |
 * |---|---|---|
 * | 数据源 | `AppPrefs.whitelist` | `AppPrefs.splitWhitelist` |
 * | 行形状 | [AppModeRow]（右侧四选一） | [AppRow]（右侧一个开关） |
 * | "已设置"判据 | 档位 ≠ 跟随全局（两层的和） | `pkg in wl`（只有一层） |
 * | 有无总开关 | 有（`gateEnabled`，关着时名单不生效 ⇒ 红字） | **没有**（名单闸就是名单本身） |
 *
 * ⇒ 这正是本文件 2026-10-05 那次"参数化"（见 [AppRow] 类注释）的同一条取向：
 *   **一份实现服务两份数据**，⛔ 而不是复制两份 200 行的列表代码 —— 复制出来的迟早会分叉，
 *   而分叉的症状是"某一页的开关拨不动"这种极难定位的错。
 *
 * ============================ 2026-10-06 的另外两处改动 ============================
 * ① **A-Z 全表从"默认收起"改成"默认展开"**（用户点名）。
 *    上一版（同日上午）为了压长度做成了「默认只渲染已设置项 + 一个『显示全部应用（N）』按钮」，
 *    那是在**二级页**里的权宜之计。现在整页都是它自己的，再收起就只剩下一个空壳
 *    ⇒ 恢复默认展开，并**去掉那个开关按钮**（`wl_show_all` / `wl_hide_all` 从此没有调用点，
 *    但资源保留：删除资源要动三套 xml，而它们不占界面、也不误导人）。
 * ② **换成 `LazyColumn`**（`[LazySubPage]`）：既能按组懒加载，也是首字母索引所必需的
 *    —— 没有 `LazyListState` 就没法"跳到第 N 组"。
 *    ⚠️ 之前那份 `Column` + `forEachIndexed` 是**全量 compose**，这是它"一两百行 = 6~12 屏"
 *      的另一半代价（`UI_信息架构优化方案` R8 末尾那条"性能事实"记的就是它）。
 *
 * ⛔ 两条**不能碰**的判据（都是踩过坑才写下来的）：
 *  - 开关的当前值/档位**只能**从 [AppPrefs] 读（`appModeOf` / 那份名单），界面自己推一遍
 *    就会在"用户手动关过默认项"这种情形上与引擎分歧（见类注释第 ② 条）；
 *  - 「已设置」分区的计数与 A-Z 列表**同口径**（都只算已安装的），见 `wl_count` 那一行。
 *
 * @param rotation `true` = 旋转那份（四选一）；`false` = 分屏那份（开关）。
 */
@Composable
internal fun WhitelistPage(
    rotation: Boolean,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current

    // ★ 唯一真值来源（理由见类注释末尾）。两份名单**各读各的**。
    val wl by (if (rotation) AppPrefs.whitelist else AppPrefs.splitWhitelist).collectAsState()
    // ★ 只有旋转那份要它：四选一的"点名"落在 `APP_ROTATE_MODES` 里（见 [AppModeRow] 那张表）。
    //   分屏那份只有"在名单 / 不在名单"两态，读它没有意义 —— 但读一次无害且能保证重绘，
    //   所以统一读、由下面的 [isOn] 决定用不用。
    val appModes by AppPrefs.appModes.collectAsState()
    // ★ 只有旋转那份有总开关（分屏的闸就是名单本身，见类注释那张表的最后一行）。
    val gateEnabled by AppPrefs.gateEnabled.collectAsState()

    var query by remember { mutableStateOf("") }

    /** `null` = 还没读完（读列表要几十~几百毫秒，别让界面先渲染一个空列表再跳一次） */
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    // ⚠️ 即使一进页面就返回，这次读取也会跑完 —— 它只读 PackageManager，不改任何状态。
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(ctx) }
    }

    /**
     * 每一行该显示哪一档（**R2 的唯一界面判据**）。
     *
     * ⚠️ 它**同时是订阅点**，不是多余的包装：Compose 只追踪 `collectAsState` 交出来的 `State`，
     *   不在这里把 `wl` / `appModes` 读一次的话，用户改了档位这一页**不会重绘**
     *   （要退出重进才看得到）。⛔ 判据本身仍然**只**来自 [AppPrefs.appModeOf]。
     */
    val modeOfRow: (String) -> AppRotateMode = remember(wl, appModes) {
        { pkg -> AppPrefs.appModeOf(pkg) }
    }

    /**
     * 这一行算不算"已经单独设置过"—— **两份名单的判据不同**（见类注释那张表）：
     * - 旋转：档位不是「跟随全局」就算（它可能是"进名单=跟随系统"，也可能是被点名成
     *   人脸/半自动）⇒ 只认 `pkg in wl` 会把后者漏掉，而那些恰恰最该出现在最上面；
     * - 分屏：只有"在不在名单里"这一层。
     * ⛔ 别把两者合成一个判据 —— 合成出来的那个对两份名单至少有一份是错的。
     */
    val isOn: (String) -> Boolean = remember(rotation, wl, appModes) {
        if (rotation) {
            { pkg -> AppPrefs.appModeOf(pkg) != AppRotateMode.FOLLOW_GLOBAL }
        } else {
            { pkg -> pkg in wl }
        }
    }

    val all = apps
    val searching = query.isNotBlank()
    val shown = all?.let { filterApps(it, query) }

    /**
     * A-Z 分组。**搜索态刻意不分组**：搜索结果是几条平铺的命中项，
     * 再按字母拆成一堆"只有一个元素的字母小节"，反而更难扫。
     */
    val groups: List<Pair<String, List<AppEntry>>> = remember(shown, searching) {
        if (shown == null || searching) emptyList() else groupByInitial(shown)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * 字母 → 该组在 `LazyColumn` 里的 **item 下标**。
     *
     * ⚠️⚠️ 这个数与下面铺 item 的顺序是**一对**，改任何一边都必须同时改另一边 ——
     *   否则"点字母跳到别处"。前缀 = [WL_PREFIX_ITEMS]，之后每组占 **2 个** item
     *   （一个字母小标题 ＋ 一张装着该组全部行的卡）。
     */
    val groupAt: Map<String, Int> = remember(groups) {
        val m = LinkedHashMap<String, Int>()
        var i = WL_PREFIX_ITEMS
        groups.forEach { (letter, _) ->
            m[letter] = i
            i += 2
        }
        m
    }

    /**
     * 右侧索引条。**只在真有多个字母组时才出现** ——
     * 结果全在一组里（或还在搜索）时，一根只有"#"的索引条没有任何用处。
     */
    // ★ 2026-10-06：这个条件现在还决定"要不要给列表留右边距"（见 contentPaddingEnd），
    //   所以提成具名值 —— 两处若各写一遍 `groups.size > 1`，将来改一处漏一处，
    //   就会变成"有索引条但没留边距"（卡片被压住）或反过来（白留一条）。
    val indexShown = groups.size > 1
    val indexOverlay: (@Composable BoxScope.() -> Unit)? = if (indexShown) {
        {
            AlphabetIndex(
                letters = groups.map { it.first },
                onPick = { letter ->
                    groupAt[letter]?.let { at ->
                        // ⚠️ 用 `scrollToItem`（瞬时跳）而不是 `animateScrollToItem`：
                        //   索引条的语义是"跳过去"。滑过两百行要好几秒，反而失去了定位的意义。
                        scope.launch { listState.scrollToItem(at) }
                    }
                },
                // 贴在右边缘、垂直居中（`AlphabetIndex` 内部自己 `Arrangement.Center`）。
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    } else {
        null
    }

    LazySubPage(
        title = stringResource(if (rotation) R.string.wl_page_title else R.string.swl_page_title),
        onBack = onBack,
        listState = listState,
        overlay = indexOverlay,
        // ★ 有索引条时，让卡片右边缘缩进 [INDEX_BAR_RESERVE] —— 索引条是**浮**在列表上的，
        //   ⛔ 不留这个边距，那层半透明底就会压在卡片的开关 / 箭头上（用户 2026-10-06 反馈）。
        contentPaddingEnd = if (indexShown) INDEX_BAR_RESERVE else 0.dp,
    ) {
        // ------------------------------------------------ ① 这张名单是干什么的
        item(key = "note-title") {
            SmallTitle(
                text = stringResource(
                    if (rotation) R.string.wl_section_note else R.string.swl_section_note,
                ),
            )
        }
        item(key = "note-card") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                // ★ 总开关关掉时这份名单**此刻不参与判断**，必须说出来（只有旋转那份有这个开关）。
                //   旧版是靠"把入口藏起来"避免这个困惑（关掉了就点不进来）；现在内容一直在，
                //   藏不掉 ⇒ 改用红字说明，比藏起来更让人明白。
                if (rotation && !gateEnabled) {
                    // ⚠️ 范围**是窄的**（2026-10-05 R2）：这个总开关只管名单那一层
                    //   （「跟随系统」那一档）；"人脸 / 半自动"走另一条通道，**不受它影响**。
                    //   所以档位名从资源取（`%1$s`）而不是写死 —— 档位名一改这里不会分叉。
                    //   ⛔ 别写回"改动不会有反应"：那在四选一之后是错的。
                    Text(
                        text = stringResource(
                            R.string.wl_gate_off,
                            stringResource(R.string.app_mode_system),
                        ),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.paragraph,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // ⚠️ 这里**不能写 markdown**（星号不会被渲染，会原样显示成 `**全局**`）——
                //   强调一律用「」。实测踩过：旧文案里的星号在真机上就是带着星号显示的。
                Text(
                    text = stringResource(
                        if (rotation) R.string.wl_explain else R.string.swl_explain,
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.paragraph,
                )
            }
        }

        // ------------------------------------------------ ② 已单独设置（**排在最上面**）
        //
        // ★ 为什么单独列一块（用户 2026-09-29 原话：「应用白名单里面，开启的应用要指定」）：
        //   下面那份 A-Z 列表是**全部已装应用**；而"我到底改过哪几个"才是用户真正要管的事
        //   —— 在一堆没动过的行里找那几个动过的，太难了。⇒ 改过的集中放在最上面。
        //   ⚠️ 只列**已安装**的（生效集合里还有一堆没装的默认包，列出来只有包名可显示）。
        //     所以这一格的计数与 A-Z 列表同口径。
        if (all != null) {
            val on = all.filter { isOn(it.pkg) }
            item(key = "on-title") {
                SmallTitle(
                    text = stringResource(
                        if (rotation) R.string.wl_on_title else R.string.swl_on_title,
                        on.size,
                    ),
                )
            }
            item(key = "on-card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp),
                ) {
                    if (on.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (rotation) R.string.wl_empty else R.string.swl_empty,
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.paragraph,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        Text(
                            // ⚠️ 标签 = 含义的对照写法，比两个"…的是…的"分句短，也更好扫。
                            text = stringResource(R.string.wl_tag_legend),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        on.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            // ⚠️ 本应用自己那句说明只在上面的 A-Z 列表里出现一次 ——
                            //   同一页说两遍会把这一块撑开，扫的时候反而看不到重点。
                            WhitelistRow(
                                rotation = rotation,
                                e = e,
                                mode = modeOfRow(e.pkg),
                                on = true,
                                showOwnNote = false,
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ ③ 搜索
        item(key = "search-title") {
            SmallTitle(text = stringResource(R.string.wl_section_search))
        }
        item(key = "search-card") {
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
                if (all != null) {
                    val nOn = all.count { isOn(it.pkg) }
                    // ⚠️ 多语言：分隔符（" · "）留在各段资源里，不同语言可以各自调整。
                    val countText = stringResource(
                        if (rotation) R.string.wl_count else R.string.swl_count,
                        all.size,
                        nOn,
                    ) + (
                        if (searching) {
                            stringResource(R.string.wl_count_match, filterApps(all, query).size)
                        } else {
                            ""
                        }
                        )
                    Text(
                        text = countText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.paragraph,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // ★ 按钮排版照抄用户 2026-10-02 定的那条：满宽（同类操作等宽）+ 上边距 8dp
                    //   （彼此分开，不黏在一起）。⚠️ `fillMaxWidth` 不会让按钮变高。
                    TextButton(
                        text = stringResource(if (rotation) R.string.wl_reset else R.string.swl_reset),
                        onClick = {
                            if (rotation) {
                                AppPrefs.resetWhitelistRemovals()
                            } else {
                                AppPrefs.resetSplitWhitelistRemovals()
                            }
                        },
                        modifier = BTN_SLOT,
                    )
                }
            }
        }

        // ------------------------------------------------ ④ A-Z 列表
        //
        // ⚠️⚠️ 从这一行往下铺的 item 顺序，就是 [groupAt] 算下标时假设的那一套
        //   （前缀 [WL_PREFIX_ITEMS] ＋ 每组 2 个）。⛔ 改顺序必须同时改那边。
        item(key = "list-title") {
            SmallTitle(text = stringResource(R.string.wl_section_installed))
        }

        when {
            all == null -> item(key = "loading") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wl_loading),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.paragraph,
                    )
                }
            }

            // 搜索态：一张扁平的卡（不分字母组，理由见 [groups]）
            searching -> item(key = "flat") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp),
                ) {
                    val hit = filterApps(all, query)
                    if (hit.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wl_no_match, query),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.paragraph,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        hit.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            WhitelistRow(
                                rotation = rotation,
                                e = e,
                                mode = modeOfRow(e.pkg),
                                on = isOn(e.pkg),
                            )
                        }
                    }
                }
            }

            // 默认态：**按字母分组，每组一个 item**
            else -> groups.forEach { (letter, list) ->
                item(key = "h-$letter") { SmallTitle(text = letter) }
                item(key = "g-$letter") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        insideMargin = PaddingValues(horizontal = 16.dp),
                    ) {
                        list.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            WhitelistRow(
                                rotation = rotation,
                                e = e,
                                mode = modeOfRow(e.pkg),
                                on = isOn(e.pkg),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 名单页里的一行 —— 按 [rotation] 在两种行形状之间二选一。
 *
 * ★ 抽这一层**只有一个目的**：让上面那两处铺行的地方（搜索卡 / 分组卡）不必各写一遍
 *   `if (rotation) … else …`。⛔ 它**不是**在把 [AppRow] 与 [AppModeRow] 合并 ——
 *   两者连布局契约都不同（前者左边两行字 + 右边一个开关，后者是库的
 *   `OverlaySpinnerPreference` 整行），合并只会合出一堆 `if`。
 */
@Composable
private fun WhitelistRow(
    rotation: Boolean,
    e: AppEntry,
    mode: AppRotateMode,
    on: Boolean,
    showOwnNote: Boolean = true,
) {
    if (rotation) {
        AppModeRow(
            e = e,
            mode = mode,
            isDefault = AppWhitelist::isDefault,
            onPick = AppPrefs::setAppRotateMode,
            showOwnNote = showOwnNote,
        )
    } else {
        AppRow(
            e = e,
            on = on,
            isDefault = SplitWhitelist::isDefault,
            onToggle = AppPrefs::setSplitWhitelisted,
            showOwnNote = showOwnNote,
        )
    }
}

/**
 * A-Z 分组之前**固定**铺的 item 数。
 *
 * 逐项对应 [WhitelistPage] 里 `item(key = …)` 的顺序：
 * `note-title` · `note-card` · `on-title` · `on-card` · `search-title` · `search-card` · `list-title`
 * ⇒ **7 个**。
 *
 * ⚠️⚠️ 它与 `groupAt` 的下标算法是一对，改任一边必须同时改另一边。
 *   索引条只在 `groups.size > 1` 时出现，而那时 `apps != null` 且不在搜索态 ⇒
 *   这 7 个 item **必然**都铺了（`on-title` / `on-card` 那两个在 `all != null` 分支里）。
 *   ⛔ 若将来把某个前缀 item 改成条件铺，这个常量就不再是常数 —— 那时要么把下标算法
 *     改成"边铺边记"，要么让索引条跟着失效。
 */
private const val WL_PREFIX_ITEMS = 7

/**
 * 把应用按**首字母**分组，返回**有序**的 `(字母, 组内应用)` 列表。
 *
 * ============================ 汉字怎么归组 ============================
 * 拉丁字母开头的直接取首字母；**汉字**用 [Collator] 去"猜拼音首字母"：把若干个
 * **拼音边界字**（见 [PINYIN_BOUNDS]）当作尺子，看这个字落在哪两个边界之间。
 * 依据是 `Collator.getInstance(Locale.CHINA)` 在 Android 上**默认就是拼音序**
 * （ICU 的中文默认排序），所以"比『八』大、比『擦』小"⇒ 拼音首字母是 B。
 *
 * ⚠️ **这条依赖需要真机验证**：若哪天 ICU 换了默认排序，"汉字全归到 #" 是它的退化表现
 *   （不会崩、也不会丢应用，只是索引变得没用）⇒ 装机后**必须**打开名单页看一眼
 *   汉字应用有没有散在各个字母下。
 * ⚠️ 数字 / 符号 / 认不出来的字符（含希腊字母、日文假名）一律归 `#`，且 `#` **排在最后**。
 */
private fun groupByInitial(list: List<AppEntry>): List<Pair<String, List<AppEntry>>> {
    val collator = Collator.getInstance(Locale.CHINA)
    return list
        .groupBy { letterOf(collator, it.label) }
        .toSortedMap(COMPARE_LETTER)
        .map { (letter, apps) -> letter to apps.sortedWith(compareBy(collator) { it.label }) }
}

/** 字母组的顺序：A→Z 升序，`#` 恒排最后 */
private val COMPARE_LETTER = Comparator<String> { a, b ->
    when {
        a == b -> 0
        a == "#" -> 1
        b == "#" -> -1
        else -> a.compareTo(b)
    }
}

/**
 * 汉语拼音的 **23 个边界字**（每个声母组里排序最靠前的那个字）。
 * ⚠️ 没有 I / U / V —— 汉语拼音不以它们开头。
 * ⚠️ 这些字只用于**比较**，永远不显示给用户；显示的是字母本身。
 */
private val PINYIN_BOUNDS = listOf(
    "A" to "阿", "B" to "八", "C" to "擦", "D" to "搭", "E" to "蛾",
    "F" to "发", "G" to "噶", "H" to "哈", "J" to "击", "K" to "喀",
    "L" to "垃", "M" to "妈", "N" to "拿", "O" to "哦", "P" to "啪",
    "Q" to "七", "R" to "然", "S" to "撒", "T" to "塌", "W" to "挖",
    "X" to "夕", "Y" to "压", "Z" to "匝",
)

/** 单个应用名 → 它所属的字母组（规则见 [groupByInitial]） */
private fun letterOf(c: Collator, label: String): String {
    val ch = label.trim().firstOrNull() ?: return "#"
    if (ch in 'A'..'Z' || ch in 'a'..'z') return ch.uppercaseChar().toString()
    if (ch.code > 127) {
        // 非 ASCII（绝大多数是汉字）：在拼音边界上定位。
        // ⚠️ 边界字按拼音**递增**排列，所以"最后一个 ≤ 它的边界"就是答案。
        var hit: String? = null
        for ((letter, bound) in PINYIN_BOUNDS) {
            if (c.compare(ch.toString(), bound) >= 0) hit = letter else break
        }
        if (hit != null) return hit
    }
    return "#"
}

/**
 * 一行：应用名 +（包名 · 默认清单 / 手动指定）+ 一个开关 + 可能的说明文字。
 *
 * ⚠️ **2026-10-05（R2）起，本函数只服务「分屏名单」**：旋转那份名单的行已经换成四选一的
 *   [AppModeRow]（用户拍板"每行右侧直接选 / 四选一，不跳页"）。分屏这边的语义是
 *   "折不折"，本来就只有两态 ⇒ 继续用开关。
 *   ⛔ 别把 [AppModeRow] 抄一份过来给分屏用，也**别删本函数** —— 两处各要一种行形状。
 * ★ 开关**任何应用都点得动** —— 包括本应用自己、包括默认清单、包括桌面。
 *   ⛔ 曾经有过"点不动的置灰行"，两类都已删除，别捡回来：
 *     ① 按应用声明推断出来的（记过案，详见 [AppWhitelist] 类注释）；
 *     ② 「实测不可控」（引擎观测到"写过方向但屏幕没动"就被永久置灰）——
 *        2026-10-05 按用户点名随该功能一起删除，改成转不动只弹一次提示。
 *
 * ★★ **2026-10-05 参数化**（这是本节最要紧的一处改动）：本函数原来把
 *   `AppWhitelist.isDefault` 与 `AppPrefs.setAppWhitelisted` **写死在函数体里** ——
 *   那时它只服务旋转名单。分屏增强要加**另一份独立的名单**（用户拍板"各自独立一份"），
 *   若把整个函数复制一份，就会有两份 200 行的列表代码要同步维护。
 *   ⇒ 抽出两个参数 [isDefault]、[onToggle]，让同一份行组件服务两份名单：
 *
 *   | 调用方 | [isDefault] | [onToggle] |
 *   |---|---|---|
 *   | 旋转名单（本页） | `AppWhitelist.isDefault` | `AppPrefs.setAppWhitelisted` |
 *   | 分屏名单（[SplitWhitelistSection]） | `SplitWhitelist.isDefault` | `AppPrefs.setSplitWhitelisted` |
 *
 *   ⚠️ **"默认清单"这个标签的判据必须跟着名单走**：两个功能的默认清单**不一样**
 *     （旋转 = 游戏 + 长视频；分屏 = 只有游戏）。写死 [AppWhitelist.isDefault] 的后果是
 *     "长视频在分屏页里被标成默认"，而那**恰恰是错的**（[SplitWhitelist] 刻意不收视频）。
 *     ⛔ 别把 [isDefault] 去掉、退回写死。
 *
 * @param on 当前是否开启。它决定副标题里的第二段标注：
 *   「默认清单」（出厂就带）还是「手动指定」（用户自己开的）。
 *   ⚠️ 两者都**只有开着的那些**才标 —— A-Z 列表里几百行关着的应用都挂一个
 *   「手动指定」的话，这一页就没法扫了。
 * @param isDefault 这个包是不是**调用方那份名单**的出厂默认项。
 * @param onToggle 用户拨动开关时的回调（调用方负责落到**它自己那份**集合里）。
 * @param showOwnNote 是否渲染「本应用自己」那条说明。它只在 A-Z 列表里出现
 *   （同一页的「已开启」分区里不重复说，见那边注释）。
 */
@Composable
internal fun AppRow(
    e: AppEntry,
    on: Boolean,
    isDefault: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
    showOwnNote: Boolean = true,
) {
    // ⚠️ 2026-10-03 多语言：`buildString` 里**不能**直接调 `stringResource`（它只允许
    //   出现在 Composable 的语句位置）。所以先把三段标签取出来，再在 `buildString` 里用。
    val tagDefault = stringResource(R.string.wl_tag_default)
    val tagManual = stringResource(R.string.wl_tag_manual)
    val summary = buildString {
        append(e.pkg)
        if (isDefault(e.pkg)) {
            append(tagDefault)
        } else if (on) {
            append(tagManual)
        }
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
            Switch(
                checked = on,
                // ⚠️ 2026-10-05 参数化：原来这里**写死** `AppPrefs.setAppWhitelisted`。
                //   ⇒ 换成调用方传进来的 [onToggle]，否则分屏页里拨开关会**改到旋转名单上去**。
                onCheckedChange = { v -> onToggle(e.pkg, v) },
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

/**
 * ★★★ **R2 的一行：应用名 +（包名 · 标签）+ 右侧「四选一」下拉**（2026-10-05）。
 *
 * ============================ 它为什么不是 [AppRow] 加个参数 ============================
 * [AppRow] 的行形状是「左边两行字 + 右边一个开关」—— 开关的语义只有"开 / 关"两种取值。
 * 本行要的是**四选一**（跟随全局 / 跟随系统 / 人脸 / 半自动），右边控件从 [Switch] 换成了
 * Miuix 官方的 [OverlaySpinnerPreference]（行内下拉；⛔ 别自己画一个出来）。
 * 两者连**布局契约**都不同：[OverlaySpinnerPreference] 自带 `BasicComponent`，
 * **它本身就是一个整行**，不能再塞进 [AppRow] 那个 `Row` 里 ⇒ 只能另起一个函数。
 * ⛔ 别为了"少一个函数"把两者合并：合出来的那个会带一串 `if (是不是四选一)` 分支，
 *   而分支走错的表现是"某一页的控件拨不动"这种极难定位的症状。
 *
 * ★ 本行**只服务旋转那一份**（应用名 + 四选一）。分屏名单仍走 [AppRow] ——
 *   那边的语义是"折不折"，本来就只有两态。
 *
 * ============================ 四档的落盘分工 ============================
 * 见 [AppWhitelistSection] 类注释那张表。本函数**不关心**它 —— 只管"用户点了第几项"，
 * 落盘交给 [onPick]（[AppPrefs.setAppRotateMode] 会同时处理名单那两层与点名记录）。
 *
 * @param mode 当前选中的档。判据**必须**来自 [AppPrefs.appModeOf]（调用方传 `modeOfRow`）；
 *   ⛔ 别在这里从 `whitelist` / `appModes` 自己推 —— 那就是把判据抄了第二份。
 * @param isDefault 这个包是不是**旋转名单**的出厂默认项（决定副标题标「默认清单」）。
 * @param onPick 用户选了某一档时的回调（包名 + 档）。
 * @param showOwnNote 是否渲染「本应用自己」那条说明（只在 A-Z 列表里出现一次）。
 */
@Composable
private fun AppModeRow(
    e: AppEntry,
    mode: AppRotateMode,
    isDefault: (String) -> Boolean,
    onPick: (String, AppRotateMode) -> Unit,
    showOwnNote: Boolean = true,
) {
    // ⚠️ 多语言纪律同 [AppRow]：`buildString` 里**不能**直接调 `stringResource`，
    //   先把三段标签取出来再用。
    val tagDefault = stringResource(R.string.wl_tag_default)
    val tagManual = stringResource(R.string.wl_tag_manual)
    val summary = buildString {
        append(e.pkg)
        // ★ 标签口径与升级前**逐字一致**（`isDefault` 优先，其次看"动过没有"）——
        //   只是"动过"的判据从"开关开着"变成了"档位不是跟随全局"。两档共用同一个标签。
        if (isDefault(e.pkg)) {
            append(tagDefault)
        } else if (mode != AppRotateMode.FOLLOW_GLOBAL) {
            append(tagManual)
        }
    }

    // ★ 下拉的四个选项：次序来自 [AppRotateMode.PICKER]（= 从最不干预到最干预），
    //   显示名来自各档自己的 `labelRes`（多语言）。
    //   ⚠️ `stringResource` 出现在 `map` 的 lambda 里是合法的（`map` 是 inline，
    //     内联到本 Composable 里 ⇒ 仍是 Composable 上下文）；但 `remember {}` 里**不行**
    //     （那块的 lambda 禁止 Composable 调用）—— 所以这里**刻意不 remember**：
    //     每次重组重建 4 个短对象，代价可以忽略，换来的是"语言一切换立刻就对"。
    val items = AppRotateMode.PICKER.map { DropdownItem(text = stringResource(it.labelRes)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OverlaySpinnerPreference(
            items = items,
            // ⚠️ 用 `indexOf` 而不是自己维护一个整数：`PICKER` 次序改了这里自动跟上。
            //   `PICKER` 是同一个枚举的全集 ⇒ 一定包含 `mode`，不会出现 -1。
            selectedIndex = AppRotateMode.PICKER.indexOf(mode),
            title = e.label,
            summary = summary,
            // ⚠️ 左右内边距归零：卡片自己已经给了 `horizontal = 16.dp`
            //   （见调用处的 `Card(insideMargin = ...)`）。这里再给 16 就成了 32dp 缩进，
            //   与同一页里 [AppRow] 的行对不齐。
            //   ⚠️ 上下也归零：`BasicComponent` 自带 `heightIn(min = 56.dp)`，
            //     再叠 [AppRow] 那套 `vertical = 12.dp` 会让整页的行明显变高。
            insideMargin = PaddingValues(horizontal = 0.dp),
            onSelectedIndexChange = { i ->
                // ⚠️ 索引**理应**落在 `PICKER` 里（items 就是它变的），但仍用 `getOrNull`
                //   兜一下：这个回调来自库内部，一旦越界就是**崩溃** ——
                //   而崩溃点会是"用户点了一下下拉"这种最普通的动作。
                AppRotateMode.PICKER.getOrNull(i)?.let { onPick(e.pkg, it) }
            },
        )
        if (e.pkg == AppWhitelist.OWN_PACKAGE && showOwnNote) {
            // ★ 与 [AppRow] 逐字同一条说明：本应用自己也是普通一行。
            Text(
                text = stringResource(R.string.wl_own_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(bottom = 8.dp),
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

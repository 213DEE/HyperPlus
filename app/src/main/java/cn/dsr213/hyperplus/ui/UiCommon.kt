package cn.dsr213.hyperplus.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppToast
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全工程共用的界面零件与文案工具。
 *
 * ★ 这些原本都塞在 `EngineScreen.kt` 里（那个文件 1100+ 行，既管主页又管诊断页）。
 *   2026-09-29 拆分时按「谁都要用就搬这里」的原则抽出来 ——
 *   副标题文案、排障读数、格式化函数都是**多个页面共用**的，
 *   留在某一个页面文件里会让另一个页面被迫反向依赖。
 *
 * ============================ 2026-10-03 多语言改造 ============================
 * ★ 本文件下半部分那一批「文案函数」（[rotName] / [voteLine] / [phaseText] …）现在
 *   **都接一个 `Context` 首参**，内部走 `ctx.getString`。
 *
 *   ⚠️ 为什么不是把它们改成 `@Composable` + `stringResource`（那样调用点一行都不用改）：
 *     因为它们**不总是出现在"语句位置"** —— 例如 `StatusPage` 里
 *     `append("（当前状态：${phaseText(...)}）")` 是在**字符串模板**里求值，
 *     而 Composable 不能在那样的位置被调用（`append` 的实参不是 Composable 上下文）。
 *     加参数则没有这个限制，而且**编译器会把每一个漏改的调用点都报出来** ——
 *     对一个"必须全量覆盖"的改造来说，这是最值钱的一条。
 *
 *   ⚠️ 这些函数**只在 App 进程**用（引擎侧另有一份只写日志的同名函数，
 *     见 `AdaptiveEngine` 里的 `rotName`，那一份**不动**：日志口径与人读口径本就该分开）。
 */

/**
 * 悬浮底栏占掉的高度。
 *
 * ★ 为什么必须显式留：底栏是**悬浮**的（浮在内容之上），不是 Scaffold 的 `bottomBar`，
 *   所以框架不会自动把内容推上去。不留这段的话，每个页面最后一行都会被玻璃条压住 ——
 *   而玻璃条是半透明的，用户看到的是"字被糊住了"，比干脆遮住更难判断。
 *
 * 取值 = 底栏高度（54dp，见 `LiquidGlassBar.BAR_HEIGHT`）+ 底边留白（16dp，
 *   `LiquidGlassBar.BAR_MARGIN`）+ 呼吸余量。
 * ⚠️ 2026-10-06：底边留白从 8dp 改成了 16dp（用户「太靠下了，往上调节一下」），
 *   底栏的**上沿**因此从"离底 62dp"变成"离底 70dp"——仍然小于 100dp，**本值不用改**。
 * ⚠️ 底栏高度改小之后这里**必须跟着改小**：留多了页面底部会莫名其妙空一大块，
 *   留少了最后一行会被玻璃压住 —— 两边都不好，所以两个常量要一起看。
 * ⚠️ 它**只与高度有关**，与底栏宽度无关 —— 宽度这次从 188dp 改成了"外屏宽 − 两侧留白"，
 *   这个值一行都不用动。
 */
internal val BOTTOM_BAR_RESERVE = 100.dp

/** 页面内容统一水平留白（与 hyperos 设置页一致：12dp） */
private val PAGE_H_PADDING = 12.dp

/**
 * **卡片内容的水平内缩** —— 与 [TextCard] 给的是同一个数。
 *
 * ★ 为什么要有这个数（2026-10-07 用户点名）：[BTN_SLOT] 只管「满宽 + 上边距
 *   [CARD_BTN_GAP]」，**左右与底部边距是靠外层卡片的 `insideMargin` 给的**：
 *     - 卡片是 [TextCard] ⇒ 16dp 自动就有，✅ 什么都不用写；
 *     - 卡片是 [SectionCard]（内边距 **0**）或手写 `Card(insideMargin = 0.dp)` ⇒
 *       **一点边距都不给**，按钮会贴住卡片边缘。这时要么整卡换成 [TextCard]
 *       （装纯文字时首选），要么给控件补
 *       `padding(start = CARD_H_INSET, end = CARD_H_INSET)`。
 *   ⚠️ 同一个坑踩了三次：诊断页「导出诊断日志」、分屏页「恢复默认」「确认」。
 *   ⛔ 别写裸 `16.dp` —— 这个数只该有一处定义。
 */
internal val CARD_H_INSET = 16.dp

/**
 * 卡片 + 小标题的标准组合。**默认不给自己加内边距**，留给里面的控件。
 *
 * ★ 界面里"小标题 + 一张卡片"这个形状出现了几十次，每次手写 [SmallTitle] + [Card] 时
 *   间距都得自己记得加对（漏一个 `padding(bottom = 12.dp)` 就是"两块粘在一起"）。
 *   统一成这个函数之后，间距只有一处定义。
 *
 * ⚠️ **`insideMargin` 默认 `PaddingValues(0.dp)` 是刻意对齐库的行为，不是漏写**：
 *   MiuiX 的 `CardDefaults.InsideMargin` 就是 0，因为里面装的偏好控件
 *   （`SwitchPreference` / `ArrowPreference` / `RadioButtonPreference` …）**自带内边距**
 *   （`BasicComponentDefaults.InsideMargin`，约 16dp 起）。在外面再裹一层 16dp
 *   会让它们被挤成"双层留白"，一行开关变得又高又窄 —— 旧版主页上「开关」那张卡
 *   之所以看着比别的卡紧，就是因为那张卡没传 `insideMargin`、别的卡传了 16dp。
 *   ⇒ 装偏好控件用本函数（不传）；装纯文字用 [TextCard]。
 */
@Composable
internal fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) SmallTitle(text = title)
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = insideMargin,
        content = content,
    )
}

/**
 * **「你此刻在外屏」的置顶红条**（2026-10-02 用户点名）。
 *
 * 用户原话：「还有"你现在在外屏、外屏不做处理"这种提示，**改成红字或红底置顶**，
 * "当前屏幕不生效，请打开内屏"」。
 *
 * ★ 为什么从普通说明卡升级成红条：那句话以前是页面**中段**的一张灰卡，语气是"告知"，
 *   而它说的其实是**此刻这一页上的东西全都不会生效** —— 那是**故障口吻**的事，
 *   不是背景知识。灰卡会被当成"说明文字"扫过去，红条才会被停下来读。
 *
 * ★ 两条实现纪律：
 *  ① **置顶**：调用点必须放在页面内容的最前面（[RotationPage] 里它在「模式」卡之上）。
 *     放在被影响的那一行旁边是没用的 —— 用户已经先读到那一行、甚至先动手改了。
 *  ② **不写死颜色**：用主题的 `errorContainer` / `error` / `onErrorContainer`
 *     三件套（深浅色模式各有一套值）。自己填 #FF… 到深色模式上就糊了。
 *
 * @param reason 造成"当前屏幕不生效"的**具体**原因（一句话，随场景不同）。
 */
@Composable
internal fun OuterScreenBanner(reason: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.outer_banner_title),
            // ⚠️ 用 `error`（红）而不是 `onErrorContainer`：在浅色模式下
            //   `errorContainer` 是淡红底，`onErrorContainer` 偏深棕；要的是"红字"的观感。
            color = MiuixTheme.colorScheme.error,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = reason,
            color = MiuixTheme.colorScheme.onErrorContainer,
            style = MiuixTheme.textStyles.paragraph,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 纯文字卡片（说明 / 读数 / 提醒）—— 16dp 内边距。
 *
 * ★ 单独开一个函数而不是每次手写 `SectionCard(insideMargin = PaddingValues(16.dp))`：
 *   上面那个默认值是 0，忘了传就会得到"文字贴着卡片边缘"的难看结果，
 *   而这种错误在编译期看不出来、在代码评审里也不显眼。
 *   名字里带 Text 是让"这张卡装的是字"这件事在调用点一眼可见。
 */
@Composable
internal fun TextCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = SectionCard(
    title = title,
    modifier = modifier,
    insideMargin = PaddingValues(CARD_H_INSET),
    content = content,
)

/**
 * 顶栏导航位里的**返回箭头**（HyperOS 形状 + RTL 镜像）。
 *
 * ★ 抽出来的理由：现在有 **8 个**二级/三级页要用同一个箭头（全部经 [SubPage]，
 *   而 [BackIcon] 现在**只有 [SubPage] 内部这一个调用点**），
 *   而它带两件容易漏的事 —— RTL 下 `scaleX = -1`、以及 `contentDescription`。
 *   抄 8 遍的下场是某一处忘了镜像（阿拉伯语下箭头指错方向），或者忘了无障碍描述。
 * （写法照抄官方示例 `BackNavigationIcon.kt`。）
 *
 * ⚠️ 2026-10-03 多语言：默认描述从字面量 `"返回"` 改成 `null` + 内部回落资源 ——
 *   **默认参数值里不能调用 `stringResource`**（它只在 Composable 体内才合法）。
 */
@Composable
internal fun BackIcon(onBack: () -> Unit, contentDescription: String? = null) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onBack) {
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = contentDescription ?: stringResource(R.string.common_back),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 卡片内按钮与**上一行**之间的垂直间距 —— 见 [BTN_SLOT]。
 * ⛔ 别再引入第二个数字。
 */
internal val CARD_BTN_GAP = 8.dp

/**
 * **卡片内按钮的统一外观：满宽 + 与上一行的 [CARD_BTN_GAP] 间距。**
 *
 * ★★ 出处（用户亲口定的，2026-10-04 晚）：
 *   「**按钮之间要有间距，不要黏在一起**」。
 *   动机不只是好看：Miuix 的 [top.yukonga.miuix.kmp.basic.TextButton] 自带底色与圆角，
 *   两个挨着摆时**看起来像一个被切成两块的整块**，用户分不清哪儿是边界；
 *   危险动作与"就这么定了"贴在一起时，误触的代价是后者。
 *
 * ★ 8dp 这个数不是新定的：它早就是**全工程卡片内按钮的既有约定**
 *   （`RotationPage` / `AppWhitelistPage` / `AboutPage` 里都写着"与全工程其他卡片内
 *   按钮统一：等宽 + 上边距 8dp"）。
 *
 * ★★ 它最早是 2026-10-05 从「角度校准」三级页（`SplitCalibrationPage`，**已于 2026-10-06 随
 *   校准功能一起下线**）的 `private val` 提上来的：它在那个文件里是私有的，于是**别处再写
 *   按钮就只能重新发明一遍** —— `SplitScreenPage` 的「温度上限 / 确认」第一版就是这么写歪的
 *   （裸 `Text` + `clickable`、不满宽、无间距）。提到这里之后，"全工程卡片内按钮统一"才是
 *   **一处定义、处处引用**，而不是一句要靠人记的纪律。
 *   ⛔ 别在页面文件里再定义一个同义的 `BTN_SLOT`；新加卡片内按钮一律用这一个。
 *
 * ★★★ **边距归谁给（2026-10-07 用户点名补写，务必先读这段）**：
 *   本 Modifier **只给**「满宽 + 上边距 [CARD_BTN_GAP]」——
 *   **左右与底部边距来自外层卡片的 `insideMargin`**：
 *     - 卡片是 [TextCard] / `Card(insideMargin = 16.dp)` ⇒ 按钮自动有边距，✅ 到此为止；
 *     - 卡片是 [SectionCard]（内边距 **0**）/ 手写 `Card(insideMargin = 0.dp)` ⇒
 *       **卡片一点边距都不给**，按钮会**贴住卡片左右边缘**（是不是还贴底，看它是否
 *       是本卡最后一个控件）。⇒ 这时要么整卡换成 [TextCard]（装纯文字时首选），
 *       要么给控件补 `padding(start = CARD_H_INSET, end = CARD_H_INSET)`。
 *   ⚠️ 这个坑实际踩了三次：诊断页「导出诊断日志」、分屏页「恢复默认」「确认」。
 *       ⛔ 别再踩第四次。
 *
 * ⚠️ 声明顺序：本 val 引用 [CARD_BTN_GAP]，所以后者**必须写在前面**
 *   （顶层 `val` 按声明顺序初始化，倒过来会编译不过：`Variable must be initialized`）。
 */
internal val BTN_SLOT: Modifier = Modifier.fillMaxWidth().padding(top = CARD_BTN_GAP)


/**
 * 主页（功能 / 设置）的**薄顶栏** —— 自建外壳，代替库的 `TopAppBar(largeTitle=…)`。
 *
 * ============================ 为什么不用库的（2026-10-06）============================
 * 用户定位：「**顶栏太厚了，都快占整个屏幕的五分之一了**」。
 * 反查 `miuix-ui` 0.9.4（javap 签名 + 拉上游 `TopAppBar.kt` 源码 + 真机逐行像素统计，
 * 三条互证）后确认库版展开态是：
 *
 * ```
 * 状态栏 + CollapsedHeight(52dp) + 大标题行 + [副标题行] + SubtitleBottomPadding(8dp)
 * ```
 *
 * 那 **52dp 是给「滚动折叠」预留的**（收起后小标题就住在这 52dp 里）——
 * 而本工程**刻意不接滚动折叠**（理由见 [FunctionPage] 头注释）⇒ 它从头到尾都在白占，
 * 连带底部那 8dp 也是（因为我们永远不展开/收起，不需要让位给"小副标题"）。
 * 且库的 `TopAppBar` **16 个公开参数里没有一个是高度**（javap 反查），
 * 用 `Modifier.height()` 强压只会把大标题裁掉半截（库内部已 `clipToBounds`）。
 *
 * ⇒ 自建后总高 = `状态栏 + 6dp + 大标题行 + [副标题行] + 8dp`：
 *   **净降 46dp**（= 52 + 8 − 6 − 8），而**副标题（版本号）照旧显示**。
 *   ⚠️ 这个 46dp 是"与状态栏高度无关"的净差值，换屏/换密度都成立。
 *   ⚠️ 水平留白取 26dp，与库的 `TopAppBarDefaults.TitlePadding` 默认值一致
 *     ⇒ 换壳之后标题的**左右位置不变**，只有竖直方向变薄。
 *
 * ============================ 复刻了库的哪些外观 ============================
 * - 大标题：`textStyles.title1.fontSize` + `FontWeight.Normal` + `onSurface`（与库逐字对齐）
 * - 副标题：`textStyles.body2` + `onSurfaceVariantSummary`（同上）
 * - 背景：`colorScheme.surface`（库用的是 `Modifier.background(color)`，本函数据此照做）
 * - 状态栏避让：`windowInsetsPadding(systemBars.only(Top))`（库在同一层做同一件事）
 *
 * ⛔ **别退回库的 `TopAppBar`** —— 那是"一改就打回原形"（白占 60dp 的问题会原样回来）。
 * ⚠️ 想加"滚动时收起大标题"的话：那是另一个设计决策，需要自己写滚动监听，
 *   ⛔ 不要为了它把库顶栏装回来。
 */
@Composable
internal fun HomeTopBar(
    title: String,
    /** 副标题；`null` 表示不占这一行（比传空串更省一行高度）。 */
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .padding(horizontal = HOME_BAR_H_PADDING)
            .padding(top = HOME_BAR_TOP_PAD, bottom = HOME_BAR_BOTTOM_PAD),
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = MiuixTheme.textStyles.title1.fontSize,
            fontWeight = FontWeight.Normal,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

/** 薄顶栏的水平留白 —— 与库的 `TopAppBarDefaults.TitlePadding`(26dp) 对齐，换壳后标题左右位置不变 */
private val HOME_BAR_H_PADDING = 26.dp

/**
 * 薄顶栏的上留白（状态栏之下）。
 * ⚠️ 它和 [HOME_BAR_BOTTOM_PAD] 是**唯一**两个决定"顶栏有多厚"的数
 * （库那边是 52dp + 8dp 的硬编码）—— 想再调厚薄就动这两个，⛔ 别去改字号。
 */
private val HOME_BAR_TOP_PAD = 6.dp

/** 薄顶栏的下留白（大标题块与内容区之间） */
private val HOME_BAR_BOTTOM_PAD = 8.dp

/**
 * **二级页的统一外壳**：小号顶栏（含返回箭头）+ 可滚动内容区（已为悬浮底栏留位）。
 *
 * ★ 返回口这次从"页内一个「← 返回」文字按钮"（旧 DiagnosticsPage 的做法）换成了
 *   **顶栏导航位**：旧做法是当时为了绕开"MiuiX 各版本 TopAppBar 导航位参数不一致"，
 *   而现在用的 0.9.4 明确有 `navigationIcon`（已由 javap 核实签名），
 *   再让用户滚到页面顶部去找返回按钮就不合适了 —— 那是可以避免的一步操作。
 *   ⚠️ 2026-09-29 追加的理由更硬：**页内按钮会随内容滚走**。二级页现在也浮着一条玻璃底栏，
 *     用户滚到中段想返回时必须先滚回顶部 —— 而顶栏是钉住的。
 */
@Composable
internal fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = { BackIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PAGE_H_PADDING),
        ) {
            content()
            // ⚠️ 二级页同样要给悬浮底栏留位（理由见 [BOTTOM_BAR_RESERVE]）。
            //   漏了这处的话，只有二级页会"最后一行被玻璃条压住"，而主页正常 ——
            //   这种"一个页面跟别的页面不一样"的错最难被联想到是底栏造成的。
            Spacer(Modifier.height(BOTTOM_BAR_RESERVE))
        }
    }
}

/** 主页（功能 / 设置）的可滚动内容区，已为悬浮底栏留好高度 */
@Composable
internal fun HomeColumn(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_H_PADDING),
    ) {
        content()
        Spacer(Modifier.height(BOTTOM_BAR_RESERVE))
    }
}

/**
 * **长列表页的外壳** —— 内容是一两百行长列表时用它，⛔ 不用 [SubPage]。
 *
 * ============================ 为什么必须另开一个（2026-10-06）============================
 * [SubPage] 的滚动容器是 `Modifier.verticalScroll`（`Column` 一次性组合、整页滚动）。
 * 名单级的页面有两件它做不到的事：
 *  1. **长度**：一两百行一次性 compose（`AppWhitelistPage.kt` 注释里的测算：6~12 屏）；
 *  2. **定位**：右侧首字母索引要"跳到某一组" —— 那需要一个 `LazyListState`。
 * ⇒ 两者都要求 `LazyColumn`。而把 `LazyColumn` 塞进 `verticalScroll` 里是**同向嵌套滚动**，
 *   会打架（滑动冲突 / 测量异常）⇒ 只能给这类页面单独一个外壳。
 *
 * ⚠️ 分工（与 [SubPage] 并列，不是替代）：
 *   - 绝大多数二/三级页仍是 [SubPage]（内容短、一次性组合更简单）；
 *   - **只有"内容是一两百行长列表"的页面**用本函数 —— 目前是两份应用名单
 *     （`Route.RotationApps` / `Route.SplitApps`）。
 *
 * ============================ 与 [SubPage] 保持一致的三件事 ============================
 * 顶栏形状（`SmallTopAppBar` ＋ 返回箭头）、水平留白（`PAGE_H_PADDING`）、
 * 以及**为悬浮底栏留位**（末尾 `Spacer(BOTTOM_BAR_RESERVE)`）——
 * ⛔ 漏了最后一项的话，只有这一页的最后一行会被玻璃条压住，而别的页面正常。
 *
 * @param listState 调用方持有它才能做"跳到第 N 项"（索引条要用）；不给就自己建一个。
 * @param overlay 浮在内容之上的东西（目前只有右侧索引条）。`null` = 没有。
 *   它被放在 `Box` 里、**在列表之后**绘制 ⇒ 天然盖在列表上，且不占列表宽度。
 * @param contentPaddingEnd 列表**额外的右边距**。★ 有 `overlay` 时要把它设成那条
 *   overlay 占掉的宽度（见 [INDEX_BAR_RESERVE]）—— 否则卡片会被 overlay 压住右边缘。
 *   ⚠️ 这条是 2026-10-06 加半透明底之后才需要的：**没底**时压住一点留白看不出来，
 *     有了底就是"一块色压在卡片上"，用户当场反馈「加了背景色之后会遮挡内容卡片，
 *     内容卡片右边留点边距」。⇒ 只要 overlay 是"有底色的一块"，就必须留这个边距。
 */
@Composable
internal fun LazySubPage(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    contentPaddingEnd: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = { BackIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PAGE_H_PADDING,
                    end = PAGE_H_PADDING + contentPaddingEnd,
                ),
            ) {
                content()
                // ⚠️ 与 [SubPage] / [HomeColumn] 同一条纪律：悬浮底栏是**浮**在内容上的，
                //   框架不会替内容让位（理由见 [BOTTOM_BAR_RESERVE]）。
                item { Spacer(Modifier.height(BOTTOM_BAR_RESERVE)) }
            }
            // 索引条画在列表**上面**（不在列表的测量里），但它占的那条宽度由调用方
            // 通过 `contentPaddingEnd` **让**出来（见该参数的说明）。
            // ⚠️ 不是"把它塞进列表当一列"：那样它会跟着列表一起滚，也就不叫索引条了。
            overlay?.invoke(this)
        }
    }
}

/**
 * **右侧首字母索引条** —— 点或**划**一个字母，列表跳到那一组。
 *
 * ============================ 它是自绘，为什么这次可以 ============================
 * ⚠️ `Miuix 0.9.4` **没有**索引条组件（已用 `unzip -l` 列全类清单取证：`basic` 包里
 *   只有通用 `ScrollBar`，没有任何 `Index*` / `Letter*` / `Alphabet*`）。
 * UI 规范【零】禁的是"**自己发明一个『看起来差不多』的控件**"（典型反面例子是自绘开关）——
 * 那条禁令要防的是"用户一眼看出这不是系统里的东西"。
 * 而本组件是**列表的导航附属元素**（系统通讯录、小米应用列表里都是这个形态），
 * 且刻意做得**最朴素**：纯文字 + 主题色，不画形状、不加阴影、不改字号层级。
 * ⇒ 本次按用户 10-06 点名（「右边要加一个根据首字母的索引」）**特意放行**。
 * ⛔ 别把它当成"自绘控件开了口子"的先例：将来任何"看起来像按钮/开关/卡片"的自制件
 *   仍然要先问用户。
 *
 * ============================ 交互：点 + 滑动（2026-10-06）============================
 * 用户原话：「**索引条需要滑动选择**」。
 * ★ 两种手势都要支持，而且**按下那一刻就要跳**：
 *   · **点**：手指落在哪个字母上，立刻跳那一组（点一下就松手同样生效）；
 *   · **划**：按住别松、沿条上下滑，手指经过哪个字母就跟着跳哪一组。
 * ⚠️ 这正是系统通讯录 / 应用列表里索引条的形态 —— 一屏二三十个字母、每个格子
 *   只有十几 dp 高，只能靠「按住再滑」来选准；只支持点的话，跨好几组要反复点。
 * ⚠️ 所以**不能**用 `detectVerticalDragGestures`：那个要等「滑过 touch slop 才算拖」，
 *   点一下就走不到它的回调里。这里用底层 `awaitEachGesture` 自己循环取坐标。
 * ★ 事件在本组件范围内一律 `consume()` —— 否则手指在条上滑动会**穿透去滚列表**，
 *   变成「列表自己在滚、索引还乱跳」。

 * ============================ 放大提示（2026-10-06）============================
 * 用户原话：「**滑动到哪个字母的时候，旁边多加一个放大字体，强调提示**」。
 * ★ 为什么加：字母条本身**刻意做得很小**（每格 [INDEX_CELL_H]、字号 `footnote2`，
 *   否则 26 格放不下一屏）⇒ 手指压上去时，那几格正好被指尖挡住，根本看不清指到了谁。
 *
 * ⚠️⚠️ **它固定在条"上方"，不跟着手指走**（第一版贴在条左侧、随手指上下 —— 用户
 *   10-06 当场纠正：「**加在索引条的顶部吧，现在这个位置，滑动的时候会被手指遮挡**」）。
 *   教训：提示是为了"看得见"，而**指尖恰恰就是那个挡住视线的东西** ⇒
 *   凡是"跟手"的位置都可能被自己的手遮住，把提示挪到**手够不到的那一端**才是对的。
 *   ⇒ 现在纵向固定在 [INDEX_HINT_SLOT_H] 那个槽位里；它要回答的问题
 *     （"我这一下会落到哪个字母"）由**大字的内容**回答，不需要它跟着跑。
 * ★ 槽位是**常驻占位**：哪怕没按住也占着 [INDEX_HINT_SLOT_H]，
 *   这样按住 / 松手时字母条**不会上下跳**；条下方还有一块等高的 `Spacer`，
 *   让字母条本身仍然落在屏幕垂直中心（否则会被这块槽位顶下去半个槽高）。

 * ============ 右缘那一带的四轮调整（2026-10-06，每轮都有原话）============
 * ① 「**索引条加个半透明背景色**」⇒ 加了一层 `secondaryContainer` + 半透明底。
 * ② 「加了背景色之后会**遮挡内容卡片**，内容卡片**右边留点边距**」＋「**圆角太大**」
 *    ⇒ 卡片让距 [INDEX_BAR_RESERVE]；形状由胶囊改成固定 [INDEX_CORNER]。
 * ③ 「**边距太大了**」＋「**大字强调和下方的索引条贴在一起**」
 *    ⇒ 让距 36 → 26dp；大字与条之间加 [INDEX_HINT_GAP_V]。
 * ④ 「索引条**太贴边了**……**居中一下**」＋「**去掉背景色**」
 *    ⇒ 条右退 [INDEX_BAR_INSET] 居中；**字母条不再画底**。
 * ★ 现在的形态：**字母条只有字**（落在右退 5dp 的那条竖线上）；
 *   只有**放大提示**还带底（[indexSurface]，它是浮层读数）。
 *   ⛔ 别给提示单独换色 / 加阴影，那会让它看起来像另一个控件（规范【零】）。

 * ============================ 尺寸为什么是这两个数 ============================
 * 每个字母一格的触控区 = [INDEX_CELL_W] × [INDEX_CELL_H]。
 * ⚠️ 它**低于**规范【一、①】那条 48dp 最小触控高度 —— 这是索引条这个形态的固有代价
 *   （26 个字母 × 48dp = 1248dp，比任何一块屏都高，那样根本放不下）。
 *   系统通讯录同样如此 ⇒ 用「格子做小、整条竖排在右缘那一带」换「一屏放得下」。
 *   ⛔ 别把 CELL_H 加大到"合规" —— 那会让字母条高过屏幕。
 * @param letters 只传**实际存在的**分组字母（列表里没有 Z 开头的应用就不必显示 Z）。
 * @param onPick 点某个字母时的回调（调用方负责 `scrollToItem`）。
 */
@Composable
internal fun AlphabetIndex(
    letters: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    // 手指当前压着的字母下标（-1 = 没按住）。**只服务那个「放大提示」**：
    // 它不影响列表跳转（跳转由 `onPick` 负责），两者刻意分开。
    var activeIdx by remember { mutableStateOf(-1) }
    Column(
        // ★ 2026-10-06 **第三轮**：不再贴屏幕右缘，而是在「**卡片右缘 ↔ 屏幕右缘**」
        //   那段带宽里**居中**（用户原话：「让索引条从……卡片内容的右侧边缘到屏幕
        //   右侧边缘的距离，居中一下」）⇒ 右退 [INDEX_BAR_INSET]。
        //   ⚠️ padding 必须加在调用方 `modifier` **之后**：调用方那层是 `align(CenterEnd)`
        //   （负责「贴右」），这一层负责「从右边退回半个余量」。
        modifier = modifier.padding(end = INDEX_BAR_INSET),
        verticalArrangement = Arrangement.Center,
        // 右对齐：字母条与放大提示**共用同一条竖线**（居中后的那条，不再是屏幕边缘）。
        horizontalAlignment = Alignment.End,
    ) {
        // ---------------------------------------------- 放大提示的槽位（**在条上方**）
        //
        // ⚠️ 为什么是"槽位"而不是"浮层"：它**常驻占位**（不按住时也占着这块高度），
        //   于是按住 / 松手时字母条**一点都不动** —— 一个会随手指上下跳的提示，
        //   比没有提示更让人分心。
        // ⚠️ 用 `heightIn(min = …)` 而不是 `height`：系统字体调大时让槽位自己长，
        //   宁可字母条被顶下去几 dp，也不要大字被裁掉。
        Box(
            modifier = Modifier.heightIn(min = INDEX_HINT_SLOT_H),
            contentAlignment = Alignment.BottomEnd,
        ) {
            // ⛔ 不要给它挂 `pointerInput` / `clickable`：它悬在列表上方，
            //   一旦吃掉事件，指尖滑到它上面就会把字母条的拖动打断。
            if (activeIdx in letters.indices) {
                Text(
                    text = letters[activeIdx],
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = INDEX_HINT_SP,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        // ★ 与下方字母条之间**留一道缝**（用户 2026-10-06 第二轮反馈
                        //   「**大字强调和下方的索引条贴在一起**」）。
                        //   ⚠️ 它必须放在 `background` **之前** —— 放在之后等于
                        //   把缝垫在**半透明底内部**，看上去仍旧是贴着的。
                        .padding(bottom = INDEX_HINT_GAP_V)
                        .background(
                            color = indexSurface(),
                            shape = RoundedCornerShape(INDEX_CORNER),
                        )
                        .padding(
                            horizontal = INDEX_HINT_PAD_H,
                            vertical = INDEX_HINT_PAD_V,
                        ),
                )
            }
        }

        // ---------------------------------------------- 字母条
        Column(
            modifier = Modifier
                // ★ 2026-10-06 **第三轮**：用户点名「**去掉背景色**」⇒ 这里不再画底。
                //   不再靠一层半透明底去「盖住」内容，改由两件事保证不打架：
                //   ① 条在带宽里居中（[INDEX_BAR_INSET]）② 卡片主动让距（[INDEX_BAR_RESERVE]）。
                //   ⚠️ **大字提示仍然有底**（[indexSurface]）—— 它是压在列表上的
                //   浮层读数，没底就读不清；条本身只是小字，不需要。
                // ⚠️ 内边距必须在 `pointerInput` **之前**：触控区若含内边距，
                //   而 `hit()` 是按 `y / 格高` 算下标的 ⇒ 整条会整体错几 dp。
                .padding(horizontal = INDEX_BAR_PAD_H, vertical = INDEX_BAR_PAD_V)
                .pointerInput(letters) {
                    // `this` 就是 PointerInputScope（同时是 Density）⇒ 直接换算 dp→px。
                    val cell = INDEX_CELL_H.toPx()
                    // 记着上一次选中的下标：滑动过程中手指一直压在同一格里，
                    // 不该每收到一个 move 事件就重复回调一次（那会让列表反复重定位）。
                    var last = -1
                    fun hit(y: Float) {
                        val i = (y / cell).toInt().coerceIn(0, letters.lastIndex)
                        // ⚠️ `activeIdx` 每一格都写（提示要显示当前字母），
                        //   而 `onPick` 只在**跨格**时才回调（列表不必反复重定位）。
                        activeIdx = i
                        if (i != last) {
                            last = i
                            onPick(letters[i])
                        }
                    }
                    awaitEachGesture {
                        // 每一次「按下 → 抬起」是一轮，轮开头重置去重状态。
                        last = -1
                        val down = awaitFirstDown(requireUnconsumed = false)
                        hit(down.position.y)
                        down.consume()
                        // 一直跟到抬起或这根手指被系统拿走（多指 / 被上层拦截）。
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            hit(ch.position.y)
                            ch.consume()
                        }
                        // ★ 松手就收起放大提示（它表达的是"此刻指着谁"）。
                        activeIdx = -1
                    }
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { ch ->
                Box(
                    modifier = Modifier
                        .size(INDEX_CELL_W, INDEX_CELL_H),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ch,
                        style = MiuixTheme.textStyles.footnote2,
                        // ★ 用主题的 primary（HyperOS 的索引条就是主题色），⛔ 别写死颜色：
                        //   深浅色模式各有一套正确值（规范【二、⑥】）。
                        color = MiuixTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ---------------------------------------------- 下方等高占位
        //
        // ⚠️ 上面那块提示槽位是"凭空多出来的高度"，若不在下方补一块等高的，
        //   字母条整体会被顶下去半个槽高（垂直中心跑到"槽位 + 条 + 占位"的中心去了）。
        //   补上之后，字母条本身仍然落在调用方给的垂直中心上。
        Spacer(Modifier.height(INDEX_HINT_SLOT_H))
    }
}

/**
 * **放大提示**那层底：主题色 + 半透明。
 *
 * ⚠️ 2026-10-06 **第三轮**起**只有大字提示用它**：用户点名「去掉背景色」，
 *   去掉的是**字母条**那层底（条现在没有底）。提示**不能跟着去** ——
 *   它是压在列表上的浮层读数，没底就读不清。
 * ⛔ 不要用写死的 `Color.White` / `Color.Black.copy(alpha = …)`：深浅色模式各有一套
 *   正确值，写死会在其中一种模式里看不清（规范【二、⑥】）。
 */
@Composable
private fun indexSurface() = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = INDEX_BG_ALPHA)

/** 索引条一格触控区的宽 / 高（尺寸理由见 [AlphabetIndex] 的 KDoc） */
private val INDEX_CELL_W = 22.dp
private val INDEX_CELL_H = 16.dp

/**
 * 「放大提示」的尺寸：字号 / 槽位高度 / 槽内边距。
 *
 * ⚠️ [INDEX_HINT_SLOT_H] ≈ 大字行高（30sp ≈ 36dp）＋ 上下内边距，取个整数。
 *   它既是槽位的**最小**高度，也是条下方那块等高 `Spacer` 的高度（理由见 [AlphabetIndex]）。
 *   ⛔ 别为了"排得刚好"把它调得比大字还矮 —— 那样大字会被裁掉。
 */
private val INDEX_HINT_SP = 30.sp
private val INDEX_HINT_SLOT_H = 60.dp
private val INDEX_HINT_PAD_H = 7.dp
private val INDEX_HINT_PAD_V = 5.dp

/**
 * 大字提示与**下方字母条**之间的缝（用户 2026-10-06 第二轮反馈「贴在一起」）。
 *
 * ⚠️ 之所以要这条缝：两者**共用同一层底**（[indexSurface]），不留缝时它们的底色
 *   连成一片，看着像「一条被劈成两段的东西」；留一道缝才读得出
 *   「上面是提示、下面是字母条」。
 * ⛔ 别把它写成负 margin / 反过来缩槽位 —— [INDEX_HINT_SLOT_H] 已经跟着
 *   本值一起放过（54 → 60dp），槽位够高就不必从别处抠。
 */
private val INDEX_HINT_GAP_V = 6.dp

/**
 * 索引条 / 提示那层底的属性：内边距 + 半透明度。
 *
 * ⚠️ [INDEX_BG_ALPHA] 是"半透明"的**程度**：太低（< 0.6）时字母会与底下的应用名
 *   糊在一起，太高（1.0）就完全盖住列表 —— 0.85 是"看得清字、又仍感得到下面有内容"的取值。
 */
private val INDEX_BAR_PAD_H = 3.dp
private val INDEX_BAR_PAD_V = 4.dp
private const val INDEX_BG_ALPHA = 0.85f

/**
 * 索引条 / 提示那层底的**圆角半径**。
 *
 * ⚠️ 一开始用的是 `RoundedCornerShape(percent = 50)`（胶囊形）。当时那条底只有
 *   [INDEX_CELL_W] 宽，50% 就是**两个半圆头** —— 用户 2026-10-06 反馈
 *   「**索引条的圆角太大了**」。改成固定小圆角后，既不再「胖」，
 *   也仍然与列表卡片的圆角语言接近。
 *   ⛔ 别再用 `percent`：它的值随尺寸变，条一改宽就又变成胶囊了。
 */
private val INDEX_CORNER = 8.dp

/**
 * 索引条在列表里**占掉的那点右边距** —— 调用方并进 `LazySubPage` 的 `contentPaddingEnd`。
 *
 * ★ 为什么要有它：索引条是**浮**在列表上的（不挤窄列表，见 [LazySubPage]）。只画几个
 *   主题色小字时"压住右边一点留白"看不出来；**加了半透明底之后**，那层底就压在卡片的
 *   右侧（开关 / 箭头那一带）⇒ 用户 2026-10-06 反馈「**加了背景色之后会遮挡内容卡片，
 *   内容卡片右边留点边距**」。⇒ 由卡片主动让开这一条。
 * ★ 取值推导（密度 400 ⇒ 2.5 px/dp，已用像素实测）：
 *   卡片右内边距 = [PAGE_H_PADDING] + 本值 = 12 + 26 = **38dp**；
 *   条本身宽 = [INDEX_CELL_W] + 2 × [INDEX_BAR_PAD_H] = **28dp**
 *   ★ 第三轮起条**不再贴屏幕右缘**，而是在这 38dp 里居中（见 [INDEX_BAR_INSET]）
 *   ⇒ 两侧各留 (38 − 28) / 2 = **5dp**。
 *   ⚠️ 2026-10-06 用户**二轮**反馈「**边距太大了**」：上一版 36dp（间隙 20dp），
 *     卡片左右严重不对称（左 12 / 右 48）⇒ 收到本值。**再小就会贴到条上。**
 *   ⛔ 不能写成 `INDEX_CELL_W + INDEX_BAR_PAD_H * 2 + …`：Kotlin 顶层属性按
 *     **文件声明顺序**初始化，而那几个常量声明在**后面** ⇒ 那样读到的是 `0.dp`，
 *     表现为"完全没留边距"，且很难查。
 */
internal val INDEX_BAR_RESERVE = 26.dp

/**
 * 字母条**从屏幕右缘往左退**的距离 —— 让它在那段带宽里**居中**。
 *
 * ★ 2026-10-06 **第三轮**用户原话：「索引条**太贴边了**，让索引条从（现在）卡片
 *   内容的右侧边缘到屏幕右侧边缘的距离，**居中一下**」。
 * ★ 那段带宽 = 卡片右内边距 = [PAGE_H_PADDING] + [INDEX_BAR_RESERVE] = 38dp；
 *   条宽 [INDEX_CELL_W] + 2 × [INDEX_BAR_PAD_H] = 28dp ⇒ 两侧余量
 *   (38 − 28) / 2 = **5dp**，各退 5dp 即居中。
 * ★ 这是**本文件唯一允许写成公式**的尺寸常量：它声明在**最末**，上面几个依赖
 *   （[PAGE_H_PADDING] / [INDEX_CELL_W] / [INDEX_BAR_PAD_H] / [INDEX_BAR_RESERVE]）
 *   都已经初始化过。⚠️ **一往上挪就会读到 `0.dp`** ⇒ 别挪。
 */
internal val INDEX_BAR_INSET =
    ((PAGE_H_PADDING + INDEX_BAR_RESERVE) - (INDEX_CELL_W + INDEX_BAR_PAD_H * 2)) / 2

// ================================================================ 文案与格式化
//
// ⚠️ 下面这些函数**都接 `Context`**（理由见文件头）。它们不是 Composable —— 可以在
//   字符串模板、`StringBuilder.append`、`when` 条件里自由使用。

/** 诊断读数的一行：`键：值`。⚠️ 冒号本身也是资源（中文全角、英文半角加空格） */
@Composable
internal fun kv(k: String, v: String) {
    Text(
        text = stringResource(R.string.kv_format, k, v),
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 15.sp,
    )
}

/** 说明性正文（统一用次级色 + 段落样式，避免各页面各写一套字号） */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.paragraph,
        modifier = modifier,
    )
}

/**
 * 弹一句提示 —— **只是 [AppToast] 的一个薄壳**，保留是为了不动已存在的 5 个调用点。
 *
 * ★ 真正的实现（进程差异 / 线程 / 去重 / 失败纪律）全在 [AppToast] ——
 *   ⛔ **别在这里再写一份 `Toast.makeText`**，那会绕过 [AppToast] 的去重表。
 * ⚠️ 新代码**直接用 [AppToast]** 更清楚（这个短名字在这个文件里才自然）。
 */
internal fun toast(ctx: Context, msg: String) = AppToast.show(ctx, msg)

/**
 * 浮点数格式化。
 *
 * ⚠️ 这里**刻意锁 `Locale.US`**：这一格的读者是排查问题的人（诊断页原始读数），
 *   而本地化的小数点（德语/法语用逗号）会让"12,3"这种人读起来像千分位。
 *   它**不参与**多语言 —— 数字在哪个语言下都是这一份。
 */
internal fun fmt(v: Float) =
    if (v.isFinite()) String.format(java.util.Locale.US, "%.1f", v) else "—"

/**
 * 「多轮识别」一行文案。
 *
 * ★ 为什么显示票面而不是只显示结论：单帧判错时用户只看到"方向不对"，看不出为什么。
 *   票面（如 `3:9`）能直接说明"本轮 12 次识别里 9 次都判给了方向 3"。
 *   票不够时**如实写"样本太少"**，不假装定论。
 *
 * ⚠️ 措辞面向普通用户（2026-09-30 全量梳理）：原来写的是"票面 / 票不足 / 状态机"，
 *   那是我们内部的投票机制黑话，用户看不懂。现在统一说"识别"。
 */
internal fun voteLine(
    ctx: Context,
    counts: String,
    winner: Int,
    valid: Int,
    confident: Boolean,
): String = when {
    counts.isEmpty() -> ctx.getString(R.string.vote_none)
    winner < 0 -> ctx.getString(R.string.vote_no_consensus, counts)
    confident -> ctx.getString(R.string.vote_winner, counts, rotName(ctx, winner), valid)
    else -> ctx.getString(R.string.vote_few, counts, valid)
}

/**
 * 「方向基准校验」一行文案 —— 用手机摆放方向当尺子，给人脸识别的方向基准作证。
 *
 * ⚠️ 原文案是"符号位 / 重力参照 / 凑够 20 帧即自动翻转符号位"，全是内部机制用语（2026-09-30 改）。
 *   现在只说用户能理解的三件事：**在校验 / 校验通过 / 可能反了会自动纠正**。
 */
internal fun signLine(
    ctx: Context,
    gravitySector: Int,
    same: Int,
    flip: Int,
    confirmed: Boolean,
): String = when {
    gravitySector < 0 -> ctx.getString(R.string.sign_unknown)
    confirmed -> ctx.getString(R.string.sign_ok, same)
    same + flip == 0 -> ctx.getString(R.string.sign_accum, rotName(ctx, gravitySector))
    flip >= same -> ctx.getString(R.string.sign_flip, flip, same)
    else -> ctx.getString(R.string.sign_checking, same, flip)
}

/** 秒 → 人看的时长。负数按"—"处理，不编造。 */
internal fun fmtDur(ctx: Context, sec: Int): String = when {
    sec < 0 -> "—"
    sec < 60 -> ctx.getString(R.string.dur_sec, sec)
    sec < 3600 -> ctx.getString(R.string.dur_min_sec, sec / 60, sec % 60)
    else -> ctx.getString(R.string.dur_hr_min, sec / 3600, (sec % 3600) / 60)
}

/**
 * 方向的名字。
 *
 * ⚠️ 说"手机"而不是"设备"（2026-09-30）：用户手上拿的是手机，不是"设备"。
 *
 * ★★ 方位修饰改成「摄像头朝左/右」（2026-10-03，用户连提两轮"看不懂 / 太开发者"）：
 *   原来写的是"手机**逆时针**转 90°" —— 那描述的是**转动过程**，而用户转手机时屏幕内容
 *   恰恰是朝**反**方向转的，一句话就能让人掰错边。现在改成**结果姿态**「摄像头朝左」：
 *   摄像头是他眼睛能直接看到的东西，零空间映射。
 *   ⛔ 别改回"逆时针/顺时针"（过程，且方向感与屏幕相反）；
 *   ⛔ 也别写"顶部朝左"（"顶部"要先想一下是哪一头，"摄像头"不用想）。
 *   ⚠️ `EngineLogic.kt` 里**另有一份同名函数**（日志用、不面向用户）—— **那份不动**：
 *     日志口径与人读口径本来就应该分开。（2026-10-03 前那句是 `AdaptiveEngine.kt`，
 *     它随 `rotName` 一起搬进 `EngineLogic.kt` 了。）
 *   ⚠️ 多语言后繁体版译作「相機在左／右」而不是「攝像頭」—— 台港说"相機"。
 */
internal fun rotName(ctx: Context, rot: Int) = when (rot) {
    0 -> ctx.getString(R.string.rot_0)
    1 -> ctx.getString(R.string.rot_1)
    2 -> ctx.getString(R.string.rot_2)
    3 -> ctx.getString(R.string.rot_3)
    else -> ctx.getString(R.string.rot_none)
}

/**
 * 每一档模式的说明（**用户可见**，界面上会出现两遍 —— 内屏一组、外屏一组，所以只有一处维护）。
 *
 * ⚠️ 措辞面向普通用户（2026-09-30 全量梳理）：
 *   "完全不介入" / "前摄" / "设备" 这类说法对用户都是噪音，换成直白的主谓宾。
 * ★ 刻意压到一句话：写两遍会把一屏撑得很长。
 */
internal fun modeSummary(ctx: Context, m: RotateMode): String = when (m) {
    // ⚠️ 2026-10-02 精简："屏幕上的一切完全跟随" → "完全跟随"（前者七个字全是同一个意思）。
    RotateMode.SYSTEM -> ctx.getString(R.string.mode_summary_system)
    RotateMode.ADAPTIVE -> ctx.getString(R.string.mode_summary_adaptive)
    RotateMode.SEMI -> ctx.getString(R.string.mode_summary_semi)
}

/**
 * 引擎回传的 `hs.mode` 是**枚举名**（`SYSTEM` / `ADAPTIVE` / `SEMI`），
 * 直接显示等于把内部代号摆在用户面前。这里换成名字；认不出来就原样显示（不猜）。
 *
 * ⚠️ 2026-10-03：真身在 `AppPrefs.RotateMode.labelRes` 里（那里也只能放资源 id，
 *   因为 enum 没有 Context），这里只是把 id 解析成当前语言的文字。
 */
internal fun modeName(ctx: Context, raw: String): String =
    runCatching { ctx.getString(RotateMode.valueOf(raw).labelRes) }.getOrDefault(raw)

/**
 * 半自动模式的「当前状态」文案。
 *
 * ★ 必须把"按钮弹不出来"单独拎出来说（`!overlayOk && shown > 0`）：
 *   那是半自动唯一会**静默失效**的方式 —— 服务觉得一切正常（判定、冷却、日志都在跑），
 *   用户却什么都看不到。不显式提示的话，用户只会得出"半自动是坏的"这个结论。
 *
 * ★ 同理必须把"**按钮层级降级了**"说出来（`overlayType == 2038`）：
 *   提层级（状态栏子面板，层号 181000）可能被 ROM 拒，拒了就自动退回普通悬浮窗（111000）。
 *   降级后按钮**照样弹得出来、位置也对**，唯一症状是转到位之后**角上被状态栏压住一块**。
 *
 * ⚠️ 措辞面向普通用户（2026-09-30 全量梳理）：
 *   原文案里的"LSPosed 注入 SystemUI""系统覆盖层被拒""按钮层级偏低"是给开发者看的。
 *   对用户只需说清**现象 + 该去哪检查什么**。
 *
 * ⚠️⚠️ 2026-10-04 更正 `semi_no_overlay` 的**动作**（原来给的是个没用的动作）：
 *   它曾写「去系统设置给本应用「显示在其他应用上层」权限」。那条动作属于
 *   **已被整个删除的"单机模式"**（引擎跑在 App 进程那套，见
 *   `docs/半自动模式与稳定性优化_2026-09-28.md` §五：托管模式"悬浮窗走 system uid 豁免、
 *   **不需要**任何授权"）。现行形态下本 App 甚至**不再声明** `SYSTEM_ALERT_WINDOW`
 *   （清单里有专门一段写"刻意不声明"），也没有任何授权入口 ⇒ 用户照着字面去找会找不到，
 *   就算找到、给了也没用。
 *   ★ 现在这个分支的实况见引擎侧 `AdaptiveEngine.onSemiTriggered`：两档窗口类型
 *     （状态栏子面板 → 普通悬浮窗）**都被 WindowManager 拒了**，属于真故障 ——
 *     引擎注释里也早写着"提示语不再只怪缺少悬浮窗权限"。
 *   ⇒ 所以现在的写法是"如实说现象 + 指向「诊断」页"，不再给假动作。
 *     ⛔ 别把那条授权提示改回来。
 *   ⚠️ `semi_low_layer`（层级降级那条）**没动** —— 它描述的是引擎侧的
 *     `overlayType == 2038` 降级，与上面这条是两件事，别一起删。
 */
internal fun semiStatusText(
    ctx: Context,
    shown: Int,
    tapped: Int,
    overlayOk: Boolean,
    overlayType: Int,
): String = when {
    shown > 0 && !overlayOk ->
        // ⚠️ 2026-10-02 曾把这里压成"一个动作句"，而那个动作是错的（授权属于已删掉的
        //   单机模式）⇒ 2026-10-04 换成"现象 + 去哪看"，理由见上面的 KDoc。
        ctx.getString(R.string.semi_no_overlay)
    shown > 0 && overlayType == APP_OVERLAY_TYPE ->
        ctx.getString(R.string.semi_low_layer, shown, tapped)
    shown == 0 -> ctx.getString(R.string.semi_idle)
    else -> ctx.getString(R.string.semi_counts, shown, tapped)
}

/** `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` —— 只用于上面那条降级提示的判断 */
internal const val APP_OVERLAY_TYPE = 2038

/**
 * 结构化失败计数（[ModuleLink.State.errs]，形如 `bind:3,rotw:1`）→ 人话。
 *
 * ★ 为什么值得占诊断页一行：引擎的失败路径**一律吞异常**（它跑在 SystemUI 里，
 *   未捕获异常 = 状态栏崩），所以"出了错但什么都没发生"是它的常态。
 *   这一格是唯一能把那种失败**摆到界面上**的地方 —— 它跟着状态串走，不会被日志缓冲冲掉。
 *
 * ⚠️ 认不出来的键**原样显示**、不猜：键名是跨进程契约，将来引擎加了新键而界面还没跟上的话，
 *   "原样显示"至少能把线索交到人手里，"猜一个中文名"则可能把排查引向错的方向。
 */
internal fun errsText(ctx: Context, raw: String): String {
    if (raw.isBlank()) return ctx.getString(R.string.errs_none)
    return raw.split(',').filter { it.isNotBlank() }.joinToString(ctx.getString(R.string.errs_join)) { part ->
        val k = part.substringBefore(':')
        val n = part.substringAfter(':', "").trim()
        when (k) {
            "bind" -> ctx.getString(R.string.errs_bind, n)
            "rotw" -> ctx.getString(R.string.errs_rotw, n)
            "rotr" -> ctx.getString(R.string.errs_rotr, n)
            "fg" -> ctx.getString(R.string.errs_fg, n)
            else -> ctx.getString(R.string.errs_other, k, n)
        }
    }
}

/**
 * `takeover = false` 的**人话解释**；`null` = 已接管（没什么要解释的）。
 *
 * ★ 为什么必须分开判，不能一律写成"可能缺「修改系统设置」授权"（2026-09-29 修）：
 *   `takeover=false` 有**两个完全不同的成因**，而且其中一个**根本不是故障** ——
 *   ① **前台应用不归本引擎管**（[ModuleLink.State.foregroundGated]）：引擎是**按设计**停手的，
 *      方向盘留在系统手上、`ACCELEROMETER_ROTATION` 被交还（见 `ForegroundGate`）。
 *   ② **真的缺 WRITE_SETTINGS**（`writeGranted == false`）—— 这一支的读数来自
 *      **引擎侧** `Settings.System.canWrite(context)`，`context` 是 **SystemUI** 的；
 *      对 SystemUI 而言 `WRITE_SETTINGS` 是 SYSTEM_FIXED 授予 ⇒ **正常情况下恒为 true**。
 *
 *   ⚠️⚠️ 2026-10-04 更正 ② 的**动作**：它原来写「到「旋转增强」页点一下授权」——
 *     那个按钮**不存在**：本 App 的清单里刻意**没有** `WRITE_SETTINGS`
 *     （见 `AndroidManifest.xml` 里"这里刻意不声明 WRITE_SETTINGS / SYSTEM_ALERT_WINDOW /
 *     FOREGROUND_SERVICE"那一段），也没有任何授权入口；`MainActivity.openSystemSettings()`
 *     只打开系统设置**首页**（那是给"注视感知"那条提醒用的）。
 *     ⇒ 让用户去找一个不存在的按钮，比不说更糟。现在改成如实说"本应用代不了 + 去诊断页"。
 *     ⛔ 别把"点一下授权"改回来。
 *   ✅ 2026-10-04：引擎侧那两个没有调用方的旧入口（`retryTakeover` / `refreshWriteSettingsGrant`）
 *     已随本轮死代码清理删除 —— 它们的 UI 早就随"单机模式"一起没了。
 *
 *   ⚠️ 实测踩到的误报：状态串 `takeover=0|fg=1|fgstop=WHITELIST|fgpkg=cn.dsr213.hyperplus|grant=1`
 *      —— 本应用自己**恒豁免**，所以「设置」页在最前台时引擎本来就该停手；
 *      而旧文案报的是"可能缺授权"，用户照着去点授权**不会有任何变化**。
 *      判据就是 `grant=1`：授权明明是有的，那这条解释必然错了。
 *
 * ⚠️ 判据用 `foregroundGated` 而不是"看包名"：包名在不在名单里是引擎用**它手上那份生效集合**
 *   算的（含没装的默认项、含恒豁免的自己），App 侧重推一遍必然分歧 —— 见 [AppWhitelist]。
 */
internal fun takeoverNote(ctx: Context, hs: ModuleLink.State): String? = when {
    hs.takeover -> null
    hs.foregroundGated -> ctx.getString(
        R.string.takeover_gated,
        hs.foregroundPkg.ifBlank { ctx.getString(R.string.takeover_gated_current) },
        stopReasonText(ctx, hs.foregroundStop),
    )
    !hs.writeGranted -> ctx.getString(R.string.takeover_no_grant)
    // ★ 兜底**不编原因**：状态摘要里没有能解释的字段时，宁可说"我不知道，去看诊断"
    else -> ctx.getString(R.string.takeover_unknown)
}

/**
 * 不控制屏幕方向的原因（[ModuleLink.State.foregroundStop] 的枚举名 → 人话）。
 *
 * ⚠️ 认不出来时**不说具体原因**，只说"不在本应用的控制范围内"（说错了会把用户
 *   引到错的排查方向上去）。现在能认出来的只有一种：
 *   `WHITELIST` = 用户自己设的偏好（界面上的开关就是它）。
 * ⛔ 删过的取值别再补回来：2026-09-29 的 `OUTER_DESKTOP` / `OUTER_DECLARED`
 *   （外屏旋转增强整体删除），2026-10-05 的 `UNCONTROLLABLE`（实测转不动 ⇒
 *   改成弹一次提示、不再停手）。
 *
 * ⚠️ 多语言注意：这几句是**接在应用名后面**的半句（见 [takeoverNote]），
 *   英文版必须以小写动词开头（"is on your exempt list"），否则会拼出 "AppName Is on…"。
 */
internal fun stopReasonText(ctx: Context, raw: String): String = when (raw) {
    "WHITELIST" -> ctx.getString(R.string.stop_whitelist)
    else -> ctx.getString(R.string.stop_other)
}

/**
 * 运行阶段（`hs.phase` 的枚举名 → 人话）。
 *
 * ⚠️ 枚举名（`ready` / `stopped` / `failed` …）直接显示等于把内部代号摆给用户看
 *   （2026-09-30 全量梳理时补的）。认不出来就原样显示，不猜。
 */
internal fun phaseText(ctx: Context, raw: String): String = when (raw) {
    "ready" -> ctx.getString(R.string.phase_ready)
    "starting" -> ctx.getString(R.string.phase_starting)
    "stopped" -> ctx.getString(R.string.phase_stopped)
    "failed" -> ctx.getString(R.string.phase_failed)
    // ★ 2026-10-03 新增：启动熔断（连续失败到阈值后不再自动启动）。
    //   ⚠️ 它是**唯一**一个"需要用户动手才能恢复"的取值 —— 所以「当前状态」页
    //     会为它单开一个分支（带「重新启用」按钮），不能只靠这一句词。
    "halted" -> ctx.getString(R.string.phase_halted)
    else -> raw
}

/**
 * 把运行状态摊成几行**可读的**说明 —— 只讲能确证的事，不编。
 *
 * ⚠️ 托管模式下没有逐条事件流（事件留在系统界面进程里），所以这里拼的是摘要。
 *
 * ⚠️ 全量梳理（2026-09-30），逐行的改动理由：
 *   - `状态阶段：ready` → **枚举名不能直接给用户看**，走 [phaseText] 转名称；
 *   - `接管：…` → "接管"是我们的黑话，改成"屏幕方向控制"；
 *   - `状态机 ${hs.decider}` → **整段删掉**。`STABLE` / `SETTLING` 这类内部状态机名
 *     对用户没有任何可操作性，留在界面上只会让人以为出了什么问题；
 *   - `符号位校验` / `本轮投票` → 换成 [signLine] / [voteLine] 里那套人话；
 *   - `相机冲突：N 次（采集中被其他客户端抢走前摄）` → 说清是"被其他应用抢走前置摄像头"；
 *   - `宿主引擎已运行` → 用户不知道"宿主"是什么，去掉前缀；
 *   - `宿主心跳：… 秒前（在线/失联）` → "失联"是故障口吻，改成"无响应"。
 */
internal fun engineReadoutLines(ctx: Context, hs: ModuleLink.State?): List<String> {
    if (hs == null) return emptyList()
    val out = mutableListOf<String>()
    out += ctx.getString(R.string.ro_phase, phaseText(ctx, hs.phase))
    out += ctx.getString(R.string.ro_mode, modeName(ctx, hs.mode))
    // ★ 未接管时把**原因**一起写出来（2026-09-29）：单写"未接管"会把"按设计让位"和
    //   "缺权限"混成一件事 —— 那正是 [takeoverNote] 要分开的两种情形。
    out += ctx.getString(
        R.string.ro_takeover,
        takeoverNote(ctx, hs) ?: ctx.getString(R.string.ro_takeover_active),
    )
    out += ctx.getString(R.string.ro_rotation, rotName(ctx, hs.rotation))
    out += ctx.getString(
        R.string.ro_sign,
        signLine(ctx, hs.gravitySector, hs.signSame, hs.signFlip, hs.signConfirmed),
    )
    out += ctx.getString(
        R.string.ro_vote,
        voteLine(ctx, hs.voteCounts, hs.voteWinner, hs.voteValid, hs.voteConfident),
    )
    out += ctx.getString(R.string.ro_conflict, hs.conflictCount)
    out += ctx.getString(R.string.ro_display, hs.display)
    out += ctx.getString(R.string.ro_uptime, fmtDur(ctx, hs.uptimeSec))
    if (hs.heartbeatAgoSec >= 0) {
        out += ctx.getString(
            R.string.ro_heartbeat,
            hs.heartbeatAgoSec,
            ctx.getString(if (hs.hostAlive) R.string.ro_normal else R.string.ro_unresponsive),
        )
    }
    return out
}

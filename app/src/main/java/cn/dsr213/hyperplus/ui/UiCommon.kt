package cn.dsr213.hyperplus.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 取值 = 底栏高度（54dp，见 `LiquidGlassBar.BAR_HEIGHT`）+ 上下留白（8×2）+ 呼吸余量。
 * ⚠️ 底栏高度改小之后这里**必须跟着改小**：留多了页面底部会莫名其妙空一大块，
 *   留少了最后一行会被玻璃压住 —— 两边都不好，所以两个常量要一起看。
 */
internal val BOTTOM_BAR_RESERVE = 100.dp

/** 页面内容统一水平留白（与 hyperos 设置页一致：12dp） */
private val PAGE_H_PADDING = 12.dp

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
    insideMargin = PaddingValues(16.dp),
    content = content,
)

/**
 * 顶栏导航位里的**返回箭头**（HyperOS 形状 + RTL 镜像）。
 *
 * ★ 抽出来的理由：现在有 7 个二级页要用同一个箭头（[SubPage] 6 个 + 白名单页自己那一个），
 *   而它带两件容易漏的事 —— RTL 下 `scaleX = -1`、以及 `contentDescription`。
 *   抄 7 遍的下场是某一处忘了镜像（阿拉伯语下箭头指错方向），或者忘了无障碍描述。
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

internal fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

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
 */
internal fun semiStatusText(
    ctx: Context,
    shown: Int,
    tapped: Int,
    overlayOk: Boolean,
    overlayType: Int,
): String = when {
    shown > 0 && !overlayOk ->
        // ⚠️ 2026-10-02 精简：原文"系统的…权限可能没给，请到系统设置里允许本应用"是两个
        //   小句说一件事 ⇒ 合成一个动作句。**权限名一字不改**（用户得照着它去找）。
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
 *   ② **真的缺 WRITE_SETTINGS**（`writeGranted == false`）—— 这才是要引导用户去授权的那个。
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
 * ⚠️ 认不出来时**不猜具体是哪一条**，只说"不在本应用的控制范围内" ——
 *   这两个值里 `WHITELIST` 是用户自己设的偏好、`UNCONTROLLABLE` 是本应用实测出来的事实，
 *   说错了会把用户引到错的排查方向上去。
 *   （2026-09-29 删掉两条：`OUTER_DESKTOP` / `OUTER_DECLARED` —— 外屏旋转增强整个
 *   没了，"外屏专属豁免"就没有下游了。⛔ 别再补回来。）
 *
 * ⚠️ 多语言注意：这几句是**接在应用名后面**的半句（见 [takeoverNote]），
 *   英文版必须以小写动词开头（"is on your exempt list"），否则会拼出 "AppName Is on…"。
 */
internal fun stopReasonText(ctx: Context, raw: String): String = when (raw) {
    "WHITELIST" -> ctx.getString(R.string.stop_whitelist)
    "UNCONTROLLABLE" -> ctx.getString(R.string.stop_uncontrollable)
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

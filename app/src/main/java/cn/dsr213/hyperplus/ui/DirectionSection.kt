package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RootShell
import cn.dsr213.hyperplus.ScreenForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「方向」卡片 —— **只做一件事：决定展开后内屏朝哪边**。
 *
 * ============================================================================
 * ★★★ 2026-10-04（第二轮）：手动校准**整块删掉**，改由引擎全自动
 * ============================================================================
 *
 * 用户当天原话（第一句是上一轮，第二句是这一轮）：
 * > 「重新设计方向校准功能，用不明白，用户不会知道正确的竖屏方向是哪一边，
 * >   也看不懂手机横屏、摄像头朝左是什么姿势，要用最简单、最简洁的操作方法和步骤」
 * > 「**不要用摄像头来校准了，用重力传感器**」
 *
 * 第一轮我把它做成了"瞄准器"（虚线目标 + 实线实时，转手机对上即自动记录）。
 * 用户否了 —— 因为**病根不在"怎么表述姿势"，而在"这件事本就不该让用户做"**。
 *
 * ## 为什么可以删（不是偷懒，是能力已经具备）
 * 引擎里**早就有**一套「拿重力当尺子」的自动机制，而且**在实际跑**：
 *   · `AdaptiveEngine.noteSignEvidence()`（2314 行被调用）—— 每轮投票结束后，
 *     拿**重力扇区**校验当前符号位：等于重力 ⇒ 记一票 SAME，等于镜像 ⇒ 记一票 FLIPPED；
 *     攒够 `SIGN_MIN_SAMPLES = 20` 且反号证据 ≥ 3 倍，就**自动翻转 `sign` 并落盘**。
 *     这正是治「横屏方向反过来」那个根因的东西。
 *   · `OrientationFusion.gauge()` —— 落盘前的方向一致性闸（永远不朝下）。
 * ⇒ 也就是说：**用户一个按钮都不用点，引擎自己会越用越准。**
 *   手动两步校准是旧时代的残留，删掉它不损失任何能力。
 *
 * ## 删掉之后，`offsetDeg` 怎么办
 * 旧的手动校准 ① 标的是 `offsetDeg`（把"人脸正立"那一帧的 roll 归零）。
 * 删掉后它保持默认 `0`。**这是安全的**，有实证：
 *   · `OrientationFusion` 类注释记载，真机上 `calib_sign=1 / calib_offset=0.0`
 *     **正好等于代码默认值** ⇒ 说明这个偏移**从未被真正标定过**；
 *   · 方向是量化到 90° 扇区的，±45° 容差远大于任何合理的固定相位偏差。
 *
 * ## ⛔ 别再把它加回来
 * 若将来真出现"方向系统性偏 90°"，正确的做法是**查重力那一路**
 * （`OrientationFusion` 的判据、`sensor` 是否注册成功），
 * 而不是让用户举着手机对准屏幕摆两个姿势 —— 那条路已被用户明确否掉两次。
 *
 * ============================================================================
 * 它现在只有一张卡：展开后的方向（四选一，每项配手机图标）
 * ============================================================================
 *
 * ⚠️ 只做**内屏**：展开后用的是内屏，用户关心的也是它。外屏那块槽位在本模块里
 *   恒为"跟随系统"档、模块不介入 ⇒ 不给入口。写入硬写 [ScreenForm.INNER]。
 *
 * ⚠️ 写法：**App 借 root 直写槽位**（[RootShell]）—— 改的是系统自己的每屏方向记忆，
 *   一次 `settings put`，**成败当场读回可见**（与"校准发请求给引擎"是两条不同的路，
 *   后者已经随校准一起删掉了）。
 *
 * ⚠️ 文案纪律（沿用既有）：**只讲现象与后果、不写机制、不口语化**；
 *   ⛔ 里面不许出现 markdown 星号（真机会原样显示）。
 */
@Composable
internal fun DirectionSection() {
    val ctx = LocalContext.current

    // ★ 读的是**系统里的真实值**，不是本地记忆。`null` 有两层意思，靠 [slotLoaded] 分开：
    //   · 还没读回来（首帧）—— 不能说"不支持"，那会闪一下假话；
    //   · 真读不到（非 HyperOS / 键不存在）。
    var slot by remember { mutableStateOf<Int?>(null) }
    var slotLoaded by remember { mutableStateOf(false) }
    // ★ 只在**失败**时说话（成功时界面自己就变了，不需要多一句）。
    var slotFailed by remember { mutableStateOf(false) }
    // ★ 正在写：挡住连点。root 那条命令是异步的，几百毫秒内连点会起好几个 su。
    var slotBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ★ 每秒读一次：写完界面自己跟上（不必等用户重进这一页）。
    //   ⚠️ 放在 `LaunchedEffect(Unit)` 里 ⇒ 离开本页时协程自动取消，不会在后台空转。
    LaunchedEffect(Unit) {
        while (true) {
            slot = AppPrefs.readSlot(ScreenForm.INNER)
            slotLoaded = true
            delay(1000)
        }
    }

    SmallTitle(text = stringResource(R.string.direction_title))
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.direction_default_desc),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.paragraph,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        )
        // ★★ 小字标注「需要 root」：写 `Settings.System` 的非公开键，对普通应用是**权限上
        //   做不到**的事（实证：`WRITE_SETTINGS` / `WRITE_SECURE_SETTINGS` 都无效）。
        //   不写出来，用户只会看到"点了没反应"。
        //   ⚠️ 只在**真能选**时显示 —— 下面那句"系统不支持方向设置"说的是"键不存在"，
        //     与"权限不够"是两件事，叠在一起会自相矛盾。
        if (slotLoaded && slot != null) {
            Text(
                text = stringResource(R.string.direction_root_note),
                // 红字 + Medium：与 [OuterScreenBanner] 同一套强调写法。
                // ⚠️ 用 `colorScheme.error` 而不是写死 `#FF…`（深浅色各有一套值）。
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
            )
        }
        when {
            // 首帧：先不铺选项（避免闪一句"不支持"）
            !slotLoaded -> Unit

            slot == null -> Text(
                text = stringResource(R.string.direction_unsupported),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            )

            else -> UNFOLD_CHOICES.forEach { v ->
                DirectionOption(
                    value = v,
                    selected = slot == v,
                    enabled = !slotBusy,
                    onClick = {
                        if (!slotBusy) {
                            slotBusy = true
                            slotFailed = false
                            scope.launch {
                                val ok = RootShell.putSystemInt(
                                    PrefsBridge.KEY_USER_ROTATION_PREFIX + ScreenForm.INNER.storageKey,
                                    v,
                                )
                                // ★ 不管成没成，都**重新读回**再上屏 —— 显示的是事实。
                                slot = AppPrefs.readSlot(ScreenForm.INNER)
                                slotFailed = !ok
                                slotBusy = false
                            }
                        }
                    },
                )
            }
        }
        if (slotFailed) {
            Text(
                text = stringResource(R.string.direction_failed),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            )
        }
        // ⚠️ 末尾**不需要**补 Spacer：每个 [DirectionOption] 自带 9dp 上下内边距，
        //   四个选项叠起来卡片的底边已经留够了。⛔ 别再加一个凑数。
    }
}

/**
 * 可选的四个方向，**顺序刻意不是按值排**：两块横屏挨着（用户最常选的就是它们，
 * 一眼可比），竖屏开头、倒屏收尾。
 *
 * ⚠️ 值直接就是 `USER_ROTATION` 的取值，与 [rotName] **同一套编号、不需要任何换算**
 *   （见 `PanelOrientation` 的类注释）—— ⛔ 别在这里加 ±180 之类的偏置。
 * ⚠️ 2026-10-04：用户拍板**保留四选一**（备选是"只留两个横屏"），但要求**每项配图**。
 *   ⇒ 图标角度就是 `value * 90f`，与 [PhoneGlyph] 的坐标系一致。
 */
private val UNFOLD_CHOICES = listOf(0, 1, 3, 2)

/**
 * 一个方向选项：**手机图标 + 文字 + 选中标记**。
 *
 * ★ 为什么不用现成的 `RadioButtonPreference`：它的 `title` 只是个字符串，
 *   塞不进图标。而"配图"正是这一轮要解决的问题（用户看不懂"摄像头朝左"这个说法）。
 *
 * ⚠️ 整行可点（`clickable` 铺在 `Row` 上），所以点击热区比原来只有按钮大得多 ——
 *   这是刻意的：原来的小圆点对折叠屏单手操作太窄。
 */
@Composable
private fun DirectionOption(
    value: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val accent = MiuixTheme.colorScheme.primary
    val tint = if (selected) accent else MiuixTheme.colorScheme.onSurface
    val ring = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标角度 = 值 × 90°，摄像头圆点跟着落到对应那个角（实测校正过的落角表见 [PhoneGlyph]）：
        //     0 → 左上 ｜ 1 → 左下 ｜ 2 → 右下 ｜ 3 → 右上
        // ⚠️ 画布必须是**正方形**：机身是 √2:1，横过来时占宽 = `h`（= 边长 × 0.88）。
        //   旧值 `size(26.dp, 38.dp)` 在横屏两档上会把机身**左右各切掉一截**（画布太窄）。
        PhoneGlyph(
            deg = value * 90f,
            color = tint,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = rotName(ctx, value),
            color = tint,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        // 选中标记：外圈恒在、内点只在选中时出现（比整块底色更轻，不抢文字的视线）
        Canvas(modifier = Modifier.size(20.dp)) {
            val sw = 1.6.dp.toPx()
            val r = size.minDimension / 2f - sw / 2f
            drawCircle(color = ring, radius = r, center = center, style = Stroke(sw))
            if (selected) {
                drawCircle(color = accent, radius = r * 0.55f, center = center)
            }
        }
    }
}

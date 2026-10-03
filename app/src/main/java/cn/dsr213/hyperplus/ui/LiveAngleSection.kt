package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.LiveAngle
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.ScreenForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「方向校准」里的**实时角度**块（2026-10-01 新增）。
 *
 * ============================ 它是干什么的 ============================
 * 用户原话：「现在每次旋转的方向都是对的，不要再改变，但从外屏展开到内屏的默认方向不对，
 * **在方向校准里面加一个实时的角度显示，我告诉你正确方向**」。
 *
 * ⇒ 这一块只做一件事：**把引擎此刻看到的角度如实画出来**，让用户看着它说话。
 *   ⛔ 它**不参与任何判断**、不改标定、不改旋转的判定 —— 一个纯粹的读数窗口。
 *     ⚠️ 2026-10-02 更正：曾经它还会"顺带冻结屏幕方向"（引擎侧 `previewHold`），
 *     后果是**用户看到的是假现场**（屏幕冻在陈旧值上，而他以为那是即时结果）。
 *     现在预览只是"多跑几轮采帧"，方向照常按原来的路走。
 *
 * ============================ 0° 是哪一边（用户点名要求的那条）============================
 * **0° = 当前屏幕的默认方向** —— 也就是"手机竖屏正对自己、头摆正"那个姿态。
 * 从这个基准出发：手机逆时针转 90° ⇒ 90°，倒过来 ⇒ 180°，顺时针转 90° ⇒ 270°。
 * 与盘上四个标签的对应关系是 [rotName] 那四个方向。
 *
 * ============================ 生命期（谁开、谁关、兜底在哪）============================
 * | 事件 | 谁负责 |
 * |---|---|
 * | 进入本页 | 本组件 `DisposableEffect` 置 `anglePreview = true` |
 * | 离开本页 | `onDispose` 置 false |
 * | **留在本页** | **引擎按前台包名自动续期** ⇒ **只要这一页还在最前就不会停**（2026-10-02 补） |
 * | App 崩溃 / 按 Home / 被系统杀 | 引擎侧超时自停（`PREVIEW_MAX_MS`） |
 * | 引擎重启 | 引擎侧"首次只记账"（不照旧值开相机）+ `AppPrefs.init` 强制复位 |
 *
 * ⚠️ 那条「自动续期」是**踩出来的**：上一版引擎把"开一次只活 3 分钟"写死，而界面这边
 *   只会在进入这一页时置**一次** true —— 于是用户做"合上→展开→再合上"这套对照动作时，
 *   读数在中途就静默停了，他看到的其实是**上一次的陈旧值**（日志铁证：`停止读数（超过
 *   180 秒未收到界面续期）`）。见 `AdaptiveEngine.previewUntil`。
 *
 * ⚠️ 为什么不加一个手动开关：用户要的就是"打开这一页就能看"。多一个开关等于
 *   每次都要先点一下才看得见数字，而这一页本来就是个诊断页，没有误触风险。
 *   代价（占前置摄像头）在界面上如实写出来了。
 */
@Composable
internal fun LiveAngleSection() {
    val ctx = LocalContext.current

    // 宿主写下来的最新一行。⚠️ 用 `seen` 区分"还没读过"与"读到了但没脸"——
    //   两者在 `sample` 上都可能是 null/NaN，但给用户的话完全不同（见下面 when）。
    var sample by remember { mutableStateOf<LiveAngle.Sample?>(null) }

    // 进入本页 ⇒ 让引擎开始采；离开 ⇒ 停。
    // ⚠️ 这里**只依赖 onDispose**，不订阅 Activity 生命周期：用户按 Home 那种情况
    //   由引擎的超时兜底（见类注释的表格），比在这里多挂一个 LifecycleObserver 简单。
    DisposableEffect(Unit) {
        AppPrefs.setAnglePreview(true)
        onDispose { AppPrefs.setAnglePreview(false) }
    }

    // 轮询读数。⚠️ 必须切到 IO 线程：读 Settings 是一次跨进程 IPC，
    //   在组合线程上以 8Hz 去读会把这一页的滚动拖卡。
    LaunchedEffect(Unit) {
        while (true) {
            sample = withContext(Dispatchers.IO) { LiveAngle.read(ctx.contentResolver) }
            delay(POLL_MS)
        }
    }

    val s = sample
    val showAngle = s != null && s.fresh && s.hasFace

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AngleDial(
            normDeg = if (showAngle) s.normDeg else Float.NaN,
            sector = if (s != null && s.fresh) s.sector else -1,
            modifier = Modifier.size(132.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (showAngle) "${fmt(s.normDeg)}°" else "—",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 34.sp,
            )
            Note(
                // ★ 内屏那一格要说明"已按面板安装方向换算"—— 否则用户会以为读数算错了。
                //   本机内屏 `installOrientation=ROTATION_180`（外屏 0）⇒ 展开后读数基准要转半圈，
                //   详见 `AdaptiveEngine.panelPhase`。
                // ⚠️ 2026-10-02 精简：原来括号里写的是"已按内屏安装方向做 180° 换算"——
                //   那是**怎么算的**（实现）。用户只需知道"这个数字已经算好了"，
                //   "到底换算没换算"下面那行「原始读数」自己会对照出来。
                if (s?.form == "INNER") stringResource(R.string.live_zero_inner)
                else stringResource(R.string.live_zero_outer),
            )
            Spacer(Modifier.height(4.dp))
            when {
                // ① 还没开张：引擎最多 2 秒才察觉请求（配置通道是文件级的），别说成故障
                s == null -> Note(stringResource(R.string.live_starting))
                // ② 读数停了：**必须说清是"停"而不是"角度是 0"**，并给出重开的动作
                !s.fresh -> Note(stringResource(R.string.live_stopped, s.ageMs / 1000))
                // ③ 活着但没脸
                !s.hasFace -> Note(stringResource(R.string.live_no_face))
            }
            if (s != null && s.fresh) {
                kv(
                    stringResource(R.string.live_kv_sector),
                    if (s.sector < 0) stringResource(R.string.rot_none) else rotName(ctx, s.sector),
                )
                kv(
                    stringResource(R.string.live_kv_display),
                    if (s.display < 0) "—" else rotName(ctx, s.display),
                )
                kv(
                    stringResource(R.string.live_kv_form),
                    stringResource(
                        if (s.form == "OUTER") ScreenForm.OUTER.labelRes else ScreenForm.INNER.labelRes,
                    ),
                )
                // ⚠️ 标签里必须写清它**没做面板换算** —— 否则内屏上"大数字 0°、原始读数 180°"
                //   看着像 bug（其实一个是屏基准、一个是设备基准，见 `AdaptiveEngine.panelPhase`）。
                if (showAngle) {
                    kv(
                        stringResource(R.string.live_kv_raw),
                        stringResource(R.string.live_raw_suffix, fmt(s.rawRoll)),
                    )
                }
            }
        }
    }

    // ⚠️ 2026-10-02 精简：三句 → 两句。砍掉"不会因为看这一页被冻住"（**那是我们此前
    //   自己引入又撤掉的回归**，用户既不知道也不需要知道这段历史），只留"会占摄像头"
    //   与"多久停"这两件用户会碰到的。
    Note(stringResource(R.string.live_footer))
}

/** 读数轮询周期（8Hz）—— 比宿主的 4Hz 上报快一倍，界面不会比数据更迟钝 */
private const val POLL_MS = 125L

/**
 * 角度盘：**0° 在正上方**（= 屏幕默认方向），顺时针增大。
 *
 * ★ 指针画的是"**手机顶部现在朝向哪边**"而不是"人脸歪了多少" —— 这两件事在这个坐标系里
 *   是同一个量（用户要的 0° 基准就是"手机竖屏正对自己"），但"手机顶部朝向"这个说法
 *   一眼就能对着盘看明白：指针朝上 = 竖屏正持，朝左 = 手机逆时针转了 90°。
 *
 * ⚠️ 不画文字（四个方向标签由外面那层 [Box] 用 `Text` 摆）：Compose 的
 *   `drawText` 要 `TextMeasurer`，为四个固定标签引一整套文字测量没必要，
 *   而且 `Text` 还能自然跟随主题的字形与行高。
 */
@Composable
private fun AngleDial(normDeg: Float, sector: Int, modifier: Modifier) {
    val ring = MiuixTheme.colorScheme.dividerLine
    val needle = MiuixTheme.colorScheme.primary
    val sectorFill = MiuixTheme.colorScheme.primary
    val labelHi = MiuixTheme.colorScheme.onSurface
    val label = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // ⚠️ 四个标签也跟着语言走（2026-10-03）。中文是「竖/左横/倒/右横」两个字的短词，
        //   英文取 Up/Left/Down/Right —— 盘上那圈位置放不下更长的词（11sp、贴边）。
        val labels = listOf(
            stringResource(R.string.live_dial_0),
            stringResource(R.string.live_dial_1),
            stringResource(R.string.live_dial_2),
            stringResource(R.string.live_dial_3),
        )
        // 四个标签各自贴在盘的四个方向上：0 上 / 1 左 / 2 下 / 3 右。
        // ⚠️ 顺序不是"上右下左"：本坐标系里 1 = 手机**逆时针**转 90°（顶部朝左）⇒ 标签在左。
        labels.forEachIndexed { i, txt ->
            val pos = when (i) {
                0 -> Alignment.TopCenter
                1 -> Alignment.CenterStart
                2 -> Alignment.BottomCenter
                else -> Alignment.CenterEnd
            }
            Text(
                text = txt,
                color = if (i == sector) labelHi else label,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(pos),
            )
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(132.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // ⚠️ 半径要留够边距：四个方向标签是**盘外**那层 Box 里的 `Text`（见上），
            //   半径按 `-20dp` 算时"左横/右横"两个字的宽度会压到圆环上。
            val r = minOf(size.width, size.height) / 2f - 28.dp.toPx()
            if (r <= 8f) return@Canvas

            // 盘子那两圈
            drawCircle(ring, radius = r, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawCircle(ring, radius = r * 0.58f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

            // 当前判定方向所在的 90° 扇区高亮（只在有结论时）
            // ⚠️ 角度换算：Compose 的 `drawArc` 里 0° 指向 3 点钟并**顺时针**为正，
            //   而本坐标系 0° 指向 12 点 ⇒ 一律减 90。
            if (sector in 0..3) {
                drawArc(
                    color = sectorFill.copy(alpha = 0.14f),
                    startAngle = sector * 90f - 135f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                )
            }

            // 刻度：每 15° 一根，整 90° 的长一点
            for (i in 0 until 24) {
                val a = Math.toRadians((i * 15).toDouble())
                val long = i % 6 == 0
                val r1 = r - 2.dp.toPx()
                val r2 = r1 - if (long) 14.dp.toPx() else 6.dp.toPx()
                // 用与指针同一条换算（sin 取负 ⇒ 顺时针增大对应盘面上的逆时针绘制）
                drawLine(
                    color = ring,
                    start = Offset(cx - (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat()),
                    end = Offset(cx - (r2 * sin(a)).toFloat(), cy - (r2 * cos(a)).toFloat()),
                    strokeWidth = if (long) 2.dp.toPx() else 1.dp.toPx(),
                )
            }

            // 指针：手机顶部朝向。没脸 / 读数停了就不画（**不拿旧值硬撑**）
            if (normDeg.isFinite()) {
                val a = Math.toRadians(normDeg.toDouble())
                val tip = Offset(
                    cx - (r * 0.88f * sin(a)).toFloat(),
                    cy - (r * 0.88f * cos(a)).toFloat(),
                )
                drawLine(
                    color = needle,
                    start = Offset(cx, cy),
                    end = tip,
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(needle, radius = 6.dp.toPx(), center = Offset(cx, cy))
            }
        }
    }
}

package cn.dsr213.hyperplus

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 半自动模式的**旋转提示按钮** —— 屏幕右下角那个圆形悬浮图标。
 *
 * ============================ 它是干什么的（2026-09-28 用户点名要的）============================
 * 用户原话：「检测到旋转之后，在桌面右下角弹出一个旋转图标，持续 3 秒，用户点击之后旋转」。
 * 于是半自动模式 = **引擎照常判方向，但方向盘不直接打出去，而是先问一声**：
 * 弹一个按钮，**默认 3 秒**内点了就转（时长 1~60 秒可配，见 [durationMs]），
 * 不点就自己消失、保持原方向。
 *
 * ★ 它和自适应模式的关系：**判据不同、链路不同**（见 `AdaptiveEngine.semiTargetRotation`）。
 *   自适应走「开前摄 + 人脸投票」；半自动走「传感器姿态」，**一颗相机帧都不采**。
 *   所以这个按钮本身不依赖相机、也不抢任何资源。
 *
 * ============================ 为什么自己画，不是用布局 ============================
 * 1. 本 View 住在**系统界面进程**里（引擎的唯一驻地），那里的 Compose 主题与资源归属
 *    跟本应用不一样。自绘 = 零资源依赖、零 Compose 主题依赖
 *    （深浅色自己按 `Configuration.uiMode` 适配，见 [HintPalette]）。
 * 2. 倒计时环必须**逐帧**跟随剩余时间，Canvas 画 arc 是最直接的做法。
 * 3. 图标要**跟着目标方向转**（见 [HintView.targetRotation]），自绘才能精确控制旋转中心。
 *
 * ============================ 窗口（这里坑最多）============================
 * - 类型：**优先 `STATUS_BAR_SUB_PANEL`(2017)**（层号 181000 高于状态栏的 151000，
 *   否则按钮会被状态栏遮挡），拿不到 `INTERNAL_SYSTEM_WINDOW` 时退回
 *   `TYPE_APPLICATION_OVERLAY` —— 层号实测表与取舍理由见 [typeCandidates]。
 *   ⚠️ **别顺手换回 `TYPE_SYSTEM_OVERLAY`**：它层号更高（231000），但系统会强制给它
 *   `NOT_TOUCHABLE` ⇒ 按钮"弹得出来、点不动"（2026-09-28 真机踩过，详见 [typeCandidates]）。
 * - `FLAG_NOT_FOCUSABLE` 是**必须**的：它同时隐含 `FLAG_NOT_TOUCH_MODAL`，
 *   于是**窗口之外的触摸一律穿透** —— 否则这个按钮会变成挡在桌面右下角的一块死区。
 *
 *   ⚠️ **别把这句读成「这个按钮不挡触摸」**（2026-09-28 复核时发现原来的措辞会误导）：
 *   `NOT_TOUCH_MODAL` 管的只是窗口**矩形之外**；矩形**之内**是货真价实的死区 ——
 *   输入派发只找「含有该点的最上层可触摸窗口」，下面的应用**拿不到**这次触摸，
 *   **不存在**"这个窗口没处理就转给下一个"的兜底。
 *   ⇒ 弹出的那几秒里，右下角 54~62dp 的方块内，下方应用收不到任何触摸。
 *     ⚠️ 2026-09-29 起**窗口恒等于可见圆**（可见圆最小 54dp > 触摸下限 48dp ⇒
 *     `contentScale` 恒为 1，见 [HintSizePolicy.TOUCH_DP_MIN]），所以原先"看着是空的
 *     透明余量"那一圈**已经没有了** —— 整块方块在视觉上都是按钮。
 *     实测与取舍见 `docs/断触排查_2026-09-28.md` 第八节。
 * - **位置自己算**（`Gravity.TOP or Gravity.START` + `屏幕宽/高 - 尺寸 - 留白`）：
 *   不用 `Gravity.END | Gravity.BOTTOM` 配偏移量，因为偏移量在不同显示方向下
 *   的解释并不一致 —— 那正是"旋转之后按钮不在右下角"的成因，详见 [applyBottomEnd]。
 * - 刻意**不加** `FLAG_LAYOUT_NO_LIMITS`（那个 flag 会让窗口无视系统栏、也更容易被
 *   算出屏幕外的坐标）。
 *
 * ================== 材质：**自绘**液态玻璃（2026-09-29 二次修订）==================
 * 用户问「HyperOS 有没有开放高级材质 API（液态玻璃 / 磨砂玻璃），有的话把按钮改成系统样式」。
 * 查完的结论**分三层**，每一层都有实证：
 *
 * ① **HyperOS 私有的「高级材质」没有面向第三方的 SDK。**
 *    它是系统「显示与亮度」里的一个设置，管的是 SystemUI 自己（控制中心 / 通知栏 / 最近任务）。
 *    相关的私有 prop 本机确实存在且为真 —— `persist.sys.bionic_material_supported=true`、
 *    `persist.sys.gradient_blur_ddk=true` —— 但那是**系统内部开关，不是 API**。
 *
 * ② **标准 Android 的跨窗口模糊能用**（`api-versions.xml` 实证 `since=31`，本机
 *    `mBlurEnabled=true`、`global background_blur_enable=1`）。
 *    ⛔ **但它的语义不适合这里，已弃用** —— 用户当场纠正过，原话：
 *    > 「为什么弹出旋转按钮的时候，给屏幕加了模糊？我是要给按钮添加液态玻璃效果，
 *    >    **不是给整个屏幕添加效果**」
 *    机制上绕不过去：`FLAG_BLUR_BEHIND` + `blurBehindRadius` 的作用域是**窗口矩形**，
 *    干的事是"把窗口背后那一块**屏幕内容**整块糊掉"，玻璃的"透"是靠**糊掉别人**换来的。
 *    而按钮是圆的、窗口是方的 ⇒ 方角处露出来的就是一块被糊过的画面 ——
 *    观感就是"屏幕上贴了一坨模糊"，而不是"按钮有材质"。
 *    ⇒ 现在**模糊相关的东西一个都不设**（flag / 半径 / 探针全删，见 [layoutParams]）。
 *
 * ③ **"液态玻璃"的外观是三笔纯绘制**，不依赖模糊、不依赖任何 API 级别：
 *    半透明表面（[HintPalette.glassSurfaceAlpha]）
 *    + 上缘镜面高光（[HintPalette.specular] 做竖直渐变）
 *    + 一圈光晕描边（`edgePaint`，顶部亮、往下化开）。
 *    ⇒ 见 [HintView.onDraw] 的 ①~③ 步。**现在它是外观的唯一来源**。
 *
 * ⚠️ 顺带记一条**不做**的事：**不加外投影**。因为 2026-09-29 起窗口恒等于可见圆
 *   （见 [HintSizePolicy.TOUCH_DP_MIN] 与 [HintView.contentScale]），窗口外一个像素都没有，
 *   投影会被窗口边界硬裁成一圈直角 —— 比不加更难看。玻璃靠"描边 + 高光"立住边界就够了。
 *   ⚠️ 它与 ② 是同一类坑：**悬浮窗能画、能糊的范围就是它那个窗口矩形**，
 *      任何"超出窗口"的效果（投影、模糊晕开）都会被硬裁。
 */
/**
 * 按钮配色：**深浅两套**（2026-09-28 用户点名要适配深色模式）。
 *
 * ★ 为什么这不是"锦上添花"，而是**功能问题**：
 *   倒计时**轨道**是半透明黑（20% 黑）。深色模式下壁纸 / 应用底色本身就很暗，
 *   这层轨道会**直接看不见** —— 用户只看到那条蓝色剩余弧，感知不到"总共有几秒、还剩多少"，
 *   倒计时环退化成一个"不知道走到哪了"的装饰。同理白底按钮在暗背景上偏刺眼。
 *   ⇒ 整套跟着系统深浅色走：浅色 = 白底 + 深图标 + 半透明黑轨道；
 *     深色 = 深灰底 + 浅图标 + 半透明白轨道，环的蓝也提亮一级。
 *
 * ⚠️ 悬浮按钮浮在**任意背景**之上，"跟随系统"不等于"永远好看"（深色系统 + 浅色壁纸
 *   是可能的）。但这是唯一拿得到的信息 —— 拿不到底下的壁纸颜色，跟随系统是标准做法。
 *
 * ============================ 2026-09-29 追记：玻璃材质三项（用户点名要的）============================
 * 用户这一轮的要求是「查一下 HyperOS 是否有开放高级材质 API（液态玻璃），
 * 有的话把按钮改成系统样式」。查证结论（分三层，实证在 [RotateHintOverlay] 的类注释里）：
 *   - HyperOS 私有的「高级材质」**没有面向第三方的 SDK**；
 *   - 标准 Android 的**跨窗口模糊**能用，但它糊的是"窗口背后那整块屏幕"
 *     ⇒ **用户明确不要**（原话："我是要给按钮添加液态玻璃效果，不是给整个屏幕添加效果"）；
 *   - 官方设计语言里的"液态玻璃"外观 = **半透明表面 + 上缘镜面高光 + 一圈光晕描边**，
 *     这三样都是纯绘制，不依赖任何私有能力。
 * ⇒ 于是 [base] 那三个不透明度是"没有玻璃"时的值（够实，在花背景上读得清），
 *   而 [glassSurfaceAlpha] 把表面调薄、再用 [specular] 画高光。
 * ⚠️ [glassSurfaceAlpha] 现在要**独自**承担"透"这件事（背后那层模糊已经删了）——
 *   所以它比"有模糊兜底时可以取的值"略高一点：太薄的话按钮在花背景上会散。
 */
private class HintPalette(
    /** 这套是不是深色（用于在外层比较"换了没有"） */
    val night: Boolean,
    val base: Int,
    val basePressed: Int,
    val icon: Int,
    val ring: Int,
    val track: Int,
    /**
     * **玻璃模式**下表面的不透明度（0~1）。
     *
     * ★ 为什么必须比 [base] 低一大截：模糊要"透得出来"才有意义。表面太实的话，
     *   后面糊得再厉害也被盖住 —— 看起来就是一块不透明的膏药，白做一层模糊。
     * ⚠️ 也不能太低：按钮**只有图标没有文字**，表面太薄时箭头会和背后的花背景糊在一起。
     *   0.55 / 0.62 是在"透得出来"和"看得清"之间的取值，**真机截图核对过**，
     *   取值记录在 `docs/UI重构_液态玻璃_2026-09-29.md`。
     */
    val glassSurfaceAlpha: Float,
    /**
     * 上缘镜面高光的颜色（**含 alpha**）—— 液态玻璃最标志性的那一笔。
     *
     * ★ 为什么它能"一眼看出是玻璃"：真实玻璃的边缘会聚光，上缘必然比下缘亮。
     *   所以高光是一条**竖直渐变**：顶部用 [specular]，往下一段就化到全透明。
     *   浅色下用 37% 白（在白底按钮上是浅浅一层，不脏）；深色下用 28% 白
     *   （深底上白高光更显眼，压低一点免得发灰）。
     */
    val specular: Int,
) {
    companion object {
        val LIGHT = HintPalette(
            night = false,
            base = 0xF2FFFFFF.toInt(),
            basePressed = 0xFFE8F0FF.toInt(),
            icon = 0xFF2B2B2B.toInt(),
            ring = 0xFF3482FF.toInt(),
            // 20% 黑：白底上是一条能看清的浅灰轨
            track = 0x33000000,
            glassSurfaceAlpha = 0.55f,
            specular = 0x5EFFFFFF,
        )

        val DARK = HintPalette(
            night = true,
            // 同样 95% 不透明：跟着深色走，又不至于让底下内容透过来显得脏
            base = 0xF22C2C2E.toInt(),
            basePressed = 0xFF3A4252.toInt(),
            icon = 0xFFF2F2F7.toInt(),
            // 深底上的蓝要提亮 —— 原色 #3482FF 在暗背景里偏闷
            ring = 0xFF4C9AFF.toInt(),
            track = 0x33FFFFFF,
            // 深色玻璃要**更实**一点：暗色表面透光后容易和暗背景糊成一片，失去"这是一块悬浮物"的边界感
            glassSurfaceAlpha = 0.62f,
            specular = 0x47FFFFFF,
        )

        fun of(night: Boolean) = if (night) DARK else LIGHT
    }
}

/**
 * 换掉一个 ARGB 颜色的 **alpha**，保留它的 RGB。
 *
 * ★ 为什么不直接用 `Color.argb(a, r, g, b)`：配色表里那几支颜色是**带 alpha 的整数值**
 *   （如 `0xF2FFFFFF`），要改的只有 alpha。逐位拆再拼一遍不仅啰嗦，还容易把
 *   `0xF2FFFFFF` 这种"高位就是 alpha"的写法搞错 —— 而错法很隐蔽（颜色会变成黑色）。
 *
 * ⚠️ 用 `and 0x00FFFFFF` 清掉原 alpha（**不是** `0xFFFFFF`：后者是 24 位、会把
 *   最高位那个字节当数据留下来）。
 */
private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}

/**
 * 按钮内容的几何 —— 抽成纯函数，只为**让"同心"这件事可以单测**。
 *
 * ★ 为什么值得单独抽出来（2026-09-28）：这里曾有一个**只在外屏出现**的真错 ——
 *   倒计时环的圆心被按"可见圆边长的一半"算，而圆底与箭头按"窗口边长的一半"算。
 *   两者只在 `contentScale == 1`（= 内屏）时相等，于是**内屏看着完全正常、外屏偏一角**。
 *   这类"只在某一档参数下才暴露"的错，靠肉眼自测极难发现（开发时看的正是内屏），
 *   而它又纯属算术 —— 所以把算术挪出来，用单测钉住 `[ringCenter] 与窗口中心恒等`。
 *
 * ⚠️ 这一层**刻意不引任何 Android 类**（不引 RectF / View），才能进 JVM 单测。
 */
internal object HintRingGeometry {

    /** 窗口几何中心。**唯一的圆心来源**：圆底 / 图标 / 倒计时环全都必须用它 */
    fun viewCenter(viewSide: Float): Float = viewSide * 0.5f

    /**
     * 倒计时环的半径。
     *
     * @param viewSide 窗口边长（**不是**可见圆边长）
     * @param contentScale 可见圆占窗口的比例（见 `HintSize.contentScale`）
     * @param ringR 环半径占**可见圆**边长的比例（`RotateHintOverlay.RING_R`）
     */
    fun ringRadius(viewSide: Float, contentScale: Float, ringR: Float): Float =
        viewSide * contentScale * ringR
}

internal class RotateHintOverlay(
    context: Context,
    /** 用户点了按钮。参数是**目标方向**（`Surface.ROTATION_*`：0/1/2/3） */
    private val onTap: (Int) -> Unit,
) {

    private val appContext: Context = context.applicationContext ?: context

    private val wm: WindowManager? =
        runCatching { appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
            .getOrNull()

    /**
     * 进程**自称**是否持有系统窗口权限（`INTERNAL_SYSTEM_WINDOW`）。
     *
     * ⚠️ ★ **它只是个诊断读数，不用来决定走哪条路** —— 这一点是 2026-09-28 想清楚后改的，
     *   原来写成 `typeCandidates` 的开关，那样有个致命方向：
     *   权限探测返回 false 时会把系统覆盖层**整个跳过**，于是用户点名的"提层级"静默失效，
     *   日志里连一句失败都看不到（只看到"用了普通悬浮窗"）。
     *   而探测本身并不完全可信：
     *     - 权限授予是**按 uid / 按清单**双向成立的，清单里没声明时哪怕进程有资格也返回 DENIED；
     *     - 本机实测引擎所在进程 `uid=10193`（**不是** 1000），它与真实加窗时的判定
     *       是否严格同源，我没有证据 ⇒ 有假阴性的可能。
     *   ⇒ 结论：候选一律包含系统覆盖层、**加窗时逐个真试**，试不动再降级。
     *     探测值只打一行日志，用来事后解释"为什么系统覆盖层没挂上"（权限被拒 vs 类型被拒）。
     */
    private val reportsSystemWindowPermission: Boolean = runCatching {
        appContext.checkSelfPermission(INTERNAL_SYSTEM_WINDOW_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var view: HintView? = null

    /**
     * 按钮**还挂在窗口上**吗（诊断用，会报进引擎状态串的 `hintAlive`）。
     *
     * ★ 2026-09-29 从 `view != null` 改成 `view?.isAttachedToWindow == true`：
     *   用户点名要查"短时间内反复旋转会不会同时存在好几个按钮"。
     *   `view != null` 只能说明"我们手上有个对象"，**不能说明它还在屏幕上** ——
     *   换屏 / WMS 重建 / 被系统移除之后，字段可能还指着那个已经脱离的 View。
     *   用 attach 状态才是"真的看得见"，也正是用户要的那个问题的答案。
     */
    val isShowing: Boolean get() = view?.isAttachedToWindow == true

    /**
     * 弹一次。[targetRotation] = 要转到的方向。
     *
     * @return 是否**真的挂上去了**。false = WindowManager 不可用 / 加窗被拒 ——
     *   调用方必须把这个事实如实报出去（界面要能显示"按钮弹不出来"），
     *   不能静默失败，否则用户看到的就是"半自动模式没反应"。
     *   ★ 引擎跑在 SystemUI 里（本机实测 `uid=10193` / `com.android.systemui`，平台签名
     *     应用），加窗走平台权限豁免 ⇒ 走到 false 属于异常情况，而不是"用户没给悬浮窗权限"。
     *     真的失败时 [typeCandidates] 会逐档降级并逐条打日志，**不会静默**。
     *
     * ★ 已经显示时**不重建窗口**，只换目标方向并重置倒计时：
     *   用户连着转两下手机（先横后竖）时，重建会闪一下，原地更新则是"按钮跟着变"。
     */
    fun show(targetRotation: Int): Boolean {
        val w = wm ?: return false
        var ok = false
        onMainSync {
            val exist = view
            // ★★ 僵尸 view 防御（2026-09-29，配合用户提的"会不会叠出好几个按钮 / 按钮不出来"）。
            //
            //   背景：本类只有 `view` 这一个字段记"按钮还在不在"，而 `addView` / `removeView`
            //   都是**别人**（WMS / 系统）也会动手的事 —— 换屏、display 重建、系统回收窗口，
            //   都可能让那个 View 脱离窗口，而 `view` 字段还以为它在。
            //   后果不是"多一个按钮"（本类幂等，不会重复 addView），而是**更坏的一种**：
            //   下次 show 走"原地更新"分支 ⇒ `updateViewLayout` 静默失败（异常被
            //   [relayout] 里的 runCatching 吞掉）⇒ **按钮再也弹不出来**，而 `show`
            //   还会返回 true（等于对调用方撒谎）。
            //
            //   ⇒ 判据：还在窗口上就原地更新（这是"按钮跟着变方向"的正常路径，**不重建、不闪**）；
            //     已经脱开了就丢弃重建。`isAttachedToWindow` 在 `addView` 成功那一刻
            //     就已为 true（ViewRootImpl 同步 attach），所以正常路径**不会**误判成僵尸。
            if (exist != null && exist.isAttachedToWindow) {
                exist.setTarget(targetRotation)
                exist.restartCountdown()
                // ★ 连转两下手机（先横后竖）时目标方向会变 ⇒ **角也要跟着变**，
                //   否则第二次弹出来的还是上一个方向该待的那个角。
                relayout()
                ok = true
                return@onMainSync
            }
            if (exist != null) {
                // 走到这里 = 字段非空但**已经不在窗口上**。清掉它再重建，
                // 顺带把残留的窗口也移除一次（失败无所谓，它本来就不在了）。
                Log.w(TAG, "旧按钮已脱离窗口（僵尸 view）⇒ 丢弃并重建，避免按钮再也弹不出来")
                view = null
                runCatching { w.removeViewImmediate(exist) }
            }
            val v = HintView(appContext, targetRotation)
            // ★ 每次弹出都**按当前这块屏重新算**一次尺寸与坐标 —— 这是"旋转/折叠之后
            //   按钮仍在右下角、且大小合适"的主要保证：转完屏再弹的按钮，
            //   尺寸和坐标就是按转完之后的屏幕算的。
            val size = hintSize()
            // ⚠️ `applyContentScale` 必须在 `addView` **之前**设：它决定"可见圆占窗口多大"。
            //   漏了这步的话，外屏上圆会照着 48dp 的窗口画满 —— 等于自适应尺寸没生效。
            v.applyContentScale(size.contentScale)
            // ⚠️ 材质（玻璃那三笔）**不在加窗时开关**：它是唯一外观，[HintView.onDraw] 恒画它。
            //   窗口标志与它无关了（模糊已删，见 [layoutParams]）。
            // ★ 逐个试候选窗口类型（见 [typeCandidates]）；成功过一次之后就只试那一档，
            //   省得每次弹出都白摔一次异常。
            val tried = if (activeType > 0) listOf(activeType) else typeCandidates
            var usedType = -1
            for (t in tried) {
                val fail = runCatching { w.addView(v, layoutParams(size, targetRotation, t)) }
                    .exceptionOrNull()
                if (fail == null) {
                    usedType = t
                    break
                }
                Log.w(TAG, "悬浮按钮加窗失败（${typeName(t)}）", fail)
            }
            if (usedType > 0) {
                ok = true
                if (activeType != usedType) {
                    activeType = usedType
                    // ⚠️ 这句**必须**按实际生效的类型分开写：全都写"层级高于状态栏"就是撒谎，
                    //   而这条日志正是装机验证时唯一的判据（降级了却看着像成功 = 白查一轮）。
                    //   ⚠️ 更要记住"层号高"不等于"可用"：`TYPE_SYSTEM_OVERLAY` 层号 231000
                    //   却被系统强制 NOT_TOUCHABLE，按钮弹得出来但点不动（详见 [typeCandidates]）。
                    //   ⇒ 这条日志同时打**层号**和**可触摸性**两个结论，缺一个都会误导。
                    if (usedType == highLayerTouchableType()) {
                        Log.i(
                            TAG,
                            "窗口类型确定为 ${typeName(usedType)}（层号 181000 > 状态栏 151000 ⇒ " +
                                "按钮不再被状态栏遮挡；该类型可触摸 ⇒ 按钮点得动；" +
                                "权限探测=${reportsSystemWindowPermission}）",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "窗口类型确定为 ${typeName(usedType)}（层号 111000 < 状态栏 151000，" +
                                "旋转到左上/右上角仍可能被状态栏遮挡 —— 即用户报的那个问题还没解决；" +
                                "权限探测=${reportsSystemWindowPermission}，" +
                                "若为 false 说明 INTERNAL_SYSTEM_WINDOW 没被授予）",
                        )
                    }
                }
                view = v
                // 深/浅色写进日志：这套配色是"跟着系统走"的，出问题时**第一条要确认的就是
                // 系统当前到底是不是深色**（排查时最容易被忽略的一环）。
                Log.i(TAG, "按钮配色：${if (v.isNightPalette) "深色" else "浅色"}")
                // ★ 材质这一行留着（装机时一眼能确认外观是哪一套）：现在**只有**自绘玻璃一种，
                //   背后的模糊已经删掉 —— 理由见类注释 §材质 ②（用户明确不要"给屏幕加模糊"）。
                Log.i(TAG, "按钮材质：自绘液态玻璃（按钮背后不做任何模糊）")
                // 进场：淡入 + 轻微放大，160ms（够快，不挡事）
                v.alpha = 0f
                v.scaleX = 0.72f
                v.scaleY = 0.72f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ENTER_MS).start()
                v.restartCountdown()
            }
        }
        return ok
    }

    /** 立刻收掉（用户点了 / 引擎停了 / 模式切走了） */
    fun hide() {
        onMainSync {
            val v = view ?: return@onMainSync
            view = null
            v.cancelCountdown()
            v.animate().cancel()
            runCatching { wm?.removeViewImmediate(v) }
                .onFailure { Log.w(TAG, "移除悬浮按钮失败", it) }
        }
    }

    fun destroy() = hide()

    /**
     * 本次按钮的存活时长（毫秒）—— **每次弹出时读一次**配置。
     *
     * ★ 用户 2026-09-28 点名把它做成可调项：「添加自定义的时间滑条，让用户自行决定
     *   旋转按钮的消失时间，最少 1s，最多 60s」。默认 3 秒就是最初那个需求值。
     * ★ 为什么是函数而不是常量：配置在运行期会变（界面滑条），常量读不到变化。
     *   读点在 [HintView.restartCountdown] 的开头 —— 也就是**每次弹出/续期时取一次**，
     *   按钮活着的这段时间里不会再变（不会出现"倒计时跑到一半时长被改"的跳变）。
     * ⚠️ 这里再夹一次边界：引擎进程的值来自配置文件，理论上可被外部改坏。
     */
    private fun durationMs(): Long = AppPrefs.hintMs.value
        .coerceIn(AppPrefs.HINT_MS_MIN, AppPrefs.HINT_MS_MAX)
        .toLong()

    /**
     * 加窗参数。
     *
     * ⚠️ 尺寸必须由调用方**算好传进来**（而不是在这里现算）：`HintView` 的
     *   `contentScale` 与窗口边长必须是**同一次测量**的两个产物 —— 分两次算的话，
     *   正好卡在折叠/转屏中间时会得到一个"窗口按外屏、缩放按内屏"的错配。
     */
    private fun layoutParams(size: HintSize, target: Int, type: Int): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // ⛔ **刻意不设 `FLAG_BLUR_BEHIND` / `blurBehindRadius`**（2026-09-29 用户拍板弃用）。
        //   它糊的是**窗口矩形背后的那一整块屏幕**（不是"给按钮加材质"），方角处露出来的
        //   就是一块被糊过的画面 —— 用户原话「为什么弹出旋转按钮的时候，给屏幕加了模糊？
        //   我是要给按钮添加液态玻璃效果，不是给整个屏幕添加效果」。
        //   ⇒ 按钮的玻璃观感现在**全部**由 [HintView.onDraw] 自绘（见类注释 §材质 ③）。
        return WindowManager.LayoutParams(
            size.viewPx,
            size.viewPx,
            type,
            // NOT_FOCUSABLE ⇒ 隐含 NOT_TOUCH_MODAL ⇒ 窗口之外的触摸全部穿透
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // ★ 让窗口可以进挖孔的**安全区**（2026-09-28 实测发现被钳）：
            //   本机那条安全区有 140px 宽（相机孔在屏幕一角，安全区却是整条边），
            //   R=3 时 WMS 把 frame 从 x=2176 钳到 2075 —— 右边距从 14dp 变成 51dp。
            //   改成 ALWAYS 后由我们自己避开**真正的孔**（见 [cutoutRects]，孔远比安全区小）。
            //   minSdk = 30，这个常量无条件可用。
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            applyBottomEnd(this, size, target)
        }
    }

    /**
     * 窗口类型候选，**按层号从高到低**排列；加窗时逐个试，第一个成功的固定用下去。
     *
     * ================== ⚠️ 为什么**不**用 `TYPE_SYSTEM_OVERLAY`（2026-09-28 实地纠正）==================
     * 上一轮我按"层号高于状态栏"选了 `TYPE_SYSTEM_OVERLAY`(2006)，层号 231000 确实高于
     * 状态栏的 151000 —— **但装机后按钮能弹、怎么点都没反应**。真机 dump 钉死了原因：
     *
     * ```
     * Window #5 Window{8c9a6e1 u0 com.android.systemui}:
     *   mAttrs={(39,1485)(148x148) … ty=SYSTEM_OVERLAY …}
     *     fl=NOT_FOCUSABLE NOT_TOUCHABLE LAYOUT_IN_SCREEN HARDWARE_ACCELERATED
     *                       ↑ 这个 flag 我们没设过
     * ```
     *
     * `NOT_TOUCHABLE` **不是我们加的**（[layoutParams] 只给了 `NOT_FOCUSABLE | LAYOUT_IN_SCREEN`），
     * 是**这个类型的语义**决定的：系统覆盖层 = 只负责显示、不接收输入，于是
     * `onTouchEvent` 从来没有被调用过 —— 日志里 `触摸：按下` 一条都没有，
     * 总线 `semi=8` 而 `semitap=0`（弹了 8 次，一次点击都没进来）。
     *
     * ⇒ **教训：层号高 ≠ 能用。可交互的窗口必须在"可触摸的类型"里挑。**
     *   这条只能靠**实测**得到，读类型文档看不出来（文档只写"must not take input focus"，
     *   没写会被强制 `NOT_TOUCHABLE`）。
     *
     * 全机 `dumpsys window windows` 实测的「类型 → 层号 → 是否可触摸」表（本机 2026-09-28）：
     *
     * | 层号 | 类型 | 可触摸 | 本机谁在用 |
     * |---|---|---|---|
     * | 111000 | `APPLICATION_OVERLAY` | ✅ | 普通悬浮窗（**改造前的我们**，遮不住但能点） |
     * | 121000 | `SYSTEM_ALERT` | ⚠️ 这个实例不可触摸 | MIUI 安全中心悬浮窗 |
     * | 131000 | `INPUT_METHOD` | ✅ | 输入法 |
     * | **151000** | **`STATUS_BAR`** | ✅ | **状态栏 —— 遮挡线就在这一档** |
     * | 171000 | `NOTIFICATION_SHADE` | ✅ | 通知栏（WMS 单独跟踪它，别借用） |
     * | **181000** | **`STATUS_BAR_SUB_PANEL`** | ✅ | 通知模态窗 —— **选它** |
     * | 191000 | `KEYGUARD_DIALOG` | ✅ | 灵动岛 |
     * | 231000 | `SYSTEM_OVERLAY` | ❌ **系统强制不可触摸** | MIUI 防误触层（实测同样不可触摸） |
     * | 281000 | `MAGNIFICATION_OVERLAY` | ✅ | 手势条（宽只 66px，且会吞触摸） |
     *
     * ---- 为什么"必然被遮"（几何实测，不是观感）----
     * `StatusBar` 四个旋转变体全是 `(0,0)(fill×140) gr=TOP CENTER_VERTICAL` ⇒ 它恒占
     * **屏幕坐标系最上面 140px，不随旋转变动**；而按钮在 Δ=2/Δ=3 时取左上/右上角
     * （窗口 `(39,39)` / `(2177,39)`，高 149px ⇒ y∈[39,188]）⇒ **相交 101px，
     * 149px 高的按钮只露 48px**。Δ=0/Δ=1 时按钮取的是下边缘（y∈[1485,1634]），
     * 与状态栏不重叠 —— 这正是用户说「旋转 180° 之后才被挡」的原因，逐字吻合。
     *
     * 选 `STATUS_BAR_SUB_PANEL`(2017) 的依据：层号 181000 **高于状态栏 151000**（解决遮挡），
     * 而且本机上同类型的 `NotificationModalWindowManager` 窗口**实测 `可触摸`** ——
     * 有现成的同类样例，不是推断。不选 `NOTIFICATION_SHADE`：WMS 内部单独持有
     * "通知栏那个窗口"的引用（`getNotificationShadeWindowLocked`），多出一个同类型窗口
     * 有可能干扰系统级逻辑，收益不值得这个风险。
     *
     * ⚠️ 2017 是 `@hide` 常量（公开 SDK 里没有 `TYPE_STATUS_BAR_SUB_PANEL`）⇒ 只能写字面量，
     *   见 [TYPE_STATUS_BAR_SUB_PANEL_HIDDEN]。
     * ⚠️ 候选**不**按 [reportsSystemWindowPermission] 裁剪：探测读数偏保守时裁剪会让"提层级"
     *   静默失效 —— 而静默失效正是这次要消灭的东西。代价只是提层级失败时多摔一个异常，
     *   由 [show] 记成一条 W 级日志。
     */
    private val typeCandidates: List<Int> = listOf(
        highLayerTouchableType(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    )

    /** 实测生效的那一档（-1 = 还没成功加过窗）。一旦成功就固定用它，不再每次白摔一次异常 */
    @Volatile
    private var activeType: Int = -1

    /**
     * 供诊断总线读的**只读**视图：当前生效的窗口类型（-1 = 还没弹过）。
     *
     * ★ 为什么必须能读出去：提层级这件事**可能失败**（权限被拒 / 系统覆盖层被 ROM 挡），
     *   而失败时按钮照样能弹、看起来"一切正常"，只是**又被状态栏挡住了** ——
     *   正是用户报的那个问题。把这个值挂到状态总线上，才能一眼分辨
     *   「修好了」和「静默退回原样」，不用去翻日志。
     */
    val activeWindowType: Int get() = activeType

    /** 生效窗口类型的可读名（诊断用；没弹过时返回"未确定"） */
    val activeWindowTypeName: String
        get() = if (activeType > 0) typeName(activeType) else "未确定"

    /**
     * 高层号**且可触摸**的那一档：`TYPE_STATUS_BAR_SUB_PANEL`(2017)，层号 181000。
     *
     * 包成函数只是为了让 [typeCandidates] 读起来是"意图"（高层号 + 可触摸）而不是一串魔法数字；
     * 真正的取值与取舍理由全在 [TYPE_STATUS_BAR_SUB_PANEL_HIDDEN] 和 [typeCandidates]。
     */
    private fun highLayerTouchableType(): Int = TYPE_STATUS_BAR_SUB_PANEL_HIDDEN

    @Suppress("DEPRECATION")
    private fun typeName(t: Int): String = when (t) {
        TYPE_STATUS_BAR_SUB_PANEL_HIDDEN -> "STATUS_BAR_SUB_PANEL(2017)"
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "APPLICATION_OVERLAY(2038)"
        // 曾经的选项，**已弃用**：层号够高（231000）但系统强制 NOT_TOUCHABLE ⇒ 按钮点不动。
        // 保留映射只为读旧日志时能对上号，它不再是候选（见 [typeCandidates]）。
        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY -> "SYSTEM_OVERLAY(2006)"
        else -> "type=$t"
    }

    /**
     * 按钮在当前这块屏上的尺寸。**算术在 [HintSizePolicy]**，这里只管 dp→px 与取屏幕大小。
     *
     * ============================ 为什么要按屏算（2026-09-28 用户点名）============================
     * 用户原话：「考虑内屏和外屏的不同尺寸」。
     *
     * 设备实测（`wm density` / `dumpsys display`，两块屏 `densityDpi` 都是 440）：
     * 密度 **2.75x**；内屏 1672×2364px ⇒ 最小宽度 **608dp**；外屏 1168×1712px ⇒ **425dp**。
     * 所以同一颗按钮在两块屏上占屏宽的比例差着一大截（密度相同 ⇒ 绝对 dp 做不到"看着一样大"）。
     * ⇒ 可见圆按**屏宽比例**缩放（[HintSizePolicy.VISUAL_W_RATIO]），两屏才可比。
     *
     * ★ **2026-09-29 的当前结果**（用户报"按钮有点小"之后调的，两档都有单测钉住）：
     *   - 内屏 608dp ⇒ `608 × 62/608` = **62dp**（撞上限），占屏宽 10.2%；
     *   - 外屏 425dp ⇒ `425 × 62/608` ≈ 43.3dp ⇒ 夹到 **54dp**（撞下限），占屏宽 12.7%。
     *   外屏的 54dp 正是用户点名的"上一版内屏的尺寸" ⇒ 两块屏按钮**物理尺寸相同**。
     *
     * 但触摸目标**不跟着缩**（这是关键）：做法是把**窗口**取成 `max(可见圆, 48dp)`，
     * 多出来的那圈在窗口内**透明居中** —— 于是"用户看到的圆"与"手指能点中的范围"
     * 可以不一致（点偏一点也能中）。这是"视觉尺寸"与"可点性"两个诉求唯一不打架的做法。
     * ⚠️ 当前参数下可见圆最小 54dp > 48dp ⇒ 窗口恒等于可见圆、`contentScale` 恒为 1，
     *   这条安全网**暂时用不上**（但别删，见 [HintSizePolicy.TOUCH_DP_MIN]）。
     *
     * ⚠️ **2026-09-28 更正**（留着是为了不再犯）：旧注释写"密度 4.09、内屏 409dp、外屏 286dp"，
     *   四个数字全错，而且错得不只是数字 —— 比例被算成 0.132，两块屏的结果**双双撞上同一个
     *   上限**，等于自适应根本没生效。凡是"只在一档参数下才看得出"的错，都要靠把两档**同时**
     *   代入来发现 ⇒ 这就是 [HintSizePolicy] 带单测的原因。
     *
     * ⚠️ "这块屏有多宽"必须走 [DisplaySize] —— 它是**全工程唯一的量法**，与
     *   `ScreenForm` 的形态判据同源。各读各的话会出现"判成内屏、却按外屏的比例画按钮"
     *   这种自相矛盾的状态，而且两边看着都没错，排查时极难定位。
     *   尺寸按 `min(宽, 高)` 算（旋转不变量）：横屏时按钮**不会**因为"屏幕变宽了"而变大。
     */
    private fun hintSize(): HintSize {
        val measured = DisplaySize.of(appContext)
        val density = measured.density
        // ★ 算术全在 [HintSizePolicy] 里（纯函数、有单测钉住内屏/外屏各多大）——
        //   这里只负责把 dp 换成 px。别再把这行算式抄回来：它一抄就会有两份真相。
        val visualDp = HintSizePolicy.visualDp(measured.smallestWidthDp)
        val visualPx = dp(visualDp)
        // 触摸区取 max(可见圆, 48dp)：视觉可以小，可点性不能小
        val viewPx = max(dp(HintSizePolicy.viewDp(visualDp)), visualPx)
        // ⚠️ 定位仍用 currentWindowMetrics（真实可用区域），与"这块屏多大"是两件事：
        //   前者要把按钮贴在**屏幕右下角**，必须用能摆东西的那块区域。
        val (dw, dh) = screenSize()
        return HintSize(
            viewPx = viewPx,
            visualPx = visualPx,
            visualDp = visualDp,
            viewDp = viewPx / density,
            screenW = dw,
            screenH = dh,
        )
    }

    /**
     * 把窗口钉到「**用户手里那个姿态**的右下角」，且两条边留白相等。
     *
     * ================== 为什么不是「当前屏幕的右下角」（2026-09-28 第三次修）==================
     * 用户报：「逆时针转 90° 按钮显示在右上角，顺时针转 90° 按钮显示在左下角」。
     *
     * ★ 根因**不是坐标系搞错了** —— WMS 里的 frame 一直是精确的"当前逻辑右下角"，
     *   已用 `dumpsys window` 逐方向核对过（`frame=[2176,1484][2325,1633]`，父容器
     *   `[0,0][2364,1672]`）。真正的原因在**交互本身**：
     *   半自动模式是「用户转手机 → 弹按钮 → **点了才转屏**」，所以按钮弹出那一刻
     *   屏幕还停在旧方向，而用户的手已经把手机转了 90°。于是「屏幕的右下角」
     *   从他的视角看过去就是：
     *
     *   | 用户怎么转手机 | 屏幕的右下角落在他眼里的位置 |
     *   |---|---|
     *   | 逆时针 90° | **右上角** ✅ 与用户描述完全一致 |
     *   | 顺时针 90° | **左下角** ✅ 与用户描述完全一致 |
     *
     * ⇒ 正确做法：把按钮放在**目标方向（= 用户此刻的手势姿态）的右下角**。
     *   以**当前**屏幕坐标系来表达，就是把"右下角"这个角按
     *   `Δ = (目标 − 当前) mod 4` 转过去：
     *
     *   | Δ | 放在当前屏幕的哪个角 | 对应场景（当前 = 竖屏 0） |
     *   |---|---|---|
     *   | 0（这次不用转） | 右下 | 屏幕已经是用户要的方向 |
     *   | 1 | 左下 | 用户**逆时针**转 90°（顶边朝左 ⇒ 目标 1） |
     *   | 2 | 左上 | 用户倒过来拿 180°（目标 2） |
     *   | 3 | 右上 | 用户**顺时针**转 90°（顶边朝右 ⇒ 目标 3） |
     *
     * ★★ 这张表怎么来的 —— **要改就先照这个流程重推，不要用直觉**
     *   它由两段拼成，两段都必须取"标定过的"关系：
     *
     *   ① **物理姿态 → 编号**：不是推的，是真机标定的。用平台自己的 `TYPE_27` 朝向码
     *      当标准答案，9 组采样全部一致（见 `OrientationFusion` 类注释 + 回归测试
     *      `OrientationFusionTest.matchesPlatformType27Samples`）：
     *
     *      | 设备物理姿态 | 编号 |
     *      |---|---|
     *      | 竖屏正立 | 0 |
     *      | **逆时针 90°（顶边朝左）** | **1** |
     *      | 倒置 180° | 2 |
     *      | **顺时针 90°（顶边朝右）** | **3** |
     *
     *      ⚠️ 这个符号极易推错：`OrientationFusion` 自己就被真机纠正过一次
     *      （初版 `atan2(-ax, ay)`，竖屏对得上、两个横屏整体对调，正是用户报的
     *      「横屏有概率反过来」）。**竖屏能对上不代表符号对** —— 0/2 是镜像不变点，
     *      只有两个横屏能看出正负号。
     *
     *   ② **Δ → 取哪个角**：把 ① 代进世界坐标系算出来。世界 = 用户视角（x 右、y 上，
     *      角度逆时针为正）；设备此刻的真实姿态 θ = 90°×目标，屏幕当前编号 = 当前，
     *      于是内容在用户眼里整体转了 Δθ = 90°×Δ：
     *
     *      ```
     *      content-right = (cos Δθ, sin Δθ)        content-down = 它顺时针 90°
     *      content-left  = −content-right          content-up   = −content-down
     *      ```
     *
     *      ★ 某个**角**的位移方向 = 它两条边的**向量和**；用户要的"右下"= 世界
     *        (1, −1) 方向。以 Δ=1 为例（content-right=(0,1)、content-down=(1,0)）：
     *          右下 (0,1)+(1,0) = (1,1) 那是右上 ✗
     *          左下 −(0,1)+(1,0) = **(1,−1) ✅ 用户右下**
     *        ⇒ Δ=1 取**左下**。Δ=3 同理取**右上**；Δ=0/2 分别取右下 / 左上。
     *
     *      ⚠️ **踩过的坑（这张表差点写错）**：求"两条边的中间方向"**必须做向量和**，
     *      不能用两个角度取平均 —— 角度会绕过 ±180°，平均出来可能整整差 180°，
     *      而且 **Δ=0/2 上照样"看着对"**（0 与 180 都对得上），只在 Δ=1/3 才暴露。
     *      与 ① 里那个符号错误是同一类：**只错两个横屏**。
     *
     *   ⇒ 拿 ① ② 的结果与你报的现象互验：修之前恒取"屏幕右下"，于是逆时针（Δ=1）
     *     时它落在你眼里的**右上**、顺时针（Δ=3）时落在**左下** —— 与你原话逐字吻合；
     *     修之后两者都落到**右下**。
     *
     * ⚠️ 屏幕随后**真的转过去**时，这个角正好变成物理右下角 —— 而那时按钮早被收走了
     *   （`onTap` 里先 `hide()` 再写方向），所以不会出现"转完之后跑偏"。
     *   若因为别的原因屏幕在按钮还挂着时转了，`onConfigurationChanged → relayout()`
     *   会用新的 Δ 重算，角始终跟着用户的手。
     *
     * ★ 定位方式（2026-09-28 第二次修，用户报"旋转之后按钮不在右下角"）：
     *   原来用的是 `Gravity.BOTTOM or Gravity.END` + 一个像素偏移量。**偏移量的语义
     *   在不同显示方向下并不一致** —— 竖屏看着是对的，转到横屏就可能整体偏掉。
     *   现在改成 `Gravity.TOP or Gravity.START`（固定锚在左上角）+ **自己按屏幕尺寸
     *   算绝对坐标**：`宽 - 按钮宽 - 留白`。绝对坐标没有任何方向歧义，
     *   "右下角 + 两侧留白相等"就是字面意思，横竖屏都成立。
     *
     * ★ 对齐的是**可见圆**的右下角，不是窗口的（2026-09-28 加尺寸自适应后重推）：
     *   窗口可以比可见圆大一圈（触摸余量，见 [hintSize]）。若按窗口对齐，
     *   外屏那圈透明的余量会把圆整体往里推 5dp —— 留白看着就变了。
     *   设 half = (窗口边长 − 可见圆边长) / 2，则
     *     `窗口左边界 = 屏宽 − 留白 − 可见圆边长 − half`
     *   恒好让**可见圆**距右边缘 = 留白。这里的 `padPx` 就是那个 half。
     *
     * ⚠️ 与 `FLAG_LAYOUT_IN_SCREEN` 是**配套**的：那个 flag 让窗口的布局区域 = 整个
     *   屏幕（含状态栏/导航栏），所以这里"屏幕尺寸"必须取 **display** 的尺寸
     *   （见 [screenSize]）。取成应用可用区域的话，横竖屏切换时会整体偏一截。
     */
    private fun applyBottomEnd(p: WindowManager.LayoutParams, size: HintSize, target: Int) {
        val m = dp(MARGIN_DP)
        // ★★ 2026-09-29 深夜定案：**这里的两边本来就是同一个空间**（本机偏置恒 0，
        //   见 `PanelOrientation` 类注释）—— `Display.getRotation()` 与引擎给的 `target`
        //   同值同义，`toDevice()` 现在是恒等换算。
        //
        //   ⚠️ 中间那段"内屏安装朝向 180°"的推断是**错的**，它让这里的 `Δ` 偏了 180°
        //     ⇒ 按钮弹到**对角**去（"预览按钮跑到左上角"就是它）。同一份错推断也让引擎的
        //     写入端偏 180°（用户报的"半自动方向是反的"）。
        //   ★ 形态自己量（与 [hintSize] 用的是同一套 `DisplaySize`，见那里的"不分家"纪律）：
        //     别让引擎把偏置传进来 —— 悬浮窗要**重定位**（[relayout]）时引擎不在调用链上。
        val offset = PanelOrientation.offsetFor(ScreenForm.of(appContext))
        val cur = PanelOrientation.toDevice(displayRotation(), offset)
        val delta = ((target - cur) % 4 + 4) % 4

        // ★ 对齐的是**可见圆**，不是窗口（窗口比圆多出 `padPx` 的透明触摸余量）。
        //   ⇒ 朝向屏幕边的那条窗边，要让出 padPx，圆才恰好贴到 m：
        //     圆左缘贴 m ⇒ 窗左边 = m − padPx（圆右缘贴 m ⇒ 窗右边 = 屏宽 − 窗边长 − m + padPx）
        val nearL = m - size.padPx
        val nearT = m - size.padPx
        val nearR = size.screenW - size.viewPx - m + size.padPx
        val nearB = size.screenH - size.viewPx - m + size.padPx

        p.gravity = Gravity.TOP or Gravity.START
        val corner = when (delta) {
            0 -> "右下".also { p.x = nearR; p.y = nearB }
            1 -> "左下".also { p.x = nearL; p.y = nearB }
            2 -> "左上".also { p.x = nearL; p.y = nearT }
            else -> "右上".also { p.x = nearR; p.y = nearT }
        }

        // 屏幕小到按钮都放不下时（理论上不该发生），宁可压到边也不要算出屏幕外的坐标 ——
        // 坐标一旦出界，WMS 会把它钳到边上，看着就是"按钮飘在半空"。
        val maxX = (size.screenW - size.viewPx).coerceAtLeast(0)
        val maxY = (size.screenH - size.viewPx).coerceAtLeast(0)
        val rawX = p.x
        val rawY = p.y
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
        if (rawX != p.x || rawY != p.y) {
            Log.i(TAG, "屏幕放不下按钮（算得 $rawX,$rawY）→ 已压到边界 (${p.x}, ${p.y})")
        }

        // 与**真正的**相机挖孔相交 ⇒ 往代价最小的方向挪开（挖孔那块像素是看不见的）
        for (hole in cutoutRects()) {
            val win = Rect(p.x, p.y, p.x + p.width, p.y + p.height)
            if (!Rect.intersects(win, hole)) continue
            val gap = dp(2)
            val toRight = hole.right - win.left + gap
            val toLeft = win.right - hole.left + gap
            val toDown = hole.bottom - win.top + gap
            val toUp = win.bottom - hole.top + gap
            val dx = minOf(toRight, toLeft)
            val dy = minOf(toDown, toUp)
            if (dx <= dy) p.x += if (toRight <= toLeft) dx else -dx
            else p.y += if (toDown <= toUp) dy else -dy
            Log.i(TAG, "按钮压住相机挖孔 $hole → 已挪到 (${p.x}, ${p.y})")
        }

        // ★ 尺寸与坐标**必须在同一次调用里一起打**（2026-09-28 实测踩到）：
        //   屏幕旋转时 display bounds 是活的，分两次读会打出
        //   「屏幕 1672×2364，坐标 (2176, 1484)」这种自相矛盾的日志 ——
        //   前一次读在横屏（2364×1672）、后一次读在竖屏，白判断了一轮。
        //   同时把"屏幕转了多少度"和"据此选了哪个角"一起记下 —— 位置再出问题，
        //   第一件要确认的事就是这两个数（用户的手机姿态 vs 屏幕实际方向）。
        Log.i(
            TAG,
            "定位：屏幕 ${size.screenW}×${size.screenH}（旋转 $cur），" +
                "可见圆 ${size.visualDp.toInt()}dp（触摸区 ${size.viewDp.toInt()}dp），" +
                "留白 ${MARGIN_DP}dp，目标旋转 $target（Δ=$delta ⇒ 取${corner}角）" +
                " → 窗口左上角 (${p.x}, ${p.y})",
        )
    }

    /**
     * 当前**屏幕**的旋转（`Surface.ROTATION_*`：0/1/2/3）。
     *
     * ⚠️ 注意要的是**屏幕**的旋转，不是设备姿态 —— 半自动模式下这两者**故意不同步**
     *   （屏幕要等用户点了才转），而 [applyBottomEnd] 的整个角变换正是建立在
     *   "用户的手已经转了、屏幕还没转"这个事实之上。
     *
     * ★ 2026-09-29 回退说明：这里曾短暂地做过 `installOrientation` 换算（为了对齐
     *   引擎给的"设备空间" target），但偏置取自"反射 `DEFAULT_DISPLAY`"⇒ **外屏上按钮弹到对角**。
     *   （那次现场**看起来是**"读到了内屏的值"，但 `display 0` 绑哪块屏本机至今存疑，
     *   见 [ActiveDisplay] 的类注释。）现在引擎侧与这里**都不换算**，
     *   `(target - cur)` 这个差值的口径重新一致。
     */
    private fun displayRotation(): Int {
        // ★★ 2026-09-29：改走 [ActiveDisplay]（**按尺寸认屏**），不再直接读
        //   `Display.DEFAULT_DISPLAY` —— 本机"display 0 属于哪块屏"有两种互相矛盾的
        //   记录（见 [ActiveDisplay] 的类注释），而本函数的返回值决定按钮落在哪个角，
        //   读错一块屏就是"按钮弹到对角"。形态自己现量（与 [hintSize] 同一套 `DisplaySize`）。
        val form = runCatching { ScreenForm.of(appContext) }.getOrDefault(ScreenForm.INNER)
        return ActiveDisplay.rotationOf(appContext, form)
            ?: runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.rotation
            }.getOrNull() ?: 0
    }

    /**
     * 相机挖孔**真正**占住的矩形（可能多个；拿不到就是空）。
     *
     * ★ 为什么要它：系统的挖孔**安全区**会让 WMS 把窗口往里钳。本机实测 —— 安全区是
     *   **整条边 140px**，而孔本身只有 164×140 一块。R=3 时 frame 被从 x=2176 钳到 2075，
     *   右边距从 14dp 变成 51dp。现在窗口标了 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`
     *   （安全区的钳制不再生效），改由我们避开**孔**这一小块。
     */
    private fun cutoutRects(): List<Rect> =
        runCatching { wm?.currentWindowMetrics?.windowInsets?.displayCutout?.boundingRects }
            .getOrNull()
            .orEmpty()

    /**
     * 当前显示方向下的屏幕像素尺寸。
     *
     * ★ 用 `currentWindowMetrics`（API 30，本工程 `minSdk = 30` 所以无条件可用）：
     *   它是 WMS 直接给的 display bounds，**旋转后立刻是新值**。
     *   退路是 `Resources.displayMetrics` —— 它在 SystemUI 这种常驻进程里也是随方向更新的。
     */
    private fun screenSize(): Pair<Int, Int> {
        val b = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 屏幕方向/尺寸/形态变了 ⇒ 重新钉一次右下角（顺带按新屏幕重算尺寸）。
     *
     * ⚠️ 为什么 WMS 自己重排**不够**：窗口的 x/y 是**绝对像素**，方向一变，"屏幕宽/高"
     *   整个对调了，旧坐标自然就不在右下角。必须按新尺寸重算。
     *
     * ⚠️ 尺寸也**必须一起更新**：2026-09-28 加了"按屏宽自适应"之后，
     *   换屏（折叠 / 展开）时"可见圆该多大"本身就变了（内屏 62dp ↔ 外屏 54dp）。
     *   只挪坐标不换尺寸的话，折起来就会看到一个"按内屏尺寸画在外屏上"的大按钮 ——
     *   而这正是用户提「考虑内屏和外屏的不同尺寸」要解决的问题本身。
     */
    private fun relayout() {
        val w = wm ?: return
        val v = view ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        val size = hintSize()
        p.width = size.viewPx
        p.height = size.viewPx
        v.applyContentScale(size.contentScale)
        // ★ 用 view 里记着的**目标方向**重算角：屏幕真的转过去之后，
        //   "用户眼里的右下角"在当前坐标系里就是另一个角了（见 [applyBottomEnd]）。
        applyBottomEnd(p, size, v.targetRotation)
        // 直接改 view 自己那份 params（就是 `addView` 时交给 WMS 的那个实例），
        // 省掉手工搬运；updateViewLayout 会把它同步回窗口属性。
        runCatching { w.updateViewLayout(v, p) }
            .onFailure { Log.w(TAG, "旋转后重新定位悬浮按钮失败", it) }
    }

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun dp(v: Float): Int = (v * appContext.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 一次尺寸计算的完整结果。
     *
     * @param viewPx 窗口边长（= 触摸区边长）
     * @param visualPx 可见圆直径 —— 窗口里**居中**画这么大，外面那圈是透明触摸余量
     * @param visualDp / [viewDp] 只给日志和诊断看（换算回 dp 才好和常量对照）
     */
    private data class HintSize(
        val viewPx: Int,
        val visualPx: Int,
        val visualDp: Float,
        val viewDp: Float,
        val screenW: Int,
        val screenH: Int,
    ) {
        /** 窗口比可见圆大出的那一半（触摸余量） */
        val padPx: Int get() = (viewPx - visualPx) / 2

        /** 可见圆占窗口边长的比例，交给 [HintView] 把内容整体缩进去 */
        val contentScale: Float get() = if (viewPx > 0) visualPx.toFloat() / viewPx else 1f
    }

    /**
     * 当前是否深色模式。
     *
     * ★ 读的是**本进程的 Configuration**：引擎常驻 SystemUI，系统切深浅色时它会收到
     *   config change（进而触发 View 的 [HintView.onConfigurationChanged]）——
     *   所以按钮**活着这段时间里**切主题也能立刻跟上，不需要重建窗口。
     * ⚠️ 只在构造与配置回调时调它，**不要放进 onDraw**：那是每秒 60 次的热路径，
     *   没必要每帧都去问一次系统。
     */
    private fun isNight(): Boolean = runCatching {
        (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(false)

    /**
     * 把一段代码搬到主线程**同步**执行。
     *
     * ★ 为什么必须"同步"：`WindowManager.addView` 能否成功（有没有悬浮窗权限）
     *   就是 [show] 的返回值，而引擎（可能在传感器回调线程 / 相机回调线程上）必须拿到它，
     *   才能把"半自动其实没弹出来"这件事报给界面。等下一次状态上报再纠正太晚了。
     *   超时给 500ms：正常路径是微秒级，超时只可能是主线程真的被卡住。
     */
    private fun onMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            runCatching { block() }.onFailure { Log.w(TAG, "悬浮按钮操作异常", it) }
            latch.countDown()
        }
        runCatching { latch.await(500, TimeUnit.MILLISECONDS) }
    }

    // ============================================================ 自绘的按钮

    /**
     * 按钮本体。三层，绘制顺序就是列表顺序：**圆形底 → 倒计时环 → 旋转箭头**。
     * （顺序不能换 —— 环在圆底里面，先画环会被白底盖掉，见 [onDraw] 的注释。）
     *
     * ★ 箭头整体按 [targetRotation] 旋转，用户一眼就知道"点下去屏幕会变成哪个姿势"，
     *   所以不需要额外写文字（用户 2026-09-28 选的就是"只有图标 + 倒计时环"）。
     */
    private inner class HintView(context: Context, target: Int) : View(context) {

        @Volatile
        var targetRotation: Int = target

        /** 剩余比例：1 = 刚弹出（环满），0 = 时间到（环空） */
        @Volatile
        private var remain = 1f

        /**
         * 当前配色（浅色 / 深色两套，见 [HintPalette]）。
         *
         * ★ 只在**构造**与 [onConfigurationChanged] 时取值，**不在 onDraw 里读 Configuration** ——
         *   onDraw 每秒跑 60 次，没必要每帧都去问一次系统。
         */
        @Volatile
        private var palette: HintPalette = HintPalette.of(isNight())

        /** 供诊断日志用：这套配色是不是深色 */
        val isNightPalette: Boolean get() = palette.night

        /**
         * 内容缩放 = **可见圆边长 / 窗口边长**（1 = 窗口本身就是可见圆）。
         *
         * ★ 为什么需要它：窗口边长取的是 `max(可见圆, 48dp 触摸下限)`（见 [hintSize]）。
         *   它**曾经**在每个方向上都起作用：外屏上可见圆只有 38dp、窗口却取 48dp，
         *   而下面的几何量（[RING_R] / [BTN_R] / [ICON_R]…）全都是"相对窗口边长的比例"，
         *   不缩的话圆会照着窗口画满，那圈触摸余量就白留了（视觉上等于**没有**缩小）。
         *
         * ⚠️ **2026-09-29 起它恒为 1**：可见圆最小 54dp > 触摸下限 48dp ⇒ 窗口就是可见圆
         *   （两块屏都如此）。之所以**不把这条分支删掉** —— 它是"视觉尺寸让位给可点性"
         *   这个机制的本体：参数一旦回调（比如将来给某种小屏把下限压到 48 以下）它就会
         *   立刻重新生效；删了只会剩下一处"没人记得为什么"的硬编码。
         *
         * ★ 缩放以**窗口中心**为原点（见 [onDraw] 的 `s`）：窗口是正方形且圆居中，
         *   所以按中心缩放等价于"把圆画小一点"，不需要动任何坐标。
         */
        @Volatile
        private var contentScale = 1f

        /** onDraw 真正用的边长（窗口边长 × [contentScale]） */
        private val drawSide: Float get() = width.toFloat() * contentScale

        /** 由外层在换屏 / 转屏后设置（见 `RotateHintOverlay.relayout`） */
        fun applyContentScale(s: Float) {
            if (s == contentScale) return
            contentScale = s
            // 尺寸没变但缩放变了 ⇒ onSizeChanged 不会再触发，环的矩形必须在这里重算，
            // 否则倒计时环会按旧比例画（"环缩不到正确大小"）。
            if (width > 0) {
                updateRingRect(width)
                updateGlassShaders(width)
            }
            invalidate()
        }

        private var pressed = false

        /**
         * 触摸判定的起点与「这次算不算点击」。
         *
         * ★ 2026-09-28 追加（方案 E）：**手指在这块窗口里滑动，不算点击**。
         *   理由见 [onTouchEvent]。判据用平台标准的 [touchSlop]，不自定魔法数。
         */
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        /** 诊断：这次手势收到过几个 MOVE、最远走了多少像素（只在按钮存活期内累加） */
        private var moveCount = 0
        private var maxDist = 0f

        /** 平台标准触摸阈值（通常 8dp），超过它就算"在滑动"而不是"在点" */
        private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
        private var startedAtMs = 0L
        private var ticker: Runnable? = null

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // ---------------------------------------------------------- 玻璃材质的两支笔
        //
        // ★ 这两支笔的画法见 [onDraw] 的 ②③ 步；这里只说**为什么把 shader 缓存起来**：
        //   `LinearGradient` 每帧 new 一个会对 GC 造成每秒 60 次的小分配，而本按钮是
        //   常驻进程（SystemUI）里的热路径。shader 只跟**尺寸**有关（与剩余时间无关），
        //   所以跟 [ringRect] 一样在尺寸变化时算一次即可 —— 见 [updateGlassShaders]。

        /** 上缘镜面高光（填充整圆，被 shader 的竖直渐变限制成"只有上半亮"） */
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /** 光晕描边：一圈细环，顶部亮、往下化开 —— 玻璃"有厚度"的那一笔 */
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private var sheenShader: Shader? = null
        private var edgeShader: Shader? = null

        /**
         * ⚠️ 倒计时环专用矩形。**绝不能与外层的 arcRect 复用** ——
         *   图标那段弧用的半径和环完全不同，复用会让下一帧的倒计时环画到图标那么小的
         *   矩形上去（"环突然缩成一点"）。
         */
        private val ringRect = RectF()

        /** 图标弧度专用（与 [ringRect] 分开，理由同上） */
        private val iconRect = RectF()

        private val iconPath = Path()
        private val arrowPath = Path()

        fun setTarget(t: Int) {
            if (t == targetRotation) return
            targetRotation = t
            invalidate()
        }

        fun restartCountdown() {
            startedAtMs = SystemClock.elapsedRealtime()
            remain = 1f
            cancelCountdown()
            // ★ 时长在**这里**取一次（配置值），整个倒计时用它 —— 见 [durationMs]
            val dur = durationMs()
            val r = object : Runnable {
                override fun run() {
                    val el = SystemClock.elapsedRealtime() - startedAtMs
                    remain = (1f - el.toFloat() / dur).coerceIn(0f, 1f)
                    invalidate()
                    if (remain <= 0f) {
                        ticker = null
                        // 时间到、用户没点 ⇒ 收掉按钮、**不旋转**
                        hide()
                    } else {
                        postOnAnimation(this)
                    }
                }
            }
            ticker = r
            postOnAnimation(r)
        }

        fun cancelCountdown() {
            ticker?.let { removeCallbacks(it) }
            ticker = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateRingRect(w)
            updateGlassShaders(w)
        }

        /**
         * 重算倒计时环的矩形。
         *
         * ★★ 半径用 [drawSide]（= 窗口边长 × [contentScale]），**圆心必须用窗口中心** ——
         *   这两个数不是一个东西，2026-09-28 在这里踩过一次真错：
         *
         *   旧代码把圆心也按 `s * 0.5` 算了（`s` = 可见圆边长）。而 `s == viewSide`
         *   只在 `contentScale == 1` 时成立。当年**恰好只有内屏**满足这一点
         *   （内屏可见圆 54dp ≥ 触摸下限 48dp，窗口就是可见圆）；**外屏**则可见圆 38dp、
         *   窗口取 48dp ⇒ `contentScale ≈ 0.79`，于是环心被算到 `0.79/2 = 0.395 × 窗口边长` 处，
         *   而圆底与箭头（[onDraw] 的 `cx/cy`）在 `0.5 × 窗口边长` ——
         *   环整体朝**左上**偏了 `0.105 × 窗口边长`（132px 的窗口上约 14px）。
         *   症状就是用户报的「外屏上倒计时圈不在同一个圆心上」。
         *
         *   ⚠️ 2026-09-29 起 `contentScale` 在两块屏上都**恒为 1**（见 [contentScale]），
         *   也就是这个错**暂时不会再现**。但下面的判据照旧执行 —— 它是"圆心与半径同源"
         *   的结构保证，而参数随时可能再回到"窗口 > 可见圆"。
         *
         *   ⇒ 判据：**圆心只跟窗口有关，半径只跟可见圆有关**。二者混用就会"一档看着对、
         *   另一档偏一角"，而开发时看到的恰是最常看的那档 —— 所以这个错很容易蒙过自测。
         */
        private fun updateRingRect(viewSide: Int) {
            val rr = HintRingGeometry.ringRadius(viewSide.toFloat(), contentScale, RING_R)
            // ⚠️ 圆心只跟**窗口**有关（[HintRingGeometry.viewCenter]），半径只跟**可见圆**有关。
            //   别再写成 `s * 0.5f` —— 那正是外屏偏心的成因（见 [HintRingGeometry] 的类注释）。
            val c = HintRingGeometry.viewCenter(viewSide.toFloat())
            ringRect.set(c - rr, c - rr, c + rr, c + rr)
        }

        /**
         * 重算玻璃那两笔高光的渐变。
         *
         * ★ 两条渐变都**只在竖直方向**变化（同一个 x 从顶到底），所以用 `LinearGradient`
         *   就够，不需要 `RadialGradient`：
         *   - 高光：从圆顶稍上方（`cy - btnR`）往下到圆内 20% 处，色从 [HintPalette.specular]
         *     化到**全透明**。终点刻意落在圆内而不是圆底 —— 真实玻璃的高光集中在边缘，
         *     铺满整圆就变成"上白下透的渐变色块"，不像玻璃。
         *   - 描边：从圆顶到下缘，色从 [HintPalette.specular] 化到透明的 25%。
         *     下缘留一点微光（而不是全透明），是为了让圆在下半部分也有边界，
         *     否则按钮压在白背景上时下半圈会"消失"。
         *
         * ⚠️ 跟 [updateRingRect] 一样，**只在尺寸变化时算**（[onSizeChanged] /
         *   [applyContentScale] / 构造后首次布局），不要放进 `onDraw`。
         * ⚠️ 半径一律用 `drawSide * BTN_R`（可见圆半径），与圆底**同源** ——
         *   这里再踩一次"圆心/半径不同源"的坑就会重现外屏偏心那个 bug
         *   （见 [updateRingRect] 的注释）。
         */
        private fun updateGlassShaders(viewSide: Int) {
            if (viewSide <= 0) return
            val s = viewSide.toFloat() * contentScale
            if (s <= 0f) return
            val c = HintRingGeometry.viewCenter(viewSide.toFloat())
            val btnR = s * BTN_R
            val top = c - btnR
            val spec = palette.specular

            sheenShader = LinearGradient(
                0f, top,
                0f, c + btnR * SHEEN_END_FRACTION,
                spec, spec and 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
            edgeShader = LinearGradient(
                0f, top,
                0f, c + btnR,
                spec, withAlpha(spec, EDGE_BOTTOM_ALPHA),
                Shader.TileMode.CLAMP,
            )
        }

        /**
         * 配置变了：可能换了**屏幕方向**，也可能换了**深浅色** —— 两件事都要处理。
         *
         * ★ 方向：覆盖的是"按钮**正显示着**时屏幕转了"这种情况（用户自己转手机、
         *   或别的应用改了方向）。`show()` 时算的那次坐标只对当时的方向有效。
         * ★ 深浅色：用户切了系统深色模式 ⇒ 整枚按钮（含倒计时轨道）要换配色，
         *   见 [HintPalette] 里"为什么这是功能问题、不是审美问题"。
         * ⚠️ 重定位延后一帧：配置回调触发的时刻，display bounds 可能还没落定。
         */
        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            val night = newConfig?.let {
                (it.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            } ?: isNight()
            if (night != palette.night) {
                palette = HintPalette.of(night)
                // ⚠️ 换了配色必须**重算高光的渐变**：渐变的两端颜色是从
                //   `palette.specular` 烤进 shader 里的（shader 不会自己重读配色）。
                //   漏了这步的症状是"切深色模式后按钮换了底色，但上缘那道光还是浅色版"——
                //   很细、很容易被当成"高光本来就该那样"。
                if (width > 0) updateGlassShaders(width)
                invalidate()
            }
            // ★ 两次重定位，不是冗余：
            //   ① 方向切换是"配置先到、display bounds 稍后落定"；
            //   ② 折叠屏的展开/收拢还带一段**动画**，尺寸在动画期间是中间值，
            //      折叠态下内外屏尺寸差着一大截（本机 1672×2364 ↔ 1168×1712），
            //      只算一次很可能钉在中间态上。
            //   第二次是"等落定后再确认一遍"，幂等 —— 尺寸没变时 updateViewLayout 无副作用。
            post { relayout() }
            postDelayed({ relayout() }, RELAYOUT_SETTLE_MS)
        }

        override fun onDraw(canvas: Canvas) {
            val s = drawSide
            if (s <= 0f) return
            // ⚠️ 圆心取**窗口**中心（不是 s 的一半）：s 是"可见圆的边长"，
            //   而可见圆是在窗口里居中的 —— 窗口是正方形、圆也居中，
            //   所以中心恒为 width/2。按 s/2 算会把圆画偏（左上角那半圈少一截）。
            //   ★ 这个数就是 [HintRingGeometry.viewCenter] —— 倒计时环用的是**同一个函数**，
            //     两处同源才能保证"环与圆底同心"（曾经不同源，只在外屏上偏，见那里的注释）。
            val cx = HintRingGeometry.viewCenter(width.toFloat())
            val cy = cx
            val ringW = s * RING_W
            val btnR = s * BTN_R

            trackPaint.strokeWidth = ringW
            ringPaint.strokeWidth = ringW
            iconPaint.strokeWidth = s * ICON_W

            // ① 圆底（**必须先画**）
            //
            // ★ 绘制顺序是有讲究的：旧几何是"环在外、底在内"，先画环再画圆底没问题
            //   （圆底盖不到外面的环）；2026-09-28 把环收进圆底里面之后，顺序就**必须**
            //   反过来 —— 否则不透明的白底会把整圈倒计时环盖掉，用户看到的就是
            //   "只有白圆 + 箭头，倒计时条不见了"。
            //
            // ★ 2026-09-29：表面**恒**用玻璃那档不透明度（[HintPalette.glassSurfaceAlpha]）。
            //   曾经这里还分"有没有跨窗口模糊"两套，现在模糊已删（见类注释 §材质 ②），
            //   自绘玻璃是**唯一**的外观 ⇒ 不需要那个开关，也就不会再有"两套外观不一致"。
            //   ⚠️ 圆底与后面两笔高光的**半径都是 `btnR`**（= `s * BTN_R`）：
            //     同源，绝不能一处按可见圆、一处按窗口（那个错只在外屏暴露，见 [updateRingRect]）。
            val flat = if (pressed) palette.basePressed else palette.base
            basePaint.color = withAlpha(flat, palette.glassSurfaceAlpha)
            canvas.drawCircle(cx, cy, btnR, basePaint)

            // ②③ 玻璃的两笔：上缘高光 + 光晕描边。
            //
            // ★ 顺序在圆底**之后**、倒计时环**之前**：高光属于"表面"，环属于"表面上的内容"，
            //   环画在下面会被高光冲淡（尤其深色主题下白高光很明显）。
            // ⚠️ shader 可能还没备好（`onSizeChanged` 之前不该画），`?.let` 兜住：
            //   少一笔高光只是"素了点"，而丢掉圆底或环是功能受损 —— 两者代价不对称。
            sheenShader?.let {
                sheenPaint.shader = it
                canvas.drawCircle(cx, cy, btnR, sheenPaint)
            }
            edgeShader?.let {
                edgePaint.shader = it
                edgePaint.strokeWidth = s * EDGE_W
                // ⚠️ 半径要**内缩半个线宽**：`drawCircle` 的描边是骑在半径上画的，
                //   不内缩的话外面半个线宽会被窗口边界裁掉（窗口 == 可见圆），
                //   那圈描边会变成"厚度只有一半"的断线。
                canvas.drawCircle(cx, cy, btnR - s * EDGE_W * 0.5f, edgePaint)
            }

            // ④ 倒计时环的轨道（浅色下是浅灰，深色下是浅白 —— 见 HintPalette）
            trackPaint.color = palette.track
            canvas.drawArc(ringRect, 0f, 360f, false, trackPaint)

            // ⑤ 倒计时进度：从 12 点方向**顺时针缩短**
            // ★ 用"缩短"而不是"增长"：用户看到的是"剩下的时间在变少"，符合沙漏的直觉。
            ringPaint.color = palette.ring
            canvas.drawArc(ringRect, -90f, 360f * remain, false, ringPaint)

            // ⑥ 旋转箭头：整体按目标方向旋转
            //
            // ⚠️ 半径 `s * ICON_R` 而不是"按圆底比例算"：图标必须落在**倒计时环的内缘**
            //   里面（见 ICON_R 的注释），按圆底算会让箭头顶到环上去。
            canvas.save()
            canvas.rotate(targetRotation * 90f, cx, cy)
            drawRotateIcon(canvas, cx, cy, s * ICON_R)
            canvas.restore()
        }

        /**
         * 画一个"环形箭头"（⟳）：一段 270° 的弧 + 弧终点处的一个三角箭头。
         *
         * 几何全部按极坐标算，不写死像素 —— 弧的起点固定在 45°（右下），
         * 顺时针扫 270° 到 315°（右上），缺口留在右侧，箭头指向"顺时针的下一个位置"。
         */
        private fun drawRotateIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val w = iconPaint.strokeWidth

            iconPath.reset()
            iconRect.set(cx - r, cy - r, cx + r, cy + r)
            // canvas 角度：0° = 3 点钟方向，顺时针为正
            iconPath.addArc(iconRect, START_DEG, SWEEP_DEG)
            iconPaint.color = palette.icon
            canvas.drawPath(iconPath, iconPaint)

            // 箭头：贴在弧的**终点**，指向切线方向（= 顺时针前进方向）
            val rad = Math.toRadians((START_DEG + SWEEP_DEG).toDouble())
            val tipX = cx + (r * cos(rad)).toFloat()
            val tipY = cy + (r * sin(rad)).toFloat()
            // 切线（顺时针为正）：(-sin θ, cos θ)
            val tanX = (-sin(rad)).toFloat()
            val tanY = cos(rad).toFloat()
            // 法线
            val norX = -tanY
            val norY = tanX

            val head = r * 0.62f
            val halfSpan = head * 0.62f
            arrowPath.reset()
            arrowPath.moveTo(tipX + tanX * head, tipY + tanY * head)
            arrowPath.lineTo(tipX + norX * halfSpan, tipY + norY * halfSpan)
            arrowPath.lineTo(tipX - norX * halfSpan, tipY - norY * halfSpan)
            arrowPath.close()
            arrowPaint.color = palette.icon
            canvas.drawPath(arrowPath, arrowPaint)

            // 同色小圆盖住"弧末端 → 箭头"之间的接缝，避免出现缺口
            canvas.drawCircle(tipX, tipY, w * 0.5f, arrowPaint)
        }

        /**
         * 触摸分发。**这里只认「点」，不认「划」**。
         *
         * ★★ 2026-09-28 追加（方案 E，用户拍板）：`ACTION_UP` 之前先看位移。
         *
         * 为什么必须加：`ACTION_DOWN` 是**无条件 `return true`** 的（没有命中测试），
         * 而这个窗口又盖在右下角 54~62dp 的方块上。于是会有这样一条路径 ——
         * 用户从这块区域**起手**做一次滑动（滚动列表、边缘手势、拖拽），
         * 手势整个被本窗口吃掉，抬手时 `ACTION_UP` 直接就把屏转了。
         * 用户的主观感受是「我没点它，屏幕自己转了」——**误触发**。
         *
         * 判据用平台标准的 [touchSlop]（`scaledTouchSlop`，通常 8dp）：位移超过它
         * 就**不再算点击**，抬手只收状态、不回调。为什么不自己定个阈值：
         * 这个值本来就是"系统认为的手指抖动上限"，各厂商/各尺寸下已经调好，
         * 我们另立一套只会在某些设备上和系统手势的手感打架。
         *
         * ⚠️ 与 [pressed] 的关系：一旦判定为"在滑动"，就把按下态**取消**
         *   （按钮回到常态底色）—— 这是给用户的即时反馈："你这一下不算点击"，
         *   否则按钮会一直保持按下色直到抬手，看着像卡住了。
         *
         * ⚠️ 已知且**刻意保留**的行为：窗口内那圈透明余量（外屏约 5dp）仍然吞触摸。
         *   Android 的输入派发只找"含有该点的最上层可触摸窗口"，**不存在**
         *   "这个窗口没处理就转给下面"的兜底 —— 所以想把透明圈"让"给下面的应用
         *   是做不到的，只能靠缩小窗口，而那会让触摸目标掉到 48dp 以下。
         *   取舍见 `docs/断触排查_2026-09-28.md` 第八节。
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    moveCount = 0
                    maxDist = 0f
                    pressed = true
                    invalidate()
                    // 这一行同时是"新代码是否真的在跑"的指纹，并把实际阈值量出来 ——
                    // 阈值是平台给的（会随密度/厂商变），出问题时它是第一个要看的数。
                    Log.i(TAG, "触摸：按下 (${event.x.toInt()}, ${event.y.toInt()})，判定阈值 ${touchSlop}px")
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveCount++
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist > maxDist) maxDist = dist
                    // 只在第一次越过阈值时改状态，之后不必重复算
                    if (!moved && dist > touchSlop) {
                        moved = true
                        pressed = false
                        invalidate()
                        Log.i(TAG, "触摸：滑动（位移 ${dist.toInt()}px > ${touchSlop}px）⇒ 本次不算点击")
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    moved = false
                    invalidate()
                    Log.i(TAG, "触摸：被系统取消（收到 $moveCount 个 MOVE，最远 ${maxDist.toInt()}px）⇒ 不算点击")
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasTap = !moved
                    pressed = false
                    moved = false
                    invalidate()
                    // ★ 滑动起手 ⇒ 什么都不做：不转屏、也不收按钮
                    //   （按钮还在倒计时里，用户可能抬手后接着点它）
                    if (!wasTap) {
                        Log.i(TAG, "触摸：抬起 —— 滑过（$moveCount 个 MOVE，最远 ${maxDist.toInt()}px）⇒ 忽略，不转屏")
                        return true
                    }
                    Log.i(TAG, "触摸：抬起 —— 未滑动（$moveCount 个 MOVE，最远 ${maxDist.toInt()}px）⇒ 计为点击")
                    // ★ 先收按钮再回调：回调里会写方向，屏幕可能立刻转，
                    //   窗口留在屏幕上会跟着闪一下。
                    val t = targetRotation
                    hide()
                    runCatching { onTap(t) }.onFailure { Log.w(TAG, "点击回调异常", it) }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /** 无障碍要求：点击必须经 performClick 上报 */
        override fun performClick(): Boolean = super.performClick()
    }

    private companion object {
        const val TAG = "HyperPlusSemiHint"

        /**
         * 系统窗口权限的名字，**只能写字面量**。
         *
         * ⚠️ 别改成 `Manifest.permission.INTERNAL_SYSTEM_WINDOW` —— 它是 `@hide` 常量，
         *   公开 SDK 里没有，编译期直接报 `Unresolved reference`（2026-09-28 实测踩过）。
         *   拼错的名字不会崩，只会让 [reportsSystemWindowPermission] 恒为 false（探测失真），
         *   而候选列表**不受探测影响** ⇒ 最多是多一条误导性的日志，功能本身照常。
         */
        const val INTERNAL_SYSTEM_WINDOW_PERMISSION = "android.permission.INTERNAL_SYSTEM_WINDOW"

        /**
         * `WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL` 的**字面量**（`@hide` ⇒ 公开 SDK 取不到）。
         *
         * 值 = 2017，冷冰冰一个数字，所以这里必须留理由：**为什么是它**。
         * 一句话：**层号 181000 高于状态栏 151000，而且是可触摸的类型**。
         * 完整取舍（含"为什么不用 231000 的 `TYPE_SYSTEM_OVERLAY`"和全机实测表）
         * 见 [typeCandidates]。
         *
         * ⚠️ 它需要 `INTERNAL_SYSTEM_WINDOW`（引擎跑在 SystemUI 里，实测持有）；
         *   拿不到时会自动降级到 `TYPE_APPLICATION_OVERLAY`，按钮不会消失。
         */
        const val TYPE_STATUS_BAR_SUB_PANEL_HIDDEN = 2017

        // ⚠️ 曾经的 `DURATION_MS = 3_000L` 已移出本文件：等待时长现在是**用户可配**的
        //   （1~60 秒滑条），读点在 [RotateHintOverlay.durationMs]，边界与默认值在
        //   `AppPrefs.HINT_MS_*`。这里不留常量 —— 留一个读不到配置变化的死值就是坑。

        const val ENTER_MS = 160L

        // ---------------------------------------------------------- 尺寸（按屏宽自适应）

        /*
         * ★ 尺寸策略（参考屏宽 608dp、上限 62dp、下限 54dp、比例 = 62/608、触摸下限 48dp）
         *   **全部搬去了 [HintSizePolicy]**（2026-09-29）——
         *
         *   ① 它是纯算术，但只有**把内屏和外屏两档同时代入**才看得出对错：2026-09-28 就出过
         *      "密度写错 ⇒ 两块屏双双撞上同一上限 ⇒ 自适应静默失效"这种错。搬进不依赖
         *      Android 的文件后就能进 JVM 单测，两档结果由 `HintSizePolicyTest` 钉住。
         *   ② 这里原本还有一份副本，而 [hintSize] 用的正是本地副本 —— 一旦有人只改一处，
         *      就会出现"日志说 62dp、实际画 54dp"这种两边看着都对的状态。
         *   ⇒ 全工程只留 [HintSizePolicy] 一处定义，这里刻意**不留常量别名**，
         *     从结构上排除第二条真相。
         */

        /**
         * 距屏幕右边缘 / 下边缘的距离。**一个常量管两条边** —— 用户 2026-09-28 明确要求
         * 「右下角，且两条边的留白必须一致」，所以这里不给它们各自留参数，
         * 从结构上排除"下次改一个忘了另一个"的可能。
         *
         * ★ 14dp 的来历：这是上一轮从 116dp 降到 14dp 后**用户认可的"贴边"观感**
         *   （原话"没有贴边"是对 116dp 说的）。本轮只把右边距从 10dp 对齐到 14dp。
         *
         * ★ 2026-09-28 加了尺寸自适应之后**刻意没有跟着缩放**：用户的要求是
         *   "两条边留白一致"，一个固定值天然满足；而把它也按比例缩会引入第二个变量，
         *   让"贴边"这件事重新变得不可预期。14dp 在外屏（425dp 宽）占 3.3%、
         *   内屏（608dp 宽）占 2.3%，两者观感都仍然是"贴着边"。
         *
         * ⚠️ 14dp 落在系统手势条（约 16dp）的高度里，但**上滑手势只吃滑动、不吃单击**，
         *   而 `FLAG_NOT_TOUCH_MODAL` 保证按钮范围内的触摸优先给本窗口 ⇒ 贴边不会点不动。
         */
        const val MARGIN_DP = 14

        // 几何比例（相对 View 边长）—— 全按比例算，与屏幕密度/分辨率无关
        //
        // ★ 2026-09-28 第二次调（用户点名：「倒计时条要被按钮背景包裹在里面」）：
        //   旧比例是"环在外、底在内"（RING_R 0.45 > BTN_R 0.345），倒计时环**露在白色
        //   圆底外面**一整圈。现在反过来 —— 环内缩、底放大：
        //     环外缘 = RING_R + RING_W/2 = 0.345 + 0.023 = 0.368
        //     圆底   = BTN_R                  = 0.45
        //   0.368 < 0.45 ⇒ 整圈环都落在白底里面，视觉上就是"倒计时条被按钮包着"。
        const val RING_R = 0.345f
        const val RING_W = 0.046f
        const val BTN_R = 0.45f
        const val ICON_W = 0.048f

        /**
         * 旋转图标（弧 + 箭头）的半径。
         *
         * ⚠️ 硬约束：**必须小于环的内缘**（`RING_R - RING_W/2` = 0.322），否则箭头会压在
         *   倒计时环上。箭头还会沿法线外扩 `ICON_R * 0.62 * 0.62`，所以真正要满足的是
         *   `ICON_R * (1 + 0.3844) < 0.322` ⇒ ICON_R < 0.232，取 0.205 留出余量。
         */
        const val ICON_R = 0.205f

        /** 图标弧：45° 起、顺时针 270° */
        const val START_DEG = 45f
        const val SWEEP_DEG = 270f

        /**
         * 「配置变了之后，再确认一遍坐标」的延时。
         *
         * ★ 为什么需要第二次：折叠屏展开/收拢是**带过渡动画**的，`onConfigurationChanged`
         *   回调触发的那一刻尺寸往往还在中间态；本机内屏 1672×2364、外屏 1168×1712，
         *   中间态可能两个都不是。300ms 是折叠动画的量级，落定后再钉一次。
         * ⚠️ 幂等：尺寸没变时 `updateViewLayout` 不产生任何位移。
         */
        const val RELAYOUT_SETTLE_MS = 300L

        // ------------------------------------------------------------ 玻璃材质（2026-09-29）

        /**
         * 上缘高光渐变的**终点**位置（相对可见圆半径，从圆心往下为正）。
         *
         * ★ 0.2 的含义：高光从圆顶（`-1.0 R`）一路化到 `+0.2 R`（圆心略下方）就已经全透明。
         *   ⇒ 亮区集中在上面 60% 左右，下面基本没有 —— 真实玻璃就是边缘聚光，不是整颗发亮。
         * ⚠️ 调到 1.0（铺满整圆）会变成"上白下透的渐变色块"，看着像没上好色的按钮。
         */
        const val SHEEN_END_FRACTION = 0.2f

        /**
         * 光晕描边的线宽（相对可见圆边长）。
         *
         * ★ 0.022 ⇒ 内屏（可见圆 62dp）上约 1.36dp、外屏（54dp）上约 1.19dp。
         *   刻意**比 1dp 略粗**：细于 1px 的描边在部分密度下会被抗锯齿吃掉，
         *   那圈"玻璃厚度"就时有时无。而再粗就开始像"给按钮加了个圈"，不像玻璃边缘。
         */
        const val EDGE_W = 0.022f

        /**
         * 描边**下缘**的残留不透明度（**绝对值**，直接当 alpha 用）。
         *
         * ★ 不给 0：下缘全透明的话，按钮压在浅色背景上时下半圈会"没有边界"，
         *   看起来像被削平了一块。留 25% 让它在任何背景上都能收住边。
         * ⚠️ 这里**不是**"取 [HintPalette.specular] alpha 的 25%"——是绝对值 25%，
         *   所以深浅色下缘一样亮（上缘才有 37% / 28% 的差别）。刻意如此：
         *   下缘只是"收边"，不需要跟着主题变。
         */
        const val EDGE_BOTTOM_ALPHA = 0.25f

        // ⚠️ 配色**不在这个 companion 里** —— 它有深浅两套，住在文件级的 [HintPalette]，
        //   由 [HintView] 按系统当前深浅色选一套（理由见那边的注释）。
    }
}

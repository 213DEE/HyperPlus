package cn.dsr213.hyperplus

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import cn.dsr213.hyperplus.ForegroundGate.StopReason
import android.util.Size
import android.view.Display
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import cn.dsr213.hyperplus.trigger.DeviceOrientationTrigger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 自适应旋转引擎。
 *
 * 数据流（相机不常开，而是「触发时 burst」）：
 *   DeviceOrientationTrigger（硬件 on-change，零轮询）
 *        ↓ 只在朝向真的变化时回调
 *   四道让步闸门（G1 事前查占用 / G2 绑定失败即放弃 / G3 持有期被抢即让位 / G4 采完无条件释放）
 *        ↓ 通过了才开相机
 *   burst 采集 ≈16 帧（约 1.0s）
 *        ↓ OrientationDecider 判定（绝对映射）
 *   写 user_rotation（与实际显示方向不一致时才写）
 *
 * ============================================================================
 * ★ 2026-09-25 三处结构性改动
 * ============================================================================
 *
 * **A. 基准绝对化**：分析器的旋转基准从 `imageInfo.rotationDegrees`（参考系是
 *    「绑定相机那一刻的显示方向」，实测不跟设备本体走）改为
 *    `CameraCharacteristics.SENSOR_ORIENTATION`（硬件常量）。从此 roll 只表示
 *    「人脸相对设备自然方向的倾斜」，与屏幕转成什么样解耦。
 *
 * **B. 接管语义合并（用户拍板的方案 b）**：`takeoverOn` 不再是独立的手动开关，
 *    而是**跟着模式走** —— 切到 ADAPTIVE 自动接管（关系统自动旋转 + 由人脸写方向），
 *    切回 SYSTEM 自动交还。同时把「接管前的原值」与「是否正在接管」都落盘，
 *    进程被强杀后下次启动能检测到**孤儿接管**并自动还原（否则系统自动旋转
 *    会永久停在关闭状态 —— 这是实测踩过的事故）。
 *
 * **C. 方向标定**：sign / offsetDeg 不再硬编码猜测，由用户两步点按确定并持久化。
 *    见 [applyCalibrationBaseline] / [applyCalibrationAxis]。
 */
class AdaptiveEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /**
     * 致命异常的回调（可选）。
     *
     * ★ 为什么需要它：本引擎现在也会跑在 **SystemUI 进程**里（见 `EngineHost`）。
     *   在那边，任何未捕获异常都会杀掉整个进程 —— 用户看到的是状态栏/导航栏崩掉。
     *   引擎内部已经用 `CoroutineExceptionHandler` + 线程级 handler 双层兜住，
     *   兜住之后把异常转交给这个回调，由宿主决定怎么收场（宿主实现：一次异常即停引擎）。
     *   在 App 进程里默认为 null，行为与以前一致（只记日志）。
     */
    private val onFatal: ((Throwable) -> Unit)? = null,
) {

    // ============================================================ UI 状态

    data class UiState(
        val mode: RotateMode = RotateMode.SYSTEM,
        val strategy: CaptureStrategy = CaptureStrategy.POWER_SAVING,

        // —— 触发层 ——
        val sensorAvailable: Boolean = false,
        val triggerCode: Int = -1,
        val triggerCount: Int = 0,
        val suppressedCount: Int = 0,

        // —— 让步统计 ——
        val skipOccupiedCount: Int = 0,
        val skipBusyCount: Int = 0,

        // —— burst 统计 ——
        val burstCount: Int = 0,
        val lastOpenMs: Long = -1,      // 从 bind 到第一帧
        val lastBurstMs: Long = -1,
        val lastBurstFrames: Int = 0,

        // —— 判定 ——
        val eulerZ: Float = Float.NaN,
        val eyeRoll: Float = Float.NaN,
        val roll: Float = Float.NaN,
        val rotation: Int = -1,
        val deciderState: String = "UNKNOWN",
        val switchCount: Int = 0,

        // —— 本轮「多帧投票」——
        //  ★ 为什么要有这一组：单帧判定会被"侧脸 / 运动模糊"带偏一格（用户体感 =
        //    「人脸识别和旋转方向不一致」）。一轮本来就要采十几帧，那就**按票数定方向**，
        //    而不是让最后一帧说了算。见 [tallyVote]。
        /** 每格票数，形如 `0:1 3:9`（只列出得票 > 0 的格） */
        val voteCounts: String = "",
        /** 有效票数（有脸且过了置信度闸门的帧数） */
        val voteValid: Int = 0,
        /** 得票最多的方向（-1 = 本轮还没有票） */
        val voteWinner: Int = -1,
        /** 获胜方得票占比 0..1 */
        val voteRatio: Float = 0f,
        /** 票数是否已足以定论（见 [voteConfident] 的判据） */
        val voteConfident: Boolean = false,

        // —— 重力当尺子：自动校验人脸链路的符号位（见 [OrientationFusion]）——
        /**
         * 重力算出的**参照扇区**（0..3）；-1 = 这一路当前不可用
         * （没注册到重力传感器 / 读数太旧 / 手机完全平放）。
         *
         * ⚠️ 它**不参与方向运算**（为什么不能相加，见 [OrientationFusion] 的类注释）。
         *   它只做一件事：当尺子，判"人脸链路的符号位到底该是 +1 还是 -1"。
         */
        val gravitySector: Int = -1,
        /** 符号位校验：实测扇区**等于**重力扇区的分辨帧数（= 当前符号位是对的） */
        val signSame: Int = 0,
        /** 符号位校验：实测扇区等于**镜像**重力扇区的分辨帧数（= 当前符号位反了） */
        val signFlip: Int = 0,
        /** 是否已**被数据验证**过（而不是"用户点过校准按钮"）—— 供界面如实显示 */
        val signConfirmed: Boolean = false,

        // —— 相机/接管 ——
        val cameraHeld: Boolean = false,
        /**
         * 当前正在用的**前摄 id**（如 "1" / "5"）。
         *
         * ★ 2026-09-25 新增：这台机器有 **5 个前摄**（`1,5,7,8,9`，HAL 原文确认
         *   `Facing: Front / Orientation: 270 / 无闪光灯`），旧代码写死
         *   `CameraSelector.DEFAULT_FRONT_CAMERA`（= 默认前摄 id "1"）⇒ 一旦 id "1"
         *   被 `com.miui.aoc` 挡住，整个引擎就没牌可打。现在按 [frontIdOrder] 轮换。
         */
        val cameraId: String = "",
        val occupiedByOthers: Boolean = false,
        /**
         * 被**其他客户端**占着、导致我们这一轮采不到样的次数（"冲突"）。
         *
         * 实测抢我们的是 `com.miui.aoc` —— 小米 AON（注视感知 / 智能扫码）那条链上的
         * 常开取像进程（`/odm/bin/hw/misensor_camera`）。`dumpsys media.camera` 里的原文：
         * ```
         * REJECT device 1 client for com.android.systemui: Too many cameras already open, cannot open camera "1"
         * DENIED connect device 1 (PID 12358, score 1001 state 6) due to eviction policy
         *  - Blocked by existing device 0 client for package com.miui.aoc (PID 19801, score 200, state 1)
         * ```
         * ★ 2026-09-25 订正：这个计数**只在"一轮 burst 一帧都没收到"时** +1
         *   （见 [startBurst] 的超时分支）。原先挂在这里的
         *   "`onCameraUnavailable` 里 +1"实际上**永远不触发** —— 等帧期间
         *   `selfHolding == true`，那条回调被我们自己挡掉了。所以此前界面上的
         *   `conflict=0` 是假数据（真机明明在拒我们）。
         */
        val conflictCount: Int = 0,
        val takeoverOn: Boolean = false,
        val writeSettingsGranted: Boolean = false,

        // —— 前台门控（2026-09-28）——
        /** 是否因前台应用自己声明朝向而停手（不开相机 / 不写方向） */
        val foregroundGated: Boolean = false,
        /**
         * **最近一次**前台巡检读到的包名（不进门前也有值）。
         *
         * ⚠️ 2026-09-29 改语义：原先是"触发停手的包名（仅门控中有意义）"，
         *   结果**没停手时这一格永远是空的** —— 用户报「外屏抖音弹按钮但转不动」时，
         *   `fgpkg=` / `fgori=` 全空，等于把"引擎到底看到了什么"这个最关键的问题
         *   关在了门外（详情见 [foregroundForm]）。现在它与 [foregroundStop] 分工：
         *   前两格 = **读数**（一直在更新），后者 = **结论**（只有停手时才有值）。
         */
        val foregroundPkg: String = "",
        /** **最近一次**前台巡检读到的朝向（可读名，如 `SENSOR_LANDSCAPE`） */
        val foregroundOrientation: String = "",
        /**
         * 判据用的是哪块屏的名单（`OUTER` / `INNER`；空 = 还没判过）。
         *
         * ★ 2026-09-29 加：三条豁免里 ② 外屏桌面、③ 外屏声明**都带 `form == OUTER` 前置**，
         *   所以"判据没命中"可能只是因为形态读错了（比如 App 在后台时窗口指标过期）。
         *   没有这一格就分不清"朝向我读错了"还是"形态我读错了"。
         */
        val foregroundForm: String = "",
        /**
         * 停手是哪条豁免造成的（`ForegroundGate.StopReason` 的名字；空 = 没在停手）。
         *
         * ★ 为什么要报它：三条豁免的**性质完全不同** —— 白名单是用户自己勾的，
         *   另外两条是系统约束（用户改不了）。诊断时"为什么这个应用不转"的答案
         *   就在这三格里，而它们的**现象一模一样**（都不弹按钮）。
         */
        val foregroundStop: String = "",
        /** 前台朝向读得到吗；false ⇒ 门控未生效（保持原行为） */
        val foregroundReadable: Boolean = false,
        /** 因前台门跳过的触发次数 */
        val skipGateCount: Int = 0,
        /** 停手时是否交还系统自动旋转（镜像用户开关，供界面显示） */
        val handoffRotate: Boolean = true,
        /** 前台门控**总开关**（用户可关；关掉 = 永不因前台朝向停手） */
        val gateEnabled: Boolean = true,

        // —— 应用白名单（2026-09-28 用户拍板，取代"读声明朝向去推"）——
        //
        // ⚠️ 这里**刻意没有** `whiteStop` / `whiteStopPkg` 这样的"白名单口径"字段：
        //   它们的值恒等于 [foregroundGated] / [foregroundPkg]（停手就是由白名单命中的），
        //   多一对同值字段只会多一个会漂移的真值来源。要区分"为什么停手"用现成的三格就够：
        //   `foregroundReadable`（读不到前台）/ `gateEnabled`（总开关关掉）/ `foregroundGated`。
        /**
         * **生效白名单**的条数（默认清单 ∪ 用户加的 − 用户关的，再并上恒豁免的自己）。
         *
         * ★ 为什么要报条数：用户在界面上看到的那份列表是"**已安装**应用"，
         *   与生效集合**不是一回事**（默认清单里没装的包也在集合里，见 `AppWhitelist.resolve`）。
         *   排查时第一件要对的就是"引擎手上到底有多少条"。
         */
        val whitelistSize: Int = 0,

        // —— 半自动模式（2026-09-28）——
        /** 半自动按钮累计弹出次数 */
        val semiShownCount: Int = 0,
        /** 最近一次弹出时的目标方向（-1 = 还没有过） */
        val lastSemiTarget: Int = -1,
        /** 用户点半自动按钮「确认旋转」并成功写方向的累计次数 */
        val semiTappedCount: Int = 0,
        /**
         * 屏幕上**此刻**是否挂着旋转按钮。
         *
         * ★ 为什么要有这个字段（2026-09-29 用户点名要查）：
         *   用户问「短时间内反复旋转，会不会**同时存在好几个按钮**」。
         *   代码侧的分析结论是不会 —— [RotateHintOverlay.show] 对"已在显示"是幂等地
         *   原地换目标（不重建窗口），`hide()` 又会把 `view` 字段清空，而引擎是单例
         *   （`EngineHost` 用 `engine != null` 守着）。但"分析过"不等于"观测过"：
         *   这一格就是**让用户自己在真机上验证**的那个读数 ——
         *   它只可能是 true / false，**不可能出现 2**。
         * ⚠️ 它是**采样值**（在每次触发 + 每 [FOREGROUND_POLL_MS] 巡检时刷新），
         *   不是实时的 —— 按钮自己倒计时消失后，最坏情况下要等一个巡检周期才变成 false。
         */
        val hintAlive: Boolean = false,

        /**
         * 引擎**实测**到的最小边宽度（dp）—— 形态判据的原始输入。
         *
         * ★ 为什么单独报这一个数（2026-09-29 用户报「内屏桌面旋转没反应」时加的）：
         *   [foregroundForm] 报的是 `AppPrefs.screenForm`（**已采纳**的形态），
         *   而那次事故的成因恰恰是"采纳值陈旧" —— 只看 `fgform` 只能得出
         *   「引擎认为自己是外屏」，看不出**它到底量到了多少**。有这个数就能一眼分辨
         *   两种完全不同的故障，而它们的修法完全不同：
         *     · 量到 608dp（内屏）却仍写 `OUTER` ⇒ **采纳逻辑**坏了（本次就是这个：没人重测）；
         *     · 在内屏上量到 425dp ⇒ **测量本身**错了（读到了另一块屏）。
         * ⚠️ 与 [hintAlive] 一样是**巡检采样值**（每 [FOREGROUND_POLL_MS] 刷一次）。
         */
        val formProbeDp: Int = 0,
        /**
         * 悬浮窗是否可用。
         *
         * ★ 半自动的按钮**只能**靠悬浮窗弹出来，所以这个字段是"半自动到底能不能用"
         *   的唯一诚实答案。false = 还没弹过，或弹的时候被 WindowManager 拒了
         *   （单机模式下多半是没给「显示在其他应用上层」）。
         */
        val overlayUsable: Boolean = false,

        /**
         * 半自动按钮**实际生效的窗口类型**（`WindowManager.LayoutParams` 里的类型号；
         * -1 = 还没弹过）。
         *
         * ★ 2017 = `STATUS_BAR_SUB_PANEL`（层号 181000，高于状态栏 ⇒ 按钮不会被遮，
         *   而且该类型可触摸 ⇒ 点得动）；
         *   2038 = `TYPE_APPLICATION_OVERLAY`（层号 111000，低于状态栏 ⇒ 角上会被挡）。
         *   2006 = `TYPE_SYSTEM_OVERLAY` —— **已弃用**：层号 231000 够高，但系统会强制
         *   给它 `NOT_TOUCHABLE`（按钮弹得出来、点不动），只可能出现在旧日志里。
         *   这两档的差别**肉眼不一定看得出来**（都要转到位才暴露），所以必须可观测 ——
         *   见 `RotateHintOverlay.activeWindowType` 的注释。
         */
        val overlayType: Int = -1,

        // —— 方向标定 / 诊断 ——
        val sensorOrientation: Int = -1,
        /**
         * 当前屏幕旋转（`Display.getRotation()`，**不做任何换算**）。
         * 2026-09-29 起只作诊断展示 —— 判"要不要写方向"用的是 `USER_ROTATION` 的读盘值。
         */
        val displayRotation: Int = 0,
        /**
         * 本屏的**安装朝向偏移**（0 = 外屏那一类 / 2 = 内屏那一类）。真机实测外 0 / 内 2。
         *
         * ⚠️ 2026-09-29 起它**不再参与写方向** —— 上一版拿它去换算，正是"每次旋转方向
         *   完全相反"的元凶（见 [writeUserRotation] 的注释）。现在只剩两个用途：
         *   ① 诊断展示（总线 `pofs`）；② 换屏搬运方向时的增量（[onScreenFormChanged]）。
         */
        val panelOffset: Int = 0,
        val calibSign: Int = 1,
        val calibOffsetDeg: Float = 0f,
        val calibrated: Boolean = false,
        /** 标定第 1 步采到的基准 roll（未设置 = NaN） */
        val calibBaselineRoll: Float = Float.NaN,
        val calibrating: Boolean = false,

        // —— 运行时长统计 ——
        val totalFrames: Long = 0,
        val facesFound: Long = 0,

        val events: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ============================================================ 内部件

    /**
     * ★ 双层异常兜底（2026-09-25 新增，为「引擎跑进 SystemUI」而加）：
     *
     *  1) [CoroutineExceptionHandler] —— 兜住 scope 里所有协程的未捕获异常。
     *     没有它时，`scope.launch` 里一抛异常就会冒到线程的默认处理器，
     *     在 SystemUI 进程里等于**直接把状态栏搞崩**。
     *  2) [ThreadFactory] 给分析线程装 `setUncaughtExceptionHandler` —— 兜住相机帧
     *     回调（[onFrame]）这条**不走协程**的路径。
     *
     * 两处都只对自己**引擎自己的线程/协程**生效，不碰进程全局处理器，
     * 也不会误吞 SystemUI 其他子系统的异常。
     */
    private val fatalGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "协程内未捕获异常（已拦截，不让它杀进程）", e)
        runCatching { onFatal?.invoke(e) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + fatalGuard)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread(r, "hyperplus-engine").apply {
                setUncaughtExceptionHandler { _, e ->
                    Log.e(TAG, "引擎线程未捕获异常（已拦截，不让它杀进程）", e)
                    runCatching { onFatal?.invoke(e) }
                }
            }
        },
    )

    /**
     * ★ CameraX 的 bind/unbind **必须在主线程调用**（2026-09-25 实测栈坐实）：
     *
     * ```
     * java.lang.IllegalStateException: Not in application's main thread
     *   at androidx.camera.core.impl.utils.Threads.checkMainThread(Threads.java:55)
     *   at androidx.camera.lifecycle.ProcessCameraProvider.bindToLifecycle(…:558)
     *   at AdaptiveEngine.startBurst(…)
     *   at AdaptiveEngine$captureCalibrationSample$1.invokeSuspend(…)   ← 协程里调的
     * ```
     *
     * 后果分两种：
     *  - **抛异常**：`bindToLifecycle` 直接失败 ⇒ 校准点按钮后永远采不到脸
     *    （用户看到的正是「方向校准提示没有检测到人脸」）。
     *  - **静默吞掉**：`unbind` 外面套了 `runCatching` ⇒ 释放没真的发生，
     *    CameraX 内部 use-case 状态越积越多。
     *
     * 所有对 provider 的 bind/unbind 一律经 [onMain] 投到主线程，
     * 借用主线程 Looper 天然保证「先 unbind 后 bind」的顺序。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private val decider = OrientationDecider()
    private val recorder = CsvRecorder(context)

    private var trigger: DeviceOrientationTrigger? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: FaceAnalyzer? = null
    private var availabilityCb: CameraManager.AvailabilityCallback? = null

    /** 我们当前是否正持有相机 —— 用来把「自己的占用」从 AvailabilityCallback 里剔除 */
    @Volatile private var selfHolding = false

    /** 是否真的被别人占用（burst 之外的时刻收到 unavailable 才置位） */
    @Volatile private var occupiedByOthers = false

    /**
     * [occupiedByOthers] 被置 true 的时刻（`elapsedRealtime`）。
     *
     * ★ 2026-09-25 新增：这个标志位只靠 AvailabilityCallback 的**跳变**更新，
     *   而跳变可能被我们自己的挡板吃掉（`selfHolding==true` 时无视回调）
     *   ⇒ 它会**永久停在 true**，之后每次触发都"让步"，屏幕彻底没反应。
     *   [onTriggered] 用这个时刻给它加保质期（[OCCUPANCY_STALE_MS]）。
     */
    @Volatile private var occupiedSinceMs = 0L

    /** 统一入口：置位占用（并记时刻），避免各处漏记 */
    private fun markOccupiedByOthers() {
        occupiedByOthers = true
        occupiedSinceMs = SystemClock.elapsedRealtime()
        _ui.update { it.copy(occupiedByOthers = true) }
    }

    /** 统一入口：解除占用 */
    private fun clearOccupiedByOthers() {
        occupiedByOthers = false
        occupiedSinceMs = 0L
        _ui.update { it.copy(occupiedByOthers = false) }
    }

    @Volatile private var bursting = false
    private var burstStartedAt = 0L
    private var burstBindRequestedAt = 0L
    @Volatile private var burstFrameCount = 0

    /**
     * ★★ **本轮采样里有没有出现过"一帧可用的人脸"**（R1，2026-10-05）。
     *
     * 判据 = [pickRoll] 给了非 null 的结果（脸数 > 0、不是低置信、角度可读）。
     * ⚠️ **刻意复用 `pickRoll`**（就是 [onFrame] 里那个 `roll`），不另立标准 ——
     *   两套口径迟早分叉，那时会出现"按钮弹了、界面却显示还在自适应"这种谁都没错的怪状态。
     * 生命周期与 [burstFrameCount] 完全一致：[startBurst] 清、[onFrame] 置、[finishBurst] 读。
     */
    @Volatile private var burstHadUsableFace = false

    // ---- R1（2026-10-05）：自适应读不到环境时**临时**降级半自动 -------------------
    //
    // ★ 整份状态是**一个不可变对象 + 一个 `@Volatile` 引用**（而不是四个散字段）：
    //   它写在 [finishBurst]（分析线程）、读在 [mode]（传感器线程 / 主线程都会调）。
    //   并成一份之后**不会读到"半更新"**（比如 `downgraded` 已置 true、探测窗口还没推）。
    //   状态转移本身是纯逻辑，在 [AdaptiveFallback] 里 —— 那个有单测。
    //
    // ⚠️ 它**只描述引擎自己的临时状态**，与用户配的那一档毫无关系：
    //   降级**不写** `AppPrefs`（见 [mode] 那条注释）。
    @Volatile private var r1 = AdaptiveFallback.State()

    /**
     * 最近一帧到达的时刻（[SystemClock.elapsedRealtime]），只在 [onFrame] 里更新。
     *
     * ★ 用途：**判断"我们还握着相机"这句话还成不成立**（见 [sessionLooksDead]）。
     *   相机被系统常开服务（`com.miui.aoc`）踢掉时，**没有任何回调会通知我们** ——
     *   唯一的事实是"帧不再来了"。所以把"最后一帧的时间"记下来，就能把它变成可判定的量。
     *   0 = 这个进程还从没收到过帧（此时不做判断，否则会误杀）。
     */
    @Volatile private var lastFrameAtMs = 0L

    /**
     * 「相机保温期」的延迟释放任务（见 [scheduleCameraRelease]）。
     *
     * 省电优先下，一轮 burst 结束**不立刻**关相机，而是等 [CAMERA_KEEP_WARM_MS]
     * 内没有新一轮触发才关 —— 连续摆姿势时省下每次 ~220ms 的开流等待。
     */
    private var keepWarmJob: Job? = null

    // ============================================================ 前台门 / 白名单（2026-09-28）

    /** 读「前台 activity 的包名（+ 声明朝向，仅诊断）」；懒建。只有宿主（SystemUI）才读得到别人的任务栈 */
    private val fgProbe: ForegroundProbe by lazy { ForegroundProbe(context) }

    /** 是否因前台应用**在白名单里**而停手（不采帧 / 不弹按钮 / 不写方向） */
    @Volatile private var fgGated = false

    /**
     * 停手时是否**真的**把系统自动旋转交还过（还原 `accelerometer_rotation=1`）。
     * ★ 只有交还过才需要"拿回来" —— 与用户开关 [AppPrefs.handoffRotate] 是两件事：
     *   开关是意愿，这个是**实际发生过的动作**（开关关着时它就是 false）。
     */
    @Volatile private var fgHandedOff = false

    /**
     * 「解除门控后补判一次」的重入闸。
     *
     * ★ 为什么必须防重入：补判的实现就是直接调 [onTriggered]，而 [onTriggered] 的第一件事
     *   又是 [refreshForegroundGate] → 可能再进 [leaveForegroundGate]。
     *   没有这道闸就变成 `onTriggered → leave → onTriggered → leave → ...`。
     *   （实际上 `leaveForegroundGate` 开头的 `if (!fgGated) return` 已经能截断，
     *    但显式闸门更清楚，也让"这段不会无限递归"成为可读的事实而不是推理。）
     */
    @Volatile private var gateRejudging = false

    /** 前台门巡检协程 */
    private var fgWatchJob: Job? = null

    // ============================================================ 半自动模式（2026-09-28）
    //
    // ★ 这条链路和自适应**完全分家**：它不需要相机、不需要人脸、不需要投票 ——
    //   只读一次"设备现在是什么姿态"，然后弹个按钮问用户。
    //   所以下面的字段没有一个跟 burst / 相机有关。

    /**
     * 右下角那个圆形悬浮按钮。
     *
     * ★ 刻意**不用 `by lazy`**：`lazy` 会在"只是想 hide 一下"的地方（比如 `stop()`、
     *   模式切走）把对象凭空创建出来。这种"为了收尾反而先创建"的写法在 SystemUI 进程里
     *   是白拿风险（构造里要 `getSystemService`）。改成显式 [ensureOverlay]，
     *   谁真要用谁创建，收尾只碰 [overlayRef]（null 就什么都不做）。
     */
    @Volatile private var overlayRef: RotateHintOverlay? = null

    private fun ensureOverlay(): RotateHintOverlay =
        overlayRef ?: synchronized(this) {
            overlayRef ?: RotateHintOverlay(context) { target -> applySemiRotation(target) }
                .also { overlayRef = it }
        }

    /** 最近处理过的"预览按钮"请求（null = 本次进程还没处理过，见 [showHintForTest]） */
    @Volatile private var lastHintTestReq: String? = null

    /**
     * 弹一次按钮**只为看外观**（用户在界面上点"预览旋转按钮"）。
     *
     * ★ 与半自动那条真实路径**刻意分开**：这里不判姿态、不判白名单、不冷却 ——
     *   它就是"让我看一眼"。按钮本身还是那个按钮，点下去照样走 [applySemiRotation]
     *   （写方向 + 读回），所以它也**不能**当"绕过传感器"用。
     *
     * ⚠️ **冷启动首次只记账**（“首次”语义必须有**独立**的第一状态，不能借值域里的
     *   特殊值冒充 —— 本工程为这个坑失过一次手）：
     *   配置通道的 StateFlow 会重放持久化下来的历史值，而这个键长期停在最后一次点击上
     *   ⇒ 不挡的话，每次软重启 SystemUI 都会在屏幕右下角凭空弹一个按钮。
     *
     * @param req 形如 `"<时间戳>|<目标方向>"`；格式不对就当没收到（不弹、不报错）
     */
    private fun showHintForTest(req: String) {
        if (lastHintTestReq == null) {
            lastHintTestReq = req
            return
        }
        if (req == lastHintTestReq) return
        lastHintTestReq = req
        val reqTarget = req.substringAfter('|', "").toIntOrNull() ?: return
        // ★★ 定位用的目标方向取**设备此刻的真实姿态**，**不用请求里那个数**（2026-09-29 实测修）。
        //
        //   为什么：App 侧没有姿态读数，请求里带的只能是一个**写死的方向**。而按钮落在哪个角
        //   由 `delta = (target − cur) mod 4` 决定 ⇒ 写死的 target 撞上任意 cur 就是任意一个角。
        //   真机上就是这样：target=1、cur=3 ⇒ delta=2 ⇒ 按钮弹在**左上角**，压着状态栏，
        //   看着像"坏了"（用户点"预览"就是为了看外观，结果看到的是一个错位的按钮）。
        //
        //   改用设备自己的姿态 ⇒ `delta == 0` ⇒ 恒落在**当前物理右下角**，也就是真实
        //   半自动路径下用户会看到的那一角。于是"预览"预览的才是**真的那个东西**。
        //   req 里那个数仍然解析（用来校验格式），只是不再参与定位。
        val target = semiTargetRotation() ?: currentDeviceRotation()
        val ok = runCatching { ensureOverlay().show(target) }.getOrElse { false }
        event(
            if (ok) {
                "预览按钮：已弹出（目标 ${rotName(target)}；请求里写的是 ${rotName(reqTarget)}，" +
                    "定位按设备姿态走）—— 只为看外观，点下去仍走真实路径"
            } else {
                "预览按钮：⚠️ 加窗失败（看下面几行悬浮窗类型的日志）"
            },
        )
    }

    /** 半自动的冷却截止时刻：这段时间内不再弹（`elapsedRealtime`） */
    @Volatile private var semiCooldownUntil = 0L

    /** 最近一次弹按钮的目标方向；配合 [semiHintAtMs] 做"同一方向不反复骚扰" */
    @Volatile private var semiLastTarget = -1

    /** 最近一次弹按钮的时刻（`elapsedRealtime`） */
    @Volatile private var semiHintAtMs = 0L

    // —— ★ 姿态稳定确认（2026-09-29，治"180° 弹两次"）——
    //    这三个都**只在主线程读写**（见 [scheduleSemiSettle]），所以刻意不加 @Volatile。

    /** 待确认时记下的触发原因（debounce 用；确认到期时写进日志） */
    private var semiSettleReason = ""

    /** 待确认的任务 —— 存成字段是为了能被 `removeCallbacks` 取消（见 [cancelSemiSettle]） */
    private val semiSettleRunnable = Runnable { confirmSemiHint(semiSettleReason) }

    /** 写方向的**读回代次**：每次排期都 +1，旧任务据此自我作废（只在主线程读写） */
    private var semiReadbackGen = 0

    private var lastRot = -1
    private var lastAppliedRot = -1

    // ============================================================ 多帧投票
    //
    // ★ 设计要点（用户 2026-09-25 明确要求「每一轮识别多次，按命中最多的方向旋转」）：
    //   投票用的是**每帧的瞬时扇区**（`decider.sectorOf(angleOf(smoothed))`），
    //   **不经过状态机的候选/待定格** —— 因为状态机的"保持"会让旧方向天然多拿票，
    //   等于把票投给了过去。瞬时扇区只反映"这一帧看到的脸朝哪"，才是可投票的量。
    //   平滑（EMA）仍然保留，用来压掉单帧抖动。

    /**
     * 本轮计票器（纯逻辑，可离线单测 —— 见 [VoteTally]）。
     * 只在 [burstLock] 里变更，变更后把结果**快照**到下面几个字段，供 UI / 状态上报读。
     */
    private val tally = VoteTally(MIN_VOTES, VOTE_RATIO_MIN)

    // ---- [tally] 的快照（跨线程读这两个之外的东西都可能读到半更新状态）----
    @Volatile private var voteValid = 0
    @Volatile private var voteWinner = -1
    @Volatile private var voteConfident = false
    @Volatile private var voteTextCache = ""
    @Volatile private var voteRatioCache = 0f

    /**
     * 末段共识用的环形缓冲（见 [recentConsensus]）。与 [tally] 同锁变更、每轮清空。
     * 顺序不重要 —— 判据只看"这 [RECENT_VOTES] 票是不是全一样"。
     */
    private val recentSectors = IntArray(RECENT_VOTES)
    private var recentIdx = 0
    private var recentFilled = 0

    /**
     * 符号位校验的累计票（见 [noteSignEvidence]）。
     *
     * 只在**有分辨力的帧**上累加 —— 重力的扇区必须是横屏（1/3），因为竖屏时
     * 两种符号位给出同一个结果，记进去只会稀释信号。
     */
    @Volatile private var signSameCount = 0
    @Volatile private var signFlipCount = 0

    /** 被其他客户端抢走前摄的次数（见 [UiState.conflictCount]） */
    @Volatile private var conflictCount = 0

    /**
     * 相机在别人手里、我们这一轮**没能采到样** ⇒ 等它空出来要自动补采。
     *
     * ★ 为什么不能用定时补试代替（那已经有了）：实测抢我们的一方（`com.miui.aoc`）
     *   占用时长不确定（日志里它自己 8 秒后 Binder 就死了），定时重试总是撞在它还在用的窗口里，
     *   而 `AvailabilityCallback` 是**它松手那一刻**的通知 —— 一次就够，还不费电。
     *   ⚠️ 必须只在"我们**没**拿到相机"时置位，且成功采到后就清掉，
     *      否则我们自己释放相机（省电模式每轮都释放）会触发无限补采。
     */
    @Volatile private var waitingForCamera = false

    /** 标定第 1 步记录的基准 roll */
    private var calibBaselineRoll = Float.NaN

    /** 上次写入 user_rotation 的时刻 —— 用来做写入冷却，见 [applyRotationIfNeeded] */
    private var lastWriteAt = 0L

    /**
     * burst 序号 —— 每次 [startBurst] 自增。
     *
     * ★ 为什么必须有（实测坐实的 bug）：超时协程是 `delay(1500)` 后**无条件**检查
     *   全局的 `bursting`，于是**上一次** burst 的超时会把**当前这一次**误判成
     *   "超时未收齐帧"然后放弃。实测日志里：某次触发后仅 265 ms 就打出了
     *   「burst 超时（1500ms 只收到 1 帧）→ 放弃」—— 1500ms 根本还没到。
     *   后果是随机丢掉一整轮采集（方向不更新 / 偶发判错）。
     *   带上序号后，超时只对"自己那一次"生效。
     */
    private var burstSeq = 0

    /**
     * 本次触发还剩几次补试额度（每次新触发重置为 [MAX_BURST_RETRY]）。
     * 用于"相机一帧都没给"时的自动重采，理由见 [scheduleRetry]。
     */
    @Volatile private var retryLeft = 0

    /**
     * burst 状态机的互斥锁 —— **只用来保护几个标志位的读写**，绝不包住
     * `bindToLifecycle` / `unbind` 这类会回调到 executor 的调用（会互锁）。
     */
    private val burstLock = Any()

    /** 最近 1.5s 的 roll 样本，供标定取中位数 */
    private val recentRolls = ArrayDeque<Pair<Long, Float>>()

    /** 前摄传感器朝向（硬件常量），懒加载 —— 作为图像旋转的固定基准 */
    private val sensorOrientation: Int by lazy { readFrontSensorOrientation() }

    /**
     * ★★★ **前台包名**（R2 起是"取档"的输入）。
     *
     * 每次 [refreshForegroundGate] 判定时写入；**读不到就置 `null`** ⇒ [mode] 回落全局档。
     * 改造前前台包只用于"要不要停手"（二值），R2 之后它同时决定**按哪一档干活**
     * （跟随全局 / 跟随系统 / 自适应 / 半自动，见 [AppPrefs.modeFor]）。
     *
     * ⚠️ 它只是一次**读数**的缓存，不是账 —— 判据永远现算（[AppPrefs.modeFor]），
     *   ⛔ 别把它当成"真值来源"四处传，也别在别处直接读它算档。
     */
    @Volatile private var fgPkg: String? = null

    /**
     * ★★ 引擎当前**是否已进入介入态**（触发层在跑 + 已接管方向盘）。
     *
     * 引入它只为让 [syncEngagement] 幂等：全局档与逐应用档两条流都会调它，
     * 而 `StateFlow.collect` 订阅那一刻**立刻重放当前值** ⇒ 没有这个记账的话，
     * 引擎启动时会白接管一次（多写两笔 Settings）。
     * ⚠️ `engageTakeover()` 自身是幂等的（见那边的注释），这里只是省掉一次无谓的写。
     */
    @Volatile private var engagementOn = false

    // ============================================================ 生命周期

    fun start() {
        AppPrefs.init(context)

        // ★ 内/外屏形态监听：模式在 2026-09-28 按屏解耦了，引擎必须知道
        //   "我现在在哪块屏上"，否则折叠起来还在按内屏那一档转。
        installFormWatch()

        // ★ 孤儿接管检测：上次进程被杀时若还开着接管，accelerometer_rotation 会永远停在 0
        recoverOrphanTakeover()

        // 标定值 → 判定器
        decider.sign = AppPrefs.sign.value
        decider.offsetDeg = AppPrefs.offsetDeg.value

        _ui.update {
            it.copy(
                mode = AppPrefs.mode.value,
                strategy = AppPrefs.strategy.value,
                writeSettingsGranted = Settings.System.canWrite(context),
                sensorOrientation = sensorOrientation,
                // ★ 报的是**设备空间**的方向（用户眼里的方向），不是 getRotation 的原始值 ——
                //   内屏的安装朝向是 180，两者差半圈，界面/日志里说"竖屏"必须是字面意思。
                displayRotation = displayRotation(),
                panelOffset = PanelOrientation.installOffset(context),
                calibSign = decider.sign,
                calibOffsetDeg = decider.offsetDeg,
                calibrated = AppPrefs.isCalibrated,
                handoffRotate = AppPrefs.handoffRotate.value,
                gateEnabled = AppPrefs.gateEnabled.value,
            )
        }

        // ★ 前台门控总开关：运行中实时跟随（用户是在"发现不转"的当口去关它的，
        //   若要等重启引擎才生效，这个开关就等于没有）。
        //   StateFlow 收集会立刻重放当前值 —— 这段逻辑天然幂等（开关开着时什么都不做）。
        scope.launch {
            AppPrefs.gateEnabled.collect { v ->
                _ui.update { it.copy(gateEnabled = v) }
                if (!v) {
                    // 关掉门控 = 立刻解除可能正停着的状态（见 refreshForegroundGate 的注释）
                    runCatching { refreshForegroundGate("总开关关闭") }
                } else {
                    runCatching { refreshForegroundGate("总开关打开") }
                }
            }
        }

        // 白名单变化 → 报条数进状态 + **立刻**重判一次前台门。
        //
        // ★ 为什么白名单一动就要立刻重判：用户在设置页里勾掉/勾上一个开关，那个动作本身就是
        //   "让这个应用现在开始/别再受我控制"的意思，而前台门巡检最长要等
        //   [FOREGROUND_POLL_MS] 才轮到下一次 —— 用户会看到"开关拨了没反应"。
        // ⚠️ StateFlow 收集会立刻重放当前值 ⇒ 启动时这一段顺手把条数填上，逻辑天然幂等。
        scope.launch {
            AppPrefs.whitelist.collect { wl ->
                _ui.update { it.copy(whitelistSize = wl.size) }
                runCatching { refreshForegroundGate("白名单变更") }
            }
        }

        // ★ 用户点了"预览旋转按钮" → 直接弹一次按钮（**只为看外观**，见 [PrefsBridge.HINT_TEST]）。
        //
        //   为什么必须有这个入口：按钮只在传感器判定"设备姿态 ≠ 屏幕方向"时出现，而**传感器
        //   没法用 adb 注入**（`SensorService` 的数据注入是 eng build 才有的开关）⇒
        //   没有它的话，每调一次按钮材质都要人去转一次手机。
        //
        //   ⚠️ StateFlow 收集会**立刻重放当前值**（= 历史最后一次点击留下的时间戳），
        //     所以 [showHintForTest] 里第一件事是"冷启动首次只记账" —— 否则引擎每次
        //     软重启 SystemUI 都会凭空弹一个按钮出来。
        scope.launch {
            AppPrefs.hintTestReq.collect { req ->
                runCatching { showHintForTest(req) }
            }
        }

        // 模式变化（**全局档**与**逐应用档**两条流）→ 启停触发层 + 接管/交还。
        //
        // ★★ 2026-10-05（R2）判据换人：从"全局档 engage 不 engage"改成 [AppPrefs.engagesAny]。
        //   原因：存在「全局档 = 跟随系统，但某个应用被点名成自适应」这种组合 ——
        //   那时全局档不 engage，只看它会**整个引擎都不启动**，用户的点名等于没设。
        //
        //   ⚠️ **两条流都要收**：全局档（[AppPrefs.mode]：换模式 / 折叠换屏）与
        //     逐应用档（[AppPrefs.appModes]）。漏掉后者的症状很隐蔽 ——
        //     "全局档是跟随系统时，新点名的那个应用要等下一次引擎重启才生效"。
        //   两条都汇进同一个 [syncEngagement]，由它内部的 [engagementOn] 记账保证幂等。
        scope.launch {
            AppPrefs.mode.collect { m ->
                _ui.update { it.copy(mode = m) }
                syncEngagement("模式切换")
            }
        }
        scope.launch {
            AppPrefs.appModes.collect { syncEngagement("逐应用档切换") }
        }
        scope.launch {
            AppPrefs.strategy.collect { s ->
                _ui.update { it.copy(strategy = s) }
                // 从"响应优先"切回"省电优先"时，把常驻的相机立刻放掉
                if (s == CaptureStrategy.POWER_SAVING && !bursting) releaseCamera("切换为省电优先")
                event("采集策略 -> ${s.label}")
            }
        }
        // 前台门的「交还自动旋转」开关变化 → 立刻生效。
        //
        // ★ 为什么要收这个流，而不是等下次门控时现读：用户切开关时，引擎**可能正处在
        //   门控态**（比如正打着游戏）。只改意图不改现状，用户看到的就是"开关切了没反应"。
        // ⚠️ StateFlow 收集会立刻重放当前值 —— 下面的逻辑天然幂等（fgGated 为 false 时直接返回）。
        scope.launch {
            AppPrefs.handoffRotate.collect { v ->
                _ui.update { it.copy(handoffRotate = v) }
                if (!fgGated) return@collect
                if (v && !fgHandedOff && _ui.value.takeoverOn) {
                    releaseTakeover(restoreSystem = true, quiet = true)
                    fgHandedOff = true
                    event("前台门：开关切为「交还自动旋转」→ 已把方向盘还给系统")
                } else if (!v && fgHandedOff) {
                    fgHandedOff = false
                    engageTakeover()
                    event("前台门：开关切为「保留当前方向」→ 已拿回方向盘（系统自动旋转仍关闭）")
                }
            }
        }
        // 标定值变化 → 同步判定器并重新落定
        //
        // ★ `drop(1)` 不能省：StateFlow 收集时会**立刻重放当前值**，而 `start()` 上面
        //   已经用 `AppPrefs.isCalibrated` 把真实状态写进 `calibrated` 了。
        //   不丢这一次的话，无论有没有标定过，首帧都会被无条件置成 `calibrated = true`
        //   —— 现象就是新装的应用一进界面就显示「已校准」（实测踩到过）。
        scope.launch {
            AppPrefs.sign.drop(1).collect { v ->
                decider.sign = v
                decider.reset()
                lastAppliedRot = -1
                _ui.update { it.copy(calibSign = v, calibrated = true) }
            }
        }
        scope.launch {
            AppPrefs.offsetDeg.drop(1).collect { v ->
                decider.offsetDeg = v
                decider.reset()
                lastAppliedRot = -1
                _ui.update { it.copy(calibOffsetDeg = v, calibrated = true) }
            }
        }

        ensureProvider()
        registerAvailability()

        // ★★ 启动时的启停判据也走同一个 [syncEngagement]（R2 起 = [AppPrefs.engagesAny]）。
        //   ⚠️ 别在这里另写一份 `if (mode.engages)` —— 那份会漏掉"逐应用点名"，
        //     症状是"全局档跟随系统时，被点名成自适应的应用在整个引擎里都没反应"。
        //   ⚠️ 它与上面那两条 `collect` 是**同一个函数的两次调用**（collect 订阅即重放），
        //     靠 [engagementOn] 记账保证"只真接管一次" —— 谁先谁后都成立。
        syncEngagement("引擎启动")

        // 前台门控巡检：与触发层同生共死。循环体内自判条件（模式为 SYSTEM / 触发层
        // 没在跑时直接跳过、一次 IPC 都不发），所以这里无条件启动即可。
        startForegroundWatch()
        event("引擎启动（模式=${AppPrefs.mode.value.label}｜逐应用点名 ${AppPrefs.appModes.value.size} 个）")
    }

    /**
     * ★★★ **按前台应用取档**（R2，2026-10-05）—— 引擎里**唯一**该回答"按哪一档干活"的地方。
     *
     * 改造前引擎读的是 `AppPrefs.mode.value`（全局一档管所有应用，共 14 个读点）；
     * R2 之后每个应用可以单独点名 ⇒ 那些读点全部换成这里。
     *
     * ★ 回落规则（见 [AppPrefs.modeFor]）：读不到前台包 ⇒ 用**全局档**。
     *   判错方向的代价不对称 —— 多干一点活的代价只是耗电，凭空停手的代价是"功能消失"。
     *
     * ⚠️ 每次都现算（不看缓存）：用户改一个应用的档之后，下一次触发 / 巡检就用新档，
     *   不需要重启任何东西。
     */
    private fun mode(): RotateMode {
        val m = AppPrefs.modeFor(fgPkg)
        // ★★★ R1（2026-10-05）：自适应"读不到环境"时**临时**按半自动干活。
        //   ⚠️ 只改**本函数返回的答案**，一个字都不动 `AppPrefs` 里用户配的那一档 ——
        //     与"安全开关的真值只能放在 App prefs"是同一条纪律：
        //     **引擎不许偷偷改用户的配置**。用户退出降级条件后，档位原封不动地回来。
        //   ⚠️ 为什么降级后要走 SEMI 而不是"自适应 + 兜底弹按钮"：半自动**不开相机**，
        //     而暗光下开相机是纯浪费（本来就拍不到脸）—— 这正是半自动"最省电"的由来。
        //     代价是引擎从此不再知道环境变没变，那个缺口由 [r1DowngradeNow] ③ 补。
        if (m == RotateMode.ADAPTIVE && r1DowngradeNow()) return RotateMode.SEMI
        return m
    }

    /**
     * ★★ R1：此刻是否处于"临时降级成半自动"的状态（**纯读**，不写任何字段）。
     *
     * ⚠️ **必须纯读**：[mode] 被十几处调用，其中不少是"读一眼"的场合
     *   （前台门刷新、界面展示、`applySemiRotation` 的早退判据）。一旦它带副作用，
     *   那些地方会**顺手把探测窗口消耗掉** —— 症状是"降级之后再也没回到自适应"，
     *   而且因为消耗点不在触发路径上，极难查。⇒ 推进窗口的那一句只写在 [noteAdaptiveEnv] 里。
     *
     * 判据三条，缺一不可：
     * ```
     * ① 用户在界面里开着这个功能（[AppPrefs.r1Fallback]，出厂默认开）
     * ② 引擎确实判过"环境不可用"（[r1Downgraded]）
     * ③ 现在**不在**探测窗口里 —— 到了窗口就放行一次真正的自适应，
     *    那是"环境恢复就自动切回"唯一的手段（见 [R1_PROBE_INTERVAL_MS]）
     * ```
     * ★ ③ 的反向读法同样要紧：**窗口外的触发一律走半自动**，所以降级的意义
     *   （"暗光下别再白开相机"）才真的成立。
     */
    private fun r1DowngradeNow(): Boolean =
        AppPrefs.r1Fallback.value &&
            AdaptiveFallback.isDowngradedAt(r1, SystemClock.elapsedRealtime())

    /**
     * ★★★ R1：自适应这一轮"读不到环境"的记账（2026-10-05，分析线程调用）。
     *
     * 逐轮累积，攒够 [R1_MISS_ROUNDS] 就把引擎临时切成半自动；任一**成功**的轮次立刻切回。
     *
     * ★ 恢复只要求**一轮成功**、不做"连续 M 轮"的滞回 —— 代价不对称：
     *   多等一轮 = 用户多转一次手机没反应；而早一轮恢复，最坏只是下一轮又被降回去。
     *   真正的防抖交给 [R1_RECOVER_COOLDOWN_MS] 那道冷却。
     *
     * @param hadFace 本轮有没有出现过可用人脸（= [burstHadUsableFace]）
     * @param frames 本轮采到的帧数。调用点已保证 `> 0`。
     */
    private fun noteAdaptiveEnv(hadFace: Boolean, frames: Int) {
        // ★ 功能关掉时**连状态一起清**：否则用户"关掉 → 又打开"，会带着关闭期间
        //   攒下的 miss 计数**立刻**降级一次 —— 而他在关的那一刻并没有这个预期。
        if (!AppPrefs.r1Fallback.value) {
            r1 = AdaptiveFallback.State()
            return
        }
        // ★ 转移全在纯函数 [AdaptiveFallback.step] 里（有单测）；本函数只负责
        //   "喂输入 → 落状态 → 按结果做副作用"。⛔ 别把判据搬回这里 ——
        //   一旦判据分散，测试就再也钉不住它了。
        val (next, kind) = AdaptiveFallback.step(r1, SystemClock.elapsedRealtime(), hadFace)
        r1 = next
        when (kind) {
            AdaptiveFallback.Kind.ENTER -> event(
                "自适应：连续 ${next.missStreak} 轮没读到人脸（本轮 $frames 帧，一帧可用的都没有）" +
                    " ⇒ 临时降级半自动；每 ${R1_PROBE_INTERVAL_MS / 1000} 秒试一次能不能恢复",
            )

            AdaptiveFallback.Kind.EXIT -> {
                event("自适应：又读到人脸了 ⇒ 退出降级、恢复自动旋转")
                // ★ 降级期间弹出来的按钮此刻**必须收掉**：模式已经回到自适应，而那个
                //   按钮挂在屏幕上就是误导（点下去会走 `applySemiRotation`，它自己会因
                //   `mode() != SEMI` 早退、不会写错方向，但用户体验是"点了没反应"）。
                //   ⚠️ 窗口操作必须回主线程（见 [onMain]），而本函数跑在分析线程上。
                onMain {
                    cancelSemiSettle()
                    runCatching { overlayRef?.hide() }
                }
            }

            AdaptiveFallback.Kind.NONE -> Unit
        }
    }

    /**
     * ★★★ **把"引擎该不该介入"对齐到当前配置**（R2 起是本引擎启停的**唯一入口**）。
     *
     * 触发它的事件有三类：① 引擎启动；② 全局档变化（换模式 / 折叠换屏）；
     * ③ 逐应用档变化（界面上的四选一）。
     *
     * ★ 判据是 [AppPrefs.engagesAny]，**不是** `mode().engages` —— 理由是：
     *   存在「全局档 = 跟随系统，但某个应用被点名成自适应」这种组合，
     *   只看全局档会导致**整个引擎都不启动**，用户的点名等于没设。
     *
     * ★★ 幂等靠 [engagementOn] 记账：`StateFlow.collect` 订阅那一刻会立刻重放，
     *   而启动路径也会调一次 ⇒ 没有记账就会白接管一次。
     *   ⚠️ 记错也不会造成故障：`engageTakeover()` 自身幂等（见那边的注释），
     *     最多多写两笔 Settings。
     */
    private fun syncEngagement(reason: String) {
        val engage = AppPrefs.engagesAny
        val was = engagementOn
        engagementOn = engage
        if (engage) {
            // ★ 只在"状态真的翻转"时才动，避免每次改一个应用的档都白跑一遍
            //   startTrigger（它会打一行"触发层已恢复"，纯噪音）。
            if (!was) {
                startTrigger()
                engageTakeover()
            }
        } else {
            if (was) {
                stopTrigger()
                releaseTakeover()
            }
        }
        // ★ 收掉"上一次判定"留下的半自动按钮：它挂在窗口上，档都换了就不该继续等人点。
        //   ⚠️ **同时取消"待确认"**（2026-09-29）：姿态稳定确认延迟 280ms，
        //     漏这步会出现"档都切走了，280ms 后按钮又自己冒出来"。
        if (mode() != RotateMode.SEMI) {
            cancelSemiSettle()
            runCatching { overlayRef?.hide() }
        }
        // ★ 档变了 ⇒ 前台门的结论也跟着变，立刻重判一次（别等下一轮巡检，最多等
        //   [FOREGROUND_POLL_MS]）。这一调同时把 [fgPkg] 刷成最新。
        runCatching { refreshForegroundGate(reason) }
        _ui.update { it.copy(mode = mode()) }
    }

    fun stop() {
        stopTrigger()
        // ★ R2（2026-10-05）：停掉引擎 ⇒ 介入态归零。不归零的话，
        //   下一次 [syncEngagement] 会以为"还开着"而跳过 startTrigger/engageTakeover，
        //   引擎就再也起不来了（症状：软重启 SystemUI 后方向功能整个失效）。
        engagementOn = false
        // ★ R1（2026-10-05）：降级状态一并无条件归零。引擎都停了，"环境不可用"这个
        //   **临时**结论没有继续成立的理由 —— 留着它会让下次启动**第一次**触发
        //   就直接走半自动（用户看到的将是"重启后自适应不工作了"）。清零后从零重新判。
        r1 = AdaptiveFallback.State()
        // ★ 半自动的按钮是"挂在窗口上的"，引擎停了必须收掉 ——
        //   否则它会一直停在屏幕右下角，等自己的倒计时走完才收走，
        //   但那个 View 的回调指向一个已经停掉的引擎（点下去等于什么都没发生）。
        //   ⚠️ 连"待确认"一起取消（[SEMI_SETTLE_MS] 的延时任务），
        //      否则引擎都停了还会冒一个按钮出来。
        cancelSemiSettle()
        runCatching { overlayRef?.hide() }
        // ★ 顺序：夹在 stopTrigger 与 releaseTakeover 之间。
        //   停巡检时只清前台门标志（不自己 engage），随后由 releaseTakeover 统一交还 ——
        //   反过来的话会先 engage 再 release，白写两笔 Settings。
        stopForegroundWatch()
        releaseTakeover(restoreSystem = true, quiet = true)
        keepWarmJob?.cancel()
        keepWarmJob = null
        releaseCamera("引擎停止")
        availabilityCb?.let { cm?.unregisterAvailabilityCallback(it) }
        availabilityCb = null
        analyzer?.close()
        recorder.stop()
        executor.shutdown()
        event("引擎停止")
    }

    // ============================================================ 触发层

    private fun startTrigger() {
        val existing = trigger
        if (existing != null) {
            // 复用已有实例（退后台让位后回前台恢复）
            val ok = existing.start()
            _ui.update { it.copy(sensorAvailable = ok) }
            if (ok) event("触发层已恢复")
            return
        }
        val t = DeviceOrientationTrigger(context) { reason, code ->
            _ui.update {
                it.copy(
                    triggerCode = code,
                    triggerCount = it.triggerCount + 1,
                )
            }
            event("触发：$reason")
            onTriggered(reason)
        }
        val ok = t.start()
        trigger = t
        _ui.update { it.copy(sensorAvailable = ok) }
        if (ok) {
            event(
                "触发层已启用（触发：主路 device_orientation on-change + 兜底 gyro 角速度；" +
                    "重力路：只当尺子，用于自动校验人脸链路的符号位）"
            )
        } else {
            event("⚠️ device_orientation 与 gyro 都不可用，触发层未启动")
        }
    }

    private fun stopTrigger() {
        trigger?.stop()
        // 刻意不置 null：DeviceOrientationTrigger 支持 start/stop 复用，
        // 退后台让位、回前台恢复时不必重建对象（也能保住累计统计）
        _ui.update { it.copy(sensorAvailable = false) }
    }

    // ============================================================ 前台门控

    /**
     * 判一次「前台应用是不是在白名单里」，据此进入 / 退出门控。
     *
     * ★ 判据（2026-09-28 用户拍板换过）：**白名单**。原来的"读 Activity 声明的朝向去推"
     *   连着出了两次真错（`BEHIND` 语义、静态值 vs 运行时值），完整记录见 [ForegroundGate]。
     *   新判据不需要任何推断 —— 名单是用户点的，命中与否是一次集合查找。
     *
     * ★ 停手治的是什么（用户报的"打游戏断触"）：每轮触发 = 开前摄 + 8 帧 ML Kit 推理，
     *   2026-09-25 实测最密 4 轮/4 秒 ≈ 32 次推理/秒，跟游戏抢 CPU/GPU。
     *   停手 = 停相机 = 停推理。
     *
     * ★ 本应用自己当年是**恒豁免**的（治「设置页没跟随系统旋转」：模块的接管会让
     *   `accelerometer_rotation` 冻结成 0，打开自己的设置页也转不动）。
     *   ⚠️ **2026-09-29 用户要求改掉**：本应用现在是名单里的普通一行、默认**不**豁免，
     *   想让它跟随系统就在界面上勾一下（理由见 [AppWhitelist] 类注释）。
     *
     * ★★ **一条豁免**（2026-10-05 收敛）：生效白名单命中。
     *   ⛔ 原来的第二条「实测不可控」（原 `Uncontrollable`，A 方案）已按用户点名**整个删除**
     *   —— 它的结尾从"记住并永久停手"改成了"转不动就弹一次「旋转失败」"
     *   （见 [scheduleSemiReadback] / [notifyRotateFailed]）。
     *   ⇒ 引擎**不再有任何"自己写进去的豁免"**：停手与否只由用户配置的那份名单决定。
     *   （更早删掉的"外屏桌面"与"按应用声明判外屏豁免"两条见函数体内那段注释。）
     *
     * @return 是否**读到了**前台应用。false = 读不到 ⇒ 调用方**不得**据此停手。
     */
    private fun refreshForegroundGate(why: String): Boolean {
        // ★★ 总开关关掉 ⇒ 门控**整个不生效**（用户 2026-09-28 要的逃生阀，治"该转不转"）。
        //   ⚠️ 必须主动 [leaveForegroundGate]：光"不再新判"是不够的 —— 已经停手、
        //   已经把方向盘交还给系统的状态不会自己回来，屏幕仍然是死的。
        if (!AppPrefs.gateEnabled.value) {
            // ★ R2：门控整个不生效 ⇒ **逐应用点名也一并失效**（门控关掉 = "别管前台是谁"），
            //   所以把前台读数清成"不知道"⇒ [mode] 回落全局档。
            fgPkg = null
            leaveForegroundGate()
            return false
        }
        val info = runCatching { fgProbe.read() }.getOrNull()
        if (info == null) {
            // ★ R2 同上：读不到前台应用 ⇒ 没有"逐应用"这个维度可用 ⇒ 回落全局档。
            //   宁可退回改造前的行为（全局一档管所有），也不许拿一个陈旧的包名去取档。
            fgPkg = null
            // ★ 失败计数（2026-10-03）：读不到 ⇒ **门控整个不生效**（下面的行为一个字不改，
            //   但这件事实必须能被界面读到，否则用户会以为门控在工作）。见 [EngineErrors]。
            EngineErrors.bump(EngineErrors.FOREGROUND)
            // ★ 读不到 → 只把"读不到"如实报出去，**一处行为都不改**。
            //   宁可退回改造前的样子，也不允许因为读不到就永久误停（代价不对称）。
            if (_ui.value.foregroundReadable) {
                _ui.update { it.copy(foregroundReadable = false) }
                event("前台门：读不到前台应用（$why）→ 门控不生效，按原行为运行")
            }
            return false
        }
        // ★★ 判据现在**只有一条**：**生效白名单**（用户 2026-09-28 拍板：
        //   「白名单应用不受 app 控制」）。名单只有一份（外屏增强删掉之后，
        //   "按形态取名单"就没有意义了），算法见 [AppWhitelist.resolve]。
        //
        //   ⛔ 原来的第二条「实测不可控」（原 `Uncontrollable`，A 方案）已于 2026-10-05
        //      按用户点名**整个删除**（原话：「删掉旋转增强"发现转不动就不再控制"的这个功能，
        //      换成转不动就弹 toast 提示"旋转失败"」）⇒ 现在转不动只弹一次提示，
        //      **不落盘、不改后续行为**。这也是本工程最后一条"用观测结果反推判据"的机制 ——
        //      删掉之后，引擎对"该不该停手"的判断**只剩用户配置**这一个来源。
        //
        //   ⛔ 同一天删掉的两条判据（都是"推断"，各被用户抓到一次真错，别再捡回来）：
        //     - **外屏桌面**（原 `OuterDesktopGate`）：外屏的旋转增强整个删了
        //       （闸在 `AppPrefs.modeOf`：跑在外屏时生效档恒为 SYSTEM，引擎压根不会走到这里）。
        //       留着它不但多余，还会让"外屏"这个概念在本函数里阴魂不散。
        //     - **按应用声明判外屏豁免**（原 `isOuterForced`）：用户报「有些软件其实是可以
        //       旋转的，但被识别成不可旋转，强制豁免了，还关不掉」。病根是判据读错了对象 ——
        //       界面读的是**启动页**的声明、引擎读的是**栈顶 Activity** 的声明。
        //       完整复盘见 [AppWhitelist] 类注释"记过案"。
        // ★★ R2（2026-10-05）：**先把前台包记下来** —— 它是"按哪一档干活"的输入
        //   （[mode]）。漏这一行的症状是"逐应用点名怎么设都没反应"，而且不报任何错。
        //   ⚠️ 必须在下面所有分支之前：本函数有多个 return，写在中间就会漏。
        fgPkg = info.pkg
        // 状态里那一格也跟着更新（界面 / 诊断页读 [UiState.mode]）——
        // 逐应用点名时它会显示"这个应用实际在用的档"，而不是全局档。
        val appModeNow = AppPrefs.modeFor(info.pkg)
        if (_ui.value.mode != appModeNow) _ui.update { it.copy(mode = appModeNow) }

        val form = AppPrefs.screenForm.value
        val whitelisted = AppPrefs.isWhitelisted(info.pkg)
        // ★ 2026-09-29：把**本轮判据的三个输入**无条件上报（进不进门都报）。
        //   起因：用户报「外屏抖音弹了按钮但不能转」，而三格诊断字段此前**只在停手时**才写
        //   ⇒ 没停手时全空 ⇒ 读不到"引擎到底看到了什么"，只能靠日志措辞反推，
        //   而日志又只在**状态跃迁**时打。这一格现在是排查"判据为什么没命中"的唯一窗口。
        //   `foregroundStop` 不动（空 = 没在停手），语义保持"结论"。
        _ui.update {
            it.copy(
                foregroundPkg = info.pkg,
                foregroundOrientation = ForegroundGate.label(info.orientation),
                foregroundForm = if (form == ScreenForm.OUTER) "OUTER" else "INNER",
                // ★ 按钮存活状态也在这里采样（用户 2026-09-29 要查"反复旋转会不会叠出好几个按钮"）。
                //   放在巡检路径上而不是"每次 hide 都刷"：hide 有 5 个调用点，
                //   逐个补容易漏；而这里每 [FOREGROUND_POLL_MS] 一定会跑一次，
                //   代价是"按钮自己倒计时消失后最多晚一个周期才报到"—— 够用。
                hintAlive = overlayRef?.isShowing == true,
            )
        }
        val reason = if (whitelisted) StopReason.WHITELIST else null
        when (ForegroundGate.decide(info.pkg, reason != null)) {
            ForegroundGate.Decision.YIELD ->
                enterForegroundGate(info, why, reason ?: StopReason.WHITELIST)
            ForegroundGate.Decision.MANAGEABLE -> leaveForegroundGate()
            // UNKNOWN 只可能出现在 pkg 为空时，而上面已经 return 了 —— 兜一下
            ForegroundGate.Decision.UNKNOWN -> Unit
        }
        if (!_ui.value.foregroundReadable) {
            _ui.update { it.copy(foregroundReadable = true) }
        }
        return true
    }

    /**
     * 停手：不开相机、不写方向；(按用户开关) 把系统自动旋转交还系统。
     *
     * @param reason 停手原因。**只影响日志措辞与界面展示**（行为完全相同）。
     *   ⚠️ 2026-10-05 起只剩「白名单」一条 —— 原来的「实测不可控」已删除。
     */
    private fun enterForegroundGate(
        info: ForegroundProbe.Info,
        why: String,
        reason: ForegroundGate.StopReason,
    ) {
        val label = ForegroundGate.label(info.orientation)
        // ★ 日志必须点出"是谁让它停的"：白名单 = **用户偏好**（用户自己能改）。
        //   （原来还有第二种原因「实测不可控」= 观测事实，2026-10-05 已删除。）
        // ★ 2026-10-05：停手原因现在**只有一条**（白名单）—— 原来的「实测不可控」
        //   已按用户点名删除（转不动改成弹一次提示，不再停手）。
        //   ⇒ 这里不再需要分支；但 [reason] 参数**保留**：它是"停手原因"的统一表达，
        //     界面与日志都指着它（将来若真有第二种原因，往枚举里加一项即可）。
        val hit = "在豁免名单里"
        if (fgGated) {
            // 已经停手了：只刷新展示（前台可能换成了另一个**也命中豁免**的应用）
            if (_ui.value.foregroundPkg != info.pkg ||
                _ui.value.foregroundOrientation != label ||
                _ui.value.foregroundStop != reason.name
            ) {
                _ui.update {
                    it.copy(
                        foregroundPkg = info.pkg,
                        foregroundOrientation = label,
                        foregroundStop = reason.name,
                    )
                }
            }
            return
        }
        // ★ 顺序很重要：**先**置 fgGated，**再**收尾/释放。
        //   否则 [applyRotationIfNeeded] 的闸还开着，burst 收尾会把方向写出去 ——
        //   那正好是我们要避免的"在应用自管朝向时动它的屏幕"。
        fgGated = true

        // ① 停掉两样会持续吃 CPU/GPU 的东西：相机保温续租、进行中的 burst
        keepWarmJob?.cancel()
        keepWarmJob = null
        finishBurst(gaveUp = true) // 没在 burst 时内部直接 return，是 no-op
        releaseCamera("前台门：${info.pkg} $hit")

        // ★ 屏幕上还挂着的按钮也要撤（2026-09-29 修，用户报「按了按钮完全没反应」）。
        //   停手之后 `takeoverOn` 会被清掉（[applySemiRotation] 的接管闸会静默 return），
        //   若按钮留着，用户点下去既不转屏、也没有任何反馈 —— 比"压根不弹"更糟。
        //   按钮有效期本来只有 3 秒，撤掉不会打断任何正常流程。
        //   ⚠️ **同时取消"待确认"**：姿态稳定确认是延迟 [SEMI_SETTLE_MS] 的，
        //      漏掉这步就会出现"刚判定停手撤掉按钮、280ms 后它又冒出来"。
        cancelSemiSettle()
        runCatching { overlayRef?.hide() }

        // ② 按用户开关决定要不要把方向盘还给系统
        val wantHandoff = AppPrefs.handoffRotate.value
        if (wantHandoff && _ui.value.takeoverOn) {
            releaseTakeover(restoreSystem = true, quiet = true)
            fgHandedOff = true
        } else {
            fgHandedOff = false
        }

        _ui.update {
            it.copy(
                foregroundGated = true,
                foregroundPkg = info.pkg,
                foregroundOrientation = label,
                foregroundStop = reason.name,
                foregroundReadable = true,
                handoffRotate = wantHandoff,
                // 顺手把生效白名单条数记住（总线要报它；判据变了之后这是"名单到底多大"的唯一窗口）
                whitelistSize = AppPrefs.whitelist.value.size,
            )
        }
        // ⚠️ 三种"停手"互不相同，文案不许混为一谈 —— 排查时就靠这句分辨：
        //   ① 真把方向盘还回去了（系统自动旋转已恢复）
        //   ② 用户在设置里要求"保留当前方向"，所以没还
        //   ③ 当时压根没接管（启动竞态 / 未授权）⇒ 方向盘本来就在系统手上
        //   ⚠️ 原写法是 `+ if (…) … else … + "（该应用…）"`，运算符优先级让
        //      if 分支把尾注丢了、else 分支才带 —— 同一句话两种长度，属自找的困惑。
        event(
            "⏸️ 前台门（$why）：${info.pkg} $hit → 已停手（不采帧 / 不弹按钮 / 不写方向）" +
                when {
                    fgHandedOff -> "，并把自动旋转交还系统"
                    !wantHandoff -> "，按你的设置保留当前方向"
                    else -> "（当时尚未接管，方向盘本就在系统手上）"
                } +
                "（该应用声明的朝向为 $label，仅作诊断）",
        )
    }

    /** 解除停手：前台应用不再自己管朝向 ⇒ 把方向盘拿回来，恢复脸控 */
    private fun leaveForegroundGate() {
        if (!fgGated) return
        fgGated = false
        // ★ 无条件尝试拿回（2026-09-29 修）：原来只在 `fgHandedOff` 时调 [engageTakeover]，
        //   但"进白名单时压根没接管"也是一种合法状态（启动竞态、用户刚关过授权、
        //   或上一个白名单应用之后就没接管过）—— 那时 fgHandedOff 是 false，
        //   可我们**恰恰需要**在离开时接管。少这一下的症状 = 出了游戏再也不跟脸转。
        //   engageTakeover 自带去重闸（已接管直接 return）+ 前台门闸，重复调用无害。
        fgHandedOff = false
        engageTakeover()
        _ui.update {
            it.copy(
                foregroundGated = false,
                foregroundPkg = "",
                foregroundOrientation = "",
                foregroundStop = "",
                handoffRotate = AppPrefs.handoffRotate.value,
            )
        }
        event("▶️ 前台门解除：前台应用不在白名单里（或已退出）→ 恢复脸控")
        // ★★ 补判一次（2026-09-28 新增，专治用户报的「该转的时候不转」）：
        //
        //   停手期间触发是**整个被丢掉**的（[onTriggered] 第一件事就 return）。
        //   于是有一个很难自查的漏洞：用户**在停手期间转了手机**（比如刚退出游戏、
        //   切回微信），那一刻的意图没有任何人接手 —— 而传感器触发是"动了才来"的事件，
        //   用户摆好姿势后**不会再有第二次触发**，屏幕就停在上一个方向。
        //
        //   补判把这一下接住：退出停手的瞬间立刻按当前姿势重新判一次，走正常链路
        //   （自适应 = 开相机跑一轮 burst；半自动 = 直接读姿态弹按钮）。
        //   代价是退出停手时可能多跑一轮 burst —— 而这个时刻**本来就是**用户
        //   刚切回前台、正想用的时候，值得付。
        rejudgeAfterGate()
    }

    /** [leaveForegroundGate] 的补判出口；带重入闸（见 [gateRejudging]） */
    private fun rejudgeAfterGate() {
        if (gateRejudging) return
        gateRejudging = true
        try {
            onTriggered("前台门解除补判")
        } catch (t: Throwable) {
            Log.w(TAG, "外壳补判异常（已忽略）", t)
        } finally {
            gateRejudging = false
        }
    }

    /**
     * 前台门巡检：与**触发层同生共死**。
     *
     * ★ 为什么不能只在触发时判：常驻相机（响应优先）与进行中的 burst 都可能在
     *   "没有任何触发"的情况下继续吃资源 —— 用户切进游戏这个动作本身不产生传感器触发，
     *   光靠触发时判定，相机会一直开着。
     *
     * ⚠️ 循环体里在确认该干活之前**一次 IPC 都不发**：非 ADAPTIVE、或触发层没在跑时
     *   直接跳过 ⇒ 相当于零开销。跑在 [scope] 的 `Dispatchers.Default` 上，不占主线程。
     */
    private fun startForegroundWatch() {
        if (fgWatchJob?.isActive == true) return
        fgWatchJob = scope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_MS)
                // ★ 形态巡检（2026-09-29 加）：搭这条既有心跳的车，理由见 [watchScreenForm]。
                //   ⚠️ 必须放在下面两个 `continue` **之前** —— 形态是全局读数，
                //   与"模式是不是 SYSTEM""传感器在不在"都无关；放后面的话 SEMI 模式下
                //   或传感器不可用时就永远不重测，又回到这次事故那个状态。
                runCatching { watchScreenForm() }
                    .onFailure { Log.w(TAG, "形态巡检异常（已忽略）", it) }
                // ⚠️ 2026-10-03：「槽位对齐」那一段**删掉了** —— 它和用户的「默认方向」
                //   直接冲突（用户定死的值会被下一次方向变化 / 换屏覆盖回"当前方向"）。
                //   现在方向槽位的写入者只有两个：用户点「默认方向」（App 借 root 直写）
                //   与框架自己（用户手动转屏时同步）。⛔ 别把自动对齐加回来。
                // ★ R2（2026-10-05）：判据从"全局档是不是 SYSTEM"换成 [AppPrefs.engagesAny] ——
                //   全局档跟随系统、但有应用被点名成自适应时，这条巡检**必须继续跑**
                //   （否则那些应用永远不会被取到档）。
                if (!AppPrefs.engagesAny) continue
                if (!_ui.value.sensorAvailable) continue
                runCatching { refreshForegroundGate("巡检") }
                    .onFailure { Log.w(TAG, "前台门巡检异常（已忽略）", it) }
            }
        }
    }

    /**
     * 停巡检。
     *
     * ⚠️ 顺手 [leaveForegroundGate]：巡检停了若还停在"已交还自动旋转"的状态，
     *   系统自动旋转会一直开着，而界面还写着"已接管" —— 正是"诊断说谎"那类事故。
     */
    private fun stopForegroundWatch() {
        fgWatchJob?.cancel()
        fgWatchJob = null
        // ⚠️ 这里**只清标志，不"拿回方向盘"**：唯一调用方 [stop] 紧接着就要交还系统，
        //   再 engage 一次等于白写两笔 Settings（刚 engage 又马上 release）。
        //   ⇒ 调用方必须负责把接管状态收干净（[stop] 里的 releaseTakeover 正是干这个）。
        fgGated = false
        fgHandedOff = false
        _ui.update {
            it.copy(foregroundGated = false, foregroundPkg = "", foregroundOrientation = "")
        }
    }

    // ============================================================ 让步闸门

    private fun registerAvailability() {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        cm = manager
        runCatching {
            val fronts = manager.cameraIdList.filter { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.sorted()   // 稳定顺序，避免 cameraIdList 顺序变化导致"偏好 id"漂移
            frontIds = fronts.toSet()

            // ★ 有序候选：把"上次真正采到过帧的 id"顶到第一位，其余按 id 升序兜底。
            //   持久化只用于**少烧一轮**（换 id 的代价是一轮 bind 失败，~几十毫秒到 1.5s），
            //   id 不在当前前摄列表里（换机型 / 系统更新）就自然被忽略。
            val pref = runCatching { AppPrefs.preferredCameraId() }.getOrDefault("")
            frontIdOrder = orderFrontIds(fronts, pref)
            frontIndex = 0
            frontFailStreak = 0
            frontCycles = 0
            frontRotatePausedUntil = 0L
            _ui.update { it.copy(cameraId = currentFrontId ?: "") }
        }
        event("前摄候选 = ${frontIdOrder.joinToString(",")}（当前 ${currentFrontId ?: "默认"}）")

        availabilityCb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                // 只有"我们没在用"时的可用事件才算别人释放了
                if (selfHolding) return
                // ★★ 2026-09-25 修（用户报"卡住了"）：**不再只认前摄 id**。
                //   挡我们的 `com.miui.aoc` 占的是 **后摄 id "0"**（HAL device 0），
                //   而它释放时回调的是 `onCameraAvailable("0")` —— 旧代码
                //   `if (cameraId !in frontIds) return` 恰好把这条最关键的信号丢了
                //   ⇒ `occupiedByOthers` 永久为 true ⇒ 之后每次触发都"让步"。
                //   可用的相机**变多了**这件事本身，永远是"压力变小"的证据，
                //   所以任何 id 空出来都足以解除让步判定。
                if (occupiedByOthers) {
                    clearOccupiedByOthers()
                    event("相机 $cameraId 空出（非前摄也算）→ 解除让步状态")
                }
                if (cameraId in frontIds) maybeRetryForFreedCamera(cameraId)
            }

            override fun onCameraUnavailable(cameraId: String) {
                if (cameraId !in frontIds) return
                // ★ 关键：这个回调包含我们自己的占用。自己持有期间一律忽略，
                //   否则每次 burst 都会把自己误判成"被别人抢了"
                if (selfHolding) return
                markOccupiedByOthers()
                if (!bursting) {
                    event("前摄 $cameraId 被其他应用占用 → 进入让步状态")
                    return
                }
                // G3：正在 burst 时被抢 → 立刻让位（**不抢回来**，抢只会两边都拿不到）。
                //     同时记一笔冲突数 + 登记"等它松手补采"。
                // ⚠️ 这条路只在"还没绑上相机就发现被占"时才会走到（见上面 selfHolding 的挡板）；
                //    真正高频的那种（已经开流、采到一半被踢）由 [startBurst] 的超时分支记账。
                conflictCount++
                _ui.update { it.copy(conflictCount = conflictCount) }
                waitingForCamera = true
                event("⚠️ burst 中被抢走前摄（第 $conflictCount 次）→ 立即让位，等它松手再补采")
                finishBurst(gaveUp = true)
            }
        }.also { manager.registerAvailabilityCallback(executor, it) }
    }

    /**
     * 为**指定的前摄 id** 造一个 CameraSelector。
     *
     * ★ 为什么不能用 `CameraSelector.DEFAULT_FRONT_CAMERA`：它只认"默认前摄"（id "1"），
     *   没有备选 —— 一旦被占就没有退路。这里用 `addCameraFilter` 精确挑选 id，
     *   失败时（过滤结果为空）CameraX 会抛 `IllegalArgumentException`，
     *   由调用方接住并**换下一个 id**。
     *
     * ⚠️ 过滤结果为空时**故意不退回全集**：退回全集等于又去抢同一个默认前摄，
     *   那正是我们想避开的。宁可快速失败、换 id。
     */
    private fun frontSelectorFor(id: String?): CameraSelector {
        if (id.isNullOrEmpty()) return CameraSelector.DEFAULT_FRONT_CAMERA
        return CameraSelector.Builder()
            .requireLensFacing(CameraCharacteristics.LENS_FACING_FRONT)
            .addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() == id
                }.toMutableList()
            }
            .build()
    }

    /**
     * 换用下一个前摄 id，并在换之前丢掉旧会话。返回是否真的换了。
     *
     * 触发点有两处（都要走这里，才能保证计数与释放动作一致）：
     *  · bind 失败（`Too many cameras already open` / 空过滤结果）；
     *  · 一轮 burst 收到 **0 帧**（会话被 `com.miui.aoc` 异步踢掉）。
     */
    private fun advanceFrontId(why: String): Boolean {
        if (frontIdOrder.size <= 1) return false
        val now = SystemClock.elapsedRealtime()
        if (now < frontRotatePausedUntil) return false

        // ★ 先把"要不要暂停"判完再动状态 —— 否则暂停分支会把 frontIndex 悄悄改掉
        //   却不做切换动作（释放/清占用/打日志），id 与日志就对不上了。
        //   走到「要绕回起点」= 5 个前摄已经全试过一遍，再从头试只是重复烧 1.5s 超时
        //   （aoc 的占用条件并没有变）⇒ 暂停轮换，交给"让位 + 等它松手补采"那条路。
        val willWrap = frontIndex == frontIdOrder.size - 1
        if (willWrap && frontCycles + 1 >= FRONT_MAX_CYCLES) {
            frontCycles = 0
            frontFailStreak = 0
            frontRotatePausedUntil = now + FRONT_ROTATE_PAUSE_MS

            // ★★ 兜底（2026-09-25）：**绝不允许"比改之前更糟"**。
            //   如果从引擎启动到现在，5 个候选 id **一个都没出过帧**，那说明问题不在
            //   "哪个 id"，而很可能在**我这套按 id 精确选相机的方式在这里根本不成立**
            //   （例如 `Camera2CameraInfo.cameraId` 与 `cameraIdList` 的字符串对不上，
            //   导致过滤结果恒为空）。那种情况下继续轮换 = 永久空转，用户看到的是
            //   "彻底不动了" —— 比改动前（至少还能用默认前摄）更差。
            //   ⇒ 退回 [CameraSelector.DEFAULT_FRONT_CAMERA]，即**与改动前完全一致的旧行为**，
            //     并如实说清楚"是兜底退回去的"，不假装是成功。
            if (!frontEverSucceeded) {
                frontIdOrder = emptyList()
                frontIndex = 0
                _ui.update { it.copy(cameraId = "默认") }
                event(
                    "⚠️ 5 个候选前摄 id 全部拿不到帧 → 退回默认前摄（旧行为）。" +
                        "按 id 精确选相机这条路在本机可能不成立（原因：$why）",
                )
                return false
            }

            event(
                "🔄 ${frontIdOrder.size} 个前摄已全试过一遍仍无帧 → 暂停轮换 ${FRONT_ROTATE_PAUSE_MS}ms，" +
                    "退回「让位 + 等它松手补采」（原因：$why）",
            )
            return false
        }
        if (willWrap) frontCycles++

        val from = currentFrontId
        frontIndex = (frontIndex + 1) % frontIdOrder.size
        frontFailStreak = 0
        releaseCamera("换前摄 id 前先丢掉旧会话")
        // 旧 id 的"被占"结论对新 id 不成立，一起清掉，否则新 id 会被 G1 挡在门外
        clearOccupiedByOthers()
        _ui.update { it.copy(cameraId = currentFrontId ?: "") }
        event("🔄 切换前摄 id：$from → ${currentFrontId}（已换 ${frontCycles} 圈，原因：$why）")
        return true
    }

    /** 某个 id 又失败了一次（bind 失败 / 0 帧）；连败到阈值就换下一个 id */
    private fun noteFrontIdFailure(why: String) {
        frontFailStreak++
        if (frontFailStreak < FRONT_FAIL_TO_ROTATE) return
        advanceFrontId(why)
    }

    /**
     * 当前 id 真的出帧了 —— 重置失败计数，并把它记成"下次优先用"的那个。
     *
     * ★ 只在**确实收到帧**时才记：0 帧的 id 不能进偏好，否则下次启动会先试一个坏的，
     *   白白多烧一轮（用户抱怨的正是"慢"，不能自己制造慢）。
     */
    private fun noteFrontIdSuccess() {
        frontFailStreak = 0
        frontCycles = 0
        frontRotatePausedUntil = 0L
        frontEverSucceeded = true
        val id = currentFrontId ?: return
        runCatching { AppPrefs.setPreferredCameraId(id) }
    }


    /**
     * 相机空出来了 —— 如果我们之前正是因为「相机在别人手里」而没采到样，这里补一次。
     *
     * ★ 这是路线 A 的核心：**不跟系统感知抢相机**，改为让位 + 等它松手的那一刻再采。
     *   与 [scheduleRetry]（定时补试）的区别：定时重试是"盲猜它放开了没有"，
     *   实测对方占用时长不定（`com.miui.aoc` 那次自己 8 秒后 Binder 就死了），
     *   定时重试经常正好撞在它还在用的窗口里；这个是事件驱动，命中率高且更省电。
     */
    private fun maybeRetryForFreedCamera(cameraId: String) {
        if (!waitingForCamera) return
        if (mode() != RotateMode.ADAPTIVE) {
            waitingForCamera = false
            return
        }
        if (bursting) return
        if (retryLeft <= 0) {
            waitingForCamera = false
            event("相机 $cameraId 已空闲，但本次触发的补采额度已用完 → 等下一次触发")
            return
        }
        retryLeft--
        waitingForCamera = false
        event("相机 $cameraId 已被让出 → 立即补采（事件驱动，不轮询）")
        startBurst()
    }

    private var cm: CameraManager? = null
    private var frontIds: Set<String> = emptySet()

    // ------------------------------------------------ 前摄 id 轮换（2026-09-25 新增）
    //
    // 为什么需要：本机有 **5 个前摄**（`1,5,7,8,9`），而旧代码只会用
    //   `CameraSelector.DEFAULT_FRONT_CAMERA`（= id "1"）。`com.miui.aoc` 一占住
    //   HAL device 0，我们开 id "1" 就直接 `Too many cameras already open` 被拒，
    //   而引擎**除了重试同一个 id 之外无计可施** ⇒ 用户看到"卡住了"。
    //
    // ★ 换 id 能奏效的**物理依据**（`dumpsys media.camera` 里各设备的
    //   `Conflicting devices:` 清单，2026-09-25 实读）：
    //     前摄 1 → 冲突 {8,7}     ┐
    //     前摄 7 → 冲突 {8,1}     ├ A 组：三者互斥，但**与 B 组不冲突**
    //     前摄 8 → 冲突 {7,1}     ┘
    //     前摄 5 → 冲突 {9}       ┐ B 组：两者互斥，但**与 A 组不冲突**
    //     前摄 9 → 冲突 {5}       ┘
    //     后摄 0 → 冲突 {2,3,10,6,4}（全是后摄，与前摄无交集）
    //   ⇒ A 组被占时 B 组仍可开。5 个前摄的 `Orientation` **全是 270**
    //     （原文核对过）⇒ 换 id **不会改变 roll 的固定基准**，方向映射安全。
    //
    // ⚠️ 轮换有上限（[FRONT_MAX_CYCLES]）：若 5 个 id 全被挡（aoc 占着后摄、
    //   整体"打开数量"预算满），继续盲转只会白烧帧预算，此时退回原来的
    //   "让位 + 等它松手补采"，并暂停轮换 [FRONT_ROTATE_PAUSE_MS]。

    /** 有序候选（[registerAvailability] 里填）：上次成功过的 id 排第一，其余按 id 升序 */
    private var frontIdOrder: List<String> = emptyList()
    private var frontIndex = 0

    /** 当前正在用/即将用的前摄 id；null = 尚未探测到，退回 `DEFAULT_FRONT_CAMERA` */
    private val currentFrontId: String? get() = frontIdOrder.getOrNull(frontIndex)

    /** 当前 id 连续失败（bind 失败 或 一轮 0 帧）的次数 */
    private var frontFailStreak = 0

    /** 已经"换满一整圈"的次数 —— 达到 [FRONT_MAX_CYCLES] 就暂停轮换 */
    private var frontCycles = 0

    /** 暂停轮换的截止时刻（`elapsedRealtime` 基准）；0 = 未暂停 */
    @Volatile private var frontRotatePausedUntil = 0L

    /**
     * 自从**引擎启动**以来，是否有过"某个候选 id 真的出过帧"。
     *
     * ★ 只用于 [advanceFrontId] 的兜底判定：若换满全部候选仍**一次都没出过帧**，
     *   说明"按 id 精确选相机"这条路在本机可能根本不成立，此时必须退回
     *   `DEFAULT_FRONT_CAMERA`（旧行为），绝不能永久空转 —— 那比改动前更差。
     */
    @Volatile private var frontEverSucceeded = false

    // ============================================================ 触发 → burst

    private fun onTriggered(reason: String) {
        // ★★★ R2（2026-10-05）：这里两件事的**顺序刻意与改造前相反**。
        //
        //   改造前是"先看全局档，是 SYSTEM 就直接返回"，因为档是全局的、不看前台也知道。
        //   现在档**取决于前台是哪个应用**（[AppPrefs.modeFor]）⇒ 必须先读前台门
        //   （它顺手把包名记进 [fgPkg]），再按那个包取档。
        //
        //   ⛔ 别把顺序倒回来：倒回来读到的是**上一次巡检留下的包名** ——
        //     用户刚切到另一个应用时，会拿旧应用那一档去做决定，
        //     症状是"某个应用偶尔不按我设的那一档走"，几乎不可能复现。
        //
        // ★★ 前台门（2026-09-28，判据 = **应用白名单**）：**第一件事**就是看前台包在不在名单里。
        //   在名单里 ⇒ 我们对它做的一切都是白做（用户明确说"这个应用不受本 app 控制"），
        //   而代价是实打实的 —— 每轮触发要开前摄 + 跑 8 帧 ML Kit（实测最密 32 次推理/秒），
        //   跟游戏抢 CPU/GPU，正是用户报的"打游戏断触"。所以这里直接掉头，连相机都不开。
        //
        // ⚠️ 门**对两种模式一视同仁**（见 [ForegroundGate.decide]）：白名单命中 ⇒ 自适应
        //   不采帧、半自动**也不弹按钮**。老判据那套"自适应严格 / 半自动宽松"的分档
        //   已经随判据一起删掉了 —— 用户说"不受控制"就该是真的不受控制。
        refreshForegroundGate("触发")
        if (fgGated) {
            _ui.update { it.copy(skipGateCount = it.skipGateCount + 1) }
            return
        }

        // ★★ 按**前台应用**取档（R2）。`SYSTEM` = 这个应用不归我们管 ⇒ 一个字都不做。
        val mode = mode()
        if (mode == RotateMode.SYSTEM) return

        // ★★ 半自动分流（2026-09-28）：**从此处开始与自适应完全分家**。
        //   半自动不需要相机、不需要人脸、不需要投票 —— 它只回答一个问题：
        //   "设备现在的姿态指向哪个方向？" 然后弹个按钮让用户拍板。
        //   ⇒ 一次 burst 都不发起，一行帧都不采（这正是用户选"设备转动触发"的红利：
        //     游戏断触的根因是相机 + 推理抢 CPU/GPU，而这条路两者都不碰）。
        if (mode == RotateMode.SEMI) {
            onSemiTriggered(reason)
            return
        }

        // 新触发 = 新额度：用户又动了一次设备，值得重新试满
        retryLeft = MAX_BURST_RETRY

        // G1：事前就知道被别人占着 —— 不抢，登记「等它松手」后直接返回。
        //
        // ★ 这里原来是「放弃、不排队、不重试」。问题在于触发是"设备动了才来"的事件：
        //   一旦把这次触发丢掉，用户摆好姿势后**不会再有第二次触发**，屏幕就停在旧方向。
        //   所以改成**让位 + 等空出来再采**（[waitingForCamera] → [onCameraAvailable]），
        //   是"不抢"，但不是"不管"。
        //
        // ★★ 2026-09-25 追加「占用判定会过期」这一条（用户报"卡住了"）：
        //   `occupiedByOthers` 只能靠 [AvailabilityCallback] 的**状态跳变**更新，
        //   一旦那个跳变被我们自己的挡板吃掉（`selfHolding==true` 时无视一切回调），
        //   标志位就**永久停在 true** —— 之后每次触发都"让步"，屏幕彻底没反应。
        //   真机铁证：`com.miui.aoc` 11:19:40 已 `DISCONNECT + DIED`，
        //   我们 11:19:44 / 11:19:49 仍在打"让步：相机在别人手里"。
        //   ⇒ 这里给判定加**保质期**：过期就当作"它可能已经松手了"，放行一次尝试。
        //     判错方向的代价是**不对称**的 —— 多试一次的代价是 bind 失败（几十毫秒），
        //     而误判"被占"的代价是用户完全没反应，所以宁可乐观。
        if (occupiedByOthers && !selfHolding) {
            if (SystemClock.elapsedRealtime() - occupiedSinceMs > OCCUPANCY_STALE_MS) {
                clearOccupiedByOthers()
                event("让步判定已过期（${OCCUPANCY_STALE_MS}ms 无新证据）→ 解除占用，放行一次尝试")
            } else {
                _ui.update { it.copy(skipOccupiedCount = it.skipOccupiedCount + 1) }
                waitingForCamera = true
                event("让步：相机在别人手里（$reason）→ 登记等待，它一松手就补采")
                return
            }
        }

        if (bursting) {
            _ui.update { it.copy(skipBusyCount = it.skipBusyCount + 1) }
            return
        }

        startBurst()
    }

    // ============================================================ 半自动：检测姿态 → 弹按钮 → 点击才转

    /**
     * 半自动的核心：**判一次设备姿态，与当前屏幕方向不一致就弹按钮**。
     *
     * ============================ 判据是"设备姿态"，不是人脸 ============================
     * 用户 2026-09-28 拍板：「检测到设备转动就弹」（而不是"等人脸判定"）。这条选择
     * 带来两个直接后果，写在这里免得以后被当成 bug 改掉：
     *  ① **一颗相机帧都不采** ⇒ 零推理开销、零相机抢占 —— 而"每轮触发开前摄 + 8 帧
     *     ML Kit 抢 CPU/GPU"正是用户报的「打游戏断触」的根因。半自动天然绕开了它。
     *  ② 准确性的上限就是**重力传感器**的上限 —— 躺着 / 斜靠着时，"重力说的方向"与
     *     "用户想看的方向"可能不是一回事（这正是自适应模式当初要用人脸的原因）。
     *     用户明确选了这个取舍，所以这里**不偷偷退回人脸**。
     *
     * ============================ ★ 姿态稳定确认（2026-09-29） ============================
     * 用户原话：「现在 180° 旋转屏幕，可能会在 90° 的时候弹一次按键、180° 的时候再谈一次按键，
     * 需要优化一下」。
     *
     * 成因：转 180° 的过程中，重力扇区会**依次**经过 0 → 1 → 2。中间停在 90° 的那一刻，
     *   姿态合法、又和当时的屏幕方向不同 ⇒ 先弹一次；停到 180° 再弹一次。
     *   而当时的两道闸都拦不住它：
     *     - [semiCooldownUntil] 只有 [SEMI_COOLDOWN_MS]（500ms）—— 跨不过一次稍慢的转动；
     *     - [semiRepeatSuppressMs] 那条要求 `target == lastTarget`，而 90° 与 180°
     *       是**两个不同的目标方向**，所以根本不进那条判据。
     *
     * ⇒ 修法：**触发时不立刻弹，先排一次"稳定确认"**（[SEMI_SETTLE_MS]）。
     *   确认窗口内每来一次新触发就把时间**往后推**（标准 debounce）⇒ 转动过程中一次都不弹，
     *   等姿态真的停住了才弹一次。90° 只是"路过"，就这样被过滤掉了。
     *
     * ★ 为什么 280ms 这么短就够：确认窗口只需盖过"经过 90° 的那一小段"，
     *   而不是整次转动 —— 因为每个新事件都会把它重置。真正防"手一抖就弹"的是触发层
     *   （`minTriggerIntervalMs` / `GYRO_RATE_DPS`），这里只负责"**别在过渡态弹**"。
     * ★ 另有一条兜底（[shouldShowSemiHint] 里的 180° 反转抑制）：即使窗口没盖住
     *   （用户真的在 90° 停了一会儿、按钮弹出来了），停在 180° 时也不会**再弹一次** ——
     *   那时按钮本来还挂在屏幕上，[RotateHintOverlay.show] 会原地换方向而不是重新出场。
     */
    private fun onSemiTriggered(reason: String) {
        // ★ 整段搬到主线程，有两个收益：
        //   ① 触发来自**传感器线程**，而 [confirmSemiHint] 的判据读的全是主线程写的状态
        //      （`semiCooldownUntil` / `semiLastTarget` / `applySemiRotation` 也在主线程）——
        //      从此这条链路上不再有跨线程竞态；
        //   ② `Handler.removeCallbacks` 的"取消上一次待确认"因此是**可靠**的
        //      （同一 Looper 内的 FIFO，不存在"取消不到"的窗口）。
        //   本函数自己只做一次 `postDelayed`，不阻塞传感器回调。
        onMain { scheduleSemiSettle(reason) }
    }

    /** 排一次姿态稳定确认（主线程）。重复调用 = 把确认时间往后推（debounce） */
    private fun scheduleSemiSettle(reason: String) {
        semiSettleReason = reason
        mainHandler.removeCallbacks(semiSettleRunnable)
        mainHandler.postDelayed(semiSettleRunnable, SEMI_SETTLE_MS)
    }

    /**
     * 取消待确认（主线程）。
     *
     * ★ **四个**调用点都必须在**收掉按钮**的同时调用它，否则会出现"按钮已经撤了、
     *   280ms 后又自己冒出来"的怪象：停手（[enterForegroundGate]）、引擎停止（[stop]）、
     *   模式切走（[applyModeChange]）、**R1 从降级里恢复**（[noteAdaptiveEnv]）。
     */
    private fun cancelSemiSettle() {
        mainHandler.removeCallbacks(semiSettleRunnable)
    }

    /**
     * 姿态稳定确认到期 —— 判据与弹按钮的**真正入口**（主线程）。
     *
     * 与旧的 [onSemiTriggered] 一字不差，只多了两道"延迟期间世界已经变了"的检查。
     */
    private fun confirmSemiHint(reason: String) {
        // ★ 延迟了 [SEMI_SETTLE_MS]，这期间模式可能已被切走（快捷开关 / 界面改档）。
        //   不看这一眼的话，切走后的 280ms 内还会弹一个按了不生效的按钮。
        if (mode() != RotateMode.SEMI) return
        // ★ 同理：这期间前台门可能已经合上（切到了白名单应用 / 桌面 / 声明式豁免的包）。
        //   [enterForegroundGate] 自己会 `cancelSemiSettle()`，这里是第二道保险。
        if (fgGated) return
        val now = SystemClock.elapsedRealtime()
        val target = semiTargetRotation()
        // ★★ `cur` 必须是**设备空间**的（2026-09-29 二次修正）：
        //   `target` 来自重力扇区/`TYPE_27`（设备空间），而 `Display.getRotation()` 给的是
        //   **面板空间** —— 内屏上两者差 180°。不换算的后果（真机现场）：
        //   刚展开就弹一个**假按钮**，一点屏幕就真的翻 180°。
        //   ⚠️ 上一版这里写着"读取端与写入端口径一致"，那个一致是"两边都错"的一致，
        //     而且 A 方案那两条读回正好两个错互相抵消 ⇒ 永远不会暴露。详见 [panelToDevice]。
        val cur = currentDeviceRotation()

        if (!shouldShowSemiHint(
                now = now,
                cooldownUntil = semiCooldownUntil,
                target = target,
                current = cur,
                lastTarget = semiLastTarget,
                lastHintAt = semiHintAtMs,
                repeatSuppressMs = semiRepeatSuppressMs(),
                // ★ 180° 反转的抑制窗口 == 按钮存活时长：语义是"这一轮按钮还在屏幕上时，
                //   同轴的另一端不再弹一个新的"（用户报的 90°→180° 就是它）。
                flipSuppressMs = semiRepeatSuppressMs(),
            )
        ) {
            // ★ 副作用要保留：屏幕**已经就是**目标方向时，把"上次弹过的目标"清掉。
            //   否则会出现这种情况 —— 用户从横屏转回竖屏（屏幕被我们转成了竖屏），
            //   过一会儿又转回横屏，而"横屏"还被重复抑制规则记着 ⇒ 按钮不弹，
            //   看着就像"时灵时不灵"（正是用户报的"不稳定"）。
            if (target != null && target == cur) semiLastTarget = -1
            // ★ 2026-09-28 追加：**每弹过一次就把目标方向挪到列表末尾**是另一条思路，
            //   这里没采用 —— 复杂度换不来用户可感知的收益。真正解决问题的是
            //   把 [semiRepeatSuppressMs] 从 10s 降到 3s、[SEMI_TAP_COOLDOWN_MS]
            //   从 4s 降到 700ms（用户报的"转完马上再转没按钮"就是后者造成的）。
            return
        }
        val t = target ?: return

        semiLastTarget = t
        semiHintAtMs = now
        semiCooldownUntil = now + SEMI_COOLDOWN_MS

        val ok = runCatching { ensureOverlay().show(t) }.getOrDefault(false)
        _ui.update {
            it.copy(
                semiShownCount = it.semiShownCount + 1,
                lastSemiTarget = t,
                overlayUsable = ok,
                overlayType = overlayRef?.activeWindowType ?: -1,
                // ★ 刚刚 show 过，这里读到的就是真值（show 返回 false 时窗口没挂上）
                hintAlive = overlayRef?.isShowing == true,
            )
        }
        if (ok) {
            event(
                "半自动：设备姿态 → ${rotName(t)}（当前 ${rotName(cur)}）" +
                    " ⇒ 弹出旋转按钮，${AppPrefs.hintMs.value / 1000} 秒内点击生效（$reason）"
            )
        } else {
            // ⚠️ 提示语不再只怪"缺少悬浮窗权限"：引擎现在跑在 SystemUI 里，
            //   那一档权限是平台授予的，失败更可能是**系统覆盖层类型被 ROM 拒了**。
            //   两档都失败才会走到这里（逐档降级见 `RotateHintOverlay.show`），
            //   所以这时连普通悬浮窗也挂不上，属于真故障。
            event("⚠️ 半自动：旋转按钮弹出失败 —— 两档窗口类型都被 WindowManager 拒了（$reason）")
        }
    }

    /**
     * 半自动模式的目标方向（`Surface.ROTATION_*`：0/1/2/3；`null` = 此刻判不出）。
     *
     * 取数顺序与理由：
     *  ① **重力**（`TYPE_GRAVITY`，~15Hz 连续）—— 唯一能实时反映"设备现在什么姿势"的量，
     *     且 [OrientationFusion.gravitySector] 的符号约定已被真机 9 组采样钉死
     *     （见 `OrientationFusionTest.matchesPlatformType27Samples`）；
     *  ② `device_orientation`（TYPE_27）的最近一次读数 —— 手机**完全平放**时重力退化成噪声
     *     （平面分量 < [GRAVITY_MIN_PLANAR_G]），而这一路可能还停在上一个有意义的象限上，
     *     比"判不出"强；
     *  ③ 都拿不到 ⇒ `null`（**宁可不弹，也不拿噪声当方向**）。
     */
    private fun semiTargetRotation(): Int? {
        val t = trigger ?: return null
        val fresh = t.gravityFresh(SystemClock.elapsedRealtime())
        return semiTarget(fresh, t.gravityAx, t.gravityAy, t.lastCode, GRAVITY_MIN_PLANAR_G)
    }

    /**
     * 用户点了按钮 —— **这时候才真的写方向**。
     *
     * ★ 这是全工程**唯一**不经过"投票 / 状态机"的写方向路径，所以每道保护都自己带上：
     *   ① 模式仍是 SEMI（用户可能已经切走了）；
     *   ② 接管还在 —— 否则 `accelerometer_rotation` 可能是 1，系统会自己跟着重力转，
     *      我们这一写等于跟系统抢方向盘，方向会来回跳；
     *   ③ 屏幕不是已经就是这个方向（避免白写 + 一次无意义的显示重配）。
     */
    private fun applySemiRotation(target: Int) {
        // ★★ 冷却与记忆清理放在**最前面**（2026-09-28 调整），不放在 writeUserRotation 之后：
        //   ① "点击"本身就是用户意图的表达 —— 无论后面走哪条早退分支（模式切走了 / 没接管 /
        //      屏幕已是该方向），都不该让刚被点过的按钮在原地立刻又弹出来；
        //   ② 冷却值已降到 [SEMI_TAP_COOLDOWN_MS]=700ms，所以"提前设"不会拦住
        //      "用户点完马上转另一个方向"这个真实诉求。
        val now = SystemClock.elapsedRealtime()
        semiCooldownUntil = now + SEMI_TAP_COOLDOWN_MS
        semiLastTarget = target
        // ★ 顺手清掉「同方向重复抑制」的记忆：用户已经通过点击表过态，
        //   不该再因"这个方向刚弹过"而拦下一次。否则会出现这类假的失灵 ——
        //   点了横屏 → 转回竖屏 → 再想转横屏，而"横屏"还在抑制期里记着 ⇒ 按钮不弹。
        //   置 0（而不是 now）是让 `now - lastHintAt` 必然大于任何抑制期，语义上等于"无记忆"。
        semiHintAtMs = 0L

        if (mode() != RotateMode.SEMI) return
        // ★ 前台门停手期间点了按钮（2026-09-29 补，用户报的"按了按钮完全没反应"的兜底）。
        //   停手时我们已经把自动旋转交还系统（`accelerometer_rotation` 可能已是 1），
        //   这一写会被传感器在几毫秒内覆盖；而且下面那道 `takeoverOn` 闸会**静默** return。
        //   根治办法是"停手时就把按钮撤掉"（见 [enterForegroundGate]），这里只兜底：
        //   万一按钮还活着，至少把原因说清楚，别让点击像掉进了黑洞。
        if (fgGated) {
            runCatching { overlayRef?.hide() }
            event("半自动：当前应用在白名单里（不受本应用控制）⇒ 点击不生效，已收起按钮")
            return
        }
        if (!_ui.value.takeoverOn) {
            event("⚠️ 半自动：尚未接管（缺「修改系统设置」授权）⇒ 点击不生效")
            return
        }
        // ★★ 写之前必须确认方向盘**真的**在我们手里（2026-09-29 修，理由是用户报的
        //   「很多软件是能旋转的，但被标成实测不可旋转」）。
        //
        //   上面那道闸查的是 `_ui.value.takeoverOn` —— 引擎**自己记的账**。账可能陈旧：
        //   前台门交接的边界 / MIUI 自己 / 用户拨了系统自动旋转开关，都能把
        //   `ACCELEROMETER_ROTATION` 改回 1 而账仍停在 true。
        //   账陈旧时这一写就是**空操作**（传感器几毫秒内覆盖它），屏幕不动，于是
        //   [scheduleSemiReadback] 会把这笔"没动"**算到应用头上**，
        //   弹出一句没来由的「旋转失败」。
        //   ⇒ 账说没接管就补一次（幂等；它自己还会再闸一次前台门与授权）；
        //     补完仍不在手里就**别写、别测**，宁可什么都不做。
        if (!_ui.value.takeoverOn || readAutoRotate() != 0) engageTakeover()
        if (readAutoRotate() != 0) {
            runCatching { overlayRef?.hide() }
            event("⚠️ 半自动：自动旋转不在本模块手里（ACCELEROMETER_ROTATION=1）⇒ 点击不生效，已收起按钮")
            return
        }
        // ★ 与盘上实际值比。`target` 是姿态给的目标方向，[currentDeviceRotation] 是"屏幕
        //   当前展示的方向" —— 本机两者同一个空间（偏置恒 0），换算不改变数值，但留这条路
        //   是为了"该不该转"永远只有一个判据表达。
        val cur = currentDeviceRotation()
        if (cur == target) {
            event("半自动：点击时屏幕已是 ${rotName(target)}，无需旋转")
            return
        }
        writeUserRotation(target)
        _ui.update { it.copy(semiTappedCount = it.semiTappedCount + 1, lastSemiTarget = target) }
        event("半自动：用户点击 ⇒ 旋转到 ${rotName(target)}（原 ${rotName(cur)}）")
        // ★ 写完方向**排一次读回**，看屏幕到底动没动；两次都没动 ⇒ 弹一句「旋转失败」。
        //   ⚠️ 2026-10-05 用户点名把结尾从"记进名单、此后永久停手"（原 A 方案 / `Uncontrollable`）
        //     改成"只提示这一次" ⇒ 判据一个字没改，改的只是**结论的用途**。
        scheduleSemiReadback(target)
    }

    // ============================================================ 半自动：转不动就提示

    /**
     * 写完 `user_rotation` 之后**读回屏幕的真实旋转**；两次都没变 ⇒ 弹一句「旋转失败」。
     *
     * ============================ 为什么必须读两次 ============================
     * 写 `Settings.System.USER_ROTATION` 与屏幕真转过去之间隔着一次**显示重配**
     * （WMS 重算配置 + 跑旋转动画），实测量级 250~400ms。只读一次的代价是**误报**：
     *   - 读太早 ⇒ 明明转过去了却弹「旋转失败」⇒ 用户不再信这句提示，
     *     下次真失败他也不看 —— 那这句提示就白做了；
     *   - 多读一次 ⇒ 只多花 [SEMI_READBACK_MS]（半秒），而这段时间按钮早已收起，
     *     用户感知不到。
     * ⇒ 宁可读两次。任何一次读回**等于目标**就立刻收工（"屏幕真转过去了"是唯一判据），
     *   连"系统接受了值但还没转"这类中间态都不会误报。
     *
     * ★ 代次（[semiReadbackGen]）解决的是"用户在半秒内又点了另一个方向"：
     *   那会排一期新任务，旧任务醒来发现代次变了就自己作废 —— 否则旧任务会用
     *   **过期的目标**去判"屏幕没动"，弹出一句没来由的「旋转失败」。
     *
     * ⚠️ **2026-10-05 用户点名改的只是"结论的用途"**：原来两次没动就把它记进
     *   「实测不可控」名单、此后**永久静默停手**（A 方案，原 `Uncontrollable`）；
     *   现在改成弹一次系统 Toast（[EngineText.rotationFailed]），**不落盘、不改后续行为**。
     *   ⇒ 下面那两段归因前置条件**一条都没删** —— 误报虽然不再"永久"，但
     *     一个不该出现的「旋转失败」同样是坏体验（用户会怀疑功能坏了），判据必须一样严。
     *
     * ============ ⛔ 归因前置条件一：方向盘必须真在我们手里（2026-09-29 补，真机事故）============
     * 用户报「**很多软件是能旋转的，但被标成实测不可旋转**，比如美团」。
     * 真机取证（本机，折叠屏内屏）：
     *
     * | 前台 | `ACCELEROMETER_ROTATION` | 写 `user_rotation=1` | 结果 |
     * |---|---|---|---|
     * | 美团 | **1**（被外部打开） | 写进去了 | **屏幕纹丝不动** |
     * | 美团 | 0（手动固定） | 写进去了 | **转得好好的** ⇒ 同一台机器！ |
     * | 设置 / 本应用 | 0 | 写进去了 | 都正常转 |
     *
     * ⇒ 结论：**屏幕没动这件事，只有在方向盘确实握在我们手里时才能算数。**
     *   自动旋转一旦是开的，`USER_ROTATION` 就是一张废纸（传感器几毫秒内覆盖它），
     *   屏幕当然不动 —— 这跟"这个应用能不能转"半点关系都没有。
     *   ⇒ 两次读回前都查一次 `ACCELEROMETER_ROTATION`，不满足就**放弃这一轮**、绝不弹提示。
     *
     * ============ ⛔ 归因前置条件二：我们写下去的那个值得还在（2026-09-29 修正）============
     * 判据统一走 [currentDeviceRotation]（= "屏幕当前展示的方向"），与写入端 [writeUserRotation]
     * **用同一套换算**。本机偏置恒 0，两边都是恒等 —— 但"读的一侧和写的一侧必须同源"这条
     * 纪律要留着：曾经一端不换算、另一端也不换算，**两个错互相抵消**，内屏明明是反的却
     * 一路报"转成功了"（见 [PanelOrientation] 类注释）。
     */
    private fun scheduleSemiReadback(target: Int) {
        val gen = ++semiReadbackGen
        // 判据要落到「包 + 形态」上，两个都得有：包名取不到（前台变了 / 读不到）就**不提示**
        // —— 与全工程"读不到就不下结论"一致，宁可少提示一次，不许乱提示一次。
        val pkg = _ui.value.foregroundPkg.ifEmpty { null } ?: return
        val form = AppPrefs.screenForm.value
        mainHandler.postDelayed({
            if (gen != semiReadbackGen) return@postDelayed
            // ★★ 归因前置条件一（2026-09-29 补，这是那个 bug 的另一半根因）：
            //   "屏幕没动"这件事**只有在方向盘确实握在我们手里时**才能算数。
            //   若此刻 `ACCELEROMETER_ROTATION != 0`，屏幕不动的原因是**传感器在覆盖我们的
            //   写入**，与这个应用能不能转毫无关系 —— 真机实证：美团在 accel=1 时写
            //   `user_rotation` 完全不生效（把 accel 固定成 0 后同一台机器上它转得好好的）。
            //   ⇒ 不满足就**放弃这一轮**，绝不提示。
            if (readAutoRotate() != 0) {
                event("旋转提示：放弃判定（自动旋转被打开了，屏幕没动能归因给应用以外的东西）")
                return@postDelayed
            }
            // ★ `wrotePanel` 是"我们写下去的那个数"，判"屏幕没动"之前先确认**它还在**
            //   （被别的写入覆盖 ⇒ 屏幕不动的原因不是"这个应用转不了"，同样不能归因）。
            //   ⚠️ 读取端与写入端**必须用同一个判据空间**（都用换算后的量）：
            //      曾经一端不换算、另一端也不换算，两个错正好抵消 ⇒ 内屏明明是反的
            //      却一路报"转成功了"。本机偏置恒 0，两边都是恒等；
            //      但别再让"读的一侧"和"写的一侧"各写一套。
            val wrotePanel = deviceToPanel(target)
            if (currentDeviceRotation() == target) return@postDelayed
            // 第一次没变 —— 再给一次机会（见 KDoc 的"读两次"）
            mainHandler.postDelayed({
                if (gen != semiReadbackGen) return@postDelayed
                // 同一个前提再查一次：这半秒里方向盘可能又被交还了
                if (readAutoRotate() != 0) return@postDelayed
                if (readUserRotation() != wrotePanel) {
                    event("旋转提示：放弃判定（我们写下的 $wrotePanel 已被改写，屏幕没动不能算到应用头上）")
                    return@postDelayed
                }
                if (currentDeviceRotation() == target) return@postDelayed
                notifyRotateFailed(pkg, form, target)
            }, SEMI_READBACK_MS)
        }, SEMI_READBACK_MS)
    }

    /**
     * 两次读回屏幕都没动 ⇒ 弹一句「旋转失败」。
     *
     * ⚠️ **只提示这一次**：不落盘、不刷新前台门、不改变后续任何行为 ——
     *   用户再点、再失败，就该再看到这句（去重只交给 [AppToast] 那 2 秒）。
     *   ⛔ 别在这里加"记一笔、以后不提示"的逻辑 —— 那正是 2026-10-05 用户点名删掉的那个功能。
     *
     * ★ 为什么由**引擎**弹：这一刻用户正看着**别的应用**（他是在那个应用里点的悬浮按钮），
     *   而 Android 10+ 起**后台普通应用弹 Toast 会被系统静默丢弃**（见 [AppToast] ① 的实测）；
     *   引擎跑在 SystemUI 系统进程里 ⇒ 不受那条限制。文案按系统语言选，见 [EngineText.rotationFailed]。
     *
     * ★ 参数 [pkg] / [form] / [target] **只用来写日志**（提示文案是固定的一句）
     *   —— 保留它们是为了让 `✗ 旋转失败` 这条记录能直接看出"是哪个应用、哪块屏、想去哪个方向"。
     */
    private fun notifyRotateFailed(pkg: String, form: ScreenForm, target: Int) {
        // ★ 不必再包 runCatching —— [AppToast] 自己吞并记 `HyperPlusToast` 标签（它的类注释有约）。
        AppToast.show(context, EngineText.rotationFailed(context))
        event(
            "半自动：$pkg 在${form.label}上写了 ${rotName(target)} 但屏幕没有变化（两次读回都没变）" +
                " ⇒ 已提示「旋转失败」（不记录、不改变后续行为）"
        )
    }

    private fun startBurst() {
        // ★ 先撤销「保温期到点就关相机」的预约：新一轮已经来了，相机必须留着。
        //   漏了这一句，保温期的定时器会在新 burst 中途把相机关掉（踩过类似的坑）。
        keepWarmJob?.cancel()
        keepWarmJob = null
        val seq: Int
        var alreadyHolding: Boolean
        // 只用锁保护几个标志位的读写；真正的相机操作交给 [onMain] 串行（主线程 Looper 本身就是队列）。
        synchronized(burstLock) {
            bursting = true
            burstFrameCount = 0
            // ★ R1：与帧计数同生命周期 —— 不过这一句会让上一轮的"见到过人脸"
            //   冒充这一轮的结论，降级就永远判不出来。
            burstHadUsableFace = false
            burstStartedAt = SystemClock.elapsedRealtime()
            seq = ++burstSeq
            alreadyHolding = selfHolding
            // ★ 投票是**每轮独立**的：上一轮的票绝不带进这一轮，否则旧方向会靠历史票一直赢
            tally.reset()
            voteValid = 0
            voteWinner = -1
            voteConfident = false
            voteTextCache = ""
            voteRatioCache = 0f
            // 末段共识的缓冲同理，必须一起清 —— 否则上一轮的末段会冒充这一轮的
            recentSectors.fill(0)
            recentIdx = 0
            recentFilled = 0
        }

        // ★ 超时保护（实测踩过）：绑好相机后若帧一直不来
        //   （典型场景：宿主退到后台 CameraX 自己解绑、或相机被别的 App 抢走），
        //   burst 会永远卡在"进行中"，实测出现过「8 帧 / 40010ms」这种 40 秒的僵尸 burst。
        //   ⚠️ 判据必须带上 seq —— 否则会误杀后续的 burst（见 [burstSeq] 注释）。
        scope.launch {
            delay(BURST_TIMEOUT_MS)
            if (seq == burstSeq && bursting && burstFrameCount < BURST_FRAMES) {
                val got = burstFrameCount
                // 带上分析器诊断：0 帧 + 在途=1 ⇒ inFlight 泄漏（帧在入口被挡掉）；
                //               0 帧 + 在途=0 ⇒ 相机压根没开流。
                event(
                    "⚠️ burst 超时（${BURST_TIMEOUT_MS}ms 收到 $got 帧；" +
                        "analyzer 总帧=${analyzer?.totalFrames ?: -1} " +
                        "在途=${analyzer?.inFlightNow ?: -1} 峰值=${analyzer?.maxInFlight ?: -1}）→ 放弃",
                )
                finishBurst(gaveUp = true)
                // ★ 一帧都没给 = 相机压根没开流，最常见的真因是**被别的客户端抢走**
                //   （实测：`com.miui.aoc`（小米智能感知）连上来会把我们的会话直接踢掉，
                //   日志里是 `CameraService::connect evicting conflicting client` +
                //   `Device error received, code 3` + `Stop camera streaming`）。
                //   这时用户已经摆好新姿势了，不会再有触发 ⇒ 必须自己补试，
                //   否则屏幕会一直停在上一次的方向（现象就是"转了手机没反应"）。
                if (got == 0) {
                    // ★★ 关键一修（2026-09-25，用户报"测试到一半又没反应了"）：
                    //   **先把这条死会话丢掉，再登记让位。**
                    //
                    //   为什么必须丢：`selfHolding` 是在 [bindAnalysisUseCase] 里
                    //   **先置 true 再 bind** 的，而 bind 是**异步**的 ——
                    //   `com.miui.aoc` 随后 `Evicted by device 0 client for package com.miui.aoc`
                    //   把会话踢掉时，没有任何回调通知我们 ⇒ `selfHolding` 永远停在 true，
                    //   `analysis` 还挂着一个**已死的 use case**。
                    //   后续每一次 burst 因此都走"相机已常驻，直接取帧"、干等 1500ms、0 帧、
                    //   烧一次补试额度；而补试又因 `selfHolding=true` 绕过了
                    //   [scheduleRetry] 里的 G1 让位闸 ⇒ **原地打转**。
                    //   真机铁证：`analyzer 总帧=596` 之后**再也不动**，burst 却一个接一个超时。
                    //
                    //   ⇒ 0 帧就是"我这条会话已经废了"的硬事实。unbind 丢掉它之后，
                    //     `selfHolding` 归 false，下一次才会真的重新 bind，G1 让位闸也才恢复作用。
                    releaseCamera("本轮 0 帧 —— 会话多半已被系统常开相机踢掉，丢弃以便重新 bind")
                    // 0 帧 = 相机压根没开流，真因多半是**被别人抢走**（见上面的实测记录）。
                    //
                    // ★ 这里才是"冲突"的**唯一可靠入口**（2026-09-25 厘清）：
                    //   [registerAvailability] 那条 `onCameraUnavailable` 判据实际上抓不到这个
                    //   场景 —— 我们在等帧的时候 `selfHolding == true`，回调会被我们自己故意
                    //   挡掉（不挡的话每次 burst 都会把自己误判成"被抢"）。
                    //   于是 `conflictCount` 长期停在 0，而 `dumpsys media.camera` 里
                    //   明明写着 `Too many cameras already open` + `Blocked by com.miui.aoc`
                    //   ⇒ 界面在说假话。现在用"一帧都没给"这个硬事实补上这一笔。
                    conflictCount++
                    _ui.update { it.copy(conflictCount = conflictCount) }
                    markOccupiedByOthers()
                    // ★ 这一步是 2026-09-25「换前摄 id」的关键：0 帧说明**这个 id 拿不到**，
                    //   记账后若达到阈值就自动换下一个候选 id（见 [advanceFrontId]）。换 id
                    //   的物理依据是各设备的 `Conflicting devices:` 分成了两个互斥组
                    //   （`{1,7,8}` 与 `{5,9}`），详见 [frontIdOrder] 的注释。
                    //   ⚠️ 必须在 [markOccupiedByOthers] **之后**调：advanceFrontId 会清掉
                    //     占用判定（旧 id 的结论对新 id 不成立），顺序反了就等于清了又置。
                    noteFrontIdFailure("本轮 0 帧（id=$currentFrontId）")
                    // 除了定时补试，还登记"等它松手"这条更准的路（[maybeRetryForFreedCamera]）。
                    waitingForCamera = true
                    scheduleRetry("相机一帧都没给（多半被系统常开相机占用）")
                }
            }
        }

        // ★★ 「相机已常驻」这句话**不能盲信**（2026-09-25，用户报"测试到一半又没反应了"）。
        //
        //   机理：`com.miui.aoc`（小米 AON）会把我们的前摄会话**异步踢掉**，而 CameraX
        //   不会因此回调到我们这里 ⇒ `selfHolding` 停在 true、`analysis` 挂着一个**已死的
        //   use case**。于是每次 burst 都走这条"直接等帧"，干等 [BURST_TIMEOUT_MS]=1500ms、
        //   0 帧、烧一次补试额度 —— 而补试又因为 `selfHolding=true` 绕过 G1 让位闸，
        //   继续在原地打转。**这就是"没反应"的死循环。**
        //
        //   唯一可靠的事实是"**帧不再来了**"（见 [lastFrameAtMs]）：
        //   会话活着时 30fps 的帧是连续的，1s 一帧都没有 ⇒ 会话已废。
        //   ⇒ 在这里当场把它丢掉，下面就会走**重新 bind** 的路，而不是干等超时。
        //   ⚠️ 阈值取 [SESSION_STALE_MS]（1000ms）：比一帧的周期（~33ms）宽 30 倍，
        //     不会被偶发卡顿误杀；又明显小于 [BURST_TIMEOUT_MS]，所以能真正省下那 1.5s。
        //
        //   ★★ 2026-09-25 追加：证伪之后**顺带换 id**。死会话有两种成因 ——
        //     "被 aoc 踢" 和 "这个 id 本身就开不出来"，前者换个 id 往往就活了。
        //     换 id 是有代价的（要重新 bind），所以只在**同一 id 反复证伪**时才换
        //     （走 [noteFrontIdFailure] 的阈值），不是每次抖一下就换。
        if (alreadyHolding && sessionLooksDead()) {
            event("⚠️ 「相机已常驻」已被证伪（${SESSION_STALE_MS}ms 无帧）→ 丢掉死会话，改为重新 bind")
            releaseCamera("会话疑似已被系统常开相机踢掉")
            alreadyHolding = false
            noteFrontIdFailure("会话被证伪（id=$currentFrontId）")
            // 换过 id 的话，`analysis` 已被 releaseCamera 清掉，下面自然走重新 bind
        }

        // 响应优先：相机已常驻，直接等帧
        if (alreadyHolding) {
            burstBindRequestedAt = burstStartedAt
            event("burst 开始（相机已常驻，直接取帧）")
            return
        }

        burstBindRequestedAt = burstStartedAt
        // ★★ 相机操作整段投到主线程 —— CameraX 的 bind/unbind 有 checkMainThread()。
        //   以前这里直接从调用线程执行：触发路径（主线程）侥幸能过，
        //   校准路径（scope.launch = Dispatchers.Default）必抛 —— 见 [onMain]。
        onMain { bindAnalysisUseCase(seq) }
    }

    /**
     * 补试一次采集。
     *
     * ★ 为什么需要它（实测踩出来的）：触发源是"设备朝向变化"事件，**只在设备动的时候来**。
     *   而相机被别人抢走时那次 burst 会 0 帧放弃 —— 用户此时已经把手机摆好了，
     *   不会再有第二次触发 ⇒ 判定永远落不了地，屏幕停在上一次的方向。
     *   表现就是"我转了啊，屏幕没反应"，而日志里只有一条超时，看的人一头雾水。
     *   ⇒ 补试把"这一次触发"的采样机会用完（[MAX_BURST_RETRY] 次），而不是干等下次移动。
     *
     * 电量代价可控：补试只在**触发之后**发生，且最多 2 次（≈1.4 秒），不会变成轮询。
     */
    private fun scheduleRetry(why: String) {
        if (mode() != RotateMode.ADAPTIVE) return
        if (retryLeft <= 0) {
            event("补试额度已用完，等下一次触发（原因：$why）")
            return
        }
        event("补试：${RETRY_DELAY_MS}ms 后再采一次（原因：$why）")
        scope.launch {
            delay(RETRY_DELAY_MS)
            if (mode() != RotateMode.ADAPTIVE) return@launch
            if (bursting) return@launch
            // 补试同样要过 G1：此刻相机若仍被别人占着，就老实让步，别硬抢
            // ★ 而且**不扣额度** —— 否则"被占用而放弃"会把额度白烧掉，
            //   等 `com.miui.aoc` 真松手时反而没额度了。
            //   真机日志（2026-09-25）：`相机 1 已空闲，但本次触发的补采额度已用完` —— 就是这个坑。
            if (occupiedByOthers && !selfHolding) {
                event("补试放弃：相机仍被其他应用占用（额度保留，等它松手时事件驱动补采）")
                return@launch
            }
            if (retryLeft <= 0) {
                event("补试额度已用完，等下一次触发")
                return@launch
            }
            retryLeft--
            startBurst()
        }
    }

    /**
     * 真正执行相机绑定的部分。**必须运行在主线程**（原因见 [onMain] 的实测栈）。
     *
     * 每次 burst 都用**全新的** ImageAnalysis，用完即丢（[releaseCamera] 里置 null）——
     * 避免反复 bind/unbind 后 CameraX 内部残留上一条 use case 的状态。
     */
    private fun bindAnalysisUseCase(seq: Int) {
        if (seq != burstSeq || !bursting) return

        val provider = cameraProvider ?: run {
            event("⚠️ cameraProvider 未就绪，放弃本次 burst")
            bursting = false
            return
        }
        val a = analyzer ?: run {
            // ★ 这条以前是静默丢弃：触发早于相机就绪时被吞掉，日志里什么都看不到
            event("⚠️ 分析器未就绪（相机/权限未完成初始化），放弃本次 burst")
            bursting = false
            return
        }

        val analysisUseCase = runCatching {
            ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(executor, a) }
        }.getOrElse { e ->
            // 诊断用：区分是 build() 失败还是 bind 失败（前者与相机状态无关）
            Log.w(TAG, "ImageAnalysis.build 失败", e)
            event("⚠️ ImageAnalysis 构建失败（${e.javaClass.simpleName}: ${e.message}），放弃本次 burst")
            bursting = false
            analysis = null
            return
        }
        analysis = analysisUseCase

        selfHolding = true
        _ui.update { it.copy(cameraHeld = true) }

        // ★ 用**当前候选 id** 精确选相机，而不是写死的 DEFAULT_FRONT_CAMERA。
        //   `bind` 刻意做成"每次调用都重读 [currentFrontId]"——因为失败重试之间
        //   可能已经换过 id（见 [advanceFrontId]），用捕获下来的旧 id 会白试一次。
        val bind = {
            provider.bindToLifecycle(lifecycleOwner, frontSelectorFor(currentFrontId), analysisUseCase)
        }

        runCatching { bind() }.onFailure { e ->
            // ★ 失败计数（2026-10-03）：绑不上是"转了手机没反应"的头号成因，
            //   而它此前只有日志。见 [EngineErrors]。
            EngineErrors.bump(EngineErrors.CAMERA_BIND)
            // 把异常原文与栈打出来，别再靠猜
            Log.w(TAG, "bindToLifecycle 失败（第 1 次，id=$currentFrontId）", e)
            event(
                "相机绑定失败（id=$currentFrontId，${e.javaClass.simpleName}: ${e.message}）" +
                    "，${BIND_RETRY_DELAY_MS}ms 后重试",
            )
            // ★ 绑不上最常见的原因就是 id 被占（`Too many cameras already open`）
            //   ⇒ 立刻记账换 id，重试就会用新的那个。
            noteFrontIdFailure("bind 失败：${e.javaClass.simpleName}")
            scope.launch {
                delay(BIND_RETRY_DELAY_MS)
                // ★ 重试同样必须回主线程
                onMain {
                    if (seq != burstSeq || !bursting) return@onMain
                    runCatching { bind() }
                        .onSuccess {
                            selfHolding = true
                            _ui.update { it.copy(cameraHeld = true) }
                            event("相机绑定成功（id=$currentFrontId）")
                        }
                        .onFailure { e2 ->
                            EngineErrors.bump(EngineErrors.CAMERA_BIND)
                            Log.w(TAG, "bindToLifecycle 失败（重试）", e2)
                            releaseCamera("bind 连续失败，丢掉半开状态")
                            bursting = false
                            noteFrontIdFailure("bind 重试仍失败：${e2.javaClass.simpleName}")
                            event(
                                "让步：相机绑定连续失败（${e2.javaClass.simpleName}: ${e2.message}），" +
                                    "放弃本次 burst（下一次触发将用 id=$currentFrontId）",
                            )
                        }
                }
            }
        }
    }

    private fun finishBurst(gaveUp: Boolean = false) {
        synchronized(burstLock) {
            if (!bursting) return
            bursting = false
        }
        val now = SystemClock.elapsedRealtime()
        val dur = now - burstStartedAt
        val frames = burstFrameCount

        _ui.update {
            it.copy(
                burstCount = if (gaveUp) it.burstCount else it.burstCount + 1,
                lastBurstMs = if (gaveUp) it.lastBurstMs else dur,
                lastBurstFrames = if (gaveUp) it.lastBurstFrames else frames,
            )
        }
        if (!gaveUp) {
            // ★ burst 收尾裁定：帧到此为止（相机马上释放，不会再有帧），
            //   必须把还停在 CANDIDATE 的候选方向落定 —— 否则判定永不着地。
            //   minPendingMs 取 holdMs 同量级（250ms ≈ 4 帧）：低于这个时长宁可
            //   保持原方向，避免把 1~2 帧的噪声当成"新方向"采纳。
            val before = _ui.value
            val settled = decider.settle(now, minPendingMs = 250L)
            // ★ 投票优先于状态机收尾：票是"整轮多数"的直接体现，而 settle 只看最后几帧的
            //   待定格 —— 恰好最后两帧是侧脸/模糊时，settle 就会把方向带偏一格，
            //   这正是用户报的「人脸识别和旋转方向不一致」。票不够才退回状态机。
            // ★ 重力当尺子：拿本轮**多数票**当"实测扇区"，给符号位记一票
            //   （放在收尾而不是每帧，是为了把 IPC/落盘次数压到每轮一次）。
            //   若这一票让证据压倒性翻转，本轮的结果就要按**纠正后**的符号重算 ——
            //   否则写进去的还是那个镜像方向，得再等一轮才纠正。
            val flipped = voteConfident && noteSignEvidence(voteWinner)

            // ★ 三级裁定，越靠前越快、越贴"现在的姿势"：
            //   ① 整轮多数票 —— 正常路径；
            //   ② **末段共识** —— 专治"转了 180° 画面没反应"：转 180° 必然路过中间扇区，
            //      整轮票被切散（如 `0:4 1:3 2:3 3:2`）谁都攒不够票；但"最后几张脸朝哪"
            //      本来就够判了。见 [recentConsensus]。
            //   ③ 状态机收尾 —— 最后兜底。
            val recent = if (voteConfident) -1 else recentConsensus()
            val byRecent = recent in 0..3
            val decided = when {
                voteConfident && flipped -> OrientationFusion.mirror(voteWinner)
                voteConfident -> voteWinner
                byRecent -> recent
                else -> settled.rotation
            }
            _ui.update {
                it.copy(
                    rotation = decided,
                    deciderState = settled.state.name,
                    voteCounts = voteText(),
                    voteValid = voteValid,
                    voteWinner = voteWinner,
                    voteRatio = voteRatio(),
                    voteConfident = voteConfident,
                )
            }
            event(
                "burst 完成：$frames 帧 / ${dur}ms  投票[${voteText()}]  " +
                    "重力参照=${gravitySectorNow()?.toString() ?: "不可用"}" +
                    "（符号位证据 ${signSameCount}对/${signFlipCount}反）  " +
                    "判定 ${before.deciderState}(${before.rotation}) -> " +
                    "${settled.state.name}(${settled.rotation})  " + when {
                        voteConfident ->
                            "→ 采纳多数票 $voteWinner（$voteValid 票中 ${(voteRatio() * 100).toInt()}%）" +
                                if (flipped) "　⚠️ 符号位刚被纠正，本轮已按纠正后输出 $decided" else ""
                        byRecent ->
                            "→ 整轮票被切散（$voteValid 票），改用**末段共识** $recent" +
                                "（最后 $RECENT_VOTES 票一致）"
                        else ->
                            "→ 票不足（$voteValid 票）且末段不齐，沿用状态机结果"
                    },
            )
            // ★ 收尾才允许退回状态机结果；且本轮得**确实采到足够多的帧** ——
            //   被抢走 / 被打断的 burst 帧数很少，那时 settle() 给出的可能只是
            //   "开头那几帧的旧方向"。宁可不动，也不能拿残帧去写屏幕。
            // `force = byRecent`：末段共识本身就是一个**独立于票数门槛**的结论，
            // 必须绕过 "byVote || byDecider" 那道闸才能落盘 —— 否则算出来了却写不出去。
            applyRotationIfNeeded(
                allowDeciderFallback = frames >= MIN_FRAMES_FOR_SETTLE,
                force = byRecent,
            )

            // ★ 正常收尾且确实采到过帧（哪怕没脸）⇒ 相机本身没问题，清掉"等补采"。
            //   ⚠️ 这一句**只能放在 gaveUp 之外**：被抢占时 `gaveUp=true`，而那时
            //   `frames` 常常 > 0（采了一半才被踢掉）—— 若在 gaveUp 分支里也清掉，
            //   就会把我们最需要补采的那条路掐断（踩过）。
            if (frames > 0) {
                waitingForCamera = false
                // ★ 顺带撤销"被别人占着"的判断：本轮真的出帧了，相机就是通的。
                //   没有这一句的话，一旦（误）判成 occupied，G1 会把后续触发一直挡在门外，
                //   而 onCameraAvailable 未必再来（占用状态没有变化就不会回调）⇒ 永久卡死。
                if (occupiedByOthers) clearOccupiedByOthers()
                // ★ 这个 id 是通的 ⇒ 重置失败计数、取消轮换暂停，并记成"下次优先用"。
                //   只在出帧时记，避免把 0 帧的坏 id 写进偏好、下次启动先试一个坏的。
                noteFrontIdSuccess()
            }
            // ★★★ R1（2026-10-05）：环境不可用的降级记账。
            //   ⚠️ 位置是**刻意选的**：放在 `frames > 0` 之后 —— 那个分支刚确认了
            //     "相机是通的、确实出帧了"，所以这里若仍然"一帧可用人脸都没有"，
            //     就**只可能**是环境问题（暗光 / 没对着镜头 / 脸被挡），
            //     而不是相机故障（那种走 `gaveUp`，根本不在本分支里）——
            //     把相机故障记到"环境"头上会让降级掩盖真正的故障。
            noteAdaptiveEnv(hadFace = burstHadUsableFace, frames = frames)
        }

        // G4：省电优先下**不再立刻关**，而是进入 [CAMERA_KEEP_WARM_MS] 保温期；
        //     响应优先则干脆常驻。
        // ★ 旧行为（"burst 一结束就 unbind"）是"响应太慢"的**头号原因**：
        //   用户连续摆姿势时，每一次触发都要重新 bind + 开流（实测 openMs≈222ms），
        //   这段时间**一帧都没有** —— 表现就是"我转了，屏幕半天没反应"。
        //   更糟的是"刚 bind / 刚 unbind"的瞬间恰好最容易被 `com.miui.aoc` 抢走
        //   ⇒ 越频繁开关，被抢的概率越高。
        if (AppPrefs.strategy.value == CaptureStrategy.POWER_SAVING) {
            scheduleCameraRelease()
        }
    }

    /**
     * 预约「保温期结束后释放相机」。
     *
     * 每次收尾都重置计时（[keepWarmJob] 先 cancel 再建），所以语义是
     * **"连续 no-burst 满 [CAMERA_KEEP_WARM_MS] 才真正关机"** —— 只要用户还在转，
     * 相机就一直热着；一旦停下来，[CAMERA_KEEP_WARM_MS] 后关掉，电量代价回到旧水平。
     *
     * ⚠️ 触发时 `bursting` 可能刚被下一轮置位（时序：触发 → startBurst → 本协程醒来）。
     *   所以醒来后必须**再看一眼** [bursting]，true 就什么都不做 —— 否则会把
     *   正在跑的 burst 的相机关掉。
     */
    private fun scheduleCameraRelease() {
        keepWarmJob?.cancel()
        keepWarmJob = scope.launch {
            delay(CAMERA_KEEP_WARM_MS)
            if (bursting) return@launch
            // 响应优先可能在这一刻被切回来 —— 那就不该关
            if (AppPrefs.strategy.value != CaptureStrategy.POWER_SAVING) return@launch
            releaseCamera("保温期结束（${CAMERA_KEEP_WARM_MS}ms 无新触发）")
        }
    }

    /**
     * 「我们还握着相机」这句话现在还成立吗？
     *
     * 判据只有一条：**最近 [SESSION_STALE_MS] 内有没有收到过帧**（见 [lastFrameAtMs]）。
     *
     * ⚠️ 两个"不知道"的情形必须返回 false（= 不推翻），否则会误杀：
     *  - `lastFrameAtMs == 0`：本进程还没收到过任何帧（引擎刚起来 / 相机刚 bind）；
     *  - `selfHolding == false`：本来就没握着，无所谓。
     */
    private fun sessionLooksDead(): Boolean {
        if (!selfHolding) return false
        val last = lastFrameAtMs
        if (last <= 0L) return false
        return SystemClock.elapsedRealtime() - last > SESSION_STALE_MS
    }

    private fun releaseCamera(why: String) {
        val provider = cameraProvider
        val a = analysis
        // ★ unbind 同样必须回主线程（见 [onMain]）。以前是从分析线程 / 协程直接调，
        //   `runCatching` 把 IllegalStateException 吞了 ⇒ 相机其实**没释放**，
        //   CameraX 里 use case 越堆越多，后续 bind 才会莫名其妙地失败。
        if (provider != null && a != null) {
            onMain { runCatching { provider.unbind(a) } }
        }
        // ★ 丢弃 use case，下次 burst 重建 —— 复用实例会让 CameraX 内部状态残留
        analysis = null
        selfHolding = false
        _ui.update { it.copy(cameraHeld = false) }
        Log.i(TAG, "释放相机（$why）")
    }

    // ============================================================ 判定 → 接管

    private fun pickRoll(f: FaceFrame): Float? = when {
        f.faceCount <= 0 -> null
        // ★ 丢弃低置信帧：eulerZ 与「左右眼连线算出的 roll」是**两个独立来源**，
        //   正常帧两者差 1~5°；差超过 30° 说明这一帧**测错了**（侧脸、运动模糊），
        //   而不是"脸真的转过去了"。以前只是把它记进 CSV 却照样喂给判定器，
        //   于是偶发地把方向推错一格 —— 这就是「非常偶发方向错误」的来源之一。
        //   帧被丢弃是安全的：faceLostMs=2000ms，丢几帧不会让方向变"未知"。
        f.lowConf -> null
        f.eulerZ.isFinite() -> f.eulerZ
        // ★ 符号必须取反！实测 eulerZ ≈ -eyeRoll（-82.5 vs +87.1）。
        //   回退到 eyeRoll 时若不取反，那几帧的方向会整体反 180° —— 另一个偶发源。
        f.eyeRoll.isFinite() -> -f.eyeRoll
        else -> null
    }

    // ------------------------------------------------------------ 多帧投票（本轮多数）

    /**
     * 记一票并重算多数（分析线程调用）。
     *
     * 只做纯计数（实际算术在 [VoteTally] 里，有单测），这里只负责"改 + 快照"。
     */
    private fun tallyVote(sector: Int) {
        if (sector !in 0..3) return
        val total: Int
        val win: Int
        val conf: Boolean
        val txt: String
        val rat: Float
        synchronized(burstLock) {
            tally.add(sector)
            total = tally.total
            win = tally.winner
            conf = tally.confident
            txt = tally.text()
            rat = tally.ratio()
            // ★ 末段共识用的环形缓冲（见 [recentConsensus]）：只留最后 [RECENT_VOTES] 票。
            //   必须和 tally 一起放在锁里 —— 两者代表同一份"本轮观测"，不能出现半更新。
            recentSectors[recentIdx] = sector
            recentIdx = (recentIdx + 1) % recentSectors.size
            if (recentFilled < recentSectors.size) recentFilled++
        }
        voteValid = total
        voteWinner = win
        voteConfident = conf
        voteTextCache = txt
        voteRatioCache = rat
    }

    /**
     * **末段共识**：最后 [RECENT_VOTES] 票若完全一致，返回那个扇区；否则返回 -1。
     *
     * ★ 为什么单靠 [VoteTally] 不够（用户报的真实症状）：
     *   "手机都反转 180 度了，画面都没反应"。**转 180° 必然会路过中间的扇区**，
     *   于是整轮票被切散成 `0:4 1:3 2:3 3:2` 这种形状 —— 没有任何一个扇区能攒到
     *   [MIN_VOTES] 票 ⇒ `voteConfident` 永远为 false ⇒ 整轮不下结论。
     *   而新人一眼就能看出的信息"**最后几张脸都朝那边**"被整轮平均掉了。
     *
     *   所以收尾时补这一道：整轮定不了论，就看**末段**是不是已经一致 ——
     *   是就采纳它。它比"退回状态机"更快（不必再等 holdMs），也比整轮多数更贴"现在的姿势"。
     *
     * ⚠️ 它只在**整轮定不了论**时兜底（见 [finishBurst]），正常情况下永远走不到这里。
     */
    private fun recentConsensus(): Int =
        VoteTally.recentConsensus(recentSectors, recentFilled, RECENT_VOTES)

    /** 票面，形如 `0:1 3:9`；一张票都没有时返回空串 */
    private fun voteText(): String = voteTextCache

    /** 获胜方得票占比（0..1）；没有票时为 0 */
    private fun voteRatio(): Float = voteRatioCache

    // ------------------------------------------------------------ 重力锚定（见 [OrientationFusion]）

    /**
     * 当前重力给出的**参照扇区**（只用来校验符号位，不参与方向运算）；`null` = 这一路暂时不可用。
     *
     * 三种不可用情形，都要**放弃这一帧的校验**（而不是硬编一个 0 去凑证据）：
     *  1. 触发层没起来 / 没注册到重力传感器；
     *  2. 读数太旧（[DeviceOrientationTrigger.gravityFresh] 为 false）——
     *     拿停用期间的陈旧方向当参照，会给出**错误**的符号位结论，比没有参照更糟；
     *  3. **手机完全平放** ⇒ 平面分量只剩噪声，[OrientationFusion.gravitySectorOrNull] 返回 null。
     */
    private fun gravitySectorNow(): Int? {
        val t = trigger ?: return null
        val now = SystemClock.elapsedRealtime()
        if (!t.gravityFresh(now)) return null
        return OrientationFusion.gravitySectorOrNull(
            t.gravityAx, t.gravityAy, GRAVITY_MIN_PLANAR_G,
        )
    }

    /**
     * 拿重力当尺子，给「当前符号位对不对」记一票。**这是本轮的修复核心。**
     *
     * 背景：`u = θrel/90` 本身已经把方向算全了，唯一未知的是人脸链路的符号
     * （ML Kit 的 `headEulerAngleZ` 与 `θrel` 之间隔着"前置镜像/左右眼定义/传感器朝向"三层）。
     * `sign` 取反的效果恰好是 **1↔3 对调、0/2 不变** ⇒ 只在横屏上表现为"方向反过来"，
     * 正是用户报的「横屏有概率反过来」「转动方向越来越反」。
     *
     * 判据：日常用机时人头是朝着世界正立的，此时「人脸算出的扇区」应当**等于**重力扇区。
     * 详见 [OrientationFusion.verdict]（含"为什么竖屏帧一律不算数"的原因）。
     *
     * ★ 它**不改方向、不参与运算**，只在证据压倒性时翻一次符号位并落盘 ——
     *   这样"已校准"这句话才第一次有了证据，而不是"用户点过按钮"。
     */
    private fun noteSignEvidence(faceSector: Int): Boolean {
        val g = gravitySectorNow() ?: return false
        when (OrientationFusion.verdict(faceSector, g)) {
            OrientationFusion.Verdict.SAME -> signSameCount++
            OrientationFusion.Verdict.FLIPPED -> signFlipCount++
            // 竖屏/倒置的重力扇区、以及"头真的歪着"的帧都对符号位零信息量 —— 见 verdict 的注释
            OrientationFusion.Verdict.AMBIGUOUS ->
                return false
        }
        val same = signSameCount
        val flip = signFlipCount
        if (OrientationFusion.shouldFlip(same, flip, SIGN_MIN_SAMPLES)) {
            val old = decider.sign
            val next = -old
            decider.sign = next
            AppPrefs.persistCalibration(next, AppPrefs.offsetDeg.value)
            signSameCount = 0
            signFlipCount = 0
            event(
                "★ 符号位自动纠正：sign $old -> $next　" +
                    "（重力当尺子的证据：等于重力 $same 帧 / 等于镜像 $flip 帧）" +
                    "　—— 这就是「横屏方向反过来」的根因",
            )
            _ui.update {
                it.copy(gravitySector = g, signSame = 0, signFlip = 0, signConfirmed = false)
            }
            return true
        }
        val confirmed = OrientationFusion.signConfirmed(same, flip, SIGN_MIN_SAMPLES)
        _ui.update {
            it.copy(
                gravitySector = g,
                signSame = same,
                signFlip = flip,
                signConfirmed = confirmed || it.signConfirmed,
            )
        }
        return false
    }

    /**
     * @param allowDeciderFallback 票数不足时，是否允许**退回状态机结果**。
     *
     * ★ 只有 burst **收尾**时才传 true。每帧都允许的话会出一个真机实证过的坏现象：
     *   [onFrame] 每帧都调本函数，而 [OrientationDecider] 的 `smoothed/committed` 是
     *   **跨 burst 不清零**的（它得靠上一轮的值做平滑起点），于是"刚开流"那几帧的状态机
     *   还停在**上一轮的姿势**上、且 `state == STABLE` —— 满足旧判据，于是在 burst 进行到
     *   ~250ms 时就把方向写进 user_rotation，等票攒够再改回正确值。
     *   真机日志（2026-09-25）：
     *   ```
     *   09:36:40.808  接管写入 user_rotation = 3     ← 本轮才开始 256ms
     *   09:36:41.127  接管写入 user_rotation = 0     ← 300ms 后又被改回来
     *   09:36:41.318  burst 完成 投票[0:15 3:1] → 采纳多数票 0
     *   ```
     *   用户看到的就是「**偶尔先切到相反方向、再跳回来**」—— 也就是报的"切反"。
     *   ⇒ 现在每帧**只认票**；状态机结果降级为收尾兜底。
     */
    private fun applyRotationIfNeeded(
        allowDeciderFallback: Boolean = false,
        /**
         * 绕过"定论来源"那道闸，直接按 [UiState.rotation] 写。
         *
         * ★ 只给 [recentConsensus] 用：它给出的结论**天然不体现在 [voteConfident] 上**
         *   （整轮票被切散时票数就是不够），若还按那道闸判就会被自己的判据挡在门外 ——
         *   算出方向却写不出去，正是"转了 180° 没反应"。
         *   `s.rotation < 0`、写入冷却、`cur == s.rotation` 三道保护**依然生效**。
         */
        force: Boolean = false,
    ) {
        val s = _ui.value
        if (!s.takeoverOn) return
        // ★★★ 历史教训（2026-10-01 加闸 / 10-02 撤闸；2026-10-04 那个功能整体删除）。
        //   ⛔ **别再加这类「诊断页开着就不写方向」的闸。**
        //
        //   当时的动机是「一个仪表不该顺手把屏幕转掉」。真机证伪：打开那一页看读数时，
        //   屏幕被**冻在陈旧值**上 —— 用户报的「屏幕朝左躺」，其实引擎算的一直是 3，
        //   只是被这道闸压着没写，闸一撤 27ms 内就对了。
        //   ⇒ 铁的纪律：**仪表不准改变被测量的东西** —— 任何只读/诊断类功能
        //     都不许插进「写方向」这条链。
        // ★ 前台门停手期间**一律不写方向**（2026-09-28）：前台应用自己管朝向时，
        //   我们这一写要么白写、要么真的把它的屏幕转掉并打断进行中的手势。
        //   门控期间理论上不会有 burst，但这里是**最后一道闸**，必须自己兜住 ——
        //   burst 收尾就发生在进入门控的那一瞬间（见 [enterForegroundGate] 的顺序注释）。
        if (fgGated) return
        // ★★ 「账 vs 事实」的闸（2026-09-29 补，与 [applySemiRotation] 同一处根因）：
        //   `s.takeoverOn` 是引擎自己记的账；`ACCELEROMETER_ROTATION` 才是事实。
        //   账陈旧时（前台门交接的边界 / MIUI / 用户拨了系统自动旋转开关）这里每帧都会走到
        //   `writeUserRotation`，而每一次都被传感器在几毫秒内覆盖 ⇒ 症状是"自适应**完全不跟手**"，
        //   日志上却写着一排"接管写入 user_rotation = N"，看着像在工作。
        //   ⇒ 先按事实补一次接管（幂等，且它自己会再闸前台门与授权）；仍不在手里就**别写**。
        //   ⚠️ 代价是每条调用多一次 Settings 读；这个函数本来就已经在读一次
        //     `readUserRotation()`（见下），量级相同，可接受。
        if (readAutoRotate() != 0) {
            engageTakeover()
            if (readAutoRotate() != 0) return
        }
        if (s.rotation < 0) return
        // ★★ 半自动档**不走这条路**（2026-10-02 补）。**别删。**
        //   半自动的语义是"引擎不自己转，弹按钮等用户拍板"（见 [applySemiRotation]），
        //   唯一该写盘的是那次点击。这个函数是"看到帧就落定"的自适应路径 ——
        //   正常半自动下**一次 burst 都不发**（见 [onSemiTriggered] 上面的分流注释），
        //   所以它本来压根到不了这里，看起来加不加都一样。
        //   ⚠️ 任何**会额外发 burst 的只读功能**都要先想清这一点：没有这道闸，一个
        //   「看一眼角度」的诊断动作就会把半自动悄悄变成自动 —— 看着读数的同时屏幕自己转，
        //   退出时又攒下一次性决定突然落盘。两头的表现都不是用户选的那个档。
        //   ⇒ 只读功能必须是纯仪器：不改被测量的东西。
        if (mode() == RotateMode.SEMI) return
        // ★ 定论来源有两个，但**每帧**只认第一个：
        //   ① 本轮投票已达多数（[voteConfident]）—— 立刻生效，不再等状态机的保持期；
        //   ② 状态机稳定（STABLE）—— 仅在 [allowDeciderFallback]（= burst 收尾）时可用。
        //   这样既保住"票一够就转"的跟手感，又不会拿"上一轮的陈旧状态"去写屏幕。
        val byVote = s.voteConfident
        val byDecider = allowDeciderFallback &&
            s.deciderState == OrientationDecider.State.STABLE.name
        if (!byVote && !byDecider && !force) return
        // ★ 写入冷却：Settings 写进去之后，系统更新 display rotation 有几十~几百 ms 的延迟，
        //   期间每帧回读都还是旧值 ⇒ 实测出现同一个值连写 3 次（日志噪声 + 白白 IPC）。
        if (SystemClock.elapsedRealtime() - lastWriteAt < WRITE_COOLDOWN_MS) return
        // ★★★ 2026-10-03：落盘前过一道**方向一致性闸**（见 [OrientationFusion.gauge]）。
        //   本函数的判据（票/状态机）**全都建立在"人脸测出来的扇区是对的"这个前提上**，
        //   而真机现场证明这个前提会破：手一转，ML Kit 会把整张脸**整体认成另一个象限**
        //   （euler 与 eye 两路同时翻，现有两路交叉验证抓不到），
        //   于是 8 帧票 8:0 一边倒、收尾照写 —— 屏幕上就是**倒竖屏**
        //   （用户原话「连桌面方向都不对」；证据 `_probe/_err180_2026-10-03_0824.log`）。
        //   重力是唯一独立于相机链路的物理量，这里只借它判**一件事**：
        //   **这一轮的目标是不是"朝下"（相对重力差 180°）** —— 是就改写成重力给的那个值。
        //   ⚠️ 差 90°（朝左／朝右）**不拦**：躺着玩时人脸给的横屏值天然就与重力差 90°，
        //      拦掉等于让"躺着玩"不转（第一版犯过，用户纠正）。
        val gated = gravityGate(s.rotation) ?: return
        // ★ 与**盘上的实际值**对比，而不是与"上次写过什么"对比：
        //   这样用户在别处手动改过方向、或系统被第三方改过，也能被纠正回来。
        //
        // ★ `readUserRotation()` 读的与 `s.rotation` 是**同一个空间**的值（本机偏置恒 0，
        //   见 [PanelOrientation] 类注释），直接比即可；[deviceToPanel] 留着只为将来真需要换算时
        //   有一条统一的路（现在是恒等）。
        if (readUserRotation() == deviceToPanel(gated)) return
        writeUserRotation(gated)
    }

    /**
     * ★★★ 2026-10-03 新增：**方向一致性闸** —— 见 [OrientationFusion.gauge] 的完整推导与真机证据。
     *
     * @param target 人脸链路（票/状态机）给出的目标方向
     * @return 允许落盘的方向；只会是 `target` 或「重力给的那个值」
     *
     * ★ 为什么闸放在**写入点**而不是投票点：票/状态机/末段共识/收尾兜底**四条路都汇到这里**，
     *   放在投票点只能拦住其中一条（实测：把票拦掉之后，收尾会退回状态机照样写出错值）。
     *
     * ★ 重力这一路不可用时（手机平放、读数过期、触发层没起来）**一律放行** ——
     *   没有尺子就判不出哪一格是"朝下"，硬拦只会把"没有重力的场景"整个变成不转。
     *
     * ★ 只拦「朝下」（相对重力差 180°），**差 90° 的"朝左／朝右"一律放行** ——
     *   那是躺着手持等合法姿势的正确答案，用户明确定过（见 [OrientationFusion.gauge]）。
     */
    private fun gravityGate(target: Int): Int? {
        val g = gravitySectorNow()
        when (OrientationFusion.gauge(target, g ?: -1)) {
            OrientationFusion.Gauge.OK -> return target
            OrientationFusion.Gauge.UPSIDE_DOWN -> {
                noteGate(target, g ?: -1)
                // 顺手清掉被带偏的热点角：否则下一帧还在同一个错角上，改写成重力后马上又被推回去
                analyzer?.resetHot()
                return g
            }
        }
    }

    /** 闸的日志去重键 —— 同一组 (目标,重力) 只报一次，避免每帧刷屏（`event()` 会推 UI） */
    @Volatile private var gateLogKey = ""

    private fun noteGate(target: Int, gravity: Int) {
        val key = "$target|$gravity"
        if (key == gateLogKey) return
        gateLogKey = key
        event(
            "⚠️ 方向一致性闸：这一轮本要写 ${rotName(target)}（$target），" +
                "但它相对重力（${rotName(gravity)}）整整差了 180°，画面会倒过来 ⇒ 已改写成 ${rotName(gravity)}。" +
                "是相机链路这一轮判错了，不是你在转。",
        )
    }

    /**
     * 写方向 —— 参数是**目标方向**（姿态/投票给的），内部换算成要落盘的数再写。
     *
     * ============================ 换算的四次反复（都记在这里，别再走第五遍）============================
     * | 版本 | 换算 | 结局 |
     * |---|---|---|
     * | v1 | 有，偏置反射自 `Display.DEFAULT_DISPLAY` 的 `installOrientation` | **外屏被减了 2** ⇒ 用户报「每次旋转的方向都完全相反」 |
     * | v2（09-29 上午） | **摘掉**，原值直接写 | 外屏好了，但把"内屏偏 180°"的**假说**留着 ⇒ 下一版照它加偏置 |
     * | v3（09-29 白天） | 有，偏置按形态取（内 2 / 外 0） | 用户持续报「内屏展开翻转 180°」「半自动方向是反的」 |
     * | **v4（09-29 深夜，现在）** | 有，但**偏置恒为 0**（内 0 / 外 0） | 病因是偏置本身不存在，见 [PanelOrientation] 类注释 |
     *
     * ⛔ 前三次的共同错法：拿**某个读数**去推"内屏该不该加 180°"，而真机验证一次要展开一次
     *   手机、还要肉眼判断正不正 —— 于是每轮都以为修好了。**判据在源码里，不在读数里**：
     *   框架 `DisplayRotation.configure()` 按**每块面板自己的宽高**定旋转原点，两块都是
     *   `宽 < 高` ⇒ 语义对称 ⇒ `USER_ROTATION` 在两块屏上同值同义。
     */
    private fun writeUserRotation(rot: Int) {
        val offset = installOffsetNow()
        val panel = deviceToPanel(rot)
        if (!putPanelRotation(panel)) {
            event("⚠️ 写入 user_rotation 失败（目标方向 ${rotName(rot)} → 落盘 $panel）")
            return
        }
        lastAppliedRot = panel
        lastWriteAt = SystemClock.elapsedRealtime()
        event(
            "接管写入 user_rotation = $panel" +
                "（目标方向 ${rotName(rot)}；本屏安装朝向偏移 $offset）"
        )
    }

    /**
     * 只把值写进 `Settings.System.USER_ROTATION`，别的什么都不管。
     *
     * ★ 抽出来是为了让**换屏搬运**（[onScreenFormChanged]）复用这唯一一处写盘动作：
     *   那一条整段都在"面板值"上算（`旧值 + Δ`），**不该再套 [deviceToPanel]**。
     *   ⚠️ 它曾经叫 `putUserRotation`，被两条路径共用，而当时两条路径对"手上这个数属于哪个
     *   空间"的理解不一致 —— 名字里带 `Panel` 就是为了逼调用点回答这个问题。
     *   偏置归零之后两个空间重合了，但这个"必须表态"的作用留着。
     *
     * ★★ 2026-10-03：**它不再动方向槽位了**。2026-10-02 那版是"写全局键时顺手把本屏槽位
     *   也同步成同一个值"，理由是"两份记录分两处写必然漏一边"。那条同步**和用户的
     *   「默认方向」直接冲突** —— 用户刚把槽位定成某个值，一来自动转屏就被改成"当前方向"，
     *   于是又变成"设置了却不生效"（10-03 已被投诉过的那个观感）。
     *   ⇒ 现在的分工：**全局键归模块**（该转就转），**槽位归用户 + 框架**
     *     （用户点「默认方向」借 root 直写；用户手动转屏时框架自己会同步）。
     *   ⛔ 别把同步加回来 —— 那会覆盖用户设定。
     */
    private fun putPanelRotation(panel: Int): Boolean {
        val ok = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, panel)
        }.getOrDefault(false)
        // ★ 失败计数（2026-10-03）：这条路径此前只打一行普通日志，几分钟就被冲掉 ——
        //   而"方向写不进去"是整条控制链上最要命的一种失败，必须能被界面读到。
        if (!ok) EngineErrors.bump(EngineErrors.ROTATION_WRITE)
        return ok
    }

    /**
     * 读**指定屏**的方向槽位（[KEY_USER_ROTATION_PREFIX] + 形态后缀）。
     *
     * ★ 默认读"当前形态"，但**可以显式指定** —— 换屏处理时 `AppPrefs.screenForm`
     *   有可能还没采纳到新形态，那时必须问清楚是问哪块屏的槽位。
     *
     * @return null = 这个 ROM 没有 per-display 槽位（或读失败）。⚠️ 调用方**不得**当成 0：
     *   "没有这个键"与"键值是 0"是两件不同的事 —— ⛔ 别把"读不到"当成 0 去写。
     */
    private fun readFormRotationSlot(form: ScreenForm = AppPrefs.screenForm.value): Int? = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            KEY_USER_ROTATION_PREFIX + form.storageKey,
            Int.MIN_VALUE,
        )
    }.getOrNull()?.takeIf { it != Int.MIN_VALUE }

    // ⚠️ 2026-10-03 删除：原先这里有一个 `alignFormRotationSlot()` —— 2 秒巡检把"本屏槽位"
    //   对齐成"当前方向"（还有它的一对记账字段 `lastAlignedForm` / `lastAlignedRotation`）。
    //   删除原因（与用户「默认方向」冲突）见 [startForegroundWatch] 里那段说明。⛔ 不要恢复。

    /**
     * 读回 `Settings.System.USER_ROTATION` 的**实际现值**（面板空间）。
     *
     * ★ 判"要不要写"用的是它，而不是 `Display.getRotation()` —— 理由见
     *   [applyRotationIfNeeded] 的注释（那是一次**回避未知语义**的选择）。
     *
     * @return null = 读失败（调用方**不得**把它当成 0，"读不到"与"读到了 0"后果完全不同）
     */
    private fun readUserRotation(): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
    }.getOrNull().also {
        // ★ 失败计数（2026-10-03）：读不到就没法判"要不要写"，会让引擎整个停摆 ——
        //   而它此前只是一句 `getOrNull()`，静默到连日志都没有。见 [EngineErrors]。
        if (it == null) EngineErrors.bump(EngineErrors.ROTATION_READ)
    }

    // ============================================================ 接管（方案 b：跟随模式）

    /**
     * 接管 = 关掉系统自动旋转 + 由我们写 user_rotation。
     *
     * ★ 用户拍板的方案 b：不再做成独立手动开关，而是**切到 ADAPTIVE 模式时自动接管**。
     *   理由：模式开关与接管开关语义高度重叠，两个开关会互相打架
     *   （开了自适应但忘了开接管 → 看着像坏了）。
     */
    private fun engageTakeover() {
        // ★★ 短路判据取**事实**，不取自己记的账（2026-09-29 修，用户报「很多软件是能旋转的，
        //   但被标成实测不可旋转」的根因之一）。
        //
        //   `_ui.value.takeoverOn` 是引擎**自己记的账**。账与事实可能脱钩：
        //   前台门交接的边界、MIUI 自己、用户拨了系统的自动旋转开关 —— 任何一种都能把
        //   `ACCELEROMETER_ROTATION` 改回 1，而账还停在 true。
        //   账一旦陈旧，"接管"就成了空操作：写下去的 `user_rotation` 会被传感器在几毫秒内
        //   覆盖 ⇒ 屏幕不动 ⇒ 半自动那条**读回**会把这笔"没动"算到应用头上（见
        //   [scheduleSemiReadback]）。
        //   ⇒ 只有"账说是、且事实也是"才敢短路；否则老老实实再接管一次（幂等）。
        if (_ui.value.takeoverOn && readAutoRotate() == 0) return
        // ★★ 前台门优先：当前前台应用在白名单里 ⇒ **不接管**（2026-09-29 修）。
        //
        //   没有这道闸时会撞上一个很隐蔽的**启动竞态**：启动序列里前台门先跑
        //   （那一刻还没接管，所以它判"无需交还"、fgHandedOff=false），紧接着接管执行，
        //   而它只看 takeoverOn ⇒ 把刚停下来的方向盘又冻回去了。
        //   之后前台没变化 ⇒ 没人再调前台门 ⇒ **一直冻着**，白名单形同虚设。
        //
        //   实测症状：本应用自己进来后 `accelerometer_rotation` 仍是 0 ⇒ 设置页不跟随系统旋转。
        //   （当时本应用自己还是**恒豁免**的，现在它是名单里的普通一行、默认不豁免 ——
        //    但这个竞态的教训与"谁被豁免"无关，那道闸必须留着。）
        //   日志里能直接看到这一对相邻行：00:52:18.212「已停手」/ .213「★ 已接管」。
        if (fgGated) {
            Log.i(TAG, "前台应用在白名单里 → 暂不接管（方向盘留在系统手上）")
            return
        }
        if (!Settings.System.canWrite(context)) {
            _ui.update { it.copy(writeSettingsGranted = false) }
            // ⚠️ 这条文案 2026-10-05 改过。旧文案是「请在页面上点授权」—— 那个页面**不存在**：
            //   本应用的清单刻意**不声明** `WRITE_SETTINGS`（见 `AndroidManifest.xml` 那段说明），
            //   系统的「修改系统设置」列表里因此**找不到 HyperPlus** ⇒ 用户照做只会白跑一趟。
            //   何况 `context` 是 **SystemUI** 的，它自带的 `WRITE_SETTINGS` 是 SYSTEM_FIXED 授予
            //   （实测 `dumpsys package com.android.systemui` ⇒ `granted=true`）⇒ 正常环境
            //   **这一支永不触发**。走到了就是异常，如实说是异常、并把人指向真能看的地方。
            event(
                "⚠️ 引擎进程拿不到 WRITE_SETTINGS —— 这是异常（系统界面本应自带该权限）" +
                    " ⇒ 尚未接管。本应用没有这个权限、也无处可授权；请到「设置 → 权限管理」核对",
            )
            return
        }
        val cur = readAutoRotate()
        // ★★ 记账规则（2026-09-30 第二轮定案，用户报「**不要影响外屏的旋转锁定状态**」）。
        //
        //   判据不是"接管前的值是多少"，而是"**我们到底动没动过它**"：
        //
        //   | 读到 cur | 含义 | 记什么 |
        //   |---|---|---|
        //   | `!= 0` | 用户开着自动旋转，我们**确实把它关成了 0**（欠一笔） | `cur`（=1） |
        //   | `== 0` | 用户**本来就锁着**，我们什么都没改（不欠） | 哨兵 = 不写 |
        //
        //   ⛔ 上一版（记"接管前的原值"，不区分动没动）**不收敛**，这是用户这次报障的根：
        //   ```
        //   ① 某次接管读到 0（那时用户锁着）⇒ target = 0 落盘
        //   ② 合上回外屏：交还写 0                                        ⇒ accel = 0
        //   ③ 用户手动「关闭旋转锁定」                                    ⇒ accel = 1
        //   ④ 展开（这次没重新接管）→ 合上 → 交还写回**陈旧**的 target=0   ← ★ 手动改动被抹掉
        //   ```
        //   只要有一次落到 0，之后就永远是"读到 0 → 存 0 → 还 0"，用户的 1 再也回不来 ——
        //   正是用户说的「哪怕我手动关闭，展开再合上之后依然会自动打开」。
        //   ★ 真机旁证（09-30 23:0x）：HyperOS 自己的每屏记忆槽 `mUserRotationModeOuter`
        //     是 `USER_ROTATION_FREE`（**用户要的是自动旋转开**），而实际 accel = 0
        //     ⇒ 连框架都记着用户的选择，只有我们在往回写。
        //
        //   ⚠️ `cur` 是**这一刻**读的，若此刻 accel 已被上一次异常退出留成 0（孤儿接管），
        //     读到的 0 是**我们自己的残留**、不是用户的选择 —— 那一路由
        //     [recoverOrphanTakeover] 负责（它启动时会把 0 还原并清标志，先于本函数）。
        //     ⚠️ 而它同时也是"这次读到 0 就记哨兵"的**风险点**：万一残留没被清掉，
        //       我们会把"欠的 1"记成"不欠"。⇒ 那道恢复逻辑**必须先于接管跑**，
        //       现在 `start()` 里的顺序就是如此（`recoverOrphanTakeover()` 在首次
        //       `engageTakeover()` 之前）。
        AppPrefs.setRestoreTarget(
            if (cur == 0) AppPrefs.AUTO_ROTATE_UNTOUCHED else cur,
        )
        runCatching {
            Settings.System.putInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
            )
        }
        AppPrefs.setTakeoverActive(true)
        _ui.update { it.copy(takeoverOn = true, writeSettingsGranted = true) }
        // ⚠️ 别把这句话写成"关闭系统自动旋转" —— `cur == 0` 时我们**什么都没改**
        //   （用户本来就锁着，"接管"只是把账记上）。措辞必须能覆盖这两种情况，
        //   否则日志会让人以为我们动过 accel（排查时会被带偏）。
        val touched = if (cur == 0) "本来就锁着，未改动" else "本次改为锁定"
        event("★ 已接管旋转控制（系统自动旋转：$touched；交还时按原样恢复）")
    }

    private fun releaseTakeover(restoreSystem: Boolean = true, quiet: Boolean = false) {
        if (!_ui.value.takeoverOn && !AppPrefs.isTakeoverActive()) return
        if (restoreSystem) {
            val target = AppPrefs.restoreTarget()
            val cur = readAutoRotate()
            // ★★ 三道闸，按顺序问（2026-09-30 第二轮定案，用户原话「**不要影响外屏的旋转锁定
            //   状态**……展开前外屏如果打开旋转锁定、展开再合上之后依然保持打开状态；
            //   如果原本是关闭、之后也保持关闭」）。完整推导见 [AppPrefs.AUTO_ROTATE_UNTOUCHED]。
            //
            //   ① `target == -1`（哨兵）⇒ **我们从来没改过它** ⇒ 现在也没资格写。
            //      ⛔ 这就是上一版不收敛的那条路：用户本来就锁着（0），我们"接管"时其实
            //        什么都没做，却在交还时写回 0 —— 看着无害，实际上把用户后来手动打开的
            //        自动旋转（1）又按回了 0，而且此后永远为 0。
            //   ② `cur != 0` ⇒ **用户自己动过**（在内屏期间把自动旋转打开了）⇒ 尊重，不碰。
            //      ⛔ 少了这道闸，「原值往返」仍会覆盖用户的新选择。
            //   ③ 以上都不成立（`target >= 0` 且 `cur == 0`）⇒ 确实还是"我们关的那个状态"
            //      ⇒ 才按原样还原。
            val skipReason = when {
                target == AppPrefs.AUTO_ROTATE_UNTOUCHED ->
                    "这项设置本来就是我们没动过的状态，保持原样"
                cur != 0 ->
                    "你自己改过它（当前是开启），按你的设置保持"
                else -> null
            }
            if (skipReason == null) {
                runCatching {
                    Settings.System.putInt(
                        context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target,
                    )
                }
                // ⚠️ 别写成"= 开启" —— 目标值可能是 0（用户原本就锁着）。
                if (!quiet) {
                    event(
                        "已交还系统自动旋转（恢复为接管前的样子：" +
                            if (target == 0) "锁定" else "自动旋转开启" + "）",
                    )
                }
            } else if (!quiet) {
                event("已交还系统自动旋转（未改动：$skipReason）")
            }
        }
        AppPrefs.setTakeoverActive(false)
        lastAppliedRot = -1
        _ui.update { it.copy(takeoverOn = false) }
    }

    /**
     * ★ 孤儿接管恢复：进程被强杀时 `takeoverOn` 的内存态没了，
     *   但 `accelerometer_rotation` 已经被置 0 —— 若不还原，系统的自动旋转
     *   会**永久失效**（实测踩过：设置停在 0，手机再也转不动）。
     */
    private fun recoverOrphanTakeover() {
        if (!AppPrefs.isTakeoverActive()) return
        val target = AppPrefs.restoreTarget()
        val cur = readAutoRotate()
        // ★★ 判据是 `cur == 0`，即"**确实还是我们留下的那个状态**"（2026-09-30 修）。
        //
        //   旧版写的是 `cur != target`，其理由（"目标值恒为 1，所以只有还是 0 才需要动"）
        //   **依赖"目标恒为 1"这个前提** —— 那条前提已废（见 [AppPrefs.AUTO_ROTATE_UNTOUCHED]）
        //   ⇒ 旧推理失效。
        //   ⚠️ 用 `cur != target` 必踩的反例：接管前用户锁着（target=0）→ 进程被杀留下 0 →
        //     用户手动把自动旋转打开（cur=1）→ 启动时 `1 != 0` 成立 ⇒ **把用户刚做的选择改回锁定**。
        //   `cur == 0` 没有这个问题：只有"还是 0"才动，而 0 只可能来自我们自己。
        //
        // ★★ 第二道闸 `target != 哨兵`（2026-09-30 第二轮）：哨兵意味着"我们**从没改过**
        //   accel"（接管时它本来就是 0）⇒ 那这个 0 是**用户自己锁的**，不还。
        //   ⛔ 少了这道闸就是上面那条不收敛的路：把用户的 0 当成"我们的残留"再写一次。
        val weOwe = target != AppPrefs.AUTO_ROTATE_UNTOUCHED
        if (cur == 0 && weOwe) {
            runCatching {
                Settings.System.putInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target,
                )
            }
            Recovery.fixed = true
            event(
                "⚠️ 上次没有正常退出，系统自动旋转被留在锁定状态 → 已恢复为" +
                    (if (target == 0) "锁定" else "自动旋转开启"),
            )
        }
        AppPrefs.setTakeoverActive(false)
    }

    /** 启动时是否做过孤儿接管修复（供 UI 提示一次） */
    object Recovery {
        @Volatile var fixed = false
    }

    private fun readAutoRotate(): Int = runCatching {
        Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1,
        )
    }.getOrDefault(1)

    // ============================================================ 方向标定

    /**
     * 标定第 1 步：把手机**竖屏正对自己**、头摆正，点一下按钮。
     * 引擎会主动采一次 burst，取样本中位数作为「人脸正立」的基准。
     */
    fun applyCalibrationBaseline(rollMean: Float) {
        calibBaselineRoll = rollMean
        decider.calibrateBaseline(rollMean)
        AppPrefs.persistCalibration(decider.sign, decider.offsetDeg)
        _ui.update {
            it.copy(
                calibBaselineRoll = rollMean,
                calibSign = decider.sign,
                calibOffsetDeg = decider.offsetDeg,
                calibrated = true,
            )
        }
        event("标定①：竖屏基准 roll=${fmt1(rollMean)}°，offset=${fmt1(decider.offsetDeg)}°")
    }

    /**
     * 标定第 2 步：把手机**逆时针转 90°**（顶部朝左）、头保持正立，点一下按钮。
     * 依据两点差分自动定 sign，并重算 offset。
     */
    fun applyCalibrationAxis(rollAxis: Float): Boolean {
        if (calibBaselineRoll.isNaN()) {
            event("⚠️ 标定②：还没有第 1 步的竖屏基准")
            return false
        }
        val ok = decider.calibrateAxis(calibBaselineRoll, rollAxis)
        if (ok) {
            AppPrefs.persistCalibration(decider.sign, decider.offsetDeg)
            _ui.update {
                it.copy(
                    calibSign = decider.sign,
                    calibOffsetDeg = decider.offsetDeg,
                    calibrated = true,
                )
            }
            event(
                "标定②：横屏基准 roll=${fmt1(rollAxis)}° → sign=${decider.sign}, " +
                    "offset=${fmt1(decider.offsetDeg)}°",
            )
        } else {
            event("⚠️ 标定②：两点角度差不在 30°~150°，无效（请确认已把手机转了 90°）")
        }
        return ok
    }

    fun resetCalibration() {
        calibBaselineRoll = Float.NaN
        decider.sign = 1
        decider.offsetDeg = 0f
        decider.reset()
        // ★ 引擎侧的清空要写进 Settings（标定归引擎管，见 AppPrefs.persistCalibration 的注释）。
        //   界面上的「清除校准」走的是另一条路：App 只发一个请求，由引擎执行到这里。
        AppPrefs.clearCalibrationInStore()
        lastAppliedRot = -1
        _ui.update {
            it.copy(
                calibBaselineRoll = Float.NaN,
                calibSign = 1,
                calibOffsetDeg = 0f,
                calibrated = false,
            )
        }
        event("标定已重置（sign=+1, offset=0°）")
    }

    /**
     * 主动采一次样本并回调中位数 —— 标定两步共用。
     *
     * ★ 为什么不能直接读 HUD 上的即时值：burst 模式下两次触发之间相机是关的，
     *   用户点按钮的瞬间**大概率没有帧**，读到的是上一次的陈旧值。
     *   所以这里主动拉一次 burst，采满 [CALIBRATION_MS] 再取中位数。
     */
    fun captureCalibrationSample(onDone: (Float?) -> Unit) {
        scope.launch {
            synchronized(recentRolls) { recentRolls.clear() }
            _ui.update { it.copy(calibrating = true) }
            // ★ 以前这里会**偷偷把模式改成 ADAPTIVE**，已移除（实测副作用）：托管模式下
            //   App 的开关读的是自己那份配置，宿主单方面改镜像会让两边不一致 ——
            //   用户的体感就是"开关关不掉 / 打不开"。标定本身并不需要接管
            //   （它只采样算 sign/offset），启用与否交给开关，这里只留一句解释。
            if (!mode().usesCamera) {
                event("提示：当前不是「自适应旋转」，标定结果会先存下来，切到自适应后立刻生效")
            }

            // ★ 采样窗口 1200 → 2600ms，且**循环补采**：
            //   单个 burst ≈1s，1200ms 只够跑一次；而一次采集很可能空手而归
            //   （相机刚被释放、被别人占用、或人脸刚好没进画面）。
            //   多轮补采直到凑够 MIN_CALIB_SAMPLES 个有效样本或超时，
            //   失败时在事件里写清"采了几轮 / 拿到几个样本"，避免只给一句含糊的失败提示。
            var rounds = 0
            val deadline = SystemClock.elapsedRealtime() + CALIBRATION_MS
            var n = calibSampleCount()
            while (n < MIN_CALIB_SAMPLES && SystemClock.elapsedRealtime() < deadline) {
                if (!bursting) {
                    startBurst()
                    rounds++
                }
                delay(350)
                n = calibSampleCount()
            }

            val v = synchronized(recentRolls) {
                val arr = recentRolls.map { it.second }.sorted()
                if (arr.isEmpty()) null else arr[arr.size / 2]
            }
            _ui.update { it.copy(calibrating = false) }
            if (v == null) {
                event("⚠️ 标定采样失败：$rounds 轮采集、$n 个有效人脸样本（相机被占 / 没对准脸 / 光线太暗）")
            } else {
                event("标定采样完成：$n 个有效样本，中位数 ${fmt1(v)}°")
            }
            withContext(Dispatchers.Main) { onDone(v) }
        }
    }

    /** 当前缓冲里的有效人脸样本数（标定用） */
    private fun calibSampleCount(): Int = synchronized(recentRolls) { recentRolls.size }

    // ============================================================ 相机初始化

    private fun ensureProvider() {
        // ★ 必须先显式配置 CameraX（宿主进程里清单发现这条路不成立）—— 见 [CameraXBootstrap]
        event("CameraX 引导：${CameraXBootstrap.ensureExplicitConfig()}")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    event("⚠️ 找不到前摄")
                }
                ensureAnalyzer()
                event("相机服务就绪（尚未开流，等触发）sensorOrientation=$sensorOrientation")
            }.onFailure {
                event("⚠️ 相机服务初始化失败：${it.message}")
            }
        }, executor)
    }

    /** 幂等：分析器只创建一次，避免权限重试时泄漏旧实例 */
    private fun ensureAnalyzer() {
        if (analyzer == null) {
            analyzer = FaceAnalyzer(sensorOrientation) { frame -> onFrame(frame) }
        }
    }

    /** 帧回调（分析线程） */
    private fun onFrame(frame: FaceFrame) {
        // ★★★ 2026-10-03：**只在采样轮内计数**（原为无条件递增）。
        //   相机保温期（见 [CAMERA_KEEP_WARM_MS]）帧仍在来，但那时**没有新触发** ——
        //   计数继续涨会让下面 [VOTE_WARMUP_FRAMES] 那道闸形同虚设（恒为真），
        //   于是零散帧继续往**同一个票池**里投票。
        //   实测（2026-10-03 微信/小红书对照）：两次 `burst 完成` 之间出现了
        //   **5 次落盘、0 轮采样**，屏幕在同一姿态下 0↔2 来回横跳。
        if (bursting) burstFrameCount++
        // ★ 记下"帧还在来"这个事实 —— 这是检测"会话已被系统踢掉"的唯一可靠依据（见 [lastFrameAtMs]）
        lastFrameAtMs = SystemClock.elapsedRealtime()

        val roll = pickRoll(frame)
        // ★★★ R1（2026-10-05）：本轮"见到过可用人脸"的唯一标记。
        //   ⚠️ 判据**复用上面这个 `roll`**（`pickRoll` 的答案），不另立标准 ——
        //     否则会出现"投票认为有脸、R1 认为没脸"这种两套口径打架的局面。
        //   ⚠️ 带 `bursting` 守卫，理由与上面 [burstFrameCount] 那条逐字相同：
        //     相机保温期仍有帧在来，那不算"这一轮的观测"。
        if (bursting && roll != null) burstHadUsableFace = true
        val r = decider.update(roll, frame.atMs, frame.faceCount)

        // ★ 每轮多帧投票：这一帧投给「它自己看到的那个方向」。
        //   用瞬时扇区（绝对量化、不带状态机）而不是 r.rotation —— 见 [votes] 的注释。
        //
        // ★ 方向 = 人脸单源的**绝对扇区**（`u = θrel / 90`）。
        //   ⚠️ 重力**不参与**这里的运算：`u = 重力 + 人脸偏差` 会把设备自身的旋转算两遍
        //   （反例：手机逆时针 90°、头正立 ⇒ 重力 1 + 偏差 1 = 2，而正确答案是 1）。
        //   重力的职责在 [noteSignEvidence]：当尺子，校验符号位。
        val faceSector = if (roll != null && roll.isFinite() && r.smoothedRoll.isFinite()) {
            decider.sectorOf(decider.angleOf(r.smoothedRoll))
        } else {
            -1
        }

        // ⚠️ 头 [VOTE_WARMUP_FRAMES] 帧**不投票**：这一轮是"设备动了"触发的，相机开流要
        //    ~220ms，所以开头几帧拍到的常常是**还没转完的旧姿势**。真机证据（2026-09-25）：
        //   某轮票面 `0:4 1:9 3:3`，而**上一轮刚把方向写成 0** —— 那 4 张 "0" 票就是转动前
        //   的残留。它们把多数往旧方向拉，是"有概率切反"的一个来源。
        // ★★★ 2026-10-03：**票只在采样轮内投**（原缺 `bursting` 守卫）。
        //   票池是只增不减的累加器，`reset()` 只在 [startBurst] 里调用 ⇒
        //   轮外再投一票，等于把上一轮的历史票带进下一轮 —— 与 [startBurst] 里
        //   「投票是**每轮独立**的」那句注释**正好相反**。后果见 [onFrame] 开头。
        if (bursting && faceSector in 0..3 && burstFrameCount > VOTE_WARMUP_FRAMES) {
            tallyVote(faceSector)
        }

        // ★ 2026-10-03：这里曾挂过一段**逐帧**诊断探针（`帧#… 原始倾角=… 面积=…` 裸 `Log.i`），
        //   用于查「小红书里转向总出错」。结论已经拿到（见 `docs/重力一致性闸_方向判错_2026-10-03.md`）：
        //   **`apparentTiltDeg` 恒在 ±8° 内 ⇒ `ExtraPicker.record()` 的 `|tilt| ≤ 45°` 恒真
        //   ⇒ 永远在"第一个试到的角度"上停下 ⇒ `extra` 就等于热点角 `bestExtra`。**
        //   也就是说这条链路上**方向几乎完全由热点角决定**，而热点角会在轮间被带偏。
        //   ⇒ 探针已按"定案即删"的约定移除（`FaceFrame.ageMs` 字段同步删）。
        //   ⚠️ 以后若还要逐帧取证，先看 `event()` 推的 `方向一致性闸` 日志 —— 它已经在报这件事了。

        // 标定样本缓冲（只留最近 CALIBRATION_MS + 余量）
        if (roll != null && roll.isFinite()) {
            synchronized(recentRolls) {
                recentRolls.addLast(frame.atMs to roll)
                val cut = frame.atMs - (CALIBRATION_MS + 500)
                while (recentRolls.isNotEmpty() && recentRolls.first().first < cut) {
                    recentRolls.removeFirst()
                }
            }
        }

        if (recorder.isRecording) recorder.append(csvRow(frame, r))

        val newRotSwitch = r.rotation != lastRot && lastRot != -1 && r.rotation != -1
        if (r.rotation != lastRot) {
            lastRot = r.rotation
        }

        _ui.update { s ->
            s.copy(
                eulerZ = frame.eulerZ,
                eyeRoll = frame.eyeRoll,
                roll = r.smoothedRoll,
                // ★ 方向取「本轮多数票」优先（票够了就用票），票不够才用状态机的单帧结果。
                //   这就是"按命中最多的方向旋转"的落点。
                rotation = if (voteConfident) voteWinner else r.rotation,
                deciderState = r.state.name,
                displayRotation = displayRotation(),
                panelOffset = PanelOrientation.installOffset(context),
                switchCount = s.switchCount + if (newRotSwitch) 1 else 0,
                totalFrames = analyzer?.totalFrames ?: s.totalFrames,
                facesFound = analyzer?.facesFound ?: s.facesFound,
                voteCounts = voteText(),
                voteValid = voteValid,
                voteWinner = voteWinner,
                voteRatio = voteRatio(),
                voteConfident = voteConfident,
                // 第一帧到达 = open 真正完成
                lastOpenMs = if (burstFrameCount == 1 && burstBindRequestedAt > 0) {
                    SystemClock.elapsedRealtime() - burstBindRequestedAt
                } else s.lastOpenMs,
            )
        }

        // ★ **轮内**每帧都尝试落定，而不是只在 burst 结束时判一次 —— 实测踩坑：
        //   「时间保持」commit 的那一帧 state 确实是 STABLE，但用户持续转动时
        //   下一帧立刻进入新的 CANDIDATE ⇒ 只在 burst 末尾判定会**恰好错过所有
        //   commit 时刻**（实测：rotation 切换 10 次，user_rotation 只被写 1 次）。
        // ⚠️ 2026-10-03 加了 `bursting` 守卫：上面那条里的「每帧」**只在一轮之内**成立。
        //   轮外（相机保温期）没有新触发、就没有新证据 —— 那时还能落盘，只可能是因为
        //   票池被轮外帧养大后 winner 翻转，而那**正是"转来转去"本身**。
        //   收尾那次落盘走 [finishBurst] 里的显式调用（带 `force = byRecent`），不受此影响。
        if (bursting) applyRotationIfNeeded()

        if (bursting && burstFrameCount >= BURST_FRAMES) {
            finishBurst()
        }
    }

    /**
     * CSV 列顺序（必须与 [CsvRecorder.HEADER] 逐列一致，也与 2026-09-25 那批
     * 历史实测数据保持一致，方便直接复用分析脚本）：
     * at_ms, euler_z, eye_roll, face_count, face_area, detect_ms,
     * rot_deg, sys_rot, extra_rot, tried, interval_ms,
     * smoothed, norm, decided, state, deviation, low_conf, err, raw_tilt
     */
    private fun csvRow(f: FaceFrame, r: OrientationDecider.Result) = String.format(
        java.util.Locale.US,
        "%d,%s,%s,%d,%.4f,%d,%d,%d,%d,%d,%d,%s,%s,%d,%s,%s,%d,%s,%s",
        f.atMs, fmt(f.eulerZ), fmt(f.eyeRoll), f.faceCount, f.faceArea, f.detectMs,
        f.rotationDegrees, _ui.value.displayRotation, f.extraRotation, f.triedCount, f.intervalMs,
        fmt(r.smoothedRoll), fmt(r.normalized), r.rotation, r.state.name, fmt(r.deviation),
        if (f.lowConf) 1 else 0, f.err ?: "", fmt(f.apparentTiltDeg),
    )

    private fun fmt(v: Float) = if (v.isFinite()) String.format(java.util.Locale.US, "%.1f", v) else ""

    private fun fmt1(v: Float) = if (v.isFinite()) String.format(java.util.Locale.US, "%.1f", v) else "—"

    // ============================================================ 环境读取

    /** 前摄传感器朝向：**硬件常量**，与屏幕当前转成什么样无关 —— 作为 roll 的固定基准 */
    private fun readFrontSensorOrientation(): Int {
        val fallback = 270
        return runCatching {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching fallback
            val id = manager.cameraIdList.firstOrNull { cid ->
                manager.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return@runCatching fallback
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: fallback
        }.getOrDefault(fallback)
    }

    /**
     * **当前这块屏**（[AppPrefs.screenForm] 指的那一块）的显示旋转 —— 面板空间的**原值**。
     *
     * ★★ 2026-09-29 第二次修正：**不再依赖 `Display.DEFAULT_DISPLAY`**。
     *
     *   为什么不能依赖 —— **本机"display 0 是哪块屏"有两条互相矛盾的记录**：
     *     · 我展开态多次抓的 `dumpsys window displays`：`Display#0` = 1672×2364 = 内屏；
     *     · 但**用户明确说过**「display#0 永远绑在当前使用的屏幕上」，而且更早期一次
     *       **折叠态**实测记的是「折叠态 display 0 = 外屏 1168×1712」。
     *   ⚠️ 我上一版据前者断言"恒为内屏"——那是拿**展开态**的观测去外推**折叠态**，
     *     属于过度推断（本轮已按用户的纠正改回诚实表述，详见 [ActiveDisplay] 的类注释）。
     *
     *   ⇒ 而这一段（[lastStablePanelRotation] 的采样、悬浮按钮角位）**主要在折叠态跑** ——
     *     赌不起。所以改成**按尺寸认屏**：无论 id 怎么分配都能挑对。
     *
     * ★ 判据 = **屏幕尺寸**，与 [ScreenForm] 同一套口径（最小边，旋转不变量）：
     *   内屏 1672×2364 ⇒ 608dp、外屏 1168×1712 ⇒ 425dp，阈值 512dp。
     *
     * ⚠️ 认不出那块屏时**先退 `USER_ROTATION`**（全局唯一一份、天然跟着活动屏走），
     *   最后才退 `DEFAULT_DISPLAY`。多这两层是为了"宁可降级，也别静默读错屏"。
     */
    private fun displayRotation(): Int {
        ActiveDisplay.rotationOf(context, AppPrefs.screenForm.value)?.let { return it }
        readUserRotation()?.let { return it }
        return runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: run {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.rotation
                }
        }.getOrDefault(0)
    }

    // ============================================================ ★★ 方向所在的坐标系（2026-09-29 定案）

    /**
     * ### ★★ 2026-09-29 深夜定案：**只有一套坐标系**
     *
     * 曾经这里写着"本引擎里同时存在两个坐标系（面板空间 / 设备空间），差一个安装朝向 `I`"，
     * 并给内屏加了 2 的偏置。**那整套前提是错的**，成因见 [PanelOrientation] 的类注释
     * （把 `dumpsys display` 的 `installOrientation` 误当成了 `USER_ROTATION` 的原点；
     * 而框架的 `DisplayRotation` 全文不读那个字段，两块屏的旋转语义本来就对称）。
     *
     * ⇒ 现在：`Settings.System.USER_ROTATION` / `Display.getRotation()` / 我们传感器算出的目标
     *   **是同一个数**。下面的 `panelToDevice` / `deviceToPanel` 是**恒等换算**，留着只为把
     *   "要不要换算"收在一处（万一将来某台机器真需要）。
     *
     * ⛔ 别再给任何一块屏加静态偏置。补偏置的后果是**屏幕转到相反方向（180°）**，
     *   而且它**不报错、不崩溃** —— 本轮"内屏半自动方向反了"就是这么来的。
     *
     * ### 这一轮真正修掉的是"谁来定换屏后的方向"
     *
     * 用户的原话（2026-09-28）：「不能让系统自己算一次方向 …… **应该根据外屏当前状态来定
     * 内屏状态**」。所以换屏时我们**照搬**旧屏的 `USER_ROTATION`（见 [onScreenFormChanged]），
     * 而不是让框架按重力重算 —— 后者在"躺着看手机"时会差 90°。
     * 而 09-28 那个「展开内屏默认倒置 180°」的真凶是 **HyperOS 的每屏方向记忆**：
     * 展开那一瞬它把**上一次**的方向灌回来（用户的旁证：「手动关闭旋转锁定之后展开方向就正常了」）。
     * ⇒ 对策是"照搬 + 落定后看护一段"（[handoffVerifyJob]），不是静态偏置。
     *
     * ### 纪律（改动前先读）
     *   - 凡是**跟传感器/姿态/投票结果比**的，用 [currentDeviceRotation]（现在是同一个数，但
     *     表意清楚："我要的是屏幕当前展示的方向"）；
     *   - 凡是**写盘**的，交给 [writeUserRotation]；
     *   - **换屏搬运**整段在"面板值"上算（`旧值 + Δ`），直接走 [putPanelRotation]。
     *   - ⛔ 偏置**别再从任何 `dumpsys` 字段去推**。这一层算错，真机验证一次要展开一次手机、
     *     还要肉眼判断正不正，代价极高（已经为此绕了三轮）。
     */

    /** 当前这块屏的安装朝向偏移 —— 本机**恒为 0**，见 [PanelOrientation] */
    private fun installOffsetNow(): Int = PanelOrientation.offsetFor(AppPrefs.screenForm.value)

    /** 面板空间 → 设备空间。给"读回来的值"用（本机恒等） */
    private fun panelToDevice(panel: Int): Int = PanelOrientation.toDevice(panel, installOffsetNow())

    /** 设备空间 → 面板空间。给"要写下去的值"用（本机恒等） */
    private fun deviceToPanel(device: Int): Int = PanelOrientation.toPanel(device, installOffsetNow())

    /**
     * **当前屏幕展示的方向，换算到设备空间** —— 一切"该不该转"的比较都用它。
     *
     * ★ 面板空间的原值（[displayRotation]）只留给"诊断展示"和"换屏搬运"用。
     */
    private fun currentDeviceRotation(): Int = panelToDevice(displayRotation())

    // ============================================================ 杂项

    private fun event(msg: String) {
        Log.i(TAG, msg)
        _ui.update { s ->
            s.copy(events = (listOf(ts() + " " + msg) + s.events).take(16))
        }
    }

    private fun ts(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    /** 相机权限刚被授予 —— 之前初始化可能因缺权限失败，这里重试一次 */
    fun onCameraPermissionGranted() {
        if (cameraProvider == null) ensureProvider() else ensureAnalyzer()
        event("相机权限已授予")
    }

    // ============================================================ 内外屏形态（模式解耦的引擎半边）

    /** 是否已注册配置监听。`start()` 可能被反复调用，重复注册会收到 N 份回调 */
    @Volatile
    private var formWatchInstalled = false

    /** 「等折叠动画落定再量一次」的那次任务 —— 快速反复折叠时只留最后一次 */
    private var formSettleJob: Job? = null

    /**
     * **换屏之前**，旧屏最后那个面板值（`USER_ROTATION` 的原值）。
     *
     * ============================ 它是干什么的（2026-09-29 补）============================
     * 给换屏搬运当**锚点**用。为什么不能到搬运那一刻现读 `USER_ROTATION`：
     * 外屏那一档方向盘是**交还系统**的（`accelerometer_rotation = 1`），展开的那一瞬间框架
     * 自己也会按新屏写一次 —— 谁先写是不确定的。抢在我们前面写 ⇒ 现读到的是"新屏的面板值"，
     * 再加一次 `delta` 就多转了一段。**这正是用户报的"有概率翻转 180°"**。
     *
     * ⚠️ 值域外的哨兵（**−1**，与 [lastAppliedRot] 同款）表示"还没采到"，搬运时会退回现读。
     *
     * ============================ ★★ 采样的两个时机（2026-09-29 二次修正，别再只留一个）============================
     * 它必须在**两处**被写，缺一处就会出现"锚点是另一块屏的值"：
     *
     *   ① **形态没变**时的常规采样（[watchScreenForm]）；
     *   ② ★ **形态刚变那一刻**，用**旧形态**认屏补采一次 —— 这是本轮真正的修复点。
     *
     * 为什么必须有 ②：巡检周期是 [FOREGROUND_POLL_MS]（**2 秒**），而用户
     * "折叠一下、马上再展开"时**折叠态停留经常不到 2 秒** ⇒ 那段时间**一次都没采过**，
     * 于是这个字段还停在**上一块屏（内屏）**的值上，却被搬运当成"旧屏（外屏）的锚点"
     * ⇒ `want = 错锚点 + Δ`，整体偏 `(内屏值 − 外屏值) mod 4`，就是 ±90° 或 180°。
     *
     * ★ 这一条**同时解释了三件事**，所以可以确认就是它：
     *   · 偏差为什么是 90°/180° 这种整角 —— 两块屏的面板值之差必是整数格；
     *   · 为什么"**有概率**" —— 取决于折叠停留有没有跨过 2 秒那个采样点；
     *   · 为什么锚点值**总是像另一块屏的** —— 因为它字面上就是另一块屏的值。
     * ⚠️ 注意这个结论**不依赖**"`display 0` 到底绑哪块屏"那个争议问题（见 [ActiveDisplay]）
     *   —— 就算 `displayRotation()` 读的屏完全正确，只要**没人去读**，锚点照样是陈旧的。
     *
     * 另外配套加了 [lastStablePanelRotForm]：搬运时会校验"这个锚点是不是真来自旧屏"，
     * 不是就退回现读 —— 让"用错锚点"最多降级成"不搬"，不再静默搬错。
     */
    @Volatile
    private var lastStablePanelRotation: Int = -1

    /**
     * [lastStablePanelRotation] **是哪块屏**上的读数。
     *
     * ★ 存在的意义（2026-09-29）：锚点的取值时机一旦漏掉，它就会变成"另一块屏的值"，
     *   而这种错**从数值上看不出来**（都是一个 0..3 的合法值）—— 于是搬运照做、日志照写
     *   "搬运成功"，只有用户看得出方向不对。
     *   带上这块屏的标签之后，搬运就能自检：**`form.other` 才算旧屏**，其余一律退回现读。
     *
     * ⚠️ null = 还没采过（进程刚起来就赶上换屏），与 [lastStablePanelRotation] == −1 同义。
     */
    @Volatile
    private var lastStablePanelRotForm: ScreenForm? = null

    /**
     * 形态（内屏 / 外屏）变化的监听体。
     *
     * ★ 为什么走 `ComponentCallbacks.onConfigurationChanged`，而不是去查物理折叠状态：
     *   折叠 / 展开**必定改变屏幕尺寸**（本机 1672×2364 ↔ 1168×1712），
     *   而尺寸变化必定发一次配置变更 —— 这是**任何机型都成立**的事件源。
     *   反过来 `DeviceStateManager.getCurrentState()` 在部分机型上返回恒定值，
     *   拿它当唯一来源会静默失灵（判据取法的完整理由见 `ScreenForm` 的类注释）。
     *
     * ⚠️ 引擎是常驻的（住在 SystemUI 里），这个回调**不能**在 `stop()` 里注销 ——
     *   引擎停了但进程还在时，用户折一下手机，下次 `start()` 就应该按新形态起。
     *   注销掉反而会漏掉那一次。用 [formWatchInstalled] 保证只注册一次，不重复。
     */
    private val formCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            syncForm()
            // ★ 300ms 后再确认一遍：折叠屏的展开/收拢是**带过渡动画**的，
            //   回调触发的那一刻尺寸可能还在中间态（既不是内屏也不是外屏的值），
            //   只量一次有可能把档位判到错误的那块屏上。
            //   与悬浮按钮重定位用的是同一个理由（`RotateHintOverlay.RELAYOUT_SETTLE_MS`）。
            formSettleJob?.cancel()
            formSettleJob = scope.launch {
                delay(FORM_SETTLE_MS)
                syncForm()
            }
        }

        /**
         * `ComponentCallbacks` 的必实现项；本引擎的内存策略与它无关（相机/分析器自己有回收）。
         * ⚠️ `@Suppress` 的理由：平台在 API 34 之后把 `onLowMemory` 标为废弃（想让人用
         *   `onTrimMemory`），但它仍是这个接口的**必实现成员** —— 不加抑制就会在每次
         *   构建时留一条"覆写了废弃成员"的告警，把真正的告警淹掉。
         */
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    private fun installFormWatch() {
        // ★ 换屏（折起来 / 展开）时要**重新定一次方向** —— 两块屏的安装朝向差 180°，
        //   冻结着的 user_rotation 对新的那块屏就是个错值（用户报的"展开内屏倒置 180°"）。
        //   钩子挂在 AppPrefs 里（换屏的判定只有那一处），见 [onScreenFormChanged]。
        //   ⚠️ **必须在下面的 syncForm() 之前挂**：syncForm 可能真的改变了形态并触发它。
        AppPrefs.setScreenFormListener { form -> onScreenFormChanged(form) }

        // ★ 形态重测**每次 start 都要做**（2026-09-29 从下面的守卫后面挪出来）。
        //   原因：`formWatchInstalled` 的语义是"回调注册过了"，与"我知道自己在哪块屏"
        //   是**两件事**。留在守卫后面的话，引擎 stop → start 时这一步就全靠
        //   `AppPrefs.init` 的早退分支兜着 —— 而本次「内屏桌面不能转」的事故
        //   恰恰是**单一路径失效**造成的（引擎只在启动那一刻量过一次形态），
        //   不该再给自己留一处同样形状的隐患。幂等，代价只有一次尺寸读取。
        syncForm()

        if (formWatchInstalled) return
        formWatchInstalled = true
        runCatching { context.registerComponentCallbacks(formCallback) }
            .onFailure { Log.w(TAG, "注册配置监听失败 —— 折叠后可能仍按上一块屏的模式转", it) }
    }

    /**
     * 换屏了 —— 把**上一块屏此刻的方向原样搬过来**，并保持接管（**不交还系统**）。
     *
     * ============================ 为什么是"照搬"而不是"让系统算一次" ============================
     * ⚠️ **这个函数改过一次，别再改回去。** 两版的做法与结局：
     *
     * | 版本 | 做法 | 结局 |
     * |---|---|---|
     * | 第一版 | 交还 `accelerometer_rotation`，等框架按新屏写一次 `user_rotation`，再接管 | 被用户否决 |
     * | **本版** | 全程冻结，只把方向从旧屏坐标系搬到新屏坐标系 | 采用 |
     *
     * 用户 2026-09-28 的原话（**本函数唯一的行为依据**）：
     * > 「不能让系统自己算一次方向，如果我躺着的时候打开内屏，内屏就会自动切到竖屏状态，
     * >   但外屏的竖屏状态，打开之后是内屏的横屏状态，**应该根据外屏当前状态来定内屏状态**」
     *
     * 第一版错在**输入源**：交还之后框架手上只有重力，它按"设备自然方向"去算；而用户当时
     * 可能正躺着把手机举在脸前 —— 姿态与坐/站着一样，框架给的顶边和用户想要的差 90°。
     * 用户要的从来不是"框架算得准"，而是"**延续上一块屏在看的方向**"，那个信息只有我们手里有。
     *
     * ★ 搬运的算式只有一句 `user_rotation += (I_old - I_new)`（推导见 [PanelOrientation]）。
     *   本机 `I` 为外 0 / 内 2 ⇒ 展开时加 2，正好等于内屏"正立"该写的值。
     *
     * ⚠️ 顺序上有两件事不能省：
     *   1. **先 [engageTakeover] 再搬**。若此刻 `ACCELEROMETER_ROTATION` 还是 1
     *      （比如刚从一个不接管的档切过来），系统会在几毫秒内用传感器覆盖掉我们刚写下去的
     *      `user_rotation` —— 那就又变回"让系统自己算"了，正是用户不准的那件事。
     *   2. 搬完要**同步 [lastAppliedRot]**，否则下一次 [applyRotationIfNeeded] 会以为还差
     *      一格又写一遍（值相同，无害，但日志上看着像抖动，排查时会误导）。
     *
     * ⚠️ 只在"模式需要我们接管"时才搬：SYSTEM 档方向盘本来就在系统手里，由它自己处理。
     */
    private fun onScreenFormChanged(form: ScreenForm) {
        // ★★ 换屏必记一行，**放在所有早退之前**（2026-09-29 补）。
        //
        //   理由：换屏方向出问题时，第一件要查的就是"此刻系统里有哪几块 display、各是哪个 id、
        //   各自多少度、USER_ROTATION 多少" —— 缺了它就只能靠猜。
        //   ⚠️ 尤其那句「各屏 …」：它是**终结"display 0 是哪块屏"这个争议的一手证据**。
        //   ✅ **2026-09-29 13:09 已定论**：`display 0` **跟着当前在用的那块屏走**
        //   （用户原话「display 是相对值，display#0 永远绑在当前使用的屏幕上」）——
        //   同一份日志里折叠态是「外屏#0」、展开态是「内屏#0 外屏#1」，两条实测同框出现。
        //   这里每换一次屏留一条样本，下次换机型还能用它自查。
        //   （形态切换频率极低，不会刷屏。）
        Log.i(
            TAG,
            "形态切换 → ${form.label}｜模式 ${AppPrefs.mode.value.label}" +
                "｜现量形态 ${runCatching { ScreenForm.probe(context).describe() }.getOrDefault("?")}" +
                "｜各屏 ${ActiveDisplay.describe(context)}" +
                "｜USER_ROTATION=${readUserRotation()} ｜accel=${readAutoRotate()}",
        )

        // 无论搬不搬，先把本屏的安装朝向报进状态 —— 它是"内屏 180°"那个 bug 的唯一判据
        // ★ 用回调给的 `form` 查表（而不是 [PanelOrientation.installOffset] 现量）：
        //   换屏这一瞬间 WMS 的窗口尺寸可能还没切过来，现量会拿到**旧屏**的值。
        val offset = PanelOrientation.offsetFor(form)
        _ui.update { it.copy(panelOffset = offset) }
        // ★ R2（2026-10-05）：这一闸同样换成 [AppPrefs.engagesAny] —— 换屏方向是**整块屏**的
        //   事，与"前台是哪个应用"无关（[mode] 在这里也读不到有意义的前台包）。
        //   漏换的症状：全局档跟随系统、但某个应用被点名成自适应时，
        //   展开 / 合上会让那个应用的方向记忆丢掉（这一段正是"搬运"它的地方）。
        if (!AppPrefs.engagesAny) return

        // ★ 白名单停手期间**不搬**（2026-09-29 补）。
        //   两个理由，缺一个都够：
        //   ① 语义：前台在白名单里 ⇒ 屏幕方向压根不归我们管（见 [ForegroundGate]），
        //      我们连"上一块屏在看什么"都不该替它延续。
        //   ② 机制：这一档下 [engageTakeover] 会被前台门挡住 ⇒ `takeoverOn` 仍是 false，
        //      而系统的 `ACCELEROMETER_ROTATION` 可能正开着（=1）—— 那写下去的
        //      `user_rotation` 会在几毫秒内被传感器覆盖，日志上却留下一条"搬运成功"的假象。
        //      （这个缺口正是给 [engageTakeover] 加前台门闸之后才出现的：以前它总会接管。）
        if (fgGated) {
            event("形态切换 → ${form.label}：前台应用在白名单里（已停手）→ 方向不搬")
            return
        }

        // ① 确保方向盘在我们手里（理由见注释第 1 条）
        //   ★★ 「账 vs 事实」的闸（2026-09-29 补，与 [applySemiRotation] / [applyRotationIfNeeded]
        //      同一处根因）：`takeoverOn` 是引擎**自己记的账**，`ACCELEROMETER_ROTATION` 才是事实。
        //      账陈旧时（前台门交接的边界 / MIUI / 用户拨了系统自动旋转开关）这一写会被传感器
        //      在几毫秒内覆盖，而日志留下一句「方向照搬上一块屏」的**假成功** —— 用户下次
        //      解锁才发现方向不对，且完全无从自查。
        //      这条路径**不排读回**（见 [scheduleSemiReadback]），所以不会误报，
        //      危害小于另外两处；但"写了等于没写还报成功"仍是 bug，判据统一按事实来。
        if (!_ui.value.takeoverOn || readAutoRotate() != 0) engageTakeover()
        if (readAutoRotate() != 0) {
            event(
                "形态切换 → ${form.label}：⚠️ 自动旋转不在本模块手里" +
                    "（ACCELEROMETER_ROTATION=1）→ 方向未搬运",
            )
            return
        }

        // ★★★ 2026-09-30 第二次修正：换屏时**本模块不写 `USER_ROTATION`**。
        //
        //   ============================ 两条错路都记在这里（别再走第三条）============================
        //   | 版本 | 换屏后我们做了什么 | 结局 |
        //   |---|---|---|
        //   | v4（09-29 深夜） | **照搬旧屏的值** | 外屏的 0 盖掉 HyperOS 灌的 3 ⇒ 用户报「朝左的竖屏」 |
        //   | v5（09-30 凌晨） | **按当前握姿重算** | 展开瞬间传感器还报"竖持" ⇒ 又算出 0，同样盖掉 3 |
        //   | **v6（现在）** | **不写**（保留框架灌入的槽位值） | —— |
        //
        //   真机实测 v5 失败的那一次（01:07:37 展开到内屏）：
        //   ```
        //   37.917  形态切换 → 内屏｜USER_ROTATION=3           ← HyperOS 灌的，正确
        //   37.917  触发：device_orientation=0                 ← 展开瞬间传感器报"竖持"（实测，没读错）
        //   38.174  按当前握姿定方向（设备空间 竖屏 → 面板 0）   ← 我们写 0，把对的盖成竖屏
        //   49.453  重力交叉校验：TYPE_27=3 重力扇区=3          ← 12 秒之后才报另一个姿态
        //   ```
        //   ⇒ 用户展开时**手上确实是竖持**（传感器实测，不是我们读错），但他**要的仍然是横屏**：
        //     他每次展开内屏都要横着用，`mUserRotationInner = ROTATION_270` 正是这个偏好的落盘。
        //
        //   ⇒ **v4 与 v5 犯的是同一个错**：都拿一个**外部读数**去覆盖 HyperOS 已经灌好的、
        //     用户自己的偏好。区别只是读数来源不同（旧屏值 vs 当前姿态）。
        //     而"用户想要哪个方向"这件事，**框架的每屏方向记忆比我们任何推算都准** ——
        //     那本来就是用户上一次在这块屏上定下来的值。
        //
        //   ⛔ 代价（知道清楚再改）：我们**不再**纠正"槽位值本身是错的"那种情况
        //     （09-28 用户报「展开倒置 180°」＝ 当时内屏槽位是 2）。但那种情况下的兜底是人：
        //     半自动模式下用户点一次按钮就会把槽位更新成对的 —— 让用户点一下，
        //     远比我们拿一个读数去猜可靠（本轮**四次**误修，每一轮都是"猜"的代价）。
        //   ⛔ 也别再加"延迟久一点再按姿态算"这类补丁：那只是在赌"传感器何时跟上"，
        //     而用户的偏好与姿态本来就没有因果关系（他竖持展开也想要横屏）。
        // ★★★ 2026-10-02 v7 / v8 期间这里还做过两件事（记录备查，**2026-10-03 已全部删除**）：
        //   ① 换屏后延迟 800ms 按届时姿态复核一次、不一致就纠正（`UNFOLD_APPLY_DELAY_MS`）；
        //   ② 查明内屏方向槽位就是 `user_rotation_inner`（见 [KEY_USER_ROTATION_PREFIX]），
        //      于是想用它来"养"展开方向。
        //   ⚠️ 用户对这件事的原话从头到尾没变：「**我要的是一展开就是屏幕正对我，
        //     而不是自己转一下**」。而"延迟复核"这条路**按工作原理就必然产生一次可见转动**
        //     （它就是靠"发现不对再扳一次"实现的）⇒ 用户 10-03 拍板：
        //     「**直接去掉展开后方向这个功能**」⇒ ① ② 连同它的 UI 一起删掉了。
        //   ⇒ 最终形态就是上面的 v6：**换屏时本模块不写 `USER_ROTATION`**，
        //     方向完全由框架灌它自己的槽位 ⇒ 用户看到的永远只是"保持这块屏的方向"
        //     （要用哪个方向，由用户通过「默认方向」定死），没有任何自动转动。
        if (!AppPrefs.engagesAny) {
            event("形态切换 → ${form.label}：本档不介入 ⇒ 方向保持系统灌入值")
            return
        }
        // ★ 日志里把**槽位**一并报出来（2026-10-02）：它是"展开会不会转"的**唯一决定量**，
        //   而它以前从来没被打印过 —— 排查时只能看到 USER_ROTATION，看不到它的源头。
        //   ⚠️ 用参数 `form` 读槽位，不用 `AppPrefs.screenForm`：这里它可能还没采纳到新形态。
        //
        // ★★ 2026-10-03：这里原本还有一段「延迟 800ms 复核方向、不对就纠正」的任务
        //   （`unfoldApplyJob` / `applyUnfoldDirection` / 固化值那一套），**已随"展开默认方向"
        //   功能整体删除**（用户原话「**直接去掉展开后方向这个功能**」）。
        //   ⇒ 现在的语义很简单：**换屏后方向完全交给系统**（框架灌它自己那份槽位），
        //     本模块一个字都不写。槽位现在的写入者只有"用户点「默认方向」"与"框架自己"
        //     ⇒ 展开时灌进来的就是用户定下的那个方向。
        //   ⛔ 别再往这里加"延迟之后再复查一次方向"：那正是用户看到"自己转一下"的来源。
        event(
            "形态切换 → ${form.label}：方向交由系统灌入（USER_ROTATION=" +
                "${readUserRotation()?.let { rotName(it) } ?: "读不到"}" +
                "；${form.label}槽位 ${readFormRotationSlot(form)?.let { rotName(it) } ?: "无"}）" +
                "⇒ 本模块不介入",
        )
    }

    /** 「等换屏搬运落定后再校验一次」的那次任务 —— 连着换屏时只留最后一次 */
    private var handoffVerifyJob: Job? = null

    /** 把"当前方向 / 本屏安装朝向"刷进 UI 状态（换屏搬运后调一次，供界面与总线读） */
    private fun refreshRotationUi() {
        _ui.update {
            it.copy(
                displayRotation = displayRotation(),
                panelOffset = PanelOrientation.installOffset(context),
            )
        }
    }

    /**
     * 量一次当前形态并交给 [AppPrefs]。
     *
     * ★ 幂等：形态没变时内部只做一次比较 + 一次派生值刷新（不写盘、不广播触发层），
     *   所以可以在"配置变化 + 落定后再来一次"里放心地调两遍。
     */
    private fun syncForm() {
        AppPrefs.syncScreenForm(context)
    }

    /**
     * 形态巡检：现在这块屏**还是不是**我们记着的那一块。
     *
     * ============================ 为什么必须有它（2026-09-29 用户报的 bug）============================
     * 用户报「**内屏桌面**旋转没反应，你是不是把整个桌面豁免了？」。查下来的真相是：
     * 当时那条"外屏桌面豁免"（**现已删除**，连同外屏旋转增强一起）本身**没写错** ——
     * 它要求 `form == OUTER` 才豁免。错的是喂给它的 `form` **陈旧**：引擎那次启动时设备是
     * 折叠的（量到外屏 425dp ⇒ `OUTER`），之后用户展开内屏，`form` **一直没更新**
     * ⇒ 那条豁免在**内屏**上被误命中 ⇒ 桌面被停手 ⇒ 按了也没反应。
     * **不是"把桌面整体豁免了"，是"把内屏当成了外屏"。**
     *
     * ⛔ 那条豁免已经删了，**但这个函数不能跟着删** —— `form` 现在仍然是两件事的输入：
     *   ① [AppPrefs.modeOf] 的闸（外屏恒 `SYSTEM` ⇒ 外屏完全不介入）；
     *   ② 悬浮按钮的尺寸与画在哪块屏。
     *   `form` 陈旧 ⇒ 在外屏误按内屏的模式跑、或按钮按错的屏画 —— 照样是"看着像坏了"。
     *
     * 为什么会陈旧：全工程能改 `form` 的只有 `AppPrefs.syncScreenForm`，它的触发源算下来只有两处 ——
     *   ① 引擎 [start] → [installFormWatch]（**只在那一次**。`AppPrefs.init` / `initHost` 的
     *      早退分支也会调，但那也要有人来调 `start`）；
     *   ② [formCallback] 的 `onConfigurationChanged`。
     * 而 ② 实测**不可靠**：真机证据是「展开内屏后 `fgform` 仍是 `OUTER`，直到手动软重启
     * SystemUI 才变成 `INNER`」。成因是 `ComponentCallbacks` 注册在 SystemUI 的
     * `systemContext` 上、而不是挑得动的 Application —— 🔎 推断它没被
     * `ActivityThread.handleConfigurationChanged` 的分发列表收进去。
     * ⚠️ 那只是"为什么 ② 不管用"的解释，**本次修复不依赖这个解释** ——
     *   与门控那几条豁免同一条纪律：不去猜机制，直接**观测**。
     *
     * ============================ 为什么是"巡检 + 落定确认" ============================
     * 折叠 / 展开是**带过渡动画**的（本机内屏 1672px ↔ 外屏 1168px，中间态既不是这个
     * 也不是那个）。2 秒一次的巡检**有概率正好落在动画中间**，那一刻量到的
     * `smallestWidthDp` 可能落到 [ScreenForm.INNER_MIN_WIDTH_DP] 的另一侧 ⇒ 把档位判到
     * 错误的那块屏上（后果：方向被搬错、按钮按错的屏画）。
     * 所以**发现不一致也不立刻采纳**，而是复用手上已有的 [FORM_SETTLE_MS] 落定机制：
     * 等 300ms 再量一次（[syncForm] → `AppPrefs.syncScreenForm`），由它来定夺。
     * ★ 一致时**一个字节都不写、一行日志都不打** —— 这个函数每 [FOREGROUND_POLL_MS]
     *   都会跑到，正常运行应该是"永远安静"的。代价只有一次 `maximumWindowMetrics` 读取。
     */
    private fun watchScreenForm() {
        val probe = runCatching { ScreenForm.probe(context) }.getOrNull() ?: return
        // ★ 先把"实测到多少 dp"如实报出去（即使形态没变）。它就是这次事故里
        //   最缺的那个读数：只看 `fgform` 分不清"量错了"还是"逻辑错了"（见 [UiState.formProbeDp]）。
        if (_ui.value.formProbeDp != probe.smallestWidthDp) {
            _ui.update { it.copy(formProbeDp = probe.smallestWidthDp) }
        }
        val current = AppPrefs.screenForm.value
        if (probe.form == current) {
            // ★ 形态没变 ⇒ **这块**屏此刻的面板值就是"换屏前的锚点"（见 [lastStablePanelRotation]）。
            //   放在这里而不是搬运那一刻现读，是为了躲开"框架抢先按新屏写 user_rotation"的竞态。
            //   代价只有每 [FOREGROUND_POLL_MS] 一次 `Display.getRotation()`（读的是本地状态，不发 IPC）。
            //
            // ★★ 2026-09-29 修：这里改走 [displayRotation]（**按尺寸认屏**，见它的 KDoc）。
            //   本分支采到的才是"换屏前的值"，而它**主要在折叠态**跑 —— 正是"读哪块屏"
            //   最容易出错的场景。⚠️ **别再改回 `Display.DEFAULT_DISPLAY`**：本机
            //   "display 0 属于哪块屏"至今有两种互相矛盾的记录（见 [ActiveDisplay]），赌不得。
            //   下面那行只在**值真的变了**时打：这条每 [FOREGROUND_POLL_MS] 都会跑到、不能刷屏，
            //   但排查"锚点到底是多少、从哪读的"时，它是唯一的一手证据。
            val sampled = displayRotation()
            if (sampled != lastStablePanelRotation) {
                Log.i(
                    TAG,
                    "锚点采样（当前 ${current.label}，实测 ${probe.smallestWidthDp}dp）：" +
                        "面板 $lastStablePanelRotation → $sampled" +
                        "（USER_ROTATION=${readUserRotation()}，各屏 ${ActiveDisplay.describe(context)}）",
                )
            }
            lastStablePanelRotation = sampled
            lastStablePanelRotForm = current
            return
        }

        // ============================================================ ★★ 形态**刚变**，补采一次锚点
        //
        // 这一刻 `AppPrefs.screenForm` **还是旧形态**（它要到 300ms 后的 [syncForm] 才更新）
        // ⇒ 用 `current`（旧形态）认屏，拿到的正是**旧屏最后那个值** —— 也就是搬运要的锚点。
        //
        // ⚠️⚠️ 为什么要在这里补采（2026-09-29，用户两次报障的**真正根因**）：
        //   上面那个采样分支只覆盖"形态没变"，而巡检周期是 [FOREGROUND_POLL_MS]（**2 秒**）。
        //   用户"折叠一下、马上再展开"时，折叠态停留**经常不到 2 秒** ⇒ 那段时间**一次都没采过**
        //   ⇒ `lastStablePanelRotation` 还停在**上一块屏（内屏）**的值上，却被搬运当成
        //   "旧屏（外屏）的锚点"用 ⇒ 搬过去的方向整体偏 `(内屏值 − 外屏值) mod 4`
        //   = ±90° 或 180°。用户先报"有概率翻转 180°"、再报"变成顺时针 90°"，
        //   就是同一个错的两组取值；而"有概率"= 折叠停留有没有跨过那个 2 秒采样点。
        //
        // ★ 注意这条结论**与"display 0 到底绑哪块屏"无关**（那个争议见 [ActiveDisplay]）——
        //   即便读屏完全正确，**没人去读**的时候锚点照样陈旧。所以这里是真正的修复点。
        //
        // ⚠️ 必须用 `current`（旧形态）而不是 `probe.form`（新形态）认屏：屏幕正在切，
        //   用新形态会拿到新屏刚配置出来的、还没稳定的值 —— 那是"新屏的初值"，不是"旧屏的终值"。
        runCatching { ActiveDisplay.rotationOf(context, current) }.getOrNull()?.let { lastOld ->
            Log.i(
                TAG,
                "锚点采样（形态切换瞬间·取**旧屏** ${current.label} 的值）：" +
                    "$lastStablePanelRotation → $lastOld" +
                    "（USER_ROTATION=${readUserRotation()}，各屏 ${ActiveDisplay.describe(context)}）",
            )
            lastStablePanelRotation = lastOld
            lastStablePanelRotForm = current
        }

        Log.i(
            TAG,
            "形态巡检：实测「${probe.form.label}」（最小宽度 ${probe.smallestWidthDp}dp，取自 ${probe.source}），" +
                "与记录的「${current.label}」不一致 → 等 ${FORM_SETTLE_MS}ms 落定后再确认",
        )
        formSettleJob?.cancel()
        formSettleJob = scope.launch {
            delay(FORM_SETTLE_MS)
            syncForm()
        }
    }

    // 录制开关（沿用 MVP 的 CSV 落盘，供事后曲线分析）
    fun toggleRecording(): Boolean {
        if (recorder.isRecording) recorder.stop() else recorder.start()
        return recorder.isRecording
    }

    val isRecording: Boolean get() = recorder.isRecording

    val recordingFile: String? get() = recorder.currentFile?.absolutePath

    companion object {
        private const val TAG = "FaceRotate"

    }
}

package cn.dsr213.hyperplus

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.WindowManager
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import cn.dsr213.hyperplus.trigger.DeviceOrientationTrigger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

        // —— 相机/接管 ——
        val cameraHeld: Boolean = false,
        val occupiedByOthers: Boolean = false,
        val takeoverOn: Boolean = false,
        val writeSettingsGranted: Boolean = false,

        // —— 方向标定 / 诊断 ——
        val sensorOrientation: Int = -1,
        val displayRotation: Int = 0,
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

    @Volatile private var bursting = false
    private var burstStartedAt = 0L
    private var burstBindRequestedAt = 0L
    @Volatile private var burstFrameCount = 0

    private var lastRot = -1
    private var lastAppliedRot = -1

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
     * burst 状态机的互斥锁 —— **只用来保护几个标志位的读写**，绝不包住
     * `bindToLifecycle` / `unbind` 这类会回调到 executor 的调用（会互锁）。
     */
    private val burstLock = Any()

    /** 最近 1.5s 的 roll 样本，供标定取中位数 */
    private val recentRolls = ArrayDeque<Pair<Long, Float>>()

    /** 前摄传感器朝向（硬件常量），懒加载 —— 作为图像旋转的固定基准 */
    private val sensorOrientation: Int by lazy { readFrontSensorOrientation() }

    // ============================================================ 生命周期

    fun start() {
        AppPrefs.init(context)

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
                displayRotation = displayRotation(),
                calibSign = decider.sign,
                calibOffsetDeg = decider.offsetDeg,
                calibrated = AppPrefs.isCalibrated,
            )
        }

        // 模式变化 → 启停触发层 + 接管/交还（方案 b）
        scope.launch {
            AppPrefs.mode.collect { m ->
                val first = _ui.value.mode != m
                _ui.update { it.copy(mode = m) }
                if (m == RotateMode.ADAPTIVE) {
                    startTrigger()
                    if (first) engageTakeover()
                } else {
                    stopTrigger()
                    if (first) releaseTakeover()
                }
            }
        }
        scope.launch {
            AppPrefs.strategy.collect { s ->
                _ui.update { it.copy(strategy = s) }
                // 从"响应优先"切回"省电优先"时，把常驻的相机立刻放掉
                if (s == CaptureStrategy.POWER_SAVING && !bursting) releaseCamera("切换为省电优先")
                event("采集策略 -> ${s.label}")
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

        if (AppPrefs.mode.value == RotateMode.ADAPTIVE) {
            startTrigger()
            engageTakeover()
        }
        event("引擎启动（模式=${AppPrefs.mode.value.label}）")
    }

    /**
     * 宿主退到后台：**立刻完全让位**。这不是可选优化，是必须做的：
     *  1) 相机绑在 Activity 生命周期上，宿主一 stop，CameraX 自己就解绑了，
     *     但我们内部的 selfHolding 标志不会自动跟着变 ⇒ 之后触发会误判"相机已常驻"；
     *  2) Android 14+ 后台根本不允许访问相机，硬撑只会失败；
     *  3) 接管若还开着，系统自动旋转已经被我们关掉，用户退到后台会发现屏幕转不动。
     */
    fun onHostStop() {
        stopTrigger()
        releaseCamera("宿主退到后台")
        if (_ui.value.takeoverOn) {
            releaseTakeover(restoreSystem = true, quiet = false)
            event("宿主退到后台 → 已交还系统自动旋转")
        }
    }

    /** 宿主回到前台：按当前模式恢复（ADAPTIVE 会把接管重新拿回来） */
    fun onHostStart() {
        if (AppPrefs.mode.value == RotateMode.ADAPTIVE) {
            startTrigger()
            engageTakeover()
        }
    }

    fun stop() {
        stopTrigger()
        releaseTakeover(restoreSystem = true, quiet = true)
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
        if (ok) event("触发层已启用（device_orientation，on-change，硬件级省电）")
        else event("⚠️ device_orientation 不可用，触发层未启动")
    }

    private fun stopTrigger() {
        trigger?.stop()
        // 刻意不置 null：DeviceOrientationTrigger 支持 start/stop 复用，
        // 退后台让位、回前台恢复时不必重建对象（也能保住累计统计）
        _ui.update { it.copy(sensorAvailable = false) }
    }

    // ============================================================ 让步闸门

    private fun registerAvailability() {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        cm = manager
        runCatching {
            frontIds = manager.cameraIdList.filter { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.toSet()
        }
        event("前摄 id = ${frontIds.joinToString(",")}")

        availabilityCb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                if (cameraId !in frontIds) return
                // 只有"我们没在用"时的可用事件才算别人释放了
                if (!selfHolding) {
                    occupiedByOthers = false
                    _ui.update { it.copy(occupiedByOthers = false) }
                }
            }

            override fun onCameraUnavailable(cameraId: String) {
                if (cameraId !in frontIds) return
                // ★ 关键：这个回调包含我们自己的占用。自己持有期间一律忽略，
                //   否则每次 burst 都会把自己误判成"被别人抢了"
                if (selfHolding) return
                occupiedByOthers = true
                _ui.update { it.copy(occupiedByOthers = true) }
                event("前摄 $cameraId 被其他应用占用 → 进入让步状态")
                // G3：正在 burst 时被抢，立刻让位
                if (bursting) {
                    event("burst 中被抢占 → 立即释放相机")
                    finishBurst(gaveUp = true)
                }
            }
        }.also { manager.registerAvailabilityCallback(executor, it) }
    }

    private var cm: CameraManager? = null
    private var frontIds: Set<String> = emptySet()

    // ============================================================ 触发 → burst

    private fun onTriggered(reason: String) {
        if (AppPrefs.mode.value != RotateMode.ADAPTIVE) return

        // G1：事前就知道被别人占着 —— 直接放弃，不排队、不重试
        if (occupiedByOthers && !selfHolding) {
            _ui.update { it.copy(skipOccupiedCount = it.skipOccupiedCount + 1) }
            event("让步：相机被其他应用占用，本次跳过")
            return
        }

        if (bursting) {
            _ui.update { it.copy(skipBusyCount = it.skipBusyCount + 1) }
            return
        }

        startBurst()
    }

    private fun startBurst() {
        val seq: Int
        val alreadyHolding: Boolean
        // 只用锁保护几个标志位的读写；真正的相机操作交给 [onMain] 串行（主线程 Looper 本身就是队列）。
        synchronized(burstLock) {
            bursting = true
            burstFrameCount = 0
            burstStartedAt = SystemClock.elapsedRealtime()
            seq = ++burstSeq
            alreadyHolding = selfHolding
        }

        // ★ 超时保护（实测踩过）：绑好相机后若帧一直不来
        //   （典型场景：宿主退到后台 CameraX 自己解绑、或相机被别的 App 抢走），
        //   burst 会永远卡在"进行中"，实测出现过「8 帧 / 40010ms」这种 40 秒的僵尸 burst。
        //   ⚠️ 判据必须带上 seq —— 否则会误杀后续的 burst（见 [burstSeq] 注释）。
        scope.launch {
            delay(BURST_TIMEOUT_MS)
            if (seq == burstSeq && bursting && burstFrameCount < BURST_FRAMES) {
                // 带上分析器诊断：0 帧 + 在途=1 ⇒ inFlight 泄漏（帧在入口被挡掉）；
                //               0 帧 + 在途=0 ⇒ 相机压根没开流。
                event(
                    "⚠️ burst 超时（${BURST_TIMEOUT_MS}ms 收到 $burstFrameCount 帧；" +
                        "analyzer 总帧=${analyzer?.totalFrames ?: -1} " +
                        "在途=${analyzer?.inFlightNow ?: -1} 峰值=${analyzer?.maxInFlight ?: -1}）→ 放弃",
                )
                finishBurst(gaveUp = true)
            }
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

        val bind = {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysisUseCase)
        }

        runCatching { bind() }.onFailure { e ->
            // 把异常原文与栈打出来，别再靠猜
            Log.w(TAG, "bindToLifecycle 失败（第 1 次）", e)
            event("相机绑定失败（${e.javaClass.simpleName}: ${e.message}），${BIND_RETRY_DELAY_MS}ms 后重试")
            scope.launch {
                delay(BIND_RETRY_DELAY_MS)
                // ★ 重试同样必须回主线程
                onMain {
                    if (seq != burstSeq || !bursting) return@onMain
                    runCatching { bind() }
                        .onSuccess { event("相机绑定重试成功") }
                        .onFailure { e2 ->
                            Log.w(TAG, "bindToLifecycle 失败（重试）", e2)
                            selfHolding = false
                            bursting = false
                            analysis = null
                            _ui.update { it.copy(cameraHeld = false) }
                            event("让步：相机绑定重试仍失败（${e2.javaClass.simpleName}: ${e2.message}），放弃本次 burst")
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
            _ui.update { it.copy(rotation = settled.rotation, deciderState = settled.state.name) }
            event(
                "burst 完成：$frames 帧 / ${dur}ms  " +
                    "判定 ${before.deciderState}(${before.rotation}) -> " +
                    "${settled.state.name}(${settled.rotation})",
            )
            applyRotationIfNeeded()
        }

        // G4：采完无条件释放（省电优先）；响应优先则保持常驻
        if (AppPrefs.strategy.value == CaptureStrategy.POWER_SAVING) {
            releaseCamera("burst 结束")
        }
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

    private fun applyRotationIfNeeded() {
        val s = _ui.value
        if (!s.takeoverOn) return
        if (s.rotation < 0) return
        if (s.deciderState != OrientationDecider.State.STABLE.name) return
        // ★ 写入冷却：Settings 写进去之后，系统更新 display rotation 有几十~几百 ms 的延迟，
        //   期间每帧回读都还是旧值 ⇒ 实测出现同一个值连写 3 次（日志噪声 + 白白 IPC）。
        if (SystemClock.elapsedRealtime() - lastWriteAt < WRITE_COOLDOWN_MS) return
        // ★ 与**实际显示方向**对比，而不是与"上次写过什么"对比：
        //   这样用户在别处手动改过方向、或系统被第三方改过，也能被纠正回来。
        val cur = displayRotation()
        if (cur == s.rotation) return
        writeUserRotation(s.rotation)
    }

    private fun writeUserRotation(rot: Int) {
        val ok = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, rot)
        }.getOrDefault(false)
        if (ok) {
            lastAppliedRot = rot
            lastWriteAt = SystemClock.elapsedRealtime()
            event("接管写入 user_rotation = $rot")
        } else {
            event("⚠️ 写入 user_rotation 失败")
        }
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
        if (_ui.value.takeoverOn) return
        if (!Settings.System.canWrite(context)) {
            _ui.update { it.copy(writeSettingsGranted = false) }
            event("⚠️ 缺少 WRITE_SETTINGS 授权 → 尚未接管（请在页面上点授权）")
            return
        }
        val cur = readAutoRotate()
        // ★ 落盘的是**交还目标值**（恒为 1），不是接管前的原值 —— 见 AppPrefs.RESTORE_AUTO_ROTATE。
        //   落盘是防进程被系统强杀后加速度计开关永久停在 0（实测踩过的事故）。
        AppPrefs.setRestoreTarget(AppPrefs.RESTORE_AUTO_ROTATE)
        runCatching {
            Settings.System.putInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
            )
        }
        AppPrefs.setTakeoverActive(true)
        _ui.update { it.copy(takeoverOn = true, writeSettingsGranted = true) }
        event(
            "★ 已接管：关闭系统自动旋转（接管前为 $cur；交还将还原为 " +
                "${AppPrefs.RESTORE_AUTO_ROTATE} = 开启），方向由人脸决定"
        )
    }

    private fun releaseTakeover(restoreSystem: Boolean = true, quiet: Boolean = false) {
        if (!_ui.value.takeoverOn && !AppPrefs.isTakeoverActive()) return
        if (restoreSystem) {
            val target = AppPrefs.restoreTarget()
            runCatching {
                Settings.System.putInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target,
                )
            }
            if (!quiet) event("已交还系统自动旋转（还原为 $target = 开启）")
        }
        AppPrefs.setTakeoverActive(false)
        lastAppliedRot = -1
        _ui.update { it.copy(takeoverOn = false) }
    }

    /** 供 UI 的「授权后重试接管」按钮调用 */
    fun retryTakeover() = engageTakeover()

    /**
     * ★ 孤儿接管恢复：进程被强杀时 `takeoverOn` 的内存态没了，
     *   但 `accelerometer_rotation` 已经被置 0 —— 若不还原，系统的自动旋转
     *   会**永久失效**（实测踩过：设置停在 0，手机再也转不动）。
     */
    private fun recoverOrphanTakeover() {
        if (!AppPrefs.isTakeoverActive()) return
        val target = AppPrefs.restoreTarget()
        val cur = readAutoRotate()
        // ★ 判据从「cur == 0」改成「cur != target」：目标值现在恒为 1，
        //   只有真的还是 0（被我们关掉没还）才需要动；若用户自己又开回来了，就不要覆盖他的选择。
        if (cur != target) {
            runCatching {
                Settings.System.putInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target,
                )
            }
            Recovery.fixed = true
            event("⚠️ 检测到上次未正常退出（孤儿接管）→ 已还原系统自动旋转 = $target（原为 $cur）")
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
        AppPrefs.setCalibration(decider.sign, decider.offsetDeg)
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
            AppPrefs.setCalibration(decider.sign, decider.offsetDeg)
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
        AppPrefs.clearCalibration()
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
            if (AppPrefs.mode.value != RotateMode.ADAPTIVE) AppPrefs.setMode(RotateMode.ADAPTIVE)

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
        burstFrameCount++

        val roll = pickRoll(frame)
        val r = decider.update(roll, frame.atMs, frame.faceCount)

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
                rotation = r.rotation,
                deciderState = r.state.name,
                displayRotation = displayRotation(),
                switchCount = s.switchCount + if (newRotSwitch) 1 else 0,
                totalFrames = analyzer?.totalFrames ?: s.totalFrames,
                facesFound = analyzer?.facesFound ?: s.facesFound,
                // 第一帧到达 = open 真正完成
                lastOpenMs = if (burstFrameCount == 1 && burstBindRequestedAt > 0) {
                    SystemClock.elapsedRealtime() - burstBindRequestedAt
                } else s.lastOpenMs,
            )
        }

        // ★ 每帧都尝试落定，而不是只在 burst 结束时判一次 —— 实测踩坑：
        //   「时间保持」commit 的那一帧 state 确实是 STABLE，但用户持续转动时
        //   下一帧立刻进入新的 CANDIDATE ⇒ 只在 burst 末尾判定会**恰好错过所有
        //   commit 时刻**（实测：rotation 切换 10 次，user_rotation 只被写 1 次）。
        applyRotationIfNeeded()

        if (bursting && burstFrameCount >= BURST_FRAMES) {
            finishBurst()
        }
    }

    /**
     * CSV 列顺序（必须与 [CsvRecorder.HEADER] 逐列一致，也与 2026-09-25 那批
     * 历史实测数据保持一致，方便直接复用分析脚本）：
     * at_ms, euler_z, eye_roll, face_count, face_area, detect_ms,
     * rot_deg, sys_rot, extra_rot, tried, interval_ms,
     * smoothed, norm, decided, state, deviation, low_conf, err
     */
    private fun csvRow(f: FaceFrame, r: OrientationDecider.Result) = String.format(
        java.util.Locale.US,
        "%d,%s,%s,%d,%.4f,%d,%d,%d,%d,%d,%d,%s,%s,%d,%s,%s,%d,%s",
        f.atMs, fmt(f.eulerZ), fmt(f.eyeRoll), f.faceCount, f.faceArea, f.detectMs,
        f.rotationDegrees, _ui.value.displayRotation, f.extraRotation, f.triedCount, f.intervalMs,
        fmt(r.smoothedRoll), fmt(r.normalized), r.rotation, r.state.name, fmt(r.deviation),
        if (f.lowConf) 1 else 0, f.err ?: "",
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
     * 当前屏幕方向（0/1/2/3）。只用于诊断显示 + CSV 对照 + 判断"要不要写"。
     *
     * ★ 取值顺序（2026-09-25 调整，为「引擎跑进 SystemUI」而改）：
     *   1) `DisplayManager.getDisplay(DEFAULT_DISPLAY)` —— 最通用。传入的 context 是不是
     *      视觉上下文都无所谓，Activity / Service / **SystemUI 的 Application** 下都拿得到；
     *   2) 兜底 `WindowManager.defaultDisplay`（已废弃，但在 Activity 下仍然可靠）。
     *
     * ⚠️ 为什么必须先试 DisplayManager：本函数的返回值直接参与
     *   [applyRotationIfNeeded] 的「当前方向 == 目标方向」判断。一旦它恒定返回一个错的值，
     *   引擎会以为每次都"不一致"，于是每 [WRITE_COOLDOWN_MS] 就重复写一次 user_rotation
     *   （日志噪声 + 白费 IPC）。所以宁可多一层兜底，也不要赌某一个 API 在宿主进程里可用。
     */
    private fun displayRotation(): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: run {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.rotation
            }
    }.getOrDefault(0)

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

    /** 从系统「修改系统设置」授权页回来，刷新授权状态并补一次接管 */
    fun refreshWriteSettingsGrant() {
        val granted = Settings.System.canWrite(context)
        _ui.update { it.copy(writeSettingsGranted = granted) }
        if (granted && AppPrefs.mode.value == RotateMode.ADAPTIVE) engageTakeover()
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

        /**
         * 一次 burst 采多少帧。
         *
         * ★ 16 帧的依据：EMA(α=0.35) 从 0° 收敛到跨过扇区边界约需 7 帧，
         *   再加 holdMs=250ms 的确认期（约 4 帧 @63ms），8 帧走不完。
         *   16 帧 ≈ 1.0s，给足收敛+确认余量。
         */
        const val BURST_FRAMES = 16

        /** burst 硬超时：超过这个时间还没凑齐帧数就放弃，防止僵尸 burst 把状态机卡死 */
        const val BURST_TIMEOUT_MS = 1500L

        /** 标定采样窗口：主动开相机采这么久，再取中位数 */
        const val CALIBRATION_MS = 2600L

        /** 标定至少要有这么多个有效人脸样本才算成功 */
        const val MIN_CALIB_SAMPLES = 5

        /** 两次写入 user_rotation 之间的最小间隔 —— 等系统把新方向反映到 display 上 */
        const val WRITE_COOLDOWN_MS = 300L

        /** 相机绑定失败后的重试延迟（首次失败多是上一个 burst 未收尾，不是真被占用） */
        const val BIND_RETRY_DELAY_MS = 400L
    }
}

/**
 * CameraX 的一次性引导。
 *
 * ============================ 为什么必须显式配置（实测，非推断） ============================
 * `ProcessCameraProvider.getInstance(context)` 会构造 `CameraX(context, provider)`，
 * provider 为 null 时 CameraX 去**猜**默认实现。反编译 `CameraX.getConfigProvider` 得到的
 * 真实查找顺序只有两条：
 *
 *   ① `context.getApplicationContext() instanceof CameraXConfig.Provider`
 *      —— 需要宿主 Application 实现该接口；
 *   ② 否则拿 `context` 所属包去查 `androidx.camera.core.impl.MetadataHolderService`
 *      的 meta-data：`…MetadataHolderService.DEFAULT_CONFIG_PROVIDER`
 *      = `androidx.camera.camera2.Camera2Config$DefaultProvider`。
 *      （这条 meta-data 由 camera-camera2 的 AAR 清单合并进**我们 App 的**清单，实测存在。）
 *
 * ⇒ 引擎跑在 **SystemUI 进程**里、用的是 SystemUI 的 Application 与包名，
 *   ①②**两条都不成立** ⇒ 实测抛：
 * ```
 * IllegalStateException: CameraX is not configured properly. The most likely cause is you did
 *   not include a default implementation in your build such as 'camera-camera2'.
 * ```
 * ⚠️ 这句话会把人带偏 —— 依赖明明在，缺的是「从宿主包里找不到配置」。
 *
 * ⇒ 解法：在**第一次** `getInstance` 之前调一次
 *   `ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig())`。
 *   该方法字节码做的事就是把 provider 塞进 `mCameraXConfigProvider`，
 *   而 `getOrCreateCameraXInstance` 正是把它传给 `CameraX(context, provider)`。
 *   ⚠️ 它内部有 `checkState(mCameraXConfigProvider == null)` ——
 *      **重复调用会抛** "CameraX has already been configured"，
 *      所以这里用 [done] 保证每进程只做一次，且整体 runCatching 兜住。
 *
 * ★ 单机模式（App 进程）靠清单发现本来就能过，但仍然走同一条路：
 *   少一条「只在某个模式下才成立」的分支，就少一类「只有托管模式才复现」的怪问题。
 */
private object CameraXBootstrap {
    @Volatile
    private var done = false

    /**
     * ★ 该方法标了 `@ExperimentalCameraProviderConfiguration` —— 那是 **androidx 的**
     *   `@RequiresOptIn`（不是 Kotlin 的），编译器实测**不要求** `@OptIn`（加了反而报
     *   "has no effect" 的警告），所以这里不加多余注解。
     */
    fun ensureExplicitConfig(): String {
        if (done) return "已配置过，跳过"
        done = true
        return runCatching {
            ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig())
            "✅ 已显式配置（Camera2Config.defaultConfig）"
        }.getOrElse {
            "⚠️ 显式配置失败 ${it.javaClass.simpleName}: ${it.message}（若清单发现可用则无碍）"
        }
    }
}

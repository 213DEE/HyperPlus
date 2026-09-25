package cn.dsr213.hyperplus.module

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cn.dsr213.hyperplus.AdaptiveEngine
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.RotateMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 在 **SystemUI 进程**里把引擎跑起来，并把它接到 App 侧的总线上。
 *
 * ============================ 这一步解决了什么 ============================
 * 用户反馈：「打开 App 页面之后是正常的，但回到桌面又被系统的自动旋转接管」。
 * 根因是引擎宿主是 Activity。本类把宿主换成常驻的 SystemUI 进程之后，
 * **不存在「退到后台」这回事**，交还逻辑永远不会因回桌面而触发。
 *
 * ============================ 纪律（生死线） ============================
 * 这段代码跑在 SystemUI 进程里。SystemUI 崩 = 状态栏 / 导航栏崩，
 * 严重时用户连桌面都进不去。所以：
 *
 *  1. **启动路径上不能有重活** —— 本类的 [start] 由 `HyperPlusModule` 在开机 4s 后
 *     的独立后台线程上调用，绝不在 SystemUI 的启动路径上跑；
 *  2. **每一处都要兜住** —— 任何未捕获异常都会杀掉整个进程。引擎内部另有
 *     `CoroutineExceptionHandler` + 线程级 handler 双保险，都汇到 [panic]；
 *  3. **异常兜底按用户拍板：一次就停** —— 宁可自适应功能停掉，也绝不让状态栏崩。
 *     [panic] 会停引擎、还原系统自动旋转、退回普通状态栏，然后**不再尝试重启**。
 */
internal object EngineHost {

    private const val TAG = HyperPlusModule.TAG

    /** 状态摘要两次上报之间的最小间隔 —— 防止 Settings 写入把 SystemUI 拖慢 */
    private const val PUBLISH_MIN_INTERVAL_MS = 2_000L

    /**
     * 心跳周期。**与状态变化无关**，到点就写（见 [installLivenessTick]）。
     *
     * 取 5 秒的依据：App 侧把"心跳距今 > 15 秒"判为失联（[ModuleLink.HOST_LOST_AFTER_SEC]），
     * 5 秒周期 ⇒ 真失联最多 3 个周期内被发现，同时每分钟只有 12 次 `Settings` 写入，
     * 对 SystemUI 是可以忽略的开销。
     */
    private const val HEARTBEAT_PERIOD_MS = 5_000L

    @Volatile private var engine: AdaptiveEngine? = null
    @Volatile private var starting = false

    /** 有一次致命异常就置位，之后不再尝试启动（用户拍板：一次异常就停） */
    @Volatile private var dead = false

    @Volatile private var bootThread: HandlerThread? = null

    /**
     * 本引擎的启动时刻（`elapsedRealtime`，毫秒）。
     *
     * ★ 不能直接用 `SystemClock.elapsedRealtime()` 当"已运行"上报 —— 那是**开机**以来的时间，
     *   界面会显示成"宿主已运行 116131 秒"（≈32 小时，其实是整机 uptime），是假数据。
     */
    @Volatile private var startedAtMs = 0L

    private var pingObserver: ContentObserver? = null
    private var calibObserver: ContentObserver? = null
    private var lastPing = Int.MIN_VALUE
    @Volatile private var lastCalibReq = 0

    val isDead: Boolean get() = dead

    // ================================================================ 启动

    /**
     * 【**worker 线程**调用】准备宿主环境并把引擎挂上去。
     *
     * 顺序**不可调换**（理由见 [HostEnv] 的类注释）：
     *   ① 预加载 native so → ② 初始化 MlKitContext → ③ 配置后端切到镜像
     *   → ④（主线程）造 lifecycle + 引擎 + start
     */
    fun start(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        if (starting || engine != null || dead) {
            Log.i(TAG, "引擎已启动 / 正在启动 / 已停用，跳过（starting=$starting engine=${engine != null} dead=$dead）")
            return
        }
        starting = true

        val t = HandlerThread("hyperplus-engine-boot").apply { start() }
        bootThread = t
        Handler(t.looper).post {
            runCatching { bootOn(hostCtx, appCtx, classLoader) }
                .onFailure {
                    Log.e(TAG, "引擎启动过程异常（已吞掉，不影响 SystemUI）", it)
                    starting = false
                    panic(hostCtx, "启动异常", it)
                }
        }
    }

    private fun bootOn(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        // ① + ② ML Kit 的宿主环境（必须在任何 ML Kit 类被触碰之前）
        val notes = runCatching { HostEnv.prepare(hostCtx, appCtx, classLoader) }
            .getOrElse { listOf("❌ 宿主环境准备异常：${it.javaClass.simpleName}: ${it.message}") }
        notes.forEach { Log.i(TAG, "  [env] $it") }

        // ③ 配置后端 = 跨进程镜像（读 App 改的值，实时跟随）
        AppPrefs.initHost(hostCtx)

        // ④ 主线程：lifecycle 与 LifecycleRegistry 都有 checkMainThread()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val lc = SystemUiLifecycle()
                lc.resumeForever()
                val eng = AdaptiveEngine(hostCtx, lc) { e -> panic(hostCtx, "引擎内部异常", e) }
                engine = eng
                eng.start()
                startedAtMs = SystemClock.elapsedRealtime()
                starting = false
                publishPhase(hostCtx, "ready")
                installBus(hostCtx)
                installStatePublisher(hostCtx, eng)
                installLivenessTick(hostCtx)
                Log.i(TAG, "✅ 引擎已在 SystemUI 内启动（常驻，不随前台/后台变化）")
            }.onFailure {
                starting = false
                Log.e(TAG, "引擎启动失败（已吞掉，不影响 SystemUI）", it)
                panic(hostCtx, "启动失败", it, publishFailure = true)
            }
        }
    }

    // ================================================================ 异常兜底

    /**
     * 用户拍板的兜底：**一次异常就停**。
     *
     * 做三件事，顺序不能反：
     *   ① 停引擎（内部会 `releaseTakeover(restoreSystem = true)` → 还原系统自动旋转）；
     *   ② 再兜一次原始设置 —— 万一引擎没走到解绑那一步（例如构造期就炸了），
     *      这里直接把 `accelerometer_rotation` 写回 [AppPrefs.RESTORE_AUTO_ROTATE]
     *      并清掉接管标志，避免用户遇到"屏幕转不动"；
     *   ③ 标记 [dead]，**不再重启**。
     */
    private fun panic(hostCtx: Context, why: String, e: Throwable?, publishFailure: Boolean = false) {
        if (dead) return
        dead = true
        Log.e(TAG, "⚠️ 引擎停用（$why）—— 已还原系统自动旋转，自适应功能下线", e)

        runCatching { engine?.stop() }
        engine = null

        runCatching {
            if (AppPrefs.isTakeoverActive()) {
                Settings.System.putInt(
                    hostCtx.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    AppPrefs.RESTORE_AUTO_ROTATE,
                )
                AppPrefs.setTakeoverActive(false)
            }
        }

        if (publishFailure) {
            val msg = "v1|phase=failed|err=" + (e?.javaClass?.simpleName ?: why)
            runCatching { PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.STATE, msg) }
        } else {
            publishPhase(hostCtx, "stopped")
        }
    }

    // ================================================================ 总线：探活 / 远程标定

    /**
     * 装上两条 App → 宿主的请求通道（复用本项目已实测的「设置键 + ContentObserver」机制）：
     *  - **探活**：App 写 `bus_ping`（自增），这里原值回写 `bus_pong` —— App 据此判断
     *    「模块在不在」，从而决定自己要不要启本地引擎（避免两套引擎抢相机）；
     *  - **远程标定**：App 写 `bus_calib_req`，这里用宿主的相机采一次样，
     *    把结果写回 `bus_calib_result`。App 界面上的两个校准按钮因此在托管模式下依然可用。
     *
     * ★ 两条都挂在专用 HandlerThread 上，绝不占用 SystemUI 主线程。
     */
    private fun installBus(hostCtx: Context) {
        val cr = hostCtx.contentResolver
        val t = HandlerThread("hyperplus-bus").apply { start() }
        val h = Handler(t.looper)

        val po = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                runCatching {
                    val v = PrefsBridge.readInt(cr, PrefsBridge.PING, 0)
                    if (v == lastPing) return
                    lastPing = v
                    PrefsBridge.writeInt(cr, PrefsBridge.PONG, v)
                }.onFailure { Log.w(TAG, "探活回执失败", it) }
            }
        }
        pingObserver = po
        PrefsBridge.watch(cr, PrefsBridge.PING, po)

        val co = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                runCatching {
                    val req = PrefsBridge.readInt(cr, PrefsBridge.CALIB_REQ, 0)
                    if (req == 0 || req == lastCalibReq) return
                    lastCalibReq = req
                    PrefsBridge.writeInt(cr, PrefsBridge.CALIB_REQ, 0)   // 复位，可反复触发
                    doCalibrationRequest(hostCtx, req)
                }.onFailure { Log.w(TAG, "远程标定失败", it) }
            }
        }
        calibObserver = co
        PrefsBridge.watch(cr, PrefsBridge.CALIB_REQ, co)

        Log.i(TAG, "总线就绪：探活 ${PrefsBridge.PING} / 远程标定 ${PrefsBridge.CALIB_REQ}")
    }

    /**
     * 处理一次远程标定请求。
     *  - `req == 1` → 记竖屏基准（[AdaptiveEngine.applyCalibrationBaseline]）
     *  - `req == 2` → 记左横屏、定方向（[AdaptiveEngine.applyCalibrationAxis]）
     *
     * ★ 应用动作在**宿主侧**完成（相机也在宿主手里），所以结果通过镜像自然回流到 App，
     *   App 不需要自己算，也不会出现两边标定值打架。
     */
    private fun doCalibrationRequest(hostCtx: Context, req: Int) {
        val eng = engine
        if (eng == null) {
            publishCalibResult(hostCtx, req, if (dead) "stopped" else "starting")
            return
        }
        eng.captureCalibrationSample { roll ->
            val status = when {
                roll == null || !roll.isFinite() -> "noface"
                req == 1 -> {
                    eng.applyCalibrationBaseline(roll)
                    "ok"
                }
                else -> if (eng.applyCalibrationAxis(roll)) "ok" else "badangle"
            }
            publishCalibResult(hostCtx, req, status)
        }
    }

    private fun publishCalibResult(hostCtx: Context, req: Int, status: String) {
        val line = "$req|$status"
        Handler(bootThread?.looper ?: Looper.getMainLooper()).post {
            runCatching {
                PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.CALIB_RESULT, line)
                Log.i(TAG, "远程标定结果 → $line")
            }
        }
    }

    // ================================================================ 状态上报

    /**
     * 把引擎状态摘要写回镜像，App 界面据此显示「托管中」的诊断信息。
     *
     * ★ 只上报**慢变量**（模式 / 接管 / 判定结果 / 计数），**不含** roll、eulerZ 这类逐帧值：
     *   那些每秒变十几次，写系统设置会把 SystemUI 拖慢，而且界面也没必要看。
     *   再加 2s 节流 + `distinctUntilChanged`，实际写入频率极低。
     */
    private fun installStatePublisher(hostCtx: Context, eng: AdaptiveEngine) {
        val cr = hostCtx.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            var last = ""
            var lastAt = 0L
            eng.ui.map { summary(it) }.distinctUntilChanged().collect { line ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < PUBLISH_MIN_INTERVAL_MS) {
                    delay(PUBLISH_MIN_INTERVAL_MS - (now - lastAt))
                }
                if (line == last) return@collect
                last = line
                lastAt = SystemClock.elapsedRealtime()
                runCatching {
                    PrefsBridge.writeString(cr, PrefsBridge.STATE, line)
                }.onFailure { Log.w(TAG, "状态上报失败", it) }
            }
        }
    }

    /**
     * 心跳：**不管状态有没有变化，到点就写**。
     *
     * ★ 为什么必须与状态上报分开（实测踩的）：状态是"变化才写"的，稳定态下（没人脸事件、
     *   方向没切、decider 停在 STABLE）整串都不动，那一刻的 `bus_engine_heartbeat` 就会**冻住**。
     *   实测两次读数都是 607，而引擎明明活着 —— 于是界面会显示"上次上报 312 秒前"，
     *   把一台健康的机器说成失联。**这就是"界面在说假话"，必须修。**
     *
     * ⇒ 心跳单独一个循环无条件写；它只回答一个问题：**宿主还活着吗**。
     *   "引擎在干活吗"由状态摘要回答（frames/faces/decider 那些字段），两件事分开表达。
     *
     * ⚠️ [dead] 置位后必须停写 —— 那时引擎已下线，继续发心跳就是在谎报存活。
     *   此时 `phase=stopped/failed` 由 [panic] 写入，界面据它如实显示"已停用"。
     */
    private fun installLivenessTick(hostCtx: Context) {
        val cr = hostCtx.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            while (!dead) {
                runCatching {
                    PrefsBridge.writeInt(
                        cr,
                        PrefsBridge.HEARTBEAT,
                        (SystemClock.elapsedRealtime() / 1000).toInt(),
                    )
                }.onFailure { Log.w(TAG, "心跳写失败", it) }
                delay(HEARTBEAT_PERIOD_MS)
            }
        }
    }

    private fun publishPhase(hostCtx: Context, phase: String) {
        runCatching {
            PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.STATE, "v1|phase=$phase")
        }
    }

    /**
     * 状态摘要格式（v1，`|` 分隔的 k=v）—— 新字段只追加，不改已有字段名。
     *
     * ★ 这个字符串是**跨进程契约**：App 侧的 `ModuleLink.parse` 按它解析。
     *   改格式必须两边一起改，并且把 `v1` 升级（Additive only）。
     *
     * ★ `startedAt` / `uptime` 的关系（踩过一次才知道要这么设计）：
     *   状态是**变化才上报**的（见 [installStatePublisher]）——这本身是对的，
     *   省电又不扰民。但它有个反直觉的后果：**没有新事件时整串都不动**，
     *   于是 `uptime` 会冻在"最后一次上报那一刻"的值上。
     *   界面要是直接显示它，就会长期停在"已运行 44 秒"（实测），是假数据。
     *   ⇒ 所以额外给出 `startedAt`（引擎启动时的 `elapsedRealtime` 秒）。
     *     App 与宿主在同一台设备上，`elapsedRealtime` 是同一条系统级时钟，
     *     所以 App 可以用 `now - startedAt` **自己算出实时运行时长**，不需要宿主定时刷。
     */
    fun summary(s: AdaptiveEngine.UiState): String = buildString {
        append("v1")
        append("|phase=ready")
        append("|mode=").append(s.mode.name)
        append("|takeover=").append(if (s.takeoverOn) 1 else 0)
        append("|rot=").append(s.rotation)
        append("|disp=").append(s.displayRotation)
        append("|decider=").append(s.deciderState)
        append("|burst=").append(s.burstCount)
        append("|frames=").append(s.totalFrames)
        append("|faces=").append(s.facesFound)
        append("|sw=").append(s.switchCount)
        append("|calib=").append(if (s.calibrated) 1 else 0)
        append("|offset=").append(String.format(Locale.US, "%.1f", s.calibOffsetDeg))
        append("|sign=").append(s.calibSign)
        append("|sensor=").append(if (s.sensorAvailable) 1 else 0)
        append("|grant=").append(if (s.writeSettingsGranted) 1 else 0)
        append("|openMs=").append(s.lastOpenMs)
        append("|uptime=").append(engineUptimeSec())
        append("|startedAt=").append(if (startedAtMs <= 0L) 0L else startedAtMs / 1000)
    }

    /** 本引擎已运行秒数（上报那一刻的快照）；还没起来时返回 0（不拿整机 uptime 冒充） */
    private fun engineUptimeSec(): Int {
        val t = startedAtMs
        if (t <= 0L) return 0
        return ((SystemClock.elapsedRealtime() - t) / 1000).toInt()
    }
}

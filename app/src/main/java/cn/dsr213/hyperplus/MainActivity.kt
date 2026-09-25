package cn.dsr213.hyperplus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import cn.dsr213.hyperplus.ui.EngineScreen
import cn.dsr213.hyperplus.ui.FaceRotateTheme
import java.util.concurrent.Executors

/**
 * 薄壳 Activity：权限、授权跳转、**宿主归属判定**，业务逻辑都在 [AdaptiveEngine]。
 *
 * ============================ 本类的核心职责：别让两套引擎同时活着 ============================
 * 引擎有两个可能的宿主：
 *  1. **SystemUI 里的常驻引擎**（LSPosed 模块，见 `module.EngineHost`）—— 装机启用模块后就在跑；
 *  2. **App 自己的本地引擎**（以前的形态）—— 只有 App 在前台时活着。
 *
 * 两者**绝不能同时存在**：它们会抢相机、抢写 `user_rotation`，症状是方向乱跳。
 *
 * 所以 [onCreate] 里先探一次活（[ModuleLink.ping]）：
 *  - 有回执 → 进**托管模式**：不创建本地引擎，界面只当遥控器 + 显示宿主回传的状态。
 *    ★ 这也顺手解掉了用户反馈的「打开 App 正常、回桌面就失效」——
 *      托管模式下 `onStop()` 里没有引擎可交还，SystemUI 那边的引擎一直活着。
 *  - 没回执 → 起本地引擎，行为与以前**完全一致**（没装模块的人照样能用）。
 */
class MainActivity : ComponentActivity() {

    /** 只在「单机模式」下才会被创建；托管模式恒为 null */
    private var engine: AdaptiveEngine? = null

    private var cameraGranted by mutableStateOf(false)
    private var recording by mutableStateOf(false)

    /** 探活是否已完成（完成前界面显示"正在确认运行位置"） */
    private var probed by mutableStateOf(false)

    /** 是否由 SystemUI 托管 */
    private var hosted by mutableStateOf(false)

    /** 宿主回传的引擎状态 */
    private var hostState by mutableStateOf<ModuleLink.State?>(null)

    /** 跨进程镜像写不进去（多为缺「修改系统设置」授权）→ 界面要如实提示 */
    private var mirrorProblem by mutableStateOf(false)

    /** root 是否可用（= 能不能把配置同步给常驻引擎；见 [RootBridge]） */
    private var rootReady by mutableStateOf(false)

    /** 探活用线程池：内部要阻塞轮询，绝不能占主线程 */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hyperplus-probe") }

    private var probing = false

    /** 上次探活时刻，用于节流（探活要 spawn `su`，见 [probeHost]） */
    private var lastProbeAtMs = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            if (granted) engine?.onCameraPermissionGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPrefs.init(this)
        probeRoot()

        cameraGranted = hasCamera()
        // ★ 权限没给就**主动申请**，不要等用户去界面里找按钮。
        //   踩过的坑（2026-09-25）：改包名后等同于全新应用，运行时权限被清空；
        //   而 App 不主动申请 ⇒ 相机永远打不开 ⇒ burst 恒 0 帧 ⇒ 方向**完全不工作**，
        //   现象就是「彻底失效」。发布到 GitHub 后每个新用户都会先遇到这一步。
        if (!cameraGranted) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }

        probeHost()

        setContent {
            FaceRotateTheme {
                EngineScreen(
                    engine = engine,
                    appVersion = BuildConfig.VERSION_NAME,
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                    mirrorProblem = mirrorProblem,
                    rootReady = rootReady,
                    onRequestWriteSettings = { requestWriteSettings() },
                    onRootGrant = { requestRoot() },
                    onToggleRecording = { recording = engine?.toggleRecording() ?: false },
                    onRequestCameraPermission = { permLauncher.launch(Manifest.permission.CAMERA) },
                    onCalibrate = { step -> calibrate(step) },
                    onRefreshHost = { refreshHostState() },
                    cameraGranted = cameraGranted,
                )
            }
        }
    }

    // ================================================================ 宿主判定

    /**
     * 【后台】探一次活，再回主线程决定跑哪套引擎。
     *
     * ★ 探活**不便宜**：App 侧写 `bus_ping` 要走 root（一次 `su` spawn，数百毫秒，
     *   见 [PrefsBridge] 类注释里那个坑）。所以它**不能挂在 `onResume` 上无脑跑** ——
     *   那样用户每切回一次应用就白花一次。这里用 [lastProbeAtMs] 做节流，
     *   真正的触发时机是「冷启动」和「上次判定为非托管」。
     */
    private fun probeHost() {
        if (probing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeAtMs < PROBE_MIN_INTERVAL_MS) return
        lastProbeAtMs = now
        probing = true
        io.execute {
            val st = runCatching { ModuleLink.ping(this) }
                .getOrElse { ModuleLink.Status(false, null) }
            runOnUiThread {
                probing = false
                probed = true
                hosted = st.alive
                hostState = st.state
                if (st.alive) {
                    // ★ 托管模式：**不创建本地引擎**。这一句就是"回桌面不再被系统接管"的根因修复
                    val local = engine
                    engine = null
                    local?.let { runCatching { it.stop() } }
                } else {
                    startLocalEngine()
                }
            }
        }
    }

    /** 单机模式：与改造前完全一致的本地引擎 */
    private fun startLocalEngine() {
        if (engine != null) return
        AppPrefs.init(this)
        val e = AdaptiveEngine(this, this)
        engine = e
        e.start()
    }

    /** 纯读一眼宿主留下的状态（不探活），用于界面定时刷新 */
    private fun refreshHostState() {
        if (!hosted) return
        hostState = ModuleLink.currentState(this)
        mirrorProblem = AppPrefs.mirrorProblem.value
    }

    // ================================================================ 生命周期
    //
    // ★ 托管模式下 engine == null，所以 onStart/onStop 里什么都不会发生 ——
    //   SystemUI 那边的引擎不会因为我们退到后台而被交还。这正是本次改造的目的。

    override fun onStart() {
        super.onStart()
        engine?.onHostStart()
    }

    override fun onStop() {
        engine?.onHostStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从「修改系统设置」授权页回来时，重新读授权状态；若已授权则补一次接管
        engine?.refreshWriteSettingsGrant()
        rootReady = RootBridge.available()
        mirrorProblem = AppPrefs.mirrorProblem.value
        // 回到前台重新确认一次运行位置（用户可能刚在 LSPosed 里启用/停用了模块）。
        //  - 已知托管：先免费地读一眼回传状态；要真探活的话由 [probeHost] 自己的节流把关；
        //  - 否则（单机 / 还没定论）：走探活，同样受节流保护。
        if (hosted) refreshHostState() else probeHost()
    }

    override fun onDestroy() {
        // ★ 必须还原：接管期间 accelerometer_rotation 被置 0，
        //   如果直接退出而不还原，屏幕方向会永久卡在最后一次写入的值
        runCatching { engine?.stop() }
        io.shutdownNow()
        super.onDestroy()
    }

    // ================================================================ 标定（两种模式分流）

    /**
     * 标定分流：
     *  - 托管模式 → 交给宿主（**相机在 SystemUI 手里**，App 这边开相机只会两边抢）；
     *  - 单机模式 → 本地引擎自己采样，逻辑与改造前一致。
     */
    private fun calibrate(step: Int) {
        if (hosted) {
            io.execute {
                val r = runCatching { ModuleLink.requestCalibration(this, step) }
                    .getOrElse { ModuleLink.CalibStatus.UNAVAILABLE }
                runOnUiThread {
                    toast(calibMessage(r))
                    refreshHostState()
                }
            }
            return
        }
        val e = engine ?: return
        e.captureCalibrationSample { roll ->
            when {
                roll == null -> toast("没采到人脸，请对准脸再试")
                step == ModuleLink.CALIB_BASELINE -> e.applyCalibrationBaseline(roll)
                !e.applyCalibrationAxis(roll) -> toast("两点角度差不对，请确认已把手机转了 90°")
                else -> toast("已校准")
            }
        }
    }

    private fun calibMessage(r: ModuleLink.CalibStatus): String = when (r) {
        ModuleLink.CalibStatus.OK -> "已校准"
        ModuleLink.CalibStatus.NO_FACE -> "没采到人脸，请对准脸再试"
        ModuleLink.CalibStatus.BAD_ANGLE -> "两点角度差不对，请确认已把手机转了 90°"
        ModuleLink.CalibStatus.TIMEOUT -> "等待系统界面响应超时，请重试"
        ModuleLink.CalibStatus.UNAVAILABLE -> "系统界面里的引擎当前不可用"
    }

    // ================================================================ 杂项

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 授权页只对**还需要写系统设置**的场景有意义（托管模式下写的是跨进程镜像） */
    private fun requestWriteSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /**
     * 【**后台执行 su，绝不上主线程**】申请 / 复探 root。
     *
     * ★ 探针本身就是申请入口：首次跑 `su -c id` 会让 root 管理器弹框，用户点「允许」即可。
     *   点过一次之后系统会记住，所以 [probeRoot] 这条**静默**路径只在之前成功过才真跑，
     *   不会每次开 App 都弹框。
     *
     * 拿到 root 后立刻回填一次镜像：用户点这个按钮的动机就是"配置同步不过去"，
     * 只提示"已授权"却让他再手动切一次开关，等于没解决问题。
     */
    private fun requestRoot() {
        io.execute {
            val msg = runCatching { RootBridge.probe(this, silent = false) }
                .getOrElse { "❌ ${it.javaClass.simpleName}: ${it.message}" }
            applyRootResult(msg)
        }
    }

    /** 启动时的静默复探：历史上授权过才跑，避免平白弹授权框 */
    private fun probeRoot() {
        io.execute {
            val msg = runCatching { RootBridge.probe(this, silent = true) }.getOrNull()
            if (msg != null) applyRootResult(msg)
        }
    }

    private fun applyRootResult(msg: String?) {
        val ok = RootBridge.available()
        if (ok) AppPrefs.seedMirrorAsync()
        runOnUiThread {
            rootReady = ok
            msg?.let { toast(it) }
            if (hosted) refreshHostState()
        }
        if (ok) {
            // 回填是异步的，稍等一下再取回读结论（界面每 2s 也会再刷一次）
            runCatching { Thread.sleep(900) }
            runOnUiThread { mirrorProblem = AppPrefs.mirrorProblem.value }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this as Context, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /**
         * 两次探活之间的最小间隔。
         *
         * 取 30s 的理由：`onResume` 是唯一会反复触发的入口，而用户"切出去看一眼再切回来"
         * 通常在几秒内完成 —— 那段时间里模块的启用状态不可能变。真正需要立刻重探的是
         * **冷启动**（`lastProbeAtMs == 0`，首次调用必然通过）。
         */
        const val PROBE_MIN_INTERVAL_MS = 30_000L
    }
}

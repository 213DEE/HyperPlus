package cn.dsr213.hyperplus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.dsr213.hyperplus.ui.FaceRotateTheme
import cn.dsr213.hyperplus.ui.HyperPlusApp
import java.util.concurrent.Executors

/**
 * 薄壳 Activity：**配置界面 + 状态显示**。
 *
 * ============================ 它现在不再跑引擎了（2026-09-28 改造） ============================
 * 用户拍板：「App 一个引擎、SystemUI 一个引擎，用起来非常割裂，只保留 SystemUI 的引擎就行」。
 *
 * 于是本类里**不存在任何引擎实例**，职责缩成三件：
 *   ① 编辑配置 —— 写 App 自己的 prefs，引擎通过 LSPosed 的配置通道实时跟随；
 *   ② 显示引擎回传的状态（读 `Settings.System`，零权限）；
 *   ③ 发标定请求（写 prefs，零权限）。
 *
 * ★ 顺手了结的两个历史问题：
 *   - **「双引擎抢相机」**：两个引擎曾经同时活着，抢前摄 + 抢写 `user_rotation`，
 *     症状就是用户报的「越转越反 / 转了没反应」。现在 App 侧压根没有引擎，
 *     这个问题从根上不存在 —— 也不需要那个 5 秒对账的看门狗了；
 *   - **「配置同步要 root」**：旧通道要 App 往非公开的 `Settings.System` 键里写，
 *     普通应用写不了（判据见 [PrefsBridge] 类注释），只能借 root 代写。
 *     现在**配置**只写自己的文件，这条依赖消失。
 *     ⚠️ 但别把这句话扩大成"全工程不再需要 root"（2026-10-03 更正）：
 *        「默认方向」那一项改走 App 借 root 直写内屏槽位（见 [RootShell]），仍然要 root。
 *
 * ⚠️ 代价（用户明确接受）：**模块没在 LSPosed 里启用 = 引擎不存在 = 功能不可用**，
 *   本界面只剩"看一眼状态"的价值。这也正是"单引擎"的必然含义。
 */
class MainActivity : ComponentActivity() {

    /** 引擎是否在线（= 心跳够新鲜）。语义由「谁在跑」变成了单纯的「引擎活着吗」 */
    private var hosted by mutableStateOf(false)

    /** 引擎回传的状态 */
    private var hostState by mutableStateOf<ModuleLink.State?>(null)

    /** 状态是否已至少读过一次（界面据此决定显示"还没读到"还是真状态） */
    private var probed by mutableStateOf(false)

    /** 发标定请求用：内部要阻塞轮询等结果，绝不能占主线程 */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hyperplus-calib") }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 界面开着时的状态刷新。
     *
     * ★ 从前这里挂的是「运行位置看门狗」，负责把"谁该跑引擎"在两个宿主之间拉回一致。
     *   现在只有一个宿主，对账没了对象 —— 它退化成单纯的"把状态读新鲜"。
     *   周期取 2 秒：心跳本身是 5 秒一跳，2 秒的刷新粒度能让"最后活跃 N 秒前"这个
     *   数字看起来是连续的，又不会给系统设置查询添负担。
     */
    private val stateTick = object : Runnable {
        override fun run() {
            refreshHostState()
            mainHandler.postDelayed(this, STATE_TICK_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // ★ 界面语言的套用点，**必须是这里**（比 `onCreate`、比任何 Compose 都早）：
        //   一旦 Activity 的 base context 建好，`stringResource` / `getString` 就已经
        //   按那个 Locale 取资源了，再往后套只能等下一条生命周期。
        //   ⚠️ `read(newBase)` 用 `newBase` 而不是 `this`：`attachBaseContext` 时
        //     本对象的 base 还没装好，`this` 上的 `getSharedPreferences` 会走空。
        //   ⚠️ 这里**只读、不写**用户的选择（写由 `LanguagePage` 负责）。
        super.attachBaseContext(AppLocale.wrap(newBase, AppLocale.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ★ 必须在 `setContent` 之前：语言页要显示"当前选中哪一档"，
        //   它读的是 `AppLocale.lang` 这个 StateFlow，而初值只是 SYSTEM（跟随系统）。
        //   ⚠️ 不放在 `attachBaseContext` 里是因为那里还不该碰 StateFlow 的初始化时机。
        AppLocale.init(this)
        AppPrefs.init(this)
        refreshHostState()

        setContent {
            FaceRotateTheme {
                HyperPlusApp(
                    appVersion = BuildConfig.VERSION_NAME,
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                    onCalibrate = { step -> calibrate(step) },
                    onOpenSettings = { openSystemSettings() },
                    // ★ 语言改了要**重建**才能生效（语言装在 base context 上，
                    //   而那个时刻一个 Activity 只有一次 —— 见 LanguagePage 类注释）。
                    //   ⚠️ 由这里传下去、而不是让页面自己去 `LocalContext` 里找 Activity：
                    //     从 Compose 的 Context 往上找要穿 ContextWrapper，被谁包一层就静默失效。
                    onRecreate = { recreate() },
                    // 返回栈见底时再按返回 ⇒ 结束界面（交给系统回桌面）
                    onExit = { finish() },
                )
            }
        }
    }

    // ================================================================ 状态读取（只读）

    /**
     * 纯读一眼引擎留下的状态 —— 两次 `Settings` 读 + 解析，可在主线程调用。
     *
     * ★ 这里**不再有任何"探活"**：旧实现要 App 往 `bus_ping` 写一个自增 nonce 等回执，
     *   而写非公开键正是 root 依赖的来源；回执超时还会误判（SystemUI 重启的那几秒里探活
     *   就会判成"模块没在"，进而错误地拉起本地引擎 —— 这正是"越转越反"的起点）。
     *   心跳是引擎**单方面**每 5 秒写的，我们只读不写，既零权限也没有超时窗口。
     */
    private fun refreshHostState() {
        val st = ModuleLink.currentState(this)
        hostState = st
        hosted = st?.hostAlive == true
        probed = true
        // 标定值住在 Settings（引擎的账），顺便刷一次，让界面跟上引擎的自动修正
        AppPrefs.refreshCalibFromSettings()
    }

    // ================================================================ 标定（一律交给引擎）

    /**
     * 标定**只有一个去向**：请 SystemUI 里的引擎采样。
     *
     * ★ 为什么 App 不能自己标：采样要真的开相机，而相机在引擎手里（SystemUI 进程）。
     *   改造前"单机模式"下 App 会自己开相机 —— 那条路连同 App 侧引擎一起删掉了。
     */
    private fun calibrate(step: Int) {
        io.execute {
            val r = runCatching { ModuleLink.requestCalibration(this, step) }
                .getOrElse { ModuleLink.CalibStatus.UNAVAILABLE }
            // ★ 记一笔分步进度（2026-10-03）：界面靠它显示"①/② 做完没"。
            //   ⚠️ 判据是**引擎的回报**，不是"用户点了按钮" —— 点了但没采到脸
            //     （NO_FACE / BAD_ANGLE）不算完成。见 `AppPrefs.calibStep1Done`。
            //   ⚠️ 必须在 IO 线程（它会写 prefs），所以放在 runOnUiThread 之前。
            AppPrefs.recordCalibStep(step, r == ModuleLink.CalibStatus.OK)
            runOnUiThread {
                toast(calibMessage(r))
                refreshHostState()
            }
        }
    }

    /**
     * 标定结果的提示文案。
     *
     * ⚠️ 归到 string 资源之后**要注意调用线程**：它现在在 `io` 线程里被调用
     *   （见 [calibrate]），而 `getString` 是线程安全的，没问题；
     *   但**不能**在这里用 `stringResource`（那是 Composable 专用）。
     */
    private fun calibMessage(r: ModuleLink.CalibStatus): String = getString(
        when (r) {
            ModuleLink.CalibStatus.OK -> R.string.calib_ok
            ModuleLink.CalibStatus.NO_FACE -> R.string.calib_no_face
            ModuleLink.CalibStatus.BAD_ANGLE -> R.string.calib_bad_angle
            ModuleLink.CalibStatus.TIMEOUT -> R.string.calib_timeout
            ModuleLink.CalibStatus.UNAVAILABLE -> R.string.calib_unavailable
        },
    )

    // ================================================================ 生命周期

    override fun onResume() {
        super.onResume()
        refreshHostState()
        mainHandler.removeCallbacks(stateTick)
        mainHandler.postDelayed(stateTick, STATE_TICK_MS)
    }

    override fun onPause() {
        // 界面不在前台就不必刷新了（引擎的运行完全不受这里影响）
        mainHandler.removeCallbacks(stateTick)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(stateTick)
        io.shutdownNow()
        super.onDestroy()
    }

    // ================================================================ 杂项

    /**
     * 打开系统设置首页 —— 给「注视感知」那条提醒用。
     *
     * ★ 只跳到设置首页，**不猜 MIUI 具体页面的 Activity 名**：不同 HyperOS 版本里
     *   这个开关的位置不一样（「锁屏 → 注视感知」或「更多设置 → 主动视觉」），
     *   写死一个 Activity 名在别的版本上会直接崩，得不偿失。文案里已经写清路径。
     */
    private fun openSystemSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { toast(getString(R.string.open_settings_failed, it.javaClass.simpleName)) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this as Context, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 界面开着时的状态刷新周期（见 [stateTick]） */
        const val STATE_TICK_MS = 2_000L
    }
}

package cn.dsr213.hyperplus.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cn.dsr213.hyperplus.AdaptiveEngine
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.BOOT_BREAKER_THRESHOLD
import cn.dsr213.hyperplus.BOOT_HEALTHY_WINDOW_MS
import cn.dsr213.hyperplus.EngineErrors
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.bootBreakerTripped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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

    /**
     * 「等用户解锁」时的轮询周期（见 [scheduleBootAfterUnlock]）。
     *
     * ★ 为什么广播之外还要一条轮询：解锁广播是首选通道，但它依赖 `registerReceiver`
     *   在 13/14+ 上的 flag 语义（对非系统广播强制要求 `EXPORTED`/`NOT_EXPORTED`）。
     *   万一广播没来，引擎就**永远起不来**（自适应旋转静默失效）—— 这个代价太大，
     *   所以再加一条最朴素的兜底：只要 `isUserUnlocked` 变真就补启动。
     * ⚠️ 不会变成"每 3 秒醒一次"：进程没有 wakelock，屏幕关着时 `postDelayed`
     *   的消息本来就会被推迟 ⇒ 锁屏整夜的额外开销可以忽略。
     */
    private const val UNLOCK_POLL_MS = 3_000L

    @Volatile private var engine: AdaptiveEngine? = null
    @Volatile private var starting = false

    /** 有一次致命异常就置位，之后不再尝试启动（用户拍板：一次异常就停） */
    @Volatile private var dead = false

    @Volatile private var bootThread: HandlerThread? = null

    /**
     * 「正在等用户解锁」的标记 —— 见 [scheduleBootAfterUnlock]。
     * ★ 同时当**幂等闸**用：同一进程里只登记一次（广播 ＋ 轮询两条路都靠它收敛）。
     */
    @Volatile private var waitingUnlock = false

    // ---------------------------------------------------------------- 熔断相关（2026-10-03）

    /**
     * 熔断态下"重新启用"要用到的启动参数。
     *
     * ★ 为什么存下来：熔断分支是在 [start] 的入口返回的，而"用户点了重新启用"
     *   可能在几分钟之后才来 —— 那时候只能靠这里留着的一份参数把 [start] 再喊一次。
     * ⚠️ 只存引用，不额外持有任何资源；SystemUI 进程本身就是常驻的。
     */
    @Volatile private var resumeHostCtx: Context? = null
    @Volatile private var resumeAppCtx: Context? = null
    @Volatile private var resumeClassLoader: ClassLoader? = null

    /** [installBreakerResumeWatch] 的幂等闸（同一进程里只装一次） */
    @Volatile private var breakerWatchInstalled = false

    /**
     * 本引擎的启动时刻（`elapsedRealtime`，毫秒）。
     *
     * ★ 不能直接用 `SystemClock.elapsedRealtime()` 当"已运行"上报 —— 那是**开机**以来的时间，
     *   界面会显示成"宿主已运行 116131 秒"（≈32 小时，其实是整机 uptime），是假数据。
     */
    @Volatile private var startedAtMs = 0L

    /**
     * 上次处理过的标定请求值（`"<时间戳>|<步骤>"`）。
     *
     * ★ 存的是**原始字符串**而不是步骤号：配置通道的通知不带键名，
     *   只能靠"值变了"判定有新请求。用步骤号比较的话，用户连着点两次同一个按钮
     *   （值不同、步骤相同）就会漏掉第二次。
     */
    @Volatile private var lastCalibReq: String? = null

    val isDead: Boolean get() = dead

    // ================================================================ 启动

    /**
     * 【**worker 线程**调用】准备宿主环境并把引擎挂上去。
     *
     * 顺序**不可调换**（理由见 [HostEnv] 的类注释）：
     *   ① 预加载 native so → ② 初始化 MlKitContext → ③ 配置后端切到镜像
     *   → ④（主线程）造 lifecycle + 引擎 + start
     *
     * ★★ 2026-10-03 起，进门前先过**启动熔断闸**（见 [BOOT_BREAKER_THRESHOLD]）：
     *   连续失败到阈值就不再启动，只上报 `phase=halted` 并等用户点「重新启用」。
     *   闸放在**置 [starting] 之前** —— 否则熔断分支返回时 `starting` 会永远停在 true，
     *   后面那条恢复路径就再也进不来了（这个是坑，别动顺序）。
     *
     * ★★ 2026-10-07 起，**解锁闸在最前面**：[HostEnv.userUnlocked] 为假就整段不启动，
     *   改挂解锁广播 ＋ 轮询兜底（[scheduleBootAfterUnlock]）。原因不是"锁屏用不上"，
     *   而是**会崩**：解锁前初始化 ML Kit ⇒ 它的后台线程读 CE 存储抛异常 ⇒ SystemUI 死
     *   （实证见 [HostEnv.userUnlocked]）。⚠️ 顺序：解锁闸 → 熔断闸 → 置 [starting]，
     *   三道**都不能挪**（挪了要么漏崩、要么把熔断阈值吃满、要么恢复路径进不来）。
     */
    fun start(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        if (starting || engine != null || dead) {
            Log.i(TAG, "引擎已启动 / 正在启动 / 已停用，跳过（starting=$starting engine=${engine != null} dead=$dead）")
            return
        }

        // ★★★ 2026-10-07：**解锁闸**。必须排在熔断闸**之前**——
        //   「我们主动没启动」和「启动失败」是两件事，前者**不该**计入 `BOOT_ATTEMPTS`
        //   （否则锁屏过夜会把熔断阈值吃满，等用户解锁时反而被熔断挡住）。
        //   为什么解锁前绝不能碰 ML Kit：见 [HostEnv.userUnlocked] 的长注释（有完整崩溃栈）。
        if (!HostEnv.userUnlocked(hostCtx)) {
            Log.i(TAG, "🔒 用户尚未解锁 ⇒ 本次不启动引擎（解锁前初始化 ML Kit 会崩掉系统界面），改为解锁后自动补启动")
            BootReport.note("🔒 用户尚未解锁 ⇒ 引擎推迟到解锁后启动")
            scheduleBootAfterUnlock(hostCtx, appCtx, classLoader)
            return
        }

        // 留一份启动参数：熔断期间用户点「重新启用」时要靠它把本方法再喊一次。
        resumeHostCtx = hostCtx
        resumeAppCtx = appCtx
        resumeClassLoader = classLoader

        // ★★★ 熔断闸。它读的是**落盘**的计数（不是内存里的 [dead]）——
        //   故障是确定性的那种情况下，内存标志会随进程一起复位，等于没有这道闸。
        val attempts = PrefsBridge.readInt(hostCtx.contentResolver, PrefsBridge.BOOT_ATTEMPTS, 0)
        if (bootBreakerTripped(attempts)) {
            Log.w(
                TAG,
                "⛔ 启动熔断已生效（连续失败 $attempts 次 ≥ $BOOT_BREAKER_THRESHOLD）" +
                    "—— 本次不再启动引擎，以免系统界面继续反复重启",
            )
            publishHalted(hostCtx, attempts)
            installBreakerResumeWatch()
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

    // ---------------------------------------------------------------- 解锁后补启动（2026-10-07）

    /**
     * 锁屏时的出路：登记「解锁广播」＋ 一条「轮询兜底」，**解锁后自动把 [start] 再喊一次**。
     *
     * ★ 为什么参数要存着：真正的补启动可能发生在几十分钟之后（用户才解锁），
     *   到那时入口参数只剩这一份引用。⛔ 不额外持有任何资源 —— 宿主进程本身就是常驻的。
     * ★ 两条通道都在置回 [waitingUnlock] **之后**才调 [start]，且 [start] 自己开头就有
     *   `starting || engine != null` 闸 ⇒ 广播与轮询即使同时到，也只会真启动一次。
     */
    private fun scheduleBootAfterUnlock(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        if (waitingUnlock) {
            Log.i(TAG, "🔒 已经在等解锁了，跳过重复登记")
            return
        }
        waitingUnlock = true

        // ① 首选：解锁广播（解锁那一刻就到）
        runCatching {
            hostCtx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        Log.i(TAG, "🔓 收到 ACTION_USER_UNLOCKED ⇒ 补启动引擎")
                        waitingUnlock = false
                        start(hostCtx, appCtx, classLoader)
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
            )
            Log.i(TAG, "已登记解锁广播（解锁后自动补启动引擎）")
        }.onFailure { Log.w(TAG, "登记解锁广播失败 ⇒ 只剩轮询兜底", it) }

        // ② 兜底：轮询（不能把「引擎还能不能起来」押在广播的 flag 语义上）
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!waitingUnlock) return
                if (!HostEnv.userUnlocked(hostCtx)) {
                    h.postDelayed(this, UNLOCK_POLL_MS)
                    return
                }
                Log.i(TAG, "🔓 轮询发现已解锁 ⇒ 补启动引擎")
                waitingUnlock = false
                start(hostCtx, appCtx, classLoader)
            }
        }
        h.postDelayed(tick, UNLOCK_POLL_MS)
    }

    private fun bootOn(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        // ★★ 熔断记账（2026-10-03）：走到这里说明**这次真的要动手了** ⇒ 先 +1。
        //   清零由 [installLivenessTick] 里的"健康窗口"负责 —— 两份代码合起来表达
        //   "这次启动最终活下来了吗"，且**跨进程存活**（写 Settings，不写内存）。
        val cr = hostCtx.contentResolver
        runCatching {
            val before = PrefsBridge.readInt(cr, PrefsBridge.BOOT_ATTEMPTS, 0)
            PrefsBridge.writeInt(cr, PrefsBridge.BOOT_ATTEMPTS, before + 1)
            Log.i(
                TAG,
                "启动计数 ${before + 1}（健康运行 ${BOOT_HEALTHY_WINDOW_MS / 1000} 秒后清零）",
            )
        }.onFailure { Log.w(TAG, "启动计数写失败（不影响本次启动）", it) }

        // ① + ② ML Kit 的宿主环境（必须在任何 ML Kit 类被触碰之前）
        val notes = runCatching { HostEnv.prepare(hostCtx, appCtx, classLoader) }
            .getOrElse { listOf("❌ 宿主环境准备异常：${it.javaClass.simpleName}: ${it.message}") }
        notes.forEach { Log.i(TAG, "  [env] $it") }

        // ★★ ③ 配置**广播通道**：注册接收者 + 把 `Settings` 里的镜像读回来 + 必要时反向问 App 要一份。
        //
        //   ⚠️⚠️ 顺序**必须**在下面的 `AppPrefs.initHost` **之前**。
        //     迁移前是反的，那时候还有 nsp 文件通道当底，反着写只是"第一次读到旧的"；
        //     现在通道**只剩这一条** ⇒ 先 initHost 的话，它读配置的那一刻镜像必然是空的，
        //     引擎就此**按默认值起跑**，而下游没有任何一环会"等镜像到了再补读一次"
        //     （`applyFromModulePrefs` 只在 initHost 里被调一次，之后要等一条新推送）。
        //   ⚠️ 也必须在 `installConfigWatcher` **之前** —— 订阅者是在那儿挂上的，
        //     先有数据源再挂订阅者，免得刚起来那一条推送白丢（用户改了没生效最难查）。
        runCatching { ModulePrefs.attachTransport(hostCtx) }
            .onFailure { Log.w(TAG, "装配配置广播通道失败（配置下发会一直停在默认值）", it) }

        // ③.5 配置后端 = 跨进程镜像（读 App 改的值，实时跟随）。**必须在上一行之后**。
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
                installConfigWatcher(hostCtx)
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
     *      这里直接把 `accelerometer_rotation` 写回 [AppPrefs.restoreTarget] 记的原值
     *      并清掉接管标志，避免用户遇到"屏幕转不动"；
     *      ⚠️ 若那个值是哨兵 [AppPrefs.AUTO_ROTATE_UNTOUCHED]（= 我们**从没改过** accel），
     *         **一个字节都不写** —— 用户本来就锁着的设置必须原样留着。
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
                // ★★ 三道闸（2026-09-30，与 `releaseTakeover` / `recoverOrphanTakeover`
                //   同一套判据，改一处必须改三处）：
                //   ① `target != 哨兵` —— **我们从没改过 accel** 时一律不写
                //      （用户本来就锁着，那个 0 是他的选择，不是我们的残留）。
                //   ② **只有"还是我们留下的 0"才动** —— 用户在这期间自己把自动旋转开回来了
                //      （`cur == 1`）就不该被我们覆盖掉。旧版无条件写，等于篡改用户的选择。
                //   ③ 还原成 **`restoreTarget()`（接管前原值）**，不是常量 1 —— 否则
                //      "接管前锁着"的用户会被我们强行解开锁定。
                val target = AppPrefs.restoreTarget()
                val cur = Settings.System.getInt(
                    hostCtx.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1,
                )
                if (cur == 0 && target != AppPrefs.AUTO_ROTATE_UNTOUCHED) {
                    Settings.System.putInt(
                        hostCtx.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        target,
                    )
                }
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

    // ================================================================ 配置通道：远程标定
    //
    // ★ 探活（ping/pong）在 2026-09-28 的改造里**被整个删掉**了。
    //   它唯一的用途是让 App 判断"模块在不在"，从而决定自己要不要起本地引擎；
    //   而 App 侧引擎已经不存在（用户拍板"只保留 SystemUI 的引擎"），
    //   这个判断再也没有下游，留着只是多一份要维护的协议。
    //   "引擎活着吗"改由 STATE 的 `phase=` 与 HEARTBEAT 回答 —— 更直接，也更难说谎。

    /**
     * 装上「标定请求」通道：App 写自己的 prefs，这里通过 [ModulePrefs] 的文件监控收到通知。
     *
     * ★ 为什么改走 prefs 而不是 `Settings.System`：后者要求 **App 写**非公开键，
     *   而普通应用写不了（判据见 [cn.dsr213.hyperplus.PrefsBridge] 的类注释）⇒
     *   会被迫引入 root。换到 prefs 之后 App 只写自己的文件，零权限、零 root。
     */
    private fun installConfigWatcher(hostCtx: Context) {
        ModulePrefs.subscribe {
            runCatching { onConfigChanged(hostCtx) }
                .onFailure { Log.w(TAG, "配置变更处理失败（已吞掉）", it) }
        }
        // ★ 启动时把当前值记为"已处理"，**不**执行它。
        //   理由：文件是持久化的，里面很可能躺着几天前那次标定请求。
        //   若在这里补跑一次，就会在引擎启动时莫名其妙地开一次相机 —— 副作用远大于收益。
        //   代价只是"App 在引擎启动前点的按钮会被忽略"，用户再点一次即可。
        runCatching { lastCalibReq = ModulePrefs.getString(cn.dsr213.hyperplus.PrefsBridge.CALIB_REQ, null) }
        Log.i(TAG, "配置通道已接入：标定请求键 ${cn.dsr213.hyperplus.PrefsBridge.CALIB_REQ}")
    }

    /**
     * 配置文件有变化 → 检查是不是新来的标定请求。
     *
     * ★ 配置项本身（模式 / 策略 / 两个开关）的刷新由 `AppPrefs.initHost` 注册的
     *   那个订阅负责，这里只管"标定请求"这一件事 —— 两个订阅互不干扰。
     *
     * ⚠️ 回调跑在配置通道**自建的推送线程**上（`ModulePrefs` 给接收者挂的调度器，
     *   **不是** SystemUI 主线程）。2026-10-03 迁移前这里写的是「框架的文件监控线程」——
     *   那条腿随 nsp 一起删了，回调来源已经换成我们自己的广播接收者。
     *   标定要开相机会阻塞，所以再丢到 [bootThread] 去做。
     */
    private fun onConfigChanged(hostCtx: Context) {
        val raw = ModulePrefs.getString(cn.dsr213.hyperplus.PrefsBridge.CALIB_REQ, null) ?: return
        if (raw == lastCalibReq) return
        lastCalibReq = raw
        // 值形如 "<时间戳>|<步骤>"。时间戳要**原样带回结果** —— 它是这次请求的唯一标识，
        // 让 App 能区分"我这次的结果"和"上一次同样是 step=1 的旧结果"。
        val token = raw.substringBefore('|')
        val step = raw.substringAfter('|', "").toIntOrNull() ?: return
        Handler(bootThread?.looper ?: Looper.getMainLooper()).post {
            runCatching { doCalibrationRequest(hostCtx, token, step) }
                .onFailure { Log.w(TAG, "标定请求处理失败", it) }
        }
    }

    /**
     * 处理一次标定请求（步骤见 `AppPrefs.CALIB_STEP_*`）：
     *  - `1` → 记竖屏基准（[AdaptiveEngine.applyCalibrationBaseline]）
     *  - `2` → 记左横屏、定方向（[AdaptiveEngine.applyCalibrationAxis]）
     *  - `3` → 清空标定（**不开相机**，纯清账）
     *
     * ★ 应用动作在**宿主侧**完成（相机与 ML Kit 都在宿主手里），App 那边没有帧可采。
     *   结果写回 `Settings.System`，App 读它 —— 读系统设置零门槛，两边都不需要 root。
     *
     * @param token 本次请求的唯一标识（App 生成的时间戳），原样带回结果里。
     *   ⚠️ 为什么需要它：结果里只有 `step` 的话，用户**连点两次同一个按钮**
     *   （比如连点两次"记竖屏基准"）时，App 会读到上一次留下的同 step 结果，
     *   于是"还没采样就说已完成"。带上 token 才能严格配对。
     */
    private fun doCalibrationRequest(hostCtx: Context, token: String, step: Int) {
        val eng = engine
        if (eng == null) {
            publishCalibResult(hostCtx, token, step, if (dead) "stopped" else "starting")
            return
        }
        if (step == AppPrefs.CALIB_STEP_CLEAR) {
            runCatching { eng.resetCalibration() }
            publishCalibResult(hostCtx, token, step, "ok")
            return
        }
        eng.captureCalibrationSample { roll ->
            val status = when {
                roll == null || !roll.isFinite() -> "noface"
                step == AppPrefs.CALIB_STEP_BASELINE -> {
                    eng.applyCalibrationBaseline(roll)
                    "ok"
                }
                else -> if (eng.applyCalibrationAxis(roll)) "ok" else "badangle"
            }
            publishCalibResult(hostCtx, token, step, status)
        }
    }

    /** 结果格式：`"<token>|<step>|<status>"`（跨进程契约，App 侧 `ModuleLink` 按它解析） */
    private fun publishCalibResult(hostCtx: Context, token: String, step: Int, status: String) {
        val line = "$token|$step|$status"
        Handler(bootThread?.looper ?: Looper.getMainLooper()).post {
            runCatching {
                PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.CALIB_RESULT, line)
                Log.i(TAG, "标定结果 → $line")
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
            // ★★ 2026-10-03（迁移后补的一格）：来源里**必须**再带上配置通道那本账。
            //   状态串的 `cfgold=` / `cfgmsg=` 取自 `AppPrefs.configOk` / `configDiag`，
            //   而那两个**不在** `eng.ui` 里。只跟 `ui` 走的话有一条路径会**永久滞后**：
            //     引擎启动时手上还没有配置（cfgold=0 已上报）→ App 打开并推来一份
            //     → 若用户**从没改过任何设置**，配置值与默认值逐项相同，
            //       `_ui` 的各个 StateFlow 对相同值不重发 ⇒ `ui` 不变 ⇒ 摘要不重算
            //     ⇒ 界面一直挂着「设置暂时不会生效」这条**假警报**
            //       （而那正是本次迁移要消灭的东西：假警报比没警报更坏）。
            //   `combine` 在任一来源变化时重算摘要，`distinctUntilChanged` 再把重复挡掉，
            //   所以这不会让上报变频繁。
            combine(eng.ui, AppPrefs.configOk, AppPrefs.configDiag) { ui, _, _ -> ui }
                .map { summary(it) }.distinctUntilChanged().collect { line ->
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

        // ★★★ 健康窗口（2026-10-03）：连续活过这么久 ⇒ 把**启动熔断计数清零**。
        //
        //   它与 `bootOn` 里那句 +1 是**一对**，合起来回答一个问题：
        //   "这次启动最终活下来了吗"。判据刻意做成"**代码跑到这里才算活下来**"——
        //   进程在窗口内死掉的话，这段协程根本不会被执行到，
        //   计数留在盘上，下一次启动接着累加 ⇒ 确定性的坏启动会在阈值处被拦下。
        //
        //   ⚠️ 本方法**只在启动成功那条分支**被调用（见 [bootOn]），
        //     所以"没起来"的路径天然不会清零，不需要额外判断。
        //   ⚠️ `dead` 要判：引擎在窗口内被 [panic] 停掉时不该下调计数 ——
        //     那也是一次"没活下来"。
        scope.launch {
            delay(BOOT_HEALTHY_WINDOW_MS)
            if (dead) {
                Log.i(TAG, "健康窗口到点，但引擎已被停用 ⇒ 保留启动计数（不下调）")
                return@launch
            }
            val ok = PrefsBridge.writeInt(cr, PrefsBridge.BOOT_ATTEMPTS, 0)
            Log.i(TAG, "引擎健康运行 ${BOOT_HEALTHY_WINDOW_MS / 1000} 秒 ⇒ 启动计数清零（$ok）")
        }
    }

    private fun publishPhase(hostCtx: Context, phase: String) {
        runCatching {
            PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.STATE, "v1|phase=$phase")
        }
    }

    // ================================================================ 熔断（2026-10-03）

    /**
     * 熔断态上报：`v1|phase=halted|attempts=N`。
     *
     * ★ 复用**既有**的状态串形状，所以跨进程契约不用改版本号：
     *   App 侧 [cn.dsr213.hyperplus.ModuleLink.parse] 把 `phase` 当字符串读、
     *   认不出来的取值**原样显示**，不认识的新字段直接忽略。
     *   `attempts` 是**只追加**的字段（App 读它来显示"连续失败几次"）。
     *
     * ⚠️ 熔断态**没有心跳**（心跳循环根本没启动）⇒ App 侧会把它判成"离线"。
     *   这正是为什么 App 那边要专门认 `phase=halted`：否则用户只会看到
     *   「旋转服务已离线」这种没有可操作性的说法，而真正该做的是点「重新启用」。
     */
    private fun publishHalted(hostCtx: Context, attempts: Int) {
        runCatching {
            PrefsBridge.writeString(
                hostCtx.contentResolver,
                PrefsBridge.STATE,
                "v1|phase=halted|attempts=$attempts",
            )
        }.onFailure { Log.w(TAG, "熔断态上报失败", it) }
    }

    /**
     * 熔断态的**唯一出口**：等用户在 App 里点「重新启用」。
     *
     * 链路：App 往自己的 prefs 写一个新时间戳（[PrefsBridge.BREAKER_RESET]）
     * → 这里读到"值变了" → 清掉计数 → 把 [start] 再喊一次。
     *
     * ★ 为什么熔断态还允许跑这一小段：它是本模块里**最轻**的一环 ——
     *   读一个小文件 + 2 秒比对一次内容。**不碰** native 库、**不开**相机、
     *   **不写** `Settings`（清零那次除外）。与真正会崩的启动序列（`HostEnv.prepare` /
     *   CameraX / ML Kit）完全没有交集，所以它不会把我们刚停下来又拽回故障里。
     *
     * ⚠️ 配置通道不可用时要**如实报出来**（`diag`）—— 那种情况下这条路是死的，
     *   用户只能走 App 里那个借 root 写键的兜底（见 [PrefsBridge.BOOT_ATTEMPTS] 的注释）。
     */
    private fun installBreakerResumeWatch() {
        if (breakerWatchInstalled) return
        breakerWatchInstalled = true

        // ★ 必须先自己把配置文件读起来：正常路径上这一步由 AppPrefs.initHost 做，
        //   而熔断分支**没有**走那条路（它刻意什么都不初始化）。
        // ★★ 2026-10-03 追加：还要**把广播通道也装上** —— 「重新启用」这条命令现在正是从
        //   那条通道来的，不装就等于在熔断态把自己锁死（只剩 root / adb 两个兜底）。
        resumeHostCtx?.let { h ->
            runCatching { ModulePrefs.attachTransport(h) }
                .onFailure { Log.w(TAG, "熔断态：装配配置广播通道失败", it) }
        }
        // ★ 2026-10-03 迁移后 `open()` 只**报告状态**、不再返回读取器（nsp 没了）⇒
        //   判据换成"接收者装上了没有"（[ModulePrefs.transportReady]），**不是**"手上有没有配置"。
        //   理由：熔断就发生在启动早期，那时镜像几乎必然是空的（还没从 App 那侧拿到过），
        //   拿 `available` 判会把"能收命令"误判成"收不到"，当场把自己锁死 ——
        //   而这条通道正是熔断态**唯一的出口**。
        if (!ModulePrefs.transportReady) {
            Log.w(
                TAG,
                "熔断态：配置广播通道没装上（${ModulePrefs.diag}）" +
                    "⇒ 收不到 App 的「重新启用」，只能靠 App 借 root 清零 / adb 清键",
            )
            return
        }

        var last = runCatching { ModulePrefs.getString(PrefsBridge.BREAKER_RESET, null) }.getOrNull()
        ModulePrefs.subscribe {
            val raw = runCatching { ModulePrefs.getString(PrefsBridge.BREAKER_RESET, null) }
                .getOrNull() ?: return@subscribe
            if (raw == last) return@subscribe
            last = raw
            Log.i(TAG, "收到熔断复位请求（$raw）⇒ 清零启动计数并重新尝试启动")

            val h = resumeHostCtx ?: return@subscribe
            runCatching { PrefsBridge.writeInt(h.contentResolver, PrefsBridge.BOOT_ATTEMPTS, 0) }
                .onFailure { Log.w(TAG, "熔断计数清零失败", it) }
            // ⚠️ 这里**只是**把 start 再喊一次。它照样要过阈值闸（现在读到 0 了）——
            //   所以"用户点了重新启用"并不会绕过熔断机制，只是给了它一次新的机会。
            runCatching { start(h, resumeAppCtx, resumeClassLoader) }
                .onFailure { Log.w(TAG, "熔断复位后重试启动失败", it) }
        }
        Log.w(
            TAG,
            "熔断已生效：不再自动启动。等用户在 App 里点「重新启用」（监听键 ${PrefsBridge.BREAKER_RESET}）",
        )
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
        // 多帧投票（本轮票面 / 获胜方向 / 有效票数 / 是否已定论）—— 只追加，不改已有字段
        append("|votes=").append(s.voteCounts)
        append("|vwin=").append(s.voteWinner)
        append("|vvalid=").append(s.voteValid)
        append("|vconf=").append(if (s.voteConfident) 1 else 0)
        // 重力当尺子：符号位校验（见 [cn.dsr213.hyperplus.OrientationFusion]）——
        // 只追加，不改已有字段。`grav` = 重力参照扇区（-1 = 这一路当前不可用）；
        // `sgnS`/`sgnF` = "实测等于重力 / 等于镜像"的分辨帧数；`sgnOK` = 是否已被数据验证。
        // 摆在界面上是为了让"符号位到底有没有被证据钉死"一眼可见，不用再翻日志猜。
        append("|grav=").append(s.gravitySector)
        append("|sgnS=").append(s.signSame)
        append("|sgnF=").append(s.signFlip)
        append("|sgnOK=").append(if (s.signConfirmed) 1 else 0)
        append("|conflict=").append(s.conflictCount)
        // 当前在用的前摄 id（2026-09-25 新增，只追加）—— 引擎会在 5 个前摄间轮换，
        // 界面上必须能看到"现在到底在用哪一个"，否则轮换逻辑等于不可观测。
        append("|cid=").append(s.cameraId)
        // —— 前台门控（2026-09-28）：fg=是否停手 / fgr=读得到吗 / handoff=停手时是否交还自动旋转 ——
        //   ⚠️ 2026-09-29 起 `fgpkg` / `fgori` 是**最近一次巡检的读数**（进不进门都更新），
        //     不再是"触发停手的那个"。没停手时它们仍然有值 —— 这正是排查
        //     「判据为什么没命中」所必需的（用户报的「外屏抖音弹按钮但转不动」就靠它定位）。
        append("|fg=").append(if (s.foregroundGated) 1 else 0)
        append("|fgpkg=").append(s.foregroundPkg)
        append("|fgori=").append(s.foregroundOrientation)
        // 判据按哪块屏的名单算的（OUTER / INNER）
        // ⚠️ 2026-09-29 起"外屏专属豁免"两条已删 ⇒ 这格**只剩形态本身**的观测价值
        //    （外屏不再介入，但引擎仍如实报它量到的形态）。
        append("|fgform=").append(s.foregroundForm)
        // ★ 引擎**实测**到的最小宽度 dp（2026-09-29 加）。它与 `fgform` 必须**配套**读：
        //   本机内屏 608dp / 外屏 425dp、阈值 512dp，所以
        //   `fgform=INNER` + `swdp=608` = 正常；
        //   `fgform=OUTER` + `swdp=608` = **采纳值陈旧**（本次「内屏桌面不能转」那个 bug，
        //   成因是引擎只在启动时量一次形态）；
        //   `swdp=425` 而人确实在用内屏 = **测量本身**错（读到了另一块屏）。
        //   只看 `fgform` 是分不出后两种的。
        append("|swdp=").append(s.formProbeDp)
        // 停手是哪条豁免造成的（现在只有 WHITELIST；空 = 没停手）
        append("|fgstop=").append(s.foregroundStop)
        append("|fgr=").append(if (s.foregroundReadable) 1 else 0)
        append("|gate=").append(s.skipGateCount)
        append("|handoff=").append(if (s.handoffRotate) 1 else 0)
        // 前台门控**总开关**（2026-09-28 新增，只追加）：用户报"该转不转"时的逃生阀。
        // ⚠️ 字段名与上面的 `gate`（跳过次数）刻意区分成 `gateon`，别混。
        append("|gateon=").append(if (s.gateEnabled) 1 else 0)
        // —— 半自动模式（2026-09-28）：semi=弹出次数 / semitgt=目标方向 / semitap=点击生效次数
        //    ovl=悬浮窗是否可用（false ⇒ 半自动根本弹不出按钮，界面必须如实提示）——
        append("|semi=").append(s.semiShownCount)
        append("|semitgt=").append(s.lastSemiTarget)
        append("|semitap=").append(s.semiTappedCount)
        append("|ovl=").append(if (s.overlayUsable) 1 else 0)
        // ★ 屏幕上此刻**是否挂着**按钮（2026-09-29，用户点名要查"反复旋转会不会叠出好几个"）。
        //   ⚠️ 它只可能是 0 / 1 —— 出现任何"计数 > 1"的形态都意味着有窗口泄漏，
        //      所以这里刻意报**布尔**而不是个数：读的时候不会产生"2 是不是正常"的歧义。
        append("|hintAlive=").append(if (s.hintAlive) 1 else 0)
        // —— 本轮新增（2026-09-28，只追加）——
        //   `hint` = 引擎**实际读到**的按钮等待时长（毫秒）。
        //     ★ 它存在的唯一理由：回答"我在界面把滑条拖到 20 秒，引擎到底收到了吗"。
        //       在此之前这个链路只能靠"弹一次按钮看日志"来验证 —— 而弹按钮需要
        //       真的把手机转到位，代价高。直接读引擎进程自己的 AppPrefs，
        //       就能把「界面写的」「文件里的」「引擎读到的」三者一眼对完。
        //   `ovlt` = 生效的窗口类型（2017=状态栏子面板 层号181000 / 2038=普通悬浮窗 层号111000）。
        //     ★ 提层级**可能静默失败**（被 ROM 拒了就降级），而降级后的按钮看着一切正常、
        //       只是角上仍被状态栏挡 —— 那正是用户报的问题。有这一格才能一眼分辨。
        append("|hint=").append(AppPrefs.hintMs.value)
        append("|ovlt=").append(s.overlayType)
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
        // —— 配置通道（2026-09-28 单引擎改造后新增，只追加）——
        //   `cfgold` = 引擎进程有没有成功读到 App 的配置。它**是**新架构唯一的单点：
        //   读不到 ⇒ 界面上改什么都白改（引擎永远按默认值走），所以必须让界面能如实显示。
        //   `cfgmsg` = 失败原因。⚠️ 值里不能出现 `|`（那是本格式的分隔符），先替换掉。
        append("|cfgold=").append(if (AppPrefs.configOk.value) 1 else 0)
        append("|cfgmsg=").append(AppPrefs.configDiag.value.replace('|', '/'))
        // —— 白名单 + 换屏搬运（2026-09-28 判据换成应用白名单后新增，只追加）——
        //   `wlN` = 引擎手上**生效白名单**的条数（默认清单 ∪ 用户加的 − 用户关的 + 恒豁免的自己）。
        //     ★ 界面里勾的那份列表是"**已安装**应用"，与生效集合不是一回事（没装的包也在集合里，
        //       见 AppWhitelist）—— 出问题时要对的就是这一格。
        //   `pofs` = 本屏的**安装朝向偏移**（0 = 外屏那一类 / 2 = 内屏那一类）。
        //     ★ 它是"展开内屏倒置 180°"那个 bug 的判据：展开后它应当**从 0 变 2**。
        //       若一直显示 0，说明反射没读到 installOrientation（那时是按"不换算"在跑）。
        //       ⚠️ 别把它和 `sw`/`rot` 混起来看：它描述的是**硬件装的朝向**，不随转屏变。
        append("|wlN=").append(s.whitelistSize)
        append("|pofs=").append(s.panelOffset)
        // —— 结构化失败计数（2026-10-03 新增，只追加）——
        //   形如 `bind:3,rotw:1`；**一次都没出错 = 空串**。
        //   ★ 为什么要专门加这一格：引擎的失败路径**一律吞异常**（跑在 SystemUI 里，
        //     未捕获异常 = 状态栏崩），代价是"静默失败"根本查不出来 ——
        //     2026-10-03「默认方向不生效」那轮就是因为失败只留一句普通日志、
        //     几分钟被高频日志冲掉而无法定位。计数**跟着状态串走**，不会被冲掉。
        //   ⚠️ 这个字段会"变化才上报"里的"变化"多起来一点点（出错时它每次 +1），
        //     但 2 秒节流还在，量级与心跳同级，可以接受。
        //   ⛔ 别把它做成滑窗 / 速率 —— 要看的是"这次开机以来出过没有、几次"。
        append("|errs=").append(EngineErrors.snapshot())
    }

    /** 本引擎已运行秒数（上报那一刻的快照）；还没起来时返回 0（不拿整机 uptime 冒充） */
    private fun engineUptimeSec(): Int {
        val t = startedAtMs
        if (t <= 0L) return 0
        return ((SystemClock.elapsedRealtime() - t) / 1000).toInt()
    }
}

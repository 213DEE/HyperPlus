package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * 旋转策略两态。
 * ★ 用户已确认：只做两态，去掉「关闭」态（关闭 = 用户直接用系统自带那个开关）。
 */
enum class RotateMode {
    /** 自动旋转（系统）：本 App 完全不介入 */
    SYSTEM,

    /** 自适应旋转：按人脸方向决定屏幕方向 */
    ADAPTIVE;

    val label: String
        get() = when (this) {
            SYSTEM -> "自动旋转（系统）"
            ADAPTIVE -> "自适应旋转（人脸）"
        }
}

/**
 * 采集策略 —— 用户要求做成可选开关的那一项。
 *
 * 两者的本质差别是「相机什么时候 open」：
 *  - POWER_SAVING：只在触发后 open，采完立刻 close。省电，但每次触发都要付 open 代价
 *  - RESPONSIVE：相机常驻 open，触发后只处理新帧。延迟低，但相机管线本身耗电
 *
 * ★ 依据（MVP 实测）：相机管线本身约占 35% 单核，而检测负载（30fps 全检）约 57%。
 *   所以「相机常驻」的代价主要不在检测，而在 open 本身与管线占用。
 */
enum class CaptureStrategy {
    POWER_SAVING,
    RESPONSIVE;

    val label: String
        get() = when (this) {
            POWER_SAVING -> "省电优先"
            RESPONSIVE -> "响应优先"
        }

    val summary: String
        get() = when (this) {
            POWER_SAVING -> "用完即关相机，每次触发承担开机耗时"
            RESPONSIVE -> "相机常驻，响应更快但更耗电"
        }
}

/**
 * 全局偏好：两态模式 + 采集策略 + 方向标定 + 孤儿接管防护。
 *
 * ============================ 双后端（2026-09-25 新增） ============================
 * 同一份代码要跑在两个进程里，而配置只有一个真身。规则：
 *
 *  - **跨进程镜像（`Settings.System`，见 [PrefsBridge]）= 唯一真身。**
 *    两个进程都读它、都写它。
 *  - App 进程额外维护一份**本地 SP**（[localSp]），作用有二：
 *      ① root 不可用时的离线兜底（本进程自己读得到，界面不至于开天窗）；
 *      ② 首次升级 / 清数据后，把本地值**回填**进镜像（自愈，见 [init] / [seedMirrorAsync]）。
 *    它是镜像的**从属副本**，不是权威 —— 读的时候镜像优先。
 *  - 宿主进程（SystemUI）没有本地 SP，纯镜像 + `ContentObserver` 实时跟随。
 *    ★ 它的特权身份让它能**直写**镜像（见 [PrefsBridge] 的自动降级），App 侧则要借 root。
 *
 * ★ 为什么镜像优先而不是本地优先：标定值可能是**宿主侧**算出来的
 *   （远程标定时相机在 SystemUI 手里），那条路径只写镜像、写不到 App 的 SP。
 *   若启动时用本地 SP 去覆盖镜像，就会把刚标定好的值抹掉。实测踩过这个坑的思路，
 *   所以规则定死：**镜像里没有的键才允许用本地值回填。**
 *
 * ★ 为什么是 object 单例：QS Tile（TileService）与 Activity 同进程但生命周期独立 ——
 *   用户完全可能只点控制中心的开关、从不打开 App。
 *   单例 + StateFlow 保证两边读同一份内存状态，且互相能即时看到变化。
 */
object AppPrefs {
    private const val NAME = "facerotate_prefs"
    private const val K_MODE = "rotate_mode"
    private const val K_STRATEGY = "capture_strategy"

    /** 标定：roll 符号翻转（+1 / -1） */
    private const val K_SIGN = "calib_sign"

    /** 标定：相位偏移（度），使「人脸正立」对应 0° */
    private const val K_OFFSET = "calib_offset"

    /**
     * ★ 交还系统自动旋转时的**固定目标值** = 1（跟随系统重力）。
     *
     * 历史坑（2026-09-25，用户拍板改掉）：这里原来存的是「接管前的原值」，交还时照原样还原。
     * 但最早几次接管时系统自动旋转本身就是关的，于是落盘成了 0 ⇒ 之后**每次**交还都把
     * 系统自动旋转还成「关闭」，用户切到别的 App 会发现屏幕完全转不动，看着就是"坏了"。
     * 现在的语义：接管期我们把它置 0，交还/孤儿恢复一律还原为 1。
     * 旧键 `saved_auto_rotate` 就此废弃（不再读也不再写），新键与它不同名，
     * 于是历史脏值天然失效，不需要额外的迁移代码。
     */
    const val RESTORE_AUTO_ROTATE = 1
    private const val K_RESTORE_AUTO_ROTATE = "restore_auto_rotate"

    /** ★ 是否处于「我们正接管」状态 —— 持久化，进程被杀后下次启动能还原 */
    private const val K_TAKEOVER = "takeover_active"

    private val _mode = MutableStateFlow(RotateMode.SYSTEM)
    private val _strategy = MutableStateFlow(CaptureStrategy.POWER_SAVING)
    private val _sign = MutableStateFlow(1)
    private val _offsetDeg = MutableStateFlow(0f)

    val mode: StateFlow<RotateMode> = _mode.asStateFlow()
    val strategy: StateFlow<CaptureStrategy> = _strategy.asStateFlow()
    val sign: StateFlow<Int> = _sign.asStateFlow()
    val offsetDeg: StateFlow<Float> = _offsetDeg.asStateFlow()

    /** 镜像通道是否出过问题（写不进去 / 回读不一致）—— 供界面如实提示 */
    private val _mirrorProblem = MutableStateFlow(false)
    val mirrorProblem: StateFlow<Boolean> = _mirrorProblem.asStateFlow()

    // ---------------------------------------------------------------- 后端

    /** App 进程的本地副本（离线兜底）；宿主模式下恒为 null */
    @Volatile
    private var localSp: SharedPreferences? = null

    /** 读写跨进程镜像用的 context；两个进程都会设置 */
    @Volatile
    private var bridge: Context? = null

    /** 本进程是否宿主（SystemUI）：配置以镜像为唯一真身，且实时跟随变化 */
    @Volatile
    private var hostMode = false

    /** 宿主侧监听镜像用的专用线程（绝不用 SystemUI 主线程） */
    @Volatile
    private var watchThread: HandlerThread? = null

    private var watchObserver: ContentObserver? = null

    val isHostMode: Boolean get() = hostMode

    // ================================================================ 初始化

    /**
     * **App 进程**初始化。幂等：Activity、TileService、引擎都会调。
     *
     * 读取顺序：镜像 → 本地 SP（仅当镜像里没有这个键）→ 内置默认值。
     */
    fun init(context: Context) {
        if (localSp != null || hostMode) return
        synchronized(this) {
            if (localSp != null || hostMode) return
            val app = context.applicationContext ?: context
            val p = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            val cr = app.contentResolver

            // ★ 先挂上后端，**再**读配置 —— 下面的回填要用 `bridge`。
            bridge = app
            localSp = p

            _mode.value = enumOrNull<RotateMode>(readMirrorString(cr, PrefsBridge.MODE))
                ?: enumOrNull<RotateMode>(p.getString(K_MODE, null))
                ?: RotateMode.SYSTEM

            _strategy.value = enumOrNull<CaptureStrategy>(readMirrorString(cr, PrefsBridge.STRATEGY))
                ?: enumOrNull<CaptureStrategy>(p.getString(K_STRATEGY, null))
                ?: CaptureStrategy.POWER_SAVING

            // ★ 标定值只在镜像里没有时才用本地兜底，**绝不反过来用本地覆盖镜像** ——
            //   远程标定的结果只写镜像，覆盖会把它抹掉（见类注释）。

            _sign.value = (readMirrorString(cr, PrefsBridge.SIGN)?.toIntOrNull()
                ?: p.getInt(K_SIGN, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE })
                ?.let { if (it < 0) -1 else 1 }
                ?: 1

            _offsetDeg.value = readMirrorString(cr, PrefsBridge.OFFSET)?.toFloatOrNull()
                ?: p.getFloat(K_OFFSET, Float.NaN).takeIf { it.isFinite() }
                ?: 0f
        }

        // 镜像里缺的键用本地值回填。**放到后台**：回填可能要 spawn `su`（见 [mirror]），
        // 而这里是 Activity 的 `onCreate` 路径，占住主线程就是几百毫秒的白屏。
        seedMirrorAsync()
    }

    /**
     * **宿主进程**（SystemUI）初始化。必须在引擎起来之前调用。
     *
     * 与 [init] 的差别：不碰本地 SP，改为注册 [PrefsBridge.WATCHED] 的监听 ——
     * 用户在 App 界面改任何一项，这里都会在毫秒级收到并灌进 StateFlow，
     * 引擎的 `collect` 随即跟着启停 / 换策略 / 重载标定。
     */
    fun initHost(context: Context) {
        if (hostMode) return
        synchronized(this) {
            if (hostMode) return
            val app = context.applicationContext ?: context
            val cr = app.contentResolver

            _mode.value = enumOrNull<RotateMode>(readMirrorString(cr, PrefsBridge.MODE)) ?: RotateMode.SYSTEM
            _strategy.value =
                enumOrNull<CaptureStrategy>(readMirrorString(cr, PrefsBridge.STRATEGY))
                    ?: CaptureStrategy.POWER_SAVING
            _sign.value = if ((readMirrorString(cr, PrefsBridge.SIGN)?.toIntOrNull() ?: 1) < 0) -1 else 1
            _offsetDeg.value = readMirrorString(cr, PrefsBridge.OFFSET)?.toFloatOrNull() ?: 0f

            bridge = app
            hostMode = true

            val t = HandlerThread("hyperplus-prefs").apply { start() }
            watchThread = t
            val h = Handler(t.looper)
            val ob = object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                    runCatching { applyMirror(uri?.lastPathSegment) }
                }
            }
            watchObserver = ob
            PrefsBridge.WATCHED.forEach { PrefsBridge.watch(cr, PrefsBridge.full(it), ob) }
        }
    }

    /** 宿主侧：镜像某个键变了 → 同步进内存流 */
    private fun applyMirror(fullKey: String?) {
        val cr = bridge?.contentResolver ?: return
        when (fullKey) {
            PrefsBridge.full(PrefsBridge.MODE) ->
                enumOrNull<RotateMode>(readMirrorString(cr, PrefsBridge.MODE))?.let { _mode.value = it }

            PrefsBridge.full(PrefsBridge.STRATEGY) ->
                enumOrNull<CaptureStrategy>(readMirrorString(cr, PrefsBridge.STRATEGY))?.let { _strategy.value = it }

            PrefsBridge.full(PrefsBridge.SIGN) ->
                (readMirrorString(cr, PrefsBridge.SIGN)?.toIntOrNull())?.let {
                    _sign.value = if (it < 0) -1 else 1
                }

            PrefsBridge.full(PrefsBridge.OFFSET) ->
                readMirrorString(cr, PrefsBridge.OFFSET)?.toFloatOrNull()?.let { _offsetDeg.value = it }
        }
    }

    // ================================================================ 写入

    fun setMode(value: RotateMode) {
        _mode.value = value
        localSp?.edit()?.putString(K_MODE, value.name)?.apply()
        publish(PrefsBridge.MODE, value.name)
    }

    fun setStrategy(value: CaptureStrategy) {
        _strategy.value = value
        localSp?.edit()?.putString(K_STRATEGY, value.name)?.apply()
        publish(PrefsBridge.STRATEGY, value.name)
    }

    /** 供 QS Tile 单击使用：两态互切 */
    fun toggleMode(): RotateMode =
        if (_mode.value == RotateMode.SYSTEM) RotateMode.ADAPTIVE.also { setMode(it) }
        else RotateMode.SYSTEM.also { setMode(it) }

    // ---------------------------------------------------------------- 方向标定

    fun setCalibration(sign: Int, offsetDeg: Float) {
        _sign.value = if (sign < 0) -1 else 1
        _offsetDeg.value = offsetDeg
        localSp?.edit()
            ?.putInt(K_SIGN, _sign.value)
            ?.putFloat(K_OFFSET, _offsetDeg.value)
            ?.apply()
        publish(PrefsBridge.SIGN, _sign.value.toString())
        publish(PrefsBridge.OFFSET, _offsetDeg.value.toString())
    }

    /**
     * 是否已标定。
     * 判据放在**镜像里有没有 OFFSET 这个键**（而不是"值是否非 0"）——
     * 因为「已标定成 0° 偏移」是完全合法的结果，用值判断会把它当成没标定。
     */
    val isCalibrated: Boolean
        get() {
            bridge?.let { if (readMirrorString(it.contentResolver, PrefsBridge.OFFSET) != null) return true }
            return localSp?.contains(K_OFFSET) == true
        }

    fun clearCalibration() = setCalibration(1, 0f)

    // ---------------------------------------------------------------- 接管状态

    /**
     * 记下「交还时该把系统自动旋转还原成什么」。目前恒写 [RESTORE_AUTO_ROTATE]（=1）。
     * ★ 必须落盘：进程被系统强杀时实例变量会丢，导致 accelerometer_rotation
     *   永久停在 0（系统自动旋转再也回不来）—— 这是实测踩过的事故。
     *
     * ★ 这两个键是**宿主自己的账**（谁接管谁交还），所以只在本进程的存储里写：
     *   宿主进程写镜像，App 进程写本地 SP。**不参与 App → 主机的推送**
     *   —— 否则 App 会拿自己那份去覆盖宿主正在进行的接管状态。
     */
    fun setRestoreTarget(v: Int) {
        localSp?.edit()?.putInt(K_RESTORE_AUTO_ROTATE, v)?.apply()
        if (hostMode) mirror(PrefsBridge.RESTORE, v.toString())
    }

    /** 交还目标值；无记录时返回 [RESTORE_AUTO_ROTATE]（=1，系统默认开） */
    fun restoreTarget(): Int {
        bridge?.let {
            readMirrorString(it.contentResolver, PrefsBridge.RESTORE)?.toIntOrNull()?.let { v -> return v }
        }
        return localSp?.getInt(K_RESTORE_AUTO_ROTATE, RESTORE_AUTO_ROTATE) ?: RESTORE_AUTO_ROTATE
    }

    fun setTakeoverActive(active: Boolean) {
        localSp?.edit()?.putBoolean(K_TAKEOVER, active)?.apply()
        if (hostMode) mirror(PrefsBridge.TAKEOVER, if (active) "1" else "0")
    }

    /** 上次退出时是否还开着接管 —— 用于启动时做「孤儿接管」检测与还原 */
    fun isTakeoverActive(): Boolean {
        bridge?.let {
            readMirrorString(it.contentResolver, PrefsBridge.TAKEOVER)?.let { v -> return v == "1" }
        }
        return localSp?.getBoolean(K_TAKEOVER, false) ?: false
    }

    // ================================================================ 跨进程

    /**
     * 镜像写入专用线程（单线程，保序）。
     *
     * ★ 为什么必须异步：App 侧的镜像写入要走 `su`（见 [PrefsBridge] 类注释里那个坑），
     *   一次 spawn 100~300ms。而 [setMode] / [setCalibration] 都是从 Compose 点击回调
     *   （主线程）进来的，同步做就是肉眼可见的卡顿。
     *   内存里的 StateFlow 是**同步**更新的，所以界面响应不受影响。
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hyperplus-mirror").apply { isDaemon = true }
    }

    /**
     * 配置变更后的跨进程同步：写 `Settings.System` 镜像（异步）。
     *
     * ★ 两个进程走**同一条路**（见 [PrefsBridge] 的自动降级：特权包直写、App 侧转 root）。
     *   所以通道是双向对称的：谁改谁写，另一侧用 `ContentObserver` 收。
     *
     * ⚠️ 写入失败会置位 [mirrorProblem]，界面据此弹「用 root 同步配置」，
     *   **不能假装成功** —— 实测最容易出现的事故就是"界面切了开关、宿主那边什么都没变"。
     */
    private fun publish(localKey: String, v: String) {
        writer.execute { mirror(localKey, v) }
    }

    /**
     * 把**镜像里缺失的键**用当前内存值回填（异步）。
     *
     * ★ 用途有二：
     *   ① 首次升级 / 清数据后自愈；
     *   ② 用户在界面点了「授予 root 权限」之后补一次 —— 否则他刚拿到写权限时镜像是空的，
     *      宿主 `initHost` 会退回默认值（SYSTEM / 省电 / 未标定），用户还得手动把每个开关
     *      重切一次，等于没修好。
     *
     * ★ 纪律：**镜像里没有的键才写**。绝不覆盖 —— 远程标定只写镜像（见类注释）。
     */
    fun seedMirrorAsync() {
        writer.execute {
            val c = bridge ?: return@execute
            val cr = c.contentResolver
            if (readMirrorString(cr, PrefsBridge.MODE) == null) mirror(PrefsBridge.MODE, _mode.value.name)
            if (readMirrorString(cr, PrefsBridge.STRATEGY) == null) mirror(PrefsBridge.STRATEGY, _strategy.value.name)
            // ★ 标定只在**本地确实标过**时才回填 —— 否则会凭空造出一个 OFFSET 键，
            //   而 [isCalibrated] 的判据正是"镜像里有没有 OFFSET"，界面就会谎报"已校准"。
            if (localSp?.contains(K_OFFSET) == true && readMirrorString(cr, PrefsBridge.OFFSET) == null) {
                mirror(PrefsBridge.SIGN, _sign.value.toString())
                mirror(PrefsBridge.OFFSET, _offsetDeg.value.toString())
            }
        }
    }

    // ================================================================ 镜像工具

    private fun readMirrorString(cr: ContentResolver, localKey: String): String? =
        PrefsBridge.readString(cr, PrefsBridge.full(localKey))

    /**
     * 写一处配置到跨进程镜像，并**回读校验**（在 [writer] 线程上执行）。
     *
     * ★ 为什么必须回读：写失败可能是「返回 false」也可能是「抛异常 / 被 root 拒」，
     *   两种都容易被静默吞掉。实测正是这样踩过一次，所以回读把"写了但没生效"变成
     *   可判定的结果，界面才有机会如实告诉用户。
     */
    private fun mirror(localKey: String, v: String) {
        val c = bridge ?: return
        val ok = PrefsBridge.writeString(c.contentResolver, PrefsBridge.full(localKey), v)
        if (!ok || readMirrorString(c.contentResolver, localKey) != v) {
            _mirrorProblem.value = true
        } else {
            _mirrorProblem.value = false
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        if (name.isNullOrEmpty()) null
        else runCatching { enumValueOf<T>(name) }.getOrNull()
}

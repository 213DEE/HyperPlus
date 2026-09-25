package cn.dsr213.hyperplus

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * App 侧与「跑在 SystemUI 里的引擎」打交道的那一层。
 *
 * ============================ 为什么 App 需要先"问一声" ============================
 * 引擎现在可能有两个宿主：**SystemUI 里的常驻引擎**，或**App 自己的本地引擎**。
 * 两者绝不能同时活着 —— 它们会抢相机、抢写 `user_rotation`，症状是方向乱跳。
 *
 * 所以 App 启动时先探一次活：
 *  - 有回执 → 模块在跑 → **不启本地引擎**，界面进「托管模式」，只显示回传的状态；
 *  - 没回执 → 模块没装/没启用 → 起本地引擎，行为与以前完全一致（单机可用）。
 *
 * 这个探活刻意做成 **App 主动问、宿主即时答**（而不是宿主定时心跳）：
 * 零后台开销，也不需要在 SystemUI 里养一个定时器。机制与自检复测通道同款
 * （设置键 + `ContentObserver`），是本项目已经实测过的通路。
 */
object ModuleLink {

    private const val TAG = "HyperPlusLink"

    /** 总线键的短名，只用于日志可读性 */
    private val KEY_PING get() = PrefsBridge.PING

    /** 一次探活的结论 */
    data class Status(
        /** 宿主是否回执了这次探活（= 模块正在运行） */
        val alive: Boolean,
        /** 宿主回传的引擎状态；拿不到为 null */
        val state: State?,
    )

    /**
     * 宿主回传的状态摘要（[cn.dsr213.hyperplus.module.EngineHost.summary] 的解析结果）。
     *
     * ★ 格式是**跨进程契约**：只允许追加字段，不允许改已有字段名。
     */
    data class State(
        val phase: String,
        val mode: String,
        val takeover: Boolean,
        /** 引擎判定的目标方向（-1 = 未判定） */
        val rotation: Int,
        /** 系统当前实际显示方向 */
        val display: Int,
        val decider: String,
        val burstCount: Int,
        val frames: Long,
        val faces: Long,
        val switchCount: Int,
        val calibrated: Boolean,
        val calibSign: Int,
        val calibOffsetDeg: Float,
        val sensorAvailable: Boolean,
        val writeGranted: Boolean,
        val lastOpenMs: Long,
        /**
         * 宿主引擎**实时**已运行秒数。
         *
         * ★ 不是直接取摘要里的 `uptime` —— 那个是"上报那一刻的快照"，而状态是变化才上报的，
         *   所以没有新事件时它会冻住。这里的值由 `startedAt`（宿主启动时的 `elapsedRealtime` 秒）
         *   与本机同一条系统级时钟现算，因此**永远准确**。
         */
        val uptimeSec: Int,
        /**
         * 距宿主**最后一次心跳**过了多少秒（-1 = 拿不到心跳）。
         *
         * ★ 心跳由宿主每 [HOST_HEARTBEAT_PERIOD_MS] 无条件写一次（与"状态有无变化"无关），
         *   所以这个数字只回答一件事：**宿主还活着吗**。
         *   "引擎在干活吗"看 [frames] / [faces] / [decider]，两件事不要混。
         *
         * ⚠️ 别把它当"引擎没在干活"的证据 —— 无事件时状态本来就不上报（这是设计）。
         */
        val heartbeatAgoSec: Int,
        /** 原始串，托管模式下直接展示，避免界面为了少数字段解析失败就全空 */
        val raw: String,
    ) {

        /**
         * 宿主是否**在线**（心跳够新鲜）。
         *
         * 阈值 15 秒 = 心跳周期 5 秒的 3 倍，容得下 SystemUI 偶发的调度延迟；
         * 真死掉的话最多 15 秒就被认出来。
         */
        val hostAlive: Boolean get() = heartbeatAgoSec in 0..HOST_LOST_AFTER_SEC
    }

    /** 心跳超过这个秒数没更新 ⇒ 判定宿主失联（≈ 3 个心跳周期） */
    const val HOST_LOST_AFTER_SEC = 15

    /** 宿主的心跳周期（秒）。改宿主的 `HEARTBEAT_PERIOD_MS` 时要一起改这里。 */
    const val HOST_HEARTBEAT_PERIOD_MS = 5_000L

    /** 探活超时。宿主回执是"改个设置值"，正常情况下是毫秒级；1.5s 已是极宽裕的余量。 */
    private const val PING_TIMEOUT_MS = 1_500L
    private const val POLL_INTERVAL_MS = 40L

    /**
     * 【**不要在主线程调用**】探一次活。
     *
     * 内部会短暂 Sleep 轮询（最多 [PING_TIMEOUT_MS]），所以必须放在后台线程。
     */
    fun ping(ctx: Context, timeoutMs: Long = PING_TIMEOUT_MS): Status {
        val cr = ctx.contentResolver

        // 读一次当前状态：即使模块没在跑，也能拿到它上次留下的状态（用于展示"上次"）
        val cached = readState(cr)

        val cur = PrefsBridge.readInt(cr, PrefsBridge.PING, 0)
        val nonce = if (cur == Int.MAX_VALUE) 1 else cur + 1

        // 写不进总线 ⇒ 缺 root（App 侧写自定义键的唯一出路，见 [PrefsBridge] 类注释里那个坑）。
        // 这种情况下模块也收不到我们的任何请求，直接判定为「未托管」，
        // 交给本地引擎兜底（而不是让开关变成死的）。
        if (!PrefsBridge.writeInt(cr, PrefsBridge.PING, nonce)) {
            Log.w(TAG, "⚠️ 探活写不进总线（$KEY_PING=$nonce 未生效）→ 判定为未托管。" +
                "原因通常是还没点「授予 root 权限」")
            return Status(alive = false, state = cached)
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (PrefsBridge.readInt(cr, PrefsBridge.PONG, 0) == nonce) {
                Log.i(TAG, "✅ 宿主已回执（nonce=$nonce）→ 托管模式")
                return Status(alive = true, state = readState(cr) ?: cached)
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
        Log.i(TAG, "⌛ 宿主未回执（nonce=$nonce，等了 ${timeoutMs}ms）→ 单机模式")
        return Status(alive = false, state = cached)
    }

    // ================================================================ 远程标定

    /** 标定步骤：1 = 记竖屏基准，2 = 记左横屏（定方向） */
    const val CALIB_BASELINE = 1
    const val CALIB_AXIS = 2

    /** 请求结果 */
    enum class CalibStatus { OK, NO_FACE, BAD_ANGLE, UNAVAILABLE, TIMEOUT }

    /**
     * 【**不要在主线程调用**】让宿主的引擎采一次标定样本并应用。
     *
     * ★ 为什么标定必须由宿主做：标定要真的开相机采 2.6 秒的样本，而**相机在宿主手里**。
     *   App 这边开着相机只会两边抢。
     *
     * @return 宿主回报的结果；超时返回 [CalibStatus.TIMEOUT]
     */
    fun requestCalibration(
        ctx: Context,
        step: Int,
        timeoutMs: Long = 10_000L,
    ): CalibStatus {
        val cr = ctx.contentResolver
        // 先把上一次的结果清成非本次的值，避免读到陈旧结果（格式 "req|status"）
        PrefsBridge.writeString(cr, PrefsBridge.CALIB_RESULT, "0|pending")
        if (!PrefsBridge.writeInt(cr, PrefsBridge.CALIB_REQ, step)) return CalibStatus.UNAVAILABLE

        val want = "$step|"
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = PrefsBridge.readString(cr, PrefsBridge.CALIB_RESULT)
            if (raw != null && raw.startsWith(want)) {
                return when (raw.substringAfter('|').substringBefore('|').trim()) {
                    "ok" -> CalibStatus.OK
                    "noface" -> CalibStatus.NO_FACE
                    "badangle" -> CalibStatus.BAD_ANGLE
                    else -> CalibStatus.UNAVAILABLE
                }
            }
            runCatching { Thread.sleep(120) }
        }
        return CalibStatus.TIMEOUT
    }

    // ================================================================ 状态读取

    /**
     * 纯读一眼宿主留下的状态（**不探活**，两次 `Settings` 读 + 解析，可在主线程调用）。
     * 界面定时刷新用它 —— 比每次探活便宜得多。
     */
    fun currentState(ctx: Context): State? = readState(ctx.contentResolver)

    /** 读状态串 + 心跳，一起交给 [parse]（心跳用来算"宿主心跳距今多久"） */
    private fun readState(cr: android.content.ContentResolver): State? =
        parse(
            PrefsBridge.readString(cr, PrefsBridge.STATE),
            PrefsBridge.readInt(cr, PrefsBridge.HEARTBEAT, 0),
        )

    // ================================================================ 解析

    /**
     * 解析状态摘要；格式不认识就返回 null（**不猜**）。
     *
     * @param heartbeatSec 宿主写 `bus_engine_heartbeat` 时的 `elapsedRealtime` 秒；
     *                     传 0 表示拿不到，此时 [State.heartbeatAgoSec] 为 -1。
     */
    fun parse(raw: String?, heartbeatSec: Int = 0): State? {
        if (raw.isNullOrBlank()) return null
        // 版本头是契约的一部分：认不出来的格式宁可当作"没有状态"，也不要按字段名硬猜
        if (!raw.startsWith("v1|")) return null
        val kv = HashMap<String, String>()
        for (part in raw.split('|')) {
            val i = part.indexOf('=')
            if (i > 0) kv[part.substring(0, i)] = part.substring(i + 1)
        }
        val nowSec = SystemClock.elapsedRealtime() / 1000
        val startedAt = kv["startedAt"]?.toLongOrNull() ?: 0L
        return runCatching {
            State(
                phase = kv["phase"] ?: "unknown",
                mode = kv["mode"] ?: "SYSTEM",
                takeover = kv["takeover"] == "1",
                rotation = kv["rot"]?.toIntOrNull() ?: -1,
                display = kv["disp"]?.toIntOrNull() ?: 0,
                decider = kv["decider"] ?: "UNKNOWN",
                burstCount = kv["burst"]?.toIntOrNull() ?: 0,
                frames = kv["frames"]?.toLongOrNull() ?: 0L,
                faces = kv["faces"]?.toLongOrNull() ?: 0L,
                switchCount = kv["sw"]?.toIntOrNull() ?: 0,
                calibrated = kv["calib"] == "1",
                calibSign = kv["sign"]?.toIntOrNull() ?: 1,
                calibOffsetDeg = kv["offset"]?.toFloatOrNull() ?: 0f,
                sensorAvailable = kv["sensor"] == "1",
                writeGranted = kv["grant"] == "1",
                lastOpenMs = kv["openMs"]?.toLongOrNull() ?: -1L,
                // ★ 优先用 startedAt 现算（实时、准确）；拿不到才退回快照值（老格式兜底）
                uptimeSec = if (startedAt > 0L) {
                    (nowSec - startedAt).coerceAtLeast(0L).toInt()
                } else {
                    kv["uptime"]?.toIntOrNull() ?: 0
                },
                heartbeatAgoSec = if (heartbeatSec > 0) {
                    (nowSec - heartbeatSec).coerceAtLeast(0L).toInt()
                } else {
                    -1
                },
                raw = raw,
            )
        }.getOrNull()
    }
}

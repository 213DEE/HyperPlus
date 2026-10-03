package cn.dsr213.hyperplus.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import cn.dsr213.hyperplus.OrientationFusion
import kotlin.math.abs

/**
 * 触发源：**双路**（2026-09-25 起）。
 *
 * ============================ ① device_orientation（主路，零功耗）============================
 *
 * 选型依据（本机 `dumpsys sensorservice` 实测 + 官方定义）：
 *   - `device_orientation` 是 **on-change** 型：
 *     只在朝向真的发生变化时才回调一次 ⇒ 天生就是「只在旋转时唤醒」，零轮询成本。
 *   - 对比 `tilt_detector`(22)：官方定义是「2 秒平均**重力方向**变化 ≥35°」。
 *     手机在平面内由竖转横时，重力方向几乎不变 ⇒ **不触发**，不适合本场景。
 *   - 对比 `significant_motion`(17)：one-shot 型，官方定义面向「可能导致**位置**变化的运动」
 *     （走路/坐车），原地翻转大概率不触发。
 *
 * ============================ ② gyroscope（兜底，2026-09-25 新增）============================
 *
 * ★ 为什么必须补这一路（真机实证，用户的复现步骤）：
 *   用户报告「头和手机（横屏）同时侧着约 45°，自适应调完方向之后，把手机转回竖屏，
 *   **完全没有反应**」。查 `dumpsys sensorservice` 拿到直接证据：
 *   ```
 *   09:35:51 + 0x00000048 pid=12358 uid=10193 samplingPeriod=200000us result=OK
 *              (device_orient Non-wakeup, cn.dsr213.hyperplus.trigger.DeviceOrientationTrigger)
 *   ```
 *   —— 监听器**注册着、从未注销**，而 09:48:58 之后**连续 3 分钟零事件**。
 *   原因就在这类传感器的物理限制：`device_orientation` 是 `on-change`、`maxDelay=0`、
 *   **无批处理**，值域是 4 个象限（0~3），靠**重力**分辨朝向。
 *   手机平放 / 斜着拿时重力几乎全落在 Z 轴，平面内的转动**不改变重力方向**
 *   ⇒ 象限不变 ⇒ **一个事件都不发**，触发层形同虚设，屏幕就"卡"在上一次的方向。
 *   （本类旧注释里其实已把这个盲区写成"待补"，一直没补上；这次补。）
 *
 * ★ 为什么选陀螺仪而不是加速度计：陀螺仪测的是**角速度**，与重力方向无关，
 *   手机平着、侧着、躺着都能测到"我在转"。加速度计只比 `device_orientation`
 *   多一层"自己算象限"，平放时同样失效。
 *
 * ★ 判据（两个条件同时满足才算一次真转动，避免手抖误触发）：
 *   1) 绕**屏幕法线轴（Z）**的角速度 |ωz| 超过 [GYRO_RATE_DPS]；
 *   2) 本次"转动过程"累计转过的角度超过 [GYRO_MIN_SPIN_DEG]。
 *   一旦触发就把累计清零；静止超过 [GYRO_IDLE_RESET_MS] 也清零
 *   （否则陀螺零偏会慢慢积成一个假角度）。
 *
 * ★ 代价要说清楚：陀螺仪在 ADAPTIVE 模式下会**常驻**（[SENSOR_DELAY_UI]，约 15Hz）。
 *   这是拿一点电换"转了必定有反应"。另一种选择是加一个低频轮询兜底（比如 12 秒一次），
 *   但那会带来最长 12 秒的迟钝 —— 用户要的是"转了就跟手"，所以选陀螺。
 *
 * ============================ ③ gravity（当尺子，2026-09-25 新增）============================
 *
 * ⚠️ 这一路**不产生触发**，也**不参与方向运算** —— 它只当一把尺子，校验人脸链路的符号位。
 *
 * ★ 为什么不参与运算（曾经想写成"重力基准 + 人脸偏差"，**那是错的**）：
 *   设 `φ` = 设备相对世界正立的转角、`ψ` = 人头相对世界正立的倾斜，
 *   则 `θrel = φ + ψ`，而屏幕要转的角度就是 `u = θrel / 90`。
 *   重力单独测出 `φ`，人脸单独测出 `θrel` ⇒ 相加得到 `2φ + ψ`，**把设备自身的旋转算了两遍**。
 *   反例：手机逆时针 90°、头正立 ⇒ `φ=90`、`θrel=90`，正确 `u=1`，相加却得 `1+1=2`。
 *
 * ★ 真正的病根是**符号位**（用户报「横屏有概率反过来」「转动方向越来越反」）：
 *   `u = θrel/90` 本身已把方向算全，唯一未知的是相机链路那一个 `sign = ±1`
 *   （ML Kit 的 `headEulerAngleZ` 与 `θrel` 之间隔着"前置镜像/左右眼定义/传感器朝向"三层）。
 *   而 `sign` 取反的效果恰好是 **1↔3 对调、0/2 不变** ⇒ **只在横屏上表现为方向反过来**。
 *   真机取证：`calib_sign=1 / calib_offset=0.0` 恰好等于代码默认值
 *   ⇒ 这一位从未被真正钉死；`OrientationDecider` 的注释自己也写着"理论应为 -1 …… 待标定确定"。
 *
 *   ⇒ 重力是**唯一能自动把它钉死**的参照物：日常用机时人头朝世界正立（`ψ≈0`），
 *     此时「人脸算出的扇区」应当**等于**重力扇区；若等于它的镜像，就是符号位反了。
 *     判据与"为什么竖屏帧一律不算数"见 [cn.dsr213.hyperplus.OrientationFusion.verdict]。
 *     注意 `TYPE_27` 也是重力象限的另一种表达，可作为本路读数的交叉校验（见下）。
 *
 * ⚠️ **仍未覆盖的场景（诚实记录）**：手机不动、只有人头转转。
 *   任何传感器都测不到"头动了"，只有相机能看 —— 而开相机又需要一个触发。
 *   要覆盖它只能加定时轮询，属于功耗与跟手度的取舍，未做，等用户拍板。
 */
class DeviceOrientationTrigger(
    context: Context,
    /** 两次触发之间的最小间隔，防止传感器抖动导致 burst 连发（device_orientation 路） */
    private val minTriggerIntervalMs: Long = 300L,
    /**
     * 陀螺仪路的去抖间隔。比主路长：一次 90° 转动会跨好几个采样点，只该产生一轮 burst。
     *
     * ★ 1200 → **500**（2026-09-25，用户反馈"响应太慢、转了没反应"）：
     *   1200ms 是"宁可少发也不重复发"的保守值，但它直接决定了下一次触发的最早时刻 ——
     *   用户连着转两下（比如先转横屏、再转回竖屏）时，第二下会被整个压掉，
     *   表现就是**"手机都转过来了，画面一点反应都没有"**。
     *   一轮 burst 现在只要 ~0.7s（[BURST_FRAMES]=8），
     *   500ms 的去抖已经足够避免"一次转动连发两轮"，不必再压到 1200ms。
     *
     * ★ 500 → **400**（2026-09-28，用户报「延迟大 / 该转的时候不转」）：
     *   它直接决定"上一次触发之后多久才允许下一次" —— 用户转完一个方向、马上又转回来时，
     *   第二下会整个压在这段时间里。400ms 仍大于一轮 burst 的收尾抖动，
     *   不会造成连发（连发真正被压住是靠 [GYRO_RATE_DPS] + [GYRO_MIN_SPIN_DEG]）。
     */
    private val minGyroIntervalMs: Long = 400L,
    private val onTrigger: (reason: String, orientationCode: Int) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val sensor: Sensor? = sensorManager.getDefaultSensor(SENSOR_TYPE_DEVICE_ORIENTATION)

    /** 兜底路的传感器（可能为 null —— 极少数低端机没有陀螺） */
    val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * ★ 第三路（2026-09-25 新增）：**重力基准** —— 屏幕方向的"锚"。
     *
     * 优先用虚拟传感器 `TYPE_GRAVITY`（系统已做融合/滤波，且不含线性加速度），
     * 没有就退回裸 `TYPE_ACCELEROMETER`（本类自己做一层低通）。
     *
     * ★ 这一路存在的理由，和陀螺路**完全不同**：
     *   - 陀螺路解决的是「**触发**」（手机在转，得叫醒引擎）；
     *   - 重力路解决的是「**方向基准**」（现在到底该朝哪）。旧实现把方向基准
     *     100% 压在人脸角度上，再乘一个拟合出来的符号位 —— 那一位错了就整片横屏反向。
     *   详见 [cn.dsr213.hyperplus.OrientationFusion] 的类注释。
     *
     * ★ 它比 TYPE_27 (`device_orientation`) 强在**不会静默**：TYPE_27 是 on-change、
     *   靠重力**象限**判朝向，平放/斜放时象限不变 ⇒ 一个事件都不发；而加速度计是
     *   连续型，平放时照样有 x/y 分量可读（真的完全平放才退化，那时 [gravitySectorOrNull] 返回 null）。
     */
    val gravity: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 这一路是裸加速度计吗（= 需要自己做低通） */
    val gravityIsRawAccel: Boolean =
        gravity != null && gravity.type == Sensor.TYPE_ACCELEROMETER

    /** 只要有一路可用，触发层就算可用 */
    val available: Boolean get() = sensor != null || gyro != null || gravity != null

    // ---- 重力路对外发布的量（引擎每帧读它算方向，见 OrientationFusion）----

    /** 平滑后的设备坐标 x 分量（m/s²，右为正） */
    @Volatile
    var gravityAx: Float = 0f
        private set

    /** 平滑后的设备坐标 y 分量（m/s²，朝顶边为正） */
    @Volatile
    var gravityAy: Float = 0f
        private set

    /** 最近一次重力事件时刻（elapsedRealtime）；0 = 从未收到 */
    @Volatile
    var gravityAtMs: Long = 0L
        private set

    /**
     * 重力路读数是否够新。引擎据此决定「用重力锚定」还是「退回人脸单源」。
     *
     * 判据刻意放宽到 [GRAVITY_MAX_AGE_MS]：传感器在低功耗下会被系统降频，
     * 但重力方向本身变化很慢，1.5 秒前的值仍然有效。
     */
    fun gravityFresh(now: Long): Boolean =
        gravityAtMs != 0L && now - gravityAtMs <= GRAVITY_MAX_AGE_MS

    /** 最近一次原始 code，供 HUD 显示（-1 表示还没收到过 / 本次由陀螺触发） */
    @Volatile
    var lastCode: Int = -1
        private set

    /** 统计：真实触发次数（两路合计） / 因间隔太短被压掉的次数 */
    @Volatile
    var triggerCount: Int = 0
        private set

    @Volatile
    var suppressedCount: Int = 0
        private set

    /** 其中由**陀螺仪兜底路**触发的次数 —— 这个数 > 0 就说明"传感器静默"确实发生过 */
    @Volatile
    var gyroTriggerCount: Int = 0
        private set

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    @Volatile
    private var lastGyroTriggerAtMs: Long = 0L

    /** 幂等保护：退后台让位 / 回前台恢复会反复调 start/stop，重复注册没有意义 */
    @Volatile
    private var started = false

    // ---- 陀螺仪路的内部状态（只在主线程/传感器线程访问）----
    private var lastGyroAtMs = 0L
    private var motionEndAtMs = 0L
    private var spinDeg = 0f

    fun start(): Boolean {
        if (started) return true
        val s = sensor
        val g = gyro
        val gr = gravity
        var ok = false
        if (s != null) {
            ok = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) || ok
        }
        if (g != null) {
            // ★ 注意这里注册的是**三个**传感器，onSensorChanged 里按 type 分流。
            //   旧实现只注册了 device_orientation —— 手机平放时它静默，整个引擎就瞎了。
            ok = sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_UI) || ok
        }
        if (gr != null) {
            // ★ 重力路用 SENSOR_DELAY_UI（~66ms ≈ 15Hz）：与陀螺/投票窗口（~700ms）匹配，
            //   又足够跟得上一整次 90° 翻转。TYPE_GRAVITY 是虚拟传感器、成本很低；
            //   若退化成裸加速度计也没关系（本类自己做低通，见 [onGravity]）。
            ok = sensorManager.registerListener(this, gr, SensorManager.SENSOR_DELAY_UI) || ok
        }
        started = ok
        Log.i(
            TAG,
            "触发层启动=$ok  主路=${s?.name ?: "不可用"}（on-change）  " +
                "兜底路=${g?.name ?: "不可用"}（gyro, |ωz|≥${GYRO_RATE_DPS}°/s 且累计≥${GYRO_MIN_SPIN_DEG}°）  " +
                "重力锚=${gr?.name ?: "不可用"}" +
                (if (gravityIsRawAccel) "（裸加速度计，已开低通 α=$GRAVITY_EMA_ALPHA）" else "（虚拟传感器，系统已滤波）") +
                "  去抖=${minTriggerIntervalMs}/${minGyroIntervalMs}ms",
        )
        return ok
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
        lastGyroAtMs = 0L
        spinDeg = 0f
        // 重力路的值**不清零**：下次 start() 时首帧会直接覆盖；
        // 清零反而会让"刚启动那一瞬间"拿 (0,0) 当成平放。这里只清时间戳，
        // 让 gravityFresh() 立刻返回 false，避免拿停用期间的陈旧值当方向。
        gravityAtMs = 0L
        Log.i(TAG, "触发层停止")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            SENSOR_TYPE_DEVICE_ORIENTATION -> onDeviceOrientation(event)
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> onGravity(event)
        }
    }

    // ------------------------------------------------------------ 主路：device_orientation

    private fun onDeviceOrientation(event: SensorEvent) {
        val code = event.values.firstOrNull()?.toInt() ?: return
        if (code == lastCode) return
        lastCode = code

        // ★ 交叉校验（只打日志）：TYPE_27 是**平台自己**给出的朝向码，是"约定对不对"
        //   最便宜的一把尺子 —— 它和重力算出来的扇区若长期差 1~2 格，说明
        //   [OrientationFusion.gravitySector] 的符号约定在本机型上要翻。
        //   这条日志就是为了让"到底该不该翻"有实测依据，而不是继续猜。
        if (gravityAtMs != 0L) {
            val gSec = OrientationFusion.gravitySector(gravityAx, gravityAy)
            Log.i(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "重力交叉校验：TYPE_27=%d  重力扇区=%d（%s）  ax=%.2f ay=%.2f",
                    code, gSec, OrientationFusion.crossCheck(gSec, code), gravityAx, gravityAy,
                ),
            )
        }
        fire("device_orientation=$code", code, minTriggerIntervalMs)
    }

    // ------------------------------------------------------------ 第三路：重力基准

    /**
     * 重力 → 方向基准。**不做任何触发**：它只负责"现在世界朝哪边"，不负责叫醒引擎。
     *
     * `TYPE_GRAVITY` 已经滤掉了线性加速度，直接用；裸 `TYPE_ACCELEROMETER` 则先过一层
     * 指数低通（拿手机走路时加速度计会叠上 1~3 m/s² 的手部晃动，不滤会把扇区抖出格）。
     */
    private fun onGravity(event: SensorEvent) {
        val ax = event.values.getOrElse(0) { 0f }
        val ay = event.values.getOrElse(1) { 0f }
        val useRaw = gravityIsRawAccel
        if (useRaw && gravityAtMs != 0L) {
            gravityAx += GRAVITY_EMA_ALPHA * (ax - gravityAx)
            gravityAy += GRAVITY_EMA_ALPHA * (ay - gravityAy)
        } else {
            // 首帧（或虚拟传感器）直接采用，避免从 0 收敛造成的假方向
            gravityAx = ax
            gravityAy = ay
        }
        gravityAtMs = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------ 兜底：gyroscope

    /**
     * 屏幕法线轴（设备 Z 轴）的角速度积分。
     *
     * 只取 Z 轴的理由：我们要判的是"手机在**屏幕平面内**转了多少度"，
     * 那正好是绕屏幕法线的旋转。绕 X/Y（前后倾、左右倒）不属于本引擎关心的事件。
     */
    private fun onGyro(event: SensorEvent) {
        val tMs = event.timestamp / 1_000_000L
        val w = abs(event.values.getOrElse(2) { 0f })

        // 采样间隔（夹到 0~200ms，避免系统卡顿后 dt 巨大把积分一次推爆）
        val dtMs = if (lastGyroAtMs == 0L) 0L else (tMs - lastGyroAtMs).coerceIn(0L, 200L)
        lastGyroAtMs = tMs

        // 静止超过 WINDOW 就重新起算：否则陀螺零偏（~0.01°/s）会慢慢积成假角度
        if (tMs - motionEndAtMs > GYRO_IDLE_RESET_MS) spinDeg = 0f
        motionEndAtMs = tMs

        if (dtMs <= 0L) return
        // 条件 1：转得够快。手抖通常在 30°/s 以下，故意转动在 150°/s 以上。
        if (w < GYRO_RATE_DPS) return
        // 条件 2：累计转过足够的角度，避免单点噪声
        spinDeg += w * dtMs / 1000f
        if (spinDeg < GYRO_MIN_SPIN_DEG) return

        spinDeg = 0f
        gyroTriggerCount++
        fire("gyro（角速度兜底：device_orientation 静默）", CODE_GYRO, minGyroIntervalMs)
    }

    // ------------------------------------------------------------ 共用

    /**
     * @param minIntervalMs 本路自己的去抖间隔（两路的物理特性不同，不能共用一个值）
     */
    private fun fire(reason: String, code: Int, minIntervalMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val base = if (code == CODE_GYRO) lastGyroTriggerAtMs else lastTriggerAtMs
        if (now - base < minIntervalMs) {
            suppressedCount++
            return
        }
        if (code == CODE_GYRO) lastGyroTriggerAtMs = now else lastTriggerAtMs = now
        triggerCount++
        onTrigger(reason, code)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val TAG = "FaceRotate"

        /**
         * `SENSOR_TYPE_DEVICE_ORIENTATION` 的数值。
         *
         * ★ 刻意用字面量，而不是 `Sensor.TYPE_DEVICE_ORIENTATION`：
         *   实测 compileSdk 36 下该常量**不存在**，直接编译报 Unresolved reference。
         *   27 这个值有实证依据 —— 本机 `dumpsys sensorservice` 里
         *   `device_orientation` 的 handle 就是 `0x0000001b` = 27。
         */
        const val SENSOR_TYPE_DEVICE_ORIENTATION = 27

        /** 陀螺仪路回调里的"没有象限码"占位值（上层只把它记进日志） */
        const val CODE_GYRO = -2

        /**
         * 判定"正在转"的角速度门槛（度/秒），只取屏幕法线轴。
         *
         * ★ 90 → **60**（2026-09-25，响应提速）：90°/s 意味着"必须利落地甩一下"，
         *   而慢悠悠地转手机（约 60~80°/s，很常见的日常动作）会被判成"没在转"，
         *   触发就直接丢了。60°/s ≈ 1.7 秒转 100°，仍在人手抖（<30°/s）之上。
         */
        const val GYRO_RATE_DPS = 60f

        /**
         * 判定"确实转过了一个象限级的角度"（度）。
         *
         * ★ 45 → **25**（2026-09-25，响应提速）：45° 要求"转够半个象限"才触发，
         *   而预热帧 + 投票其实只需要**开头一点点角度**就能看出新姿势。
         *   降到 25° 既让触发更早，又靠 [GYRO_RATE_DPS] 的速度门槛挡住纯手抖。
         *
         * ★ 25 → **20**（2026-09-28，用户报「该转的时候不转」）：
         *   25° 仍会漏掉**慢速小幅度**的调整（比如躺着把手机从 80° 摆到 95°）。
         *   20° 约等于"手明显动了一下"的量级，而手抖的累计角度通常在 10° 以内，
         *   再加上 [GYRO_RATE_DPS] 的速度门槛（60°/s ≈ 1/3 秒转过 20°）双重把关，
         *   不会因为这一降就变成"手一碰就触发"。
         */
        const val GYRO_MIN_SPIN_DEG = 20f

        /** 静止这么久就清掉累计角度（防零偏积分漂移） */
        const val GYRO_IDLE_RESET_MS = 400L

        /**
         * 裸加速度计的低通系数。取 0.25 的理由：@15Hz 下时间常数 ≈ 4 个采样 ≈ 270ms，
         * 既能滤掉走路时 1~3 m/s² 的手部晃动，又不会让"翻一下手机"的跟随明显拖后。
         */
        const val GRAVITY_EMA_ALPHA = 0.25f

        /**
         * 重力值的老化上限（ms）。超过它就认为"这一路暂时没有可信读数"，
         * 引擎退回人脸单源。放到 1500ms 是因为重力方向本身变得很慢，
         * 而系统在低功耗下可能把传感器降频到 1Hz 左右。
         */
        const val GRAVITY_MAX_AGE_MS = 1_500L
    }
}

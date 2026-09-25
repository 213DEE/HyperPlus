package cn.dsr213.hyperplus.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * 触发源：`TYPE_DEVICE_ORIENTATION`（传感器 id 27）。
 *
 * ★ 选型依据（本机 `dumpsys sensorservice` 实测 + 官方定义）：
 *   - `device_orientation` 是 **on-change** 型且有 wakeup 版本：
 *     硬件只在朝向真的发生变化时才回调一次 ⇒ 天生就是「只在旋转时唤醒」，零轮询成本。
 *   - 对比 `tilt_detector`(22)：官方定义是「2 秒平均**重力方向**变化 ≥35°」。
 *     手机在平面内由竖转横时，重力方向几乎不变 ⇒ **不触发**，不适合本场景。
 *   - 对比 `significant_motion`(17)：one-shot 型，官方定义面向「可能导致**位置**变化的运动」
 *     （走路/坐车），原地翻转大概率不触发。
 *
 * ⚠️ 尚待实测确认的一点：
 *   AOSP 只写了 `values[0]` 取值范围是 `0-3`，**没有明说它到 0/90/180/270 的映射**。
 *   本类不假设映射，而是把原始 code 一并回调出去，由上层记录并在 HUD 里显示真实值，
 *   再用真人转头做对照校正 —— 不猜。
 *
 * ⚠️ **已知覆盖盲区（截至 2026-09-25，代码核实过，没有兜底）**：
 *   本传感器是 on-change 型，**只在设备朝向变化时回调**。所以"手机没动、只有人头转了"
 *   这类场景（躺着侧看、手机架桌上人挪到另一侧）**不会触发**，方向也就不会更新。
 *   ⇒ 需要补一个零功耗兜底触发源（屏幕亮起 / 解锁 / 相机被占用后释放）。
 *   在补上之前不要以为它已经覆盖了这些场景 —— 这里曾经写着"上层另挂了兜底触发源"，
 *   但全仓 grep 确认并不存在，属于文档与实现不一致，已改正。
 */
class DeviceOrientationTrigger(
    context: Context,
    /** 两次触发之间的最小间隔，防止传感器抖动导致 burst 连发 */
    private val minTriggerIntervalMs: Long = 500L,
    private val onTrigger: (reason: String, orientationCode: Int) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val sensor: Sensor? = sensorManager.getDefaultSensor(SENSOR_TYPE_DEVICE_ORIENTATION)

    val available: Boolean get() = sensor != null

    /** 最近一次原始 code，供 HUD 显示（-1 表示还没收到过） */
    @Volatile
    var lastCode: Int = -1
        private set

    /** 统计：真实触发次数 / 因间隔太短被压掉的次数 */
    @Volatile
    var triggerCount: Int = 0
        private set

    @Volatile
    var suppressedCount: Int = 0
        private set

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    /** 幂等保护：退后台让位 / 回前台恢复会反复调 start/stop，重复注册没有意义 */
    @Volatile
    private var started = false

    fun start(): Boolean {
        val s = sensor ?: run {
            Log.i(TAG, "device_orientation 传感器不可用，触发层无法启动")
            return false
        }
        if (started) return true
        val ok = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        started = ok
        Log.i(
            TAG,
            "触发层启动=$ok  sensor=${s.name}  minInterval=${minTriggerIntervalMs}ms  " +
                "maxDelay=${s.maxDelay}us  wakeUp=${s.isWakeUpSensor}",
        )
        return ok
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
        Log.i(TAG, "触发层停止")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val code = event.values.firstOrNull()?.toInt() ?: return
        if (code == lastCode) return
        lastCode = code

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAtMs < minTriggerIntervalMs) {
            suppressedCount++
            return
        }
        lastTriggerAtMs = now
        triggerCount++
        onTrigger("device_orientation=$code", code)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "FaceRotate"

        /**
         * `SENSOR_TYPE_DEVICE_ORIENTATION` 的数值。
         *
         * ★ 刻意用字面量，而不是 `Sensor.TYPE_DEVICE_ORIENTATION`：
         *   实测 compileSdk 36 下该常量**不存在**，直接编译报 Unresolved reference。
         *   27 这个值有实证依据 —— 本机 `dumpsys sensorservice` 里
         *   `device_orientation` 的 handle 就是 `0x0000001b` = 27。
         */
        const val SENSOR_TYPE_DEVICE_ORIENTATION = 27
    }
}

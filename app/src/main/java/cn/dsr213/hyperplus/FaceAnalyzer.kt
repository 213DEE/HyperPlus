package cn.dsr213.hyperplus

import android.media.Image
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一帧的分析结果。字段选择的原则：**够事后离线判断"准不准"**。
 */
data class FaceFrame(
    /** elapsedRealtime，用于和别的信号对时间轴 */
    val atMs: Long,
    /** 已按 [extraRotation] 修正过的 headEulerAngleZ（度）。无脸 = NaN */
    val eulerZ: Float,
    /** 由左右眼连线算出的 roll（度），同样已修正。用于交叉验证 eulerZ。无眼 = NaN */
    val eyeRoll: Float,
    val faceCount: Int,
    /** 选中那张脸的归一化面积占比 */
    val faceArea: Float,
    val detectMs: Long,
    /** 本帧所用的**固定旋转基准**（= SENSOR_ORIENTATION）。记下来才能复现 roll 的符号 */
    val rotationDegrees: Int,
    /** 本帧相对上一帧的间隔，用来算有效帧率 */
    val intervalMs: Long,
    /** ★ 本帧为了找到脸而多转了多少度（0/90/180/270）；无脸时是最后试的那个 */
    val extraRotation: Int = 0,
    /** ★ 本帧一共试了几个角度（1 = 热点一次命中；4 = 全试遍还没脸） */
    val triedCount: Int = 0,
    /** ★ 低置信帧：eulerZ 与 eyeRoll 两路测量对不上（差超过 maxConsistencyDeg） */
    val lowConf: Boolean = false,
    val err: String? = null
)

/**
 * CameraX 的 ImageAnalysis.Analyzer：取帧 → ML Kit 人脸检测 → 回调 [FaceFrame]。
 *
 * ## ⚠️ 为什么必须做「多角度搜索」（2026-09-25 实测结论）
 *
 * ML Kit 的人脸检测器**只认接近正立的脸**。拿同一张图转 0/15/30/45/60/75/90° 实测：
 *
 * ```
 *    ±15° ±30° ±45° ±60°  → 全部检出
 *    +75°                 → FAST 检不到，ACCURATE 能检出
 *    ±90° / 180° / 270°   → 两个模式都检不到
 * ```
 *
 * 而「竖屏 ↔ 横屏」这个最核心的切换，人脸 roll 恰好会从 0 走到 **±90°** ——
 * **正好落在失明区**。所以想把 360° 全覆盖，就必须把图像额外转几个角度各试一次。
 *
 * ## 搜索策略（热点优先，平均接近 1 次）
 *
 * 人脸角度是**连续变化**的，上一帧命中的角度极可能这一帧还有效。
 * 所以按 `[上次命中, +90, +180, +270]` 的顺序试，**第一个检出就停**：
 * 稳态 1 次、跨象限 2 次、全失明才 4 次。实测平均值看 `sumTriedCount / totalFrames`。
 *
 * ## 角度修正（实测得到的 1:1 关系）
 *
 * 把图像额外**顺时针**转 `extra` 度后测得：
 * ```
 *    eulerZ  ≈ -(图像顺时针旋转量)   ⇒  真实 eulerZ = 测量值 + extra
 *    eyeRoll ≈ +(图像顺时针旋转量)   ⇒  真实 eyeRoll = 测量值 - extra
 * ```
 * （实测：顺时针 +45° ⇒ eulerZ 变化 -42.8 / eyeRoll 变化 +43.8，扣掉基线后完全线性。）
 *
 * ## 无人脸退避（2026-09-25 实测后补）
 *
 * 实测：检出率 34~47% 时平均搜索次数被推到 2.6~3.0 次/帧 —— 因为"没脸"要把 4 个角度全试遍。
 * 现在连续 [missBackoffFrames] 帧无脸就进入退避：只试热点角，每 [backoffProbeEvery] 帧放一次全扫。
 * 预期把无人脸场景的平均搜索从 4.0 压到 ~1.3。
 *
 * ## 两路交叉验证（2026-09-25 实测后补）
 *
 * eulerZ（ML Kit 头部欧拉角）与 eyeRoll（左右眼连线）是两个独立来源，符号天然相反。
 * 差超过 [maxConsistencyDeg] 的帧标 [FaceFrame.lowConf]，上层丢弃 —— 消掉判定器的跳变噪声。
 */
class FaceAnalyzer(
    /**
     * 图像旋转的**固定基准** —— 取 CameraCharacteristics.SENSOR_ORIENTATION。
     *
     * ★ 为什么不用 imageInfo.rotationDegrees（2026-09-25 实测后改）：
     *   它的参考系是「绑定相机那一刻的显示方向」。实测 817 帧里它恒为 270，
     *   而同期系统显示方向 sys_rot 在 0/1/3 之间变过 ⇒ **它不跟设备本体走**。
     *   用硬件常量当基准后，`eulerZ` 只表示「人脸相对**设备自然方向**的倾斜」，
     *   与屏幕当前转成什么样彻底解耦 —— 这也就是用户说的「要用绝对位置」。
     */
    private val sensorOrientation: Int,
    private val onFrame: (FaceFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // ACCURATE 的容差比 FAST 大（±75° vs ±60°），且耗时同量级 ⇒ 这里选 ACCURATE
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.2f)
            .enableTracking()
            .build()
    )

    /** 同时只允许一帧在跑，防排队 —— 与 KEEP_ONLY_LATEST 是同一道闸 */
    private val inFlight = AtomicInteger(0)

    /** 当前在途帧数（诊断用） */
    val inFlightNow: Int get() = inFlight.get()

    /**
     * 历史上 inFlight 的峰值。**长期 > 1 就是泄漏信号** ——
     * 一旦泄漏，之后每一帧都会在入口被判"已有帧在跑"而直接丢弃，
     * 现象是「相机明明开着，burst 却一帧都收不到」。
     */
    @Volatile
    var maxInFlight = 0
        private set

    private var lastAt = 0L

    /**
     * 最小帧间隔（ms），0 = 不限。**这是功耗账上最关键的旋钮** ——
     * 判定"脸朝哪边"根本不需要 30fps，人脸转 90° 至少也要 200ms。
     * 30 → 5fps 能把检测次数降到 1/6。
     */
    @Volatile var minIntervalMs: Long = 0L

    /** 最近一次真正被分析的时刻。**别人抢走相机时它会停住** —— 这是判断"被抢占"的可靠信号 */
    @Volatile var lastAnalyzedAtMs: Long = 0L
        private set

    /** 当前"最优额外旋转"。绝大多数帧用它一次就命中 */
    @Volatile private var bestExtra = 0

    /** 本帧试了几个角度 */
    @Volatile var lastTriedCount = 0; private set

    // ============== ★ 无人脸退避（省功耗的核心）==============
    /**
     * 连续没检出人脸的帧数。达到 [missBackoffFrames] 后进入退避。
     *
     * 为什么必须退避（2026-09-25 实测）：画面里没人脸时，原实现会把 4 个角度**全部试一遍**
     * 才死心，于是平均搜索次数被推到 2.6~3.0 次/帧 —— 而"手机放桌上没人看"恰恰是日常常态。
     * 最需要省电的场景反而最费电。
     */
    @Volatile var consecutiveMiss = 0; private set

    /** 连续多少帧无脸后进入退避 */
    @Volatile var missBackoffFrames = 5

    /** 退避中每 N 帧放一次全角度扫描 —— 否则"人脸真转了 90°"时热点角永远命中不了，死锁 */
    @Volatile var backoffProbeEvery = 10

    /** 是否处于退避（HUD 会显示） */
    @Volatile var backoffActive = false; private set
    private var sinceFullScan = 0

    /** 低置信帧累计（两路测量差过大） */
    @Volatile var lowConfFrames = 0L; private set

    /** 两路测量差超过这个度数就判低置信。实测正常帧 1~5°、异常帧 80° ⇒ 30° 是安全分界 */
    @Volatile var maxConsistencyDeg = 30f

    // ---- 累计统计，供 HUD 显示 ----
    @Volatile var totalFrames = 0L; private set
    @Volatile var facesFound = 0L; private set
    @Volatile var sumDetectMs = 0L; private set
    /** 累计"试了几个角度"——除以 totalFrames 就是平均搜索次数，直接对应功耗 */
    @Volatile var sumTriedCount = 0L; private set
    /** 热点角一次命中的次数 */
    @Volatile var hitByHot = 0L; private set

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (minIntervalMs > 0 && lastAt != 0L && now - lastAt < minIntervalMs) {
            image.close()
            return
        }
        if (inFlight.get() > 0) { image.close(); return }
        val nowInFlight = inFlight.incrementAndGet()
        if (nowInFlight > maxInFlight) maxInFlight = nowInFlight
        lastAnalyzedAtMs = now

        val interval = if (lastAt == 0L) 0L else now - lastAt
        lastAt = now
        val t0 = SystemClock.elapsedRealtime()

        // ★ 固定基准（硬件常量），不是 imageInfo.rotationDegrees —— 见类注释
        val rotation = sensorOrientation
        val media = image.image
        if (media == null) {
            inFlight.decrementAndGet(); image.close()
            return
        }

        val hot = bestExtra
        val full = intArrayOf(hot, (hot + 90) % 360, (hot + 180) % 360, (hot + 270) % 360)
        // 退避中：绝大多数帧只试热点角（1 次），每 backoffProbeEvery 帧才放一次全角度扫描
        val order = if (!backoffActive) {
            full
        } else {
            sinceFullScan++
            if (sinceFullScan >= backoffProbeEvery) { sinceFullScan = 0; full } else intArrayOf(hot)
        }
        attempt(image, media, rotation, order, 0, now, interval, t0)
    }

    /**
     * 按 [order] 逐个角度试，**第一个检出的就停**。
     * ⚠️ 递归点必须放在 `addOnCompleteListener` 里而不是 `addOnSuccessListener` 里 ——
     * 否则下一轮的 `process()` 会叠在上一轮还没收尾的时候，串行性就没了。
     */
    private fun attempt(
        image: ImageProxy,
        media: Image,
        baseRotation: Int,
        order: IntArray,
        idx: Int,
        now: Long,
        interval: Long,
        t0: Long
    ) {
        if (idx >= order.size) {
            // 四个角度全试过仍是空 ⇒ 判定"这帧没人脸"
            val dm = SystemClock.elapsedRealtime() - t0
            lastTriedCount = order.size
            sumTriedCount += order.size
            consecutiveMiss++
            if (consecutiveMiss >= missBackoffFrames) backoffActive = true
            totalFrames++
            sumDetectMs += dm
            try {
                onFrame(
                    FaceFrame(
                        now, Float.NaN, Float.NaN, 0, 0f, dm, baseRotation, interval,
                        extraRotation = order.last(), triedCount = order.size
                    )
                )
            } finally {
                // 同上：收尾必须在 finally 里，否则 onFrame 抛一次就永久泄漏 inFlight
                inFlight.decrementAndGet()
                image.close()
            }
            return
        }

        val extra = order[idx]
        val rot = ((baseRotation + extra) % 360 + 360) % 360
        var found: Face? = null
        var w = 0
        var h = 0
        val proceedToNext: () -> Unit = { attempt(image, media, baseRotation, order, idx + 1, now, interval, t0) }

        runCatching {
            val input = InputImage.fromMediaImage(media, rot)
            w = input.width
            h = input.height
            detector.process(input)
                .addOnSuccessListener { faces -> found = pickFace(faces, w, h) }
                .addOnCompleteListener {
                    val f = found
                    if (f != null) emit(image, f, extra, idx + 1, w, h, baseRotation, now, interval, t0)
                    else proceedToNext()
                }
        }.onFailure {
            Log.w(TAG, "detect threw at extra=$extra: ${it.message}")
            proceedToNext()
        }
    }

    private fun emit(
        image: ImageProxy,
        face: Face,
        extra: Int,
        tried: Int,
        w: Int,
        h: Int,
        baseRotation: Int,
        now: Long,
        interval: Long,
        t0: Long
    ) {
        val dm = SystemClock.elapsedRealtime() - t0
        bestExtra = extra
        lastTriedCount = tried
        sumTriedCount += tried
        if (tried == 1) hitByHot++
        // ★ 一旦找到脸就立刻退出退避，恢复正常搜索
        consecutiveMiss = 0
        backoffActive = false
        sinceFullScan = 0
        totalFrames++
        facesFound++
        sumDetectMs += dm

        // ★ 角度修正：真实 = 测量 + extra（eulerZ）；真实 = 测量 - extra（eyeRoll）
        val euler = OrientationDecider.angDiff(face.headEulerAngleZ + extra, 0f)
        val rawEye = eyeRollOf(face)
        val eye = if (rawEye.isFinite()) OrientationDecider.angDiff(rawEye - extra, 0f) else Float.NaN

        // ★ 两路交叉验证：eyeRoll 与 eulerZ 的符号天然相反（实测 -82.5 vs +87.1），
        //   所以要比的是 eulerZ vs (-eyeRoll)。正常帧差 1~5°，侧脸/糊帧差 36~81°。
        //   差太大不是"脸转过去了"，是"测错了" —— 这种帧宁可当没测到，交给时间保持兜底。
        val lowConf = eye.isFinite() && OrientationDecider.angDiffAbs(euler, -eye) > maxConsistencyDeg
        if (lowConf) lowConfFrames++

        // 必须在 finally 里收尾：若 onFrame 抛异常而这两行被跳过，inFlight 会永久停在 1，
        // 之后**每一帧**都在入口被丢掉且无法自愈（纯防御性写法，实测中未复现过）。
        // ⚠️ 更正一条旧注释：burst「超时 0 帧」的真实原因**不是** inFlight 泄漏，
        //    而是**相机权限没授予** —— CameraService 直接 REJECT，压根没有帧（2026-09-25 坐实，
        //    证据：dumpsys media.camera 里 "cannot open camera 1 without camera permission"）。
        try {
            onFrame(
                FaceFrame(
                    atMs = now,
                    eulerZ = euler,
                    eyeRoll = eye,
                    faceCount = 1,
                    faceArea = normalizedArea(face, w, h),
                    detectMs = dm,
                    rotationDegrees = baseRotation,
                    intervalMs = interval,
                    extraRotation = extra,
                    triedCount = tried,
                    lowConf = lowConf
                )
            )
        } finally {
            inFlight.decrementAndGet()
            image.close()
        }
    }

    private fun pickFace(faces: List<Face>, w: Int, h: Int): Face? {
        val ok = faces.filter { it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
        if (ok.isEmpty()) return null
        return ok.maxByOrNull { normalizedArea(it, w, h) }
    }

    private fun normalizedArea(f: Face, w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 0f
        val b = f.boundingBox
        return (b.width().toFloat() * b.height().toFloat()) / (w.toFloat() * h.toFloat())
    }

    /** 左右眼连线 → roll。任一眼睛缺失则返回 NaN（不要用 0 兜底，0 是合法角度） */
    private fun eyeRollOf(f: Face): Float {
        val le = f.getLandmark(FaceLandmark.LEFT_EYE) ?: return Float.NaN
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE) ?: return Float.NaN
        return OrientationDecider.rollFromEyes(le.position.x, le.position.y, re.position.x, re.position.y)
    }

    fun close() {
        runCatching { detector.close() }
    }

    companion object {
        const val TAG = "FaceRotate"
    }
}

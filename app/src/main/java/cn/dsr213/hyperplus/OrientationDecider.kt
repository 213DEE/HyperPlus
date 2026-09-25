package cn.dsr213.hyperplus

import kotlin.math.abs

/**
 * 人脸 roll 角 → 屏幕方向 的判定器。
 *
 * 这是整个项目里**唯一真正的算法核心**：纯逻辑、不依赖 Android，可离线单测。
 *
 * ============================================================================
 * ★ 2026-09-25 重构：从「相对状态机」改为「绝对映射」
 * ============================================================================
 *
 * 旧实现把「当前方向 committed」当锚点，要求新方向与它偏离超过
 * `45° + hysteresis(22°) = 67°` 才允许切换。这在数学上等价于一个带 67° 死区的
 * 量化器 —— 后果是人脸已经歪了 60°，屏幕纹丝不动，用户体感就是
 * **「没完全跟着我的脸转」**。
 *
 * 新实现是纯绝对量：每一帧直接把归一化角度量化到最近的 90° 扇区，
 * **不再参考「上一步是什么」**。唯一的非线性只剩一层时间保持（holdMs），
 * 它只延迟提交、不改变目标值，所以不会再把方向拖出偏差。
 *
 * ============================================================================
 * 坐标系与符号 —— 完整推导链（本条为 2026-09-25 实测后重写，禁止再凭感觉改）
 * ============================================================================
 *
 * 1) **图像基准必须锚在「设备本体」，不能锚在「屏幕当前方向」**　✅实证
 *    CameraX 的 `ImageProxy.imageInfo.rotationDegrees` 参考系是**绑定相机那一刻**
 *    的显示方向。实测（817 帧 CSV）：`rot_deg` 恒为 270，而同期 `sys_rot`
 *    在 0/1/3 之间变过 ⇒ 它**不会**跟着设备本体走。
 *    ⇒ 改用 `CameraCharacteristics.SENSOR_ORIENTATION`（硬件常量）作为固定基准。
 *      从此 `eulerZ` 只表示「人脸相对**设备自然方向**的倾斜」，与屏幕转成什么样无关。
 *
 * 2) 角度修正（实测 1:1 线性）　✅实证
 *    `eulerZ ≈ -(图像顺时针额外旋转量)` ⇒ `真实 eulerZ = 测量值 + extra`
 *    `eyeRoll ≈ +(图像顺时针额外旋转量)` ⇒ `真实 eyeRoll = 测量值 - extra`
 *
 * 3) 两路交叉验证：`eulerZ ≈ -(eyeRoll)`，正常帧差 1~5°　✅实证
 *
 * 4) 定义 θ = 人脸「上方向」在正立图中相对图像上方**顺时针**的夹角。
 *    由 2)、3) 得 `eulerZ ≈ -θ`。
 *
 * 5) 定义 θrel = 人脸上方向相对**设备顶部**的顺时针夹角
 *    （= 人脸相对设备的绝对倾斜，与重力无关）：
 *      · 头正立、设备竖屏            ⇒ θrel = 0
 *      · 设备**逆时针**转 90°(顶部朝左) ⇒ θrel = +90
 *      · 设备**顺时针**转 90°(顶部朝右) ⇒ θrel = 270
 *    由 1) 得 θ ≡ θrel。
 *
 * 6) 目标：屏幕内容相对人脸正立。
 *    `Settings.System.USER_ROTATION` 语义：ROTATION_90 = 渲染顺时针转 90°
 *    = 设备物理**逆时针**转 90°（Android 官方：绘制旋转方向与设备物理旋转相反）
 *    ⇒ **u = θrel / 90**。
 *
 * 7) 所以理论上 `sign` 应为 **-1**（`u = -eulerZ/90`）。
 *    ⚠️ 但同一台机器的实测数据里，`decided` 与系统重力判定 `sys_rot` 在 8 个
 *    稳定段中有 7 段一致（含 1↔1、3↔3、0↔0）—— **支撑 sign = +1**。
 *    两者冲突，说明还有一项未被推导覆盖的机型/镜像因素。❓**待标定确定**
 *    ⇒ 因此 sign 与 offsetDeg **不做硬编码猜测**，默认 (+1, 0°)，由用户用
 *      「方向校准」两步点按钉死，并持久化到 SharedPreferences。
 *
 * 输出用 Surface.ROTATION_* 的整数值：
 *   0 = 竖屏（设备自然方向）　1 = 设备逆时针转 90°　2 = 倒置　3 = 设备顺时针转 90°
 */
class OrientationDecider(
    /** roll 符号翻转：吸收前置镜像 / ML Kit 左右定义 / 传感器朝向造成的机型差异 */
    @Volatile var sign: Int = 1,
    /** 相位偏移（度）：把「人脸正立」对应的 roll 归零 */
    @Volatile var offsetDeg: Float = 0f,
) {

    data class Config(
        /** 指数平滑系数（0~1，越大越跟手） */
        val emaAlpha: Float = 0.35f,
        /**
         * 时间保持：新扇区要连续成立这么久才提交。
         *
         * ★ 350ms → 250ms：绝对映射下 holdMs 只承担「挡边界抖动」这一个职责，
         *   不再承担「减少误切」—— 因为已经没有死区了。250ms ≈ 4 帧 @16ms，
         *   足以滤掉角度在 45° 边界上的一次抖动，又不会让人觉得迟钝。
         */
        val holdMs: Long = 250L,
        /**
         * 边界滞回带（度）—— 切换点从 45° 推到 `45 + hysteresisDeg`，回切点压到
         * `45 - hysteresisDeg`。
         *
         * ★ 为什么需要一个「小」滞回而不是零滞回：
         *   纯 `round()` 的切换点正好在 45°。噪声让角度在 45° 附近来回时，扇区会在
         *   相邻两格间反复跳。holdMs 能挡住「来回跳」，却挡不住「噪声整体偏向一侧
         *   并持续 250ms」的偶发误切。7° 的滞回带就是给边界留的抗噪余量 ——
         *   既保持「过 45° 就翻」的跟手感，又不至于迟钝。
         *   （旧实现是 22° 滞回叠加在「相对当前方向」的锚定上，等效死区 67°，
         *     那是迟钝而不是抗噪，所以被换掉。）
         */
        val hysteresisDeg: Float = 7f,
        /**
         * 超过这么久没脸，判定为未知。burst 模式下两次触发间隔实测 1.5~2.5s，
         * 若太短则每个新 burst 的头一两帧必然被判 lost。
         */
        val faceLostMs: Long = 2000L,
        /** 低于该置信度的人脸直接丢弃 */
        val minScore: Float = 0.4f,
        /** 一帧里有几张脸时以谁为准：true = 面积最大的那张 */
        val pickLargest: Boolean = true,
    )

    var cfg = Config()

    enum class State {
        /** 没有脸，方向未知 */
        UNKNOWN,

        /** 方向稳定 */
        STABLE,

        /** 已进入新扇区，正在等 holdMs 确认 */
        CANDIDATE,
    }

    data class Result(
        val rawRoll: Float,
        val smoothedRoll: Float,
        /** 归一化后的角度，0..360（符号/偏移已应用） */
        val normalized: Float,
        /** 0/1/2/3；-1 = 未知 */
        val rotation: Int,
        val state: State,
        /** 相对当前扇区中心的夹角（度） */
        val deviation: Float,
        val faceCount: Int,
    )

    private var smoothed: Float? = null

    /** 已提交的方向（绝对扇区编号） */
    private var committed: Int = -1

    /** 正在等待确认的候选扇区 */
    private var pending: Int = -1
    private var pendingSince: Long = 0L
    private var lastFaceAt: Long = 0L

    /** 标定用：最近一次归一化角，便于 UI 显示「当前落在哪个扇区」 */
    val lastNormalized: Float get() = lastNorm
    private var lastNorm: Float = Float.NaN

    // ---------------------------------------------------------------- 桥接

    /** roll（原始测量）→ 归一化角 0..360，符号与偏移在此处应用 */
    fun angleOf(roll: Float): Float {
        var n = (roll * sign + offsetDeg) % 360f
        if (n < 0f) n += 360f
        return n
    }

    /** 绝对映射核心：把归一化角量化到**最近的** 90° 扇区（不加任何死区） */
    fun sectorOf(norm: Float): Int = (((norm + 45f) / 90f).toInt()) % 4

    /**
     * 带**小滞回**的绝对量化 —— 真正用在 [update] 里的那个。
     *
     * 与 [sectorOf] 的唯一差别：只有当角度相对**当前扇区中心**偏离到
     * `45 + hysteresisDeg` 时才允许换格。注意它仍然只看**绝对角度**，
     * 不参考"上一步是怎么来的"，所以不会产生旧实现那种累积死区。
     */
    private fun sectorWithHysteresis(norm: Float): Int {
        val raw = sectorOf(norm)
        if (committed < 0 || raw == committed) return raw
        val dev = angDiffAbs(norm, committed * 90f)
        return if (dev >= 45f + cfg.hysteresisDeg) raw else committed
    }

    // ---------------------------------------------------------------- 主循环

    fun update(
        roll: Float?,
        nowMs: Long,
        faceCount: Int = if (roll == null) 0 else 1,
    ): Result {
        if (roll == null) {
            val lost = lastFaceAt > 0 && nowMs - lastFaceAt > cfg.faceLostMs
            return Result(
                rawRoll = Float.NaN,
                smoothedRoll = smoothed ?: Float.NaN,
                normalized = Float.NaN,
                rotation = if (lost) -1 else committed,
                state = if (lost) State.UNKNOWN else State.STABLE,
                deviation = Float.NaN,
                faceCount = faceCount,
            )
        }
        lastFaceAt = nowMs

        // 1) 平滑：走最短角路径，避免 ±180 处跳变
        val s = smoothed?.let { it + cfg.emaAlpha * angDiff(roll, it) } ?: roll
        smoothed = s

        // 2) 归一化 + 3) 绝对量化（带小滞回，见 sectorWithHysteresis）
        val norm = angleOf(s)
        lastNorm = norm
        val sector = sectorWithHysteresis(norm)

        if (committed < 0) {
            // 首次判定：直接绝对落定，不做任何等待（等下去只会让 HUD 空着）
            committed = sector
            pending = -1
            return resultOf(roll, s, norm, faceCount)
        }

        if (sector == committed) {
            // 回到已提交扇区，撤销候选。这是唯一的「回退」路径，且完全由绝对角决定。
            pending = -1
            return resultOf(roll, s, norm, faceCount)
        }

        if (sector != pending) {
            pending = sector
            pendingSince = nowMs
            return resultOf(roll, s, norm, faceCount, State.CANDIDATE)
        }

        if (nowMs - pendingSince >= cfg.holdMs) {
            committed = sector
            pending = -1
            return resultOf(roll, s, norm, faceCount)
        }
        return resultOf(roll, s, norm, faceCount, State.CANDIDATE)
    }

    private fun resultOf(
        raw: Float,
        s: Float,
        norm: Float,
        faces: Int,
        st: State = State.STABLE,
    ) = Result(
        rawRoll = raw,
        smoothedRoll = s,
        normalized = norm,
        rotation = committed,
        state = st,
        deviation = angDiffAbs(norm, committed * 90f),
        faceCount = faces,
    )

    // ---------------------------------------------------------------- burst 收尾

    /**
     * burst 收尾裁定 —— burst 模式下必须有这个出口。
     *
     * 相机采完 N 帧就释放，不再有新帧喂进来；若此刻正停在 CANDIDATE，
     * 候选将永远无法完成。绝对映射下候选本身已经是一个**确定的绝对扇区**，
     * 所以只要它稳定了 [minPendingMs]，就可以直接采纳。
     *
     * 另外要求「本轮确实见过脸」，否则返回 UNKNOWN/-1 ——
     * **绝不允许拿陈旧方向去写屏幕**（宁可不判，不可乱判）。
     */
    fun settle(nowMs: Long, minPendingMs: Long = 250L): Result {
        if (pending >= 0 && nowMs - pendingSince >= minPendingMs) {
            committed = pending
            pending = -1
        }
        val fresh = lastFaceAt > 0 && nowMs - lastFaceAt <= cfg.faceLostMs
        return Result(
            rawRoll = Float.NaN,
            smoothedRoll = smoothed ?: Float.NaN,
            normalized = lastNorm,
            rotation = if (fresh) committed else -1,
            state = if (fresh && committed >= 0) State.STABLE else State.UNKNOWN,
            deviation = Float.NaN,
            faceCount = 0,
        )
    }

    // ---------------------------------------------------------------- 标定

    /**
     * 标定第 1 步：把「人脸正立」对应的 roll 均值 [rollMean] 定义为 0°。
     * offset = -rollMean * sign（保证 sign 切换后竖屏基准仍然成立）
     */
    fun calibrateBaseline(rollMean: Float) {
        offsetDeg = -rollMean * sign
        reset()
    }

    /**
     * 标定第 2 步：用户把手机**逆时针转 90°**（顶部朝左）、头保持正立，
     * 此时期望 `θrel = +90`（即归一化角应落在 90°），据此定 sign。
     *
     * 判据：`Δ = angDiff(rollAxis, rollBaseline)`
     *   Δ > 0 ⇒ sign = +1 ；Δ < 0 ⇒ sign = -1
     *
     * @return 是否标定成功（两点太近则视为无效）
     */
    fun calibrateAxis(rollBaseline: Float, rollAxis: Float): Boolean {
        val d = angDiff(rollAxis, rollBaseline)
        if (abs(d) < 30f || abs(d) > 150f) return false
        sign = if (d > 0f) 1 else -1
        offsetDeg = -rollBaseline * sign
        reset()
        return true
    }

    fun reset() {
        smoothed = null
        committed = -1
        pending = -1
        pendingSince = 0L
        lastFaceAt = 0L
        lastNorm = Float.NaN
    }

    companion object {
        /** a - b，归一到 (-180, 180] */
        fun angDiff(a: Float, b: Float): Float {
            var d = (a - b) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        fun angDiffAbs(a: Float, b: Float): Float = abs(angDiff(a, b))

        /** 由左右眼坐标算头部 roll（度）。用于和 ML Kit 的 eulerZ 互相交叉验证。 */
        fun rollFromEyes(lx: Float, ly: Float, rx: Float, ry: Float): Float =
            Math.toDegrees(kotlin.math.atan2((ry - ly).toDouble(), (rx - lx).toDouble())).toFloat()
    }
}

package cn.dsr213.hyperplus

import kotlin.math.abs

/**
 * 「多角度搜索该采纳哪一个角度」的规则 —— **纯逻辑，可离线单测**。
 *
 * ============================================================================
 * 为什么需要这条规则（2026-09-25 用户实测报「竖屏切横屏有概率变成相反的横屏」）
 * ============================================================================
 *
 * 背景：ML Kit 的检测器只认接近正立的脸（本项目实测：±45° 能检、±75° 勉强、±90° 检不到），
 * 所以 [FaceAnalyzer] 会把图像额外转 `extra`（0/90/180/270 之一）再检测，
 * 命中后按 `真实角度 = 测量值 + extra` 还原。
 *
 * ★ 这个还原公式**只在"图像里的脸接近正立"时才成立**（实测是在 +45° 上标定的 1:1 线性关系）。
 *
 * 原实现是「**第一个检出就采纳、立刻停**」。反例（可算）：
 * 人脸在基准图里转了约 100° 时，最优的 extra 是 270（把脸摆到约 10°），
 * 但**次优的 180 会让脸落在约 80°** —— 而 ACCURATE 模式的容差恰好能"勉强检出"。
 * 如果上一帧的热点角正好是 180，它会被**先试到并采纳**：
 * 此时模型的 Z 估计已经越出可靠区间（80° 附近本来就是 ±90 模糊带），
 * 还原出来的角度会整体**反 180°** ⇒ 屏幕切到**相反的横屏**。
 * 因为只在容差边缘才发生，所以表现为「**有概率**」而不是"每次都错"。
 *
 * ⇒ 规则：**不采纳"勉强认出"的那一个，改采纳"摆得最正"的那一个。**
 *   - 命中时若脸看起来已经够正（|tilt| ≤ [trustDeg]），立刻采纳 —— 稳态仍只检测一次，不增功耗；
 *   - 否则继续试下一个角度，最后取**倾斜最小**的那个。
 *
 * 附带好处：`extra` 从此是**确定性**的（恒为最优角），
 * 判定器拿到的 roll 落在模型可靠区间内，±90° 附近的估计噪声同时被压掉一截。
 */
class ExtraPicker<P>(
    /** 命中时人脸"看起来"歪过的角度不超过它就采纳（度）。取 45：四个候选角互隔 90°，最优角必然 ≤ 45° */
    private val trustDeg: Float = 45f,
) {

    /**
     * 一次命中。
     *
     * @param extra 这次检测所用的额外旋转角
     * @param apparentTiltDeg 该角度下 ML Kit 报的 `headEulerAngleZ` —— 即"这张脸在这个图里看起来歪了多少度"
     * @param payload 随命中一起带走的东西（检测器用它带脸与图像尺寸；单测里用 Unit）
     */
    data class Hit<P>(val extra: Int, val apparentTiltDeg: Float, val payload: P) {
        /** 比较用的倾斜绝对值；NaN 视为最差，避免拿"测不出来"当最优 */
        val tiltAbs: Float get() = if (apparentTiltDeg.isFinite()) abs(apparentTiltDeg) else Float.MAX_VALUE
    }

    private var best: Hit<P>? = null

    /** 已验证过的最优命中（一个都没命中时为 null） */
    fun result(): Hit<P>? = best

    /**
     * 记一次命中。
     *
     * @return `true` = 这一次已经"摆得足够正"，可以停止搜索，直接采纳；`false` = 继续试下一个角度
     */
    fun record(extra: Int, apparentTiltDeg: Float, payload: P): Boolean {
        val h = Hit(extra, apparentTiltDeg, payload)
        val b = best
        // 只有更"正"的才替换 —— 相等时保留先试到的（顺序即热点优先，尽量少换）
        if (b == null || h.tiltAbs < b.tiltAbs) best = h
        return h.tiltAbs <= trustDeg
    }

    fun reset() {
        best = null
    }
}

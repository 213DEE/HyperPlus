package cn.dsr213.hyperplus

/**
 * 「一轮多帧投票」的纯计数核心 —— **不依赖 Android，可离线单测**。
 *
 * ============================================================================
 * 为什么把它单独拎出来
 * ============================================================================
 *
 * 用户要求「每一轮识别多次，按命中最多的方向旋转」。这条行为的全部难点都在
 * **票数判据的边界**上，而不是在"数数"本身：
 *
 *  - 平票（2:2）时该不该动？—— 不该。平票说明这一轮看不准，动就是赌。
 *  - 只采到 5 帧（脸丢得多）时该不该动？—— 不该。样本太少，噪声就是全部。
 *  - 正在转弯、票面 6:2 时该不该动？—— 该动。新方向已经领先，死等 60% 反而迟钝。
 *
 * 这三条恰恰是最容易写错、又**最难在真机上复现**的部分（真机里"脸转多少度"不可控）。
 * 抽成纯函数之后，可以用单测把边界钉死（见 `app/src/test/…/VoteTallyTest.kt`）。
 *
 * ★ 定论判据（两条任一成立）：
 *   ① 绝对多数 —— 获胜方至少 [minVotes] 票，且占比 ≥ [ratioMin]；
 *   ② 明显领先 —— 获胜方至少 [minVotes] 票，且是第二名的 2 倍以上。
 *
 * ★ [minVotes] 的取值史（别只看数字，要看它对应的前提）：
 *   - 4：真机会"早采纳再纠正"（日志里 `user_rotation=1` 紧接着 400ms 后 `=3`）；
 *   - 6：为了压掉那个抖动（2026-09-25 上午）；
 *   - **3：当前值**（2026-09-25 下午，用户反馈"响应太慢、手机都反转 180° 了画面没反应"）。
 *
 *   ⚠️ 降到 3 之所以安全，是因为**当初制造抖动的成因已经被拆掉了**，不是把门槛硬降：
 *     那个抖动来自"投票把转动**之前**的旧姿势帧也算了进去"（真机票面 `0:4 1:9 3:3`，
 *     而上一轮刚把方向写成 0）。现在预热帧 + EMA(0.55) 把那批坏帧挡在外面，
 *     3 票 = "连续三帧看到同一个方向"，可信度并不低。
 *   ⚠️ 这里只负责**数票**。"票不够时怎么办"由上层决定：整轮定不了论时，
 *     `AdaptiveEngine` 会先试「末段共识」，再退回状态机 —— 见其 [recentConsensus]。
 *
 * 输出方向用 `Surface.ROTATION_*` 的整数值（0/1/2/3），与全项目一致。
 */
class VoteTally(
    /** 定论所需的最少票数（低于它一律不判） */
    private val minVotes: Int = 3,
    /** 绝对多数阈值，0..1 */
    private val ratioMin: Float = 0.6f,
) {

    private val counts = IntArray(4)

    /** 有效票总数 */
    var total: Int = 0
        private set

    /** 得票最多的方向；-1 = 一张票都没有 */
    var winner: Int = -1
        private set

    /** 获胜方的票数 */
    var leaderVotes: Int = 0
        private set

    /** 是否已足以定论（见类注释里的两条判据） */
    var confident: Boolean = false
        private set

    fun reset() {
        counts.fill(0)
        total = 0
        winner = -1
        leaderVotes = 0
        confident = false
    }

    /** 投一票；非法方向（不在 0..3）直接忽略，不计数 */
    fun add(sector: Int) {
        if (sector !in 0..3) return
        counts[sector]++
        recount()
    }

    /** 获胜方得票占比 0..1；没有票时为 0 */
    fun ratio(): Float =
        if (total <= 0 || winner !in 0..3) 0f else counts[winner].toFloat() / total

    /** 票面，形如 `0:1 3:9`（只列出得票 > 0 的格）；没有票时是空串 */
    fun text(): String =
        (0..3).filter { counts[it] > 0 }.joinToString(" ") { "$it:${counts[it]}" }

    private fun recount() {
        var best = -1
        var bestN = 0
        var secondN = 0
        var sum = 0
        for (i in 0..3) {
            val n = counts[i]
            sum += n
            if (n > bestN) {
                // 旧的第一名降级为第二名 —— 顺序不能反，否则 secondN 会错
                secondN = bestN
                bestN = n
                best = i
            } else if (n > secondN) {
                secondN = n
            }
        }
        total = sum
        leaderVotes = bestN
        winner = if (bestN > 0) best else -1
        // ① 绝对多数：bestN*100 >= sum*60（整数比较，避免浮点误差）
        // ② 明显领先：bestN >= 2*secondN（secondN 为 0 时天然成立）
        val majority = bestN * 100 >= sum * (ratioMin * 100).toInt()
        val dominant = bestN >= 2 * secondN
        confident = bestN >= minVotes && (majority || dominant)
    }

    companion object {
        /**
         * **末段共识（纯逻辑，可离线单测）**：最后 [need] 票若完全一致，返回那个扇区，否则 -1。
         *
         * ★ 为什么需要它（用户报的真实症状："手机都反转 180 度了，画面都没反应"）：
         *   转 180° **必然路过中间的扇区**，于是整轮票被切散成 `0:4 1:3 2:3 3:2` 这种形状 ——
         *   任何一个扇区都攒不到 [minVotes] 票 ⇒ 整轮永远定不了论。可"**最后几张脸朝哪**"
         *   本来就足够判断方向了，这个信息被整轮平均掉了。
         *
         * 顺序无关：判据只看"这 [need] 份观测是不是全一样"，与它们在环形缓冲里的位置无关。
         *
         * @param recent 环形缓冲（只读，不修改）
         * @param filled 缓冲里已写入的有效元素个数（< [need] 时一律不下结论）
         */
        fun recentConsensus(recent: IntArray, filled: Int, need: Int): Int {
            if (need <= 0 || filled < need || recent.size < need) return -1
            val first = recent[0]
            if (first !in 0..3) return -1
            for (i in 1 until need) {
                if (recent[i] != first) return -1
            }
            return first
        }
    }
}

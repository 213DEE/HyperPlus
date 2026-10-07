package cn.dsr213.hyperplus

/**
 * ★★ **R1：自适应「读不到环境」⇒ 临时降级半自动**的状态机（2026-10-05）。
 *
 * ============================ 它解决什么 ============================
 * 暗光 / 没把脸对着镜头时，自适应的表现是「**转手机完全没反应**」——
 * 用户分不清"功能坏了"和"环境不允许"。降级之后引擎改走半自动（弹按钮、不开相机），
 * 用户点一下就能转；环境一恢复立刻切回自适应。
 *
 * ============================ 为什么是独立的纯 object ============================
 * ① **可测**：状态转移是这一功能里唯一会"判错"的东西（判早了抖、判晚了没反应），
 *    而它本来只是"几个数进、几个数出"。抽出来就能像 [OrientationFusion] /
 *    [SplitThresholds] 那样直接在 JVM 上钉死，不必拖一台真机 + 相机 + 暗房。
 * ② **原子**：四个字段本来分散在引擎里各自 `@Volatile`，读写之间可以被打断
 *    （比如"刚置 downgraded=true，probeDue 还没推"）。并成一个不可变 [State] 之后，
 *    引擎一次只读/写一个引用，**不会读到半更新**。
 *
 * ============================ 与用户配置的关系 ============================
 * ⛔ **它一个字都不碰 `AppPrefs`**。降级只是"引擎这一段时间的行为"，用户配的档
 * （全局档 / 逐应用档）原封不动 —— 与"安全开关的真值只能放在 App prefs"是同一条纪律。
 * 用户关掉这个功能（[AppPrefs.r1Fallback]）时，引擎连状态一起清（见 `noteAdaptiveEnv`）。
 *
 * ============================ 三件事别做错 ============================
 * ① **恢复只要一轮成功**，不做"连续 M 轮"的滞回 —— 代价不对称：多等一轮 = 用户多转一次
 *    手机没反应；早一轮恢复最坏只是下一轮又被降回去。防抖交给 [R1_RECOVER_COOLDOWN_MS]。
 * ② **探测窗口必须重新计时**：降级后引擎走半自动、**不开相机** ⇒ 若没有
 *    [R1_PROBE_INTERVAL_MS] 这个窗口，就永远感知不到环境恢复（死锁）。
 *    而且**每一轮都要推**（无论成败），否则窗口一直开着、降级再也生不了效。
 * ③ **[isDowngradedAt] 必须纯读**：它在 `AdaptiveEngine.mode()` 的路径上，
 *    而那个函数被十几处调用（前台门刷新、界面展示…）。带副作用的话那些地方会
 *    **顺手把探测窗口消耗掉** —— 症状是"降级之后再也没回到自适应"，极难查。
 */
object AdaptiveFallback {

    /**
     * 状态。四个字段与 [AdaptiveEngine] 里那一个 `@Volatile` 引用一一对应。
     *
     * @param missStreak 连续多少轮"相机出帧、但一帧可用人脸都没有"。任一轮读到脸就清零。
     * @param downgraded 是否已判定"环境不可用"
     * @param probeDueMs 下次允许**放行一次真正的自适应探测**的时刻。
     *   语义："在它之前 ⇒ 按降级走（半自动、不开相机）；到它之后 ⇒ 放行一次"。
     * @param recoverUntilMs 退出降级后的冷却截止时刻：在此之前不再重新进入降级
     */
    data class State(
        val missStreak: Int = 0,
        val downgraded: Boolean = false,
        val probeDueMs: Long = 0L,
        val recoverUntilMs: Long = 0L,
    )

    /** 这一轮发生了什么（引擎据此决定要不要打日志 / 收按钮）。 */
    enum class Kind {
        /** 什么都没变（含"还在往阈值上攒"和"已经在降级里、这轮只是推窗口"） */
        NONE,

        /** 刚进入降级 */
        ENTER,

        /** 刚从降级里出来 */
        EXIT,
    }

    /**
     * 一轮采样结束后的状态转移（**纯函数**，同样输入必得同样输出）。
     *
     * ★ 调用点在 `AdaptiveEngine.finishBurst` 里 `frames > 0` 之后 —— 那一句已经保证
     *   "相机是通的、确实出帧了"。所以 [hadFace] 为 false 只可能是**环境**问题
     *   （太暗 / 没对着镜头 / 脸被挡），而不是相机故障（那种走 `gaveUp`，不调这里）。
     *
     * @param s 当前状态
     * @param now 现在（`SystemClock.elapsedRealtime()`）
     * @param hadFace 本轮有没有出现过可用人脸
     */
    fun step(s: State, now: Long, hadFace: Boolean): Pair<State, Kind> {
        if (hadFace) {
            // ★ 恢复：**一轮成功就切回**（理由见类注释 ①）。
            //   同时把两个时间戳都往后推：
            //   - `probeDueMs`：这一轮很可能就是"窗口放行"的那一轮，不推的话窗口会一直开着；
            //   - `recoverUntilMs`：冷却从现在起算（防"忽明忽暗"来回抖）。
            return State(
                missStreak = 0,
                downgraded = false,
                probeDueMs = now + R1_PROBE_INTERVAL_MS,
                recoverUntilMs = now + R1_RECOVER_COOLDOWN_MS,
            ) to if (s.downgraded) Kind.EXIT else Kind.NONE
        }

        if (s.downgraded) {
            // 已经在降级里 ⇒ 只把探测窗口往后推（这一轮就是窗口放行的那一次）。
            return s.copy(probeDueMs = now + R1_PROBE_INTERVAL_MS) to Kind.NONE
        }

        val streak = s.missStreak + 1
        // 没攒够：**保留 streak**（下一次接着数）。
        if (streak < R1_MISS_ROUNDS) {
            return s.copy(missStreak = streak) to Kind.NONE
        }
        // ★ 冷却期内即使攒够了也不进 —— 这是用户拍板那句"带滞回 + 冷却"里的冷却。
        //   ⚠️ 但 streak 要**照样保留**：冷却一过、下一次再失败就直接进，不必重新数两轮。
        if (now < s.recoverUntilMs) {
            return s.copy(missStreak = streak) to Kind.NONE
        }
        return State(
            missStreak = streak,
            downgraded = true,
            probeDueMs = now + R1_PROBE_INTERVAL_MS,
            recoverUntilMs = s.recoverUntilMs,
        ) to Kind.ENTER
    }

    /**
     * [now] 这一刻是否处于降级（**纯读**，⛔ 不许在这里推进任何窗口 —— 理由见类注释 ③）。
     *
     * 两个条件缺一不可：判过"环境不可用"，**且**还没到探测窗口。
     * 到了窗口就返回 false ⇒ `mode()` 放行一次真正的自适应（那是"环境恢复就自动切回"
     * 唯一的手段）；那一轮的成败由 [step] 记账、并把窗口重新推到 [R1_PROBE_INTERVAL_MS] 之后。
     */
    fun isDowngradedAt(s: State, now: Long): Boolean = s.downgraded && now < s.probeDueMs
}

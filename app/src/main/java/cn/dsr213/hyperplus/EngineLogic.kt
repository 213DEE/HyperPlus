package cn.dsr213.hyperplus

/**
 * 引擎的**无状态判据函数**（原 `AdaptiveEngine` 的 `companion object` 的一部分，2026-10-03 搬出）。
 *
 * ★ 语言层面的保证（这是"搬走不改变行为"的根据，不是推断）：
 *   它们原先住在 `companion object` 里，而 **companion 的函数在编译期就无法访问实例状态** ——
 *   所以能把它们搬出来这件事本身，就说明它们不依赖任何运行时状态。
 *
 * ⚠️ 单测的调用方式随之改变：调用时**不再需要 `AdaptiveEngine.` 前缀**（测试与它们同包）。
 *
 * ⛔ 别在这里读 `AdaptiveEngine` 的实例字段 —— 会编译不过（这正是它的守门作用）。
 */

/**
 * 半自动：**同一个目标方向**在这个时间内只弹一次（ms）。
 *
 * 语义是"用户连续把手机转到同一姿态、又不去点按钮 ⇒ 他大概不想要那个方向"，
 * 于是不重复骚扰。但原来取 10 秒太长，会制造一个假的"失灵"：
 * 用户转到横屏 → 没点 → 转回竖屏 → 又转回横屏，10 秒内那个"横屏"还被记着
 * ⇒ 按钮不弹，用户看到的就是"时灵时不灵"（2026-09-28 降到 3000ms）。
 *
 * ★★ 它必须**恒等于按钮自己的存活时间** —— 这正是"按钮刚自己消失，
 *   用户再转到同一方向就又能弹"的成立条件，也就是「**消失即可再次召唤**」。
 *
 *   所以它**从常量改成了函数**（2026-09-28，用户把按钮时长做成 1~60s 可调）：
 *   若还写死 3 秒，用户把时长调到 30 秒之后，按钮**还挂在屏幕上时**同方向就会
 *   再次触发 ⇒ 按钮被反复"续命"，再也等不到自己消失。两者必须同源。
 *
 * ★ 注意这条**只对"没点"的情况生效**：用户一旦点了按钮，
 *   [applySemiRotation] 会把记忆清掉（见那里的 `semiHintAtMs = 0`）。
 */
internal fun semiRepeatSuppressMs(): Long =
    AppPrefs.hintMs.value
        .coerceIn(AppPrefs.HINT_MS_MIN, AppPrefs.HINT_MS_MAX)
        .toLong()

/** 方向编号 → 人话（与界面 [cn.dsr213.hyperplus.ui] 的叫法保持一致） */
internal fun rotName(rot: Int): String = when (rot) {
    0 -> "竖屏"
    1 -> "横屏（设备逆时针转 90°）"
    2 -> "倒竖屏"
    3 -> "横屏（设备顺时针转 90°）"
    else -> "未判定"
}

/**
 * 半自动的**目标方向选择**（纯函数，直接单测）。
 *
 * @param gravityFresh 重力读数是否够新（见 `DeviceOrientationTrigger.gravityFresh`）
 * @param lastCode `device_orientation`(TYPE_27) 的最近读数：
 *                 `0..3` = 有效象限；`-1` = 从未收到；`-2` = 本次由陀螺兜底路触发
 *
 * ★ 两条判据的顺序**不能调换**（重力优先）：
 *   重力是 ~15Hz 的**连续**量，反映"此刻"；TYPE_27 是 on-change，只在象限跳变时更新 ——
 *   一旦手机停在某个象限里，它的值就冻住了。若反过来优先用 TYPE_27，
 *   会出现"手机已经转回来、按钮却还指着上一个方向"。
 *
 * ★ 重力不可用时（手机**完全平放**，平面分量 < [minPlanarG]）才退回 TYPE_27：
 *   平放时重力算出来的是噪声方向，而 TYPE_27 至少还停在上一个有意义的象限上。
 *
 * ★ 两条都拿不到 ⇒ `null`，**宁可不弹按钮，也不拿噪声当方向**
 *   （误弹一个方向错的按钮，比什么都不弹更糟：用户点下去屏幕会转错）。
 */
internal fun semiTarget(
    gravityFresh: Boolean,
    ax: Float,
    ay: Float,
    lastCode: Int,
    minPlanarG: Float = GRAVITY_MIN_PLANAR_G,
): Int? {
    if (gravityFresh) {
        OrientationFusion.gravitySectorOrNull(ax, ay, minPlanarG)?.let { return it }
    }
    if (lastCode in 0..3) return lastCode
    return null
}

/**
 * 「这次触发到底该不该弹按钮」（纯函数，直接单测）。
 *
 * 四道闸，按**代价大小**排序（错一次就白骚扰用户一次）：
 *  ① **静默期** [cooldownUntil]：[SEMI_COOLDOWN_MS] / 点击后 [SEMI_TAP_COOLDOWN_MS]；
 *  ② 姿态**判不出**（`target == null`，手机完全平放）或**目标与当前屏幕一致**
 *     （没有可确认的事）；
 *  ③ **180° 反转**：新目标与上次弹过的目标恰好是"同轴的两端"
 *     （竖 ↔ 倒竖 / 横 ↔ 倒横），且在 [flipSuppressMs] 内 ⇒ 不弹。
 *     这条治的正是用户报的「转 180° 时 90° 弹一次、180° 又弹一次」——
 *     而且**不会让用户失去确认机会**：真到 180° 时按钮本来就还挂在屏幕上，
 *     [RotateHintOverlay.show] 对"已在显示"是原地换方向、不重新出场。
 *  ④ **同一方向重复**：刚弹过同一个方向、用户没理 ⇒ [repeatSuppressMs] 内不再弹。
 *
 * ⚠️ ③ 与 ④ 的先后在语义上等价（都只是抑制），但 ③ **不能**与 ② 交换 ——
 *   ② 是"根本没有东西可确认"，那是更强的结论。
 *
 * ⚠️ 每一条都只影响"弹不弹"，**不影响"能不能转"** —— 半自动真正写方向的动作
 *   在 [applySemiRotation] 里，那条路上没有任何"抑制"。
 */
internal fun shouldShowSemiHint(
    now: Long,
    cooldownUntil: Long,
    target: Int?,
    current: Int,
    lastTarget: Int,
    lastHintAt: Long,
    repeatSuppressMs: Long,
    /** 180° 反转的抑制窗口。默认同 [repeatSuppressMs]（= 按钮存活时长）；留成参数是为了能单测边界 */
    flipSuppressMs: Long = repeatSuppressMs,
): Boolean {
    if (now < cooldownUntil) return false
    if (target == null) return false
    if (target == current) return false
    // ★ 180° 反转抑制（2026-09-29，用户报「转 180° 时 90° 弹一次、180° 又弹一次」）：
    //   真到了 180°，按钮本来就还挂在屏幕上（[RotateHintOverlay.show] 对"已在显示"
    //   是原地换方向、不重新出场）⇒ 这里"不弹"不会让用户失去确认机会。
    //   ⚠️ `lastTarget >= 0` 这个前提不能省：-1 是"从没弹过"的占位值，
    //      而 isHalfTurn(-1, …) 在加了范围夹紧后本来就返回 false（见那里），
    //      这里再显式写一次，是为了让"没弹过就绝不可能被这条挡住"这个事实**读得出来**。
    if (lastTarget >= 0 && isHalfTurn(target, lastTarget) &&
        now - lastHintAt < flipSuppressMs
    ) {
        return false
    }
    if (target == lastTarget && now - lastHintAt < repeatSuppressMs) return false
    return true
}

/**
 * 两个方向是不是「**同轴的两端**」—— 竖 ↔ 倒竖、横 ↔ 倒横（相差 180°）。
 *
 * 判据用奇偶：`Surface.ROTATION_*` 里 0(竖)/2(倒竖) 都是偶、1(横)/3(倒横) 都是奇 ——
 * **同奇偶 ⇒ 同一个轴的两端**（差 180°）；不同奇偶 ⇒ 差 90°，那是另一次有效旋转。
 *
 * ⚠️ 取值必须夹在 `0..3`：调用方传进来的正常是 `Surface.ROTATION_*`，
 *   但 `-1`（"从没弹过"的占位）与任何越界值都必须返回 **false** ——
 *   否则会出现"从没弹过、却因为 `-1` 而被抑制"这种荒唐结果（单测钉了它）。
 */
internal fun isHalfTurn(a: Int, b: Int): Boolean =
    a != b && a in 0..3 && b in 0..3 && (a % 2) == (b % 2)

/**
 * 「这次收到的**清除不可控名单**请求该不该真的执行」—— 抽成纯函数是为了能单测。
 *
 * 三条判据，缺一不可：
 *  ① [primed] = `false` ⇒ **冷启动第一次读数，只记账不动作**。
 *     那个值很可能是"历史上某次点击"留下的（引擎处理完**不会**改它，
 *     因为它写不了 App 的私有文件）⇒ 引擎每次重启都会重新读到它。
 *     不挡的话，用户攒下的记录每重启一次就被清空一次。
 *  ② [req] 为空 ⇒ 从来没人点过清除，无事可做。
 *  ③ [req] == [seen] ⇒ 还是上次那个值（StateFlow 会重放、2 秒轮询也会重复读到），
 *     不重复执行；否则名单会在"清 → 引擎又读到同一个值 → 又清"之间空转。
 *
 * ⚠️ ① 用的是**独立的布尔**，而不是"`seen` 是不是空串" —— 后者会让
 *   "读到的值确实是空"（还没人点过）与"还没读过"**撞成同一个值**，
 *   于是冷启动后的**第一次真实请求**被误当成"首次记账"丢掉。
 *   真机复现过：把该键从"不存在"改成"一个新时间戳"，引擎收到后纹丝不动。
 */
internal fun shouldRunClear(primed: Boolean, seen: String, req: String): Boolean =
    primed && req.isNotEmpty() && req != seen

/**
 * 前摄候选顺序：**上次成功过的 id 排第一**，其余按 id 升序。
 *
 * 抽成纯函数是为了能单测 —— 这里有两个必须成立的性质：
 *  ① 偏好 id 必须是列表**第一个**（否则它就没起"优先用"的作用，白持久化）；
 *  ② 结果必须**包含每个前摄恰好一次**（多一个 = 白白多烧一轮失败的 1.5s 超时，
 *     少一个 = 少一条退路）。
 *
 * ⚠️ 偏好 id 不在当前前摄列表里（换机型 / 系统更新后 id 变了）时**静默忽略**，
 *   退回纯 id 升序 —— 绝不能让一个过期的 id 卡在队首反复失败。
 */
internal fun orderFrontIds(fronts: List<String>, preferred: String): List<String> {
    val sorted = fronts.sorted()
    return if (preferred.isNotEmpty() && preferred in sorted) {
        listOf(preferred) + sorted.filter { it != preferred }
    } else {
        sorted
    }
}

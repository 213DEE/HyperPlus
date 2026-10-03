package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 半自动模式的**纯逻辑**测试（2026-09-28 新增）。
 *
 * 只覆盖两件事，但都是"错了用户立刻能感觉到"的那种：
 *  1. [semiTarget] —— **转到哪个方向**（错了 ⇒ 屏幕转错方向）；
 *  2. [shouldShowSemiHint] —— **该不该弹按钮**（错了 ⇒ 疯狂骚扰 或 该弹不弹）。
 *
 * ⚠️ 不覆盖的部分（诚实记录）：悬浮窗本身（`WindowManager.addView`、3 秒倒计时、
 *    点击回调）只能在真机上看 —— 单测跑不了 WindowManager。那一层靠
 *    "真机上转一下手机、看右下角有没有出现按钮"来验证。
 */
class SemiAutoTest {

    // ================================================================ 目标方向

    // 姿态样张直接取 [OrientationFusionTest] 里那 9 组**真机实测**读数
    // （已由平台自己的 TYPE_27 朝向码交叉验证），不另造数据。
    private val portraitUp = -1.37f to 8.73f      // 竖屏正立
    private val ccw90 = 8.16f to 0.35f            // 设备逆时针 90°（顶边朝左）
    private val cw90 = -8.43f to -0.68f           // 设备顺时针 90°（顶边朝右）

    private fun target(g: Pair<Float, Float>, lastCode: Int = -1) =
        semiTarget(true, g.first, g.second, lastCode)

    @Test
    fun gravityDrivesTargetWhenFresh() {
        assertEquals(0, target(portraitUp))
        assertEquals(1, target(ccw90))
        assertEquals(3, target(cw90))
    }

    /**
     * ★ 重力优先于 TYPE_27（顺序不能反）。
     *
     * 构造一个**矛盾**的输入：重力说 1（横屏左）、TYPE_27 还停在 3（上一个象限）。
     * 必须采信重力 —— 它是 15Hz 连续量；TYPE_27 是 on-change，手机停下来它就冻住了。
     * 这条挂了，症状就是"手机已经转回来了，按钮却还指着上一个方向"。
     */
    @Test
    fun gravityWinsOverStaleType27() {
        assertEquals(1, semiTarget(true, ccw90.first, ccw90.second, 3))
    }

    /** 手机**完全平放**：重力退化（平面分量不足）⇒ 退回 TYPE_27 的最近象限 */
    @Test
    fun flatDeviceFallsBackToType27() {
        assertEquals(2, semiTarget(true, 0.10f, 0.20f, 2))
    }

    /** 重力读数太旧 ⇒ 同样退回 TYPE_27 */
    @Test
    fun staleGravityFallsBackToType27() {
        assertEquals(1, semiTarget(false, ccw90.first, ccw90.second, 1))
    }

    /** 陀螺兜底触发（code = -2）且重力不可用 ⇒ **判不出**，宁可不弹也不猜 */
    @Test
    fun unknownWhenNothingUsable() {
        assertNull(semiTarget(false, 0f, 0f, -2))
        assertNull(semiTarget(false, 0f, 0f, -1))
        assertNull(semiTarget(true, 0.1f, 0.1f, -2))
    }

    /**
     * 这条锁的是一个**符号约定**：重力扇区必须与 `Surface.ROTATION_*` 同义。
     *
     * 历史坑（见 `OrientationFusion.gravitySector` 的注释）：初版写成 `atan2(-ax, ay)`，
     * 纸面上自洽，真机上**两个横屏整体对调**。半自动直接拿它当目标方向，
     * 所以这一位错了就是"点一下转反 180°"。这里用真机样张把它钉死。
     */
    @Test
    fun gravitySectorSemanticsMatchSurfaceRotation() {
        // 顶边朝左 ⇒ ROTATION_90（设备逆时针转了 90°）⇒ ax 为正
        assertEquals(1, semiTarget(true, 8.0f, 0.0f, -1))
        // 顶边朝右 ⇒ ROTATION_270 ⇒ ax 为负
        assertEquals(3, semiTarget(true, -8.0f, 0.0f, -1))
        // 倒置 ⇒ ROTATION_180 ⇒ ay 为负
        assertEquals(2, semiTarget(true, 0.0f, -8.0f, -1))
    }

    /** 平面分量阈值在**外侧**：恰好等于阈值时仍采信重力（`<` 才判为平放） */
    @Test
    fun planarThresholdBoundary() {
        assertNull(semiTarget(true, 1.49f, 0.0f, 7, minPlanarG = 1.5f))
        assertEquals(1, semiTarget(true, 1.5f, 0.0f, 7, minPlanarG = 1.5f))
    }

    /** 阈值必须是可注入的参数（否则测不了边界，也说明它被写死了） */
    @Test
    fun thresholdIsInjectable() {
        // ⚠️ lastCode 必须传 -1：传 0..3 的话，重力被判平放后会**兜底**到 TYPE_27，
        //    于是这个用例测的就不是"阈值"而是"兜底"了（第一版就是这么写错的）。
        assertNull(semiTarget(true, 3.0f, 0.0f, -1, minPlanarG = 5f))
        assertEquals(1, semiTarget(true, 3.0f, 0.0f, -1, minPlanarG = 1.5f))
    }

    // ================================================================ 该不该弹

    private fun show(
        now: Long = 10_000L,
        cooldownUntil: Long = 0L,
        target: Int? = 1,
        current: Int = 0,
        lastTarget: Int = -1,
        lastHintAt: Long = 0L,
    ) = shouldShowSemiHint(
        now = now,
        cooldownUntil = cooldownUntil,
        target = target,
        current = current,
        lastTarget = lastTarget,
        lastHintAt = lastHintAt,
        repeatSuppressMs = 10_000L,
    )

    /** 基本路径：姿态指向横屏、屏幕是竖屏、没在冷却里 ⇒ 弹 */
    @Test
    fun showsWhenDirectionDiffers() {
        assertTrue(show())
    }

    /** 屏幕已经是目标方向 ⇒ 没有可确认的事，不弹 */
    @Test
    fun skipsWhenAlreadyThatWay() {
        assertFalse(show(target = 0, current = 0))
    }

    /** 姿态判不出 ⇒ 不弹（绝不拿噪声当方向） */
    @Test
    fun skipsWhenTargetUnknown() {
        assertFalse(show(target = null))
    }

    /** 冷却期内一律不弹（普通冷却 / 点击后冷却共用这个闸） */
    @Test
    fun skipsDuringCooldown() {
        assertFalse(show(now = 10_000L, cooldownUntil = 10_001L))
        assertTrue(show(now = 10_001L, cooldownUntil = 10_001L))
    }

    /** 同一方向刚弹过且用户没理 ⇒ 抑制期内不弹 */
    @Test
    fun skipsRepeatOfSameTarget() {
        assertFalse(show(now = 10_000L, target = 1, lastTarget = 1, lastHintAt = 5_000L))
        // 过了抑制期就可以再弹
        assertTrue(show(now = 20_000L, target = 1, lastTarget = 1, lastHintAt = 5_000L))
    }

    /** 换了个方向 ⇒ 抑制规则**不适用**（用户改主意了，就该立刻响应） */
    @Test
    fun differentTargetIsNotSuppressed() {
        assertTrue(show(now = 10_000L, target = 2, lastTarget = 1, lastHintAt = 9_900L))
    }

    /**
     * ★ 零值陷阱：`lastHintAt = 0`（从没弹过）不能被当成"很久以前弹过"而误触发抑制。
     *
     * `now - 0 = now`，只要 `now < repeatSuppressMs` 就会被判成"刚弹过" ——
     * 真机上 `elapsedRealtime()` 在开机初期只有几千毫秒，于是**开机后第一次转手机不弹按钮**。
     * 这里把一个"从未弹过"的现场钉住：`lastTarget = -1` 与任何合法目标（0..3）都不同，
     * 抑制分支天然不会命中。
     */
    @Test
    fun neverShownBeforeIsNotSuppressed() {
        assertTrue(show(now = 500L, target = 0, current = 1, lastTarget = -1, lastHintAt = 0L))
    }

    /** 抑制期边界：差值**恰好等于**抑制期时放行（判据是 `<`，不是 `<=`） */
    @Test
    fun repeatSuppressBoundary() {
        // 差 9999ms < 10000ms ⇒ 仍在抑制期
        assertFalse(show(now = 14_999L, target = 1, lastTarget = 1, lastHintAt = 5_000L))
        // 差 10000ms，不再满足 `<` ⇒ 放行
        assertTrue(show(now = 15_000L, target = 1, lastTarget = 1, lastHintAt = 5_000L))
    }

    // ================================================================ 冷却刻度（用户诉求的回归防线）

    /**
     * ★★ 用户 2026-09-28 原话：「转完之后马上再转、就没有按钮了，一定要隔一会再按」。
     *
     * 根因：点击后的冷却曾是 **4000ms**。它当初是为了盖住"写 user_rotation 后的显示重配"
     * （量级 250~400ms）—— 顾虑成立，但**拿错了刻度**，多出来的 3.3 秒纯粹在拦用户。
     *
     * 这条用例把刻度钉住：点击后的冷却必须**短到用户察觉不到**。
     * 它是"行为需求"而不是"实现细节"——所以哪怕将来重写整套冷却机制，这个上界也该保留。
     */
    @Test
    fun tapCooldownIsSnappy() {
        assertTrue(
            "点击后冷却 = ${SEMI_TAP_COOLDOWN_MS}ms，太长 —— " +
                "用户点完想马上转到另一个方向时会被静默拦住（用户实际报过这个症状）",
            SEMI_TAP_COOLDOWN_MS <= 1_000L,
        )
    }

    /** 弹出后的冷却同理：它只是"一次转动跨多个采样点"的去抖余量，不该长到能拦住下一次转动 */
    @Test
    fun hintCooldownIsSnappy() {
        assertTrue(
            "普通冷却 = ${SEMI_COOLDOWN_MS}ms，超出一次转动的量级",
            SEMI_COOLDOWN_MS <= 1_000L,
        )
    }

    /**
     * ★★ "同一方向抑制期"必须**恒等于按钮自己的存活时间**（2026-09-28 起两者同源）。
     *
     * 两个方向都不许破：
     *   - 抑制期 **超过** 存活期 ⇒ 按钮已经自己消失了（用户认为"我可以再叫它出来"），
     *     转到同一方向它却不出来 —— 表现就是"时灵时不灵"；
     *   - 抑制期 **短于** 存活期 ⇒ 按钮**还挂在屏幕上**时同方向再次触发，
     *     按钮被反复"续命"，永远等不到自己消失。
     *
     * 所以按钮时长做成 1~60s 可调之后，这条约束从"常数比较"升级成了「**同源**」：
     * 抑制期直接读同一个配置值（[semiRepeatSuppressMs]）。
     */
    @Test
    fun repeatSuppressEqualsButtonLifetime() {
        assertEquals(
            "同方向抑制期必须与按钮存活期同源（都读 AppPrefs.hintMs）—— " +
                "否则用户一调时长，两者就脱钩",
            AppPrefs.hintMs.value.toLong(),
            semiRepeatSuppressMs(),
        )
        // 这个值本身也必须落在用户可配的边界内（滑条 1~60 秒）
        assertTrue(semiRepeatSuppressMs() >= AppPrefs.HINT_MS_MIN.toLong())
        assertTrue(semiRepeatSuppressMs() <= AppPrefs.HINT_MS_MAX.toLong())
    }

    /**
     * `hintMs` 恒为**整秒**，且恒在 [AppPrefs.HINT_MS_MIN] ~ [AppPrefs.HINT_MS_MAX] 内。
     *
     * ★ 这条不变量来自一次实测：界面滑条给回来的是**浮点秒**，直接乘 1000 落盘过
     *   `7927`，而界面文案是 `hintMs / 1000` 取整 ⇒ 出现「界面写 7 秒、实际等 7.927 秒」。
     *   归一收在 [AppPrefs.snapHintMs] 这个唯一入口上，所以这里直接测它，
     *   顺便把越界（含 `59999 + 500` 会进位到 60000 之外这种边界）一起钉住。
     */
    @Test
    fun hintMsIsAlwaysWholeSecondsInRange() {
        val samples = listOf(
            // 输入的毫秒值 → 期望归一结果
            7927 to 8000,          // 实测落盘过的那一个（四舍五入到 8 秒）
            1499 to 1000,          // 不到 1.5 秒 → 往下
            1500 to 2000,          // 正好半档 → 往上
            3000 to 3000,          // 默认值必须原样不动
            1 to 1000,             // 低于下界 → 夹到下界
            1000 to 1000,
            99999 to 60000,        // 高于上界 → 夹到上界
            60000 to 60000,
            59999 to 60000,        // 59999+500 进位越界 ⇒ 末尾那次 coerceIn 兜住
        )
        for ((input, expect) in samples) {
            assertEquals("snapHintMs($input)", expect, AppPrefs.snapHintMs(input))
        }
        // 抽样全区间：结果必须都是整秒、且都在边界内
        for (v in AppPrefs.HINT_MS_MIN..AppPrefs.HINT_MS_MAX step 137) {
            val out = AppPrefs.snapHintMs(v)
            assertEquals("snapHintMs($v) 不是整秒", 0, out % 1000)
            assertTrue("snapHintMs($v)=$out 越界", out in AppPrefs.HINT_MS_MIN..AppPrefs.HINT_MS_MAX)
        }
    }

    /**
     * 顺序关系：点击后的冷却必须比"普通冷却"长（它要盖住显示重配），
     * 且**短于按钮存活期的下界** —— 用户把时长调到最短（1 秒）时也不能被它盖满。
     */
    @Test
    fun cooldownOrderingIsSane() {
        assertTrue(SEMI_TAP_COOLDOWN_MS >= SEMI_COOLDOWN_MS)
        assertTrue(
            "点击冷却 ${SEMI_TAP_COOLDOWN_MS}ms 不短于按钮存活期下界 " +
                "${AppPrefs.HINT_MS_MIN}ms",
            SEMI_TAP_COOLDOWN_MS < AppPrefs.HINT_MS_MIN,
        )
    }

    // ================================================================ ★ 180° 反转（2026-09-29 用户报）

    /**
     * ★★ 用户 2026-09-29 原话：「现在 180° 旋转屏幕，可能会在 90° 的时候弹一次按键、
     * 180° 的时候再谈一次按键，需要优化一下」。
     *
     * 成因：转 180° 时重力扇区会**依次**经过 0 → 1 → 2，中间停在 90° 那一刻姿态合法、
     * 又和当时的屏幕方向不同 ⇒ 先弹一次；停到 180° 再弹一次。
     * 而两道已有闸都拦不住它：
     *   - `SEMI_COOLDOWN_MS` 只有 500ms，跨不过一次稍慢的转动；
     *   - 重复抑制要求 `target == lastTarget`，而 90° 与 180° 是**两个不同的目标**。
     *
     * 完整的修法是两道叠加：① 触发侧的**姿态稳定确认**（`SEMI_SETTLE_MS` 的 debounce，
     * 见 [AdaptiveEngine.onSemiTriggered]）—— 转动过程中一次都不弹；
     * ② 本用例钉的这道**兜底**：即使窗口没盖住（用户真在 90° 停了一会儿），
     * 停在 180° 时也不再弹**第二次**。
     *
     * ★ 用户不会因此失去确认机会：真到 180° 时按钮本来就还挂在屏幕上，
     *   `RotateHintOverlay.show` 对"已在显示"是**原地换方向**、不重新出场。
     */
    @Test
    fun halfTurnAfterHintIsSuppressed() {
        // 上次弹的是竖屏(0)，这次目标是横屏(1)：差 90° ⇒ 该弹（那是另一个诉求）
        assertTrue(show(now = 10_000L, target = 1, lastTarget = 0, lastHintAt = 9_900L))
        // 上次弹的是竖屏(0)，这次目标是倒竖屏(2)：同轴两端 ⇒ **抑制**
        assertFalse(show(now = 10_000L, target = 2, lastTarget = 0, lastHintAt = 9_900L))
        // 横屏的另一端同理
        assertFalse(show(now = 10_000L, target = 3, lastTarget = 1, lastHintAt = 9_900L))
        // 过了抑制窗口 ⇒ 正常放行（用户真的想把屏幕转 180° 时，转回去一次还是能拿到按钮）
        assertTrue(show(now = 30_000L, target = 2, lastTarget = 0, lastHintAt = 9_900L))
    }

    /** 90° 关系**不受** 180° 那条规则影响 —— 连续转两个不同方向时仍要立刻响应 */
    @Test
    fun quarterTurnIsNotSuppressedByHalfTurnRule() {
        assertTrue(show(now = 10_000L, target = 1, lastTarget = 0, lastHintAt = 9_900L))
        assertTrue(show(now = 10_000L, target = 2, lastTarget = 3, lastHintAt = 9_900L))
        // ⚠️ `current` 必须显式给一个**不等于 target** 的值：默认是 0，
        //    而下面这条的目标正好是 0 —— 不写就会撞上"屏幕已经是该方向"那道闸，
        //    测出来的失败与 180° 抑制毫无关系（第一版就是这么写错的）。
        assertTrue(
            show(now = 10_000L, target = 0, current = 2, lastTarget = 3, lastHintAt = 9_900L),
        )
    }

    /**
     * `isHalfTurn` 的真值表。
     *
     * ⚠️ 最容易错的是**越界值**：`-1` 是"从没弹过"的占位（`semiLastTarget` 的初值），
     *   它必须返回 false —— 否则开机后第一次转手机就会被莫名抑制。
     *   这比 [neverShownBeforeIsNotSuppressed] 那个"零值陷阱"更隐蔽：那条错在**时间戳**上，
     *   这条错在**方向值**上。
     */
    @Test
    fun halfTurnTruthTable() {
        // —— 同轴两端：差 180° ——
        assertTrue(isHalfTurn(0, 2))
        assertTrue(isHalfTurn(2, 0))
        assertTrue(isHalfTurn(1, 3))
        assertTrue(isHalfTurn(3, 1))

        // —— 差 90°：不同轴，不抑制 ——
        assertFalse(isHalfTurn(0, 1))
        assertFalse(isHalfTurn(1, 0))
        assertFalse(isHalfTurn(0, 3))
        assertFalse(isHalfTurn(3, 0))
        assertFalse(isHalfTurn(1, 2))
        assertFalse(isHalfTurn(2, 1))
        assertFalse(isHalfTurn(2, 3))
        assertFalse(isHalfTurn(3, 2))

        // —— 自己和自己：不是"反转" ——
        for (r in 0..3) assertFalse("isHalfTurn($r, $r) 应为 false", isHalfTurn(r, r))

        // —— ★ 越界 / 占位值一律 false ——
        assertFalse("占位值 -1 必须不抑制", isHalfTurn(-1, 0))
        assertFalse("占位值 -1 必须不抑制", isHalfTurn(0, -1))
        assertFalse("占位值 -1 必须不抑制", isHalfTurn(-1, 2))
        assertFalse(isHalfTurn(-1, -1))
        assertFalse(isHalfTurn(4, 6))
        assertFalse(isHalfTurn(0, 4))
        assertFalse(isHalfTurn(99, 2))

        // 反向调用与正向一致（纯函数该有的性质）
        for (a in -1..4) for (b in -1..4) {
            assertEquals(
                "isHalfTurn 不对称：($a, $b)",
                isHalfTurn(a, b),
                isHalfTurn(b, a),
            )
        }
    }

    /**
     * 姿态稳定确认窗口必须落在**可用区间**内。
     *
     * 两端都会出真问题，所以两个方向都钉：
     *   - 太短（< 150ms）⇒ 快速甩动时 90° 那一帧仍会被抓成一次弹出（用户报的就是它）；
     *   - 太长（> 500ms）⇒ 用户觉得"转完了按钮半天不出来" ——
     *     而 2026-09-25~28 两轮投诉的正是"不够跟手"。
     */
    @Test
    fun settleWindowIsInUsableRange() {
        assertTrue(
            "姿态稳定确认窗口 = ${SEMI_SETTLE_MS}ms，太短 —— 挡不住经过 90° 的那一帧",
            SEMI_SETTLE_MS >= 150L,
        )
        assertTrue(
            "姿态稳定确认窗口 = ${SEMI_SETTLE_MS}ms，太长 —— 用户会觉得按钮不出来",
            SEMI_SETTLE_MS <= 500L,
        )
    }

    /**
     * 读回等待必须**长于一次显示重配**，且不能长到用户能察觉。
     *
     * ★ 这条是 A 方案（实测不可控）的安全边界：读太早 ⇒ 把"能转的应用"记成不可控
     *   ⇒ **功能永久消失**（不对称代价的严重那一侧）。实测量级是 250~400ms。
     */
    @Test
    fun readbackDelayCoversDisplayReconfigure() {
        assertTrue(
            "读回等待 = ${SEMI_READBACK_MS}ms，短于一次显示重配（250~400ms）" +
                " —— 会把能转的应用误记成不可控",
            SEMI_READBACK_MS >= 400L,
        )
        assertTrue(
            "读回等待 = ${SEMI_READBACK_MS}ms，太长了（这是两段，判定最坏 2 倍）",
            SEMI_READBACK_MS <= 2_000L,
        )
    }
}

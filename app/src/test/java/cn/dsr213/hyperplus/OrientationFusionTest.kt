package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [OrientationFusion] 的边界测试。
 *
 * 这里钉的是**用户能直接感觉到、而真机上最难复现**的三类问题：
 *  ① 重力读数 → 屏幕方向的**符号/约定**（错了就整片横屏反向）；
 *  ② 用重力当尺子判**人脸链路符号位**的判据（含"竖屏帧必须一律不算数"）；
 *  ③ 量化边界（45° / 回绕）。
 */
class OrientationFusionTest {

    /** 重力加速度（m/s²），本机实测 9.8 量级 */
    private val G = 9.81f

    // ============================================================
    // ① gravitySector：四个姿势的约定
    // ============================================================

    /** 竖屏正立（设备自然方向）：加速度计读 (0, +g, 0) ⇒ 扇区 0 */
    @Test
    fun portraitIsZero() {
        assertEquals(0, OrientationFusion.gravitySector(0f, G))
    }

    /**
     * 设备**顺时针**转 90°（顶边朝右）：读 (−g, 0, 0) ⇒ 扇区 **3**（`ROTATION_270`）。
     *
     * ⚠️ 这里必须是 3 不是 1。初版写成 1（= 用 `atan2(-ax, ay)`），数学上"看着也自洽"，
     *   但会和平台自己的 `TYPE_27` 朝向码在**两个横屏上整体对调**，正是用户报的
     *   「横屏有概率反过来」。依据见下方 [matchesPlatformType27Samples]。
     */
    @Test
    fun clockwiseIsThree() {
        assertEquals(3, OrientationFusion.gravitySector(-G, 0f))
    }

    /** 倒置 180°：读 (0, −g, 0) ⇒ 扇区 2 */
    @Test
    fun upsideDownIsTwo() {
        assertEquals(2, OrientationFusion.gravitySector(0f, -G))
    }

    /** 设备**逆时针**转 90°（顶边朝左）：读 (+g, 0, 0) ⇒ 扇区 **1**（`ROTATION_90`） */
    @Test
    fun counterClockwiseIsOne() {
        assertEquals(1, OrientationFusion.gravitySector(G, 0f))
    }

    /**
     * ★ **真机采样回归** —— 用平台自己的朝向码当"标准答案"。
     *
     * 每一行都是 2026-09-25 从 `logcat` 抓的同一时刻的两个值：
     * `TYPE_27`（`SENSOR_TYPE_DEVICE_ORIENTATION`，**平台自己给的**朝向码）
     * 与当时的重力读数 `(ax, ay)`。9 组全部一致，本函数的符号就是据此定下来的。
     *
     * 这是本类里**唯一一处"标定级"的证据**：它比纸面推导可靠 ——
     * 因为符号错一位恰好只影响两个横屏（0/2 是镜像不变点），纸面上极难自查出来。
     *
     * ⚠️ 故意**不含** `(5.19, 5.13)` 那一组：那是设备正落在 45° 边界上的过渡帧，
     *   而 `TYPE_27` 是 on-change 型、还停在旧值 0 —— 属于边界延迟，不是约定错误。
     */
    @Test
    fun matchesPlatformType27Samples() {
        val samples = listOf(
            // ax, ay, 平台 TYPE_27
            Triple(-1.37f, 8.73f, 0),   // 竖屏（微倾）
            Triple(0.22f, 8.25f, 0),    // 竖屏
            Triple(1.36f, 7.29f, 0),    // 竖屏
            Triple(3.49f, 7.51f, 0),    // 竖屏（已倾 25°）
            Triple(8.16f, 0.35f, 1),    // 逆时针 90°（顶边朝左）
            Triple(7.61f, 0.37f, 1),    // 同上
            Triple(-9.13f, -0.00f, 3),  // 顺时针 90°（顶边朝右）
            Triple(-7.82f, -0.61f, 3),  // 同上
            Triple(-8.43f, -0.68f, 3),  // 同上
        )
        for ((ax, ay, code) in samples) {
            assertEquals(
                "ax=$ax ay=$ay 应与平台 TYPE_27=$code 一致",
                code, OrientationFusion.gravitySector(ax, ay),
            )
        }
    }

    /**
     * **俯仰不变性** —— 这条是"更精准"的关键性质之一。
     *
     * 人拿手机看视频时通常会后仰 30°~60°，此时重力在 y/z 之间分配；
     * 但只要**平面内的朝向**没变，扇区就必须不变。旧实现（只吃人脸 roll）
     * 在这个姿势下会因为视角变化而抖，本函数在数学上就不抖。
     */
    @Test
    fun pitchDoesNotChangeSector() {
        for (deg in intArrayOf(15, 30, 45, 60, 75, 85)) {
            val p = Math.toRadians(deg.toDouble()).toFloat()
            // 竖屏 + 后仰 p 度：平面分量变成 (0, G·cos p)
            assertEquals(
                "竖屏后仰 $deg° 仍应是扇区 0",
                0, OrientationFusion.gravitySector(0f, G * cos(p)),
            )
            // 横屏（逆时针 90°，顶边朝左）+ 后仰：绕 x 轴的俯仰不改变 x 分量
            assertEquals(
                "横屏后仰 $deg° 仍应是扇区 1",
                1, OrientationFusion.gravitySector(G, 0f),
            )
        }
        // 真实俯仰后重力还带 z 分量。函数签名只吃 x/y —— 这里把三维向量的模长
        // 还原一次，确认"丢掉 z"在数学上是安全的（roll 只依赖 x/y 的比值）。
        val p50 = Math.toRadians(50.0).toFloat()
        val ay = G * cos(p50)
        val az = G * sin(p50)
        assertEquals("三维向量模长应守恒", G.toDouble(), kotlin.math.hypot(ay.toDouble(), az.toDouble()), 0.01)
        assertEquals("后仰 50°（已丢 z 分量）仍应是扇区 0", 0, OrientationFusion.gravitySector(0f, ay))
    }

    /**
     * 平面内连续转一圈：扫过 0→1→2→3 四个区间，且**扇区编号随逆时针角度递增**。
     *
     * 这条守的是"编号方向"这个整体性质 —— 单独验四个姿势可以各自都对，
     * 但编号方向整体反过来（1↔3 对调）在单张姿势表里也能"自洽"，只有连续扫一圈才看得出。
     */
    @Test
    fun fullTurnVisitsFourSectorsInOrder() {
        // 设 θ = 设备相对自然方向**逆时针**转过的角度。
        // 世界「上」在设备坐标里被反向转了 −θ ⇒ 读数 (ax, ay) = G·(sinθ, cosθ)。
        // 代入 gravitySector 内部用的 φ = atan2(ax, ay) ⇒ φ = θ。
        // ⇒ 扇区 = sectorOf(θ)：θ=90° → 1、θ=270° → 3。
        fun sectorForPhysicalCcw(thetaDeg: Int): Int {
            val t = Math.toRadians(thetaDeg.toDouble())
            return OrientationFusion.gravitySector(
                (G * sin(t)).toFloat(), (G * cos(t)).toFloat(),
            )
        }

        assertEquals(0, sectorForPhysicalCcw(0))
        assertEquals(0, sectorForPhysicalCcw(30))      // 仍在 0 区间（<45°）
        assertEquals(1, sectorForPhysicalCcw(90))      // 逆时针 90°（顶边朝左）
        assertEquals(2, sectorForPhysicalCcw(180))
        assertEquals(3, sectorForPhysicalCcw(270))     // 逆时针 270° == 顺时针 90°
        assertEquals(0, sectorForPhysicalCcw(360))
        // 单调扫一圈：每个象限恰好各占一格，不能缺格也不能重复
        val seen = (0 until 360 step 5).map { sectorForPhysicalCcw(it) }.toSet()
        assertEquals("一圈必须正好覆盖 4 个扇区", setOf(0, 1, 2, 3), seen)
    }

    /** 完全平放（重力全在 z 轴）⇒ 平面分量是噪声，必须返回 null 让上层退回人脸单源 */
    @Test
    fun flatIsNull() {
        assertNull(OrientationFusion.gravitySectorOrNull(0f, 0f, 1.5f))
        assertNull(OrientationFusion.gravitySectorOrNull(0.4f, -0.7f, 1.5f))
    }

    /** 斜拿着（俯仰 70°，平面分量 ≈ 3.4）**不能**被当成平放 */
    @Test
    fun steepTiltIsNotNull() {
        val p = Math.toRadians(70.0).toFloat()
        assertEquals(
            "俯仰 70° 时平面分量仍有 3.4 m/s²，必须照常给出方向",
            0, OrientationFusion.gravitySectorOrNull(0f, G * cos(p), 1.5f),
        )
    }

    // ============================================================
    // ② mirror / verdict：拿重力当尺子判符号位
    // ============================================================

    /**
     * ★ `mirror` 必须**只对调 1↔3**，把 0/2 原样留下。
     *
     * 这一条钉的是整个 bug 的机理：`sign` 取反（内积同号/反号）在扇区上的表现
     * 恰好是"两个横屏对调、两个竖屏不变" —— 所以**竖屏永远看不出符号位错**，
     * 用户只能在横屏上撞见它。若哪天 mirror 被写成"整体加 2"（= 180° 翻转），
     * 那么竖屏也会反向，症状就完全不同了，这条单测会立刻拦住。
     */
    @Test
    fun mirrorSwapsOnlyLandscape() {
        assertEquals(0, OrientationFusion.mirror(0))
        assertEquals(3, OrientationFusion.mirror(1))
        assertEquals(2, OrientationFusion.mirror(2))
        assertEquals(1, OrientationFusion.mirror(3))
        // 自反：镜子照两次回到原处
        for (s in 0..3) assertEquals(s, OrientationFusion.mirror(OrientationFusion.mirror(s)))
    }

    /**
     * ★ 符号位判据的核心：**只有横屏的重力参照才有分辨力**。
     *
     * `mirror(0)=0`、`mirror(2)=2` ⇒ 竖屏/倒置时两种符号位给出同一个结果，
     * 该帧对"哪个符号对"零信息量。若把它记成 SAME，大量竖屏帧会把真正的
     * FLIPPED 信号稀释掉，符号位就永远纠正不过来了。
     */
    @Test
    fun verdictIgnoresPortraitGravityReference() {
        // 重力在竖屏象限 ⇒ 一律 AMBIGUOUS，无论实测是什么
        for (measured in 0..3) {
            assertEquals(
                "重力=0（竖屏）时不该给出任何符号位结论，实测=$measured",
                OrientationFusion.Verdict.AMBIGUOUS,
                OrientationFusion.verdict(measured, 0),
            )
            assertEquals(
                "重力=2（倒置）时同理，实测=$measured",
                OrientationFusion.Verdict.AMBIGUOUS,
                OrientationFusion.verdict(measured, 2),
            )
        }
    }

    /** 重力是横屏时：实测等于重力 ⇒ SAME；等于镜像 ⇒ FLIPPED；都不等 ⇒ 分辨不了 */
    @Test
    fun verdictDecidesOnlyOnLandscapeReference() {
        // 重力 = 1（逆时针 90°）
        assertEquals(OrientationFusion.Verdict.SAME, OrientationFusion.verdict(1, 1))
        assertEquals(OrientationFusion.Verdict.FLIPPED, OrientationFusion.verdict(3, 1))
        // 实测 0 或 2 时，既不是重力也不是它的镜像（头真的歪着/姿势在变）⇒ 不记票
        assertEquals(OrientationFusion.Verdict.AMBIGUOUS, OrientationFusion.verdict(0, 1))
        assertEquals(OrientationFusion.Verdict.AMBIGUOUS, OrientationFusion.verdict(2, 1))
        // 重力 = 3 时对称
        assertEquals(OrientationFusion.Verdict.SAME, OrientationFusion.verdict(3, 3))
        assertEquals(OrientationFusion.Verdict.FLIPPED, OrientationFusion.verdict(1, 3))
    }

    /** 缺任何一路数据都不能下结论（负数 = 该路不可用） */
    @Test
    fun verdictNeedsBothSources() {
        assertEquals(OrientationFusion.Verdict.AMBIGUOUS, OrientationFusion.verdict(-1, 1))
        assertEquals(OrientationFusion.Verdict.AMBIGUOUS, OrientationFusion.verdict(1, -1))
        assertEquals(OrientationFusion.Verdict.AMBIGUOUS, OrientationFusion.verdict(-1, -1))
    }

    /**
     * 翻转门槛必须"苛刻"：样本够多 **且** 反号证据 ≥3 倍。
     *
     * 理由：符号位是全局性的一位，翻错一次就是整片横屏反向。
     * 宁可多等几百帧，也不能被一次"头歪着看手机"的偶然采样带偏。
     */
    @Test
    fun flipRequiresStrongEvidence() {
        assertFalse("样本不够，不许翻", OrientationFusion.shouldFlip(0, 19, 20))
        assertFalse("刚刚够样本但优势不足（20:10）", OrientationFusion.shouldFlip(10, 20, 20))
        assertFalse("20 反 / 7 对 = 2.9 倍，仍不满足 3 倍", OrientationFusion.shouldFlip(7, 20, 20))
        assertTrue("20 反 / 6 对 ≈ 3.3 倍 ⇒ 该翻", OrientationFusion.shouldFlip(6, 20, 20))
        assertTrue("一边倒 ⇒ 该翻", OrientationFusion.shouldFlip(0, 30, 20))
    }

    /** 「已被数据验证」同样是苛刻判据 —— 界面上那句"已校准"必须有证据 */
    @Test
    fun confirmRequiresStrongEvidence() {
        assertFalse(OrientationFusion.signConfirmed(19, 0, 20))
        assertFalse(OrientationFusion.signConfirmed(20, 10, 20))
        assertTrue(OrientationFusion.signConfirmed(20, 6, 20))
        assertTrue(OrientationFusion.signConfirmed(40, 0, 20))
    }

    /**
     * ★ **端到端自证**：若符号位是错的，判据必须把它**判成 FLIPPED 并翻回来**；
     *   翻回来之后，同样的姿势必须变成 SAME。
     *
     * 这条不用真机就能把"纠错闭环"验完 —— 它模拟的就是 `noteSignEvidence` 的行为。
     */
    @Test
    fun wrongSignGetsDetectedAndCorrected() {
        // 场景：重力=1（横屏），真实 θrel 落在 1 号扇区，但 sign 是反的 ⇒ 实测得到镜像 3
        var sign = -1
        val measured = { real: Int -> if (sign > 0) real else OrientationFusion.mirror(real) }
        assertEquals(OrientationFusion.Verdict.FLIPPED, OrientationFusion.verdict(measured(1), 1))

        // 累计 20 个分辨帧后触发翻转
        var same = 0
        var flip = 0
        repeat(20) {
            when (OrientationFusion.verdict(measured(1), 1)) {
                OrientationFusion.Verdict.SAME -> same++
                OrientationFusion.Verdict.FLIPPED -> flip++
                OrientationFusion.Verdict.AMBIGUOUS -> Unit
            }
        }
        assertTrue("20 反 / 0 对 ⇒ 必须判定需要翻转", OrientationFusion.shouldFlip(same, flip, 20))

        // 翻转之后，同一姿势必须变成一致，并最终被"验证通过"
        sign = 1
        same = 0
        flip = 0
        repeat(20) {
            when (OrientationFusion.verdict(measured(1), 1)) {
                OrientationFusion.Verdict.SAME -> same++
                OrientationFusion.Verdict.FLIPPED -> flip++
                OrientationFusion.Verdict.AMBIGUOUS -> Unit
            }
        }
        assertEquals("翻转后不应再有反号帧", 0, flip)
        assertFalse("翻转后不该再触发翻转", OrientationFusion.shouldFlip(same, flip, 20))
        assertTrue("翻转后应被确认为已验证", OrientationFusion.signConfirmed(same, flip, 20))
    }

    // ============================================================
    // ③ 量化边界与工具函数
    // ============================================================

    /** 扇区边界必须落在 45° ± 90k，且 <45 归 0、=45 归 1 */
    @Test
    fun sectorBoundariesAreAtFortyFiveDegrees() {
        assertEquals(0, OrientationFusion.sectorOf(0f))
        assertEquals(0, OrientationFusion.sectorOf(44.9f))
        assertEquals(1, OrientationFusion.sectorOf(45f))
        assertEquals(1, OrientationFusion.sectorOf(134.9f))
        assertEquals(2, OrientationFusion.sectorOf(135f))
        assertEquals(2, OrientationFusion.sectorOf(224.9f))
        assertEquals(3, OrientationFusion.sectorOf(225f))
        assertEquals(3, OrientationFusion.sectorOf(314.9f))
        assertEquals(0, OrientationFusion.sectorOf(315f))
        assertEquals(0, OrientationFusion.sectorOf(359.9f))
    }

    /** 负角与超过 360° 的角都要能正确归一 */
    @Test
    fun sectorHandlesWrapAround() {
        assertEquals(3, OrientationFusion.sectorOf(-90f))
        assertEquals(2, OrientationFusion.sectorOf(-180f))
        assertEquals(1, OrientationFusion.sectorOf(-270f))
        assertEquals(1, OrientationFusion.sectorOf(450f))    // 450 = 90
        assertEquals(0f, OrientationFusion.norm360(360f), 0.001f)
        assertEquals(350f, OrientationFusion.norm360(-10f), 0.001f)
    }

    /** 交叉校验串：用于日志里比对「平台 TYPE_27 码」与「重力算出的扇区」 */
    @Test
    fun crossCheckTexts() {
        assertEquals("—", OrientationFusion.crossCheck(1, -1))
        assertEquals("一致", OrientationFusion.crossCheck(2, 2))
        assertEquals("差-1格", OrientationFusion.crossCheck(0, 1))
        assertEquals("差1格", OrientationFusion.crossCheck(1, 0))
        // 3 vs 0 在环上只差 1 格（不是 3 格）
        assertEquals("差-1格", OrientationFusion.crossCheck(3, 0))
        assertEquals("差1格", OrientationFusion.crossCheck(0, 3))
    }

    // ============================================================ 方向一致性闸

    /**
     * 闸的两个分支。**用例全部取自 2026-10-03 的真机现场**（内屏 + 小红书，
     * `_probe/_err180_2026-10-03_0824.log` / `_probe/_live_2026-10-03_0826.log`），
     * 不是凭空构造的 —— 改这条判据之前先把那两个日志读一遍。
     */
    @Test
    fun gaugeAcceptsWhenFaceAgreesWithGravity() {
        // 08:25:53 那一轮：重力 3、人脸 euler≈-93 ⇒ 扇区 3 ⇒ 写 user_rotation=3（正确，用户没报错）
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(3, 3))
        // 08:25:50 那一轮：重力 0、人脸扇区 0
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(0, 0))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(1, 1))
        // ★ 重力 2（手机真的被翻过来）⇒ 此时写 2 才是对的。用户原话：
        //   「永远不朝下 不等于 user_rotation≠2，要利用重力判断，**手机本身是可以翻转的**」
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(2, 2))
    }

    /**
     * ★★★ **「朝下」= 相对重力整整差 180°** —— 拿重力算，**不是**写死的 `2`。
     *
     * ⛔ 别用 `mirror()` 代替 `upsideDownOf()`：`mirror(0)=0`、`mirror(2)=2`，
     *   竖屏那两格会被漏掉，而竖屏的 0↔2 恰恰也是 180°。
     */
    @Test
    fun upsideDownIsHalfTurnFromGravityNotAFixedValue() {
        assertEquals(2, OrientationFusion.upsideDownOf(0))
        assertEquals(3, OrientationFusion.upsideDownOf(1))
        assertEquals(0, OrientationFusion.upsideDownOf(2))   // ⚠️ 与 mirror(2)=2 不同
        assertEquals(1, OrientationFusion.upsideDownOf(3))
    }

    @Test
    fun gaugeFlagsUpsideDown() {
        // 横屏那一对：重力 3 却要写 1（或反过来）⇒ 画面倒
        assertEquals(OrientationFusion.Gauge.UPSIDE_DOWN, OrientationFusion.gauge(1, 3))
        assertEquals(OrientationFusion.Gauge.UPSIDE_DOWN, OrientationFusion.gauge(3, 1))
        // 竖屏那一对：重力 0 却要写 2（或反过来）⇒ 画面倒 ★ 这一格最容易漏
        assertEquals(OrientationFusion.Gauge.UPSIDE_DOWN, OrientationFusion.gauge(2, 0))
        assertEquals(OrientationFusion.Gauge.UPSIDE_DOWN, OrientationFusion.gauge(0, 2))
    }

    /**
     * ★★ 相对重力差 **90°**（"朝左／朝右"）**一律放行** —— 用户只禁了"朝下"。
     *
     * 为什么必须放行：**躺着玩**就是典型情形 —— 重力说竖屏、而脸是横的，
     * 此时人脸链路给出的横屏值**才是对的**，它天然就与重力差 90°。
     * （第一版我拿"人不会倒立"去拦了这些，用户当场纠正。
     *   真机现场里 `重力=3 / 人脸扇区=2` 就是这一类。）
     */
    @Test
    fun gaugeAllowsNinetyDegreesOff() {
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(0, 1))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(1, 0))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(0, 3))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(3, 0))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(1, 2))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(2, 1))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(3, 2))
        // 08:21:51 那一轮：重力 3（横持）、人脸扇区 2 —— 差 90°，放行
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(2, 3))
    }

    /**
     * ★ 缺一路数据时**必须放行**（不能拦）。
     * 手机平放 / 读数过期 / 触发层没起来 ⇒ 重力这一路给不出方向，
     * 这时拦下来会把"没有重力的场景"整个变成不转。
     */
    @Test
    fun gaugeLetsThroughWhenGravityUnavailable() {
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(0, -1))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(1, -1))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(2, -1))
        assertEquals(OrientationFusion.Gauge.OK, OrientationFusion.gauge(3, -1))
    }
}

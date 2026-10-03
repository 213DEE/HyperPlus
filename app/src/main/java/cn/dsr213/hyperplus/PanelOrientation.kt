package cn.dsr213.hyperplus

import android.content.Context

/**
 * 「目标方向 ↔ 要写进 `Settings.System.USER_ROTATION` 的值」这一层换算。
 *
 * ============================================================================================
 * ★★ 2026-09-29 深夜**定案**：本机两块屏在这个坐标系里**没有**安装朝向差 —— 偏置恒为 **0**。
 * ============================================================================================
 *
 * 也就是说 [toPanel] / [toDevice] 现在是**恒等换算**，[handoffDelta] 恒为 0。这一层保留
 * 下来，是为了把"到底要不要换算"这个问题**只留一处可答的地方**（将来换机型若真有差，
 * 改的就是下面那两个常量），而不是因为它现在还在干活。
 *
 * ### 当初为什么会以为有偏置（错在哪）
 *
 * 2026-09-28 用户报「展开内屏默认方向倒置 180°」，我在 `dumpsys display` 里看到：
 * ```
 * 外屏 1168×1712  mStaticDisplayInfo{port=1, installOrientation=0}
 * 内屏 1672×2364  mStaticDisplayInfo{port=0, installOrientation=2}
 * ```
 * 就把 `installOrientation` 当成了"这块面板相对设备自然方向转了 180°"，于是推出
 * `R = (T - I) mod 4`，给内屏加了 2 的偏置。
 *
 * ⛔ **这个外推是错的**，两条独立证据：
 *
 * 1. **源码**（`DisplayRotation`，反编译 `_dr_android16-release.java`）：
 *    - `configure(width, height)` 里**只按"这块面板自己的宽高"**决定旋转原点；
 *      两块屏都是 `宽 < 高` ⇒ **两块的 `mPortraitRotation` 都是 `ROTATION_0`、
 *      `mLandscapeRotation` 都是 `ROTATION_90`** —— 框架的旋转语义在两块屏上**完全对称**。
 *    - `rotationForOrientation()`（决定最终落进 `USER_ROTATION` 的那个值）**全文不读
 *      `installOrientation`**。
 *    ⇒ `installOrientation` 是交给**显示管线（SurfaceFlinger / HWC）**在扫描输出时补偿的，
 *      **框架的旋转逻辑与 `USER_ROTATION` 这个键都看不到它**。我们的引擎同理：它拿到的
 *      就是已经统一过语义的值，**不该再补一次**。
 *
 * 2. **真机症状**（补一次偏置的后果，三个都指向"多转了 180°"）：
 *    - 用户报「内屏**无法转向正确方向，而是转到相反方向**」—— 我们写 `T - 2`，屏幕上就是
 *      `T + 2`；
 *    - 用户报「展开的时候是**正确**方向，然后才跳到错误的方向」—— 框架写的值是对的，
 *      我们随后照搬 +2 才把它转坏；
 *    - 而"展开默认倒置 180°"的真实成因是 **HyperOS 的每屏方向记忆**
 *      （`mUserRotationInner` 槽位），它会把**上一次**的方向灌回来。用户的旁证原话：
 *      「手动关闭旋转锁定之后展开方向就正常了」——关掉锁定＝框架按重力重算，就不倒置了。
 *      ⇒ 要治的是"换屏时把当前方向搬过去"（[AdaptiveEngine.onScreenFormChanged]），
 *      不是给某一块屏加静态偏置。
 *      ⚠️ 2026-09-30 再修一次：上面那个"槽位陈旧"的判断也不成立 —— 本机内屏槽位
 *      （`mUserRotationInner = ROTATION_270`）**正是用户展开后想要的横屏**。真正的病是
 *      **我们随后用"照搬旧屏值"把这个正确值覆盖掉了**（日志：`HyperOS 灌 3` →
 *      `我们照搬 0` → 屏幕变竖屏画布，用户横持时看到内容朝左躺倒）。
 *      现在改成"按当前握姿重算"，整套"照搬 + 锚点"已退役（见 [handoffDelta]）。
 *
 * ### ⚠️ 纪律
 * - **两块屏的竖屏都是 `ROTATION_0`**。同一个目标方向在两块屏上要写的值**必然相同**。
 *   若哪天发现"两屏写出的值不同"，那说明有人又把偏置加回来了 —— 那是 bug，不是特性。
 * - ⛔ 不要再从 `installOrientation` / `Display.DEFAULT_DISPLAY` / `dumpsys display` 的
 *   任何字段去"自动推出"偏置。这一层算错**不报错、不崩溃**，只是屏幕转到相反方向去，
 *   而真机验证一次要展开一次手机、还要肉眼判断正不正 —— 代价极高（已经为此绕了三轮）。
 */
internal object PanelOrientation {

    /**
     * 两块屏在 `USER_ROTATION` 坐标系里的安装朝向偏移。**恒为 0**，理由见类注释。
     *
     * ⚠️ 换机型时**不要**急着改这里。先自问：`DisplayRotation.configure()` 给这块面板算出的
     *   `mPortraitRotation` 是 0 吗？是 ⇒ 偏置就是 0，不用动。只有当某块面板**天然是横的**
     *   （宽 > 高，`mPortraitRotation` 会变成 90/270）时，它才可能有差。
     *   ⛔ `installOrientation` **不是**判据（那正是踩过的坑）。
     */
    private const val INSTALL_INNER = 0
    private const val INSTALL_OUTER = 0

    /** 缓存以**形态**为键：同一块屏上重复调用直接返回，换屏后自动重算 */
    @Volatile
    private var cachedForm: ScreenForm? = null

    @Volatile
    private var cachedValue: Int = 0

    /** 诊断：当前用的是哪一档、依据是什么（总线 `pofs` 旁会带上它） */
    @Volatile
    var diag: String = "未读取"
        private set

    /**
     * 当前这块屏的安装朝向偏移。
     *
     * ★ 形态每被问一次就**实量一次**（[ScreenForm.of] 读 WMS 的窗口尺寸，是 binder 调用、
     *   微秒级），只有"形态变了"才会重新查表。
     */
    fun installOffset(context: Context): Int {
        val form = ScreenForm.of(context)
        if (cachedForm == form) return cachedValue
        val v = offsetOf(form)
        cachedForm = form
        cachedValue = v
        diag = "form=${form.label} → I=$v（本机两块屏恒 0，见类注释）"
        return v
    }

    /**
     * 丢掉缓存。换屏（折叠 / 展开）时调用 —— 现在**只是让下次重算**（判定本身是实量的，
     * 不丢也不会错），留着是为了"换屏时明确把状态归零"这个可读意图。
     */
    fun invalidate() {
        cachedForm = null
        diag = "已失效，待重读"
    }

    /**
     * 这次换屏要让 `user_rotation` 挪多少格。
     *
     * 推导：`delta = I_old − I_new`，旧屏 = 新屏的 [ScreenForm.other]（形态翻转）。
     * 本机两个 `I` 都是 0 ⇒ **恒为 0**。
     *
     * ⛔⛔ **2026-09-30 起已退役：不再有任何生产调用点，别再照它推理。**
     *   它服务于"换屏时把旧屏的方向**照搬**过去"那套逻辑，而那套逻辑在真机上被证伪了：
     *   折叠屏展开几乎必然伴随一次 90° 转手（合着看外屏是竖持，展开看内屏是横持），
     *   所以"延续旧屏的值"这个语义本身不成立。现在换屏后是
     *   **按当前握姿重算**（见 `AdaptiveEngine.onScreenFormChanged`）。
     *
     *   ⚠️ 特别当心这条历史误读：`delta == 0` 曾被读成"两块屏没差别 ⇒ 照搬即可"，
     *   而它真正的含义只是"**两块屏的旋转语义相同**"——同值同义。语义相同 ≠ 该照搬：
     *   同一个值在两块屏上表达的是**同一个方向**，而用户换屏时换了握姿，需要的方向就变了。
     */
    fun handoffDelta(context: Context, newForm: ScreenForm = ScreenForm.of(context)): Int =
        normalize(offsetOf(newForm.other) - offsetOf(newForm))

    /** 形态 → 该屏的安装朝向。**唯一的映射点** */
    private fun offsetOf(form: ScreenForm): Int =
        if (form == ScreenForm.INNER) INSTALL_INNER else INSTALL_OUTER

    /**
     * 换屏搬运的**锚点筛选**：一个采样值，只有在"**确实来自旧屏**"时才能当锚点。
     *
     * ⛔⛔ **2026-09-30 起与 [handoffDelta] 一同退役：无生产调用点。**
     *   它服务的是"照搬旧屏的值"，而那套语义已被真机证伪（见 [handoffDelta] 的注释）。
     *   保留是因为"一个采样值到底来自哪块屏"这件事本身仍然是个真实的坑
     *   （`ActiveDisplay` 按尺寸认屏、巡检 2 秒一次会错过折叠窗口），将来若有人
     *   要做"跨屏比对"还会撞上它。**但现在没有任何调用点，别照它推理。**
     *
     * @param anchorValue 采到的面板值（可能是哨兵 −1）
     * @param anchorForm 这个值是在**哪块屏**上采的（null = 没采过）
     * @param newForm 要换到哪块屏。旧屏 = [ScreenForm.other]
     * @return 可用的锚点；**null = 不可用**
     *
     * ★ 为什么当初需要它（2026-09-29 用户报「展开有概率翻转 180°」/「变成顺时针 90°」）：
     *   锚点由巡检每 2 秒采一次，而"折叠一下马上再展开"时折叠态停留**常常不到 2 秒**
     *   ⇒ 那段时间一次都没采过，锚点还停在**上一块屏**的值上，却被当作"旧屏的值"搬走 ⇒
     *   搬过去的整体偏 `(那块屏的值 − 旧屏的值) mod 4`。
     *
     * ⚠️ 关键：**这种错从数值上看不出来** —— "另一块屏的值"同样是个合法的 0..3。
     *   所以判据必须是"它来自哪块屏"，不能只查值域。
     */
    fun handoffAnchor(anchorValue: Int, anchorForm: ScreenForm?, newForm: ScreenForm): Int? =
        anchorValue.takeIf { it in 0..3 && anchorForm == newForm.other }

    /**
     * 形态 → 安装朝向。给**手上已经有准确形态**的调用点用。
     *
     * ★ 为什么不一律走 [installOffset]：那个函数每次都**现量**形态，而换屏那一瞬间
     *   WMS 的窗口尺寸可能还没切过来 ⇒ 现量的可能是**旧屏**。换屏回调里手上那个 `form`
     *   是判定"形态真的变了"时给出的，才是权威。
     */
    fun offsetFor(form: ScreenForm): Int = offsetOf(form)

    /** 归一化到 0..3（容忍任何整数输入） */
    fun normalize(v: Int): Int = ((v % 4) + 4) % 4

    /**
     * 设备空间的目标方向 → 面板空间的 `USER_ROTATION` 值。
     *
     * `R = (T − I) mod 4`；本机 `I ≡ 0` ⇒ **恒等**。
     */
    fun toPanel(deviceRot: Int, offset: Int): Int = normalize(deviceRot - offset)

    /**
     * 面板空间的显示旋转 → 设备空间。`T = (R + I) mod 4`；本机 `I ≡ 0` ⇒ **恒等**。
     *
     * ★ 保留这层（而不是直接内联）的理由：引擎里所有"读回来 → 跟目标比"的地方都经过它，
     *   万一将来某台机器真有偏置，改一处就够，不用再去 17 个调用点上找。
     */
    fun toDevice(panelRot: Int, offset: Int): Int = normalize(panelRot + offset)
}

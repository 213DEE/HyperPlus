package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.util.Xml
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * 旋转策略**三态**（2026-09-28 由两态扩为三态，同日再给 [SYSTEM] 改名）。
 *
 * ★ 原先是两态（SYSTEM / ADAPTIVE），因为"多一个状态就多一份组合爆炸"。
 *   这一轮加上 [SEMI] 的理由是——**它和自适应走的根本不是同一条链路**：
 *
 * | | 判据 | 开相机？ | 谁拍板 |
 * |---|---|---|---|
 * | [SYSTEM] | 系统自己的重力传感器 | — | 系统 |
 * | [ADAPTIVE] | 前摄 + ML Kit 人脸投票 | 是 | 引擎自动 |
 * | [SEMI] | 设备姿态（重力/device_orientation） | **否** | **用户点击** |
 *
 * ⇒ [SEMI] 是"零相机、零推理、零资源争抢"的一条独立通路，
 *   代价是响应从"自动"降级为"要人点一下"。
 *
 * ============================ 关于「关闭旋转」这一档（2026-09-28 讨论结论）============================
 * 曾计划再补第四档「关闭旋转」。用户在 2026-09-28 明确否掉了这个加法，改为：
 *
 * > 「不要关闭旋转了，把关闭旋转和自动旋转结合成"跟随系统"，然后使用系统自带的
 * >   自动旋转和关闭旋转」
 *
 * ★ 理由（记录在此，避免以后又有人想加回来）：**"关闭旋转"本来就不是第三种状态，
 *   而是系统那份设置里的一个取值。** 系统设置里的 `ACCELEROMETER_ROTATION` 只有
 *   0/1 两态 —— "自动旋转"和"关闭旋转"是同一个开关的两个位置。
 *   把它拆成本应用的独立一档，语义上必然与"不介入、全交给系统"重复，
 *   而且会制造一个真正的坏状态：用户在本应用选了「关闭旋转」，回到系统设置却看到
 *   自动旋转是开着的 —— 两个地方各说各话。
 *   ⇒ 合并成一档 [SYSTEM]（界面显示「跟随系统」）：**系统的自动旋转开着就转、
 *     关着就不转，本应用一句话都不插**。改系统开关就是改这一档的行为，只有一个真值来源。
 *
 * ⚠️ 枚举常量名**仍是 `SYSTEM`**（没有跟着改成 `FOLLOW_SYSTEM`）：它是 prefs 里
 *   `rotate_mode_*` 的**落盘值**，改名会让老用户的配置读不出来（`enumValueOf` 抛异常 →
 *   静默退回默认档）。对外显示的名字走 [label]，两者本来就不必一致。
 */
enum class RotateMode {
    /**
     * **跟随系统**：本 App 完全不介入。
     *
     * 用系统自带的「自动旋转」开关 —— 开着就跟传感器转，关着就锁住。
     * 引擎不写任何方向、不接管方向盘、不开相机。
     */
    SYSTEM,

    /** 自适应旋转：按人脸方向决定屏幕方向 */
    ADAPTIVE,

    /** 半自动旋转：检测到设备转动就弹出按钮，用户点了才转 */
    SEMI;

    /**
     * **日志口径**的名字（引擎侧写 logcat / 事件流用）。
     *
     * ⚠️ 2026-10-03 多语言改造时**刻意保留**了它，而不是把它换成资源：
     *   ① 排查时读日志的人需要一个**固定说法**，不该随界面语言变；
     *   ② 引擎跑在 SystemUI 进程里，它**读不到**用户在 App 里选的语言
     *      （那是另一个进程、另一个 prefs 文件，见 `AppLocale` 的类注释）。
     *   ⛔ **别拿它显示在界面上** —— 那会让界面文字锁死在中文。界面用 [labelRes]。
     */
    val label: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            ADAPTIVE -> "自适应旋转（人脸）"
            SEMI -> "半自动旋转（点击确认）"
        }

    /**
     * **界面口径**的名字（多语言）。
     *
     * ⚠️ 与 [label] 是**同义的两份真值**：它们内容相同但形式不同（一个 String、一个资源 id），
     *   改文案要**两处一起改**。这份"冗余"是刻意的 —— 让日志不依赖 Context、
     *   让界面不锁死语言，两者都要，就只能有两份。
     */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.mode_label_system
            ADAPTIVE -> R.string.mode_label_adaptive
            SEMI -> R.string.mode_label_semi
        }

    /**
     * 短名，给**放不下长文案**的地方用（控制中心快捷开关的 subtitle、模式行的角标）。
     *
     * ★ 为什么要单独一份：QS Tile 的 `subtitle` 是一行小字，
     *   放 `SYSTEM.label`（"自适应旋转（人脸）"）会被系统截断成半句，
     *   截断处正好是括号里的关键信息。
     * ⚠️ 同 [label]：这是**日志口径**，界面用 [shortLabelRes]。
     */
    val shortLabel: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            ADAPTIVE -> "人脸"
            SEMI -> "半自动"
        }

    /** 短名的**界面口径**（多语言）。⚠️ 与 [shortLabel] 同义，改文案要一起改 */
    @get:StringRes
    val shortLabelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.mode_short_system
            ADAPTIVE -> R.string.mode_short_adaptive
            SEMI -> R.string.mode_short_semi
        }

    /**
     * 是否由本引擎**接管**方向盘 —— 即关掉系统的 `ACCELEROMETER_ROTATION`
     * 并由我们写 `USER_ROTATION`。
     *
     * ★ 两种介入模式都要接管，理由对 [SEMI] 尤其关键：
     *   半自动的用户动作是"转手机 → 点按钮"。如果**不**接管，系统自己的自动旋转会在
     *   用户转手机的那一刻就把屏幕转掉 —— 按钮还没弹出来事情就已经发生了，
     *   "点击确认"这个交互压根没有存在意义。
     */
    val engages: Boolean get() = this != SYSTEM

    /** 是否需要相机 / 人脸推理。只有自适应需要；半自动一条相机帧都不采 */
    val usesCamera: Boolean get() = this == ADAPTIVE
}

/**
 * 采集策略 —— 用户要求做成可选开关的那一项。
 *
 * 两者的本质差别是「相机什么时候 open」：
 *  - POWER_SAVING：只在触发后 open，采完立刻 close。省电，但每次触发都要付 open 代价
 *  - RESPONSIVE：相机常驻 open，触发后只处理新帧。延迟低，但相机管线本身耗电
 *
 * ★ 依据（MVP 实测）：相机管线本身约占 35% 单核，而检测负载（30fps 全检）约 57%。
 *   所以「相机常驻」的代价主要不在检测，而在 open 本身与管线占用。
 */
enum class CaptureStrategy {
    POWER_SAVING,
    RESPONSIVE;

    /**
     * **日志口径**（见 [RotateMode.label] 那段：引擎侧日志不本地化）。
     * ⛔ 别拿它显示在界面上，界面用 [labelRes]。
     */
    val label: String
        get() = when (this) {
            POWER_SAVING -> "省电优先"
            RESPONSIVE -> "响应优先"
        }

    /** **界面口径**的名字（多语言）。⚠️ 与 [label] 同义，改文案要一起改 */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            POWER_SAVING -> R.string.strategy_label_power
            RESPONSIVE -> R.string.strategy_label_responsive
        }

    /**
     * **界面口径**的说明（多语言）。
     *
     * ⚠️ 2026-10-03：原来这里是一个 `val summary: String`（中文），
     *   而它**只有界面一处用途**（`RotationPage` 的「省电优先」开关）⇒ 直接换成资源 id，
     *   不像 [label] 那样需要留两份（那一份有引擎日志在用）。
     */
    @get:StringRes
    val summaryRes: Int
        get() = when (this) {
            POWER_SAVING -> R.string.strategy_summary_power
            RESPONSIVE -> R.string.strategy_summary_responsive
        }
}

/**
 * 全局偏好：**三态模式（只有内屏一份）** + 采集策略 + 方向标定 + 孤儿接管防护。
 *
 * ★ 2026-09-29 收成一份：用户拍板「直接删除外屏的旋转增强，只保留内屏的旋转增强和应用豁免」。
 *   于是模式**只有一份真身** [modeInner]，两层流的分工不变：
 *   [modeInner] 给界面（显示 / 修改），[mode] 给引擎（**当前形态**的生效档）。
 *   ⇒ 引擎侧 17 处 `mode.value` 读点一行都不用改；"外屏恒不介入"被关在 [modeOf] 一处。
 *   ⚠️ 09-28 那版曾解耦成内外屏两份，**已废止** —— 别再加回 `modeOuter` 之类的东西，
 *   外屏既然不做增强，"外屏模式"就没有下游。
 *
 * ============================ 单引擎架构（2026-09-28 改造） ============================
 * 用户拍板：「App 一个引擎、SystemUI 一个引擎，用起来非常割裂，只保留 SystemUI 的引擎就行」。
 *
 * 于是 App 侧不再有任何引擎实例，**配置通道也随之重做** —— 原先两个进程靠
 * `Settings.System` 自定义键互传，而"往非公开键里写"只有特权包/root 身份能做，
 * App 是普通应用 ⇒ 只能借 root（整个工程**唯一**需要 root 的地方）。
 * 现在改成 LSPosed 官方的 [cn.dsr213.hyperplus.module.ModulePrefs]：
 *
 * | 数据 | 方向 | 走哪 | 要 root？ |
 * |---|---|---|---|
 * | 模式 / 策略 / 门控开关 / 交还开关 | App → 引擎 | App 的 prefs 文件 | ❌ |
 * | 标定请求（用户点按钮） | App → 引擎 | App 的 prefs 文件 | ❌ |
 * | 标定值 sign / offset | 引擎算出并持久化 | `Settings.System` | ❌（引擎是特权包） |
 * | 接管标志 / 交还目标 / 前摄 id | 引擎自己的账 | `Settings.System` | ❌ |
 * | 状态 / 心跳 / 标定结果 | 引擎 → App | `Settings.System` | ❌ |
 *
 * ⇒ **配置通道这条链路，两个方向都不再需要 root。** 分工原则一句话：
 *   **「用户能编辑的」走 App 的 prefs（App 写得动），「引擎自己算的账」走 Settings（引擎写得动）。**
 *
 * ⚠️ **更正（2026-10-03）**：这句的原文是"**两个方向都不再需要 root**"，**现在已不成立** ——
 *   2026-10-03 起「默认方向」改成 **App 借 root 直写内屏的方向槽位**（`user_rotation_inner`，
 *   见 [RootShell] 与 `docs/默认方向校准入口_直接改槽位_2026-10-03.md`）。
 *   那是**唯一例外**，也是用户拍板的取舍（本模块的装机前提是"有 LSPosed ⇒ 一定有 root"）。
 *   ⇒ 准确说法：**配置通道不需要 root；「默认方向」这一项需要。**
 *   ⛔ 别再把"完全不需要 root"写进任何面向用户的文案（`AboutPage` / `AndroidManifest` 已按此修正）。
 *
 * ★ 判据来源：`WRITE_SETTINGS` 与 `WRITE_SECURE_SETTINGS` 都**不能**让普通应用写非公开键
 *   （AOSP 里后一道 `enforceRestrictedSystemSettingsMutationForCallingPackage` 是无条件执行的），
 *   详见旧实现留下的实证记录（git 历史里的 `PrefsBridge` / `RootBridge` 注释）。
 *
 * ⚠️ **代价**：模块没在 LSPosed 里启用 = 引擎不存在 = 功能完全不可用，
 *   本 App 退化成"配置界面 + 状态显示"。这是用户明确选择的取舍。
 */
object AppPrefs {

    private const val TAG = "HyperPlusPrefs"

    /** 配置文件名。⚠️ 必须与 [cn.dsr213.hyperplus.module.ModulePrefs.PREFS_NAME] 一字不差 */
    const val NAME = "facerotate_prefs"

    /**
     * 标定请求的值：`"<时间戳>|<步骤>"`，步骤见 `EngineHost` 的 `doCalibrationRequest`。
     *
     * ★ 为什么带时间戳：配置通道是**文件级监听**（回调不告诉你哪个键变了），
     *   而引擎没有写 App 文件的权限 ⇒ **没法"复位"这个请求**。
     *   于是改成"每次请求都产生一个新值"，引擎靠"值变了"来判定有新请求，天然幂等。
     */
    const val CALIB_STEP_BASELINE = 1
    const val CALIB_STEP_AXIS = 2
    const val CALIB_STEP_CLEAR = 3

    /**
     * 「校准分步进度」的落盘键（2026-10-03）。
     *
     * ⚠️ 这两个键**引擎不读**（引擎的键全在 [PrefsBridge] 里）—— 它们纯粹是 App 的界面记账，
     *   写在 App 自己的 prefs 里只是因为 App 只有这一个文件。见 [calibStep1Done] 的说明。
     */
    private const val K_CALIB_STEP1 = "calib_step1_done"
    private const val K_CALIB_STEP2 = "calib_step2_done"

    /**
     * 交还目标值的哨兵：**我们没有改过** `accelerometer_rotation`，交还时**一个字节都不许碰**。
     *
     * ★★ 2026-09-30 第二轮定案（用户报「不要影响外屏的旋转锁定状态」）。
     *
     * ## 为什么"原值往返"还不够
     *
     * 上面那版（接管时记原值、交还写回原值）方向是对的，但**仍会吃掉用户的改动**，
     * 因为它记的 `target` 是**接管那一刻**读到的值，而那一刻的值可能正是**上一次的残留**：
     *
     * ```
     * ① 内屏接管：读到 cur = 0（那时用户锁着）⇒ target = 0 落盘
     * ② 合上回外屏：交还写 0                    ⇒ accel = 0
     * ③ 用户在外屏手动「关闭旋转锁定」           ⇒ accel = 1
     * ④ 展开（若此时没重新接管）→ 合上 → 交还写回陈旧的 target = 0   ← ★ 用户的选择被抹掉
     * ```
     *
     * ⛔ **最要命的一点：这个模型不收敛。** 只要有一次落到 `target = 0`，
     *   之后就永远是"读到 0 → 存 0 → 还 0"，用户的 `1` 再也回不来 ——
     *   这正是用户口中的「**哪怕我手动关闭，展开再合上之后依然会自动打开**」。
     *
     * ## 现在的判据：先问"我们到底动没动过它"
     *
     * | 接管时读到 | 含义 | 记账 |
     * |---|---|---|
     * | `cur != 0` | 用户开着自动旋转，**我们把它关成了 0**（欠一笔） | `target = cur`（=1） |
     * | `cur == 0` | 用户本来就锁着，**我们什么都没改**（不欠） | `target = 本哨兵` |
     *
     * 交还（见 `AdaptiveEngine.releaseTakeover`）：
     * - `target == 本哨兵` ⇒ **不碰**（用户本来就锁着 ⇒ 合上后依然锁着 ✅）
     * - `target >= 0` **且当前仍是 0**（确实还是我们关的那个状态）⇒ 写回 `target`（=1）
     * - `target >= 0` **但当前已经不是 0**（用户在内屏期间自己动了）⇒ **不碰**，尊重他的新选择
     *
     * ⇒ 三条路径都不再"替用户做决定"。用户原话的诉求就是这两句：
     *   「展开前外屏如果打开旋转锁定、展开再合上之后依然保持打开状态；如果原本是关闭、之后也保持关闭」。
     *
     * ⚠️ 历史教训两则，都留着别再犯：
     * - 09-25 把「切到别的 App 转不动」判成故障 ⇒ 改成"一律还 1"。**因果搞反了** ——
     *   "转不动"恰恰是因为用户的旋转锁定本来就开着，那是他自己的设置。
     * - 09-30 第一轮改成"原值往返"，仍不收敛（见上）。
     * ★ 想要"停手后强制打开自动旋转"的人，用 [PrefsBridge.HANDOFF_ROTATE] 那个开关表达意图，
     *   别把这种语义塞进这里的常量 —— 常量没有"用户是谁、他想要什么"这个维度。
     */
    const val AUTO_ROTATE_UNTOUCHED = -1

    // ---------------------------------------------------------------- 模式（按内/外屏分开）

    /**
     * **旋转增强的模式**（唯一一份）。
     *
     * ★ 它只描述**内屏**的行为 —— 2026-09-29 用户拍板「直接删除外屏的旋转增强，
     *   只保留内屏的旋转增强和应用豁免」。
     *
     * ⚠️ 历史：09-28 曾把模式解耦成内外屏两份（`rotate_mode_inner` / `rotate_mode_outer`）。
     *   外屏增强删掉之后 `rotate_mode_outer` **已不再读写**（键还留在老用户的文件里，无害）。
     *   本键名沿用 `rotate_mode_inner`，老用户那一档原样生效、零迁移。
     */
    private val _modeInner = MutableStateFlow(RotateMode.SYSTEM)

    /** 当前形态（内屏 / 外屏）—— 由 App 界面与引擎各自喂进来，见 [syncScreenForm] */
    private val _form = MutableStateFlow(ScreenForm.INNER)

    /**
     * **当前形态的生效模式** —— 引擎只认这一个。
     *
     * ★ 名字故意保持叫 `mode`（而不是 `effectiveMode`）：引擎（`AdaptiveEngine`）里
     *   有 **17 处** `AppPrefs.mode.value` 读点，全都工作在"我现在该按哪一档转"这个
     *   语义上 —— 那本来就是"当前形态的模式"。留着这个名字，那 17 处**一行都不用改**，
     *   "外屏不介入"这件事被完整地关在本文件里（见 [refreshEffectiveMode]）。
     *
     * ⚠️ 它是个**派生值**：真身是 [_modeInner]。不要直接写它。
     */
    private val _mode = MutableStateFlow(RotateMode.SYSTEM)

    private val _strategy = MutableStateFlow(CaptureStrategy.POWER_SAVING)
    private val _sign = MutableStateFlow(1)
    private val _offsetDeg = MutableStateFlow(0f)

    /**
     * ★ 默认 **true**（停手时把方向盘还给系统）。
     *
     * ★★ "交还"的准确含义 = **还原成接管前的原值**（2026-09-30 更正）：
     *   接管前是 1 ⇒ 还 1；接管前是 0（用户开着旋转锁定）⇒ 还 0。
     *   ⛔ 它**不是**"一律设成 1" —— 那样等于**替用户解开旋转锁定**，正是用户 09-30 报的
     *   「为什么总是把外屏的旋转锁定关掉」。
     *
     * 理由（这个开关存在的意义）：堵住"停手后留下真空"那个坑 —— 只停引擎而不还原
     * `accelerometer_rotation`，而系统自动旋转本来开着，用户在全屏视频/看图时会发现
     * "屏幕转不动了"且不知道为什么。
     * ⚠️ 关掉它 = 停手后**保留当前方向**（accel 维持 0，屏幕冻在当前角度），
     *   适合"我就想让屏幕锁在这个角度"的场景。
     */
    private val _handoffRotate = MutableStateFlow(true)

    /** ★ 默认 **true**（门控生效）。关掉 = 引擎永不因前台朝向停手，退回 2026-09-28 之前的行为 */
    private val _gateEnabled = MutableStateFlow(true)

    /**
     * ★ 「实验功能 → 自适应旋转」总闸（界面**可见性**开关，2026-10-03）。
     *
     * 默认 **false**（关）：自适应旋转仍处于实验阶段（效果不稳定），
     * 所以「旋转增强 → 模式」里**默认不列出**它，用户要先去「实验功能」页主动打开。
     *
     * ⚠️ 它**不参与引擎判断**、也**不改** [modeInner] —— 见 `PrefsBridge.EXPERIMENTAL_ADAPTIVE`
     *   的注释。换句话说：关掉它不会把正在用自适应的用户踢出去。
     */
    private val _experimentalAdaptive = MutableStateFlow(false)

    /** 按钮等待时长的取值边界与默认值（用户 2026-09-28 点名：最少 1s、最多 60s） */
    const val HINT_MS_MIN = 1_000
    const val HINT_MS_MAX = 60_000
    const val HINT_MS_DEFAULT = 3_000

    /**
     * 把任意毫秒值归一成**合法的整秒毫秒值**（先夹紧、再四舍五入到整秒）。
     *
     * ★ 为什么必须归一：界面滑条给回来的是一个**浮点秒**（实测落盘过 `7927`），
     *   而界面文案是 `hintMs / 1000` 取整显示的 —— 不归一会得到
     *   「界面写 7 秒、实际等 7.927 秒」这种**显示与行为不一致**。
     *   用户报的规格本来就是整秒（「最少 1s，最多 60s」），对齐整秒之后
     *   界面文案与真实时长逐字相符，滑条也天然是 60 个离散档位。
     *
     * ★ 归一放在**唯一入口**（[setHintMs] 与两个读盘函数），而不是放在界面里：
     *   这样无论值从滑条、预设档还是被手改的配置文件进来，`hintMs` 都恒是整秒，
     *   引擎端也就不可能读到 `7927` 这种数。
     *
     * 算式说明：`(v + 500) / 1000 * 1000` 是整数域的四舍五入（+半档再截断），
     * 末尾再夹一次是因为 `59999 + 500` 会进位到 `60000` 之外，不夹就越界。
     */
    fun snapHintMs(v: Int): Int =
        ((v.coerceIn(HINT_MS_MIN, HINT_MS_MAX) + 500) / 1000 * 1000)
            .coerceIn(HINT_MS_MIN, HINT_MS_MAX)

    /**
     * 半自动按钮的**等待时长**（毫秒）—— 用户 2026-09-28 点名要的可调项。
     *
     * ★ 范围与默认值都不在这里硬编码，见 [HINT_MS_MIN] / [HINT_MS_MAX] / [HINT_MS_DEFAULT]。
     * ★ 它是"引擎侧要读、界面侧要写"的配置，所以走与模式/开关同一条通道（prefs 文件），
     *   引擎那边由 [applyFromModulePrefs] 灌进来 —— **不需要任何新机制**。
     * ★ 取值恒为整秒，见 [snapHintMs]。
     */
    private val _hintMs = MutableStateFlow(HINT_MS_DEFAULT)

    /**
     * ★ **清除「实测不可控」名单**的请求值（App 写、引擎读）。
     *
     * 每次点"清除"都写一个**新的 wall-clock 时间戳** —— 引擎靠"值变了"驱动，
     * 不比较大小也不复位（复位做不到：引擎写不了 App 的私有文件）。
     * 完整理由见 [PrefsBridge.UNCONTROLLABLE_CLEAR]。
     *
     * ⚠️ 它是**请求**不是状态：引擎处理完**不会**改它，所以它长期停在最后一个时间戳上。
     *   引擎侧据此必须做"首次只记账、不当动作"的处理（见
     *   `AdaptiveEngine.handleUncontrollableClearRequest`）—— 否则引擎每次重启
     *   都会把用户好不容易攒下的实测记录清掉一次。
     */
    private val _uncontrollableClearReq = MutableStateFlow("")

    /**
     * 「弹一次按钮给我看看」的请求（值 = `<时间戳>|<目标方向>`）。
     *
     * ★ 为什么需要：半自动按钮只在传感器判定"设备姿态 ≠ 屏幕方向"时出现，而传感器
     *   **没法用 adb 注入** ⇒ 想看一眼按钮长什么样，只能靠人把手机转一下。
     *   调外观要转一次手机，这不可接受。
     * ⚠️ 它**只影响外观验证**：弹出来的按钮点下去走的是真实路径（写方向 + 读回），
     *   所以这不是"绕过传感器的入口"，只是让人能看见按钮。
     */
    private val _hintTestReq = MutableStateFlow("")

    /**
     * ★★ **实时角度预览**开关（2026-10-01 新增；App 写、引擎读）。
     *
     * 用户原话：「在方向校准里面加一个实时的角度显示，我告诉你正确方向」。
     *
     * ★ 它是**状态**，不是请求：界面进「方向校准」时置 true、离开时置 false，
     *   引擎直接跟着这个值开/停前摄。（与其他三个请求键的"值变了"驱动不同，
     *   这里不比较新旧值 —— 状态本来就该是可以反复重放的。）
     *
     * ⚠️ **不跨进程启动存活**：预览只在"用户正盯着那一页"时才有意义。
     *   App 崩溃 / 被杀会留下一个 `true`，下一次冷启动若照它开相机就是白耗电
     *   ⇒ [init] 读盘时**强制复位**（见那边的注释）；引擎侧也另有一道"首次只记账"的闸。
     */
    private val _anglePreview = MutableStateFlow(false)

    /** 实时角度预览开关（界面读它显示"正在读数"；引擎 `collect` 它启停采样） */
    val anglePreview: StateFlow<Boolean> = _anglePreview.asStateFlow()

    // --------------------------------------- 应用白名单（2026-09-28 建立 / 09-29 内外屏解耦）

    /**
     * 用户**手动开启**的包（**名单只有这一份**，理由见 [AppWhitelist] 类注释"记过案"）。
     *
     * ★ 落盘键沿用旧的 `app_whitelist_add` —— 09-29 上午那版三层名单里它就是"全局层"，
     *   而当时用户勾的每一格语义都是"两块屏都豁免" ⇒ **老配置零迁移**。
     */
    private val _wlAdd = MutableStateFlow<Set<String>>(emptySet())

    /** 用户**手动关闭**的包。为什么必须是独立一份减集：见 [PrefsBridge.WHITELIST_REMOVE] */
    private val _wlDel = MutableStateFlow<Set<String>>(emptySet())

    /**
     * **生效**白名单（派生值 = [AppWhitelist.resolve] 的结果）。
     *
     * ★ 为什么存派生态而不是每次现算：引擎侧要拿它做**前台包的集合查找**
     *   （每次触发、每 2 秒巡检各一次），现算就得每次都拼一遍默认清单的 40 多个包名。
     *   派生值只在输入变化时重算，见 [refreshWhitelist]。
     *
     * ⚠️ 它是 StateFlow 且引擎会 `collect` 它 —— 用户改一个开关 ⇒ 引擎在 2 秒内
     *   重新判一次前台门（见 `AdaptiveEngine` 里的收集）。这就是"改完立刻生效"的实现。
     */
    private val _whitelist = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 配置通道（App 的 prefs → 引擎）是否就绪。
     *
     * ★ 取代了旧实现里的「配置镜像故障」提示：那时要回答"配置有没有成功塞进 Settings"，
     *   而那是会失败的（需要 root）；新实现写的是**自己的**文件，App 侧不会失败。
     *   现在这个标志表达的是**引擎侧**的观感：引擎进程有没有成功读到配置
     *   （读不到的原因通常是模块没启用 / 用户从没打开过 App）。
     */
    private val _configOk = MutableStateFlow(false)
    val configOk: StateFlow<Boolean> = _configOk.asStateFlow()

    /**
     * 引擎侧（SystemUI 里的宿主）回传的**配置通道原因**。
     *
     * ⚠️ 它**不本地化**，而且不是"懒得改"：
     *   ① 句子是**另一个进程**（[cn.dsr213.hyperplus.module.ModulePrefs]）算出来的，
     *      那里读不到用户在 App 里选的语言（见 `AppLocale` 的类注释）；
     *   ② 它还会被**拼进状态摘要字符串**走配置通道回传
     *      （`EngineHost.summary` 的 `cfgmsg=` 段）⇒ 换成本地化文案会改协议内容。
     *   ⇒ 与日志同一条纪律：**排查用的话术固定成中文**。
     */
    private val _configDiag = MutableStateFlow("未初始化")
    val configDiag: StateFlow<String> = _configDiag.asStateFlow()

    /**
     * **App 侧**的配置通道自检：本应用进程有没有被 LSPosed 注入。
     *
     * ★ 为什么必须单独判这一条（2026-09-28 补，源码级结论）：
     *   配置能被引擎读到的**前提**是 prefs 文件落在 LSPosed 的 safe-zone
     *   （`/data/misc/<uuid>/prefs/<pkg>/`，SELinux `magisk_file`、目录 711、文件 664），
     *   而那要靠 LSPosed 在**本应用进程**里 hook `ContextImpl.getPreferencesDir()` 才会发生。
     *   按 `LoadedApkCreateCLHooker.afterHookedMethod`，这个 hook 只在
     *   「当前进程就是模块自己的包」且该包在 `XposedInit.loadedModules` 里时安装，
     *   ⇒ 本应用进程被注入是**硬前提**。
     *
     * ⚠️ **但这不需要用户做任何事**（2026-10-03 更正）：
     *   此前这里写着「若用户没在作用域里勾上 HyperPlus 自己，本应用进程不会被注入」——
     *   **是错的**。本模块用 `assets/xposed_init` 声明入口 = legacy 模块，而 LSPosed
     *   在保存作用域时会**自动把模块自己的包也写进 scope**
     *   （管理器侧 `ConfigManager.setModuleScope` 的 `if (legacy) { … }` 分支，
     *   `userId = 0`），并且**刻意把它从作用域列表里过滤掉**（同文件 `getModuleScope`、
     *   以及 `ScopeAdapter` 的 `packageName.equals(module.packageName)` 分支）
     *   ⇒ 用户在列表里看不到、也不需要勾它。
     *   ⇒ 界面提示**不许**再让用户去勾 HyperPlus 自己（勾不到），指向「启用模块 + 勾系统界面」。
     *
     * ★ 判据取"`MODE_WORLD_READABLE` 有没有被放行"而不是猜路径：
     *   LSPosed 的 `checkMode` hook 与 `getPreferencesDir` hook 是**同一处代码**里一起装的
     *   （`LoadedApkCreateCLHooker.hookNewXSP`），所以"能开 WORLD_READABLE"
     *   ⇔ "被注入了" ⇔ "文件落在 safe-zone"。反过来必然抛 SecurityException。
     *   一个 try 就能判定整条链路，不需要读任何私有路径。
     */
    private val _appChannelOk = MutableStateFlow(false)
    val appChannelOk: StateFlow<Boolean> = _appChannelOk.asStateFlow()

    /**
     * 上面那条自检的**原因**，供界面显示。
     *
     * ⚠️ 2026-10-03 多语言：类型从 `StateFlow<String>` 改成 **`StateFlow<Int>`（资源 id）**。
     *   原因不是"顺手" —— 原先这里存的是**算好的中文句子**，而它只在 [init] 里写一次，
     *   而 [init] 在 `prefs != null` 时会**早退**（见上面那段注释）。
     *   ⇒ 用户在设置里切换语言后，这句会**永远停在旧语言**，直到进程被杀掉重建。
     *   存资源 id 就没有这个问题：id 与语言无关，取用那一刻才查表。
     *
     * `0` = 还没跑过 [init]（界面回退到 `settings_channel_default`）。
     */
    private val _appChannelDiagRes = MutableStateFlow(0)
    val appChannelDiagRes: StateFlow<Int> = _appChannelDiagRes.asStateFlow()

    /**
     * 是否已标定（判据：`Settings` 里**存在** OFFSET 键，而不是"值非 0"）。
     *
     * ★ 做成 StateFlow 是为了驱动界面：[isCalibrated] 也能回答同一个问题，
     *   但它是普通属性、变化不会触发重组，界面会停在旧状态。
     *   由 [refreshCalibFromSettings] 维护。
     */
    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated.asStateFlow()

    /**
     * 校准的**分步进度**（App 自己的账，纯界面用，2026-10-03）。
     *
     * ★★ 为什么必须由 App 自己记：
     *   用户原话是「①/② 做完没，界面上看不出来」—— 他点了「① 记竖屏」之后界面上没有
     *   留下任何痕迹，不知道还要不要点 ②。
     *
     *   而**系统侧根本没有"分步"这个状态**：`applyCalibrationBaseline`（①）里就已经调了
     *   [persistCalibration] ⇒ ① 一成功，`Settings` 里 OFFSET 键就有值、[calibrated] 就是 true；
     *   ② 只是把 sign/offset 重算得更准，落盘动作一模一样。
     *   ⇒ 拿 [calibrated] 只能回答"校准过没有"，分不出"①②各做完没有"。
     *
     *   ⇒ 唯一诚实的来源是**引擎回报的那一次结果**（`ModuleLink.requestCalibration` ⇒
     *     宿主 `publishCalibResult`）。App 在收到那一刻记一笔，就得到真实的步骤进度。
     *     ⛔ 别改成"点过按钮就算完成"：点 ≠ 成功（`noface` / `badangle` 都是失败），
     *       那样会告诉用户"① 好了"，而他其实还得重做一次。
     *
     * ⚠️ 它落在 **App 自己的 prefs**（不是 `Settings`）：这是界面记账，引擎不需要读、
     *   也不需要知道。引擎读到文件变化时会因"标定请求没变"直接返回，无副作用。
     *   （引擎进程 [prefs] 为 `null` ⇒ 下面的读写自然跳过。）
     */
    private val _calibStep1Done = MutableStateFlow(false)
    private val _calibStep2Done = MutableStateFlow(false)

    /** ① 记竖屏是否**成功过**（判据是引擎回报 `ok`，不是"点过按钮"） */
    val calibStep1Done: StateFlow<Boolean> = _calibStep1Done.asStateFlow()

    /** ② 记横屏是否成功过（② 成功必然意味着 ① 也成功过，见 [recordCalibStep]） */
    val calibStep2Done: StateFlow<Boolean> = _calibStep2Done.asStateFlow()

    /** 当前形态的生效模式（派生值，见 [_mode] 的注释） */
    val mode: StateFlow<RotateMode> = _mode.asStateFlow()

    /** 内屏模式（界面用：**只有这一份** —— 外屏不做增强，见 [modeOf]） */
    val modeInner: StateFlow<RotateMode> = _modeInner.asStateFlow()

    /** 当前形态（界面用来标注"你正在配的是哪块屏"） */
    val screenForm: StateFlow<ScreenForm> = _form.asStateFlow()

    val strategy: StateFlow<CaptureStrategy> = _strategy.asStateFlow()
    val sign: StateFlow<Int> = _sign.asStateFlow()
    val offsetDeg: StateFlow<Float> = _offsetDeg.asStateFlow()
    val handoffRotate: StateFlow<Boolean> = _handoffRotate.asStateFlow()
    val gateEnabled: StateFlow<Boolean> = _gateEnabled.asStateFlow()

    /**
     * 「实验功能 → 自适应旋转」是否已启用（默认 false）。
     *
     * ★ 只被**界面**读：`false` 时「旋转增强 → 模式」不列出 `ADAPTIVE` 那一档。
     * ⚠️ 引擎不读它，也不该读 —— 见 [PrefsBridge.EXPERIMENTAL_ADAPTIVE]。
     */
    val experimentalAdaptive: StateFlow<Boolean> = _experimentalAdaptive.asStateFlow()

    /** 半自动按钮等待时长（毫秒，1000~60000）。界面滑条读它，引擎侧由配置通道灌入 */
    val hintMs: StateFlow<Int> = _hintMs.asStateFlow()

    /**
     * 清除「实测不可控」名单的请求（值 = App 每次点"清除"时写的时间戳）。
     *
     * ★ 引擎 `collect` 它 ⇒ 用户点一下，毫秒级就代为删掉 `Settings.System` 里那张名单。
     */
    val uncontrollableClearReq: StateFlow<String> = _uncontrollableClearReq.asStateFlow()

    /** 「预览旋转按钮」的请求（值 = `<时间戳>|<目标方向>`）。引擎 `collect` 它并弹一次按钮 */
    val hintTestReq: StateFlow<String> = _hintTestReq.asStateFlow()

    /**
     * 生效的应用白名单（默认清单 + 用户增删）—— 界面画开关、引擎判前台门，用的都是它。
     */
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    // ---------------------------------------------------------------- 后端

    /** 读 `Settings.System`（引擎侧的状态/标定值）用的 context */
    @Volatile
    private var ctx: Context? = null

    /** App 进程的 prefs —— 配置**真身**。引擎进程恒为 null（引擎读不到别人的文件，走 [ModulePrefs]） */
    @Volatile
    private var prefs: SharedPreferences? = null

    /** 本进程是否引擎宿主（SystemUI） */
    @Volatile
    private var hostMode = false

    // ================================================================ 初始化：App 进程

    /**
     * **App 进程**初始化。幂等：Activity、TileService 都会调。
     *
     * ★ 打开方式必须带 [Context.MODE_WORLD_READABLE] —— 这是 LSPosed「New XSharedPreferences」
     *   契约里的一环：LSPosed 会 hook `ContextImpl.checkMode` 抑制随之而来的
     *   `SecurityException`，并 hook `getPreferencesDir()` 把文件重定向到 SELinux 友好的
     *   safe-zone。**只有在这个模式下**框架才帮忙把文件弄成引擎可读的。
     *   代价是它已被标 `@Deprecated`，所以有抑制注解。
     *
     * ⚠️ 模块**没启用**时上面两个 hook 都不存在 ⇒ 真的会抛 `SecurityException` ⇒
     *   降级成 `MODE_PRIVATE`（此时引擎也不在，配置只要 App 自己读得到就够）。
     *   两个模式落在**不同目录**，所以下面要做一次性迁移，否则用户配置会凭空丢失。
     */
    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (prefs != null || hostMode) {
            // ★ 已初始化过也**必须重算形态**（2026-09-28 模式解耦后加的）。
            //   本函数是 QS 开关和界面的共同入口，而"形态"是个会过期的读数：
            //   典型场景 —— 用户展开手机时打开过 App（记下"内屏"），
            //   合上之后从控制中心点一下开关，此时进程还活着、`prefs != null`，
            //   若在这里直接 return，`toggleMode()` 会去改**内屏**那一档。
            //   ⇒ 早退路径上补一次 syncScreenForm，代价只是一次尺寸读取。
            syncScreenForm(context)
            return
        }
        synchronized(this) {
            if (prefs != null || hostMode) {
                syncScreenForm(context)
                return
            }
            val app = context.applicationContext ?: context
            ctx = app

            val opened = runCatching {
                app.getSharedPreferences(NAME, Context.MODE_WORLD_READABLE)
            }.onFailure {
                Log.i(TAG, "MODE_WORLD_READABLE 不可用（LSPosed 未注入本应用？）→ 降级 MODE_PRIVATE：${it.javaClass.simpleName}")
            }.getOrNull()

            // ★ 这一步的结果本身就是**整条配置通道的体检报告**，必须如实传给界面：
            //   成功 = 本应用被注入 ⇒ prefs 落在 safe-zone ⇒ 引擎读得到；
            //   失败 = 没被注入 ⇒ 文件落在本应用私有目录 ⇒ 引擎读不到，配置改了也不会生效。
            _appChannelOk.value = opened != null
            // ⚠️ 存**资源 id**，不存句子 —— 理由见 [appChannelDiagRes] 的注释（切换语言后会停在旧语言）。
            // ⚠️ 文案里**不能写 markdown**：星号在真机上是**原样显示**的
            //   （2026-10-02 全量文案梳理时又抓出一处 —— 界面上原来的
            //    `**自己也勾上**` 就是带着星号显示的）。要强调就用「」。
            _appChannelDiagRes.value = if (opened != null) {
                R.string.channel_ready
            } else {
                R.string.channel_not_ready
            }

            val p = opened ?: app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            prefs = p
            migrateLegacyIfNeeded(app, p)
            reloadFromPrefs()
            // ★ 顺序不能变：**先**读盘拿到两份模式，**再**量形态 —— 因为"生效模式"是
            //   「当前形态 × 两份模式」的组合，反过来算的话第一次一定是默认档。
            syncScreenForm(app)
        }

        // ★ 必须主动落一次盘：LSPosed 的通道读的是**文件**，而 Android 的
        //   SharedPreferences 只在**首次写入**时才创建文件。用户可能只是打开看一眼、
        //   什么都不改 —— 那样文件压根不存在，引擎那边读到的就是"不可读"。
        persistAll()
    }

    /** 把内存里的配置整体写成一次提交（幂等；同时负责把文件"具现"出来） */
    private fun persistAll() {
        val p = prefs ?: return
        runCatching {
            val e = p.edit()
                .putString(PrefsBridge.MODE_INNER, _modeInner.value.name)
                .putString(PrefsBridge.STRATEGY, _strategy.value.name)
                .putBoolean(PrefsBridge.HANDOFF_ROTATE, _handoffRotate.value)
                .putBoolean(PrefsBridge.GATE, _gateEnabled.value)
                .putBoolean(PrefsBridge.EXPERIMENTAL_ADAPTIVE, _experimentalAdaptive.value)
                .putInt(PrefsBridge.HINT_MS, _hintMs.value)
            // ★ 实时角度预览**也落盘**（2026-10-01）：它是"跨进程的一次性状态"，
            //   引擎靠文件读到它。⚠️ 落盘时用的值已被 [init] 复位过（见那边的注释），
            //   所以这里永远不会把一个"上次没关掉"的 true 又写回去。
            e.putBoolean(PrefsBridge.ANGLE_PREVIEW, _anglePreview.value)
            // 白名单落成**单个字符串**（`\n` 分隔），理由见 [AppWhitelist.encode]。
            // 一份名单 × 两份（增 / 减）= 2 个键。
            e.putString(PrefsBridge.WHITELIST_ADD, AppWhitelist.encode(_wlAdd.value))
            e.putString(PrefsBridge.WHITELIST_REMOVE, AppWhitelist.encode(_wlDel.value))
            e.apply()
        }.onFailure { Log.w(TAG, "配置落盘失败", it) }
    }

    /** 用 prefs 里的值刷新内存流（App 进程读自己；引擎进程由 [applyFromModulePrefs] 负责） */
    private fun reloadFromPrefs() {
        val p = prefs ?: return
        // ★ 更老的版本只有一个全局键 `rotate_mode`：那一档就是现在的档（零迁移）。
        //   ⚠️ `rotate_mode_outer`（09-29 上午那版内外屏解耦的产物）**刻意不再读** ——
        //     外屏增强已经删掉，读进来也没有下游；键留在文件里不读，比"读进来再到处忽略"干净。
        val legacy = enumOrNull<RotateMode>(p.getString(PrefsBridge.MODE_LEGACY, null))
        _modeInner.value =
            enumOrNull<RotateMode>(p.getString(PrefsBridge.MODE_INNER, null)) ?: legacy ?: RotateMode.SYSTEM
        refreshEffectiveMode()
        _strategy.value =
            enumOrNull<CaptureStrategy>(p.getString(PrefsBridge.STRATEGY, null)) ?: CaptureStrategy.POWER_SAVING
        _handoffRotate.value = p.getBoolean(PrefsBridge.HANDOFF_ROTATE, true)
        _gateEnabled.value = p.getBoolean(PrefsBridge.GATE, true)
        // ★ 实验开关**只在 App 这条路径上读**（2026-10-03）：
        //   它 gates 的是界面上"列不列出自适应那一档"，引擎对此毫无兴趣
        //   ⇒ 刻意**不**进 [applyFromModulePrefs]（那是 SystemUI 进程读 ModulePrefs 的路径）。
        _experimentalAdaptive.value = p.getBoolean(PrefsBridge.EXPERIMENTAL_ADAPTIVE, false)
        // 读的时候也归一一次：老版本写进去的（如 7927）/ 文件被手改过的值都可能越界或非整秒
        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))
        // ★ 两条读盘路径都要灌这一个键（App 侧 / 引擎侧各一次）：
        //   引擎侧那一次才是功能性的（它驱动 collect 去代删 Settings 键），
        //   App 侧这次是为了"读进来的状态与文件一致"—— 否则界面上的反馈会与真实请求错位。
        _uncontrollableClearReq.value =
            p.getString(PrefsBridge.UNCONTROLLABLE_CLEAR, null).orEmpty()
        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()
        // ★★ 实时角度预览**在这里强制复位**（2026-10-01）。
        //
        //   它是"用户此刻正盯着「方向校准」那一页"的投影，**不该跨进程启动存活**：
        //   App 崩溃 / 被系统杀掉时会留下一个 true，若下次冷启动照它走，
        //   引擎就会**在用户根本没打开界面时白开前摄**（耗电 + 可能撞上系统的注视感知）。
        //   ⇒ 启动时无条件当 false，并让紧随其后的 persistAll() 把这个 false 写回文件。
        //   ⚠️ 别改成"读进来就用" —— 那样"崩溃一次就多烧一次电"，而且现象很难被发现。
        _anglePreview.value = false
        // ★ 校准分步进度（2026-10-03）：跨 App 重启存活，这里读回来。
        //   ⚠️ 紧接着的 `refreshCalibFromSettings()` 会按系统状态再对齐一次
        //     （见那边的注释），所以这里不需要额外的合法性判断。
        _calibStep1Done.value = p.getBoolean(K_CALIB_STEP1, false)
        _calibStep2Done.value = p.getBoolean(K_CALIB_STEP2, false)
        // ★★ 必须**先**做历史键迁移，再读名单（2026-10-03）：
        //   旧"内屏层"那份减集要并进现在这一份，读之前不并的话这一轮界面就少一条。
        migrateLegacyKeys(p)
        loadWhitelist { k -> p.getString(k, null) }
        refreshCalibFromSettings()
    }

    /**
     * 一次性处理配置文件里的**历史键**（2026-10-03 加）：
     *  ① 先把还有语义的旧键**迁移**过来；
     *  ② 再把**已经没有任何代码读写**的键清掉。
     *
     * ★ 为什么要专门写这一段：孤儿键不会被任何人发现 —— `remove` 掉它们**不会有任何
     *   可观测的后果**，所以没人有动力去做；而它们会一直躺在用户的配置里，
     *   将来排查时被当成"还有代码在读它"的证据。2026-10-03 收尾体检时就是这么撞上的：
     *   `unfold_default_inner=3` 还在，让人差点以为「展开后的方向」还有一份真值留在文件里。
     *
     * ★ 幂等 + 零代价：先 [SharedPreferences.contains] 判一遍，**一个都不存在就一个字都不写**
     *   —— 否则每次冷启动都要重写一遍配置文件（无谓 IO，还平白动 mtime）。
     *
     * ⚠️ 只在 **App 侧**做（[reloadFromPrefs]）：引擎进程读不到也写不了这个文件
     *   （见 `module/ModulePrefs`）。所以"装了新版但从不打开界面"的用户不会被清 ——
     *   不要紧，那些键已经没有任何下游。
     * ⚠️ 每一条删除都必须**先 grep 过读写函数**再往这里加（本文件顶部那条纪律）：
     *   "我印象里没人读"不算证据，配置有三套存储，很容易判错在哪一套。
     */
    private fun migrateLegacyKeys(p: SharedPreferences) {
        // ---------------------------------------------- ① 迁移：旧"内屏层"的减集
        //
        // ★ 背景（2026-09-29 收成一层名单时留下的）：更早的版本按屏各存一份名单，
        //   `app_whitelist_remove_inner` 是内屏那一份**减集**。收成一层之后这个键
        //   **不再被读**，于是用户在旧版里"关掉"的应用**悄悄回到了受控状态** ——
        //   这是**丢用户意图**，不是清理垃圾，所以要先并回来。
        //   ⚠️ 只并减集（`_remove` 那一份）。旧加集（`_add_inner`）实测是空的，
        //     且"默认清单"本身已经覆盖了它的语义 —— 并进来反而可能多出一堆
        //     用户从没选过的包，那是**凭空加**，比丢失更糟。⛔ 别顺手把它也并了。
        val legacyRemoveInner = p.getString("app_whitelist_remove_inner", null)
        if (!legacyRemoveInner.isNullOrBlank()) {
            runCatching {
                val merged = AppWhitelist.decode(p.getString(PrefsBridge.WHITELIST_REMOVE, null)) +
                    AppWhitelist.decode(legacyRemoveInner)
                p.edit()
                    .putString(PrefsBridge.WHITELIST_REMOVE, AppWhitelist.encode(merged))
                    .apply()
                Log.i(TAG, "已迁回旧内屏层的豁免名单：$legacyRemoveInner")
            }.onFailure { Log.w(TAG, "旧内屏名单迁移失败（已忽略）", it) }
        }

        // ---------------------------------------------- ② 清理：已无任何读写的键
        val dead = listOf(
            // —— 功能整体删除后留下的值 ——
            "unfold_default_inner",      // 「展开后的方向」固化值（10-03 整个功能删除）
            "slot_set",                  // 第一版「默认方向」的请求键（10-03 改为 root 直写）
            // —— 旧"按屏两份名单"的其余三份（09-29 收成一层后作废；实测都是空的）——
            "app_whitelist_add_inner",
            "app_whitelist_add_outer",
            "app_whitelist_remove_outer",   // ⚠️ 注意：`_remove_inner` 上面已单独迁移，别写进来
            "rotate_mode_outer",         // 外屏模式（外屏增强删除后不再读写）
            // —— 这四项**已经搬到 `Settings.System`**（带 `hyperplus_` 前缀，见 PrefsBridge.full），
            //    留在 prefs 文件里的是搬家前的旧副本。读的全是 Settings，故此处可清。
            "restore_auto_rotate",
            "takeover_active",
            "calib_sign",
            "calib_offset",
        )
        val present = dead.filter { p.contains(it) }
        if (present.isEmpty()) return
        runCatching {
            p.edit().apply { present.forEach { remove(it) } }.apply()
            Log.i(TAG, "已清理失效的历史键：$present")
        }.onFailure { Log.w(TAG, "清理历史键失败（已忽略，不影响功能）", it) }
    }

    // ================================================================ 初始化：引擎进程

    /**
     * **宿主进程**（SystemUI）初始化。必须在引擎起来之前调用。
     *
     * 与 [init] 的差别：不碰任何 SharedPreferences（引擎读不到 App 的私有文件），
     * 改为通过 [cn.dsr213.hyperplus.module.ModulePrefs] 读 App 的 prefs 文件，
     * 并注册变更监听 —— 用户在界面改任何一项，这里毫秒级收到并灌进 StateFlow，
     * 引擎的 `collect` 随即跟着启停 / 换策略。
     */
    fun initHost(context: Context) {
        if (hostMode) {
            // 与 [init] 同理：形态是会过期的读数，早退路径上也要重算一次
            syncScreenForm(context)
            return
        }
        synchronized(this) {
            if (hostMode) {
                syncScreenForm(context)
                return
            }
            val app = context.applicationContext ?: context
            ctx = app
            hostMode = true

            // 标定值 / 接管标志这些"引擎的账"永远从 Settings 读，与配置通道无关
            refreshCalibFromSettings()

            val ok = runCatching {
                val p = cn.dsr213.hyperplus.module.ModulePrefs.open()
                if (p != null) {
                    cn.dsr213.hyperplus.module.ModulePrefs.subscribe { applyFromModulePrefs() }
                    true
                } else {
                    false
                }
            }.getOrDefault(false)

            _configOk.value = ok
            _configDiag.value = cn.dsr213.hyperplus.module.ModulePrefs.diag
            if (ok) {
                applyFromModulePrefs()
                Log.i(TAG, "宿主配置通道就绪：${cn.dsr213.hyperplus.module.ModulePrefs.diag}")
            } else {
                // ⚠️ 读不到不是"用默认值将就"那么轻描淡写 —— 它意味着**用户在界面上的所有改动
                //   都不会生效**，因为引擎永远看到的是默认值。必须让界面如实显示出来。
                Log.w(TAG, "⚠️ 宿主读不到 App 配置，将使用默认值：${cn.dsr213.hyperplus.module.ModulePrefs.diag}")
            }

            // ★ 引擎进程也要自己量一次形态，且**放在配置读完之后**：
            //   ① App 界面可能从来没打开过（开机后引擎先起来），没人替它喂形态；
            //   ② 上面那个 `syncScreenForm` 的日志会打出"生效模式"，而生效模式 =
            //      当前形态 × 两份模式 —— 模式还没读进来就打，那条日志必然写着默认档，
            //      排查时会把人带到沟里去。所以顺序是：先读配置，再量形态。
            syncScreenForm(app)
        }
    }

    /**
     * 从 App 的 prefs 全量刷新内存流。
     *
     * ★ 一律全量读：配置通道的变更回调**不携带键名**（LSPosed 设计使然），
     *   拿不到"哪个键变了"，所以不做增量。配置项不到十个，全量读的代价可忽略。
     */
    private fun applyFromModulePrefs() {
        val p = cn.dsr213.hyperplus.module.ModulePrefs

        val legacy = enumOrNull<RotateMode>(p.getString(PrefsBridge.MODE_LEGACY, null))
        enumOrNull<RotateMode>(p.getString(PrefsBridge.MODE_INNER, null))?.let { _modeInner.value = it }
            ?: legacy?.let { _modeInner.value = it }
        refreshEffectiveMode()

        enumOrNull<CaptureStrategy>(p.getString(PrefsBridge.STRATEGY, null))?.let { _strategy.value = it }
        _handoffRotate.value = p.getBoolean(PrefsBridge.HANDOFF_ROTATE, true)
        _gateEnabled.value = p.getBoolean(PrefsBridge.GATE, true)
        // 读的时候也归一一次：老版本写进去的（如 7927）/ 文件被手改过的值都可能越界或非整秒
        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))
        // ★ 清除请求必须在这里灌进内存流 —— 引擎侧 `collect` 它才会去代删 Settings 键。
        //   这一行就是"App 点一下清除 ⇒ 引擎毫秒级收到"的**唯一通道**（理由见
        //   [PrefsBridge.UNCONTROLLABLE_CLEAR]：App 没有 WRITE_SETTINGS，删不了那个键）。
        _uncontrollableClearReq.value =
            p.getString(PrefsBridge.UNCONTROLLABLE_CLEAR, null).orEmpty()
        // ★ 预览请求同样要灌进内存流 —— 引擎侧 `collect` 它才会弹按钮。
        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()
        // ★★ 实时角度预览（2026-10-01）：**这里是引擎侧那一次读盘**，它才是有下游的那次
        //   （引擎 `collect` 它来启停前摄采样）。
        //   ⚠️ 首次立即重放的可能是"上次没关掉"的 true —— 过滤那道闸在引擎里
        //     （`setAnglePreview` 的冷启动分支），不放这里：AppPrefs 分不清
        //     "这是启动时的重放"还是"用户真的刚打开"，引擎那边有明确的一次性时机。
        _anglePreview.value = p.getBoolean(PrefsBridge.ANGLE_PREVIEW, false)
        loadWhitelist { k -> p.getString(k, null) }

        _configOk.value = p.available
        _configDiag.value = p.diag
    }

    // ================================================================ 应用白名单

    /** 读两份集合（用统一的取值函数，App / 引擎两条读盘路径共用） */
    private fun loadWhitelist(get: (String) -> String?) {
        _wlAdd.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_ADD))
        _wlDel.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_REMOVE))
        refreshWhitelist()
    }

    /**
     * 由 [_wlAdd] / [_wlDel] 重算生效白名单。**唯一的写入点** ——
     * 任何改到那两个流的地方都必须调它，否则引擎看到的还是旧集合。
     *
     * ★ 只在新值与旧值不同时赋值：`MutableStateFlow` 对相同值不重发，
     *   但显式挡一道能让"每次读盘都触发一轮引擎重判"这种事不发生（读盘很频繁）。
     */
    private fun refreshWhitelist() {
        val next = AppWhitelist.resolve(_wlAdd.value, _wlDel.value)
        if (next != _whitelist.value) _whitelist.value = next
    }

    /**
     * 开关某个应用的豁免。**界面唯一入口**。
     *
     * ★ `on` 落到 `add`、`off` 落到 `del`，并**把同一个包在另一份里的记录清掉**
     *   （两份对同一个包同时有记录没有意义，只会让落盘内容与人的直觉不符）。
     *
     * ⚠️ 不做"默认清单项不必进 add"那种归约：默认清单是**现算**的
     *   （见 [AppWhitelist.resolve]），所以"把一个默认项关掉"必须真的往 `del` 写一个包名，
     *   否则下一次现算又会被默认值顶回来。代价是落盘集合随**点过的应用数**增长
     *   （不是随点击次数），完全可接受。
     *
     * ✅ 现在**任何应用都能被关掉** —— 09-29 上午那版"按应用声明强制豁免、开关不给点"
     *   已经删掉（判错率太高、且判错了用户无法自救，见 [AppWhitelist] 类注释"记过案"）。
     *
     * @return 是否真的发生了变化（false = 用户点的状态本来就成立）
     */
    internal fun setAppWhitelisted(pkg: String, on: Boolean): Boolean {
        if (pkg.isBlank()) return false
        val before = pkg in _whitelist.value
        val add = _wlAdd.value.toMutableSet().apply { remove(pkg); if (on) add(pkg) }
        val del = _wlDel.value.toMutableSet().apply { remove(pkg); if (!on) add(pkg) }
        if (before == (pkg in AppWhitelist.resolve(add, del))) return false

        _wlAdd.value = add
        _wlDel.value = del
        refreshWhitelist()
        persistAll()
        return true
    }

    /**
     * 引擎侧判据：这个前台包要不要停手。
     *
     * ★ **形态参数已经删掉**（2026-09-29）：外屏的旋转增强整个没了（见 [modeOf]），
     *   引擎只会在内屏判它 —— 留着一个永远传 `INNER` 的参数，只会让人以为还有两份名单。
     */
    fun isWhitelisted(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg in _whitelist.value
    }

    /**
     * 清掉「用户手动关闭」的记录，让默认清单重新生效（界面上的"恢复默认"按钮）。
     *
     * ★ 只清 `del`，**不动 `add`**：用户自己加进来的第三方应用是他的劳动成果，
     *   恢复默认不该把它抹掉。
     */
    fun resetWhitelistRemovals(): Boolean {
        if (_wlDel.value.isEmpty()) return false
        _wlDel.value = emptySet()
        refreshWhitelist()
        persistAll()
        return true
    }

    // ================================================================ 形态（内屏 / 外屏）

    /**
     * 量一次"现在这块屏是内屏还是外屏"并记录。**App 与引擎都调它** ——
     * 这是模式解耦唯一的输入。
     *
     * ★ 调用点（三处，缺一处就会出现"折起来还按内屏那档在转"）：
     *   1. [init] / [initHost] 初始化时各量一次（进程刚起来就得知道自己在哪块屏）；
     *   2. App 界面：配置变化时（`EngineScreen` 里跟着 `LocalConfiguration` 走）；
     *   3. 引擎：`AdaptiveEngine` 注册的 `ComponentCallbacks`。
     *
     * ★ 为什么**只留这一个入口**（而不是再暴露一个"直接设形态"的重载）：
     *   判定逻辑与"留下判定依据"必须绑在一起。分成两条路的话，传形态进来的那条
     *   没有测量值可打，日志就会丢最关键的 `smallestWidthDp` —— 而"判据错了"
     *   恰恰是这套东西唯一会出的故障。
     *
     * ★ 幂等且廉价：形态没变时只是一次比较 + 一次派生值刷新（不写盘、不广播），
     *   所以调用方可以放心地"每次配置变化都调一次"，不需要自己先去重。
     */
    fun syncScreenForm(context: Context) {
        val probe = runCatching { ScreenForm.probe(context) }
            .onFailure { ScreenFormLog.failed(it) }
            .getOrNull() ?: return
        val changed = probe.form != _form.value
        _form.value = probe.form
        refreshEffectiveMode()
        // ★ 2026-09-29：白名单**分了内外屏两层** ⇒ "当前形态的生效名单"随形态变。
        //   漏掉这一行的症状很隐蔽：换屏后引擎仍按**另一块屏**的名单判前台门
        //   （比如内屏豁免了某应用，折起来之后外屏也把它当成豁免，反之亦然）。
        //   无条件调（不只是 changed 时）：首次测量前 [_form] 是默认值，
        //   而 initialize 期的读盘是在它之前跑的 —— 两条路径都要落到同一份结果上。
        refreshWhitelist()
        if (changed) {
            // ★★ 换屏了 —— **两块屏的安装朝向差 180°**（本机实测外屏 0 / 内屏 180，
            //   见 [PanelOrientation]），所以那个按屏缓存的换算偏置必须立刻丢掉，
            //   否则展开之后还会拿外屏的偏置继续写方向，等于没修。
            PanelOrientation.invalidate()
            // 引擎要知道"换屏了"：它得据新屏重新处理方向（见 AdaptiveEngine 的形态回调）。
            runCatching { formChangeListener?.invoke(probe.form) }
                .onFailure { Log.w(TAG, "形态变更回调异常（已忽略）", it) }
        }
        // 每次测量都留一行 —— 频率很低（初始化 / 折叠 / 转屏），而"它到底量到了多少"
        // 正是判定出问题时唯一要看的东西。
        ScreenFormLog.note(probe, _mode.value, changed)
    }

    /**
     * 换屏（内 ⇄ 外）时的回调。**只该由引擎注册**（App 进程没人需要它）。
     *
     * ★ 为什么把钩子挂在这里而不是让调用方各自比较：换屏的判定**只有这一处**
     *   （`ScreenForm.probe` 的唯一入口）。谁都在外面再比一次，就会出现
     *   "两处都认为自己发现了换屏"的重复动作。
     */
    @Volatile
    private var formChangeListener: ((ScreenForm) -> Unit)? = null

    fun setScreenFormListener(l: ((ScreenForm) -> Unit)?) {
        formChangeListener = l
    }

    /**
     * 某个形态当前是哪一档。
     *
     * ⚠️ **外屏恒为 [RotateMode.SYSTEM]（= 引擎不介入）** —— 这是"删除外屏旋转增强"
     *   的**唯一实现点**：引擎那 17 处读的都是 [mode]，而 [mode] 由本函数派生。
     *   闸放在这里，引擎侧一行都不用改，也不可能漏掉某条路径。
     */
    fun modeOf(form: ScreenForm): RotateMode =
        if (form == ScreenForm.INNER) _modeInner.value else RotateMode.SYSTEM

    /**
     * 把"当前形态的模式"这个派生值刷成最新。
     *
     * ⚠️ 只有三处会动到真身（模式 + 当前形态），那三处**必须**调它，
     *   否则界面显示的和引擎执行的就分家了：
     *   [setMode] / [syncScreenForm] / 两条读盘路径（[reloadFromPrefs] / [applyFromModulePrefs]）。
     *
     * ★ 它同时是"外屏不介入"的**换屏时刻**：折叠 ⇒ 形态变 OUTER ⇒ 这里把 [mode] 写成
     *   SYSTEM ⇒ 引擎那侧 `AppPrefs.mode.collect` 立刻看到"不 engage" ⇒
     *   `stopTrigger()` + `releaseTakeover()`（把系统自动旋转还回去、收掉按钮）。
     */
    private fun refreshEffectiveMode() {
        _mode.value = modeOf(_form.value)
    }

    // ================================================================ 写入（App 进程）
    //
    // 只写自己的 prefs —— 零权限、零 root、同进程同步更新 StateFlow。
    // 引擎那边靠文件监控在毫秒级跟上（见 ModulePrefs 的类注释）。

    /**
     * 设定旋转增强的模式（**只有一份** —— 外屏不做增强，见 [modeOf]）。
     *
     * ⚠️ 旧签名是 `setMode(form, value)`（内外屏各一份）。形参去掉之后，
     *   界面**不可能**再"配一份用不上的外屏模式"，这正是想要的。
     */
    fun setMode(value: RotateMode) {
        _modeInner.value = value
        prefs?.edit()?.putString(PrefsBridge.MODE_INNER, value.name)?.apply()
        refreshEffectiveMode()
    }

    fun setStrategy(value: CaptureStrategy) {
        _strategy.value = value
        prefs?.edit()?.putString(PrefsBridge.STRATEGY, value.name)?.apply()
    }

    /**
     * 切「前台门停手时，是否把系统自动旋转交还系统」。
     *
     * ★ 走 prefs 而不是静默丢弃：它是**用户可见的开关**，必须真的落盘
     *   —— 否则用户以为关了，重启后引擎又按旧值走。
     */
    fun setHandoffRotate(v: Boolean) {
        _handoffRotate.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.HANDOFF_ROTATE, v)?.apply()
    }

    /**
     * 切「前台门控」总开关。
     *
     * 用户 2026-09-28 点名要的逃生阀：门控是全工程唯一"主动放弃干活"的机制，
     * 判据一旦在某个应用上出错，症状就是"该转不转"。关掉即退回改造前的行为。
     */
    fun setGateEnabled(v: Boolean) {
        _gateEnabled.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.GATE, v)?.apply()
    }

    /**
     * 开关「实验功能 → 自适应旋转」（2026-10-03）。
     *
     * ★★ **它只改可见性，一个字都不碰 [modeInner]。** 这一点是刻意的，两个方向都别改：
     *   - **打开时**不自动切到自适应 —— 用户说的是"才显示选项"，不是"才启用"；
     *     自动切过去等于**替他做了选择**（他可能只是想先看一眼有哪些档）。
     *   - **关闭时**不把已经在用自适应的用户踢回跟随系统 —— 那是在他毫不知情的情况下
     *     **改掉他的配置**（本机当前档就是自适应）。关掉开关的效果只是：
     *     新用户在三档单选里看不到它。
     *   ⇒ 所以"开关关着、但当前档是自适应"是个**合法状态**，界面必须能表达它 ——
     *     见 [cn.dsr213.hyperplus.ui.ModePicker] 里"当前档永远列出"的那条规则。
     */
    fun setExperimentalAdaptive(v: Boolean) {
        _experimentalAdaptive.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.EXPERIMENTAL_ADAPTIVE, v)?.apply()
    }

    /**
     * 设半自动按钮的等待时长（毫秒）。
     *
     * ★ 在这里**统一夹紧 + 对齐整秒**（见 [snapHintMs]），而不是只靠界面滑条约束：
     *   配置将来可能从别处写（预设档 / 迁移），把边界收在**唯一入口**上最省心 ——
     *   引擎读到的永远是"合法的整秒值"。
     */
    fun setHintMs(v: Int) {
        val clamped = snapHintMs(v)
        _hintMs.value = clamped
        prefs?.edit()?.putInt(PrefsBridge.HINT_MS, clamped)?.apply()
    }

    /**
     * 开/关「实时角度预览」（界面进/出「方向校准」时调）。
     *
     * ★ 为什么**相等就早退**：这一页每次重组都会走一遍 [DisposableEffect]，
     *   `prefs.edit().commit` 是有真实代价的（写文件 + fsync）；
     *   而引擎那边也靠"值真的变了"来决定启停采样 —— 反复写同一个值会让它来回开销。
     */
    fun setAnglePreview(on: Boolean) {
        if (_anglePreview.value == on) return
        _anglePreview.value = on
        prefs?.edit()?.putBoolean(PrefsBridge.ANGLE_PREVIEW, on)?.apply()
        Log.i(TAG, "实时角度预览：${if (on) "开启" else "关闭"}")
    }

    /**
     * ★ 请求清除「实测不可控」名单（A 方案的自救出口）。
     *
     * ============================ 为什么 App 不自己删 ============================
     * 名单存在 `Settings.System`（[Uncontrollable.KEY]），而**删除也要写权限** ——
     * App 的清单里**没有 `WRITE_SETTINGS`**（见 [PrefsBridge] 类注释：往非公开键里写
     * 只认 SYSTEM / SHELL / ROOT uid 与特权包）。所以这里只做两件事：
     *   ① 往自己 prefs 写一个新时间戳（引擎读得到）；
     *   ② 同时更新内存流，界面上可以立刻给反馈。
     * 真正的删除由引擎代劳（它是特权包）。
     *
     * ★ 为什么必须给这个出口：这条名单是**观测结论，不是永恒事实** ——
     *   系统更新、应用更新、用户改了该应用的方向设置，都可能让它失效。
     *   没有出口的话，一旦误记就是**永久**的"这个应用在外屏永远不弹按钮"，且无从自查。
     */
    fun requestUncontrollableClear() {
        val v = System.currentTimeMillis().toString()
        _uncontrollableClearReq.value = v
        prefs?.edit()?.putString(PrefsBridge.UNCONTROLLABLE_CLEAR, v)?.apply()
    }

    /**
     * ★ 请引擎**弹一次旋转按钮**（只为看外观）。
     *
     * ============================ 为什么必须走这条请求 ============================
     * 半自动按钮的触发条件是"传感器判定设备姿态 ≠ 屏幕方向"，而**传感器没法用 adb 注入**
     *   （`SensorService` 的数据注入是 eng build 才有的开关）。
     *   ⇒ 装机后想看一眼按钮长什么样，只能靠人把手机转一下 —— 调一次外观转一次手机，
     *     这不可接受（按钮材质是本工程被反复调整的一项）。
     *
     * ★ 与 [requestCalibration] / [requestUncontrollableClear] **同一个套路**：写一个新时间戳
     *   到自己的 prefs，引擎靠"值变了"驱动；不复位（引擎写不了 App 的私有文件）。
     *   ⚠️ 引擎侧因此必须"冷启动首次只记账"，否则每次软重启 SystemUI 都会凭空弹一个按钮。
     *
     * @param targetRotation **只用于占位**：引擎侧已不再拿它定位（App 没有姿态读数，
     *   写死的方向会让按钮落到任意一个角 —— 真机上撞到过"弹在左上角压着状态栏"）。
     *   现在真正的目标方向由引擎用**设备此刻的姿态**取（`semiTargetRotation()`），
     *   于是 `delta == 0`、按钮恒落在当前物理右下角。传什么都行，默认 1 只为可读性。
     */
    fun requestHintTest(targetRotation: Int = 1) {
        val v = "${System.currentTimeMillis()}|$targetRotation"
        _hintTestReq.value = v
        prefs?.edit()?.putString(PrefsBridge.HINT_TEST, v)?.apply()
        Log.i(TAG, "按钮预览请求已落盘：$v")
    }

    /**
     * 读**某块屏方向槽位**的当前实际值（`Settings.System.user_rotation_<form>`）。
     *
     * ★ 读系统设置**不需要任何权限**（零门槛），所以 App 侧读它完全正当 ——
     *   界面据此显示"现在到底是什么方向"，而不是"用户点过什么"。
     *
     * ★★ 2026-10-03：**写**这一侧改由 App 借 root 直写（[RootShell.putSystemInt]；
     *   用户原话「**能装上模块的手机一定有 Root，可以通过获取 root 来修改**」）。
     *   ⚠️ 这里曾经写着"写必须由引擎做、别引入 su" —— 那条**已被真机否掉**：
     *     引擎代写要过配置通道，实测出现过"请求落了盘、槽位却没变"（见当日文档）。
     *     现在写就在 `ui.SlotCalibrationSection` 里，点一下一条命令，成败当场可见。
     *   ⚠️ 别把写挪到这条路径上来：读是**每秒轮询**的，绝不能夹带写。
     *
     * @return `null` = 读不到 / 这个键不存在（⚠️ 非 HyperOS 机型就是"键根本没有"，
     *   调用方**不得**当成 0 —— "没有这个键"与"键值是 0"是两件事）；也不返回越界值
     */
    fun readSlot(form: ScreenForm): Int? {
        val cr = ctx?.contentResolver ?: return null
        return runCatching {
            Settings.System.getInt(
                cr,
                PrefsBridge.KEY_USER_ROTATION_PREFIX + form.storageKey,
                Int.MIN_VALUE,
            )
        }.getOrNull()?.takeIf { it in 0..3 }
    }

    /**
     * 供 QS Tile 单击使用：**三态循环** 跟随系统 → 自适应 → 半自动 → 跟随系统。
     *
     * ⚠️ 外屏**不增强**（见 [modeOf]）⇒ 在外屏上"切换"没有可切换的东西。这里**直接返回
     *   SYSTEM 且不写盘** —— 磁贴那边据此显示"外屏不做旋转增强"，
     *   而不是让用户点出一个改不动任何东西的档位。
     */
    fun toggleMode(): RotateMode {
        if (_form.value == ScreenForm.OUTER) return RotateMode.SYSTEM
        return nextMode(_modeInner.value).also { setMode(it) }
    }

    /** 三态循环的**唯一定义**（`toggleMode` 与界面上的说明都必须与它一致） */
    fun nextMode(m: RotateMode): RotateMode = when (m) {
        RotateMode.SYSTEM -> RotateMode.ADAPTIVE
        RotateMode.ADAPTIVE -> RotateMode.SEMI
        RotateMode.SEMI -> RotateMode.SYSTEM
    }

    // ================================================================ 标定请求（App → 引擎）

    /**
     * 请引擎采一次样做标定 —— App 侧唯一的动作就是**留个请求**。
     *
     * ★ 为什么不在 App 侧算：相机与 ML Kit 都在引擎手里（SystemUI 进程），
     *   App 侧没有帧可采。旧实现在托管模式下也是这个链路，只是请求走 Settings 需要 root；
     *   现在走自己的 prefs，零权限。
     *
     * ★ 值带 token（默认取当前毫秒）：[ModulePrefs] 的通知不带键名，且引擎**没有权限复位**
     *   这个请求（写不了 App 的文件），所以靠"值变了"驱动，天然幂等、可反复触发。
     *   token 会被宿主原样带回结果里，让调用方能严格配对"这是我的那次请求"。
     */
    fun requestCalibration(step: Int, token: String = System.currentTimeMillis().toString()) {
        val v = "$token|$step"
        prefs?.edit()?.putString(PrefsBridge.CALIB_REQ, v)?.apply()
        Log.i(TAG, "标定请求已落盘：$v")
    }

    /** 清空标定（用户点「清除校准」）—— 同样只是发一个请求，实际清空由引擎做 */
    fun clearCalibration() {
        requestCalibration(CALIB_STEP_CLEAR)
        // ★ 分步进度**立刻归零**，不等引擎回执（2026-10-03）：用户点「恢复默认」的意思
        //   就是"从头来一遍"，界面上那两句"① 已完成 ✓"必须马上消失 —— 留到下次 2 秒
        //   轮询才更新的话，他会以为没点上。
        //   ⚠️ 万一引擎那边清失败：`calibrated` 仍是 true，[refreshCalibFromSettings]
        //     的对齐会把进度自愈回来（见那边的注释），所以这里不必等回执。
        markCalibSteps(step1 = false, step2 = false)
    }

    /**
     * 记一笔校准步骤的结果（**只在 App 进程调用**，由 `MainActivity.calibrate` 在引擎
     * 回报结果后立刻调用）。
     *
     * ★ 判据是**引擎的回报**，不是"用户点了按钮"：`noface`（没采到脸）、`badangle`
     *   （两步角度差不对）都是失败，不能算完成 —— 否则界面会告诉用户"这一步好了"，
     *   而他其实还得重做一次。
     *
     * @param ok 引擎是否回报 `ok`。⚠️ 失败时**什么都不做** —— 进度只前进不后退，
     *   "这次没采到脸"不该把"上次成功了"的记录抹掉。
     */
    fun recordCalibStep(step: Int, ok: Boolean) {
        if (!ok) return
        when (step) {
            // ★ ② 成功时把 ① 也一起标上：引擎侧 `applyCalibrationAxis` 会先检查
            //   `calibBaselineRoll` 在不在（不在就直接返回 false）⇒ ② 能成功，
            //   逻辑上必然意味着 ① 已经成功过。⛔ 别只标 ②。
            CALIB_STEP_AXIS -> markCalibSteps(step1 = true, step2 = true)
            CALIB_STEP_BASELINE -> markCalibSteps(step1 = true, step2 = null)
            else -> return
        }
    }

    /** 写进度（`null` = 这一项不动）。内存流 + 落盘；引擎进程 [prefs] 为 null ⇒ 只改内存 */
    private fun markCalibSteps(step1: Boolean?, step2: Boolean?) {
        step1?.let { _calibStep1Done.value = it }
        step2?.let { _calibStep2Done.value = it }
        val p = prefs ?: return
        p.edit()
            .putBoolean(K_CALIB_STEP1, _calibStep1Done.value)
            .putBoolean(K_CALIB_STEP2, _calibStep2Done.value)
            .apply()
    }

    // ---------------------------------------------------------------- 标定值（引擎的账）

    /**
     * 标定值由**引擎**持久化到 `Settings.System`。
     *
     * ★ 为什么不让 App 存：标定可能是引擎自己算出来的 ——
     *   ① 用户点按钮后的采样结果在引擎手里；
     *   ② 引擎还会在运行中**自动修正符号位**（见 `AdaptiveEngine` 的符号位校准）。
     *   这两条路径都只发生在 SystemUI 进程，而它写不了 App 的私有文件。
     *   反过来，引擎是特权包，写 Settings 免 root —— 所以标定归引擎管是最自然的。
     *
     * ★ **只在引擎进程调用。** App 进程走 [requestCalibration]。
     */
    fun persistCalibration(sign: Int, offsetDeg: Float) {
        _sign.value = if (sign < 0) -1 else 1
        _offsetDeg.value = offsetDeg
        _calibrated.value = true
        val c = ctx ?: return
        PrefsBridge.writeString(c.contentResolver, PrefsBridge.full(PrefsBridge.SIGN), _sign.value.toString())
        PrefsBridge.writeString(c.contentResolver, PrefsBridge.full(PrefsBridge.OFFSET), _offsetDeg.value.toString())
    }

    /** 清空标定值（引擎进程） */
    fun clearCalibrationInStore() {
        _sign.value = 1
        _offsetDeg.value = 0f
        _calibrated.value = false
        val c = ctx ?: return
        PrefsBridge.delete(c.contentResolver, PrefsBridge.full(PrefsBridge.SIGN))
        PrefsBridge.delete(c.contentResolver, PrefsBridge.full(PrefsBridge.OFFSET))
    }

    /**
     * 从 `Settings.System` 读标定值。App 与引擎**都**会调：
     *   - 引擎启动时读一次（拿到上次的值）；
     *   - App 界面刷新时读（引擎可能刚自动修正过符号位）。
     */
    fun refreshCalibFromSettings() {
        val cr = ctx?.contentResolver ?: return
        val sRaw = PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.SIGN))
        val oRaw = PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.OFFSET))
        // 键不存在时**重置**为默认（而不是保留旧值）—— 否则清空标定之后界面还显示旧偏移。
        _sign.value = (sRaw?.toIntOrNull() ?: 1).let { if (it < 0) -1 else 1 }
        _offsetDeg.value = oRaw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
        // ★ 判据是"键在不在"，不是"值是否非 0"：标定成 0° 偏移是合法结果。
        _calibrated.value = oRaw != null

        // ★★ 分步进度对齐（2026-10-03，只在 App 侧做）。两个方向，都要：
        //   ① 系统说"没校准" ⇒ App 的进度必须跟着归零。否则用户点了「恢复默认」、
        //      引擎那边也清干净了，界面却还挂着"① ✓ ② ✓"。
        //   ② 系统说"已校准"、而 App 的进度**一格都没有** ⇒ 那是本次记账机制之前
        //      就已经校准过（旧版本、或重装）。此时**补成两步都完成**。
        //   ⚠️ 补的方向只能是"都完成"，⛔ 不能补成"只完成 ①"：那会让一个明明校准好的
        //     用户看到"还差 ②"，白白再去做一遍（② 要转手机 + 对准脸，成本不低）。
        //   ⚠️ 这里**只改内存不落盘**：它是派生修正，下次冷启动读盘得到 false 后会走
        //     同一段代码再补回来，结果一致 ⇒ 省一次 IO 写入。
        //   ⚠️ 引擎侧跳过（[prefs] 为 null）：这是我们自己的界面记账，引擎读不到也不需要。
        if (prefs != null) {
            if (!_calibrated.value) {
                _calibStep1Done.value = false
                _calibStep2Done.value = false
            } else if (!_calibStep1Done.value && !_calibStep2Done.value) {
                _calibStep1Done.value = true
                _calibStep2Done.value = true
            }
        }
    }

    /**
     * 是否已标定。
     * 判据是**键存在与否**（而不是"值是否非 0"）——
     * 因为「已标定成 0° 偏移」是完全合法的结果，用值判断会把它当成没标定。
     */
    val isCalibrated: Boolean
        get() = ctx?.let {
            PrefsBridge.readString(it.contentResolver, PrefsBridge.full(PrefsBridge.OFFSET)) != null
        } ?: false

    // ---------------------------------------------------------------- 接管状态（引擎的账）

    /**
     * 记下「我们接管时把系统自动旋转从什么值关成了 0」，供交还时还原。
     *
     * ★★ **只在"我们确实改过它"时才记**（`cur != 0`）；本来就是 0 时记 [AUTO_ROTATE_UNTOUCHED]。
     *   判据与理由见 [AUTO_ROTATE_UNTOUCHED] 那段 —— 一句话：**没动过的东西不欠，
     *   交还时也就没资格写。**
     *
     * ★ 必须落盘：进程被系统强杀时实例变量会丢，导致 accelerometer_rotation
     *   永久停在 0（系统自动旋转再也回不来）—— 这是实测踩过的事故。
     * ★ 只在引擎进程调用（谁接管谁交还），所以只写 Settings。
     */
    fun setRestoreTarget(v: Int) {
        val c = ctx ?: return
        PrefsBridge.writeString(c.contentResolver, PrefsBridge.full(PrefsBridge.RESTORE), v.toString())
    }

    /**
     * 交还目标值。
     *
     * ⚠️ **默认值是哨兵 [AUTO_ROTATE_UNTOUCHED]，不是 1**（2026-09-30 改）。
     *   旧版默认 1 的语义是"没记录就当我们要过 1"，于是没记录时也会被写一笔；
     *   现在没记录 = 没动过 = **不写**。调用方必须先判哨兵，见 `releaseTakeover`。
     */
    fun restoreTarget(): Int {
        val cr = ctx?.contentResolver ?: return AUTO_ROTATE_UNTOUCHED
        return PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.RESTORE))
            ?.toIntOrNull() ?: AUTO_ROTATE_UNTOUCHED
    }

    fun setTakeoverActive(active: Boolean) {
        val c = ctx ?: return
        PrefsBridge.writeString(
            c.contentResolver,
            PrefsBridge.full(PrefsBridge.TAKEOVER),
            if (active) "1" else "0",
        )
    }

    /** 上次退出时是否还开着接管 —— 用于启动时做「孤儿接管」检测与还原 */
    fun isTakeoverActive(): Boolean {
        val cr = ctx?.contentResolver ?: return false
        return PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.TAKEOVER)) == "1"
    }

    // ---------------------------------------------------------------- 前摄 id（引擎自己的账）

    /**
     * 记下"上次真正采到过帧的前摄 id"。
     *
     * ★ 只由引擎在**确实收到帧**之后调用（见 `AdaptiveEngine.noteFrontIdSuccess`），
     *   所以它代表的是"这个 id 在这台机器上真的能用"，而不是"试过"。
     * ★ 归引擎管：它由引擎写、引擎读，中途被 App 覆盖反而可能把"可用 id"抹掉。
     */
    fun setPreferredCameraId(id: String) {
        val c = ctx ?: return
        PrefsBridge.writeString(c.contentResolver, PrefsBridge.full(PrefsBridge.CAMID), id)
    }

    /** 上次采到过帧的前摄 id；没有记录返回空串（引擎按 id 升序试） */
    fun preferredCameraId(): String {
        val cr = ctx?.contentResolver ?: return ""
        return PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.CAMID))
            ?.takeIf { it.isNotEmpty() } ?: ""
    }

    // ================================================================ 一次性迁移

    /**
     * 把旧通道里的配置搬进新的 prefs 文件。**只在目标键缺失时才搬**，绝不覆盖。
     *
     * 为什么需要它：改造前配置真身住在两处 ——
     *   ① `Settings.System` 的自定义键（两进程都认的那个"镜像"）；
     *   ② App 用 `MODE_PRIVATE` 打开的那份 prefs 文件。
     * 而新实现写的是 **safe-zone 里的新文件**（LSPosed 重定向后的目录），
     * 首次启动时它是空的 ⇒ 不做迁移，用户刚配好的模式、标定就全丢了。
     *
     * ★ 迁移的活只能在 App 进程干：只有它有权限读 Settings，
     *   也只有它写得了这个 prefs 文件（引擎在 SystemUI，两样都做不到）。
     *
     * ⚠️ 标定值**不在迁移范围**：它本来就住在 Settings（引擎的账），
     *   改完后引擎照样从那儿读，压根没挪窝。
     */
    private fun migrateLegacyIfNeeded(app: Context, p: SharedPreferences) {
        // 新文件里已经有**按形态分的**模式项 ⇒ 认为迁移过（或本来就是全新用户），直接返回。
        // ⚠️ 判据必须看新键：旧键 `rotate_mode` 停止写入后，拿它判会永远判成"没迁移过"
        //    而每次启动都重放一遍迁移（虽然不覆盖、无害，但白跑）。
        // ⚠️ `MODE_OUTER` 是**退役键**（外屏增强 2026-09-29 已删，见 PrefsBridge），
        //    这里只是拿它当"09-28 那版写过的新文件"的指纹 —— 那种文件里必然也有 MODE_INNER。
        if (p.contains(PrefsBridge.MODE_INNER) || p.contains(PrefsBridge.MODE_OUTER)) return

        val cr = app.contentResolver
        // 源①：旧位置的 prefs 文件（App 用 MODE_PRIVATE 时写的）
        val legacyLocal = readLegacyLocalPrefs(app)
        // 源②：Settings 自定义键（旧通道的"真身"，两个进程都写过）
        fun fromSettings(key: String): String? = PrefsBridge.readString(cr, PrefsBridge.full(key))

        // ⚠️ 这两个源里找的都是**旧键名**（`rotate_mode` / `hyperplus_rotate_mode`），
        //   不是新的 `rotate_mode_inner` —— 它们存在的前提就是"那时候还没有内外屏之分"。
        val mode = fromSettings(PrefsBridge.MODE_LEGACY) ?: legacyLocal[PrefsBridge.MODE_LEGACY] as? String
        val strategy = fromSettings(PrefsBridge.STRATEGY) ?: legacyLocal[PrefsBridge.STRATEGY] as? String
        val handoff = fromSettings(PrefsBridge.HANDOFF_ROTATE) ?: legacyLocal[PrefsBridge.HANDOFF_ROTATE]?.toString()
        val gate = fromSettings(PrefsBridge.GATE) ?: legacyLocal[PrefsBridge.GATE]?.toString()

        if (mode == null && strategy == null && handoff == null && gate == null) return

        runCatching {
            val e = p.edit()
            if (enumOrNull<RotateMode>(mode) != null) {
                // ★ 迁移后的语义：原来那一档就是现在的内屏档（唯一在用的那一份）。
                //   ⛔ 09-28 那版这里还写了一份 MODE_OUTER；外屏增强删掉之后它没有下游，
                //     写它只会让"还有一个外屏配置"这个错觉继续存在。
                e.putString(PrefsBridge.MODE_INNER, mode)
            }
            if (enumOrNull<CaptureStrategy>(strategy) != null) e.putString(PrefsBridge.STRATEGY, strategy)
            if (handoff != null) e.putBoolean(PrefsBridge.HANDOFF_ROTATE, handoff == "true" || handoff == "1")
            if (gate != null) e.putBoolean(PrefsBridge.GATE, gate == "true" || gate == "1")
            e.apply()
            Log.i(
                TAG,
                "已从旧通道迁移配置：mode=$mode（灌给内屏那一份）strategy=$strategy handoff=$handoff gate=$gate",
            )
        }.onFailure { Log.w(TAG, "旧配置迁移失败（用户需手动重设）", it) }
    }

    /**
     * 直接解析**旧位置**的 prefs XML。
     *
     * ⚠️ 不能用 `getSharedPreferences` 去读它：LSPosed 启用后 `getPreferencesDir()` 被
     *   重定向到 safe-zone，普通 API 再也指不到旧目录。而 App 对自己的 `dataDir`
     *   天然有读权限（DAC + SELinux 都放行），所以直接按路径解析文件是唯一可行且干净的办法。
     */
    private fun readLegacyLocalPrefs(app: Context): Map<String, Any> {
        val f = File(app.dataDir, "shared_prefs/$NAME.xml")
        if (!f.isFile || !f.canRead()) return emptyMap()
        val out = mutableMapOf<String, Any>()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            FileInputStream(f).use { parser.setInput(it, null) }

            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    if (!key.isNullOrEmpty()) {
                        when (parser.name) {
                            "string" -> out[key] = parser.nextText()
                            "boolean" -> out[key] = parser.getAttributeValue(null, "value") == "true"
                            "int" -> out[key] = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                            "float" -> out[key] = parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                        }
                    }
                }
                ev = parser.next()
            }
            out
        }.onFailure { Log.w(TAG, "解析旧 prefs 失败", it) }.getOrDefault(emptyMap())
    }

    // ================================================================ 工具

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        if (name.isNullOrEmpty()) null
        else runCatching { enumValueOf<T>(name) }.getOrNull()
}

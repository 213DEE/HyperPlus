package cn.dsr213.hyperplus

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
 * ★★★ **「每个应用单独适配」的旋转方式**（2026-10-05 新增）—— 应用列表里一行一个。
 *
 * 用户原话（2026-10-05）：「应用列表「**每个应用单独适配**」（跟随全局 / 跟随系统 /
 * 自适应 / 半自动）」，并拍板「**不在名单的应用视为跟随全局**」。
 *
 * ============================ 四个档位各自是什么意思 ============================
 *
 * | 档 | 该应用前台时用的档 | 落盘在哪 |
 * |---|---|---|
 * | [FOLLOW_GLOBAL] | **当前生效的全局档**（[AppPrefs.modeInner]） | 不落盘（= 没被指名） |
 * | [SYSTEM] | [RotateMode.SYSTEM]（本应用不干预它） | **名单的 add/remove 两份集合** |
 * | [ADAPTIVE] | [RotateMode.ADAPTIVE] | [PrefsBridge.APP_ROTATE_MODES] |
 * | [SEMI] | [RotateMode.SEMI] | [PrefsBridge.APP_ROTATE_MODES] |
 *
 * ★★ **为什么后两档的"跟随系统 / 跟随全局"不落本键** —— 这是本节最要紧的一处取舍：
 *   改造前那个布尔开关就是这两档（开 = 跟随系统 = 进名单、关 = 跟随全局 = 出名单）
 *   ⇒ 借道既有名单之后，**老用户勾过的每一格语义不变、零迁移**，
 *     而且"谁不受本应用控制"仍然只有一个真值来源（[AppWhitelist.resolve]）。
 *   ⛔ 别为了"四个档整齐"把这两档也写进 [PrefsBridge.APP_ROTATE_MODES]：
 *     那会立刻造出两份互相打架的名单，且"到底听谁的"变成第二个真值。
 *
 * ⚠️ 顺序 = 界面上下拉里的顺序（**逐字照用户给的次序**，见 [PICKER]）——
 *   多语言纪律：中文原字面量逐字搬，⛔ 别顺手"优化"成「默认 / 系统 / 人脸 / 点击」之类。
 */
enum class AppRotateMode(
    /**
     * 解析后的旋转档。
     * `null` = **[FOLLOW_GLOBAL]**（跟随全局）—— 它不是一个具体的档，而是"不指名"。
     */
    val mode: RotateMode?,
) {
    /** 跟随全局：用「旋转增强」里那一档（出厂默认） */
    FOLLOW_GLOBAL(null),

    /** 跟随系统：本应用交给系统，本应用不干预（= 旧开关"打开"） */
    SYSTEM(RotateMode.SYSTEM),

    /** 自适应：这个人脸转 */
    ADAPTIVE(RotateMode.ADAPTIVE),

    /** 半自动：这个弹按钮让用户确认 */
    SEMI(RotateMode.SEMI);

    /**
     * 是否**需要写进** [PrefsBridge.APP_ROTATE_MODES]。
     *
     * ★ 判据 = "它是不是一个**具体的、要干预**的档"（[RotateMode.engages]）——
     *   由 [mode] 派生，⛔ 没有第二份名单。见类注释那条取舍。
     */
    val stored: Boolean get() = mode?.engages == true

    /**
     * **界面口径**的名字（多语言）。
     *
     * ⚠️ 这里**刻意只有一份**（没有 [RotateMode] 那种 `label` 日志口径）：它与
     *   `SplitUnfoldDirection.labelRes` 同源 —— **只有界面一处用途**（引擎取档用的是
     *   [mode]，日志打的是那个枚举自己的名字）。将来引擎真要拿它打日志，再加一份不迟。
     */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            FOLLOW_GLOBAL -> R.string.app_mode_follow_global
            SYSTEM -> R.string.app_mode_system
            ADAPTIVE -> R.string.app_mode_adaptive
            SEMI -> R.string.app_mode_semi
        }

    companion object {

        /**
         * 界面上"四选一"的**顺序**。
         *
         * ★ 逐字取用户给的次序（跟随全局 / 跟随系统 / 自适应 / 半自动）——
         *   它同时是"从最不干预到最干预"的方向，读起来也是顺的。
         */
        val PICKER: List<AppRotateMode> = listOf(FOLLOW_GLOBAL, SYSTEM, ADAPTIVE, SEMI)

        /**
         * 读盘：`"<包名>=<枚举名>\n…"` → map。
         *
         * ★ **防御式解析**（与 [AppWhitelist.decode] 同一条纪律）：文件可能被手改过、
         *   也可能是更老的版本留下的，任何一行解析不出来都**只跳过那一行**，
         *   ⛔ 不抛异常 —— 这条路径跑在**界面冷启动**上，抛一次就是整个界面起不来。
         */
        fun decode(raw: String?): Map<String, AppRotateMode> {
            if (raw.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, AppRotateMode>()
            raw.split('\n').forEach { line ->
                val s = line.trim()
                if (s.isEmpty()) return@forEach
                val i = s.indexOf('=')
                if (i <= 0) return@forEach
                val pkg = s.substring(0, i).trim()
                if (pkg.isEmpty()) return@forEach
                val v = runCatching { valueOf(s.substring(i + 1).trim()) }.getOrNull()
                    ?: return@forEach
                out[pkg] = v
            }
            return out
        }

        /**
         * 落盘：map → `"<包名>=<枚举名>\n…"`（排序后，只写 [stored] 的那些）。
         *
         * ★ 排序 + 单键字符串：与 [AppWhitelist.encode] 同源的两个理由 ——
         *   ① 穿过广播快照的长度前缀格式时它天然是一个可打印的差异；
         *   ② 引擎侧"内容变没变"的判据是**整快照比对**（见 `ModulePrefs.advanceBaseline`），
         *      稳定的顺序能让"改了一个包"在诊断日志里一眼可见。
         */
        fun encode(m: Map<String, AppRotateMode>): String =
            m.filterValues { it.stored }
                .toSortedMap()
                .entries.joinToString("\n") { "${it.key}=${it.value.name}" }
    }
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
 * 「**2 分屏展开方向**」—— 轻折一下追加一格分屏时，**当前应用留在哪一侧**。
 *
 * ★ 用户 2026-10-04 点名的选项（原话：「直接在 app 里面给选项「2 分屏展开方向：朝左 / 朝右」」）。
 *   两个档位就是用户给的那两个词，**逐字照搬** —— ⛔ 别顺手"优化"成「左侧 / 右侧」「左 / 右」
 *   之类的说法（多语言那一节的纪律：中文原字面量逐字搬）。
 *
 * ★ 为什么说的是"当前应用留哪侧"、而不是"新格开哪侧"：这是同一件事的两面
 *   （两块屏，一个留、一个进），而"当前应用"是用户**看得见、能当场验证**的那个对象 ——
 *   折一下看它跳到哪边，比数"新格排在左边还是右边"直观得多。
 *
 * ⚠️ **物理落点还没钉死**：证据目前只到「两条手势分支往里送的常量不同」
 *   （✅ 反汇编实测，`docs/分屏增强_实现方案_2026-10-04.md` §1.10.6），
 *   而「哪个常量 = 左边」**证不出来** ⇒ 观察钩子（§6 第 2 步）正是为了钉这一件事。
 *   ⛔ 在钩子给出现场证据之前，别在任何实现里写死"LEFT 对应系统的几"。
 */
enum class SplitUnfoldDirection {
    /** 朝左：轻折新增一格时，当前应用留在左边 */
    LEFT,

    /** 朝右：轻折新增一格时，当前应用留在右边 */
    RIGHT;

    /**
     * **界面口径**的名字（多语言）。
     *
     * ⚠️ 这里**刻意只有一份**（没有 [RotateMode] 那种 `label` 日志口径）：判据与
     *   `CaptureStrategy.summaryRes` 相同 —— **只有界面一处用途**。将来引擎真要拿它打日志，
     *   再加一份不迟，那时两处要一起改。
     */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            LEFT -> R.string.split_dir_left
            RIGHT -> R.string.split_dir_right
        }
}

/**
 * ★★★ 「轻折一下」的**四个触发阈值** —— **编译期常量**（2026-10-06 起）。
 *
 * 它们就是 `SplitTrigger` 判定状态机的全部输入量（代号沿用方案文档 §3）：
 *
 * | 字段 | 含义 | 判据 |
 * |---|---|---|
 * | [d1] | **进入**：比展开基线低这么多度 ⇒ 认为"用户开始折了" | `A0 - angle > D1` |
 * | [d2] | **回弹**：回到基线 -D2 以内 ⇒ 认为"折完松手了" | `angle ≥ A0 - D2` |
 * | [d3] | **合上保护**：比基线低这么多度 ⇒ 认为"这是要合上手机"，不触发 | `A0 - angle ≥ D3` |
 * | [winMs] | **判定时限**：从进入开始算，超时就不触发 | `now - 进入 > win` |
 *
 * ============================ 🔴 它曾经是用户配置（2026-10-04 ~ 10-06） ============================
 * 那时由「角度校准」页测出来、用户按「确认」生效，住 `AppPrefs.splitCalib`
 * （键 `split_calib`）。用户 **2026-10-06** 原话：
 * 「**把角度校准功能删掉，不给这么多自定义功能，越多越难做**」
 * ⇒ 整条校准链下线，本类**退回只读常量**：[DEFAULT] 是**唯一真身**，没有第二条路径能改它。
 * ⛔ **别把校准加回来**（已否一次）。要调阈值 = 改 [DEFAULT] 重新编译。
 * ⚠️ 随校准一起删掉的还有 `fromDemo` / `decode` / `summary` / `trim` 四个方法 ——
 *   它们全部只服务"校准页 + 落盘"。只留 [encode]，因为引擎启动日志要打这一行。
 *
 * ============================ 为什么这几个数能拿来用 ============================
 * 它们不是拍的，是 2026-10-04 上午拿 `SplitTrigger` 的**原始折角轨迹日志**
 * 在本机定标出来的（全展 179° / 半开 89~92° / 合上 2~3° / 真·轻折落差 24~52°、
 * 完整来回 1122ms）—— 见 `docs/分屏增强_实现方案_2026-10-04.md` §3.1。
 *
 * 🔴 **2026-10-06 用户口径定死两处**（原话：「轻折一下、**在 1 秒内**展开才触发，
 *   轻折一下、但是**超过 1 秒**；或者**折角超过 90 度**都不触发」）：
 * - 窗口 `winMs` 由 1500 收到 **1000**（"1 秒内"）—— 它是从【进入】那一刻开始算的**绝对期限**。
 * - 合上保护 `d3 = 90`（"折角超过 90 度不触发"）—— 它本来就是 90，本轮只是把它**冻结**。
 *   ⚠️ `d3` 是**相对量**："比展开基线低了 90°"。本机全展 ≈179° ⇒ 等价于"铰链角掉到 89°
 *     以下"，与用户说的"折角超过 90 度"是同一件事（90° 恰好是本机的半开位）。
 */
data class SplitThresholds(
    val d1: Float,
    val d2: Float,
    val d3: Float,
    val winMs: Long,
) {
    /** 日志格式 `"<D1>|<D2>|<D3>|<winMs>"`（只被 `SplitTrigger` 的启动日志用） */
    fun encode(): String = "$d1|$d2|$d3|$winMs"

    companion object {
        /**
         * ★★★ **唯一真身** —— 引擎（`SplitTrigger`）与界面都读这一份。
         * ⛔ 别在别处再写一份字面量：两个真身的后果是"日志说 A、实际按 B 判"。
         */
        val DEFAULT = SplitThresholds(d1 = 20f, d2 = 6f, d3 = 90f, winMs = 1_000L)
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

    /**
     * 配置推送的**防抖窗口**（2026-10-03 新增，广播通道）。
     *
     * ★ 为什么要防抖：一次界面操作常常连着写好几个键（例如切模式 = 模式 + 整组落盘），
     *   每个键各发一条广播是浪费，引擎那边每次还得跑一遍全量比对。
     *   200ms 足够把同一批写入合并成一条，体感上仍然是"立刻生效"。
     */
    private const val CONFIG_PUSH_DEBOUNCE_MS = 200L

    /**
     * 配置文件名 —— 2026-10-03 迁移后**全工程唯一的真值**。
     *
     * ★ 从前这里写着「必须与 `ModulePrefs.PREFS_NAME` 一字不差」，因为引擎要按路径
     *   去读这个文件（靠 LSPosed 的 nsp 把它重定向到双方都能读的 safe-zone）。
     *   迁移后引擎**不再读任何文件**（配置走广播快照 + `Settings` 镜像）
     *   ⇒ 那个对称约束已不存在，`ModulePrefs` 里的同名常量也已删除。
     *   ⚠️ 但**别以为它就可以随便改了**：它现在实质上是"落盘格式"级别的常量 ——
     *     改掉它会让老用户升级后读到一个空文件（配置看起来凭空丢失）。
     */
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
     * ★★★ **自适应「读不到环境」时临时降级半自动**（2026-10-05 新增，R1）—— 用户配置。
     *
     * ★ 默认 `true`（理由见 [PrefsBridge.ADAPTIVE_FALLBACK]：暗光下自适应的表现是
     *   "转手机完全没反应"，用户分不清"坏了"和"环境不允许"）。
     * ★ **引擎要读它** ⇒ 两条读盘路径成对灌，见 [reloadFromPrefs] 与 [applyFromModulePrefs]。
     */
    private val _r1Fallback = MutableStateFlow(true)

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
    /**
     * ★ 「实验功能 → 提高分屏上限」总闸（2026-10-06）。
     *
     * 默认 **false**。打开 ⇒ `SplitStageLimit` 把多分屏上限抬到 8（**要重启系统界面**）；
     * 关着 ⇒ 那边**什么都不做**，系统默认（本机 6）原样保留。
     *
     * ⚠️ 它**必须能被模块侧读到**（这一点与 [experimentalAdaptive] 不同）：那个只管界面
     *   可见性；这一个要经「App prefs → 全量快照 → Settings 镜像 → 模块」那条链走一趟。
     *   ★ 链路**零新增** —— 快照本来就是 `prefs.all` 全量抽。
     */
    private val _experimentalMultiSplit = MutableStateFlow(false)

    /**
     * ★★ 「**2 分屏展开方向**」—— 用户 2026-10-04 点名要的选项。
     *
     * 含义与值的判据见 [SplitUnfoldDirection]；键名与落盘约定见 [PrefsBridge.SPLIT_UNFOLD_DIR]。
     *
     * ⚠️ 默认值**暂定 [SplitUnfoldDirection.LEFT]**：它现在还没有现场证据支撑
     *   （"哪一侧对应系统的哪个值"要等观察钩子，见 [SplitUnfoldDirection] 的注释）。
     *   选 LEFT 只是因为用户给的文案里"朝左"写在前面 —— ⛔ 别把它当成已定的事实，
     *   钩子给出结论之后，若与原生手感不一致，这里要跟着改。
     *
     * ⚠️ 它是**引擎要读的配置**（分屏触发要用）⇒ 两条读盘路径都要灌，见
     *   [reloadFromPrefs] 与 [applyFromModulePrefs]，⛔ 别只加一处。
     */
    private val _splitUnfoldDir = MutableStateFlow(SplitUnfoldDirection.LEFT)

    // ------------------------------------------------ 高温保护（2026-10-05 用户点名）

    /**
     * ★★ 多分屏的**高温保护阈值**（摄氏度，整数）—— 用户配置。
     *
     * 语义与取值范围见 [PrefsBridge.SPLIT_THERMAL_LIMIT_C]（那里写清了它到底改的是什么、
     * 为什么上限是 60）。
     * ⚠️ 默认值恒等于系统的出厂值 [THERMAL_LIMIT_C_DEFAULT] —— 界面上的「恢复默认」
     *   按钮写的就是它，⛔ 别在界面里另写一个字面量。
     */
    private val _splitThermalLimitC = MutableStateFlow(THERMAL_LIMIT_C_DEFAULT)

    /**
     * ★★ **是否关掉多分屏的高温保护**（默认 false）。
     *
     * 语义与风险见 [PrefsBridge.SPLIT_THERMAL_GUARD_OFF]。
     * ⚠️ 它与 [_splitThermalLimitC] 是**两个独立手段**，⛔ 别合并语义。
     */
    private val _splitThermalGuardOff = MutableStateFlow(false)

    /**
     * 温度上限的**取值边界与默认值** —— ⛔ 三处算术都以这里为唯一真值。
     *
     * ★ [THERMAL_LIMIT_C_DEFAULT] = **47**：不是我们拍的，是**照抄系统的出厂值**
     *   （`MultiTaskingTemperatureObserver.HIGH_TEMPERATURE = 47`，实测记录见
     *   `docs/分屏增强_实现方案_2026-10-04.md` §14.2）⇒ 「恢复默认」＝"回到厂商设定"。
     * ★ [THERMAL_LIMIT_C_MAX] = **60** 是**用户点名的硬顶**（原话「禁止超过60度」）——
     *   它是"输入框接受的最大值"，也是 [setSplitThermalLimitC] 的夹取上界。
     *
     * ★★ [THERMAL_LIMIT_C_MIN] = **40** —— **2026-10-05 由单测纠正过一次**（原为 30）。
     *
     *   原本按"低于某个值就等于关掉保护"这个直觉取了 30。但本机日常板温实测是
     *   **35.969°C**（见 `docs/分屏增强_实现方案_2026-10-04.md` §14.2.1）
     *   ⇒ 下限 30 **低于日常温度**，用户把输入框拉到最小，阈值就落到常温之下
     *   ⇒ 高温判定**永远不会触发** ⇒ 那是一条**不经过二次确认弹窗**的关保护路径，
     *   正好绕开我们特意加的那道闸（[PrefsBridge.SPLIT_THERMAL_GUARD_OFF]）。
     *
     *   ⚠️ 这个错是 `ThermalLimitTest.minIsAboveIdleBoardTemperature` **抓出来的**
     *   （它当时真的红了）—— 那条测试的存在理由就是钉死"下限必须高于日常板温"。
     *
     *   取值理由：40 明显高于日常 36（留 4°C 余量，覆盖"轻微发热但远没到关机"的常态），
     *   又明显低于出厂 47 —— 用户往低调时仍有一段**真实有效**的区间（40~46）。
     *   ⛔ 别再往下调：每降 1°C 就多一分"这个输入框其实是隐形开关"的风险。
     */
    const val THERMAL_LIMIT_C_MIN = 40
    const val THERMAL_LIMIT_C_MAX = 60
    const val THERMAL_LIMIT_C_DEFAULT = 47

    /**
     * 把任意温度值归一成**合法的摄氏度整数**（夹在 [[THERMAL_LIMIT_C_MIN], [THERMAL_LIMIT_C_MAX]]）。
     *
     * ★ 与 [snapHintMs] 同型：归一放在**唯一入口**（[setSplitThermalLimitC] 与两条读盘路径），
     *   而不是只靠界面输入框的过滤器 —— 配置将来可能从别处写（迁移 / 文件被手改），
     *   把边界收在入口上，引擎读到的就恒是合法值。
     */
    fun clampThermalLimitC(v: Int): Int = v.coerceIn(THERMAL_LIMIT_C_MIN, THERMAL_LIMIT_C_MAX)

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
     * 「弹一次按钮给我看看」的请求（值 = `<时间戳>|<目标方向>`）。
     *
     * ★ 为什么需要：半自动按钮只在传感器判定"设备姿态 ≠ 屏幕方向"时出现，而传感器
     *   **没法用 adb 注入** ⇒ 想看一眼按钮长什么样，只能靠人把手机转一下。
     *   调外观要转一次手机，这不可接受。
     * ⚠️ 它**只影响外观验证**：弹出来的按钮点下去走的是真实路径（写方向 + 读回），
     *   所以这不是"绕过传感器的入口"，只是让人能看见按钮。
     */
    private val _hintTestReq = MutableStateFlow("")

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
     * ★★★ **每个应用单独指定的旋转方式**（2026-10-05 新增，R2）—— 键名与编码见
     * [AppPrefs.AppRotateMode] 与 [PrefsBridge.APP_ROTATE_MODES]。
     *
     * ⚠️ **它只装 [AppRotateMode.ADAPTIVE] / [AppRotateMode.SEMI]**
     *   —— 另外两档由名单那两份集合表达（取舍见 [AppRotateMode] 类注释）。
     *   ⇒ 引擎侧**不能**只读它来回答"这个应用该用哪一档"，必须走 [modeFor]。
     *
     * ★ 为什么它是个 `StateFlow` 且引擎会 `collect`：用户改一个应用的方式 ⇒
     *   引擎要在 2 秒内（下一次前台巡检）按新档干活，不需要重启任何东西。
     *   与 [_whitelist] 同一条纪律。
     */
    private val _appModes = MutableStateFlow<Map<String, AppRotateMode>>(emptyMap())

    // ---------------------------------------- 分屏名单（2026-10-05 新增，**与上面那份独立**）

    /**
     * 分屏名单：用户**手动加进来**的包。
     *
     * ★★ 与 [_wlAdd] **刻意是两份东西**（用户 2026-10-05 拍板「各自独立一份」）——
     *   命中语义不同（"不干涉转屏" vs "折一下也不分屏"），混一份会让改一边、另一边莫名变化。
     *   键名与落盘约定见 [PrefsBridge.SPLIT_WHITELIST_ADD]。
     */
    private val _swlAdd = MutableStateFlow<Set<String>>(emptySet())

    /** 分屏名单：用户**手动移出**的包（独立减集，理由同 [_wlDel]） */
    private val _swlDel = MutableStateFlow<Set<String>>(emptySet())

    /**
     * **生效**分屏名单（派生值 = [SplitWhitelist.resolve] 的结果）。
     *
     * ★ 为什么存派生态而不是每次现算：引擎侧要在**每次折叠判定成立时**做一次集合查找
     *   （见 `SplitTrigger.onTrigger` 的前置闸），现算就得每次拼一遍默认清单的 30 多个包名。
     *   派生值只在输入变化时重算，见 [refreshSplitWhitelist]。
     *
     * ⚠️ 它是 StateFlow 且引擎会读它 —— 与 [_whitelist] 同一条纪律：
     *   用户改一个开关 ⇒ 引擎下一次折叠就用新集合（不需要重启任何东西）。
     */
    private val _splitWhitelist = MutableStateFlow<Set<String>>(emptySet())

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

    // ★ 2026-10-03 迁移（libxposed API 102）时**删掉了两个状态**：`appChannelOk` /
    //   `appChannelDiagRes`。它们是「App 侧自检」：靠**能不能以 `MODE_WORLD_READABLE`
    //   打开 prefs** 来推断「本应用有没有被 LSPosed 注入」，因为只有被注入时框架才会把
    //   prefs 重定向到引擎读得到的 safe-zone（`getPreferencesDir` 那个 hook）。
    //
    //   新 API 下这条链路**整个消失**：引擎不再读 prefs 文件，配置改由 App 推广播
    //   （见 [ConfigChannel] / `module/ModulePrefs`），App 也就**不再需要被注入**。
    //   ⇒ 那个判据恒为假 —— 留在界面上就是一条**永远挂着的假警报**。
    //
    //   ★ 现在「配置通道通没通」只剩**一个真值来源**：引擎在状态摘要里回传的
    //     `cfgold` / `cfgmsg`（解析成 [cn.dsr213.hyperplus.ModuleLink.State.cfgOk] / `cfgMsg`）。
    //     界面直接读它 —— 见 `ui/SettingsPage`（前置条件横幅）与 `ui/DiagnosticsPage`（读数）。
    //   ⛔ 别再补回任何「App 侧自检」：它成立的前提（App 进程必须被注入）已经没了。

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
     * R1（2026-10-05）：自适应读不到环境时，是否临时降级成半自动。
     *
     * ★ 界面**只读**它画那个开关；真正的判定在引擎侧
     *   （`AdaptiveEngine.noteAdaptiveEnv` 累积、`mode()` 消费）。
     *   ⛔ 别在界面里复现"几轮才算降级"那套判据 —— 那是引擎的账。
     */
    val r1Fallback: StateFlow<Boolean> = _r1Fallback.asStateFlow()

    /**
     * 「实验功能 → 自适应旋转」是否已启用（默认 false）。
     *
     * ★ 只被**界面**读：`false` 时「旋转增强 → 模式」不列出 `ADAPTIVE` 那一档。
     * ⚠️ 引擎不读它，也不该读 —— 见 [PrefsBridge.EXPERIMENTAL_ADAPTIVE]。
     */
    val experimentalAdaptive: StateFlow<Boolean> = _experimentalAdaptive.asStateFlow()
    /** 见 [_experimentalMultiSplit] */
    val experimentalMultiSplit: StateFlow<Boolean> = _experimentalMultiSplit.asStateFlow()

    /** 「2 分屏展开方向」（界面显示 / 修改；引擎侧从镜像读同一份真值，见 [SplitUnfoldDirection]） */
    val splitUnfoldDir: StateFlow<SplitUnfoldDirection> = _splitUnfoldDir.asStateFlow()


    /**
     * 多分屏的**高温保护阈值**（摄氏度；30~60，默认 [THERMAL_LIMIT_C_DEFAULT]）。
     * ★ 界面输入框读它；引擎侧从镜像读同一份真值（见 [PrefsBridge.SPLIT_THERMAL_LIMIT_C]）。
     */
    val splitThermalLimitC: StateFlow<Int> = _splitThermalLimitC.asStateFlow()

    /**
     * 是否**关掉**多分屏的高温保护（默认 false ⇒ 保护是开的）。
     * ★ 界面开关读它；引擎侧从镜像读同一份真值（见 [PrefsBridge.SPLIT_THERMAL_GUARD_OFF]）。
     */
    val splitThermalGuardOff: StateFlow<Boolean> = _splitThermalGuardOff.asStateFlow()

    /** 半自动按钮等待时长（毫秒，1000~60000）。界面滑条读它，引擎侧由配置通道灌入 */
    val hintMs: StateFlow<Int> = _hintMs.asStateFlow()

    /** 「预览旋转按钮」的请求（值 = `<时间戳>|<目标方向>`）。引擎 `collect` 它并弹一次按钮 */
    val hintTestReq: StateFlow<String> = _hintTestReq.asStateFlow()

    /**
     * 生效的应用白名单（默认清单 + 用户增删）—— 界面画开关、引擎判前台门，用的都是它。
     */
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    /**
     * ★★★ 用户**逐个应用指定**的旋转方式（2026-10-05 新增，R2）。
     *
     * ★ 界面只读它渲染"四选一"里**当前选中的是哪一个**（[appModeOf] 才是完整答案）；
     *   引擎**不要**直接读它 —— 走 [modeFor]（它把"跟随全局 / 跟随系统"两档也算进去）。
     *   ⛔ 别在别处自己查表：漏掉名单那一层，症状就是"用户设了跟随系统却还在转"。
     */
    val appModes: StateFlow<Map<String, AppRotateMode>> = _appModes.asStateFlow()

    /**
     * 生效的**分屏名单**（默认游戏清单 + 用户增删）—— 界面画开关、引擎判"折不折"，用的都是它。
     *
     * ★★ 与 [whitelist] **是两份**（用户 2026-10-05 拍板），⛔ 别把两者对齐 / 合并。
     * ⚠️ 引擎侧读它的位置是 `SplitTrigger.onTrigger` 的前置闸 —— 那是**不可逆动作之前**
     *   的最后一道，所以这份集合必须"改完立刻生效"（靠派生值 + StateFlow，见 [_splitWhitelist]）。
     */
    val splitWhitelist: StateFlow<Set<String>> = _splitWhitelist.asStateFlow()

    // ---------------------------------------------------------------- 后端

    /** 读 `Settings.System`（引擎侧的状态/标定值）用的 context */
    @Volatile
    private var ctx: Context? = null

    /** App 进程的 prefs —— 配置**真身**。引擎进程恒为 null（引擎读不到别人的文件，走 [ModulePrefs]） */
    @Volatile
    private var prefs: SharedPreferences? = null

    // ------------------------------------------------------------ 配置推送（广播通道，2026-10-03）

    /**
     * 挂在 [prefs] 上的变更监听器。
     *
     * ⚠️⚠️ **必须留一个强引用**：`SharedPreferencesImpl` 内部是用 `WeakHashMap` 存监听器的
     *   ⇒ 只用 lambda 注册、不持有引用的话，它随时会被 GC 掉；之后"改配置就再也不推了"，
     *   而且**没有任何报错**（最坏的一种失败：静默）。
     */
    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 防抖是否已排程（回调来自框架主线程，见 [scheduleConfigPush]） */
    @Volatile private var pushScheduled = false

    private val pushHandler by lazy { Handler(Looper.getMainLooper()) }

    private val pushRunnable = Runnable {
        pushScheduled = false
        pushConfigNow()
    }

    /** 本进程是否引擎宿主（SystemUI） */
    @Volatile
    private var hostMode = false

    // ================================================================ 初始化：App 进程

    /**
     * **App 进程**初始化。幂等：Activity、TileService 都会调。
     *
     * ★★ 2026-10-03 迁移后打开方式固定为 [Context.MODE_PRIVATE]，**不再是 WORLD_READABLE**。
     *   旧写法是为了走 LSPosed 的 nsp：框架只在那个模式下 hook `checkMode` /
     *   `getPreferencesDir`，把 prefs 重定向到引擎读得到的 safe-zone（另一套 hook 只对
     *   被注入的进程生效）。nsp 已废（官方 2.3.0 移除），配置改由广播下发
     *   ⇒ **没有任何人再读这个文件**，私有目录就是它的正确位置。
     *
     * ★ 顺带消掉的两件麻烦事：① 这条链路**不再依赖「本应用被 LSPosed 注入」**，
     *   所以「safe-zone 前提」不成立了；② 也就不存在「模块没启用时会抛 SecurityException、
     *   要降级 MODE_PRIVATE、还得做目录迁移」那一整套。
     *   ⚠️ 但「文件的路径**可能**变了」这件事仍然存在（若 LSPosed 作用域里本应用的包被去掉）
     *     ⇒ 由 [adoptConfigFromMirrorIfNeeded] 这条安全网兜住，别删它。
     */
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

            // ★★ 迁移后固定 MODE_PRIVATE（理由见上面 KDoc）：没有任何人再读这个文件，
            //   私有目录就是它正确的位置。
            val p = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            prefs = p

            // ★★ 升级安全网：见 [adoptConfigFromMirrorIfNeeded] 的注释。
            //   ⚠️ 位置必须在**注册变更监听之前** —— 监听之后再写，那次「补搬」会立刻
            //     触发一条推送（无害，但会在日志里多出一条莫名其妙的「已推送配置快照」）。
            adoptConfigFromMirrorIfNeeded(p)

            // ★★ 广播通道的挂钩点（2026-10-03）：**一处监听覆盖所有写入**。
            //   改配置的地方有十几处（每个 setter 各自 `edit().apply()`），逐个补"顺手推一次"
            //   必然漏 ⇒ 用 prefs 自己的变更回调，一次注册全部覆盖。
            //   ⚠️ 回调固定由框架在主线程派发（`SharedPreferencesImpl` 的通知走主线程 Handler），
            //     而且**只在"值真的变了"时才回调** —— 所以启动时还要再无条件推一次，
            //     见本函数末尾（引擎可能在 App 上次退出之后才重启，它的镜像会缺）。
            //   ⚠️ 必须留强引用，理由见 [prefsChangeListener]。
            runCatching {
                val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleConfigPush() }
                p.registerOnSharedPreferenceChangeListener(l)
                prefsChangeListener = l
            }.onFailure {
                Log.w(TAG, "注册配置变更监听失败（配置仍会写盘，只是不会主动推给引擎）", it)
            }

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

        // ★★ 再**无条件推一次**（2026-10-03 广播通道）：上面那条变更监听只在"值真的变了"
        //   时才回调，而这里恰恰是"值没变、但引擎需要一份"的典型场景 ——
        //   引擎可能在 App 上次退出之后才起来（SystemUI 被重启 / 刚装机 / 刚升级），
        //   它那份 `Settings` 镜像还是空的。启动时推一次，免去"要用户去动个开关才生效"。
        pushConfigNow()
    }

    /**
     * 排一次防抖推送（窗口见 [CONFIG_PUSH_DEBOUNCE_MS]）。
     *
     * ⚠️ 回调来自框架主线程，`pushScheduled` 基本只在主线程读写；留 `@Volatile`
     *   只为防御 [init] 被后台线程调用那条路径（最坏后果是多推一条广播，无害）。
     */
    private fun scheduleConfigPush() {
        if (pushScheduled) return
        pushScheduled = true
        pushHandler.postDelayed(pushRunnable, CONFIG_PUSH_DEBOUNCE_MS)
    }

    /**
     * 立刻把**当前整份配置**推给引擎。
     *
     * ★ 推的是**全量快照**，不是"改了哪个键"：与引擎侧订阅者的判据一致（它们比的都是整体
     *   快照），而且请求类键（标定 / 预览按钮 / 清除名单 / 熔断复位）本来就是"值变了一个新高"
     *   驱动的 —— 全量快照天然把这件事说清楚，不需要额外协议。详见 [ConfigChannel] 的类注释。
     *
     * ⚠️ 广播**没有回执** ⇒ 这里只能报告"发出去了"，⛔ 不能对界面说"引擎已收到"。
     *   真正的确认来自引擎回报的状态串（`phase=` / `cfgd=`）。
     */
    internal fun pushConfigNow() {
        val c = ctx ?: return
        val p = prefs ?: return
        val snap = ConfigChannel.snapshotOf(p)
        // 空快照不发：只会让引擎那边多一条"空包已丢弃"的日志，没有信息量。
        if (snap.isEmpty()) return
        if (ConfigChannel.sendPush(c, snap)) {
            Log.i(TAG, "已推送配置快照（${snap.size} 个键）")
        }
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
                .putBoolean(PrefsBridge.ADAPTIVE_FALLBACK, _r1Fallback.value)
                .putBoolean(PrefsBridge.EXPERIMENTAL_ADAPTIVE, _experimentalAdaptive.value)
                .putBoolean(PrefsBridge.EXPERIMENTAL_MULTISPLIT, _experimentalMultiSplit.value)
                .putString(PrefsBridge.SPLIT_UNFOLD_DIR, _splitUnfoldDir.value.name)
                .putInt(PrefsBridge.SPLIT_THERMAL_LIMIT_C, _splitThermalLimitC.value)
                .putBoolean(PrefsBridge.SPLIT_THERMAL_GUARD_OFF, _splitThermalGuardOff.value)
                .putInt(PrefsBridge.HINT_MS, _hintMs.value)
            // 白名单落成**单个字符串**（`\n` 分隔），理由见 [AppWhitelist.encode]。
            // 一份名单 × 两份（增 / 减）= 2 个键。
            e.putString(PrefsBridge.WHITELIST_ADD, AppWhitelist.encode(_wlAdd.value))
            e.putString(PrefsBridge.WHITELIST_REMOVE, AppWhitelist.encode(_wlDel.value))
            // ★ 每个应用单独指定的旋转方式（R2）：只装"自适应 / 半自动"两个档，
            //   另外两档由上面那两行（名单）表达 —— 取舍见 [AppRotateMode] 类注释。
            e.putString(PrefsBridge.APP_ROTATE_MODES, AppRotateMode.encode(_appModes.value))
            // ★ 分屏名单：**独立的另一份**（用户 2026-10-05 拍板），同样是"一份名单 × 增/减 2 键"。
            //   ⚠️ 编码复用 [AppWhitelist.encode]（同一个包名逐行约定，见 [SplitWhitelist] 类注释）。
            e.putString(PrefsBridge.SPLIT_WHITELIST_ADD, AppWhitelist.encode(_swlAdd.value))
            e.putString(PrefsBridge.SPLIT_WHITELIST_REMOVE, AppWhitelist.encode(_swlDel.value))
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
        // ★★ R1「环境不可用时降级」（2026-10-05）—— **引擎要读它**（`AdaptiveEngine.mode`
        //   与 `noteAdaptiveEnv` 都看这一项）⇒ 两条读盘路径成对灌，本处是 App 这一条。
        _r1Fallback.value = p.getBoolean(PrefsBridge.ADAPTIVE_FALLBACK, true)
        // ★ 实验开关**只在 App 这条路径上读**（2026-10-03）：
        //   它 gates 的是界面上"列不列出自适应那一档"，引擎对此毫无兴趣
        //   ⇒ 刻意**不**进 [applyFromModulePrefs]（那是 SystemUI 进程读 ModulePrefs 的路径）。
        _experimentalAdaptive.value = p.getBoolean(PrefsBridge.EXPERIMENTAL_ADAPTIVE, false)
        // ★ 「提高分屏上限」（2026-10-06）：与上面那个**不同** —— 它要送到模块侧
        //   （经快照 → 镜像 → `SplitStageLimit`）⇒ 必须进 persistAll，否则快照里
        //   没有它、模块永远读到 null。
        _experimentalMultiSplit.value =
            p.getBoolean(PrefsBridge.EXPERIMENTAL_MULTISPLIT, false)
        // ★ 分屏方向：**引擎侧也要读**（将来触发分屏要用它定左右）⇒ 这条 App 路径与
        //   [applyFromModulePrefs] 那条引擎路径都得灌，漏一条就是"界面改了、引擎没跟上"。
        //   ⚠️ 用 [enumOrNull] 而不是 `enumValueOf`：文件可能被手改过，不认识的值要退回默认档
        //     而不是抛异常（这条读盘路径跑在**界面冷启动**上，抛一次就是整个界面起不来）。
        _splitUnfoldDir.value =
            enumOrNull<SplitUnfoldDirection>(p.getString(PrefsBridge.SPLIT_UNFOLD_DIR, null))
                ?: SplitUnfoldDirection.LEFT
        // ★★ 高温保护两项（2026-10-05）：**引擎侧要读它们**（它据此改写温度观察者的判定）
        //   ⇒ 两条读盘路径成对灌，本处是 App 这一条（引擎那条见 [applyFromModulePrefs]）。
        //   ⚠️ 读的时候也夹一次（见 [clampThermalLimitC]）：文件被手改过 / 是从更老的版本
        //     升级上来的，都可能带着越界值 —— 而这条路径跑在**界面冷启动**上，
        //     越界值会让「恢复默认」的比对失真，也可能让引擎收到一个没意义的阈值。
        _splitThermalLimitC.value =
            clampThermalLimitC(
                p.getInt(PrefsBridge.SPLIT_THERMAL_LIMIT_C, THERMAL_LIMIT_C_DEFAULT)
            )
        _splitThermalGuardOff.value =
            p.getBoolean(PrefsBridge.SPLIT_THERMAL_GUARD_OFF, false)
        // 读的时候也归一一次：老版本写进去的（如 7927）/ 文件被手改过的值都可能越界或非整秒
        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))
        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()
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
            "angle_preview",             // 「实时角度预览」开关（10-04 整条链删除，见 docs/死代码清理_*）
            //   ⚠️ 用字面量、⛔ 别写回 `PrefsBridge.ANGLE_PREVIEW` —— 那个常量已随功能删除，
            //      写成常量编译就过不了；而且孤儿键的判据本来就是"没有代码再引用这个名字"。
            //   ⚠️ 漏了它的后果（2026-10-04 装机实测）：值一直躺在 prefs 里 ⇒ 每次全量快照都带着它
            //      ⇒ 引擎镜像恒为 16 个键（正确应是 15），将来排查时会误以为"还有代码在读它"。
            // —— 分屏「角度校准」整条链删除（10-06，用户口径"不给这么多自定义功能"），见
            //    docs/分屏增强_触发收紧与删除角度校准_* ——
            "split_calib",               // 用户标定出来的阈值串（实测值 `12.0|5.0|85.0|1500`）
            "split_calib_done",          // 「已校准」完成标记
            "split_calib_req",           // 「开始校准」请求键（值是一个时间戳）
            //   ⚠️ 同样是**装机实测**撞见的（10-06 05:16 软重启后读镜像）：三个键都还在
            //      ⇒ 快照恒 28 个键（正确应是 25），和 `angle_preview` 那次是同一个坑。
            //   ⚠️ `split_calib_req` 还**多一层**：它不在 [REQUEST_ONLY_KEYS] 里之后，升级安全网
            //      会把它当"设置"从镜像搬回 prefs —— 所以这里必须清，否则每装一次新版本就复活一次。
            //   ⛔ 别把旋转的标定键混进来：`calib_req` / `calib_step1_done` / `calib_step2_done`
            //      仍在用（那些是**方向**的标定，和折角无关）。
            //      （`calib_sign` / `calib_offset` 另在上面单列：它们已搬到 `Settings.System`。）
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

    // ================================================================ 升级安全网

    /** 一次性迁移的**完成标记**（写在 App 自己的 prefs 里；只判「跑过没有」） */
    private const val K_ADOPTED_MIRROR = "migrated_from_engine_mirror"

    /**
     * [adoptConfigFromMirrorIfNeeded] 里**不该搬**的键：它们是「请求」，不是「设置」。
     *
     * ⚠️ 搬了会有**可见副作用** —— 它们是「值变了一个新高就触发一次动作」的语义
     *   （标定 / 预览按钮 / 清除名单 / 熔断复位）：把上次那个旧时间戳原样搬回文件，
     *   值**没变**所以引擎不会动（无害），但「文件被清空一次、这些键同时被搬回」
     *   就会凭空触发一轮动作。用户的配置里**没有**「我上次点过标定」这一项，
     *   所以它们本来就不该算「要保留的设置」。
     *
     * ⚠️ 它同时也是那条**判据**的一部分（见 [adoptConfigFromMirrorIfNeeded] 的 ③）：
     *   算「本地缺了哪些镜像有的键」时要**跳过**这些键 —— 否则一个请求键就足以
     *   把本地判成"影子"，从而把整份镜像搬回来。
     */
    private val REQUEST_ONLY_KEYS = setOf(
        PrefsBridge.CALIB_REQ,
        PrefsBridge.HINT_TEST,
        PrefsBridge.BREAKER_RESET,
    )

    /**
     * ★★ **升级安全网**：把「引擎那份配置镜像」里存的配置搬回 App 自己的 prefs（一次性）。
     *
     * ============================ 它防的是什么 ============================
     * 迁移前，App 的 prefs 文件**不在**自己的私有目录里 —— LSPosed 的 nsp 会 hook
     * `getPreferencesDir()` 把它重定向到全局可读的 safe-zone
     * （`/data/misc/apexdata/<uuid>/prefs/<pkg>/`，之所以能被引擎读到就是因为这个）。
     * 而那个 hook **只对被注入的进程生效** ⇒ 迁移后（模块不再是 legacy
     * ⇒ 管理器重算作用域时会把「模块自己的包」从作用域里去掉）本应用进程可能不再被注入，
     * `getSharedPreferences` 从此读的是**私有目录里那个空文件**。
     *
     * ⇒ 后果不是「看不见设置」这么轻：界面会把默认值当成用户的配置**推给引擎**，
     *   而引擎会用它**覆盖掉 `Settings.System` 里那份正确的镜像** —— 也就是说
     *   **连回退到旧版本都救不回来**。这条安全网就是为了消掉这个后果。
     *
     * ============================ 判据（顺序有意义） ============================
     *   ① 标记已在 ⇒ 什么都不做（一次就够）；
     *   ② 读引擎镜像：没有 ⇒ 只记标记（全新安装）；解析失败 ⇒ **不记标记**，下次再试；
     *   ③ 镜像里的键本地**都有** ⇒ 本地是权威 ⇒ 只记标记，一个字不改；
     *      本地**缺**任何镜像有的键 ⇒ 本地是影子 ⇒ 整份采纳（跳过 [REQUEST_ONLY_KEYS]）。
     *
     * ⚠️ ③ 那条判据 2026-10-03 装机实测后收紧过一次，理由（真机上踩到的后果）写在函数体里 ——
     *   **改它之前先读那段注释**。
     *
     * ⚠️ 为什么来源选**引擎镜像**而不是「去找 safe-zone 那个旧文件」：
     *   那个路径是**不可枚举**的（`/data/misc/apexdata/<uuid>/`，uuid 随机、目录 700）
     *   ⇒ App 侧根本没有能力定位它。而镜像里放的正好就是**最近一次的全量快照**
     *   （见 [PrefsBridge.MIRROR]），信息量等价。
     * ⚠️ 镜像解析失败时**不记标记**（下次启动再试一次），但也**不抛** —— 读不到就当没有。
     *
     * ⚠️⚠️ **覆盖不到的一种升级路径（已知残留，刻意接受）**：从 **0.4.0（没有镜像那个版本）**
     *   升上来，且配置只存在于 safe-zone 那侧时 —— 引擎镜像里没有东西可搬，
     *   这条安全网无能为力。那条路径由 [migrateLegacyIfNeeded] 兜底一部分
     *   （它读私有目录里那份**冻结在 09-28 之前**的旧文件，能救回模式/策略/两个开关，
     *   **救不回** 09-28 之后才有的白名单与提示时长）。
     *   0.5.0 及以上升上来的都走镜像那条，不受影响。
     */
    private fun adoptConfigFromMirrorIfNeeded(p: SharedPreferences) {
        if (p.getBoolean(K_ADOPTED_MIRROR, false)) return
        val c = ctx?.contentResolver ?: return

        val raw = PrefsBridge.readString(c, PrefsBridge.MIRROR)
        if (raw.isNullOrEmpty()) {
            // 没有可搬的东西（全新安装，或引擎从没推过）⇒ 记一笔，别每次启动都去读 Settings。
            p.edit().putBoolean(K_ADOPTED_MIRROR, true).apply()
            Log.i(TAG, "升级迁移：没有引擎镜像可搬（全新安装？）—— 记上标记，以后不再检查")
            return
        }
        val snap = ConfigChannel.decode(raw)
        if (snap.isNullOrEmpty()) {
            Log.w(TAG, "升级迁移：引擎镜像解析失败（长度 ${raw.length}）→ 不搬，下次启动再试")
            return
        }

        // ★★ 判据（2026-10-03 装机实测之后**收紧过一次**）：**本地缺了镜像里有的键 ⇒ 本地是影子**。
        //   ⛔ 别退回第一版那句「文件里有任意一个配置键就不搬」—— 它在真机上直接放行了：
        //      legacy 时代本模块被框架自动加进自己的作用域，App 的 prefs 被 LSPosed 重定向到
        //      safe-zone，私有目录那份于是冻结在「重定向生效之前」的旧快照上 —— 它**有配置键**，
        //      但缺后来才新增的键（实测缺 app_whitelist_remove_inner 等 5 个）。安全网因此没出手，
        //      界面把那份陈旧配置推给引擎、**覆盖掉 Settings 里正确的镜像**：
        //      `app_whitelist_remove` 从 com.alibaba.wireless 变成空、`rotate_mode` 从 SEMI 变回
        //      SYSTEM、`_inner` 那个键整条消失（日志实证：「改动:app_whitelist_remove,rotate_mode
        //      删除:…,app_whitelist_remove_inner」）。用户的「移出名单」就是这么丢的。
        //   ⇒ 现在判据是**双向的**：镜像该有的键本地都有 ⇒ 本地才是权威，一个字都不改；
        //      少任何一个 ⇒ 本地不是最近那份 ⇒ 整份采纳（仍然跳过 [REQUEST_ONLY_KEYS]）。
        //   ⚠️ 为什么"缺键"足以判定：镜像只可能由**本 App 自己推上去的快照**产生
        //      ⇒ 镜像的键集恒 ⊆ 本地键集。缺键只可能是"这份文件不是最近那份"。
        val missing = snap.keys.filter { it !in REQUEST_ONLY_KEYS && !p.contains(it) }
        if (missing.isEmpty()) {
            // 正常升级：镜像该有的键文件里都有 ⇒ 只记一笔，不动任何数据。
            p.edit().putBoolean(K_ADOPTED_MIRROR, true).apply()
            return
        }

        val moved = mutableListOf<String>()
        runCatching {
            val e = p.edit()
            snap.forEach { (k, v) ->
                if (k in REQUEST_ONLY_KEYS) return@forEach
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    else -> return@forEach
                }
                moved += k
            }
            // ⚠️⚠️ 标记**必须**和这批值在**同一次** `edit()` 里提交：
            //   分两次写的话，「搬了一半 + 标记已置」会让下次启动直接跳过，永远补不回来。
            e.putBoolean(K_ADOPTED_MIRROR, true)
            e.apply()
        }.onFailure {
            Log.w(TAG, "升级迁移：把引擎镜像搬回 prefs 失败（已放弃，等 App 自己推一份新的）", it)
        }

        Log.i(
            TAG,
            "升级迁移：本地缺 ${missing.size} 个键（$missing）⇒ 判定为陈旧影子，" +
                "已整份采纳引擎镜像（共 ${moved.size} 个键）",
        )
    }

    // ================================================================ 初始化：引擎进程

    /**
     * **宿主进程**（SystemUI）初始化。必须在引擎起来之前调用。
     *
     * ★★ **调用顺序有硬要求**：必须排在 `module/ModulePrefs.attachTransport` **之后**
     *   （见 `EngineHost.bootOn` 的 ③ / ③.5）—— 这里读配置那一刻，镜像得已经在手上。
     *   反过来的话它读到的一定是空的，引擎就按默认值起跑，而**没有任何一环会事后补读**
     *   （本函数里那次 [applyFromModulePrefs] 只有这一次，之后要等一条新推送）。
     *
     * 与 [init] 的差别：不碰任何 SharedPreferences（引擎读不到 App 的私有文件），
     * 改为通过 [cn.dsr213.hyperplus.module.ModulePrefs] 拿配置 —— 那份配置由 App
     * **广播推过来**（并在引擎侧落一份 `Settings` 镜像）。订阅之后用户在界面改任何一项，
     * 这里毫秒级收到并灌进 StateFlow，引擎的 `collect` 随即跟着启停 / 换策略。
     *
     * ⚠️ 订阅是**无条件**的：迁移后通道只剩这一条（nsp 已废），不存在"通道不可用时
     *   就不订阅"这种分支 —— 那只会让"后来通道好了"也收不到东西。
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

            // ★★ 迁移后 `open()` 只**报告状态**（手上有没有一份配置），不再返回读取器
            //   ⇒ 「要不要订阅」这个分支**消失**了：通道就是唯一数据源，无条件订阅。
            cn.dsr213.hyperplus.module.ModulePrefs.subscribe { applyFromModulePrefs() }
            val ok = runCatching { cn.dsr213.hyperplus.module.ModulePrefs.open() }.getOrDefault(false)

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
     * 从**配置镜像**全量刷新内存流。
     *
     * ★ 一律全量读：App 推过来的本来就是一份**全量快照**（[ConfigChannel] 的设计），
     *   而配置项不到十个，全量读的代价可忽略 —— 也就不需要"哪个键变了"那种增量协议。
     *   ⚠️ 触发它的两条路：① `initHost` 启动时那一次；② 每次收到推送（订阅回调）。
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
        // ★★ R1（2026-10-05）—— **这一条才是功能性的那条**：引擎进程靠它决定
        //   "读不到人脸时要不要临时降成半自动"（`AdaptiveEngine.noteAdaptiveEnv`）。
        //   ⚠️ 与 [reloadFromPrefs] 那条**成对**，漏一条 = 界面改了、引擎还按旧值走。
        _r1Fallback.value = p.getBoolean(PrefsBridge.ADAPTIVE_FALLBACK, true)
        // ★ 「2 分屏展开方向」也走这一条引擎路径（与 [reloadFromPrefs] 那条**成对**）。
        //   它是引擎将来要读的配置，不是界面专有的可见性开关 —— 判据见
        //   [PrefsBridge.SPLIT_UNFOLD_DIR]（⛔ 别拿 "experimental_adaptive 不进这里" 当反例：
        //   那一个不进是因为**引擎对界面可见性毫无兴趣**，本键恰恰相反）。
        _splitUnfoldDir.value =
            enumOrNull<SplitUnfoldDirection>(p.getString(PrefsBridge.SPLIT_UNFOLD_DIR, null))
                ?: SplitUnfoldDirection.LEFT
        // ★★ 高温保护两项（2026-10-05）—— **这一条才是功能性的那条**：引擎进程靠它拿到
        //   用户设的温度上限与"要不要关掉保护"（`SplitTrigger` 从 `AppPrefs` 读这两个流）。
        //   ⚠️ 与 [reloadFromPrefs] 那条**成对**，漏一条 = 界面改了、引擎还按 47°C 走。
        //   ⚠️ 同样夹一次（理由见 [reloadFromPrefs] 那一处）。
        _splitThermalLimitC.value =
            clampThermalLimitC(
                p.getInt(PrefsBridge.SPLIT_THERMAL_LIMIT_C, THERMAL_LIMIT_C_DEFAULT)
            )
        _splitThermalGuardOff.value =
            p.getBoolean(PrefsBridge.SPLIT_THERMAL_GUARD_OFF, false)
        // 读的时候也归一一次：老版本写进去的（如 7927）/ 文件被手改过的值都可能越界或非整秒
        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))
        // ★ 预览请求同样要灌进内存流 —— 引擎侧 `collect` 它才会弹按钮。
        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()
        loadWhitelist { k -> p.getString(k, null) }

        _configOk.value = p.available
        _configDiag.value = p.diag
    }

    // ================================================================ 应用白名单

    /**
     * 读**两份名单、四个集合**（用统一的取值函数，App / 引擎两条读盘路径共用）。
     *
     * ★★ 2026-10-05 起这一个函数管**两个功能的名单**（旋转那份 + 分屏那份）——
     *   它们共用"一份名单 = add 集 + remove 集"这个形状，而且共用同一个取值函数
     *   ⇒ 合成一个装载点能让"两条读盘路径"自动覆盖两边，**不会出现只加了一条路**。
     *   ⚠️ 但**两份集合本身刻意不合并**（用户拍板，见 [SplitWhitelist] 类注释）。
     */
    private fun loadWhitelist(get: (String) -> String?) {
        _wlAdd.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_ADD))
        _wlDel.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_REMOVE))
        refreshWhitelist()
        // ★★ 每个应用单独指定的旋转方式（R2，2026-10-05）：它必须紧跟上面那两行 ——
        //   "跟随系统 / 跟随全局"两档的答案来自名单，另两档来自这个 map，
        //   两者合起来才是"这个应用该用哪一档"（见 [appModeOf]）。
        //   ⚠️ 放本函数里 ⇒ **两条读盘路径（App / 引擎）自动都覆盖**，不会只加了一条。
        _appModes.value = AppRotateMode.decode(get(PrefsBridge.APP_ROTATE_MODES))
        // ★ 分屏名单（另一份，别和上面那两行搞混）：编码同样是 [AppWhitelist.encode]。
        _swlAdd.value = AppWhitelist.decode(get(PrefsBridge.SPLIT_WHITELIST_ADD))
        _swlDel.value = AppWhitelist.decode(get(PrefsBridge.SPLIT_WHITELIST_REMOVE))
        refreshSplitWhitelist()
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
        // ★★ 2026-10-05（R2 四选一上线时补）：**必须连 [_appModes] 一起清**。
        //   理由是一条真实的漏洞 —— [setAppRotateMode] 选「自适应 / 半自动」时**会往
        //   `_wlDel` 写那个包名**（"把这个应用从名单里摘出来"），而点名记录本身在
        //   [_appModes] 里。只清 `_wlDel` 的话：名单确实回到出厂了，但
        //   [appModeOf] ① 仍然命中那条记录 ⇒ **档位一个字都没变**。
        //   用户看到的是"点了恢复默认，那个应用还是自适应"。
        val hadDel = _wlDel.value.isNotEmpty()
        val hadModes = _appModes.value.isNotEmpty()
        // ⚠️ 两个都空才算"没东西可恢复"——只看 `_wlDel` 会让"只点过档位"的用户
        //   点了按钮却什么都不发生（而界面上那个按钮是真的该起作用）。
        if (!hadDel && !hadModes) return false
        _wlDel.value = emptySet()
        refreshWhitelist()
        _appModes.value = emptyMap()
        persistAll()
        return true
    }

    // ------------------------------------------------ 每个应用单独适配（R2，2026-10-05）

    /**
     * 这个应用**在界面上应该显示成哪一档**（四选一里的选中项）。
     *
     * 判据（**两层的合成，顺序有意义**）：
     * ```
     * ① [_appModes] 里点名了          ⇒ 就是它（自适应 / 半自动）
     * ② 命中生效名单                   ⇒ 跟随系统
     * ③ 否则                           ⇒ 跟随全局
     * ```
     * ★ ① 优先于 ②：被点名成"自适应"的应用**同时**不会被名单命中 ——
     *   [setAppRotateMode] 写过 ① 的包一定不在名单里（它会把那个包从名单里摘掉）。
     *   两道都留着只为"文件被手改过"这种情形兜底，正常路径上不会打架。
     *
     * ⚠️ **界面用它画选中项、[modeFor] 用它算引擎档**：两处必须是同一个判据，
     *   否则会出现"界面显示跟随系统、引擎却在自适应"这种最难查的分歧。
     */
    fun appModeOf(pkg: String?): AppRotateMode {
        if (pkg.isNullOrEmpty()) return AppRotateMode.FOLLOW_GLOBAL
        _appModes.value[pkg]?.let { return it }
        return if (pkg in _whitelist.value) AppRotateMode.SYSTEM else AppRotateMode.FOLLOW_GLOBAL
    }

    /**
     * ★★★ **引擎侧唯一入口**：这个前台应用该按哪一档干活。
     *
     * 它替代了引擎里原来那 14 处 `AppPrefs.mode.value` 读点（R2 之前"全局一档管所有应用"）。
     *
     * 判据（顺序有意义）：
     * ```
     * ① 外屏 ⇒ 恒 SYSTEM      —— 外屏的旋转增强已整体删除（见 [modeOf]），一个字都不能写
     * ② 点名过 ⇒ 那一档
     * ③ 命中名单 ⇒ SYSTEM
     * ④ 否则 ⇒ 全局档（[_modeInner]）
     * ```
     * ★ ③ 与 [isWhitelisted] **必须是同一个集合**：前台门（"要不要停手"）判的就是它
     *   ⇒ 两处若用不同集合，会出现"门停了手、档却还在自适应"（引擎白跑一轮相机）。
     *   ⚠️ 这正是"跟随系统"这一档**不落 [PrefsBridge.APP_ROTATE_MODES]** 的原因。
     *
     * @param pkg 前台包名；`null` / 空 = 读不到 ⇒ 回落到**全局档**
     *   （判错方向的代价不对称：读不到就按用户设的全局档干活，比"凭空停手"安全）
     */
    fun modeFor(pkg: String?): RotateMode {
        if (_form.value != ScreenForm.INNER) return RotateMode.SYSTEM
        if (!pkg.isNullOrEmpty()) {
            _appModes.value[pkg]?.mode?.let { return it }
            if (pkg in _whitelist.value) return RotateMode.SYSTEM
        }
        return _modeInner.value
    }

    /**
     * 是否有**任何一档需要引擎介入**（引擎启停 / 形态切换 / 前台巡检的判据）。
     *
     * ============================ 为什么不能只看全局档 ============================
     * R2 之后可能出现「全局档 = 跟随系统，但某个应用被点名成自适应」——
     * 那时全局档是 [RotateMode.SYSTEM]（[RotateMode.engages] = false），
     * 只看它会**整个引擎都不启动**，用户在那个应用里的点名等于没设。
     *
     * ⚠️ 第二条（外屏恒 false）不是冗余：外屏的点名**必须不生效**（见 [modeFor] ①），
     *   否则引擎会在外屏打开并 `engageTakeover` 关掉系统的自动旋转 ——
     *   而外屏写 `user_rotation` 本来就无效（`ignoreOrientationRequest=false`）
     *   ⇒ 用户看到的是"屏幕彻底转不动了"。这是本工程踩过的那个坑，别再来一次。
     */
    val engagesAny: Boolean
        get() = when {
            _mode.value.engages -> true
            _form.value != ScreenForm.INNER -> false
            else -> _appModes.value.values.any { it.mode?.engages == true }
        }

    /**
     * 改某个应用的旋转方式（**界面唯一入口**，四选一）。
     *
     * ============================ 它同时动两处（刻意） ============================
     * | 选的档 | 名单那两层（add / remove） | [PrefsBridge.APP_ROTATE_MODES] |
     * |---|---|---|
     * | 跟随系统 | **进名单**（能被前台门识别为"停手"） | 清掉该包 |
     * | 另外三档 | **出名单** | 只有自适应 / 半自动留下记录 |
     *
     * ★ 为什么名单那两层必须跟着动：前台门的判据就是它（[isWhitelisted]）——
     *   只改 `_appModes` 而名单不动的话，一个"点名成自适应"的应用若恰好命中默认清单
     *   （游戏 / 长视频），前台门会**继续停手**，而 `modeFor` 却返回自适应
     *   ⇒ 两个判据打架，症状是"设了自适应但那个应用完全没反应"。
     *
     * ⚠️ 不做"默认清单项不必进名单"那种归约：名单是**现算**的（[AppWhitelist.resolve]），
     *   所以"把一个默认项改成跟随全局"必须真的往 `_wlDel` 写一个包名，
     *   否则下一次现算又会被默认值顶回来。理由与 [setAppWhitelisted] 逐字相同。
     *
     * @return 是否真的发生了变化（false = 用户点的状态本来就成立）
     */
    internal fun setAppRotateMode(pkg: String, m: AppRotateMode): Boolean {
        if (pkg.isBlank()) return false
        if (appModeOf(pkg) == m) return false

        val inList = m == AppRotateMode.SYSTEM
        val add = _wlAdd.value.toMutableSet().apply { remove(pkg); if (inList) add(pkg) }
        val del = _wlDel.value.toMutableSet().apply { remove(pkg); if (!inList) add(pkg) }
        val modes = _appModes.value.toMutableMap().apply {
            if (m.stored) put(pkg, m) else remove(pkg)
        }

        _wlAdd.value = add
        _wlDel.value = del
        refreshWhitelist()
        _appModes.value = modes
        persistAll()
        return true
    }

    // ================================================================ 分屏名单（2026-10-05）

    /**
     * 由 [_swlAdd] / [_swlDel] 重算生效分屏名单。**唯一的写入点** ——
     * 任何改到那两个流的地方都必须调它，否则引擎下次折叠用的还是旧集合。
     *
     * ★ 形状与 [refreshWhitelist] 逐字相同（同一个"只在真变了才赋值"的取舍）。
     * ⚠️ **但它算的是另一份集合**（[SplitWhitelist.resolve]），⛔ 别为了省事把两个
     *   refresh 合并成一个 —— 它们的输入输出都是两套。
     */
    private fun refreshSplitWhitelist() {
        val next = SplitWhitelist.resolve(_swlAdd.value, _swlDel.value)
        if (next != _splitWhitelist.value) _splitWhitelist.value = next
    }

    /**
     * 开关某个应用的**分屏豁免**。**分屏名单界面的唯一入口**。
     *
     * ★ 语义与 [setAppWhitelisted] **相反方向地"同名"**，读之前先记这条：
     *   - [setAppWhitelisted] 的 `on = true` ⇒ 该应用**不受旋转控制**（停手）；
     *   - 本函数的 `on = true` ⇒ 该应用**折一下也不分屏**（同样是一种"停手"）。
     *   ⇒ 两个开关在界面上都表现为"打开 = 别动它"，用户心智是一致的；
     *     但落的是**两份不同的集合**（用户 2026-10-05 拍板），⛔ 别互相写。
     *
     * ★ `on` 落到 `add`、`off` 落到 `del`，并把同一个包在另一份里的记录清掉
     *   —— 逐字同 [setAppWhitelisted]，理由不再重复。
     *
     * @return 是否真的发生了变化（false = 用户点的状态本来就成立）
     */
    internal fun setSplitWhitelisted(pkg: String, on: Boolean): Boolean {
        if (pkg.isBlank()) return false
        val before = pkg in _splitWhitelist.value
        val add = _swlAdd.value.toMutableSet().apply { remove(pkg); if (on) add(pkg) }
        val del = _swlDel.value.toMutableSet().apply { remove(pkg); if (!on) add(pkg) }
        if (before == (pkg in SplitWhitelist.resolve(add, del))) return false

        _swlAdd.value = add
        _swlDel.value = del
        refreshSplitWhitelist()
        persistAll()
        return true
    }

    /**
     * 引擎侧判据：这个前台包**折一下要不要加分屏**。
     *
     * @return true = 命中名单 ⇒ **不加**（放过用户正在全屏用的那个应用）。
     *
     * ★ 与 [isWhitelisted] 的**关键差异**：读不到包名（`null` / 空）时这里返回 **false**
     *   —— 也就是"**照常加分屏**"。这与 [ForegroundGate.Decision.UNKNOWN] 那条
     *   "读不到就不下结论"的取舍**刻意相反**，理由：
     *   - 旋转那边"读不到 ⇒ 停手"是对的，因为停手的代价只是少转一次屏；
     *   - 这边"读不到 ⇒ 停手"会让用户**折了没反应**，而且他没有任何办法自查
     *     （"是不是我名单配错了？" —— 其实是读不到）⇒ 判错方向的代价不对称，
     *     宁可多分一次屏（可撤销：用户按一下返回 / 退出分屏就回来了），
     *     也不要让他以为功能坏了。
     *   ⚠️ 这个取舍有代价：读不到前台包时，游戏也可能被分屏。接受 ——
     *     因为"读不到"在 SystemUI 进程里是**罕见**的（它有 REAL_GET_TASKS），
     *     而"折了没反应"是用户每天都会遇到的观感。
     */
    fun isSplitWhitelisted(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg in _splitWhitelist.value
    }

    /**
     * 清掉「用户手动移出」的记录，让默认游戏清单重新生效（界面上的"恢复默认"按钮）。
     *
     * ★ 只清 `del`、**不动 `add`** —— 逐字同 [resetWhitelistRemovals]。
     */
    fun resetSplitWhitelistRemovals(): Boolean {
        if (_swlDel.value.isEmpty()) return false
        _swlDel.value = emptySet()
        refreshSplitWhitelist()
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
     * 开关「自适应读不到环境时降级半自动」（R1，2026-10-05）。
     *
     * ★ 与 [setGateEnabled] 逐字同形（改内存流 + 落盘）—— 它同样要被引擎实时跟随，
     *   配置通道支持实时下发，所以不需要"重启引擎才生效"那套。
     */
    fun setR1Fallback(v: Boolean) {
        _r1Fallback.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.ADAPTIVE_FALLBACK, v)?.apply()
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
     * 开 / 关「提高分屏上限」（2026-10-06）。
     *
     * ============================ 它为什么是 suspend、还返回 Boolean ============================
     * 这个开关的值**必须让引擎（SystemUI）在进程起来的第一个毫秒读到**：
     * 实测截止线是 `MultipleSplitStageOrderOperator.<init>` 的 Δ404 ms，而走
     * `Settings.System` 那条路要 Δ4.6 s 才拿得到（日志表在 [PrefsBridge.PROP_MULTISPLIT]）。
     * ⇒ 值得落进 `persist.*` 属性，而**属性只有 root 能写**
     *   （[cn.dsr213.hyperplus.RootShell.putProp]）⇒ 这一步必然要等一个 su 进程。
     *
     * ★★ **写成功才改状态** —— 这是本方法唯一的不变式：
     *   属性写不进去 = 引擎永远读不到 = 用户开到天亮也不会生效。
     *   所以先借 root 写 + **读回校验**，只有真的写成了才更新内存流与落盘；
     *   失败就**一个字都不改**，由界面如实告诉他「没生效、去授权」。
     *   ⛔ 别为了「界面手感」先开起来再补写 —— 那正是「开着但没生效」这个坑的形状，
     *     也正是这次改造要根除的那个病。
     *
     * ⚠️ **关也要 root**：关闭时要把属性写成 `"0"`（而**不是**删掉它）——
     *   删掉的话引擎会回落去读镜像，而镜像那边还留着旧的「开」，等于关不掉。
     *   所以两个方向都要过 root；失败时状态同样保持原样。
     *
     * ⚠️ 这里**不去动 SystemUI**：重启由界面上的说明与设置页那个按钮交给用户做
     *   （本应用没有替用户重启系统界面的道理，那会打断他正在做的事）。
     *
     * @return 是否真的生效。**false 时本方法不改任何状态**。
     */
    suspend fun applyExperimentalMultiSplit(want: Boolean): Boolean {
        val ok = RootShell.putProp(PrefsBridge.PROP_MULTISPLIT, if (want) "1" else "0")
        if (!ok) return false
        _experimentalMultiSplit.value = want
        prefs?.edit()?.putBoolean(PrefsBridge.EXPERIMENTAL_MULTISPLIT, want)?.apply()
        return true
    }

    /**
     * 设「2 分屏展开方向」（用户 2026-10-04 点名）。
     *
     * ★ 与 [setMode] / [setStrategy] 同型：先更新内存流（界面**同帧**跟上），再落盘
     *   —— 落盘之后那条 [scheduleConfigPush] 会自动把新快照推给引擎，这里不需要额外动作。
     *
     * ⚠️ 早期返回那道 `if` **不是**可有可无的优化：`SharedPreferences` 只在**值真的变了**时
     *   才回调监听器，而这里若无条件写，重复点同一个档位会写一次盘却不触发推送 ——
     *   盘上内容与引擎镜像就会被一次真正的推送"意外对齐"，掩盖掉别处真正的漏推。
     *   保持"值没变就一个字节都不写"，与其它 setter 的纪律一致（见 [setMode] 的写法）。
     */
    fun setSplitUnfoldDir(v: SplitUnfoldDirection) {
        if (_splitUnfoldDir.value == v) return
        _splitUnfoldDir.value = v
        prefs?.edit()?.putString(PrefsBridge.SPLIT_UNFOLD_DIR, v.name)?.apply()
    }

    /**
     * 「到上限」提示的**已读游标**（毫秒时刻）—— 最后一次**已经给用户看过的**回报。
     *
     * ★★ 它必须落盘（理由见 [PrefsBridge.SPLIT_LIMIT_SEEN]）：用户折手机时人在分屏里、
     *   不在本 App 上 ⇒ 界面上那一瞬间的提示**根本送不出去**。落盘之后，
     *   下次打开 App 会把"错过的那一条"补上。
     * ⚠️ 纯读 + `runCatching`：它跑在界面冷启动路径上，读盘失败也只是退化成"可能重弹一次"。
     */
    fun splitLimitSeen(): Long =
        runCatching { prefs?.getLong(PrefsBridge.SPLIT_LIMIT_SEEN, 0L) ?: 0L }.getOrDefault(0L)

    /**
     * 记下"这条「到上限」回报已经给用户看过了"。
     * ⚠️ 只**前进**不后退（`maxOf`）：界面若因并发读到旧值，不该把游标往回拨 ——
     *   往回拨的后果是**同一条提示反复弹**，而"重复打扰"的代价是用户连真的提示也不看了。
     */
    fun markSplitLimitSeen(stampMs: Long) {
        if (stampMs <= splitLimitSeen()) return
        prefs?.edit()?.putLong(PrefsBridge.SPLIT_LIMIT_SEEN, stampMs)?.apply()
        Log.i(TAG, "已记下「到上限」提示已读：stamp=$stampMs")
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
     * 设**多分屏的高温保护阈值**（摄氏度）—— 界面输入框的唯一入口。
     *
     * ★ 与 [setHintMs] 同型：在这里**统一夹紧**（见 [clampThermalLimitC]），
     *   而不是只靠输入框的键盘过滤器 —— 用户可能粘一串字、也可能是从文件里改的。
     *
     * ⚠️ 与 [setSplitUnfoldDir] 同型：值没变就**一个字节都不写**（理由见那边的注释）——
     *   无条件写会"意外对齐"盘上内容与引擎镜像，掩盖掉别处真正的漏推。
     *
     * ⚠️ 改它**不影响** [splitThermalGuardOff]：那是另一个开关（语义见
     *   [PrefsBridge.SPLIT_THERMAL_GUARD_OFF]），⛔ 别在这里顺手把它关掉。
     */
    fun setSplitThermalLimitC(v: Int) {
        val clamped = clampThermalLimitC(v)
        if (_splitThermalLimitC.value == clamped) return
        _splitThermalLimitC.value = clamped
        prefs?.edit()?.putInt(PrefsBridge.SPLIT_THERMAL_LIMIT_C, clamped)?.apply()
        Log.i(TAG, "高温保护阈值已设为：$clamped°C")
    }

    /**
     * **恢复默认温度上限**（＝回到厂商出厂的 47°C）。
     *
     * ★ 为什么单独给它一个函数、而不是让界面调 `setSplitThermalLimitC(DEFAULT)`：
     *   语义不同。"恢复默认"是**用户明确表达的一个动作**（他不会去想默认值是多少），
     *   把它写成 `set…(常量)` 会让界面出现一个"看起来能点的按钮，
     *   但点之前得先知道 47 这个数"的循环。
     * ⚠️ 它**只动上限**，不碰那个"关掉保护"的开关 —— 用户的意图是"回到出厂的保护水平"，
     *   而"关掉保护"是他自己另外打开的另一件事（⛔ 别顺手替他关掉，那是加剧风险）。
     */
    fun resetSplitThermalLimitC() = setSplitThermalLimitC(THERMAL_LIMIT_C_DEFAULT)

    /**
     * 设**是否关掉多分屏的高温保护** —— 只有用户在**二次确认弹窗**里点了确认才该调。
     *
     * 🔴 这是本模块唯一一处"拆掉厂商安全保护"的开关，风险说明见
     *   [PrefsBridge.SPLIT_THERMAL_GUARD_OFF]。**界面的确认弹窗不是可选项** ——
     *   ⛔ 别为了"少点一下"直接调它、也别做"记住选择"的免确认。
     */
    fun setSplitThermalGuardOff(v: Boolean) {
        if (_splitThermalGuardOff.value == v) return
        _splitThermalGuardOff.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.SPLIT_THERMAL_GUARD_OFF, v)?.apply()
        Log.i(TAG, "多分屏高温保护已${if (v) "关闭（用户确认过）" else "恢复"}")
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
     * ★ 与 [requestCalibration] **同一个套路**：写一个新时间戳
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
     *     现在写就在 `ui.DirectionSection` 里，点一下一条命令，成败当场可见。
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

    // ================================================================ 熔断复位（App → 引擎）

    /**
     * ★★★ 请求「重新启用」旋转服务 —— **启动熔断的唯一出口**（2026-10-03）。
     *
     * 熔断（`phase=halted`）意味着引擎**已经不再自动启动**：那是为了打断
     * 「装完就崩、系统界面反复重启」的死循环（判据见 `EngineTuning.BOOT_BREAKER_THRESHOLD`）。
     *
     * ★ 两件事**同时**做，任意一条通了用户就能恢复（理由见 [PrefsBridge.BOOT_ATTEMPTS]）：
     *   ① 往自己的 prefs 写一个新时间戳（[PrefsBridge.BREAKER_RESET]）—— **零权限**。
     *      熔断态下引擎仍然只监听这一个键（那条链路极轻，见 `EngineHost.installBreakerResumeWatch`），
     *      收到就会当场清零并重试启动 ⇒ 用户**不用等重启**，几秒内就该看到状态变化。
     *   ② 借 root 直接把计数键写 0（[RootShell]）—— 兜住"配置通道也坏了"的双故障：
     *      那种情况下 ① 根本送不到引擎，没有 ② 就等于**永久锁死**。
     *      ⚠️ 这是**用户明确点了这个按钮**才发生的，符合 [RootShell] 那条
     *      「只在为用户点击服务时调用」的纪律；没授权 root 时它只是返回 false，不影响 ①。
     *
     * @return true = root 直写这一路成功了（仅用于日志/诊断；界面上不拿它判断成败 ——
     *   真正的判据是"状态串里的 phase 有没有离开 halted"）
     */
    suspend fun requestBreakerReset(): Boolean {
        val token = System.currentTimeMillis().toString()
        prefs?.edit()?.putString(PrefsBridge.BREAKER_RESET, token)?.apply()
        Log.i(TAG, "熔断复位：请求已落盘（$token）")
        val byRoot = RootShell.putSystemInt(PrefsBridge.BOOT_ATTEMPTS, 0)
        Log.i(TAG, "熔断复位：root 直写计数键 = $byRoot")
        return byRoot
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
     * ⚠️ 不能用 `getSharedPreferences` 去读它（那是 2026-09-28 那版写这条注释时的理由）：
     *   当时 LSPosed 的 nsp 把 `getPreferencesDir()` 重定向到 safe-zone，
     *   普通 API 再也指不到旧目录 ⇒ 只能按路径直接解析。
     *
     * ★★ 2026-10-03 迁移后**前提变了**：本模块不再声明 `xposedsharedprefs`，
     *   nsp 已关闭（模块整条迁到 libxposed API）⇒ `getSharedPreferences` 就落在
     *   **本函数的同一个文件**上（`dataDir/shared_prefs/$NAME.xml`）。
     *   ⇒ 现在它与 `p` 是**同一份数据**，这个函数退化成"换个写法再读一次"。
     *
     *   ⚠️ 那为什么不删掉它：**过渡期不能赌**。只要本应用进程当时仍被注入、nsp 仍在生效
     *     （作用域重算之前的那一小段），`p` 就还在 safe-zone，而**旧私有文件里那份
     *     pre-09-28 的配置是唯一还能救回用户模式的来源**。留着它是零成本的保险，
     *     删掉则在那个窗口里直接丢配置。真要清理，等确认过一轮装机再说。
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

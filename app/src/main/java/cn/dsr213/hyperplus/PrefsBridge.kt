package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log

/**
 * 键名约定 + 引擎侧写 `Settings.System` 的工具。
 *
 * ============================ 这个类现在管什么（2026-09-28 重构后） ============================
 * 改造前它是「App ⇄ SystemUI 的**双向配置总线**」，代价是 App 侧必须借 root 写非公开键。
 * 现在配置走「App 的 prefs → 广播快照 → 引擎镜像」（见 [ConfigChannel] /
 * [cn.dsr213.hyperplus.module.ModulePrefs]），本类只剩两件**都不需要 root** 的事：
 *
 * | 组 | 谁写 | 谁读 | 走哪 |
 * |---|---|---|---|
 * | **配置键** [MODE_INNER] [STRATEGY] [HANDOFF_ROTATE] [GATE] [CALIB_REQ] [HINT_TEST] [WHITELIST_ADD] [WHITELIST_REMOVE] [SPLIT_UNFOLD_DIR] [SPLIT_WHITELIST_ADD] [SPLIT_WHITELIST_REMOVE] | App | 引擎 | App 的 prefs ⇒ **广播快照**（本类只借用键名常量） |
 * | **引擎的账** [SIGN] [OFFSET] [RESTORE] [TAKEOVER] [CAMID] | 引擎 | 两边 | `Settings.System`（引擎是特权包，直写免 root） |
 * | **状态键** [STATE] [HEARTBEAT] [CALIB_RESULT] [SPLIT_BUS_LIMIT] | 引擎 | App | `Settings.System` |
 *
 * ★ 为什么不把"引擎的账"也塞进 prefs：**引擎写不了 App 的私有文件**（跨 uid，DAC + SELinux
 *   双重拦截）。而它写 Settings 是免 root 的（SystemUI 命中特权包豁免），
 *   所以凡是"引擎自己算出来、自己用"的数据，留在 Settings 反而更顺。
 *
 * ★ 判据来源（旧实现的实证，别再重走一遍）：`WRITE_SETTINGS` 与 `WRITE_SECURE_SETTINGS`
 *   都**不能**让普通应用写非公开键 —— 前者只对公开键有意义，后者在 AOSP 里只用来跳过
 *   AppOps 检查，后面那道 `enforceRestrictedSystemSettingsMutationForCallingPackage`
 *   是无条件执行的，只认 SYSTEM/SHELL/ROOT uid、公开键白名单、`PRIVATE_FLAG_PRIVILEGED`。
 *   ⇒ 所以"App 往非公开键写"这条路**在原理上就堵死**了，只能换通道，这就是本次改造的动机。
 *
 * ============================ 键名前缀约定 ============================
 * 落到 `Settings.System` 里的键统一加前缀 `hyperplus_`（见 [full]），
 * 而配置键在 App 的 prefs 文件里用**裸名**（不加前缀）。
 * 两种命名刻意保持一致，方便对照 —— 但**别搞混它们的存放位置**。
 */
internal object PrefsBridge {

    private const val TAG = "HyperPlusPrefs"

    private const val PREFIX = "hyperplus_"

    /** 本地键名 → 系统设置库里的键名 */
    fun full(localKey: String): String = PREFIX + localKey

    // ---------------------------------------------------------------- 配置键（App 的 prefs）

    /**
     * ★ **旧键（已不再写入）**：早先只有一份全局模式时用的键名。
     *
     * 现在模式**只有一份真身** [MODE_INNER]（外屏不做增强，理由见 [AppWhitelist] 类注释
     * "记过案"）。这个键只用来做**一次性迁移**：老用户升级上来时，把当初那份模式值灌给
     * 内屏这一份，免得配置凭空丢失。新代码**不要**再往它里面写 —— 写了也没人读。
     */
    const val MODE_LEGACY = "rotate_mode"

    /** 内屏模式：`rotate_mode_inner`（App 的 prefs，裸名）。**唯一在用的模式键** */
    const val MODE_INNER = "rotate_mode_inner"

    /**
     * 外屏模式：`rotate_mode_outer`。
     *
     * ⛔ **已退役**（2026-09-29，外屏旋转增强整个删掉）：不再读写。
     *   只在 [AppPrefs.migrateLegacyIfNeeded] 里当"老文件的指纹"用（"迁移过了吗"判据）。
     *   ⚠️ 2026-10-03 迁移前它还有**第二个**用途 —— `module/ModulePrefs` 拿它判"这文件像不像
     *     我们的"。引擎改走广播快照之后那条腿已随 nsp 一起删除，**别再照旧描述去找它**。
     *   ⛔ 别把它当"还有一个隐藏的配置层"再读回来。
     */
    const val MODE_OUTER = "rotate_mode_outer"

    /** 采集策略：POWER_SAVING / RESPONSIVE */
    const val STRATEGY = "capture_strategy"

    /**
     * ★ 前台门控：停手时是否把系统自动旋转**交还**（还原 `ACCELEROMETER_ROTATION=1`）。
     *
     * 用户 2026-09-28 明确要求"给个开关" —— 因为两种取向都合理：
     *   开（默认）：停手时把方向盘还给系统 ⇒ 全屏看视频/看图时想转还能转；
     *   关：停手但**保留当前方向**（`accelerometer_rotation` 维持 0），
     *      适合"我就想让屏幕锁在这个角度"的场景。
     */
    const val HANDOFF_ROTATE = "handoff_auto_rotate"

    /**
     * ★ 前台门控**总开关**。`true` = 门控生效（默认），`false` = 整个关掉。
     *
     * ★ 为什么要这个开关：用户报「该转的时候不转」，而门控是全工程**唯一会主动放弃干活**的
     *   机制 —— 判据一旦在某个应用上出错，症状就是"没反应"。它是留给用户的逃生阀。
     *
     * ★ 必须**运行中实时生效**：用户是在"发现不转"的当口去关它的，
     *   若要等重启引擎才生效，这个开关就等于没有。配置通道支持实时跟随，满足。
     */
    const val GATE = "gate_enabled"

    /**
     * ★★★ **自适应「读不到环境」时临时降级半自动**（2026-10-05 新增，R1）—— 用户配置。
     *
     * `true`（出厂默认）= 自适应连续几轮读不到人脸时，**临时**改按半自动干活（弹按钮），
     * 环境一恢复立刻切回自适应；`false` = 自适应永远按自适应跑，读不到就什么都不做。
     *
     * ★ 为什么默认 `true`：暗光 / 没把脸对着镜头时，自适应的表现是
     *   「**转手机完全没反应**」—— 用户分不清"功能坏了"和"环境不允许"。
     *   降级之后至少弹得出按钮，点一下就能转 ⇒ 这个方向的错法代价小得多。
     * ★ 降级只改**引擎这一次的行为**，一个字都不动用户配的那一档
     *   （见 `AdaptiveEngine.mode`；⛔ 别把它实现成"偷偷改用户的配置"）。
     * ★ 引擎侧要读它 ⇒ **两条读盘路径都要灌**（App prefs / 引擎镜像），
     *   漏一条就是"界面改了、引擎没跟上"。
     */
    const val ADAPTIVE_FALLBACK = "adaptive_fallback"

    /**
     * ★ **实验功能总闸**：`true` = 用户在「实验功能」页里打开了「自适应旋转」。
     *
     * 用户 2026-10-03 点名（原话：「在首页新增一个"实验功能"置底，里面添加"自适应旋转"开关，
     * 打开开关之后，旋转增强里面才显示自适应旋转的选项」）。
     *
     * ## 它 gates 什么（**只 gates 界面可见性，不 gates 功能本身**）
     * `false`（默认）时，「旋转增强 → 模式」的三档单选里**不列出** `ADAPTIVE`，
     * 用户只剩「跟随系统 / 半自动」两档。
     *
     * ⚠️ **它不参与引擎的任何判断** —— 引擎侧读到的仍是 `rotate_mode_inner` 那一份真值，
     *   所以"开关关掉"**不会**把已经在用自适应的用户从自适应里踢出来
     *   （踢出来 = 替用户改配置，见 [setExperimentalAdaptive] 的注释）。
     *   换句话说：**关开关只让新用户看不见它，不动老用户的现有选择。**
     *
     * ⚠️ 与 [GATE] 的区别：GATE 是**行为**开关（引擎实时跟随）；
     *   本键是**界面**开关（只有 App 进程读它）⇒ 引擎侧**刻意不读**，
     *   它出现在这里是因为 App 的 prefs 文件是唯一的配置落盘处，不是因为它跨进程。
     */
    const val EXPERIMENTAL_ADAPTIVE = "experimental_adaptive"
    /**
     * ★★ 「**提高分屏上限**」总闸（2026-10-06，用户点名）。
     *
     * 默认 **false**（关）：折叠分屏的上限**保持系统原样**（本机 = 6 个）。
     * 打开后上限抬到 **8**，但 ⚠️ **要重启一次系统界面才生效**。
     *
     * ★ 为什么必须「重启才生效」（不是偷懒）：`MultipleSplitOrganizer.MAX_STAGES` 是
     *   `<clinit>` 里**一次算出来**的静态字段，`allStages` 列表也是那一刻按它建好的
     *   ⇒ 运行时热改不了（详见 `module/SplitStageLimit` 的类注释）。
     *
     * ⚠️ 与 [EXPERIMENTAL_ADAPTIVE] 的语义**不同**（别抄错）：
     *   那个只管「界面上列不列出来」，不参与引擎判断；
     *   这一个**真的会改系统行为**（分屏格数）⇒ 它关着的时候
     *   `SplitStageLimit` **一个字节都不许改**（判据见 `SplitStageGate.targetStages`）。
     */
    const val EXPERIMENTAL_MULTISPLIT = "experimental_multisplit"

    /**
     * ★★★ **「提高分屏上限」的 `persist.*` 属性镜像**（2026-10-06 新增）。
     *
     * 完整名 = `persist.sys.hyperplus.multisplit`。取值 `"1"` / `"0"`；**键不存在** = 没写过。
     *
     * ============================ 它为什么必须存在 ============================
     * 在这条属性之前，引擎（SystemUI 进程）想读 [EXPERIMENTAL_MULTISPLIT] 只有
     * [MIRROR]（`Settings.System`）一条路。真机实测（2026-10-06 08:01:49，逐条日志）：
     *
     * | 时刻 | 事实 |
     * |---|---|
     * | Δ380 ms | `MultipleSplitOrganizer.<clinit>` 拦到 `resolveMaxStages` ← **截止线** |
     * | Δ404 ms | `MultipleSplitStageOrderOperator.<init>` 跑完，`allStages` 已按 6 建好 |
     * | Δ25 ~ Δ4737 ms | 配置镜像**读了 51 次，全是空** |
     * | Δ4987 ms | 才第一次读到开关 |
     *
     * ⇒ **截止线在 Δ404 ms，值 Δ4.6 s 才到，晚了 4.2 秒**。根因不是「数据没写进去」
     *   （库里那份镜像一直在：760 字符 / 26 个键），而是 `Settings.System` 要走
     *   ContentProvider（Binder），SystemUI 进程刚起来的头几秒就是拿不到值。
     *
     * ⇒ `persist.*` 属性活在**进程内 mmap 的属性区**：不经过 Binder、不经过
     *   ContentProvider、不等用户解锁 ⇒ **进程起来的第一个毫秒就能读**。
     *   相对 Δ380 ms 的截止线有足够余量。
     *
     * ============================ 四条纪律 ============================
     * ① 由 **App 借 root** 写（[cn.dsr213.hyperplus.RootShell.putProp]）——
     *    `persist.` 属性只有 root / init 能设。⛔ 别指望 App 侧零权限能写它。
     * ② 引擎侧**只读不写**（SystemUI 的 SELinux 域不保证能写 `persist.sys.*`）。
     * ③ ⛔ **绝不与 ROM 自己的 `persist.sys.multiple.split.max_stages` 混用** ——
     *    那是小米的键，改它会被 ROM 自己的档位逻辑反向利用（实测把它设成 8 会连崩三次，
     *    见 `docs/分屏增强_上限限制点位_字节码结论_2026-10-06.md`）。
     *    本键是我们**自己的命名空间**，只表达「用户开关是开是关」这一件事。
     * ④ 它**不是** [MIRROR] 的替代品，而是**优先级更高的第一条通道**：属性没写过时，
     *    引擎仍回落去读 [MIRROR]（判据见
     *    [cn.dsr213.hyperplus.SplitStageGate.targetStages] 的优先级表）。
     *
     * ⚠️ `persist.` 属性**跨整机重启存活**（这正是我们要的），代价是
     *   **App 卸载后会残留** ⇒ 极端情形下用户可
     *   `setprop persist.sys.hyperplus.multisplit 0` 手工清掉（本工程的用户必是 root 用户）。
     */
    const val PROP_MULTISPLIT = "persist.sys.hyperplus.multisplit"

    /**
     * ★★ 「**2 分屏展开方向**」—— 用户 2026-10-04 点名的选项（原话：
     *   「直接在 app 里面给选项「2 分屏展开方向：朝左 / 朝右」」）。
     *
     * ============================ 它定义的是什么 ============================
     * 轻折一下追加一格分屏时，**当前应用留在哪一侧**（另一侧才是新格）。
     *
     * ★ 这条语义**不是我们发明的**：原生「三指左滑 / 三指右滑」本来就是两个**独立的手势类**
     *   （`MiuiThreeFingerHorizontalGesture$…LTRGesture` / `…RTLGesture`，动作串
     *   `split_ltr` / `split_rtl`，✅ 反汇编级实证见
     *   `docs/分屏增强_实现方案_2026-10-04.md` §1.10）⇒ **方向是系统的一等输入量**，
     *   原生只是把它绑在"往哪边滑"上，我们把同一个量提到设置里来。
     *
     * ⛔ **别把它与屏幕旋转方向混起来** —— 那个是 [KEY_USER_ROTATION_PREFIX]
     *   （`user_rotation_*`，管屏幕转多少度）。两者只是名字里都带"方向"，语义毫无关系。
     *
     * ⚠️ 取值用**枚举名**（`LEFT` / `RIGHT`），与 [MODE_INNER] / [STRATEGY] 同一条落盘约定。
     * ⚠️ 引擎侧**要读它**（分屏触发时要用；现在只是先把界面摆上）⇒ 必须同时进
     *   `AppPrefs.reloadFromPrefs`（App 进程）与 `AppPrefs.applyFromModulePrefs`（引擎进程）
     *   两条读盘路径。漏一条的症状是「界面改了、引擎那边没跟上」，而且**不报错**。
     */
    const val SPLIT_UNFOLD_DIR = "split_unfold_dir"



    /**
     * ★★ **多分屏的高温保护阈值**（摄氏度，**整数**；2026-10-05 新增）—— 用户配置。
     *
     * 用户原话：「在app里添加修改温度上限的输入框，**禁止超过60度**，并用**红字**标注警告
     * "该操作存在风险，请谨慎修改"，下面要配一个**恢复默认**的按钮」。
     *
     * ============================ 它改的是什么 ============================
     * 系统有一条**高温自动收掉多分屏**的保护（`MultiTaskingTemperatureObserver`）：
     * 板温（`/sys/class/thermal/thermal_message/board_sensor_temp`）爬到 **47°C** 就
     * 弹一个引导对话框，并把"退出多分屏"排进 10 秒后的队列；回落到 **45°C** 才复位
     * （回滞 2°C，见 `MultiTaskingTemperatureObserver.HIGH_TEMPERATURE / NORMAL_TEMPERATURE`）。
     *
     * ★★ 更关键的是：**它同时是对"加一格"的一道闸** —— `dockMultipleTasks()` /
     *   `dockSoScTasks()` 的第一件事就是 `if (isHighTemperature()) { toast; return; }`
     *   ⇒ 板温超过这个阈值时，"轻折一下多一格"**当场被拒**（弹
     *   `multi_tasking_temperature_block_toast`）。这就是用户想调它的动机。
     *
     * ⇒ 本键让用户把这个 **47** 改掉。引擎侧看到的写法是**改写
     *   `MultiTaskingTemperatureObserver.mIsHighTemperature` 这个字段**
     *   （而不是改那个 `static final` 常量 —— 它的值已被内联进调用点，改字段无效）。
     *
     * ============================ 取值边界（⛔ 必须在三处一致） ============================
     * - 默认 **[THERMAL_LIMIT_C_DEFAULT] = 47**（**照抄系统的出厂值**，不是我们拍的）。
     * - 下限 **[THERMAL_LIMIT_C_MIN] = 40**：必须**明显高于日常板温**（本机实测 36°C）
     *   —— 阈值一旦落到常温之下，高温判定就**永远不触发**，
     *   那等于一条**不经过二次确认弹窗**的关保护路径。
     *   ⚠️ 这个数被单测改过一次（原为 30，低于实测板温 36）：
     *   见 `AppPrefs.THERMAL_LIMIT_C_MIN` 的 KDoc。
     * - 上限 **[THERMAL_LIMIT_C_MAX] = 60** —— **用户点名的硬顶**（原话「禁止超过60度」）。
     *   ★ 这道顶不只是"用户说了算"：60°C 已经远超手机的舒适区（本机日常实测 36°C，
     *   出厂阈值 47°C），再往上调**大概率在触发前系统自己就降频/关屏了**，
     *   那个输入框会变成一个"看起来能调、其实调了没用"的摆设。
     *
     * ⚠️ **引擎侧要读它** ⇒ 必须同时进 `AppPrefs.reloadFromPrefs`（App 进程）与
     *   `AppPrefs.applyFromModulePrefs`（引擎进程）两条读盘路径。漏一条的症状是
     *   「界面改了、引擎那边没跟上」，而且**不报错**。
     */
    const val SPLIT_THERMAL_LIMIT_C = "split_thermal_limit_c"

    /**
     * ★★ **关掉多分屏的高温保护**（布尔；2026-10-05 新增）—— 用户配置，**默认 false**。
     *
     * 用户原话：「再加一个**关闭过热保护的开关**，加一个**二次确认弹窗**，弹窗里面写清楚
     * **关闭过热保护可能造成不可逆的风险**」。
     *
     * ============================ 它和上一条的区别 ============================
     * [SPLIT_THERMAL_LIMIT_C] 是"把闸门抬到多高"，本键是"**把闸门整个拆掉**"。
     * 两者是**独立**的两个手段（抬到 60 仍然会拦；关掉则不再看温度）——
     * ⛔ 别把它们合并成一个语义（例如"关掉时把阈值置 0"）：那样界面上两个控件会互相打架，
     *   而且用户关掉又打开时**找不回**他自己调过的那个数。
     *
     * ============================ 它做了什么 ============================
     * 引擎侧让 `mIsHighTemperature` **恒为 `false`** ⇒ `onHighTemperature()` 那条链
     * （引导对话框 + 10 秒后 `exitMultipleSplit`）与"加一格"路上那道高温闸**同时失效**。
     *
     * 🔴 **这是本模块里唯一一处"拆掉厂商安全保护"的开关** ——
     *   不可逆风险（电池/主板长期高温）真实存在，所以界面必须有二次确认，
     *   且弹窗正文要把风险写清楚（见 `split_thermal_off_dlg_msg` 三套资源）。
     *   ⛔ 别在任何地方把它默认打开、也别做"记住上次选择"的免确认。
     *
     * ⚠️ 与上一条同源：**引擎侧要读它** ⇒ 两条读盘路径都要灌，见 [SPLIT_THERMAL_LIMIT_C]。
     */
    const val SPLIT_THERMAL_GUARD_OFF = "split_thermal_guard_off"

    /**
     * ★ 半自动按钮的**等待时长**（毫秒）。用户 2026-09-28 点名要的可调项：
     * 「添加自定义的时间滑条，让用户自行决定旋转按钮的消失时间，最少 1s，最多 60s」。
     *
     * 取值 1000 ~ 60000，默认 3000（沿用原来的 3 秒 —— 那是用户最初的需求值，
     * 只把它从"写死的常量"变成"可调的默认值"）。
     */
    const val HINT_MS = "semi_hint_ms"

    /**
     * ★ **半自动按钮的预览请求**（App 写、引擎读）。
     *
     * ============================ 为什么需要它 ============================
     * 半自动的旋转按钮只在"传感器判定设备姿态 ≠ 屏幕方向"时弹出，而**传感器没法用 adb 注入**
     *   （`SensorService` 的数据注入是 eng build 才有的开关）⇒ 装机后想看一眼按钮长什么样，
     *   只能靠人把手机转一下。调一次外观要转一次手机，这不可接受。
     *
     * ⇒ 约定：值形如 `"<时间戳>|<目标方向>"`，App 点一下写一个新值，引擎读到"值变了"就
     *   **直接弹一次按钮**（存活时长照 [HINT_MS]）。与 [CALIB_REQ]
     *   同一个套路：靠"值变了"驱动，不复位（引擎写不了 App 的私有文件）。
     *
     * ⚠️ 它**只影响外观验证**：弹出来的按钮点下去照样走真实路径（写方向 + 读回），
     *   所以别拿它当"绕过传感器"的正规入口 —— 它只是让人能看见按钮。
     */
    const val HINT_TEST = "hint_test"

    /**
     * ★ 应用白名单：**用户手动开启**的包（`\n` 分隔的单个字符串，见 [AppWhitelist.encode]）。
     *
     * 用户 2026-09-28 拍板：「把（原来的）判断删了、改成应用白名单，白名单应用不受 app 控制」。
     *
     * ⚠️ **2026-09-29 起名单只有一份**（那天上午做过内外屏解耦、当天下午就收回，
     *   因为外屏增强被整个删掉了 —— 理由见 [AppWhitelist] 类注释里"记过案"那段）。
     *   键名沿用当时那个"全局层"的键：用户以前勾的一格都对应"两块屏都豁免"，
     *   落在这一份里**零迁移**。
     *   ⇒ 旧文件里可能还留着 `app_whitelist_add_inner` / `app_whitelist_remove_inner` /
     *     `..._outer` 三个键：**已不再读写**，留着无害，但别把它们当成"还有一个隐藏层"。
     */
    const val WHITELIST_ADD = "app_whitelist_add"

    /**
     * ★ 应用白名单：**用户手动关闭**的包。
     *
     * ⚠️ 它必须是一份**独立存下来的减集**，不能只靠"把值从 [WHITELIST_ADD] 里删掉"：
     *   默认清单里的应用是"装了就该默认开"的，如果只删加集，
     *   下一次 [AppWhitelist.resolve] 又会把它按默认值顶回来 ——
     *   用户会觉得"我明明关过，怎么又开了"。
     */
    const val WHITELIST_REMOVE = "app_whitelist_remove"

    /**
     * ★★★ **「每个应用单独适配」的旋转方式**（2026-10-05 新增）—— 用户配置。
     *
     * 用户原话（2026-10-05）：「应用列表「**每个应用单独适配**」（跟随全局 / 跟随系统 /
     * 自适应 / 半自动）」。
     *
     * 编码：`"<包名>=<枚举名>\n…"`（与 [AppWhitelist.encode] 同样用**单个字符串**，
     * 理由逐字相同）。枚举见 `AppPrefs.AppRotateMode`。
     *
     * ============================ 它为什么只装 ADAPTIVE / SEMI ============================
     * [[FOLLOW_GLOBAL]]（跟随全局）与 [[SYSTEM]]（跟随系统）**不进这个键** —— 它们由
     * 「名单」那两份集合表达（[WHITELIST_ADD] / [WHITELIST_REMOVE]），
     * 判据是"命中名单 ⇒ 跟随系统、否则 ⇒ 跟随全局"（见 `AppPrefs.appModeOf`）。
     *
     * ★ 为什么这样做：这两档**本来就是这个意思** —— 改造前那个布尔开关的"开"就是
     *   「跟随系统」（名单命中）、"关"就是「跟随全局」。借道既有名单 ⇒ **老用户的勾零迁移**，
     *   而且"谁不受控制"这件事仍然只有一个真值来源（[AppWhitelist.resolve]）。
     *   ⛔ 别为了"四个档位整齐"把这两档也写进本键 —— 那会立刻造出两份互相打架的名单。
     *
     * ⚠️ 引擎侧**要读它**（前台应用取档）⇒ 必须同时进 `AppPrefs.reloadFromPrefs`（App 进程）
     *   与 `AppPrefs.applyFromModulePrefs`（引擎进程）两条读盘路径。漏一条的症状是
     *   「界面改了、引擎还按全局档走」，而且**不报错**。
     */
    const val APP_ROTATE_MODES = "app_rotate_modes"

    // ------------------------------------------------ 分屏增强的应用名单（2026-10-05 新增）

    /**
     * ★★★ 分屏名单：**用户手动加进来**的包（`\n` 分隔的单个字符串，编码同 [AppWhitelist.encode]）。
     *
     * ============================ 它与 [WHITELIST_ADD] 是**两份不同的东西** ============================
     * 用户 2026-10-05 拍板：「分屏这份名单和旋转那份**各自独立一份**」。
     *
     * | | 旋转名单（[WHITELIST_ADD]） | 分屏名单（本条） |
     * |---|---|---|
     * | 命中含义 | 本应用**不干涉那个应用的转屏** | 那个应用**折一下也不分屏** |
     * | 默认清单 | 常见游戏 + 长视频 | **常见游戏**（见 `SplitWhitelist.DEFAULT_GAMES`） |
     *
     * ⚠️ **刻意不共用存储**：两者语义毫无关系 —— 用户完全可能"不想让某个游戏被切屏"
     *   但"愿意让它自己转屏"。共用一份会让改一边、另一边莫名变化。
     * ⛔ 别为了"少维护一份"把它们合并（用户已明确否决）。
     *
     * ★ 键名刻意**不带** `split_` 前缀之外的花样：沿用 `app_whitelist_*` 那套的
     *   "`add` / `remove` 两份独立集合"范式（理由与 [WHITELIST_REMOVE] 注释里逐字相同）。
     */
    const val SPLIT_WHITELIST_ADD = "split_whitelist_add"

    /**
     * ★★★ 分屏名单：**用户手动移出**的包（独立减集，理由见 [WHITELIST_REMOVE]）。
     *
     * ⚠️ 必须是独立一份 —— 默认清单里的游戏是"装了就该默认不触发分屏"的，
     *   只删加集的话，下一次现算又被默认值顶回来。
     */
    const val SPLIT_WHITELIST_REMOVE = "split_whitelist_remove"

    /**
     * ★ 标定请求：App 写、引擎读，值是 `"<时间戳>|<步骤>"`（步骤见 `AppPrefs.CALIB_STEP_*`）。
     *
     * ⚠️ **刻意不复位**（旧实现是写 0 复位后反复触发）：配置通道的变更通知**不携带键名**，
     *   而引擎**没有权限写 App 的文件**，根本复位不了。于是改成"每次请求都是一个新值"，
     *   引擎靠"值变了"驱动，反而更干净 —— 不需要额外的复位握手。
     */
    const val CALIB_REQ = "calib_req"

    /**
     * ★★ **熔断复位请求**（App 写、引擎读；2026-10-03 新增）。
     *
     * 值与 [CALIB_REQ] **同一套路**：`"<时间戳>"`，靠"值变了"驱动，**不复位**
     * （引擎写不了 App 的私有文件）。
     *
     * ★ 引擎**只在熔断态**（`phase=halted`）读它 —— 那时它已经不是一个完整引擎，
     *   只剩这一条最轻的链路（读一个小文件 + 2 秒内容比对），见
     *   `EngineHost.installBreakerResumeWatch`。
     *
     * ★ 它为什么必须有：`hyperplus_boot_attempts`（[BOOT_ATTEMPTS]）住在 `Settings.System`，
     *   **普通应用写不了**（判据见本类开头）⇒ App 没法自己复位熔断。
     *   走这条路 App 零权限、零 root，却能让"最需要一键恢复"的场景真的可恢复。
     */
    const val BREAKER_RESET = "breaker_reset"

    // ---------------------------------------------------------------- 引擎的账（Settings）

    /** 标定：roll 符号翻转（+1 / -1）。由**引擎**写（用户点按钮后的采样、运行中自动修正） */
    const val SIGN = "calib_sign"

    /** 标定：相位偏移（度），使「人脸正立」对应 0°。同上，引擎写 */
    const val OFFSET = "calib_offset"

    /** 交还系统自动旋转时的目标值（恒 1，理由见 [AppPrefs.RESTORE_AUTO_ROTATE]） */
    const val RESTORE = "restore_auto_rotate"

    /** 是否处于「我们正接管」状态 —— 持久化，进程被杀后下次启动能还原 */
    const val TAKEOVER = "takeover_active"

    /**
     * ★ 引擎自己的账：**上次真正采到过帧的前摄 id**（"1" / "5" …）。
     *
     * 它不是用户配置，没有"实时跟随"的需求 —— 只有引擎启动时读一次，
     * 用来决定先从哪个前摄开起。由引擎写、引擎读，中途被 App 覆盖反而可能把"可用 id"抹掉。
     */
    const val CAMID = "camera_id"

    /**
     * ★★★ **启动熔断计数**（2026-10-03 新增；引擎写、引擎读，App 只从状态串里读来显示）。
     *
     * 语义：引擎每次**真要动手启动**之前 +1；健康运行满 `BOOT_HEALTHY_WINDOW_MS` 就清零；
     * 启动前读到 ≥ `BOOT_BREAKER_THRESHOLD` ⇒ **本次不再自动启动**，只上报 `phase=halted`。
     *
     * ★★ 它为什么**必须落盘**（而不是像 `EngineHost.dead` 那样活在内存里）：
     *   `dead` 是"当前这一次 SystemUI 进程内有效"的熔断 —— 而故障若是**确定性**的，
     *   进程一重启它就复位，同一个故障被一遍遍重放，用户看到的就是
     *   「装完就崩、系统界面反复重启、进不了桌面」（2026-10-03 两名用户反馈）。
     *   落盘之后计数跨进程存活 ⇒ **重启两次就停**，用户能进桌面去关模块。
     *
     * ⚠️ 复位出口有**两个**，都挂在 App 的「重新启用」按钮上：
     *   ① App 往自己的 prefs 写 [BREAKER_RESET]（零 root；熔断态下引擎仍在监听这一个键）——
     *      通了就能**当场**重试启动；
     *   ② App 借 root 直接把这个键写 0（[cn.dsr213.hyperplus.RootShell]）——
     *      兜住"配置通道也坏了"那种双故障（那时 ① 送不到）。
     *   ⛔ 别删其中任何一个：只留 ① 的话，通道坏掉时熔断会变成**永久锁死**，
     *     用户除了 adb（`settings put system hyperplus_boot_attempts 0`）没有出路。
     */
    val BOOT_ATTEMPTS = PREFIX + "boot_attempts"

    /**
     * ★★★ **配置镜像**（2026-10-03 新增；引擎写、引擎读）。
     *
     * 里放的是 App 最近一次推过来的**全量配置快照**（[ConfigChannel.encode] 产出的字符串）。
     *
     * ============================ 它为什么必须存在 ============================
     * 配置下行现在走广播（[ConfigChannel] 的类注释有全景）。广播是**单向、且需要对方在跑**
     * 的通道 ⇒ 只靠它的话，SystemUI 一重启（本模块自己就在制造这种重启，见 [BOOT_ATTEMPTS]）
     * 拿到的就是"什么都没有"，落到用户眼里是「改过的设置全被打回默认」，而且**极难复现定位**。
     *
     * ⇒ 引擎每收到一次推送就顺手往 `Settings.System` 落一份；启动时先把它读回来。
     *   于是「引擎重启」与「App 从没运行过」这两种情况都不再需要 App 配合。
     *
     * ⚠️ 它**不是**用户配置的第二个真身：真身永远是 App 的私有 prefs
     *   （用户改配置只可能从 App 界面进）。这里只是一份**引擎侧的读缓存**，
     *   可以随时被下一次推送整体覆盖 —— 所以⛔ 别在这里做"合并"或"部分更新"。
     */
    val MIRROR = PREFIX + "config_mirror"

    /**
     * ★ **分屏触发器的档位**（0 关 / 1 只观察 / 2 真动作）。
     *
     * ============================ 它为什么在 `Settings.Global`，不在这份清单的其它两栏里 ============================
     * 它既不是"用户在 App 里编辑的配置"（**分屏还没有界面开关**，现在是 adb 设的），
     * 也不是引擎算出来的账 —— 它是一条**由人直接设进系统**的开关。
     *
     * ★ 真正的定义在 `module/SplitTrigger.KEY`，而它**直接引用这一条**（单一真值）。
     *   留在这个类里是因为本类是"键名唯一真值处"（见类注释）。
     * ⚠️ App 侧**读**它（主页要汇总"整个模块"启用了哪些增强），读 Global 零权限。
     */
    const val SPLIT_TRIGGER = PREFIX + "split_trigger"

    /**
     * ★★★ **HyperOS「每块屏各自的方向槽位」键前缀**（不是我们的键，是 WMS 的）。
     *
     * 完整键 = 前缀 + [ScreenForm.storageKey] ⇒ `user_rotation_inner` / `user_rotation_outer`。
     * ⚠️ **AOSP 标准里只有全局的 `user_rotation`**，这两个是 HyperOS 私有扩展 ——
     *   非 HyperOS 机型上**根本不存在**（[AppPrefs.readSlot] 会返回 null，界面据此
     *   说"这台设备的系统不支持方向设置"，**不会**凭空造一个没人读的垃圾键）。
     *
     * ★ 它为什么重要：**框架展开 / 折叠时灌进屏幕的就是它**（实测与 WMS 的
     *   `mUserRotationInner` / `mUserRotationOuter` 一一对应）。槽位对 ⇒ 展开即正立、
     *   我们一行都不用写；槽位错 ⇒ 用户看到的那个"自己转一下"。
     *
     * ★★ 2026-10-03 起 **写它的人变了**：以前是引擎（SystemUI 特权包）代写，
     *   现在改由 **App 借 root 直写**（[cn.dsr213.hyperplus.RootShell]；用户原话
     *   「**能装上模块的手机一定有 Root，可以通过获取 root 来修改**」）。
     *   原因：引擎代写要过配置通道，真机上出现过"请求落了盘、槽位却没变"。
     *   ⚠️ **读**它不需要任何权限（普通应用也能读），App 侧据此显示真实当前值；
     *     写要 root，**且只在用户点「默认方向」时写** —— ⛔ 别在任何自动路径里写它。
     *
     * ★ 单一真值：引擎侧那个同名常量直接引用这一条，别在两处各写一份字符串。
     */
    const val KEY_USER_ROTATION_PREFIX = "user_rotation_"

    // ---------------------------------------------------------------- 状态键（完整名，引擎 → App）

    /** 宿主 → App：引擎状态摘要（格式见 `EngineHost.summary`） */
    val STATE = PREFIX + "bus_engine_state"

    /** 宿主 → App：心跳（elapsedRealtime/1000，仅用于显示「最后活跃」） */
    val HEARTBEAT = PREFIX + "bus_engine_heartbeat"

    /** 宿主 → App：标定结果 `"<token>|<step>|<status>"`（status ∈ ok/noface/badangle/starting/stopped） */
    val CALIB_RESULT = PREFIX + "bus_calib_result"



    /**
     * 宿主 → App：**「多分屏已经到上限了」**，格式 `"<时刻ms>|<当前格数>|<上限>"`（A4，2026-10-05）。
     *
     * ★★ 引擎写、界面读，而且**没有配对 token** —— 界面只按"值变了"判新旧
     *   （旧的"折角校准结果"用的是严格配对，那个键已随校准下线）。
     *
     * ⚠️ 为什么不让引擎自己震动 / 弹提示：本代码跑在 **SystemUI 进程**，
     *   从那里触发震动在部分 ROM 上会被拦（缺 `VIBRATE` 权限的持有者身份），
     *   而且"哪个 App 该收到反馈"这件事本身应当由界面决定。
     *
     * ⚠️ 引擎侧**只在真的到顶**时写一次（`dock 态` 与 `被拒` 都不写，见 `SplitTrigger.reportLimitHit`），
     *   并且有 2 秒去重 ⇒ 界面侧**不需要**再做节流。
     * ★ 上限值一并写回来（而不是让界面自己去读 `miui_multiple_split_resolved_max_stages`）：
     *   那是**引擎实测过的**那个数（`resolveMaxStages` 的产物），界面侧不该自己算一遍 ——
     *   两边算出的数不一致时，"到底几格是满"就成了第二个真值。
     */
    val SPLIT_BUS_LIMIT = PREFIX + "bus_split_limit"

    /**
     * App 自己的键：**我已经给用户提示过哪一条「到上限」回报了**（A4，2026-10-05）。
     *
     * ★★ 为什么必须**落盘**、而不是放在界面内存里（`remember`）：
     *   用户折手机的场景**必然不在本 App 界面上**（他在分屏的两个 App 里操作）——
     *   引擎那一刻写的提示，界面**根本来不及看到**。
     *   如果"看过没看过"只记在内存里，那个提示就永远丢了 —— 这正是第一版
     *   「没弹出提示」的根因（`SplitScreenPage` 里那个 `remember` 版轮询）。
     * ⇒ 落盘之后：用户**下次打开 App 就补上那条提示**。这是唯一能真正送达的语义。
     *
     * ⚠️ 它记的是**引擎那条记录的时间戳**（[SPLIT_BUS_LIMIT] 的第 1 段），不是本机时间 ——
     *   两边时钟是同一个（同机），所以直接比大小即可。
     * ★ 与 [SPLIT_BUS_LIMIT] 是**两个不同的东西**：那个是"引擎说了什么"（引擎写），
     *   这个是"用户看过没有"（App 写）⇒ ⛔ 别合并，否则引擎重写会把自己的已读状态冲掉。
     */
    const val SPLIT_LIMIT_SEEN = "split_limit_seen"

    // ---------------------------------------------------------------- 读写
    //
    // ★ 这三条**只该被引擎侧（SystemUI 进程）调用**：它是特权包，直写免 root。
    //   App 侧读没问题（读系统设置零门槛），但**绝不要**从这里写 ——
    //   改造前正是这条路逼出了 root 依赖，现在 App 侧的配置一律走自己的 prefs。
    // ⚠️ 2026-10-03 唯一例外：用户点「默认方向」要写**方向槽位**时，App 侧走
    //   [cn.dsr213.hyperplus.RootShell]（借 root 执行 `settings put`），
    //   仍然**不经过**下面这几个函数 —— 它们要的是"调用方自己就有权限"。

    fun readString(cr: ContentResolver, key: String): String? =
        runCatching { Settings.System.getString(cr, key) }.getOrNull()

    fun readInt(cr: ContentResolver, key: String, def: Int): Int =
        runCatching { Settings.System.getInt(cr, key, def) }.getOrDefault(def)

    /**
     * 写系统设置。
     *
     * ★ 改造后**不再有"失败转 root"的降级** —— 调用方只剩引擎（SystemUI，特权包），
     *   直写必然成功；而 App 侧已经不再走这条路。留一个 root 分支只会让人以为
     *   "App 侧也能写"，把刚摘掉的依赖又悄悄带回来。
     *
     * @return 是否写成功。失败必须如实返回，调用方不能假装成功。
     */
    fun writeString(cr: ContentResolver, key: String, v: String): Boolean =
        runCatching { Settings.System.putString(cr, key, v) }
            .onFailure { Log.w(TAG, "写 $key 失败", it) }
            .getOrDefault(false)

    fun writeInt(cr: ContentResolver, key: String, v: Int): Boolean =
        runCatching { Settings.System.putInt(cr, key, v) }
            .onFailure { Log.w(TAG, "写 $key 失败", it) }
            .getOrDefault(false)

    /** 删键（清空标定用 —— 让 [AppPrefs.isCalibrated] 的"键存在"判据回到未标定） */
    fun delete(cr: ContentResolver, key: String): Boolean =
        runCatching { Settings.System.putString(cr, key, null) }
            .onFailure { Log.w(TAG, "删 $key 失败", it) }
            .getOrDefault(false)
}

package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log

/**
 * 键名约定 + 引擎侧写 `Settings.System` 的工具。
 *
 * ============================ 这个类现在管什么（2026-09-28 重构后） ============================
 * 改造前它是「App ⇄ SystemUI 的**双向配置总线**」，代价是 App 侧必须借 root 写非公开键。
 * 现在配置改走文件（App 的 prefs，见 [cn.dsr213.hyperplus.module.ModulePrefs]），
 * 本类只剩两件**都不需要 root** 的事：
 *
 * | 组 | 谁写 | 谁读 | 走哪 |
 * |---|---|---|---|
 * | **配置键** [MODE_INNER] [STRATEGY] [HANDOFF_ROTATE] [GATE] [CALIB_REQ] [HINT_TEST] [WHITELIST_ADD] [WHITELIST_REMOVE] | App | 引擎 | App 的 prefs 文件（**只借用这里的键名常量**） |
 * | **引擎的账** [SIGN] [OFFSET] [RESTORE] [TAKEOVER] [CAMID] | 引擎 | 两边 | `Settings.System`（引擎是特权包，直写免 root） |
 * | **状态键** [STATE] [HEARTBEAT] [CALIB_RESULT] | 引擎 | App | `Settings.System` |
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
     *   只在两处当"老文件的指纹"用 —— [AppPrefs.migrateLegacyIfNeeded] 的"迁移过了吗"
     *   判据、[cn.dsr213.hyperplus.module.ModulePrefs] 的"这文件像不像我们的"兜底。
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
     *   **直接弹一次按钮**（存活时长照 [HINT_MS]）。与 [CALIB_REQ] / [UNCONTROLLABLE_CLEAR]
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
     * ★ 标定请求：App 写、引擎读，值是 `"<时间戳>|<步骤>"`（步骤见 `AppPrefs.CALIB_STEP_*`）。
     *
     * ⚠️ **刻意不复位**（旧实现是写 0 复位后反复触发）：配置通道的变更通知**不携带键名**，
     *   而引擎**没有权限写 App 的文件**，根本复位不了。于是改成"每次请求都是一个新值"，
     *   引擎靠"值变了"驱动，反而更干净 —— 不需要额外的复位握手。
     */
    const val CALIB_REQ = "calib_req"

    /**
     * ★ **清除「实测不可控」名单的请求**（App 写、引擎读）。
     *
     * ============================ 为什么要有这个键（跨进程的方向问题） ============================
     * 名单本体存在 `Settings.System`（[Uncontrollable.KEY]），**只有引擎能写** ——
     * App 的清单里没有 `WRITE_SETTINGS`（见 [PrefsBridge] 类注释：
     * "往非公开键里写"只认 SYSTEM / SHELL / ROOT uid 与特权包）。
     * 所以 App 侧那个"清除"按钮**不能直接删键**，只能走这条约定：
     *
     * ```
     * App 点清除 ⇒ 往自己的 prefs 写一个新时间戳 ⇒ 引擎读到"值变了" ⇒ 引擎代删 Settings 键
     * ```
     *
     * ★ 与 [CALIB_REQ] **同一个套路**：值本身就是"每次请求一个新高"，靠"变了"驱动，
     *   不需要复位握手 —— 而复位恰恰做不到（引擎写不了 App 的私有文件）。
     */
    const val UNCONTROLLABLE_CLEAR = "uncontrollable_clear"

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

    /**
     * ★★ **实时角度预览**开关（App 写、引擎读；2026-10-01 新增）。
     *
     * 用户原话：「在方向校准里面加一个实时的角度显示，我告诉你正确方向」。
     *
     * 语义：界面打开「方向校准」时置 `true`、离开时置 `false`；引擎读到 `true` 就
     * **持续开前摄采帧**，并把实时角度写到 [LIVE_ANGLE]。
     *
     * ⚠️ 与其他请求键（[CALIB_REQ] / [HINT_TEST]）**不同**，它是一个**状态**而不是
     *   一次性请求 —— 所以引擎读它不需要"值变了"那套判据，直接跟着走即可。
     *   但它必须**不跨进程启动存活**：预览只在"用户正盯着那一页"时有意义，
     *   进程重启后还自动开相机是白耗电。两侧各自兜住这件事，见
     *   `AppPrefs.init`（读盘时强制复位）与 `AdaptiveEngine.setAnglePreview`（首次只记账）。
     */
    const val ANGLE_PREVIEW = "angle_preview"

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
     * 宿主 → App：**实时角度**（2026-10-01 新增）。
     *
     * 格式：`"<归一化角>|<原始roll>|<判定方向>|<系统方向>|<形态>|<时间戳ms>"`
     * （角度为空串 = 当前没识别到人脸；时间戳 = 宿主的 `elapsedRealtime()`，
     *  App 与宿主是同一条系统级时钟，可直接算"这个读数有多旧"）。
     *
     * ⚠️ **刻意不走 [STATE] 那条总线**：总线是"变化才上报 + 2 秒节流"的慢变量通道，
     *   而这里是 4Hz 的快变量。混进去会让整串状态每次都变，
     *   等于把总线变成一条持续写入的日志（也会把「最近事件」冲掉）。
     *   单开一个键，两边互不打扰。
     */
    val LIVE_ANGLE = PREFIX + "bus_live_angle"

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

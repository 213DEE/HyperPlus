package cn.dsr213.hyperplus

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「导出诊断日志」的**采集层** —— 把所有能读到的线索收成一段段文本。
 *
 * ============================ 分层原则（本文件的核心） ============================
 * | 层 | 内容 | 要不要 root |
 * |---|---|---|
 * | 环境 | 机型 / Android / HyperOS / ABI / App 版本 / 安装时间 | ❌ |
 * | 模块与配置 | `ModuleLink.State` 全字段（★ 含 `raw` 原始串）/ 心跳 / 配置镜像 / prefs / 上限读数 | ❌ |
 * | 日志 | App 自身 `logcat`；**有 root 追加**模块 TAG 日志 ＋ 自检报告 ＋ 崩溃缓冲 ＋ tombstones | 部分 |
 *
 * ★ **为什么日志必须分层**（这是本功能最要紧的一条事实）：
 *   引擎住在 **SystemUI 进程**里，两边 UID 不同 ⇒ **App 无 root 时读不到模块的任何一行日志**。
 *   所以「有没有 root」不是可选信息，它**直接决定了这份报告能回答多少问题**
 *   ⇒ 写进报告头部，让收到文件的人第一眼就知道该往哪看。
 *
 * ⚠️ 采集**全是只读动作**，不改任何设置、不写任何系统位置。
 *   唯一的一次写入发生在 UI 层（把报告写进 App 自己的 cacheDir），见 `DiagnosticsPage`。
 *
 * ⚠️ 这里的两条 root 纪律照抄 [RootShell] 的类注释：
 *   ① 每次调用起一个 `su` 进程 ⇒ **合成一条脚本**（本文件只有 [rootProbe] 一次调用）；
 *   ② 所有 root 输出都可能混着 stderr（`redirectErrorStream`）⇒ 原样收进报告，
 *      ⛔ 不做「猜哪行是错误」的加工（报告是给排查用的，加工过的信息反而更危险）。
 */
internal object DiagnosticsCollect {

    private const val TAG = "HyperPlusDiag"

    /**
     * 模块侧日志的 TAG 全表（2026-10-06 从源码 `grep 'const val TAG ='` 抽出来的，19 个）。
     *
     * ⚠️ 这张表**可能漏**（新文件会加新 TAG）⇒ 所以报告里另外附一段
     *   「未过滤的全量尾部」，宁可多给也不漏。
     */
    private val MODULE_TAGS = listOf(
        "HyperPlusModule", "HyperPlusModulePrefs", "HyperPlusPrefs", "HyperPlusSplitLimit",
        "HyperPlusSplit", "HyperPlusSplitProbe", "HyperPlusLink", "HyperPlusRoot",
        "HyperPlusChannel", "HyperPlusFgGate", "HyperPlusScreenForm", "HyperPlusSemiHint",
        "HyperPlusMain", "HyperPlusToast", "HyperPlusSqliteBin", "HyperPlusDisplaySize",
        "FaceRotate",
    )

    /**
     * 模块侧落盘文件所在的**候选目录**（按「最可能 → 兜底」排序，取第一个存在的）。
     *
     * ★★ 2026-10-07 实测（踩到了）：SystemUI 是 **direct-boot aware** 应用，`hostCtx.filesDir`
     *   在本机（lhasa / Android 17）解析成 **`/data/user_de/0/com.android.systemui/files`**，
     *   ⛔ **不是** `/data/data/...` —— `/data/data` 只是 `/data/user/0` 的软链，两者不是一回事。
     *   原先只写死 `/data/data/...` 一条 ⇒ 报告里恒显示「没有这个文件」，
     *   而实际上自检报告 **2026-09-25 就落过一份**（`hyperplus_selfcheck.txt`，2237 字节）。
     *   ⇒ 这里全列出来、取第一个存在的；报告里会写明**实际命中**的是哪条，免得再猜。
     */
    private val MODULE_FILE_DIRS = listOf(
        "/data/user_de/0/com.android.systemui/files", // ← 实测命中（DE 存储）
        "/data/user/0/com.android.systemui/files", // 非 DE 应用的常规位置
        "/data/data/com.android.systemui/files", // 软链兜底
    )

    /** 模块侧落盘的文件名 —— 分别与 `module/HyperPlusModule.reportFile`、`module/BootReport.FILE_NAME` 一致 */
    private const val SELF_CHECK_NAME = "hyperplus_selfcheck.txt"

    /**
     * 模块「启动摘要」的文件名 —— ★★ 读它的理由（2026-10-07 实测）：
     * `main` logcat 缓冲在本机**只保留 ~8–10 分钟**（SystemUI 02:10 重启打的「模块已加载」
     * 到 02:18 导出时就没了，`-s HyperPlusModule` 返回 **0 行**）⇒ 「模块到底有没有起来」
     * 这条**最先消失**，而它恰恰是用户报障时最需要的。模块那边已把关键节点**无条件**落盘
     * （见 `module/BootReport`），这里读回来补上这个空档。
     */
    private const val BOOT_REPORT_NAME = "hyperplus_boot.txt"

    /** 报告别动辄几 MB：每段都设上限 */
    private const val TAIL_APP_LOG = 400
    private const val TAIL_MODULE_LOG = 600

    /**
     * 「未过滤的全量尾部」—— 取**最近这么多分钟**的 `all` 缓冲（⛔ 不是「最近 N 行」）。
     *
     * ★★ 2026-10-07 实测更正：这一段原来是按**行数**取的（300 → 3000），**这条路走不通** ——
     *   本机 `all` 缓冲有 **578,341 行 / 约 12 分钟**，`-t 3000` 只覆盖**几秒钟**：
     *   实测「最近 3000 行」里属于我们的日志 **0 条**（恒为空 ⇒ 这一段等于不存在）；
     *   `-t 200000` 才覆盖约 1/3（922 ms）。
     *   ⇒ 改用 logcat 的**时间窗**形式 `-t '<MM-dd HH:mm:ss.SSS>'`（语义＝「打这个时刻之后的行」）：
     *     窗口**完整**、而且 logcat 自己按时间截断（不用把几十万行灌进管道）。
     *     实测 12 分钟窗 = **526 ms**，同一窗口命中我们 TAG **26 行**（按行数取是 0 行）。
     *
     * ⚠️ 过滤用 `-E "HyperPlus|FaceRotate"`（**区分大小写**）：原先的 `grep -i hyperplus`
     *   会把 powerkeeper / MiSight 因**层名** `VRI-cn.dsr213.hyperplus/…` 打的行全捞进来
     *   （实测那些命中 100% 是噪音，一条我们自己的日志都没有）。我们的 TAG 一律以
     *   `HyperPlus` / `FaceRotate` 开头、**大写 P**，而包名是小写 ⇒ 改一下就干净了。
     *
     * ⚠️ 本段**允许为空**（这段时间模块确实没打日志也是常态）—— 空不等于出故障，故给的是说明句。
     */
    private const val ALL_LOG_WINDOW_MIN = 15

    /** 上面那个窗口的毫秒形式（只给 logcat 那条命令用） */
    private const val ALL_LOG_WINDOW_MS = ALL_LOG_WINDOW_MIN * 60_000L
    private const val TAIL_CRASH_LOG = 150
    private const val TAIL_SELF_CHECK = 150
    private const val TAIL_BOOT_REPORT = 80

    /** 非 root 的 shell 命令也必须有超时（挂住就等于把界面吊死） */
    private const val SHELL_TIMEOUT_MS = 5_000L

    // ================================================================ 入口

    /**
     * 采一份完整报告。
     *
     * **不要在主线程序列化调用** —— 里面会跑 `su`（有 root 时）与 `logcat`。
     *
     * @return `文件名 to 内容`
     */
    suspend fun collect(ctx: Context): Pair<String, String> {
        val app = ctx.applicationContext ?: ctx
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(now))
        val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

        // ★ root 三态：它决定报告里「模块日志」那半是内容还是说明
        val root = runCatching { RootShell.probe() }.getOrNull()
        val state = runCatching { ModuleLink.currentState(app) }.getOrNull()

        // ★ 2026-10-07：这条必须打在**采集之前** —— 报告里的「App 自身日志」是稍后从 logcat 读的，
        //   只有先落一条锚点，收到报告的人才能确认「这次导出确实跑过」。
        //   （原先只有结尾那条「已采集」，而它必然不在自己抓到的 logcat 里 ⇒ grep 恒 0 命中。）
        Log.i(TAG, "▶ 开始采集诊断报告（root=${rootText(root)}）")

        val header = listOf(
            "HyperPlus 诊断日志",
            "生成时间 = $human",
            "root = ${rootText(root)}",
            "",
            "⚠️ 本文件含你的设备信息、「已单独设置的应用包名」与运行日志 —— 发给别人之前请自己先扫一眼。",
            if (root == RootShell.RootStatus.GRANTED) {
                "✅ 已授权 root ⇒ 模块（系统界面进程）的日志已一并收录。"
            } else {
                "⚠️ 未授权 root ⇒ **模块（系统界面进程）的日志读不到**（进程 UID 隔离）。" +
                    "想让它一起带上：到「权限管理」页授权 root 后重新导出。"
            },
        )

        val sections = mutableListOf<DiagnosticsReport.Section>()
        sections += envSection(app)
        sections += moduleSection(app, state)
        sections += configSection(app)
        sections += logSections(root)

        Log.i(TAG, "诊断报告已采集：${sections.size} 段（root=${rootText(root)}）")
        return DiagnosticsReport.fileName(stamp) to DiagnosticsReport.build(header, sections)
    }

    private fun rootText(r: RootShell.RootStatus?): String = when (r) {
        null -> DiagnosticsReport.NOT_READ
        RootShell.RootStatus.GRANTED -> "已授权"
        RootShell.RootStatus.DENIED -> "有 su 但没授权"
        RootShell.RootStatus.UNAVAILABLE -> "这台机器没有 root"
    }

    // ================================================================ ① 环境

    private fun envSection(ctx: Context): DiagnosticsReport.Section {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val cfg = ctx.resources.configuration
        return DiagnosticsReport.Section(
            "环境",
            listOf(
                DiagnosticsReport.line("App 版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                DiagnosticsReport.line("包名", ctx.packageName),
                DiagnosticsReport.line("安装时间", pi?.firstInstallTime?.let { fmt.format(Date(it)) }),
                DiagnosticsReport.line("上次更新", pi?.lastUpdateTime?.let { fmt.format(Date(it)) }),
                DiagnosticsReport.line("品牌 / 型号", "${Build.MANUFACTURER} / ${Build.MODEL}"),
                DiagnosticsReport.line("设备代号", Build.DEVICE),
                DiagnosticsReport.line("Android", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"),
                DiagnosticsReport.line("安全补丁", Build.VERSION.SECURITY_PATCH),
                DiagnosticsReport.line("系统构建号", Build.DISPLAY),
                DiagnosticsReport.line("CPU 架构", Build.SUPPORTED_ABIS.joinToString()),
                DiagnosticsReport.line("内核", System.getProperty("os.version")),
                // ★ 为什么单独要看 HyperOS 版本：本项目的分屏增强依赖 MIUI 私有类
                //   （`Miui-WindowManager-Shell.jar` 里的 `MultipleSplitStageOrderOperator`）
                //   ⇒ 非小米机型上「提高分屏上限」必然无效，这一格是判断它的第一依据。
                DiagnosticsReport.line("HyperOS / MIUI 版本", prop("ro.miui.ui.version.name")),
                DiagnosticsReport.line("HyperOS 版本号", prop("ro.miui.ui.version.code")),
                DiagnosticsReport.line(
                    "屏幕",
                    "widthDp=${cfg.screenWidthDp} heightDp=${cfg.screenHeightDp} " +
                        "smallestWidthDp=${cfg.smallestScreenWidthDp} densityDpi=${cfg.densityDpi} " +
                        "orientation=${cfg.orientation}",
                ),
            ),
        )
    }

    // ================================================================ ② 模块状态

    /**
     * `ModuleLink.State` 的逐字段展开。
     *
     * ★ 读不到时**不写一堆空行**，而是直接把最可能的原因摊在台面上 ——
     *   这一格就是「用户说用不了」时最该看的地方。
     */
    private fun moduleSection(ctx: Context, s: ModuleLink.State?): DiagnosticsReport.Section {
        if (s == null) {
            return DiagnosticsReport.Section(
                "模块状态",
                listOf(
                    "⚠️ 读不到状态串 ⇒ 引擎很可能**根本没有被加载**。按可能性排序自检：",
                    "   ① LSPosed 里本模块的作用域**没勾「系统界面」**（只勾了本应用）——最常见；",
                    "   ② 模块在 LSPosed 里**没启用**；",
                    "   ③ 勾了之后**没有重启过系统界面**（改作用域必须重启才对引擎生效）；",
                    "   ④ 模块被 LSPosed 的崩溃保护自动禁用了（连续崩溃后会发生）。",
                    "",
                    DiagnosticsReport.line("原始状态串", rawSetting(ctx, PrefsBridge.STATE)),
                    DiagnosticsReport.line("心跳（elapsedRealtime 秒）", heartbeatText(ctx)),
                ),
            )
        }
        return DiagnosticsReport.Section(
            "模块状态（引擎回传）",
            listOf(
                // —— 存活与启动 ——
                DiagnosticsReport.line("阶段 phase", rawField(s.raw, "phase")),
                DiagnosticsReport.flag("宿主在线 hostAlive", s.hostAlive),
                DiagnosticsReport.line("心跳距今（秒）", "${s.heartbeatAgoSec}"),
                DiagnosticsReport.line("已运行（秒）", "${s.uptimeSec}"),
                DiagnosticsReport.line("熔断连续失败（attempts）", "${s.breakerAttempts}"),
                DiagnosticsReport.line("模式 mode", rawField(s.raw, "mode")),
                DiagnosticsReport.line("判定器 decider", rawField(s.raw, "decider")),
                DiagnosticsReport.flag("已接管 takeover", s.takeover),
                DiagnosticsReport.line("目标方向 rot", "${s.rotation}"),
                DiagnosticsReport.line("实际显示方向 disp", "${s.display}"),
                // —— 配置通道（新架构唯一的单点）——
                DiagnosticsReport.tri("引擎读到配置 cfgold", s.cfgOk),
                DiagnosticsReport.line("通道诊断 cfgmsg", rawField(s.raw, "cfgmsg")),
                // —— 权限（三态，见 State.grantReported）——
                DiagnosticsReport.tri("修改系统设置 grant", if (s.grantReported) s.writeGranted else null),
                // —— 识别指标 ——
                DiagnosticsReport.line("识别轮次 burst", "${s.burstCount}"),
                DiagnosticsReport.line("帧 / 人脸", "${s.frames} / ${s.faces}"),
                DiagnosticsReport.line("切换次数 sw", "${s.switchCount}"),
                DiagnosticsReport.line("票面 votes", rawField(s.raw, "votes")),
                DiagnosticsReport.line("票王 vwin", "${s.voteWinner}"),
                DiagnosticsReport.line("有效票 vvalid", "${s.voteValid}"),
                DiagnosticsReport.flag("票已定论 vconf", s.voteConfident),
                DiagnosticsReport.line("前摄编号 cid", rawField(s.raw, "cid")),
                DiagnosticsReport.line("前摄被抢 conflict", "${s.conflictCount}"),
                DiagnosticsReport.line("打开耗时 openMs", "${s.lastOpenMs}"),
                // —— 方向基准（原「校准」）——
                DiagnosticsReport.flag("已校准 calib", s.calibrated),
                DiagnosticsReport.line("符号位 sign", "${s.calibSign}"),
                DiagnosticsReport.line("偏移 offset", "${s.calibOffsetDeg}"),
                DiagnosticsReport.line("重力扇区 grav", "${s.gravitySector}"),
                DiagnosticsReport.line("符号校验 sgnS / sgnF", "${s.signSame} / ${s.signFlip}"),
                DiagnosticsReport.flag("符号已确证 sgnOK", s.signConfirmed),
                DiagnosticsReport.flag("方向传感器可用 sensor", s.sensorAvailable),
                // —— 前台门控 ——
                DiagnosticsReport.flag("门控总开关 gateon", s.gateEnabled),
                DiagnosticsReport.flag("正在停手 fg", s.foregroundGated),
                DiagnosticsReport.line("停手原因 fgstop", rawField(s.raw, "fgstop")),
                DiagnosticsReport.line("前台包 fgpkg", rawField(s.raw, "fgpkg")),
                DiagnosticsReport.line("前台声明朝向 fgori", rawField(s.raw, "fgori")),
                DiagnosticsReport.flag("前台朝向可读 fgr", s.foregroundReadable),
                DiagnosticsReport.line("因门跳过 gate", "${s.skipGateCount}"),
                DiagnosticsReport.flag("停手时交还系统旋转 handoff", s.handoffRotate),
                // —— 名单 ——
                DiagnosticsReport.line("生效名单条数 wlN", "${s.whitelistSize}"),
                DiagnosticsReport.line("本屏安装朝向偏移 pofs", "${s.panelOffset}"),
                // —— 半自动 ——
                DiagnosticsReport.line("按钮弹出 semi", "${s.semiShown}"),
                DiagnosticsReport.line("目标方向 semitgt", "${s.semiTarget}"),
                DiagnosticsReport.line("已确认 semitap", "${s.semiTapped}"),
                DiagnosticsReport.flag("悬浮窗可用 ovl", s.overlayOk),
                DiagnosticsReport.line("悬浮窗类型 ovlt", "${s.overlayType}"),
                // —— 失败计数（★ 静默失败的唯一出口）——
                DiagnosticsReport.line("失败计数 errs", rawField(s.raw, "errs")),
                // —— 原始串（★ 最有价值的一格）——
                DiagnosticsReport.line("原始状态串 raw", s.raw),
            ),
        )
    }

    // ================================================================ ③ 配置与通道

    private fun configSection(ctx: Context): DiagnosticsReport.Section {
        val cr = ctx.contentResolver
        val lim = runCatching { ModuleLink.readSplitLimit(ctx) }.getOrNull()
        return DiagnosticsReport.Section(
            "配置与通道",
            listOf(
                DiagnosticsReport.line("配置镜像原文", runCatching { PrefsBridge.readString(cr, PrefsBridge.MIRROR) }.getOrNull()),
                DiagnosticsReport.line("状态串原文", runCatching { PrefsBridge.readString(cr, PrefsBridge.STATE) }.getOrNull()),
                DiagnosticsReport.line("心跳原文", heartbeatText(ctx)),
                DiagnosticsReport.line("到上限提示（bus_split_limit）", if (lim == null) null else "${lim.count} / ${lim.max}"),
                DiagnosticsReport.line("分屏触发档位", "${runCatching { ModuleLink.splitTriggerMode(ctx) }.getOrDefault(0)}"),
                // ★★ 这一格是「开了开关到底有没有生效」的外部判据：
                //   属性 = 引擎读到的开关值；上限 = 系统实际算出来的格数（真生效才会是 8）。
                DiagnosticsReport.line("属性 persist.sys.hyperplus.multisplit", prop(PrefsBridge.PROP_MULTISPLIT)),
                DiagnosticsReport.line("属性 persist.sys.multiple.split.max_stages（ROM 自带，只读）", prop("persist.sys.multiple.split.max_stages")),
                DiagnosticsReport.line("系统上限 miui_multiple_split_resolved_max_stages", globalText(ctx, "miui_multiple_split_resolved_max_stages")),
                // ⚠️ 2026-10-07 修：accelerometer_rotation 属 Settings.**System**（不是 Global）——
                //   原先用 globalText 读，恒得 null，报告里错显「(未读到)」。
                //   ★ 这一格是「系统自动旋转到底开没开」的唯一判据（[[MEM-02]]），不能缺。
                DiagnosticsReport.line("加速计旋转 accelerometer_rotation", systemText(ctx, "accelerometer_rotation")),
                DiagnosticsReport.line("用户旋转 user_rotation", systemText(ctx, "user_rotation")),
                "",
                "—— App 本地设置（prefs：${AppPrefs.NAME}，按字母序）——",
            ) + prefLines(ctx),
        )
    }

    /**
     * App prefs 全量。
     * ⚠️ 这里会**如实地**把用户设置过的东西都带出来（含应用名单的包名）——
     *   那正是「他说某个应用不跟手」时要看的输入。头部的隐私提示就是为它写的。
     */
    private fun prefLines(ctx: Context): List<String> = runCatching {
        ctx.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE).all
            .toSortedMap()
            .map { (k, v) -> "$k = $v" }
            .ifEmpty { listOf(DiagnosticsReport.NOT_READ) }
    }.getOrDefault(listOf(DiagnosticsReport.NOT_READ))

    // ================================================================ ④ 日志

    private suspend fun logSections(root: RootShell.RootStatus?): List<DiagnosticsReport.Section> {
        val out = mutableListOf<DiagnosticsReport.Section>()

        // ① App 自身：无 root 也拿得到。
        // ★★ 标题按**包型**分叉（2026-10-07 用户选 B）—— 一句断言不可能同时对两种包成立：
        //   · **release 包**：Android 4.1 起 logcat 按 UID 隔离 ⇒ 确实只含本应用进程。
        //   · **debug 包**（`debuggable=true`）：**不做 UID 过滤**。本机实测该段混进
        //     pid 7373（system_server 的 PerfBoostPolicy / SchedBoost / MIUIInput publisher）
        //     与 12000（iui.powerkeeper）⇒ 照 release 那句话写，等于对 debug 报告说了假话。
        //   ⇒ 索性把**包型本身**写进标题，让收到文件的人一眼知道这段该按哪种口径读。
        val appLogTitle = if (BuildConfig.DEBUG) {
            "App 自身日志（logcat；⚠️ 本机为 debug 包，未按 UID 过滤，可能含其他进程）"
        } else {
            "App 自身日志（logcat，只看得到本进程）"
        }
        out += DiagnosticsReport.raw(
            appLogTitle,
            shell(arrayOf("logcat", "-d", "-t", "$TAIL_APP_LOG")).also {
                if (it == null) Log.w(TAG, "读 App 自身 logcat 失败（可能被系统限制）")
            } ?: "（读不到 —— 本机可能限制了应用读 logcat）",
        )

        if (root != RootShell.RootStatus.GRANTED) {
            out += DiagnosticsReport.raw(
                "模块日志（系统界面进程）",
                "未授权 root ⇒ 读不到。\n" +
                    "原因：引擎跑在 com.android.systemui 进程里，与 App 的 UID 不同，\n" +
                    "Android 从 4.1 起就按 UID 隔离日志 —— 只有 root 能看到别人进程的日志。\n" +
                    "⇒ 到「权限管理」页授权 root 后重新导出，这里就会带上模块的全部日志。",
            )
            return out
        }

        // ② 有 root：一条脚本抓全（★ 只起一次 su —— RootShell 的纪律 ①）
        // ★ 时间窗的**起点**：logcat 的 `-t '<MM-dd HH:mm:ss.SSS>'` 语义是「这个时刻之后的行」，
        //   格式与 `SimpleDateFormat` 的 `MM-dd HH:mm:ss.SSS` 一致（本机实测通过）。
        val allSince = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(System.currentTimeMillis() - ALL_LOG_WINDOW_MS))
        val script = buildString {
            // ★ 2026-10-07 新增：先读「模块启动摘要」—— logcat 缓冲只保 ~10 分钟，模块刚起来那几条
            //   （模块已加载 / 注入成功 / 钩子安装 / 上限闸门）届时**必已被挤掉**，
            //   只有这份落盘文件能证明「模块到底有没有跑起来」。放在最前面，因为它最要紧。
            append("echo '--- 模块启动摘要（$BOOT_REPORT_NAME） ---';")
            catFirst(
                BOOT_REPORT_NAME,
                TAIL_BOOT_REPORT,
                "（还没有这个文件 —— 模块本次启动后还没写入，或模块根本没加载）",
            )
            append("echo;echo '--- 模块日志（按 TAG 过滤，只看系统界面进程，尾部 $TAIL_MODULE_LOG 行） ---';")
            // ★ 2026-10-07：HyperPlusPrefs / HyperPlusScreenForm / HyperPlusRoot 这三个 TAG **App 侧也在用**
            //   ⇒ 不加 uid 限定会把 App 进程的行混进来（与上一段重复）。这里按 SystemUI 的 uid 过滤。
            //   uid **现查现用**；查不到就**退回不过滤** —— 宁可多给，也不能因为查不到 uid 把模块日志整段丢掉。
            append("U=\$(dumpsys package com.android.systemui 2>/dev/null | grep -m1 -o 'uid=[0-9]*' | cut -d= -f2);")
            append("if [ -n \"\$U\" ]; then UARG=\"--uid=\$U\"; else UARG=\"\"; fi;")
            append("logcat -d -b all \$UARG -s ${MODULE_TAGS.joinToString(" ")} 2>&1 | tail -n $TAIL_MODULE_LOG;")
            // ★ 2026-10-07 改：按**时间窗**取（理由见 [ALL_LOG_WINDOW_MIN]），并把过滤改成
            //   **区分大小写**的 TAG 匹配 —— 原先那版「按行数取 + `grep -i hyperplus`」
            //   实测恒 0 行，且捞回来的全是别家**层名**的噪音（那段等于不存在）。
            //   ⚠️ 顺带修掉一个老错别字：标题原写「全量尾部**署**」（0324 那份报告里也有）。
            append("echo;echo '--- 未过滤的全量尾部（最近 $ALL_LOG_WINDOW_MIN 分钟里含 HyperPlus/FaceRotate 的行） ---';")
            // ⚠️ 模式写成 `HyperPlu[s]|FaceRotat[e]`（**不是** `HyperPlus|FaceRotate`）：字符类绕开
            //   了它自己的字面量 —— 否则「执行这条命令」这件事本身会被 `adbd` 记进 logcat，
            //   而那条记录里恰好含 `HyperPlus`，于是**自己的命令把自己喂给了自己的过滤器**
            //   （实测确认：不加这个绕法，跑一次 adb 就会凭空多出 2~6 条"命中"）。
            append("L=\$(logcat -d -b all -t '$allSince' 2>&1 | grep -E 'HyperPlu[s]|FaceRotat[e]');")
            append("if [ -z \"\$L\" ]; then echo '（这个时间窗里没有我们 TAG 的日志 —— 可能这段时间模块本来就没打）'; else echo \"\$L\"; fi;")
            append("echo;echo '--- 崩溃缓冲 ---';")
            append("logcat -b crash -d -t $TAIL_CRASH_LOG 2>&1;")
            // ★★ 2026-10-07 新增（P30）：**dropbox**。原先报告只收 `logcat -b crash`
            //   （缓冲区滚动极快）＋ tombstone 列表 ⇒ 「系统为什么自己重启」这条线索
            //   **一条都进不来** —— 本轮三类 SystemUI 崩溃全靠手工 adb 读
            //   `/data/system/dropbox/` 才发现。这里只列最近 6 份的**头部**
            //   （该文件前 20 行就含 Timestamp / Process / Process-Runtime /
            //   Keyguard-Locked / 异常首行 —— 定位已经够用），**全程只读**。
            //   ⚠️ `Process-Runtime` 是判「进程活了多久才崩」的关键格；
            //      `Keyguard-Locked` 是判「是不是发生在锁屏期间」的关键格（P28 就是靠它定的性）。
            append("echo;echo '--- 系统崩溃与重启记录（dropbox，最近 6 份；这是「系统自己重启」的第一现场） ---';")
            append("for f in \$(ls -t /data/system/dropbox/system_app_crash*.txt /data/system/dropbox/SYSTEM_RESTART*.txt /data/system/dropbox/system_app_anr*.txt 2>/dev/null | head -n 6); do ")
            append("echo \"### \$(basename \$f)\"; head -n 26 \$f; echo; done;")
            append("echo '--- dropbox 目录概览（最近 20 条）---';")
            append("ls -lt /data/system/dropbox/ 2>&1 | head -n 20;")
            // ★ 2026-10-07：自检**默认是关的**（`hyperplus_selfcheck` 未设 ⇒ 连 files 目录都不存在），
            //   所以「文件不在」是正常状态。裸 `cat` 会吐 `No such file or directory`，看起来像出故障。
            append("echo;echo '--- 模块自检报告（$SELF_CHECK_NAME） ---';")
            catFirst(
                SELF_CHECK_NAME,
                TAIL_SELF_CHECK,
                "（没有这个文件 —— 自检默认关闭，属正常状态）",
                "  要看自检：adb shell settings put global hyperplus_selfcheck 1，然后重启系统界面。",
            )
            // ★ 2026-10-07：改按**时间倒序**取尾 —— 原先 `ls -l | tail` 是字典序，
            //   tombstone 编号跨位（9 与 10）时取到的不是最新的。
            append("echo;echo '--- tombstones（有文件 = native 崩溃；按时间倒序，最新在前） ---';")
            append("ls -lt /data/tombstones/ 2>&1 | head -n 12;")
            append("echo;echo '--- 关键属性 ---';")
            append("getprop | grep -iE 'hyperplus|multiple.split' 2>&1;")
            append("echo;echo '--- SystemUI 的 WRITE_SETTINGS ---';")
            append("dumpsys package com.android.systemui 2>&1 | grep -A2 WRITE_SETTINGS | head -n 12;")
            append("echo;echo '--- 系统分屏上限 ---';")
            append("settings get global miui_multiple_split_resolved_max_stages 2>&1;")
        }
        val text = runCatching { RootShell.runRaw(script) }.getOrNull()
        out += DiagnosticsReport.raw(
            "模块日志（借 root，系统界面进程）",
            text ?: "（su 执行失败 —— 可能授权被拒或超时）",
        )
        return out
    }

    // ================================================================ 小工具

    /**
     * 生成「在 [MODULE_FILE_DIRS] 里找到**第一个存在的**文件、cat 其尾部」的 shell 片段。
     *
     * ★ 必须遍历候选目录：理由见 [MODULE_FILE_DIRS] 的注释 —— SystemUI 是 DE 应用，
     *   文件实际落在 `/data/user_de/0/...`，写死 `/data/data/...` 会**恒判为不存在**。
     *
     * ★ 命中时把**实际路径**一起打出来：报告里能一眼看到文件到底在哪，
     *   下次再遇到路径疑问就不用再猜（这是 2026-10-07 那次踩坑的直接教训）。
     *
     * @param missingLines 一个都没找到时要打的说明（每行一条，⛔ 不要含单引号）
     */
    private fun StringBuilder.catFirst(fileName: String, tail: Int, vararg missingLines: String) {
        append("F='';")
        append("for D in ${MODULE_FILE_DIRS.joinToString(" ")}; do ")
        append("if [ -f \"\$D/$fileName\" ]; then F=\"\$D/$fileName\"; break; fi; done;")
        append("if [ -n \"\$F\" ]; then echo \"（命中 \$F）\"; cat \"\$F\" 2>&1 | tail -n $tail;")
        append("else ")
        for (line in missingLines) append("echo '$line';")
        append("fi;")
    }

    /**
     * 从模块的**原始状态串**里取某个键的值。
     *
     * ★ 为什么不能直接用 [ModuleLink.State] 的同名字段（2026-10-07 用户拍板）：`State.parse`
     *   写的是 `kv["votes"] ?: ""` —— 「模块**没回传**这个键」与「模块回传了**空值**」
     *   **都会变成 `""`**，App 侧于是分不开。可这两件事含义正好相反：
     *     · 键在、值为空 ⇒ **模块明确报了空**（如 `errs=` = 没有失败记录）；
     *     · 键不在       ⇒ 这一项**压根没拿到**。
     *   把后者说成前者，就是本项目最忌讳的那类误报。⇒ 直接回原始串判「**键在不在**」。
     *
     * 格式（见 `module/EngineHost` 的序列化）：`v1|key=value|key=value|…`
     *
     * @return `null` = 键不存在（未读到）；`""` = 键在但值为空；其余为原值。
     */
    private fun rawField(raw: String?, key: String): String? {
        if (raw == null) return null
        val marker = "|$key="
        val at = raw.indexOf(marker)
        if (at < 0) return null
        val start = at + marker.length
        val end = raw.indexOf('|', start)
        return if (end < 0) raw.substring(start) else raw.substring(start, end)
    }

    /**
     * 跑一条**非 root** 的公开命令（`getprop`），带超时。
     *
     * ⚠️ `getprop` 本身是 `/system/bin` 下的公开可执行文件，普通应用跑它**不需要 root**。
     *   （⛔ 别改成反射 `android.os.SystemProperties` —— 那是 hidden API，随时可能被拦。）
     *
     * @return null = 跑不动或没输出
     */
    private fun shell(cmd: Array<String>): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        try {
            if (!p.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return@runCatching null
            }
            p.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            p.destroyForcibly()
            throw t
        }
    }.getOrNull()

    /** 读一个系统属性（走 `getprop`，见 [shell]） */
    private fun prop(key: String): String? =
        shell(arrayOf("getprop", key))?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.trim()

    private fun globalText(ctx: Context, key: String): String? = runCatching {
        Settings.Global.getString(ctx.contentResolver, key)
    }.getOrNull()

    private fun systemText(ctx: Context, key: String): String? = runCatching {
        Settings.System.getString(ctx.contentResolver, key)
    }.getOrNull()

    private fun rawSetting(ctx: Context, key: String): String? =
        runCatching { PrefsBridge.readString(ctx.contentResolver, key) }.getOrNull()

    private fun heartbeatText(ctx: Context): String? = runCatching {
        val sec = PrefsBridge.readInt(ctx.contentResolver, PrefsBridge.HEARTBEAT, 0)
        if (sec <= 0) null else "$sec"
    }.getOrNull()
}

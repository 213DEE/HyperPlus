package cn.dsr213.hyperplus.module

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import cn.dsr213.hyperplus.ConfigChannel
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.SplitStageGate
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把多分屏（`MultipleSplit`）的**格数上限抬到 8** —— 2026-10-06 实验。
 *
 * ==================== ★★ 由「实验功能」页的开关门控（2026-10-06 追加） ====================
 * 它**不再无条件生效**：只有「实验功能 → **提高分屏上限**」打开时才改写；
 * 关着的时候本类**一个字节都不改**（系统自己的上限原样保留，本机 = 6）。
 *
 * ★★ 开关的值**怎么进来：两条通道，属性优先**（2026-10-06 改）：
 *   ```
 *   ① 【主】App 借 root 把开关写进 `persist.sys.hyperplus.multisplit`
 *        （[PrefsBridge.PROP_MULTISPLIT]）⇒ 本类读属性 ⇒ **进程第一个毫秒就能拿到**
 *   ② 【兜底】属性没写过时，仍走原来那条：
 *        开关住在 App 的 prefs 里 ⇒ `ConfigChannel` 把它编进全量快照
 *          ⇒ 引擎收到后把整份快照落成 `Settings.System` 里的一个键（[PrefsBridge.MIRROR]）
 *            ⇒ 本类读那个键、用 `ConfigChannel.decode` 解开
 *   ```
 *   ⛔ 兜底那条**不许删**：属性从没写过的用户（老版本升上来、或写入失败）全靠它。
 *
 * ★★ 为什么非要加 ① 这条属性通道 —— 实测数字（2026-10-06 08:01:49，逐条日志）：
 *   `MultipleSplitOrganizer.<clinit>` 在 **Δ380 ms** 就调 `resolveMaxStages`
 *   —— 那是我们的**截止线**；而走 `Settings.System` 那条路 **Δ4987 ms** 才第一次
 *   读到值 ⇒ **晚了 4.2 秒**（其间镜像连读 51 次全是空）。
 *   属性是本进程内存里的 mmap 区，不经过 Binder ⇒ 380 ms 的余量足够。
 *   （完整日志表在 [PrefsBridge.PROP_MULTISPLIT] 的 KDoc 里。）
 *
 * ⚠️ `onPackageReady` 的入参**没有 Context**（✅ javap 实证：`PackageReadyParam` 只有
 *   `getClassLoader()` 与 `getAppComponentFactory()`）—— 这是本类必须「惰性取值」的
 *   原因，**不是**「必须那一刻读」的原因。读属性其实任何时候都行。
 *
 * 🔴 **代价：改了开关要重启一次系统界面才生效**。`MAX_STAGES` 是 `<clinit>` 里一次
 *   算出来、并且**被同一次 `<clinit>` 的下游直接用掉**的静态字段
 *   （`MultipleSplitStageOrderOperator.<init>` 就按它把 `allStages` 建出来）
 *   ⇒ 运行时热改不了那一次已经建好的东西。
 *   （所以界面上必须把这句话写出来、并指到设置页那个重启入口。）
 *
 * ==================== 为什么需要这个（实测证据） ====================
 * 光把属性 `persist.sys.multiple.split.max_stages` 设成 8 是**不够的**：
 * 属性确实会被 `MultipleSplitOrganizer.<clinit>` 采纳（`resolved_max_stages` 6→8），
 * stage 结构也会真的建出 8 份（`onSplitBoundsChanged` 给 8 个 `Rect`、`index = 0..7`、
 * `stageId` 全集 `STAGE_A…STAGE_H`），**但一进多分屏 SystemUI 就连崩 3 次**：
 *
 * ```
 * java.lang.IllegalStateException: No stage for the given splitIndex
 *   at MultipleSplitStageOrderOperator.getStageBySplitIndex        ← 真凶
 *   at MultipleSplitRootTaskOrganizer$1.getStageHostIdentityForSplitIndex
 *   at MultipleSplitLayout.populateTouchZones
 *   at …MultipleSplitRootTaskOrganizer.lambda$startDockChangeTransitionForMultiple$20
 * ```
 *
 * 把那个方法反汇编出来看，它是 **javac 生成的一张 `switch` 表**：
 * `case` 只写到 **`0..5`**，`default` 直接抛 —— **方法体里 `MAX_STAGES` 一次都没出现**。
 * 而 `populateTouchZones` 的遍历上界是 **stage bounds 的数量**
 * （`IntStream.range(0, mStageBounds.size())`）⇒ `MAX_STAGES > 6` 时必然走到 index 6 ⇒ 必崩。
 * **7 格并不比 8 格安全**（同样走到 index 6）。
 *
 * ==================== 两道闸门，必须**同时**打开 ====================
 * 1. **上限闸门**：`MultipleSplitOrganizer.MAX_STAGES` 必须是 8，否则
 *    `MultipleSplitStageOrderOperator.<init>` 里 `for (i = 0; i < MAX_STAGES; i++) allStages.add(...)`
 *    只会建 6 个 listener ⇒ 就算补了 `case 6/7`，`allStages.get(6)` 也会 `IndexOutOfBounds`。
 * 2. **索引闸门**：`getStageBySplitIndex` 必须能回答 6 / 7，否则就是上面那条崩溃栈。
 *
 * ==================== 为什么走 hook 而不是改系统文件 ====================
 * 目标类住在 `/system_ext/framework/Miui-WindowManager-Shell.jar`，而**改文件这条路本机走不通**：
 * ① `/system_ext` 是 **erofs 只读**分区；② 每个 jar 带 `.fsv_meta`（fs-verity 校验元数据）；
 * ③ ★ 最要命的是 —— SystemUI 真正执行的是 `oat/arm64/` 下的 `.odex` ＋ `.vdex`（**AOT 编译产物**），
 * 光换 jar 里的 dex **不生效**。⇒ 走进程内 hook：**关模块即失效、可逆、不碰任何文件**。
 *
 * ==================== 时序：必须在 `<clinit>` 之前装上 ====================
 * `MAX_STAGES` 是 `<clinit>` 里算出来的静态字段（非编译期常量，引用处全是 `sget`）。
 * ⇒ 本类**只能从 [HyperPlusModule.onPackageReady] 里同步调用**，
 * ⛔ 不能等到 `doBoot`（那条路径**刻意延迟 4 秒**，而 WM Shell 的初始化早得多）。
 * `Class.forName(name, false, loader)` 的 `initialize=false` 是刻意的：**不触发** `<clinit>`。
 *
 * ⚠️ 三个 hook 都只是"**在系统自己的流程里插一脚**"，绝不自建状态、不改行为分支；
 *   任何一个失败都只是"这一路没生效"，不影响宿主（全程 `runCatching` + `PROTECTIVE`）。
 */
object SplitStageLimit {

    private const val TAG = "HyperPlusSplitLimit"

    private const val ORGANIZER =
        "com.android.wm.shell.multiplesplit.MultipleSplitOrganizer"
    private const val ORDER_OP =
        "com.android.wm.shell.multiplesplit.MultipleSplitStageOrderOperator"

    /**
     * 目标格数 —— **真身在 [SplitStageGate.MAX_SUPPORTED]**。
     *
     * ⚠️ 为什么不在这儿直接写 8：本类引用了 libxposed，而那依赖是 `compileOnly`
     *   ⇒ **不在单测类路径上**，判定核放这里就测不了（见 [SplitStageGate] 类注释）。
     * ⚠️ **8 是硬上限**：官方 `MultipleSplitStageOrderOperator.<init>` 里
     * `stageIds = List.of(0,1,2,3,4,5,6,7)` 只预置 8 个 id，再往上 `stageIds.get(8)` 会越界。
     */
    private const val TARGET_STAGES = SplitStageGate.MAX_SUPPORTED

    /** 实验开关在配置镜像里的键（= App 侧的 `PrefsBridge.EXPERIMENTAL_MULTISPLIT`） */
    private const val KEY_ENABLED = PrefsBridge.EXPERIMENTAL_MULTISPLIT

    /** 源码里 `getStageBySplitIndex` 的 `switch` 只写到 `case 5` —— 这就是崩塌的直接原因。 */
    private const val SOURCE_CASE_MAX = 5

    @Volatile private var installed = false

    /** 宿主（SystemUI）的 classloader —— 留给惰性取 Context 那条路用（见 [openResolver]） */
    @Volatile private var hostLoader: ClassLoader? = null

    /** `Settings.System` 的读取口。惰性取一次；拿不到就一直 null ⇒ 按「开关没开」处理 */
    @Volatile private var resolver: ContentResolver? = null

    /** `MultipleSplitStageOrderOperator.allStages`（`Ljava/util/List;`，实为 `ArrayList`） */
    @Volatile private var allStagesField: Field? = null

    /** 索引闸门一次操作会被调用十几次，日志限频用（只记最前面几次，够看就行） */
    private val indexHits = AtomicInteger(0)

    // ================================================================ 安装

    /**
     * 由 [HyperPlusModule.onPackageReady] **同步**调用（⛔ 不要挪进延迟 4 秒的 `doBoot`）。
     *
     * @param module      框架接口
     * @param classLoader **宿主（SystemUI）**的 classloader —— WM Shell 的类就在它上面
     */
    fun install(module: XposedInterface, classLoader: ClassLoader?) {
        if (installed) {
            Log.i(TAG, "已装过，跳过")
            return
        }
        installed = true
        hostLoader = classLoader

        // ⚠️ 顺序有讲究：**先宿主自己的 loader**（最可能命中），再系统 loader 兜底。
        val loaders = buildList {
            classLoader?.let { add(it) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
        }.distinct()

        val banner = "===== 多分屏上限提升开始安装（开关打开时抬到 $TARGET_STAGES 格）====="
        Log.i(TAG, banner)
        BootReport.note(banner)

        val organizer = resolve(ORGANIZER, loaders)
        if (organizer == null) {
            val msg = "❶ 类未找到：$ORGANIZER ⇒ 上限无法提升（ROM 升级后包名可能变）"
            Log.w(TAG, msg)
            BootReport.note(msg)
        } else {
            // ★ 这两条是**同一道闸门**的两种开法，都要装：
            //   ① 改 `<clinit>` 算完之后的字段值 —— 不依赖"方法有没有被内联"，最稳；
            //   ② 拦 `resolveMaxStages` 让它直接算成 8 —— 更干净，且能打出原值便于取证据。
            runCatching { hookClinit(module, organizer) }
                .onFailure { Log.w(TAG, "❶ 挂 <clinit> 失败（已吞掉）", it) }
            runCatching { hookResolveMaxStages(module, organizer) }
                .onFailure { Log.w(TAG, "❶ 挂 resolveMaxStages 失败（已吞掉）", it) }
        }

        val orderOp = resolve(ORDER_OP, loaders)
        if (orderOp == null) {
            val msg = "❷ 类未找到：$ORDER_OP ⇒ 索引闸门无法补（7/8 格仍会崩）"
            Log.w(TAG, msg)
            BootReport.note(msg)
        } else {
            runCatching { hookGetStageBySplitIndex(module, orderOp) }
                .onFailure { Log.w(TAG, "❷ 挂 getStageBySplitIndex 失败（已吞掉）", it) }
        }

        Log.i(TAG, "===== 多分屏上限提升安装完成 =====")
        BootReport.note("多分屏上限提升安装完成（钩子已挂）")
    }

    private fun resolve(name: String, loaders: List<ClassLoader>): Class<*>? =
        loaders.firstNotNullOfOrNull { l ->
            runCatching { Class.forName(name, false, l) }.getOrNull()
        }

    // ================================================================ 闸门 ①：上限

    /**
     * 挂在 `MultipleSplitOrganizer` 的**类初始化器**上，等 `<clinit>` 跑完再兜底改字段。
     *
     * ★ 为什么要有这一条：`resolveMaxStages` 只有 21 个字节码单元，而本类所在的
     *   `Miui-WindowManager-Shell.odex` 是**已 AOT 编译**的产物
     *   ⇒ 存在"被内联进 `<clinit>`、于是方法 hook 拦不到"的可能。
     *   改字段不关心这条路，只要赶在其他类读 `MAX_STAGES` 之前就行。
     *
     * ⚠️ `hookClassInitializer` 要求类**尚未初始化**。`onPackageReady` 阶段装得上，
     *   跑到 `doBoot` 就晚了 —— 这也是本类必须"同步早装"的原因之一。
     */
    private fun hookClinit(module: XposedInterface, cls: Class<*>) {
        module.hookClassInitializer(cls)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching { forceMaxStages(cls) }
                        .onFailure { Log.w(TAG, "❶ 改 MAX_STAGES 字段失败（已吞掉）", it) }
                    return r
                }
            })
        val hung = "❶ 已挂 ${cls.simpleName}.<clinit>（算完即改写 MAX_STAGES）"
        Log.i(TAG, hung)
        BootReport.note(hung)
    }

    /**
     * 拦 `static int resolveMaxStages(int ramGb, String prop)`。
     * ⚠️ 它是 `direct`（静态）方法，只有 21 个单元；`<clinit>` 里唯一的调用点就是把结果
     *   赋给 `MAX_STAGES`。原逻辑是 `Math.max(3, 属性没设 ? getDefaultMaxStages(ramGb) : parse(属性))`
     *   —— **只有下限、没有上限 clamp**，所以我们这里直接给出 8 即可，不需要模仿它。
     */
    private fun hookResolveMaxStages(module: XposedInterface, cls: Class<*>) {
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "resolveMaxStages" || m.parameterTypes.size != 2) continue
            val ok = runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val orig = runCatching { chain.proceed() }.getOrNull()
                            val target = resolveTarget()
                            if (target == null) {
                                // 开关关着 / 读不到配置 ⇒ **原样放行**。
                                // ⚠️ 这里返回 orig 而不是 null：`proceed()` 已经把真值算出来了，
                                //   返回它才等价于「我们什么也没做」。
                                // ⚠️ 日志**必须先判空再打**：否则会先打出一行
                                //   "⇒ 改为 null"，再打一行"不干预"，读日志的人要愣一下。
                                val msg = "⭐⭐ 上限闸门被拦到：原值=$orig ⇒ 不干预（保持系统默认）"
                                Log.i(TAG, msg)
                                BootReport.note(msg)
                                return orig
                            }
                            val msg =
                                "⭐⭐ 上限闸门被拦到：原值=$orig（内存分档/属性算出来的）⇒ 改为 $target"
                            Log.i(TAG, msg)
                            BootReport.note(msg)
                            return target
                        }
                    })
            }.isSuccess
            if (ok) n++
        }
        if (n == 0) {
            Log.w(TAG, "❶ 未钩上 resolveMaxStages ⇒ 只能靠 <clinit> 那条兜底")
        } else {
            Log.i(TAG, "❶ resolveMaxStages 已钩上（$n 个重载）")
        }
    }

    /**
     * 把静态字段 `MAX_STAGES` 改成**开关要求的那个值**。
     *
     * ★ 可行性依据：`MAX_STAGES` **不是编译期常量**（值来自 `resolveMaxStages(...)` 调用），
     *   所以任何引用处都是 `sget` 现读字段 ⇒ 改字段有效。
     *
     * ⚠️ **开关没开（或读不到配置）⇒ 立刻返回，一个字节都不改**。这是本功能的红线：
     *   用户没同意就不能改系统行为，而"读不到"与"没开"必须同样处理（见 [SplitStageGate]）。
     * ⚠️ 只在"比目标小"时才改：万一 ROM 自己的档位比 8 还高（stageIds 只有 8 个，理论上
     *   不会），也不要把它压下来 —— 那属于别人的既定行为。
     */
    private fun forceMaxStages(cls: Class<*>) {
        val target = resolveTarget() ?: return
        val f = runCatching {
            cls.getDeclaredField("MAX_STAGES").apply { isAccessible = true }
        }.getOrNull() ?: run {
            Log.w(TAG, "❶ 找不到字段 MAX_STAGES（改名了？）")
            return
        }
        val cur = runCatching { f.getInt(null) }.getOrNull() ?: return
        if (cur >= target) {
            Log.i(TAG, "❶ MAX_STAGES 已是 $cur（≥ $target），不用改")
            BootReport.note("❶ MAX_STAGES 已是 $cur（≥ $target），不用改")
            return
        }
        f.setInt(null, target)
        val rewritten = "⭐⭐ MAX_STAGES 已改写：$cur → $target（字段直改，不依赖方法 hook）"
        Log.i(TAG, rewritten)
        BootReport.note(rewritten)
    }

    /**
     * 解析「本次要不要抬、抬到几」—— **属性优先，镜像兜底**。**每次调用都现读**。
     *
     * ★ 不做缓存是刻意的：这条路径在一次进程生命周期里只走一两次
     *   （`<clinit>` 与 `resolveMaxStages` 各一次），而缓存会把
     *   「两次读取之间配置变了」这种情形弄得难解释 —— 收益为零、成本是复杂度。
     *
     * ★★ 属性一旦有明确取值，就**不去读镜像**（给 [SplitStageGate.targetStages] 传 `null`）：
     *   既省一次 Binder，也避免在宿主启动早期连打几十行「配置镜像还不存在」的 warning
     *   （实测那一轮打了 **51 行**，把真正的信号淹了）。
     *   优先级规则本身在 [SplitStageGate.targetStages] 里，这里只负责「取原料 + 记日志」。
     */
    private fun resolveTarget(): Int? {
        val prop = readProp()
        // 属性没给出明确取值（null / 空 / 不认识）⇒ 才值得去读镜像那条兜底路
        val needMirror = prop != "1" && prop != "0"
        val mirror = if (needMirror) configMirror() else null
        val raw = mirror?.get(KEY_ENABLED)
        val t = SplitStageGate.targetStages(prop, mirror)

        // 日志把「值是从哪条通道来的」写清楚 —— 排查时这一条最省事
        val src = if (needMirror) "属性=未写｜镜像里的开关=$raw" else "属性=$prop"
        if (t == null) {
            Log.i(TAG, "🔘 开关（$src）⇒ **不干预**（系统默认上限原样保留）")
            BootReport.note("🔘 开关（$src）⇒ 不干预（系统默认上限原样保留）")
        } else {
            Log.i(TAG, "🔘 开关（$src）⇒ 目标上限 $t 格")
            BootReport.note("🔘 开关（$src）⇒ 目标上限 $t 格")
        }
        return t
    }

    /**
     * 读 `persist.*` 属性（本工程只用 [PrefsBridge.PROP_MULTISPLIT]）。
     *
     * ★ 为什么**手写反射** `android.os.SystemProperties`：它是 `@hide` 类，
     *   走 `compileOnly` 的 android.jar 编译不出来；而本工程对隐藏 API 的既定手法
     *   就是自己写反射（见 `HyperPlusModule.hostApplication` 那段长注释）。
     *   ⚠️ 注入进程里隐藏 API 限制本就已解除，这套是跑得通的。
     *
     * ★ 用 `get(String, String)` 这个**带默认值**的重载：键不存在时它返回默认值
     *   而不是抛异常 ⇒ 「键不存在」与「读到空串」能用同一个判据处理
     *   （见 [SplitStageGate.targetStages] 的优先级表）。
     *
     * ⚠️ 全程 `runCatching` + 失败返回 `null`：这条路径跑在宿主的**类初始化**里，
     *   一次抛异常就可能把宿主某个类的初始化搞坏 ⇒ 宁可当作「没读到」
     *   （= 回落镜像那条老路，而**不是**当作「关」）。
     */
    private fun readProp(): String? = runCatching {
        val loader = hostLoader ?: ClassLoader.getSystemClassLoader()
        val cls = Class.forName("android.os.SystemProperties", false, loader)
        cls.getDeclaredMethod("get", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(null, PrefsBridge.PROP_MULTISPLIT, "") as? String
    }.getOrNull()

    /**
     * 从 `Settings.System` 把**配置镜像**读出来并解码。**读不到一律返回 `null`。**
     *
     * ★ 键与编解码都**复用既有那条链**：[PrefsBridge.MIRROR] 装的是 App prefs 的
     *   全量编码快照，`ConfigChannel.decode` 是纯 Kotlin ⇒ 这条兜底路径**零新增格式**。
     *
     * ⚠️ **2026-10-06 更正**：此处原来写着「⛔ 不许自建任何给模块专用的小通道」——
     *   那句话**已被推翻，但只在一种情形下**：新通道必须是**进程启动即可读**的
     *   （[PrefsBridge.PROP_MULTISPLIT] 的 `persist.*` 属性），因为 `Settings.System`
     *   在宿主起来的头几秒根本给不出值（实测晚 **4.2 秒**，见 [resolveTarget]）。
     *   ⇒ 除此之外**仍然不许**再自建通道：多一条通道，就多一处会和真实配置不同步的地方。
     *
     * ⚠️ 全程 `runCatching`：这条路径跑在宿主（SystemUI）的**类初始化**里，
     *   一次抛异常就可能把宿主某个类的初始化搞坏 ⇒ **宁可返回 null**（= 不干预）。
     */
    private fun configMirror(): Map<String, Any?>? = runCatching {
        val cr = resolver ?: openResolver()
        if (cr == null) {
            Log.w(TAG, "拿不到 ContentResolver ⇒ 读不到配置（按「开关没开」处理）")
            null
        } else {
            resolver = cr
            val raw = PrefsBridge.readString(cr, PrefsBridge.MIRROR)
            if (raw.isNullOrEmpty()) {
                Log.w(TAG, "配置镜像还不存在（App 没推过 / 首次开机）⇒ 按「开关没开」处理")
                null
            } else {
                ConfigChannel.decode(raw)
            }
        }
    }.getOrNull()

    /**
     * 反射 `ActivityThread` 取 **system context** 的 `ContentResolver`。
     *
     * ★ 手法与 `HyperPlusModule.hostApplication()` **同源**（那里取 Application、
     *   这里取 system context 上的 resolver），所以这条路在本机是走通过的。
     * ⚠️ `getSystemContext()` 由 `handleBindApplication` 建立、**早于** Application 创建；
     *   而 WM Shell 的类初始化远在那之后 ⇒ 时点上够用。
     * ⚠️ 成功一次就缓存（[resolver]）；**失败的也缓存**（= 留 null），不反复重试 ——
     *   这条路径在同一个进程里只会走一两次，重试没有意义，只会在日志里刷屏。
     */
    private fun openResolver(): ContentResolver? = runCatching {
        val loader = hostLoader ?: ClassLoader.getSystemClassLoader()
        val atCls = Class.forName("android.app.ActivityThread", false, loader)
        val at = atCls.getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return@runCatching null
        val ctx = atCls.getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(at) as? Context ?: return@runCatching null
        ctx.contentResolver
    }.onFailure { Log.w(TAG, "取 system context 失败（已吞掉）", it) }.getOrNull()


    // ================================================================ 闸门 ②：索引

    /**
     * 挂在 `MultipleSplitStageOrderOperator.getStageBySplitIndex(int)` 上。
     *
     * ★ 原实现（反汇编逐字）：先 `allStages.get(splitIndex)`，然后一张
     *   `case 0..5 → return allStages.get(N)` 的 switch 表，`else` 抛
     *   `IllegalStateException("No stage for the given splitIndex")`。
     * ⇒ 我们只接管 `index > 5` 的部分：**按 index 直接从 `allStages` 取**，
     *   `0..5` 一律放行走原逻辑（**行为零改动**）。
     *
     * ⚠️ 必须在**前置**接管：原实现的 `default` 分支是**直接 `throw`**，
     *   等到 after 阶段已经来不及（异常都出去了）。
     */
    private fun hookGetStageBySplitIndex(module: XposedInterface, cls: Class<*>) {
        val intType = Int::class.javaPrimitiveType
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "getStageBySplitIndex") continue
            if (m.parameterTypes.size != 1 || m.parameterTypes[0] != intType) continue
            val ok = runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(IndexHooker)
            }.isSuccess
            if (ok) n++
        }
        if (n == 0) {
            Log.w(TAG, "❷ 未钩上 getStageBySplitIndex ⇒ 7/8 格仍会崩")
        } else {
            Log.i(TAG, "❷ getStageBySplitIndex 已钩上（$n 个）⇒ index > $SOURCE_CASE_MAX 时自行按索引取")
        }
    }

    private object IndexHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val patched = runCatching { patchStageByIndex(chain) }.getOrNull()
            return patched ?: chain.proceed()
        }
    }

    /**
     * `index > [SOURCE_CASE_MAX]` 时按索引取 stage；返回 `null` 表示"这一趟不接管"。
     *
     * ⚠️ 返回 `null` 时由调用方走 `chain.proceed()` —— 这里**绝不自己调 `proceed()`**，
     *   因为 `Chain.proceed()` 一趟只能调一次。
     * ⚠️ 取不到（`allStages` 只有 6 个）时也返回 `null`：那是"闸门 ① 没生效"的情形，
     *   放行原逻辑让它按原样抛，比我们编一个假对象出来安全得多。
     */
    private fun patchStageByIndex(chain: XposedInterface.Chain): Any? {
        val idx = runCatching { chain.args.getOrNull(0) as? Int }.getOrNull() ?: return null
        if (idx <= SOURCE_CASE_MAX) return null          // 0..5 原样走系统逻辑
        val self = runCatching { chain.thisObject }.getOrNull() ?: return null

        val f = allStagesField ?: runCatching {
            self.javaClass.getDeclaredField("allStages").apply { isAccessible = true }
        }.getOrNull() ?: return null
        allStagesField = f

        val list = runCatching { f.get(self) as? List<*> }.getOrNull() ?: return null
        val n = list.size
        if (idx >= n) {
            // 闸门 ① 没生效（MAX_STAGES 还是 6）⇒ 这里无解，放行让系统按原样处理
            if (indexHits.getAndIncrement() < 3) {
                Log.w(TAG, "❷ index=$idx 超出 allStages 实际大小($n) ⇒ 上限没抬起来？放行原逻辑")
            }
            return null
        }
        if (indexHits.getAndIncrement() < 6) {
            Log.i(TAG, "⭐⭐ 索引闸门被拦到：index=$idx ⇒ 取 allStages[$idx]（size=$n）")
        }
        return list[idx]
    }
}

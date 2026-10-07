package cn.dsr213.hyperplus.module

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * ★★★ **分屏观察钩子**（2026-10-04 新增）—— 本模块**第一个方法 hook**。
 *
 * ============================ 它要回答的问题 ============================
 * `docs/分屏增强_实现方案_2026-10-04.md` §5.1 把功能 1 定成「**只做触发器**：
 * 调系统原生那条「新增分屏」链，其余（回桌面 / 选图标 / 填槽）全交回系统」。
 * 但**「那条链」到底是哪个方法，离线查不出来** —— 候选有一大把：
 *
 * ```
 * SoScSplitScreenController.enterSplitScreen(I, Z)      ← 进分屏
 * SoScSplitScreenController.onGestureStart(I)           ← 手势起点
 * SoScSplitScreenController.onDroppedToSplit(I, …)      ← 拖到图标
 * SoScStageCoordinator.addSplitPair(I, I)               ← 成对加入
 * SoScStageCoordinator.enterSoScForDeviceUnfoldedIfNeed() ← 「展开时」进 SoSc
 * SoScSplitScreenController.startIntentInSoSc(…)        ← 带 intent 起一格
 * SoScSplitScreenController.startIntentInSoScFor3rd(…)  ← 名字带 3rd
 * MultipleSplitController / MultipleSplitOrganizer …    ← 多分屏那套
 * ```
 *
 * ⇒ 本探针**把它们全部 hook 上、只打日志**（不改行为、不吞异常、不改参数），
 *   然后让**你按原生手势**做一遍，看日志里**真的出现了哪一条**。
 *   这是把"猜该调哪个方法"换成"看系统自己走哪条链"的唯一办法 ——
 *   与铁律「**因果判定不能单向匹配**」「**自写探针会撒谎**」同源：先观测，再复刻。
 *
 * ============================ 它还顺带钉住另一件事 ============================
 * 「**方向**」（轻折后当前应用留左还是留右，见 `AppPrefs.SplitUnfoldDirection`）
 * 目前只有"两条手势分支送进去的值不同"这一条实证，**"哪个值 = 左边"证不出来**。
 * 探针会把每次调用的**实参值**原样打出来 ⇒ 对着"我这次是往左滑的"一读，映射关系就定了。
 *
 * ============================ 安全设计（逐条，都不是可选） ============================
 * ① **默认关闭**：要 `Settings.Global.putInt(... "hyperplus_split_probe", 1)` 才装
 *    （与 `HyperPlusModule` 的 `hyperplus_selfcheck` 同一套路）。它是一次性的调查工具，
 *    ⛔ 不许默认打开 —— 常驻 hook 一个 156 方法的类，是拿日常稳定性换排查便利。
 * ② **只读**：hooker 里**只**读 `chain.executable` / `chain.args` / 线程栈，
 *    然后 `chain.proceed()` 原样放行。⛔ 不return 假值、⛔ 不改 args、⛔ 不吞异常。
 * ③ **PROTECTIVE 异常模式**：万一探针自己抛了（例如 `args` 读失败），
 *    框架会吞掉它并**当作没有这个 hook**继续跑 —— 宿主永远不会因为探针崩。
 *    这比"靠我们自己 try/catch"多一层保障（我们那层也留着，两道都要）。
 * ④ **永不抛到调用方**：`runCatching` 包住整段日志逻辑。日志失败不是故障。
 * ⑤ **所有类都用 `Class.forName(name, false, loader)`**（`initialize = false`）——
 *    不跑目标类的 `<clinit>`，避免"装个探针把某个还没初始化的组件拽起来"。
 * ⑥ **多 classloader 兜底**：WMShell 的类**与引擎同进程**，但未必在同一个 loader 里
 *    （可能是 SystemUI 的 PathClassLoader，也可能是框架级共享 jar）。三个 loader 依次试，
 *    每个类在日志里写明**从哪个 loader 找到的** —— 这一条本身就是给将来正式实现用的情报。
 */
internal object SplitProbe {

    /**
     * ⚠️ TAG 刻意**短且带方括号**：探针的日志要与引擎日常日志一眼分开，
     *   而 `adb logcat | grep HyperPlusSplitProbe` 是它的唯一用法。
     */
    private const val TAG = "HyperPlusSplitProbe"

    /**
     * 开关（`Settings.Global`，**默认关**）。
     *
     * 用法：
     * ```
     * adb shell settings put global hyperplus_split_probe 1
     * adb shell su -c "killall com.android.systemui"     # 软重启，让探针装上
     * adb logcat -s HyperPlusSplitProbe                  # 然后按手势看输出
     * adb shell settings put global hyperplus_split_probe 0   # 查完记得关
     * ```
     * ⚠️ 用 `Settings.Global` 而不是 `Settings.System`：它是**开发开关**、不是用户配置，
     *   不该混进配置镜像那条通道（那条通道里的每一个键都会被推到引擎并落盘）。
     */
    const val KEY = "hyperplus_split_probe"

    /**
     * 要 hook 的类。**顺序 = 从"最可能就是那个落点"到"兜底"**，日志里也按这个顺序装。
     *
     * ⚠️ 全是**小米私有实现**（`com.android.wm.shell.*` 下由 HyperOS 加的那一层）——
     *   目标系统升级后这些类可能整个搬走或者改名。装不上**不是故障**，只是探针失效，
     *   日志里会把"找不到的类"逐条列出来（那本身就是有用的情报）。
     */
    private val TARGETS = listOf(
        // —— SoSc（2 分屏）那套 ——
        "com.android.wm.shell.sosc.SoScSplitScreenController",
        "com.android.wm.shell.sosc.SoScStageCoordinator",
        "com.android.wm.shell.sosc.SoScStageTaskListener",
        // —— 多分屏（3~6）那套 ——
        "com.android.wm.shell.multiplesplit.MultipleSplitController",
        "com.android.wm.shell.multiplesplit.MultipleSplitOrganizer",
        // —— AppFunction 层（`wm shell` 那批命令的处理器就在这儿）——
        "com.android.wm.shell.appfunction.SplitScreenFunctionHandler",
        "com.android.wm.shell.appfunction.MultiSplitFunctionHandler",
        // —— ITaskOrganizer 的实现（下行命令的接收侧）——
        "com.android.wm.shell.ShellTaskOrganizer\$MiuiShellTaskListener",
    )

    /**
     * 方法名里**出现**这些词才 hook。
     *
     * ★ 判据：全部是**动作**词（起 / 加 / 进 / 出 / 换 / 准备 / 判定）。
     *   ⛔ 刻意**不含** `get` / `is` / `has`：那类查询在一次操作里会被调几十次
     *   （`isSplitScreenVisible`、`getTaskIdByPosition`…），把它们记下来只会把
     *   真正的调用链淹掉。**我们要看的是"谁动了手"，不是"谁看了一眼"。**
     */
    private val KEYWORDS = listOf(
        "start", "add", "insert", "enter", "exit", "remove", "replace",
        "prepare", "determine", "toggle", "drop", "gesture", "launch",
        "set", "clear", "activate", "fillin", "refill", "newstage", "splitprimary",
    )

    /** 调用栈里只保留这些前缀的帧 —— 其余（`java.*` / `android.os.Handler` 之类）是噪音 */
    private val STACK_KEEP = listOf(
        "com.android.wm.shell",
        "com.android.systemui",
        "com.miui",
        "com.android.server",
        "cn.dsr213",
    )

    // ================================================================ 安装

    /**
     * 装探针。**由 [HyperPlusModule.doBoot] 调**，且只在开关打开时才会真的动。
     *
     * @param module      框架接口（`XposedModule` 就是它）—— `hook(Executable)` 来自这里
     * @param hostCtx     宿主（SystemUI）的 Context，只用来读那个开关
     * @param classLoader **宿主**的 classloader（`PackageReadyParam.classLoader`）
     */
    fun install(module: XposedInterface, hostCtx: Context, classLoader: ClassLoader?) {
        if (!isOn(hostCtx)) {
            Log.i(TAG, "分屏探针未开启（要开：adb shell settings put global $KEY 1 然后重启系统界面）")
            return
        }

        // ★ 三个 loader 依次试。⚠️ 顺序有讲究：**先宿主自己的**（最可能命中），
        //   再系统 loader，最后我们模块自己的（LSPosed 给模块单独的 loader，
        //   它能看到的往往是宿主 loader 的父级 —— 兜底用）。
        val loaders = buildList {
            classLoader?.let { add(it) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
            runCatching { SplitProbe::class.java.classLoader?.let { add(it) } }
        }.distinct()

        Log.i(TAG, "===== 分屏探针开始安装（已开启）=====")
        var total = 0
        for (name in TARGETS) {
            val hit = loaders.firstNotNullOfOrNull { l ->
                runCatching { Class.forName(name, false, l) to l }.getOrNull()
            }
            if (hit == null) {
                Log.w(TAG, "❶ 类未找到：$name（可能改包名了 / 该功能不在本机型）")
                continue
            }
            val (cls, loader) = hit
            val n = runCatching { hookClass(module, cls) }
                .onFailure { Log.w(TAG, "处理类失败：$name", it) }
                .getOrDefault(0)
            total += n
            Log.i(TAG, "❷ ${cls.simpleName}：钩上 $n 个（loader=${loader.javaClass.simpleName}）")
        }
        Log.i(TAG, "===== 分屏探针安装完成：共 $total 个方法 =====")
        Log.i(TAG, "现在请按原生手势操作（三指左滑 / 右滑 · 上滑进最近任务后拖到左上角图标），观察上面的输出")
    }

    /** hook 一个类里"看起来会动手"的所有方法与全部构造器，返回成功条数 */
    private fun hookClass(module: XposedInterface, cls: Class<*>): Int {
        var n = 0
        for (m in cls.declaredMethods) {
            // 抽象 / 本地方法挂不上（没有字节码体），跳过而不是让它报错刷屏
            if (Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
            if (!interesting(m.name)) continue
            val ok = runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(newHooker())
            }.isSuccess
            if (ok) n++ else Log.w(TAG, "   ✗ 钩不上方法 ${cls.simpleName}.${m.name}")
        }
        // ★ 构造器也全钩：一是能**捕获到实例**（正式实现要靠它拿 WMShell 对象，
        //   见方案 §4 方案 A），二是"构造发生在什么时候"本身就是有用的事件
        //   （例如用户展开手机那一刻某个 Controller 才被造出来）。
        for (c in cls.declaredConstructors) {
            val ok = runCatching {
                module.hook(c)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(newHooker())
            }.isSuccess
            if (ok) n++ else Log.w(TAG, "   ✗ 钩不上构造器 ${cls.simpleName}")
        }
        return n
    }

    private fun newHooker() = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // ④ 探针自己绝不许影响宿主：日志那一段整包 runCatching。
            //    ⚠️ `proceed()` 必须在**任何时候**都被调到 —— 所以它在 try 之外。
            runCatching { report(chain) }
            return chain.proceed()
        }
    }

    // ================================================================ 上报

    private fun report(chain: XposedInterface.Chain) {
        val ex = chain.executable
        val cls = ex.declaringClass.simpleName
        val args = runCatching { chain.args }.getOrDefault(emptyList())
        Log.i(TAG, "▶ $cls.${ex.name}(${args.map(::fmt).joinToString(", ")})")
        // 调用栈单独一行：与被调用的那一行分开，方便 `grep '↳'` 只捞链路
        val stack = callStack()
        if (stack.isNotEmpty()) Log.i(TAG, "    ↳ $stack")
    }

    /**
     * 精简调用栈：只留我们自己关心的那几个包，最多 6 帧。
     *
     * ★ 为什么这事儿值得做：光看"哪个方法被调了"**分不清**是系统自己走的、
     *   还是我们（或别的模块）调进去的。栈里出现 `cn.dsr213` 就一定是我们起的头；
     *   出现 `com.android.server` 说明上游在 system_server。
     *   ⚠️ 跳过 `SplitProbe` 自己的帧（否则每一行开头都是探针自己，白占位置）。
     */
    private fun callStack(): String = runCatching {
        Thread.currentThread().stackTrace
            .asSequence()
            .drop(1)
            .filter { f -> STACK_KEEP.any { f.className.startsWith(it) } }
            .filterNot { it.className.startsWith("cn.dsr213.hyperplus.module.SplitProbe") }
            .take(6)
            .joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName }
    }.getOrDefault("")

    /**
     * 实参的人话形式。
     *
     * ★ 关键要求：**数值必须原样打出来**（`int` 就是 `1` / `0` / `3`，不许用 `toString` 之外的手法），
     *   因为我们正是靠这几个数字去定"左 = 几"（见类注释）。
     * ⚠️ 大对象只打**类型 + 身份**，不打内容：`Intent` / `PendingIntent` 的 `toString()`
     *   能有好几百字符，一条日志就能把 logcat 缓冲打满，而且它里面没有一个字是我们需要的。
     */
    private fun fmt(a: Any?): String = when (a) {
        null -> "null"
        is Int, is Long, is Short, is Byte, is Boolean, is Float, is Double -> a.toString()
        is String -> "「${a.take(48)}」"
        is Intent -> "Intent(${a.component?.flattenToShortString() ?: a.action ?: "?"})"
        is PendingIntent -> "PendingIntent"
        is Array<*> -> "Array[${a.size}]"
        is IntArray -> "int[${a.size}]"
        is LongArray -> "long[${a.size}]"
        else -> "${a.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(a))}"
    }

    // ================================================================ 开关

    private fun isOn(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY, 0) != 0
    }.getOrDefault(false)

    /**
     * 方法名要不要 hook。
     *
     * ⚠️ 两条"看起来像误伤、其实是有意保留"的判据：
     *  - `set*` 收进来（`setSideStagePosition` / `setSplitRatio`）—— 它们是**低频率**的
     *    状态设置，而"系统在什么时候把位置设成了几"正是我们要的方向证据；
     *  - 含 `Transition` 的排除掉（`addDimLayerToTransition` 之类）—— 那是**转场动画**
     *    的内部步骤，一次操作里会调一串，且与"哪条链进了分屏"无关。
     */
    private fun interesting(name: String): Boolean {
        if (name == "<init>") return true
        val n = name.lowercase()
        if (n.startsWith("get") || n.startsWith("is") || n.startsWith("has") || n.startsWith("dump")) return false
        if (n.contains("transition")) return false
        return KEYWORDS.any { n.contains(it) }
    }
}

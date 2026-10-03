package cn.dsr213.hyperplus.module

import android.content.SharedPreferences
import android.util.Log
import cn.dsr213.hyperplus.PrefsBridge
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 引擎侧（SystemUI 进程）读取 App 配置的**唯一入口** —— 零 root 的配置通道。
 *
 * ============================ 为什么要它 ============================
 * 用户 2026-09-28 拍板：「我不要 App 那套引擎，只留注入 SystemUI 的；App 也不该再依赖 root」。
 *
 * 旧通道是 `Settings.System` 自定义键，而**往非公开键里写**只有三种身份能做
 * （SYSTEM/SHELL/ROOT uid、公开键白名单、`PRIVATE_FLAG_PRIVILEGED` 特权包）。
 * 引擎跑在 SystemUI（特权包）⇒ 它写没问题；但 App 是普通应用 ⇒ 只能借 root 代写。
 * 那曾是整条链路里**唯一**需要 root 的地方（旧类 `RootBridge` 的**全部**用途，
 * 该类已随本次改造删除，git 历史里还能看到）。
 *
 * 换成 LSPosed 官方的 [XSharedPreferences] 之后，配置**下行**（App → 引擎）改走文件，
 * App 只需写自己的 prefs（零权限），root 依赖就此消失。
 *
 * ============================ 机制（LSPosed 官方 Wiki 实证） ============================
 * ⚠️⚠️ **2026-10-03 状态变更 —— 这条通道有到期日**：本类用的 LSPosed「New XSharedPreferences」
 *   **已被官方标记为废弃**，计划在 **LSPosed 2.3.0 移除**；v2.2.0 起模块页会对本模块弹
 *   「此模块使用了已废弃且即将移除的功能」。届时 `getPreferencesDir()` 不再重定向到 safe-zone、
 *   `checkMode` 不再放行 `MODE_WORLD_READABLE` ⇒ **配置下行（App → 引擎）会断**，
 *   且**失败是静默的**（读不到不抛异常，现象只是"设置点了没反应"）。
 *   ⇒ 动这个类之前先读 `docs/配置通道_nsp废弃警告_2026-10-03.md`（证据链 + 两条出路对比）。
 *   ❓ 那条"退回旧式"的路（`xposedminversion=82` + 删 `xposedsharedprefs`）**尚未验证**。
 *
 * 前提：模块的 `xposedminversion >= 93`（或声明 `xposedsharedprefs` 元数据）——
 * 本工程 Manifest 里**本来就是 93**，等于零成本满足。
 *
 * 满足后 LSPosed 会做两件事（**只作用于模块自己的进程**）：
 *   ① hook `ContextImpl.checkMode(int)`：于是 App 侧可以传 `MODE_WORLD_READABLE`
 *      而**不抛** `SecurityException`（Android 7+ 本来必抛）；
 *   ② hook `ContextImpl.getPreferencesDir()`：把 prefs 目录**重定向**到 daemon 准备的
 *      safe-zone，并给它一个 SELinux 全局可读的上下文 + 755 权限。
 *
 * ⇒ 净效果：App 用 [SharedPreferences] 正常写，引擎用 [XSharedPreferences] 正常读，
 *   中间不需要 root，也不需要把文件权限改成 world-writable 那种脏手段。
 *
 * ⚠️ **两条硬约束**（官方 Wiki 明写，别踩）：
 *   1. App 侧必须用 `MODE_WORLD_READABLE` 打开 prefs —— LSPosed **只在**这个模式下
 *      才帮你把文件弄成可读的。用 `MODE_PRIVATE` 打开的话，文件权限照旧，引擎读不到。
 *   2. **不能用 `XSharedPreferences(File)` 构造** —— 因为 safe-zone 是**随机目录**，
 *      路径不能猜。必须用 `XSharedPreferences(packageName, prefFileName)` 让框架自己找。
 *
 * ============================ 实时跟随：两条腿走路（2026-09-28 补） ============================
 * 官方 Wiki「Preference change listener in a hooked process」支持在**被注入的进程里**
 * 注册变更监听，框架用 `WatchService`/inotify 监控那个物理文件，一变就回调。
 * 本类照做了（[installWatcher]），但**不能只靠它** —— 源码级的理由：
 *
 *   1. 框架侧 `XSharedPreferences.tryRegisterWatcher()` 是对**文件所在目录**
 *      `path.getParent().register(sWatcher, …)` 注册 inotify —— 这要求对该目录有**读**权限；
 *   2. 而 daemon 给 safe-zone 的权限**恰恰只有执行位**：
 *      `ConfigManager.getPrefsPath()` 用 `PosixFilePermissions.fromString("rwx--x--x")`（711）
 *      建目录，并把目录 chown 给模块 App 的 uid。被注入的进程（SystemUI）既不是 owner
 *      也不在组里 ⇒ 只剩 `x`，**没有 `r`**；
 *   3. 框架对这个失败是**静默**的：`catch (AccessDeniedException) { if (DEBUG) Log.e(...) }` ——
 *      没有 watcher、也没有任何提示，只是"再也不会回调"。
 *
 *   ⇒ 结论（🔎 推断，依据是上面两处源码，未在真机验证）：**inotify 这条路很可能注册不上**。
 *   而它失败的样子是"配置改了但引擎不动"，与"配置压根没写进去"在界面上**无法区分**
 *   —— 所以设计上不赌它：
 *
 *   **[installPollFallback] 每 [POLL_MS] 自己比对一次文件内容**，与 watcher 那条腿并行。
 *   watcher 若真能通知，会更及时；通知不了，轮询兜住。重复通知无害 ——
 *   订阅者都是幂等的全量刷新（见 [subscribe] 与 `EngineHost.onConfigChanged` 的 token 比对）。
 *
 * ⚠️ **2026-09-28 真机实测（把上面那半句"不赌它"从推断变成了事实）**：
 *   两条腿**原来都是死的**，配置跟随实际不生效。证据链：
 *     - 框架的 `XSharedPreferences.hasFileChanged()` 在真机上**恒返回 false**
 *       （轮询线程活着、文件 mtime 确实变了、同进程 `p.file.canRead()` = true，
 *       却始终没有"发现变化"日志）⇒ 轮询这条腿空转；
 *     - watcher 那条腿按上面的推断本就注册不上（失败还静默）。
 *   ⇒ 现在轮询**不再调任何框架辅助方法**，改成 `reload()` 后比对 `all` 的内容快照
 *     （见 [installPollFallback] 的注释）。判据从"框架说文件变了吗"变成
 *     "我读出来的内容变了吗" —— 后者只依赖一件已被证实成立的事：文件读得出来。
 *     顺带还能说出**是哪个键变了**（原来是"回调里 key 恒为 null"的痛点）。
 *
 * ⚠️ **设计使然的坑（官方原话）**：watcher 回调里**拿不到是哪个键变了**（key 恒为 null）。
 *   所以这里不试图做增量更新 —— 收到通知就 [XSharedPreferences.reload] **全量重读**，
 *   再由订阅者整体比对。配置项一共不到十个，全量重读的代价可以忽略。
 */
internal object ModulePrefs {

    private const val TAG = "HyperPlusModulePrefs"

    /**
     * 配置真身的位置。
     *
     * ⚠️ [PREFS_NAME] 必须与 `AppPrefs.NAME` 一字不差 —— 两边读的是同一个文件，
     *   改一边忘了另一边就是"配置怎么改都不生效"。
     */
    const val APP_PKG = "cn.dsr213.hyperplus"
    const val PREFS_NAME = "facerotate_prefs"

    /**
     * 兜底轮询周期（见类注释的「实时跟随：两条腿走路」）。
     *
     * 取 2 秒的依据：这是**用户点开关**的链路，不是逐帧链路 —— 2 秒在体感上就是"立刻生效"。
     * 单次代价 = 一次 `reload()`（615 字节左右的小文件）+ 一次内容比对，
     * 对 SystemUI 可以忽略；而它换来的是"不依赖 inotify 与 `hasFileChanged()` 是否可用"
     * —— 后者 2026-09-28 已被真机证明是**恒 false**（见 [installPollFallback]）。
     */
    private const val POLL_MS = 2_000L

    @Volatile private var xs: XSharedPreferences? = null

    /**
     * 本进程内的订阅者。
     *
     * ★ 对框架**只注册一个**监听器（[registered]），收到变化后广播给这里的所有订阅者。
     *   理由：框架级监听走的是文件监控，多注册几个既没必要，也让我们无法控制广播顺序。
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var registered = false

    /** 兜底轮询是否已启动（幂等） */
    @Volatile private var polling = false

    /**
     * "读空"告警是否已经提示过（**只在轮询线程里读写**，所以不需要 volatile）。
     *
     * ★ 存在的理由：读空是**持续状态**（文件真被清掉时每一轮都是空），
     *   不记这一笔就会每 [POLL_MS] 刷一行日志。见 [advanceBaseline]。
     */
    private var warnedEmpty = false

    /** 配置通道是否就绪（拿得到且读得动那个文件）。供状态上报，让界面能如实显示 */
    val available: Boolean get() = xs != null

    /** 通道诊断信息（不可用时界面/日志要看的原因） */
    @Volatile var diag: String = "未初始化"
        private set

    /**
     * 打开配置。**幂等**，失败返回 null（调用方必须处理，不能假设它一定成功）。
     *
     * 失败的三个真实原因，都要如实报出来，不能静默：
     *   - 模块没在 LSPosed 里**启用**，或作用域里**漏勾 `com.android.systemui`**
     *     ⇒ SystemUI 里压根没有我们；
     *   - 本应用进程没被注入 ⇒ prefs 没被重定向到 safe-zone，写在了
     *     `/data/data/<pkg>/shared_prefs/`（SELinux `app_data_file`，SystemUI 读不了）。
     *     ⚠️ 这一条**与作用域无关、用户也无需操作**（2026-10-03 更正，此前注释与界面文案
     *     都错误地让用户"把 HyperPlus 自己勾进作用域"—— 列表里根本没有它）：
     *     legacy 模块（`assets/xposed_init` 入口）由 LSPosed 保存作用域时**自动**把自己的包
     *     加进 scope，并把它从列表里过滤掉。详见 `docs/配置通道_*_2026-10-03`。
     *     App 侧仍会单独诊断并提示（见 `AppPrefs.appChannelOk`）；
     *   - 用户从未打开过 App ⇒ prefs 文件还没被创建（Android 只在首次写入时落盘）。
     *     ⇒ 这条由 `AppPrefs.init` 主动落一次盘来兜（见那边的注释）。
     */
    fun open(): XSharedPreferences? {
        xs?.let { return it }
        synchronized(this) {
            xs?.let { return it }

            val p = runCatching { XSharedPreferences(APP_PKG, PREFS_NAME) }
                .onFailure { Log.w(TAG, "构造 XSharedPreferences 失败", it) }
                .getOrNull()
            if (p == null) {
                diag = "XSharedPreferences 构造失败（LSPosed 框架未提供该 API？）"
                Log.w(TAG, diag)
                return null
            }

            // ⚠️ **不要**拿 `File.canRead()` 当准入门槛（2026-09-28 真机实测后改掉的）：
            //   `canRead()` 走 access(2)，SELinux 会对它**单独**判一次；而 XSharedPreferences
            //   读文件是经 `SELinuxHelper` 的服务通道（直读被 SELinux 拦时由 lspd 代读），
            //   两条路的结论可能不一致 ⇒ 用 canRead 当门槛会**误杀一个本来能用的通道**。
            //   真机形态：safe-zone 文件 = 664 + `u:object_r:lsposed_file:s0:c157,c257,...`，
            //   而 SystemUI 跑在 `system_app` 域 —— 光看标签无法断言可读性，只能读一次看结果。
            //   ⇒ 真正的判据是「能不能读出**实际内容**」。
            runCatching { p.reload() }

            val f = runCatching { p.file }.getOrNull()
            val canRead = runCatching { f?.canRead() }.getOrNull()
            val hasContent = runCatching {
                // `p.all` 本身就够判"有内容"；后面这串是**兜底**：万一某个版本的
                // XSharedPreferences 在进程刚起来时 `all` 还是空的，只要认得出我们的键就放行。
                // ⚠️ 键名跟着 2026-09-28 的模式解耦一起改过（rotate_mode → rotate_mode_inner）；
                //   09-29 外屏增强删掉后 `rotate_mode_outer` 是**退役键**，这里留着只是因为
                //   它在老用户的文件里存在、能当"这文件是我们的"的指纹。别当"还有一个配置层"。
                p.all.isNotEmpty() ||
                    p.contains(PrefsBridge.MODE_INNER) ||
                    p.contains(PrefsBridge.MODE_OUTER) ||
                    p.contains(PrefsBridge.STRATEGY)
            }.getOrDefault(false)
            if (!hasContent) {
                // ⚠️ 这句会经 `AppPrefs.configDiag` **显示在设置页**上（不是只进日志），
                //   所以⛔ 不能写 markdown —— 星号在真机上是**原样显示**的。
                //   （2026-10-03 修：这是同一错误的第三处，前两处在 AppWhitelistPage / AppPrefs。）
                diag = "配置为空或读不到（path=$f, canRead=$canRead）—— 检查：① HyperPlus 在 LSPosed " +
                    "里已启用，且作用域勾了「系统界面」；② 装好后打开过一次 App"
                Log.w(TAG, diag)
                return null
            }

            xs = p
            diag = "就绪（path=$f, canRead=$canRead）"
            installWatcher(p)
            installPollFallback(p)
            Log.i(TAG, "配置通道就绪：$APP_PKG/$PREFS_NAME → $f")
            return p
        }
    }

    /** 订阅配置变化（回调在本模块自建的线程/框架监控线程上，**不在** SystemUI 主线程） */
    fun subscribe(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    // ---------------------------------------------------------------- 读取
    //
    // 全部包 runCatching：XSharedPreferences 的 getter 要等异步加载完成，
    // 文件被删/被改坏时可能抛。这里一律退化成"用默认值"，绝不向上冒泡 ——
    // 调用方是 SystemUI 进程，任何未捕获异常都是状态栏级别的事故。

    fun getString(key: String, def: String?): String? =
        runCatching { xs?.getString(key, def) }.getOrDefault(def)

    fun getInt(key: String, def: Int): Int =
        runCatching { xs?.getInt(key, def) ?: def }.getOrDefault(def)

    fun getFloat(key: String, def: Float): Float =
        runCatching { xs?.getFloat(key, def) ?: def }.getOrDefault(def)

    fun getBoolean(key: String, def: Boolean): Boolean =
        runCatching { xs?.getBoolean(key, def) ?: def }.getOrDefault(def)

    fun contains(key: String): Boolean =
        runCatching { xs?.contains(key) == true }.getOrDefault(false)

    /**
     * 全量重读。改配置后由订阅者自行调用一次（框架回调里已经调过，这里是给
     * "手动要求刷新"的场景用的兜底）。
     */
    fun reload() {
        runCatching { xs?.reload() }.onFailure { Log.w(TAG, "reload 失败", it) }
    }

    // ---------------------------------------------------------------- 内部

    private fun installWatcher(p: XSharedPreferences) {
        if (registered) return
        runCatching {
            p.registerOnSharedPreferenceChangeListener { _, _ ->
                // ★ key 恒为 null（官方原话：by design）⇒ 只能全量重读
                reload()
                listeners.forEach { l -> runCatching { l() } }
            }
            registered = true
            Log.i(TAG, "配置变更监听已注册（回调 key 恒为 null，内部走全量重读）")
        }.onFailure {
            // 注意：这里失败**不代表**配置不跟随了 —— [installPollFallback] 会兜住，
            // 只是跟随粒度从"文件一变就通知"退化成"最多晚 POLL_MS"。
            Log.w(TAG, "注册配置变更监听失败（预期之内，见类注释）→ 由兜底轮询接管", it)
        }
    }

    /**
     * 兜底轮询：不依赖框架的"文件变了吗"判断（理由见类注释与下面的实测记录）。
     *
     * ★ 2026-09-28 **真机实测：框架的 `XSharedPreferences.hasFileChanged()` 恒返回 false**，
     *   于是这条腿原本是**死的**。证据（真机，非推断）：
     *     - 轮询线程确实活着 —— SystemUI 进程的 `/proc/<pid>/task/` 下每个 `comm` 里
     *       能看到 `hyperplus-prefs`（线程名被内核截到 15 字符）；
     *     - 直接改配置文件（`sed` 改 `semi_hint_ms`，mtime 由 `10:58:57` 变到 `10:59:38`），
     *       等 10 秒，**"发现变化"那条日志一次都没出现**；
     *     - 同文件的 `p.file` 在同一进程里 `canRead=true`（`open()` 的日志），
     *       说明**不是**权限问题 —— 就是这个框架方法本身失效，恒返回 false。
     *   ⚠️ 那条 `runCatching { p.hasFileChanged() }` 会把"方法不可用（抛异常）"和
     *     "文件没变"混成同一个 false ⇒ **静默失效**，正是最坏的一种失败方式。
     *
     * ⇒ 现在改成**自己比对内容**（`reload()` + 比 `all`）：
     *   1. 不依赖任何框架辅助方法，只依赖"能把文件读出来"这一件已被证实成立的事
     *      （`open()` 里读到了实际内容才会走到这里）；
     *   2. 顺带解决了类注释提到的那个痛点 —— 回调拿不到 key，而**内容比对能说出是哪个键变了**，
     *      以后排查"改了没生效"时这条日志直接给出答案；
     *   3. 代价是每 [POLL_MS] 多一次 615 字节左右的小文件读取（原来是纯 stat）。
     *      对它换来的确定性来说可以接受：配置跟随一旦失效，用户看到的是
     *      "我明明改了，它就是不动"，且**界面无法区分**。
     */
    private fun installPollFallback(p: XSharedPreferences) {
        if (polling) return
        polling = true
        val t = Thread({
            reload()
            var last = snapshot(p) ?: emptyMap()
            Log.i(TAG, "兜底轮询已启动（每 ${POLL_MS}ms 比对内容，基线 ${last.size} 个键）")
            while (true) {
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                reload()
                // 读不到 ⇒ 这一轮跳过。**绝不**把它当成"变成空配置"去广播，
                // 否则会每 2 秒用默认值覆盖引擎配置（模式被悄悄打回 SYSTEM）。
                val cur = snapshot(p) ?: continue
                val next = advanceBaseline(last, cur)
                if (next == null) {
                    // 只有**读空**才值得提示，而且只提示一次（读空是持续状态）。
                    // "内容没变"是常态，绝不能在这里刷日志。
                    if (cur.isEmpty() && !warnedEmpty) {
                        warnedEmpty = true
                        Log.w(TAG, "配置文件已消失或变空 → 保留上一次的配置，不广播")
                    }
                    continue
                }
                warnedEmpty = false
                val diff = changedKeys(last, cur)
                last = next
                Log.i(TAG, "兜底轮询发现配置内容变化（$diff）→ 全量重读并广播")
                listeners.forEach { l -> runCatching { l() } }
            }
        }, "hyperplus-prefs-poll")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    /** 取一份配置内容快照；读不到返回 null（调用方必须区分"读不到"与"空"） */
    @Suppress("UNCHECKED_CAST")
    private fun snapshot(p: XSharedPreferences): Map<String, Any?>? =
        runCatching { p.all as Map<String, Any?> }.getOrNull()

    /**
     * 轮询基线的推进规则（纯函数，可单测）。
     *
     * 返回 `null` = **保持原基线不动**；返回非空 = 新基线，并且值得广播。
     *
     * ★ 为什么把这段抽出来（2026-09-29 实测踩到的真 bug）：
     *   原来的写法是 `last = cur` 写在"读空"早退**之前**，于是**一次空读就把基线打空**。
     *   后果链条（真机日志，07:42:05 → 07:42:11）：
     *     ① 05.475 读到空（`cat >` 就地改写文件时"截断→写入"之间有个瞬时窗口）⇒ 基线被清空；
     *     ② 07.475、09.475 两轮"空 == 空"⇒ `continue`，**连日志都没有**（静默丢了两轮）；
     *     ③ 11.475 读到完整内容 ⇒ 相对空基线，18 个键**全部**被判成"新增"
     *        ⇒ 日志变成"新增:（全部键）"，**诊断价值归零**（看不出真正变的是哪个键）。
     *
     *   ⚠️ 保留旧基线**不会**导致"文件真被清空后不再跟随"：文件重新有内容时
     *     `cur != last`（旧基线）照样成立，照常广播。
     */
    internal fun advanceBaseline(
        last: Map<String, Any?>,
        cur: Map<String, Any?>,
    ): Map<String, Any?>? = when {
        // 读空：既不能广播，也不能把基线打空（打空 = 下次把全部键误报成新增）
        cur.isEmpty() -> null
        // 没变：保持（也不刷日志）
        cur == last -> null
        else -> cur
    }

    /** 两个快照之间的差异，形如 `新增:a,b 改动:c 删除:d`（只用于日志） */
    internal fun changedKeys(old: Map<String, Any?>, new: Map<String, Any?>): String {
        val added = new.keys - old.keys
        val removed = old.keys - new.keys
        val modified = old.keys.intersect(new.keys).filter { old[it] != new[it] }.toSet()
        return buildString {
            if (added.isNotEmpty()) append("新增:${added.joinToString(",")} ")
            if (modified.isNotEmpty()) append("改动:${modified.joinToString(",")} ")
            if (removed.isNotEmpty()) append("删除:${removed.joinToString(",")} ")
        }.trim().ifEmpty { "内容相同" }
    }
}

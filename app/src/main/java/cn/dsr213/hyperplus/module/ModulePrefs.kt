package cn.dsr213.hyperplus.module

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import cn.dsr213.hyperplus.ConfigChannel
import cn.dsr213.hyperplus.PrefsBridge
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 引擎侧（SystemUI 进程）读取 App 配置的**唯一入口**。
 *
 * ============================ 2026-10-03：通道定型 ============================
 * 配置下行的完整链路**只有一条**，且**完全不依赖 LSPosed 的任何配置 API**：
 *
 *   ① **广播推送（主通道）**：App 每一次改设置，都把**全量快照**用一条广播发过来
 *      （[ConfigChannel]，带签名级权限 [ConfigChannel.PERM_PUSH]，只有同签名应用能发）；
 *   ② **引擎代写镜像**：收到后写进 `Settings.System`（[PrefsBridge.MIRROR]）——
 *      引擎自己算的账写系统设置，这条规矩没变；
 *   ③ **启动读回**：SystemUI 重启时先读镜像（[loadMirrorFromSettings]）⇒ **不依赖 App 在跑**；
 *   ④ **反向兜底**：镜像为空时反向问 App 要一次（[ConfigChannel.ACTION_REQUEST]），
 *      只在 App 活着时有效（HyperOS 会拦"广播冷启动 App"）。
 *
 * ⇒ 历史沿革（**别再从历史里找方案**）：
 *   更早的下行通道是 `Settings.System` 自定义键 + App 借 root 代写（唯一需要 root 的地方）；
 *   2026-09-28 换成 LSPosed 的 `XSharedPreferences`（nsp）—— 文件通道、零 root；
 *   2026-10-03 **nsp 被摘除**：官方已宣布它将在 LSPosed 2.3.0 移除，且**声明使用它就会让模块页面
 *   常驻一条「使用了已废弃功能」的横幅**。本模块因此在同一天迁到 LSPosed 新 API（libxposed，102），
 *   下行走上表那四条。⇒ 现在这里**一行 `de.robv.*` 都没有**（新 API 下 legacy 包在 classloader
 *   层面就被禁了），也别想着"把 nsp 加回来"。
 *
 * ⚠️ **一个已知的窄缝（刻意接受，不是 bug）**：用户改了设置、但**引擎当时不在**
 *   （模块刚装还没启用 / 系统界面正在重启），那条广播就没人接；此时若 App 之后再没被打开过，
 *   引擎手上会是空配置而按默认值跑。**只要用户打开一次 App 就自动补齐**（AppPrefs.init 末尾
 *   无条件推一次）。想彻底消掉这个缝，就得让 App 自己去写 `Settings.System`（需要额外授权）
 *   或走 LSPosed 的 service 通道（要引第二套依赖）—— 都不划算。
 *
 * ⚠️ **镜像存在 `Settings.System` 里，别的应用可以读**（键名 `hyperplus_*`）。
 *   这与"配置属于私人偏好"是有张力的：里面含**应用名单**。真要收口，得把镜像挪进
 *   引擎自己的私有目录（App 不需要读它）—— 那是另一件事，见
 *   `docs/API102迁移_2026-10-03.md` 的「还没做的事」。
 */

internal object ModulePrefs {

    private const val TAG = "HyperPlusModulePrefs"

    // ================================================================ 已经不在这里的东西
    //
    // ★★ 2026-10-03 迁移时**删掉**了两个常量：`APP_PKG` 与 `PREFS_NAME`（值分别是
    //   本应用包名与 `facerotate_prefs`）。它们服务的是"引擎按路径去读 App 的 prefs 文件"
    //    那套（nsp）—— 而那套已经不存在：引擎侧**不再碰任何文件**。
    //
    //   配置现在只有一条路（见类注释那张表）：App 广播推快照 → 引擎内存镜像
    //   ＋ `Settings.System` 落一份供重启读回。
    //
    //   ⚠️⚠️ 别再按"两边文件名必须一字不差"的思路把 `PREFS_NAME` 加回来 ——
    //     那个**对称约束已经不存在**，留着它只会在下次有人改文件名时误导他去"同步"一个
    //     根本没人读的常量。真身文件名现在只在 `AppPrefs.NAME` 一处。
    //   ⚠️ 包名同理：只剩一份真值 [cn.dsr213.hyperplus.ConfigChannel.APP_PKG]
    //     （`HyperPlusModule` 也引它，不再自己写一份）。

    /**
     * ★★★ **广播镜像**（2026-10-03 新增）—— 现在配置下行的**主通道**。
     *
     * App 每改一次设置就把**全量快照**用一条广播发过来（[ConfigChannel]），
     * 到达后存在这里；启动时还会先从 `Settings.System`（[PrefsBridge.MIRROR]）读回一份，
     * 于是**引擎重启不需要 App 配合**。
     *
     * ★ 它是**唯一**的配置来源（nsp 那条腿已于 2026-10-03 摘除）：
     *   读得到就有配置、读不到就是空 —— 不再有"镜像没有、老通道有"的中间态。
     */
    @Volatile private var mirror: Map<String, Any?>? = null

    /** 引擎侧写 [PrefsBridge.MIRROR] 用的 resolver（[attachTransport] 灌入） */
    @Volatile private var cr: ContentResolver? = null

    /** 接收 App 推送的广播接收者（动态注册，幂等） */
    @Volatile private var pushReceiver: BroadcastReceiver? = null

    /** 推送线程本体（[pushHandler] 的持有者；只为日志/排障留个名字） */
    @Volatile private var pushThread: HandlerThread? = null

    /**
     * 推送的**调度器**（交给 `registerReceiver`，见 [registerPushReceiver]）。
     *
     * ★★ 必须非空：`onReceive` 默认跑在**注册方所在进程的主线程**上 —— 也就是 **SystemUI 主线程**。
     *   迁移前那条路是 LSPosed 框架的文件监控线程，天然不在主线程；换成广播之后
     *   **默认就把这份活搬到了状态栏的主线程上**，而 `onConfigPushed` 里要做
     *   ① `Settings.System` 落盘（跨进程 binder 写）② 逐个通知订阅者
     *   （订阅者里有读 Settings 判断"要不要开相机标定"这种活）
     *   ⇒ 在主线程上做就是**掉帧起步、ANR 封顶**，而 ANR 在 SystemUI 上是状态栏级事故。
     *   ⛔ 别把这个调度器改回 `null`（`null` = 主线程）。
     */
    @Volatile private var pushHandler: Handler? = null

    /**
     * 本进程内的订阅者。
     *
     * ★ 变更来源**只有一个**：[onConfigPushed] —— 收到 App 推来的全量快照后逐个回调。
     *   2026-10-03 之前这里还挂着 LSPosed 框架级的文件监听，已随 nsp 一起删除；
     *   现在"通知谁、按什么顺序"全由本模块自己说了算。
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 配置通道是否就绪 —— 判据是**手上到底有没有一份配置**（镜像非空）。
     *
     * ⚠️ 别再拿"某个读取器构造出来了没"当判据：那套东西（nsp）已经不存在了。
     */
    val available: Boolean get() = !mirror.isNullOrEmpty()

    /**
     * **广播通道装上了没有** —— 判据是接收者注册成功（[pushReceiver] 非空）。
     *
     * ★ 它回答的问题与 [available] **不是同一个**，别混：
     *   - [available]   = "手上有没有配置"（空是**正常状态** —— App 可能从没被打开过）；
     *   - [transportReady] = "App 发过来说明它想干什么时，我收不收得到"（空是**故障**）。
     *
     * ⚠️ 熔断态那条出口（`EngineHost.installBreakerResumeWatch`）必须看这一个：它要等的是
     *   App 那条「重新启用」，而熔断发生时镜像几乎必然是空的（熔断就发生在启动早期，
     *   那时候镜像还没来得及读回）⇒ 拿 [available] 当判据会把"能收命令"误判成"收不到"。
     */
    val transportReady: Boolean get() = pushReceiver != null

    /** 通道诊断信息（不可用时界面/日志要看的原因） */
    @Volatile var diag: String = "未初始化"
        private set

    /**
     * 配置是否已就绪。**幂等**，只读不写。
     *
     * 判据就是 [mirror] 有没有内容。它由 [attachTransport] 灌（启动时从 `Settings` 读回 + 接收 App 推送）。
     * ⚠️ **调用前必须先 [attachTransport]** —— 否则手上一定是空的，会得出"没配置"的错误结论。
     *   调用顺序由 `EngineHost.bootOn` 保证（通道在 `AppPrefs.initHost` 之前装）。
     *
     * ★ 返回值语义（调用方按它决定界面怎么说）：
     *   - `true`  → 手上有一份配置，引擎按它跑；
     *   - `false` → 还没有配置，引擎按默认值跑。**这不是故障**：App 一被打开就会推一份过来，
     *     所以 [diag] 的措辞是"告诉用户该做什么"，而不是"报告一个错误"。
     *
     * ⚠️ 与 0.5.0 的行为差异（刻意）：那时 `open()` 返回一个文件读取器对象，`null` = 通道坏了。
     *   现在没有"文件读取器"这回事，所以"空"只意味着**还没收到**，随时会被一条推送填上。
     */
    fun open(): Boolean {
        if (available) {
            diag = "就绪（收到 ${mirror?.size ?: 0} 个键）"
            return true
        }
        // ⚠️ 这句会经 `AppPrefs.configDiag` 显示在设置页上（不是只进日志），
        //   所以⛔ 不能写 markdown —— 星号在真机上是**原样显示**的。
        //   也⛔ 不要写"检查 LSPosed 作用域"这种用户做不到/不相关的话（10-03 更正过四次的那类错误）。
        diag = "还没收到配置，引擎按默认值在跑。打开一次 HyperPlus 应用就会自动同步"
        Log.i(TAG, "配置镜像为空：$diag")
        return false
    }

    // ---------------------------------------------------------------- 广播通道（2026-10-03 新增）

    /** 反向"请推一次配置"是否已经问过（只问一次，避免 App 不在时反复发广播） */
    @Volatile private var requestedOnce = false

    /**
     * 装上**广播通道**：读回镜像 → 注册接收者 → 必要时反向问 App 要一次配置。
     *
     * **幂等**；失败只记日志 —— 调用方是 SystemUI 进程，绝不能因为通道装不上就抛。
     *
     * ⚠️ 调用时机有**两处，缺一不可**：
     *   ① 正常启动：`EngineHost.bootOn` 里，且**必须排在 `AppPrefs.initHost` 之前**
     *      （反了的话 `initHost` 读配置那一刻镜像必然还空 ⇒ 引擎按默认值起跑，
     *      而下游没有任何一环会事后补读一次）；
     *   ② **熔断态**：`EngineHost.installBreakerResumeWatch` 里。熔断态刻意什么都不初始化，
     *      但「重新启用」这条命令**正是从这条通道来的** —— 不装就等于把自己锁死。
     */
    fun attachTransport(hostCtx: Context) {
        cr = runCatching { hostCtx.applicationContext?.contentResolver ?: hostCtx.contentResolver }
            .getOrNull()
        loadMirrorFromSettings()
        registerPushReceiver(hostCtx)

        // 镜像还空着 ⇒ 手上没有可用配置。反向问一次：App 不一定在跑，
        // 但它在收到之后会自己把整份配置补齐。
        if (mirror.isNullOrEmpty() && !requestedOnce) {
            requestedOnce = true
            if (ConfigChannel.sendRequest(hostCtx)) {
                Log.i(TAG, "本地镜像为空 → 已向 App 请求一次配置推送（${ConfigChannel.ACTION_REQUEST}）")
            }
        }
    }

    /**
     * 从 `Settings.System`（[PrefsBridge.MIRROR]）把上次落盘的镜像读回来。
     *
     * ★ 它就是"引擎重启后不依赖 App 也能拿回配置"的全部实现。没有它，SystemUI 每被
     *   杀一次、用户的设置就会看起来被打回默认。
     */
    private fun loadMirrorFromSettings() {
        val c = cr ?: return
        val raw = runCatching { PrefsBridge.readString(c, PrefsBridge.MIRROR) }.getOrNull()
        if (raw.isNullOrEmpty()) return
        val snap = ConfigChannel.decode(raw)
        if (snap.isNullOrEmpty()) {
            Log.w(TAG, "镜像解析失败或为空（长度 ${raw.length}）→ 先按「没有」处理")
            return
        }
        mirror = snap
        Log.i(TAG, "配置镜像已从系统设置读回（${snap.size} 个键）")
    }

    /**
     * 收到 App 推来的**全量快照**。
     *
     * ★ 两条纪律：
     *   ① **坏包 / 空包一律丢弃**，绝不拿去覆盖现有配置 —— 否则一次传输故障就会把用户的
     *      配置悄悄打回默认（与 [advanceBaseline] 里"读空不打空基线"是同一条教训）；
     *   ② **落盘失败不影响本次生效**：先更新内存、先通知订阅者，`Settings` 写失败只记日志。
     *      理由：落盘是为"下次引擎重启"服务的，而"现在生效"是用户此刻就要看到的东西。
     */
    fun onConfigPushed(json: String) {
        val snap = ConfigChannel.decode(json)
        if (snap == null) {
            Log.w(TAG, "配置推送解析失败（长度 ${json.length}）→ 丢弃")
            return
        }
        if (snap.isEmpty()) {
            Log.w(TAG, "配置推送是空的 → 丢弃（不用空配置覆盖引擎状态）")
            return
        }
        val base = mirror ?: emptyMap()
        val diff = changedKeys(base, snap)
        val worthNotifying = advanceBaseline(base, snap) != null
        mirror = snap
        // ★ 诊断文本必须跟着更新：[open] 只在启动时算过一次，那句「收到 N 个键」在收到推送后
        //   立刻就过期了。2026-10-03 装机实测踩到过 —— 推送后镜像已经是 12 个键，
        //   而设置页/诊断页显示的仍是启动时那句「收到 15 个键」。
        //   ⛔ 别删：仪表说与事实不符的话，比不显示更坏。
        diag = "就绪（收到 ${snap.size} 个键）"
        // ★ 落盘**不跳过**：上一次落盘可能失败（或引擎刚起来还没写过），
        //   而"内容没变"恰恰是最该补写一次的时刻 —— 跳过它就等于永不重试。
        val ok = cr?.let { PrefsBridge.writeString(it, PrefsBridge.MIRROR, json) } ?: false
        Log.i(
            TAG,
            "收到 App 推送的配置（${snap.size} 个键，$diff；落盘=$ok；" +
                "通知订阅者=${if (worthNotifying) "是" else "内容没变，跳过"}）",
        )
        if (worthNotifying) listeners.forEach { l -> runCatching { l() } }
    }

    /**
     * 注册接收 App 推送的**动态**广播接收者。**幂等**。
     *
     * ★★ 两个参数都必须给对，少一个就有一个方向是坏的：
     *
     *   ① **`RECEIVER_EXPORTED`**（API 33+ 不带这个标志会直接抛）
     *      —— 我们跑在 SystemUI 进程里、发送方是**另一个应用**，不导出就永远收不到；
     *   ② **`broadcastPermission`** = [ConfigChannel.PERM_PUSH]（signature 级）
     *      —— 导出意味着"任何应用都能往这个 action 上发"，所以必须配一条准入权限，
     *      把它限死成"与 App 同签名的应用"，也就是只有 App 自己能发。
     *
     * ★ 鉴权为什么走**权限**而不是"在 `onReceive` 里核对发送方 uid"：
     *   核对那条路要读发送方身份，而那组 API 在 `BroadcastReceiver` 上 **API 34 才有**
     *   ⇒ Android 13 及以下只能退化成"谁都能发"（本工程 minSdk 30，那就是三个大版本敞着门）。
     *   权限是 **AMS 在投递前**判的，不持有者连 `onReceive` 都进不来，且与系统版本无关。
     *   ⚠️ 2026-10-03 先写的那版用了 `sendingUid`，那个属性**在 `BroadcastReceiver` 上不存在**，
     *   编译期直接 `Unresolved reference` —— 别再往那个方向试。
     *
     * ★ 用 **5 参**重载 `registerReceiver(r, f, perm, scheduler, flags)`：它 **API 26** 就有，
     *   本工程 minSdk 30 ⇒ **不需要按版本分支**。（API 26–32 上 `flags` 被忽略，
     *   那个年代动态接收者本来就只有"导出"一种形态，行为不变。）
     */
    private fun registerPushReceiver(hostCtx: Context) {
        if (pushReceiver != null) return
        synchronized(this) {
            if (pushReceiver != null) return

            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action != ConfigChannel.ACTION_PUSH) return
                    val s = i.getStringExtra(ConfigChannel.EXTRA_SNAPSHOT) ?: return
                    runCatching { onConfigPushed(s) }
                        .onFailure { Log.w(TAG, "处理配置推送失败（已吞掉）", it) }
                }
            }

            val f = IntentFilter(ConfigChannel.ACTION_PUSH)
            runCatching {
                hostCtx.registerReceiver(
                    r, f, ConfigChannel.PERM_PUSH, ensurePushHandler(), Context.RECEIVER_EXPORTED,
                )
                pushReceiver = r
                Log.i(
                    TAG,
                    "配置推送接收者已注册（${ConfigChannel.ACTION_PUSH}；" +
                        "发送方须持 ${ConfigChannel.PERM_PUSH} ⇒ 仅同签名应用可发；" +
                        "处理线程=${if (pushHandler != null) "自建推送线程" else "⚠️ 主线程（没兜住，见 ensurePushHandler）"})",
                )
            }.onFailure { Log.w(TAG, "注册配置推送接收者失败 → 本次收不到 App 的配置推送（反向请求也走这条通道）", it) }
        }
    }

    /**
     * 取得推送处理线程的 Handler（**懒建、幂等**）。
     *
     * ★ 为什么单独开一条线程而不是复用 `EngineHost.bootThread`：
     *   那条线程是**启动用的**（名字就叫 boot），启动流程跑完之后靠它的 looper 活着纯属巧合；
     *   而这条通道要在**整个进程生命周期**里都用（配置随时可能改），
     *   两者寿命与语义都不同 —— 混用会让"启动线程哪天被 quit"变成一条静默的配置失联。
     *
     * ★ 建不出来（极端情况）时返回 `null` ⇒ `registerReceiver` 退回主线程。
     *   那时**宁可收得到但占一点主线程**，也不要"收不到"：配置是功能的命脉，
     *   而主线程那点开销只是掉帧级别 —— 故障等级差着量级。日志会如实记下这次降级。
     */
    private fun ensurePushHandler(): Handler? {
        pushHandler?.let { return it }
        synchronized(this) {
            pushHandler?.let { return it }
            return runCatching {
                val t = HandlerThread("hyperplus-config-push").apply { start() }
                val h = Handler(t.looper)
                pushThread = t
                pushHandler = h
                h
            }.onFailure { Log.w(TAG, "推送线程创建失败 → 本次退回主线程处理配置推送", it) }.getOrNull()
        }
    }

    /**
     * 订阅配置变化。
     *
     * ★★ 回调跑在 [ensurePushHandler] 那条**自建推送线程**上，**不在** SystemUI 主线程。
     *   这不是风格问题：订阅者里有"读 `Settings` 判断要不要开相机做标定"这种会阻塞的活
     *   （见 `EngineHost.onConfigChanged`），在状态栏进程的主线程上做就是 ANR 风险。
     *   ⛔ 别把接收者的调度器改回 `null`（那等于把这份保证撤掉，而代码看着一切正常）。
     */
    fun subscribe(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    // ---------------------------------------------------------------- 读取
    //
    // 取值一律"先从镜像找，找不到就用默认值"，**绝不向上抛** ——
    // 调用方是 SystemUI 进程，任何未捕获异常都是状态栏级别的事故。

    /**
     * 取值：**镜像就是唯一来源**（2026-10-03 起 nsp 那条"老通道"已不存在）。
     *
     * ★ 镜像那条路**不强求类型一致**：JSON 回来的数字可能是 `Int` 也可能是 `Float`，
     *   而调用方拿 `getInt` 去读一个 `Float`（或反过来）都是正常的 ⇒ 一律经 `Number` 转换。
     *   ⛔ 别改成 `as Int` 那种硬转 —— 那会在"值恰好是浮点"时抛 `ClassCastException`，
     *   而异常在 SystemUI 进程里是状态栏级别的事故。
     */
    private fun fromMirror(key: String): Any? = mirror?.get(key)

    fun getString(key: String, def: String?): String? {
        val m = fromMirror(key)
        if (m != null) return m.toString()
        return def
    }

    fun getInt(key: String, def: Int): Int {
        when (val m = fromMirror(key)) {
            is Number -> return m.toInt()
            is String -> return m.toIntOrNull() ?: def
            is Boolean -> return if (m) 1 else 0
            else -> Unit
        }
        return def
    }

    fun getFloat(key: String, def: Float): Float {
        when (val m = fromMirror(key)) {
            is Number -> return m.toFloat()
            is String -> return m.toFloatOrNull() ?: def
            else -> Unit
        }
        return def
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        when (val m = fromMirror(key)) {
            is Boolean -> return m
            is String -> return m.toBooleanStrictOrNull() ?: def
            is Number -> return m.toInt() != 0
            else -> Unit
        }
        return def
    }

    fun contains(key: String): Boolean {
        if (mirror?.containsKey(key) == true) return true
        return false
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 配置基线的推进规则（纯函数，可单测）。
     *
     * 返回 `null` = **保持原基线不动**；返回非空 = 新基线，并且值得通知订阅者。
     *
     * ★ 2026-10-03 迁移后它的位置变了：原先那条 2 秒轮询腿（`installPollFallback`）
     *   与 `snapshot()` **已随 nsp 一起删除**，
     *   现在这个判据挂在**广播推送**这一侧（见 [onConfigPushed]）——
     *   用途从"文件内容变没变"变成"这次推送有没有带来新内容"。
     *   App 每次启动都会无条件推一份，绝大多数推送内容与镜像相同 ⇒ 判据替我们省掉
     *   一次无意义的全量重读，同时把"读空不许打空基线"这条教训留在了代码里（有单测钉着）。
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

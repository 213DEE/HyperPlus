package cn.dsr213.hyperplus.module

import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * ★★★ **分屏几何探针**（2026-10-11 新增）—— 专查"7/8 格为什么出 bug"。
 *
 * ============================ 它要回答的问题 ============================
 * 用户实测：**6 格正常，开 7 格 / 8 格就出 bug**（响应慢、窗口跑到不该在的地方、
 * 窗口莫名其妙消失）。严重度：影响使用。
 *
 * 离线反汇编 `Miui-WindowManager-Shell.jar` 得到的**关键事实**（✅ 字节码实证）：
 *
 * ```
 * MultipleSplitStageOrderOperator.<init>:
 *     stageIds   = List.of(0,1,2,3,4,5,6,7)     // 硬编码 8 个 ⇒ 最多 8 格
 *     for (i = 0; i < MAX_STAGES; i++)          // ★ 严格小于
 *         allStages.add(new ...StageTaskListener(stageIds.get(i), ...))
 *     ⇒ allStages.size() == MAX_STAGES == 8       （索引 0..7）
 *
 * MultipleSplitLayout.<init>:
 *     for (i = 0; i <= MAX_STAGES; i++) {       // ★ 含等号 ⇒ 9 次！
 *         mDividerPosition.add(Integer.valueOf(i))   // ⇒ size == 9（0..8）
 *         mDividerBounds.add(new Rect())             // ⇒ size == 9
 *         if (i < MAX_STAGES) {                      // ★ 严格小于 ⇒ 8 次
 *             mWinTokens.add(null)                   // ⇒ size == 8
 *             mStageBounds.add(new Rect())           // ⇒ size == 8
 *             mQuickAnimBounds.add(new RectF())      // ⇒ size == 8
 *             mContentBounds.add(new Rect())         // ⇒ size == 8
 *         }
 *     }
 * ```
 *
 * ⇒ **同一份 `MAX_STAGES` 被用了两种语义**：有的 List 是 `0..MAX_STAGES`（9 个），
 *   有的是 `0..MAX_STAGES-1`（8 个）。而 `onStageOrderAdded` 的**入参 `addedIndex`
 *   没有被入口 clamp**（入口只挡 `rightmostActiveIndex == MAX_STAGES - 1`）。
 *
 * ⇒ 一旦某个 `addedIndex == 8`，下面这些就**必然越界**：
 * ```
 *   mStageBounds.get(insertIndex - 1)        // 8 元素 List，索引 8 ⇒ 爆
 *   mDividerPosition.get(addedIndex + 1)     // 9 元素 List，索引 9 ⇒ 爆
 * ```
 * 而这正好解释「**8 格崩**」；「7 格也崩」则要看 `mStageBounds` 是否被
 * `rightmostActiveIndex` 顶到 7（入口只挡了 `== 7`，没挡 `> 7` 的前置状态）。
 *
 * ============================ 这个探针做什么 ============================
 * **只读、只打日志**：把每次 `MultipleSplitLayout` 几何方法的调用、入参、以及
 * 六张 List 的**实时 size** 打出来。你要做的是：
 *
 * ```
 * adb shell settings put global hyperplus_split_geom_probe 1
 * adb shell su -c "killall com.android.systemui"
 * adb logcat -s HyperPlusSplitGeom
 * # 然后开 7 格、开 8 格，把日志发我
 * adb shell settings put global hyperplus_split_geom_probe 0   # 查完关掉
 * ```
 *
 * 有了这份日志，就能**确证**（而不是推断）越界发生在哪一行、`addedIndex` 实际取值。
 *
 * ============================ 安全设计 ============================
 * ① **默认关闭**（`Settings.Global`，设 1 才装）—— 与 [SplitProbe] 同一套路。
 * ② **只读**：hooker 里只读 `chain.args` + 反射读 List，然后原样 `proceed()`。
 * ③ **PROTECTIVE**：探针自己抛了也只当没这个 hook，宿主永不因它崩。
 * ④ 所有反射读包 `runCatching`，读失败就打 `?`，不影响流程。
 * ⑤ 日志**限频**：`onStageOrderAdded` 一次操作会被调很多次，前 N 次之后降级。
 */
internal object SplitGeomProbe {

    private const val TAG = "HyperPlusSplitGeom"

    /** 开关键（`Settings.Global`，默认关） */
    const val KEY = "hyperplus_split_geom_probe"

    private const val LAYOUT = "com.android.wm.shell.multiplesplit.MultipleSplitLayout"

    /** `onStageOrderRemoved` 的**唯一调用者**（实测 158/160）—— 必须挂上才能抓"谁删了格子" */
    private const val ROOT_ORG = "com.android.wm.shell.multiplesplit.MultipleSplitRootTaskOrganizer"

    /**
     * 要 hook 的方法名 —— **精确匹配**（不是关键字包含）。
     *
     * ★ 选这几个的判据：它们共同覆盖了"格子增减"（added / removed）、
     *   "分割线初始化"（init / reset）、"拖动改几何"（update 系列）、"整体布局"（updateBounds）。
     *   这正是"窗口跑到不该在的地方 / 消失"要经过的全部路径。
     */
    private val METHODS = setOf(
        "onStageOrderAdded",             // ★ 最可疑：入参 addedIndex 没被 clamp
        "onStageOrderRemoved",
        "onStageOrderReordered",
        "onStageOrderSwaped",
        "onDragStageOrderReordered",
        "onStageAddFromIconDrag",
        "initDividerPosition",           // ★ 循环 i <= MAX_STAGES
        "resetDividerPosition",          // ★ 同款循环
        "updateMultipleDividerPosition",
        "setDividerPosition",
        "updateBounds",
        "updateLayouts",
        "recomputeBoundsForSurvivors",
        "moveOffscreenStageToScreen",
        "moveStagesByOffsetFinished",
        "populateTouchZones",
        "rotateTo",
        "onConfigurationChanged",
        "updateConfiguration",
        // —— MultipleSplitRootTaskOrganizer 侧（只有这几个名字会命中它）——
        "updateSplitBounds",     // ★★ 唯一调 onStageOrderRemoved 的地方
        "addRecentTasks",        // ← 它最后转调 updateSplitBounds
        "updateRecentTasks",     // ← 上面那个的入口
        "removeRecentTasks",
    )

    /** 六张 List 字段（探针读它们的 size） */
    private val LIST_FIELDS = listOf(
        "mDividerPosition",   // 9 个（i <= MAX_STAGES）
        "mDividerBounds",     // 9 个
        "mWinTokens",         // 8 个（i < MAX_STAGES）
        "mStageBounds",       // 8 个  ★ 最可能被越界访问
        "mQuickAnimBounds",   // 8 个
        "mContentBounds",     // 8 个
        "mCurrentDividerPosition",
        "offscreenTouchZoneBounds",
    )

    /** 可能参与几何的 int 字段 */
    private val INT_FIELDS = listOf(
        "mDragDividerIndex",
        "mMaxActiveStageIndex",
        "mDividerSize",
    )

    private var hits = 0

    // ================================================================ 安装

    fun install(module: XposedInterface, hostCtx: Context, classLoader: ClassLoader?) {
        if (!isOn(hostCtx)) {
            Log.i(TAG, "分屏几何探针未开启（要开：adb shell settings put global $KEY 1 然后重启系统界面）")
            return
        }

        val loaders = buildList {
            classLoader?.let { add(it) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
            runCatching { SplitGeomProbe::class.java.classLoader?.let { add(it) } }
        }.distinct()

        val cls = loaders.firstNotNullOfOrNull { l ->
            runCatching { Class.forName(LAYOUT, false, l) }.getOrNull()
        }
        if (cls == null) {
            Log.w(TAG, "❶ 类未找到：$LAYOUT ⇒ 探针失效（ROM 改名了？）")
            return
        }

        Log.i(TAG, "===== 分屏几何探针开始安装 =====")
        var n = 0

        // ① `MultipleSplitLayout` —— 几何计算层
        n += hookClass(module, cls)

        // ② `MultipleSplitRootTaskOrganizer` —— `onStageOrderRemoved` 的**唯一调用者**。
        //    ★ 实测：160 次 removed 里 158 次来自 `updateSplitBounds(Map)`。
        //    要抓"为什么删格子"，必须挂它。只挂少数几个方法（不是全类）。
        val orgCls = loaders.firstNotNullOfOrNull { l ->
            runCatching { Class.forName(ROOT_ORG, false, l) }.getOrNull()
        }
        if (orgCls == null) {
            Log.w(TAG, "❶ 类未找到：$ROOT_ORG ⇒ 拿不到 updateSplitBounds 入参")
        } else {
            n += hookClass(module, orgCls)
        }

        Log.i(TAG, "===== 分屏几何探针安装完成：钩上 $n 个方法 =====")
        Log.i(TAG, "现在请开 7 格、再开 8 格，复现那几种现象，然后把日志发我")
    }

    private fun hookClass(module: XposedInterface, cls: Class<*>): Int {
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name !in METHODS) continue
            val ok = runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker)
            }.isSuccess
            if (ok) n++ else Log.w(TAG, "   ✗ 钩不上 ${cls.simpleName}.${m.name}")
        }
        return n
    }

    private object Hooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { report(chain) }
            return chain.proceed()
        }
    }

    // ================================================================ 上报

    private fun report(chain: XposedInterface.Chain) {
        val ex = runCatching { chain.executable }.getOrNull() ?: return
        val self = runCatching { chain.thisObject }.getOrNull()
        val args = runCatching { chain.args }.getOrDefault(emptyList())

        // 前 1000 条全打，之后每 20 条打 1 条（降级而不是沉默，别把复现那一刻漏掉）
        hits++
        if (hits > 1000 && hits % 20 != 0) return

        val sb = StringBuilder()
        sb.append("▶ ").append(ex.name)
        if (args.isNotEmpty()) sb.append("(").append(args.joinToString(", ") { fmt(it) }).append(")")
        Log.i(TAG, sb.toString())

        // ★★ 调用栈 —— 回答"是谁在调它"。这是本次调查最需要的一行：
        //    onStageOrderRemoved 被调了 100+ 次，必须知道发起者是
        //    MultipleSplitOrganizer / Controller / 还是别的什么。压 8 帧。
        val stack = callStack()
        if (stack.isNotEmpty()) Log.i(TAG, "    ↳ $stack")

        if (self == null) return

        // List 尺寸 —— 越界诊断的核心
        val sizes = LIST_FIELDS.mapNotNull { f ->
            val v = readField(self, f) ?: return@mapNotNull null
            val sz = runCatching { (v as? Collection<*>)?.size }.getOrNull() ?: "?"
            "$f=$sz"
        }
        Log.i(TAG, "    ▸ $sizes")

        val ints = INT_FIELDS.mapNotNull { f ->
            val v = readField(self, f) ?: return@mapNotNull null
            "$f=$v"
        }
        Log.i(TAG, "    ▸ $ints")

        // ★ 几何 dump —— 只在"会改几何"的方法上做（updateBounds 之类），
        //   其它方法打这个太吵。这是判定「窗口跑到不该在的位置 / 消失」的直接凭据：
        //   逐格打出 mStageBounds[i] 的范围与 isValid()。
        if (ex.name in DUMP_METHODS) dumpGeometry(self)

        // ★★ **最关键的取证**：`MultipleSplitRootTaskOrganizer.updateSplitBounds(Map)`
        //   是 `onStageOrderRemoved` 的**唯一调用者**（实测 158/160 次）。
        //   它的入参 map 里，如果某个 SplitBounds 的
        //   `mMultipleSplitTaskIds.size() != mMultipleSplitBounds.size()`，就会走进
        //   `idx >= bounds.size()` 分支 ⇒ **删格子**（`onStageOrderRemoved` + `remove`）。
        //   所以：把 map 里每一对的「两个 List 长度」打出来，直接看错位。
        if (ex.name == "updateSplitBounds") dumpSplitBoundsMap(args)
    }

    /**
     * 复刻 `updateSplitBounds(Map<SplitBounds, List<Integer>>)` 的判定，把**触发删格子**的
     * 那一组入参原样打出来。
     *
     * ★ 字节码实证（dis_RootTaskOrg.txt `u[0015]~u[0118]`）——它干的是这件事：
     * ```
     * for (sb : map.keySet()) {            // u[0034] check-cast → SplitBounds  ⇒ key 就是 SplitBounds
     *     List<Integer> active = map.get(sb);        // u[0039]  入参 value = 当前活跃 taskId 列表
     *     List<Integer> ids = sb.mMultipleSplitTaskIds;     // u[0053]
     *     List<Integer> bds = sb.mMultipleSplitBounds;      // u[0055]
     *     if (active == null || active.isEmpty()) continue;
     *     if (bds == null || ids == null)          continue;
     *     for (Integer tid : active) {                  // u[0062]
     *         int idx = ids.indexOf(tid);               // u[0078]
     *         if (idx < 0)            continue;         // u[0082] if-ltz
     *         if (idx < bds.size())   continue;         // u[0088] if-ge   ← ★★ 唯一放行口
     *         onStageOrderRemoved(idx, bds.size()-1, bds);   // u[0097]  ⇒ 删格子
     *         ids.remove(idx);                           // u[0100]
     *         ...
     *     }
     * }
     * ```
     * ⇒ **触发条件是：某个活跃 taskId 在 `mMultipleSplitTaskIds` 里的下标 `idx` 已 ≥ `mMultipleSplitBounds.size()`。**
     *   换言之"taskIds 说这里有 8 格，但 bounds 只剩 7 条" ⇒ 认定该格已死 ⇒ 把格子摘掉。
     *   **这才是"窗口莫名其妙消失"的现行。**（之前我以为只是两 List 长度不等 —— 不够准，已纠正。）
     */
    private fun dumpSplitBoundsMap(args: List<Any?>) {
        val map = runCatching { args.getOrNull(0) as? Map<*, *> }.getOrNull() ?: return
        Log.i(TAG, "    ◆◆ updateSplitBounds: map.size=${map.size}")
        var i = 0
        for ((k0, v) in map) {
            val k: Any = k0 ?: continue
            val ids = runCatching { readField(k, "mMultipleSplitTaskIds") as? List<*> }.getOrNull() ?: emptyList<Any?>()
            val bds = runCatching { readField(k, "mMultipleSplitBounds") as? List<*> }.getOrNull() ?: emptyList<Any?>()
            val active = (v as? List<*>) ?: emptyList<Any?>()

            // 复刻判定：找出所有 idx >= bds.size() 的活跃 taskId
            val hit = ArrayList<String>()
            for (t in active) {
                val idx = ids.indexOf(t)
                if (idx < 0) continue          // u[0082] if-ltz  ⇒ 不在 ids 里，跳过
                if (idx < bds.size) continue   // u[0088] if-ge   ⇒ 正常，跳过
                hit.add("tid=$t@idx=$idx≥bounds=${bds.size}")  // ★★ 会走 onStageOrderRemoved
            }
            val nIds = ids.size
            val nBds = bds.size
            val lenFlag = if (nIds == nBds) "len-OK" else "★len-DIFF"
            val flag = if (hit.isEmpty()) "SAFE" else "★★DELETES-STAGE"
            Log.i(TAG, "    ◆◆ [$i] $flag $lenFlag  ids=$nIds bounds=$nBds active=${active.size}")
            // ★ 把三方内容也打出来 —— 光看长度定不了改法，必须知道"哪个 tid 落到了几号位"
            Log.i(TAG, "    ◆◆ [$i]    active=$active")
            Log.i(TAG, "    ◆◆ [$i]    sb.ids=$ids")
            Log.i(TAG, "    ◆◆ [$i]    sb.bds(rect)=${bds.map { r -> runCatching { rectStr(r) }.getOrNull() }}")
            if (hit.isNotEmpty()) Log.i(TAG, "    ◆◆ [$i]    ★★ 触发删格子: ${hit.joinToString(" | ")}")
        }
    }

    /** 对哪些方法做逐格几何 dump（都是"布局算完"的收口点） */
    private val DUMP_METHODS = setOf(
        "updateBounds", "initDividerPosition", "resetDividerPosition",
        "moveStagesByOffsetFinished", "recomputeBoundsForSurvivors",
        "onStageOrderAdded", "onStageOrderRemoved",
    )

    /**
     * 逐格 dump：`mDividerPosition[i]` / `mStageBounds[i]`(含 isValid)。
     *
     * ★ 为什么 isValid 是关键：`updateBounds(List)` 的字节码里（✅ 实证）
     *   只要**任何一格** `mStageBounds[i].isValid()` 为 false，就会
     *   `Slog.e("... invalid ...")` 然后 **`resetDividerPosition()` 全量重排**
     *   ⇒ 用户在屏幕上看到的就是"窗口跳位置 / 凭空消失"。
     *   所以这一行日志 = 直接抓现行。
     */
    private fun dumpGeometry(self: Any) {
        val divs = runCatching {
            (readField(self, "mDividerPosition") as? List<*>)?.map { it as? Int }
        }.getOrNull()
        val stages = runCatching {
            (readField(self, "mStageBounds") as? List<*>)
        }.getOrNull()

        if (divs != null) {
            Log.i(TAG, "    ◆ mDividerPosition=$divs")
        }
        if (stages != null) {
            val txt = stages.mapIndexed { i, r ->
                val s = runCatching {
                    val left = r!!.javaClass.getField("left").getInt(r)
                    val right = r.javaClass.getField("right").getInt(r)
                    val top = r.javaClass.getField("top").getInt(r)
                    val bottom = r.javaClass.getField("bottom").getInt(r)
                    val valid = r.javaClass.getMethod("isValid").invoke(r) as? Boolean ?: false
                    "[$i]($left..$right,$top..$bottom)${if (valid) "" else "★INVALID"}"
                }.getOrNull() ?: "[$i]?"
                s
            }
            Log.i(TAG, "    ◆ mStageBounds=$txt")
        }
    }

    private fun readField(obj: Any, name: String): Any? = runCatching {
        val f = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.get(obj)
    }.getOrNull()

    /** 调用栈前缀白名单（与 [SplitProbe] 同源思路）—— 只留真正相关的包，压 8 帧 */
    private val STACK_KEEP = listOf(
        "com.android.wm.shell",
        "com.android.systemui",
        "com.miui",
        "com.android.server",
        "cn.dsr213",
    )

    /**
     * 精简调用栈：回答"**是谁在调它**"。
     * ⚠️ 跳过探针自己的帧，否则每行开头都是自己，白占位置。
     */
    private fun callStack(): String = runCatching {
        Thread.currentThread().stackTrace
            .asSequence()
            .drop(1)
            .filter { f -> STACK_KEEP.any { f.className.startsWith(it) } }
            .filterNot { it.className.startsWith("cn.dsr213.hyperplus.module.SplitGeomProbe") }
            .take(8)
            .joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName }
    }.getOrDefault("")

    private fun fmt(a: Any?): String = when (a) {
        null -> "null"
        is Int, is Long, is Short, is Byte, is Boolean, is Float, is Double -> a.toString()
        is String -> "「${a.take(48)}」"
        is Collection<*> -> "${a.javaClass.simpleName}(size=${a.size})"
        is Array<*> -> "Array[${a.size}]"
        is IntArray -> "int[${a.size}]"
        else -> a.javaClass.simpleName
    }

    /** 把 Rect 打成人读形式 `(l..r,t..b)`；非 Rect 退化成 fmt */
    private fun rectStr(r: Any?): String {
        if (r == null) return "null"
        val l = readField(r, "left") as? Int
        val t = readField(r, "top") as? Int
        val rr = readField(r, "right") as? Int
        val b = readField(r, "bottom") as? Int
        return if (l != null && rr != null) "($l..$rr,$t..$b)" else fmt(r)
    }

    // ================================================================ 开关

    private fun isOn(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY, 0) != 0
    }.getOrDefault(false)
}

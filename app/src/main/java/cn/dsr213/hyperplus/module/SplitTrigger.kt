package cn.dsr213.hyperplus.module

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppToast
import cn.dsr213.hyperplus.EngineText
import cn.dsr213.hyperplus.ForegroundProbe
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.SplitThresholds
import cn.dsr213.hyperplus.SplitUnfoldDirection
import io.github.libxposed.api.XposedInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ★★★ **功能 1：轻折一下 → 分屏 +1**（2026-10-04 新建，**本模块第二个方法 hook**）。
 *
 * ============================ 它做什么 ============================
 * 用户规格（`docs/分屏增强_实现方案_2026-10-04.md` §0）：
 * **轻折一下**，把分屏数 +1（只要还没到上限）。且 §5.1 定成「**只做触发器**」——
 * 其余（回桌面 / 选图标 / 填槽）全部交回系统。
 *
 * ============================ 它怎么做到的（全部来自实测，不是猜） ============================
 * §1.11 的观察钩子把系统自己走的那条链抓了出来；§1.11.6 + jadx 反编译把参数语义钉死：
 *
 * ```
 * 三指滑（系统原生）
 *   ShellTaskOrganizer.updateSplitSnapTarget(pos, taskId, x, z)
 *     → SoScSplitScreenController.updateSplitSnapTarget(pos, taskId, x, z)
 *       → 【非分屏态】SoScSplitScreenController.splitPrimaryTask(pos, taskId)   ← ★ 我们直接调这个
 *         → SoScStageCoordinator.startTaskInSoScByResizeEnter(taskId, pos, 1)
 * ```
 *
 * 三条硬事实（都用 jadx 反编译出的 Java 源码逐字核对过）：
 * ① `splitPrimaryTask(int pos, int taskId)` 是 **public**、**返回 boolean** ⇒ 不用改可见性、**能回读成败**；
 * ② 它的第一行就是 `if (isSplitScreenVisible()) return false;` ⇒ **它天然只会做 0→2**
 *    （已有分屏时自动拒绝）—— 这正是我们要的"准入"，不必自己再实现一道；
 * ③ `taskId` 是**外部传进来的**（`mTaskOrganizer.getRunningTaskInfo(taskId)` 按 id 取），
 *    系统三指滑时算好送进来 ⇒ 我们得自己算 —— 办法见 [focusedTaskId]。
 *
 * ⛔ **不采用**的两条路（都实测/反编译证过）：
 * - `wm shell SoScSplitScreen updateSplitSnapTarget <i>`：它的 taskId **硬编码 -1**
 *   （`ShellCommandHandler.runUpdateSplitSnapTarget` 实测源码：`updateSplitSnapTarget(i, -1, -1, z)`），
 *   拿不到任务 ⇒ 空转；
 * - `wm shell SoScSplitScreen startSplitWithIntentsForMiui`：**崩过 SystemUI**（§1.6.2）。
 *
 * ============================ 三档开关（都不是可选） ============================
 * `Settings.Global` 键 [KEY]：
 * ```
 * 0 = 关（默认）—— 不挂 hook、绝不进分屏
 * 1 = 只观察    —— 打轨迹日志（**定标与调参就靠它**），⛔ 绝不真的进分屏
 * 2 = 真动作    —— 判定成立时才调 splitPrimaryTask
 * ```
 * ⇒ 默认必须是 0：这个功能会**真的改变用户屏幕**，不能默认打开。
 *
 * ⚠️ **0 档也注册折角传感器**：这一行原本是 2026-10-04 晚为了「角度校准」能用才改的
 *   （校准已随功能一起下线，见下），现在留着它是因为**成本可忽略**
 *   （`hinge_angle` 是 on-change 传感器，实测静止时零回调）。
 *   ⛔ 但它**不构成任何能力**：0 档既不装控制器也不挂 hook ⇒ 判定就算成立，
 *      [onTrigger] 也只会安静放弃。
 *   ⇒ 准确说法：**0 档 = 不挂 hook、不进分屏；传感器照常注册。**
 *
 * ============================ ★★★ 阈值是**编译期常量**（2026-10-06 起） ============================
 * 判定状态机的四个输入量（D1/D2/D3/窗口）住在 [SplitThresholds.DEFAULT]，**引擎只读它**。
 *
 * 🔴 改这里之前先读这段历史：2026-10-04 晚它们曾是**用户配置** —— 由「角度校准」页测出来、
 *   用户按「确认」生效，住 `AppPrefs.splitCalib` + `PrefsBridge.SPLIT_CALIB`（这两个符号**都已删**，
 *   此处只作历史追溯 —— ⛔ 别顺着这两个名字去 grep 代码，它们已经不在了）。
 *   用户 **2026-10-06** 原话：「**把角度校准功能删掉，不给这么多自定义功能，越多越难做**」
 *   ⇒ 整条校准链（界面 / 请求键 / 引擎采样 / 结果串 / 「校准期抑制分屏」那套护栏）**全部删除**，
 *     阈值退回常量。
 *   ⛔ **别把校准加回来**（已否一次）。要让阈值可调，就是改 [SplitThresholds.DEFAULT] 重新编译。
 *   ⚠️ 2026-10-04 上午那几个 `Settings.Global` 键（`hyperplus_split_d{1,2,3}` / `_win_ms`）是更早的
 *     脚手架，**同样已退役、这里一个都不读**；⛔ 别再往那边写 —— 写了没有下游，
 *     而且会让"真值到底在哪"变成两个答案。
 *
 * ============================ 那套「校准期抑制分屏」的护栏去哪了 ============================
 * 2026-10-04 晚它有两层（[onAngle] 入口截样本 + [onTrigger] 出口硬闸，外加"折回展开位就提前
 * 解除"的尾巴）：因为"用户照着界面上提示折的那一下"与"真·轻折"在**数据上完全一样**，
 * 判据层面分不开，只能靠"这段时间归校准所有"这个外部事实来抑制。
 * ⇒ **它随校准一起删除**：没有校准动作，就没有"必须被抑制的那一下折"。
 * ⚠️ 万一将来又出现同类需求（"某段时期内的样本不该判触发"），⛔ 别照着旧版重写：
 *   旧版为"护栏要压多久"付过两次代价（固定 3 秒太死、`maxOf` 把截止时刻锁死改不短）。
 *
 * ============================ 纪律（同 HyperPlusModule：本代码跑在 SystemUI 里） ============================
 * - **每一处** runCatching 兜住：本进程任何未捕获异常 = 状态栏崩溃；
 * - 传感器监听挂在**自建 HandlerThread** 上：判定里要读反射、要打日志，不该占 SystemUI 主线程；
 * - 反射**不改可见性**（`splitPrimaryTask` 本来就是 public）；
 * - 拿不到实例 / 拿不到 taskId ⇒ **安静放弃**（一行日志），绝不重试、绝不提示用户。
 */
internal object SplitTrigger {

    /**
     * ⚠️ TAG 与探针刻意分开：探针是 `HyperPlusSplitProbe`，本类是产品代码用 `HyperPlusSplit`
     *   ⇒ `adb logcat | grep HyperPlusSplit` 能同时看到两者。
     */
    private const val TAG = "HyperPlusSplit"

    /**
     * 三档开关键（见类注释）。默认 0 = 关。
     *
     * ⚠️ 值**引用 [PrefsBridge.SPLIT_TRIGGER]**，不在这里写第二份字面量 ——
     *   App 侧（主页那条"整个模块启用了哪些增强"的汇总）也要读它，
     *   两处各写一份的后果是改键名时漏一处、而且**不报错**。
     */
    const val KEY = PrefsBridge.SPLIT_TRIGGER

    // ---- 判定参数 ----
    // ⚠️ 四个阈值**不在这里**，在 [SplitThresholds.DEFAULT] —— 它现在是**编译期常量**
    //   （校准已下线，见类注释）。本文件刻意**一个阈值字面量都没有**：
    //   留一份默认值在这里的后果是"内置默认值"有两个真身，那正是本工程最忌讳的事。

    /** 只把 > 这个值的角度当作"展开态"样本。低于它说明手机合着/半折，不该进基线窗口。 */
    private const val OPEN_MIN = 150f

    /** 两次触发之间的最小间隔（§6 第 6 步的"冷却"） */
    private const val COOLDOWN_MS = 1_500L
    // ⚠️ 基线窗口宽度（`A0_WINDOW_MS`）与滤噪阈值（`EPS`）**已搬进 [FoldJudge]**
    //   —— 基线窗口现在是判定状态的一部分（理由见那边的类注释「基线窗口为什么住在这里」）。
    //   ⛔ 别再在本文件里写第二份：两个真身的后果是"日志按 8s 算、判定按别的算"。

    private const val CTRL_CLASS = "com.android.wm.shell.sosc.SoScSplitScreenController"

    /**
     * ★★ `ShellTaskOrganizer` —— **捕获控制器的真正入口**（2026-10-04 第二轮实测后定案，理由见 [installCapture]）。
     * 为什么是它：它持有 `mSplitScreenListener`（ArrayList），而控制器实现了
     * `ShellTaskOrganizer.MiuiShellTaskListener` 并注册在里面 ⇒ **拿到它就等于拿到控制器**。
     */
    private const val ORG_CLASS = "com.android.wm.shell.ShellTaskOrganizer"

    /**
     * ⭐⭐⭐ 【2→3 与 3→6 的落点】多分屏的**真身执行体**类名 —— `MultipleSplitRootTaskOrganizer`。
     *
     * 它是**用户「拖到多分屏图标」时系统走的那条链**的落点（跨进程入口
     * `IMultiTaskingStateManager.dockSplitFromRecent` → 同进程 `MultipleSplitController`
     * → **本类** `dockMultipleSplitTasks` → `dockSoScTasks()` / `dockMultipleTasks()`）。
     * ⇒ ★ 我们**折一下就走同一段代码** —— 这是用户 2026-10-04 定的
     *   「直接走原生链路，我们折一下只是除了拖动触发以外新的触发方式」的落地方式。
     * ⚠️ 只用来做**类型名比对**（[msplitOrganizerOrNull]），⛔ 不 `Class.forName` 它 ——
     *   我们是从 `ShellTaskOrganizer.mTaskListeners` 里**认出**它的（那才是活体）。
     */
    private const val MSPLIT_ORG_NAME = "com.android.wm.shell.multiplesplit.MultipleSplitRootTaskOrganizer"

    /**
     * `dockMultipleSplitTasks(Bundle)` 收的那个 Bundle 的键名。
     *
     * ✅ 逐字取自 `MultipleSplitOrganizer`：
     *   `OPTION_DOCK_TASKS = "multiple_split_dock_task"` ⇒ **装一个 `RunningTaskInfo`**
     *   （不是 taskId 的 int！见 `dockMultipleSplitTasks` 里 `bundle.getParcelable(…)`）。
     * 🔴🔴 **装的是「分屏的 root 任务」，不是被拖的那个 App** —— 2026-10-05 实测打脸更正，
     *   见 [splitRootTaskInfoOrNull]（官方 `runDockTasks` L77 装 `splitRootTaskInfo`）。
     * ⚠️ 另几个 `OPTION_DOCK_*`（bounds / offset / radius / orientation）是**系统回给桌面**
     *   用来画那条 dock 带子的参数 ⇒ **我们不用填**（`getParcelable` 只读 DOCK_TASKS）。
     */
    private const val KEY_DOCK_TASKS = "multiple_split_dock_task"

    /**
     * 多分屏的**上限格数**所在的系统设置键（2026-10-05 新增，A3）。
     *
     * ✅ 本机实测：`settings get global miui_multiple_split_resolved_max_stages` ⇒ **6**
     *   来源（jadx 逐字）：`MultipleSplitOrganizer.MAX_STAGES = resolveMaxStages(getTotalRamGb(),
     *   SystemProperties.get("persist.sys.multiple.split.max_stages", "-1"))`，
     *   常量 `DEFAULT_HIGH_RAM_MAX_STAGES = 6` / `DEFAULT_LOW_RAM_MAX_STAGES = 4`。
     * ⇒ ⛔⛔ **绝不许硬编码 6**：低内存机型是 4，且用户/厂商可以用 `persist.sys.*` 覆盖。
     *   [maxStagesOrNull] 读不到就返 null ⇒ 调用方**跳过上限闸**（宁可让系统自己拒，
     *   也不要拿一个编出来的上限去拦用户）。
     */
    private const val KEY_MAX_STAGES = "miui_multiple_split_resolved_max_stages"

    /**
     * 上限提示的去重间隔（毫秒）。
     * ⚠️ 只在**判定为"到顶"**时写一次；用户连续折着玩不该刷屏（震动/提示各有成本）。
     */
    private const val LIMIT_NOTIFY_MS = 2_000L

    /**
     * ★★★ **温度观察者**（2026-10-05 新增）—— 高温保护的唯一真身。
     *
     * ✅ jadx 逐字（`MultiTaskingTemperatureObserver.java`）：
     * ```java
     * private static final int HIGH_TEMPERATURE = 47;
     * private static final int NORMAL_TEMPERATURE = 45;
     * private float mBoardTemperature;
     * private boolean mIsHighTemperature;
     * ```
     * 源文件 `/sys/class/thermal/thermal_message/board_sensor_temp`（毫摄氏度，代码 `/1000.0f`），
     * `FileObserver` 盯它、`onEvent(i == 2)`(MODIFY) 就重读（**不是轮询**）。
     *
     * ★★ 它被 `MultipleSplitRootTaskOrganizer` 在 **6 处**读（见表），其中三处是**闸门**：
     *   ① `dockMultipleTasks()` 第一道（"折一下加一格"走这里）
     *   ② `dockSoScTasks()` 第一道（SoSc 那条路）
     *   ③ `startTaskInSplit` 那道（往格子里塞 App 时）
     *   ⇒ 板温 > 47°C 时**"加一格"直接被拒**并 toast（`multi_tasking_temperature_block_toast`）。
     */
    private const val TEMP_OBSERVER_CLASS =
        "com.android.wm.shell.multitasking.common.performance.MultiTaskingTemperatureObserver"

    /**
     * ★★★ 观察者上那个**只读闸门**的方法名 —— **2026-10-06 修正后的唯一正确落点**。
     *
     * ================= 为什么不是改字段（10-05 那版错在哪） =================
     * 10-05 的实现在 `onTemperatureChanged()` 之后把**字段** `mIsHighTemperature` 覆写掉。
     * 2026-10-06 反汇编 `MultiTaskingTemperatureObserver.onTemperatureChanged` 逐条读出：
     * ```
     * mBoardTemperature = getTemperatureFromFile(...)
     * if (!mIsHighTemperature && mBoardTemperature > 47.0f) {      // ← 上升沿检测
     *     mIsHighTemperature = true
     *     for (l : listeners) l.onHighTemperature(mBoardTemperature)
     * } else if (mIsHighTemperature && mBoardTemperature <= 45.0f) { // ← 下降沿检测
     *     mIsHighTemperature = false
     *     for (l : listeners) l.onNormalTemperature(mBoardTemperature)
     * }
     * ```
     * ⇒ 🔴🔴 **那个字段不是"给下游读的状态"，而是这两条 if 的判据本身**（上升沿检测器）。
     *   把它恒置 `false` ⇒ `!mIsHighTemperature` **恒真** ⇒ 板温每变化一次就重放一次
     *   "进高温"分支 ⇒ **反复通知** `MultipleSplitRootTaskOrganizer.onHighTemperature()`
     *   ⇒ 每次都重新 `executeDelayed(mExitMultipleSplit, 10000)`
     *   ⇒ **多分屏每 10 秒被系统收掉一次**（实测日志：`dialogShown=false` 反复出现，
     *     间隔离散但成串）。
     *   ⭐ 也就是：那版"关掉保护"**不但没关，还把一个一次性动作放大成了持续动作**。
     *
     * ================= 正确的两个落点 =================
     * ① 本站（观察者的**方法** `isHighTemperature()`）—— 它 `return mIsHighTemperature`，
     *    是**只读**的，系统内部**不调用它**（`onTemperatureChanged` 直接 `iget` 字段）。
     *    ⚠️ 全 dex 只有 `MultipleSplitRootTaskOrganizer` 的 **5 处**调它，全是"下游闸门"：
     *    `dockMultipleTasks` / `dockSoScTasks` / 一处 `startTaskInSplit` 型入口、
     *    以及两处列表长度守卫（`size() < 3` 那两个）。
     *    ⇒ 改它的返回值 = **只影响下游判定，不破坏上游时序** ✅
     * ② [MSPLIT_ON_HIGH_TEMP] / [MSPLIT_ON_HIGH_TEMP_DIALOG_READY] —— 高温的**动作端**
     *    （弹窗 + 排 10 秒后收分屏），见 [splitHighTempHooker]。
     */
    private const val TEMP_METHOD_IS_HIGH = "isHighTemperature"

    /**
     * 读**真实**板温（℃）。★ 我们只读不写 —— 这是"仪表不得改变被测量"的体现：
     *   [TEMP_METHOD_IS_HIGH] 被我们改写的是**判定结论**，而原始读数永远是真值，
     *   ⇒ 诊断页 / 日志里看到的温度依旧可对照官方温控
     *   （`/sys/class/thermal/thermal_message/board_sensor_temp`）。
     */
    private const val TEMP_METHOD_BOARD = "getBoardTemperature"

    /**
     * 高温链在 `MultipleSplitRootTaskOrganizer` 上的**两个动作入口**。
     *
     * ✅ 反汇编逐字（本机 `Miui-WindowManager-Shell.jar`）：
     * ```
     * onHighTemperature(float temperature):            // 参数就是当时板温
     *     Slog.i("onHighTemperature: temperature=…, dialogShown=…")
     *     if (!MultipleSplitUtils.getInstance().isMultipleSplitActive()) return
     *     if (mHighTemperatureDialogShown) return        // 它自己的防抖
     *     mHighTemperatureDialogShown = true
     *     getMainExecutor().executeDelayed(mExitMultipleSplit, 10000)   // ← 10 秒后收掉多分屏
     *     getMainExecutor().execute(弹对话框)              // → onHighTemperatureDialogReady()
     *
     * onHighTemperatureDialogReady():
     *     getMainExecutor().execute(() -> {              // 对话框就绪后**重新计时**
     *         if (!isMultipleSplitActive()) return
     *         removeCallbacks(mExitMultipleSplit)
     *         executeDelayed(mExitMultipleSplit, 10000)
     *     })
     * ```
     * ⇒ 两条都要拦：只拦前一条，对话框那条仍会把退出定时补回来。
     * ⚠️ 这**不是** hook 系统"内部实现细节"：这两个方法就是它对**温度监听器接口**
     *   （`MultiTaskingTemperatureObserver.MultiTaskingTemperatureListener`）的实现体，
     *   属于**契约面**（观察者按名字回调它们）。
     */
    private const val MSPLIT_ON_HIGH_TEMP = "onHighTemperature"
    private const val MSPLIT_ON_HIGH_TEMP_DIALOG_READY = "onHighTemperatureDialogReady"

    /**
     * 撤销"已排上的高温退出"所需的两个私有字段（[splitHighTempHooker] 里用）。
     * ⚠️ ⚠️ `mExitMultipleSplit` **不只被高温预约** —— `onLowMemory()` 也会
     *   `removeCallbacks + executeDelayed` 它（反汇编 11175 行起）。
     *   ⇒ 撤销是**有代价**的：极端情况下（低内存与高温同时）会顺带撤掉那次低内存退出。
     *   我们仍然做，理由：用户点的是"**关掉过热保护**"，留一个"还会自己收掉"的尾巴
     *   等于没关干净；而低内存那条路下一轮内存压力会自己再来。
     *   ⛔ 别把这个取舍说成"完全无副作用"。
     */
    private const val RTO_FIELD_TRANSITIONS = "mTransitions"
    private const val RTO_FIELD_EXIT_RUNNABLE = "mExitMultipleSplit"

    /**
     * 温度观察者实例（从 hook 的 `this` 拿；取到即缓存 —— 诊断用，也省掉重复判类型）。
     * ⚠️ 它是 [TEMP_OBSERVER_CLASS] 的实例，只用来做**类型名比对**，不 `Class.forName`。
     */
    @Volatile private var tempObserver: Any? = null

    /**
     * 只在 [ORG_CLASS] 上挂这几个「任务生命周期」方法 —— 它们**运行期一定会被调到**
     * （`onTaskInfoChanged` 在探针实测里是整个 ShellTaskOrganizer 命中最多的那个）。
     * ⛔ 别扩成"整个类的全部方法"：那是探针干的事，产品代码不需要。
     */
    private val ORG_HOOK_NAMES = setOf(
        "onTaskInfoChanged", "onTaskAppeared", "onTaskVanished",
        "updateTaskListenerIfNeeded", "addMiuiShellTaskListener",
    )

    /** 捕获到的 WMShell 控制器实例（构造器 hook 存下；只读） */
    @Volatile private var controller: Any? = null

    /** 从 [controller] 的 `mTaskOrganizer` 字段反射拿到（取一次并缓存） */
    @Volatile private var taskOrganizer: Any? = null

    /** 多分屏真身（n≥2 追加一格用；见 [msplitOrganizerOrNull]，取到即缓存） */
    @Volatile private var msplitOrganizer: Any? = null

    /**
     * 多分屏**上限格数**的缓存（读一次就够 —— 它是 `persist.sys.*` + 内存总量算出来的，
     * 运行期不会变）。`0` = 还没读过；读不到会**每次都重试**（镜像可能晚一点才就绪）。
     * ⚠️ 用 `0` 当"未读"而不是 `-1`：格数恒 ≥ 2，`0` 不可能是一个真值。
     */
    @Volatile private var maxStagesCache = 0

    /** 上次给用户发过「到上限了」提示的时刻（去重，见 [LIMIT_NOTIFY_MS]） */
    @Volatile private var lastLimitNotifyMs = 0L

    // ★★ 这里原来有一个 `limitOverlay: SplitLimitOverlay?` 字段（自绘屏上提示条）。
    //   2026-10-05 用户点名「把 toast 改成系统原生样式」⇒ 改用 `android.widget.Toast`
    //   （见 [AppToast]）⇒ 那个自绘窗口连同 `SplitLimitOverlay.kt` 整个删除，字段也随之消失。
    //   ★ 为什么系统 Toast 能替代它的全部职责：
    //     ① 「App 不在前台也能看见」—— 由"引擎跑在 SystemUI 系统进程"保证（Toast 的
    //        `Suppressing` 只针对后台**普通应用**，系统进程不受限；2026-10-05 实测见 [AppToast]）；
    //     ② 「2 秒自动消失」—— `Toast.LENGTH_SHORT` 天然如此；
    //     ③ 「不吃触摸」—— Toast 由系统 `NotificationManagerService` 持有，本就不拦输入。
    //   ⛔ 别再把这个自绘浮层加回来（用户已明确否决"照抄外观"路线、要求走系统 API）。

    /**
     * ★★★ **「一次追加正在飞」的闸**（B3，2026-10-05 新增）。
     *
     * ==================== 为什么冷却 [COOLDOWN_MS] 不够 ====================
     * 追加那条链是**异步**的：`onTrigger` 把 `doSplit` 投到 WMShell 主线程就立刻返回，
     * 而 `lastTriggerMs` 记的是**投递时刻**。于是存在这个窗口：
     * ```
     * t=0     折一下 ⇒ 投递 doAppend（记 lastTriggerMs = 0）
     * t=200   dockMultipleSplitTasks 正在跑（系统要缩带子、置桌面、动画）
     * t=1600  冷却到期 ⇒ 用户又折一下 ⇒ ★ 又一个 doAppend 压上去
     * ```
     * 而 `dockMultipleSplitTasks` 在**上一次还没跑完**时被重入，后果不是"多加一格"
     * （那还算好的），而是 `mSplitRootTaskInfo` 被换成中间态 ⇒ 拿到的 root info 是脏的。
     * ⇒ 用一道**真并发闸**（而不是时间闸）把"一次没飞完就不许再起飞"钉死。
     * ⚠️ 判据必须是"**上一次真的做完了**"（[doSplit] 整个函数体跑完），不是"投递出去了"。
     * ⚠️ 它是 `@Volatile`：写来自 WMShell 主线程（`finally`），读来自传感器线程（[onTrigger] 前的判断）。
     */
    @Volatile private var appending = false

    /** 宿主 Context（传感器回调里要用它读开关/配置） */
    @Volatile private var hostCtxRef: Context? = null

    private var installed = false

    /**
     * 是否「只观察」模式（模式 1）。
     *
     * ★ 为什么要在**每次样本**上打原始角度：模式 1 的说明书就是「**定标与调参就靠它**」
     *   （见类注释的三档开关）。而定标要的是**完整轨迹**（全展 → 折到 90° → 合上各是多少度），
     *   只在状态翻转时打日志**采不到三个关键角度**。
     * ⚠️ 另有一条现成的路子被实测否掉：`dumpsys sensorservice` 的 `hinge_angle` 段是
     *   **10 个事件的环形缓冲**（on-change 传感器）⇒ 折快了必然丢段，不足以定标。
     * ⇒ 所以轨迹日志**必须**由本类自己出。
     */
    private var observeOnly = false

    /** 轨迹日志限流：角度至少变了这么多、或静默这么久，才打一行 */
    private var lastLoggedAngle = Float.NaN
    private var lastLoggedMs = 0L

    // ================================================================ 安装

    /**
     * 由 [HyperPlusModule.doBoot] 调用。
     *
     * ★★ **2026-10-04 晚改了门控**：传感器与校准通道**无条件装**，不再受"0 档就整个返回"拦截
     *   （理由见类注释里那段更正）—— 「角度校准」必须在功能还没打开时就能用。
     *   ⛔ 但 hook（真正有风险的那部分）仍然只在 1 / 2 档装。
     *
     * @param module  框架接口 —— 捕获 WMShell 实例要靠 hook 它的构造器（§4 方案 A）
     * @param hostCtx 宿主（SystemUI）Context：拿 SensorManager、读开关与阈值
     */
    fun install(module: XposedInterface, hostCtx: Context, classLoader: ClassLoader?) {
        if (installed) {
            Log.i(TAG, "已装过，跳过")
            return
        }
        hostCtxRef = hostCtx
        val mode = mode(hostCtx)
        observeOnly = mode == 1

        val modeTag = when (mode) {
            1 -> " = 只观察，不会真的进分屏"
            2 -> " = 真动作"
            else -> " = 关闭（只装传感器，不进分屏）"
        }
        Log.i(TAG, "===== 分屏触发器开始安装（模式=$mode$modeTag）=====")

        // ① 注册折角传感器 —— **无条件**（理由见类注释那条更正：注册成本可忽略，
        //   但它在 0 档**不构成任何能力** —— 没有控制器、没有 hook，判定成立也动不了屏幕）
        runCatching { installHinge(hostCtx) }
            .onFailure { Log.e(TAG, "注册折角传感器失败（已吞掉）", it) }

        // ③ 高温保护改写（2026-10-05）—— **无条件装**（它与三档开关无关：这是"改厂商的
        //   保护阈值"，不是"分屏增强本身"。用户在界面上设了温度上限就该生效，
        //   哪怕他没打开"轻折一下"那条触发链）。
        runCatching { installThermalHook(module, classLoader) }
            .onFailure { Log.e(TAG, "安装高温保护改写失败（已吞掉）", it) }
        runCatching { logThermalOnChange() }
            .onFailure { Log.e(TAG, "订阅高温配置失败（已吞掉）", it) }

        if (mode <= 0) {
            Log.i(
                TAG,
                "分屏触发器处于关闭档（$KEY=0）：已装好折角传感器，" +
                    "**不挂任何 hook、不会进分屏**（要开：adb shell settings put global $KEY 1 然后重启系统界面）",
            )
            installed = true
            Log.i(TAG, "===== 分屏触发器安装完成（关闭档）=====")
            return
        }

        // ② 捕获 WMShell 实例（唯一"脏活"，见方案 §4 方案 A）—— 只有真要用时才挂
        val ok = runCatching { installCapture(module, classLoader) }
            .onFailure { Log.e(TAG, "捕获控制器失败（已吞掉）", it) }
            .getOrDefault(false)
        if (!ok) Log.w(TAG, "⚠️ 没能捕获控制器 ⇒ 本功能无法动作（观察日志仍会输出）")

        installed = true
        Log.i(TAG, "===== 分屏触发器安装完成 =====")
        // ★★★ 阈值**只可能**是这一个 —— 校准已下线（见类注释），没有第二条路径能改它
        //   ⇒ 这行日志可以放心当"引擎到底按哪组数判"的证据。
        //   🔴 2026-10-04 晚曾被旧版带偏过：那时它打印的是**镜像回读之前**的值，
        //      日志说 20/6/90/1500、实际用的是用户校出来的 12/5/65/1500。
        //      那一类歧义随校准一起消失了 —— 现在它就是 [SplitThresholds.DEFAULT]。
        val th = SplitThresholds.DEFAULT
        Log.i(
            TAG,
            "判定阈值：${th.encode()}（D1 触发折深=${th.d1}° / D2 回到基线=${th.d2}° / " +
                "D3 合上判定=${th.d3}° / 窗口=${th.winMs}ms；" +
                "A0 自动 = 最近 ${FoldJudge.A0_WINDOW_MS / 1000}s 内最大角度）",
        )
    }

    // ================================================================ 高温保护改写

    /**
     * ★★★ **改写系统的多分屏高温保护**（2026-10-05 新增；**2026-10-06 换落点重写**）
     *   —— 用户点名要的功能。
     *
     * ============================ 用户要的是什么 ============================
     * 用户原话：「在app里添加**修改温度上限**的输入框，**禁止超过60度**，并用**红字**标注警告
     * "该操作存在风险，请谨慎修改"，下面要配一个**恢复默认**的按钮；再加一个**关闭过热保护的
     * 开关**，加一个**二次确认弹窗**，弹窗里面写清楚**关闭过热保护可能造成不可逆的风险**。」
     *
     * ============================ 改哪两个落点（10-06 定案） ============================
     * 高温保护在框架里是**两段**，必须**各拆一处**，缺一段就会漏：
     * ```
     *   ① 读数段：MultiTaskingTemperatureObserver.isHighTemperature()      ← 5 处下游闸门读它
     *   ② 动作段：MultipleSplitRootTaskOrganizer.onHighTemperature(float)  ← 弹窗 + 10 秒后收分屏
     *             MultipleSplitRootTaskOrganizer.onHighTemperatureDialogReady()
     * ```
     * ⛔ **绝不许**再回头去改 `MultiTaskingTemperatureObserver.mIsHighTemperature`
     *   那个字段 —— 它是 `onTemperatureChanged` 里**上升沿/下降沿检测的判据**，
     *   改它等于"每来一次板温变化就重放一次高温通知"，10-05 那版就是这么把
     *   "一次性退出"放大成"每 10 秒退出一次"的。理由与反汇编原文见 [TEMP_METHOD_IS_HIGH]。
     *
     * ============================ 判定规则（[wantHigh]） ============================
     * 1. 关掉保护（[AppPrefs.splitThermalGuardOff] == true）⇒ **一律不算高温**
     *    （闸门放行"加一格"，且高温的弹窗与退出定时都不发生）。
     * 2. 否则 ⇒ `板温 > 用户设的上限`（默认 47，界面可调 30~60）。
     *
     * 🔴🔴 **这是本模块唯一一处"拆掉厂商安全保护"的代码** —— 它只在用户
     *   **在界面上明确改过**的情况下才会与出厂行为不同。⛔ 别给它加任何"自动"
     *   或"记住上次"的行为；界面侧那次二次确认不是可选项（见 `SplitThermalCard`）。
     *
     * ⚠️ 与 [installCapture] 那套"两条路"不同，这里**全是方法 hook**。
     *   理由：`MultiTaskingTemperatureObserver` 是 WMShell Dagger 图里的一个单例，
     *   而这两个方法都是实例方法 —— **方法 hook 是探针实测过数百次的
     *   安全模式**（对象早已存在，我们只是插一脚）。⛔ 不需要也不该去 hook 构造器：
     *   那要抢在 Dagger 建图之前，而本模块刻意晚 4 秒启动（见 `HyperPlusModule`）。
     */
    private fun installThermalHook(module: XposedInterface, classLoader: ClassLoader?) {
        val loaders = buildList {
            classLoader?.let { add(it) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
        }.distinct()

        // ---------- 落点 ①：观察者上的只读闸门 isHighTemperature() ----------
        val obsCls = resolve(TEMP_OBSERVER_CLASS, loaders)
        if (obsCls == null) {
            Log.w(
                TAG,
                "🌡 类未找到：$TEMP_OBSERVER_CLASS ⇒ 高温保护无法改写" +
                    "（换 ROM / 升级后包名可能变）。用户设的温度上限与开关**都不会生效**。",
            )
        } else {
            var n = 0
            for (method in obsCls.declaredMethods) {
                if (method.name != TEMP_METHOD_IS_HIGH) continue
                val ok = runCatching {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(isHighTemperatureHooker)
                }.isSuccess
                if (ok) n++
            }
            if (n == 0) {
                Log.w(TAG, "🌡 ① 未钩上 $TEMP_OBSERVER_CLASS.$TEMP_METHOD_IS_HIGH ⇒ 「加一格」闸门拆不掉")
            } else {
                Log.i(TAG, "🌡 ① 已钩上 $TEMP_METHOD_IS_HIGH（$n 个重载；闸门读数跟随用户配置）")
            }
        }

        // ---------- 落点 ②③：RTO 上两个"高温动作"入口 ----------
        val rtoCls = resolve(MSPLIT_ORG_NAME, loaders)
        if (rtoCls == null) {
            Log.w(
                TAG,
                "🌡 类未找到：$MSPLIT_ORG_NAME ⇒ 「弹窗 + 10 秒收分屏」拦不住" +
                    "（换 ROM / 升级后包名可能变）",
            )
            return
        }
        var m = 0
        for (name in listOf(MSPLIT_ON_HIGH_TEMP, MSPLIT_ON_HIGH_TEMP_DIALOG_READY)) {
            for (method in rtoCls.declaredMethods) {
                if (method.name != name) continue
                val ok = runCatching {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(splitHighTempHooker)
                }.isSuccess
                if (ok) m++
            }
        }
        if (m == 0) {
            Log.w(TAG, "🌡 ②③ 未钩上 $MSPLIT_ORG_NAME 的高温入口 ⇒ 高温仍会弹窗并收掉多分屏")
        } else {
            Log.i(
                TAG,
                "🌡 ②③ 已钩上高温入口（$m 个：$MSPLIT_ON_HIGH_TEMP / $MSPLIT_ON_HIGH_TEMP_DIALOG_READY）",
            )
        }
    }

    /**
     * 落点① —— `MultiTaskingTemperatureObserver.isHighTemperature()`。
     *
     * ★ 原实现就是一行 `return mIsHighTemperature`（反汇编仅 3 单元）—— 纯**只读**读数口。
     *   系统内部（`onTemperatureChanged`）读的是**字段**、**不走这个方法**
     *   ⇒ 我们接管它的返回值**不会**影响上升沿/下降沿检测
     *   ⇒ 从根上避免了 10-05 那版"反复重放高温 → 每 10 秒收一次分屏"的放大效应。
     *
     * ⚠️ 返回值必须是 `java.lang.Boolean`（目标签名是 primitive `boolean`，libxposed 负责拆箱）。
     * ⚠️ 全程 `runCatching`，任何异常都**放行原值** `r` —— 宁可没生效，也不许打断宿主。
     */
    private val isHighTemperatureHooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val r = chain.proceed()
            return runCatching {
                val self = thisObject(chain) ?: return r
                if (self.javaClass.name != TEMP_OBSERVER_CLASS) return r
                tempObserver = self
                val t = boardTemperatureOf(self) ?: return r
                java.lang.Boolean.valueOf(wantHigh(t))
            }.onFailure {
                Log.w(TAG, "🌡 闸门读数改写失败（已吞掉，放行原值）", it)
            }.getOrDefault(r)
        }
    }

    /**
     * 落点②③ —— RTO 的 `onHighTemperature(float)` 与 `onHighTemperatureDialogReady()`。
     *
     * ★ 判定按**用户配置**（[wantHigh]）：
     *   - 该当高温 ⇒ `proceed()` 放行，**完全走系统原逻辑**（用户没关保护时行为零变化）；
     *   - 不该当 ⇒ **不 `proceed()`**（方法体整个不执行）：既不弹对话框、也不排那个
     *     10 秒定时，并顺手**撤销已经排上的**退出（清掉旧版本留下的残留）。
     * ⚠️ 读不到板温时一律放行（宁可漏关一次，不可误关）。
     * ⚠️ 本 hooker 同时挂在两个方法上 ⇒ 用 `args` 是否为空区分（前者带 float，后者无参）。
     */
    private val splitHighTempHooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val t = runCatching {
                (chain.args.firstOrNull() as? Float) ?: boardTemperatureOf(thisObject(chain))
            }.getOrNull()

            if (t == null || wantHigh(t)) return chain.proceed()

            runCatching { cancelPendingHighTempExit(chain) }
                .onFailure { Log.w(TAG, "🌡 撤销已排的高温退出失败（已吞掉）", it) }
            // ★ 2026-10-07 措辞修正：原句只写「已拦下高温动作」——读不出**拦了什么**、
            //   也读不出**顺手做了什么**，而且跟后半句「过热保护已关闭」看着像打架
            //   （其实正相反：正因为用户把保护关了，我们才去拦系统那套）。
            //   实际动作有两件，写全：① 不 proceed ⇒ 系统那段不执行（不弹对话框、不排 10 秒退出定时）
            //   ② 同一次调用里把**可能已经排上**的那次退出撤销掉。
            Log.i(
                TAG,
                "🌡 已拦下系统的高温动作，并撤销已排的退出（不弹窗、不排定时；" +
                    "板温 ${fmtTemp(t)}°C，${thermalConfigDesc()}）",
            )
            return null
        }
    }

    /**
     * 撤销 RTO 上已排的 `mExitMultipleSplit`（那个 10 秒后收掉多分屏的定时）。
     *
     * ⚠️ 取舍见 [RTO_FIELD_EXIT_RUNNABLE] 的注释：同一个 Runnable 在低内存链上也会被用，
     *   极端情况下（低内存与高温同时）会顺带撤掉那次低内存退出。
     * ⚠️ 全程调用方 `runCatching`：字段名变了 / 被混淆了就静默跳过 ——
     *   退化成"只拦新动作、不撤旧的"，不影响主功能。
     */
    private fun cancelPendingHighTempExit(chain: XposedInterface.Chain) {
        val rto = thisObject(chain) ?: return
        val transitions = rto.javaClass
            .getDeclaredField(RTO_FIELD_TRANSITIONS).apply { isAccessible = true }
            .get(rto) ?: return
        val exit = rto.javaClass
            .getDeclaredField(RTO_FIELD_EXIT_RUNNABLE).apply { isAccessible = true }
            .get(rto) as? Runnable ?: return
        val executor = transitions.javaClass.getMethod("getMainExecutor").invoke(transitions) ?: return
        executor.javaClass.getMethod("removeCallbacks", Runnable::class.java).invoke(executor, exit)
    }

    /**
     * 算出**我们希望**的"此刻算不算高温"——整个功能的判定核心。
     *
     * ★ **两个落点共用它**（[isHighTemperatureHooker] 的闸门读数 + [splitHighTempHooker]
     *   的动作拦截）⇒ 两处**永远一致**：不会出现"闸门拆了、但分屏还在被收掉"这种半吊子状态。
     * ★ 写成不吃实例的纯函数（板温由调用方传入）⇒ 可直接单测，也便于审"阈值改没改对"。
     *
     * ⚠️ [AppPrefs.splitThermalGuardOff] 优先于 [AppPrefs.splitThermalLimitC]：
     *   关掉保护时**不看温度**（那个输入框此时不再起作用，
     *   见 [PrefsBridge.SPLIT_THERMAL_GUARD_OFF]）。
     * ⚠️ 用 `>` 而不是 `>=`：与出厂实现逐字一致（`mBoardTemperature > 47.0f`）——
     *   差一个等号会让"刚好等于上限"在两套判定下给出相反结论。
     */
    private fun wantHigh(t: Float): Boolean {
        if (AppPrefs.splitThermalGuardOff.value) return false
        return t > AppPrefs.splitThermalLimitC.value
    }

    /**
     * 读板温（**真值**，不受我们的改写影响 —— 这正是"仪表不改被测量"的体现）。
     * ★ 我们只改写 [TEMP_METHOD_IS_HIGH] 的**判定结论**，原始读数永远是真的
     *   ⇒ 诊断页与日志里看到的温度依旧可与官方温控对照。
     * ⚠️ 接受可空入参：两个 hooker 都可能拿不到 `this`（`thisObject` 返回 null）——
     *   那时返 null，调用方按"读不到 ⇒ 放行"处理。
     */
    private fun boardTemperatureOf(obs: Any?): Float? =
        obs?.let {
            runCatching { it.javaClass.getMethod(TEMP_METHOD_BOARD).invoke(it) as? Float }.getOrNull()
        }

    /** 日志用：把板温格式化成三位小数（与系统 `Slog` 里那份精度一致，便于对照） */
    private fun fmtTemp(t: Float): String = String.format(java.util.Locale.US, "%.3f", t)

    /** 日志用：当前两项配置的一句话描述 */
    private fun thermalConfigDesc(): String =
        if (AppPrefs.splitThermalGuardOff.value) {
            "过热保护已关闭"
        } else {
            "上限 ${AppPrefs.splitThermalLimitC.value}°C"
        }

    /**
     * 把**引擎实际在用**的温度配置在它**变化时**打一行（诊断用）。
     *
     * ★ 两条读盘路径都可能改它，（那时它与温度那条曾共用同一个订阅作用域），
     *   而"界面改了、引擎跟上没"最容易骗人。`StateFlow` 会把当前值重放一次
     *   ⇒ 顺便补上"启动时到底是多少"这一行。
     * ⚠️ 只打日志：⛔ 别在 collect 里顺手去调那两个 hooker 的改写逻辑 ——
     *   闸门改写需要一个**观察者实例**才能读到板温，而 collect 可能跑在配置镜像线程上。
     *   改写统一由 [isHighTemperatureHooker] / [splitHighTempHooker] 在**系统自己来调**时做
     *   （那时实例一定有，且天然对齐"下一次板温变化"）。
     */
    private fun logThermalOnChange() {
        logScope.launch {
            runCatching {
                AppPrefs.splitThermalLimitC.collect { c ->
                    Log.i(TAG, "🌡 温度上限已更新：$c°C（${thermalConfigDesc()}）")
                }
            }
        }
        logScope.launch {
            runCatching {
                AppPrefs.splitThermalGuardOff.collect { off ->
                    Log.i(
                        TAG,
                        if (off) "🌡 过热保护已被用户关闭（闸门 + 高温动作都拆掉）"
                        else "🌡 过热保护已恢复（上限 ${AppPrefs.splitThermalLimitC.value}°C）",
                    )
                }
            }
        }
    }

    /**
     * 捕获 `SoScSplitScreenController` 与 `ShellTaskOrganizer` 实例 —— **两条路同时走**。
     *
     * ==================== 为什么不能只靠 hook 构造器（2026-10-04 第二轮实测） ====================
     * 实测时序（logcat 原文）：
     * ```
     * 06:42:47.030  SystemUI 进程起
     * 06:42:51.031  WMShell 开始建（wmshell-ScoutStateMachine created）
     * 06:42:51.735  WMShell Dagger 建图完成（Topological CoreStartables completed）
     *               ⇒ ★ 控制器在这一刻就已经构造完了
     * 06:42:55.671  我们才执行 doBoot（HyperPlusModule.START_DELAY_MS = 4000）
     * ```
     * ⇒ **hook 比构造晚 4.5 秒，永远抓不到**。⛔ 也不去动那 4 秒：那是「别占宿主启动路径」的纪律
     *   （见 `HyperPlusModule` 类注释）；把 hook 提到 `onPackageReady` 的瞬间也不行 ——
     *   那时宿主 Application 还没建好，连开关都读不到。
     *
     * ==================== 真正起作用的是「运行期捕获 organizer」 ====================
     * jadx 反编译 `ShellTaskOrganizer`，逐字看到：
     * ```java
     * private final ArrayList mSplitScreenListener;                       // 第 85 行
     * public void addMiuiShellTaskListener(MiuiShellTaskListener l) {
     *     this.mSplitScreenListener.add(l);                                // 第 615 行
     * }
     * ```
     * 而 `SoScSplitScreenController implements ShellTaskOrganizer.MiuiShellTaskListener`
     * （它确实实现了接口里的 `exitSoSc` / `exitSoScByTaskId` / `startTaskInSoSc` / `startIntentInSoSc`）
     * ⇒ **控制器就躺在那条 list 里**（挖掘见 [controllerOrNull]）。
     *
     * 于是 hook `ShellTaskOrganizer` 上几个「任务生命周期」方法 —— 它们**运行期一定会被调到**，
     * 而**方法 hook 是探针实测过 372 次的安全模式**（对象早已存在，我们只是插一脚读 `this`）。
     * ⭐ 这条路**完全不依赖构造时序**。
     *
     * ⚠️ 路 ① 的构造器 hook 保留着：万一控制器被重建，它就直接命中；正常开机一次都不会响（成本为零）。
     *   hooker 里只 `proceed()` + 存引用，**不改变任何行为**。
     */
    private fun installCapture(module: XposedInterface, classLoader: ClassLoader?): Boolean {
        val loaders = buildList {
            classLoader?.let { add(it) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
        }.distinct()

        var n = 0

        // ---- 路 ①（保险）构造器捕获 ----
        val ctrlCls = resolve(CTRL_CLASS, loaders)
        if (ctrlCls == null) {
            Log.w(TAG, "❶ 类未找到：$CTRL_CLASS（换机/升级后包名可能变）")
        } else {
            var c = 0
            for (ctor in ctrlCls.declaredConstructors) {
                val ok = runCatching {
                    module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                // ⛔ 先放行，拿到返回值再存 —— 绝不在构造过程中做别的事
                                val r = chain.proceed()
                                runCatching { takeController(r ?: thisObject(chain)) }
                                return r
                            }
                        })
                }.isSuccess
                if (ok) c++
            }
            Log.i(TAG, "❶ ${ctrlCls.simpleName}：钩上 $c 个构造器（保险用，正常开机不会响）")
            n += c
        }

        // ---- 路 ②（主路）运行期捕获 organizer ----
        val orgCls = resolve(ORG_CLASS, loaders)
        if (orgCls == null) {
            Log.w(TAG, "❷ 类未找到：$ORG_CLASS")
        } else {
            var m = 0
            for (method in orgCls.declaredMethods) {
                if (method.name !in ORG_HOOK_NAMES) continue
                val ok = runCatching {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(orgHooker)
                }.isSuccess
                if (ok) m++
            }
            Log.i(TAG, "❷ ${orgCls.simpleName}：钩上 $m 个运行期方法（拿实例用）")
            n += m
        }
        return n > 0
    }

    /**
     * 挂在 `ShellTaskOrganizer` 那几个方法上的 hooker。
     * ⛔ 唯一职责：**把 `this` 存下来**（顺带看一眼参数里有没有控制器），然后原样放行。
     * ⚠️ 命中后 `taskOrganizer != null` ⇒ 之后每次调用的成本只是一次空判断。
     */
    private val orgHooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching {
                if (taskOrganizer == null) {
                    val self = thisObject(chain)
                    if (self != null && self.javaClass.name == ORG_CLASS) {
                        taskOrganizer = self
                        Log.i(TAG, "✅ 已捕获 $ORG_CLASS（来自 ${execName(chain)}）")
                    }
                }
                // ★ 白捡一条：`addMiuiShellTaskListener(t)` 的参数**就是**控制器
                if (controller == null) {
                    for (a in chain.args) if (a != null) takeController(a)
                }
            }
            return chain.proceed()
        }
    }

    /** 只认类名匹配，避免把别的监听器误当成控制器 */
    private fun takeController(obj: Any?) {
        if (obj == null || controller != null) return
        if (obj.javaClass.name == CTRL_CLASS) {
            controller = obj
            Log.i(TAG, "✅ 已捕获 ${obj.javaClass.simpleName} 实例")
        }
    }

    private fun resolve(name: String, loaders: List<ClassLoader>): Class<*>? =
        loaders.firstNotNullOfOrNull { l ->
            runCatching { Class.forName(name, false, l) }.getOrNull()
        }

    private fun execName(chain: XposedInterface.Chain): String =
        runCatching { chain.executable.name }.getOrNull() ?: "?"

    /** 构造器 hook 的 `proceed()` 各版本返回不一致，用 `Chain.getThisObject()` 兜底 */
    private fun thisObject(chain: XposedInterface.Chain): Any? = runCatching {
        chain.javaClass.getMethod("getThisObject").invoke(chain)
    }.getOrNull()

    // ================================================================ 传感器

    private fun installHinge(hostCtx: Context) {
        val sm = hostCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            Log.w(TAG, "拿不到 SensorManager")
            return
        }
        val sensor = hingeSensor(sm) ?: run {
            Log.w(TAG, "本机没有 hinge_angle 传感器 ⇒ 折角触发不可用")
            return
        }
        val thread = HandlerThread("hyperplus-hinge").apply { start() }
        val ok = runCatching {
            // ⚠️ GAME 档（~20ms）；本机传感器实测最快约 66.7ms，系统会自动收敛
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))
        }.getOrDefault(false)
        Log.i(
            TAG,
            if (ok) "✅ 折角传感器已注册：${sensor.name}（minDelay=${sensor.minDelay}µs）"
            else "⚠️ 折角传感器注册被拒",
        )
    }

    /**
     * `Sensor.TYPE_HINGE_ANGLE`（= 36）从 API 30 才有。用 `SuppressLint("NewApi")` 而不是运行时分支：
     * 低版本系统上这个词本就是未知传感器，`getDefaultSensor` 会返回 null，[installHinge] 已处理。
     */
    @SuppressLint("NewApi")
    private fun hingeSensor(sm: SensorManager): Sensor? = runCatching {
        sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    }.getOrNull()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val a = event.values.firstOrNull() ?: return
            // ⛔ 任何异常都不许冒泡到 SystemUI 的传感器线程
            runCatching { onAngle(a, SystemClock.elapsedRealtime()) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ================================================================ 状态机

    /**
     * ★★★ 判定状态机的**全部状态** —— 含展开基线 A0 的滑动窗口（2026-10-06 收拢）。
     *
     * ★★ 为什么从 6 个散落的 `var` 收成一份：[FoldJudge.State] 里那几个字段
     *   （`folding` / `suppressed` / `foldStartMs` / `minDuringFold` / `window` / `lastSample`）
     *   原来在这里被**人工一对一**地复制了一份，注释里还得写"改的时候两处一起看，别只改一边"。
     *   更要紧的是：**基线窗口留在外面时，「合上之后再展开会误触发分屏」那条 bug 没法离线复现**
     *   （它需要"判定"与"窗口"协同，而两者分居两个文件）⇒ 窗口搬进 [FoldJudge] 之后，
     *   整条链在 JVM 单测里逐样本跑得出来（见 [FoldJudgeTest.closingThenReopeningMustNotTrigger]）。
     *
     * ⛔ 别再把其中某个字段单独提出来当 `var` —— 那正是这次收拢要消掉的隐患。
     * ⚠️ 唯一的例外是 [lastTriggerMs]：它是**冷却**的时间语义，[FoldJudge.judge] 只读不写
     *   （判定不该管"上一次什么时候触发的"，那是调用方的事）。
     */
    private var judgeState = FoldJudge.State()

    private var lastTriggerMs = 0L

    /**
     * 引擎里的**只读订阅作用域**（现只服务诊断日志：[logThermalOnChange]）。
     * ⚠️ 它是 `SupervisorJob + Dispatchers.Default`，**不是主线程** —— collect 里⛔ 别碰需要主线程的东西。
     * ★ 名字由 `calibScope` 改来（2026-10-06 校准下线）：它从来就不只服务校准。
     */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 一个折角样本进来。判定形状（§3 逐字落成代码）：
     * ```
     * 进入：A0 - angle > D1
     * 窗口：WIN ms（从【进入】那一刻开始算，是**绝对期限**）
     *   合上 ：A0 - angle ≥ D3              ⇒ 要合上   → 取消 + 【闩住】 + **清空基线窗口**
     *   到期 ：now - 进入 > WIN             ⇒ 超时      → 取消 + 【闩住】  ← ★ 必须排在两条回弹之前
     *   回弹①：angle ≥ A0 - D2              ⇒ 轻折回弹 → ★ 触发
     *   回弹②：angle - 谷底 ≥ D1            ⇒ 轻折回弹 → ★ 触发（松手必回弹，更鲁棒）
     * 闩住后：angle > OPEN_MIN（回到展开态）才重新武装
     * ```
     * ⚠️ 角度**变大** = 更展开（本机实测：全展 ≈ 179、半开 ≈ 90、合上 ≈ 2~3）。
     * ⚠️ 【闩住】不是可选项 —— 没有它就会"每帧重进一次"，理由见 [FoldJudge.State.suppressed]。
     * ★ 回弹② 是 2026-10-04 真机漏触发后补的：判据①要求"完全回到展开位"太苛刻，
     *   而"是否松手"其实只需看**回升**；合上永远单调下降，不会误入这一路。
     *
     * ★★★ **2026-10-05：整个判定已搬进 [FoldJudge.judge]，本函数只剩"喂输入 / 落状态 /
     *   打日志 / 真触发"。** 原因就是上面那个 `到期` 的位置 —— 它原来被排在 `when` 的**最后**，
     *   于是「折一下停好几秒再展开」**永远不会走到超时那条**（on-change 传感器静止时不发样本，
     *   下一个样本就是展开那一下，天然满足回弹②）⇒ 用户报的「过了动作时限还是分屏」就是这么来的。
     *   ⇒ 抽成纯函数之后这条顺序由 `FoldJudgeTest` 钉住，⛔ 别再把这套判定搬回本函数。
     */
    private fun onAngle(angle: Float, nowMs: Long) {
        val host = hostCtxRef ?: return

        // ★ 阈值是**编译期常量**（校准已下线，见类注释）：没有第二条路径能改它，
        //   ⇒ 直接取 [SplitThresholds.DEFAULT]，不必再订阅任何东西。
        val th = SplitThresholds.DEFAULT

        // ★★★ 判定**只在这里发生一次**（[FoldJudge.judge]）—— 本函数不许自己再写一遍 `if`：
        //   2026-10-05 那次报障就是"判定散落在 `when` 的四个分支里、顺序错了没人看得出来"。
        //   ★ 状态**整份**进出（2026-10-06 收拢，见 [judgeState]）；A0 由判定核算、这里只读。
        val verdict = FoldJudge.judge(
            s = judgeState,
            angle = angle,
            nowMs = nowMs,
            lastTriggerMs = lastTriggerMs,
            th = th,
            openMin = OPEN_MIN,
            cooldownMs = COOLDOWN_MS,
        )
        judgeState = verdict.state
        val a0 = verdict.a0

        // ★ 模式 1 的**定标轨迹**：原始角度逐点打（这是定标唯一的可靠数据源）。
        //   ⚠️ 它要用 A0，而 A0 现在由 [FoldJudge.judge] 算出来 ⇒ 只能**判完再打**；
        //     日志顺序从"先轨迹后判定"变成"先判定后轨迹"。两者都只读，不影响行为。
        if (observeOnly) logTrajectory(angle, a0, nowMs)

        when (verdict.outcome) {
            FoldJudge.Outcome.NOTHING -> Unit

            FoldJudge.Outcome.REARM ->
                Log.i(TAG, "⟲ 已回到展开态（${fmt(angle)}° > ${fmt(OPEN_MIN)}°）⇒ 重新武装")

            FoldJudge.Outcome.ENTER -> {
                // ★★ 2026-10-06：计时起点现在是「**开始折**」那一刻（见 [FoldJudge.START_DROP]），
                //   不再是"这一刻" ⇒ 把**提前量**打出来。
                //   它是"1 秒到底从哪算"的唯一现场证据：用户报"超过 1 秒还分屏"时，
                //   看一眼这个数就知道分歧在起算点还是在阈值。
                val lead = nowMs - verdict.state.foldStartMs
                val from = if (lead > 0L) "开始折（比这一刻早 ${lead}ms）" else "这一刻（没抓到开局）"
                Log.i(
                    TAG,
                    "▼ 进入折叠判定：angle=${fmt(angle)} A0=${fmt(a0)} 差=${fmt(a0 - angle)}° " +
                        "(D1=${th.d1})；计时起点=$from",
                )
                // ★ 顺带把"真动作"要用的三个量预演一遍 —— 这样**不开模式 2 也能验证整条链通不通**
                if (observeOnly) {
                    val ctrl = controllerOrNull()
                    val h = ctrl?.let { wmShellMainHandler(it) }
                    Log.i(
                        TAG,
                        "   ↳ 预演：${plan()}（控制器=${if (ctrl != null) "有" else "⚠️无"}；" +
                            "WMShell主线程=${h ?: "⚠️无"}）",
                    )
                }
            }

            // ★★★ 2026-10-06：判为「要合上」时判定核会**顺手清空展开基线窗口**
            //   （修「合上之后再展开会误触发分屏」，见 [FoldJudge] 类注释第二个 bug）。
            //   ⛔ 别把那条清空删掉 —— [FoldJudgeTest.closingThenReopeningMustNotTrigger] 钉着它。
            FoldJudge.Outcome.CANCEL_CLOSE ->
                Log.i(
                    TAG,
                    "✗ 判为「要合上」（最低 ${fmt(verdict.state.minDuringFold)}°）⇒ 不触发，闩住，" +
                        "并清空展开基线（重开时不会误判）",
                )

            // ★★★ 这行日志是 2026-10-05 那个报障（「折一下停几秒再展开」不该触发）的**验收眼**：
            //   它出现 = 这一次确实**没有**分屏。⛔ 别把历时/窗口两个数从日志里删掉，
            //   它们是"到底是窗口太小还是判定被架空"的唯一现场证据。
            FoldJudge.Outcome.CANCEL_TIMEOUT ->
                Log.i(
                    TAG,
                    "✗ 超时未回弹（历时 ${nowMs - verdict.state.foldStartMs}ms > 窗口 ${th.winMs}ms）" +
                        "⇒ 不触发，闩住",
                )

            FoldJudge.Outcome.TRIGGER -> {
                val dur = nowMs - verdict.state.foldStartMs
                val minDuringFold = verdict.state.minDuringFold
                // ⚠️ 两条回弹的日志**分开打**（逐字沿用旧格式）：排查时"是完全弹回还是只回升"很重要，
                //   合成一句会把这个信息抹掉。判据与 [FoldJudge] 里的分支条件一致。
                if (angle >= a0 - th.d2) {
                    Log.i(TAG, "✅ 判为「轻折回弹」：历时 ${dur}ms，最低 ${fmt(minDuringFold)}° ⇒ 触发")
                } else {
                    Log.i(TAG, "✅ 判为「轻折回弹」（自谷底回升 ${fmt(angle - minDuringFold)}°）：" +
                        "历时 ${dur}ms，最低 ${fmt(minDuringFold)}° ⇒ 触发")
                }
                lastTriggerMs = nowMs
                runCatching { onTrigger(host) }
            }
        }
    }

    /**
     * 定标用：把**原始折角轨迹**打到 logcat（模式 1 专属）。
     *
     * 限流：角度相对上一行变了 ≥1° 就打；3s 没有任何**新样本**则补一行心跳。
     * ⚠️ 但本机 `hinge_angle` 是 **on-change 传感器**（`dumpsys` 原文：`on-change | minRate=1.00Hz`）
     *   ⇒ 手机静止时**根本没有回调**，心跳自然也不会出现。这不是 bug：
     *   实测静置时日志是断的，一动起来就是 20ms 一个点（`logs/hinge_traj_2026-10-04.txt` 实证）。
     */
    private fun logTrajectory(angle: Float, a0: Float, nowMs: Long) {
        val changed = lastLoggedAngle.isNaN() || kotlin.math.abs(angle - lastLoggedAngle) >= 1f
        if (!changed && nowMs - lastLoggedMs < 3_000L) return
        lastLoggedAngle = angle
        lastLoggedMs = nowMs
        Log.i(TAG, "# 折角 ${fmt(angle)}° / A0=${fmt(a0)}°（差 ${fmt(a0 - angle)}°）")
    }

    // ⚠️ 原来的 `baseline(angle, nowMs)` 已整段搬进 [FoldJudge]（`advance` + `State.window`）——
    //   搬的理由见 [judgeState] 那条注释（bug 复现需要窗口与判定在同一处）。

    // ================================================================ 触发

    /** 「真动作」要用到的全部输入量，一次算齐（日志与调用共用，⛔ 不让两处各算一遍） */
    private data class Plan(val dir: SplitUnfoldDirection?, val pos: Int, val taskId: Int) {
        override fun toString() = "方向=${dir ?: "?"} ⇒ pos=$pos；taskId=$taskId"
    }

    /**
     * 算出 [Plan] —— **只读**，不改任何状态。
     * ★ 抽出来的意义：模式 1 可以**先预演**，即在**不真进分屏**的前提下验证
     *   「控制器实例拿没拿到 → `mTaskOrganizer` 反射通不通 → `mTasks` 里找不找得到聚焦任务」
     *   这条链 —— 这正是本轮定标要顺带回答的问题。
     */
    private fun plan(): Plan {
        val dir = runCatching { AppPrefs.splitUnfoldDir.value }.getOrNull()
        return Plan(dir, posOf(dir), focusedTaskId())
    }

    /**
     * 取 `SoScSplitScreenController` 实例。
     *
     * ★ 主路：从 `ShellTaskOrganizer.mSplitScreenListener`（`ArrayList`）里**按类名**挖 ——
     *   控制器实现了 `ShellTaskOrganizer.MiuiShellTaskListener` 并注册在里面（见 [installCapture]）。
     * ⚠️ 挖到就**缓存**（[controller]），之后零成本；挖不到返回 null，**不重试、不报错**。
     * ⛔ 全程只读 + runCatching：本代码跑在 SystemUI 里，任何异常都会带崩状态栏。
     */
    private fun controllerOrNull(): Any? {
        controller?.let { return it }
        return runCatching {
            val to = taskOrganizer ?: return null
            val list = readField(to, "mSplitScreenListener") ?: return null
            val size = (list.javaClass.getMethod("size").invoke(list) as? Int) ?: return null
            val get = list.javaClass.getMethod("get", Int::class.java)
            for (i in 0 until size) {
                val e = runCatching { get.invoke(list, i) }.getOrNull() ?: continue
                if (e.javaClass.name == CTRL_CLASS) {
                    controller = e
                    Log.i(TAG, "✅ 从 mSplitScreenListener[$i] 挖到 ${e.javaClass.simpleName}")
                    return e
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 判定成立 → 真的让系统"新增一格分屏"。
     *
     * ★ 我们**不指定任何 App**：`splitPrimaryTask` 只吃 (pos, taskId)，taskId 取**当前聚焦的全屏任务**
     *   —— 即"把当前正在用的 App 放到某一侧"，与三指滑的行为**逐字一致**（§5.1「只做触发器」）。
     */
    private fun onTrigger(host: Context) {
        // ★★★ **名单闸（2026-10-05 新增）** —— 前台应用在分屏名单里 ⇒ 不折出分屏。
        //
        // ==================== 它为什么在这里、而不在 onAngle 的判定里 ====================
        // 判定的其余输入量（阈值、闩锁）都是"这一折有多深"，而名单是"**谁在前台**"——
        // 后者要读前台任务（一次 binder IPC，见 [ForegroundProbe]），放在 per-sample 的
        // 判定路径上是浪费。这里是**每次触发才走一次**的地方，成本可以忽略。
        //
        // ★ **必须在 `mode < 2` 之前判**：1 档观察模式也要打这行日志 ——
        //   那正是用户用来确认"我名单配对了吗"的唯一手段（他折一下，看日志说
        //   "命中名单、本会跳过"）。若放在 mode 判断之后，1 档就看不到名单的结论了。
        if (splitWhitelistHit()) {
            // 命中 ⇒ 不执行。⚠️ 这里**只 return**，不闩锁、不设冷却：
            //   名单是"这个应用"的属性，与"这一折"无关 —— 用户换到别的应用再折，
            //   应当**立刻**能用（闩锁会把那份等待也一起吃掉，那是错的）。
            return
        }
        val dir = runCatching { AppPrefs.splitUnfoldDir.value }.getOrNull()
        val pos = posOf(dir)
        val taskIdNow = focusedTaskId()

        if (mode(host) < 2) {
            Log.i(TAG, "（只观察模式）方向=${dir ?: "?"} ⇒ pos=$pos；taskId=$taskIdNow —— 未执行")
            return
        }
        val ctrl = controllerOrNull() ?: run {
            Log.w(TAG, "没有控制器实例 ⇒ 放弃（下次再来；organizer 通常几秒内就会被捕获）")
            return
        }

        // ★★★ 必须投到 WMShell 主线程（理由见 [wmShellMainHandler]）—— 拿不到就彻底不动手
        val h = wmShellMainHandler(ctrl)
        if (h == null) {
            Log.w(TAG, "⚠️ 拿不到 WMShell 主 Handler ⇒ 放弃本次（在错线程上调会留下脏状态）")
            return
        }
        Log.i(TAG, "→ 方向=${dir ?: "?"} ⇒ pos=$pos；投递到 WMShell 主线程 $h")
        // ★★ B3 并发闸：上一次还没飞完（`final` 还没执行）⇒ **直接丢掉这一次**。
        //   丢而不是排队：排队会让"用户折得越快、系统排得越多"，最后一次性连加好几格 ——
        //   而用户的本意只是"我折了一下"。丢掉之后的下一折照样能用（闸是一次性的，不是闩锁）。
        //   ⚠️ 判断放在**投递之前**、`h.post` 的外面：那是唯一的竞争窗口（只有一个传感器线程）。
        if (appending) {
            Log.i(TAG, "⏳ 上一次追加还在进行中 ⇒ 丢掉这一次折叠（B3 并发闸）")
            return
        }
        appending = true
        h.post {
            // ★ `finally` 是**必须的**：`doSplit` 里任何一条早退（拿不到 taskId / 上限闸 / 组织者
            //   找不到）都走 `return`，漏掉释放就变成"一次失败之后永久卡死"。
            try {
                runCatching { doSplit(ctrl, pos) }.onFailure { Log.e(TAG, "doSplit 异常（已吞掉）", it) }
            } finally {
                appending = false
            }
        }
    }

    /**
     * 真正的那一下 —— **只在 WMShell 主线程上跑**（由 [onTrigger] 投递）。
     *
     * ★★★ **按当前形态分流**（2026-10-04 深夜新增；两条链是系统的两套实现，判据见 `docs` §1.5.1）：
     * ```
     * 全屏（0）    ⇒ splitPrimaryTask(pos, taskId)              ← 三指滑那条，只做 0→2
     * 已有分屏(≥2) ⇒ MultipleSplitRootTaskOrganizer
     *                  .dockMultipleSplitTasks(Bundle)          ← 「拖到多分屏图标」那条，追加一格
     * ```
     * ★ 分流的理由**不是我们发明的**：`splitPrimaryTask` 第一行就是
     *   `if (isSplitScreenVisible()) return false;` ⇒ **它自己对已有分屏一律拒绝**（§1.11.7），
     *   所以 n≥2 必须换一条链。而那条链恰好就是用户"拖到多分屏图标"时走的同一条（§11）。
     * ★ taskId 在这里现取：等排到队时前台可能已经变了，取"执行瞬间"的才准。
     * ⚠️ 注意 `taskId` 只用于**分流与日志**；追加那一格真正要装进 Bundle 的是**分屏 root 的 info**，
     *   与聚焦 App 无关（见 [splitRootTaskInfoOrNull]，2026-10-05 实测更正）。
     */
    private fun doSplit(ctrl: Any, pos: Int) {
        val taskId = focusedTaskId()
        if (taskId <= 0) {
            Log.w(TAG, "拿不到聚焦任务 ⇒ 安静放弃（不重试）")
            return
        }
        // ★ 分流判据：多分屏 stage 数（**真值**，不是数 dumpsys 的容器 —— 那会低报，§1.5.3）
        val n = activeStageCount()
        if (n >= 2) {
            doAppend(taskId, n)
            return
        }
        // ★ 诊断：把 splitPrimaryTask 的**全部 5 个拒绝点**先问一遍 —— 被拒时一眼看出卡在哪
        Log.i(TAG, "   ↳ 预检：${precheck(ctrl, taskId)}")
        val r = runCatching {
            val m = ctrl.javaClass.getMethod("splitPrimaryTask", Int::class.java, Int::class.java)
            m.invoke(ctrl, pos, taskId) as? Boolean
        }.onFailure { Log.e(TAG, "调用 splitPrimaryTask 失败（已吞掉）", it) }.getOrNull()
        Log.i(TAG, "★ splitPrimaryTask(pos=$pos, taskId=$taskId) 返回 $r")
    }

    /**
     * ⭐⭐⭐ **追加一格** —— n≥2 时走这条（2026-10-04 深夜新增，**2026-10-05 上机跑通**）。
     *
     * 做的事**只有一件**：把「用户拖动时系统调的那个方法」原样调一遍。
     * ```
     * MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks(
     *     Bundle{ "multiple_split_dock_task" = <分屏 root 的 RunningTaskInfo> })
     *   → n=2：dockSoScTasks()   ⇒ 两块缩成一条带子 + 空出第 3 槽 + 桌面置顶（buildHomeToFront）
     *   → n≥3：dockMultipleTasks()
     * ```
     * ★★ **2→3 与 3→6 是同一个入口** ⇒ 只实现这一次（§10.3）。
     * ★★★ **"回桌面 / 选 App / 填槽"全是系统自己做的** —— 我们只按了一下扳机，
     *   这就是 §5.1「只做触发器」在 n≥2 这一段的落地。
     * ⛔ **不构造任何 `PendingIntent`**：主路完全不需要它（那是"我们替用户指定 App"，
     *   用户已明确不要 —— 见文档 §11.4 / §11.6）。
     * @param taskId 仅用于**日志**（人眼追哪个 App 在前台）；⛔ 不参与 Bundle 组装。
     */
    private fun doAppend(taskId: Int, n: Int) {
        // ① 拿分屏 root 的 RunningTaskInfo 本体
        //    🔴🔴 **2026-10-05 更正（实测打脸）**：这里原本装的是「当前聚焦 App 的 info」——
        //      **错的**。官方那条命令行（`MultipleSplitShellCommandHandler.runDockTasks` L77 逐字）装的是
        //      `splitRootTaskInfo`（**分屏的 root 任务**），不是被拖的 App：
        //      ```java
        //      RunningTaskInfo splitRootTaskInfo =
        //          SoScUtils.getInstance().inSoScFullMode() ? SoScUtilsImpl.getInstance().getSplitRootTaskInfo()
        //        : MultipleSplitUtils.getInstance().isMultipleSplitActive() ? controller.getMultipleSplitOrganizer().mSplitRootTaskInfo
        //        : null;
        //      bundle.putParcelable(OPTION_DOCK_TASKS, splitRootTaskInfo);
        //      ```
        //    ★ 上机反证：`wm shell MultipleSplit dockTasks`（**不带参**）⇒ 2 分屏当场变 3 分屏 ✅；
        //      带参 `dockTasks 353 496`（参数其实被忽略）⇒ 静默无变化。两次都装同一个 root info ⇒ 判据成立。
        val info = splitRootTaskInfoOrNull()
        if (info == null) {
            Log.w(TAG, "拿不到分屏 root 的 RunningTaskInfo（分屏可能刚退）⇒ 安静放弃")
            return
        }
        // ② ★★★ **上限闸（A3）** —— 先问"还能不能加"，再动手。
        //   ⚠️ 这不是"优化"：不设它就是在**盲调系统**。实测日志 `stage 数 5 → 5` 说明
        //     系统自己会拒（不会崩），但代价是：我们发了一次必然失败的调用、
        //     用户看到"折了没反应"却不知道为什么、而且真正的上限（低内存机是 4）我们并不知道。
        //   ⚠️ 上限读不到（`maxStagesOrNull() == null`）⇒ **跳过这道闸**：宁可让系统自己拒，
        //     也不拿一个编出来的数去拦用户（那种"我明明没到 6 格为什么不让加"最难排查）。
        val max = maxStagesOrNull()
        if (max != null && n >= max) {
            Log.i(TAG, "⛔ 已到多分屏上限（$n/$max）⇒ 不再追加（A3 上限闸）")
            reportLimitHit(n, max)
            return
        }
        // ③ 拿**多分屏的真身** —— `MultipleSplitRootTaskOrganizer`（见 [msplitOrganizerOrNull]）
        val org = msplitOrganizerOrNull()
        if (org == null) {
            Log.w(TAG, "⚠️ 找不到 MultipleSplitRootTaskOrganizer ⇒ 安静放弃（⛔ 不回落到 SoSc 那条必被拒的路）")
            return
        }
        // ④ ★★★ **dock 待选态拦截（2026-10-05 新增，见 docs §15）**。
        //
        //   ==================== 为什么这一道闸既要"省一次调用"又要"给一句话" ====================
        //   系统把"折一下"实现成：**收成 dock 带子 + 桌面置顶 + 空出下一格 + 等用户点一个 App**。
        //   在用户点之前，`MultipleSplitRootTaskOrganizer.mDockedState != null`，此时：
        //   ```
        //   dockMultipleSplitTasks(): if (mDockedState != null) { Slog.d("Already in dock mode"); return null; }
        //   ```
        //   ⇒ **必然被拒**。原来的代码会**照样发这一次调用**，然后拿一个 null 回来、
        //     在日志里写一句"系统拒了，安静放弃"——用户那边**一点动静都没有**（"折了没反应"）。
        //
        //   ★ 现在**先问再动**：
        //     ① **省掉一次注定失败的 `dockMultipleSplitTasks`** —— 它内部会走一遍
        //        `buildDockMultipleWct` / `updateUIVisibility` 之类的准备路径，
        //        而那**正是用户报"收成 dock 带子时卡顿"的那段**（虽然那一段本身是系统动画，
        //        但"再踩一次"只会把它压得更重）；
        //     ② **给用户一句能照着做的话**（"请先选一个应用补齐当前格"）——
        //        把静默失败变成"有指令的等待"。
        //
        //   ⚠️ 判据读的是 `isDocked()`（`MultipleSplitOrganizer` 的 **public 接口方法**，
        //     实现 ＝ `mDockedState != null`）——**与系统那道闸读的是同一个字段**，
        //     所以不会出现"我们说在 dock 态、系统说不在"的分叉。
        //   ⚠️ 读不到时（反射失败 / 方法改名）⇒ **当作不在 dock 态**（照常往下走）：
        //     宁可多走老路（系统会拒、行为与从前一致），也不拿一个"读不到"去拦用户。
        if (isDockedNow(org)) {
            Log.i(TAG, "⏳ 系统还在 dock 待选态（上一次折叠等你点应用）⇒ 不发调用，改为提示（§15）")
            reportDockWait()
            return
        }
        Log.i(TAG, "→ 追加一格：当前 stage 数=$n${if (max != null) "/$max" else ""}，" +
                "目标=${org.javaClass.simpleName}，分屏 root taskId=${intField(info, "taskId")}")
        val r = runCatching {
            val bundle = android.os.Bundle().apply {
                putParcelable(KEY_DOCK_TASKS, info as android.os.Parcelable)
            }
            val m = org.javaClass.getMethod("dockMultipleSplitTasks", android.os.Bundle::class.java)
            m.invoke(org, bundle)
        }.onFailure { Log.e(TAG, "调用 dockMultipleSplitTasks 失败（已吞掉）", it) }.getOrNull()
        // ④ ★ **回读**：不许把"调过了"当成"成了"（§10.6 的纪律）
        val after = activeStageCount()
        // ★ B2：解析返回的 Bundle —— 它是**系统画的 dock 带子的几何参数**（给桌面用的）。
        //   我们不画任何东西，但它能回答一个真问题：**"调成功了但格数没涨"到底是哪一种？**
        //   实测（§12）：返回 Bundle ⇒ 系统接受了这次 dock（进入 dock 态：两块缩成带子 +
        //   桌面置顶 + 空出下一格，等用户点 App 补回去）；返回 null ⇒ 系统直接拒了。
        val docked = r is android.os.Bundle
        Log.i(TAG, "★ dockMultipleSplitTasks 返回 ${describeDockResult(r)}；stage 数 $n → $after" +
                when {
                    after > n -> "（✅ 已生效）"
                    docked -> "（⚠️ 格数没涨但系统进了 dock 态 —— 它能接受，只是要用户点 App 补格）"
                    else -> "（⚠️ 没涨且没 dock —— 系统拒了，安静放弃）"
                })
        // ★ A4：**只有"真的到顶"才打扰用户**。dock 态与"被拒"都不提示 ——
        //   那两种情况下用户自己有下一步可做（点 App 补格 / 换个 App 再折），
        //   弹个"到上限了"反而是误导。
        if (after <= n && docked && max != null && after >= max) {
            Log.i(TAG, "⇒ 判定为「已到上限」（$after/$max）⇒ 给用户一次提示")
            reportLimitHit(after, max)
        } else if (after <= n && !docked) {
            // ★★ 2026-10-05 新增（§15）：这条分支原来**只打日志**。
            //   实测它是"用户在 dock 待选态里又折了一下"的落点（`mDockedState != null ⇒ null`），
            //   也可能是别的拒绝原因（比如"已在 dock 态"之外的几种）—— 无论哪种，
            //   用户看到的都是"折了没反应"。
            //   ⇒ 既然 `isDockedNow()` 那道前置闸没能拦住（说明我们读到的和系统判的不一致，
            //     或读不到），这里**兜一句提示**，让"静默"这条路径彻底消失。
            //   ⚠️ 两种提示共用同一个窗口与同一道去重闸 ⇒ 不会叠着弹。
            Log.i(TAG, "⇒ 系统拒了这次追加（$n → $after）⇒ 给用户一次提示（§15 兜底）")
            reportDockWait()
        }
    }

    /**
     * **前台应用是不是在分屏名单里** ⇒ true = 命中，本次折叠**不**折出分屏（2026-10-05 新增）。
     *
     * ============================ 它做的事只有一件 ============================
     * ```
     * 读前台包名 → 问 AppPrefs.isSplitWhitelisted(pkg)
     * ```
     * 名单的**真值**在 `AppPrefs.splitWhitelist`（App 的 prefs → 广播快照 → 引擎镜像，
     * 见 [cn.dsr213.hyperplus.SplitWhitelist]）—— 本函数**不缓存、不自己存一份**，
     * 因为那份镜像由 `ModulePrefs` 的推送维护，用户改一个开关几秒内就到位。
     *
     * ============================ 三条刻意的取舍 ============================
     * ① **读不到前台包 ⇒ 返回 false（＝照常分屏）**。这与 `ForegroundGate` 那条
     *    "读不到就不下结论 / 宁可停手"**方向相反**，理由写在
     *    [cn.dsr213.hyperplus.AppPrefs.isSplitWhitelisted] 的 KDoc 里（判错方向的代价不对称：
     *    "多分一次"用户按一下返回就回去了，"折了没反应"他无从自查）。
     * ② **probe 懒加载且只建一个**：`ForegroundProbe` 的构造只是存个 Context，
     *    但 `read()` 是 binder IPC ⇒ 用一个字段缓存实例，避免每次触发都 new 一个。
     * ③ **整包 runCatching 兜底**：本代码跑在 SystemUI 进程，任何未捕获异常 = 状态栏崩溃
     *    （见类注释那条纪律）。这里读的都是"别人的东西"，更要兜。
     */
    private fun splitWhitelistHit(): Boolean = runCatching {
        val host = hostCtxRef ?: return false
        val pkg = probeOrNull()?.read()?.pkg
        val hit = AppPrefs.isSplitWhitelisted(pkg)
        if (hit) {
            Log.i(TAG, "⛔ 前台「${pkg ?: "?"}」在分屏名单里 ⇒ 本次不折出分屏（名单闸）")
        } else {
            // ⚠️ 只在**有包名**时打这行：读不到包名的情况很吵（每次折都打），
            //   而它本身不需要用户知道 —— 该看的时候是"折了没反应"，那时日志里有上面那行。
            if (pkg != null) Log.i(TAG, "前台「$pkg」不在分屏名单里 ⇒ 照常判定")
        }
        hit
    }.onFailure { Log.w(TAG, "查分屏名单失败（当作未命中，已吞掉）", it) }.getOrDefault(false)

    /**
     * 懒加载的 [ForegroundProbe] 实例。
     * ⚠️ **引擎侧自建一个**，不复用 `AdaptiveEngine` 那个（那是它的私有字段，且职责是"前台门"）——
     *   两者读的是**同一份系统数据**，各自持有一个 probe 只是多一份小小的组件名缓存，
     *   换取的是"分屏触发不依赖 AdaptiveEngine 的初始化状态"。
     * ⛔ 别为了省这一个对象去跨类共享：那会让 `SplitTrigger` 多一条**隐式依赖**
     *   （AdaptiveEngine 没起来 / 形态判断尚未完成时，这里就会静默失效）。
     */
    @Volatile private var fgProbe: ForegroundProbe? = null

    private fun probeOrNull(): ForegroundProbe? {
        fgProbe?.let { return it }
        val host = hostCtxRef ?: return null
        return runCatching { ForegroundProbe(host).also { fgProbe = it } }.getOrNull()
    }

    /**
     * **系统此刻是不是处在「dock 待选态」**（= 上一次折叠的"等用户点一个应用"还没完成）。
     *
     * ✅ 判据：`MultipleSplitOrganizer.isDocked()`（接口 L425 声明的 **public** 方法），
     *   实现在 `MultipleSplitRootTaskOrganizer`：
     *   ```java
     *   public boolean isDocked() { return this.mDockedState != null; }
     *   ```
     *   ★ 这**就是** `dockMultipleSplitTasks()` 开头那道
     *   `if (this.mDockedState != null) { Slog.d("Already in dock mode"); return null; }` 读的东西
     *   ⇒ 我们问的和系统拒的是**同一个字段**，不会分叉。
     *
     * ⚠️ 读不到（方法改名 / 反射失败）⇒ 返 **false**（＝当作不在 dock 态）：
     *   宁可让调用照常发出去（被系统拒，行为与改动前**完全一致**），
     *   也不拿一个"读不到"去拦用户 —— 那种"我明明没在等、为什么不让折"最难排查。
     *   ⛔ 别把这里的异常往外抛：它是**前置判断**，不该影响主链。
     */
    private fun isDockedNow(org: Any): Boolean = runCatching {
        org.javaClass.getMethod("isDocked").invoke(org) as? Boolean
    }.onFailure { Log.w(TAG, "读 isDocked() 失败（当作不在 dock 态，已吞掉）", it) }.getOrDefault(false) == true

    /**
     * 读多分屏**上限格数**；读不到返 null（调用方跳过上限闸）。
     *
     * ✅ 真值来源：`Settings.Global` 的 [KEY_MAX_STAGES]，本机 = **6**。
     * ⚠️ 两个 Key 都试（`Settings.Global` / `Settings.System`）：小米把它放在 global，
     *   但换 ROM / 换版本可能挪窝 —— 读两个的成本可以忽略（结果还会被缓存）。
     * ⛔ **绝不硬编码 6**：低内存机型是 4，而且用户/厂商可以用 `persist.sys.*` 覆盖。
     */
    private fun maxStagesOrNull(): Int? {
        val cached = maxStagesCache
        if (cached >= 2) return cached
        val host = hostCtxRef ?: return null
        // ⚠️ 两个命名空间都试：小米把它放在 `global`，但换 ROM / 换版本可能挪窝。
        val hits = listOf(
            Settings.Global.NAME to runCatching {
                Settings.Global.getInt(host.contentResolver, KEY_MAX_STAGES, -1)
            }.getOrDefault(-1),
            Settings.System.NAME to runCatching {
                Settings.System.getInt(host.contentResolver, KEY_MAX_STAGES, -1)
            }.getOrDefault(-1),
        )
        for ((ns, v) in hits) {
            if (v >= 2) {
                maxStagesCache = v
                Log.i(TAG, "多分屏上限格数 = $v（来自 $ns.$KEY_MAX_STAGES）")
                return v
            }
        }
        return null
    }

    /**
     * 把 `dockMultipleSplitTasks` 的返回值整理成一行**能直接读懂的**日志（B2）。
     *
     * 实测返回的 Bundle（§12.4）：
     * ```
     * multiple_split_dock_support    = true
     * multiple_split_dock_bounds     = Rect(0, 0 - 2364, 1672)
     * multiple_split_dock_offset     = -2196.0
     * multiple_split_dock_orientation= 2
     * multiple_split_dock_corner_radius = 60.0
     * ```
     * ★ 这些数**我们不用**（系统自己画那条 dock 带子）⇒ 只打日志，⛔ 不落盘、不进配置。
     *   留着它的价值：以后若出现"dock 带子位置不对"这类现象，日志里有原始几何可比对。
     */
    private fun describeDockResult(r: Any?): String {
        if (r !is android.os.Bundle) return if (r == null) "null" else r.toString()
        return buildString {
            append("Bundle[")
            append("support=").append(r.getBoolean("multiple_split_dock_support"))
            r.getParcelable<android.os.Parcelable>("multiple_split_dock_bounds")?.let {
                append(" bounds=").append(it)
            }
            append(" offset=").append(r.getFloat("multiple_split_dock_offset"))
            append(" orientation=").append(r.getInt("multiple_split_dock_orientation"))
            append(" radius=").append(r.getFloat("multiple_split_dock_corner_radius"))
            append("]")
        }
    }

    /**
     * 「已经到多分屏上限了」的回报 —— **两条腿一起走**（A4，2026-10-05 起第二条腿是系统 Toast）。
     *
     * ============================ 腿 ①：让用户当场看见（主力）============================
     * 在 **SystemUI 进程**里弹一个**系统原生 Toast**（[AppToast]）—— 与"哪个 App 在前台"
     * 完全无关 ⇒ 用户此刻**正在分屏里折手机**也看得见。这就是用户要的"像旋转按钮一样"。
     * ★★ **为什么必须是 SystemUI 进程来弹**：Android 10+ 起**后台应用不能弹 Toast**，
     *   App 进程会被系统静默丢弃（见 [AppToast] ①）。而引擎跑在系统进程里 ⇒ 不受那条限制。
     * ★ **2026-10-05 用户点名"完全对齐系统 Toast 的位置"** ⇒ 从自绘浮层
     *   （`SplitLimitOverlay`，已被删除）改用 `android.widget.Toast` ⇒ 位置/外观/时长
     *   全部交给系统，和"屏幕截图"这类系统提示长得一模一样。
     * ⚠️ 文案在**引擎侧**选（[limitText]）而不是从 App 资源读：引擎与界面的资源归属不同一份
     *   （见 `AppLocale` 类注释的"三套资源只管 App 进程"），且这是**屏上浮层**，
     *   它必须以系统语言为准 —— 用户切了语言而 App 没重启时，这里也要对。
     *
     * ============================ 腿 ②：落盘给界面（保底）============================
     * 与校准结果同一条纪律：**引擎写 `Settings`、界面自己读**（App 侧只按"值变没变"判新旧）。
     * ★ 它的角色是**保底**：覆盖"用户事后打开本应用"的场景（`onResume` 补发）——
     *   因为 Toast 那 2 秒很可能被他错过（比如他正低头折手机、或提示在另一块屏上）。
     * ⚠️ 落盘降级也要吞掉（`runCatching`）。
     *
     * ⚠️ 去重 [LIMIT_NOTIFY_MS]：用户连着折好几下不该刷屏 —— 那会让"到上限了"变成噪音，
     *   而噪音的代价是**下一次真的有问题时他也不看了**。两条腿共用这一道闸。
     *   ★ 它和 [AppToast] **按文案**自带的那道去重闸不冲突（两道闸管的是不同粒度：
     *   这道管"同一件事别重复报"，那道管"同一句话别重叠显示"）。
     */
    private fun reportLimitHit(n: Int, max: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLimitNotifyMs < LIMIT_NOTIFY_MS) return
        lastLimitNotifyMs = now
        val host = hostCtxRef ?: return
        showHint(host, limitText(host))

        // ② 落盘给界面（保底，见 KDoc）
        runCatching {
            PrefsBridge.writeString(
                host.contentResolver,
                PrefsBridge.SPLIT_BUS_LIMIT,
                "${System.currentTimeMillis()}|$n|$max",
            )
        }.onFailure { Log.w(TAG, "写「到上限」提示键失败（已吞掉）", it) }
        Log.i(TAG, "已上报「到上限」提示（$n/$max）")
    }

    /**
     * **dock 待选态**下的提示（2026-10-05 新增，见 docs §15）。
     *
     * 与 [reportLimitHit] 的区别：**不落盘**（界面不需要知道这件事，它是个瞬态操作提示），
     * 但共用同一道 [LIMIT_NOTIFY_MS] 去重闸。
     */
    private fun reportDockWait() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLimitNotifyMs < LIMIT_NOTIFY_MS) return
        lastLimitNotifyMs = now
        val host = hostCtxRef ?: return
        showHint(host, dockWaitText(host))
    }

    /**
     * 把一句文案弹成**系统原生 Toast**（`android.widget.Toast`，见 [AppToast]）。
     *
     * ⚠️ 所有失败路径**一律吞掉并如实记日志**（[AppToast] 内部已经这么做了）：
     *   提示弹不出来是"要排查的事实"，但它**绝不该影响分屏主链**（⛔ 不许把异常抛回去）。
     * ★ 这里**不需要**再包一层 `runCatching` —— [AppToast] 自己吞并记 `HyperPlusToast` 标签。
     */
    private fun showHint(host: Context, text: String) {
        AppToast.show(host, text)
    }

    /**
     * 屏上提示条的文案。
     *
     * ⚠️ 中文是**用户原话**（「分屏数量已达上限」）⇒ ⛔ 不许顺手"优化"措辞。
     * ★ 三语表、以及"怎么按系统语言选"，现在**都住在 [EngineText]**（2026-10-05 统一搬家：
     *   那一版 `pickText` 提成了公共函数，因为「旋转失败」提示需要第二处调用）。
     *   ⇒ 本文件**不再自己判语言**（⛔ 那套 `when` 全工程只允许有一份）。
     */
    private fun limitText(host: Context): String =
        EngineText.pick(host, "分屏数量已达上限", "分屏數量已達上限", "Split screen limit reached")

    /**
     * **dock 待选态**下的提示文案：告诉用户"还要点一下应用才算数"。
     *
     * ★ 它存在的理由（2026-10-05 用户报"2→3、3→6 不顺畅"的调查结论，见 docs §15）：
     *   系统把"折一下"实现成「收成 dock 带子 + 空出下一格 + **等用户点一个 App**」——
     *   在用户点之前，**再折一下会被系统直接拒**（`mDockedState != null ⇒ return null`）。
     *   原来这条路径是**静默**的 ⇒ 用户看到的就是"折了没反应"。
     *   ⇒ 这一句把"静默失败"变成"有指令的等待"。
     * ⚠️ 与 [limitText] 同一条纪律：三语**逐字**，中文用用户能立刻照做的措辞。
     * ⛔ 「补齐」「补格」是内部术语，用户看不懂 ⇒ 说"**选一个应用**"（他要做的动作）。
     */
    private fun dockWaitText(host: Context): String =
        EngineText.pick(host, "请先选一个应用补齐当前格", "請先選一個應用補齊當前格", "Pick an app to fill this slot first")

    /**
     * 取**分屏的 root 任务** info —— 这就是 `OPTION_DOCK_TASKS` 真正要装的东西。
     *
     * 复刻官方 `MultipleSplitShellCommandHandler.runDockTasks` L77 的三分支（逐字）：
     * ```
     * inSoScFullMode()      ⇒ SoScUtilsImpl.getInstance().getSplitRootTaskInfo()   // 2 分屏
     * isMultipleSplitActive() ⇒ 多分屏 organizer 的 mSplitRootTaskInfo              // n≥3
     * 否则                   ⇒ null（⇒ 调用方安静放弃）
     * ```
     * ✅ 两条 getter 均已 `dexmethods` 实证存在：
     *   `SoScUtilsImpl.getInstance() -> SoScUtilsImpl`、
     *   `SoScUtilsImpl.getSplitRootTaskInfo() -> ActivityManager$RunningTaskInfo`。
     */
    private fun splitRootTaskInfoOrNull(): Any? {
        // 路 ①：SoSc 全屏（n=2）—— 从 SoSc 侧拿
        val sosc = runCatching {
            val cls = resolveAny(arrayOf("com.android.wm.shell.sosc.SoScUtilsImpl")) ?: return@runCatching null
            val inst = callObj(cls, "getInstance") ?: return@runCatching null
            if (callObj(inst, "inSoScFullMode") != true) return@runCatching null
            callObj(inst, "getSplitRootTaskInfo")
        }.getOrNull()
        if (sosc != null) {
            Log.i(TAG, "   分屏 root 来源：SoScUtilsImpl.getSplitRootTaskInfo()（inSoScFullMode=true）")
            return sosc
        }
        // 路 ②：多分屏（n≥3）—— 从 organizer 的字段拿
        val fromOrg = msplitOrganizerOrNull()?.let { readField(it, "mSplitRootTaskInfo") }
        if (fromOrg != null) {
            Log.i(TAG, "   分屏 root 来源：organizer.mSplitRootTaskInfo（多分屏已活跃）")
            return fromOrg
        }
        return null
    }

    /**
     * 取 `MultipleSplitRootTaskOrganizer`（多分屏的**真身执行体**）。
     *
     * 🔴🔴 **为什么不用 `MultiTaskingControllerImpl.getInstance()`** —— 2026-10-05 上机实测：
     *   它**恒返 null**。根因（jadx 逐字）：
     *   ```java
     *   // MultiTaskingControllerStub
     *   private static final MultiTaskingControllerStub sInstance =
     *       (MultiTaskingControllerStub) MiuiStubUtil.getInstance(MultiTaskingControllerStub.class);
     *   //                          ^^^^^ static final ⇒ 在**类初始化那一刻**就定格
     *   ```
     *   这是小米的 **MiuiStub 桩**：框架里放空壳，真身靠 `MiuiStubUtil` 注册替换。
     *   一旦这个类在 registry 就绪**之前**被加载 ⇒ `sInstance` 永久为 null，**之后也救不回来**。
     *   ⚠️ 且它是站不住脚的证据链：`MultiTaskingControllerImpl.Provider.SINGLETON.INSTANCE` 是
     *   `new MultiTaskingControllerImpl()` **裸构造**（无 Dagger 注入）⇒ 就算拿到，字段全 null，
     *   调 `getMultipleSplitController()` 只会 NPE。**这条路整个作废。**（见 `docs` §12）
     *
     * ✅ **改用一条从已持有实例出发的路**（不碰任何 MiuiStub）：
     *   ```
     *   MultipleSplitRootTaskOrganizer implements ShellTaskOrganizer.TaskListener   // ✅ 类声明逐字
     *   ShellTaskOrganizer.mTaskListeners : SparseArray<TaskListener>               // ✅ 字段逐字
     *   ```
     *   ⇒ 我们**已经**持有 `ShellTaskOrganizer`（[taskOrganizer]，从 `onTaskInfoChanged` 抓的）
     *   ⇒ 遍历 `mTaskListeners` 的 value，凡是 `instanceof MultipleSplitRootTaskOrganizer` 的就是它。
     *   ★ 这个对象**不是**我们反射 new 出来的，是系统自己建好并注册进去的**活体** ⇒ 字段全就绪。
     */
    private fun msplitOrganizerOrNull(): Any? {
        msplitOrganizer?.let { return it }
        val to = taskOrganizer
        if (to == null) {
            Log.w(TAG, "还没有 ShellTaskOrganizer（引擎刚起？）⇒ 暂时拿不到多分屏 organizer")
            return null
        }
        val arr = readField(to, "mTaskListeners")
        if (arr == null) {
            Log.w(TAG, "读不到 ShellTaskOrganizer.mTaskListeners（字段改名了？）")
            return null
        }
        val size = runCatching { arr.javaClass.getMethod("size").invoke(arr) as Int }
            .onFailure { Log.w(TAG, "调 mTaskListeners.size() 失败", it) }
            .getOrDefault(0)
        val valueAt = runCatching { arr.javaClass.getMethod("valueAt", Int::class.java) }
            .onFailure { Log.w(TAG, "拿 mTaskListeners.valueAt(int) 方法失败（SparseArray 泛型擦除？）", it) }
            .getOrNull()
        if (valueAt == null) {
            Log.w(TAG, "mTaskListeners 是个 ${arr.javaClass.name}，但取不到 valueAt(int) ⇒ 换 get(int) 兜底")
            // 兜底：SparseArray 也有 get(int key)，但这里没有 key —— 退化成只报 size
            Log.w(TAG, "mTaskListeners 共 $size 项，暂无法遍历（这是本机实测要解决的点）")
            return null
        }
        for (i in 0 until size) {
            val v = runCatching { valueAt.invoke(arr, i) }.getOrNull() ?: continue
            // ★ 按**类型名**判，不 import 它（模块编译期不该依赖 WMShell 内部类）
            if (v.javaClass.name == MSPLIT_ORG_NAME) {
                msplitOrganizer = v
                Log.i(TAG, "✅ 拿到 ${v.javaClass.simpleName}（来自 ShellTaskOrganizer.mTaskListeners 第 $i 项，共 $size 项）")
                return v
            }
        }
        Log.w(TAG, "mTaskListeners 共 $size 项，但没有 MultipleSplitRootTaskOrganizer（多分屏未初始化？）")
        return null
    }

    /**
     * 逐条复刻 `splitPrimaryTask` 的早退条件（jadx 逐字读过，共 5 条）。
     * ⚠️ 仅用于**诊断打日志**，不参与任何决策 —— 真正的判定交给系统自己。
     */
    private fun precheck(ctrl: Any, taskId: Int): String = runCatching {
        val visible = callObj(ctrl, "isSplitScreenVisible") as? Boolean
        val organizer = readField(ctrl, "mTaskOrganizer")
        val info = callObj(organizer, "getRunningTaskInfo", taskId)
        val type = callObj(info, "getActivityType") as? Int
        val supports = readField(info, "supportsSplitScreenMultiWindow") as? Boolean
        val isRoot = callObj(readField(ctrl, "mStageCoordinator"), "isRootOrStageRoot", taskId) as? Boolean
        buildString {
            append("已在分屏=").append(visible)
            append(" 任务存在=").append(info != null)
            append(" activityType=").append(type).append(if (type == 2) "(HOME)" else if (type == 3) "(RECENTS)" else "")
            append(" 支持分屏=").append(supports)
            append(" 是根任务=").append(isRoot)
        }
    }.getOrElse { "预检本身出错：$it" }

    /** 反射调方法（自动向上找父类；按**参数个数**匹配重载）。找不到 / 出错 ⇒ null */
    private fun callObj(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return null
        return runCatching {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                if (m != null) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
                c = c.superclass
            }
            null
        }.getOrNull()
    }

    /**
     * 「朝左 / 朝右」→ 系统的位置编号。
     *
     * ⚠️⚠️ **全项目唯一一处把方向语义映射到数字的地方，要改就改这里。**
     * 依据（§1.11.3）：同 taskId 单变量对照 —— `pos=0` 那次 `setSideStagePosition(1)`、
     * 动画起始 x=2364（屏宽，右缘滑入）⇒ 🔎 **推断 pos 0 = 左、pos 1 = 右**，
     * 且与用户口述「左滑是把 1 放在左边」吻合。
     * ⛔ **几何直读还没做**（§1.11.3 末尾）⇒ 若实机发现反了，**只改这一个函数**。
     */
    private fun posOf(dir: SplitUnfoldDirection?): Int = when (dir) {
        SplitUnfoldDirection.RIGHT -> 1
        else -> 0
    }

    /**
     * 当前**聚焦的、可见的**全屏任务 id。
     *
     * ★ 为什么这么绕：`splitPrimaryTask(pos, taskId)` 的 taskId 是**外部算好传进来的**
     *   （三指滑时由 system_server 侧给出）。我们同进程拿不到那个值，只能自己从
     *   `ShellTaskOrganizer.mTasks`（`SparseArray<taskId, TaskAppearedInfo>`）里找
     *   `isFocused && isVisible` 的那个 —— 这个字段名是 jadx 反编译 `ShellTaskOrganizer.dump()`
     *   时**逐字看到**的（`… + " focused=" + …isFocused`）。
     *
     * ⚠️ `splitPrimaryTask` 自己还会再筛一遍（activityType 不能是 HOME/RECENTS、
     *   `supportsSplitScreenMultiWindow` 必须为真、不能是 sosc 根任务）⇒ 我们只要挑出最可能的那个，
     *   剩下的**交给系统拒绝**。
     */
    private fun focusedTaskId(): Int = runCatching {
        // ★ 直接用捕获到的 organizer（不再绕"控制器 → mTaskOrganizer"那一跳）
        val to = taskOrganizer ?: return -1
        val tasks = readField(to, "mTasks") ?: return -1
        val size = runCatching { tasks.javaClass.getMethod("size").invoke(tasks) as Int }.getOrDefault(0)
        val valueAt = tasks.javaClass.getMethod("valueAt", Int::class.java)

        var fallback = -1
        for (i in 0 until size) {
            val info = runCatching { valueAt.invoke(tasks, i) }.getOrNull() ?: continue
            val ti = runCatching { info.javaClass.getMethod("getTaskInfo").invoke(info) }.getOrNull() ?: continue
            val id = intField(ti, "taskId") ?: continue
            val focused = intField(ti, "isFocused") == 1
            val visible = intField(ti, "isVisible") == 1
            if (visible && fallback < 0) fallback = id      // 兜底：个别时刻 focused 会短暂为 false
            if (focused && visible && id > 0) return id
        }
        fallback
    }.getOrDefault(-1)

    // ================================================================ 追加一格（n≥2）

    /**
     * 当前**多分屏 stage 数**（真值）。
     *
     * ★★★ 为什么必须是它 —— 这是被实测纠正过的地方（文档 §1.5.3 / §1.6.5）：
     *   `dumpsys window displays` 里 `name=stage_* visible=true` 的个数**会低报**，
     *   而且 SoSc 分屏时六个 stage 容器**恒在**（只是 `visible`/`sz` 在变）⇒ ⛔ 数容器必错。
     * 取法（按干净程度）：`IMultiTaskingStateManager.getActiveMultipleSplitStageCount()`
     *   → 反射 `MultipleSplitOrganizer.getCurrentActiveStageCount()`。
     * ⚠️ **拿不到就返 0** —— 0 会让调用方走 `splitPrimaryTask`（那条天然只做全屏→分屏），
     *   对已在分屏的场景它会自己 `return false` ⇒ **最坏情况是"什么都没发生"**，不会乱动。
     */
    private fun activeStageCount(): Int {
        // 路 ①：从多分屏组织者读 —— `getCurrentActiveStageCount()`（✅ dexmethods 实证存在）
        // ⚠️ 2026-10-05 修：这里原来整段包在一个 runCatching 里，**任一步抛异常就整体返 0**，
        //    导致后面的"SoSc 路"永远执行不到（上机实证：预检明明读到 isSplitScreenVisible=true，
        //    分流却走了 splitPrimaryTask）⇒ 现在**两条路各自 try**，互不牵连。
        val byOrg = runCatching {
            msplitOrganizerOrNull()?.let { callObj(it, "getCurrentActiveStageCount") as? Int }
        }.onFailure { Log.w(TAG, "读 organizer 的 stage 数失败（已吞）", it) }.getOrNull()
        if (byOrg != null && byOrg > 0) {
            Log.i(TAG, "   ↳ stage 数=$byOrg（organizer.getCurrentActiveStageCount）")
            return byOrg
        }
        // 路 ②：SoSc 是否在用（2 分屏）—— 用它把 0 和 2 分开
        val ctrl = controller
        if (ctrl == null) {
            Log.i(TAG, "   ↳ stage 数=0（organizer 读数=$byOrg，且 SoSc 控制器还没抓到）")
            return 0
        }
        val sosc = runCatching { callObj(ctrl, "isSplitScreenVisible") as? Boolean }
            .onFailure { Log.w(TAG, "读 isSplitScreenVisible 失败（已吞）", it) }.getOrNull()
        Log.i(TAG, "   ↳ stage 数：organizer 读数=$byOrg，SoSc 在分屏=$sosc（ctrl=${ctrl.javaClass.simpleName}）")
        return if (sosc == true) 2 else 0
    }

    /**
     * 按 taskId 取 `ActivityManager.RunningTaskInfo` **本体**（不是 id）。
     * ★ `dockSplitFromRecent` 的 Bundle 里要装**对象**（`getParcelable(OPTION_DOCK_TASKS)`）。
     * 数据源与 [focusedTaskId] 同一个（`ShellTaskOrganizer.mTasks` → `TaskAppearedInfo.getTaskInfo()`）。
     */
    private fun taskInfoOf(taskId: Int): Any? = runCatching {
        val to = taskOrganizer ?: return null
        val tasks = readField(to, "mTasks") ?: return null
        val size = runCatching { tasks.javaClass.getMethod("size").invoke(tasks) as Int }.getOrDefault(0)
        val valueAt = tasks.javaClass.getMethod("valueAt", Int::class.java)
        for (i in 0 until size) {
            val info = runCatching { valueAt.invoke(tasks, i) }.getOrNull() ?: continue
            val ti = runCatching { info.javaClass.getMethod("getTaskInfo").invoke(info) }.getOrNull() ?: continue
            if (intField(ti, "taskId") == taskId) return ti
        }
        null
    }.getOrNull()

    /** 在某个类里按候选名字逐个 `Class.forName`（返回第一个命中的） */
    private fun resolveAny(names: Array<String>): Class<*>? {
        val loaders = buildList {
            controller?.let { add(it.javaClass.classLoader) }
            runCatching { add(ClassLoader.getSystemClassLoader()) }
        }.distinct()
        for (n in names) for (l in loaders) {
            val c = runCatching { Class.forName(n, false, l) }.getOrNull()
            if (c != null) return c
        }
        return null
    }

    /**
     * 沿继承链读一个 int/boolean 字段（boolean 折成 1/0）；失败给 null
     */
    private fun intField(obj: Any, name: String): Int? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val fld = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (fld != null) {
                fld.isAccessible = true
                val v = fld.get(obj)
                return when {
                    v is Boolean -> if (v) 1 else 0
                    v is Int -> v
                    else -> null
                }
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    /** 沿继承链找字段并读值 */
    private fun readField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return runCatching {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                val fld = runCatching { c.getDeclaredField(name) }.getOrNull()
                if (fld != null) {
                    fld.isAccessible = true
                    return fld.get(obj)
                }
                c = c.superclass
            }
            null
        }.getOrNull()
    }

    // ================================================================ WMShell 主线程

    /**
     * 取 WMShell 的**主 Handler**。
     *
     * ★★★ 为什么非要它不可（2026-10-04 真机踩到）：
     *   `splitPrimaryTask` 内部走到 `Transitions.startTransition` 时会
     *   `HandlerExecutor.assertCurrentThread()`，**要求必须跑在 WMShell 自己的主线程上**。
     *   在传感器回调线程上调 ⇒ 抛 `IllegalStateException: must be called on Handler {…}`。
     *   ⚠️⚠️ 更糟的是：`splitPrimaryTask` **不是原子的** —— 它先改系统状态、再启过渡动画，
     *   动画那步抛异常 ⇒ **状态已脏但界面没变**（实测：屏幕被切成两半、
     *   SoSc 的 main/side stage 容器建了出来但 `visible=false sz=0`，且此后每次都返回 `false`）。
     *   ⇒ 拿不到 Handler 时**宁可什么都不做**，也绝不在错的线程上试。
     *
     * 取法（按可靠度）：`Transitions.mMainExecutor.mHandler` 正是报错信息里那个 Handler；
     * 其次是构造器注入的 `mMainHandler`；最后兜底 `mMainExecutor.mHandler`。
     */
    private fun wmShellMainHandler(ctrl: Any): Handler? {
        val candidates = listOf<() -> Any?>(
            { readField(readField(ctrl, "mTransitions"), "mMainExecutor").let { readField(it, "mHandler") } },
            { readField(ctrl, "mMainHandler") },
            { readField(readField(ctrl, "mMainExecutor"), "mHandler") },
        )
        for (get in candidates) {
            val h = runCatching { get() }.getOrNull() as? Handler ?: continue
            return h
        }
        return null
    }

    // ================================================================ 配置

    private fun mode(ctx: Context): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY, 0)
    }.getOrDefault(0)

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
}

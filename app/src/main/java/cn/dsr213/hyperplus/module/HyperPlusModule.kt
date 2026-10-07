package cn.dsr213.hyperplus.module

import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import cn.dsr213.hyperplus.ConfigChannel
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

/**
 * HyperPlus 的 LSPosed 模块入口。
 *
 * ============================ 新 API（libxposed，API 102，2026-10-03 迁移） ============================
 * 本类原先实现 legacy 的 `IXposedHookLoadPackage#handleLoadPackage`，由 `assets/xposed_init` 声明入口。
 * 2026-10-03 整体迁到 **libxposed**：入口改成继承 [XposedModule]、由
 * `META-INF/xposed/java_init.list` 声明；作用域走 `META-INF/xposed/scope.list`；
 * 版本走 `META-INF/xposed/module.prop`。清单里的五条 `xposed*` meta-data 全部删除。
 *
 * ★ 为什么非迁不可：legacy + 声明 nsp 的模块，LSPosed 会在模块页面弹
 *   「此模块使用了已废弃且即将移除的功能」—— 那条横幅由**清单声明 + 磁盘上存在其他人可读的
 *   xml** 共同决定，改代码消不掉，只有换 API 才能摘掉。
 *
 * ⚠️⚠️ **本模块的方法 hook 只有两个，都是可选的、默认不装的**：
 *   [SplitProbe]（2026-10-04，为"分屏增强"调查"系统原生新增分屏走哪条链"而加，**默认关**）、
 *   [SplitTrigger]（2026-10-04，功能 1 本体 —— 捕获 WMShell 控制器实例，
 *   三档开关与理由见它自己的类注释；★ 它的**折角传感器与校准通道不受开关限制**）。
 *   别把"只有一个 hook"当成"迁移没做完"：我们注入 SystemUI 的主要目的是
 *   **在那个进程里跑引擎**（借用它的进程身份与权限），不是去改它的行为。
 *   因此本次迁移**不涉及** Hooker / Chain / HookBuilder / Invoker 那一整套，
 *   只涉及"入口怎么写、配置从哪读"；真要用 hook 时的写法见 `docs/API102迁移_2026-10-03.md`，
 *   现成的调用样例就在 [SplitProbe.install] 里。
 *
 * ⚠️ 硬边界（官方原话）：`targetApiVersion >= 102` 的模块在 **classloader 层面被禁止访问 legacy 包**
 *   ⇒ `XposedHelpers` / `XSharedPreferences` / `XposedBridge.log` 一律不可用。
 *   所以本类里那次取宿主 Application 的操作改成了**自己写反射**（见 [hostApplication]）。
 *
 * ============================ 为什么引擎跑在 SystemUI 里 ============================
 * 用户拍板方案（2026-09-25）。相比"本 App 起前台服务"的做法，这样做的好处：
 *   1. **没有常驻通知** —— Android 不允许无通知的后台相机，前台服务必然带通知；
 *   2. **不需要「修改系统设置」授权页** —— SystemUI 自带的 `WRITE_SETTINGS` 是
 *      系统固定授予的（实测 `granted=true`），我们直接借用它的进程身份写方向；
 *   3. **开机自动生效** —— SystemUI 开机即起，模块随之加载，不需要 BOOT_COMPLETED；
 *      （Android 15+ 已明确禁止 BOOT_COMPLETED 接收器启动 camera 类型前台服务，
 *        会抛 ForegroundServiceStartNotAllowedException，所以前台服务那条路
 *        本来也做不到"开机生效"。）
 *
 * ★ 这一步真正修的是用户反馈的那个现象：
 *   「打开 App 页面之后是正常的，但回到桌面又被系统的自动旋转接管」——
 *   根因是引擎宿主是 Activity，`onStop()` 就会交还。宿主换成常驻的 SystemUI 之后，
 *   **不存在"退到后台"这回事**。
 *
 * ============================ 纪律（必须遵守） ============================
 * 本模块的代码运行在 **SystemUI 进程**里。SystemUI 崩溃 = 状态栏 / 导航栏崩溃，
 * 严重时用户进不了桌面。因此：
 *   - 入口只打一行日志就返回，真正的初始化延后 [START_DELAY_MS] 并丢到**后台线程**，
 *     绝不在目标进程的启动路径上同步做开相机这类耗时操作；
 *   - **每一处**都要包在 runCatching 里：任何未捕获异常都会杀掉整个进程。
 * ============================ 纪律（必须遵守） ============================
 */
class HyperPlusModule : XposedModule() {

    /** 由 [onModuleLoaded] 记下（`PackageReadyParam` 不带进程名，而日志里需要它） */
    @Volatile private var processName: String = "?"

    /**
     * 模块被装进**本进程**时调用一次。
     *
     * ★ 这里只打日志，**不做任何初始化** —— 官方明确要求：
     *   "Modules should not perform initialization before onModuleLoaded() is called"，
     *   而反过来，真正碰宿主（SystemUI）的动作要等 [onPackageReady]。
     */
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        runCatching {
            val loaded =
                "模块已加载：框架=${frameworkName} ${frameworkVersion}(code=$frameworkVersionCode, " +
                    "api=$apiVersion) 进程=${param.processName} " +
                    "systemServer=${param.isSystemServer} myPid=${Process.myPid()} myUid=${Process.myUid()}"
            Log.i(TAG, loaded)
            // ★ 同时落盘：logcat 缓冲只保 ~10 分钟，而这条是「模块到底有没有起来」的关键证据
            BootReport.note(loaded)
        }
    }

    /**
     * 宿主的 classloader 就绪、即将创建 Application 时调用。
     *
     * ★ 只认 `com.android.systemui`（与 `META-INF/xposed/scope.list` 一致）。
     *   作用域理论上已经把我们限死在 SystemUI，但**这道判断必须留着** ——
     *   它保证"哪怕用户手滑把作用域勾到别的应用，那边也不会跑起引擎来"。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PKG) return

        runCatching {
            val injected =
                "★★★ HyperPlus 模块注入成功 package=${param.packageName} " +
                    "process=$processName first=${param.isFirstPackage} " +
                    "myPid=${Process.myPid()} myUid=${Process.myUid()}"
            Log.i(TAG, injected)
            BootReport.note(injected)
        }

        val loader = param.classLoader

        // ★★★ 2026-10-06：多分屏上限提升 —— **必须在这里同步装，⛔ 不能挪进下面延迟 4 秒的 boot()**。
        //   理由：它要赶在 `MultipleSplitOrganizer.<clinit>` **之前**挂上钩（`MAX_STAGES` 是
        //   `<clinit>` 里算出来的静态字段），而 WM Shell 的初始化远早于 4 秒。
        //   ⚠️ 这条路径上只做两次 `Class.forName(..., false, ...)` + 几次 hook，无 I/O，
        //      不会拖慢 SystemUI 启动（见 [SplitStageLimit] 的时序说明）。
        runCatching { SplitStageLimit.install(this, loader) }
            .onFailure {
                Log.e(TAG, "装多分屏上限提升失败（已吞掉，不影响 SystemUI）", it)
                BootReport.note("装多分屏上限提升失败：${it.javaClass.simpleName}: ${it.message}")
            }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { boot(loader) }
                .onFailure { Log.e(TAG, "模块初始化失败（已吞掉，不影响 SystemUI）", it) }
        }, START_DELAY_MS)
    }

    // ==================================================================== 初始化

    private fun boot(classLoader: ClassLoader) {
        // 工作丢到独立后台线程：主机（SystemUI）启动路径上不能有重活
        val thread = android.os.HandlerThread("hyperplus-boot")
        // ⚠️⚠️ `start()` 不能少：`HandlerThread.getLooper()` 在 `!isAlive()` 时**直接返回 null**
        //    （不是阻塞等待），于是 `Handler(null)` 会抛
        //    「Attempt to read from field 'MessageQueue Looper.mQueue' on a null object reference」。
        //    2026-10-03 迁移时漏了这一行 —— 实测后果：模块注入成功但**引擎根本没起来**，
        //    而异常被 `runCatching` 吞掉，只留一行 E，日志里看不出"引擎没跑"这件事。
        thread.start()
        Handler(thread.looper).post {
            runCatching { doBoot(classLoader) }
                .onFailure { Log.e(TAG, "doBoot 异常（已吞掉）", it) }
        }
    }

    private fun doBoot(classLoader: ClassLoader) {
        // ① 宿主（SystemUI）的 Application —— 权限与 uid 都是它的
        val hostCtx = runCatching { hostApplication(classLoader) }.getOrNull()
        if (hostCtx == null) {
            Log.e(TAG, "拿不到宿主 Application，模块无法工作")
            return
        }

        // ★ 拿到宿主 Context ⇒ 立刻把「启动摘要」落盘（含早于此处攒下的注入 / 钩子记录）
        BootReport.reset(hostCtx)

        // ② 本 App 的 Context —— 用来定位我们自己的资源与 native 库。
        //    走 HostEnv：它会把上下文包一层，修掉 createPackageContext() 的
        //    getApplicationContext() == null（ML Kit 会在这里 NPE，实测踩过）
        //    ⚠️ 包名常量只有一份真值（[ConfigChannel.APP_PKG]），别在这儿再写一份字面量。
        val appCtx = HostEnv.appContext(hostCtx, ConfigChannel.APP_PKG)

        Log.i(
            TAG,
            "初始化：宿主=${hostCtx.packageName}(uid=${hostCtx.applicationInfo?.uid}) " +
                "本App上下文=${appCtx != null} " +
                "nativeLibDir=${appCtx?.applicationInfo?.nativeLibraryDir}",
        )

        // ③ 完整自检 —— **默认不跑**，见 [AUTO_SELFCHECK_KEY] 的注释
        if (isAutoSelfCheckOn(hostCtx)) {
            runCatching { ModuleSelfCheck.run(hostCtx, appCtx) }
                .onFailure { Log.e(TAG, "自检过程异常（已吞掉）", it) }
        } else {
            Log.i(TAG, "跳过启动自检（要跑：adb shell settings put global $AUTO_SELFCHECK_KEY 1）")
        }

        // ④ 复测通道：不必重启状态栏就能随时重跑自检
        runCatching { installSelfCheckTrigger(hostCtx, appCtx) }
            .onFailure { Log.e(TAG, "安装自检触发失败（已吞掉）", it) }

        // ⑤ ★ 引擎搬进 SystemUI —— 本模块的主线任务
        runCatching { EngineHost.start(hostCtx, appCtx, classLoader) }
            .onFailure { Log.e(TAG, "EngineHost 启动失败（已吞掉）", it) }

        // ⑥ ★ 分屏观察钩子（2026-10-04 新增，**默认关闭**的调查工具）。
        //   ⚠️ 位置：**在引擎之后**。引擎是主线，探针是临时工具 —— 两者的顺序表达优先级，
        //     而且探针若因某种原因卡住，也不该挡住引擎启动（它是后台线程上的同步调用）。
        //   ⚠️ 它**默认什么都不做**：先读 `Settings.Global` 的开关，关着就只打一行日志返回，
        //     一个 hook 都不装。理由见 [SplitProbe] 类注释（常驻 hook 156 个方法是拿稳定性换便利）。
        runCatching { SplitProbe.install(this, hostCtx, classLoader) }
            .onFailure { Log.e(TAG, "分屏探针安装失败（已吞掉）", it) }

        // ⑦ ★ 分屏触发器（2026-10-04 新增，功能 1：**轻折一下 → 分屏 +1**，**默认关闭**）。
        //   ⚠️ 它先读三档开关（`Settings.Global` 的 [SplitTrigger.KEY]）：
        //     0 = 关 / 1 = 只观察（只打日志，绝不进分屏）/ 2 = 真动作。
        //   ★★ **0 档不再"什么都不装"**（2026-10-04 晚改）：折角传感器与「角度校准」
        //     那条通道**照常装**——不然用户得先想办法把功能打开，才能校准它的阈值。
        //     真正有风险的 hook 仍然只在 1 / 2 档挂。理由见 [SplitTrigger] 类注释。
        //   ⚠️ 放在探针**之后**：探针是调查工具，它才是产品功能；两者互不依赖，
        //     顺序只决定读日志时谁先出现。
        runCatching { SplitTrigger.install(this, hostCtx, classLoader) }
            .onFailure { Log.e(TAG, "分屏触发器安装失败（已吞掉）", it) }
    }

    /**
     * 取宿主进程的 `Application` —— 即 `ActivityThread.currentActivityThread().getApplication()`。
     *
     * ★★ 这里为什么是**手写反射**而不是一行 `XposedHelpers.callStaticMethod(...)`：
     *   新 API 下 `de.robv.android.xposed.*` **连 import 都不允许**（见类注释的硬边界），
     *   而且官方明确 **不再提供 `XposedHelpers`**（替代品 `libxposed/helper` 官方标为
     *   "Developing"，暂不引入）。
     *
     * ⚠️ **行为与旧写法等价**：`XposedHelpers.findClass/callStaticMethod/callMethod` 内部也就是
     *   `Class.forName(name, false, loader)` + `getDeclaredMethod` + `setAccessible(true)`。
     *   旧版跑通了，这套就一样跑得通（隐藏 API 限制在 LSPosed 注入的进程里本就已解除）。
     *   万一将来这里被拦，改用官方 `getInvoker(method)`（框架侧调用，绕过限制）即可：
     *   `getInvoker(atCls.getDeclaredMethod("currentActivityThread")).invoke(null)`。
     */
    private fun hostApplication(classLoader: ClassLoader): Context? {
        val atCls = Class.forName("android.app.ActivityThread", false, classLoader)
        val at = atCls.getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        return atCls.getDeclaredMethod("getApplication")
            .apply { isAccessible = true }
            .invoke(at) as? Context
    }

    /**
     * ★ 为什么完整自检要默认关掉（2026-09-25 改动）：
     *   自检里有一项会**真的打开相机**（逐尺寸试会话，约 1s）。引擎现在也住在同一个
     *   进程里、也需要相机 —— 开机时两者会互相抢，最坏情况是把引擎的第一轮 burst 挤掉，
     *   留下一条"相机被占用"的假故障。所以：
     *     - 自检改为**按需触发**（[SELFCHECK_KEY] / [AUTO_SELFCHECK_KEY]）；
     *     - 装机后要验收，显式打开一次即可，命令行在日志里也打出来了。
     */
    private fun isAutoSelfCheckOn(hostCtx: Context): Boolean = runCatching {
        Settings.Global.getInt(hostCtx.contentResolver, AUTO_SELFCHECK_KEY, 0) != 0
    }.getOrDefault(false)

    /**
     * 开发用复测通道：监听一个 Settings.Global 键，值变化即重跑自检。
     *
     * ★ 为什么用"设置键 + ContentObserver"而不是轮询文件：
     *   - `/data/local/tmp` 属于 shell_data_file，platform_app 域**没有**读写权限
     *     （探针实测：往那里写报告返回 false）；
     *   - 轮询会持续唤醒进程；ContentObserver 是零成本通知，只有真被改才醒。
     * 触发方式：
     *   adb shell settings put global hyperplus_selfcheck 1
     */
    private fun installSelfCheckTrigger(hostCtx: Context, appCtx: Context?) {
        val cr = hostCtx.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val v = runCatching { Settings.Global.getInt(cr, SELFCHECK_KEY, 0) }.getOrDefault(0)
                if (v == 0) return
                runCatching { Settings.Global.putInt(cr, SELFCHECK_KEY, 0) }  // 复位，可反复触发
                Log.i(TAG, "收到复测信号 → 重跑自检")
                // ★ 新开线程跑：SelfCheck 内部会阻塞等回调，主线程绝不能被占住
                Thread {
                    runCatching { ModuleSelfCheck.run(hostCtx, appCtx) }
                        .onFailure { Log.e(TAG, "复测异常（已吞掉）", it) }
                }.start()
            }
        }
        cr.registerContentObserver(Settings.Global.getUriFor(SELFCHECK_KEY), false, observer)
        Log.i(TAG, "复测通道就绪：adb shell settings put global $SELFCHECK_KEY 1")
    }

    companion object {
        const val TAG = "HyperPlusModule"

        // ★ 2026-10-03：这里原先有一个 `APP_PKG` 常量，现已删除 —— 包名只留一份真值
        //   [ConfigChannel.APP_PKG]（本类在 [doBoot] 里直接引用它）。
        private const val TARGET_PKG = "com.android.systemui"

        /** 开发用复测通道的设置键（见 installSelfCheckTrigger） */
        private const val SELFCHECK_KEY = "hyperplus_selfcheck"

        /** 置 1 则每次 SystemUI 启动都跑完整自检（默认关，理由见 isAutoSelfCheckOn） */
        private const val AUTO_SELFCHECK_KEY = "hyperplus_selfcheck_auto"

        /** 注入点位于 SystemUI 启动路径上，必须延后 */
        private const val START_DELAY_MS = 4_000L

        /** 自检报告落盘位置（SystemUI 的 DE 存储，可读、不污染用户数据） */
        fun reportFile(hostCtx: Context): File =
            File(hostCtx.filesDir, "hyperplus_selfcheck.txt")
    }
}

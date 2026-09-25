package cn.dsr213.hyperplus.module

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * HyperPlus 的 LSPosed 模块入口。
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
class HyperPlusModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只注入 SystemUI（与 res/values/arrays.xml 的 xposed_scope 一致）
        if (lpparam.packageName != TARGET_PKG) return

        Log.i(
            TAG,
            "★★★ HyperPlus 模块注入成功 package=${lpparam.packageName} " +
                "process=${lpparam.processName} first=${lpparam.isFirstApplication} " +
                "myPid=${Process.myPid()} myUid=${Process.myUid()}",
        )

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { boot(lpparam) }
                .onFailure { Log.e(TAG, "模块初始化失败（已吞掉，不影响 SystemUI）", it) }
        }, START_DELAY_MS)
    }

    // ==================================================================== 初始化

    private fun boot(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 工作丢到独立后台线程：主机（SystemUI）启动路径上不能有重活
        val thread = android.os.HandlerThread("hyperplus-boot")
        thread.start()
        Handler(thread.looper).post {
            runCatching { doBoot(lpparam) }
                .onFailure { Log.e(TAG, "doBoot 异常（已吞掉）", it) }
        }
    }

    private fun doBoot(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ① 宿主（SystemUI）的 Application —— 权限与 uid 都是它的
        val hostCtx = runCatching {
            val atCls = XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader)
            val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
            XposedHelpers.callMethod(at, "getApplication") as? Context
        }.getOrNull()
        if (hostCtx == null) {
            Log.e(TAG, "拿不到宿主 Application，模块无法工作")
            return
        }

        // ② 本 App 的 Context —— 用来定位我们自己的资源与 native 库。
        //    走 HostEnv：它会把上下文包一层，修掉 createPackageContext() 的
        //    getApplicationContext() == null（ML Kit 会在这里 NPE，实测踩过）
        val appCtx = HostEnv.appContext(hostCtx, APP_PKG)

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
        runCatching { EngineHost.start(hostCtx, appCtx, lpparam.classLoader) }
            .onFailure { Log.e(TAG, "EngineHost 启动失败（已吞掉）", it) }
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
        const val APP_PKG = "cn.dsr213.hyperplus"
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

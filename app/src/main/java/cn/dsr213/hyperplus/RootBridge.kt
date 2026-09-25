package cn.dsr213.hyperplus

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * App → 跨进程镜像的**写入代理**：借 root 的 `settings` 命令替我们写系统设置。
 *
 * ============================ 为什么不能用 `pm grant`（实测 + 源码实证，2026-09-25） ============================
 * 最初的思路是"root 一条 `pm grant WRITE_SECURE_SETTINGS` 就完事"。**这条思路是错的**，
 * 而且错得很隐蔽：`pm grant` 确实会成功 —— `dumpsys package` 里明明白白写着
 * `android.permission.WRITE_SECURE_SETTINGS: granted=true`，`checkSelfPermission` 也返回
 * `GRANTED` —— **但写入照样被拒**。
 *
 * 原因是 AOSP `SettingsProvider.mutateSystemSetting()` 的判定顺序：
 *
 * ```java
 * if (!hasWriteSecureSettingsPermission()) {      // ← 有权限就跳过 AppOps 检查
 *     if (!Settings.checkAndNoteWriteSettingsOperation(...)) return false;
 * }
 * ...
 * // 然后**无条件**走这道闸：
 * enforceRestrictedSystemSettingsMutationForCallingPackage(operation, name, callingUserId);
 * ```
 *
 * 而那道闸里只认三样东西：
 *
 * ```java
 * final int appId = UserHandle.getAppId(callingUid);
 * if (appId == SYSTEM_UID || appId == SHELL_UID || appId == ROOT_UID) return;   // ← ① uid
 * if (Settings.System.PUBLIC_SETTINGS.contains(name)) return;                  // ← ② 公开键
 * if ((packageInfo.applicationInfo.privateFlags & PRIVATE_FLAG_PRIVILEGED) != 0) return; // ← ③ 特权包
 * warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk(targetSdk, name);  // ← 否则抛
 * ```
 *
 * ⇒ **`WRITE_SECURE_SETTINGS` 完全不在这道闸的判断里**，它只影响前面那道 AppOps 检查。
 *   而 `PRIVATE_FLAG_PRIVILEGED` 是**安装期**由"APK 落在 `/system/priv-app` + 特权白名单"决定的，
 *   事后无法用 `pm grant` 改出来。
 *
 * 于是三条路里只剩**第一条 uid 豁免**是可达的：让 uid 真的变成 0。
 * 我们做不到"把自己的进程变成 root"，但可以**让 root 进程替我们写** —— `su -c settings put ...`。
 * 本机实测：以 App 的 uid 执行 `su -c "settings put system <自定义键> <值>"` → 写入成功、可回读。
 *
 * ============================ 代价与取舍 ============================
 * 每次写要 spawn 一个 `su` 进程（约 100~300ms）。因此：
 *   - **绝不在主线程调用** —— [AppPrefs] 把它的全部镜像写入都丢到了一个专用后台线程；
 *   - 写入频率极低（只有用户改开关 / 标定时才发生），不构成负担。
 *
 * ★ root 只需授权一次，KernelSU 会记住"始终允许"；只有卸载重装才会再问。
 *   没 root 也能用：单机模式（引擎跑在 App 进程里）完全不依赖这条通道。
 */
object RootBridge {

    private const val TAG = "HyperPlusRoot"

    /** 记住"上次已经拿到过 root"，用于启动时**静默**复探 —— 避免每次开 App 都弹授权框 */
    private const val SP_NAME = "hyperplus_root"
    private const val K_GRANTED = "granted_at_least_once"

    /** 探针结果：0=未知（还没问过） 1=可用 -1=不可用 */
    @Volatile
    private var state = 0

    /** 是否已经探过（无论成功失败） */
    val probed: Boolean get() = state != 0

    /** root 是否可用。**只有探针成功过才返回 true**（不猜）。 */
    fun available(): Boolean = state == 1

    /** 启动时的静默复探：**只有历史上成功授权过**才真去跑（否则会平白弹一次授权框） */
    fun shouldAutoProbe(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getBoolean(K_GRANTED, false)
    }.getOrDefault(false)

    /**
     * 【**不要在主线程调用**】探一次 root 可用性。
     *
     * ★ 首次会弹出 root 管理器的授权框（KernelSU/Magisk），用户点「允许」即可。
     *   点过一次之后系统会记住，之后不再弹。
     *
     * @return 可读结果；[silent] 且从未授权过时返回 null（= 什么都没做）
     */
    fun probe(ctx: Context, silent: Boolean = false): String? {
        if (available()) return "✅ root 已可用（无需再次申请）"
        if (silent && !shouldAutoProbe(ctx)) return null   // 静默模式下不主动弹框

        val out = shell("id")
        val ok = out != null && out.contains("uid=0")
        mark(ctx, ok)
        Log.i(TAG, "root 探针 → ${if (ok) "可用" else "不可用"}（$out）")
        return if (ok) {
            "✅ root 已授予，配置同步已就绪"
        } else {
            "❌ root 不可用${if (out.isNullOrEmpty()) "（没弹出授权框，或本机没有 root）" else "：$out"}。" +
                "没有 root 也能用单机模式，只是退到后台不再生效。"
        }
    }

    /** 记下探针结果 + 落盘"曾授权过"，供下次启动静默复探 */
    private fun mark(ctx: Context, ok: Boolean) {
        state = if (ok) 1 else -1
        runCatching {
            ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(K_GRANTED, ok).apply()
        }
    }

    /** 重置探针状态，让界面上的按钮可以再试一次 */
    fun resetProbe() {
        state = 0
    }

    /**
     * 【**不要在主线程调用**】以 root 身份写一个 `Settings.System` 键。
     *
     * ★ 键名与值都只包含 `[A-Za-z0-9_.+-]`（见 [PrefsBridge] 的常量），
     *   外面再套一层单引号即可安全 —— 不需要做通用 shell 转义。
     */
    fun putSystem(key: String, value: String): Boolean {
        val out = shell("settings put system '$key' '$value'") ?: return false
        // `settings put` 成功时无输出；有输出多半是权限/语法错误
        if (out.isNotEmpty()) Log.w(TAG, "settings put system $key 输出：$out")
        // 写成功了 ⇒ root 必然可用（这不是猜测，是这次调用本身证明了）
        if (state != 1) {
            state = 1
            Log.i(TAG, "由一次成功的 root 写入推断：root 可用")
        }
        return true
    }

    /** 【**不要在主线程调用**】以 root 身份删除一个 `Settings.System` 键（用于测试/清理） */
    fun deleteSystem(key: String): Boolean = shell("settings delete system '$key'") != null

    /**
     * 跑一条 `su -c <cmd>`，返回 stdout+stderr（已 trim）。
     * 失败（没有 su / 被拒 / 超时）返回 null。
     */
    private fun shell(cmd: String): String? {
        Log.i(TAG, "su -c \"$cmd\"")
        return runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                runCatching { p.destroy() }
                Log.w(TAG, "su 超时 ${TIMEOUT_SEC}s")
                return@runCatching null
            }
            if (p.exitValue() != 0) {
                Log.w(TAG, "su 退出码=${p.exitValue()} 输出=$out")
                return@runCatching if (out.isEmpty()) null else out
            }
            out
        }.getOrElse {
            Log.w(TAG, "无法执行 su", it)
            null
        }
    }

    private const val TIMEOUT_SEC = 30L
}

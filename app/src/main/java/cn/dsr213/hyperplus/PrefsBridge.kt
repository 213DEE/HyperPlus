package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 跨进程配置桥 —— App 进程 ⇄ SystemUI 进程。
 *
 * ============================ 为什么需要它 ============================
 * 引擎搬进了 SystemUI（见 `HyperPlusModule` 的注释），但配置的**编辑入口**仍在 App 界面。
 * 两个进程各有各的 SharedPreferences，天然不共享；LSPosed 本身也不提供双向数据通道。
 *
 * 这里选 `Settings.System` 作为「跨进程的状态/配置载体」：
 *
 *  ✅ **读免费** —— 任何应用都能读系统设置，两边零门槛；
 *  ✅ **宿主编写得进去** —— SystemUI 是特权包（`PRIVATE_FLAG_PRIVILEGED`），
 *     自定义键直写即可落库（`hyperplus_bus_engine_state` 实测就在库里）；
 *  ✅ **App 侧也有办法写** —— 借 root 的 uid 代写，见下面那个"决定性的坑"；
 *  ✅ **天然持久化** —— 重启后我们的进程还没起来，值就已经在库里了，
 *     不需要另做「开机同步」这一步；孤儿接管检测也因此能跨重启成立；
 *  ✅ **变更通知零成本** —— `ContentObserver` 是本项目实测过的机制（自检复测通道同款），
 *     零轮询，只有真被改才醒；
 *  ✅ 同一个 `SettingsProvider` 同时被两个进程访问，不存在「谁先谁后」的时序问题。
 *
 *  ⚠️ **代价（已向用户说明并取得同意）**：这些键会落在系统设置库里，同设备其他应用可读。
 *     里面不含任何敏感信息，只有模式 / 策略 / 标定参数 / 引擎状态。
 *
 * ============================ 键名约定 ============================
 * 配置键用「本地名」在 [AppPrefs] 与 [HyperPlusModule] / [cn.dsr213.hyperplus.module.EngineHost]
 * 之间传递，落库时统一加前缀 `hyperplus_` ——
 * 这样**本地 SP 的键名一个字都不用改**，两边代码可以共用同一份常量。
 *
 * ============================ ⛔ 一个决定性的坑（实测，2026-09-25） ============================
 * **写自定义键这件事，"有权限"和"写得进去"是两回事。**
 * `SettingsProvider` 对不在 `Settings.System.PUBLIC_SETTINGS` 白名单里的键会走
 * `warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk`，而 targetSdk > 22 就是直接抛：
 *
 * ```
 * java.lang.IllegalArgumentException: You cannot keep your settings in the secure settings.
 *   at android.provider.Settings$System.putString(Settings.java:4617)
 * ```
 *
 * 我们先后试过、并**逐一实证否掉**了两条看似可行的路：
 *   - ❌ **`WRITE_SETTINGS`（app-op 已 `allow`）** → 照样抛。它只对公开键有意义。
 *   - ❌ **root `pm grant WRITE_SECURE_SETTINGS`** → `dumpsys` 里 `granted=true`，
 *     `checkSelfPermission` 也返回 `GRANTED`，**但写入仍然抛**。
 *     读 AOSP 源码才看清判定顺序：[SettingsProvider.mutateSystemSetting] 里，
 *     该权限**只用来跳过前面的 AppOps 检查**，后面那道
 *     `enforceRestrictedSystemSettingsMutationForCallingPackage` 是**无条件**执行的，
 *     而它只认 SYSTEM/SHELL/ROOT uid、公开键、`PRIVATE_FLAG_PRIVILEGED`（特权包，安装期决定）。
 *     详见 [RootBridge] 的类注释（那里有逐段源码引用）。
 *
 * ★ 三条实测证据合起来才定位得出来：
 *   - ❌ 本 App（无论有没有 `WRITE_SECURE_SETTINGS`）→ 抛上面那句；
 *   - ✅ SystemUI 写同样的自定义键**成功**（`hyperplus_bus_engine_state` 就落在库里）
 *        —— 它是特权包，命中第三条豁免；
 *   - ✅ shell（`adb shell settings put system <自定义键>`）也成功 —— 命中第一条 uid 豁免。
 *
 * ⇒ 解法（用户拍板「有 root，直接用最方便的」）：**借 root 的 uid 替我们写** ——
 *   以 App 的 uid 执行 `su -c "settings put system <键> <值>"`（实测可行）。
 *   见 [RootBridge]；[writeString] / [writeInt] 里做了「先直写、失败就转 root」的自动降级，
 *   所以**调用方不需要关心自己跑在哪个进程**。
 *   没 root 也不影响单机模式（引擎在 App 进程里，根本不走这条通道）。
 *
 * ⚠️ 两条写不进去时的表现（界面必须如实告知，不能假装成功）：App 侧直写被拒且 root 不可用
 *   → [AppPrefs.mirrorProblem] 置位 → 界面弹出「用 root 同步配置」卡片。
 */
internal object PrefsBridge {

    private const val TAG = "HyperPlusPrefs"

    private const val PREFIX = "hyperplus_"

    /** 本地键名 → 系统设置库里的键名 */
    fun full(localKey: String): String = PREFIX + localKey

    // ---------------------------------------------------------------- 配置键（本地名）

    const val MODE = "rotate_mode"
    const val STRATEGY = "capture_strategy"
    const val SIGN = "calib_sign"
    const val OFFSET = "calib_offset"
    const val RESTORE = "restore_auto_rotate"
    const val TAKEOVER = "takeover_active"

    /** 需要被**宿主实时跟随**的全部配置键 */
    val WATCHED = listOf(MODE, STRATEGY, SIGN, OFFSET, RESTORE, TAKEOVER)

    // ---------------------------------------------------------------- 总线键（完整名）

    /** App → 宿主：探活。App 写自增的 nonce，宿主把原值回写 [PONG] 作为回执 */
    val PING = PREFIX + "bus_ping"

    /** 宿主 → App：探活回执 */
    val PONG = PREFIX + "bus_pong"

    /** 宿主 → App：引擎状态摘要（格式见 [cn.dsr213.hyperplus.module.EngineHost.summary]） */
    val STATE = PREFIX + "bus_engine_state"

    /** 宿主 → App：心跳（elapsedRealtime/1000，仅用于显示「最后活跃」） */
    val HEARTBEAT = PREFIX + "bus_engine_heartbeat"

    /**
     * App → 宿主：请求一次标定采样。
     *  0 = 无请求；1 = 记竖屏基准；2 = 记左横屏（定方向）
     */
    val CALIB_REQ = PREFIX + "bus_calib_req"

    /** 宿主 → App：标定结果 `"<step>|<status>"`（status ∈ ok/noface/badangle/starting/stopped） */
    val CALIB_RESULT = PREFIX + "bus_calib_result"

    // ---------------------------------------------------------------- 读写

    fun uri(key: String): Uri = Settings.System.getUriFor(key)

    fun readString(cr: ContentResolver, key: String): String? =
        runCatching { Settings.System.getString(cr, key) }.getOrNull()

    fun readInt(cr: ContentResolver, key: String, def: Int): Int =
        runCatching { Settings.System.getInt(cr, key, def) }.getOrDefault(def)

    /**
     * 写系统设置。**调用方不需要关心自己在哪个进程** —— 这里做自动降级：
     *
     *   ① **直写**：SystemUI 侧（特权包）一次成功，零开销；
     *   ② 直写失败（App 侧必然如此，见类注释）→ **转 root** `settings put system`。
     *
     * ⚠️ 第②步会 spawn 一个 `su` 进程（100~300ms），**必须在后台线程调用**
     *   （[AppPrefs] 已经把全部镜像写入丢到了专用后台线程）。
     *
     * @return 是否真的写进去了。两种情况都失败才返回 false（界面据此提示，不假装成功）。
     */
    fun writeString(cr: ContentResolver, key: String, v: String): Boolean {
        if (runCatching { Settings.System.putString(cr, key, v) }
                .onFailure { Log.w(TAG, "直写 $key 被拒", it) }
                .getOrDefault(false)
        ) return true
        Log.i(TAG, "直写 $key 未生效 → 转 root 写入")
        return RootBridge.putSystem(key, v)
    }

    fun writeInt(cr: ContentResolver, key: String, v: Int): Boolean {
        if (runCatching { Settings.System.putInt(cr, key, v) }
                .onFailure { Log.w(TAG, "直写 $key 被拒", it) }
                .getOrDefault(false)
        ) return true
        Log.i(TAG, "直写 $key 未生效 → 转 root 写入")
        return RootBridge.putSystem(key, v.toString())
    }

    /**
     * 注册监听。
     *
     * ★ 注意投递线程由 **`ContentObserver(Handler)` 构造函数**决定，不是由这里传参 ——
     *   `ContentResolver.registerContentObserver` 没有带 Handler 的重载（编译不过，试过）。
     *   所以调用方必须 new 一个带 handler 的 observer；宿主侧请给它一个专用 HandlerThread，
     *   **绝不要用 SystemUI 主线程**。
     */
    fun watch(
        cr: ContentResolver,
        key: String,
        observer: ContentObserver,
    ): Boolean = runCatching {
        cr.registerContentObserver(uri(key), false, observer)
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- ⚠️ 没有 canWrite / writeVerified
    //
    // 这里刻意**不提供**「判权限」和「写+回读」两个便利方法：
    //
    //  - 判权限：写过一次自定义键的真实判据只有"特权包 / root uid"（见类注释），
    //    而这两者要么查不到、要么必须真跑一次才知道。任何"先判再写"的 API 都只会
    //    给出一个可能错的答案，进而让界面谎报状态。**直接写、以结果为准**才诚实。
    //  - 写+回读：已经统一收在 [AppPrefs.mirror] 里了（它同时负责置位 mirrorProblem）。
    //    在桥这一层再提供一个，只会诱使调用方绕过 mirrorProblem 的记账。
}

package cn.dsr213.hyperplus

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * 配置下行通道的**运输层**（2026-10-03 新增，为摆脱 LSPosed nsp 而做）。
 *
 * ============================ 它替代了什么 ============================
 * 换掉之前是「App 写 prefs 文件 → 引擎用 `XSharedPreferences` 读那个文件」，
 * 而那个文件之所以能被引擎读到，**完全靠 LSPosed 的 New XSharedPreferences（nsp）**：
 * 框架在本应用进程里 hook `ContextImpl.getPreferencesDir()`，把 prefs 重定向到
 * SELinux 全局可读的 safe-zone（`/data/misc/apexdata/<uuid>/prefs/<pkg>/`，文件 664）。
 *
 * ⚠️⚠️ **nsp 已被 LSPosed 官方标记为废弃、计划 2.3.0 移除**，v2.2.0 起模块页会弹
 * 「此模块使用了已废弃且即将移除的功能」。⇒ 必须换掉。
 *
 * ★★ 而「把 `xposedminversion` 降到 82、删掉 `xposedsharedprefs`」这条**看起来最省事**的路
 * **已经证否**：那两个 hook 就装在 nsp 的判据分支**里面**，关掉 nsp 等于关掉它们；
 * 而 App 私有目录是 **700**，SystemUI 连遍历都做不到。
 * 证据链见 `docs/配置通道_nsp废弃警告_2026-10-03.md` §2.4（官方源码 + 真机权限双证）。
 *
 * ============================ 现行设计（用户 2026-10-03 拍板「乙 广播 + 引擎代写」） ====
 * ```
 *   App 改设置 ⇒ 写自己的私有 prefs（零权限）
 *             ⇒ 变更监听触发 ⇒ 防抖 200ms ⇒ 把**全量快照**用一条广播发出去
 *   引擎收到广播 ⇒ 解析 ⇒ ① 更新内存镜像 ② 落一份到 Settings.System（它写得了）
 *                       ③ 通知订阅者（EngineHost 那套原样复用）
 *   引擎启动时  ⇒ 先从 Settings.System 把镜像读回来（不依赖 App 在不在跑）
 * ```
 *
 * ★ 为什么推**全量快照**而不是"改了哪个键"：
 *   与原来那条轮询链路的判据一致（`ModulePrefs` 的订阅者比的都是**整体快照**），
 *   而且请求类键（[PrefsBridge.CALIB_REQ] / [PrefsBridge.HINT_TEST] /
 *   [PrefsBridge.UNCONTROLLABLE_CLEAR] / [PrefsBridge.BREAKER_RESET]）本来就是
 *   "值变了一个新高"驱动的 —— 全量快照天然把这件事表达清楚，不需要额外协议。
 *
 * ★ 为什么引擎还要往 `Settings.System` 落一份：
 *   SystemUI 会被杀、会重启（本模块自己就在处理这件事）。落一份之后**引擎重启不需要
 *   等 App 醒过来**就能拿回配置；否则用户会看到「改完设置、系统界面重启一次、配置全回默认」。
 *
 * ⚠️ 与 [PrefsBridge] 的分工：那边只管**键名**与 `Settings.System` 读写；
 *   这边只管**怎么把一份快照从 App 运到引擎**。别把两边混起来。
 */
internal object ConfigChannel {

    private const val TAG = "HyperPlusChannel"

    /** 本应用包名。两侧读的都是它；引擎侧 `module/ModulePrefs.APP_PKG` 同源 */
    const val APP_PKG = "cn.dsr213.hyperplus"

    /**
     * App → 引擎：**配置快照推送**。
     *
     * ⚠️ 引擎侧用 [Context.registerReceiver] **动态**注册（它跑在 SystemUI 进程里，包名是
     *   `com.android.systemui`）⇒ 发送方**不能用 `setPackage`**，否则会被路由到"本应用的接收者"
     *   而**永远收不到**（动态接收者的归属包是注册它的那个进程的包）。
     */
    const val ACTION_PUSH = "cn.dsr213.hyperplus.action.CONFIG_PUSH"

    /**
     * 引擎 → App：**请把配置再推一次**。
     *
     * ★ 什么时候需要：引擎第一次起来、`Settings.System` 里那份镜像还不存在
     *   （新装机 / 刚升级上来）。这时引擎手上什么都没有，而 App 的私有 prefs 里
     *   躺着用户的真实配置 ⇒ 反向要一次。
     *
     * ⚠️ 这条走 App 的**清单接收者**（见 `AndroidManifest.xml`），所以可以用 `setPackage`
     *   定向，也就能穿过 Android 8+ 对**隐式广播**的限制。
     */
    const val ACTION_REQUEST = "cn.dsr213.hyperplus.action.CONFIG_REQUEST"

    /** 快照本体（[encode] 的产出）挂在哪个 extra 上 */
    const val EXTRA_SNAPSHOT = "snapshot"

    /**
     * ★★ [ACTION_PUSH] 的**发送方准入权限** —— `signature` 级，声明在 `AndroidManifest.xml`。
     *
     * ============================ 为什么必须有一条权限 ============================
     * 引擎侧的接收者是**动态注册**的，而它跑在 SystemUI 进程里、发送方是本应用 ⇒
     * 必须 `RECEIVER_EXPORTED`，也就是"**任何应用都能往这个 action 上发**"。
     * 没有准入的话，随便一个 App 发一条 [ACTION_PUSH] 就能改本模块的旋转配置。
     *
     * ============================ 为什么是"签名权限"而不是"核对 uid" ============================
     * 核对的思路是"在 `onReceive` 里比一下发送方 uid 是不是本应用"。问题是
     * 读发送方身份的 API 在 `BroadcastReceiver` 上**要 API 34（Android 14）才有**
     * （那套 `getSentFromUid` / `getSentFromPackage`），在 Android 13 及以下**只能退化成
     * "谁都能发"** —— 而本工程 minSdk 是 30，那就等于在三个大版本上把门敞着。
     *
     * 签名权限把这件事交给 **AMS 在投递之前**做：不持有该权限的发送方
     * **连 `onReceive` 都进不来**（`BroadcastQueue` 里按发送方 uid 判 `requiredPermission`，
     * 不通过就跳过这个接收者）。与系统版本无关，也不给应用层留"忘了写核对"的余地。
     *
     * ⚠️ 权限声明方就是本应用自己，而 `signature` 级的语义正是"与权限声明方**同签名**才授予"
     *   ⇒ 等价于**只有本应用能发**。（root / shell 由平台的 uid 特例放行，这正是
     *   `adb shell su -c "am broadcast ..."` 能用来验证的原因。）
     */
    const val PERM_PUSH = "cn.dsr213.hyperplus.permission.CONFIG_PUSH"

    // ================================================================ 编解码
    //
    // ★★ 为什么**不用** `org.json`（虽然 Android 自带、写起来更顺手）：
    //   单元测试跑在 **JVM** 上，那里 `org.json` 只是 `android.jar` 的空壳存根
    //   （配合 `isReturnDefaultValues = true` 会**返回默认值**而不是抛异常）
    //   ⇒ 编解码的用例会变成**假绿**，"测过了"却什么都没测到 —— 这正是本项目最忌讳的失败方式。
    //   要真测就得引第三方 `org.json:json`，那就多一条"能不能下载到"的不确定依赖。
    //
    // ⇒ 改成**长度前缀**的自描述格式：不需要转义、不依赖任何库、纯 Kotlin，
    //   连值里带 `|`、`\n`（白名单就是 `\n` 分隔的）都天然安全。
    //
    //   每条记录： `<类型><键长>:<键><值长>:<值>`
    //   例：`i12:semi_hint_ms4:3000` = Int 键 semi_hint_ms 值 3000
    //       `s17:rotate_mode_inner6:SYSTEM`
    //   ⚠️ 键长/值长都是**实际字符数**，别凭印象写（`semi_hint_ms` 是 **12** 不是 11 —— 
    //      2026-10-03 就在注释与用例里各写错过一次，被单测抓出来）。
    //
    //   ★ 长度是 **UTF-16 code unit 数**（`String.length`），与 `substring` 口径一致，
    //     所以中文字符不会算错。

    private const val T_STRING = 's'
    private const val T_INT = 'i'
    private const val T_LONG = 'l'
    private const val T_FLOAT = 'f'
    private const val T_BOOL = 'b'

    /**
     * 把一份 prefs 快照编成字符串。
     *
     * 类型处理：`String` / `Boolean` / `Int` / `Long` / `Float` 各带一个类型标记；
     * `Double` 归到 `Float`（本工程 prefs 里没有 Double 键，收进来只是不让它抛）；
     * 其余类型（如 `Set<String>`）退化成 `String` —— 本工程也没有这种键
     * （名单是用 `\n` 分隔的**单个字符串**，见 [AppWhitelist.encode]）；
     * `null` 直接跳过（"键存在但值为 null"在这套语义里没有意义）。
     */
    fun encode(map: Map<String, Any?>): String = buildString {
        for ((k, v) in map) {
            if (v == null) continue
            val t: Char
            val s: String
            when (v) {
                is String -> { t = T_STRING; s = v }
                is Boolean -> { t = T_BOOL; s = if (v) "1" else "0" }
                is Int -> { t = T_INT; s = v.toString() }
                is Long -> { t = T_LONG; s = v.toString() }
                is Float -> { t = T_FLOAT; s = v.toString() }
                is Double -> { t = T_FLOAT; s = v.toFloat().toString() }
                else -> { t = T_STRING; s = v.toString() }
            }
            append(t).append(k.length).append(':').append(k)
                .append(s.length).append(':').append(s)
        }
    }

    /**
     * [encode] 的逆。**解析不出来返回 `null`** —— 调用方必须区分"坏包"与"空包"：
     * 这条链路跨进程，坏包绝不能静默变成"空配置"，否则会用默认值覆盖引擎状态。
     *
     * 长度越界、类型标记不认识、数字格式不对，一律判为坏包（不"尽力恢复"）——
     * 恢复逻辑在这里只会制造"半个配置"这种更难查的状态。
     */
    fun decode(blob: String): Map<String, Any?>? {
        val out = LinkedHashMap<String, Any?>()
        var i = 0

        /** 读一个十进制长度，读到 `:` 结束（并把游标推过它）；读到不合法东西就返回 null */
        fun readLen(): Int? {
            val start = i
            while (i < blob.length && blob[i].isDigit()) i++
            if (i == start || i >= blob.length || blob[i] != ':') return null
            val n = blob.substring(start, i).toIntOrNull() ?: return null
            i++ // 推过 ':'
            return n
        }

        /**
         * 长度是否**能安全切成子串**。
         *
         * ⚠️⚠️ 这里**必须**写成 `len > blob.length - i`，⛔ 不能写 `i + len > blob.length`：
         *   后者在 `len` 接近 `Int.MAX_VALUE` 时**会整数溢出成负数**，于是判据放行，
         *   紧接着的 `substring` 抛 `StringIndexOutOfBoundsException`
         *   —— 而这段代码跑在 **SystemUI 进程里**，一次异常就是状态栏级别的事故。
         *   （2026-10-03 被 [ConfigChannelTest.outOfRangeLengthReturnsNullNotCrash] 抓出来的真 bug，
         *     测试里那条 `s2147483647:a1:b` 就是它的触发器。）
         *   ★ 减法这一侧不会溢出：恒有 `i <= blob.length` ⇒ `blob.length - i >= 0`。
         */
        fun fits(len: Int): Boolean = len >= 0 && len <= blob.length - i

        while (i < blob.length) {
            val t = blob[i]
            i++
            val kLen = readLen() ?: return null
            if (!fits(kLen)) return null
            val key = blob.substring(i, i + kLen)
            i += kLen
            val vLen = readLen() ?: return null
            if (!fits(vLen)) return null
            val raw = blob.substring(i, i + vLen)
            i += vLen
            val value: Any? = when (t) {
                T_STRING -> raw
                T_INT -> raw.toIntOrNull() ?: return null
                T_LONG -> raw.toLongOrNull() ?: return null
                T_FLOAT -> raw.toFloatOrNull() ?: return null
                T_BOOL -> when (raw) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                else -> return null
            }
            out[key] = value
        }
        return out
    }

    /** 取一份 prefs 的全量快照。读不到就返回空表（调用方按"空"处理，不会崩） */
    @Suppress("UNCHECKED_CAST")
    fun snapshotOf(p: SharedPreferences): Map<String, Any?> =
        runCatching { p.all as Map<String, Any?> }.getOrDefault(emptyMap())

    // ================================================================ 发送端

    /**
     * App → 引擎：推一份快照。
     *
     * @return 是否把广播**发出去了**。⚠️ **不代表引擎收到了** —— 广播是单向的，
     *   所以引擎侧必须有一条不依赖它的兜底：`Settings.System` 镜像 + [sendRequest]。
     */
    fun sendPush(ctx: Context, snapshot: Map<String, Any?>): Boolean = runCatching {
        ctx.sendBroadcast(Intent(ACTION_PUSH).putExtra(EXTRA_SNAPSHOT, encode(snapshot)))
        true
    }.onFailure { Log.w(TAG, "推送配置失败", it) }.getOrDefault(false)

    /**
     * 引擎 → App：请再推一次。
     *
     * ★ 用 `setPackage` 定向（走 App 的清单接收者）—— 理由见 [ACTION_REQUEST]。
     */
    fun sendRequest(ctx: Context): Boolean = runCatching {
        ctx.sendBroadcast(Intent(ACTION_REQUEST).setPackage(APP_PKG))
        true
    }.onFailure { Log.w(TAG, "请求配置失败", it) }.getOrDefault(false)
}

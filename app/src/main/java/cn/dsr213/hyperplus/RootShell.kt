package cn.dsr213.hyperplus

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 借 root 直接读写系统设置（2026-10-03）。
 *
 * ============================ 它为什么存在 ============================
 * 用户原话：「**能装上模块的手机一定有 Root，可以通过获取 root 来修改**」。
 * 「默认方向」入口要改的是 `Settings.System.user_rotation_inner` —— WMS 的私有键。
 * 写它要求调用方是 SYSTEM / SHELL / ROOT uid：
 *
 *   · **App 进程没有这个能力**。AndroidManifest 里刻意**没有**申请 WRITE_SETTINGS
 *     （原因见那份 manifest 里的说明），一条 `putInt` 直接被 SettingsProvider 拒掉；
 *   · **引擎（SystemUI）虽然有权限，但那条路要走配置通道投递** —— App 写 prefs →
 *     引擎 `collect`。2026-10-03 真机上用户点了「默认方向」，请求**落了盘、
 *     却没有落进槽位**（详见当日文档）。引擎那条腿当时分不清是没投递到、
 *     还是投递到了但被别的写入盖掉。
 * ⇒ 改走这条路：**由 App 借 root 直接执行 `settings put`**。点一下就是一下，
 *   中间没有任何一跳可以掉链子，也**不依赖引擎在不在跑**。
 *
 * ============================ 与"引擎代写"的取舍 ============================
 * | | 引擎代写（旧） | root 直写（现在） |
 * |---|---|---|
 * | 依赖 | 配置通道通、引擎在跑、冷启动闸放行 | 只要 root 能用 |
 * | 失败面 | 三跳里的任意一跳都可能静默失败 | 一条命令，成败当场可见 |
 * | 代价 | 不额外要权限 | 首次弹一次 root 授权 |
 * 这是用户拍板选的：**宁可多要一次授权，也不要"点了没反应"**。
 *
 * ============================ 纪律 ============================
 * · 写完**必须读回校验** —— 返回 true 仅当"读回来的值 == 目标值"，
 *   界面显示也来自真实读回，绝不假装成功。
 * · 只在**为用户点击服务**时调用；不要在定时巡检里用它（每次都要起一个 su 进程）。
 * · 日志用「」不用直引号（项目纪律）。
 */
internal object RootShell {

    private const val TAG = "HyperPlusRoot"

    /**
     * 单条命令的超时上限。
     * ⚠️ 必须有：su 授权对话框弹着的时候，命令会一直挂在管道上不返回 ——
     *   没有上限就等于把界面协程永久吊死。
     */
    private const val TIMEOUT_MS = 15_000L

    /**
     * 写一个 `Settings.System` 的整型键，并**读回校验**。
     *
     * ★ 写与读回**合成一条命令**（`settings put … ; settings get …`）：
     *   只起一个 su、省一次进程启动；顺带保证"读回来的就是刚写完的状态"
     *   （中间不给别的写入者留插队的窗口）。
     *
     * @return 仅当命令成功**且**读回值等于目标值时返回 true；其余一律 false。
     */
    suspend fun putSystemInt(key: String, value: Int): Boolean = withContext(Dispatchers.IO) {
        val script = "settings put system $key $value; settings get system $key"
        val text = runShell(script) ?: return@withContext false
        // 输出里可能混着 stderr（见 [exec] 的 redirectErrorStream）⇒
        // 只认**最后一个非空行**，解析不出数字就算失败。
        text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.toIntOrNull() == value
    }

    /**
     * 读一个 `Settings.System` 的整型键（借 root）。
     *
     * ⚠️ 普通读设置**本来不需要 root**（见 [AppPrefs.readSlot]）—— 这个方法是给
     *   "需要跟写入同一视角核对"的场合用的备用手段，别拿它做每秒轮询。
     *
     * @return 读不到 / 不是整数 ⇒ null
     */
    suspend fun getSystemInt(key: String): Int? = withContext(Dispatchers.IO) {
        runShell("settings get system $key")?.trim()?.toIntOrNull()
    }

    /**
     * ★★ **写一个 `persist.*` 属性，并读回校验**（2026-10-06 新增）。
     *
     * ============================ 它为什么必须存在 ============================
     * 「提高分屏上限」那个开关的值**必须让 SystemUI 在进程起来的第一个毫秒读到** ——
     * 实测截止线是 Δ404 ms，而走 `Settings.System` 要 Δ4.6 s 才拿得到
     * （日志表在 [PrefsBridge.PROP_MULTISPLIT] 的 KDoc 里）。
     * 属性活在**进程内 mmap 的属性区**，不经过 Binder ⇒ 正好满足这个时限。
     *
     * ⚠️ 它与 [putSystemInt] 是**两条完全不同的通道**，⛔ 谁也替代不了谁：
     *   · `Settings.System`：ContentProvider，普通应用**读**免权限、**写**要特权 uid；
     *   · 属性：property_service，**只有 root / init 能设** ⇒ 本方法必然起一个 su。
     *   ⇒ 因此它同样受类注释那条纪律约束：**只为用户点击服务**，⛔ 别放进轮询。
     *
     * ★ 写与读回**合成一条命令**，理由与 [putSystemInt] 逐字相同：只起一个 su、
     *   且保证「读回来的就是刚写完的状态」（中间不给别的写入者留插队的窗口）。
     * ⚠️ 属性要走 `setprop` / `getprop`，⛔ 别照抄 [putSystemInt] 的 `settings put`。
     *
     * ⚠️ 失败形态也照抄 [putSystemInt]：`runShell` 把 stderr 并进了 stdout，
     *   所以命令失败时输出里可能是 `setprop: ...` 之类的报错 ⇒ 只认**最后一个非空行**，
     *   对不上目标值就算失败（被 SELinux 拦下时正是这个形态）。
     *
     * @param key   完整属性名（本工程只用常量，见 [PrefsBridge.PROP_MULTISPLIT]）
     * @param value 属性值。本工程只写 `0` / `1` —— 都不含空格，
     *   所以这里**不加引号、不做转义**（这也是它不能当通用接口用的原因之一）。
     * @return 仅当命令成功**且**读回值恰好等于目标值时返回 true。
     */
    suspend fun putProp(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val script = "setprop $key $value; getprop $key"
        val text = runShell(script) ?: return@withContext false
        text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() } == value
    }

    /**
     * ★★ **跑一条任意 root 命令，返回原样输出**（2026-10-04 新增）。
     *
     * ============================ 它为什么必须存在 ============================
     * 有些动作是**文件级**的（`getprop` 读属性、`killall` 重启某个进程、`cp`/`tar` 搬运文件），
     * 这些都不是 `Settings.System` 的读写 —— [putSystemInt] / [getSystemInt] 那两个
     * 是"设置专用"的窄接口，做不了这些事。
     *
     * ⚠️ **别拿它当通用提权执行器用**：本工程对 root 的定位是
     *   「**为用户点击服务的一次性动作**」（见类注释的纪律）—— 每次调用都会起一个 `su` 进程。
     *   ⇒ ⛔ 不要在定时巡检、循环、每秒轮询里调它。
     *
     * @param script 要执行的 shell 片段（可以是 `a && b` 这种复合命令）
     * @return null = **这台机器上根本没能用的 su**（"能力缺失"，与"命令失败"不同）；
     *         非 null = 命令的输出（stdout 与 stderr 已合并，顺序即发生顺序）
     */
    suspend fun runRaw(script: String): String? = withContext(Dispatchers.IO) {
        runShell(script)
    }

    private data class ShellResult(val code: Int, val out: String)

    // ================================================================ root 可用性探测（2026-10-05）

    /**
     * root 探测的**三态**结果（见 [probe]）。
     *
     * ⚠️ 刻意不用 `Boolean`：对用户来说这三条路的**下一步完全不同** ——
     *   「去授权」/「等一会儿再点一次」/「这台机器根本没 root，死心吧」。
     *   压成一个 false 只会让用户反复点、反复失败。
     */
    enum class RootStatus {
        /** su 可用，而且真的给了 root（`id` 报 `uid=0`） */
        GRANTED,

        /** 有 su，但没给到 root —— 授权框被拒、或者框还挂着没人点（[TIMEOUT_MS] 超时） */
        DENIED,

        /** 这台机器上**根本没有可用的 su** —— 用户做什么都没用，只能如实告诉他 */
        UNAVAILABLE,
    }

    /**
     * 探一次 root 到底能不能用（权限管理页用）。
     *
     * 判据只有一条：跑最小命令 `id`，输出里**必须**有 `uid=0`。
     *   - `runShell` 返回 null ⇒ [RootStatus.UNAVAILABLE]（连 su 二进制都找不到）；
     *   - 有输出但不含 `uid=0` ⇒ [RootStatus.DENIED]。
     *
     * ============================ 三条纪律 ============================
     * ① **它有副作用，而且是好的那种**：root 从没授权过时，这一次调用会**弹出授权框**。
     *    用户要的恰恰是这个（"没获取就给我一个入口去获取"）—— 所以页面里把它说明白，
     *    ⛔ 别偷偷调。
     * ② ⛔ **不要放进定时巡检 / 每次重组**：每调一次就起一个 `su` 进程（见类注释的纪律）。
     *    权限页里的调用点是"进页面一次 + 用户点一次「重新检测」"。
     * ③ 别把这个方法当成"root 一定好用"的证明 —— 它只证明**这一刻 `id` 能跑通**。
     *    真正的写入仍然要靠 [putSystemInt] 自己的读回校验兜底。
     */
    suspend fun probe(): RootStatus = withContext(Dispatchers.IO) {
        val out = runShell("id") ?: return@withContext RootStatus.UNAVAILABLE
        if (out.contains("uid=0")) RootStatus.GRANTED else RootStatus.DENIED
    }

    /**
     * 依次尝试几个 `su` 路径，第一个**跑得动**的为准。
     *
     * ⚠️ 为什么要有备选：App 进程的 `PATH` 里不一定有 `su`（各家 root 方案安装位置不同），
     *   而 `/system/bin/su` 是 KernelSU / Magisk 都会建的软链。
     * ⚠️ 区分「跑不动」与「跑失败」：
     *   · 跑不动（su 不存在 ⇒ 抛异常）⇒ 记下来继续试下一个；
     *   · 跑得动但命令报错 ⇒ **立刻返回原文**，别换路径重试（换的是 su，不是命令）。
     *
     * @return null = 这台机器上根本没能用的 su（调用方据此判定"能力缺失"）
     */
    private fun runShell(script: String): String? {
        for (su in SU_CANDIDATES) {
            val r = runCatching { exec(suArgs(su, script)) }.getOrNull() ?: continue
            Log.i(TAG, "root 执行（$su）：exit=${r.code}｜${r.out.take(200)}")
            // ★★ 兜底：个别 su 不认 `-M`（会吐 usage 并 exit != 0）⇒ 退到不带 `-M` 再试一次。
            //   ⚠️ 判据是**输出里有没有 usage 字样**，而不是 exit code —— 因为
            //     "命令真的失败"和"参数被拒"都会非 0，不能靠 exit code 区分。
            if (r.code != 0 && r.out.contains("Usage:", ignoreCase = true)) {
                Log.w(TAG, "$su 不认 -M，退回不带 -M 重试")
                val r2 = runCatching { exec(arrayOf(su, "-c", script)) }.getOrNull() ?: continue
                Log.i(TAG, "root 执行（$su，无 -M）：exit=${r2.code}｜${r2.out.take(200)}")
                return r2.out
            }
            return r.out
        }
        Log.w(TAG, "root 不可用（试过 ${SU_CANDIDATES.joinToString()}）")
        return null
    }

    /**
     * ★★★ **`su` 的完整参数 —— `-M` 这条是必须的**（2026-10-05 实证）。
     *
     * ============================ 没有 `-M` 会怎样 ============================
     * 用户报障的「备份拿不到桌面数据」的真因**就是这条**（不是权限、不是 sqlite3）：
     *
     * ⚠️ 那次报障来自**已下线**的「桌面增强」（2026-10-05），但**这条机制对任何 root
     *   文件操作都成立** ⇒ ⛔ 别因为功能没了就把 `-M` 删掉。
     *
     * ```
     * # App 的 su（不带 -M）：
     * /system/bin/sh: cd: /data/user_de/0/com.miui.home/databases: No such file or directory
     *
     * # adb shell 里同样的命令（adb 的 su 恰好在 global ns）：
     * CD-OK
     * ```
     *
     * **根因**：App 进程活在**它自己的 mount namespace** 里
     * （实测：App `mnt:[4026537423]` vs adb shell `mnt:[4026535570]`），
     * 而 `/data/user_de/0/com.miui.home/` 这个挂载点**在那个 ns 里不可见**
     * ⇒ 报错伪装成「No such file or directory」（**不是 permission denied**，
     * 这正是它难查的原因）。
     *
     * ★ 用 `nsenter --mount=/proc/<app pid>/ns/mnt` 可以 1:1 复现该报错 ⇒ 根因确认。
     *
     * **修法**：KernelSU / Magisk 的 `su` 都支持 `-M` / `--mount-master`
     * ⇒ **强制在 global mount namespace 里跑**，那里能看到全部挂载点。
     *
     * ⚠️ **只对 `-c` 那种单命令用**：`-M` 是所有 su 实现里都有的通用选项
     *   （KernelSU 的 `--help` 里明确列着），但个别老版本可能不认 ⇒ [runShell] 有兜底重试。
     */
    private fun suArgs(su: String, script: String): Array<String> = arrayOf(su, "-M", "-c", script)

    /**
     * 跑一条命令并等它结束。
     *
     * ★ 顺序刻意是「先 `waitFor(超时)`、再读输出」：本场景输出只有一两行，远小于
     *   管道缓冲（64KB），不会因为"没读输出"把子进程堵死；换来的是一个**可靠的
     *   时间上限** —— su 弹窗挂住时不会把协程吊死。
     */
    private fun exec(cmd: Array<String>): ShellResult {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        // ⚠️ 出任何事都要保证子进程不留下：走 try/finally 而不是只靠超时分支。
        return try {
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return ShellResult(-1, "")
            }
            val text = p.inputStream.bufferedReader().use { it.readText() }
            ShellResult(p.exitValue(), text)
        } catch (t: Throwable) {
            p.destroyForcibly()
            throw t
        }
    }

    /**
     * `su` 的候选路径。
     * ⚠️ 就这三个（一个裸名走 PATH + 两个常见绝对路径）；不在这里穷举各家 root 方案，
     *   真有第四种再加 —— 三个都跑不动时 [runShell] 会明确报"root 不可用"。
     */
    private val SU_CANDIDATES = arrayOf("su", "/system/bin/su", "/system/xbin/su")
}

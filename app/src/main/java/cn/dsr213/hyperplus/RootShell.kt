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

    private data class ShellResult(val code: Int, val out: String)

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
            val r = runCatching { exec(arrayOf(su, "-c", script)) }.getOrNull() ?: continue
            Log.i(TAG, "root 执行（$su）：exit=${r.code}｜${r.out.take(200)}")
            return r.out
        }
        Log.w(TAG, "root 不可用（试过 ${SU_CANDIDATES.joinToString()}）")
        return null
    }

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

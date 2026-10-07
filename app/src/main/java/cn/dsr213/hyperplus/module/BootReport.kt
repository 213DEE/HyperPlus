package cn.dsr213.hyperplus.module

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块「启动摘要」落盘 —— ★★ **无条件写**，不依赖任何开关（与 `hyperplus_selfcheck.txt` 不同）。
 *
 * ============================ 为什么非做不可 ============================
 * `main` logcat 缓冲在本机**只保留约 8–10 分钟**（2026-10-07 实测：SystemUI 于 02:10 重启，
 * 打的「模块已加载 / 注入成功 / 多分屏上限提升开始安装」到 02:18 导出诊断日志时**已经不在**；
 * `logcat -b all -s HyperPlusModule` 返回 **0 行**，缓冲区里现存 hyperplus 记录最早只到 02:13）。
 *
 * 而用户报「模块用不了」时，最需要的恰恰是「**模块到底有没有加载成功**」这一条 ——
 * 它却最先消失。README 里教的 `adb logcat` 对用户同样无效（普通应用只见本进程）。
 * ⇒ 所以在关键节点**一边打日志、一边追加写一个小文件**，交给 App 的「导出诊断日志」读。
 *
 * ============================ 纪律 ============================
 * 本类运行在 **SystemUI 进程**里。⇒ 每一处 I/O 都必须 `runCatching` 包住，
 * 任何异常都⛔ 不许冒泡（一次未捕获 = 状态栏没了）。
 *
 * ⚠️ 时序：早期节点（`onModuleLoaded` / `onPackageReady` / `SplitStageLimit.install`）
 *   **拿不到宿主 Context** ⇒ 只能先记内存（[pending]）；等 `doBoot` 拿到 `hostCtx`
 *   调 [reset] 时再一次性冲盘。闸门那几条可能落在 [reset] 前后任一时刻，两种都覆盖。
 */
internal object BootReport {

    private const val TAG = "HyperPlusBoot"

    /** 落盘位置：SystemUI 的 DE 存储（与自检报告同目录，App 采集时借 root 读） */
    private const val FILE_NAME = "hyperplus_boot.txt"

    /** 文件上限：超了就整份重写，只留一行说明 —— 防无限增长 */
    private const val MAX_BYTES = 24 * 1024L

    /** 绑定（[reset]）之前先攒在这儿 */
    private val pending = ArrayList<String>()

    @Volatile private var file: File? = null

    /** 上一条正文（不含时间戳）—— 用来吃掉连续重复，闸门可能被调两次 */
    @Volatile private var lastLine = ""

    private val stampShort = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val stampFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 绑定宿主 Context 并把文件**重写**（不是追加）—— 每次 SystemUI 进程启动只该走一次，
     * 这样文件里永远只有**本次启动**的记录，不会越积越多。
     */
    fun reset(hostCtx: Context) {
        synchronized(this) {
            runCatching {
                val f = File(hostCtx.filesDir, FILE_NAME)
                file = f
                f.writeText(
                    buildString {
                        append("HyperPlus 模块启动摘要（本文件由模块在系统界面进程内无条件写入）\n")
                        append("进程启动 = ${stampFull.format(Date())}\n")
                        append("为什么有这份文件：logcat 缓冲只保留约 10 分钟，\n")
                        append("模块刚起来那几条关键日志届时会被挤掉，所以在这里留一份。\n")
                        append("-".repeat(56) + "\n")
                    },
                )
                lastLine = ""
                // 补上早于 Context 就发生的事（注入成功 / 钩子安装 / 闸门）
                val buffered = pending.toList()
                pending.clear()
                buffered.forEach { appendLineLocked(it) }
                Log.i(TAG, "启动摘要已写入 $f")
            }.onFailure { Log.w(TAG, "启动摘要 reset 失败（已吞掉）", it) }
        }
    }

    /**
     * 记一条。**绑定了就直接追加；还没绑定就先攒着**（见类注释的时序说明）。
     *
     * @param text 正文，⛔ 别自带换行 —— 本方法会加时间戳前缀和换行
     */
    fun note(text: String) {
        synchronized(this) {
            if (file == null) {
                pending.add(text)
                return
            }
            appendLineLocked(text)
        }
    }

    private fun appendLineLocked(text: String) {
        val f = file ?: return
        // 连续重复不写：闸门路径可能被调两次，内容完全一样就没必要占两行
        if (text == lastLine) return
        lastLine = text
        runCatching {
            if (f.length() > MAX_BYTES) {
                f.writeText("（超出 ${MAX_BYTES / 1024} KB 上限，已截断 —— 以下是最新的记录）\n")
            }
            f.appendText("${stampShort.format(Date())} $text\n")
        }.onFailure { Log.w(TAG, "写启动摘要失败（已吞掉）", it) }
    }
}

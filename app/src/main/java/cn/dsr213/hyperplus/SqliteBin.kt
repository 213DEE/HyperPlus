package cn.dsr213.hyperplus

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ★★★ **本应用自带的 `sqlite3` 命令行**（2026-10-05 新增）。
 *
 * ============================ 它为什么必须存在 ============================
 * 本机**没有任何可用的 `sqlite3` 命令行**（下面有逐条实测），而有些场合需要直接读
 * 系统应用的数据库 —— 例如数桌面数据库里有多少个图标（`SELECT count(*) FROM favorites`）。
 *
 * ⚠️ **2026-10-05：原使用者（「桌面增强」的备份 / 熔断）已整个下线** ⇒ 当前**无调用方**。
 *   用户点名保留它作为**备用能力**（自带二进制、开箱可用），⛔ 别当死代码删掉。
 *
 * ⚠️ 但 **这台设备上根本没有 `sqlite3`**（2026-10-05 逐条实测，✅ 实证）：
 *   · `/system/bin`、`/system/xbin`、`/vendor/bin`、`/product/bin`、`/system_ext/bin`、
 *     `/apex` 下各 bin —— **全无**；
 *   · KernelSU 的 busybox（`/data/adb/ksu/bin/busybox`）**没有编进 `sqlite3` applet**
 *     （执行报 `sqlite3: applet not found`，`busybox --list | grep sql` 空）；
 *   · 系统里**只有** `/system/lib64/libsqlite.so`（库在，但**没有命令行**）。
 * ⇒ 借现成的路走不通，**只能自己带一个**（用户 2026-10-05 拍板：「自带 sqlite3 二进制」）。
 *
 * ============================ 二进制怎么来的 ============================
 * **官方源码交叉编译**（不是从网上随便下的预编译 —— 那种来路不明的二进制
 * 塞进一个要求 root 的 App 里，是不能接受的风险）：
 * ```
 * 源码：sqlite-amalgamation-3530400.zip（sqlite.org 官方）
 * 编译器：NDK r27c 的 aarch64-linux-android21-clang（-O2）
 * 宏：SQLITE_THREADSAFE=1  SQLITE_OMIT_LOAD_EXTENSION（⛔ 关掉扩展加载，见下）
 * 链接：**动态**（依赖 libm/libdl/libc，Android 全都有）
 * ```
 * ⚠️ **为什么不 `-static`**（踩过，2026-10-05）：静态链接出的可执行文件
 * **TLS 段对齐是 8**，而 **Bionic（Android libc）要求 ≥ 64**
 * ⇒ 一跑就 `error: executable's TLS segment is underaligned ... Aborted`。
 * 改动态链接后正常（真机实测 `--version` → `3.53.4`，并成功读出桌面 DB 里的 194 个图标）。
 *
 * ⚠️ **`SQLITE_OMIT_LOAD_EXTENSION` 是刻意的**：这个二进制要借 root 跑，
 *   而 SQLite 的扩展加载 = 「加载任意 `.so` 并执行其中代码」。
 *   关掉它，这个二进制就**只能读数据、不能加载代码** ⇒ 被滥用时的爆炸半径小一个数量级。
 *   本工程只需要 `SELECT count(*)`，用不到扩展。
 *
 * ============================ 放在哪、为什么 ============================
 * 文件打成 `jniLibs/arm64-v8a/libsqlite3bin.so`（**故意用 `.so` 后缀**）。
 *
 * ★ 为什么走 jniLibs 而不是 assets（关键，别改回去）：
 *   · `app/build.gradle.kts` 里 `packaging.jniLibs.useLegacyPackaging = true` ⇒
 *     Android 装机时会把 jniLibs 里的文件**真正解包到 `nativeLibraryDir`**，
 *     而且**权限天生就是 `0755`**（实测：`-rwxr-xr-x`）——
 *     **不需要任何 chmod**，也不需要"第一次用时解压"那套状态机；
 *   · 走 assets 的话：assets **不保留可执行位**，必须自己 `cat` 出来 + `chmod 755`，
 *     还要处理"解到哪、SELinux 让不让执行"两个额外的坑。
 *
 * ⚠️ **后缀叫 `.so` 但它不是共享库、`System.load` 不了它**（它是 `EXEC` 类型的可执行文件，
 *   见 [Type] 那段）—— 这里**只把它当文件读**，从不用 ClassLoader 加载。
 *   叫 `.so` 纯粹是为了让打包流程按 native 库处理（换来"自动解包 + 可执行位"）。
 *
 * ============================ 路径纪律 ============================
 * [path] 返回的是**绝对路径**，用于拼进 `su -c` 的命令里 ——
 * ⚠️ 必须绝对：`su` 起的是一个**全新的 shell**，它的 `PATH` 里没有我们的目录。
 */
internal object SqliteBin {

    private const val TAG = "HyperPlusSqliteBin"

    /**
     * jniLibs 里的文件名。⚠️ 改了它必须同步改 `jniLibs/arm64-v8a/` 下的实际文件名
     * （或者反过来 —— 两者是**硬绑定**的，没有任何构建脚本会替你检查）。
     */
    private const val LIB_NAME = "libsqlite3bin.so"

    /**
     * 拿到这个二进制的**绝对路径**，并确认它真的能执行。
     *
     * ★ 校验方式是**真的跑一下 `--version`**（不是 `File.canExecute()`）：
     *   权限位对 ≠ 跑得起来（架构不对、库缺失、SELinux 拒绝都在权限位之外）。
     *   宁可这里多花一次 `su`，也不要让"数图标"那条路拿着一个跑不动的路径去失败。
     *
     * ⚠️ 这个方法**会起一个 su 进程** ⇒ 只在"用户点击触发的动作"里调，
     *   ⛔ 别放进轮询（同 [RootShell.runRaw] 的纪律）。
     *
     * @return 可用的绝对路径；不可用 ⇒ null（调用方据此走"拿不到图标数"的降级分支）
     */
    suspend fun path(ctx: Context): String? {
        val p = File(ctx.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (!p.exists()) {
            Log.w(TAG, "自带 sqlite3 不在 nativeLibraryDir（${p.absolutePath}）")
            return null
        }
        // ★ 权限兜底：正常装机后是 0755，但**升级安装 / 某些 ROM** 上不保证
        //   （nativeLibraryDir 属 system，App 自己改不了 ⇒ 借 root 改，失败也不算错）。
        val probe = RootShell.runRaw(
            "chmod 755 ${p.absolutePath} 2>/dev/null; ${p.absolutePath} --version 2>&1",
        )
        if (probe.isNullOrBlank()) {
            Log.w(TAG, "自带 sqlite3 跑不起来（root 不可用？）")
            return null
        }
        // 成功输出形如 `3.53.4 2026-07-24 ... (64-bit)`；失败会带 error/Aborted 之类字样。
        val first = probe.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val looksOk = first.firstOrNull()?.isDigit() == true
        Log.i(TAG, "自带 sqlite3 探测：$first（可用=$looksOk）")
        return if (looksOk) p.absolutePath else null
    }

    /**
     * 跑一条查询，返回 stdout（失败 ⇒ null）。
     *
     * ⚠️ SQL 里的**单引号**要用 shell 的引号包起来 —— 这里固定用双引号包 SQL，
     *   因为我们的 SQL 全是 `SELECT count(*) ...` 这类**不含 `$` 和反引号**的常量，
     *   ⛔ 别往这里塞带用户输入的 SQL（那会变成一条命令注入通道）。
     *
     * @param db  数据库绝对路径
     * @param sql 常量 SQL（调用方保证：不含引号、不含 `$`、不含反引号）
     */
    suspend fun query(ctx: Context, db: String, sql: String): String? {
        val bin = path(ctx) ?: return null
        return RootShell.runRaw("$bin \"$db\" \"$sql\" 2>&1")
    }
}

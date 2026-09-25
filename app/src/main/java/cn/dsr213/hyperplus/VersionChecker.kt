package cn.dsr213.hyperplus

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检测：查 GitHub Releases，判断有没有比本机更新的版本。
 *
 * 设计取舍：
 *  - 用 `/releases/latest` 而不是 `/releases` 列表 —— 后者会把 draft / prerelease
 *    一起返回，还得自己过滤。`/latest` 由 GitHub 保证是「最新正式发布」。
 *  - **不带 token**：仓库是公开的，匿名调用限额 60 次/小时，本 App 一天最多查几次，
 *    完全够用；把 PAT 塞进 APK 反而是安全事故。
 *  - 网络失败一律返回 [Result.Failed]，UI 静默处理（**绝不能因为查不到版本就弹窗打扰用户**）。
 *
 * 版本号比较：
 *      v0.3.0-Alpha  →  主数字 [0,3,0] + 阶段权重 ALPHA(0)
 *      v0.3.0-Beta   →  [0,3,0] + BETA(1)
 *      v0.3.0        →  [0,3,0] + RELEASE(3)
 *    比较时先比主数字，相等再比阶段权重。这样 0.3.0-Alpha < 0.3.0-Beta < 0.3.0。
 */
object VersionChecker {

    /** 本项目的 GitHub 仓库（owner/repo） */
    const val REPO = "213DEE/HyperPlus"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TAG = "FaceRotate"

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val publishedAt: String,
    )

    sealed interface Result {
        /** 远端更新，需要提醒 */
        data class Newer(val remote: Release, val current: String) : Result

        /** 已是最新 */
        data class UpToDate(val current: String) : Result

        /** 查询失败（网络/限流/无 Release）—— UI 应静默 */
        data class Failed(val reason: String) : Result
    }

    /** 在**后台线程**调用；内部是阻塞 IO */
    fun check(currentVersion: String): Result {
        return try {
            val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                // GitHub API 强制要求 User-Agent，缺了直接 403
                setRequestProperty("User-Agent", "HyperPlus-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                return Result.Failed("仓库还没有已发布的 Release")
            }
            if (code != 200) {
                conn.disconnect()
                return Result.Failed("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val o = JSONObject(text)
            val remote = Release(
                tag = o.optString("tag_name", ""),
                name = o.optString("name", "").ifBlank { o.optString("tag_name", "") },
                body = o.optString("body", ""),
                htmlUrl = o.optString("html_url", ""),
                publishedAt = o.optString("published_at", ""),
            )
            if (remote.tag.isBlank()) return Result.Failed("返回里没有 tag_name")

            if (compare(parse(remote.tag), parse(currentVersion)) > 0) {
                Result.Newer(remote, currentVersion)
            } else {
                Result.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "version check failed: ${e.message}")
            Result.Failed(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    // ---------------------------------------------------------------- 版本号

    /** 阶段权重：越大越"正式" */
    private const val STAGE_ALPHA = 0
    private const val STAGE_BETA = 1
    private const val STAGE_RC = 2
    private const val STAGE_RELEASE = 3

    data class Ver(val nums: List<Int>, val stage: Int) {
        /** 给人看的归一化字符串 */
        override fun toString() = nums.joinToString(".") + when (stage) {
            STAGE_ALPHA -> "-Alpha"
            STAGE_BETA -> "-Beta"
            STAGE_RC -> "-RC"
            else -> ""
        }
    }

    /**
     * 解析形如 `v0.3.0-Alpha` / `0.3.0` / `0.3` / `v1.2.3-beta.2` 的版本号。
     * 解析不出数字时按 [Ver] 全 0 处理（于是任何远端版本都比它新 —— 宁多提醒不漏提醒）。
     */
    fun parse(raw: String): Ver {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        val main = s.substringBefore('-').substringBefore('+')
        val nums = main.split('.').mapNotNull { it.trim().toIntOrNull() }
        val lower = s.lowercase()
        val stage = when {
            lower.contains("alpha") -> STAGE_ALPHA
            lower.contains("beta") -> STAGE_BETA
            lower.contains("rc") -> STAGE_RC
            else -> STAGE_RELEASE
        }
        return Ver(nums.ifEmpty { listOf(0) }, stage)
    }

    /** 逐段比较；a > b 返回正数 */
    fun compare(a: Ver, b: Ver): Int {
        val n = maxOf(a.nums.size, b.nums.size)
        for (i in 0 until n) {
            val x = a.nums.getOrElse(i) { 0 }
            val y = b.nums.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return a.stage - b.stage
    }

    /** GitHub Release 页地址（兜底用；正常直接用 API 返回的 html_url） */
    fun releasePageUrl(tag: String) =
        "https://github.com/$REPO/releases" + if (tag.isBlank()) "" else "/tag/$tag"
}

package cn.dsr213.hyperplus

/**
 * 「导出诊断日志」的**排版核** —— 纯函数，**零 android 依赖**（只用 kotlin stdlib）。
 *
 * ============================ 它为什么被单独拆出来 ============================
 * 2026-10-06 用户：「**还是有不少用户反馈无法使用模块**，再检查一遍，然后添加一个日志功能，
 * 让用户可以自行导出日志」。
 *
 * 排查之后确认的硬事实（逐条读过源码）：
 *   · 模块的失败原因**全部**只落在 logcat —— `SplitStageLimit` 的「❶ 类未找到：…⇒ 上限无法提升」
 *     「❷ 类未找到：…⇒ 7/8 格仍会崩」、`HyperPlusModule` 的「拿不到宿主 Application，模块无法工作」
 *     「EngineHost 启动失败（已吞掉）」，清一色是 `Log.w` / `Log.e`；
 *   · 而 README 现在教用户跑 `adb logcat …` —— **普通用户做不到这件事**。
 * ⇒ 于是「用不了」的反馈永远只能靠猜。这份报告就是要把
 *   「环境 + 模块状态 + 配置 + 日志」一次性打包，让用户点一下就能发出来。
 *
 * ★ 为什么**排版**也要单独一层：报告里最要紧的不是「有哪几行」，而是**缺失值怎么显示**。
 *   本项目反复踩过同一个坑 —— 把「没读到」写成 `false` / 空串，用户就以为「确定没拿到」，
 *   于是跑去系统设置里找一个根本不在那儿的开关（2026-10-05 外部用户报障的真因，
 *   完整复盘见 `ui/PermissionsPage.kt` 的类注释）。
 *   ⇒ 三态必须一路保持到**文件里**：[tri] 的 `null` 与 `false` 写法**不同**。
 *
 * ⚠️ 本文件**不许**出现任何 `android.*` / `libxposed` 的东西：
 *   `libxposed` 是 `compileOnly` ⇒ 引用它的类不在单测类路径上；一旦本文件碰了 android，
 *   [DiagnosticsReportTest] 就跑不起来。判定核纯函数化是本项目惯例
 *   （同 `FoldJudge` / `SplitThresholds` / `SplitStageGate` / `AdaptiveFallback`）。
 */
internal object DiagnosticsReport {

    /**
     * 「没读到」的统一写法。
     *
     * ⚠️ 刻意**不用空串**：空串在文本报告里看起来就是「这一行是空的」，而
     *   「引擎没报这一项」本身才是排查时要看的信号 —— 两者必须长得不一样。
     */
    const val NOT_READ = "(未读到)"

    /**
     * 「**确定为空**」的统一写法 —— 与 [NOT_READ] 必须长得不一样。
     *
     * ★ 2026-10-07 用户拍板：`null`（没读到）与空串（确实为空）是**两件事**。
     *   模块侧**无条件**回传所有键（见 `module/EngineHost` 的序列化），所以「键在、值为空」
     *   是模块明确报了空 —— 典型如 `errs=`（= 没有失败记录）、`votes=`（= 本轮没有票）。
     *   若把这也写成 [NOT_READ]，读者会以为「没拿到」，从而错过「一切正常」这个结论。
     */
    const val EMPTY = "（空）"

    /** 一段 = 标题 + 若干行（每行已是最终文本） */
    data class Section(val title: String, val lines: List<String>)

    /**
     * 段标题。格式固定（单测钉死）——
     * 这份文件既要能被人**人肉扫**，也要能被 `grep` 挑段。
     */
    fun heading(title: String): String = "===== $title ====="

    /**
     * 一行读数：`key = value` —— **三态**（2026-10-07 由两态扩为三态）：
     *   · `null` ⇒ [NOT_READ]（这一项**没读到**）
     *   · `""`   ⇒ [EMPTY]（读到了，但值为空 —— 模块**明确报了空**）
     *   · 其余   ⇒ 原样
     *
     * ⚠️ `"0"` / `"false"` 是**有效值**，⛔ 别顺手把它们也当成空 —— 那正是本项目最忌讳的误报。
     */
    fun line(key: String, value: String?): String = when {
        value == null -> "$key = $NOT_READ"
        value.isEmpty() -> "$key = $EMPTY"
        else -> "$key = $value"
    }

    /**
     * **三态**一行：`null` = 没读到 / `true` = 是 / `false` = 否。
     *
     * ⛔ **别拿 [flag] 干这件事** —— `Boolean` 只有两支，「我们不知道」会被并进「否」，
     *   而那正是 2026-10-05 那次误报的成因。
     */
    fun tri(label: String, v: Boolean?): String = line(
        label,
        when (v) {
            null -> null
            true -> "是"
            else -> "否"
        },
    )

    /** 二态一行 —— 只给「本来就只有真假两种可能」的量用（如 `sensorAvailable`）。 */
    fun flag(label: String, v: Boolean): String = line(label, if (v) "是" else "否")

    /**
     * 多行原文段（日志那种）。
     *
     * ⚠️ 内容走 [Section] 的 `lines`，**不经过** [line]：日志里本来就有换行，
     *   塞进 `key = value` 的形状会把整段压成一行、还会把 `=` 读成语义。
     */
    fun raw(title: String, text: String?): Section =
        Section(title, (text?.takeIf { it.isNotBlank() } ?: NOT_READ).lines())

    /**
     * 拼装整份报告。
     *
     * 形状：头部若干行（版本 / 时间 / 隐私提示）→ 空行 → 每段「标题 + 内容」。
     * 段与段之间插一个空行：方便人眼分段，也方便用户**自己先扫一眼再发出去**。
     */
    fun build(header: List<String>, sections: List<Section>): String {
        val sb = StringBuilder()
        for (h in header) sb.append(h).append('\n')
        for (s in sections) {
            sb.append('\n').append(heading(s.title)).append('\n')
            for (l in s.lines) sb.append(l).append('\n')
        }
        return sb.toString()
    }

    /**
     * 导出文件名。
     *
     * @param stamp 已格式化好的时间戳（如 `20261006_1830`）。
     *   ★ **不在纯函数里取当前时间** —— 否则单测钉不住结果，也会把时区依赖带进来。
     *   由调用方按本机时区格式化。
     */
    fun fileName(stamp: String): String = "HyperPlus_日志_$stamp.txt"
}

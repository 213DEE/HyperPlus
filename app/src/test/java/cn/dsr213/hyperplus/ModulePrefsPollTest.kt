package cn.dsr213.hyperplus

import cn.dsr213.hyperplus.module.ModulePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配置变更的**基线推进**与**差异诊断**（纯函数部分）。
 *
 * ⚠️ 类名里的 `Poll` 是**历史名字**：这些判据原来挂在"兜底轮询"上（每 2 秒比一次文件内容），
 *   而那条腿已随 LSPosed nsp 一起删除（2026-10-03 迁移，配置改走 App 广播推快照）。
 *   函数本体与它们的不变量**一个字都没变**，只是触发时机从"轮询到"变成"推送来了"
 *   —— 所以这个文件照旧成立，只是别再照着名字去找"2 秒轮询"那套代码。
 *
 * ★ 这个文件是被一次真机 bug 逼出来的（2026-09-29）。
 *   当时为了让配置通道改一项内容，我用 `cat > 文件` 就地改写 —— 而 shell 的
 *   "截断 → 写入"之间有个**瞬时窗口**，那一轮正好读到空内容，于是：
 *
 *   ```
 *   07:42:05.475 W HyperPlusModulePrefs: 配置文件已消失或变空 → 保留上一次的配置，不广播
 *   07:42:11.479 I HyperPlusModulePrefs: 兜底轮询发现配置内容变化
 *                                        （新增:uncontrollable_clear,capture_strategy,…,rotate_mode_inner）→ …
 *   ⚠️ 上面这行引文**原样保留**：它是当时真机日志的原话，改了就不是证据了。
 *      （`uncontrollable_clear` 这个键 2026-10-05 已随「实测不可控」功能一起删除，
 *       与这段历史无关 —— 别因为 grep 到它就以为还有代码在用。）
 *   ```
 *
 *   第二行的"新增"里**18 个键全在里面**，可我实际只加了一个键 —— 因为第一行那次空读
 *   把基线打空了（旧代码 `last = cur` 写在"读空早退"**之前**）。
 *   ⇒ 后果分三层，一层比一层隐蔽：
 *     ① 诊断价值归零（看不出真正变的是哪个键）；
 *     ② 中间两轮 `空 == 空` 直接 `continue`，**连日志都没有**（静默丢两轮）；
 *     ③ 真正的配置变化会被延后到"下一次读到非空"才生效。
 *   ⇒ 迁移后这条不变量的意义**更强了**：现在的"一次空读"等价于"App 推来一个坏包"，
 *     而坏包如果被当成"新基线"，下一份正常快照就会整份被判成"新增"（同一类误诊）。
 *   所以它值得用测试钉死，而不是只写个注释。
 */
class ModulePrefsPollTest {

    // ---------------------------------------------------------------- 基线推进

    @Test
    fun emptyReadMustNotWipeTheBaseline() {
        val last = mapOf<String, Any?>("a" to 1, "b" to 2)
        assertNull(
            "读空必须返回 null（保持原基线）—— 这正是 2026-09-29 那个 bug 的分界线",
            ModulePrefs.advanceBaseline(last, emptyMap()),
        )
    }

    @Test
    fun emptyBaselineStaysEmptyWithoutSignal() {
        // 基线本来就是空的（例如刚启动、文件还没写）⇒ 也不该广播
        assertNull(ModulePrefs.advanceBaseline(emptyMap(), emptyMap()))
    }

    @Test
    fun unchangedContentKeepsBaseline() {
        val m = mapOf<String, Any?>("a" to 1)
        assertNull("内容没变 ⇒ 不通知订阅者（否则每次 App 打开都会白跑一轮全量重读）", ModulePrefs.advanceBaseline(m, m.toMap()))
    }

    @Test
    fun changedContentAdvancesBaseline() {
        val last = mapOf<String, Any?>("a" to 1)
        val cur = mapOf<String, Any?>("a" to 1, "b" to 2)
        assertEquals(cur, ModulePrefs.advanceBaseline(last, cur))
    }

    @Test
    fun valueOnlyChangeAlsoAdvances() {
        // 键集合不变、值变了 —— 也是变化（用户改了模式就在这一类）
        val last = mapOf<String, Any?>("rotate_mode" to "SEMI")
        val cur = mapOf<String, Any?>("rotate_mode" to "SYSTEM")
        assertEquals(cur, ModulePrefs.advanceBaseline(last, cur))
    }

    // ---------------------------------------------------------------- 差异诊断

    @Test
    fun changedKeysNamesOnlyTheRealAddition() {
        val last = mapOf<String, Any?>("a" to 1, "b" to 2)
        val cur = mapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3)
        assertEquals("新增:c", ModulePrefs.changedKeys(last, cur))
    }

    @Test
    fun changedKeysSeparatesModifiedAndRemoved() {
        val last = mapOf<String, Any?>("a" to 1, "b" to 2, "gone" to 9)
        val cur = mapOf<String, Any?>("a" to 7, "b" to 2)
        val d = ModulePrefs.changedKeys(last, cur)
        // 顺序固定为 新增 → 改动 → 删除
        assertEquals("改动:a 删除:gone", d)
    }

    @Test
    fun changedKeysSaysIdenticalWhenNothingChanged() {
        val m = mapOf<String, Any?>("a" to 1)
        assertEquals("内容相同", ModulePrefs.changedKeys(m, m.toMap()))
    }

    // ---------------------------------------------------------------- 回归：完整走一遍当时的序列

    /**
     * **这是那个 bug 的复现脚本。**
     *
     * 序列：有内容 → 读到空（`cat >` 的瞬时窗口）→ 有内容（只多一个键）。
     * 期望：基线与诊断都**不受那次空读影响**，差异里只出现真正新增的那个键。
     */
    @Test
    fun regressionEmptyReadInTheMiddleDoesNotPoisonLaterDiagnosis() {
        var baseline = mapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3)

        // 第 1 轮：读到空
        val afterEmpty = ModulePrefs.advanceBaseline(baseline, emptyMap())
        assertNull(afterEmpty)
        // 调用方在 null 时**必须保持原基线**（这就是修复本身）
        afterEmpty?.let { baseline = it }

        // 第 2 轮：内容回来，且真的只多了 `d`
        val cur = mapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3, "d" to 4)
        val next = ModulePrefs.advanceBaseline(baseline, cur)
        assertNotNull("内容变了就必须推进并广播", next)

        assertEquals(
            "只该报真正新增的 d —— 旧代码在这里会报「新增:a,b,c,d」，" +
                "因为那次空读把基线打空了",
            "新增:d",
            ModulePrefs.changedKeys(baseline, cur),
        )
    }

    /**
     * 反面对照：文件**真的**被清空后又有了**全新**内容时，必须照样跟随。
     *
     * ★ 这条是防止"为了修 bug 把功能修死"：`advanceBaseline` 在读空时返回 null
     *   （保持旧基线），看代码像是"永远不跟空配置"，但重新有内容时
     *   `cur != last`（旧基线）照样成立 ⇒ 正常广播。
     */
    @Test
    fun genuineEmptyThenNewContentStillPropagates() {
        val baseline = mapOf<String, Any?>("old" to 1)
        assertNull(ModulePrefs.advanceBaseline(baseline, emptyMap()))
        val fresh = mapOf<String, Any?>("brand_new" to 2)
        assertEquals(fresh, ModulePrefs.advanceBaseline(baseline, fresh))
    }
}

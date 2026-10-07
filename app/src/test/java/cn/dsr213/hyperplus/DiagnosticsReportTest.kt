package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticsReport] 的判定核单测（纯函数、离线可跑）。
 *
 * 钉住的是**「缺失值怎么写」**这一类约定 —— 它们是本项目最容易在重构里被「顺手润色」的东西，
 * 而一旦被润色（比如把 `(未读到)` 改成空串、把三态并成两态），
 * 用户就会重新走进「我确定没拿到 → 跑去系统设置里找」那条错路。
 */
class DiagnosticsReportTest {

    // ================================================================ line()

    @Test
    fun missingValueIsMarkedNotRead() {
        assertEquals("cfgmsg = (未读到)", DiagnosticsReport.line("cfgmsg", null))
    }

    @Test
    fun emptyIsDistinctFromMissing() {
        // ★ 2026-10-07 用户拍板：空串 ≠ null。
        //   模块**无条件**回传所有键（见 `module/EngineHost` 的 `append("|errs=")` 等），
        //   所以「键在、值为空」= 模块**明确报了空**（`errs=` = 没有失败记录），
        //   与「压根没读到」含义相反 ⇒ 必须长得不一样。
        assertEquals("errs = （空）", DiagnosticsReport.line("errs", ""))
        assertTrue(
            "空串与 null 不能显示成同一个东西",
            DiagnosticsReport.line("errs", "") != DiagnosticsReport.line("errs", null),
        )
    }

    @Test
    fun theTwoMarkersDiffer() {
        assertEquals("（空）", DiagnosticsReport.EMPTY)
        assertEquals("(未读到)", DiagnosticsReport.NOT_READ)
        assertTrue("两个缺失标记不能相同", DiagnosticsReport.EMPTY != DiagnosticsReport.NOT_READ)
    }

    @Test
    fun zeroIsARealValue() {
        // ★ 最要紧的一条：`0` 绝不能显示成「未读到」。
        assertEquals("wlN = 0", DiagnosticsReport.line("wlN", "0"))
    }

    @Test
    fun falseStringIsARealValue() {
        assertEquals("cfgold = false", DiagnosticsReport.line("cfgold", "false"))
    }

    @Test
    fun valueIsKeptVerbatim() {
        assertEquals(
            "cfgmsg = 未读到配置镜像",
            DiagnosticsReport.line("cfgmsg", "未读到配置镜像"),
        )
    }

    @Test
    fun blankLookingValueIsNotTrimmed() {
        // 单空格不是空串 ⇒ 不该被当成「没读到」（本类不做 trim，避免自作主张）
        assertEquals("a =  ", DiagnosticsReport.line("a", " "))
    }

    // ================================================================ tri() / flag()

    @Test
    fun triKeepsUnknownApartFromFalse() {
        assertEquals("grant = (未读到)", DiagnosticsReport.tri("grant", null))
        assertEquals("grant = 是", DiagnosticsReport.tri("grant", true))
        assertEquals("grant = 否", DiagnosticsReport.tri("grant", false))
    }

    @Test
    fun triNeverFallsBackToFlag() {
        // 三态与二态的输出**不能重合**：`null` 一定不是「否」
        assertTrue(DiagnosticsReport.tri("x", null) != DiagnosticsReport.tri("x", false))
    }

    @Test
    fun flagWritesYesOrNo() {
        assertEquals("sensor = 是", DiagnosticsReport.flag("sensor", true))
        assertEquals("sensor = 否", DiagnosticsReport.flag("sensor", false))
    }

    // ================================================================ raw()

    @Test
    fun rawSplitsIntoLines() {
        val s = DiagnosticsReport.raw("日志", "第一行\n第二行")
        assertEquals("日志", s.title)
        assertEquals(listOf("第一行", "第二行"), s.lines)
    }

    @Test
    fun rawBlankBecomesNotRead() {
        assertEquals(listOf("(未读到)"), DiagnosticsReport.raw("日志", null).lines)
        assertEquals(listOf("(未读到)"), DiagnosticsReport.raw("日志", "   \n  ").lines)
    }

    @Test
    fun rawKeepsEqualsSignsUntouched() {
        // 日志里常有 `key=value`，⛔ 不能被 line() 那套「key = value」规则改写
        assertEquals(listOf("takeover=1|fg=0"), DiagnosticsReport.raw("状态", "takeover=1|fg=0").lines)
    }

    // ================================================================ heading() / build()

    @Test
    fun headingFormatIsFrozen() {
        assertEquals("===== 环境 =====", DiagnosticsReport.heading("环境"))
    }

    @Test
    fun buildPutsHeaderFirstAndSeparatesSectionsWithBlankLine() {
        val out = DiagnosticsReport.build(
            header = listOf("头一行", "头二行"),
            sections = listOf(
                DiagnosticsReport.Section("甲", listOf("a = 1")),
                DiagnosticsReport.Section("乙", listOf("b = 2")),
            ),
        )
        assertEquals(
            "头一行\n头二行\n\n===== 甲 =====\na = 1\n\n===== 乙 =====\nb = 2\n",
            out,
        )
    }

    @Test
    fun buildWorksWithNoSections() {
        assertEquals("只有头\n", DiagnosticsReport.build(listOf("只有头"), emptyList()))
    }

    @Test
    fun everyLineEndsWithNewline() {
        val out = DiagnosticsReport.build(
            listOf("h"),
            listOf(DiagnosticsReport.Section("s", listOf("l"))),
        )
        assertTrue("整份报告必须每行都以 \\n 收尾（否则拼接时会粘行）", out.endsWith("\n"))
    }

    // ================================================================ fileName()

    @Test
    fun fileNameIsStable() {
        assertEquals("HyperPlus_日志_20261006_1830.txt", DiagnosticsReport.fileName("20261006_1830"))
    }
}

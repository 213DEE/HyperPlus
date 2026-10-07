package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.SplitUnfoldDirection
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**分屏增强**（2026-10-04 新增，入口在「功能」主页）。
 *
 * ============================ 它为什么单独成页 ============================
 * 沿用 [RotationPage] 立下的形状（用户 2026-09-29 原话：「功能页设置二级菜单旋转增强，
 * 把旋转的相关设置都放进二级菜单里面，**因为以后还要添加更多功能**」）：
 * 主页只放"入口 + 当前取值摘要"，某个能力的**全部设置**都装进它自己的二级页。
 * ⇒ 这一页是第二个这样的二级页，`Route.SplitScreen` 就是照那条纪律开的。
 *
 * ⚠️ **别把这个入口塞进「旋转增强」页**：分屏与屏幕旋转是两件事
 *   （前者管窗口怎么分，后者管屏幕转多少度）—— 混在一页里，用户会以为
 *   "分屏方向"是旋转的一个参数。两者唯一的共同点只是都住在同一个模块里。
 *
 * ============================ 现在这一页有什么 ============================
 * ① **2分屏展开方向**（[SplitUnfoldDirection]）—— 用户 2026-10-04 点名要的
 * （原话：「直接在 app 里面给选项「2 分屏展开方向：朝左 / 朝右」」）。
 *
 * ⚠️ 它是**配置先行**：分屏触发链（"轻折一下多一格"）本身还没接上，所以现在改它
 *   还看不到任何行为变化 —— 值会照常落盘、照常推给引擎（见
 *   [cn.dsr213.hyperplus.PrefsBridge.SPLIT_UNFOLD_DIR]），等触发链做好就直接生效。
 *   ⛔ 别为了让界面"看起来有用"在这里加任何临时行为（例如顺手弹个提示说"已保存"）。
 *
 * 🔴 ② ~~**角度校准**~~ —— **2026-10-06 已随功能整体下线**。
 *   用户原话：「**把角度校准功能删掉，不给这么多自定义功能，越多越难做**」
 *   ⇒ 触发阈值从此是**编译期常量** [cn.dsr213.hyperplus.SplitThresholds.DEFAULT]，
 *     本页不再有任何通往它的入口。⛔ 别把它加回来（已否一次）。
 *
 * ③ 这一页**只改配置**：⛔ 别在这里显示引擎在不在、也⛔ 别拿引擎回传的状态当控件的值
 *   （同 [RotationPage] 那条告诫，理由见下面）。
 *
 * ============================ 选项为什么是两档而不是开关 ============================
 * 它是**互斥的两个取值**，不是"开/关" —— 用 [RadioButtonPreference] 与
 * [RotationPage] 的「模式」三档单选保持同一种表达（选中态一眼可辨、整行可点）。
 * ⛔ 别改成 `SwitchPreference`：那样会凭空多出一个"关闭"态，而"不选边"没有意义。
 */
@Composable
internal fun SplitScreenPage(
    onBack: () -> Unit,
    /** 打开三级页（应用名单）—— 与 [RotationPage] 同一个形状，理由见那边。 */
    onOpen: (Route) -> Unit,
) {
    // ★ 真值一律来自 AppPrefs（写配置的唯一入口，也是引擎会跟的那份文件的内存投影）。
    //   ⚠️ 与 [RotationPage] 里那条告诫同源：绝不从引擎回传的状态里取开关真值 ——
    //     状态是慢变量（变化才上报），拿它当控件值会出现"点了没反应"的假象。
    val dir by AppPrefs.splitUnfoldDir.collectAsState()

    // ------------------------------------------------ 「到上限」提示**不在这里**（2026-10-05 更正）
    //
    // 🔴 第一版把它做成了这一页里的 `LaunchedEffect` 轮询 + `remember` 已读游标 —— **已删除**，
    //   因为它**送不到用户手里**：用户折手机的那一刻人**在分屏的两个 App 里**，
    //   本 App 不在前台 ⇒ 这个 effect 压根没跑，引擎明明上报了，他什么都看不到。
    // ⇒ 改由 [cn.dsr213.hyperplus.MainActivity] 用两条腿送：
    //   ① `Settings.System` 的 `ContentObserver`（用户就开着本 App 时当场弹）
    //   ② `onResume` 补发（覆盖"在分屏里折完、回到本 App"这个最常见路径）
    //   已读游标也**落盘**在 App prefs（见 [AppPrefs.splitLimitSeen]）。
    // ⛔ 别把轮询加回来：页面级的生命周期与"用户什么时候折手机"在时间上毫无关系。

    SubPage(title = stringResource(R.string.split_title), onBack = onBack) {
        SmallTitle(text = stringResource(R.string.split_dir_section))
        Card(modifier = Modifier.padding(bottom = 12.dp)) {
            // ★ 说明放在选项**之上**：它解释的是"这一组选项在管什么"，
            //   放下面会变成"用户先做完选择才读到解释"。
            //   ⚠️ 内边距照抄 [DirectionSection] 里那张卡（同一形状的两处实现，将来一起改）：
            //     选项控件自带左右 16dp，所以说明这里补上 16dp 才不会与它错位。
            Text(
                text = stringResource(R.string.split_dir_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            )
            // 顺序按枚举声明（LEFT → RIGHT），也就是用户给的那句话里的顺序（"朝左 / 朝右"）。
            // ⚠️ 用 `entries` 而不是手写两个分支：将来真加了取值（例如"跟随上次"），
            //   这里会自动跟上，不会出现"枚举加了、界面漏了"。
            SplitUnfoldDirection.entries.forEach { d ->
                RadioButtonPreference(
                    title = stringResource(d.labelRes),
                    selected = dir == d,
                    onClick = { AppPrefs.setSplitUnfoldDir(d) },
                )
            }
        }

        // ------------------------------------------------ 高温保护（2026-10-05 新增）
        ThermalSection()

        // ------------------------------------------------ 应用名单（**一行入口**，2026-10-06 改）
        //
        // 用户原话（2026-10-05）：「**分屏增强也添加应用列表，把市面上常见的游戏包名添加进默认列表**」。
        // 用户原话（2026-10-06）：「**还是很乱，可以适当增加三级菜单，要让所有功能都清晰明了**」
        // ⇒ 整块内容搬进三级页 [Route.SplitApps]，本页只留一行入口。
        //   ★ 那一页与旋转那份**共用同一个实现**（[WhitelistPage]），只是服务**另一份独立名单**
        //     （见 [SplitWhitelist] 类注释"各自独立一份"）⇒ 两页长得一样，但数据互不影响。
        //
        // ★ 固定在**整页最末**（骨架 R5 的第 ⑤ 段：作用对象永远排在设置之后），
        //   理由与 [RotationPage] 那条逐字相同（名单一两百行）。
        SectionCard(title = stringResource(R.string.swl_entry_section)) {
            ArrowPreference(
                title = stringResource(R.string.swl_page_title),
                summary = stringResource(R.string.swl_entry_summary),
                onClick = { onOpen(Route.SplitApps) },
            )
        }
    }
}

/**
 * 「高温保护」那一张卡 —— 温度上限输入框 ＋ 红字风险提示 ＋ 恢复默认 ＋ 关闭保护的开关。
 *
 * ============================ 它为什么单独一张卡 ============================
 * 上面两张卡改的都是**体验**（分屏往哪边开、折多深触发），这一张改的是
 * **厂商的安全保护**（见 [PrefsBridge.SPLIT_THERMAL_LIMIT_C] / [SPLIT_THERMAL_GUARD_OFF]）。
 * 混进任何一张现有卡里都会让"改它是有代价的"这件事被冲淡 —— 它值得自己一段小标题。
 *
 * ============================ 界面结构为什么是这样 ============================
 * 用户原话（2026-10-05）：「在app里添加修改温度上限的输入框，**禁止超过60度**，
 * 并用**红字**标注警告"该操作存在风险，请谨慎修改"，下面要配一个**恢复默认**的按钮；
 * 再加一个**关闭过热保护的开关**，加一个**二次确认弹窗**，弹窗里面写清楚
 * **关闭过热保护可能造成不可逆的风险**。」
 * ⇒ 逐条落地：输入框（见 [ThermalLimitField]）→ 红字警告 → 恢复默认 →
 *   开关（见 [SwitchPreference] 那一行）→ 二次确认弹窗（见 [ThermalGuardDialog]）。
 *
 * ============================ ⛔ 两条别踩的线 ============================
 * ① **输入框不是"逐字符写盘"的**：真正落盘走的是**用户按下「确认」时校验一次**
 *    （见 [ThermalLimitField]）——
 *    ⛔ 别在 `onValueChange` 里逐字符写盘：那样用户想输 "55" 时，打到 "5" 就已经
 *    把阈值改成了 5（会被夹到 30），中途状态被写进去既无意义又会让引擎抖一下。
 * ② **关掉保护必须过弹窗**，且**弹窗里点错方向不能改配置** ——
 *    ⛔ 别写成 `onCheckedChange = { AppPrefs.setSplitThermalGuardOff(it) }` 就直接生效。
 */
@Composable
private fun ThermalSection() {
    val limit by AppPrefs.splitThermalLimitC.collectAsState()
    val guardOff by AppPrefs.splitThermalGuardOff.collectAsState()

    // 二次确认弹窗的可见性。★ 两个方向各有一个标题/正文（开→关 是高风险那次，关→开 只是回退），
    //   ⛔ 别只做一个"确定/取消"的通用弹窗 —— 那样高风险那次的正文会被回退那次稀释。
    var pendingOff by remember { mutableStateOf<Boolean?>(null) }

    SmallTitle(text = stringResource(R.string.split_thermal_sec_title))
    Card(
        modifier = Modifier.padding(bottom = 12.dp),
        // ⚠️ 卡里既有自带左右 16dp 的 preference 控件（开关），又有自己画边距的 Text/TextField
        //   ⇒ 内边距给 0、由各控件自己负责（同 [DirectionSection] 那张卡的取舍）。
        insideMargin = PaddingValues(0.dp),
    ) {
        Text(
            text = stringResource(R.string.split_thermal_limit_note),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.paragraph,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        )
        ThermalLimitField(limit = limit)
        // ★ 红字警告 —— 用户点名要的。位置刻意在**输入框正下方**：
        //   它约束的是上面那个框，放远了就变成一段与控件无关的说明。
        // ⚠️ 用 `MiuixTheme.colorScheme.error` 而不是写死 `Color.Red`：
        //   深色/浅色主题各有一份正确的红，写死会在其中一个主题上刺眼到看不清字。
        Text(
            text = stringResource(R.string.split_thermal_warn),
            color = MiuixTheme.colorScheme.error,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        )
        // ★ 「恢复默认」：走 [TextButton] 而不是 [ArrowPreference] ——
        //   ★★ 2026-10-05 改（用户点名"不符合 HyperOS 设计规范"）：[ArrowPreference]
        //   右边那个 `>` 在 HyperOS 里表示**这一行点进去还有一页**。而"恢复默认"当场就把
        //   值改回去了，没有下一页 ⇒ 画成带箭头的行是在骗用户"点进去看看"，
        //   他点完发现什么都没发生，只会以为按钮坏了。
        //   ⇒ 动作就用动作的形状：与全工程其他卡片内按钮同一个写法（[BTN_SLOT]）
        //     （满宽 + 上边距 8dp），用户在这两页看到的是同一种东西。
        //   ⚠️ 摘要里**报出默认值是多少**：用户点它之前不必知道 47 这个数
        //     （见 [AppPrefs.resetSplitThermalLimitC]）。
        Text(
            text = stringResource(
                R.string.split_thermal_limit_summary,
                AppPrefs.THERMAL_LIMIT_C_DEFAULT,
            ),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
        TextButton(
            text = stringResource(R.string.split_thermal_reset),
            onClick = { AppPrefs.resetSplitThermalLimitC() },
            // ⚠️ 本卡是**手写 Card + insideMargin = 0**（里面装着自带内边距的输入框与开关）
            //   ⇒ 卡片不会替按钮留边距，必须自己补水平内缩，否则按钮左右贴住卡片边缘。
            //   契约见 [BTN_SLOT] / [CARD_H_INSET]。
            modifier = BTN_SLOT.padding(start = CARD_H_INSET, end = CARD_H_INSET),
        )

        // ★ 「关闭过热保护」开关 —— 点它**只弹确认框**，不直接改配置。
        //   🔴 这是全模块唯一一处拆厂商保护的入口，理由见上面那条纪律 ②。
        SwitchPreference(
            checked = guardOff,
            onCheckedChange = { pendingOff = it },
            title = stringResource(R.string.split_thermal_guard_title),
            summary = stringResource(R.string.split_thermal_guard_summary),
        )
    }

    pendingOff?.let { want ->
        ThermalGuardDialog(
            turningOff = want,
            onDismiss = { pendingOff = null },
            onConfirm = {
                AppPrefs.setSplitThermalGuardOff(want)
                pendingOff = null
            },
        )
    }
}

/**
 * 温度上限的输入框（＋即时校验）。
 *
 * ============================ 为什么把字符串存成 local state ============================
 * 用户可能**打到一半**（"5" → "55"），中间态不是合法配置（会被夹到 30）。
 * ⇒ 输入期间只动本地的 `text`，**只有按下「确认」时才尝试落盘**（见 `commit`）。
 * 这样用户看到的就是他自己打的字，而不是"我打 5、屏幕上跳成 30"。
 *
 * ⚠️ 这里刻意用 **String 版重载**（`value=` / `onValueChange=`）而不是新版的
 *   `TextFieldState` 版：本工程已有 [AppWhitelistPage] 在用前者，而 `TextFieldState`
 *   的切换/回写语义（`edit { }` 里的 replace）在这里只有"整体覆盖"这一种用法，
 *   用 String 更直白。
 *
 * ⚠️ 别把初始值写成 `limit.toString()` 而不再同步：默认值按钮
 *   （[AppPrefs.resetSplitThermalLimitC]）会在**不重建本组件**的情况下改掉 `limit`
 *   ⇒ 必须有一个"外部值变了就跟着走"的同步（下面那个 `if` 分支），
 *   否则用户点了「恢复默认」，框里还留着他刚输的 59。
 */
@Composable
private fun ThermalLimitField(limit: Int) {
    // ⚠️ 只记住**上一次从外部同步过来的那个值**，用来判"是不是外部改的"。
    var syncedFrom by remember { mutableStateOf(limit) }
    var text by remember { mutableStateOf(limit.toString()) }
    // 校验失败的提示（false ⇒ 不显示）。★ 它只在**用户按了确认却给不出合法数**时出现，
    //   而**不是**输错就红 —— 边打字边报错会把"还没打完"当成错误。
    var invalid by remember { mutableStateOf(false) }

    if (limit != syncedFrom) {
        // 外部（恢复默认 / 文件被改）改了值 ⇒ 覆盖输入框
        syncedFrom = limit
        text = limit.toString()
        invalid = false
    }

    /** 尝试把当前文本落盘；不合法就点亮校验提示（见上面 `invalid` 的说明）。 */
    fun commit() {
        val v = text.trim().toIntOrNull()
        if (v == null || v < AppPrefs.THERMAL_LIMIT_C_MIN || v > AppPrefs.THERMAL_LIMIT_C_MAX) {
            invalid = true
            return
        }
        invalid = false
        AppPrefs.setSplitThermalLimitC(v)
        // ★ 回写一次：用户输 "047" / " 47 " 这类写法时，落盘的是 47，
        //   框里也该显示 47（否则"框里的字"与"真值"看起来不一致）。
        text = AppPrefs.clampThermalLimitC(v).toString()
        syncedFrom = AppPrefs.clampThermalLimitC(v)
    }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
        TextField(
            value = text,
            onValueChange = {
                // ★ 只收数字字符，且限长 2 位（合法区间是 40~60，两位数够用）。
                //   ⚠️ 这道过滤**不是**校验：它只挡明显不可能的东西（字母、三位数），
                //   真正的范围校验在 [commit] 里 —— 两件事分开，报错文案才说得准。
                if (it.length <= 2 && it.all { c -> c.isDigit() }) {
                    text = it
                    invalid = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.split_thermal_limit_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (invalid) {
            Text(
                text = stringResource(
                    R.string.split_thermal_limit_invalid,
                    AppPrefs.THERMAL_LIMIT_C_MIN,
                    AppPrefs.THERMAL_LIMIT_C_MAX,
                ),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // ★★ 2026-10-05 改：这里原来是**裸 `Text` + `clickable`**（用户点名"不符合 HyperOS
        //   设计规范"）。三处不合规，逐条对齐：
        //   ① **必须用按钮组件** —— 裸 `Text` 没有 HyperOS 的按压态（按下缩放/水波纹）
        //      也没有最小触控高度（`ButtonDefaults.minHeight`），手指粗一点就点不中；
        //   ② **满宽** —— 复用 [BTN_SLOT]（`fillMaxWidth()` + 上边距 8dp），与校准页那排
        //      按钮、「恢复默认」**三者同宽同距**，这是用户 2026-10-04 定下的
        //      「按钮之间要有间距，不要黏在一起」+「全工程卡片内按钮统一」；
        //   ③ **用默认 `TextButton`（浅底深字）** —— ⚠️ 这里踩过一次：第一版写过
        //      `colors = ButtonDefaults.textButtonColorsPrimary()`，那是**主色填充 + 反白字**，
        //      看起来像页面主 CTA（＝ `DonateCard` 里那个「保存二维码到相册」）。
        //      而「确认」与它下面的「恢复默认」**是同一级的动作**（都只是"改这个值"），
        //      同一张卡里出现一深一浅两种重量，用户会以为「确认」才是必须按的那个 ——
        //      可事实上改完输入框不按它、直接去拨开关也不会出错。
        //      ⇒ **同级动作 = 同一种形状**（都走默认 `TextButton`）。
        //      `textButtonColorsPrimary()` 留给"这一页的主要 CTA"，本卡没有那种东西。
        //   ⛔ 别改回 `Text(modifier = Modifier.clickable { })`（已否一次）；
        //   ⛔ 也别改成 `textButtonColorsPrimary()`（已否两次）。
        TextButton(
            // ★ 复用全模块那个「确认」：同一个动作、同一个词，⛔ 别为它再开一条资源
            //   （多一条就多一处要在三种语言里同步维护）。
            //   ⚠️ 2026-10-06：它原名 `split_calib_btn_confirm`（角度校准页的确认按钮），
            //     校准下线后**改名保留** —— 本卡是它唯一的用处了。
            text = stringResource(R.string.split_thermal_btn_confirm),
            onClick = { commit() },
            // ⚠️ 同上（本卡内边距为 0）；它又是本卡**最后一个**控件 ⇒ 还要补底部内缩，
            //   否则按钮贴住卡片下沿。
            modifier = BTN_SLOT.padding(
                start = CARD_H_INSET,
                end = CARD_H_INSET,
                bottom = CARD_H_INSET,
            ),
        )
    }
}

/**
 * 「关闭过热保护」的**二次确认弹窗**。
 *
 * ============================ 为什么两个方向要两套文案 ============================
 * - `turningOff == true`（开 → 关）：**高风险那次**。正文必须写清不可逆风险
 *   （这是用户点名要的，见 `split_thermal_on_dlg_msg` 三套资源里的注释）。
 * - `turningOff == false`（关 → 开）：只是**回退**，一句"会重新阻止你增加分屏"就够 ——
 *   ⛔ 别复用高风险那段：把恢复保护也说得像危险操作，用户会连恢复都不敢点。
 *
 * ⚠️ 确认按钮的措辞跟着动作走（"关闭" / "关闭"）—— 见 `split_thermal_dlg_confirm`。
 *   恢复那一次用的也是它，因为**按钮说的是动作**（把开关拨到"关闭保护"），
 *   而不是"关掉保护"。⛔ 别为了区分这两次给恢复配一个"确定"。
 *
 * ============================ ★★ 按钮为什么是"两个同色文本按钮" ============================
 * 🔴 2026-10-05 重写（用户原话：「高温保护的确认按钮和二次确认的按钮**不符合 HyperOS
 *   的 UI 设计规范**，优化一下」）。第一版把高风险那次的确认做成了**实心红底大按钮**
 *   （`Button(colors = buttonColors(color = error, contentColor = onError))`），
 *   那**不是** HyperOS 的做法。规范形状只有一种，本工程里 [UpdateDialog] 就是范例：
 *
 *   ① **弹窗按钮恒为 [TextButton]**（文本按钮），一左一右两个，**不涂实心底** ——
 *      HyperOS 的弹窗里"重"是靠**位置**（确认在右、离拇指近）和**文案**表达的，
 *      不是靠把按钮刷成红的。刷红底反而会让弹窗里出现第二个视觉焦点，
 *      跟标题抢注意力，用户第一眼看到的是"一块红"而不是"我要读的那句话"。
 *   ② **间距用 `Arrangement.spacedBy(8.dp)`**，⛔ 别写 `Modifier.padding(start = 8.dp)`
 *      去给后一个按钮让位：两者在只有一个按钮的布局里表现不同（前者在**按钮之间**排版，
 *      后者无条件给右边留白），而 `spacedBy` 是 [UpdateDialog] 用过的那一个。
 *   ③ **危险动作靠正文说清**（`split_thermal_on_dlg_msg` 里写明不可逆），
 *      并且**取消在左、确认在右** —— 用户误触时手指落到的是"取消"。
 *      ⛔ 别把确认挪到左边（那样最坏情况变成"误触即关闭保护"）。
 *
 *   ⚠️ 这个形状与 [UpdateDialog] 完全一致 ⇒ 用户在本应用里见到的是同一种弹窗，
 *      不用为"这个弹窗的按钮为什么长得不一样"多花一秒。
 */
@Composable
private fun ThermalGuardDialog(
    turningOff: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp),
        ) {
            Text(
                text = stringResource(
                    if (turningOff) R.string.split_thermal_on_dlg_title
                    else R.string.split_thermal_off_dlg_title
                ),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (turningOff) R.string.split_thermal_on_dlg_msg
                    else R.string.split_thermal_off_dlg_msg
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 间距 8dp 的写法照抄 [UpdateDialog]（见上面纪律 ②）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.split_thermal_dlg_cancel),
                    onClick = onDismiss,
                )
                TextButton(
                    text = stringResource(R.string.split_thermal_dlg_confirm),
                    onClick = onConfirm,
                )
            }
        }
    }
}

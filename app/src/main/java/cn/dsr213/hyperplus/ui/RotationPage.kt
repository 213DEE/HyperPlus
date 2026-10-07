package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppRotateMode
import cn.dsr213.hyperplus.CaptureStrategy
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import cn.dsr213.hyperplus.ScreenForm
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**旋转增强**。
 *
 * ============================ 为什么它单独成页（2026-09-29）============================
 * 用户原话：「功能页设置二级菜单旋转增强，把旋转的相关设置都放进二级菜单里面，
 * **因为以后还要添加更多功能**」。
 *
 * ⇒ 这一页是「功能」页里**第一个**二级页，也是这一轮重构的样板：
 *   凡是"某个能力的全部设置"都装在一个二级页里，主页只留入口。
 *   以后新的能力照这个形状加即可，不用动主页的结构。
 *
 * 内容（★ **2026-10-06 重排后的顺序** —— 用户原话：「**还是很乱**，可以适当增加三级菜单，
 *   要让所有功能都清晰明了」）：
 *   ① 模式        —— 内屏一份三态单选
 *   ② 方向        —— 一张卡（[DirectionSection]）：**展开后的方向**，四选一，每项配一部手机小图标
 *                      ★ 2026-10-06 **上移到主设置区**：它此前被「使用提醒 / 预览按钮」两段
 *                        **说明**压在**倒第二**，而用户进这一页 90% 就是为了改它。
 *   ③ 开关        —— 省电优先 / 名单门控 / 停手交还 / 按钮等待时长
 *   ④ 使用提醒    —— 只在"当前这块屏"是自适应档时出现
 *   ⑤ 应用名单    —— **一行入口**（→ 三级页 [Route.RotationApps]）
 *
 * 🔴 2026-10-06 删掉的一整段：**预览旋转按钮**（用户点名「去掉旋转增强的预览旋转按钮」）。
 * ⇒ 页内从 **7 段降到 4 段**（模式 / 方向 / 开关 / 提醒）＋ 1 行入口 —— 一屏能看完。
 *
 * ==================== 应用名单的"两进两出"（2026-10-01 / 2026-10-06）====================
 * - **2026-10-01 进**：用户原话「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」
 *   ⇒ `Route.Apps` 删除，整块内容铺进这一页（那时是 `AppWhitelistSection()`）。
 * - **2026-10-06 出**：用户改口径「**还是很乱，可以适当增加三级菜单**」⇒ 内容搬进三级页
 *   [Route.RotationApps]（实现是 [WhitelistPage]，与分屏那份**共用**），本页只留**一行入口**。
 *
 * ★ 两次调整背后是**同一个理由**：名单是一两百行的巨型列表（实测）。
 *   铺在页内会把页面上所有设置推出几屏外；而它又不能被删（"对谁生效"必须有地方管）。
 *   ⇒ 结论：**内容留在体系里，长度搬出页面外**。⛔ 别再往页内铺。
 * ⚠️ 2026-10-06 这次动的只是"**二级**菜单"那半句 —— 名单**仍然不是一个二级页**，
 *   而是「二级页的一条入口 ＋ 一个三级页」。⛔ 别据此把它搬回二级页。
 *
 * ★ 旧的「配置通道」卡片**没有**搬进来 —— 它是所有设置生效的前置条件，
 *   搬进二级页会让"我改的东西到底生效没有"变成要翻进去才知道的事，
 *   所以它改挂在「设置」主页上（见 [SettingsPage]）。
 *   诊断读数同理，仍然在「诊断」页（[DiagnosticsPage]）。
 */
@Composable
internal fun RotationPage(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * 打开三级页（目前只有「应用名单」一个出口）。
     * ★ 用 `(Route) -> Unit` 而不是一个专用的 `onOpenApps`：外壳那边本来就是
     *   `go(route)`（`HyperPlusApp.kt`），多一个参数就能复用它，⛔ 不必为每个出口
     *   各开一个回调 —— 那样每加一个三级入口都要动一次外壳。
     */
    onOpen: (Route) -> Unit,
) {
    // ★ 配置的真值一律来自 AppPrefs（**写配置的唯一入口**，也是引擎会跟随的那份文件的内存投影）。
    //   ⚠️ 绝不能从引擎回传的状态里取开关真值：状态是**慢变量**（变化才上报），
    //     拿它当开关值会出现"点了没反应"的假象（2026-09-25 实测踩过）。
    //
    //   `mode` = 当前形态的**生效档**（派生值，见 AppPrefs.mode 的注释）。
    //   ⚠️ 跑在**外屏**时它恒为 SYSTEM（"外屏不介入"，闸在 `AppPrefs.modeOf`）——
    //     「使用提醒」正是按它判的：外屏上不会提醒"自适应在抢相机"，因为外屏压根不开相机。
    val mode by AppPrefs.mode.collectAsState()
    val modeInner by AppPrefs.modeInner.collectAsState()
    val form by AppPrefs.screenForm.collectAsState()
    val strategy by AppPrefs.strategy.collectAsState()
    val handoffRotate by AppPrefs.handoffRotate.collectAsState()
    val gateEnabled by AppPrefs.gateEnabled.collectAsState()
    // ★ 2026-10-03：自适应旋转是**实验功能** ⇒ 默认不在模式单选里列出。
    //   开关在「实验功能」页（入口在功能主页最底下），见 [ExperimentalPage]。
    //
    // ★★ 2026-10-04：**同一个开关现在管两项** —— 「模式」里的自适应旋转
    //   与「开关」里的省电优先（用户原话：「实验功能里面『自适应旋转』一个开关，
    //   控制旋转增强页里面『自适应旋转』、『省电优先』两个功能的可见」）。
    //   ⚠️ 名字沿用 `showAdaptive` 不再贴切，但**改个名要动的地方比它省下来的多**，
    //     而且它会立刻误导下一个读者 —— 所以变量名保持，靠这段注释和 [ExperimentalPage] 的说明兜住。
    //     判据仍然是"实验开关开没开"，与 [AppPrefs.setExperimentalAdaptive] 同一个值。
    val showAdaptive by AppPrefs.experimentalAdaptive.collectAsState()
    // ★ R1（2026-10-05）：这一项 + 逐应用的档，一起决定「读不到光线时降级」那个开关
    //   列不列出来（理由写在开关旁边那一行，别只看这里的名字猜）。
    val r1Fallback by AppPrefs.r1Fallback.collectAsState()
    val appModes by AppPrefs.appModes.collectAsState()
    val hintMs by AppPrefs.hintMs.collectAsState()
    val ctx = LocalContext.current

    SubPage(title = stringResource(R.string.rotation_title), onBack = onBack) {
        // ---------------------------------------------------- 外屏：置顶红条，先说"此刻不生效"
        //
        // ★★ 2026-10-02 用户点名（原话：「还有"你现在在外屏、外屏不做处理"这种提示，
        //   改成红字或红底置顶，"当前屏幕不生效，请打开内屏"」）。
        //   改前的样子是一张**灰底**说明卡、位置在「模式」下面 —— 语气是"告知"，
        //   可它说的其实是"这一页上的一切此刻都不会生效"，那是**警告**。
        //   ⚠️ 所以它必须在**所有内容之上**：用户在这里改的每个开关，外屏上都不生效；
        //     放在被影响的那一行旁边等于"他先动完手才看到说明"。
        if (form == ScreenForm.OUTER) {
            OuterScreenBanner(reason = stringResource(R.string.rotation_outer_reason))
        }

        // ---------------------------------------------------- 模式（只有一份：内屏）
        //
        // ★ 2026-09-28 由"单个开关（自适应 开/关）"升级为**三态单选**：
        //   半自动不是"自适应的一个增强"，而是**另一条链路**
        //   （检测姿态、不开相机、点击确认）。用开关表达不了三态，
        //   用单选才是诚实的 —— 而且用户一眼能看出这三个是互斥的。
        //
        // ★★ 2026-09-29 用户拍板：「直接删除外屏的旋转增强，只保留内屏的旋转增强和应用豁免」。
        //   ⇒ 这里从"内屏 / 外屏两组单选"收成**一组**。外屏的行为是**恒定**的：
        //     完全交给系统和应用自己（不弹按钮、不写方向、不开相机），没有任何可配的东西。
        //     实现闸在 `AppPrefs.modeOf`（跑在外屏时生效档恒为 SYSTEM）——
        //     ⛔ 别再往这一页加"外屏模式"：加了也没人读。
        TextCard(title = stringResource(R.string.rotation_mode_section)) {
            Text(
                text = stringResource(R.string.rotation_mode_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
            )
        }
        SmallTitle(
            text = stringResource(
                if (form == ScreenForm.INNER) R.string.rotation_mode_title_active
                else R.string.rotation_mode_title,
            ),
        )
        ModePicker(value = modeInner, showAdaptive = showAdaptive)

        // ---------------------------------------------------- 展开后的方向（**主设置**，2026-10-06 上移）
        //
        // ★★ 原先这里是**两张卡**：「方向校准」（内联在本文件）与「默认方向」
        //   （原 `ui/SlotCalibrationSection.kt`）。用户当天把两块一起否了 ——
        //   原话「用不明白，不会知道正确的竖屏方向是哪一边，也看不懂手机横屏、摄像头朝左是什么姿势」。
        //   ⇒ 先合并成一张 [DirectionSection]，姿势一律**画成图**。
        //
        // ★★★ 随后用户又追加一条：「**不要用摄像头来校准了，用重力传感器**」
        //   ⇒ 校准**整块删除**（含瞄准器、①② 分步、重新校准按钮），改由引擎全自动：
        //     `AdaptiveEngine.noteSignEvidence` 每轮都用**重力扇区**校验符号位，证据够了自动翻正。
        //     ⇒ 用户从此**一个按钮都不用点**。完整理由见 [DirectionSection] 的类注释。
        //
        // ★★ 2026-10-06 位置**再动一次**（用户原话：「**还是很乱**，可以适当增加三级菜单，
        //   要让所有功能都清晰明了」）：它原本被「使用提醒」「预览旋转按钮」两段**说明**
        //   压在**倒第二**，而用户进这一页 90% 就是为了改它。
        //   ⇒ 现在紧跟「模式」，两者并列为这一页的**两件主设置**；其余一切（开关 / 提醒 / 名单）
        //     都排在它后面。
        //   ⛔ 别把它再挪回后面 —— 这条已按方案 R5「③ 主设置必须落在首屏」定过一次。
        //
        // ⚠️ 原来内联在这里的 `LiveAngleSection()`（角度盘 + 原始读数行）已一并退役：
        //   它本来就是给校准看的，校准没了它就没有存在的理由。
        //   ✅ 2026-10-04：界面之外那条链（引擎采样循环 + `live_angle` 读数键 + 解析器）
        //     也一并删除，不留半截。⛔ 别再把任何"实时角度显示"加回来。
        DirectionSection()

        // ⚠️ 2026-10-02：这里原来还有一张「你现在在外屏」说明卡，已搬到页面**最顶部**
        //   并升级成红条（[OuterScreenBanner]）。⛔ 别把说明卡加回来 —— 两处说同一件事，
        //   而且中段那张会被当成普通说明扫过去。

        // ---------------------------------------------------- 开关
        SectionCard(title = stringResource(R.string.rotation_section_switches)) {
            // ★★ 2026-10-04：省电优先**只在会开相机的档位下才有意义**，所以它的可见性
            //   比自适应旋转还多一道闸 —— 两个条件**都要**满足才列出：
            //     ① 实验开关开着（`showAdaptive`，与自适应旋转共用同一个开关）；
            //     ② **当前生效档是自适应**（`mode == RotateMode.ADAPTIVE`）。
            //
            //   ⛔ 为什么必须有②（2026-10-04 用户指出，我第一版写错过）：
            //     省电优先管的是**相机策略**（`POWER_SAVING` = 用完就关 / `RESPONSIVE` = 保温），
            //     而**半自动与跟随系统压根不开相机**（半自动靠重力+按钮，见 [RotateMode.SEMI] 的注释）
            //     ⇒ 那两种档位下拨这个开关**什么都不会发生**。
            //     把它列出来 = 摆一个"按了没反应"的开关在用户面前，比不列更糟。
            //   ⚠️ 也不要因为它"当前值是 POWER_SAVING"就强行列出（我第一版就是这么写的）——
            //     那正是上面那种"不生效却看得见"的状态。
            //
            //   ⚠️ 代价（知道清楚再改）：切档时它会**忽隐忽现**。这是刻意的 ——
            //     它的价值就在于"只在真起作用的时候出现"，而不是当个摆设。
            if (showAdaptive && mode == RotateMode.ADAPTIVE) {
                SwitchPreference(
                    title = stringResource(strategy.labelRes),
                    summary = stringResource(strategy.summaryRes),
                    checked = strategy == CaptureStrategy.POWER_SAVING,
                    onCheckedChange = { on ->
                        AppPrefs.setStrategy(
                            if (on) CaptureStrategy.POWER_SAVING else CaptureStrategy.RESPONSIVE,
                        )
                    },
                )
            }
            // ★★ 2026-10-05（R1）：自适应「读不到环境」时降级成半自动。
            //
            //   可见性判据与上面那个省电优先**同源**（两者都只在"**有可能开相机**"时才有意义，
            //   见那一段的理由），但多认一种情形：**逐应用的档**。
            //   ⛔ 为什么不能只看全局档 `mode`：R2（2026-10-05）之后"档位"是逐应用的 ——
            //     用户完全可以全局设成半自动、只给某个应用点名自适应。那时这个开关**是有作用的**，
            //     只按全局档判就会把它藏起来，而用户恰恰是为了那几个应用才需要它。
            //   ⛔ 也别因为"当前值是出厂默认的 true"就强行列出 —— 那正是"不生效却看得见"。
            val hasAdaptive = mode == RotateMode.ADAPTIVE ||
                appModes.values.any { it == AppRotateMode.ADAPTIVE }
            if (showAdaptive && hasAdaptive) {
                SwitchPreference(
                    title = stringResource(R.string.rotation_fallback_title),
                    summary = stringResource(
                        if (r1Fallback) R.string.rotation_fallback_on
                        else R.string.rotation_fallback_off,
                    ),
                    checked = r1Fallback,
                    // ⚠️ 真正的判定（连续几轮算"读不到"、多久试一次恢复）全在**引擎**里
                    //   （`AdaptiveEngine.noteAdaptiveEnv` / `mode`）—— 这里只是用户的意愿开关。
                    onCheckedChange = { on -> AppPrefs.setR1Fallback(on) },
                )
            }
            // ★ 前台门的**总开关**（2026-09-28 新增，用户报「该转的时候不转」）。
            //   门控是全工程唯一"主动放弃干活"的机制，名单一旦少勾/多勾，
            //   症状就是"没反应"。这个开关就是留给用户的逃生阀。
            //
            // ⚠️ 标题在判据换代（读声明朝向 → 应用白名单）时改过口径：现在它管的是
            //   **名单**，而不是"这个应用自己声明了什么"。措辞必须跟着走 ——
            //   否则用户会按"游戏/视频"去理解，而实际判据完全是另一回事。
            SwitchPreference(
                title = stringResource(R.string.rotation_gate_title),
                // ⚠️ 2026-10-01 精简：原文三段里只有"怎么用"对用户有用（移出名单 / 关开关），
                //   "不开摄像头、不占性能"是我们内部实现的说法 ⇒ 砍掉。
                // ⚠️ 2026-10-02 再精简：砍掉"玩游戏时不抢资源"的括号（它只是标题的注脚，
                //   而"不弹按钮、不改方向"已经把结果说全了）。
                summary = stringResource(
                    if (gateEnabled) R.string.rotation_gate_on else R.string.rotation_gate_off,
                ),
                checked = gateEnabled,
                onCheckedChange = { on -> AppPrefs.setGateEnabled(on) },
            )
            // ★ 前台门控的"交还"开关（用户 2026-09-28 点名要的）。
            //   两种取向都合理：交还 = 不留下"谁都管不着"的真空；不交还 = 把方向锁在当前角度。
            //   ⚠️ 只在门控开着时才有意义 —— 门控关了就不会"停手"，也就无所谓交还。
            if (gateEnabled) {
                SwitchPreference(
                    title = stringResource(R.string.rotation_handoff_title),
                    summary = stringResource(
                        if (handoffRotate) R.string.rotation_handoff_on
                        else R.string.rotation_handoff_off,
                    ),
                    checked = handoffRotate,
                    onCheckedChange = { on -> AppPrefs.setHandoffRotate(on) },
                )
                // ⚠️ 这里原来是一个「管理应用白名单」的 [ArrowPreference] 入口行
                //   （2026-09-28 加，2026-09-29 从页内文字按钮改成箭头形），**2026-10-01 已删除**：
                //   名单不再是另一个二级页，它的整块内容铺在本页末尾（见 ⑦ 与类注释）。
                //   ⛔ 别再加回一个"跳到名单页"的入口 —— `Route.Apps` 已经不存在了。
                //   ⚠️ 也**不要**因为"名字里带名单"就把上面的总开关一起挪走：
                //     总开关改的是**行为**（引擎做不做），明细列表改的是**范围**，两者分居 ② 和 ⑦。
            }
            // ★ 半自动按钮的**等待时长**（2026-09-28 用户点名要的滑条，原话：
            //   「添加自定义的时间滑条，让用户自行决定旋转按钮的消失时间，
            //     最少 1s，最多 60s」）。
            //
            // ★ 为什么放在"开关"卡片里、而不是跟着半自动档的选项走：它只在半自动档
            //   有意义，但界面不该让一个设置项随模式**忽隐忽现** —— 用户是先在安稳的
            //   地方把它配好，再切到半自动去用的。
            //
            // ⚠️ 它和引擎里那条「同一方向抑制期」是**同一个值**（见
            //   `semiRepeatSuppressMs`）：改这里会同时改掉"同方向多久
            //   才能再弹一次"。两者必须相等，否则按钮会一直"续命"或"叫不出来"。
            //
            // ★ 刻意**不传 `steps`**：滑条的值一直是从 `hintMs`（受控状态）读回来的，
            //   而写入时 `AppPrefs.setHintMs` 会**夹紧并对齐整秒**（见 `AppPrefs.snapHintMs`）
            //   ⇒ 滑块位置天然吸附到整秒，界面上的「N 秒」与实际等待时长逐字一致。
            //   这样就不必再依赖 `steps` 那套"中间刻度数"的语义（少一个可能理解错的参数）。
            //
            // ⚠️ 实测记录（2026-09-28）：滑条**只响应拖动，不响应点按定位**
            //   （点一下不动，必须按住拖；用 `adb shell input swipe x1 y x2 y` 验证过）。
            //   所以别把"点一下就设成某值"当成它的能力。
            SliderPreference(
                value = hintMs / 1000f,
                onValueChange = { AppPrefs.setHintMs((it * 1000f).roundToInt()) },
                title = stringResource(R.string.rotation_hint_title),
                summary = stringResource(R.string.rotation_hint_summary, hintMs / 1000),
                valueText = stringResource(R.string.rotation_hint_value, hintMs / 1000),
                valueRange = (AppPrefs.HINT_MS_MIN / 1000f)..(AppPrefs.HINT_MS_MAX / 1000f),
            )
        }

        // ---------------------------------------------------- 使用提醒（注视感知）
        //
        // ★ 这是一条**静态提醒**，不做开关探测 —— 用户 2026-09-25 明确要求
        //   「不要检测了，直接提示」。理由也是对的：MIUI 各版本这个开关落在哪个设置键上
        //   并不稳定（已实证存在的 AON 相关键有三个：system 的 miui_aon_scanner、
        //   secure 的 miui_aon_up_down_waving，以及 Settings 里引用的 miui_aon_perception），
        //   与其按可能过期的键名去猜，不如把**原因**说清楚，让用户自己判断。
        //
        // ★ 为什么确实会冲突（2026-09-25 补齐硬证据，全部来自 `dumpsys media.camera`）：
        //   抢我们的是 `com.miui.aoc` —— 小米 AON（注视感知 / 智能扫码）那条链上的
        //   常开取像进程，进程名 `/odm/bin/hw/misensor_camera`，以 `camera ID 0` 常驻。
        //   本机同时只能开一路前摄，于是：
        //   ```
        //   REJECT device 1 client for com.android.systemui: Too many cameras already open, cannot open camera "1"
        //   DENIED connect device 1 (PID 12358, score 1001 state 6) due to eviction policy
        //    - Blocked by existing device 0 client for package com.miui.aoc (PID 19801, score 200, state 1)
        //   ```
        //   ⚠️ **"什么时候"被拒**（2026-10-04 更正归因）：曾记为"App 退到后台 / 被从最近任务
        //      划掉之后 AON 就起来"——那是**时间上的相关，不是因果**。同日归档里的 force-stop
        //      实证反证了它：App 被强杀后引擎照常活着（心跳推进、`takeover=1` 保留），
        //      而抢前摄的是 `com.miui.aoc`。触发 AON 的是**设备闲着 / 熄屏**这类条件，
        //      不是"本应用还在不在最近任务里"。
        //      ⛔ 别据此写出"别把本应用划掉"这类祈使句 —— 用户 2026-10-04 明确指出
        //         "模块跑在 SystemUI 里、不需要后台常驻"，那种说法与之直接冲突，
        //         而且会给出一个**没用**的动作。要讲就讲第一段那个真动作（关掉注视感知）。
        //
        //   ★ 本应用对此的处理是**让位 + 等它松手再补采**（不硬抢），
        //     被让位的次数会如实记在「诊断」页的「相机冲突」里 —— 那是判断"到底有没有在抢"的直接证据。
        if (mode == RotateMode.ADAPTIVE) {
            TextCard(title = stringResource(R.string.rotation_notice_title)) {
                // ⚠️ 2026-10-01 精简：原来这里是**两段共五行**，把"谁抢谁、我们怎么让位、
                //   AON 是哪个进程"全讲了一遍 —— 那些是**我们的实现**，用户只关心两件事：
                //   方向为什么会跟不上（原因）+ 我该怎么办（动作）。
                // ⚠️ 2026-10-02 再精简：两段 → 两件事各留一句，第一段带动作。
                // ⚠️ 2026-10-04：第二段换了内容 —— 原来是「别把本应用从最近任务里划掉」
                //   （见上面那段归因更正）。现在它是**打消顾虑**的一句：「本应用不用一直开着」。
                //   下面那个按钮仍然只服务第一段（打开系统设置去关注视感知）。
                Text(
                    text = stringResource(R.string.rotation_notice_body),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.paragraph,
                )
                TextButton(
                    text = stringResource(R.string.rotation_notice_button),
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        // ---------------------------------------------------- 预览旋转按钮（2026-10-06 **整段删除**）
        //
        // 🔴 用户原话：「**去掉旋转增强的预览旋转按钮**」⇒ 整段删除（含入口与三处文案调用）。
        //
        // ★ 它本来就是**验证工具**，不是用户设置：半自动的旋转按钮只在"传感器判定设备姿态
        //   ≠ 屏幕方向"时弹出，而传感器没法用 adb 注入 ⇒ 当时只能靠这个按钮"叫它出来一次"
        //   看外观（链路见 [PrefsBridge.HINT_TEST]）。功能稳定之后它就没用了，
        //   而它和「模式 / 方向」并列摆在正式设置页里，本身就是"乱"的一部分 ——
        //   读者无法判断这一条到底该不该动。
        //
        // ⛔ 别加回来（已否一次）。
        // ⚠️ `AppPrefs.requestHintTest()` 与引擎侧 `PrefsBridge.HINT_TEST` 那条链**保留不动**：
        //   删界面入口不必连底层链路一起拆，而拆它要动引擎 —— 本轮只改 App 侧 UI。
        //   （`requestHintTest` 因此暂时没有界面调用点，这是**有意**留下的，不是漏删。）

        // ---------------------------------------------------- 应用名单（**一行入口**，2026-10-06 改）
        //
        // ★★ 2026-10-01 用户原话是「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」
        //   ⇒ 那时整块内容（`AppWhitelistSection()`）铺在这里。
        //   2026-10-06 用户改口径：「**还是很乱，可以适当增加三级菜单，要让所有功能都清晰明了**」
        //   —— 名单是一两百行的巨型列表，铺在页内会把上面所有设置推出几屏外。
        //   ⇒ 现在这里只有**一行入口**，内容搬进三级页 [Route.RotationApps]。
        //   完整理由（以及"为什么这不是把那半句口径推翻"）见类注释那段"两进两出"。
        //
        // ★ 它固定在**整页最末**：作用对象（"对谁生效"）永远排在设置之后 ——
        //   这是骨架 R5 的第 ⑤ 段。⛔ 别因为"就剩一行了"就把它往上提。
        //
        // ⚠️ 摘要**不给数字**（不写"已设置 N 个"）：那个数要从名单与逐应用档两处合起来算，
        //   而"两处各算一遍"正是本工程反复踩过的坑（判据只允许有一份）。宁可少一个数字。
        SectionCard(title = stringResource(R.string.wl_entry_section)) {
            ArrowPreference(
                title = stringResource(R.string.wl_page_title),
                summary = stringResource(R.string.wl_entry_summary),
                onClick = { onOpen(Route.RotationApps) },
            )
        }
    }
}

/**
 * 模式的单选（**只有内屏一份**，2026-09-29 起）。
 *
 * ★ 三档的说明文案由 [modeSummary] 统一给（不在这里写死）：文案必须只有一处在维护。
 *
 * ⚠️ 点击回调是 `AppPrefs.setMode(mode)` —— 旧签名带 `form`（内外屏各一份）。
 *   外屏增强删掉之后只可能有一份，形参也随之去掉：界面**不可能**再"配一份用不上的
 *   外屏模式"（这正是想要的）。
 *
 * ============================ 自适应是实验功能（2026-10-03）============================
 *
 * 用户原话：「打开开关之后，旋转增强里面才显示自适应旋转的选项」
 * ⇒ [showAdaptive] 为 false 时**不列出** `ADAPTIVE` 那一档。
 *
 * ⚠️ **但"当前正在用的那一档"永远列出**（下面那个 `m == value` 或条件）—— 这条不是装饰：
 *   - 关掉开关**不会**替用户改配置（见 [AppPrefs.setExperimentalAdaptive]）⇒
 *     "开关关着、当前档是自适应"是个**合法状态**，本机装完这一版立刻就是它；
 *   - 若这时把它从列表里藏掉，三档单选里会**一个都不选中** —— 用户看到的是
 *     "我明明在用某个模式，界面却说我没选"，比多显示一行糟得多。
 *   ⇒ 实际行为是：**没在用的人看不见它，正在用的人仍然看得见**。
 */
@Composable
private fun ModePicker(value: RotateMode, showAdaptive: Boolean) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        // 顺序：跟随系统 → 自适应 → 半自动（与 QS Tile 的三态循环顺序一致，
        // 用户在控制中心连点三下看到的顺序，和这里读到的顺序相同）
        // ⚠️ 下面只过滤"列不列出来"，**顺序一个字没动**。
        listOf(RotateMode.SYSTEM, RotateMode.ADAPTIVE, RotateMode.SEMI)
            .filter { m ->
                // 非自适应档恒列出；自适应档在"开关已打开"或"当前正是它"时才列出
                m != RotateMode.ADAPTIVE || showAdaptive || m == value
            }
            .forEach { m ->
                RadioButtonPreference(
                    // ⚠️ 用 `labelRes` 而不是 `label`：后者是**日志口径**的中文，
                    //   拿它显示会让界面锁死在中文（见 `AppPrefs.RotateMode.label` 的注释）。
                    title = stringResource(m.labelRes),
                    summary = modeSummary(ctx, m),
                    selected = value == m,
                    onClick = { AppPrefs.setMode(m) },
                )
            }
    }
}

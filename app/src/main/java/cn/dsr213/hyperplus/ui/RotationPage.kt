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
import cn.dsr213.hyperplus.CaptureStrategy
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import cn.dsr213.hyperplus.ScreenForm
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
 * 内容：
 *   ① 模式        —— 内屏一份三态单选
 *   ② 开关        —— 省电优先 / 名单门控 / 停手交还 / 按钮等待时长
 *   ③ 使用提醒    —— 只在"当前这块屏"是自适应档时出现
 *   ④ 预览按钮    —— 叫悬浮按钮出来一次（传感器没法用电脑触发）
 *   ⑤ 方向        —— 一张卡（[DirectionSection]，2026-10-04）：**展开后的方向**，四选一，
 *                      每项配一部手机小图标（借 root 直写内屏槽位）。
 *                      ⚠️ 它下面原来还有一块「校准」，同日**整块删除** —— 改由引擎拿重力全自动。
 *   ⑦ 应用名单    —— 整块内容（[AppWhitelistSection]），见下面那条 2026-10-01 的说明
 *
 * ============================ 2026-10-01：应用名单并进来了 ============================
 * 用户原话：「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」。
 * ⇒ `Route.Apps` 已删除，那一页的内容变成这里的 ⑦（[AppWhitelistSection]，一个 `internal` 函数）。
 *   "名单里的应用不干预"那个总开关仍在 ② 里 —— 它是**行为开关**，
 *   而 ⑦ 是**它作用的明细**，两者挨得近正是合并的收益。
 *
 * ⚠️ ⑦ 放在**整页最末尾**，不是紧跟那个开关：名单里的 A-Z 列表有一两百个应用（实测），
 *   插在中间会把 ③④⑤ 全部推进屏幕外几千像素，等于把它们埋了。
 *   放末尾时"旋转设置"仍在页面顶部一眼可见，名单作为明细垫底。
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
    val showAdaptive by AppPrefs.experimentalAdaptive.collectAsState()
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
        // ⚠️ 2026-10-02：这里原来还有一张「你现在在外屏」说明卡，已搬到页面**最顶部**
        //   并升级成红条（[OuterScreenBanner]）。⛔ 别把说明卡加回来 —— 两处说同一件事，
        //   而且中段那张会被当成普通说明扫过去。

        // ---------------------------------------------------- 开关
        SectionCard(title = stringResource(R.string.rotation_section_switches)) {
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

        // ---------------------------------------------------- 预览按钮
        //
        // ★ 为什么要给用户这个按钮：半自动的旋转按钮只在"传感器判定设备姿态 ≠ 屏幕方向"
        //   时弹出，而**传感器没法用 adb 注入**（装机验证时最痛的一点）——
        //   想知道按钮长什么样、在哪、多大，只能靠人把手机转一下。
        //   ⇒ 给一条"叫它出来一次"的路径（链路见 [PrefsBridge.HINT_TEST]）。
        // ⚠️ 它**只影响外观验证**：弹出来的按钮点下去走的仍是真实路径（写方向 + 读回），
        //   所以这不是"绕过传感器"的入口，也不能用来测"这个应用能不能转"。
        TextCard(title = stringResource(R.string.rotation_preview_title)) {
            // ⚠️ 2026-10-01 精简：删掉"传感器没法用电脑触发"（那是我们验证时的不便，
            //   跟用户无关）与"停留时长就是上面的设置"（上面就是那一条，不必再说一次）。
            // ⚠️ 2026-10-02 再精简：三句 → 两句，"位置和平时一样"并进第一句。
            Text(
                text = stringResource(R.string.rotation_preview_body),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
            )
            TextButton(
                text = stringResource(R.string.rotation_preview_button),
                onClick = { AppPrefs.requestHintTest() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        // ---------------------------------------------------- 方向（2026-10-04 合并重做 + 删校准）
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
        // ⚠️ 位置：紧跟「开关」、在名单之前 —— 名单有一两百行，只能待在整页末尾。
        // ⚠️ 原来内联在这里的 `LiveAngleSection()`（角度盘 + 原始读数行）也一并退役：
        //   它本来就是给校准看的，校准没了它就没有存在的理由。
        //   ⛔ 别再把任何"实时角度显示"加回来。
        DirectionSection()

        // ---------------------------------------------------- 应用名单（2026-10-01 合并进来）
        //
        // ★★ 用户原话：「把应用名单合并进旋转增强页，不再是一个独立的二级菜单」。
        //   它原来是 `Route.Apps` 这另一个二级页，入口挂在上面 ② 的开关里。
        //   ⇒ 现在整块内容直接铺在这里，不再有中间那一跳；`Route.Apps` 连同它的顶栏一起删了。
        //
        // ⚠️ 为什么放在**整页最末尾**、而不是紧跟②那个"名单里的应用不干预"开关：
        //   名单里的 A-Z 列表有一两百个应用，插在中间会把 ③④⑤ 全部推进屏幕外几千像素。
        //   完整理由见类注释 ⑦ 那段，别在这里重抄。
        //
        // ⚠️ 它内部会发射一长串「小标题 + 卡片」兄弟节点，所以调用点必须在**一个 Column 里** ——
        //   这里正是 [SubPage] 的内容列，满足。⛔ 别把它挪进 Card / Row / Box。
        AppWhitelistSection()
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

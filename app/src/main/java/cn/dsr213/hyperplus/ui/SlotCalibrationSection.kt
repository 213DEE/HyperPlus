package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RootShell
import cn.dsr213.hyperplus.ScreenForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「默认方向」块（2026-10-03）。
 *
 * ============================ 它是干什么的 ============================
 * 用户原话：「**提供校准入口，直接修改默认槽位的值**」。
 * ⇒ 这一块只做一件事：把用户选的方向**直接写进内屏的方向槽位**
 *   （`user_rotation_inner`）—— 它才是"展开时屏幕会朝哪边"的真正决定量。
 * 把它写对之后，**框架展开时自己就灌对了**：模块零写入、用户零旋转。
 *
 * ============================ 写这条路走的是 root ============================
 * ★★ 2026-10-03 **第二版**：改由 **App 借 root 直写**（[RootShell]），
 *   不再"写个请求让引擎代做"。
 *
 *   为什么改 —— 用户拍板（原话：「**能装上模块的手机一定有 Root，可以通过获取 root
 *   来修改**」）。第一版（引擎代写）在真机上**点了没生效**：请求确实落了盘，槽位却没变
 *   （详见当日文档）。原因在"投递"这一跳，而 root 直写把这一跳整个去掉：
 *   点一下 = 一条 `settings put`，成败当场可见，也**不依赖引擎是否在跑**。
 *
 *   ⚠️ 代价：首次点击会弹一次 root 授权（KernelSU / Magisk）。拒绝 ⇒ 写入失败 ⇒
 *     下面的 [failed] 那段话会出来，界面**不会**假装成功。
 *
 * ★★ 2026-10-03 **补一行小字标注"需要 root"**（用户要求：原话「**这个功能是不是需要
 *   root？如果需要 root，在功能卡片上小字标注一下**」）。
 *   为什么**必须**标：写 `Settings.System` 的非公开键，对普通应用是**权限上做不到**的事
 *   —— 项目自己的实证记录写着 `WRITE_SETTINGS` 与 `WRITE_SECURE_SETTINGS` 都无效
 *   （AOSP 那道 `enforceRestrictedSystemSettingsMutationForCallingPackage` 无条件执行）。
 *   不写出来，用户只会看到"点了没反应"，而这正是他被坑过一轮的那个观感。
 *   ★ 当天稍后又按用户要求加强为**红字 + Medium 字重**（原话「**不够显眼，改红色强调字体**」），
 *     字号仍保持小字 —— 样式细节见 [ROOT_NOTE] 的 KDoc。
 *   ⚠️ 只标在**能选**的情形下（见 [ROOT_NOTE] 附近的判断）：不支持该键是另一回事，
 *     两者叠在一起会自相矛盾。
 *
 * ============================ ⚠️ 它不是被删掉的那一版 ============================
 * 同名的功能 2026-10-03 被删过一次，别再混：
 *   · 被删的那版 = 把方向**存在本应用的配置里**，展开后 800ms 再去比对、纠正
 *     ⇒ 按工作原理**必然**产生一次可见转动，正是用户否掉的"自己转一下"；
 *   · 这一版 = 改的是**源头**（系统槽位本身）⇒ 展开时框架自己灌对 ⇒ 零转动。
 * 前者的病根是"碰不动源头，只能事后补救"；这一版就是把源头改掉。
 * ⛔ 别因为界面上都是"选四个方向"就以为回到了老路。
 *
 * ============================ 为什么必须由用户来选 ============================
 * "展开时用户想要哪个方向"这件事，外面**没有任何一个量**能告诉我们 ——
 * v4～v8 试过四种外部读数（旧屏的方向 / 展开瞬间的握姿 / 槽位 / 延迟后再读姿态），
 * 四版全错。⇒ 唯一可靠的来源就是"用户指着一个方向说：就是这个"。
 *
 * ============================ 只做内屏 ============================
 * 展开之后用的是内屏，用户关心的也是它。外屏那块槽位在本模块里恒为"跟随系统"档、
 * 模块不介入 ⇒ 暂不给入口（真要加，写法与这里完全一样，只是换个 [ScreenForm]）。
 *
 * ============================ 界面上显示的是"事实" ============================
 * 选中态来自 [AppPrefs.readSlot] 的**真实读回**（每秒一次，离开本页自动停），
 * 而不是"用户点过什么"。⇒ 这个 ROM 没有该键、或 root 被拒时，用户看到的就是
 * "点了没变化" —— 这是**对的**，界面不能假装成功（10-03 已经因为"设置了却看不出
 * 生效"被投诉过一轮）。
 *
 * ★★ 还有一条与它配套的改动（同一轮做的）：**模块从此不再自动写任何方向槽位**
 *   （引擎侧那条"让本屏槽位跟上当前方向"的巡检整个删掉了）。否则用户刚设的值
 *   会被下一次巡检 / 下一次自动转屏覆盖回来 —— 那又是"设置了却没生效"。
 *   ⇒ 槽位现在的写入者只有两个：**用户点这里**，和**框架自己**（用户手动转屏时）。
 *
 * ⚠️ 文案纪律（2026-10-03 定）：**只讲现象和后果，不讲机制**；不口语化；
 *   ⛔ 里面不许出现 markdown 星号（真机会原样显示）。
 */
@Composable
internal fun SlotCalibrationSection() {
    val ctx = LocalContext.current

    // ★ 读的是**系统里的真实值**，不是本地记忆。`null` 有两层意思，靠 `loaded` 分开：
    //   · 还没读回来（首帧）—— 不能直接说"不支持"，那会闪一下假话；
    //   · 真读不到（非 HyperOS / 键不存在）。
    var slot by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // ★ 写入结果。只在**失败**时说话（成功时界面自己就变了，不需要多一句）。
    var failed by remember { mutableStateOf(false) }
    // ★ 正在写：挡住连点。root 那条命令是异步的，几百毫秒内连点会起好几个 su。
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // ★ 每秒读一次：写完之后界面能自己跟上（不必等用户重进这一页）。
    //   ⚠️ 放在 LaunchedEffect(Unit) 里 ⇒ 离开本页时协程自动取消，不会在后台空转。
    LaunchedEffect(Unit) {
        while (true) {
            slot = AppPrefs.readSlot(ScreenForm.INNER)
            loaded = true
            delay(1000)
        }
    }

    SmallTitle(text = stringResource(R.string.slot_title))

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.slot_desc),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.paragraph,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        )
        // ★★ 小字标注「需要 root」（理由见文件头 KDoc）。
        //   ⚠️ 只在**真能选**的时候显示 —— 下面那句「这台设备的系统不支持方向设置」说的是
        //     "这个键不存在"，与"权限不够"是两件事，叠在一起会自相矛盾。
        //
        //   ★ 写法纪律（项目既有）：**说人话、一句话、只讲"我该怎么办"**；讲现象与后果，
        //     ⛔ 不讲机制（不解释"非公开键"/"uid"那些）；⛔ 里面不许出现 markdown 星号。
        //     ⇒ 前半句给**前提**（需要什么），后半句给**用户会遇到的事**（会弹窗，别当成出错）。
        //   ⚠️ 它与下面 [failed] 那句「未能修改…」**不是重复**：那句是**失败之后**的解释，
        //     这句是**动手之前**的预告 —— 没有它，用户点第一下时突然弹出一个系统弹窗
        //     会以为出了什么事。
        //   ⚠️ 2026-10-03 多语言：原先是 `private const val ROOT_NOTE`（硬编码中文），
        //     现在住 `res/values` 系列三套 `strings.xml` 的 `slot_root_note`；**样式不动**
        //     （红字 + Medium 字重 + 小字号，用户当天追加要求的就是"显眼但不抢层级"）。
        if (loaded && slot != null) {
            Text(
                text = stringResource(R.string.slot_root_note),
                // ★★ 2026-10-03 改**红字强调**（用户原话：「**不够显眼，改红色强调字体**」）。
                //   · `color = error`（红）＋ `fontWeight = Medium` —— 与 [OuterScreenBanner]
                //     同一套强调写法（那里也是 `error` 红 + Medium），别在这里自创颜色；
                //   · ⚠️ 用 `colorScheme.error` 而**不是**写死 `#FF…`：深浅色模式各有一套值，
                //     写死到深色模式上就糊了；
                //   · ⚠️ **字号仍是 `footnote2`**（用户最初的要求就是"小字标注"）——
                //     显眼靠"颜色 + 字重"，不靠把字号顶上去：顶上去就与上面那句说明句抢层级了。
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
            )
        }
        when {
            // 首帧：只显示上面那句说明，等读回来再铺选项（避免闪一句"不支持"）
            !loaded -> Unit
            slot == null -> Text(
                text = stringResource(R.string.slot_unsupported),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            )
            else -> UNFOLD_CHOICES.forEach { v ->
                RadioButtonPreference(
                    title = rotName(ctx, v),
                    selected = slot == v,
                    onClick = {
                        if (!busy) {
                            busy = true
                            failed = false
                            scope.launch {
                                val ok = RootShell.putSystemInt(
                                    PrefsBridge.KEY_USER_ROTATION_PREFIX + ScreenForm.INNER.storageKey,
                                    v,
                                )
                                // ★ 不管成没成，都**重新读回**再上屏 —— 显示的是事实。
                                slot = AppPrefs.readSlot(ScreenForm.INNER)
                                failed = !ok
                                busy = false
                            }
                        }
                    },
                )
            }
        }
        if (failed) {
            Text(
                text = stringResource(R.string.slot_failed),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            )
        }
    }
}

/**
 * 可选的四个方向，**顺序刻意不是按值排**：两块横屏挨着（用户最常选的就是它们，
 * 一眼可比），竖屏开头、倒屏收尾。
 *
 * ⚠️ 值直接就是 `USER_ROTATION` 的取值，与 [rotName] **同一套编号、不需要任何换算**
 *   （见 [SlotCalibrationSection] 的说明）—— ⛔ 别在这里加 ±180 之类的偏置。
 */
private val UNFOLD_CHOICES = listOf(0, 1, 3, 2)

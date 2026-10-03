package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**实验功能**（2026-10-03 新增，入口在「功能」主页**最底下**）。
 *
 * 用户原话：「在首页新增一个"实验功能"置底，里面添加"自适应旋转"开关，打开开关之后，
 *   旋转增强里面才显示自适应旋转的选项，并说明打开开关之后，需要到「旋转增强-模式」切换；
 *   另外在"实验功能"二级菜单**置顶红标**提示"以下功能仍处于实验阶段，效果可能极度不稳定，
 *   请谨慎使用"」。
 *
 * ============================ 这一页的定位 ============================
 *
 * 它只放**一件事：某个功能要不要出现在界面上**（可见性）。
 * ⛔ 功能**自己的参数**（模式、按钮时长、名单……）一律留在 [RotationPage] ——
 *   把参数搬到这里会让"改一个设置要跑两个页面"，而这一页以后还会不断变长
 *   （每多一个实验功能就多一个开关）。
 *
 * ============================ 三个刻意为之 ============================
 *
 * ① **红标是不可省略的**：用户点名要它置顶，而且措辞是他给的**原话**
 *    （见 `R.string.experimental_warning`）。这一页里的一切都可能出问题，
 *    用户必须在**动手之前**看到这句话 —— 所以它在**所有内容之上**，与
 *    [OuterScreenBanner] 在旋转页的位置同理。
 *
 * ② **开关只改可见性，不改现有选择**：打开它**不会**自动切到自适应，关闭它**也不会**
 *    把正在用自适应的人踢走（本机当前档就是自适应）。理由写在
 *    `AppPrefs.setExperimentalAdaptive` 的注释里 —— ⛔ 别在这里"顺手"加上联动。
 *
 * ③ **说明卡讲的是后果、不是机制**：用户想知道的是"我关了它，我现在的设置会不会变"，
 *    不是"这个布尔值参与哪些判断"。
 */
@Composable
internal fun ExperimentalPage(
    onBack: () -> Unit,
) {
    val adaptive by AppPrefs.experimentalAdaptive.collectAsState()

    SubPage(title = stringResource(R.string.experimental_title), onBack = onBack) {
        // ---------------------------------------------------- 置顶红标（用户点名）
        ExperimentalBanner(stringResource(R.string.experimental_warning))

        // ---------------------------------------------------- 开关
        SectionCard {
            SwitchPreference(
                title = stringResource(R.string.experimental_adaptive_title),
                // ★ 这一行就是用户要求的"说明打开开关之后，需要到「旋转增强-模式」切换"。
                //   放在**开关的摘要**里而不是另起一张卡：它是这个开关的**下一步动作**，
                //   离开关越近越不会被跳过。
                summary = stringResource(R.string.experimental_adaptive_summary),
                checked = adaptive,
                onCheckedChange = { AppPrefs.setExperimentalAdaptive(it) },
            )
        }

        // ---------------------------------------------------- 说明（讲后果）
        TextCard {
            Note(stringResource(R.string.experimental_adaptive_note))
        }
    }
}

/**
 * 实验功能的**红色警示条**（置顶）。
 *
 * 与 [OuterScreenBanner] 的差别只有一处：**没有标题行** —— 那句警告本身就是一整句话，
 * 再配一个"注意"之类的标题只会稀释它。
 * 配色刻意与红条保持一致（`errorContainer` 底 + `error` 字），
 * 让用户一眼把"实验功能"和"当前屏幕不生效"识别成**同一等级的提醒**。
 *
 * ⚠️ 单独开一个私有函数而不是直接写在页面里：它的内边距 / 字号 / 颜色是**一组搭配**，
 *   拆散写在调用处的话，下次改配色就会改漏其中一处。
 */
@Composable
private fun ExperimentalBanner(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = text,
            // ⚠️ 与红条同样用 `error` 而不是 `onErrorContainer`：浅色模式下后者偏深棕，
            //   要的是"红字"的观感（那个取舍记在 [OuterScreenBanner]）。
            color = MiuixTheme.colorScheme.error,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

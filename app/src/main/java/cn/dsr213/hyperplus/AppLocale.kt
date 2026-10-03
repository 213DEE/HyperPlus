package cn.dsr213.hyperplus

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 界面语言（**只有四档，且这是"用户选了哪一档"，不是"实际生效的语言"**）。
 *
 * ★ 用户 2026-10-03 原话：「可选「跟随系统、简体中文、繁体中文、英文」，
 *   **如果系统文字不是以上三种，默认英文**」。
 *
 * ⚠️ 与 [RotateMode] 一样：**落盘的是枚举名以外的稳定串**（[storageKey]），
 *   ⛔ 别直接写 `name`（将来改枚举名会读不回来）。
 *   枚举名叫什么不许乱改的理由见 `AppPrefs` 里关于落盘值的那条纪律。
 */
internal enum class UiLang(
    /** 落进 prefs 的值。⛔ 已发布的值不许改（改了老用户的选择会读不回来、退回跟随系统） */
    val storageKey: String,
) {
    SYSTEM("system"),
    ZH_HANS("zh_hans"),
    ZH_HANT("zh_hant"),
    EN("en"),
    ;

    companion object {
        fun fromStorage(v: String?): UiLang = entries.firstOrNull { it.storageKey == v } ?: SYSTEM
    }
}

/**
 * 「界面语言」的解析与套用 —— **全工程唯一一处**。
 *
 * ============================ 它为什么独立于 AppPrefs ============================
 * ★ 这一项**只有 App 界面关心**，引擎（SystemUI 进程）一个字都不需要读它。
 *   所以它**不放进配置通道那个文件**（`facerotate_prefs`）：那个文件的内容会被**整份**
 *   推给引擎（广播快照，见 `ConfigChannel`），引擎还会把它落进谁都能读的
 *   `Settings.System` 镜像 —— 往里塞一个引擎永远不读的键，等于每次改语言都白跑一趟传输，
 *   还顺手把语言偏好写进了一个别的应用读得到的公共位置。
 *   ⇒ 单独一个 `hyperplus_ui` 文件、`MODE_PRIVATE`（**不需要** WORLD_READABLE：
 *     没有任何别的进程要读它）。⛔ 别把它并回 `facerotate_prefs`。
 *   ⚠️ 2026-10-03 更正：这里原先写的理由是"那个文件是引擎**实时监听**的跨进程契约"——
 *     引擎改走广播 + 镜像之后"监听文件"这件事已经不存在了，但**结论不变**（理由见上）。
 *
 * ============================ 「跟随系统」到底是什么意思 ============================
 * ★ 它不是"不干预、交给系统"（那会掉进"系统是日语 ⇒ 显示英语还是中文"的模糊地带），
 *   而是**在这里把系统语言归到那三档之一**，然后当成一个明确的选择去套用：
 *
 * | 系统语言 | 生效 |
 * |---|---|
 * | `zh` + 繁体标记（`Hant` 脚本，或地区 TW / HK / MO） | 繁体中文 |
 * | `zh` + 其余地区（CN / SG / …） | 简体中文 |
 * | `en` | 英文 |
 * | **其它任何语言** | **英文** ← 用户点名的那条规则 |
 *
 * ★ 为什么一定要**归一成具体 Locale**、而不是"跟随系统就什么都不做"：
 *   资源只准备了三套（`values` 英文 / `values-zh-rCN` / `values-zh-rTW`），
 *   系统若是日语，交给框架去匹配同样会落到英文；但系统若是 `zh-HK`，
 *   框架匹配**未必**认得它属于繁体 —— 归一之后这件事不再靠框架的心情。
 */
internal object AppLocale {

    /** 界面语言自己的 prefs 文件（与引擎无关，见类注释） */
    private const val PREFS = "hyperplus_ui"
    private const val KEY = "ui_lang"

    private val _lang = MutableStateFlow(UiLang.SYSTEM)

    /** 当前选择（界面订阅它显示选中态） */
    val lang: StateFlow<UiLang> = _lang.asStateFlow()

    private var prefs: SharedPreferences? = null

    /**
     * 【**必须在任何界面之前调用一次**】把用户的选择读进内存。
     *
     * ★ 它**不负责"把语言套到界面上"** —— 那件事发生在 `MainActivity.attachBaseContext`
     *   （比 `onCreate` 更早），走的是 [read]。两处读的**同一个文件、同一个键**，
     *   只是那一处拿不到已经初始化好的对象（那时 Activity 还没构造完）。
     */
    fun init(ctx: Context) {
        prefs = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        _lang.value = read(ctx)
    }

    /** 用户改了语言。⚠️ 落盘之后**界面要重建**才生效，调用方负责（见 `LanguagePage`）。 */
    fun set(ctx: Context, v: UiLang) {
        if (_lang.value == v) return
        _lang.value = v
        runCatching {
            (prefs ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                .edit().putString(KEY, v.storageKey).apply()
        }
    }

    /** 读用户的选择。**任何一步出问题都退回 [UiLang.SYSTEM]**（宁可跟着系统，也不要卡在半个语言上） */
    fun read(ctx: Context): UiLang = runCatching {
        val p = prefs ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        UiLang.fromStorage(p.getString(KEY, null))
    }.getOrDefault(UiLang.SYSTEM)

    /**
     * 系统语言 → 那三档之一（规则见类注释的表格）。
     *
     * ⚠️ 判繁体**先看脚本、再看地区**：`zh-Hant-CN`（少见但合法）脚本就是繁体，
 *   而 `zh-HK` 报的脚本常常是空的、只能靠地区。两条件都要有。
     */
    fun fromSystem(loc: Locale): Locale {
        if (!loc.language.equals("zh", ignoreCase = true)) return Locale.ENGLISH
        val script = loc.script
        val region = loc.country.uppercase(Locale.ROOT)
        val traditional = script.equals("Hant", ignoreCase = true) || region in TRADITIONAL_REGIONS
        return if (traditional) Locale.TRADITIONAL_CHINESE else Locale.SIMPLIFIED_CHINESE
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /** 用户的选择 → 一个**具体的** Locale（[UiLang.SYSTEM] 时先看系统语言，见 [fromSystem]） */
    fun resolve(system: Locale, v: UiLang): Locale = when (v) {
        UiLang.ZH_HANS -> Locale.SIMPLIFIED_CHINESE
        UiLang.ZH_HANT -> Locale.TRADITIONAL_CHINESE
        UiLang.EN -> Locale.ENGLISH
        UiLang.SYSTEM -> fromSystem(system)
    }

    /**
     * 取 **"系统语言"**（不是"当前界面上生效的语言"）。
     *
     * ⚠️ 这里有个很容易踩死的坑：**不能拿"已经套用过语言的 Context"去问系统语言**，
     *   否则会自我引用 —— 用户选了英文之后，Activity 重建时读回来的"系统语言"
     *   就成了英文，"跟随系统"于是永远回到英文，再也回不去。
     *
     * ★ 所以来源是 **[LocaleList.getDefault]**：本工程**从不**调用 `Locale.setDefault`
     *   （我们只对单个 Context 套 locale，见 [wrap]），所以这份默认值始终是
     *   系统设置里的语言，不会被我们自己污染。
     *   ⚠️ 兜底才用 `base.resources.configuration` —— 它是**应用级** configuration，
     *     在 `attachBaseContext` 那一刻通常等于系统语言，但它有被复用的风险，
     *     所以只当退路，不当主选。
     */
    private fun systemLocaleOf(base: Context): Locale =
        runCatching { LocaleList.getDefault()[0] }.getOrNull()
            ?: runCatching { base.resources.configuration.locales[0] }.getOrNull()
            ?: Locale.getDefault()

    /**
     * 把用户选的语言**套到 base 上**，返回一个带该 Locale 的新 Context。
     * 由 `MainActivity.attachBaseContext` 调用 —— 那是唯一比一切界面都早的时机。
     */
    fun wrap(base: Context, v: UiLang): Context = runCatching {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(resolve(systemLocaleOf(base), v))
        base.createConfigurationContext(cfg)
    }.getOrDefault(base)

    /**
     * 语言选项的显示名。
     *
     * ★ 这里有一个**必须分清的界线**（2026-10-03 修正过一次）：
     *
     *   - **「跟随系统」是一个"选项"，不是语言名** ⇒ 它**跟着界面语言翻译**：
     *     中文界面里显示「跟随系统」，英文界面里显示 "Follow system"。
     *     （最初把它也归进"不翻译"是错的 —— 那会让英文用户在一屏中文里找 "Follow system"。）
     *   - **三种语言名不翻译**：`简体中文` / `繁體中文` / `English` 在任何界面语言下
     *     都写成它自己的样子。理由是"语言选择器"的通行做法（系统设置里也是这样）：
     *     用户是在**认自己的母语名字**，翻译成 "Simplified Chinese" 反而要多跨一层。
     *
     * ⛔ 别为了"整齐"把三行语言名改成随界面语言变化。
     */
    @StringRes
    fun labelRes(v: UiLang): Int = when (v) {
        UiLang.SYSTEM -> R.string.lang_name_auto
        UiLang.ZH_HANS -> R.string.lang_name_zh_hans
        UiLang.ZH_HANT -> R.string.lang_name_zh_hant
        UiLang.EN -> R.string.lang_name_en
    }
}

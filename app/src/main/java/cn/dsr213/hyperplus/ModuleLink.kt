package cn.dsr213.hyperplus

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * App 侧与「跑在 SystemUI 里的引擎」打交道的那一层 —— **读状态 + 发标定请求**。
 *
 * ============================ 它现在不管什么了（2026-09-28 改造） ============================
 * 改造前它还负责"探活"，让 App 判断模块在不在、从而决定要不要起本地引擎。
 * 用户拍板删掉 App 侧引擎之后，这个判断再没有下游：
 *   - App 侧**不存在**引擎了（全设备只有 SystemUI 那一套）；
 *   - "引擎活着吗"直接看心跳（[State.hostAlive]）与 `phase=` 字段 —— 比一次握手更直接，
 *     而且**不需要 App 往总线写任何东西**（旧探活要写 `bus_ping`，那正是 root 依赖之一）。
 * 于是 ping/pong 整套协议被移除，本类只剩两条低权能力：
 *   ① 读宿主回传的状态摘要（**读**系统设置零门槛）；
 *   ② 发一个标定请求（写 App 自己的 prefs，零权限）。
 *
 * ★ 两条都不需要 root，也不需要 App 持有任何特殊权限。
 */
object ModuleLink {

    private const val TAG = "HyperPlusLink"

    /**
     * 宿主回传的状态摘要（[cn.dsr213.hyperplus.module.EngineHost.summary] 的解析结果）。
     *
     * ★ 格式是**跨进程契约**：只允许追加字段，不允许改已有字段名。
     */
    data class State(
        val phase: String,
        /**
         * ★ **启动熔断的连续失败次数**（2026-10-03 新增，只追加）。
         *
         * 只在 `phase == "halted"` 时有意义 —— 那时引擎已经**不再自动启动**
         * （理由见 `EngineTuning.BOOT_BREAKER_THRESHOLD`），界面据此如实说明"连续失败几次"。
         * ⚠️ 老格式摘要里没有这个字段 ⇒ 兜底 0；那不是"真的失败过 0 次"，
         *   只是老引擎报不出这个数（而老引擎也没有熔断机制）。
         */
        val breakerAttempts: Int,
        val mode: String,
        val takeover: Boolean,
        /** 引擎判定的目标方向（-1 = 未判定） */
        val rotation: Int,
        /** 系统当前实际显示方向 */
        val display: Int,
        val decider: String,
        val burstCount: Int,
        val frames: Long,
        val faces: Long,
        val switchCount: Int,
        /** 本轮票面，形如 `0:1 3:9`（可能为空串 = 本轮还没有票） */
        val voteCounts: String,
        /** 得票最多的方向（-1 = 本轮还没有票） */
        val voteWinner: Int,
        /** 有效票数 */
        val voteValid: Int,
        /** 本轮票数是否已足够定论 */
        val voteConfident: Boolean,
        /**
         * 重力给出的**参照扇区**（0..3）；-1 = 这一路当前不可用
         * （没注册到重力传感器 / 读数太旧 / 手机完全平放）。
         *
         * ⚠️ 它**不参与方向运算** —— 只当尺子，校验人脸链路的符号位。
         *   为什么不能把重力加到人脸角度上，见 [cn.dsr213.hyperplus.OrientationFusion] 类注释。
         */
        val gravitySector: Int,
        /** 符号位校验：「实测扇区 == 重力扇区」的分辨帧数（= 当前符号位是对的） */
        val signSame: Int,
        /** 符号位校验：「实测扇区 == 镜像(重力扇区)」的分辨帧数（= 当前符号位反了） */
        val signFlip: Int,
        /** 符号位是否已被**数据验证**过（而不是"用户点过校准按钮"） */
        val signConfirmed: Boolean,
        /** 被其他客户端抢走前摄的累计次数（实测抢我们的是小米的注视感知取像进程） */
        val conflictCount: Int,
        /**
         * 引擎**当前在用的前摄 id**（"1" / "5" / …）。
         *
         * ★ 本机有 5 个前摄，引擎会在它们之间轮换（见 `AdaptiveEngine.frontIdOrder`）。
         *   界面上必须能看到"现在用的是哪一个" —— 否则轮换等于不可观测，
         *   出问题只能翻日志。空串 = 还没探测到（旧格式 / 引擎未就绪）。
         */
        val cameraId: String,

        // —— 前台门控（2026-09-28）——
        /**
         * ★ 引擎是否**因前台应用自己管朝向而停手**。
         *
         * 停手 = 不开相机、不写方向；原因见 [foregroundOrientation]。
         * 它的价值是把"每轮触发开前摄 + 8 帧 ML Kit"这块 CPU/GPU 占用
         * 从游戏等自管朝向的应用里摘出去（用户报的"打游戏断触"）。
         */
        val foregroundGated: Boolean,
        /** 触发这次停手的前台包名（仅门控中有意义） */
        val foregroundPkg: String,
        /** 前台应用声明的朝向（可读名，如 `SENSOR_LANDSCAPE` / `FULL_USER`） */
        val foregroundOrientation: String,
        /**
         * 前台朝向**读得到吗**。
         * false = 读不到（`REAL_GET_TASKS` 被收回 / API 不可用）⇒ 引擎按 UNKNOWN 处理、
         * 保持改造前的行为。界面必须如实显示 —— 否则用户会以为门控在生效，其实没有。
         */
        val foregroundReadable: Boolean,
        /**
         * 停手是**哪一条豁免**造成的。
         *
         * 取值 = `WHITELIST`（在豁免名单里）；**空串 = 此刻没在停手**。
         * ⚠️ 删过的取值（⛔ 别再补回来）：
         *   - 2026-09-29 的 `OUTER_DESKTOP` / `OUTER_DECLARED`（外屏旋转增强整体删除，
         *     "外屏专属豁免"没有下游）；
         *   - 2026-10-05 的 `UNCONTROLLABLE`（实测转不动 ⇒ 改成弹一次提示，**不再停手**）。
         *
         * ★ 引擎 2026-09-29 起就在报这一格（`fgstop`，见 [cn.dsr213.hyperplus.module.EngineHost]），
         *   但 App 侧**一直没解析** —— 后果是界面只知道"停手了"、说不出"谁让它停的"，
         *   于是 `takeover=false` 一律被写成了"可能缺「修改系统设置」授权"。
         *   那是**误报**：实测状态串 `takeover=0|fg=1|fgstop=WHITELIST|grant=1` 里
         *   `grant=1` 明明写着授权是有的（真因是本应用自己恒豁免、它就在最前台）。
         *   补上这一格之后，界面那句话才是**结论**而不是猜测。
         *
         * ⚠️ 与 [foregroundPkg] / [foregroundOrientation] 一样是**最近一次巡检的读数**
         *   （进不进门都更新），配套读才有意义。
         */
        val foregroundStop: String,
        /** 因前台门而跳过的触发次数 */
        val skipGateCount: Int,
        /** 停手时是否交还系统自动旋转（用户可切的开关） */
        val handoffRotate: Boolean,
        /**
         * 前台门控**总开关**（2026-09-28 新增）。
         * false ⇒ 引擎永不因前台朝向停手（用户报"该转不转"时的逃生阀）。
         */
        val gateEnabled: Boolean,

        // —— 半自动模式（2026-09-28）——
        /** 旋转按钮累计弹出次数 */
        val semiShown: Int,
        /** 最近一次弹出的目标方向（-1 = 还没有过） */
        val semiTarget: Int,
        /** 用户点击确认并真的转过方向的累计次数 */
        val semiTapped: Int,
        /**
         * 悬浮窗是否可用。
         * ★ false ⇒ 半自动**根本弹不出按钮**（单机模式下多半是没给「显示在其他应用上层」）。
         *   这是"半自动是不是真的能用"的唯一诚实答案，界面必须如实显示。
         */
        val overlayOk: Boolean,
        /**
         * 半自动按钮**实际生效的窗口类型**（`WindowManager.LayoutParams` 的类型号；-1=还没弹过）。
         *
         * ★ 2017 = 状态栏子面板（层号 181000，高于状态栏 ⇒ 角上不会被遮，且该类型可触摸）；
         *   2038 = 普通悬浮窗（层号 111000，低于状态栏 ⇒ 转到位后角上被压住一块）。
         *   2006 = 系统覆盖层 —— 已弃用（层号够高但系统强制不可触摸），只会见于旧日志。
         *   提层级被 ROM 拒时会**静默降级**成 2038 —— 按钮照样弹、位置也对，
         *   所以界面上必须能把这件事说出来，否则用户只会觉得"修了但没好"。
         *   老版本引擎不报 `ovlt` ⇒ 这里保持 -1（"不知道"），不误报成降级。
         */
        val overlayType: Int,

        val calibrated: Boolean,
        val calibSign: Int,
        val calibOffsetDeg: Float,
        val sensorAvailable: Boolean,
        val writeGranted: Boolean,
        /**
         * 这次状态报告里**到底有没有「修改系统设置」这一项**（`grant` 键在不在）。
         *
         * ★ 为什么必须和 [writeGranted] 分开（2026-10-05，外部用户实测报障）：
         *   [writeGranted] 把「键不存在」与「键 = 0」压成同一个 `false`，界面于是把
         *   **没读到**说成了**缺失** —— 用户看到「修改系统设置：缺失」，跑去系统设置里找
         *   本应用，**根本找不到**（清单刻意没声明那个权限，见 `PermissionsPage` 的类注释）。
         *   而实测的真相是：`com.android.systemui` 的 `WRITE_SETTINGS` 是
         *   `granted=true`（`android.uid.systemui` 自带）⇒ 那一格红字**不是权限问题**，
         *   是引擎没在跑、状态串里没这一项。
         *   ⇒ 三态：**没这一项 = 未读到**（≠ 缺失）；有且为 1 才是拿到了；
         *     有但为 0 才是真的异常。
         *
         * ⚠️ 做法与 [cfgOk] 同源 —— 那个也用「本次状态里没有这一项」表达"不知道"，
         *   而不是假装它是坏的。
         */
        val grantReported: Boolean,
        val lastOpenMs: Long,
        /**
         * 宿主引擎**实时**已运行秒数。
         *
         * ★ 不是直接取摘要里的 `uptime` —— 那个是"上报那一刻的快照"，而状态是变化才上报的，
         *   所以没有新事件时它会冻住。这里的值由 `startedAt`（宿主启动时的 `elapsedRealtime` 秒）
         *   与本机同一条系统级时钟现算，因此**永远准确**。
         */
        val uptimeSec: Int,
        /**
         * 距宿主**最后一次心跳**过了多少秒（-1 = 拿不到心跳）。
         *
         * ★ 心跳由宿主每 [HOST_HEARTBEAT_PERIOD_MS] 无条件写一次（与"状态有无变化"无关），
         *   所以这个数字只回答一件事：**宿主还活着吗**。
         *   "引擎在干活吗"看 [frames] / [faces] / [decider]，两件事不要混。
         *
         * ⚠️ 别把它当"引擎没在干活"的证据 —— 无事件时状态本来就不上报（这是设计）。
         */
        val heartbeatAgoSec: Int,
        /**
         * **引擎进程有没有成功读到 App 的配置**（2026-09-28 单引擎改造后新增）。
         *
         * ★ 取值三态，刻意不用 Boolean：
         *   - `true` = 读到了；
         *   - `false` = **没读到** —— 这是新架构唯一的单点：配置走的是 App 的 prefs 文件，
         *     读不到就意味着"在界面上改什么都不生效"（引擎永远按默认值走）。界面必须报警；
         *   - `null` = 老格式摘要里没有这个字段（本字段是后加的），**不表态**，
         *     而不是假装它没问题。
         */
        val cfgOk: Boolean?,
        /** 引擎侧的通道诊断（失败原因 / 就绪路径）。空串 = 老格式没有该字段 */
        val cfgMsg: String,
        // —— 白名单 / 换屏搬运（2026-09-28 判据换成应用白名单后新增）——
        /**
         * 引擎手上**生效白名单**的条数（默认清单 ∪ 用户加的 − 用户关的 + 恒豁免的自己）。
         *
         * ⚠️ 它与用户界面里数出来的勾**不是同一个数**：生效集合里含一批**没装**的默认包。
         *   两边的差正好说明"默认清单有多大"，出问题时这就是要对的第一格。
         */
        val whitelistSize: Int,
        /**
         * 本屏的**安装朝向偏移**（0 = 外屏那一类 / 2 = 内屏那一类）。
         *
         * ★ 它是「展开内屏倒置 180°」那个 bug 的判据：展开后应当**从 0 变 2**。
         *   一直显示 0 就说明反射没读到 `installOrientation`（那时按"不换算"在跑，
         *   外屏正常、内屏仍是老的错法 —— 必须能一眼看出来）。
         */
        val panelOffset: Int,
        /**
         * **结构化失败计数**（2026-10-03 新增，只追加），形如 `bind:3,rotw:1`；
         * 空串 = 本次开机以来一次都没出过。
         *
         * ★ 为什么要它：引擎的失败路径一律吞异常（跑在 SystemUI 里，见 `EngineHost` 的纪律），
         *   代价是"静默失败"只能靠日志，而日志几分钟就被冲掉 ——
         *   2026-10-03「默认方向不生效」那轮正是因此**查不出根因**。
         *   计数跟着状态串上报，于是它和心跳一样**不会被冲掉**。
         *
         * ⚠️ 键名（`bind` / `rotw` / `rotr` / `fg`）是跨进程契约，认不出来时
         *   界面**原样显示**、不猜（见 [cn.dsr213.hyperplus.ui.errsText]）。
         */
        val errs: String,
        /** 原始串，托管模式下直接展示，避免界面为了少数字段解析失败就全空 */
        val raw: String,
    ) {

        /**
         * 宿主是否**在线**（心跳够新鲜）。
         *
         * 阈值 15 秒 = 心跳周期 5 秒的 3 倍，容得下 SystemUI 偶发的调度延迟；
         * 真死掉的话最多 15 秒就被认出来。
         */
        val hostAlive: Boolean get() = heartbeatAgoSec in 0..HOST_LOST_AFTER_SEC
    }

    /** 心跳超过这个秒数没更新 ⇒ 判定宿主失联（≈ 3 个心跳周期） */
    const val HOST_LOST_AFTER_SEC = 15

    /** 宿主的心跳周期（秒）。改宿主的 `HEARTBEAT_PERIOD_MS` 时要一起改这里。 */
    const val HOST_HEARTBEAT_PERIOD_MS = 5_000L

    /** 等标定结果时的轮询间隔。标定本身要采约 2.6 秒样本，120ms 的粒度足够了 */
    private const val POLL_INTERVAL_MS = 120L

    // ================================================================ 标定请求

    /** 请求结果 */
    enum class CalibStatus { OK, NO_FACE, BAD_ANGLE, UNAVAILABLE, TIMEOUT }

    /**
     * 【**不要在主线程调用**】让宿主的引擎采一次标定样本并应用。
     *
     * ★ 为什么标定必须由宿主做：采样要真的开相机，而**相机在宿主手里** ——
     *   改造后 App 侧连引擎都没有了，更没有相机权限。
     *
     * 完整链路（两端都不需要 root）：
     *   ① 写 App 自己的 prefs：`calib_req = "<token>|<step>"`；
     *   ② 宿主感知到变化（文件监控 + 2 秒兜底轮询，见 `ModulePrefs`）→ 采样
     *      → 把 `"<token>|<step>|<status>"` 写进 Settings；
     *   ③ 这里轮询**读**回来（读系统设置零门槛）。
     *
     * ★ token 是这次请求的唯一标识：结果里只有 step 的话，用户连点两次同一个按钮就会
     *   读到上一次的旧结果。有了 token 才能严格配对 —— 也正因如此，**不需要**像旧实现那样
     *   先把总线上的旧结果"清成 pending"（那需要 App 写 Settings，正是要摘掉的 root 依赖）。
     *
     * ⚠️ 超时取 20 秒：链路里有一段"引擎最多 2 秒才察觉请求"的兜底轮询，
     *   之后还要真的开一次相机采帧（本机对 SystemUI 的相机连接延迟实测可达 ~6 秒）。
     *   按 12 秒给会把"本来就慢但会成功"的标定判成超时。
     *
     * @return 宿主回报的结果；超时返回 [CalibStatus.TIMEOUT]
     */
    fun requestCalibration(
        ctx: Context,
        step: Int,
        timeoutMs: Long = 20_000L,
    ): CalibStatus {
        val token = System.currentTimeMillis().toString()
        AppPrefs.requestCalibration(step, token)
        Log.i(TAG, "标定请求已发出：token=$token step=$step")

        val cr = ctx.contentResolver
        val want = "$token|"
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = PrefsBridge.readString(cr, PrefsBridge.CALIB_RESULT)
            if (raw != null && raw.startsWith(want)) {
                // 格式 "<token>|<step>|<status>"
                val status = raw.split('|').getOrNull(2)?.trim()
                Log.i(TAG, "标定结果已回收：$raw")
                return when (status) {
                    "ok" -> CalibStatus.OK
                    "noface" -> CalibStatus.NO_FACE
                    "badangle" -> CalibStatus.BAD_ANGLE
                    else -> CalibStatus.UNAVAILABLE
                }
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
        Log.w(TAG, "标定结果超时（token=$token，等了 ${timeoutMs}ms）")
        return CalibStatus.TIMEOUT
    }

    /**
     * 一次「已经到多分屏上限了」的回报（A4，2026-10-05）。
     *
     * @param stampMs 引擎判定到顶的时刻（**不是**我们读它的时刻）
     * @param count 当时已有的格数
     * @param max 上限格数（**引擎实测的那个数**，见 `SplitTrigger.maxStagesOrNull`）
     */
    data class SplitLimit(val stampMs: Long, val count: Int, val max: Int)

    /**
     * 读「到上限」提示（[PrefsBridge.SPLIT_BUS_LIMIT] 的解析结果）。
     *
     * ★★ 它**没有**配对 token（旧「折角校准结果」那种严格配对已随校准下线）——
     *   因为这条提示是**引擎主动推**的（不是"用户发起、引擎应答"）。
     *   ⇒ 判新旧只能靠**时间戳本身**：调用方拿到后自己决定"这条是不是我还没提示过的"
     *   （见 `SplitEnhancePage` 里那个 `lastLimitStamp`）。
     *
     * @return null = 没有这条记录（引擎从没报过到顶）
     */
    fun readSplitLimit(ctx: Context): SplitLimit? {
        val raw = PrefsBridge.readString(ctx.contentResolver, PrefsBridge.SPLIT_BUS_LIMIT)
            ?: return null
        val f = raw.split('|')
        if (f.size < 3) return null
        val stamp = f[0].toLongOrNull() ?: return null
        val count = f[1].toIntOrNull() ?: return null
        val max = f[2].toIntOrNull() ?: return null
        if (stamp <= 0L || max < 2) return null
        return SplitLimit(stamp, count, max)
    }

    // ================================================================ 状态读取

    /**
     * 分屏触发器的档位（0 关 / 1 只观察 / 2 真动作）；读不到按 **0**（= 关）。
     *
     * ★ 为什么 App 能读：它住在 `Settings.Global`（[PrefsBridge.SPLIT_TRIGGER]），
     *   而**读**系统设置零权限（只有写非公开键才受限，见 [PrefsBridge] 类注释）。
     *
     * ★ 为什么要读它：主页那条状态行的主语是**整个模块**（用户 2026-10-04 点名：
     *   「运行中改成整个模块的运行状态显示，不要再只显示旋转增强」），
     *   而"现在启用了哪些增强"必须知道分屏这一档 —— 旋转那一档在 AppPrefs 里。
     *
     * ⚠️ 它是**启动时读一次**的开关（引擎侧 `SplitTrigger.install` 只读一次，
     *   改了要重启系统界面才生效）⇒ 界面把它说成"已开启"，⛔ 别说成"正在运行"。
     */
    fun splitTriggerMode(ctx: Context): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, PrefsBridge.SPLIT_TRIGGER, 0)
    }.getOrDefault(0)

    /**
     * [splitTriggerMode] 的取值之一：**真动作**（判定成立时真的会让屏幕分成两半）。
     *
     * ⚠️ 另外两档（0 = 关 / 1 = 只观察）**界面用不着**，所以只把这一档提出来 ——
     *   界面唯一要回答的问题是"分屏增强算不算已开启"，而只有 2 档算。
     */
    const val SPLIT_TRIGGER_ACT = 2

    /**
     * 纯读一眼宿主留下的状态（**不探活**，两次 `Settings` 读 + 解析，可在主线程调用）。
     * 界面定时刷新用它 —— 比每次探活便宜得多。
     */
    fun currentState(ctx: Context): State? = readState(ctx.contentResolver)

    /** 读状态串 + 心跳，一起交给 [parse]（心跳用来算"宿主心跳距今多久"） */
    private fun readState(cr: android.content.ContentResolver): State? =
        parse(
            PrefsBridge.readString(cr, PrefsBridge.STATE),
            PrefsBridge.readInt(cr, PrefsBridge.HEARTBEAT, 0),
        )

    // ================================================================ 解析

    /**
     * 解析状态摘要；格式不认识就返回 null（**不猜**）。
     *
     * @param heartbeatSec 宿主写 `bus_engine_heartbeat` 时的 `elapsedRealtime` 秒；
     *                     传 0 表示拿不到，此时 [State.heartbeatAgoSec] 为 -1。
     */
    fun parse(raw: String?, heartbeatSec: Int = 0): State? {
        if (raw.isNullOrBlank()) return null
        // 版本头是契约的一部分：认不出来的格式宁可当作"没有状态"，也不要按字段名硬猜
        if (!raw.startsWith("v1|")) return null
        val kv = HashMap<String, String>()
        for (part in raw.split('|')) {
            val i = part.indexOf('=')
            if (i > 0) kv[part.substring(0, i)] = part.substring(i + 1)
        }
        val nowSec = SystemClock.elapsedRealtime() / 1000
        val startedAt = kv["startedAt"]?.toLongOrNull() ?: 0L
        return runCatching {
            State(
                phase = kv["phase"] ?: "unknown",
                // ★ 只在 phase=halted 时有意义；老格式没有这个字段 ⇒ 0（见字段注释）
                breakerAttempts = kv["attempts"]?.toIntOrNull() ?: 0,
                mode = kv["mode"] ?: "SYSTEM",
                takeover = kv["takeover"] == "1",
                rotation = kv["rot"]?.toIntOrNull() ?: -1,
                display = kv["disp"]?.toIntOrNull() ?: 0,
                decider = kv["decider"] ?: "UNKNOWN",
                burstCount = kv["burst"]?.toIntOrNull() ?: 0,
                frames = kv["frames"]?.toLongOrNull() ?: 0L,
                faces = kv["faces"]?.toLongOrNull() ?: 0L,
                switchCount = kv["sw"]?.toIntOrNull() ?: 0,
                voteCounts = kv["votes"] ?: "",
                voteWinner = kv["vwin"]?.toIntOrNull() ?: -1,
                voteValid = kv["vvalid"]?.toIntOrNull() ?: 0,
                voteConfident = kv["vconf"] == "1",
                gravitySector = kv["grav"]?.toIntOrNull() ?: -1,
                signSame = kv["sgnS"]?.toIntOrNull() ?: 0,
                signFlip = kv["sgnF"]?.toIntOrNull() ?: 0,
                signConfirmed = kv["sgnOK"] == "1",
                conflictCount = kv["conflict"]?.toIntOrNull() ?: 0,
                cameraId = kv["cid"] ?: "",
                foregroundGated = kv["fg"] == "1",
                foregroundPkg = kv["fgpkg"] ?: "",
                foregroundOrientation = kv["fgori"] ?: "",
                foregroundReadable = kv["fgr"] == "1",
                // 老格式没有 `fgstop` ⇒ 空串（= "不知道停手原因"），不猜
                foregroundStop = kv["fgstop"] ?: "",
                skipGateCount = kv["gate"]?.toIntOrNull() ?: 0,
                handoffRotate = kv["handoff"] != "0",
                // 门控总开关：默认视为"开"（老格式没有这个字段时的合理默认，与引擎一致）
                gateEnabled = kv["gateon"] != "0",
                semiShown = kv["semi"]?.toIntOrNull() ?: 0,
                semiTarget = kv["semitgt"]?.toIntOrNull() ?: -1,
                semiTapped = kv["semitap"]?.toIntOrNull() ?: 0,
                overlayOk = kv["ovl"] == "1",
                // 老格式没有 `ovlt` ⇒ -1（"不知道"）。不默认成 2038，否则老引擎会被误报成降级。
                overlayType = kv["ovlt"]?.toIntOrNull() ?: -1,
                calibrated = kv["calib"] == "1",
                calibSign = kv["sign"]?.toIntOrNull() ?: 1,
                calibOffsetDeg = kv["offset"]?.toFloatOrNull() ?: 0f,
                sensorAvailable = kv["sensor"] == "1",
                // ★ 三态判据：`grant` **键在不在**，而不是它的值。
                //   ⛔ 别把它并回上面那一行：键不存在时它是"未读到"，不是"缺失"（见 [State.grantReported]）。
                grantReported = kv.containsKey("grant"),
                writeGranted = kv["grant"] == "1",
                lastOpenMs = kv["openMs"]?.toLongOrNull() ?: -1L,
                // ★ 优先用 startedAt 现算（实时、准确）；拿不到才退回快照值（老格式兜底）
                uptimeSec = if (startedAt > 0L) {
                    (nowSec - startedAt).coerceAtLeast(0L).toInt()
                } else {
                    kv["uptime"]?.toIntOrNull() ?: 0
                },
                heartbeatAgoSec = if (heartbeatSec > 0) {
                    (nowSec - heartbeatSec).coerceAtLeast(0L).toInt()
                } else {
                    -1
                },
                // ⚠️ 刻意只认显式写出来的 0/1：字段缺失（老格式）时留 null 不表态，见 [State.cfgOk]
                cfgOk = kv["cfgold"]?.let { it == "1" },
                cfgMsg = kv["cfgmsg"] ?: "",
                // ⚠️ 老格式没有这两项 ⇒ 取 0。对 `pofs` 来说 0 恰好是"外屏/不换算"的意思，
                //   而老格式的引擎本来就是不换算的 —— 所以这个兜底是**如实**的，不是掩饰。
                whitelistSize = kv["wlN"]?.toIntOrNull() ?: 0,
                panelOffset = kv["pofs"]?.toIntOrNull() ?: 0,
                // 老格式没有 `errs` ⇒ 空串（= "没有失败记录"）。⚠️ 老引擎确实报不出失败，
                // 所以这里空串是**如实**的，不是掩饰。
                errs = kv["errs"] ?: "",
                raw = raw,
            )
        }.getOrNull()
    }
}

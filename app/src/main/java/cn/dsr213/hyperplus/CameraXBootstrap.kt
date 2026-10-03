package cn.dsr213.hyperplus

import androidx.camera.camera2.Camera2Config
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * CameraX 的一次性引导。
 *
 * ============================ 为什么必须显式配置（实测，非推断） ============================
 * `ProcessCameraProvider.getInstance(context)` 会构造 `CameraX(context, provider)`，
 * provider 为 null 时 CameraX 去**猜**默认实现。反编译 `CameraX.getConfigProvider` 得到的
 * 真实查找顺序只有两条：
 *
 *   ① `context.getApplicationContext() instanceof CameraXConfig.Provider`
 *      —— 需要宿主 Application 实现该接口；
 *   ② 否则拿 `context` 所属包去查 `androidx.camera.core.impl.MetadataHolderService`
 *      的 meta-data：`…MetadataHolderService.DEFAULT_CONFIG_PROVIDER`
 *      = `androidx.camera.camera2.Camera2Config$DefaultProvider`。
 *      （这条 meta-data 由 camera-camera2 的 AAR 清单合并进**我们 App 的**清单，实测存在。）
 *
 * ⇒ 引擎跑在 **SystemUI 进程**里、用的是 SystemUI 的 Application 与包名，
 *   ①②**两条都不成立** ⇒ 实测抛：
 * ```
 * IllegalStateException: CameraX is not configured properly. The most likely cause is you did
 *   not include a default implementation in your build such as 'camera-camera2'.
 * ```
 * ⚠️ 这句话会把人带偏 —— 依赖明明在，缺的是「从宿主包里找不到配置」。
 *
 * ⇒ 解法：在**第一次** `getInstance` 之前调一次
 *   `ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig())`。
 *   该方法字节码做的事就是把 provider 塞进 `mCameraXConfigProvider`，
 *   而 `getOrCreateCameraXInstance` 正是把它传给 `CameraX(context, provider)`。
 *   ⚠️ 它内部有 `checkState(mCameraXConfigProvider == null)` ——
 *      **重复调用会抛** "CameraX has already been configured"，
 *      所以这里用 [done] 保证每进程只做一次，且整体 runCatching 兜住。
 *
 * ★ 单机模式（App 进程）靠清单发现本来就能过，但仍然走同一条路：
 *   少一条「只在某个模式下才成立」的分支，就少一类「只有托管模式才复现」的怪问题。
 */
internal object CameraXBootstrap {
    @Volatile
    private var done = false

    /**
     * ★ 该方法标了 `@ExperimentalCameraProviderConfiguration` —— 那是 **androidx 的**
     *   `@RequiresOptIn`（不是 Kotlin 的），编译器实测**不要求** `@OptIn`（加了反而报
     *   "has no effect" 的警告），所以这里不加多余注解。
     */
    fun ensureExplicitConfig(): String {
        if (done) return "已配置过，跳过"
        done = true
        return runCatching {
            ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig())
            "✅ 已显式配置（Camera2Config.defaultConfig）"
        }.getOrElse {
            "⚠️ 显式配置失败 ${it.javaClass.simpleName}: ${it.message}（若清单发现可用则无碍）"
        }
    }
}

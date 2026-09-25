package cn.dsr213.hyperplus.module

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 给 CameraX 用的**常驻**生命周期宿主。
 *
 * ============================ 为什么需要它 ============================
 * 原来引擎的宿主是 `MainActivity`，用的就是 Activity 自己的 lifecycle。这带来一个
 * 用户能直接看到的天花板：
 *
 * ```
 * 在 App 里 → 引擎活着 → 正常
 * 一回到桌面 → Activity onStop() → CameraX 自动解绑 → 我们主动交还系统自动旋转
 *            → 系统接管 → 「自适应」失效
 * ```
 *
 * 这不是 bug，是「引擎宿主是 Activity」的必然结果。搬进 SystemUI 后就没有「退到后台」
 * 这回事了（SystemUI 是 PERSISTENT 进程），但 CameraX 仍然需要一个 lifecycle 才能
 * `bindToLifecycle` —— SystemUI 里恰恰没有 Activity 可借。
 *
 * ⇒ 手工造一个：推到 `RESUMED` 之后**永不下调**。
 *   CameraX 只在 lifecycle 走到 `DESTROYED` 或显式 unbind 时才解绑，所以
 *   「只升不降」等价于「相机管线跟着 SystemUI 进程活」—— 这正是我们要的语义。
 *
 * ★ 两个必须遵守的线程约束（LifecycleRegistry 内部有 `checkMainThread()`）：
 *   1. **构造必须主线程**；
 *   2. **`currentState` 赋值必须主线程**。
 *   所以 [EngineHost] 是整体 post 到主线程才创建本对象的，不要在别处 new。
 */
internal class SystemUiLifecycle : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    /**
     * 【主线程】推进到 RESUMED 并停在那里。
     *
     * `LifecycleRegistry` 会自动按 `INITIALIZED → CREATED → STARTED → RESUMED`
     * 顺序补齐中间事件，所以一句赋值就够，不用手写四个状态。
     */
    fun resumeForever() {
        runCatching { registry.currentState = Lifecycle.State.RESUMED }
    }

    /** 只在需要彻底收摊时用（会把已绑定的 use case 解绑） */
    fun destroy() {
        runCatching { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

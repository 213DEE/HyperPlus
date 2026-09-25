package cn.dsr213.hyperplus.module

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.mlkit.common.sdkinternal.MlKitContext
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * 「宿主进程里的运行环境准备」—— 把引擎从 App 搬进 SystemUI 时，所有**必须提前做**的事。
 *
 * ============================ 为什么这些事必须提前做 ============================
 * 引擎的人脸检测依赖 ML Kit，而 ML Kit 有两个只在「自己的进程」里才天然成立的前提。
 * 下面每一条都是**实测**结论（证据见 `ModuleSelfCheck` 第 ⑤ / ⑧ 项的输出），不是推断：
 *
 * **① native 库：`System.load(绝对路径)` ✅ ≠ SDK 自己能加载**
 *   `ThickFaceDetectorCreator` 是在**静态初始化块**里 `System.loadLibrary("face_detector_v2_jni")`
 *   （8.5MB，人脸模型就在这个 so 里）。`System.loadLibrary` 走的是「调用方类的加载器
 *   + `java.library.path`」，而在 SystemUI 进程里两条都指不到我们 APK：
 *   实测 `findLibrary(face_detector_v2_jni) → null`、`java.library.path = /system/lib64:/system_ext/lib64`。
 *
 *   ★★ 而静态块**每个类加载器只跑一次**：第一次失败就永久 `NoClassDefFoundError`，
 *      连重试的机会都没有。所以预加载必须发生在**任何 ML Kit 类被触碰之前**。
 *      这是本类存在的第一理由。
 *
 *   ⚠️ 证据等级诚实标注：✅ 用绝对路径预加载（[preloadNativeLibs]）实测有效；
 *      🔎 它起作用的确切机制（推测是补齐了 ART 进程级的已加载表）**没有被完全确认**。
 *      另外两条"更常规"的解法实测都是死的：`java.library.path` 是保护属性、
 *      `setProperty` 静默无效；反射改 `DexPathList` 的 native 搜索路径虽然报成功，
 *      `findLibrary` 仍返回 null。所以生产代码里只写**确定有效**的那一条，
 *      不依赖任何反射补丁去"顺便修好它"。
 *
 * **② MlKitContext：本 App 清单里的 `MlKitInitProvider` 在 SystemUI 里不存在**
 *   平时 ML Kit 靠清单注册的 Provider 在进程启动时自初始化；SystemUI 的清单里没有这个 Provider，
 *   所以必须手动 `MlKitContext.initializeIfNeeded(...)`。
 *   而且传的上下文有讲究：
 *     - **必须是本 App 的上下文** —— 组件发现读的是「传入 context 所属包的清单」，
 *       传 SystemUI 会一个组件都发现不到，**而且不报错**（静默变空组件表）；
 *     - `createPackageContext()` 造出来的上下文，`getApplicationContext()` 返回 **null**
 *       （`LoadedApk.mApplication` 只在真的创建过 Application 之后才非空），
 *       而 ML Kit 多处 `checkNotNull(context.getApplicationContext(), "The provided context
 *       did not have an application context.")` ⇒ 必须用 [AppCtxShim] 包一层。
 *
 * **③ 「没抛异常」≠ 成功**
 *   第一次跑自检时 `initializeIfNeeded` 报了 ✅，真用的时候却 NPE。所以 [prepare]
 *   不只报成败，还把「加载器类名 / findLibrary 返回值 / java.library.path」全打出来 ——
 *   一次重启就能定位，不用猜。
 */
internal object HostEnv {

    private const val TAG = HyperPlusModule.TAG

    /**
     * 需要预加载的 so。
     * ⚠️ 刻意**不含** `libandroidx.graphics.path.so`（Compose 带来的）——
     *    手动 `System.load` 它会 `JNI_ERR returned from JNI_OnLoad`，与业务无关，
     *    混进清单只会干扰判断。
     */
    val NATIVE_LIBS = listOf(
        "libface_detector_v2_jni.so",
        "libimage_processing_util_jni.so",
        "libsurface_util_jni.so",
    )

    // ================================================================ 上下文

    /**
     * 把「本 App 的包上下文」包一层，强制 `getApplicationContext()` 返回自己。
     * 原因见类注释 ②。
     */
    internal class AppCtxShim(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    /** 取本 App 的包上下文（已套 [AppCtxShim]）。拿不到返回 null。 */
    fun appContext(hostCtx: Context, appPkg: String): Context? = runCatching {
        hostCtx.createPackageContext(appPkg, Context.CONTEXT_IGNORE_SECURITY)?.let { AppCtxShim(it) }
    }.getOrNull()

    /** 本 App 的 native 库目录（so 实际解包位置，依赖 `useLegacyPackaging=true`） */
    fun nativeLibDir(appCtx: Context?): File? =
        appCtx?.applicationInfo?.nativeLibraryDir?.let { File(it) }

    // ================================================================ ① 预加载 so

    /**
     * 用**绝对路径**逐个预加载 native 库。必须在任何 ML Kit 类被触碰之前调用。
     *
     * 顺序照抄实测通过的那次：先主库，再两个依赖库。单个失败不影响其余，全部如实回报。
     */
    fun preloadNativeLibs(appCtx: Context?): List<String> {
        val dir = nativeLibDir(appCtx) ?: return listOf("⚠️ 拿不到本 App nativeLibraryDir → 无法预加载")
        val out = mutableListOf<String>()
        out += "目标 so 目录=${dir.absolutePath}（存在=${dir.isDirectory}）"
        if (dir.isDirectory) out += "目录内容=[${dir.list()?.joinToString(",") ?: "不可读"}]"
        for (name in NATIVE_LIBS) {
            val r = runCatching {
                System.load(File(dir, name).absolutePath); "✅ OK"
            }.getOrElse { "❌ ${it.javaClass.simpleName}: ${it.message}" }
            out += "System.load($name) → $r"
        }
        return out
    }

    // ================================================================ ② 初始化 ML Kit

    /** `MlKitContext.initializeIfNeeded(本 App 上下文)`，返回可读结果 */
    fun initMlKit(hostCtx: Context, appCtx: Context?): String = runCatching {
        MlKitContext.initializeIfNeeded(appCtx ?: hostCtx)
        "✅ 未抛异常"
    }.getOrElse { "❌ ${it.javaClass.name}: ${it.message}" }

    // ================================================================ 诊断

    fun findLibrary(loader: ClassLoader?): String {
        if (loader == null) return "加载器为 null"
        return runCatching {
            val m = BaseDexClassLoader::class.java.getDeclaredMethod("findLibrary", String::class.java)
            m.isAccessible = true
            m.invoke(loader, "face_detector_v2_jni") as? String ?: "(null)"
        }.getOrElse { "反射失败：${it.javaClass.simpleName}" }
    }

    fun libraryPath(): String = System.getProperty("java.library.path") ?: "(无)"

    // ================================================================ 组合动作

    /**
     * 引擎启动前的一次性准备。**必须在 worker 线程上调用**（含文件 I/O 与 dlopen）。
     *
     * 顺序是有讲究的，不能调换：
     *   ① 预加载 so  →  ② 初始化 MlKitContext  →  ③ 之后才允许碰 FaceDetector
     *
     * @return 逐行可读的准备报告（写日志用）
     */
    fun prepare(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?): List<String> {
        val out = mutableListOf<String>()
        out += "java.library.path=${libraryPath()}"

        val mlCls = runCatching { Class.forName("com.google.mlkit.vision.face.FaceDetection") }.getOrNull()
        if (mlCls == null) {
            out += "❌ ML Kit 类加载不了 → 模块 dex 里没带上依赖，这条路不通"
            return out
        }
        val cl = mlCls.classLoader
        out += "ML Kit 类的加载器 = ${cl?.javaClass?.name}"
        out += "补丁前 findLibrary(face_detector_v2_jni) → ${findLibrary(cl)}"

        out += preloadNativeLibs(appCtx)

        nativeLibDir(appCtx)?.let { dir ->
            patchLibraryPath(cl, dir).forEach { out += "  $it" }
        }
        out += "补丁后 findLibrary(face_detector_v2_jni) → ${findLibrary(cl)}"
        out += "MlKitContext.initializeIfNeeded(本App上下文) → ${initMlKit(hostCtx, appCtx)}"
        return out
    }

    // ================================================================ 反射补丁（诊断/兜底）

    /**
     * 把 [libDir] 塞进 [loader] 及其**整条父链**的 native 库搜索路径。
     *
     * ⚠️ 实测：改了 `findLibrary` **仍然返回 null**。所以它不是「让 ML Kit 能加载」的原因，
     *    只作为**诊断与兜底**保留 —— 真正起作用的是 [preloadNativeLibs] 的绝对路径预加载。
     *    留着的价值：万一某个机型/版本上预加载不够，这一层可能救回来，且改动全程 runCatching，
     *    拿不到字段只如实回报，**绝不把 SystemUI 拖下水**。
     *
     * 走 `BaseDexClassLoader.pathList`（实际类型 `dalvik.system.DexPathList`）的两个字段：
     *  - `nativeLibraryDirectories`（`List<File>`，老 API 用）
     *  - `nativeLibraryPathElements`（`NativeLibraryElement[]`，Android 8+ 的
     *    `findLibrary` 真正遍历的就是它）
     * 两个都改，且**新元素插到最前面** —— 万一某个父加载器里也有同名 so，要保证解析到我们这份。
     */
    fun patchLibraryPath(loader: ClassLoader?, libDir: File): List<String> {
        val out = mutableListOf<String>()

        // ① java.library.path：顺手也加上（部分实现会兜底查它）
        runCatching {
            val old = System.getProperty("java.library.path") ?: ""
            if (!old.contains(libDir.absolutePath)) {
                val merged = if (old.isEmpty()) libDir.absolutePath else "$old:${libDir.absolutePath}"
                System.setProperty("java.library.path", merged)
            }
            out += "① java.library.path → ${System.getProperty("java.library.path")}"
        }.onFailure { out += "① java.library.path 失败：${it.javaClass.simpleName}" }

        // ② 沿类加载器父链逐个打补丁
        var cur: ClassLoader? = loader
        var depth = 0
        while (cur != null && depth < 8) {
            out += "② 链[$depth] ${cur.javaClass.name} → ${patchOneLoader(cur, libDir)}"
            cur = cur.parent
            depth++
        }
        return out
    }

    private fun patchOneLoader(loader: ClassLoader, libDir: File): String {
        if (loader !is BaseDexClassLoader) return "非 BaseDexClassLoader，跳过"

        val pathList = runCatching {
            BaseDexClassLoader::class.java.getDeclaredField("pathList")
                .apply { isAccessible = true }
                .get(loader)
        }.getOrElse { return "拿不到 pathList：${it.javaClass.simpleName}" }
        if (pathList == null) return "pathList=null"

        val notes = mutableListOf<String>()

        // nativeLibraryDirectories: List<File>
        runCatching {
            val f = pathList.javaClass.getDeclaredField("nativeLibraryDirectories")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = f.get(pathList) as MutableList<File>
            if (list.any { it.absolutePath == libDir.absolutePath }) {
                notes += "dirs已含"
            } else {
                list.add(0, libDir)
                notes += "dirs+=ok"
            }
        }.onFailure { notes += "dirs改不动(${it.javaClass.simpleName})" }

        // nativeLibraryPathElements: NativeLibraryElement[]
        runCatching {
            val f = pathList.javaClass.getDeclaredField("nativeLibraryPathElements")
                .apply { isAccessible = true }
            val arr = f.get(pathList) as? Array<*>
            if (arr == null) {
                notes += "elements=null"
                return@runCatching
            }
            val elemCls = Class.forName("dalvik.system.DexPathList\$NativeLibraryElement")
            val ctor = elemCls.declaredConstructors.firstOrNull { it.parameterCount == 1 }
            if (ctor == null) {
                notes += "无单参构造(${elemCls.declaredConstructors.map { it.parameterCount }})"
                return@runCatching
            }
            ctor.isAccessible = true
            val newArr = java.lang.reflect.Array.newInstance(elemCls, arr.size + 1)
            java.lang.reflect.Array.set(newArr, 0, ctor.newInstance(libDir))
            for (i in arr.indices) java.lang.reflect.Array.set(newArr, i + 1, arr[i])
            f.set(pathList, newArr)
            notes += "elements+=ok(${arr.size}→${arr.size + 1})"
        }.onFailure { notes += "elements改不动(${it.javaClass.simpleName})" }

        return notes.joinToString(" ")
    }
}

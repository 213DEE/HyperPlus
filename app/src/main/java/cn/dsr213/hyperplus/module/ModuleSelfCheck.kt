package cn.dsr213.hyperplus.module

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块启动自检。
 *
 * ★ 为什么正式模块里要留这么一段"验收代码"：
 *   引擎跑在 SystemUI 进程里，这条路对系统版本、机型、厂商定制都敏感，
 *   一旦线上失效，没有自检就只能靠猜。每次启动跑一遍并落盘，排查时一眼看清卡在哪项。
 *
 * 检查项：
 *   ① 宿主身份  ② CAMERA / WRITE_SETTINGS 权限通路  ③ 前摄参数与各档 YUV 尺寸
 *   ④ 取流（逐尺寸试错，第一档拿到帧即停）
 *   ⑤ 本 App 的 native 库能否在 SystemUI 进程里加载（ML Kit 人脸模型依赖）
 *   ⑥ 能否直写 USER_ROTATION（写回原值，无副作用）  ⑦ 朝向传感器
 *   ⑧ ML Kit 能否在 SystemUI 进程里**真跑**（一票否决项，见 [probeMlKit]）
 *
 * ==================== 两个实测踩出来的硬约束（2026-09-25） ====================
 * 1) **小米对 SystemUI 的相机 connect 有约 6 秒延迟**（日志实测：请求 19.297 → CONNECT 25.3）。
 *    所以打开超时必须给足，不能按普通应用的经验设几秒。
 * 2) **超时后设备仍可能在稍后真的打开** —— 此时若没人去 close，
 *    相机就被永久攥住，且会**自我毒化**：后续每次打开都要先做一次同包 eviction，越来越慢。
 *    这正是本自检第一版的现象（每一档都"打开超时"）。
 *    ⇒ 修法：超时后置 abandoned 标志，onOpened 迟到时**当场关掉**；并且改为
 *      "只 connect 一次、反复试会话"，从根上不再反复 connect。
 * ==========================================================================
 *
 * ⚠️ 无副作用原则：⑥ 写的是读出来的原值，不会改变用户当前屏幕方向。
 */
internal object ModuleSelfCheck {

    private const val TAG = HyperPlusModule.TAG

    /** 打开超时：小米对 SystemUI 的连接延迟实测约 6s，这里放到 15s 留足余量 */
    private const val OPEN_TIMEOUT_MS = 15_000L
    private const val SESSION_TIMEOUT_MS = 5_000L
    private const val FRAME_TIMEOUT_MS = 4_000L

    /** 27 = SENSOR_TYPE_DEVICE_ORIENTATION（主工程已实证：本机 handle 0x1b） */
    private const val SENSOR_DEVICE_ORIENTATION = 27

    /**
     * 会话试错顺序。
     * ★ 为什么要多档：640x480 明明在框架的 StreamConfigurationMap 里，
     *   小米 HAL 仍打印 `roundBufferDimensionNearest: can't find size 640x480 in xiaomi size`
     *   并**静默卡死** —— 框架层与 HAL 层的可用尺寸并不一致，只能实试。
     */
    private val CANDIDATE_SIZES = listOf(
        Size(640, 480), Size(1280, 720), Size(320, 240), Size(176, 144), Size(1600, 1200),
    )

    private val report = StringBuilder()

    private fun put(s: String) {
        Log.i(TAG, s)
        report.append(s).append('\n')
    }

    fun run(hostCtx: Context, appCtx: Context?) {
        report.setLength(0)
        put("=========== HyperPlus 模块自检 ===========")

        // ---------------------------------------------------------- ① 宿主身份
        put(
            "① 宿主=${hostCtx.packageName} uid=${hostCtx.applicationInfo?.uid} " +
                "SELinux=${readTrim("/proc/self/attr/current")}",
        )

        // ---------------------------------------------------------- ② 权限通路
        val camOk = runCatching {
            hostCtx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val canWrite = runCatching { Settings.System.canWrite(hostCtx) }.getOrDefault(false)
        put("② CAMERA=$camOk  canWrite=$canWrite")

        // ---------------------------------------------------------- ③④ 相机
        val cm = hostCtx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            put("③ ❌ CameraManager 取不到")
        } else {
            val frontId = runCatching { cm.cameraIdList }.getOrElse { emptyArray() }.firstOrNull { id ->
                runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }
                    .getOrNull() == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (frontId == null) {
                put("③ ❌ 找不到前摄")
            } else {
                put(
                    "③ 前摄 id=$frontId SENSOR_ORIENTATION=" + runCatching {
                        cm.getCameraCharacteristics(frontId).get(CameraCharacteristics.SENSOR_ORIENTATION)
                    }.getOrElse { "?" },
                )
                cameraTrial(cm, frontId)
            }
        }

        // ---------------------------------------------------------- ⑤ native 库
        //  ★ 与生产路径共用同一份实现（HostEnv）——引擎启动时做的就是这一步，
        //    这里只是把它的话原样打出来，保证「自检结果」和「引擎实际遇到的情况」一致。
        val libDir = appCtx?.applicationInfo?.nativeLibraryDir
        put("⑤ 本 App nativeLibraryDir=$libDir")
        HostEnv.preloadNativeLibs(appCtx).forEach { put("   $it") }

        // ---------------------------------------------------------- ⑥ 写方向
        runCatching {
            val cr = hostCtx.contentResolver
            val before = Settings.System.getInt(cr, Settings.System.USER_ROTATION)
            put("⑥ 写入前 USER_ROTATION=$before")
            val res = runCatching { Settings.System.putInt(cr, Settings.System.USER_ROTATION, before) }
            put("   putInt → " + res.fold({ "✅ 成功" }, { "❌ ${it.javaClass.simpleName}: ${it.message}" }))
            put(
                "   回读=" + Settings.System.getInt(cr, Settings.System.USER_ROTATION) +
                    "（写原值，未改你的屏幕）",
            )
        }.onFailure { put("⑥ ❌ 读写异常：${it.javaClass.simpleName}: ${it.message}") }

        // ---------------------------------------------------------- ⑦ 传感器
        probeSensor(hostCtx)

        // ---------------------------------------------------------- ⑧ ML Kit（一票否决项）
        runCatching { probeMlKit(hostCtx, appCtx) }
            .onFailure { put("⑧ ❌ 探测过程异常：${it.javaClass.name}: ${it.message}") }

        put("=========== 自检结束 ===========")
        val f = HyperPlusModule.reportFile(hostCtx)
        val ok = runCatching { f.writeText(report.toString()); true }.getOrDefault(false)
        Log.i(TAG, "自检报告写入 $f → $ok")
    }

    // ==================================================================== ④ 取流

    private fun cameraTrial(cm: CameraManager, frontId: String) {
        val supported = runCatching {
            cm.getCameraCharacteristics(frontId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
        }.getOrNull()
        put("   框架支持的 YUV 尺寸=[${supported?.joinToString(",") { "${it.width}x${it.height}" } ?: "查询失败"}]")

        val candidates = CANDIDATE_SIZES.filter { c ->
            supported == null || supported.any { it.width == c.width && it.height == c.height }
        }.ifEmpty { CANDIDATE_SIZES }

        // ★ 只 connect 一次：反复 connect 会触发同包 eviction 抖动，越试越慢（实测）
        val dev = openOnce(cm, frontId)
        if (dev == null) {
            put("④ ❌ 相机打不开（${OPEN_TIMEOUT_MS}ms 内无 onOpened）→ 这条通路不通")
            return
        }
        put("④ 相机已打开，开始逐尺寸试会话")

        var okSize: Size? = null
        for ((i, size) in candidates.withIndex()) {
            put("   [${i + 1}/${candidates.size}] ${size.width}x${size.height}")
            if (captureOnce(dev, size)) {
                okSize = size
                break
            }
        }
        runCatching { dev.close() }

        if (okSize == null) {
            put("④ ❌ 所有候选尺寸都拿不到帧 → 指向小米对 SystemUI 的取流策略，而非尺寸")
        } else {
            put("④ ✅ 取流通路成立，可用尺寸=${okSize.width}x${okSize.height}")
        }
    }

    /**
     * 打开前摄一次。
     * ★ 超时后若设备**迟到打开**，必须当场 close —— 否则相机被永久攥住并自我毒化（实测踩过）。
     */
    private fun openOnce(cm: CameraManager, frontId: String): CameraDevice? {
        // ★★ 回调 Handler 必须用**主线程**，绝不能用"当前线程"。
        //   实测踩过的死锁：如果把回调投到 worker 线程自己的 Looper，
        //   又在同一个线程上 latch.await() 阻塞等回调 —— 线程被自己堵死，
        //   回调永远进不来，只能等超时解锁后才"迟到到达"
        //   （日志原样：请求 → 15000ms 超时 → 17ms 后 onOpened 迟到）。
        //   同理，凡是"阻塞等回调"的写法，回调线程必须与阻塞线程**分离**。
        val h = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        var dev: CameraDevice? = null
        var note = "❌ 无回调"
        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (abandoned.get()) {
                    // 我们早已超时放弃 → 迟到的设备直接关掉，绝不留着
                    Log.w(TAG, "⚠️ 相机迟到打开（已超时放弃）→ 立即关闭，避免攥住设备")
                    runCatching { d.close() }
                    return
                }
                dev = d; note = "✅ onOpened"; latch.countDown()
            }

            override fun onDisconnected(d: CameraDevice) {
                note = "⚠️ onDisconnected"; runCatching { d.close() }; latch.countDown()
            }

            override fun onError(d: CameraDevice, error: Int) {
                note = "❌ onError=$error（${errorName(error)}）"
                runCatching { d.close() }; latch.countDown()
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val syncExc = runCatching { cm.openCamera(frontId, cb, h) }.exceptionOrNull()
        if (syncExc != null) {
            put("   ❌ openCamera 同步异常：${syncExc.javaClass.name}: ${syncExc.message}")
            return null
        }
        if (!latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            put("   ❌ 打开超时（${OPEN_TIMEOUT_MS}ms）")
            return null
        }
        put("   $note 开相机耗时=${SystemClock.elapsedRealtime() - t0}ms")
        return dev
    }

    /** 在已打开的设备上试一次会话 + 取首帧 */
    private fun captureOnce(dev: CameraDevice, size: Size): Boolean {
        // 同 openOnce：回调走主线程，阻塞等待在 worker 线程上，两者必须分离
        val h = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        val frameLatch = CountDownLatch(1)
        var frameNote = "❌ 无帧"
        reader.setOnImageAvailableListener({ r ->
            runCatching {
                val img = r.acquireLatestImage()
                if (img != null) {
                    frameNote = "✅ 首帧 ${img.width}x${img.height} ts=${img.timestamp}"
                    img.close()
                }
            }.onFailure { frameNote = "❌ acquire 异常 ${it.javaClass.simpleName}" }
            if (frameLatch.count > 0) frameLatch.countDown()
        }, h)

        val sessionLatch = CountDownLatch(1)
        var sessionNote = "❌ 会话无回调（静默卡死）"
        val sessCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                sessionNote = "✅ onConfigured"
                runCatching {
                    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    req.addTarget(reader.surface)
                    s.setRepeatingRequest(req.build(), null, h)
                }.onFailure { sessionNote += " / ❌ setRepeating ${it.javaClass.simpleName}" }
                sessionLatch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionNote = "❌ onConfigureFailed"; sessionLatch.countDown()
            }
        }

        val syncExc = runCatching { dev.createCaptureSession(listOf(reader.surface), sessCb, h) }
            .exceptionOrNull()
        if (syncExc != null) {
            put("      ❌ createCaptureSession 同步异常：${syncExc.javaClass.name}: ${syncExc.message}")
            runCatching { reader.close() }
            return false
        }
        if (!sessionLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            put("      $sessionNote（${SESSION_TIMEOUT_MS}ms）")
            runCatching { reader.close() }
            return false
        }
        put("      $sessionNote")
        val got = frameLatch.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        put("      $frameNote 超时=${!got}")
        runCatching { reader.close() }
        return got
    }

    // ==================================================================== ⑦ 传感器

    private fun probeSensor(ctx: Context) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val s = runCatching { sm?.getDefaultSensor(SENSOR_DEVICE_ORIENTATION) }.getOrNull()
        put("⑦ device_orientation=${s?.name ?: "❌ 不可用"}")
        if (s == null || sm == null) return
        val latch = CountDownLatch(1)
        var note = "无回调（静止时属正常）"
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                note = "✅ code=${e.values.firstOrNull()}"
                if (latch.count > 0) latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val reg = runCatching { sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL) }
            .getOrDefault(false)
        put("   registerListener=$reg")
        if (reg) {
            put("   回调=$note")
            runCatching { sm.unregisterListener(l) }
        }
        // 不阻塞等待：静止时收不到回调是正常的
    }

    // ==================================================================== ⑧ ML Kit

    /**
     * ★ 这是「引擎能否跑在 SystemUI 里」的**一票否决项**。
     *
     * 引擎的人脸检测依赖 ML Kit，而 ML Kit 有两个只在「它自己那个进程」里才天然成立的前提。
     * 下面两条都是**反编译字节码确认**过的，不是推测：
     *
     *  1) **上下文未初始化**：`MlKitContext` 平时由本 App 清单里的 `MlKitInitProvider`
     *     在进程启动时初始化（它的 `onCreate()` 调 `MlKitContext.zza(getContext())`）；
     *     SystemUI 的清单里没有这个 Provider ⇒ SystemUI 进程里必须我们手动初始化。
     *     - 好消息：`MlKitContext.initializeIfNeeded(Context)` 是 **public** 的，不用反射；
     *     - 关键：`zzb()` 内部走 `ComponentDiscovery.forContext(上下文, MlKitComponentDiscoveryService)`
     *       —— 组件列表是从**传入 context 所属包的清单**里查这个 Service 查出来的，
     *       传 SystemUI 的上下文会一个组件都发现不到 ⇒ **必须传本 App 的上下文**；
     *     - 还得再包一层：`createPackageContext()` 造出的 Context，其
     *       `getApplicationContext()` 返回 **null**（`LoadedApk.mApplication` 只在真的创建过
     *       Application 之后才非空），而 ML Kit 多处 `checkNotNull(context.getApplicationContext(), …)`
     *       ⇒ 用 [HostEnv.AppCtxShim] 把 `getApplicationContext()` 强制指向自己。
     *
     *  2) **native 库加载不了**（真正的拦路虎）：`ThickFaceDetectorCreator` 的
     *     **静态初始化块**里就一句 `System.loadLibrary("face_detector_v2_jni")`
     *     （8.5MB，人脸模型就在这个 so 里）。而 `System.loadLibrary` 只搜
     *     「调用方类的加载器」+ `java.library.path`，在 SystemUI 进程里这两个都是
     *     LSPosed 模块加载器 / `/system/lib64`，**指不到我们 App 的 so 目录**。
     *     ⚠️ 静态初始化块**每个类加载器只执行一次**：失败了该类就被标记为 erroneous，
     *     同进程内再试只会得到 `NoClassDefFoundError`。所以预加载必须在**第一次**碰 ML Kit
     *     之前做完，不能"失败了再补救"。
     *     ⇒ 真正有效的是 ⑤ 里的**绝对路径预加载**（[HostEnv.preloadNativeLibs]）。
     *       ⚠️ 实测 [HostEnv.patchLibraryPath] 改完 `findLibrary` **仍返回 null** ——
     *       它不是生效原因，保留只为诊断与兜底。
     *
     * ⇒ 本项不只报"成功/失败"，而是把**可判定的证据**打出来（哪个加载器、
     *   补丁前后 `findLibrary` 各返回什么），最后真跑一次 `FaceDetector.process()`
     *   （空白位图，检出 0 张脸也算通过 —— 要验的是 native 模型能不能起来，不是识别准不准）。
     */
    private fun probeMlKit(hostCtx: Context, appCtx: Context?) {
        put("⑧ java.library.path=${HostEnv.libraryPath()}")
        put("   本 App nativeLibraryDir=${appCtx?.applicationInfo?.nativeLibraryDir}")

        // ---- 证据 A：ML Kit 的类由谁加载 ----
        val mlCls = runCatching { Class.forName("com.google.mlkit.vision.face.FaceDetection") }.getOrNull()
        if (mlCls == null) {
            put("   ❌ ML Kit 类加载不了 → 模块 dex 里没带上依赖，整条路不通")
            return
        }
        val cl = mlCls.classLoader
        put("   证据A：ML Kit 类的加载器 = ${cl?.javaClass?.name}")
        put("   证据B：补丁前 findLibrary(face_detector_v2_jni) → ${HostEnv.findLibrary(cl)}")

        // ---- 打补丁：把本 App 的 so 目录塞进模块加载器的 native 搜索路径 ----
        //  ⚠️ 实测这层补丁**改不动 findLibrary 的返回值**。真正让 ML Kit 起来的是
        //     ⑤ 里的绝对路径预加载。这里保留只为诊断与兜底 —— 详见 HostEnv 的类注释。
        val libDir = HostEnv.nativeLibDir(appCtx)
        if (libDir == null) {
            put("   ❌ 拿不到本 App nativeLibraryDir → 无法打补丁")
        } else {
            put("   打补丁：目标 so 目录=${libDir.absolutePath}（存在=${libDir.isDirectory}）")
            HostEnv.patchLibraryPath(cl, libDir).forEach { put("      $it") }
            put("   证据B'：补丁后 findLibrary(face_detector_v2_jni) → ${HostEnv.findLibrary(cl)}")
        }

        // ---- 实测：初始化 + 真跑一次 ----
        put("   MlKitContext.initializeIfNeeded(本App上下文) → ${HostEnv.initMlKit(hostCtx, appCtx)}")

        put("   FaceDetector.process → ${runFaceDetector()}")
    }

    /** 真跑一次人脸检测。★ 回调走 ML Kit 默认的主线程投递，本函数跑在 worker 线程上，两者分离（防自死锁） */
    private fun runFaceDetector(): String {
        return runCatching {
            val opts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(opts)
            try {
                val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var note = "❌ 20s 内无回调"
                detector.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { faces ->
                        note = "✅ 模型可运行（空白图检出 ${faces.size} 张脸，应为 0）"
                        latch.countDown()
                    }
                    .addOnFailureListener { e ->
                        note = "❌ ${e.javaClass.name}: ${e.message}"
                        latch.countDown()
                    }
                latch.await(20, TimeUnit.SECONDS)
                bmp.recycle()
                note
            } finally {
                runCatching { detector.close() }
            }
        }.getOrElse { "❌ 同步异常 ${it.javaClass.name}: ${it.message}" }
    }

    // ==================================================================== 工具

    private fun readTrim(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("读取失败")

    private fun errorName(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "相机已被占用"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "达到最大相机数"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "相机被策略禁用"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "设备级致命错误"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "相机服务错误"
        else -> "未知"
    }
}

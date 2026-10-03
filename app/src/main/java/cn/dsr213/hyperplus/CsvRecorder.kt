package cn.dsr213.hyperplus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把每帧结果落成 CSV。
 *
 * 为什么必须落盘：**"判定准不准"唯一可量化的验收方式**就是事后看曲线 ——
 * 人盯着屏幕看抖动，既记不住也没法复现。落盘后可以用 Excel/Python 直接算
 * 「切换时刻 vs 真实朝向改变时刻」的延迟，以及「稳定期内的抖动次数」。
 *
 * 写盘策略：内存缓冲 + 每 N 行或超时 flush。绝不在分析线程上做同步 IO 阻塞太久。
 */
class CsvRecorder(private val ctx: Context) {

    private val sb = StringBuilder(8192)
    private var file: File? = null
    private var writer: FileWriter? = null
    @Volatile private var recording = false
    @Volatile private var rows = 0

    val isRecording: Boolean get() = recording
    val rowCount: Int get() = rows
    val currentFile: File? get() = file

    fun start(): File {
        stop()
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "facerotate_$stamp.csv")
        file = f
        writer = FileWriter(f, false)
        writer?.write(HEADER + "\n")
        writer?.flush()
        sb.setLength(0)
        rows = 0
        recording = true
        Log.i(FaceAnalyzer.TAG, "recording -> ${f.absolutePath}")
        return f
    }

    fun append(row: String) {
        if (!recording) return
        sb.append(row).append('\n')
        rows++
        if (sb.length > 6000) flushBuffer()
    }

    private fun flushBuffer() {
        val w = writer ?: return
        if (sb.isEmpty()) return
        runCatching { w.write(sb.toString()); w.flush() }
            .onFailure { Log.w(FaceAnalyzer.TAG, "csv write failed: ${it.message}") }
        sb.setLength(0)
    }

    fun stop() {
        if (recording) {
            flushBuffer()
            runCatching { writer?.flush(); writer?.close() }
            recording = false
            Log.i(FaceAnalyzer.TAG, "recording stopped, rows=$rows")
        }
        writer = null
    }

    companion object {
        /** ⚠️ 必须与 AdaptiveEngine.csvRow() 的列顺序逐列一致，否则事后分析会错位。
         *  也与 2026-09-25 那批历史实测 CSV 保持一致，分析脚本可直接复用。
         *
         *  `raw_tilt`（2026-09-25 追加在**末尾**，不影响前 18 列的既有分析脚本）：
         *  采纳角度时脸"看起来"歪了多少度。> 45 说明这一帧是勉强认出来的，
         *  用来验证「摆得最正才采纳」这条规则是否真的把角度还原稳住了。 */
        val HEADER = "at_ms,euler_z,eye_roll,face_count,face_area,detect_ms," +
                "rot_deg,sys_rot,extra_rot,tried,interval_ms," +
                "smoothed,norm,decided,state,deviation,low_conf,err,raw_tilt"
    }
}

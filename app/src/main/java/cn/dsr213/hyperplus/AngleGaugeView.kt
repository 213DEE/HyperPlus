package cn.dsr213.hyperplus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 角度盘：把「人脸 roll」画成指针，4 个 90° 扇区标注方向。
 *
 * 为什么要画盘而不是只给数字：判定逻辑的滞回/相位是**角度关系**问题，
 * 数字看不出「在边界附近来回跳」，盘一眼就能看出来。这是调试这个算法最快的视图。
 */
class AngleGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 归一化角度（0..360，0 = 竖直正持），NaN = 未知 */
    var angle: Float = Float.NaN
        set(v) { field = v; invalidate() }

    /** 原始 eulerZ，用另一根指针画出来对比 */
    var rawAngle: Float = Float.NaN
        set(v) { field = v; invalidate() }

    /** 当前判定方向 0..3，-1 = 未知。⚠️ 不能叫 rotation —— View 自己就有个 float rotation */
    var direction: Int = -1
        set(v) { field = v; invalidate() }

    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF2A313C.toInt()
    }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF39414E.toInt()
    }
    private val pSector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x2234D399
    }
    private val pNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
        color = 0xFF34D399.toInt()
    }
    private val pNeedleRaw = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
        color = 0x88F59E0B.toInt()
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = 0xFF8B95A5.toInt(); textSize = 30f
    }
    private val pTextHi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = 0xFFE6EDF3.toInt(); textSize = 34f
        isFakeBoldText = true
    }

    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - 34f
        if (r <= 10f) return

        rect.set(cx - r, cy - r, cx + r, cy + r)

        // 目标方向扇区高亮（±22.5°）
        if (direction in 0..3) {
            val center = direction * 90f
            canvas.drawArc(rect, center - 22.5f - 90f, 45f, true, pSector)
        }

        canvas.drawCircle(cx, cy, r, pRing)
        canvas.drawCircle(cx, cy, r * 0.62f, pRing)

        // 刻度：每 15° 一根
        for (i in 0 until 24) {
            val a = Math.toRadians((i * 15).toDouble())
            val long = i % 6 == 0
            val r1 = r; val r2 = r - if (long) 22f else 10f
            val p = if (long) pRing else pTick
            canvas.drawLine(
                cx + (r1 * Math.sin(a)).toFloat() * 1f,
                cy - (r1 * Math.cos(a)).toFloat(),
                cx + (r2 * Math.sin(a)).toFloat(),
                cy - (r2 * Math.cos(a)).toFloat(),
                p
            )
        }

        // 方向标签（0 上 / 1 右 / 2 下 / 3 左）
        val labels = arrayOf("0", "1", "2", "3")
        for (i in 0..3) {
            val a = Math.toRadians((i * 90).toDouble())
            val lx = cx + (r * 0.80f * Math.sin(a)).toFloat()
            val ly = cy - (r * 0.80f * Math.cos(a)).toFloat() + 12f
            canvas.drawText(labels[i], lx, ly, if (i == direction) pTextHi else pText)
        }

        if (!angle.isNaN() && !angle.isFinite()) return
        if (!angle.isNaN()) {
            val a = Math.toRadians(angle.toDouble())
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + (r * Math.sin(a)).toFloat(), cy - (r * Math.cos(a)).toFloat())
            canvas.drawPath(path, pNeedle)
            canvas.drawCircle(cx, cy, 9f, pNeedle)
        }
        if (!rawAngle.isNaN() && rawAngle.isFinite()) {
            val a = Math.toRadians(rawAngle.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (r * 0.62f * Math.sin(a)).toFloat(),
                cy - (r * 0.62f * Math.cos(a)).toFloat(),
                pNeedleRaw
            )
        }
    }
}

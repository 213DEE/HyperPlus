package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * √2 —— 展开态内屏的**机身宽高比**（本机 2364/1672 = 1.4135 ≈ √2）。
 *
 * ⚠️ 它**只**用于 [PhoneGlyph] 的机身尺寸计算。
 * ⛔ 别把它卷进角度 / 方向换算里 —— 那是两件毫不相干的事。
 */
private const val SQRT2 = 1.41421356f

/**
 * 「手机」图标：把**握持姿势**画成一个手机轮廓，**左上角**那个圆点是摄像头。
 *
 * ============================ 画的是哪块屏 ============================
 * 画的是**展开后的内屏**（2026-10-04 用户指定），所以：
 *   · 机身宽高比 = **√2 : 1**（近方形）—— 本机内屏 1672×2364，2364/1672 = 1.4135 ≈ √2；
 *   · 摄像头在**左上角**（面板自然方向）—— 实测，见下面那张对照表。
 * ⛔ 别再画成顶部居中的细长直板机（那是外屏 / 普通手机的样子，用户会认错）。
 * ⛔ 机身里**不画任何内部纹样**（2026-10-04 第二轮，用户要求「去掉中间那条横线」）——
 *   只留「外框 + 左上角摄像头圆点」。内部小纹样在小尺寸下只会跟摄像头抢注意力。
 *
 * ============================ 它解决的是什么问题 ============================
 * 2026-10-04 用户原话：「**重新设计方向校准功能，用不明白，用户不会知道正确的竖屏方向是
 * 哪一边，也看不懂手机横屏、摄像头朝左是什么姿势**」。
 *
 * ⇒ 病根是**用文字描述姿势**（"竖屏正对自己" / "横屏、摄像头朝左"）—— 用户得先在脑子里
 *   把词翻译成动作，而"左 / 右"还会跟"转手机的方向"拧着（屏幕上内容转的方向恰好相反）。
 *   图形没有这个问题：**看图对姿势，是同一件事**，不需要翻译。
 *
 * ============================ 角度与落角 ============================
 * [deg] 用的是**引擎那套归一化角**（`LiveAngle.Sample.normDeg` 的同款语义）；
 * 调用点传的是 `USER_ROTATION 值 × 90`（见 [DirectionSection]）。四档的实际落角：
 *
 *   value 0 / [deg] 0   —— 竖屏                —— 摄像头 **左上**
 *   value 1 / [deg] 90  —— 横屏（摄像头朝左）  —— 摄像头 **左下**
 *   value 2 / [deg] 180 —— 倒屏                —— 摄像头 **右下**
 *   value 3 / [deg] 270 —— 横屏（摄像头朝右）  —— 摄像头 **右上**
 *
 * ★★ 也就是说：**摄像头画在哪边，就是用户真切看到的那个样子** ——
 *   这也是为什么它比"摄像头朝左"这五个字好懂。
 *
 * ★★★ 上表是**实测校正**过的（2026-10-04 第二轮），两条独立证据互相对上：
 *   ① 面板自然方向（`rotation=0`）时挖孔 = `Rect(0, 0 - 164, 140)` ⇒ **左上角**；
 *   ② 该屏正处 ROTATION_270（= 值 3）时，cutout 的 `boundingRect` =
 *      `Rect(2224, 0 - 2364, 164)`（显示坐标 2364×1672）⇒ 右 + 上 = **右上角**。
 *   来源：`adb shell dumpsys display`（`cutout` 字段）。
 *   ⚠️ 用户同日口述时把值 3 说成了"右下"（且值 2 也说了"右下"，两条重了）—— 与实测不符。
 *     **以实测为准**：图标跟真机长得一样才有意义。⛔ 别再凭"左右对称"把它改回右下。
 *
 * ⚠️ 旋转方向：本坐标系 0° 在正上方、**顺时针增大**，而 Compose 的 `rotate()` 正值是
 *   顺时针 ⇒ 这里取 `-deg`。⛔ 别把负号去掉（去掉之后 90° / 270° 会对着画反，
 *   而症状恰恰是"用户照着摆却总是反的"，最难查）。
 *
 * ⚠️ 四个方向选项共用本函数（`value * 90f`），⛔ 别在调用点自己加 180 / ±90 之类的修正。
 */
@Composable
internal fun PhoneGlyph(
    deg: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // 机身：**展开态内屏的比例 √2 : 1**（2026-10-04 用户指定）。
        //   ✅实证：本机内屏 1672×2364 ⇒ 2364/1672 = 1.4135 ≈ √2 ⇒ 展开后是**近方形**。
        //   ⛔ 别再画成细长直板机（旧值 `w = h * 0.56` ⇒ 宽高比 1.79，那是 16:9 手机）。
        //   ⚠️ `h` 留 0.88 余量是为了**横过来时"长边"能吃满画布**（旋转 90° 后占宽 = h）。
        val h = size.height * 0.88f
        val w = h / SQRT2
        val cx = size.width / 2f
        val cy = size.height / 2f
        val lineW = 2.2.dp.toPx()
        val stroke = Stroke(width = lineW)

        rotate(degrees = -deg, pivot = Offset(cx, cy)) {
            // 机身外框
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.20f, w * 0.20f),
                style = stroke,
            )
            // 摄像头：**左上角**（面板自然方向 = 0° 时的位置）。
            //   ★★ 这里只画 0° 的位置，其余三档由顶层 `rotate` 带过去 —— 落角见类注释那张表。
            //   ⚠️ 尺寸（2026-10-04 第二轮）：用户嫌"太大"，从 `w * 0.105f` 收到 `w * 0.070f`
            //      （38dp 画布上：直径 4.97dp → 3.31dp，约为机身线宽的 1.5 倍）。
            //      ⛔ 别再放大回 0.10 以上 —— 那个尺寸在小图标上像颗"痣"，不像挖孔。
            val camR = (w * 0.070f).coerceAtLeast(1.0.dp.toPx())
            // 圆心距机身边 = 半径 + 线宽（一半压线、一半留白）⇒ 圆点"贴着角"但不压框线。
            //   ⚠️ 写成与 camR 的**加法**而不是乘法：改半径时留白不会跟着一起缩水。
            val inset = camR + lineW
            drawCircle(
                color = color,
                radius = camR,
                center = Offset(cx - w / 2f + inset, cy - h / 2f + inset),
            )
            // ⛔ 机身里**不画任何内部纹样**（2026-10-04 第二轮，用户原话：「去掉中间那条横线」）。
            //   原来这里有一条短横线，本意是"示意画面正立、帮用户分清上下"，
            //   但在 38dp 的小图标上它更像**多出来的零件** —— 会跟摄像头圆点抢注意力，
            //   甚至让人以为那才是摄像头。现在只留「外框 + 左上角圆点」，一眼对上真机。
        }
    }
}

// ================================================================ 已删除
//
// 本文件原来还有一个 `PhoneAim`（瞄准器：虚线画目标姿势、实线画实时读数，
// 转手机对上即自动记录）。**2026-10-04 随手动校准整块删除** —— 用户拍板
// 「不要用摄像头来校准了，用重力传感器」，而引擎早已能拿重力自动纠正方向
// （`AdaptiveEngine.noteSignEvidence`），校准这件事本就不该让用户做。
// ⇒ 本文件现在**只**提供 [PhoneGlyph]，供 `DirectionSection` 的四选一配图使用。
// ⛔ 别把 `PhoneAim` 复活；理由见 `DirectionSection` 的类注释。

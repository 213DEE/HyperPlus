# -*- coding: utf-8 -*-
"""
HyperPlus 图标生成器（正式资源）

单一数据源：本文件里的常量 + 路径构造函数，同时产出
  1. design/icon/hyperplus_icon.svg          矢量母版（给人看 / 给设计工具改）
  2. res/drawable/ic_launcher_background.xml 自适应图标 - 背景层（不透明、满幅）
  3. res/drawable/ic_launcher_foreground.xml 自适应图标 - 前景层
  4. res/drawable/ic_launcher_monochrome.xml 自适应图标 - 单色层（Android 13+ 主题图标）
  5. res/mipmap-*/ic_launcher.png            传统图标（直角、无透明像素、满幅）
  6. res/mipmap-*/ic_launcher_round.png      传统圆形图标
  7. design/icon/ic_launcher_512.png         商店用 512（满幅、不预置圆角）

设计空间 = 108 x 108（= Android 自适应图标画布）。
前景内容全部落在安全区（居中 Ø66 圆，半径 33）内，因此**任何**遮罩
（圆形 / 圆角方 / 小米超椭圆）都不会裁到主体。

═══════════════════════════ 为什么是「齿轮 + 加号」（2026-10-03 重做）═══════════════════════════
用户判定上一版（人脸 + 旋转环）**不符合"HyperOS 增强模块"的定位**：
  · 语义错位 —— 画的是一张脸在转，读起来像相机 / 美颜 App。"人脸"是实现手段，不是身份；
    用一个功能去代表一个"增强模块"本身就窄（HyperPlus 后面还要装更多增强项）。
  · 材质掉队 —— HyperOS 3（2025-08）把图标改成「圆角方 + 精致渐变 + 更高对比 + 光影层次」，
    上一版是单层 35° 线性渐变、纯平、无光影。
  · 没有归属 —— 通用科技蓝，看不出是给 HyperOS 做的。
用户给的新方向（原话）：「既然是系统增强，那就用齿轮和加号作为主要元素」，配色选「深色沉稳」。
  ⇒ 齿轮 = 系统；加号 = 增强。两个元素各自可读，合起来就是"给系统做增强"。

═══════════════════════════ 层次（2026-10-03 第三轮，用户反馈「太扁平、没有层次」）═══════════════════════════
★★★ 硬约束：Android VectorDrawable **没有模糊 / 投影滤镜**。
    所以所有层次只能用矢量图也支持的手段表达 —— 这也是「PNG 与矢量图不分叉」的前提：
      ① 多层 path 叠加（厚度层 + 顶面层 + 倒角环）
      ② fillColor 用 gradient（linear / radial 都支持）
      ③ 半透明纯色 + 环状 path（模拟内阴影 / 倒角）
    任何一步用了模糊或滤镜，三份产物就会各画各的 ⇒ 预览失去意义。

层次由四件事构成（都写进 SVG / 矢量 / PNG 三路，公式完全相同）：
  背景  ① 对角基色渐变      ② 左上**角光源**（半径小、衰减快 ⇒ 只有角上一块亮）
        ③ 右下暗角          ④ 顶缘细高光（玻璃边）
        ⚠️ 第一版是"整块斜着变亮"，中灰面积太大 ⇒ 看着脏而平。光必须是**角光源**。
  前景  ① 厚度层（向右下偏移 1.9/2.3，自带渐变越外越暗 ⇒ 读成"厚度"而不是"重影"）
        ② 顶面（径向渐变：左上亮 → 边缘暗 ⇒ 球面凸起感）
        ③ 倒角环（齿轮外缘压暗 1.4，模拟边缘斜下去）
        ④ 加号纯白（比齿轮更亮 ⇒ 更靠前）
        ⑤ 细描边环（0.7，把图形与背景分开；浅色壁纸上尤其重要）
        ⚠️ 厚度层必须**同号偏移且带渐变**：第一版 dx=1.5/dy=2.6 + 纯中灰 #555C6A，
           读成了"另一个齿轮垫在下面"（重影），因为深背景 / 中灰厚度 / 白顶面三方打架。

规范依据（均已核对官方原文）：
  Android  m2.material.io/design/platform-guidance/android-icons.html
           108dp 画布 · 72dp 可见 · 安全区 Ø66 · keyline 圆 Ø52
  小米     dev.mi.com/xiaomihyperos/documentation/detail?pId=1511
           方形图标要直角、周围无透明像素、避免大圆角；xhdpi>=94px、xxhdpi>=130px
"""
import math
import os

from PIL import Image, ImageChops, ImageDraw

# ----------------------------------------------------------------- 设计常量
DS = 108.0
CT = 54.0
SS = 6                     # 超采样倍数（仅 PNG 输出用）
NG = 192                   # 渐变计算网格

# 齿轮（环状：齿轮体 + 中心圆孔）
GEAR_TEETH = 8
GEAR_R_ROOT = 23.4         # 齿根半径
GEAR_R_TIP = 28.8          # 齿顶半径
# ★ 孔半径 13.6 → 11.4（2026-10-03）：孔比加号大了一圈（半臂 9.2 ⇒ 缝 4.4），
#   孔里空出大片背景 ⇒ 无论怎么加内壁阴影都读成"齿轮里贴了一块深色圆盘"。
#   收小孔 + 放大加号（缝 1.6）⇒ 加号像嵌在齿轮中心，环体也更厚重。
GEAR_HOLE = 11.4           # 中心孔半径
# 齿形：齿顶只占 0.37 齿距、齿根占 0.49，齿侧面是径向线 —— 不做成方波
TOOTH_ROOT0, TOOTH_TIP0, TOOTH_TIP1, TOOTH_ROOT1 = 0.255, 0.315, 0.685, 0.745

# 加号（圆角十字，两条圆角矩形叠加）
PLUS_ARM = 9.8             # 半臂长
PLUS_T = 4.8               # 臂宽
PLUS_RAD = 1.92            # 端点圆角

# ---------------------------------------------------- 层次：背景
BG_TOP = (0x63, 0x6C, 0x7A)          # 左上（受光端）
BG_BOT = (0x13, 0x14, 0x19)          # 右下（背光端）
KEY_X, KEY_Y = 16.0, 12.0            # 角光源位置（左上角）
KEY_R = 70.0                         # 半径
# ★ 角光源的衰减**不能用线性**：线性会让亮部铺满半个图标（看着过曝、发灰）。
#   用分段线性折线逼近幂曲线 —— SVG 的多 stop / Android 的多 item / PNG 的分段插值
#   三方都能表达**同一条折线**，不会分叉。
KEY_STOPS = ((0.0, 0.50), (0.35, 0.16), (0.70, 0.04), (1.0, 0.0))
VIG_X, VIG_Y = 84.0, 92.0            # 暗角中心（右下）
VIG_R = 88.0
VIG_STOPS = ((0.0, 0.0), (0.45, 0.10), (1.0, 0.44))
RIM_A, RIM_H = 0.12, 1.3             # 顶缘高光（玻璃边）

# ---------------------------------------------------- 层次：前景
THICK_DX, THICK_DY = 2.1, 2.6        # 厚度层偏移（光从左上来 ⇒ 右下露厚度）
THICK_RGB0 = (0x4A, 0x51, 0x5E)      # 厚度层渐变起点（近端）
THICK_RGB1 = (0x28, 0x2C, 0x35)      # 厚度层渐变终点（远端，越外越暗）
PLUS_THICK_RGB = (0x6E, 0x75, 0x84)  # 加号厚度（比齿轮厚度的近端亮一档）
THICK_G0 = (CT - 30, CT - 30)        # 厚度渐变的两个端点（设计坐标）
THICK_G1 = (CT + 34, CT + 34)
PLUS_THICK_KX, PLUS_THICK_KY = 0.6, 0.7   # 加号厚度的偏移比例

SPH_CX, SPH_CY, SPH_R = 43.0, 39.0, 64.0  # 顶面"球面"光源
TOP_RGB0 = (255, 255, 255)
TOP_RGB1 = (0xC9, 0xCF, 0xDC)

BEVEL_W = 1.4                        # 外缘倒角环宽度
# ★ 轮廓光（2026-10-03）：倒角环不能填"纯黑"—— 那只是一圈死边。
#   真实物体边缘是**受光侧亮、背光侧暗**：左上一条白高光，右下一条暗影，
#   中间透明过渡。矢量图完全可以表达（渐变填充 + 透明 stop），所以照做。
#   格式：((offset, (r,g,b), alpha), ...)
EDGE_STOPS = ((0.0, (255, 255, 255), 0.72), (0.40, (255, 255, 255), 0.0),
              (0.60, (0, 0, 0), 0.0), (1.0, (0, 0, 0), 0.46))
EDGE_G0, EDGE_G1 = (10.0, 10.0), (98.0, 98.0)   # 轮廓光的渐变轴（左上 → 右下）
# ★ 孔壁环：只压外缘还不够 —— 孔会读成"贴了一块深色圆片"。孔边再压一圈才有"洞"感。
HOLE_BEVEL_W, HOLE_BEVEL_A = 1.1, 0.34
# ★ 孔内深度阴影：孔里露出的是背景色，而背景是**均匀的** ⇒ 看着就是一块圆盘。
#   叠一层径向压暗（中心最深、到孔边归零）⇒ 孔才有纵深。
HOLE_SHADOW_R = GEAR_HOLE + 1.0
HOLE_SHADOW_STOPS = ((0.0, 0.42), (0.5, 0.22), (0.85, 0.08), (1.0, 0.0))
STROKE_W, STROKE_A = 0.7, 0.373      # 细描边环：宽度 / 黑色 alpha

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
DESIGN = HERE


# ----------------------------------------------------------------- 几何
def pt(cx, cy, r, a):
    return (cx + r * math.cos(math.radians(a)), cy - r * math.sin(math.radians(a)))


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def circle_sub(cx, cy, r):
    """用相对圆弧画圆 —— SVG 与 Android pathData 同一套语法"""
    return ("M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z"
            % (f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r)))


def _gear_points_raw(cx, cy):
    pts = []
    P = 360.0 / GEAR_TEETH
    for i in range(GEAR_TEETH):
        a0 = i * P
        for frac, r in ((TOOTH_ROOT0, GEAR_R_ROOT), (TOOTH_TIP0, GEAR_R_TIP),
                        (TOOTH_TIP1, GEAR_R_TIP), (TOOTH_ROOT1, GEAR_R_ROOT)):
            pts.append(pt(cx, cy, r, a0 + frac * P))
    return pts


def gear_points(dx=0.0, dy=0.0, shrink=0.0):
    """
    齿轮轮廓点。

    [shrink] 内缩量：把整圈按比例收 [shrink]（齿顶半径 → GEAR_R_TIP - shrink），
    同时中心孔**变大** [shrink]。这个"收外圈 + 放内孔"的组合让
    `轮廓 - 内缩轮廓` 恰好等于"沿整个轮廓的一条等宽环"（倒角环就靠它）。
    """
    pts = _gear_points_raw(CT + dx, CT + dy)
    if not shrink:
        return pts
    k = 1.0 - shrink / GEAR_R_TIP
    cx, cy = CT + dx, CT + dy
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in pts]


def gear_hole_r(shrink=0.0):
    return GEAR_HOLE + shrink


def gear_shape_path(dx=0.0, dy=0.0, shrink=0.0):
    """齿轮体 + 中心孔：一条 pathData，配 fillType=evenOdd（孔靠 evenOdd 挖出来）"""
    pts = gear_points(dx, dy, shrink)
    s = "M%s,%s" % (f(pts[0][0]), f(pts[0][1]))
    s += "".join(" L%s,%s" % (f(x), f(y)) for x, y in pts[1:])
    s += " Z " + circle_sub(CT + dx, CT + dy, gear_hole_r(shrink))
    return s


def bevel_path(w):
    """
    沿齿轮轮廓的等宽环（宽 [w]），用于倒角 / 描边。

    两条子路径叠起来配 evenOdd：外轮廓 + 内缩 [w] 的轮廓。
      · 环带（外轮廓内、内缩轮廓外）→ 穿越 1 次 → 填充
      · 齿轮体内部 / 孔内          → 穿越 2 次 → 不填充
    与 PNG 端 `gear_mask() - gear_mask(shrink=w)` 数学等价（PNG 用 ImageChops 相减）。
    """
    a = gear_shape_path()
    pts = gear_points(0, 0, w)
    b = "M%s,%s" % (f(pts[0][0]), f(pts[0][1]))
    b += "".join(" L%s,%s" % (f(x), f(y)) for x, y in pts[1:])
    b += " Z " + circle_sub(CT, CT, gear_hole_r(w))
    return a + " " + b


def hole_ring_path(w):
    """
    孔壁环（半径 GEAR_HOLE ~ GEAR_HOLE+w）：两条同心圆，配 evenOdd 得到环带。
    与 PNG 端 `[circle(hole+w) - circle(hole)]` 等价。
    """
    return circle_sub(CT, CT, GEAR_HOLE + w) + " " + circle_sub(CT, CT, GEAR_HOLE)


def rrect_path(x0, y0, x1, y1, r):
    """圆角矩形（屏幕坐标顺时针）—— SVG d 与 Android pathData 通用"""
    return ("M%s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s H%s "
            "A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s Z"
            % (f(x0 + r), f(y0), f(x1 - r),
               f(r), f(r), f(x1), f(y0 + r),
               f(y1 - r),
               f(r), f(r), f(x1 - r), f(y1),
               f(x0 + r),
               f(r), f(r), f(x0), f(y1 - r),
               f(y0 + r),
               f(r), f(r), f(x0 + r), f(y0)))


def plus_paths(dx=0.0, dy=0.0):
    """加号 = 两条圆角矩形（各自是闭合子路径，靠覆盖取并集）"""
    a, t, r = PLUS_ARM, PLUS_T, PLUS_RAD
    cx, cy = CT + dx, CT + dy
    return (rrect_path(cx - a, cy - t / 2, cx + a, cy + t / 2, r),
            rrect_path(cx - t / 2, cy - a, cx + t / 2, cy + a, r))


# ----------------------------------------------------------------- SVG 母版
def _stops(stops, rgb, indent="      "):
    """折线 stop 列表（SVG 多 stop / Android 多 item / PNG 分段插值 —— 同一条折线）"""
    hexc = "%02X%02X%02X" % tuple(rgb)
    return ['%s<stop offset="%s" stop-color="#%s" stop-opacity="%s"/>'
            % (indent, f(o), hexc, f(a)) for o, a in stops]


def _stops_rgba(stops, indent="      "):
    """同上，但**颜色也随折线变**（轮郭光：左上白高光 → 右下暗影）"""
    return ['%s<stop offset="%s" stop-color="#%02X%02X%02X" stop-opacity="%s"/>'
            % (indent, f(o), c[0], c[1], c[2], f(a)) for o, c, a in stops]


def write_svg():
    hb, vb = plus_paths()
    hbt, vbt = plus_paths(THICK_DX * PLUS_THICK_KX, THICK_DY * PLUS_THICK_KY)
    bevel, stroke = bevel_path(BEVEL_W), bevel_path(STROKE_W)
    hole = hole_ring_path(HOLE_BEVEL_W)

    L = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">',
         '  <defs>',
         '    <!-- 背景①：对角基色（左上受光 → 右下背光） -->',
         '    <linearGradient id="bg" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">',
         '      <stop offset="0" stop-color="#%s"/>' % hx(BG_TOP),
         '      <stop offset="1" stop-color="#%s"/>' % hx(BG_BOT),
         '    </linearGradient>',
         '    <!-- 背景②：左上角光源。★ 折线衰减（不是线性）：线性会让亮部铺满半个图标 -->',
         '    <radialGradient id="key" cx="%s" cy="%s" r="%s" gradientUnits="userSpaceOnUse">'
         % (f(KEY_X), f(KEY_Y), f(KEY_R))]
    L += _stops(KEY_STOPS, (255, 255, 255))
    L += ['    </radialGradient>',
          '    <!-- 背景③：右下暗角（纵深） -->',
          '    <radialGradient id="vig" cx="%s" cy="%s" r="%s" gradientUnits="userSpaceOnUse">'
          % (f(VIG_X), f(VIG_Y), f(VIG_R))]
    L += _stops(VIG_STOPS, (0, 0, 0))
    L += ['    </radialGradient>',
          '    <!-- 前景：厚度层渐变（越靠外越暗 ⇒ 没入背景，而不是"贴一片灰"） -->',
          '    <linearGradient id="thickG" x1="%s" y1="%s" x2="%s" y2="%s" gradientUnits="userSpaceOnUse">'
          % (f(THICK_G0[0]), f(THICK_G0[1]), f(THICK_G1[0]), f(THICK_G1[1])),
          '      <stop offset="0" stop-color="#%s"/>' % hx(THICK_RGB0),
          '      <stop offset="1" stop-color="#%s"/>' % hx(THICK_RGB1),
          '    </linearGradient>',
          '    <!-- 前景：顶面球面渐变（左上亮 → 边缘暗 ⇒ 凸起） -->',
          '    <radialGradient id="top" cx="%s" cy="%s" r="%s" gradientUnits="userSpaceOnUse">'
          % (f(SPH_CX), f(SPH_CY), f(SPH_R)),
          '      <stop offset="0" stop-color="#%s"/>' % hx(TOP_RGB0),
          '      <stop offset="1" stop-color="#%s"/>' % hx(TOP_RGB1),
          '    </radialGradient>',
          '    <!-- 前景：孔内深度阴影（孔心最深 → 孔边归零 ⇒ 孔读成"洞"） -->',
          '    <radialGradient id="holeSh" cx="%s" cy="%s" r="%s" gradientUnits="userSpaceOnUse">'
          % (f(CT), f(CT), f(HOLE_SHADOW_R))]
    L += _stops(HOLE_SHADOW_STOPS, (0, 0, 0))
    L += ['    </radialGradient>',
          '    <!-- 前景：轮廓光（左上白高光 → 中间透明 → 右下暗影）—— 纯黑一圈只是死边 -->',
          '    <linearGradient id="edgeG" x1="%s" y1="%s" x2="%s" y2="%s" gradientUnits="userSpaceOnUse">'
          % (f(EDGE_G0[0]), f(EDGE_G0[1]), f(EDGE_G1[0]), f(EDGE_G1[1]))]
    L += _stops_rgba(EDGE_STOPS)
    L += ['    </linearGradient>',
          '  </defs>',
          '  <rect width="108" height="108" fill="url(#bg)"/>',
          '  <rect width="108" height="108" fill="url(#key)"/>',
          '  <rect width="108" height="108" fill="url(#vig)"/>',
          '  <rect width="108" height="%s" fill="#FFFFFF" opacity="%s"/>' % (f(RIM_H), f(RIM_A)),
          '  <!-- ① 厚度层：向右下偏移（光从左上来 ⇒ 厚度只在右下露） -->',
          '  <path d="%s" fill="url(#thickG)" fill-rule="evenodd"/>'
          % gear_shape_path(THICK_DX, THICK_DY),
          '  <path d="%s" fill="url(#thickG)" fill-rule="evenodd"/>' % hbt,
          '  <path d="%s" fill="url(#thickG)" fill-rule="evenodd"/>' % vbt,
          '  <!-- ② 孔内深度阴影（孔里露出的是背景，叠一层径向压暗才有纵深） -->',
          '  <path d="%s" fill="url(#holeSh)" fill-rule="evenodd"/>' % circle_sub(CT, CT, HOLE_SHADOW_R),
          '  <!-- ③ 顶面（球面渐变） -->',
          '  <path d="%s" fill="url(#top)" fill-rule="evenodd"/>' % gear_shape_path(),
          '  <!-- ④ 外缘轮廓光（左上白高光 / 右下暗影） -->',
          '  <path d="%s" fill="url(#edgeG)" fill-rule="evenodd"/>' % bevel,
          '  <!-- ⑤ 孔壁（孔边压暗 ⇒ 孔读成"凹下去的洞"） -->',
          '  <path d="%s" fill="#000000" opacity="%s" fill-rule="evenodd"/>' % (hole, f(HOLE_BEVEL_A)),
          '  <!-- ⑥ 加号：纯白 ⇒ 比齿轮更靠前 -->',
          '  <path d="%s" fill="#FFFFFF" fill-rule="evenodd"/>' % hb,
          '  <path d="%s" fill="#FFFFFF" fill-rule="evenodd"/>' % vb,
          '  <!-- ⑦ 细描边：与背景分离（浅色壁纸上尤其重要） -->',
          '  <path d="%s" fill="#000000" opacity="%s" fill-rule="evenodd"/>' % (stroke, f(STROKE_A)),
          '</svg>', '']
    p = os.path.join(DESIGN, "hyperplus_icon.svg")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L))
    print("SVG   ", p)


def hx(rgb):
    return "%02X%02X%02X" % tuple(rgb)


# ----------------------------------------------------------------- Android 矢量图
def write_vector(path, body):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 由 design/icon/gen_icon.py 生成，不要手改 -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n%s</vector>\n' % body)
    print("VECTOR", path)


def _grad_path(fill_xml, d):
    # ⚠️ fillType=evenOdd 是**必需**的：齿轮的孔与 bevel 环都靠"穿越次数"挖出来，
    #    默认的 nonZero 会把同向子路径填满 ⇒ 齿轮变实心圆盘、倒角环糊成一坨。
    return ('    <path android:fillType="evenOdd" android:pathData="%s">\n'
            '        <aapt:attr name="android:fillColor">\n%s'
            '        </aapt:attr>\n'
            '    </path>\n' % (d, fill_xml))


def _lin(x0, y0, x1, y1, c0, c1):
    return ('            <gradient\n'
            '                android:type="linear"\n'
            '                android:startX="%s" android:startY="%s"\n'
            '                android:endX="%s" android:endY="%s"\n'
            '                android:startColor="#%s"\n'
            '                android:endColor="#%s" />\n'
            % (f(x0), f(y0), f(x1), f(y1), c0, c1))


def _items(stops, rgb):
    """折线 → Android gradient 的 item 列表（颜色串 = AARRGGBB）"""
    h = "%02X%02X%02X" % tuple(rgb)
    return [(o, a8(a) + h) for o, a in stops]


def _items_rgba(stops):
    """同上，但颜色也随折线变（轮廓光）"""
    return [(o, a8(a) + "%02X%02X%02X" % tuple(c)) for o, c, a in stops]


def _lin_stops(p0, p1, items):
    """线性渐变 + 多 item（与 SVG 的 edgeG、PNG 的 linear_rgba_img 同一条折线）"""
    s = ('            <gradient\n'
         '                android:type="linear"\n'
         '                android:startX="%s" android:startY="%s"\n'
         '                android:endX="%s" android:endY="%s" >\n'
         % (f(p0[0]), f(p0[1]), f(p1[0]), f(p1[1])))
    for off, col in items:
        s += '                <item android:offset="%s" android:color="#%s" />\n' % (f(off), col)
    return s + '            </gradient>\n'


def _rad(cx, cy, r, items):
    """
    径向渐变。items = [(offset, "AARRGGBB"), ...]

    ★ 用**多 item** 表达折线衰减，与 SVG 的多 stop、PNG 的分段插值走同一条曲线
      （线性衰减会让亮部铺满半个图标 —— 这是第一版的教训）。
    """
    s = ('            <gradient\n'
         '                android:type="radial"\n'
         '                android:centerX="%s" android:centerY="%s"\n'
         '                android:gradientRadius="%s" >\n' % (f(cx), f(cy), f(r)))
    for off, col in items:
        s += '                <item android:offset="%s" android:color="#%s" />\n' % (f(off), col)
    return s + '            </gradient>\n'


def write_vectors():
    d = os.path.join(RES, "drawable")
    hb, vb = plus_paths()
    hbt, vbt = plus_paths(THICK_DX * PLUS_THICK_KX, THICK_DY * PLUS_THICK_KY)
    bevel, stroke = bevel_path(BEVEL_W), bevel_path(STROKE_W)
    hole = hole_ring_path(HOLE_BEVEL_W)

    # 背景层：满幅不透明 —— 满足小米「方形直角、周围无透明像素」
    #   四层：① 对角基色 ② 左上角光源 ③ 右下暗角 ④ 顶缘高光（与 PNG 同一条公式，见 make_bg）
    body = _grad_path(_lin(0, 0, 108, 108, "FF" + hx(BG_TOP), "FF" + hx(BG_BOT)),
                      "M0,0h108v108h-108z")
    body += _grad_path(_rad(KEY_X, KEY_Y, KEY_R, _items(KEY_STOPS, (255, 255, 255))),
                       "M0,0h108v108h-108z")
    body += _grad_path(_rad(VIG_X, VIG_Y, VIG_R, _items(VIG_STOPS, (0, 0, 0))),
                       "M0,0h108v108h-108z")
    body += ('    <path android:pathData="M0,0h108v%sh-108z" android:fillColor="#%sFFFFFF" />\n'
             % (f(RIM_H), a8(RIM_A)))
    write_vector(os.path.join(d, "ic_launcher_background.xml"), body)

    # 前景层：厚度层（右下偏移）→ 顶面（球面）→ 外缘倒角 → 孔壁 → 加号 → 细描边
    fg = _grad_path(_lin(THICK_G0[0], THICK_G0[1], THICK_G1[0], THICK_G1[1],
                         "FF" + hx(THICK_RGB0), "FF" + hx(THICK_RGB1)),
                    gear_shape_path(THICK_DX, THICK_DY))
    fg += ('    <path android:pathData="%s" android:fillColor="#FF%s" />\n' % (hbt, hx(THICK_RGB0)))
    fg += ('    <path android:pathData="%s" android:fillColor="#FF%s" />\n' % (vbt, hx(THICK_RGB0)))
    # 孔内深度阴影（与 SVG 的 #holeSh / PNG 的 radial_alpha_img 同一条折线）
    fg += _grad_path(_rad(CT, CT, HOLE_SHADOW_R, _items(HOLE_SHADOW_STOPS, (0, 0, 0))),
                     circle_sub(CT, CT, HOLE_SHADOW_R))
    fg += _grad_path(_rad(SPH_CX, SPH_CY, SPH_R,
                          [(0.0, "FF" + hx(TOP_RGB0)), (1.0, "FF" + hx(TOP_RGB1))]),
                     gear_shape_path())
    fg += _grad_path(_lin_stops(EDGE_G0, EDGE_G1, _items_rgba(EDGE_STOPS)), bevel)
    fg += ('    <path android:fillType="evenOdd" android:pathData="%s" android:fillColor="#%s000000" />\n'
           % (hole, a8(HOLE_BEVEL_A)))
    fg += ('    <path android:pathData="%s" android:fillColor="#FFFFFFFF" />\n' % hb)
    fg += ('    <path android:pathData="%s" android:fillColor="#FFFFFFFF" />\n' % vb)
    fg += ('    <path android:fillType="evenOdd" android:pathData="%s" android:fillColor="#%s000000" />\n'
           % (stroke, a8(STROKE_A)))
    write_vector(os.path.join(d, "ic_launcher_foreground.xml"), fg)

    # 单色层（颜色被系统忽略，只用 alpha）—— 刻意**不带层次**，系统会重着色
    mono = ('    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="%s" />\n'
            % gear_shape_path())
    mono += ('    <path android:fillColor="#FF000000" android:pathData="%s" />\n' % hb)
    mono += ('    <path android:fillColor="#FF000000" android:pathData="%s" />\n' % vb)
    write_vector(os.path.join(d, "ic_launcher_monochrome.xml"), mono)


def a8(a):
    """0..1 的 alpha → 两位十六进制"""
    return "%02X" % min(255, max(0, int(round(a * 255))))


def write_adaptive_xml():
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        d = os.path.join(RES, "mipmap-anydpi-v26")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name), "w", encoding="utf-8") as fh:
            fh.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
        print("ADAPT ", os.path.join(d, name))


# ----------------------------------------------------------------- PNG 输出
class Xf:
    def __init__(self, px, mode):
        self.win = DS if mode == "adaptive" else 72.0
        self.x0 = 0.0 if mode == "adaptive" else 18.0
        self.k = px * SS / self.win

    def p(self, x, y):
        return ((x - self.x0) * self.k, (y - self.x0) * self.k)

    def box(self, cx, cy, r):
        return [*self.p(cx - r, cy - r), *self.p(cx + r, cy + r)]

    def l(self, v):
        return v * self.k


def _window(mode):
    return (0.0, DS) if mode == "adaptive" else (18.0, 72.0)


def lerp(c0, c1, t):
    return tuple(c0[i] + (c1[i] - c0[i]) * t for i in range(3))


def pw(t, stops):
    """
    分段线性插值（折线）—— 与 SVG 的多 stop、Android 的多 item 是**同一条曲线**。
    ⚠️ 这里绝不能用"单段线性"：那会让角光源的亮部铺满半个图标（第一版的教训）。
    """
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    if t <= stops[0][0]:
        return stops[0][1]
    for i in range(1, len(stops)):
        o0, v0 = stops[i - 1]
        o1, v1 = stops[i]
        if t <= o1:
            k = (t - o0) / (o1 - o0) if o1 > o0 else 0.0
            return v0 + (v1 - v0) * k
    return stops[-1][1]


def make_bg(px, mode):
    """
    与矢量图的背景层**同一条公式**（保证 SVG / 矢量 / PNG 三格式不分叉）：
      ① 对角基色 → ② 左上角光源 → ③ 右下暗角 → ④ 顶缘高光
    """
    x0, win = _window(mode)
    img = Image.new("RGB", (NG, NG))
    o = img.load()
    for j in range(NG):
        yd = x0 + (j + 0.5) / NG * win
        for i in range(NG):
            xd = x0 + (i + 0.5) / NG * win
            # ① 对角基色（左上亮 → 右下暗）
            t = (xd + yd) / (2.0 * DS)
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            col = lerp(BG_TOP, BG_BOT, t)
            # ② 角光源：白色按 alpha 叠加（**折线**衰减 ⇒ 亮部集中在角上）
            ak = pw(math.hypot(xd - KEY_X, yd - KEY_Y) / KEY_R, KEY_STOPS)
            col = [col[c] + (255 - col[c]) * ak for c in range(3)]
            # ③ 暗角：黑色按 alpha 叠加
            av = pw(math.hypot(xd - VIG_X, yd - VIG_Y) / VIG_R, VIG_STOPS)
            col = [col[c] * (1.0 - av) for c in range(3)]
            o[i, j] = tuple(int(round(c)) for c in col)
    bg = img.convert("RGBA").resize((px * SS, px * SS), Image.LANCZOS)
    # ④ 顶缘高光
    rim = Image.new("RGBA", (px * SS, px * SS), (0, 0, 0, 0))
    ImageDraw.Draw(rim).rectangle(
        [0, 0, px * SS - 1, max(1, int(round(RIM_H / DS * px * SS))) - 1],
        fill=(255, 255, 255, int(round(RIM_A * 255))))
    bg = Image.alpha_composite(bg, rim)
    return bg.resize((px, px), Image.LANCZOS)


# ------------------------------------------------------------------ PNG 的前景（与矢量图逐层对应）
def gear_mask(px, mode, dx=0.0, dy=0.0, shrink=0.0):
    xf = Xf(px, mode)
    m = Image.new("L", (px * SS, px * SS), 0)
    d = ImageDraw.Draw(m)
    d.polygon([xf.p(*p) for p in gear_points(dx, dy, shrink)], fill=255)
    d.ellipse(xf.box(CT + dx, CT + dy, gear_hole_r(shrink)), fill=0)
    return m.resize((px, px), Image.LANCZOS)


def bevel_ring(px, mode, w):
    """与 bevel_path() 等价：外轮廓 - 内缩轮廓"""
    return ImageChops.subtract(gear_mask(px, mode), gear_mask(px, mode, shrink=w))


def hole_ring_mask(px, mode, w):
    """与 hole_ring_path() 等价：外圆 - 内圆（孔壁环）"""
    xf = Xf(px, mode)
    m = Image.new("L", (px * SS, px * SS), 0)
    d = ImageDraw.Draw(m)
    d.ellipse(xf.box(CT, CT, GEAR_HOLE + w), fill=255)
    d.ellipse(xf.box(CT, CT, GEAR_HOLE), fill=0)
    return m.resize((px, px), Image.LANCZOS)


def radial_alpha_img(px, mode, cx, cy, r, rgb, stops):
    """
    纯色 + 径向 alpha 渐变（折线）—— 与 SVG 的 #holeSh、矢量图的 radial gradient 同一曲线。
    用于"孔内深度阴影"这种"颜色固定、只有透明度在变"的层。
    """
    x0, win = _window(mode)
    a = Image.new("L", (NG, NG))
    o = a.load()
    for j in range(NG):
        yd = x0 + (j + 0.5) / NG * win
        for i in range(NG):
            xd = x0 + (i + 0.5) / NG * win
            o[i, j] = int(round(pw(math.hypot(xd - cx, yd - cy) / r, stops) * 255))
    out = Image.new("RGBA", (px, px), tuple(rgb) + (255,))
    out.putalpha(a.resize((px, px), Image.LANCZOS))
    return out


def interp_rgba(t, stops):
    """分段线性：在 ((offset, (r,g,b), alpha), ...) 折线上取颜色+透明度"""
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    if t <= stops[0][0]:
        _, c, a = stops[0]
        return c[0], c[1], c[2], a
    for i in range(1, len(stops)):
        o0, c0, a0 = stops[i - 1]
        o1, c1, a1 = stops[i]
        if t <= o1:
            k = (t - o0) / (o1 - o0) if o1 > o0 else 0.0
            return (c0[0] + (c1[0] - c0[0]) * k,
                    c0[1] + (c1[1] - c0[1]) * k,
                    c0[2] + (c1[2] - c0[2]) * k,
                    a0 + (a1 - a0) * k)
    _, c, a = stops[-1]
    return c[0], c[1], c[2], a


def linear_rgba_img(px, mode, stops, p0, p1):
    """颜色+透明度都随折线变的线性渐变 —— 与 SVG 的 #edgeG 同一条曲线（轮廓光用它）"""
    x0, win = _window(mode)
    img = Image.new("RGBA", (NG, NG))
    o = img.load()
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L2 = dx * dx + dy * dy
    for j in range(NG):
        yd = x0 + (j + 0.5) / NG * win
        for i in range(NG):
            xd = x0 + (i + 0.5) / NG * win
            r, g, b, a = interp_rgba(((xd - p0[0]) * dx + (yd - p0[1]) * dy) / L2, stops)
            o[i, j] = (int(round(r)), int(round(g)), int(round(b)), int(round(a * 255)))
    return img.resize((px, px), Image.LANCZOS)


def masked_mul(ci, m):
    """mask 与图自身 alpha **相乘**（不是覆盖）—— 渐变层自带 alpha 时必须用这个"""
    out = ci.convert("RGBA").copy()
    out.putalpha(ImageChops.multiply(out.getchannel("A"), m))
    return out


def circle_mask(px, mode, r):
    xf = Xf(px, mode)
    m = Image.new("L", (px * SS, px * SS), 0)
    ImageDraw.Draw(m).ellipse(xf.box(CT, CT, r), fill=255)
    return m.resize((px, px), Image.LANCZOS)


def plus_mask(px, mode, dx=0.0, dy=0.0):
    xf = Xf(px, mode)
    m = Image.new("L", (px * SS, px * SS), 0)
    d = ImageDraw.Draw(m)
    a, t, r = PLUS_ARM, PLUS_T, PLUS_RAD
    cx, cy = CT + dx, CT + dy
    d.rounded_rectangle([*xf.p(cx - a, cy - t / 2), *xf.p(cx + a, cy + t / 2)], radius=xf.l(r), fill=255)
    d.rounded_rectangle([*xf.p(cx - t / 2, cy - a), *xf.p(cx + t / 2, cy + a)], radius=xf.l(r), fill=255)
    return m.resize((px, px), Image.LANCZOS)


def linear_img(px, mode, rgb0, rgb1, p0, p1):
    x0, win = _window(mode)
    img = Image.new("RGB", (NG, NG))
    o = img.load()
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L2 = dx * dx + dy * dy
    for j in range(NG):
        yd = x0 + (j + 0.5) / NG * win
        for i in range(NG):
            xd = x0 + (i + 0.5) / NG * win
            t = ((xd - p0[0]) * dx + (yd - p0[1]) * dy) / L2
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            o[i, j] = tuple(int(round(c)) for c in lerp(rgb0, rgb1, t))
    return img.resize((px, px), Image.LANCZOS).convert("RGBA")


def radial_img(px, mode, cx, cy, r, rgb0, rgb1):
    x0, win = _window(mode)
    img = Image.new("RGB", (NG, NG))
    o = img.load()
    for j in range(NG):
        yd = x0 + (j + 0.5) / NG * win
        for i in range(NG):
            xd = x0 + (i + 0.5) / NG * win
            t = math.hypot(xd - cx, yd - cy) / r
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            o[i, j] = tuple(int(round(c)) for c in lerp(rgb0, rgb1, t))
    return img.resize((px, px), Image.LANCZOS).convert("RGBA")


def masked(ci, m):
    out = ci.convert("RGBA").copy()
    out.putalpha(m)
    return out


def solid(px, rgba):
    return Image.new("RGBA", (px, px), rgba)


def render(px, mode="adaptive", mono=False):
    WH = (255, 255, 255, 255)
    if mono:
        # 单色层：纯几何，不带层次（系统会整体重着色）
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        fg = Image.alpha_composite(fg, masked(solid(px, WH), gear_mask(px, mode)))
        fg = Image.alpha_composite(fg, masked(solid(px, WH), plus_mask(px, mode)))
        bg = Image.new("RGBA", (px, px), (108, 112, 118, 255))
        return Image.alpha_composite(bg, fg)

    fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    # ① 厚度层（右下偏移，自带渐变）
    tg = linear_img(px, mode, THICK_RGB0, THICK_RGB1, THICK_G0, THICK_G1)
    fg = Image.alpha_composite(fg, masked(tg, gear_mask(px, mode, THICK_DX, THICK_DY)))
    pg = linear_img(px, mode, PLUS_THICK_RGB, THICK_RGB1, THICK_G0, THICK_G1)
    fg = Image.alpha_composite(
        fg, masked(pg, plus_mask(px, mode, THICK_DX * PLUS_THICK_KX, THICK_DY * PLUS_THICK_KY)))
    # ② 孔内深度阴影（孔里露出的是背景色，而背景是均匀的 ⇒ 不压暗就只是一块"圆盘"）
    hs = radial_alpha_img(px, mode, CT, CT, HOLE_SHADOW_R, (0, 0, 0), HOLE_SHADOW_STOPS)
    hs.putalpha(ImageChops.multiply(hs.getchannel("A"), circle_mask(px, mode, HOLE_SHADOW_R)))
    fg = Image.alpha_composite(fg, hs)
    # ③ 顶面（球面径向渐变）
    top = radial_img(px, mode, SPH_CX, SPH_CY, SPH_R, TOP_RGB0, TOP_RGB1)
    fg = Image.alpha_composite(fg, masked(top, gear_mask(px, mode)))
    # ④ 外缘轮廓光（左上白高光 → 右下暗影；纯黑一圈只是死边）
    edge = linear_rgba_img(px, mode, EDGE_STOPS, EDGE_G0, EDGE_G1)
    fg = Image.alpha_composite(fg, masked_mul(edge, bevel_ring(px, mode, BEVEL_W)))
    # ⑤ 孔壁环
    fg = Image.alpha_composite(fg, masked(solid(px, (0, 0, 0, int(round(HOLE_BEVEL_A * 255)))),
                                           hole_ring_mask(px, mode, HOLE_BEVEL_W)))
    # ⑥ 加号顶面（纯白 ⇒ 比齿轮更靠前）
    fg = Image.alpha_composite(fg, masked(solid(px, WH), plus_mask(px, mode)))
    # ⑦ 描边环
    fg = Image.alpha_composite(fg, masked(solid(px, (0, 0, 0, int(round(STROKE_A * 255)))),
                                           bevel_ring(px, mode, STROKE_W)))
    return Image.alpha_composite(make_bg(px, mode), fg)


def circle_alpha(px):
    m = Image.new("L", (px * SS, px * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, px * SS - 1, px * SS - 1], fill=255)
    return m.resize((px, px), Image.LANCZOS)


def write_pngs():
    dens = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
    for name, px in dens:
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        render(px, mode="legacy").save(os.path.join(d, "ic_launcher.png"))
        rd = render(px, mode="legacy")
        rd.putalpha(circle_alpha(px))
        rd.save(os.path.join(d, "ic_launcher_round.png"))
        print("PNG   ", name, px)
    render(512, mode="adaptive").save(os.path.join(DESIGN, "ic_launcher_512.png"))
    print("PNG    512 store")


if __name__ == "__main__":
    write_svg()
    write_vectors()
    write_adaptive_xml()
    write_pngs()
    print("\nOK")

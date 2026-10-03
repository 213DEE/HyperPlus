package cn.dsr213.hyperplus.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页置顶的**捐赠卡片**：二维码直接摆出来 + 一键存相册。
 *
 * ============================ 为什么在这一页（2026-10-01）============================
 * 用户原话：「捐赠信息不要放到二三级菜单，直接在首页置顶，把二维码直接放出来，
 * 加上一键保存二维码到相册的功能」。
 *
 * ⇒ 打破「关于页」的收纳关系（原来入口在 `设置 → 关于 → 捐赠支持 → 弹窗`，点三层才看得到码）。
 *   ⚠️ 这与主页那条「只放入口、不放内容」的纪律是**冲突的**，但它是用户点名要的例外 ——
 *   捐赠码放在二级页里等于没人看得到。所以这里把「例外」写下来：
 *   **本页唯一直出内容的元素就是它，别再照这个先例往主页塞第二张内容卡。**
 *
 * ⚠️ 位置在**引擎状态那一行之上**：引擎状态行原本是第一行（理由见 [FunctionPage] 的类注释），
 *   现在被这张卡顶下去了。代价是"现在改的东西会不会生效"从第一眼变成第二眼可见 ——
 *   已知取舍，用户点名。
 *
 * ============================ 为什么"保存到相册"这件事需要单独做 ============================
 * 屏幕上那张码是**缩过的**（见 [QR_WIDTH]），而微信扫码要的是**清晰的码**。
 * 存进相册之后，用户可以打开大图、或者把码转发给别人 —— 那才是真正能扫的那一份。
 */
@Composable
internal fun DonateCard() {
    val ctx = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    // ⚠️ 这两条**必须先取出来**：下面 `Button(onClick = ...)` 的 lambda 不是 @Composable，
    //   在里面调 `stringResource` 编译不过（`onClick` 是普通 lambda）。
    val savedMsg = stringResource(R.string.donate_saved)
    val saveFailedMsg = stringResource(R.string.donate_save_failed)

    TextCard(title = stringResource(R.string.donate_title)) {
        // ★ 这行只装"码 + 一句话"，两样东西垂直居中对齐（像名片）。
        //   ⚠️ 按钮**不能**再放回这里（见下方中段的说明）—— 放回来卡片就又变成"三样挤左半边"。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.donate_wechat_qr),
                contentDescription = stringResource(R.string.donate_qr_desc),
                modifier = Modifier
                    .width(QR_WIDTH)
                    .height(QR_HEIGHT)
                    // 圆角只为好看：素材本身是"绿底整图"，方正地贴在卡片里会显得像贴歪了的截图。
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // ⚠️ 这句是**用户点名要的原话**（2026-10-01），别"帮他把话说体面"：
                    //   用户在文案上明确偏好大白话、自嘲式的真人语气，
                    //   ⛔ 不要润色成"支持开发者"这类营销腔。
                    text = stringResource(R.string.donate_pitch),
                    // 16sp + `onSurface`：这句是这张卡的主信息，跟下面的收费口径（13sp 灰）
                    // 必须拉开层级 —— 原来两者同为 13sp 灰字，扫过去分不出主次。
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(14.dp))
                // ============ 中段：操作 ============
                // ★★ 2026-10-01 第三轮。用户原话：「保存按钮不够美观，再优化一下」，追加澄清
                //   「**是捐赠卡片的布局不美观，不是样式**」。⇒ 问题不在按钮长什么样，在**它待在哪**。
                //
                //   试过、**否掉**的两个极端（都是实测截图后否掉的，别重复试）：
                //   ① 旧的 `TextButton`：灰底灰字 + 宽度自适应（只剩约 140dp），
                //      压在同色系卡片底上像"禁用中"，而且和文案一起悬在二维码右边中间。
                //   ② **整卡级的通栏按钮**：确实气派，但按钮独占一整行 ⇒ 卡片高到约 316dp
                //      （外屏可用的 51%），把「运行中」和所有功能入口全部挤出首屏 ——
                //      首页是**工具的功能目录**，不能变成捐赠页。
                //
                //   现在的位置：回到二维码右边这一栏，但**撑满整栏**（`fillMaxWidth`）。
                //   ⇒ 按钮宽度比①宽约四成、颜色是主色（页面最亮的一处）、和上面那句话左对齐成一根轴，
                //     而卡片高度只由二维码决定 ⇒ 不再多占一整行。
                Button(
                    onClick = {
                        saving = true
                        val ok = runCatching {
                            saveDrawableToGallery(ctx, R.drawable.donate_wechat_qr)
                        }.getOrDefault(false)
                        saving = false
                        toast(ctx, if (ok) savedMsg else saveFailedMsg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // ★ 防连点：保存是**写系统媒体库**的副作用，连点两次会插两条记录。
                    //   `saving` 挡住的是"上一次还没写完就又来一次"。
                    enabled = !saving,
                    // 主色填充（`buttonColorsPrimary`）：默认的 `TextButton` 是灰底灰字，
                    // 压在同色系的卡片底上几乎看不见，长得像"禁用中"。
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Download,
                        contentDescription = null, // 装饰性：按钮已有文字说明用途
                        // ⚠️ 必须显式给 tint：按钮是**主色底**，图标的默认色继承自
                        //   `LocalContentColor`，一旦 Button 没覆盖它就会拿到页面正文色（亮灰），
                        //   在主色底上糊成一片。这里跟着按钮文字色走。
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (saving) R.string.donate_saving else R.string.donate_save_button,
                        ),
                        color = MiuixTheme.colorScheme.onPrimary,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        // ============ 末段：条款 ============
        // ★ 一条 1dp 细线。作用不是装饰，是**把"条款"从"内容/操作"里划出去** ——
        //   收费口径是一段与用户操作无关的长文字，紧贴按钮会让卡片看起来"下面糊了一大块"。
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )
        Spacer(Modifier.height(12.dp))
        // ★ 收费口径是**固定文案**（用户 2026-09-21 定的）：不写「永久免费」，
        //   也不写任何同义承诺（"一直免费" / "不打赏也能用"）。它跟着捐赠段走 ——
        //   捐赠段从「关于」页搬到首页时，这一段一起搬。
        //   ⚠️ 改动前先全仓 grep「Alpha 阶段 将始终保持免费」。
        Text(
            text = stringResource(R.string.donate_terms),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

/**
 * 二维码素材的**宽高比**（= 1179 / 1495）。
 *
 * ⚠️ 素材是 `drawable-nodpi` 的位图，而且是微信"保存收款码"给出的**整图** ——
 *   带「推荐使用微信支付」页头、白色码片（含收款人昵称）和「微信支付」页脚，
 *   所以**不是正方形**。
 *   `painterResource` + 固定宽却不给高时，`Image` 会按 `ContentScale.Fit` 塞进方框，
 *   四周留一圈空白 —— 看着像图小了，其实是框大了。⇒ 高必须按这个比例算出来。
 */
private const val QR_ASPECT = 1179f / 1495f

/**
 * 屏幕上那张码的宽度。
 *
 * ★ 140dp 的取舍（2026-10-01 第二轮放大，原 128dp）：用户原话「把捐赠信息放大一点」。
 *   素材是**整图**（带微信支付页头页脚），码片实际只占宽度的六成多 ——
 *   140dp 里真正能扫的那块约 96dp。
 *   ⚠️ 上限在哪：它是首页第一张卡，而首页是**工具的功能目录**。
 *   140dp 时整卡约 275dp（二维码是唯一的定高元素），
 *   再加上文案与按钮同步放大，"放大"的观感主要来自**字号和按钮**，不是靠这张图撑高度。
 *   **再往上加就该把功能入口挤出首屏了。**
 *   ⚠️ 而且屏幕上这张码**不是**给人扫的主路径（保存按钮才是，存的是原图），
 *   所以"看得清"就够了，别为了"能扫"继续放大。
 */
private val QR_WIDTH = 140.dp

/** 按素材比例算出的高（见 [QR_ASPECT]），避免 `Image` 内部再留空 */
private val QR_HEIGHT = QR_WIDTH / QR_ASPECT

/**
 * 把一张 PNG 素材存进**系统相册**，成功返回 `true`。
 *
 * ============================ 为什么不需要任何权限（别顺手加回来）============================
 * 本应用 `minSdk = 30`，而"把自己的图片写进媒体库"从 **Android 10（API 29）** 起就
 * 走**作用域存储**：应用往 `MediaStore` 里 insert 一条**属于自己**的记录并写内容，
 * 平台不需要 `WRITE_EXTERNAL_STORAGE`（那个权限在 API 29+ 已彻底失去意义）。
 * ⇒ ⛔ **不要**往清单里加 `WRITE_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`：
 *   加了不但没用，还会让用户在应用详情页看到一个"照片和视频"权限项。
 *
 * ============================ `IS_PENDING` 是干什么的 ============================
 * 先插一条 `IS_PENDING = 1` 的占位记录 → 写完内容 → 再置回 0。
 * 中间这段时间里**别的应用（相册、微信）读不到这个文件**，所以它们不会读到一个
 * 只写了一半的 PNG。不写这套的话，相册有一定概率显示出一张**残缺的图**。
 * ⚠️ 失败路径必须 `delete` 掉那条占位记录，否则会在媒体库里留下一条**永远 pending
 *   的僵尸条目**（相册里看不到，但数据库里有）。
 *
 * @return 成功 `true`；插入失败 / 写流失败 / 编码失败都是 `false`（调用方据此报错，不猜原因）
 */
@Suppress("DEPRECATION")
private fun saveDrawableToGallery(ctx: Context, @DrawableRes resId: Int): Boolean {
    // ★ `inScaled = false`：素材在 `drawable-nodpi` 里（密度 = none），默认解码不会缩放，
    //   但显式关掉更保险 —— 一旦将来有人把它挪进 `drawable/`，这里会安静地
    //   按屏幕密度放大一圈，存出来的图就不是原图了。
    val opts = BitmapFactory.Options().apply { inScaled = false }
    val bmp: Bitmap = BitmapFactory.decodeResource(ctx.resources, resId, opts) ?: return false

    // ⚠️ 2026-10-03 多语言：文件名**不跟着界面语言变**，固定用 ASCII ——
    //   文件名会进媒体库、会被别的应用读，中文名在非中文系统上会显示成乱码或方框。
    val name = "HyperPlus_donate_qr_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        // `Pictures/HyperPlus/` —— 不给它一个自己的目录的话，几十张码会散在相册根目录里。
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/HyperPlus",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val resolver = ctx.contentResolver
    val uri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: run { bmp.recycle(); return false }

    return try {
        val out = resolver.openOutputStream(uri) ?: error("openOutputStream 返回 null")
        out.use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG 编码失败") }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (t: Throwable) {
        // 半成品留在媒体库里比"保存失败"更糟：相册里会出现一张打不开的图。
        runCatching { resolver.delete(uri, null, null) }
        false
    } finally {
        bmp.recycle()
    }
}

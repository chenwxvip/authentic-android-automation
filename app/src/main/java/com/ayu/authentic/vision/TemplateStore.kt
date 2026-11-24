package com.ayu.authentic.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog
import kotlin.math.abs

/**
 * MVP 阶段三 · 特征模板库。
 *
 * 注册接口：init(context) 从 assets/templates/ 读取并解码：
 *   - lobby_entry.png / armor_*.png   阶段二特勤处模板（阶段三保留但不参与登录判定）
 *   - login_1..8.png                 登录流程 8 张场景签名
 *     （源图 features/stations/user-defined/{1..8}.png.jpg）
 *     1 登录界面  2 请阅读弹窗  3 授权/同意页  4 选号页
 *     5 大厅  6 设置页  7 退出确认  8 回到登录界面
 *
 * 匹配：把模板缩放到与搜索区域同尺寸，计算「采样点 RGB 平均绝对差」相似度。
 */
object TemplateStore {

    data class Template(
        val id: String,
        val bmp: Bitmap,
        /** 平均色（用于颜色兜底） */
        val avgR: Int, val avgG: Int, val avgB: Int
    )

    private val templates = LinkedHashMap<String, Template>()

    /** 已注册模板数量 */
    fun count(): Int = templates.size

    /** 取已注册模板的位图（供外部自定义比对用），未注册返回 null */
    fun getBitmap(id: String): Bitmap? = templates[id]?.bmp

    fun ids(): List<String> = templates.keys.toList()

    /**
     * 从 assets 读取并注册全部模板。可重复调用（幂等）。
     * 返回成功注册的模板名列表。
     */
    @Synchronized
    fun init(context: Context): List<String> {
        if (templates.isNotEmpty()) return ids()
        val ctx = context.applicationContext
        val files = mutableListOf<Pair<String, String>>(
            "lobby" to Config.Templates.LOBBY_ENTRY,
            "armor_idle" to Config.Templates.ARMOR_IDLE,
            "armor_ready" to Config.Templates.ARMOR_READY,
            "armor_producing" to Config.Templates.ARMOR_PRODUCING,
            "teqin_stations" to Config.Templates.TEQIN_STATIONS,
            "teqin_roi" to Config.Templates.TEQIN_STATIONS_ROI,
            "lobby_prepare" to Config.Templates.LOBBY_PREPARE,
            "lobby_roi" to Config.Templates.LOBBY_PREPARE_ROI,
            "reward_page" to Config.Templates.REWARD_PAGE,
            "close_btn" to Config.Templates.CLOSE_BTN,
            "lobby_marker" to Config.Templates.LOBBY_MARKER,
            "teqin_entry" to Config.Templates.TEQIN_ENTRY,
            "teqin_upgrade" to Config.Templates.TEQIN_UPGRADE_BTN
        )
        // 登录流程 8 张场景签名
        Config.Templates.LOGIN_STEPS.forEachIndexed { i, f ->
            files.add("login_${i + 1}" to f)
        }
        val ok = mutableListOf<String>()
        for ((id, file) in files) {
            val bmp = runCatching {
                ctx.assets.open("templates/$file").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp == null) {
                ALog.w("[模板] 读取失败: templates/$file（将仅用节点/坐标兜底）")
                continue
            }
            val (r, g, b) = averageColor(bmp)
            templates[id] = Template(id, bmp, r, g, b)
            ok.add("$id(${bmp.width}x${bmp.height})")
        }
        ALog.i("[模板] 已注册 ${ok.size}/${files.size}: ${ok.joinToString()}")
        return ok
    }

    /**
     * 全屏场景相似度：把整帧缩到模板尺寸后逐点比对，返回 0f..1f。
     * 用于判断当前画面属于 login_1..8 的哪一步（横屏/竖屏分别匹配对应模板）。
     */
    fun sceneSimilarity(id: String, frame: Bitmap): Float {
        val t = templates[id] ?: return 0f
        val f = runCatching { Bitmap.createScaledBitmap(frame, t.bmp.width, t.bmp.height, true) }
            .getOrNull() ?: return 0f
        val step = 8
        var sum = 0L; var n = 0
        var y = 0
        while (y < f.height) {
            var x = 0
            while (x < f.width) {
                val c = f.getPixel(x, y); val s = t.bmp.getPixel(x, y)
                sum += abs(Color.red(c) - Color.red(s)) +
                    abs(Color.green(c) - Color.green(s)) +
                    abs(Color.blue(c) - Color.blue(s))
                n += 3
                x += step
            }
            y += step
        }
        if (f !== frame) f.recycle()
        if (n == 0) return 0f
        return (1f - (sum.toFloat() / n) / 255f).coerceIn(0f, 1f)
    }

    /** 颜色签名兜底：区域平均色与模板平均色的接近程度 0f..1f */
    fun colorSimilarity(id: String, regionAvg: Triple<Int, Int, Int>): Float {
        val t = templates[id] ?: return 0f
        val d = (abs(t.avgR - regionAvg.first) + abs(t.avgG - regionAvg.second) + abs(t.avgB - regionAvg.third)) / 3f
        return (1f - d / 255f).coerceIn(0f, 1f)
    }

    /**
     * 区域模板相似度：把模板缩放到 roi 尺寸，按步长采样逐点比对 RGB。
     * 返回 0f..1f，越大越像。
     */
    fun regionSimilarity(id: String, frame: Bitmap, roi: Config.Region): Float {
        val t = templates[id] ?: return 0f
        val w = (roi.right - roi.left).coerceAtLeast(1)
        val h = (roi.bottom - roi.top).coerceAtLeast(1)
        val scaled = runCatching { Bitmap.createScaledBitmap(t.bmp, w, h, true) }.getOrNull() ?: return 0f

        val step = 6
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val fx = roi.left + x
                val fy = roi.top + y
                if (fx in 0 until frame.width && fy in 0 until frame.height) {
                    val c = frame.getPixel(fx, fy)
                    val s = scaled.getPixel(x, y)
                    sum += abs(Color.red(c) - Color.red(s)) +
                        abs(Color.green(c) - Color.green(s)) +
                        abs(Color.blue(c) - Color.blue(s))
                    n += 3
                }
                x += step
            }
            y += step
        }
        if (scaled !== t.bmp) scaled.recycle()
        if (n == 0) return 0f
        return (1f - (sum.toFloat() / n) / 255f).coerceIn(0f, 1f)
    }

    /** 找图结果：模板在画面中的中心坐标 + 相似度 */
    data class Match(val cx: Int, val cy: Int, val score: Float)

    /**
     * 【2026-11-20 整屏找图】在 frame 的 roi 区域内滑动窗口找模板，返回最佳匹配中心坐标。
     * 找不到（相似度低于 threshold）返回 null。
     *
     * 用于"检测+动作"二合一：如找弹窗 X 按钮——找到就点它关弹窗，找不到=画面干净。
     * 为速度：模板按 scale 缩放，窗口步长 stride，逐点采样 step。
     *
     * @param id 模板 id（init 时注册的）
     * @param frame 当前帧（BASE_W×BASE_H 或任意尺寸）
     * @param roi 搜索区域（null=整屏）
     * @param threshold 相似度阈值 0f..1f
     * @param stride 滑动步长（像素，越小越精确越慢）
     * @param step 模板内采样步长
     */
    fun findTemplate(
        id: String,
        frame: Bitmap,
        roi: Config.Region? = null,
        threshold: Float = 0.72f,
        stride: Int = 4,
        step: Int = 3
    ): Match? {
        val t = templates[id] ?: run {
            ALog.w("[找图] 模板未注册: $id"); return null
        }
        val l = roi?.left?.coerceIn(0, frame.width - 1) ?: 0
        val top = roi?.top?.coerceIn(0, frame.height - 1) ?: 0
        val r = (roi?.right ?: frame.width).coerceAtMost(frame.width)
        val b = (roi?.bottom ?: frame.height).coerceAtMost(frame.height)
        val tw = t.bmp.width; val th = t.bmp.height
        var best = -1f; var bx = -1; var by = -1
        var y = top
        while (y + th <= b) {
            var x = l
            while (x + tw <= r) {
                var sum = 0L; var n = 0
                var sy = 0
                while (sy < th) {
                    var sx = 0
                    while (sx < tw) {
                        val c = frame.getPixel(x + sx, y + sy)
                        val s = t.bmp.getPixel(sx, sy)
                        sum += abs(Color.red(c) - Color.red(s)) +
                            abs(Color.green(c) - Color.green(s)) +
                            abs(Color.blue(c) - Color.blue(s))
                        n += 3; sx += step
                    }
                    sy += step
                }
                if (n > 0) {
                    val sim = (1f - (sum.toFloat() / n) / 255f)
                    if (sim > best) { best = sim; bx = x; by = y }
                }
                x += stride
            }
            y += stride
        }
        ALog.i("[找图] $id best=%.3f 阈值=%.2f pos=($bx,$by)".format(best, threshold))
        return if (best >= threshold) Match(bx + tw / 2, by + th / 2, best) else null
    }

    /** 计算区域或整图的平均色（步长采样，避免逐像素太慢） */
    fun averageColor(frame: Bitmap, roi: Config.Region? = null): Triple<Int, Int, Int> {
        val l = roi?.left ?: 0
        val t = roi?.top ?: 0
        val r = (roi?.right ?: frame.width).coerceAtMost(frame.width)
        val b = (roi?.bottom ?: frame.height).coerceAtMost(frame.height)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val step = 8
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = frame.getPixel(x, y)
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
                x += step
            }
            y += step
        }
        if (n == 0) return Triple(0, 0, 0)
        return Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    private fun averageColor(bmp: Bitmap): Triple<Int, Int, Int> = averageColor(bmp, null)
}

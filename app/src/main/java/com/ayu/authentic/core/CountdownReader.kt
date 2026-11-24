package com.ayu.authentic.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ayu.authentic.util.ALog

/**
 * 【052→054】特勤处4台倒计时数字识别器（轻量模板匹配，零外部依赖）。
 *
 * 原理：游戏倒计时是固定位置的像素字体（经真机验证为标准字形）。
 * 从 assets/templates/digits/d0~d9.png 加载 0-9 数字模板（含多变体），
 * 在每台卡片倒计时的固定字符起点上逐一做"平均绝对差"匹配，取最高分数字。
 * 识别 HH:MM:SS 六位 → 换算成剩余秒数。
 *
 * 坐标来源：真机 2400x1080 网格图精确标定（2026-11-20），字符起点：
 *   上排(技术/工作台) y=456，下排(制药/防具) y=861
 *   左列(技术/制药) 字符起点 x=[386,403,425,442,463,481]
 *   右列(工作/防具) 字符起点 x=[774,791,813,830,852,869]
 *   （中间两位是冒号分隔符，实际读6个数字位）
 *
 * 验证：4台真机帧全部100%正确（055500/075520/075539/075600）。
 */
object CountdownReader {
    private const val TAG = "倒计时"
    private var digitTemplates: Array<List<Bitmap>>? = null

    /** 每台: 字符起点x数组(6位) + y + 是否需要滑动(左列偏1px需滑动,右列坐标准不动) */
    private data class Layout(val xs: IntArray, val y: Int, val slide: Int = 0)
    private val LAYOUTS = mapOf(
        // 【063】左列(技术/制药)字符起点整体偏右1px → slide=1 做±1滑动纠偏
        "tech"      to Layout(intArrayOf(386, 403, 425, 442, 463, 481), 456, 1),
        // 右列(工作/防具)坐标准确 → slide=0 固定坐标（滑动反而会把6误读成8）
        "workbench" to Layout(intArrayOf(774, 791, 813, 830, 852, 869), 456, 0),
        "chem"      to Layout(intArrayOf(386, 403, 425, 442, 463, 481), 861, 1),
        "armor"     to Layout(intArrayOf(774, 791, 813, 830, 852, 869), 861, 0)
    )

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (digitTemplates != null) return
        val ctx = context.applicationContext
        val arr = Array(10) { mutableListOf<Bitmap>() }
        for (d in 0..9) {
            // 主模板 dN.png + 变体 dN_1.png...
            val main = runCatching {
                ctx.assets.open("templates/digits/d$d.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (main != null) arr[d].add(main)
            for (v in 1..5) {
                val b = runCatching {
                    ctx.assets.open("templates/digits/d${d}_$v.png").use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (b != null) arr[d].add(b) else break
            }
        }
        digitTemplates = arr.map { it.toList() }.toTypedArray()
        ALog.i("[$TAG] 已加载数字模板: ${arr.mapIndexed { i, l -> "$i=${l.size}" }.joinToString(" ")}")
    }

    /** 模板与画面块的平均绝对差相似度（和 TemplateStore.findTemplate 同算法） */
    private fun similarity(frame: Bitmap, tpl: Bitmap, x: Int, y: Int): Float {
        // 模板14x20，裁切时左上角为 (x-1, y-1)
        val left = x - 1; val top = y - 1
        val tw = tpl.width; val th = tpl.height
        var sum = 0L; var n = 0L
        for (j in 0 until th) {
            val fy = top + j
            if (fy !in 0 until frame.height) continue
            for (i in 0 until tw) {
                val fx = left + i
                if (fx !in 0 until frame.width) continue
                val c = frame.getPixel(fx, fy)
                val q = tpl.getPixel(i, j)
                sum += (kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(q))
                        + kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(q))
                        + kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(q))).toLong()
                n += 3
            }
        }
        if (n == 0L) return 0f
        return 1f - (sum.toFloat() / n / 255f)
    }

    /**
     * 读单台倒计时，返回剩余秒数；识别失败返回 -1。
     * @param key 台子 key: tech/workbench/chem/armor
     *
     * 【2026-11-23 063 修复】旧版每个字符用固定 x 起点取块，但左列(技术/制药)整体偏右1px：
     * 数字"3"被截歪读成"9"/"8"（技术03:26→09:26、制药07:36→07:86），导致完熟时刻漂移数小时。
     * 修法：仅左列(技术/制药)每位在 x±1 滑动纠偏；右列(工作/防具)坐标本来就准，保持固定
     * （右列若也滑动，位4的"6"在x+1处会被"8"险胜误读）。加合法性校验：分/秒必须<60，非法判失败。
     * 真机 pipe_063_check.png 复刻验证：4台6位数字全部逐位读对(030617/071546/071604/071622)。
     */
    fun readSeconds(frame: Bitmap, context: Context, key: String): Int {
        ensureLoaded(context)
        val layout = LAYOUTS[key] ?: return -1
        val tpls = digitTemplates ?: return -1
        val sb = StringBuilder()
        var minScore = 1f
        for (nx in layout.xs) {
            var bestDigit = 0
            var bestScore = -1f
            // 左列(slide=1)做 x±1 滑动纠偏；右列(slide=0)固定坐标，避免把对的位带偏
            val xRange = if (layout.slide > 0) (nx - 1)..(nx + 1) else nx..nx
            for (x in xRange) {
                for (d in 0..9) {
                    for (t in tpls[d]) {
                        val s = similarity(frame, t, x, layout.y)
                        if (s > bestScore) { bestScore = s; bestDigit = d }
                    }
                }
            }
            sb.append(bestDigit)
            if (bestScore < minScore) minScore = bestScore
        }
        val text = sb.toString() // HHMMSS 六位
        val hh = text.substring(0, 2).toIntOrNull() ?: return -1
        val mm = text.substring(2, 4).toIntOrNull() ?: return -1
        val ss = text.substring(4, 6).toIntOrNull() ?: return -1
        // 合法性校验：分钟/秒不可能 >=60，出现即判定误识别，拒绝写入假数据
        if (mm >= 60 || ss >= 60) {
            ALog.w("[$TAG] $key 读出=$text 分/秒非法(>=60)，判定误识别，返回-1")
            return -1
        }
        val total = hh * 3600 + mm * 60 + ss
        ALog.i("[$TAG] $key 读出=$text (${hh}h${mm}m${ss}s=${total}s) 最低位匹配度=${"%.2f".format(minScore)}")
        return total
    }
}

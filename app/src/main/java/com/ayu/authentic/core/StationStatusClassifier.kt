package com.ayu.authentic.core

import android.content.Context
import android.graphics.Bitmap
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.TemplateStore

/**
 * 【app-debug051】特勤处 4 制造台状态识别器。
 *
 * 状态分类：
 *   READY    —— 有菜熟！点台子会弹"获得奖励"全屏页（reward_page 模板相似度≥阈值）
 *   COUNTDOWN —— 生产中！点台子进详情页，右下按钮是"中止"（区域亮度 < 100）
 *   IDLE     —— 空闲中！点台子进详情页，右下按钮是"一键补齐"或"生产"（区域亮度 > 150）
 *   UNKNOWN  —— 判不出来，打日志跳过，不碰这台
 *
 * 设计原则：**尽量用亮度/模板匹配，不引额外依赖**。
 * 真 OCR（RapidOCR）接进来时，只改 [classifyAfterTapIntoDetail] 里 IDLE 分支的倒计时提取部分。
 */
object StationStatusClassifier {

    enum class Status { READY, COUNTDOWN, IDLE, UNKNOWN }

    /**
     * 【052 主界面读卡片三态】用户2026-11-20 17:28 拍板流程专用。
     * RIPE      —— 熟了：卡片右上角有黄色小标(成品名旁)，卡片内金黄边框 → 点进去收菜+再种
     * PRODUCING —— 生产中：卡片左下有 ⏳倒计时(00:xx:xx)，无黄标 → 只读时间，绝不点进去
     * IDLE      —— 空闲：卡片显示"+ 空间中/空闲中"，无黄标无倒计时 → 点进去直接种
     *
     * 【055修复】判据来源（18:12真机帧 pipe_enter_teqin 实测）：
     *   生产中倒计时数字是【灰白色】不是金黄！之前的 countYellow 检不到 → 全误判 IDLE。
     *   左下倒计时区"亮白像素"：生产中 326~382，空闲/熟台 恒=0 → >80 判 PRODUCING
     *   右上角黄标像素(金黄)：熟了 188~375，生产中/空闲 恒=0 → >100 判 RIPE
     * 顺序：先看黄标(熟)，再看倒计时亮白(生产中)，都没有=空闲。
     */
    enum class CardStatus { RIPE, PRODUCING, IDLE }

    /** 每台卡片的两个采样小区域（2400x1080 基准）：右上黄标(金黄)、左下倒计时(亮白) */
    private data class CardRois(val yellowMark: Config.Region, val countdown: Config.Region)
    private val CARD_ROIS = mapOf(
        "技术中心" to CardRois(Config.Region(600, 295, 668, 320), Config.Region(380, 450, 470, 478)),
        "工作台"   to CardRois(Config.Region(1000, 295, 1068, 320), Config.Region(770, 450, 860, 478)),
        "制药台"   to CardRois(Config.Region(600, 690, 668, 715), Config.Region(380, 855, 470, 883)),
        "防具台"   to CardRois(Config.Region(1000, 690, 1068, 715), Config.Region(770, 855, 860, 883))
    )
    private const val YELLOW_MARK_MIN = 100   // 右上黄标金黄像素阈值
    private const val COUNTDOWN_MIN = 80      // 左下倒计时亮白像素阈值

    /** 主界面读卡片判态（不点进去）。frame 应是特勤处4台子主界面实时帧。 */
    fun classifyFromCard(frame: Bitmap, stationName: String): CardStatus {
        val rois = CARD_ROIS[stationName] ?: return CardStatus.IDLE
        val markPx = countYellow(frame, rois.yellowMark)
        val cdPx = countBright(frame, rois.countdown)
        val result = when {
            markPx > YELLOW_MARK_MIN -> CardStatus.RIPE
            cdPx > COUNTDOWN_MIN -> CardStatus.PRODUCING
            else -> CardStatus.IDLE
        }
        ALog.i("[卡片判态] $stationName 黄标=$markPx 倒计时亮白=$cdPx → $result")
        return result
    }

    /** 统计区域内"金黄色"像素数（黄标/金框） */
    private fun countYellow(frame: Bitmap, roi: Config.Region): Int {
        var count = 0
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                val p = frame.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                if (r > 170 && g > 120 && b < 110 && r > b + 70) count++
            }
        }
        return count
    }

    /** 统计区域内"亮白灰"像素数（生产中倒计时数字是灰白色，去色偏） */
    private fun countBright(frame: Bitmap, roi: Config.Region): Int {
        var count = 0
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                val p = frame.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                if (r > 125 && g > 125 && b > 125 && kotlin.math.abs(r - g) < 50 && kotlin.math.abs(g - b) < 50) count++
            }
        }
        return count
    }

    private const val REWARD_THRESHOLD = 0.55f
    private const val PRODUCE_BTN_BRIGHT_IDLE_MIN = 130   // 右下绿按钮亮度下限
    private const val PRODUCE_BTN_BRIGHT_COUNTDOWN_MAX = 100 // 右下"中止"暗按钮亮度上限

    /**
     * 点进台子详情后调用：识别当前状态。
     * 输入 frame 应是"点台子 → 等3s 后的实时帧"。
     */
    fun classifyAfterTapIntoDetail(frame: Bitmap, context: Context): Status {
        TemplateStore.init(context)

        // ---- 先查有没有"获得奖励"全屏页（READY=有菜熟）----
        val rewardSim = TemplateStore.sceneSimilarity("reward_page", frame)
        if (rewardSim >= REWARD_THRESHOLD) {
            ALog.i("[状态识别] reward_page sim=%.3f≥%.2f → READY（有菜熟）".format(rewardSim, REWARD_THRESHOLD))
            return Status.READY
        }
        ALog.i("[状态识别] reward_page sim=%.3f<%.2f → 非奖励页，查详情页右下按钮亮度".format(rewardSim, REWARD_THRESHOLD))

        // ---- 查详情页右下按钮区域亮度 ----
        // BTN_TEQIN_PRODUCE 的区域 = (1834, 950, 2282, 1036)
        val btnRoi = Config.Region(1834, 950, 2282, 1036)
        val (r, g, b) = TemplateStore.averageColor(frame, btnRoi)
        val brightness = (r + g + b) / 3

        return when {
            brightness >= PRODUCE_BTN_BRIGHT_IDLE_MIN -> {
                ALog.i("[状态识别] 右下按钮亮度=$brightness≥$PRODUCE_BTN_BRIGHT_IDLE_MIN → IDLE（空闲/可种菜/已熟进详情）")
                Status.IDLE
            }
            brightness <= PRODUCE_BTN_BRIGHT_COUNTDOWN_MAX -> {
                ALog.i("[状态识别] 右下按钮亮度=$brightness≤$PRODUCE_BTN_BRIGHT_COUNTDOWN_MAX → COUNTDOWN（生产中）")
                Status.COUNTDOWN
            }
            else -> {
                ALog.w("[状态识别] 右下按钮亮度=$brightness 在[$PRODUCE_BTN_BRIGHT_COUNTDOWN_MAX,$PRODUCE_BTN_BRIGHT_IDLE_MIN]之间 → UNKNOWN，跳过")
                Status.UNKNOWN
            }
        }
    }
}

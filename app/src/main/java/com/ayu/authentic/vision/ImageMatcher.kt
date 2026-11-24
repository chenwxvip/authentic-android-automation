package com.ayu.authentic.vision

import android.graphics.Bitmap
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段二 · 图色判定引擎（仅防具台单点）。
 *
 * 输入：MediaProjection 喂进来的实时帧（缩放到 2400x1080 基准）。
 * 输出：
 *   - scene: LOBBY / TEQIN / UNKNOWN
 *   - armor: IDLE / READY / PRODUCING / UNKNOWN
 *
 * 判定策略（最稳妥，三层）：
 *   ① 模板区域相似度（TemplateStore.regionSimilarity）
 *   ② 区域平均色签名（TemplateStore.colorSimilarity）兜底
 *   ③ 都拿不准就 UNKNOWN，由状态机走坐标兜底，不误操作
 */
class ImageMatcher {

    enum class Scene { LOBBY, TEQIN, UNKNOWN }
    enum class ArmorState { IDLE, READY, PRODUCING, UNKNOWN }

    data class Result(
        val scene: Scene,
        val armor: ArmorState,
        val lobbySim: Float,
        val sims: Map<String, Float>,
        val note: String
    )

    /** 判定阈值：模板匹配比较宽松，因为模板是裁剪图、屏幕有缩放/UI 差异 */
    private val THRESHOLD = 0.72f

    fun analyze(frame: Bitmap): Result {
        // 按帧自身宽高比选基准：横屏帧用 2400x1080，竖屏帧用 1080x2400，避免拉伸变形
        val landscape = frame.width >= frame.height
        val baseW = if (landscape) Config.BASE_W else Config.BASE_H
        val baseH = if (landscape) Config.BASE_H else Config.BASE_W
        val f = if (frame.width == baseW && frame.height == baseH) frame
        else Bitmap.createScaledBitmap(frame, baseW, baseH, true)

        // 竖屏（未进游戏）时不硬判特勤处/大厅，直接返回 UNKNOWN 让状态机等待，
        // 防止把竖屏 UI 误识别成游戏画面导致误点
        if (!landscape) {
            if (f !== frame) f.recycle()
            return Result(
                Scene.UNKNOWN, ArmorState.UNKNOWN, 0f, emptyMap(),
                "竖屏帧 ${frame.width}x${frame.height}，等待游戏横屏"
            )
        }

        val lobbySim = TemplateStore.regionSimilarity("lobby", f, Config.Region(0, 0, Config.BASE_W, Config.BASE_H))
        val armorRegion = Config.ARMOR_CARD
        val avg = TemplateStore.averageColor(f, armorRegion)
        val sims = linkedMapOf<String, Float>()
        for (id in listOf("armor_idle", "armor_ready", "armor_producing")) {
            val tpl = TemplateStore.regionSimilarity(id, f, armorRegion)
            val col = TemplateStore.colorSimilarity(id, avg)
            // 取模板相似度和颜色签名里较高者，降低单路误判
            sims[id] = maxOf(tpl, col)
        }

        val best = sims.maxByOrNull { it.value }
        val armor = when {
            best == null -> ArmorState.UNKNOWN
            best.value < THRESHOLD -> ArmorState.UNKNOWN
            best.key == "armor_idle" -> ArmorState.IDLE
            best.key == "armor_ready" -> ArmorState.READY
            best.key == "armor_producing" -> ArmorState.PRODUCING
            else -> ArmorState.UNKNOWN
        }

        // 场景：大厅模板相似度 + 防具台相似度共同判断
        val armorBest = sims.values.maxOrNull() ?: 0f
        val scene = when {
            armorBest >= THRESHOLD -> Scene.TEQIN          // 防具台卡片出现 => 已在特勤处面板
            lobbySim >= THRESHOLD -> Scene.LOBBY           // 大厅签名命中
            else -> Scene.UNKNOWN
        }

        val note = "lobby=%.2f armor[idle=%.2f ready=%.2f producing=%.2f] best=%s avg=(%d,%d,%d)".format(
            lobbySim,
            sims["armor_idle"] ?: 0f, sims["armor_ready"] ?: 0f, sims["armor_producing"] ?: 0f,
            best?.key ?: "-", avg.first, avg.second, avg.third
        )
        if (f !== frame) f.recycle()
        return Result(scene, armor, lobbySim, sims, note)
    }

    companion object {
        fun log(r: Result) {
            ALog.i("[图色] scene=${r.scene} armor=${r.armor} | ${r.note}")
        }
    }
}

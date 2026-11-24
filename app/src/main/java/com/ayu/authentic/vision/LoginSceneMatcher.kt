package com.ayu.authentic.vision

import android.graphics.Bitmap
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段三 · 登录流程场景识别器。
 *
 * 依据 features/stations/user-defined/{1..8}.png.jpg 的 8 张流程截图，
 * 把 MediaProjection 实时帧归类到某个场景：
 *   S1 登录界面 / S2 请阅读弹窗 / S3 授权同意页 / S4 选号页
 *   S5 大厅 / S6 设置页 / S7 退出确认 / S8 回到登录界面
 *
 * 横屏图(1,2,5,6,7,8)只在横屏帧里匹配，竖屏图(3,4)只在竖屏帧里匹配，
 * 避免拉伸误判。相似度低于阈值返回 UNKNOWN（状态机按「保持等待」处理，不误点）。
 */
class LoginSceneMatcher {

    enum class Scene(val step: Int, val cn: String) {
        S1_LOGIN(1, "登录界面"),
        S2_READ_AGREE(2, "请阅读弹窗"),
        S3_AUTH(3, "授权/同意页"),
        S4_PICK_ACCOUNT(4, "选号页"),
        S5_LOBBY(5, "大厅"),
        S6_SETTINGS(6, "设置页"),
        S7_CONFIRM(7, "退出确认"),
        S8_BACK_LOGIN(8, "回到登录界面"),
        UNKNOWN(0, "未识别");

        companion object { fun of(step: Int) = values().firstOrNull { it.step == step } ?: UNKNOWN }
    }

    data class Result(val scene: Scene, val scores: Map<Int, Float>, val note: String)

    /**
     * 相似度阈值。
     * 【阶段四校准】真机日志实测：整屏缩略图签名在真机画面上通常只有 0.4~0.6，
     * （模板是压缩过的 600px 缩略图，与实时帧有亮度/UI 差异），0.68 永远达不到。
     * 下调到 0.50，并额外要求「best 比第二名领先 LEAD 以上」才接纳，避免模棱两可时误点。
     */
    private val THRESHOLD = 0.50f
    private val LEAD = 0.06f

    private val landscapeSteps = listOf(1, 2, 5, 6, 7, 8)
    private val portraitSteps = listOf(3, 4)

    fun analyze(frame: Bitmap): Result {
        val landscape = frame.width >= frame.height
        val candidates = if (landscape) landscapeSteps else portraitSteps
        val scores = linkedMapOf<Int, Float>()
        for (s in candidates) {
            scores[s] = TemplateStore.sceneSimilarity("login_$s", frame)
        }
        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull()
        val second = sorted.getOrNull(1)?.value ?: 0f
        val lead = (best?.value ?: 0f) - second
        val scene = if (best != null && best.value >= THRESHOLD && lead >= LEAD)
            Scene.of(best.key) else Scene.UNKNOWN
        val note = "${if (landscape) "横屏" else "竖屏"} " +
            scores.entries.joinToString { "%d=%.2f".format(it.key, it.value) } +
            " best=${best?.key ?: "-"}(${"%.2f".format(best?.value ?: 0f)}) lead=${"%.2f".format(lead)} -> ${scene.cn}"
        return Result(scene, scores, note)
    }

    companion object {
        fun log(r: Result) = ALog.i("[场景] ${r.scene.cn} | ${r.note}")
    }
}

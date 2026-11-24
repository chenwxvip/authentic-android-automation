package com.ayu.authentic.core

import android.content.Context
import android.widget.Toast
import com.ayu.authentic.config.Config
import com.ayu.authentic.service.AuthenticAccessibilityService
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.FrameBus
import com.ayu.authentic.vision.ImageMatcher
import com.ayu.authentic.vision.TemplateStore
import kotlinx.coroutines.delay

/**
 * MVP 阶段二 · 防具台单点闭环状态机。
 *
 * 流程：
 *   ① 拉起游戏 + 等加载（由调用方完成）
 *   ② 逐帧检测大厅「特勤处」入口（lobby_entry 模板 / 坐标兜底）-> dispatchGesture 点击进入
 *   ③ 进入特勤处面板后检测防具台区域：
 *        IDLE(armor_idle)       -> 点卡片 -> 选默认配方 -> 一键补齐 -> 点右下「生产」
 *        READY(armor_ready)     -> Toast + 日志打印「待收取」，不误操作
 *        PRODUCING(armor_producing) -> Toast + 日志打印「生产中/倒计时」，不误操作
 *        UNKNOWN                -> 只记日志，继续等下一帧（不误点）
 *
 * 只针对防具台一个台子，不引入多账号 / 四台子 / 轮询调度。
 */
class ArmorStateMachine(private val context: Context) {

    enum class Phase { WAIT_LOBBY, ENTER_TEQIN, DETECT_ARMOR, ACTION_DONE, STOPPED }

    @Volatile
    var phase: Phase = Phase.WAIT_LOBBY
        private set

    @Volatile
    var running = false
        private set

    private val matcher = ImageMatcher()
    private var rounds = 0
    private var lastToastAt = 0L

    /** 状态机主循环；由前台服务在协程里调用 */
    suspend fun run() {
        if (running) return
        running = true
        phase = Phase.WAIT_LOBBY
        rounds = 0

        // 模板库注册（幂等）
        val registered = TemplateStore.init(context)
        ALog.i("[状态机] 启动 · 模板=${registered.joinToString()}")

        if (!AuthenticAccessibilityService.isReady()) {
            ALog.w("[状态机] 无障碍服务未连接，手势不可用；仍可跑图色判定，但点击会失败")
            toast("无障碍未开启，请先点①开启无障碍")
        } else {
            toast("单点闭环启动：等待大厅画面")
        }

        while (running && rounds < MAX_ROUNDS) {
            rounds++
            val g = AuthenticAccessibilityService.gesture
            val frame = FrameBus.snapshot()
            if (frame == null) {
                val err = FrameBus.lastError
                if (rounds == 1 || rounds % 10 == 0) {
                    ALog.w("[状态机] 暂无画面帧 (${err ?: "等待 MediaProjection 授权"})，round=$rounds")
                }
                delay(Config.CAPTURE_INTERVAL_MS)
                continue
            }

            val r = runCatching { matcher.analyze(frame) }.getOrElse {
                ALog.e("[状态机] 图色分析异常: ${it.message}")
                null
            }
            frame.recycle()
            if (r == null) {
                delay(Config.CAPTURE_INTERVAL_MS)
                continue
            }

            ImageMatcher.log(r)

            when (phase) {
                Phase.WAIT_LOBBY -> handleLobby(r, g)
                Phase.ENTER_TEQIN -> handleTeqin(r, g)
                Phase.DETECT_ARMOR -> handleArmor(r, g)
                Phase.ACTION_DONE -> {
                    ALog.i("[状态机] 闭环完成，停止")
                    running = false
                }
                Phase.STOPPED -> running = false
            }
            delay(Config.CAPTURE_INTERVAL_MS)
        }
        ALog.i("[状态机] 结束 running=$running phase=$phase rounds=$rounds")
        running = false
    }

    /** 大厅：确认在大厅后点「特勤处」入口（模板命中或坐标兜底） */
    private suspend fun handleLobby(r: ImageMatcher.Result, g: com.ayu.authentic.gesture.GestureDispatcher?) {
        val inLobby = r.scene == ImageMatcher.Scene.LOBBY || r.scene == ImageMatcher.Scene.UNKNOWN
        if (!inLobby) {
            // 已经在特勤处面板了，直接进入检测
            ALog.i("[状态机] 已在特勤处面板，跳过大厅点击")
            phase = Phase.DETECT_ARMOR
            return
        }
        if (g == null) {
            ALog.w("[状态机] 手势不可用，无法点大厅入口")
            return
        }
        // 模板命中 lobby 时提示；无论是否命中都按实测坐标点（最稳妥，不阻断闭环）
        if (r.lobbySim >= 0.5f) ALog.i("[状态机] 大厅入口模板相似度=%.2f".format(r.lobbySim))
        ALog.i("[状态机] 点击大厅「特勤处」入口 (${Config.LOBBY_TEQIN_ENTRY.cx},${Config.LOBBY_TEQIN_ENTRY.cy})")
        toast("识别到大厅，点击进入特勤处")
        g.tapRegion(Config.LOBBY_TEQIN_ENTRY)
        phase = Phase.ENTER_TEQIN
    }

    /** 等特勤处面板加载完成 */
    private suspend fun handleTeqin(r: ImageMatcher.Result, g: com.ayu.authentic.gesture.GestureDispatcher?) {
        if (r.scene == ImageMatcher.Scene.TEQIN || r.armor != ImageMatcher.ArmorState.UNKNOWN) {
            ALog.i("[状态机] 特勤处面板已就绪")
            phase = Phase.DETECT_ARMOR
        } else {
            ALog.i("[状态机] 等待特勤处面板加载... (round=$rounds)")
        }
    }

    /** 防具台三态分支 */
    private suspend fun handleArmor(r: ImageMatcher.Result, g: com.ayu.authentic.gesture.GestureDispatcher?) {
        when (r.armor) {
            ImageMatcher.ArmorState.IDLE -> {
                ALog.i("[状态机] 防具台【空闲】-> 发起制造")
                toast("防具台空闲，发起制造：${Config.ARMOR_DEFAULT_RECIPE}")
                if (g == null) {
                    ALog.w("[状态机] 手势不可用，仅记录，不执行点击")
                    phase = Phase.ACTION_DONE
                    return
                }
                // 点卡片进详情
                g.tapRegion(Config.ARMOR_CARD)
                // 选默认配方（第一行）
                ALog.i("[状态机] 选默认配方 ${Config.ARMOR_DEFAULT_RECIPE}")
                g.tapRegion(Config.RECIPE_FIRST_ROW)
                // 一键补齐
                g.tapRegion(Config.BTN_FILL, 800)
                // 右下角「生产」
                ALog.i("[状态机] 点击右下角「生产」")
                g.tapRegion(Config.BTN_PRODUCE)
                toast("已发起制造：${Config.ARMOR_DEFAULT_RECIPE}")
                phase = Phase.ACTION_DONE
            }
            ImageMatcher.ArmorState.READY -> {
                val msg = "防具台【待收取】- 本阶段不做收取，仅提示"
                ALog.i("[状态机] $msg | ${r.note}")
                throttledToast("防具台待收取（暂不操作）")
                // 按需求：不误操作，停留继续观察
            }
            ImageMatcher.ArmorState.PRODUCING -> {
                val msg = "防具台【生产中/倒计时】- 不进行操作"
                ALog.i("[状态机] $msg | ${r.note}")
                throttledToast("防具台生产中，暂不操作")
                // 按需求：不误操作，停留继续观察
            }
            ImageMatcher.ArmorState.UNKNOWN -> {
                ALog.i("[状态机] 防具台状态未识别，继续等下一帧（不误点）")
            }
        }
    }

    private fun toast(msg: String) {
        ALog.i("[Toast] $msg")
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 状态未变化时避免每 1.5s 刷一次 Toast */
    private fun throttledToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt > 8000L) {
            lastToastAt = now
            toast(msg)
        }
    }

    fun stop() {
        ALog.i("[状态机] 收到 stop()")
        running = false
        phase = Phase.STOPPED
    }

    companion object {
        /** 最多观察 80 轮（约 2 分钟），防止无限跑 */
        const val MAX_ROUNDS = 80
    }
}

package com.ayu.authentic.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog
import kotlinx.coroutines.delay

/**
 * MVP 阶段二 · 无障碍手势分发。
 *
 * 把 2400x1080 基准坐标等比缩放到真机分辨率，用
 * AccessibilityService.dispatchGesture() 执行点击（API 24+，真机 API 29 可用）。
 */
class GestureDispatcher(private val service: AccessibilityService) {

    private var realW = 0
    private var realH = 0

    fun updateDisplay(w: Int, h: Int) {
        realW = w
        realH = h
        ALog.i("[手势] 显示尺寸 ${w}x${h}")
    }

    private fun sx(x: Int): Float =
        if (realW > 0) x * realW / Config.BASE_W.toFloat() else x.toFloat()

    private fun sy(y: Int): Float =
        if (realH > 0) y * realH / Config.BASE_H.toFloat() else y.toFloat()

    /**
     * 【阶段五修复】每次点击前刷新真实显示尺寸。
     * onServiceConnected 时记录的可能是竖屏 1080x2400，游戏进横屏后
     * 不刷新会把 2400x1080 基准坐标缩到竖屏上，导致点击全偏。
     */
    private fun refreshDisplay() {
        runCatching {
            val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            @Suppress("DEPRECATION")
            val rot = wm.defaultDisplay.rotation
            // 华为 ROM 横屏游戏里仍返回物理 1080x2400，按 rotation 交换成 2400x1080
            val landscape = rot == android.view.Surface.ROTATION_90 ||
                rot == android.view.Surface.ROTATION_270 || dm.widthPixels >= dm.heightPixels
            val w = if (landscape) maxOf(dm.widthPixels, dm.heightPixels) else minOf(dm.widthPixels, dm.heightPixels)
            val h = if (landscape) minOf(dm.widthPixels, dm.heightPixels) else maxOf(dm.widthPixels, dm.heightPixels)
            if (w != realW || h != realH) {
                realW = w; realH = h
                ALog.i("[手势] 显示尺寸刷新为 ${realW}x${realH} (rotation=$rot, ${if (landscape) "横屏" else "竖屏"})")
            }
        }
    }

    /** 单击基准坐标点 */
    suspend fun tap(x: Int, y: Int, settleMs: Long = Config.UI_SETTLE_MS) {
        refreshDisplay()
        val rx = sx(x); val ry = sy(y)
        val path = Path().apply { moveTo(rx, ry) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(desc, null, null)
        ALog.i("[手势] 点击基准($x,$y) -> 真机(${rx.toInt()},${ry.toInt()}) dispatch=$ok")
        delay(settleMs)
    }

    /** 点击区域中心 */
    suspend fun tapRegion(r: Config.Region, settleMs: Long = Config.UI_SETTLE_MS) =
        tap(r.cx, r.cy, settleMs)

    /**
     * 【2026-11-17】物理坐标直投：入参即真机当前屏幕的物理像素，不做任何基准换算。
     * 用于 QQ/微信竖屏授权页——用户从 1080x2400 截图直接读的坐标 (540,2016)/(540,1987)，
     * 若走 tapPortrait 的 1220x2712 换算会被算偏，因此这里原样投到屏幕。
     */
    suspend fun tapPhysical(x: Int, y: Int, settleMs: Long = Config.UI_SETTLE_MS) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(desc, null, null)
        ALog.i("[手势] 物理直投($x,$y) dispatch=$ok")
        delay(settleMs)
    }

    /**
     * 竖屏页点击：竖屏模板图是 1220x2712，与真机竖屏 1080x2400 近似等比。
     * 这里按「竖屏基准 1220x2712」换算到真实竖屏尺寸，避免误用横屏 2400x1080 缩放。
     */
    suspend fun tapPortrait(r: Config.Region, settleMs: Long = Config.UI_SETTLE_MS) {
        refreshDisplay()
        val baseW = 1220f
        val baseH = 2712f
        // refreshDisplay 已把横屏尺寸规整成 2400x1080；竖屏页出现时 realW/realH 应为 1080x2400。
        // 这里统一取「短边为宽、长边为高」映射到竖屏基准。
        val rw = minOf(realW, realH).toFloat()
        val rh = maxOf(realW, realH).toFloat()
        val rx = r.cx * rw / baseW
        val ry = r.cy * rh / baseH
        val path = android.graphics.Path().apply { moveTo(rx, ry) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        val desc = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(desc, null, null)
        ALog.i("[手势] 竖屏点击基准(${r.cx},${r.cy}) -> 真机(${rx.toInt()},${ry.toInt()}) dispatch=$ok")
        delay(settleMs)
    }

    /** 全局返回键 */
    suspend fun back(settleMs: Long = Config.UI_SETTLE_MS) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(settleMs)
    }
}

package com.ayu.authentic.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ayu.authentic.gesture.GestureDispatcher
import com.ayu.authentic.gesture.NodeFinder
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段三 · 无障碍服务（手势 + 节点双能力）。
 *
 * - GestureDispatcher：游戏内横屏界面按坐标 dispatchGesture 点击
 * - NodeFinder：微信/QQ 竖屏授权页按原生节点文本点「同意/切换账号/选号」
 *
 * Manifest 配置需 canPerformGestures=true 且 canRetrieveWindowContent=true（阶段三已改）。
 */
class AuthenticAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = resources.displayMetrics
        instance = this
        gesture = GestureDispatcher(this).also { it.updateDisplay(dm.widthPixels, dm.heightPixels) }
        nodes = NodeFinder(this)
        ALog.i("[无障碍] 服务已连接 · 手势+节点就绪 ${dm.widthPixels}x${dm.heightPixels}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 阶段三由 MediaProjection 图色 + 主动节点查询驱动，不依赖事件流
    }

    override fun onInterrupt() = ALog.w("[无障碍] 服务被中断")

    override fun onDestroy() {
        if (instance === this) {
            instance = null; gesture = null; nodes = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: AuthenticAccessibilityService? = null; private set
        @Volatile var gesture: GestureDispatcher? = null; private set
        @Volatile var nodes: NodeFinder? = null; private set

        fun isReady(): Boolean = instance != null && gesture != null && nodes != null
    }
}

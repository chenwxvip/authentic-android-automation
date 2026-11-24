package com.ayu.authentic.vision

import android.graphics.Bitmap
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段二 · 进程内帧总线。
 *
 * MediaProjection 服务每抓到一帧就 publish()，状态机读 latest()。
 * 用 @Volatile 引用 + 复用同一 Bitmap 的副本，避免跨线程回收冲突：
 * publish 时若上一帧还没被取走，直接替换引用（旧帧由 GC 回收）。
 */
object FrameBus {

    @Volatile
    private var latest: Bitmap? = null

    @Volatile
    var frameCount: Long = 0
        private set

    /** 【022】最新帧的发布时间戳(ms)，用于判断帧是否新鲜——防止拿旧帧判断导致"看着特勤处、实际在模式页" */
    @Volatile
    var lastPublishMs: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun publish(frame: Bitmap) {
        latest = frame
        frameCount++
        lastPublishMs = System.currentTimeMillis()
    }

    /** 【022】最近 maxAgeMs 内是否出新帧；false=帧过期，不能用于判断/点击 */
    fun isFresh(maxAgeMs: Long = 2500L): Boolean =
        lastPublishMs > 0 && System.currentTimeMillis() - lastPublishMs <= maxAgeMs

    fun ageMs(): Long = if (lastPublishMs <= 0) -1L else System.currentTimeMillis() - lastPublishMs

    /** 取一帧副本（调用方负责 recycle） */
    @Synchronized
    fun snapshot(): Bitmap? {
        val f = latest ?: return null
        return runCatching { Bitmap.createBitmap(f) }.getOrElse {
            ALog.w("[帧] 拷贝失败: ${it.message}")
            null
        }
    }

    fun hasFrame(): Boolean = latest != null

    fun error(msg: String) {
        lastError = msg
        ALog.e("[帧] $msg")
    }

    @Synchronized
    fun reset() {
        latest = null
        frameCount = 0
        lastPublishMs = 0L
        lastError = null
    }
}

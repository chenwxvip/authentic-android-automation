package com.ayu.authentic.vision

import android.content.Context

/**
 * 截图服务运行状态的持久化标志。
 *
 * 用 SharedPreferences 而不是进程内变量：CaptureService 与 MainActivity
 * 可能不在同一进程生命周期（App 被回收后重建），进程内 volatile 会丢。
 * CaptureService 建立 VirtualDisplay 成功后置 true，stop 时置 false。
 */
object CaptureState {
    private const val PREF = "authentic_capture"
    private const val KEY_RUNNING = "running"
    private const val KEY_LAST_FRAME_MS = "last_frame_ms"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun setRunning(c: Context, running: Boolean) {
        prefs(c).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(c: Context): Boolean = prefs(c).getBoolean(KEY_RUNNING, false)

    fun markFrame(c: Context) {
        prefs(c).edit().putLong(KEY_LAST_FRAME_MS, System.currentTimeMillis()).apply()
    }

    fun lastFrameMs(c: Context): Long = prefs(c).getLong(KEY_LAST_FRAME_MS, 0L)

    /** 有运行标志且最近 15 秒内来过帧，才算「真的可用」 */
    fun isLive(c: Context): Boolean =
        isRunning(c) && System.currentTimeMillis() - lastFrameMs(c) < 15_000L
}

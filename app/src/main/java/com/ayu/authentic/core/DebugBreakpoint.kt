package com.ayu.authentic.core

import android.content.Context
import com.ayu.authentic.util.ALog
import kotlinx.coroutines.delay
import java.io.File

/**
 * 【断点单步调试开关】
 *
 * 用外部文件标志控制，AI 通过 adb 创建/删除文件来开关，不污染 App 界面。
 *  - 文件存在 = 断点开启：040 流水线跑到「4台子收种完毕、回到特勤处主界面」时挂起，
 *    不返回大厅、不退出登录、也不切回 Authentic（协程停在 harvestSegment 内，
 *    runFullPipeline 不返回，故 KeepAliveService 里的切回前台逻辑不会执行）。
 *    此时游戏停在特勤处4台子界面，AI 用 adb 手动点击，一问一答趟通种菜坐标。
 *  - 文件删除 = 放行：挂起循环检测到文件消失，流水线继续走返回大厅→退出段→切回前台。
 *
 * adb 用法：
 *   开启：adb shell "echo 1 > /sdcard/Android/data/com.ayu.authentic/files/debug_breakpoint_on"
 *   放行：adb shell rm /sdcard/Android/data/com.ayu.authentic/files/debug_breakpoint_on
 */
object DebugBreakpoint {
    private fun flag(c: Context) = File(c.getExternalFilesDir(null), "debug_breakpoint_on")

    fun isEnabled(c: Context): Boolean = flag(c).exists()

    /** 挂起等待，直到标志文件被删除；每 10s 打一次心跳日志，方便 AI 监控 */
    suspend fun awaitResume(c: Context) {
        val f = flag(c)
        var beat = 0
        while (f.exists()) {
            beat++
            if (beat % 10 == 0) ALog.i("[断点] 仍停在特勤处，等待单步指令…（${beat}s）")
            delay(1000L)
        }
        ALog.i("[断点] 断点标志已移除，流水线继续（返回大厅→退出段）")
    }
}

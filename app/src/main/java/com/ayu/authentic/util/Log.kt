package com.ayu.authentic.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志：logcat + 内存环形缓冲 + **落盘文件**。
 *
 * 【阶段四】之前日志只在内存里，logcat 256KB 被系统日志冲掉后就再也读不到实测记录。
 * 现在每条日志同时追加到 /sdcard/Download/authentic_log.txt，
 * 可用 `adb pull /sdcard/Download/authentic_log.txt` 或直接 `adb shell cat` 读取。
 * 文件超过 512KB 自动轮转（保留一个 .old），避免无限增长。
 */
object ALog {
    private const val TAG = "Authentic"
    private const val LOG_PATH = "/sdcard/Download/authentic_log.txt"
    private const val MAX_BYTES = 512 * 1024L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val ring = ArrayList<String>(300)
    private var contextPath: String? = null

    /** 由 Application/Activity 调用，传入外部存储可用路径（这里固定用公共 Download） */
    fun init(path: String = LOG_PATH) { contextPath = path }

    @Synchronized
    fun i(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        Log.i(TAG, line)
        ring.add(line)
        if (ring.size > 300) ring.removeAt(0)
        appendFile(line)
    }

    fun w(msg: String) = i("WARN  $msg")
    fun e(msg: String, t: Throwable? = null) {
        i("ERROR $msg ${t?.message ?: ""}")
        t?.let { Log.e(TAG, msg, it) }
    }

    @Synchronized
    fun tail(n: Int = 60): List<String> = ring.takeLast(n)

    @Synchronized
    fun clear() = ring.clear()

    private fun appendFile(line: String) {
        runCatching {
            val f = File(contextPath ?: LOG_PATH)
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.absolutePath + ".old")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.appendText(line + "\n")
        }
    }
}

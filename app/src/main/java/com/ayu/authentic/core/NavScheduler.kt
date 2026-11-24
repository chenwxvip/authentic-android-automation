package com.ayu.authentic.core

import android.content.Context
import com.ayu.authentic.util.ALog
import com.ayu.authentic.core.AccountRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自适应退避定时巡检调度。【057】看板可收驱动的常驻巡检闭环。
 *
 * 规则（取所有未成熟台子里【最小剩余时间】决定间隔）：
 *   剩余 > 60 分钟  → 300s
 *   20 ~ 60 分钟   → 180s
 *   5 ~ 20 分钟    → 120s
 *   剩余 < 5 分钟   → 60s（临近成熟高频盯防）
 * 一句话：剩得久拉长间隔，快熟了缩短间隔。
 *
 * 【057 巡检闭环】开启定时后：
 *   1) 倒数5秒跑首轮摸底流水线 → 看板写入4台真实倒计时；
 *   2) refreshStation 每秒调 checkAndFire：先依次摸底所有账号，之后看板有台子 finishAtMs ≤ now
 *      （界面显示 00:00:00 可收）且流水线不在运行 → 触发对应账号的完整流水线去收+种；
 *   3) 流水线跑完重写看板为新的未来倒计时 → 回到 2) 继续等。
 *   运行锁 running 防止同一时刻重复拉起游戏；内存态，进程死自动清零。
 */
object NavScheduler {
    private const val PREF = "authentic_schedule"
    private const val K_ENABLED = "enabled"
    private const val K_NEXT = "next_ms"
    /** 【065】首轮摸底游标：>=0 表示还要摸第 N 个账号；-1 表示摸底完成，进入常规巡检 */
    private const val K_PROBE_INDEX = "probe_index"

    /** 【057】流水线运行锁：内存态，进程死自动清零，防止巡检重复拉起 */
    @Volatile private var running = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean(K_ENABLED, v).apply() }
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)
    fun setNextMs(c: Context, ms: Long) { prefs(c).edit().putLong(K_NEXT, ms).apply() }
    fun nextMs(c: Context): Long = prefs(c).getLong(K_NEXT, 0L)

    fun setRunning(c: Context, v: Boolean) { running = v; ALog.i("[定时] 运行锁=$v") }
    fun isRunning(c: Context): Boolean = running

    // ===== 【065 多账号摸底游标】=====
    /** 开启定时时重置摸底游标为 0（从头依次摸所有账号） */
    fun resetProbe(c: Context) { prefs(c).edit().putInt(K_PROBE_INDEX, 0).apply(); ALog.i("[摸底] 游标重置=0") }
    /** 当前摸底游标；-1=摸底已完成 */
    fun probeIndex(c: Context): Int = prefs(c).getInt(K_PROBE_INDEX, -1)
    /** 是否还需要首轮摸底 */
    fun needProbe(c: Context): Boolean = probeIndex(c) >= 0
    /** 摸底游标前进一位；到达账号总数后置 -1（摸底完成，交回常规巡检） */
    fun advanceProbe(c: Context, accountCount: Int) {
        val next = probeIndex(c) + 1
        val v = if (next >= accountCount) -1 else next
        prefs(c).edit().putInt(K_PROBE_INDEX, v).apply()
        ALog.i("[摸底] 游标前进 → $v${if (v < 0) "（摸底完成）" else ""}")
    }
    /** 关闭定时时清掉摸底游标，避免下次重复 */
    fun clearProbe(c: Context) { prefs(c).edit().putInt(K_PROBE_INDEX, -1).apply() }

    /** 【065】取所有账号所有未成熟台子里最小剩余秒数，按自适应退避规则返回间隔(秒) */
    fun pickIntervalSec(c: Context): Long {
        val now = System.currentTimeMillis()
        var minRemain = Long.MAX_VALUE
        for (d in StationBoard.allBoards(c)) {
            for (r in d.rows) {
                if (r.finishAtMs <= 0) continue
                val remain = (r.finishAtMs - now) / 1000
                if (remain > 0 && remain < minRemain) minRemain = remain
            }
        }
        if (minRemain == Long.MAX_VALUE) return 300L
        val min = minRemain / 60
        return when {
            min > 60 -> 300L
            min > 20 -> 180L
            min > 5 -> 120L
            else -> 60L
        }
    }

    /** 排定下一次检测时刻，返回格式化 HH:mm:ss */
    fun reschedule(c: Context): String {
        val interval = pickIntervalSec(c)
        val next = System.currentTimeMillis() + interval * 1000
        setNextMs(c, next)
        ALog.i("[定时] 自适应间隔=${interval}s，下次检测=${fmt(next)}")
        return fmt(next)
    }

    fun fmt(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    /** 距离下次检测还剩多少秒（<=0 表示到点，需重排） */
    fun remainSec(c: Context): Long {
        val n = nextMs(c)
        if (n <= 0L) return 0L
        val r = (n - System.currentTimeMillis()) / 1000
        return if (r < 0) 0 else r
    }

    /** 倒计时格式 mm:ss（如 05:00 → 00:38 → 00:37），界面每秒刷新 */
    fun fmtCountdown(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    /** 【057】看板中已到可收时刻的台子名列表（界面显示 00:00:00 可收） */
    fun readyStations(c: Context): List<String> {
        val out = ArrayList<String>()
        val now = System.currentTimeMillis()
        for (d in StationBoard.allBoards(c)) {
            out.addAll(d.rows.filter { it.finishAtMs in 1 until now }.map { "${d.account}:${it.name}" })
        }
        return out
    }

    /**
     * 【065】找出"有可收台子"的账号 note（巡检要触发哪个账号的流水线）。
     * 遍历所有账号看板，返回第一个有台子 finishAtMs ≤ now 的账号。
     */
    fun readyAccount(c: Context): String? {
        val now = System.currentTimeMillis()
        // 按账号列表顺序，保证稳定选择
        val accounts = AccountRepository(c).all().map { it.note }
        for (note in accounts) {
            val d = StationBoard.read(c, note) ?: continue
            if (d.rows.any { it.finishAtMs in 1 until now }) return note
        }
        return null
    }

    /**
     * 【065 多账号巡检触发入口】
     * 优先级：① 首轮摸底未完 → 触发下一个待摸底账号；② 摸底完成 + 有账号可收 → 触发那个账号。
     * 返回 (note|null, reason)。note 为空表示本次不触发。core 层不直接调 service。
     */
    fun checkAndFire(c: Context): Pair<String?, String> {
        if (!isEnabled(c)) return null to "定时未开启"
        if (isRunning(c)) return null to "流水线运行中，跳过本次巡检"
        // ① 首轮摸底：依次把每个账号登录进去读4台真实状态写各自看板
        if (needProbe(c)) {
            val accounts = AccountRepository(c).all()
            val idx = probeIndex(c)
            if (idx < accounts.size) {
                val acc = accounts[idx]
                return acc.note to "首轮摸底 ${idx + 1}/${accounts.size}：${acc.note}"
            }
            advanceProbe(c, accounts.size) // 游标越界，收尾置 -1
            return null to "摸底游标越界，已收尾"
        }
        // ② 常规巡检：找有可收台子的账号
        val note = readyAccount(c)
        if (note != null) {
            reschedule(c)
            return note to "账号 $note 看板有可收台子 → 触发完整流水线"
        }
        if (remainSec(c) <= 0) reschedule(c)
        return null to "无可收账号，继续倒计时等待"
    }
}

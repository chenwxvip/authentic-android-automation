package com.ayu.authentic.core

import android.content.Context
import com.ayu.authentic.util.ALog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 特勤处 4 台子状态看板数据源。
 *
 * 数据文件：getExternalFilesDir()/station_status.txt（纯文本 key=value，adb 可直接写）
 *   account=1234567890
 *   ts=1763532313000        <- 读数时刻的 System.currentTimeMillis()
 *   tech=6800               <- 读数那一刻的剩余秒数
 *   workbench=18124
 *   chem=21491
 *   armor=21544
 *
 * 展示逻辑：
 *   预计完成时间 = ts + 剩余秒数
 *   实时倒计时   = 预计完成时间 - 现在（每秒刷新）
 *
 * 数据源目前由外部写入（我读真机截图后 adb 写文件）；
 * 以后接 OCR 时，OCR 也写同一个文件即可，界面不用改。
 */
object StationBoard {
    const val FILE_NAME = "station_status.txt"
    private val NAMES = listOf("技术台", "工作台", "制药台", "防具台")
    private val KEYS = listOf("tech", "workbench", "chem", "armor")

    /**
     * 【065 多账号】每个账号独立看板文件 station_status_{账号}.txt。
     * account 为空时仍用默认 FILE_NAME（兼容旧调用/旧 adb 脚本）。
     */
    fun file(context: Context, account: String = ""): File {
        val name = if (account.isBlank()) FILE_NAME else "station_status_${account}.txt"
        return File(context.getExternalFilesDir(null), name)
    }

    data class Row(val name: String, val finishAtMs: Long)
    data class Data(val account: String, val rows: List<Row>)

    fun read(context: Context, account: String = ""): Data? = readFile(file(context, account))

    private fun readFile(f: File): Data? {
        if (!f.exists()) return null
        val map = HashMap<String, String>()
        runCatching {
            f.readLines().forEach { line ->
                val p = line.split("=", limit = 2)
                if (p.size == 2) map[p[0].trim()] = p[1].trim()
            }
        }.onFailure { return null }
        val account = map["account"] ?: return null
        val ts = map["ts"]?.toLongOrNull() ?: return null
        val rows = KEYS.mapIndexed { i, k ->
            val sec = map[k]?.toLongOrNull() ?: -1L
            Row(NAMES[i], if (sec >= 0) ts + sec * 1000L else -1L)
        }
        return Data(account, rows)
    }

    /**
     * 【065】读取所有账号的独立看板。
     * 扫描 getExternalFilesDir 下所有 station_status_*.txt（每账号一份）；
     * 另外把无后缀的默认 FILE_NAME 也算进来（历史账号0数据）。
     * 供界面展示多账号 + 巡检遍历找可收账号。
     */
    fun allBoards(context: Context): List<Data> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        val out = ArrayList<Data>()
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("station_status") && f.name.endsWith(".txt")) {
                    readFile(f)?.let { out.add(it) }
                }
            }
        }
        return out.sortedBy { it.account }
    }

    /** 格式化时间戳 (HH:mm:ss)，供日志里打印"预计完成时间" */
    fun fmt(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun readFileAsMap(f: File): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (!f.exists()) return map
        runCatching {
            f.readLines().forEach { line ->
                val p = line.split("=", limit = 2)
                if (p.size == 2) map[p[0].trim()] = p[1].trim()
            }
        }
        return map
    }

    /**
     * 写单个台子的完成时刻到看板文件。
     * 不覆写其他台子的数据——只更新指定 key，保留 account/ts 和其他行。
     * 首次写入（文件不存在）时：用当前时间作 ts，account 留空（界面会显示"当前登录"）。
     *
     * @param key 台子 key：tech / workbench / chem / armor
     * @param finishAtMs 预计完成时刻的绝对时间戳
     */
    fun write(context: Context, key: String, finishAtMs: Long, account: String = "") {
        if (key !in KEYS) return
        val f = file(context, account)
        val now = System.currentTimeMillis()
        val existing = readFileAsMap(f)
        if (!existing.containsKey("account")) existing["account"] = ""
        existing["ts"] = now.toString()
        existing[key] = ((finishAtMs - now) / 1000).toString()
        val content = existing.entries.joinToString("\n") { "${it.key}=${it.value}" }
        runCatching { f.writeText(content) }
        ALog.i("[看板] 已更新 $key：finishAt=${fmt(finishAtMs)}（剩余${(finishAtMs - now) / 1000}s）")
    }

    /** 批量更新多个台子 finishAtMs。entries 是 mapOf(key to finishAtMs) */
    fun writeBatch(context: Context, entries: Map<String, Long>, account: String = "") {
        val now = System.currentTimeMillis()
        val f = file(context, account)
        val existing = readFileAsMap(f)
        // 【062】把当前登录账号写进看板，界面账号行才能显示真实账号（此前恒为空→显示"--"）
        if (account.isNotBlank()) existing["account"] = account
        else if (!existing.containsKey("account")) existing["account"] = ""
        existing["ts"] = now.toString()
        for ((key, finishMs) in entries) {
            if (key !in KEYS) continue
            existing[key] = ((finishMs - now) / 1000).toString()
        }
        val content = existing.entries.joinToString("\n") { "${it.key}=${it.value}" }
        runCatching { f.writeText(content) }
        ALog.i("[看板] 已批量更新 ${entries.size} 台：${entries.entries.joinToString { "${it.key}=${fmt(it.value)}" }}")
    }

    /** 生成单个账号看板文本（每行一条，供 TextView 直接 join） */
    fun lines(context: Context, account: String = ""): List<String> = linesOf(read(context, account))

    /**
     * 【065】生成所有账号看板文本：每个账号一段（账号行 + 4台），段间空行。
     * 界面用它一次性展示多账号；没有独立看板文件时显示账号列表的占位（由调用方决定）。
     */
    fun linesAll(context: Context, accounts: List<String>): List<String> {
        val out = ArrayList<String>()
        val boards = allBoards(context).associateBy { it.account }
        accounts.forEachIndexed { idx, acc ->
            if (idx > 0) out.add("")
            out.addAll(linesOf(boards[acc]))
        }
        return out
    }

    /** 单份 Data → 文本行；null 时输出 00:00:00 可收 占位（061 用户要求） */
    private fun linesOf(d: Data?): List<String> {
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        out.add(if (d == null || d.account.isBlank()) "账号 --（首轮运行后填入）"
                else "账号 ${d.account}（当前登录）")
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val rows = d?.rows ?: NAMES.map { Row(it, -1L) }
        for (r in rows) {
            if (r.finishAtMs <= 0) {
                out.add("${r.name} ｜ 00:00:00 ｜ 00:00:00 可收")
                continue
            }
            val finishStr = fmt.format(Date(r.finishAtMs))
            val remain = (r.finishAtMs - now) / 1000
            val remainStr = if (remain <= 0) "00:00:00 可收"
            else "%02d:%02d:%02d".format(remain / 3600, (remain % 3600) / 60, remain % 60)
            out.add("${r.name} ｜ $finishStr ｜ $remainStr")
        }
        return out
    }
}

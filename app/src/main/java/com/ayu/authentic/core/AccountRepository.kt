package com.ayu.authentic.core

import android.content.Context
import com.ayu.authentic.util.ALog
import org.json.JSONArray
import org.json.JSONObject

/**
 * MVP 阶段三 · 简易账号列表（SharedPreferences 持久化）。
 *
 * 字段：备注(note) + 登录渠道(QQ / WECHAT)。
 * 主界面提供可视化录入/删除；也支持直接调用 upsert / replaceAll。
 */
data class AccountConfig(
    val id: String = System.currentTimeMillis().toString(),
    val note: String = "",
    val channel: Channel = Channel.QQ
) {
    enum class Channel(val cn: String) { QQ("QQ"), WECHAT("微信") }

    fun label(): String = "${note.ifBlank { "未命名" }} · ${channel.cn}"
}

class AccountRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("authentic_accounts", Context.MODE_PRIVATE)

    /** 读取账号列表（按保存顺序） */
    fun all(): List<AccountConfig> {
        val raw = prefs.getString(KEY, null) ?: return default()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AccountConfig(
                    id = o.optString("id"),
                    note = o.optString("note"),
                    channel = runCatching {
                        AccountConfig.Channel.valueOf(o.optString("channel", "QQ"))
                    }.getOrDefault(AccountConfig.Channel.QQ)
                )
            }
        }.getOrElse {
            ALog.e("[账号] 解析失败: ${it.message}")
            default()
        }
    }

    /** 保存并返回列表 */
    fun replaceAll(list: List<AccountConfig>): List<AccountConfig> {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("note", a.note); put("channel", a.channel.name)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        ALog.i("[账号] 已保存 ${list.size} 个: ${list.joinToString { it.label() }}")
        return list
    }

    fun add(a: AccountConfig): List<AccountConfig> = replaceAll(all() + a)

    fun remove(id: String): List<AccountConfig> = replaceAll(all().filter { it.id != id })

    fun clear(): List<AccountConfig> = replaceAll(emptyList())

    private fun default(): List<AccountConfig> = listOf(
        AccountConfig("demo_qq", "小号1", AccountConfig.Channel.QQ),
        AccountConfig("demo_wx", "小号2", AccountConfig.Channel.WECHAT)
    )

    companion object { private const val KEY = "accounts_json" }
}

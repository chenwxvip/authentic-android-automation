package com.ayu.authentic.gesture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段三 · 跨应用无障碍节点定位。
 *
 * 步骤 B/C 需求：跳到微信/QQ 授权页时，自动点「同意 / 确认登录 / 允许」等原生按钮；
 * 多账号切换时点「切换账号」并选中账号行。
 *
 * 用系统 API findAccessibilityNodeInfosByText + 子树文本包含兜底，不做 OCR。
 */
class NodeFinder(private val service: AccessibilityService) {

    private fun root(): AccessibilityNodeInfo? = runCatching { service.rootInActiveWindow }
        .getOrElse { ALog.e("[节点] 取 root 失败: ${it.message}"); null }

    /** 按任一关键词找可点节点（优先本身可点，其次可点祖先） */
    fun findByAnyText(vararg texts: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        for (t in texts) {
            val exact = runCatching { r.findAccessibilityNodeInfosByText(t) }.getOrNull()
            val hit = exact?.firstOrNull { clickableOf(it).isClickable }
                ?: walk(r).firstOrNull { it.text?.toString()?.contains(t) == true && clickableOf(it).isClickable }
            if (hit != null) return clickableOf(hit)
        }
        return null
    }

    /** 点击关键词节点；返回命中的文本，失败返回 null */
    fun clickAnyText(vararg texts: String): String? {
        val n = findByAnyText(*texts) ?: return null
        val label = n.text?.toString() ?: n.viewIdResourceName ?: "?"
        val ok = runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ALog.i("[节点] 点击「$label」ok=$ok (关键词=${texts.joinToString()})")
        return if (ok) label else null
    }

    /** 点击含指定账号备注/标识的节点行（用于步骤 4 选号） */
    fun clickAccountRow(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val r = root() ?: return false
        val n = walk(r).firstOrNull {
            val t = it.text?.toString() ?: ""
            t.contains(keyword) && clickableOf(it).isClickable
        } ?: return false
        val ok = runCatching { clickableOf(n).performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
        ALog.i("[节点] 选号「$keyword」ok=$ok")
        return ok
    }

    /**
     * 【阶段八】点选号页里的「第一个账号行」。
     * 账号备注是 QQ 号（如 1234567890），但列表项显示的是昵称/头像，
     * 用文本匹配永远命中不了。改为：找 RecyclerView/ListView/AdapterView 容器，
     * 点它的第一个可点子项；找不到容器时，退而求其次点第一个带文本的可点节点。
     * 返回是否点到。
     */
    fun clickFirstAccountRow(): Boolean {
        val r = root() ?: return false
        val all = walk(r)

        // ① 找列表容器的直接子项（QQ 选号页常见 RecyclerView）
        val listClasses = listOf(
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.ListView",
            "android.widget.AbsListView",
            "android.widget.AdapterView"
        )
        val containers = all.filter { n -> listClasses.any { it == n.className?.toString() } }
        for (c in containers) {
            for (i in 0 until c.childCount) {
                val child = runCatching { c.getChild(i) }.getOrNull() ?: continue
                val target = clickableOf(child)
                val ok = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                ALog.i("[节点] 选号：点列表容器(${c.className})第${i}项 ok=$ok text=${child.text ?: "-"}")
                if (ok) return true
            }
        }

        // ② 兜底：找第一个「有文本且可点、且不在屏幕顶部/底部按钮区」的节点
        val candidates = all.filter { n ->
            val t = n.text?.toString() ?: ""
            t.isNotBlank() && t.length >= 2 && clickableOf(n).isClickable &&
                t !in listOf("同意", "确认", "确定", "切换账号", "返回", "取消")
        }
        // 优先 viewId 里含 account / item / name / nick 的
        val preferred = candidates.firstOrNull {
            val id = it.viewIdResourceName ?: ""
            id.contains("account", true) || id.contains("item", true) ||
                id.contains("name", true) || id.contains("nick", true)
        } ?: candidates.firstOrNull()
        if (preferred != null) {
            val ok = runCatching {
                clickableOf(preferred).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            ALog.i("[节点] 选号：点候选「${preferred.text}」(${preferred.viewIdResourceName}) ok=$ok")
            return ok
        }
        return false
    }

    /**
     * 【2026-11-17 全量轮询】当前是否在「输入密码」异常页（本地登录态过期）。
     * 命中任一密码/登录关键词即判定——用于在切号后第一时间发现异常，避免卡死。
     */
    fun isPasswordPage(): Boolean {
        val texts = allTexts()
        val keys = listOf("输入密码", "请输入密码", "密码登录", "忘记密码", "重新登录",
            "账号密码", "输入QQ密码", "请输入QQ密码", "验证密码", "安全验证")
        val hit = texts.firstOrNull { t -> keys.any { t.contains(it) } }
        if (hit != null) ALog.i("[节点] 检测到密码/验证页关键词: $hit")
        return hit != null
    }

    /**
     * 【2026-11-17 全量轮询】检测当前是否有随机礼包/活动弹窗遮挡。
     * 命中「前往获取/领取/立即领取」等特征词即判定为广告弹窗，交给上层按返回键关掉。
     * 注意：只在游戏大厅/行前备战页调用，授权页不会有这些词，避免误判。
     */
    fun hasPromoPopup(): Boolean {
        val t = allTexts()
        val keys = listOf("前往获取", "立即领取", "立即获取", "前往领取", "领取奖励",
            "限时礼包", "礼包", "购买", "前往购买", "查看活动")
        val hit = t.firstOrNull { x -> keys.any { x.contains(it) } }
        if (hit != null) ALog.i("[节点] 检测到礼包/活动弹窗: $hit")
        return hit != null
    }

    /**
     * 【2026-11-17 全量轮询】检测当前窗口是否存在任一指定文本（不要求可点）。
     * 用于轮询等待授权页出现：检测到「切换账号」或「同意」即认为授权页就绪。
     */
    fun hasAnyText(vararg texts: String): Boolean {
        val t = allTexts()
        return texts.any { key -> t.any { it.contains(key) } }
    }

    /**
     * 【2026-11-17 全量轮询】校验授权页当前选中的账号是否为目标号。
     * 在 allTexts 中查找目标 QQ 号或昵称；找到即认为切换成功。
     */
    fun containsAccount(target: String): Boolean {
        if (target.isBlank()) return false
        val texts = allTexts()
        val hit = texts.firstOrNull { it.contains(target) }
        ALog.i("[节点] 账号校验 target=$target 命中=${hit ?: "无"} (文本数=${texts.size})")
        return hit != null
    }

    /** 【调试】列出所有可点节点（文本 + viewId），供日志排障选号页 */
    fun clickableDump(): List<String> = root()?.let { r ->
        walk(r).filter { it.isClickable || clickableOf(it).isClickable }
            .mapNotNull { n ->
                val t = n.text?.toString()?.trim()
                val id = n.viewIdResourceName ?: ""
                if (t.isNullOrBlank() && id.isBlank()) null
                else "${t ?: "(无文本)"} [$id]"
            }.distinct().take(30)
    } ?: emptyList()

    /** 当前窗口所有节点文本（供日志排障） */
    fun allTexts(): List<String> = root()?.let { walk(it).mapNotNull { n -> n.text?.toString() } }
        ?: emptyList()

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = n
        var guard = 0
        while (cur != null && !cur.isClickable && guard++ < 6) cur = cur.parent
        return cur ?: n
    }

    private fun walk(n: AccessibilityNodeInfo, acc: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        acc.add(n)
        for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { walk(it, acc) }
        return acc
    }
}

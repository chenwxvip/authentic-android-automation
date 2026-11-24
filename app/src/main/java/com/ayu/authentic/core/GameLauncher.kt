package com.ayu.authentic.core

import android.content.Context
import android.content.Intent
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog

/**
 * MVP 阶段一 · 通过包名强制拉起《三角洲行动》。
 *
 * 不依赖无障碍服务、不依赖截图、不依赖协程：
 * 逐个候选包名取 LaunchIntent，能 start 就返回命中包名。
 */
class GameLauncher(private val context: Context) {

    /** 已安装、且能取到启动入口的包名；没有则 null */
    fun resolvePackage(): String? =
        Config.GAME_PACKAGES.firstOrNull { pkg ->
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                .getOrDefault(false)
        }

    /**
     * 强制拉起游戏。
     * @return 命中的包名；失败返回 null
     */
    fun launch(): String? {
        val pm = context.packageManager
        for (pkg in Config.GAME_PACKAGES) {
            val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrElse {
                ALog.e("[拉起] $pkg 启动失败: ${it.message}")
                false
            }
            if (ok) {
                ALog.i("[拉起] 已通过包名 $pkg 启动三角洲行动")
                return pkg
            }
        }
        ALog.w("[拉起] 未找到可启动的包名：${Config.GAME_PACKAGES.joinToString()}")
        return null
    }
}

package com.ayu.authentic

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ayu.authentic.core.AccountConfig
import com.ayu.authentic.core.AccountRepository
import com.ayu.authentic.core.NavScheduler
import com.ayu.authentic.core.StationBoard
import com.ayu.authentic.service.CaptureService
import com.ayu.authentic.service.KeepAliveService
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.CaptureState
import com.ayu.authentic.vision.TemplateStore

/**
 * MVP 阶段三 · 控制台（多账号登录轮询）。
 *
 * 底座：① 无障碍 / ③ MediaProjection 授权截图（阶段一、二已验证）
 * 账号：录入（备注 + 渠道 QQ/微信）、列表展示、删除、清空
 * 轮询：单账号测试（重点）/ 全量轮询 / 停止
 *
 * 建议顺序：① 开无障碍 → ③ 授权截图 → 配置账号 → 单账号测试
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var stationView: TextView
    private lateinit var scheduleBtn: Button
    private lateinit var nextCheckView: TextView
    private lateinit var accountList: LinearLayout
    private lateinit var noteInput: EditText
    private var pendingChannel = AccountConfig.Channel.QQ
    private lateinit var channelBtn: Button
    /** 防呆：截图未就绪时，用户授权成功后自动继续执行的轮询动作 */
    private var pendingPoll: (() -> Unit)? = null
    /** [055] 「开始定时」按钮的5秒倒数任务与剩余秒数 */
    private var countdownRunnable: Runnable? = null
    private var countdownValue = 0
    /** 【058】倒数期间为 true：巡检暂不触发，避免抢在首轮摸底前进游戏 */
    private var countdownActive = false
    /** 【059】定时按钮两态文案：开着显示"关闭定时"，关着显示"开始定时" */
    private val scheduleOnText = "■ 关闭定时（巡检中）"
    private val scheduleOffText = "⏱ 开始定时（倒数5s自动跑流水线）"

    private val repo by lazy { AccountRepository(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshLog = object : Runnable {
        override fun run() {
            logView.text = ALog.tail(45).joinToString("\n").ifEmpty { "（暂无日志）" }
            handler.postDelayed(this, 1200)
        }
    }
    /** 台子看板每秒刷新，倒计时实时走。【057】每秒巡检：看板有可收台子就自动跑完整流水线收种 */
    private val refreshStation = object : Runnable {
        override fun run() {
            if (::stationView.isInitialized) {
                // 【065】看板展示所有账号，每个账号一段4台倒计时
                val notes = repo.all().map { it.note }
                stationView.text = StationBoard.linesAll(this@MainActivity, notes).joinToString("\n")
            }
            // 【059】每秒刷新定时按钮(开/关两态)+下次检测倒计时，直观判断定时是否在工作
            updateScheduleUi()
            // 【065】自动巡检：首轮摸底依次跑所有账号；之后按账号看板可收触发对应账号流水线。
            // 【058】倒数5秒期间不抢跑，倒数结束才交给巡检。
            if (countdownActive) {
                handler.postDelayed(this, 1000)
                return
            }
            val (note, reason) = NavScheduler.checkAndFire(this@MainActivity)
            if (note != null) {
                val isProbe = NavScheduler.needProbe(this@MainActivity) || NavScheduler.probeIndex(this@MainActivity) >= 0
                startPollWithGuard { firePipelineOnce(note, reason, isProbe) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * 【057修复并发】统一触发入口：先查运行锁，已在跑就跳过（防两条流水线并发互相点乱坐标）。
     * 倒数首轮和每秒巡检都汇到这里，保证同一时刻只有一条流水线。
     * 锁在服务协程 finally 里释放，之后巡检才能触发下一轮。
     */
    private fun firePipelineOnce(note: String, reason: String, isProbe: Boolean) {
        if (NavScheduler.isRunning(this)) {
            ALog.i("[流水线][触发] 跳过（已有流水线在跑）：$reason")
            return
        }
        val accounts = repo.all()
        val index = accounts.indexOfFirst { it.note == note }
        if (index < 0) {
            ALog.w("[流水线][触发] 账号 $note 不在列表中，跳过：$reason")
            if (isProbe) NavScheduler.advanceProbe(this, accounts.size)
            return
        }
        NavScheduler.setRunning(this, true)
        ALog.i("[流水线][触发] $reason → 账号$index($note)${if (isProbe) " [摸底]" else ""}")
        // 【065】摸底跑完一个账号后推进游标；服务层在流水线 finally 释放锁后，巡检会自动触发下一个
        if (isProbe) NavScheduler.advanceProbe(this, accounts.size)
        KeepAliveService.fullPipeline(this, index)
    }

    /**
     * 【059】每秒刷新定时按钮文案 + 下次检测倒计时，让用户直观判断"定时是否还开着、在不在倒计时"。
     * 开：按钮=关闭定时，下方=下次检测 HH:mm:ss（还剩 Ns）· 或"流水线运行中"
     * 关：按钮=开始定时，下方隐藏。
     */
    private fun updateScheduleUi() {
        if (!::scheduleBtn.isInitialized) return
        val enabled = NavScheduler.isEnabled(this)
        val countingDown = countdownRunnable != null
        if (countingDown) return // 倒数期间的文案由倒数 tick 自己管，别覆盖
        if (enabled) {
            if (scheduleBtn.text.toString() != scheduleOnText) scheduleBtn.setText(scheduleOnText)
            nextCheckView.visibility = TextView.VISIBLE
            if (NavScheduler.isRunning(this)) {
                nextCheckView.text = "🔄 流水线运行中：正在收菜种菜…（完成后自动继续巡检）"
            } else {
                val next = NavScheduler.nextMs(this)
                val remain = NavScheduler.remainSec(this)
                val nextStr = if (next > 0) NavScheduler.fmt(next) else "--:--:--"
                nextCheckView.text = "⏱ 定时巡检中 · 下次检测 $nextStr（还剩 ${remain}s）· 看板可收即自动收种"
            }
        } else {
            if (scheduleBtn.text.toString() != scheduleOffText) scheduleBtn.setText(scheduleOffText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 日志落盘到 app 私有外部目录（无需存储权限，adb 可直接 pull）
        val logFile = java.io.File(getExternalFilesDir(null), "authentic_log.txt")
        ALog.init(logFile.absolutePath)
        ALog.i("[启动] 日志文件: ${logFile.absolutePath}")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }

        root.addView(TextView(this).apply {
            text = "Authentic · MVP 阶段三\n多账号登录轮询（跳过特勤处）"
            textSize = 17f
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "顺序：① 开无障碍 → ③ 授权截图 → 配账号 → 单账号测试"
            textSize = 11f
            setPadding(0, 0, 0, 16)
        })

        // ---------- 定时开关（账号看板上边，单独一行）----------
        scheduleBtn = Button(this).apply {
            text = scheduleOffText
            textSize = 14f
            setPadding(20, 8, 20, 8)
            setOnClickListener {
                // 倒数中再点 = 取消
                if (countdownRunnable != null) {
                    countdownRunnable?.let { handler.removeCallbacks(it) }
                    countdownRunnable = null
                    countdownActive = false
                    setText(scheduleOffText)
                    nextCheckView.text = "定时已关闭"
                    NavScheduler.setEnabled(this@MainActivity, false)
                    NavScheduler.clearProbe(this@MainActivity)
                    ALog.i("[定时] 用户取消倒数，自动巡检已关闭")
                    toast("已取消定时启动")
                    return@setOnClickListener
                }
                // 【059】定时已开启 → 再点 = 关闭定时（按钮文案与动作一致）
                if (NavScheduler.isEnabled(this@MainActivity)) {
                    NavScheduler.setEnabled(this@MainActivity, false)
                    NavScheduler.clearProbe(this@MainActivity)
                    setText(scheduleOffText)
                    nextCheckView.text = "定时已关闭"
                    ALog.i("[定时] 用户点按钮关闭定时，自动巡检已停用")
                    toast("已关闭定时")
                    return@setOnClickListener
                }
                // 【057/065】开启常驻巡检：倒数5秒→首轮依次摸底所有账号→之后按账号看板可收触发
                NavScheduler.setEnabled(this@MainActivity, true)
                NavScheduler.setRunning(this@MainActivity, false)
                NavScheduler.resetProbe(this@MainActivity)   // 【065】摸底游标归零，从账号1开始
                NavScheduler.reschedule(this@MainActivity)
                countdownActive = true
                ALog.i("[定时] 开启自动巡检：倒数5秒→依次摸底全部账号→之后按账号可收触发")
                toast("自动巡检已开启：可收自动收种，再点取消")
                countdownValue = 5
                nextCheckView.visibility = TextView.VISIBLE
                val tick = object : Runnable {
                    override fun run() {
                        if (countdownValue > 0) {
                            setText("⏱ 倒数 ${countdownValue}s（点此取消）")
                            nextCheckView.text = "⏱ ${countdownValue}s 后自动进游戏收菜种菜…"
                            ALog.i("[定时] 倒数 ${countdownValue}s")
                            countdownValue--
                            handler.postDelayed(this, 1_000L)
                        } else {
                            handler.removeCallbacks(this)
                            countdownRunnable = null
                            countdownActive = false
                            // 【059】不设回"开始定时"：定时已开启，交给 updateScheduleUi 显示"关闭定时"+实时倒计时
                            // 【065】倒数完成 → 交给 refreshStation 巡检统一触发首个摸底账号（依次摸所有号）
                            ALog.i("[定时] 倒数完成 → 巡检接管，开始首轮摸底（依次跑所有账号）")
                        }
                    }
                }
                countdownRunnable = tick
                tick.run() // 立即显示 5s
            }
        }
        root.addView(scheduleBtn)
        nextCheckView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(200, 80, 20))
            visibility = TextView.GONE
            setPadding(4, 0, 0, 8)
        }
        root.addView(nextCheckView)

        // ---------- 台子看板（顶部，实时倒计时）----------
        stationView = TextView(this).apply {
            text = "── 特勤处台子状态 ──"
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(30, 90, 160))
            setBackgroundColor(android.graphics.Color.rgb(235, 243, 252))
            setPadding(16, 12, 16, 12)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(stationView)

        // ---------- 底座 3 个按钮 ----------
        root.addView(Button(this).apply {
            text = "① 开启无障碍权限"
            setOnClickListener {
                if (isAccessibilityEnabled()) {
                    ALog.i("[无障碍] 已开启 -> 弹窗「无障碍已就绪」")
                    AlertDialog.Builder(this@MainActivity).setTitle("提示")
                        .setMessage("无障碍已就绪").setPositiveButton("确定", null).show()
                } else {
                    ALog.i("[无障碍] 未开启 -> 跳转系统设置页")
                    toast("请手动开启本应用的无障碍服务")
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { ALog.e("[无障碍] 打开设置页失败: ${it.message}") }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "② 强制拉起游戏"
            setOnClickListener { KeepAliveService.start(this@MainActivity) }
        })
        root.addView(Button(this).apply {
            text = "③ 授权屏幕截图（MediaProjection）"
            setOnClickListener { requestCapture() }
        })

        // ---------- 账号配置 ----------
        root.addView(TextView(this).apply {
            text = "── 账号列表 ──"
            setPadding(0, 20, 0, 8)
        })
        accountList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(accountList)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        noteInput = EditText(this).apply {
            hint = "账号备注（如 小号1）"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(noteInput)
        channelBtn = Button(this).apply {
            text = "渠道:QQ"
            setOnClickListener {
                pendingChannel = if (pendingChannel == AccountConfig.Channel.QQ)
                    AccountConfig.Channel.WECHAT else AccountConfig.Channel.QQ
                text = "渠道:${pendingChannel.cn}"
            }
        }
        row.addView(channelBtn)
        root.addView(row)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "＋添加"
            setOnClickListener {
                val note = noteInput.text.toString().trim()
                if (note.isEmpty()) { toast("请输入账号备注"); return@setOnClickListener }
                repo.add(AccountConfig(note = note, channel = pendingChannel))
                noteInput.setText("")
                renderAccounts()
                toast("已添加：$note · ${pendingChannel.cn}")
            }
        })
        btnRow.addView(Button(this).apply {
            text = "清空"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("确认")
                    .setMessage("清空全部账号？").setPositiveButton("确定") { _, _ ->
                        repo.clear(); renderAccounts()
                    }.setNegativeButton("取消", null).show()
            }
        })
        root.addView(btnRow)

        // ---------- 轮询控制 ----------
        root.addView(TextView(this).apply {
            text = "── 轮询控制 ──"
            setPadding(0, 20, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "▶ 单账号测试（第 1 个号）"
            setOnClickListener {
                ALog.i("[控制台] 单账号测试")
                startPollWithGuard {
                    KeepAliveService.pollSingle(this@MainActivity, 0)
                    toast("单账号测试已启动")
                }
            }
        })
        root.addView(Button(this).apply {
            text = "▶▶ 全量轮询（所有账号）"
            setOnClickListener {
                ALog.i("[控制台] 全量轮询")
                startPollWithGuard {
                    KeepAliveService.pollAll(this@MainActivity)
                    toast("全量轮询已启动")
                }
            }
        })
        root.addView(Button(this).apply {
            text = "■ 停止"
            setOnClickListener {
                NavScheduler.setEnabled(this@MainActivity, false)
                NavScheduler.setRunning(this@MainActivity, false)
                NavScheduler.clearProbe(this@MainActivity)
                countdownRunnable?.let { handler.removeCallbacks(it) }
                countdownRunnable = null
                countdownActive = false
                KeepAliveService.stop(this@MainActivity)
                CaptureService.stop(this@MainActivity)
                ALog.i("[定时] 停止：巡检已关闭，运行锁已清")
                toast("已停止（自动巡检已关闭）")
            }
        })

        // ---------- 【023 阶段1验证】取色判断 QQ登录/模式页，不进大厅 ----------
        root.addView(TextView(this).apply {
            text = "── 023 阶段1验证（取色判断）──"
            setPadding(0, 20, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "🧪 023 取色判断→点QQ登录→停（验证）"
            setOnClickListener {
                ALog.i("[控制台] 023 阶段1验证")
                startPollWithGuard {
                    KeepAliveService.step1Verify(this@MainActivity)
                    toast("023 阶段1验证已启动")
                }
            }
        })

        // ---------- 【038 换号验证】退出当前号 → 切到指定QQ号 → 收菜种菜 ----------
        root.addView(TextView(this).apply {
            text = "── 038 换号验证（退出→切号→收菜种菜）──"
            setPadding(0, 20, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "🔄 换号验证（切到账号列表第2个号并收菜种菜）"
            setOnClickListener {
                val target = AccountRepository(this@MainActivity).all().getOrNull(1)?.note
                if (target.isNullOrBlank()) {
                    toast("请先在上方账号列表添加至少 2 个账号")
                    return@setOnClickListener
                }
                ALog.i("[控制台] 换号验证 → $target")
                startPollWithGuard {
                    KeepAliveService.switchAccount(this@MainActivity, target)
                    toast("换号到 $target 已启动")
                }
            }
        })

        // ---------- 【完整流水线】登录 → 收菜种菜 → 退出 ----------
        root.addView(TextView(this).apply {
            text = "── 完整流水线（登录→收菜→退出）──"
            setPadding(0, 20, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "🚀 040 完整流水线（账号1）"
            setOnClickListener {
                ALog.i("[控制台] 040 完整流水线启动")
                startPollWithGuard {
                    KeepAliveService.fullPipeline(this@MainActivity, 0)
                    toast("完整流水线已启动")
                }
            }
        })

        // ---------- 特勤处一键收种（独立，不影响登录轮询）----------
        root.addView(TextView(this).apply {
            text = "── 特勤处（独立）──"
            setPadding(0, 20, 0, 8)
        })
        root.addView(Button(this).apply {
            text = "🌾 特勤处一键收种（4台子）"
            setOnClickListener {
                ALog.i("[控制台] 特勤处一键收种")
                startPollWithGuard {
                    KeepAliveService.teqinOneClick(this@MainActivity)
                    toast("特勤处一键收种已启动")
                }
            }
        })

        // ---------- 日志面板 ----------
        root.addView(TextView(this).apply { text = "── 运行日志 ──"; setPadding(0, 16, 0, 8) })
        logView = TextView(this).apply {
            text = "（暂无日志）"; textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFF111111.toInt()); setTextColor(0xFF33FF88.toInt())
        }
        root.addView(logView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 420))

        // 【019修复】外层包 ScrollView：账号多时整页可滚动，底部按钮不会被挤出屏幕
        setContentView(ScrollView(this).apply { addView(root) })
        TemplateStore.init(this)
        renderAccounts()
    }

    private fun renderAccounts() {
        accountList.removeAllViews()
        val list = repo.all()
        if (list.isEmpty()) {
            accountList.addView(TextView(this).apply { text = "（暂无账号，请添加）"; textSize = 12f })
            return
        }
        list.forEachIndexed { i, a ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply {
                text = "${i + 1}. ${a.label()}"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(Button(this).apply {
                text = "删"
                setOnClickListener { repo.remove(a.id); renderAccounts() }
            })
            r.addView(Button(this).apply {
                text = "单测"
                setOnClickListener {
                    startPollWithGuard {
                        KeepAliveService.pollSingle(this@MainActivity, i)
                        toast("单测：${a.label()}")
                    }
                }
            })
            accountList.addView(r)
        }
    }

    /**
     * 防呆保护：启动轮询前先确认截图服务真的在出帧。
     * 未就绪时不再让状态机空转 60 轮，而是弹窗说明并自动拉起一次授权弹窗；
     * 用户点「允许」后 onActivityResult 会自动接着执行刚才想跑的轮询。
     */
    private fun startPollWithGuard(poll: () -> Unit) {
        if (CaptureState.isLive(this)) {
            ALog.i("[防呆] 截图服务正常（最近 15s 有帧），直接启动轮询")
            poll()
            return
        }
        val reason = if (CaptureState.isRunning(this))
            "截图服务在跑但长时间没有画面帧（可能被系统回收），需要重新授权"
        else "截图服务未启动，图色引擎拿不到游戏画面"
        ALog.w("[防呆] $reason，暂停轮询并请求授权")
        AlertDialog.Builder(this)
            .setTitle("需要先授权屏幕截图")
            .setMessage("$reason。\n\n点「去授权」后会弹出系统录屏确认框，允许后将自动继续本次轮询。")
            .setCancelable(false)
            .setPositiveButton("去授权") { _, _ ->
                pendingPoll = poll
                requestCapture()
            }
            .setNegativeButton("取消") { _, _ ->
                ALog.i("[防呆] 用户取消，未启动轮询")
                toast("已取消：未启动轮询")
            }
            .show()
    }

    private fun requestCapture() {
        ALog.i("[截图] 请求 MediaProjection 授权")
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE) }
            .onFailure {
                ALog.e("[截图] 授权弹窗失败: ${it.message}")
                toast("无法打开截图授权")
                pendingPoll = null
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                ALog.i("[截图] 已授权 MediaProjection，启动 CaptureService")
                CaptureService.start(this, data)
                toast("截图已授权，正在建立画面帧")
                val p = pendingPoll
                pendingPoll = null
                if (p != null) {
                    // 等 VirtualDisplay 建起来、首帧到达后再启动轮询，避免刚授权就空转
                    ALog.i("[防呆] 授权完成，2.5s 后自动继续之前请求的轮询")
                    handler.postDelayed({
                        if (CaptureState.isLive(this)) {
                            ALog.i("[防呆] 已确认有实时帧，自动启动轮询")
                            p()
                        } else {
                            ALog.w("[防呆] 授权后仍未拿到帧，轮询未启动，请手动再点一次")
                            toast("截图帧尚未就绪，请手动再点一次轮询")
                        }
                    }, 2500)
                }
            } else {
                ALog.w("[截图] 用户拒绝授权 (result=$resultCode)")
                toast("未授权截图，轮询未启动")
                pendingPoll = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        logView.text = ALog.tail(45).joinToString("\n").ifEmpty { "（暂无日志）" }
        stationView.text = StationBoard.linesAll(this, repo.all().map { it.note }).joinToString("\n")
        updateScheduleUi() // 【059】重启/切回前台时同步按钮开/关两态
        handler.postDelayed(refreshLog, 1200)
        handler.postDelayed(refreshStation, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLog)
        handler.removeCallbacks(refreshStation)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        if (enabled.isNullOrBlank()) return false
        return enabled.split(':').any { p ->
            val s = p.trim()
            s == "com.ayu.authentic/.service.AuthenticAccessibilityService" ||
                (s.startsWith("com.ayu.authentic/") && s.endsWith("AuthenticAccessibilityService"))
        }
    }

    companion object { private const val REQ_CAPTURE = 1001 }
}

package com.ayu.authentic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.ayu.authentic.config.Config
import com.ayu.authentic.core.AccountPollMachine
import com.ayu.authentic.core.Step1VerifyMachine
import com.ayu.authentic.core.TeqinFarmer
import com.ayu.authentic.core.GameLauncher
import com.ayu.authentic.core.NavScheduler
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.FrameBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MVP 阶段二 · 前台保活 + 单点闭环调度服务。
 *
 * 默认 action（阶段一按钮②）：仅强制拉起游戏。
 * ACTION_POLL_SINGLE（阶段三）：单账号登录轮询闭环（重点先跑通这个）
 * ACTION_POLL_ALL（阶段三）：多账号全量轮询
 * ACTION_STOP：停止
 *
 * 截图帧由 CaptureService(MediaProjection) 通过 FrameBus 提供；若未授权，
 * 状态机会持续等待并打日志，不会崩、不会误点。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var machine: AccountPollMachine? = null
    /** 特勤处 farmer（独立，与登录轮询 machine 完全隔离） */
    private var farmer: TeqinFarmer? = null
    /** 【023】阶段1验证机（独立，不碰登录主流程） */
    private var step1Machine: Step1VerifyMachine? = null

    override fun onCreate() {
        super.onCreate()
        ALog.init(java.io.File(getExternalFilesDir(null), "authentic_log.txt").absolutePath)
        createChannel()
        ALog.i("[保活] 前台服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("保活中"))

        when (intent?.action) {
            ACTION_STOP -> {
                ALog.i("[保活] 收到停止指令")
                machine?.stop()
                farmer?.stop()
                step1Machine?.stop()
                toast("已停止")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEQIN_HARVEST -> {
                ALog.i("[保活] === 特勤处收菜（独立）===")
                toast("特勤处收菜启动")
                launchTeqin(harvest = true, stationIndex = 0)
            }
            ACTION_TEQIN_PLANT -> {
                ALog.i("[保活] === 特勤处种菜（独立）===")
                toast("特勤处种菜启动")
                launchTeqin(harvest = false, stationIndex = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0)
            }
            ACTION_TEQIN_ONEClick -> {
                ALog.i("[保活] === 特勤处一键收种（独立）===")
                toast("特勤处一键收种启动")
                launchTeqin(oneclick = true, harvest = false, stationIndex = 0)
            }
            ACTION_STEP1_VERIFY -> {
                ALog.i("[保活] === 023 阶段1验证：取色判断 QQ登录/模式页，不进大厅 ===")
                toast("023 阶段1验证启动")
                launchStep1Verify()
            }
            ACTION_SWITCH_ACCOUNT -> {
                val note = intent?.getStringExtra(EXTRA_TARGET_NOTE).orEmpty()
                ALog.i("[保活] === 038 换号验证：切到 $note ===")
                toast("038 换号启动：$note")
                launchSwitchAccount(note)
            }
            ACTION_FULL_PIPELINE -> {
                val idx = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0
                val skipBp = intent?.getBooleanExtra(EXTRA_SKIP_BREAKPOINT, false) ?: false
                ALog.i("[保活] === 完整流水线：登录→收菜→退出（账号$idx，跳过断点=$skipBp）===")
                toast("完整流水线启动")
                launchFullPipeline(idx, skipBp)
            }
            ACTION_POLL_SINGLE -> {
                ALog.i("[保活] === 阶段三：单账号登录轮询测试 ===")
                toast("单账号轮询启动")
                launchPoll(single = true, index = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0)
            }
            ACTION_POLL_ALL -> {
                ALog.i("[保活] === 阶段三：多账号全量轮询 ===")
                toast("多账号轮询启动")
                launchPoll(single = false, index = 0)
            }
            else -> {
                // 阶段一行为：只拉起游戏
                ALog.i("[保活] 收到拉起指令（阶段一行为）")
                toast("正在拉起三角洲...")
                val pkg = GameLauncher(applicationContext).launch()
                if (pkg == null) {
                    toast("未检测到三角洲行动，请先安装游戏")
                    ALog.w("[保活] 未检测到游戏，拉起失败")
                } else {
                    ALog.i("[保活] 拉起完成：$pkg")
                }
            }
        }
        return START_STICKY
    }

    /** 拉起 + 等待加载 + 跑多账号登录轮询状态机 */
    private fun launchPoll(single: Boolean, index: Int) {
        machine?.stop()
        scope.launch {
            // 服务层防呆兜底：截图帧不可用时直接中止，不再空转 60 轮
            if (!FrameBus.hasFrame()) {
                ALog.w("[轮询][防呆] 没有实时画面帧，中止本次轮询（不会空转）。请先点③授权屏幕截图")
                toast("未授权截图，轮询已中止（请先授权）")
                return@launch
            }
            if (!AuthenticAccessibilityService.isReady()) {
                ALog.w("[轮询] 无障碍服务未连接，节点/手势点击将不可用")
                toast("请先开启无障碍（按钮①）")
            }
            val m = AccountPollMachine(applicationContext)
            machine = m
            if (single) m.runSingle(index) else m.runAll()
            ALog.i("[轮询] 状态机返回 phase=${m.phase}")
        }
    }

    /**
     * 【023 阶段1验证】独立通道：取色判断 -> 决定点QQ登录 -> 处理授权 -> 截图停住。
     * 不碰 machine / farmer，不进大厅不收菜。
     */
    private fun launchStep1Verify() {
        step1Machine?.stop()
        scope.launch {
            if (!FrameBus.hasFrame()) {
                ALog.w("[023][防呆] 没有实时画面帧，中止。请先点③授权屏幕截图")
                toast("未授权截图，023验证已中止（请先授权）")
                return@launch
            }
            if (!AuthenticAccessibilityService.isReady()) {
                ALog.w("[023] 无障碍未连接，点击将不可用")
                toast("请先开启无障碍（按钮①）")
            }
            val m = Step1VerifyMachine(applicationContext)
            step1Machine = m
            m.run()
            ALog.i("[023] 验证机返回 phase=${m.phase} state=${m.stateResult}")
        }
    }

    /**
     * 【038 换号验证】退出当前号 → 切到目标号 → 收菜种菜。独立协程 + 独立实例。
     */
    private fun launchSwitchAccount(note: String) {
        step1Machine?.stop()
        scope.launch {
            if (!FrameBus.hasFrame()) {
                ALog.w("[038][防呆] 没有实时画面帧，中止。请先点③授权屏幕截图")
                toast("未授权截图，换号验证已中止")
                return@launch
            }
            if (!AuthenticAccessibilityService.isReady()) {
                ALog.w("[038] 无障碍未连接，点击将不可用")
                toast("请先开启无障碍（按钮①）")
            }
            val m = Step1VerifyMachine(applicationContext)
            step1Machine = m
            m.runSwitchAccount(note)
            ALog.i("[038] 换号验证机返回 phase=${m.phase}")
        }
    }

    /**
     * 【完整流水线】登录进大厅(AccountPollMachine) → 收菜段(Step1VerifyMachine) → 退出回登录页。
     * 两段串联在一条协程里，收菜段以回调方式插入 runOneAccount 的大厅节点。
     */
    private fun launchFullPipeline(index: Int, skipBreakpoint: Boolean = false) {
        machine?.stop(); step1Machine?.stop()
        scope.launch {
            // 【057】运行锁：流水线全程占用，结束/中止必须在 finally 里释放，否则巡检永久卡死
            NavScheduler.setRunning(applicationContext, true)
            try {
                // 防呆：等无障碍就绪（授权恢复时服务可能晚一拍重连）
                var waited = 0L
                while (!AuthenticAccessibilityService.isReady() && waited < 8_000L) {
                    kotlinx.coroutines.delay(300L); waited += 300L
                }
                ALog.i("[流水线] 无障碍就绪=${AuthenticAccessibilityService.isReady()} 等待${waited}ms")
                if (!FrameBus.hasFrame()) {
                    ALog.w("[流水线][防呆] 没有实时画面帧，中止。请先点③授权屏幕截图")
                    toast("未授权截图，流水线已中止")
                    return@launch
                }
                if (!AuthenticAccessibilityService.isReady()) {
                    ALog.w("[流水线] 无障碍仍未连接，中止")
                    toast("请先开启无障碍（按钮①）")
                    return@launch
                }
                // 拉起游戏回前台（点按钮时 App 在前台，手势会点在 App 上）
                val pkg = GameLauncher(applicationContext).launch()
                ALog.i("[流水线] 已把游戏带回前台 pkg=$pkg")
                kotlinx.coroutines.delay(3_000L)

                val harvestMachine = Step1VerifyMachine(applicationContext)
                step1Machine = harvestMachine
                val poll = AccountPollMachine(applicationContext)
                machine = poll
                poll.runFullPipeline(index) { acc -> harvestMachine.harvestSegment(skipBreakpoint, acc.note) }
                ALog.i("[流水线] 状态机返回 phase=${poll.phase}")

                // 退出到登录页后，把 Authentic 切回前台，方便点下一个按钮/下一个账号
                kotlinx.coroutines.delay(1_000L)
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(launchIntent)
                        ALog.i("[流水线] 已把 Authentic 切回前台")
                        toast("流水线结束，已切回 Authentic")
                    } else {
                        ALog.w("[流水线] 未找到 Authentic 启动 Intent")
                    }
                } catch (e: Exception) {
                    ALog.w("[流水线] 切回前台失败：${e.message}")
                }
            } finally {
                // 无论正常结束/防呆中止/异常，都释放运行锁，让巡检能继续下一轮
                NavScheduler.setRunning(applicationContext, false)
                ALog.i("[流水线] 运行锁已释放，巡检可继续下一轮")
            }
        }
    }

    /**
     * 【独立通道】特勤处收菜/种菜：与 launchPoll 完全并列的独立协程 + 独立实例，
     * 不碰 machine，不影响登录轮询主流程。
     */
    private fun launchTeqin(oneclick: Boolean = false, harvest: Boolean = false, stationIndex: Int = 0) {
        farmer?.stop()
        scope.launch {
            if (!FrameBus.hasFrame()) {
                ALog.w("[特勤处][防呆] 没有实时画面帧，中止。请先点③授权屏幕截图")
                toast("未授权截图，特勤处操作已中止")
                return@launch
            }
            if (!AuthenticAccessibilityService.isReady()) {
                ALog.w("[特勤处] 无障碍未连接，点击不可用")
                toast("请先开启无障碍（按钮①）")
            }
            val f = TeqinFarmer(applicationContext)
            farmer = f
            when {
                oneclick -> f.runHarvestAndPlant()
                harvest -> f.runHarvest()
                else -> f.runPlantOne(stationIndex)
            }
            ALog.i("[特勤处] 状态机返回 phase=${f.phase} stats=${f.lastStats()}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        machine?.stop()
        farmer?.stop()
        step1Machine?.stop()
        scope.cancel()
        ALog.i("[保活] 前台服务已销毁")
        super.onDestroy()
    }

    private fun toast(msg: String) {
        ALog.i("[Toast] $msg")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "保活与拉起", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "前台服务保活通知" }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Authentic · 保活中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "authentic_keepalive"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_POLL_SINGLE = "com.ayu.authentic.POLL_SINGLE"
        const val ACTION_POLL_ALL = "com.ayu.authentic.POLL_ALL"
        const val ACTION_STEP1_VERIFY = "com.ayu.authentic.STEP1_VERIFY"
        const val ACTION_SWITCH_ACCOUNT = "com.ayu.authentic.SWITCH_ACCOUNT"
        const val ACTION_FULL_PIPELINE = "com.ayu.authentic.FULL_PIPELINE"
        const val ACTION_TEQIN_HARVEST = "com.ayu.authentic.TEQIN_HARVEST"
        const val ACTION_TEQIN_PLANT = "com.ayu.authentic.TEQIN_PLANT"
        const val ACTION_TEQIN_ONEClick = "com.ayu.authentic.TEQIN_ONECLICK"
        const val ACTION_STOP = "com.ayu.authentic.CYCLE_STOP"
        const val EXTRA_INDEX = "account_index"
        const val EXTRA_TARGET_NOTE = "target_note"
        /** 定时触发的完整流水线=true：跳过断点挂起，收完直接退出（无人值守） */
        const val EXTRA_SKIP_BREAKPOINT = "skip_breakpoint"

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 阶段三：只跑单个账号（重点先跑通这个） */
        fun pollSingle(context: Context, index: Int = 0) {
            val i = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_POLL_SINGLE
                putExtra(EXTRA_INDEX, index)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 阶段三：跑全部账号 */
        fun pollAll(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).apply { action = ACTION_POLL_ALL }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 023 阶段1验证：取色判断 QQ登录/模式页，不进大厅 */
        fun step1Verify(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).apply { action = ACTION_STEP1_VERIFY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 038 换号验证：退出当前号 → 切到目标号 → 收菜种菜 */
        fun switchAccount(context: Context, targetNote: String) {
            val i = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_SWITCH_ACCOUNT
                putExtra(EXTRA_TARGET_NOTE, targetNote)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 完整流水线：登录进大厅 → 收菜种菜 → 退出回登录页
         *  @param skipBreakpoint true=定时触发，跳过断点挂起直接退出（无人值守） */
        fun fullPipeline(context: Context, index: Int = 0, skipBreakpoint: Boolean = false) {
            val i = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_FULL_PIPELINE
                putExtra(EXTRA_INDEX, index)
                putExtra(EXTRA_SKIP_BREAKPOINT, skipBreakpoint)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 特勤处收菜（独立，不影响登录轮询） */
        fun teqinHarvest(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).apply { action = ACTION_TEQIN_HARVEST }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 特勤处种菜（独立，stationIndex=台子序号0起） */
        fun teqinPlant(context: Context, stationIndex: Int = 0) {
            val i = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_TEQIN_PLANT; putExtra(EXTRA_INDEX, stationIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 特勤处一键收种（独立，遍历4台子：熟则收+种，倒计时跳过） */
        fun teqinOneClick(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).apply { action = ACTION_TEQIN_ONEClick }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}

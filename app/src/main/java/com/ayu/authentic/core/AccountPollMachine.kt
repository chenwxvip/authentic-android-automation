package com.ayu.authentic.core

import android.content.Context
import android.widget.Toast
import com.ayu.authentic.config.Config
import com.ayu.authentic.service.AuthenticAccessibilityService
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.FrameBus
import com.ayu.authentic.vision.FrameDump
import com.ayu.authentic.vision.TemplateStore
import kotlinx.coroutines.delay

/**
 * MVP 阶段九 · 多账号登录轮询状态机【固定步骤序列驱动】。
 *
 * 【为什么重写】阶段三~八一直卡在登录界面，根因是「整屏缩略图场景识别器」：
 * 8 个场景的相似度常年全在 0.7~0.96、彼此只差 0.02~0.06，
 * 所谓"识别到场景X"实际是噪声里挑最大值，状态机被它牵着乱点。
 * 本版彻底不再由分类器决定动作。
 *
 * 【2026-11-17 重构】改为真机口述校准坐标直投的闭环序列（实测跑通）：
 *   1 拉起游戏
 *   2 勾协议勾选框「我已阅读」（登录前固定点一次，防呆）
 *   3 点 QQ/微信 登录（横屏 2400x1080，tapRegion）
 *   4 竖屏授权页「同意」①（1080x2400 物理坐标，tapPhysical）
 *   5 二次授权页「同意」②（物理坐标直投）—— 关键：授权是两次同意，旧版只点一次
 *   6 进大厅 → 停留 → 点「前往游玩」进烽火地带
 *   7 行前备战页点右上角「设置」齿轮
 *   8 设置页点「退出登录」
 *   9 退出确认框点「确定」
 *   10 回到登录页 → 本号闭环完成
 *
 * 已删除阶段九中实测并不存在的「选号页切账号」与「弹窗内同意」步骤。
 * 图色帧只用于每步存一帧供真机校准，不驱动任何逻辑。
 * 每一步都打印清晰日志与实际点击坐标，真机实测后改 Config 即可校准，不动逻辑。
 */
class AccountPollMachine(private val context: Context) {

    enum class Phase { IDLE, RUNNING, DONE, STOPPED }

    @Volatile var phase: Phase = Phase.IDLE; private set
    @Volatile var running = false; private set
    @Volatile var currentIndex = -1; private set
    @Volatile var currentAccount: AccountConfig? = null; private set
    @Volatile var currentStep: Int = 0; private set

    /** 单账号闭环结果：供全量轮询判断是否跳过/继续 */
    sealed class AccountResult {
        object OK : AccountResult()              // 正常跑完闭环
        object NEED_RELOGIN : AccountResult()    // 本地登录态过期，需人工重新登录 → 跳过
        object FAILED : AccountResult()          // 其它失败 → 跳过
    }

    /** 本轮轮询中「需要重新登录」的账号 QQ 号列表（内存态，日志同步打印） */
    private val needRelogin = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun needReloginAccounts(): List<String> = needRelogin.toList()

    private val repo = AccountRepository(context)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stepFrameSaved = 0

    /** 跑完整轮询（所有账号） */
    suspend fun runAll() {
        if (running) return
        running = true; phase = Phase.RUNNING
        needRelogin.clear()
        TemplateStore.init(context)
        val accounts = repo.all()
        ALog.i("[轮询] === 固定序列·全量轮询，共 ${accounts.size} 个账号 ===")
        if (accounts.isEmpty()) {
            toast("账号列表为空，请先在主界面添加账号")
            phase = Phase.DONE; running = false; return
        }
        if (!AuthenticAccessibilityService.isReady()) {
            ALog.w("[轮询] 无障碍未连接：节点/手势点击不可用，仅记录")
            toast("请先开启无障碍（按钮①）")
        }
        for ((i, acc) in accounts.withIndex()) {
            if (!running) break
            currentIndex = i; currentAccount = acc
            ALog.i("[轮询] ---- 账号 ${i + 1}/${accounts.size}: ${acc.label()} ----")
            when (runOneAccount(acc)) {
                AccountResult.OK -> ALog.i("[轮询] 账号 ${acc.note} 闭环完成 ✓")
                AccountResult.NEED_RELOGIN -> {
                    needRelogin.add(acc.note)
                    ALog.w("[轮询] ⚠ 账号 ${acc.note} 需要重新登录（本地登录态过期），已跳过")
                    toast("账号 ${acc.note} 需重新登录，已跳过")
                }
                AccountResult.FAILED -> {
                    ALog.w("[轮询] ✗ 账号 ${acc.note} 失败，已跳过，继续下一个")
                }
            }
            // 账号之间不做 force-stop，避免解绑无障碍/截图服务；靠游戏内退出回到登录页衔接。
            delay(Config.STEP_GAP_MS)
        }
        ALog.i("[轮询] === 全部账号轮询完毕 ===需要重新登录的号: ${needRelogin.joinToString()} ===")
        toast(if (needRelogin.isEmpty()) "轮询完毕" else "轮询完毕，${needRelogin.size} 个号需重新登录")
        phase = Phase.DONE; running = false
    }

    /** 只跑单个账号（重点先跑通这个） */
    suspend fun runSingle(index: Int = 0) {
        if (running) return
        running = true; phase = Phase.RUNNING
        TemplateStore.init(context)
        val acc = repo.all().getOrNull(index)
        if (acc == null) {
            toast("账号列表为空或索引越界")
            phase = Phase.DONE; running = false; return
        }
        currentIndex = index; currentAccount = acc
        ALog.i("[轮询] === 固定序列·单账号: ${acc.label()} ===")
        toast("单账号测试: ${acc.label()}")
        when (runOneAccount(acc)) {
            AccountResult.OK -> toast("单账号测试结束：闭环完成")
            AccountResult.NEED_RELOGIN -> toast("单账号测试结束：该号需重新登录")
            AccountResult.FAILED -> toast("单账号测试结束：失败")
        }
        ALog.i("[轮询] === 单账号结束 ===")
        phase = Phase.DONE; running = false
    }

    /**
     * 【完整流水线】登录进大厅(步骤1~6) → 收菜段回调 → 退出回登录页(步骤7~11)。
     * 收菜段由外部注入（Step1VerifyMachine.harvestSegment），实现"登录管理+收菜作业"串联。
     * @param onLobby 进大厅后调用的收菜段，返回 true=继续退出，false=中止（保留现场）
     */
    suspend fun runFullPipeline(index: Int, onLobby: suspend (AccountConfig) -> Boolean) {
        if (running) return
        running = true; phase = Phase.RUNNING
        TemplateStore.init(context)
        val acc = repo.all().getOrNull(index)
        if (acc == null) {
            toast("账号列表为空或索引越界")
            phase = Phase.DONE; running = false; return
        }
        currentIndex = index; currentAccount = acc
        ALog.i("[流水线] ========== 完整流水线开始：${acc.label()} ==========")
        toast("完整流水线：${acc.label()}")
        when (runOneAccount(acc, onLobby)) {
            AccountResult.OK -> toast("流水线结束：登录→收菜→退出 闭环完成")
            AccountResult.NEED_RELOGIN -> toast("流水线结束：该号需重新登录")
            AccountResult.FAILED -> toast("流水线结束：失败（看日志）")
        }
        ALog.i("[流水线] ========== 完整流水线结束 ==========")
        phase = Phase.DONE; running = false
    }

    /**
     * 【2026-11-17 全量轮询版】单账号闭环。
     * 相比 012 单账号版，新增：在 QQ 授权页「切换账号」→ 按 acc.note(QQ号) 节点匹配点目标号
     * → 账号校验 + 密码过期异常检测（异常则退回、标记 NEED_RELOGIN、跳过）。
     *
     * 闭环序列：
     *   1 拉起游戏 → 2 勾协议 → 3 点QQ登录 → 3.5 切到目标号(关键) → 4 同意① → 5 同意②
     *   → 6 大厅+前往游玩 → 7 设置齿轮 → 8 退出登录 → 9 确认 → 10 回登录页
     * 返回 AccountResult 供全量轮询判断继续/跳过。
     */
    private suspend fun runOneAccount(
        acc: AccountConfig,
        onLobby: (suspend (AccountConfig) -> Boolean)? = null
    ): AccountResult {
        stepFrameSaved = 0
        val g = AuthenticAccessibilityService.gesture
        val nodes = AuthenticAccessibilityService.nodes

        ALog.i("[${acc.note}] ===== 开始单账号闭环（全量轮询版）=====")

        // ---- 步骤 1：拉起游戏 ----
        step(1, "拉起游戏")
        val pkg = GameLauncher(context).launch()
        if (pkg == null) { ALog.w("[${acc.note}] 游戏未拉起，中止"); return AccountResult.FAILED }
        delay(Config.STEP_1_BOOT_MS)
        saveStepFrame(1)

        // ---- 步骤 2：勾协议勾选框 ----
        // 【016回退】撤掉 waitForLoginPage：游戏登录页是UE4渲染，UI树读不到"微信登录/QQ登录"，
        //  用UI树等必然超时且白等坏事事。改回纯固定等待(步骤1的45s已覆盖冷启动)。
        step(2, "勾选协议「我已阅读」")
        val cb = Config.Login.BTN_AGREE_CHECKBOX
        ALog.i("[${acc.note}] 步骤2：点协议勾选框 ((${cb.cx},${cb.cy}))")
        g?.tapRegion(cb) ?: ALog.w("[${acc.note}] 手势不可用：勾选框")
        delay(Config.STEP_GAP_MS)

        // ---- 步骤 3：点 QQ/微信 登录 ----
        step(3, "点「${acc.channel.cn}登录」")
        tapLogin(acc, g)
        delay(Config.STEP_3_WAIT_MS)
        saveStepFrame(2)

        // ---- 步骤 3.5：切到目标 QQ 号（核心）----
        if (acc.channel == AccountConfig.Channel.QQ) {
            step(4, "QQ授权页切到目标号 ${acc.note}")
            // 【修复时机】轮询等待授权页节点就绪（最多15s），替代死等；超时则判定页面没出来
            if (!waitForAuthPage(nodes, acc)) {
                ALog.w("[${acc.note}] 授权页未按时出现 → 判定失败跳过（可点节点dump=${nodes?.clickableDump()?.take(20)}）")
                escapePasswordPage(g, nodes, acc)
                return AccountResult.FAILED
            }
            // 先检查是不是一进来就弹了密码页（默认号登录态过期）
            if (nodes?.isPasswordPage() == true) {
                ALog.w("[${acc.note}] 进入授权页即检测到密码输入页 → 判定需重新登录，跳过")
                escapePasswordPage(g, nodes, acc)
                return AccountResult.NEED_RELOGIN
            }
            // 点「切换账号」：节点优先，坐标兜底
            val sw = nodes?.clickAnyText("切换账号")
            if (sw == null) {
                ALog.w("[${acc.note}] 未找到「切换账号」节点，用竖屏坐标兜底")
                g?.tapPhysical(Config.Login.BTN_SWITCH_QQ_ACCOUNT.cx, Config.Login.BTN_SWITCH_QQ_ACCOUNT.cy)
            } else ALog.i("[${acc.note}] 节点点「$sw」✓")
            delay(Config.STEP_GAP_MS)

            // 按 note(QQ号) 节点匹配点目标行（列表增减也不怕）
            val picked = nodes?.clickAccountRow(acc.note) ?: false
            ALog.i("[${acc.note}] 切号：clickAccountRow(${acc.note})=$picked")
            if (!picked) {
                ALog.w("[${acc.note}] 目标号 ${acc.note} 在QQ列表中未找到可点节点 → 跳过")
                ALog.w("[${acc.note}] 可点节点dump=${nodes?.clickableDump()?.take(20)}")
                escapePasswordPage(g, nodes, acc) // 尝试退回，避免卡在列表
                return AccountResult.NEED_RELOGIN
            }
            delay(Config.STEP_GAP_MS)

            // 防呆校验①：切号后是否又弹出密码页（该号登录态过期）
            if (nodes?.isPasswordPage() == true) {
                ALog.w("[${acc.note}] ⚠ 目标号 ${acc.note} 要求输入密码 → 本地登录态过期，标记需重新登录并跳过")
                escapePasswordPage(g, nodes, acc)
                return AccountResult.NEED_RELOGIN
            }
            // 防呆校验②【2026-11-17 修复误杀】：授权页显示的是昵称而非QQ号，
            //  旧版用 containsAccount(QQ号) 校验必然落空 → 误判失败。改为：只要授权页有「同意」
            //  二字，就证明已稳稳停在授权页，放行。精准切号由 clickAccountRow=true 本身保证。
            val onAuthPage = nodes?.hasAnyText("同意") == true
            if (!onAuthPage) {
                ALog.w("[${acc.note}] ⚠ 切号后未发现「同意」节点（文本=${nodes?.allTexts()?.take(15)}），判定失败跳过")
                escapePasswordPage(g, nodes, acc)
                return AccountResult.FAILED
            }
            ALog.i("[${acc.note}] ✅ 切号成功(picked=$picked)且已停在授权页(命中「同意」)，准备点同意")
        }

        // ---- 步骤 4：竖屏授权页 同意①（1080x2400 物理坐标直投）----
        step(5, "竖屏授权页点「同意」①")
        val a1 = Config.Login.BTN_AGREE_QQ_STEP1
        ALog.i("[${acc.note}] 步骤：物理直投同意① ((${a1.cx},${a1.cy}))")
        g?.tapPhysical(a1.cx, a1.cy) ?: ALog.w("[${acc.note}] 手势不可用：同意①")
        delay(Config.STEP_4_WAIT_MS)
        saveStepFrame(3)

        // ---- 步骤 5：二次授权页 同意② ----
        step(6, "二次授权页点「同意」②")
        val a2 = Config.Login.BTN_AGREE_QQ_STEP2
        ALog.i("[${acc.note}] 步骤：物理直投同意② ((${a2.cx},${a2.cy}))")
        g?.tapPhysical(a2.cx, a2.cy) ?: ALog.w("[${acc.note}] 手势不可用：同意②")
        delay(Config.STEP_LOBBY_WAIT_MS)
        saveStepFrame(4)

        // ---- 步骤 6：大厅 → 前往游玩 ----
        step(7, "进大厅 → 点「前往游玩」")
        ALog.i("[${acc.note}] ✅ 已进大厅，停留 ${Config.LOBBY_HOLD_MS}ms")
        toast("${acc.note}: 登录成功，已进大厅")
        delay(Config.LOBBY_HOLD_MS)
        val fz = Config.BTN_ENTER_FIRE_ZONE
        ALog.i("[${acc.note}] 点前往游玩 ((${fz.cx},${fz.cy}))")
        g?.tapRegion(fz) ?: ALog.w("[${acc.note}] 手势不可用：前往游玩")
        delay(Config.STEP_GAP_MS)
        saveStepFrame(5)

        // ---- 【流水线插入点】收菜段：进特勤处 → 4台子收种 → 返回大厅 ----
        if (onLobby != null) {
            step(7, "【流水线】收菜种菜段（进特勤处→4台子→返回大厅）")
            ALog.i("[流水线] 进入收菜段回调（当前在大厅/行前备战页）")
            val harvestOk = onLobby(acc)
            ALog.i("[流水线] 收菜段返回=$harvestOk，继续退出段")
            delay(Config.STEP_GAP_MS)
            saveStepFrame(55)
        }

        // ---- 步骤 7：设置齿轮（017方案A：安全点固定按返回关弹窗）----
        step(8, "行前备战页：按返回关弹窗 → 点右上角设置齿轮")
        clearPopupAtPreparePage(g, acc)
        val set = Config.Login.BTN_SETTINGS_GEAR_TOPRIGHT
        ALog.i("[${acc.note}] 点设置齿轮 ((${set.cx},${set.cy}))")
        g?.tapRegion(set) ?: ALog.w("[${acc.note}] 手势不可用：设置齿轮")
        delay(Config.STEP_GAP_MS)
        saveStepFrame(6)

        // ---- 步骤 8：退出登录 ----
        step(9, "设置页点「退出登录」")
        val lo = Config.Login.BTN_LOGOUT_SETTINGS_PAGE
        ALog.i("[${acc.note}] 点退出登录 ((${lo.cx},${lo.cy}))")
        g?.tapRegion(lo) ?: ALog.w("[${acc.note}] 手势不可用：退出登录")
        delay(Config.STEP_GAP_MS)
        saveStepFrame(7)

        // ---- 步骤 9：确认 ----
        step(10, "确认框点「确定」")
        val cf = Config.Login.BTN_LOGOUT_CONFIRM
        ALog.i("[${acc.note}] 点确认 ((${cf.cx},${cf.cy}))")
        g?.tapRegion(cf) ?: ALog.w("[${acc.note}] 手势不可用：确认")
        delay(Config.STEP_GAP_MS)
        saveStepFrame(8)

        // ---- 步骤 10：回到登录页 ----
        step(11, "回到登录页，本号完成")
        saveStepFrame(9)
        ALog.i("[${acc.note}] ✅ 本号闭环执行完毕（已回到登录页）")
        return AccountResult.OK
    }

    /**
     * 【016 修复】密码过期/切号失败时的兜底退出：
     * 旧版只按返回×2，结果在游戏主界面触发"确定退出登录"框后按返回取消，把确认框留在游戏里，
     * 污染下一轮起始状态。新版：返回×2 后主动点「确认」(1380,730) 走完退出，退回干净登录页。
     * 不做 force-stop（会解绑无障碍/截图服务）。
     */
    private suspend fun escapePasswordPage(
        g: com.ayu.authentic.gesture.GestureDispatcher?,
        nodes: com.ayu.authentic.gesture.NodeFinder?,
        acc: AccountConfig
    ) {
        ALog.w("[${acc.note}] 执行兜底退出（取消节点/返回×2 → 点确认框确认退登录）")
        val cancel = nodes?.clickAnyText("取消", "返回")
        if (cancel == null) g?.back(600) else delay(600)
        g?.back(600)
        // 返回×2 若触发了"确定退出登录"框，点确认走完退出，确保回到干净登录页
        val cf = Config.Login.BTN_LOGOUT_CONFIRM
        g?.tapRegion(cf)
        ALog.w("[${acc.note}] 兜底：已点确认框 ((${cf.cx},${cf.cy}))，确保退出到登录页")
    }

    private suspend fun tapLogin(acc: AccountConfig, g: com.ayu.authentic.gesture.GestureDispatcher?) {
        val region = when (acc.channel) {
            AccountConfig.Channel.QQ -> Config.Login.BTN_QQ_LOGIN
            AccountConfig.Channel.WECHAT -> Config.Login.BTN_WECHAT_LOGIN
        }
        ALog.i("[${currentAccount?.note}] 步骤1：点「${acc.channel.cn}登录」((${region.cx},${region.cy}))")
        g?.tapRegion(region) ?: ALog.w("[${currentAccount?.note}] 手势不可用")
    }

    /**
     * 【2026-11-17 修复时机】轮询等待 QQ 授权页就绪（最多 timeoutMs）。
     * 不再死等固定秒数：每 1s 查一次 UI 树，出现「切换账号」或「同意」即返回 true。
     * 这样授权页弹得早就快走，弹得晚也能等到，解决账号1死等6秒时页面还没出来的问题。
     */
    private suspend fun waitForAuthPage(
        nodes: com.ayu.authentic.gesture.NodeFinder?,
        acc: AccountConfig,
        timeoutMs: Long = 15_000L
    ): Boolean {
        if (nodes == null) { delay(timeoutMs); return false }
        val deadline = System.currentTimeMillis() + timeoutMs
        var tried = 0
        while (System.currentTimeMillis() < deadline) {
            tried++
            if (nodes.hasAnyText("切换账号", "同意")) {
                ALog.i("[${acc.note}] 授权页已就绪（第${tried}次轮询命中「切换账号/同意」）")
                return true
            }
            delay(1_000L)
        }
        ALog.w("[${acc.note}] ⚠ 等待授权页超时 ${timeoutMs}ms，最后文本=${nodes.allTexts().take(15)}")
        return false
    }

    /**
     * 【017 方案A】在"行前备战页"这个唯一安全点，固定按一次返回键关掉可能的礼包弹窗，
     * 然后重新点一次「前往游玩」把自己拉回行前备战页，确保后面点设置齿轮时界面正确。
     *
     * 为什么这样：
     *  - 礼包弹窗是 UE4 渲染，UI树读不到词 → 不能靠 hasPromoPopup 判断（016 教训）。
     *  - 无条件按返回曾在"确定退出登录"框上误伤（015 教训）→ 所以把按返回动作
     *    **锁定在行前备战页**：流程走到这里时界面确定是行前备战页(或弹窗盖其上)，
     *    绝不可能是"确定退出登录"框，按返回只可能关弹窗或退回大厅，都安全。
     *  - 按返回若退回大厅，则再点一次「前往游玩」回到行前备战页，消除副作用。
     */
    private suspend fun clearPopupAtPreparePage(
        g: com.ayu.authentic.gesture.GestureDispatcher?,
        acc: AccountConfig
    ) {
        if (g == null) return
        ALog.i("[${acc.note}] 弹窗清理(方案A)：行前备战页安全点，按一次返回键关礼包弹窗")
        g.back(1_500L)
        // 不做"重新点前往游玩"：那个坐标(447,980)在行前备战页会误触底部"部门/交易行"标签。
        // 按返回只有两种结果：①关弹窗(仍在行前备战页) ②退回大厅。
        // 而大厅与行前备战页的右上角设置齿轮是同一个位置(2260,42)，所以两种结果都能继续点设置。
    }

    private fun step(n: Int, desc: String) {
        currentStep = n
        ALog.i("[${currentAccount?.note}] ===== 步骤 $n: $desc =====")
    }

    /** 每步存一帧真实画面（供真机实测后校准坐标），不再参与任何判断 */
    private fun saveStepFrame(step: Int) {
        if (stepFrameSaved == step) return
        val f = FrameBus.snapshot() ?: return
        FrameDump.save(context, f, "step_$step")
        f.recycle()
        stepFrameSaved = step
    }

    private fun toast(msg: String) {
        ALog.i("[Toast] $msg")
        mainHandler.post { Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun stop() {
        ALog.i("[轮询] 收到 stop()")
        running = false; phase = Phase.STOPPED
    }
}

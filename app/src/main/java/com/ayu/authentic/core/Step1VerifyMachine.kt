package com.ayu.authentic.core

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import com.ayu.authentic.config.Config
import com.ayu.authentic.service.AuthenticAccessibilityService
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.FrameBus
import com.ayu.authentic.vision.FrameDump
import com.ayu.authentic.vision.TemplateStore
import kotlinx.coroutines.delay

/**
 * 【app-debug023 · 阶段1验证机】冷启动 -> 取色判断 -> 决定是否点 QQ 登录 -> 停。
 *
 * 【本版边界，务必遵守】
 *  - 取色判断 (1400,850) 亮度 >100=登录页点QQ登录；<=100=模式选择页绝对不点。
 *  - 若点了 QQ 登录，等 10s 处理可能的 QQ 授权页（017 授权逻辑，不切号直接同意）。
 *  - 处理完立即停止：**不点烽火地带 / 不点前往游玩 / 不进大厅**。
 *  - 最后 screencap 存一帧 023_step1_model_page.png，并记录当前停在哪个状态。
 *  - 与登录轮询主流程 AccountPollMachine 完全隔离，不收菜不种菜。
 */
class Step1VerifyMachine(private val context: Context) {

    enum class Phase { IDLE, RUNNING, DONE, STOPPED }
    @Volatile var phase: Phase = Phase.IDLE; private set
    @Volatile var running = false; private set

    /** 本轮取色判断结论：LOGIN_PAGE / MODE_PAGE，供日志与截图标记 */
    @Volatile var stateResult: String = ""; private set

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    suspend fun run() {
        if (running) return
        running = true; phase = Phase.RUNNING
        stateResult = ""
        val g = AuthenticAccessibilityService.gesture
        val nodes = AuthenticAccessibilityService.nodes

        ALog.i("[023] ========== 阶段1验证开始（取色判断，不进大厅）==========")

        // ---- 步骤1：拉起游戏 + 等 45s ----
        val pkg = GameLauncher(context).launch()
        if (pkg == null) {
            ALog.w("[023] 游戏未拉起，中止")
            phase = Phase.DONE; running = false
            return
        }
        ALog.i("[023] 已拉起游戏 $pkg，等待 ${Config.STEP_1_BOOT_MS}ms 让画面加载")
        delay(Config.STEP_1_BOOT_MS)

        // ---- 步骤2：screencap 取最新帧，读 (1400,850) 的 RGB 亮度 ----
        val frame = FrameBus.snapshot()
        if (frame == null) {
            ALog.e("[023] 无实时画面帧，无法取色判断。请先点③授权截图。中止")
            phase = Phase.DONE; running = false
            return
        }
        val pxX = Config.Step1Verify.SAMPLE_X
        val pxY = Config.Step1Verify.SAMPLE_Y
        val inBounds = pxX in 0 until frame.width && pxY in 0 until frame.height
        var brightness = -1
        var isLoginPage = false
        if (inBounds) {
            val pixel = frame.getPixel(pxX, pxY)
            val r = Color.red(pixel); val gg = Color.green(pixel); val b = Color.blue(pixel)
            // 亮度 = RGB 三通道均值；阈值见 Config.Step1Verify.BRIGHT_THRESHOLD 注释（100）
            brightness = (r + gg + b) / 3
            isLoginPage = brightness > Config.Step1Verify.BRIGHT_THRESHOLD
            ALog.i("[023] 取色($pxX,$pxY)=RGB($r,$gg,$b) 亮度=$brightness 阈值=${Config.Step1Verify.BRIGHT_THRESHOLD} -> ${if (isLoginPage) "登录页(偏亮)" else "模式选择页(偏暗)"}")
        } else {
            ALog.w("[023] 取色点越界：帧尺寸 ${frame.width}x${frame.height}，点($pxX,$pxY)。按暗色处理，不点击")
        }
        stateResult = if (isLoginPage) "LOGIN_PAGE" else "MODE_PAGE"
        // 先把判断帧存下来，供核对取色点画面
        FrameDump.save(context, frame, "023_before_pick")
        frame.recycle()

        // ---- 步骤3：状态 A 登录页 -> 点 QQ 登录；状态 B 模式页 -> 跳过，绝不点 ----
        if (isLoginPage) {
            val qx = Config.Step1Verify.QQ_LOGIN_X
            val qy = Config.Step1Verify.QQ_LOGIN_Y
            ALog.i("[023] 状态A=登录页：点 QQ 登录 ($qx,$qy)")
            g?.tap(qx, qy, Config.UI_SETTLE_MS) ?: ALog.w("[023] 手势不可用：QQ登录点击失败")
            ALog.i("[023] 点 QQ 登录后再等 10s，处理可能的 QQ 授权页")
            delay(10_000L)
            handleAuthPage(g, nodes)
        } else {
            ALog.i("[023] 状态B=模式选择页：跳过点击，绝对不点（(1400,850)在模式页会误触其他模式）")
        }

        // ---- 步骤4：处理完立即停止，screencap 截图存证 ----
        val finalFrame = FrameBus.snapshot()
        if (finalFrame != null) {
            FrameDump.save(context, finalFrame, "023_step1_model_page")
            finalFrame.recycle()
            ALog.i("[023] 已保存 023_step1_model_page.png (${finalFrame.width}x${finalFrame.height})")
        } else {
            ALog.w("[023] 终态截图失败：无帧")
        }
        ALog.i("[023] ======== 阶段1验证结束，停在 $stateResult（模式页截图已存）========")

        // ---- 阶段二：点「烽火地带/前往游玩」进大厅 → 等 20s → 截图 024_lobby.png → 暂停 ----
        // 【边界】只点这一个坐标，不关弹窗、不点特勤处、不退出登录。大厅若长什么样原样截图给用户看。
        val fz = Config.BTN_ENTER_FIRE_ZONE
        ALog.i("[024] 点 烽火地带/前往游玩 ((${fz.cx},${fz.cy}))")
        g?.tapRegion(fz, Config.UI_SETTLE_MS) ?: ALog.w("[024] 手势不可用：进大厅点击失败")
        ALog.i("[024] 等待 20000ms 让大厅加载")
        delay(20_000L)
        val lobbyFrame = FrameBus.snapshot()
        if (lobbyFrame != null) {
            FrameDump.save(context, lobbyFrame, "024_lobby")
            ALog.i("[024] 已保存 024_lobby.png (${lobbyFrame.width}x${lobbyFrame.height})")
            lobbyFrame.recycle()
        } else {
            ALog.w("[024] 大厅截图失败：无帧")
        }
        ALog.i("[024] 大厅截图已保存，继续阶段三：进特勤处")

        // ---- 阶段三：点大厅底部「特勤处」→ 等 8s → 截图 025_teqin.png → 暂停 ----
        // 【边界】只点这一个坐标，不点4台子收菜、不点返回、不退出登录。特勤处原样截图给用户看。
        val teq = Config.BTN_TEQIN_CENTER
        ALog.i("[025] 点 特勤处 ((${teq.cx},${teq.cy}))")
        g?.tapRegion(teq, Config.UI_SETTLE_MS) ?: ALog.w("[025] 手势不可用：特勤处点击失败")
        ALog.i("[025] 等待 8000ms 让特勤处加载")
        delay(8_000L)
        val teqinFrame = FrameBus.snapshot()
        if (teqinFrame != null) {
            FrameDump.save(context, teqinFrame, "025_teqin")
            ALog.i("[025] 已保存 025_teqin.png (${teqinFrame.width}x${teqinFrame.height})")
            teqinFrame.recycle()
        } else {
            ALog.w("[025] 特勤处截图失败：无帧")
        }
        ALog.i("[025] 特勤处截图已保存，继续阶段四：返回大厅 + 点开技术中心弹窗")

        // ---- 阶段四：返回大厅 → 再进特勤处 → 点技术中心 → 截图弹窗 → 暂停 ----
        // 【边界】不收菜不种菜、不在弹窗里点任何按钮。只截图原样给用户看。
        // 4-1 点特勤处左上角返回按钮 → 等 3s → 截图 025_back_lobby.png
        val back = Config.BTN_TEQIN_BACK
        ALog.i("[025][阶段四] 点 特勤处返回大厅 ((${back.cx},${back.cy}))")
        g?.tapRegion(back, Config.UI_SETTLE_MS) ?: ALog.w("[025][阶段四] 手势不可用：返回大厅")
        ALog.i("[025][阶段四] 等 3000ms 让大厅加载")
        delay(3_000L)
        val backFrame = FrameBus.snapshot()
        if (backFrame != null) {
            FrameDump.save(context, backFrame, "025_back_lobby")
            ALog.i("[025][阶段四] 已保存 025_back_lobby.png (${backFrame.width}x${backFrame.height})")
            backFrame.recycle()
        } else {
            ALog.w("[025][阶段四] 返回大厅截图失败：无帧")
        }

        // 4-2 重新点大厅「特勤处」→ 等 5s
        ALog.i("[025][阶段四] 重新点 特勤处 ((${teq.cx},${teq.cy}))")
        g?.tapRegion(teq, Config.UI_SETTLE_MS) ?: ALog.w("[025][阶段四] 手势不可用：再次进特勤处")
        ALog.i("[025][阶段四] 等 5000ms 让特勤处加载")
        delay(5_000L)

        // 4-3 只点「技术中心」→ 等 3s → 截图 025_teqin_popup.png
        val tech = Config.STATION_TECH_CENTER
        ALog.i("[025][阶段四] 点 技术中心 ((${tech.cx},${tech.cy}))")
        g?.tapRegion(tech, Config.UI_SETTLE_MS) ?: ALog.w("[025][阶段四] 手势不可用：技术中心")
        ALog.i("[025][阶段四] 等 3000ms 让弹窗加载")
        delay(3_000L)
        val popupFrame = FrameBus.snapshot()
        if (popupFrame != null) {
            FrameDump.save(context, popupFrame, "025_teqin_popup")
            ALog.i("[025][阶段四] 已保存 025_teqin_popup.png (${popupFrame.width}x${popupFrame.height})")
            popupFrame.recycle()
        } else {
            ALog.w("[025][阶段四] 技术中心弹窗截图失败：无帧")
        }
        ALog.i("[025][阶段四] 已点击技术中心，弹窗/奖励页已截图，继续阶段五：关闭奖励页")
        toast("阶段四：技术中心弹窗已截图")

        // ---- 阶段五：点 (1200,540) 关闭「获得奖励」页 → 等 3s → 截图 027_teqin_idle.png → 暂停 ----
        // 【边界】只点画面中心空白处关奖励页，绝不点「空闲种」、不点其他台子、不点奖励页里任何按钮。
        val closeReward = Config.BTN_CLOSE_REWARD_BLANK
        ALog.i("[027][阶段五] 点 关闭奖励页空白 ((${closeReward.cx},${closeReward.cy}))")
        g?.tapRegion(closeReward, Config.UI_SETTLE_MS) ?: ALog.w("[027][阶段五] 手势不可用：关闭奖励页")
        ALog.i("[027][阶段五] 等 3000ms 让特勤处空闲界面加载")
        delay(3_000L)
        val idleFrame = FrameBus.snapshot()
        if (idleFrame != null) {
            FrameDump.save(context, idleFrame, "027_teqin_idle")
            ALog.i("[027][阶段五] 已保存 027_teqin_idle.png (${idleFrame.width}x${idleFrame.height})")
            idleFrame.recycle()
        } else {
            ALog.w("[027][阶段五] 空闲界面截图失败：无帧")
        }
        ALog.i("[027][阶段五] 已关闭奖励页，回到特勤处空闲状态，继续阶段六：选中图纸")
        toast("阶段五：已关闭奖励页截图")

        // ---- 阶段六：点「骨架狙击枪托」图纸 (450,300) → 等 3s → 截图 028_teqin_recipe_selected.png → 暂停 ----
        // 【边界】图纸已由用户手动置顶，位置固定，不写任何图像识别/文本查找。只点这一个图纸项，
        // 绝不点右侧「开始制造」，不写任何确认/提交逻辑。只看选中后右侧面板长什么样。
        val recipe = Config.RECIPE_ARMOR_STOCK
        ALog.i("[028][阶段六] 点 骨架狙击枪托图纸 ((${recipe.cx},${recipe.cy}))")
        g?.tapRegion(recipe, Config.UI_SETTLE_MS) ?: ALog.w("[028][阶段六] 手势不可用：图纸点击失败")
        ALog.i("[028][阶段六] 等 3000ms 让右侧制造详情刷新")
        delay(3_000L)
        val recipeFrame = FrameBus.snapshot()
        if (recipeFrame != null) {
            FrameDump.save(context, recipeFrame, "028_teqin_recipe_selected")
            ALog.i("[028][阶段六] 已保存 028_teqin_recipe_selected.png (${recipeFrame.width}x${recipeFrame.height})")
            recipeFrame.recycle()
        } else {
            ALog.w("[028][阶段六] 图纸选中截图失败：无帧")
        }
        ALog.i("[028][阶段六] 已选中骨架狙击枪托，继续阶段七：一键补齐")
        toast("阶段六：已选中图纸截图")

        // ---- 阶段七：点右下「一键补齐」(1650,1000) → 等 3s → 截图 029_teqin_buy_material.png → 暂停 ----
        // 【边界】只点这一个按钮。绝不点「开始制造」「确定」，绝不点任何可能弹出的「购买确认」按钮。
        // 只看点完补齐后弹出什么界面（购买确认弹窗 or 材料自动补齐刷新），原样截图给用户看。
        val buyMat = Config.BTN_BUY_MATERIAL
        ALog.i("[029][阶段七] 点 一键补齐 ((${buyMat.cx},${buyMat.cy}))")
        g?.tapRegion(buyMat, Config.UI_SETTLE_MS) ?: ALog.w("[029][阶段七] 手势不可用：一键补齐点击失败")
        ALog.i("[029][阶段七] 等 3000ms 让购买确认弹窗出现或材料补齐刷新")
        delay(3_000L)
        val buyFrame = FrameBus.snapshot()
        if (buyFrame != null) {
            FrameDump.save(context, buyFrame, "029_teqin_buy_material")
            ALog.i("[029][阶段七] 已保存 029_teqin_buy_material.png (${buyFrame.width}x${buyFrame.height})")
            buyFrame.recycle()
        } else {
            ALog.w("[029][阶段七] 补齐后截图失败：无帧")
        }
        ALog.i("[029][阶段七] 已点击一键补齐（修正坐标2050,1000），继续阶段八：确认购买")

        // ---- 阶段八：点购买确认框底部绿色按钮 (1200,876) → 等 4s → 截图 031_teqin_material_bought.png → 暂停 ----
        // 【边界】只点这一个确认按钮。绝对禁止写后续「开始制造」逻辑——补齐后按钮位置会变，等用户给新坐标。
        val confirmBuy = Config.BTN_CONFIRM_BUY
        ALog.i("[031][阶段八] 点 购买确认 ((${confirmBuy.cx},${confirmBuy.cy}))")
        g?.tapRegion(confirmBuy, Config.UI_SETTLE_MS) ?: ALog.w("[031][阶段八] 手势不可用：购买确认点击失败")
        ALog.i("[031][阶段八] 等 4000ms 让购买网络延迟及界面刷新")
        delay(4_000L)
        val boughtFrame = FrameBus.snapshot()
        if (boughtFrame != null) {
            FrameDump.save(context, boughtFrame, "031_teqin_material_bought")
            ALog.i("[031][阶段八] 已保存 031_teqin_material_bought.png (${boughtFrame.width}x${boughtFrame.height})")
            boughtFrame.recycle()
        } else {
            ALog.w("[031][阶段八] 购买后截图失败：无帧")
        }
        ALog.i("[031][阶段八] 已确认补齐材料，继续阶段九：点生产")

        // ---- 阶段九：点右下绿色「生产」按钮 (2058,993) → 等 3s → 截图 032_teqin_producing.png → 暂停 ----
        // 【边界】只点这一个按钮。绝对禁止写返回特勤处/返回大厅/退出登录逻辑。
        // 点完生产若弹出任何确认提示框，绝不点它，原样截图给用户看。
        val produce = Config.BTN_TEQIN_PRODUCE
        ALog.i("[032][阶段九] 点 生产 ((${produce.cx},${produce.cy}))")
        g?.tapRegion(produce, Config.UI_SETTLE_MS) ?: ALog.w("[032][阶段九] 手势不可用：生产点击失败")
        ALog.i("[032][阶段九] 等 3000ms 让生产界面刷新")
        delay(3_000L)
        val producingFrame = FrameBus.snapshot()
        if (producingFrame != null) {
            FrameDump.save(context, producingFrame, "032_teqin_producing")
            ALog.i("[032][阶段九] 已保存 032_teqin_producing.png (${producingFrame.width}x${producingFrame.height})")
            producingFrame.recycle()
        } else {
            ALog.w("[032][阶段九] 生产后截图失败：无帧")
        }
        ALog.i("[032][阶段九] 已点击生产，继续阶段十：取消中止框并返回特勤处")

        // ---- 阶段十：点「取消」(960,742) 关中止框 → 点技术中心返回(210,50) → 截图 033_back_to_teqin_main.png → 暂停 ----
        // 【边界】绝绝对禁止点「中止」(1440,742)——会取消生产且材料不退。截图后立即停住，不点其他台子。
        val cancelAbort = Config.BTN_CANCEL_ABORT
        ALog.i("[033][阶段十] 点 取消中止框 ((${cancelAbort.cx},${cancelAbort.cy})) —— 保留生产，绝不点中止(1440,742)")
        g?.tapRegion(cancelAbort, Config.UI_SETTLE_MS) ?: ALog.w("[033][阶段十] 手势不可用：取消中止框失败")
        ALog.i("[033][阶段十] 等 2000ms 让中止框关闭")
        delay(2_000L)
        val detailBack = Config.BTN_TEQIN_DETAIL_BACK
        ALog.i("[033][阶段十] 点 技术中心返回 ((${detailBack.cx},${detailBack.cy}))")
        g?.tapRegion(detailBack, Config.UI_SETTLE_MS) ?: ALog.w("[033][阶段十] 手势不可用：技术中心返回失败")
        ALog.i("[033][阶段十] 等 3000ms 让界面刷新回特勤处")
        delay(3_000L)
        val backTeqinFrame = FrameBus.snapshot()
        if (backTeqinFrame != null) {
            FrameDump.save(context, backTeqinFrame, "033_back_to_teqin_main")
            ALog.i("[033][阶段十] 已保存 033_back_to_teqin_main.png (${backTeqinFrame.width}x${backTeqinFrame.height})")
            backTeqinFrame.recycle()
        } else {
            ALog.w("[033][阶段十] 返回特勤处截图失败：无帧")
        }
        ALog.i("[033][阶段十] 已取消中止并返回特勤处，继续阶段十一：工作台收菜+种菜闭环")

        // ---- 阶段十一：工作台(900,400) 完整"收菜+种菜"闭环，每步 2s ----
        // 用户已把工作台配方手动置顶，点进去逻辑与技术中心一致；若有菜熟会先弹"获得奖励"全屏页。
        // 固定点一次屏幕中心(1200,540)兜底关奖励页（有奖励页则跳过，无奖励页点中心也不误触）。
        // 【防呆】若点补齐后直接补满没弹购买框，则"确认购买"会点空——没关系，时间轴对上即可。
        ALog.i("[034][工作台] === 工作台收菜+种菜闭环开始 ===")
        // 1) 点工作台 → 等2s
        ALog.i("[034][工作台] ① 点工作台 ((${Config.BTN_WORKBENCH.cx},${Config.BTN_WORKBENCH.cy}))")
        g?.tapRegion(Config.BTN_WORKBENCH, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：点工作台")
        delay(2_000L)
        // 2) 固定点屏幕中心关奖励页（兜底）→ 等2s
        ALog.i("[034][工作台] ② 点屏幕中心 ((${Config.BTN_CLOSE_REWARD_BLANK.cx},${Config.BTN_CLOSE_REWARD_BLANK.cy})) 兜底关奖励页")
        g?.tapRegion(Config.BTN_CLOSE_REWARD_BLANK, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：关奖励页")
        delay(2_000L)
        // 3) 点图纸(置顶配方) → 等2s
        ALog.i("[034][工作台] ③ 点图纸 ((${Config.RECIPE_ARMOR_STOCK.cx},${Config.RECIPE_ARMOR_STOCK.cy}))")
        g?.tapRegion(Config.RECIPE_ARMOR_STOCK, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：点图纸")
        delay(2_000L)
        // 4) 点一键补齐 → 等2s
        ALog.i("[034][工作台] ④ 点一键补齐 ((${Config.BTN_BUY_MATERIAL.cx},${Config.BTN_BUY_MATERIAL.cy}))")
        g?.tapRegion(Config.BTN_BUY_MATERIAL, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：一键补齐")
        delay(2_000L)
        // 5) 点确认购买（若没弹框会点空，无妨）→ 等2s
        ALog.i("[034][工作台] ⑤ 点确认购买 ((${Config.BTN_CONFIRM_BUY.cx},${Config.BTN_CONFIRM_BUY.cy}))")
        g?.tapRegion(Config.BTN_CONFIRM_BUY, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：确认购买")
        delay(2_000L)
        // 6) 点生产 → 等2s
        ALog.i("[034][工作台] ⑥ 点生产 ((${Config.BTN_TEQIN_PRODUCE.cx},${Config.BTN_TEQIN_PRODUCE.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_PRODUCE, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：生产")
        delay(2_000L)
        // 7) 点取消中止框 → 等2s
        ALog.i("[034][工作台] ⑦ 点取消中止 ((${Config.BTN_CANCEL_ABORT.cx},${Config.BTN_CANCEL_ABORT.cy})) —— 绝不点中止(1440,742)")
        g?.tapRegion(Config.BTN_CANCEL_ABORT, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：取消中止")
        delay(2_000L)
        // 8) 点详情返回 → 等2s
        ALog.i("[034][工作台] ⑧ 点详情返回 ((${Config.BTN_TEQIN_DETAIL_BACK.cx},${Config.BTN_TEQIN_DETAIL_BACK.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_DETAIL_BACK, Config.UI_SETTLE_MS) ?: ALog.w("[034][工作台] 手势不可用：详情返回")
        delay(2_000L)
        // 9) 截图
        val wbFrame = FrameBus.snapshot()
        if (wbFrame != null) {
            FrameDump.save(context, wbFrame, "034_workbench_done")
            ALog.i("[034][工作台] 已保存 034_workbench_done.png (${wbFrame.width}x${wbFrame.height})")
            wbFrame.recycle()
        } else {
            ALog.w("[034][工作台] 闭环终态截图失败：无帧")
        }
        ALog.i("[034][工作台] === 工作台闭环测试完成，等待截图核对，程序暂停 ===")
        toast("阶段十一：工作台闭环完成，程序暂停")

        // ---- 阶段十二（本轮唯一目标）：制药台(500,600) 完整「收菜 -> 二次点击 -> 种菜」闭环 ----
        // 背景：工作台/防具台已收过菜变「空闲中」，点进去直接进图纸详情，不能再套"点中心关奖励+再点台子"。
        // 只有制药台还有熟菜。每一步等待统一 3 秒，给足界面加载时间。截图后立即停住，绝不碰其他台子。
        // 【防呆】若"一键补齐"时材料本来就够（没弹购买框），"确认购买"会点空——没关系，按时间轴继续走。
        ALog.i("[035][制药台] === 制药台收菜+种菜闭环开始（本轮唯一目标，其余台子不碰）===")

        // 1) 点制药台(500,600) → 等3s（有熟菜会先弹"获得奖励"页）
        ALog.i("[035][制药台] ① 点制药台 ((${Config.BTN_CHEM_STATION.cx},${Config.BTN_CHEM_STATION.cy}))")
        g?.tapRegion(Config.BTN_CHEM_STATION, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：点制药台")
        delay(3_000L)

        // 2) 点屏幕中心(1200,540) 关"获得奖励"页 → 等3s
        ALog.i("[035][制药台] ② 点屏幕中心 ((${Config.BTN_CLOSE_REWARD_BLANK.cx},${Config.BTN_CLOSE_REWARD_BLANK.cy})) 关闭获得奖励页")
        g?.tapRegion(Config.BTN_CLOSE_REWARD_BLANK, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：关奖励页")
        delay(3_000L)

        // 3) 再次点制药台(500,600)（关键：收菜后退回主界面，必须再点一次才进详情）→ 等3s
        ALog.i("[035][制药台] ③ 再次点制药台 ((${Config.BTN_CHEM_STATION.cx},${Config.BTN_CHEM_STATION.cy})) 进详情")
        g?.tapRegion(Config.BTN_CHEM_STATION, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：再点制药台")
        delay(3_000L)

        // 4) 点图纸(450,300) 选中置顶配方 → 等3s
        ALog.i("[035][制药台] ④ 点图纸 ((${Config.RECIPE_ARMOR_STOCK.cx},${Config.RECIPE_ARMOR_STOCK.cy}))")
        g?.tapRegion(Config.RECIPE_ARMOR_STOCK, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：点图纸")
        delay(3_000L)

        // 5) 点一键补齐(2050,1000) → 等3s
        ALog.i("[035][制药台] ⑤ 点一键补齐 ((${Config.BTN_BUY_MATERIAL.cx},${Config.BTN_BUY_MATERIAL.cy}))")
        g?.tapRegion(Config.BTN_BUY_MATERIAL, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：一键补齐")
        delay(3_000L)

        // 6) 点确认购买(1200,876)（没弹框会点空，无妨）→ 等3s
        ALog.i("[035][制药台] ⑥ 点确认购买 ((${Config.BTN_CONFIRM_BUY.cx},${Config.BTN_CONFIRM_BUY.cy}))")
        g?.tapRegion(Config.BTN_CONFIRM_BUY, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：确认购买")
        delay(3_000L)

        // 7) 点生产(2058,993) → 等3s
        ALog.i("[035][制药台] ⑦ 点生产 ((${Config.BTN_TEQIN_PRODUCE.cx},${Config.BTN_TEQIN_PRODUCE.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_PRODUCE, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：生产")
        delay(3_000L)

        // 8) 点取消中止(960,742)（绝不点中止1440,742）→ 等3s
        ALog.i("[035][制药台] ⑧ 点取消中止 ((${Config.BTN_CANCEL_ABORT.cx},${Config.BTN_CANCEL_ABORT.cy})) —— 绝不点中止(1440,742)")
        g?.tapRegion(Config.BTN_CANCEL_ABORT, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：取消中止")
        delay(3_000L)

        // 9) 点详情返回(210,50) → 等3s
        ALog.i("[035][制药台] ⑨ 点详情返回 ((${Config.BTN_TEQIN_DETAIL_BACK.cx},${Config.BTN_TEQIN_DETAIL_BACK.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_DETAIL_BACK, Config.UI_SETTLE_MS) ?: ALog.w("[035][制药台] 手势不可用：详情返回")
        delay(3_000L)

        // 10) 截图 035_chem_retry.png
        val chemFrame = FrameBus.snapshot()
        if (chemFrame != null) {
            FrameDump.save(context, chemFrame, "035_chem_retry")
            ALog.i("[035][制药台] 已保存 035_chem_retry.png (${chemFrame.width}x${chemFrame.height})")
            chemFrame.recycle()
        } else {
            ALog.w("[035][制药台] 闭环终态截图失败：无帧")
        }
        ALog.i("[035][制药台] 制药台收菜种菜闭环完成，程序暂停")
        toast("阶段十二：制药台收菜种菜闭环完成，程序暂停")

        // ============================================================================
        // 阶段十三（app-debug036）：跳过状态兼容，空闲台子直接当技术中心走空闲种菜流程
        // 背景：用户确认制药台(500,800)、防具台(900,800)都是空闲无熟菜，点进去直接是图纸页面。
        //       不需要处理"获得奖励"页，不点中心、不二次点击台子。两段都照搬技术中心7步流程。
        // 【防呆】若"一键补齐"时材料本来就够（没弹购买框），"确认购买"会点空——按时间轴继续走即可。
        // 绝不点"中止"(1440,742)。截图后立即停住。
        // ============================================================================

        // ---------- 第一段：制药台(500,800) 空闲种菜 ----------
        ALog.i("[036][制药台] === 制药台空闲种菜开始（跳过奖励页，直接进详情）===")
        // 1) 点制药台(500,800) → 等3s
        ALog.i("[036][制药台] ① 点制药台 ((${Config.BTN_CHEM_STATION_IDLE.cx},${Config.BTN_CHEM_STATION_IDLE.cy}))")
        g?.tapRegion(Config.BTN_CHEM_STATION_IDLE, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：点制药台")
        delay(3_000L)
        // 2) 点置顶图纸(450,300) → 等2s
        ALog.i("[036][制药台] ② 点置顶图纸 ((${Config.RECIPE_ARMOR_STOCK.cx},${Config.RECIPE_ARMOR_STOCK.cy}))")
        g?.tapRegion(Config.RECIPE_ARMOR_STOCK, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：点图纸")
        delay(2_000L)
        // 3) 点一键补齐(2050,1000) → 等2s
        ALog.i("[036][制药台] ③ 点一键补齐 ((${Config.BTN_BUY_MATERIAL.cx},${Config.BTN_BUY_MATERIAL.cy}))")
        g?.tapRegion(Config.BTN_BUY_MATERIAL, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：一键补齐")
        delay(2_000L)
        // 4) 点确认购买(1200,876)（没弹框会点空，无妨）→ 等2s
        ALog.i("[036][制药台] ④ 点确认购买 ((${Config.BTN_CONFIRM_BUY.cx},${Config.BTN_CONFIRM_BUY.cy}))")
        g?.tapRegion(Config.BTN_CONFIRM_BUY, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：确认购买")
        delay(2_000L)
        // 5) 点生产(2058,993) → 等2s
        ALog.i("[036][制药台] ⑤ 点生产 ((${Config.BTN_TEQIN_PRODUCE.cx},${Config.BTN_TEQIN_PRODUCE.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_PRODUCE, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：生产")
        delay(2_000L)
        // 6) 点取消中止(960,742)（绝不点中止）→ 等2s
        ALog.i("[036][制药台] ⑥ 点取消中止 ((${Config.BTN_CANCEL_ABORT.cx},${Config.BTN_CANCEL_ABORT.cy})) —— 绝不点中止(1440,742)")
        g?.tapRegion(Config.BTN_CANCEL_ABORT, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：取消中止")
        delay(2_000L)
        // 7) 点详情返回(210,50) → 等2s
        ALog.i("[036][制药台] ⑦ 点详情返回 ((${Config.BTN_TEQIN_DETAIL_BACK.cx},${Config.BTN_TEQIN_DETAIL_BACK.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_DETAIL_BACK, Config.UI_SETTLE_MS) ?: ALog.w("[036][制药台] 手势不可用：详情返回")
        delay(2_000L)
        ALog.i("[036][制药台] === 制药台空闲种菜7步执行完，进入防具台 ===")

        // ---------- 第二段：防具台(900,800) 空闲种菜 ----------
        ALog.i("[036][防具台] === 防具台空闲种菜开始（跳过奖励页，直接进详情）===")
        // 1) 点防具台(900,800) → 等3s
        ALog.i("[036][防具台] ① 点防具台 ((${Config.BTN_ARMOR_STATION_IDLE.cx},${Config.BTN_ARMOR_STATION_IDLE.cy}))")
        g?.tapRegion(Config.BTN_ARMOR_STATION_IDLE, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：点防具台")
        delay(3_000L)
        // 2) 点置顶图纸(450,300) → 等2s
        ALog.i("[036][防具台] ② 点置顶图纸 ((${Config.RECIPE_ARMOR_STOCK.cx},${Config.RECIPE_ARMOR_STOCK.cy}))")
        g?.tapRegion(Config.RECIPE_ARMOR_STOCK, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：点图纸")
        delay(2_000L)
        // 3) 点一键补齐(2050,1000) → 等2s
        ALog.i("[036][防具台] ③ 点一键补齐 ((${Config.BTN_BUY_MATERIAL.cx},${Config.BTN_BUY_MATERIAL.cy}))")
        g?.tapRegion(Config.BTN_BUY_MATERIAL, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：一键补齐")
        delay(2_000L)
        // 4) 点确认购买(1200,876)（没弹框会点空，无妨）→ 等2s
        ALog.i("[036][防具台] ④ 点确认购买 ((${Config.BTN_CONFIRM_BUY.cx},${Config.BTN_CONFIRM_BUY.cy}))")
        g?.tapRegion(Config.BTN_CONFIRM_BUY, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：确认购买")
        delay(2_000L)
        // 5) 点生产(2058,993) → 等2s
        ALog.i("[036][防具台] ⑤ 点生产 ((${Config.BTN_TEQIN_PRODUCE.cx},${Config.BTN_TEQIN_PRODUCE.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_PRODUCE, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：生产")
        delay(2_000L)
        // 6) 点取消中止(960,742)（绝不点中止）→ 等2s
        ALog.i("[036][防具台] ⑥ 点取消中止 ((${Config.BTN_CANCEL_ABORT.cx},${Config.BTN_CANCEL_ABORT.cy})) —— 绝不点中止(1440,742)")
        g?.tapRegion(Config.BTN_CANCEL_ABORT, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：取消中止")
        delay(2_000L)
        // 7) 点详情返回(210,50) → 等2s
        ALog.i("[036][防具台] ⑦ 点详情返回 ((${Config.BTN_TEQIN_DETAIL_BACK.cx},${Config.BTN_TEQIN_DETAIL_BACK.cy}))")
        g?.tapRegion(Config.BTN_TEQIN_DETAIL_BACK, Config.UI_SETTLE_MS) ?: ALog.w("[036][防具台] 手势不可用：详情返回")
        delay(2_000L)
        ALog.i("[036][防具台] === 防具台空闲种菜7步执行完，截图终态 ===")

        // 终态截图 037_all_done.png
        val allDoneFrame = FrameBus.snapshot()
        if (allDoneFrame != null) {
            FrameDump.save(context, allDoneFrame, "037_all_done")
            ALog.i("[036] 已保存 037_all_done.png (${allDoneFrame.width}x${allDoneFrame.height})")
            allDoneFrame.recycle()
        } else {
            ALog.w("[036] 终态截图失败：无帧")
        }
        ALog.i("[036] 制药台+防具台空闲种菜全部执行完，程序暂停")
        toast("阶段十三：036 两台子空闲种菜完成，程序暂停")
        phase = Phase.DONE; running = false
    }

    /**
     * 【039 换号验证】先退出当前号 → 回登录页 → 点QQ登录 → 授权页切到目标QQ号 → 同意①②
     * → 进大厅 → 4台子空闲种菜 → 截图 039_switch_all_done.png。
     * 起点假设：游戏已在「特勤处4台子主界面」（037 终态）。
     * 退出登录坐标复用 Config.Login 里已有的设置齿轮/退出/确认（均为估算值，真机实测校准）。
     */
    suspend fun runSwitchAccount(targetNote: String) {
        if (running) return
        running = true; phase = Phase.RUNNING
        ALog.i("[039] ========== 换号验证开始，目标号=$targetNote ==========")
        // 【防呆恢复时序修复】授权恢复后无障碍服务可能晚一拍重连，等待就绪再取手势，避免全程 null
        var waited = 0L
        while (!AuthenticAccessibilityService.isReady() && waited < 6_000L) {
            delay(300L); waited += 300L
        }
        ALog.i("[039] 无障碍就绪=${AuthenticAccessibilityService.isReady()} 等待${waited}ms")
        val g = AuthenticAccessibilityService.gesture
        val nodes = AuthenticAccessibilityService.nodes
        if (g == null) {
            ALog.w("[039] 手势仍不可用，中止换号")
            phase = Phase.DONE; running = false; return
        }
        // 【前台修复】点触发按钮时 App 在前台，手势会点在 App 上。先把游戏带回前台再开始点击。
        val launchedPkg = GameLauncher(context).launch()
        ALog.i("[039] 已把游戏带回前台 pkg=$launchedPkg，等待界面稳定")
        delay(3_000L)

        // ===== 阶段A：退出当前号 =====
        // A1 特勤处返回大厅
        val back = Config.BTN_TEQIN_BACK
        ALog.i("[039][退出] A1 特勤处返回大厅 ((${back.cx},${back.cy}))")
        g?.tapRegion(back, Config.UI_SETTLE_MS) ?: ALog.w("[039][退出] 手势不可用：返回大厅")
        delay(5_000L)
        // A4 游戏大厅右上角「设置」齿轮 (2250,50) —— 用户口述：返回大厅后直接点设置，不经行前备战页
        val set = Config.Login.BTN_SETTINGS_GEAR_LOBBY
        ALog.i("[039][退出] A4 大厅右上角设置 ((${set.cx},${set.cy}))")
        g?.tapRegion(set, Config.UI_SETTLE_MS) ?: ALog.w("[039][退出] 手势不可用：设置齿轮")
        delay(3_000L)
        saveFrameSafe("039_a4_settings", "设置页")
        // A5 点退出登录
        val lo = Config.Login.BTN_LOGOUT_SETTINGS_PAGE
        ALog.i("[039][退出] A5 点退出登录 ((${lo.cx},${lo.cy}))")
        g?.tapRegion(lo, Config.UI_SETTLE_MS) ?: ALog.w("[039][退出] 手势不可用：退出登录")
        delay(3_000L)
        saveFrameSafe("039_a5_logout_confirm", "退出确认框")
        // A6 点确认 → 回登录页
        val cf = Config.Login.BTN_LOGOUT_CONFIRM
        ALog.i("[039][退出] A6 点确认 ((${cf.cx},${cf.cy}))")
        g?.tapRegion(cf, Config.UI_SETTLE_MS) ?: ALog.w("[039][退出] 手势不可用：确认")
        delay(8_000L)
        saveFrameSafe("039_a6_login_page", "退出后登录页")

        // ===== 阶段B：切到目标号登录 =====
        // B1 勾协议
        val cb = Config.Login.BTN_AGREE_CHECKBOX
        ALog.i("[039][切号] B1 勾协议 ((${cb.cx},${cb.cy}))")
        g?.tapRegion(cb, Config.UI_SETTLE_MS) ?: ALog.w("[039][切号] 手势不可用：勾协议")
        delay(2_000L)
        // B2 点 QQ 登录
        val qq = Config.Login.BTN_QQ_LOGIN
        ALog.i("[039][切号] B2 点QQ登录 ((${qq.cx},${qq.cy}))")
        g?.tapRegion(qq, Config.UI_SETTLE_MS) ?: ALog.w("[039][切号] 手势不可用：QQ登录")
        delay(6_000L)
        // B3 等授权页就绪（最多15s）
        val authReady = waitAuthReady(nodes, 15_000L)
        ALog.i("[039][切号] B3 授权页就绪=$authReady")
        if (!authReady) {
            ALog.w("[039][切号] 授权页未出现，中止换号")
            saveFrameSafe("039_abort_no_auth", "无授权页")
            phase = Phase.DONE; running = false; return
        }
        // B4 点「切换账号」：节点优先，坐标兜底
        val sw = nodes?.clickAnyText("切换账号")
        if (sw == null) {
            val swp = Config.Login.BTN_SWITCH_QQ_ACCOUNT
            ALog.i("[039][切号] B4 坐标兜底点切换账号 ((${swp.cx},${swp.cy}))")
            g?.tapPhysical(swp.cx, swp.cy) ?: ALog.w("[039][切号] 手势不可用：切换账号")
        } else ALog.i("[039][切号] B4 节点点「$sw」✓")
        delay(3_000L)
        // B5 按 QQ 号节点选目标行
        val picked = nodes?.clickAccountRow(targetNote) ?: false
        ALog.i("[039][切号] B5 clickAccountRow($targetNote)=$picked")
        saveFrameSafe("039_b5_picked", "选目标号后")
        if (!picked) {
            ALog.w("[039][切号] 未找到目标号 $targetNote 节点，中止换号")
            phase = Phase.DONE; running = false; return
        }
        delay(2_000L)
        // 密码页检测
        if (nodes?.isPasswordPage() == true) {
            ALog.w("[039][切号] 目标号要求输入密码（登录态过期），中止。请手动登录后重试")
            phase = Phase.DONE; running = false; return
        }
        // B6 同意①
        val a1 = Config.Login.BTN_AGREE_QQ_STEP1
        ALog.i("[039][切号] B6 同意① ((${a1.cx},${a1.cy}))")
        g?.tapPhysical(a1.cx, a1.cy) ?: ALog.w("[039][切号] 手势不可用：同意①")
        delay(6_000L)
        // B7 同意② → 等15s进大厅
        val a2 = Config.Login.BTN_AGREE_QQ_STEP2
        ALog.i("[039][切号] B7 同意② ((${a2.cx},${a2.cy}))")
        g?.tapPhysical(a2.cx, a2.cy) ?: ALog.w("[039][切号] 手势不可用：同意②")
        delay(15_000L)
        saveFrameSafe("039_b7_lobby", "切号后进大厅")
        ALog.i("[039][切号] 目标号 $targetNote 登录完成，开始收菜种菜")

        // ===== 阶段C：4台子空闲种菜（复用036模式：跳过奖励页，直接点台子→图纸→补齐→确认→生产→取消→返回）=====
        // C0 点前往游玩进大厅 → 点特勤处
        val fz = Config.BTN_ENTER_FIRE_ZONE
        g?.tapRegion(fz, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] 手势不可用：前往游玩")
        delay(15_000L)
        val teq = Config.BTN_TEQIN_CENTER
        g?.tapRegion(teq, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] 手势不可用：特勤处")
        delay(8_000L)
        val stations = listOf(
            "工作台" to Config.BTN_WORKBENCH,
            "技术中心" to Config.STATION_TECH_CENTER,
            "制药台" to Config.BTN_CHEM_STATION_IDLE,
            "防具台" to Config.BTN_ARMOR_STATION_IDLE
        )
        for ((name, region) in stations) {
            ALog.i("[039][种菜] === $name 开始7步 ===")
            g?.tapRegion(region, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 点台子失败"); delay(3_000L)
            g?.tapRegion(Config.RECIPE_ARMOR_STOCK, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 图纸失败"); delay(2_000L)
            g?.tapRegion(Config.BTN_BUY_MATERIAL, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 补齐失败"); delay(2_000L)
            g?.tapRegion(Config.BTN_CONFIRM_BUY, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 确认失败"); delay(2_000L)
            g?.tapRegion(Config.BTN_TEQIN_PRODUCE, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 生产失败"); delay(2_000L)
            ALog.i("[039][种菜] $name 点取消中止 —— 绝不点中止(1440,742)")
            g?.tapRegion(Config.BTN_CANCEL_ABORT, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 取消失败"); delay(2_000L)
            g?.tapRegion(Config.BTN_TEQIN_DETAIL_BACK, Config.UI_SETTLE_MS) ?: ALog.w("[039][种菜] $name 返回失败"); delay(2_000L)
            ALog.i("[039][种菜] === $name 7步完成 ===")
        }
        saveFrameSafe("039_switch_all_done", "换号+收菜种菜终态")
        ALog.i("[039] ========== 换号 $targetNote 全流程完成，程序暂停 ==========")
        toast("039 换号 $targetNote 完成，程序暂停")
        phase = Phase.DONE; running = false
    }

    /** 轮询 UI 树等授权页「切换账号/同意」出现，超时返回 false */
    private suspend fun waitAuthReady(nodes: com.ayu.authentic.gesture.NodeFinder?, timeoutMs: Long): Boolean {
        if (nodes == null) { delay(timeoutMs); return false }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (nodes.hasAnyText("切换账号", "同意")) return true
            delay(1_000L)
        }
        return false
    }

    /** 取帧存图，失败只记日志不影响流程 */
    private fun saveFrameSafe(name: String, desc: String) {
        val f = FrameBus.snapshot()
        if (f != null) {
            FrameDump.save(context, f, name)
            ALog.i("[039][截图] 已保存 $name.png ($desc, ${f.width}x${f.height})")
            f.recycle()
        } else ALog.w("[039][截图] $name 取帧失败：无帧")
    }

    /**
     * QQ 授权页处理（017 授权逻辑，本版不切号）：
     * 轮询 UI 树最多 15s 等「同意/切换账号」出现；出现「同意」则点两次同意（竖屏物理坐标）。
     * 若进来就是密码页，用取消/返回兜底退出，不做任何切号。
     */
    private suspend fun handleAuthPage(
        g: com.ayu.authentic.gesture.GestureDispatcher?,
        nodes: com.ayu.authentic.gesture.NodeFinder?
    ) {
        if (nodes == null) {
            ALog.w("[023][授权] 无障碍节点不可用，跳过授权页处理")
            return
        }
        // 等授权页就绪（最多 15s）
        val deadline = System.currentTimeMillis() + 15_000L
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (nodes.hasAnyText("同意", "切换账号")) { ready = true; break }
            delay(1_000L)
        }
        if (!ready) {
            ALog.w("[023][授权] 未等到授权页（文本=${nodes.allTexts().take(12)}），可能已直接进游戏，跳过同意")
            return
        }
        ALog.i("[023][授权] 授权页已就绪")
        if (nodes.isPasswordPage()) {
            ALog.w("[023][授权] 检测到密码页（登录态过期），取消/返回兜底退出，不切号")
            val cancel = nodes.clickAnyText("取消", "返回")
            if (cancel == null) g?.back(600) else delay(600)
            g?.back(600)
            return
        }
        // 不切号，直接点「同意」①②（017 实测竖屏物理坐标）
        val a1 = Config.Login.BTN_AGREE_QQ_STEP1
        ALog.i("[023][授权] 物理直投 同意① ((${a1.cx},${a1.cy}))")
        g?.tapPhysical(a1.cx, a1.cy) ?: ALog.w("[023][授权] 手势不可用：同意①")
        delay(Config.STEP_4_WAIT_MS)

        val a2 = Config.Login.BTN_AGREE_QQ_STEP2
        ALog.i("[023][授权] 物理直投 同意② ((${a2.cx},${a2.cy}))")
        g?.tapPhysical(a2.cx, a2.cy) ?: ALog.w("[023][授权] 手势不可用：同意②")
        // 【023 修复】同意②后游戏要跨过"授权完成->资源加载->模式选择页"，实测 5s 只截到 28% 加载画面。
        // 改为等 15s（Config.STEP_LOBBY_WAIT_MS）给足加载时间，确保终态截图稳定停在模式选择页。
        ALog.i("[023][授权] 同意②后等待 ${Config.STEP_LOBBY_WAIT_MS}ms 让游戏加载到模式选择页")
        delay(Config.STEP_LOBBY_WAIT_MS)
        ALog.i("[023][授权] 授权页处理完毕（未切号）")
    }

    /**
     * 【完整流水线·收菜段】从大厅/行前备战页起跑：进特勤处 → 4台子收种 → 返回大厅。
     * 由 AccountPollMachine.runFullPipeline 在"登录进大厅后、退出前"回调。
     * @return true=收菜段正常走完（继续退出段）；false=校验失败/中止（保留现场，不继续退出）
     */
    suspend fun harvestSegment(skipBreakpoint: Boolean = false, accountNote: String = ""): Boolean {
        val g = AuthenticAccessibilityService.gesture
        if (g == null) { ALog.w("[收菜段] 手势不可用，中止"); return false }
        ALog.i("[收菜段] ========== 收菜段开始（账号=$accountNote，进特勤处→4台子→返回大厅）==========")
        toast("收菜段开始")

        // --- 0/1.【状态驱动闭环】判断当前页面 → 有弹窗点X → 找特勤处入口 → 进特勤处 ---
        //  不再写死坐标盲点：每轮取帧按状态决策，详见 navigateToTeqinByState。
        ALog.i("[收菜段] ========== 状态驱动导航开始（判断页面→找特勤处）==========")
        toast("状态导航：判断当前页面")
        if (!navigateToTeqinByState(g)) {
            toast("收菜段中止：未能进入特勤处")
            return false
        }
        val enterFrame = FrameBus.snapshot()
        if (enterFrame != null) {
            FrameDump.save(context, enterFrame, "pipe_enter_teqin")
            enterFrame.recycle()
        }
        ALog.i("[收菜段] ✅ 已进入特勤处4台子界面（状态驱动校验通过）")

        // --- 2. 4台子【052主界面读卡片三态】收菜+种菜 ---
        // 用户2026-11-20 17:28拍板：全程在特勤处主界面看卡片判态，生产中绝不点进去。
        // 台子坐标写死: 技术中心(400,400) 工作台(900,400) 制药台(500,800) 防具台(900,800)
        val stations = listOf(
            StationDef("技术中心", 400, 400, "tech"),
            StationDef("工作台",   900, 400, "workbench"),
            StationDef("制药台",   500, 800, "chem"),
            StationDef("防具台",   900, 800, "armor")
        )
        val boardUpdates = mutableMapOf<String, Long>()
        for (st in stations) {
            ALog.i("[收菜段] === ${st.name} 主界面读卡片开始 ===")

            // 【先取主界面帧判态，不点进去】
            val cardFrame = FrameBus.snapshot()
            if (cardFrame == null) { ALog.w("[收菜段] ${st.name} 无主界面帧，跳过"); continue }
            val cardStatus = StationStatusClassifier.classifyFromCard(cardFrame, st.name)
            cardFrame.recycle()
            ALog.i("[收菜段] ${st.name} 主界面卡片态=$cardStatus")

            when (cardStatus) {
                StationStatusClassifier.CardStatus.RIPE -> {
                    // 熟了：点台子 → 弹出奖励页 → 点(1800,400)收菜 → 再点台子进详情 → 种菜
                    ALog.i("[收菜段] ${st.name} 熟了→收菜(1800,400)→再进详情→种菜")
                    g.tapPhysical(st.x, st.y, 2_000L)             // 点台子 → 等2s
                    val rf = FrameBus.snapshot()
                    if (rf != null) { FrameDump.save(context, rf, "pipe_${st.name}_reward"); rf.recycle() }
                    g.tapPhysical(1800, 400, 2_000L)             // 收菜 → 等2s
                    g.tapPhysical(st.x, st.y, 2_000L)             // 再点台子进详情 → 等2s
                    plantSteps052(g, st.name)
                    readCountdownToBoard(g, st, boardUpdates)      // 种完读真实倒计时写看板
                }
                StationStatusClassifier.CardStatus.IDLE -> {
                    // 空闲：点台子进详情 → 直接种菜
                    ALog.i("[收菜段] ${st.name} 空闲→进详情直接种菜")
                    g.tapPhysical(st.x, st.y, 2_000L)             // 点台子 → 等2s
                    plantSteps052(g, st.name)
                    readCountdownToBoard(g, st, boardUpdates)      // 种完读真实倒计时写看板
                }
                StationStatusClassifier.CardStatus.PRODUCING -> {
                    // 生产中：只读倒计时写看板，绝不点进去
                    ALog.i("[收菜段] ${st.name} 生产中→只读时间，绝不点进去")
                    readCountdownToBoard(g, st, boardUpdates)
                }
            }

            val f = FrameBus.snapshot()
            if (f != null) { FrameDump.save(context, f, "pipe_${st.name}_done"); f.recycle() }
            ALog.i("[收菜段] === ${st.name} 处理完毕，boardKey=${st.boardKey} ===")
        }

        // 批量写回看板（统一写，避免中间态刷新混乱）
        if (boardUpdates.isNotEmpty()) {
            // 【065】写当前正在跑的账号自己的看板文件（多账号不再写死账号0）
            val note = accountNote.ifBlank { AccountRepository(context).all().getOrNull(0)?.note ?: "" }
            StationBoard.writeBatch(context, boardUpdates, note)
            ALog.i("[收菜段] 看板写入账号=$note")
            // 写回后重排定时（自适应退避会根据新的 finishAtMs 计算间隔）
            NavScheduler.reschedule(context)
            ALog.i("[收菜段] 看板已更新+定时已重排")
        }

        // --- 【断点调试】收完菜后挂起，不返回大厅、不退出、不切前台（供 AI 单步趟通种菜）---
        // 定时触发的流水线 skipBreakpoint=true，跳过挂起直接走退出段（无人值守）
        if (!skipBreakpoint && DebugBreakpoint.isEnabled(context)) {
            ALog.i("[断点] ⏸ 已停在特勤处（4台子收完），挂起等待单步指令。不返回大厅/不退出/不切前台")
            toast("断点：已停在特勤处，等待单步")
            val bpFrame = FrameBus.snapshot()
            if (bpFrame != null) {
                FrameDump.save(context, bpFrame, "breakpoint_at_teqin")
                bpFrame.recycle()
            }
            DebugBreakpoint.awaitResume(context)
            ALog.i("[断点] ▶ 已放行，继续返回大厅")
        }

        // --- 3. 从特勤处返回大厅（交给后续退出段）---
        val back = Config.BTN_TEQIN_BACK
        ALog.i("[收菜段] 特勤处返回大厅 ((${back.cx},${back.cy}))")
        g.tapRegion(back, Config.UI_SETTLE_MS)
        delay(5_000L)
        val finalFrame = FrameBus.snapshot()
        if (finalFrame != null) {
            FrameDump.save(context, finalFrame, "pipe_harvest_all_done")
            ALog.i("[收菜段] 已保存 pipe_harvest_all_done.png")
            finalFrame.recycle()
        }
        ALog.i("[收菜段] ========== 收菜段完成，返回大厅，交给退出段 ==========")
        toast("收菜段完成，准备退出登录")
        return true
    }

    /** 台子定义：名称 + 卡片点击坐标 + 看板key（用户17:28拍板写死） */
    private data class StationDef(val name: String, val x: Int, val y: Int, val boardKey: String)

    /**
     * 【054】读单台真实倒计时并转成完成时刻写看板。
     * 调用前提：当前在特勤处主界面、该台卡片可见（种完返回后/生产中未点进去）。
     * 取新鲜帧 → CountdownReader 读 HH:MM:SS → now+秒数 写 boardUpdates。
     * 读失败(-1)时兜底写占位1h，保证定时不会因缺数据卡死。
     */
    private fun readCountdownToBoard(
        g: com.ayu.authentic.gesture.GestureDispatcher,
        st: StationDef,
        boardUpdates: MutableMap<String, Long>
    ) {
        val frame = FrameBus.snapshot()
        if (frame == null) {
            ALog.w("[收菜段] ${st.name} 读倒计时无帧，兜底占位1h")
            boardUpdates[st.boardKey] = System.currentTimeMillis() + 3_600_000L
            return
        }
        val sec = CountdownReader.readSeconds(frame, context, st.boardKey)
        frame.recycle()
        val now = System.currentTimeMillis()
        if (sec > 0) {
            boardUpdates[st.boardKey] = now + sec * 1000L
            ALog.i("[收菜段] ${st.name} 真实倒计时=${sec}s，预计完熟=${StationBoard.fmt(now + sec * 1000L)}")
        } else {
            ALog.w("[收菜段] ${st.name} 倒计时识别失败，兜底占位1h")
            boardUpdates[st.boardKey] = now + 3_600_000L
        }
    }

    /**
     * 【052种菜5步】用户2026-11-20 17:28拍板，全部用 tapPhysical 写死坐标。
     * 调用前提：已在该台子的详情页（点台子进详情后的态）。
     * 步骤：图纸(350,300) → 一键补齐(2059,1000) → 数字框确认(1200,880) → 生产(2059,1000) → 返回特勤处(250,50)
     * 注意：一键补齐和生产是同一坐标(2059,1000)——补齐材料后该按钮就地变成"生产"。
     * 不点"取消中止"——用户拍板流程无此步。
     */
    private suspend fun plantSteps052(
        g: com.ayu.authentic.gesture.GestureDispatcher,
        name: String
    ) {
        ALog.i("[种菜] $name ①图纸(350,300)→等2s")
        g.tapPhysical(350, 300, 2_000L)
        ALog.i("[种菜] $name ②一键补齐(2059,1000)→等2s")
        g.tapPhysical(2059, 1000, 2_000L)
        ALog.i("[种菜] $name ③数字框确认购买(1200,880)→等2s")
        g.tapPhysical(1200, 880, 2_000L)
        ALog.i("[种菜] $name ④生产(2059,1000)→等2s")
        g.tapPhysical(2059, 1000, 2_000L)
        ALog.i("[种菜] $name ⑤返回特勤处(250,50)→等2s")
        g.tapPhysical(250, 50, 2_000L)
        ALog.i("[种菜] $name 种菜5步完成，已退回特勤处主界面")
    }

    /**
     * 【状态驱动·导航闭环】不再按写死坐标盲点，每轮取帧判断当前页面再决策。
     *
     * 每轮状态优先级（2026-11-20 按行业做法重排：强特征优先 + 关弹窗次数兜底）：
     *  ① 已在特勤处4台子界面（teqin_upgrade 绿框 ≥0.93）→ 直接成功返回
     *  ② 【强特征优先】在大厅看见底部"特勤处"菜单字（teqin_entry ≥0.62，真机干净大厅 sim=0.945）
     *     → 点特勤处(x≈950,y≈1000) → 下一轮重判。放在弹窗判断之前，常驻图标页必走此路，
     *     绝不会被"顶部常驻X"误判饿死（原 bug：②弹窗优先+continue 把该强信号饿死→10轮死循环）。
     *  ③ 【弹窗兜底】看不到特勤处菜单字才怀疑被遮挡 → 找 close_btn X 点它，但整段导航最多关
     *     [maxPopupCloses] 次（行业 maxPerRun 兜底，杜绝死循环）；点后用画面签名差验证是否真关掉。
     *  ④ 都不命中（疑似卡在社交/角色信息/战略板等子菜单）→ 按一次返回 → 下一轮重判
     * 超过 [maxRounds] 轮仍未进特勤处 → 失败返回 false（保留现场）。
     */
    private suspend fun navigateToTeqinByState(g: com.ayu.authentic.gesture.GestureDispatcher): Boolean {
        TemplateStore.init(context)
        val xRoi = Config.Region(2080, 10, 2270, 130)              // 顶部栏弹窗 X
        val menuRoi = Config.Region(100, 950, 1250, 1050)          // 底部菜单栏整条（特勤处在 x≈950，绝不含右下 x≈2100 行前备战）
        val upgradeRoi = Config.Region(40, 60, 300, 230)           // 特勤处左侧"升级制造"绿框按钮（主判据区）
        val maxRounds = 12
        val maxPopupCloses = 4      // 行业 maxPerRun 兜底：常驻图标长得像 X，最多关4次，防死循环
        var popupCloses = 0
        // 【065】点了特勤处菜单字却没进去的次数：新账号登录后常有广告弹窗/加载页盖在大厅上，
        //  teqin_entry 在底部若隐若现仍能 sim0.67 命中 → 每轮都点特勤处坐标(点在弹窗上无效) → 死循环。
        //  连续2次点特勤处无进展就先关弹窗/按返回清遮挡，再重新点。
        var entryTappedStuck = 0

        for (round in 1..maxRounds) {
            // 等新鲜帧（页面切换瞬间可能拿到旧帧）
            var waited = 0
            while (!FrameBus.isFresh() && waited < 3000) { delay(400L); waited += 400 }
            val frame = FrameBus.snapshot()
            if (frame == null) {
                ALog.w("[状态导航] 第$round/$maxRounds 轮无帧，等1s重试")
                delay(1_000L); continue
            }

            // ① 已在特勤处？【主判据】找左侧"升级制造"绿框按钮——真特勤处稳定0.98+,其它页(战略板/大厅/弹窗)≤0.88。
            //    (teqin_roi 区域相似度在战略板高达0.89会误判,已弃用为判定,仅打日志参考)
            val upgrade = TemplateStore.findTemplate("teqin_upgrade", frame, upgradeRoi, 0.93f)
            val teqinSim = TemplateStore.regionSimilarity("teqin_roi", frame, Config.Teqin.TEQIN_CHECK_ROI)
            if (upgrade != null) {
                ALog.i("[状态导航] 第$round 轮：已在特勤处4台子界面 升级制造sim=${upgrade.score}≥0.93(roi参考=${teqinSim})，导航成功")
                frame.recycle()
                return true
            }
            ALog.i("[状态导航] 第$round 轮：非特勤处(升级制造sim<0.93, roi参考=%.3f)，继续判断".format(teqinSim))

            // ②【强特征优先】在大厅？【只认底部菜单栏"特勤处"三个字，绝不理右下角"行前备战"绿标】
            //  真机数据：干净大厅 teqin_entry sim=0.945(位置959,1000)，被遮挡/其它页仅 0.53~0.61。
            //  放在弹窗判断之前：常驻图标页必命中此路直接点特勤处，不会被弹窗X误判饿死。
            //  用户2026-11-20明确要求：右下行前备战(x≈2100)会把游戏带进战略板，必须只点底部特勤处(x≈950,y≈1000)。
            val entry = TemplateStore.findTemplate("teqin_entry", frame, menuRoi, 0.62f)
            // 【065】若连续2次点特勤处都没进去，说明菜单字是被弹窗/加载页遮挡的"假命中"，
            //  本轮跳过点特勤处，强制走③关弹窗/④按返回清掉遮挡。
            if (entry != null && entryTappedStuck < 2) {
                // 命中坐标只能是底部菜单栏内的特勤处：加安全护栏，x 强制落在[850,1050]、y 落在[970,1030]
                // （即使找图偶发偏到相邻标签，也不允许点到右侧行前备战的 x≈2100）
                val tx = entry.cx.coerceIn(850, 1050)
                val ty = entry.cy.coerceIn(970, 1030)
                ALog.i("[状态导航] 第$round 轮：在大厅，特勤处菜单字 teqin_entry sim=${entry.score} 命中坐标(${entry.cx},${entry.cy}) → 点击特勤处($tx,$ty)（已连续无效${entryTappedStuck}次）")
                frame.recycle()
                g.tapPhysical(tx, ty)
                entryTappedStuck++
                delay(4_000L)
                continue
            }
            if (entry != null) {
                ALog.w("[状态导航] 第$round 轮：特勤处菜单字命中但已连续2次点不进去→判定被遮挡，转关弹窗/返回清遮挡")
            } else {
                ALog.i("[状态导航] 第$round 轮：底部未找到特勤处菜单字(sim<0.62)，转弹窗兜底")
            }

            // ③【弹窗兜底】看不到特勤处菜单字才怀疑被遮挡 → 找顶部 X 点它关弹窗。
            //  行业铁律：关弹窗必须有次数上限(maxPerRun)，否则顶部常驻图标误判会无限 continue 死循环。
            if (popupCloses < maxPopupCloses) {
                val foundX = TemplateStore.findTemplate("close_btn", frame, xRoi, 0.70f)
                if (foundX != null) {
                    val sigBefore = sampleSig(frame)
                    ALog.i("[状态导航] 第$round 轮：检测到弹窗X sim=${foundX.score}，点击(${foundX.cx},${foundX.cy})关闭（第${popupCloses + 1}/$maxPopupCloses 次）")
                    frame.recycle()
                    g.tapPhysical(foundX.cx, foundX.cy)
                    delay(1_500L)
                    popupCloses++
                    entryTappedStuck = 0  // 【065】关了弹窗，重新给"点特勤处"机会
                    // 点后画面变化验证：真弹窗关掉画面会大变；常驻图标点了几乎不变=误判
                    val afterFrame = FrameBus.snapshot()
                    if (afterFrame != null) {
                        val diff = sigDiff(sigBefore, sampleSig(afterFrame))
                        afterFrame.recycle()
                        ALog.i("[状态导航] 第$round 轮：点X后画面签名差=%.1f".format(diff))
                        if (diff < 3f) ALog.w("[状态导航] 点X后画面几乎无变化→疑为常驻图标误判，已消耗1次弹窗额度，不再反复点")
                    }
                    continue
                }
            } else {
                ALog.w("[状态导航] 关弹窗已达上限 $maxPopupCloses 次，不再点X（防常驻图标误判死循环）")
            }

            // ④ 都不命中 或 特勤处被遮挡 → 疑似卡在子菜单/广告/加载页 → 按一次返回清遮挡
            ALog.w("[状态导航] 第$round 轮：非特勤处/无弹窗可关/特勤处被遮挡，按一次返回清遮挡")
            frame.recycle()
            g.back(1_200L)
            entryTappedStuck = 0  // 【065】按了返回，重新给"点特勤处"机会
            delay(600L)
        }

        ALog.w("[状态导航] $maxRounds 轮仍未进特勤处，导航失败，保留现场")
        return false
    }

    /** 取帧的 4x4 网格采样亮度签名（16 个 float），用于判断画面是否换页 */
    private fun sampleSig(bmp: android.graphics.Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val sig = FloatArray(16)
        var i = 0
        for (gy in 0..3) for (gx in 0..3) {
            val x = (w * (gx + 0.5f) / 4).toInt().coerceIn(0, w - 1)
            val y = (h * (gy + 0.5f) / 4).toInt().coerceIn(0, h - 1)
            val p = bmp.getPixel(x, y)
            sig[i++] = ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3).toFloat()
        }
        return sig
    }

    /** 两个签名的平均绝对差 */
    private fun sigDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 999f
        var s = 0f
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s / a.size
    }

    private fun toast(msg: String) {
        ALog.i("[Toast] $msg")
        mainHandler.post { Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun stop() {
        ALog.i("[023] 收到 stop()")
        running = false; phase = Phase.STOPPED
    }
}

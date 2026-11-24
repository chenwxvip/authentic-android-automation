package com.ayu.authentic.core

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import com.ayu.authentic.config.Config
import com.ayu.authentic.service.AuthenticAccessibilityService
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.FrameBus
import com.ayu.authentic.vision.FrameDump
import com.ayu.authentic.vision.TemplateStore
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 【2026-11-18 独立模块】特勤处收菜/种菜 farmer。
 *
 * 【解耦铁律】本文件与登录轮询主流程(AccountPollMachine.runAll/runOneAccount)完全独立：
 *  - 不修改、不调用、不依赖主流程的任何状态；
 *  - 只由独立入口 main 触发；
 *  - 所有坐标来自 Config.Teqin（用户真机口述校准）。
 *
 * 【为什么这样写】游戏是 UE4，UI 树读不到任何文字（实测非空 text 节点=0），
 * 所以判断"成熟/空闲/倒计时"只能靠【点开台子后存帧 + 中央区域亮暗特征】：
 *  - 获得奖励页：中央暗底上有一个高亮大物品图 → 高亮像素占比高 → 判定成熟，收菜；
 *  - 制造页/倒计时页：点开后无奖励特征 → 判定非成熟，跳过（本轮只做收菜，种菜单独入口）。
 *
 * 收菜流程（对 4 个台子逐个）：
 *   点台子 → 存帧判断 → 若是奖励页：点(2000,200)关全屏 → 收菜+1；否则按返回/点空白跳过。
 * 种菜流程（runPlantOne，独立入口，指定台子）：
 *   点空闲台子 → 选第一个物品 → 一键补齐 → 确认购买 → 存帧防呆(钱不够则 NEED_FUNDS 跳过)
 *   → 生产 → 存帧确认中止+倒计时 → 返回。
 */
class TeqinFarmer(private val context: Context) {

    enum class Phase { IDLE, RUNNING, DONE }
    @Volatile var phase: Phase = Phase.IDLE; private set
    @Volatile var running = false; private set

    /** 本轮统计 */
    data class Stats(var harvested: Int = 0, var planted: Int = 0, var skipped: Int = 0, var needFunds: Int = 0)
    private var stats = Stats()
    fun lastStats(): Stats = stats.copy()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 收菜：遍历4台子，收掉所有成熟的 */
    suspend fun runHarvest() {
        if (running) return
        running = true; phase = Phase.RUNNING
        stats = Stats()
        ALog.i("[特勤处] === 收菜开始 ===")
        toast("特勤处收菜开始")

        if (!AuthenticAccessibilityService.isReady()) {
            ALog.w("[特勤处] 无障碍未就绪，中止")
            finish(); return
        }
        val g = AuthenticAccessibilityService.gesture ?: run { ALog.w("[特勤处] 手势不可用"); finish(); return }

        // 1. 拉起游戏 → 等冷启动 → 进大厅 → 前往游玩 → 特勤处
        val pkg = GameLauncher(context).launch()
        if (pkg == null) { ALog.w("[特勤处] 游戏未拉起，中止"); finish(); return }
        delay(Config.STEP_1_BOOT_MS)
        // 大厅 → 前往游玩
        g.tapRegion(Config.BTN_ENTER_FIRE_ZONE)
        delay(Config.STEP_GAP_MS)
        // 行前备战页 → 按返回关可能的礼包弹窗
        g.back(1200)
        // 进入特勤处：底部标签栏「特勤处」（用户真机校准坐标）
        g.tapRegion(Config.Teqin.LOBBY_TEQIN_ENTRY)
        delay(Config.STEP_GAP_MS)
        saveFrame("teqin_enter")
        ALog.i("[特勤处] 已进入特勤处4台子界面（入口坐标待实测校准）")

        // 2. 逐个台子收菜
        for ((idx, st) in Config.Teqin.STATIONS.withIndex()) {
            if (!running) break
            ALog.i("[特勤处] ---- 台子 ${idx + 1}/4 center=(${st.cx},${st.cy}) ----")
            g.tapRegion(st)
            delay(Config.STEP_GAP_MS)
            val f = FrameBus.snapshot()
            val isReward = f?.let { looksLikeRewardPage(it) } ?: false
            if (f != null) { FrameDump.save(context, f, "harvest_station_${idx + 1}_${if (isReward) "reward" else "skip"}"); f.recycle() }
            ALog.i("[特勤处] 台子${idx + 1} 判定: ${if (isReward) "奖励页(成熟)" else "非奖励页(跳过)"}")

            if (isReward) {
                // 收菜：点右上角空白关全屏
                g.tapRegion(Config.Teqin.CLOSE_REWARD_FULLSCREEN)
                delay(Config.STEP_GAP_MS)
                stats.harvested++
                ALog.i("[特勤处] ✅ 台子${idx + 1} 收菜成功，累计 ${stats.harvested}")
            } else {
                stats.skipped++
                ALog.i("[特勤处] 台子${idx + 1} 未成熟/空闲，跳过")
                // 若点开是制造页(空闲)，按返回退回4台子；若是倒计时页点开无反应，返回键无害
                g.back(Config.UI_SETTLE_MS)
            }
        }
        ALog.i("[特勤处] === 收菜结束：收${stats.harvested} 跳过${stats.skipped} ===")
        toast("收菜完成：收${stats.harvested}个")
        finish()
    }

    /** 种菜：针对指定台子（默认台子1）走完整制造流程。独立入口，不与收菜混用。 */
    suspend fun runPlantOne(stationIndex: Int = 0) {
        if (running) return
        running = true; phase = Phase.RUNNING
        stats = Stats()
        ALog.i("[特勤处] === 种菜开始 台子${stationIndex + 1} ===")
        val g = AuthenticAccessibilityService.gesture ?: run { ALog.w("[特勤处] 手势不可用"); finish(); return }
        val st = Config.Teqin.STATIONS.getOrNull(stationIndex) ?: run { ALog.w("[特勤处] 台子索引越界"); finish(); return }

        // 假设已在特勤处4台子界面（由调用方保证，或复用 runHarvest 的进入逻辑）
        g.tapRegion(st)
        delay(Config.STEP_GAP_MS)
        // 选第一个物品（骨架狙击枪托）
        g.tapRegion(Config.Teqin.FIRST_ITEM_ROW)
        delay(Config.STEP_GAP_MS)
        // 点一键补齐
        g.tapRegion(Config.Teqin.BTN_CRAFT_ACTION)
        delay(Config.STEP_GAP_MS)
        // 确认购买（绿色金额按钮）
        g.tapRegion(Config.Teqin.BTN_BUY_CONFIRM)
        delay(2000L)
        // 防呆：购买后2s存帧，检查"购买框是否消失/按钮区是否变化"——用中央区域特征粗判
        val afterBuy = FrameBus.snapshot()
        val buyStillOpen = afterBuy?.let { looksLikeBuyDialog(it) } ?: false
        if (afterBuy != null) { FrameDump.save(context, afterBuy, "plant_after_buy"); afterBuy.recycle() }
        if (buyStillOpen) {
            ALog.w("[特勤处] ⚠ 购买后弹窗仍在 → 判定哈夫币不足(NEED_FUNDS)，跳过该台子，不死循环")
            stats.needFunds++
            g.tapRegion(Config.Teqin.BTN_BUY_CANCEL_X)
            delay(Config.STEP_GAP_MS)
            g.back(Config.UI_SETTLE_MS)
            finish(); return
        }
        // 点生产（同一坐标，此时是"生产"）
        g.tapRegion(Config.Teqin.BTN_CRAFT_ACTION)
        delay(Config.STEP_GAP_MS)
        val afterProd = FrameBus.snapshot()
        if (afterProd != null) { FrameDump.save(context, afterProd, "plant_after_produce"); afterProd.recycle() }
        stats.planted++
        ALog.i("[特勤处] ✅ 台子${stationIndex + 1} 已点生产，倒计时开始")
        // 返回4台子
        g.tapRegion(Config.Teqin.BTN_CRAFT_BACK)
        delay(Config.STEP_GAP_MS)
        ALog.i("[特勤处] === 种菜结束：种${stats.planted} 缺钱跳过${stats.needFunds} ===")
        finish()
    }

    /**
     * 【2026-11-18 一键收种】遍历4台子，成熟就收+随即种，倒计时/空闲跳过。
     * 用户要求：挂机一键闭环——有熟的收掉，收完变空闲，当场在这个台子种下新菜。
     * 流程（每个台子）：
     *   点台子 → 存帧判断奖励页
     *     ├ 是奖励页(成熟) → 点(2000,200)关闭 → harvested++
     *     │                  → 再点同一台子进制造页 → 走种菜流程 → planted++
     *     └ 否(倒计时/空闲) → 按返回跳过
     */
    suspend fun runHarvestAndPlant() {
        if (running) return
        running = true; phase = Phase.RUNNING
        stats = Stats()
        ALog.i("[特勤处] === 一键收种开始 ===")
        toast("特勤处一键收种开始")

        if (!AuthenticAccessibilityService.isReady()) {
            ALog.w("[特勤处] 无障碍未就绪，中止"); finish(); return
        }
        val g = AuthenticAccessibilityService.gesture ?: run { ALog.w("[特勤处] 手势不可用"); finish(); return }

        // 1. 【三段式进入】模式选择页 → 行前备战 → 备战大厅 → 特勤处，每段校验
        val pkg = GameLauncher(context).launch()
        if (pkg == null) { ALog.w("[特勤处] 游戏未拉起，中止"); finish(); return }
        delay(Config.STEP_1_BOOT_MS)

        if (!navigateToTeqin(g)) {
            ALog.w("[特勤处] ⚠ 三段式进入失败，安全中止（绝不在错误页面瞎点）")
            toast("未能进入特勤处，已安全中止")
            finish(); return
        }
        saveFrame("oneclick_enter")

        // 2. 逐台子收+种（每步都校验，绝不在错误页面瞎点）
        for ((idx, st) in Config.Teqin.STATIONS.withIndex()) {
            if (!running) break
            // 【021关键】点台子前必须确认还在特勤处——否则坐标点偏已跳走，立即中止
            if (!isAtTeqin()) {
                ALog.w("[特勤处] ⚠ 点台子${idx + 1}前发现已不在特勤处 → 中止，绝不瞎点")
                toast("已离开特勤处，安全中止")
                break
            }
            ALog.i("[特勤处] ---- 台子 ${idx + 1}/4 center=(${st.cx},${st.cy}) ----")
            g.tapRegion(st)
            delay(Config.STEP_GAP_MS)
            // 【022】等新帧后再判断，避免拿点击前的旧帧
            var w = 0
            while (!FrameBus.isFresh() && w < 4000) { delay(500L); w += 500 }
            val f = FrameBus.snapshot()
            if (f != null) { FrameDump.save(context, f, "oneclick_s${idx + 1}_aftertap"); f.recycle() }

            // 【022】先用模板匹配判奖励页（奖励页是全屏页，不属于4台子界面，不能用 isAtTeqin 判）
            val afterTap = FrameBus.snapshot()
            val isReward = afterTap?.let { looksLikeRewardPage(it) } ?: false
            afterTap?.recycle()
            ALog.i("[特勤处] 台子${idx + 1} 奖励页判定(模板): ${if (isReward) "是(成熟)" else "否"}")
            // 非奖励页时，必须确认仍在特勤处4台子界面；跳到模式页/大厅=坐标点偏，立即停
            if (!isReward && !isAtTeqin()) {
                ALog.w("[特勤处] ⚠ 台子${idx + 1}点开后已离开特勤处(age=${FrameBus.ageMs()}ms) → 中止本台")
                if (!navigateToTeqin(g)) { ALog.w("[特勤处] 重新导航失败，中止整轮"); break }
                continue
            }

            if (!isReward) {
                stats.skipped++
                ALog.i("[特勤处] 台子${idx + 1} 跳过（未成熟/非奖励页）")
                // 【021】不在这里按返回！020就是back把界面退到模式页又触发退出框。
                // 若点开是制造页/倒计时页，先尝试回退到特勤处；用校验确认，不盲退。
                if (!isAtTeqin()) {
                    ALog.w("[特勤处] 台子${idx + 1} 点开后离开了特勤处，尝试重新导航")
                    if (!navigateToTeqin(g)) { ALog.w("[特勤处] 重新导航失败，中止"); break }
                }
                continue
            }

            // 收菜：关闭奖励页
            g.tapRegion(Config.Teqin.CLOSE_REWARD_FULLSCREEN)
            delay(Config.STEP_GAP_MS)
            stats.harvested++
            ALog.i("[特勤处] ✅ 台子${idx + 1} 收菜成功")

            // 紧接着在这个台子种菜
            plantAfterHarvest(g, idx, st)
        }
        ALog.i("[特勤处] === 一键收种结束：收${stats.harvested} 种${stats.planted} 跳过${stats.skipped} 缺钱${stats.needFunds} ===")
        toast("收${stats.harvested} 种${stats.planted} 跳过${stats.skipped}")
        finish()
    }

    /** 收菜后紧接着在同台子种菜（内联版，避免重复启停 running 状态） */
    private suspend fun plantAfterHarvest(
        g: com.ayu.authentic.gesture.GestureDispatcher,
        idx: Int,
        st: Config.Region
    ) {
        ALog.i("[特勤处] 台子${idx + 1} 开始种菜")
        g.tapRegion(st)                                   // 空闲台子 → 制造页
        delay(Config.STEP_GAP_MS)
        g.tapRegion(Config.Teqin.FIRST_ITEM_ROW)          // 选第一个物品
        delay(Config.STEP_GAP_MS)
        g.tapRegion(Config.Teqin.BTN_CRAFT_ACTION)        // 一键补齐
        delay(Config.STEP_GAP_MS)
        g.tapRegion(Config.Teqin.BTN_BUY_CONFIRM)         // 确认购买
        delay(2000L)
        // 防呆：钱不够 → 购买框仍在 → 跳过
        val af = FrameBus.snapshot()
        val stillOpen = af?.let { looksLikeBuyDialog(it) } ?: false
        if (af != null) { FrameDump.save(context, af, "oneclick_s${idx + 1}_afterbuy"); af.recycle() }
        if (stillOpen) {
            ALog.w("[特勤处] ⚠ 台子${idx + 1} 购买框仍在 → NEED_FUNDS，跳过种菜")
            stats.needFunds++
            g.tapRegion(Config.Teqin.BTN_BUY_CANCEL_X)
            delay(Config.STEP_GAP_MS)
            g.back(Config.UI_SETTLE_MS)
            return
        }
        g.tapRegion(Config.Teqin.BTN_CRAFT_ACTION)        // 生产
        delay(Config.STEP_GAP_MS)
        val pf = FrameBus.snapshot()
        if (pf != null) { FrameDump.save(context, pf, "oneclick_s${idx + 1}_produce"); pf.recycle() }
        stats.planted++
        ALog.i("[特勤处] ✅ 台子${idx + 1} 已生产，倒计时开始")
        g.tapRegion(Config.Teqin.BTN_CRAFT_BACK)          // 返回4台子
        delay(Config.STEP_GAP_MS)
    }

    /**
     * 【020 入口校验】当前是否真的在特勤处4台子界面。
     * 只用固定 UI 区域（左上"特勤处"标题 + 左侧4台子卡片），绝不用中间会变的角色立绘。
     * 用 assets 里的 teqin_stations 基准图做区域模板比对；拿不准返回 false（宁可重试，不可瞎点）。
     */
    private fun isAtTeqin(): Boolean {
        TemplateStore.init(context)
        // 【022 关键】帧新鲜度守卫：旧帧会让我们"看着特勤处、实际在模式页"→点坐标跳飞
        if (!FrameBus.isFresh()) {
            ALog.w("[特勤处] 入口校验：帧已过期(age=${FrameBus.ageMs()}ms)，拒绝用旧帧判断")
            return false
        }
        val f = FrameBus.snapshot() ?: run {
            ALog.w("[特勤处] 校验：无实时帧"); return false
        }
        val sim = compareRoi(f, Config.Teqin.TEQIN_CHECK_ROI)
        f.recycle()
        val ok = sim >= Config.Teqin.TEQIN_SIM_THRESHOLD
        ALog.i("[特勤处] 入口校验: sim=%.3f 阈值=%.2f → %s".format(sim, Config.Teqin.TEQIN_SIM_THRESHOLD, if (ok) "在特勤处" else "不在"))
        return ok
    }

    /** 【021】是否已在备战大厅（底部固定标签栏比对，中间立绘不参与） */
    private fun isAtLobbyPrepare(): Boolean {
        TemplateStore.init(context)
        if (!FrameBus.isFresh()) return false
        val f = FrameBus.snapshot() ?: return false
        val sim = compareRoi(f, Config.Teqin.LOBBY_CHECK_ROI, "lobby_roi")
        f.recycle()
        val ok = sim >= 0.60f
        ALog.i("[特勤处] 大厅校验: sim=%.3f → %s".format(sim, if (ok) "备战大厅" else "不是"))
        return ok
    }

    /**
     * 【021 三段式进入】模式选择页 → 行前备战 → 备战大厅 → 特勤处，每段严格校验。
     * 返回 true=已在特勤处4台子界面，可以开始收种；false=失败（调用方应中止）。
     */
    private suspend fun navigateToTeqin(g: com.ayu.authentic.gesture.GestureDispatcher): Boolean {
        TemplateStore.init(context)

        // 【022】等新鲜帧：游戏页面切换时截图服务可能短暂停帧，旧帧会误导判断
        var waited = 0
        while (!FrameBus.isFresh() && waited < 6000) { delay(500L); waited += 500 }
        ALog.i("[特勤处] 帧新鲜度检查：age=${FrameBus.ageMs()}ms fresh=${FrameBus.isFresh()}")
        if (!FrameBus.isFresh()) { ALog.w("[特勤处] 等待6s仍无新帧，中止"); return false }

        // 第0步：已经在特勤处 → 直接返回
        if (isAtTeqin()) { ALog.i("[特勤处] 起点已在特勤处，跳过导航"); return true }

        // 第1步：如果在大厅 → 点「行前备战」进烽火地带；否则假设在模式选择页
        if (isAtLobbyPrepare()) {
            ALog.i("[特勤处] 当前在备战大厅 → 点「行前备战」进模式选择")
            g.tapRegion(Config.Teqin.LOBBY_START_BUTTON)
            delay(Config.STEP_GAP_MS)
        } else {
            ALog.i("[特勤处] 当前非大厅/非特勤处 → 当作模式选择页，找「行前备战」入口")
        }

        // 第2步：模式选择页 → 点「前往游玩」/行前备战 → 期望回到备战大厅
        //  (模式选择页右下也有"行前备战"绿按钮；用大厅标签校验确认回到大厅)
        var reachedLobby = isAtLobbyPrepare()
        for (i in 1..3) {
            if (reachedLobby) break
            ALog.i("[特勤处] 尝试从模式页进大厅 第${i}/3次：点前往游玩 ((${Config.BTN_ENTER_FIRE_ZONE.cx},${Config.BTN_ENTER_FIRE_ZONE.cy}))")
            g.tapRegion(Config.BTN_ENTER_FIRE_ZONE)
            delay(2000L)
            reachedLobby = isAtLobbyPrepare()
        }
        if (!reachedLobby) {
            ALog.w("[特勤处] 未能进入备战大厅（三段式第1段失败），中止")
            return false
        }
        ALog.i("[特勤处] ✅ 已到备战大厅")

        // 第3步：备战大厅 → 点底部「特勤处」（实测要点2下），校验进4台子
        for (i in 1..3) {
            ALog.i("[特勤处] 点底部「特勤处」第${i}/3次 ((${Config.Teqin.LOBBY_TEQIN_ENTRY.cx},${Config.Teqin.LOBBY_TEQIN_ENTRY.cy}))")
            g.tapRegion(Config.Teqin.LOBBY_TEQIN_ENTRY)
            delay(2000L)
            if (isAtTeqin()) {
                ALog.i("[特勤处] ✅ 第${i}次成功进入特勤处4台子界面")
                return true
            }
        }
        ALog.w("[特勤处] 点特勤处3次仍未进4台子界面，中止")
        return false
    }

    /** 当前帧 ROI 与指定基准模板逐点比对，返回 0f..1f 相似度 */
    private fun compareRoi(frame: Bitmap, roi: Config.Region, templateId: String = "teqin_roi"): Float {
        val tpl = TemplateStore.getBitmap(templateId) ?: return 0f
        val w = (roi.right - roi.left).coerceAtMost(frame.width - roi.left)
        val h = (roi.bottom - roi.top).coerceAtMost(frame.height - roi.top)
        if (w <= 0 || h <= 0) return 0f
        var sum = 0L; var n = 0
        val step = 6
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                val c = frame.getPixel(roi.left + tx, roi.top + ty)
                val sx = (tx * tpl.width / w).coerceIn(0, tpl.width - 1)
                val sy = (ty * tpl.height / h).coerceIn(0, tpl.height - 1)
                val s = tpl.getPixel(sx, sy)
                sum += abs(((c shr 16) and 0xFF) - ((s shr 16) and 0xFF)) +
                       abs(((c shr 8) and 0xFF) - ((s shr 8) and 0xFF)) +
                       abs((c and 0xFF) - (s and 0xFF))
                n += 3; tx += step
            }
            ty += step
        }
        return if (n == 0) 0f else (1f - (sum.toFloat() / n) / 255f).coerceIn(0f, 1f)
    }

    /**
     * 判断存帧是不是「获得奖励」全屏页：
     * 中央大区(约 x[900..1500] y[300..800])在奖励页是高亮物品图，普通页是暗色背景。
     * 用"高亮像素占比 + 平均亮度"粗判，阈值保守；拿不准返回 false（宁可漏收，不可误点）。
     */
    private fun looksLikeRewardPage(bmp: Bitmap): Boolean {
        // 【022 修复】改用整帧模板匹配，杜绝"模式页阳光"被亮度误判成奖励页
        val sim = TemplateStore.sceneSimilarity("reward_page", bmp)
        val hit = sim >= 0.62f
        ALog.i("[特勤处] 奖励页判定(模板): sim=%.3f 阈值=0.62 → %s".format(sim, if (hit) "奖励页" else "普通页"))
        return hit
    }

    /** 判断购买框是否还在：中央偏下(确认按钮区)出现大面积绿色 → 框还在；消失=购买成功 */
    private fun looksLikeBuyDialog(bmp: Bitmap): Boolean {
        val f = if (bmp.width == Config.BASE_W && bmp.height == Config.BASE_H) bmp
                else Bitmap.createScaledBitmap(bmp, Config.BASE_W, Config.BASE_H, true)
        var green = 0; var total = 0
        for (y in 930 until 1050 step 6) {
            for (x in 990 until 1420 step 6) {
                val c = f.getPixel(x, y)
                val r = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val b = c and 0xFF
                if (gg > 120 && gg > r + 30 && gg > b + 10) green++
                total++
            }
        }
        if (f !== bmp) f.recycle()
        val ratio = green.toFloat() / total.coerceAtLeast(1)
        ALog.i("[特勤处] 购买框判定: greenRatio=%.3f → %s".format(ratio, if (ratio > 0.08f) "仍在(疑缺钱)" else "已消失(购买成功)"))
        return ratio > 0.08f
    }

    private fun saveFrame(tag: String) {
        val f = FrameBus.snapshot() ?: return
        FrameDump.save(context, f, tag)
        f.recycle()
    }

    private fun finish() { running = false; phase = Phase.DONE }

    private fun toast(msg: String) {
        ALog.i("[Toast] $msg")
        mainHandler.post { Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun stop() { ALog.i("[特勤处] 收到 stop()"); running = false; phase = Phase.DONE }
}

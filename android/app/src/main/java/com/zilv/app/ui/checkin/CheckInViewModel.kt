package com.zilv.app.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.mapper.RuleCheckInStatus
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot
import com.zilv.app.domain.spot.CheckInValidator
import com.zilv.app.domain.spot.GeoUtils
import com.zilv.app.location.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.HabitType

/** 打卡页的 6 种状态（PRD §4.3 交互状态机）。 */
enum class CheckInPhase {
    Idle,
    Locating,
    NeedPermission,
    AccuracyTooLow,
    TooFar,
    Ready,
    Success,
    Error
}

data class CheckInUiState(
    val ruleId: Long = 0L,
    val ruleName: String = "",
    /**
     * 从**习惯**进来的那条习惯 id；`-1`（或 0）表示不是从习惯进来的。
     *
     * v2.2 新增：室内定位失败时要给一个「改用无条件打卡」的快捷入口，
     * 而那个操作改的是**习惯**（见 [CheckInViewModel.useUnconditionalCheckIn]），
     * 所以必须知道改哪一条。
     */
    val habitId: Long = NO_HABIT,
    /**
     * 这条习惯的打卡条件是否为「指定地点」。
     *
     * 只有"指定地点"的习惯才需要（也才允许）改成无条件 —— 否则这个按钮
     * 对一条本来就无条件的习惯毫无意义。
     */
    val habitNeedsLocation: Boolean = false,
    val spot: Spot? = null,
    val location: GeoPoint? = null,
    val distanceM: Double? = null,
    val directionHint: String? = null,
    val phase: CheckInPhase = CheckInPhase.Idle,
    val message: String = "",
    /**
     * 连续**精度不达标**的计数（含"拿不到定位"）。
     *
     * ⚠️ v2.12 起它**只**服务于「拍照存证 + 临时解锁」那条门槛
     * （只在从锁机规则进来时才有），口径与 v2.10/v2.11 完全一致 ——
     * "拿不到定位"与"精度不达标"都算。
     *
     * 「改用无条件打卡」**不再**读它，读 [consecutiveLocationFailures]。
     * 两条门槛各有各的计数，用户点名要求别混在一起。
     */
    val consecutiveAccuracyFailures: Int = 0,
    /**
     * 连续**拿不到定位**的次数（v2.12，用户第 4 条要求）。
     *
     * > 「只有『定位失败』（拿不到定位）才累加这个门槛。
     * > 『定位成功但不在打卡范围内』不算失败，不能触发无条件打卡。」
     *
     * 所以它**只在** `LocationSource.currentLocation()` 返回 `null` 时 +1；
     * 只要拿到了定位（通过 / 太远 / 精度不达标都算拿到了），立刻清零。
     * 计数规则是纯函数：`CheckInValidator.nextLocationFailureCount` /
     * `CheckInValidator.locationFailureCountAfterFix`。
     */
    val consecutiveLocationFailures: Int = 0,
    /** 是否开放室内降级通道（连续 3 次精度不达标） */
    val offerFallback: Boolean = false,
    /**
     * 是否给出「改用无条件打卡」入口（连续 3 次**拿不到定位**）。
     *
     * ⚠️ 它与 [offerFallback] 数值相同但**适用路径不同**：
     *  - [offerFallback]：只在 `ruleId > 0`（从锁机规则进来）时有意义
     *  - 本字段：只在从**「指定地点」的习惯**进来（`habitId > 0` 且该习惯
     *    确实要求地点）时才有意义
     *
     * 两条路可以同时可用（既从规则进来、又挂着一条指定地点的习惯），
     * 所以界面上它们各占一块、各说各的，不共用一个开关。
     */
    val offerUnconditional: Boolean = false,
    /**
     * 用户已经在弹窗里选了「继续重试」（v2.12）。
     *
     * 置真之后**本次进入打卡页期间**不再自动弹那个对话框 ——
     * 否则每失败一次就弹一次，等于骚扰。
     * 只要定位成功过一次（计数清零），它跟着回到 false：
     * 下次再连续失败 3 次时该弹还是要弹。
     */
    val unconditionalOfferAcknowledged: Boolean = false,
    /** 已经把它改成无条件打卡了，界面提示用户"回去点一下就能打卡" */
    val switchedToUnconditional: Boolean = false,
    val saved: Boolean = false
) {
    val canCheckIn: Boolean get() = phase == CheckInPhase.Ready

    /**
     * 还差几次失败才能用上「改用无条件打卡」；0 表示现在就能用。
     *
     * 数值由 [CheckInValidator.failuresUntilOffer] 算 —— 阈值只有一个来源。
     *
     * ⚠️ v2.12：这个数字**不再显示在界面上**（用户要求"把无条件打卡的提示隐藏"）。
     * 它仍然留着，是因为"还差几次"是门槛本身的一部分（[unconditionalPending]
     * 与若干单测都读它），只是不再被画出来。
     */
    val failuresUntilUnconditional: Int
        get() = CheckInValidator.failuresUntilOffer(consecutiveLocationFailures)

    /** 这条路**本可以**走通（从指定地点的习惯进来），只是失败次数还不够。 */
    val unconditionalPending: Boolean
        get() = habitId > 0 && habitNeedsLocation && !offerUnconditional

    /**
     * 现在该不该**弹出**「改用无条件打卡」的对话框（v2.12）。
     *
     * 两个条件：达到门槛（[offerUnconditional]）且用户这次还没选过"继续重试"
     * （[unconditionalOfferAcknowledged]）。
     *
     * 用对话框而不是页面里的一块卡片，是用户明确要求：
     * 「达到 3 次 → **弹出对话框**（不是页面里的卡片），让用户选择
     * 『改用无条件打卡』或『继续重试』」。这同时修掉了一个真实缺陷：
     * 原先那块卡片排在页面末尾而整页**不可滚动**，在手机上会被挤出屏幕。
     */
    val showUnconditionalDialog: Boolean
        get() = offerUnconditional && !unconditionalOfferAcknowledged

    companion object {
        /** 没有关联习惯时的 [habitId] 哨兵值（路由默认值也是它）。 */
        const val NO_HABIT = -1L
    }
}

/**
 * 打卡流程（PRD §3.5 / §4.3）。
 *
 * 校验逻辑在 [CheckInValidator]（纯函数，16 个单元测试覆盖）；
 * 本类只负责编排：取定位 → 校验 → 落库 → 联动解锁状态。
 */
class CheckInViewModel(
    private val spotRepository: SpotRepository,
    private val lockRuleRepository: LockRuleRepository,
    private val habitRepository: HabitRepository,
    private val locationProvider: LocationSource,
    private val clock: Clock,
    /**
     * 打卡成功后的通知钩子。
     *
     * **为什么必需**：打卡解锁后锁机状态从 LOCKED 变成 UNLOCKED，
     * 但原先没有任何东西会去通知前台服务重算 —— 结果是解锁了、
     * 通知栏却还挂着「锁机中」。用户明确要求打卡成功后通知也要消失。
     *
     * 用回调而不是直接依赖 Service，是为了让本 ViewModel 保持可单元测试。
     */
    private val onLockStateChanged: () -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    /**
     * 进入打卡页（不带习惯）。
     *
     * 保留这个重载是为了不破坏既有调用点与回归测试；
     * 需要「改用无条件打卡」入口时必须用带 `habitId` 的那个。
     */
    fun start(ruleId: Long, spotId: Long) = start(ruleId, spotId, CheckInUiState.NO_HABIT)

    /**
     * 进入打卡页：载入规则与打卡点，并立刻取一次位置。
     *
     * @param habitId 这次打卡挂在哪条**习惯**上（`-1` 表示没有）。
     *        室内定位失败时要用它把该习惯的打卡条件改成「无」。
     */
    fun start(ruleId: Long, spotId: Long, habitId: Long) {
        viewModelScope.launch {
            val rule = lockRuleRepository.getRule(ruleId)
            val spot = spotRepository.getSpot(spotId)
            // 打卡条件取自**习惯**：只有"指定地点"的习惯才需要真正的定位，
            // 也才允许就地改成"无条件"。
            val habit = habitId.takeIf { it > 0 }?.let { habitRepository.getHabit(it) }
            _uiState.value = _uiState.value.copy(
                ruleId = ruleId,
                ruleName = rule?.name.orEmpty(),
                habitId = habitId,
                habitNeedsLocation = habit?.let { habitRepository.requiresLocationProof(it) } == true,
                spot = spot
            )
            if (spot == null) {
                _uiState.value = _uiState.value.copy(
                    phase = CheckInPhase.Error,
                    message = "找不到打卡点（可能已被删除）"
                )
                return@launch
            }
            refreshLocation()
        }
    }

    /** 取一次位置（PRD：只在需要时取一次，不持续定位）。 */
    fun refreshLocation() {
        val spot = _uiState.value.spot ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(phase = CheckInPhase.Locating, message = "正在定位…")

            if (!locationProvider.hasPermission()) {
                _uiState.value = _uiState.value.copy(
                    phase = CheckInPhase.NeedPermission,
                    message = "需要定位权限才能校验打卡位置"
                )
                return@launch
            }

            val location = locationProvider.currentLocation()
            if (location == null) {
                // 用户要求（v2.2）：定位失败时必须**明说「室内定位失败」**，
                // 并且给一个「改用无条件打卡」的快捷入口，而不是只丢一句"出错了"。
                //
                // 这里修的是一个真实缺陷：原先只有"精度不达标"才累加失败计数，
                // 而"定位超时/拿不到定位"直接进 Error —— 于是室内用户
                // 永远看不到降级通道，只能卡在"出错了"上。
                //
                // ---- v2.12（用户第 4 条）：两条门槛各数各的 ----
                //
                // ⚠️ 这里**是唯一**会累加「无条件打卡」门槛的地方：
                // 只有"拿不到定位"才算失败。`applyValidation` 那三条路径
                // （通过 / 精度不达标 / 距离过远）都拿到了定位，一律清零。
                //
                // 「拍照存证 + 临时解锁」那条门槛沿用 v2.10 的口径
                // （拿不到定位或精度不达标都算），计数是另一个字段，
                // 互不影响 —— 用户点名要求"两者不要混在一起"。
                val state = _uiState.value
                val accuracyFailures = state.consecutiveAccuracyFailures + 1
                val locationFailures = CheckInValidator.nextLocationFailureCount(
                    state.consecutiveLocationFailures
                )
                val canFallback = state.ruleId > 0
                val canSwitch = state.habitId > 0 && state.habitNeedsLocation
                // 两条降级通道**各有各的门槛**，都是"3 次"，但判据来自不同的计数：
                //  - canFallback：从锁机规则进来才有"这次锁机"可临时解
                //  - canSwitch：从「指定地点」的习惯进来才有可改的习惯
                // 两者可以同时成立（既从规则进来、又挂着一条指定地点的习惯）。
                val offerFallback = canFallback &&
                    CheckInValidator.shouldOfferFallback(accuracyFailures)
                val offerSwitch = canSwitch &&
                    CheckInValidator.shouldOfferUnconditionalSwitch(locationFailures)
                _uiState.value = state.copy(
                    phase = CheckInPhase.Error,
                    message = buildString {
                        append("室内定位失败")
                        append("：室内、地下、信号弱时很常见。可以重试")
                        if (offerFallback) {
                            append("，或点下面的「拍照存证 + 临时解锁」")
                        }
                        // ⚠️ v2.12：**这里不再报"再失败 N 次可以改用无条件打卡"**。
                        // 用户要求"把无条件打卡的提示隐藏" —— 没到门槛时界面上
                        // 一个字都不该提它；到了门槛直接弹对话框（见 showUnconditionalDialog）。
                        append("。")
                    },
                    consecutiveAccuracyFailures = accuracyFailures,
                    consecutiveLocationFailures = locationFailures,
                    offerFallback = offerFallback,
                    offerUnconditional = offerSwitch
                    // ⚠️ 刻意**不**重置 unconditionalOfferAcknowledged：
                    // 用户选了"继续重试"之后，再失败也不该反复弹同一个对话框。
                    // 只有定位成功过一次（计数清零）才会把它放回去。
                )
                return@launch
            }

            applyValidation(spot, location)
        }
    }

    private fun applyValidation(spot: Spot, location: GeoPoint) {
        val result = CheckInValidator.validate(location, spot)
        val distance = GeoUtils.haversineMeters(location.lat, location.lng, spot.lat, spot.lng)
        val bearing = GeoUtils.bearingDegrees(location.lat, location.lng, spot.lat, spot.lng)

        when (result) {
            is CheckInValidator.Result.Pass -> {
                _uiState.value = _uiState.value.copy(
                    location = location,
                    distanceM = distance,
                    directionHint = null,
                    phase = CheckInPhase.Ready,
                    message = "已到达打卡范围",
                    // 成功一次就重置精度失败计数
                    consecutiveAccuracyFailures = 0,
                    // v2.12：拿到了定位 —— 「无条件打卡」的门槛链条也断了
                    consecutiveLocationFailures = CheckInValidator.locationFailureCountAfterFix(),
                    offerFallback = false,
                    // v2.11：失败链条断了，两条降级通道都跟着收回去 ——
                    // 否则"定位已经好了，界面上还挂着改用无条件打卡"会让人误点。
                    offerUnconditional = false,
                    // v2.12：链条断了，下次再连续失败 3 次时对话框该重新弹
                    unconditionalOfferAcknowledged = false
                )
            }

            is CheckInValidator.Result.AccuracyTooLow -> {
                // 关键：精度不达标**不计失败**，只是累加"连续精度失败"用于开放降级通道
                val failures = _uiState.value.consecutiveAccuracyFailures + 1
                // ⚠️ v2.12（用户第 4 条）：精度不达标是**定位成功**（拿到了一个点，
                // 只是不够准），所以它**不**累加「无条件打卡」的门槛，而是把它清零。
                // 两个计数从这一版起彻底分开：
                //  - consecutiveAccuracyFailures：给「拍照存证 + 临时解锁」用（口径不变）
                //  - consecutiveLocationFailures：给「改用无条件打卡」用（只有拿不到定位才算）
                // 因此这个分支里再也算不出"改用无条件打卡" —— [offerUnconditional]
                // 恒为 false，`unconditionalPending` 也随之为真（"这条路本可以走通"）。
                _uiState.value = _uiState.value.copy(
                    location = location,
                    distanceM = distance,
                    phase = CheckInPhase.AccuracyTooLow,
                    // ⚠️ v2.12：文案里**不再**出现"再失败 N 次可以改用无条件打卡"
                    // （用户要求把那个提示隐藏）。拿到定位就是一条新消息，不是失败链条。
                    message = result.message,
                    consecutiveAccuracyFailures = failures,
                    consecutiveLocationFailures = CheckInValidator.locationFailureCountAfterFix(),
                    offerFallback = _uiState.value.ruleId > 0 &&
                        CheckInValidator.shouldOfferFallback(failures),
                    offerUnconditional = false,
                    unconditionalOfferAcknowledged = false
                )
            }

            is CheckInValidator.Result.TooFar -> {
                _uiState.value = _uiState.value.copy(
                    location = location,
                    distanceM = distance,
                    directionHint = GeoUtils.directionHint(bearing),
                    phase = CheckInPhase.TooFar,
                    message = "${result.message}（${GeoUtils.directionHint(bearing)}）",
                    consecutiveAccuracyFailures = 0,
                    // v2.12（用户原话）："定位成功但不在打卡范围之内不触发无条件打卡" ——
                    // 这是"定位成功"，所以次数清零，一点门槛都不攒。
                    consecutiveLocationFailures = CheckInValidator.locationFailureCountAfterFix(),
                    offerFallback = false,
                    // 能拿到位置就说明定位本身没坏 —— 两条降级通道都收回去
                    offerUnconditional = false,
                    unconditionalOfferAcknowledged = false
                )
            }

            is CheckInValidator.Result.NoLocation -> {
                _uiState.value = _uiState.value.copy(
                    phase = CheckInPhase.Error,
                    message = "没有拿到定位结果，请重试"
                )
            }
        }
    }

    /** 点击「立即打卡」。 */
    fun checkIn() {
        val state = _uiState.value
        val spot = state.spot ?: return
        val location = state.location ?: return
        if (!state.canCheckIn) return

        viewModelScope.launch {
            val distance = state.distanceM ?: 0.0
            // 迟到判定：打卡时刻晚于窗口开始即为迟到，具体由规则窗口决定
            val status = resolveStatus(state.ruleId)

            // 只有从锁机规则进来的才写 rule_check_ins。
            // 习惯打卡（ruleId = 0）不该往那张表塞 ruleId=0 的垃圾行。
            if (state.ruleId > 0) {
                spotRepository.recordCheckIn(
                    ruleId = state.ruleId,
                    spotId = spot.id,
                    location = location,
                    distanceM = distance,
                    status = status
                )
            }

            // 给所有绑定到这个打卡点的「到点打卡」习惯记一次**带地点**的打卡。
            // 这是它们能加连续天数的唯一途径 —— 见 HabitRepository.requiresLocationProof
            val credited = recordHabitCheckIns(spot.id, location, distance, status)

            // 打卡成功 -> 锁机状态已变 -> 立刻让前台服务重算并收起通知
            onLockStateChanged()
            _uiState.value = state.copy(
                phase = CheckInPhase.Success,
                message = if (state.ruleId > 0) {
                    "打卡成功，本次锁机已解除"
                } else {
                    buildString {
                        append("打卡成功")
                        if (credited > 0) append("，已为 $credited 个习惯记上今天的完成")
                    }
                },
                saved = true
            )
        }
    }

    /**
     * **改用无条件打卡**：把这条习惯的打卡条件从「指定地点」改成「无」。
     *
     * 用户要求（v2.2）：室内定位失败时给一个快捷入口，改完提示
     * "已改为无条件打卡，回去点一下就能打卡"。
     *
     * 落在 `type`/`spotId` 两列上（`SIMPLE` + `spotId = null`），
     * 因此**不需要新建数据库列**，也不动既有数据。
     *
     * 只对"从习惯进来且该习惯确实是「指定地点」"时有效 —— 从锁机规则进来
     * 时没有可改的习惯（见 [CheckInUiState.habitId]）。
     */
    fun useUnconditionalCheckIn() {
        val state = _uiState.value
        val habitId = state.habitId
        if (habitId <= 0 || !state.habitNeedsLocation) return

        viewModelScope.launch {
            val habit = habitRepository.getHabit(habitId)
            if (habit == null) {
                _uiState.value = _uiState.value.copy(
                    message = "找不到这条习惯（可能已被删除）",
                    offerUnconditional = false
                )
                return@launch
            }
            // ⚠️ 回调里一律写 `_uiState.value`，不用上面那个快照：
            // 中间可能已经有一次定位结果把状态推新了，用旧快照覆盖会把新状态抹掉
            // （这类"用陈旧快照回写"的 bug 在本项目出现过，症状是界面卡在旧文案上）。
            runCatching { habitRepository.save(habit.withUnconditionalCheckIn()) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        habitNeedsLocation = false,
                        offerUnconditional = false,
                        // 已经改完了，那个对话框不该再弹
                        unconditionalOfferAcknowledged = true,
                        switchedToUnconditional = true,
                        message = "已改为无条件打卡，回去点一下就能打卡"
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        message = "改成无条件打卡失败：" + (it.message ?: "未知原因")
                    )
                }
        }
    }

    /**
     * 用户在「改用无条件打卡」对话框里选了**「继续重试」**（v2.12）。
     *
     * 只是把这次机会收起来：计数继续累加（下次定位失败还是 +1），
     * 但**本次进入打卡页期间**不再自动弹同一个对话框 —— 否则每失败一次
     * 就弹一次，那不是在帮用户，是在骚扰他。
     *
     * 计数一旦清零（定位成功过），[CheckInUiState.unconditionalOfferAcknowledged]
     * 会跟着回到 false：下次再连续失败 3 次时该弹还是要弹。
     */
    fun dismissUnconditionalOffer() {
        val state = _uiState.value
        if (!state.offerUnconditional || state.unconditionalOfferAcknowledged) return
        _uiState.value = state.copy(unconditionalOfferAcknowledged = true)
    }

    /**
     * 室内定位降级通道（PRD §3.5）：拍照存证 + 临时解锁。
     *
     * 记为异常解锁，在报告里单独列出，**不计入正常打卡**。
     */
    fun abnormalUnlock(photoPath: String? = null) {
        val state = _uiState.value
        val spot = state.spot ?: return
        val location = state.location ?: GeoPoint(
            lat = spot.lat, lng = spot.lng, accuracyM = -1f, atMillis = clock.millis()
        )

        viewModelScope.launch {
            // 只有从锁机规则进来才写 rule_check_ins；习惯打卡没有规则，
            // 不该往里塞 ruleId=0 的垃圾行。
            // 另外：异常解锁刻意不给「到点打卡」习惯记账 —— 它并未真正通过地点校验。
            if (state.ruleId > 0) {
                spotRepository.recordCheckIn(
                    ruleId = state.ruleId,
                    spotId = spot.id,
                    location = location,
                    distanceM = state.distanceM ?: -1.0,
                    status = RuleCheckInStatus.ABNORMAL_UNLOCK
                )
            }
            // 异常解锁同样解除拦截，通知也要收起
            // ⚠️ 异常解锁**不**给「到点打卡」习惯记打卡 ——
            // 它是精度不达标时的降级通道，并没有真正通过地点校验。
            // 用户要求是"检测地点打卡后才能加连续天数"，所以这里严格按此执行。
            onLockStateChanged()
            _uiState.value = state.copy(
                phase = CheckInPhase.Success,
                message = "已临时解锁（记为异常解锁，会出现在报告里）",
                saved = true
            )
        }
    }

    /**
     * 把这次地点打卡记到绑定该打卡点的「到点打卡」习惯上。
     *
     * 记录**带 spotId 与坐标**，因此满足 [HabitRepository.requiresLocationProof]
     * 的判定，会真正计入连续天数。
     *
     * @return 记了几个习惯（用于给用户一句明确反馈）
     */
    private suspend fun recordHabitCheckIns(
        spotId: Long,
        location: GeoPoint,
        distanceM: Double,
        status: RuleCheckInStatus
    ): Int {
        val habits = habitRepository.getActiveHabits()
            .filter { it.type == HabitType.LOCATION && it.spotId == spotId }

        val checkInStatus = when (status) {
            RuleCheckInStatus.LATE -> CheckInStatus.LATE
            else -> CheckInStatus.ON_TIME
        }

        habits.forEach { habit ->
            habitRepository.checkIn(
                habitId = habit.id,
                status = checkInStatus,
                lat = location.lat,
                lng = location.lng,
                accuracyM = location.accuracyM,
                distanceM = distanceM,
                spotId = spotId
            )
        }
        return habits.size
    }
    private suspend fun resolveStatus(ruleId: Long): RuleCheckInStatus {
        val status = lockRuleRepository.currentStatus()
        val instance = status.instance
        if (instance == null || instance.rule.id != ruleId) return RuleCheckInStatus.ON_TIME
        // 在窗口内打卡即为迟到（提前打卡不会走到这里）
        return if (status.phase == com.zilv.app.domain.lock.LockPhase.LOCKED) {
            RuleCheckInStatus.LATE
        } else {
            RuleCheckInStatus.ON_TIME
        }
    }
}

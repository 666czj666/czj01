package com.zilv.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.BlockedAttemptRepository
import com.zilv.app.data.repository.LockSessionRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.data.repository.ZilvEvent
import com.zilv.app.domain.report.LockReportBuilder
import com.zilv.app.intercept.AppLabelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---------------------------------------------------------------- 使用时长

data class UsageEntry(
    val packageName: String,
    val label: String,
    val durationMillis: Long
)

data class UsageUiState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<UsageEntry> = emptyList(),
    /** 屏幕点亮总时长 = 所有前台应用时长之和 */
    val totalMillis: Long = 0L,
    /** 最长单次连续使用 */
    val longestMillis: Long = 0L,
    val longestAppLabel: String? = null,
    val loading: Boolean = true
)

/**
 * 使用时长页（PRD §4.4 T6.6）。
 *
 * 数据来自无障碍自采集，**不需要「使用情况访问」权限**（PRD §3.7）。
 */
class UsageViewModel(
    private val usageRepository: UsageRepository,
    private val labelResolver: AppLabelSource,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    init {
        load(usageRepository.today())
    }

    fun load(date: LocalDate) {
        viewModelScope.launch {
            val totals = usageRepository.dailyTotals(date)
            val entries = totals.entries
                .filter { it.value > 0L }
                .sortedByDescending { it.value }
                .map { UsageEntry(it.key, labelResolver.labelOf(it.key), it.value) }

            // 当天 00:00 ~ 次日 00:00 内找最长单次使用
            val zone = ZoneId.systemDefault()
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val longest = usageRepository.longestSessionInWindow(dayStart, dayEnd)
            val longestApp = if (longest > 0L) {
                usageRepository.sessionsInWindow(dayStart, dayEnd)
                    .maxByOrNull { it.durationMillis }
                    ?.let { labelResolver.labelOf(it.packageName) }
            } else {
                null
            }

            _uiState.value = UsageUiState(
                date = date,
                entries = entries,
                totalMillis = entries.sumOf { it.durationMillis },
                longestMillis = longest,
                longestAppLabel = longestApp,
                loading = false
            )
        }
    }
}

// ---------------------------------------------------------------- 拦截日志

data class BlockedRow(
    val packageName: String,
    val label: String,
    val count: Int,
    val lastAtMillis: Long
)

data class BlockedLogUiState(
    val rows: List<BlockedRow> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true
)

/** 拦截日志页（PRD §4.4 T6.5）。按合并口径统计，不是原始点击次数。 */
class BlockedLogViewModel(
    private val blockedAttemptRepository: BlockedAttemptRepository,
    private val labelResolver: AppLabelSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedLogUiState())
    val uiState: StateFlow<BlockedLogUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val attempts = blockedAttemptRepository.observeRecentOnce()
            val byPackage = attempts.groupBy { it.packageName }
                .map { (pkg, rows) ->
                    BlockedRow(
                        packageName = pkg,
                        label = labelResolver.labelOf(pkg),
                        count = rows.sumOf { it.count },
                        lastAtMillis = rows.maxOf { it.lastAtMillis }
                    )
                }
                .sortedByDescending { it.count }

            _uiState.value = BlockedLogUiState(
                rows = byPackage,
                totalCount = byPackage.sumOf { it.count },
                loading = false
            )
        }
    }
}

// ---------------------------------------------------------------- 锁机历史

data class LockHistoryUiState(
    val sessions: List<com.zilv.app.data.repository.LockSession> = emptyList(),
    val loading: Boolean = true
)

/** 锁机历史列表（PRD §4.4 T6.7）。 */
class LockHistoryViewModel(
    private val lockSessionRepository: LockSessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockHistoryUiState())
    val uiState: StateFlow<LockHistoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LockHistoryUiState(
                sessions = lockSessionRepository.recent(100),
                loading = false
            )
        }
    }
}

// ---------------------------------------------------------------- 锁机报告

data class LockReportUiState(
    val report: LockReportBuilder.Report? = null,
    val ruleName: String = "",
    val loading: Boolean = true,
    val error: String? = null
)

/**
 * 锁机结束报告（PRD §3.8 T6.8）。
 *
 * 聚合逻辑在 [LockReportBuilder]（纯函数，16 个单元测试覆盖）；
 * 本类只负责把四类原始数据取出来喂给它：
 *  1. 窗口内的使用时长（区间裁剪）
 *  2. 窗口内的拦截次数（合并口径）
 *  3. 打卡记录
 *  4. 异常事件
 */
class LockReportViewModel(
    private val lockSessionRepository: LockSessionRepository,
    private val usageRepository: UsageRepository,
    private val blockedAttemptRepository: BlockedAttemptRepository,
    private val labelResolver: AppLabelSource,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockReportUiState())
    val uiState: StateFlow<LockReportUiState> = _uiState.asStateFlow()

    fun load(sessionId: Long) {
        viewModelScope.launch {
            val session = lockSessionRepository.getById(sessionId)
            if (session == null) {
                _uiState.value = LockReportUiState(loading = false, error = "找不到这次锁机记录")
                return@launch
            }

            val zone = ZoneId.systemDefault()
            val start = session.windowStartMillis
            val end = session.windowEndMillis

            val usage = usageRepository.windowTotals(start, end)
            val blocked = blockedAttemptRepository.totalsByPackageBetween(start, end)
            val events: List<ZilvEvent> = lockSessionRepository.eventsFor(sessionId)

            val labels = labelResolver.labelsOf(usage.keys + blocked.keys)

            val report = LockReportBuilder.build(
                ruleName = session.ruleName,
                windowStart = Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime(),
                windowEnd = Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime(),
                checkIn = null, // 解锁打卡与窗口的对应关系在 M7 的导出里补；此处保持为 null 而不是伪造
                usageMillis = usage,
                labels = labels,
                blockedTotals = blocked,
                anomalies = events.map { describeEvent(it, zone) }
            )

            // 查看即标记已读（首页「上次回顾」卡片随之消失）
            lockSessionRepository.markReportSeen(sessionId)

            _uiState.value = LockReportUiState(
                report = report,
                ruleName = session.ruleName,
                loading = false
            )
        }
    }

    private fun describeEvent(event: ZilvEvent, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(event.detectedAtMillis)
            .atZone(zone).toLocalTime()
            .let { "%02d:%02d".format(it.hour, it.minute) }
        val detail = event.detail?.let { "（$it）" }.orEmpty()
        return "$time ${event.label}$detail"
    }
}

/** 一次性读取最近拦截记录（供日志页聚合）。 */
private suspend fun BlockedAttemptRepository.observeRecentOnce(limit: Int = 500) =
    observeRecent(limit).first()

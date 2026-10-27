package com.zilv.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.HabitType
import com.zilv.app.intercept.AppLabelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** 某一天里某个习惯的完成情况。 */
data class HabitDayRecord(
    val habitId: Long,
    val habitName: String,
    val habitType: HabitType,
    /** 打卡时刻，格式 HH:mm */
    val times: List<String>,
    val statuses: List<CheckInStatus>,
    /** 该习惯当前是否已归档 */
    val archived: Boolean
) {
    val count: Int get() = times.size
    val checked: Boolean get() = count > 0

    /**
     * 是否至少有一次经过地点验证。
     *
     * 「到点打卡」习惯只有这种记录才算数（见 HabitRepository.requiresLocationProof），
     * 所以明细里必须把两种记录区分开，否则用户会以为打卡成功了但天数没涨。
     */
    val locationVerified: Boolean get() = statuses.any {
        it == CheckInStatus.ON_TIME || it == CheckInStatus.LATE
    }

    val statusLabel: String
        get() = when {
            !checked -> "未打卡"
            statuses.contains(CheckInStatus.ABNORMAL_UNLOCK) -> "异常打卡（未通过地点校验）"
            statuses.contains(CheckInStatus.LATE) -> "迟到打卡"
            locationVerified -> "已打卡"
            else -> "手动勾选"
        }
}

data class DayDetailUiState(
    val date: LocalDate = LocalDate.now(),
    val records: List<HabitDayRecord> = emptyList(),
    val usageEntries: List<UsageEntry> = emptyList(),
    val totalUsageMillis: Long = 0L,
    /**
     * 这一天的留言（用户要求：留言记录可以在日历上看见）。
     *
     * `null` = 那天没写留言；空字符串也按 `null` 处理
     * （空留言不存在，见 [DayNoteRepository.save]）。
     */
    val note: String? = null,
    val loading: Boolean = true
) {
    val checkedCount: Int get() = records.count { it.checked }
    val hasAnything: Boolean get() = records.any { it.checked } || usageEntries.isNotEmpty() || !note.isNullOrBlank()
    val hasNote: Boolean get() = !note.isNullOrBlank()
    val dateLabel: String get() = "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}

/**
 * 某一天的明细（用户要求：点击打卡日历可以观看某一天的记录，
 * 包括打卡记录和各 app 使用时长）。
 *
 * 三部分数据来源不同：
 *  - 打卡记录：`check_ins` 表按天查（[HabitRepository]）
 *  - 使用时长：`app_usage` 按天聚合（[UsageRepository]），包名再解析成应用名
 *  - 留言：`day_notes` 按天查（[DayNoteRepository]）
 */
class DayDetailViewModel(
    private val habitRepository: HabitRepository,
    private val usageRepository: UsageRepository,
    private val dayNoteRepository: DayNoteRepository,
    private val labelResolver: AppLabelSource,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DayDetailUiState())
    val uiState: StateFlow<DayDetailUiState> = _uiState.asStateFlow()

    fun load(epochDay: Long) {
        viewModelScope.launch {
            val date = LocalDate.ofEpochDay(epochDay)
            val active = habitRepository.getActiveHabits()
            val archived = habitRepository.getArchivedHabits()

            // 展示规则：所有在用的习惯都列出（没打卡的显示"未打卡"）；
            // 已归档的习惯只在**当天确实有打卡**时出现，否则列表会被历史习惯淹没。
            val visible = active + archived
            val records = visible.mapNotNull { habit ->
                // DAO 返回的是倒序（最新在前），但"某天明细"里按时间正序更自然。
                // 在这里排序而不是改 DAO —— 倒序对"最近一次打卡"那类查询是对的。
                val checkIns = habitRepository.checkInsOn(habit.id, date)
                    .sortedBy { it.checkedAtMillis }
                val isArchived = archived.any { it.id == habit.id }

                if (checkIns.isEmpty() && isArchived) return@mapNotNull null

                HabitDayRecord(
                    habitId = habit.id,
                    habitName = habit.name,
                    habitType = habit.type,
                    times = checkIns.map { timeOf(it.checkedAtMillis) },
                    statuses = checkIns.map { it.status },
                    archived = isArchived
                )
            }

            val totals = usageRepository.dailyTotals(date)
            val entries = totals.entries
                .filter { it.value > 0L }
                .sortedByDescending { it.value }
                .map { UsageEntry(it.key, labelResolver.labelOf(it.key), it.value) }

            _uiState.value = DayDetailUiState(
                date = date,
                records = records,
                usageEntries = entries,
                totalUsageMillis = entries.sumOf { it.durationMillis },
                note = dayNoteRepository.get(date)?.text?.takeIf { it.isNotBlank() },
                loading = false
            )
        }
    }

    private fun timeOf(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
            .let { "%02d:%02d".format(it.hour, it.minute) }
}

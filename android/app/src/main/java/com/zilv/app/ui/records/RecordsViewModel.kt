package com.zilv.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.records.MonthCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class RecordsUiState(
    val today: LocalDate = LocalDate.now(),
    /** 当前展示月份的完整网格（用户要求：日历显示所有天数） */
    val month: MonthCalendar.Month = MonthCalendar.build(YearMonth.now(), emptyMap(), LocalDate.now()),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalCheckIns: Int = 0,
    /** 最近 7 天的完成率，0..1 */
    val weeklyCompletion: Float = 0f,
    val loading: Boolean = true
) {
    val monthLabel: String get() = "${month.yearMonth.year} 年 ${month.yearMonth.monthValue} 月"

    /** 是否已经在看当前月（用于决定是否允许继续往后翻）。 */
    val atCurrentMonth: Boolean get() = month.yearMonth >= YearMonth.from(today)
}

/**
 * 记录页 ViewModel：月历 + 本周概览（PRD §4.4）。
 *
 * 日历从"GitHub 风格周网格"改成"可点击的月历"：
 * 用户要求显示所有天数，并且能点进某一天看明细。
 *
 * 打卡计数只查一次，翻月时**本地重建网格**而不是重新查库 ——
 * 数据量很小（一天一条聚合），没必要每次都走数据库。
 *
 * v2.3：月历上还要标出"这一天有留言"（用户要求留言能在日历上看见）。
 *
 * v2.5：月历上还要标出"这一天有规划"（用户要求"日历上可以看见当天的规划"）。
 * 与留言**完全同构**：走 Flow 观察，写入后不用手动刷新就能看见。
 */
class RecordsViewModel(
    private val repo: HabitRepository,
    private val dayNoteRepo: DayNoteRepository,
    /**
     * 「明日规划」的数据入口（v2.5）。
     *
     * 只用来读"哪些天有规划"这一个日期集合 —— 月历一次要渲染 42 个格子，
     * 不需要把规划的正文拉进内存（与 `PlanRepository.observePlanDates` 的口径一致）。
     */
    private val planRepo: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordsUiState())
    val uiState: StateFlow<RecordsUiState> = _uiState.asStateFlow()

    /** 最近一次查到的按天打卡条数，翻月时复用。 */
    private var checkInCounts: Map<LocalDate, Int> = emptyMap()

    /**
     * 有留言的日期。
     *
     * 走 Flow 观察而不是每次 refresh 查一次：用户可能是在今天界面写完留言
     * 再切到记录页，中间不经过 `refresh()` 之外的路径；观察能保证
     * "写完立刻能看见"，也让 [rebuildMonth] 天然拿到最新集合。
     */
    private var noteDates: Set<LocalDate> = emptySet()

    /**
     * 有规划的日期（v2.5）。
     *
     * 与 [noteDates] 走同一条路：`plans` 表的写入发生在**别的页面**
     * （「添加」页新建/编辑规划），所以必须观察，不能靠本页 refresh。
     */
    private var planDates: Set<LocalDate> = emptySet()
    private var today: LocalDate = repo.today()
    private var monthCursor: YearMonth = YearMonth.from(today)

    init {
        refresh()
        observeNoteDates()
        observePlanDates()
    }

    private fun observeNoteDates() {
        viewModelScope.launch {
            dayNoteRepo.observeNoteDates().collect { dates ->
                noteDates = dates
                rebuildMonth()
            }
        }
    }

    private fun observePlanDates() {
        viewModelScope.launch {
            planRepo.observePlanDates().collect { dates ->
                planDates = dates
                rebuildMonth()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            today = repo.today()
            val habits = repo.getActiveHabits()
            checkInCounts = repo.dailyCheckInCountsOnce()
            noteDates = dayNoteRepo.noteDates()
            // 规划日期也在这里补一次快照：Flow 的**首次**发射可能早于 refresh，
            // 之后又没有新的写入，那样月历就会停在空集合上。
            // 观察负责"后续变化"，这里负责"进页面时的初始快照"。
            planDates = planRepo.planDates()
            val streaks = habits.map { repo.streakFor(it, today) }

            // 最近 7 天完成率：每天"已打卡习惯数 / 习惯总数"的平均
            val completion = if (habits.isEmpty()) {
                0f
            } else {
                val days = (0L until 7L).map { today.minusDays(it) }
                val ratios = days.map { day ->
                    val done = habits.count { repo.isCheckedToday(it, day) }
                    done.toFloat() / habits.size
                }
                ratios.average().toFloat()
            }

            _uiState.value = RecordsUiState(
                today = today,
                month = buildMonth(),
                currentStreak = streaks.maxOfOrNull { it.current } ?: 0,
                longestStreak = streaks.maxOfOrNull { it.longest } ?: 0,
                totalCheckIns = streaks.sumOf { it.total },
                weeklyCompletion = completion,
                loading = false
            )
        }
    }

    // ---------------------------------------------------------------- 翻月

    fun previousMonth() {
        monthCursor = monthCursor.minusMonths(1)
        rebuildMonth()
    }

    fun nextMonth() {
        monthCursor = monthCursor.plusMonths(1)
        rebuildMonth()
    }

    /** 回到当前月。 */
    fun goToCurrentMonth() {
        monthCursor = YearMonth.from(today)
        rebuildMonth()
    }

    private fun buildMonth(): MonthCalendar.Month =
        MonthCalendar.build(
            yearMonth = monthCursor,
            checkInCounts = checkInCounts,
            today = today,
            noteDates = noteDates,
            planDates = planDates
        )

    private fun rebuildMonth() {
        // rebuildMonth 也可能在 refresh() 之前被 Flow 回调触发（noteDates 先到），
        // 那时 uiState 还是默认值 —— 无条件 copy 一次是安全的，网格会用
        // 最新的 noteDates 重建。
        _uiState.value = _uiState.value.copy(month = buildMonth())
    }
}

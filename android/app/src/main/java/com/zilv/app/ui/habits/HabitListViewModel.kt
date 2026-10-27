package com.zilv.app.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.domain.model.Habit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HabitListUiState(
    val habits: List<Habit> = emptyList(),
    /** 已归档的习惯：单独一组展示，可恢复或彻底删除 */
    val archivedHabits: List<Habit> = emptyList(),
    val showArchived: Boolean = false,
    /** 待确认删除的习惯；null 表示没有待确认的 */
    val pendingDelete: Habit? = null,
    val loading: Boolean = true
) {
    val isEmpty: Boolean get() = habits.isEmpty() && archivedHabits.isEmpty()
    val archivedCount: Int get() = archivedHabits.size
}

/**
 * 习惯管理列表 ViewModel（PRD §4.5 T2.5）。
 *
 * 这里有两个**语义不同**的移除方式，界面上必须区分清楚：
 *
 * | | 归档 | 彻底删除 |
 * | --- | --- | --- |
 * | 数据 | 保留 | 习惯 + 全部打卡记录一并抹掉 |
 * | 可恢复 | 可以（取消归档） | **不可恢复** |
 * | 入口 | 列表行上的「归档」 | 列表行上的「删除」，且需二次确认 |
 *
 * 之所以要补上「取消归档」：原实现只有归档、没有恢复入口，
 * 归档后的习惯从列表消失且**再也看不到**，用户会以为数据丢了。
 */
class HabitListViewModel(
    private val repo: HabitRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HabitListUiState())
    val uiState: StateFlow<HabitListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val archived = repo.getArchivedHabits()
            _uiState.value = _uiState.value.copy(
                habits = repo.getActiveHabits(),
                archivedHabits = archived,
                // 已归档组在为空时自动收起，避免占地方
                showArchived = _uiState.value.showArchived && archived.isNotEmpty(),
                loading = false
            )
        }
    }

    /** 归档：从今日列表隐藏，数据保留。 */
    fun archive(id: Long) {
        viewModelScope.launch {
            repo.archive(id)
            refresh()
        }
    }

    /** 取消归档：让习惯重新回到今日列表。 */
    fun unarchive(id: Long) {
        viewModelScope.launch {
            repo.unarchive(id)
            refresh()
        }
    }

    fun toggleArchivedSection() {
        _uiState.value = _uiState.value.copy(showArchived = !_uiState.value.showArchived)
    }

    // ------------------------------------------------------------ 彻底删除

    /** 第一步：点「删除」——只弹确认框，**不立即删**。 */
    fun requestDelete(id: Long) {
        val target = _uiState.value.habits.firstOrNull { it.id == id }
            ?: _uiState.value.archivedHabits.firstOrNull { it.id == id }
            ?: return
        _uiState.value = _uiState.value.copy(pendingDelete = target)
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    /** 第二步：确认删除。不可恢复，会连同打卡记录一起清掉。 */
    fun confirmDelete() {
        val target = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            repo.deletePermanently(target.id)
            _uiState.value = _uiState.value.copy(pendingDelete = null)
            refresh()
        }
    }
}

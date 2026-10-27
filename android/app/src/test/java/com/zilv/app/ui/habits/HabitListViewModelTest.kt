package com.zilv.app.ui.habits

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.domain.model.Habit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [HabitListViewModel] 的单元测试。
 *
 * 重点是把**归档**与**彻底删除**两种语义区分清楚 ——
 * 用户反馈"没有删除功能"，而补上删除的同时必须保证：
 *  1. 点「删除」只是弹确认框，**数据不能先没了**
 *  2. 确认后才真正删除，并连打卡记录一起清掉
 *  3. 归档是可恢复的，不能和删除混为一谈
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitListViewModelTest {

    private lateinit var habitDao: FakeHabitDao
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var repo: HabitRepository
    private lateinit var viewModel: HabitListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
        habitDao = FakeHabitDao()
        checkInDao = FakeCheckInDao()
        repo = HabitRepository(habitDao, checkInDao, clock)
        viewModel = HabitListViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 造数据。
     *
     * ⚠️ 这里**必须手动 refresh**：`HabitListViewModel` 是快照式的
     * （真实使用中由 `HabitListScreen` 的 `LifecycleResumeEffect` 在进入页面时刷新），
     * 它不会自己感知到外部写库。`setUp` 里构造 ViewModel 时库还是空的，
     * 所以造完数据要让它重新读一次。
     *
     * 这与 `TodayViewModel` 不同 —— 后者是 Flow 驱动的，会自动更新。
     */
    private suspend fun seed(name: String = "早起"): Long {
        val id = repo.save(Habit(name = name))
        viewModel.refresh()
        return id
    }

    // ---------------------------------------------------------------- 归档

    @Test
    fun `归档把习惯移到已归档组且数据仍在`() = runTest {
        val id = seed()
        advanceUntilIdle()

        viewModel.archive(id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.habits).isEmpty()
        assertThat(state.archivedHabits.map { it.name }).containsExactly("早起")
        assertThat(state.archivedCount).isEqualTo(1)
        // 数据没丢：取消归档还能回来
        assertThat(repo.getHabit(id)).isNotNull()
    }

    @Test
    fun `取消归档让习惯回到活跃列表`() = runTest {
        val id = seed()
        viewModel.archive(id)
        advanceUntilIdle()

        viewModel.unarchive(id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.habits.map { it.name }).containsExactly("早起")
        assertThat(state.archivedHabits).isEmpty()
    }

    @Test
    fun `已归档分组可折叠`() = runTest {
        seed()
        viewModel.archive(repo.getActiveHabits().first().id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showArchived).isFalse()
        viewModel.toggleArchivedSection()
        assertThat(viewModel.uiState.value.showArchived).isTrue()
        viewModel.toggleArchivedSection()
        assertThat(viewModel.uiState.value.showArchived).isFalse()
    }

    // ------------------------------------------------------------ 彻底删除

    @Test
    fun `请求删除只弹确认框，数据不能先没了`() = runTest {
        val id = seed()
        advanceUntilIdle()

        viewModel.requestDelete(id)
        advanceUntilIdle()

        // 确认框已弹出
        assertThat(viewModel.uiState.value.pendingDelete?.name).isEqualTo("早起")
        // 关键：此时数据必须**还在**
        assertThat(repo.getHabit(id)).isNotNull()
        assertThat(viewModel.uiState.value.habits).hasSize(1)
    }

    @Test
    fun `取消删除后数据完好且确认框关闭`() = runTest {
        val id = seed()
        advanceUntilIdle()

        viewModel.requestDelete(id)
        viewModel.cancelDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.pendingDelete).isNull()
        assertThat(repo.getHabit(id)).isNotNull()
        assertThat(viewModel.uiState.value.habits).hasSize(1)
    }

    @Test
    fun `确认删除后数据与打卡记录一并消失`() = runTest {
        val id = seed()
        repo.checkIn(id)
        advanceUntilIdle()
        assertThat(repo.isCheckedOn(id, repo.today())).isTrue()

        viewModel.requestDelete(id)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(repo.getHabit(id)).isNull()
        assertThat(repo.checkedDates(id)).isEmpty()
        assertThat(viewModel.uiState.value.habits).isEmpty()
        assertThat(viewModel.uiState.value.pendingDelete).isNull()
    }

    @Test
    fun `没有待确认项时确认删除是空操作`() = runTest {
        seed()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.habits).hasSize(1)

        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.habits).hasSize(1)
    }

    @Test
    fun `可以删除已归档的习惯`() = runTest {
        val id = seed()
        viewModel.archive(id)
        advanceUntilIdle()

        viewModel.requestDelete(id)
        assertThat(viewModel.uiState.value.pendingDelete?.name).isEqualTo("早起")
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(repo.getHabit(id)).isNull()
        assertThat(viewModel.uiState.value.archivedHabits).isEmpty()
    }

    @Test
    fun `删除不存在的 id 不会弹出确认框`() = runTest {
        seed()
        advanceUntilIdle()

        viewModel.requestDelete(9999L)
        assertThat(viewModel.uiState.value.pendingDelete).isNull()
    }

    @Test
    fun `删除一个习惯不影响其它习惯`() = runTest {
        val a = seed("A")
        seed("B")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.habits).hasSize(2)

        viewModel.requestDelete(a)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.habits.map { it.name }).containsExactly("B")
    }

    @Test
    fun `归档组清空后自动收起`() = runTest {
        val id = seed()
        viewModel.archive(id)
        advanceUntilIdle()
        viewModel.toggleArchivedSection()
        assertThat(viewModel.uiState.value.showArchived).isTrue()

        viewModel.unarchive(id)
        advanceUntilIdle()

        // 归档组空了就自动收起，不占地方
        assertThat(viewModel.uiState.value.showArchived).isFalse()
        assertThat(viewModel.uiState.value.archivedCount).isEqualTo(0)
    }
}

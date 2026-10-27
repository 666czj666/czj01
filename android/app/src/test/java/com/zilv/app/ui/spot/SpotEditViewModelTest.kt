package com.zilv.app.ui.spot

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.FakeSpotDao
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot
import com.zilv.app.location.LocationSource
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
 * [SpotEditViewModel] 的单元测试（v2.12）。
 *
 * 用户要求：「**打卡点创建好之后不能再修改**。新建打卡点照旧；
 * 创建完成后进入编辑页只能看，不能改（名称、坐标、半径等字段都只读）」
 *
 * 这个文件钉住的是**第二道**（界面之后的那道）：`SpotEditViewModel` 的每个 setter
 * 在只读态下都必须**直接不生效** —— 不是"改了但存不下去"，因为那会让界面上
 * 显示 200 米、库里还是 100 米。权威的那道在 `SpotRepository.save`
 * （见 `SpotRepositoryTest` 里那组用例）。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)，与其它测试一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotEditViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var spotDao: FakeSpotDao
    private lateinit var repo: SpotRepository
    private lateinit var location: FakeLocationSource

    private class FakeLocationSource(
        var permission: Boolean = true,
        var location: GeoPoint? = GeoPoint(31.0, 121.0, 12f, 0L)
    ) : LocationSource {
        override fun hasPermission(): Boolean = permission
        override suspend fun currentLocation(timeoutMs: Long): GeoPoint? = location
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spotDao = FakeSpotDao()
        repo = SpotRepository(
            spotDao = spotDao,
            ruleCheckInDao = FakeRuleCheckInDao(),
            clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        )
        location = FakeLocationSource()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = SpotEditViewModel(repo, location)

    private suspend fun seedSpot(): Long = repo.save(
        Spot(name = "三教一楼", lat = 30.0, lng = 120.0, radiusM = 100, minAccuracyM = 50f)
    )

    // ------------------------------------------------------------ 新建照旧

    @Test
    fun `新建时不是只读态`() = runTest {
        val vm = newViewModel()
        assertThat(vm.uiState.value.readOnly).isFalse()
    }

    @Test
    fun `新建流程照旧可用 —— 定位、改半径、保存`() = runTest {
        val vm = newViewModel()
        vm.setName("三教一楼")
        vm.useCurrentLocation()
        advanceUntilIdle()
        vm.setRadiusM(200)
        vm.save()
        advanceUntilIdle()

        assertThat(vm.uiState.value.saved).isTrue()
        assertThat(repo.getAllSpots().single().name).isEqualTo("三教一楼")
        assertThat(repo.getAllSpots().single().radiusM).isEqualTo(200)
    }

    // ------------------------------------------------------- 已创建 → 只读

    @Test
    fun `载入已有打卡点后进入只读态`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        assertThat(vm.uiState.value.readOnly).isTrue()
        assertThat(vm.uiState.value.name).isEqualTo("三教一楼")
        assertThat(vm.uiState.value.radiusM).isEqualTo(100)
        // 只读态下"能不能保存"必须为假
        assertThat(vm.uiState.value.canSave).isFalse()
    }

    @Test
    fun `只读态下改名字不生效`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.setName("改个名")

        assertThat(vm.uiState.value.name).isEqualTo("三教一楼")
    }

    @Test
    fun `只读态下改半径与精度门槛不生效`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.setRadiusM(500)
        vm.setMinAccuracyM(80f)

        assertThat(vm.uiState.value.radiusM).isEqualTo(100)
        assertThat(vm.uiState.value.minAccuracyM).isEqualTo(50f)
    }

    @Test
    fun `只读态下改备注与手动坐标不生效`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.setAddress("随便写点什么")
        vm.setCoordinates(1.0, 2.0)

        assertThat(vm.uiState.value.address).isNull()
        assertThat(vm.uiState.value.lat).isEqualTo(30.0)
        assertThat(vm.uiState.value.lng).isEqualTo(120.0)
    }

    @Test
    fun `只读态下用当前位置不生效 —— 坐标是锁定字段`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.useCurrentLocation()
        advanceUntilIdle()

        assertThat(vm.uiState.value.lat).isEqualTo(30.0)
        assertThat(vm.uiState.value.lng).isEqualTo(120.0)
        assertThat(vm.uiState.value.locating).isFalse()
    }

    @Test
    fun `只读态下点保存不会写库，而是给出可读原因`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertThat(vm.uiState.value.saved).isFalse()
        assertThat(vm.uiState.value.error).isEqualTo(SpotEditViewModel.READ_ONLY_HINT)
        // 库里的值一个字都没动
        assertThat(repo.getSpot(id)!!.name).isEqualTo("三教一楼")
        assertThat(repo.getSpot(id)!!.radiusM).isEqualTo(100)
    }

    // ---------------------------------------------------- 删除仍然允许

    @Test
    fun `只读态下删除照旧可用，且成功后请求返回上一页`() = runTest {
        val id = seedSpot()
        val vm = newViewModel()
        vm.load(id)
        advanceUntilIdle()

        vm.delete()
        advanceUntilIdle()

        assertThat(repo.getSpot(id)).isNull()
        assertThat(vm.uiState.value.saved).isTrue()
    }

    @Test
    fun `新建态下点删除是空操作 —— 还没有东西可删`() = runTest {
        val vm = newViewModel()
        vm.delete()
        advanceUntilIdle()

        assertThat(vm.uiState.value.saved).isFalse()
        assertThat(repo.getAllSpots()).isEmpty()
    }
}

package com.zilv.app.ui.spot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.model.Spot
import com.zilv.app.location.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SpotListUiState(
    val spots: List<Spot> = emptyList(),
    val loading: Boolean = true,
    /** 一次性提示（删除被拒之类），界面消费后清空。 */
    val message: String? = null
)

/** 打卡点列表（PRD T5.2）。 */
class SpotListViewModel(
    private val repo: SpotRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotListUiState())
    val uiState: StateFlow<SpotListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                spots = runCatching { repo.getAllSpots() }.getOrDefault(emptyList()),
                loading = false
            )
        }
    }

    /**
     * 删除打卡点。
     *
     * ⚠️ 必须 `runCatching`：仓库层（以及底下的 Room）随时可能抛，
     * 而**未捕获的协程异常会终止 App 进程** —— 这个坑本项目踩过两次。
     * 失败时把原因原样显示出来，而不是静默。
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repo.delete(id) }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        message = "删除失败：${error.message ?: "未知原因"}"
                    )
                }
            refresh()
        }
    }

    fun clearMessage() {
        if (_uiState.value.message != null) {
            _uiState.value = _uiState.value.copy(message = null)
        }
    }
}

data class SpotEditUiState(
    val id: Long = 0L,
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int = Spot.DEFAULT_RADIUS_M,
    val minAccuracyM: Float = Spot.DEFAULT_MIN_ACCURACY_M,
    val address: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val locating: Boolean = false,
    val locationAccuracyM: Float? = null,
    val error: String? = null,
    val saved: Boolean = false
) {
    val hasCoordinates: Boolean get() = lat != null && lng != null

    /**
     * **只读态**：这是一个已经创建好的打卡点（`id != 0`）—— 只能看，不能改。
     *
     * v2.12（用户要求）：「创建完成后进入编辑页只能看，不能改」。
     * 判据就是 id 是否已存在，所以它**不可能**与仓库层的守卫分叉：
     * 那条守卫用的也是同一个 id 判据（见 `SpotRepository.save`）。
     */
    val readOnly: Boolean get() = id != 0L

    /**
     * 能不能保存。
     *
     * ⚠️ 只读态一律为假 —— 界面上那个「保存」按钮因此根本不出现，
     * 但这只是**第一道**：真正的拒绝在 `SpotRepository.save`（可单测）。
     */
    val canSave: Boolean
        get() = !readOnly &&
            name.isNotBlank() &&
            hasCoordinates &&
            radiusM in Spot.RADIUS_RANGE &&
            minAccuracyM > 0f

    fun toSpot(): Spot = Spot(
        id = id,
        name = name.trim(),
        lat = lat ?: 0.0,
        lng = lng ?: 0.0,
        radiusM = radiusM,
        minAccuracyM = minAccuracyM,
        address = address,
        createdAt = createdAt
    )
}

/**
 * 打卡点编辑（PRD T5.3）。
 *
 * **主路径是「用当前位置创建」，完全不依赖地图 SDK** ——
 * 高德/腾讯需要实名认证与 Key 申请，OSMDroid 国内底图质量差，
 * 而"站在打卡点按一下"这个操作根本不需要地图。
 *
 * ## v2.12：已经创建好的打卡点在这个页面**只能看**
 *
 * 用户要求：「打卡点创建好之后不能再修改……创建完成后进入编辑页只能看，不能改
 * （名称、坐标、半径等字段都只读）」。
 *
 * 所以本类里每一个 setter 都在只读态下**直接返回**（不是"改了但存不下去"），
 * 状态因此不会出现"界面上显示 200 米、库里还是 100 米"这种假象。
 * 这一层同样是第二道 —— 权威的那道在 `SpotRepository.save`
 * （它抛 `SpotImmutableException`，`save()` 里的 `runCatching` 把它变成一句人话）。
 */
class SpotEditViewModel(
    private val repo: SpotRepository,
    private val locationProvider: LocationSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotEditUiState())
    val uiState: StateFlow<SpotEditUiState> = _uiState.asStateFlow()

    fun load(spotId: Long) {
        if (spotId == 0L) return
        viewModelScope.launch {
            val spot = repo.getSpot(spotId) ?: return@launch
            _uiState.value = SpotEditUiState(
                id = spot.id,
                name = spot.name,
                lat = spot.lat,
                lng = spot.lng,
                radiusM = spot.radiusM,
                minAccuracyM = spot.minAccuracyM,
                address = spot.address,
                createdAt = spot.createdAt
            )
        }
    }

    fun setName(v: String) {
        if (_uiState.value.readOnly) return
        _uiState.value = _uiState.value.copy(name = v, error = null)
    }

    fun setRadiusM(v: Int) {
        if (_uiState.value.readOnly) return
        _uiState.value = _uiState.value.copy(
            radiusM = v.coerceIn(Spot.MIN_RADIUS_M, Spot.MAX_RADIUS_M),
            error = null
        )
    }

    fun setMinAccuracyM(v: Float) {
        if (_uiState.value.readOnly) return
        _uiState.value = _uiState.value.copy(minAccuracyM = v.coerceAtLeast(1f), error = null)
    }

    fun setAddress(v: String) {
        if (_uiState.value.readOnly) return
        _uiState.value = _uiState.value.copy(address = v.ifBlank { null })
    }

    /** 手动输入坐标（备用路径）。只读态下无效。 */
    fun setCoordinates(lat: Double, lng: Double) {
        if (_uiState.value.readOnly) return
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            _uiState.value = _uiState.value.copy(error = "坐标超出合法范围")
            return
        }
        _uiState.value = _uiState.value.copy(lat = lat, lng = lng, error = null)
    }

    /** 主路径：用当前位置创建打卡点。**只读态下无效**（坐标是锁定字段之一）。 */
    fun useCurrentLocation() {
        if (_uiState.value.readOnly) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(locating = true, error = null)

            if (!locationProvider.hasPermission()) {
                _uiState.value = _uiState.value.copy(
                    locating = false,
                    error = "需要定位权限才能使用当前位置"
                )
                return@launch
            }

            val location = locationProvider.currentLocation()
            if (location == null) {
                _uiState.value = _uiState.value.copy(
                    locating = false,
                    error = "定位失败或超时，请到空旷处重试，或手动输入坐标"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                lat = location.lat,
                lng = location.lng,
                locating = false,
                locationAccuracyM = location.accuracyM,
                error = null
            )
        }
    }

    /**
     * 保存。**只读态下不可能成功** —— 这里改成"只读就报原因"，
     * 真正的拒绝仍然由 `SpotRepository.save` 权威地抛出（可单测）。
     */
    fun save() {
        val s = _uiState.value
        if (s.readOnly) {
            _uiState.value = s.copy(error = READ_ONLY_HINT)
            return
        }
        if (!s.canSave) {
            _uiState.value = s.copy(
                error = when {
                    s.name.isBlank() -> "请填写打卡点名称"
                    !s.hasCoordinates -> "请用当前位置创建，或手动输入坐标"
                    else -> "半径需在 ${Spot.MIN_RADIUS_M}–${Spot.MAX_RADIUS_M} 米之间"
                }
            )
            return
        }
        viewModelScope.launch {
            runCatching { repo.save(s.toSpot()) }
                .onSuccess { _uiState.value = _uiState.value.copy(saved = true, error = null) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(error = it.message ?: "保存失败")
                }
        }
    }

    /**
     * 删除这个打卡点（只读态下**仍然允许**）。
     *
     * 理由见 `SpotRepository.save` 的 KDoc：锁定的是"改"，不是"删"——
     * 建错了的打卡点必须清得掉，否则用户会被它永久绑住。
     */
    fun delete() {
        val id = _uiState.value.id
        if (id == 0L) return
        viewModelScope.launch {
            runCatching { repo.delete(id) }
                .onSuccess { _uiState.value = _uiState.value.copy(saved = true, error = null) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(error = it.message ?: "删除失败")
                }
        }
    }

    /** 供 UI 显示的精度提示。 */
    fun accuracyHint(): String? = _uiState.value.locationAccuracyM?.let {
        "当前定位精度 ${it.roundToInt()} 米"
    }

    companion object {
        /** 只读态下给用户的一句话 —— 必须说清"怎么办"，不能只说"不能改"。 */
        const val READ_ONLY_HINT =
            "打卡点创建后不能修改，这里只能查看。需要调整就删掉它再新建一个。"
    }
}

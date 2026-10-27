package com.zilv.app.ui.allowlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.mapper.AllowApp
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.intercept.SystemPackageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AllowListUiState(
    val builtin: List<AllowApp> = emptyList(),
    val userApps: List<AllowApp> = emptyList(),
    /** 可添加的应用（已安装的可启动应用，去掉已在白名单里的） */
    val candidates: List<SystemPackageDetector.Detected> = emptyList(),
    val loading: Boolean = true,
    /**
     * 当前是否处于锁机态（v2.14）。
     *
     * 为真时本页所有操作按钮**置灰且不可点击**（用户要求："锁机时设置白名单按钮
     * 改成灰色，点击不能有反应"）。仓库层的 `RuleLockedException` 守卫仍然保留 ——
     * 置灰只是界面层，挡不住其它调用路径。
     */
    val locked: Boolean = false,
    val message: String? = null
)

/**
 * 白名单管理（PRD T4.3 / §3.4）。
 *
 * 三类内容：
 *  - **内置强制项**：运行时探测得到，只读、不可移除（不把手机搞坏的底线）
 *  - **用户项**：可增删
 *  - **候选应用**：已安装的可启动应用
 */
class AllowListViewModel(
    private val lockRepo: LockRuleRepository,
    private val detector: SystemPackageDetector
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllowListUiState())
    val uiState: StateFlow<AllowListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // 首次进入时把探测到的内置项写入数据库（幂等）
            val detected = withContext(Dispatchers.IO) {
                detector.detectBuiltins().map { it.packageName to it.label }
            }
            lockRepo.ensureBuiltinAllowList(detected)

            val all = lockRepo.getAllowedApps()
            val builtin = all.filter { it.builtin }
            val user = all.filterNot { it.builtin }

            val existing = all.map { it.packageName }.toSet()
            val candidates = withContext(Dispatchers.IO) {
                detector.installedLaunchableApps().filterNot { it.packageName in existing }
            }

            _uiState.value = AllowListUiState(
                builtin = builtin,
                userApps = user,
                candidates = candidates,
                loading = false,
                locked = isLocked()
            )
        }
    }

    /**
     * 只刷新"是否锁机"（v2.14）。
     *
     * 界面每秒调一次：锁机开始/结束时按钮的置灰状态要跟着变，
     * 而 `refresh()` 会重新扫一遍已安装应用（几十毫秒），不能每秒做。
     */
    fun refreshLockState() {
        viewModelScope.launch {
            val locked = isLocked()
            if (_uiState.value.locked != locked) {
                _uiState.value = _uiState.value.copy(locked = locked)
            }
        }
    }

    /** 读真实的锁机状态；读失败按"没锁机"处理（保持界面可用，安全由仓库守卫保证）。 */
    private suspend fun isLocked(): Boolean =
        runCatching { lockRepo.currentStatus().phase == LockPhase.LOCKED }.getOrDefault(false)

    fun addApp(packageName: String, label: String) {
        viewModelScope.launch {
            runCatching { lockRepo.addUserApp(packageName, label) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "操作失败") }
            _uiState.value = _uiState.value.copy(message = "已加入白名单：$label")
            refresh()
        }
    }

    fun removeApp(packageName: String) {
        viewModelScope.launch {
            val removed = runCatching { lockRepo.removeUserApp(packageName) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "操作失败") }
                .getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                message = if (removed) {
                    "已移出白名单"
                } else {
                    "内置强制项不可移除（这是保证手机可用的安全底线）"
                }
            )
            refresh()
        }
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { lockRepo.setAppEnabled(packageName, enabled) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "操作失败") }
            refresh()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

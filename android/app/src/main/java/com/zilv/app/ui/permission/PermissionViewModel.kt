package com.zilv.app.ui.permission

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.settings.SettingsRepository
import com.zilv.app.domain.permission.PermissionState
import com.zilv.app.domain.permission.PermissionStep
import com.zilv.app.domain.permission.PermissionSummary
import com.zilv.app.permission.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PermissionUiState(
    val summary: PermissionSummary = PermissionSummary.from(emptySet()),
    val loading: Boolean = true
) {
    val ready: Boolean get() = summary.ready
    val progress: Float get() = summary.progress
}

/**
 * 权限向导 / 权限体检（PRD §3.10、§4.1、§4.7）。
 *
 * 检测逻辑在 [PermissionChecker]，就绪判定与进度在 [PermissionSummary]
 * （后者有 13 个单元测试覆盖）。本 ViewModel 只负责把两者接起来。
 */
class PermissionViewModel(
    private val checker: PermissionChecker,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 从系统设置页返回后必须调用，重新检测真实状态。 */
    fun refresh() {
        viewModelScope.launch {
            val manual = settings.manualConfirmedStepsOnce()
            val summary = PermissionSummary(
                PermissionStep.ordered.map { step ->
                    when (step) {
                        in PermissionChecker.MANUAL_STEPS ->
                            PermissionState(step, step in manual)
                        else -> PermissionState(step, checker.isGranted(step))
                    }
                }
            )
            _uiState.value = PermissionUiState(summary = summary, loading = false)
        }
    }

    /** 该步骤的系统设置页跳转；null 表示只能手动确认，或必须由 Activity 就地请求。 */
    fun settingsIntent(step: PermissionStep): Intent? = checker.settingsIntentFor(step)

    /**
     * 应用详情设置页。
     *
     * 定位权限被**永久拒绝**（勾了「不再询问」）时的退化路径 ——
     * 那时候运行时请求已经弹不出框，只能请用户到应用信息页手动打开。
     */
    fun appDetailsIntent(): Intent = checker.appDetailsIntent()

    fun confirmManually(step: PermissionStep) {
        viewModelScope.launch {
            settings.confirmStep(step)
            refresh()
        }
    }

    fun revokeManual(step: PermissionStep) {
        viewModelScope.launch {
            settings.revokeStep(step)
            refresh()
        }
    }
}

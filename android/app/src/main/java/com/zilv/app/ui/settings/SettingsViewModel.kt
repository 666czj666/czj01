package com.zilv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页的锁机状态（v2.14）。
 *
 * ## 它只做一件事
 *
 * 告诉界面"现在是锁机态吗"，用来把「白名单」这个入口**置灰且不可点击**。
 *
 * 用户原话：「锁机时设置白名单按钮改成灰色，点击不能有反应。」
 * 在那之前，点进去操作会抛 `RuleLockedException` 并弹一句提示 ——
 * 用户要的更彻底：**按钮本身就看得出不能点，点了也不发生任何事**。
 *
 * ## 为什么用 [LockRuleRepository.currentStatus]
 *
 * 那是项目里**唯一**的锁机状态来源（无障碍服务、前台服务、首页卡片、
 * 打卡页全都用它）。另造一份（比如读某个缓存布尔）迟早与它不一致 ——
 * 那种不一致的后果是"界面是灰的但实际没锁"或反过来，两种都很难查。
 *
 * ## 状态会变，所以必须能刷新
 *
 * 锁机是按时间窗口自动开始/结束的，界面停在设置页时它也可能变。
 * 因此界面侧每秒调一次 [refreshLockState]（与 `InterceptStatusSection`
 * 每秒重读快照是同一做法），回到本页时也刷一次。
 *
 * ⚠️ 这里**只是界面层**。仓库层的 `RuleLockedException` 守卫**不删** ——
 * 置灰挡不住其它调用路径（快捷方式、未来的自动化、代码里的误用），
 * 守卫才是真正的边界。
 */
class SettingsViewModel(
    private val lockRepo: LockRuleRepository
) : ViewModel() {

    private val _locked = MutableStateFlow(false)

    /** 当前是否处于锁机态（`LockPhase.LOCKED`）。 */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    init {
        refreshLockState()
    }

    /**
     * 重新读一次真实锁机状态。
     *
     * 仓库层会访问数据库（`currentStatus()` 是 suspend），因此一律 `runCatching`：
     * 读失败时按"没锁机"处理（**界面保持可用**），而不是崩掉或把入口永远置灰 ——
     * 真正的拦截安全由仓库守卫与无障碍服务保证，不靠这一处界面状态。
     */
    fun refreshLockState() {
        viewModelScope.launch {
            val locked = runCatching { lockRepo.currentStatus().phase == LockPhase.LOCKED }
                .getOrDefault(false)
            _locked.value = locked
        }
    }
}

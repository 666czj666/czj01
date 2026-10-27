package com.zilv.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.service.LockForegroundService
import com.zilv.app.ui.nav.ZilvNavHost
import com.zilv.app.ui.theme.ZilvTheme
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 唯一 Activity。
 *
 * 两件事：
 *  1. 承载 Compose 导航；支持从遮挡层的「立即打卡」直接跳到打卡页
 *  2. 在 onStart 启动保活服务（Android 12+ 限制后台启动前台服务，
 *     从可见 Activity 启动是被允许的）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val checkInRuleId = intent?.getLongExtra(EXTRA_CHECK_IN_RULE_ID, -1L)?.takeIf { it > 0 }
        val checkInSpotId = intent?.getLongExtra(EXTRA_CHECK_IN_SPOT_ID, -1L)?.takeIf { it > 0 }

        setContent {
            ZilvTheme {
                ZilvNavHost(
                    initialCheckInRuleId = checkInRuleId,
                    initialCheckInSpotId = checkInSpotId
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 有启用的规则才需要常驻；没有规则就不打扰用户
        val koin = GlobalContext.getOrNull() ?: return
        lifecycleScope.launch {
            val repo: LockRuleRepository = koin.get()
            // 用户要求：非锁机时间不显示任何通知。
            // 因此只有当前确实处于锁机或预警态时才启动前台服务；
            // 其余情况只让服务（若在运行）重算并收起通知。
            val phase = repo.currentStatus().phase
            if (phase == LockPhase.LOCKED || phase == LockPhase.WARN) {
                LockForegroundService.start(this@MainActivity)
            } else {
                LockForegroundService.notifyStateChanged(this@MainActivity)
            }
        }
    }

    companion object {
        const val EXTRA_CHECK_IN_RULE_ID = "check_in_rule_id"
        const val EXTRA_CHECK_IN_SPOT_ID = "check_in_spot_id"

        /** 供遮挡层构造"直接去打卡"的 Intent。 */
        fun checkInIntent(
            context: android.content.Context,
            ruleId: Long,
            spotId: Long
        ): Intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_CHECK_IN_RULE_ID, ruleId)
            putExtra(EXTRA_CHECK_IN_SPOT_ID, spotId)
        }
    }
}

package com.zilv.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zilv.app.ui.add.AddScreen
import com.zilv.app.ui.allowlist.AllowListScreen
import com.zilv.app.ui.backup.BackupScreen
import com.zilv.app.ui.checkin.CheckInScreen
import com.zilv.app.ui.habits.HabitEditScreen
import com.zilv.app.ui.habits.HabitListScreen
import com.zilv.app.ui.lock.LockRuleEditScreen
import com.zilv.app.ui.permission.PermissionScreen
import com.zilv.app.ui.plan.PlanEditScreen
import com.zilv.app.ui.quota.QuotaScreen
import com.zilv.app.ui.records.BlockedLogScreen
import com.zilv.app.ui.records.DayDetailScreen
import com.zilv.app.ui.records.LockHistoryScreen
import com.zilv.app.ui.records.LockReportScreen
import com.zilv.app.ui.records.RecordsScreen
import com.zilv.app.ui.records.UsageScreen
import com.zilv.app.ui.settings.SettingsScreen
import com.zilv.app.ui.spot.SpotEditScreen
import com.zilv.app.ui.spot.SpotListScreen
import com.zilv.app.ui.today.TodayScreen
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.zilv.app.ui.theme.Motion

internal object Routes {
    const val TODAY = "today"

    /**
     * 「添加」页（v2.5）。
     *
     * 用户要求"新建名词为 添加 的界面，界面显示已有的习惯和明日规划，点击可进行编辑"，
     * 并把今天界面的加号搬到这里。位置在「今天」与「记录」之间。
     */
    const val ADD = "add"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val HABITS = "habits"
    const val HABIT_EDIT = "habit/{id}"
    /** 锁机设置：**按习惯**，不再是独立的规则列表 */
    const val RULE_EDIT = "rule/{habitId}"
    const val PERMISSIONS = "permissions"
    const val ALLOWLIST = "allowlist"
    const val SPOTS = "spots"
    const val SPOT_EDIT = "spot/{id}"

    /**
     * 「明日规划」新建 / 编辑页（v2.5）。
     *
     * 后面三个查询参数是「添加」页两个弹窗攒出来的**预填值**：
     *  - `name`：弹窗 1 里填的昵称
     *  - `start` / `end`：弹窗 2 里设的起止（当天第几分钟）
     *
     * 用查询参数而不是在目标页再查一次库：这两个值在落库之前根本不存在，
     * 而落库只应该发生在用户点「保存」那一刻（否则取消就会留下空壳规划）。
     */
    const val PLAN_EDIT = "plan/{id}?name={name}&start={start}&end={end}"
    /**
     * 打卡页路由。
     *
     * v2.2 追加可选查询参数 `habitId`：室内定位失败时要能就地把**这条习惯**
     * 的打卡条件改成「无」（见 `CheckInViewModel.useUnconditionalCheckIn`），
     * 所以必须知道这次打卡挂在哪条习惯上。
     *
     * 用查询参数而不是新路径段：`-1` 表示"没有关联习惯"（从锁机规则进来），
     * 已有的两个调用点（首页锁机卡片 / 遮挡层）因此不需要改路径形态。
     */
    const val CHECK_IN = "checkin/{ruleId}/{spotId}?habitId={habitId}"

    /** 不带习惯的打卡页（从锁机规则进入）。 */
    const val CHECK_IN_NO_HABIT = -1L
    const val USAGE = "usage"
    const val BLOCKED_LOG = "blocked_log"
    const val LOCK_HISTORY = "lock_history"
    const val DAY_DETAIL = "day/{epochDay}"
    const val LOCK_REPORT = "lock_report/{sessionId}"
    const val BACKUP = "backup"

    /**
     * 「软件使用时间限时」管理页（v2.7）。
     *
     * 从「添加」页的**独立入口**进入（与"习惯""明日规划"并列的第三个区块），
     * 不挂在任何二级流程下面 —— 额度是配置，不是每天要建的东西。
     */
    const val QUOTAS = "quotas"

    fun habitEdit(id: Long) = "habit/$id"
    fun ruleEdit(habitId: Long) = "rule/$habitId"
    fun spotEdit(id: Long) = "spot/$id"

    /**
     * 规划编辑页。
     *
     * 名称会经 `Uri.encode` 再拼进路由：规划名是用户随手输入的，
     * 可能带空格、`/`、`?`、`#`，不编码的话会被当成路径分隔符或参数边界，
     * 结果是点进去名称变成半截（这类问题在真机上才暴露，所以在这里一次做对）。
     */
    fun planEdit(
        planId: Long,
        name: String? = null,
        startMinute: Int? = null,
        endMinute: Int? = null
    ): String {
        val encoded = android.net.Uri.encode(name.orEmpty())
        return "plan/$planId?name=$encoded" +
            "&start=${startMinute ?: PLAN_NO_TIME}" +
            "&end=${endMinute ?: PLAN_NO_TIME}"
    }

    /** 查询参数里表示"没有这个值"的哨兵（`-1` 不可能是合法的当天分钟数）。 */
    const val PLAN_NO_TIME = -1

    /**
     * savedStateHandle 里放"这次习惯编辑是从「添加」页进来的"的 key。
     *
     * 取值方式是**先读后删**：删掉之后，用户下次从「习惯管理」进同一页时
     * 就回到默认的"受锁机守卫约束"状态，不会被上一次的放行标记粘住。
     *
     * 为什么需要它：用户要求"锁机时间不能控制添加界面"，而锁机守卫在仓库层
     * （`LockRuleRepository.ensureRuleEditable`）。这条标记就是**唯一**一处
     * 把 `allowDuringLock = true` 传下去的入口 —— 一眼能数清哪些路径被放开。
     *
     * （v2.5 的 `KEY_INITIAL_NAME` 已随"加号弹窗去掉名称输入"一并删除：
     * 弹窗不再收集昵称，也就没有东西要传给习惯编辑页了。）
     */
    const val KEY_ALLOW_LOCK_EDITS = "allow_lock_edits"

    /**
     * 打卡页。
     *
     * @param habitId 这次打卡挂在哪条习惯上；默认 [CHECK_IN_NO_HABIT] 表示没有
     *        —— 从习惯进入时必须传，否则室内定位失败时给不出「改用无条件打卡」。
     */
    fun checkIn(ruleId: Long, spotId: Long, habitId: Long = CHECK_IN_NO_HABIT) =
        "checkin/$ruleId/$spotId?habitId=$habitId"

    fun lockReport(sessionId: Long) = "lock_report/$sessionId"
    fun dayDetail(epochDay: Long) = "day/$epochDay"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.TODAY, "今天", Icons.Filled.Today),
    // v2.5：用户要求新增「添加」页，位置放在「今天」与「记录」之间
    Tab(Routes.ADD, "添加", Icons.Filled.Add),
    Tab(Routes.RECORDS, "记录", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)

/**
 * 主导航（PRD §4.1）：底部 3 个 Tab（今天 / 记录 / 设置）。
 * 二级页面进入时隐藏底部栏。
 *
 * @param initialCheckInRuleId / [initialCheckInSpotId] 由遮挡层的「立即打卡」按钮
 *        通过 Intent extra 传入，用于直接跳到打卡页（PRD §4.5）。
 */
@Composable
fun ZilvNavHost(
    initialCheckInRuleId: Long? = null,
    initialCheckInSpotId: Long? = null,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TABS.map { it.route }

    // 从遮挡层进入时直接打开打卡页
    LaunchedEffect(initialCheckInRuleId, initialCheckInSpotId) {
        if (initialCheckInRuleId != null && initialCheckInSpotId != null) {
            navController.navigate(Routes.checkIn(initialCheckInRuleId, initialCheckInSpotId))
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = backStackEntry?.destination
                    TABS.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding),
            // 统一转场：淡入淡出 + 轻微水平位移。
            //
            // 位移刻意只用 1/6 屏宽而不是整屏 —— 底部标签之间切换若是整屏滑动
            // 会像"翻页"，很晕；小幅位移在二级页面又足够表达"进入了下一层"。
            enterTransition = {
                fadeIn(Motion.enterSpec()) +
                    slideInHorizontally(Motion.slideSpec()) { it / 6 }
            },
            exitTransition = {
                fadeOut(Motion.exitSpec()) +
                    slideOutHorizontally(Motion.exitSpec()) { -it / 10 }
            },
            popEnterTransition = {
                fadeIn(Motion.enterSpec()) +
                    slideInHorizontally(Motion.enterSpec()) { -it / 6 }
            },
            popExitTransition = {
                fadeOut(Motion.exitSpec()) +
                    slideOutHorizontally(Motion.slideSpec()) { it / 6 }
            }
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    // v2.5：首页**不再可编辑**，所以这里只传打卡回调。
                    // 加号与编辑入口都在「添加」页。
                    onCheckIn = { ruleId, spotId, habitId ->
                        // habitId 为 null 表示这条规则没挂习惯（旧数据）——
                        // 那时不带这个查询参数，打卡页也就不会给出"改用无条件打卡"
                        val route = if (habitId != null) {
                            Routes.checkIn(ruleId, spotId, habitId)
                        } else {
                            Routes.checkIn(ruleId, spotId)
                        }
                        navController.navigate(route)
                    }
                )
            }
            composable(Routes.ADD) {
                AddScreen(
                    onEditHabit = { habitId ->
                        // 「添加」页这条路径要能穿透锁机守卫（用户要求
                        // "锁机时间不能控制添加界面"）。标记**只**在这里置上，
                        // 而且仓库层还会再判一次"这条习惯的锁此刻有没有生效"——
                        // 正在锁机的那条改不动/删不掉（见 KEY_ALLOW_LOCK_EDITS）。
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set(Routes.KEY_ALLOW_LOCK_EDITS, true)
                        navController.navigate(Routes.habitEdit(habitId))
                    },
                    onEditPlan = { planId, name, startMinute, endMinute ->
                        navController.navigate(
                            Routes.planEdit(planId, name, startMinute, endMinute)
                        )
                    },
                    // 独立入口 ①：「使用时间限时」区块
                    onOpenQuotas = { navController.navigate(Routes.QUOTAS) },
                    // 新建习惯弹窗里"去创建打卡点"
                    onManageSpots = { navController.navigate(Routes.SPOTS) }
                )
            }
            composable(Routes.RECORDS) {
                RecordsScreen(
                    onOpenUsage = { navController.navigate(Routes.USAGE) },
                    onOpenBlockedLog = { navController.navigate(Routes.BLOCKED_LOG) },
                    onOpenHistory = { navController.navigate(Routes.LOCK_HISTORY) },
                    onOpenDay = { epochDay -> navController.navigate(Routes.dayDetail(epochDay)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onManageHabits = { navController.navigate(Routes.HABITS) },
                    onPermissions = { navController.navigate(Routes.PERMISSIONS) },
                    onAllowList = { navController.navigate(Routes.ALLOWLIST) },
                    onManageSpots = { navController.navigate(Routes.SPOTS) },
                    onBackup = { navController.navigate(Routes.BACKUP) },
                )
            }
            composable(Routes.HABITS) {
                HabitListScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Routes.habitEdit(id)) },
                    // 新建已改为弹窗（在 HabitListScreen 内部），这里只提供
                    // 弹窗里"去创建打卡点"的跳转
                    onManageSpots = { navController.navigate(Routes.SPOTS) }
                )
            }
            composable(Routes.HABIT_EDIT) { entry ->
                // 「添加」页那条路径会置上这个标记：**读一次就删**，
                // 避免用户下次从「习惯管理」进同一页时还被当成"添加页来的"
                val allowDuringLock = entry.savedStateHandle
                    .get<Boolean>(Routes.KEY_ALLOW_LOCK_EDITS)
                    ?.also { entry.savedStateHandle.remove<Boolean>(Routes.KEY_ALLOW_LOCK_EDITS) }
                    ?: false
                HabitEditScreen(
                    habitId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() },
                    onManageSpots = { navController.navigate(Routes.SPOTS) },
                    onOpenLockSettings = { habitId ->
                        navController.navigate(Routes.ruleEdit(habitId))
                    },
                    allowDuringLock = allowDuringLock
                )
            }
            composable(Routes.PLAN_EDIT) { entry ->
                val args = entry.arguments
                // -1 是"没有这个值"的哨兵，换回 null 再交给界面
                fun argMinute(key: String): Int? =
                    args?.getString(key)?.toIntOrNull()?.takeIf { it >= 0 }

                PlanEditScreen(
                    planId = args?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() },
                    initialName = args?.getString("name")?.takeIf { it.isNotBlank() },
                    initialStartMinute = argMinute("start"),
                    initialEndMinute = argMinute("end")
                )
            }
            composable(Routes.RULE_EDIT) { entry ->
                LockRuleEditScreen(
                    habitId = entry.arguments?.getString("habitId")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.PERMISSIONS) {
                PermissionScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ALLOWLIST) {
                AllowListScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SPOTS) {
                SpotListScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Routes.spotEdit(id)) },
                    onNew = { navController.navigate(Routes.spotEdit(0L)) }
                )
            }
            composable(Routes.SPOT_EDIT) { entry ->
                SpotEditScreen(
                    spotId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.CHECK_IN) { entry ->
                CheckInScreen(
                    ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull() ?: 0L,
                    spotId = entry.arguments?.getString("spotId")?.toLongOrNull() ?: 0L,
                    // 可选查询参数：没带时是 -1（不是从习惯进来的）
                    habitId = entry.arguments?.getString("habitId")?.toLongOrNull()
                        ?: Routes.CHECK_IN_NO_HABIT,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.DAY_DETAIL) { entry ->
                DayDetailScreen(
                    epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.USAGE) {
                UsageScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.BLOCKED_LOG) {
                BlockedLogScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOCK_HISTORY) {
                LockHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReport = { id -> navController.navigate(Routes.lockReport(id)) }
                )
            }
            composable(Routes.LOCK_REPORT) { entry ->
                LockReportScreen(
                    sessionId = entry.arguments?.getString("sessionId")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.BACKUP) {
                BackupScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.QUOTAS) {
                QuotaScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

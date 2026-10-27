package com.zilv.app.core.di

import android.content.Context
import com.zilv.app.data.local.ZilvDatabase
import com.zilv.app.data.repository.BlockedAttemptRepository
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.LockSessionRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.PlanRuleSource
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.data.settings.SettingsRepository
import com.zilv.app.domain.intercept.TemporaryAllowList
import com.zilv.app.domain.quota.QuotaEnforcementSource
import com.zilv.app.intercept.AppLabelResolver
import com.zilv.app.intercept.AppLabelSource
import com.zilv.app.intercept.OverlayController
import com.zilv.app.intercept.SystemPackageDetector
import com.zilv.app.location.LocationProvider
import com.zilv.app.location.LocationSource
import com.zilv.app.permission.PermissionChecker
import com.zilv.app.service.LockForegroundService
import com.zilv.app.service.AlarmScheduler
import com.zilv.app.ui.add.AddViewModel
import com.zilv.app.ui.allowlist.AllowListViewModel
import com.zilv.app.ui.backup.BackupViewModel
import com.zilv.app.ui.checkin.CheckInViewModel
import com.zilv.app.ui.habits.HabitEditViewModel
import com.zilv.app.ui.habits.HabitListViewModel
import com.zilv.app.ui.lock.LockRuleEditViewModel
import com.zilv.app.ui.permission.PermissionViewModel
import com.zilv.app.ui.plan.PlanEditViewModel
import com.zilv.app.ui.quota.QuotaViewModel
import com.zilv.app.ui.records.BlockedLogViewModel
import com.zilv.app.ui.records.DayDetailViewModel
import com.zilv.app.ui.records.LockHistoryViewModel
import com.zilv.app.ui.records.LockReportViewModel
import com.zilv.app.ui.records.RecordsViewModel
import com.zilv.app.ui.records.UsageViewModel
import com.zilv.app.ui.settings.SettingsViewModel
import com.zilv.app.ui.spot.SpotEditViewModel
import com.zilv.app.ui.spot.SpotListViewModel
import com.zilv.app.ui.today.TodayViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/** 数据库与 DAO。 */
val databaseModule: Module = module {
    single { ZilvDatabase.build(get<Context>()) }
    single { get<ZilvDatabase>().habitDao() }
    single { get<ZilvDatabase>().checkInDao() }
    single { get<ZilvDatabase>().lockRuleDao() }
    single { get<ZilvDatabase>().allowAppDao() }
    single { get<ZilvDatabase>().ruleAllowAppDao() }
    single { get<ZilvDatabase>().blockedAttemptDao() }
    single { get<ZilvDatabase>().spotDao() }
    single { get<ZilvDatabase>().ruleCheckInDao() }
    single { get<ZilvDatabase>().usageDao() }
    single { get<ZilvDatabase>().lockSessionDao() }
    single { get<ZilvDatabase>().eventDao() }
    single { get<ZilvDatabase>().dayNoteDao() }
    single { get<ZilvDatabase>().planDao() }
    single { get<ZilvDatabase>().usageQuotaDao() }
}

/** 时间源。单独成模块，测试时可整体替换。 */
val clockModule: Module = module {
    single<Clock> { Clock.systemDefaultZone() }
}

/** 仓库层。 */
val repositoryModule: Module = module {
    single { HabitRepository(habitDao = get(), checkInDao = get(), clock = get()) }
    // v2.4「明日规划」：只能添加明天的规划，规划时间内锁机（仅到点解锁）
    single { PlanRepository(planDao = get(), clock = get()) }
    // A1 接线：规划规则以 PlanRuleSource 的形式并进锁机仓库。
    // 用 PlanRepository 自己实现的那个接口（而不是另造一份逻辑），
    // 这样"规划 → 锁机规则"的换算全项目只有一处。
    single<PlanRuleSource> { get<PlanRepository>() }
    single {
        LockRuleRepository(
            lockRuleDao = get(),
            allowAppDao = get(),
            ruleAllowAppDao = get(),
            ruleCheckInDao = get(),
            // v2.2：解锁方法＝打卡（任何打卡方法都可以）——
            // 习惯的打卡（check_ins）因此也要参与解锁判定
            checkInDao = get(),
            clock = get(),
            // A1：当天的规划也参与锁机（只能到点解锁），见 PlanRuleSource
            planRuleSource = get()
        )
    }
    single { SpotRepository(spotDao = get(), ruleCheckInDao = get(), clock = get()) }
    single { UsageRepository(dao = get(), clock = get()) }
    // v2.7「软件使用时间限时」：额度配置 + 周期内用量判定。
    // 它自己实现 QuotaEnforcementSource（下一个 single 只是把同一个实例
    // 暴露成那个**窄接口**），于是无障碍服务不需要拖一串仓库进来 ——
    // 与 A1 里 PlanRepository / PlanRuleSource 的接法完全一致。
    single { UsageQuotaRepository(quotaDao = get(), usageRepository = get(), clock = get()) }
    single<QuotaEnforcementSource> { get<UsageQuotaRepository>() }
    single { LockSessionRepository(lockSessionDao = get(), eventDao = get(), clock = get()) }
    single { BlockedAttemptRepository(dao = get(), clock = get()) }
    single { DayNoteRepository(dayNoteDao = get(), clock = get()) }
    single { SettingsRepository(get<Context>()) }
}

/** Android 平台相关的服务。 */
val platformModule: Module = module {
    single { AlarmScheduler(get<Context>()) }
    single { PermissionChecker(context = get<Context>()) }
    single { OverlayController(get<Context>()) }
    // 紧急放行的运行时白名单：全局单例，供无障碍服务与 UI 共用
    single { TemporaryAllowList() }
    single { SystemPackageDetector(get<Context>()) }
    single<LocationSource> { LocationProvider(get<Context>()) }
    single<AppLabelSource> { AppLabelResolver(get<Context>()) }
}

/** ViewModel 层。 */
val viewModelModule: Module = module {
    // v2.5：首页要显示"今天生效的规划"，所以多一个 PlanRepository
    // v2.12：首页还要能**即时锁机** —— 那种"现在就开始锁"的动作必须让
    // 前台服务立刻重算并挂上「🔒 锁机中」的通知（与打卡解锁那条路径同一手法），
    // 所以多一个回调；它用 startForegroundService 真正把服务拉起来。
    viewModel {
        val ctx: Context = get()
        TodayViewModel(get(), get(), get(), get()) {
            LockForegroundService.start(ctx)
        }
    }
    // v2.5：记录页要在月历上标出"有规划的那天"
    viewModel { RecordsViewModel(get(), get(), get()) }
    viewModel { HabitEditViewModel(get(), get(), get()) }
    viewModel { HabitListViewModel(get()) }
    // v2.5：「添加」页（新建入口 + 编辑入口）
    // v2.7：这一页还要显示「使用限时」有几个额度，所以多一个额度仓库
    viewModel { AddViewModel(get(), get(), get()) }
    // v2.5：「明日规划」新建 / 编辑
    viewModel { PlanEditViewModel(get()) }
    // v2.7：「软件使用时间限时」管理页
    viewModel { QuotaViewModel(get(), get()) }
    viewModel { LockRuleEditViewModel(get(), get()) }
    viewModel { PermissionViewModel(get(), get()) }
    // v2.14：锁机期间把设置页的「白名单」入口置灰（按钮变灰 + 点击无反应）。
    // 只注入一个仓库：状态来源必须是既有的 LockRuleRepository.currentStatus()。
    viewModel { SettingsViewModel(get()) }
    viewModel { AllowListViewModel(get(), get()) }
    viewModel {
        val ctx: Context = get()
        // 打卡成功后必须让前台服务重算 —— 否则解锁了通知还挂着"锁机中"
        CheckInViewModel(get(), get(), get(), get(), get()) {
            LockForegroundService.notifyStateChanged(ctx)
        }
    }
    viewModel { SpotListViewModel(get()) }
    viewModel { SpotEditViewModel(get(), get()) }

    // M6：记录与报告
    viewModel { UsageViewModel(get(), get(), get()) }
    viewModel { BlockedLogViewModel(get(), get()) }
    viewModel { LockHistoryViewModel(get()) }
    viewModel { LockReportViewModel(get(), get(), get(), get(), get()) }
    viewModel { DayDetailViewModel(get(), get(), get(), get()) }

    // M7：备份
    // v2.5：规划也进备份，所以多一个 PlanRepository
    // v2.7：额度也进备份，再一个 UsageQuotaRepository
    viewModel { BackupViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

val appModules: List<Module> = listOf(
    databaseModule,
    clockModule,
    repositoryModule,
    platformModule,
    viewModelModule
)

/** 供 Koin 启动时调用。 */
fun initKoin(context: Context, extra: List<Module> = emptyList()) =
    org.koin.core.context.startKoin {
        androidContext(context)
        modules(appModules + extra)
    }

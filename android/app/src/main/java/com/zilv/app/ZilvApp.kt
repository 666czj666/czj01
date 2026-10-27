package com.zilv.app

import android.app.Application
import com.zilv.app.core.di.appModules
import com.zilv.app.service.LockSelfCheckWorker
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用入口。
 *
 * 启动时只做两件不依赖界面的事：
 *  1. 初始化 Koin（数据库与仓库都依赖 Context）
 *  2. 幂等注册定期自检任务（保证闹钟链路在进程重启后能恢复）
 *
 * 前台服务的启动放在 [MainActivity]，因为 Android 12+ 限制后台启动前台服务，
 * 而从可见的 Activity 启动是被允许的。
 */
class ZilvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ZilvApp)
            modules(appModules)
        }
        LockSelfCheckWorker.ensureScheduled(this)
    }
}

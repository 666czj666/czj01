package com.zilv.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zilv.app.MainActivity
import com.zilv.app.R
import com.zilv.app.intercept.InterceptDiagnostics
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.LockSessionRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.WindowScheduler
import com.zilv.app.domain.lock.WindowScheduler.LockStatus
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.format.DateTimeFormatter

/**
 * 保活层（PRD §3.3 L2）。
 *
 * 职责：
 *  1. 常驻前台服务，避免被系统回收导致拦截失效
 *  2. 维护常驻通知，显示当前锁机状态
 *  3. 每次状态变化后重算并注册下一个精确闹钟
 *
 * M4 会把遮挡层与无障碍服务接进来；M6 会把使用时长接到这条通知上
 * （用 `setUsesChronometer`，不做定时 notify —— 见 PRD §3.7）。
 */
class LockForegroundService : Service(), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val alarmScheduler: AlarmScheduler by inject()
    private val usageRepository: UsageRepository by inject()
    private val lockSessionRepository: LockSessionRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在用秒表计时的包名（null 表示空闲） */
    @Volatile
    private var chronometerPackage: String? = null

    @Volatile
    private var chronometerStartedAt: Long = 0L

    /** 当前是否处于前台服务态（决定通知是否可见）。 */
    @Volatile
    private var foregroundActive = false

    /** 最近一次算出的"是否需要显示通知"。空闲时通知必须消失。 */
    @Volatile
    private var lastKnownLocked = false

    /**
     * 最近一次真正投递出去的**通知内容**（标题 + 正文）。
     *
     * 只用来做"内容没变就不要再 notify 一次"的去重（见 [notifyIfChanged]）。
     * 它与 [lastKnownLocked] 是两件事：前者管"文案有没有变"，后者管"通知该不该在"。
     */
    @Volatile
    private var lastNotifiedTitle: String? = null

    @Volatile
    private var lastNotifiedText: String? = null

    /**
     * 锁机期间的**每分钟刷新**任务（v2.11）。
     *
     * 用户要求："锁机中那条通知里的『还有 X 分钟』不会自己走，要每分钟刷新一次。"
     *
     * 它只在 [LockPhase.LOCKED] 期间存在（见 [LockNotificationTicker]）：
     * 一旦阶段离开 LOCKED，任务里的 `first { ... }` 会立刻拿到新阶段并返回，
     * 于是这个 Job **自己结束**，不会在解锁后继续空转耗电。
     */
    /**
     * 保护 [phaseFlow] 与 [tickerJob] 的锁。
     *
     * 这两个字段会被不同线程碰：`evaluate()` 可能跑在 IO、ticker 自己跑在
     * [scope] 的调度器上、无障碍服务发来的 `ACTION_FOREGROUND_CHANGED` 又是另一条。
     * 没有这把锁就可能**同时开出两个 ticker**（每分钟推两条通知）。
     */
    private val tickerLock = Any()

    /** 当前阶段。ticker 靠它决定"该不该继续走"。 */
    private val phaseFlow = MutableStateFlow<LockPhase?>(null)

    private var tickerJob: Job? = null

    /** 今日累计时长（内存缓存，重启后由仓库重新加载） */
    private val todayTotalMillis = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ **只有"被真正拉起"的那条路径才允许进入前台。**
        //
        // 原先这里无条件调 startForegroundCompat，于是每次启动都会先弹一条
        // "正在检查"再收起来 —— 而且 ACTION_FOREGROUND_CHANGED 由无障碍服务
        // 在**每次切换应用**时发出，非锁机时间也在反复闪通知。
        // 这正是用户反馈"非锁机时间还有通知"的来源。
        //
        // 下面两个 action 走的是 startService（只有服务已在运行时才送得到），
        // 因此不需要、也不应该重新进入前台。
        when (intent?.action) {
            ACTION_FOREGROUND_CHANGED -> {
                handleForegroundChanged(intent.getStringExtra(EXTRA_FOREGROUND_PACKAGE))
                return START_STICKY
            }

            ACTION_STATE_CHANGED -> {
                // 锁机状态可能变了（例如刚打卡成功）——重算并按需收起通知
                scope.launch { evaluate() }
                return START_STICKY
            }
        }

        // 走到这里说明是被 startForegroundService 拉起的。
        // 系统要求必须尽快 startForeground，否则 ANR；随后由 evaluate()
        // 决定是否收起（用户要求：非锁机时间不显示任何通知）。
        startForegroundCompat(buildNotification("正在检查", "确认锁机计划中"))
        scope.launch { evaluate() }
        return START_STICKY
    }

    /**
     * 前台应用变化时的通知处理（PRD §3.7）。
     *
     * ⚠️ **不要用定时 `notify()` 刷新文本** —— 系统对每包的发通知速率有硬上限
     * （默认 5 条/秒，且官方注释明确"包含更新"）。
     *
     * 正解是 `setUsesChronometer(true)`：由**系统自己**显示"自 when 起经过的时间"，
     * 零额外 notify 调用、不闪烁、不占配额。
     */
    private fun handleForegroundChanged(packageName: String?) {
        // 非锁机时间不显示任何通知（用户要求），时长秒表也一并停用
        if (!lastKnownLocked) return
        val tracked = packageName != null && packageName in TRACKED_PACKAGES
        if (tracked) {
            // 进入被跟踪应用：开启秒表，when = 进入时刻，系统自动走秒
            markSessionStart(packageName!!)
        } else if (chronometerPackage != null) {
            // 离开：先结算这一次的时长，再切回静态累计文本
            settleChronometerSession()
        }
    }

    private fun markSessionStart(packageName: String) {
        if (chronometerPackage == packageName) return // 已在计时，忽略（去重）
        if (chronometerPackage != null) settleChronometerSession()
        chronometerPackage = packageName
        chronometerStartedAt = System.currentTimeMillis()
        // ⚠️ 秒表通知与文字通知共用同一个 NOTIFICATION_ID，但**不是**同一份内容
        // （一条 `setUsesChronometer`、一条是静态文本）。把去重缓存清掉，
        // 这样会话结算之后 `evaluate()` 推的静态文本一定不会被误判成"没变"而丢掉。
        forgetNotifiedContent()
        notify(buildChronometerNotification(packageName, chronometerStartedAt))
    }

    private fun settleChronometerSession() {
        val pkg = chronometerPackage ?: return
        val startedAt = chronometerStartedAt
        chronometerPackage = null
        chronometerStartedAt = 0L

        if (startedAt > 0L) {
            val duration = System.currentTimeMillis() - startedAt
            scope.launch {
                runCatching {
                    usageRepository.recordSegment(pkg, startedAt, startedAt + duration)
                    todayTotalMillis[pkg] = (todayTotalMillis[pkg] ?: 0L) + duration
                }.onFailure { Log.e(TAG, "结算计时会话失败", it) }
                // 走带缓存的版本：既把静态文本推上去，也让缓存与之一致，
                // 于是紧接着的一次 `evaluate()` 不会重复推同一条。
                notifyIfChanged("自律 · 空闲", staticUsageText())
            }
        }
    }

    private fun staticUsageText(): String {
        val pkg = DEFAULT_TRACKED_PACKAGE
        val ms = todayTotalMillis[pkg] ?: 0L
        return if (ms <= 0L) {
            "监控锁机计划中"
        } else {
            "${labelOf(pkg)} · 今日 ${formatMinutes(ms)}"
        }
    }

    private fun buildChronometerNotification(packageName: String, startedAt: Long): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(labelOf(packageName))
            .setContentText("正在使用 · 本次已用")
            .setContentIntent(tapIntent)
            // 系统自动刷新的秒表：不需要我们定时 notify
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun labelOf(packageName: String): String = when (packageName) {
        "com.tencent.mm" -> "微信"
        "com.eg.android.AlipayGphone" -> "支付宝"
        else -> packageName
    }

    private fun formatMinutes(ms: Long): String {
        val totalMinutes = ms / 60_000L
        return if (totalMinutes < 60) "${totalMinutes} 分" else "${totalMinutes / 60} 小时 ${totalMinutes % 60} 分"
    }

    /** 重算当前状态 → 维护会话生命周期 → 更新通知 → 注册下一个闹钟。 */
    private suspend fun evaluate() {
        runCatching {
            val rules = lockRuleRepository.getEnabledRules()
            val status = lockRuleRepository.currentStatus()

            // ---- 锁机窗口实例的生命周期（M6：报告页的数据源）----
            val now = System.currentTimeMillis()
            status.instance?.let { inst ->
                val startMillis = inst.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMillis = inst.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val session = lockSessionRepository.ensureSession(
                    ruleId = inst.rule.id,
                    ruleName = inst.rule.name,
                    windowStartMillis = startMillis,
                    windowEndMillis = endMillis
                )
                if (status.phase == LockPhase.UNLOCKED &&
                    session.state == LockSessionRepository.STATE_LOCKED
                ) {
                    lockSessionRepository.markUnlocked(session.id, reasonFor(status))
                }
            }

            // 已过结束时刻但仍标记为锁机的窗口 -> 置为已结束
            lockSessionRepository.recent(50)
                .filter { it.state == LockSessionRepository.STATE_LOCKED && it.windowEndMillis <= now }
                .forEach { lockSessionRepository.markExpired(it.id) }

            val title = when (status.phase) {
                LockPhase.LOCKED -> "🔒 锁机中 · ${status.rule?.name ?: ""}"
                LockPhase.WARN -> "⏳ 即将锁机 · ${status.rule?.name ?: ""}"
                LockPhase.CHECKIN_DUE -> "📌 该打卡了 · ${status.rule?.name ?: ""}"
                LockPhase.UNLOCKED -> "✅ 已解锁 · ${status.rule?.name ?: ""}"
                LockPhase.IDLE -> "自律 · 空闲"
            }
            val text = when (status.phase) {
                LockPhase.LOCKED -> "还有 ${status.minutesUntilChange} 分钟"
                LockPhase.WARN -> "还有 ${status.minutesUntilChange} 分钟开始"
                LockPhase.CHECKIN_DUE -> "打卡时间还剩 ${status.minutesUntilChange} 分钟"
                else -> status.instance?.let { "下一个：${it.rule.name} ${it.start.format(HHMM)}" }
                    ?: "暂无锁机计划"
            }
            updateNotificationForPhase(status.phase, title, text)
            syncNotificationTicker(status.phase)

            alarmScheduler.reschedule(rules)
        }.onFailure { Log.e(TAG, "状态重算失败", it) }
    }

    /**
     * 按当前阶段决定通知的可见性。
     *
     * **用户要求：非锁机时间不显示任何通知，只有锁机时才显示。**
     *
     * 空闲时不仅移除通知，还会 `stopSelf()` —— 没有通知就没有留在前台的
     * 正当理由，也就不该白占系统资源。真正的到点触发靠精确闹钟
     * （AlarmManager 能在进程被杀后重新拉起应用），所以退出前台不影响锁机。
     */
    private fun updateNotificationForPhase(phase: LockPhase, title: String, text: String) {
        lastKnownLocked = phase == LockPhase.LOCKED || phase == LockPhase.WARN
        if (lastKnownLocked) {
            if (foregroundActive) notifyIfChanged(title, text) else startForeground(title, text)
        } else {
            hideForeground()
        }
    }

    /**
     * 锁机期间每分钟重算一次 —— 由 [LockNotificationTicker] 驱动（v2.11）。
     *
     * ## 为什么必须复用 [evaluate]
     *
     * 用户要求"建议复用既有的状态重算入口，而不是另写一套文案生成"。
     * 这同时也是**唯一正确**的做法：通知文案、会话生命周期、下一个闹钟
     * 全都由 [evaluate] 一处决定。ticker 只负责"到点戳一下"，
     * 于是它不可能和状态重算打架（重复通知 / 文案分叉这类历史坑）。
     *
     * ## 为什么 ticker 改变不了通知的可见性
     *
     * [LockNotificationTicker.intervalMillis] 只对 [LockPhase.LOCKED] 返回非 null，
     * 而 [evaluate] 对一个仍然 LOCKED 的阶段算出的必然还是"通知该在"，
     * 走的还是 [notifyIfChanged]（**不是** `startForegroundCompat`）——
     * 这正是历史上"切应用时通知闪烁"的那条路径，ticker 一个字节都碰不到它。
     */
    private fun syncNotificationTicker(phase: LockPhase) {
        synchronized(tickerLock) {
            // 顺序很重要：先更新阶段，再决定要不要开 ———
            // 正在跑的 ticker 会在 delay 返回后读到最新的阶段，从而自行结束。
            phaseFlow.value = phase
            if (tickerJob?.isActive == true) return
            tickerJob = LockNotificationTicker.startIfTicking(
                phases = phaseFlow,
                scope = scope,
                onTick = {
                    // 空转防护：解锁后 ticker 已被取消，但"取消"与"正在执行 onTick"
                    // 之间有一个极窄的窗口 —— 这里再确认一次阶段，
                    // 避免多推一条过时通知。
                    if (LockNotificationTicker.intervalMillis(phaseFlow.value) != null) {
                        // ⚠️ **tick 里必须自己兜异常**：evaluate() 内部本来就有
                        // runCatching，这里再包一层是刻意的 —— 未捕获的异常会
                        // 让 ticker 这个子协程直接结束，于是通知从此再也不刷新，
                        // 而用户完全看不出来（症状只是"倒计时卡住"）。
                        // 兜住之后下一分钟照常重试，最坏情况只是慢一分钟。
                        runCatching { evaluate() }
                            .onFailure { Log.e(TAG, "定时刷新通知失败", it) }
                    }
                }
            )
        }
    }

    /** 进入前台并显示这条通知 —— 只在"还没在前台态"时走。 */
    private fun startForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        lastNotifiedTitle = title
        lastNotifiedText = text
        startForegroundCompat(notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundActive = true
        } catch (t: Throwable) {
            // Android 14 起从 BOOT_COMPLETED 启动某些前台服务类型会被拒绝。
            // 优雅降级：服务起不来不影响闹钟继续工作。
            Log.e(TAG, "startForeground 失败（可能是后台启动限制）", t)
            InterceptDiagnostics.onError("前台服务被系统拒绝：${t.message}")
            foregroundActive = false
        }
    }

    /** 收起通知并退出前台态。 */
    private fun hideForeground() {
        // 去重缓存跟着通知一起清掉：下次再进前台时必须重新投递，
        // 否则会和"上一轮锁机遗留的文案"撞上而被误判成"没变"。
        forgetNotifiedContent()
        if (!foregroundActive) {
            stopSelf()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }.onFailure { Log.w(TAG, "移除通知失败", it) }
        foregroundActive = false
        stopSelf()
    }
    /**
     * 打卡解锁 vs 到时自动解除 —— 用于报告页显示解锁原因。
     *
     * v2.2：解锁方式不再由规则上的 `unlockMode` 决定（那玩意儿已废弃，
     * 而且旧代码在 TIME_ONLY 下总报"到时解除"，与"打卡即解锁"的新行为矛盾）。
     * 现在直接问调度器：这次窗口内最近一次打卡算不算数？
     */
    private suspend fun reasonFor(status: LockStatus): String {
        val instance = status.instance ?: return LockSessionRepository.REASON_TIME
        val latest = runCatching { lockRuleRepository.latestCheckInAt(instance.rule) }.getOrNull()
            ?: return LockSessionRepository.REASON_TIME
        return if (WindowScheduler.isValidCheckIn(instance, latest)) {
            LockSessionRepository.REASON_CHECKIN
        } else {
            LockSessionRepository.REASON_TIME
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "锁机状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示当前锁机状态与倒计时"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    /**
     * 只在**文案真的变了**的时候才更新通知（v2.11）。
     *
     * 每分钟刷新会把这个方法叫醒一次；同一分钟里重复走到的路径
     * （状态重算 + 切前台 + 闹钟到点）非常多，无条件 `notify`
     * 会白推好几条一模一样的通知：既浪费系统的每条包发通知配额
     * （默认 5 条/秒，官方注释明确"包含更新"），也会让通知栏闪一下。
     *
     * 所以这里以"上一次投递出去的标题+正文"为准做去重 ——
     * **文案没变就一个字节都不发**。
     */
    private fun notifyIfChanged(title: String, text: String) {
        if (title == lastNotifiedTitle && text == lastNotifiedText) return
        lastNotifiedTitle = title
        lastNotifiedText = text
        notify(title, text)
    }

    /**
     * 把"通知内容"这件事忘掉。
     *
     * 用于秒表通知与静态文本通知**互相切换**的时刻：系统里那条通知的内容
     * 已经不是我们缓存的那一份了，缓存继续留着只会让下一次更新被误判成
     * "没变"而丢掉（症状是"离开微信后通知停在秒表上"）。
     */
    private fun forgetNotifiedContent() {
        lastNotifiedTitle = null
        lastNotifiedText = null
    }

    /** 直接投递一条已构造好的通知（秒表通知走这条路）。 */
    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    override fun onDestroy() {
        // 先让 ticker 走完它自己的退出流程（stage 一变就返回），再撤掉整个作用域。
        // 顺序反了也没关系（scope.cancel 是幂等的），这里只是让日志更干净。
        synchronized(tickerLock) {
            tickerJob?.cancel()
            tickerJob = null
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockForegroundService"
        private const val CHANNEL_ID = "zilv_lock_status"
        private const val NOTIFICATION_ID = 2001
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        const val ACTION_FOREGROUND_CHANGED = "com.zilv.app.action.FOREGROUND_CHANGED"
        const val ACTION_STATE_CHANGED = "com.zilv.app.action.STATE_CHANGED"
        const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"

        /** 需要在通知上显示使用时长的应用（PRD §3.7：微信） */
        val TRACKED_PACKAGES = setOf("com.tencent.mm")
        private const val DEFAULT_TRACKED_PACKAGE = "com.tencent.mm"

        /** 启动服务。已在前台时重复调用是安全的。 */
        fun start(context: Context) {
            val intent = Intent(context, LockForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "启动前台服务失败", it) }
        }

        /**
         * 告知服务前台应用变了。
         *
         * 服务未运行时这个调用是空操作 —— 不为了更新通知而把服务拉起来。
         */
        fun notifyForegroundChanged(context: Context, packageName: String) {
            val intent = Intent(context, LockForegroundService::class.java).apply {
                action = ACTION_FOREGROUND_CHANGED
                putExtra(EXTRA_FOREGROUND_PACKAGE, packageName)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.d(TAG, "服务未运行，跳过时长通知更新") }
        }

        /**
         * 告知服务"锁机状态可能变了"，让它重算并**按需收起通知**。
         *
         * 打卡成功后必须调用它 —— 否则解锁了但通知还挂着"锁机中"。
         *
         * 用 startService（不是 startForegroundService）：服务未运行时这个调用
         * 会因后台启动限制抛异常，被 runCatching 吞掉，结果是"什么都没发生"，
         * 正是我们想要的 —— 此时本来就没有通知需要收。
         */
        fun notifyStateChanged(context: Context) {
            val intent = Intent(context, LockForegroundService::class.java).apply {
                action = ACTION_STATE_CHANGED
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.d(TAG, "服务未运行，无需收起通知") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockForegroundService::class.java))
        }
    }
}

/**
 * 锁机通知的**每分钟刷新**规则（v2.11）。
 *
 * ## 为什么做成独立的纯对象
 *
 * 用户要求（第 1 条）："锁机期间每分钟重算并更新一次通知文案，不在锁机态时要停掉
 * 这个 ticker，不能一直空转耗电"。
 *
 * 这就要求"什么时候该走、什么时候该停"是**可以被单测逐条钉死**的东西 ——
 * 而不是散在 Service 里的几个 `if`。所以判定（[intervalMillis]）与节奏控制
 * （[runCollecting]）都在这里，它们是**纯逻辑**：不碰 Android、不碰通知、
 * 不认识仓库，单测里直接给阶段流喂值即可（见 `LockNotificationTickerTest`）。
 *
 * ## 两个阶段的取舍：为什么 WARN 不刷新
 *
 * 用户点名的是**锁机中**那条「🔒 锁机中 · 规则名 / 还有 X 分钟」。
 * [LockPhase.WARN]（即将锁机）也有倒计时，但：
 *  - 预警只有 15 分钟，期间至少有"开始锁机"这个闹钟会到点重算
 *  - 多一个常驻 ticker 就多一份耗电，而预警态本身很短
 *
 * 所以只给 [LockPhase.LOCKED] 开 ticker。这是一个**可以扩的点**：
 * 将来若要求预警也走，只需把 WARN 加进 [tickingPhases]，其余逻辑一行不用改。
 *
 * ## 停止路径（每一条都落回"阶段不再是 LOCKED"）
 *
 * ticker 不是靠一堆 `stopTicker()` 调用点来收尾的 —— 它**只看当前阶段**：
 *  - **打卡成功 / 异常解锁** → `CheckInViewModel` 调
 *    `notifyStateChanged` → `ACTION_STATE_CHANGED` → `evaluate()` → 阶段变
 *  - **解锁**（到点自动解除、规则被停用/删除、规划窗口结束）→ 同上，
 *    任何一次 `evaluate()` 都会把新阶段写进 [phases]
 *  - **切应用** → `ACTION_FOREGROUND_CHANGED` 也会走 `evaluate()`。
 *    ⚠️ 这条路**刻意只走 [notifyIfChanged]**，不碰 `startForegroundCompat` ——
 *    历史上"切应用导致通知闪烁"就是那里踩的
 *  - **服务被销毁** → `onDestroy` 里 `tickerJob.cancel()` + `scope.cancel()`
 *
 * 于是"解锁后还在后台空转"这件事在结构上不可能发生：阶段一变，
 * [runCollecting] 里那个 `delay` 会立刻被取消并 `return`。
 */
object LockNotificationTicker {

    /** 只有锁机中才需要每分钟刷新（见上面 KDoc 的取舍说明）。 */
    val tickingPhases: Set<LockPhase> = setOf(LockPhase.LOCKED)

    /** 刷新间隔：一分钟。用户要求"每分钟刷新一次"。 */
    const val TICK_MILLIS = 60_000L

    /**
     * 这个阶段要多久刷一次通知；**null 表示不该有 ticker**（必须停掉）。
     *
     * 做成"返回间隔"而不是"返回布尔"有两个好处：
     *  - "该不该走"与"走多快"是同一个决定，不可能分叉
     *  - 单测可以直接断言"解锁后返回 null"（= 不空转）
     */
    fun intervalMillis(phase: LockPhase?): Long? =
        if (phase != null && phase in tickingPhases) TICK_MILLIS else null

    /**
     * 节奏控制：等到阶段进入 [tickingPhases] 后，每 [TICK_MILLIS] 戳一次 [onTick]；
     * 阶段一离开就自己结束。
     *
     * ## 为什么不先 tick 再 delay
     *
     * 进入锁机态那一次的通知由 `evaluate()` 自己发（闹钟到点 → `AlarmReceiver`
     * → `LockForegroundService.start`），ticker 再立刻戳一下只会推一条**完全一样**
     * 的通知 —— 那正是"通知闪烁"。所以这里先 `delay`，等下一分钟再重算。
     *
     * ## 返回值就是"该不该有 ticker"的答案
     *
     * 阶段还不是 LOCKED 时，这个 Job 在 `filter` 那里就结束了，
     * 于是 `job.isActive == false` —— 调用方据此知道"没有 ticker 在跑"。
     * 这样做而不是自己维护一个 `Boolean`：**只有一处真相**，
     * 不可能出现"标志说在跑，其实早就结束了"。
     *
     * @param phases 当前阶段流；null = 还没算过
     * @param scope 跑 ticker 的作用域（Service 的 scope；销毁时一起取消）
     * @return 这次启动的 Job；它可能已经因为"阶段不是 LOCKED"而结束
     */
    fun startIfTicking(
        phases: StateFlow<LockPhase?>,
        scope: CoroutineScope,
        onTick: suspend () -> Unit
    ): Job = scope.launch {
        // ① 等一个"该刷新"的阶段。不是 LOCKED 就一直等，什么都不做。
        phases.first { intervalMillis(it) != null }
        // ② 每 TICK_MILLIS 戳一次；阶段一变，delay 立即被取消，循环退出。
        while (currentCoroutineContext().isActive) {
            delay(TICK_MILLIS)
            if (intervalMillis(phases.value) == null) return@launch
            onTick()
        }
    }
}

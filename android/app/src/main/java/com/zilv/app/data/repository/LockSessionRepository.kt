package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.EventDao
import com.zilv.app.data.local.dao.LockSessionDao
import com.zilv.app.data.local.entity.EventEntity
import com.zilv.app.data.local.entity.LockSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * 事件类型（PRD §3.8「异常」区块 / §6.6）。
 *
 * 注意：本 App **不做对抗加固**（PRD §0 已确认），这些事件只用于「记账 / 自省」，
 * 不具备强制力。
 */
enum class EventType {
    /** 无障碍服务被关闭 */
    ACCESSIBILITY_OFF,

    /** 悬浮窗权限被撤销 */
    OVERLAY_REVOKED,

    /** App 被强制停止（下次启动时发现异常中断） */
    APP_KILLED,

    /** 系统时间被修改 */
    TIME_CHANGED,

    /** 紧急放行被使用 */
    EMERGENCY_RELEASE,

    /** 异常解锁（室内定位降级通道） */
    ABNORMAL_UNLOCK,

    /** 锁机期间进入了系统设置 */
    SETTINGS_OPENED;

    companion object {
        fun decode(raw: String): EventType? =
            runCatching { valueOf(raw.trim().uppercase()) }.getOrNull()

        /** 面向用户的中文描述（PRD 文案规范：只陈述事实，不做道德评判）。 */
        fun describe(type: EventType): String = when (type) {
            ACCESSIBILITY_OFF -> "无障碍服务被关闭"
            OVERLAY_REVOKED -> "悬浮窗权限被撤销"
            APP_KILLED -> "应用被强制停止"
            TIME_CHANGED -> "系统时间被修改"
            EMERGENCY_RELEASE -> "紧急放行"
            ABNORMAL_UNLOCK -> "异常解锁（定位降级）"
            SETTINGS_OPENED -> "进入系统设置"
        }
    }
}

/** 事件记录。 */
data class ZilvEvent(
    val id: Long,
    val sessionId: Long?,
    val type: EventType?,
    val rawType: String,
    val detectedAtMillis: Long,
    val detail: String?
) {
    val label: String get() = type?.let { EventType.describe(it) } ?: rawType
}

/** 锁机窗口实例。 */
data class LockSession(
    val id: Long,
    val ruleId: Long,
    val ruleName: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val state: String,
    val unlockedAtMillis: Long?,
    val unlockReason: String?,
    val reportSeen: Boolean
)

/**
 * 锁机窗口与会话仓库（PRD §5 `lock_sessions` / `events`）。
 *
 * 窗口实例的创建是**幂等**的：同规则同开始时刻只会有一条记录，
 * 这样无障碍服务、闹钟、自检任务都可以安全地反复调用。
 */
class LockSessionRepository(
    private val lockSessionDao: LockSessionDao,
    private val eventDao: EventDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /**
     * 取得（必要时创建）某个窗口实例。
     */
    suspend fun ensureSession(
        ruleId: Long,
        ruleName: String,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): LockSession {
        val existing = lockSessionDao.findByRuleAndStart(ruleId, windowStartMillis)
        if (existing != null) return existing.toDomain()

        val id = lockSessionDao.insert(
            LockSessionEntity(
                ruleId = ruleId,
                ruleName = ruleName,
                windowStartMillis = windowStartMillis,
                windowEndMillis = windowEndMillis,
                state = STATE_LOCKED,
                unlockedAtMillis = null,
                unlockReason = null,
                reportSeen = false
            )
        )
        return lockSessionDao.getById(id)!!.toDomain()
    }

    suspend fun markUnlocked(sessionId: Long, reason: String) {
        val s = lockSessionDao.getById(sessionId) ?: return
        lockSessionDao.update(
            s.copy(
                state = STATE_UNLOCKED,
                unlockedAtMillis = clock.millis(),
                unlockReason = reason
            )
        )
    }

    suspend fun markExpired(sessionId: Long) {
        val s = lockSessionDao.getById(sessionId) ?: return
        if (s.state == STATE_EXPIRED) return
        lockSessionDao.update(s.copy(state = STATE_EXPIRED))
    }

    suspend fun markReportSeen(sessionId: Long) = lockSessionDao.markReportSeen(sessionId)

    /** 首页「上次回顾」卡片的数据源：最近一条未查看的已结束窗口。 */
    suspend fun latestUnseenFinished(): LockSession? =
        lockSessionDao.latestUnseenFinished(clock.millis())?.toDomain()

    suspend fun recent(limit: Int = 50): List<LockSession> =
        lockSessionDao.recent(limit).map { it.toDomain() }

    fun observeAll(): Flow<List<LockSession>> =
        lockSessionDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: Long): LockSession? = lockSessionDao.getById(id)?.toDomain()

    // ---------------------------------------------------------------- 事件

    suspend fun recordEvent(
        type: EventType,
        sessionId: Long? = null,
        detail: String? = null,
        atMillis: Long = clock.millis()
    ): Long = eventDao.insert(
        EventEntity(
            sessionId = sessionId,
            type = type.name,
            detectedAtMillis = atMillis,
            detail = detail
        )
    )

    suspend fun eventsFor(sessionId: Long): List<ZilvEvent> =
        eventDao.forSession(sessionId).map { it.toDomain() }

    suspend fun eventsSince(fromMillis: Long): List<ZilvEvent> =
        eventDao.since(fromMillis).map { it.toDomain() }

    fun observeRecentEvents(limit: Int = 200): Flow<List<ZilvEvent>> =
        eventDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    private fun LockSessionEntity.toDomain() = LockSession(
        id = id,
        ruleId = ruleId,
        ruleName = ruleName,
        windowStartMillis = windowStartMillis,
        windowEndMillis = windowEndMillis,
        state = state,
        unlockedAtMillis = unlockedAtMillis,
        unlockReason = unlockReason,
        reportSeen = reportSeen
    )

    private fun EventEntity.toDomain() = ZilvEvent(
        id = id,
        sessionId = sessionId,
        type = EventType.decode(type),
        rawType = type,
        detectedAtMillis = detectedAtMillis,
        detail = detail
    )

    companion object {
        const val STATE_LOCKED = "locked"
        const val STATE_UNLOCKED = "unlocked"
        const val STATE_EXPIRED = "expired"

        const val REASON_CHECKIN = "checkin"
        const val REASON_TIME = "time"
        const val REASON_EMERGENCY = "emergency"
    }
}

package com.zilv.app.domain.lock

import java.time.LocalDateTime

/**
 * 精确闹钟触发时刻规划（PRD §3.3 L2 保活层）。
 *
 * 抽出纯函数的原因：`AlarmManager` 本身无法在 JVM 上测试，但"什么时候该响"
 * 是纯计算 —— 跨零点、T-15 预警、宽限期结束、窗口结束这四种触发点很容易算错，
 * 所以把决策逻辑与 Android API 分离，前者用单元测试钉死。
 */
object AlarmPlanner {

    /** 需要精确触发的时刻类型。每种都显式携带 [instance] 与 [at]。 */
    sealed interface Trigger {
        val instance: WindowScheduler.WindowInstance
        val at: LocalDateTime

        /** T-15 预警 */
        data class Warn(
            override val instance: WindowScheduler.WindowInstance,
            override val at: LocalDateTime
        ) : Trigger

        /** 窗口开始（进入拦截态） */
        data class WindowStart(
            override val instance: WindowScheduler.WindowInstance,
            override val at: LocalDateTime
        ) : Trigger

        /** 宽限期结束（真正开始拦截） */
        data class GraceEnd(
            override val instance: WindowScheduler.WindowInstance,
            override val at: LocalDateTime
        ) : Trigger

        /** 窗口结束（自动解除） */
        data class WindowEnd(
            override val instance: WindowScheduler.WindowInstance,
            override val at: LocalDateTime
        ) : Trigger
    }

    /** 单个窗口实例产生的全部触发点。 */
    fun triggersFor(instance: WindowScheduler.WindowInstance): List<Trigger> {
        val rule = instance.rule
        val list = mutableListOf<Trigger>()

        list += Trigger.Warn(
            instance,
            instance.start.minusMinutes(WindowScheduler.WARN_LEAD_MINUTES)
        )
        list += Trigger.WindowStart(instance, instance.start)

        if (rule.graceMinutes > 0) {
            list += Trigger.GraceEnd(
                instance,
                instance.start.plusMinutes(rule.graceMinutes.toLong())
            )
        }

        list += Trigger.WindowEnd(instance, instance.end)
        return list
    }

    /**
     * [at] 之后（不含）在 [horizonDays] 天内所有触发点，按时间升序。
     *
     * 只考察启用的规则。
     */
    fun upcoming(
        rules: List<LockRule>,
        at: LocalDateTime,
        horizonDays: Long = 7L
    ): List<Trigger> {
        val dates = (0..horizonDays).map { at.toLocalDate().plusDays(it) }
        return rules.asSequence()
            .filter { it.enabled }
            .flatMap { rule -> dates.asSequence().flatMap { d -> WindowScheduler.instancesStartingOn(rule, d) } }
            .flatMap { triggersFor(it) }
            .filter { it.at.isAfter(at) }
            .sortedBy { it.at }
            .toList()
    }

    /**
     * 最近一次需要设定闹钟的时刻。
     *
     * 实际实现里每次触发后重新计算下一个即可，避免一次性注册几十个闹钟
     * （系统对每应用待定闹钟数量有限制）。
     */
    fun next(
        rules: List<LockRule>,
        at: LocalDateTime,
        horizonDays: Long = 7L
    ): Trigger? = upcoming(rules, at, horizonDays).firstOrNull()
}

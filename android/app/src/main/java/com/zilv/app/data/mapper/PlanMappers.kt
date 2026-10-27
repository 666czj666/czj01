package com.zilv.app.data.mapper

import com.zilv.app.data.local.entity.PlanEntity
import com.zilv.app.domain.plan.Plan
import java.time.LocalDate

/**
 * 「明日规划」的实体 <-> 领域映射。
 *
 * 只做**机械换算**（epochDay ↔ [LocalDate]），不做任何业务判断：
 * "只能添加明天"、"不能跨 0 点"这些规则都在
 * [com.zilv.app.data.repository.PlanRepository] 里，这样它们才能被
 * 纯 JVM 单测直接钉住。
 *
 * 与其它映射一致：读取方向**不抛异常**（见 [Plan.isValid] 的说明），
 * 遇到脏数据只让 [Plan.isValid] 为假，由调用方决定怎么处理。
 */
fun PlanEntity.toDomain(): Plan = Plan(
    id = id,
    name = name,
    date = LocalDate.ofEpochDay(epochDay),
    startMinute = startMinute,
    endMinute = endMinute,
    createdAt = createdAt
)

fun Plan.toEntity(): PlanEntity = PlanEntity(
    id = id,
    name = name,
    epochDay = date.toEpochDay(),
    startMinute = startMinute,
    endMinute = endMinute,
    createdAt = createdAt
)

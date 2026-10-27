package com.zilv.app.data.mapper

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.entity.PlanEntity
import com.zilv.app.domain.plan.Plan
import org.junit.Test
import java.time.LocalDate

/**
 * 「明日规划」实体映射的单元测试。
 *
 * 重点是 **epochDay 的换算方向**：写库时用 [LocalDate.toEpochDay]，
 * 读库时用 [LocalDate.ofEpochDay]，两边必须严格互为逆运算 ——
 * 一旦口径不一致，日历上的规划就会落到隔壁那天。
 */
class PlanMappersTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 15)

    @Test
    fun `规划 - 实体往返无损`() {
        val plan = Plan(
            id = 7L,
            name = "读书",
            date = day,
            startMinute = 9 * 60,
            endMinute = 11 * 60,
            createdAt = 1234L
        )
        assertThat(plan.toEntity().toDomain()).isEqualTo(plan)
    }

    @Test
    fun `规划 - epochDay 与本地日期一一对应`() {
        val entity = Plan(
            name = "读书",
            date = day,
            startMinute = 540,
            endMinute = 660,
            createdAt = 0L
        ).toEntity()

        // 2026-10-14 是 20740（既有测试里的基准），所以 10-15 是 20741
        assertThat(entity.epochDay).isEqualTo(20741L)
        assertThat(entity.toDomain().date).isEqualTo(day)
    }

    @Test
    fun `规划 - 列名与领域字段一一对应`() {
        val entity = PlanEntity(
            id = 3L,
            name = "跑步",
            epochDay = 20741L,
            startMinute = 1080,
            endMinute = 1140,
            createdAt = 99L
        )
        val plan = entity.toDomain()
        assertThat(plan.id).isEqualTo(3L)
        assertThat(plan.name).isEqualTo("跑步")
        assertThat(plan.startMinute).isEqualTo(1080)
        assertThat(plan.endMinute).isEqualTo(1140)
        assertThat(plan.createdAt).isEqualTo(99L)
        assertThat(plan.isValid).isTrue()
    }

    @Test
    fun `规划 - 脏数据读回来不抛异常，只是标记为不合法`() {
        val entity = PlanEntity(
            id = 1L,
            name = "越界",
            epochDay = 20741L,
            startMinute = 0,
            endMinute = 1440,
            createdAt = 0L
        )
        val plan = entity.toDomain()
        assertThat(plan.isValid).isFalse()
        assertThat(plan.invalidReason).isNotNull()
    }
}

package com.zilv.app.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * **打卡条件**（[CheckInCondition]）的派生规则与文案（v2.2）。
 *
 * 用户亲自纠正过术语：
 *  - 「打卡」= 一次完成、连续天数 +1
 *  - 「打卡条件」= 这条习惯要求怎么打卡：**指定地点** 或 **无**
 *
 * 打卡条件是**派生属性**（`type` + `spotId`），刻意不新增数据库列 ——
 * 用户数据正在使用，只改代码不建新列。这里把映射表钉死，防止
 * "库里是 LOCATION、界面却显示无条件"这种两套口径的分叉。
 */
class HabitCheckInConditionTest {

    @Test
    fun `LOCATION 且选了打卡点就是指定地点`() {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        assertThat(habit.checkInCondition).isEqualTo(CheckInCondition.LOCATION)
        assertThat(habit.checkInConditionLabel).isEqualTo("指定地点")
    }

    @Test
    fun `LOCATION 但没选打卡点退化为无`() {
        // 这种情况没有可校验的地点，继续要求"地点在证"会让用户既打不了卡、
        // 又点不了勾 —— 所以按"无条件"处理
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = null)
        assertThat(habit.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(habit.checkInConditionLabel).isEqualTo("无条件")
    }

    @Test
    fun `纯打卡与计时类型都是无条件`() {
        assertThat(Habit(name = "x", type = HabitType.SIMPLE).checkInCondition)
            .isEqualTo(CheckInCondition.NONE)
        assertThat(Habit(name = "x", type = HabitType.TIMED).checkInCondition)
            .isEqualTo(CheckInCondition.NONE)
    }

    @Test
    fun `两种打卡条件各有一句讲清取舍的说明`() {
        val location = Habit(name = "x", type = HabitType.LOCATION, spotId = 7L)
        val none = Habit(name = "x", type = HabitType.SIMPLE)
        // 「无」必须让用户看出室内也能用；「指定地点」必须提前警告室内可能定位不到
        assertThat(none.checkInConditionHint).contains("室内")
        assertThat(location.checkInConditionHint).contains("室内")
        assertThat(none.checkInConditionHint).isNotEqualTo(location.checkInConditionHint)
    }

    @Test
    fun `改成无条件打卡会同时清掉类型与打卡点`() {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val switched = habit.withUnconditionalCheckIn()

        assertThat(switched.type).isEqualTo(HabitType.SIMPLE)
        assertThat(switched.spotId).isNull()
        assertThat(switched.checkInCondition).isEqualTo(CheckInCondition.NONE)
        // 其余字段不能被顺手改掉
        assertThat(switched.name).isEqualTo(habit.name)
        assertThat(switched.id).isEqualTo(habit.id)
    }

    @Test
    fun `打卡条件只有两个取值`() {
        assertThat(CheckInCondition.entries.map { it.name })
            .containsExactly("NONE", "LOCATION")
    }
}

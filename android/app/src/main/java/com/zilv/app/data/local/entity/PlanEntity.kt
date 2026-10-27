package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「明日规划」表（`plans`）。
 *
 * 用户要求："添加明日规划界面，该界面只能添加明天的规划。自定义规划名称，
 * 自定义规划的开始和结束时间，规划时间内锁机，解锁条件仅为到点解锁，
 * 规划不能跨 0 点。日历上可以看见当天的规划。"
 *
 * 设计取舍：
 *  - `epoch_day` 存**本地日期**的 epochDay（与 `check_ins` / `day_notes` 同口径），
 *    而不是时间戳：规划是"哪一天的事"，与设备时区换算解耦，
 *    日历查询也只需要一个整数集合就能标出"有规划的日期"。
 *  - 起止时间存"当天第几分钟"（`0..1439`），与 [com.zilv.app.domain.lock.TimeWindow] 同口径，
 *    转成锁机窗口时**零换算**。
 *  - **半开区间** `[start_minute, end_minute)`：与锁机窗口一致，
 *    所以 11:00 结束的规划在 11:00 整就不再锁机。
 *  - 不做外键、不建额外的锁机规则行：规划的锁机规则是**运行时**
 *    由 [com.zilv.app.domain.plan.Plan.toLockRule] 派生的（见该方法的说明）。
 *  - `created_at` 用于调试与将来排序（现在按开始时间排）。
 */
@Entity(
    tableName = "plans",
    indices = [Index("epoch_day")]
)
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** 这条规划**生效的那一天**（本地日期转 epochDay）。 */
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    /** 当天第几分钟，含 */
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    /** 当天第几分钟，不含 */
    @ColumnInfo(name = "end_minute") val endMinute: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

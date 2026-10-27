package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按天留言表（`day_notes`）。
 *
 * 用户要求："当天所有习惯都打卡完成后，今天界面上方显示绿底的「所有任务已完成」，
 * 点它可以留言，留言记录可以在日历上看见。"
 *
 * 设计取舍：
 *  - **一天最多一条**：留言是"这一天过得怎么样"的一句话总结，不是流水账。
 *    所以主键直接用 `epoch_day`（而不是自增 id）—— "一天一条"由主键在
 *    数据库层保证，而不是靠调用方自觉。
 *  - `upsert` 语义天然成立：同一天再写就是覆盖。
 *  - 主键用 `epoch_day` 而不是 `LocalDate`：与 `check_ins.epoch_day` 口径一致，
 *    日历标记只需要一个 `Set<Long>` 就能查出来。
 *  - `updated_at` 单独留着，是为了将来按"最近写过留言"排序（现在只用于展示/调试）。
 */
@Entity(tableName = "day_notes")
data class DayNoteEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

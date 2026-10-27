package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zilv.app.data.local.entity.DayNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * 按天留言的 DAO。
 *
 * 与其它 DAO 一样，这里只做"存取"，所有业务判断（例如"今天是哪天"）
 * 都留在仓库层，这样纯 JVM 单测可以用内存 Fake 完整覆盖。
 */
@Dao
interface DayNoteDao {

    /** 观察某一天的留言（没有就是 null）。 */
    @Query("SELECT * FROM day_notes WHERE epoch_day = :epochDay")
    fun observe(epochDay: Long): Flow<DayNoteEntity?>

    @Query("SELECT * FROM day_notes WHERE epoch_day = :epochDay")
    suspend fun get(epochDay: Long): DayNoteEntity?

    @Query("SELECT * FROM day_notes ORDER BY epoch_day ASC")
    suspend fun getAll(): List<DayNoteEntity>

    /**
     * 有留言的日期。
     *
     * 日历只要一个日期集合就够了，**不把留言正文一起查出来** ——
     * 月历一次要渲染 42 个格子，没必要为了画一个小圆点把整月文本拉进内存。
     */
    @Query("SELECT epoch_day FROM day_notes ORDER BY epoch_day ASC")
    fun observeEpochDays(): Flow<List<Long>>

    @Query("SELECT epoch_day FROM day_notes ORDER BY epoch_day ASC")
    suspend fun epochDays(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: DayNoteEntity)

    @Query("DELETE FROM day_notes WHERE epoch_day = :epochDay")
    suspend fun delete(epochDay: Long)

    @Query("SELECT COUNT(*) FROM day_notes")
    suspend fun count(): Int
}

package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.RuleCheckInDao
import com.zilv.app.data.local.dao.SpotDao
import com.zilv.app.data.mapper.RuleCheckIn
import com.zilv.app.data.mapper.RuleCheckInStatus
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 打卡点与「打卡解锁」记录的数据入口（PRD §3.5）。
 */
class SpotRepository(
    private val spotDao: SpotDao,
    private val ruleCheckInDao: RuleCheckInDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    private val zone: ZoneId get() = clock.zone

    // ---------------------------------------------------------------- 打卡点

    fun observeSpots(): Flow<List<Spot>> =
        spotDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAllSpots(): List<Spot> = spotDao.getAll().map { it.toDomain() }

    suspend fun getSpot(id: Long): Spot? = spotDao.getById(id)?.toDomain()

    /**
     * **新建**一个打卡点；已存在的打卡点**改不动**（v2.12，用户要求）。
     *
     * ## 用户要求
     *
     * > 「打卡点创建好之后不能再修改。新建打卡点照旧；创建完成后进入编辑页
     * > 只能看，不能改（名称、坐标、半径等字段都只读）」
     *
     * ## 为什么守卫必须放在这里，而不是只挡界面
     *
     * 界面挡只能挡住看得见的入口（这一版是 `SpotEditScreen` 的只读态），
     * 其余调用点（备份恢复、将来的快捷入口、脚本化调用）会直接绕过。
     * 放在仓库层还带来一个好处：这条规则能被**纯 JVM 单测**直接覆盖
     * （见 `SpotRepositoryTest` 里那组"创建后不可改"的用例）。
     *
     * ## 为什么"删除"不在这条守卫里
     *
     * 删除**照旧允许**（[delete] 一个字节都没改）。理由：打卡点是很容易建错的
     * 东西（站在楼里按一下就把当前位置存下来了），而"建错了就清不掉"比
     * "建错了能改"更糟 —— 用户会被一个再也删不掉的打卡点永久绑住
     * （习惯的打卡条件指着它，而它又不让改）。
     * 所以本轮的取舍是：**锁定"改"，保留"删"**。用户想调整坐标/半径，
     * 就删掉重建 —— 这也让"已经生效过的打卡点"不会在背后悄悄换位置
     * （历史 `rule_check_ins` 里记的距离与坐标因此始终与当时那个点一致）。
     *
     * ⚠️ **备份恢复不受影响**：`BackupViewModel` 走的是
     * `save(it.copy(id = 0L))` —— id 归零就是"新建"，所以恢复快照仍然能
     * 把所有打卡点原样搬回来（它们本来就是"新建"出来的）。
     *
     * @throws SpotImmutableException [spot] 带了一个已存在的 id
     */
    suspend fun save(spot: Spot): Long {
        require(spot.isValid) {
            "打卡点不合法：需要名称，半径须在 ${Spot.MIN_RADIUS_M}–${Spot.MAX_RADIUS_M} 米之间"
        }
        if (spot.id != 0L) {
            throw SpotImmutableException(
                "打卡点创建后不能修改（名称、坐标、半径都锁定）。" +
                    "需要调整就删掉它再新建一个。"
            )
        }
        return spotDao.insert(spot.toEntity())
    }

    /**
     * 删除打卡点。**这条路径照旧允许**（理由见 [save] 的 KDoc）。
     *
     * 已经写在 `rule_check_ins` 里的历史打卡记录**不随之删除** ——
     * 那是"当时确实到过这里"的事实，删掉打卡点不该抹掉历史。
     */
    suspend fun delete(id: Long) = spotDao.delete(id)

    // ------------------------------------------------------------ 解锁打卡

    /**
     * 记录一次成功的「打卡解锁」。
     *
     * @param status 按时 / 迟到 / 异常解锁（室内降级通道）
     */
    suspend fun recordCheckIn(
        ruleId: Long,
        spotId: Long,
        location: GeoPoint,
        distanceM: Double,
        status: RuleCheckInStatus,
        atMillis: Long = clock.millis()
    ): Long {
        val epochDay = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toEpochDay()
        return ruleCheckInDao.insert(
            RuleCheckIn(
                ruleId = ruleId,
                spotId = spotId,
                checkedAtMillis = atMillis,
                epochDay = epochDay,
                lat = location.lat,
                lng = location.lng,
                accuracyM = location.accuracyM,
                distanceM = distanceM,
                status = status
            ).toEntity()
        )
    }

    suspend fun latestCheckIn(ruleId: Long): RuleCheckIn? =
        ruleCheckInDao.latestFor(ruleId)?.toDomain()

    suspend fun latestCheckInTime(ruleId: Long): LocalDateTime? =
        latestCheckIn(ruleId)?.localDateTime(zone)

    suspend fun checkInsFor(ruleId: Long): List<RuleCheckIn> =
        ruleCheckInDao.allFor(ruleId).map { it.toDomain() }

    /** 清空某规则的打卡记录（删除规则时调用，避免留下孤儿数据）。 */
    suspend fun clearCheckInsForRule(ruleId: Long) = ruleCheckInDao.deleteForRule(ruleId)

    fun now(): LocalDateTime = LocalDateTime.now(clock)
}

/**
 * 试图修改一个**已经创建好**的打卡点。
 *
 * 单独定义一个异常类型而不是复用 [IllegalArgumentException]，
 * 与 [RuleLockedException] / [QuotaRejectedException] 的理由相同：
 * 上层要能精确区分"业务规则拒绝"（给用户一句人话）与"程序出错"（才需要上报）。
 * `SpotEditViewModel` 就靠它把原因原样显示出来。
 */
class SpotImmutableException(message: String) : IllegalStateException(message)

package com.zilv.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zilv.app.data.local.dao.AllowAppDao
import com.zilv.app.data.local.dao.BlockedAttemptDao
import com.zilv.app.data.local.dao.CheckInDao
import com.zilv.app.data.local.dao.DayNoteDao
import com.zilv.app.data.local.dao.EventDao
import com.zilv.app.data.local.dao.HabitDao
import com.zilv.app.data.local.dao.LockRuleDao
import com.zilv.app.data.local.dao.LockSessionDao
import com.zilv.app.data.local.dao.PlanDao
import com.zilv.app.data.local.dao.RuleAllowAppDao
import com.zilv.app.data.local.dao.RuleCheckInDao
import com.zilv.app.data.local.dao.SpotDao
import com.zilv.app.data.local.dao.UsageDao
import com.zilv.app.data.local.dao.UsageQuotaDao
import com.zilv.app.data.local.entity.AllowAppEntity
import com.zilv.app.data.local.entity.AppUsageEntity
import com.zilv.app.data.local.entity.BlockedAttemptEntity
import com.zilv.app.data.local.entity.CheckInEntity
import com.zilv.app.data.local.entity.DayNoteEntity
import com.zilv.app.data.local.entity.EventEntity
import com.zilv.app.data.local.entity.HabitEntity
import com.zilv.app.data.local.entity.LockRuleEntity
import com.zilv.app.data.local.entity.LockSessionEntity
import com.zilv.app.data.local.entity.PlanEntity
import com.zilv.app.data.local.entity.RuleAllowAppEntity
import com.zilv.app.data.local.entity.RuleCheckInEntity
import com.zilv.app.data.local.entity.SpotEntity
import com.zilv.app.data.local.entity.UsageQuotaAppEntity
import com.zilv.app.data.local.entity.UsageQuotaEntity
import com.zilv.app.data.local.entity.UsageSessionEntity

/**
 * 本地数据库（v10）。
 *
 * ## 迁移策略（v1 → v10 全覆盖，升级不再清库）
 *
 * 历史上每一次改版本号都是"直接重建库"（`fallbackToDestructiveMigration()`），
 * 用户数据每次升级都被清空。现在改成**逐段迁移**：
 *
 * | 段 | 方式 | 该段 schema 差异（依据 `schemas/` 下的 JSON） |
 * |---|---|---|
 * | 1→2 | 自动 | 新增表 `allow_apps` / `lock_rules` / `rule_allow_apps` |
 * | 2→3 | 自动 | 新增表 `blocked_attempts` |
 * | 3→4 | 自动 | 新增表 `spots` / `rule_check_ins` |
 * | 4→5 | 自动 | 新增表 `usage_sessions` / `app_usage` / `lock_sessions` / `events` |
 * | 5→6 | **手写** | `lock_rules` 加列 `habit_id`（可空）与 `check_in_windows`（NOT NULL 且**无默认值**），并加索引 `index_lock_rules_habit_id` |
 * | 6→7 | **手写** | `lock_rules` 加列 `lock_duration_minutes`（NOT NULL 且**无默认值**） |
 * | 7→8 | 自动 | 新增表 `day_notes` |
 * | 8→9 | 自动 | 新增表 `plans`；`lock_rules` 加列 `plan_id`（可空）与 `allow_check_in_unlock`（NOT NULL，`DEFAULT 1`） |
 * | 9→10 | **自动** | 新增表 `usage_quotas` / `usage_quota_apps`（v2.7「软件使用时间限时」） |
 *
 * 为什么 5→6 / 6→7 必须手写：Room 只会为「新增表 / 新增索引 / 新增可空列 /
 * 新增带 `@ColumnInfo(defaultValue = ...)` 的非空列」生成自动迁移。
 * 这两段各有一个新增的 **NOT NULL 且实体上没有声明默认值** 的列，
 * room-compiler 2.6.1 的 `AutoMigrationWriter` 遇到这种列会直接
 * 抛 `IllegalStateException("A Non-Null field should always have a default value.")`
 * （见 `androidx/room/writer/AutoMigrationWriter`），所以自动迁移在这里做不到。
 * 手写的两条迁移见 [MIGRATION_5_6] / [MIGRATION_6_7]。
 *
 * 9→10 为什么可以自动：这一段**只新增两张表**，没有任何 `ALTER TABLE`。
 * 这是 Room 自动迁移最擅长的形态 —— 它会在编译期用 `schemas/9.json` 与
 * `schemas/10.json` 校验"能否产出目标 schema"，产出不符直接编译失败。
 * 两张新表用的是全新的表名，与既有表没有任何交互，所以不需要回填数据。
 *
 * ## 覆盖度
 *
 * 1→2 … 9→10 九段一段不缺（[MANUAL_MIGRATIONS] + 本注解的 `autoMigrations`），
 * 由纯 JVM 单测 `ZilvMigrationCoverageTest` 钉住。
 *
 * ## 为什么降级仍然破坏性
 *
 * 降级（用户装了新版又退回旧版）意味着要按"未来 schema → 过去 schema"回退，
 * 旧版本代码里根本不存在这段迁移（它还没被写出来），只能重建空库 ——
 * 这是常规做法，也是 [Room.databaseBuilder] 上
 * `fallbackToDestructiveMigrationOnDowngrade()` 的语义。
 * 注意它**只**在降级时清库：升级路径缺任何一段都会大声抛
 * `IllegalStateException`（"A migration from X to Y was required but not found"），
 * 而不是静默清空用户数据。
 */
@Database(
    entities = [
        HabitEntity::class,
        CheckInEntity::class,
        LockRuleEntity::class,
        AllowAppEntity::class,
        RuleAllowAppEntity::class,
        BlockedAttemptEntity::class,
        SpotEntity::class,
        RuleCheckInEntity::class,
        UsageSessionEntity::class,
        AppUsageEntity::class,
        LockSessionEntity::class,
        EventEntity::class,
        DayNoteEntity::class,
        PlanEntity::class,
        UsageQuotaEntity::class,
        UsageQuotaAppEntity::class
    ],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        // v2.7：只新增 usage_quotas / usage_quota_apps 两张表，无需回填
        AutoMigration(from = 9, to = 10)
    ]
)
abstract class ZilvDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao
    abstract fun checkInDao(): CheckInDao
    abstract fun lockRuleDao(): LockRuleDao
    abstract fun allowAppDao(): AllowAppDao
    abstract fun ruleAllowAppDao(): RuleAllowAppDao
    abstract fun blockedAttemptDao(): BlockedAttemptDao
    abstract fun spotDao(): SpotDao
    abstract fun ruleCheckInDao(): RuleCheckInDao
    abstract fun usageDao(): UsageDao
    abstract fun lockSessionDao(): LockSessionDao
    abstract fun eventDao(): EventDao
    abstract fun dayNoteDao(): DayNoteDao
    abstract fun planDao(): PlanDao
    abstract fun usageQuotaDao(): UsageQuotaDao

    companion object {
        const val NAME = "zilv.db"

        /**
         * v5 → v6：`lock_rules` 增加 `habit_id` 与 `check_in_windows`，并补上 `habit_id` 的索引。
         *
         * **为什么不能自动迁移**：`check_in_windows` 在 v6 的 schema 里是
         * `TEXT NOT NULL` 且没有默认值（见 `schemas/.../6.json` 的 `createSql`），
         * SQLite 不允许 `ADD COLUMN` 一个没有默认值的 NOT NULL 列，
         * Room 对这种列也拒绝生成自动迁移。
         *
         * 三条 SQL 做的事：
         *  1. `habit_id` 可空，直接加，老规则读回来是 null（= 旧版遗留的独立规则，仍可正常工作）
         *  2. `check_in_windows` 非空，回填 `''` —— 与 [LockRuleEntity.checkInWindows]
         *     的 Kotlin 默认值一致（实体注释写明：空串表示不要求打卡，行为与 v1.x 完全相同）
         *  3. 补建 `index_lock_rules_habit_id`，与 v6 schema 里的索引对齐
         *
         * 注意 SQLite 会把 `DEFAULT ''` 留在表定义里，而全新建库（直接执行 v9 的建表语句）
         * 时 `check_in_windows` / `lock_duration_minutes` 上是没有默认值的。这个差异不影响
         * 正确性：Room 的运行时校验（`TableInfo.Column.equals`）对"来自数据库的列"
         * 只比较名字/类型/NOT NULL/主键位置与**实体自己声明的**默认值，
         * 实体没声明默认值时不做比较；而 DAO 的每一次写入都会显式带上这两列。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lock_rules ADD COLUMN habit_id INTEGER")
                db.execSQL(
                    "ALTER TABLE lock_rules ADD COLUMN check_in_windows TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lock_rules_habit_id " +
                        "ON lock_rules (habit_id)"
                )
            }
        }

        /**
         * v6 → v7：`lock_rules` 增加 `lock_duration_minutes`。
         *
         * **为什么不能自动迁移**：与 [MIGRATION_5_6] 同因 —— v7 的 schema 里
         * `lock_duration_minutes` 是 `INTEGER NOT NULL` 且无默认值。
         *
         * SQL 做的事：加列并回填 `0`，与 [LockRuleEntity.lockDurationMinutes]
         * 的 Kotlin 默认值一致（0 = 走旧式自选窗口 `windows`，不改变老规则的锁机行为）。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE lock_rules ADD COLUMN lock_duration_minutes " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 手写迁移清单：`build()` 与单测共用这一份对象，避免"声明了却没注册"。
         *
         * 自动迁移那六段在 [Database] 注解的 `autoMigrations` 里（注解参数必须是
         * 编译期常量，没法引用到这里来）。
         */
        val MANUAL_MIGRATIONS: List<Migration> = listOf(MIGRATION_5_6, MIGRATION_6_7)

        fun build(context: Context): ZilvDatabase =
            Room.databaseBuilder(context, ZilvDatabase::class.java, NAME)
                // 升级：1→10 的每一段都有迁移（自动迁移 ×7 + 手写 ×2）。
                // 任何没被覆盖的路径都会抛 IllegalStateException 而不是清库 ——
                // 宁可大声失败，也不要静默丢用户数据。
                .addMigrations(*MANUAL_MIGRATIONS.toTypedArray())
                // 降级：旧版本代码里不可能存在"未来 → 过去"的迁移，只能重建空库。
                // 只对降级生效，不影响任何升级路径。
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}

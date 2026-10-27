package com.zilv.app.data.local

import androidx.room.migration.Migration
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * 数据库迁移覆盖度测试 —— **纯 JVM 单测，不需要真机 / 模拟器**。
 *
 * 为什么不用 `MigrationTestHelper`：它属于 `androidTest`，必须有设备才能跑，
 * 而本项目没有真机。所以这里换一种不依赖设备、但依然能真正钉住迁移的手法：
 *
 * 1. **版本历史**取自 `schemas/com.zilv.app.data.local.ZilvDatabase/N.json`
 *    —— Room 导出的 schema 是"某版本长什么样"的唯一真相来源；
 * 2. **自动迁移**以 Room 自己生成的类为证据：
 *    每个通过编译期校验的自动迁移都会生成
 *    `ZilvDatabase_AutoMigration_<from>_<to>_Impl` 类（Room 若判定自动迁移
 *    产出不了目标 schema，会直接编译失败，因此"类存在"⟺"该段自动迁移成立"）。
 *    它同时与源码里 `@Database(autoMigrations = [...])` 的声明交叉比对，
 *    两边必须一字不差；
 * 3. **手写迁移**读的是 `build()` 真正注册的那份对象
 *    （[ZilvDatabase.MANUAL_MIGRATIONS]，反射取 `startVersion` / `endVersion`），
 *    并与源码里 `object : Migration(a, b)` 的声明交叉比对；它的 SQL 不是
 *    Room 校验的（Room 只校验自动迁移），所以额外逐段比对 schema JSON，
 *    确认目标版本新增的列与索引都被 SQL 覆盖、且没有漏掉删列 / 改列类型。
 *
 * ⚠️ 自动迁移清单**不能**用反射读 `@Database` 注解：`androidx.room.Database`
 * 与 `androidx.room.AutoMigration` 的保留级别是 `BINARY`（= Java 的
 * `RetentionPolicy.CLASS`），运行时 `getAnnotation` 只会拿到 null。
 * 这也正是上面第 2 条改用"生成类 + 源码文本"两条腿的原因。
 *
 * 解析源码时会先剥掉注释，所以**注释掉的声明不算数**；解析器不认识字符串
 * 字面量里的 `//` 与 `\"`（本文件当前没有这种写法）。
 */
class ZilvMigrationCoverageTest {

    // ============================================================ 事实来源

    /** 模块根目录（`android/app`）。 */
    private val moduleRoot: File = resolveModuleRoot()

    /** Room 导出的 schema 目录。 */
    private val schemaDir: File = File(moduleRoot, SCHEMA_DIR)

    /** 版本历史：把 `N.json` 的文件名解析成 N，升序。 */
    private val schemaVersions: List<Int> = schemaDir
        .listFiles { f -> f.isFile && VERSION_JSON.matches(f.name) }
        .orEmpty()
        .map { VERSION_JSON.matchEntire(it.name)!!.groupValues[1].toInt() }
        .sorted()

    private val maxSchemaVersion: Int
        get() = schemaVersions.lastOrNull()
            ?: error("$schemaDir 下没有任何 schema JSON")

    /** `ZilvDatabase.kt` 原文（`@Database` 注解与手写迁移都在这里）。 */
    private val databaseSource: String =
        File(moduleRoot, DATABASE_SOURCE).readText(Charsets.UTF_8)

    /** 剥掉注释后的源码：注释里的 `AutoMigration(...)` / `fallbackTo...` 不该算数。 */
    private val code: String = stripComments(databaseSource)

    /** `@Database(...)` 的括号内容。 */
    private val databaseAnnotation: String = parenBlock(code, "@Database", "找不到 @Database 注解")

    /** 注解里的 `version = N`。 */
    private val declaredVersion: Int =
        RE_VERSION.find(databaseAnnotation)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("@Database 注解里找不到 version = ...")

    /** 注解里 `entities = [...]` 登记的实体类名。 */
    private val declaredEntities: List<String> = RE_ENTITY_CLASS
        .findAll(bracketBlock(databaseAnnotation, "entities", "@Database"))
        .map { it.groupValues[1] }
        .toList()

    /** 注解里 `autoMigrations = [...]` 声明的自动迁移。 */
    private val autoPairsFromSource: List<Pair<Int, Int>> = RE_AUTO_MIGRATION
        .findAll(bracketBlock(databaseAnnotation, "autoMigrations", "@Database"))
        .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        .toList()

    /** 源码里 `object : Migration(a, b)` 声明的（不一定注册过的）手写迁移。 */
    private val manualPairsFromSource: List<Pair<Int, Int>> = RE_MIGRATION_DECL
        .findAll(code)
        .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        .toList()

    /** `build()` 真正注册的手写迁移对象。 */
    private val manualMigrations: List<Migration> = ZilvDatabase.MANUAL_MIGRATIONS

    private val manualPairs: List<Pair<Int, Int>> =
        manualMigrations.map { it.startVersion to it.endVersion }

    /** Room 真正生成了自动迁移实现类的那些段（= 编译期校验通过的自动迁移）。 */
    private val autoPairsFromRoom: List<Pair<Int, Int>> =
        (1..maxSchemaVersion).flatMap { from ->
            ((from + 1)..maxSchemaVersion).map { to -> from to to }
        }.filter { (from, to) -> autoMigrationClassExists(from, to) }

    /** 每一段手写迁移的 SQL（按 (from, to) 归类，已把字符串拼接拼回一条）。 */
    private val manualSql: Map<Pair<Int, Int>, List<String>> = buildMap {
        for (chunk in code.split(SPLIT_BEFORE_VAL_MIGRATION)) {
            val head = RE_VAL_MIGRATION.find(chunk) ?: continue
            val pair = head.groupValues[1].toInt() to head.groupValues[2].toInt()
            put(pair, execSqlStatements(chunk))
        }
    }

    // ============================================================ 1. 版本历史

    @Test
    fun `schemas 里的版本历史是 1 到 10 连续`() {
        assertWithMessage("schemas 目录: $schemaDir")
            .that(schemaVersions)
            .isNotEmpty()
        assertWithMessage("版本历史有断档 —— 迁移覆盖度就无从谈起")
            .that(schemaVersions)
            .isEqualTo((1..maxSchemaVersion).toList())
        // v2.7「软件使用时间限时」把版本号从 9 升到了 10（新增
        // `usage_quotas` / `usage_quota_apps` 两张表），并且**同一批**
        // 补上了 9→10 的迁移（见 `@Database(autoMigrations = ...)`）。
        // 以后真的要再升版本时，把 10 改成新版本号，并同时补上那一段迁移。
        assertWithMessage("数据库版本号被改动了（当前应为 10）")
            .that(maxSchemaVersion)
            .isEqualTo(10)
    }

    @Test
    fun `@Database 的 version 与最新 schema 文件一致`() {
        assertThat(declaredVersion).isEqualTo(maxSchemaVersion)
    }

    // ============================================================ 2. 覆盖度

    @Test
    fun `每一段相邻迁移都被覆盖且只被覆盖一次`() {
        val covered = autoPairsFromSource + manualPairs
        val expected = (1 until maxSchemaVersion).map { it to (it + 1) }

        for (pair in expected) {
            assertWithMessage("$pair 这一段没有任何迁移：升级到该版本会直接抛 IllegalStateException")
                .that(covered.count { it == pair })
                .isEqualTo(1)
        }
        assertWithMessage("声明了多余的迁移段（覆盖度只需相邻段）")
            .that(covered.sortedBy { it.first })
            .containsExactlyElementsIn(expected.sortedBy { it.first })
    }

    @Test
    fun `没有重复或互相矛盾的迁移声明`() {
        assertWithMessage("autoMigrations 里有重复的段")
            .that(autoPairsFromSource)
            .containsNoDuplicates()
        assertWithMessage("手写迁移里有重复的段")
            .that(manualPairs)
            .containsNoDuplicates()
        assertWithMessage("同一段既声明了自动迁移又声明了手写迁移 —— Room 会按两条路径各跑一遍")
            .that(autoPairsFromSource.toSet().intersect(manualPairs.toSet()))
            .isEmpty()

        assertWithMessage("手动迁移清单 [$manualPairs] 与源码声明的 [$manualPairsFromSource] 不一致：声明了却没注册")
            .that(manualPairs.sortedBy { it.first })
            .isEqualTo(manualPairsFromSource.sortedBy { it.first })

        for ((from, to) in autoPairsFromSource + manualPairs) {
            assertWithMessage("$from→$to 不是升级方向")
                .that(from)
                .isLessThan(to)
            assertWithMessage("$from→$to 跳过了中间版本：相邻段已经全覆盖，跳段声明只会重复劳动")
                .that(to)
                .isEqualTo(from + 1)
        }
    }

    @Test
    fun `源码声明的自动迁移与 Room 生成的一致（编译期校验通过的证据）`() {
        assertWithMessage(
            "源码声明了 $autoPairsFromSource，但 Room 只生成了 $autoPairsFromRoom。" +
                "两者不一致说明：要么声明没被 Room 接受，要么生成的类不是这个命名。"
        )
            .that(autoPairsFromRoom.sortedBy { it.first })
            .isEqualTo(autoPairsFromSource.sortedBy { it.first })

        val notCoveredManually = (1 until maxSchemaVersion).map { it to (it + 1) } - manualPairs
        assertWithMessage("手写迁移覆盖的段不该再让 Room 生成自动迁移: $manualPairs")
            .that(autoPairsFromRoom.filter { it in manualPairs })
            .isEmpty()
        assertWithMessage("有段既没有自动迁移也没有手写迁移")
            .that(notCoveredManually.filter { it !in autoPairsFromRoom })
            .isEmpty()
    }

    // ============================================================ 3. 手写迁移

    @Test
    fun `每一段手写迁移都真的执行了 SQL`() {
        assertThat(manualSql.keys).containsExactlyElementsIn(manualPairs)
        for ((pair, statements) in manualSql) {
            assertWithMessage("$pair 的 migrate() 里一条 execSQL 都没有")
                .that(statements)
                .isNotEmpty()
        }
        // 5→6 与 6→7 是仅有的两段手写迁移；断言条数会把"漏写一条 SQL"变成红色。
        assertWithMessage("MIGRATION_5_6 应当是 3 条 SQL（2 个新列 + 1 个新索引）")
            .that(manualSql[5 to 6])
            .hasSize(3)
        assertWithMessage("MIGRATION_6_7 应当是 1 条 SQL（1 个新列）")
            .that(manualSql[6 to 7])
            .hasSize(1)
    }

    @Test
    fun `手写迁移补齐了目标 schema 的每一个新增列与新增索引`() {
        // 防"空转"：解析不出东西时上面的断言会全部空过。
        var addedColumnsChecked = 0
        var addedIndicesChecked = 0

        for ((pair, statements) in manualSql) {
            val (from, to) = pair
            val sql = statements.joinToString("\n")
            val added = RE_ADD_COLUMN.findAll(sql)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            val createdIndices = RE_CREATE_INDEX.findAll(sql)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()

            val fromJson = schemaJson(from)
            val toJson = schemaJson(to)
            val fromTables = tables(fromJson)
            val toTables = tables(toJson)

            assertWithMessage("$from→$to 丢表了")
                .that(toTables.keys)
                .containsAtLeastElementsIn(fromTables.keys)

            for ((table, toBlock) in toTables) {
                val toColumns = columns(toBlock)
                assertWithMessage("$to.json 里表 $table 一列都解析不出来 —— 测试自己坏了")
                    .that(toColumns)
                    .isNotEmpty()
                val fromBlock = fromTables[table] ?: continue
                val fromColumns = columns(fromBlock)
                assertWithMessage("$from.json 里表 $table 一列都解析不出来 —— 测试自己坏了")
                    .that(fromColumns)
                    .isNotEmpty()

                assertWithMessage("$from→$to 的表 $table 丢列了")
                    .that(toColumns.keys)
                    .containsAtLeastElementsIn(fromColumns.keys)

                // 同名列的 affinity / notNull 不能变 —— 变了就必须手写"重建表"，
                // 而不是 ADD COLUMN（Room 的自动迁移也处理不了这种变化）。
                for ((name, toColumn) in toColumns) {
                    val fromColumn = fromColumns[name] ?: continue
                    assertWithMessage("$from→$to 改了 $table.$name 的类型/约束，这不是 ADD COLUMN 能解决的")
                        .that(toColumn)
                        .isEqualTo(fromColumn)
                }

                for (name in toColumns.keys - fromColumns.keys) {
                    addedColumnsChecked++
                    assertWithMessage("$from→$to 新增的列 $table.$name 没有对应的 ADD COLUMN")
                        .that(added)
                        .contains(table to name)
                }
            }

            for ((table, name) in added) {
                val toBlock = toTables[table]
                assertWithMessage("$from→$to 的 ADD COLUMN 指向了不存在的表 $table")
                    .that(toBlock)
                    .isNotNull()
                assertWithMessage("$from→$to 的 ADD COLUMN $table.$name 指向了目标 schema 里不存在的列")
                    .that(columns(toBlock!!))
                    .containsKey(name)
                assertWithMessage("$from→$to 的 ADD COLUMN $table.$name 加的是已经存在的列")
                    .that(columns(fromTables.getValue(table)))
                    .doesNotContainKey(name)
            }

            for (index in indexNames(toJson) - indexNames(fromJson)) {
                addedIndicesChecked++
                assertWithMessage("$from→$to 新增的索引 $index 没有对应的 CREATE INDEX")
                    .that(createdIndices.map { it.first })
                    .contains(index)
            }
            for ((index, table) in createdIndices) {
                assertWithMessage("$from→$to 的 CREATE INDEX $index 不在目标 schema 里")
                    .that(indexNames(toJson))
                    .contains(index)
                assertWithMessage("$from→$to 的 CREATE INDEX $index 建在了错误的表 $table 上")
                    .that(toTables)
                    .containsKey(table)
            }
        }

        // 5→6 新增 2 列 + 1 索引，6→7 新增 1 列：真的比对过东西，不是空转。
        assertWithMessage("一条新增列都没比对到 —— 要么解析坏了，要么手写迁移段真的什么都没加")
            .that(addedColumnsChecked)
            .isEqualTo(3)
        assertWithMessage("一个新增索引都没比对到")
            .that(addedIndicesChecked)
            .isEqualTo(1)
    }

    // ============================================================ 4. 注解与 build()

    @Test
    fun `@Database 登记的实体与最新 schema 的表一一对应`() {
        assertWithMessage("注解里的实体类名重复了")
            .that(declaredEntities)
            .containsNoDuplicates()
        assertWithMessage("@Database(entities = ...) 与 $maxSchemaVersion.json 的表数量不一致")
            .that(declaredEntities)
            .hasSize(tables(schemaJson(maxSchemaVersion)).size)
        // 编译期 room.schemaLocation 校验 + 上面的数量比对，一起保证
        // DayNoteEntity / PlanEntity / UsageQuotaEntity 这些新表与 10.json 一致。
        assertWithMessage("exportSchema 必须保持打开，否则上面这些比对全都失去依据")
            .that(databaseAnnotation)
            .contains("exportSchema = true")
    }

    /**
     * v2.7：`usage_quotas` / `usage_quota_apps` 必须真的出现在 10.json 里。
     *
     * 上面那条"数量一致"只能证明**个数**对上了（比如漏一张、多一张、
     * 或者两张表写成一张都会让它失败），但证明不了"对的是这两张表"。
     * 这张表名字清单是"升级后用户的额度配置有地方落"的最小证据。
     */
    @Test
    fun `v10 的新表 usage_quotas 与 usage_quota_apps 都在 schema 里`() {
        val tablesInV10 = tables(schemaJson(maxSchemaVersion)).keys
        assertWithMessage("10.json 里找不到使用时间限时的两张表")
            .that(tablesInV10)
            .containsAtLeast("usage_quotas", "usage_quota_apps")
        // 反过来：这两张表在 v9 里**不该**存在，否则 9→10 就不是"新增表"，
        // 而"只新增表"正是它可以用自动迁移的理由。
        assertWithMessage("v9 里就已经有这两张表了 —— 那 9→10 的自动迁移理由不成立")
            .that(tables(schemaJson(9)).keys)
            .containsNoneOf("usage_quotas", "usage_quota_apps")
    }

    /**
     * v2.7：9→10 这一段**只新增表**，没有任何 `ALTER TABLE`。
     *
     * 这正是它可以交给 `AutoMigration` 的判据（Room 只为"新增表 / 新增索引 /
     * 新增可空列"生成自动迁移）。如果以后有人往这张段里加列，
     * 这条断言会失败，提醒他"这段已经不能再自动了，要写手写迁移"。
     */
    @Test
    fun `9 到 10 只新增表、不新增列也不改列`() {
        val fromTables = tables(schemaJson(9))
        val toTables = tables(schemaJson(maxSchemaVersion))

        for ((table, fromBlock) in fromTables) {
            val toBlock = toTables[table]
                ?: throw AssertionError("9→10 丢了表 $table")
            assertWithMessage("9→10 改了表 $table 的列定义 —— 这不是 AutoMigration 能处理的")
                .that(columns(toBlock))
                .isEqualTo(columns(fromBlock))
        }
        assertWithMessage("9→10 新增的表不是恰好这两张")
            .that(toTables.keys - fromTables.keys)
            .containsExactly("usage_quotas", "usage_quota_apps")
        // 反向：新增表里如果有索引，自动迁移同样能建，但那是"新增索引"，
        // 只要 Room 能生成就说明合法；这里不额外限制。
    }

    @Test
    fun `build() 不再无条件清库`() {
        assertWithMessage("破坏性回退又回来了 —— 升级会静默清空用户数据")
            .that(code)
            .doesNotContain(".fallbackToDestructiveMigration()")
        assertWithMessage("降级仍然需要 fallbackToDestructiveMigrationOnDowngrade()")
            .that(code)
            .contains(".fallbackToDestructiveMigrationOnDowngrade()")
        assertWithMessage("build() 必须把手写迁移注册进 databaseBuilder")
            .that(code)
            .contains(".addMigrations(")
    }

    // ============================================================ 工具

    private fun autoMigrationClassExists(from: Int, to: Int): Boolean {
        val name = "$GENERATED_PACKAGE.ZilvDatabase_AutoMigration_${from}_${to}_Impl"
        return try {
            Class.forName(name)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private fun schemaJson(version: Int): String {
        val file = File(schemaDir, "$version.json")
        check(file.isFile) { "缺少 schema 文件: $file" }
        return file.readText(Charsets.UTF_8)
    }

    /** 把 `"tableName": "x"` 之后的 JSON 片段切成每张表一块（到下一张表为止）。*/
    private fun tables(json: String): Map<String, String> {
        val hits = RE_TABLE_NAME.findAll(json).toList()
        return hits.mapIndexed { i, m ->
            val start = m.range.first
            val end = hits.getOrNull(i + 1)?.range?.first ?: json.length
            m.groupValues[1] to json.substring(start, end)
        }.toMap()
    }

    /** 表块里的列：`columnName` → (affinity, notNull)。字段对象都是扁平的 `{...}`。*/
    private fun columns(tableBlock: String): Map<String, Column> =
        RE_FIELD_OBJECT.findAll(tableBlock).mapNotNull { field ->
            val text = field.value
            val name = RE_COLUMN_NAME.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val affinity = RE_AFFINITY.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val notNull = RE_NOT_NULL.find(text)?.groupValues?.get(1) == "true"
            name to Column(affinity, notNull)
        }.toMap()

    private fun indexNames(json: String): Set<String> =
        RE_INDEX_NAME.findAll(json).map { it.groupValues[1] }.toSet()

    /** 取 `marker` 之后第一个 `[` 到配对 `]` 的内容（含方括号）。*/
    private fun bracketBlock(text: String, marker: String, where: String): String {
        val start = text.indexOf(marker)
        if (start < 0) throw AssertionError("$where 里找不到 `$marker`")
        val open = text.indexOf('[', start)
        if (open < 0) throw AssertionError("$where 的 `$marker` 后面没有 `[`")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("$where 的 `$marker` 的 `[` 没有闭合")
    }

    /** 取 `marker` 之后第一个 `(` 到配对 `)` 的内容（含圆括号）。*/
    private fun parenBlock(text: String, marker: String, errorMessage: String): String {
        val start = text.indexOf(marker)
        if (start < 0) throw AssertionError(errorMessage)
        val open = text.indexOf('(', start)
        if (open < 0) throw AssertionError("$errorMessage（没有 `(`）")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("$errorMessage（括号没有闭合）")
    }

    /**
     * 取出每一处 `execSQL(...)` 里的 SQL。
     *
     * 源码里长 SQL 会被 Kotlin 拆成多段字符串拼接（`"..." + "..."`），
     * 这里把它们**拼回一条**，否则 `CREATE INDEX ... ON ...` 会被截断。
     */
    private fun execSqlStatements(chunk: String): List<String> {
        val statements = mutableListOf<String>()
        var cursor = chunk.indexOf(EXEC_SQL)
        while (cursor >= 0) {
            val open = cursor + EXEC_SQL.length - 1
            val sql = StringBuilder()
            var depth = 0
            var i = open
            while (i < chunk.length) {
                when (val c = chunk[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                    '"' -> {
                        val close = chunk.indexOf('"', i + 1)
                        check(close > i) { "execSQL 里的字符串字面量没有闭合: $chunk" }
                        sql.append(chunk, i + 1, close)
                        i = close
                    }
                    else -> check(!c.isLetterOrDigit()) { "execSQL 里出现了未解析的代码: $c" }
                }
                i++
            }
            statements += sql.toString()
            cursor = chunk.indexOf(EXEC_SQL, i)
        }
        return statements
    }

    private fun stripComments(source: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, " "), " ")

    private fun resolveModuleRoot(): File {
        val visited = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(dir, File(dir, "app"))) {
                visited += candidate
                val source = File(candidate, DATABASE_SOURCE)
                val schemas = File(candidate, SCHEMA_DIR)
                if (source.isFile && schemas.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到模块根目录（应同时含 $DATABASE_SOURCE 与 $SCHEMA_DIR）。" +
                "user.dir = ${System.getProperty("user.dir")}，找过: $visited"
        )
    }

    private data class Column(val affinity: String, val notNull: Boolean)

    private companion object {
        const val DATABASE_SOURCE = "src/main/java/com/zilv/app/data/local/ZilvDatabase.kt"
        const val SCHEMA_DIR = "schemas/com.zilv.app.data.local.ZilvDatabase"
        const val GENERATED_PACKAGE = "com.zilv.app.data.local"
        const val EXEC_SQL = "execSQL("

        val VERSION_JSON = Regex("""(\d+)\.json""")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val RE_VERSION = Regex("""\bversion\s*=\s*(\d+)""")
        val RE_AUTO_MIGRATION =
            Regex("""AutoMigration\s*\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)\s*\)""")
        val RE_MIGRATION_DECL = Regex(""":\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val RE_ENTITY_CLASS = Regex("""(\w+)::class""")
        val RE_VAL_MIGRATION = Regex("""val\s+MIGRATION_(\d+)_(\d+)""")
        val SPLIT_BEFORE_VAL_MIGRATION = Regex("""(?=val\s+MIGRATION_\d+_\d+)""")
        val RE_ADD_COLUMN = Regex(
            """ALTER\s+TABLE\s+([a-zA-Z_]\w*)\s+ADD\s+COLUMN\s+([a-zA-Z_]\w*)""",
            RegexOption.IGNORE_CASE
        )
        val RE_CREATE_INDEX = Regex(
            """CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-zA-Z_]\w*)\s+ON\s+([a-zA-Z_]\w*)""",
            RegexOption.IGNORE_CASE
        )
        val RE_TABLE_NAME = Regex("\"tableName\":\\s*\"([a-zA-Z_]\\w*)\"")
        val RE_FIELD_OBJECT = Regex("""\{[^{}]*\}""")
        val RE_COLUMN_NAME = Regex("\"columnName\":\\s*\"([^\"]+)\"")
        val RE_AFFINITY = Regex("\"affinity\":\\s*\"([^\"]+)\"")
        val RE_NOT_NULL = Regex("\"notNull\":\\s*(true|false)")
        val RE_INDEX_NAME = Regex("\"name\":\\s*\"(index_[a-zA-Z_]\\w*)\"")
    }
}

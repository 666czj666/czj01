package com.zilv.app.domain.backup

import com.zilv.app.data.mapper.AllowApp
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.Spot
import com.zilv.app.domain.permission.PermissionStep
import com.zilv.app.domain.plan.Plan
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota

/**
 * 数据备份 / 恢复（PRD §1「个人自用版必须保留但可简化的功能」）。
 *
 * 个人自用没人帮你恢复数据，丢了就是丢了 —— 所以备份必须做。
 *
 * 设计取舍：
 *  - **导出格式用自定义的纯文本行式格式，不用 JSON**：本项目全程避免在 JVM 单测里
 *    依赖 Android 的 org.json，也不引入序列化库。行式格式同样可读、可手改、可 diff。
 *  - 只导出**配置**（习惯、规则、打卡点、白名单、额度），不导出使用时长与拦截日志 ——
 *    那些是观测数据，体积大且没有恢复价值。
 */
object DataBackup {

    /**
     * 备份格式版本。
     *
     * v3（v2.7，本次改动）：新增 `quota` 记录（软件使用时间限时额度）。
     *
     * ### 为什么 v2.7 必须再升一次（2 → 3）
     *
     * 与 v2 的理由**逐字相同**，只是换成了额度：
     *  - **向上兼容**：新版读得懂旧版（v1/v2 备份里根本没有 `quota` 行 →
     *    额度列表为空，其余数据照常恢复），所以老备份不会失效。
     *  - **向下拒绝**：旧版 App 读 v3 备份时，`quota` 行会被它静默丢掉 ——
     *    而额度是**用户亲手配的限制**，"恢复了但限时没了"意味着
     *    用户以为自己还有限时保护，实际没有。这比"直接告诉你版本不兼容"危险得多。
     *    升到 3 之后，旧版会走 `version > FORMAT_VERSION` 那条路原样拒绝导入，
     *    符合本项目"拒绝而不是猜"的一贯做法。
     *
     * 注意 `FORMAT_VERSION` 与数据库版本号是**两件独立的事**：
     * 前者是备份文件的对外契约，后者是 Room schema 的版本；
     * 两者这次都 +1 只是巧合（额度同时带来了新表与新记录类型）。
     */
    const val FORMAT_VERSION = 3
    const val HEADER = "zilv-backup"
    private const val FIELD_SEP = "\u0001"
    private const val RECORD_SEP = "\n"

    /** 可备份的全部配置。 */
    data class Snapshot(
        val habits: List<Habit> = emptyList(),
        val rules: List<LockRule> = emptyList(),
        val spots: List<Spot> = emptyList(),
        val allowApps: List<AllowApp> = emptyList(),
        val manualPermissionSteps: List<PermissionStep> = emptyList(),
        /**
         * 按天留言。
         *
         * 用户是在"所有任务已完成"之后写下这些字的，属于**自己写的记录**
         * 而不是观测数据，所以必须跟习惯一起备份 —— 丢了就是丢了。
         */
        val dayNotes: List<DayNoteBackup> = emptyList(),
        /**
         * 「明日规划」（v2.4）。
         *
         * 与留言同理：这是用户自己排的计划，属于**自己写的记录**，
         * 不是观测数据，必须一起备份。
         */
        val plans: List<Plan> = emptyList(),
        /**
         * 「软件使用时间限时」额度（v2.7）。
         *
         * 与规划同理：这是用户**亲手配的限制**，不是观测数据。
         * 丢了不只是"少了个配置"，而是"用户以为自己还被限着，其实没有" ——
         * 那正是这个功能存在的意义被悄悄抹掉。
         */
        val quotas: List<UsageQuota> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = habits.isEmpty() && rules.isEmpty() && spots.isEmpty() &&
                allowApps.isEmpty() && manualPermissionSteps.isEmpty() &&
                dayNotes.isEmpty() && plans.isEmpty() && quotas.isEmpty()
    }

    /**
     * 备份里的一条按天留言。
     *
     * 定义为 [DataBackup] 内部的独立类型而不是复用 `data.repository.DayNote`：
     * 备份格式是**对外契约**，不该被内部领域模型的重构牵着走。
     */
    data class DayNoteBackup(
        val epochDay: Long,
        val text: String,
        val updatedAt: Long
    )

    /**
     * 序列化。
     *
     * 格式：首行 `zilv-backup<US>1`，其后每行 `类型<US>字段1<US>字段2...`。
     * 字段内不允许出现分隔符（写入前会被替换掉）。
     */
    fun encode(snapshot: Snapshot): String {
        val sb = StringBuilder()
        sb.append(HEADER).append(FIELD_SEP).append(FORMAT_VERSION).append(RECORD_SEP)

        snapshot.habits.forEach { h ->
            sb.append("habit").append(FIELD_SEP)
                .append(clean(h.name)).append(FIELD_SEP)
                .append(h.type.name).append(FIELD_SEP)
                .append(h.frequency.let { enc ->
                    when (enc) {
                        is com.zilv.app.domain.habit.HabitFrequency.Daily -> "DAILY"
                        // v2.14：「每周 N 次」已被删除，这里不再写 WEEKLY（只在解码侧保留兼容）。
                        // v2.13：「指定每月几号」取代了 v2.4 的「每月 N 次」。
                        // 这里只写新格式 MONTH_DAYS；MONTHLY 只在解码侧保留（旧备份兼容）。
                        is com.zilv.app.domain.habit.HabitFrequency.SpecificMonthDays ->
                            "MONTH_DAYS:" + enc.days.sorted().joinToString(",") { it.toString() }

                        is com.zilv.app.domain.habit.HabitFrequency.SpecificDays ->
                            "DAYS:" + enc.days.sortedBy { d -> d.value }.joinToString(",") { it.value.toString() }
                    }
                }).append(FIELD_SEP)
                .append(h.remindTimes.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) })
                .append(FIELD_SEP)
                .append(h.spotId?.toString().orEmpty())
                .append(RECORD_SEP)
        }

        snapshot.rules.forEach { r ->
            sb.append("rule").append(FIELD_SEP)
                .append(clean(r.name)).append(FIELD_SEP)
                .append(r.enabled).append(FIELD_SEP)
                .append(r.daysOfWeek.sortedBy { it.value }.joinToString(",") { it.value.toString() })
                .append(FIELD_SEP)
                .append(r.windows.joinToString(",") { "${it.startMinute}-${it.endMinute}" })
                .append(FIELD_SEP)
                .append(r.unlockMode.name).append(FIELD_SEP)
                .append(r.spotId?.toString().orEmpty()).append(FIELD_SEP)
                .append(r.earlyCheckInMinutes).append(FIELD_SEP)
                .append(r.graceMinutes).append(FIELD_SEP)
                .append(r.strictMode).append(FIELD_SEP)
                .append(clean(r.message))
                // ---- v2.4 追加的两个**尾部**字段（老备份里没有，解码时取默认值）----
                // 理由：这两个字段一旦丢失，规则的解锁行为会**静默变松**
                // （allowCheckInUnlock=false 的规则会变回"打卡可解锁"），
                // 而这正是用户明确要求"仅到点解锁"的那条规则。
                .append(FIELD_SEP).append(r.allowCheckInUnlock)
                .append(FIELD_SEP).append(r.planId?.toString().orEmpty())
                .append(RECORD_SEP)
        }

        snapshot.spots.forEach { s ->
            sb.append("spot").append(FIELD_SEP)
                .append(clean(s.name)).append(FIELD_SEP)
                .append(s.lat).append(FIELD_SEP)
                .append(s.lng).append(FIELD_SEP)
                .append(s.radiusM).append(FIELD_SEP)
                .append(s.minAccuracyM).append(FIELD_SEP)
                .append(clean(s.address.orEmpty()))
                .append(RECORD_SEP)
        }

        snapshot.allowApps.forEach { a ->
            sb.append("allow").append(FIELD_SEP)
                .append(clean(a.packageName)).append(FIELD_SEP)
                .append(clean(a.label)).append(FIELD_SEP)
                .append(a.builtin).append(FIELD_SEP)
                .append(a.enabled)
                .append(RECORD_SEP)
        }

        snapshot.manualPermissionSteps.forEach { step ->
            sb.append("permission").append(FIELD_SEP)
                .append(step.name)
                .append(RECORD_SEP)
        }

        snapshot.dayNotes.forEach { note ->
            sb.append("note").append(FIELD_SEP)
                .append(note.epochDay).append(FIELD_SEP)
                .append(escapeNote(note.text)).append(FIELD_SEP)
                .append(note.updatedAt)
                .append(RECORD_SEP)
        }

        // v2.4：明日规划。规划名与留言一样可能被用户随手写上换行，
        // 复用同一套转义（往返测试覆盖）。
        snapshot.plans.forEach { plan ->
            sb.append("plan").append(FIELD_SEP)
                .append(escapeNote(plan.name)).append(FIELD_SEP)
                .append(plan.date.toEpochDay()).append(FIELD_SEP)
                .append(plan.startMinute).append(FIELD_SEP)
                .append(plan.endMinute).append(FIELD_SEP)
                .append(plan.createdAt)
                .append(RECORD_SEP)
        }

        // v2.7：使用时间限时额度。
        //
        // 格式：`quota / name / limitMinutes / resetPeriod / enabled / createdAt / pkg,pkg,...`
        //
        // 绑定关系编码成**一个字段**里的逗号分隔列表，而不是一行一个应用：
        //  - 额度与它的应用集合是"一个整体"，拆成多行会让"额度行与应用行
        //    不成对出现"成为一种可能（备份被手工编辑过时尤其容易）；
        //  - 包名里不可能出现逗号（Android 包名只允许字母数字下划线和点），
        //    所以这个分隔符是安全的，不需要额外转义。
        // 名称用与留言/规划同一套 [escapeNote]，额度名用户可以随手写。
        snapshot.quotas.forEach { quota ->
            sb.append("quota").append(FIELD_SEP)
                .append(escapeNote(quota.name)).append(FIELD_SEP)
                .append(quota.limitMinutes).append(FIELD_SEP)
                .append(quota.resetPeriod.name).append(FIELD_SEP)
                .append(quota.enabled).append(FIELD_SEP)
                .append(quota.createdAt).append(FIELD_SEP)
                .append(quota.effectivePackages.sorted().joinToString(","))
                .append(RECORD_SEP)
        }

        return sb.toString()
    }

    /**
     * 反序列化。
     *
     * 容错策略：**逐行解析，坏行跳过**，不因为一行脏数据丢掉整份备份。
     * 头部不匹配则返回 null（调用方提示"这不是本应用的备份文件"）。
     */
    fun decode(text: String): Snapshot? {
        val lines = text.split(RECORD_SEP).filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val header = lines.first().split(FIELD_SEP)
        if (header.size < 2 || header[0] != HEADER) return null
        val version = header[1].toIntOrNull() ?: return null
        if (version > FORMAT_VERSION) return null // 来自更新版本的备份，拒绝而不是猜

        val habits = mutableListOf<Habit>()
        val rules = mutableListOf<LockRule>()
        val spots = mutableListOf<Spot>()
        val allows = mutableListOf<AllowApp>()
        val steps = mutableListOf<PermissionStep>()
        val notes = mutableListOf<DayNoteBackup>()
        val plans = mutableListOf<Plan>()
        val quotas = mutableListOf<UsageQuota>()

        lines.drop(1).forEach { line ->
            val f = line.split(FIELD_SEP)
            runCatching {
                when (f.getOrNull(0)) {
                    "habit" -> decodeHabit(f)?.let { habits += it }
                    "rule" -> decodeRule(f)?.let { rules += it }
                    "spot" -> decodeSpot(f)?.let { spots += it }
                    "allow" -> decodeAllow(f)?.let { allows += it }
                    "permission" -> f.getOrNull(1)
                        ?.let { runCatching { PermissionStep.valueOf(it) }.getOrNull() }
                        ?.let { steps += it }

                    "note" -> decodeNote(f)?.let { notes += it }
                    "plan" -> decodePlan(f)?.let { plans += it }
                    "quota" -> decodeQuota(f)?.let { quotas += it }
                }
            }
        }

        return Snapshot(
            habits = habits,
            rules = rules,
            spots = spots,
            allowApps = allows,
            manualPermissionSteps = steps,
            dayNotes = notes,
            plans = plans,
            quotas = quotas
        )
    }

    // ---------------------------------------------------------------- 逐行解码

    private fun decodeHabit(f: List<String>): Habit? {
        // 编码产生 6 个字段：habit / name / type / frequency / remindTimes / spotId
        if (f.size < 6) return null
        val name = f[1]
        if (name.isBlank()) return null
        return Habit(
            name = name,
            type = com.zilv.app.domain.model.HabitType.entries
                .firstOrNull { it.name == f[2] } ?: com.zilv.app.domain.model.HabitType.SIMPLE,
            frequency = decodeFrequency(f[3]),
            remindTimes = f[4].split(',')
                .mapNotNull { t ->
                    val p = t.trim().split(':')
                    if (p.size != 2) null else runCatching {
                        java.time.LocalTime.of(p[0].toInt(), p[1].toInt())
                    }.getOrNull()
                },
            spotId = f[5].toLongOrNull()
        )
    }

    private fun decodeFrequency(raw: String) = when {
        // v1 的「每周 N 次」。v2.14 已经没有这个类型了，但**旧备份文件里一定有**，
        // 所以必须能读、且不能崩。映射规则直接复用编解码器里那一个：
        // WEEKLY:n → 指定星期「周一..第 n 天」（次数不变、任何周都排得下）。
        // 详见 HabitFrequencyCodec 的 KDoc。
        raw.startsWith("WEEKLY:") ->
            com.zilv.app.domain.habit.HabitFrequencyCodec
                .legacyWeeklyTimesToSpecificDays(raw.removePrefix("WEEKLY:"))

        // v2.13：新的「指定每月几号」。与 HabitFrequencyCodec 同一套口径：
        // 越界日期在过滤阶段被丢掉，全都非法则退化为「每天」。
        raw.startsWith("MONTH_DAYS:") ->
            com.zilv.app.domain.habit.HabitFrequencyCodec.decode(
                com.zilv.app.domain.habit.HabitFrequencyCodec.TYPE_MONTH_DAYS,
                raw.removePrefix("MONTH_DAYS:")
            )

        // v2.4 的旧备份格式（「每月 N 次」）。v2.13 已经没有这个类型了，
        // 但**旧备份文件里一定有**，所以必须能读、且不能崩。
        // 映射规则直接复用编解码器里那一个：MONTHLY:n → 指定每月 1..n 号
        // （次数不变、任何月份都排得下）。详见 HabitFrequencyCodec 的 KDoc。
        raw.startsWith("MONTHLY:") ->
            com.zilv.app.domain.habit.HabitFrequencyCodec
                .legacyMonthlyTimesToMonthDays(raw.removePrefix("MONTHLY:"))

        raw.startsWith("DAYS:") ->
            com.zilv.app.domain.habit.HabitFrequency.SpecificDays(
                raw.removePrefix("DAYS:").split(',')
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it in 1..7 }
                    .map { java.time.DayOfWeek.of(it) }
                    .toSet()
            )

        else -> com.zilv.app.domain.habit.HabitFrequency.Daily
    }

    private fun decodeRule(f: List<String>): LockRule? {
        if (f.size < 10) return null
        val name = f[1]
        if (name.isBlank()) return null
        val windows = f[4].split(',').mapNotNull { w ->
            val p = w.split('-')
            if (p.size != 2) return@mapNotNull null
            val s = p[0].toIntOrNull() ?: return@mapNotNull null
            val e = p[1].toIntOrNull() ?: return@mapNotNull null
            runCatching { com.zilv.app.domain.lock.TimeWindow(s, e) }.getOrNull()
        }
        if (windows.isEmpty()) return null
        return LockRule(
            name = name,
            enabled = f[2].toBooleanStrictOrNull() ?: true,
            daysOfWeek = f[3].split(',').mapNotNull { it.toIntOrNull() }
                .filter { it in 1..7 }.map { java.time.DayOfWeek.of(it) }.toSet(),
            windows = windows,
            unlockMode = com.zilv.app.domain.lock.UnlockMode.entries
                .firstOrNull { it.name == f[5] } ?: com.zilv.app.domain.lock.UnlockMode.TIME_ONLY,
            spotId = f[6].toLongOrNull(),
            earlyCheckInMinutes = f[7].toIntOrNull() ?: 0,
            graceMinutes = f[8].toIntOrNull() ?: 0,
            strictMode = f[9].toBooleanStrictOrNull() ?: false,
            message = f.getOrNull(10).orEmpty(),
            // v2.4 的尾部字段：老备份里没有 -> 取默认值（true / null），
            // 即"打卡可解锁"，与这些规则原本的行为一致。
            allowCheckInUnlock = f.getOrNull(11)?.toBooleanStrictOrNull() ?: true,
            planId = f.getOrNull(12)?.toLongOrNull()
        )
    }

    /**
     * 解码一条规划：`plan / name / epochDay / startMinute / endMinute / createdAt`。
     *
     * 与留言一样逐字段校验，坏行跳过；时间区间不合法的规划直接丢弃 ——
     * 宁可少一条规划，也不要往库里塞一条"起止相同"（会被 [TimeWindow] 当成
     * 全天锁机）或"跨 0 点"的规划。
     */
    private fun decodePlan(f: List<String>): Plan? {
        if (f.size < 6) return null
        val name = unescapeNote(f[1]).trim()
        if (name.isEmpty()) return null
        val epochDay = f[2].toLongOrNull() ?: return null
        val startMinute = f[3].toIntOrNull() ?: return null
        val endMinute = f[4].toIntOrNull() ?: return null
        val plan = Plan(
            name = name,
            date = runCatching { java.time.LocalDate.ofEpochDay(epochDay) }.getOrNull()
                ?: return null,
            startMinute = startMinute,
            endMinute = endMinute,
            createdAt = f[5].toLongOrNull() ?: 0L
        )
        return if (plan.isValid) plan else null
    }

    /**
     * 解码一条额度：`quota / name / limitMinutes / resetPeriod / enabled / createdAt / pkgs`。
     *
     * 与规划一样逐字段校验，坏行跳过；**不合法的额度直接丢弃** ——
     * 宁可少一条额度，也不要往库里塞一条"没有名称""时长越界"
     * 或者"一个应用都没绑"的额度：
     *  - 没绑应用的额度永远不会生效（拦截判定里 `packages.isEmpty()` 直接跳过），
     *    留着只会让用户在列表里看到一条"看起来有、实际没用"的配置；
     *  - 时长越界同理，会被 `UsageQuota.isValid` 判假。
     *
     * `id` 一律置 0：额度必须作为**新记录**插入，否则两条额度会争同一个主键。
     * `createdAt` 沿用备份里的值（它是"这个配置什么时候建的"，恢复不该改它）。
     */
    private fun decodeQuota(f: List<String>): UsageQuota? {
        if (f.size < 7) return null
        val name = unescapeNote(f[1]).trim()
        if (name.isEmpty()) return null
        val limitMinutes = f[2].toIntOrNull() ?: return null
        val resetPeriod = runCatching { ResetPeriod.valueOf(f[3].trim().uppercase()) }
            .getOrNull() ?: ResetPeriod.DAILY
        val enabled = f[4].toBooleanStrictOrNull() ?: true
        val quota = UsageQuota(
            id = 0L,
            name = name,
            limitMinutes = limitMinutes,
            resetPeriod = resetPeriod,
            enabled = enabled,
            createdAt = f[5].toLongOrNull() ?: 0L,
            packageNames = f[6].split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        )
        return if (quota.isValid) quota else null
    }

    private fun decodeSpot(f: List<String>): Spot? {        if (f.size < 6) return null
        val name = f[1]
        if (name.isBlank()) return null
        val lat = f[2].toDoubleOrNull() ?: return null
        val lng = f[3].toDoubleOrNull() ?: return null
        val radius = f[4].toIntOrNull() ?: Spot.DEFAULT_RADIUS_M
        val accuracy = f[5].toFloatOrNull() ?: Spot.DEFAULT_MIN_ACCURACY_M
        return runCatching {
            Spot(
                name = name,
                lat = lat,
                lng = lng,
                radiusM = radius,
                minAccuracyM = accuracy,
                address = f.getOrNull(6)?.ifBlank { null }
            )
        }.getOrNull()
    }

    private fun decodeAllow(f: List<String>): AllowApp? {
        if (f.size < 5) return null
        val pkg = f[1]
        if (pkg.isBlank()) return null
        return AllowApp(
            packageName = pkg,
            label = f[2],
            builtin = f[3].toBooleanStrictOrNull() ?: false,
            enabled = f[4].toBooleanStrictOrNull() ?: true
        )
    }

    /**
     * 解码一条按天留言：`note / epochDay / text / updatedAt`。
     *
     * 空文本直接丢弃 —— 与 `DayNoteRepository.save` 的口径一致：
     * 空留言不存在，"有留言的日子"这个集合里不该混进没有内容的一天。
     */
    private fun decodeNote(f: List<String>): DayNoteBackup? {
        if (f.size < 4) return null
        val epochDay = f[1].toLongOrNull() ?: return null
        val text = unescapeNote(f[2]).trim()
        if (text.isEmpty()) return null
        return DayNoteBackup(
            epochDay = epochDay,
            text = text,
            updatedAt = f[3].toLongOrNull() ?: 0L
        )
    }

    /**
     * 留言正文的转义。
     *
     * 留言是**多行**输入的，而记录分隔符就是换行 —— 不转义的话一条留言会被
     * 拆成好几行，解码时只剩第一行。这里把换行写成字面量 `\n`，
     * 反斜杠自身先转义成 `\\`，两条规则合起来是可逆的（单测覆盖往返）。
     *
     * 字段分隔符 `\u0001` 与回车同样处理掉：它们出现的概率极低，
     * 与其发明第三套转义，不如直接丢弃。
     */
    private fun escapeNote(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")
            .replace(FIELD_SEP, " ")

    private fun unescapeNote(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> {
                        sb.append('\n'); i += 2; continue
                    }

                    '\\' -> {
                        sb.append('\\'); i += 2; continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 去掉字段内的分隔符，避免破坏行结构。 */
    private fun clean(value: String): String =
        value.replace(FIELD_SEP, " ").replace(RECORD_SEP, " ")
}

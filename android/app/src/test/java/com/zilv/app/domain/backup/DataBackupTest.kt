package com.zilv.app.domain.backup

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.AllowApp
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.domain.model.Spot
import com.zilv.app.domain.permission.PermissionStep
import com.zilv.app.domain.plan.Plan
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 数据备份的单元测试（PRD M7 T7.2）。
 *
 * 个人自用没人帮你恢复数据，所以"导出再导入必须一模一样"是本项目
 * 少数几个**不允许有任何偏差**的功能之一。
 */
class DataBackupTest {

    private fun sampleSnapshot() = DataBackup.Snapshot(
        habits = listOf(
            Habit(
                name = "早起",
                type = HabitType.SIMPLE,
                frequency = HabitFrequency.Daily,
                remindTimes = listOf(LocalTime.of(7, 30))
            ),
            Habit(
                name = "到图书馆",
                type = HabitType.LOCATION,
                frequency = HabitFrequency.SpecificDays(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                ),
                remindTimes = listOf(LocalTime.of(7, 0), LocalTime.of(13, 0)),
                spotId = 3L
            ),
            // v2.14 改写：这个 fixture 原来用的是已被删除的 `WeeklyTimes(3)`。
            // 归类：**测试错（旧规格）** —— 被测类型整体删除。
            // 换成「指定每月几号」，它同样能证明"第三种频率也能往返"。
            Habit(name = "健身", frequency = HabitFrequency.SpecificMonthDays(setOf(1, 15, 31)))
        ),
        rules = listOf(
            LockRule(
                name = "早课",
                enabled = true,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                windows = listOf(TimeWindow(7 * 60, 9 * 60), TimeWindow(22 * 60 + 30, 7 * 60)),
                unlockMode = UnlockMode.SPOT_CHECKIN,
                spotId = 3L,
                earlyCheckInMinutes = 20,
                graceMinutes = 5,
                strictMode = true,
                message = "去图书馆，别在这刷手机"
            )
        ),
        spots = listOf(
            Spot(name = "三教一楼", lat = 30.123456, lng = 120.654321, radiusM = 150, minAccuracyM = 40f)
        ),
        allowApps = listOf(
            AllowApp("com.android.systemui", "系统界面", builtin = true, enabled = true),
            AllowApp("com.tencent.mm", "微信", builtin = false, enabled = true)
        ),
        manualPermissionSteps = listOf(
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.AUTO_START
        ),
        dayNotes = listOf(
            // 20740 = 2026-10-14，20741 = 2026-10-15
            DataBackup.DayNoteBackup(epochDay = 20740L, text = "今天很稳", updatedAt = 1000L),
            DataBackup.DayNoteBackup(epochDay = 20741L, text = "第二行\n也写上了", updatedAt = 2000L)
        )
    )

    // ---------------------------------------------------------------- 往返

    @Test
    fun `习惯往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        assertThat(decoded.habits).hasSize(3)

        val first = decoded.habits[0]
        assertThat(first.name).isEqualTo("早起")
        assertThat(first.type).isEqualTo(HabitType.SIMPLE)
        assertThat(first.frequency).isEqualTo(HabitFrequency.Daily)
        assertThat(first.remindTimes).containsExactly(LocalTime.of(7, 30))

        val second = decoded.habits[1]
        assertThat(second.name).isEqualTo("到图书馆")
        assertThat(second.type).isEqualTo(HabitType.LOCATION)
        assertThat(second.frequency).isEqualTo(
            HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        )
        assertThat(second.remindTimes).containsExactly(LocalTime.of(7, 0), LocalTime.of(13, 0)).inOrder()
        assertThat(second.spotId).isEqualTo(3L)

        val third = decoded.habits[2]
        assertThat(third.frequency)
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 15, 31)))
    }

    @Test
    fun `规则往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        val rule = decoded.rules.single()

        assertThat(rule.name).isEqualTo("早课")
        assertThat(rule.enabled).isTrue()
        assertThat(rule.daysOfWeek).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertThat(rule.windows).containsExactly(
            TimeWindow(7 * 60, 9 * 60),
            TimeWindow(22 * 60 + 30, 7 * 60)
        ).inOrder()
        assertThat(rule.unlockMode).isEqualTo(UnlockMode.SPOT_CHECKIN)
        assertThat(rule.spotId).isEqualTo(3L)
        assertThat(rule.earlyCheckInMinutes).isEqualTo(20)
        assertThat(rule.graceMinutes).isEqualTo(5)
        assertThat(rule.strictMode).isTrue()
        assertThat(rule.message).isEqualTo("去图书馆，别在这刷手机")
    }

    @Test
    fun `打卡点往返一致（含浮点精度）`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        val spot = decoded.spots.single()
        assertThat(spot.name).isEqualTo("三教一楼")
        assertThat(spot.lat).isEqualTo(30.123456)
        assertThat(spot.lng).isEqualTo(120.654321)
        assertThat(spot.radiusM).isEqualTo(150)
        assertThat(spot.minAccuracyM).isEqualTo(40f)
    }

    @Test
    fun `白名单往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        assertThat(decoded.allowApps).hasSize(2)
        assertThat(decoded.allowApps[0].packageName).isEqualTo("com.android.systemui")
        assertThat(decoded.allowApps[0].builtin).isTrue()
        assertThat(decoded.allowApps[1].builtin).isFalse()
        assertThat(decoded.allowApps[1].label).isEqualTo("微信")
    }

    @Test
    fun `手动确认的权限步骤往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        assertThat(decoded.manualPermissionSteps)
            .containsExactly(PermissionStep.RESTRICTED_SETTINGS, PermissionStep.AUTO_START)
    }

    @Test
    fun `空快照也能往返`() {
        val decoded = DataBackup.decode(DataBackup.encode(DataBackup.Snapshot()))!!
        assertThat(decoded.isEmpty).isTrue()
    }

    // ---------------------------------------------------------------- 留言
    //
    // 用户要求："点「所有任务已完成」可以留言"。留言是用户自己写下的东西，
    // 必须跟习惯一起备份 —— 丢了就是丢了。

    @Test
    fun `留言往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(sampleSnapshot()))!!
        assertThat(decoded.dayNotes).hasSize(2)

        assertThat(decoded.dayNotes[0].epochDay).isEqualTo(20740L)
        assertThat(decoded.dayNotes[0].text).isEqualTo("今天很稳")
        assertThat(decoded.dayNotes[0].updatedAt).isEqualTo(1000L)

        assertThat(decoded.dayNotes[1].epochDay).isEqualTo(20741L)
        assertThat(decoded.dayNotes[1].updatedAt).isEqualTo(2000L)
    }

    @Test
    fun `多行留言往返后换行仍在，且没有把一条拆成两行`() {
        val text = DataBackup.encode(sampleSnapshot())
        // 头部 + 3 条习惯 + 1 条规则 + 1 个打卡点 + 2 条白名单 +
        // 2 个权限步骤 + 2 条留言 = 12 行
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(12)

        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.dayNotes[1].text).isEqualTo("第二行\n也写上了")
    }

    @Test
    fun `只有留言的快照不算空`() {
        val snapshot = DataBackup.Snapshot(
            dayNotes = listOf(DataBackup.DayNoteBackup(epochDay = 20740L, text = "就写一句", updatedAt = 1L))
        )
        assertThat(snapshot.isEmpty).isFalse()
        assertThat(DataBackup.decode(DataBackup.encode(snapshot))!!.dayNotes).hasSize(1)
    }

    @Test
    fun `留言里的反斜杠能原样往返`() {
        val snapshot = DataBackup.Snapshot(
            dayNotes = listOf(
                DataBackup.DayNoteBackup(epochDay = 20740L, text = "路径 C:\\tmp 与字面量 \\n", updatedAt = 1L)
            )
        )
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.dayNotes.single().text).isEqualTo("路径 C:\\tmp 与字面量 \\n")
    }

    @Test
    fun `留言里的字段分隔符被清理，不会破坏行结构`() {
        val snapshot = DataBackup.Snapshot(
            dayNotes = listOf(
                DataBackup.DayNoteBackup(epochDay = 20740L, text = "前\u0001后", updatedAt = 1L)
            )
        )
        val text = DataBackup.encode(snapshot)
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(2)

        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.dayNotes.single().text).doesNotContain("\u0001")
    }

    @Test
    fun `坏留言行被跳过而不影响好行`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("note\u0001正常留言\u0001内容\u0001123\n")   // epochDay 不是数字 -> 跳过
            append("note\u000120740\u0001\u0001123\n")          // 空文本 -> 跳过
            append("note\u000120741\n")                          // 字段不足 -> 跳过
            append("note\u000120742\u0001好留言\u0001456\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.dayNotes.map { it.text }).containsExactly("好留言")
        assertThat(decoded.dayNotes.single().epochDay).isEqualTo(20742L)
    }

    @Test
    fun `导出内容里能看到留言`() {
        assertThat(DataBackup.encode(sampleSnapshot())).contains("note")
    }

    @Test
    fun `快照非空判定`() {
        assertThat(DataBackup.Snapshot().isEmpty).isTrue()
        assertThat(sampleSnapshot().isEmpty).isFalse()
    }

    // ---------------------------------------------------------------- 容错

    @Test
    fun `头部不匹配时返回 null`() {
        assertThat(DataBackup.decode("something-else\u00011\nhabit\u0001x")).isNull()
        assertThat(DataBackup.decode("")).isNull()
        assertThat(DataBackup.decode("   ")).isNull()
    }

    @Test
    fun `来自更高版本的备份被拒绝而不是猜测`() {
        val future = "${DataBackup.HEADER}\u0001${DataBackup.FORMAT_VERSION + 1}\n"
        assertThat(DataBackup.decode(future)).isNull()
    }

    @Test
    fun `坏行被跳过而不影响好行`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("habit\u0001正常习惯\u0001SIMPLE\u0001DAILY\u0001\u0001\n")
            append("habit\u0001\u0001SIMPLE\u0001DAILY\u0001\u0001\n")          // 空名称 -> 跳过
            append("habit\u0001缺字段\n")                                       // 字段不足 -> 跳过
            append("garbage-line\n")                                            // 未知类型 -> 跳过
            append("habit\u0001第二个正常\u0001SIMPLE\u0001WEEKLY:5\u0001\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits.map { it.name })
            .containsExactly("正常习惯", "第二个正常").inOrder()
        // v2.14 改写：`WEEKLY:5` 是**旧备份文件里真实存在**的编码，
        // 现在仍然必须能读（不能崩、不能丢行）——
        // 但解码结果从已删除的 `WeeklyTimes(5)` 变成「指定星期 周一..周五」。
        // 归类：**测试错（旧规格）**。
        assertThat(decoded.habits[1].frequency).isEqualTo(
            HabitFrequency.SpecificDays(
                setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY
                )
            )
        )
    }

    @Test
    fun `字段里含分隔符时被清理，不会破坏行结构`() {
        val nasty = DataBackup.Snapshot(
            habits = listOf(Habit(name = "名字\n里有换行\u0001和分隔符"))
        )
        val text = DataBackup.encode(nasty)
        // 编码后行数应当是 头部 + 1 条记录
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(2)

        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits).hasSize(1)
        assertThat(decoded.habits[0].name).doesNotContain("\u0001")
    }

    @Test
    fun `无窗口的规则在导入时被跳过`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("rule\u0001没窗口的规则\u0001true\u0001\u0001\u0001TIME_ONLY\u0001\u000115\u00010\u0001false\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.rules).isEmpty()
    }

    @Test
    fun `非法坐标的打卡点被跳过`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("spot\u0001坏点\u000191.0\u0001120.0\u0001100\u000150\u0001\n")
            append("spot\u0001好点\u000130.0\u0001120.0\u0001100\u000150\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.spots.map { it.name }).containsExactly("好点")
    }

    @Test
    fun `未识别的解锁条件退化为仅定时`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("rule\u0001x\u0001true\u0001\u0001480-1080\u0001FUTURE_MODE\u0001\u000115\u00010\u0001false\u0001\n")
        }
        assertThat(DataBackup.decode(text)!!.rules.single().unlockMode).isEqualTo(UnlockMode.TIME_ONLY)
    }

    @Test
    fun `导出内容是人类可读的`() {
        val text = DataBackup.encode(sampleSnapshot())
        assertThat(text).startsWith(DataBackup.HEADER)
        assertThat(text).contains("habit")
        assertThat(text).contains("rule")
        assertThat(text).contains("spot")
        assertThat(text).contains("allow")
        assertThat(text).contains("早课")
        assertThat(text).contains("三教一楼")
    }

    // ------------------------------------ 指定每月几号（v2.13，取代「每月 N 次」）

    @Test
    fun `指定每月几号 - 频率往返一致`() {
        val snapshot = DataBackup.Snapshot(
            habits = listOf(
                Habit(
                    name = "剪头发",
                    frequency = HabitFrequency.SpecificMonthDays(setOf(1, 15, 31))
                )
            )
        )
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.habits.single().frequency)
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 15, 31)))
        // 新格式：MONTH_DAYS:1,15,31（升序）
        assertThat(DataBackup.encode(snapshot)).contains("MONTH_DAYS:1,15,31")
    }

    @Test
    fun `指定每月几号 - 只选 31 号也能往返一致`() {
        val snapshot = DataBackup.Snapshot(
            habits = listOf(
                Habit(name = "月末", frequency = HabitFrequency.SpecificMonthDays(setOf(31)))
            )
        )
        assertThat(DataBackup.encode(snapshot)).contains("MONTH_DAYS:31")
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.habits.single().frequency)
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(31)))
    }

    @Test
    fun `指定每月几号 - 越界日期被过滤而不是崩溃`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("habit\u0001混合\u0001SIMPLE\u0001MONTH_DAYS:0,1,32\u0001\u0001\n")
            append("habit\u0001全非法\u0001SIMPLE\u0001MONTH_DAYS:99\u0001\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits.map { it.frequency })
            .containsExactly(
                HabitFrequency.SpecificMonthDays(setOf(1)),
                HabitFrequency.Daily
            ).inOrder()
    }

    @Test
    fun `旧备份里的 MONTHLY 编码仍然读得出来（映射成每月 1 到 n 号）`() {
        // v2.13 删掉了「每月 N 次」，但**用户手上的旧备份文件里一定有 MONTHLY:n**。
        // 映射规则与 HabitFrequencyCodec 一致：MONTHLY:n → 指定每月 1..n 号
        // （次数不变、任何月份都排得下）。详见 HabitFrequencyCodec 的 KDoc。
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("habit\u0001剪头发\u0001SIMPLE\u0001MONTHLY:3\u0001\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits.single().frequency)
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 2, 3)))
    }

    @Test
    fun `旧备份里的 MONTHLY 越界值退化为每天而不是崩溃`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("habit\u0001越界习惯\u0001SIMPLE\u0001MONTHLY:99\u0001\u0001\n")
            append("habit\u0001非数字\u0001SIMPLE\u0001MONTHLY:abc\u0001\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits.map { it.frequency })
            .containsExactly(HabitFrequency.Daily, HabitFrequency.Daily).inOrder()
    }

    // ------------------------------------------------------------ 明日规划（v2.4）
    //
    // 规划是用户自己排的计划，与留言一样属于"自己写的记录"，必须跟着备份走。

    private fun planSnapshot() = DataBackup.Snapshot(
        plans = listOf(
            // 20742 = 2026-10-16
            Plan(
                name = "晨读",
                date = LocalDate.of(2026, 10, 16),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 1234L
            ),
            Plan(
                name = "跑步",
                date = LocalDate.of(2026, 10, 17),
                startMinute = 18 * 60,
                endMinute = 19 * 60,
                createdAt = 5678L
            )
        )
    )

    @Test
    fun `规划往返一致`() {
        val decoded = DataBackup.decode(DataBackup.encode(planSnapshot()))!!
        assertThat(decoded.plans).hasSize(2)

        val first = decoded.plans[0]
        assertThat(first.name).isEqualTo("晨读")
        assertThat(first.date).isEqualTo(LocalDate.of(2026, 10, 16))
        assertThat(first.startMinute).isEqualTo(540)
        assertThat(first.endMinute).isEqualTo(660)
        assertThat(first.createdAt).isEqualTo(1234L)
        // id 不导出：恢复时按新库重新分配
        assertThat(first.id).isEqualTo(0L)

        assertThat(decoded.plans[1].name).isEqualTo("跑步")
        assertThat(decoded.plans[1].date).isEqualTo(LocalDate.of(2026, 10, 17))
    }

    @Test
    fun `导出内容里能看到规划`() {
        val text = DataBackup.encode(planSnapshot())
        assertThat(text).contains("plan")
        assertThat(text).contains("晨读")
        // 行数 = 头部 + 2 条规划
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(3)
    }

    @Test
    fun `只有规划的快照不算空`() {
        assertThat(planSnapshot().isEmpty).isFalse()
        assertThat(DataBackup.decode(DataBackup.encode(planSnapshot()))!!.plans).hasSize(2)
    }

    @Test
    fun `规划名里的换行被转义，不会把一条拆成两行`() {
        val snapshot = DataBackup.Snapshot(
            plans = listOf(
                Plan(
                    name = "上午\n写论文",
                    date = LocalDate.of(2026, 10, 16),
                    startMinute = 9 * 60,
                    endMinute = 10 * 60,
                    createdAt = 1L
                )
            )
        )
        val text = DataBackup.encode(snapshot)
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(2)
        assertThat(DataBackup.decode(text)!!.plans.single().name).isEqualTo("上午\n写论文")
    }

    @Test
    fun `坏规划行被跳过而不影响好行`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("plan\u0001\u000120742\u0001540\u0001660\u00011\n")        // 空名称 -> 跳过
            append("plan\u0001跨0点\u000120742\u00011320\u0001360\u00011\n")  // 结束早于开始 -> 跳过
            append("plan\u0001全天\u000120742\u0001600\u0001600\u00011\n")    // 起止相同 -> 跳过
            append("plan\u0001越界\u000120742\u00010\u00011440\u00011\n")     // 越界 -> 跳过
            append("plan\u0001字段不足\u000120742\n")                          // 字段不足 -> 跳过
            append("plan\u0001非法日期\u0001abc\u0001540\u0001660\u00011\n")  // epochDay 非数字 -> 跳过
            append("plan\u0001好规划\u000120742\u0001540\u0001660\u00011\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.plans.map { it.name }).containsExactly("好规划")
        assertThat(decoded.plans.single().startMinute).isEqualTo(540)
    }

    // ------------------------------------------------------ 规则新字段（v2.4）

    @Test
    fun `规则 - 仅到点解锁的标记往返一致`() {
        val snapshot = DataBackup.Snapshot(
            rules = listOf(
                LockRule(
                    name = "规划锁机",
                    windows = listOf(TimeWindow(9 * 60, 11 * 60)),
                    allowCheckInUnlock = false,
                    planId = 7L
                )
            )
        )
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        val rule = decoded.rules.single()
        assertThat(rule.allowCheckInUnlock).isFalse()
        assertThat(rule.planId).isEqualTo(7L)
        assertThat(rule.isPlanRule).isTrue()
    }

    @Test
    fun `规则 - 老备份行没有尾部字段时默认打卡可解锁`() {
        // v1 的规则行只有 11 个字段（到 message 为止），没有 allowCheckInUnlock / planId
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(1).append('\n')
            append("rule\u0001旧规则\u0001true\u0001\u0001480-540\u0001TIME_ONLY\u0001\u00010\u00010\u0001false\u0001老备份\n")
        }
        val decoded = DataBackup.decode(text)!!
        val rule = decoded.rules.single()
        assertThat(rule.message).isEqualTo("老备份")
        // 默认值 = 与 v2.2 行为一致：打卡可以解锁
        assertThat(rule.allowCheckInUnlock).isTrue()
        assertThat(rule.planId).isNull()
        assertThat(rule.isPlanRule).isFalse()
    }

    @Test
    fun `旧版本的备份（v1）仍然能导入`() {
        val v1 = DataBackup.HEADER + "\u00011\n" +
            "habit\u0001早起\u0001SIMPLE\u0001DAILY\u0001\u0001\n" +
            "note\u000120740\u0001今天很稳\u00011000\n"
        val decoded = DataBackup.decode(v1)!!
        assertThat(decoded.habits.single().name).isEqualTo("早起")
        assertThat(decoded.dayNotes.single().text).isEqualTo("今天很稳")
        // 老备份里没有规划，读出来就是空 —— 不报错、也不凭空造数据
        assertThat(decoded.plans).isEmpty()
    }
}

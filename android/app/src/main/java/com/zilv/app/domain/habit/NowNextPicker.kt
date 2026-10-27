package com.zilv.app.domain.habit

import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.model.Habit
import java.time.LocalTime
import kotlin.math.abs

/**
 * 「当前 / 接下来」聚焦卡片（用户要求：今日列表**上方**显示当前习惯
 * 或距离当前最近的习惯）。
 *
 * 为什么做成纯函数：
 *  - "选哪条"的规则有好几条边界（提醒时间刚好等于现在、刚过 60 分钟、
 *    完全没有提醒时间），塞进 Compose 里就只能靠眼睛看，无法回归；
 *  - 本项目一贯做法：纯逻辑抽成纯函数 + 单测，Room 的运行时行为另由
 *    instrumented 测试覆盖。
 */
object NowNextPicker {

    /**
     * 一个提醒时间"还算数"的时间窗：已经过了多久仍在 `NOW` 里算数。
     *
     * 取 60 分钟的理由：这是"刚错过还不算太久，现在做还来得及"的量级。
     * 再久（比如两小时前的提醒）说"现在该做"就是撒谎了，那时会给 [Mode.NEXT]。
     */
    const val NOW_WINDOW_MINUTES = 60

    enum class Mode {
        /** 提醒时间刚过（[NOW_WINDOW_MINUTES] 以内）——「现在该做」 */
        NOW,

        /** 提醒时间还没到，或这条习惯根本没有提醒时间 ——「接下来」 */
        NEXT
    }

    /** 一条候选：某习惯的某一个提醒时刻。放在对象里而不是函数内，便于阅读与调试。 */
    private data class Candidate(val index: Int, val habit: Habit, val time: LocalTime)

    /**
     * 聚焦卡片的内容。
     *
     * @param habit 选中的习惯
     * @param mode 见 [Mode]
     * @param minutesUntil 距离该习惯提醒时间的**带符号分钟数**：
     *        正数 = 还有多久，负数 = 已经过了多久，0 = 就是现在。
     *        没有提醒时间时为 `null`（界面不要编造一个"还有 X 分钟"）。
     */
    data class NowNext(
        val habit: Habit,
        val mode: Mode,
        val minutesUntil: Int?
    ) {
        /** 有没有一个真实的提醒时间作为依据。 */
        val hasRemindTime: Boolean get() = minutesUntil != null

        /** 距离该习惯的下一分钟提示文案，例如「还有 1 小时 20 分钟」。 */
        val distanceLabel: String
            get() = minutesUntil?.let { formatDistance(it) }.orEmpty()
    }

    /**
     * 选出"当前该做 / 接下来要做"的那一条习惯。
     *
     * 语义（按优先级，逐条都保证确定性）：
     *  1. **只看未打卡的习惯**：已经打过卡的不该再出现在聚焦卡片里。
     *  2. 有提醒时间、且**已过但在 [NOW_WINDOW_MINUTES] 分钟以内** → [Mode.NOW]，
     *     且在这些候选里取**提醒时间最晚**的那条（＝最近刚开始的那件事）。
     *  3. 否则取提醒时间**在将来**里**最早**的那条 → [Mode.NEXT]。
     *  4. 都没有（全部没有提醒时间）→ 取**列表顺序最靠前**的未打卡习惯 → [Mode.NEXT]。
     *  5. 一条未打卡的都没有 → `null`（界面这时显示「所有任务已完成」横幅）。
     *
     * 有一条刻意保留的取舍：只有提醒时间落在 [先 -60 分钟, 现在] 区间内才算 `NOW`。
     * 一旦超出这个窗口，哪怕它是"唯一一条今天该做的"，也降级成 `NEXT`
     * （此时它仍然会被第 3 条挑中，如果它的提醒时间还没到）。
     *
     * 平手（同一时刻多条提醒）时按**列表顺序**取靠前的那条 —— 列表本身
     * 已按 `sort_order` 排好，这样结果与界面看到的第一条一致，不会"跳"。
     *
     * @param items 今日习惯列表（顺序即优先级）
     * @param checkedHabitIds 今天**已打卡**的习惯 id 集合
     * @param now 当前时刻（由调用方从注入的时钟取，测试可固定）
     */
    fun pick(
        items: List<TodayHabit>,
        checkedHabitIds: Set<Long>,
        now: LocalTime
    ): NowNext? {
        // 第 1 条：只看未打卡的。用 withIndex 保留列表顺序作为平手判据
        val pending = items.withIndex().filter { (_, item) ->
            !item.checkedToday && item.habit.id !in checkedHabitIds
        }
        if (pending.isEmpty()) return null

        // 摊平成 (列表下标, 习惯, 提醒时刻) 三元组，一个习惯多个提醒时间就有多条候选
        val timed = pending.flatMap { (index, item) ->
            item.habit.remindTimes.sorted().map { Candidate(index, item.habit, it) }
        }

        // 第 2 条：已过但还在窗口内 → NOW，取其中最晚的
        //
        // 比较器的次键写成**降序**：`maxWith` 取的是比较器意义上的最大值，
        // 而我们要"时间最晚；并列时列表最靠前"，所以下标必须反过来排。
        // （这一条是被单测抓出来的：写成升序时并列会选到列表末尾，
        //  与界面上看到的第一条不一致。）
        val nowCandidates = timed.filter { c ->
            val delta = minutesBetween(now, c.time)
            delta in -NOW_WINDOW_MINUTES..0
        }
        if (nowCandidates.isNotEmpty()) {
            val best = nowCandidates.maxWithOrNull(
                compareBy<Candidate> { it.time }.thenByDescending { it.index }
            )!!
            return NowNext(best.habit, Mode.NOW, minutesBetween(now, best.time))
        }

        // 第 3 条：将来最早的 → NEXT（并列时取列表靠前的，min 用升序次键即可）
        val futureCandidates = timed.filter { minutesBetween(now, it.time) > 0 }
        if (futureCandidates.isNotEmpty()) {
            val best = futureCandidates.minWithOrNull(
                compareBy<Candidate> { it.time }.thenBy { it.index }
            )!!
            return NowNext(best.habit, Mode.NEXT, minutesBetween(now, best.time))
        }

        // 第 4 条：没有任何可用的提醒时间 → 列表最靠前的未打卡习惯
        val (index, item) = pending.first()
        return NowNext(item.habit, Mode.NEXT, null)
    }

    /**
     * `from` 到 `to` 的带符号分钟数。正数表示 [to] 在将来。
     *
     * 用"当天内的分钟数"直接相减，不做跨天处理 —— 这是个**日视图**，
     * 提醒时间也只有 `HH:mm`，跨天没有意义。
     */
    fun minutesBetween(from: LocalTime, to: LocalTime): Int =
        (to.hour * 60 + to.minute) - (from.hour * 60 + from.minute)

    /**
     * 把带符号分钟数说成人话。
     *
     * 例：`80` → 「还有 1 小时 20 分钟」；`-12` → 「已经过了 12 分钟」；`0` → 「就是现在」。
     */
    fun formatDistance(minutesUntil: Int): String = when {
        minutesUntil == 0 -> "就是现在"
        minutesUntil > 0 -> "还有 " + humanize(minutesUntil)
        else -> "已经过了 " + humanize(abs(minutesUntil))
    }

    private fun humanize(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m 分钟"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分钟"
        }
    }
}

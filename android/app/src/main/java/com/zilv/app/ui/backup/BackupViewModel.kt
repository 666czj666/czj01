package com.zilv.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.data.settings.SettingsRepository
import com.zilv.app.domain.backup.DataBackup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    /** 导出的文本内容；UI 拿到后写入用户选择的文件 */
    val pendingExport: String? = null
)

/**
 * 数据备份与恢复（PRD M7 T7.2）。
 *
 * 只备份**配置**（习惯 / 规则 / 打卡点 / 白名单 / 手动确认的权限步骤），
 * 不备份使用时长与拦截日志 —— 那些是观测数据，体积大、没有恢复价值。
 */
class BackupViewModel(
    private val habitRepository: HabitRepository,
    private val lockRuleRepository: LockRuleRepository,
    private val spotRepository: SpotRepository,
    private val dayNoteRepository: DayNoteRepository,
    /**
     * 「明日规划」（v2.5 接线）。
     *
     * 规划是**用户自己排的计划**，与留言同理，属于"自己写的记录"而不是
     * 观测数据 —— 丢了就是丢了，必须跟着备份走。
     * `DataBackup` 侧（`FORMAT_VERSION = 2` 的 `plan` 记录）在上一批已经就绪，
     * 这里只负责把它接进导出/导入流程。
     */
    private val planRepository: PlanRepository,
    /**
     * 「软件使用时间限时」额度（v2.7 接线）。
     *
     * 与规划同理：额度是用户**亲手配的限制**，不是观测数据。
     * 丢了不只是"少了个配置"，而是"用户以为自己还被限着，其实没有" ——
     * 那正是这个功能存在的意义被悄悄抹掉。
     * `DataBackup` 侧（`FORMAT_VERSION = 3` 的 `quota` 记录）已就绪，
     * 这里只负责把它接进导出/导入流程。
     */
    private val quotaRepository: UsageQuotaRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 生成备份内容，交给 UI 写入文件。 */
    fun prepareExport() {
        viewModelScope.launch {
            _uiState.value = BackupUiState(busy = true)
            runCatching {
                val snapshot = DataBackup.Snapshot(
                    habits = habitRepository.getActiveHabits(),
                    rules = lockRuleRepository.getAllRules(),
                    spots = spotRepository.getAllSpots(),
                    allowApps = lockRuleRepository.getAllowedApps(),
                    manualPermissionSteps = settingsRepository.manualConfirmedStepsOnce().toList(),
                    // 留言是自己写下的记录，必须跟着走 —— 丢了就是丢了
                    dayNotes = dayNoteRepository.getAll().map {
                        DataBackup.DayNoteBackup(
                            epochDay = it.date.toEpochDay(),
                            text = it.text,
                            updatedAt = it.updatedAtMillis
                        )
                    },
                    // 明日规划同理：这是用户亲手排的计划，不是观测数据。
                    // getAll() 而不是 getForDay(明天)：历史规划也是用户的记录，
                    // 备份要尽量完整，恢复时再由仓库层决定哪些能落库。
                    plans = planRepository.getAll(),
                    // v2.7 使用时间限时：同样是"用户亲手配的限制"。
                    quotas = quotaRepository.getAll()
                )
                DataBackup.encode(snapshot)
            }.onSuccess {
                _uiState.value = BackupUiState(pendingExport = it)
            }.onFailure {
                _uiState.value = BackupUiState(message = "导出失败：${it.message}")
            }
        }
    }

    fun onExportWritten(success: Boolean) {
        _uiState.value = BackupUiState(
            message = if (success) "备份已保存" else "保存失败，请重试"
        )
    }

    /** 从备份文本恢复。 */
    fun import(text: String) {
        viewModelScope.launch {
            _uiState.value = BackupUiState(busy = true)

            val snapshot = DataBackup.decode(text)
            if (snapshot == null) {
                _uiState.value = BackupUiState(message = "这不是本应用的备份文件，或版本不兼容")
                return@launch
            }

            // 恢复统计里要报告"规划恢复了几条"。声明在 runCatching **外面**：
            // 在 lambda 里对 `var` 的赋值会写回外层作用域，而 `onSuccess` 需要读它。
            var importedPlanCount = 0
            var importedQuotaCount = 0
            // 本轮新增：备份里那些 0 分钟额度会被 QuotaImport 夹成 1 分钟
            // （用户决定"不予许设置成 0 分钟"，而静默改动用户数据的时长不合适），
            // 所以这里单独记一笔，恢复完成后告诉用户。这里用行注释，下面那句
            // 三元表达式嵌在字符串拼接里，块注释会被当成运算符。
            var repairedQuotaCount = 0

            runCatching {
                // 打卡点先导入，因为习惯和规则会引用它们的 id；
                // 这里不做 id 重映射（个人自用，备份与恢复通常在同一个库上），
                // 但会跳过引用不到打卡点的规则，避免造出无法解锁的规则。
                snapshot.spots.forEach { spotRepository.save(it.copy(id = 0L)) }

                val spotIds = spotRepository.getAllSpots().map { it.id }.toSet()

                snapshot.habits.forEach { habit ->
                    val spotId = habit.spotId?.takeIf { it in spotIds }
                    if (habit.type == com.zilv.app.domain.model.HabitType.LOCATION && spotId == null) {
                        return@forEach
                    }
                    habitRepository.save(habit.copy(id = 0L, spotId = spotId))
                }

                // v2.2：规则不再要求打卡点（打卡点归习惯的打卡条件管），
                // 所以不再有"因缺少打卡点被跳过"的规则 —— 一律导入。
                // spotId 仍然只在能对上号时保留，避免留下悬空引用。
                snapshot.rules.forEach { rule ->
                    val spotId = rule.spotId?.takeIf { it in spotIds }
                    lockRuleRepository.save(rule.copy(id = 0L, spotId = spotId))
                }

                snapshot.allowApps.filterNot { it.builtin }.forEach {
                    lockRuleRepository.addUserApp(it.packageName, it.label)
                    lockRuleRepository.setAppEnabled(it.packageName, it.enabled)
                }

                snapshot.manualPermissionSteps.forEach { settingsRepository.confirmStep(it) }

                // 留言按 epoch_day 主键 upsert：恢复是幂等的（同一天覆盖，
                // 一天仍然只有一条），不会因为重复导入堆出一串重复留言。
                snapshot.dayNotes.forEach { note ->
                    dayNoteRepository.save(
                        LocalDate.ofEpochDay(note.epochDay),
                        note.text
                    )
                }

                // ---- 明日规划（v2.5）----
                //
                // 逻辑抽到 [PlanImport] 里：它是纯数据搬运（读快照 → 调仓库），
                // 抽出来之后可以在**纯 JVM 单测**里直接覆盖"哪些规划能落库、
                // 哪些被拒绝"（见 `PlanImportTest`）；留在 ViewModel 里的话，
                // 要测它就得先造一个 Android Context 来喂 SettingsRepository。
                importedPlanCount = PlanImport.restore(snapshot.plans, planRepository)

                // ---- 使用时间限时额度（v2.7）----
                //
                // 与规划同一条思路：搬运逻辑抽到 [QuotaImport] 里，
                // 于是"哪些额度能落库、哪些被拒绝"可以在**纯 JVM 单测**里覆盖
                // （见 `QuotaImportTest`）。
                //
                // v2.11 本轮：快照里时长是 0 的额度会被**夹成 1 分钟**
                // 而不是被拒绝（0 已不是合法配置，但也不该让整条数据丢掉，
                // 见 `QuotaImport.restore` 的 KDoc）。夹过的条数要如实报出来。
                val quotaResult = QuotaImport.restore(snapshot.quotas, quotaRepository)
                importedQuotaCount = quotaResult.imported
                repairedQuotaCount = quotaResult.repaired
            }.onSuccess {
                _uiState.value = BackupUiState(
                    message = "已恢复：习惯 ${snapshot.habits.size}、" +
                        "规则 ${snapshot.rules.size}、打卡点 ${snapshot.spots.size}、" +
                        "留言 ${snapshot.dayNotes.size} 天、" +
                        "规划 $importedPlanCount 条、" +
                        "额度 $importedQuotaCount 个" +
                        // 只在实际改过的时候才多这一句，否则每天都是噪音
                        if (repairedQuotaCount > 0) {
                            "（其中 $repairedQuotaCount 个 0 分钟额度已按新规则改为 1 分钟）"
                        } else {
                            ""
                        }
                )
            }.onFailure {
                _uiState.value = BackupUiState(message = "恢复失败：${it.message}")
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

package com.zilv.app.domain.backup

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import org.junit.Test

/**
 * 备份格式 v3 里「软件使用时间限时」额度的往返与容错（v2.7）。
 *
 * 为什么额度必须进备份：[DataBackup] 的取舍是"只导出**配置**"，
 * 而额度是用户**亲手配的限制** —— 丢了不只是"少了个配置"，
 * 而是"用户以为自己还被限着，其实没有"。
 *
 * 这一组测试钉死三件事：
 *  1. **往返一致**：名称（含换行）/ 时长 / 刷新周期 / 启用状态 / 绑定应用集合
 *  2. **向下拒绝**：`FORMAT_VERSION` 必须真的升到 3，旧版 App 拒绝读新备份
 *  3. **向上兼容 + 坏行跳过**：v2 备份能读（额度为空），非法额度行被丢弃
 */
class QuotaBackupTest {

    private val douyin = "com.ss.android.ugc.aweme"
    private val kuaishou = "com.smile.gifmaker"
    private val wechat = "com.tencent.mm"

    private fun quotaSnapshot(quotas: List<UsageQuota>) = DataBackup.Snapshot(quotas = quotas)

    // ---------------------------------------------------------------- 往返

    @Test
    fun `额度往返一致`() {
        val snapshot = quotaSnapshot(
            listOf(
                UsageQuota(
                    id = 7L,
                    name = "短视频",
                    limitMinutes = 45,
                    resetPeriod = ResetPeriod.DAILY,
                    enabled = true,
                    createdAt = 1_700_000_000_000L,
                    packageNames = setOf(douyin, kuaishou)
                ),
                UsageQuota(
                    id = 8L,
                    name = "游戏",
                    limitMinutes = 0,
                    resetPeriod = ResetPeriod.WEEKLY,
                    enabled = false,
                    createdAt = 1_700_000_001_000L,
                    packageNames = setOf(wechat)
                )
            )
        )

        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.quotas).hasSize(2)

        val first = decoded.quotas[0]
        assertThat(first.name).isEqualTo("短视频")
        assertThat(first.limitMinutes).isEqualTo(45)
        assertThat(first.resetPeriod).isEqualTo(ResetPeriod.DAILY)
        assertThat(first.enabled).isTrue()
        assertThat(first.packageNames).containsExactly(douyin, kuaishou)

        val second = decoded.quotas[1]
        assertThat(second.name).isEqualTo("游戏")
        // 0 分钟是用户明确要求的"额度为零时禁用"，往返不能把它变成默认值
        assertThat(second.limitMinutes).isEqualTo(0)
        assertThat(second.resetPeriod).isEqualTo(ResetPeriod.WEEKLY)
        assertThat(second.enabled).isFalse()
        assertThat(second.packageNames).containsExactly(wechat)
    }

    @Test
    fun `额度名称里的换行被转义，不会把一条记录拆成两行`() {
        val snapshot = quotaSnapshot(
            listOf(UsageQuota(name = "刷\n视频\u0001带分隔符", limitMinutes = 20, packageNames = setOf(douyin)))
        )
        val text = DataBackup.encode(snapshot)
        // 头部 + 1 条额度记录
        assertThat(text.split('\n').filter { it.isNotBlank() }).hasSize(2)

        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.quotas.single().name).isEqualTo("刷\n视频 带分隔符")
        assertThat(decoded.quotas.single().packageNames).containsExactly(douyin)
    }

    @Test
    fun `恢复是追加的 —— id 一律归零，不会与库里已有额度撞主键`() {
        val snapshot = quotaSnapshot(
            listOf(UsageQuota(id = 42L, name = "短视频", limitMinutes = 30, packageNames = setOf(douyin)))
        )
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.quotas.single().id).isEqualTo(0L)
        // createdAt 保留：它是"这个配置什么时候建的"，恢复不该改它
        assertThat(decoded.quotas.single().createdAt).isEqualTo(0L)
    }

    @Test
    fun `createdAt 原样保留`() {
        val snapshot = quotaSnapshot(
            listOf(
                UsageQuota(
                    name = "短视频",
                    limitMinutes = 30,
                    createdAt = 1_699_999_999_999L,
                    packageNames = setOf(douyin)
                )
            )
        )
        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!
        assertThat(decoded.quotas.single().createdAt).isEqualTo(1_699_999_999_999L)
    }

    // ---------------------------------------------------------------- 版本

    @Test
    fun `格式版本已升到 3 且写进头部`() {
        assertThat(DataBackup.FORMAT_VERSION).isEqualTo(3)
        val text = DataBackup.encode(quotaSnapshot(listOf(UsageQuota(name = "x", packageNames = setOf(douyin)))))
        assertThat(text).startsWith("${DataBackup.HEADER}\u00013")
    }

    @Test
    fun `来自更新版本的备份被拒绝而不是猜`() {
        val future = "${DataBackup.HEADER}\u0001${DataBackup.FORMAT_VERSION + 1}\n"
        assertThat(DataBackup.decode(future)).isNull()
    }

    @Test
    fun `v2 的备份仍能读，额度为空`() {
        // v2 备份里没有 quota 行 —— 向上兼容，其余数据照常恢复
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(2).append('\n')
            append("habit\u0001旧习惯\u0001SIMPLE\u0001DAILY\u0001\u0001\n")
            append("plan\u0001旧规划\u000120742\u0001540\u0001660\u00011\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.habits.map { it.name }).containsExactly("旧习惯")
        assertThat(decoded.plans).hasSize(1)
        assertThat(decoded.quotas).isEmpty()
    }

    @Test
    fun `v1 的备份仍能读，额度为空`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(1).append('\n')
            append("habit\u0001旧习惯\u0001SIMPLE\u0001DAILY\u0001\u0001\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.quotas).isEmpty()
    }

    // ---------------------------------------------------------------- 容错

    @Test
    fun `非法额度行被跳过而不影响好行`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("quota\u0001\u000130\u0001DAILY\u0001true\u00010\u0001$douyin\n")   // 空名称 -> 跳过
            append("quota\u0001没绑应用\u000130\u0001DAILY\u0001true\u00010\u0001\n")     // 没应用 -> 跳过
            // ⚠️ 规格变更：时长上限从 1440 分钟（24 小时）提到 44640 分钟（744 小时），
            // 所以"越界"的样本必须跟着挪 —— 原来的 9999 现在落在合法区间里，
            // 它不再是非法行（这是**测试夹具跟着规格改**，不是代码错）。
            append("quota\u0001越界\u000199999\u0001DAILY\u0001true\u00010\u0001$douyin\n") // 时长越界 -> 跳过
            append("quota\u0001负时长\u0001-5\u0001DAILY\u0001true\u00010\u0001$douyin\n") // 负数 -> 跳过
            append("quota\u0001字段不足\u000130\n")                                        // 字段不足 -> 跳过
            append("quota\u0001非数字时长\u0001abc\u0001DAILY\u0001true\u00010\u0001$douyin\n") // -> 跳过
            append("quota\u0001好额度\u000130\u0001DAILY\u0001true\u00010\u0001$douyin,$kuaishou\n")
        }
        val decoded = DataBackup.decode(text)!!
        assertThat(decoded.quotas.map { it.name }).containsExactly("好额度")
        assertThat(decoded.quotas.single().packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `未知的刷新周期退化为每天而不是丢弃整条额度`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("quota\u0001未来周期\u000130\u0001FUTURE_PERIOD\u0001true\u00010\u0001$douyin\n")
        }
        val decoded = DataBackup.decode(text)!!
        // 宁可刷得勤一点，也不要变成"永不刷新"（那会让应用被永久禁用）
        assertThat(decoded.quotas.single().resetPeriod).isEqualTo(ResetPeriod.DAILY)
    }

    @Test
    fun `启用状态缺失时默认启用`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("quota\u0001额度\u000130\u0001DAILY\u0001garbage\u00010\u0001$douyin\n")
        }
        assertThat(DataBackup.decode(text)!!.quotas.single().enabled).isTrue()
    }

    @Test
    fun `绑定关系里的空项被清理`() {
        val text = buildString {
            append(DataBackup.HEADER).append('\u0001').append(DataBackup.FORMAT_VERSION).append('\n')
            append("quota\u0001额度\u000130\u0001DAILY\u0001true\u00010\u0001$douyin,,$kuaishou,\n")
        }
        assertThat(DataBackup.decode(text)!!.quotas.single().packageNames)
            .containsExactly(douyin, kuaishou)
    }

    @Test
    fun `导出内容里能看到 quota 行与额度名 —— 人类可读`() {
        val text = DataBackup.encode(
            quotaSnapshot(listOf(UsageQuota(name = "短视频", limitMinutes = 30, packageNames = setOf(douyin))))
        )
        assertThat(text).contains("quota")
        assertThat(text).contains("短视频")
        assertThat(text).contains(douyin)
    }

    // ---------------------------------------------------------------- 空判定

    @Test
    fun `只有额度时快照不为空`() {
        assertThat(DataBackup.Snapshot().isEmpty).isTrue()
        assertThat(
            quotaSnapshot(listOf(UsageQuota(name = "x", packageNames = setOf(douyin)))).isEmpty
        ).isFalse()
    }

    @Test
    fun `空额度列表往返后仍为空`() {
        val decoded = DataBackup.decode(DataBackup.encode(DataBackup.Snapshot()))!!
        assertThat(decoded.quotas).isEmpty()
    }
}

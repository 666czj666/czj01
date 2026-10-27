package com.zilv.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 数据备份与恢复（PRD §4.5 T7.2）。
 *
 * 个人自用没人帮你恢复数据 —— 所以这个页面是**必需**的，不是可选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = viewModel.uiState.value.pendingExport
        val ok = if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                true
            }.getOrDefault(false)
        } else {
            false
        }
        viewModel.onExportWritten(ok)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) viewModel.import(text)
        }
    }

    // 生成好内容后拉起文件选择器
    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { exportLauncher.launch("zilv-backup.txt") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据备份与导出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("备份包含什么", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            // v2.8：原来这里是一整段三段落的正文，收进「?」——
                            // 导出的实际内容/格式属于"这一步在做什么"的说明；
                            // 下面那条"恢复是追加而不是覆盖"的橙色警告**保持可见**
                            // （它带着 ⚠️，是操作前必须看到的后果）
                            HelpHint(
                                text = "习惯、锁机规则、打卡点、白名单、手动确认过的权限步骤、" +
                                    "按天留言、明日规划、使用时间限时额度。\n\n" +
                                    "不包含使用时长与拦截日志 —— 那些是观测数据，恢复没有意义。\n\n" +
                                    "格式是纯文本，可直接打开查看甚至手工修改。"
                            )
                        }
                    }
                }

                Button(
                    onClick = viewModel::prepareExport,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出备份到文件")
                }

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从备份文件恢复")
                }

                Text(
                    "⚠️ 恢复是**追加**而不是覆盖：会往当前数据里再加一份，" +
                        "不会删除你现有的习惯与规则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(Modifier.height(24.dp))
            }

            state.message?.let { msg ->
                LaunchedEffect(msg) {
                    delay(3500)
                    viewModel.clearMessage()
                }
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter)) { Text(msg) }
            }
        }
    }
}

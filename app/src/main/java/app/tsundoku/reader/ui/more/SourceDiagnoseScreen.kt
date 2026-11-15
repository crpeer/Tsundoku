package app.tsundoku.reader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.source.SourceDiagnostics
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SourceDiagnoseViewModel(
    private val container: AppContainer,
    private val sourceId: Long,
) : ViewModel() {

    data class State(
        val keyword: String = "",
        val running: Boolean = false,
        val report: SourceDiagnostics.Report? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setKeyword(value: String) = _state.update { it.copy(keyword = value) }

    fun run() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty() || _state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, report = null) }
            val report = container.diagnostics.run(sourceId, keyword)
            _state.update { it.copy(running = false, report = report) }
        }
    }
}

/**
 * 书源诊断页：搜索 / 详情 / 目录 / 正文逐段测试，
 * 每段给出状态、耗时和失败原因，避免只看到一句「加载失败」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDiagnoseScreen(sourceId: Long, sourceName: String, onBack: () -> Unit) {
    val viewModel = appViewModel { container -> SourceDiagnoseViewModel(container, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyword by remember { mutableStateOf("斗破苍穹") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断 · $sourceName", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.setKeyword(keyword)
                        viewModel.run()
                    }, enabled = !state.running) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "开始诊断")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    viewModel.setKeyword(it)
                },
                label = { Text("测试关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when {
                        state.running -> "正在测试…"
                        state.report == null -> "点右上角开始"
                        else -> "通过 ${state.report!!.okCount}/${state.report!!.steps.size} 步"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        viewModel.setKeyword(keyword)
                        viewModel.run()
                    },
                    enabled = !state.running,
                ) { Text("运行") }
            }

            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }

            val report = state.report
            if (report != null) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(report.steps, key = { it.name }) { step ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = statusMark(step.status),
                                    color = statusColor(step.status),
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(step.name, style = MaterialTheme.typography.titleSmall)
                                if (step.millis > 0) {
                                    Text(
                                        text = "  ${step.millis} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (step.detail.isNotBlank()) {
                                Text(
                                    text = step.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusMark(status: SourceDiagnostics.Status): String = when (status) {
    SourceDiagnostics.Status.OK -> "✓"
    SourceDiagnostics.Status.FAILED -> "✕"
    SourceDiagnostics.Status.SKIPPED -> "–"
}

@Composable
private fun statusColor(status: SourceDiagnostics.Status): Color = when (status) {
    SourceDiagnostics.Status.OK -> MaterialTheme.colorScheme.primary
    SourceDiagnostics.Status.FAILED -> MaterialTheme.colorScheme.error
    SourceDiagnostics.Status.SKIPPED -> MaterialTheme.colorScheme.outline
}

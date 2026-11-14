package app.tsundoku.reader.ui.more

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.LegadoSourceCandidate
import app.tsundoku.reader.ui.common.EmptyState
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportLegadoState(
    val loading: Boolean = false,
    val importing: Boolean = false,
    val all: List<LegadoSourceCandidate> = emptyList(),
    val groups: List<String> = emptyList(),
    val group: String? = null,
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val message: String? = null,
) {
    val visible: List<LegadoSourceCandidate>
        get() = all.filter { item ->
            (group == null || item.group == group) &&
                (query.isBlank() || item.name.contains(query, true) || item.group.contains(query, true))
        }
}

class ImportLegadoViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ImportLegadoState())
    val state: StateFlow<ImportLegadoState> = _state.asStateFlow()

    fun load(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                _state.update { it.copy(loading = false, message = "读取文件失败") }
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) { container.legadoImporter.parse(json) }
            if (parsed.isEmpty()) {
                _state.update { it.copy(loading = false, message = "没解析到书源，确认是阅读/Legado 的 JSON") }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    all = parsed,
                    groups = parsed.map { source -> source.group }.filter { g -> g.isNotBlank() }
                        .distinct().sorted(),
                    message = "已载入 ${parsed.size} 个书源，选择要启用的",
                )
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setGroup(value: String?) = _state.update { it.copy(group = value) }

    fun toggle(name: String) = _state.update { current ->
        val next = if (name in current.selected) current.selected - name else current.selected + name
        current.copy(selected = next)
    }

    fun selectVisible() = _state.update { it.copy(selected = it.selected + it.visible.map { s -> s.name }) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun install() {
        val chosen = _state.value.all.filter { it.name in _state.value.selected }
        if (chosen.isEmpty()) {
            _state.update { it.copy(message = "先勾选要导入的书源") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            val added = runCatching { container.legadoImporter.install(chosen) }.getOrDefault(0)
            _state.update {
                it.copy(importing = false, selected = emptySet(), message = "已导入 $added 个书源")
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportLegadoScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { container -> ImportLegadoViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.load(context, uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入阅读书源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.selected.isNotEmpty()) {
                        TextButton(onClick = viewModel::install, enabled = !state.importing) {
                            Text("导入 ${state.selected.size}")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.all.isEmpty()) {
                EmptyState(
                    title = "选择书源 JSON",
                    hint = "支持「阅读 / Legado」导出的 bookSource JSON（单个对象或数组，几千个源也不怕）",
                    actionLabel = if (state.loading) "读取中…" else "选择文件",
                    onAction = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
            } else {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("筛选名称 / 分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.group == null,
                        onClick = { viewModel.setGroup(null) },
                        label = { Text("全部 ${state.all.size}") },
                    )
                    state.groups.forEach { group ->
                        FilterChip(
                            selected = state.group == group,
                            onClick = { viewModel.setGroup(group) },
                            label = {
                                Text("$group ${state.all.count { it.group == group }}")
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "显示 ${state.visible.size} / 已选 ${state.selected.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::selectVisible) { Text("全选当前") }
                    TextButton(onClick = viewModel::clearSelection, enabled = state.selected.isNotEmpty()) {
                        Text("清空")
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.visible, key = { it.baseUrl + it.name }) { item ->
                        SourceRow(
                            item = item,
                            checked = item.name in state.selected,
                            onToggle = { viewModel.toggle(item.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(item: LegadoSourceCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    item.group.takeIf { it.isNotBlank() },
                    item.typeLabel,
                    item.baseUrl.substringAfter("://").substringBefore("/"),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

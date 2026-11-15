package app.tsundoku.reader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.ui.common.EmptyState
import app.tsundoku.reader.ui.common.SourceAvatar
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SourceManageViewModel(private val container: AppContainer) : ViewModel() {
    val sources: StateFlow<List<BookSourceInfo>> = container.sources.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(source: BookSourceInfo, enabled: Boolean) {
        viewModelScope.launch { container.sources.setEnabled(source.id, enabled) }
    }

    fun delete(source: BookSourceInfo) {
        viewModelScope.launch { container.sources.delete(source.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onAddSource: () -> Unit,
    onDiagnose: (BookSourceInfo) -> Unit = {},
) {
    val viewModel = appViewModel { SourceManageViewModel(it) }
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<BookSourceInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("来源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAddSource) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加来源")
                    }
                },
            )
        },
    ) { padding ->
        if (sources.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "还没有来源",
                    hint = "添加网页源、OPDS 或 RSS 订阅",
                    actionLabel = "添加来源",
                    onAction = onAddSource,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(sources, key = { it.id }) { source ->
                ListItem(
                    leadingContent = { SourceAvatar(source.name, Modifier.size(42.dp)) },
                    headlineContent = { Text(source.name) },
                    supportingContent = {
                        Text(source.type.label + if (source.lang.isNotBlank()) " · ${source.lang}" else "")
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { viewModel.setEnabled(source, it) },
                            )
                            Row {
                                TextButton(onClick = { onDiagnose(source) }) {
                                    Icon(
                                        Icons.Outlined.PlayArrow,
                                        contentDescription = "诊断",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                TextButton(onClick = { pendingDelete = source }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除来源") },
            text = { Text("删除「${source.name}」？已经加入书架的书籍不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(source)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

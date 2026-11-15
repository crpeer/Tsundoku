package app.tsundoku.reader.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.repo.UpdatesRepository
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.ui.common.EmptyState
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdatesViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val scanning: Boolean = false,
        val progress: String = "",
        val results: List<UpdatesRepository.UpdateResult> = emptyList(),
        val scanned: Boolean = false,
    ) {
        val withUpdates: Int get() = results.count { it.newChapters > 0 }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, progress = "", scanned = false) }
            val results = runCatching {
                container.updates.scan { title -> _state.update { it.copy(progress = title) } }
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(scanning = false, results = results, scanned = true, progress = "")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(onOpenChapter: (bookId: Long, chapterIndex: Int) -> Unit) {
    val viewModel = appViewModel { container -> UpdatesViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新") },
                actions = {
                    IconButton(onClick = viewModel::scan, enabled = !state.scanning) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "扫描更新")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = state.progress.takeIf { it.isNotBlank() }?.let { "正在检查：$it" } ?: "正在检查…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                !state.scanned -> EmptyState(
                    title = "还没有扫描过",
                    hint = "点右上角刷新，逐个来源检查书架里的书有没有新章节。\n只追加新章节，不会动已读进度。",
                    actionLabel = "开始扫描",
                    onAction = viewModel::scan,
                )

                state.results.isEmpty() -> EmptyState(
                    title = "书架里没有可追更的书",
                    hint = "只有来自网络来源的书才能追更；本地导入的书不需要扫描。",
                )

                else -> {
                    Text(
                        text = "共 ${state.results.size} 本 · ${state.withUpdates} 本有新章节",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.results, key = { it.bookId }) { result ->
                            UpdateRow(
                                result = result,
                                onClick = {
                                    if (result.newChapters > 0 && result.firstNewIndex >= 0) {
                                        onOpenChapter(result.bookId, result.firstNewIndex)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(result: UpdatesRepository.UpdateResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = result.newChapters > 0, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val badge = when {
                result.error != null -> "失败"
                result.newChapters > 0 -> "+${result.newChapters} 章"
                else -> "已是最新"
            }
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    result.error != null -> MaterialTheme.colorScheme.error
                    result.newChapters > 0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        val detail = when {
            result.error != null -> result.error
            result.newChapters > 0 -> "点开从第 ${result.firstNewIndex + 1} 章开始读"
            else -> result.latestChapter?.let { "最新：$it" }
        }
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    HorizontalDivider()
}

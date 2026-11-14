package app.tsundoku.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.SearchItem
import app.tsundoku.reader.ui.common.CoverImage
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GlobalSearchViewModel(private val container: AppContainer) : ViewModel() {

    enum class Status { LOADING, DONE, ERROR }

    data class SourceResult(
        val source: BookSourceInfo,
        val items: List<SearchItem> = emptyList(),
        val status: Status = Status.LOADING,
        val error: String? = null,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SourceResult>>(emptyList())
    val results: StateFlow<List<SourceResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _onlyWithResults = MutableStateFlow(false)
    val onlyWithResults: StateFlow<Boolean> = _onlyWithResults.asStateFlow()

    private var job: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun toggleOnlyWithResults() {
        _onlyWithResults.value = !_onlyWithResults.value
    }

    fun search() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _searching.value = true
            val sources = runCatching { container.sources.enabledSources() }.getOrDefault(emptyList())
            _results.value = sources.map { SourceResult(it) }
            if (sources.isEmpty()) {
                _searching.value = false
                return@launch
            }
            coroutineScope {
                sources.forEach { info ->
                    launch {
                        val source: BookSource? = container.sources.build(info)
                        val result = runCatching { source?.search(keyword, 1).orEmpty() }
                        _results.update { list ->
                            list.map { entry ->
                                if (entry.source.id != info.id) {
                                    entry
                                } else {
                                    result.fold(
                                        onSuccess = {
                                            entry.copy(items = it, status = Status.DONE)
                                        },
                                        onFailure = {
                                            entry.copy(
                                                status = Status.ERROR,
                                                error = it.message ?: "搜索失败",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            _searching.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenRemote: (BookSourceInfo, SearchItem) -> Unit,
) {
    val viewModel = appViewModel { GlobalSearchViewModel(it) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val onlyWithResults by viewModel.onlyWithResults.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    var autoSearch by remember { mutableStateOf(false) }
    val finished = results.count { it.status != GlobalSearchViewModel.Status.LOADING }

    LaunchedEffect(query) {
        if (autoSearch && query.isNotBlank()) {
            viewModel.search()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("全局搜索") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            autoSearch = false
                            viewModel.onQueryChange(it)
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("书名 / 作者 / 关键词") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            viewModel.search()
                        },
                    ) { Text("搜索") }
                }
                if (results.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(
                            selected = onlyWithResults,
                            onClick = viewModel::toggleOnlyWithResults,
                            label = { Text("仅显示有结果") },
                        )
                        Text(
                            text = "已完成 $finished/${results.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (searching) {
                        LinearProgressIndicator(
                            progress = {
                                if (results.isEmpty()) 0f
                                else finished.toFloat() / results.size
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val visible = results.filter { !onlyWithResults || it.items.isNotEmpty() }
        if (results.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("在所有来源中同时搜索", style = MaterialTheme.typography.titleMedium)
                Text(
                    "输入关键词后，每个已启用的来源会并发返回结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(visible, key = { it.source.id }) { result ->
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = result.source.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when (result.status) {
                            GlobalSearchViewModel.Status.LOADING ->
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))

                            GlobalSearchViewModel.Status.ERROR ->
                                Text(
                                    "失败",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )

                            GlobalSearchViewModel.Status.DONE ->
                                Text(
                                    "${result.items.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    }

                    when {
                        result.status == GlobalSearchViewModel.Status.ERROR ->
                            Text(
                                text = result.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )

                        result.items.isEmpty() && result.status == GlobalSearchViewModel.Status.DONE ->
                            Text(
                                text = "没有找到",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )

                        else -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(result.items, key = { it.url }) { item ->
                                ResultCard(item) { onOpenRemote(result.source, item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(item: SearchItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            CoverImage(
                coverPath = null,
                coverUrl = item.cover,
                title = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!item.author.isNullOrBlank()) {
            Text(
                text = item.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

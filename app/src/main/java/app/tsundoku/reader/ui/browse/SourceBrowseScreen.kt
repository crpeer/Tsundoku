package app.tsundoku.reader.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.tsundoku.reader.data.source.BrowseCategory
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.SearchItem
import app.tsundoku.reader.ui.common.CoverImage
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SourceBrowseViewModel(
    private val container: AppContainer,
    private val sourceId: Long,
) : ViewModel() {

    data class State(
        val source: BookSourceInfo? = null,
        val items: List<SearchItem> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val isSearch: Boolean = false,
        val endReached: Boolean = false,
        val folderUrl: String? = null,
        val folderTitle: String? = null,
        val categories: List<BrowseCategory> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var source: BookSource? = null
    private var page = 1
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val info = runCatching { container.sources.sourceById(sourceId) }.getOrNull()
            source = info
            _state.update {
                it.copy(
                    source = info?.info,
                    categories = info?.categories().orEmpty(),
                )
            }
            reload()
        }
    }

    fun reload() {
        page = 1
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val current = _state.value
            val result = runCatching {
                if (current.isSearch) {
                    source?.search(current.query, 1).orEmpty()
                } else {
                    source?.browse(current.folderUrl, 1).orEmpty()
                }
            }
            result.onSuccess { items ->
                _state.update {
                    it.copy(items = items, loading = false, endReached = items.isEmpty())
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(loading = false, error = error.message ?: "加载失败")
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.endReached) return
        page += 1
        val targetPage = page
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = runCatching {
                if (current.isSearch) {
                    source?.search(current.query, targetPage).orEmpty()
                } else {
                    source?.browse(current.folderUrl, targetPage).orEmpty()
                }
            }
            result.onSuccess { items ->
                _state.update {
                    it.copy(
                        items = it.items + items,
                        loading = false,
                        endReached = items.isEmpty(),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.message) }
            }
        }
    }

    fun search(query: String) {
        _state.update { it.copy(query = query, isSearch = true, folderUrl = null, folderTitle = null) }
        reload()
    }

    fun clearSearch() {
        _state.update { it.copy(query = "", isSearch = false) }
        reload()
    }

    fun openFolder(item: SearchItem) {
        _state.update { it.copy(folderUrl = item.url, folderTitle = item.title, isSearch = false) }
        reload()
    }

    /** 点分类入口：用分类地址重新加载（这就是之前被忽略的参数）。 */
    fun openCategory(category: BrowseCategory) {
        _state.update {
            it.copy(folderUrl = category.url, folderTitle = category.name, isSearch = false)
        }
        reload()
    }

    fun closeFolder() {
        _state.update { it.copy(folderUrl = null, folderTitle = null) }
        reload()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: Long,
    onBack: () -> Unit,
    onOpenRemote: (BookSourceInfo, SearchItem) -> Unit,
) {
    val viewModel = appViewModel { SourceBrowseViewModel(it, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current

    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(gridState, state.items.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (state.items.isNotEmpty() && lastVisible >= state.items.size - 6) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            state.folderTitle ?: state.source?.name ?: "来源",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (state.folderUrl != null) viewModel.closeFolder() else onBack()
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                    },
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("搜索书籍") },
                        singleLine = true,
                        trailingIcon = {
                            if (state.isSearch) {
                                IconButton(onClick = {
                                    query = ""
                                    viewModel.clearSearch()
                                }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除")
                                }
                            }
                        },
                    )
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            viewModel.search(query)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("搜索") }
                }
                if (state.categories.isNotEmpty() && !state.isSearch) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.categories, key = { it.name + it.url }) { category ->
                            FilterChip(
                                selected = state.folderTitle == category.name,
                                onClick = { viewModel.openCategory(category) },
                                label = { Text(category.name) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null && state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("加载失败：${state.error}")
                        TextButton(onClick = viewModel::reload) { Text("重试") }
                    }
                }

                state.items.isEmpty() && !state.loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("这里还没有内容", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "试试搜索，或检查来源规则是否匹配当前网页结构",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { it.url + it.title }) { item ->
                            BrowseCell(item) {
                                val info = state.source
                                if (item.isFolder) {
                                    viewModel.openFolder(item)
                                } else if (info != null) {
                                    onOpenRemote(info, item)
                                }
                            }
                        }
                    }
                }
            }

            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowseCell(item: SearchItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            CoverImage(
                coverPath = null,
                coverUrl = item.cover,
                title = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
            )
            if (item.isFolder) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
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

package app.tsundoku.reader.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.local.BookEntity
import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookFormat
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.domain.model.SourceType
import app.tsundoku.reader.ui.common.CoverImage
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteBook(
    val sourceId: Long,
    val url: String,
    val title: String,
    val cover: String?,
    val author: String?,
)

class BookDetailViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val remote: RemoteBook?,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val book: Book? = null,
        val chapters: List<Chapter> = emptyList(),
        val details: BookDetails? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val addedBookId: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        when {
            bookId > 0 -> observeLibraryBook()
            remote != null -> loadRemote()
            else -> _state.update { it.copy(loading = false, error = "书籍不存在") }
        }
    }

    private fun observeLibraryBook() {
        viewModelScope.launch {
            container.library.book(bookId).collect { book ->
                _state.update { it.copy(book = book, loading = false) }
            }
        }
        viewModelScope.launch {
            container.library.chapters(bookId).collect { chapters ->
                _state.update { it.copy(chapters = chapters) }
            }
        }
    }

    private fun loadRemote() {
        val target = remote ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val source = runCatching { container.sources.sourceById(target.sourceId) }.getOrNull()
            if (source == null) {
                _state.update { it.copy(loading = false, error = "来源不存在或已停用") }
                return@launch
            }
            val details = runCatching { source.details(target.url) }.getOrNull()
            val chapterItems = runCatching { source.chapters(target.url) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    details = details,
                    chapters = chapterItems.map { item ->
                        Chapter(
                            id = 0,
                            bookId = 0,
                            index = item.index,
                            title = item.title,
                            url = item.url,
                            content = null,
                            read = false,
                        )
                    },
                    error = if (chapterItems.isEmpty()) "没有解析到章节目录" else null,
                )
            }
        }
    }

    fun addToLibrary() {
        val target = remote ?: return
        val current = _state.value
        if (current.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val info = runCatching { container.sources.sourceById(target.sourceId) }.getOrNull()?.info
            val details = current.details
            val result = runCatching {
                val existing = container.library.findBook(target.sourceId, target.url)
                if (existing != null) {
                    existing.id
                } else if (info?.type == SourceType.OPDS) {
                    container.importer.importRemoteBook(
                        url = target.url,
                        title = details?.title?.ifBlank { target.title } ?: target.title,
                        author = details?.author ?: target.author,
                        coverUrl = details?.cover ?: target.cover,
                    )
                } else {
                    val now = System.currentTimeMillis()
                    container.library.insertBook(
                        book = BookEntity(
                            title = details?.title?.takeIf { it.isNotBlank() } ?: target.title,
                            author = details?.author ?: target.author,
                            coverPath = null,
                            coverUrl = details?.cover ?: target.cover,
                            sourceId = target.sourceId,
                            url = target.url,
                            description = details?.description,
                            format = BookFormat.HTML.name,
                            inLibrary = true,
                            lastReadAt = null,
                            lastChapterIndex = 0,
                            pageCount = 0,
                            updatedAt = now,
                        ),
                        chapters = current.chapters.map { chapter ->
                            ChapterEntity(
                                bookId = 0,
                                idx = chapter.index,
                                title = chapter.title,
                                url = chapter.url,
                                content = null,
                                read = false,
                                updatedAt = now,
                            )
                        },
                    )
                }
            }
            result.onSuccess { id ->
                _state.update { it.copy(busy = false, addedBookId = id, message = "已加入书架") }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = "加入失败：${error.message}") }
            }
        }
    }

    fun refreshChapters() {
        val book = _state.value.book ?: return
        if (book.sourceId == Book.LOCAL_SOURCE_ID) {
            _state.update { it.copy(message = "本地书籍无需更新目录") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val source = runCatching { container.sources.sourceById(book.sourceId) }.getOrNull()
            if (source == null) {
                _state.update { it.copy(busy = false, message = "来源不可用") }
                return@launch
            }
            val items = runCatching { source.chapters(book.url) }.getOrNull()
            if (items.isNullOrEmpty()) {
                _state.update { it.copy(busy = false, message = "没有获取到章节") }
                return@launch
            }
            // 走合并而不是「全删重导」：保留已缓存正文、已读状态和阅读进度。
            val merged = container.library.syncChapters(book.id, items)
            val summary = if (merged == null) {
                "目录已更新（${items.size} 章）"
            } else {
                buildString {
                    append("目录已更新：共 ${merged.chapters.size} 章")
                    if (merged.added > 0) append("，新增 ${merged.added}")
                    if (merged.removed > 0) append("，移除 ${merged.removed}")
                    if (merged.renamed > 0) append("，改名 ${merged.renamed}")
                }
            }
            _state.update { it.copy(busy = false, message = summary) }
        }
    }

    fun delete() {
        val id = _state.value.book?.id ?: _state.value.addedBookId ?: return
        viewModelScope.launch {
            container.library.delete(id)
            _state.update { it.copy(book = null, message = "已从书架移除") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    remote: RemoteBook?,
    onBack: () -> Unit,
    onRead: (Long, Int) -> Unit,
) {
    val viewModel = appViewModel { BookDetailViewModel(it, bookId, remote) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.addedBookId) {
        state.addedBookId?.let { onRead(it, -1) }
    }

    val book = state.book
    val title = book?.title
        ?: state.details?.title?.takeIf { it.isNotBlank() }
        ?: remote?.title
        ?: "书籍"
    val author = book?.author ?: state.details?.author ?: remote?.author
    val coverPath = book?.coverPath
    val coverUrl = book?.coverUrl ?: state.details?.cover ?: remote?.cover
    val description = book?.description ?: state.details?.description

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (book != null && book.sourceId != Book.LOCAL_SOURCE_ID) {
                        IconButton(onClick = viewModel::refreshChapters) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "更新目录")
                        }
                    }
                    if (book != null) {
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CoverImage(
                        coverPath = coverPath,
                        coverUrl = coverUrl,
                        title = title,
                        modifier = Modifier.width(112.dp).aspectRatio(0.7f),
                        corner = 12,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!author.isNullOrBlank()) {
                            Text(
                                text = author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = buildString {
                                append(book?.format?.label ?: "在线")
                                if (state.chapters.isNotEmpty()) {
                                    append(" · ").append(state.chapters.size).append(" 章")
                                }
                                book?.let { b ->
                                    if (b.totalChapters > 0) {
                                        append(" · 已读 ").append(b.readChapters)
                                    }
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (book != null) {
                        Button(
                            onClick = { onRead(book.id, -1) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (book.lastReadAt == null) "开始阅读" else "继续阅读")
                        }
                    } else {
                        Button(
                            onClick = viewModel::addToLibrary,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("加入书架并阅读")
                        }
                        OutlinedButton(onClick = viewModel::addToLibrary) {
                            Text("仅加入书架")
                        }
                    }
                }
            }

            if (!description.isNullOrBlank()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("简介", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            if (state.chapters.isNotEmpty()) {
                item {
                    Text(
                        text = "目录 · ${state.chapters.size} 章",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.chapters, key = { it.index }) { chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val id = book?.id ?: state.addedBookId
                                if (id != null) onRead(id, chapter.index) else viewModel.addToLibrary()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (chapter.read) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapter.read) {
                            Text(
                                "已读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }

            if (state.error != null && state.chapters.isEmpty()) {
                item {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

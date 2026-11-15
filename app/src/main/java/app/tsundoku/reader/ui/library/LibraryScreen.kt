package app.tsundoku.reader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import app.tsundoku.reader.ui.common.CoverImage
import app.tsundoku.reader.ui.common.EmptyState
import app.tsundoku.reader.ui.common.ProgressLine
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val query = MutableStateFlow("")
    val importing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    val books: StateFlow<List<Book>> = combine(
        container.library.libraryBooks(),
        query,
    ) { list, keyword ->
        if (keyword.isBlank()) {
            list
        } else {
            list.filter { book ->
                book.title.contains(keyword, ignoreCase = true) ||
                    book.author?.contains(keyword, ignoreCase = true) == true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            runCatching { container.importer.importUri(uri) }
                .onSuccess { message.value = "已导入到书架" }
                .onFailure { message.value = "导入失败：${it.message}" }
            importing.value = false
        }
    }

    fun remove(book: Book, keepMeta: Boolean) {
        viewModelScope.launch {
            if (keepMeta) container.meta.keepFor(book) else container.meta.discardFor(book)
            container.library.delete(book.id)
            message.value = if (keepMeta) {
                "已移除《${book.title}》，进度与批注已存成 meta.json"
            } else {
                "已移除《${book.title}》"
            }
        }
    }

    fun setCover(book: Book, uri: Uri) {
        viewModelScope.launch {
            val file = runCatching { container.importer.replaceCover(book, uri) }.getOrNull()
            if (file == null) {
                message.value = "封面更新失败"
                return@launch
            }
            container.library.updateMeta(
                bookId = book.id,
                title = book.title,
                author = book.author,
                coverUrl = book.coverUrl,
                coverPath = file.absolutePath,
                description = book.description,
            )
            container.meta.sync(book.id)
            message.value = "已更新封面"
        }
    }

    fun consumeMessage() {
        message.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenReader: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val viewModel = appViewModel { LibraryViewModel(it) }
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var coverTarget by remember { mutableStateOf<Book?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = coverTarget
        coverTarget = null
        if (uri != null && target != null) viewModel.setCover(target, uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("书架", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        IconButton(
                            onClick = {
                                picker.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "text/plain",
                                        "application/pdf",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "导入书籍")
                        }
                    },
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = {
                            keyword = it
                            viewModel.setQuery(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("搜索书名或作者") },
                        singleLine = true,
                        trailingIcon = {
                            if (keyword.isNotEmpty()) {
                                IconButton(onClick = {
                                    keyword = ""
                                    viewModel.setQuery("")
                                }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除")
                                }
                            }
                        },
                    )
                }
                if (importing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = if (keyword.isBlank()) "书架还是空的" else "没有匹配的书籍",
                    hint = if (keyword.isBlank()) {
                        "导入 EPUB / TXT / PDF，或者从「发现」里添加一个来源"
                    } else {
                        "换个关键词试试"
                    },
                    actionLabel = if (keyword.isBlank()) "导入本地文件" else null,
                    onAction = if (keyword.isBlank()) {
                        {
                            picker.launch(
                                arrayOf("application/epub+zip", "text/plain", "application/pdf"),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(books, key = { it.id }) { book ->
                BookCell(
                    book = book,
                    onClick = { onOpenReader(book.id) },
                    onLongClick = { menuBook = book },
                )
            }
        }
    }

    menuBook?.let { book ->
        ModalBottomSheet(onDismissRequest = { menuBook = null }) {
            ListItem(
                headlineContent = {
                    Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            book.author,
                            book.format.label,
                            if (book.totalChapters > 0) "${book.totalChapters} 章" else null,
                        ).joinToString(" · "),
                    )
                },
            )
            ListItem(
                headlineContent = { Text("查看详情") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.combinedClickable(
                    onClick = {
                        menuBook = null
                        onOpenBook(book.id)
                    },
                    onLongClick = {},
                ),
            )
            ListItem(
                headlineContent = { Text("更换封面") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.combinedClickable(
                    onClick = {
                        coverTarget = book
                        menuBook = null
                        coverPicker.launch(arrayOf("image/*", "application/octet-stream"))
                    },
                    onLongClick = {},
                ),
            )
            ListItem(
                headlineContent = { Text("从书架移除") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.combinedClickable(
                    onClick = {
                        pendingDelete = book
                        menuBook = null
                    },
                    onLongClick = {},
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { book ->
        var keepMeta by remember(book.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除书籍") },
            text = {
                Column {
                    Text("确定要把《${book.title}》从书架移除吗？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepMeta, onCheckedChange = { keepMeta = it })
                        Text("保留阅读进度与批注（存成 meta.json）")
                    }
                    Text(
                        text = "书籍文件与封面会留在 books/ 目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(book, keepMeta)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCell(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            CoverImage(
                coverPath = book.coverPath,
                coverUrl = book.coverUrl,
                title = book.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f),
            )
            if (book.format == BookFormat.PDF) {
                Text(
                    text = "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.86f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val subtitle = book.author
            ?: when {
                book.format == BookFormat.PDF && book.pageCount > 0 -> "${book.pageCount} 页"
                book.totalChapters > 0 -> "${book.totalChapters} 章"
                else -> null
            }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (book.totalChapters > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                ProgressLine(
                    current = book.readChapters,
                    total = book.totalChapters,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${book.readChapters}/${book.totalChapters}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(2.dp))
    }
}

package app.tsundoku.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.prefs.ReaderSettings
import app.tsundoku.reader.data.prefs.ReaderTheme
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ChapterContent {
    data object Loading : ChapterContent
    data class Ready(val html: String?, val localPath: String?) : ChapterContent
}

class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val initialChapter: Int,
) : ViewModel() {

    val book: StateFlow<Book?> = container.library.book(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chapters: StateFlow<List<Chapter>> = container.library.chapters(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<ReaderSettings> = container.settings.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    private val _index = MutableStateFlow(initialChapter.coerceAtLeast(0))
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _content = MutableStateFlow<ChapterContent>(ChapterContent.Loading)
    val content: StateFlow<ChapterContent> = _content.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val current = book.filterNotNull().first()
            if (current.format == BookFormat.PDF) return@launch
            val list = chapters.filter { it.isNotEmpty() }.first()
            val target = if (initialChapter >= 0) {
                initialChapter
            } else {
                current.lastChapterIndex
            }
            _index.value = target.coerceIn(0, list.size - 1)
            loadChapter(_index.value)
        }
    }

    fun goTo(target: Int) {
        val total = chapters.value.size
        if (total == 0) return
        val clamped = target.coerceIn(0, total - 1)
        if (clamped == _index.value) return
        _index.value = clamped
        loadChapter(clamped)
    }

    fun next() = goTo(_index.value + 1)

    fun previous() = goTo(_index.value - 1)

    fun savePdfPage(page: Int) {
        viewModelScope.launch {
            val current = book.value ?: return@launch
            if (current.lastChapterIndex != page) {
                container.library.updateProgress(current.id, page)
            }
        }
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch { container.settings.setFontSize(size) }
    }

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { container.settings.setTheme(theme) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun loadChapter(index: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val current = book.value ?: return@launch
            val chapter = chapters.value.getOrNull(index) ?: return@launch
            _content.value = ChapterContent.Loading
            container.library.updateProgress(current.id, index)
            container.library.markRead(current.id, index, true)

            val stored = chapter.content
            val localPath = stored?.takeIf { it.isNotBlank() && File(it).exists() }
            if (localPath != null) {
                _content.value = ChapterContent.Ready(html = null, localPath = localPath)
                return@launch
            }
            if (!stored.isNullOrBlank()) {
                _content.value = ChapterContent.Ready(html = stored, localPath = null)
                return@launch
            }

            val source = if (current.sourceId == Book.LOCAL_SOURCE_ID) {
                null
            } else {
                runCatching { container.sources.sourceById(current.sourceId) }.getOrNull()
            }
            if (source == null || chapter.url.isBlank()) {
                _content.value = ChapterContent.Ready(html = "<p>无法获取正文</p>", localPath = null)
                _message.value = "无法获取正文：来源不可用"
                return@launch
            }
            val html = runCatching { source.content(chapter.url) }
                .onFailure { _message.value = "加载失败：${it.message}" }
                .getOrNull()
            if (html.isNullOrBlank()) {
                _content.value = ChapterContent.Ready(html = "<p>没有抓到正文内容</p>", localPath = null)
            } else {
                container.library.cacheContent(current.id, index, html)
                _content.value = ChapterContent.Ready(html = html, localPath = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    initialChapter: Int,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel { ReaderViewModel(it, bookId, initialChapter) }
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pdfPage by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val current = book
    val isPdf = current?.format == BookFormat.PDF
    val chapter = chapters.getOrNull(index)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = chapter?.title ?: current?.title ?: "阅读",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = if (isPdf) {
                                "第 ${pdfPage + 1} / ${current?.pageCount ?: 0} 页"
                            } else {
                                "${index + 1} / ${chapters.size}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!isPdf && chapters.isNotEmpty()) {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "阅读设置")
                    }
                },
            )
        },
        bottomBar = {
            if (!isPdf && chapters.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = viewModel::previous,
                            enabled = index > 0,
                        ) { Text("上一章") }
                        Text(
                            text = "${index + 1} / ${chapters.size}",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(
                            onClick = viewModel::next,
                            enabled = index < chapters.size - 1,
                        ) { Text("下一章") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                current == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                isPdf -> PdfBookView(
                    book = current,
                    initialPage = current.lastChapterIndex,
                    onPageChanged = {
                        pdfPage = it
                        viewModel.savePdfPage(it)
                    },
                )

                else -> when (val state = content) {
                    is ChapterContent.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is ChapterContent.Ready -> ChapterView(
                        html = state.html,
                        localPath = state.localPath,
                        theme = settings.theme,
                        fontSize = settings.fontSize,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text(
                text = "目录 · ${chapters.size} 章",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(chapters, key = { it.id }) { item ->
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.index == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.goTo(item.index)
                                showToc = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text("字号 · ${settings.fontSize}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = settings.fontSize.toFloat(),
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                    valueRange = 13f..34f,
                    steps = 20,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    ReaderTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = theme == settings.theme,
                            onClick = { viewModel.setTheme(theme) },
                            label = { Text(theme.label) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun cssFor(theme: ReaderTheme, fontSize: Int): String = """
    html, body {
      margin: 0;
      padding: 0 18px 64px 18px;
      background: ${theme.bg};
      color: ${theme.fg};
      font-size: ${fontSize}px;
      line-height: 1.85;
      -webkit-text-size-adjust: 100%;
      word-wrap: break-word;
    }
    body { padding-top: 8px; }
    p { margin: 0 0 0.85em 0; text-indent: 2em; }
    img, video, svg { max-width: 100%; height: auto; }
    h1, h2, h3 { line-height: 1.45; margin: 1em 0 0.6em; }
    a { color: inherit; }
    pre { white-space: pre-wrap; }
""".trimIndent()

@Suppress("UnusedReceiverParameter")
@Composable
private fun ChapterView(
    html: String?,
    localPath: String?,
    theme: ReaderTheme,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color(android.graphics.Color.parseColor(theme.bg))),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.textZoom = 100
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { web ->
            val style = cssFor(theme, fontSize)
            when {
                localPath != null -> {
                    val file = File(localPath)
                    val raw = runCatching { file.readText() }.getOrNull()
                    if (raw != null) {
                        val injected = if (raw.contains("</head>", ignoreCase = true)) {
                            raw.replaceFirst(
                                "</head>",
                                "<style>$style</style></head>",
                                ignoreCase = true,
                            )
                        } else {
                            "<style>$style</style>$raw"
                        }
                        web.loadDataWithBaseURL(
                            "file://${file.parentFile?.absolutePath}/",
                            injected,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                }

                html != null -> web.loadDataWithBaseURL(
                    null,
                    "<!doctype html><html><head><meta charset=\"utf-8\">" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                        "<style>$style</style></head><body>$html</body></html>",
                    "text/html",
                    "utf-8",
                    null,
                )

                else -> Unit
            }
        },
    )
}

private class PdfHolder(
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) {
    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

@Composable
private fun PdfBookView(
    book: Book,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
) {
    val holder = remember(book.id) {
        runCatching {
            val descriptor = ParcelFileDescriptor.open(
                File(book.url),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            PdfHolder(descriptor, PdfRenderer(descriptor))
        }.getOrNull()
    }
    DisposableEffect(holder) {
        onDispose { holder?.close() }
    }
    if (holder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无法打开这个 PDF 文件")
        }
        return
    }

    val pageCount = holder.renderer.pageCount
    if (pageCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF 没有可用页面")
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pageCount - 1),
    ) { pageCount }
    val mutex = remember(holder) { Mutex() }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF2A2A2A)),
    ) { page ->
        PdfPage(holder = holder, mutex = mutex, pageIndex = page)
    }
}

@Composable
private fun PdfPage(holder: PdfHolder, mutex: Mutex, pageIndex: Int) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    holder.renderer.openPage(pageIndex).use { page ->
                        val scale = 1.6f
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        rendered.eraseColor(android.graphics.Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered
                    }
                }.getOrNull()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

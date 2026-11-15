package app.tsundoku.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.prefs.ReaderFontFamily
import app.tsundoku.reader.data.prefs.ReaderFontWeight
import app.tsundoku.reader.data.prefs.ReaderLayout
import app.tsundoku.reader.data.prefs.ReaderSettings
import app.tsundoku.reader.data.prefs.ReaderTheme
import app.tsundoku.reader.data.prefs.ReaderWritingMode
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

sealed interface ChapterContent {
    data object Loading : ChapterContent
    data class Ready(val html: String?, val localPath: String?) : ChapterContent
    data class Failure(val message: String) : ChapterContent
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

    /** 进入本章时要恢复的位置（滚动偏移或页码）。 */
    private val _initialOffset = MutableStateFlow(0)
    val initialOffset: StateFlow<Int> = _initialOffset.asStateFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var currentOffset = 0

    init {
        viewModelScope.launch {
            // 等待超时也要落到明确的失败态，不能一直转圈。
            val current = withTimeoutOrNull(INIT_TIMEOUT_MS) { book.filterNotNull().first() }
            if (current == null) {
                _content.value = ChapterContent.Failure("找不到这本书，可能已被移除")
                return@launch
            }
            if (current.format == BookFormat.PDF) return@launch

            val list = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                chapters.filter { it.isNotEmpty() }.first()
            }
            if (list.isNullOrEmpty()) {
                _content.value = ChapterContent.Failure(
                    "这本书没有解析出章节。\n可能是文件损坏，或在「详情」里重新更新目录。",
                )
                return@launch
            }

            val target = (if (initialChapter >= 0) initialChapter else current.lastChapterIndex)
                .coerceIn(0, list.size - 1)
            _index.value = target
            loadChapter(target)
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

    /** 下一章：用户已经往下走了，视为读完当前章。 */
    fun next() {
        markRead(_index.value)
        goTo(_index.value + 1)
    }

    fun previous() {
        goTo(_index.value - 1)
    }

    fun retry() {
        loadChapter(_index.value)
    }

    fun savePdfPage(page: Int) {
        viewModelScope.launch {
            val current = book.value ?: return@launch
            if (current.lastChapterIndex != page) {
                container.library.updateProgress(current.id, page)
            }
        }
    }

    /**
     * 阅读位置变化：先只更新内存里的位置（防抖后再落库），
     * 只有真正读到本章末尾才算「已读」。
     */
    fun onPosition(offset: Int, atEnd: Boolean) {
        currentOffset = offset
        val chapterIndex = _index.value
        if (atEnd) markRead(chapterIndex)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(POSITION_DEBOUNCE_MS)
            persistPosition(chapterIndex, offset)
        }
    }

    /** 退出阅读器时立即落盘（不等防抖）。 */
    suspend fun flushPosition() {
        saveJob?.cancel()
        persistPosition(_index.value, currentOffset)
        container.meta.sync(bookId)
    }

    private suspend fun persistPosition(chapterIndex: Int, offset: Int) {
        val current = book.value ?: return
        container.library.updatePosition(current.id, chapterIndex, offset)
    }

    private fun markRead(chapterIndex: Int) {
        val current = book.value ?: return
        viewModelScope.launch {
            container.library.markRead(current.id, chapterIndex, true)
            container.meta.sync(current.id)
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 8_000L
        const val POSITION_DEBOUNCE_MS = 700L
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope 已经在取消，用应用级作用域保证最后一次写入。
        val chapterIndex = _index.value
        val offset = currentOffset
        container.appScope.launch {
            val current = book.value ?: return@launch
            container.library.updatePosition(current.id, chapterIndex, offset)
            container.meta.sync(current.id)
        }
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch { container.settings.setFontSize(size) }
    }

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { container.settings.setTheme(theme) }
    }

    fun setFontFamily(family: ReaderFontFamily) {
        viewModelScope.launch { container.settings.setFontFamily(family) }
    }

    fun setFontWeight(weight: ReaderFontWeight) {
        viewModelScope.launch { container.settings.setFontWeight(weight) }
    }

    fun setLineHeight(value: Float) {
        viewModelScope.launch { container.settings.setLineHeight(value) }
    }

    fun setParagraphSpacing(value: Float) {
        viewModelScope.launch { container.settings.setParagraphSpacing(value) }
    }

    fun setTextIndent(value: Float) {
        viewModelScope.launch { container.settings.setTextIndent(value) }
    }

    fun setHorizontalPadding(value: Int) {
        viewModelScope.launch { container.settings.setHorizontalPadding(value) }
    }

    fun setWritingMode(mode: ReaderWritingMode) {
        viewModelScope.launch { container.settings.setWritingMode(mode) }
    }

    fun setLayout(layout: ReaderLayout) {
        viewModelScope.launch { container.settings.setLayout(layout) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun loadChapter(index: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val current = book.value ?: run {
                _content.value = ChapterContent.Failure("找不到这本书")
                return@launch
            }
            val chapter = chapters.value.getOrNull(index) ?: run {
                _content.value = ChapterContent.Failure("章节不存在（第 ${index + 1} 章）")
                return@launch
            }
            _content.value = ChapterContent.Loading
            // 只有重新进入上次读的那一章才恢复位置；否则从章首开始。
            currentOffset = if (index == current.lastChapterIndex) current.lastPositionOffset else 0
            _initialOffset.value = currentOffset
            container.library.updateProgress(current.id, index, currentOffset)

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
                _content.value = ChapterContent.Failure(
                    "无法获取正文：来源不可用或章节缺少地址。\n可以在「详情 → 更新目录」后重试。",
                )
                return@launch
            }
            val html = runCatching { source.content(chapter.url) }
                .onFailure { _message.value = "加载失败：${it.message}" }
                .getOrNull()
            if (html.isNullOrBlank()) {
                _content.value = ChapterContent.Failure("没有抓到正文内容，可能是书源规则不匹配该站点。")
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
    val initialOffset by viewModel.initialOffset.collectAsStateWithLifecycle()

    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pdfPage by remember { mutableStateOf(0) }
    var pageInfo by remember { mutableStateOf(0 to 1) }
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

    val readerTheme = settings.theme.resolved(isSystemInDarkTheme())
    val readerBg = Color(android.graphics.Color.parseColor(readerTheme.bg))
    val readerFg = Color(android.graphics.Color.parseColor(readerTheme.fg))
    val themedChrome = !isPdf && current != null
    val scope = rememberCoroutineScope()

    // 退出前把当前位置写库，避免「看完就退」丢进度。
    BackHandler {
        scope.launch {
            viewModel.flushPosition()
            onBack()
        }
    }

    Scaffold(
        containerColor = if (themedChrome) readerBg else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = if (themedChrome) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = readerBg,
                        titleContentColor = readerFg,
                        navigationIconContentColor = readerFg,
                        actionIconContentColor = readerFg,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
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
                                val base = "${index + 1} / ${chapters.size}"
                                if (settings.layout == ReaderLayout.PAGED) {
                                    "$base · 第 ${pageInfo.first + 1} 页"
                                } else {
                                    base
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (themedChrome) {
                                readerFg.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
                Surface(color = readerBg, tonalElevation = 0.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = viewModel::previous,
                            enabled = index > 0,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = readerFg,
                                disabledContentColor = readerFg.copy(alpha = 0.38f),
                            ),
                        ) { Text("上一章") }
                        Text(
                            text = "${index + 1} / ${chapters.size}",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = readerFg.copy(alpha = 0.7f),
                        )
                        TextButton(
                            onClick = viewModel::next,
                            enabled = index < chapters.size - 1,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = readerFg,
                                disabledContentColor = readerFg.copy(alpha = 0.38f),
                            ),
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

                    is ChapterContent.Failure -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "正文加载失败",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("重试")
                        }
                    }

                    is ChapterContent.Ready -> ChapterView(
                        html = state.html,
                        localPath = state.localPath,
                        readerSettings = settings,
                        theme = readerTheme,
                        initialOffset = initialOffset,
                        onPosition = viewModel::onPosition,
                        onPageChanged = { page, pages -> pageInfo = page to pages },
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                SettingSlider(
                    title = "字号",
                    valueLabel = "${settings.fontSize} px",
                    value = settings.fontSize.toFloat(),
                    range = 13f..34f,
                    steps = 20,
                    onChange = { viewModel.setFontSize(it.toInt()) },
                )
                SettingSlider(
                    title = "行距",
                    valueLabel = formatDecimal(settings.lineHeight),
                    value = settings.lineHeight,
                    range = 1.2f..2.6f,
                    steps = 13,
                    onChange = viewModel::setLineHeight,
                )
                SettingSlider(
                    title = "段距",
                    valueLabel = "${formatDecimal(settings.paragraphSpacing)} em",
                    value = settings.paragraphSpacing,
                    range = 0f..2f,
                    steps = 7,
                    onChange = viewModel::setParagraphSpacing,
                )
                SettingSlider(
                    title = "首行缩进",
                    valueLabel = "${formatDecimal(settings.textIndent)} 字",
                    value = settings.textIndent,
                    range = 0f..3f,
                    steps = 5,
                    onChange = viewModel::setTextIndent,
                )
                SettingSlider(
                    title = "页边距",
                    valueLabel = "${settings.horizontalPadding} px",
                    value = settings.horizontalPadding.toFloat(),
                    range = 12f..40f,
                    steps = 13,
                    onChange = { viewModel.setHorizontalPadding(it.toInt()) },
                )
                SettingChips(
                    title = "字族",
                    options = ReaderFontFamily.entries,
                    selected = settings.fontFamily,
                    label = { it.label },
                    onSelect = viewModel::setFontFamily,
                )
                SettingChips(
                    title = "字重",
                    options = ReaderFontWeight.entries,
                    selected = settings.fontWeight,
                    label = { it.label },
                    onSelect = viewModel::setFontWeight,
                )
                SettingChips(
                    title = "阅读模式",
                    options = ReaderLayout.entries,
                    selected = settings.layout,
                    label = { it.label },
                    onSelect = viewModel::setLayout,
                )
                SettingChips(
                    title = "书写方向",
                    options = ReaderWritingMode.entries,
                    selected = settings.writingMode,
                    label = { it.label },
                    onSelect = viewModel::setWritingMode,
                )
                SettingChips(
                    title = "主题",
                    options = ReaderTheme.entries,
                    selected = settings.theme,
                    label = { it.label },
                    onSelect = viewModel::setTheme,
                )
            }
        }
    }
}

private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingChips(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

private fun cssFor(
    theme: ReaderTheme,
    s: ReaderSettings,
    viewWidthPx: Int,
    viewHeightPx: Int,
): String {
    val pad = s.horizontalPadding
    val vertical = s.writingMode == ReaderWritingMode.VERTICAL
    val paged = s.layout == ReaderLayout.PAGED && !vertical
    val blockFlow = when {
        paged -> {
            // 一列 = 一页：列宽 = 可视宽度 - 2*pad，列间距 = 2*pad，所以翻页步长正好等于可视宽度。
            """
            html { overflow-x: auto; overflow-y: hidden; }
            body {
              height: ${viewHeightPx}px;
              padding: 8px ${pad}px;
              box-sizing: border-box;
              column-width: ${(viewWidthPx - pad * 2).coerceAtLeast(120)}px;
              column-gap: ${pad * 2}px;
              column-fill: auto;
            }
            """.trimIndent()
        }

        vertical -> {
            // vertical-rl 的块轴是水平的，行长度 = block-size；WebView 不认 vh/%，用实测高度写死。
            """
            html { writing-mode: ${s.writingMode.css}; -webkit-writing-mode: ${s.writingMode.css}; height: ${viewHeightPx}px; }
            body { writing-mode: ${s.writingMode.css}; -webkit-writing-mode: ${s.writingMode.css}; height: ${viewHeightPx}px; padding: 16px ${pad}px; }
            """.trimIndent()
        }

        else -> """
            html { writing-mode: ${s.writingMode.css}; }
            body { padding: 8px ${pad}px 64px ${pad}px; }
        """.trimIndent()
    }
    val media = if (vertical) {
        "img, video, svg { max-width: none; max-height: 60vh; }"
    } else {
        "img, video, svg { max-width: 100%; height: auto; }"
    }
    val breakRule = if (paged) "h1, h2, h3 { break-after: avoid; }" else ""
    return """
    html, body {
      margin: 0;
      padding: 0;
      background: ${theme.bg};
      color: ${theme.fg};
      font-size: ${s.fontSize}px;
      font-family: ${s.fontFamily.css};
      font-weight: ${s.fontWeight.css};
      line-height: ${s.lineHeight};
      -webkit-text-size-adjust: 100%;
      word-wrap: break-word;
    }
    $blockFlow
    p { margin: 0 0 ${s.paragraphSpacing}em 0; text-indent: ${s.textIndent}em; }
    $media
    h1, h2, h3 { line-height: 1.45; margin: 1em 0 0.6em; }
    $breakRule
    a { color: inherit; }
    pre { white-space: pre-wrap; }
""".trimIndent()
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ChapterView(
    html: String?,
    localPath: String?,
    readerSettings: ReaderSettings,
    theme: ReaderTheme,
    initialOffset: Int,
    onPosition: (offset: Int, atEnd: Boolean) -> Unit,
    onPageChanged: (page: Int, pageCount: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // CSS px == dp here (viewport meta width=device-width, initial-scale=1), so the measured
        // size in dp is directly usable as a CSS length.
        val viewWidthPx = maxWidth.value.roundToInt().coerceAtLeast(1)
        val viewHeightPx = maxHeight.value.roundToInt().coerceAtLeast(1)
        val paged = readerSettings.layout == ReaderLayout.PAGED &&
            readerSettings.writingMode != ReaderWritingMode.VERTICAL

        // 非 Compose 状态：记录当前偏移，供「设置变更后重新加载」时回到原处。
        val lastOffset = remember(localPath, html) { intArrayOf(initialOffset.coerceAtLeast(0)) }
        var webRef by remember { mutableStateOf<ReaderWebView?>(null) }

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(android.graphics.Color.parseColor(theme.bg))),
            factory = { context ->
                ReaderWebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.textZoom = 100
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // 阅读器不应该能把用户带出当前章节（点外链/广告位）
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true

                        override fun onPageFinished(view: WebView, url: String?) {
                            (view as? ReaderWebView)?.applyRestore()
                        }
                    }
                    onOffset = { offset, atEnd ->
                        lastOffset[0] = offset
                        onPosition(offset, atEnd)
                    }
                    onPage = { page, pages -> onPageChanged(page, pages) }
                }.also { webRef = it }
            },
            update = { web ->
                val style = cssFor(theme, readerSettings, viewWidthPx, viewHeightPx)
                web.pagedMode = paged
                web.restoreOffset = lastOffset[0]
            when {
                localPath != null -> {
                    val file = File(localPath)
                    val raw = runCatching { file.readText() }.getOrNull()
                    if (raw != null) {
                        val meta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                        val injected = if (raw.contains("</head>", ignoreCase = true)) {
                            raw.replaceFirst(
                                "</head>",
                                "$meta<style>$style</style></head>",
                                ignoreCase = true,
                            )
                        } else {
                            "$meta<style>$style</style>$raw"
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

        if (paged) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(viewWidthPx) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                val web = webRef ?: return@detectHorizontalDragGestures
                                val step = web.width.coerceAtLeast(1)
                                val before = web.scrollX
                                when {
                                    dragged <= -40f -> web.scrollBy(step, 0)
                                    dragged >= 40f -> web.scrollBy(-step, 0)
                                    else -> return@detectHorizontalDragGestures
                                }
                                web.post {
                                    val page = (web.scrollX / step).coerceAtLeast(0)
                                    // 往前翻却纹丝不动 = 已经在最后一页，本章读完
                                    val atEnd = dragged <= -40f && web.scrollX == before
                                    web.publishPosition(page, atEnd)
                                }
                            },
                            onHorizontalDrag = { _, amount -> dragged += amount },
                        )
                    },
            )
        }
    }
}

/**
 * 阅读器用的 WebView：
 * - 滚动时上报位置（滚动模式 = scrollY，翻页模式 = 页码）
 * - 重新加载后恢复到上次位置（换字号/主题不会丢失阅读位置）
 */
private class ReaderWebView(context: android.content.Context) : WebView(context) {

    var pagedMode: Boolean = false
    var restoreOffset: Int = 0
    var onOffset: ((offset: Int, atEnd: Boolean) -> Unit)? = null
    var onPage: ((page: Int, pageCount: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val step = width.coerceAtLeast(1)
        if (pagedMode) {
            publishPosition((scrollX / step).coerceAtLeast(0), false)
        } else {
            val atEnd = contentHeight > 0 && scrollY + height >= contentHeight - 24
            onOffset?.invoke(scrollY, atEnd)
        }
    }

    fun publishPosition(page: Int, atEnd: Boolean) {
        onPage?.invoke(page, 0)
        onOffset?.invoke(page, atEnd)
    }

    fun applyRestore() {
        val step = width.coerceAtLeast(1)
        val target = restoreOffset.coerceAtLeast(0)
        if (pagedMode) scrollTo(target * step, 0) else scrollTo(0, target)
        post {
            if (pagedMode) publishPosition((scrollX / step).coerceAtLeast(0), false)
        }
    }
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

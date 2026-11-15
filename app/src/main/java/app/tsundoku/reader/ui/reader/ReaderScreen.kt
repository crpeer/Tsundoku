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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import app.tsundoku.reader.data.prefs.ReaderPageAnim
import app.tsundoku.reader.data.prefs.ReaderTapZone
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

    /** >= 0 时按相对位置打开（回溯到上一章时用它落在章末）。 */
    private val _initialRatio = MutableStateFlow(-1f)
    val initialRatio: StateFlow<Float> = _initialRatio.asStateFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var currentOffset = 0

    /** 下一次加载章节时要用的相对位置，消费一次就清空。 */
    private var pendingRatio = -1f

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

    /** 上一章：从章末开始，和纸质书往前翻的手感一致。 */
    fun previous() {
        if (_index.value <= 0) return
        pendingRatio = 1f
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

    fun setPageAnim(anim: ReaderPageAnim) {
        viewModelScope.launch { container.settings.setPageAnim(anim) }
    }

    fun setTapZone(zone: ReaderTapZone) {
        viewModelScope.launch { container.settings.setTapZone(zone) }
    }

    fun setVolumeKeys(value: Boolean) {
        viewModelScope.launch { container.settings.setVolumeKeys(value) }
    }

    fun setLetterSpacing(value: Float) {
        viewModelScope.launch { container.settings.setLetterSpacing(value) }
    }

    fun setWordSpacing(value: Float) {
        viewModelScope.launch { container.settings.setWordSpacing(value) }
    }

    fun setJustify(value: Boolean) {
        viewModelScope.launch { container.settings.setJustify(value) }
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
            _initialRatio.value = pendingRatio
            pendingRatio = -1f
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
    val initialRatio by viewModel.initialRatio.collectAsStateWithLifecycle()

    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pdfPage by remember { mutableStateOf(0) }
    var pageInfo by remember { mutableStateOf(0 to 1) }
    val snackbarHostState = remember { SnackbarHostState() }
    val turnController = remember { ReaderTurnController() }
    var chromeVisible by remember { mutableStateOf(true) }

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

    DisposableEffect(settings.volumeKeys, isPdf) {
        ReaderKeyEvents.handler = if (settings.volumeKeys && !isPdf) {
            { event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            turnController.turn?.invoke(true)
                            true
                        }
                        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                            turnController.turn?.invoke(false)
                            true
                        }
                        else -> false
                    }
                }
            }
        } else null
        onDispose { ReaderKeyEvents.handler = null }
    }

    // 退出前把当前位置写库，避免「看完就退」丢进度。
    // 目录/设置面板打开时要让位给面板自己的返回处理（否则返回键什么都关不掉）。
    BackHandler(enabled = !showSettings && !showToc) {
        scope.launch {
            viewModel.flushPosition()
            onBack()
        }
    }

    Scaffold(
        containerColor = if (themedChrome) readerBg else MaterialTheme.colorScheme.background,
        topBar = {
            if (chromeVisible) {
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
                                    "$base · 第 ${pageInfo.first + 1} / ${pageInfo.second} 页"
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
            }
        },
        bottomBar = {
            if (chromeVisible && !isPdf && chapters.isNotEmpty()) {
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
                        initialRatio = initialRatio,
                        turnController = turnController,
                        onPosition = viewModel::onPosition,
                        onPageChanged = { page, pages -> pageInfo = page to pages },
                        onEdgeTurn = { forward ->
                            if (forward) viewModel.next() else viewModel.previous()
                        },
                        onMenuTap = { chromeVisible = !chromeVisible },
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
                    title = "翻页动画",
                    options = ReaderPageAnim.entries,
                    selected = settings.pageAnim,
                    label = { it.label },
                    onSelect = viewModel::setPageAnim,
                )
                SettingChips(
                    title = "点击区域",
                    options = ReaderTapZone.entries,
                    selected = settings.tapZone,
                    label = { it.label },
                    onSelect = viewModel::setTapZone,
                )
                SettingSlider(
                    title = "字间距",
                    valueLabel = formatDecimal(settings.letterSpacing) + " em",
                    value = settings.letterSpacing,
                    range = 0f..0.3f,
                    steps = 5,
                    onChange = viewModel::setLetterSpacing,
                )
                SettingSlider(
                    title = "词间距",
                    valueLabel = formatDecimal(settings.wordSpacing) + " em",
                    value = settings.wordSpacing,
                    range = 0f..0.5f,
                    steps = 9,
                    onChange = viewModel::setWordSpacing,
                )
                SettingSwitch(
                    title = "两端对齐",
                    checked = settings.justify,
                    onChange = viewModel::setJustify,
                )
                SettingSwitch(
                    title = "音量键翻页",
                    checked = settings.volumeKeys,
                    onChange = viewModel::setVolumeKeys,
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
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 音量键由 Activity 统一派发：WebView 抢到焦点后 Compose 收不到按键。 */
internal object ReaderKeyEvents {
    @Volatile
    var handler: ((android.view.KeyEvent) -> Boolean)? = null
}

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

internal fun cssFor(
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
            // 翻页模式的多栏由内嵌脚本按「栏宽 = 视口宽」动态设置（见 ReaderScript），
            // 这里只保证容器能横向滚动、并且不参与纵向滚动。
            """
            body { padding: 0; margin: 0; }
            #tsu-view { -webkit-overflow-scrolling: touch; }
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
    // 翻页模式下必须压住 EPUB 自带的固定宽度/分栏，否则内容会溢出页面、页码与实际位置脱节。
    val pagedGuard = if (paged) {
        // 不能在这里用 !important 压 column-*/height：脚本是用行内样式设置分栏的，
        // 行内样式打不过 !important，压了就等于把分栏关掉。
        """
        body { overflow: visible !important; }
        img, svg, video, table, pre { max-width: 100% !important; }
        p, div, h1, h2, h3, h4, h5, h6 { break-inside: auto; }
        """.trimIndent()
    } else {
        ""
    }
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
    $pagedGuard
    p { margin: 0 0 ${s.paragraphSpacing}em 0; text-indent: ${s.textIndent}em; letter-spacing: ${s.letterSpacing}em; word-spacing: ${s.wordSpacing}em; ${if (s.justify) "text-align: justify; text-justify: inter-ideograph;" else ""} }
    $media
    h1, h2, h3 { line-height: 1.45; margin: 1em 0 0.6em; }
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
    initialRatio: Float,
    turnController: ReaderTurnController,
    onPosition: (offset: Int, atEnd: Boolean) -> Unit,
    onPageChanged: (page: Int, pageCount: Int) -> Unit,
    onEdgeTurn: (forward: Boolean) -> Unit,
    onMenuTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val viewWidthPx = maxWidth.value.roundToInt().coerceAtLeast(1)
        val viewHeightPx = maxHeight.value.roundToInt().coerceAtLeast(1)
        val vertical = readerSettings.writingMode == ReaderWritingMode.VERTICAL
        // 竖排的栏轴与横排相反，暂不做多栏分页（滚动模式可用）
        val paged = readerSettings.layout == ReaderLayout.PAGED && !vertical

        // 非 Compose 状态：记录当前偏移/相对位置，供「设置变更后重新加载」时回到原处。
        val lastOffset = remember(localPath, html) { intArrayOf(initialOffset.coerceAtLeast(0)) }
        val lastRatio = remember(localPath, html) { floatArrayOf(0f) }
        // 上一次真正加载时用的 key：只有它变了才允许重新 loadData。
        val loadedKey = remember(localPath, html) { arrayOfNulls<String>(1) }
        var webRef by remember { mutableStateOf<ReaderWebView?>(null) }

        val positionCb = rememberUpdatedState(onPosition)
        val pageCb = rememberUpdatedState(onPageChanged)
        val edgeCb = rememberUpdatedState(onEdgeTurn)
        val menuCb = rememberUpdatedState(onMenuTap)

        // 翻页动画：把旧页截成位图盖在上面滑动/淡出（WebView 自己无法做翻页动画）
        var turnFx by remember { mutableStateOf<TurnSnapshot?>(null) }
        val animProgress = remember { Animatable(1f) }
        LaunchedEffect(turnFx) {
            val current = turnFx ?: return@LaunchedEffect
            animProgress.snapTo(0f)
            val duration = if (current.style == ReaderPageAnim.FADE) 150 else 220
            animProgress.animateTo(1f, tween(durationMillis = duration, easing = LinearEasing))
            turnFx = null
            current.bitmap.recycle()
        }

        fun doTurn(forward: Boolean) {

            val web = webRef ?: return
            val style = readerSettings.pageAnim
            val snap = if (style != ReaderPageAnim.NONE) web.snapshot() else null
            web.turnPage(forward) { moved ->
                if (!moved) {
                    snap?.recycle()
                    edgeCb.value(forward)
                } else if (snap != null) {
                    turnFx = TurnSnapshot(snap, forward, style, web.width)
                }
            }
        }

        DisposableEffect(turnController) {
            turnController.turn = { forward -> doTurn(forward) }
            onDispose { turnController.turn = null }
        }

        // 正文 / 排版 / 视口任何一项变化才需要重新排版，普通重组（例如页码变化）不得重排。
        val loadKey = remember(
            localPath,
            html,
            theme,
            readerSettings,
            viewWidthPx,
            viewHeightPx,
        ) {
            listOf(
                localPath ?: html?.hashCode()?.toString() ?: "empty",
                theme.name,
                readerSettings.fontSize,
                readerSettings.fontFamily,
                readerSettings.fontWeight,
                readerSettings.lineHeight,
                readerSettings.paragraphSpacing,
                readerSettings.textIndent,
                readerSettings.horizontalPadding,
                readerSettings.writingMode,
                readerSettings.layout,
                readerSettings.letterSpacing,
                readerSettings.wordSpacing,
                readerSettings.justify,
                readerSettings.tapZone.key,
                viewWidthPx,
                viewHeightPx,
            ).joinToString("|")
        }

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(android.graphics.Color.parseColor(theme.bg))),
            factory = { context ->
                ReaderWebView(context).apply {
                    // 脚本只读 DOM 并把页码报给桥；加载前会清掉 EPUB 自带的脚本与内联事件
                    settings.javaScriptEnabled = true
                    // 让我们注入的 viewport meta 生效，否则 innerWidth 与真实排版宽度不一致
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.textZoom = 100
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // 阅读器不应该能把用户带出当前章节（点外链/广告位）
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true

                        override fun onPageFinished(view: WebView, url: String?) {
                            (view as? ReaderWebView)?.onDocumentFinished()
                        }
                    }
                    // 脚本失效只会表现为「页码不动」，所以把 console 与异常都打到日志里
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.w(
                                "TSUNDOKU_JS",
                                "${message.message()} @${message.lineNumber()}",
                            )
                            return true
                        }
                    }
                    onOffset = { offset, atEnd, ratio ->
                        lastOffset[0] = offset
                        lastRatio[0] = ratio
                        positionCb.value(offset, atEnd)
                    }
                    onPage = { page, pages -> pageCb.value(page, pages) }
                    onTapZone = { delta -> doTurn(delta > 0) }
                    this.onMenuTap = { menuCb.value.invoke() }
                }.also { webRef = it }
            },
            update = { web ->
                val style = cssFor(theme, readerSettings, viewWidthPx, viewHeightPx)
                web.pagedMode = paged
                // 关键：只有 loadKey 变了才重新加载文档。
                if (loadedKey[0] != loadKey) {
                    val firstLoad = loadedKey[0] == null
                    loadedKey[0] = loadKey
                    web.restoreOffset = lastOffset[0]
                    // 换字号/行距这类重排，用相对位置还原比绝对偏移更准
                    web.restoreRatio = if (firstLoad) initialRatio else lastRatio[0]
                    web.beginLoad()
                    val script = ReaderScript.build(
                        paged = paged,
                        padPx = readerSettings.horizontalPadding,
                        tapZone = readerSettings.tapZone,
                        vwDp = viewWidthPx,
                        vhDp = viewHeightPx,
                    )
                    loadContent(
                        web = web,
                        html = html,
                        localPath = localPath,
                        style = style,
                        script = script,
                        viewWidthDp = viewWidthPx,
                        viewHeightDp = viewHeightPx,
                    )
                }
            },
        )

        turnFx?.let { current ->
            val progress = animProgress.value
            val width = current.width.coerceAtLeast(1).toFloat()
            val direction = if (current.forward) -1f else 1f
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        when (current.style) {
                            ReaderPageAnim.FADE -> alpha = 1f - progress
                            ReaderPageAnim.COVER -> {
                                translationX = direction * width * progress
                                shadowElevation = 24f
                            }
                            else -> translationX = direction * width * progress
                        }
                    },
            )
        }
    }
}

/** 翻页动画用的旧页快照。 */
private class TurnSnapshot(
    val bitmap: android.graphics.Bitmap,
    val forward: Boolean,
    val style: ReaderPageAnim,
    val width: Int,
)

/** 让屏幕级控件（音量键、底部按钮）驱动翻页。 */
private class ReaderTurnController {
    var turn: ((forward: Boolean) -> Unit)? = null
}

/** 把正文塞进 WebView（EPUB 用本地文件，网页/TXT 用内存 HTML），并注入分页脚本。 */
private fun loadContent(
    web: WebView,
    html: String?,
    localPath: String?,
    style: String,
    script: String,
    viewWidthDp: Int,
    viewHeightDp: Int,
) {
    // 写死视口宽高（dp = CSS 像素）：device-width 在不同加载下会解析成不同的布局宽度
    val meta = "<meta name=\"viewport\" content=\"width=$viewWidthDp, " +
        "height=$viewHeightDp, initial-scale=1, user-scalable=no\">"
    when {
        localPath != null -> {
            val file = File(localPath)
            val raw = runCatching { file.readText() }.getOrNull() ?: return
            var doc = sanitizeForScript(raw)
            doc = if (doc.contains("</head>", ignoreCase = true)) {
                doc.replaceFirst("</head>", "$meta<style>$style</style></head>", ignoreCase = true)
            } else {
                "$meta<style>$style</style>$doc"
            }
            doc = if (doc.contains("</body>", ignoreCase = true)) {
                doc.replaceFirst("</body>", "<script>$script</script></body>", ignoreCase = true)
            } else {
                "$doc<script>$script</script>"
            }
            web.loadDataWithBaseURL(
                "file://${file.parentFile?.absolutePath}/",
                doc,
                "text/html",
                "utf-8",
                null,
            )
        }

        html != null -> web.loadDataWithBaseURL(
            null,
            "<!doctype html><html><head><meta charset=\"utf-8\">$meta<style>$style</style></head>" +
                "<body>$html<script>$script</script></body></html>",
            "text/html",
            "utf-8",
            null,
        )
    }
}

private val SCRIPT_TAG = Regex("(?is)<script\\b[^>]*>.*?</script>|<script\\b[^>]*/>")
private val INLINE_HANDLER = Regex("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")

/** 清掉正文自带的脚本与内联事件：分页桥只该被我们自己的脚本调用。 */
internal fun sanitizeForScript(raw: String): String =
    raw.replace(SCRIPT_TAG, "").replace(INLINE_HANDLER, "")

/**
 * 阅读器用的 WebView。
 *
 * 位置与页码优先由内嵌脚本上报（见 [ReaderScript]）：脚本在自己的 CSS 像素坐标系里测量行盒与滚动，
 * 因此不存在 CSS 像素 / 物理像素混用导致的错位与半行残字。
 * 脚本没有回报时（被禁用或异常）退回「按视口高度整页滚动」（[PagerMath]）。
 */
private class ReaderWebView(context: android.content.Context) : WebView(context) {

    var pagedMode: Boolean = false
    var restoreOffset: Int = 0

    /** >= 0 时按相对位置还原（换字号/行距后的重排更准）。 */
    var restoreRatio: Float = -1f

    var onOffset: ((offset: Int, atEnd: Boolean, ratio: Float) -> Unit)? = null
    var onPage: ((page: Int, pageCount: Int) -> Unit)? = null
    var onTapZone: ((delta: Int) -> Unit)? = null
    var onMenuTap: (() -> Unit)? = null

    private var jsActive = false
    private var restoreApplied = false

    private var fallbackMaxScroll = 0
    private var fallbackMeasuring = false
    private var fallbackRestorePass = 0
    private var fallbackUserMoved = false
    private var fallbackRestored = false

    private val bridge = PaginationBridge(
        onReady = { post { onScriptReady() } },
        onState = { page, pages, ratio, atEnd ->
            post { onScriptState(page, pages, ratio, atEnd) }
        },
        onTap = { delta -> post { onTapZone?.invoke(delta) } },
        onMenu = { post { onMenuTap?.invoke() } },
        onDebug = { message -> android.util.Log.d("TSUNDOKU_JS", message) },
    )

    init {
        runCatching { addJavascriptInterface(bridge, BRIDGE_NAME) }
    }

    // ---------- 脚本路径 ----------

    private fun onScriptReady() {
        jsActive = true
        applyRestore()
    }

    private fun onScriptState(page: Int, pages: Int, ratio: Float, atEnd: Boolean) {
        jsActive = true
        restoreApplied = true
        if (pagedMode) {
            onPage?.invoke(page, pages)
            onOffset?.invoke(page, atEnd, ratio)
        } else {
            // 滚动模式没有页码，把相对位置编码成整数存库，还原时仍优先用比例
            onOffset?.invoke((ratio * RATIO_SCALE).toInt(), atEnd, ratio)
        }
    }

    fun beginLoad() {
        restoreApplied = false
        fallbackRestored = false
        fallbackMeasuring = false
        fallbackRestorePass = 0
        fallbackUserMoved = false
    }

    /** 正文载入完成：脚本通常会自己上报 ready，没上报才走回退路径。 */
    fun onDocumentFinished() {
        postDelayed({
            if (jsActive) {
                if (!restoreApplied) applyRestore()
            } else {
                prepareFallback()
            }
        }, 300)
    }

    fun applyRestore() {
        if (!jsActive) {
            applyFallbackRestore()
            return
        }
        val js = when {
            restoreRatio >= 0f ->
                "window.__tsu&&window.__tsu.ratio(${formatRatio(restoreRatio)})"
            pagedMode ->
                "window.__tsu&&window.__tsu.page(${restoreOffset.coerceAtLeast(0)})"
            else ->
                "window.__tsu&&window.__tsu.ratio(${formatRatio(restoreOffset.coerceAtLeast(0) / RATIO_SCALE)})"
        }
        evaluateJavascript(js, null)
        restoreApplied = true
    }

    /** 翻一页；回调 false 表示已经在章首/章末，调用方据此切章。 */
    fun turnPage(forward: Boolean, onResult: (Boolean) -> Unit) {
        fallbackUserMoved = true
        if (jsActive) {
            val delta = if (forward) 1 else -1
            evaluateJavascript("window.__tsu&&window.__tsu.turn($delta)") { result ->
                onResult(result != null && result.contains("true"))
            }
            return
        }
        onResult(fallbackTurn(forward))
    }

    /** 把当前页截成位图，供翻页动画使用。 */
    fun snapshot(): android.graphics.Bitmap? = runCatching {
        if (width <= 0 || height <= 0) return null
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        draw(android.graphics.Canvas(bitmap))
        bitmap
    }.getOrNull()

    private fun formatRatio(value: Float): String =
        String.format(Locale.US, "%.5f", value.coerceIn(0f, 1f))

    // ---------- 回退路径（脚本不可用时） ----------

    private val fallbackPageHeight: Int get() = height.coerceAtLeast(1)

    private fun fallbackMaxScrollY(): Int =
        (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)

    private fun prepareFallback() {
        if (fallbackMeasuring) return
        fallbackRestored = true
        fallbackMaxScroll = fallbackMaxScrollY()
        if (scrollY > fallbackMaxScroll) {
            fallbackMeasuring = true
            scrollTo(scrollX, fallbackMaxScroll)
            fallbackMeasuring = false
        }
        if (fallbackRestorePass == 0) {
            applyFallbackRestore()
        } else if (fallbackRestorePass == 1 && !fallbackUserMoved) {
            applyFallbackRestore()
        }
        fallbackPublish()
    }

    private fun applyFallbackRestore() {
        fallbackRestorePass++
        val max = fallbackMaxScrollY()
        fallbackMaxScroll = max
        val target = when {
            restoreRatio >= 0f -> PagerMath.offsetForRatio(restoreRatio, max)
            pagedMode -> PagerMath.pageOffset(restoreOffset.coerceAtLeast(0), max, fallbackPageHeight)
            else -> restoreOffset.coerceIn(0, max)
        }
        if (target != scrollY) {
            fallbackMeasuring = true
            scrollTo(scrollX, target)
            fallbackMeasuring = false
        }
        fallbackRestored = true
        fallbackPublish()
    }

    private fun fallbackTurn(forward: Boolean): Boolean {
        if (fallbackMeasuring) return false
        fallbackMaxScroll = fallbackMaxScrollY()
        val before = scrollY
        val target = PagerMath.targetPageScroll(before, forward, fallbackMaxScroll, fallbackPageHeight)
        if (target == before) return false
        scrollTo(scrollX, target)
        if (scrollY == before) return false
        fallbackPublish()
        return true
    }

    private fun fallbackPublish() {
        if (fallbackMeasuring || !fallbackRestored) return
        fallbackMaxScroll = fallbackMaxScrollY()
        val ratio = PagerMath.ratio(scrollY, fallbackMaxScroll)
        val atEnd = PagerMath.isAtEnd(scrollY, fallbackMaxScroll, if (pagedMode) 2 else 24)
        if (pagedMode) {
            onPage?.invoke(
                PagerMath.pageOf(scrollY, fallbackMaxScroll, fallbackPageHeight),
                PagerMath.pageCount(fallbackMaxScroll, fallbackPageHeight),
            )
            onOffset?.invoke(
                PagerMath.pageOf(scrollY, fallbackMaxScroll, fallbackPageHeight),
                atEnd,
                ratio,
            )
        } else {
            onOffset?.invoke(scrollY, atEnd, ratio)
        }
    }

    private companion object {
        const val BRIDGE_NAME = "TsundokuBridge"


        /** 滚动模式把比例编码成整数存库时的倍数。 */
        const val RATIO_SCALE = 1_000_000f
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

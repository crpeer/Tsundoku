package app.tsundoku.reader.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.data.prefs.ReaderSettings
import app.tsundoku.reader.data.prefs.ReaderTheme
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.ui.common.SectionTitle
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MoreViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = container.settings.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    val sources: StateFlow<List<BookSourceInfo>> = container.sources.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val message = MutableStateFlow<String?>(null)

    fun import(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.importer.importUri(uri) }
                .onSuccess { message.value = "已导入到书架" }
                .onFailure { message.value = "导入失败：${it.message}" }
        }
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch { container.settings.setFontSize(size) }
    }

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { container.settings.setTheme(theme) }
    }

    fun consumeMessage() {
        message.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onAddSource: () -> Unit,
    onManageSources: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val viewModel = appViewModel { MoreViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("更多", fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("积读", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Mihon 风格的多来源电子书阅读器 · v0.1.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SectionTitle("书库", Modifier.padding(horizontal = 16.dp)) }

            item {
                ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    },
                    headlineContent = { Text("导入本地书籍") },
                    supportingContent = { Text("支持 EPUB / TXT / PDF") },
                    modifier = Modifier.clickable {
                        picker.launch(
                            arrayOf(
                                "application/epub+zip",
                                "text/plain",
                                "application/pdf",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SectionTitle("来源", Modifier.padding(horizontal = 16.dp)) }

            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    headlineContent = { Text("添加来源") },
                    supportingContent = { Text("网页源 / OPDS 目录 / RSS 订阅") },
                    modifier = Modifier.clickable(onClick = onAddSource),
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Source, contentDescription = null) },
                    headlineContent = { Text("管理来源") },
                    supportingContent = { Text("已添加 ${sources.size} 个来源") },
                    modifier = Modifier.clickable(onClick = onManageSources),
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SectionTitle("阅读设置", Modifier.padding(horizontal = 16.dp)) }

            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    headlineContent = { Text("正文字号") },
                    supportingContent = { Text("${settings.fontSize} px") },
                    trailingContent = {
                        Slider(
                            value = settings.fontSize.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 13f..34f,
                            steps = 20,
                            modifier = Modifier.size(width = 160.dp, height = 40.dp),
                        )
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReaderTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = theme == settings.theme,
                            onClick = { viewModel.setTheme(theme) },
                            label = { Text(theme.label) },
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SectionTitle("关于", Modifier.padding(horizontal = 16.dp)) }

            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    headlineContent = { Text("积读 Tsundoku") },
                    supportingContent = {
                        Text(
                            "声明：本应用不内置任何书籍内容，请仅添加你有权访问的来源。",
                        )
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

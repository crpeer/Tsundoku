package app.tsundoku.reader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.ui.common.EmptyState
import app.tsundoku.reader.ui.common.SectionTitle
import app.tsundoku.reader.ui.common.SourceAvatar
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class BrowseViewModel(container: AppContainer) : ViewModel() {
    val sources: StateFlow<List<BookSourceInfo>> = container.sources.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenSource: (Long) -> Unit,
    onGlobalSearch: () -> Unit,
    onAddSource: () -> Unit,
    onManageSources: () -> Unit,
) {
    val viewModel = appViewModel { BrowseViewModel(it) }
    val sources by viewModel.sources.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发现", fontWeight = FontWeight.SemiBold) },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onGlobalSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable(onClick = onGlobalSearch),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全局搜索", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "在所有已启用的来源里同时搜索",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
            }

            item { SectionTitle("来源", Modifier.padding(horizontal = 16.dp)) }

            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有来源",
                        hint = "添加网页源、OPDS 目录或 RSS 订阅后就能在这里浏览和搜索",
                        actionLabel = "添加来源",
                        onAction = onAddSource,
                    )
                }
            }

            items(sources, key = { it.id }) { source ->
                ListItem(
                    leadingContent = { SourceAvatar(source.name, Modifier.size(42.dp)) },
                    headlineContent = { Text(source.name, maxLines = 1) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(source.type.label)
                                if (source.lang.isNotBlank()) append(" · ").append(source.lang)
                                if (!source.enabled) append(" · 已停用")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onOpenSource(source.id) },
                )
            }

            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    headlineContent = { Text("添加来源") },
                    modifier = Modifier.clickable(onClick = onAddSource),
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    headlineContent = { Text("管理来源") },
                    modifier = Modifier.clickable(onClick = onManageSources),
                )
            }
        }
    }
}

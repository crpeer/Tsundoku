package app.tsundoku.reader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.OpdsConfig
import app.tsundoku.reader.domain.model.RssConfig
import app.tsundoku.reader.domain.model.SourceConfigs
import app.tsundoku.reader.domain.model.SourceType
import app.tsundoku.reader.domain.model.WebSourceConfig
import app.tsundoku.reader.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val WEB_TEMPLATE = """
{
  "name": "示例站点",
  "base_url": "https://example.com",
  "lang": "zh",
  "search": {
    "url": "/search?q={{query}}&page={{page}}",
    "item": ".book-item",
    "title": "h3 a@text",
    "link": "h3 a@href",
    "cover": "img@src",
    "author": ".author@text"
  },
  "browse": {
    "url": "/latest?page={{page}}",
    "item": ".book-item",
    "title": "h3 a@text",
    "link": "h3 a@href",
    "cover": "img@src"
  },
  "details": {
    "title": "h1@text",
    "author": ".author@text",
    "cover": ".cover img@src",
    "description": "#intro@text"
  },
  "chapters": {
    "item": "#list dd a",
    "title": "@text",
    "link": "@href",
    "reverse": true
  },
  "content": {
    "selector": "#content",
    "remove": ["script", "style", ".ads"]
  }
}
""".trimIndent()

class AddSourceViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val type: SourceType = SourceType.WEB,
        val name: String = "",
        val url: String = "",
        val searchUrl: String = "",
        val lang: String = "zh",
        val config: String = WEB_TEMPLATE,
        val saving: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setType(type: SourceType) {
        _state.update { current ->
            when (type) {
                SourceType.WEB -> current.copy(type = type, config = WEB_TEMPLATE)
                else -> current.copy(type = type)
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setUrl(value: String) = _state.update { it.copy(url = value) }

    fun setSearchUrl(value: String) = _state.update { it.copy(searchUrl = value) }

    fun setLang(value: String) = _state.update { it.copy(lang = value) }

    fun setConfig(value: String) = _state.update { it.copy(config = value) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val result = runCatching {
                val json: String
                val finalName: String
                when (current.type) {
                    SourceType.WEB -> {
                        val parsed = SourceConfigs.json.decodeFromString<WebSourceConfig>(current.config)
                        require(parsed.baseUrl.isNotBlank()) { "base_url 不能为空" }
                        require(parsed.name.isNotBlank()) { "name 不能为空" }
                        json = SourceConfigs.json.encodeToString(parsed)
                        finalName = parsed.name
                    }

                    SourceType.OPDS -> {
                        require(current.name.isNotBlank()) { "名称不能为空" }
                        require(current.url.isNotBlank()) { "目录地址不能为空" }
                        json = SourceConfigs.json.encodeToString(
                            OpdsConfig(
                                name = current.name.trim(),
                                url = current.url.trim(),
                                lang = current.lang.trim(),
                                searchUrl = current.searchUrl.trim(),
                            ),
                        )
                        finalName = current.name.trim()
                    }

                    SourceType.RSS -> {
                        require(current.name.isNotBlank()) { "名称不能为空" }
                        require(current.url.isNotBlank()) { "订阅地址不能为空" }
                        json = SourceConfigs.json.encodeToString(
                            RssConfig(
                                name = current.name.trim(),
                                feedUrl = current.url.trim(),
                                lang = current.lang.trim(),
                                title = current.name.trim(),
                            ),
                        )
                        finalName = current.name.trim()
                    }

                    SourceType.LOCAL -> error("本地来源无需添加")
                }
                container.sources.add(
                    BookSourceInfo(
                        id = 0,
                        name = finalName,
                        type = current.type,
                        lang = current.lang.trim().ifBlank { "zh" },
                        enabled = true,
                        config = json,
                        iconUrl = null,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
            result.onSuccess {
                _state.update { it.copy(saving = false, done = true) }
            }.onFailure { error ->
                _state.update { it.copy(saving = false, error = error.message ?: "保存失败") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { AddSourceViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        if (state.done) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加来源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving) {
                        Text(if (state.saving) "保存中…" else "保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SourceType.WEB, SourceType.OPDS, SourceType.RSS).forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.label) },
                    )
                }
            }

            if (state.type == SourceType.WEB) {
                Text(
                    "网页源用 JSON 描述抓取规则，格式为 `css选择器@属性`，" +
                        "支持 {{query}} 与 {{page}} 占位符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.config,
                    onValueChange = viewModel::setConfig,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                    label = { Text("源配置 JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            } else {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("来源名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::setUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (state.type == SourceType.OPDS) "OPDS 目录地址" else "RSS / Atom 订阅地址")
                    },
                    singleLine = true,
                )
                if (state.type == SourceType.OPDS) {
                    OutlinedTextField(
                        value = state.searchUrl,
                        onValueChange = viewModel::setSearchUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索地址（可选，支持 {{query}}）") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = state.lang,
                    onValueChange = viewModel::setLang,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("语言代码") },
                    singleLine = true,
                )
            }

            if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存来源") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

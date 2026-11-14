package app.tsundoku.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ReaderTheme(val label: String, val bg: String, val fg: String) {
    SYSTEM("跟随系统", "#FFFFFF", "#1B1B1B"),
    PAPER("纸质", "#F5EFE1", "#2B2118"),
    LIGHT("白纸", "#FFFFFF", "#1B1B1B"),
    GREEN("护眼", "#CCE8CF", "#1F2B22"),
    DARK("夜间", "#14161A", "#C9CDD4"),
    ;

    /** SYSTEM resolves against the platform dark-mode flag; others are fixed. */
    fun resolved(systemDark: Boolean): ReaderTheme =
        if (this == SYSTEM) (if (systemDark) DARK else LIGHT) else this
}

enum class ReaderFontFamily(val label: String, val css: String) {
    DEFAULT("默认", "inherit"),
    SERIF("宋体", "\"Noto Serif CJK SC\", \"Source Han Serif SC\", \"Songti SC\", serif"),
    SANS("黑体", "\"Noto Sans CJK SC\", \"Source Han Sans SC\", sans-serif"),
    KAI("楷体", "\"Kaiti SC\", \"KaiTi\", \"STKaiti\", serif"),
    MONO("等宽", "\"Noto Sans Mono CJK SC\", monospace"),
}

enum class ReaderFontWeight(val label: String, val css: Int) {
    NORMAL("常规", 400),
    MEDIUM("中等", 500),
    BOLD("加粗", 700),
}

enum class ReaderWritingMode(val label: String, val css: String) {
    HORIZONTAL("横排", "horizontal-tb"),
    VERTICAL("竖排", "vertical-rl"),
}

/** 阅读模式：整章滚动，或左右翻页（Mihon 式的 pager 手感）。 */
enum class ReaderLayout(val label: String) {
    SCROLL("滚动"),
    PAGED("翻页"),
}

data class ReaderSettings(
    val fontSize: Int = 19,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    val fontWeight: ReaderFontWeight = ReaderFontWeight.NORMAL,
    val lineHeight: Float = 1.85f,
    val paragraphSpacing: Float = 0.85f,
    val textIndent: Float = 2f,
    val horizontalPadding: Int = 20,
    val writingMode: ReaderWritingMode = ReaderWritingMode.HORIZONTAL,
    val layout: ReaderLayout = ReaderLayout.SCROLL,
    /** true = meta.json 写进书籍目录；false = 写到 files/meta/<id>.json。 */
    val metadataInBookDir: Boolean = false,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val fontSizeKey = intPreferencesKey("reader_font_size")
    private val themeKey = stringPreferencesKey("reader_theme")
    private val fontFamilyKey = stringPreferencesKey("reader_font_family")
    private val fontWeightKey = stringPreferencesKey("reader_font_weight")
    private val lineHeightKey = floatPreferencesKey("reader_line_height")
    private val paragraphSpacingKey = floatPreferencesKey("reader_paragraph_spacing")
    private val textIndentKey = floatPreferencesKey("reader_text_indent")
    private val paddingKey = intPreferencesKey("reader_horizontal_padding")
    private val writingModeKey = stringPreferencesKey("reader_writing_mode")
    private val metaInBookDirKey = booleanPreferencesKey("metadata_in_book_dir")
    private val layoutKey = stringPreferencesKey("reader_layout")

    val readerSettings: Flow<ReaderSettings> = context.settingsDataStore.data.map { prefs ->
        ReaderSettings(
            fontSize = prefs[fontSizeKey] ?: 19,
            theme = enumOf(prefs[themeKey], ReaderTheme.SYSTEM),
            fontFamily = enumOf(prefs[fontFamilyKey], ReaderFontFamily.DEFAULT),
            fontWeight = enumOf(prefs[fontWeightKey], ReaderFontWeight.NORMAL),
            lineHeight = prefs[lineHeightKey] ?: 1.85f,
            paragraphSpacing = prefs[paragraphSpacingKey] ?: 0.85f,
            textIndent = prefs[textIndentKey] ?: 2f,
            horizontalPadding = prefs[paddingKey] ?: 20,
            writingMode = enumOf(prefs[writingModeKey], ReaderWritingMode.HORIZONTAL),
            metadataInBookDir = prefs[metaInBookDirKey] ?: false,
            layout = enumOf(prefs[layoutKey], ReaderLayout.SCROLL),
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    suspend fun setFontSize(size: Int) {
        context.settingsDataStore.edit { it[fontSizeKey] = size.coerceIn(13, 34) }
    }

    suspend fun setTheme(theme: ReaderTheme) {
        context.settingsDataStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setFontFamily(family: ReaderFontFamily) {
        context.settingsDataStore.edit { it[fontFamilyKey] = family.name }
    }

    suspend fun setFontWeight(weight: ReaderFontWeight) {
        context.settingsDataStore.edit { it[fontWeightKey] = weight.name }
    }

    suspend fun setLineHeight(value: Float) {
        context.settingsDataStore.edit { it[lineHeightKey] = value.coerceIn(1.2f, 2.6f) }
    }

    suspend fun setParagraphSpacing(value: Float) {
        context.settingsDataStore.edit { it[paragraphSpacingKey] = value.coerceIn(0f, 2f) }
    }

    suspend fun setTextIndent(value: Float) {
        context.settingsDataStore.edit { it[textIndentKey] = value.coerceIn(0f, 3f) }
    }

    suspend fun setHorizontalPadding(value: Int) {
        context.settingsDataStore.edit { it[paddingKey] = value.coerceIn(12, 40) }
    }

    suspend fun setWritingMode(mode: ReaderWritingMode) {
        context.settingsDataStore.edit { it[writingModeKey] = mode.name }
    }

    suspend fun metadataInBookDir(): Boolean =
        context.settingsDataStore.data.map { it[metaInBookDirKey] ?: false }.first()

    suspend fun setMetadataInBookDir(value: Boolean) {
        context.settingsDataStore.edit { it[metaInBookDirKey] = value }
    }

    suspend fun setLayout(layout: ReaderLayout) {
        context.settingsDataStore.edit { it[layoutKey] = layout.name }
    }
}

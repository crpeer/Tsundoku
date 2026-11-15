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

/** 翻页动画：无 / 滑动 / 覆盖 / 淡入（仿真翻页成本高，暂不提供）。 */
enum class ReaderPageAnim(val label: String) {
    NONE("无"),
    SLIDE("滑动"),
    COVER("覆盖"),
    FADE("淡入"),
}

/** 点击区域布局，参考 Mihon 的 NavigationMode。 */
enum class ReaderTapZone(val label: String, val key: String) {
    DEFAULT("三栏", "default"),
    L_SHAPED("L 形", "lshape"),
    KINDLE("Kindle 式", "kindle"),
    EDGE("边缘", "edge"),
    RIGHT_LEFT("左右两栏", "rightleft"),
    DISABLED("禁用", "disabled"),
}

/** 跨页显示：从不 / 总是 / 仅宽屏（横屏）。 */
enum class ReaderDualPage(val label: String) {
    NEVER("从不"),
    ALWAYS("总是"),
    WIDE("仅宽屏"),
}

/** 进度显示：百分比 / 分数。 */
enum class ReaderProgressStyle(val label: String) {
    PERCENT("百分比"),
    FRACTION("分数"),
}

/** 中文变体转换（简繁）。 */
enum class ReaderChineseVariant(val label: String) {
    NONE("不转换"),
    S_TO_T("简转繁"),
    T_TO_S("繁转简"),
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
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 0f,
    val justify: Boolean = false,
    val pageAnim: ReaderPageAnim = ReaderPageAnim.NONE,
    val tapZone: ReaderTapZone = ReaderTapZone.DEFAULT,
    val volumeKeys: Boolean = false,
    val dualPage: ReaderDualPage = ReaderDualPage.NEVER,
    val highlightOpacity: Float = 0.35f,
    val autoScrollSpeed: Int = 40,
    val chineseVariant: ReaderChineseVariant = ReaderChineseVariant.NONE,
    val replaceQuotes: Boolean = false,
    val showHeaderFooter: Boolean = false,
    val progressStyle: ReaderProgressStyle = ReaderProgressStyle.FRACTION,
    val readingRuler: Boolean = false,
    val paragraphFocus: Boolean = false,
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
    private val letterSpacingKey = floatPreferencesKey("reader_letter_spacing")
    private val wordSpacingKey = floatPreferencesKey("reader_word_spacing")
    private val justifyKey = booleanPreferencesKey("reader_justify")
    private val pageAnimKey = stringPreferencesKey("reader_page_anim")
    private val tapZoneKey = stringPreferencesKey("reader_tap_zone")
    private val volumeKeysKey = booleanPreferencesKey("reader_volume_keys")
    private val dualPageKey = stringPreferencesKey("reader_dual_page")
    private val highlightOpacityKey = floatPreferencesKey("reader_highlight_opacity")
    private val autoScrollSpeedKey = intPreferencesKey("reader_auto_scroll_speed")
    private val chineseVariantKey = stringPreferencesKey("reader_chinese_variant")
    private val replaceQuotesKey = booleanPreferencesKey("reader_replace_quotes")
    private val headerFooterKey = booleanPreferencesKey("reader_header_footer")
    private val progressStyleKey = stringPreferencesKey("reader_progress_style")
    private val readingRulerKey = booleanPreferencesKey("reader_reading_ruler")
    private val paragraphFocusKey = booleanPreferencesKey("reader_paragraph_focus")

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
            letterSpacing = prefs[letterSpacingKey] ?: 0f,
            wordSpacing = prefs[wordSpacingKey] ?: 0f,
            justify = prefs[justifyKey] ?: false,
            pageAnim = enumOf(prefs[pageAnimKey], ReaderPageAnim.NONE),
            tapZone = enumOf(prefs[tapZoneKey], ReaderTapZone.DEFAULT),
            volumeKeys = prefs[volumeKeysKey] ?: false,
            dualPage = enumOf(prefs[dualPageKey], ReaderDualPage.NEVER),
            highlightOpacity = prefs[highlightOpacityKey] ?: 0.35f,
            autoScrollSpeed = prefs[autoScrollSpeedKey] ?: 40,
            chineseVariant = enumOf(prefs[chineseVariantKey], ReaderChineseVariant.NONE),
            replaceQuotes = prefs[replaceQuotesKey] ?: false,
            showHeaderFooter = prefs[headerFooterKey] ?: false,
            progressStyle = enumOf(prefs[progressStyleKey], ReaderProgressStyle.FRACTION),
            readingRuler = prefs[readingRulerKey] ?: false,
            paragraphFocus = prefs[paragraphFocusKey] ?: false,
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

    suspend fun setLetterSpacing(value: Float) {
        context.settingsDataStore.edit { it[letterSpacingKey] = value.coerceIn(0f, 0.3f) }
    }

    suspend fun setWordSpacing(value: Float) {
        context.settingsDataStore.edit { it[wordSpacingKey] = value.coerceIn(0f, 0.5f) }
    }

    suspend fun setJustify(value: Boolean) {
        context.settingsDataStore.edit { it[justifyKey] = value }
    }

    suspend fun setPageAnim(anim: ReaderPageAnim) {
        context.settingsDataStore.edit { it[pageAnimKey] = anim.name }
    }

    suspend fun setTapZone(zone: ReaderTapZone) {
        context.settingsDataStore.edit { it[tapZoneKey] = zone.name }
    }

    suspend fun setVolumeKeys(value: Boolean) {
        context.settingsDataStore.edit { it[volumeKeysKey] = value }
    }

    suspend fun setDualPage(value: ReaderDualPage) {
        context.settingsDataStore.edit { it[dualPageKey] = value.name }
    }

    suspend fun setHighlightOpacity(value: Float) {
        context.settingsDataStore.edit { it[highlightOpacityKey] = value.coerceIn(0.1f, 0.8f) }
    }

    suspend fun setAutoScrollSpeed(value: Int) {
        context.settingsDataStore.edit { it[autoScrollSpeedKey] = value.coerceIn(10, 100) }
    }

    suspend fun setChineseVariant(value: ReaderChineseVariant) {
        context.settingsDataStore.edit { it[chineseVariantKey] = value.name }
    }

    suspend fun setReplaceQuotes(value: Boolean) {
        context.settingsDataStore.edit { it[replaceQuotesKey] = value }
    }

    suspend fun setShowHeaderFooter(value: Boolean) {
        context.settingsDataStore.edit { it[headerFooterKey] = value }
    }

    suspend fun setProgressStyle(style: ReaderProgressStyle) {
        context.settingsDataStore.edit { it[progressStyleKey] = style.name }
    }

    suspend fun setReadingRuler(value: Boolean) {
        context.settingsDataStore.edit { it[readingRulerKey] = value }
    }

    suspend fun setParagraphFocus(value: Boolean) {
        context.settingsDataStore.edit { it[paragraphFocusKey] = value }
    }
}

package app.tsundoku.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderTheme(val label: String, val bg: String, val fg: String) {
    PAPER("纸质", "#F5EFE1", "#2B2118"),
    LIGHT("白纸", "#FFFFFF", "#1B1B1B"),
    GREEN("护眼", "#CCE8CF", "#1F2B22"),
    DARK("夜间", "#14161A", "#C9CDD4"),
}

data class ReaderSettings(
    val fontSize: Int = 19,
    val theme: ReaderTheme = ReaderTheme.PAPER,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val fontSizeKey = intPreferencesKey("reader_font_size")
    private val themeKey = stringPreferencesKey("reader_theme")

    val readerSettings: Flow<ReaderSettings> = context.settingsDataStore.data.map { prefs ->
        ReaderSettings(
            fontSize = prefs[fontSizeKey] ?: 19,
            theme = runCatching { ReaderTheme.valueOf(prefs[themeKey] ?: "PAPER") }
                .getOrDefault(ReaderTheme.PAPER),
        )
    }

    suspend fun setFontSize(size: Int) {
        context.settingsDataStore.edit { it[fontSizeKey] = size.coerceIn(13, 34) }
    }

    suspend fun setTheme(theme: ReaderTheme) {
        context.settingsDataStore.edit { it[themeKey] = theme.name }
    }
}

package app.tsundoku.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4C41),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADFD8),
    onPrimaryContainer = Color(0xFF2B1A12),
    secondary = Color(0xFF8D6E63),
    secondaryContainer = Color(0xFFF3E5DC),
    onSecondaryContainer = Color(0xFF3A2A22),
    background = Color(0xFFFBF6EE),
    onBackground = Color(0xFF241C16),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF241C16),
    surfaceVariant = Color(0xFFF0E7DC),
    onSurfaceVariant = Color(0xFF5B4A40),
    outline = Color(0xFFB9A79C),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD7BFAE),
    onPrimary = Color(0xFF3B2A1F),
    primaryContainer = Color(0xFF4A372C),
    onPrimaryContainer = Color(0xFFF2E2D6),
    secondary = Color(0xFFC3A899),
    secondaryContainer = Color(0xFF3A2E27),
    onSecondaryContainer = Color(0xFFEDE0D6),
    background = Color(0xFF15120F),
    onBackground = Color(0xFFEDE3D8),
    surface = Color(0xFF1C1815),
    onSurface = Color(0xFFEDE3D8),
    surfaceVariant = Color(0xFF2A2521),
    onSurfaceVariant = Color(0xFFCBBDB1),
    outline = Color(0xFF6D5C51),
    error = Color(0xFFF2B8B5),
)

@Composable
fun TsundokuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

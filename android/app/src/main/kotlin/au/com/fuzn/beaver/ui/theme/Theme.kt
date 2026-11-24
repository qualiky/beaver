package au.com.fuzn.beaver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Warm brown/amber seed (matches the iOS accent). Static schemes are tonal sets
// around the seed 0xFF8B5A2B; dynamic color is optional and off by default.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8B5A2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBD),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF745944),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2A1708),
    tertiary = Color(0xFF8A6D00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF211A15),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF211A15),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB86E),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6A3E10),
    onPrimaryContainer = Color(0xFFFFDCBD),
    secondary = Color(0xFFE3BFA5),
    onSecondary = Color(0xFF422B19),
    secondaryContainer = Color(0xFF5A412E),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFEBC248),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4400),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF211A15),
    onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF211A15),
    onSurface = Color(0xFFEDE0D8),
)

@Composable
fun BeaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

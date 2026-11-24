package au.com.fuzn.beaver.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import au.com.fuzn.beaver.core.Money

/** Status of a figure. Always paired with a symbol or a word so colour is never the only signal. */
enum class Tone {
    Positive, Negative, Warning, Neutral;

    val symbol: ImageVector?
        get() = when (this) {
            Positive -> Icons.Filled.NorthEast
            Negative -> Icons.Filled.SouthEast
            Warning -> Icons.Filled.Warning
            Neutral -> null
        }

    @Composable
    fun color(): Color = when (this) {
        Neutral -> MaterialTheme.colorScheme.onSurface
        else -> Palette.color(this, dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    }

    companion object {
        fun of(money: Money): Tone = when {
            money.cents > 0 -> Positive
            money.cents < 0 -> Negative
            else -> Neutral
        }
    }
}

/** Semantic colours with light and dark variants (the stock green and red are too pale as text). */
object Palette {
    val positiveLight = Color(0xFF006B2E)
    val positiveDark = Color(0xFF5CD985)
    val negativeLight = Color(0xFFBD1419)
    val negativeDark = Color(0xFFFF756B)
    val warningLight = Color(0xFF9E5700)
    val warningDark = Color(0xFFFFBD47)

    /** Background of the startup-error banner (white text on it passes contrast). */
    val errorBanner = Color(0xFF9E1217)

    fun color(tone: Tone, dark: Boolean): Color = when (tone) {
        Tone.Positive -> if (dark) positiveDark else positiveLight
        Tone.Negative -> if (dark) negativeDark else negativeLight
        Tone.Warning -> if (dark) warningDark else warningLight
        Tone.Neutral -> Color.Unspecified
    }
}

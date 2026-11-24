package au.com.fuzn.beaver.ui.roster

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.HoursWarningLevel
import au.com.fuzn.beaver.core.Money

/** Tabular (equal-width) digits so columns of money line up. */
internal val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun positiveColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7CDB8C) else Color(0xFF1B6E2D)

@Composable
internal fun negativeColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)

@Composable
internal fun warningColor(level: HoursWarningLevel): Color =
    if (level == HoursWarningLevel.hard) negativeColor()
    else if (isSystemInDarkTheme()) Color(0xFFFFB95C) else Color(0xFF8A5100)

/** Money text with tabular digits, formatted from integer cents. */
@Composable
internal fun RosterMoneyText(
    money: Money,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(RosterFormat.money(money, currency), modifier, color = color, style = style.merge(TabularNumbers))
}

/** A small capsule label for a band (Day, Eve, PH ...). */
@Composable
internal fun BandChip(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** The public holiday chip, with its name. */
@Composable
internal fun HolidayChip(name: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.semantics { contentDescription = "Public holiday: $name" },
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                " $name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** An inline hours-limit warning. Shown as text with an icon (not colour alone); never blocks anything. */
@Composable
internal fun WarningRow(text: String, level: HoursWarningLevel, modifier: Modifier = Modifier) {
    val color = warningColor(level)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Warning: $text" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (level == HoursWarningLevel.hard) Icons.Filled.Error else Icons.Filled.Warning,
            contentDescription = null, tint = color, modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** "+$12.00 above expected" in green, "-$4.00 below expected" in red. */
@Composable
internal fun DifferenceLabel(difference: Money, currency: String, modifier: Modifier = Modifier) {
    val color = when {
        difference.cents > 0 -> positiveColor()
        difference.cents < 0 -> negativeColor()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = if (difference.cents == 0L) "On target"
    else "${RosterFormat.signedMoney(difference, currency)} ${if (difference.cents > 0) "above" else "below"}"
    val spoken = if (difference.cents == 0L) "Exactly as expected" else
        "${RosterFormat.money(Money(Math.abs(difference.cents)), currency)} ${if (difference.cents > 0) "above" else "below"} expected"
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                difference.cents > 0 -> Icons.Filled.ArrowUpward
                difference.cents < 0 -> Icons.Filled.ArrowDownward
                else -> Icons.Filled.Remove
            },
            contentDescription = null, tint = color, modifier = Modifier.size(16.dp),
        )
        Text(" $text", color = color, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
    }
}

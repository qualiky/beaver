package au.com.fuzn.beaver.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.BillCategory

/** Tabular (fixed-width) digits, so columns of figures line up and do not jitter as values change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * A figure in its status colour, with tabular digits. Colour is never the only signal: pass a [Tone] symbol or
 * words alongside it. The spoken form reads "-$5.00" as "minus $5.00".
 */
@Composable
fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier.semantics { contentDescription = spokenMoney(text) },
        color = if (tone == Tone.Neutral) Color.Unspecified else tone.color(),
        style = style.tabular(),
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Small uppercase caption above a group of rows. Announced as a heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A titled card. The title is a heading for screen readers. */
@Composable
fun CardView(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** A tinted banner with an icon, a title, a message and optional extra content (a bill list, a button). */
@Composable
fun BannerView(
    tone: Tone,
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val color = tone.color()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}

/**
 * Shown above the whole app when the database could not be opened and Beaver fell back to memory.
 * It cannot be dismissed: nothing entered in this state survives closing the app.
 */
@Composable
fun StartupErrorBanner(detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .background(Palette.errorBanner)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Your changes won't be saved", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Text(
            "Beaver couldn't use its database, so it is running in temporary memory. Anything you enter will be lost " +
                "when you close the app. Restart the app; if this keeps happening, free up storage on your phone.",
            color = Color.White, style = MaterialTheme.typography.bodySmall,
        )
        Text(detail, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A +/- control for a money or count value. Each button is a 48dp touch target with its own label, and the whole
 * row announces "label, value".
 */
@Composable
fun StepperRow(
    label: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            MoneyText(valueText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics {
                stateDescription = spokenMoney(valueText)
            })
        }
        IconButton(onClick = onDecrement, enabled = canDecrement) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
        }
        IconButton(onClick = onIncrement, enabled = canIncrement) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label")
        }
    }
}

fun billIcon(category: BillCategory): ImageVector = when (category) {
    BillCategory.housing -> Icons.Filled.Home
    BillCategory.utilities -> Icons.Filled.Bolt
    BillCategory.subscription -> Icons.Filled.Subscriptions
    BillCategory.transport -> Icons.Filled.Tram
    BillCategory.debt -> Icons.Filled.CreditCard
    BillCategory.insurance -> Icons.Filled.Security
    BillCategory.other -> Icons.Filled.Description
}

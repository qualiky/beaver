package au.com.fuzn.beaver.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.PayFrequency
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.SectionLabel
import au.com.fuzn.beaver.ui.editors.DropdownField
import au.com.fuzn.beaver.ui.editors.EditorLabels
import au.com.fuzn.beaver.ui.editors.MoneyField
import au.com.fuzn.beaver.ui.editors.MoneyParser
import au.com.fuzn.beaver.ui.editors.SwitchRow
import au.com.fuzn.beaver.ui.theme.BeaverTheme

/** What the list needs to show. Immutable, so previews build it from the demo data. */
data class SettingsListData(
    val input: ForecastInput,
    val settings: AppSettings,
    val tables: List<WithholdingTable>,
    val version: String,
    val reminderOn: Boolean,
    val reminderMessage: String?,
    val lastExportedText: String,
)

/** What the list can do. Everything is a callback, so the list holds no state of its own except the budget text. */
class SettingsListActions(
    val open: (SettingsRoute) -> Unit = {},
    val setCurrency: (String) -> Unit = {},
    val saveBudget: (String) -> Unit = {},
    val export: () -> Unit = {},
    val import: () -> Unit = {},
    val loadDemo: () -> Unit = {},
    val erase: () -> Unit = {},
    val setReminder: (Boolean) -> Unit = {},
)

val CURRENCIES = listOf("AUD", "NZD", "USD", "GBP", "EUR", "CAD", "SGD", "JPY")

/** The scrolling content of the Settings screen: income, rate cards, tax tables, bills, goals, preferences, data, About. */
@Composable
fun SettingsListContent(data: SettingsListData, actions: SettingsListActions, modifier: Modifier = Modifier) {
    val input = data.input
    val currency = data.settings.currency
    val fmt = FigureFormatter(currency)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Group("Income") {
            input.sources.forEach { source ->
                SettingsRow(source.name, subtitle = sourceSubtitle(source), onClick = { actions.open(SettingsRoute.Source(source.id)) })
                if (source.kind == IncomeKind.hourly) {
                    SettingsRow(
                        "Default week: ${source.name}", icon = Icons.Filled.CalendarMonth,
                        onClick = { actions.open(SettingsRoute.Templates(source.id)) },
                    )
                }
            }
            SettingsRow("Add income source", icon = Icons.Filled.Add, onClick = { actions.open(SettingsRoute.SourceNew) })
        }

        Group("Rate cards") {
            input.rateCards.forEach { card -> SettingsRow(card.name, onClick = { actions.open(SettingsRoute.Card(card.id)) }) }
            SettingsRow("Add rate card", icon = Icons.Filled.Add, onClick = { actions.open(SettingsRoute.CardNew) })
        }

        Group(
            "Withholding tables",
            footer = "Verify against official sources. The built-in table may be out of date. Duplicate it to edit your own copy.",
        ) {
            data.tables.forEach { table ->
                if (SettingsState.isEditable(table)) {
                    SettingsRow(table.name, icon = Icons.Filled.Percent, onClick = { actions.open(SettingsRoute.Table(table.id)) })
                } else {
                    SettingsRow(
                        table.name, subtitle = "Built in, read-only", icon = Icons.Filled.Lock,
                        onClick = { actions.open(SettingsRoute.Table(table.id)) },
                    )
                }
            }
        }

        Group("Bills") {
            input.bills.forEach { bill ->
                SettingsRow(
                    bill.name, subtitle = EditorLabels.categoryTitle(bill.category),
                    trailing = { MoneyText(fmt.string(bill.amount), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { actions.open(SettingsRoute.Bill(bill.id)) },
                )
            }
            SettingsRow("Add bill", icon = Icons.Filled.Add, onClick = { actions.open(SettingsRoute.BillNew) })
        }

        Group("Goals") {
            val primary = input.primaryGoal?.id
            input.goals.forEach { goal ->
                SettingsRow(
                    goal.name,
                    subtitle = if (goal.id == primary) "Primary goal" else null,
                    trailing = { MoneyText(fmt.string(goal.target), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { actions.open(SettingsRoute.Goal(goal.id)) },
                )
            }
            SettingsRow("Add goal", icon = Icons.Filled.Add, onClick = { actions.open(SettingsRoute.GoalNew) })
        }

        Group("Limits and dates") {
            SettingsRow(
                "Hours limit, holidays, unavailable dates", icon = Icons.Filled.EventBusy,
                onClick = { actions.open(SettingsRoute.Limits) },
            )
        }

        Group("Preferences") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField("Currency", CURRENCIES.let { if (currency in it) it else it + currency }, currency, actions.setCurrency, { it })
                SettingsRow(
                    "Time zone", subtitle = data.settings.timeZoneId.replace('_', ' '), padded = false,
                    onClick = { actions.open(SettingsRoute.TimeZone) },
                )
                LivingBudgetField(data.settings, currency, actions.saveBudget)
            }
        }

        Group("Setup", footer = "Walk through setup again. Your data is kept.") {
            SettingsRow("Re-run setup", icon = Icons.Filled.Replay, onClick = { actions.open(SettingsRoute.Setup) })
        }

        Group("Data", footer = "Export a backup regularly. If you uninstall the app, your data goes with it.") {
            SettingsRow("Export backup", icon = Icons.Filled.FileUpload, onClick = actions.export)
            Text(
                data.lastExportedText,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            SettingsRow("Import backup", icon = Icons.Filled.FileDownload, onClick = actions.import)
            SettingsRow("Load demo data", icon = Icons.Filled.AutoFixHigh, onClick = actions.loadDemo)
            SettingsRow("Erase all data", icon = Icons.Filled.DeleteForever, destructive = true, onClick = actions.erase)
        }

        Group(
            "Notifications",
            footer = "A local reminder at 8pm the day before each payday in the next four weeks. Nothing leaves your device.",
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow("Payday-eve reminder", data.reminderOn, actions.setReminder)
                data.reminderMessage?.let {
                    Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Group("About") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Version", Modifier.weight(1f))
                    Text(data.version, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(AboutInfo.LICENSE_LINE)
                Text(AboutInfo.PRIVACY, style = MaterialTheme.typography.bodyMedium)
                Text("Acknowledgements", style = MaterialTheme.typography.titleSmall)
                AboutInfo.acknowledgements.forEach {
                    Text(
                        "${it.name} by ${it.author}, ${it.license}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun sourceSubtitle(source: IncomeSource): String {
    val kind = if (source.kind == IncomeKind.hourly) "Hourly" else "Fixed"
    val frequency = when (source.schedule.frequency) {
        PayFrequency.weekly -> "weekly"
        PayFrequency.fortnightly -> "fortnightly"
        PayFrequency.monthly -> "monthly"
    }
    return "$kind, paid $frequency" + if (source.isActive) "" else " (inactive)"
}

/** The weekly living budget. Saved on focus loss or with the Save button, never silently dropped. */
@Composable
private fun LivingBudgetField(settings: AppSettings, currency: String, save: (String) -> Unit) {
    val saved = MoneyParser.text(settings.livingBudget.cents)
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    val valid = MoneyParser.cents(text) != null
    val dirty = valid && MoneyParser.cents(text) != settings.livingBudget.cents
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MoneyField(
            "Weekly living budget", text, { text = it },
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused && dirty) save(text) },
            currencyCode = currency,
            error = if (valid) null else "Enter an amount like 150 or 150.50.",
        )
        if (dirty) TextButton(onClick = { save(text) }, Modifier.padding(top = 8.dp)) { Text("Save") }
    }
}

/** A titled card of rows with an optional footer. */
@Composable
private fun Group(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title, Modifier.padding(horizontal = 4.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) { Column { content() } }
        if (footer != null) {
            Text(
                footer, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One tappable row: title, optional subtitle and icon, optional trailing content. */
@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    padded: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (padded) 16.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = if (destructive) color else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Preview(showBackground = true, heightDp = 2400)
@Composable
private fun SettingsListPreview() {
    BeaverTheme {
        Surface {
            SettingsListContent(
                SettingsListData(
                    input = DemoData.forecastInput(),
                    settings = AppSettings(currency = "AUD", timeZoneId = "Australia/Sydney", livingBudget = DemoData.livingBudget),
                    tables = WithholdingTable.builtIn,
                    version = "0.1.0",
                    reminderOn = false,
                    reminderMessage = null,
                    lastExportedText = "Never exported",
                ),
                SettingsListActions(),
            )
        }
    }
}

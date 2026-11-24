package au.com.fuzn.beaver.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillStatus
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.common.spokenMoney
import au.com.fuzn.beaver.ui.editors.BillEditor
import au.com.fuzn.beaver.ui.editors.BillPreset
import au.com.fuzn.beaver.ui.editors.BillPresetPicker
import au.com.fuzn.beaver.ui.editors.EditorLabels
import au.com.fuzn.beaver.ui.editors.MoneyField
import au.com.fuzn.beaver.ui.editors.MoneyParser
import java.time.LocalDate
import java.util.Locale

// Sheet keys (saved across rotation): "pick", "new", "preset:<name>", "edit:<billID>", "paid:<billID>:<dueDate>".

/** The Bills segment: overdue, the next 8 weeks by payday, the Monthly view and paused bills. */
@Composable
fun BillsSection(
    input: ForecastInput,
    currency: String,
    repository: Repository,
    runner: MoneyRunner,
    modifier: Modifier = Modifier,
) {
    val model = remember(input, currency) { BillsModel(input, currency) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var pausedOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }

    BillsContent(
        model = model, monthly = monthly, onMonthlyChange = { monthly = it },
        pausedOpen = pausedOpen, onPausedOpenChange = { pausedOpen = it },
        onAdd = { sheet = "pick" },
        onMarkPaid = { sheet = "paid:${it.billID}:${it.dueDate}" },
        onUnmark = { row -> runner.perform { unmarkPaid(row.billID, row.dueDate) } },
        onEdit = { sheet = "edit:$it" },
        onSetActive = { bill, active -> runner.perform { setActive(bill, active) } },
        modifier = modifier,
    )

    val key = sheet
    if (key != null) {
        val close = { sheet = null }
        when {
            key == "pick" -> PresetSheet(
                onPreset = { sheet = "preset:${it.name}" }, onCustom = { sheet = "new" }, onDismiss = close,
            )
            key == "new" -> FullScreenEditor(close) {
                BillEditor(repository, onSaved = { close() }, onDismiss = close, currencyCode = currency)
            }
            key.startsWith("preset:") -> {
                val preset = BillPreset.common.firstOrNull { it.name == key.removePrefix("preset:") }
                FullScreenEditor(close) {
                    BillEditor(repository, onSaved = { close() }, onDismiss = close, preset = preset, currencyCode = currency)
                }
            }
            key.startsWith("edit:") -> {
                val bill = input.bills.firstOrNull { it.id == key.removePrefix("edit:").toIntOrNull() }
                if (bill == null) CloseEffect(close) else FullScreenEditor(close) {
                    BillEditor(
                        repository, onSaved = { close() }, onDismiss = close, bill = bill, currencyCode = currency,
                        onDeleted = close,
                    )
                }
            }
            key.startsWith("paid:") -> {
                val parts = key.split(":")
                val row = model.groups.flatMap { it.rows }.plus(model.overdue)
                    .firstOrNull { it.billID == parts.getOrNull(1)?.toIntOrNull() && it.dueDate.toString() == parts.getOrNull(2) }
                if (row == null || row.isPaid) CloseEffect(close) else MarkPaidSheet(
                    row = row, today = model.today, currency = currency, onDismiss = close,
                    onSave = { form -> form.save(runner.actions) },
                )
            }
            else -> CloseEffect(close)
        }
    }
}

@Composable
internal fun BillsContent(
    model: BillsModel,
    monthly: Boolean,
    onMonthlyChange: (Boolean) -> Unit,
    pausedOpen: Boolean,
    onPausedOpenChange: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onMarkPaid: (MoneyBillRow) -> Unit,
    onUnmark: (MoneyBillRow) -> Unit,
    onEdit: (Int) -> Unit,
    onSetActive: (Bill, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overdue = model.overdue
    val groups = model.groups
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "controls") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) { stateDescription = if (monthly) "On" else "Off" }
                        .clickable { onMonthlyChange(!monthly) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(checked = monthly, onCheckedChange = null)
                    Text("Monthly view", style = MaterialTheme.typography.bodyLarge)
                }
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Text(" Add bill")
                }
            }
            Text(
                if (monthly) "Every active bill with its usual day or schedule." else "Bills due in the next 8 weeks, grouped by payday.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            model.isEmpty -> item(key = "empty") { EmptyBills() }
            monthly -> monthlyRows(model, onEdit, onSetActive)
            else -> {
                if (overdue.isNotEmpty()) {
                    item(key = "overdue-header") { OverdueHeader(model.fmt.string(model.overdueTotal)) }
                    items(overdue, key = { "o-${it.id}" }) { row -> BillRow(row, onMarkPaid, onUnmark, onEdit) }
                }
                groups.forEach { group ->
                    item(key = "h-${group.id}") { GroupHeader(group.title, group.totalText) }
                    items(group.rows, key = { "g-${it.id}" }) { row -> BillRow(row, onMarkPaid, onUnmark, onEdit) }
                }
                if (groups.isEmpty() && overdue.isEmpty()) {
                    item(key = "nothing") {
                        Text("Nothing due in the next 8 weeks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        pausedSection(model, pausedOpen, onPausedOpenChange, onEdit, onSetActive)
    }
}

@Composable
private fun EmptyBills() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("No bills yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add rent, phone and other regular bills to see them against your paydays.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverdueHeader(totalText: String) {
    val color = Tone.Negative.color()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics(mergeDescendants = true) { heading(); contentDescription = "Overdue, total $totalText" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, Modifier.size(18.dp), tint = color)
        Text(
            " OVERDUE", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = color,
        )
        MoneyText(totalText, tone = Tone.Negative, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GroupHeader(title: String, totalText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics(mergeDescendants = true) { heading(); contentDescription = "$title, total $totalText" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(totalText, style = MaterialTheme.typography.labelLarge)
    }
}

private fun LazyListScope.monthlyRows(model: BillsModel, onEdit: (Int) -> Unit, onSetActive: (Bill, Boolean) -> Unit) {
    val rows = model.monthly
    val dated = rows.filter { it.dayOfMonth != null }
    val others = rows.filter { it.dayOfMonth == null }
    if (dated.isNotEmpty()) {
        item(key = "m-h1") { GroupHeader("Every month", "") }
        items(dated, key = { "m-${it.id}" }) { MonthlyRow(it, onEdit, onSetActive) }
    }
    if (others.isNotEmpty()) {
        item(key = "m-h2") { GroupHeader("Every few days", "") }
        items(others, key = { "m-${it.id}" }) { MonthlyRow(it, onEdit, onSetActive) }
    }
}

@Composable
private fun MonthlyRow(row: MonthlyBillRow, onEdit: (Int) -> Unit, onSetActive: (Bill, Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) {
                    customActions = listOf(CustomAccessibilityAction("Pause bill") { onSetActive(row.bill, false); true })
                }
                .clickable(onClickLabel = "Edit bill") { onEdit(row.bill.id) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.dayOfMonth != null) {
                MoneyText(
                    row.dayOfMonth.toString(), Modifier.heightIn(min = 0.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(row.bill.name, style = MaterialTheme.typography.bodyLarge)
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyText(row.amountText, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onSetActive(row.bill, false) }) { Text("Pause") }
        }
    }
}

private fun LazyListScope.pausedSection(
    model: BillsModel,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onEdit: (Int) -> Unit,
    onSetActive: (Bill, Boolean) -> Unit,
) {
    val paused = model.pausedBills
    if (paused.isEmpty()) return
    item(key = "paused-header") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(onClickLabel = if (open) "Collapse" else "Expand") { onOpenChange(!open) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PAUSED (${paused.size})", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
    }
    if (open) {
        items(paused, key = { "p-${it.id}" }) { bill ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics(mergeDescendants = true) {
                            customActions = listOf(CustomAccessibilityAction("Resume bill") { onSetActive(bill, true); true })
                        }
                        .clickable(onClickLabel = "Edit bill") { onEdit(bill.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(BillIcons.icon(bill.name, bill.category), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(bill.name, style = MaterialTheme.typography.bodyLarge)
                        MoneyText(model.fmt.string(bill.amount), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onSetActive(bill, true) }) { Text("Resume") }
                }
            }
        }
        item(key = "paused-footer") {
            Text(
                "Paused bills are left out of the forecast.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One occurrence. Tap an unpaid one to mark it paid; swipe a paid one to unmark it, or swipe an unpaid one to edit
 * its bill. Screen readers get the same actions from the custom actions menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillRow(
    row: MoneyBillRow,
    onMarkPaid: (MoneyBillRow) -> Unit,
    onUnmark: (MoneyBillRow) -> Unit,
    onEdit: (Int) -> Unit,
) {
    val current by rememberUpdatedState(row)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { if (current.isPaid) onUnmark(current); false }
                SwipeToDismissBoxValue.StartToEnd -> { if (!current.isPaid) onEdit(current.billID); false }
                else -> false
            }
        },
    )
    val overdue = row.status == BillStatus.overdue
    val subtitle = billSubtitle(row)
    val description = "${row.name}, ${spokenMoney(row.amountText)}, $subtitle"
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !row.isPaid,
        enableDismissFromEndToStart = row.isPaid,
        backgroundContent = {
            SwipeBackdrop(dismissState.dismissDirection, "Edit bill", SwipeIcons.edit, "Unmark paid", SwipeIcons.unmark)
        },
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        customActions = buildList {
                            if (row.isPaid) add(CustomAccessibilityAction("Unmark paid") { onUnmark(row); true })
                            else add(CustomAccessibilityAction("Mark paid") { onMarkPaid(row); true })
                            add(CustomAccessibilityAction("Edit bill") { onEdit(row.billID); true })
                        }
                    }
                    .clickable(enabled = !row.isPaid, onClickLabel = "Mark paid") { onMarkPaid(row) }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name, style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (row.isPaid) TextDecoration.LineThrough else null,
                    )
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) Tone.Negative.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyText(row.amountText, style = MaterialTheme.typography.bodyLarge)
                // The state icon is paired with the words in the subtitle.
                when {
                    row.isPaid -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tone.Positive.color())
                    overdue -> Icon(Icons.Filled.Error, contentDescription = null, tint = Tone.Negative.color())
                    else -> Icon(Icons.Outlined.Circle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onEdit(row.billID) }) {
                    Icon(SwipeIcons.edit, contentDescription = "Edit ${row.name}")
                }
            }
        }
    }
}

private fun billSubtitle(row: MoneyBillRow, locale: Locale = Locale.getDefault()): String {
    row.occurrence.paidDate?.let { return "Paid ${DateText.dayMonth(it, locale)} · due ${row.dateText}" }
    return if (row.status == BillStatus.overdue) "Overdue · due ${row.dateText}" else "Due ${row.dateText}"
}

// region Sheets

/** Pick a common bill or start a custom one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(onPreset: (BillPreset) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a bill", style = MaterialTheme.typography.titleLarge)
            Text("Start from a common bill, or make your own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BillPresetPicker(onSelect = onPreset)
            OutlinedButton(onClick = onCustom, Modifier.fillMaxWidth()) { Text("Custom bill") }
        }
    }
}

/** "Mark paid": the amount is pre-filled and editable, and the date defaults to today. */
@Composable
private fun MarkPaidSheet(
    row: MoneyBillRow,
    today: LocalDate,
    currency: String,
    onDismiss: () -> Unit,
    onSave: suspend (MarkPaidForm) -> Unit,
) {
    var amount by rememberSaveable(row.id) { mutableStateOf(MoneyParser.text(row.amount.cents)) }
    var paidDate by rememberSaveableDate(today)
    val form = MarkPaidForm(row, amount, paidDate)
    FormSheet(
        title = "Mark paid", canSave = form.isValid, onDismiss = onDismiss, onSave = { onSave(form) }, saveLabel = "Mark paid",
    ) {
        Text("${row.name} · due ${row.dateText}", style = MaterialTheme.typography.bodyLarge)
        MoneyField("Amount paid", amount, { amount = it }, currencyCode = currency)
        SheetDateField("Paid on", paidDate) { paidDate = it }
    }
}

// endregion

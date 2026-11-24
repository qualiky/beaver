package au.com.fuzn.beaver.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.spokenMoney
import au.com.fuzn.beaver.ui.editors.GoalEditor
import au.com.fuzn.beaver.ui.editors.MoneyField
import java.time.LocalDate

// Sheet keys (saved across rotation): "new", "edit:<goalID>", "deposit:<goalID>".

private const val RECENT_DEPOSITS = 5

/** The Goals segment: progress, deposits, and add, edit and make primary. */
@Composable
fun GoalsSection(
    input: ForecastInput,
    currency: String,
    repository: Repository,
    runner: MoneyRunner,
    modifier: Modifier = Modifier,
) {
    val model = remember(input, currency) { GoalsModel(input, currency) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }

    GoalsContent(
        model = model, today = input.today,
        onAdd = { sheet = "new" },
        onEdit = { sheet = "edit:$it" },
        onAddDeposit = { sheet = "deposit:$it" },
        onMakePrimary = { id -> runner.perform { makePrimary(id) } },
        onDeleteDeposit = { d -> runner.perform { deleteDeposit(d.id) } },
        modifier = modifier,
    )

    val key = sheet
    if (key != null) {
        val close = { sheet = null }
        when {
            key == "new" -> FullScreenEditor(close) {
                GoalEditor(repository, onSaved = { close() }, onDismiss = close, currencyCode = currency)
            }
            key.startsWith("edit:") -> {
                val goal = input.goals.firstOrNull { it.id == key.removePrefix("edit:").toIntOrNull() }
                if (goal == null) CloseEffect(close) else FullScreenEditor(close) {
                    GoalEditor(repository, onSaved = { close() }, onDismiss = close, goal = goal, currencyCode = currency, onDeleted = close)
                }
            }
            key.startsWith("deposit:") -> {
                val goal = input.goals.firstOrNull { it.id == key.removePrefix("deposit:").toIntOrNull() }
                if (goal == null) CloseEffect(close) else DepositSheet(goal.id, goal.name, input.today, currency, close) { it.save(runner.actions) }
            }
            else -> CloseEffect(close)
        }
    }
}

@Composable
internal fun GoalsContent(
    model: GoalsModel,
    today: LocalDate,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onAddDeposit: (Int) -> Unit,
    onMakePrimary: (Int) -> Unit,
    onDeleteDeposit: (Deposit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = model.rows
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rows.isEmpty()) "No goals yet" else "Your goals", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Text(" Add goal")
                }
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Add a savings goal and log deposits to track progress. The primary goal drives the Week tab.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        rows.forEach { row ->
            item(key = "goal-${row.id}") { GoalCard(row, model, onEdit, onAddDeposit, onMakePrimary, onDeleteDeposit) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalCard(
    row: GoalRow,
    model: GoalsModel,
    onEdit: (Int) -> Unit,
    onAddDeposit: (Int) -> Unit,
    onMakePrimary: (Int) -> Unit,
    onDeleteDeposit: (Deposit) -> Unit,
) {
    var showAll by rememberSaveable(row.id) { mutableStateOf(false) }
    val summary = "${row.savedText} of ${row.targetText}"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.goal.name, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (row.isPrimary) PrimaryBadge()
            }
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("${row.goal.name}, ${spokenMoney(row.savedText)} of ${spokenMoney(row.targetText)}, ${row.percentText}")
                        row.deadlineText?.let { append(", $it") }
                    }
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(progress = { row.progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MoneyText(summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(row.percentText, style = MaterialTheme.typography.bodyLarge)
                }
                row.deadlineText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onAddDeposit(row.id) }) { Text("Add deposit") }
                OutlinedButton(onClick = { onEdit(row.id) }) { Text("Edit") }
                if (!row.isPrimary) TextButton(onClick = { onMakePrimary(row.id) }) { Text("Make primary") }
            }
            if (row.deposits.isEmpty()) {
                Text("No deposits yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "DEPOSITS (${row.deposits.size})", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val shown = if (showAll) row.deposits else row.deposits.take(RECENT_DEPOSITS)
                shown.forEach { deposit -> key(deposit.id) { DepositRow(deposit, model, onDeleteDeposit) } }
                if (row.deposits.size > RECENT_DEPOSITS) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all ${row.deposits.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Primary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepositRow(deposit: Deposit, model: GoalsModel, onDelete: (Deposit) -> Unit) {
    val current by rememberUpdatedState(deposit)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete(current)
            false
        },
    )
    val amount = model.fmt.string(deposit.amount)
    val detail = listOf(DateText.weekdayDayMonth(deposit.date, model.fmt.locale), deposit.note).filter { it.isNotEmpty() }.joinToString(" · ")
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = { SwipeBackdrop(state.dismissDirection, null, null, "Delete", SwipeIcons.delete) },
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Deposit ${spokenMoney(amount)}, $detail"
                        customActions = listOf(CustomAccessibilityAction("Delete deposit") { onDelete(deposit); true })
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                MoneyText(amount, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DepositSheet(
    goalID: Int,
    goalName: String,
    today: LocalDate,
    currency: String,
    onDismiss: () -> Unit,
    onSave: suspend (DepositForm) -> Unit,
) {
    var amount by rememberSaveable(goalID) { mutableStateOf("") }
    var date by rememberSaveableDate(today)
    var note by rememberSaveable(goalID) { mutableStateOf("") }
    val form = DepositForm(goalID, amount, date, note)
    FormSheet(title = "Add deposit", canSave = form.isValid, onDismiss = onDismiss, onSave = { onSave(form) }) {
        Text(goalName, style = MaterialTheme.typography.bodyLarge)
        MoneyField("Amount", amount, { amount = it }, currencyCode = currency)
        SheetDateField("Date", date) { date = it }
        NoteField(note, { note = it })
    }
}

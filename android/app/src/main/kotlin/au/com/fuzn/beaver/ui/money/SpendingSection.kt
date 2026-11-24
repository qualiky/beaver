package au.com.fuzn.beaver.ui.money

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.common.spokenMoney
import au.com.fuzn.beaver.ui.common.title
import au.com.fuzn.beaver.ui.editors.MoneyField
import au.com.fuzn.beaver.ui.editors.MoneyParser
import au.com.fuzn.beaver.ui.week.AddExpenseSheet
import au.com.fuzn.beaver.ui.week.WeekActions
import java.time.LocalDate

// Sheet keys (saved across rotation): "add", "total:<weekStart>", "expense:<id>". The open week detail is "week".

/** The Spending segment: this week, its entries, and the last 12 weeks. A tap on a week opens that week's detail. */
@Composable
fun SpendingSection(
    input: ForecastInput,
    currency: String,
    repository: Repository,
    runner: MoneyRunner,
    modifier: Modifier = Modifier,
) {
    val model = remember(input, currency) { SpendingModel(input, currency) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var week by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = week?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    if (detail != null) {
        BackHandler { week = null }
        WeekDetail(
            model = model, weekStart = detail, onBack = { week = null },
            onSetTotal = { sheet = "total:$detail" }, onClearTotal = { runner.perform { clearWeekTotal(detail) } },
            onEdit = { sheet = "expense:${it.id}" }, onDelete = { runner.perform { deleteExpense(it.id) } },
            modifier = modifier,
        )
    } else {
        SpendingContent(
            model = model,
            onAdd = { sheet = "add" },
            onSetTotal = { sheet = "total:${model.currentWeekStart}" },
            onClearTotal = { runner.perform { clearWeekTotal(model.currentWeekStart) } },
            onEdit = { sheet = "expense:${it.id}" },
            onDelete = { runner.perform { deleteExpense(it.id) } },
            onOpenWeek = { week = it.toString() },
            modifier = modifier,
        )
    }

    val key = sheet
    if (key != null) {
        val close = { sheet = null }
        when {
            key == "add" -> AddExpenseSheet(
                currency = currency, onDismiss = close,
                onSave = { cents, category, note -> WeekActions.addExpense(repository, model.today, cents, category, note) },
            )
            key.startsWith("total:") -> {
                val start = runCatching { LocalDate.parse(key.removePrefix("total:")) }.getOrNull()
                if (start == null) CloseEffect(close) else WeekTotalSheet(model, start, currency, close) { it.save(runner.actions) }
            }
            key.startsWith("expense:") -> {
                val expense = input.expenses.firstOrNull { it.id == key.removePrefix("expense:").toIntOrNull() }
                if (expense == null) CloseEffect(close) else ExpenseEditSheet(expense, currency, close) { it.save(runner.actions) }
            }
            else -> CloseEffect(close)
        }
    }
}

@Composable
internal fun SpendingContent(
    model: SpendingModel,
    onAdd: () -> Unit,
    onSetTotal: () -> Unit,
    onClearTotal: () -> Unit,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
    onOpenWeek: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "this-week") { ThisWeekCard(model) }
        item(key = "actions") { WeekActionsRow(model.total(model.currentWeekStart), onAdd, onSetTotal, onClearTotal) }
        weekExpenses(model, model.currentWeekStart, "Expenses this week", onEdit, onDelete)
        item(key = "history-header") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LAST 12 WEEKS", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val running = model.runningVariance
                VarianceChip(Tone.of(running), "Running ${model.fmt.signed(running)}")
            }
        }
        items(model.history, key = { "w-${it.id}" }) { row -> HistoryRow(row, model, onOpenWeek) }
        item(key = "history-footer") {
            Text(
                "Running total adds up completed weeks that have spending recorded, from the oldest shown. " +
                    "Weeks with nothing logged are skipped.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThisWeekCard(model: SpendingModel) {
    val left = model.leftThisWeek
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This week", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (model.total(model.currentWeekStart) != null) TotalSetBadge()
                VarianceChip(Tone.of(model.varianceThisWeek), model.varianceText(model.varianceThisWeek))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure("Budget", model.fmt.string(model.budget))
                Figure("Spent", model.fmt.string(model.spentThisWeek))
                Figure("Left", model.fmt.string(left), tone = if (left.cents < 0) Tone.Negative else Tone.Neutral)
            }
            val budget = maxOf(model.budget.cents, 1L)
            LinearProgressIndicator(
                progress = { model.spentThisWeek.cents.coerceIn(0L, budget).toFloat() / budget },
                modifier = Modifier.fillMaxWidth(),
                color = if (left.cents < 0) Tone.Negative.color() else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, tone: Tone = Tone.Neutral) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label ${spokenMoney(value)}" }) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, tone = tone, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekActionsRow(
    total: Money?,
    onAdd: () -> Unit,
    onSetTotal: () -> Unit,
    onClearTotal: () -> Unit,
    showAdd: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showAdd) {
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" Add expense")
            }
        }
        OutlinedButton(onClick = onSetTotal) { Text(if (total == null) "Set week total" else "Change week total") }
        if (total != null) TextButton(onClick = onClearTotal) { Text("Clear total") }
    }
}

/** A week's total line and its quick entries (dimmed while a total is set, since the total replaces them). */
private fun LazyListScope.weekExpenses(
    model: SpendingModel,
    weekStart: LocalDate,
    header: String,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
) {
    val total = model.total(weekStart)
    val expenses = model.expenses(weekStart)
    item(key = "$header-header") {
        Text(
            header.uppercase(), Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (total != null) {
        item(key = "$header-total") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Week total", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TotalSetBadge()
                MoneyText(model.fmt.string(total), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (expenses.isEmpty()) {
        item(key = "$header-none") { Text("No quick entries", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(expenses, key = { "$header-e-${it.id}" }) { expense ->
        ExpenseRow(expense, model, dimmed = total != null, onEdit, onDelete)
    }
    if (total != null && expenses.isNotEmpty()) {
        item(key = "$header-note") {
            Text(
                "Entries are kept but ignored while a week total is set.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseRow(expense: Expense, model: SpendingModel, dimmed: Boolean, onEdit: (Expense) -> Unit, onDelete: (Expense) -> Unit) {
    val current by rememberUpdatedState(expense)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(current); false }
                SwipeToDismissBoxValue.StartToEnd -> { onEdit(current); false }
                else -> false
            }
        },
    )
    val amount = model.fmt.string(expense.amount)
    val detail = listOf(DateText.weekdayDayMonth(expense.date, model.fmt.locale), expense.note).filter { it.isNotEmpty() }.joinToString(" · ")
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackdrop(state.dismissDirection, "Edit", SwipeIcons.edit, "Delete", SwipeIcons.delete) },
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${expense.category.title}, ${spokenMoney(amount)}, $detail"
                        customActions = listOf(
                            CustomAccessibilityAction("Edit expense") { onEdit(expense); true },
                            CustomAccessibilityAction("Delete expense") { onDelete(expense); true },
                        )
                    }
                    .clickable(onClickLabel = "Edit expense") { onEdit(expense) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        expense.category.title, style = MaterialTheme.typography.bodyLarge,
                        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MoneyText(amount, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun HistoryRow(row: WeekSpendRow, model: SpendingModel, onOpen: (LocalDate) -> Unit) {
    val fmt = model.fmt
    val subtitle = if (row.hasData) "Spent ${fmt.string(row.spent)} of ${fmt.string(row.budget)}" else "Nothing recorded"
    val varianceText = if (row.hasData) model.varianceText(row.variance) else null
    val running = if (row.hasData) row.running else null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(model.weekTitle(row.weekStart))
                        if (row.hasTotal) append(", total set")
                        varianceText?.let { append(", $it") }
                        append(", $subtitle")
                        running?.let { append(", running ${spokenMoney(fmt.signed(it))}") }
                    }
                }
                .clickable(onClickLabel = "Open week") { onOpen(row.weekStart) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    model.weekTitle(row.weekStart), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (row.hasTotal) TotalSetBadge()
                Spacer(Modifier.weight(1f))
                if (varianceText != null) {
                    val tone = Tone.of(row.variance)
                    Text(varianceText, style = MaterialTheme.typography.labelLarge, color = tone.color())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subtitle, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (running != null) {
                    MoneyText("Running ${fmt.signed(running)}", tone = Tone.of(running), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** One week's detail: its figures, total controls and entries. */
@Composable
internal fun WeekDetail(
    model: SpendingModel,
    weekStart: LocalDate,
    onBack: () -> Unit,
    onSetTotal: () -> Unit,
    onClearTotal: () -> Unit,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spent = model.spent(weekStart)
    val variance = model.budget - spent
    val total = model.total(weekStart)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Spending") }
                Text(model.weekTitle(weekStart), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
        }
        item(key = "figures") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Figure("Budget", model.fmt.string(model.budget))
                        Figure("Spent", model.fmt.string(spent))
                        VarianceChip(Tone.of(variance), model.varianceText(variance))
                    }
                }
            }
        }
        item(key = "actions") {
            WeekActionsRow(total, onAdd = {}, onSetTotal, onClearTotal, showAdd = false)
        }
        weekExpenses(model, weekStart, "Expenses", onEdit, onDelete)
    }
}

// region Sheets

@Composable
private fun WeekTotalSheet(
    model: SpendingModel,
    weekStart: LocalDate,
    currency: String,
    onDismiss: () -> Unit,
    onSave: suspend (WeekTotalForm) -> Unit,
) {
    var amount by rememberSaveable(weekStart) { mutableStateOf(WeekTotalForm.of(weekStart, model.total(weekStart)).amountText) }
    val form = WeekTotalForm(weekStart, amount)
    FormSheet(title = "Week total", canSave = form.isValid, onDismiss = onDismiss, onSave = { onSave(form) }) {
        Text(
            "${model.weekTitle(weekStart)}. A total replaces this week's quick entries.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyField("Spent this week", amount, { amount = it }, currencyCode = currency)
    }
}

@Composable
private fun ExpenseEditSheet(expense: Expense, currency: String, onDismiss: () -> Unit, onSave: suspend (ExpenseForm) -> Unit) {
    val start = remember(expense.id) { ExpenseForm(expense) }
    var amount by rememberSaveable(expense.id) { mutableStateOf(start.amountText) }
    var category by rememberSaveable(expense.id) { mutableStateOf(start.category) }
    var date by rememberSaveableDate(start.date)
    var note by rememberSaveable(expense.id) { mutableStateOf(start.note) }
    val form = ExpenseForm(expense.id, amount, category, date, note)
    FormSheet(title = "Edit expense", canSave = form.isValid, onDismiss = onDismiss, onSave = { onSave(form) }) {
        MoneyField("Amount", amount, { amount = it }, currencyCode = currency)
        SpendCategoryChips(category) { category = it }
        SheetDateField("Date", date) { date = it }
        NoteField(note, { note = it })
    }
}

// endregion

package au.com.fuzn.beaver.ui.onboarding

import au.com.fuzn.beaver.ui.common.TimeZonePickerField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.editors.BillPresetPicker
import au.com.fuzn.beaver.ui.editors.DropdownField
import au.com.fuzn.beaver.ui.editors.EditorSection
import au.com.fuzn.beaver.ui.editors.MoneyField
import au.com.fuzn.beaver.ui.editors.MoneyParser

// The seven steps. Copy and behaviour follow the iOS onboarding; each step is stateless apart from its dialogs and
// reads and writes through [OnboardingModel] via the callbacks.

/** A big title and one line of explanation at the top of a step. */
@Composable
internal fun StepHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A large tappable choice with an icon, a title and a hint. */
@Composable
internal fun ChoiceButton(title: String, hint: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $hint" },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A tappable list row (edit) with optional trailing actions. The text part is one merged, labelled target. */
@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    editLabel: String,
    onClick: () -> Unit,
    trailingText: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(title, subtitle, trailingText).joinToString(", ") + ". $editLabel"
                    }
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (trailingText != null) MoneyText(trailingText, style = MaterialTheme.typography.bodyLarge)
            }
            actions()
        }
    }
}

internal object Describe {
    fun cadence(c: Cadence): String = when (c) {
        Cadence.Weekly -> "weekly"
        Cadence.Fortnightly -> "fortnightly"
        is Cadence.EveryNDays -> "every ${c.n} days"
        is Cadence.Monthly -> "monthly"
        Cadence.Quarterly -> "quarterly"
        Cadence.Yearly -> "yearly"
        Cadence.OneOff -> "one-off"
    }

    fun schedule(s: PaySchedule): String = when (s) {
        is PaySchedule.Weekly -> "paid weekly"
        is PaySchedule.Fortnightly -> "paid fortnightly"
        is PaySchedule.Monthly -> "paid monthly"
    }

    fun source(s: IncomeSource, fmt: FigureFormatter): String = when (s.kind) {
        IncomeKind.hourly -> "Hourly, ${schedule(s.schedule)}"
        IncomeKind.fixed -> (s.fixedAmount?.let { fmt.string(it) + ", " } ?: "") + schedule(s.schedule)
    }

    fun capitalised(text: String): String = text.replaceFirstChar { it.uppercase() }
}

// region 1 Welcome

@Composable
internal fun WelcomeStep(
    model: OnboardingModel,
    onCurrency: (String) -> Unit,
    onTimeZone: (String) -> Unit,
    onRestore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Eco, contentDescription = null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "Welcome to Beaver",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Beaver turns your shifts or salary into expected pay, tracks your bills and spending, and tells you " +
                    "each week if you're ahead or behind on your goal.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EditorSection("Your privacy") {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Everything stays on this phone. No account, no bank login, nothing is sent anywhere.")
            }
        }

        EditorSection("Your money", footer = "Taken from your phone. Change them if they're not right.") {
            DropdownField(
                label = "Currency",
                options = model.currencyChoices,
                selected = model.currency,
                onSelect = onCurrency,
                optionLabel = { code -> "$code ${MoneyParser.symbol(code)}" },
            )
            TimeZonePickerField(selected = model.timeZoneId, onSelect = onTimeZone)
        }

        EditorSection(null, footer = "Already use Beaver? Pick your backup file (JSON) and skip setup.") {
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, contentDescription = null, Modifier.size(18.dp))
                Text("Restore from backup", Modifier.padding(start = 8.dp))
            }
        }
    }
}

// endregion

// region 2 Income

@Composable
internal fun IncomeStep(
    model: OnboardingModel,
    present: (OnboardingSheet) -> Unit,
    onDelete: (IncomeSource) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    val fmt = model.formatter
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader("How do you get paid?", "Add what you earn. You can add more later.")

        if (model.sources.isNotEmpty()) {
            EditorSection("Your income") {
                model.sources.forEach { source ->
                    ListRow(
                        title = source.name,
                        subtitle = Describe.source(source, fmt),
                        editLabel = "Edit",
                        onClick = { present(OnboardingSheet.EditIncome(source.id)) },
                    ) {
                        if (source.kind == IncomeKind.hourly) {
                            IconButton(onClick = { present(OnboardingSheet.UsualWeek(source.id)) }) {
                                Icon(Icons.Filled.Schedule, contentDescription = "Set usual week for ${source.name}")
                            }
                        }
                        IconButton(onClick = { deleting = source.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${source.name}")
                        }
                    }
                }
            }
        }

        EditorSection(if (model.sources.isEmpty()) "Pick one" else "Add another") {
            ChoiceButton("Shifts / hourly", "Casual or shift work, paid by the hour", Icons.Filled.Schedule) {
                present(OnboardingSheet.NewIncome(IncomeKind.hourly))
            }
            ChoiceButton("Salary / fixed amount", "The same amount each pay", Icons.Filled.Payments) {
                present(OnboardingSheet.NewIncome(IncomeKind.fixed))
            }
        }
    }

    val target = model.sources.firstOrNull { it.id == deleting }
    if (target != null) {
        ConfirmDeleteDialog(
            title = "Delete ${target.name.ifBlank { "this income" }}?",
            message = "This also deletes its shifts and default week. It can't be undone.",
            onConfirm = { deleting = null; onDelete(target) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// endregion

// region 3 Bills

@Composable
internal fun BillsStep(
    model: OnboardingModel,
    present: (OnboardingSheet) -> Unit,
    onDelete: (Bill) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    val fmt = model.formatter
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader("What bills do you pay?", "Tap a common one, then fill in the amount.")

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BillPresetPicker(onSelect = { present(OnboardingSheet.NewBill(it.name)) })
            OutlinedButton(onClick = { present(OnboardingSheet.NewBill(null)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Text("Add custom", Modifier.padding(start = 8.dp))
            }
        }

        if (model.bills.isNotEmpty()) {
            val weekly = fmt.string(model.weeklyBills)
            EditorSection("Your bills") {
                model.bills.forEach { bill ->
                    ListRow(
                        title = bill.name,
                        subtitle = Describe.capitalised(Describe.cadence(bill.cadence)),
                        editLabel = "Edit",
                        trailingText = fmt.string(bill.amount),
                        onClick = { present(OnboardingSheet.EditBill(bill.id)) },
                    ) {
                        IconButton(onClick = { deleting = bill.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${bill.name}")
                        }
                    }
                }
                Text(
                    "≈ $weekly per week",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { contentDescription = "About $weekly per week" },
                )
            }
        }
    }

    val target = model.bills.firstOrNull { it.id == deleting }
    if (target != null) {
        ConfirmDeleteDialog(
            title = "Delete ${target.name.ifBlank { "this bill" }}?",
            message = "It can't be undone.",
            onConfirm = { deleting = null; onDelete(target) },
            onDismiss = { deleting = null },
        )
    }
}

// endregion

// region 4 Living money

@Composable
internal fun LivingStep(model: OnboardingModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader("Living money", "How much do you spend each week outside your bills?")
        EditorSection(null, footer = "Food, transport, coffee and fun: everything that isn't a bill.") {
            MoneyField(
                label = "Per week",
                value = model.livingText,
                onValueChange = { model.livingText = it },
                currencyCode = model.currency,
                suffix = "/ week",
                error = model.livingIssue,
            )
        }
        if (model.hasIncome) {
            val fmt = model.formatter
            Text(
                "Your income ≈ ${fmt.string(model.weeklyIncome)}/week, bills ≈ ${fmt.string(model.weeklyBills)}/week",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// endregion

// region 5 Goal

@Composable
internal fun GoalStep(model: OnboardingModel, present: (OnboardingSheet) -> Unit) {
    val fmt = model.formatter
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader("Saving for something?", "Optional. Beaver tells you each week if you're on track.")
        if (model.goals.isNotEmpty()) {
            EditorSection("Your goals") {
                model.goals.forEach { goal: Goal ->
                    ListRow(
                        title = goal.name,
                        subtitle = goal.deadline?.let { "By ${DateText.full(it)}" },
                        editLabel = "Edit",
                        trailingText = fmt.string(goal.target),
                        onClick = { present(OnboardingSheet.EditGoal(goal.id)) },
                    )
                }
            }
        }
        EditorSection(null, footer = "Your first goal becomes your main one.") {
            ChoiceButton(
                title = if (model.goals.isEmpty()) "Add a goal" else "Add another goal",
                hint = "A name, a target and a date",
                icon = Icons.Filled.Flag,
            ) { present(OnboardingSheet.EditGoal(null)) }
        }
    }
}

// endregion

// region 6 Limits

@Composable
internal fun LimitsStep(model: OnboardingModel, present: (OnboardingSheet) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader("Limits and dates", "Optional. Beaver warns you, it never stops you.")
        Text("Add a fortnightly hours limit (for example a student visa), public holidays, and dates you can't work.")
        model.limitsSummary?.let { summary ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tone.Positive.color())
                Text(summary, color = Tone.Positive.color())
            }
        }
        OutlinedButton(onClick = { present(OnboardingSheet.Limits) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.EventBusy, contentDescription = null, Modifier.size(18.dp))
            Text(
                if (model.limitsSummary == null) "Set limits and dates" else "Edit limits and dates",
                Modifier.padding(start = 8.dp),
            )
        }
    }
}

// endregion

// region 7 Summary

@Composable
internal fun SummaryStep(model: OnboardingModel) {
    val s = model.summary
    val fmt = model.formatter
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EditorSection("Your first forecast") {
            val negative = !s.isEmpty && s.weeklySurplus.cents < 0 && !s.hasGoal
            Text(
                if (s.isEmpty) "All set" else s.headline,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (negative) Tone.Negative.color() else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (!s.isEmpty && s.detail != null) {
                Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.isEmpty) {
                Text(
                    "You skipped setup. Add your income, bills and goal any time from the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!s.isEmpty) {
            EditorSection("Key numbers, per week") {
                KeyNumber("Income", "≈ " + fmt.string(s.weeklyIncome))
                KeyNumber("Bills", "≈ " + fmt.string(s.weeklyBills))
                KeyNumber("Living money", fmt.string(s.weeklyLiving))
                val need = s.requiredPerPayday
                if (s.hasGoal && need != null) KeyNumber("Needed per payday", fmt.string(need))
            }
        }
    }
}

@Composable
private fun ColumnScope.KeyNumber(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        MoneyText(value, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider()
}

// endregion

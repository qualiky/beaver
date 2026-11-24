package au.com.fuzn.beaver.ui.week

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.BannerView
import au.com.fuzn.beaver.ui.common.BillLine
import au.com.fuzn.beaver.ui.common.CardView
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.Palette
import au.com.fuzn.beaver.ui.common.SectionLabel
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.common.billIcon
import au.com.fuzn.beaver.ui.common.spokenMoney
import au.com.fuzn.beaver.ui.common.tabular
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The Week tab. Reads the store, loads the hours limits, and routes actions to the repository. */
@Composable
fun WeekScreen(env: AppEnv, onBackUp: () -> Unit, modifier: Modifier = Modifier) {
    val forecast by env.store.forecast.collectAsState()
    val input by env.store.input.collectAsState()
    val settings by env.store.settings.collectAsState()
    var limits by remember { mutableStateOf<List<FortnightLimit>>(emptyList()) }
    var showAddExpense by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Hours limits are not part of the forecast input; reload them when shifts change (and on first show).
    LaunchedEffect(input.shifts.size) {
        limits = runCatching { env.repository.hoursLimits() }.getOrDefault(emptyList())
    }

    val model = remember(forecast, input, settings, limits) { WeekModel(forecast, input, settings, limits) }

    fun run(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { error = it.message ?: "Something went wrong." } }
    }

    WeekContent(
        model = model,
        modifier = modifier,
        onBackUp = onBackUp,
        onAddExpense = { showAddExpense = true },
        onTogglePaid = { bill -> run { WeekActions.togglePaid(env.repository, bill, model.today) } },
    )

    if (showAddExpense) {
        AddExpenseSheet(
            currency = settings.currency,
            onDismiss = { showAddExpense = false },
            onSave = { cents, category, note ->
                WeekActions.addExpense(env.repository, model.today, cents, category, note)
            },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            title = { Text("Couldn't save") },
            text = { Text(it) },
        )
    }
}

/** Stateless content of the Week tab, so previews and tests can draw it from a model. */
@Composable
fun WeekContent(
    model: WeekModel,
    modifier: Modifier = Modifier,
    onBackUp: () -> Unit = {},
    onAddExpense: () -> Unit = {},
    onTogglePaid: (BillLine) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (model.isEmpty) {
            WeekEmptyState()
        } else {
            Banners(model, onBackUp)
            HeadlineView(model.headline)
            model.hoursBar?.let { HoursBarCard(it, model) }
            model.goalCard?.let { GoalCardView(it) }
            ThisWeekCard(model, onAddExpense, onTogglePaid)
            model.split?.let { SplitCardView(it) }
        }
    }
}

// region Banners

@Composable
private fun Banners(model: WeekModel, onBackUp: () -> Unit) {
    model.dangerBanners.forEach { banner ->
        BannerView(
            tone = Tone.Negative, icon = Icons.Filled.Warning, title = banner.title,
            message = "Bills put you ${banner.shortfallText} that week.",
        ) {
            banner.bills.forEach { bill ->
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${bill.name} · ${bill.dateText}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    MoneyText(bill.amountText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val warning = model.hoursWarning
    val bar = model.hoursBar
    if (warning != null && bar != null) {
        val over = bar.status == HoursBar.Status.Over
        BannerView(
            tone = if (over) Tone.Negative else Tone.Warning, icon = Icons.Filled.Schedule,
            title = if (over) "Over your hours limit" else "Near your hours limit", message = warning,
        )
    }
    model.backupMessage?.let { message ->
        BannerView(tone = Tone.Warning, icon = Icons.Filled.Save, title = "Back up your data", message = message) {
            OutlinedButton(onClick = onBackUp) { Text("Back up now") }
        }
    }
}

// endregion

// region Headline, hours, goal

@Composable
private fun HeadlineView(headline: Headline) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {
            heading()
            contentDescription = spokenMoney(headline.text) + (headline.detail?.let { ". $it" } ?: "")
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            headline.tone.symbol?.let { Icon(it, contentDescription = null, tint = headline.tone.color(), modifier = Modifier.size(32.dp)) }
            Text(
                headline.text,
                style = MaterialTheme.typography.displaySmall.tabular(),
                fontWeight = FontWeight.Bold,
                color = headline.tone.color(),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        headline.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HoursBarCard(bar: HoursBar, model: WeekModel) {
    val tone = when (bar.status) {
        HoursBar.Status.Ok -> Tone.Neutral
        HoursBar.Status.Near -> Tone.Warning
        HoursBar.Status.Over -> Tone.Negative
    }
    val statusWord = when (bar.status) {
        HoursBar.Status.Ok -> "within the limit"
        HoursBar.Status.Near -> "close to the limit"
        HoursBar.Status.Over -> "over the limit"
    }
    CardView("Fortnight hours", Icons.Filled.Schedule) {
        Column(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Fortnight hours, ${bar.summary} used, $statusWord"
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LinearProgressIndicator(
                progress = { bar.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.primary else tone.color(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tone.symbol?.let { Icon(it, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp)) }
                    MoneyText(bar.summary, tone = tone, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    DateText.range(bar.window, model.fmt.locale),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoalCardView(goal: GoalCard) {
    CardView(goal.name, Icons.Filled.Flag) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { goal.progress },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Goal progress"
                    stateDescription = "${(goal.progress * 100).toInt()} percent"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyText("${goal.savedText} of ${goal.targetText}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(goal.deadlineText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyText(goal.requiredText, style = MaterialTheme.typography.titleMedium)
            Text(goal.paydaysText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// endregion

// region This week

@Composable
private fun ThisWeekCard(model: WeekModel, onAddExpense: () -> Unit, onTogglePaid: (BillLine) -> Unit) {
    CardView("This week", Icons.Filled.CalendarMonth) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PaySection(model)
            HorizontalDivider()
            BillsSection(model, onTogglePaid)
            HorizontalDivider()
            LivingSection(model, onAddExpense)
        }
    }
}

@Composable
private fun PaySection(model: WeekModel) {
    val lines = model.payLines
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Pay")
        if (lines.isEmpty()) {
            Text(
                model.nextPaydayText?.let { "No pay this week. Next payday $it." } ?: "No upcoming paydays.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lines.forEach { line ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${line.sourceName} · ${line.paydayText}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    MoneyText(if (line.isActual) line.actualText.orEmpty() else line.expectedText, fontWeight = FontWeight.SemiBold)
                }
                if (line.isActual && line.differenceText != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoneyText("Received. Expected ${line.expectedText}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        MoneyText("${line.differenceText} difference", tone = line.differenceTone, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        if (line.fromTemplate) "Estimate from your default week" else "Estimate from rostered shifts",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BillsSection(model: WeekModel, onTogglePaid: (BillLine) -> Unit) {
    val bills = model.bills
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(model.nextPaydayText?.let { "Bills before $it" } ?: "Bills this week", Modifier.weight(1f))
            if (bills.isNotEmpty()) MoneyText(model.fmt.string(model.billsTotal), fontWeight = FontWeight.SemiBold)
        }
        if (bills.isEmpty()) {
            Text("No bills due before your next payday.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bills.forEach { bill -> BillRow(bill, onTogglePaid) }
        if (bills.any { !it.isPaid }) {
            MoneyText("${model.fmt.string(model.billsUnpaidTotal)} still to pay", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BillRow(bill: BillLine, onTogglePaid: (BillLine) -> Unit) {
    val overdue = bill.isOverdue && !bill.isPaid
    val spoken = buildString {
        append(bill.name).append(", ").append(spokenMoney(bill.amountText)).append(", ")
        append(if (overdue) "overdue, due ${bill.dateText}" else "due ${bill.dateText}")
        append(if (bill.isPaid) ", paid" else ", not paid")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Checkbox,
                onClickLabel = if (bill.isPaid) "Mark ${bill.name} unpaid" else "Mark ${bill.name} paid",
            ) { onTogglePaid(bill) }
            .semantics(mergeDescendants = true) { contentDescription = spoken; stateDescription = if (bill.isPaid) "Paid" else "Not paid" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (bill.isPaid) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (bill.isPaid) Tone.Positive.color() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(bill.name, style = MaterialTheme.typography.bodyMedium, textDecoration = if (bill.isPaid) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (overdue) Icon(Icons.Filled.Error, contentDescription = null, tint = Tone.Negative.color(), modifier = Modifier.size(14.dp))
                Text(
                    if (overdue) "Overdue · ${bill.dateText}" else bill.dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) Tone.Negative.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MoneyText(bill.amountText)
    }
}

@Composable
private fun LivingSection(model: WeekModel, onAddExpense: () -> Unit) {
    val living = model.living
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Living budget left", Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (living.left.cents < 0) Icon(Icons.Filled.Warning, contentDescription = null, tint = living.tone.color(), modifier = Modifier.size(18.dp))
                MoneyText(living.leftText, tone = living.tone, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        LinearProgressIndicator(
            progress = { living.fractionSpent },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Living budget used"
                stateDescription = "${living.spentText} of ${living.budgetText}"
            },
            color = if (living.left.cents < 0) Tone.Negative.color() else MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyText("${living.spentText} spent of ${living.budgetText}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAddExpense, modifier = Modifier.semantics { contentDescription = "Add expense" }) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Expense")
            }
        }
    }
}

// endregion

// region Split and empty state

@Composable
private fun SplitCardView(split: SplitCard) {
    CardView("Suggested split", Icons.Filled.PieChart) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MoneyText("Payday ${split.paydayText} · expected ${split.expectedPayText}", fontWeight = FontWeight.Medium)
                Text(split.coversText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            split.lines.forEach { line ->
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        MoneyText(line.fundedText, fontWeight = FontWeight.SemiBold)
                    }
                    line.fromBufferText?.let { short ->
                        val warn = Tone.Warning.color()
                        Surface(shape = RoundedCornerShape(50), color = warn.copy(alpha = 0.15f)) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = warn, modifier = Modifier.size(14.dp))
                                MoneyText("$short from buffer (needs ${line.neededText})", tone = Tone.Warning, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            split.bufferText?.let { buffer ->
                HorizontalDivider()
                Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Left over for buffer", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    MoneyText(buffer, fontWeight = FontWeight.SemiBold)
                }
            }
            split.totalFromBufferText?.let { total ->
                MoneyText(
                    "Pay doesn't cover everything. $total would come from your buffer.",
                    tone = Tone.Warning, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WeekEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Inbox, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nothing to forecast yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Add an income source and your regular bills in Settings, and Beaver will show how far ahead or behind you are.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

// endregion

// region Previews (demo data only)

internal fun demoWeekModel(today: LocalDate = DemoData.referenceToday, shifts: List<Shift> = emptyList(), limits: List<FortnightLimit> = emptyList()): WeekModel {
    val input = DemoData.forecastInput(today).copy(shifts = shifts)
    return WeekModel(Forecast.compute(input), input, AppSettings(currency = "AUD"), limits)
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun WeekPreview() {
    BeaverTheme { WeekContent(demoWeekModel()) }
}

@Preview(showBackground = true, fontScale = 1.8f, heightDp = 1800)
@Composable
private fun WeekLargeFontPreview() {
    BeaverTheme { WeekContent(demoWeekModel(today = LocalDate.of(2026, 11, 26))) }
}

@Preview(showBackground = true)
@Composable
private fun WeekEmptyPreview() {
    BeaverTheme {
        val input = DemoData.forecastInput().copy(sources = emptyList(), bills = emptyList())
        WeekContent(WeekModel(Forecast.compute(input), input, AppSettings()))
    }
}

// endregion

package au.com.fuzn.beaver.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.MoneyText
import au.com.fuzn.beaver.ui.common.SectionLabel
import au.com.fuzn.beaver.ui.common.StepperRow
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.common.billIcon
import au.com.fuzn.beaver.ui.common.tabular
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import kotlinx.coroutines.launch

/** The Plan tab. Reads the forecast from the store; the what-ifs write through it and the repository. */
@Composable
fun PlanScreen(env: AppEnv, modifier: Modifier = Modifier) {
    val forecast by env.store.forecast.collectAsState()
    val settings by env.store.settings.collectAsState()
    val extra by env.store.extraWeeklyIncome.collectAsState()
    var whatIf by remember { mutableStateOf(WhatIfState.starting(settings.livingBudget)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val model = remember(forecast, settings) { PlanModel(forecast, settings) }

    fun setLiving(cents: Long) {
        val next = whatIf.withLiving(cents)
        whatIf = next // working copy first, so rapid taps never read a stale value
        scope.launch {
            runCatching { env.repository.setLivingBudget(Money(next.livingCents)) }
                .onFailure { error = it.message ?: "Something went wrong." }
        }
    }

    PlanContent(
        model = model,
        whatIf = whatIf,
        extraIncome = extra,
        modifier = modifier,
        onLivingChange = ::setLiving,
        onExtraChange = { env.store.setExtraWeeklyIncome(Money(it.coerceIn(0, WhatIfState.MAX_EXTRA_CENTS))) },
        onReset = {
            env.store.setExtraWeeklyIncome(Money.ZERO)
            if (whatIf.baselineLivingCents != whatIf.livingCents) setLiving(whatIf.baselineLivingCents)
        },
    )
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            title = { Text("Couldn't save") },
            text = { Text(it) },
        )
    }
}

/** Stateless content of the Plan tab, so previews can draw it from a model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanContent(
    model: PlanModel,
    whatIf: WhatIfState,
    extraIncome: Money,
    modifier: Modifier = Modifier,
    onLivingChange: (Long) -> Unit = {},
    onExtraChange: (Long) -> Unit = {},
    onReset: () -> Unit = {},
) {
    var selected by remember { mutableStateOf<PlanRow?>(null) }
    val rows = remember(model) { model.rows }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("summary") { SummarySection(model.summary) }
        item("whatif") {
            WhatIfSection(model, whatIf, extraIncome, onLivingChange, onExtraChange, onReset)
        }
        item("header") {
            SectionLabel("Week by week", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
        }
        items(rows, key = { it.id }) { row ->
            PlanRowView(row, onClick = { selected = row })
            HorizontalDivider()
        }
    }

    selected?.let { row ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            PlanWeekDetailContent(model.detail(row), Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun SummarySection(s: PlanSummary) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Summary")
        Column(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${s.gapLabel}, ${au.com.fuzn.beaver.ui.common.spokenMoney(s.gapText)}"
            },
        ) {
            Text(s.gapLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.gapTone.symbol?.let { Icon(it, contentDescription = null, tint = s.gapTone.color(), modifier = Modifier.size(28.dp)) }
                Text(
                    s.gapText, style = MaterialTheme.typography.displaySmall.tabular(), fontWeight = FontWeight.Bold,
                    color = s.gapTone.color(), modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        LabeledRow(s.projectedLabel) { MoneyText(s.projectedText) }
        LabeledRow("Pay vs expected") { MoneyText(s.payVarianceText, tone = s.payVarianceTone) }
        LabeledRow("Spending to date") {
            MoneyText("${s.spendingVarianceText} ${s.spendingVarianceNote}", tone = s.spendingVarianceTone)
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value()
    }
}

@Composable
private fun WhatIfSection(
    model: PlanModel,
    whatIf: WhatIfState,
    extraIncome: Money,
    onLivingChange: (Long) -> Unit,
    onExtraChange: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val fmt = model.fmt
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("What if")
        StepperRow(
            label = "Living budget per week",
            valueText = "${fmt.string(Money(whatIf.livingCents))} / week",
            onDecrement = { onLivingChange(whatIf.livingCents - WhatIfState.LIVING_STEP_CENTS) },
            onIncrement = { onLivingChange(whatIf.livingCents + WhatIfState.LIVING_STEP_CENTS) },
            canDecrement = whatIf.livingCents > 0,
            canIncrement = whatIf.livingCents < WhatIfState.MAX_LIVING_CENTS,
        )
        StepperRow(
            label = "Extra weekly income",
            valueText = "${fmt.string(extraIncome)} / week",
            onDecrement = { onExtraChange(extraIncome.cents - WhatIfState.EXTRA_STEP_CENTS) },
            onIncrement = { onExtraChange(extraIncome.cents + WhatIfState.EXTRA_STEP_CENTS) },
            canDecrement = extraIncome.cents > 0,
            canIncrement = extraIncome.cents < WhatIfState.MAX_EXTRA_CENTS,
        )
        OutlinedButton(onClick = onReset, enabled = whatIf.isActive(extraIncome)) { Text("Reset what-ifs") }
        Text(
            "The living budget is saved to your settings. Extra income is only for trying things out and is cleared when you close the app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A two-line row: week and paydays, then income, bills and living; surplus and running balance on the right. */
@Composable
private fun PlanRowView(row: PlanRow, onClick: () -> Unit) {
    val danger = if (row.isDanger) Tone.Negative.color().copy(alpha = 0.12f) else Color.Transparent
    Surface(color = danger) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = "Show pay and bills for this week", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clearAndSetSemantics { contentDescription = row.accessibilityLabel },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.isDanger) Icon(Icons.Filled.Warning, contentDescription = null, tint = Tone.Negative.color(), modifier = Modifier.size(16.dp))
                    Text(
                        "${row.title} · ${row.paydaysText}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                }
                MoneyText(
                    "In ${row.incomeText} · Bills ${row.billsText} · Living ${row.livingText}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                if (row.isCurrent || row.isDeadlineWeek) {
                    Text(
                        if (row.isCurrent) "This week" else "Deadline week",
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MoneyText(
                    row.surplusText,
                    tone = if (row.isDanger) Tone.Negative else if (row.surplus.cents > 0) Tone.Positive else Tone.Neutral,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                MoneyText(
                    row.balanceText, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
    }
}

/** One forecast week: its pay events, bills and totals. */
@Composable
fun PlanWeekDetailContent(detail: PlanWeekDetail, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(detail.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (detail.isDanger) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Tone.Negative.color())
                Text("Spending is more than income this week.", color = Tone.Negative.color(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionLabel("Pay")
        if (detail.pays.isEmpty()) Text("No payday this week", color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail.pays.forEach { pay ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pay.sourceName, Modifier.weight(1f))
                    MoneyText(if (pay.isActual) pay.actualText.orEmpty() else pay.expectedText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${pay.paydayText} · ${if (pay.isActual) "actual" else "est."}", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pay.differenceText?.let { MoneyText("$it vs ${pay.expectedText}", tone = pay.differenceTone, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        detail.extraIncomeText?.let { extra ->
            LabeledRow("What-if extra income") { MoneyText(extra) }
        }
        SectionLabel("Bills")
        if (detail.bills.isEmpty()) Text("No bills due", color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail.bills.forEach { bill ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(billIcon(bill.category), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(bill.name, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (bill.isPaid) "Paid · ${bill.dateText}" else bill.dateText,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyText(bill.amountText)
            }
        }
        SectionLabel("Totals")
        LabeledRow("Income") { MoneyText(detail.incomeText) }
        LabeledRow("Bills") { MoneyText(detail.billsText) }
        LabeledRow("Living") { MoneyText(detail.livingText) }
        LabeledRow("Surplus") { MoneyText(detail.surplusText, tone = detail.surplusTone, fontWeight = FontWeight.Bold) }
        if (detail.isDeadlineWeek) {
            Text(
                "Deadline week: only pay and bills up to the deadline count.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// region Previews (demo data only)

private fun demoPlanModel() = PlanModel(Forecast.compute(DemoData.forecastInput()), AppSettings(currency = "AUD"))

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun PlanPreview() {
    BeaverTheme {
        PlanContent(demoPlanModel(), WhatIfState.starting(DemoData.livingBudget), Money.ZERO)
    }
}

@Preview(showBackground = true, fontScale = 1.8f, heightDp = 1600)
@Composable
private fun PlanLargeFontPreview() {
    BeaverTheme {
        PlanContent(demoPlanModel(), WhatIfState.starting(DemoData.livingBudget), Money.dollars(50))
    }
}

@Preview(showBackground = true)
@Composable
private fun PlanDetailPreview() {
    BeaverTheme {
        val model = demoPlanModel()
        Surface { PlanWeekDetailContent(model.detail(model.rows.first())) }
    }
}

// endregion

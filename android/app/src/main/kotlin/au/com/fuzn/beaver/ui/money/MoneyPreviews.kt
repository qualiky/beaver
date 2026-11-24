package au.com.fuzn.beaver.ui.money

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.core.WeekSpendTotal
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.LocalDate
import java.util.Locale

// Previews use the fictional demo data only (today is the demo reference date).

private fun demoInput(): ForecastInput {
    val d = LocalDate.of(2026, 11, 23)
    return DemoData.forecastInput(d).copy(
        // An unpaid bill from last week (overdue) and one paid occurrence.
        billPayments = listOf(BillPayment(4, LocalDate.of(2026, 11, 27).minusDays(14), d.minusDays(10), Money.dollars(25))),
        expenses = listOf(
            Expense(1, d, Money.dollars(12, 50), SpendCategory.coffeeDrinks, "flat white"),
            Expense(2, d.plusDays(1), Money.dollars(34), SpendCategory.food, ""),
            Expense(3, d.minusDays(9), Money.dollars(90), SpendCategory.food, "groceries"),
            Expense(4, d.minusDays(16), Money.dollars(210), SpendCategory.shopping, ""),
        ),
        weekSpendTotals = listOf(WeekSpendTotal(d.minusDays(28), Money.dollars(120))),
        deposits = listOf(
            Deposit(1, DemoData.GOAL_ID, d.minusDays(30), Money.dollars(500), "first"),
            Deposit(2, DemoData.GOAL_ID, d.minusDays(7), Money.dollars(250)),
        ),
    )
}

private val previewLocale: Locale = Locale.US

@Composable
private fun Themed(content: @Composable () -> Unit) = BeaverTheme { content() }

@Preview(name = "Bills", showBackground = true, heightDp = 900)
@Composable
private fun BillsPreview() = Themed {
    val model = BillsModel(demoInput(), "AUD", previewLocale)
    BillsContent(model, false, {}, false, {}, {}, {}, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
}

@Preview(name = "Bills monthly", showBackground = true, heightDp = 900)
@Composable
private fun BillsMonthlyPreview() = Themed {
    val model = BillsModel(demoInput(), "AUD", previewLocale)
    BillsContent(model, true, {}, false, {}, {}, {}, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
}

@Preview(name = "Bills paused", showBackground = true, heightDp = 700)
@Composable
private fun BillsPausedPreview() = Themed {
    val input = demoInput().let { i -> i.copy(bills = i.bills.map { if (it.id == 3) it.copy(isActive = false) else it }) }
    BillsContent(BillsModel(input, "AUD", previewLocale), false, {}, true, {}, {}, {}, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
}

@Preview(name = "Spending", showBackground = true, heightDp = 1100)
@Composable
private fun SpendingPreview() = Themed {
    SpendingContent(SpendingModel(demoInput(), "AUD", previewLocale), {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize())
}

@Preview(name = "Goals", showBackground = true, heightDp = 800)
@Composable
private fun GoalsPreview() = Themed {
    GoalsContent(GoalsModel(demoInput(), "AUD", previewLocale), LocalDate.of(2026, 11, 23), {}, {}, {}, {}, {}, Modifier.fillMaxSize())
}

@Preview(name = "Goals dark", showBackground = true, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GoalsDarkPreview() = BeaverTheme(darkTheme = true) {
    GoalsContent(GoalsModel(demoInput(), "AUD", previewLocale), LocalDate.of(2026, 11, 23), {}, {}, {}, {}, {}, Modifier.fillMaxSize())
}

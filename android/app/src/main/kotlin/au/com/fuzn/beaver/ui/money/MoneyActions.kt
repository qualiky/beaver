package au.com.fuzn.beaver.ui.money

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.editors.EditorInvalidException
import au.com.fuzn.beaver.ui.editors.MoneyParser
import java.time.LocalDate

/** Every write the Money tab makes, kept out of the composables so tests can drive them. */
class MoneyActions(private val repository: Repository) {
    suspend fun markPaid(billID: Int, dueDate: LocalDate, paidDate: LocalDate, amount: Money) =
        repository.markPaid(BillPayment(billID, dueDate, paidDate, amount))

    suspend fun unmarkPaid(billID: Int, dueDate: LocalDate) = repository.unmarkPaid(billID, dueDate)

    /** Pauses or resumes a bill. Paused bills are left out of the forecast. */
    suspend fun setActive(bill: Bill, active: Boolean) {
        repository.save(bill.copy(isActive = active))
    }

    suspend fun setWeekTotal(weekStart: LocalDate, amount: Money) = repository.setWeekSpendTotal(weekStart, amount)

    suspend fun clearWeekTotal(weekStart: LocalDate) = repository.clearWeekSpendTotal(weekStart)

    suspend fun save(expense: Expense) {
        repository.save(expense)
    }

    suspend fun deleteExpense(id: Int) = repository.deleteExpense(id)

    suspend fun addDeposit(goalID: Int, date: LocalDate, amount: Money, note: String) {
        repository.save(Deposit(goalID = goalID, date = date, amount = amount, note = note.trim()))
    }

    suspend fun deleteDeposit(id: Int) = repository.deleteDeposit(id)

    suspend fun makePrimary(goalID: Int) = repository.setPrimaryGoalId(goalID)
}

// region Sheet forms. Plain values over the typed text; the sheets keep the text in saveable state.

private fun positiveCents(text: String): Long? = MoneyParser.cents(text)?.takeIf { it > 0 }

/** The "Mark paid" sheet: amount pre-filled with the occurrence's amount, date defaulting to today. */
data class MarkPaidForm(val row: MoneyBillRow, val amountText: String, val paidDate: LocalDate) {
    constructor(row: MoneyBillRow, today: LocalDate) : this(row, MoneyParser.text(row.amount.cents), today)

    val cents: Long? get() = positiveCents(amountText)
    val isValid: Boolean get() = cents != null

    suspend fun save(actions: MoneyActions) {
        val value = cents ?: throw EditorInvalidException()
        actions.markPaid(row.billID, row.dueDate, paidDate, Money(value))
    }
}

data class DepositForm(val goalID: Int, val amountText: String = "", val date: LocalDate, val note: String = "") {
    val cents: Long? get() = positiveCents(amountText)
    val isValid: Boolean get() = cents != null

    suspend fun save(actions: MoneyActions) {
        val value = cents ?: throw EditorInvalidException()
        actions.addDeposit(goalID, date, Money(value), note)
    }
}

data class WeekTotalForm(val weekStart: LocalDate, val amountText: String) {
    /** A total may be zero (nothing spent), but not blank or negative. */
    val cents: Long? get() = MoneyParser.cents(amountText)
    val isValid: Boolean get() = cents != null

    suspend fun save(actions: MoneyActions) {
        val value = cents ?: throw EditorInvalidException()
        actions.setWeekTotal(weekStart, Money(value))
    }

    companion object {
        fun of(weekStart: LocalDate, existing: Money?) = WeekTotalForm(weekStart, existing?.let { MoneyParser.text(it.cents) } ?: "")
    }
}

/** Edits an expense: amount, category, note and date. */
data class ExpenseForm(
    val id: Int,
    val amountText: String,
    val category: SpendCategory,
    val date: LocalDate,
    val note: String,
) {
    constructor(expense: Expense) : this(expense.id, MoneyParser.text(expense.amount.cents), expense.category, expense.date, expense.note)

    val cents: Long? get() = positiveCents(amountText)
    val isValid: Boolean get() = cents != null

    suspend fun save(actions: MoneyActions) {
        val value = cents ?: throw EditorInvalidException()
        actions.save(Expense(id, date, Money(value), category, note.trim()))
    }
}

// endregion

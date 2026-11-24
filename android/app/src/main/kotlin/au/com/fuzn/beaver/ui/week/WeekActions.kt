package au.com.fuzn.beaver.ui.week

import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.common.BillLine
import java.time.LocalDate

/** The writes the Week tab makes. Plain functions over the repository, so they are testable without Compose. */
object WeekActions {
    /** Ticks an unpaid bill (paid today, at its amount) or unticks a paid one. */
    suspend fun togglePaid(repository: Repository, bill: BillLine, today: LocalDate) {
        if (bill.isPaid) {
            repository.unmarkPaid(bill.billID, bill.dueDate)
        } else {
            repository.markPaid(BillPayment(bill.billID, bill.dueDate, today, bill.amount))
        }
    }

    /** Quick-add of a living expense dated today. [cents] must be positive. */
    suspend fun addExpense(repository: Repository, today: LocalDate, cents: Long, category: SpendCategory, note: String) {
        require(cents > 0) { "An expense must be more than zero." }
        repository.save(Expense(date = today, amount = Money(cents), category = category, note = note.trim()))
    }
}

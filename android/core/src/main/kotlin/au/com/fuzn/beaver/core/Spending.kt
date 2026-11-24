@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

@Serializable
enum class SpendCategory { food, transport, coffeeDrinks, goingOut, shopping, other }

/** A quick spending entry. */
@Serializable
data class Expense(
    val id: Int = 0,
    val date: LocalDate,
    val amount: Money,
    val category: SpendCategory = SpendCategory.other,
    val note: String = "",
)

/** One total for a whole week, replacing that week's entries. */
@Serializable
data class WeekSpendTotal(val weekStart: LocalDate, val amount: Money)

/** Weekly living spending maths. */
object Spending {
    /** Spent in the week containing [weekStart]: the week total if set, otherwise the sum of entries. */
    fun spent(weekStart: LocalDate, expenses: List<Expense>, totals: List<WeekSpendTotal>): Money {
        val monday = weekStart.mondayOfWeek
        totals.firstOrNull { it.weekStart.mondayOfWeek == monday }?.let { return it.amount }
        val week = monday.week
        return expenses.filter { it.date in week }.map { it.amount }.total()
    }

    /** Whether any spending was recorded for the week. */
    fun hasData(weekStart: LocalDate, expenses: List<Expense>, totals: List<WeekSpendTotal>): Boolean {
        val monday = weekStart.mondayOfWeek
        val week = monday.week
        return totals.any { it.weekStart.mondayOfWeek == monday } || expenses.any { it.date in week }
    }

    /** Budget minus spent. Positive means under budget. */
    fun variance(budget: Money, spent: Money): Money = budget - spent
}

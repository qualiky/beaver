package au.com.fuzn.beaver.ui.common

import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.BillOccurrence
import au.com.fuzn.beaver.core.BillStatus
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayEvent
import au.com.fuzn.beaver.core.SpendCategory
import java.time.LocalDate

/** A bill occurrence ready for display. */
data class BillLine(
    val billID: Int,
    val name: String,
    val dueDate: LocalDate,
    val amount: Money,
    val amountText: String,
    val dateText: String,
    val isPaid: Boolean,
    val isOverdue: Boolean,
    val category: BillCategory,
) {
    val id: String get() = "$billID-$dueDate"

    companion object {
        fun of(o: BillOccurrence, today: LocalDate, fmt: FigureFormatter) = BillLine(
            billID = o.billID, name = o.name, dueDate = o.dueDate, amount = o.amount,
            amountText = fmt.string(o.amount), dateText = DateText.weekdayDayMonth(o.dueDate, fmt.locale),
            isPaid = o.isPaid, isOverdue = o.status(today) == BillStatus.overdue, category = o.category,
        )
    }
}

/** A pay event ready for display. */
data class PayLine(
    val sourceID: Int,
    val sourceName: String,
    val payday: LocalDate,
    val paydayText: String,
    val expected: Money,
    val expectedText: String,
    val actualText: String?,
    val differenceText: String?,
    val differenceTone: Tone,
    val isActual: Boolean,
    val fromTemplate: Boolean,
    val net: Money,
) {
    val id: String get() = "$sourceID-$payday"

    companion object {
        fun of(e: PayEvent, fmt: FigureFormatter) = PayLine(
            sourceID = e.sourceID, sourceName = e.sourceName, payday = e.payday,
            paydayText = DateText.weekdayDayMonth(e.payday, fmt.locale), expected = e.expectedNet,
            expectedText = fmt.string(e.expectedNet), actualText = e.actualNet?.let(fmt::string),
            differenceText = e.variance?.let(fmt::signed), differenceTone = e.variance?.let { Tone.of(it) } ?: Tone.Neutral,
            isActual = e.isActual, fromTemplate = e.usedTemplate, net = e.net,
        )
    }
}

val SpendCategory.title: String
    get() = when (this) {
        SpendCategory.food -> "Food"
        SpendCategory.transport -> "Transport"
        SpendCategory.coffeeDrinks -> "Coffee & drinks"
        SpendCategory.goingOut -> "Going out"
        SpendCategory.shopping -> "Shopping"
        SpendCategory.other -> "Other"
    }

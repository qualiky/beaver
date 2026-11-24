package au.com.fuzn.beaver.reminders

import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.total
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One local "payday is tomorrow" notification. */
data class PayReminder(
    /** The payday the reminder is about. */
    val payday: LocalDate,
    /** The day it fires: the day before the payday. */
    val fireDate: LocalDate,
    val title: String,
    val body: String,
    /** Fires at this hour, local time. */
    val hour: Int = PayReminderPlanner.FIRE_HOUR,
) {
    val identifier: String get() = PayReminderPlanner.IDENTIFIER_PREFIX + payday

    /** The moment it fires in [zone]. */
    fun fireInstant(zone: ZoneId): Instant = fireDate.atTime(hour, 0).atZone(zone).toInstant()
}

/** Pure planning of payday-eve reminders (no Android types), so it is unit-tested directly. Mirrors the iOS planner. */
object PayReminderPlanner {
    const val IDENTIFIER_PREFIX = "beaver.payday-eve."
    const val HORIZON_DAYS = 28
    const val FIRE_HOUR = 20

    /**
     * Reminders for every distinct payday from tomorrow through four weeks from today. A reminder whose 20:00 has
     * already passed ([nowMinutes] is minutes since local midnight, for today's date) is skipped.
     * [format] renders an amount in the user's currency.
     */
    fun reminders(forecast: Forecast, nowMinutes: Int = 0, format: (Money) -> String): List<PayReminder> {
        val today = forecast.today
        val events = forecast.payEvents
        val paydays = events.map { it.payday }.distinct().sorted()
        val bills = forecast.weeks.flatMap { it.bills }
        val out = mutableListOf<PayReminder>()
        paydays.forEachIndexed { index, payday ->
            val offset = ChronoUnit.DAYS.between(today, payday)
            if (offset < 1 || offset > HORIZON_DAYS) return@forEachIndexed
            val fire = payday.minusDays(1)
            if (fire == today && nowMinutes >= FIRE_HOUR * 60) return@forEachIndexed
            val expected = events.filter { it.payday == payday }.map { it.net }.total()
            val nextPayday = paydays.getOrNull(index + 1) ?: payday.plusDays(7)
            val due = bills.filter { !it.isPaid && it.dueDate >= payday && it.dueDate < nextPayday }
                .map { it.plannedAmount }.total()
            out += PayReminder(
                payday = payday,
                fireDate = fire,
                title = "Pay day tomorrow",
                body = "Pay day tomorrow: expected ${format(expected)}. Bills due before next payday: ${format(due)}.",
            )
        }
        return out
    }
}

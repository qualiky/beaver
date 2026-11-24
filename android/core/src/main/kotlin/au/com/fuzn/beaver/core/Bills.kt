@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

@Serializable
enum class BillCategory { housing, utilities, subscription, transport, debt, insurance, other }

/** How often a bill recurs. */
@Serializable(with = CadenceSerializer::class)
sealed interface Cadence {
    data object Weekly : Cadence
    data object Fortnightly : Cadence
    data class EveryNDays(val n: Int) : Cadence

    /** Due on [dayOfMonth] each month (clamped to the month end) after the anchor occurrence. */
    data class Monthly(val dayOfMonth: Int) : Cadence

    /** Every 3 months from the anchor, keeping the anchor's day of month (clamped). */
    data object Quarterly : Cadence

    /** Every 12 months from the anchor (29 Feb clamps to 28 Feb). */
    data object Yearly : Cadence
    data object OneOff : Cadence
}

/**
 * Swift shape: `{"weekly":{}}`, `{"everyNDays":{"_0":28}}`, `{"monthly":{"dayOfMonth":5}}`, `{"oneOff":{}}`.
 */
internal object CadenceSerializer : SwiftEnumSerializer<Cadence>("Cadence") {
    override fun encodeCase(value: Cadence): Pair<String, JsonObject> = when (value) {
        Cadence.Weekly -> "weekly" to payload()
        Cadence.Fortnightly -> "fortnightly" to payload()
        is Cadence.EveryNDays -> "everyNDays" to payload("_0" to value.n)
        is Cadence.Monthly -> "monthly" to payload("dayOfMonth" to value.dayOfMonth)
        Cadence.Quarterly -> "quarterly" to payload()
        Cadence.Yearly -> "yearly" to payload()
        Cadence.OneOff -> "oneOff" to payload()
    }

    override fun decodeCase(name: String, payload: JsonObject): Cadence = when (name) {
        "weekly" -> Cadence.Weekly
        "fortnightly" -> Cadence.Fortnightly
        "everyNDays" -> Cadence.EveryNDays(payload.intAt("_0"))
        "monthly" -> Cadence.Monthly(payload.intAt("dayOfMonth"))
        "quarterly" -> Cadence.Quarterly
        "yearly" -> Cadence.Yearly
        "oneOff" -> Cadence.OneOff
        else -> throw SerializationException("Unknown Cadence $name")
    }
}

/** A recurring (or one-off) expense. [anchorDate] is the first due date; nothing is generated before it. */
@Serializable
data class Bill(
    val id: Int = 0,
    val name: String,
    val amount: Money,
    val cadence: Cadence,
    val anchorDate: LocalDate,
    val category: BillCategory = BillCategory.other,
    val account: String? = null,
    val isActive: Boolean = true,
    val note: String = "",
) {
    /** The k-th due date (k = 0 is the anchor), or null if the cadence has no such occurrence. */
    internal fun dueDate(k: Int): LocalDate? = when (val c = cadence) {
        Cadence.Weekly -> anchorDate.plusDays(7L * k)
        Cadence.Fortnightly -> anchorDate.plusDays(14L * k)
        is Cadence.EveryNDays -> anchorDate.plusDays(maxOf(c.n, 1).toLong() * k)
        is Cadence.Monthly -> if (k == 0) anchorDate else {
            val target = LocalDate.of(anchorDate.year, anchorDate.month, 1).plusMonths(k.toLong())
            LocalDate.of(target.year, target.month, minOf(maxOf(c.dayOfMonth, 1), target.lengthOfMonth()))
        }
        Cadence.Quarterly -> anchorDate.plusMonths(3L * k)
        Cadence.Yearly -> anchorDate.plusMonths(12L * k)
        Cadence.OneOff -> if (k == 0) anchorDate else null
    }

    /** Due dates inside the inclusive range, never before the anchor. Ignores `isActive`. */
    fun dueDates(range: DateRange): List<LocalDate> {
        if (range.isEmpty || range.end < anchorDate) return emptyList()
        var k = 0
        val step: Int? = when (val c = cadence) {
            Cadence.Weekly -> 7
            Cadence.Fortnightly -> 14
            is Cadence.EveryNDays -> maxOf(c.n, 1)
            else -> null
        }
        if (step != null) {
            val diff = anchorDate.daysUntil(range.start)
            k = if (diff > 0) (diff + step - 1) / step else 0
        }
        val out = mutableListOf<LocalDate>()
        while (true) {
            val d = dueDate(k) ?: break
            if (d > range.end) break
            if (d >= range.start) out.add(d)
            k += 1
        }
        return out
    }

    /** Occurrences in the range with paid state applied from [payments]. */
    fun occurrences(range: DateRange, payments: List<BillPayment> = emptyList()): List<BillOccurrence> =
        dueDates(range).map { due ->
            val payment = payments.firstOrNull { it.billID == id && it.dueDate == due }
            BillOccurrence(id, name, category, due, amount, payment?.paidDate, payment?.amount)
        }

    /** Approximate cost per year, in cents (null for one-off bills). Used for the sinking fund. */
    internal fun annualCostCents(): Long? = when (val c = cadence) {
        Cadence.Weekly -> amount.cents * 52
        Cadence.Fortnightly -> amount.cents * 26
        is Cadence.EveryNDays -> IntMath.roundHalfUp(amount.cents * 365, maxOf(c.n, 1).toLong())
        is Cadence.Monthly -> amount.cents * 12
        Cadence.Quarterly -> amount.cents * 4
        Cadence.Yearly -> amount.cents
        Cadence.OneOff -> null
    }
}

/** A logged payment of one occurrence, with the actual amount and date. */
@Serializable
data class BillPayment(
    val billID: Int,
    val dueDate: LocalDate,
    val paidDate: LocalDate,
    val amount: Money,
)

@Serializable
enum class BillStatus { paid, due, overdue }

/** One due date of a bill. */
@Serializable
data class BillOccurrence(
    val billID: Int,
    val name: String,
    val category: BillCategory,
    val dueDate: LocalDate,
    val plannedAmount: Money,
    val paidDate: LocalDate? = null,
    val paidAmount: Money? = null,
) {
    val isPaid: Boolean get() = paidDate != null

    /** The paid amount if paid, otherwise the planned amount. */
    val amount: Money get() = paidAmount ?: plannedAmount

    /** Paid, overdue (unpaid and before [today]), or due. */
    fun status(today: LocalDate): BillStatus = when {
        isPaid -> BillStatus.paid
        dueDate < today -> BillStatus.overdue
        else -> BillStatus.due
    }
}

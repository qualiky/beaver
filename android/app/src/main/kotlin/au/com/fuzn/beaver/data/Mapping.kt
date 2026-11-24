package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.BeaverJson
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.RateRule
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.core.TaxMethod
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.TemplateShift
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WeekSpendTotal
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.core.parseIsoDate
import au.com.fuzn.beaver.core.parseIsoTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Errors raised when stored values cannot be turned back into core values, or a write is not allowed. */
sealed class DataError(message: String) : Exception(message) {
    class Corrupt(detail: String) : DataError("Stored data is invalid: $detail")
    class BuiltinReadOnly : DataError("Built-in reference tables can not be changed. Duplicate the table to edit it.")
}

internal fun parseDate(s: String): LocalDate = parseIsoDate(s) ?: throw DataError.Corrupt("bad date \"$s\"")
internal fun parseTime(s: String): LocalTime = parseIsoTime(s) ?: throw DataError.Corrupt("bad time \"$s\"")

private inline fun <reified E : Enum<E>> parseEnum(s: String): E =
    enumValues<E>().firstOrNull { it.name == s } ?: throw DataError.Corrupt("bad ${E::class.simpleName} \"$s\"")

/** Encodes a core value to the compact JSON text stored in a `*_json` column. */
private inline fun <reified T> jsonText(value: T): String = BeaverJson.encodeToString(serializer<T>(), value)

private inline fun <reified T> fromJsonText(text: String, what: String): T =
    try {
        BeaverJson.decodeFromString(serializer<T>(), text)
    } catch (e: Exception) {
        throw DataError.Corrupt("bad $what JSON")
    }

// Core values use id 0 for "not saved yet"; so do entities (Room assigns the next id on insert).

internal fun RateCard.toEntity() = RateCardEntity(id, name, jsonText(rules))
internal fun RateCardEntity.toCore() = RateCard(id, name, fromJsonText<List<RateRule>>(rulesJson, "rate card rules"))

internal fun WithholdingTable.toEntity() =
    WithholdingTableEntity(id, name, jsonText(rows), isBuiltin, note)

internal fun WithholdingTableEntity.toCore() =
    WithholdingTable(id, name, fromJsonText<List<WithholdingRow>>(rowsJson, "withholding rows"), builtin, note)

internal fun IncomeSource.toEntity() = IncomeSourceEntity(
    id = id, name = name, kind = kind.name, rateCardId = rateCardID, fixedAmountCents = fixedAmount?.cents,
    amountIsNet = amountIsNet, scheduleJson = jsonText(schedule), taxMethodJson = jsonText(taxMethod),
    weeklyCapSoft = weeklyCapSoftHours, weeklyCapHard = weeklyCapHardHours, active = isActive,
)

internal fun IncomeSourceEntity.toCore() = IncomeSource(
    id = id, name = name, kind = parseEnum<IncomeKind>(kind), rateCardID = rateCardId,
    fixedAmount = fixedAmountCents?.let { Money(it) }, amountIsNet = amountIsNet,
    schedule = fromJsonText<PaySchedule>(scheduleJson, "schedule"),
    taxMethod = fromJsonText<TaxMethod>(taxMethodJson, "tax method"),
    weeklyCapSoftHours = weeklyCapSoft, weeklyCapHardHours = weeklyCapHard, isActive = active,
)

internal fun Template.toEntity() = TemplateEntity(id, sourceID, name, effectiveFrom?.toString())

internal fun TemplateEntity.toCore(shifts: List<TemplateShiftEntity>) =
    Template(id, sourceId, name, effectiveFrom?.let(::parseDate), shifts.map { it.toCore() })

internal fun TemplateShift.toEntity(templateId: Int) = TemplateShiftEntity(
    templateId = templateId, weekday = weekday.value, start = formatTime(start), end = formatTime(end), breakMin = breakMinutes,
)

internal fun TemplateShiftEntity.toCore(): TemplateShift {
    if (weekday !in 1..7) throw DataError.Corrupt("bad weekday $weekday")
    return TemplateShift(DayOfWeek.of(weekday), parseTime(start), parseTime(end), breakMin)
}

/** `HH:mm`, the iOS text form of a time. */
internal fun formatTime(t: LocalTime): String =
    t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')

internal fun Shift.toEntity() = ShiftEntity(
    id = id, sourceId = sourceID, date = date.toString(), start = formatTime(start), end = formatTime(end),
    breakMin = breakMinutes, status = status.name, note = note,
)

internal fun ShiftEntity.toCore() = Shift(
    id = id, sourceID = sourceId, date = parseDate(date), start = parseTime(start), end = parseTime(end),
    breakMinutes = breakMin, status = parseEnum<ShiftStatus>(status), note = note,
)

internal fun PayReceived.toEntity() =
    PayReceivedEntity(id, sourceID, payDate.toString(), net.cents, gross?.cents, tax?.cents, note)

internal fun PayReceivedEntity.toCore() = PayReceived(
    id, sourceId, parseDate(payDate), Money(netCents), grossCents?.let { Money(it) }, taxCents?.let { Money(it) }, note,
)

internal fun Holiday.toEntity() = HolidayEntity(date.toString(), name)
internal fun HolidayEntity.toCore() = Holiday(parseDate(date), name)

internal fun UnavailableRange.toEntity() = UnavailableEntity(id, from.toString(), to.toString(), note)
internal fun UnavailableEntity.toCore() = UnavailableRange(id, parseDate(dateFrom), parseDate(dateTo), note)

internal fun FortnightLimit.toEntity() = HoursLimitEntity(id, hoursPerFortnight, effectiveFrom.toString())
internal fun HoursLimitEntity.toCore() = FortnightLimit(id, hoursPerFortnight, parseDate(effectiveFrom))

internal fun Bill.toEntity() = BillEntity(
    id = id, name = name, amountCents = amount.cents, cadenceJson = jsonText(cadence), anchorDate = anchorDate.toString(),
    category = category.name, account = account, active = isActive, note = note,
)

internal fun BillEntity.toCore() = Bill(
    id = id, name = name, amount = Money(amountCents), cadence = fromJsonText<Cadence>(cadenceJson, "cadence"),
    anchorDate = parseDate(anchorDate), category = parseEnum<BillCategory>(category), account = account,
    isActive = active, note = note,
)

internal fun BillPayment.toEntity(id: Int = 0) =
    BillPaymentEntity(id, billID, dueDate.toString(), paidDate.toString(), amount.cents)

internal fun BillPaymentEntity.toCore() =
    BillPayment(billId, parseDate(dueDate), parseDate(paidDate), Money(amountCents))

internal fun Expense.toEntity() = ExpenseEntity(id, date.toString(), amount.cents, category.name, note)
internal fun ExpenseEntity.toCore() =
    Expense(id, parseDate(date), Money(amountCents), parseEnum<SpendCategory>(category), note)

internal fun WeekSpendTotal.toEntity() = WeekSpendTotalEntity(weekStart.toString(), amount.cents)
internal fun WeekSpendTotalEntity.toCore() = WeekSpendTotal(parseDate(weekStart), Money(amountCents))

internal fun Goal.toEntity() = GoalEntity(id, name, target.cents, deadline?.toString())
internal fun GoalEntity.toCore() = Goal(id, name, Money(targetCents), deadline?.let(::parseDate))

internal fun Deposit.toEntity() = DepositEntity(id, goalID, date.toString(), amount.cents, note)
internal fun DepositEntity.toCore() = Deposit(id, goalId, parseDate(date), Money(amountCents), note)

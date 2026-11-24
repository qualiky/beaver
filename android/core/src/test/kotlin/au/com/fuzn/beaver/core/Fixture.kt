package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Decoder for `fixtures/golden.json` (see fixtures/README.md). The file is the single source of truth for the
// golden cases; this file only maps its data to engine types. Nothing here is a copy of the fixture.

/** The supported fixture schema version. Bump it together with fixtures/README.md and the Swift tests. */
const val SUPPORTED_FIXTURE_VERSION = 1

private val fixtureJson = Json { ignoreUnknownKeys = true }

/** The fixture, decoded once. The path comes from the Gradle `beaver.fixture.path` system property. */
val fixture: Fixture by lazy {
    val path = System.getProperty("beaver.fixture.path")
        ?: error("System property beaver.fixture.path is not set (Gradle sets it for :core:test)")
    val file = File(path)
    check(file.isFile) { "Golden fixture not found at $path" }
    fixtureJson.decodeFromString(Fixture.serializer(), file.readText())
}

@Serializable
data class Fixture(
    val fixtureVersion: Int,
    val rateCards: List<FRateCard>,
    val holidaySets: List<FHolidaySet>,
    val withholdingTables: List<FWithholdingTable>,
    val shiftCases: List<FShiftCase>,
    val weekCases: List<FWeekCase>,
    val taxCases: List<FTaxCase>,
    val scheduleCases: List<FScheduleCase>,
    val billCases: List<FBillCase>,
    val hoursLimitCases: List<FHoursLimitCase>,
    val forecastCases: List<FForecastCase>,
) {
    fun rateCard(id: String): RateCard =
        (rateCards.firstOrNull { it.id == id } ?: error("unknown rateCard $id")).model

    fun holidays(id: String): Set<LocalDate> =
        (holidaySets.firstOrNull { it.id == id } ?: error("unknown holidaySet $id")).holidays.map { parseDate(it.date) }.toSet()

    fun table(id: String): WithholdingTable =
        (withholdingTables.firstOrNull { it.id == id } ?: error("unknown withholdingTable $id")).model
}

fun parseDate(s: String): LocalDate = parseIsoDate(s) ?: error("bad date $s")
fun parseTime(s: String): LocalTime = parseIsoTime(s) ?: error("bad time $s")

/** "HH:mm" to minutes since midnight; "24:00" is allowed (end of day). */
fun clockMinutes(s: String): Int {
    val p = s.split(":").map { it.toInt() }
    require(p.size == 2) { "bad clock time $s" }
    return p[0] * 60 + p[1]
}

fun weekday(s: String): DayOfWeek = DayOfWeek.valueOf(s.uppercase())

/** Parses a non-negative decimal string with at most 4 fraction digits into ten-thousandths, without floating point. */
fun tenThousandths(s: String): Int {
    val parts = s.split(".")
    require(parts.size <= 2) { "bad decimal $s" }
    val whole = parts[0].toInt()
    var frac = if (parts.size == 2) parts[1] else ""
    require(frac.length <= 4 && frac.all { it.isDigit() }) { "bad decimal $s" }
    frac += "0".repeat(4 - frac.length)
    return whole * 10_000 + frac.toInt()
}

fun payFrequency(s: String): PayFrequency = PayFrequency.valueOf(s)

@Serializable
data class FWindow(val from: String, val to: String)

@Serializable
data class FRule(val band: String, val centsPerHour: Int, val days: String, val window: FWindow? = null) {
    val model: RateRule
        get() {
            val selector = when (days) {
                "publicHoliday" -> DaySelector.publicHoliday
                "saturday" -> DaySelector.saturday
                "sunday" -> DaySelector.sunday
                "weekday" -> DaySelector.weekdays // the fixture says "weekday", the engine enum says "weekdays"
                else -> error("unknown day selector $days")
            }
            return RateRule(band, centsPerHour, selector, window?.let { ClockWindow(clockMinutes(it.from), clockMinutes(it.to)) })
        }
}

@Serializable
data class FRateCard(val id: String, val name: String, val rules: List<FRule>) {
    /** Rate card ids are strings in the fixture; the engine uses an Int id, which tests set to 1. */
    val model: RateCard get() = RateCard(1, name, rules.map { it.model })
}

@Serializable
data class FHoliday(val date: String, val name: String)

@Serializable
data class FHolidaySet(val id: String, val holidays: List<FHoliday>)

@Serializable
data class FWithholdingRow(val belowDollars: Int? = null, val a: String, val b: String)

@Serializable
data class FWithholdingTable(val id: String, val name: String, val rows: List<FWithholdingRow>) {
    val model: WithholdingTable
        get() = WithholdingTable(id, name, rows.map { WithholdingRow(it.belowDollars, tenThousandths(it.a), tenThousandths(it.b)) })
}

@Serializable
data class FShift(val date: String, val start: String, val end: String, val breakMinutes: Int) {
    val model: Shift get() = Shift(sourceID = 1, date = parseDate(date), start = parseTime(start), end = parseTime(end), breakMinutes = breakMinutes)
}

@Serializable
data class FBandMinutes(val band: String, val minutes: Int, val hoursHundredths: Int)

@Serializable
data class FShiftCase(
    val id: String,
    val description: String,
    val rateCard: String,
    val holidaySet: String,
    val shift: FShift,
    val expected: Expected,
) {
    @Serializable
    data class Expected(val durationMinutes: Int, val paidMinutes: Int, val bands: List<FBandMinutes>, val grossCents: Long)
}

@Serializable
data class FWeekCase(
    val id: String,
    val description: String,
    val rateCard: String,
    val holidaySet: String,
    val withholdingTable: String,
    val frequency: String,
    val shifts: List<FShift>,
    val expected: Expected,
) {
    @Serializable
    data class Expected(
        val paidMinutes: Int,
        val grossCents: Long,
        val taxCents: Long,
        val netCents: Long,
        val bandHoursHundredths: Map<String, Int> = emptyMap(),
    )
}

@Serializable
data class FTaxCase(
    val id: String,
    val description: String,
    val withholdingTable: String,
    val frequency: String,
    val samples: List<Sample>,
) {
    @Serializable
    data class Sample(val grossCents: Long, val taxCents: Long)
}

@Serializable
data class FRange(val start: String, val end: String) {
    val model: DateRange get() = DateRange(parseDate(start), parseDate(end))
}

@Serializable
data class FSchedule(
    val type: String,
    val payWeekday: String? = null,
    val lagWeeks: Int? = null,
    val anchorPayday: String? = null,
    val dayOfMonth: Int? = null,
) {
    val model: PaySchedule
        get() = when (type) {
            "weekly" -> PaySchedule.Weekly(weekday(payWeekday!!), lagWeeks!!)
            "fortnightly" -> PaySchedule.Fortnightly(parseDate(anchorPayday!!), lagWeeks!!)
            "monthly" -> PaySchedule.Monthly(dayOfMonth!!)
            else -> error("unknown schedule type $type")
        }
}

@Serializable
data class FScheduleCase(
    val id: String,
    val description: String,
    val schedule: FSchedule,
    val workDateToPayday: List<WorkDay>? = null,
    val paydayToWorkPeriod: List<Period>? = null,
    val paydaysInRange: Paydays? = null,
) {
    @Serializable
    data class WorkDay(val workDate: String, val payday: String)

    @Serializable
    data class Period(val payday: String, val start: String, val end: String)

    @Serializable
    data class Paydays(val range: FRange, val expected: List<String>)
}

@Serializable
data class FCadence(val type: String, val n: Int? = null, val dayOfMonth: Int? = null) {
    val model: Cadence
        get() = when (type) {
            "weekly" -> Cadence.Weekly
            "fortnightly" -> Cadence.Fortnightly
            "everyNDays" -> Cadence.EveryNDays(n!!)
            "monthly" -> Cadence.Monthly(dayOfMonth!!)
            "quarterly" -> Cadence.Quarterly
            "yearly" -> Cadence.Yearly
            "oneOff" -> Cadence.OneOff
            else -> error("unknown cadence $type")
        }
}

@Serializable
data class FBill(val name: String, val amountCents: Long, val category: String, val cadence: FCadence, val anchorDate: String) {
    fun model(id: Int = 0): Bill =
        Bill(id, name, Money(amountCents), cadence.model, parseDate(anchorDate), BillCategory.valueOf(category))
}

@Serializable
data class FBillCase(val id: String, val description: String, val bill: FBill, val queries: List<Query>) {
    @Serializable
    data class Query(val range: FRange, val expectedDueDates: List<String>)
}

@Serializable
data class FHoursLimitCase(val id: String, val description: String, val limit: Limit, val scenarios: List<Scenario>) {
    @Serializable
    data class Limit(val hoursPerFortnight: Int, val effectiveFrom: String)

    @Serializable
    data class Expected(val warns: Boolean, val workedMinutes: Int? = null, val period: FRange? = null)

    @Serializable
    data class Scenario(val description: String, val date: String, val shifts: List<FShift>, val expected: Expected)
}

@Serializable
data class FForecastCase(val id: String, val description: String, val input: Input, val expected: Expected) {
    @Serializable
    data class Source(val name: String, val kind: String, val rateCard: String, val withholdingTable: String, val schedule: FSchedule)

    @Serializable
    data class TShift(val weekday: String, val start: String, val end: String, val breakMinutes: Int)

    @Serializable
    data class Tmpl(val name: String, val shifts: List<TShift>)

    @Serializable
    data class Unavailable(val from: String, val to: String, val note: String)

    @Serializable
    data class GoalIn(val name: String, val targetCents: Long, val deadline: String, val depositsCents: Long)

    @Serializable
    data class Pay(val payDate: String, val netCents: Long)

    @Serializable
    data class Input(
        val today: String,
        val source: Source,
        val template: Tmpl,
        val bills: List<FBill>,
        val livingBudgetCents: Long,
        val holidays: List<FHoliday>,
        val unavailable: List<Unavailable>,
        val goal: GoalIn,
        val paysReceived: List<Pay>,
    )

    @Serializable
    data class Payday(val payday: String, val netCents: Long)

    @Serializable
    data class BillTotal(val name: String, val count: Int, val totalCents: Long)

    @Serializable
    data class Expected(
        val paydayCount: Int,
        val paydayNets: List<Payday>,
        val totalIncomeCents: Long,
        val totalBillsCents: Long,
        val totalLivingCents: Long,
        val projectedAtDeadlineCents: Long,
        val gapCents: Long,
        val requiredPerPaydayCents: Long,
        val billTotals: List<BillTotal>,
    )

    /** Maps the fixture's demo input to the engine's [ForecastInput]. */
    fun forecastInput(fixture: Fixture, includeCustomTable: Boolean = true): ForecastInput {
        val sourceID = 1
        val cardID = 1
        val i = input
        require(i.source.kind == "hourly") { "forecast fixture supports hourly sources only" }
        val card = fixture.rateCard(i.source.rateCard).copy(id = cardID)
        val source = IncomeSource(
            id = sourceID, name = i.source.name, kind = IncomeKind.hourly, rateCardID = cardID,
            schedule = i.source.schedule.model, taxMethod = TaxMethod.Table(i.source.withholdingTable),
        )
        val template = Template(
            id = 1, sourceID = sourceID, name = i.template.name,
            shifts = i.template.shifts.map { TemplateShift(weekday(it.weekday), parseTime(it.start), parseTime(it.end), it.breakMinutes) },
        )
        val goal = Goal(1, i.goal.name, Money(i.goal.targetCents), parseDate(i.goal.deadline))
        val today = parseDate(i.today)
        return ForecastInput(
            today = today, sources = listOf(source), rateCards = listOf(card),
            withholdingTables = if (includeCustomTable) listOf(fixture.table(i.source.withholdingTable)) else emptyList(),
            templates = listOf(template),
            paysReceived = i.paysReceived.map { PayReceived(1, sourceID, parseDate(it.payDate), Money(it.netCents)) },
            holidays = i.holidays.map { Holiday(parseDate(it.date), it.name) },
            unavailable = i.unavailable.mapIndexed { n, u -> UnavailableRange(n + 1, parseDate(u.from), parseDate(u.to), u.note) },
            bills = i.bills.mapIndexed { n, b -> b.model(n + 1) },
            livingBudget = Money(i.livingBudgetCents), goals = listOf(goal),
            deposits = if (i.goal.depositsCents > 0) listOf(Deposit(goalID = 1, date = today, amount = Money(i.goal.depositsCents))) else emptyList(),
            primaryGoalID = 1,
        )
    }
}

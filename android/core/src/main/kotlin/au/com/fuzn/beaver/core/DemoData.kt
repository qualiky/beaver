package au.com.fuzn.beaver.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Fictional sample data: a casual worker named "Sam". Used by previews, tests and the "Load demo data" button only.
 * A real install never seeds this, and it contains no real person's data.
 */
object DemoData {
    const val SOURCE_ID = 1
    const val RATE_CARD_ID = 1
    const val GOAL_ID = 1
    const val PERSONA_NAME = "Sam"

    /** The date the golden forecast tests use. */
    val referenceToday: LocalDate = LocalDate.of(2026, 11, 23)

    val rateCard: RateCard get() = RateCard.typicalCasual(RATE_CARD_ID)

    val source: IncomeSource
        get() = IncomeSource(
            id = SOURCE_ID, name = "Example Bar", kind = IncomeKind.hourly, rateCardID = RATE_CARD_ID,
            schedule = PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1),
            taxMethod = TaxMethod.Table(WithholdingTable.AU_SCALE2_WEEKLY_ID),
        )

    val template: Template
        get() = Template(
            id = 1, sourceID = SOURCE_ID, name = "Default week", shifts = listOf(
                TemplateShift(DayOfWeek.WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0), 30),
                TemplateShift(DayOfWeek.THURSDAY, LocalTime.of(18, 0), LocalTime.of(23, 0), 30),
                TemplateShift(DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(4, 30), 30),
                TemplateShift(DayOfWeek.SATURDAY, LocalTime.of(18, 0), LocalTime.of(4, 30), 30),
                TemplateShift(DayOfWeek.SUNDAY, LocalTime.of(17, 0), LocalTime.of(1, 0), 30),
            )
        )

    val bills: List<Bill>
        get() = listOf(
            Bill(1, "Rent", Money.dollars(400), Cadence.Weekly, LocalDate.of(2026, 11, 25), BillCategory.housing),
            Bill(2, "Phone", Money.dollars(30), Cadence.EveryNDays(28), LocalDate.of(2026, 11, 22), BillCategory.utilities),
            Bill(3, "Streaming", Money.dollars(15, 99), Cadence.Monthly(5), LocalDate.of(2026, 12, 5), BillCategory.subscription),
            Bill(4, "Gym", Money.dollars(25), Cadence.Fortnightly, LocalDate.of(2026, 11, 27), BillCategory.subscription),
            Bill(5, "Electricity", Money.dollars(240), Cadence.Quarterly, LocalDate.of(2027, 1, 15), BillCategory.utilities),
            Bill(6, "Car service", Money.dollars(500), Cadence.OneOff, LocalDate.of(2026, 12, 10), BillCategory.transport),
        )

    val livingBudget: Money = Money.dollars(150)

    val holidays: List<Holiday>
        get() = listOf(
            Holiday(LocalDate.of(2026, 12, 25), "Christmas Day"),
            Holiday(LocalDate.of(2026, 12, 26), "Boxing Day"),
            Holiday(LocalDate.of(2026, 12, 28), "Boxing Day (observed)"),
            Holiday(LocalDate.of(2027, 1, 1), "New Year's Day"),
            Holiday(LocalDate.of(2027, 1, 26), "Australia Day"),
        )

    val holidayDates: Set<LocalDate> get() = holidays.map { it.date }.toSet()

    val unavailable: List<UnavailableRange>
        get() = listOf(UnavailableRange(1, LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 5), "Trip"))

    val goal: Goal
        get() = Goal(GOAL_ID, "Course fees", Money.dollars(6000), LocalDate.of(2027, 3, 31))

    val paysReceived: List<PayReceived>
        get() = listOf(PayReceived(1, SOURCE_ID, LocalDate.of(2026, 11, 25), Money.dollars(600)))

    /** The full demo state as a forecast input. */
    fun forecastInput(today: LocalDate = referenceToday): ForecastInput = ForecastInput(
        today = today, sources = listOf(source), rateCards = listOf(rateCard), templates = listOf(template),
        paysReceived = paysReceived, holidays = holidays, unavailable = unavailable, bills = bills,
        livingBudget = livingBudget, goals = listOf(goal), primaryGoalID = GOAL_ID,
    )
}

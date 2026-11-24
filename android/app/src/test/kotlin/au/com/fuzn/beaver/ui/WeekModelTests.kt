package au.com.fuzn.beaver.ui

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.core.SplitKind
import au.com.fuzn.beaver.data.AppStore
import au.com.fuzn.beaver.data.TestDb
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.week.BackupReminder
import au.com.fuzn.beaver.ui.week.HoursBar
import au.com.fuzn.beaver.ui.week.WeekActions
import au.com.fuzn.beaver.ui.week.WeekModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
class WeekHeadlineTests {
    @Test
    fun headlineShowsAheadForDemoData() = DemoStore().use { demo ->
        val headline = demo.weekModel().headline
        assertEquals("+\$2,578.99 ahead", headline.text)
        assertEquals(Tone.Positive, headline.tone)
        assertTrue(headline.detail!!.contains("\$8,578.99"))
        assertTrue(headline.detail!!.contains("\$6,000.00"))
    }

    @Test
    fun headlineShowsBehindWithNegativeSign() {
        val model = demoModel { it.copy(goals = listOf(Goal(DemoData.GOAL_ID, "Big", Money.dollars(20_000), day("2027-03-31")))) }
        assertTrue(model.headline.text.endsWith(" behind"))
        assertTrue(model.headline.text.startsWith("-\$"))
        assertEquals(Tone.Negative, model.headline.tone)
    }

    @Test
    fun headlineWithoutGoalShowsThisWeekSurplus() {
        val model = demoModel { it.copy(goals = emptyList(), primaryGoalID = null) }
        // Week of 23 Nov: $600 actual pay - $400 rent - $25 gym - $150 living.
        assertEquals("This week: +\$25.00 surplus", model.headline.text)
        assertNull(model.goalCard)
    }

    @Test
    fun goalCardShowsProgressAndRequiredAmount() {
        val model = demoModel { it.copy(deposits = listOf(Deposit(goalID = DemoData.GOAL_ID, date = day("2026-11-20"), amount = Money.dollars(1500)))) }
        val card = model.goalCard!!
        assertEquals(0.25f, card.progress, 0.0001f)
        assertEquals("\$1,500.00", card.savedText)
        assertTrue(card.requiredText.endsWith("needed per payday"))
        assertEquals("19 paydays left", card.paydaysText)
    }

    @Test
    fun emptyStateWhenNoSourcesOrBills() {
        val empty = TestDb().use { db ->
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val store = AppStore.create(db.repository, scope) { TODAY }
                val model = WeekModel(store.forecast.value, store.input.value, store.settings.value, locale = US)
                scope.cancel()
                model.isEmpty
            }
        }
        assertTrue(empty)
        DemoStore().use { assertFalse(it.weekModel().isEmpty) }
    }
}

@RunWith(RobolectricTestRunner::class)
class WeekDatesTests {
    @Test
    fun nextPaydayAndSplitUseTheRightDates() = DemoStore().use { demo ->
        val model = demo.weekModel()
        assertEquals(day("2026-11-25"), model.nextPayday)
        val split = demo.store.forecast.value.suggestedSplit!!
        assertEquals(day("2026-11-25"), split.payday)
        assertEquals(day("2026-12-02"), split.followingPayday)
        assertEquals(listOf(SplitKind.bills, SplitKind.living, SplitKind.sinkingFund, SplitKind.goal), model.split!!.lines.map { it.kind })
        // Rent (25 Nov) is due on payday, so it belongs to this split, not to "before the next payday".
        assertEquals(Money.dollars(400) + Money.dollars(25), split.line(SplitKind.bills)!!.needed)
    }

    @Test
    fun billsBeforeNextPaydayMidWeek() {
        // Thursday 26 Nov: next payday is Wed 2 Dec, so the window is Mon 23 Nov through Tue 1 Dec.
        val model = demoModel(today = day("2026-11-26"))
        assertEquals(day("2026-12-02"), model.nextPayday)
        assertEquals(listOf("Phone", "Rent", "Gym"), model.bills.map { it.name })
        assertEquals(listOf(day("2026-11-22"), day("2026-11-25"), day("2026-11-27")), model.bills.map { it.dueDate })
        assertTrue(model.bills.first().isOverdue)
        assertEquals(Money.dollars(455), model.billsTotal)
        assertEquals("Wed, Dec 2", model.split!!.paydayText)
        val split = model.forecast.suggestedSplit!!
        assertEquals(day("2026-12-09"), split.followingPayday)
        // Rent 2 Dec + Streaming 5 Dec fall in 2 Dec ... 8 Dec.
        assertEquals(Money.dollars(400) + Money.dollars(15, 99), split.line(SplitKind.bills)!!.needed)
    }

    @Test
    fun billsOnTheDayBeforePaydayAreIncludedAndPaydayBillsAreNot() {
        // Tuesday 24 Nov, payday Wed 25: only the overdue Phone bill is "before" it; Rent lands on payday.
        assertEquals(listOf("Phone"), demoModel(today = day("2026-11-24")).bills.map { it.name })
    }

    @Test
    fun paidBillsStayListedWithATick() {
        val model = demoModel(today = day("2026-11-26")) {
            it.copy(billPayments = listOf(BillPayment(1, day("2026-11-25"), day("2026-11-25"), Money.dollars(400))))
        }
        assertTrue(model.bills.first { it.name == "Rent" }.isPaid)
        assertEquals(Money.dollars(55), model.billsUnpaidTotal)
    }

    @Test
    fun payLinesShowActualAndDifference() {
        val model = demoModel()
        assertEquals(1, model.payLines.size)
        val line = model.payLines.first()
        assertEquals(day("2026-11-25"), line.payday)
        assertTrue(line.isActual)
        assertEquals("\$600.00", line.actualText)
        assertTrue(line.differenceText!!.startsWith("-\$") || line.differenceText!!.startsWith("+\$"))
    }

    @Test
    fun markPaidUnmarkAndExpensesFlowThroughTheRepository() = DemoStore().use { demo ->
        runBlocking {
            assertEquals("\$150.00", demo.weekModel().living.leftText)

            WeekActions.addExpense(demo.repository, TODAY, 4000, SpendCategory.food, " Lunch ")
            val phone = demo.weekModel().bills.first { it.name == "Phone" }
            assertFalse(phone.isPaid)
            WeekActions.togglePaid(demo.repository, phone, TODAY)
            demo.store.reload()

            val after = demo.weekModel()
            assertEquals("\$110.00", after.living.leftText)
            // A paid overdue bill leaves the list (only unpaid overdue items are carried over).
            assertTrue(after.bills.all { it.isPaid })
            assertEquals("Lunch", demo.repository.expenses().single().note)
            assertEquals(1, demo.repository.billPayments().size)

            WeekActions.togglePaid(demo.repository, phone.copy(isPaid = true), TODAY) // ticking a paid bill unmarks it
            demo.store.reload()
            assertTrue(demo.repository.billPayments().isEmpty())
            assertFalse(demo.weekModel().bills.first { it.name == "Phone" }.isPaid)
        }
    }
}

class WeekBannerTests {
    @Test
    fun noDangerBannerWhenCurrentAndNextWeekAreFine() {
        val model = demoModel()
        // The demo has danger weeks later in December, but not this week or next.
        assertTrue(model.forecast.dangerWeeks.isNotEmpty())
        assertTrue(model.dangerBanners.isEmpty())
    }

    @Test
    fun dangerBannerForNextWeekListsCausingBills() {
        val model = demoModel {
            it.copy(bills = it.bills + Bill(9, "Big repair", Money.dollars(2000), Cadence.OneOff, day("2026-12-01"), BillCategory.transport))
        }
        val banner = model.dangerBanners.single()
        assertEquals(day("2026-11-30"), banner.weekStart)
        assertEquals("Next week is tight", banner.title)
        assertTrue(banner.bills.map { it.name }.contains("Big repair"))
        assertTrue(banner.shortfallText.endsWith("short"))
    }

    @Test
    fun dangerBannersCoverBothThisAndNextWeek() {
        val model = demoModel {
            it.copy(bills = it.bills +
                Bill(9, "A", Money.dollars(2000), Cadence.OneOff, day("2026-11-24"), BillCategory.other) +
                Bill(10, "B", Money.dollars(2000), Cadence.OneOff, day("2026-12-01"), BillCategory.other))
        }
        assertEquals(listOf("This week is tight", "Next week is tight"), model.dangerBanners.map { it.title })
    }

    @Test
    fun backupReminderLogic() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        fun ago(days: Long): Instant = now.minus(days, ChronoUnit.DAYS)
        assertTrue(BackupReminder.isDue(null, now))
        assertFalse(BackupReminder.isDue(ago(3), now))
        assertFalse(BackupReminder.isDue(ago(7), now)) // exactly 7 days is not "more than 7"
        assertTrue(BackupReminder.isDue(ago(7).minusSeconds(1), now))
        assertTrue(BackupReminder.isDue(ago(8), now))
        assertNull(BackupReminder.message(ago(3), now))
        assertTrue(BackupReminder.message(null, now)!!.contains("haven't backed up"))
        assertTrue(BackupReminder.message(ago(8), now)!!.contains("8 days"))
    }

    @Test
    fun backupBannerFollowsSettings() {
        // Demo data has never been exported.
        assertNotNull(demoModel().backupMessage)
    }
}

class HoursBarTests {
    private val limit = FortnightLimit(hoursPerFortnight = 40, effectiveFrom = day("2026-11-23"))

    @Test
    fun okNearOverThresholds() {
        assertEquals(HoursBar.Status.Ok, HoursBar.make(listOf(limit), shifts(4), TODAY)!!.status) // 32 h
        assertEquals(HoursBar.Status.Near, HoursBar.make(listOf(limit), shifts(5), TODAY)!!.status) // 40 h, at the limit
        assertEquals(HoursBar.Status.Over, HoursBar.make(listOf(limit), shifts(6), TODAY)!!.status) // 48 h
        // 36 h is exactly limit - 4: amber.
        val thirtySix = shifts(4) + Shift(sourceID = 1, date = day("2026-11-28"), start = LocalTime.of(8, 0), end = LocalTime.of(12, 0))
        assertEquals(HoursBar.Status.Near, HoursBar.make(listOf(limit), thirtySix, TODAY)!!.status)
        // 35 h 59 min is still fine.
        assertEquals(HoursBar.Status.Ok, HoursBar.status(35 * 60 + 59, 40))
        assertEquals(HoursBar.Status.Over, HoursBar.status(40 * 60 + 1, 40))
    }

    @Test
    fun nullWhenNoLimitIsActiveForTheCurrentFortnight() {
        assertNull(HoursBar.make(emptyList(), emptyList(), TODAY))
        assertNull(HoursBar.make(listOf(FortnightLimit(hoursPerFortnight = 40, effectiveFrom = day("2026-12-07"))), emptyList(), TODAY))
    }

    @Test
    fun cancelledShiftsAndOtherFortnightsDoNotCount() {
        val all = shifts(2).toMutableList()
        all[0] = all[0].copy(status = ShiftStatus.cancelled)
        all += Shift(sourceID = 1, date = day("2026-12-10"), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0))
        val bar = HoursBar.make(listOf(limit), all, TODAY)!!
        assertEquals(480, bar.usedMinutes)
        assertEquals(DateRange(day("2026-11-23"), day("2026-12-06")), bar.window)
    }

    @Test
    fun warningTextOnlyWhenNearOrOver() {
        assertNull(demoModel().hoursWarning)
        val over = demoModel(limits = listOf(limit)) { it.copy(shifts = shifts(6)) }
        assertEquals(HoursBar.Status.Over, over.hoursBar!!.status)
        assertTrue(over.hoursWarning!!.contains("over"))
        val near = demoModel(limits = listOf(limit)) { it.copy(shifts = shifts(5)) }
        assertTrue(near.hoursWarning!!.contains("close to"))
        val ok = demoModel(limits = listOf(limit)) { it.copy(shifts = shifts(2)) }
        assertNull(ok.hoursWarning)
    }

    @Test
    fun barTextAndFraction() {
        val bar = HoursBar.make(listOf(limit), shifts(5), TODAY)!!
        assertEquals("40 h of 40 h", bar.summary)
        assertEquals(1f, bar.fraction, 0.0001f)
        assertEquals("7 h 30 min", HoursBar.hoursText(450))
    }
}

class SplitCardTests {
    @Test
    fun fromBufferLinesAreFlagged() {
        // A huge goal requirement leaves the goal line short.
        val model = demoModel { it.copy(goals = listOf(Goal(DemoData.GOAL_ID, "Huge", Money.dollars(500_000), day("2027-03-31")))) }
        val split = model.split!!
        assertEquals(SplitKind.goal, split.lines.last().kind)
        assertNotNull(split.lines.last().fromBufferText)
        assertNotNull(split.totalFromBufferText)
        assertNull(split.lines.first().fromBufferText)
    }
}

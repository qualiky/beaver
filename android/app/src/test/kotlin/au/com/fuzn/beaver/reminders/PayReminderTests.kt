package au.com.fuzn.beaver.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.FigureFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PayReminderTests {
    private val today = DemoData.referenceToday // Mon 2026-11-23
    private val forecast = Forecast.compute(DemoData.forecastInput(today))
    private val fmt = FigureFormatter("AUD", Locale.US)

    private fun plan(nowMinutes: Int = 0, forecast: Forecast = this.forecast) =
        PayReminderPlanner.reminders(forecast, nowMinutes, fmt::string)

    @Test fun demoPaydaysGetOneReminderTheEveningBefore() {
        val reminders = plan()
        // Weekly pay on Wednesday: the next four fall within 28 days of Monday 23 Nov (the fifth is 30 days away).
        assertEquals(
            listOf("2026-11-25", "2026-12-02", "2026-12-09", "2026-12-16").map(LocalDate::parse),
            reminders.map { it.payday },
        )
        assertEquals(
            listOf("2026-11-24", "2026-12-01", "2026-12-08", "2026-12-15").map(LocalDate::parse),
            reminders.map { it.fireDate },
        )
        assertTrue(reminders.all { it.hour == 20 })
        assertEquals("beaver.payday-eve.2026-11-25", reminders.first().identifier)
    }

    @Test fun reminderTextCarriesExpectedPayAndBillsBeforeNextPayday() {
        val first = plan().first()
        val payday = LocalDate.parse("2026-11-25")
        val expected = forecast.payEvents.filter { it.payday == payday }.sumOf { it.net.cents }
        val bills = forecast.weeks.flatMap { it.bills }
            .filter { !it.isPaid && it.dueDate >= payday && it.dueDate < payday.plusDays(7) }
            .sumOf { it.plannedAmount.cents }
        assertEquals("Pay day tomorrow", first.title)
        assertEquals(
            "Pay day tomorrow: expected ${fmt.string(Money(expected))}. Bills due before next payday: ${fmt.string(Money(bills))}.",
            first.body,
        )
    }

    @Test fun aReminderWhoseTimeHasPassedTodayIsSkipped() {
        val tuesday = LocalDate.parse("2026-11-24") // payday tomorrow: fires today at 20:00
        val input = DemoData.forecastInput(tuesday)
        val f = Forecast.compute(input)
        assertEquals(tuesday, PayReminderPlanner.reminders(f, 19 * 60 + 59, fmt::string).first().fireDate)
        assertEquals(LocalDate.parse("2026-12-01"), PayReminderPlanner.reminders(f, 20 * 60, fmt::string).first().fireDate)
    }

    @Test fun aPaydayTodayHasNoReminder() {
        val wednesday = Forecast.compute(DemoData.forecastInput(LocalDate.parse("2026-11-25")))
        assertFalse(LocalDate.parse("2026-11-25") in PayReminderPlanner.reminders(wednesday, 0, fmt::string).map { it.payday })
    }

    @Test fun reminderFiresAtEightPmInTheGivenZone() {
        val r = plan().first()
        assertEquals(Instant.parse("2026-11-24T09:00:00Z"), r.fireInstant(ZoneId.of("Australia/Sydney")))
        assertEquals(Instant.parse("2026-11-24T20:00:00Z"), r.fireInstant(ZoneId.of("UTC")))
    }

    // region Scheduler

    private class FakeBackend(var allowed: Boolean = true) : ReminderBackend {
        var replaced: List<PayReminder>? = null
        var zone: ZoneId? = null
        var cancelled = 0
        override fun replace(reminders: List<PayReminder>, zone: ZoneId, now: Instant) {
            replaced = reminders
            this.zone = zone
        }

        override fun cancelAll() {
            cancelled++
        }

        override fun canNotify() = allowed
    }

    private lateinit var prefs: ReminderPrefs

    @Before fun freshPrefs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(ReminderPrefs.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = ReminderPrefs.of(context)
    }

    private val settings = AppSettings(currency = "AUD", timeZoneId = "Australia/Sydney")
    private fun scheduler(backend: ReminderBackend) =
        PayReminderScheduler(prefs, backend, clock = { Instant.parse("2026-11-22T21:00:00Z") }) // Mon 08:00 Sydney

    @Test fun theReminderIsOffByDefaultAndSchedulesNothing() {
        val backend = FakeBackend()
        val s = scheduler(backend)
        assertFalse(s.isEnabled)
        s.reschedule(forecast, settings)
        assertEquals(null, backend.replaced)
    }

    @Test fun enablingSchedulesAndDisablingCancels() {
        val backend = FakeBackend()
        val s = scheduler(backend)
        s.enable(forecast, settings)
        assertTrue(s.isEnabled)
        assertTrue(prefs.enabled) // kept in SharedPreferences
        assertEquals(4, backend.replaced!!.size)
        assertEquals(ZoneId.of("Australia/Sydney"), backend.zone)

        s.disable()
        assertFalse(s.isEnabled)
        assertEquals(1, backend.cancelled)
        backend.replaced = null
        s.reschedule(forecast, settings)
        assertEquals(null, backend.replaced)
    }

    @Test fun reschedulingFollowsAForecastChange() {
        val backend = FakeBackend()
        val s = scheduler(backend)
        s.enable(forecast, settings)
        val noIncome = Forecast.compute(DemoData.forecastInput(today).copy(sources = emptyList()))
        s.reschedule(noIncome, settings)
        assertEquals(emptyList<PayReminder>(), backend.replaced)
    }

    @Test fun withoutNotificationPermissionNothingIsScheduled() {
        val backend = FakeBackend(allowed = false)
        val s = scheduler(backend)
        s.enable(forecast, settings)
        assertEquals(null, backend.replaced)
    }

    // endregion
}

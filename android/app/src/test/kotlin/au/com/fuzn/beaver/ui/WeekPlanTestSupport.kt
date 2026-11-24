package au.com.fuzn.beaver.ui

import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.data.AppStore
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.data.TestDb
import au.com.fuzn.beaver.ui.plan.PlanModel
import au.com.fuzn.beaver.ui.week.WeekModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

val US: Locale = Locale.US

/** Mon 2026-11-23, the demo data's reference day. */
val TODAY: LocalDate = DemoData.referenceToday

fun day(s: String): LocalDate = LocalDate.parse(s)

/** The demo input for a given "today", run through the engine directly (no database). */
fun demoModel(
    today: LocalDate = TODAY,
    now: Instant = Instant.now(),
    limits: List<FortnightLimit> = emptyList(),
    edit: (ForecastInput) -> ForecastInput = { it },
): WeekModel {
    val input = edit(DemoData.forecastInput(today))
    return WeekModel(Forecast.compute(input), input, AppSettings(currency = "AUD"), limits, now, US)
}

fun shifts(count: Int, hours: Long = 8): List<Shift> = (0 until count).map { i ->
    Shift(sourceID = 1, date = TODAY.plusDays(i.toLong()), start = LocalTime.of(8, 0), end = LocalTime.of(8, 0).plusHours(hours))
}

/** A real in-memory repository loaded with the demo data and a store pinned to the reference day. */
class DemoStore : AutoCloseable {
    private val db = TestDb()
    val repository: Repository = db.repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store: AppStore

    init {
        store = runBlocking {
            repository.loadDemoData()
            AppStore.create(repository, scope) { TODAY }
        }
    }

    fun weekModel() = WeekModel(store.forecast.value, store.input.value, store.settings.value, emptyList(), Instant.now(), US)
    fun planModel() = PlanModel(store.forecast.value, store.settings.value, US)

    override fun close() {
        scope.cancel()
        db.close()
    }
}

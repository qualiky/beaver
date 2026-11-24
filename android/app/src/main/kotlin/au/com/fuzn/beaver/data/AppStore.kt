package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.LocalDate
import java.time.ZoneId

/**
 * The live state the UI reads: settings, the engine input and the computed forecast, as `StateFlow`s.
 * It recomputes whenever the database changes. The UI never touches Room.
 *
 * Create it with [create], which reads the current state first so the first frame is correct
 * (no onboarding flash), then follows the database in [scope] until the scope is cancelled.
 */
class AppStore private constructor(
    private val repository: Repository,
    private val clock: (ZoneId) -> LocalDate,
    initialSettings: AppSettings,
    initialInput: ForecastInput,
) {
    private val _settings = MutableStateFlow(initialSettings)
    private val _input = MutableStateFlow(initialInput)
    private val _extraWeeklyIncome = MutableStateFlow(Money.ZERO)
    private val _forecast = MutableStateFlow(Forecast.compute(initialInput))
    private val _lastError = MutableStateFlow<String?>(null)
    private var job: Job? = null

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val input: StateFlow<ForecastInput> = _input.asStateFlow()
    val forecast: StateFlow<Forecast> = _forecast.asStateFlow()

    /** The last error from observing or loading, for a banner. Null when healthy. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** What-if: extra net income added to every forecast week. Not saved. */
    val extraWeeklyIncome: StateFlow<Money> = _extraWeeklyIncome.asStateFlow()

    fun setExtraWeeklyIncome(amount: Money) {
        if (_extraWeeklyIncome.value == amount) return
        _extraWeeklyIncome.value = amount
        recompute()
    }

    /** Synchronous-style refresh from the database (after writes in tests, and as a fallback). */
    suspend fun reload() {
        val settings = repository.settings()
        apply(StoreSnapshot(settings, repository.forecastInput(clock(settings.timeZone))))
    }

    /** Call when the app returns to the foreground so a new day moves the forecast window. */
    fun refreshToday() {
        val today = clock(_settings.value.timeZone)
        if (today == _input.value.today) return
        _input.value = _input.value.copy(today = today)
        recompute()
    }

    private fun start(scope: CoroutineScope) {
        job = repository.observeSnapshot(clock)
            .onEach { apply(it) }
            .catch { e -> if (e !is CancellationException) _lastError.value = e.message ?: e.toString() }
            .launchIn(scope)
    }

    private fun apply(snapshot: StoreSnapshot) {
        _settings.value = snapshot.settings
        _input.value = snapshot.input
        _lastError.value = null
        recompute()
    }

    private fun recompute() {
        _forecast.value = Forecast.compute(_input.value.copy(extraWeeklyIncome = _extraWeeklyIncome.value))
    }

    companion object {
        /** Reads the current state, then keeps it up to date in [scope]. */
        suspend fun create(
            repository: Repository,
            scope: CoroutineScope,
            clock: (ZoneId) -> LocalDate = { LocalDate.now(it) },
        ): AppStore {
            val settings = runCatching { repository.settings() }.getOrDefault(AppSettings())
            val today = clock(settings.timeZone)
            val input = runCatching { repository.forecastInput(today) }.getOrDefault(ForecastInput(today))
            return AppStore(repository, clock, settings, input).also { it.start(scope) }
        }
    }
}

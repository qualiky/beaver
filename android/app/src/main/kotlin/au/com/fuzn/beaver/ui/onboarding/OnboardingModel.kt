package au.com.fuzn.beaver.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.Backup
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.editors.MoneyParser
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/** First launch, or opened again from Settings (shows existing data, never changes `onboardingDone`, dismisses). */
enum class OnboardingMode { FirstRun, Rerun }

enum class OnboardingStep(val title: String) {
    Welcome("Welcome"), Income("Income"), Bills("Bills"), Living("Living money"), Goal("Goal"), Limits("Limits"),
    Summary("Summary");

    /** Steps 2 to 6 can be skipped. */
    val canSkip: Boolean get() = this != Welcome && this != Summary
}

/** Which editor is open over the flow. It has a string [key] so it survives rotation and process death. */
sealed interface OnboardingSheet {
    data class NewIncome(val kind: IncomeKind) : OnboardingSheet
    data class EditIncome(val id: Int) : OnboardingSheet
    data class UsualWeek(val sourceId: Int) : OnboardingSheet

    /** [presetName] null is "Add custom". */
    data class NewBill(val presetName: String?) : OnboardingSheet
    data class EditBill(val id: Int) : OnboardingSheet

    /** [id] null is a new goal. */
    data class EditGoal(val id: Int?) : OnboardingSheet
    data object Limits : OnboardingSheet

    val key: String
        get() = when (this) {
            is NewIncome -> "new-income:${kind.name}"
            is EditIncome -> "edit-income:$id"
            is UsualWeek -> "usual-week:$sourceId"
            is NewBill -> "new-bill:${presetName.orEmpty()}"
            is EditBill -> "edit-bill:$id"
            is EditGoal -> "goal:${id ?: ""}"
            Limits -> "limits"
        }

    companion object {
        fun parse(key: String): OnboardingSheet? {
            val head = key.substringBefore(':')
            val arg = key.substringAfter(':', "")
            return when (head) {
                "new-income" -> runCatching { NewIncome(IncomeKind.valueOf(arg)) }.getOrNull()
                "edit-income" -> arg.toIntOrNull()?.let(::EditIncome)
                "usual-week" -> arg.toIntOrNull()?.let(::UsualWeek)
                "new-bill" -> NewBill(arg.ifEmpty { null })
                "edit-bill" -> arg.toIntOrNull()?.let(::EditBill)
                "goal" -> EditGoal(arg.toIntOrNull())
                "limits" -> Limits
                else -> null
            }
        }
    }
}

/** What the last step shows. */
data class OnboardingSummary(
    val headline: String,
    val detail: String?,
    val weeklyIncome: Money,
    val weeklyBills: Money,
    val weeklyLiving: Money,
    val weeklySurplus: Money,
    val requiredPerPayday: Money?,
    val hasGoal: Boolean,
    val isEmpty: Boolean,
)

/**
 * The step logic for onboarding; no views. Editors save themselves through the repository, so this model reloads
 * what they wrote, tracks the step, and writes only currency, time zone, the living budget and the finish flag.
 * The step, typed fields and open editor are plain snapshot state, saved through [saver] so rotation and process
 * death keep them; everything else is re-read from the database.
 */
class OnboardingModel(
    private val repository: Repository,
    private val backup: Backup,
    val mode: OnboardingMode = OnboardingMode.FirstRun,
    deviceCurrency: String? = defaultDeviceCurrency(),
    deviceZone: ZoneId = ZoneId.systemDefault(),
    private val clock: (ZoneId) -> LocalDate = { LocalDate.now(it) },
    private val locale: Locale = Locale.getDefault(),
) {
    var step by mutableStateOf(OnboardingStep.Welcome)
        private set

    // Welcome
    var currency by mutableStateOf(deviceCurrency?.uppercase(Locale.ROOT) ?: "AUD")
        private set
    var timeZoneId by mutableStateOf(deviceZone.id)
        private set
    var restoreError by mutableStateOf<String?>(null)

    // Loaded data
    var sources by mutableStateOf(emptyList<IncomeSource>())
        private set
    var bills by mutableStateOf(emptyList<Bill>())
        private set
    var goals by mutableStateOf(emptyList<Goal>())
        private set
    var limitsSummary by mutableStateOf<String?>(null)
        private set
    var input by mutableStateOf<ForecastInput?>(null)
        private set
    var livingText by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)

    /** The editor open now, if any. */
    var sheet by mutableStateOf<OnboardingSheet?>(null)

    /** Set after an hourly source is saved: the source id to offer "Set your usual week" for. */
    var usualWeekPrompt by mutableStateOf<Int?>(null)

    /** True once the first [refresh] has finished, so editors that look items up by id can resolve them. */
    var loaded by mutableStateOf(false)
        private set

    /** Set when onboarding finishes (the screen then calls its `onFinished`). */
    var isFinished by mutableStateOf(false)
        private set

    private var fieldsRestored = false
    private var storedLiving = Money.ZERO

    val formatter: FigureFormatter get() = FigureFormatter(currency, locale)

    // region Navigation

    val isFirst: Boolean get() = step == OnboardingStep.Welcome
    val isLast: Boolean get() = step == OnboardingStep.Summary
    val stepNumber: Int get() = step.ordinal + 1
    val stepCount: Int get() = OnboardingStep.entries.size
    val progress: Float get() = stepNumber.toFloat() / stepCount
    val canSkip: Boolean get() = step.canSkip

    /** Whether Next is allowed now. Only a typed but invalid living budget blocks it. */
    val canAdvance: Boolean get() = step != OnboardingStep.Living || livingIssue == null

    /** Commits this step's own fields, then moves on. Returns false when it stayed (invalid input or a write error). */
    suspend fun next(): Boolean {
        if (isLast || !canAdvance) return false
        if (!commitCurrentStep()) return false
        move(+1)
        return true
    }

    /** Moves on without committing this step's typed fields: Skip never writes. Only steps 2 to 6. */
    suspend fun skip(): Boolean {
        if (!canSkip) return false
        move(+1)
        return true
    }

    suspend fun back() {
        if (isFirst) return
        move(-1)
    }

    private suspend fun move(delta: Int) {
        step = OnboardingStep.entries[step.ordinal + delta]
        refresh()
    }

    private suspend fun commitCurrentStep(): Boolean = when (step) {
        OnboardingStep.Welcome -> commitWelcome()
        OnboardingStep.Living -> commitLiving()
        else -> true
    }

    // endregion

    // region Welcome

    /** Currency choices: the common list plus the current one. */
    val currencyChoices: List<String>
        get() = if (currency in COMMON_CURRENCIES) COMMON_CURRENCIES else listOf(currency) + COMMON_CURRENCIES

    suspend fun setCurrency(code: String) {
        currency = code
        write { repository.setCurrency(code) }
        refresh()
    }

    suspend fun setTimeZone(id: String) {
        timeZoneId = id
        write { repository.setTimeZone(id) }
        refresh()
    }

    private suspend fun commitWelcome(): Boolean {
        val ok = write {
            repository.setCurrency(currency)
            repository.setTimeZone(timeZoneId)
        }
        refresh()
        return ok
    }

    // endregion

    // region Restore

    /**
     * Replaces everything with the backup text. On success onboarding is complete (also when the file was exported
     * part-way through setup); on failure the database is untouched (the import is one transaction) and
     * [restoreError] says why.
     */
    suspend fun restore(json: String): Boolean {
        restoreError = null
        return try {
            backup.import(json)
            // A backup taken part-way through onboarding may not carry the flag; a re-run was already done.
            repository.setOnboardingDone(true)
            finishNow()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restoreError = e.message ?: "The backup could not be restored."
            false
        }
    }

    // endregion

    // region Income and bills

    /** Deletes a source. Its rate card goes too when no other source uses it (as the editor does). */
    suspend fun deleteSource(source: IncomeSource) {
        write {
            repository.deleteIncomeSource(source.id)
            val cardId = source.rateCardID
            if (cardId != null && repository.incomeSources().none { it.rateCardID == cardId }) {
                repository.deleteRateCard(cardId)
            }
        }
        refresh()
    }

    suspend fun deleteBill(bill: Bill) {
        write { repository.deleteBill(bill.id) }
        refresh()
    }

    val weeklyBills: Money get() = WeeklyEquivalent.bills(bills)
    val weeklyIncome: Money get() = input?.let(WeeklyEquivalent::income) ?: Money.ZERO
    val hasIncome: Boolean get() = sources.any { it.isActive }

    // endregion

    // region Living money

    val livingIssue: String?
        get() {
            val t = livingText.trim()
            if (t.isEmpty()) return null
            return if (MoneyParser.cents(t) == null) "Enter an amount like 150 or 150.50." else null
        }

    /** The typed amount, or zero when empty or invalid. */
    val livingBudget: Money get() = Money(MoneyParser.cents(livingText) ?: 0L)

    /** Saves the weekly budget. Empty text leaves the stored value alone. */
    private suspend fun commitLiving(): Boolean {
        if (livingIssue != null) return false
        if (livingText.trim().isEmpty()) return true
        val ok = write { repository.setLivingBudget(livingBudget) }
        refresh()
        return ok
    }

    // endregion

    // region Data

    /**
     * Loads stored values on first use (the stored living budget always; currency and time zone only in a re-run),
     * unless a saved state already carries what the user typed.
     */
    suspend fun load() {
        val stored = runCatching { repository.settings() }.getOrNull()
        if (stored != null && !fieldsRestored) {
            if (mode == OnboardingMode.Rerun) {
                currency = stored.currency
                timeZoneId = stored.timeZoneId
            }
            livingText = if (stored.livingBudget.cents > 0) MoneyParser.text(stored.livingBudget.cents) else ""
        }
        refresh()
    }

    /** Re-reads what the editors wrote and refreshes the forecast. */
    suspend fun refresh() {
        try {
            val settings = repository.settings()
            input = repository.forecastInput(clock(settings.timeZone))
            sources = repository.incomeSources()
            bills = repository.bills()
            goals = repository.goals()
            val holidays = repository.holidays().size
            val ranges = repository.unavailableRanges().size
            val limit = repository.hoursLimits().lastOrNull()
            val parts = buildList {
                if (limit != null) add("${limit.hoursPerFortnight} hours per fortnight")
                if (holidays > 0) add(if (holidays == 1) "1 public holiday" else "$holidays public holidays")
                if (ranges > 0) add(if (ranges == 1) "1 unavailable period" else "$ranges unavailable periods")
            }
            limitsSummary = parts.joinToString(", ").ifEmpty { null }
            storedLiving = settings.livingBudget
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Something went wrong."
        }
    }

    private suspend fun write(body: suspend () -> Unit): Boolean = try {
        body()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = e.message ?: "Something went wrong."
        false
    }

    // endregion

    // region Summary

    val summary: OnboardingSummary
        get() {
            val fmt = formatter
            val forecast = input?.let { Forecast.compute(it) }
            val income = weeklyIncome
            val billsWeekly = weeklyBills
            val living = storedLiving
            val surplus = income - billsWeekly - living
            val isEmpty = sources.isEmpty() && bills.isEmpty() && goals.isEmpty() && living.cents == 0L
            val goal = forecast?.goal
            if (forecast == null || goal == null) {
                val word = if (surplus.cents < 0) "Weekly shortfall" else "Weekly surplus"
                val amount = fmt.string(Money(Math.abs(surplus.cents)))
                return OnboardingSummary("$word ≈ $amount", null, income, billsWeekly, living, surplus, null, false, isEmpty)
            }
            val gap = forecast.gap
            val amount = fmt.string(Money(Math.abs(gap.cents)))
            val by = forecast.deadline?.let { DateText.full(it, locale) }
            val headline = when {
                gap.cents == 0L -> "Right on target"
                gap.cents > 0 -> "$amount ahead"
                else -> "$amount behind" + (by?.let { " by $it" } ?: "")
            }
            val detail = "Projected ${fmt.string(forecast.projectedAtDeadline)} of ${fmt.string(goal.target)}" +
                if (gap.cents >= 0) (by?.let { " by $it" } ?: "") else ""
            return OnboardingSummary(headline, detail, income, billsWeekly, living, surplus, forecast.requiredPerPayday, true, isEmpty)
        }

    // endregion

    // region Finish

    /**
     * First run: marks onboarding done in the database (the app then shows the tabs). Re-run: leaves the flag
     * alone and just finishes so the caller can dismiss. Returns false when the write failed.
     */
    suspend fun finish(): Boolean {
        if (mode == OnboardingMode.FirstRun && !write { repository.setOnboardingDone(true) }) return false
        finishNow()
        return true
    }

    private suspend fun finishNow() {
        refresh()
        isFinished = true
    }

    // endregion

    private fun restoreFrom(saved: List<Any>) {
        fieldsRestored = true
        step = OnboardingStep.entries.getOrElse(saved[0] as Int) { OnboardingStep.Welcome }
        currency = saved[1] as String
        timeZoneId = saved[2] as String
        livingText = saved[3] as String
        sheet = (saved[4] as String).takeIf { it.isNotEmpty() }?.let(OnboardingSheet::parse)
        usualWeekPrompt = (saved[5] as Int).takeIf { it >= 0 }
    }

    companion object {
        /** Currencies offered in the picker. The current one is added when it is not here. */
        val COMMON_CURRENCIES = listOf("AUD", "USD", "EUR", "GBP", "NZD", "CAD", "SGD", "HKD", "JPY", "CHF", "INR", "ZAR")

        /** The device locale's currency, or null when the locale has none. */
        fun defaultDeviceCurrency(locale: Locale = Locale.getDefault()): String? =
            runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()

        /** Keeps the step, typed fields and open editor across rotation and process death. */
        fun saver(create: () -> OnboardingModel): Saver<OnboardingModel, Any> = listSaver(
            save = { m ->
                listOf<Any>(m.step.ordinal, m.currency, m.timeZoneId, m.livingText, m.sheet?.key.orEmpty(), m.usualWeekPrompt ?: -1)
            },
            restore = { saved -> create().also { it.restoreFrom(saved) } },
        )
    }
}

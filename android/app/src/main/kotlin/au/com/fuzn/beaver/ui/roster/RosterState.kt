package au.com.fuzn.beaver.ui.roster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.mondayOfWeek
import au.com.fuzn.beaver.data.Repository
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** A one-line message for the Snackbar. Undoable messages (delete) also carry an Undo action. */
data class RosterMessage(val text: String, val undoable: Boolean = false, val serial: Long = 0)

/**
 * The Roster screen's state and actions. All reads and writes go through [Repository]; all calculations live in
 * [RosterData]. Functions are `suspend`, so the screen calls them from a coroutine scope and tests call them
 * with `runBlocking`. State is Compose snapshot state, readable on the JVM without a UI.
 */
class RosterState(
    private val repository: Repository,
    today: LocalDate,
    weekStart: LocalDate? = null,
    currency: String = "AUD",
) {
    var currency by mutableStateOf(currency)

    /** True once the first load has finished (so the screen does not flash an empty state). */
    var loaded by mutableStateOf(false); private set

    var data by mutableStateOf(RosterData(weekStart = (weekStart ?: today).mondayOfWeek, today = today)); private set

    /** Logged pays with their expected net, newest first. */
    var payRows by mutableStateOf<List<PayRow>>(emptyList()); private set

    var message by mutableStateOf<RosterMessage?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set

    private var lastDeleted: Shift? = null
    private var messageSerial = 0L

    // region Loading and week navigation

    /** Reads everything for [week] (default: the week shown) and publishes it in one step. */
    suspend fun reload(week: LocalDate = data.weekStart, today: LocalDate = data.today) {
        guard {
            val monday = week.mondayOfWeek
            val shifts = repository.shifts(DateRange(monday.minusDays(14), monday.plusDays(20)))
            val next = RosterData(
                weekStart = monday, today = today,
                sources = repository.incomeSources(), rateCards = repository.rateCards(),
                templates = repository.templates(), holidays = repository.holidays(),
                unavailable = repository.unavailableRanges(), limits = repository.hoursLimits(),
                tables = repository.withholdingTables(), pays = repository.paysReceived(), shifts = shifts,
            )
            val rows = recentPayRows(next)
            data = next
            payRows = rows
            errorMessage = null
        }
        loaded = true
    }

    private suspend fun recentPayRows(d: RosterData): List<PayRow> = d.recentPays.take(10).map { pay ->
        val source = d.source(pay.sourceID)
        PayRow(pay, source?.name ?: "Unknown source", source?.let { expectedNet(d, it, pay.payDate) })
    }

    suspend fun goToPreviousWeek() = reload(data.weekStart.minusDays(7))
    suspend fun goToNextWeek() = reload(data.weekStart.plusDays(7))
    suspend fun goToThisWeek() = reload(data.today.mondayOfWeek)

    // endregion

    // region Add, edit, delete

    /** The last shift entered for each source (highest id), used as defaults for new shifts. */
    private suspend fun lastShiftsBySource(): Map<Int, Shift> {
        val out = HashMap<Int, Shift>()
        for (s in repository.shifts()) if (s.id >= (out[s.sourceID]?.id ?: 0)) out[s.sourceID] = s
        return out
    }

    /** A new or edit form, or null when there is no hourly source to add a shift to. */
    suspend fun makeEditor(editing: Shift? = null): ShiftEditorState? {
        val d = data
        if (d.hourlySources.isEmpty()) return null
        val defaultDate = if (d.today in d.week) d.today else d.weekStart
        return ShiftEditorState(editing, defaultDate, d, lastShiftsBySource())
    }

    suspend fun save(shift: Shift): Boolean = guard {
        repository.save(shift)
        reload()
    }

    suspend fun setStatus(status: ShiftStatus, shift: Shift) = save(shift.copy(status = status))

    /** Deletes a shift and shows a message with an Undo action. */
    suspend fun delete(shift: Shift) {
        guard {
            repository.deleteShift(shift.id)
            lastDeleted = shift
            show("Shift deleted", undoable = true)
            reload()
        }
    }

    suspend fun undoDelete() {
        val shift = lastDeleted ?: return
        lastDeleted = null
        message = null
        save(shift)
    }

    /** Called when a message has been shown (or timed out). */
    fun dismissMessage(serial: Long? = null) {
        if (serial != null && message?.serial != serial) return
        message = null
        lastDeleted = null
    }

    fun clearError() { errorMessage = null }

    fun show(text: String, undoable: Boolean = false) {
        message = RosterMessage(text, undoable, ++messageSerial)
    }

    // endregion

    // region Copy last week, apply template, save as template

    suspend fun apply(plan: BulkPlan): Int {
        if (plan.toAdd.isEmpty()) return 0
        return if (guard {
                repository.saveShifts(plan.toAdd)
                reload()
            }) plan.toAdd.size else 0
    }

    suspend fun copyLastWeek() {
        val plan = data.copyLastWeekPlan()
        if (!plan.hasSource) { show("Nothing to copy: last week has no shifts."); return }
        val added = apply(plan)
        show(RosterData.summary(added, plan, "Copied"))
    }

    suspend fun applyTemplate() {
        val plan = data.applyTemplatePlan()
        if (!plan.hasSource) { show("No template yet. Use Save week as template first."); return }
        val added = apply(plan)
        show(RosterData.summary(added, plan, "Added"))
    }

    /**
     * Saves the non-cancelled shifts of the week shown as each source's template, effective from this Monday.
     * A template already saved for the same source and Monday is replaced rather than duplicated.
     * Returns the number of shifts saved.
     */
    suspend fun saveWeekAsTemplate(): Int {
        var saved = 0
        val ok = guard {
            val d = data
            for (source in d.hourlySources) {
                val rows = d.templateRows(source.id)
                if (rows.isEmpty()) continue
                val existing = d.templates.firstOrNull { it.sourceID == source.id && it.effectiveFrom == d.weekStart }
                repository.save(
                    Template(existing?.id ?: 0, source.id, "Week of ${RosterFormat.shortDate(d.weekStart)}", d.weekStart, rows),
                )
                saved += rows.size
            }
            reload()
        }
        return if (ok) saved else 0
    }

    suspend fun saveTemplateAction() {
        val count = saveWeekAsTemplate()
        if (count == 0) show("Nothing to save: this week has no shifts.")
        else show("Saved $count ${if (count == 1) "shift" else "shifts"} as the template from ${RosterFormat.shortDate(data.weekStart)}.")
    }

    // endregion

    // region Expected and logged pay

    private suspend fun expectedNet(d: RosterData, source: IncomeSource, payday: LocalDate): Money {
        val period = source.schedule.workPeriod(payday)
        val shifts = if (period != null && source.kind == au.com.fuzn.beaver.core.IncomeKind.hourly) {
            repository.shifts(period, source.id)
        } else emptyList()
        return d.expectedNet(source, payday, shifts)
    }

    /** Expected net for a payday (see [RosterData.expectedNet]). */
    suspend fun expectedNet(source: IncomeSource, payday: LocalDate): Money = expectedNet(data, source, payday)

    /** Saves a logged pay. If one already exists for the same source and payday it is updated, not duplicated. */
    suspend fun logPay(
        sourceId: Int, payDate: LocalDate, net: Money, gross: Money?, tax: Money?, note: String,
    ): Boolean = guard {
        val existing = data.pays.firstOrNull { it.sourceID == sourceId && it.payDate == payDate }
        repository.save(PayReceived(existing?.id ?: 0, sourceId, payDate, net, gross, tax, note))
        reload()
    }

    suspend fun savePay(pay: PayReceived): Boolean = logPay(pay.sourceID, pay.payDate, pay.net, pay.gross, pay.tax, pay.note)

    suspend fun deletePay(pay: PayReceived) {
        guard {
            repository.deletePayReceived(pay.id)
            reload()
        }
    }

    // endregion

    private suspend fun guard(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = e.message ?: e.toString()
        false
    }
}

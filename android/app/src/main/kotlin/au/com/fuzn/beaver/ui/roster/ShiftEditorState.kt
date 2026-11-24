package au.com.fuzn.beaver.ui.roster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftPay
import au.com.fuzn.beaver.core.ShiftPayResult
import au.com.fuzn.beaver.core.ShiftStatus
import java.time.LocalDate
import java.time.LocalTime

/** Form state for adding or editing one shift, with a live pay preview. Plain Kotlin, so it runs in JVM tests. */
class ShiftEditorState(
    existing: Shift?,
    defaultDate: LocalDate,
    private val data: RosterData,
    private val lastShifts: Map<Int, Shift>,
) {
    val existingId: Int? = existing?.id?.takeIf { it != 0 }
    val sources: List<IncomeSource> = data.hourlySources
    private val rateCards: List<RateCard> = data.rateCards

    var sourceId by mutableStateOf(existing?.sourceID ?: sources.firstOrNull()?.id ?: 0); private set
    var date by mutableStateOf(existing?.date ?: defaultDate)
    var start by mutableStateOf(existing?.start ?: lastShifts[sourceId]?.start ?: LocalTime.of(9, 0))
    var end by mutableStateOf(existing?.end ?: lastShifts[sourceId]?.end ?: LocalTime.of(17, 0))
    var breakMinutes by mutableStateOf(existing?.breakMinutes ?: lastShifts[sourceId]?.breakMinutes ?: 0)
    var status by mutableStateOf(existing?.status ?: ShiftStatus.rostered)
    var note by mutableStateOf(existing?.note ?: "")

    /** True while the custom break stepper is showing. */
    var customBreak by mutableStateOf(breakMinutes !in BREAK_PRESETS)

    /** Changes the source. When adding, start, end and break follow the last shift entered for that source. */
    fun selectSource(id: Int) {
        if (id == sourceId) return
        sourceId = id
        if (existingId == null) lastShifts[id]?.let {
            start = it.start
            end = it.end
            breakMinutes = it.breakMinutes
            customBreak = it.breakMinutes !in BREAK_PRESETS
        }
    }

    fun selectBreak(minutes: Int) {
        customBreak = false
        breakMinutes = minutes
    }

    fun selectCustomBreak() { customBreak = true }

    fun adjustBreak(minutes: Int) { breakMinutes = minutes.coerceIn(0, 480) }

    val shift: Shift
        get() = Shift(
            id = existingId ?: 0, sourceID = sourceId, date = date, start = start, end = end,
            breakMinutes = breakMinutes, status = status, note = note.trim(),
        )

    val selectedSource: IncomeSource? get() = sources.firstOrNull { it.id == sourceId }

    val rateCard: RateCard?
        get() = selectedSource?.rateCardID?.let { id -> rateCards.firstOrNull { it.id == id } }

    val pay: ShiftPayResult? get() = rateCard?.let { ShiftPay.calculate(shift, it, data.holidayDates) }

    /** Gross counted for the shift as it stands: nothing when cancelled. */
    val gross: Money get() = if (status == ShiftStatus.cancelled) Money.ZERO else (pay?.gross ?: Money.ZERO)

    val finishesNextDay: Boolean get() = shift.crossesMidnight

    /** Public holiday names on the start date, and on the next day when the shift crosses midnight. */
    val holidayNames: List<String>
        get() {
            val names = mutableListOf<String>()
            for (d in listOf(date) + if (finishesNextDay) listOf(date.plusDays(1)) else emptyList()) {
                data.holidayName(d)?.let { if (it !in names) names.add(it) }
            }
            return names
        }

    val durationText: String get() = RosterFormat.hoursAndMinutes(shift.durationMinutes)

    companion object {
        val BREAK_PRESETS = listOf(0, 15, 30, 45, 60)
    }
}

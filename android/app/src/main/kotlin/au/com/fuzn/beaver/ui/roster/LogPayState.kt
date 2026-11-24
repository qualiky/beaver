package au.com.fuzn.beaver.ui.roster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayReceived
import java.time.LocalDate

/**
 * Form state for logging pay received. [data] is replaced by the screen when the roster reloads, so the
 * "already logged" check and the recent list stay current. [expected] is filled by [RosterState.expectedNet].
 */
class LogPayState(data: RosterData) {
    var data by mutableStateOf(data)

    var sourceId by mutableStateOf(0); private set
    var payday by mutableStateOf(data.today); private set
    var netText by mutableStateOf("")
    var grossText by mutableStateOf("")
    var taxText by mutableStateOf("")
    var note by mutableStateOf("")

    /** The net we expect for the chosen source and payday. Null until worked out. */
    var expected by mutableStateOf<Money?>(null)

    init {
        val first = data.hourlySources.firstOrNull() ?: data.sourcesForPay.firstOrNull()
        sourceId = first?.id ?: 0
        payday = first?.let { defaultPayday(it) } ?: data.today
        loadExisting()
    }

    val source: IncomeSource? get() = data.source(sourceId)

    private fun defaultPayday(source: IncomeSource): LocalDate {
        val list = data.paydays(source)
        return list.firstOrNull { it <= data.today } ?: list.firstOrNull() ?: data.today
    }

    /** Paydays to pick from, newest first (the chosen one is always included). */
    val paydays: List<LocalDate>
        get() {
            val list = source?.let { data.paydays(it) }.orEmpty()
            return if (payday in list) list else listOf(payday) + list
        }

    val actualNet: Money? get() = RosterFormat.parseMoney(netText)

    /** Actual minus expected net, or null until an amount is entered. */
    val difference: Money?
        get() {
            val actual = actualNet ?: return null
            val exp = expected ?: return null
            return RosterData.difference(actual, exp)
        }

    /** The pay already logged for this source and payday, if any. Saving updates it. */
    val existing: PayReceived? get() = data.pays.firstOrNull { it.sourceID == sourceId && it.payDate == payday }

    val canSave: Boolean get() = source != null && actualNet != null

    fun selectSource(id: Int) {
        if (id == sourceId) return
        sourceId = id
        source?.let { payday = defaultPayday(it) }
        expected = null
        loadExisting()
    }

    fun selectPayday(date: LocalDate) {
        if (date == payday) return
        payday = date
        expected = null
        loadExisting()
    }

    /** Fills the fields from an already logged pay for this source and payday, or clears them. */
    fun loadExisting() {
        val pay = existing
        if (pay != null) load(pay) else {
            netText = ""; grossText = ""; taxText = ""; note = ""
        }
    }

    /** Loads a logged pay into the form (tapping a row in the recent list). */
    fun load(pay: PayReceived) {
        sourceId = pay.sourceID
        payday = pay.payDate
        expected = null
        netText = RosterFormat.editableText(pay.net)
        grossText = RosterFormat.editableText(pay.gross)
        taxText = RosterFormat.editableText(pay.tax)
        note = pay.note
    }

    /** Gross, tax and note are optional; an unreadable optional amount is treated as not entered. */
    fun toPay(): PayReceived? {
        val net = actualNet ?: return null
        return PayReceived(
            id = existing?.id ?: 0, sourceID = sourceId, payDate = payday, net = net,
            gross = RosterFormat.parseMoney(grossText), tax = RosterFormat.parseMoney(taxText), note = note.trim(),
        )
    }
}

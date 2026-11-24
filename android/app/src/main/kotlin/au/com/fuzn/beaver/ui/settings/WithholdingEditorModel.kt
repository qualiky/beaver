package au.com.fuzn.beaver.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import java.util.concurrent.atomic.AtomicLong

/**
 * One row of a custom withholding table as text. `belowX` is whole dollars (empty means no limit, last row only);
 * `a` and `b` are decimals with up to four places, stored as ten-thousandths.
 */
class WithholdingRowDraft(belowX: String = "", a: String = "0", b: String = "0") {
    val key: Long = nextKey.incrementAndGet()
    var belowX by mutableStateOf(belowX)
    var a by mutableStateOf(a)
    var b by mutableStateOf(b)

    /** The row, or null when any field is not valid. */
    val row: WithholdingRow?
        get() {
            val limit = belowX.trim()
            var below: Int? = null
            if (limit.isNotEmpty()) {
                if (!limit.all { it in '0'..'9' }) return null
                below = limit.toIntOrNull() ?: return null
            }
            val aValue = tenThousandths(a) ?: return null
            val bValue = tenThousandths(b) ?: return null
            return WithholdingRow(below, aValue, bValue)
        }

    companion object {
        private val nextKey = AtomicLong()

        fun of(row: WithholdingRow) = WithholdingRowDraft(
            row.belowDollars?.toString() ?: "", text(row.aTenThousandths), text(row.bTenThousandths),
        )

        /** 1600 becomes "0.16"; 578462 becomes "57.8462". */
        fun text(tenThousandths: Int): String {
            val whole = tenThousandths / 10_000
            val frac = (tenThousandths % 10_000).toString().padStart(4, '0').trimEnd('0')
            return if (frac.isEmpty()) "$whole" else "$whole.$frac"
        }

        /** "0.16" as 1600. Up to four decimals, no sign. */
        fun tenThousandths(text: String): Int? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val parts = t.split('.', limit = 2)
            val whole = parts[0]
            val fraction = parts.getOrElse(1) { "" }
            if (!whole.all { it in '0'..'9' } || whole.length > 8) return null
            if (fraction.length > 4 || !fraction.all { it in '0'..'9' }) return null
            if (whole.isEmpty() && fraction.isEmpty()) return null
            val padded = fraction.padEnd(4, '0')
            return (if (whole.isEmpty()) 0 else whole.toInt()) * 10_000 + padded.toInt()
        }
    }
}

/** Validation and building for the withholding row editor. */
class WithholdingEditorModel(val source: WithholdingTable) {
    var name by mutableStateOf(source.name)
    val drafts = mutableStateListOf<WithholdingRowDraft>().also { list ->
        source.rows.forEach { list += WithholdingRowDraft.of(it) }
    }

    val nameIssue: String? get() = if (name.isBlank()) "Enter a name." else null

    fun issue(draft: WithholdingRowDraft): String? =
        if (draft.row == null) "Use whole dollars for the limit (or leave it empty) and numbers like 0.16 for a and b." else null

    /** Rows must have rising limits, with at most one row (the last) without a limit. */
    val orderIssue: String?
        get() {
            val rows = drafts.mapNotNull { it.row }
            if (rows.size != drafts.size) return null
            if (rows.isEmpty()) return "Add at least one row."
            var last = Int.MIN_VALUE
            rows.forEachIndexed { i, row ->
                val below = row.belowDollars
                if (below != null) {
                    if (below <= last) return "Limits must increase from one row to the next."
                    last = below
                } else if (i != rows.size - 1) {
                    return "Only the last row can have no limit."
                }
            }
            return null
        }

    val isValid: Boolean
        get() = nameIssue == null && orderIssue == null && drafts.isNotEmpty() && drafts.all { it.row != null }

    fun addRow() {
        drafts += WithholdingRowDraft()
    }

    fun removeRow(draft: WithholdingRowDraft) {
        drafts.remove(draft)
    }

    /** The table to save, or null while invalid. */
    fun build(): WithholdingTable? =
        if (!isValid) null else source.copy(name = name.trim(), rows = drafts.mapNotNull { it.row })
}

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** How often a pay period occurs. Withholding tables are weekly by convention. */
@Serializable
enum class PayFrequency { weekly, fortnightly, monthly }

/**
 * One row of a withholding table: use it when the weekly `x` is below `belowDollars`.
 * Coefficients are exact integers in ten-thousandths (0.1600 is 1600), so tax is deterministic.
 */
@Serializable
data class WithholdingRow(
    /** Upper bound (exclusive) in whole dollars; null means no upper bound. */
    val belowDollars: Int?,
    val aTenThousandths: Int,
    val bTenThousandths: Int,
)

/** A coefficient table: `x = floor(grossDollars) + 0.99`, `tax = max(0, roundHalfUp(a*x - b))` in whole dollars. */
@Serializable
data class WithholdingTable(
    val id: String,
    val name: String,
    val rows: List<WithholdingRow>,
    val isBuiltin: Boolean = false,
    val note: String? = null,
) {
    /** Tax for one weekly-sized period, in cents (always whole dollars). */
    fun weeklyTax(gross: Money): Money {
        val dollars = maxOf(0L, gross.cents) / 100
        val row = rows.firstOrNull { it.belowDollars == null || dollars < it.belowDollars } ?: return Money.ZERO
        // a * x in 1e-6 dollars: a is 1e-4, x is in cents (1e-2). b is 1e-4 dollars, scaled by 100 to 1e-6.
        val x = dollars * 100 + 99
        val micro = row.aTenThousandths.toLong() * x - row.bTenThousandths.toLong() * 100
        val wholeDollars = maxOf(0L, IntMath.roundHalfUp(micro, 1_000_000L))
        return Money(wholeDollars * 100)
    }

    /**
     * Tax for a period of the given frequency. Fortnightly applies the table to half the gross and doubles it;
     * monthly converts to a weekly equivalent (x 12/52), applies the table, and scales back (x 52/12).
     */
    fun tax(gross: Money, frequency: PayFrequency): Money = when (frequency) {
        PayFrequency.weekly -> weeklyTax(gross)
        PayFrequency.fortnightly -> Money(weeklyTax(Money(gross.cents / 2)).cents * 2)
        PayFrequency.monthly -> {
            val weekly = Money(IntMath.roundHalfUp(gross.cents * 12, 52L))
            Money(IntMath.roundHalfUp(weeklyTax(weekly).cents * 52, 12L))
        }
    }

    companion object {
        /** Identifier of the built-in AU Scale 2 table. */
        const val AU_SCALE2_WEEKLY_ID = "au-scale2-weekly"

        /** "AU ATO Schedule 1 - Scale 2 (weekly, tax-free threshold claimed)". Verify against the current ATO schedule. */
        val auScale2Weekly = WithholdingTable(
            id = AU_SCALE2_WEEKLY_ID,
            name = "AU ATO Schedule 1 – Scale 2 (weekly, tax-free threshold claimed)",
            rows = listOf(
                WithholdingRow(361, 0, 0),
                WithholdingRow(500, 1600, 578_462),
                WithholdingRow(625, 2600, 1_078_462),
                WithholdingRow(721, 1800, 578_462),
                WithholdingRow(865, 1790, 608_377),
                WithholdingRow(1282, 3227, 1_800_385),
                WithholdingRow(2596, 3200, 1_765_769),
                WithholdingRow(3653, 3900, 3_583_077),
                WithholdingRow(null, 4700, 6_506_154),
            ),
            isBuiltin = true,
            note = "Verify against the current ATO schedule before relying on it.",
        )

        /** All tables shipped with the app. */
        val builtIn: List<WithholdingTable> = listOf(auScale2Weekly)
    }
}

/** How tax is taken from gross pay in each pay period. */
@Serializable(with = TaxMethodSerializer::class)
sealed interface TaxMethod {
    /** The amount is already net. */
    data object None : TaxMethod

    /** A flat percentage in basis points (1000 is 10%), rounded half up to the cent. */
    data class FlatPercent(val basisPoints: Int) : TaxMethod

    /** A coefficient table, by id (custom tables first, then built-ins). */
    data class Table(val id: String) : TaxMethod

    /** Tax on a period's gross. A missing table yields zero tax. */
    fun tax(gross: Money, frequency: PayFrequency, tables: List<WithholdingTable> = emptyList()): Money = when (this) {
        None -> Money.ZERO
        is FlatPercent -> Money(IntMath.roundHalfUp(maxOf(0L, gross.cents) * basisPoints, 10_000L))
        is Table -> {
            val table = (tables + WithholdingTable.builtIn).firstOrNull { it.id == id }
            table?.tax(gross, frequency) ?: Money.ZERO
        }
    }
}

/** Swift shape: `{"none":{}}`, `{"flatPercent":{"basisPoints":1000}}`, `{"withholdingTable":{"id":"..."}}`. */
internal object TaxMethodSerializer : SwiftEnumSerializer<TaxMethod>("TaxMethod") {
    override fun encodeCase(value: TaxMethod): Pair<String, JsonObject> = when (value) {
        TaxMethod.None -> "none" to payload()
        is TaxMethod.FlatPercent -> "flatPercent" to payload("basisPoints" to value.basisPoints)
        is TaxMethod.Table -> "withholdingTable" to payload("id" to value.id)
    }

    override fun decodeCase(name: String, payload: JsonObject): TaxMethod = when (name) {
        "none" -> TaxMethod.None
        "flatPercent" -> TaxMethod.FlatPercent(payload.intAt("basisPoints"))
        "withholdingTable" -> TaxMethod.Table(payload.stringAt("id"))
        else -> throw kotlinx.serialization.SerializationException("Unknown TaxMethod $name")
    }
}

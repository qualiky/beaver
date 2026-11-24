@file:Suppress("unused")

package au.com.fuzn.beaver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomWarnings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor

/**
 * Room entities for the v1 schema. Table and column names, types and constraints mirror the iOS
 * migration `v1_initial` one to one (text dates `YYYY-MM-DD`, times `HH:mm`, integer cents, JSON text columns).
 *
 * The same classes are the backup records: property names are the Swift record property names (camelCase),
 * so `kotlinx.serialization` writes exactly the JSON that Swift's `Codable` records write. A JSON column
 * (`*Json`) is stored as text in SQLite but appears as a nested JSON value in the backup, as on iOS.
 *
 * SQLite CHECK constraints (iOS has some) are not expressible in Room; the same rules are enforced in code
 * when rows are converted to core values, and on import.
 */

/** A SQLite JSON text column that is a nested JSON value (not a string) in the backup document. */
internal object JsonTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonText")
    override fun serialize(encoder: Encoder, value: String) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        json.encodeJsonElement(Json.parseToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        return json.decodeJsonElement().toString()
    }
}

@Serializable
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    /** A JSON fragment (for example `"AUD"`, `true`, `15000`). */
    val value: String,
)

@Serializable
@Entity(tableName = "rate_card")
data class RateCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    @Serializable(with = JsonTextSerializer::class)
    @ColumnInfo(name = "rules_json") val rulesJson: String,
)

@Serializable
@Entity(tableName = "withholding_table")
data class WithholdingTableEntity(
    @PrimaryKey val id: String,
    val name: String,
    @Serializable(with = JsonTextSerializer::class)
    @ColumnInfo(name = "rows_json") val rowsJson: String,
    @ColumnInfo(defaultValue = "0") val builtin: Boolean = false,
    val note: String? = null,
)

@Serializable
@Entity(
    tableName = "income_source",
    foreignKeys = [ForeignKey(RateCardEntity::class, ["id"], ["rate_card_id"], onDelete = ForeignKey.SET_NULL)],
)
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
data class IncomeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "rate_card_id") val rateCardId: Int? = null,
    @ColumnInfo(name = "fixed_amount_cents") val fixedAmountCents: Long? = null,
    @ColumnInfo(name = "amount_is_net", defaultValue = "0") val amountIsNet: Boolean = false,
    @Serializable(with = JsonTextSerializer::class)
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    @Serializable(with = JsonTextSerializer::class)
    @ColumnInfo(name = "tax_method_json") val taxMethodJson: String,
    @ColumnInfo(name = "weekly_cap_soft") val weeklyCapSoft: Int? = null,
    @ColumnInfo(name = "weekly_cap_hard") val weeklyCapHard: Int? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
)

@Serializable
@Entity(
    tableName = "template",
    foreignKeys = [ForeignKey(IncomeSourceEntity::class, ["id"], ["source_id"], onDelete = ForeignKey.CASCADE)],
)
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_id") val sourceId: Int,
    val name: String,
    @ColumnInfo(name = "effective_from") val effectiveFrom: String? = null,
)

@Serializable
@Entity(
    tableName = "template_shift",
    foreignKeys = [ForeignKey(TemplateEntity::class, ["id"], ["template_id"], onDelete = ForeignKey.CASCADE)],
)
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
data class TemplateShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "template_id") val templateId: Int,
    /** ISO weekday, 1 (Monday) to 7 (Sunday). */
    val weekday: Int,
    val start: String,
    val end: String,
    @ColumnInfo(name = "break_min", defaultValue = "0") val breakMin: Int = 0,
)

@Serializable
@Entity(
    tableName = "shift",
    foreignKeys = [ForeignKey(IncomeSourceEntity::class, ["id"], ["source_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["date"], name = "shift_on_date"), Index(value = ["source_id", "date"], name = "shift_on_source_date")],
)
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_id") val sourceId: Int,
    val date: String,
    val start: String,
    val end: String,
    @ColumnInfo(name = "break_min", defaultValue = "0") val breakMin: Int = 0,
    /** `rostered`, `worked` or `cancelled`. */
    val status: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

@Serializable
@Entity(
    tableName = "pay_received",
    foreignKeys = [ForeignKey(IncomeSourceEntity::class, ["id"], ["source_id"], onDelete = ForeignKey.CASCADE)],
)
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
data class PayReceivedEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_id") val sourceId: Int,
    @ColumnInfo(name = "pay_date") val payDate: String,
    @ColumnInfo(name = "net_cents") val netCents: Long,
    @ColumnInfo(name = "gross_cents") val grossCents: Long? = null,
    @ColumnInfo(name = "tax_cents") val taxCents: Long? = null,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

@Serializable
@Entity(tableName = "holiday")
data class HolidayEntity(
    @PrimaryKey val date: String,
    val name: String,
)

@Serializable
@Entity(tableName = "unavailable")
data class UnavailableEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "date_from") val dateFrom: String,
    @ColumnInfo(name = "date_to") val dateTo: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

@Serializable
@Entity(tableName = "hours_limit")
data class HoursLimitEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "hours_per_fortnight") val hoursPerFortnight: Int,
    @ColumnInfo(name = "effective_from") val effectiveFrom: String,
)

@Serializable
@Entity(tableName = "bill")
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @Serializable(with = JsonTextSerializer::class)
    @ColumnInfo(name = "cadence_json") val cadenceJson: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    val category: String,
    val account: String? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

@Serializable
@Entity(
    tableName = "bill_payment",
    foreignKeys = [ForeignKey(BillEntity::class, ["id"], ["bill_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bill_id", "due_date"], unique = true)],
)
data class BillPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "bill_id") val billId: Int,
    @ColumnInfo(name = "due_date") val dueDate: String,
    @ColumnInfo(name = "paid_date") val paidDate: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
)

@Serializable
@Entity(tableName = "expense", indices = [Index(value = ["date"], name = "expense_on_date")])
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val category: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

@Serializable
@Entity(tableName = "week_spend_total")
data class WeekSpendTotalEntity(
    @PrimaryKey @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
)

@Serializable
@Entity(tableName = "goal")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    @ColumnInfo(name = "target_cents") val targetCents: Long,
    val deadline: String? = null,
)

@Serializable
@Entity(
    tableName = "deposit",
    foreignKeys = [ForeignKey(GoalEntity::class, ["id"], ["goal_id"], onDelete = ForeignKey.CASCADE)],
)
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
data class DepositEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "goal_id") val goalId: Int,
    val date: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

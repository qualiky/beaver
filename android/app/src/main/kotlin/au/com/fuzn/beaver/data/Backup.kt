package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.BeaverJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** Every table, in dependency order. Key names are the iOS `BackupTables` property names (camelCase). */
@Serializable
data class BackupTables(
    val setting: List<SettingEntity> = emptyList(),
    val rateCard: List<RateCardEntity> = emptyList(),
    val withholdingTable: List<WithholdingTableEntity> = emptyList(),
    val incomeSource: List<IncomeSourceEntity> = emptyList(),
    val template: List<TemplateEntity> = emptyList(),
    val templateShift: List<TemplateShiftEntity> = emptyList(),
    val shift: List<ShiftEntity> = emptyList(),
    val payReceived: List<PayReceivedEntity> = emptyList(),
    val holiday: List<HolidayEntity> = emptyList(),
    val unavailable: List<UnavailableEntity> = emptyList(),
    val hoursLimit: List<HoursLimitEntity> = emptyList(),
    val bill: List<BillEntity> = emptyList(),
    val billPayment: List<BillPaymentEntity> = emptyList(),
    val expense: List<ExpenseEntity> = emptyList(),
    val weekSpendTotal: List<WeekSpendTotalEntity> = emptyList(),
    val goal: List<GoalEntity> = emptyList(),
    val deposit: List<DepositEntity> = emptyList(),
)

/** The versioned JSON file written by export and read by import. `exportedAt` is ISO 8601 UTC, whole seconds. */
@Serializable
data class BackupDocument(
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val tables: BackupTables,
)

/** Why a backup could not be read or imported. Messages match iOS `BackupError`. */
sealed class BackupError(message: String) : Exception(message) {
    /** The file is not a Beaver backup (not JSON, or no schema version). */
    class NotABackup : BackupError("This file is not a Beaver backup.")

    /** A schema version this build does not know: newer than [supported], or not a valid version number. */
    class UnsupportedVersion(val found: Int, val supported: Int) : BackupError(
        if (found > supported) {
            "This backup was made by a newer version of Beaver (backup format $found; this app understands up to " +
                "$supported). Update Beaver, then try again. Nothing was changed."
        } else {
            "This backup has an unknown format version ($found). Nothing was changed."
        },
    )

    /** The file claims a supported version but its content is broken. */
    class Corrupt(val detail: String) : BackupError("This backup could not be read: $detail. Nothing was changed.")
}

/** Export and import of the whole database as JSON, in the same document format iOS reads and writes. */
class Backup(private val repository: Repository, private val appVersion: String) {
    private val format = Json(from = BeaverJson) { prettyPrint = true }

    /** Builds the JSON file and records `lastExportAt`. */
    suspend fun export(now: Instant = Instant.now()): String {
        val tables = repository.snapshotForExport(now)
        val document = BackupDocument(SCHEMA_VERSION, appVersion, Repository.isoString(now), tables)
        return format.encodeToString(BackupDocument.serializer(), document)
    }

    /** Validates the version first, then replaces all data in one transaction. */
    suspend fun import(json: String) {
        val root = try {
            BeaverJson.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupError.NotABackup()
        val version = (root["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }
            ?: throw BackupError.NotABackup()
        val found = version.intOrNull ?: throw BackupError.NotABackup()
        if (found != SCHEMA_VERSION) throw BackupError.UnsupportedVersion(found, SCHEMA_VERSION)

        val document = try {
            BeaverJson.decodeFromJsonElement(BackupDocument.serializer(), root)
        } catch (e: Exception) {
            throw BackupError.Corrupt("the file's contents do not match the expected format")
        }
        try {
            validate(document.tables)
            repository.replaceAll(document.tables)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataError) {
            throw BackupError.Corrupt(e.message ?: "invalid data")
        } catch (e: Exception) {
            throw BackupError.Corrupt("the data conflicts with itself (${e.message})")
        }
    }

    /** Rejects values iOS's CHECK constraints and core types would reject, before anything is written. */
    private fun validate(t: BackupTables) {
        t.rateCard.forEach { it.toCore() }
        t.withholdingTable.forEach { it.toCore() }
        t.incomeSource.forEach { it.toCore() }
        t.template.forEach { it.toCore(emptyList()) }
        t.templateShift.forEach { it.toCore() }
        t.shift.forEach { it.toCore() }
        t.payReceived.forEach { it.toCore() }
        t.holiday.forEach { it.toCore() }
        t.unavailable.forEach { it.toCore() }
        t.hoursLimit.forEach { it.toCore() }
        t.bill.forEach { it.toCore() }
        t.billPayment.forEach { it.toCore() }
        t.expense.forEach { it.toCore() }
        t.weekSpendTotal.forEach { it.toCore() }
        t.goal.forEach { it.toCore() }
        t.deposit.forEach { it.toCore() }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

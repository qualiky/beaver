package au.com.fuzn.beaver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.fuzn.beaver.core.WithholdingTable
import java.io.File

/**
 * Schema version 1: the same tables and columns as the iOS `v1_initial` migration.
 * Schema changes must add a new version and a migration; never edit version 1.
 * The exported schema JSON is committed under `android/app/schemas/`.
 */
@Database(
    entities = [
        SettingEntity::class, RateCardEntity::class, WithholdingTableEntity::class, IncomeSourceEntity::class,
        TemplateEntity::class, TemplateShiftEntity::class, ShiftEntity::class, PayReceivedEntity::class,
        HolidayEntity::class, UnavailableEntity::class, HoursLimitEntity::class, BillEntity::class,
        BillPaymentEntity::class, ExpenseEntity::class, WeekSpendTotalEntity::class, GoalEntity::class,
        DepositEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BeaverDatabase : RoomDatabase() {
    abstract fun dao(): BeaverDao

    companion object {
        const val FILE_NAME = "beaver.db"

        /** SQL table names, children before parents (the iOS `BackupTables.deleteOrder`). */
        val TABLES_CHILDREN_FIRST = listOf(
            "deposit", "goal", "week_spend_total", "expense", "bill_payment", "bill", "hours_limit",
            "unavailable", "holiday", "pay_received", "shift", "template_shift", "template",
            "income_source", "withholding_table", "rate_card", "setting",
        )

        /** The on-disk database `beaver.db` in the app's no-backup files directory (never in cloud backups). */
        fun open(context: Context): BeaverDatabase {
            val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
            return Room.databaseBuilder(context.applicationContext, BeaverDatabase::class.java, file.absolutePath)
                .addCallback(SeedCallback)
                .build()
        }

        /** A private in-memory database (tests and previews). */
        fun inMemory(context: Context): BeaverDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, BeaverDatabase::class.java)
                .addCallback(SeedCallback)
                .build()
    }

    /** Inserts the built-in reference data (not personal data) when the database file is created. */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val table = WithholdingTable.auScale2Weekly.copy(isBuiltin = true).toEntity()
            db.execSQL(
                "INSERT INTO withholding_table (id, name, rows_json, builtin, note) VALUES (?, ?, ?, 1, ?)",
                arrayOf<Any?>(table.id, table.name, table.rowsJson, table.note),
            )
        }
    }
}

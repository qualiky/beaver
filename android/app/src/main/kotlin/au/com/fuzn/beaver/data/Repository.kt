package au.com.fuzn.beaver.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WeekSpendTotal
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.core.mondayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Settings and the engine input, read together in one snapshot. */
data class StoreSnapshot(val settings: AppSettings, val input: ForecastInput)

/**
 * The only class that runs SQL (through the Room DAO). Views and models talk to core value types through this API.
 * Every `save` returns the stored value (with its assigned id when it was new, id 0).
 * All functions are suspending and safe to call from any dispatcher.
 */
class Repository(private val database: BeaverDatabase) {
    private val dao: BeaverDao get() = database.dao()

    // region Settings

    suspend fun settings(): AppSettings = AppSettings.fromRows(dao.settings())

    suspend fun setCurrency(code: String) = setSetting(AppSettings.Key.CURRENCY, JsonPrimitive(code))
    suspend fun setTimeZone(id: String) = setSetting(AppSettings.Key.TIME_ZONE, JsonPrimitive(id))
    suspend fun setOnboardingDone(done: Boolean) = setSetting(AppSettings.Key.ONBOARDING_DONE, JsonPrimitive(done))
    suspend fun setPrimaryGoalId(id: Int?) =
        setSetting(AppSettings.Key.PRIMARY_GOAL_ID, id?.let { JsonPrimitive(it) })

    suspend fun setLivingBudget(amount: Money) =
        setSetting(AppSettings.Key.LIVING_BUDGET_CENTS, JsonPrimitive(amount.cents))

    suspend fun setLastExportAt(at: Instant) = setSetting(AppSettings.Key.LAST_EXPORT_AT, JsonPrimitive(isoString(at)))

    private suspend fun setSetting(key: String, value: JsonPrimitive?) {
        if (value == null) dao.deleteSetting(key) else dao.upsert(SettingEntity(key, value.toString()))
    }

    // endregion

    // region Income sources and rate cards

    suspend fun incomeSources(): List<IncomeSource> = dao.incomeSources().map { it.toCore() }

    suspend fun save(source: IncomeSource): IncomeSource {
        val id = idAfterUpsert(source.id, dao.upsert(source.toEntity()))
        return source.copy(id = id)
    }

    suspend fun deleteIncomeSource(id: Int) = dao.deleteIncomeSource(id)

    suspend fun rateCards(): List<RateCard> = dao.rateCards().map { it.toCore() }

    suspend fun save(card: RateCard): RateCard = card.copy(id = idAfterUpsert(card.id, dao.upsert(card.toEntity())))

    suspend fun deleteRateCard(id: Int) = dao.deleteRateCard(id)

    // endregion

    // region Withholding tables

    /** Built-in and custom tables, built-ins first. */
    suspend fun withholdingTables(): List<WithholdingTable> = dao.withholdingTables().map { it.toCore() }

    /** Saves a custom table. The built-in table cannot be changed ([DataError.BuiltinReadOnly]). */
    suspend fun save(table: WithholdingTable): WithholdingTable = database.withTransaction {
        if (dao.withholdingTable(table.id)?.builtin == true) throw DataError.BuiltinReadOnly()
        val stored = table.copy(isBuiltin = false)
        dao.upsert(stored.toEntity())
        stored
    }

    suspend fun deleteWithholdingTable(id: String) = database.withTransaction {
        if (dao.withholdingTable(id)?.builtin == true) throw DataError.BuiltinReadOnly()
        dao.deleteWithholdingTable(id)
    }

    // endregion

    // region Templates

    suspend fun templates(): List<Template> = database.withTransaction { templatesInTransaction() }

    private suspend fun templatesInTransaction(): List<Template> {
        val byTemplate = dao.templateShifts().groupBy { it.templateId }
        return dao.templates().map { it.toCore(byTemplate[it.id].orEmpty()) }
    }

    /** Saves the template and replaces its shifts. */
    suspend fun save(template: Template): Template = database.withTransaction {
        val id = idAfterUpsert(template.id, dao.upsert(template.toEntity()))
        dao.deleteTemplateShifts(id)
        template.shifts.forEach { dao.insert(it.toEntity(id)) }
        template.copy(id = id)
    }

    suspend fun deleteTemplate(id: Int) = dao.deleteTemplate(id)

    // endregion

    // region Shifts and pay received

    suspend fun shifts(): List<Shift> = dao.shifts().map { it.toCore() }

    suspend fun shifts(range: DateRange, sourceId: Int? = null): List<Shift> =
        (if (sourceId == null) dao.shifts(range.start.toString(), range.end.toString())
        else dao.shifts(range.start.toString(), range.end.toString(), sourceId)).map { it.toCore() }

    suspend fun save(shift: Shift): Shift = shift.copy(id = idAfterUpsert(shift.id, dao.upsert(shift.toEntity())))

    /** Saves several shifts in one transaction (copy last week, apply template). */
    suspend fun saveShifts(shifts: List<Shift>): List<Shift> = database.withTransaction { shifts.map { save(it) } }

    suspend fun deleteShift(id: Int) = dao.deleteShift(id)

    suspend fun paysReceived(): List<PayReceived> = dao.paysReceived().map { it.toCore() }

    suspend fun save(pay: PayReceived): PayReceived = pay.copy(id = idAfterUpsert(pay.id, dao.upsert(pay.toEntity())))

    suspend fun deletePayReceived(id: Int) = dao.deletePayReceived(id)

    // endregion

    // region Holidays, unavailable ranges, hours limits

    suspend fun holidays(): List<Holiday> = dao.holidays().map { it.toCore() }

    /** A holiday is keyed by date: saving an existing date renames it. */
    suspend fun save(holiday: Holiday) = dao.upsert(holiday.toEntity())

    suspend fun deleteHoliday(date: LocalDate) = dao.deleteHoliday(date.toString())

    suspend fun unavailableRanges(): List<UnavailableRange> = dao.unavailable().map { it.toCore() }

    suspend fun save(range: UnavailableRange): UnavailableRange =
        range.copy(id = idAfterUpsert(range.id, dao.upsert(range.toEntity())))

    suspend fun deleteUnavailableRange(id: Int) = dao.deleteUnavailable(id)

    suspend fun hoursLimits(): List<FortnightLimit> = dao.hoursLimits().map { it.toCore() }

    suspend fun save(limit: FortnightLimit): FortnightLimit =
        limit.copy(id = idAfterUpsert(limit.id, dao.upsert(limit.toEntity())))

    suspend fun deleteHoursLimit(id: Int) = dao.deleteHoursLimit(id)

    // endregion

    // region Bills

    suspend fun bills(): List<Bill> = dao.bills().map { it.toCore() }

    suspend fun save(bill: Bill): Bill = bill.copy(id = idAfterUpsert(bill.id, dao.upsert(bill.toEntity())))

    suspend fun deleteBill(id: Int) = dao.deleteBill(id)

    suspend fun billPayments(): List<BillPayment> = dao.billPayments().map { it.toCore() }

    /** Marks one occurrence paid. A second call for the same bill and due date replaces the first. */
    suspend fun markPaid(payment: BillPayment) = database.withTransaction {
        val existing = dao.billPaymentId(payment.billID, payment.dueDate.toString()) ?: 0
        dao.upsert(payment.toEntity(existing))
        Unit
    }

    suspend fun unmarkPaid(billId: Int, dueDate: LocalDate) = dao.deleteBillPayment(billId, dueDate.toString())

    // endregion

    // region Spending

    suspend fun expenses(): List<Expense> = dao.expenses().map { it.toCore() }

    suspend fun expenses(range: DateRange): List<Expense> =
        dao.expenses(range.start.toString(), range.end.toString()).map { it.toCore() }

    suspend fun save(expense: Expense): Expense =
        expense.copy(id = idAfterUpsert(expense.id, dao.upsert(expense.toEntity())))

    suspend fun deleteExpense(id: Int) = dao.deleteExpense(id)

    suspend fun weekSpendTotals(): List<WeekSpendTotal> = dao.weekSpendTotals().map { it.toCore() }

    /** A week total replaces that week's quick entries in the forecast. Stored against the Monday. */
    suspend fun setWeekSpendTotal(weekStart: LocalDate, amount: Money) =
        dao.upsert(WeekSpendTotal(weekStart.mondayOfWeek, amount).toEntity())

    suspend fun clearWeekSpendTotal(weekStart: LocalDate) = dao.deleteWeekSpendTotal(weekStart.mondayOfWeek.toString())

    // endregion

    // region Goals

    suspend fun goals(): List<Goal> = dao.goals().map { it.toCore() }

    suspend fun save(goal: Goal): Goal = goal.copy(id = idAfterUpsert(goal.id, dao.upsert(goal.toEntity())))

    suspend fun deleteGoal(id: Int) = dao.deleteGoal(id)

    suspend fun deposits(): List<Deposit> = dao.deposits().map { it.toCore() }

    suspend fun save(deposit: Deposit): Deposit = deposit.copy(id = idAfterUpsert(deposit.id, dao.upsert(deposit.toEntity())))

    suspend fun deleteDeposit(id: Int) = dao.deleteDeposit(id)

    // endregion

    // region Forecast input

    /** Everything the forecast engine needs, read in one consistent snapshot. */
    suspend fun forecastInput(today: LocalDate): ForecastInput = database.withTransaction {
        forecastInputInTransaction(today, settings())
    }

    private suspend fun forecastInputInTransaction(today: LocalDate, settings: AppSettings) = ForecastInput(
        today = today,
        sources = dao.incomeSources().map { it.toCore() },
        rateCards = dao.rateCards().map { it.toCore() },
        withholdingTables = dao.customWithholdingTables().map { it.toCore() },
        templates = templatesInTransaction(),
        shifts = dao.shifts().map { it.toCore() },
        paysReceived = dao.paysReceived().map { it.toCore() },
        holidays = dao.holidays().map { it.toCore() },
        unavailable = dao.unavailable().map { it.toCore() },
        bills = dao.bills().map { it.toCore() },
        billPayments = dao.billPayments().map { it.toCore() },
        expenses = dao.expenses().map { it.toCore() },
        weekSpendTotals = dao.weekSpendTotals().map { it.toCore() },
        livingBudget = settings.livingBudget,
        goals = dao.goals().map { it.toCore() },
        deposits = dao.deposits().map { it.toCore() },
        primaryGoalID = settings.primaryGoalId,
    )

    // Fortnight limits are not part of ForecastInput (the engine only needs them for warnings),
    // so they are read separately through `hoursLimits()`.

    // endregion

    // region Observation

    /** Emits a fresh value every time any of [tables] changes. The first value is the current state. */
    private fun <T> observe(vararg tables: String, fetch: suspend () -> T): Flow<T> =
        database.invalidationTracker.createFlow(*tables).map { fetch() }.distinctUntilChanged()

    fun observeSettings(): Flow<AppSettings> = observe("setting") { settings() }
    fun observeIncomeSources(): Flow<List<IncomeSource>> = observe("income_source") { incomeSources() }
    fun observeBills(): Flow<List<Bill>> = observe("bill") { bills() }
    fun observeBillPayments(): Flow<List<BillPayment>> = observe("bill_payment") { billPayments() }
    fun observeShifts(range: DateRange, sourceId: Int? = null): Flow<List<Shift>> =
        observe("shift") { shifts(range, sourceId) }

    fun observeExpenses(range: DateRange): Flow<List<Expense>> = observe("expense") { expenses(range) }
    fun observeGoals(): Flow<List<Goal>> = observe("goal") { goals() }
    fun observeDeposits(): Flow<List<Deposit>> = observe("deposit") { deposits() }

    /** Settings and the engine input together, recomputed when any table changes. */
    fun observeSnapshot(today: (ZoneId) -> LocalDate): Flow<StoreSnapshot> =
        observe(*BeaverDatabase.TABLES_CHILDREN_FIRST.toTypedArray()) {
            database.withTransaction {
                val settings = settings()
                StoreSnapshot(settings, forecastInputInTransaction(today(settings.timeZone), settings))
            }
        }

    // endregion

    // region Demo data and erase

    /** Wipes all personal data and inserts the fictional "Sam" from the core `DemoData`. */
    suspend fun loadDemoData() = database.withTransaction {
        wipe()
        dao.upsert(DemoData.rateCard.toEntity())
        dao.upsert(DemoData.source.toEntity())
        val template = DemoData.template
        dao.upsert(template.toEntity())
        template.shifts.forEach { dao.insert(it.toEntity(template.id)) }
        DemoData.paysReceived.forEach { dao.upsert(it.toEntity()) }
        DemoData.holidays.forEach { dao.upsert(it.toEntity()) }
        DemoData.unavailable.forEach { dao.upsert(it.toEntity()) }
        DemoData.bills.forEach { dao.upsert(it.toEntity()) }
        dao.upsert(DemoData.goal.toEntity())
        setCurrency("AUD")
        setOnboardingDone(true)
        setPrimaryGoalId(DemoData.GOAL_ID)
        setLivingBudget(DemoData.livingBudget)
    }

    /** Deletes all personal data and settings. The built-in reference table stays. */
    suspend fun eraseAll() = database.withTransaction { wipe() }

    /** Deletes everything except built-in reference data (children first, so foreign keys hold). */
    private suspend fun wipe() {
        dao.clearDeposit(); dao.clearGoal(); dao.clearWeekSpendTotal(); dao.clearExpense(); dao.clearBillPayment()
        dao.clearBill(); dao.clearHoursLimit(); dao.clearUnavailable(); dao.clearHoliday(); dao.clearPayReceived()
        dao.clearShift(); dao.clearTemplateShift(); dao.clearTemplate(); dao.clearIncomeSource()
        dao.clearCustomWithholdingTables(); dao.clearRateCard(); dao.clearSetting()
    }

    // endregion

    // region Backup support

    /** Records `lastExportAt` and reads every table, in one transaction. */
    suspend fun snapshotForExport(now: Instant): BackupTables = database.withTransaction {
        setLastExportAt(now)
        backupTables()
    }

    suspend fun backupTables(): BackupTables = BackupTables(
        setting = dao.settings(),
        rateCard = dao.rateCards(),
        withholdingTable = dao.withholdingTablesById(),
        incomeSource = dao.incomeSources(),
        template = dao.templates(),
        templateShift = dao.templateShifts(),
        shift = dao.shiftsById(),
        payReceived = dao.paysReceivedById(),
        holiday = dao.holidays(),
        unavailable = dao.unavailableById(),
        hoursLimit = dao.hoursLimitsById(),
        bill = dao.bills(),
        billPayment = dao.billPaymentsById(),
        expense = dao.expensesById(),
        weekSpendTotal = dao.weekSpendTotals(),
        goal = dao.goals(),
        deposit = dao.depositsById(),
    )

    /** Replaces every table with [tables] in one transaction. Any failure rolls the whole import back. */
    suspend fun replaceAll(tables: BackupTables) = database.withTransaction {
        // Delete everything, including the built-in table: the backup (or the code, below) puts it back.
        wipe()
        dao.clearWithholdingTable()
        dao.insertSettings(tables.setting)
        dao.insertRateCards(tables.rateCard)
        dao.insertWithholdingTables(tables.withholdingTable)
        dao.insertIncomeSources(tables.incomeSource)
        dao.insertTemplates(tables.template)
        dao.insertTemplateShifts(tables.templateShift)
        dao.insertShifts(tables.shift)
        dao.insertPaysReceived(tables.payReceived)
        dao.insertHolidays(tables.holiday)
        dao.insertUnavailable(tables.unavailable)
        dao.insertHoursLimits(tables.hoursLimit)
        dao.insertBills(tables.bill)
        dao.insertBillPayments(tables.billPayment)
        dao.insertExpenses(tables.expense)
        dao.insertWeekSpendTotals(tables.weekSpendTotal)
        dao.insertGoals(tables.goal)
        dao.insertDeposits(tables.deposit)
        // The built-in reference table always exists, even if the backup predates it.
        if (dao.withholdingTable(WithholdingTable.AU_SCALE2_WEEKLY_ID) == null) {
            dao.upsert(WithholdingTable.auScale2Weekly.copy(isBuiltin = true).toEntity())
        }
        // Fail the import (and roll back) if any foreign key is broken.
        val broken = dao.rawStrings(SimpleSQLiteQuery("PRAGMA foreign_key_check"))
        if (broken.isNotEmpty()) throw DataError.Corrupt("broken reference in table ${broken.first()}")
    }

    /** Rows the user (or demo data) put in the database. Built-in reference tables are not counted. */
    suspend fun userRowCount(): Int = database.withTransaction {
        var total = dao.customWithholdingTableCount()
        for (table in BeaverDatabase.TABLES_CHILDREN_FIRST) {
            if (table == "withholding_table") continue
            total += dao.rawInt(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table"))
        }
        total
    }

    suspend fun tableNames(): List<String> = dao.tableNames()

    suspend fun foreignKeysEnabled(): Boolean = dao.rawInt(SimpleSQLiteQuery("PRAGMA foreign_keys")) == 1

    // endregion

    private fun idAfterUpsert(id: Int, rowId: Long): Int = if (id == 0) rowId.toInt() else id

    internal companion object {
        /** `2026-11-23T00:00:00Z`: UTC, whole seconds, the form iOS `ISO8601DateFormatter` writes and reads. */
        fun isoString(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS))
    }
}

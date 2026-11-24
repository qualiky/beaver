package au.com.fuzn.beaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * The Room SQL for every table. Only [Repository] uses it; the UI and models never see it.
 * Orderings match the iOS repository so lists come back in the same order on both platforms.
 */
@Dao
interface BeaverDao {
    // Settings
    @Query("SELECT * FROM setting ORDER BY `key`") suspend fun settings(): List<SettingEntity>
    @Upsert suspend fun upsert(setting: SettingEntity)
    @Query("DELETE FROM setting WHERE `key` = :key") suspend fun deleteSetting(key: String)

    // Rate cards
    @Query("SELECT * FROM rate_card ORDER BY id") suspend fun rateCards(): List<RateCardEntity>
    @Upsert suspend fun upsert(card: RateCardEntity): Long
    @Query("DELETE FROM rate_card WHERE id = :id") suspend fun deleteRateCard(id: Int)

    // Withholding tables
    @Query("SELECT * FROM withholding_table ORDER BY builtin DESC, name") suspend fun withholdingTables(): List<WithholdingTableEntity>
    @Query("SELECT * FROM withholding_table WHERE builtin = 0 ORDER BY name") suspend fun customWithholdingTables(): List<WithholdingTableEntity>
    @Query("SELECT * FROM withholding_table ORDER BY id") suspend fun withholdingTablesById(): List<WithholdingTableEntity>
    @Query("SELECT * FROM withholding_table WHERE id = :id") suspend fun withholdingTable(id: String): WithholdingTableEntity?
    @Upsert suspend fun upsert(table: WithholdingTableEntity)
    @Query("DELETE FROM withholding_table WHERE id = :id") suspend fun deleteWithholdingTable(id: String)

    // Income sources
    @Query("SELECT * FROM income_source ORDER BY id") suspend fun incomeSources(): List<IncomeSourceEntity>
    @Upsert suspend fun upsert(source: IncomeSourceEntity): Long
    @Query("DELETE FROM income_source WHERE id = :id") suspend fun deleteIncomeSource(id: Int)

    // Templates
    @Query("SELECT * FROM template ORDER BY id") suspend fun templates(): List<TemplateEntity>
    @Query("SELECT * FROM template_shift ORDER BY id") suspend fun templateShifts(): List<TemplateShiftEntity>
    @Upsert suspend fun upsert(template: TemplateEntity): Long
    @Insert suspend fun insert(shift: TemplateShiftEntity): Long
    @Query("DELETE FROM template_shift WHERE template_id = :templateId") suspend fun deleteTemplateShifts(templateId: Int)
    @Query("DELETE FROM template WHERE id = :id") suspend fun deleteTemplate(id: Int)

    // Shifts
    @Query("SELECT * FROM shift ORDER BY date, start, id") suspend fun shifts(): List<ShiftEntity>
    @Query("SELECT * FROM shift WHERE date >= :from AND date <= :to ORDER BY date, start, id")
    suspend fun shifts(from: String, to: String): List<ShiftEntity>
    @Query("SELECT * FROM shift WHERE date >= :from AND date <= :to AND source_id = :sourceId ORDER BY date, start, id")
    suspend fun shifts(from: String, to: String, sourceId: Int): List<ShiftEntity>
    @Query("SELECT * FROM shift ORDER BY id") suspend fun shiftsById(): List<ShiftEntity>
    @Upsert suspend fun upsert(shift: ShiftEntity): Long
    @Query("DELETE FROM shift WHERE id = :id") suspend fun deleteShift(id: Int)

    // Pay received
    @Query("SELECT * FROM pay_received ORDER BY pay_date, id") suspend fun paysReceived(): List<PayReceivedEntity>
    @Query("SELECT * FROM pay_received ORDER BY id") suspend fun paysReceivedById(): List<PayReceivedEntity>
    @Upsert suspend fun upsert(pay: PayReceivedEntity): Long
    @Query("DELETE FROM pay_received WHERE id = :id") suspend fun deletePayReceived(id: Int)

    // Holidays
    @Query("SELECT * FROM holiday ORDER BY date") suspend fun holidays(): List<HolidayEntity>
    @Upsert suspend fun upsert(holiday: HolidayEntity)
    @Query("DELETE FROM holiday WHERE date = :date") suspend fun deleteHoliday(date: String)

    // Unavailable ranges
    @Query("SELECT * FROM unavailable ORDER BY date_from, id") suspend fun unavailable(): List<UnavailableEntity>
    @Query("SELECT * FROM unavailable ORDER BY id") suspend fun unavailableById(): List<UnavailableEntity>
    @Upsert suspend fun upsert(range: UnavailableEntity): Long
    @Query("DELETE FROM unavailable WHERE id = :id") suspend fun deleteUnavailable(id: Int)

    // Hours limits
    @Query("SELECT * FROM hours_limit ORDER BY effective_from, id") suspend fun hoursLimits(): List<HoursLimitEntity>
    @Query("SELECT * FROM hours_limit ORDER BY id") suspend fun hoursLimitsById(): List<HoursLimitEntity>
    @Upsert suspend fun upsert(limit: HoursLimitEntity): Long
    @Query("DELETE FROM hours_limit WHERE id = :id") suspend fun deleteHoursLimit(id: Int)

    // Bills and payments
    @Query("SELECT * FROM bill ORDER BY id") suspend fun bills(): List<BillEntity>
    @Upsert suspend fun upsert(bill: BillEntity): Long
    @Query("DELETE FROM bill WHERE id = :id") suspend fun deleteBill(id: Int)
    @Query("SELECT * FROM bill_payment ORDER BY due_date, id") suspend fun billPayments(): List<BillPaymentEntity>
    @Query("SELECT * FROM bill_payment ORDER BY id") suspend fun billPaymentsById(): List<BillPaymentEntity>
    @Query("SELECT id FROM bill_payment WHERE bill_id = :billId AND due_date = :dueDate")
    suspend fun billPaymentId(billId: Int, dueDate: String): Int?
    @Upsert suspend fun upsert(payment: BillPaymentEntity): Long
    @Query("DELETE FROM bill_payment WHERE bill_id = :billId AND due_date = :dueDate")
    suspend fun deleteBillPayment(billId: Int, dueDate: String)

    // Spending
    @Query("SELECT * FROM expense ORDER BY date, id") suspend fun expenses(): List<ExpenseEntity>
    @Query("SELECT * FROM expense WHERE date >= :from AND date <= :to ORDER BY date, id")
    suspend fun expenses(from: String, to: String): List<ExpenseEntity>
    @Query("SELECT * FROM expense ORDER BY id") suspend fun expensesById(): List<ExpenseEntity>
    @Upsert suspend fun upsert(expense: ExpenseEntity): Long
    @Query("DELETE FROM expense WHERE id = :id") suspend fun deleteExpense(id: Int)
    @Query("SELECT * FROM week_spend_total ORDER BY week_start") suspend fun weekSpendTotals(): List<WeekSpendTotalEntity>
    @Upsert suspend fun upsert(total: WeekSpendTotalEntity)
    @Query("DELETE FROM week_spend_total WHERE week_start = :weekStart") suspend fun deleteWeekSpendTotal(weekStart: String)

    // Goals and deposits
    @Query("SELECT * FROM goal ORDER BY id") suspend fun goals(): List<GoalEntity>
    @Upsert suspend fun upsert(goal: GoalEntity): Long
    @Query("DELETE FROM goal WHERE id = :id") suspend fun deleteGoal(id: Int)
    @Query("SELECT * FROM deposit ORDER BY date, id") suspend fun deposits(): List<DepositEntity>
    @Query("SELECT * FROM deposit ORDER BY id") suspend fun depositsById(): List<DepositEntity>
    @Upsert suspend fun upsert(deposit: DepositEntity): Long
    @Query("DELETE FROM deposit WHERE id = :id") suspend fun deleteDeposit(id: Int)

    // Wipe, children before parents (the order matches iOS `BackupTables.deleteOrder`).
    @Query("DELETE FROM deposit") suspend fun clearDeposit()
    @Query("DELETE FROM goal") suspend fun clearGoal()
    @Query("DELETE FROM week_spend_total") suspend fun clearWeekSpendTotal()
    @Query("DELETE FROM expense") suspend fun clearExpense()
    @Query("DELETE FROM bill_payment") suspend fun clearBillPayment()
    @Query("DELETE FROM bill") suspend fun clearBill()
    @Query("DELETE FROM hours_limit") suspend fun clearHoursLimit()
    @Query("DELETE FROM unavailable") suspend fun clearUnavailable()
    @Query("DELETE FROM holiday") suspend fun clearHoliday()
    @Query("DELETE FROM pay_received") suspend fun clearPayReceived()
    @Query("DELETE FROM shift") suspend fun clearShift()
    @Query("DELETE FROM template_shift") suspend fun clearTemplateShift()
    @Query("DELETE FROM template") suspend fun clearTemplate()
    @Query("DELETE FROM income_source") suspend fun clearIncomeSource()
    @Query("DELETE FROM withholding_table") suspend fun clearWithholdingTable()
    @Query("DELETE FROM withholding_table WHERE builtin = 0") suspend fun clearCustomWithholdingTables()
    @Query("DELETE FROM rate_card") suspend fun clearRateCard()
    @Query("DELETE FROM setting") suspend fun clearSetting()

    // Bulk inserts for import (plain insert: a duplicate key fails and rolls the import back).
    @Insert suspend fun insertSettings(rows: List<SettingEntity>)
    @Insert suspend fun insertRateCards(rows: List<RateCardEntity>)
    @Insert suspend fun insertWithholdingTables(rows: List<WithholdingTableEntity>)
    @Insert suspend fun insertIncomeSources(rows: List<IncomeSourceEntity>)
    @Insert suspend fun insertTemplates(rows: List<TemplateEntity>)
    @Insert suspend fun insertTemplateShifts(rows: List<TemplateShiftEntity>)
    @Insert suspend fun insertShifts(rows: List<ShiftEntity>)
    @Insert suspend fun insertPaysReceived(rows: List<PayReceivedEntity>)
    @Insert suspend fun insertHolidays(rows: List<HolidayEntity>)
    @Insert suspend fun insertUnavailable(rows: List<UnavailableEntity>)
    @Insert suspend fun insertHoursLimits(rows: List<HoursLimitEntity>)
    @Insert suspend fun insertBills(rows: List<BillEntity>)
    @Insert suspend fun insertBillPayments(rows: List<BillPaymentEntity>)
    @Insert suspend fun insertExpenses(rows: List<ExpenseEntity>)
    @Insert suspend fun insertWeekSpendTotals(rows: List<WeekSpendTotalEntity>)
    @Insert suspend fun insertGoals(rows: List<GoalEntity>)
    @Insert suspend fun insertDeposits(rows: List<DepositEntity>)

    // Diagnostics
    @Query("SELECT COUNT(*) FROM withholding_table WHERE builtin = 0") suspend fun customWithholdingTableCount(): Int
    @Query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name != 'room_master_table' ORDER BY name")
    suspend fun tableNames(): List<String>
    @RawQuery suspend fun rawInt(query: SupportSQLiteQuery): Int
    @RawQuery suspend fun rawStrings(query: SupportSQLiteQuery): List<String>
}

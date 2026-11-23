import Foundation
import Testing
import BeaverCore
@testable import Beaver

private let today: LocalDate = "2026-11-23"

private func repo() throws -> Repository { try Repository.inMemory() }

// MARK: - MoneyField parsing

struct MoneyParserTests {
    @Test(arguments: [
        ("12", 1200), ("12.5", 1250), ("12.50", 1250), ("1,234.56", 123_456), ("0.05", 5), (".5", 50),
        ("12.", 1200), ("$12.30", 1230), ("  7 ", 700), ("1,000", 100_000), ("0", 0),
    ])
    func parsesValidInput(text: String, cents: Int) {
        #expect(MoneyParser.cents(from: text) == cents)
    }

    @Test(arguments: ["", " ", "abc", "12.345", "1.2.3", "-5", "+5", "1,23", "12,34.5", ",123", "1e3", ".", "$", "12 50", "1234567890123"])
    func rejectsBadInput(text: String) {
        #expect(MoneyParser.cents(from: text) == nil)
    }

    @Test func formatsCentsAsEditableText() {
        #expect(MoneyParser.text(fromCents: 1250) == "12.50")
        #expect(MoneyParser.text(fromCents: 5) == "0.05")
        #expect(MoneyParser.text(fromCents: 123_456) == "1234.56")
        #expect(MoneyParser.cents(from: MoneyParser.text(fromCents: 99_999)) == 99_999)
    }

    @Test func percentParsesToBasisPoints() {
        #expect(MoneyParser.basisPoints(fromPercent: "12.5") == 1250)
        #expect(MoneyParser.basisPoints(fromPercent: "15") == 1500)
        #expect(MoneyParser.basisPoints(fromPercent: "x") == nil)
    }
}

// MARK: - Rate card

@MainActor
struct RateCardEditorTests {
    @Test func startsInvalidAndEmpty() {
        let m = RateCardEditorModel()
        #expect(!m.isValid)
        #expect(m.nameIssue != nil)
        #expect(m.rulesIssue != nil)
    }

    @Test func templateIsValidAndComplete() {
        let m = RateCardEditorModel()
        m.applyTypicalCasual()
        #expect(m.isValid)
        #expect(m.gaps.isEmpty)
        #expect(m.gapWarning == nil)
        #expect(m.makeCard()?.rules == RateCard.typicalCasual().rules)
    }

    @Test func editingABandRate() {
        let m = RateCardEditorModel()
        m.applyTypicalCasual()
        m.rules[1].rateText = "31.25"
        #expect(m.makeCard()?.rules[1].centsPerHour == 3125)
    }

    @Test func deletingTheEveningBandShowsAGap() {
        let m = RateCardEditorModel()
        m.applyTypicalCasual()
        m.removeRule(id: m.rules[2].id) // Weekday evening 19:00-24:00
        #expect(m.isValid) // a gap warns but does not block
        #expect(m.gaps == [ClockWindow(fromHour: 19, toHour: 24)])
        #expect(m.gapWarning?.contains("19:00–24:00") == true)
    }

    @Test func ruleValidation() {
        var r = RateRuleDraft()
        #expect(r.bandIssue != nil)
        #expect(r.rateIssue != nil)
        r.band = "Weekday"
        r.rateText = "0"
        #expect(r.rateIssue != nil)
        r.rateText = "30"
        #expect(r.isValid)
        r.hasWindow = true
        r.fromMinute = 600
        r.toMinute = 600
        #expect(r.windowIssue != nil)
        #expect(r.rule == nil)
        r.toMinute = 1440
        #expect(r.windowIssue == nil)
        #expect(r.rule?.window == ClockWindow(startMinute: 600, endMinute: 1440))
    }

    @Test func reorderAndRemove() {
        let m = RateCardEditorModel()
        m.applyTypicalCasual()
        let first = m.rules[0].band
        m.moveRule(id: m.rules[0].id, by: -1) // already first: no-op
        #expect(m.rules[0].band == first)
        m.moveRule(id: m.rules[0].id, by: 1)
        #expect(m.rules[1].band == first)
        m.addRule()
        #expect(m.rules.count == 7)
        #expect(!m.isValid) // the new blank rule is invalid
    }

    @Test func savingRoundTrips() throws {
        let repository = try repo()
        let m = RateCardEditorModel()
        m.applyTypicalCasual()
        m.name = "Example rates"
        let stored = try m.save(using: repository)
        #expect(stored.id != 0)
        let loaded = try repository.rateCards().first { $0.id == stored.id }
        #expect(loaded == stored)
        #expect(loaded?.name == "Example rates")
        #expect(throws: EditorError.self) { try RateCardEditorModel().save(using: repository) }
    }
}

// MARK: - Income source

@MainActor
struct IncomeSourceEditorTests {
    @Test func newSourceNeedsNameAndRates() {
        let m = IncomeSourceEditorModel(today: today)
        #expect(!m.isValid)
        #expect(m.issue(for: .name) != nil)
        #expect(m.issue(for: .rates) != nil)
        m.name = "Example Bar"
        m.rateCard.applyTypicalCasual()
        #expect(m.isValid)
    }

    @Test func fixedNeedsAmountAndMonthlyOnlyForFixed() {
        let m = IncomeSourceEditorModel(today: today)
        m.name = "Example Salary"
        m.kind = .fixed
        #expect(m.issue(for: .amount) != nil)
        m.fixedAmountText = "4,800"
        m.scheduleKind = .monthly
        m.dayOfMonth = 31
        #expect(m.isValid)
        m.kind = .hourly // monthly is not allowed for hourly: falls back to weekly
        #expect(m.scheduleKind == .weekly)
        #expect(m.availableSchedules == [.weekly, .fortnightly])
        m.scheduleKind = .monthly
        #expect(m.issue(for: .schedule) != nil)
    }

    @Test func netAmountSkipsTax() {
        let m = IncomeSourceEditorModel(today: today)
        m.name = "Salary"
        m.kind = .fixed
        m.fixedAmountText = "100"
        m.taxKind = .flat
        m.flatPercentText = "bad"
        #expect(m.issue(for: .tax) != nil)
        m.amountIsNet = true
        #expect(m.issue(for: .tax) == nil)
        #expect(m.taxMethod == TaxMethod.none)
    }

    @Test func taxValidation() {
        let m = IncomeSourceEditorModel(today: today)
        m.taxKind = .flat
        m.flatPercentText = "0"
        #expect(m.issue(for: .tax) != nil)
        m.flatPercentText = "101"
        #expect(m.issue(for: .tax) != nil)
        m.flatPercentText = "12.5"
        #expect(m.issue(for: .tax) == nil)
        #expect(m.taxMethod == .flatPercent(basisPoints: 1250))
        m.taxKind = .table
        #expect(m.issue(for: .tax) != nil) // not loaded, no table chosen
    }

    @Test func capValidation() {
        let m = IncomeSourceEditorModel(today: today)
        #expect(m.issue(for: .softCap) == nil)
        m.softCapText = "30"
        m.hardCapText = "20"
        #expect(m.issue(for: .hardCap) != nil)
        m.hardCapText = "38"
        #expect(m.issue(for: .hardCap) == nil)
        m.softCapText = "x"
        #expect(m.issue(for: .softCap) != nil)
        m.softCapText = "200"
        #expect(m.issue(for: .softCap) != nil)
    }

    @Test func hourlyRoundTripsWithRateCardAndTable() throws {
        let repository = try repo()
        let m = IncomeSourceEditorModel(today: today)
        m.load(from: repository)
        #expect(m.tables.contains { $0.id == WithholdingTable.auScale2WeeklyID })
        m.name = "Example Bar"
        m.rateCard.applyTypicalCasual()
        m.scheduleKind = .weekly
        m.payWeekday = .wednesday
        m.taxKind = .table
        m.tableID = WithholdingTable.auScale2WeeklyID
        m.softCapText = "30"
        m.hardCapText = "38"
        let stored = try m.save(using: repository)
        #expect(stored.id != 0)
        #expect(stored.rateCardID != nil)
        #expect(stored.schedule == .weekly(payWeekday: .wednesday, lagWeeks: 1))
        #expect(stored.taxMethod == .withholdingTable(id: WithholdingTable.auScale2WeeklyID))
        #expect(try repository.incomeSources() == [stored])
        #expect(try repository.rateCards().first?.rules == RateCard.typicalCasual().rules)

        // Edit it: reload the model from storage, change the Sunday rate and the schedule.
        let edit = IncomeSourceEditorModel(source: stored, today: today)
        edit.load(from: repository)
        #expect(edit.rateCard.rules.count == 6)
        edit.rateCard.rules[5].rateText = "46.00"
        edit.scheduleKind = .fortnightly
        edit.anchorPayday = "2026-11-25"
        edit.isActive = false
        let updated = try edit.save(using: repository)
        #expect(updated.id == stored.id)
        #expect(updated.rateCardID == stored.rateCardID)
        #expect(updated.isActive == false)
        #expect(updated.schedule == .fortnightly(anchorPayday: "2026-11-25", lagWeeks: 1))
        #expect(try repository.rateCards().count == 1)
        #expect(try repository.rateCards().first?.rules[5].centsPerHour == 4600)

        // Delete removes the source and its now-unused rate card.
        try edit.delete(using: repository)
        #expect(try repository.incomeSources().isEmpty)
        #expect(try repository.rateCards().isEmpty)
    }

    @Test func fixedRoundTrips() throws {
        let repository = try repo()
        let m = IncomeSourceEditorModel(today: today)
        m.name = "Example Salary"
        m.kind = .fixed
        m.fixedAmountText = "4800"
        m.amountIsNet = true
        m.scheduleKind = .monthly
        m.dayOfMonth = 31
        let stored = try m.save(using: repository)
        #expect(stored.fixedAmount == .dollars(4800))
        #expect(stored.amountIsNet)
        #expect(stored.rateCardID == nil)
        #expect(stored.taxMethod == TaxMethod.none)
        #expect(try repository.incomeSources() == [stored])
        #expect(try repository.rateCards().isEmpty)
    }

    @Test func existingSourceMapsBackToFields() {
        let m = IncomeSourceEditorModel(source: DemoData.source, today: today)
        #expect(m.kind == .hourly)
        #expect(m.scheduleKind == .weekly)
        #expect(m.payWeekday == .wednesday)
        #expect(m.taxKind == .table)
        #expect(m.tableID == WithholdingTable.auScale2WeeklyID)
        #expect(!m.isNew)
    }
}

// MARK: - Template

@MainActor
struct TemplateEditorTests {
    @Test func validation() {
        let m = TemplateEditorModel(sourceID: 1, today: today)
        #expect(!m.isValid)
        #expect(m.shiftsIssue != nil)
        m.addShift()
        #expect(m.isValid)
        m.shifts[0].end = m.shifts[0].start
        #expect(m.shifts[0].issue != nil)
        #expect(!m.isValid)
        m.shifts[0].end = "17:00"
        m.shifts[0].breakMinutes = 480
        #expect(m.shifts[0].issue != nil)
        m.shifts[0].breakMinutes = 30
        m.name = "  "
        #expect(m.nameIssue != nil)
        #expect(!m.isValid)
    }

    @Test func overnightShiftIsValid() {
        let d = TemplateShiftDraft(weekday: .friday, start: "18:00", end: "04:30", breakMinutes: 30)
        #expect(d.crossesMidnight)
        #expect(d.durationMinutes == 630)
        #expect(d.issue == nil)
        #expect(d.paidHoursText == "10.0")
    }

    @Test func addShiftPicksUnusedWeekday() {
        let m = TemplateEditorModel(sourceID: 1, today: today)
        m.addShift()
        m.addShift()
        #expect(m.shifts.map(\.weekday) == [.monday, .tuesday])
    }

    @Test func roundTripsDemoTemplateWithEffectiveDate() throws {
        let repository = try repo()
        let source = try repository.save(IncomeSource(id: 0, name: "Example Bar", kind: .hourly,
                                                      schedule: .weekly(payWeekday: .wednesday, lagWeeks: 1)))
        let m = TemplateEditorModel(sourceID: source.id, template: DemoData.template, today: today)
        #expect(m.shifts.count == 5)
        m.hasEffectiveFrom = true
        m.effectiveFrom = "2027-02-22"
        m.name = "Semester 1"
        let stored = try m.save(using: repository)
        #expect(stored.sourceID == source.id)
        #expect(stored.effectiveFrom == "2027-02-22")
        #expect(stored.shifts == DemoData.template.shifts)
        #expect(try repository.templates() == [stored])

        // A second template for the same source: both are listed.
        let second = TemplateEditorModel(sourceID: source.id, today: today)
        second.addShift()
        try second.save(using: repository)
        #expect(try repository.templates().count == 2)

        // Delete.
        let editing = TemplateEditorModel(sourceID: source.id, template: stored, today: today)
        try editing.delete(using: repository)
        #expect(try repository.templates().count == 1)
    }
}

// MARK: - Bills

@MainActor
struct BillEditorTests {
    @Test func presetsHaveNoAmountAndCoverTheCommonList() {
        let names = BillPreset.common.map(\.name)
        #expect(names == ["Rent", "Phone", "Internet", "Electricity", "Gas", "Water", "Transport card", "Gym",
                          "Streaming", "Music", "Cloud storage", "Insurance", "Loan repayment"])
        #expect(Set(names).count == names.count)
        #expect(BillPreset.common.first { $0.name == "Rent" }?.category == .housing)
    }

    @Test func applyingAPresetLeavesAmountEmpty() {
        let m = BillEditorModel(today: today)
        m.apply(BillPreset.common[3]) // Electricity
        #expect(m.name == "Electricity")
        #expect(m.category == .utilities)
        #expect(m.cadenceKind == .quarterly)
        #expect(m.amountText.isEmpty)
        #expect(!m.isValid)
        m.amountText = "240"
        #expect(m.isValid)
    }

    @Test func validation() {
        let m = BillEditorModel(today: today)
        #expect(m.issue(for: .name) != nil)
        #expect(m.issue(for: .amount) != nil)
        m.name = "Phone"
        m.amountText = "0"
        #expect(m.issue(for: .amount) != nil)
        m.amountText = "30"
        m.cadenceKind = .everyNDays
        m.everyNDaysText = "0"
        #expect(m.issue(for: .cadence) != nil)
        m.everyNDaysText = "abc"
        #expect(m.issue(for: .cadence) != nil)
        m.everyNDaysText = "28"
        #expect(m.issue(for: .cadence) == nil)
        #expect(m.makeBill()?.cadence == .everyNDays(28))
    }

    @Test func monthlyDayFollowsAnchor() {
        let m = BillEditorModel(today: today)
        m.cadenceKind = .weekly
        m.cadenceKind = .monthly
        #expect(m.dayOfMonth == 23)
        m.anchorDate = "2026-12-05"
        #expect(m.dayOfMonth == 5)
    }

    @Test(arguments: BillCadenceKind.allCases)
    func everyCadenceBuildsAndRoundTrips(kind: BillCadenceKind) throws {
        let repository = try repo()
        let m = BillEditorModel(today: today)
        m.name = "Test bill"
        m.amountText = "15.99"
        m.cadenceKind = kind
        m.everyNDaysText = "10"
        m.anchorDate = "2026-12-05"
        m.dayOfMonth = 31
        m.category = .subscription
        m.account = " Everyday "
        m.note = "A note"
        m.isActive = false
        let stored = try m.save(using: repository)
        #expect(stored.id != 0)
        #expect(stored.amount == .dollars(15, cents: 99))
        #expect(stored.account == "Everyday")
        #expect(try repository.bills() == [stored])
        #expect(BillCadenceKind(stored.cadence) == kind)
        if kind == .monthly { #expect(stored.cadence == .monthly(dayOfMonth: 31)) }
        if kind == .everyNDays { #expect(stored.cadence == .everyNDays(10)) }

        let edit = BillEditorModel(bill: stored, today: today)
        #expect(edit.makeBill() == stored)
        try edit.delete(using: repository)
        #expect(try repository.bills().isEmpty)
    }
}

// MARK: - Goal

@MainActor
struct GoalEditorTests {
    @Test func validation() {
        let m = GoalEditorModel(today: today)
        #expect(m.issue(for: .name) != nil)
        #expect(m.issue(for: .target) != nil)
        m.name = "Course fees"
        m.targetText = "6000"
        #expect(m.isValid)
        m.hasDeadline = true
        m.deadline = today
        #expect(m.issue(for: .deadline) != nil)
        m.deadline = "2027-03-31"
        #expect(m.isValid)
        m.savedText = "12.345"
        #expect(m.issue(for: .saved) != nil)
        #expect(!m.isValid)
    }

    @Test func firstGoalDefaultsToPrimary() {
        #expect(GoalEditorModel(today: today).makePrimary)
        #expect(!GoalEditorModel(isFirstGoal: false, today: today).makePrimary)
    }

    @Test func savedAmountCreatesInitialDepositAndPrimary() throws {
        let repository = try repo()
        let m = GoalEditorModel(today: today)
        m.name = "Course fees"
        m.targetText = "6,000"
        m.hasDeadline = true
        m.deadline = "2027-03-31"
        m.savedText = "250.50"
        let stored = try m.save(using: repository)
        #expect(stored.target == .dollars(6000))
        #expect(stored.deadline == "2027-03-31")
        #expect(try repository.goals() == [stored])
        let deposits = try repository.deposits()
        #expect(deposits.count == 1)
        #expect(deposits.first?.amount == .dollars(250, cents: 50))
        #expect(deposits.first?.goalID == stored.id)
        #expect(deposits.first?.date == today)
        #expect(try repository.settings().primaryGoalID == stored.id)
    }

    @Test func zeroSavedMakesNoDepositAndEditDoesNotAddOne() throws {
        let repository = try repo()
        let m = GoalEditorModel(today: today)
        m.name = "Holiday"
        m.targetText = "1000"
        m.savedText = "0"
        m.makePrimary = false
        let stored = try m.save(using: repository)
        #expect(try repository.deposits().isEmpty)
        #expect(try repository.settings().primaryGoalID == nil)

        let edit = GoalEditorModel(goal: stored, today: today)
        edit.savedText = "500" // ignored for existing goals
        edit.targetText = "1500"
        let updated = try edit.save(using: repository)
        #expect(updated.id == stored.id)
        #expect(updated.target == .dollars(1500))
        #expect(try repository.deposits().isEmpty)
    }

    @Test func deleteClearsPrimary() throws {
        let repository = try repo()
        let m = GoalEditorModel(today: today)
        m.name = "A"
        m.targetText = "10"
        let stored = try m.save(using: repository)
        try GoalEditorModel(goal: stored, isPrimary: true, today: today).delete(using: repository)
        #expect(try repository.goals().isEmpty)
        #expect(try repository.settings().primaryGoalID == nil)
    }
}

// MARK: - Limits

@MainActor
struct LimitsEditorTests {
    @Test func hoursValidation() {
        let m = LimitsEditorModel(today: today)
        m.hasLimit = true
        #expect(m.issue(for: .hours) != nil)
        m.hoursText = "48"
        #expect(m.issue(for: .hours) == nil)
        m.hoursText = "0"
        #expect(m.issue(for: .hours) != nil)
        m.hoursText = "4.5"
        #expect(m.issue(for: .hours) != nil)
        m.hasLimit = false
        #expect(m.isValid)
    }

    @Test func holidayAddEditDuplicate() {
        let m = LimitsEditorModel(value: LimitsValue(), today: today)
        #expect(m.saveHoliday(Holiday(date: "2026-12-25", name: "Christmas Day")))
        #expect(!m.saveHoliday(Holiday(date: "2026-12-25", name: "Again")))
        #expect(!m.saveHoliday(Holiday(date: "2026-12-26", name: "  ")))
        #expect(m.saveHoliday(Holiday(date: "2026-12-26", name: "Boxing Day")))
        // Move Christmas to a new date by editing it.
        #expect(m.saveHoliday(Holiday(date: "2026-12-27", name: "Christmas"), editing: "2026-12-25"))
        #expect(m.holidays.map(\.date) == ["2026-12-26", "2026-12-27"])
        m.removeHoliday(date: "2026-12-26")
        #expect(m.holidays.count == 1)
    }

    @Test func unavailableRangeValidation() {
        let m = LimitsEditorModel(value: LimitsValue(), today: today)
        #expect(!m.saveUnavailable(UnavailableRange(from: "2026-12-05", to: "2026-12-02")))
        #expect(m.saveUnavailable(UnavailableRange(from: "2026-12-02", to: "2026-12-05", note: " Trip ")))
        #expect(m.saveUnavailable(UnavailableRange(from: "2026-11-01", to: "2026-11-01")))
        #expect(m.unavailable.count == 2)
        #expect(m.unavailable[0].id != m.unavailable[1].id)
        #expect(m.unavailable[1].note == "Trip")
    }

    @Test func savingRoundTripsAndDiffs() throws {
        let repository = try repo()
        let m = LimitsEditorModel(today: today)
        m.load(from: repository)
        #expect(m.isLoaded)
        m.hasLimit = true
        m.hoursText = "48"
        m.effectiveFrom = "2027-03-01"
        m.saveHoliday(Holiday(date: "2026-12-25", name: "Christmas Day"))
        m.saveHoliday(Holiday(date: "2026-12-26", name: "Boxing Day"))
        m.saveUnavailable(UnavailableRange(from: "2026-12-02", to: "2026-12-05", note: "Trip"))
        let first = try m.save(using: repository)
        #expect(first.fortnightLimit?.hoursPerFortnight == 48)
        #expect(try repository.hoursLimits() == [first.fortnightLimit!])
        #expect(try repository.holidays().map(\.name).sorted() == ["Boxing Day", "Christmas Day"])
        let ranges = try repository.unavailableRanges()
        #expect(ranges.count == 1 && ranges[0].id > 0 && ranges[0].note == "Trip")

        // Reload from storage, then edit, delete and disable.
        let again = LimitsEditorModel(today: today)
        again.load(from: repository)
        #expect(again.hasLimit && again.hoursText == "48" && again.effectiveFrom == "2027-03-01")
        again.hoursText = "40"
        again.removeHoliday(date: "2026-12-25")
        again.saveUnavailable(UnavailableRange(id: ranges[0].id, from: "2026-12-02", to: "2026-12-06", note: "Trip"))
        let second = try again.save(using: repository)
        #expect(second.fortnightLimit?.id == first.fortnightLimit?.id)
        #expect(try repository.hoursLimits().map(\.hoursPerFortnight) == [40])
        #expect(try repository.holidays().map(\.name) == ["Boxing Day"])
        #expect(try repository.unavailableRanges().map(\.to) == ["2026-12-06"])

        again.hasLimit = false
        again.removeUnavailable(id: ranges[0].id)
        try again.save(using: repository)
        #expect(try repository.hoursLimits().isEmpty)
        #expect(try repository.unavailableRanges().isEmpty)
    }

    @Test func cannotSaveBeforeLoading() throws {
        let repository = try repo()
        let m = LimitsEditorModel(today: today)
        #expect(throws: EditorError.self) { try m.save(using: repository) }
    }

    @Test func loadsDemoData() throws {
        let repository = try repo()
        try repository.loadDemoData()
        let m = LimitsEditorModel(today: today)
        m.load(from: repository)
        #expect(m.holidays.count == 5)
        #expect(m.unavailable.count == 1)
        #expect(!m.hasLimit)
    }
}

// MARK: - Pristine forms show no errors

@MainActor
struct PristineFormTests {
    @Test func newIncomeShowsNoErrorsUntilEdited() {
        let m = IncomeSourceEditorModel(today: today)
        #expect(!m.isValid)
        for field in IncomeSourceEditorModel.Field.allCases { #expect(m.visibleIssue(for: field) == nil) }
        m.touch(.name)
        #expect(m.visibleIssue(for: .name) != nil)
        #expect(m.visibleIssue(for: .rates) == nil)
        m.rateCard.addRule()
        m.rateCard.removeRule(id: m.rateCard.rules[0].id)
        #expect(m.visibleIssue(for: .rates) != nil)
    }

    @Test func failedSaveRevealsEverything() throws {
        let m = IncomeSourceEditorModel(today: today)
        #expect(throws: EditorError.self) { try m.save(using: repo()) }
        #expect(m.visibleIssue(for: .name) != nil)
        #expect(m.visibleIssue(for: .rates) != nil)
        #expect(m.rateCard.visibleNameIssue != nil)
    }

    @Test func newFixedIncomeIsNewWithoutDelete() {
        let m = IncomeSourceEditorModel(newKind: .fixed, today: today)
        #expect(m.isNew)
        #expect(m.kind == .fixed)
        #expect(m.visibleIssue(for: .amount) == nil)
    }

    @Test func otherEditorsAreQuietWhenPristine() {
        let bill = BillEditorModel(today: today)
        for f in BillEditorModel.Field.allCases { #expect(bill.visibleIssue(for: f) == nil) }
        bill.touch(.amount)
        #expect(bill.visibleIssue(for: .amount) != nil)

        let goal = GoalEditorModel()
        for f in GoalEditorModel.Field.allCases { #expect(goal.visibleIssue(for: f) == nil) }
        goal.touch(.target)
        #expect(goal.visibleIssue(for: .target) != nil)

        let tpl = TemplateEditorModel(sourceID: 1, today: today)
        #expect(tpl.visibleShiftsIssue == nil)
        tpl.addShift(); tpl.removeShift(id: tpl.shifts[0].id)
        #expect(tpl.visibleShiftsIssue != nil)

        let card = RateCardEditorModel()
        #expect(card.visibleNameIssue == nil && card.visibleRulesIssue == nil)
        card.addRule()
        #expect(card.visibleBandIssue(card.rules[0]) == nil)
    }

    @Test func presetsHaveDistinctSymbols() {
        let symbols = BillPreset.common.map(\.symbol)
        #expect(Set(symbols).count == symbols.count)
        #expect(BillPreset.common.first { $0.name == "Phone" }?.symbol == "iphone")
    }

    @Test func timeZoneSearchPutsCurrentFirstAndFilters() {
        let all = ["Australia/Sydney", "Europe/London", "America/New_York"]
        #expect(TimeZoneChoices.filtered(query: "", current: "Europe/London", all: all).first == "Europe/London")
        #expect(TimeZoneChoices.filtered(query: "new york", current: "Europe/London", all: all) == ["America/New_York"])
        #expect(TimeZoneChoices.filtered(query: "SYD", current: "Europe/London", all: all) == ["Australia/Sydney"])
        #expect(TimeZoneChoices.filtered(query: "", current: "Mars/Base", all: all).first == "Mars/Base")
    }
}

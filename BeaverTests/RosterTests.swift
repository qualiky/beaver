import Foundation
import Testing
import BeaverCore
@testable import Beaver

/// Roster view model tests over an in-memory repository with the fictional demo data.
@MainActor
struct RosterTests {
    private let today: LocalDate = "2026-11-23"

    private func demoRepository() throws -> Repository {
        let repo = try Repository.inMemory()
        try repo.loadDemoData()
        return repo
    }

    /// The demo template expanded onto the week starting `monday`, saved as real shifts.
    @discardableResult
    private func rosterTemplate(_ repo: Repository, week monday: LocalDate) throws -> [Shift] {
        let shifts = TemplateExpander.shifts(sourceID: DemoData.sourceID, templates: [DemoData.template], range: monday.week)
        return try repo.save(shifts: shifts)
    }

    private func model(_ repo: Repository, week: LocalDate) -> RosterViewModel {
        RosterViewModel(repository: repo, today: today, timeZone: TimeZone(identifier: "Australia/Sydney")!, weekStart: week)
    }

    @Test func weekTotalsMatchPeriodPayForDemoTemplateWeek() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")

        let footer = try #require(vm.footers.first)
        #expect(vm.footers.count == 1)
        #expect(footer.gross.cents == 137283)
        #expect(footer.net.cents == 110983)
        #expect(footer.tax.cents == 137283 - 110983)
        #expect(footer.paidMinutes == 3650 * 60 / 100)
        // Pays the Wednesday after the week ends.
        #expect(footer.payday == "2026-11-25")
        #expect(footer.taxPeriod == nil)
        // Row grossing agrees with the footer.
        let rowTotal = vm.dayGroups.flatMap(\.lines).map(\.gross).total()
        #expect(rowTotal.cents == 137283)
        #expect(vm.dayGroups.count == 5)
    }

    @Test func cancelledShiftsEarnNothingAndHaveNoChips() throws {
        let repo = try demoRepository()
        let saved = try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")
        var first = try #require(saved.first)
        first.status = .cancelled
        vm.save(first)
        let footer = try #require(vm.footers.first)
        #expect(footer.gross.cents < 137283)
        let line = try #require(vm.dayGroups.flatMap(\.lines).first { $0.id == first.id })
        #expect(line.gross == .zero)
    }

    @Test func copyLastWeekSkipsExistingAndNeverDuplicates() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-16")
        // Wednesday already rostered in the target week, plus a cancelled shift last week that must not be copied.
        try repo.save(Shift(sourceID: DemoData.sourceID, date: "2026-11-25", start: "18:00", end: "23:00", breakMinutes: 30))
        try repo.save(Shift(sourceID: DemoData.sourceID, date: "2026-11-16", start: "09:00", end: "12:00", status: .cancelled))
        let vm = model(repo, week: "2026-11-23")

        let plan = vm.copyLastWeekPlan()
        #expect(plan.toAdd.count == 4)
        #expect(plan.skippedExisting == 1)
        #expect(plan.skippedUnavailable == 0)
        #expect(plan.toAdd.allSatisfy { $0.status == .rostered && $0.id == 0 })

        vm.copyLastWeek()
        #expect(vm.weekShifts.count == 5)
        #expect(vm.toast?.message.contains("Copied 4 shifts") == true)

        // A second copy adds nothing.
        vm.copyLastWeek()
        #expect(vm.weekShifts.count == 5)
        #expect(try repo.shifts(in: "2026-11-23".asDate.week).count == 5)
    }

    @Test func copyLastWeekSkipsUnavailableDates() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-23")
        let vm = model(repo, week: "2026-11-30")

        let plan = vm.copyLastWeekPlan()
        // Demo unavailable range is Wed 2 Dec to Sat 5 Dec: only Sunday 6 Dec is added.
        #expect(plan.toAdd.map(\.date) == ["2026-12-06"])
        #expect(plan.skippedUnavailable == 4)
        vm.copyLastWeek()
        #expect(vm.weekShifts.map(\.date) == ["2026-12-06"])
    }

    @Test func copyLastWeekWithNothingToCopy() throws {
        let repo = try demoRepository()
        let vm = model(repo, week: "2026-11-30")
        #expect(vm.copyLastWeekPlan().hasSource == false)
        vm.copyLastWeek()
        #expect(vm.weekShifts.isEmpty)
    }

    @Test func applyTemplateOnWeekOf30NovAddsOnlySunday6Dec() throws {
        let repo = try demoRepository()
        let vm = model(repo, week: "2026-11-30")

        let plan = vm.applyTemplatePlan()
        #expect(plan.toAdd.count == 1)
        let shift = try #require(plan.toAdd.first)
        #expect(shift.date == "2026-12-06")
        #expect(shift.start == "17:00")
        #expect(plan.skippedUnavailable == 4)

        vm.applyTemplate()
        #expect(vm.weekShifts.count == 1)
        #expect(vm.toast?.message.contains("Added 1 shift") == true)

        // Applying again never duplicates.
        vm.applyTemplate()
        #expect(vm.weekShifts.count == 1)
    }

    @Test func applyTemplateUsesTheTemplateEffectiveForTheWeek() throws {
        let repo = try demoRepository()
        // A reduced template from 7 Dec: Monday only.
        try repo.save(Template(sourceID: DemoData.sourceID, name: "Reduced", effectiveFrom: "2026-12-07",
                               shifts: [TemplateShift(weekday: .monday, start: "10:00", end: "14:00")]))
        let vm = model(repo, week: "2026-12-07")
        let plan = vm.applyTemplatePlan()
        #expect(plan.toAdd.map(\.date) == ["2026-12-07"])
    }

    @Test func saveWeekAsTemplateReplacesRatherThanDuplicates() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")
        #expect(vm.saveWeekAsTemplate() == 5)
        #expect(vm.saveWeekAsTemplate() == 5)
        let templates = try repo.templates().filter { $0.effectiveFrom == "2026-11-16" }
        #expect(templates.count == 1)
        #expect(templates.first?.shifts.count == 5)
    }

    @Test func deleteCanBeUndone() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")
        let victim = try #require(vm.weekShifts.first)
        vm.delete(victim)
        #expect(vm.weekShifts.count == 4)
        #expect(vm.toast?.undoable == true)
        vm.undoDelete()
        #expect(vm.weekShifts.count == 5)
        #expect(vm.toast == nil)
    }

    @Test func weeklyHoursWarningIsShownButNeverBlocksSaving() throws {
        let repo = try demoRepository()
        var source = DemoData.source
        source.weeklyCapSoftHours = 10
        try repo.save(source)
        try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")
        let warning = try #require(vm.footers.first?.warnings.first)
        #expect(warning.level == .soft)
        #expect(vm.save(Shift(sourceID: DemoData.sourceID, date: "2026-11-17", start: "09:00", end: "17:00")))
    }

    @Test func editorDefaultsComeFromTheLastShiftEntered() throws {
        let repo = try demoRepository()
        try repo.save(Shift(sourceID: DemoData.sourceID, date: "2026-11-16", start: "11:00", end: "19:30", breakMinutes: 45))
        let vm = model(repo, week: "2026-11-16")
        let editor = try #require(vm.makeEditor())
        #expect(editor.start == "11:00")
        #expect(editor.end == "19:30")
        #expect(editor.breakMinutes == 45)
        #expect(editor.customBreak == false)
        // Preview: 8.5 h shift, 45 min break, weekday day rate 30.00 until 19:00 then evening 33.00.
        #expect(editor.pay != nil)
        #expect(editor.finishesNextDay == false)
    }

    @Test func editorPreviewShowsHolidayAndMidnightCrossing() throws {
        let repo = try demoRepository()
        let vm = model(repo, week: "2026-12-21")
        let editor = try #require(vm.makeEditor())
        editor.date = "2026-12-25"
        editor.start = "20:00"
        editor.end = "02:00"
        editor.breakMinutes = 0
        #expect(editor.finishesNextDay)
        #expect(editor.holidayNames == ["Christmas Day", "Boxing Day"])
        let pay = try #require(editor.pay)
        #expect(pay.gross.cents == 6 * 6000)
        editor.status = .cancelled
        #expect(editor.gross == .zero)
    }

    // MARK: Log pay received

    @Test func logPayDifferenceIsActualMinusExpected() throws {
        #expect(RosterViewModel.difference(actual: .dollars(1150), expected: Money(cents: 110983)).cents == 4017)
        #expect(RosterViewModel.difference(actual: .dollars(600), expected: Money(cents: 110983)).cents == -50983)
        #expect(RosterViewModel.difference(actual: Money(cents: 110983), expected: Money(cents: 110983)) == .zero)
    }

    @Test func logPayModelComputesDifferenceAgainstExpectedNet() throws {
        let repo = try demoRepository()
        try rosterTemplate(repo, week: "2026-11-16")
        let vm = model(repo, week: "2026-11-16")
        let pay = LogPayModel(roster: vm)
        pay.sourceID = DemoData.sourceID
        pay.payday = "2026-11-25"
        #expect(pay.expectedNet?.cents == 110983)
        pay.netText = "1,150.00"
        #expect(pay.difference?.cents == 4017)
        pay.netText = "$1000"
        #expect(pay.difference?.cents == 100000 - 110983)
        pay.netText = ""
        #expect(pay.difference == nil)
        #expect(!pay.canSave)
    }

    @Test func loggingPayTwiceUpdatesTheSameRow() throws {
        let repo = try demoRepository()
        let vm = model(repo, week: "2026-11-16")
        // The demo already has a logged pay for 25 Nov.
        let before = try repo.paysReceived().count
        #expect(vm.logPay(sourceID: DemoData.sourceID, payDate: "2026-11-25", net: .dollars(700), gross: nil, tax: nil, note: ""))
        #expect(try repo.paysReceived().count == before)
        #expect(try repo.paysReceived().first { $0.payDate == "2026-11-25" }?.net == .dollars(700))
        #expect(vm.logPay(sourceID: DemoData.sourceID, payDate: "2026-12-02", net: .dollars(800), gross: .dollars(900), tax: .dollars(100), note: "x"))
        #expect(try repo.paysReceived().count == before + 1)
        #expect(vm.recentPays.first?.payDate == "2026-12-02")
    }

    @Test func noHourlySourcesGivesNoEditor() throws {
        let repo = try Repository.inMemory()
        let vm = RosterViewModel(repository: repo, today: today)
        #expect(vm.hourlySources.isEmpty)
        #expect(vm.makeEditor() == nil)
        #expect(vm.dayGroups.isEmpty)
    }

    // MARK: Formatting

    @Test func bandChipsUseRateCardBandNames() {
        let names = DemoData.rateCard.bandNames
        #expect(names.map(RosterFormat.chip(forBand:)) == ["PH", "Day", "Eve", "Night", "Sat", "Sun"])
        #expect(RosterFormat.chip(forBand: "Unrated") == "Unrated")
    }

    @Test func moneyParsing() {
        #expect(RosterFormat.parseMoney("1,234.50") == Money(cents: 123450))
        #expect(RosterFormat.parseMoney("$12") == Money(cents: 1200))
        #expect(RosterFormat.parseMoney("12,5") == Money(cents: 1250))
        #expect(RosterFormat.parseMoney("1,234") == Money(cents: 123400))
        #expect(RosterFormat.parseMoney("") == nil)
        #expect(RosterFormat.parseMoney("abc") == nil)
        #expect(RosterFormat.parseMoney("-5") == nil)
    }

    @Test func hoursFormatting() {
        #expect(RosterFormat.hours(hundredths: 3650) == "36.5 h")
        #expect(RosterFormat.hours(hundredths: 3600) == "36 h")
        #expect(RosterFormat.hours(hundredths: 469) == "4.69 h")
        #expect(RosterFormat.hours(minutes: 330) == "5.5 h")
        #expect(RosterFormat.hoursAndMinutes(1350) == "22 h 30 m")
    }
}

private extension String {
    var asDate: LocalDate { LocalDate(stringLiteral: self) }
}

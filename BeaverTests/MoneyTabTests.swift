import Foundation
import Testing
import BeaverCore
@testable import Beaver

private let enUS = Locale(identifier: "en_US")
private let today = DemoData.referenceToday // Mon 2026-11-23

@MainActor
private func demo() throws -> (repository: Repository, actions: MoneyActions) {
    let repository = try Repository.inMemory()
    try repository.loadDemoData()
    return (repository, MoneyActions(repository: repository))
}

private func input(_ repository: Repository) throws -> ForecastInput { try repository.forecastInput(today: today) }

private func bills(_ repository: Repository) throws -> BillsModel {
    BillsModel(input: try input(repository), currency: "AUD", locale: enUS)
}

private func spending(_ repository: Repository) throws -> SpendingModel {
    SpendingModel(input: try input(repository), currency: "AUD", locale: enUS)
}

struct MoneyBillsTests {
    @MainActor
    @Test func rentOnPaydayGroupsUnderThatPayday() throws {
        let (repo, _) = try demo()
        let groups = try bills(repo).groups
        let group = try #require(groups.first { $0.payday == "2026-11-25" })
        #expect(group.title.hasPrefix("Before "))
        #expect(group.rows.contains { $0.name == "Rent" && $0.dueDate == "2026-11-25" })
        // The next group starts after that payday.
        #expect(groups.dropFirst().first?.payday == "2026-12-02")
        #expect(group.total == group.rows.map(\.amount).total())
        // Eight weeks of weekly rent: every week appears, and nothing sits past the window.
        #expect(groups.flatMap(\.rows).filter { $0.name == "Rent" }.count == 8)
    }

    @MainActor
    @Test func overdueUnpaidIsPinnedAndLeftOutOfGroups() throws {
        let (repo, _) = try demo()
        let model = try bills(repo)
        // Phone was due 2026-11-22, the day before today.
        #expect(model.overdue.map(\.name) == ["Phone"])
        #expect(model.overdueTotal == .dollars(30))
        #expect(!model.groups.flatMap(\.rows).contains { $0.name == "Phone" && $0.dueDate == "2026-11-22" })
    }

    @MainActor
    @Test func withoutIncomeSourcesGroupsByWeek() throws {
        let (repo, _) = try demo()
        for source in try repo.incomeSources() { try repo.deleteIncomeSource(id: source.id) }
        let groups = try bills(repo).groups
        #expect(groups.allSatisfy { $0.payday == nil })
        #expect(groups.first?.title == "This week")
        #expect(groups.first?.rows.contains { $0.name == "Rent" } == true)
    }

    @MainActor
    @Test func monthlyViewOrdersByDayOfMonth() throws {
        let (repo, _) = try demo()
        let rows = try bills(repo).monthly
        let dated = rows.compactMap { r in r.dayOfMonth.map { (r.bill.name, $0) } }
        #expect(dated.map(\.0) == ["Streaming", "Car service", "Electricity"])
        #expect(dated.map(\.1) == [5, 10, 15])
        // Weekly style bills follow, by name.
        #expect(rows.dropFirst(3).map(\.bill.name) == ["Gym", "Phone", "Rent"])
    }

    @MainActor
    @Test func markPaidAndUnmarkRoundTrip() throws {
        let (repo, actions) = try demo()
        try actions.markPaid(billID: 1, dueDate: "2026-11-25", paidDate: today, amount: .dollars(395))
        var rent = try #require(try bills(repo).groups.flatMap(\.rows).first { $0.billID == 1 && $0.dueDate == "2026-11-25" })
        #expect(rent.isPaid)
        #expect(rent.amount == .dollars(395))
        #expect(rent.occurrence.paidDate == today)

        try actions.unmarkPaid(billID: 1, dueDate: "2026-11-25")
        rent = try #require(try bills(repo).groups.flatMap(\.rows).first { $0.billID == 1 && $0.dueDate == "2026-11-25" })
        #expect(!rent.isPaid)
        #expect(rent.amount == .dollars(400))
        #expect(try repo.billPayments().isEmpty)
    }

    @MainActor
    @Test func markPaidFormDefaultsToPlannedAmountAndToday() throws {
        let (repo, actions) = try demo()
        let row = try #require(try bills(repo).overdue.first)
        var form = MarkPaidForm(row: row, today: today)
        #expect(form.amountText == "30.00")
        #expect(form.paidDate == today)
        form.amountText = "28.50"
        try form.save(using: actions)
        #expect(try repo.billPayments().first?.amount == Money(cents: 2850))
        #expect(try bills(repo).overdue.isEmpty)
    }

    @MainActor
    @Test func paidAmountSurvivesLaterBillEdit() throws {
        let (repo, actions) = try demo()
        try actions.markPaid(billID: 1, dueDate: "2026-11-25", paidDate: today, amount: .dollars(395))
        var rent = try #require(try repo.bills().first { $0.id == 1 })
        rent.amount = .dollars(450)
        try repo.save(rent)

        let rows = try bills(repo).groups.flatMap(\.rows).filter { $0.billID == 1 }
        let paid = try #require(rows.first { $0.dueDate == "2026-11-25" })
        #expect(paid.isPaid && paid.amount == .dollars(395))
        // Unpaid occurrences from the next one on take the new amount.
        let next = try #require(rows.first { $0.dueDate == "2026-12-02" })
        #expect(next.amount == .dollars(450))
    }

    @MainActor
    @Test func pausedBillsLeaveListsAndReturnOnResume() throws {
        let (repo, actions) = try demo()
        let gym = try #require(try repo.bills().first { $0.name == "Gym" })
        try actions.setActive(gym, false)
        var model = try bills(repo)
        #expect(model.pausedBills.map(\.name) == ["Gym"])
        #expect(!model.groups.flatMap(\.rows).contains { $0.name == "Gym" })
        #expect(!model.monthly.contains { $0.bill.name == "Gym" })
        try actions.setActive(gym, true)
        model = try bills(repo)
        #expect(model.pausedBills.isEmpty)
        #expect(model.monthly.contains { $0.bill.name == "Gym" })
    }
}

struct MoneySpendingTests {
    @MainActor
    @Test func weekTotalOverridesEntries() throws {
        let (repo, actions) = try demo()
        try repo.save(Expense(date: "2026-11-23", amount: .dollars(20), category: .food))
        try repo.save(Expense(date: "2026-11-24", amount: .dollars(30), category: .coffeeDrinks))
        #expect(try spending(repo).spentThisWeek == .dollars(50))

        try actions.setWeekTotal(weekStart: "2026-11-25", amount: .dollars(120)) // any day in the week
        var model = try spending(repo)
        #expect(model.spentThisWeek == .dollars(120))
        #expect(model.leftThisWeek == .dollars(30))
        #expect(model.history.first?.hasTotal == true)

        try actions.clearWeekTotal(weekStart: "2026-11-23")
        model = try spending(repo)
        #expect(model.spentThisWeek == .dollars(50))
        #expect(model.history.first?.hasTotal == false)
    }

    @MainActor
    @Test func runningVarianceTotalsCompletedWeeksWithData() throws {
        let (repo, _) = try demo() // budget $150
        try repo.save(Expense(date: "2026-11-02", amount: .dollars(100))) // week of 11-02: +50
        try repo.save(Expense(date: "2026-11-10", amount: .dollars(200))) // week of 11-09: -50
        try repo.setWeekSpendTotal(weekStart: "2026-11-16", amount: .dollars(140)) // +10
        try repo.save(Expense(date: "2026-11-24", amount: .dollars(500))) // current week, not counted
        let model = try spending(repo)
        let history = model.history
        #expect(history.count == 12)
        #expect(history.first?.weekStart == "2026-11-23")
        #expect(history.first?.isCurrent == true && history.first?.running == nil)
        func row(_ week: LocalDate) -> WeekSpendRow? { history.first { $0.weekStart == week } }
        #expect(row("2026-11-02")?.running == .dollars(50))
        #expect(row("2026-11-09")?.running == .dollars(0))
        #expect(row("2026-11-16")?.running == .dollars(10))
        #expect(model.runningVariance == .dollars(10))
        // Weeks with no data are skipped, not counted as savings.
        #expect(row("2026-10-26")?.hasData == false)
        #expect(row("2026-10-26")?.running == .zero)
        #expect(model.varianceThisWeek == .dollars(-350))
    }

    @MainActor
    @Test func editAndDeleteExpense() throws {
        let (repo, actions) = try demo()
        let saved = try repo.save(Expense(date: "2026-11-23", amount: .dollars(12), category: .food))
        var form = ExpenseForm(expense: saved)
        form.amountText = "15.50"
        form.category = .shopping
        try form.save(using: actions)
        let edited = try #require(try repo.expenses().first)
        #expect(edited.id == saved.id && edited.amount == Money(cents: 1550) && edited.category == .shopping)
        try actions.deleteExpense(saved.id)
        #expect(try repo.expenses().isEmpty)
    }
}

struct MoneyGoalsTests {
    @MainActor
    @Test func depositsUpdateGoalProgress() throws {
        let (repo, actions) = try demo()
        var row = try #require(GoalsModel(input: try input(repo), currency: "AUD", locale: enUS).rows.first)
        #expect(row.saved == .zero && row.progressBasisPoints == 0 && row.isPrimary)

        var form = DepositForm(goalID: row.goal.id, today: today)
        form.amountText = "1,500"
        form.note = "Pay"
        try form.save(using: actions)
        row = try #require(GoalsModel(input: try input(repo), currency: "AUD", locale: enUS).rows.first)
        #expect(row.saved == .dollars(1500))
        #expect(row.progressBasisPoints == 2500)
        #expect(row.percentText == "25%")
        #expect(row.deposits.count == 1)

        try actions.deleteDeposit(try #require(row.deposits.first).id)
        row = try #require(GoalsModel(input: try input(repo), currency: "AUD", locale: enUS).rows.first)
        #expect(row.saved == .zero)
    }

    @MainActor
    @Test func makePrimaryMovesTheBadge() throws {
        let (repo, actions) = try demo()
        let second = try repo.save(Goal(name: "Bike", target: .dollars(900)))
        try actions.makePrimary(second.id)
        let rows = GoalsModel(input: try input(repo), currency: "AUD", locale: enUS).rows
        #expect(rows.first { $0.goal.id == second.id }?.isPrimary == true)
        #expect(rows.first { $0.goal.id == DemoData.goalID }?.isPrimary == false)
    }
}

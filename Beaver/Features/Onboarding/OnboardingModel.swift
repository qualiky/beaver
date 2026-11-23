import Foundation
import Observation
import BeaverCore

// MARK: - Weekly maths

/// Weekly equivalents for onboarding's "≈ $X per week" lines. Integer cents, rounded half up.
enum WeeklyEquivalent {
    /// `n / d` rounded half up, for `n >= 0` and `d > 0`.
    static func roundDiv(_ n: Int, _ d: Int) -> Int {
        guard d > 0 else { return 0 }
        return (2 * n + d) / (2 * d)
    }

    /// What a bill costs per week on average. One-off bills count as zero (they are not a regular cost).
    static func cents(amount: Money, cadence: Cadence) -> Int {
        let c = max(0, amount.cents)
        switch cadence {
        case .weekly: return c
        case .fortnightly: return roundDiv(c, 2)
        case .everyNDays(let n): return roundDiv(c * 7, max(n, 1))
        case .monthly: return roundDiv(c * 12, 52)
        case .quarterly: return roundDiv(c * 4, 52)
        case .yearly: return roundDiv(c, 52)
        case .oneOff: return 0
        }
    }

    /// Weekly equivalent of every active, repeating bill.
    static func bills(_ bills: [Bill]) -> Money {
        Money(cents: bills.filter(\.isActive).reduce(0) { $0 + cents(amount: $1.amount, cadence: $1.cadence) })
    }

    /// Average net income per week from the active sources.
    /// Fixed sources use their net amount per pay. Hourly sources use their default week of shifts (a pay period of
    /// shifts, taxed once on the period gross); an hourly source with no default week counts as zero.
    static func income(_ input: ForecastInput) -> Money {
        var total = 0
        for source in input.sources where source.isActive {
            switch source.kind {
            case .fixed:
                let amount = source.fixedAmount ?? .zero
                let tax = source.amountIsNet ? Money.zero
                    : source.taxMethod.tax(onGross: amount, frequency: source.schedule.frequency, tables: input.withholdingTables)
                let net = max(0, (amount - tax).cents)
                switch source.schedule {
                case .weekly: total += net
                case .fortnightly: total += roundDiv(net, 2)
                case .monthly: total += roundDiv(net * 12, 52)
                }
            case .hourly:
                var weeks = 1
                if case .fortnightly = source.schedule { weeks = 2 }
                let start = input.today.mondayOfWeek.adding(days: 7)
                let range = DateRange(start: start, end: start.adding(days: weeks * 7 - 1))
                let shifts = TemplateExpander.shifts(sourceID: source.id, templates: input.templates, range: range,
                                                     unavailable: input.unavailable)
                let card = input.rateCards.first { $0.id == source.rateCardID }
                let pay = PeriodPay.calculate(shifts: shifts, rateCard: card, holidays: input.holidayDates,
                                              taxMethod: source.taxMethod, frequency: source.schedule.frequency,
                                              tables: input.withholdingTables)
                total += roundDiv(max(0, pay.net.cents), weeks)
            }
        }
        return Money(cents: total)
    }
}

// MARK: - Summary

/// What the last step shows.
struct OnboardingSummary: Equatable {
    var headline: String
    var detail: String?
    var weeklyIncome: Money
    var weeklyBills: Money
    var weeklyLiving: Money
    var weeklySurplus: Money
    var requiredPerPayday: Money?
    var hasGoal: Bool
    var isEmpty: Bool
}

// MARK: - Model

/// The step logic for onboarding. It holds no views. Editors save themselves through the repository; this model
/// reloads what they wrote, tracks the step, and writes settings, the living budget and the finish flag.
@MainActor
@Observable
final class OnboardingModel {
    enum Mode: Sendable { case firstRun, rerun }

    enum Step: Int, CaseIterable, Comparable, Sendable {
        case welcome, income, bills, living, goal, limits, summary

        static func < (a: Step, b: Step) -> Bool { a.rawValue < b.rawValue }

        /// Steps 2 to 6 can be skipped.
        var canSkip: Bool { self != .welcome && self != .summary }

        var title: String {
            switch self {
            case .welcome: "Welcome"
            case .income: "Income"
            case .bills: "Bills"
            case .living: "Living money"
            case .goal: "Goal"
            case .limits: "Limits"
            case .summary: "Summary"
            }
        }
    }

    /// Currencies offered in the picker. The current one is added when it is not here.
    static let commonCurrencies = ["AUD", "USD", "EUR", "GBP", "NZD", "CAD", "SGD", "HKD", "JPY", "CHF", "INR", "ZAR"]

    let mode: Mode
    let appModel: AppModel
    private(set) var step: Step = .welcome

    // Welcome
    var currency: String
    var timeZoneID: String
    /// Backup data picked in the file importer, waiting for the user's confirmation.
    var pendingRestore: Data?
    var restoreError: String?

    // Loaded data
    private(set) var sources: [IncomeSource] = []
    private(set) var bills: [Bill] = []
    private(set) var goals: [Goal] = []
    private(set) var limitsSummary: String?
    var livingText: String = ""
    var errorMessage: String?

    /// Set when onboarding finishes (the view dismisses a re-run).
    private(set) var isFinished = false

    /// Formats money in the saved currency.
    var formatter: FigureFormatter { FigureFormatter(currency: store.settings.currency) }

    private var repository: Repository { appModel.repository }
    private var store: AppStore { appModel.store }

    init(appModel: AppModel, mode: Mode = .firstRun, deviceCurrency: String? = Locale.current.currency?.identifier,
         deviceTimeZone: TimeZone = .current) {
        self.appModel = appModel
        self.mode = mode
        let stored = (try? appModel.repository.settings()) ?? AppSettings()
        if mode == .rerun {
            currency = stored.currency
            timeZoneID = stored.timeZoneID
        } else {
            currency = deviceCurrency?.uppercased() ?? "AUD"
            timeZoneID = deviceTimeZone.identifier
        }
        refresh()
        let budget = stored.livingBudget
        livingText = budget.cents > 0 ? MoneyParser.text(fromCents: budget.cents) : ""
    }

    // MARK: Navigation

    var isFirst: Bool { step == .welcome }
    var isLast: Bool { step == .summary }
    var stepNumber: Int { step.rawValue + 1 }
    var stepCount: Int { Step.allCases.count }
    var progress: Double { Double(stepNumber) / Double(stepCount) }
    var canSkip: Bool { step.canSkip }

    /// Whether Next is allowed now. Only a typed but invalid living budget blocks it.
    var canAdvance: Bool { step != .living || livingIssue == nil }

    /// Commits this step's own fields, then moves on. Returns false when it stayed (invalid input or a write error).
    @discardableResult
    func next() -> Bool {
        guard !isLast, canAdvance else { return false }
        guard commitCurrentStep() else { return false }
        step = Step(rawValue: step.rawValue + 1) ?? step
        refresh()
        return true
    }

    /// Moves on without committing this step's typed fields. Only steps 2 to 6.
    @discardableResult
    func skip() -> Bool {
        guard canSkip else { return false }
        step = Step(rawValue: step.rawValue + 1) ?? step
        refresh()
        return true
    }

    func back() {
        guard !isFirst else { return }
        step = Step(rawValue: step.rawValue - 1) ?? step
        refresh()
    }

    private func commitCurrentStep() -> Bool {
        switch step {
        case .welcome: return commitWelcome()
        case .living: return commitLiving()
        default: return true
        }
    }

    // MARK: Welcome

    /// Currency choices: the common list plus the current one.
    var currencyChoices: [String] {
        Self.commonCurrencies.contains(currency) ? Self.commonCurrencies : [currency] + Self.commonCurrencies
    }

    static var timeZoneChoices: [String] { TimeZone.knownTimeZoneIdentifiers.sorted() }

    func setCurrency(_ code: String) {
        currency = code
        write { try repository.setCurrency(code) }
        reloadStore()
    }

    func setTimeZone(_ id: String) {
        timeZoneID = id
        write { try repository.setTimeZone(id) }
        reloadStore()
    }

    private func commitWelcome() -> Bool {
        let ok = write {
            try repository.setCurrency(currency)
            try repository.setTimeZone(timeZoneID)
        }
        reloadStore()
        return ok
    }

    // MARK: Restore

    /// Holds a backup the user picked. It is not applied until `confirmRestore()`.
    func stageRestore(data: Data) {
        restoreError = nil
        pendingRestore = data
    }

    func stageRestore(error: Error) {
        pendingRestore = nil
        restoreError = error.localizedDescription
    }

    func cancelRestore() { pendingRestore = nil }

    /// Replaces everything with the staged backup. On success onboarding is complete; on failure the database is
    /// untouched and `restoreError` says why.
    @discardableResult
    func confirmRestore() -> Bool {
        guard let data = pendingRestore else { return false }
        pendingRestore = nil
        do {
            try Backup(repository: repository).import(data)
            restoreError = nil
            // A backup taken part-way through onboarding may not carry the flag.
            try? repository.setOnboardingDone(true)
            finish()
            return true
        } catch {
            restoreError = error.localizedDescription
            return false
        }
    }

    // MARK: Income and bills

    /// Deletes a source. Its rate card goes too when no other source uses it (as the editor does).
    func deleteSource(_ source: IncomeSource) {
        write {
            try repository.deleteIncomeSource(id: source.id)
            if let cardID = source.rateCardID, !(try repository.incomeSources()).contains(where: { $0.rateCardID == cardID }) {
                try repository.deleteRateCard(id: cardID)
            }
        }
        refresh()
    }

    func deleteBill(_ bill: Bill) {
        write { try repository.deleteBill(id: bill.id) }
        refresh()
    }

    var weeklyBills: Money { WeeklyEquivalent.bills(bills) }
    var weeklyIncome: Money { WeeklyEquivalent.income(store.input) }
    var hasIncome: Bool { sources.contains(where: \.isActive) }

    // MARK: Living money

    var livingIssue: String? {
        let t = livingText.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return nil }
        return MoneyParser.cents(from: t) == nil ? "Enter an amount like 150 or 150.50." : nil
    }

    var livingBudget: Money { Money(cents: MoneyParser.cents(from: livingText) ?? 0) }

    /// Saves the weekly budget. Empty text leaves the stored value alone.
    private func commitLiving() -> Bool {
        guard livingIssue == nil else { return false }
        guard !livingText.trimmingCharacters(in: .whitespaces).isEmpty else { return true }
        let amount = livingBudget
        let ok = write { try repository.setLivingBudget(amount) }
        reloadStore()
        return ok
    }

    // MARK: Data

    /// Re-reads what the editors wrote and refreshes the forecast.
    func refresh() {
        reloadStore()
        sources = (try? repository.incomeSources()) ?? []
        bills = (try? repository.bills()) ?? []
        goals = (try? repository.goals()) ?? []
        let holidays = (try? repository.holidays())?.count ?? 0
        let ranges = (try? repository.unavailableRanges())?.count ?? 0
        let limit = (try? repository.hoursLimits())?.last
        var parts: [String] = []
        if let limit { parts.append("\(limit.hoursPerFortnight) hours per fortnight") }
        if holidays > 0 { parts.append(holidays == 1 ? "1 public holiday" : "\(holidays) public holidays") }
        if ranges > 0 { parts.append(ranges == 1 ? "1 unavailable period" : "\(ranges) unavailable periods") }
        limitsSummary = parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    private func reloadStore() { try? store.reload() }

    @discardableResult
    private func write(_ body: () throws -> Void) -> Bool {
        do { try body(); return true } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    // MARK: Summary

    var summary: OnboardingSummary {
        let forecast = store.forecast
        let fmt = FigureFormatter(currency: store.settings.currency)
        let income = weeklyIncome
        let bills = weeklyBills
        let living = store.settings.livingBudget
        let surplus = income - bills - living
        let isEmpty = sources.isEmpty && self.bills.isEmpty && goals.isEmpty && living.cents == 0

        guard let goal = forecast.goal else {
            let word = surplus.cents < 0 ? "Weekly shortfall" : "Weekly surplus"
            let amount = fmt.string(Money(cents: abs(surplus.cents)))
            return OnboardingSummary(headline: "\(word) ≈ \(amount)", detail: nil, weeklyIncome: income, weeklyBills: bills,
                                     weeklyLiving: living, weeklySurplus: surplus, requiredPerPayday: nil, hasGoal: false,
                                     isEmpty: isEmpty)
        }
        let gap = forecast.gap
        let amount = fmt.string(Money(cents: abs(gap.cents)))
        let by = forecast.deadline.map { DateText.full($0) }
        let headline: String
        if gap.cents == 0 {
            headline = "Right on target"
        } else if gap.cents > 0 {
            headline = "\(amount) ahead"
        } else {
            headline = "\(amount) behind" + (by.map { " by \($0)" } ?? "")
        }
        let detail = "Projected \(fmt.string(forecast.projectedAtDeadline)) of \(fmt.string(goal.target))"
            + (gap.cents >= 0 ? (by.map { " by \($0)" } ?? "") : "")
        return OnboardingSummary(headline: headline, detail: detail, weeklyIncome: income, weeklyBills: bills,
                                 weeklyLiving: living, weeklySurplus: surplus, requiredPerPayday: forecast.requiredPerPayday,
                                 hasGoal: true, isEmpty: isEmpty)
    }

    // MARK: Finish

    /// First run: marks onboarding done through `AppModel` (the app then shows the tabs). Re-run: just finishes.
    func finish() {
        switch mode {
        case .firstRun:
            appModel.completeOnboarding()
        case .rerun:
            reloadStore()
        }
        isFinished = true
    }
}

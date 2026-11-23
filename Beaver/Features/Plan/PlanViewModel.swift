import Foundation
import BeaverCore

struct PlanSummary: Equatable {
    var hasGoal: Bool
    var projectedLabel: String
    var projectedText: String
    var gapLabel: String
    var gapText: String
    var gapTone: Tone
    var payVarianceText: String
    var payVarianceTone: Tone
    var spendingVarianceText: String
    var spendingVarianceNote: String
    var spendingVarianceTone: Tone
}

struct IncomePart: Hashable {
    var text: String
    var isActual: Bool
    /// "$600.00 actual" or "$1,109.83 est."
    var label: String { "\(text) \(isActual ? "actual" : "est.")" }
}

struct PlanRow: Identifiable, Hashable {
    var id: String { weekStart.iso }
    let weekStart: LocalDate
    let week: WeekRow
    let title: String
    let paydaysText: String
    let incomeParts: [IncomePart]
    let incomeText: String
    let billsText: String
    let livingText: String
    let surplus: Money
    let surplusText: String
    let balanceText: String
    var isDanger: Bool { week.isDanger }
    var isCurrent: Bool { week.isCurrent }
    var isDeadlineWeek: Bool { week.isDeadlineWeek }

    var accessibilityLabel: String {
        var parts = ["Week \(title)", paydaysText, "Income \(incomeText)", "Bills \(billsText)", "Living \(livingText)",
                     "Surplus \(surplusText)", "Projected balance \(balanceText)"]
        if isCurrent { parts.insert("Current week", at: 1) }
        if isDanger { parts.insert("Danger week, spending exceeds income", at: 1) }
        if isDeadlineWeek { parts.insert("Deadline week", at: 1) }
        return parts.joined(separator: ". ")
    }
}

struct PlanWeekDetail: Equatable {
    var title: String
    var pays: [PayLine]
    var bills: [BillLine]
    var incomeText: String
    var extraIncomeText: String?
    var billsText: String
    var livingText: String
    var surplusText: String
    var surplusTone: Tone
    var isDanger: Bool
    var isDeadlineWeek: Bool
}

struct PlanViewModel {
    let forecast: Forecast
    let today: LocalDate
    let fmt: FigureFormatter

    init(forecast: Forecast, settings: AppSettings, locale: Locale = .current) {
        self.forecast = forecast
        self.today = forecast.today
        self.fmt = FigureFormatter(currency: settings.currency, locale: locale)
    }

    var summary: PlanSummary {
        let hasGoal = forecast.goal != nil
        let gap = forecast.gap
        let spending = forecast.spendingVariance
        return PlanSummary(
            hasGoal: hasGoal,
            projectedLabel: hasGoal ? "Projected at deadline" : "Projected surplus",
            projectedText: fmt.string(forecast.projectedAtDeadline),
            gapLabel: hasGoal ? (gap.cents < 0 ? "Behind goal" : "Ahead of goal") : "Surplus",
            gapText: fmt.signed(gap),
            gapTone: Tone.of(gap),
            payVarianceText: fmt.signed(forecast.payVariance),
            payVarianceTone: Tone.of(forecast.payVariance),
            spendingVarianceText: fmt.signed(spending),
            spendingVarianceNote: spending.cents > 0 ? "under budget" : spending.cents < 0 ? "over budget" : "on budget",
            spendingVarianceTone: Tone.of(spending))
    }

    /// One row per forecast week. The balance is deposits so far plus the running total of weekly surplus.
    var rows: [PlanRow] {
        var balance = forecast.depositsTotal
        return forecast.weeks.map { week in
            balance += week.surplus
            var parts = week.payEvents.map { IncomePart(text: fmt.string($0.net), isActual: $0.isActual) }
            if week.extraIncome.cents != 0 { parts.append(IncomePart(text: fmt.string(week.extraIncome), isActual: false)) }
            let incomeText = parts.isEmpty ? fmt.string(.zero) : parts.map(\.label).joined(separator: " + ")
            let paydays = week.payEvents.map { DateText.weekdayDayMonth($0.payday, locale: fmt.locale) }
            return PlanRow(
                weekStart: week.weekStart, week: week,
                title: DateText.range(week.weekStart, week.weekEnd, locale: fmt.locale),
                paydaysText: paydays.isEmpty ? "No payday" : "Pay \(paydays.joined(separator: ", "))",
                incomeParts: parts, incomeText: incomeText,
                billsText: fmt.string(week.billsTotal), livingText: fmt.string(week.living),
                surplus: week.surplus, surplusText: fmt.signed(week.surplus), balanceText: fmt.string(balance))
        }
    }

    func detail(for row: PlanRow) -> PlanWeekDetail {
        let week = row.week
        return PlanWeekDetail(
            title: row.title,
            pays: week.payEvents.map { PayLine($0, fmt: fmt) },
            bills: week.bills.map { BillLine($0, today: today, fmt: fmt) },
            incomeText: row.incomeText,
            extraIncomeText: week.extraIncome.cents != 0 ? fmt.string(week.extraIncome) : nil,
            billsText: row.billsText, livingText: row.livingText, surplusText: row.surplusText,
            surplusTone: Tone.of(week.surplus), isDanger: week.isDanger, isDeadlineWeek: week.isDeadlineWeek)
    }
}

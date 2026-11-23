import SwiftUI
import BeaverCore

struct WeekView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository

    /// Called by the "Back up your data" banner. The Settings feature supplies the real export.
    var onBackUp: () -> Void = {}

    @State private var showingSettings = false
    @State private var showingAddExpense = false
    @State private var hoursLimits: [FortnightLimit] = []
    @State private var errorMessage: String?

    private var model: WeekViewModel {
        WeekViewModel(forecast: store.forecast, input: store.input, settings: store.settings,
                      hoursLimits: hoursLimits, now: Date())
    }

    var body: some View {
        let model = model
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if model.isEmpty {
                        WeekEmptyState()
                    } else {
                        banners(model)
                        HeadlineView(headline: model.headline)
                        if let bar = model.hoursBar { HoursBarCard(bar: bar, fmt: model.fmt) }
                        if let goal = model.goalCard { GoalCardView(goal: goal) }
                        thisWeekCard(model)
                        if let split = model.split { SplitCardView(split: split) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Week")
            .settingsToolbar(isPresented: $showingSettings)
            .sheet(isPresented: $showingAddExpense) { AddExpenseSheet() }
            .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorMessage ?? "") }
        }
        .task(id: store.input.shifts.count) { loadLimits() }
        .onChange(of: showingSettings) { _, showing in if !showing { loadLimits() } }
    }

    private func loadLimits() {
        hoursLimits = (try? repository.hoursLimits()) ?? []
    }

    // MARK: Banners

    @ViewBuilder private func banners(_ model: WeekViewModel) -> some View {
        ForEach(model.dangerBanners) { banner in
            BannerView(tone: .negative, symbol: "exclamationmark.triangle.fill", title: banner.title,
                       message: "Bills put you \(banner.shortfallText) that week.") {
                ForEach(banner.bills) { bill in
                    HStack {
                        Text("\(bill.name) · \(bill.dateText)")
                        Spacer()
                        Text(bill.amountText).monospacedDigit()
                    }
                    .font(.footnote)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        if let message = model.hoursWarning, let bar = model.hoursBar {
            BannerView(tone: bar.status == .over ? .negative : .warning, symbol: "clock.badge.exclamationmark",
                       title: bar.status == .over ? "Over your hours limit" : "Near your hours limit", message: message) {}
        }
        if let message = model.backupMessage {
            BannerView(tone: .warning, symbol: "externaldrive.badge.exclamationmark", title: "Back up your data",
                       message: message) {
                Button("Back up now", action: onBackUp)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        }
    }

    // MARK: This week

    private func thisWeekCard(_ model: WeekViewModel) -> some View {
        CardView(title: "This week", systemImage: "calendar") {
            VStack(alignment: .leading, spacing: 14) {
                paySection(model)
                Divider()
                billsSection(model)
                Divider()
                livingSection(model)
            }
        }
    }

    @ViewBuilder private func paySection(_ model: WeekViewModel) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Pay")
            if model.payLines.isEmpty {
                Text(model.nextPaydayText.map { "No pay this week. Next payday \($0)." } ?? "No upcoming paydays.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(model.payLines) { line in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("\(line.sourceName) · \(line.paydayText)").font(.subheadline.weight(.medium))
                        Spacer()
                        Text(line.isActual ? (line.actualText ?? "") : line.expectedText).monospacedDigit()
                            .font(.subheadline.weight(.semibold))
                    }
                    if line.isActual, let diff = line.differenceText {
                        HStack(spacing: 4) {
                            Text("Received. Expected \(line.expectedText)")
                            Spacer()
                            Text("\(diff) difference")
                                .foregroundStyle(line.differenceTone.color)
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    } else {
                        Text(line.fromTemplate ? "Estimate from your default week" : "Estimate from rostered shifts")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .monospacedDigit()
                .accessibilityElement(children: .combine)
            }
        }
    }

    @ViewBuilder private func billsSection(_ model: WeekViewModel) -> some View {
        let bills = model.bills
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionLabel(model.nextPaydayText.map { "Bills before \($0)" } ?? "Bills this week")
                Spacer()
                if !bills.isEmpty {
                    Text(model.fmt.string(model.billsTotal)).monospacedDigit().font(.subheadline.weight(.semibold))
                }
            }
            if bills.isEmpty {
                Text("No bills due before your next payday.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(bills) { bill in
                HStack(spacing: 10) {
                    Button { togglePaid(bill) } label: {
                        Image(systemName: bill.isPaid ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(bill.isPaid ? Palette.positive : .secondary)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(bill.isPaid ? "\(bill.name) paid" : "Mark \(bill.name) paid")
                    .accessibilityHint(bill.isPaid ? "Double tap to undo" : "")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(bill.name).strikethrough(bill.isPaid)
                        HStack(spacing: 4) {
                            if bill.isOverdue && !bill.isPaid {
                                Image(systemName: "exclamationmark.circle.fill")
                                Text("Overdue · \(bill.dateText)")
                            } else {
                                Text(bill.dateText)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(bill.isOverdue && !bill.isPaid ? Palette.negative : .secondary)
                    }
                    Spacer()
                    Text(bill.amountText).monospacedDigit()
                }
                .font(.subheadline)
                .accessibilityElement(children: .contain)
            }
            if bills.contains(where: { !$0.isPaid }) {
                Text("\(model.fmt.string(model.billsUnpaidTotal)) still to pay")
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        }
    }

    @ViewBuilder private func livingSection(_ model: WeekViewModel) -> some View {
        let living = model.living
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionLabel("Living budget left")
                Spacer()
                HStack(spacing: 4) {
                    if living.left.cents < 0 { Image(systemName: "exclamationmark.triangle.fill") }
                    Text(living.leftText).monospacedDigit().font(.title3.weight(.semibold))
                }
                .foregroundStyle(living.tone.color)
            }
            ProgressView(value: living.fractionSpent)
                .tint(living.left.cents < 0 ? Palette.negative : Color.accentColor)
                .accessibilityLabel("Living budget used")
                .accessibilityValue("\(living.spentText) of \(living.budgetText)")
            HStack {
                Text("\(living.spentText) spent of \(living.budgetText)")
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                Spacer()
                Button { showingAddExpense = true } label: { Label("Expense", systemImage: "plus") }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Add expense")
            }
        }
    }

    private func togglePaid(_ bill: BillLine) {
        do {
            if bill.isPaid {
                try repository.unmarkPaid(billID: bill.billID, dueDate: bill.dueDate)
            } else {
                try repository.markPaid(BillPayment(billID: bill.billID, dueDate: bill.dueDate,
                                                    paidDate: store.input.today, amount: bill.amount))
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Pieces

private struct HeadlineView: View {
    let headline: Headline

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                if let symbol = headline.tone.symbol {
                    Image(systemName: symbol).font(.title.weight(.bold)).accessibilityHidden(true)
                }
                Text(headline.text)
                    .font(.largeTitle.bold())
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("gapValue")
            }
            .foregroundStyle(headline.tone.color)
            if let detail = headline.detail {
                Text(detail).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

private struct GoalCardView: View {
    let goal: GoalCard

    var body: some View {
        CardView(title: goal.name, systemImage: "flag") {
            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: goal.progress)
                    .accessibilityLabel("Goal progress")
                    .accessibilityValue("\(Int(goal.progress * 100)) percent")
                HStack {
                    Text("\(goal.savedText) of \(goal.targetText)").monospacedDigit()
                    Spacer()
                    Text(goal.deadlineText)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                Text(goal.requiredText).font(.headline).monospacedDigit()
                Text(goal.paydaysText).font(.caption).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

private struct HoursBarCard: View {
    let bar: HoursBar
    let fmt: FigureFormatter

    private var tone: Tone {
        switch bar.status {
        case .ok: .neutral
        case .near: .warning
        case .over: .negative
        }
    }

    private var statusWord: String {
        switch bar.status {
        case .ok: "within the limit"
        case .near: "close to the limit"
        case .over: "over the limit"
        }
    }

    var body: some View {
        CardView(title: "Fortnight hours", systemImage: "clock") {
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: bar.fraction)
                    .tint(bar.status == .ok ? Color.accentColor : tone.color)
                HStack {
                    HStack(spacing: 4) {
                        if let symbol = tone.symbol { Image(systemName: symbol) }
                        Text(bar.summary).monospacedDigit().font(.subheadline.weight(.semibold))
                    }
                    .foregroundStyle(tone.color)
                    Spacer()
                    Text(DateText.range(bar.window.start, bar.window.end, locale: fmt.locale))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Fortnight hours")
            .accessibilityValue("\(bar.summary) used, \(statusWord)")
        }
    }
}

private struct SplitCardView: View {
    let split: SplitCard

    var body: some View {
        CardView(title: "Suggested split", systemImage: "chart.pie") {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Payday \(split.paydayText) · expected \(split.expectedPayText)")
                        .font(.subheadline.weight(.medium)).monospacedDigit()
                    Text(split.coversText).font(.caption).foregroundStyle(.secondary)
                }
                ForEach(split.lines) { line in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Label(line.title, systemImage: line.symbol).font(.subheadline)
                            Spacer()
                            Text(line.fundedText).monospacedDigit().font(.subheadline.weight(.semibold))
                        }
                        if let short = line.fromBufferText {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.down.to.line")
                                Text("\(short) from buffer (needs \(line.neededText))")
                            }
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Palette.warning)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Palette.warning.opacity(0.15), in: Capsule())
                            .monospacedDigit()
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
                if let buffer = split.bufferText {
                    Divider()
                    HStack {
                        Label("Left over for buffer", systemImage: "tray").font(.subheadline)
                        Spacer()
                        Text(buffer).monospacedDigit().font(.subheadline.weight(.semibold))
                    }
                    .accessibilityElement(children: .combine)
                }
                if let total = split.totalFromBufferText {
                    Text("Pay doesn't cover everything. \(total) would come from your buffer.")
                        .font(.caption).foregroundStyle(Palette.warning).monospacedDigit()
                }
            }
        }
    }
}

private struct WeekEmptyState: View {
    var body: some View {
        ContentUnavailableView {
            Label("Nothing to forecast yet", systemImage: "tray")
        } description: {
            Text("Add an income source and your regular bills in Settings, and Beaver will show how far ahead or behind you are.")
        }
        .padding(.top, 40)
    }
}

struct BannerView<Content: View>: View {
    let tone: Tone
    let symbol: String
    let title: String
    let message: String
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).foregroundStyle(tone.color).font(.title3).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(message).font(.footnote).foregroundStyle(.secondary)
                content
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(tone.color.opacity(0.5), lineWidth: 1))
    }
}

struct CardView<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: systemImage).font(.headline)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.caption.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            .accessibilityAddTraits(.isHeader)
    }
}

#Preview {
    let repository = try! Repository.inMemory()
    try! repository.loadDemoData()
    return WeekView()
        .environment(AppStore(repository: repository, clock: { _ in DemoData.referenceToday }))
        .environment(\.repository, repository)
}

#Preview("Empty") {
    let repository = try! Repository.inMemory()
    return WeekView()
        .environment(AppStore(repository: repository, clock: { _ in DemoData.referenceToday }))
        .environment(\.repository, repository)
}

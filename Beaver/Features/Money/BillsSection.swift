import SwiftUI
import BeaverCore

private enum BillsSheet: Identifiable {
    case markPaid(MoneyBillRow)
    case newBill
    case editBill(Bill)

    var id: String {
        switch self {
        case .markPaid(let row): "paid-\(row.id)"
        case .newBill: "new"
        case .editBill(let bill): "edit-\(bill.id)"
        }
    }
}

/// The Bills segment: overdue, the next 8 weeks by payday, the Monthly view and paused bills.
struct BillsSection: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var monthly = false
    @State private var sheet: BillsSheet?
    @State private var errorMessage: String?

    private var model: BillsModel { BillsModel(input: store.input, currency: store.settings.currency) }
    private var actions: MoneyActions { MoneyActions(repository: repository) }

    var body: some View {
        let model = model
        List {
            Section {
                Toggle("Monthly view", isOn: $monthly)
            } footer: {
                Text(monthly ? "Every active bill with its usual day or schedule." : "Bills due in the next 8 weeks, grouped by payday.")
            }

            if model.isEmpty {
                ContentUnavailableView("No bills yet", systemImage: "doc.text",
                                       description: Text("Add rent, phone and other regular bills to see them against your paydays."))
                    .listRowBackground(Color.clear)
            } else if monthly {
                monthlyRows(model)
            } else {
                overdueSection(model)
                ForEach(model.groups) { group in groupSection(group) }
                if model.groups.isEmpty && model.overdue.isEmpty {
                    Section { Text("Nothing due in the next 8 weeks.").foregroundStyle(.secondary) }
                }
            }
            pausedSection(model)
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { sheet = .newBill } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add bill")
            }
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .markPaid(let row): MarkPaidSheet(row: row, today: store.input.today)
            case .newBill: BillEditor(style: .sheet) { _ in }
            case .editBill(let bill): BillEditor(bill: bill, style: .sheet) { _ in } onDelete: {}
            }
        }
        .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    // MARK: Sections

    @ViewBuilder
    private func overdueSection(_ model: BillsModel) -> some View {
        let overdue = model.overdue
        if !overdue.isEmpty {
            Section {
                ForEach(overdue) { row in billRow(row, model: model) }
            } header: {
                HStack {
                    Label("Overdue", systemImage: "exclamationmark.triangle.fill")
                    Spacer()
                    Text(model.fmt.string(model.overdueTotal)).monospacedDigit()
                }
                .foregroundStyle(Palette.negative)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
            }
        }
    }

    private func groupSection(_ group: MoneyBillGroup) -> some View {
        Section {
            ForEach(group.rows) { row in billRow(row, model: model) }
        } header: {
            HStack {
                Text(group.title)
                Spacer()
                Text(group.totalText).monospacedDigit()
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(group.title), total \(group.totalText)")
            .accessibilityAddTraits(.isHeader)
        }
    }

    @ViewBuilder
    private func monthlyRows(_ model: BillsModel) -> some View {
        let rows = model.monthly
        Section("Every month") {
            ForEach(rows.filter { $0.dayOfMonth != nil }) { monthlyRow($0) }
        }
        let others = rows.filter { $0.dayOfMonth == nil }
        if !others.isEmpty {
            Section("Every few days") {
                ForEach(others) { monthlyRow($0) }
            }
        }
    }

    private func monthlyRow(_ row: MonthlyBillRow) -> some View {
        Button { sheet = .editBill(row.bill) } label: {
            HStack(spacing: 12) {
                if let day = row.dayOfMonth {
                    Text("\(day)")
                        .font(.title3.weight(.semibold))
                        .monospacedDigit()
                        .frame(minWidth: 32)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.bill.name).foregroundStyle(.primary)
                    Text(row.detail).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Text(row.amountText).monospacedDigit().foregroundStyle(.primary)
            }
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint("Edits this bill")
        .swipeActions(edge: .trailing) {
            Button { pause(row.bill, active: false) } label: { Label("Pause", systemImage: "pause.circle") }
                .tint(.orange)
        }
    }

    @ViewBuilder
    private func pausedSection(_ model: BillsModel) -> some View {
        let paused = model.pausedBills
        if !paused.isEmpty {
            Section {
                DisclosureGroup("Paused (\(paused.count))") {
                    ForEach(paused) { bill in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(bill.name)
                                Text(model.fmt.string(bill.amount)).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                            }
                            Spacer()
                            Button("Resume") { pause(bill, active: true) }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { sheet = .editBill(bill) }
                        .accessibilityElement(children: .combine)
                        .accessibilityAction(named: "Edit") { sheet = .editBill(bill) }
                        .accessibilityAction(named: "Resume") { pause(bill, active: true) }
                    }
                }
            } footer: {
                Text("Paused bills are left out of the forecast.")
            }
        }
    }

    // MARK: Rows

    private func billRow(_ row: MoneyBillRow, model: BillsModel) -> some View {
        Button {
            if !row.isPaid { sheet = .markPaid(row) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: row.isPaid ? "checkmark.circle.fill" : row.status == .overdue ? "exclamationmark.circle.fill" : "circle")
                    .foregroundStyle(row.isPaid ? Palette.positive : row.status == .overdue ? Palette.negative : .secondary)
                    .imageScale(.large)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).foregroundStyle(.primary).strikethrough(row.isPaid, color: .secondary)
                    Text(subtitle(row)).font(.footnote).foregroundStyle(row.status == .overdue ? Palette.negative : .secondary)
                }
                Spacer()
                Text(row.amountText).monospacedDigit().foregroundStyle(.primary)
            }
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(row))
        .accessibilityHint(row.isPaid ? "Swipe to unmark as paid" : "Opens the mark paid sheet")
        .swipeActions(edge: .trailing) {
            if row.isPaid {
                Button { unmark(row) } label: { Label("Unmark paid", systemImage: "arrow.uturn.backward") }
                    .tint(.orange)
            } else {
                Button { editBill(row.billID) } label: { Label("Edit bill", systemImage: "pencil") }
                    .tint(.blue)
            }
        }
        .contextMenu {
            if row.isPaid {
                Button { unmark(row) } label: { Label("Unmark paid", systemImage: "arrow.uturn.backward") }
            } else {
                Button { sheet = .markPaid(row) } label: { Label("Mark paid", systemImage: "checkmark.circle") }
            }
            Button { editBill(row.billID) } label: { Label("Edit bill", systemImage: "pencil") }
        }
    }

    private func subtitle(_ row: MoneyBillRow) -> String {
        if let paid = row.occurrence.paidDate { return "Paid \(DateText.dayMonth(paid, locale: model.fmt.locale)) · due \(row.dateText)" }
        return row.status == .overdue ? "Overdue · due \(row.dateText)" : "Due \(row.dateText)"
    }

    private func accessibilityLabel(_ row: MoneyBillRow) -> String {
        "\(row.name), \(row.amountText), \(subtitle(row))"
    }

    // MARK: Actions

    private func unmark(_ row: MoneyBillRow) {
        run { try actions.unmarkPaid(billID: row.billID, dueDate: row.dueDate) }
    }

    private func pause(_ bill: Bill, active: Bool) {
        run { try actions.setActive(bill, active) }
    }

    private func editBill(_ id: Int) {
        if let bill = store.input.bills.first(where: { $0.id == id }) { sheet = .editBill(bill) }
    }

    private func run(_ work: () throws -> Void) {
        do { try work() } catch { errorMessage = error.localizedDescription }
    }
}

#if DEBUG
#Preview("Bills") {
    let repo = MoneyPreviewSupport.repository()
    NavigationStack { BillsSection() }
        .environment(\.repository, repo)
        .environment(MoneyPreviewSupport.store(repo))
}
#endif

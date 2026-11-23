import SwiftUI
import UniformTypeIdentifiers
import BeaverCore

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?

    var body: some View {
        NavigationStack {
            SettingsContent(model: SettingsViewModel(repository: repository, didChange: { [store] in try? store?.reload() }))
                .navigationTitle("Settings")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
    }
}

private struct SettingsContent: View {
    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @State var model: SettingsViewModel
    @State private var exportDocument = BackupFile()
    @State private var exportName = "beaver-backup.json"
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var showOnboarding = false
    @State private var livingBudgetText = ""
    @State private var reminderOn = false
    @State private var reminderMessage: String?
    @FocusState private var budgetFocused: Bool

    private var input: ForecastInput? { store?.input }
    private var currency: String { model.settings.currency }

    var body: some View {
        List {
            incomeSection
            rateCardSection
            withholdingSection
            billsSection
            goalsSection
            limitsSection
            preferencesSection
            setupSection
            dataSection
            notificationsSection
            aboutSection
        }
        .listStyle(.insetGrouped)
        .onAppear { model.refresh(); livingBudgetText = MoneyParser.text(fromCents: model.settings.livingBudget.cents); reminderOn = PayReminderScheduler().isEnabled }
        .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .json, defaultFilename: exportName) { result in
            if case .failure(let error) = result { model.errorMessage = "The backup was not saved: \(error.localizedDescription)" }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            switch result {
            case .failure(let error): model.importFailedToRead(error)
            case .success(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do { model.stageImport(data: try Data(contentsOf: url), fileName: url.lastPathComponent) }
                catch { model.importFailedToRead(error) }
            }
        }
        .alert(pendingTitle, isPresented: Binding(get: { model.pending != nil }, set: { if !$0 { model.cancelPending() } })) {
            Button(pendingButton, role: .destructive) { model.confirmPending(); syncBudgetText() }
            Button("Cancel", role: .cancel) { model.cancelPending() }
        } message: {
            Text(pendingMessage)
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .alert("Done", isPresented: Binding(get: { model.statusMessage != nil }, set: { if !$0 { model.statusMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.statusMessage ?? "")
        }
        .fullScreenCover(isPresented: $showOnboarding) { OnboardingLauncher() }
    }

    private func reload() { try? store?.reload(); model.refresh() }
    private func syncBudgetText() { livingBudgetText = MoneyParser.text(fromCents: model.settings.livingBudget.cents) }

    // MARK: Income

    private var incomeSection: some View {
        Section("Income") {
            ForEach(input?.sources ?? []) { source in
                NavigationLink {
                    IncomeSourceEditor(source: source, onSave: { _ in reload() }, onDelete: reload)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(source.name)
                        Text(sourceSubtitle(source)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
                if source.kind == .hourly {
                    NavigationLink {
                        TemplatesEditor(sourceID: source.id)
                    } label: {
                        Label("Default week: \(source.name)", systemImage: "calendar.badge.clock")
                    }
                }
            }
            NavigationLink {
                IncomeSourceEditor(onSave: { _ in reload() })
            } label: {
                Label("Add income source", systemImage: "plus.circle")
            }
        }
    }

    private func sourceSubtitle(_ source: IncomeSource) -> String {
        let kind = source.kind == .hourly ? "Hourly" : "Fixed"
        let freq: String = switch source.schedule.frequency {
        case .weekly: "weekly"
        case .fortnightly: "fortnightly"
        case .monthly: "monthly"
        }
        return "\(kind), paid \(freq)" + (source.isActive ? "" : " (inactive)")
    }

    private var rateCardSection: some View {
        Section("Rate cards") {
            ForEach(input?.rateCards ?? []) { card in
                NavigationLink {
                    RateCardEditor(card: card) { _ in reload() }
                } label: {
                    Text(card.name)
                }
            }
            NavigationLink {
                RateCardEditor { _ in reload() }
            } label: {
                Label("Add rate card", systemImage: "plus.circle")
            }
        }
    }

    // MARK: Withholding

    private var withholdingSection: some View {
        Section {
            ForEach(model.tables) { table in
                if SettingsViewModel.isEditable(table) {
                    NavigationLink {
                        WithholdingTableEditor(table: table) { saved in _ = model.saveTable(saved) }
                    } label: {
                        Label(table.name, systemImage: "percent")
                    }
                } else {
                    NavigationLink {
                        BuiltinWithholdingView(table: table) { _ = model.duplicate(table) }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Label(table.name, systemImage: "lock")
                            Text("Built in, read-only").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        } header: {
            Text("Withholding tables")
        } footer: {
            Text("Verify against official sources. The built-in table may be out of date. Duplicate it to edit your own copy.")
        }
    }

    // MARK: Bills, goals, limits

    private var billsSection: some View {
        Section("Bills") {
            ForEach(input?.bills ?? []) { bill in
                NavigationLink {
                    BillEditor(bill: bill, onSave: { _ in reload() }, onDelete: reload)
                } label: {
                    HStack {
                        Label(bill.name, systemImage: EditorLabels.categorySymbol(bill.category))
                        Spacer()
                        MoneyText(cents: bill.amount.cents, currencyCode: currency).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            NavigationLink {
                BillEditor(onSave: { _ in reload() })
            } label: {
                Label("Add bill", systemImage: "plus.circle")
            }
        }
    }

    private var goalsSection: some View {
        Section("Goals") {
            let primary = input?.primaryGoal?.id
            ForEach(input?.goals ?? []) { goal in
                NavigationLink {
                    GoalEditor(goal: goal, onSave: { _ in reload() }, onDelete: reload)
                } label: {
                    HStack {
                        Text(goal.name)
                        if goal.id == primary {
                            Text("Primary").font(.caption.bold()).padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.tint.opacity(0.15), in: Capsule())
                        }
                        Spacer()
                        MoneyText(cents: goal.target.cents, currencyCode: currency).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            NavigationLink {
                GoalEditor(onSave: { _ in reload() })
            } label: {
                Label("Add goal", systemImage: "plus.circle")
            }
        }
    }

    private var limitsSection: some View {
        Section("Limits and dates") {
            NavigationLink {
                LimitsEditor { _ in reload() }
            } label: {
                Label("Hours limit, holidays, unavailable dates", systemImage: "calendar.badge.exclamationmark")
            }
        }
    }

    // MARK: Preferences

    private var preferencesSection: some View {
        Section("Preferences") {
            Picker("Currency", selection: Binding(get: { model.settings.currency }, set: { model.setCurrency($0) })) {
                ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
            }
            TimeZonePicker(selection: Binding(get: { model.settings.timeZoneID }, set: { model.setTimeZone($0) }))
            MoneyField("Weekly living budget", text: $livingBudgetText, currencyCode: currency)
                .focused($budgetFocused)
                .onChange(of: budgetFocused) { _, focused in if !focused { commitBudget() } }
                .onSubmit(commitBudget)
            if MoneyParser.cents(from: livingBudgetText) == nil {
                Text("Enter an amount like 150 or 150.50.").font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private func commitBudget() {
        if !model.setLivingBudget(text: livingBudgetText) { syncBudgetText() }
    }

    static let currencies = ["AUD", "NZD", "USD", "GBP", "EUR", "CAD", "SGD", "JPY"]

    private var setupSection: some View {
        Section {
            Button { showOnboarding = true } label: { Label("Re-run setup", systemImage: "arrow.counterclockwise") }
        } footer: {
            Text("Walk through setup again. Your data is kept.")
        }
    }

    // MARK: Data

    private var dataSection: some View {
        Section {
            Button { exportBackup() } label: { Label("Export backup", systemImage: "square.and.arrow.up") }
            Text(model.lastExportedText).font(.footnote).foregroundStyle(.secondary)
            Button { showImporter = true } label: { Label("Import backup", systemImage: "square.and.arrow.down") }
            Button { model.requestLoadDemo() } label: { Label("Load demo data", systemImage: "wand.and.stars") }
            Button(role: .destructive) { model.requestErase() } label: { Label("Erase all data", systemImage: "trash") }
                .foregroundStyle(.red)
        } header: {
            Text("Data")
        } footer: {
            Text("Export a backup regularly. If you delete the app, your data goes with it.")
        }
    }

    private func exportBackup() {
        guard let result = model.makeExport() else { return }
        exportDocument = BackupFile(data: result.data)
        exportName = result.filename
        showExporter = true
    }

    private var pendingTitle: String {
        switch model.pending {
        case .importBackup: "Replace everything?"
        case .loadDemo: "Load demo data?"
        case .erase: "Erase all data?"
        case nil: ""
        }
    }

    private var pendingButton: String {
        switch model.pending {
        case .importBackup: "Replace with backup"
        case .loadDemo: "Replace with demo"
        case .erase: "Erase everything"
        case nil: "OK"
        }
    }

    private var pendingMessage: String {
        switch model.pending {
        case .importBackup(let name):
            "Importing \(name) replaces everything in Beaver with the contents of the file. This cannot be undone."
        case .loadDemo:
            "This replaces everything in Beaver with a fictional example (\"Sam\"). Your own data will be lost unless you have a backup."
        case .erase:
            "This deletes all your data and returns Beaver to first-time setup. This cannot be undone."
        case nil: ""
        }
    }

    // MARK: Notifications

    private var notificationsSection: some View {
        Section {
            Toggle("Payday-eve reminder", isOn: Binding(get: { reminderOn }, set: { setReminder($0) }))
            if let reminderMessage { Text(reminderMessage).font(.footnote).foregroundStyle(.secondary) }
        } header: {
            Text("Notifications")
        } footer: {
            Text("A local reminder at 8pm the day before each payday in the next four weeks. Nothing leaves your device.")
        }
    }

    private func setReminder(_ on: Bool) {
        reminderOn = on
        reminderMessage = nil
        Task {
            let scheduler = PayReminderScheduler()
            if on {
                let forecast = store?.forecast ?? Forecast.compute(ForecastInput(today: LocalDate.today()))
                let ok = await scheduler.enable(forecast: forecast, settings: model.settings)
                if !ok {
                    reminderOn = false
                    reminderMessage = "Notifications are off for Beaver. Allow them in the iOS Settings app to use this."
                }
            } else {
                await scheduler.disable()
            }
        }
    }

    // MARK: About

    private var aboutSection: some View {
        Section("About") {
            LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0")
            Text("Free and open source (MIT).")
            Text("Beaver works entirely on your device. It makes no network requests and has no analytics or tracking.")
            VStack(alignment: .leading, spacing: 2) {
                Text("Acknowledgements").font(.subheadline.bold())
                Text("GRDB.swift by Gwendal Roué, MIT License.").font(.footnote).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

// MARK: - Withholding views

private struct BuiltinWithholdingView: View {
    let table: WithholdingTable
    var onDuplicate: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                Text(table.note ?? "Verify against official sources before relying on it.")
                Text("Verify against official sources.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Rows (read-only)") {
                ForEach(Array(table.rows.enumerated()), id: \.offset) { _, row in
                    WithholdingRowSummary(row: row)
                }
            }
            Section {
                Button("Duplicate to edit") { onDuplicate(); dismiss() }
            } footer: {
                Text("Makes an editable copy. The built-in table stays as it is.")
            }
        }
        .navigationTitle("Built-in table")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct WithholdingRowSummary: View {
    let row: WithholdingRow
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(row.belowDollars.map { "Below $\($0)" } ?? "No upper limit").font(.subheadline)
            Text("a \(WithholdingRowDraft.text(tenThousandths: row.aTenThousandths)), b \(WithholdingRowDraft.text(tenThousandths: row.bTenThousandths))")
                .font(.footnote).foregroundStyle(.secondary).monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }
}

struct WithholdingTableEditor: View {
    var onSave: (WithholdingTable) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var model: WithholdingEditorModel

    init(table: WithholdingTable, onSave: @escaping (WithholdingTable) -> Void) {
        self.onSave = onSave
        _model = State(initialValue: WithholdingEditorModel(table: table))
    }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Name", text: $model.name)
                if let issue = model.nameIssue { Text(issue).font(.footnote).foregroundStyle(.red) }
            }
            Section {
                ForEach($model.drafts) { $draft in
                    VStack(alignment: .leading, spacing: 6) {
                        LabeledContent("Below x ($)") {
                            TextField("No limit", text: $draft.belowX).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        }
                        LabeledContent("a") {
                            TextField("0", text: $draft.a).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        }
                        LabeledContent("b") {
                            TextField("0", text: $draft.b).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        }
                        if let issue = model.issue(for: draft) { Text(issue).font(.footnote).foregroundStyle(.red) }
                    }
                }
                .onDelete { model.removeRows(at: $0) }
                Button { model.addRow() } label: { Label("Add row", systemImage: "plus.circle") }
                if let issue = model.orderIssue { Text(issue).font(.footnote).foregroundStyle(.red) }
            } header: {
                Text("Rows")
            } footer: {
                Text("Tax = a x weekly pay - b, using the first row whose limit is above the pay. Leave the last row's limit empty. Verify against official sources.")
            }
        }
        .navigationTitle("Edit table")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    if let table = model.build() { onSave(table); dismiss() }
                }
                .disabled(!model.isValid)
            }
        }
    }
}

extension View {
    /// Adds the toolbar gear that opens the Settings sheet.
    func settingsToolbar(isPresented: Binding<Bool>) -> some View {
        toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isPresented.wrappedValue = true
                } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityLabel("Settings")
            }
        }
        .sheet(isPresented: isPresented) { SettingsView() }
    }
}

#Preview {
    let repo = try! Repository.inMemory()
    try! repo.loadDemoData()
    let model = AppModel(repository: repo)
    return SettingsView()
        .environment(model)
        .environment(model.store)
        .environment(\.repository, repo)
}

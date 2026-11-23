import SwiftUI
import BeaverCore

/// The Roster tab: a Monday to Sunday week of shifts with totals, warnings and bulk actions.
struct RosterView: View {
    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store
    /// Overrides the week shown first (previews and tests). Nil means the current week.
    var initialWeek: LocalDate?
    @State private var model: RosterViewModel?

    var body: some View {
        NavigationStack {
            Group {
                if let model {
                    RosterContent(model: model)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Roster")
        }
        .onAppear {
            if let model {
                model.today = store.input.today
                model.reload()
            } else {
                model = RosterViewModel(repository: repository, today: store.input.today, timeZone: store.settings.timeZone,
                                        currency: store.settings.currency, weekStart: initialWeek)
            }
        }
        .onChange(of: store.settings) { _, settings in
            model?.timeZone = settings.timeZone
            model?.currency = settings.currency
        }
        // The store follows the database, so edits made in Settings (sources, templates, holidays) arrive here.
        .onChange(of: store.input) { _, input in
            model?.today = input.today
            model?.reload()
        }
    }
}

private enum RosterSheet: Identifiable {
    case editor(ShiftEditorModel)
    case logPay(LogPayModel)

    var id: String {
        switch self {
        case .editor(let m): "editor-\(m.id)"
        case .logPay: "log-pay"
        }
    }
}

private enum BulkAction: Identifiable {
    case copyLastWeek, applyTemplate, saveTemplate
    var id: Self { self }
}

private struct RosterContent: View {
    @Bindable var model: RosterViewModel
    @State private var showingSettings = false
    @State private var sheet: RosterSheet?
    @State private var pendingAction: BulkAction?

    var body: some View {
        VStack(spacing: 0) {
            if !model.hourlySources.isEmpty {
                WeekPicker(model: model)
            }
            content
        }
        .overlay(alignment: .bottom) { ToastView(model: model) }
        .toolbar { toolbar }
        .settingsToolbar(isPresented: $showingSettings)
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .editor(let editor):
                ShiftEditorView(editor: editor, currency: model.currency) { model.save($0) }
            case .logPay(let pay):
                LogPayView(model: pay)
            }
        }
        .confirmationDialog(confirmTitle, isPresented: Binding(get: { pendingAction != nil }, set: { if !$0 { pendingAction = nil } }),
                            titleVisibility: .visible, presenting: pendingAction) { action in
            Button(confirmButton(action)) { run(action) }
            Button("Cancel", role: .cancel) {}
        } message: { action in
            Text(confirmMessage(action))
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.clearError() } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    // MARK: Content

    @ViewBuilder private var content: some View {
        if model.hourlySources.isEmpty {
            ContentUnavailableView {
                Label("No shift-based income yet", systemImage: "briefcase")
            } description: {
                Text("Add an hourly income source in Settings, or in onboarding, and your shifts will show up here.")
            } actions: {
                Button("Open Settings") { showingSettings = true }
                    .buttonStyle(.borderedProminent)
            }
        } else if model.weekShifts.isEmpty {
            ContentUnavailableView {
                Label("No shifts this week", systemImage: "calendar.badge.plus")
            } description: {
                Text("Add a shift, or fill the week from your template.")
            } actions: {
                Button("Add shift") { addShift() }.buttonStyle(.borderedProminent)
                Button("Apply template") { request(.applyTemplate) }.buttonStyle(.bordered)
            }
        } else {
            List {
                ForEach(model.dayGroups) { group in
                    Section {
                        ForEach(group.lines) { line in
                            ShiftRow(line: line, model: model)
                                .contentShape(Rectangle())
                                .onTapGesture { edit(line.shift) }
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) { model.delete(line.shift) } label: { Label("Delete", systemImage: "trash") }
                                }
                                .swipeActions(edge: .leading) {
                                    if line.shift.status != .worked {
                                        Button { model.setStatus(.worked, for: line.shift) } label: { Label("Worked", systemImage: "checkmark") }
                                            .tint(.green)
                                    }
                                }
                                .accessibilityAddTraits(.isButton)
                        }
                    } header: {
                        DayHeader(date: group.date, model: model)
                    }
                }
                if let w = model.fortnightWarning {
                    Section("Hours limit") {
                        WarningRow(text: RosterFormat.warningText(w, sourceName: nil, timeZone: model.timeZone), level: w.level)
                    }
                }
                ForEach(model.footers) { footer in
                    Section("\(footer.source.name) this week") { FooterRows(footer: footer, model: model) }
                }
                Section { Color.clear.frame(height: 56).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Menu {
                Button { request(.copyLastWeek) } label: { Label("Copy last week", systemImage: "doc.on.doc") }
                Button { request(.applyTemplate) } label: { Label("Apply template", systemImage: "rectangle.stack.badge.plus") }
                Button { request(.saveTemplate) } label: { Label("Save week as template", systemImage: "square.and.arrow.down") }
                Divider()
                Button { logPay() } label: { Label("Log pay received", systemImage: "banknote") }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .disabled(model.hourlySources.isEmpty && model.sourcesForPay.isEmpty)
            .accessibilityLabel("Roster actions")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { addShift() } label: { Image(systemName: "plus") }
                .disabled(model.hourlySources.isEmpty)
                .accessibilityLabel("Add shift")
        }
    }

    // MARK: Actions

    private func addShift() {
        if let editor = model.makeEditor() { sheet = .editor(editor) }
    }

    private func edit(_ shift: Shift) {
        if let editor = model.makeEditor(editing: shift) { sheet = .editor(editor) }
    }

    private func logPay() {
        sheet = .logPay(LogPayModel(roster: model))
    }

    private func request(_ action: BulkAction) {
        guard !model.hourlySources.isEmpty else { return }
        pendingAction = action
    }

    private func run(_ action: BulkAction) {
        switch action {
        case .copyLastWeek: model.copyLastWeek()
        case .applyTemplate: model.applyTemplate()
        case .saveTemplate: model.saveTemplateAction()
        }
    }

    private var confirmTitle: String {
        switch pendingAction {
        case .copyLastWeek: "Copy last week?"
        case .applyTemplate: "Apply template?"
        case .saveTemplate: "Save week as template?"
        case nil: ""
        }
    }

    private func confirmButton(_ action: BulkAction) -> String {
        switch action {
        case .copyLastWeek: "Copy shifts"
        case .applyTemplate: "Apply template"
        case .saveTemplate: "Save as template"
        }
    }

    private func confirmMessage(_ action: BulkAction) -> String {
        switch action {
        case .copyLastWeek:
            let plan = model.copyLastWeekPlan()
            return plan.hasSource ? "\(RosterViewModel.summary(added: plan.toAdd.count, plan: plan, verb: "This will add")) Nothing is overwritten."
                                  : "Last week has no shifts to copy."
        case .applyTemplate:
            let plan = model.applyTemplatePlan()
            return plan.hasSource ? "\(RosterViewModel.summary(added: plan.toAdd.count, plan: plan, verb: "This will add")) Existing shifts stay."
                                  : "There is no template yet. Save a week as a template first."
        case .saveTemplate:
            return "Your shifts this week become the default week from \(RosterFormat.shortDate(model.weekStart, in: model.timeZone)) onwards. This changes your forecast."
        }
    }
}

// MARK: Pieces

private struct WeekPicker: View {
    let model: RosterViewModel

    var body: some View {
        HStack {
            Button { model.goToPreviousWeek() } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel("Previous week")
            Spacer()
            VStack(spacing: 2) {
                Text(RosterFormat.weekTitle(model.weekStart, in: model.timeZone))
                    .font(.headline)
                    .monospacedDigit()
                if model.isThisWeek {
                    Text("This week").font(.caption).foregroundStyle(.secondary)
                } else {
                    Button("This week") { model.goToThisWeek() }.font(.caption)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button { model.goToNextWeek() } label: { Image(systemName: "chevron.right") }
                .accessibilityLabel("Next week")
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

private struct DayHeader: View {
    let date: LocalDate
    let model: RosterViewModel

    var body: some View {
        HStack {
            Text(RosterFormat.day(date, in: model.timeZone))
            if let name = model.holidayName(on: date) {
                Chip(text: name, style: .holiday, systemImage: "star.fill")
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

private struct ShiftRow: View {
    let line: ShiftLine
    let model: RosterViewModel

    var body: some View {
        let shift = line.shift
        let cancelled = shift.status == .cancelled
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(line.sourceName).font(.headline)
                Spacer()
                MoneyText(cents: line.gross.cents, currencyCode: model.currency)
                    .font(.headline)
            }
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("\(RosterFormat.time(shift.start)) \u{2013} \(RosterFormat.time(shift.end))").monospacedDigit()
                    if shift.crossesMidnight {
                        Text("finishes next day").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text("\(RosterFormat.hours(minutes: shift.paidMinutes)) paid").foregroundStyle(.secondary).monospacedDigit()
            }
            .font(.subheadline)
            FlowLayout {
                Label(shift.status.rosterTitle, systemImage: shift.status.rosterSymbol)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(statusColor)
                if !cancelled {
                    ForEach(line.chips, id: \.self) { Chip(text: $0) }
                }
                ForEach(line.holidayNames, id: \.self) { Chip(text: $0, style: .holiday, systemImage: "star.fill") }
            }
            if !shift.note.isEmpty {
                Text(shift.note).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .opacity(cancelled ? 0.6 : 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint("Opens the shift to edit")
    }

    private var statusColor: Color {
        switch line.shift.status {
        case .rostered: .secondary
        case .worked: .green
        case .cancelled: .red
        }
    }

    private var accessibilityLabel: String {
        let s = line.shift
        var parts = [line.sourceName, "\(RosterFormat.time(s.start)) to \(RosterFormat.time(s.end))"]
        if s.crossesMidnight { parts.append("finishes next day") }
        parts.append("\(RosterFormat.hours(minutes: s.paidMinutes)) paid")
        parts.append(s.status == .cancelled ? "no pay" : RosterFormat.money(line.gross, currency: model.currency))
        parts.append(s.status.rosterTitle)
        if !line.holidayNames.isEmpty { parts.append("public holiday " + line.holidayNames.joined(separator: ", ")) }
        if !s.note.isEmpty { parts.append(s.note) }
        return parts.joined(separator: ", ")
    }
}

private struct FooterRows: View {
    let footer: SourceFooter
    let model: RosterViewModel

    var body: some View {
        LabeledContent("Hours") { Text(RosterFormat.hours(minutes: footer.paidMinutes)).monospacedDigit() }
        LabeledContent("Gross") { MoneyText(cents: footer.gross.cents, currencyCode: model.currency) }
        LabeledContent("Tax") { MoneyText(cents: footer.tax.cents, currencyCode: model.currency) }
        LabeledContent("Net") { MoneyText(cents: footer.net.cents, currencyCode: model.currency).fontWeight(.semibold) }
        if let period = footer.taxPeriod {
            Text("Tax and net cover the pay period \(RosterFormat.shortDate(period.start, in: model.timeZone)) to \(RosterFormat.shortDate(period.end, in: model.timeZone)).")
                .font(.footnote).foregroundStyle(.secondary)
        }
        if let payday = footer.payday {
            Label("Paid \(RosterFormat.day(payday, in: model.timeZone))", systemImage: "calendar.badge.checkmark")
                .font(.subheadline)
        }
        ForEach(footer.warnings, id: \.limitHours) { w in
            WarningRow(text: RosterFormat.warningText(w, sourceName: footer.source.name, timeZone: model.timeZone), level: w.level)
        }
    }
}

private struct WarningRow: View {
    let text: String
    let level: HoursWarningLevel

    var body: some View {
        Label {
            Text(text).font(.subheadline)
        } icon: {
            Image(systemName: level == .hard ? "exclamationmark.octagon.fill" : "exclamationmark.triangle.fill")
        }
        .foregroundStyle(level == .hard ? Color.red : Color.orange)
        .accessibilityLabel("Warning: \(text)")
    }
}

private struct ToastView: View {
    let model: RosterViewModel

    var body: some View {
        if let toast = model.toast {
            HStack(spacing: 12) {
                Text(toast.message).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                if toast.undoable {
                    Button("Undo") { model.undoDelete() }.font(.subheadline.weight(.semibold))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .shadow(radius: 6, y: 2)
            .padding(.horizontal)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .accessibilityElement(children: .contain)
            .task(id: toast.id) {
                try? await Task.sleep(for: .seconds(toast.undoable ? 6 : 4))
                if !Task.isCancelled { withAnimation { model.dismissToast(toast.id) } }
            }
        }
    }
}

// MARK: Previews

@MainActor
private func previewRepository(weekShifts: Bool) -> Repository {
    // swiftlint:disable:next force_try
    let repo = try! Repository.inMemory()
    try? repo.loadDemoData()
    if weekShifts {
        let shifts = TemplateExpander.shifts(sourceID: DemoData.sourceID, templates: [DemoData.template], range: "2026-11-16".asLocalDate.week)
        _ = try? repo.save(shifts: shifts)
    }
    return repo
}

private extension String {
    var asLocalDate: LocalDate { LocalDate(stringLiteral: self) }
}

#Preview("Week with shifts") {
    let repo = previewRepository(weekShifts: true)
    let model = AppModel(repository: repo)
    return RosterView(initialWeek: "2026-11-16")
        .environment(model)
        .environment(model.store)
        .environment(\.repository, repo)
}

#Preview("Empty week") {
    let repo = previewRepository(weekShifts: false)
    let model = AppModel(repository: repo)
    return RosterView(initialWeek: "2026-11-16")
        .environment(model)
        .environment(model.store)
        .environment(\.repository, repo)
}

#Preview("No hourly sources") {
    let repo = try! Repository.inMemory()
    let model = AppModel(repository: repo)
    return RosterView()
        .environment(model)
        .environment(model.store)
        .environment(\.repository, repo)
}

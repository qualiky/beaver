import SwiftUI
import UniformTypeIdentifiers
import BeaverCore

/// The onboarding flow. `.firstRun` is the first launch; `.rerun` is presented from Settings, shows existing data and
/// its finish button just dismisses.
struct OnboardingView: View {
    var mode: OnboardingModel.Mode = .firstRun
    @Environment(AppModel.self) private var appModel

    var body: some View {
        OnboardingFlow(appModel: appModel, mode: mode)
    }
}

/// Which editor sheet is open.
enum OnboardingSheet: Identifiable {
    case newIncome(IncomeKind)
    case editIncome(IncomeSource)
    case usualWeek(Int)
    case newBill(BillPreset?)
    case editBill(Bill)
    case goal(Goal?)
    case limits

    var id: String {
        switch self {
        case .newIncome(let k): "new-income-\(k.rawValue)"
        case .editIncome(let s): "edit-income-\(s.id)"
        case .usualWeek(let id): "usual-week-\(id)"
        case .newBill(let p): "new-bill-\(p?.name ?? "custom")"
        case .editBill(let b): "edit-bill-\(b.id)"
        case .goal(let g): "goal-\(g?.id ?? 0)"
        case .limits: "limits"
        }
    }
}

private struct OnboardingFlow: View {
    @State private var model: OnboardingModel
    @State private var sheet: OnboardingSheet?
    @State private var showImporter = false
    @State private var usualWeekSource: IncomeSource?
    @State private var pendingUsualWeek: IncomeSource?
    @State private var showUsualWeekPrompt = false
    @Environment(\.dismiss) private var dismiss

    init(appModel: AppModel, mode: OnboardingModel.Mode) {
        _model = State(initialValue: OnboardingModel(appModel: appModel, mode: mode))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                progressHeader
                stepContent
                    .id(model.step)
                    .transition(.opacity)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { navigationBar }
            .toolbar(.hidden, for: .navigationBar)
            .background(Color(.systemGroupedBackground))
        }
        // The editors read these from the environment, so they work wherever this flow is shown.
        .environment(model.appModel.store)
        .environment(\.repository, model.appModel.repository)
        .sheet(item: $sheet, onDismiss: sheetDismissed) { sheetContent($0) }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            handleImport(result)
        }
        .confirmationDialog("Replace everything with this backup?", isPresented: Binding(
            get: { model.pendingRestore != nil },
            set: { if !$0 { model.cancelRestore() } }
        ), titleVisibility: .visible) {
            Button("Replace and restore", role: .destructive) { model.confirmRestore() }
            Button("Cancel", role: .cancel) { model.cancelRestore() }
        } message: {
            Text("Anything already in Beaver on this iPhone is replaced by the backup, and setup is skipped.")
        }
        .alert("Couldn't restore", isPresented: Binding(
            get: { model.restoreError != nil },
            set: { if !$0 { model.restoreError = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.restoreError ?? "")
        }
        .alert("Something went wrong", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .alert("Set your usual week (optional)", isPresented: $showUsualWeekPrompt, presenting: usualWeekSource) { source in
            Button("Set usual week") { sheet = .usualWeek(source.id) }
            Button("Not now", role: .cancel) {}
        } message: { _ in
            Text("Add the shifts you normally work, so Beaver can forecast your pay before you roster them.")
        }
        .onChange(of: model.isFinished) { _, done in
            if done, model.mode == .rerun { dismiss() }
        }
        .animation(.default, value: model.step)
    }

    // MARK: Chrome

    private var progressHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Step \(model.stepNumber) of \(model.stepCount)")
                Spacer()
                Text(model.step.title)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(.secondary)
            ProgressView(value: model.progress)
                .accessibilityHidden(true)
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(model.stepNumber) of \(model.stepCount), \(model.step.title)")
    }

    private var navigationBar: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) { backButton; skipButton; nextButton }
            VStack(spacing: 8) { nextButton; HStack { backButton; skipButton } }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private var backButton: some View {
        Button("Back") { model.back() }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(model.isFirst)
    }

    @ViewBuilder
    private var skipButton: some View {
        if model.canSkip {
            Button("Skip") { model.skip() }
                .buttonStyle(.borderless)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .accessibilityHint("Skips this step. You can set it up later.")
        } else {
            Spacer(minLength: 0)
        }
    }

    private var nextButton: some View {
        Button {
            if model.isLast { model.finish() } else { model.next() }
        } label: {
            Text(model.isLast ? (model.mode == .rerun ? "Done" : "Start using Beaver") : "Next")
                .frame(minWidth: 80)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!model.canAdvance)
    }

    // MARK: Content

    @ViewBuilder
    private var stepContent: some View {
        switch model.step {
        case .welcome:
            WelcomeStep(model: model, showImporter: $showImporter)
        case .income:
            IncomeStep(model: model, present: { sheet = $0 })
        case .bills:
            BillsStep(model: model, present: { sheet = $0 })
        case .living:
            LivingStep(model: model)
        case .goal:
            GoalStep(model: model, present: { sheet = $0 })
        case .limits:
            LimitsStep(model: model, present: { sheet = $0 })
        case .summary:
            SummaryStep(model: model)
        }
    }

    @ViewBuilder
    private func sheetContent(_ item: OnboardingSheet) -> some View {
        switch item {
        case .newIncome(let kind):
            if kind == .hourly {
                IncomeSourceEditor(style: .sheet) { saved in
                    pendingUsualWeek = saved
                }
            } else {
                // A blank salary draft, so the editor opens on "Fixed amount". Id 0 is saved as a new source.
                IncomeSourceEditor(source: Self.salaryDraft, style: .sheet) { _ in }
            }
        case .editIncome(let source):
            IncomeSourceEditor(source: source, style: .sheet, onSave: { _ in }, onDelete: {})
        case .usualWeek(let id):
            TemplatesEditor(sourceID: id, style: .sheet)
        case .newBill(let preset):
            BillEditor(preset: preset, style: .sheet) { _ in }
        case .editBill(let bill):
            BillEditor(bill: bill, style: .sheet, onSave: { _ in }, onDelete: {})
        case .goal(let goal):
            GoalEditor(goal: goal, style: .sheet, onSave: { _ in }, onDelete: {})
        case .limits:
            LimitsEditor(style: .sheet) { _ in }
        }
    }

    private func sheetDismissed() {
        model.refresh()
        if let source = pendingUsualWeek {
            pendingUsualWeek = nil
            usualWeekSource = source
            showUsualWeekPrompt = true
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        switch result {
        case .failure(let error):
            model.stageRestore(error: error)
        case .success(let url):
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                model.stageRestore(data: try Data(contentsOf: url))
            } catch {
                model.stageRestore(error: error)
            }
        }
    }

    private static var salaryDraft: IncomeSource {
        IncomeSource(id: 0, name: "", kind: .fixed, schedule: .weekly(payWeekday: .wednesday, lagWeeks: 1))
    }
}

#if DEBUG
@MainActor
private func previewModel(demo: Bool) -> AppModel {
    let repo = try! Repository.inMemory()
    if demo { try? repo.loadDemoData(); try? repo.setOnboardingDone(false) }
    return AppModel(repository: repo)
}

#Preview("First run, empty") {
    let app = previewModel(demo: false)
    return OnboardingView().environment(app)
}

#Preview("Re-run, demo data") {
    let app = previewModel(demo: true)
    return OnboardingView(mode: .rerun).environment(app)
}
#endif

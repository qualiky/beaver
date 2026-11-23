import SwiftUI

/// Presents the export flow (build the backup, then the system save sheet) when `isPresented` becomes true.
/// Used by the Week tab's "Back up now" banner.
private struct BackupExportFlow: ViewModifier {
    @Binding var isPresented: Bool
    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @State private var document = BackupFile()
    @State private var filename = "beaver-backup.json"
    @State private var showExporter = false
    @State private var errorMessage: String?

    func body(content: Content) -> some View {
        content
            .onChange(of: isPresented) { _, now in
                guard now else { return }
                isPresented = false
                let model = SettingsViewModel(repository: repository, didChange: { [store] in try? store?.reload() })
                if let result = model.makeExport() {
                    document = BackupFile(data: result.data)
                    filename = result.filename
                    showExporter = true
                } else {
                    errorMessage = model.errorMessage
                }
            }
            .fileExporter(isPresented: $showExporter, document: document, contentType: .json, defaultFilename: filename) { result in
                if case .failure(let error) = result { errorMessage = "The backup was not saved: \(error.localizedDescription)" }
            }
            .alert("Backup failed", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
    }
}

extension View {
    func backupExportFlow(isPresented: Binding<Bool>) -> some View {
        modifier(BackupExportFlow(isPresented: isPresented))
    }
}

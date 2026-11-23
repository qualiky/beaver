import Foundation
import Observation
import SwiftUI

@MainActor
@Observable
final class AppModel {
    let repository: Repository
    let store: AppStore
    /// Set when the on-disk database could not be opened and the app fell back to memory.
    private(set) var startupError: String?

    /// Whether onboarding is finished. Backed by the persisted `onboardingDone` setting.
    var onboardingDone: Bool { store.settings.onboardingDone }

    init(repository: Repository, startupError: String? = nil) {
        self.repository = repository
        self.store = AppStore(repository: repository)
        self.startupError = startupError
    }

    /// An empty in-memory app, for previews and tests.
    convenience init() {
        // An in-memory database cannot fail to open.
        self.init(repository: try! Repository.inMemory())
    }

    /// The real app: `beaver.sqlite` in Application Support, falling back to memory if it can not be opened.
    static func live() -> AppModel {
        do {
            return AppModel(repository: Repository(database: try .makePersistent()))
        } catch {
            return AppModel(repository: try! Repository.inMemory(), startupError: error.localizedDescription)
        }
    }

    func completeOnboarding() {
        do {
            try repository.setOnboardingDone(true)
            try store.reload()
        } catch {
            startupError = error.localizedDescription
        }
    }
}

private struct RepositoryKey: EnvironmentKey {
    // Previews that forget to inject a repository get an empty in-memory one.
    static let defaultValue: Repository = try! Repository.inMemory()
}

extension EnvironmentValues {
    var repository: Repository {
        get { self[RepositoryKey.self] }
        set { self[RepositoryKey.self] = newValue }
    }
}

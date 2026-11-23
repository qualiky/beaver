import Observation

@MainActor
@Observable
final class AppModel {
    // TODO(Milestone 2, data layer): persist this flag in the database instead of memory.
    var onboardingDone = false

    init() {}
}

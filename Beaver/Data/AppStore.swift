import Foundation
import Observation
import BeaverCore

/// The live, observable state the UI reads: settings, the engine input and the computed forecast.
/// It re-computes whenever the database changes. Views read it from the SwiftUI environment and
/// never touch GRDB.
@MainActor
@Observable
final class AppStore {
    private(set) var settings: AppSettings
    private(set) var input: ForecastInput
    private(set) var forecast: Forecast
    /// The last error from observing or loading, for a banner. Nil when healthy.
    private(set) var lastError: String?

    /// What-if: extra net income added to every forecast week. Not saved.
    var extraWeeklyIncome: Money = .zero {
        didSet { if extraWeeklyIncome != oldValue { recompute() } }
    }

    @ObservationIgnored private let repository: Repository
    @ObservationIgnored private let clock: @Sendable (TimeZone) -> LocalDate

    /// Reads the current state synchronously so the first frame is correct (no onboarding flash).
    init(repository: Repository, clock: @escaping @Sendable (TimeZone) -> LocalDate = { LocalDate.today(in: $0) }) {
        self.repository = repository
        self.clock = clock
        let loaded = try? repository.settings()
        let settings = loaded ?? AppSettings()
        let today = clock(settings.timeZone)
        let input = (try? repository.forecastInput(today: today)) ?? ForecastInput(today: today)
        self.settings = settings
        self.input = input
        self.forecast = Forecast.compute(input)
    }

    /// Follows the database until the calling task is cancelled. Run it from `.task` on the root view.
    func run() async {
        let stream = repository.observeSnapshot(today: clock)
        do {
            for try await snapshot in stream { apply(snapshot) }
        } catch {
            if !Task.isCancelled { lastError = error.localizedDescription }
        }
    }

    /// Synchronous refresh from the database (used after writes in tests, and as a fallback).
    func reload() throws {
        let settings = try repository.settings()
        apply(StoreSnapshot(settings: settings, input: try repository.forecastInput(today: clock(settings.timeZone))))
    }

    /// Call when the app returns to the foreground so a new day moves the forecast window.
    func refreshToday() {
        let today = clock(settings.timeZone)
        guard today != input.today else { return }
        input.today = today
        recompute()
    }

    private func apply(_ snapshot: StoreSnapshot) {
        settings = snapshot.settings
        input = snapshot.input
        lastError = nil
        recompute()
    }

    private func recompute() {
        var effective = input
        effective.extraWeeklyIncome = extraWeeklyIncome
        forecast = Forecast.compute(effective)
    }
}

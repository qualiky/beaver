import Foundation
import SwiftUI
import UserNotifications
import BeaverCore

/// One local "payday is tomorrow" notification.
struct PayReminder: Equatable, Sendable {
    /// The payday the reminder is about.
    var payday: LocalDate
    /// The day the reminder fires (the day before the payday).
    var fireDate: LocalDate
    /// Fires at 20:00 local time.
    var hour: Int = 20
    var title: String
    var body: String

    var identifier: String { PayReminderPlanner.identifierPrefix + payday.iso }
}

/// Pure planning of payday-eve reminders. No `UNUserNotificationCenter` here, so it is unit-tested directly.
enum PayReminderPlanner {
    static let identifierPrefix = "beaver.payday-eve."
    static let horizonDays = 28

    /// Reminders for every distinct payday from tomorrow through four weeks from today.
    /// A reminder whose 20:00 time has already passed (`nowMinutes` is minutes since local midnight) is skipped.
    static func reminders(forecast: Forecast, nowMinutes: Int = 0, formatter: FigureFormatter) -> [PayReminder] {
        let today = forecast.today
        let events = forecast.payEvents
        let paydays = Array(Set(events.map(\.payday))).sorted()
        let bills = forecast.weeks.flatMap(\.bills)
        var out: [PayReminder] = []
        for (index, payday) in paydays.enumerated() {
            let offset = today.days(until: payday)
            guard offset >= 1, offset <= horizonDays else { continue }
            let fire = payday.adding(days: -1)
            if fire == today, nowMinutes >= 20 * 60 { continue }
            let expected = events.filter { $0.payday == payday }.map(\.net).total()
            let nextPayday = index + 1 < paydays.count ? paydays[index + 1] : payday.adding(days: 7)
            let due = bills.filter { $0.paidDate == nil && $0.dueDate >= payday && $0.dueDate < nextPayday }
                .map(\.plannedAmount).total()
            out.append(PayReminder(
                payday: payday, fireDate: fire, title: "Pay day tomorrow",
                body: "Pay day tomorrow: expected \(formatter.string(expected)). Bills due before next payday: \(formatter.string(due))."))
        }
        return out
    }
}

/// The slice of `UNUserNotificationCenter` the scheduler needs.
protocol ReminderCenter: Sendable {
    func requestAuthorization() async -> Bool
    func isAuthorized() async -> Bool
    func replace(_ reminders: [PayReminder], timeZone: TimeZone) async
    func removeAll() async
}

/// Local notifications only. Nothing leaves the device.
struct SystemReminderCenter: ReminderCenter {
    func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func isAuthorized() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return s == .authorized || s == .provisional
    }

    func replace(_ reminders: [PayReminder], timeZone: TimeZone) async {
        let center = UNUserNotificationCenter.current()
        await removeAll()
        for r in reminders {
            let content = UNMutableNotificationContent()
            content.title = r.title
            content.body = r.body
            content.sound = .default
            var parts = DateComponents()
            parts.timeZone = timeZone
            parts.year = r.fireDate.year
            parts.month = r.fireDate.month
            parts.day = r.fireDate.day
            parts.hour = r.hour
            parts.minute = 0
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: r.identifier, content: content, trigger: trigger))
        }
    }

    func removeAll() async {
        let center = UNUserNotificationCenter.current()
        let ids = await center.pendingNotificationRequests().map(\.identifier)
            .filter { $0.hasPrefix(PayReminderPlanner.identifierPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }
}

/// Turns the reminder on or off and keeps the scheduled notifications in step with the forecast.
@MainActor
struct PayReminderScheduler {
    static let defaultsKey = "payReminderEnabled"
    var center: any ReminderCenter = SystemReminderCenter()
    var defaults: UserDefaults = .standard

    var isEnabled: Bool { defaults.bool(forKey: Self.defaultsKey) }

    /// Asks permission only now. Returns false (and stays off) when the user declines.
    func enable(forecast: Forecast, settings: AppSettings) async -> Bool {
        guard await center.requestAuthorization() else {
            defaults.set(false, forKey: Self.defaultsKey)
            return false
        }
        defaults.set(true, forKey: Self.defaultsKey)
        await reschedule(forecast: forecast, settings: settings)
        return true
    }

    func disable() async {
        defaults.set(false, forKey: Self.defaultsKey)
        await center.removeAll()
    }

    /// Replaces the pending reminders from the current forecast. Does nothing when the reminder is off.
    func reschedule(forecast: Forecast, settings: AppSettings, now: Date = Date()) async {
        guard isEnabled else { return }
        guard await center.isAuthorized() else { return }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = settings.timeZone
        let c = calendar.dateComponents([.hour, .minute], from: now)
        let minutes = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        let reminders = PayReminderPlanner.reminders(
            forecast: forecast, nowMinutes: minutes, formatter: FigureFormatter(currency: settings.currency))
        await center.replace(reminders, timeZone: settings.timeZone)
    }
}

/// Reschedules whenever the forecast changes, from anywhere in the app.
private struct PayReminderSync: ViewModifier {
    @Environment(AppStore.self) private var store: AppStore?

    func body(content: Content) -> some View {
        content
            .task(id: store?.forecast) {
                guard let store else { return }
                await PayReminderScheduler().reschedule(forecast: store.forecast, settings: store.settings)
            }
    }
}

extension View {
    /// Keeps payday-eve reminders in step with the forecast (a no-op while the reminder is off).
    func payReminderSync() -> some View { modifier(PayReminderSync()) }
}

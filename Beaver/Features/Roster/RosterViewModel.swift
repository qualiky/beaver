import Foundation
import Observation
import BeaverCore

/// One shift as shown in the week list.
struct ShiftLine: Identifiable, Equatable {
    var shift: Shift
    var sourceName: String
    var pay: ShiftPayResult?
    var holidayNames: [String]
    /// Chip labels for the bands the shift touches, e.g. ["Eve", "Night"].
    var chips: [String]

    var id: Int { shift.id }
    /// Gross counted for this shift (cancelled shifts earn nothing).
    var gross: Money { shift.status == .cancelled ? .zero : (pay?.gross ?? .zero) }
}

/// The shifts that start on one date.
struct DayGroup: Identifiable, Equatable {
    var date: LocalDate
    var lines: [ShiftLine]
    var id: LocalDate { date }
}

/// Week totals for one income source.
struct SourceFooter: Identifiable, Equatable {
    var source: IncomeSource
    var paidMinutes: Int
    var gross: Money
    var tax: Money
    var net: Money
    var payday: LocalDate?
    /// Set when tax and net cover a longer pay period (fortnightly pay) than the week shown.
    var taxPeriod: DateRange?
    var warnings: [HoursWarning]

    var id: Int { source.id }
}

/// What a bulk action (copy last week, apply template) would do, worked out before anything is saved.
struct BulkPlan: Equatable {
    var toAdd: [Shift] = []
    var skippedUnavailable = 0
    var skippedExisting = 0
    /// False when there was nothing to copy from (no shifts last week, or no template).
    var hasSource = true
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var message: String
    var undoable = false
}

/// The Roster tab's state and actions. All reads and writes go through `Repository`.
@MainActor
@Observable
final class RosterViewModel {
    let repository: Repository
    var timeZone: TimeZone
    var currency: String
    var today: LocalDate
    private(set) var weekStart: LocalDate

    private(set) var sources: [IncomeSource] = []
    private(set) var rateCards: [RateCard] = []
    private(set) var templates: [Template] = []
    private(set) var holidays: [Holiday] = []
    private(set) var unavailable: [UnavailableRange] = []
    private(set) var limits: [FortnightLimit] = []
    private(set) var tables: [WithholdingTable] = []
    private(set) var pays: [PayReceived] = []
    /// Every shift from two weeks before the week shown to three weeks after it.
    private(set) var loadedShifts: [Shift] = []

    private(set) var toast: Toast?
    private(set) var errorMessage: String?
    @ObservationIgnored private var lastDeleted: Shift?

    init(repository: Repository, today: LocalDate, timeZone: TimeZone = .current, currency: String = "AUD", weekStart: LocalDate? = nil) {
        self.repository = repository
        self.today = today
        self.timeZone = timeZone
        self.currency = currency
        self.weekStart = (weekStart ?? today).mondayOfWeek
        reload()
    }

    // MARK: Loading

    func reload() {
        do {
            sources = try repository.incomeSources()
            rateCards = try repository.rateCards()
            templates = try repository.templates()
            holidays = try repository.holidays()
            unavailable = try repository.unavailableRanges()
            limits = try repository.hoursLimits()
            tables = try repository.withholdingTables()
            pays = try repository.paysReceived()
            loadedShifts = try repository.shifts(in: DateRange(start: weekStart.adding(days: -14), end: weekStart.adding(days: 20)))
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: Week navigation

    var week: DateRange { weekStart.week }
    var isThisWeek: Bool { weekStart == today.mondayOfWeek }

    func goToPreviousWeek() { setWeek(weekStart.adding(days: -7)) }
    func goToNextWeek() { setWeek(weekStart.adding(days: 7)) }
    func goToThisWeek() { setWeek(today.mondayOfWeek) }

    func setWeek(_ monday: LocalDate) {
        weekStart = monday.mondayOfWeek
        reload()
    }

    // MARK: Derived data

    var holidayDates: Set<LocalDate> { Set(holidays.map(\.date)) }

    /// Active hourly sources: the only ones that can have shifts.
    var hourlySources: [IncomeSource] { sources.filter { $0.kind == .hourly && $0.isActive } }

    func source(id: Int) -> IncomeSource? { sources.first { $0.id == id } }

    func rateCard(for source: IncomeSource?) -> RateCard? {
        guard let id = source?.rateCardID else { return nil }
        return rateCards.first { $0.id == id }
    }

    func holidayName(on date: LocalDate) -> String? { holidays.first { $0.date == date }?.name }

    var weekShifts: [Shift] {
        loadedShifts.filter { week.contains($0.date) }
    }

    private func shifts(in range: DateRange, sourceID: Int) -> [Shift] {
        loadedShifts.filter { $0.sourceID == sourceID && range.contains($0.date) }
    }

    func line(for shift: Shift) -> ShiftLine {
        let source = source(id: shift.sourceID)
        let pay = rateCard(for: source).map { ShiftPay.calculate(shift: shift, rateCard: $0, holidays: holidayDates) }
        var names: [String] = []
        if let name = holidayName(on: shift.date) { names.append(name) }
        if shift.crossesMidnight, let name = holidayName(on: shift.endDate), !names.contains(name) { names.append(name) }
        return ShiftLine(shift: shift, sourceName: source?.name ?? "Unknown source", pay: pay,
                         holidayNames: names, chips: (pay?.bands ?? []).map { RosterFormat.chip(forBand: $0.band) })
    }

    /// Shifts for the week shown, grouped by start date (only days that have shifts).
    var dayGroups: [DayGroup] {
        let byDate = Dictionary(grouping: weekShifts, by: \.date)
        return week.dates.compactMap { date in
            guard let shifts = byDate[date], !shifts.isEmpty else { return nil }
            let sorted = shifts.sorted { ($0.start, $0.id) < ($1.start, $1.id) }
            return DayGroup(date: date, lines: sorted.map(line(for:)))
        }
    }

    /// One footer per source that has a shift this week, in source order.
    var footers: [SourceFooter] {
        sources.compactMap { source in
            guard source.kind == .hourly, !shifts(in: week, sourceID: source.id).isEmpty else { return nil }
            return footer(for: source)
        }
    }

    private func footer(for source: IncomeSource) -> SourceFooter {
        let card = rateCard(for: source)
        let weekShifts = shifts(in: week, sourceID: source.id)
        let payday = source.schedule.payday(forWorkDate: weekStart)
        let period = payday.flatMap { source.schedule.workPeriod(forPayday: $0) } ?? week
        let weekPay = PeriodPay.calculate(shifts: weekShifts, rateCard: card, holidays: holidayDates,
                                          taxMethod: source.taxMethod, frequency: .weekly, tables: tables)
        var gross = weekPay.gross, tax = weekPay.tax, net = weekPay.net
        var taxPeriod: DateRange?
        if source.schedule.frequency != .weekly || period != week {
            // Tax is worked out once on the whole pay period, so show the period's tax and net.
            let periodPay = PeriodPay.calculate(shifts: shifts(in: period, sourceID: source.id), rateCard: card, holidays: holidayDates,
                                                taxMethod: source.taxMethod, frequency: source.schedule.frequency, tables: tables)
            gross = weekPay.gross
            tax = periodPay.tax
            net = periodPay.net
            taxPeriod = period
        }
        var warnings: [HoursWarning] = []
        if let w = HoursLimits.weeklyWarning(source: source, weekStart: weekStart, shifts: loadedShifts) { warnings.append(w) }
        return SourceFooter(source: source, paidMinutes: weekPay.paidMinutes, gross: gross, tax: tax, net: net,
                            payday: payday, taxPeriod: taxPeriod, warnings: warnings)
    }

    /// The fortnight limit warning (all sources together) for the fortnight that holds the week shown.
    var fortnightWarning: HoursWarning? {
        guard let limit = limits.filter({ $0.effectiveFrom <= week.end }).max(by: { $0.effectiveFrom < $1.effectiveFrom }) else { return nil }
        return HoursLimits.fortnightWarning(limit: limit, date: weekStart, shifts: loadedShifts)
    }

    // MARK: Add, edit, delete

    /// The last shift entered for each hourly source (highest id), used for fast-path defaults.
    func lastShiftsBySource() -> [Int: Shift] {
        guard let all = try? repository.shifts() else { return [:] }
        var out: [Int: Shift] = [:]
        for s in all where s.id >= (out[s.sourceID]?.id ?? 0) { out[s.sourceID] = s }
        return out
    }

    func makeEditor(editing shift: Shift? = nil) -> ShiftEditorModel? {
        let hourly = hourlySources
        guard !hourly.isEmpty else { return nil }
        let defaultDate = week.contains(today) ? today : weekStart
        return ShiftEditorModel(editing: shift, defaultDate: defaultDate, sources: hourly, rateCards: rateCards,
                                holidays: holidays, lastShifts: lastShiftsBySource(), timeZone: timeZone)
    }

    @discardableResult
    func save(_ shift: Shift) -> Bool {
        do {
            try repository.save(shift)
            reload()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func setStatus(_ status: ShiftStatus, for shift: Shift) {
        var copy = shift
        copy.status = status
        save(copy)
    }

    /// Deletes a shift and shows a toast with an Undo button.
    func delete(_ shift: Shift) {
        do {
            try repository.deleteShift(id: shift.id)
            lastDeleted = shift
            toast = Toast(message: "Shift deleted", undoable: true)
            reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func undoDelete() {
        guard let shift = lastDeleted else { return }
        lastDeleted = nil
        toast = nil
        save(shift)
    }

    func dismissToast(_ id: UUID? = nil) {
        guard id == nil || toast?.id == id else { return }
        toast = nil
        lastDeleted = nil
    }

    func clearError() { errorMessage = nil }

    func show(_ message: String) { toast = Toast(message: message) }

    // MARK: Copy last week and apply template

    private func key(_ s: Shift) -> String { "\(s.sourceID)|\(s.date.iso)|\(s.start.iso)" }

    private func isUnavailable(_ date: LocalDate) -> Bool { unavailable.contains { $0.range.contains(date) } }

    /// Last week's shifts (cancelled ones left out) moved forward seven days. Dates the user is unavailable are skipped,
    /// and a shift is never added when one already exists for the same source, date and start.
    func copyLastWeekPlan() -> BulkPlan {
        let hourlyIDs = Set(hourlySources.map(\.id))
        let lastWeek = weekStart.adding(days: -7).week
        let previous = loadedShifts.filter { lastWeek.contains($0.date) && hourlyIDs.contains($0.sourceID) && $0.status != .cancelled }
        var plan = BulkPlan(hasSource: !previous.isEmpty)
        var seen = Set(weekShifts.map(key))
        for old in previous.sorted(by: { ($0.date, $0.start, $0.id) < ($1.date, $1.start, $1.id) }) {
            let new = Shift(sourceID: old.sourceID, date: old.date.adding(days: 7), start: old.start, end: old.end,
                            breakMinutes: old.breakMinutes, status: .rostered, note: old.note)
            if isUnavailable(new.date) { plan.skippedUnavailable += 1; continue }
            if !seen.insert(key(new)).inserted { plan.skippedExisting += 1; continue }
            plan.toAdd.append(new)
        }
        return plan
    }

    /// Shifts from the template in force for the week shown, with the same skip rules as copy.
    func applyTemplatePlan() -> BulkPlan {
        var plan = BulkPlan(hasSource: false)
        var seen = Set(weekShifts.map(key))
        for source in hourlySources {
            guard TemplateExpander.template(for: source.id, weekStart: weekStart, in: templates) != nil else { continue }
            plan.hasSource = true
            let all = TemplateExpander.shifts(sourceID: source.id, templates: templates, range: week)
            let kept = TemplateExpander.shifts(sourceID: source.id, templates: templates, range: week, unavailable: unavailable)
            plan.skippedUnavailable += all.count - kept.count
            for shift in kept {
                if seen.insert(key(shift)).inserted { plan.toAdd.append(shift) } else { plan.skippedExisting += 1 }
            }
        }
        return plan
    }

    @discardableResult
    func apply(_ plan: BulkPlan) -> Int {
        guard !plan.toAdd.isEmpty else { return 0 }
        do {
            try repository.save(shifts: plan.toAdd)
            reload()
            return plan.toAdd.count
        } catch {
            errorMessage = error.localizedDescription
            return 0
        }
    }

    static func summary(added: Int, plan: BulkPlan, verb: String) -> String {
        var text = added == 0 ? "No shifts added." : "\(verb) \(added) \(added == 1 ? "shift" : "shifts")."
        var skipped: [String] = []
        if plan.skippedUnavailable > 0 { skipped.append("\(plan.skippedUnavailable) on unavailable dates") }
        if plan.skippedExisting > 0 { skipped.append("\(plan.skippedExisting) already on the roster") }
        if !skipped.isEmpty { text += " Skipped " + skipped.joined(separator: " and ") + "." }
        return text
    }

    func copyLastWeek() {
        let plan = copyLastWeekPlan()
        if !plan.hasSource { show("Nothing to copy: last week has no shifts."); return }
        let added = apply(plan)
        show(Self.summary(added: added, plan: plan, verb: "Copied"))
    }

    func applyTemplate() {
        let plan = applyTemplatePlan()
        if !plan.hasSource { show("No template yet. Use Save week as template first."); return }
        let added = apply(plan)
        show(Self.summary(added: added, plan: plan, verb: "Added"))
    }

    // MARK: Save week as template

    /// Saves the non-cancelled shifts of the week shown as each source's template, effective from this Monday.
    /// A template already saved for the same source and Monday is replaced rather than duplicated.
    /// Returns the number of shifts saved.
    @discardableResult
    func saveWeekAsTemplate() -> Int {
        var saved = 0
        do {
            for source in hourlySources {
                let shifts = shifts(in: week, sourceID: source.id).filter { $0.status != .cancelled }
                guard !shifts.isEmpty else { continue }
                let existing = templates.first { $0.sourceID == source.id && $0.effectiveFrom == weekStart }
                let rows = shifts.sorted { ($0.date, $0.start) < ($1.date, $1.start) }.map {
                    TemplateShift(weekday: $0.date.weekday, start: $0.start, end: $0.end, breakMinutes: $0.breakMinutes)
                }
                let name = "Week of \(RosterFormat.shortDate(weekStart, in: timeZone))"
                try repository.save(Template(id: existing?.id ?? 0, sourceID: source.id, name: name, effectiveFrom: weekStart, shifts: rows))
                saved += rows.count
            }
            reload()
        } catch {
            errorMessage = error.localizedDescription
            return 0
        }
        return saved
    }

    func saveTemplateAction() {
        let count = saveWeekAsTemplate()
        if count == 0 { show("Nothing to save: this week has no shifts.") } else {
            show("Saved \(count) \(count == 1 ? "shift" : "shifts") as the template from \(RosterFormat.shortDate(weekStart, in: timeZone)).")
        }
    }

    // MARK: Expected pay and logged pay

    /// Expected net for a payday: the shifts in its work period (or the template when the period has none),
    /// taxed once on the period gross. Fixed sources use their amount.
    func expectedNet(for source: IncomeSource, payday: LocalDate) -> Money {
        switch source.kind {
        case .fixed:
            let amount = source.fixedAmount ?? .zero
            if source.amountIsNet { return amount }
            return amount - source.taxMethod.tax(onGross: amount, frequency: source.schedule.frequency, tables: tables)
        case .hourly:
            guard let period = source.schedule.workPeriod(forPayday: payday) else { return .zero }
            var shifts = (try? repository.shifts(in: period, sourceID: source.id)) ?? []
            if shifts.isEmpty {
                shifts = TemplateExpander.shifts(sourceID: source.id, templates: templates, range: period, unavailable: unavailable)
            }
            return PeriodPay.calculate(shifts: shifts, rateCard: rateCard(for: source), holidays: holidayDates,
                                       taxMethod: source.taxMethod, frequency: source.schedule.frequency, tables: tables).net
        }
    }

    /// Actual minus expected. Positive means paid more than expected.
    static func difference(actual: Money, expected: Money) -> Money { actual - expected }

    /// Paydays to choose from for a source, newest first: the next payday within a week and earlier ones.
    func paydays(for source: IncomeSource, count: Int = 12) -> [LocalDate] {
        let range = DateRange(start: today.adding(days: -180), end: today.adding(days: 7))
        return Array(source.schedule.paydays(in: range).reversed().prefix(count))
    }

    var sourcesForPay: [IncomeSource] { sources.filter(\.isActive) }

    var recentPays: [PayReceived] {
        pays.sorted { ($0.payDate, $0.id) > ($1.payDate, $1.id) }
    }

    /// Saves a logged pay. If one already exists for the same source and payday it is updated, not duplicated.
    @discardableResult
    func logPay(sourceID: Int, payDate: LocalDate, net: Money, gross: Money?, tax: Money?, note: String) -> Bool {
        let existing = pays.first { $0.sourceID == sourceID && $0.payDate == payDate }
        do {
            try repository.save(PayReceived(id: existing?.id ?? 0, sourceID: sourceID, payDate: payDate, net: net,
                                            gross: gross, tax: tax, note: note))
            reload()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func deletePay(_ pay: PayReceived) {
        do {
            try repository.deletePayReceived(id: pay.id)
            reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

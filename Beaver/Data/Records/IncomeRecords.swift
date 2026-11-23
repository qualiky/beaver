import Foundation
import BeaverCore
import GRDB

struct RateCardRecord: BeaverRecord {
    static let databaseTableName = "rate_card"
    var id: Int?
    var name: String
    var rulesJson: [RateRule]

    init(_ card: RateCard) { id = recordID(card.id); name = card.name; rulesJson = card.rules }
    func core() throws -> RateCard { RateCard(id: id ?? 0, name: name, rules: rulesJson) }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct WithholdingTableRecord: BeaverRecord {
    static let databaseTableName = "withholding_table"
    var id: String
    var name: String
    var rowsJson: [WithholdingRow]
    var builtin: Bool
    var note: String?

    init(_ t: WithholdingTable) { id = t.id; name = t.name; rowsJson = t.rows; builtin = t.isBuiltin; note = t.note }
    func core() throws -> WithholdingTable {
        WithholdingTable(id: id, name: name, rows: rowsJson, isBuiltin: builtin, note: note)
    }
}

struct IncomeSourceRecord: BeaverRecord {
    static let databaseTableName = "income_source"
    var id: Int?
    var name: String
    var kind: String
    var rateCardId: Int?
    var fixedAmountCents: Int?
    var amountIsNet: Bool
    var scheduleJson: PaySchedule
    var taxMethodJson: TaxMethod
    var weeklyCapSoft: Int?
    var weeklyCapHard: Int?
    var active: Bool

    init(_ s: IncomeSource) {
        id = recordID(s.id); name = s.name; kind = s.kind.rawValue; rateCardId = s.rateCardID
        fixedAmountCents = s.fixedAmount?.cents; amountIsNet = s.amountIsNet
        scheduleJson = s.schedule; taxMethodJson = s.taxMethod
        weeklyCapSoft = s.weeklyCapSoftHours; weeklyCapHard = s.weeklyCapHardHours; active = s.isActive
    }

    func core() throws -> IncomeSource {
        IncomeSource(id: id ?? 0, name: name, kind: try parseEnum(kind, as: IncomeKind.self), rateCardID: rateCardId,
                     fixedAmount: fixedAmountCents.map { Money(cents: $0) }, amountIsNet: amountIsNet,
                     schedule: scheduleJson, taxMethod: taxMethodJson,
                     weeklyCapSoftHours: weeklyCapSoft, weeklyCapHardHours: weeklyCapHard, isActive: active)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct TemplateRecord: BeaverRecord {
    static let databaseTableName = "template"
    var id: Int?
    var sourceId: Int
    var name: String
    var effectiveFrom: String?

    init(_ t: Template) { id = recordID(t.id); sourceId = t.sourceID; name = t.name; effectiveFrom = t.effectiveFrom?.iso }
    func core(shifts: [TemplateShiftRecord]) throws -> Template {
        Template(id: id ?? 0, sourceID: sourceId, name: name,
                 effectiveFrom: try effectiveFrom.map(parseDate), shifts: try shifts.map { try $0.core() })
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct TemplateShiftRecord: BeaverRecord {
    static let databaseTableName = "template_shift"
    var id: Int?
    var templateId: Int
    var weekday: Int
    var start: String
    var end: String
    var breakMin: Int

    init(_ s: TemplateShift, templateID: Int) {
        id = nil; templateId = templateID; weekday = s.weekday.rawValue
        start = s.start.iso; end = s.end.iso; breakMin = s.breakMinutes
    }
    func core() throws -> TemplateShift {
        guard let day = Weekday(rawValue: weekday) else { throw DataError.corrupt("bad weekday \(weekday)") }
        return TemplateShift(weekday: day, start: try parseTime(start), end: try parseTime(end), breakMinutes: breakMin)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct ShiftRecord: BeaverRecord {
    static let databaseTableName = "shift"
    var id: Int?
    var sourceId: Int
    var date: String
    var start: String
    var end: String
    var breakMin: Int
    var status: String
    var note: String

    init(_ s: Shift) {
        id = recordID(s.id); sourceId = s.sourceID; date = s.date.iso; start = s.start.iso; end = s.end.iso
        breakMin = s.breakMinutes; status = s.status.rawValue; note = s.note
    }
    func core() throws -> Shift {
        Shift(id: id ?? 0, sourceID: sourceId, date: try parseDate(date), start: try parseTime(start),
              end: try parseTime(end), breakMinutes: breakMin, status: try parseEnum(status, as: ShiftStatus.self), note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct PayReceivedRecord: BeaverRecord {
    static let databaseTableName = "pay_received"
    var id: Int?
    var sourceId: Int
    var payDate: String
    var netCents: Int
    var grossCents: Int?
    var taxCents: Int?
    var note: String

    init(_ p: PayReceived) {
        id = recordID(p.id); sourceId = p.sourceID; payDate = p.payDate.iso; netCents = p.net.cents
        grossCents = p.gross?.cents; taxCents = p.tax?.cents; note = p.note
    }
    func core() throws -> PayReceived {
        PayReceived(id: id ?? 0, sourceID: sourceId, payDate: try parseDate(payDate), net: Money(cents: netCents),
                    gross: grossCents.map { Money(cents: $0) }, tax: taxCents.map { Money(cents: $0) }, note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

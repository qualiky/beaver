import Foundation
import BeaverCore
import GRDB

struct HolidayRecord: BeaverRecord {
    static let databaseTableName = "holiday"
    var date: String
    var name: String

    init(_ h: Holiday) { date = h.date.iso; name = h.name }
    func core() throws -> Holiday { Holiday(date: try parseDate(date), name: name) }
}

struct UnavailableRecord: BeaverRecord {
    static let databaseTableName = "unavailable"
    var id: Int?
    var dateFrom: String
    var dateTo: String
    var note: String

    init(_ u: UnavailableRange) { id = recordID(u.id); dateFrom = u.from.iso; dateTo = u.to.iso; note = u.note }
    func core() throws -> UnavailableRange {
        UnavailableRange(id: id ?? 0, from: try parseDate(dateFrom), to: try parseDate(dateTo), note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct HoursLimitRecord: BeaverRecord {
    static let databaseTableName = "hours_limit"
    var id: Int?
    var hoursPerFortnight: Int
    var effectiveFrom: String

    init(_ l: FortnightLimit) { id = recordID(l.id); hoursPerFortnight = l.hoursPerFortnight; effectiveFrom = l.effectiveFrom.iso }
    func core() throws -> FortnightLimit {
        FortnightLimit(id: id ?? 0, hoursPerFortnight: hoursPerFortnight, effectiveFrom: try parseDate(effectiveFrom))
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

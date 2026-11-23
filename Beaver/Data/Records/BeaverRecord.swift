import Foundation
import BeaverCore
import GRDB

/// Shared behaviour of every table record: snake_case columns, value semantics, and Sendable.
/// Records hold only database-friendly values (strings, integers, JSON columns); each converts
/// to and from the matching BeaverCore type, which is the only thing the engine ever sees.
protocol BeaverRecord: Codable, FetchableRecord, MutablePersistableRecord, Equatable, Sendable {}

extension BeaverRecord {
    static var databaseColumnDecodingStrategy: DatabaseColumnDecodingStrategy { .convertFromSnakeCase }
    static var databaseColumnEncodingStrategy: DatabaseColumnEncodingStrategy { .convertToSnakeCase }
}

/// Errors raised when stored values can not be turned back into BeaverCore values.
enum DataError: Error, LocalizedError, Equatable {
    case corrupt(String)
    case builtinReadOnly

    var errorDescription: String? {
        switch self {
        case .corrupt(let detail): "Stored data is invalid: \(detail)"
        case .builtinReadOnly: "Built-in reference tables can not be changed. Duplicate the table to edit it."
        }
    }
}

func parseDate(_ s: String) throws -> LocalDate {
    guard let d = LocalDate(iso: s) else { throw DataError.corrupt("bad date \"\(s)\"") }
    return d
}

func parseTime(_ s: String) throws -> LocalTime {
    guard let t = LocalTime(iso: s) else { throw DataError.corrupt("bad time \"\(s)\"") }
    return t
}

func parseEnum<E: RawRepresentable>(_ s: String, as: E.Type) throws -> E where E.RawValue == String {
    guard let e = E(rawValue: s) else { throw DataError.corrupt("bad \(E.self) \"\(s)\"") }
    return e
}

/// Core values use id 0 for "not saved yet"; records use nil so SQLite assigns the next id.
func recordID(_ id: Int) -> Int? { id == 0 ? nil : id }

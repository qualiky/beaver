import Foundation
import GRDB

/// Owns the SQLite connection. The app uses a `DatabasePool` in Application Support;
/// tests and previews use an in-memory `DatabaseQueue`. Only the Data layer touches GRDB.
struct AppDatabase: Sendable {
    let writer: any DatabaseWriter

    init(_ writer: any DatabaseWriter) throws {
        self.writer = writer
        try Migrations.makeMigrator().migrate(writer)
    }

    static func configuration() -> Configuration {
        var config = Configuration()
        config.foreignKeysEnabled = true
        return config
    }

    /// The on-disk database at `Application Support/beaver.sqlite`.
    static func makePersistent(fileManager: FileManager = .default) throws -> AppDatabase {
        let directory = try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                            appropriateFor: nil, create: true)
        return try makePersistent(at: directory.appendingPathComponent("beaver.sqlite"))
    }

    static func makePersistent(at url: URL) throws -> AppDatabase {
        let pool = try DatabasePool(path: url.path, configuration: configuration())
        return try AppDatabase(pool)
    }

    /// A private in-memory database, for tests and previews.
    static func makeInMemory() throws -> AppDatabase {
        try AppDatabase(DatabaseQueue(configuration: configuration()))
    }
}

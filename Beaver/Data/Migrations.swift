import Foundation
import BeaverCore
import GRDB

/// Append-only schema history. Never edit a registered migration; add a new one.
enum Migrations {
    static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1_initial") { db in
            try db.create(table: "setting") { t in
                t.primaryKey("key", .text)
                t.column("value", .text).notNull()
            }
            try db.create(table: "rate_card") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull()
                t.column("rules_json", .text).notNull()
            }
            try db.create(table: "withholding_table") { t in
                t.primaryKey("id", .text)
                t.column("name", .text).notNull()
                t.column("rows_json", .text).notNull()
                t.column("builtin", .boolean).notNull().defaults(to: false)
                t.column("note", .text)
            }
            try db.create(table: "income_source") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull()
                t.column("kind", .text).notNull().check(sql: "kind IN ('hourly','fixed')")
                t.column("rate_card_id", .integer).references("rate_card", onDelete: .setNull)
                t.column("fixed_amount_cents", .integer)
                t.column("amount_is_net", .boolean).notNull().defaults(to: false)
                t.column("schedule_json", .text).notNull()
                t.column("tax_method_json", .text).notNull()
                t.column("weekly_cap_soft", .integer)
                t.column("weekly_cap_hard", .integer)
                t.column("active", .boolean).notNull().defaults(to: true)
            }
            try db.create(table: "template") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("source_id", .integer).notNull().references("income_source", onDelete: .cascade)
                t.column("name", .text).notNull()
                t.column("effective_from", .text)
            }
            try db.create(table: "template_shift") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("template_id", .integer).notNull().references("template", onDelete: .cascade)
                t.column("weekday", .integer).notNull().check(sql: "weekday BETWEEN 1 AND 7")
                t.column("start", .text).notNull()
                t.column("end", .text).notNull()
                t.column("break_min", .integer).notNull().defaults(to: 0)
            }
            try db.create(table: "shift") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("source_id", .integer).notNull().references("income_source", onDelete: .cascade)
                t.column("date", .text).notNull()
                t.column("start", .text).notNull()
                t.column("end", .text).notNull()
                t.column("break_min", .integer).notNull().defaults(to: 0)
                t.column("status", .text).notNull().check(sql: "status IN ('rostered','worked','cancelled')")
                t.column("note", .text).notNull().defaults(to: "")
            }
            try db.create(index: "shift_on_date", on: "shift", columns: ["date"])
            try db.create(index: "shift_on_source_date", on: "shift", columns: ["source_id", "date"])
            try db.create(table: "pay_received") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("source_id", .integer).notNull().references("income_source", onDelete: .cascade)
                t.column("pay_date", .text).notNull()
                t.column("net_cents", .integer).notNull()
                t.column("gross_cents", .integer)
                t.column("tax_cents", .integer)
                t.column("note", .text).notNull().defaults(to: "")
            }
            try db.create(table: "holiday") { t in
                t.primaryKey("date", .text)
                t.column("name", .text).notNull()
            }
            try db.create(table: "unavailable") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("date_from", .text).notNull()
                t.column("date_to", .text).notNull()
                t.column("note", .text).notNull().defaults(to: "")
            }
            try db.create(table: "hours_limit") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("hours_per_fortnight", .integer).notNull()
                t.column("effective_from", .text).notNull()
            }
            try db.create(table: "bill") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull()
                t.column("amount_cents", .integer).notNull()
                t.column("cadence_json", .text).notNull()
                t.column("anchor_date", .text).notNull()
                t.column("category", .text).notNull()
                t.column("account", .text)
                t.column("active", .boolean).notNull().defaults(to: true)
                t.column("note", .text).notNull().defaults(to: "")
            }
            try db.create(table: "bill_payment") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("bill_id", .integer).notNull().references("bill", onDelete: .cascade)
                t.column("due_date", .text).notNull()
                t.column("paid_date", .text).notNull()
                t.column("amount_cents", .integer).notNull()
                t.uniqueKey(["bill_id", "due_date"])
            }
            try db.create(table: "expense") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("date", .text).notNull()
                t.column("amount_cents", .integer).notNull()
                t.column("category", .text).notNull()
                t.column("note", .text).notNull().defaults(to: "")
            }
            try db.create(index: "expense_on_date", on: "expense", columns: ["date"])
            try db.create(table: "week_spend_total") { t in
                t.primaryKey("week_start", .text)
                t.column("amount_cents", .integer).notNull()
            }
            try db.create(table: "goal") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull()
                t.column("target_cents", .integer).notNull()
                t.column("deadline", .text)
            }
            try db.create(table: "deposit") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("goal_id", .integer).notNull().references("goal", onDelete: .cascade)
                t.column("date", .text).notNull()
                t.column("amount_cents", .integer).notNull()
                t.column("note", .text).notNull().defaults(to: "")
            }

            // Reference data, not personal data: the built-in AU Scale 2 table.
            let table = WithholdingTable.auScale2Weekly
            let rows = try String(decoding: JSONEncoder.sortedKeys().encode(table.rows), as: UTF8.self)
            try db.execute(sql: """
                INSERT INTO withholding_table (id, name, rows_json, builtin, note) VALUES (?, ?, ?, 1, ?)
                """, arguments: [table.id, table.name, rows, table.note])
        }

        return migrator
    }
}

extension JSONEncoder {
    static func sortedKeys() -> JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }
}

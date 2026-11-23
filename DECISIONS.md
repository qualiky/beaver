
## M0 scaffold
- GRDB.swift pinned to exact 7.12.0 (released 2026-11-01, newest 7.x at least two weeks old when chosen).
- Tests target depends on the app target (hosted unit tests) so `@testable import Beaver` works.
- Config/Base.xcconfig sets the default bundle ID and an empty DEVELOPMENT_TEAM, and includes the optional git-ignored Local.xcconfig.

# Contributing to Beaver

Thanks for helping. Beaver is a small app, so small focused changes are easiest to review.

## Set up

You need Xcode 27 or later and XcodeGen.

```sh
brew install xcodegen
xcodegen generate
open Beaver.xcodeproj
```

`Beaver.xcodeproj` is generated and not committed. Run `xcodegen generate` after you change `project.yml`.

## Run the tests

Engine tests run with no simulator:

```sh
cd Packages/BeaverCore && swift test
```

Data-layer and backup tests run in the app's test target, `BeaverTests`. They use an in-memory database. Run them from Xcode or with `xcodebuild test`.

Check the engine's imports before you open a pull request:

```sh
scripts/check-core-imports.sh
```

Also run the Release build (`scripts/build-ipa.sh`) before you open a pull request.

## Where code goes

- Business logic lives in `Packages/BeaverCore`. It uses Foundation only. No SwiftUI, GRDB or UIKit. The check script enforces this.
- Every change to business logic needs a test in `Packages/BeaverCore/Tests`.
- SQL lives only in `Beaver/Data/Repository.swift`.
- Database migrations are append-only. Add a new migration. Do not edit an old one.

## Money

Money is always integer cents, in an `Int`, through the `Money` type. Never store or calculate money with `Double`. Format amounts only in the UI.

## Personal financial data

**Never commit personal financial data.** That includes real employer pay rates, real rate cards, real payslips, real bank statements and real account details.

Tests, previews and demo data use only the fictional persona in `SPEC.md` §5 (the casual worker "Sam", with "Example Bar"). If you need a new example, make up the values.

Check `git diff` and `git status` before you commit. If you commit something by mistake, tell a maintainer right away. Do not just delete it in a new commit, because it stays in the history.

## Design decisions

Record each design decision in `DECISIONS.md`. Include the date, the choice, and the reason. If you make a choice the spec leaves open, add a line there.

## Pull requests

- Keep each pull request small and focused on one change.
- Describe what changed and why. Say how you tested it.
- Make sure `swift test` passes in `Packages/BeaverCore`, and the check script passes.
- Do not mix formatting changes with logic changes.

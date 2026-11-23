# Beaver

Beaver is an offline, local-first budgeting and pay forecaster for iPhone. It is built for casual and shift workers, whose pay changes from week to week. It also works for salaried people paid monthly.

Beaver turns your shift roster, or your salary, into expected pay. It tracks bills and weekly spending. Each week it tells you how far ahead or behind you are on a savings goal.

## Key features

- **Pay from your roster:** enter shifts and a rate card. Beaver splits each shift by band (weekday, evening, night, Saturday, Sunday, public holiday) and calculates gross, tax and net pay.
- **Pay schedules:** weekly, fortnightly or monthly paydays, with a lag where your pay arrives after the work week.
- **Tax:** flat percentage, or a withholding table. You can edit the tables or add your own.
- **Bills:** recurring bills with weekly, fortnightly, monthly, quarterly, yearly and custom cadences. Mark them paid and see what is overdue.
- **Spending:** a weekly living budget, logged as quick entries or as one weekly total.
- **Goals:** set a target and deadline, log deposits, and see the projected gap.
- **Forecast:** a week-by-week plan through your goal deadline, with danger weeks and a suggested split for each payday.
- **Hours limits:** optional warnings for weekly caps and fortnightly limits, such as student work-hour rules.
- **Backup:** export all data to a versioned JSON file, and import it later.

## Privacy

- No accounts.
- No network access. No analytics. No third-party SDKs except GRDB, the local database library.
- All data stays on the device, in a SQLite database.
- To move data or keep a copy, export a JSON backup from Settings.

## Status

Beaver is in early development. The goal for v0.1 is a working release with the forecast engine, the Roster, Money, Week and Plan tabs, onboarding, and JSON backup. Expect changes before then.

## Requirements

- Xcode 27 or later
- iOS 18 or later (iPhone, portrait)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)

## Build from source

```sh
brew install xcodegen
xcodegen generate
open Beaver.xcodeproj
```

`Beaver.xcodeproj` is generated from `project.yml`, so it is not committed. Run `xcodegen generate` again after you change the project file.

To run the engine tests, which need no simulator:

```sh
cd Packages/BeaverCore && swift test
```

To use your own bundle ID and signing team, copy `Config/Local.xcconfig.example` to `Config/Local.xcconfig` and edit it. That file is git-ignored.

## Installing on your own iPhone

To install a build on your own iPhone without a paid Apple Developer account, see [docs/SIDELOADING.md](docs/SIDELOADING.md).

## Project layout

```
project.yml                  XcodeGen project definition
Packages/BeaverCore/         Business logic (Foundation only, no UI, no storage)
Beaver/                      iOS app: App, Data, Features, Shared, Resources
BeaverTests/                 Data-layer and backup tests
scripts/                     build-ipa.sh, check-core-imports.sh
docs/                        Architecture and sideloading guides
```

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before you open a pull request. Record design decisions in `DECISIONS.md`.

## License

Beaver is released under the MIT License. See [LICENSE](LICENSE).

## Disclaimer

Beaver is a personal planning tool. It is not financial, tax or legal advice. Its pay and tax results are estimates. The bundled withholding table must be checked against official sources, such as the current ATO schedule, before you rely on it. Check your payslips and your employer's pay rules.

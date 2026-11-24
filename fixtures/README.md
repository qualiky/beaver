# Golden fixtures

`golden.json` holds the golden test cases from SPEC section 6 as language-neutral data. The Swift engine (`Packages/BeaverCore`) and any other port (for example Kotlin) must pass **this file unchanged**. Never fork or edit a copy for one language. To change a case, edit `golden.json`, and make every language's tests pass.

The data is fictional (demo persona "Sam"). It contains no real personal or employer data.

## Conventions

- `fixtureVersion` (integer, now `1`): bump it when the schema changes, and update this file and every test loader.
- All money is integer cents. Hours are integer minutes, or hours x 100 (`hoursHundredths`) rounded half up. No floats anywhere.
- Dates are `YYYY-MM-DD`, times are `HH:mm`. A shift with `end <= start` crosses midnight. `"24:00"` is valid only as the end of a rate window.
- Weekdays are lowercase English names (`monday` ... `sunday`).
- Case `id` matches the SPEC section 6 test number. Sub-cases add a suffix (`1.3`, `7.2`).
- Band lists are compared by band name; their order is not significant.

## Sections

| Section | Content |
|---|---|
| `rateCards` | Rate cards by `id`. `rules` is ordered (first match wins). `days` is `weekday`, `saturday`, `sunday` or `publicHoliday`; `window` is `{from, to}` (`[from, to)`) or `null` for all day. |
| `holidaySets` | Named public holiday lists (`demo`). |
| `withholdingTables` | Tables by `id`. Rows are `{belowDollars (null = no limit), a, b}`. `a` and `b` are **decimal strings** (up to 4 fraction digits); parse them as exact decimals, never as floating point. |
| `shiftCases` | One shift priced alone: minutes and hours x 100 per band, paid minutes and gross. |
| `weekCases` | A list of shifts for one weekly pay period: paid minutes, gross, tax, net and (optionally) band hours x 100. |
| `taxCases` | Withholding samples (`grossCents` to `taxCents`). |
| `scheduleCases` | Pay schedules: work date to payday, payday to work period, paydays in a range. |
| `billCases` | A bill and date ranges with the expected due dates. |
| `hoursLimitCases` | A fortnight limit and scenarios with shifts, and whether (and how) it warns. |
| `forecastCases` | The full demo state as data (source, template, bills, living budget, holidays, unavailable dates, goal, logged pay, today) and the expected totals, every payday's net, and the required amount per payday. |

## Running

- Swift: `cd Packages/BeaverCore && swift test`. The tests read the file through `#filePath`, so there is no copy.
- Kotlin: load `fixtures/golden.json` from the repo root and assert every case the same way.

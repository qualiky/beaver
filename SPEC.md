# Beaver: build spec

Beaver is an offline, local-first iPhone app for people whose money doesn't arrive in neat monthly salaries. It turns your shift roster (or salary) into expected pay, tracks bills and weekly spending, and tells you every week how far ahead or behind you are on a savings goal.

It is built for **casual and shift workers** (variable hours, weekend and night penalty rates, public holidays, weekly pay) and works just as well for a **salaried user paid monthly**.

The app is **free and open source** (MIT). It has no backend, no accounts, no bank connections, no analytics and no network access. All data stays in SQLite on the device. People can install it from source or by sideloading.

> **Agents:** read this whole file before writing code. Build the BeaverCore engine and its tests first. The golden tests in §6 must pass before any UI depends on them. If something is ambiguous, pick the simplest option and record it in `DECISIONS.md`.
>
> **The repository is public.** Never commit personal financial data, real employer names, real pay rates from private contracts, or real bank statements. Seed data, previews and tests use the fictional examples in this spec only.

---

## 1. Stack and constraints

| Area | Choice |
|---|---|
| App | Native iOS: **Swift 6 + SwiftUI**, minimum **iOS 18**, iPhone only, portrait. Use Observation (`@Observable`), not Combine. |
| Project | **XcodeGen**: `project.yml` generates `Beaver.xcodeproj`, which is **not committed**. |
| Business logic | **`BeaverCore`**, a local Swift Package at `Packages/BeaverCore`. Foundation only: no SwiftUI, no GRDB, no UIKit. |
| Storage | **GRDB.swift** (Swift Package Manager, pinned exact version), used only by the app's `Data/` layer. Migrations through `DatabaseMigrator`, values observed with `ValueObservation`. The database file lives in Application Support. |
| Tests | **Swift Testing** (`@Test`). `swift test` in `Packages/BeaverCore` runs the engine tests with no simulator. Data-layer tests use an in-memory `DatabaseQueue` inside the app's test target. |
| Navigation | First launch shows onboarding (§4.0). After that, a `TabView` with 4 tabs: Week, Roster, Money, Plan. Settings sits behind a gear icon in a toolbar. |
| Network | None. No networking code and no third-party SDKs other than GRDB. |
| Money | `Int` cents everywhere, through a `Money` value type. The currency code is a setting (default `AUD`); format only in the UI. Never use `Double` for stored money. |
| Dates | Store as ISO strings (`YYYY-MM-DD`, `HH:mm`). Calculate with a `Calendar` using the user's chosen time zone (default: the device's), with Monday as the first weekday. Wall-clock shift times are never converted through UTC, so daylight saving changes don't shift them. |
| Theme | System light/dark, Dynamic Type, SF Symbols, VoiceOver labels on every control. Must be usable one-handed. |
| Bundle ID | Comes from the build setting `BEAVER_BUNDLE_ID`. Default `dev.beaver.app`, overridable through a git-ignored `Local.xcconfig`, so each person sideloading can use their own. |

### Distribution
- `scripts/build-ipa.sh`:
  1. `xcodegen generate`
  2. `xcodebuild -project Beaver.xcodeproj -scheme Beaver -configuration Release -sdk iphoneos -archivePath build/Beaver.xcarchive archive CODE_SIGNING_ALLOWED=NO`
  3. Copy `Beaver.app` into `Payload/` and zip it as `build/Beaver.ipa`.
- The `.ipa` can be sideloaded with SideStore or AltStore, which re-sign it with a free Apple ID and refresh it every 7 days. Keep the bundle ID fixed so a refresh keeps the data.
- Use no capabilities that need a paid developer account: no push, no iCloud, no App Groups. Local notifications are allowed.
- An app that expires and gets deleted takes its data with it, so **JSON export and import (§4.6) is required**.

---

## 2. Domain model and rules

### 2.1 Income sources

A user has one or more income sources. Each has a name, a kind, a pay schedule and a tax method.

| Kind | Used for | Expected pay comes from |
|---|---|---|
| `hourly` | Casual and shift work | Shifts × a **rate card** (§2.2) |
| `fixed` | Salary, regular side gig, allowance | A fixed amount per pay period |

**Pay schedule:**
- `weekly(payWeekday, lagWeeks)`: paid every week on `payWeekday`. A payday `P` covers the Mon–Sun work week ending on the last Sunday strictly before `P − (lagWeeks − 1) × 7 days`. Default Wednesday, lag 1, so the week ending Sun 29 Nov is paid Wed 2 Dec.
- `fortnightly(anchorPayday, lagWeeks)`: paid every 14 days from `anchorPayday`, in both directions. Each payday covers the 14-day work period (Mon to Sun) ending on the same Sunday the weekly rule gives.
- `monthly(dayOfMonth)`: `fixed` sources only. If the month is too short, it pays on the last day of the month.

**Tax method**, worked out per pay period on gross pay:
- `none`: the amount entered is already net.
- `flatPercent(p)`
- `withholdingTable(id)`: a coefficient table stored as data (§2.3).

`fixed` sources let the user enter either gross (taxed by the method) or net (tax method `none`).

### 2.2 Rate cards (hourly sources)

A rate card is user data. The app ships **no real employer's rates**.
- **Day rules:** an ordered list where the first match wins. Each rule names a **band**, its `$ per hour`, and where it applies:
  - which days: public holiday, Saturday, Sunday or weekdays (Mon–Fri)
  - an optional clock window `[from, to)`, e.g. weekdays 19:00–24:00
- Every weekday minute must fall into some weekday band. The editor checks this and shows any gaps.
- The **public holiday band**, if defined, overrides everything else on a public holiday date.

**Preset:** "Typical casual (template)". The user overrides the dollar amounts in onboarding. Its fictional rates, also used by the tests:

| Band | Applies | $/hr |
|---|---|---|
| Weekday day | Mon–Fri 07:00–19:00 | 30.00 |
| Weekday evening | Mon–Fri 19:00–24:00 | 33.00 |
| Weekday night | Mon–Fri 00:00–07:00 | 34.50 |
| Saturday | all day | 37.50 |
| Sunday | all day | 45.00 |
| Public holiday | all day | 60.00 |

**How a shift is split:**
- A shift has a date, start, end and an unpaid break in minutes. If `end <= start`, the shift crosses midnight into the next day.
- Walk the shift **minute by minute**. Each minute takes the band of the **calendar date and clock time it actually falls on**. Friday 23:00–01:00 is weekday evening, then Saturday. Sunday 23:00–01:00 is Sunday, then Monday's weekday night band.
- A minute on a public holiday (looked up by that minute's date) takes the public holiday band.
- The unpaid break is spread pro-rata over the whole shift.
- **Exact integer maths:**
  - `duration` = total minutes; `paidMinutes` = `duration − break`.
  - Band hours = `bandMinutes × paidMinutes / duration / 60`, for display only.
  - **Shift gross in cents** = `roundHalfUp( Σ(rateCents_b × bandMinutes_b) × paidMinutes / (duration × 60) )`.
  - **Period gross** = the sum of the shift gross amounts.

### 2.3 Withholding tables

A table is a list of rows `(belowX, a, b)` stored as data. For one pay period:
- `x = floor(grossDollars) + 0.99`
- `tax = max(0, roundHalfUp(a·x − b))` in **whole dollars**, using the first row whose `x < belowX`.

The app ships one built-in table, **"AU ATO Schedule 1 – Scale 2 (weekly, tax-free threshold claimed)"**, marked "verify against the current ATO schedule before relying on it":

| x below | a | b |
|---|---|---|
| 361 | 0 | 0 |
| 500 | 0.1600 | 57.8462 |
| 625 | 0.2600 | 107.8462 |
| 721 | 0.1800 | 57.8462 |
| 865 | 0.1790 | 60.8377 |
| 1282 | 0.3227 | 180.0385 |
| 2596 | 0.3200 | 176.5769 |
| 3653 | 0.3900 | 358.3077 |
| ∞ | 0.4700 | 650.6154 |

Users can duplicate and edit tables, or add their own. For fortnightly pay, the engine applies the table to the period gross ÷ 2 and doubles the result. Tables are weekly by convention, so record this in DECISIONS.md.

### 2.4 Public holidays and unavailable dates
- **Public holidays:** a user-editable list of dates and names. Onboarding offers to add them later; none are pre-filled, because they depend on the region.
- **Unavailable ranges:** `from`, `to` and a note, such as travel or exams. Template shifts are not generated on these dates.

### 2.5 Hours limits (optional, warnings only)
- **Weekly hours cap per income source,** optional: a soft warning above the value, and a hard warning above an optional second value.
- **Hours limit per rolling fortnight across all sources,** optional, active from a date. Fortnights are anchored on the Monday of that date. This covers student visa work limits and similar rules.
- Never block saving a shift. Warn inline.

### 2.6 Bills (recurring expenses)
A bill has: name, amount, cadence, anchor date (the next due date), category (`housing`, `utilities`, `subscription`, `transport`, `debt`, `insurance`, `other`), an optional account label, an active flag and a note.

Cadences:
- `weekly`
- `fortnightly`
- `everyNDays(n)`
- `monthly(dayOfMonth)`: if the month is too short, it falls on the last day of the month
- `quarterly`: every 3 months from the anchor, using the same day-of-month clamping
- `yearly`
- `oneOff`

Occurrences:
- Generated for any date range, starting at the anchor; nothing comes before the anchor.
- An occurrence can be marked **paid** with the actual amount and date.
- An unpaid past occurrence is **overdue**.
- Editing a bill's amount applies only to unpaid occurrences from the next one on.

### 2.7 Spending
- A **weekly living budget**: money for food, transport and so on, outside bills.
- Spending is logged as quick entries (amount, category, optional note), **or** as one total for a week. A week total replaces that week's entries.
- Categories: food, transport, coffee & drinks, going out, shopping, other.
- Weekly variance = budget − spent. A positive number means under budget.

### 2.8 Goals
- One or more goals (name, target, optional deadline), with **deposits** logged against them.
- **The primary goal** drives the Week tab. With no goal set, the app just shows the forecast surplus.

---

## 3. Forecast engine

`forecast(state, today) -> Forecast` covers every week (Mon–Sun) from the current one through the primary goal's deadline, or 26 weeks if there's no deadline.

For each week:
- **Income:** every pay event of every active source whose payday falls in the week.
  - Logged actual pay is used when present.
  - Otherwise, expected pay is calculated from the shifts in that pay period.
  - If an `hourly` source has **no shifts at all** in a future pay period, the shifts come from its **default week template**, skipping unavailable dates and applying public holidays.
  - Optionally, a template can be set to start from a date (e.g. a reduced semester template). The latest template whose start date is on or before the week is used.
- **Bills:** occurrences due in the week, using paid amounts where logged.
- **Living:** actual spending for past weeks, the budget for the current and future weeks.
- **Surplus** = income − bills − living.
- **The deadline week** counts pay events and bills dated on or before the deadline only. Living still counts as a full week.

Outputs:
- `weeks: [WeekRow]`: one per week, with income (each item marked actual or estimate), bills, living and surplus.
- **Projected at deadline** = goal deposits so far + Σ surplus over the weeks from the current one to the deadline.
- **Gap** = projected − target. Positive means ahead, negative means behind. This is the "how far off am I" number.
- **Required per remaining payday** = (target − deposits) ÷ the number of paydays from today to the deadline.
- **Danger weeks:** surplus < 0, each with the bills due in it.
- **Variance to date:**
  - Pay: Σ(actual − expected) over logged pay events.
  - Spending: Σ(budget − actual) over past weeks.
- **Suggested split** for the next payday, in order:
  1. Bills due before the following payday.
  2. The living budget until the following payday.
  3. The bills sinking fund: the weekly share of non-weekly bills.
  4. The required goal amount.
  5. The rest goes to buffer.

  When pay falls short, the shortfall is shown as "from buffer".

---

## 4. Screens

### 4.0 Onboarding (first launch; all steps can be skipped and edited later)
1. **Welcome:**
   - Explains that all data stays on the device, with no accounts and no bank connections.
   - Lets the user choose currency and time zone.
   - Offers **Restore from backup** (§4.6), which skips the rest.
2. **Income sources:** add one or more.
   - **Shift-based:** name; rate card (start from the template preset and edit the dollar amounts, or build from scratch); pay schedule; tax method; optionally a default week of shifts.
   - **Salary / fixed:** name, amount, gross or net, schedule (weekly, fortnightly or monthly), tax method.
3. **Bills:** add recurring expenses.
   - A list of common bills to tap and fill in: Rent, Phone, Internet, Electricity, Gas, Water, Transport card, Gym, Streaming, Music, Cloud storage, Insurance, Loan repayment.
   - Each needs an amount, cadence and next due date. Plus "Add custom".
4. **Spending:** the weekly living budget, with a short hint on how to choose one.
5. **Goal** (optional): name, target, deadline, amount already saved.
6. **Optional limits:** weekly hours caps, a fortnightly hours limit and its start date, public holidays, unavailable dates.
7. **Summary:** the first forecast ("You'll be $X ahead/behind by …"), then go to the Week tab.

Onboarding writes through the same repository as Settings and is fully re-runnable from Settings.

### 4.1 Week (home)
- **"$X ahead / $X behind for ‹deadline›"**, big and colored by status. With no goal, "This week: +$X".
- Goal progress bar with the required amount per payday.
- This week:
  - Expected pay, plus actual if logged, with the difference.
  - Bills due before the next payday, with a total and paid ticks.
  - Living budget left, with a "+ expense" button.
- Suggested split for the next payday.
- Banners for a danger week, an hours limit, and a backup reminder when the last export is more than 7 days old.

### 4.2 Roster
- Week picker, Monday to Sunday.
- List of shifts.
  - Add or edit a shift: source, date, start, end, break (0/15/30/45/60 or custom), status (rostered, worked or cancelled), note.
  - The form shows a live band and gross preview, a public holiday chip, and "finishes next day".
- Week footer per source: hours, gross, tax, net, and payday. Hours-limit warnings appear here too.
- Buttons:
  - **Copy last week**
  - **Apply template**
  - **Save as template**
- **Log pay received:** actual net, with optional gross and tax, and the difference against the estimate.

### 4.3 Money
- **Bills:**
  - The next 8 weeks of occurrences, grouped under "Before ‹payday›" headers, each with a total.
  - Overdue items are pinned to the top.
  - Mark paid; add, edit or deactivate a bill.
  - A "Monthly view" listing each bill's usual day of the month.
- **Spending:** quick entry, set a week total, per-week variance.
- **Goals:** deposits, with progress.

### 4.4 Plan
- The forecast table described in §3, with danger weeks highlighted and tap-through to their bills.
- What-if controls that change the settings: living budget, an extra weekly income amount, template choice.

### 4.5 Settings
Income sources and rate cards, withholding tables, bills, goals, public holidays, unavailable ranges, hours limits, currency and time zone, re-run onboarding, backup, about and licenses.

### 4.6 Backup
- **Export:** the whole database as one versioned JSON file (`schemaVersion`, every table) through `fileExporter` or the share sheet.
- **Import:**
  - Replaces all data in one transaction, after an in-app confirmation.
  - Rejects newer or unknown schema versions with a clear message.
  - Works from onboarding (restore) and from Settings.
- Show "Last exported" on Settings. The Week tab banner appears after 7 days.

### 4.7 Notifications (optional, local only)
- "Pay day tomorrow: expected $X. Bills due before next payday: $Y."
- Off by default.

---

## 5. Sample data (SwiftUI previews, tests and a "Load demo data" button only)

The demo persona is fictional: a casual worker named **"Sam"**. **A real install never seeds this.** It is loaded only from Settings → "Load demo data" (with confirmation), in previews and in tests.

- **Source:** "Example Bar". Hourly, typical casual preset rates (§2.2), weekly pay on Wednesday with lag 1, AU Scale 2 table.
- **Default template** (all shifts with 30-minute breaks):
  - Wed 18:00–23:00
  - Thu 18:00–23:00
  - Fri 18:00–04:30
  - Sat 18:00–04:30
  - Sun 17:00–01:00
- **Bills:**

  | Bill | Amount | Cadence | Anchor |
  |---|---|---|---|
  | Rent | $400.00 | weekly | 2026-11-25 |
  | Phone | $30.00 | every 28 days | 2026-11-22 |
  | Streaming | $15.99 | monthly (5th) | 2026-12-05 |
  | Gym | $25.00 | fortnightly | 2026-11-27 |
  | Electricity | $240.00 | quarterly | 2027-01-15 |
  | Car service | $500.00 | one-off | 2026-12-10 |
- **Living:** $150 a week.
- **Public holidays:** 2026-12-25, 2026-12-26, 2026-12-28, 2027-01-01, 2027-01-26.
- **Unavailable:** 2026-12-02 to 2026-12-05.
- **Goal:** "Course fees", $6,000 by 2027-03-31, nothing saved yet.
- **Logged pay:** $600.00 net on 2026-11-25.

---

## 6. Golden tests (must pass, in `Packages/BeaverCore`)

All tests use the typical casual preset rates (§2.2) and the demo public holidays, with 30-minute breaks unless stated. Amounts are in cents.

**Shifts and pay:**

1. **Mixed week, Mon 2026-11-23 to Sun 2026-11-29**
   - Shifts:
     - Mon 08:30–16:30
     - Wed 08:30–15:30
     - Fri 17:00–01:00
     - Sat 19:00–03:30
     - Sun 12:00–19:45
   - Shift gross: 22500, 19500, 24609, 32471, 32625.
   - Week: **36.75 hours, gross 131705, tax 24500, net 107205.**
   - Band hours, rounded to 2 decimal places: weekday day 15.88, evening 4.69, Saturday 5.64, Sunday 10.54.
2. **Template week, week of Mon 2026-11-16:** 36.50 hours, gross 137283, tax 26300, net 110983.
3. **Withholding (AU Scale 2):**
   - 30000 → 0
   - 73512 → 7100
   - 80000 → 8300
   - 100000 → 14300
   - 152000 → 31000
4. **Thu 2026-11-26 22:00–02:00, no break:** 2h evening + 2h weekday night.
5. **Thu 2026-12-24 20:00–02:00, no break:** 4h evening + 2h public holiday.
6. **Sun 2026-11-29 23:00–01:00, no break:** 1h Sunday + 1h weekday night.
7. **Public holiday weeks with the template:**
   - Week of 2026-12-21: gross 177887, tax 39300, net 138587.
   - Week of 2026-12-28: gross 152997, tax 31300, net 121697.

**Pay schedules:**

8. **Weekly, Wednesday, lag 1:**
   - A shift on Sun 2026-11-29 is paid 2026-12-02.
   - A shift on Mon 2026-11-30 is paid 2026-12-09.
9. **Monthly fixed** (net $4,800, day 31): paydays 2026-11-30, 2026-12-31, 2027-01-31, 2027-02-28, 2027-03-31.

**Bills:**

10. `monthly(31)` falls on 2027-02-28.
11. Every 28 days from 2026-11-22 gives 2026-12-20, 2027-01-17, 2027-02-14, 2027-03-14.
12. Quarterly from 2027-01-15 gives 2027-04-15 and 2027-07-15.

**Hours limits:**

13. A 48-hour fortnightly limit from 2027-03-01: 50 hours in the fortnight starting 2027-03-01 warns. The same shifts in the fortnight starting 2027-02-15 do not.

**Forecast smoke test (demo data, `today` = 2026-11-23, deadline 2027-03-31):**

14. Expected results:
    - **19 paydays**, from 2026-11-25 to 2027-03-31.
    - Income **2,017,795**:
      - 2026-11-25 is the logged 60000.
      - 2026-12-09 is 32766 (unavailable dates leave only the Sun 6 Dec shift).
      - 2026-12-30 is 138587 and 2027-01-06 is 121697 (public holidays).
      - The other 15 are 110983.
    - Bills **874,896**:
      - Rent 19 × 40000
      - Phone 4 × 3000 (the 2026-11-22 occurrence falls before the window)
      - Streaming 4 × 1599
      - Gym 9 × 2500
      - Electricity 1 × 24000
      - Car service 50000
    - Living **285,000** (19 × 15000).
    - Projected **857,899**. Gap against 600000: **+257,899**.

---

## 7. Data model (SQLite through GRDB)

```
setting(key TEXT PRIMARY KEY, value TEXT)                -- JSON values: currency, timeZone, onboardingDone, lastExportAt, primaryGoalId…
rate_card(id, name, rules_json)
withholding_table(id, name, rows_json, builtin INTEGER)
income_source(id, name, kind, rate_card_id NULL, fixed_amount_cents NULL, amount_is_net INTEGER,
              schedule_json, tax_method_json, weekly_cap_soft NULL, weekly_cap_hard NULL, active)
template(id, source_id, name, effective_from NULL)
template_shift(id, template_id, weekday, start, end, break_min)
shift(id, source_id, date, start, end, break_min, status, note)
pay_received(id, source_id, pay_date, net_cents, gross_cents NULL, tax_cents NULL, note)
holiday(date PRIMARY KEY, name)
unavailable(id, date_from, date_to, note)
hours_limit(id, hours_per_fortnight, effective_from)
bill(id, name, amount_cents, cadence_json, anchor_date, category, account NULL, active, note)
bill_payment(id, bill_id, due_date, paid_date, amount_cents, UNIQUE(bill_id, due_date))
expense(id, date, amount_cents, category, note)
week_spend_total(week_start PRIMARY KEY, amount_cents)
goal(id, name, target_cents, deadline NULL)
deposit(id, goal_id, date, amount_cents, note)
```

Rules for the data layer:
- Records map to BeaverCore value types, and the engine only ever sees those.
- All SQL lives in `Data/Repository.swift`.
- Foreign keys are on, with `ON DELETE CASCADE` where it makes sense.
- Migrations are append-only (`v1_initial`, …).

---

## 8. Project layout

```
project.yml
Packages/BeaverCore/
  Package.swift
  Sources/BeaverCore/   Money  LocalDate  LocalTime  CalendarHelpers  RateCard  ShiftPay
                        Withholding  PaySchedule  IncomeSource  Bills  HoursLimits  Spending  Goals
                        Forecast  Split  DemoData
  Tests/BeaverCoreTests/
Beaver/
  App/          BeaverApp.swift, AppModel
  Data/         Database, Migrations, Records/, Repository, Backup
  Features/     Onboarding/  Week/  Roster/  Money/  Plan/  Settings/
  Shared/       MoneyText, StatusPill, BandChips, formatters
  Resources/    Assets.xcassets
BeaverTests/    data layer, backup round trip
scripts/        build-ipa.sh, check-core-imports.sh
docs/           ARCHITECTURE.md, SIDELOADING.md
README.md  CONTRIBUTING.md  DECISIONS.md  LICENSE  SPEC.md
```

## 9. Milestones
0. **Scaffold:** project.yml, BeaverCore package, GRDB, an empty tab shell. Builds for the simulator.
1. **BeaverCore engine** with all golden tests green.
2. **Data layer:** migrations, repository, backup, demo-data loader.
3. **Onboarding.**
4. **Roster tab.**
5. **Money tab.**
6. **Week and Plan tabs.**
7. **Settings**, backup UI, notifications.
8. **Release .ipa script and docs.**

**Done when:**
- All golden tests pass.
- A fresh install starts empty and goes through onboarding.
- The app works in airplane mode.
- An export → delete → reinstall → import round trip restores everything.

## 10. Out of scope (for now)
Bank sync, statement import, cloud sync, login, multiple users on one device, AI or chat, ads, Android, App Store submission, and end-of-year tax return estimates.

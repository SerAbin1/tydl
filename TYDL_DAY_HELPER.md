# Tydl day helper (`getTydlDay`)

A Tydl day is **not** a calendar day: it starts at the configured rollover hour (for example, 04:00). At 03:59 the app must still be operating on yesterday's date; at 04:00 it moves to today's.

`getTydlDay` is the shared way to answer "which Tydl day is this?" from anywhere in the codebase.

## API

```kotlin
// Imperative shell: "right now", using the current settings and device zone.
val day = getTydlDay(rolloverHour = settings.rolloverHour)

// Functional core: explicit inputs, no clock or settings read.
val day = TydlDay.of(epochMillis, rolloverHour = 4, zone = ZoneId.systemDefault())
val key = TydlDay.key(epochMillis, rolloverHour = 4)          // "2026-11-26"
val previous = TydlDay.previousKey(epochMillis, rolloverHour = 4)
```

`ObsidianNotes.effectiveDate` delegates to `TydlDay.of`, so daily notes, daily stats, the yesterday comparison and auto-delete retention all share one definition of a day.

## Why a shared helper is worth having

- **One boundary rule.** Before this, the note rollover used the rollover hour while stats, yesterday's comparison and retention used `LocalDate.now()`. Between midnight and the rollover hour, events were filed under the wrong day.
- **Consistent surfaces.** The R&T stats line, Gravitas sleep, settings "Today" card, daily notes and stored records now agree on what "today" and "yesterday" mean.
- **No drift when the setting changes.** Callers pass the current `rolloverHour`; changing it in settings immediately changes every lookup instead of leaving some paths on the old boundary.
- **Testable without a device.** The core takes an instant, an hour and a zone. Boundary cases (03:59 vs 04:00, rollover 0, DST/timezone changes) are plain JVM unit tests.
- **Correct zone handling.** The instant is resolved in the supplied zone *before* the hour comparison. Tests do not depend on the CI machine's UTC default.
- **Discoverable.** Any new feature needs only `getTydlDay(...)` or `TydlDay.key(...)`; it does not have to rediscover the rollover rule or invent its own date key format.
- **Safe refactors.** Changing the definition of a day is one edit plus one set of tests, not a hunt for scattered `LocalDate.now()` calls.
- **Stable keys.** `TydlDay.key` gives every store the same ISO `yyyy-MM-dd` key, so records written by one screen are readable by another.

## Contract

- `rolloverHour` is clamped to `0..23`; `0` means the calendar day never rolls back.
- An instant with a local hour **less than** the rollover hour belongs to the previous calendar date.
- Sleep belongs to the Tydl day of its **wake time**, not the time it was noticed.
- Pure callers pass an explicit instant and zone. `getTydlDay`'s "now" default is meant for the shell, where reading the clock is the point.

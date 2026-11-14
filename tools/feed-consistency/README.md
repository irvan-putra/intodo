# feed-consistency

Generic consistency checks for apps that publish a **projection** of a
source list (reminders, todos, any records) to another device or page.

Three independent checks, stdlib-only Python 3:

1. **Projection vs source** (`check_projection`) - both directions: every
   active source record appears in the projection with its verbatim title,
   every projected active item exists active in the source, archived items
   are not active in the source, and an optional id map is one-to-one.
2. **Schedule wording** (`check_schedules`) - reminder items against plain
   schedule entries; the expected human date text ("Mon Jan 12", "Monthly
   on the 5th") is *derived* from the entry, never duplicated by hand, so a
   rescheduled entry is caught as wording drift. An ISO datetime carrying
   its own offset is converted to the check timezone, never re-labeled. A
   note that states a year must state the entry's year, so a cross-year
   same-weekday date cannot slip through. Orphan reminder items (no
   schedule entry), unchecked entries, duplicates and malformed entries are
   all findings. An optional expectations table also pins title wording
   (for example a verb that must stay).
3. **Exact payload** (`payload_exact_match`) - truly byte-exact comparison
   of a served payload against the expected one (no normalization), to
   catch a stale page serving old content after a publish.

Strictness rule: duplicate or malformed ids in any input (source records,
sections - including a section with no id - items, schedule entries,
expectation pairs) are reported as findings, never silently collapsed, and
a map entry alone never excuses an active source record missing from the
projection. Date wording is matched on digit boundaries, so a one-digit
day never matches inside a two-digit one. CLEAN means every
promised check actually passed.

## Data contracts (plain JSON, no vendor schemas)

```jsonc
// source record
{"id": "s1", "title": "Water the plants", "status": "active", "date": "2026-01-12"}

// projection document
{"generated_at": "2026-01-01T00:00:00Z",
 "sections": [{"id": "todos", "items": [
   {"id": "t1", "title": "Water the plants", "note": "", "status": "pending"}]}]}

// schedule entry
{"ref": "bill", "kind": "once", "date": "2026-01-12"}
{"ref": "rent", "kind": "monthly", "day": 5}
```

Sections named `archive` / `removed` are treated as non-active; the
sections mirrored against the source default to `todos` and are
configurable. Schedule-driven sections (for example `reminders`) are
checked by `check_schedules` instead.

## Usage

```sh
python3 feed_consistency.py \
  --source examples/source.example.json \
  --projection examples/feed.example.json \
  --map examples/id-map.example.json \
  --schedules examples/schedules.example.json \
  --expectations examples/expectations.example.json

python3 -m unittest discover -s tests   # 26 tests, synthetic fixtures only
```

Exit code 0 means no findings; findings are printed one per line.

## Scope

This library only compares data you hand it. It does not fetch anything,
knows nothing about where the records or schedules come from, and does not
publish or repair projections - that wiring belongs to the app using it.

#!/usr/bin/env python3
"""feed_consistency - generic consistency checks between a source list of
records and a published projection of them, plus schedule wording checks and
exact-payload comparison.

Plain data contracts (no vendor schemas):

  source record : {"id": str, "title": str, "status": "active"|"completed"|"removed",
                   "date": "YYYY-MM-DD" (optional)}
  projection    : {"generated_at": ISO str,
                   "sections": [{"id": str, "items": [
                       {"id": str, "title": str, "note": str, "status": str}]}]}
  schedule entry: {"ref": str, "kind": "once"|"monthly",
                   "date": "YYYY-MM-DD" or ISO datetime (once) | "day": int 1-31 (monthly)}

Design rules: duplicate or malformed input is a FINDING, never silently
collapsed; every check the library promises fails loudly. A CLEAN result
means the promised checks actually passed.
"""
import json
import re
import sys
import argparse
from datetime import datetime, timedelta, timezone

ACTIVE = "active"
YEAR_RE = re.compile(r"\b(19|20)\d{2}\b")


def _ordinal(n):
    return f"{n}{'th' if 11 <= n % 100 <= 13 else {1: 'st', 2: 'nd', 3: 'rd'}.get(n % 10, 'th')}"


def _dedupe(pairs, what, findings):
    """Index [(id, obj)] by id, flagging duplicates instead of collapsing."""
    out = {}
    for key, obj in pairs:
        if key in out:
            findings.append(f"INPUT: duplicate {what} id {key!r}")
        else:
            out[key] = obj
    return out


def expected_date_text(entry, tz_offset_hours=0):
    """Core human date text ('Mon Jan 12' / 'Monthly on the 5th') and the year
    (or None for monthly) that a projection note must agree with.

    Raises ValueError on a malformed entry - callers turn that into a finding.
    An ISO datetime with its own offset is converted, never re-labeled; a
    bare date is read as local wall time at tz_offset_hours.
    """
    kind = entry.get("kind")
    tz = timezone(timedelta(hours=tz_offset_hours))
    if kind == "monthly":
        day = entry.get("day")
        if not isinstance(day, int) or isinstance(day, bool) or not 1 <= day <= 31:
            raise ValueError(f"monthly entry needs integer day 1-31, got {day!r}")
        return f"Monthly on the {_ordinal(day)}", None
    if kind == "once":
        raw = entry.get("date")
        if not raw:
            raise ValueError("once entry needs a date")
        dt = datetime.fromisoformat(raw)
        dt = dt.replace(tzinfo=tz) if dt.tzinfo is None else dt.astimezone(tz)
        return dt.strftime("%a %b ") + str(dt.day), dt.year
    raise ValueError(f"unknown kind {kind!r}")


def date_text_matches(entry, note, tz_offset_hours=0):
    """True when the note carries the entry's core date text and no
    contradicting year. A note without any year passes on the core text; any
    year present in the note must equal the entry's year."""
    core, year = expected_date_text(entry, tz_offset_hours)
    # bounded match: "Thu Jan 1" must not match inside "Thu Jan 12"
    if not re.search(r"(?<!\d)" + re.escape(core) + r"(?!\d)", note or ""):
        return False
    if year is not None:
        for m in YEAR_RE.finditer(note or ""):
            if int(m.group(0)) != year:
                return False
    return True


def check_projection(source_records, projection, id_map=None, mirror_sections=("todos",)):
    """Both-direction consistency between source records and a projection.

    id_map maps projection item id -> source record id when they differ.
    mirror_sections names the projection sections whose items must mirror
    active source records (default: just 'todos'). Sections 'archive' and
    'removed' are checked as holding non-active items. Other sections (for
    example schedule-driven reminders) are left to check_schedules.
    """
    findings = []
    id_map = id_map or {}
    src = _dedupe(((r.get("id"), r) for r in source_records), "source record", findings)
    for r in source_records:
        if not r.get("id") or "title" not in r or "status" not in r:
            findings.append(f"INPUT: malformed source record (need id/title/status): {str(r)[:80]!r}")

    raw_sections = projection.get("sections", [])
    for s in raw_sections:
        if not s.get("id"):
            findings.append(f"INPUT: malformed projection section (missing id): {str(s)[:80]!r}")
    sections = _dedupe(((s.get("id"), s.get("items", [])) for s in raw_sections if s.get("id")),
                       "projection section", findings)

    projected_active = {}
    projected_inactive = {}
    for sec_id, items in sections.items():
        if sec_id in ("archive", "removed"):
            target = projected_inactive
        elif sec_id in mirror_sections:
            target = projected_active
        else:
            continue
        for it in items:
            pid = it.get("id")
            if not pid or "title" not in it:
                findings.append(f"INPUT: malformed projection item in {sec_id}: {str(it)[:80]!r}")
                continue
            if pid in projected_active or pid in projected_inactive:
                findings.append(f"INPUT: duplicate projection item id {pid!r} (section {sec_id})")
                continue
            target[pid] = it

    # map integrity: one-to-one, resolvable
    seen = {}
    for pid, sid in id_map.items():
        if sid in seen:
            findings.append(f"MAP: {pid} and {seen[sid]} share source id {sid}")
        seen[sid] = pid
        if sid not in src:
            findings.append(f"MAP: {pid} -> {sid} not found in source records")

    # projected active items must exist active in source with verbatim titles
    for pid, item in projected_active.items():
        sid = id_map.get(pid, pid)
        r = src.get(sid)
        if not r:
            findings.append(f"PROJECTION: active item {pid} has no source record")
            continue
        if r.get("status") != ACTIVE:
            findings.append(f"PROJECTION: {pid} shown active but source is {r.get('status')}: {r.get('title', '')[:60]!r}")
        if item.get("title") != r.get("title"):
            findings.append(f"PROJECTION: {pid} title drift: projection {item.get('title', '')[:60]!r} vs source {r.get('title', '')[:60]!r}")

    # archived/removed projection items must not be active in source
    for pid, item in projected_inactive.items():
        sid = id_map.get(pid, pid)
        r = src.get(sid)
        if r and r.get("status") == ACTIVE:
            findings.append(f"ARCHIVE: {pid} inactive in projection but source still active: {r.get('title', '')[:60]!r}")

    # every active source record must be projected - a map entry alone never
    # excuses a missing projection item
    projected_sids = {id_map.get(pid, pid) for pid in projected_active}
    for sid, r in src.items():
        if r.get("status") == ACTIVE and sid not in projected_sids:
            findings.append(f"SOURCE: active record not projected: {sid} {r.get('title', '')[:60]!r}")
    return findings


def check_schedules(reminder_items, schedule_entries, expectations=None, tz_offset_hours=0):
    """Reminder projection items vs plain schedule entries.

    expectations: optional list of {"ref": schedule ref, "item": projection
    item id, "title_pattern": regex or null}. With none, each entry pairs to
    the item whose id equals its ref. Orphan projection items (no entry) and
    unchecked entries (no pair) are both findings.
    """
    findings = []
    items = _dedupe(((i.get("id"), i) for i in reminder_items), "reminder item", findings)
    entries = _dedupe(((e.get("ref"), e) for e in schedule_entries), "schedule entry", findings)

    pairs = expectations if expectations is not None else \
        [{"ref": ref, "item": ref, "title_pattern": None} for ref in entries]
    covered_items, covered_refs = set(), set()
    seen_pairs = set()
    for p in pairs:
        key = (p.get("ref"), p.get("item"))
        if key in seen_pairs:
            findings.append(f"INPUT: duplicate expectation pair ref={p.get('ref')!r} item={p.get('item')!r}")
        seen_pairs.add(key)
        item = items.get(p.get("item"))
        if not item:
            findings.append(f"SCHEDULE: projection item {p.get('item')} missing")
            continue
        entry = entries.get(p.get("ref"))
        if not entry:
            findings.append(f"SCHEDULE: no schedule entry {p.get('ref')!r} for item {p.get('item')}")
            continue
        covered_items.add(p["item"])
        covered_refs.add(p["ref"])
        try:
            ok = date_text_matches(entry, item.get("note"), tz_offset_hours)
            core, _year = expected_date_text(entry, tz_offset_hours)
        except ValueError as e:
            findings.append(f"SCHEDULE: entry {p.get('ref')!r} invalid: {e}")
            continue
        if not ok:
            findings.append(f"SCHEDULE: {p['item']} note disagrees with {core!r} (entry {p['ref']}): {(item.get('note') or '')[:60]!r}")
        pat = p.get("title_pattern")
        if pat:
            try:
                if not re.search(pat, item.get("title") or ""):
                    findings.append(f"SCHEDULE: {p['item']} title does not match {pat!r}: {(item.get('title') or '')[:60]!r}")
            except re.error as e:
                findings.append(f"INPUT: bad title_pattern {pat!r}: {e}")

    for iid in items:
        if iid not in covered_items:
            findings.append(f"SCHEDULE: projection reminder {iid} has no schedule entry (orphan/stale)")
    for ref in entries:
        if ref not in covered_refs:
            findings.append(f"SCHEDULE: schedule entry {ref!r} not checked against any projection item")
    return findings


def payload_exact_match(served, expected):
    """Byte-exact comparison of a served payload against the expected one.
    No normalization: compare exactly what each side serves."""
    return served == expected


def _load(p):
    with open(p) as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser(description="Check a projection against source records and schedule entries.")
    ap.add_argument("--source", required=True, help="JSON list of source records")
    ap.add_argument("--projection", required=True, help="JSON projection document")
    ap.add_argument("--map", help="JSON object mapping projection id -> source id")
    ap.add_argument("--schedules", help="JSON list of schedule entries")
    ap.add_argument("--expectations", help="JSON list of schedule expectations")
    ap.add_argument("--reminders-section", default="reminders")
    ap.add_argument("--mirror-sections", default="todos",
                    help="comma-separated projection sections mirroring active source records")
    ap.add_argument("--tz-offset", type=int, default=0, help="hours ahead of UTC for date text")
    a = ap.parse_args()

    source = _load(a.source)
    projection = _load(a.projection)
    id_map = _load(a.map) if a.map else None
    findings = check_projection(source, projection, id_map,
                                tuple(x for x in a.mirror_sections.split(",") if x))
    if a.schedules:
        reminders = []
        for s in projection.get("sections", []):
            if s.get("id") == a.reminders_section:
                reminders = s.get("items", [])
                break
        findings += check_schedules(reminders, _load(a.schedules),
                                    _load(a.expectations) if a.expectations else None,
                                    a.tz_offset)
    for f in findings:
        print("FINDING:", f)
    print("CLEAN" if not findings else f"DRIFT: {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())

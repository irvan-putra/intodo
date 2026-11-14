import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from feed_consistency import (
    check_projection, check_schedules, expected_date_text, date_text_matches,
    payload_exact_match,
)

SOURCE = [
    {"id": "s1", "title": "Water the plants", "status": "active"},
    {"id": "s2", "title": "Read a chapter", "status": "active"},
    {"id": "s3", "title": "Old task", "status": "completed"},
]
FEED = {
    "generated_at": "2026-01-01T00:00:00Z",
    "sections": [
        {"id": "reminders", "items": [
            {"id": "r1", "title": "Pay the example bill", "note": "Monthly on the 5th, around 9:00 AM", "status": ""},
            {"id": "r2", "title": "Dentist checkup", "note": "Mon Jan 12, around 9:00 AM", "status": ""},
        ]},
        {"id": "todos", "items": [
            {"id": "t1", "title": "Water the plants", "note": "", "status": "pending"},
            {"id": "t2", "title": "Read a chapter", "note": "", "status": "pending"},
        ]},
        {"id": "archive", "items": [
            {"id": "t3", "title": "Old task", "note": "Completed Jan 1", "status": "completed"},
        ]},
    ],
}
ID_MAP = {"t1": "s1", "t2": "s2", "t3": "s3"}
SCHEDULES = [
    {"ref": "bill", "kind": "monthly", "day": 5},
    {"ref": "dentist", "kind": "once", "date": "2026-01-12"},
]
EXPECT = [
    {"ref": "bill", "item": "r1", "title_pattern": "(?i)bill"},
    {"ref": "dentist", "item": "r2", "title_pattern": None},
]


def feed_with(section_id, items):
    import copy
    f = copy.deepcopy(FEED)
    for s in f["sections"]:
        if s["id"] == section_id:
            s["items"] = items
    return f


class TestProjection(unittest.TestCase):
    def test_clean(self):
        self.assertEqual(check_projection(SOURCE, FEED, ID_MAP), [])

    def test_title_drift(self):
        bad = [dict(r, title="Read two chapters") if r["id"] == "s2" else r for r in SOURCE]
        self.assertTrue(any("title drift" in f for f in check_projection(bad, FEED, ID_MAP)))

    def test_completed_still_active_in_projection(self):
        bad = [dict(r, status="completed") if r["id"] == "s1" else r for r in SOURCE]
        self.assertTrue(any("shown active" in f for f in check_projection(bad, FEED, ID_MAP)))

    def test_unmapped_active_source(self):
        extra = SOURCE + [{"id": "s9", "title": "New idea", "status": "active"}]
        self.assertTrue(any("not projected" in f for f in check_projection(extra, FEED, ID_MAP)))

    def test_mapped_but_not_projected_is_flagged(self):
        # map entry alone must never excuse a missing projection item
        extra = SOURCE + [{"id": "s9", "title": "New idea", "status": "active"}]
        m = dict(ID_MAP, t9="s9")
        self.assertTrue(any("not projected" in f for f in check_projection(extra, FEED, m)))

    def test_archive_still_active_source(self):
        bad = [dict(r, status="active") if r["id"] == "s3" else r for r in SOURCE]
        self.assertTrue(any("ARCHIVE" in f for f in check_projection(bad, FEED, ID_MAP)))

    def test_map_sharing(self):
        bad_map = dict(ID_MAP, t4="s1")
        self.assertTrue(any("share source id" in f for f in check_projection(SOURCE, FEED, bad_map)))

    def test_duplicate_source_id_flagged(self):
        dup = SOURCE + [dict(SOURCE[0])]
        self.assertTrue(any("duplicate source record" in f for f in check_projection(dup, FEED, ID_MAP)))

    def test_duplicate_item_id_across_sections(self):
        bad = feed_with("archive", FEED["sections"][2]["items"] + [
            {"id": "t1", "title": "Water the plants", "note": "", "status": "completed"}])
        self.assertTrue(any("duplicate projection item" in f for f in check_projection(SOURCE, bad, ID_MAP)))

    def test_duplicate_section_flagged(self):
        import copy
        bad = copy.deepcopy(FEED)
        bad["sections"].append({"id": "todos", "items": []})
        self.assertTrue(any("duplicate projection section" in f for f in check_projection(SOURCE, bad, ID_MAP)))

    def test_malformed_source_record(self):
        bad = SOURCE + [{"id": "sX"}]
        self.assertTrue(any("malformed source record" in f for f in check_projection(bad, FEED, ID_MAP)))


class TestSchedules(unittest.TestCase):
    def test_clean(self):
        reminders = FEED["sections"][0]["items"]
        self.assertEqual(check_schedules(reminders, SCHEDULES, EXPECT), [])

    def test_date_text(self):
        self.assertEqual(expected_date_text({"kind": "monthly", "day": 3}), ("Monthly on the 3rd", None))
        self.assertEqual(expected_date_text({"kind": "once", "date": "2026-01-12"}), ("Mon Jan 12", 2026))

    def test_offset_preserved_not_relabeled(self):
        # 2026-01-12T01:30:00+09:00 is still Jan 11 in UTC - must convert, not re-label
        core, year = expected_date_text({"kind": "once", "date": "2026-01-12T01:30:00+09:00"}, 0)
        self.assertEqual((core, year), ("Sun Jan 11", 2026))

    def test_monthly_invalid_day_rejected(self):
        with self.assertRaises(ValueError):
            expected_date_text({"kind": "monthly", "day": 32})
        reminders = FEED["sections"][0]["items"]
        bad = [{"ref": "bill", "kind": "monthly", "day": 32}, SCHEDULES[1]]
        self.assertTrue(any("invalid" in f for f in check_schedules(reminders, bad, EXPECT)))

    def test_year_mismatch_fails(self):
        entry = {"kind": "once", "date": "2026-01-12"}
        self.assertTrue(date_text_matches(entry, "Mon Jan 12, 2026, around 9:00 AM"))
        self.assertFalse(date_text_matches(entry, "Mon Jan 12, 2027, around 9:00 AM"))
        self.assertTrue(date_text_matches(entry, "Mon Jan 12, around 9:00 AM"))

    def test_note_drift(self):
        bad = [dict(i, note="Monthly on the 6th") if i["id"] == "r1" else i for i in FEED["sections"][0]["items"]]
        self.assertTrue(any("disagrees" in f for f in check_schedules(bad, SCHEDULES, EXPECT)))

    def test_title_pattern(self):
        bad = [dict(i, title="Pay it") if i["id"] == "r1" else i for i in FEED["sections"][0]["items"]]
        self.assertTrue(any("does not match" in f for f in check_schedules(bad, SCHEDULES, EXPECT)))

    def test_orphan_reminder_item(self):
        extra = FEED["sections"][0]["items"] + [
            {"id": "r9", "title": "Stale reminder", "note": "Mon Jan 5", "status": ""}]
        self.assertTrue(any("orphan/stale" in f for f in check_schedules(extra, SCHEDULES, EXPECT)))

    def test_unchecked_schedule_entry(self):
        extra = SCHEDULES + [{"ref": "rent", "kind": "monthly", "day": 1}]
        self.assertTrue(any("not checked" in f for f in check_schedules(FEED["sections"][0]["items"], extra, EXPECT)))

    def test_duplicate_schedule_ref(self):
        dup = SCHEDULES + [dict(SCHEDULES[0])]
        self.assertTrue(any("duplicate schedule entry" in f for f in check_schedules(FEED["sections"][0]["items"], dup, EXPECT)))

    def test_duplicate_expectation_pair(self):
        dup = EXPECT + [dict(EXPECT[0])]
        self.assertTrue(any("duplicate expectation pair" in f for f in check_schedules(FEED["sections"][0]["items"], SCHEDULES, dup)))



    def test_single_digit_day_no_substring_false_pass(self):
        # 2026-01-01 is a Thursday: "Thu Jan 1" must not match "Thu Jan 12"
        entry = {"kind": "once", "date": "2026-01-01"}
        self.assertTrue(date_text_matches(entry, "Thu Jan 1, 2026, around 9:00 AM"))
        self.assertFalse(date_text_matches(entry, "Thu Jan 12, 2026, around 9:00 AM"))

    def test_projection_section_missing_id_flagged(self):
        import copy
        bad = copy.deepcopy(FEED)
        bad["sections"].append({"items": []})
        self.assertTrue(any("malformed projection section" in f
                            for f in check_projection(SOURCE, bad, ID_MAP)))

    def test_cli_malformed_section_no_crash(self):
        import json, subprocess, tempfile, os
        d = tempfile.mkdtemp()
        proj = {"generated_at": "x", "sections": [
            {"items": []},
            {"id": "reminders", "items": []},
        ]}
        paths = {}
        for name, obj in [("s", SOURCE), ("p", proj),
                          ("sch", [{"ref": "a", "kind": "monthly", "day": 5}])]:
            paths[name] = os.path.join(d, name + ".json")
            with open(paths[name], "w") as fh:
                json.dump(obj, fh)
        r = subprocess.run(
            [sys.executable, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                          "feed_consistency.py"),
             "--source", paths["s"], "--projection", paths["p"], "--schedules", paths["sch"]],
            capture_output=True, text=True)
        self.assertNotIn("Traceback", r.stderr)
        self.assertEqual(r.returncode, 1)  # findings expected, not a crash
        self.assertIn("malformed projection section", r.stdout)


class TestPayload(unittest.TestCase):
    def test_exact(self):
        self.assertTrue(payload_exact_match("abc123", "abc123"))
        self.assertFalse(payload_exact_match("abc123", "abc124"))
        self.assertFalse(payload_exact_match("abc123\n", "abc123"))  # truly byte-exact


if __name__ == "__main__":
    unittest.main()

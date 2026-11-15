#!/usr/bin/env python3
"""Fixture cases for the stage-6 state-archive validation.

Executes the REAL helper the instrumented test calls -
app/src/debug/python/emu_state_fixture.py (debug source set: present in
debug builds where the emulator qualification runs, never in release) -
against focused in-memory archives: correct compressed archive, marker
invisibility in compressed bytes, marker in the wrong member, excluded
log in the member list, missing expected member, corrupt input,
oversized compressed input, unexpected member, duplicate member,
non-regular member, missing ops members, unmarked ops members, and an
oversized member refused by the bounded read.

The helper returns only fixed outcome ids (ASCII bytes); the androidTest
emits them as beacons and fails on the first failure id.

Runs standalone (python3 ci/test_stage6_state_archive.py); no third-party
deps. Prints a PASS line on success.
"""

import gzip
import io
import os
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "debug", "python"))

import emu_state_fixture as fx

MARK_STATE = "EMU-STATE-MARKER-5dca71"
MARK_OPS = "EMU-OPS-MARKER-9f02e4"


def require(cond, message):
    if not cond:
        raise AssertionError(message)


NARROW_ERRORS = {"state_archive_input_error", "state_archive_bounds_error",
                 "state_archive_gzip_error", "state_archive_tar_error",
                 "state_archive_read_error"}
OUTCOME_NEXT = {
    "START": {"state_archive_ok", "state_archive_input_error",
              "state_archive_bounds_error", "state_archive_gzip_error",
              "state_archive_tar_error"},
    "state_archive_ok": {"state_logs_clean",
                          "state_logs_leak", "state_members_unexpected",
                          "state_archive_bounds_error", "state_archive_tar_error",
                          "state_archive_read_error"},
    "state_logs_clean": {"state_members_ok"},
    "state_members_ok": {"state_member_state_missing", "state_mark_state_fail",
                         "state_mark_state_ok"},
    "state_mark_state_ok": {"state_member_ops_missing", "state_mark_ops_fail",
                             "state_mark_ops_ok"},
}
TERMINAL = {"state_logs_leak", "state_members_unexpected",
            "state_member_state_missing", "state_mark_state_fail",
            "state_member_ops_missing", "state_mark_ops_fail",
            "state_mark_ops_ok"} | NARROW_ERRORS


def valid_sequence(items):
    if not items or len(items) > 7 or len(set(items)) != len(items):
        return False
    previous = "START"
    for i, item in enumerate(items):
        if item not in OUTCOME_NEXT.get(previous, set()):
            return False
        if item in TERMINAL and i != len(items) - 1:
            return False
        previous = item
    return items[-1] in TERMINAL


def outcomes(tar_bytes):
    result = fx.validate_state_archive(tar_bytes, MARK_STATE, MARK_OPS)
    require(isinstance(result, bytes), "helper must return bytes")
    require(0 < len(result) <= 256, "helper output must be nonempty and bounded")
    require(all(c == 44 or c == 95 or 97 <= c <= 122 for c in result),
            "helper output must contain only lowercase ASCII tokens")
    items = result.decode("ascii").split(",")
    require(valid_sequence(items), "helper output must be an ordered terminal outcome sequence")
    return items


def make_archive(members):
    """Build a gzip tar in memory. members: {name: text} for regular
    files, or {name: ("dir", None)} for a directory entry."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name, payload in members.items():
            if isinstance(payload, tuple):
                info = tarfile.TarInfo(name)
                info.type = tarfile.DIRTYPE
                tf.addfile(info)
                continue
            data = payload.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tf.addfile(info, io.BytesIO(data))
    return buf.getvalue()


# Empty, unknown, duplicate, unordered, and nonterminal streams all fail
# the same protocol enforced around the bridge in the instrumentation test.
for malformed in ([], ["unknown"], ["state_archive_ok"],
                  ["state_archive_ok", "state_logs_clean", "state_mark_ops_ok",
                   "state_mark_ops_fail"],
                  ["state_archive_ok", "state_archive_ok"]):
    require(not valid_sequence(malformed), "malformed outcome sequence accepted")

GOOD = make_archive({
    "bot_state.json": '{"mark": "%s", "provisioned": true}' % MARK_STATE,
    "ops-5.db": "ops %s" % MARK_OPS,
    "ops-9.db": "ops %s" % MARK_OPS,
})

# 1. correct compressed archive: every checkpoint passes in order
require(outcomes(GOOD) == [
    "state_archive_ok", "state_logs_clean", "state_members_ok",
    "state_mark_state_ok", "state_mark_ops_ok"], "correct archive")

# 2. markers are absent from the COMPRESSED bytes (the original
# test-predicate bug): only decompressed member reads can find them
raw = GOOD.decode("latin-1")
require(MARK_STATE not in raw and MARK_OPS not in raw,
        "compressed container must hide plaintext markers")

# 3. state marker in the wrong member: per-member attribution fails the
# state check even though the marker IS in the archive
require(outcomes(make_archive({
    "bot_state.json": '{"provisioned": true}',
    "ops-5.db": "ops %s %s" % (MARK_OPS, MARK_STATE),
}))[-1] == "state_mark_state_fail", "wrong-member marker")

# 4. excluded log in the member list: exact archive exclusion fires
# before any content check
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS,
    "secret-bot.log": "noise",
})) == ["state_archive_ok", "state_logs_leak"], "excluded log member")

# 5. missing expected member
require(outcomes(make_archive({
    "ops-5.db": MARK_OPS,
}))[-1] == "state_member_state_missing", "missing bot_state.json")

# 6. corrupt / non-gzip input
require(outcomes(b"not a gzip stream") == ["state_archive_gzip_error"],
        "corrupt archive")

# 7. compressed and expanded inputs are bounded before tar iteration
require(outcomes(b"x" * (16 * 1024 * 1024 + 1)) == ["state_archive_bounds_error"],
        "oversized compressed input")
require(outcomes(gzip.compress(b"x" * (16 * 1024 * 1024 + 1)))
        == ["state_archive_bounds_error"], "expanded gzip input cap")
require(outcomes(gzip.compress(b"not a tar archive"))
        == ["state_archive_tar_error"], "invalid tar structure")

# 7b. input contract: Chaquopy passes a Java byte[] as a jarray (buffer
# protocol), NOT a bytes object - the helper must normalize via bytes().
# array.array is the local buffer-protocol non-bytes stand-in.
import array
expected_good = [
    "state_archive_ok", "state_logs_clean", "state_members_ok",
    "state_mark_state_ok", "state_mark_ops_ok"]
require(outcomes(array.array("B", GOOD)) == expected_good,
        "unsigned buffer-protocol input")
signed_bytes = [value if value < 128 else value - 256 for value in GOOD]
require(outcomes(array.array("b", signed_bytes)) == expected_good,
        "signed buffer-protocol bytes preserve exact bit pattern")
class NotBuffer:
    pass
for invalid in (NotBuffer(), 10, 10**100, list(GOOD)):
    require(fx.validate_state_archive(invalid, MARK_STATE, MARK_OPS)
            == b"state_archive_input_error", "reject non-buffer input")
require(fx.validate_state_archive(memoryview(b"x" * (16 * 1024 * 1024 + 1)),
                                  MARK_STATE, MARK_OPS)
        == b"state_archive_bounds_error", "reject oversized buffer before copy")

# 8. unexpected member names include traversal and separator forms
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS,
    "evil.txt": "x",
}))[-1] == "state_members_unexpected", "unexpected member")
for bad_name in ("../evil", "a/b", "/abs", "x\\y"):
    require(outcomes(make_archive({bad_name: "x"}))[-1]
            == "state_members_unexpected", "unsafe member name: " + bad_name)

# 9. duplicate member name (dict cannot hold duplicates; build directly)
def make_duplicate_archive():
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name, text in (("bot_state.json", MARK_STATE),
                           ("ops-5.db", MARK_OPS),
                           ("ops-5.db", MARK_OPS)):
            data = text.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tf.addfile(info, io.BytesIO(data))
    return buf.getvalue()
require(outcomes(make_duplicate_archive())[-1] == "state_members_unexpected",
        "duplicate member")

# 10. non-regular member
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS,
    "ops-7.db": ("dir", None),
}))[-1] == "state_members_unexpected", "non-regular member")

def make_symlink_archive():
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        info = tarfile.TarInfo("ops-5.db")
        info.type = tarfile.SYMTYPE
        info.linkname = "bot_state.json"
        tf.addfile(info)
    return buf.getvalue()

require(outcomes(make_symlink_archive())[-1] == "state_members_unexpected",
        "symlink member")

# 11. member count is bounded (use distinct allowed names)
many = {"bot_state.json": MARK_STATE}
many.update({"ops-%d.db" % i: "x" for i in range(64)})
require(outcomes(make_archive(many))[-1] == "state_members_unexpected",
        "member count cap")

# 12. missing ops members
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
}))[-1] == "state_member_ops_missing", "missing ops members")

# 13. ops members present but unmarked
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": "unrelated",
}))[-1] == "state_mark_ops_fail", "unmarked ops members")

# 14. oversized member is refused by the bounded read
require(outcomes(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS + "x" * (4 * 1024 * 1024),
}))[-1] == "state_archive_bounds_error", "oversized member")

print("stage-6 state archive fixtures: PASS")

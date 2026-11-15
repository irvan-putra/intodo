#!/usr/bin/env python3
"""Reference fixture cases for the androidTest stage-6 state-archive
validation (MigrationEmuExportTest.kt).

The state blob inside a migration bundle is a gzip-compressed tar
(build_state_tar, migration.py mode "w:gz"). Because deflate never emits
a marker string as contiguous plaintext, the export test must OPEN the
archive and validate member names and per-member contents - scanning the
decrypted (still compressed) bytes proves nothing either way. This file
mirrors the Kotlin validation block call-for-call (tarfile.open(None,
"r:gz", BytesIO) -> getnames -> allowlist/exclusion -> bounded
extractfile reads) and pins its outcomes on focused fixture archives:
correct compressed archive, marker in the wrong member, excluded log in
the member list, missing expected member, corrupt input, unexpected
member, missing ops members, unmarked ops members, oversized member.

Runs standalone (python3 ci/test_stage6_state_archive.py); no third-party
deps. Prints a PASS line on success.
"""

import gzip
import io
import os
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))

MARK_STATE = "EMU-STATE-MARKER-5dca71"
MARK_OPS = "EMU-OPS-MARKER-9f02e4"
EXCLUDED = "secret-bot.log"
COMPRESSED_CAP = 16 * 1024 * 1024
READ_CAP = 4 * 1024 * 1024
EXPANDED_CAP = 16 * 1024 * 1024
MEMBER_MAX = 64
OPS_MEMBER_RE = __import__("re").compile(r"^ops-\d+\.db$")
SAFE_MEMBER_RE = __import__("re").compile(r"^[A-Za-z0-9._-]+$")


def require(cond, message):
    if not cond:
        raise AssertionError(message)


def validate_state_archive(tar_bytes):
    """Mirror the Kotlin streaming validation block and outcome order."""
    outcomes = []
    if len(tar_bytes) > COMPRESSED_CAP:
        return ["state_archive_error"]
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(tar_bytes), mode="rb") as source:
            expanded = bytearray()
            while True:
                chunk = source.read(min(8192, EXPANDED_CAP - len(expanded) + 1))
                if not chunk:
                    break
                expanded.extend(chunk)
                if len(expanded) > EXPANDED_CAP:
                    return ["state_archive_error"]
        tf = tarfile.open(None, "r|", io.BytesIO(expanded))
    except Exception:
        return ["state_archive_error"]
    outcomes.append("state_archive_ok")
    names = []
    seen = set()
    contents = {}
    expanded = 0
    try:
        while True:
            member = tf.next()
            if member is None:
                break
            name = str(member.name)
            if EXCLUDED in name:
                return outcomes + ["state_logs_leak"]
            allowed = name == "bot_state.json" or OPS_MEMBER_RE.match(name)
            if (len(names) >= MEMBER_MAX or not SAFE_MEMBER_RE.match(name)
                    or not allowed or name in seen or not member.isfile()):
                return outcomes + ["state_members_unexpected"]
            seen.add(name)
            if (member.size < 0 or member.size > READ_CAP
                    or expanded + member.size > EXPANDED_CAP):
                return outcomes + ["state_archive_error"]
            expanded += member.size
            source = tf.extractfile(member)
            if source is None:
                return outcomes + ["state_archive_error"]
            data = source.read(READ_CAP + 1)
            if len(data) != member.size or len(data) > READ_CAP:
                return outcomes + ["state_archive_error"]
            names.append(name)
            contents[name] = data.decode("latin-1")
    except Exception:
        return outcomes + ["state_archive_error"]

    outcomes.append("state_logs_clean")
    outcomes.append("state_members_ok")
    if "bot_state.json" not in names:
        return outcomes + ["state_member_state_missing"]
    if MARK_STATE not in contents["bot_state.json"]:
        return outcomes + ["state_mark_state_fail"]
    outcomes.append("state_mark_state_ok")
    ops_names = [name for name in names if OPS_MEMBER_RE.match(name)]
    if not ops_names:
        return outcomes + ["state_member_ops_missing"]
    if not any(MARK_OPS in contents[name] for name in ops_names):
        return outcomes + ["state_mark_ops_fail"]
    outcomes.append("state_mark_ops_ok")
    return outcomes

def make_archive(members):
    """Build a gzip tar in memory from {name: text} members."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        items = members.items() if hasattr(members, "items") else members
        for name, text in items:
            data = text.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tf.addfile(info, io.BytesIO(data))
    return buf.getvalue()


GOOD = make_archive({
    "bot_state.json": '{"mark": "%s", "provisioned": true}' % MARK_STATE,
    "ops-5.db": "ops %s" % MARK_OPS,
    "ops-9.db": "ops %s" % MARK_OPS,
})

# 1. correct compressed archive: every checkpoint passes in order
require(validate_state_archive(GOOD) == [
    "state_archive_ok", "state_logs_clean", "state_members_ok",
    "state_mark_state_ok", "state_mark_ops_ok"], "correct archive")

# 2. markers are absent from the COMPRESSED bytes (the original
# test-predicate bug): only decompressed member reads can find them
raw = GOOD.decode("latin-1")
require(MARK_STATE not in raw and MARK_OPS not in raw,
        "compressed container must hide plaintext markers")

# 3. state marker in the wrong member: per-member attribution fails the
# state check even though the marker IS in the archive
require(validate_state_archive(make_archive({
    "bot_state.json": '{"provisioned": true}',
    "ops-5.db": "ops %s %s" % (MARK_OPS, MARK_STATE),
}))[-1] == "state_mark_state_fail", "wrong-member marker")

# 4. excluded log in the member list: exact archive exclusion fires
# before any content read
require(validate_state_archive(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS,
    "secret-bot.log": "noise",
})) == ["state_archive_ok", "state_logs_leak"], "excluded log member")

# 5. missing expected member
require(validate_state_archive(make_archive({
    "ops-5.db": MARK_OPS,
}))[-1] == "state_member_state_missing", "missing bot_state.json")

# 6. corrupt / non-gzip input
require(validate_state_archive(b"not a gzip stream")
        == ["state_archive_error"], "corrupt archive")
require(validate_state_archive(GOOD + b"x" * (COMPRESSED_CAP + 1))
        == ["state_archive_error"], "compressed input bound")
require(validate_state_archive(gzip.compress(b"x" * (EXPANDED_CAP + 1)))
        == ["state_archive_error"], "expanded stream bound before tar parsing")

# 7. unexpected member name
require(validate_state_archive(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS,
    "evil.txt": "x",
}))[-1] == "state_members_unexpected", "unexpected member")

# 8. duplicate exact member names are rejected before marker attribution
def make_archive_items(items):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name, text in items:
            data = text.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tf.addfile(info, io.BytesIO(data))
    return buf.getvalue()

require(validate_state_archive(make_archive_items([
    ("bot_state.json", MARK_STATE), ("bot_state.json", "second"),
    ("ops-5.db", MARK_OPS),
]))[-1] == "state_members_unexpected", "duplicate member")

# 9. symlink and traversal members are rejected (no extraction takes place)
def make_special_archive(name, typeflag, linkname=""):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        info = tarfile.TarInfo(name)
        info.type = typeflag
        info.linkname = linkname
        tf.addfile(info)
    return buf.getvalue()

require(validate_state_archive(make_special_archive(
    "ops-5.db", tarfile.SYMTYPE, "bot_state.json"))[-1]
        == "state_members_unexpected", "symlink member")
require(validate_state_archive(make_archive({
    "../evil": MARK_STATE,
}))[-1] == "state_members_unexpected", "path traversal member")

# 10. member count is bounded
many = [("bot_state.json", MARK_STATE)] + [
    ("ops-%d.db" % i, "x") for i in range(MEMBER_MAX)
]
require(validate_state_archive(make_archive_items(many))[-1]
        == "state_members_unexpected", "too many members")

# 11. missing ops members
require(validate_state_archive(make_archive({
    "bot_state.json": MARK_STATE,
}))[-1] == "state_member_ops_missing", "missing ops members")

# 12. ops members present but unmarked
require(validate_state_archive(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": "unrelated",
}))[-1] == "state_mark_ops_fail", "unmarked ops members")

# 13. oversized member is refused before reading its payload
require(validate_state_archive(make_archive({
    "bot_state.json": MARK_STATE,
    "ops-5.db": MARK_OPS + "x" * (READ_CAP + 1),
}))[-1] == "state_archive_error", "oversized member")

print("stage-6 state archive fixtures: PASS")

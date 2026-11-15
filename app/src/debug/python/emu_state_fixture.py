"""Fixture-only state-archive validation for MigrationEmuExportTest.

This module lives in the DEBUG source set: Chaquopy packages the
python/ subdirectory of every source set, so this file is present in
debug builds (where the emulator qualification runs) and never in
release builds.

The instrumented test crosses the Python bridge exactly once with the
decrypted (still gzip-compressed) state archive and the fixture marker
constants; this module performs the whole validation in memory and
returns only fixed outcome ids. No member names, member contents, or
exception text ever cross the bridge. Nothing is extracted to the
filesystem. The same module is executed directly by the local fixture
cases in ci/test_stage6_state_archive.py.
"""

import gzip
import io
import re
import tarfile

_COMPRESSED_CAP = 16 * 1024 * 1024
_EXPANDED_CAP = 16 * 1024 * 1024
_MEMBER_CAP = 4 * 1024 * 1024
_MAX_MEMBERS = 64
_OPS_MEMBER_RE = re.compile(r"^ops-\d+\.db$")
_SAFE_MEMBER_RE = re.compile(r"^[A-Za-z0-9._-]+$")
_EXCLUDED = "secret-bot.log"


def _join(outcomes, last):
    return ",".join(outcomes + [last]).encode("ascii")


def _bounded_gzip_expand(tar_gz):
    """Return bounded plain tar bytes or None for invalid/oversized gzip."""
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(tar_gz), mode="rb") as source:
            out = bytearray()
            while True:
                chunk = source.read(min(8192, _EXPANDED_CAP - len(out) + 1))
                if not chunk:
                    return bytes(out)
                out.extend(chunk)
                if len(out) > _EXPANDED_CAP:
                    return None
    except Exception:
        return None


def validate_state_archive(tar_gz, state_marker, ops_marker):
    """Validate the decrypted gzip-compressed state archive using bounded
    gzip expansion and streaming tar iteration. Returns ASCII bytes with
    fixed outcome ids in order, ending at first failure or final success."""
    if not isinstance(tar_gz, (bytes, bytearray)) or len(tar_gz) > _COMPRESSED_CAP:
        return b"state_archive_error"
    expanded_tar = _bounded_gzip_expand(tar_gz)
    if expanded_tar is None:
        return b"state_archive_error"
    try:
        tf = tarfile.open(None, "r|", io.BytesIO(expanded_tar))
    except Exception:
        return b"state_archive_error"
    outcomes = ["state_archive_ok"]
    names = []
    seen = set()
    contents = {}
    expanded = 0
    try:
        with tf:
            for member in tf:
                name = member.name
                if _EXCLUDED in name:
                    return _join(outcomes, "state_logs_leak")
                if (len(names) >= _MAX_MEMBERS
                        or name in seen
                        or not member.isfile()
                        or not _SAFE_MEMBER_RE.fullmatch(name)
                        or (name != "bot_state.json"
                            and not _OPS_MEMBER_RE.fullmatch(name))):
                    return _join(outcomes, "state_members_unexpected")
                seen.add(name)
                names.append(name)
                if member.size < 0 or member.size > _MEMBER_CAP:
                    return _join(outcomes, "state_archive_error")
                expanded += member.size
                if expanded > _EXPANDED_CAP:
                    return _join(outcomes, "state_archive_error")
                try:
                    source = tf.extractfile(member)
                    if source is None:
                        return _join(outcomes, "state_archive_error")
                    data = source.read(_MEMBER_CAP + 1)
                except Exception:
                    return _join(outcomes, "state_archive_error")
                if len(data) != member.size or len(data) > _MEMBER_CAP:
                    return _join(outcomes, "state_archive_error")
                contents[name] = data.decode("latin-1")
    except Exception:
        # Parser/runtime errors are terminal, never an empty valid archive.
        return _join(outcomes, "state_archive_error")
    outcomes.extend(("state_logs_clean", "state_members_ok"))
    if "bot_state.json" not in contents:
        return _join(outcomes, "state_member_state_missing")
    if state_marker not in contents["bot_state.json"]:
        return _join(outcomes, "state_mark_state_fail")
    outcomes.append("state_mark_state_ok")
    ops = [name for name in names if _OPS_MEMBER_RE.fullmatch(name)]
    if not ops:
        return _join(outcomes, "state_member_ops_missing")
    if not any(ops_marker in contents[name] for name in ops):
        return _join(outcomes, "state_mark_ops_fail")
    outcomes.append("state_mark_ops_ok")
    return ",".join(outcomes).encode("ascii")

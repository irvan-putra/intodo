#!/usr/bin/env python3
"""Allowlist validators for round-trip evidence (public-repo policy).

Two modes, both print NOTHING but a verdict and exit 0/1:

  emu_rt_validate.py json <name> <path>
      The pulled file must be a top-level JSON object whose keys are
      within <name>'s allowlist, with per-key type and bounded-value
      checks against the known fixture constants. Exact-set files
      (written atomically on success) must match their key set
      exactly; the incremental import-assertions report may hold any
      subset (it grows phase by phase) but must carry finalPhase.
      Anything else exits 1: the caller drops the file and records a
      status only, so unexpected content is never uploaded.

  emu_rt_validate.py code <path>
      The runner-local one-time transfer code must be exactly the
      shape migration.py's generate_transfer_code produces: 44 chars,
      urlsafe-base64 of exactly 32 bytes (43 alphabet chars + '=').
      Checked WITHOUT printing it.
"""
import base64
import json
import re
import sys

ADDR = "fixture-bot@emu.fixture"
MAX_BUNDLE_BYTES = 64 * 1024 * 1024
PHASES = {"handoff", "python-start", "virgin-check", "transaction",
          "state-landed", "settings", "leftovers", "done"}
LEFTOVER_NAMES = {"IMPORT_PENDING.json", "IMPORT_PREFS.json",
                  "IMPORT_PRIOR_PREFS.json", ".import-staging"}
SETTINGS_KEYS = {"priv", "feed", "feed_gen", "feed_url", "status",
                 "feed_at", "canvas_layout"}


def is_bool(v):
    return v is True or v is False


def is_count(v):
    return not is_bool(v) and isinstance(v, int) and 0 <= v <= 8


def is_true(v):
    return v is True


def is_addr(v):
    return v == ADDR


def is_bundle_bytes(v):
    return not is_bool(v) and isinstance(v, int) and 0 < v <= MAX_BUNDLE_BYTES


def is_code_len(v):
    return v == 44 and not is_bool(v) and isinstance(v, int)


def is_ok_str(v):
    return v == "ok"


def is_settings(v):
    return (isinstance(v, dict) and set(v) <= SETTINGS_KEYS
            and all(is_bool(x) for x in v.values()))


def is_leftovers(v):
    return (isinstance(v, list)
            and all(isinstance(x, str) and x in LEFTOVER_NAMES for x in v))


def is_phase(v):
    return isinstance(v, str) and v in PHASES


ASSERTION_SPEC = {
    "bundlePresent": is_bool,
    "codePresent": is_bool,
    "codeShapeOk": is_bool,
    "virginAccountCount": is_count,
    "runningMarkerAbsent": is_bool,
    "prepareAddrMatches": is_bool,
    "journalStagedBeforeMutation": is_bool,
    "prefsStagedBeforeCommit": is_bool,
    "prefsApplied": is_bool,
    "admitted": is_bool,
    "commitAddrMatches": is_bool,
    "accountCountAfter": is_count,
    "importedAddrMatches": is_bool,
    "stateMarkerLanded": is_bool,
    "stateAddrLanded": is_bool,
    "opsFilesLanded": is_count,
    "opsMarkersLanded": is_bool,
    "settings": is_settings,
    "leftoversPresent": is_leftovers,
    "finalPhase": is_phase,
}

SCHEMAS = {
    # name -> (spec, exact_key_set_required)
    "smoke-ok.json": ({"python": is_ok_str, "core": is_ok_str,
                       "fixtureSeed": is_ok_str}, True),
    "export-done.json": ({"bundleBytes": is_bundle_bytes,
                          "codeLen": is_code_len, "addr": is_addr}, True),
    "import-done.json": ({"addr": is_addr, "settingsApplied": is_true,
                          "journalResolved": is_true}, True),
    "import-assertions.json": (ASSERTION_SPEC, False),
}


def check_json(name, path):
    schema = SCHEMAS.get(name)
    if schema is None:
        return False
    spec, exact = schema
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        return False
    if not isinstance(data, dict):
        return False
    keys = set(data)
    if exact:
        if keys != set(spec):
            return False
    else:
        if not keys <= set(spec):
            return False
        if "finalPhase" not in keys:
            return False
    for key, validator in spec.items():
        if key in data and not validator(data[key]):
            return False
    return True


def check_code(path):
    try:
        with open(path, "rb") as f:
            raw = f.read()
        # The export writer emits no newline. Accept exactly that shape,
        # or one terminal LF for a line-oriented transport; reject all
        # other whitespace and standard-base64 '+'/'/' characters.
        if raw.endswith(b"\n"):
            raw = raw[:-1]
        if re.fullmatch(rb"[A-Za-z0-9_-]{43}=", raw) is None:
            return False
        return len(base64.b64decode(raw, altchars=b"-_", validate=True)) == 32
    except Exception:
        return False


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "json":
        ok = check_json(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] == "code":
        ok = check_code(sys.argv[2])
    else:
        ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

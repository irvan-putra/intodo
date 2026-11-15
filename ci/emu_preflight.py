#!/usr/bin/env python3
"""Desktop preflight for the on-device migration qualification.

Runs migration.py's REAL export/import legs against REAL Delta Chat core
(deltachat-rpc-server, same v2.69.0 base as the app's fork) with the
emu_fixture offline account - no network, no real keys, no stubs. This
proves the core legs the JVM suite stubs out, on the desktop, before the
slow arm64 emulator leg repeats them under Chaquopy.

Scenarios (all fixture-only):
  1. happy path: seed -> export_migration -> import_prepare ->
     import_commit -> landed state/settings verified
  2. wrong-code refusal: nothing staged, nothing landed, no journal
  3. abort path: import_prepare -> import_abort -> account removed,
     leftovers cleared
  4. crash after journal "committing": recover_import rolls FORWARD

Exit 0 only when every scenario passes.
"""

import json
import os
import shutil
import sys
import tempfile

# Locate the app python sources relative to this script (ci/ -> repo root).
HERE = os.path.dirname(os.path.abspath(__file__))
APP_PY = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, APP_PY)

import migration as M  # noqa: E402
import emu_fixture  # noqa: E402

MARK_STATE = "EMU-STATE-MARKER-5dca71"
MARK_OPS = "EMU-OPS-MARKER-9f02e4"
MARK_PRIV = "EMU-PRIV-MARKER-31c7aa"
FIXTURE_LAYOUT = '{"v":1,"emu":true}'


def find_rpc_server():
    """deltachat-rpc-server binary, presented as librpcsrv.so (the name
    bot_runtime._rpc uses inside the app)."""
    import deltachat_rpc_server
    pkg = os.path.dirname(deltachat_rpc_server.__file__)
    for root, _, files in os.walk(pkg):
        for f in files:
            if "deltachat-rpc-server" in f or "deltachat_rpc_server" in f:
                p = os.path.join(root, f)
                if os.access(p, os.X_OK) and not f.endswith(".py"):
                    return p
    # fallback: the package's documented entry
    candidate = os.path.join(pkg, "deltachat-rpc-server")
    if os.path.exists(candidate):
        return candidate
    raise RuntimeError("deltachat-rpc-server binary not found under %s" % pkg)


def make_shim(tmp):
    shim = os.path.join(tmp, "nativelib")
    os.makedirs(shim)
    target = os.path.join(shim, "librpcsrv.so")
    os.symlink(find_rpc_server(), target)
    os.chmod(find_rpc_server(), 0o755)
    return shim


def seed_source(files_dir):
    """Mirror what the app's export flow sees: provisioned bot state,
    per-chat ops stores, and the activity's EXPORT_PREFS.json snapshot."""
    os.makedirs(files_dir, exist_ok=True)
    with open(os.path.join(files_dir, "bot_state.json"), "w") as f:
        json.dump({"provisioned": True, "bot_addr": emu_fixture.FIXTURE_ADDR,
                   "owner_addr": "owner@example.test", "mark": MARK_STATE}, f)
    for cid in (5, 9):
        with open(os.path.join(files_dir, "ops-%d.db" % cid), "wb") as f:
            f.write(("ops-%d-" % cid).encode() + MARK_OPS.encode() + b"\x00")
    # exactly the shape BotActivity.writeExportPrefsSnapshot writes
    prefs = {"instinct_widget": {
        "priv": "fixture-priv-" + MARK_PRIV,
        "pub": "fixture-pub",
        "feed": "fixture-feed-ciphertext",
        "feed_gen": "7",
        "feed_url": "https://feed.example.test/f",
        "status": "ok"},
        "review_canvas": {"layout": FIXTURE_LAYOUT}}
    with open(os.path.join(files_dir, "EXPORT_PREFS.json"), "w") as f:
        json.dump(prefs, f)
    emu_fixture.seed_account(SHIM, files_dir)


def assert_landed(dst):
    with open(os.path.join(dst, "bot_state.json")) as f:
        st = json.load(f)
    assert st.get("bot_addr") == emu_fixture.FIXTURE_ADDR, st
    assert st.get("mark") == MARK_STATE, st
    for cid in (5, 9):
        with open(os.path.join(dst, "ops-%d.db" % cid), "rb") as f:
            assert MARK_OPS.encode() in f.read(), cid


def consume_staged_prefs(dst):
    """Mirror StagedPrefsApplier: the staged settings exist BETWEEN prepare
    and commit and are consumed by the applying side, so assert them after
    prepare and delete the staged copy (the applier does the same)."""
    with open(os.path.join(dst, "IMPORT_PREFS.json")) as f:
        prefs = json.load(f)
    assert prefs["instinct_widget"]["priv"].endswith(MARK_PRIV), prefs
    assert prefs["review_canvas"]["layout"] == FIXTURE_LAYOUT, prefs
    os.unlink(os.path.join(dst, "IMPORT_PREFS.json"))


def scenario_happy(tmp):
    src = os.path.join(tmp, "happy-src")
    dst = os.path.join(tmp, "happy-dst")
    os.makedirs(dst)
    seed_source(src)
    out_dir = os.path.join(src, "export-out")
    M.export_migration(SHIM, src, out_dir)
    # plaintext snapshot consumed by the export, never left behind
    assert not os.path.exists(os.path.join(src, "EXPORT_PREFS.json"))
    with open(os.path.join(src, "EXPORT_RESULT")) as f:
        res = json.load(f)
    bundle, code = res["bundle"], res["code"]
    assert len(code) == 44, code
    # no plaintext markers anywhere in the bundle
    raw = open(bundle, "rb").read()
    for m in (MARK_STATE, MARK_OPS, MARK_PRIV):
        assert m.encode() not in raw, m
    addr = M.import_prepare(SHIM, dst, bundle, code)
    assert addr == emu_fixture.FIXTURE_ADDR, addr
    # durable journal between the phases
    assert M._read_pending(dst)["phase"] == "prepared"
    consume_staged_prefs(dst)
    M.import_commit(SHIM, dst)
    assert M._read_pending(dst) is None
    assert_landed(dst)
    print("PASS happy path")


def scenario_wrong_code(tmp):
    src = os.path.join(tmp, "wc-src")
    dst = os.path.join(tmp, "wc-dst")
    os.makedirs(dst)
    seed_source(src)
    M.export_migration(SHIM, src, os.path.join(src, "export-out"))
    with open(os.path.join(src, "EXPORT_RESULT")) as f:
        bundle = json.load(f)["bundle"]
    try:
        M.import_prepare(SHIM, dst, bundle, "x" * 44)
        raise AssertionError("wrong code ACCEPTED")
    except Exception:
        pass
    assert M._read_pending(dst) is None
    assert not os.path.exists(os.path.join(dst, "bot_state.json"))
    assert not os.path.exists(os.path.join(dst, "IMPORT_PREFS.json"))
    print("PASS wrong-code refusal")


def scenario_abort(tmp):
    src = os.path.join(tmp, "ab-src")
    dst = os.path.join(tmp, "ab-dst")
    os.makedirs(dst)
    seed_source(src)
    M.export_migration(SHIM, src, os.path.join(src, "export-out"))
    with open(os.path.join(src, "EXPORT_RESULT")) as f:
        res = json.load(f)
    M.import_prepare(SHIM, dst, res["bundle"], res["code"])
    assert M.import_abort(SHIM, dst) is True
    assert M._read_pending(dst) is None
    assert not os.path.exists(os.path.join(dst, "bot_state.json"))
    assert not os.path.exists(os.path.join(dst, M.STAGING_DIR))
    print("PASS abort rollback")


def scenario_commit_crash(tmp):
    src = os.path.join(tmp, "cc-src")
    dst = os.path.join(tmp, "cc-dst")
    os.makedirs(dst)
    seed_source(src)
    M.export_migration(SHIM, src, os.path.join(src, "export-out"))
    with open(os.path.join(src, "EXPORT_RESULT")) as f:
        res = json.load(f)
    M.import_prepare(SHIM, dst, res["bundle"], res["code"])
    consume_staged_prefs(dst)  # settings were applied before the flip
    # Simulate the crash AFTER the durable decision point but before the
    # landing finished: the journal is exactly what commit wrote first.
    M._write_pending(dst, "committing", res.get("addr") or
                     emu_fixture.FIXTURE_ADDR)
    directive = M.recover_import(SHIM, dst)
    assert directive == "rolled_forward", directive
    assert M._read_pending(dst) is None
    assert_landed(dst)
    print("PASS commit-crash roll-forward")


def main():
    tmp = tempfile.mkdtemp(prefix="emu-preflight-")
    global SHIM
    SHIM = make_shim(tmp)
    try:
        scenario_happy(tmp)
        scenario_wrong_code(tmp)
        scenario_abort(tmp)
        scenario_commit_crash(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("PREFLIGHT OK: all 4 scenarios passed against real core (offline)")


if __name__ == "__main__":
    main()

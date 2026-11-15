"""Desktop-host tests for migration.py (no core, no network).

The core RPC legs (export_backup / import_backup against a live account)
are exercised on-device only; everything up to and around them is covered
here: bundle structure, hash verification, encryption, wrong-code and
tamper refusal, state include/exclude rules, staging safety, atomic commit,
and import failure preservation (with a fake rpc factory).
"""

import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import migration as M

MARK_STATE = "STATE-MARKER-7f3c2e1a"
MARK_DB = "PRIVATE-DB-MARKER-9b04d2"


def _files_dir():
    d = tempfile.mkdtemp(prefix="mig-src-")
    with open(os.path.join(d, "bot_state.json"), "w") as f:
        json.dump({"provisioned": True, "bot_addr": "bot@example.test",
                   "owner_addr": "owner@example.test", "mark": MARK_STATE}, f)
    for cid in (5, 9):
        with open(os.path.join(d, "ops-%d.db" % cid), "wb") as f:
            f.write(("ops-store-%d-" % cid).encode() + b"\x00\x01")
    # things that must NOT travel:
    for n in ("bot.log", "invite.svg", "invite.txt", "validation.log",
              "STOP", "RUNNING", "INVITE_REQ", "INVITE_DONE", M.RESULT_FILE):
        with open(os.path.join(d, n), "w") as f:
            f.write("secret-%s" % n)
    os.makedirs(os.path.join(d, "accounts"))
    return d


def _fake_backup(d):
    p = os.path.join(d, "delta-chat-backup-2026-11-12.tar")
    with open(p, "wb") as f:
        f.write(b"fake-tar:" + MARK_DB.encode() + b":" + os.urandom(64))
    return p


class TestCode(unittest.TestCase):
    def test_code_shape(self):
        c = M.generate_transfer_code()
        self.assertEqual(len(c), 44)
        self.assertRegex(c, r"^[A-Za-z0-9_-]+={0,2}$")
        M._fernet(c, M.STATE_DOMAIN)  # must be a valid Fernet key source

    def test_codes_unique(self):
        self.assertNotEqual(M.generate_transfer_code(),
                            M.generate_transfer_code())


class TestStateArchive(unittest.TestCase):
    def setUp(self):
        self.d = _files_dir()
        self.addCleanup(shutil.rmtree, self.d, True)

    def test_include_exclude(self):
        names = M.state_members(self.d)
        self.assertEqual(sorted(names), ["bot_state.json", "ops-5.db", "ops-9.db"])

    def test_tar_roundtrip(self):
        data = M.build_state_tar(self.d)
        staging = tempfile.mkdtemp(prefix="mig-stage-")
        self.addCleanup(shutil.rmtree, staging, True)
        M.stage_state(data, staging)
        for n in ("bot_state.json", "ops-5.db", "ops-9.db"):
            with open(os.path.join(self.d, n), "rb") as a, \
                 open(os.path.join(staging, n), "rb") as b:
                self.assertEqual(a.read(), b.read())

    def test_traversal_refused(self):
        for bad in ("../evil", "a/b", "/abs", "ops-5.db/../x", "x\\y"):
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode="w:gz") as tar:
                payload = b"x"
                info = tarfile.TarInfo(bad)
                info.size = len(payload)
                tar.addfile(info, io.BytesIO(payload))
            stage = tempfile.mkdtemp(prefix="mig-bad-")
            try:
                with self.assertRaises(M.MigrationError, msg=bad):
                    M.stage_state(buf.getvalue(), stage)
            finally:
                shutil.rmtree(stage, True)


class TestBundle(unittest.TestCase):
    def setUp(self):
        self.d = _files_dir()
        self.addCleanup(shutil.rmtree, self.d, True)
        self.backup = _fake_backup(self.d)

    def _build(self):
        out = os.path.join(self.d, "out.dcmig")
        return M.build_bundle(self.backup, self.d, out)

    def test_roundtrip_and_no_plaintext(self):
        out, code = self._build()
        with open(out, "rb") as f:
            raw = f.read()
        self.assertNotIn(MARK_STATE.encode(), raw)
        self.assertNotIn(MARK_DB.encode(), raw)
        self.assertNotIn(b"owner@example.test", raw)
        parsed = M.parse_bundle(out)
        backup = M._decrypt(code, M.WRAP_DOMAIN, parsed["backup"])
        self.assertIn(MARK_DB.encode(), backup)
        state = M._decrypt(code, M.STATE_DOMAIN, parsed["state_blob"])
        import gzip
        self.assertIn(MARK_STATE.encode(), gzip.decompress(state))

    def test_members_exact(self):
        out, _ = self._build()
        with zipfile.ZipFile(out) as z:
            self.assertEqual(sorted(z.namelist()),
                             ["account.backup", "manifest.json", "state.bin"])

    def test_wrong_code(self):
        out, _ = self._build()
        parsed = M.parse_bundle(out)
        with self.assertRaises(M.MigrationError):
            M._decrypt(M.generate_transfer_code(), M.STATE_DOMAIN,
                       parsed["state_blob"])
        with self.assertRaises(M.MigrationError):
            M._decrypt(M.generate_transfer_code(), M.WRAP_DOMAIN,
                       parsed["backup"])

    def test_tamper_payload(self):
        out, _ = self._build()
        parsed = M.parse_bundle(out)
        blob = bytearray(parsed["state_blob"])
        blob[10] ^= 0xFF
        tmp = os.path.join(self.d, "tampered.dcmig")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
            z.writestr("manifest.json", json.dumps(parsed["manifest"]))
            z.writestr("account.backup", parsed["backup"])
            z.writestr("state.bin", bytes(blob))
        with self.assertRaises(M.MigrationError):
            M.parse_bundle(tmp)

    def test_tamper_manifest(self):
        out, _ = self._build()
        parsed = M.parse_bundle(out)
        man = dict(parsed["manifest"], state_sha256="0" * 64)
        tmp = os.path.join(self.d, "tampered2.dcmig")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
            z.writestr("manifest.json", json.dumps(man))
            z.writestr("account.backup", parsed["backup"])
            z.writestr("state.bin", parsed["state_blob"])
        with self.assertRaises(M.MigrationError):
            M.parse_bundle(tmp)

    def test_wrong_format(self):
        out, _ = self._build()
        parsed = M.parse_bundle(out)
        man = dict(parsed["manifest"], format="other/9")
        tmp = os.path.join(self.d, "fmt.dcmig")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
            z.writestr("manifest.json", json.dumps(man))
            z.writestr("account.backup", parsed["backup"])
            z.writestr("state.bin", parsed["state_blob"])
        with self.assertRaises(M.MigrationError):
            M.parse_bundle(tmp)


class TestCommit(unittest.TestCase):
    def setUp(self):
        self.d = _files_dir()
        self.addCleanup(shutil.rmtree, self.d, True)
        self.staging = tempfile.mkdtemp(prefix="mig-stage-")
        self.addCleanup(shutil.rmtree, self.staging, True)
        M.stage_state(M.build_state_tar(self.d), self.staging)

    def test_commit_fresh(self):
        dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, dst, True)
        M.commit_state(self.staging, dst)
        self.assertTrue(os.path.exists(os.path.join(dst, "bot_state.json")))
        self.assertTrue(os.path.exists(os.path.join(dst, "ops-5.db")))
        self.assertFalse(os.path.exists(self.staging))

    def test_commit_replaces_and_cleans_backup(self):
        with open(os.path.join(self.d, "bot_state.json"), "w") as f:
            f.write('{"old": true}')
        M.commit_state(self.staging, self.d)
        with open(os.path.join(self.d, "bot_state.json")) as f:
            self.assertIn("owner_addr", f.read())
        self.assertFalse(
            os.path.exists(os.path.join(self.d, "bot_state.json.pre-import")))

    def test_commit_failure_restores(self):
        with open(os.path.join(self.d, "bot_state.json"), "w") as f:
            f.write('{"old": true}')
        os.chmod(self.d, 0o555)  # no writes in the dir -> every replace fails
        try:
            with self.assertRaises(M.MigrationError):
                M.commit_state(self.staging, self.d)
        finally:
            os.chmod(self.d, 0o755)
        with open(os.path.join(self.d, "bot_state.json")) as f:
            self.assertEqual(f.read(), '{"old": true}')
        self.assertFalse(
            os.path.exists(os.path.join(self.d, "bot_state.json.pre-import")))


class _FakeAccount:
    def __init__(self, configured=False, addr=None):
        self._configured = configured
        self._addr = addr
        self.removed = False
        self.imported = None

    def is_configured(self):
        return self._configured

    def list_transports(self):
        return [{"addr": self._addr}] if self._addr else []

    def get_config(self, key):
        return None

    def import_backup(self, path, code):
        self.imported = (path, code)
        self._configured = True
        self._addr = "bot@example.test"

    def remove(self):
        self.removed = True
        self._configured = False


class _FakeDC:
    def __init__(self, accounts):
        self._accounts = accounts

    def get_all_accounts(self):
        return list(self._accounts)

    def add_account(self):
        a = _FakeAccount()
        self._accounts.append(a)
        return a


class _FakeRpc:
    def close(self):
        pass


class TestImportFlow(unittest.TestCase):
    """Import with a fake RPC layer: proves ordering, refusal, identity
    check and failure preservation without a core."""

    def setUp(self):
        self.src = _files_dir()
        self.addCleanup(shutil.rmtree, self.src, True)
        self.backup = _fake_backup(self.src)
        self.bundle, self.code = M.build_bundle(
            self.backup, self.src, os.path.join(self.src, "out.dcmig"))
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, self.dst, True)

    def _factory(self, dc):
        return lambda native, files: (_FakeRpc(), dc)

    def test_happy_path(self):
        dc = _FakeDC([])
        addr = M.import_migration("nl", self.dst, self.bundle, self.code,
                                  _rpc_factory=self._factory(dc))
        self.assertEqual(addr, "bot@example.test")
        with open(os.path.join(self.dst, "bot_state.json")) as f:
            st = json.load(f)
        self.assertEqual(st["owner_addr"], "owner@example.test")
        self.assertTrue(os.path.exists(os.path.join(self.dst, "ops-9.db")))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.BACKUP_TMP)))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.STAGING_DIR)))

    def test_wrong_code_mutates_nothing(self):
        dc = _FakeDC([])
        with self.assertRaises(M.MigrationError):
            M.import_migration("nl", self.dst, self.bundle,
                               M.generate_transfer_code(),
                               _rpc_factory=self._factory(dc))
        self.assertEqual(os.listdir(self.dst), [])
        self.assertEqual(dc.get_all_accounts(), [])

    def test_refuses_existing_configured_account(self):
        dc = _FakeDC([_FakeAccount(configured=True)])
        with self.assertRaises(M.MigrationError):
            M.import_migration("nl", self.dst, self.bundle, self.code,
                               _rpc_factory=self._factory(dc))
        # no bot files may land and no account may be added:
        self.assertFalse(os.path.exists(os.path.join(self.dst, "bot_state.json")))
        self.assertEqual(len(dc.get_all_accounts()), 1)

    def test_failed_account_import_removed(self):
        class BadDC(_FakeDC):
            def add_account(self):
                a = _FakeAccount()
                orig = a.import_backup
                def boom(path, code):
                    raise RuntimeError("core refused the backup")
                a.import_backup = boom
                self._accounts.append(a)
                return a
        dc = BadDC([])
        with self.assertRaises(RuntimeError):
            M.import_migration("nl", self.dst, self.bundle, self.code,
                               _rpc_factory=self._factory(dc))
        self.assertTrue(dc.get_all_accounts()[0].removed)
        self.assertFalse(os.path.exists(os.path.join(self.dst, "bot_state.json")))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.BACKUP_TMP)))


if __name__ == "__main__":
    unittest.main()


MARK_PRIV = "EC-PRIV-MARKER-b64url"


def _prefs_snapshot():
    return {
        "instinct_widget": {
            "priv": MARK_PRIV, "pub": "EC-PUB", "feed": "{\"items\":[]}",
            "feed_at": 1763222400000, "feed_gen": "2026-11-15T12:00:00+0700",
            "feed_url": "https://feed.example/x", "status": "synced just now",
            # must be dropped: device-local / transient
            "install_session": 991, "check_at": 1763222400001,
            "check_attempt_at": 1763222400002, "check_error": "boom",
        },
        "review_canvas": {"layout": "{\"pan\":[1,2]}"},
        "bot": {"last_mode": "live"},       # not allowlisted as a whole
        "unknown_file": {"x": "y"},         # not allowlisted
        "broken": None,                     # not a dict
    }


class _FakeExportAccount:
    def __init__(self):
        self.exported = None

    def export_backup(self, out_dir, code):
        self.exported = (out_dir, code)
        with open(os.path.join(out_dir, "core-backup.tar"), "wb") as f:
            f.write(b"fake-tar:" + MARK_DB.encode())


def _stub_bot_runtime(dc, acct):
    import types
    mod = types.ModuleType("bot_runtime")
    mod.RUN_LOCK = "LIVE_LOCK"  # absent from the fixture dir (service stopped)
    mod._rpc = lambda native, files: (_FakeRpc(), dc)
    mod._account = lambda d: acct
    mod.load_state = lambda files: {"provisioned": True}
    return mod


class TestPrefsV2(unittest.TestCase):
    """dcmig/2: settings snapshot member, filtering, tamper refusal and
    backward compatibility with dcmig/1 bundles."""

    def setUp(self):
        self.src = _files_dir()
        self.addCleanup(shutil.rmtree, self.src, True)
        self.backup = _fake_backup(self.src)
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, self.dst, True)

    def _factory(self, dc):
        return lambda native, files: (_FakeRpc(), dc)

    def test_v2_roundtrip_prefs(self):
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"),
                                      prefs=_prefs_snapshot())
        parsed = M.parse_bundle(bundle)
        self.assertEqual(parsed["manifest"]["format"], M.FORMAT_V2)
        self.assertIsNotNone(parsed["prefs_blob"])
        # The plaintext private key must never appear in the bundle bytes.
        with open(bundle, "rb") as f:
            raw = f.read()
        self.assertNotIn(MARK_PRIV.encode(), raw)
        dc = _FakeDC([])
        M.import_migration("nl", self.dst, bundle, code,
                           _rpc_factory=self._factory(dc))
        out = os.path.join(self.dst, M.PREFS_OUT)
        self.assertTrue(os.path.exists(out))
        with open(out) as f:
            got = json.load(f)
        w = got["instinct_widget"]
        self.assertEqual(w["priv"], MARK_PRIV)
        self.assertEqual(w["feed_at"], 1763222400000)
        self.assertEqual(w["feed_url"], "https://feed.example/x")
        for dropped in ("install_session", "check_at", "check_attempt_at",
                        "check_error"):
            self.assertNotIn(dropped, w)
        self.assertEqual(got["review_canvas"]["layout"], "{\"pan\":[1,2]}")
        self.assertNotIn("bot", got)          # last_mode stays behind
        self.assertNotIn("unknown_file", got)

    def test_v1_bundle_still_imports(self):
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"))
        parsed = M.parse_bundle(bundle)
        self.assertEqual(parsed["manifest"]["format"], M.FORMAT)
        self.assertIsNone(parsed["prefs_blob"])
        dc = _FakeDC([])
        M.import_migration("nl", self.dst, bundle, code,
                           _rpc_factory=self._factory(dc))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.PREFS_OUT)))

    def test_v2_tamper_prefs(self):
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"),
                                      prefs=_prefs_snapshot())
        fixed = os.path.join(self.src, "tampered.dcmig")
        with zipfile.ZipFile(bundle) as zin:
            items = {n: zin.read(n) for n in zin.namelist()}
        p = bytearray(items["prefs.bin"]); p[10] ^= 1
        items["prefs.bin"] = bytes(p)
        with zipfile.ZipFile(fixed, "w", zipfile.ZIP_STORED) as z:
            for n, b in items.items():
                z.writestr(n, b)
        with self.assertRaises(M.MigrationError):
            M.parse_bundle(fixed)

    def test_v2_member_format_mismatch(self):
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"),
                                      prefs=_prefs_snapshot())
        stripped = os.path.join(self.src, "stripped.dcmig")
        with zipfile.ZipFile(bundle) as zin:
            items = {n: zin.read(n) for n in zin.namelist()
                     if n != "prefs.bin"}
        with zipfile.ZipFile(stripped, "w", zipfile.ZIP_STORED) as z:
            for n, b in items.items():
                z.writestr(n, b)
        with self.assertRaises(M.MigrationError):
            M.parse_bundle(stripped)

    def test_v2_wrong_code_stages_nothing(self):
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"),
                                      prefs=_prefs_snapshot())
        dc = _FakeDC([])
        with self.assertRaises(M.MigrationError):
            M.import_migration("nl", self.dst, bundle,
                               M.generate_transfer_code(),
                               _rpc_factory=self._factory(dc))
        self.assertEqual(os.listdir(self.dst), [])

    def test_export_consumes_prefs_snapshot(self):
        """export_migration reads EXPORT_PREFS.json, wraps it into the
        bundle and removes the plaintext snapshot from disk."""
        with open(os.path.join(self.src, M.PREFS_IN), "w") as f:
            json.dump(_prefs_snapshot(), f)
        dc, acct = _FakeDC([]), _FakeExportAccount()
        import sys as _sys
        _sys.modules["bot_runtime"] = _stub_bot_runtime(dc, acct)
        try:
            out_dir = os.path.join(self.src, "export-out")
            bundle = M.export_migration("nl", self.src, out_dir)
        finally:
            del _sys.modules["bot_runtime"]
        self.assertFalse(os.path.exists(os.path.join(self.src, M.PREFS_IN)))
        parsed = M.parse_bundle(bundle)
        self.assertEqual(parsed["manifest"]["format"], M.FORMAT_V2)
        with open(os.path.join(self.src, M.RESULT_FILE)) as f:
            res = json.load(f)
        code = res["code"]
        dc2 = _FakeDC([])
        M.import_migration("nl", self.dst, bundle, code,
                           _rpc_factory=self._factory(dc2))
        with open(os.path.join(self.dst, M.PREFS_OUT)) as f:
            got = json.load(f)
        self.assertEqual(got["instinct_widget"]["priv"], MARK_PRIV)

    def test_bundle_carries_no_package_names(self):
        """The bundle must be applicationId-agnostic: it crosses from the
        old package to the new one."""
        bundle, code = M.build_bundle(self.backup, self.src,
                                      os.path.join(self.src, "out.dcmig"),
                                      prefs=_prefs_snapshot())
        with open(bundle, "rb") as f:
            raw = f.read()
        for needle in (b"com.instinct", b"com.vanipy", b"itdapk", b"dcbot"):
            self.assertNotIn(needle, raw)

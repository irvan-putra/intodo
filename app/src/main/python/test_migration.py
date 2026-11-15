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
from unittest import mock
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import migration as M

MARK_STATE = "STATE-MARKER-7f3c2e1a"
MARK_DB = "PRIVATE-DB-MARKER-9b04d2"


def _write(d, name, text):
    with open(os.path.join(d, name), "w") as f:
        f.write(text)


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


class _BadRemoveAccount(_FakeAccount):
    """An account whose remove() fails (core / IO error) until healed."""
    fail_remove = True

    def remove(self):
        if self.fail_remove:
            raise RuntimeError("core remove failed")
        super().remove()


class _BadRemoveDC(_FakeDC):
    def add_account(self):
        a = _BadRemoveAccount()
        self._accounts.append(a)
        return a


class TestRemovalFailureProtocol(unittest.TestCase):
    """An account-removal failure must fail closed: keep the journal and
    never let anyone claim a rollback that did not happen."""

    def _prep(self, cls=_FakeDC):
        files = _files_dir()
        dc = cls([])
        bundle, code = self._bundle(files)
        addr = M.import_prepare("/native", files, bundle, code,
                                _rpc_factory=lambda *a: (_FakeRpc(), dc),
                                _allow_running=True)
        return files, dc, addr

    def _bundle(self, files):
        src = _files_dir()
        _write(src, "bot_state.json",
               json.dumps({"bot_addr": "bot@example.test", "provisioned": True}))
        _write(src, "accounts.toml", "[accounts]\n")
        return M.build_bundle(_fake_backup(src), src,
                              os.path.join(src, "out.dcmig"),
                              prefs=_prefs_snapshot())

    def test_abort_failure_keeps_journal_then_retry_succeeds(self):
        files, dc, _ = self._prep(cls=_BadRemoveDC)
        with self.assertRaises(M.MigrationError):
            M.import_abort("/native", files,
                           _rpc_factory=lambda *a: (_FakeRpc(), dc))
        # Fail closed: journal and account survive for a retry.
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertTrue(dc._accounts[0].is_configured())
        # Retry with a working core finishes the rollback.
        dc._accounts[0].fail_remove = False
        self.assertTrue(M.import_abort("/native", files,
                        _rpc_factory=lambda *a: (_FakeRpc(), dc)))
        self.assertFalse(dc._accounts[0].is_configured())
        self.assertFalse(os.path.exists(os.path.join(files, M.PREFS_PENDING)))

    def test_recovery_cleans_secret_temp_files(self):
        # Kills before atomic renames leave pre-rename temps holding the
        # feed key / decrypted settings; recovery must remove them.
        files, dc, _ = self._prep()
        for name in (M.PREFS_OUT + ".tmp", M.PREFS_PRIOR + ".tmp",
                     M.PREFS_PENDING + ".tmp"):
            _write(files, name, "secret")
        out = M.recover_import("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertEqual(out, "aborted")
        for name in (M.PREFS_OUT + ".tmp", M.PREFS_PRIOR + ".tmp",
                     M.PREFS_PENDING + ".tmp"):
            self.assertFalse(os.path.exists(os.path.join(files, name)))

    def test_recover_export_cleans_orphan_secret_temps(self):
        files = tempfile.mkdtemp(prefix="mig-exp-")
        os.makedirs(os.path.join(files, M.EXPORT_OUT))
        for name in (M.PREFS_IN, M.PREFS_IN + ".tmp", M.RESULT_FILE + ".tmp"):
            _write(files, name, "secret")
        self.assertEqual(M.recover_export(files), "cleaned")
        for name in (M.PREFS_IN, M.PREFS_IN + ".tmp", M.RESULT_FILE + ".tmp"):
            self.assertFalse(os.path.exists(os.path.join(files, name)))
        self.assertFalse(os.path.exists(os.path.join(files, M.EXPORT_OUT)))

    def test_recover_export_keeps_completed_result(self):
        files = tempfile.mkdtemp(prefix="mig-exp-")
        _write(files, M.RESULT_FILE, json.dumps({"bundle": "b", "code": "c"}))
        self.assertEqual(M.recover_export(files), "none")
        self.assertTrue(os.path.exists(os.path.join(files, M.RESULT_FILE)))

    def test_recovery_rollback_failure_keeps_journal_fail_closed(self):
        files, dc, _ = self._prep(cls=_BadRemoveDC)
        with self.assertRaises(M.MigrationError):
            M.recover_import("/native", files,
                             _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        # Retry after the core is healthy again.
        dc._accounts[0].fail_remove = False
        out = M.recover_import("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertEqual(out, "aborted")
        self.assertFalse(dc._accounts[0].is_configured())

    def test_prepare_failure_with_failed_removal_keeps_journal(self):
        # The post-import identity check fails AND the cleanup remove fails:
        # the journal must survive as the only proof the stranded account
        # is import-owned, so recovery can finish the removal.
        class _IntruderAccount(_BadRemoveAccount):
            def import_backup(self, path, code):
                self.imported = (path, code)
                self._configured = True
                self._addr = "intruder@example.test"

        class _IntruderDC(_FakeDC):
            def add_account(self):
                a = _IntruderAccount()
                self._accounts.append(a)
                return a

        files = _files_dir()
        dc = _IntruderDC([])
        bundle, code = self._bundle(files)
        with self.assertRaises(M.MigrationError):
            M.import_prepare("/native", files, bundle, code,
                             _rpc_factory=lambda *a: (_FakeRpc(), dc),
                             _allow_running=True)
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertTrue(dc._accounts[0].is_configured())
        dc._accounts[0].fail_remove = False
        out = M.recover_import("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertEqual(out, "aborted")
        self.assertFalse(dc._accounts[0].is_configured())


    def test_commit_cleanup_unlink_failure_keeps_decision_proof(self):
        # import_commit succeeds, but deleting the prior snapshot fails:
        # the COMMITTING journal must survive, so recovery rolls FORWARD
        # and retries cleanup - never restoring the orphan prior over the
        # committed import.
        files, dc, addr = self._prep()
        _write(files, M.PREFS_PRIOR, json.dumps({}))   # Kotlin's snapshot
        real_unlink = os.unlink

        def flaky(path, *a, **k):
            if str(path).endswith(M.PREFS_PRIOR):
                raise OSError("unlink denied")
            return real_unlink(path, *a, **k)

        with mock.patch.object(M.os, "unlink", side_effect=flaky):
            self.assertEqual(
                M.import_commit("/native", files,
                                _rpc_factory=lambda *a: (_FakeRpc(), dc)),
                addr)
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PRIOR)))
        out = M.recover_import("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertEqual(out, "rolled_forward")
        self.assertFalse(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertFalse(os.path.exists(os.path.join(files, M.PREFS_PRIOR)))

    def test_abort_cleanup_unlink_failure_keeps_journal_and_converges(self):
        files, dc, _ = self._prep()
        _write(files, M.PREFS_PRIOR, json.dumps({}))
        real_unlink = os.unlink

        def flaky(path, *a, **k):
            if str(path).endswith(M.PREFS_OUT):
                raise OSError("unlink denied")
            return real_unlink(path, *a, **k)

        with mock.patch.object(M.os, "unlink", side_effect=flaky):
            self.assertTrue(
                M.import_abort("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc)))
        # Journal kept as proof; account already removed.
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertFalse(dc._accounts[0].is_configured())
        out = M.recover_import("/native", files,
                               _rpc_factory=lambda *a: (_FakeRpc(), dc))
        self.assertEqual(out, "rollback_prefs")   # prior kept for the caller
        self.assertFalse(os.path.exists(os.path.join(files, M.PREFS_PENDING)))
        self.assertTrue(os.path.exists(os.path.join(files, M.PREFS_PRIOR)))


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
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        out = os.path.join(self.dst, M.PREFS_OUT)
        self.assertTrue(os.path.exists(out))
        M.import_commit("nl", self.dst, _rpc_factory=self._factory(dc))
        self.assertFalse(os.path.exists(out))  # commit consumes the staging
        # Re-stage on a fresh install to verify the pre-commit content the
        # activity would apply.
        dc2 = _FakeDC([])
        shutil.rmtree(self.dst)
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc2))
        with open(os.path.join(self.dst, M.PREFS_OUT)) as f:
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
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc2))
        with open(os.path.join(self.dst, M.PREFS_OUT)) as f:
            got = json.load(f)
        self.assertEqual(got["instinct_widget"]["priv"], MARK_PRIV)
        M.import_commit("nl", self.dst, _rpc_factory=self._factory(dc2))

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


class TestAtomicityRegressions(unittest.TestCase):
    """End-to-end rollback: account + state + settings land together or
    not at all, and export never leaves the plaintext settings snapshot."""

    def setUp(self):
        self.src = _files_dir()
        self.addCleanup(shutil.rmtree, self.src, True)
        self.backup = _fake_backup(self.src)
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, self.dst, True)

    def _factory(self, dc):
        return lambda native, files: (_FakeRpc(), dc)

    def _v2_bundle(self):
        return M.build_bundle(self.backup, self.src,
                              os.path.join(self.src, "out.dcmig"),
                              prefs=_prefs_snapshot())

    def test_state_commit_failure_defers_then_recovery_finishes(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        original = M.commit_state
        M.commit_state = lambda *a, **k: (_ for _ in ()).throw(
            M.MigrationError("injected commit failure"))
        try:
            with self.assertRaises(M.MigrationError) as cm:
                M.import_migration("nl", self.dst, bundle, code,
                                   _rpc_factory=self._factory(dc))
        finally:
            M.commit_state = original
        self.assertTrue(str(cm.exception).startswith("COMMIT_INTERRUPTED:"))
        # post-decision: the account is kept, the journal drives recovery
        self.assertEqual(len([a for a in dc.get_all_accounts()
                              if a.is_configured()]), 1)
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "rolled_forward")
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertIsNone(M._read_pending(self.dst))

    def test_prefs_stage_failure_removes_account(self):
        bundle, code = self._v2_bundle()
        # A directory where IMPORT_PREFS.json must be written: the atomic
        # replace fails, simulating a staging write failure.
        os.makedirs(os.path.join(self.dst, M.PREFS_OUT))
        dc = _FakeDC([])
        with self.assertRaises(Exception):
            M.import_migration("nl", self.dst, bundle, code,
                               _rpc_factory=self._factory(dc))
        self.assertEqual([a for a in dc.get_all_accounts()
                          if a.is_configured()], [])
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.STAGING_DIR)))

    def test_export_failure_cleans_prefs_snapshot(self):
        with open(os.path.join(self.src, M.PREFS_IN), "w") as f:
            json.dump(_prefs_snapshot(), f)

        class _BoomAccount:
            def export_backup(self, out_dir, code):
                raise RuntimeError("core export exploded")

        dc = _FakeDC([])
        import sys as _sys
        mod = _stub_bot_runtime(dc, _BoomAccount())
        _sys.modules["bot_runtime"] = mod
        try:
            with self.assertRaises(Exception):
                M.export_migration("nl", self.src,
                                   os.path.join(self.src, "export-out"))
        finally:
            del _sys.modules["bot_runtime"]
        self.assertFalse(os.path.exists(os.path.join(self.src, M.PREFS_IN)))
        with open(os.path.join(self.src, M.RESULT_FILE)) as f:
            res = json.load(f)
        self.assertIn("error", res)

    def test_export_build_failure_removes_plaintext_backup(self):
        """A bundle-build failure after the core wrote its backup must not
        leave the plaintext backup tar (or a partial bundle) on disk."""
        class _TarAccount:
            def export_backup(self, out_dir, code):
                with open(os.path.join(out_dir, "backup.tar"), "wb") as f:
                    f.write(b"PLAINTEXT-BACKUP")

        with open(os.path.join(self.src, M.PREFS_IN), "w") as f:
            json.dump(_prefs_snapshot(), f)
        dc = _FakeDC([])
        import sys as _sys
        _sys.modules["bot_runtime"] = _stub_bot_runtime(dc, _TarAccount())
        original = M.build_bundle
        M.build_bundle = lambda *a, **k: (_ for _ in ()).throw(
            RuntimeError("bundle build exploded"))
        out_dir = os.path.join(self.src, "export-out")
        try:
            with self.assertRaises(Exception):
                M.export_migration("nl", self.src, out_dir)
        finally:
            M.build_bundle = original
            del _sys.modules["bot_runtime"]
        self.assertFalse(os.path.exists(out_dir))
        self.assertFalse(os.path.exists(os.path.join(self.src, M.PREFS_IN)))
        with open(os.path.join(self.src, M.RESULT_FILE)) as f:
            self.assertIn("error", json.load(f))

    def test_export_malformed_prefs_snapshot_clears_everything(self):
        """Malformed EXPORT_PREFS.json: the unreadable plaintext snapshot
        and the recreated output dir both go away on the failure path."""
        with open(os.path.join(self.src, M.PREFS_IN), "w") as f:
            f.write("{ not json")
        dc = _FakeDC([])
        import sys as _sys
        _sys.modules["bot_runtime"] = _stub_bot_runtime(dc, _FakeAccount())
        out_dir = os.path.join(self.src, "export-out")
        try:
            with self.assertRaises(M.MigrationError):
                M.export_migration("nl", self.src, out_dir)
        finally:
            del _sys.modules["bot_runtime"]
        self.assertFalse(os.path.exists(os.path.join(self.src, M.PREFS_IN)))
        self.assertFalse(os.path.exists(out_dir))

    def test_success_keeps_all_three_together(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        addr = M.import_prepare("nl", self.dst, bundle, code,
                                _rpc_factory=self._factory(dc))
        self.assertEqual(addr, "bot@example.test")
        # After prepare the settings are staged and nothing is committed.
        self.assertTrue(os.path.exists(os.path.join(self.dst, M.PREFS_OUT)))
        self.assertFalse(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        M.import_commit("nl", self.dst, _rpc_factory=self._factory(dc))
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertEqual(len([a for a in dc.get_all_accounts()
                              if a.is_configured()]), 1)


class TestTwoPhaseImport(unittest.TestCase):
    """prepare/commit/abort: the two-phase path BotImport drives so a
    settings failure aborts the whole import instead of stranding it."""

    def setUp(self):
        self.src = _files_dir()
        self.addCleanup(shutil.rmtree, self.src, True)
        self.backup = _fake_backup(self.src)
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, self.dst, True)

    def _factory(self, dc):
        return lambda native, files: (_FakeRpc(), dc)

    def _v2_bundle(self):
        return M.build_bundle(self.backup, self.src,
                              os.path.join(self.src, "out.dcmig"),
                              prefs=_prefs_snapshot())

    def test_prepare_refuses_while_the_bot_runs(self):
        # The live bot holds this data directory: import_prepare must
        # refuse BEFORE any bundle/account/RPC work and leave no journal.
        # The marker holds the runtime pid; liveness is pid-grounded.
        files = _files_dir()
        running = os.path.join(files, "RUNNING")
        with open(running, "w") as f:
            f.write(str(os.getpid()))
        try:
            # The marker check fires before any bundle/RPC work, so a
            # dummy bundle path suffices.
            with self.assertRaises(M.MigrationError):
                M.import_prepare("/native", files,
                                 os.path.join(files, "nope.dcmig"), "x" * 44,
                                 _rpc_factory=lambda *a: (_FakeRpc(),
                                                          _FakeDC([])))
            self.assertFalse(os.path.exists(
                os.path.join(files, M.PREFS_PENDING)))
        finally:
            if os.path.exists(running):
                os.remove(running)

    def test_prepare_clears_a_stale_running_marker(self):
        # A marker whose pid is dead (killed process) is STALE: cleared on
        # the pid's grounding, never blindly honored - the import proceeds
        # (here it fails later on the dummy bundle, which is fine).
        files = _files_dir()
        running = os.path.join(files, "RUNNING")
        with open(running, "w") as f:
            f.write("4194304")   # beyond the pid space: no live process
        try:
            M.import_prepare("/native", files,
                             os.path.join(files, "nope.dcmig"), "x" * 44,
                             _rpc_factory=lambda *a: (_FakeRpc(),
                                                      _FakeDC([])))
        except Exception:
            pass
        self.assertFalse(os.path.exists(running))

    def test_prepare_fails_closed_on_unknown_probe_error(self):
        # EPERM from the pid probe means the process EXISTS but is owned
        # by another uid: NOT stale. Fail closed - refuse the import,
        # keep the marker. Only ESRCH clears.
        files = _files_dir()
        running = os.path.join(files, "RUNNING")
        with open(running, "w") as f:
            f.write("4194304")
        real_kill = os.kill
        def eperm(pid, sig):
            raise PermissionError()
        os.kill = eperm
        try:
            with self.assertRaises(M.MigrationError):
                M.import_prepare("/native", files,
                                 os.path.join(files, "nope.dcmig"), "x" * 44,
                                 _rpc_factory=lambda *a: (_FakeRpc(),
                                                          _FakeDC([])))
            self.assertTrue(os.path.exists(running))
        finally:
            os.kill = real_kill
            if os.path.exists(running):
                os.remove(running)

    def test_prepare_commit_lands_everything(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        addr = M.import_prepare("nl", self.dst, bundle, code,
                                _rpc_factory=self._factory(dc))
        self.assertEqual(addr, "bot@example.test")
        # Pending: journal and staged settings exist, state NOT committed.
        self.assertEqual(M._read_pending(self.dst)["phase"], "prepared")
        self.assertTrue(os.path.exists(os.path.join(self.dst, M.PREFS_OUT)))
        self.assertFalse(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        M.import_commit("nl", self.dst, _rpc_factory=self._factory(dc))
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertIsNone(M._read_pending(self.dst))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.PREFS_OUT)))

    def test_abort_restores_untouched_install(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        self.assertTrue(M.import_abort("nl", self.dst,
                                       _rpc_factory=self._factory(dc)))
        self.assertEqual([a for a in dc.get_all_accounts()
                          if a.is_configured()], [])
        self.assertTrue(all(a.removed for a in dc.get_all_accounts()))
        self.assertEqual(os.listdir(self.dst), [])
        # No marker left: a second abort is a no-op.
        self.assertFalse(M.import_abort("nl", self.dst,
                                        _rpc_factory=self._factory(dc)))

    def test_commit_rpc_failure_is_pre_decision_and_abortable(self):
        """An rpc open failure happens BEFORE the decision point: the
        journal stays "prepared" and the normal abort path rolls the
        import back cleanly."""
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))

        def failing(native, files):
            raise RuntimeError("core rpc unavailable")

        with self.assertRaises(RuntimeError):
            M.import_commit("nl", self.dst, _rpc_factory=failing)
        self.assertEqual(M._read_pending(self.dst)["phase"], "prepared")
        M.import_abort("nl", self.dst, _rpc_factory=self._factory(dc))
        self.assertEqual([a for a in dc.get_all_accounts()
                          if a.is_configured()], [])
        self.assertEqual(os.listdir(self.dst), [])

    def test_commit_failure_after_decision_point_defers_to_roll_forward(self):
        """Post-decision failures are NEVER rolled back: account, journal
        (committing), staging, staged settings and prior snapshot all
        survive, the error carries the COMMIT_INTERRUPTED: prefix the
        caller checks, and recovery finishes the import. This is the
        kill-after-account-removal window, closed by never removing the
        account post-decision."""
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        with open(os.path.join(self.dst, M.PREFS_PRIOR), "w") as f:
            json.dump({"instinct_widget": {"priv": "OLD"}}, f)
        original = M.commit_state
        M.commit_state = lambda *a, **k: (_ for _ in ()).throw(
            M.MigrationError("injected commit failure"))
        try:
            with self.assertRaises(M.MigrationError) as cm:
                M.import_commit("nl", self.dst,
                                _rpc_factory=self._factory(dc))
        finally:
            M.commit_state = original
        self.assertTrue(str(cm.exception).startswith("COMMIT_INTERRUPTED:"))
        self.assertEqual(M._read_pending(self.dst)["phase"], "committing")
        self.assertEqual(len([a for a in dc.get_all_accounts()
                              if a.is_configured()]), 1)
        self.assertTrue(os.path.exists(os.path.join(self.dst, M.STAGING_DIR)))
        self.assertTrue(os.path.exists(os.path.join(self.dst, M.PREFS_PRIOR)))
        # recovery finishes the import, idempotently
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "rolled_forward")
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertIsNone(M._read_pending(self.dst))

    def test_commit_without_prepare_refused(self):
        with self.assertRaises(M.MigrationError):
            M.import_commit("nl", self.dst,
                            _rpc_factory=self._factory(_FakeDC([])))

    def test_prepare_self_heals_stale_interrupted_import(self):
        """A crash between prepare and commit leaves a configured account
        plus the pending marker; the next prepare rolls it back first."""
        bundle, code = self._v2_bundle()
        stale = _FakeAccount(configured=True)
        dc = _FakeDC([stale])
        with open(os.path.join(self.dst, M.PREFS_PENDING), "w") as f:
            json.dump({"addr": None}, f)
        addr = M.import_prepare("nl", self.dst, bundle, code,
                                _rpc_factory=self._factory(dc))
        self.assertTrue(stale.removed)
        self.assertEqual(addr, "bot@example.test")
        M.import_abort("nl", self.dst, _rpc_factory=self._factory(dc))
        self.assertEqual(os.listdir(self.dst), [])

    def test_prepare_refuses_configured_account_without_marker(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([_FakeAccount(configured=True)])
        with self.assertRaises(M.MigrationError):
            M.import_prepare("nl", self.dst, bundle, code,
                             _rpc_factory=self._factory(dc))
        self.assertFalse(os.path.exists(
            os.path.join(self.dst, M.STAGING_DIR)))


class TestCrashRecovery(unittest.TestCase):
    """Process-death windows: each test kills at a mutation boundary (the
    in-process calls simply stop) and restarts with fresh calls, exactly
    like a relaunched app running recover_import."""

    def setUp(self):
        self.src = _files_dir()
        self.addCleanup(shutil.rmtree, self.src, True)
        self.backup = _fake_backup(self.src)
        self.dst = tempfile.mkdtemp(prefix="mig-dst-")
        self.addCleanup(shutil.rmtree, self.dst, True)

    def _factory(self, dc):
        return lambda native, files: (_FakeRpc(), dc)

    def _v2_bundle(self):
        return M.build_bundle(self.backup, self.src,
                              os.path.join(self.src, "out.dcmig"),
                              prefs=_prefs_snapshot())

    def test_kill_before_journal_cleans_files_only(self):
        # staging + backup tmp exist, journal does not: no account
        # mutation ever happened (the journal PRECEDES it).
        dc = _FakeDC([])
        os.makedirs(os.path.join(self.dst, M.STAGING_DIR))
        open(os.path.join(self.dst, M.BACKUP_TMP), "w").close()
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "none")
        self.assertEqual(os.listdir(self.dst), [])

    def test_kill_after_account_import_rolls_back_with_ownership_proof(self):
        # journal "preparing" + configured account: provably import-owned.
        dc = _FakeDC([_FakeAccount(configured=True)])
        M._write_pending(self.dst, "preparing", None)
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "aborted")
        self.assertEqual([a for a in dc.get_all_accounts()
                          if a.is_configured()], [])
        self.assertEqual(os.listdir(self.dst), [])

    def test_no_journal_never_removes_a_preexisting_account(self):
        # configured account + leftovers but NO journal: ownership is not
        # provable -> the account stays, only files are cleaned.
        acct = _FakeAccount(configured=True)
        dc = _FakeDC([acct])
        os.makedirs(os.path.join(self.dst, M.STAGING_DIR))
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "none")
        self.assertTrue(acct.is_configured())
        self.assertFalse(acct.removed)
        self.assertEqual(os.listdir(self.dst), [])

    def test_kill_mid_settings_apply_restores_via_durable_snapshot(self):
        # journal "prepared" + durable prior snapshot + imported account:
        # recovery rolls back the account and KEEPS the prior snapshot for
        # the activity-side SharedPreferences restore.
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        with open(os.path.join(self.dst, M.PREFS_PRIOR), "w") as f:
            json.dump({"instinct_widget": {"priv": "OLD"}}, f)
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "rollback_prefs")
        self.assertEqual([a for a in dc.get_all_accounts()
                          if a.is_configured()], [])
        with open(os.path.join(self.dst, M.PREFS_PRIOR)) as f:
            self.assertEqual(json.load(f)["instinct_widget"]["priv"], "OLD")
        self.assertIsNone(M._read_pending(self.dst))
        self.assertFalse(os.path.exists(os.path.join(self.dst, M.STAGING_DIR)))

    def test_kill_after_decision_point_rolls_forward(self):
        # journal "committing" before any commit_state: recovery finishes
        # the commit - account kept, state landed, journal cleaned.
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        M._write_pending(self.dst, "committing", "bot@example.test")
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "rolled_forward")
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertEqual(len([a for a in dc.get_all_accounts()
                              if a.is_configured()]), 1)
        self.assertEqual(sorted(os.listdir(self.dst)),
                         ["bot_state.json", "ops-5.db", "ops-9.db"])

    def test_kill_after_commit_landed_is_idempotent_roll_forward(self):
        # kill AFTER commit_state finished but BEFORE the journal was
        # deleted: the exact window the crash injection found.
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        M._write_pending(self.dst, "committing", "bot@example.test")
        M.commit_state(os.path.join(self.dst, M.STAGING_DIR), self.dst)
        self.assertEqual(M.recover_import("nl", self.dst,
                                          _rpc_factory=self._factory(dc)),
                         "rolled_forward")
        self.assertEqual(len([a for a in dc.get_all_accounts()
                              if a.is_configured()]), 1)
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertIsNone(M._read_pending(self.dst))

    def test_commit_state_resumes_a_killed_commit(self):
        # half the files landed, a .pre-import backup lingers, the rest
        # still staged: re-running commit_state finishes the job and drops
        # the stray backup (roll forward: the landed file wins).
        staging = os.path.join(self.dst, M.STAGING_DIR)
        os.makedirs(staging)
        with open(os.path.join(staging, "bot_state.json"), "w") as f:
            f.write('{"new": true}')
        with open(os.path.join(staging, "ops-9.db"), "w") as f:
            f.write("new-db")
        with open(os.path.join(self.dst, "bot_state.json"), "w") as f:
            f.write('{"old": true}')
        # simulate the killed commit: old moved aside, new landed for
        # bot_state.json, ops-9.db never reached.
        os.replace(os.path.join(self.dst, "bot_state.json"),
                   os.path.join(self.dst, "bot_state.json.pre-import"))
        os.replace(os.path.join(staging, "bot_state.json"),
                   os.path.join(self.dst, "bot_state.json"))
        M.commit_state(staging, self.dst)
        with open(os.path.join(self.dst, "bot_state.json")) as f:
            self.assertEqual(json.load(f), {"new": True})
        with open(os.path.join(self.dst, "ops-9.db")) as f:
            self.assertEqual(f.read(), "new-db")
        self.assertFalse(os.path.exists(
            os.path.join(self.dst, "bot_state.json.pre-import")))
        self.assertFalse(os.path.exists(staging))

    def test_orphan_prior_snapshot_reports_restore(self):
        with open(os.path.join(self.dst, M.PREFS_PRIOR), "w") as f:
            json.dump({"instinct_widget": {"priv": "OLD"}}, f)
        self.assertEqual(
            M.recover_import("nl", self.dst,
                             _rpc_factory=self._factory(_FakeDC([]))),
            "restore_orphan_prior")

    def test_prepare_refuses_when_recovery_must_run_first(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        with open(os.path.join(self.dst, M.PREFS_PRIOR), "w") as f:
            json.dump({}, f)
        with self.assertRaises(M.MigrationError):
            M.import_prepare("nl", self.dst, bundle, code,
                             _rpc_factory=self._factory(dc))

    def test_prepare_rolls_forward_committing_journal_then_refuses(self):
        bundle, code = self._v2_bundle()
        dc = _FakeDC([])
        M.import_prepare("nl", self.dst, bundle, code,
                         _rpc_factory=self._factory(dc))
        M._write_pending(self.dst, "committing", "bot@example.test")
        with self.assertRaises(M.MigrationError):
            M.import_prepare("nl", self.dst, bundle, code,
                             _rpc_factory=self._factory(dc))
        # the interrupted commit was finished first
        self.assertTrue(os.path.exists(
            os.path.join(self.dst, "bot_state.json")))
        self.assertIsNone(M._read_pending(self.dst))


class TestExportRecovery(unittest.TestCase):
    """Interrupted-export cleanup: an export that died before writing
    EXPORT_RESULT leaves the working dir (with the plaintext backup tar)
    and/or the plaintext settings snapshot; a completed export is kept."""

    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="mig-exp-")
        self.addCleanup(shutil.rmtree, self.d, True)

    def test_interrupted_export_removed(self):
        out = os.path.join(self.d, M.EXPORT_OUT)
        os.makedirs(out)
        with open(os.path.join(out, "backup.tar"), "wb") as f:
            f.write(b"PLAINTEXT")
        with open(os.path.join(self.d, M.PREFS_IN), "w") as f:
            f.write("{}")
        self.assertEqual(M.recover_export(self.d), "cleaned")
        self.assertFalse(os.path.exists(out))
        self.assertFalse(os.path.exists(os.path.join(self.d, M.PREFS_IN)))

    def test_completed_export_kept(self):
        out = os.path.join(self.d, M.EXPORT_OUT)
        os.makedirs(out)
        with open(os.path.join(out, "dcbot-migration.dcmig"), "w") as f:
            f.write("bundle")
        with open(os.path.join(self.d, M.RESULT_FILE), "w") as f:
            f.write("{}")
        self.assertEqual(M.recover_export(self.d), "none")
        self.assertTrue(os.path.exists(out))

    def test_nothing_to_clean(self):
        self.assertEqual(M.recover_export(self.d), "none")

"""Migration bundle: secure phone-local handoff of the bot between Android
packages (scratch test app -> itdapk). Reversible by design: export never
deletes anything; import stages + verifies before committing.

Bundle = one .dcmig zip with exactly three members (dcmig/1), or four
when the activity supplied a settings snapshot (dcmig/2):
  manifest.json   non-secret metadata + sha256 of both ENCRYPTED payloads
                  (never an address, never account content)
  account.backup  core export_backup output (a tar holding the SQLCipher-
                  encrypted account database + blob files), itself wrapped
                  in Fernet so the bundle carries no plaintext payload
  state.bin       Fernet-encrypted tar.gz of the bot's own state
                  (bot_state.json + ops-*.db per-chat stores) - the core
                  backup does NOT carry these (owner binding, chat list,
                  op history live only here)
  prefs.bin       (dcmig/2 only) Fernet-encrypted JSON snapshot of the app
                  settings in PREFS_ALLOWLIST: the widget feed keypair
                  (priv/pub), the cached feed, the on-device feed URL and
                  the review-canvas layout. Without it the imported app
                  could not decrypt the feed it already paired for.
                  Import re-stages it as IMPORT_PREFS.json; the importing
                  activity applies it through SharedPreferences and deletes
                  the file. dcmig/1 bundles (no prefs.bin) still import.

One transfer code protects everything. The code is 32 random bytes,
base64url (44 chars), generated on-device at export time, shown once in
the app UI and handed over by the user (same-device clipboard or typing) -
never logged, never sent through a chat, never inside the bundle.

Key separation (one code, four distinct uses):
  - core SQLCipher passphrase = the code itself (required by import_backup)
  - state cipher key  = SHA-256(code | STATE_DOMAIN), Fernet
  - backup wrap key   = SHA-256(code | WRAP_DOMAIN), Fernet
  - prefs cipher key  = SHA-256(code | PREFS_DOMAIN), Fernet (dcmig/2)
So the ciphers never share raw key material.

Grounding (chatmail core v2.69.0 src/imex.rs):
  - export_backup(context, dir, passphrase): destination is a DIRECTORY;
    core picks the filename; empty passphrase would export UNENCRYPTED
    (never done here); non-empty encrypts the exported database
    (export_database: "Exports the database to *dest*, encrypted using
    *passphrase*", SQLCipher ATTACH ... KEY).
  - import_backup(path, passphrase) restores into a FRESH account.
  - Blobs inside the backup tar are not individually encrypted; that is
    why the whole tar is Fernet-wrapped here.
"""

import base64
import hashlib
import io
import json
import os
import re
import secrets
import shutil
import tarfile
import time
import zipfile

FORMAT = "dcmig/1"
FORMAT_V2 = "dcmig/2"            # adds prefs.bin (app settings snapshot)
STATE_DOMAIN = b"|dcbot-migration-state-v1"
WRAP_DOMAIN = b"|dcbot-migration-wrap-v1"
PREFS_DOMAIN = b"|dcbot-migration-prefs-v1"
RESULT_FILE = "EXPORT_RESULT"     # json {bundle, code} or {error}; UI reads+deletes
STAGING_DIR = ".import-staging"
BACKUP_TMP = ".import-backup.tmp"
PREFS_IN = "EXPORT_PREFS.json"   # activity-written snapshot input, read+deleted here
PREFS_OUT = "IMPORT_PREFS.json"  # staged output; the importing activity applies+deletes
PREFS_PENDING = "IMPORT_PENDING.json"  # prepare->commit/abort marker {"addr": ...}

# App settings that must survive the move to the new applicationId. Keys not
# listed here are deliberately left behind: install_session is a device-local
# package-installer handle, check_*/check_error are transient sync diagnostics,
# last_mode must stay empty so the service cannot autostart mid-import, and
# pending_publish_* only correlates an in-flight publish (the export UI
# refuses to run while one is).
PREFS_ALLOWLIST = {
    "instinct_widget": ("priv", "pub", "feed", "feed_at", "feed_gen",
                        "feed_url", "status"),
    "review_canvas": ("layout",),
}


def filter_prefs(snapshot):
    """Allowlist + type-check a {prefs_file: {key: value}} snapshot. Only str
    and int values ride the bundle; anything else is dropped, never guessed."""
    if not isinstance(snapshot, dict):
        raise MigrationError("prefs snapshot is not an object")
    out = {}
    for name, keyvals in snapshot.items():
        allowed = PREFS_ALLOWLIST.get(name)
        if allowed is None or not isinstance(keyvals, dict):
            continue
        kept = {}
        for k in allowed:
            if k in keyvals and isinstance(keyvals[k], (str, int)) \
                    and not isinstance(keyvals[k], bool):
                kept[k] = keyvals[k]
        if kept:
            out[name] = kept
    return out
_STATE_NAMES = ("bot_state.json",)
_STATE_GLOB = re.compile(r"^ops-\d+\.db$")
_SAFE_MEMBER = re.compile(r"^[A-Za-z0-9._-]+$")


class MigrationError(Exception):
    pass


def generate_transfer_code():
    """32 random bytes, urlsafe-base64 (44 chars); valid Fernet key shape."""
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")


def _fernet(code, domain):
    from cryptography.fernet import Fernet
    try:
        raw = hashlib.sha256(code.encode("ascii") + domain).digest()
        return Fernet(base64.urlsafe_b64encode(raw))
    except Exception:
        raise MigrationError("malformed transfer code")


def _encrypt(code, domain, payload):
    return _fernet(code, domain).encrypt(payload)


def _decrypt(code, domain, blob):
    try:
        return _fernet(code, domain).decrypt(blob)
    except MigrationError:
        raise
    except Exception:
        raise MigrationError("wrong transfer code or corrupt payload")


def _sha256(b):
    return hashlib.sha256(b).hexdigest()


# -- bot state archive -------------------------------------------------------

def state_members(files_dir):
    """Only the bot's own persisted state. Excludes: accounts/ (core backup
    carries it), logs, invite files (bearer QRs), marker files, results."""
    names = [n for n in _STATE_NAMES
             if os.path.isfile(os.path.join(files_dir, n))]
    for n in sorted(os.listdir(files_dir)):
        if _STATE_GLOB.match(n) and os.path.isfile(os.path.join(files_dir, n)):
            names.append(n)
    return names


def build_state_tar(files_dir):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name in state_members(files_dir):
            tar.add(os.path.join(files_dir, name), arcname=name)
    return buf.getvalue()


def stage_state(tar_bytes, staging_dir):
    """Extract into a staging dir with a strict allowlist on member names
    (flat files only; no paths, no traversal, no links/devices)."""
    if os.path.isdir(staging_dir):
        shutil.rmtree(staging_dir)
    os.makedirs(staging_dir)
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:gz") as tar:
        members = tar.getmembers()
        for m in members:
            if not m.isfile() or not _SAFE_MEMBER.match(m.name):
                raise MigrationError("unsafe member in state archive: %r" % m.name)
        for m in members:
            src = tar.extractfile(m)
            with open(os.path.join(staging_dir, m.name), "wb") as dst:
                shutil.copyfileobj(src, dst)
    st = os.path.join(staging_dir, "bot_state.json")
    if os.path.exists(st):
        try:
            with open(st) as f:
                json.load(f)
        except Exception:
            raise MigrationError("staged bot_state.json does not parse")


def commit_state(staging_dir, files_dir):
    """Move staged files into place. All-or-nothing: existing files are
    first moved aside and restored if any move fails; nothing is deleted
    until every file landed."""
    backups = []
    try:
        for name in sorted(os.listdir(staging_dir)):
            src = os.path.join(staging_dir, name)
            if not os.path.isfile(src):
                continue
            dst = os.path.join(files_dir, name)
            if os.path.exists(dst):
                bak = dst + ".pre-import"
                os.replace(dst, bak)
                backups.append((dst, bak))
            os.replace(src, dst)
    except Exception:
        for dst, bak in reversed(backups):
            try:
                if os.path.isfile(dst):
                    os.unlink(dst)
                os.replace(bak, dst)
            except OSError:
                pass
        raise MigrationError("commit failed; previous files restored")
    for dst, bak in backups:
        try:
            os.unlink(bak)
        except OSError:
            pass
    shutil.rmtree(staging_dir, ignore_errors=True)


# -- bundle ------------------------------------------------------------------

def build_bundle(backup_path, files_dir, out_path, code=None, prefs=None):
    """Wrap core backup + bot state (+ optional prefs snapshot) into one
    .dcmig zip. Returns (path, code). With prefs the bundle is FORMAT_V2;
    without, it stays FORMAT so old parsers keep working."""
    code = code or generate_transfer_code()
    with open(backup_path, "rb") as f:
        backup_raw = f.read()
    wrapped = _encrypt(code, WRAP_DOMAIN, backup_raw)
    state_blob = _encrypt(code, STATE_DOMAIN, build_state_tar(files_dir))
    prefs_blob = None
    if prefs is not None:
        prefs_blob = _encrypt(code, PREFS_DOMAIN,
                              json.dumps(filter_prefs(prefs)).encode("utf-8"))
    manifest = {
        "format": FORMAT_V2 if prefs_blob is not None else FORMAT,
        "created_at": int(time.time()),
        "backup_sha256": _sha256(wrapped),
        "backup_bytes": len(wrapped),
        "state_sha256": _sha256(state_blob),
        "state_bytes": len(state_blob),
    }
    if prefs_blob is not None:
        manifest["prefs_sha256"] = _sha256(prefs_blob)
        manifest["prefs_bytes"] = len(prefs_blob)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=1))
        z.writestr("account.backup", wrapped)
        z.writestr("state.bin", state_blob)
        if prefs_blob is not None:
            z.writestr("prefs.bin", prefs_blob)
    return out_path, code


def parse_bundle(path):
    """Verify structure + hashes BEFORE trusting any byte of the payload.
    Accepts dcmig/1 (3 members) and dcmig/2 (adds prefs.bin); the declared
    format must match the actual member set exactly."""
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if names not in ({"manifest.json", "account.backup", "state.bin"},
                             {"manifest.json", "account.backup", "state.bin",
                              "prefs.bin"}):
                raise MigrationError("not a migration bundle (members %s)"
                                     % sorted(names))
            manifest = json.loads(z.read("manifest.json"))
            backup = z.read("account.backup")
            blob = z.read("state.bin")
            prefs = z.read("prefs.bin") if "prefs.bin" in names else None
    except MigrationError:
        raise
    except Exception as e:
        raise MigrationError("unreadable bundle: %s" % e)
    want = FORMAT_V2 if prefs is not None else FORMAT
    if manifest.get("format") != want:
        raise MigrationError("unsupported bundle format %r" % manifest.get("format"))
    if _sha256(backup) != manifest.get("backup_sha256"):
        raise MigrationError("account.backup payload corrupted (hash mismatch)")
    if _sha256(blob) != manifest.get("state_sha256"):
        raise MigrationError("state.bin payload corrupted (hash mismatch)")
    if prefs is not None and _sha256(prefs) != manifest.get("prefs_sha256"):
        raise MigrationError("prefs.bin payload corrupted (hash mismatch)")
    return {"manifest": manifest, "backup": backup, "state_blob": blob,
            "prefs_blob": prefs}


# -- export (scratch side; service mode "export") ----------------------------

def export_migration(native_lib_dir, files_dir, out_dir):
    """Export the configured account + bot state. Requires the live service
    STOPPED (consistent quiescent DB). Writes RESULT_FILE for the activity;
    the transfer code is written there (app-private) and NEVER logged."""
    # Lazy: bot_runtime imports this module for the export service mode.
    from bot_runtime import RUN_LOCK, _rpc, _account, load_state
    result = os.path.join(files_dir, RESULT_FILE)
    prefs_in = os.path.join(files_dir, PREFS_IN)
    try:
        os.unlink(result)
    except FileNotFoundError:
        pass
    try:
        if os.path.exists(os.path.join(files_dir, RUN_LOCK)):
            raise MigrationError("live service still running - stop it first "
                                 "(activity does this before starting export)")
        st = load_state(files_dir)
        if not st.get("provisioned"):
            raise MigrationError("bot not provisioned - nothing to export")
        if os.path.isdir(out_dir):
            shutil.rmtree(out_dir)
        os.makedirs(out_dir)
        # Optional settings snapshot written by the activity just before it
        # started this export mode. It holds the feed private key in
        # plaintext, so it is consumed into memory AND deleted from disk as
        # one step - there is no later cleanup window on any failure path,
        # including malformed JSON and the preconditions above.
        prefs = None
        if os.path.isfile(prefs_in):
            try:
                with open(prefs_in) as f:
                    prefs = json.load(f)
            except Exception:
                raise MigrationError("unreadable %s - retry the export"
                                     % PREFS_IN)
            try:
                os.unlink(prefs_in)
            except OSError:
                pass
        rpc, dc = _rpc(native_lib_dir, files_dir)
        try:
            acct = _account(dc)
            code = generate_transfer_code()
            # Destination is a DIRECTORY; core picks the backup filename and
            # encrypts the database with this passphrase (SQLCipher).
            acct.export_backup(out_dir, code)
        finally:
            rpc.close()
        produced = [n for n in os.listdir(out_dir)
                    if os.path.isfile(os.path.join(out_dir, n))]
        if not produced:
            raise MigrationError("core produced no backup file")
        backup_path = os.path.join(
            out_dir, max(produced,
                         key=lambda n: os.path.getmtime(os.path.join(out_dir, n))))
        bundle_path = os.path.join(out_dir, "dcbot-migration.dcmig")
        build_bundle(backup_path, files_dir, bundle_path, code, prefs=prefs)
        # The raw backup tar (plaintext blobs) must not linger on disk.
        os.unlink(backup_path)
        with open(result + ".tmp", "w") as f:
            json.dump({"bundle": bundle_path, "code": code}, f)
        os.replace(result + ".tmp", result)
        return bundle_path
    except Exception as e:
        # EVERY failure path - precondition refusal, malformed snapshot,
        # core export or bundle build errors - leaves nothing behind: not
        # the plaintext settings snapshot and not the output dir, whose
        # backup tar (and any partial bundle) is plaintext until wrapped.
        try:
            os.unlink(prefs_in)
        except OSError:
            pass
        shutil.rmtree(out_dir, ignore_errors=True)
        err = {"error": str(e)}
        try:
            with open(result + ".tmp", "w") as f:
                json.dump(err, f)
            os.replace(result + ".tmp", result)
        except OSError:
            pass
        raise


# -- import (itdapk side) ------------------------------------------------------

def _read_pending(files_dir):
    """Pending-import marker -> {"addr": ...} (addr None when the file is
    unreadable); None when no import is pending."""
    p = os.path.join(files_dir, PREFS_PENDING)
    if not os.path.isfile(p):
        return None
    try:
        with open(p) as f:
            d = json.load(f)
        return {"addr": d.get("addr")}
    except Exception:
        return {"addr": None}


def _clear_import_leftovers(files_dir):
    """Drop every import byproduct: staged state, staged settings, the
    pending marker and the decrypted backup temp file."""
    shutil.rmtree(os.path.join(files_dir, STAGING_DIR), ignore_errors=True)
    for name in (PREFS_OUT, PREFS_PENDING, BACKUP_TMP):
        try:
            os.unlink(os.path.join(files_dir, name))
        except OSError:
            pass


def _remove_configured_accounts(dc):
    """Abort-side account cleanup. import_prepare refuses installs that had
    a configured account, so any configured account present at abort time
    belongs to the interrupted import itself."""
    for a in dc.get_all_accounts():
        try:
            if a.is_configured():
                a.remove()
        except Exception:
            pass


def import_prepare(native_lib_dir, files_dir, bundle_path, code,
                   _rpc_factory=None):
    """Phase 1 of the import: verify -> decrypt -> stage -> import account
    on a fresh slot -> verify identity -> stage settings + pending marker.
    NOTHING is committed: import_commit lands the state, import_abort
    restores the untouched install. A configured account with no pending
    marker is a real install and is refused; one WITH a stale marker is
    the half-imported leftover of an interrupted run and is rolled back
    first. Any failure before the marker leaves the install untouched."""
    from bot_runtime import _account_addr, _rpc
    parsed = parse_bundle(bundle_path)
    backup_raw = _decrypt(code, WRAP_DOMAIN, parsed["backup"])
    state_tar = _decrypt(code, STATE_DOMAIN, parsed["state_blob"])
    prefs = None
    if parsed.get("prefs_blob") is not None:
        # Re-filter on this side too: the bundle is verified, but the
        # allowlist is the contract the applying activity relies on.
        prefs = filter_prefs(json.loads(
            _decrypt(code, PREFS_DOMAIN, parsed["prefs_blob"]).decode("utf-8")))
    staging = os.path.join(files_dir, STAGING_DIR)
    open_rpc = _rpc_factory or _rpc
    rpc, dc = open_rpc(native_lib_dir, files_dir)
    acct = None
    tmp = os.path.join(files_dir, BACKUP_TMP)
    try:
        if [a for a in dc.get_all_accounts() if a.is_configured()]:
            if _read_pending(files_dir) is None:
                raise MigrationError(
                    "this install already has a configured bot account - "
                    "refusing to overwrite it")
            # Stale interrupted import: roll its half-imported account back
            # (and drop its leftovers) before preparing the new one.
            _remove_configured_accounts(dc)
            _clear_import_leftovers(files_dir)
        stage_state(state_tar, staging)
        staged_addr = None
        st_path = os.path.join(staging, "bot_state.json")
        if os.path.exists(st_path):
            with open(st_path) as f:
                staged_addr = (json.load(f).get("bot_addr") or "") or None
        acct = dc.add_account()
        with open(tmp, "wb") as f:
            f.write(backup_raw)
        acct.import_backup(tmp, code)
        if not acct.is_configured():
            raise MigrationError("import left the account unconfigured")
        addr = _account_addr(acct)
        if staged_addr and addr and staged_addr != addr:
            raise MigrationError(
                "identity mismatch: state belongs to a different bot address")
        if prefs is not None:
            # Stage the settings snapshot for the activity to apply via
            # SharedPreferences (Python must not write shared_prefs XML
            # under a running app). app-private; the activity consumes it
            # before import_commit, abort removes it on failure.
            out = os.path.join(files_dir, PREFS_OUT)
            with open(out + ".tmp", "w") as f:
                json.dump(prefs, f)
            os.chmod(out + ".tmp", 0o600)
            os.replace(out + ".tmp", out)
        # Last: the pending marker. Written AFTER every fallible step so a
        # crash between prepare and commit/abort is detectable and the next
        # prepare self-heals by rolling the half-import back.
        pend = os.path.join(files_dir, PREFS_PENDING)
        with open(pend + ".tmp", "w") as f:
            json.dump({"addr": addr}, f)
        os.replace(pend + ".tmp", pend)
    except Exception:
        if acct is not None:
            try:
                acct.remove()   # no half-imported account left behind
            except Exception:
                pass
        _clear_import_leftovers(files_dir)
        raise
    finally:
        try:
            rpc.close()
        except Exception:
            pass
        try:
            os.unlink(tmp)
        except OSError:
            pass
    return addr


def import_commit(native_lib_dir, files_dir, prefs_applied=False,
                  _rpc_factory=None):
    """Phase 2: land the staged state. The activity calls this only after
    it applied the staged settings (prefs_applied=True also removes the
    consumed staging file). A commit failure rolls the WHOLE import back -
    imported account, staged state, settings snapshot, marker - so
    account/state/settings either all land or the install is untouched."""
    marker = _read_pending(files_dir)
    if marker is None:
        raise MigrationError("no prepared import to commit")
    from bot_runtime import _rpc, save_state
    open_rpc = _rpc_factory or _rpc
    rpc, dc = open_rpc(native_lib_dir, files_dir)
    try:
        commit_state(os.path.join(files_dir, STAGING_DIR), files_dir)
        if not os.path.exists(os.path.join(files_dir, "bot_state.json")):
            save_state(files_dir, provisioned=True,
                       bot_addr=marker.get("addr"))
    except Exception:
        try:
            _remove_configured_accounts(dc)
        finally:
            _clear_import_leftovers(files_dir)
        raise
    finally:
        try:
            rpc.close()
        except Exception:
            pass
    try:
        os.unlink(os.path.join(files_dir, PREFS_PENDING))
    except OSError:
        pass
    if prefs_applied:
        try:
            os.unlink(os.path.join(files_dir, PREFS_OUT))
        except OSError:
            pass
    return marker.get("addr")


def import_abort(native_lib_dir, files_dir, _rpc_factory=None):
    """Undo a prepared-but-uncommitted import: remove the imported account
    and drop staged state, settings snapshot and marker. Restores the
    untouched install import_prepare started from. No-op without a pending
    marker."""
    if _read_pending(files_dir) is None:
        return False
    from bot_runtime import _rpc
    open_rpc = _rpc_factory or _rpc
    rpc, dc = open_rpc(native_lib_dir, files_dir)
    try:
        _remove_configured_accounts(dc)
    finally:
        try:
            rpc.close()
        except Exception:
            pass
    _clear_import_leftovers(files_dir)
    return True


def import_migration(native_lib_dir, files_dir, bundle_path, code,
                     _rpc_factory=None):
    """One-call import (prepare + commit) for hosts without a settings
    apply step; the staged settings snapshot is left for the activity.
    itdapk's BotImport uses the two-phase path so a settings failure
    aborts the whole import instead of stranding it."""
    addr = import_prepare(native_lib_dir, files_dir, bundle_path, code,
                          _rpc_factory=_rpc_factory)
    import_commit(native_lib_dir, files_dir, prefs_applied=False,
                  _rpc_factory=_rpc_factory)
    return addr

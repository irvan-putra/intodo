"""ON-DEVICE EMULATOR QUALIFICATION FIXTURE - test support only.

Never imported by app code (bot_runtime, bot_validation, migration).
Its ONLY callers are the androidTest emulator qualification
(MigrationEmu*Test) and the desktop preflight (ci/emu_preflight.py).

What it does: creates a DELTA CHAT ACCOUNT THAT NEVER TOUCHES A SERVER.
Provisioning normally means add_transport_from_qr() against a real
chatmail relay (network, real server-side identity). The fixture instead
mirrors core's own test helper add_pseudo_transport() (transport.rs,
used by core's smtp/configure/key unit tests but not exposed over
JSON-RPC): it inserts the transports row directly into the account's
plaintext SQLite DB while the rpc channel is closed. With the transport
row present, core self-creates the keypair on first use exactly as it
would for a chatmail account, is_configured() is true, and the real
export_backup / import_backup legs run fully offline.

The account is fixture-only: addr @emu.fixture, password is a constant
non-credential, and nothing is configured to connect (IMAP/SMTP server
lists are EMPTY in the pseudo transport, and no tested path starts I/O).
This is NOT network isolation: the app keeps its INTERNET permission and
no firewall blocks the emulator - the fixture guarantee is only that no
server is configured and no I/O is started.
"""

import glob
import json
import os
import sqlite3

FIXTURE_ADDR = "fixture-bot@emu.fixture"
FIXTURE_PW = "emu-fixture-not-a-real-password"


def _pseudo_transport_rows(addr):
    # Exact shapes from core transport.rs add_pseudo_transport():
    # entered_param is EnteredLoginParam with defaults; configured_param
    # is the ConfiguredLoginParamJson with EMPTY imap/smtp vectors.
    entered = {
        "addr": addr,
        "imap": {"server": "", "port": 0, "folder": "",
                 "security": "Automatic", "user": "", "password": ""},
        "smtp": {"server": "", "port": 0,
                 "security": "Automatic", "user": "", "password": ""},
        "certificate_checks": "Automatic",
        "oauth2": False,
    }
    configured = {
        "addr": addr,
        "imap": [], "imap_user": "", "imap_password": "",
        "smtp": [], "smtp_user": "", "smtp_password": "",
        "certificate_checks": "Automatic", "oauth2": False,
    }
    return json.dumps(entered), json.dumps(configured)


def install_bounded_rpc_startup_diagnostics():
    """Replace the RPC startup wrapper only inside the instrumentation fixture.

    The pinned client reads stderr with an unbounded .read() after stdout EOF.
    On a still-live child that can hang the test and hide the cause. This
    test-only version preserves the client startup sequence, samples stderr
    using select/os.read with hard time and byte limits, records process poll
    and argv, then terminates the failed fixture child and joins its pipes.
    It is never imported by the production runtime.
    """
    import itertools
    import logging
    import select
    import subprocess
    import sys
    import time
    from deltachat_rpc_client.rpc import JsonRpcError, Rpc

    if getattr(Rpc.start, "_emu_bounded_diagnostics", False):
        return

    def bounded_start(self):
        popen_kwargs = {
            "stdin": subprocess.PIPE,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.PIPE,
        }
        if sys.version_info >= (3, 11):
            popen_kwargs["process_group"] = 0
        else:
            popen_kwargs["preexec_fn"] = os.setpgrp
        popen_kwargs.update(self._kwargs)
        self.process = subprocess.Popen(self.rpc_server_path, **popen_kwargs)

        self.id_iterator = itertools.count(start=1)
        self.event_queues = {}
        self.request_results = {}
        self.request_queue = __import__("queue").Queue()
        self.request_queue_closed = False
        self.closing = False
        from threading import Thread
        self.reader_thread = Thread(target=self.reader_loop)
        self.reader_thread.start()
        self.writer_thread = Thread(target=self.writer_loop)
        self.writer_thread.start()
        self.events_thread = Thread(target=self.events_loop)
        self.events_thread.start()

        try:
            system_info = self.get_system_info()
        except Exception as exc:
            proc = self.process
            pid = getattr(proc, "pid", None)
            try:
                cmdline = open("/proc/%s/cmdline" % pid, "rb").read(2048).replace(b"\0", b" ").decode(errors="replace").strip()
            except Exception as cmd_exc:
                cmdline = "unavailable: %s" % cmd_exc
            try:
                poll_before = proc.poll()
            except Exception as poll_exc:
                poll_before = "unavailable: %s" % poll_exc

            stderr_data = bytearray()
            stderr_note = ""
            pipe = getattr(proc, "stderr", None)

            def drain_stderr_until(deadline):
                """Read only ready bytes, under one total 8 KiB cap."""
                nonlocal stderr_note
                if pipe is None:
                    return
                try:
                    fd = pipe.fileno()
                    while len(stderr_data) < 8192 and time.monotonic() < deadline:
                        ready, _, _ = select.select([fd], [], [], max(0.0, deadline-time.monotonic()))
                        if not ready:
                            break
                        chunk = os.read(fd, min(4096, 8192-len(stderr_data)))
                        if not chunk:
                            break
                        stderr_data.extend(chunk)
                except Exception as read_exc:
                    if not stderr_note:
                        stderr_note = "stderr read diagnostic failed: %s" % read_exc

            # Sample before teardown, but never wait for bytes that are not ready.
            drain_stderr_until(time.monotonic() + 1.0)

            # A failed health-check is terminal for this test-only child.
            # Reap it so it cannot survive instrumentation teardown. Drain a
            # short final stderr tail after termination because some servers
            # emit their diagnostic only while exiting.
            try:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=1.0)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=1.0)
                poll_after = proc.poll()
                drain_stderr_until(time.monotonic() + 0.25)
            except Exception as cleanup_exc:
                poll_after = "cleanup failed: %s" % cleanup_exc
            self.closing = True
            try:
                self._shutdown_loops()
            except Exception as shutdown_exc:
                stderr_note = (stderr_note + "; " if stderr_note else "") + "pipe shutdown: %s" % shutdown_exc
            stderr_text = stderr_data.decode(errors="replace").strip()
            detail = (
                "pid=%r poll_before=%r poll_after_cleanup=%r cmdline=%r "
                "stderr_nonblocking=%r%s"
                % (pid, poll_before, poll_after, cmdline, stderr_text, "; " + stderr_note if stderr_note else "")
            )
            raise JsonRpcError("RPC startup health check failed: %s; bounded diagnostics: %s" % (exc, detail)) from exc

        logging.info(
            "RPC server ready. Core version: %s",
            system_info.get("deltachat_core_version", "unknown"),
        )

    bounded_start._emu_bounded_diagnostics = True
    Rpc.start = bounded_start


def seed_account(native_lib_dir, files_dir, addr=FIXTURE_ADDR):
    """Create the fixture account inside files_dir/accounts. Offline.

    Returns the address. Raises on any failure - a partially seeded
    fixture must never pass as provisioned."""
    from bot_runtime import _rpc
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        acct = dc.add_account()
        acct.set_config("addr", addr)
        acct.set_config("mail_pw", FIXTURE_PW)
    finally:
        rpc.close()
    # The rpc channel is closed (it holds the DB open otherwise). The
    # account DB is plaintext SQLite - verified against core v2.69.0.
    dbs = glob.glob(os.path.join(files_dir, "accounts", "*", "dc.db"))
    if len(dbs) != 1:
        raise RuntimeError("fixture expected exactly 1 account db, found %d"
                           % len(dbs))
    entered, configured = _pseudo_transport_rows(addr)
    con = sqlite3.connect(dbs[0])
    try:
        con.execute(
            "INSERT INTO transports (addr, entered_param, configured_param)"
            " VALUES (?, ?, ?)", (addr, entered, configured))
        # transport.rs update_configured_addr(): old cores fail importing
        # a backup without the deprecated configured_addr config row.
        con.execute(
            "INSERT OR REPLACE INTO config (keyname, value)"
            " SELECT 'configured_addr', addr FROM transports"
            " ORDER BY id LIMIT 1")
        con.commit()
    finally:
        con.close()
    # Prove it through core's own contract before returning: reopen and
    # require is_configured + a readable transport address.
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        accts = [a for a in dc.get_all_accounts() if a.is_configured()]
        if len(accts) != 1:
            raise RuntimeError(
                "fixture seed failed: %d configured accounts" % len(accts))
        from bot_runtime import _account_addr
        got = _account_addr(accts[0])
        if got != addr:
            raise RuntimeError("fixture addr mismatch: %r != %r"
                               % (got, addr))
    finally:
        rpc.close()
    return addr


def count_configured(native_lib_dir, files_dir):
    """Fixture assertion helper: how many configured accounts exist."""
    from bot_runtime import _rpc
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        return len([a for a in dc.get_all_accounts() if a.is_configured()])
    finally:
        rpc.close()

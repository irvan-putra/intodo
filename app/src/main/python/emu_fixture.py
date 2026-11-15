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

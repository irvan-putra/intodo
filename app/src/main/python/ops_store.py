"""Storage adapter #1 (v2): durable, multi-instance webxdc ops log.

v2 fixes three cross-layer findings:

- B1: serials are PER-INSTANCE (each webxdc instance numbers its updates
  from 1 in the bot's view). v1 keyed rows by bare serial with one global
  watermark, so a second instance's serials 1-3 collided with the first
  instance's and were silently dropped. v2 keys rows
  (instance_msg_id, serial, op_index), keeps a per-instance ingestion
  watermark, and gives every stored op a dense global `seq` used for
  restore cursors - restore highwaters and confirmed marks are global seqs,
  never instance serials.
- B1/B3 dedup: every op gets a canonical global identity
  (item, field, hlc, device, value) with a UNIQUE constraint, so the same
  op is stored exactly once no matter how often it is redelivered or echoed
  back, with no reliance on the app-side merge for dedup.
- B3 echo guard: ingestion only stores payloads shaped like app ops
  ({"ops": [...]}). Bot-origin restore envelopes (carrying the exact
  itd_restore_v1 marker) and exact-shape boot hints ({"list_id", "boot"})
  are consumed (watermark advances) but never stored as history, so the
  bot's own restore sends cannot re-enter as new ops - while a legitimate
  app payload that happens to contain keys like "nonce" is never dropped.
  v3 is ack-free: no restore_ack payloads exist on the wire at all.

Rows are per OP (an update may carry several ops); empty or control
updates create no rows, so counts/pending reflect real ops only.

Merging/conflict resolution stays with the app's canonical MergeDoc
(JVM/JS); this store guarantees durability, dedup, and replay only.
Serials are LOCAL to this bot profile's view of each instance - the same
numbering the app-side setUpdateListener(cb, serial) replays from.
Global ordering of history lives in the ops' HLC stamps, kept untouched
inside each op.

Local file today. The Turso cloud step is a constructor change to
TursoHTTPStore (same interface). Cloud path is untested until the user
creates/approves a Turso database.
"""

import json
import os
import re
import time

SCHEMA_V2 = """
CREATE TABLE IF NOT EXISTS webxdc_ops (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  instance_msg_id INTEGER NOT NULL,
  serial INTEGER NOT NULL,
  op_index INTEGER NOT NULL DEFAULT 0,
  op_key TEXT NOT NULL UNIQUE,
  payload TEXT NOT NULL,
  info TEXT,
  received_at INTEGER NOT NULL,
  UNIQUE(instance_msg_id, serial, op_index)
);
CREATE TABLE IF NOT EXISTS webxdc_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
"""

# Exact bot-origin marker: the ONLY payloads the store refuses as history
# are the bot's own restore envelopes, which always carry this exact key
# with this exact value. A legitimate app ops update may freely contain
# keys like "nonce" - only the exact marker triggers the echo guard.
RESTORE_MARKER = "itd_restore_v1"

_LEGACY_INSTANCE = -1  # rows migrated from the v1 single-instance schema

# Both digest implementations must agree byte-for-byte; JS numbers are
# IEEE-754 doubles, so integers beyond +/-(2^53 - 1) cannot be represented
# exactly in JS and would silently fork every future batch digest. Both
# validators enforce the same bound.
MAX_SAFE_INTEGER = 2 ** 53 - 1


def is_bot_envelope(payload):
    """True only for the bot's own restore envelopes (exact marker match)."""
    return isinstance(payload, dict) and payload.get(RESTORE_MARKER) is True


def is_boot(payload):
    """True only for an exact-shape boot hint: {"list_id", "boot"} and
    nothing else. Boot hints are control traffic, never history."""
    return (isinstance(payload, dict) and "boot" in payload
            and set(payload.keys()) <= {"list_id", "boot"})


def validate_op(op):
    """Canonical op domain: item/field/device non-empty strings; hlc an
    INTEGER within +/-MAX_SAFE_INTEGER (bool/float rejected: Python/JS
    serialize floats differently - 1e-05 vs 0.00001 - and JS cannot
    represent integers beyond 2^53-1 exactly, so either would fork the
    cross-language digest); value key PRESENT and str/int/bool/null (ints
    bounded the same way; floats rejected). Raises ValueError. The JS
    reference validates the identical domain."""
    if not isinstance(op, dict):
        raise ValueError("op must be an object")
    item, field = op.get("item"), op.get("field")
    if not isinstance(item, str) or not item:
        raise ValueError("op.item must be a non-empty string")
    if not isinstance(field, str) or not field:
        raise ValueError("op.field must be a non-empty string")
    stamp = op.get("stamp")
    if not isinstance(stamp, dict):
        raise ValueError("op.stamp must be an object")
    hlc, device = stamp.get("hlc"), stamp.get("device")
    if isinstance(hlc, bool) or not isinstance(hlc, int):
        raise ValueError("op.stamp.hlc must be an integer (floats fork the digest)")
    if abs(hlc) > MAX_SAFE_INTEGER:
        raise ValueError("op.stamp.hlc beyond +/-2^53-1 (JS cannot represent it exactly)")
    if not isinstance(device, str) or not device:
        raise ValueError("op.stamp.device must be a non-empty string")
    if "value" not in op:
        raise ValueError("op.value key must be present (use null) - a missing key "
                         "serializes differently across implementations")
    value = op["value"]
    if value is not None and not isinstance(value, (str, int, bool)):
        raise ValueError("op.value must be a string, integer, bool, or null "
                         "(floats fork the digest)")
    if isinstance(value, int) and not isinstance(value, bool) and abs(value) > MAX_SAFE_INTEGER:
        raise ValueError("op.value integer beyond +/-2^53-1 (JS cannot represent it exactly)")
    return op


def canonical_op(op):
    """The op in canonical key order (values untouched, HLC stamps intact)."""
    validate_op(op)
    return {
        "item": op["item"],
        "field": op["field"],
        "value": op.get("value"),
        "stamp": {"hlc": op["stamp"]["hlc"], "device": op["stamp"]["device"]},
    }


def op_key(op):
    """Canonical global identity of one op, for cross-instance dedup."""
    c = canonical_op(op)
    return json.dumps(
        [c["item"], c["field"], c["stamp"]["hlc"], c["stamp"]["device"], c["value"]],
        sort_keys=True, separators=(",", ":"),
    )


def extract_ops(payload):
    """App ops from an update payload, or None for bot/control payloads
    (exact marker envelopes and exact-shape boot hints only)."""
    if not isinstance(payload, dict) or is_bot_envelope(payload) or is_boot(payload):
        return None
    ops = payload.get("ops")
    if ops is None:
        return None
    if not isinstance(ops, list):
        raise ValueError("payload.ops must be a list")
    return [validate_op(o) for o in ops]


class OpsStore:
    def __init__(self, url="ops.db", auth_token=None):
        force_sqlite = os.environ.get("OPS_STORE_FORCE_SQLITE") == "1"
        if auth_token or url.startswith(("libsql://", "https://")):
            import libsql_experimental as libsql
            self.conn = libsql.connect(url, auth_token=auth_token) if auth_token else libsql.connect(url)
        elif not force_sqlite:
            try:
                import libsql_experimental as libsql
                self.conn = libsql.connect(url)
            except ImportError:
                force_sqlite = True
        if force_sqlite:
            # Termux/phone path: stdlib sqlite3 (libSQL is SQLite-compatible).
            import sqlite3
            self.conn = sqlite3.connect(url)
        self.conn.executescript(SCHEMA_V2)
        self._migrate_v1()

    # ---- single-list invariant ------------------------------------------------

    def bind_list(self, list_id):
        """Bind this store to exactly one list identity. Set-once: a
        conflicting rebind raises, so two lists can never share one store
        (the cross-list leak class stays closed even by misconfiguration)."""
        existing = self.get_meta("list_id")
        if existing is None:
            self.set_meta("list_id", list_id)
        elif existing != list_id:
            raise ValueError("store already bound to list %r (got %r)" % (existing, list_id))

    # ---- v1 -> v2 migration ------------------------------------------------

    def _migrate_v1(self):
        cols = [r[1] for r in self.conn.execute("PRAGMA table_info(webxdc_ops)").fetchall()]
        if "seq" in cols:
            return  # already v2 (or a fresh v2 database)
        # v1 layout: serial INTEGER PRIMARY KEY, payload = whole update.
        rows = self.conn.execute(
            "SELECT serial, payload, info, received_at FROM webxdc_ops ORDER BY serial"
        ).fetchall()
        self.conn.execute("ALTER TABLE webxdc_ops RENAME TO webxdc_ops_v1")
        self.conn.executescript(SCHEMA_V2)
        for serial, payload, info, received_at in rows:
            try:
                ops = extract_ops(json.loads(payload)) or []
            except Exception:
                ops = []
            for idx, op in enumerate(ops):
                self.conn.execute(
                    "INSERT OR IGNORE INTO webxdc_ops"
                    "(instance_msg_id, serial, op_index, op_key, payload, info, received_at)"
                    " VALUES (?,?,?,?,?,?,?)",
                    (_LEGACY_INSTANCE, serial, idx, op_key(op),
                     json.dumps(canonical_op(op), sort_keys=True, separators=(",", ":")),
                     info, received_at),
                )
        old_wm = self.conn.execute(
            "SELECT value FROM webxdc_meta WHERE key='last_serial'").fetchone()
        if old_wm:
            self.conn.execute(
                "INSERT INTO webxdc_meta(key,value) VALUES(?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                ("wm:%d" % _LEGACY_INSTANCE, old_wm[0]))
        # v1 restore state (delivery:v1) was keyed by v1 serials, which do not
        # map onto v2 seqs: drop it. No v1 database was ever deployed outside
        # tests; a real bot would re-register instances and replay idempotently.
        self.conn.execute("DELETE FROM webxdc_meta WHERE key IN ('last_serial','delivery:v1')")
        self.conn.execute("DROP TABLE webxdc_ops_v1")
        self.conn.commit()

    # ---- ingestion ---------------------------------------------------------

    def watermark(self, instance_msg_id):
        """Per-instance ingestion watermark (last consumed serial)."""
        row = self.conn.execute(
            "SELECT value FROM webxdc_meta WHERE key=?",
            ("wm:%d" % instance_msg_id,)).fetchone()
        return int(row[0]) if row else 0

    def apply_updates(self, instance_msg_id, updates):
        """Idempotently ingest get_webxdc_status_updates() output for ONE
        instance. Returns the number of NEW ops stored.

        Every update advances the per-instance watermark (it was consumed),
        but only app-op payloads create rows; bot envelopes and control
        payloads are skipped, and ops already known (canonical identity)
        dedup to zero - including the bot's own restore echo if it ever
        arrived app-shaped.
        """
        new = 0
        wm = self.watermark(instance_msg_id)
        for u in updates:
            serial = u["serial"]
            if serial <= wm:
                continue  # already consumed (core redelivery / replay overlap)
            ops = extract_ops(u.get("payload")) or []
            for idx, op in enumerate(ops):
                cur = self.conn.execute(
                    "INSERT OR IGNORE INTO webxdc_ops"
                    "(instance_msg_id, serial, op_index, op_key, payload, info, received_at)"
                    " VALUES (?,?,?,?,?,?,?)",
                    (instance_msg_id, serial, idx, op_key(op),
                     json.dumps(canonical_op(op), sort_keys=True, separators=(",", ":")),
                     u.get("info"), int(time.time())),
                )
                new += cur.rowcount
            if serial > wm:
                wm = serial
        self.conn.execute(
            "INSERT INTO webxdc_meta(key,value) VALUES(?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            ("wm:%d" % instance_msg_id, str(wm)),
        )
        self.conn.commit()
        return new

    # ---- replay ------------------------------------------------------------

    def ops_since(self, seq=0):
        """The canonical global stream (one entry per op) after `seq`."""
        rows = self.conn.execute(
            "SELECT seq, payload, info FROM webxdc_ops WHERE seq>? ORDER BY seq", (seq,)
        ).fetchall()
        return [(r[0], json.loads(r[1]), r[2]) for r in rows]

    def count(self):
        return self.conn.execute("SELECT count(*) FROM webxdc_ops").fetchone()[0]

    def count_since(self, seq):
        """Real ops after `seq` (serial gaps and empty/control updates never
        inflate this - rows exist only for actual ops)."""
        return self.conn.execute(
            "SELECT count(*) FROM webxdc_ops WHERE seq>?", (seq,)).fetchone()[0]

    # ---- generic meta (bot state persistence) -------------------------------

    def get_meta(self, key, default=None):
        row = self.conn.execute("SELECT value FROM webxdc_meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else default

    def set_meta(self, key, value):
        self.conn.execute(
            "INSERT INTO webxdc_meta(key,value) VALUES(?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)),
        )
        self.conn.commit()

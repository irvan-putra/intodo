"""Storage adapter #1b: Turso cloud over plain HTTPS (Python stdlib only).

Same v2 interface as OpsStore (per-op rows, per-instance watermarks,
canonical op dedup, bot-envelope echo guard - see ops_store.py), talking to
a Turso database's SQL-over-HTTP endpoint (POST /v2/pipeline, Bearer token;
official reference: https://docs.turso.tech/sdk/http/reference). urllib
only - no native wheel - so it runs on the phone as-is, unlike
libsql-experimental.

NOTE: v1 -> v2 schema migration is implemented in OpsStore only; a phone
deployment migrating a local v1 DB must open it once through OpsStore
before switching to this adapter. No v1 DB was ever deployed outside tests.

STATUS: protocol shaping and parsing are unit-tested against a local stub
that executes the SQL in sqlite3 (test_turso_store.py). It has NEVER run
against a real Turso database - none exists yet; creating one and minting a
token is the user's decision, and the token would live only in the bot's
environment on the phone.
"""

import json
import time
import urllib.request

from ops_store import canonical_op, extract_ops, op_key

SCHEMA_STATEMENTS = [
    "CREATE TABLE IF NOT EXISTS webxdc_ops ("
    "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
    "instance_msg_id INTEGER NOT NULL, "
    "serial INTEGER NOT NULL, "
    "op_index INTEGER NOT NULL DEFAULT 0, "
    "op_key TEXT NOT NULL UNIQUE, "
    "payload TEXT NOT NULL, "
    "info TEXT, "
    "received_at INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS webxdc_ops_instance_serial "
    "ON webxdc_ops(instance_msg_id, serial, op_index)",
    "CREATE TABLE IF NOT EXISTS webxdc_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
]


def _val(v):
    if v is None:
        return {"type": "null", "value": None}
    if isinstance(v, bool):
        return {"type": "integer", "value": "1" if v else "0"}
    if isinstance(v, int):
        return {"type": "integer", "value": str(v)}
    if isinstance(v, float):
        return {"type": "float", "value": str(v)}
    return {"type": "text", "value": str(v)}


def _cell(c):
    t, v = c["type"], c["value"]
    if t == "null":
        return None
    if t == "integer":
        return int(v)
    if t == "float":
        return float(v)
    return v  # text; we never store blobs


class TursoHTTPStore:
    def __init__(self, base_url, auth_token, opener=None):
        self.base = base_url.rstrip("/")
        self.token = auth_token
        self.opener = opener or urllib.request.urlopen
        self._pipeline([(s, ()) for s in SCHEMA_STATEMENTS])

    def _pipeline(self, stmts):
        reqs = [
            {"type": "execute", "stmt": {"sql": sql, "args": [_val(a) for a in args]}}
            for sql, args in stmts
        ]
        reqs.append({"type": "close"})
        req = urllib.request.Request(
            self.base + "/v2/pipeline",
            data=json.dumps({"requests": reqs}).encode(),
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"},
            method="POST",
        )
        with self.opener(req, timeout=30) as resp:
            data = json.loads(resp.read())
        out = []
        for r in data["results"][:-1]:  # last entry answers "close"
            if r["type"] != "ok":
                raise RuntimeError("turso pipeline error: %s" % json.dumps(r))
            res = r["response"]["result"]
            cols = [c["name"] for c in res["cols"]]
            rows = [[_cell(c) for c in row] for row in res["rows"]]
            out.append((cols, rows, res["affected_row_count"]))
        return out

    # ---- single-list invariant ------------------------------------------------

    def bind_list(self, list_id):
        """Same set-once single-list binding as OpsStore (the DeliveryBot
        binds at construction; without this method the gate cannot run on
        this backend at all)."""
        existing = self.get_meta("list_id")
        if existing is None:
            self.set_meta("list_id", list_id)
        elif existing != list_id:
            raise ValueError("store already bound to list %r (got %r)" % (existing, list_id))

    # ---- ingestion ---------------------------------------------------------

    def watermark(self, instance_msg_id):
        _, rows, _ = self._pipeline([
            ("SELECT value FROM webxdc_meta WHERE key=?", ("wm:%d" % instance_msg_id,))])[0]
        return int(rows[0][0]) if rows else 0

    def apply_updates(self, instance_msg_id, updates):
        """Idempotent ingest for ONE instance. Same semantics as OpsStore:
        watermark advances over every consumed update; only app-op payloads
        create rows; canonical op identity dedups globally."""
        if not updates:
            return 0
        wm = self.watermark(instance_msg_id)
        stmts = []
        for u in updates:
            serial = u["serial"]
            if serial <= wm:
                continue
            ops = extract_ops(u.get("payload")) or []
            for idx, op in enumerate(ops):
                stmts.append((
                    "INSERT OR IGNORE INTO webxdc_ops"
                    "(instance_msg_id, serial, op_index, op_key, payload, info, received_at)"
                    " VALUES (?,?,?,?,?,?,?)",
                    (instance_msg_id, serial, idx, op_key(op),
                     json.dumps(canonical_op(op), sort_keys=True, separators=(",", ":")),
                     u.get("info"), int(time.time())),
                ))
            if serial > wm:
                wm = serial
        stmts.append((
            "INSERT INTO webxdc_meta(key,value) VALUES(?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value "
            "WHERE CAST(webxdc_meta.value AS INTEGER) < CAST(excluded.value AS INTEGER)",
            ("wm:%d" % instance_msg_id, str(wm)),
        ))
        results = self._pipeline(stmts)
        return sum(affected for _, _, affected in results[:-1])

    # ---- replay ------------------------------------------------------------

    def ops_since(self, seq=0):
        _, rows, _ = self._pipeline([(
            "SELECT seq, payload, info FROM webxdc_ops WHERE seq>? ORDER BY seq", (seq,))])[0]
        return [(r[0], json.loads(r[1]), r[2]) for r in rows]

    def count(self):
        _, rows, _ = self._pipeline([("SELECT count(*) FROM webxdc_ops", ())])[0]
        return rows[0][0]

    def count_since(self, seq):
        _, rows, _ = self._pipeline([(
            "SELECT count(*) FROM webxdc_ops WHERE seq>?", (seq,))])[0]
        return rows[0][0]

    # ---- generic meta (bot state persistence) -------------------------------

    def get_meta(self, key, default=None):
        _, rows, _ = self._pipeline([("SELECT value FROM webxdc_meta WHERE key=?", (key,))])[0]
        return rows[0][0] if rows else default

    def set_meta(self, key, value):
        self._pipeline([(
            "INSERT INTO webxdc_meta(key,value) VALUES(?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)))])

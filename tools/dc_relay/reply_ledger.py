"""Deterministic reply bookkeeping for a DeltaChat bot bridge.

Network delivery itself can never be guaranteed. What can be made
deterministic is the bookkeeping around it:

- every incoming message is recorded durably;
- a reply is recorded as 'pending' BEFORE the send is attempted, so a crash
  mid-send can never look like "never tried";
- an equivalent reply (same incoming message, same text) is rejected before
  sending, so duplicates are impossible by construction;
- if a send's outcome is unknown (exception or timeout after the attempt),
  the plan stays in 'unknown' and blocks further attempts until the caller
  settles it - either reconcile() observes the actual reply in the chat and
  marks it 'sent', or mark_failed() records an authoritative transport
  failure. Absence of the reply in a possibly stale or partial observation
  settles nothing, so there are no blind retries.

This module is transport-agnostic on purpose: the caller performs the real
send through whatever DeltaChat RPC/client it runs, then reports the outcome
here. The ledger never touches the network and never stores message text -
only its SHA-256 hash.

File safety: the data file is always written to a temporary file that is
atomically renamed over the ledger, so a killed process cannot leave a
half-written file. Because a rename swaps the inode, the flock cannot live
on the data file itself; a stable sidecar lock file ("<ledger>.lock", never
replaced) serializes every read and mutation across processes. A missing or
corrupt ledger fails closed (LedgerError) rather than silently resetting to
"nothing seen".
"""

import fcntl
import hashlib
import json
import os
import tempfile
import time


class LedgerError(Exception):
    """Corrupt ledger, unknown plan, or an operation that is not safe."""


class DuplicateReply(LedgerError):
    """An equivalent reply already exists (pending, sent, or unknown)."""


class UnknownOutcome(LedgerError):
    """A previous send attempt's outcome is unknown; reconcile it first."""


def _hash(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _key(chat_id, msg_id):
    # unambiguous compound key: a JSON-escaped pair, so ids containing
    # separators can never collide ("a:b","c" vs "a","b:c")
    return json.dumps([str(chat_id), str(msg_id)], separators=(",", ":"))


_KNOWN_STATES = ("pending", "unknown", "sent", "failed")


def _scalar_id(v):
    return isinstance(v, (str, int)) and not isinstance(v, bool) and str(v) != ""


def _check_ids(chat_id, msg_id):
    """Public-API input gate: ids must be schema-valid BEFORE any mutation,
    so a bad call can never poison the file and trip validation on the next
    read. Rejects None, bools, empty strings and any non-str/non-int type."""
    if not _scalar_id(chat_id) or not _scalar_id(msg_id):
        raise LedgerError("invalid chat/msg ids: %r %r" % (chat_id, msg_id))


def _check_text(text):
    if not isinstance(text, str):
        raise LedgerError("text must be str, got %r" % type(text).__name__)


def _validate_data(data, path):
    """Full structural validation, run on every read and on the result of a
    migration before it is swapped in. Anything malformed raises LedgerError;
    a bad ledger is never reset, trimmed or worked around. Every public
    operation reads through this, so corrupt records (wrong types, missing
    fields, unknown states, keys inconsistent with their record) block ALL
    reads and writes - plan_reply included - until a human inspects the file."""
    if not isinstance(data, dict):
        raise LedgerError("ledger %s: top level is not an object" % path)
    inbound, replies = data.get("inbound"), data.get("replies")
    if not isinstance(inbound, dict) or not isinstance(replies, dict):
        raise LedgerError("ledger %s: inbound/replies missing or not objects" % path)
    for k, rec in inbound.items():
        if not isinstance(rec, dict) or not _scalar_id(rec.get("chat")) \
                or not _scalar_id(rec.get("msg")):
            raise LedgerError("ledger %s: malformed inbound record %r" % (path, k))
        if k != _key(rec["chat"], rec["msg"]):
            raise LedgerError(
                "ledger %s: inbound key %r inconsistent with stored ids" % (path, k))
    for pid, rec in replies.items():
        th = rec.get("text_hash") if isinstance(rec, dict) else None
        if not isinstance(rec, dict) or not isinstance(rec.get("inbound"), str) \
                or not _scalar_id(rec.get("chat")) or not _scalar_id(rec.get("msg")) \
                or not isinstance(th, str) or not th \
                or rec.get("state") not in _KNOWN_STATES:
            raise LedgerError("ledger %s: malformed reply record %r" % (path, pid))
        if pid != "%s:%s" % (rec["inbound"], th):
            raise LedgerError(
                "ledger %s: plan id %r inconsistent with its record" % (path, pid))
        if rec.get("state") == "sent" and not _scalar_id(rec.get("reply_msg")):
            # a settled 'sent' must carry the transport's message id -
            # is_answered on a null id would be a confirmation of nothing
            raise LedgerError(
                "ledger %s: sent plan %r has no transport message id" % (path, pid))
        try:
            pair = json.loads(rec["inbound"])
        except ValueError:
            raise LedgerError("ledger %s: unparseable inbound ref in %r" % (path, pid))
        if not isinstance(pair, list) or len(pair) != 2 \
                or [str(pair[0]), str(pair[1])] != [str(rec["chat"]), str(rec["msg"])]:
            raise LedgerError(
                "ledger %s: reply record %r ids inconsistent" % (path, pid))


class ReplyLedger:
    FORMAT_VERSION = 2

    def __init__(self, path):
        self.path = path
        self.lock_path = path + ".lock"
        if not os.path.exists(self.lock_path):
            open(self.lock_path, "a").close()
        # create-under-lock: two fresh processes must not both see "absent"
        # and have the second one's empty initial file overwrite the first
        # one's early mutations
        with open(self.lock_path, "r+") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX)
            try:
                if not os.path.exists(self.path):
                    self._swap({"version": self.FORMAT_VERSION,
                                "inbound": {}, "replies": {}})
                else:
                    self._migrate_locked()
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)

    def _migrate_locked(self):
        """Bring a legacy (pre-version) ledger to the current format.

        Legacy format had no version field, colon-joined "chat:msg" keys and
        plan ids ending in a truncated 12-hex digest. Migration recomputes
        every key and plan id from the STORED chat/msg fields inside each
        record, never from the key string. A record without authoritative
        stored ids, a reply whose inbound record is missing, an unknown
        state, or any collision fails closed with LedgerError and the file
        is left byte-unchanged - never silently dropped, never reset."""
        try:
            with open(self.path) as dfh:
                data = json.load(dfh)
        except json.JSONDecodeError as e:
            raise LedgerError("corrupt ledger %s: %s" % (self.path, e))
        if not isinstance(data, dict):
            raise LedgerError("ledger %s: top level is not an object" % self.path)
        version = data.get("version")
        if version == self.FORMAT_VERSION:
            return
        if version is not None:
            raise LedgerError(
                "ledger %s has unknown format version %r" % (self.path, version))
        old_inbound, old_replies = data.get("inbound"), data.get("replies")
        if not isinstance(old_inbound, dict) or not isinstance(old_replies, dict):
            raise LedgerError(
                "ledger %s: not a recognizable legacy ledger" % self.path)

        # Identity comes ONLY from the stored chat/msg fields of each record.
        # The legacy colon-joined key string is a lookup handle, never parsed
        # for identity: "a:b/c" splits ambiguously ("a","b/c" vs "a:b","c"),
        # so parsing it could silently re-key a record onto the wrong message.
        legacy_ids = {}
        new_inbound = {}
        for ik, rec in old_inbound.items():
            if not isinstance(rec, dict) or not _scalar_id(rec.get("chat")) \
                    or not _scalar_id(rec.get("msg")):
                raise LedgerError(
                    "legacy inbound %r lacks authoritative stored chat/msg" % (ik,))
            chat, msg = str(rec["chat"]), str(rec["msg"])
            if not isinstance(ik, str) or ik != "%s:%s" % (chat, msg):
                # the v3 key is derived as str(chat)+":"+str(msg); a key
                # that disagrees with the stored ids is corruption, not a
                # variant - reject, never guess which side is right
                raise LedgerError(
                    "legacy inbound key %r inconsistent with stored ids %r/%r"
                    % (ik, chat, msg))
            nk = _key(chat, msg)
            if nk in new_inbound:
                raise LedgerError("legacy inbound collision at %r" % (ik,))
            legacy_ids[ik] = (chat, msg)
            new_inbound[nk] = rec
        new_replies = {}
        for pid, rec in old_replies.items():
            if not isinstance(rec, dict):
                raise LedgerError("malformed legacy reply record %r" % (pid,))
            ref = rec.get("inbound")
            if not isinstance(ref, str) or ref not in legacy_ids:
                # a reply whose inbound record is missing has no authoritative
                # identity to migrate to - fail closed, file byte-unchanged
                raise LedgerError(
                    "legacy reply %r has no stored inbound identity" % (pid,))
            chat, msg = legacy_ids[ref]
            th = rec.get("text_hash")
            if not isinstance(th, str) or len(th) != 64:
                raise LedgerError("legacy reply %r lacks a full text hash" % (pid,))
            try:
                int(th, 16)
            except ValueError:
                raise LedgerError("legacy reply %r text hash is not hex" % (pid,))
            if not isinstance(pid, str) or pid != "%s:%s" % (ref, th[:12]):
                # v3 plan ids were ref+":"+truncated digest; a mismatch is
                # corruption - reject byte-unchanged rather than re-key it
                raise LedgerError(
                    "legacy plan id %r inconsistent with its record" % (pid,))
            if rec.get("state") not in _KNOWN_STATES:
                raise LedgerError(
                    "legacy reply %r has unknown state %r" % (pid, rec.get("state")))
            rec["chat"], rec["msg"] = chat, msg
            rec["inbound"] = _key(chat, msg)
            new_pid = "%s:%s" % (rec["inbound"], th)
            if new_pid in new_replies:
                raise LedgerError("legacy plan id collision at %r" % (pid,))
            new_replies[new_pid] = rec
        new_data = {"version": self.FORMAT_VERSION,
                    "inbound": new_inbound, "replies": new_replies}
        _validate_data(new_data, self.path)
        self._swap(new_data)

    # --- durable state, atomic + locked ---------------------------------

    def _swap(self, data):
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path) or ".")
        with os.fdopen(fd, "w") as fh:
            json.dump(data, fh, indent=1, sort_keys=True)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, self.path)

    def _locked_read(self, fh):
        try:
            with open(self.path) as dfh:
                data = json.load(dfh)
        except json.JSONDecodeError as e:
            raise LedgerError("corrupt ledger %s: %s" % (self.path, e))
        if data.get("version") != self.FORMAT_VERSION:
            raise LedgerError(
                "ledger %s is not format version %s" % (self.path, self.FORMAT_VERSION))
        _validate_data(data, self.path)
        return data

    def _mutate(self, fn):
        """fn(data) -> result, under the exclusive sidecar lock, atomically saved."""
        with open(self.lock_path, "r+") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX)
            try:
                data = self._locked_read(fh)
                result = fn(data)
                # post-mutation invariant: a bad mutation must be caught
                # before it can poison the file, whatever the caller passed
                _validate_data(data, self.path)
                self._swap(data)
                return result
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)

    def _get(self, fn):
        with open(self.lock_path) as fh:
            fcntl.flock(fh, fcntl.LOCK_SH)
            try:
                return fn(self._locked_read(fh))
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)

    # --- inbound ---------------------------------------------------------

    def record_inbound(self, chat_id, msg_id, sender, text):
        """Remember an incoming message. Idempotent for the same id.
        Returns True when the message is new, False when already recorded."""
        _check_ids(chat_id, msg_id)
        _check_text(text)

        def go(data):
            k = _key(chat_id, msg_id)
            if k in data["inbound"]:
                return False
            data["inbound"][k] = {
                "chat": chat_id, "msg": msg_id, "sender": sender,
                "text_hash": _hash(text), "ts": time.time(),
            }
            return True
        return self._mutate(go)

    def is_new_inbound(self, chat_id, msg_id):
        _check_ids(chat_id, msg_id)
        return self._get(lambda d: _key(chat_id, msg_id) not in d["inbound"])

    # --- reply lifecycle --------------------------------------------------

    def plan_reply(self, chat_id, msg_id, text):
        """Register the intent to reply, BEFORE sending. Returns plan id.

        One reply intent per inbound message, irrespective of body: ANY
        existing plan for this inbound raises - DuplicateReply for
        pending/sent, UnknownOutcome for an unresolved attempt - so a
        reworded reply cannot bypass dedupe and double-answer. The planned
        body is fixed at planning time; changing it requires settling the
        existing plan first (a transport-authoritative failure via
        mark_failed, then re-plan). Only a 'failed' plan may be re-planned.
        """
        _check_ids(chat_id, msg_id)
        _check_text(text)
        ik, th = _key(chat_id, msg_id), _hash(text)

        def go(data):
            for pid, r in data["replies"].items():
                if r["inbound"] != ik:
                    continue
                if r["state"] in ("pending", "sent"):
                    raise DuplicateReply("reply already %s as %s" % (r["state"], pid))
                if r["state"] == "unknown":
                    raise UnknownOutcome("reconcile %s before retrying" % pid)
            # full digest in the plan id: a truncated hash would let two
            # distinct replies collide and overwrite each other's record
            pid = "%s:%s" % (ik, th)
            prev = data["replies"].get(pid)
            # deterministic plan id: re-planning a settled 'failed' plan is the
            # same reply, so reuse the id and count the attempt instead of
            # spawning a parallel record
            data["replies"][pid] = {
                "inbound": ik, "chat": str(chat_id), "msg": str(msg_id),
                "text_hash": th, "state": "pending",
                "reply_msg": None, "ts": time.time(),
                "attempts": (prev.get("attempts", 0) if prev else 0) + 1,
            }
            return pid
        return self._mutate(go)

    # Allowed state transitions, enforced atomically under the lock.
    # 'sent' and 'failed' are settled: no late or repeated write may demote
    # or rewrite them (a sent plan can never become 'failed' and then be
    # re-planned into a duplicate). A failed plan is retried only through
    # plan_reply, which moves it back to 'pending' explicitly.
    _ALLOWED = {
        "mark_sent": ("pending", "unknown"),
        "mark_unknown": ("pending",),
        "mark_failed": ("pending", "unknown"),
    }

    def _transition(self, plan_id, op, **fields):
        def go(data):
            rec = data["replies"].get(plan_id)
            if rec is None:
                raise LedgerError("unknown plan %s" % plan_id)
            if rec["state"] not in ReplyLedger._ALLOWED[op]:
                raise LedgerError(
                    "%s not allowed from state %s" % (op, rec["state"]))
            rec.update(fields)
        self._mutate(go)

    def mark_sent(self, plan_id, reply_msg_id):
        """The transport confirmed the send; record its message id.
        Valid from pending or unknown; a settled plan rejects it.
        reply_msg_id must be the transport's real, non-empty message id -
        a null id would make 'sent' (and is_answered) a confirmation of
        nothing, so it is rejected before any transition."""
        if not _scalar_id(reply_msg_id):
            raise LedgerError("mark_sent requires the transport's message id")
        self._transition(plan_id, "mark_sent", state="sent", reply_msg=reply_msg_id)

    def mark_unknown(self, plan_id):
        """The send was attempted but the outcome is unknown (crash, timeout,
        ambiguous exception - including any error that may have followed
        server acceptance). Blocks any retry until reconcile() or
        mark_failed() settles it. Valid from pending only."""
        self._transition(plan_id, "mark_unknown", state="unknown")

    def mark_failed(self, plan_id, reason):
        """Settle a plan as failed. Only for a transport-authoritative
        terminal failure of this specific message (e.g. the message reaches
        the transport's own failed state, or the send call is rejected
        before acceptance). A timeout, an ambiguous exception, a missing
        acknowledgement or a reply absent from a possibly stale observation
        is NOT failure evidence: use mark_unknown for all of those."""
        if not reason:
            raise LedgerError("mark_failed requires the transport's reason")
        self._transition(plan_id, "mark_failed", state="failed", fail_reason=reason)

    def reconcile(self, plan_id, observed_messages):
        """Settle a pending/unknown plan against messages actually observed.

        Positive-only, evidence-checked. observed_messages is an iterable of
        dicts with keys: id, chat, outgoing, text, and quote (the id of the
        message this one replies to). A plan settles 'sent' only on a
        message that ALL of:
          - is outgoing (the transport's own authorship says WE sent it -
            a peer or injected message with the same text does not count);
          - is in the same chat as the plan's inbound message;
          - carries the planned reply text (hash match);
          - explicitly quotes the plan's inbound message - the exact
            association. A same-text outgoing message without that quote
            (an old unrelated bot reply, a coincidence) does NOT count, and
            there is no ordering fallback: id recency alone never proves
            association.
        If nothing matches, the plan stays unresolved and 'unresolved' is
        returned - a missing reply in a stale or partial observation is not
        proof of failure, and a later inbound message is not proof either.
        The only path to 'failed' is mark_failed() with an authoritative
        transport result. Settled plans are never demoted by observation.
        Transports that cannot expose quote/reply linkage cannot settle
        plans through reconcile at all - fail closed is the design."""
        rec = self._get(lambda d: d["replies"].get(plan_id))
        if rec is None:
            raise LedgerError("unknown plan %s" % plan_id)
        # settled plans are left untouched; observation never demotes them
        if rec["state"] not in ("pending", "unknown"):
            return rec["state"]
        th, chat, imsg = rec["text_hash"], rec["chat"], rec["msg"]
        for m in observed_messages:
            if not isinstance(m, dict):
                raise LedgerError("malformed evidence entry: %r" % (m,))
            mid = m.get("id")
            if mid is None or mid == "":
                continue  # no transport id - cannot record or correlate it
            if m.get("outgoing") is not True:
                continue  # strict: only the transport's own True counts
            if str(m.get("chat")) != chat:
                continue
            if m.get("quote") is None or str(m.get("quote")) != imsg:
                continue
            if _hash(m.get("text") or "") != th:
                continue
            self._transition(plan_id, "mark_sent", state="sent",
                             reply_msg=mid)
            return "sent"
        return "unresolved"

    # --- queries -----------------------------------------------------------

    def is_answered(self, chat_id, msg_id, text=None):
        """True when a reply to this incoming message is confirmed sent."""
        _check_ids(chat_id, msg_id)
        if text is not None:
            _check_text(text)
        ik = _key(chat_id, msg_id)
        th = _hash(text) if text is not None else None

        def go(d):
            for r in d["replies"].values():
                if r["inbound"] == ik and r["state"] == "sent":
                    if th is None or r["text_hash"] == th:
                        return True
            return False
        return self._get(go)

    def unresolved(self):
        """Plans needing reconcile() before anything else can proceed, as
        records {plan, chat, msg, state} (oldest first) so recovery code
        never has to read internal storage to find each plan's chat."""
        def go(d):
            recs = [r for r in d["replies"].values() if r["state"] in ("pending", "unknown")]
            recs.sort(key=lambda r: r.get("ts", 0))
            return [{"plan": "%s:%s" % (r["inbound"], r["text_hash"]),
                     "chat": r["chat"], "msg": r["msg"], "state": r["state"]}
                    for r in recs]
        return self._get(go)

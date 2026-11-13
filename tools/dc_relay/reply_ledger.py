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
    return "%s:%s" % (chat_id, msg_id)


class ReplyLedger:
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
                    self._swap({"inbound": {}, "replies": {}})
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)

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
                return json.load(dfh)
        except json.JSONDecodeError as e:
            raise LedgerError("corrupt ledger %s: %s" % (self.path, e))

    def _mutate(self, fn):
        """fn(data) -> result, under the exclusive sidecar lock, atomically saved."""
        with open(self.lock_path, "r+") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX)
            try:
                data = self._locked_read(fh)
                result = fn(data)
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
        return self._get(lambda d: _key(chat_id, msg_id) not in d["inbound"])

    # --- reply lifecycle --------------------------------------------------

    def plan_reply(self, chat_id, msg_id, text):
        """Register the intent to reply, BEFORE sending. Returns plan id.

        Raises DuplicateReply if an equivalent reply is pending or sent,
        UnknownOutcome if a previous attempt's outcome is unresolved.
        Only a plan explicitly marked 'failed' via mark_failed() may be
        re-planned.
        """
        ik, th = _key(chat_id, msg_id), _hash(text)

        def go(data):
            for pid, r in data["replies"].items():
                if r["inbound"] != ik or r["text_hash"] != th:
                    continue
                if r["state"] in ("pending", "sent"):
                    raise DuplicateReply("reply already %s as %s" % (r["state"], pid))
                if r["state"] == "unknown":
                    raise UnknownOutcome("reconcile %s before retrying" % pid)
            pid = "%s:%s" % (ik, th[:12])
            prev = data["replies"].get(pid)
            # deterministic plan id: re-planning a settled 'failed' plan is the
            # same reply, so reuse the id and count the attempt instead of
            # spawning a parallel record
            data["replies"][pid] = {
                "inbound": ik, "text_hash": th, "state": "pending",
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
        Valid from pending or unknown; a settled plan rejects it."""
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
        """Settle a pending/unknown plan against messages actually present in
        the chat: observed_messages is an iterable of (msg_id, text).
        Positive-only: if a message with the plan's text is found, the plan
        becomes 'sent' (no retry needed). If it is not found the plan stays
        unresolved and 'unresolved' is returned - a missing reply in a stale
        or partial observation is not proof of failure, and a later inbound
        message is not proof either. The only paths to 'failed' are
        mark_failed() with an authoritative transport result."""
        rec = self._get(lambda d: d["replies"].get(plan_id))
        if rec is None:
            raise LedgerError("unknown plan %s" % plan_id)
        # settled plans are left untouched; observation never demotes them
        if rec["state"] not in ("pending", "unknown"):
            return rec["state"]
        th = rec["text_hash"]
        for mid, text in observed_messages:
            if _hash(text) == th:
                self._transition(plan_id, "mark_sent", state="sent", reply_msg=mid)
                return "sent"
        return "unresolved"

    # --- queries -----------------------------------------------------------

    def is_answered(self, chat_id, msg_id, text=None):
        """True when a reply to this incoming message is confirmed sent."""
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
        """Plans needing reconcile() before anything else can proceed."""
        return self._get(lambda d: sorted(
            pid for pid, r in d["replies"].items() if r["state"] in ("pending", "unknown")))

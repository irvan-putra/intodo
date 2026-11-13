"""Witness module: independent observation of user-selected chats for
false-positive/false-negative REVIEW (never automatic verdicts).

Design rules:
- The bot's observation is INDEPENDENT EVIDENCE, not absolute truth.
  A message the bot did not observe is "unknown", never "false".
- No message is ever graded "false positive" or "false negative" by the
  bot. Mismatches surface as CANDIDATES for human review.
- Matching is by the protocol-stable RFC Message-ID (rfc724_mid via
  get_message_info_object). Content/timestamp matching is NOT used: it
  collides on repeats, transforms and skew. No stable id -> "unknown".
- Liveness: bounded windows with heartbeats (see WitnessStore). A crash
  freezes the window at the last heartbeat, so downtime can only ever
  grade "unknown", never "missed".
- Ack markers are SENDER-ATTRIBUTED CLAIMS, not verified facts. The
  marker actor is always the core-verified sender of the message
  carrying the marker, but the stage it names is only that sender's
  claim. Of the stages an actor may claim, only "received" is currently
  graded (GRADING_STAGES); every other stage is recorded as a claim and
  treated as "unknown" for review.
- A no-ack candidate is raised only where an ack obligation is
  GROUNDED: the observed message is a reply quoting the watched actor's
  own message, or it sits in a direct (1:1) chat between its sender and
  the watched actor. Any other message grades "unknown" - the witness
  does not assume a broadcast message obliges anyone to ack it.
- Delivery/read state of OTHER accounts is never inferred from this
  account's receipts.
- Reports are summaries (counts + message references), never raw
  message bodies, and stay on-device except where the user asks.
- Only chats the user explicitly opted in on the Android side are
  observed (witness_config.json, default: nothing). The ack actor being
  witnessed is named in that same local config, never hardcoded here.

Honest limits:
- A MsgsChanged event with msg_id==0 (bulk/whole-chat change) is NOT
  rescanned; observations affected only by bulk changes may be stale
  and can only grade "unknown".
- Pin coverage is NOT claimed: the python client's EventType enum has
  no PinnedMessagesChanged entry (core v2.69 emits it), so pin changes
  reach the witness at most as MsgsChanged info-messages.
- A recorded "deleted" event means LOCAL deletion on this device; it
  says nothing about server-side or peer-side deletion.
"""

import hashlib
import json
import os
import re
import time

# Explicit acknowledgment marker an observed account can put in a group
# message. Carries target + stage, e.g.:
#   [ack:aaaaaaaa-1111-4111-8111-111111111111@example.invalid:received]
# The marker is a claim BY ITS SENDER; the witness verifies who sent the
# carrying message (via the core), never the truth of the stage named.
ACK_RE = re.compile(r"\[ack:([^\s:\]]+@[^\s:\]]+):([a-z_]+)\]")

# Stages an actor may claim in a marker, in observation order.
STAGES = ("received", "downloaded", "inspected", "acknowledged",
          "sent", "delivered", "read", "played")

# Only "received" is currently grounded enough to grade: a sender can
# only truthfully claim its own receipt, and no other stage has a
# verified meaning here. Markers with any other stage are stored as
# sender-attributed claims and grade "unknown", never as stage facts.
GRADING_STAGES = ("received",)

# Retention bound (private data minimization): the log compacts itself
# once it passes MAX_BYTES, keeping the live windows and the newest
# records. The user can also delete all witness data (reset()).
MAX_BYTES = 256 * 1024
KEEP_MESSAGES = 500
KEEP_EVENTS = 1000
KEEP_ACKS = 500

# Liveness: a heartbeat marks the bot live as of its time; is_live_at
# counts a message within HEARTBEAT_SLACK seconds after the last
# heartbeat/stop. A crash freezes the end, so downtime beyond the slack
# can only grade "unknown", never a false "observed" or "missed".
HEARTBEAT_SLACK = 300
HEARTBEAT_PERSIST_EVERY = 60


class WitnessStore:
    """Durable JSONL observation log + liveness windows. One file per
    witnessed chat, append-only; recovery after a crash is just re-read."""

    def __init__(self, files_dir, chat_id):
        self.path = os.path.join(files_dir, "witness-%s.jsonl" % chat_id)
        self.messages = {}      # rfc724_mid -> record
        self.acks = []          # parsed ack markers (actor, target, stage, ts)
        self.events = []        # reactions/deletes referencing a mid
        self.live_windows = []  # {"start": ts, "end": ts, "open": bool}
        # Chat context used for grounded ack expectations:
        # {"type": chat_type str or None, "peer": address or None}.
        self.chat_context = {"type": None, "peer": None}
        self._last_hb_persist = 0
        self._load()

    # ---- persistence -----------------------------------------------------

    def _append(self, rec):
        with open(self.path, "a") as f:
            f.write(json.dumps(rec, sort_keys=True) + "\n")
        try:
            if os.path.getsize(self.path) > MAX_BYTES:
                self._compact()
        except OSError:
            pass

    def _load(self):
        if not os.path.exists(self.path):
            return
        with open(self.path) as f:
            for line in f:
                line = line.strip()
                if line:
                    self._apply(json.loads(line))

    def _apply(self, rec):
        kind = rec.get("kind")
        if kind == "message":
            self.messages.setdefault(rec["mid"], rec)
        elif kind == "ack":
            self.acks.append(rec)
        elif kind == "event":
            self.events.append(rec)
        elif kind == "chat_context":
            if not self.chat_context.get("type"):
                self.chat_context = {"type": rec.get("chat_type"),
                                     "peer": rec.get("peer")}
        elif kind == "live_start":
            self.live_windows.append(
                {"start": rec["ts"], "end": rec["ts"], "open": True})
        elif kind in ("live_heartbeat", "live_stop") and self.live_windows \
                and self.live_windows[-1]["open"]:
            w = self.live_windows[-1]
            w["end"] = max(w["end"], rec["ts"])
            if kind == "live_stop":
                w["open"] = False

    def set_chat_context(self, chat_type, peer_addr=None):
        """Record the chat's type/peer once (stable per chat); first
        write wins. Used to ground ack expectations in review()."""
        if self.chat_context.get("type"):
            return
        self.chat_context = {"type": chat_type or None,
                             "peer": peer_addr or None}
        self._append({"kind": "chat_context",
                      "chat_type": self.chat_context["type"],
                      "peer": self.chat_context["peer"]})

    def _compact(self):
        """Rewrite the log keeping liveness coverage and only the newest
        bounded set of records. Older observations are dropped, never
        archived elsewhere."""
        recs = []
        if self.chat_context.get("type"):
            recs.append({"kind": "chat_context",
                         "chat_type": self.chat_context["type"],
                         "peer": self.chat_context["peer"]})
        for w in self.live_windows:
            recs.append({"kind": "live_start", "ts": w["start"]})
            kind = "live_stop" if not w["open"] else "live_heartbeat"
            if w["end"] != w["start"]:
                recs.append({"kind": kind, "ts": w["end"]})
        msgs = list(self.messages.values())[-KEEP_MESSAGES:]
        recs.extend(self.acks[-KEEP_ACKS:])
        recs.extend(self.events[-KEEP_EVENTS:])
        # Message records fill the remaining byte budget, newest first.
        budget = MAX_BYTES - sum(len(json.dumps(r)) + 1 for r in recs)
        kept = []
        for m in reversed(msgs):
            n = len(json.dumps(m)) + 1
            if n > budget:
                break
            budget -= n
            kept.append(m)
        recs.extend(reversed(kept))
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            for r in recs:
                f.write(json.dumps(r, sort_keys=True) + "\n")
        os.replace(tmp, self.path)

    def reset(self):
        """User deletion control: drop all witness data for this chat."""
        self.messages = {}
        self.acks = []
        self.events = []
        self.live_windows = []
        self.chat_context = {"type": None, "peer": None}
        try:
            os.unlink(self.path)
        except FileNotFoundError:
            pass

    # ---- liveness --------------------------------------------------------

    def live_start(self, ts=None):
        rec = {"kind": "live_start", "ts": ts or int(time.time())}
        self._append(rec); self._apply(rec)

    def heartbeat(self, ts=None):
        """Mark the bot live as of ts. Cheap: persists at most once per
        HEARTBEAT_PERSIST_EVERY seconds; the in-memory end always moves."""
        ts = ts or int(time.time())
        self._apply({"kind": "live_heartbeat", "ts": ts})
        if ts - self._last_hb_persist >= HEARTBEAT_PERSIST_EVERY:
            self._last_hb_persist = ts
            self._append({"kind": "live_heartbeat", "ts": ts})

    def live_stop(self, ts=None):
        rec = {"kind": "live_stop", "ts": ts or int(time.time())}
        self._append(rec); self._apply(rec)

    def is_live_at(self, ts):
        return any(w["start"] <= ts <= w["end"] + HEARTBEAT_SLACK
                   for w in self.live_windows)

    # ---- observations ----------------------------------------------------

    def record_message(self, mid, sender_addr, sent_ts, viewtype,
                       has_attachment, quote_mid=None, text="",
                       local_id=None):
        """mid MUST be the protocol rfc724_mid; callers pass None when the
        core cannot resolve one and the message is then recorded as
        'unknown' identity (matchable never)."""
        rec = {"kind": "message", "mid": mid, "local_id": local_id,
               "sender": sender_addr,
               "sent_ts": sent_ts, "viewtype": str(viewtype),
               "attachment": bool(has_attachment),
               "quote_mid": quote_mid,
               "text_sha256": hashlib.sha256(
                   (text or "").encode("utf-8")).hexdigest()[:16]}
        if mid is not None:
            self._append(rec)
        self._apply(rec)
        return rec

    def mid_for_local_id(self, local_id):
        for mid, rec in self.messages.items():
            if rec.get("local_id") == local_id:
                return mid
        return None

    def record_event(self, kind, mid, actor_addr, ts, detail=""):
        """kind: 'reaction' or 'deleted'. Recorded against the message mid;
        reactions and deletions are events, not new messages."""
        rec = {"kind": "event", "event": kind, "mid": mid,
               "actor": actor_addr, "ts": ts, "detail": detail}
        self._append(rec); self._apply(rec)

    def record_acks_from_text(self, actor_addr, text, ts):
        """Parse explicit [ack:<mid>:<stage>] markers from a group message.
        Freeform text is NEVER scored: without a marker, nothing is an ack.
        actor_addr is the core-verified actual sender of the message
        carrying the marker - a marker can only claim for its own sender.
        The parsed stage is stored as that sender's CLAIM; review() only
        grades stages in GRADING_STAGES and treats the rest as unknown."""
        found = []
        for mid, stage in ACK_RE.findall(text or ""):
            if stage not in STAGES:
                continue
            rec = {"kind": "ack", "actor": actor_addr, "target": mid,
                   "stage": stage, "ts": ts}
            self._append(rec); self._apply(rec)
            found.append(rec)
        return found

    # ---- review (candidates, never verdicts) -----------------------------

    def _grounded_actors(self, msg, eligible, bot_addr):
        """Watched actors with a GROUNDED reason to ack this message:
        the message quotes one of their own messages (a reply to them),
        or this store's chat is a direct (1:1) chat between the sender
        and that actor. Anything else is an ungrounded expectation and
        must grade 'unknown', never a no-ack candidate."""
        out = []
        qmid = msg.get("quote_mid")
        for w in eligible:
            if qmid is not None and qmid in self.messages \
                    and self.messages[qmid].get("sender") == w:
                out.append(w)
            elif self.chat_context.get("type") == "Single" \
                    and w == bot_addr \
                    and self.chat_context.get("peer") == msg["sender"]:
                out.append(w)
        return out

    def review(self, watched_senders, bot_addr=None):
        """Compare observed messages against observed acks. Every output row
        is a CANDIDATE for human review; 'unknown' is the honest default.

        watched_senders: addresses whose ack behavior is being witnessed
        (from the local device config). The bot's own messages are never
        graded; an actor's marker on their own message is not an ack.
        Only markers with a stage in GRADING_STAGES count as acks; other
        stages are sender-attributed claims and grade 'unknown'."""
        rows = []
        for mid, msg in sorted(self.messages.items(),
                               key=lambda kv: kv[1]["sent_ts"]):
            if mid is None or msg["sender"] == bot_addr:
                continue
            eligible = [w for w in watched_senders if w != msg["sender"]]
            if not eligible:
                continue  # nobody else is watched; nothing to grade
            if not self.is_live_at(msg["sent_ts"]):
                rows.append({"mid": mid, "verdict": "unknown",
                             "reason": "bot not live at send time"})
                continue
            grounded = self._grounded_actors(msg, eligible, bot_addr)
            if not grounded:
                rows.append({"mid": mid, "verdict": "unknown",
                             "reason": "no grounded expectation that a "
                                       "watched actor would ack (not a "
                                       "reply to them, not their direct "
                                       "chat)"})
                continue
            acks = [a for a in self.acks if a["target"] == mid
                    and a["actor"] in grounded
                    and a["stage"] in GRADING_STAGES]
            if acks:
                rows.append({"mid": mid, "verdict": "ack-observed",
                             "stages": sorted({a["stage"] for a in acks})})
                continue
            claims = [a for a in self.acks if a["target"] == mid
                      and a["actor"] in grounded]
            if claims:
                rows.append({"mid": mid, "verdict": "unknown",
                             "reason": "actor marker seen with unsupported "
                                       "stage(s) %s - sender-attributed "
                                       "claim only, not a verified stage "
                                       "fact" % ",".join(sorted(
                                           {a["stage"] for a in claims}))})
                continue
            rows.append({"mid": mid,
                         "verdict": "candidate-no-ack",
                         "reason": "addressed to a watched actor while "
                                   "bot live, no received-stage marker "
                                   "seen; may be delay, marker absence, "
                                   "or bot gap"})
        # received-stage markers pointing at messages the bot never
        # observed are candidates too - but NOT proof of a false claim.
        known = set(self.messages)
        for a in self.acks:
            if a["target"] not in known and a["stage"] in GRADING_STAGES:
                rows.append({"mid": a["target"],
                             "verdict": "candidate-unmatched-ack",
                             "reason": "ack marker for a message the bot "
                                       "did not observe; may be bot gap "
                                       "or wrong target"})
        return rows

    def summary(self, watched_senders, bot_addr=None):
        rows = self.review(watched_senders, bot_addr=bot_addr)
        counts = {}
        for r in rows:
            counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
        return {"messages_observed": len(self.messages),
                "acks_observed": len(self.acks),
                "events_observed": len(self.events),
                "rows": counts, "candidates": rows}

    def write_summary(self, out_path, watched_senders):
        """Render the summary JSON the Android report view reads. Counts
        and message references only - no bodies, no raw logs."""
        data = self.summary(watched_senders)
        tmp = out_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f, indent=1, sort_keys=True)
        os.replace(tmp, out_path)
        return out_path


# ---------------------------------------------------------------------------
# Glue: hooks from the live bot loop into per-chat WitnessStores.
# Only chats the user explicitly opted in on the Android side
# (witness_config.json on device, default: nothing witnessed).

class BotWitness:
    CONFIG = "witness_config.json"
    CHATS_REQUEST = "WITNESS_CHATS_REQUEST"
    CHATS_OUT = "witness-chats.json"
    REPORT_REQUEST = "WITNESS_REPORT_REQUEST"
    REPORT_OUT = "witness-summary.json"
    RESET_REQUEST = "WITNESS_RESET_REQUEST"

    def __init__(self, account, files_dir, served_chat_ids):
        """account: rpc-client Account (bot's own). served_chat_ids()->set
        is used ONLY to list opt-in choices, never to widen observation."""
        self.account = account
        self.files_dir = files_dir
        self._served_chat_ids = served_chat_ids
        self.stores = {}       # chat_id -> WitnessStore
        self.local_index = {}  # (chat_id, local msg_id) -> rfc724_mid
        self._live_since = None
        self._config_cache = (None, None)  # (mtime, config)

    # ---- config (local device opt-in; default: witness nothing) ----------

    def _config(self):
        path = os.path.join(self.files_dir, self.CONFIG)
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            return {"chats": [], "watched": []}
        if self._config_cache[0] != mtime:
            try:
                data = json.load(open(path))
            except Exception:
                data = {}
            self._config_cache = (mtime, {
                "chats": [int(c) for c in data.get("chats", [])],
                "watched": [a for a in data.get("watched", []) if a]})
        return self._config_cache[1]

    def is_witnessed(self, chat_id):
        return int(chat_id) in self._config()["chats"]

    # ---- stores / liveness ------------------------------------------------

    def _store(self, chat_id):
        if chat_id not in self.stores:
            store = WitnessStore(self.files_dir, chat_id)
            self.stores[chat_id] = store
            self._load_chat_context(chat_id, store)
            if self._live_since is not None:
                store.live_start(self._live_since)
        return self.stores[chat_id]

    def _load_chat_context(self, chat_id, store):
        """Best-effort chat type/peer for grounded ack expectations. Any
        failure leaves the context unknown - messages then grade
        'unknown', never candidates."""
        if store.chat_context.get("type"):
            return
        try:
            info = self.account._rpc.get_basic_chat_info(self.account.id,
                                                         chat_id)
            ctype = str(info.get("chat_type") or "")
        except Exception:
            return
        peer = None
        if ctype == "Single":
            try:
                me = self._bot_addr()
                for cid in self.account._rpc.get_chat_contacts(
                        self.account.id, chat_id) or []:
                    addr = self._contact_addr(cid)
                    if addr and addr != me:
                        peer = addr
                        break
            except Exception:
                peer = None
        if ctype:
            store.set_chat_context(ctype, peer)

    def live_start(self, ts=None):
        self._live_since = ts or int(time.time())
        for chat_id in self._config()["chats"]:
            self._store(chat_id).live_start(self._live_since)

    def heartbeat(self):
        # Only existing stores are ticked: a heartbeat must never
        # resurrect a store after reset_all() deleted it.
        ts = int(time.time())
        for chat_id in list(self.stores):
            if self.is_witnessed(chat_id):
                self.stores[chat_id].heartbeat(ts)

    def live_stop(self):
        self._live_since = None
        for store in self.stores.values():
            store.live_stop()

    # ---- core access helpers ----------------------------------------------

    def _bot_addr(self):
        """Bot's own address (multi-transport first, legacy key fallback).
        Keep in sync with bot_runtime._account_addr."""
        try:
            for t in self.account.list_transports() or []:
                addr = (t or {}).get("addr")
                if addr:
                    return addr
            return self.account.get_config("addr")
        except Exception:
            return None

    def _rfc724_mid(self, message):
        """Protocol-stable Message-ID via the core's supported
        get_message_info_object (jsonrpc api.rs:1577). The python client
        exposes only the string form (Message.get_info), so this uses the
        raw rpc like the pin/reaction calls do. None on any failure -
        the message is then recorded with unknown identity."""
        try:
            info = message._rpc.get_message_info_object(
                self.account.id, message.id)
            return info.get("rfc724Mid")
        except Exception:
            return None

    def _contact_addr(self, contact_id):
        try:
            c = self.account._rpc.get_contact(self.account.id,
                                              int(contact_id))
            return c.get("address", "")
        except Exception:
            return ""

    @staticmethod
    def _sender_addr(snapshot):
        try:
            c = snapshot.message.get_sender_contact().get_snapshot()
            return c.get("address", "")
        except Exception:
            return ""

    # ---- recording ---------------------------------------------------------

    def _record(self, chat_id, msg, snap):
        """chat_id: int; msg: rpc-client Message; snap: dict-like snapshot."""
        if not self.is_witnessed(chat_id):
            return
        g = (lambda name: snap.get(name)) if hasattr(snap, "get") \
            else (lambda name: getattr(snap, name, None))
        mid = self._rfc724_mid(msg)
        quote_mid = None
        q = g("quote")
        if isinstance(q, dict):
            qid = q.get("message_id") or q.get("messageId")
            if qid:
                try:
                    qinfo = msg._rpc.get_message_info_object(
                        self.account.id, int(qid))
                    quote_mid = qinfo.get("rfc724Mid")
                except Exception:
                    quote_mid = None
        ts = int(g("timestamp") or g("date") or 0)
        text = g("text") or ""
        sender = self._sender_addr(snap) if not hasattr(snap, "get") else ""
        if not sender:
            sender = self._contact_addr(g("from_id"))
        store = self._store(chat_id)
        store.record_message(
            mid, sender, ts,
            g("view_type") or g("viewtype"),
            bool(g("file")), quote_mid=quote_mid, text=text,
            local_id=msg.id)
        if mid is not None:
            self.local_index[(chat_id, msg.id)] = mid
        # Ack markers: actor is the core-verified actual sender only.
        store.record_acks_from_text(sender, text, ts)

    def record_snapshot(self, snapshot):
        """NewMessage-event path: snapshot is the client's MessageSnapshot
        (attribute access, carries .message and .chat_id)."""
        self._record(snapshot.chat_id, snapshot.message, snapshot)

    def _record_by_id(self, chat_id, msg_id):
        """MsgsChanged path: the event carries only chat_id/msg_id (core
        v2.69 events.rs:87-93) - fetch the message through the core."""
        if not msg_id or not self.is_witnessed(chat_id):
            return
        try:
            msg = self.account.get_message_by_id(int(msg_id))
            snap = msg.get_snapshot()
        except Exception:
            return
        self._record(int(chat_id), msg, snap)

    # ---- hooks --------------------------------------------------------------

    def on_new_message(self, event):
        self.record_snapshot(event.message_snapshot)
        self.heartbeat()

    def on_msgs_changed(self, event):
        # Attachment downloads complete via MsgsChanged, not NewMessage.
        # The raw event has ONLY chat_id/msg_id (core v2.69
        # events.rs:87-93) - fetch, don't expect a snapshot field.
        # msg_id==0 means a bulk/whole-chat change: NOT rescanned
        # (honest limit: observations affected only by bulk changes may
        # be stale and can only grade "unknown").
        self._record_by_id(getattr(event, "chat_id", 0),
                           getattr(event, "msg_id", 0))
        self.heartbeat()

    def on_reactions_changed(self, event):
        chat_id = getattr(event, "chat_id", None)
        msg_id = getattr(event, "msg_id", None)
        if not chat_id or not msg_id or not self.is_witnessed(chat_id):
            return
        mid = self.local_index.get((chat_id, msg_id))
        if mid is None and chat_id in self.stores:
            mid = self.stores[chat_id].mid_for_local_id(msg_id)
        if mid is None:
            return  # message never observed with a stable id -> unknown
        try:
            reactions = self.account._rpc.get_message_reactions(
                self.account.id, msg_id) or {}
        except Exception:
            return
        # Core v2.69 contract (types/reactions.rs): per-actor reactions
        # live in reactionsByContact {contact_id: [emoji]}; the flat
        # reactions[] list carries only emoji/count/isFromSelf and has
        # NO actor data.
        store = self._store(chat_id)
        by_contact = reactions.get("reactionsByContact") or {}
        for contact_id, emojis in by_contact.items():
            addr = self._contact_addr(contact_id)
            for emoji in emojis or []:
                store.record_event("reaction", mid, addr,
                                   int(time.time()), emoji)
        self.heartbeat()

    def on_msg_deleted(self, event):
        # MsgDeleted means the message was deleted LOCALLY on this
        # device; it does not imply server-side or peer-side deletion.
        chat_id = getattr(event, "chat_id", None)
        msg_id = getattr(event, "msg_id", None)
        if not chat_id or not msg_id or not self.is_witnessed(chat_id):
            return
        mid = self.local_index.get((chat_id, msg_id))
        if mid is None and chat_id in self.stores:
            mid = self.stores[chat_id].mid_for_local_id(msg_id)
        if mid is None:
            return
        self._store(chat_id).record_event("deleted", mid, "",
                                          int(time.time()))
        self.heartbeat()

    # ---- app-driven report / deletion (file-request pattern, same as
    # the invite and publish watchers): the Android side writes a request
    # file, the bot answers with a summary file or clears data. No DM or
    # group command surface is added.

    def handle_app_requests(self):
        """Poll once; called by the witness watcher thread. Report and
        reset are scoped to the OPTED-IN witness chats only - never all
        bot data, never served chats the user did not select."""
        choice = os.path.join(self.files_dir, self.CHATS_REQUEST)
        if os.path.exists(choice):
            entries = []
            for cid in sorted(self._served_chat_ids()):
                name = ""
                try:
                    info = self.account._rpc.get_basic_chat_info(
                        self.account.id, cid)
                    name = info.get("name", "")
                except Exception:
                    pass
                entries.append({"chat_id": cid, "name": name,
                                "witnessed": cid in self._config()["chats"]})
            out = os.path.join(self.files_dir, self.CHATS_OUT)
            tmp = out + ".tmp"
            with open(tmp, "w") as f:
                json.dump(entries, f, indent=1)
            os.replace(tmp, out)
            os.unlink(choice)
        req = os.path.join(self.files_dir, self.REPORT_REQUEST)
        if os.path.exists(req):
            watched = self._config()["watched"]
            bot = self._bot_addr()
            combined = {"watched": watched,
                        "note": None if watched else
                                "observation only - no witnessed ack "
                                "actor configured on this device",
                        "chats": {}}
            for chat_id in self._config()["chats"]:
                if chat_id not in self.stores:
                    continue
                s = self.stores[chat_id].summary(watched, bot_addr=bot)
                if not watched:
                    s["candidates"] = []
                combined["chats"][str(chat_id)] = s
            out = os.path.join(self.files_dir, self.REPORT_OUT)
            tmp = out + ".tmp"
            with open(tmp, "w") as f:
                json.dump(combined, f, indent=1, sort_keys=True)
            os.replace(tmp, out)
            os.unlink(req)
        rst = os.path.join(self.files_dir, self.RESET_REQUEST)
        if os.path.exists(rst):
            self.reset_all()  # removes the RESET_REQUEST flag itself

    def reset_all(self):
        """Delete ALL persisted witness artifacts and in-memory state:
        every per-chat store file (including chats no longer opted in),
        report/choices outputs and pending request files. Nothing is
        kept and nothing is resurrected: heartbeats skip missing stores,
        and still-configured chats only start fresh at the next
        live_start."""
        for store in self.stores.values():
            store.reset()
        self.stores = {}
        self.local_index = {}
        self._live_since = None
        # Complete inventory of witness-owned files: per-chat stores and
        # their interrupted-compaction temp files
        # (witness-<id>.jsonl[.tmp]), the report/choices outputs and
        # their interrupted-write temp files, and all three pending
        # request files. witness_config.json (the user's opt-in
        # selection) is NOT observation data and is kept; nothing
        # outside these patterns is touched. RESET_REQUEST is included
        # so a DIRECT reset_all() call never leaves a pending flag.
        outputs = (self.CHATS_OUT, self.CHATS_OUT + ".tmp",
                   self.REPORT_OUT, self.REPORT_OUT + ".tmp",
                   self.CHATS_REQUEST, self.REPORT_REQUEST,
                   self.RESET_REQUEST)
        try:
            names = os.listdir(self.files_dir)
        except OSError:
            names = []
        for name in names:
            if (name.startswith("witness-")
                    and (name.endswith(".jsonl")
                         or name.endswith(".jsonl.tmp"))) \
                    or name in outputs:
                try:
                    os.unlink(os.path.join(self.files_dir, name))
                except OSError:
                    pass

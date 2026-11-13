"""Latest-version delivery + bot-synced continuity for itdxdc (PixelSocial pattern).

Delivery: the owner DMs the bot a new .xdc; the bot sends it into granted
chat(s). The new version appears in Delta Chat - no download, no manual
re-send (the fatigue this removes).

Continuity (v3, ACK-FREE - contract blocker):
the webxdc sandbox gives an app NO automatic authenticated channel back:
sendUpdate is an author-blind broadcast into the shared document, and
sendToChat is a user-mediated, editable, abortable draft. No ack the app
could send is therefore automatic, and any unauthenticated "applied up to
N" hint that the bot trusted as a cursor could starve a genuinely empty
instance of its history. So v3 does not ask for acks at all:

- Restore replays the FULL deduped history, in order, into a trusted
  instance on every authorized trigger. The app-side MergeDoc merge is
  idempotent (dedups by op HLC stamp - implementation-owner bootstrap
  tests 1-5 green), so replaying what an instance already has is harmless.
- There are NO cursors, confirmed marks, or ack expectations: nothing can
  starve a future restore, nothing grows per boot (5 boots = 0 state).
- Triggers: (a) right after publish_version delivers a new instance;
  (b) a boot hint {"list_id", "boot": version} from an ALREADY-TRUSTED
  instance, throttled per instance (default 300s) so a chat member
  spamming boot hints cannot flood mails; (c) the owner's DM "/restore".
- Boot hints NEVER register an unknown instance: any chat participant
  could attach a rogue app claiming a known list_id and would otherwise
  receive the full history. Registration stays origin-pinned: the bot's
  own publish, or the verified owner's owner_register command resolving
  chat/author/version through the core.

Replay batches are self-describing and integrity-checkable:
    {"itd_restore_v1": true, "list_id", "batch_highwater",
     "target_version", "nonce", "digest", "ops": [...]}
  - itd_restore_v1: exact bot-origin marker (the store's echo guard matches
    ONLY this key, so legitimate app payloads containing words like
    "nonce" are never dropped)
  - batch_highwater: the store's global seq of the batch's last op
    (informational; nothing authoritative depends on the app echoing it)
  - nonce: fresh per send (app-side duplicate-batch detection)
  - digest: canonical SHA-256 over the batch's ops so the app can verify
    batch integrity before merging. Rule (byte-verified against the JS
    reference on 9 fixtures): ops sorted by (item, field, hlc, device)
    with code-point string order; JSON with sorted keys, compact
    separators, all non-ASCII escaped \\uXXXX (astral as surrogate pairs).
    Op domain is narrowed to keep the two languages byte-identical:
    hlc must be an INTEGER; value must be str/int/bool/null. Floats are
    rejected on BOTH sides (Python "1e-05" vs JS "0.00001" would fork the
    digest). If the app ever needs floats, both implementations plus
    fixtures must change together first - surfaced as a user decision.

Hard safety rules (by design - not config):
- NEVER deletes messages: not the user's old app, not the bot's own old
  app. PixelSocial deletes its old app message on upgrade; we do not.
- Commands are accepted only from the configured owner's verified ADDRESS
  (Delta Chat contact address), never from a display name.
- The bot reads/writes ONLY granted chats (allowed_chat_ids) plus the
  owner's/registered principals' DM control channel.

Residual tradeoffs (surfaced for the user's decision, not silently
downgraded):
- Every restore is a full replay: cost is O(history) status updates
  (one mail per ~50 ops) per trigger. Fine at list-app scale; a future
  snapshot/compaction op could bound it, but only with owner sign-off.
- A chat member can force at most one replay per throttle interval by
  forging boot hints on a trusted instance - bounded mail volume, and the
  replayed data only ever goes to an instance whose chat already sees it.

Transport reality: profile create/join, instance receipt,
ordered persist, watermark resume, idempotent apply, and bot->app
send-back were verified live on desktop (deltachat-rpc-server 2.69.0) for
the single-instance v1 prototype. The v3 gating/dedup/replay design is
offline-proven only; the direct-RPC live harness exercises transport and
store but contains no real app, so the app half remains proven only by
the owner's app-side bootstrap tests. get_chat_media / get_webxdc_blob
are source-verified against 2.69.0 api.rs, not run live.
Phone runtime is unproven (native Android validation pending).
"""

import hashlib
import json
import logging
import re
import zipfile

from ops_store import RESTORE_MARKER

log = logging.getLogger("delivery-bot")

RESTORE_BATCH = 50       # ops per status update; the core coalesces status
                         # updates up to 100 KiB, so this is NOT one mail per batch
MIN_RESTORE_INTERVAL = 300  # seconds between boot-triggered restores per instance
# v2.1 bounded recovery (itd_restore_v2 envelopes): generation-frozen plans,
# queued-cursor resume, per-generation retry budget with cooldown, loud limits.
RESTORE_SCHEMA = "itd_restore_v2"
DIGEST_RULE = "canon-v1"     # canonical_digest below; pinned in the frozen plan
MAX_BATCHES = 1000           # fail loud above this, never silently truncate
MAX_BATCH_BYTES = 32768      # per-envelope safety cap; an oversize single op
                             # blocks the generation LOUDLY, never gets dropped
MAX_MISSING_HINT = 1000      # resume-hint missing-indices array cap
RESUME_BUDGET = 3            # resume attempts per generation per cooldown window
RESUME_COOLDOWN = 1800       # seconds; a new attempt window may open after this

ORIGIN_BOT = "bot"      # delivered by this bot via publish_version
ORIGIN_OWNER = "owner"  # explicitly named by the verified owner

# Strict manifest parse: the whole line must be exactly version = "..."
_VER_RE = re.compile(r"""^\s*version\s*=\s*("([^"]*)"|'([^']*)')\s*$""")


def _version_from_toml(raw):
    for line in raw.splitlines():
        m = _VER_RE.match(line)
        if m:
            return m.group(2) if m.group(2) is not None else m.group(3)
    return ""


def parse_manifest_version(xdc_path):
    """Read version from a .xdc's manifest.toml. Pure offline operation."""
    with zipfile.ZipFile(xdc_path) as z:
        return _version_from_toml(z.read("manifest.toml").decode())


def parse_manifest_version_from_blob(b64):
    """Same, from a get_webxdc_blob('manifest.toml') result.

    Core 2.69.0 returns base64 STANDARD_NO_PAD (api.rs: general_purpose::
    STANDARD_NO_PAD.encode), so re-pad before decoding.
    """
    import base64
    raw = base64.b64decode(b64 + "=" * (-len(b64) % 4)).decode()
    return _version_from_toml(raw)


def pack_list_identity(xdc_path, list_id, out_dir=None):
    """Return a bundle that carries list.json {"list_id": ...} at its root -
    the packaged expected identity the app pins itself to (contract with
    the app: the app reads this relative bundle file and
    rejects restore envelopes with a mismatched list_id; a legacy app
    without it stays UNBOUND and never auto-adopts a wire claim).

    - no list.json: writes a COPY with the file added (the owner's source
      bundle is never mutated) and returns the copy's path
    - list.json present and matching: returns the original path unchanged
    - list.json present but conflicting or malformed: refuses (ValueError)
    """
    import json as _json
    import os as _os
    import tempfile as _tempfile
    with zipfile.ZipFile(xdc_path) as z:
        names = z.namelist()
        if "list.json" in names:
            try:
                data = _json.loads(z.read("list.json").decode())
            except Exception as e:
                raise ValueError("list.json unreadable in %s: %s" % (xdc_path, e))
            if not isinstance(data, dict) or data.get("list_id") != list_id:
                raise ValueError(
                    "bundle %s already pinned to list %r, this bot serves %r - "
                    "refusing to repin silently" % (
                        xdc_path, data.get("list_id") if isinstance(data, dict) else None,
                        list_id))
            return xdc_path
        entries = {n: z.read(n) for n in names}
    out = _os.path.join(out_dir or _tempfile.mkdtemp(),
                        _os.path.basename(xdc_path))
    payload = _json.dumps({"list_id": list_id}, sort_keys=True,
                          separators=(",", ":")).encode()
    with zipfile.ZipFile(out, "w") as z:
        for n, blob in entries.items():
            z.writestr(n, blob)
        z.writestr("list.json", payload)
    # verify the packed output before it goes anywhere
    with zipfile.ZipFile(out) as z:
        written = _json.loads(z.read("list.json").decode())
        assert written == {"list_id": list_id}, "packed list.json verification failed"
        assert z.read("manifest.toml") == entries["manifest.toml"], "manifest must round-trip"
    return out


class CandidateUncertainError(Exception):
    """A candidate instance check could not be COMPLETED (transient RPC /
    read failure). Fail-closed: the caller must NOT treat the candidate as
    disqualified - an uncertain read is different from a candidate that was
    fully read and definitely does not qualify. Publish aborts the chat with
    an explicit retryable error instead of adopting an older bubble or
    sending a duplicate."""


def _is_missing_entry_error(exc, name):
    """True ONLY for the core's structured 'zip entry absent' error:
    JSON-RPC code -1 with the exact message 'no entry found for <name>'
    (real core 2.69 raises JsonRpcError(response["error"]), so args[0] is
    the error dict). A definite negative - the file is provably not in the
    webxdc. ANY other error (timeout, transport, different code/message)
    stays uncertain and must raise CandidateUncertainError instead."""
    err = exc.args[0] if getattr(exc, "args", None) else None
    return (isinstance(err, dict) and err.get("code") == -1
            and err.get("message") == "no entry found for %s" % name)


class DeliveryBot:
    """Sends new app versions and replays stored history into trusted instances.

    `account` is a deltachat_rpc_client Account (or a test fake with the same
    surface). `store` is the OpsStore holding THIS list's full history (bound
    to `list_id`; one store serves exactly one list). `owner_addr` is the
    verified Delta Chat address allowed to command the bot. `bot_addr` is
    this bot profile's own verified address. `allowed_chat_ids` is the
    explicit grant set. `list_id` is the pinned stable list identity (app
    name is NOT identity: it changes across versions and is easy to spoof).
    `now` is injectable for tests.
    """

    def __init__(self, account, store, owner_addr, allowed_chat_ids=(), list_id="",
                 bot_addr="", now=None, min_restore_interval=MIN_RESTORE_INTERVAL):
        if not list_id:
            raise ValueError("list_id is required: app name is not a stable identity")
        if not bot_addr:
            raise ValueError("bot_addr is required: registration and echo "
                             "scoping depend on the verified own address")
        self.account = account
        self.store = store
        self.store.bind_list(list_id)
        self.owner_addr = owner_addr
        self.bot_addr = bot_addr
        self.allowed_chat_ids = set(allowed_chat_ids)
        self.list_id = list_id
        self.min_restore_interval = min_restore_interval
        self._now = now or __import__("time").time
        # All mutable state below is persisted in the store's meta table after
        # every mutation. It is BOUNDED: one entry per trusted instance, one
        # timestamp, one counter - no per-send or per-boot growth.
        self._instances = {}      # msg id -> {"chat_id","list_id","version","origin","author"}
        self._last_restore = {}   # msg id -> epoch of last restore start (throttle)
        self._send_counter = {}   # msg id -> int (nonce freshness across restarts)
        self._last_listing = []   # last /instances discovery (numbered selection)
        self._pending_download = None  # {"msg_id","chat_id","attempts"}
        self._publishes = {}        # str msg id -> {targets, delivered, file, chat_id}
        self._pending_ingest = []   # owner-registered msg ids whose existing
                                    # update history has not been ingested yet
        self._plans = {}            # generation_id -> frozen RestorePlan dict
        self._gen_queue = {}        # generation_id -> {"queued","attempts",
                                    #   "window_start","state","instance"}
        self._load()

    # ---- crash-safe state persistence (OpsStore meta table) ---------------

    _META_KEY = "delivery:v3"

    def _persist(self):
        self.store.set_meta(self._META_KEY, json.dumps({
            "instances": self._instances,
            "last_restore": self._last_restore,
            "send_counter": self._send_counter,
            "last_listing": self._last_listing,
            "pending_download": self._pending_download,
            "publishes": self._publishes,
            "pending_ingest": self._pending_ingest,
            "plans": getattr(self, "_plans", {}),
            "gen_queue": getattr(self, "_gen_queue", {}),
        }))

    def _load(self):
        raw = self.store.get_meta(self._META_KEY)
        if not raw:
            return
        data = json.loads(raw)
        self._instances = {int(k): v for k, v in data.get("instances", {}).items()}
        self._last_restore = {int(k): v for k, v in data.get("last_restore", {}).items()}
        self._send_counter = {int(k): v for k, v in data.get("send_counter", {}).items()}
        self._last_listing = data.get("last_listing", [])
        self._pending_download = data.get("pending_download")
        self._publishes = data.get("publishes", {})
        self._pending_ingest = [int(m) for m in data.get("pending_ingest", [])]
        self._plans = data.get("plans", {})
        self._gen_queue = data.get("gen_queue", {})
        self._gen_queue = {g: q for g, q in self._gen_queue.items()
                           if g in self._plans}

    def pending_ingest(self):
        """Peek msg ids of owner-registered instances whose pre-registration
        update history still needs ingesting. Does NOT clear: an entry is
        only removed by ingest_done() AFTER the ingest committed, so a crash
        mid-ingest is retried on the next drain (ingest is idempotent via
        the store watermark/dedup)."""
        return list(self._pending_ingest)

    def ingest_done(self, msg_id):
        """Mark one pending-ingest entry as durably ingested."""
        if msg_id in self._pending_ingest:
            self._pending_ingest.remove(msg_id)
            self._persist()

    # ---- audience + identity gates ----------------------------------------

    def chat_allowed(self, chat_id):
        return chat_id in self.allowed_chat_ids

    def _register_trusted(self, chat_id, msg_id, version, origin, author):
        """Internal: only callable paths are publish_version (bot origin),
        owner_register (owner origin) and adopt_existing_instance (bot
        origin, recovered in-chat). There is no other way in."""
        if not version:
            raise ValueError("registered version must be non-empty (manifest-derived)")
        if not self.chat_allowed(chat_id):
            raise PermissionError("chat %s not in grant set" % chat_id)
        self._instances[msg_id] = {
            "chat_id": chat_id, "list_id": self.list_id,
            "version": version, "origin": origin, "author": author,
        }
        # Owner-origin registrations carry pre-registration history the
        # store has never seen: queue a one-shot full ingest (bot-origin
        # publishes instead get history replayed INTO them by the publish
        # flow itself).
        if origin == ORIGIN_OWNER and msg_id not in self._pending_ingest:
            self._pending_ingest.append(msg_id)
        self._persist()
        log.info("instance %s registered (origin=%s version=%s author=%s)",
                 msg_id, origin, version, author)

    def owner_register(self, sender_addr, msg_lookup, msg_id):
        """Owner command: explicitly trust an existing instance (e.g. the
        current live app the bot did not deliver).

        msg_lookup(msg_id) must resolve the message THROUGH THE CORE and
        return {"chat_id", "author", "viewtype", "version"} - the version
        read from the instance's manifest via get_webxdc_blob. The caller
        supplies only the msg id; chat, author and version are derived from
        the resolved message/manifest, never from caller parameters. The
        instance author must be the owner or the bot itself: the owner
        cannot bless a third party's app into receiving this list's
        history. An unresolved (empty) version rejects.
        """
        if not self.is_authorized(sender_addr):
            log.warning("owner_register refused for non-owner %r", sender_addr)
            return False, "not owner"
        try:
            info = msg_lookup(msg_id)
        except Exception as e:
            return False, "message not resolvable: %s" % e
        if not info or info.get("viewtype") != "Webxdc":
            return False, "not a webxdc instance"
        if not self.chat_allowed(info.get("chat_id")):
            return False, "chat not granted"
        author = info.get("author", "")
        if author not in (self.owner_addr, self.bot_addr):
            return False, "instance author is neither owner nor bot"
        version = info.get("version", "")
        if not version:
            return False, "manifest version unresolved"
        self._register_trusted(info["chat_id"], msg_id, version, ORIGIN_OWNER, author)
        return True, "registered"

    def is_owner_dm(self, chat_id, chat_info_lookup):
        """Core-derived check that a chat is the owner's private 1:1 DM:
        chat_info_lookup(chat_id) -> {"chat_type": str, "contacts": [addr...]}
        resolved THROUGH THE CORE (get_basic_chat_info + get_contacts).
        A Single chat whose sole member is the owner. Group or broadcast
        chats with the owner present NEVER qualify: DM-ing a new .xdc is
        the publish command, and a file dropped into any other chat must
        never mass-publish."""
        info = chat_info_lookup(chat_id)
        if not info or info.get("chat_type") != "Single":
            return False
        return sorted(info.get("contacts", [])) == [self.owner_addr]

    # ---- pending large-attachment download (durable, idempotent) ---------

    MAX_DOWNLOAD_ATTEMPTS = 3

    def begin_pending_download(self, msg_id, chat_id):
        """Record a pending attachment download. 'duplicate' if already
        pending (callers must not re-issue download_full_message);
        'started' for a fresh attempt."""
        if self._pending_download and self._pending_download["msg_id"] == msg_id:
            return "duplicate"
        self._pending_download = {"msg_id": msg_id, "chat_id": chat_id,
                                  "attempts": 1}
        self._persist()
        return "started"

    def retry_pending_download(self):
        """Bounded retry of the recorded pending download (boot reconcile).
        'started' to retry, 'exhausted' (and cleared) past the bound."""
        pend = self._pending_download
        if not pend:
            return "none"
        if pend["attempts"] >= self.MAX_DOWNLOAD_ATTEMPTS:
            self._pending_download = None
            self._persist()
            return "exhausted"
        pend["attempts"] += 1
        self._persist()
        return "started"

    def pending_download(self):
        return self._pending_download

    def clear_pending_download(self):
        if self._pending_download is not None:
            self._pending_download = None
            self._persist()
            return True
        return False

    # ---- publish delivery tracking (no silent under-publish) -------------

    def begin_publish(self, msg_id, targets, file_path, dm_chat_id):
        """Start tracking a DM publish: per-target delivery is recorded as
        it happens, so a crash mid-loop leaves an honest, resumable record
        instead of a lost partial publish. False if already tracked."""
        key = str(msg_id)
        if key in self._publishes:
            return False
        self._publishes[key] = {"targets": sorted(int(t) for t in targets),
                                "delivered": [], "file": file_path,
                                "chat_id": dm_chat_id}
        self._persist()
        return True

    def publish_known(self, msg_id):
        return str(msg_id) in self._publishes

    def publish_mark_delivered(self, msg_id, chat_id):
        rec = self._publishes.get(str(msg_id))
        if rec and chat_id not in rec["delivered"]:
            rec["delivered"].append(chat_id)
            self._persist()

    def publish_remaining(self, msg_id):
        rec = self._publishes.get(str(msg_id))
        if not rec:
            return []
        return [t for t in rec["targets"] if t not in rec["delivered"]]

    def publish_complete(self, msg_id):
        return self.publish_known(msg_id) and not self.publish_remaining(msg_id)

    def drop_publish(self, msg_id):
        self._publishes.pop(str(msg_id), None)
        self._persist()

    def incomplete_publishes(self):
        return {int(k): v for k, v in self._publishes.items()
                if [t for t in v["targets"] if t not in v["delivered"]]}

    def instance_trusted(self, msg_id):
        return msg_id in self._instances

    # ---- command surface (DM control channel, core-verified sender) --------

    def is_authorized(self, sender_addr):
        return sender_addr == self.owner_addr

    def _core_lookup(self, msg_id):
        """Resolve chat/author/viewtype/version of a message THROUGH THE CORE
        (get_message + get_contact + get_webxdc_blob; the latter two are
        source-verified only, not yet run live)."""
        m = self.account._rpc.get_message(self.account.id, msg_id)
        chat_id = m.get("chatId", m.get("chat_id"))
        from_id = m.get("fromId", m.get("from_id"))
        contact = self.account._rpc.get_contact(self.account.id, from_id)
        viewtype = m.get("viewType", m.get("viewtype", ""))
        return {"chat_id": chat_id, "author": contact.get("address", ""),
                "viewtype": str(viewtype), "version": self.instance_version(msg_id)}

    def cmd_instances(self, sender_addr):
        """Owner command: numbered discovery of webxdc instances in granted
        chats (users do not know core message ids - they pick a number).
        The listing is persisted so /register <n> survives a restart."""
        if not self.is_authorized(sender_addr):
            return None
        entries = []
        for chat_id in sorted(self.allowed_chat_ids):
            for msg_id in self.list_app_instances(chat_id):
                try:
                    version = self.instance_version(msg_id)
                except Exception:
                    version = ""
                entries.append({"chat_id": chat_id, "msg_id": msg_id, "version": version})
        self._last_listing = entries
        self._persist()
        if not entries:
            return "no webxdc instances found in granted chats"
        lines = []
        for i, e in enumerate(entries, 1):
            status = "trusted" if e["msg_id"] in self._instances else "untrusted"
            lines.append("%d. v%s (%s)" % (i, e["version"] or "?", status))
        return "instances in granted chats:\n" + "\n".join(lines)

    def cmd_register_numbered(self, sender_addr, n):
        """Owner command: trust instance number n from the last /instances
        listing. Chat, author and version are still derived THROUGH THE
        CORE at registration - the number is only a selection UX."""
        if not self.is_authorized(sender_addr):
            return None
        if not (1 <= n <= len(self._last_listing)):
            return "no instance #%d - run /instances first" % n
        msg_id = self._last_listing[n - 1]["msg_id"]
        ok, reason = self.owner_register(sender_addr, self._core_lookup, msg_id)
        return ("instance #%d registered (%s)" % (n, reason)) if ok \
            else ("instance #%d refused: %s" % (n, reason))

    def handle_private_message(self, sender_addr, text, restore_cb=None):
        """Owner-only DM control channel. Returns a reply string or None.

        The webxdc app itself can never use this channel automatically
        (sendToChat is user-mediated) - this is for the owner's own client.
        """
        if not self.is_authorized(sender_addr):
            log.warning("ignored DM from non-owner address %r", sender_addr)
            return None
        cmd = (text or "").strip()
        if cmd == "/version":
            return "delivery bot online"
        if cmd == "/instances":
            return self.cmd_instances(sender_addr)
        if cmd.startswith("/register"):
            parts = cmd.split()
            if len(parts) == 2 and parts[1].isdigit():
                return self.cmd_register_numbered(sender_addr, int(parts[1]))
            return "usage: /instances then /register <number>"
        if cmd == "/restore" and restore_cb is not None:
            total = 0
            for msg_id in sorted(self._instances):
                total += restore_cb(msg_id)
            return "restore replayed %d op(s) into %d instance(s)" % (total, len(self._instances))
        return None

    # ---- delivery ----------------------------------------------------------

    def publish_version(self, chat, xdc_path, note=""):
        """Send a new .xdc into `chat` and register it (bot origin).

        Never deletes or replaces anything. Uses
        Chat.send_message(file=..., viewtype=Webxdc). Returns the new
        (trusted) message id. The
        caller typically follows with restore_into() so the fresh instance
        already holds full history when the owner first opens it (queued
        status updates are delivered on open).
        """
        if not self.chat_allowed(chat.id):
            raise PermissionError("publish refused: chat %s not in grant set" % chat.id)
        packed = pack_list_identity(xdc_path, self.list_id)
        version = parse_manifest_version(packed)
        if not version:
            raise ValueError("manifest version unresolved in %s" % xdc_path)
        text = note or ("itdxdc update v%s" % version)
        msg = chat.send_message(text=text, file=packed, viewtype="Webxdc")
        self._register_trusted(chat.id, msg.id, version, ORIGIN_BOT, self.bot_addr)
        log.info("published %s (v%s) to chat %s as msg %s", xdc_path, version, chat.id, msg.id)
        return msg

    def publish_and_restore(self, chat, xdc_path, send_update, note=""):
        """The headline flow, one call: publish the new .xdc into a granted
        chat, then replay the full history into the fresh instance so it is
        complete before anyone opens it. Returns (message, ops replayed)."""
        msg = self.publish_version(chat, xdc_path, note)
        sent = self.restore_into(msg.id, send_update)
        return msg, sent

    # ---- read-only version detection (source-verified, NOT run live) -------

    def registered_instance_for(self, chat_id, version):
        """msg id of an already-registered instance of `version` in `chat_id`,
        or None. Lets a retried publish COMPLETE into the existing instance
        (replay) instead of sending a duplicate bubble for the same version."""
        for mid, inst in self._instances.items():
            if inst.get("chat_id") == chat_id and inst.get("version") == version:
                return mid
        return None

    def list_app_instances(self, chat_id):
        """All webxdc message ids in a chat, oldest first. Raises
        CandidateUncertainError when the listing itself cannot be read."""
        try:
            return self.account._rpc.get_chat_media(self.account.id, chat_id, "Webxdc", None, None)
        except Exception as e:
            raise CandidateUncertainError(
                "webxdc listing for chat %s unreadable: %s" % (chat_id, e)) from e

    def instance_version(self, msg_id):
        """Version string from a live instance's manifest. Raises
        CandidateUncertainError when the blob cannot be READ; returns None
        when a successfully read manifest does not parse (definitely not a
        versioned instance)."""
        try:
            b64 = self.account._rpc.get_webxdc_blob(self.account.id, msg_id, "manifest.toml")
        except Exception as e:
            if _is_missing_entry_error(e, "manifest.toml"):
                return None  # provably no manifest: not a versioned instance
            raise CandidateUncertainError(
                "manifest of msg %s unreadable: %s" % (msg_id, e)) from e
        return parse_manifest_version_from_blob(b64)

    def instance_list_id(self, msg_id):
        """list.json pin from a live instance. Raises
        CandidateUncertainError when the blob cannot be READ (an unreadable
        pin is NOT the same as a missing pin); '' when a successfully read
        list.json is absent or unparseable (definitely not pinned)."""
        import base64
        try:
            b64 = self.account._rpc.get_webxdc_blob(self.account.id, msg_id,
                                                    "list.json")
        except Exception as e:
            if _is_missing_entry_error(e, "list.json"):
                return ""  # provably no pin file: definitely not pinned
            raise CandidateUncertainError(
                "list.json of msg %s unreadable: %s" % (msg_id, e)) from e
        try:
            data = json.loads(base64.b64decode(
                b64 + "=" * (-len(b64) % 4)).decode())
        except Exception:
            return ""
        return data.get("list_id", "") if isinstance(data, dict) else ""

    def instance_sent_by_self(self, msg_id):
        """True only when the core says THIS account authored the message
        (snapshot from_id == SELF contact; the client's AttrDict snapshot
        normalizes the raw camelCase RPC keys). Raises
        CandidateUncertainError when the snapshot cannot be READ - False is
        reserved for a successfully read, definitively different author, so
        a transient failure can never silently disqualify a candidate."""
        from deltachat_rpc_client import Message
        try:
            snap = Message(self.account, msg_id).get_snapshot()
        except Exception as e:
            raise CandidateUncertainError(
                "snapshot of msg %s unreadable: %s" % (msg_id, e)) from e
        from_id = (snap.get("from_id") if hasattr(snap, "get")
                   else getattr(snap, "from_id", None))
        return from_id == 1  # DC_CONTACT_SELF

    def adopt_existing_instance(self, chat_id, version):
        """Upgrade-gap recovery: adopt an ALREADY-DELIVERED instance of
        `version` in `chat_id` whose registration was lost (crash between
        send and persist). STRICT, all read-only checks:
          1. instance is a webxdc message IN THIS CHAT (chat-scoped listing)
          2. sender is the bot itself (core from_id == SELF)
          3. manifest parses to EXACTLY `version`
          4. list.json exists, parses, and pins EXACTLY this gate's list_id
        Among matching candidates the HIGHEST msg id (latest send) wins;
        replay is idempotent, nothing is sent or deleted. On adoption the
        instance is registered bot-origin and persisted immediately, so
        boot hints and replay work from this point on. Returns the adopted
        msg id, or None when every candidate was READ and none qualifies.
        Raises CandidateUncertainError when ANY candidate cannot be fully
        verified: fail-closed, never skip-and-adopt-older on doubt."""
        if not version or not self.chat_allowed(chat_id):
            return None
        matches = []
        for msg_id in self.list_app_instances(chat_id):
            if not self.instance_sent_by_self(msg_id):
                continue
            if self.instance_version(msg_id) != version:
                continue
            if self.instance_list_id(msg_id) != self.list_id:
                continue
            matches.append(msg_id)
        if not matches:
            return None
        chosen = max(matches)
        self._register_trusted(chat_id, chosen, version, ORIGIN_BOT,
                               self.bot_addr)
        # One-shot strict adoption ingest: the store has never seen this
        # instance's pre-adoption updates (edits made while it was
        # orphaned), and live incremental ingest only covers FUTURE
        # updates. Queue a full-history pull exactly once; the entry
        # clears only after the ingest commits (watermark advanced), so a
        # crash retries it and watermark/dedup make any retry a no-op.
        if chosen not in self._pending_ingest:
            self._pending_ingest.append(chosen)
            self._persist()
        log.info("instance %s adopted in chat %s (v%s, %d candidate(s))",
                 chosen, chat_id, version, len(matches))
        return chosen

    # ---- boot handling (hint only, throttled) -------------------------------

    def handle_boot(self, instance_msg_id, payload):
        """A boot hint NEVER authorizes an unknown instance.

        Routing after the trust gates (trusted instance, pinned list_id,
        exact registered version):
        - a "complete" claim equal to the current store generation for this
          instance -> "complete-current": verified no-op, no budget burn;
        - an active (or budget-exhausted) plan matching the CURRENT store
          state -> "resume-authorized": bounded requeue of that same
          generation, throttle-exempt, budget-gated (never a fresh budget);
        - otherwise the 300s throttle applies; a passed throttle means
          "restore-authorized" (a new generation is frozen and sent).
        A forged boot on a trusted instance can only cause bounded,
        budgeted re-replays into that same instance (data its chat already
        sees) - wasted mails, not a leak, and rate-limited.
        """
        inst = self._instances.get(instance_msg_id)
        if not inst:
            log.warning("boot from unknown instance %s ignored", instance_msg_id)
            return None
        if not isinstance(payload, dict) or payload.get("list_id") != self.list_id:
            log.warning("boot with wrong/missing list_id for %s ignored", instance_msg_id)
            return None
        # exact version equality: an empty or missing boot version must not
        # bypass the pin (registered versions are guaranteed non-empty)
        if payload.get("boot") != inst["version"]:
            log.warning("boot version %r != registered %r for %s",
                        payload.get("boot"), inst["version"], instance_msg_id)
            return None
        gen, _hw, _gd, _n = self._compute_generation(instance_msg_id)
        # Verified-complete claim: the app retains the generation whose
        # WHOLE-SET digest it verified. When that equals the current store
        # generation FOR THIS SAME INSTANCE, the member is provably up to
        # date: no-op with NO budget burn and NO throttle state change, so
        # healthy opens cost nothing. The claim only ever suppresses this
        # instance's own requeue - it never gates any other member, and a
        # stale or foreign claim simply falls through to the normal path.
        complete = payload.get("complete")
        if complete is not None:
            if not (isinstance(complete, str) and len(complete) == 64
                    and all(c in "0123456789abcdef" for c in complete)):
                log.warning("boot with malformed complete claim for %s; "
                            "treating as plain hint", instance_msg_id)
                complete = None
        if complete and complete == gen:
            return "complete-current"
        q = self._gen_queue.get(gen)
        if q and q.get("state") in ("active", "exhausted"):
            return "resume-authorized"
        elapsed = self._now() - self._last_restore.get(instance_msg_id, 0)
        if elapsed < self.min_restore_interval:
            log.info("boot for %s throttled (%.0fs < %ss)", instance_msg_id,
                     elapsed, self.min_restore_interval)
            return "throttled"
        return "restore-authorized"

    # ---- canonical digest (batch integrity on the wire) ----------------------

    @staticmethod
    def canonical_digest(ops):
        """SHA-256 over canonically ordered ops (insertion-order independent).

        Pinned rule (byte-verified against the JS reference on 9 fixtures):
        ops sorted by (item, field, hlc, device) with code-point string
        order; JSON with sorted keys, compact separators, all non-ASCII
        escaped \\uXXXX (astral as surrogate pairs); UTF-8 bytes. Op domain
        is integer hlc + str/int/bool/null value on BOTH sides - floats are
        rejected because Python and JS serialize them differently.
        """
        canon = sorted(
            ops,
            key=lambda o: (o.get("item", ""), o.get("field", ""),
                           o.get("stamp", {}).get("hlc", 0), o.get("stamp", {}).get("device", "")),
        )
        blob = json.dumps(canon, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(blob.encode()).hexdigest()

    def _next_nonce(self, instance_msg_id, highwater, digest):
        n = self._send_counter.get(instance_msg_id, 0) + 1
        self._send_counter[instance_msg_id] = n
        return "%s:%d:%s:%d" % (instance_msg_id, highwater, digest[:12], n)

    # ---- continuity: generation-frozen bounded replay (itd_restore_v2) ----

    def _compute_generation(self, instance_msg_id):
        """Deterministic generation identity for the CURRENT store state:
        (gen_id, highwater, generation_digest, op_count). Same store state ->
        same id, so a plain hint for an unchanged store resumes the SAME
        generation instead of pretending a fresh one. Whole-op-set digest
        uses the pinned canonical rule (insertion-order independent)."""
        ops = self.store.ops_since(0)
        highwater = ops[-1][0] if ops else 0
        flat = [op for _seq, op, _info in ops]
        generation_digest = self.canonical_digest(flat)
        version = self._instances[instance_msg_id]["version"]
        gen = hashlib.sha256(
            ("%s|%s|%s|%d|%s" % (self.list_id, instance_msg_id, version,
                                 highwater, generation_digest)).encode()).hexdigest()
        return gen, highwater, generation_digest, len(flat)

    def _split_batches(self, flat):
        """Freeze-time batching: count AND byte caps. Returns batch list, or
        None when one genuine op alone exceeds the byte cap - the caller then
        blocks LOUDLY instead of dropping or truncating it."""
        batches, cur, cur_bytes = [], [], 0
        for op in flat:
            b = len(json.dumps(op, sort_keys=True, separators=(",", ":")).encode())
            if b > MAX_BATCH_BYTES:
                return None
            if cur and (len(cur) >= RESTORE_BATCH or cur_bytes + b > MAX_BATCH_BYTES):
                batches.append(cur)
                cur, cur_bytes = [], 0
            cur.append(op)
            cur_bytes += b
        batches.append(cur)  # empty history -> [[]]: one empty completion batch
        if len(batches) > MAX_BATCHES:
            return None
        return batches

    def _freeze_plan(self, instance_msg_id):
        """Freeze and persist a RestorePlan for the current store state.
        Persisted BEFORE the first send - intent, never success. Returns the
        plan, or None when limits tripped (already logged loudly + blocked).
        """
        gen, highwater, generation_digest, op_count =             self._compute_generation(instance_msg_id)
        existing = self._plans.get(gen)
        if existing:
            return existing
        flat = [op for _seq, op, _info in self.store.ops_since(0)]
        batches = self._split_batches(flat)
        if batches is None:
            self._plans[gen] = {
                "instance": instance_msg_id, "list_id": self.list_id,
                "version": self._instances[instance_msg_id]["version"],
                "highwater": highwater, "op_count": op_count,
                "batch_sizes": [], "batch_size": RESTORE_BATCH,
                "schema": RESTORE_SCHEMA, "digest_rule": DIGEST_RULE,
                "generation_digest": generation_digest, "generation_id": gen,
                "created_at": self._now(),
            }
            self._gen_queue[gen] = {"queued": [], "attempts": 0,
                                    "window_start": self._now(),
                                    "state": "blocked", "instance": instance_msg_id}
            self._persist()
            log.error("restore BLOCKED for %s generation %s: %d op(s) exceed "
                      "count/byte caps; nothing sent, NOTHING dropped - this "
                      "needs an owner-visible fix, not silent truncation",
                      instance_msg_id, gen[:12], op_count)
            return None
        plan = {
            "instance": instance_msg_id, "list_id": self.list_id,
            "version": self._instances[instance_msg_id]["version"],
            "highwater": highwater, "op_count": op_count,
            "batch_sizes": [len(b) for b in batches],
            "batch_size": RESTORE_BATCH, "schema": RESTORE_SCHEMA,
            "digest_rule": DIGEST_RULE,
            "generation_digest": generation_digest, "generation_id": gen,
            "created_at": self._now(),
        }
        self._plans[gen] = plan
        self._gen_queue.setdefault(gen, {"queued": [], "attempts": 0,
                                         "window_start": self._now(),
                                         "state": "active",
                                         "instance": instance_msg_id})
        # a new current plan supersedes older plans for this instance
        for g, p in self._plans.items():
            if p["instance"] == instance_msg_id and g != gen:
                og = self._gen_queue.get(g)
                if og and og.get("state") == "active":
                    og["state"] = "superseded"
        self._persist()
        return plan

    def _plan_batches(self, plan):
        """Re-derive the frozen batches: append-only store sliced at the
        plan's highwater, split by the PLAN's frozen batch sizes (never the
        live constant), and verified against the plan's whole-op digest -
        fail-closed when the store no longer matches the frozen plan."""
        ops = [op for seq, op, _info in self.store.ops_since(0)
               if seq <= plan["highwater"]]
        if self.canonical_digest(ops) != plan["generation_digest"]:
            log.error("restore plan %s no longer matches the store; refusing "
                      "to send an inconsistent generation",
                      plan["generation_id"][:12])
            return None
        batches, i = [], 0
        for size in plan["batch_sizes"]:
            batches.append(ops[i:i + size])
            i += size
        return batches

    def _envelope(self, plan, index, ops):
        return {
            RESTORE_MARKER: True,
            "schema": plan["schema"],
            "list_id": plan["list_id"],
            "target_version": plan["version"],
            "generation_id": plan["generation_id"],
            "generation_digest": plan["generation_digest"],
            "highwater": plan["highwater"],
            "batch_index": index,
            "batch_count": len(plan["batch_sizes"]),
            "nonce": "%s:%d" % (plan["generation_id"][:12], index),
            "digest": self.canonical_digest(ops),
            "ops": ops,
        }

    def _send_batches(self, plan, send_update, indices):
        """Queue the given batch indices into the core; persist the queued
        cursor only AFTER the core accepted each send - never before, so a
        crash requeues instead of lying about coverage."""
        batches = self._plan_batches(plan)
        if batches is None:
            return 0
        q = self._gen_queue[plan["generation_id"]]
        sent = 0
        for i in indices:
            send_update(plan["instance"], self._envelope(plan, i, batches[i]), "")
            q["queued"] = sorted(set(q["queued"]) | {i})
            self._persist()
            sent += len(batches[i])
        return sent

    def _burn_attempt(self, gen):
        """One shared resume budget per generation per cooldown window
        (crash-resumes and hint-resumes burn the same budget). A window that
        expired opens ONE fresh bounded window - the cooldown is the bound
        that prevents both a permanent stuck state and a tight reset loop.
        Exhaustion is logged owner-visibly, never silent."""
        q = self._gen_queue[gen]
        now = self._now()
        if now - q["window_start"] >= RESUME_COOLDOWN:
            q["attempts"] = 0
            q["window_start"] = now
            if q["state"] == "exhausted":
                q["state"] = "active"
        if q["attempts"] >= RESUME_BUDGET:
            if q["state"] != "exhausted":
                q["state"] = "exhausted"
                self._persist()
            log.error("restore generation %s for %s EXHAUSTED its resume "
                      "budget (%d per %ds); nothing more is sent until the "
                      "cooldown passes - the app keeps showing its "
                      "incomplete state",
                      gen[:12], q["instance"], RESUME_BUDGET, RESUME_COOLDOWN)
            return False
        q["attempts"] += 1
        self._persist()
        return True

    def incomplete_generations(self):
        """(instance, gen_id) of active generations with un-queued batches -
        the crash-resume set for reconcile_on_boot."""
        out = []
        for gen, q in self._gen_queue.items():
            plan = self._plans.get(gen)
            if not plan or q.get("state") != "active":
                continue
            if len(q["queued"]) < len(plan["batch_sizes"]):
                out.append((plan["instance"], gen))
        return out

    def resume(self, instance_msg_id, send_update, gen=None, missing=None,
               crash=False):
        """Bounded requeue. A plain hint (gen None) requeues ALL indices of
        the current-store generation: a member that lost its whole local
        state must get the full generation even when the queued cursor says
        every batch was already sent (queued != delivered). An explicit gen
        with missing indices requeues exactly those; without missing, ALL.
        crash=True (bot restart reconcile) requeues only un-queued indices:
        the bot's own crash says nothing about member-side loss. An unknown
        or superseded gen falls forward to the CURRENT plan. Every non-empty
        requeue burns one shared-budget attempt; an empty computed set
        returns BEFORE any budget mutation. Throttle-exempt."""
        if instance_msg_id not in self._instances:
            log.warning("resume for untrusted instance %s ignored", instance_msg_id)
            return 0
        cur_gen, _hw, _gd, _n = self._compute_generation(instance_msg_id)
        plan = self._plans.get(gen) if gen else self._plans.get(cur_gen)
        if not plan or plan["instance"] != instance_msg_id \
                or self._gen_queue.get(plan["generation_id"], {}).get("state") == "superseded":
            plan = self._plans.get(cur_gen)
        if not plan:
            return 0
        gen_id = plan["generation_id"]
        total = len(plan["batch_sizes"])
        if missing:
            indices = sorted({i for i in missing
                              if isinstance(i, int) and 0 <= i < total})
        elif crash:
            indices = [i for i in range(total)
                       if i not in self._gen_queue[gen_id]["queued"]]
        else:
            indices = list(range(total))
        if not indices:
            return 0
        if not self._burn_attempt(gen_id):
            return 0
        sent = self._send_batches(plan, send_update, indices)
        log.info("resume %s into %s: requeued %d batch(es), %d op(s)",
                 gen_id[:12], instance_msg_id, len(indices), sent)
        return sent

    def handle_resume(self, instance_msg_id, payload, send_update):
        """Explicit resume hint {list_id, boot, resume, missing}: same trust
        gates as a boot hint (trusted instance, pinned list_id, exact
        version), then strict shape/limits validation before any requeue."""
        inst = self._instances.get(instance_msg_id)
        if not inst:
            log.warning("resume from unknown instance %s ignored", instance_msg_id)
            return 0
        if payload.get("list_id") != self.list_id:
            log.warning("resume with wrong/missing list_id for %s ignored",
                        instance_msg_id)
            return 0
        if payload.get("boot") != inst["version"]:
            log.warning("resume version %r != registered %r for %s",
                        payload.get("boot"), inst["version"], instance_msg_id)
            return 0
        gen = payload.get("resume")
        missing = payload.get("missing") or []
        if not isinstance(gen, str) or len(gen) != 64 \
                or any(c not in "0123456789abcdef" for c in gen):
            log.warning("resume with malformed generation id for %s ignored",
                        instance_msg_id)
            return 0
        if not isinstance(missing, list) or len(missing) > MAX_MISSING_HINT:
            log.warning("resume with oversized missing list for %s ignored",
                        instance_msg_id)
            return 0
        return self.resume(instance_msg_id, send_update, gen=gen, missing=missing)

    def restore_into(self, instance_msg_id, send_update):
        """Start a restore for a trusted instance: freeze a generation plan
        over the FULL history (tombstones too) and queue every un-queued
        batch. Ack-free: the app cannot send automatic authenticated acks on
        this transport, so nothing here waits for one. Idempotent: the app's
        MergeDoc dedups by op HLC stamp. Crash mid-send is recovered by
        resume() from the persisted queued cursor.

        No-op (0 sent) for an untrusted instance or a BLOCKED generation
        (loud, owner-visible; never silently truncated).
        """
        if instance_msg_id not in self._instances:
            log.warning("restore refused for untrusted instance %s", instance_msg_id)
            return 0
        plan = self._freeze_plan(instance_msg_id)
        if plan is None:
            return 0
        gen = plan["generation_id"]
        q = self._gen_queue[gen]
        if q.get("state") != "active":
            log.warning("restore into %s: generation %s is %s; use resume",
                        instance_msg_id, gen[:12], q.get("state"))
            return 0
        self._last_restore[instance_msg_id] = self._now()
        self._persist()  # throttle state durable even if the send crashes
        sent = self._send_batches(
            plan, send_update,
            [i for i in range(len(plan["batch_sizes"])) if i not in q["queued"]])
        log.info("restore into %s: generation %s, %d op(s) in %d batch(es) "
                 "(%s, ack-free)", instance_msg_id, gen[:12], sent,
                 len(plan["batch_sizes"]), RESTORE_SCHEMA)
        return sent

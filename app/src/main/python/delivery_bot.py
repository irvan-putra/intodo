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

RESTORE_BATCH = 50       # ops per status update; each update is a separate mail
MIN_RESTORE_INTERVAL = 300  # seconds between boot-triggered restores per instance

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
        """Internal: only callable paths are publish_version (bot origin) and
        owner_register (owner origin). There is no other way in."""
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

    def list_app_instances(self, chat_id):
        """All webxdc message ids in a chat, oldest first. Read-only."""
        return self.account._rpc.get_chat_media(self.account.id, chat_id, "Webxdc", None, None)

    def instance_version(self, msg_id):
        """Version string from a live instance's manifest. Read-only."""
        b64 = self.account._rpc.get_webxdc_blob(self.account.id, msg_id, "manifest.toml")
        return parse_manifest_version_from_blob(b64)

    # ---- boot handling (hint only, throttled) -------------------------------

    def handle_boot(self, instance_msg_id, payload):
        """A boot hint NEVER authorizes an unknown instance.

        Returns "restore-authorized" only when the instance is already
        trusted (bot-delivered or owner-named), the payload carries the
        pinned list_id, the version matches registration, and the throttle
        window has passed; "throttled" when a restore ran recently. A
        forged boot on a trusted instance can only cause one bounded,
        throttled re-replay into that same instance (data its chat already
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

    # ---- continuity: ack-free full replay -------------------------------------

    def restore_plan(self, from_seq=0):
        """Batches of ops to replay, in order, FULL history (tombstones too).

        Each batch is a list of (seq, op, info) tuples from the store's
        canonical global stream - HLC stamps inside ops untouched.
        """
        ops = self.store.ops_since(from_seq)
        return [ops[i:i + RESTORE_BATCH] for i in range(0, len(ops), RESTORE_BATCH)]

    def restore_into(self, instance_msg_id, send_update):
        """Replay the FULL history into a trusted app instance. Ack-free:
        the app cannot send automatic authenticated acks on this transport,
        so nothing here waits for one. Idempotent by design - the app's
        MergeDoc dedups by op HLC stamp, so replaying known ops is a no-op
        app-side. No cursor is kept: every trigger replays everything, so
        no cursor can ever starve a future restore, and no state grows per
        boot. Crash mid-replay is harmless: the next trigger replays the
        full history again.

        No-op (0 sent) for an untrusted instance.
        """
        if instance_msg_id not in self._instances:
            log.warning("restore refused for untrusted instance %s", instance_msg_id)
            return 0
        self._last_restore[instance_msg_id] = self._now()
        self._persist()  # throttle state durable even if the replay crashes
        sent = 0
        for batch in self.restore_plan(from_seq=0):
            ops = [op for _seq, op, _info in batch]
            if not ops:
                continue
            last_info = batch[-1][2] or ""
            digest = self.canonical_digest(ops)
            highwater = batch[-1][0]  # informational: global seq of last op
            nonce = self._next_nonce(instance_msg_id, highwater, digest)
            self._persist()  # nonce counter durable per batch: no reuse across a crash
            payload = {
                RESTORE_MARKER: True,
                "list_id": self.list_id,
                "batch_highwater": highwater,
                "target_version": self._instances[instance_msg_id]["version"],
                "nonce": nonce,
                "digest": digest,
                "ops": ops,
            }
            send_update(instance_msg_id, payload, last_info)
            sent += len(ops)
        log.info("restore into %s: replayed %d op(s) (full history, ack-free)",
                 instance_msg_id, sent)
        return sent
